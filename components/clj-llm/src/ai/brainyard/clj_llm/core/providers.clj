;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.clj-llm.core.providers
  "Multi-provider LM configuration, model catalogs, and provider detection."
  (:require [ai.brainyard.clj-llm.core.usage :as usage]))

;; ============================================================================
;; Provider Registry
;; ============================================================================

(def providers
  "Registry of supported LLM providers with configuration."
  {:openai      {:base-url             "https://api.openai.com/v1"
                 :api-key-env          "OPENAI_API_KEY"
                 :auth-header          "Bearer"
                 :supports-json-schema? true
                 :message-format       :openai}
   :anthropic   {:base-url             "https://api.anthropic.com/v1"
                 :api-key-env          "ANTHROPIC_API_KEY"
                 :auth-header          "x-api-key"
                 :supports-json-schema? false
                 :message-format       :anthropic
                 :prompt-cache         true}
   :google      {:base-url             "https://generativelanguage.googleapis.com/v1beta"
                 :api-key-env          "GOOGLE_API_KEY"
                 :auth-header          "Bearer"
                 :supports-json-schema? true
                 :message-format       :openai}
   :azure       {:base-url             nil ;; Set via AZURE_OPENAI_ENDPOINT
                 :api-key-env          "AZURE_OPENAI_API_KEY"
                 :auth-header          "api-key"
                 :supports-json-schema? true
                 :message-format       :openai}
   :groq        {:base-url             "https://api.groq.com/openai/v1"
                 :api-key-env          "GROQ_API_KEY"
                 :auth-header          "Bearer"
                 :supports-json-schema? true
                 :message-format       :openai}
   :together    {:base-url             "https://api.together.xyz/v1"
                 :api-key-env          "TOGETHER_API_KEY"
                 :auth-header          "Bearer"
                 :supports-json-schema? true
                 :message-format       :openai}
   :fireworks   {:base-url             "https://api.fireworks.ai/inference/v1"
                 :api-key-env          "FIREWORKS_API_KEY"
                 :auth-header          "Bearer"
                 :supports-json-schema? true
                 :message-format       :openai}
   :openrouter  {:base-url             "https://openrouter.ai/api/v1"
                 :api-key-env          "OPENROUTER_API_KEY"
                 :auth-header          "Bearer"
                 :supports-json-schema? true
                 :message-format       :openai}
   :ollama      {:base-url             "http://localhost:11434/v1"
                 :api-key-env          nil
                 :auth-header          nil
                 :supports-json-schema? false
                 :message-format       :openai
                 :default-model        "glm-5:cloud"}
   :free-llm    {:base-url             nil  ;; resolved from FREELLM_BASE_URL at create-lm time
                 :base-url-env         "FREELLM_BASE_URL"
                 :api-key-env          "FREELLM_API_KEY"  ;; optional — sent as Bearer if present
                 :auth-header          "Bearer"
                 :supports-json-schema? false  ;; conservative default for arbitrary free backends
                 :message-format       :openai
                 :default-model        "auto"}
   :mistral     {:base-url             "https://api.mistral.ai/v1"
                 :api-key-env          "MISTRAL_API_KEY"
                 :auth-header          "Bearer"
                 :supports-json-schema? true
                 :message-format       :openai}
   :deepseek       {:base-url             "https://api.deepseek.com/v1"
                    :api-key-env          "DEEPSEEK_API_KEY"
                    :auth-header          "Bearer"
                    :supports-json-schema? true
                    :message-format       :openai}
   :anthropic-max  {:base-url             "https://api.anthropic.com/v1"
                    :api-key-env          nil  ;; No API key - uses OAuth bearer token
                    :auth-type            :oauth
                    :auth-header          "Bearer"
                    :supports-json-schema? false
                    :message-format       :anthropic
                    :prompt-cache         true}
   :apple-fm       {:base-url             "http://localhost:11435/v1"
                    :api-key-env          nil
                    :auth-header          nil
                    :supports-json-schema? false
                    :message-format       :openai
                    :default-model        "apple-foundationmodel"}
   :claude-code    {:base-url              nil   ;; No HTTP — uses CLI subprocess
                    :api-key-env           nil
                    :auth-header           nil
                    :supports-json-schema? true
                    :message-format        :claude-code}
   :acp            {:base-url              nil   ;; No HTTP — drives an ACP agent over stdio
                    :api-key-env           nil
                    :auth-header           nil
                    :supports-json-schema? false ;; Phase 4 flattens turns; no structured-output path
                    :message-format        :acp
                    :default-backend       :stub}
   :bedrock        {:base-url              nil   ;; AWS SDK resolves endpoint per region
                    :api-key-env           nil   ;; Uses AWS credential chain, not API key
                    :auth-type             :aws-sigv4
                    :auth-header           nil
                    :supports-json-schema? false ;; Converse uses tool-use, not response_format
                    :message-format        :bedrock
                    :default-region        "us-east-1"}})

;; ============================================================================
;; Model Catalogs
;; ============================================================================

;; ============================================================================
;; Model Catalog — SINGLE SOURCE OF TRUTH
;; Each model: {:model id, :curated-rank int?, :description str?, :region str?}
;; :curated-rank <int> => curated (surfaced by get-popular-models, sorted by the int).
;;                        Absent/nil => not surfaced by the picker.
;; array-map preserves provider order; inner vectors preserve model order.
;; ============================================================================

(def model-catalog
  (array-map
   :claude-code
   [{:model "opus" :curated-rank 0 :description "Claude Opus (latest) via CLI (no API key)"}
    {:model "sonnet" :curated-rank 1 :description "Claude Sonnet (latest) via CLI (no API key)"}
    {:model "haiku" :curated-rank 2 :description "Claude Haiku (latest) via CLI (no API key)"}]
   :mistral
   [{:model "mistral-large-latest" :curated-rank 18 :description "Mistral Large 3"}
    {:model "mistral-small-2501"}
    {:model "mistral-small-latest"}
    {:model "mistral-large-2512"}
    {:model "codestral-2501"}]
   :together
   [{:model "meta-llama/Llama-3.3-70B-Instruct-Turbo"}
    {:model "mistralai/Mixtral-8x22B-Instruct-v0.1"}
    {:model "Qwen/Qwen2.5-72B-Instruct-Turbo"}
    {:model "meta-llama/Meta-Llama-3.1-405B-Instruct-Turbo"}]
   :groq
   [{:model "qwen/qwen3-32b"}
    {:model "llama-3.3-70b-versatile" :curated-rank 19 :description "Groq Llama 3.3 70B (fast inference)"}
    {:model "meta-llama/llama-4-scout-17b-16e-instruct"}
    {:model "llama-3.1-8b-instant"}]
   :apple-fm
   [{:model "apple-foundationmodel" :curated-rank 22 :description "Apple FM ~3B (on-device, macOS 26+)"}]
   :free-llm
   [{:model "auto" :curated-rank 21 :description "Free OpenAI-compatible endpoint (FREELLM_BASE_URL); 'auto' lets the backend pick"}]
   :anthropic
   [{:model "claude-opus-4-1"}
    {:model "claude-opus-4-8" :curated-rank 52 :description "Anthropic Claude Opus 4.8 (most capable)"}
    {:model "claude-3-5-sonnet-20241022"}
    {:model "claude-opus-4-20250514"}
    {:model "claude-sonnet-4-5"}
    {:model "claude-3-5-sonnet-latest"}
    {:model "claude-sonnet-4-20250514"}
    {:model "claude-fable-latest"}
    {:model "claude-sonnet-4-6" :curated-rank 12 :description "Anthropic Claude Sonnet 4.6 (fast + smart)"}
    {:model "claude-sonnet-4-5-20250929"}
    {:model "claude-opus-4-5-20251101"}
    {:model "claude-3-5-haiku-20241022"}
    {:model "claude-haiku-4-5-20251001"}
    {:model "claude-opus-4-6" :curated-rank 11 :description "Anthropic Claude Opus 4.6"}
    {:model "claude-mythos-latest"}
    {:model "claude-opus-4-0"}
    {:model "claude-haiku-4-5" :curated-rank 13 :description "Anthropic Claude Haiku 4.5 (fastest, cheap)"}
    {:model "claude-opus-4-1-20250805"}
    {:model "claude-3-haiku-20240307"}
    {:model "claude-opus-4-7" :curated-rank 10 :description "Anthropic Claude Opus 4.7 (most capable)"}
    {:model "claude-fable-5" :curated-rank 54 :description "Anthropic Claude Fable 5 (creative)"}
    {:model "claude-mythos-5" :curated-rank 53 :description "Anthropic Claude Mythos 5 (flagship)"}
    {:model "claude-opus-4-5"}
    {:model "claude-3-5-haiku-latest"}
    {:model "claude-sonnet-4-0"}]
   :openai
   [{:model "gpt-5-pro" :curated-rank 50 :description "OpenAI GPT-5 Pro (extended reasoning)"}
    {:model "o1"}
    {:model "gpt-4"}
    {:model "gpt-5.4-mini" :curated-rank 48 :description "OpenAI GPT-5.4 Mini"}
    {:model "o4-mini" :curated-rank 9 :description "OpenAI o4-mini (reasoning, cheap)"}
    {:model "gpt-4o"}
    {:model "gpt-5" :curated-rank 3 :description "OpenAI GPT-5 (flagship)"}
    {:model "gpt-4.1" :curated-rank 6 :description "OpenAI GPT-4.1 (coding)"}
    {:model "gpt-3.5-turbo"}
    {:model "gpt-5.4-nano" :curated-rank 49 :description "OpenAI GPT-5.4 Nano (cheapest)"}
    {:model "gpt-4o-mini"}
    {:model "gpt-5-mini" :curated-rank 4 :description "OpenAI GPT-5 Mini (fast, cheap)"}
    {:model "gpt-5-nano" :curated-rank 5 :description "OpenAI GPT-5 Nano (cheapest, lowest latency)"}
    {:model "o3-pro" :curated-rank 51 :description "OpenAI o3 Pro (advanced reasoning)"}
    {:model "o3-mini"}
    {:model "gpt-5.5-mini" :curated-rank 47 :description "OpenAI GPT-5.5 Mini"}
    {:model "o1-mini"}
    {:model "gpt-5.5" :curated-rank 46 :description "OpenAI GPT-5.5 (advanced reasoning)"}
    {:model "o3" :curated-rank 8 :description "OpenAI o3 (reasoning)"}
    {:model "gpt-4-turbo"}
    {:model "gpt-4.1-nano"}
    {:model "gpt-4.1-mini" :curated-rank 7 :description "OpenAI GPT-4.1 Mini"}]
   :ollama
   [{:model "gemma3:12b"}
    {:model "glm-5:cloud" :curated-rank 20 :description "GLM-5 Cloud (Ollama)"}]
   :bedrock
   [{:model "anthropic.claude-haiku-4-5-20251001-v1:0"}
    {:model "meta.llama3-1-8b-instruct-v1:0"}
    {:model "global.anthropic.claude-opus-4-6-v1" :curated-rank 24 :description "Claude Opus 4.6 on Bedrock (global cross-region)"}
    {:model "mistral.mistral-small-2402-v1:0"}
    {:model "qwen.qwen3-vl-235b-a22b" :curated-rank 39 :description "Qwen3 VL 235B A22B (vision) on Bedrock" :region "us-east-1"}
    {:model "amazon.nova-pro-v1:0" :curated-rank 31 :description "Amazon Nova Pro (Bedrock, multimodal)"}
    {:model "us.anthropic.claude-sonnet-4-5-20250929-v1:0" :curated-rank 28 :description "Claude Sonnet 4.5 on Bedrock (US cross-region)"}
    {:model "global.anthropic.claude-mythos-5" :curated-rank 57 :description "Claude Mythos 5 on Bedrock (global)"}
    {:model "cohere.command-r-plus-v1:0"}
    {:model "writer.palmyra-x5-v1:0" :curated-rank 44 :description "Writer Palmyra X5 on Bedrock" :region "us-east-1"}
    {:model "qwen.qwen3-coder-30b-a3b-v1:0" :curated-rank 38 :description "Qwen3 Coder 30B (A3B) on Bedrock" :region "us-east-1"}
    {:model "global.anthropic.claude-sonnet-4-5-20250929-v1:0"}
    {:model "apac.amazon.nova-micro-v1:0"}
    {:model "apac.amazon.nova-pro-v1:0"}
    {:model "openai.gpt-oss-safeguard-20b"}
    {:model "anthropic.claude-3-5-haiku-20241022-v1:0"}
    {:model "openai.gpt-oss-120b-1:0" :curated-rank 35 :description "OpenAI gpt-oss 120B (open-weights) on Bedrock" :region "us-east-1"}
    {:model "qwen.qwen3-next-80b-a3b"}
    {:model "global.anthropic.claude-opus-4-8" :curated-rank 55 :description "Claude Opus 4.8 on Bedrock (global)"}
    {:model "us.writer.palmyra-x4-v1:0"}
    {:model "ai21.jamba-1-5-large-v1:0" :curated-rank 43 :description "AI21 Jamba 1.5 Large on Bedrock" :region "us-east-1"}
    {:model "apac.anthropic.claude-3-5-sonnet-20241022-v2:0"}
    {:model "anthropic.claude-3-haiku-20240307-v1:0"}
    {:model "apac.amazon.nova-lite-v1:0"}
    {:model "us.anthropic.claude-opus-4-1-20250805-v1:0" :curated-rank 26 :description "Claude Opus 4.1 on Bedrock (US cross-region)"}
    {:model "mistral.mistral-large-2407-v1:0" :curated-rank 34 :description "Mistral Large 2407 on Bedrock"}
    {:model "anthropic.claude-sonnet-4-5-20250929-v1:0"}
    {:model "writer.palmyra-vision-7b"}
    {:model "global.anthropic.claude-haiku-4-5-20251001-v1:0" :curated-rank 29 :description "Claude Haiku 4.5 on Bedrock (global cross-region)"}
    {:model "global.anthropic.claude-sonnet-4-6" :curated-rank 27 :description "Claude Sonnet 4.6 on Bedrock (global cross-region)"}
    {:model "deepseek.v3.2" :curated-rank 41 :description "DeepSeek V3.2 on Bedrock" :region "us-east-1"}
    {:model "cohere.command-r-v1:0"}
    {:model "meta.llama3-1-70b-instruct-v1:0"}
    {:model "writer.palmyra-x4-v1:0"}
    {:model "global.anthropic.claude-fable-5" :curated-rank 56 :description "Claude Fable 5 on Bedrock (global)"}
    {:model "qwen.qwen3-32b-v1:0" :curated-rank 37 :description "Qwen3 32B (dense) on Bedrock" :region "us-east-1"}
    {:model "amazon.nova-micro-v1:0"}
    {:model "us.anthropic.claude-haiku-4-5-20251001-v1:0" :curated-rank 30 :description "Claude Haiku 4.5 on Bedrock (US cross-region)"}
    {:model "qwen.qwen3-coder-next"}
    {:model "global.anthropic.claude-opus-4-7" :curated-rank 23 :description "Claude Opus 4.7 on Bedrock (global cross-region, most capable)"}
    {:model "us.deepseek.r1-v1:0" :curated-rank 42 :description "DeepSeek-R1 on Bedrock (US cross-region)" :region "us-east-1"}
    {:model "amazon.nova-lite-v1:0" :curated-rank 32 :description "Amazon Nova Lite (Bedrock, fast)"}
    {:model "deepseek.r1-v1:0" :curated-rank 40 :description "DeepSeek-R1 (reasoning) on Bedrock" :region "us-east-1"}
    {:model "anthropic.claude-opus-4-1-20250805-v1:0"}
    {:model "anthropic.claude-3-5-sonnet-20241022-v2:0"}
    {:model "openai.gpt-oss-safeguard-120b"}
    {:model "us.writer.palmyra-x5-v1:0" :curated-rank 45 :description "Writer Palmyra X5 on Bedrock (US cross-region)" :region "us-east-1"}
    {:model "ai21.jamba-1-5-mini-v1:0"}
    {:model "global.anthropic.claude-opus-4-5-20251101-v1:0" :curated-rank 25 :description "Claude Opus 4.5 on Bedrock (global cross-region)"}
    {:model "meta.llama3-3-70b-instruct-v1:0" :curated-rank 33 :description "Meta Llama 3.3 70B on Bedrock"}
    {:model "openai.gpt-oss-20b-1:0" :curated-rank 36 :description "OpenAI gpt-oss 20B (open-weights) on Bedrock" :region "us-east-1"}]
   :google
   [{:model "gemini-3-flash-preview"}
    {:model "gemini-1.5-pro"}
    {:model "gemini-1.5-flash"}
    {:model "gemini-2.5-pro" :curated-rank 15 :description "Google Gemini 2.5 Pro (advanced)"}
    {:model "gemini-2.5-flash-lite"}
    {:model "gemini-2.5-flash" :curated-rank 14 :description "Google Gemini 2.5 Flash (very cheap)"}
    {:model "gemini-3.1-pro-preview"}
    {:model "gemini-3-pro-preview"}
    {:model "gemini-2.0-flash"}]
   :deepseek
   [{:model "deepseek-chat" :curated-rank 16 :description "DeepSeek V3.2 (ultra cheap)"}
    {:model "deepseek-reasoner" :curated-rank 17 :description "DeepSeek V3.2 Reasoner"}]))

;; Reverse index model-id -> provider, DERIVED from model-catalog. Used by
;; get-provider-from-model for catalog lookup. Unambiguous: no model id appears
;; under more than one provider in the catalog.
(def ^:private model->provider
  (into {} (for [[provider models] model-catalog
                 {:keys [model]} models]
             [model provider])))

(def ^:private drop-temperature-exact
  "Exact model names that reject the `temperature` parameter."
  #{"gpt-5" "gpt-5-mini" "gpt-5-nano"
    "gpt-5.5" "gpt-5.5-mini"
    "gpt-5.4-mini" "gpt-5.4-nano"
    "gpt-5-pro" "o3-pro"
    "o1" "o1-mini" "o3" "o3-mini" "o4-mini"})

(defn- drops-temperature?
  "True if the model rejects (or ignores) the `temperature` parameter.
   When detected, `create-lm` sets `:drop-params #{:temperature}` automatically.
   Matches by exact name (OpenAI GPT-5/o-series reasoning models) and by
   substring for the Claude families that reject sampling params (Opus 4.7+,
   Fable, Mythos) on the Anthropic API and on Bedrock under every prefix
   (anthropic., us.anthropic., global.anthropic., …)."
  [^String model]
  (or (contains? drop-temperature-exact model)
      (.contains model "claude-opus-4-8")
      (.contains model "claude-opus-4-7")
      (.contains model "claude-fable")
      (.contains model "claude-mythos")))

(defn- bedrock-supports-prompt-cache?
  "True for Bedrock model ids that accept the Converse cachePoint block.
   As of late 2025 that's Anthropic Claude on Bedrock and Amazon Nova
   (Pro/Lite/Micro). Other foundation models (Meta, Mistral, Cohere,
   DeepSeek, AI21, Writer, Qwen, gpt-oss) reject cachePoint."
  [^String model]
  (boolean
   (or (.contains model "anthropic.")
       (re-find #"amazon\.nova-(pro|lite|micro)" model))))

;; ============================================================================
;; Provider Model Prefixes
;; ============================================================================

(def ^:private provider-prefixes
  "Ordered list of [prefix provider] pairs used to route model strings.
   Vector (not map) to guarantee deterministic iteration order — Bedrock
   prefixes are checked before the openrouter-style \"anthropic/\" so
   cross-region inference profiles like \"us.anthropic.*\" route to
   :bedrock rather than :anthropic."
  [["bedrock/"        :bedrock]
   ["us.anthropic."   :bedrock]
   ["eu.anthropic."   :bedrock]
   ["apac.anthropic." :bedrock]
   ["amazon."         :bedrock]
   ["meta."           :bedrock]
   ["cohere."         :bedrock]
   ;; Bare-vendor Bedrock prefixes (must precede openai/ which is OpenRouter-style)
   ["openai."         :bedrock]
   ["qwen."           :bedrock]
   ["deepseek."       :bedrock]
   ["ai21."           :bedrock]
   ["writer."         :bedrock]
   ["openai/"         :openai]
   ["anthropic/"      :anthropic]
   ["google/"         :google]
   ["groq/"           :groq]
   ["together/"       :together]
   ["fireworks/"      :fireworks]
   ["mistral/"        :mistral]
   ["deepseek/"       :deepseek]
   ["ollama/"         :ollama]
   ["free-llm/"       :free-llm]
   ["apple-fm/"       :apple-fm]])

(def ^:private bedrock-region-profile-re
  "Bedrock cross-region inference profile IDs:
   <region>.<vendor>.<model>  e.g. apac.amazon.nova-lite-v1:0,
   global.anthropic.claude-haiku-4-5-20251001-v1:0."
  #"^(us|eu|apac|global)\.(anthropic|amazon|meta|mistral|cohere|twelvelabs|openai|qwen|deepseek|ai21|writer)\..+")

(defn get-provider-from-model
  "Determine the provider for a given model string.
   Checks explicit prefixes first, then a Bedrock region/vendor regex,
   then model catalogs."
  [model]
  (or
   ;; Check explicit provider prefixes
   (some (fn [[prefix provider]]
           (when (.startsWith ^String model prefix)
             provider))
         provider-prefixes)
   ;; Bedrock cross-region inference profile (us./eu./apac./global. prefix)
   (when (re-matches bedrock-region-profile-re model) :bedrock)
   ;; Catalog lookup (reverse index)
   (model->provider model)
   ;; Fallbacks for ids not in the catalog
   (cond
     ;; Mistral on Bedrock IDs look like "mistral.mistral-..." (prefix-matched
     ;; above), but raw "mistral.X" without "/" still hints Bedrock.
     (.startsWith ^String model "mistral.") :bedrock
     ;; Default: if contains "claude" -> anthropic, else openai
     (.contains ^String model "claude") :anthropic
     :else :openai)))

;; ============================================================================
;; AWS Auto-Detection
;; ============================================================================

(defn detect-aws-region
  "Detect AWS region from env, falling back to provider default.
   Order: explicit arg → AWS_REGION → AWS_DEFAULT_REGION → :bedrock :default-region."
  ([] (detect-aws-region nil))
  ([explicit]
   (or explicit
       (System/getenv "AWS_REGION")
       (System/getenv "AWS_DEFAULT_REGION")
       (get-in providers [:bedrock :default-region]))))

(declare get-popular-models)

(defn- bedrock-model-region
  "Return the catalog-pinned :region for a Bedrock model id, or nil.
   Some Bedrock foundation models are only available in specific regions
   (e.g. openai.gpt-oss-* in us-east-1). The catalog records that pin so we
   can route correctly even when the user's AWS_REGION points elsewhere."
  [model]
  (some (fn [m] (when (= model (:model m)) (:region m)))
        (get-popular-models)))

(defn- bedrock-region-prefix
  "Bedrock cross-region inference-profile prefix for an AWS region
   (us-* → us, eu-* → eu, ap-* → apac). Defaults to `us`."
  [region]
  (let [^String r (str region)]
    (cond
      (.startsWith r "us-") "us"
      (.startsWith r "eu-") "eu"
      (.startsWith r "ap-") "apac"
      :else "us")))

(defn- bedrock-inference-profile-model
  "Amazon Nova can't be invoked on-demand with the BARE model id in most
   accounts/regions — it needs a cross-region inference profile
   (`<prefix>.amazon.nova-…`, e.g. `us.amazon.nova-lite-v1:0`), or AWS returns
   \"Invocation … with on-demand throughput isn't supported\". Rewrite a bare
   `amazon.nova-*` id to the region-appropriate profile id; leave already-prefixed
   ids (`us.amazon.…`) and non-Nova models untouched."
  [model region]
  (if (and (string? model)
           (re-matches #"amazon\.nova-(?:pro|lite|micro).*" model))
    (str (bedrock-region-prefix region) "." model)
    model))

(defn aws-credentials-detected?
  "Return true if any AWS credential source is present.
   Checks env vars (static keys, AWS_PROFILE/AWS_DEFAULT_PROFILE, IRSA, ECS
   task role) and the presence of ~/.aws/credentials."
  []
  (boolean
   (or (System/getenv "AWS_ACCESS_KEY_ID")
       (System/getenv "AWS_PROFILE")
       (System/getenv "AWS_DEFAULT_PROFILE")
       (System/getenv "AWS_WEB_IDENTITY_TOKEN_FILE")
       (System/getenv "AWS_CONTAINER_CREDENTIALS_RELATIVE_URI")
       (.exists (java.io.File.
                 (str (System/getProperty "user.home")
                      "/.aws/credentials"))))))

;; ============================================================================
;; LM Configuration
;; ============================================================================

(defn split-lm-str
  "Split an LM identifier string into [provider model].

   Prefers the `provider/model` form — separator is the FIRST `/`. With no `/`,
   falls back to the legacy `provider:model` form — separator is the FIRST `:`.
   Splitting on the first separator keeps model ids that themselves contain `:`
   intact (e.g. the bedrock id `amazon.nova-lite-v1:0` after a `bedrock/`
   provider). With no separator at all, returns `[lm-str nil]`."
  [^String lm-str]
  (let [slash (.indexOf lm-str "/")]
    (if (>= slash 0)
      [(subs lm-str 0 slash) (subs lm-str (inc slash))]
      (let [colon (.indexOf lm-str ":")]
        (if (>= colon 0)
          [(subs lm-str 0 colon) (subs lm-str (inc colon))]
          [lm-str nil])))))

(defn- resolve-model-spec
  "When `model` is a provider-qualified spec — `provider/model` (preferred) or
   legacy `provider:model` — whose leading token is a REGISTERED provider,
   return `[provider-kw bare-model]`; otherwise `[nil model]`.

   The known-provider gate is what keeps a bare id that merely contains a
   separator intact: a bedrock id like `amazon.nova-lite-v1:0` has the leading
   token `amazon.nova-lite-v1`, which is not a provider, so it is left whole and
   routed by `get-provider-from-model`. An OpenRouter-style `vendor/model` id is
   likewise left whole unless its vendor is itself a registered provider."
  [model]
  (if (string? model)
    (let [[lead bare] (split-lm-str model)]
      (if (and bare (contains? providers (keyword lead)))
        [(keyword lead) bare]
        [nil model]))
    [nil model]))

(defn format-lm-label
  "Canonical `provider/model` display label from a `provider` (keyword/string/
   nil) and a `model` (a bare id, or itself a provider-qualified spec). Uses the
   same registered-provider gate as `create-lm`, so a combined `:model` like
   `claude-code:opus` renders as `claude-code/opus` while a bare id that merely
   contains `:` (bedrock `amazon.nova-lite-v1:0`) is shown whole. Returns `\"?\"`
   when neither is present."
  [provider model]
  (let [[spec-provider bare] (resolve-model-spec model)
        prov (or provider spec-provider)]
    (cond
      (and prov bare) (str (name prov) "/" bare)
      bare            (str bare)
      prov            (name prov)
      :else           "?")))

(defn create-lm
  "Create an LM configuration map.
   Options:
     :model        - Model name string (required)
     :api-key      - API key (optional, falls back to env var)
     :temperature  - Sampling temperature (default 0.0)
     :max-tokens   - Max output tokens (optional)
     :base-url     - Override provider base URL (optional)
     :provider     - Override auto-detected provider (optional)
                     Use :anthropic-max for Max/Pro plan subscription auth (no API key)
     :prompt-cache - Enable prompt caching (default: provider-specific, true for Anthropic)
     :cache-ttl    - Cache-entry TTL for stable prompt zones: \"5m\" (default) or
                     \"1h\". \"1h\" keeps the cross-turn prefix cached across
                     human-paced turn gaps (Anthropic only — adds the
                     extended-cache-ttl beta header; write premium is 2x base
                     input, paid once per stable zone per session). No-op on
                     other providers.
     :drop-params  - Set of param keywords to omit from API requests (auto-detected for
                     models that reject temperature, e.g. o-series, gpt-5 family)
     :region       - (Bedrock) AWS region. Falls back to AWS_REGION /
                     AWS_DEFAULT_REGION env then :default-region of provider.
     :aws-profile  - (Bedrock) Named AWS profile from ~/.aws/credentials.
                     Falls back to AWS_PROFILE then AWS_DEFAULT_PROFILE env vars.
     :credentials-provider - (Bedrock) Custom cognitect aws-api credentials
                             provider; overrides profile/env-based detection."
  [{:keys [model api-key temperature max-tokens base-url provider prompt-cache cache-ttl
           drop-params region aws-profile credentials-provider]}]
  (let [;; `:provider` is a keyword internally, but callers at the boundary may
        ;; pass a string (e.g. the CLI's `-p`/legacy `provider:model` opt).
        ;; Keywordize defensively — `keyword` is idempotent on a keyword and nil-safe
        ;; — so a string provider still hits the keyword-keyed `providers` registry.
        provider          (some-> provider keyword)
        ;; A `:model` may itself be a provider-qualified spec ("claude-code/opus"
        ;; or legacy "claude-code:opus"). Strip a registered-provider prefix so
        ;; the bare model id flows downstream; an explicit `:provider` still wins.
        [spec-provider spec-model] (resolve-model-spec model)
        model             (or spec-model model)
        detected-provider (or provider spec-provider (get-provider-from-model model))
        provider-config   (get providers detected-provider)
        ;; For OAuth providers (anthropic-max), api-key is resolved dynamically at call time
        oauth?            (= :oauth (:auth-type provider-config))
        bedrock?          (= :bedrock detected-provider)
        resolved-api-key  (when-not (or oauth? bedrock?)
                            (or api-key
                                (when-let [env-var (:api-key-env provider-config)]
                                  ;; getProperty fallback lets a dotenv loader
                                  ;; surface keys without mutating JVM env
                                  ;; (see projects/agent-tui-app/dotenv.clj).
                                  (or (System/getenv env-var)
                                      (System/getProperty env-var)))))
        ;; prompt-cache: explicit setting > Bedrock model-aware default > provider default > false.
        ;; Bedrock defaults on for Anthropic and Nova models (the ones that
        ;; accept cachePoint) and off for every other foundation model.
        resolved-cache    (cond
                            (some? prompt-cache) prompt-cache
                            (and bedrock?
                                 (bedrock-supports-prompt-cache? model)) true
                            :else (:prompt-cache provider-config false))
        ;; drop-params: explicit > auto-detect from model catalog
        resolved-drop     (or drop-params
                              (when (drops-temperature? model)
                                #{:temperature}))
        ;; bedrock: explicit arg → catalog pin (per-model :region) → env → default.
        ;; Catalog wins over env so that region-pinned foundation models
        ;; (e.g. openai.gpt-oss-* in us-east-1) route correctly even when the
        ;; user's AWS_REGION points to a different region.
        resolved-region   (when bedrock?
                            (or region
                                (bedrock-model-region model)
                                (detect-aws-region nil)))
        ;; Bare Amazon Nova ids need a cross-region inference profile to invoke
        ;; on-demand — rewrite to `<prefix>.amazon.nova-…` for the resolved region.
        resolved-model    (if bedrock?
                            (bedrock-inference-profile-model model resolved-region)
                            model)
        resolved-profile  (when bedrock?
                            (or aws-profile
                                (System/getenv "AWS_PROFILE")
                                (System/getenv "AWS_DEFAULT_PROFILE")))
        ;; base-url: explicit arg → static provider default → env var (e.g.
        ;; FREELLM_BASE_URL). getProperty fallback lets a dotenv loader surface
        ;; the value without mutating the immutable JVM env map.
        resolved-base-url (or base-url
                              (:base-url provider-config)
                              (when-let [env-var (:base-url-env provider-config)]
                                (or (System/getenv env-var)
                                    (System/getProperty env-var))))]
    (cond-> {:model       resolved-model
             :provider    detected-provider
             :api-key     resolved-api-key
             :temperature (or temperature 0.0)
             :base-url    resolved-base-url
             :auth-header (:auth-header provider-config)
             :message-format (:message-format provider-config)
             :supports-json-schema? (:supports-json-schema? provider-config)}
      oauth?          (assoc :auth-type :oauth)
      max-tokens      (assoc :max-tokens max-tokens)
      resolved-cache  (assoc :prompt-cache true)
      cache-ttl       (assoc :cache-ttl cache-ttl)
      resolved-drop   (assoc :drop-params resolved-drop)
      bedrock?        (assoc :auth-type :aws-sigv4
                             :region    resolved-region)
      (and bedrock? resolved-profile)     (assoc :aws-profile resolved-profile)
      (and bedrock? credentials-provider) (assoc :credentials-provider credentials-provider))))

(defn- detect-default-lm
  "Default LM: claude-code:opus — most capable Claude via the CLI.
   No API key required (uses Claude CLI)."
  []
  (create-lm {:model "opus" :provider :claude-code}))

(defonce ^:private default-lm
  (atom (detect-default-lm)))

(defn configure-default-lm!
  "Set the global default LM configuration."
  [lm-config]
  (reset! default-lm lm-config))

(defn get-default-lm
  "Get the current global default LM configuration."
  []
  @default-lm)

(defn lm-initialized?
  "Return true if the default LM has a resolved API key, OAuth auth, AWS
   credentials (Bedrock), or is a no-auth provider (claude-code/ollama/apple-fm).
   :free-llm needs only a resolved :base-url (FREELLM_API_KEY is optional)."
  []
  (or (some? (:api-key @default-lm))
      (= :oauth (:auth-type @default-lm))
      (and (= :bedrock (:provider @default-lm))
           (or (:credentials-provider @default-lm)
               (aws-credentials-detected?)))
      (and (= :free-llm (:provider @default-lm))
           (some? (:base-url @default-lm)))
      (#{:claude-code :ollama :apple-fm} (:provider @default-lm))))

(defn get-popular-models
  "Get a curated list of popular models across providers.
   Derived view of model-catalog: curated entries (:curated-rank), ordered by rank."
  []
  (->> (mapcat (fn [[provider models]]
                 (map #(assoc % :provider provider) models))
               model-catalog)
       (filter :curated-rank)
       (sort-by :curated-rank)
       (mapv (fn [m] (cond-> {:model (:model m)
                              :provider (:provider m)
                              :description (:description m)}
                       (:region m) (assoc :region (:region m)))))))

(defn list-models
  "Flat view of model-catalog — the full known model set with metadata.
   Returns a vector of {:model :provider :curated? :curated-rank? :description?
   :region?} maps (keys absent when the catalog entry has no value).

   Opts:
     :provider  keyword — restrict to one provider (nil = all)
     :curated?  boolean — when true, only curated entries (those with a
                :curated-rank), ordered by rank. When false/omitted, the whole
                catalog grouped by provider (curated first within each), then
                alphabetical by model id.

   Pure data — no network calls, no API keys."
  [& {:keys [provider curated?]}]
  (let [entries (for [[prov models] model-catalog
                      m models
                      :when (or (nil? provider) (= provider prov))]
                  (cond-> {:model    (:model m)
                           :provider prov
                           :curated? (some? (:curated-rank m))}
                    (:curated-rank m) (assoc :curated-rank (:curated-rank m))
                    (:description m)  (assoc :description (:description m))
                    (:region m)       (assoc :region (:region m))))]
    (if curated?
      (->> entries (filter :curated?) (sort-by :curated-rank) vec)
      (->> entries
           (sort-by (juxt #(name (:provider %))
                          #(or (:curated-rank %) Long/MAX_VALUE)
                          :model))
           vec))))

(defn get-models-by-provider
  "Get all known models grouped by provider, optionally filtered by :provider.

  (get-models-by-provider) => all models grouped by provider (provider is nil)
  (get-models-by-provider {:provider :openai}) => only :openai models

  Derived view of model-catalog as bare-string sets, grouped by provider —
  every catalog provider, including :claude-code and :free-llm. Throws ex-info
  with {:provider :available-providers} when :provider is given but unknown."
  [& {:keys [provider]}]
  (let [all (into {} (map (fn [[prov ms]] [prov (set (map :model ms))]))
                  model-catalog)]
    (cond
      (nil? provider)          all
      (contains? all provider) (select-keys all [provider])
      :else                    (throw (ex-info (str "Unknown provider: " provider)
                                               {:provider            provider
                                                :available-providers (vec (sort (keys all)))})))))

;; ============================================================================
;; Global Usage Tracker
;; ============================================================================

(defonce ^:private global-tracker (atom nil))

(defn enable-global-tracking!
  "Enable global usage tracking. All chat-completion calls will be recorded.
   Options are passed to create-usage-tracker (e.g. :history-cap)."
  [& opts]
  (reset! global-tracker (apply usage/create-usage-tracker opts)))

(defn disable-global-tracking!
  "Disable global usage tracking and discard the tracker."
  []
  (reset! global-tracker nil))

(defn get-global-tracker
  "Get the current global usage tracker atom, or nil if tracking is disabled."
  []
  @global-tracker)
