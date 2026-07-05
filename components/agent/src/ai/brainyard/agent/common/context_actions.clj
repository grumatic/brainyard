;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.common.context-actions
  "Shared per-turn BT actions that populate st-memory fields read by DSPy
   signatures across agents (ReAct / CoAct / CoAct).

   - prepare-conversation-action — snapshot recent session messages into
     st-memory :conversation. Drops the trailing user message when its
     content matches the current :question (avoids duplicating the
     prompt's question field inside the conversation history).

   - prepare-recalled-memory-action — run cross-layer recall over the
     current question, render the hits as a layer-grouped markdown
     block, and store the string in st-memory :recalled-memory.
     No-op when no memory manager or no question.

   - re-recall-after-tool-use — :agent.tool-use/post hook (M8a). When
     `:enable-mid-turn-recall` is on, extracts novel entity terms from
     each tool result, fires a refined recall, and merges new hits
     into :recalled-memory. Self-installs at namespace load."
  (:require [ai.brainyard.behavior-tree.interface :as bt]
            [ai.brainyard.agent.core.config :as config]
            [ai.brainyard.agent.core.hooks :as hooks]
            [ai.brainyard.agent.core.protocol :as proto]
            [ai.brainyard.agent.core.memory :as agent-mem]
            [ai.brainyard.memory.interface :as mem]
            [ai.brainyard.mulog.interface :as mulog]
            [clojure.string :as str])
  (:import (java.time.format DateTimeFormatter)
           (java.time ZoneId Instant)))

(def ^:private default-conversation-limit 20)
(def ^:private default-recall-limit 10)

(def ^:private recall-hit-fields
  "Fields kept on each cross-layer recall hit — only those informative
   to the LLM consuming :recalled-memory.

   Dropped vs. the `memory$recall` tool's combined-path projection:
     :id          — internal UUID, never referenced by the LLM.
     :_rrf_score  — internal ranking score, opaque to the LLM.
     :session-id  — internal session UUID, not actionable by the LLM."
  [:_layer :kind :role :tags :content :created-at])

(defn- project-hit [hit]
  (select-keys hit recall-hit-fields))

;; ============================================================================
;; Layer-grouped recall rendering (M6)
;; ============================================================================

(def ^:dynamic *snip-chars*
  "Per-hit char cap when rendering a recalled-memory hit into the prompt. This
   is the prompt-facing truncation (decoupled from the larger L2 storage caps in
   the capture parser). Bound from `:memory-recall-snippet-chars` by
   `prepare-recalled-memory-action`; the default applies to direct callers."
  600)

(defn- snip
  "Truncate content to `*snip-chars*` with an ellipsis. Surrogate-safe: never
   cuts between the halves of a UTF-16 surrogate pair (which would leave a
   dangling surrogate that renders/encodes as a replacement char)."
  [s]
  (let [s (str s)
        n *snip-chars*]
    (if (> (count s) n)
      (let [end (if (and (pos? n) (Character/isHighSurrogate (.charAt ^String s (dec n))))
                  (dec n)
                  n)]
        (str (subs s 0 end) "…"))
      s)))

(def ^:private date-only-fmt
  (-> (DateTimeFormatter/ofPattern "yyyy-MM-dd")
      (.withZone (ZoneId/systemDefault))))

(defn- format-date
  "Format an Instant (or anything Instant/from can read) as `YYYY-MM-DD`.
   Returns an empty string when input is nil/unrecognized."
  [t]
  (try
    (cond
      (instance? Instant t) (.format date-only-fmt ^Instant t)
      (instance? java.util.Date t) (.format date-only-fmt (.toInstant ^java.util.Date t))
      (number? t) (.format date-only-fmt (Instant/ofEpochMilli (long t)))
      (string? t) (.format date-only-fmt (Instant/parse t))
      :else "")
    (catch Exception _ "")))

(defn- render-l2-hit
  [{:keys [role content created-at]}]
  (let [d (format-date created-at)
        r (or role "unknown")
        pre (cond
              (and (seq d) (seq r)) (str "[" d " " r "] ")
              (seq d) (str "[" d "] ")
              (seq r) (str "[" r "] ")
              :else "")]
    (str "- " pre (snip content))))

(defn- render-l3-hit
  [{:keys [kind tags content]}]
  (let [kind-label (when kind
                     (let [s (name kind)]
                       (when (not= s "fact") (str "(" s ") "))))
        tag-label (when (seq tags)
                    (let [tag-str (str/join ", " (map str tags))]
                      (when-not (str/blank? tag-str)
                        (str "[" tag-str "] "))))]
    (str "- " kind-label tag-label (snip content))))

(defn- render-l1-hit
  [{:keys [kind content]}]
  (let [k (or kind :overlay)]
    (str "- (" (name k) " overlay) " (snip content))))

(defn format-recalled-memory
  "Render a vector of cross-layer recall hits as a layer-grouped
   markdown block. Returns an empty string when there are no hits.

   Sections (in display order):
     ### Episodes (L2 — recent activity)
     ### Facts (L3 — long-term)
     ### System overlays (L1)

   A hit is a projected map with keys
   `:_layer :kind :role :tags :content :created-at` (see
   `recall-hit-fields`). Hits without a recognised `:_layer` fall under
   a trailing ### Other section so they're not silently dropped."
  [hits]
  (if (empty? hits)
    ""
    (let [grouped (group-by :_layer hits)
          l2 (get grouped :l2)
          l3 (get grouped :l3)
          l1 (get grouped :l1)
          other (->> hits
                     (remove #(contains? #{:l1 :l2 :l3} (:_layer %))))
          parts (cond-> ["## Recalled Memory"]
                  (seq l2)
                  (conj "\n### Episodes (L2 — recent activity)"
                        (str/join "\n" (map render-l2-hit l2)))

                  (seq l3)
                  (conj "\n### Facts (L3 — long-term)"
                        (str/join "\n" (map render-l3-hit l3)))

                  (seq l1)
                  (conj "\n### System overlays (L1)"
                        (str/join "\n" (map render-l1-hit l1)))

                  (seq other)
                  (conj "\n### Other"
                        (str/join "\n" (map (fn [h]
                                              (str "- " (snip (:content h))))
                                            other))))]
      (str/join "\n" parts))))

(defn- drop-question-tail
  "If the last message is a user message whose content equals `question`
   (after trimming), drop it. Avoids duplicating the question both as
   the dedicated :question field and as the latest entry in
   :conversation."
  [msgs question]
  (let [q (some-> question str/trim)]
    (if (and (seq msgs)
             (not (str/blank? q))
             (let [{:keys [role content]} (peek msgs)]
               (and (= role "user")
                    (string? content)
                    (= q (str/trim content)))))
      (pop msgs)
      msgs)))

(defn- completed-turn-units
  "Identify this agent's completed turn units inside a message window.

   A unit is a user question plus THIS agent's turn-final answer for it
   (the same Q/A the previous-turns chain records). Segments are bounded
   by user messages; within a segment the answer is the message tagged
   `:kind :turn-answer` with `self-id`. Messages from sessions persisted
   before the tag existed use the structural property that sub-agent
   dispatch prompts (also self-tagged assistants) always precede the
   turn answer, so the LAST untagged self assistant in a segment wins.

   Returns [{:q-idx int-or-nil :a-idx int} ...] in window order; :q-idx
   is nil when the window opens mid-segment (question cut off)."
  [msgs self-id]
  (let [n (count msgs)
        answer? (fn [{:keys [role agent-id kind]}]
                  (and (= "assistant" role)
                       (= agent-id self-id)
                       (or (= :turn-answer kind) (nil? kind))))
        seg-starts (into [0] (keep-indexed
                              (fn [i m] (when (and (pos? i) (= "user" (:role m))) i))
                              msgs))]
    (if (zero? n)
      []                                ; empty window: no units, and (nth msgs 0) would throw
      (into []
            (keep (fn [[start next-start]]
                    (let [q-idx (when (= "user" (:role (nth msgs start))) start)
                          a-idx (->> (range start (or next-start n))
                                     (filter #(answer? (nth msgs %)))
                                     last)]
                      (when a-idx {:q-idx q-idx :a-idx a-idx}))))
            (map vector seg-starts (concat (rest seg-starts) [nil]))))))

(defn- merge-adjacent-turn-refs
  "Collapse consecutive turn-ref entries into range refs
   ({:turn 3 :to 5} renders as [Turns 3–5])."
  [msgs]
  (reduce (fn [acc m]
            (let [prev (peek acc)]
              (if (and (= "turn-ref" (:role m))
                       (= "turn-ref" (:role prev))
                       (= (:turn m) (inc (or (:to prev) (:turn prev)))))
                (conj (pop acc) (assoc prev :to (:turn m)))
                (conj acc m))))
          [] msgs))

(defn- timeline-transform
  "Collapse completed own-turn Q/A pairs (older than the `keep-verbatim`
   most recent) into `{:role \"turn-ref\" :turn N}` entries pointing at
   the previous-turns chain — the Q/A text already lives there (the
   cached, append-only `[Turn N]` entries), so the volatile window keeps
   only the session timeline: refs, sub-agent messages, system notes,
   and the last few verbatim exchanges as a recency anchor.

   Turn numbers tail-align with the chain: the last completed unit in
   the window is the chain's last entry (`chain-count`). When the chain
   is shorter than the window's unit count (deep compaction dropped
   entries the window still shows) the window is returned untouched —
   refs would point at renumbered or missing entries."
  [msgs self-id chain-count keep-verbatim]
  (let [units (completed-turn-units msgs self-id)
        m (count units)
        convert-n (- m (max 0 (long (or keep-verbatim 0))))
        base (- (long chain-count) m)]
    (if (or (<= convert-n 0) (neg? base))
      msgs
      (let [converted (take convert-n units)
            drop-idxs (into #{} (mapcat (fn [{:keys [q-idx a-idx]}]
                                          (remove nil? [q-idx a-idx])))
                            converted)
            ref-at (into {} (map-indexed
                             (fn [j {:keys [q-idx a-idx]}]
                               [(or q-idx a-idx) (+ base j 1)])
                             converted))]
        (->> msgs
             (map-indexed (fn [i msg]
                            (cond
                              (ref-at i)    {:role "turn-ref" :turn (ref-at i)}
                              (drop-idxs i) nil
                              :else         msg)))
             (remove nil?)
             merge-adjacent-turn-refs
             vec)))))

(defn prepare-conversation-action
  "BT action: snapshot recent session messages into st-memory :conversation.

   Reads the most recent N messages from the agent's !session (default
   20; override via config key :conversation-limit). Drops the
   trailing user message when its content equals the current :question
   to avoid duplicating it.

   With :conversation-style \"timeline\" (default), completed own-turn
   Q/A pairs older than the :conversation-keep-verbatim most recent
   collapse to [Turn N] references into the Previous Turns section (see
   timeline-transform) — de-duplicating the Q/A both sections used to
   carry. \"full\" keeps the legacy verbatim window.

   Always returns bt/success."
  [{:keys [st-memory agent]}]
  (let [messages (some-> agent :!session deref :messages vec)
        limit    (config/get-config agent :conversation-limit)
        question (:question @st-memory)
        window   (-> (vec (take-last limit (or messages [])))
                     (drop-question-tail question))
        style    (str (or (config/get-config agent :conversation-style) "timeline"))
        window   (if (and agent (= "timeline" style))
                   (timeline-transform
                    window
                    (:agent-id agent)
                    (count (:previous-turns @st-memory))
                    (config/get-config agent :conversation-keep-verbatim))
                   window)]
    (swap! st-memory assoc :conversation window)
    bt/success))

(defn prepare-recalled-memory-action
  "BT action: cross-layer recall over the current :question. Stores the
   layer-grouped markdown rendering at st-memory :recalled-memory and
   the raw projected vector at :recalled-memory-hits (for observability
   and skill callers that need structured access).

   When recall happens with a turn-id, the audit row is qualified by
   `:agent-id` (per-agent turn-id is unique only within an agent) and
   `:total-turns` (session-cumulative counter, for cross-agent ordering).

   No-op when the agent has no memory manager or :question is blank.
   Recall errors are logged and the field is left as \"\". Always
   returns bt/success."
  [{:keys [st-memory agent]}]
  (let [mm          (some-> agent proto/get-memory-manager)
        question    (:question @st-memory)
        sid         (some-> agent proto/session-id)
        aid         (some-> agent proto/agent-id)
        turn-id     (:turn-id @st-memory)
        total-turns (:total-turns @st-memory)
        limit       (config/get-config agent :recall-limit)
        hits        (when (and mm (string? question) (not (str/blank? question)))
                      (try
                        (agent-mem/recall mm
                                          :query question
                                          :session-id sid
                                          :agent-id aid
                                          :turn-id turn-id
                                          :total-turns total-turns
                                          :limit limit)
                        (catch Exception e
                          (mulog/warn ::recall-failed
                                      :message (ex-message e))
                          nil)))
        projected (mapv project-hit (or hits []))
        rendered  (binding [*snip-chars* (or (config/get-config agent :memory-recall-snippet-chars)
                                             *snip-chars*)]
                    (format-recalled-memory projected))]
    (swap! st-memory assoc
           :recalled-memory      rendered
           :recalled-memory-hits projected)
    bt/success))

;; ============================================================================
;; Mid-turn re-recall (M8a) — :agent.tool-use/post hook
;; ============================================================================

(def ^:private default-mid-turn-recall-limit 5)
(def ^:private default-min-novel-result-chars 50)

(defn- extract-entity-terms
  "Extract candidate entity-like terms from a tool-result. Heuristic:
   matches CamelCase/TitleCase words (≥4 chars) and backtick-quoted
   identifier strings (including `$`, `:`, and other id punctuation).

   `result` can be a string or any value (will be pr-str'd first).
   Returns a vector of distinct terms in first-seen order."
  [result]
  (let [text (cond
               (nil? result) ""
               (string? result) result
               :else (pr-str result))
        camel (re-seq #"\b[A-Z][A-Za-z0-9_-]{3,}\b" text)
        ticked (->> (re-seq #"`([A-Za-z0-9._/\-$:]{4,})`" text)
                    (map second))]
    (vec (distinct (concat camel ticked)))))

(defn- novel-terms
  "Subset of `terms` not already present in `recalled-text`. Case-sensitive
   substring match — keeps the test cheap and conservative."
  [terms recalled-text]
  (let [recalled (or recalled-text "")]
    (filterv (fn [t]
               (and (string? t)
                    (not (str/blank? t))
                    (not (str/includes? recalled t))))
             terms)))

(defn- merge-hits
  "Combine two recall-hit vectors, deduping by `:content` and keeping
   `existing` first. Returns a vector. Caps total length at `:limit`."
  [existing new-hits limit]
  (let [seen (set (keep :content existing))
        adds (->> new-hits
                  (remove #(contains? seen (:content %))))]
    (vec (take (or limit 20) (concat existing adds)))))

(defn re-recall-after-tool-use
  "Hook handler for :agent.tool-use/post. When the calling agent has
   `:enable-mid-turn-recall` set on its config and the tool result
   mentions entity-like terms not already in :recalled-memory, fires a
   refined recall, merges new hits, and updates st-memory.

   No-op when the flag is off, when the agent has no memory manager,
   or when the result is too small / too generic to be worth a recall.
   Errors during recall are logged and swallowed so a flaky FTS5 query
   never blocks the BT."
  [{:keys [agent result tool-name]}]
  (try
    (when agent
      (let [st-atom (some-> agent :!state deref
                            (get-in [:behavior-tree :context :st-memory]))
            st (some-> st-atom deref)
            enabled? (config/get-config agent :enable-mid-turn-recall)
            mm (some-> agent proto/get-memory-manager)
            result-text (cond
                          (nil? result) nil
                          (string? result) result
                          :else (pr-str result))]
        (when (and enabled? mm st-atom
                   result-text
                   (> (count result-text) default-min-novel-result-chars))
          (let [terms (extract-entity-terms result-text)
                novel (novel-terms terms (:recalled-memory st))]
            (when (seq novel)
              (let [query (str/join " " (take 5 novel))
                    hits (try
                           (agent-mem/recall mm
                                             :query query
                                             :session-id (proto/session-id agent)
                                             :agent-id (proto/agent-id agent)
                                             :turn-id (:turn-id st)
                                             :total-turns (:total-turns st)
                                             :limit default-mid-turn-recall-limit)
                           (catch Exception e
                             (mulog/warn ::mid-turn-recall-failed
                                         :tool-name tool-name
                                         :message (ex-message e))
                             nil))
                    projected (mapv project-hit (or hits []))
                    existing (or (:recalled-memory-hits st) [])
                    merged (merge-hits existing projected 20)
                    new? (> (count merged) (count existing))]
                (when new?
                  (swap! st-atom assoc
                         :recalled-memory-hits merged
                         :recalled-memory
                         (binding [*snip-chars* (or (config/get-config agent :memory-recall-snippet-chars)
                                                    *snip-chars*)]
                           (format-recalled-memory merged))
                         :mid-turn-recall-fired
                         (inc (or (:mid-turn-recall-fired st) 0)))
                  (mulog/log ::mid-turn-recall-fired
                             :tool-name tool-name
                             :novel-count (count novel)
                             :new-hits (- (count merged) (count existing))))))))))
    (catch Exception e
      (mulog/warn ::mid-turn-recall-hook-error
                  :tool-name tool-name :message (ex-message e))))
  nil)

(defn install-mid-turn-recall!
  "Register the M8a re-recall hook on :agent.tool-use/post. Idempotent —
   register-hook! replaces by id, so re-loading this ns is safe.

   The hook is always registered; the handler short-circuits when
   :enable-mid-turn-recall is off, which is the default. Operators can
   tear it down per-source via
   `(hooks/unregister-source! :context-actions)`."
  []
  (hooks/register-hook!
   :agent.tool-use/post
   ::re-recall-trigger
   re-recall-after-tool-use
   :source :context-actions
   :priority 30))

;; Self-install at namespace load so any agent that imports
;; context-actions (which is most of them) gets the hook for free.
(install-mid-turn-recall!)

;; ============================================================================
;; Cross-turn tool-result cache (M8b) — :agent.tool-use/pre hook
;; ============================================================================

(def ^:private tool-cache-allow-decision
  "Reusable :allow decision — single instance avoids per-call allocation."
  {:result :allow})

(defn- normalize-tool-name
  "Match the parser's tool-name tagging — keyword → `name`, otherwise `str`."
  [tool-name]
  (cond
    (keyword? tool-name) (name tool-name)
    :else                (str tool-name)))

(defn- args-match?
  "Conservative equality check: hit-entry's :data :args must equal the
   current call's args after normalisation. Both sides are pre-coerced
   maps (or empty maps) by the time this fires, so a straight = works."
  [hit-args call-args]
  (= (or hit-args {}) (or call-args {})))

(defn tool-cache-lookup-pre
  "Hook handler for :agent.tool-use/pre. When the agent has
   `:tool-cache-ttl` > 0 on its config and a prior L2 episodic
   entry (kind:tool-result + tool:<name>) within the TTL window has
   matching args, returns a :replace decision so the dispatcher
   substitutes the cached result and skips the real tool call.

   Default off (TTL=0 = no cache). The remember side runs via the
   memory capture pipeline (parser's :agent.tool-use/post handler
   already writes the entry); only the lookup is new.

   Safety: this handler caches EVERY tool indiscriminately when TTL
   is positive. Operators flipping it on accept that destructive
   tools may be skipped — restrict via the registered tools' own
   :tool-use-control or by setting TTL only inside known-read
   sessions. A future iteration may add a per-tool allow-list."
  [{:keys [agent tool-name args]}]
  (try
    (when agent
      (let [st-atom (some-> agent :!state deref
                            (get-in [:behavior-tree :context :st-memory]))
            st (some-> st-atom deref)
            ttl-s (config/get-config agent :tool-cache-ttl)
            mm (some-> agent proto/get-memory-manager)]
        (when (and (pos? (or ttl-s 0)) mm)
          (let [tname (normalize-tool-name tool-name)
                cutoff-ms (- (System/currentTimeMillis) (* 1000 ttl-s))
                hits (try
                       (mem/read-entries
                        mm :l2
                        {:kind :episode
                         :tags #{"kind:tool-result" (str "tool:" tname)}
                         :time-after cutoff-ms}
                        {:limit 5})
                       (catch Exception e
                         (mulog/warn ::tool-cache-lookup-failed
                                     :tool-name tname
                                     :message (ex-message e))
                         nil))
                hit (some (fn [e]
                            (when (args-match? (get-in e [:data :args]) args)
                              e))
                          hits)
                cached (some-> hit :data :result)]
            (when (some? cached)
              (mulog/log ::tool-cache-hit
                         :tool-name tname
                         :ttl-s ttl-s
                         :age-ms (when-let [t (:created-at hit)]
                                   (- (System/currentTimeMillis)
                                      (cond
                                        (instance? Instant t) (.toEpochMilli ^Instant t)
                                        (number? t) (long t)
                                        :else 0))))
              {:result :replace
               :replacement cached
               :reason (str "tool-cache hit for " tname
                            " (ttl=" ttl-s "s)")})))))
    (catch Exception e
      (mulog/warn ::tool-cache-hook-error
                  :tool-name tool-name :message (ex-message e))
      nil)))

(defn install-tool-cache-lookup!
  "Register the M8b cache-lookup hook on :agent.tool-use/pre.
   Idempotent. The hook returns :allow (no-op) whenever
   :tool-cache-ttl is 0 (the default) so the cost of keeping it
   installed is one st-memory read per tool call."
  []
  (hooks/register-hook!
   :agent.tool-use/pre
   ::tool-cache-lookup
   tool-cache-lookup-pre
   :source :context-actions
   ;; Low priority so explicit per-tool :replace handlers (e.g. the
   ;; loop guard) see the call first and can short-circuit cache.
   :priority 90))

;; Self-install at namespace load. Default config (ttl=0) keeps the
;; handler a no-op until an operator opts in.
(install-tool-cache-lookup!)
