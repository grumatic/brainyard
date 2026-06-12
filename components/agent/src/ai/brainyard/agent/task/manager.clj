;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.task.manager
  "TaskManager record implementing ITaskManager.
   Global atoms: !tasks, !task-counter, !executor-service, !default-manager.
   Ring-buffer on-output callback. ExecutorService submit pattern."
  (:require [ai.brainyard.agent.core.protocol :as proto]
            [ai.brainyard.agent.core.hooks :as hooks]
            [ai.brainyard.agent.task.protocol :as tp]
            [ai.brainyard.agent.task.executor :as executor]
            [ai.brainyard.agent.task.persist :as persist]
            [ai.brainyard.mulog.interface :as mulog])
  (:import [java.util.concurrent Executors ExecutorService ThreadFactory]
           [java.util.concurrent.atomic AtomicLong]))

;; ============================================================================
;; Global State
;; ============================================================================

(defonce !tasks (atom {}))
(defonce !task-counter (atom 0))
(defonce ^:private !default-manager (atom nil))
(defonce ^:private !executor-service (atom nil))

;; Detached-task registry. After execute-job returns {:status :detached ...}
;; the pool thread is free but the work is still running. We park the
;; executor's :on-poll / :on-cancel closures here, keyed by task-id; the
;; shared watcher thread polls each entry every ~300ms and promotes the
;; task to a terminal status when :on-poll returns a result map.
(defonce ^:private !detached-handlers (atom {}))
(defonce ^:private !watcher-future    (atom nil))
(def     ^:private detach-poll-interval-ms 300)

;; Structured per-task progress snapshot (Layer 3). task-id -> a small map
;; {:iteration :tools-completed :last-tool :last-tool-result :last-reasoning}
;; maintained by the subagent-progress hooks below and surfaced by
;; task$detail as :progress. Only real running tasks get an entry (the
;; pre-adoption sentinel is never in !tasks); finalize-task! evicts on terminal
;; transition, so it can't leak.
(defonce ^:private !task-progress (atom {}))

(declare create-task-manager start-detach-watcher!)

(defn get-default-manager
  "Return the default task manager.

   Lazily auto-initializes via `create-task-manager` on first access
   so callers (TUI core, daemon, sandbox tools) don't need their own
   `(when-not @!default-manager (set-default-manager! (create-task-manager)))`
   boilerplate.  Tests and apps that need a custom manager should
   call `set-default-manager!` BEFORE the first `get-default-manager`
   — that path still wins (auto-init only runs when the atom is
   nil).

   Lock-free CAS: concurrent first-callers don't double-initialize;
   one wins and the others see the same instance."
  []
  (or @!default-manager
      (let [tm (create-task-manager)]
        (compare-and-set! !default-manager nil tm)
        @!default-manager)))

(defn set-default-manager!
  "Override the default task manager.  Used by tests and by callers
   that need a custom pool size or executor mix.  Pass `nil` to clear
   (the next `get-default-manager` will auto-init a fresh one)."
  [tm]
  (reset! !default-manager tm))

(defn peek-default-manager
  "Return the current default manager WITHOUT auto-initializing.
   Use in shutdown paths where we don't want to spin up a fresh
   manager just to immediately tear it down."
  []
  @!default-manager)

(defn set-sync-mode!
  "Flip a sync (formerly 'foreground') waiter's flag. Only `true → false` is
   honored — the reverse (background → sync) is intentionally not supported,
   since a caller that has already returned cannot be re-attached. Returns
   true when the flag was flipped, false otherwise (task missing, no flag,
   or mode? not false)."
  [mgr task-id mode?]
  (boolean
   (when (false? mode?)
     (when-let [a (some-> (tp/get-task mgr task-id) :metadata :!sync-waiter?)]
       (reset! a false)
       true))))

(defn get-sync-tasks
  "Return running tasks that still have an active sync waiter — i.e.
   `:status :running` and `:metadata :!sync-waiter?` deref's to true. Use
   to discover detach candidates from a TUI/supervisor thread before calling
   `set-sync-mode!`."
  [mgr]
  (->> (tp/list-tasks mgr {:status :running})
       (filterv #(some-> % :metadata :!sync-waiter? deref true?))))

(defn detached?
  "True when `task-id` is currently in the detached registry — its pool
   thread returned `:detached` and the work is still running in the
   background, awaiting promotion by the watcher. Cleared on finalize.
   Detach is an internal registry concept (not per-task metadata); this is
   the observable for 'a `:running` task whose work outlived the pool thread'."
  [task-id]
  (contains? @!detached-handlers task-id))

(defn set-display-mode!
  "Flip a task's display-mode flag between `:foreground` and `:background`.
   The TUI per-task block is created when `:foreground`, disposed when
   `:background`. The task itself keeps running either way — display-mode
   is purely a visibility concern.

   Returns the updated task, or nil if the task is missing."
  [mgr task-id mode]
  {:pre [(#{:foreground :background} mode)]}
  (when-let [_ (tp/get-task mgr task-id)]
    (let [updated (swap! !tasks update task-id
                         (fn [t]
                           (when t
                             (assoc-in t [:metadata :display-mode] mode))))]
      (when-let [t (get updated task-id)]
        (hooks/fire! :task/display-mode-changed
                     {:task t :display-mode mode})
        t))))

;; Task lifecycle events fire through ai.brainyard.agent.core.hooks:
;;   :task/created    {:task task}
;;   :task/completed  {:task task}
;;
;; Apps register handlers via hooks/register-hook! with :source
;; (e.g. :tui) so they can be torn down in bulk via unregister-source!.

;; ============================================================================
;; Ring Buffer Output Callback
;; ============================================================================

(defn- push-ring!
  "Conj `line` onto a tail-cache vector, evicting the oldest lines once the
   vector exceeds `max-lines`. Pure — for use inside a `swap!`."
  [lines max-lines line]
  (let [lines (conj lines line)]
    (if (> (count lines) max-lines)
      (subvec lines (- (count lines) max-lines))
      lines)))

(defn- make-on-output
  "Create a thread-safe on-output callback with ring-buffer behavior.
   Also fans out each line to the per-task disk appender (best-effort —
   disk failures are logged inside persist/append-line! and never affect
   the in-memory ring buffer or executor future)."
  [output-lines-atom max-lines task-id]
  (fn [line]
    (swap! output-lines-atom push-ring! max-lines line)
    (persist/append-line! task-id line)))

(defn append-task-output!
  "Append `line` to a running task's output via the same ring-buffer + disk
   path as the executor's on-output callback, given only a task-id (no
   closure). For out-of-band emitters — e.g. the subagent-progress hook below —
   that resolve a task-id from `proto/*current-task*` but don't hold the task's
   on-output fn. No-op when the task is unknown or already terminal (so the
   pre-adoption `:inline-tool-eval` sentinel and finished tasks are ignored)."
  [task-id line]
  (when-let [task (get @!tasks task-id)]
    (when (= :running (:status task))
      (swap! (:output-lines task) push-ring! (:max-output-lines task) line)
      (persist/append-line! task-id line))))

;; ============================================================================
;; Task Factory Helper
;; ============================================================================

(defn- make-task
  "Create a new Task record with auto-generated ID.

   `:metadata :display-mode` defaults to `:foreground` — the TUI surfaces a
   per-task block while this is `:foreground`. Callers can supply
   `:display-mode :background` (in `:metadata`) to suppress the block from
   creation, or `:auto-background-ms N` to schedule an auto-flip after N ms.
   The executor's `:detached` return does NOT flip display-mode — that is
   `await-task`'s job (on `:on-timeout :detach`, the sync → async hand-off).

   `:max-output-lines` defaults to 500 — the in-memory atom is a tail
   cache, not a windowed view of the full output. The complete record
   lives on disk via `persist/output.log`; the cache exists so callers
   can read the most recent N lines without hitting the BufferedWriter's
   unflushed window. Eval-entry / task$detail projections that detect
   `disk-line-count > cache-count` surface a marker pointing the LLM
   at the log file."
  [task-name job-type job-config {:keys [schedule metadata max-output-lines]
                                  :or {max-output-lines 500}}]
  (let [id (keyword (str "task-" (swap! !task-counter inc)))
        meta (merge {:display-mode :foreground}
                    (or metadata {}))]
    (tp/->Task id
               task-name
               job-type
               job-config
               :pending
               (System/currentTimeMillis)
               nil    ;; started-at
               nil    ;; completed-at
               nil    ;; result
               (atom [])  ;; output-lines
               max-output-lines
               nil    ;; future-ref
               schedule
               meta)))

;; ============================================================================
;; Terminal Transition + Detach Watcher
;; ============================================================================

(defn- result->status
  "Map an executor's terminal result map to a task :status keyword.
   Mirrors the rule the pool-thread closure has always used: anything with
   :error → :failed; otherwise :completed. :result and :exit-code 0 are
   both :completed."
  [result]
  (if (:error result) :failed :completed))

(defn- finalize-task!
  "Promote a task to a terminal status. Single source of truth for the
   completed/failed transition — called both by the pool thread (sync path)
   and the detach watcher (async path). Idempotent: if the task is already
   terminal, this is a no-op (CAS-free: no observable state change to race
   on).

   Side effects:
     - swaps !tasks
     - fires :task/completed
     - closes the disk appender
     - removes any detach-handler entry"
  [task-id status result]
  (let [now (System/currentTimeMillis)
        prev (get @!tasks task-id)]
    (when (and prev (not (#{:completed :failed :cancelled} (:status prev))))
      (swap! !tasks update task-id
             (fn [t]
               (when t
                 (assoc t
                        :status status
                        :completed-at now
                        :result result))))
      (swap! !detached-handlers dissoc task-id)
      (swap! !task-progress dissoc task-id)
      (let [updated (get @!tasks task-id)]
        (try (persist/close-appender! task-id updated)
             (catch Exception e
               (mulog/warn ::close-appender-failed
                           :task-id task-id :error (ex-message e))))
        (hooks/fire! :task/completed {:task updated})))))

(defn- register-detached!
  "Park an executor's :on-poll / :on-cancel closures for the shared watcher.

   The pool thread *always* returns `:detached` immediately in the new
   pure-async executor contract — this is an implementation detail, not a
   UI signal. Display-mode is NOT flipped here; it stays `:foreground` so
   the per-task block remains visible. `await-task` is the only place that
   transitions display-mode (`:foreground → :background` on
   `:on-timeout :detach` — i.e. the sync → async hand-off when the LLM
   waiter actually leaves)."
  [task-id {:keys [on-poll on-cancel]}]
  (swap! !detached-handlers assoc task-id
         {:on-poll on-poll :on-cancel on-cancel})
  (mulog/info ::task-detached :task-id task-id))

(defn adopt-detached!
  "Create a task in :running state wrapping a pre-existing eval future.
   Used by the deferred-tasking fast-eval path: when inline eval exceeds
   the fast timeout, the already-running future is adopted into a tracked
   task without restarting work.

   make-on-poll: (fn [on-output] -> poll-fn) — builder that receives the
                 task's on-output callback and returns the :on-poll closure.
   on-cancel:    (fn [] ...) — cancel closure, registered as-is.
   opts may include :started-at (epoch ms) to backdate the task's start
   time to when the eval actually began (before the fast-eval window)."
  [task-name job-type job-config opts make-on-poll on-cancel]
  (let [task    (make-task task-name job-type job-config opts)
        task-id (:id task)
        started (or (:started-at opts) (System/currentTimeMillis))
        on-output (make-on-output (:output-lines task)
                                  (:max-output-lines task) task-id)]
    ;; Two-step insert so the TUI's !tasks watcher sees :pending → :running
    ;; and creates the per-task live block (a single :running insert has
    ;; nil old-status and the watch guard skips it).
    (swap! !tasks assoc task-id (assoc task :started-at started))
    (swap! !tasks assoc-in [task-id :status] :running)
    (persist/open-appender! nil (get @!tasks task-id))
    (register-detached! task-id
                        {:on-poll   (make-on-poll on-output)
                         :on-cancel on-cancel})
    (hooks/fire! :task/created {:task (get @!tasks task-id)})
    (get @!tasks task-id)))

(defn- poll-detached-once!
  "Walk the detached-handlers snapshot, invoke each :on-poll, and finalize
   any task whose handler returned a terminal map. Poll failures finalize
   the task as :failed so a broken executor closure can't park a task
   forever."
  []
  (doseq [[task-id {:keys [on-poll]}] @!detached-handlers]
    (try
      (let [r (on-poll)]
        (when-not (= r tp/still-running)
          (finalize-task! task-id (result->status r) r)))
      (catch Throwable t
        (mulog/error ::detach-poll-failed
                     :task-id task-id :exception t)
        (finalize-task! task-id :failed
                        {:error (str "detach poll failed: " (ex-message t))})))))

(defn- start-detach-watcher!
  "Idempotently start the shared daemon thread that polls detached tasks.
   One thread for the whole JVM (the registry is also global), CAS-guarded
   so concurrent `create-task-manager` calls don't race-start two."
  []
  (when (nil? @!watcher-future)
    (let [fut (future
                (try
                  (loop []
                    (when (seq @!detached-handlers)
                      (poll-detached-once!))
                    (Thread/sleep (long detach-poll-interval-ms))
                    (recur))
                  (catch InterruptedException _
                    (mulog/info ::detach-watcher-interrupted))))]
      (when-not (compare-and-set! !watcher-future nil fut)
        ;; Lost the race; cancel the future we just made.
        (future-cancel fut)))))

(defn- stop-detach-watcher!
  "Cancel the watcher future (if any) and clear the handle. Called from
   shutdown so a re-init can start a fresh watcher."
  []
  (when-let [f @!watcher-future]
    (future-cancel f)
    (reset! !watcher-future nil)))

;; ============================================================================
;; TaskManager Record
;; ============================================================================

(defrecord TaskManager [executors]
  tp/ITaskManager

  (create-task [this task-name job-type job-config]
    (tp/create-task this task-name job-type job-config {}))

  (create-task [_this task-name job-type job-config opts]
    (let [task (make-task task-name job-type job-config opts)]
      (swap! !tasks assoc (:id task) task)
      (hooks/fire! :task/created {:task task})
      task))

  (start-task [this task-id]
    (let [task (get @!tasks task-id)]
      (when (and task (= :pending (:status task)))
        (let [executor (get executors (:job-type task))
              _ (when-not executor
                  (throw (ex-info (str "No executor for job-type: " (:job-type task))
                                  {:job-type (:job-type task)})))
              _ (persist/open-appender! nil task)
              on-output (make-on-output (:output-lines task) (:max-output-lines task) task-id)
              es @!executor-service
              fut (.submit ^ExecutorService es
                           ^Callable
                           (fn []
                             (let [started-at (System/currentTimeMillis)]
                               (try
                                 (let [result (tp/execute-job executor task on-output)]
                                   (if (= :detached (:status result))
                                     ;; Pool thread done; work outlives it. Register handlers
                                     ;; and DO NOT finalize — the watcher will when :on-poll
                                     ;; eventually returns a terminal map.
                                     (do (register-detached! task-id result)
                                         (mulog/log ::task-execution
                                                    :task-id task-id
                                                    :task-name (:name task)
                                                    :job-type (:job-type task)
                                                    :job-config (:job-config task)
                                                    :status :detached
                                                    :detached-after-ms (- (System/currentTimeMillis) started-at))
                                         result)
                                     ;; Terminal outcome — single-source-of-truth finalize.
                                     (let [status (result->status result)
                                           completed-at (System/currentTimeMillis)]
                                       (mulog/log ::task-execution
                                                  :task-id task-id
                                                  :task-name (:name task)
                                                  :job-type (:job-type task)
                                                  :job-config (:job-config task)
                                                  :status status
                                                  :result result
                                                  :output-lines @(:output-lines task)
                                                  :duration-ms (- completed-at started-at))
                                       (finalize-task! task-id status result)
                                       result)))
                                 (catch InterruptedException _
                                   (finalize-task! task-id :cancelled {:error "cancelled"})
                                   {:error "cancelled"})
                                 (catch Exception e
                                   (mulog/error ::task-execution-failed :task-id task-id :exception e)
                                   (finalize-task! task-id :failed {:error (ex-message e)})
                                   {:error (ex-message e)})))))]
          (swap! !tasks update task-id
                 (fn [t]
                   (when t
                     (assoc t
                            :status :running
                            :started-at (System/currentTimeMillis)
                            :future-ref fut))))
          ;; Auto-background-timeout: per-task opt-in. After N ms, if the task
          ;; is still :running and :display-mode :foreground, flip display to
          ;; :background. The TUI watches :task/display-mode-changed to drop
          ;; the per-task block. Skipped silently when not set on metadata.
          (when-let [ms (get-in task [:metadata :auto-background-ms])]
            (future
              (try
                (Thread/sleep (long ms))
                (let [t (get @!tasks task-id)]
                  (when (and t
                             (= :running (:status t))
                             (= :foreground (get-in t [:metadata :display-mode])))
                    (set-display-mode! this task-id :background)))
                (catch InterruptedException _ nil)
                (catch Throwable e
                  (mulog/warn ::auto-background-failed
                              :task-id task-id :error (ex-message e))))))
          (get @!tasks task-id)))))

  (cancel-task [_this task-id]
    (let [task (get @!tasks task-id)]
      (when (and task (= :running (:status task)))
        (if-let [{:keys [on-cancel]} (get @!detached-handlers task-id)]
          ;; Detached path: pool future already returned, so .cancel on it is
          ;; a no-op. Drive the executor's own cancel closure (kills the
          ;; underlying proc / future), then finalize via the shared helper.
          (do (try (on-cancel)
                   (catch Throwable t
                     (mulog/warn ::detach-cancel-failed
                                 :task-id task-id :error (ex-message t))))
              (finalize-task! task-id :cancelled {:error "cancelled"}))
          ;; Synchronous path: interrupt the pool thread and let it land in
          ;; the InterruptedException branch of start-task, which calls
          ;; finalize-task!. We also call cancel-job for executors that need
          ;; out-of-band teardown (e.g. ProcessBuilder destroy in BashJobExecutor).
          (do (when-let [fut (:future-ref task)]
                (.cancel ^java.util.concurrent.Future fut true))
              (let [executor (get executors (:job-type task))]
                (when executor (tp/cancel-job executor task)))
              ;; If the pool thread's InterruptedException handler already
              ;; finalized, this is a no-op; otherwise it's the fallback.
              (finalize-task! task-id :cancelled {:error "cancelled"})))
        (get @!tasks task-id))))

  (retry-task [this task-id]
    (let [task (get @!tasks task-id)]
      (when (and task (#{:failed :cancelled} (:status task)))
        (let [new-task (tp/create-task this (:name task) (:job-type task) (:job-config task)
                                       {:metadata (assoc (:metadata task) :retried-from task-id)
                                        :max-output-lines (:max-output-lines task)
                                        :schedule (:schedule task)})]
          (tp/start-task this (:id new-task))
          (get @!tasks (:id new-task))))))

  (remove-task [this task-id]
    (let [task (get @!tasks task-id)]
      (when task
        (when (= :running (:status task))
          (tp/cancel-task this task-id))
        (swap! !tasks dissoc task-id)
        nil)))

  (get-task [_this task-id]
    (get @!tasks task-id))

  (list-tasks [_this]
    (vals @!tasks))

  (list-tasks [_this filters]
    (let [tasks (vals @!tasks)]
      (cond->> tasks
        (:status filters)   (filter #(= (:status filters) (:status %)))
        (:job-type filters) (filter #(= (:job-type filters) (:job-type %))))))

  (shutdown [this]
    (doseq [[task-id task] @!tasks]
      (when (= :running (:status task))
        (tp/cancel-task this task-id)))
    (stop-detach-watcher!)
    (reset! !detached-handlers {})
    (reset! !task-progress {})
    (when-let [es @!executor-service]
      (.shutdownNow ^ExecutorService es)
      (reset! !executor-service nil))
    (reset! !tasks {})
    (reset! !task-counter 0)))

(defn remove-task-and-artifacts!
  "Remove the in-memory registry entry AND the on-disk
   `<project>/.brainyard/tasks/<task-id>/` directory. Caller opt-in:
   `tp/remove-task` alone leaves disk artifacts intact for post-mortem; this
   helper is for callers (CLI, GC, tests) that want the row truly gone.

   Cancels a running task first (mirroring `remove-task`). Disk deletion is
   best-effort — failure is logged inside `persist/delete-task-dir!` and never
   throws. Returns `true` when either side removed something, `false` when
   the task was unknown to begin with."
  [mgr task-id]
  (let [had? (some? (tp/get-task mgr task-id))]
    (when had? (tp/remove-task mgr task-id))
    (persist/delete-task-dir! nil task-id)
    had?))

;; ============================================================================
;; Subagent Progress Mirror (Layer 2)
;; ============================================================================
;;
;; A subagent invoked as a tool runs its OWN bt loop on the future thread that
;; `call-tool-with-fast-eval` wrapped in `(binding [proto/*current-task* …])`,
;; and that binding is conveyed into the loop's pmap tool dispatch. So while a
;; detached subagent runs, its per-iteration and per-tool events fire with
;; `proto/*current-task*` bound to the adopted task — whereas the PARENT's
;; iterations fire on the bare BT thread (no binding) and stay out. We mirror
;; those events into the task's output as compact one-liners, so a polling LLM
;; sees a meaningful growing trace, not just the Layer-1 liveness heartbeat.
;;
;; Observer-only (registered on non-gated events; return value ignored) and
;; fail-open (hook system's default :on-error :log). No-op outside a task.

(defn- truncate
  [^String s n]
  (if (> (count s) n) (str (subs s 0 n) "…") s))

(defn- summarize-tool-result
  "One short phrase for a tool result line — error text (truncated) or 'ok'.
   Never dumps the full result map (which can be large)."
  [result]
  (cond
    (not (map? result))      "ok"
    (:error result)          (str "error: " (truncate (str (:error result)) 80))
    (:error-message result)  (str "error: " (truncate (str (:error-message result)) 80))
    :else                    "ok"))

(defn- emit-progress!
  "Append `line` to the current task's output when running inside one."
  [line]
  (when-let [tid (proto/current-task-id)]
    (append-task-output! tid line)))

(defn- update-progress!
  "Apply `f` to the current task's structured-progress map, when running inside
   a real (registered) task. Guarded on `!tasks` membership so the pre-adoption
   `:inline-tool-eval` sentinel never creates an orphan entry."
  [f]
  (when-let [tid (proto/current-task-id)]
    (when (get @!tasks tid)
      (swap! !task-progress update tid (fnil f {})))))

(defn task-progress
  "The structured-progress snapshot for `task-id`, or nil. Surfaced by
   task$detail as :progress. Cleared when the task reaches a terminal status."
  [task-id]
  (get @!task-progress task-id))

(defn- on-iteration-pre
  [{:keys [iteration max-iterations]}]
  (emit-progress! (str "[iter " iteration "/" max-iterations "] thinking…"))
  (update-progress! #(assoc % :iteration iteration)))

(defn- on-iteration-post
  [{:keys [last-reasoning]}]
  ;; Latest per-iteration reasoning (the agent's "Think:" text) — a "what is it
  ;; doing and why" snapshot. Stored structured (truncated) so an LLM can read
  ;; it without slurping the log tail.
  (when (string? last-reasoning)
    (update-progress! #(assoc % :last-reasoning (truncate last-reasoning 200)))))

(defn- on-tool-use-post
  [{:keys [tool-name result]}]
  (let [summary (summarize-tool-result result)]
    (emit-progress! (str "  tool " (name tool-name) " → " summary))
    (update-progress! #(-> %
                           (assoc :last-tool (name tool-name) :last-tool-result summary)
                           (update :tools-completed (fnil inc 0))))))

(defonce ^:private !progress-hooks-installed (atom false))

(defn- install-progress-hooks!
  "Idempotently register the built-in subagent-progress mirror. Source
   `:task-progress` so it can be torn down in bulk if ever needed. Called from
   `create-task-manager` (the task subsystem's init point)."
  []
  (when (compare-and-set! !progress-hooks-installed false true)
    (hooks/register-hook! :agent.iteration/pre ::subagent-progress-iter
                          on-iteration-pre :source :task-progress)
    (hooks/register-hook! :agent.iteration/post ::subagent-progress-iter-post
                          on-iteration-post :source :task-progress)
    (hooks/register-hook! :agent.tool-use/post ::subagent-progress-tool
                          on-tool-use-post :source :task-progress)))

;; ============================================================================
;; Factory
;; ============================================================================

(defn- daemon-thread-factory
  "Build a ThreadFactory whose threads are marked daemon so the JVM
   can exit cleanly without anyone explicitly calling `shutdown` on
   the pool.  Names threads `agent-task-N` for easy `jstack` reading."
  ^ThreadFactory []
  (let [counter (AtomicLong. 0)]
    (reify ThreadFactory
      (newThread [_ r]
        (doto (Thread. ^Runnable r
                       (str "agent-task-"
                            (.getAndIncrement counter)))
          (.setDaemon true))))))

(defn create-task-manager
  "Create a TaskManager with all standard executors.
   Options: :pool-size (default 4)

   The pool's worker threads are daemon threads, so the JVM exits
   cleanly when the user's main code returns even if a background
   task is still running.  Note daemon-ness only governs whether the
   JVM *waits* for these threads — it does NOT kill subprocesses a
   task spawned via ProcessBuilder (those are independent OS processes
   that outlive the JVM).  An app's exit path MUST call `tp/shutdown`
   (e.g. via `agent/task-shutdown` from `stop!` and the JVM shutdown
   hook) so each detached task's :on-cancel destroys its proc tree;
   otherwise `npm run dev`-style tasks orphan."
  [& {:keys [pool-size] :or {pool-size 4}}]
  (when-not @!executor-service
    (reset! !executor-service
            (Executors/newFixedThreadPool (int pool-size)
                                          (daemon-thread-factory))))
  ;; Ratchet the counter past any on-disk task dirs so freshly-issued IDs
  ;; don't overwrite artifacts from a prior session (JVM restart) or from
  ;; this JVM's prior `shutdown` (which resets the counter to 0). `max` —
  ;; never decrease a counter that's already ahead of disk.
  (swap! !task-counter max (persist/max-existing-task-id nil))
  (start-detach-watcher!)
  (install-progress-hooks!)
  (->TaskManager {:bash             (executor/->BashJobExecutor)
                  :tool             (executor/->ToolJobExecutor)
                  :cli-client       (executor/->CliClientJobExecutor)
                  :clj-sandbox-eval (executor/->ClojureSandboxJobExecutor)
                  :clj-nrepl-eval   (executor/->NreplEvalJobExecutor)}))
