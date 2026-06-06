;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.task.commands
  "LLM-accessible task management commands.
   Registered via defcommand into !tool-defs for discovery by react-agent and coact-agent."
  (:require [ai.brainyard.agent.core.config :as config]
            [ai.brainyard.agent.core.protocol :as proto]
            [ai.brainyard.agent.core.tool :refer [defcommand]]
            [ai.brainyard.agent.gc :as gc]
            [ai.brainyard.agent.task.manager :as manager]
            [ai.brainyard.agent.task.persist :as persist]
            [ai.brainyard.agent.task.protocol :as tp]
            [clojure.data.json :as json]
            [clojure.string :as str])
  (:import [java.io File]))

(defcommand task$list
  "List all background tasks with their status."
  (fn [& {:as args}]
    (if-let [mgr (manager/get-default-manager)]
      (let [status-str (:status args)
            filters (when (and status-str (not (str/blank? status-str)))
                      {:status (keyword status-str)})
            tasks (if filters
                    (tp/list-tasks mgr filters)
                    (tp/list-tasks mgr))]
        {:tasks (mapv (fn [t]
                        {:id (name (:id t))
                         :name (:name t)
                         :status (name (:status t))
                         :job-type (name (:job-type t))
                         :output-lines (count @(:output-lines t))})
                      tasks)
         :total (count tasks)})
      {:error "Task manager not initialized"}))
  :input-schema  [:map
                  [:status {:optional true} [:string {:desc "Filter: pending, running, completed, failed, cancelled"}]]]
  :output-schema [:map
                  [:tasks [:vector {:desc "Task summaries — each {:id :name :status :job-type :output-lines}"} :map]]
                  [:total [:int {:desc "Total number of tasks"}]]
                  [:error [:string {:desc "Error message if failed"}]]])

(defcommand task$detail
  "Get detailed info about a background task: status, lifecycle timestamps,
   executor result, in-memory tail cache stats, and (optionally via :last-n)
   the tail of captured output. The on-disk `:output-file` always has the
   complete record — slurp it when :truncated? or when :last-n is too small.

   Replaces the separate task$output command. Pass `:last-n N` for the
   tail; omit it for metadata-only (faster, no line list)."
  (fn [& {:as args}]
    (if-let [mgr (manager/get-default-manager)]
      (let [task-id-str (:task-id args)]
        (if (str/blank? task-id-str)
          {:error "task-id is required"}
          (let [task-id     (keyword task-id-str)
                last-n      (some-> (:last-n args) parse-long)]
            (if-let [task (tp/get-task mgr task-id)]
              (let [cached      @(:output-lines task)
                    cached-cnt  (count cached)
                    output-file (persist/output-path nil task-id)
                    meta-file   (persist/meta-path nil task-id)
                    disk-cnt    (when output-file (persist/line-count nil task-id))
                    truncated?  (and disk-cnt (> disk-cnt cached-cnt))
                    now          (System/currentTimeMillis)
                    started-at   (:started-at task)
                    completed-at (:completed-at task)
                    ;; Liveness signals — let an LLM polling a long-running
                    ;; subagent task distinguish "alive but quiet" from "wedged"
                    ;; instead of cancelling on a frozen line count. last-output
                    ;; is the output.log mtime (append-line! flushes every line),
                    ;; so it advances with each streamed line / heartbeat.
                    elapsed-ms   (when started-at (- (or completed-at now) started-at))
                    last-mtime   (when output-file
                                   (let [f (File. ^String output-file)]
                                     (when (.exists f)
                                       (let [m (.lastModified f)]
                                         (when (pos? m) m)))))
                    last-output-age-ms (when (and last-mtime (nil? completed-at))
                                         (max 0 (- now last-mtime)))
                    ;; Structured progress snapshot for a running subagent task —
                    ;; one glance (iteration, tools-completed, last tool, latest
                    ;; observation) instead of parsing the streamed log tail.
                    progress     (manager/task-progress task-id)]
                (cond-> {:id           (name (:id task))
                         :name         (:name task)
                         :status       (name (:status task))
                         :job-type     (name (:job-type task))
                         :result       (:result task)
                         :cached-lines cached-cnt
                         :total-lines  (or disk-cnt cached-cnt)
                         :truncated?   (boolean truncated?)
                         :created-at   (:created-at task)
                         :started-at   (:started-at task)
                         :completed-at (:completed-at task)}
                  elapsed-ms         (assoc :elapsed-ms elapsed-ms)
                  last-output-age-ms (assoc :last-output-age-ms last-output-age-ms)
                  progress           (assoc :progress progress)
                  last-n             (assoc :lines (vec (take-last last-n cached)))
                  output-file        (assoc :output-file output-file)
                  meta-file          (assoc :meta-file meta-file)))
              {:error (str "Task not found: " task-id-str)}))))
      {:error "Task manager not initialized"}))
  :input-schema  [:map
                  [:task-id [:string {:desc "Task ID (e.g. task-1)"}]]
                  [:last-n  {:optional true} [:string {:desc "When set, include :lines = last N lines from the in-memory tail cache. Omit for metadata-only."}]]]
  :output-schema [:map
                  [:id           [:string  {:desc "Task ID"}]]
                  [:name         [:string  {:desc "Task name"}]]
                  [:status       [:string  {:desc "Task status"}]]
                  [:job-type     [:string  {:desc "Job type"}]]
                  [:result       {:optional true} [:map     {:desc "Executor return map, e.g. {:exit-code 0} for :bash, tool return for :tool"}]]
                  [:cached-lines [:int     {:desc "Lines held in the in-memory tail cache (capped by :max-output-lines, default 500)"}]]
                  [:total-lines  [:int     {:desc "Total lines written to disk (source of truth)"}]]
                  [:truncated?   [:boolean {:desc "True when :total-lines > :cached-lines — older lines evicted from cache, still on disk"}]]
                  [:elapsed-ms   {:optional true} [:int {:desc "Wall-clock ms since the work started (to :completed-at if terminal, else now). Present once :started-at is set."}]]
                  [:last-output-age-ms {:optional true} [:int {:desc "ms since the last output line was written (output.log mtime). Liveness signal for a running task: small = actively producing; large but growing on re-poll = alive but quiet (e.g. a subagent mid-LLM-turn). DO NOT cancel a task on a quiet window alone. Present only while running."}]]
                  [:progress     {:optional true} [:map {:desc "Structured progress snapshot for a running subagent task — {:iteration :tools-completed :last-tool :last-tool-result :last-reasoning}. :last-reasoning is the agent's latest 'Think:' text. Read this instead of parsing the log tail to judge how far along a subagent is. Absent for non-subagent tasks and once terminal."}]]
                  [:lines        {:optional true} [:vector  {:desc "Present only when :last-n was supplied — tail of the in-memory cache. Slurp :output-file when :truncated? is true or when you need lines beyond the cache."} :string]]
                  [:created-at   [:int     {:desc "Creation timestamp (epoch ms)"}]]
                  [:started-at   {:optional true} [:int     {:desc "Start timestamp (epoch ms); nil before start"}]]
                  [:completed-at {:optional true} [:int     {:desc "Completion timestamp (epoch ms); nil before terminal"}]]
                  [:output-file  {:optional true} [:string  {:desc "Absolute path to the combined stdout/stderr log file — always has the complete record."}]]
                  [:meta-file    {:optional true} [:string  {:desc "Absolute path to lifecycle meta.edn (status, result, timestamps)."}]]
                  [:error        [:string  {:desc "Error message if not found"}]]])

(defcommand task$cancel
  "Cancel a running background task."
  (fn [& {:as args}]
    (if-let [mgr (manager/get-default-manager)]
      (let [task-id-str (:task-id args)]
        (if (str/blank? task-id-str)
          {:error "task-id is required"}
          (let [task-id (keyword task-id-str)]
            (if (tp/cancel-task mgr task-id)
              {:result (str "Cancelled: " task-id-str)}
              {:error (str "Could not cancel: " task-id-str)}))))
      {:error "Task manager not initialized"}))
  :input-schema  [:map
                  [:task-id [:string {:desc "Task ID to cancel (e.g. task-1)"}]]]
  :output-schema [:map
                  [:result [:string {:desc "Cancellation confirmation"}]]
                  [:error  [:string {:desc "Error message if failed"}]]])

;; ============================================================================
;; Per-job-type helpers — used by task$run
;; ============================================================================

(defn- sync-meta
  "When sync? is true, seed metadata with a mutable `!sync-waiter?` atom so
   an external thread can later flip it to false to detach the sync waiter.
   When sync? is false, return nil (no metadata override; defaults to {})."
  [sync?]
  (when sync? {:metadata {:!sync-waiter? (atom true)}}))

;; ----------------------------------------------------------------------------
;; :bash job-type path validation
;; ----------------------------------------------------------------------------
;; Runs synchronously on the caller's thread BEFORE any task is created or
;; handed off to the executor pool — so `proto/*current-agent*` (which the
;; caller's frame still has bound) is in scope for permission lookups.

(defn- validate-bash-paths
  "Scan command for absolute paths and validate each against allowed dirs.
   Returns nil if OK, or {:error msg} if access denied. If permission-fn is
   available, prompts via that callback before denying."
  [command canonical-allowed permission-fn]
  (let [abs-paths (re-seq #"(?:^|[\s\"'=])(/[^\s\"'>;|&]+)" command)
        violations (when (seq abs-paths)
                     (seq (remove (fn [[_ path]]
                                    (try
                                      (let [f (File. ^String path)]
                                        (or (not (.exists f))
                                            (let [canon (.getPath (.getCanonicalFile f))]
                                              (some #(str/starts-with? canon %) canonical-allowed))))
                                      (catch Exception _ true)))
                                  abs-paths)))]
    (when violations
      (if permission-fn
        (let [paths (map second violations)
              resp (permission-fn {:type :file-access :paths (vec paths)
                                   :command command :action :bash})]
          (when-not (:allowed resp)
            {:error (str "Access denied for paths: " (str/join ", " paths)
                         ". Allowed dirs: " (str/join ", " canonical-allowed))}))
        {:error (str "Access denied for absolute paths outside allowed dirs. "
                     "Allowed: " (str/join ", " canonical-allowed)
                     ". Use relative paths from project dir instead.")}))))

(defn- executor-timeout-ms
  "Resolve the bound to plumb into the executor's job-config `:timeout-ms`.

   Async mode (sync? = false): `:timeout` is the executor's own deadline
   (semantics unchanged) — defaults to the agent's :task-timeout-ms when
   not given.

   Sync mode (sync? = true): `:timeout` is the *waiter's* deadline
   (consumed by await-task), so the executor's `:timeout-ms` is the
   agent's global cap. This separation makes :on-timeout :detach
   meaningful — the waiter can return :pending at the LLM's deadline
   while the underlying work keeps running up to the global cap."
  [timeout sync?]
  (let [agent-cap (config/get-config proto/*current-agent* :task-timeout-ms)]
    (if sync?
      agent-cap
      (if timeout (parse-long timeout) agent-cap))))

(defn- run-bash-job
  "Create + start a :bash task. Validates absolute paths in the command
   against the current agent's allowed-dirs before doing anything.
   Returns {:result {...}} or {:error ...}."
  [{:keys [command name timeout sync?]}]
  (if-let [mgr (manager/get-default-manager)]
    (if (str/blank? command)
      {:error "command is required"}
      (let [{:keys [base-dir canonical-allowed permission-fn]} (config/resolve-agent-dirs)]
        (or (validate-bash-paths command canonical-allowed permission-fn)
            (let [task-name (or (when-not (str/blank? name) name)
                                (str "bash: " (subs command 0 (min 200 (count command)))))
                  timeout-ms (executor-timeout-ms timeout sync?)
                  task (tp/create-task mgr task-name :bash
                                       {:command command
                                        :working-dir base-dir
                                        :timeout-ms timeout-ms}
                                       (or (sync-meta sync?) {}))]
              (tp/start-task mgr (:id task))
              {:result {:task-id (clojure.core/name (:id task))
                        :name task-name
                        :status "running"}}))))
    {:error "Task manager not initialized"}))

(defn- run-tool-job
  "Create + start a :tool task. Returns {:result {...}} or {:error ...}."
  [{:keys [tool-id tool-args name timeout sync?]}]
  (if-let [mgr (manager/get-default-manager)]
    (if (str/blank? tool-id)
      {:error "tool-id is required"}
      (let [tool-id-kw (keyword tool-id)
            timeout-ms (executor-timeout-ms timeout sync?)
            args-or-err (if-not (str/blank? tool-args)
                          (try
                            (let [parsed (json/read-str tool-args :key-fn keyword)]
                              (if (map? parsed)
                                parsed
                                {:error "tool-args must be a JSON object"}))
                            (catch Exception e
                              {:error (str "Invalid JSON in tool-args: " (ex-message e))}))
                          {})]
        (if (:error args-or-err)
          args-or-err
          (let [task-name (or (when-not (str/blank? name) name)
                              (str "tool: " (clojure.core/name tool-id-kw)))
                task (tp/create-task mgr task-name :tool
                                     {:tool-id tool-id-kw :tool-args args-or-err
                                      :timeout-ms timeout-ms
                                      :agent proto/*current-agent*}
                                     (or (sync-meta sync?) {}))]
            (tp/start-task mgr (:id task))
            {:result {:task-id (clojure.core/name (:id task))
                      :name task-name
                      :status "running"}}))))
    {:error "Task manager not initialized"}))

;; ----------------------------------------------------------------------------
;; Sync waiter — used when task$run is invoked with :sync true
;; ----------------------------------------------------------------------------

(def ^:private terminal-statuses #{:completed :failed :cancelled})

(defn await-task
  "Poll a task until terminal, timeout, or external detach. Returns a
   consolidated result map shaped like the per-job sync result.

   Three early-return conditions:

   1. External detach — supervisor flipped the task's
      `:metadata :!sync-waiter?` atom to false; loop returns
      `:status \"running\" :detached true` with the output captured so far.
      Reverse direction (false → true) is ignored.

   2. `:on-timeout :kill` — sync deadline reached; `cancel-task` runs
      (executor is killed / detached-handler's :on-cancel fires), the loop
      waits up to 2 s for the task to land in a terminal status, then
      returns `:status \"timeout\" :timeout-ms <ms>`.

   3. `:on-timeout :detach` (the default) — sync deadline reached but the
      executor is left running. Returns immediately with
      `:status \"pending\" :task-id <id> :timeout-ms <ms>` plus an output
      snapshot. Task stays `:running` in the manager and its display-mode
      flips to :background; callers poll later via `task$detail` (add
      `:last-n N` for the output tail). Used by the coact code-eval flow
      so long-running blocks survive the auto-background deadline and are
      harvested in a later iteration."
  [mgr task-id timeout-ms & {:keys [on-timeout] :or {on-timeout :detach}}]
  {:pre [(#{:kill :detach :snapshot} on-timeout)]}
  (let [snapshot
        (fn [task extra]
          (let [task   (or (tp/get-task mgr task-id) task)
                jt     (:job-type task)
                lines  @(:output-lines task)
                out    (str/join "\n" lines)
                out    (cond-> out
                         (= :bash jt) (str/replace #"\033\[[0-9;]*[a-zA-Z]" ""))]
            (merge {:task-id   (clojure.core/name (:id task))
                    :name      (:name task)
                    :job-type  (clojure.core/name jt)
                    :status    (clojure.core/name (:status task))
                    :exit-code (get-in task [:result :exit-code])
                    :output    out
                    :result    (:result task)}
                   extra)))]
    (loop [elapsed 0]
      (let [task   (tp/get-task mgr task-id)
            status (:status task)
            sync?  (some-> task :metadata :!sync-waiter? deref)]
        (cond
          (terminal-statuses status)
          (snapshot task nil)

          (false? sync?)
          (snapshot task {:status "running" :detached true})

          (>= elapsed timeout-ms)
          (case on-timeout
            :kill
            ;; Cancel, wait briefly for terminal, return :timeout.
            (do (tp/cancel-task mgr task-id)
                (loop [waited 0]
                  (let [t (tp/get-task mgr task-id)]
                    (if (or (terminal-statuses (:status t)) (>= waited 2000))
                      (snapshot t {:status "timeout" :timeout-ms timeout-ms})
                      (do (Thread/sleep (long 100))
                          (recur (+ waited 100)))))))
            :detach
            ;; Sync → async hand-off: the waiter is leaving, the task keeps
            ;; running. Flip display-mode to :background so the TUI per-task
            ;; block disposes (and emits its marker). Return :pending snapshot.
            (do (manager/set-display-mode! mgr task-id :background)
                (snapshot task {:status "pending" :timeout-ms timeout-ms}))
            :snapshot
            ;; Pure observation: return current status + output tail without
            ;; any side effects (no display-mode flip, no cancellation).
            (snapshot task {:status "still-running" :timeout-ms timeout-ms}))

          :else
          ;; Atom-read polling is cheap; the per-task overhead is negligible
          ;; even for long waits. 100ms strikes a balance between responsiveness
          ;; (short waiter deadlines) and CPU use.
          (do (Thread/sleep (long 100))
              (recur (+ elapsed 100))))))))

;; ============================================================================
;; Polymorphic command — preferred surface
;; ============================================================================

(defcommand task$run
  "Start a task. Pick the job type via `:job-type`.

   :bash — required: :command. Optional: :name, :timeout (ms; default 120000).
   :tool — required: :tool-id (keyword string). Optional: :tool-args (JSON
           object string), :name, :timeout (ms; default 120000; best-effort
           interrupt — tools that don't respect Thread interrupts may keep
           running in the background until they return on their own).

   Two orthogonal dimensions:

   `:sync` (caller wait mode, default false)
     false = async: return immediately with `{:result {:task-id <id>
             :name <n> :status \"running\"}}`. Poll with `task$detail`
             (pass `:last-n N` for output), stop with `task$cancel`.
     true  = sync : block until the task reaches terminal status, the
             deadline (`:timeout`) is hit, or external code detaches the
             waiter via `manager/set-sync-mode!` (true → false).

   `:on-timeout` (sync deadline policy, default :detach; ignored when :sync false)
     \"detach\" (default — unified code-eval contract)
       Stop waiting but let the task keep running. Returns
       `{:status \"pending\" :task-id <id> :timeout-ms <ms> :output <snapshot>}`.
       Poll later with `task$detail` (pass `:last-n N` for output).

     \"kill\"
       Cancel the task (executor is killed / detach-handler's :on-cancel
       fires), wait briefly for terminal status, return
       `{:status \"timeout\" :timeout-ms <ms> :output <snapshot>}`.

   Returns a consolidated map `{:status :task-id :name :job-type :exit-code
   :output :result}` plus `:timeout-ms` on detach/kill timeout or
   `:detached true` on external sync-waiter detach."
  (fn [& {:keys [job-type sync on-timeout] :as args}]
    (let [sync?   (boolean sync)
          policy  (case (some-> on-timeout str/lower-case)
                    "kill"   :kill
                    "detach" :detach
                    nil      :detach
                    :detach)
          start (case (some-> job-type keyword)
                  :bash (run-bash-job (assoc args :sync? sync?))
                  :tool (run-tool-job (assoc args :sync? sync?))
                  nil   {:error ":job-type is required (:bash or :tool)"}
                  {:error (str "Unknown :job-type '" job-type "'. Valid: :bash :tool")})]
      (if (or (not sync?) (:error start))
        start
        (await-task (manager/get-default-manager)
                    (keyword (get-in start [:result :task-id]))
                    (or (some-> (:timeout args) parse-long) (config/get-config proto/*current-agent* :task-timeout-ms))
                    :on-timeout policy))))
  :input-schema  [:map
                  [:job-type   [:enum {:desc "Job type: bash | tool"} "bash" "tool"]]
                  [:command    {:optional true} [:string {:desc "For :bash — the shell command"}]]
                  [:timeout    {:optional true} [:string {:desc "Deadline in ms (default = agent :task-timeout-ms, typically 120000). In sync mode this is the waiter's deadline only — the underlying executor is bounded by the agent global cap, so detach-mode work can keep running after the waiter returns :pending. In async mode this is the executor's own kill deadline. :tool uses best-effort interrupt."}]]
                  [:tool-id    {:optional true} [:string {:desc "For :tool — registered tool ID (e.g. my-tool)"}]]
                  [:tool-args  {:optional true} [:string {:desc "For :tool — JSON object of tool arguments"}]]
                  [:name       {:optional true} [:string {:desc "Optional task name"}]]
                  [:sync       {:optional true :default false} [:boolean {:desc "If true, block until terminal/timeout/detached. External code can flip false to detach (true→false only). Default false (async)."}]]
                  [:on-timeout {:optional true :default "detach"} [:enum {:desc "Sync-mode deadline policy. \"detach\" (default) = return :status \"pending\" and let the task keep running, harvest later via task$detail. \"kill\" = cancel the task and return :status \"timeout\". No effect when :sync is false."} "detach" "kill"]]]
  :output-schema [:map
                  [:result     {:optional true} [:map     {:desc "Async: {:task-id :name :status}. Sync: raw executor result, e.g. {:exit-code 0}"}]]
                  [:task-id    {:optional true} [:string  {:desc "Sync: task id (use with task$detail / task$cancel)"}]]
                  [:name       {:optional true} [:string  {:desc "Sync: task name"}]]
                  [:job-type   {:optional true} [:string  {:desc "Sync: \"bash\" or \"tool\""}]]
                  [:status     {:optional true} [:string  {:desc "Sync: completed | failed | cancelled | timeout | pending | running"}]]
                  [:exit-code  {:optional true} [:int     {:desc "Sync :bash: process exit code"}]]
                  [:output     {:optional true} [:string  {:desc "Sync: joined output lines (ANSI-stripped for :bash). Present on terminal AND on pending/timeout snapshots."}]]
                  [:timeout-ms {:optional true} [:int     {:desc "Sync: present on :status = \"timeout\" (kill) or :status = \"pending\" (detach)"}]]
                  [:detached   {:optional true} [:boolean {:desc "Sync: true when external code flipped sync→async mid-run"}]]
                  [:error      [:string  {:desc "Error message if failed"}]]])

(defcommand task$sweep
  "Garbage-collect on-disk task and task-adjacent artifacts.

   Sweeps three classes under `<project>/.brainyard/`:
     :tasks          tasks/task-N/             — keep newest N OR younger than D days
     :coact-scratch  coact-agent/scratch/      — drop files older than H hours
     :sandbox-cache  clj-sandbox/truncation/   — cap by count, bytes, age
                     clj-sandbox/file-backed/

   Retention bounds come from config keys
     :task-retention-count :task-retention-days
     :coact-scratch-max-age-hours
     :sandbox-cache-max-files :sandbox-cache-max-bytes :sandbox-cache-max-age-days
   (see ai.brainyard.agent.core.config/config-schema).

   With `:dry-run? true`, reports what would be deleted without acting."
  (fn [& {:keys [scope dry-run?]}]
    (let [scope* (or scope "all")
          run    (fn [sweep-fn class]
                   (try (sweep-fn nil :dry-run? dry-run?)
                        (catch Exception e
                          {:class class :error (ex-message e)
                           :scanned 0 :deleted 0 :kept 0 :bytes-freed 0
                           :dry-run? (boolean dry-run?)})))
          results (case scope*
                    "tasks"         [(run gc/sweep-tasks!         :tasks)]
                    "coact-scratch" [(run gc/sweep-coact-scratch! :coact-scratch)]
                    "sandbox-cache" [(run gc/sweep-sandbox-cache! :sandbox-cache)]
                    "all"           [(run gc/sweep-tasks!         :tasks)
                                     (run gc/sweep-coact-scratch! :coact-scratch)
                                     (run gc/sweep-sandbox-cache! :sandbox-cache)]
                    [{:error (str "Unknown :scope '" scope* "'. Valid: tasks coact-scratch sandbox-cache all")
                      :class :unknown :scanned 0 :deleted 0 :kept 0 :bytes-freed 0
                      :dry-run? (boolean dry-run?)}])]
      {:results (mapv (fn [r] (update r :class #(some-> % name))) results)
       :total-deleted     (reduce + 0 (map :deleted results))
       :total-bytes-freed (reduce + 0 (map :bytes-freed results))
       :dry-run?          (boolean dry-run?)}))
  :input-schema  [:map
                  [:scope    {:optional true :default "all"} [:enum {:desc "Which class to sweep. Default: all."}
                                                              "tasks" "coact-scratch" "sandbox-cache" "all"]]
                  [:dry-run? {:optional true :default false} [:boolean {:desc "Report without deleting."}]]]
  :output-schema [:map
                  [:results           [:vector {:desc "Per-class result maps."}
                                       [:map
                                        [:class       [:string]]
                                        [:scanned     [:int]]
                                        [:deleted     [:int]]
                                        [:kept        [:int]]
                                        [:bytes-freed [:int]]
                                        [:dry-run?    [:boolean]]
                                        [:error       {:optional true} [:string]]]]]
                  [:total-deleted     [:int     {:desc "Sum of :deleted across all classes."}]]
                  [:total-bytes-freed [:int     {:desc "Sum of :bytes-freed across all classes."}]]
                  [:dry-run?          [:boolean {:desc "Echo of input dry-run flag."}]]])

(defcommand task$wait
  "Block until a running task completes or timeout. Use after hold-timeout when a task is STILL RUNNING."
  (fn [& {:as args}]
    (if-let [mgr (manager/get-default-manager)]
      (let [task-id-str (:task-id args)
            timeout     (some-> (:timeout-ms args) long)]
        (cond
          (str/blank? task-id-str)
          {:error "task-id is required"}

          (or (nil? timeout) (<= timeout 0))
          {:error "timeout-ms is required and must be positive"}

          :else
          (let [task-id (keyword task-id-str)
                task    (tp/get-task mgr task-id)]
            (cond
              (nil? task)
              {:error (str "Task not found: " task-id-str)}

              (terminal-statuses (:status task))
              (let [out (str/join "\n" @(:output-lines task))]
                {:status  (name (:status task)) :task-id task-id-str
                 :result  (:result task)
                 :output  out})

              (not= :running (:status task))
              {:error (str "Task " task-id-str " is " (name (:status task))
                           ", not running — cannot wait")}

              :else
              (await-task mgr task-id timeout :on-timeout :snapshot)))))
      {:error "Task manager not initialized"}))
  :sync true
  :input-schema  [:map
                  [:task-id    [:string {:desc "Task ID (e.g. task-1)"}]]
                  [:timeout-ms [:int    {:desc "Max ms to wait before returning"}]]]
  :output-schema [:map
                  [:status  [:string {:desc "completed | still-running | failed | cancelled | error"}]]
                  [:task-id [:string {:desc "Echo of input task-id"}]]
                  [:result  [:any    {:desc "Task result (when completed)"}]]
                  [:output  [:string {:desc "Captured output (when completed)"}]]
                  [:message [:string {:desc "Guidance on next steps (when still-running)"}]]
                  [:error   [:string {:desc "Error message if failed"}]]])

;; ----------------------------------------------------------------------------
;; task$wakeup — yield-and-resume (non-blocking counterpart to task$wait)
;; ----------------------------------------------------------------------------
;; The LLM parks the current turn and is auto-resumed via a one-shot watch on
;; !tasks. Two modes (exactly one):
;;   :delay-ms — TIMER mode: creates a background :bash `sleep` task and
;;               resumes only when it :completed (an aborted timer = no resume).
;;   :task-id  — WATCH mode: resumes when an EXISTING task terminates in ANY
;;               status (:completed/:failed/:cancelled) — pairs with
;;               deferred-tasking (park on a detached task instead of polling
;;               via task$wait). Creates no new task.
;; The resume routes through `submit-turn` → the host's turn-submitter (e.g.
;; the TUI input queue) so the wake serializes with user turns on the SAME
;; executor rather than racing them; headless hosts fall back to ask-async.
;; No staleness token: always-resume. Use task$wait to BLOCK for a short wait;
;; task$wakeup to FREE the session during a long wait. Cross-ns fns are
;; requiring-resolved to avoid a cycle (agent.core.agent sits above the task
;; layer).

(def ^:private !submit-turn
  (delay (requiring-resolve 'ai.brainyard.agent.core.agent/submit-turn)))
(def ^:private !get-parent-agent
  (delay (requiring-resolve 'ai.brainyard.agent.core.runtime/get-parent-agent)))

(def ^:private default-max-wakeups
  "Per-session cap on task$wakeup calls — bounds an infinite park->wake->park
   chain. Overridable via a :max-wakeups-per-session config key."
  20)

(defonce ^:private !wakeup-counts (atom {})) ;; session-id -> count

(defn- wakeup-session-key [agent]
  (or (proto/session-id agent) :default))

(defn- max-wakeups [agent]
  (or (config/get-config agent :max-wakeups-per-session) default-max-wakeups))

(defn- wakeup-watch-key [task-id]
  (keyword (str "wakeup-watch-" (clojure.core/name task-id))))

(defn- wakeup-message
  "Resume-turn text. `kind` ∈ {:timer :task}; `status` is the terminal status."
  [kind task-id note status]
  (let [note-str (if (str/blank? note) "(no note given)" note)
        tid      (clojure.core/name task-id)]
    (case kind
      :timer (str "[wakeup] Scheduled timer fired (task " tid "). You parked "
                  "this agent to resume: " note-str
                  ". Continue from where you left off.")
      :task  (str "[wakeup] Watched task " tid " terminated (status: "
                  (clojure.core/name status) "). You parked this agent to "
                  "resume: " note-str ". Continue from where you left off"
                  (when (not= :completed status)
                    "; note the non-success status — handle it before proceeding")
                  "."))))

(defn- register-wakeup-watch!
  "One-shot watch on !tasks: when `task-id` transitions to a terminal status,
   remove the watch and resume `agent` via submit-turn tagged :source :wakeup.
   The watch fires on any !tasks change, so it gates on the specific task-id's
   terminal transition. `kind` ∈ {:timer :task} selects which terminal statuses
   trigger a resume — :timer resumes only on :completed (aborted timer = no
   resume); :task resumes on any terminal status. `!fired` is a CAS guard
   shared with the caller so an already-terminal task (resumed inline) and the
   watch can never both resume."
  [task-id agent note kind !fired]
  (let [wkey      (wakeup-watch-key task-id)
        resume-on (case kind :timer #{:completed} :task terminal-statuses)]
    (add-watch manager/!tasks wkey
               (fn [_ _ old-state new-state]
                 (let [old-st (get-in old-state [task-id :status])
                       new-st (get-in new-state [task-id :status])]
                   (when (and (not= old-st new-st)
                              (terminal-statuses new-st))
                     (remove-watch manager/!tasks wkey)
                     (when (and (resume-on new-st)
                                (compare-and-set! !fired false true))
                       (@!submit-turn
                        agent
                        (wakeup-message kind task-id note new-st)
                        {:source :wakeup :woken-by task-id}))))))))

(defcommand task$wakeup
  "Park this turn and AUTO-RESUME this agent later. Provide EITHER :delay-ms
   (resume after a timer) OR :task-id (resume when an existing background task
   terminates) — not both. Reply with a one-line acknowledgement and STOP — do
   not call more tools; you will be resumed automatically with your previous
   turns in context. FREES the session during a long wait; use task$wait
   instead to BLOCK for a short wait. :task-id mode pairs with deferred-tasking
   — park on a detached task rather than polling it."
  (fn [& {:as args}]
    (let [agent       proto/*current-agent*
          delay-ms    (some-> (:delay-ms args) long)
          task-id-str (:task-id args)
          note        (:note args)
          has-task?   (not (str/blank? task-id-str))]
      (cond
        (nil? agent)
        {:error "task$wakeup requires an active agent"}

        (@!get-parent-agent (:!state agent))
        {:error "task$wakeup is top-level-agent only (sub-agents auto-close before a wakeup could fire)"}

        (and (nil? delay-ms) (not has-task?))
        {:error "provide either delay-ms (timer) or task-id (wait for an existing task)"}

        (and delay-ms has-task?)
        {:error "provide only one of delay-ms or task-id, not both"}

        (and delay-ms (<= delay-ms 0))
        {:error "delay-ms must be positive"}

        (>= (get @!wakeup-counts (wakeup-session-key agent) 0) (max-wakeups agent))
        {:error (str "wakeup budget exhausted for this session (max "
                     (max-wakeups agent) "); use task$wait instead")}

        :else
        (if-let [mgr (manager/get-default-manager)]
          (if has-task?
            ;; WATCH mode — resume when an EXISTING task terminates.
            (let [tid  (keyword task-id-str)
                  task (tp/get-task mgr tid)]
              (if (nil? task)
                {:error (str "Task not found: " task-id-str)}
                (let [!fired (atom false)]
                  (swap! !wakeup-counts update (wakeup-session-key agent) (fnil inc 0))
                  (register-wakeup-watch! tid agent note :task !fired)
                  ;; Close the already-terminal / transition-during-register
                  ;; race: if terminal now and the watch hasn't claimed it,
                  ;; resume inline and tear the (never-firing) watch down.
                  (let [cur (tp/get-task mgr tid)]
                    (when (and (terminal-statuses (:status cur))
                               (compare-and-set! !fired false true))
                      (remove-watch manager/!tasks (wakeup-watch-key tid))
                      (@!submit-turn agent
                                     (wakeup-message :task tid note (:status cur))
                                     {:source :wakeup :woken-by tid})))
                  {:parked true
                   :task-id (clojure.core/name tid)
                   :message (str "PARKED. Auto-resume when task "
                                 (clojure.core/name tid) " terminates. Reply "
                                 "with a one-line acknowledgement and STOP — "
                                 "do not call more tools.")})))
            ;; TIMER mode — create a sleep task and resume on completion.
            (let [{:keys [base-dir]} (config/resolve-agent-dirs)
                  secs   (max 1 (long (Math/round (/ (double delay-ms) 1000.0))))
                  task   (tp/create-task mgr
                                         (str "wakeup: " (if (str/blank? note) "timer" note))
                                         :bash
                                         {:command (str "sleep " secs)
                                          :working-dir base-dir
                                          :timeout-ms (+ (* secs 1000) 60000)}
                                         {:metadata {:wakeup/note note}})
                  tid    (:id task)
                  !fired (atom false)]
              (swap! !wakeup-counts update (wakeup-session-key agent) (fnil inc 0))
              (register-wakeup-watch! tid agent note :timer !fired)
              (tp/start-task mgr tid)
              {:parked true
               :task-id (clojure.core/name tid)
               :message (str "PARKED. Auto-resume scheduled in ~" secs "s (task "
                             (clojure.core/name tid) "). Reply with a one-line "
                             "acknowledgement and STOP — do not call more tools. "
                             "You will be resumed automatically when the timer fires.")}))
          {:error "Task manager not initialized"}))))
  :sync true
  :input-schema  [:map
                  [:delay-ms {:optional true} [:int {:desc "Timer mode: ms to wait before auto-resuming this agent"}]]
                  [:task-id  {:optional true} [:string {:desc "Watch mode: resume when this existing task terminates (e.g. task-1)"}]]
                  [:note {:optional true} [:string {:desc "What to resume doing when woken (surfaced in the wakeup turn)"}]]]
  :output-schema [:map
                  [:parked  {:optional true} [:boolean {:desc "True when the wakeup was scheduled"}]]
                  [:task-id {:optional true} [:string  {:desc "Timer mode: sleep task id (task$cancel to abort). Watch mode: the watched task id."}]]
                  [:message {:optional true} [:string  {:desc "Guidance — acknowledge briefly and stop"}]]
                  [:error   {:optional true} [:string  {:desc "Error message if scheduling failed"}]]])

(def task-commands
  "All task management commands for agent-tools binding. `task$run` is the
   polymorphic launch surface (`:job-type :bash|:tool`); `task$detail`
   covers both metadata and (via `:last-n`) the captured output tail;
   `task$wait` blocks until a task completes or timeout;
   `task$wakeup` parks the turn and auto-resumes the agent — after a timer
   (:delay-ms) or when an existing task terminates (:task-id);
   `task$sweep` GCs on-disk task/coact-scratch/sandbox-cache artifacts."
  [#'task$list #'task$detail #'task$cancel #'task$run #'task$wait #'task$wakeup #'task$sweep])
