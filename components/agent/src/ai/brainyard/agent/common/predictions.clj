;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.common.predictions
  "Per-session log of named predictor calls — the candidate pool an optimizer
   bootstraps demos from (docs/design/dspy-programming-model-proposal.md §4.3).

   One EDN line per predictor call is appended to
   `<project>/.brainyard/sessions/<session-id>/predictions.edn`, a sibling of
   `trajectory.edn`. Where the trajectory records how a TURN went, this records
   what each PREDICTOR saw and said, keyed by `:predictor-id`, so a call can be
   replayed as `{:inputs … :outputs …}` without re-running the turn.

   Record schema (one per line):
   {:v 1 :ts <epoch-ms> :session \"…\" :agent \"…\"
    :predictor-id \"coact/think-act-code\" :node-id :…
    :model \"…\" :params-source \"…\"|:dynamic|:default|nil :params-version …
    :inputs {…} :outputs {…} :reasoning \"…\" :valid? bool
    :usage {:in N :out N :cache-read N :cache-write N :cost 0.0}
    :error \"…\" :elapsed-ms N}

   Capture rides clj-llm's trace sink, which sees EVERY `run-predictor` call —
   BT nodes that name a `:predictor-id` and direct calls alike. The sink cannot
   know the session, so it routes on the trace context's `:agent` (bound by the
   BT dspy node) and falls back to `proto/*current-agent*` (bound around tool
   calls). A call with neither — e.g. graph extraction in the detached
   `by memory reduce` child — has no session and is not logged.

   Off by default (`:enable-prediction-log`): inputs here are the raw prompt
   inputs, which carry whatever the agent read, and the file grows by one line
   per LLM call rather than per turn. String values are clipped per field for
   the same reason trajectory clips them — one line must stay one line a human
   can open."
  (:require [ai.brainyard.agent.core.config :as config]
            [ai.brainyard.agent.core.feature :as feature]
            [ai.brainyard.agent.core.protocol :as proto]
            [ai.brainyard.clj-llm.interface :as clj-llm]
            [ai.brainyard.mulog.interface :as mulog]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.walk :as walk])
  (:import [java.io File]))

(def predictions-filename "predictions.edn")

(def ^:const max-string-chars
  "Per-string cap inside :inputs/:outputs/:reasoning."
  4000)

(defn session-predictions-file
  "The `predictions.edn` File for a session-id (not created on demand)."
  ^File [session-id]
  (io/file (str (config/sessions-root)) (name session-id) predictions-filename))

(defn- clip [v]
  (walk/postwalk
   (fn [x]
     (if (and (string? x) (> (count x) max-string-chars))
       (str (subs x 0 max-string-chars) "…[truncated " (- (count x) max-string-chars) " chars]")
       x))
   v))

(defn- usage->compact [usage]
  (when (map? usage)
    (not-empty
     (cond-> {}
       (:input-tokens usage)  (assoc :in (:input-tokens usage))
       (:output-tokens usage) (assoc :out (:output-tokens usage))
       (get-in usage [:cache :read-tokens])  (assoc :cache-read (get-in usage [:cache :read-tokens]))
       (get-in usage [:cache :write-tokens]) (assoc :cache-write (get-in usage [:cache :write-tokens]))
       (get-in usage [:cost :total-cost])    (assoc :cost (get-in usage [:cost :total-cost]))))))

(defn entry->record
  "Project a clj-llm trace entry into a log record."
  [entry session-id agent-id]
  (let [usage (usage->compact (:usage entry))]
    (cond-> {:v            1
             :ts           (System/currentTimeMillis)
             :session      (str session-id)
             :agent        (some-> agent-id str)
             :predictor-id (:predictor-id entry)
             :inputs       (clip (:inputs entry))}
      (get-in entry [:context :node-id]) (assoc :node-id (get-in entry [:context :node-id]))
      (:model entry)                     (assoc :model (:model entry))
      (some? (:params-source entry))     (assoc :params-source (:params-source entry))
      (some? (:params-version entry))    (assoc :params-version (:params-version entry))
      (contains? entry :outputs)         (assoc :outputs (clip (:outputs entry)))
      (:reasoning entry)                 (assoc :reasoning (clip (:reasoning entry)))
      (contains? entry :valid?)          (assoc :valid? (:valid? entry))
      usage                              (assoc :usage usage)
      (:error entry)                     (assoc :error (clip (:error entry)))
      (:elapsed-ms entry)                (assoc :elapsed-ms (:elapsed-ms entry)))))

(defn append-prediction!
  "Append one record as a single EDN line, serialized per session (parallel
   sub-agents share a session's file). Returns the record."
  [session-id record]
  (let [^File f (session-predictions-file session-id)
        line (str (pr-str record) "\n")]
    (locking (.intern (str "ai.brainyard.predictions:" (name session-id)))
      (let [parent (.getParentFile f)]
        (when (and parent (not (.exists parent))) (.mkdirs parent)))
      (spit f line :append true))
    record))

(defn read-predictions
  "All records for a session, oldest first; unreadable lines are skipped.
   Optional `predictor-id` filters to one predictor."
  ([session-id] (read-predictions session-id nil))
  ([session-id predictor-id]
   (let [^File f (session-predictions-file session-id)]
     (if-not (.isFile f)
       []
       (with-open [r (io/reader f)]
         (into []
               (comp (remove str/blank?)
                     (keep #(try (edn/read-string %) (catch Exception _ nil)))
                     (filter #(or (nil? predictor-id) (= predictor-id (:predictor-id %)))))
               (line-seq r)))))))

(defn sink
  "clj-llm trace sink: log the entry when it belongs to an agent session and
   `:analytics/predictions` is on for that agent."
  [entry]
  (let [agent (or (get-in entry [:context :agent]) proto/*current-agent*)
        sid   (when agent (proto/session-id agent))]
    ;; :suppress-log? — evaluation runs, so a dataset is never rebuilt from
    ;; the outputs of the model it was used to evaluate.
    (when (and sid
               (not (get-in entry [:context :suppress-log?]))
               (feature/on? agent :analytics/predictions))
      (append-prediction! sid (entry->record entry sid (proto/agent-id agent))))))

(defonce ^:private !installed (atom false))

(defn ensure-installed!
  "Install the trace sink once per process at RUNTIME (a runtime atom, so a
   native image bakes `false` and the first turn installs). Safe every turn."
  []
  (when (compare-and-set! !installed false true)
    (clj-llm/set-trace-sink! sink)
    (mulog/info ::sink-installed))
  nil)
