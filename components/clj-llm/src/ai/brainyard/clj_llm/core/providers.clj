;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.clj-llm.core.providers
  "Multi-provider LM configuration, model catalogs, and provider detection."
  (:require [ai.brainyard.clj-llm.core.catalog :as catalog]
            [ai.brainyard.clj-llm.core.usage :as usage]
            [clojure.string :as str]))

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
   [{:model "mistral-large-latest" :curated-rank 31 :description "Mistral Large 3"}
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
    {:model "llama-3.3-70b-versatile" :curated-rank 32 :description "Groq Llama 3.3 70B (fast inference)"}
    {:model "meta-llama/llama-4-scout-17b-16e-instruct"}
    {:model "llama-3.1-8b-instant"}]
   :apple-fm
   [{:model "apple-foundationmodel" :curated-rank 35 :description "Apple FM ~3B (on-device, macOS 26+)"}]
   :free-llm
   [{:model "auto" :curated-rank 34 :description "Free OpenAI-compatible endpoint (FREELLM_BASE_URL); 'auto' lets the backend pick"}]
   :anthropic
   [{:model "claude-opus-5" :curated-rank 3 :description "Anthropic Claude Opus 5 (flagship)"}
    {:model "claude-sonnet-5" :curated-rank 4 :description "Anthropic Claude Sonnet 5 (fast + smart)"}
    {:model "claude-haiku-4-5" :curated-rank 5 :description "Anthropic Claude Haiku 4.5 (fastest, cheap)"}
    {:model "claude-fable-5" :curated-rank 6 :description "Anthropic Claude Fable 5 (most capable, premium)"}
    {:model "claude-opus-4-8" :curated-rank 7 :description "Anthropic Claude Opus 4.8"}
    {:model "claude-sonnet-4-6" :curated-rank 8 :description "Anthropic Claude Sonnet 4.6"}
    {:model "claude-opus-4-7" :curated-rank 9 :description "Anthropic Claude Opus 4.7"}
    {:model "claude-mythos-5" :curated-rank 10 :description "Anthropic Claude Mythos 5 (Project Glasswing only)"}
    {:model "claude-opus-4-6"}
    {:model "claude-haiku-4-5-20251001"}
    {:model "claude-sonnet-4-5"}
    {:model "claude-sonnet-4-5-20250929"}
    {:model "claude-opus-4-5"}
    {:model "claude-opus-4-5-20251101"}
    ;; Deprecated but still served; retained so provider detection and
    ;; --resume of an old session keep working. Not surfaced by the picker.
    {:model "claude-sonnet-4-0"}
    {:model "claude-sonnet-4-20250514"}
    {:model "claude-opus-4-0"}
    {:model "claude-opus-4-20250514"}]
   :openai
   ;; GPT-5.6 ships as three co-released variants (luna / sol / terra) rather
   ;; than a tiered pro/mini/nano split; listed alphabetically because the
   ;; /v1/models payload exposes no capability ordering between them.
   [{:model "gpt-5.6-luna" :curated-rank 11 :description "OpenAI GPT-5.6 Luna"}
    {:model "gpt-5.6-sol" :curated-rank 12 :description "OpenAI GPT-5.6 Sol"}
    {:model "gpt-5.6-terra" :curated-rank 13 :description "OpenAI GPT-5.6 Terra"}
    {:model "gpt-5.5" :curated-rank 14 :description "OpenAI GPT-5.5"}
    {:model "gpt-5.4" :curated-rank 15 :description "OpenAI GPT-5.4"}
    {:model "gpt-5.4-mini" :curated-rank 16 :description "OpenAI GPT-5.4 Mini"}
    {:model "gpt-5.4-nano" :curated-rank 17 :description "OpenAI GPT-5.4 Nano (cheapest)"}
    {:model "gpt-5.2" :curated-rank 18 :description "OpenAI GPT-5.2"}
    {:model "gpt-5.1" :curated-rank 19 :description "OpenAI GPT-5.1"}
    {:model "gpt-5" :curated-rank 20 :description "OpenAI GPT-5"}
    {:model "gpt-5-mini" :curated-rank 21 :description "OpenAI GPT-5 Mini (fast, cheap)"}
    {:model "gpt-5-nano" :curated-rank 22 :description "OpenAI GPT-5 Nano (lowest latency)"}
    {:model "o3" :curated-rank 23 :description "OpenAI o3 (reasoning)"}
    {:model "o4-mini" :curated-rank 24 :description "OpenAI o4-mini (reasoning, cheap)"}
    {:model "gpt-4.1" :curated-rank 25 :description "OpenAI GPT-4.1 (coding)"}
    {:model "gpt-4.1-mini" :curated-rank 26 :description "OpenAI GPT-4.1 Mini"}
    {:model "gpt-5.3-chat-latest"}
    {:model "gpt-5.2-chat-latest"}
    {:model "gpt-4.1-nano"}
    {:model "gpt-4o"}
    {:model "gpt-4o-mini"}
    {:model "gpt-4-turbo"}
    {:model "gpt-4"}
    {:model "gpt-3.5-turbo"}
    {:model "o3-mini"}
    {:model "o1"}]
   ;; Deliberately absent — see `excluded-model-patterns` below, which is the
   ;; enforceable form of this note.
   :ollama
   [{:model "gemma3:12b"}
    {:model "glm-5:cloud" :curated-rank 33 :description "GLM-5 Cloud (Ollama)"}]
   :bedrock
   ;; Anthropic on Bedrock — prefer the `global.` cross-region inference
   ;; profiles; `us.`/`eu.`/`apac.` variants exist per partition.
   [{:model "global.anthropic.claude-opus-5" :curated-rank 36 :description "Claude Opus 5 on Bedrock (global cross-region, flagship)"}
    {:model "us.anthropic.claude-opus-5" :curated-rank 37 :description "Claude Opus 5 on Bedrock (US cross-region)"}
    {:model "global.anthropic.claude-sonnet-5" :curated-rank 38 :description "Claude Sonnet 5 on Bedrock (global cross-region)"}
    {:model "us.anthropic.claude-sonnet-5" :curated-rank 39 :description "Claude Sonnet 5 on Bedrock (US cross-region)"}
    {:model "global.anthropic.claude-fable-5" :curated-rank 40 :description "Claude Fable 5 on Bedrock (global cross-region)"}
    {:model "global.anthropic.claude-opus-4-8" :curated-rank 41 :description "Claude Opus 4.8 on Bedrock (global cross-region)"}
    {:model "global.anthropic.claude-opus-4-7" :curated-rank 42 :description "Claude Opus 4.7 on Bedrock (global cross-region)"}
    {:model "global.anthropic.claude-opus-4-6-v1" :curated-rank 43 :description "Claude Opus 4.6 on Bedrock (global cross-region)"}
    {:model "global.anthropic.claude-sonnet-4-6" :curated-rank 44 :description "Claude Sonnet 4.6 on Bedrock (global cross-region)"}
    {:model "global.anthropic.claude-haiku-4-5-20251001-v1:0" :curated-rank 45 :description "Claude Haiku 4.5 on Bedrock (global cross-region)"}
    {:model "us.anthropic.claude-haiku-4-5-20251001-v1:0" :curated-rank 46 :description "Claude Haiku 4.5 on Bedrock (US cross-region)"}
    {:model "global.anthropic.claude-opus-4-5-20251101-v1:0" :curated-rank 47 :description "Claude Opus 4.5 on Bedrock (global cross-region)"}
    {:model "us.anthropic.claude-sonnet-4-5-20250929-v1:0" :curated-rank 48 :description "Claude Sonnet 4.5 on Bedrock (US cross-region)"}
    {:model "anthropic.claude-opus-5"}
    {:model "anthropic.claude-sonnet-5"}
    {:model "anthropic.claude-fable-5"}
    {:model "anthropic.claude-opus-4-8"}
    {:model "anthropic.claude-opus-4-7"}
    {:model "anthropic.claude-opus-4-6-v1"}
    {:model "anthropic.claude-sonnet-4-6"}
    {:model "anthropic.claude-haiku-4-5-20251001-v1:0"}
    {:model "anthropic.claude-sonnet-4-5-20250929-v1:0"}
    {:model "anthropic.claude-opus-4-5-20251101-v1:0"}
    {:model "anthropic.claude-opus-4-1-20250805-v1:0"}
    {:model "anthropic.claude-sonnet-4-20250514-v1:0"}
    {:model "anthropic.claude-3-haiku-20240307-v1:0"}
    {:model "us.anthropic.claude-fable-5"}
    {:model "us.anthropic.claude-opus-4-8"}
    {:model "us.anthropic.claude-opus-4-7"}
    {:model "us.anthropic.claude-opus-4-6-v1"}
    {:model "us.anthropic.claude-sonnet-4-6"}
    {:model "us.anthropic.claude-opus-4-1-20250805-v1:0"}
    {:model "us.anthropic.claude-opus-4-5-20251101-v1:0"}
    {:model "global.anthropic.claude-sonnet-4-5-20250929-v1:0"}
    {:model "eu.anthropic.claude-opus-5"}
    {:model "eu.anthropic.claude-sonnet-5"}
    {:model "eu.anthropic.claude-opus-4-8"}
    {:model "eu.anthropic.claude-sonnet-4-6"}
    {:model "eu.anthropic.claude-haiku-4-5-20251001-v1:0"}
    {:model "apac.anthropic.claude-3-5-sonnet-20241022-v2:0"}
    {:model "apac.anthropic.claude-sonnet-4-20250514-v1:0"}
    ;; Amazon Nova
    {:model "us.amazon.nova-2-lite-v1:0" :curated-rank 49 :description "Amazon Nova 2 Lite on Bedrock (US cross-region, fast)"}
    {:model "us.amazon.nova-premier-v1:0" :curated-rank 50 :description "Amazon Nova Premier on Bedrock (US cross-region)" :region "us-east-1"}
    {:model "us.amazon.nova-pro-v1:0" :curated-rank 51 :description "Amazon Nova Pro on Bedrock (US cross-region, multimodal)"}
    {:model "us.amazon.nova-lite-v1:0" :curated-rank 52 :description "Amazon Nova Lite on Bedrock (US cross-region, fast)"}
    {:model "amazon.nova-2-lite-v1:0"}
    {:model "amazon.nova-premier-v1:0" :region "us-east-1"}
    {:model "amazon.nova-pro-v1:0"}
    {:model "amazon.nova-lite-v1:0"}
    {:model "amazon.nova-micro-v1:0"}
    {:model "global.amazon.nova-2-lite-v1:0"}
    {:model "us.amazon.nova-micro-v1:0"}
    {:model "eu.amazon.nova-pro-v1:0"}
    {:model "eu.amazon.nova-lite-v1:0"}
    {:model "eu.amazon.nova-micro-v1:0"}
    {:model "eu.amazon.nova-2-lite-v1:0"}
    {:model "apac.amazon.nova-pro-v1:0"}
    {:model "apac.amazon.nova-lite-v1:0"}
    {:model "apac.amazon.nova-micro-v1:0"}
    ;; Open-weights and third-party. Entries pinned to us-east-1 are not
    ;; served in every region (verified against list-foundation-models).
    {:model "meta.llama3-3-70b-instruct-v1:0" :curated-rank 53 :description "Meta Llama 3.3 70B on Bedrock"}
    {:model "openai.gpt-oss-120b-1:0" :curated-rank 54 :description "OpenAI gpt-oss 120B (open-weights) on Bedrock" :region "us-east-1"}
    {:model "openai.gpt-oss-20b-1:0" :curated-rank 55 :description "OpenAI gpt-oss 20B (open-weights) on Bedrock" :region "us-east-1"}
    {:model "qwen.qwen3-32b-v1:0" :curated-rank 56 :description "Qwen3 32B (dense) on Bedrock" :region "us-east-1"}
    {:model "qwen.qwen3-coder-30b-a3b-v1:0" :curated-rank 57 :description "Qwen3 Coder 30B (A3B) on Bedrock" :region "us-east-1"}
    {:model "qwen.qwen3-vl-235b-a22b" :curated-rank 58 :description "Qwen3 VL 235B A22B (vision) on Bedrock" :region "us-east-1"}
    {:model "deepseek.r1-v1:0" :curated-rank 59 :description "DeepSeek-R1 (reasoning) on Bedrock" :region "us-east-1"}
    {:model "us.deepseek.r1-v1:0" :curated-rank 60 :description "DeepSeek-R1 on Bedrock (US cross-region)" :region "us-east-1"}
    {:model "deepseek.v3.2" :curated-rank 61 :description "DeepSeek V3.2 on Bedrock" :region "us-east-1"}
    {:model "mistral.mistral-large-3-675b-instruct" :curated-rank 62 :description "Mistral Large 3 675B on Bedrock" :region "us-east-1"}
    {:model "zai.glm-5" :curated-rank 63 :description "Z.ai GLM-5 on Bedrock" :region "us-east-1"}
    {:model "minimax.minimax-m2.5" :curated-rank 64 :description "MiniMax M2.5 on Bedrock" :region "us-east-1"}
    {:model "moonshotai.kimi-k2.5" :curated-rank 65 :description "Moonshot Kimi K2.5 on Bedrock" :region "us-east-1"}
    {:model "ai21.jamba-1-5-large-v1:0" :curated-rank 66 :description "AI21 Jamba 1.5 Large on Bedrock" :region "us-east-1"}
    {:model "writer.palmyra-x5-v1:0" :curated-rank 67 :description "Writer Palmyra X5 on Bedrock" :region "us-east-1"}
    {:model "us.writer.palmyra-x5-v1:0" :curated-rank 68 :description "Writer Palmyra X5 on Bedrock (US cross-region)" :region "us-east-1"}
    {:model "meta.llama3-1-70b-instruct-v1:0"}
    {:model "meta.llama3-1-8b-instruct-v1:0"}
    {:model "meta.llama3-70b-instruct-v1:0"}
    {:model "meta.llama3-8b-instruct-v1:0"}
    {:model "meta.llama4-maverick-17b-instruct-v1:0"}
    {:model "meta.llama4-scout-17b-instruct-v1:0"}
    {:model "us.meta.llama4-maverick-17b-instruct-v1:0"}
    {:model "us.meta.llama4-scout-17b-instruct-v1:0"}
    {:model "openai.gpt-oss-safeguard-120b"}
    {:model "openai.gpt-oss-safeguard-20b"}
    {:model "qwen.qwen3-coder-next"}
    {:model "qwen.qwen3-next-80b-a3b"}
    {:model "zai.glm-4.7"}
    {:model "zai.glm-4.7-flash"}
    {:model "minimax.minimax-m2"}
    {:model "minimax.minimax-m2.1"}
    {:model "moonshot.kimi-k2-thinking"}
    {:model "nvidia.nemotron-super-3-120b"}
    {:model "nvidia.nemotron-nano-3-30b"}
    {:model "nvidia.nemotron-nano-9b-v2"}
    {:model "nvidia.nemotron-nano-12b-v2"}
    {:model "google.gemma-3-27b-it"}
    {:model "google.gemma-3-12b-it"}
    {:model "google.gemma-3-4b-it"}
    {:model "mistral.mistral-large-2402-v1:0"}
    {:model "mistral.mistral-small-2402-v1:0"}
    {:model "mistral.mixtral-8x7b-instruct-v0:1"}
    {:model "mistral.mistral-7b-instruct-v0:2"}
    {:model "mistral.pixtral-large-2502-v1:0"}
    {:model "mistral.devstral-2-123b"}
    {:model "mistral.magistral-small-2509"}
    {:model "mistral.ministral-3-14b-instruct"}
    {:model "mistral.ministral-3-8b-instruct"}
    {:model "mistral.ministral-3-3b-instruct"}
    {:model "cohere.command-r-plus-v1:0"}
    {:model "cohere.command-r-v1:0"}
    {:model "ai21.jamba-1-5-mini-v1:0"}
    {:model "writer.palmyra-x4-v1:0"}
    {:model "us.writer.palmyra-x4-v1:0"}
    {:model "writer.palmyra-vision-7b"}]
   :google
   [{:model "gemini-3-flash-preview"}
    {:model "gemini-1.5-pro"}
    {:model "gemini-1.5-flash"}
    {:model "gemini-2.5-pro" :curated-rank 27 :description "Google Gemini 2.5 Pro (advanced)"}
    {:model "gemini-2.5-flash-lite"}
    {:model "gemini-2.5-flash" :curated-rank 28 :description "Google Gemini 2.5 Flash (very cheap)"}
    {:model "gemini-3.1-pro-preview"}
    {:model "gemini-3-pro-preview"}
    {:model "gemini-2.0-flash"}]
   :deepseek
   [{:model "deepseek-chat" :curated-rank 29 :description "DeepSeek V3.2 (ultra cheap)"}
    {:model "deepseek-reasoner" :curated-rank 30 :description "DeepSeek V3.2 Reasoner"}]))

(def excluded-model-patterns
  "Ids a provider serves that this client deliberately does NOT catalogue,
   with the reason. Consulted by the refresh so they are never discovered and
   never proposed.

   This is DATA rather than a comment because a comment cannot be enforced. It
   was one: a prose note in the `:openai` block recording that these had been
   probed and rejected. The first live `bb catalog:refresh` then proposed all
   40 of them as new models, and would have done so on every run forever —
   the note was invisible to the tool that needed it. Same lesson as the
   feature ledger being data rather than section comments.

   Patterns rather than an id list, because the families keep growing: a
   `gpt-6-pro` would need re-rejecting by hand otherwise. Over-matching costs
   only a catalog entry a user can still select explicitly by id, since
   nothing here affects provider detection or `create-lm`."
  {:openai [;; Served only by /v1/responses; this client speaks
            ;; /v1/chat/completions, and they answer "not a chat model".
            #"-pro$" #"-pro-\d{4}-\d{2}-\d{2}$"
            ;; The codex line reports deprecated.
            #"-codex$" #"-codex-max$" #"-codex-mini$"
            ;; Superseded aliases that report deprecated.
            #"^gpt-5-chat-latest$" #"^gpt-5\.1-chat-latest$"
            ;; Search-augmented variants: a different product surface, not a
            ;; chat model you would pick from the model picker.
            #"-search-api" #"-search-preview"]})

(defn excluded-model?
  "True when `id` is one `provider` serves but this client deliberately does
   not catalogue."
  [provider id]
  (boolean (some #(re-find % (str id)) (get excluded-model-patterns provider []))))

;; ============================================================================
;; Effective catalog = baked ∪ refresh overlay
;; ============================================================================

(def ^:private !catalog-cache
  "Memo of the merged catalog and its reverse index, keyed on the IDENTITY of
   the overlay value that produced them.

   `get-popular-models` runs on the autocomplete path — once per keystroke —
   so re-merging per call is not acceptable. The overlay is swapped wholesale
   by `catalog/set-overlay!`, so `identical?` on the value is a sound and
   allocation-free staleness check."
  (atom {:overlay ::none :catalog nil :index nil}))

(defn- catalog-view
  "Merged catalog + reverse index, recomputed only when the overlay changes."
  []
  (let [ov     (catalog/overlay)
        cached @!catalog-cache]
    (if (identical? ov (:overlay cached))
      cached
      (let [merged (catalog/merge-catalog model-catalog ov)
            index  (into {} (for [[provider models] merged
                                  {:keys [model]} models]
                              [model provider]))]
        (reset! !catalog-cache {:overlay ov :catalog merged :index index})))))

(defn current-catalog
  "`model-catalog` with the refresh overlay applied — the effective catalog
   every derived view reads.

   Identical to `model-catalog` until something calls `catalog/set-overlay!`,
   so offline and first-run behaviour is byte-for-byte what it was before the
   refresh mechanism existed."
  []
  (:catalog (catalog-view)))

;; Reverse index model-id -> provider, DERIVED from the effective catalog. Used
;; by get-provider-from-model for catalog lookup. Unambiguous: no model id
;; appears under more than one provider.
(defn- model->provider [model]
  (get (:index (catalog-view)) model))

(def ^:private drop-temperature-exact
  "Exact model names that reject the `temperature` parameter.
   Membership was established by probing each id against
   /v1/chat/completions — note gpt-5.4/5.2/5.1 accept it while their
   -mini/-nano siblings and the 5.5/5.6 generations do not, so this cannot
   be inferred from the family name."
  #{"gpt-5.6-luna" "gpt-5.6-sol" "gpt-5.6-terra"
    "gpt-5.5"
    "gpt-5.4-mini" "gpt-5.4-nano"
    "gpt-5" "gpt-5-mini" "gpt-5-nano"
    "gpt-5.3-chat-latest" "gpt-5.2-chat-latest"
    "o1" "o3" "o3-mini" "o4-mini"})

(defn- drops-temperature?
  "True if the model rejects (or ignores) the `temperature` parameter.
   When detected, `create-lm` sets `:drop-params #{:temperature}` automatically.
   Matches by exact name (OpenAI GPT-5/o-series reasoning models) and by
   substring for the Claude families that reject sampling params (Opus 5,
   Sonnet 5, Opus 4.8, Opus 4.7, Fable, Mythos) on the Anthropic API and on
   Bedrock under every prefix (anthropic., us.anthropic., global.anthropic., …).
   Opus 4.6 and Sonnet 4.6 still accept sampling params and are absent here.
   The substrings are version-anchored so `claude-opus-5` cannot match
   `claude-opus-4-5`, nor `claude-sonnet-5` match `claude-sonnet-4-5`."
  [model]
  (boolean
   (when (string? model)
     (or (contains? drop-temperature-exact model)
         (some #(str/includes? model %)
               ["claude-opus-5" "claude-sonnet-5"
                "claude-opus-4-8" "claude-opus-4-7"
                "claude-fable" "claude-mythos"])))))

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
   ;; Bare-vendor Bedrock prefixes (must precede openai/ which is OpenRouter-style).
   ;; `anthropic.` is dotted (Bedrock) vs `anthropic/` (OpenRouter) below — a
   ;; direct Anthropic id is "claude-*" and never starts with either.
   ["anthropic."      :bedrock]
   ["openai."         :bedrock]
   ["qwen."           :bedrock]
   ["deepseek."       :bedrock]
   ["ai21."           :bedrock]
   ["writer."         :bedrock]
   ["zai."            :bedrock]
   ["minimax."        :bedrock]
   ["moonshot."       :bedrock]
   ["moonshotai."     :bedrock]
   ["nvidia."         :bedrock]
   ["google."         :bedrock]
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
   then model catalogs.

   Returns nil (never throws) for a nil, blank, or non-string model. The
   String interop below previously ran unguarded, so a nil model raised
   `NullPointerException: Cannot invoke \"String.startsWith(String)\"` and a
   keyword/number raised ClassCastException — opaque failures surfacing from
   deep inside `create-lm`. Callers decide what an undetectable id means;
   `create-lm` turns it into an actionable ex-info."
  [model]
  (when-not (str/blank? (when (string? model) model))
    (or
     ;; Check explicit provider prefixes
     (some (fn [[prefix provider]]
             (when (str/starts-with? model prefix)
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
       (str/starts-with? model "mistral.") :bedrock
       ;; Default: if contains "claude" -> anthropic, else openai
       (str/includes? model "claude") :anthropic
       :else :openai))))

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
  [lm-str]
  (if-not (string? lm-str)
    ;; nil / keyword / number: no separator to find, and .indexOf would NPE.
    [nil nil]
    (let [^String s lm-str
          slash (.indexOf s "/")]
      (if (>= slash 0)
        [(subs s 0 slash) (subs s (inc slash))]
        (let [colon (.indexOf s ":")]
          (if (>= colon 0)
            [(subs s 0 colon) (subs s (inc colon))]
            [s nil]))))))

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
                     human-paced turn gaps. Anthropic direct: adds the
                     extended-cache-ttl beta header. Bedrock: cachePoint
                     {:ttl \"1h\"} (Converse CacheTTL; needs bedrock-runtime
                     ≥ 871.2.42.29 and a model that supports extended cache
                     — Anthropic Claude models). Write premium is 2x base
                     input, paid once per stable zone per session. OpenAI/
                     Azure: any value beyond \"5m\" requests
                     prompt_cache_retention \"24h\" (extended retention, same
                     price as in-memory). No-op on other providers.
     :prompt-cache-key - (OpenAI/Azure) stable routing key combined with the
                     prefix hash to improve cache hit rates. One key per
                     session; keep each prefix+key under ~15 req/min.
     :drop-params  - Set of param keywords to omit from API requests (auto-detected for
                     models that reject temperature, e.g. o-series, gpt-5 family)
     :region       - (Bedrock) AWS region. Falls back to AWS_REGION /
                     AWS_DEFAULT_REGION env then :default-region of provider.
     :aws-profile  - (Bedrock) Named AWS profile from ~/.aws/credentials.
                     Falls back to AWS_PROFILE then AWS_DEFAULT_PROFILE env vars.
     :credentials-provider - (Bedrock) Custom cognitect aws-api credentials
                             provider; overrides profile/env-based detection.
     :backend      - (ACP) ACP backend keyword (:stub/:claude-code/:gemini/…);
                     kept on the lm-config for the :acp provider.
     :acp-client-fs - (ACP) advertise the client filesystem capability
                     (default true; BY_ACP_CLIENT_FS overrides when unset).
                     false → the backend does its own direct disk I/O."
  [{:keys [model api-key temperature max-tokens base-url provider prompt-cache cache-ttl
           prompt-cache-key drop-params region aws-profile credentials-provider
           backend acp-client-fs]}]
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
        ;; Fail fast on a missing/garbage :model. Previously this fell through
        ;; to unguarded String interop and died with an opaque
        ;; `NullPointerException: Cannot invoke "String.startsWith(String)"`.
        ;; Also catches `(create-lm "openai/gpt-4o")` — a string arg destructures
        ;; to all-nil options.
        _                 (when (str/blank? (when (string? model) model))
                            (throw (ex-info
                                    (str "create-lm: :model must be a non-blank string, got "
                                         (pr-str model)
                                         ". Pass an options map, e.g. "
                                         "(create-lm {:model \"openai/gpt-4o\"}).")
                                    {:error    :invalid-model
                                     :model    model
                                     :provider provider})))
        detected-provider (or provider spec-provider (get-provider-from-model model))
        provider-config   (get providers detected-provider)
        ;; An unregistered provider yields a nil provider-config, which used to
        ;; produce a silently degenerate lm-config ({:base-url nil :auth-header
        ;; nil :message-format nil}) that only blew up much later at request
        ;; time. Reject it here with the list of registered providers.
        _                 (when-not provider-config
                            (throw (ex-info
                                    (str "create-lm: unknown provider "
                                         (pr-str detected-provider)
                                         " (model " (pr-str model) "). Known providers: "
                                         (str/join ", " (map name (sort (keys providers)))) ".")
                                    {:error           :unknown-provider
                                     :provider        detected-provider
                                     :model           model
                                     :known-providers (vec (sort (keys providers)))})))
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
                                    (System/getProperty env-var))))
        acp?              (= :acp detected-provider)
        ;; ACP client fs capability (headless path): explicit arg →
        ;; BY_ACP_CLIENT_FS env → default true. Mirrors the agent component's
        ;; :acp-client-fs config so the same env var toggles both paths; the
        ;; :acp provider (core/acp.clj) reads it off the lm-config.
        resolved-acp-fs   (when acp?
                            (if (some? acp-client-fs)
                              (boolean acp-client-fs)
                              (if-some [v (System/getenv "BY_ACP_CLIENT_FS")]
                                (= "true" v) true)))]
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
      prompt-cache-key (assoc :prompt-cache-key prompt-cache-key)
      resolved-drop   (assoc :drop-params resolved-drop)
      bedrock?        (assoc :auth-type :aws-sigv4
                             :region    resolved-region)
      (and bedrock? resolved-profile)     (assoc :aws-profile resolved-profile)
      (and bedrock? credentials-provider) (assoc :credentials-provider credentials-provider)
      acp?            (assoc :acp-client-fs resolved-acp-fs)
      (and acp? backend) (assoc :backend backend))))

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
               (current-catalog))
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
  (let [entries (for [[prov models] (current-catalog)
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
                  (current-catalog))]
    (cond
      (nil? provider)          all
      (contains? all provider) (select-keys all [provider])
      :else                    (throw (ex-info (str "Unknown provider: " provider)
                                               {:provider            provider
                                                :available-providers (vec (sort (keys all)))})))))

(defn pricing-coverage
  "Which catalog models have a per-token price and which do not.

   The catalog (`:model`/`:curated-rank`/`:description`/`:region`) and the
   pricing table (`usage/default-pricing`, keyed `[provider model]`) are two
   independent hand-curated sources, and nothing kept them in step. A model
   catalogued but unpriced still runs — it just reports a cost of 0.0, which
   reads as free rather than as unknown. That is the failure this surfaces.

   Deliberately reports against the BAKED catalog by default rather than the
   refreshed one: an unpriced model that a refresh surfaced is not a curation
   gap anybody can fix in this repo, whereas an unpriced model we ship is.
   Pass `:catalog` to check a refreshed view instead.

   Returns `{:priced […] :unpriced […] :not-applicable […] :counts {…}}`, each
   a vector of `{:provider :model}` sorted by provider then model.

   `:unpriced` is the ACTIONABLE set. Providers that cannot meaningfully carry
   a per-token rate are separated into `:not-applicable` rather than counted as
   gaps — a report that lists them every run is one nobody reads:

     :claude-code  the CLI reports cost_usd directly and `build-usage-map`
                   uses it in preference to the table, so a rate here would
                   never be consulted.
     :ollama       local inference; the marginal token cost is zero.
     :apple-fm     on-device.
     :free-llm     free by definition, and the endpoint is user-supplied.

   `:curated-only?` narrows to entries that reach the /model picker — the set
   a user can actually select, and so the set where a silent 0.0 is most
   likely to be seen and believed."
  [& {:keys [catalog curated-only?]}]
  (let [not-applicable-providers #{:claude-code :ollama :apple-fm :free-llm}
        entries (for [[prov models] (or catalog model-catalog)
                      m models
                      :when (or (not curated-only?) (:curated-rank m))]
                  {:provider prov :model (:model m)})
        {:keys [priced unpriced not-applicable]}
        (reduce (fn [acc {:keys [provider model] :as e}]
                  (update acc
                          (cond
                            (usage/get-pricing provider model)      :priced
                            (not-applicable-providers provider)     :not-applicable
                            :else                                   :unpriced)
                          conj e))
                {:priced [] :unpriced [] :not-applicable []}
                entries)
        by-key (fn [v] (vec (sort-by (juxt #(name (:provider %)) :model) v)))]
    {:priced         (by-key priced)
     :unpriced       (by-key unpriced)
     :not-applicable (by-key not-applicable)
     :counts         {:priced         (count priced)
                      :unpriced       (count unpriced)
                      :not-applicable (count not-applicable)
                      :total          (count entries)}}))

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
