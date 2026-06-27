;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.memory.core.capture.parser
  "S1 — event-to-entry parser for the memory capture pipeline.

  Pure functions only. Takes a normalized capture event (see
  `dispatcher.clj`) and produces a memory entry suitable for
  `IMemoryStore/write-entry` at layer :l2 (`:kind :episode`).

  Each emitted entry carries:
    :sources  — `[{:type <event-key> :id <stable-event-id>}]` provenance
    :tags     — set of `\"k:v\"` strings (e.g. `\"tool:bash\"`,
                `\"topic:deploy\"`, `\"role:user\"`) for cheap filtering
                without parsing the content blob

  No I/O. Tests live in `parser_test.clj`."
  (:require [clojure.string :as str]
            [ai.brainyard.memory.core.fts :as fts]))

;; =====================================================
;; Helpers
;; =====================================================

(defn- safe-content
  [v]
  (cond
    (nil? v)     ""
    (string? v)  v
    :else        (try (pr-str v) (catch Exception _ ""))))

(defn- truncate
  "Cut `s` to at most `n` chars and append an ellipsis. Surrogate-safe: if the
  cut would fall between the two halves of a UTF-16 surrogate pair (e.g. an
  emoji or astral-plane char straddling the boundary), back off by one char so
  the kept string never ends in a dangling high surrogate — a lone surrogate is
  not a valid scalar and gets mangled to `?`/U+FFFD when JDBC encodes it to
  UTF-8 for SQLite (and can confuse FTS tokenization)."
  [s n]
  (if (and (string? s) (> (count s) n))
    (let [end (if (and (pos? n)
                       (Character/isHighSurrogate (.charAt ^String s (dec n))))
                (dec n)
                n)]
      (str (subs s 0 end) "…"))
    s))

(def default-limits
  "Per-event-kind truncation caps (chars) for L2 episode content. These are the
  STORAGE caps — what a captured episode persists, hence what FTS can match.
  They are deliberately generous: the recall path re-snips each hit to a small
  prompt-facing window (see agent context_actions/`*snip-chars*`), so relaxing
  these costs only DB/FTS bytes, not prompt tokens. The conversation Q&A caps
  (`:question`/`:answer`) are overridden from config by the agent at
  `start-capture!` (`:memory-question-max-chars` / `:memory-answer-max-chars`);
  the rest are fixed. A query term in a long answer's tail now actually
  matches instead of being silently dropped."
  {:question 8000 :answer 16000 :event 8000
   :tool-args 1000 :tool-error 2000
   :eval-code 4000 :eval-error 2000
   :exception 4000})

(defn- keyword-tags
  "Pull a few topic tags from text via fts/extract-keywords. Bounded to
  avoid exploding the tag set on long content. Coerces non-string input
  via `safe-content` so the sidecar can never explode on an LLM result
  shape that happens to be a keyword/map (e.g. `{:result :failure}`)."
  [text]
  (let [s (if (string? text) text (safe-content text))]
    (->> (fts/extract-keywords s :max-keywords 5)
         (map #(str "topic:" %))
         (set))))

(defn- ->tag
  [k v]
  (str (cond
         (keyword? k) (name k)
         :else        (str k))
       ":"
       (cond
         (keyword? v) (name v)
         :else        (str v))))

(defn- qa-entry-id
  "Content-addressable id for a conversation Q&A episode, keyed on the
  (session, normalized-question) so re-asking the same thing in a session
  upserts the episode instead of duplicating it."
  [session-id question]
  (let [norm (-> (safe-content question)
                 str/lower-case
                 str/trim
                 (str/replace #"\s+" " "))]
    (str "qa/" (or session-id "?") "/" (format "%08x" (hash norm)))))

;; =====================================================
;; Per-event-kind translators
;;
;; All translators receive the raw event map (with at minimum :event-key,
;; :captured-at, :session-id, :user-id, :event-id, plus the per-event keys
;; from hooks/event-catalog) and return a partial entry map. The
;; dispatcher fills in the cross-event fields (:layer, :session-id,
;; :user-id, :created-at, :id).
;; =====================================================

(defmulti event->partial-entry
  "Dispatch on :event-key. Returns a partial entry map; the caller
  merges in the standard fields."
  :event-key)

(defmethod event->partial-entry :default
  [{:keys [event-key] :as event} limits]
  ;; Unknown events are still recorded but tagged so we can audit.
  {:kind    :episode
   :content (truncate (safe-content event) (:event limits))
   :tags    #{(->tag :event (or event-key :unknown))
              "kind:unknown"}})

;; `:agent.ask/pre` is intentionally not handled — the user's question is no
;; longer captured on its own (it self-recalled and doubled episode count);
;; it is folded into the Q&A episode below at turn end (see dispatcher).

(defmethod event->partial-entry :agent.ask/post
  [{:keys [input result session-id]} limits]
  (let [q   (truncate (safe-content input) (:question limits))
        out (safe-content
             (cond
               (string? result) result
               (map? result)    (or (:answer result)
                                    (:result result)
                                    (:output result)
                                    result)
               :else            result))
        a   (truncate out (:answer limits))]
    ;; One coherent conversational episode per turn: question + answer. The
    ;; content-addressable :id upserts a repeated question (see
    ;; episodic/append-episode!). Because this fires at turn END — after the
    ;; turn's own recall — the current question can never self-recall.
    {:kind    :episode
     :id      (qa-entry-id session-id input)
     :content (str "Q: " q "\nA: " a)
     :tags    (into #{"role:conversation" "event:qa" "kind:qa"}
                    (concat (keyword-tags q) (keyword-tags a)))}))

(defmethod event->partial-entry :agent.tool-use/post
  [{:keys [tool-name args result]} limits]
  ;; Errors only. A successful tool call is operational noise for *recall* —
  ;; the full trace already lives in the trajectory log + memory_audit.
  ;; Failures are worth remembering ("last time this errored with …"), so we
  ;; capture only those; nil ⇒ the sidecar skips the write.
  (when (and (map? result) (:error result))
    (let [tool-str (cond
                     (keyword? tool-name) (name tool-name)
                     :else                (str tool-name))
          body     (truncate
                    (str "tool=" tool-str " FAILED"
                         (when (seq args) (str " args=" (truncate (safe-content args) (:tool-args limits))))
                         (str " => " (truncate (safe-content (:error result)) (:tool-error limits))))
                    (:event limits))]
      {:kind    :episode
       :content body
       :data    {:tool-name tool-str :args args :result result}
       :tags    #{"event:tool-post" "kind:tool-error" "outcome:error"
                  (->tag :tool tool-str)}})))

(defmethod event->partial-entry :agent.code-eval/post
  [{:keys [code result output error duration-ms]} limits]
  ;; Errors only (see :agent.tool-use/post). A successful eval is in the
  ;; trajectory; only failures are recall-worthy. nil ⇒ sidecar skips.
  (when (and (string? error) (not (str/blank? error)))
    (let [body (truncate
                (str "(eval ...) FAILED "
                     (when duration-ms (str "[" duration-ms "ms] "))
                     "=> " (truncate error (:eval-error limits)))
                (:event limits))]
      {:kind    :episode
       :content body
       :data    {:code        (truncate (safe-content code) (:eval-code limits))
                 :result      result
                 :output      output
                 :error       error
                 :duration-ms duration-ms}
       :tags    #{"event:code-eval" "kind:code-eval-error" "outcome:error"}})))

(defmethod event->partial-entry :agent/exception
  [{:keys [phase exception]} limits]
  (let [msg       (cond
                    (instance? Throwable exception) (.getMessage ^Throwable exception)
                    :else                            (safe-content exception))
        phase-str (cond
                    (nil? phase)     "?"
                    (keyword? phase) (name phase)
                    :else            (str phase))]
    {:kind    :episode
     :content (truncate (str "exception in " phase-str ": " msg) (:exception limits))
     :data    {:phase phase
               :class (when (instance? Throwable exception)
                        (.getName (class exception)))}
     :tags    (cond-> #{"event:exception" "kind:exception" "outcome:error"}
                phase (conj (->tag :phase phase)))}))

;; =====================================================
;; Public API
;; =====================================================

(defn parse
  "Translate a capture event into a memory entry suitable for
  `IMemoryStore/write-entry` at layer :l2.

  Required keys on `event`:
    :event-key   — namespaced keyword (matches a hooks/event-catalog entry)
    :session-id  — string
    :user-id     — string
    :event-id    — stable id (string/uuid) — used in :sources for provenance

  Optional:
    :captured-at — millis epoch (defaults to now)

  Plus the per-event keys (:input, :result, :tool-name, etc.) from the
  hook catalog. Returns a complete entry map.

  The 2-arity takes a `limits` map (merged over `default-limits`) to override
  the storage truncation caps — the sidecar threads the agent's configured
  `:question`/`:answer` caps here. The 1-arity uses `default-limits`."
  ([event] (parse event nil))
  ([{:keys [event-key session-id user-id event-id captured-at]
     :or   {captured-at (System/currentTimeMillis)}
     :as   event}
    limits]
   ;; A translator returns nil to skip the event (e.g. a successful tool/eval —
   ;; errors only). The sidecar must treat a nil parse as "no episode".
   (when-let [partial (event->partial-entry event (merge default-limits limits))]
     (assoc partial
            :session-id session-id
            :user-id    user-id
            :created-at captured-at
            :sources    [{:type event-key :id event-id}]))))
