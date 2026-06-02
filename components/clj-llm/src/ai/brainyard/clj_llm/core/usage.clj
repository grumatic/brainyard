;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.clj-llm.core.usage
  "Token usage extraction, cost calculation, and usage tracking for LLM calls."
  (:require [ai.brainyard.mulog.interface :as mulog]))

;; ============================================================================
;; Pricing Table
;; ============================================================================

(def ^:private default-pricing
  "Per-1M-token pricing keyed by [provider model].
   Rates: :input, :output, :cache-read, :cache-write (per 1M tokens)."
  {;; OpenAI — GPT-5 family
   [:openai "gpt-5"]            {:input 1.25  :output 10.00 :cache-read 0.625 :cache-write 1.25}
   [:openai "gpt-5-mini"]       {:input 0.25  :output 2.00  :cache-read 0.125 :cache-write 0.25}
   [:openai "gpt-5-nano"]       {:input 0.05  :output 0.40  :cache-read 0.025 :cache-write 0.05}
   ;; OpenAI — GPT-4.1 family
   [:openai "gpt-4.1"]          {:input 2.00  :output 8.00  :cache-read 0.50  :cache-write 2.00}
   [:openai "gpt-4.1-mini"]     {:input 0.40  :output 1.60  :cache-read 0.10  :cache-write 0.40}
   [:openai "gpt-4.1-nano"]     {:input 0.10  :output 0.40  :cache-read 0.025 :cache-write 0.10}
   ;; OpenAI — GPT-4o family
   [:openai "gpt-4o"]           {:input 2.50  :output 10.00 :cache-read 1.25  :cache-write 2.50}
   [:openai "gpt-4o-mini"]      {:input 0.15  :output 0.60  :cache-read 0.075 :cache-write 0.15}
   ;; OpenAI — o-series (reasoning)
   [:openai "o3"]               {:input 2.00  :output 8.00  :cache-read 1.00  :cache-write 2.00}
   [:openai "o3-mini"]          {:input 1.10  :output 4.40  :cache-read 0.55  :cache-write 1.10}
   [:openai "o4-mini"]          {:input 1.10  :output 4.40  :cache-read 0.55  :cache-write 1.10}
   ;; OpenAI — Legacy
   [:openai "gpt-4-turbo"]      {:input 10.00 :output 30.00 :cache-read 5.00  :cache-write 10.00}
   [:openai "o1"]               {:input 15.00 :output 60.00 :cache-read 7.50  :cache-write 15.00}
   [:openai "o1-mini"]          {:input 3.00  :output 12.00 :cache-read 1.50  :cache-write 3.00}
   ;; Anthropic (pricing from https://docs.anthropic.com/en/docs/build-with-claude/prompt-caching#pricing)
   [:anthropic "claude-opus-4-7"]           {:input 5.00  :output 25.00 :cache-read 0.50  :cache-write 6.25}
   [:anthropic "claude-opus-4-6"]           {:input 5.00  :output 25.00 :cache-read 0.50  :cache-write 6.25}
   [:anthropic "claude-sonnet-4-6"]         {:input 3.00  :output 15.00 :cache-read 0.30  :cache-write 3.75}
   [:anthropic "claude-sonnet-4-5"]         {:input 3.00  :output 15.00 :cache-read 0.30  :cache-write 3.75}
   [:anthropic "claude-sonnet-4-5-20250929"] {:input 3.00  :output 15.00 :cache-read 0.30  :cache-write 3.75}
   [:anthropic "claude-haiku-4-5"]          {:input 1.00  :output 5.00  :cache-read 0.10  :cache-write 1.25}
   [:anthropic "claude-haiku-4-5-20251001"] {:input 1.00  :output 5.00  :cache-read 0.10  :cache-write 1.25}
   [:anthropic "claude-opus-4-5"]           {:input 5.00  :output 25.00 :cache-read 0.50  :cache-write 6.25}
   [:anthropic "claude-opus-4-5-20251101"]  {:input 5.00  :output 25.00 :cache-read 0.50  :cache-write 6.25}
   [:anthropic "claude-opus-4-1"]           {:input 15.00 :output 75.00 :cache-read 1.50  :cache-write 18.75}
   [:anthropic "claude-opus-4-1-20250805"]  {:input 15.00 :output 75.00 :cache-read 1.50  :cache-write 18.75}
   [:anthropic "claude-sonnet-4-0"]         {:input 3.00  :output 15.00 :cache-read 0.30  :cache-write 3.75}
   [:anthropic "claude-sonnet-4-20250514"]  {:input 3.00  :output 15.00 :cache-read 0.30  :cache-write 3.75}
   [:anthropic "claude-3-5-sonnet-20241022"]  {:input 3.00  :output 15.00 :cache-read 0.30  :cache-write 3.75}
   [:anthropic "claude-3-5-haiku-20241022"]   {:input 0.80  :output 4.00  :cache-read 0.08  :cache-write 1.00}
   [:anthropic "claude-3-haiku-20240307"]     {:input 0.25  :output 1.25  :cache-read 0.03  :cache-write 0.30}
   ;; Google — Gemini 2.5 (stable)
   [:google "gemini-2.5-flash"]      {:input 0.15  :output 0.60  :cache-read 0.0375 :cache-write 0.15}
   [:google "gemini-2.5-flash-lite"] {:input 0.075 :output 0.30  :cache-read 0.019  :cache-write 0.075}
   [:google "gemini-2.5-pro"]        {:input 1.25  :output 10.00 :cache-read 0.315  :cache-write 1.25}
   ;; Google — Legacy
   [:google "gemini-2.0-flash"]      {:input 0.10  :output 0.40  :cache-read 0.025 :cache-write 0.10}
   [:google "gemini-1.5-pro"]        {:input 1.25  :output 5.00  :cache-read 0.315 :cache-write 1.25}
   [:google "gemini-1.5-flash"]      {:input 0.075 :output 0.30  :cache-read 0.019 :cache-write 0.075}
   ;; DeepSeek V3.2 (updated pricing)
   [:deepseek "deepseek-chat"]       {:input 0.28  :output 0.42  :cache-read 0.028 :cache-write 0.28}
   [:deepseek "deepseek-reasoner"]   {:input 0.28  :output 0.42  :cache-read 0.028 :cache-write 0.28}
   ;; Groq (free tier / very cheap)
   [:groq "llama-3.3-70b-versatile"] {:input 0.59  :output 0.79  :cache-read 0.30  :cache-write 0.59}
   [:groq "llama-3.1-8b-instant"]    {:input 0.05  :output 0.08  :cache-read 0.025 :cache-write 0.05}
   ;; Bedrock — Anthropic on Bedrock (rates match the direct Anthropic API).
   ;; Keys use the normalized form: region prefix and -v\d+:\d+ suffix stripped.
   [:bedrock "anthropic.claude-sonnet-4-6"] {:input 3.00  :output 15.00 :cache-read 0.30 :cache-write 3.75}
   [:bedrock "anthropic.claude-sonnet-4-5"] {:input 3.00  :output 15.00 :cache-read 0.30 :cache-write 3.75}
   [:bedrock "anthropic.claude-sonnet-4-0"] {:input 3.00  :output 15.00 :cache-read 0.30 :cache-write 3.75}
   [:bedrock "anthropic.claude-haiku-4-5"]  {:input 1.00  :output 5.00  :cache-read 0.10 :cache-write 1.25}
   [:bedrock "anthropic.claude-opus-4-7"]   {:input 5.00  :output 25.00 :cache-read 0.50 :cache-write 6.25}
   [:bedrock "anthropic.claude-opus-4-6"]   {:input 5.00  :output 25.00 :cache-read 0.50 :cache-write 6.25}
   [:bedrock "anthropic.claude-opus-4-5"]   {:input 5.00  :output 25.00 :cache-read 0.50 :cache-write 6.25}
   [:bedrock "anthropic.claude-opus-4-1"]   {:input 15.00 :output 75.00 :cache-read 1.50 :cache-write 18.75}
   [:bedrock "anthropic.claude-3-5-sonnet"] {:input 3.00  :output 15.00 :cache-read 0.30 :cache-write 3.75}
   [:bedrock "anthropic.claude-3-5-haiku"]  {:input 0.80  :output 4.00  :cache-read 0.08 :cache-write 1.00}
   [:bedrock "anthropic.claude-3-haiku"]    {:input 0.25  :output 1.25  :cache-read 0.03 :cache-write 0.30}
   ;; Amazon Nova on Bedrock (cache-write derived as 1.25x input).
   [:bedrock "amazon.nova-pro"]   {:input 0.80  :output 3.20 :cache-read 0.20    :cache-write 1.00}
   [:bedrock "amazon.nova-lite"]  {:input 0.06  :output 0.24 :cache-read 0.015   :cache-write 0.075}
   [:bedrock "amazon.nova-micro"] {:input 0.035 :output 0.14 :cache-read 0.00875 :cache-write 0.04375}
   ;; Meta Llama on Bedrock (no prompt caching).
   [:bedrock "meta.llama3-3-70b-instruct"] {:input 0.72 :output 0.72}
   [:bedrock "meta.llama3-1-70b-instruct"] {:input 0.99 :output 0.99}
   [:bedrock "meta.llama3-1-8b-instruct"]  {:input 0.22 :output 0.22}
   ;; Mistral on Bedrock (no prompt caching).
   [:bedrock "mistral.mistral-large-2407"] {:input 2.00 :output 6.00}
   [:bedrock "mistral.mistral-small-2402"] {:input 1.00 :output 3.00}
   ;; Cohere Command on Bedrock (no prompt caching).
   [:bedrock "cohere.command-r-plus"] {:input 3.00 :output 15.00}
   [:bedrock "cohere.command-r"]      {:input 0.50 :output 1.50}
   ;; DeepSeek on Bedrock.
   [:bedrock "deepseek.r1"]   {:input 1.35 :output 5.40}
   [:bedrock "deepseek.v3.2"] {:input 0.28 :output 0.42}})

(defonce ^:private pricing-table (atom default-pricing))

(defn set-pricing!
  "Override or extend the pricing table.
   pricing-map is keyed by [provider model] with values {:input :output :cache-read :cache-write}."
  [pricing-map]
  (swap! pricing-table merge pricing-map))

(defn reset-pricing!
  "Reset the pricing table to defaults."
  []
  (reset! pricing-table default-pricing))

(defn- strip-version-suffix
  "Strip date version suffix from model name.
   e.g. \"gpt-4.1-mini-2025-04-14\" → \"gpt-4.1-mini\"
        \"claude-sonnet-4-5-20250929\" → \"claude-sonnet-4-5\""
  [model]
  (some-> model (clojure.string/replace #"-\d{4}-?\d{2}-?\d{2}$" "")))

(defn- normalize-bedrock-model-id
  "Normalize a Bedrock model id by stripping region prefix, the -v\\d+(:\\d+)?
   suffix, and any trailing date stamp. Lets one pricing entry match every
   cross-region inference-profile variant of a model.
   e.g. \"us.anthropic.claude-sonnet-4-5-20250929-v1:0\" → \"anthropic.claude-sonnet-4-5\"
        \"global.anthropic.claude-opus-4-6-v1\"          → \"anthropic.claude-opus-4-6\"
        \"amazon.nova-pro-v1:0\"                          → \"amazon.nova-pro\""
  [model]
  (some-> model
          (clojure.string/replace #"^(us|eu|apac|global)\." "")
          (clojure.string/replace #"-v\d+(:\d+)?$" "")
          (clojure.string/replace #"-\d{4}-?\d{2}-?\d{2}$" "")))

(defn get-pricing
  "Get pricing for a [provider model] pair.
   Falls back to stripping a date version suffix, and for :bedrock additionally
   to stripping the region prefix + -v\\d+(:\\d+)? suffix."
  [provider model]
  (let [table @pricing-table]
    (or (get table [provider model])
        (get table [provider (strip-version-suffix model)])
        (when (= :bedrock provider)
          (get table [provider (normalize-bedrock-model-id model)])))))

;; ============================================================================
;; Usage Extraction
;; ============================================================================

;; -----------------------------------------------------------------------
;; Token-accounting convention (uniform across providers)
;; -----------------------------------------------------------------------
;; :input-tokens   — TOTAL tokens the model processed on the input side,
;;                   including any served from cache and any newly written
;;                   to cache. This matches what OpenAI's `prompt_tokens`
;;                   already reports; for Anthropic-family providers we
;;                   sum the three sub-categories here so the surface is
;;                   consistent.
;; :output-tokens  — Generated output tokens.
;; :total-tokens   — :input-tokens + :output-tokens (uniformly).
;; :cache          — Sub-categories included in :input-tokens for context:
;;                   :read-tokens served from cache, :write-tokens added to
;;                   cache. They are NOT double-counted on top of
;;                   :input-tokens — they live inside it.
;;
;; Cost calc (`calculate-cost`) recovers the fresh-input portion as
;; (:input-tokens - cache-read - cache-write) and applies the three
;; per-category rates separately.

(defn- extract-openai-usage
  "Extract usage from an OpenAI-compatible API response.
   OpenAI's `prompt_tokens` already includes cached tokens, so :input-tokens
   maps to it directly. `prompt_tokens_details.cached_tokens` is surfaced as
   a sub-count under :cache."
  [response]
  (when-let [usage (:usage response)]
    (let [prompt-tokens  (or (:prompt_tokens usage) 0)
          completion-tokens (or (:completion_tokens usage) 0)
          total-tokens   (or (:total_tokens usage) (+ prompt-tokens completion-tokens))
          details        (:prompt_tokens_details usage)
          cached-tokens  (or (:cached_tokens details) 0)]
      {:input-tokens  prompt-tokens
       :output-tokens completion-tokens
       :total-tokens  total-tokens
       :cache         {:read-tokens  cached-tokens
                       :write-tokens 0}})))

(defn- extract-anthropic-usage
  "Extract usage from an Anthropic Messages API response.
   Anthropic reports input in three non-overlapping categories
   (input_tokens, cache_read_input_tokens, cache_creation_input_tokens).
   We sum them into :input-tokens so the surface matches OpenAI's
   already-total convention."
  [response]
  (when-let [usage (:usage response)]
    (let [in-fresh      (or (:input_tokens usage) 0)
          output-tokens (or (:output_tokens usage) 0)
          cache-read    (or (:cache_read_input_tokens usage) 0)
          cache-write   (or (:cache_creation_input_tokens usage) 0)
          input-tokens  (+ in-fresh cache-read cache-write)]
      {:input-tokens  input-tokens
       :output-tokens output-tokens
       :total-tokens  (+ input-tokens output-tokens)
       :cache         {:read-tokens  cache-read
                       :write-tokens cache-write}})))

(defn- extract-embedding-usage
  "Extract usage from an embedding API response."
  [response]
  (when-let [usage (:usage response)]
    {:input-tokens  (or (:prompt_tokens usage) (:total_tokens usage) 0)
     :output-tokens 0
     :total-tokens  (or (:total_tokens usage) 0)
     :cache         {:read-tokens 0 :write-tokens 0}}))

(defn- extract-claude-code-usage
  "Extract usage from a Claude CLI response.
   CLI provides cost_usd directly; token counts may be present.
   Forwards cache_read_input_tokens / cache_creation_input_tokens emitted
   by the CLI's `result` event. Follows the Anthropic convention: the
   three sub-categories are summed into :input-tokens so the surface
   matches the uniform contract."
  [response]
  (let [usage (:usage response)
        in-fresh      (or (:input_tokens usage) 0)
        output-tokens (or (:output_tokens usage) 0)
        cache-read    (or (:cache_read_input_tokens usage) 0)
        cache-write   (or (:cache_creation_input_tokens usage) 0)
        input-tokens  (+ in-fresh cache-read cache-write)]
    {:input-tokens  input-tokens
     :output-tokens output-tokens
     :total-tokens  (+ input-tokens output-tokens)
     :cache         {:read-tokens  cache-read
                     :write-tokens cache-write}
     :cli-cost      (:ai.brainyard.clj-llm.core.claude-code/cli-cost response)}))

(defn extract-usage
  "Extract normalized usage info from a raw LLM response.
   message-format is :openai, :anthropic, :bedrock, or :claude-code.
   call-type is :chat-completion or :embedding.
   :bedrock responses are pre-reshaped into Anthropic-style usage keys
   by core.bedrock, so they share the Anthropic extractor."
  [response {:keys [message-format call-type]
             :or   {call-type :chat-completion}}]
  (case call-type
    :embedding (extract-embedding-usage response)
    (case message-format
      :claude-code (extract-claude-code-usage response)
      :anthropic   (extract-anthropic-usage response)
      :bedrock     (extract-anthropic-usage response)
      (extract-openai-usage response))))

;; ============================================================================
;; Cost Calculation
;; ============================================================================

(defn- tokens->cost
  "Calculate cost for a number of tokens at a per-1M rate."
  [tokens rate]
  (if (and tokens rate (pos? tokens) (pos? rate))
    (* (/ (double tokens) 1000000.0) (double rate))
    0.0))

(defn calculate-cost
  "Calculate cost breakdown for a usage map given provider and model.
   Returns {:input-cost :output-cost :cache-read-cost :cache-write-cost :total-cost}.

   :input-tokens is assumed to include both cache-read and cache-write
   sub-counts (uniform convention — see extractor docstrings). We derive
   the fresh-input portion by subtracting both and bill the three
   categories at their separate rates."
  [{:keys [input-tokens output-tokens cache] :as _usage} provider model]
  (if-let [rates (get-pricing provider model)]
    (let [cache-read    (or (:read-tokens cache) 0)
          cache-write   (or (:write-tokens cache) 0)
          non-cached-input (max 0 (- input-tokens cache-read cache-write))
          input-cost       (tokens->cost non-cached-input (:input rates))
          output-cost      (tokens->cost output-tokens (:output rates))
          cache-read-cost  (tokens->cost cache-read (:cache-read rates))
          cache-write-cost (tokens->cost cache-write (:cache-write rates))]
      {:input-cost       input-cost
       :output-cost      output-cost
       :cache-read-cost  cache-read-cost
       :cache-write-cost cache-write-cost
       :total-cost       (+ input-cost output-cost cache-read-cost cache-write-cost)})
    (do
      (mulog/debug ::no-pricing-found :provider provider :model model :message "Cost will be zero")
      {:input-cost 0.0 :output-cost 0.0 :cache-read-cost 0.0 :cache-write-cost 0.0 :total-cost 0.0})))

(defn build-usage-map
  "Build a complete usage map from a raw response and lm-config.
   Combines extraction + cost calculation + metadata."
  [response lm-config {:keys [latency-ms call-type]
                       :or   {call-type :chat-completion}}]
  (when-let [usage (extract-usage response {:message-format (:message-format lm-config)
                                            :call-type call-type})]
    (let [provider (:provider lm-config)
          model    (or (:model response) (:model lm-config))
          cost     (if-let [cli-cost (:cli-cost usage)]
                     ;; Claude CLI provides cost directly
                     {:input-cost 0.0 :output-cost 0.0
                      :cache-read-cost 0.0 :cache-write-cost 0.0
                      :total-cost (or cli-cost 0.0)}
                     (calculate-cost usage provider model))]
      (cond-> (assoc usage
                     :model model
                     :provider provider
                     :cost cost
                     :call-type call-type
                     :timestamp (java.util.Date.))
        latency-ms (assoc :latency-ms latency-ms)))))

;; ============================================================================
;; Usage Tracker
;; ============================================================================

(def ^:private default-history-cap 1000)

(defn create-usage-tracker
  "Create a new usage tracker atom.
   Options:
     :history-cap - Max number of call records to retain (default 1000)"
  [& {:keys [history-cap] :or {history-cap default-history-cap}}]
  (atom {:totals     {:input-tokens       0
                      :output-tokens      0
                      :total-tokens       0
                      :cache-read-tokens  0
                      :cache-write-tokens 0
                      :total-cost         0.0
                      :call-count         0}
         :by-model   {}
         :history    []
         :history-cap history-cap}))

(defn record-usage!
  "Record a usage map into a tracker. Returns the usage map unchanged."
  [tracker usage-map]
  (when (and tracker usage-map)
    (let [model (:model usage-map)
          cost  (get-in usage-map [:cost :total-cost] 0.0)
          cache-read  (get-in usage-map [:cache :read-tokens] 0)
          cache-write (get-in usage-map [:cache :write-tokens] 0)]
      (swap! tracker
             (fn [state]
               (-> state
                   ;; Update totals
                   (update-in [:totals :input-tokens] + (:input-tokens usage-map 0))
                   (update-in [:totals :output-tokens] + (:output-tokens usage-map 0))
                   (update-in [:totals :total-tokens] + (:total-tokens usage-map 0))
                   (update-in [:totals :cache-read-tokens] + cache-read)
                   (update-in [:totals :cache-write-tokens] + cache-write)
                   (update-in [:totals :total-cost] + cost)
                   (update-in [:totals :call-count] inc)
                   ;; Update per-model breakdown
                   (update-in [:by-model model :input-tokens] (fnil + 0) (:input-tokens usage-map 0))
                   (update-in [:by-model model :output-tokens] (fnil + 0) (:output-tokens usage-map 0))
                   (update-in [:by-model model :total-tokens] (fnil + 0) (:total-tokens usage-map 0))
                   (update-in [:by-model model :cache-read-tokens] (fnil + 0) cache-read)
                   (update-in [:by-model model :cache-write-tokens] (fnil + 0) cache-write)
                   (update-in [:by-model model :total-cost] (fnil + 0.0) cost)
                   (update-in [:by-model model :call-count] (fnil inc 0))
                   ;; Append to history (capped)
                   (update :history (fn [h]
                                      (let [h' (conj h usage-map)]
                                        (if (> (count h') (:history-cap state))
                                          (subvec h' (- (count h') (:history-cap state)))
                                          h')))))))))
  usage-map)

(defn get-usage-summary
  "Get cumulative usage summary from a tracker."
  [tracker]
  (when tracker
    (let [state @tracker]
      {:totals   (:totals state)
       :by-model (:by-model state)})))

(defn get-usage-history
  "Get call history from a tracker.
   Options:
     :model - Filter by model name
     :limit - Max records to return (most recent first)"
  [tracker & {:keys [model limit]}]
  (when tracker
    (let [history (:history @tracker)
          filtered (cond->> (rseq history)
                     model (filter #(= model (:model %)))
                     limit (take limit))]
      (vec filtered))))

(defn last-input-tokens-with-delta
  "Across one or more usage trackers, find the two most recent BT-iteration
   calls by `:timestamp` and report the last call's `:input-tokens` plus the
   signed delta vs. the previous call's `:input-tokens`.

   Only entries tagged with `:iteration` (patched by `dspy-action` after each
   BT iteration LLM call) participate. Sub-LLM calls — `query$llm`, judges,
   extraction helpers — share the same tracker but lack the tag; including
   them would pair a ~4k sub-call against the next ~40k main iteration and
   yield a meaningless delta.

   Returns `{:last-input-tokens N :input-tokens-delta M-or-nil}` where
   `:input-tokens-delta` is nil when fewer than two BT-iteration calls have
   been recorded. Returns nil when no BT-iteration calls have been recorded.

   Used by the TUI status bar to show how the last LLM prompt compares
   in size to the call before it — a cheap signal for context churn /
   compaction effectiveness."
  [trackers]
  (let [all (mapcat #(some-> % deref :history) trackers)
        bt-iter-desc (->> all (filter :iteration) (sort-by :timestamp) reverse)
        [last-call prev-call] (take 2 bt-iter-desc)]
    (when last-call
      {:last-input-tokens (long (:input-tokens last-call 0))
       :input-tokens-delta (when prev-call
                             (- (long (:input-tokens last-call 0))
                                (long (:input-tokens prev-call 0))))})))

(defn reset-tracker!
  "Reset a tracker to initial empty state, preserving the history cap."
  [tracker]
  (when tracker
    (swap! tracker (fn [state]
                     {:totals      {:input-tokens 0 :output-tokens 0 :total-tokens 0
                                    :cache-read-tokens 0 :cache-write-tokens 0
                                    :total-cost 0.0 :call-count 0}
                      :by-model    {}
                      :history     []
                      :history-cap (:history-cap state)}))))

(defn serialize-tracker
  "Return an EDN-safe snapshot of a tracker's state — suitable for
   pr-str / read-string round-trip. Tracker shape is already pure data
   (numbers, strings, keywords, dates), so this is just `@tracker`.
   Returns nil when tracker is nil."
  [tracker]
  (some-> tracker deref))

(defn hydrate-tracker!
  "Overwrite a tracker's state from a previously-serialized snap.
   Preserves the snap's :history-cap when present; otherwise keeps the
   tracker's existing cap. No-op when tracker or snap is nil."
  [tracker snap]
  (when (and tracker (map? snap))
    (swap! tracker (fn [state]
                     (assoc snap :history-cap (or (:history-cap snap)
                                                  (:history-cap state)
                                                  default-history-cap))))))

(defn merge-usage-summaries
  "Merge multiple usage summaries (as returned by get-usage-summary) into one.
   Sums :totals and merges :by-model breakdowns."
  [summaries]
  (let [summaries (remove nil? summaries)]
    (when (seq summaries)
      {:totals   (reduce (fn [acc s]
                           (merge-with + acc (:totals s)))
                         {:input-tokens 0 :output-tokens 0 :total-tokens 0
                          :cache-read-tokens 0 :cache-write-tokens 0
                          :total-cost 0.0 :call-count 0}
                         summaries)
       :by-model (reduce (fn [acc s]
                           (merge-with (fn [a b] (merge-with (fnil + 0) a b))
                                       acc (:by-model s)))
                         {}
                         summaries)})))

;; ============================================================================
;; Per-Category Token Estimation
;; ============================================================================

(defn estimate-tokens
  "Estimate token count for a string using chars/4 heuristic.
   Accuracy ~10-20% for English text, sufficient for prompt optimization."
  [text]
  (if (and text (string? text) (pos? (count text)))
    (long (Math/ceil (/ (count text) 4.0)))
    0))

(defn build-token-breakdown
  "Build per-category token breakdown from {category-kw text-or-nil}.
   Returns {category-kw {:text-length int :estimated-tokens int}}.
   Nil/empty texts are excluded. Categories are caller-defined keywords."
  [category-texts]
  (reduce-kv (fn [acc cat text]
               (if (and text (string? text) (pos? (count text)))
                 (assoc acc cat {:text-length (count text)
                                 :estimated-tokens (estimate-tokens text)})
                 acc))
             {} category-texts))

(defn build-token-group
  "Build a hierarchical token group from a parts breakdown.
   Returns {:text-length total :estimated-tokens total :parts breakdown-map}.
   The parts map is a flat breakdown from build-token-breakdown."
  [parts-breakdown]
  (let [total-len (reduce-kv (fn [t _ {:keys [text-length]}] (+ t (or text-length 0)))
                             0 parts-breakdown)
        total-tok (reduce-kv (fn [t _ {:keys [estimated-tokens]}] (+ t (or estimated-tokens 0)))
                             0 parts-breakdown)]
    {:text-length total-len
     :estimated-tokens total-tok
     :parts parts-breakdown}))

(defn aggregate-breakdowns
  "Aggregate multiple token breakdowns into cumulative totals.
   Returns {category-kw {:estimated-tokens total :call-count n}}."
  [breakdowns]
  (reduce (fn [acc bd]
            (reduce-kv (fn [a cat {:keys [estimated-tokens]}]
                         (-> a
                             (update-in [cat :estimated-tokens] (fnil + 0) estimated-tokens)
                             (update-in [cat :call-count] (fnil inc 0))))
                       acc bd))
          {} breakdowns))
