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
            [ai.brainyard.clj-llm.core.catalog :as catalog]
            [ai.brainyard.clj-llm.core.catalog-store :as catalog-store]
            [ai.brainyard.clj-llm.core.catalog-fetch :as catalog-fetch]
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

(def compile-signature
  "Compile a signature definition into a normalized signature map — the
   function `defsignature` expands to. Exposed for callers that must build a
   signature at RUNTIME rather than load time (e.g. an agent whose output
   fields depend on config). Same return shape as a `defsignature` var.

   `(compile-signature name instructions inputs outputs & [input-order])`"
  signature/compile-signature)

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

(def split-lm-str
  "Split an LM identifier string into [provider model]. Prefers the
   'provider/model' form (first '/'), falling back to legacy 'provider:model'
   (first ':') when no '/' is present."
  llm/split-lm-str)

(def parse-lm-str
  "Parse an LM identifier string into an LM instance via create-lm. Interpreted
   as 'provider/model' (preferred) or, with no '/', legacy 'provider:model'.
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

(def format-lm-label
  "Canonical 'provider/model' display label from a provider (keyword/string/nil)
   and a model (bare id, or itself a 'provider/model'/'provider:model' spec).
   Normalizes a combined model to the '/' form without mis-splitting a bare id
   that contains ':' (e.g. bedrock 'amazon.nova-lite-v1:0')."
  providers/format-lm-label)

(def get-popular-models
  "Get a curated list of popular models across providers."
  providers/get-popular-models)

(def list-models
  "Flat view of the model catalog with metadata. Opts: :provider (keyword),
   :curated? (boolean). Returns {:model :provider :curated? :curated-rank?
   :description? :region?} maps. Pure data — no network calls."
  providers/list-models)

;; ── Catalog refresh ────────────────────────────────────────────────────────
;; The baked catalog is the offline/first-run fallback; a refresh overlays it
;; with what each provider actually serves. Ids only — curation stays human.
;; See core.catalog for the full rationale.

(def set-catalog-cache-root!
  "Install the directory the refresh overlay is cached in. The app owns path
   policy, so it calls this at startup (cf. `persist/set-root!`). Until it is
   called, the catalog is simply the baked one."
  catalog-store/set-cache-root!)

(def load-catalog-overlay!
  "Load cached provider entries from disk and install them. Cheap, offline,
   never throws — safe on the startup path."
  catalog-store/load-overlay!)

(def refresh-catalog!
  "Refresh providers whose cache is stale, synchronously. Opts:
   :ttl-hours, :force?, :region. Returns the providers refreshed."
  catalog-fetch/refresh-stale!)

(def refresh-catalog-async!
  "Same as `refresh-catalog!` but on a daemon thread, guarded against
   concurrent runs. Returns true when it started one."
  catalog-fetch/refresh-stale-async!)

(def refreshable-providers
  "Providers this machine can refresh right now — enumerable, reachable, and
   credentialed where required."
  catalog-fetch/refreshable-providers)

(def catalog-drift
  "What the current overlay changes versus the baked catalog, per provider:
   {:retired [...] :discovered [...]}. Empty when nothing was refreshed."
  (fn [] (catalog/drift providers/model-catalog (catalog/overlay))))

(def catalog-overlay
  "The refresh overlay currently in force, provider -> entry."
  catalog/overlay)

(def get-models-by-provider
  "Get all known models grouped by provider, optionally filtered by :provider.

  (get-models-by-provider) => all models grouped by provider
  (get-models-by-provider {:provider :openai}) => only :openai models

  Throws ex-info with {:provider :available-providers} when :provider is
  given but not a known provider key."
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

(def classify-error
  "Classify an LLM-call exception into `{:class :malformed|:transient|:fatal
   :reason <str>}` so callers can pick re-prompt vs retry vs abort and show an
   accurate message. See `ai.brainyard.clj-llm.core.llm/classify-error`."
  llm/classify-error)

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
   (create-llm-query-fn lm-config usage-tracker [{:timeout-ms n}])
   Returns a fn (prompt [sub-context]) → answer-string. See llm/create-llm-query-fn."
  llm/create-llm-query-fn)

(def create-llm-query-batched-fn
  "Create a concurrent sub-LLM query function (max 20 prompts).
   (create-llm-query-batched-fn lm-config usage-tracker [{:timeout-ms n}]) —
   the timeout bounds both the batch wall clock and each call's request.
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
   Returns {:totals {...} :by-model {...} :by-agent {...}}.
   :by-agent is keyed by dispatching agent-type and is empty unless the caller
   ran the calls inside `with-usage-attribution*`."
  usage/get-usage-summary)

(def with-usage-attribution*
  "(with-usage-attribution* {:agent-id … :agent-type …} thunk) — record every
   LLM call `thunk` makes against that agent, feeding `get-usage-summary`'s
   :by-agent rollup. A nil attribution passes through without rebinding."
  usage/with-attribution*)

(def with-retry-listener*
  "(with-retry-listener* f thunk) — call `f` with
   `{:attempt :max :delay-ms :status :reason}` before every call-layer backoff
   sleep `thunk` triggers, so an HTTP-level retry is visible rather than a
   silent pause. A nil listener passes through without rebinding."
  llm/with-retry-listener*)

(def pricing-coverage
  "Which catalog models have a per-token price and which do not.
   Options: :catalog (default: the baked catalog), :curated-only?
   Returns {:priced [...] :unpriced [...] :counts {...}}."
  providers/pricing-coverage)

(def get-pricing
  "Per-1M-token rates for a [provider model] pair, or nil when unpriced.
   Returns {:input :output :cache-read :cache-write}."
  usage/get-pricing)

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

(def diff-usage-summaries
  "Field-wise subtract a baseline usage summary from a later one, returning a
   {:totals ...} summary of (later − baseline). nil baseline ⇒ result == later.
   Used to derive per-turn usage from two cumulative session-tracker snapshots."
  usage/diff-usage-summaries)

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
