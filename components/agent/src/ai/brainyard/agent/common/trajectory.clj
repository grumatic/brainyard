;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.common.trajectory
  "Per-session trajectory recording for agent execution traces.

   One **EDN record per turn** is appended (newline-delimited, EDN-lines) to
   `<project>/.brainyard/sessions/<session-id>/trajectory.edn` — a sibling of the other
   well-known session files (`session.edn`, `usage-tracker.edn`, `messages.log`).
   Each record covers ALL iterations of the turn plus the final answer, so the
   file is a complete, append-only log of how the agent reached every answer.

   Suitable for post-hoc analysis/debugging and as fine-tuning training data.

   Record schema (one per line):
   {:v               2
    :ts              <epoch-ms>
    :session         \"<session-id>\"
    :agent           \"<agent-id>\"
    :turn            N
    :question        \"the user's question\"
    :answer          \"the final answer\"
    :success         true|false
    :terminated-by   :answer | :max-iterations | ...
    :total-iterations N
    :iterations      [{:n 1 :channel \"code\" :thought \"...\"
                       :code [...] :result [...] :output [...] :error [...]}
                      {:n 2 :channel \"tool\" :thought \"...\"
                       :tools [{:name \"read-file\" :args {...} :result \"...\"}]}]
    :model           \"...\"
    :cost            0.0042
    :usage           {:in N :out N :cache-read N :cache-write N}
    :duration-ms     N}

   Newline-delimiting is safe because records are written with `pr-str`
   (readable printing escapes embedded newlines inside strings, so every record
   occupies exactly one physical line). Records are read back with
   `clojure.edn/read-string` (no eval — native-image safe).

   NOTE: this namespace deliberately avoids a Polylith dep on `agent-tui-persist`
   (same cross-reference pattern as `memory_agent`). It resolves the
   project-scoped sessions root through `config/sessions-root` — the single
   authority — at call time, so there is no private root mirror to drift. Tests
   redirect writes by binding `config/*sessions-root-override*` (or the
   `agent/with-sessions-root` macro)."
  (:require [ai.brainyard.agent.core.config :as config]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.io File]))

;; ============================================================================
;; Layout
;; ============================================================================

(def trajectory-filename "trajectory.edn")

(defn session-trajectory-file
  "The `trajectory.edn` File for a session-id (not created on demand).
   Resolves the project-scoped sessions root via `config/sessions-root`."
  ^File [session-id]
  (io/file (str (config/sessions-root))
           (name session-id) trajectory-filename))

;; ============================================================================
;; Truncation — keep individual lines bounded
;; ============================================================================

(def ^:const max-field-chars 4000)   ;; per code / result / output / tool-result
(def ^:const max-thought-chars 2000) ;; per-iteration reasoning
(def ^:const max-answer-chars 50000) ;; final answer

(defn- trunc
  "Truncate a string to n chars, appending a marker when clipped. Returns the
   input unchanged when it is nil or already within budget."
  [s n]
  (if (and (string? s) (> (count s) n))
    (str (subs s 0 n) "…[truncated " (- (count s) n) " chars]")
    s))

;; ============================================================================
;; Iteration projection
;; ============================================================================

(defn project-iteration
  "Project one raw coact iteration record into a compact trajectory entry,
   preserving full per-channel detail.

   Raw shape (see coact-agent `coact-accumulate-iteration-action`):
     {:iteration N :thought s :channel \"tool\"|\"code\"|\"none\"
      :tool-results [{:tool-name :tool-args :tool-result}]
      :code-results [{:lang :code :result :output :error ...}]
      :async-completion? bool}

   Returns nil for synthetic records that carry no trace value
   (in-flight rosters, blank evaluation stubs)."
  [{:keys [iteration thought channel tool-results code-results
           async-completion? in-flight-roster?]}]
  (when-not in-flight-roster?
    (let [base (cond-> {:n iteration :channel (or channel "none")}
                 (not (str/blank? thought)) (assoc :thought (trunc thought max-thought-chars))
                 async-completion?          (assoc :async? true))]
      (case (or channel "none")
        "tool"
        (cond-> base
          (seq tool-results)
          (assoc :tools
                 (mapv (fn [{:keys [tool-name tool-args tool-result]}]
                         (cond-> {:name (str tool-name)}
                           (some? tool-args)              (assoc :args tool-args)
                           (not (str/blank? tool-result)) (assoc :result (trunc tool-result max-field-chars))))
                       tool-results)))

        ;; "code" / "none" / anything else — project the code-results channels.
        (let [codes   (->> code-results (keep :code)   (remove str/blank?) (mapv #(trunc % max-field-chars)))
              results (->> code-results (keep :result) (remove str/blank?) (mapv #(trunc % max-field-chars)))
              outputs (->> code-results (keep :output) (remove str/blank?) (mapv #(trunc % max-field-chars)))
              errors  (->> code-results (keep :error)  (remove str/blank?) vec)]
          (cond-> base
            (seq codes)   (assoc :code codes)
            (seq results) (assoc :result results)
            (seq outputs) (assoc :output outputs)
            (seq errors)  (assoc :error errors)))))))

;; ============================================================================
;; Turn-record construction
;; ============================================================================

(defn- usage->compact
  "Pull a small, stable token/cost summary out of the clj-llm usage summary."
  [usage-summary]
  (when (map? usage-summary)
    (let [totals (or (:totals usage-summary) usage-summary)]
      (not-empty
       (cond-> {}
         (:input-tokens totals)        (assoc :in (:input-tokens totals))
         (:output-tokens totals)       (assoc :out (:output-tokens totals))
         (:cache-read-tokens totals)   (assoc :cache-read (:cache-read-tokens totals))
         (:cache-write-tokens totals)  (assoc :cache-write (:cache-write-tokens totals)))))))

(defn- usage->cost
  [usage-summary]
  (when (map? usage-summary)
    (or (:total-cost (:totals usage-summary))
        (:total-cost usage-summary))))

(defn build-turn-trajectory
  "Build a single per-turn trajectory record covering all iterations + answer.

   opts keys:
     :session-id :agent-id :turn-id :question :answer :iterations
     :success :terminated-by :total-iterations :model :usage-summary
     :started-at :ended-at

   `:iterations` is the FULL (uncapped) raw iteration vector for the turn."
  [{:keys [session-id agent-id turn-id question answer iterations
           success terminated-by total-iterations model usage-summary
           started-at ended-at]}]
  (let [ended (or ended-at (System/currentTimeMillis))
        entries (->> (or iterations []) (keep project-iteration) vec)
        cost (usage->cost usage-summary)
        usage (usage->compact usage-summary)]
    (cond-> {:v 2
             :ts ended
             :turn turn-id
             :question question
             :answer (trunc answer max-answer-chars)
             :success (boolean success)
             :terminated-by terminated-by
             :total-iterations (or total-iterations (count entries))
             :iterations entries}
      session-id (assoc :session (str session-id))
      agent-id   (assoc :agent (str agent-id))
      model      (assoc :model model)
      cost       (assoc :cost (double cost))
      usage      (assoc :usage usage)
      started-at (assoc :duration-ms (- ended started-at)))))

;; ============================================================================
;; Append / read
;; ============================================================================

(defn append-trajectory!
  "Append one turn record to the session's `trajectory.edn` as a single
   newline-terminated EDN line. Creates the session directory on demand.
   Serialized per-session via `locking` so parallel sub-agents writing the
   same file can't interleave a partial line. Returns the record."
  [session-id record]
  (let [^File f (session-trajectory-file session-id)
        line (str (pr-str record) "\n")]
    ;; Intern gives a canonical String monitor keyed by session-id.
    (locking (.intern (str "ai.brainyard.trajectory:" (name session-id)))
      (let [parent (.getParentFile f)]
        (when (and parent (not (.exists parent))) (.mkdirs parent)))
      (spit f line :append true))
    record))

(defn read-trajectories
  "Read all turn records for a session back into a vector (oldest first).
   Returns nil when the file does not exist. Skips any line that fails to
   parse rather than throwing — a half-written tail line never poisons reads."
  [session-id]
  (let [^File f (session-trajectory-file session-id)]
    (when (.exists f)
      (with-open [r (io/reader f)]
        (->> (line-seq r)
             (remove str/blank?)
             (keep (fn [ln] (try (edn/read-string ln) (catch Exception _ nil))))
             vec)))))

(defn latest-trajectory
  "The most recent turn record for a session, or nil."
  [session-id]
  (last (read-trajectories session-id)))
