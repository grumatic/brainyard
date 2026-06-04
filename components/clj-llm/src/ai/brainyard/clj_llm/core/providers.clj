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

(def openai-models
  #{;; GPT-5 family
    "gpt-5" "gpt-5-mini" "gpt-5-nano"
    ;; GPT-4.1 family
    "gpt-4.1" "gpt-4.1-mini" "gpt-4.1-nano"
    ;; GPT-4o family
    "gpt-4o" "gpt-4o-mini"
    ;; o-series (reasoning)
    "o3" "o3-mini" "o4-mini"
    ;; Legacy (still API-available)
    "o1" "o1-mini" "gpt-4-turbo" "gpt-4" "gpt-3.5-turbo"})

(def anthropic-models
  #{;; Latest (current generation)
    "claude-opus-4-7"                                         ;; Opus 4.7
    "claude-opus-4-6"                                         ;; Opus 4.6
    "claude-sonnet-4-6"                                       ;; Sonnet 4.6
    "claude-haiku-4-5-20251001"  "claude-haiku-4-5"           ;; Haiku 4.5
    ;; Legacy Claude 4.x
    "claude-sonnet-4-5-20250929" "claude-sonnet-4-5"          ;; Sonnet 4.5
    "claude-opus-4-5-20251101"   "claude-opus-4-5"            ;; Opus 4.5
    "claude-opus-4-1-20250805"   "claude-opus-4-1"            ;; Opus 4.1
    "claude-opus-4-20250514"     "claude-opus-4-0"            ;; Opus 4
    "claude-sonnet-4-20250514"   "claude-sonnet-4-0"          ;; Sonnet 4
    ;; Legacy 3.x (deprecated — keep for backward compat)
    "claude-3-5-sonnet-20241022" "claude-3-5-sonnet-latest"
    "claude-3-5-haiku-20241022"  "claude-3-5-haiku-latest"
    "claude-3-haiku-20240307"})

(def google-models
  #{;; Gemini 2.5 (stable)
    "gemini-2.5-flash" "gemini-2.5-flash-lite" "gemini-2.5-pro"
    ;; Gemini 3.x (preview)
    "gemini-3-flash-preview" "gemini-3-pro-preview" "gemini-3.1-pro-preview"
    ;; Legacy (deprecated but API-available)
    "gemini-2.0-flash" "gemini-1.5-pro" "gemini-1.5-flash"})

(def groq-models
  #{"llama-3.3-70b-versatile" "llama-3.1-8b-instant"
    "meta-llama/llama-4-scout-17b-16e-instruct"
    "qwen/qwen3-32b"})

(def together-models
  #{"meta-llama/Llama-3.3-70B-Instruct-Turbo"
    "meta-llama/Meta-Llama-3.1-405B-Instruct-Turbo"
    "mistralai/Mixtral-8x22B-Instruct-v0.1"
    "Qwen/Qwen2.5-72B-Instruct-Turbo"})

(def mistral-models
  #{"mistral-large-latest" "mistral-large-2512"
    "mistral-small-latest" "mistral-small-2501"
    "codestral-2501"})

(def deepseek-models
  #{"deepseek-chat" "deepseek-reasoner"})

(def ollama-models
  #{"gemma3:12b" "glm-5:cloud"})

(def apple-fm-models
  #{"apple-foundationmodel"})

(def bedrock-models
  "Curated set of popular Bedrock model IDs (incl. cross-region inference profiles)."
  #{;; Anthropic on Bedrock — current generation
    "anthropic.claude-sonnet-4-5-20250929-v1:0"
    "anthropic.claude-opus-4-1-20250805-v1:0"
    "anthropic.claude-haiku-4-5-20251001-v1:0"
    ;; Cross-region inference profiles (US / APAC / Global)
    "us.anthropic.claude-sonnet-4-5-20250929-v1:0"
    "us.anthropic.claude-opus-4-1-20250805-v1:0"
    "us.anthropic.claude-haiku-4-5-20251001-v1:0"
    "apac.anthropic.claude-3-5-sonnet-20241022-v2:0"
    "apac.amazon.nova-pro-v1:0"
    "apac.amazon.nova-lite-v1:0"
    "apac.amazon.nova-micro-v1:0"
    "global.anthropic.claude-haiku-4-5-20251001-v1:0"
    "global.anthropic.claude-sonnet-4-5-20250929-v1:0"
    "global.anthropic.claude-opus-4-5-20251101-v1:0"
    "global.anthropic.claude-sonnet-4-6"
    "global.anthropic.claude-opus-4-6-v1"
    "global.anthropic.claude-opus-4-7"
    ;; Anthropic on Bedrock — 3.x (still widely deployed)
    "anthropic.claude-3-5-sonnet-20241022-v2:0"
    "anthropic.claude-3-5-haiku-20241022-v1:0"
    "anthropic.claude-3-haiku-20240307-v1:0"
    ;; Amazon Nova
    "amazon.nova-pro-v1:0"
    "amazon.nova-lite-v1:0"
    "amazon.nova-micro-v1:0"
    ;; Meta Llama on Bedrock
    "meta.llama3-3-70b-instruct-v1:0"
    "meta.llama3-1-70b-instruct-v1:0"
    "meta.llama3-1-8b-instruct-v1:0"
    ;; Mistral on Bedrock
    "mistral.mistral-large-2407-v1:0"
    "mistral.mistral-small-2402-v1:0"
    ;; Cohere on Bedrock
    "cohere.command-r-plus-v1:0"
    "cohere.command-r-v1:0"
    ;; OpenAI gpt-oss on Bedrock
    "openai.gpt-oss-120b-1:0"
    "openai.gpt-oss-20b-1:0"
    "openai.gpt-oss-safeguard-120b"
    "openai.gpt-oss-safeguard-20b"
    ;; Qwen on Bedrock
    "qwen.qwen3-32b-v1:0"
    "qwen.qwen3-coder-30b-a3b-v1:0"
    "qwen.qwen3-coder-next"
    "qwen.qwen3-next-80b-a3b"
    "qwen.qwen3-vl-235b-a22b"
    ;; DeepSeek on Bedrock
    "deepseek.r1-v1:0"
    "deepseek.v3.2"
    "us.deepseek.r1-v1:0"
    ;; AI21 Labs on Bedrock
    "ai21.jamba-1-5-large-v1:0"
    "ai21.jamba-1-5-mini-v1:0"
    ;; Writer on Bedrock
    "writer.palmyra-x4-v1:0"
    "writer.palmyra-x5-v1:0"
    "writer.palmyra-vision-7b"
    "us.writer.palmyra-x4-v1:0"
    "us.writer.palmyra-x5-v1:0"})

(def ^:private drop-temperature-exact
  "Exact model names that reject the `temperature` parameter."
  #{"gpt-5" "gpt-5-mini" "gpt-5-nano"
    "o1" "o1-mini" "o3" "o3-mini" "o4-mini"})

(defn- drops-temperature?
  "True if the model rejects (or ignores) the `temperature` parameter.
   When detected, `create-lm` sets `:drop-params #{:temperature}` automatically.
   Matches by exact name (OpenAI GPT-5 + o-series) and by substring for
   Claude Opus 4.7, which rejects temperature on Anthropic API and on Bedrock
   under every prefix (anthropic., us.anthropic., global.anthropic., …)."
  [^String model]
  (or (contains? drop-temperature-exact model)
      (.contains model "claude-opus-4-7")))

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
   ;; Check model catalogs
   (cond
     (bedrock-models model)   :bedrock
     (openai-models model)    :openai
     (anthropic-models model) :anthropic
     (google-models model)    :google
     (groq-models model)      :groq
     (together-models model)  :together
     (mistral-models model)   :mistral
     (deepseek-models model)  :deepseek
     (ollama-models model)    :ollama
     (apple-fm-models model)  :apple-fm
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
     :drop-params  - Set of param keywords to omit from API requests (auto-detected for
                     models that reject temperature, e.g. o-series, gpt-5 family)
     :region       - (Bedrock) AWS region. Falls back to AWS_REGION /
                     AWS_DEFAULT_REGION env then :default-region of provider.
     :aws-profile  - (Bedrock) Named AWS profile from ~/.aws/credentials.
                     Falls back to AWS_PROFILE then AWS_DEFAULT_PROFILE env vars.
     :credentials-provider - (Bedrock) Custom cognitect aws-api credentials
                             provider; overrides profile/env-based detection."
  [{:keys [model api-key temperature max-tokens base-url provider prompt-cache drop-params
           region aws-profile credentials-provider]}]
  (let [detected-provider (or provider (get-provider-from-model model))
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
    (cond-> {:model       model
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
  "Get a curated list of popular models across providers."
  []
  [{:model "opus"               :provider :claude-code :description "Claude Opus 4.6 via CLI (no API key)"}
   {:model "sonnet"             :provider :claude-code :description "Claude Sonnet 4.6 via CLI (no API key)"}
   {:model "haiku"              :provider :claude-code :description "Claude Haiku 4.5 via CLI (no API key)"}
   {:model "gpt-5"             :provider :openai    :description "OpenAI GPT-5 (flagship)"}
   {:model "gpt-5-mini"        :provider :openai    :description "OpenAI GPT-5 Mini (fast, cheap)"}
   {:model "gpt-5-nano"        :provider :openai    :description "OpenAI GPT-5 Nano (cheapest, lowest latency)"}
   {:model "gpt-4.1"           :provider :openai    :description "OpenAI GPT-4.1 (coding)"}
   {:model "gpt-4.1-mini"      :provider :openai    :description "OpenAI GPT-4.1 Mini"}
   {:model "o3"                :provider :openai    :description "OpenAI o3 (reasoning)"}
   {:model "o4-mini"           :provider :openai    :description "OpenAI o4-mini (reasoning, cheap)"}
   {:model "claude-opus-4-7"   :provider :anthropic :description "Anthropic Claude Opus 4.7 (most capable)"}
   {:model "claude-opus-4-6"   :provider :anthropic :description "Anthropic Claude Opus 4.6"}
   {:model "claude-sonnet-4-6" :provider :anthropic :description "Anthropic Claude Sonnet 4.6 (fast + smart)"}
   {:model "claude-haiku-4-5"  :provider :anthropic :description "Anthropic Claude Haiku 4.5 (fastest, cheap)"}
   {:model "gemini-2.5-flash"  :provider :google    :description "Google Gemini 2.5 Flash (very cheap)"}
   {:model "gemini-2.5-pro"    :provider :google    :description "Google Gemini 2.5 Pro (advanced)"}
   {:model "deepseek-chat"     :provider :deepseek  :description "DeepSeek V3.2 (ultra cheap)"}
   {:model "deepseek-reasoner" :provider :deepseek  :description "DeepSeek V3.2 Reasoner"}
   {:model "mistral-large-latest" :provider :mistral :description "Mistral Large 3"}
   {:model "llama-3.3-70b-versatile" :provider :groq :description "Groq Llama 3.3 70B (fast inference)"}
   {:model "glm-5:cloud" :provider :ollama :description "GLM-5 Cloud (Ollama)"}
   {:model "auto" :provider :free-llm :description "Free OpenAI-compatible endpoint (FREELLM_BASE_URL); 'auto' lets the backend pick"}
   {:model "apple-foundationmodel" :provider :apple-fm :description "Apple FM ~3B (on-device, macOS 26+)"}
   {:model "global.anthropic.claude-opus-4-7"               :provider :bedrock :description "Claude Opus 4.7 on Bedrock (global cross-region, most capable)"}
   {:model "global.anthropic.claude-opus-4-6-v1"            :provider :bedrock :description "Claude Opus 4.6 on Bedrock (global cross-region)"}
   {:model "global.anthropic.claude-opus-4-5-20251101-v1:0" :provider :bedrock :description "Claude Opus 4.5 on Bedrock (global cross-region)"}
   {:model "us.anthropic.claude-opus-4-1-20250805-v1:0"     :provider :bedrock :description "Claude Opus 4.1 on Bedrock (US cross-region)"}
   {:model "global.anthropic.claude-sonnet-4-6"             :provider :bedrock :description "Claude Sonnet 4.6 on Bedrock (global cross-region)"}
   {:model "us.anthropic.claude-sonnet-4-5-20250929-v1:0"   :provider :bedrock :description "Claude Sonnet 4.5 on Bedrock (US cross-region)"}
   {:model "global.anthropic.claude-haiku-4-5-20251001-v1:0" :provider :bedrock :description "Claude Haiku 4.5 on Bedrock (global cross-region)"}
   {:model "us.anthropic.claude-haiku-4-5-20251001-v1:0"     :provider :bedrock :description "Claude Haiku 4.5 on Bedrock (US cross-region)"}
   {:model "amazon.nova-pro-v1:0"   :provider :bedrock :description "Amazon Nova Pro (Bedrock, multimodal)"}
   {:model "amazon.nova-lite-v1:0"  :provider :bedrock :description "Amazon Nova Lite (Bedrock, fast)"}
   {:model "meta.llama3-3-70b-instruct-v1:0" :provider :bedrock :description "Meta Llama 3.3 70B on Bedrock"}
   {:model "mistral.mistral-large-2407-v1:0" :provider :bedrock :description "Mistral Large 2407 on Bedrock"}
   {:model "openai.gpt-oss-120b-1:0"   :provider :bedrock :region "us-east-1" :description "OpenAI gpt-oss 120B (open-weights) on Bedrock"}
   {:model "openai.gpt-oss-20b-1:0"    :provider :bedrock :region "us-east-1" :description "OpenAI gpt-oss 20B (open-weights) on Bedrock"}
   {:model "qwen.qwen3-32b-v1:0"       :provider :bedrock :region "us-east-1" :description "Qwen3 32B (dense) on Bedrock"}
   {:model "qwen.qwen3-coder-30b-a3b-v1:0" :provider :bedrock :region "us-east-1" :description "Qwen3 Coder 30B (A3B) on Bedrock"}
   {:model "qwen.qwen3-vl-235b-a22b"   :provider :bedrock :region "us-east-1" :description "Qwen3 VL 235B A22B (vision) on Bedrock"}
   {:model "deepseek.r1-v1:0"          :provider :bedrock :region "us-east-1" :description "DeepSeek-R1 (reasoning) on Bedrock"}
   {:model "deepseek.v3.2"             :provider :bedrock :region "us-east-1" :description "DeepSeek V3.2 on Bedrock"}
   {:model "us.deepseek.r1-v1:0"       :provider :bedrock :region "us-east-1" :description "DeepSeek-R1 on Bedrock (US cross-region)"}
   {:model "ai21.jamba-1-5-large-v1:0" :provider :bedrock :region "us-east-1" :description "AI21 Jamba 1.5 Large on Bedrock"}
   {:model "writer.palmyra-x5-v1:0"    :provider :bedrock :region "us-east-1" :description "Writer Palmyra X5 on Bedrock"}
   {:model "us.writer.palmyra-x5-v1:0" :provider :bedrock :region "us-east-1" :description "Writer Palmyra X5 on Bedrock (US cross-region)"}])

(defn get-models-by-provider
  "Get all known models grouped by provider."
  []
  {:openai    openai-models
   :anthropic anthropic-models
   :google    google-models
   :groq      groq-models
   :together  together-models
   :mistral   mistral-models
   :deepseek  deepseek-models
   :ollama    ollama-models
   :apple-fm  apple-fm-models
   :bedrock   bedrock-models})

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
