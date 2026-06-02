;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.task.protocol
  "Task management protocols, records, and status definitions.

   Status lifecycle: :pending -> :running -> :completed | :failed | :cancelled")

(defrecord Task
           [id              ;; keyword, e.g. :task-1 -- monotonically generated
            name            ;; string, human-readable description
            job-type        ;; :bash | :tool | :cli-client
            job-config      ;; map, job-type-specific
            status          ;; :pending | :running | :completed | :failed | :cancelled
            created-at      ;; epoch ms (System/currentTimeMillis)
            started-at      ;; epoch ms | nil
            completed-at    ;; epoch ms | nil
            result          ;; any -- final result value or {:error "message"}
            output-lines    ;; atom<vector<string>> -- tail cache (last N captured lines)
            max-output-lines ;; int, default 500 -- tail-cache cap; full record lives on disk
            future-ref      ;; java.util.concurrent.Future | nil -- for cancellation
            schedule        ;; {:cron "..."} | nil -- reserved for future cron support
            metadata])      ;; map -- arbitrary user metadata

(defprotocol IJobExecutor
  "Protocol for executing different job types.
   Each implementation handles one job-type."
  (execute-job [this task on-output]
    "Execute the task's job (called from executor pool thread).
     on-output: (fn [line-string]) -- called for each output line, thread-safe.

     Returns ONE of:
       Terminal outcomes (pool thread done, task transitions to terminal):
         {:exit-code N}
         {:error \"...\"}
         {:result <value>}

       Detached outcome (pool thread done, task stays :running until a watcher
       promotes it). Use when the work outlives the pool thread — e.g. a soft
       timeout where the underlying proc / future is left running:
         {:status :detached
          :on-poll   (fn [] ::tp/still-running | <terminal-result-map>)
          :on-cancel (fn [] nil)}

       The manager registers `:on-poll` with a shared daemon watcher that
       calls it every ~250ms; when it returns a terminal map, the task is
       promoted to :completed/:failed accordingly. `:on-cancel` is called
       by `cancel-task` for detached tasks (the pool future has already
       returned, so .cancel on it is a no-op).")
  (cancel-job [this task]
    "Cancel a running job. Returns true if cancellation was initiated.
     For detached tasks, the manager prefers the detach-handler's :on-cancel
     and may not call this at all.")
  (job-type [this]
    "Return the keyword job type this executor handles: :bash | :tool | :cli-client | :clj-sandbox-eval"))

;; Sentinel returned by detach-handler :on-poll to signal \"work not yet done.\"
;; Defined here (not in manager) so executors don't depend on the manager ns.
(def still-running ::still-running)

(defprotocol ITaskManager
  "Central task coordinator. Manages registry, execution, and lifecycle."
  (create-task [this name job-type job-config]
    [this name job-type job-config opts]
    "Create and register a new task (status :pending). Returns Task.
     opts: {:schedule {:cron \"...\"} :metadata {...} :max-output-lines N}
     :max-output-lines defaults to 500 — tail-cache size, not a windowed
     view; full output lives on disk.")
  (start-task [this task-id]
    "Start a :pending task. Submits to executor, sets :running. Returns Task.")
  (cancel-task [this task-id]
    "Cancel a :running task. Returns updated Task or nil.")
  (retry-task [this task-id]
    "Retry a :failed/:cancelled task. Creates new task with same config. Returns new Task.")
  (remove-task [this task-id]
    "Remove task from registry. Cancels if running. Returns nil.")
  (get-task [this task-id]
    "Get task by ID. Returns Task or nil.")
  (list-tasks [this]
    [this filters]
    "List all tasks, optionally filtered by {:status :kw :job-type :kw}.")
  (shutdown [this]
    "Shutdown: cancel all running tasks, shutdown executor service, clear registry."))
