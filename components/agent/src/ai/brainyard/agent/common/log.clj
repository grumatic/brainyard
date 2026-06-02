;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.common.log
  "Agent log querying — reads structured mulog events from the app log file.
   Provides log$ commands for LLMs to search and inspect events by
   agent-id, session-id, turn-id, event type, keyword, etc."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [ai.brainyard.agent.core.protocol :as proto]
            [ai.brainyard.agent.core.tool :refer [defcommand]]))

;; ============================================================================
;; State
;; ============================================================================

(defonce !app-log-path (atom nil))

(defn set-app-log-path!
  "Set the path to the app log file for log queries."
  [path]
  (reset! !app-log-path path))

;; ============================================================================
;; Event Classification
;; ============================================================================

(def ^:private event-categories
  "Map event-name suffixes to human-readable categories."
  {"coact-init"          :turn-start
   "store-results"       :turn-complete
   "task-execution"      :task
   "agent-conversation"  :conversation})

(defn- event-category [event]
  (let [ename (name (or (:mulog/event-name event) :unknown))]
    (or (some (fn [[suffix cat]]
                (when (str/ends-with? ename suffix) cat))
              event-categories)
        (keyword ename))))

;; ============================================================================
;; Summarize
;; ============================================================================

(defn- summarize-event
  "Create a compact summary of a raw event."
  [event]
  (let [cat (event-category event)
        base {:category cat
              :timestamp (:mulog/timestamp event)
              :agent-id (:agent-id event)
              :turn-id (:turn-id event)}]
    (case cat
      :turn-start
      (merge base
             {:total-turns (:total-turns event)
              :previous-turns (:previous-turns-count event)
              :system-chars (:system-context-chars event)
              :user-chars (:user-context-chars event)
              :budget-tokens (:budget-tokens event)})

      :turn-complete
      (merge base
             {:total-iterations (:total-iterations event)
              :terminated-by (:terminated-by event)
              :answer-length (:answer-length event)})

      :task
      (merge base
             {:task-id (:task-id event)
              :task-name (:task-name event)
              :status (:status event)
              :duration-ms (:duration-ms event)})

      :conversation
      (merge base
             {:request (let [r (str (:request event))]
                         (subs r 0 (min 120 (count r))))
              :reply-length (count (str (:reply event)))})

      ;; Default: keep all non-mulog keys
      (merge base
             {:event-name (name (or (:mulog/event-name event) :unknown))
              :keys (vec (remove #(str/starts-with? (name %) "mulog/")
                                 (keys event)))}))))

;; ============================================================================
;; App Log Parsing
;; ============================================================================

(defn- parse-app-log
  "Parse the pretty-printed app log file into a seq of event maps."
  [path]
  (try
    (let [content (slurp path)
          blocks (remove str/blank? (str/split content #"\n\n"))]
      (keep (fn [block]
              (try
                (edn/read-string {:readers {'mulog/flake identity}} block)
                (catch Exception _ nil)))
            blocks))
    (catch java.io.FileNotFoundException _ [])
    (catch Exception _ [])))

(defn- matches-filter?
  "Check if event matches identity filters. nil filter = match all."
  [event {:keys [user-id session-id agent-id turn-id]}]
  (and (or (nil? user-id) (= user-id (:user-id event)))
       (or (nil? session-id) (= session-id (:session-id event)))
       (or (nil? agent-id) (= agent-id (:agent-id event)))
       (or (nil? turn-id) (= turn-id (:turn-id event)))))

(defn- event-matches-text?
  "Check if any event value contains the query string (case-insensitive).
   Searches strings, keywords, and symbols."
  [event query]
  (let [q (str/lower-case query)]
    (some (fn [[_k v]]
            (let [s (cond
                      (string? v) v
                      (keyword? v) (str (symbol v))
                      (symbol? v) (str v)
                      :else nil)]
              (when s
                (str/includes? (str/lower-case s) q))))
          event)))

(defn- current-session-filters
  "Extract session-level filters from the current agent binding.
   Scopes by session-id only — includes subagent events."
  []
  (when-let [agent proto/*current-agent*]
    {:user-id (proto/user-id agent)
     :session-id (proto/session-id agent)}))

;; ============================================================================
;; Public Query API
;; ============================================================================

(defn query-events
  "Query events from the app log with flexible filters.

   Options:
     :user-id, :session-id, :agent-id, :turn-id — identity filters
     :type     — event category filter (e.g. :turn-start, :task, :conversation)
     :query    — text search across all string values
     :last-n   — return only the last N matching events
     :summary  — if true, return compact summaries (default true)"
  [& {:keys [user-id session-id agent-id turn-id type query last-n summary]
      :or {summary true}}]
  (if-let [path @!app-log-path]
    (let [all-events (parse-app-log path)
          filtered (->> all-events
                        (filter #(matches-filter? % {:user-id user-id
                                                     :session-id session-id
                                                     :agent-id agent-id
                                                     :turn-id turn-id}))
                        (filter #(or (nil? type)
                                     (= (keyword type) (event-category %))))
                        (filter #(or (nil? query)
                                     (event-matches-text? % query)))
                        vec)
          trimmed (if last-n
                    (vec (take-last last-n filtered))
                    filtered)]
      (if summary
        (mapv summarize-event trimmed)
        trimmed))
    {:error "Log not available — app log path not configured"}))

(defn list-turns
  "List turns with metadata. Scoped to identity filters.

   Returns: [{:turn-id N :event-count N :first-ts N :last-ts N :categories [...]}]"
  [& {:keys [user-id session-id agent-id]}]
  (if-let [path @!app-log-path]
    (let [all-events (parse-app-log path)
          filtered (->> all-events
                        (filter #(matches-filter? % {:user-id user-id
                                                     :session-id session-id
                                                     :agent-id agent-id}))
                        (filter :turn-id))
          by-turn (group-by :turn-id filtered)]
      (->> by-turn
           (map (fn [[tid events]]
                  {:turn-id tid
                   :event-count (count events)
                   :first-ts (apply min (keep :mulog/timestamp events))
                   :last-ts (apply max (keep :mulog/timestamp events))
                   :categories (vec (distinct (map event-category events)))}))
           (sort-by :turn-id)
           vec))
    {:error "Log not available — app log path not configured"}))

;; ============================================================================
;; Commands (log$*)
;; ============================================================================

(defcommand log$turns
  "List turns in the current session with event counts and categories."
  (fn [{:keys [agent-id]}]
    (let [f (current-session-filters)
          turns (list-turns :user-id (:user-id f)
                            :session-id (:session-id f)
                            :agent-id agent-id)]
      {:turns turns}))
  :input-schema  [:map
                  [:agent-id {:optional true} [:string {:desc "Filter to specific agent (default: all agents in session)"}]]]
  :output-schema [:map
                  [:turns [:vector {:desc "Turns [{:turn-id :event-count :first-ts :last-ts :categories}]"}
                           [:map
                            [:turn-id [:int {:desc "Turn number"}]]
                            [:event-count [:int {:desc "Events in this turn"}]]
                            [:categories [:vector {:desc "Event categories seen"} :keyword]]]]]])

(defcommand log$events
  "Get events for a turn. Defaults to current turn. Includes subagent events."
  (fn [{:keys [turn-id type last-n agent-id]}]
    (let [f (current-session-filters)
          events (query-events :user-id (:user-id f)
                               :session-id (:session-id f)
                               :agent-id agent-id
                               :turn-id turn-id
                               :type type
                               :last-n (or last-n 50)
                               :summary true)]
      {:events events}))
  :input-schema  [:map
                  [:turn-id  {:optional true} [:int {:desc "Turn number (default: current)"}]]
                  [:type     {:optional true} [:string {:desc "Category filter: turn-start, turn-complete, task, conversation"}]]
                  [:last-n   {:optional true} [:int {:desc "Last N events (default 50)" :default 50}]]
                  [:agent-id {:optional true} [:string {:desc "Filter to specific agent (default: all agents in session)"}]]]
  :output-schema [:map
                  [:events [:vector {:desc "Summarized events"} :map]]])

(defcommand log$search
  "Search events by keyword across all agents and turns in the current session."
  (fn [{:keys [query turn-id last-n agent-id]}]
    (let [f (current-session-filters)
          events (query-events :user-id (:user-id f)
                               :session-id (:session-id f)
                               :agent-id agent-id
                               :turn-id turn-id
                               :query query
                               :last-n (or last-n 20)
                               :summary true)]
      {:events events}))
  :input-schema  [:map
                  [:query    [:string {:desc "Text to search for in event values"}]]
                  [:turn-id  {:optional true} [:int {:desc "Limit to specific turn"}]]
                  [:last-n   {:optional true} [:int {:desc "Max results (default 20)" :default 20}]]
                  [:agent-id {:optional true} [:string {:desc "Filter to specific agent (default: all agents in session)"}]]]
  :output-schema [:map
                  [:events [:vector {:desc "Matching summarized events"} :map]]])

(def log-commands
  "All log$ commands."
  [#'log$turns
   #'log$events
   #'log$search])
