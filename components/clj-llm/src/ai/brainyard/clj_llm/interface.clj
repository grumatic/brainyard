;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.clj-llm.interface
  "Public API for the clj-llm component.

   Provides a pure Clojure DSPy-style framework for structured LLM interactions:
   - Signature definitions with Malli schemas
   - Multi-provider LM configuration (OpenAI, Anthropic, Google, etc.)
   - Predict and chain-of-thought operations
   - JSON Schema structured output via Malli"
  (:require [ai.brainyard.clj-llm.core.signature :as signature]
            [ai.brainyard.clj-llm.core.schema :as schema]
            [ai.brainyard.clj-llm.core.schema-registry :as schema-registry]
            [ai.brainyard.clj-llm.core.prompt :as prompt]
            [ai.brainyard.clj-llm.core.providers :as providers]
            [ai.brainyard.clj-llm.core.llm :as llm]
            [ai.brainyard.clj-llm.core.predict :as predict-impl]
            [ai.brainyard.clj-llm.core.chain-of-thought :as cot-impl]
            [ai.brainyard.clj-llm.core.usage :as usage]
            [ai.brainyard.clj-llm.core.oauth :as oauth]))

;; ============================================================================
;; Schema Registry (defschemas)
;; ============================================================================

(defmacro defschemas
  "Define and register Malli schemas in the global mutable registry.

   Usage:
     (defschemas domain
       {::question [:string {:desc \"User question\"}]
        ::answer   [:string {:desc \"Answer\"}]})

   Registers all schemas and defs a var with the schema map."
  [symbol schema-map]
  `(schema-registry/defschemas ~symbol ~schema-map))

(def parse-malli-field
  "Normalize a field schema definition.
   Accepts either a raw Malli schema or [schema props] pair.
   Returns {:schema <malli-schema> :desc <string|nil> :default <value|nil>}."
  schema/parse-malli-field)

;; ============================================================================
;; Signature Definition
;; ============================================================================

(defmacro defsignature
  "Define a DSPy-style signature for structured LLM interactions.

   Usage:
     (defsignature QA
       \"Answer questions accurately.\"
       {:inputs  {:question [:string {:desc \"The question to answer\"}]}
        :outputs {:answer   [:string {:desc \"The answer\"}]}})

   Creates a var containing a compiled signature map with:
   - :name, :instructions, :inputs, :outputs
   - :input-keys, :output-keys
   - :output-json-schema (for LLM structured output)"
  [sig-name docstring fields-map]
  `(signature/defsignature ~sig-name ~docstring ~fields-map))

(def extract-signature-metadata
  "Extract input and output key lists from a compiled signature.
   Returns {:input-keys [...] :output-keys [...]}."
  signature/extract-signature-metadata)

;; ============================================================================
;; Operations (Multimethod)
;; ============================================================================

(defmulti execute-dspy-operation
  "Execute a DSPy operation by keyword.
   Dispatches on the operation keyword (:predict, :chain-of-thought).

   Usage:
     (execute-dspy-operation :predict signature inputs)
     (execute-dspy-operation :predict signature inputs {:lm-config lm})"
  (fn [operation & _args] operation))

(defmethod execute-dspy-operation :predict
  [_ signature inputs & [opts]]
  (predict-impl/predict signature inputs
                        :lm-config (:lm-config opts)
                        :usage-tracker (:usage-tracker opts)
                        :system-context (:system-context opts)
                        :on-chunk (:on-chunk opts)
                        :input-token-breakdown (:input-token-breakdown opts)))

(defmethod execute-dspy-operation :chain-of-thought
  [_ signature inputs & [opts]]
  (cot-impl/chain-of-thought signature inputs
                             :lm-config (:lm-config opts)
                             :usage-tracker (:usage-tracker opts)
                             :system-context (:system-context opts)
                             :on-chunk (:on-chunk opts)
                             :input-token-breakdown (:input-token-breakdown opts)))

;; ============================================================================
;; Operations (Direct)
;; ============================================================================

(defn predict
  "Execute a predict operation on a signature.

   signature  - Compiled signature (from defsignature)
   inputs     - Map of input values
   opts       - Optional kwargs, forwarded as-is to predict-impl:
                :lm-config, :usage-tracker, :system-context,
                :on-chunk, :stream?, :input-token-breakdown

   Returns {:outputs {<field> <value>} :usage {...}}"
  [signature inputs & {:as opts}]
  (apply predict-impl/predict signature inputs (mapcat identity opts)))

(defn chain-of-thought
  "Execute a chain-of-thought operation on a signature.

   Like predict, but includes step-by-step reasoning.

   signature  - Compiled signature (from defsignature)
   inputs     - Map of input values
   opts       - Optional kwargs, forwarded as-is to chain-of-thought-impl:
                :lm-config, :usage-tracker, :system-context,
                :on-chunk, :stream?, :input-token-breakdown

   Returns {:outputs {<field> <value>} :reasoning \"...\" :usage {...}}"
  [signature inputs & {:as opts}]
  (apply cot-impl/chain-of-thought signature inputs (mapcat identity opts)))

;; ============================================================================
;; LM Configuration
;; ============================================================================

(def create-lm
  "Create an LM configuration map.
   Options: :model, :api-key, :temperature, :max-tokens, :base-url, :provider"
  providers/create-lm)

(def configure-default-lm!
  "Set the global default LM configuration."
  providers/configure-default-lm!)

(def get-default-lm
  "Get the current global default LM configuration."
  providers/get-default-lm)

(def lm-initialized?
  "Return true if the default LM has a resolved API key."
  providers/lm-initialized?)

(def parse-lm-str
  "Parse an LM identifier string 'provider:model' into an LM instance via create-lm.
   Returns nil if the string is blank or create-lm throws."
  llm/parse-lm-str)

;; ============================================================================
;; Provider/Model Info
;; ============================================================================

(def providers
  "Registry of supported LLM providers with configuration.
   Map of provider-keyword -> {:api-key-env :base-url :message-format ...}."
  providers/providers)

(def get-provider-from-model
  "Determine the provider for a given model string."
  providers/get-provider-from-model)

(def get-popular-models
  "Get a curated list of popular models across providers."
  providers/get-popular-models)

(def get-models-by-provider
  "Get all known models grouped by provider."
  providers/get-models-by-provider)

(def detect-aws-region
  "Detect AWS region from env (AWS_REGION → AWS_DEFAULT_REGION) with
   fallback to the :bedrock provider's :default-region."
  providers/detect-aws-region)

(def aws-credentials-detected?
  "Return true if any AWS credential source is present in the environment
   (env vars, AWS_PROFILE/AWS_DEFAULT_PROFILE, IRSA token file, ECS task role,
   or ~/.aws/credentials)."
  providers/aws-credentials-detected?)

;; ============================================================================
;; Schema Utilities
;; ============================================================================

(def malli->json-schema
  "Convert a Malli schema to JSON Schema with OpenAI strict mode support."
  schema/malli->json-schema)

(def validate-output
  "Validate data against a Malli schema.
   Returns {:valid? bool :data data :errors [...]}"
  schema/validate-output)

;; ============================================================================
;; Low-level LLM
;; ============================================================================

(def chat-completion
  "Low-level chat completion call.
   (chat-completion lm-config messages :json-schema schema)
   (chat-completion lm-config messages :on-chunk callback)

   Options:
     :json-schema    - JSON Schema for structured output
     :max-retries    - Max retry attempts (default 3)
     :usage-tracker  - Atom from create-usage-tracker
     :on-chunk       - Callback fn for streaming. When provided, uses SSE
                       streaming and calls (on-chunk {:type :content-delta :text \"...\"})
                       for each delta, then (on-chunk {:type :done :usage {...}}).
                       The full response is still reconstructed and returned."
  llm/chat-completion)

(def ^{:doc "Re-export of `ai.brainyard.clj-llm.core.llm/*active-stream-register*`.
            See that var's docstring."}
  active-stream-register-var
  #'llm/*active-stream-register*)

(defmacro with-active-stream-register
  "Execute body with `*active-stream-register*` bound to `f`. `f` receives
   the open SSE BufferedReader on stream open and `nil` on close."
  [f & body]
  `(binding [llm/*active-stream-register* ~f]
     ~@body))

(def extract-content
  "Extract text content from an LLM response based on message format.
   (extract-content response lm-config)"
  llm/extract-content)

(def create-llm-query-fn
  "Create a single-shot sub-LLM query function.
   Returns a fn (prompt [sub-context]) → answer-string. See llm/create-llm-query-fn."
  llm/create-llm-query-fn)

(def create-llm-query-batched-fn
  "Create a concurrent sub-LLM query function (max 20 prompts).
   Returns a fn (prompts [sub-context]) → vector of answers. See llm/create-llm-query-batched-fn."
  llm/create-llm-query-batched-fn)

;; ============================================================================
;; Embeddings
;; ============================================================================

(def create-embedding
  "Create an embedding for a single text.
   (create-embedding lm-config text :model \"text-embedding-ada-002\")"
  llm/create-embedding)

(def create-embeddings
  "Create embeddings for multiple texts.
   (create-embeddings lm-config texts :model \"text-embedding-ada-002\")"
  llm/create-embeddings)

;; ============================================================================
;; Usage Tracking
;; ============================================================================

(def create-usage-tracker
  "Create a new usage tracker atom.
   Options: :history-cap (default 1000)"
  usage/create-usage-tracker)

(def get-usage-summary
  "Get cumulative usage summary from a tracker.
   Returns {:totals {...} :by-model {...}}"
  usage/get-usage-summary)

(def get-usage-history
  "Get call history from a tracker.
   Options: :model, :limit"
  usage/get-usage-history)

(def reset-tracker!
  "Reset a tracker to initial empty state."
  usage/reset-tracker!)

(def serialize-tracker
  "Return an EDN-safe snapshot of a tracker's state, suitable for
   pr-str / read-string round-trip. Returns nil for a nil tracker."
  usage/serialize-tracker)

(def hydrate-tracker!
  "Overwrite a tracker's state from a previously-serialized snap.
   No-op when tracker or snap is nil."
  usage/hydrate-tracker!)

(def merge-usage-summaries
  "Merge multiple usage summaries into one combined summary."
  usage/merge-usage-summaries)

(def last-input-tokens-with-delta
  "Given a seq of usage trackers, return {:last-input-tokens N
   :input-tokens-delta M-or-nil} for the most recent call across them.
   Returns nil when nothing has been recorded yet."
  usage/last-input-tokens-with-delta)

(def estimate-tokens
  "Estimate token count for a string using chars/4 heuristic."
  usage/estimate-tokens)

(def build-token-breakdown
  "Build per-category token breakdown from {category-kw text-or-nil}.
   Returns {category-kw {:text-length int :estimated-tokens int}}."
  usage/build-token-breakdown)

(def build-token-group
  "Build a hierarchical token group from a parts breakdown.
   Returns {:text-length total :estimated-tokens total :parts breakdown-map}."
  usage/build-token-group)

(def aggregate-breakdowns
  "Aggregate multiple token breakdowns into cumulative totals.
   Returns {category-kw {:estimated-tokens total :call-count n}}."
  usage/aggregate-breakdowns)

(def enable-global-tracking!
  "Enable global usage tracking for all chat-completion calls."
  providers/enable-global-tracking!)

(def disable-global-tracking!
  "Disable global usage tracking."
  providers/disable-global-tracking!)

(def get-global-tracker
  "Get the current global usage tracker, or nil if disabled."
  providers/get-global-tracker)

;; ============================================================================
;; Prompt Utilities
;; ============================================================================

(def build-messages
  "Build message list from a signature and inputs.
   (build-messages signature inputs opts)"
  prompt/build-messages)

(def build-messages-with-breakdown
  "Like build-messages, but also returns per-category token breakdown.
   Returns {:messages [...] :token-breakdown {...}}."
  prompt/build-messages-with-breakdown)

;; ============================================================================
;; OAuth Authentication (Anthropic Max/Pro Plan)
;; ============================================================================

(def oauth-authenticate!
  "Run OAuth 2.0 PKCE authentication flow for Anthropic Max/Pro plan.
   Opens browser for login, stores tokens locally.
   After authenticating, use (create-lm {:model \"claude-sonnet-4-6\" :provider :anthropic-max})
   to create an LM config that uses subscription auth instead of API key."
  oauth/authenticate!)

(def oauth-authenticated?
  "Check if OAuth tokens are stored and available."
  oauth/oauth-authenticated?)

(def oauth-logout!
  "Clear stored OAuth tokens."
  oauth/logout!)
