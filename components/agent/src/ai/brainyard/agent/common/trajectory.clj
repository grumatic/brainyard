;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.common.trajectory
  "Trajectory export for RLM execution traces.

   Captures structured execution trajectories suitable for:
   - Fine-tuning RLM models (training data format)
   - Visualization in the web app
   - Post-hoc analysis and debugging

   Export format (per docs/RLM-RESEARCH.md §5.5):
   {:query          \"...\"
    :context-summary \"...\"
    :trajectory      [{:iteration 1
                       :code      [\"(+ 1 2)\"]
                       :output    [\"=> 3\"]
                       :reasoning \"...\"}]
    :answer          \"...\"
    :success         true
    :cost            0.15
    :model           \"claude-sonnet-4-20250514\"
    :timing          {:started-at ms :ended-at ms :duration-ms ms}
    :metadata        {:agent-id \"...\" :session-id \"...\" :depth 0}}"
  (:require [clojure.string :as str])
  (:import [java.io File]))

;; ============================================================================
;; Trajectory Construction
;; ============================================================================

(defn- extract-reasoning
  "Extract natural language reasoning from an LLM response text,
   excluding code blocks."
  [response-text]
  (when response-text
    (let [without-code (str/replace response-text
                                    #"```(?:clojure|clj)\s*\n[\s\S]*?```"
                                    "")
          lines (->> (str/split-lines without-code)
                     (remove str/blank?)
                     (str/join "\n"))]
      (when-not (str/blank? lines)
        (str/trim lines)))))

(defn build-trajectory-entry
  "Build a single trajectory entry from an RLM iteration.

   Args:
     iteration-data - Map with :iteration, :code-results, :error keys
     response-text  - Raw LLM response text (optional, for reasoning extraction)

   Returns:
     {:iteration N :code [...] :output [...] :reasoning \"...\"}"
  [{:keys [iteration code-results error]} & [response-text]]
  (let [codes (when (seq code-results)
                (vec (keep :code code-results)))
        outputs (if (seq code-results)
                  (->> code-results
                       (keep :output)
                       (remove str/blank?)
                       vec)
                  [])
        outputs (if error
                  (conj outputs (str "ERROR: " error))
                  outputs)]
    (cond-> {:iteration iteration}
      (seq codes) (assoc :code codes)
      (seq outputs) (assoc :output outputs)
      response-text (assoc :reasoning (extract-reasoning response-text)))))

(defn build-trajectory
  "Build a complete trajectory export from an RLM execution result.

   Args:
     question     - The user's query
     result       - RLM completion result map
     opts         - Optional map with:
                    :model         - Model identifier string
                    :usage-summary - Usage tracker summary map
                    :agent-id      - Agent identifier
                    :session-id    - Session identifier
                    :depth         - Recursion depth
                    :context-keys  - Keys present in the RLM context

   Returns trajectory map matching the export schema."
  [question result & {:keys [model usage-summary agent-id session-id
                             depth context-keys started-at]}]
  (let [now (System/currentTimeMillis)
        iterations (or (:iterations result) [])
        terminated-by (:terminated-by result)
        success? (= :final terminated-by)
        answer (:answer result)
        ;; Build trajectory entries
        trajectory-entries (mapv build-trajectory-entry iterations)
        ;; Extract cost from usage summary
        cost (when usage-summary
               (or (:total-cost (:totals usage-summary))
                   (:total-cost usage-summary)))
        ;; Build context summary
        context-summary (when (seq context-keys)
                          (str "Context keys: " (str/join ", " (map name context-keys))))]
    (cond-> {:query question
             :trajectory trajectory-entries
             :success success?
             :terminated-by terminated-by
             :total-iterations (or (:total-iterations result) (count iterations))}
      answer (assoc :answer answer)
      context-summary (assoc :context-summary context-summary)
      cost (assoc :cost (double cost))
      model (assoc :model model)
      started-at (assoc :timing {:started-at started-at
                                 :ended-at now
                                 :duration-ms (- now started-at)})
      (or agent-id session-id depth) (assoc :metadata
                                            (cond-> {}
                                              agent-id (assoc :agent-id agent-id)
                                              session-id (assoc :session-id session-id)
                                              depth (assoc :depth depth))))))

;; ============================================================================
;; Trajectory Storage
;; ============================================================================

(defonce !trajectory-store
  (atom {:trajectories [] ;; in-memory buffer (bounded)
         :export-dir nil}))

(def ^:private max-in-memory 50)

(defn store-trajectory!
  "Store a trajectory in the in-memory buffer and optionally to disk.

   Args:
     trajectory - Trajectory map from build-trajectory
     opts       - Optional {:export-dir path} for disk persistence

   Returns the trajectory with :id added."
  [trajectory & {:keys [export-dir]}]
  (let [id (str (java.util.UUID/randomUUID))
        traj (assoc trajectory :id id :exported-at (System/currentTimeMillis))]
    ;; In-memory storage (bounded ring buffer)
    (swap! !trajectory-store
           (fn [state]
             (let [trajs (conj (:trajectories state) traj)
                   trajs (if (> (count trajs) max-in-memory)
                           (vec (drop (- (count trajs) max-in-memory) trajs))
                           trajs)]
               (assoc state :trajectories trajs))))
    ;; Disk persistence
    (when-let [dir (or export-dir (:export-dir @!trajectory-store))]
      (let [dir-file (File. ^String dir)]
        (when-not (.exists dir-file)
          (.mkdirs dir-file))
        (let [session-id (get-in traj [:metadata :session-id] "unknown")
              file-path (str dir "/" session-id "-" id ".edn")]
          (spit file-path (pr-str traj)))))
    traj))

(defn get-trajectories
  "Get stored trajectories.

   Options:
     :last-n     - Return only last N trajectories
     :session-id - Filter by session-id
     :successful - Filter by success status (true/false)"
  [& {:keys [last-n session-id successful]}]
  (let [trajs (:trajectories @!trajectory-store)]
    (cond->> trajs
      session-id (filter #(= session-id (get-in % [:metadata :session-id])))
      (some? successful) (filter #(= successful (:success %)))
      last-n (take-last last-n)
      true vec)))

(defn get-trajectory
  "Get a single trajectory by ID."
  [id]
  (first (filter #(= id (:id %)) (:trajectories @!trajectory-store))))

(defn clear-trajectories!
  "Clear the in-memory trajectory buffer."
  []
  (swap! !trajectory-store assoc :trajectories []))

(defn set-export-dir!
  "Set the default export directory for trajectory persistence."
  [dir]
  (swap! !trajectory-store assoc :export-dir dir))

;; ============================================================================
;; Trajectory Summary (for WebSocket push)
;; ============================================================================

(defn trajectory-summary
  "Create a lightweight summary of a trajectory for real-time display.

   Returns:
   {:id            \"...\"
    :query         \"...\" (truncated)
    :success       bool
    :total-iterations N
    :duration-ms   N
    :cost          0.15
    :model         \"...\"
    :iterations    [{:iteration N
                     :has-code  bool
                     :has-error bool
                     :output-length N}]}"
  [trajectory]
  (let [{:keys [id query answer success total-iterations timing cost model
                trajectory metadata terminated-by]} trajectory]
    (cond-> {:id id
             :query (if (and query (> (count query) 100))
                      (str (subs query 0 100) "...")
                      query)
             :success success
             :terminated-by terminated-by
             :total-iterations total-iterations}
      answer (assoc :answer (if (> (count answer) 500)
                              (str (subs answer 0 500) "...")
                              answer))
      cost (assoc :cost cost)
      model (assoc :model model)
      timing (assoc :duration-ms (:duration-ms timing))
      metadata (assoc :metadata metadata)
      (seq trajectory) (assoc :iterations
                              (mapv (fn [{:keys [iteration code output]}]
                                      {:iteration iteration
                                       :has-code (boolean (seq code))
                                       :has-error (some #(str/starts-with? % "ERROR:") (or output []))
                                       :output-length (reduce + 0 (map count (or output [])))})
                                    trajectory)))))

(defn format-trajectory-for-export
  "Format a trajectory as a training-data-ready JSON-compatible map.
   Strips internal metadata, keeps only the schema fields."
  [trajectory]
  (select-keys trajectory [:query :context-summary :trajectory :answer
                           :success :cost :model :total-iterations]))
