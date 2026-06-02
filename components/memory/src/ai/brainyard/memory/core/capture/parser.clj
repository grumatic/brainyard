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
  [s n]
  (if (and (string? s) (> (count s) n))
    (str (subs s 0 n) "…")
    s))

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
  [{:keys [event-key] :as event}]
  ;; Unknown events are still recorded but tagged so we can audit.
  {:kind    :episode
   :content (truncate (safe-content event) 4000)
   :tags    #{(->tag :event (or event-key :unknown))
              "kind:unknown"}})

(defmethod event->partial-entry :agent.ask/pre
  [{:keys [input]}]
  (let [text (safe-content input)]
    {:kind    :episode
     :content text
     :tags    (into #{"role:user" "event:ask-pre" "kind:user-message"}
                    (keyword-tags text))}))

(defmethod event->partial-entry :agent.ask/post
  [{:keys [input result]}]
  (let [out (safe-content
             (cond
               (string? result) result
               (map? result)    (or (:answer result)
                                    (:result result)
                                    (:output result)
                                    result)
               :else            result))
        text (truncate out 4000)]
    {:kind    :episode
     :content text
     :tags    (into #{"role:assistant" "event:ask-post" "kind:assistant-answer"}
                    (concat (keyword-tags text)
                            (keyword-tags (safe-content input))))}))

(defmethod event->partial-entry :agent.tool-use/post
  [{:keys [tool-name args result]}]
  (let [tool-str (cond
                   (keyword? tool-name) (name tool-name)
                   :else                (str tool-name))
        body     (truncate
                  (str "tool=" tool-str
                       (when (seq args) (str " args=" (truncate (safe-content args) 400)))
                       (when result    (str " => "    (truncate (safe-content result) 1000))))
                  3000)]
    {:kind    :episode
     :content body
     :data    {:tool-name tool-str
               :args      args
               :result    result}
     :tags    (cond-> #{"event:tool-post" "kind:tool-result"
                        (->tag :tool tool-str)}
                (and (map? result) (:error result))
                (conj "outcome:error"))}))

(defmethod event->partial-entry :agent.code-eval/post
  [{:keys [code result output error duration-ms]}]
  (let [summary (or (when (and (string? error) (not (clojure.string/blank? error))) error)
                    result
                    output)
        body (truncate
              (str "(eval ...) "
                   (when duration-ms (str "[" duration-ms "ms] "))
                   "=> "
                   (truncate (safe-content summary) 1500))
              3000)]
    {:kind    :episode
     :content body
     :data    {:code        (truncate (safe-content code) 4000)
               :result      result
               :output      output
               :error       error
               :duration-ms duration-ms}
     :tags    (cond-> #{"event:code-eval" "kind:code-eval"}
                (and (string? error) (not (clojure.string/blank? error)))
                (conj "outcome:error"))}))

(defmethod event->partial-entry :agent/exception
  [{:keys [phase exception]}]
  (let [msg       (cond
                    (instance? Throwable exception) (.getMessage ^Throwable exception)
                    :else                            (safe-content exception))
        phase-str (cond
                    (nil? phase)     "?"
                    (keyword? phase) (name phase)
                    :else            (str phase))]
    {:kind    :episode
     :content (truncate (str "exception in " phase-str ": " msg) 2000)
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
  hook catalog. Returns a complete entry map."
  [{:keys [event-key session-id user-id event-id captured-at]
    :or   {captured-at (System/currentTimeMillis)}
    :as   event}]
  (let [partial (event->partial-entry event)]
    (assoc partial
           :session-id session-id
           :user-id    user-id
           :created-at captured-at
           :sources    [{:type event-key :id event-id}])))
