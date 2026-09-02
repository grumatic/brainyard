;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.task.executor
  "Job executors for task management system.
   BashJobExecutor — shell commands via ProcessBuilder
   ToolJobExecutor — invoke registered tools from !tool-defs
   CliClientJobExecutor — drive CliClient processes
   ClojureSandboxJobExecutor — evaluate Clojure code in a clj-sandbox SCI ctx
   NreplEvalJobExecutor — evaluate Clojure code in the LIVE runtime via clj-nrepl
   A2ATaskJobExecutor — track a REMOTE task on an A2A peer by polling tasks/get"
  (:require [ai.brainyard.a2a.interface :as a2a]
            [ai.brainyard.a2a-client.interface :as a2a-client]
            [ai.brainyard.agent.task.protocol :as tp]
            [ai.brainyard.agent.core.proc :as proc]
            [ai.brainyard.agent.core.protocol :as proto]
            [ai.brainyard.agent.core.tool :as tool]
            [ai.brainyard.agent.stdio.client :as stdio-client]
            [ai.brainyard.clj-sandbox.interface :as clj-sandbox]
            [ai.brainyard.clj-nrepl.interface :as clj-nrepl]
            [ai.brainyard.effect.interface :as fx]
            [ai.brainyard.mulog.interface :as mulog]
            [clojure.string :as str])
  (:import [java.io InputStreamReader]
           [java.lang Process ProcessHandle]
           [java.util.concurrent CancellationException ExecutionException]))

(defn destroy-process-tree!
  "Force-kill the proc and every descendant. Snapshot the descendant list
   FIRST: once the parent dies its children are reparented to init and
   `Process.descendants()` would no longer reach them. Java 9+ API; the
   project baseline is JDK 11+.

   Handles the script-wrapper chain (sh -c 'bash /tmp/foo.sh' → bash → sleep)
   that destroyForcibly on the parent alone leaves orphaned."
  [^Process proc]
  (when (and proc (.isAlive proc))
    (let [;; Realize the descendants snapshot before killing the parent.
          descendants (vec (iterator-seq (.iterator (.descendants proc))))]
      (.destroyForcibly proc)
      (doseq [^ProcessHandle d descendants]
        (try (.destroyForcibly d) (catch Exception _))))))

;; ============================================================================
;; BashJobExecutor
;; ============================================================================

(defrecord BashJobExecutor []
  tp/IJobExecutor
  (execute-job [_ task on-output]
    (let [{:keys [command working-dir env]} (:job-config task)
          ;; Own session + disarmed credential prompts (see agent.core.proc).
          ;; Closing stdin below is not enough on its own: a `:bash` task that
          ;; hits a git password prompt would otherwise sit at :running until
          ;; its timeout with :output empty, while the prompt itself appeared
          ;; on the user's terminal.
          pb (proc/shell-pb command)]
      (when working-dir (.directory pb (java.io.File. ^String working-dir)))
      ;; AFTER hardening, so an explicit job env still wins.
      (when env
        (let [env-map (.environment pb)]
          (doseq [[k v] env] (.put env-map (str k) (str v)))))
      ;; Pure-async contract: start the proc, install a stdout drain in a
      ;; daemon thread, hand the :on-poll / :on-cancel closures to the
      ;; manager, and return :detached *immediately* — the pool thread
      ;; never blocks on the proc. The shared watcher polls :on-poll every
      ;; ~300 ms; an active `await-task` polls @!tasks every 100 ms.
      ;; The LLM-facing auto-background deadline lives in await-task, not here.
      (let [^Process proc (.start pb)
            ;; Close stdin so CLIs that read stdin (e.g. apfel) don't hang.
            _ (.close (.getOutputStream proc))
            ^java.io.InputStream stream (.getInputStream proc)
            ^StringBuilder sb (StringBuilder.)
            flush-lines! (fn []
                           (loop []
                             (let [^String s (.toString sb)
                                   nl (.indexOf s (int \newline))]
                               (when (>= nl 0)
                                 (on-output (subs s 0 nl))
                                 (.delete sb 0 (inc nl))
                                 (recur)))))
            reader-future
            (future
              (let [^InputStreamReader reader (InputStreamReader. stream)
                    buf (char-array 1024)]
                (try
                  (loop []
                    (let [n (.read reader buf)]
                      (when (pos? n)
                        (.append sb buf 0 ^int n)
                        (flush-lines!)
                        (recur))))
                  (catch java.io.IOException _ nil)
                  ;; Catch Throwable so MissingReflectionRegistrationError
                  ;; (java.lang.Error, not Exception) under native-image
                  ;; doesn't die the future silently and surface downstream
                  ;; as an opaque "detach poll failed".
                  (catch Throwable t
                    (mulog/error ::reader-future-failed
                                 :task-id (:id task)
                                 :exception t)
                    nil))))
            finalize-result
            (fn []
              ;; Wait briefly for the reader thread to drain remaining output.
              (deref reader-future 5000 nil)
              (let [^String remainder (.toString sb)]
                (when (pos? (count remainder))
                  (on-output remainder)))
              (let [exit-code (.exitValue proc)]
                (if (zero? exit-code)
                  {:exit-code 0}
                  {:error (str "Exit code: " exit-code) :exit-code exit-code})))]
        (mulog/info ::bash-detached :task-id (:id task))
        ;; Effect path (design Phase 3): the Task IS the completion signal, so
        ;; `finalize-task!` runs the instant the process exits instead of up to
        ;; 300ms later when the shared watcher next looked. `.waitFor` blocks,
        ;; which is exactly what `m/blk` is for — and it replaces polling
        ;; `.isAlive` with the OS telling us.
        ;;
        ;; `:on-cancel` is NOT redundant with cancelling the Task. Measured:
        ;; cancelling an `m/via` interrupts the waiting thread but leaves the
        ;; process running. The effect cancel retires the waiter; only
        ;; `destroy-process-tree!` kills the work. The manager runs both.
        {:status    :detached
         :task      (fx/task-of (fn [] (.waitFor ^Process proc) (finalize-result)))
         :on-cancel (fn []
                      (destroy-process-tree! proc)
                      (future-cancel reader-future))})))

  (cancel-job [_ _task] true)
  (job-type [_] :bash))

;; ============================================================================
;; ToolJobExecutor
;; ============================================================================

(defrecord ToolJobExecutor []
  tp/IJobExecutor
  (execute-job [_ task on-output]
    (let [{:keys [tool-id tool-args agent timeout-ms]
           :or {timeout-ms 120000}} (:job-config task)]
      (if-not (get (tool/get-tool-defs) tool-id)
        (let [msg (str "Tool not found: " (name tool-id))]
          (on-output msg)
          {:error msg})
        (do
          (on-output (str "Invoking: " (name tool-id) " " (pr-str tool-args)))
          (let [start-ms (System/currentTimeMillis)
                call-tool-fn (requiring-resolve 'ai.brainyard.agent.core.tool/call-tool)
                ;; Wrap the entire call (including defagent await) in a future so the
                ;; configured :timeout-ms bounds it. future-cancel sends an interrupt;
                ;; tools that don't respect Thread interrupts may continue in the
                ;; background until they return on their own.
                tool-future (future
                              (binding [proto/*current-task* (atom (:id task))]
                                (let [raw (call-tool-fn tool-id tool-args :agent agent)]
                                  (if (instance? clojure.lang.Agent raw)
                                    (do (await raw)
                                        (or (:output @raw)
                                            {:error-message "Agent returned nil output"}))
                                    raw))))]
            (try
              (let [r (deref tool-future timeout-ms ::timeout)]
                (if (= r ::timeout)
                  (do (future-cancel tool-future)
                      (mulog/info ::tool-timed-out
                                  :task-id (:id task)
                                  :tool-id tool-id
                                  :timeout-ms timeout-ms)
                      (on-output (str "Timed out after " timeout-ms "ms"))
                      {:error (str "Tool timed out after " timeout-ms "ms")
                       :timed-out true
                       :timeout-ms timeout-ms})
                  (let [elapsed (- (System/currentTimeMillis) start-ms)]
                    (on-output (str "Completed in " elapsed "ms"))
                    ;; Bound the result print: a pathological tool payload
                    ;; (deeply nested / very large) must not blow the stack or
                    ;; flood the log just to surface a liveness line.
                    (on-output (str "Result: "
                                    (binding [*print-level* 12 *print-length* 200]
                                      (pr-str r))))
                    {:result r})))
              ;; Catch Throwable, not Exception: a runaway tool can throw an
              ;; Error (StackOverflowError, MissingReflectionRegistrationError
              ;; under native-image). An uncaught Error here escapes the task
              ;; future as an ExecutionException and crashes the agent loop;
              ;; contain it as a normal {:error …} result instead.
              (catch Throwable e
                (future-cancel tool-future)
                (let [elapsed (- (System/currentTimeMillis) start-ms)
                      msg (or (ex-message e) (.. e getClass getName))]
                  (on-output (str "Failed after " elapsed "ms: " msg))
                  {:error msg}))))))))

  (cancel-job [_ _task] false)
  (job-type [_] :tool))

;; ============================================================================
;; FnJobExecutor
;; ============================================================================
;;
;; Runs an in-process thunk supplied by the caller — the generic seam for
;; internal background work that is neither a shell command nor a registered
;; tool (skill distillation / refinement, see
;; `agent.common.skill-distill.background`). Callers get the task manager's
;; bounded pool, cancellation and on-disk `output.log` instead of hand-rolling
;; a `future` per job.
;;
;; Shape mirrors ToolJobExecutor: the thunk runs in an inner future so
;; `:timeout-ms` can bound it, and the pool thread is held for the job's
;; duration — which is what makes the fixed pool an actual concurrency bound.

(defrecord FnJobExecutor []
  tp/IJobExecutor
  (execute-job [_ task on-output]
    (let [{:keys [f label timeout-ms] :or {timeout-ms 300000}} (:job-config task)]
      (if-not (fn? f)
        (let [msg "job-config :f must be a function"]
          (on-output msg)
          {:error msg})
        (let [start-ms (System/currentTimeMillis)
              job-future (future
                           (binding [proto/*current-task* (atom (:id task))]
                             (f)))]
          (on-output (str "Running: " (or label (name (:id task)))))
          (try
            (let [r (deref job-future timeout-ms ::timeout)]
              (if (= r ::timeout)
                (do (future-cancel job-future)
                    (mulog/info ::fn-job-timed-out
                                :task-id (:id task) :label label :timeout-ms timeout-ms)
                    (on-output (str "Timed out after " timeout-ms "ms"))
                    {:error (str "Job timed out after " timeout-ms "ms")
                     :timed-out true
                     :timeout-ms timeout-ms})
                (do (on-output (str "Completed in " (- (System/currentTimeMillis) start-ms) "ms"))
                    (on-output (str "Result: "
                                    (binding [*print-level* 12 *print-length* 200]
                                      (pr-str r))))
                    {:result r})))
            ;; Throwable, not Exception — same reasoning as ToolJobExecutor: an
            ;; Error escaping here would surface as an ExecutionException.
            (catch Throwable e
              (future-cancel job-future)
              ;; deref re-throws the thunk's exception wrapped in an
              ;; ExecutionException, whose message is the stringified cause
              ;; ("clojure.lang.ExceptionInfo: boom {}"). Unwrap so output.log
              ;; and the task result carry the real message.
              (let [root (if (instance? ExecutionException e) (or (ex-cause e) e) e)
                    msg  (or (ex-message root) (.. root getClass getName))]
                (on-output (str "Failed after " (- (System/currentTimeMillis) start-ms) "ms: " msg))
                {:error msg})))))))

  (cancel-job [_ _task] false)
  (job-type [_] :fn))

;; ============================================================================
;; A2ATaskJobExecutor
;;
;; Adopts a REMOTE A2A task into the local task manager, so `task$wait`,
;; `task$cancel`, `task$detail`, the iteration hold and the TUI task block all
;; work on it with no new machinery. A2A's `returnImmediately` + `tasks/get`
;; polling is the same shape as the manager's detach handler.
;;
;; job-config: {:peer-name "b" :remote-task-id "t-1" :poll-interval-ms 2000}
;; ============================================================================

(def ^:const DEFAULT_A2A_POLL_MS
  "How often to actually hit the peer with `tasks/get`.

   Issuing four HTTP requests per second per task at a third party is abusive
   and would get us rate-limited by any sane server, so this is the interval
   `fx/poll-until` sleeps between attempts."
  2000)

(defrecord A2ATaskJobExecutor []
  tp/IJobExecutor
  (execute-job [_ task on-output]
    (let [{:keys [peer-name remote-task-id poll-interval-ms]} (:job-config task)
          interval (or poll-interval-ms DEFAULT_A2A_POLL_MS)
          peer     (a2a-client/get-peer peer-name)]
      (cond
        (nil? peer)
        (let [msg (str "A2A peer '" peer-name "' is not connected")]
          (on-output msg)
          {:error msg})

        (str/blank? (str remote-task-id))
        (let [msg "job-config :remote-task-id is required"]
          (on-output msg)
          {:error msg})

        :else
        (let [!last-state (atom nil)
              !announced  (atom #{})
              ;; ONE `tasks/get` round-trip → a terminal result map, or
              ;; `still-running`. The `!last-poll` timestamp throttle that
              ;; used to wrap this is gone: `fx/poll-until` sleeps `interval`
              ;; between attempts, so pacing is structural rather than
              ;; re-derived on every one of the shared watcher's 300ms ticks
              ;; (which wasted three wake-ups out of four).
              poll-once
              (fn []
                (let [{:keys [state task error]} (a2a-client/task-state peer remote-task-id)]
                  (cond
                       ;; A transport blip must not fail the task — the peer
                       ;; may simply be restarting. Report it once and keep
                       ;; polling; the user can task$cancel if it persists.
                    error
                    (do (when-not (contains? @!announced [:error error])
                          (swap! !announced conj [:error error])
                          (on-output (str "poll failed (will retry): " error)))
                        tp/still-running)

                    :else
                    (do
                      (when (not= state @!last-state)
                        (reset! !last-state state)
                        (on-output (str "state: " (name state))))
                      (cond
                           ;; INTERRUPTED is not finished. The peer is
                           ;; holding the task open for us; promoting it to
                           ;; a terminal status here would abandon work we
                           ;; could still resume.
                        (a2a/interrupted? state)
                        (do (when-not (contains? @!announced state)
                              (swap! !announced conj state)
                              (on-output
                               (str "remote task is awaiting "
                                    (if (= :auth-required state)
                                      "credentials" "input")
                                    " — reply via agent-registry$ask on the"
                                    " remote instance, or task$cancel to give up")))
                            tp/still-running)

                        (a2a/terminal? state)
                        (let [answer (or (some-> (get-in task [:status :message])
                                                 a2a/message-text)
                                         (->> (:artifacts task)
                                              (mapcat :parts)
                                              a2a/parts-text))]
                          (on-output (str "remote task " (name state)))
                          (when-not (str/blank? answer) (on-output answer))
                          (if (contains? #{:failed :rejected} state)
                            {:error (str "remote task " (name state)
                                         (when-not (str/blank? answer)
                                           (str ": " answer)))}
                            {:result {:state state
                                      :task-id remote-task-id
                                      :peer peer-name
                                      :answer answer
                                      :artifacts (vec (:artifacts task))}}))

                        :else tp/still-running)))))]
          (on-output (str "Tracking remote A2A task " remote-task-id
                          " on peer '" peer-name "' (poll " interval "ms)"))
          {:status :detached
           ;; The one executor that genuinely keeps polling: the only way to
           ;; know whether a REMOTE task has finished is to ask the peer
           ;; again. What `poll-until` changes is that the interval IS the
           ;; pacing, and the wait is cancellable — the old form ran to the
           ;; end of its interval before it could notice a cancel.
           :task      (fx/poll-until interval tp/still-running poll-once)
           ;; Exposed for unit tests, which exercise the state decisions
           ;; (which states terminate, which announce, which announce exactly
           ;; once) one step at a time. The manager never calls this — it runs
           ;; `:task`. Closed over by the loop, it would be unreachable.
           :poll-once poll-once
           :on-cancel
           (fn []
             (mulog/info ::a2a-task-cancel :task-id (:id task)
                         :remote-task-id remote-task-id :peer peer-name)
             ;; Best-effort: a peer may answer TaskNotCancelableError because
             ;; the task already finished. That is a normal outcome, not a
             ;; failure to report.
             (let [{:keys [error]} (a2a-client/cancel-task! peer remote-task-id)]
               (when error
                 (on-output (str "remote cancel: " error)))
               nil))}))))

  (cancel-job [_ _task] true)
  (job-type [_] :a2a))

;; ============================================================================
;; CliClientJobExecutor
;; ============================================================================

(defrecord CliClientJobExecutor []
  tp/IJobExecutor
  (execute-job [_ task on-output]
    (let [{:keys [command working-dir env interaction-fn]} (:job-config task)
          client (stdio-client/start! :command command
                                      :working-dir working-dir
                                      :env env)]
      (try
        (let [forwarder (future
                          (loop [cursor 0]
                            (Thread/sleep (long 100))
                            (let [lines @(:!lines client)
                                  new-lines (when (> (count lines) cursor)
                                              (subvec lines cursor))]
                              (doseq [line new-lines] (on-output line))
                              (when @(:!running client)
                                (recur (count lines))))))]
          (if interaction-fn
            (let [result (interaction-fn client)]
              (future-cancel forwarder)
              {:result result})
            (let [exit-code (stdio-client/shutdown! client)]
              (future-cancel forwarder)
              {:exit-code (or exit-code 0)})))
        (catch Exception e
          {:error (ex-message e)})
        (finally
          (.close ^java.io.Closeable client)))))

  (cancel-job [_ _task] true)
  (job-type [_] :cli-client))

;; ============================================================================
;; ClojureSandboxJobExecutor
;; ============================================================================
;;
;; Job config:
;;   {:sandbox      <sandbox map>        — required (in-process ref, not serialized)
;;    :code         <string>             — required
;;    :timeout-ms   <int, default 30000> — how long the pool thread blocks on
;;                                          the sandbox future before detaching.
;;                                          Does NOT bound the work — once
;;                                          detached, the future keeps running
;;                                          and the manager's watcher harvests
;;                                          via :on-poll. Hard kill is via
;;                                          cancel-task → :on-cancel.}
;;
;; The pool thread runs the sandbox eval in a daemon future and waits up to
;; :timeout-ms. SCI tight loops ignore Thread.interrupt() so cancel works at
;; the future level only (best-effort). On timeout the executor returns
;; :status :detached and the manager parks an :on-poll handler that promotes
;; the task when the future eventually completes.

(defn project-sandbox-result
  "Map an eval-sandbox-thunk return into the executor's terminal-result shape.
   The manager's result->status reads :error; FINAL termination already
   arrives as an :error map (with :final-value riding along) so this is just
   a passthrough that keeps :code / :output keys present."
  [r]
  (cond-> (select-keys r [:code :output])
    (:error r)        (assoc :error  (:error r))
    (:final-value r)  (assoc :final-value (:final-value r))
    (not (:error r))  (assoc :result (:result r))))

;; NOTE: a private `emit-captured-output!` lived here — a bulk fan-out of the
;; WHOLE captured buffer, with no offset tracking. It had no callers, and it is
;; deleted rather than left available because it is exactly the duplication bug
;; `drain-incremental-output!` below now guards against: anything that emits
;; from position 0 while the sampler is also draining will replay lines the
;; user has already seen. Same reasoning as the dead SGR-only `strip-ansi` in
;; the TUI formatter — dead code that looks reusable is worse than absent.

(defn drain-incremental-output!
  "Drain new stdout from a live StringWriter into on-output, line by line.
   Tracks chars-already-emitted via !drained-offset atom.  Intermediate
   polls emit only complete lines (up to last \\n); the final drain
   (flush? true) emits any trailing partial line too.

   CONCURRENT-SAFE BY CLAIMING THE RANGE, and it has to be. The manager runs a
   periodic sampler (~300ms) AND a final flush at completion, and they can
   overlap. This was `offset @!drained-offset` … emit … `reset!` — read, emit,
   write, non-atomically — so two drains both read offset 0 and both emitted
   the same lines. Reproduced without any load by firing a sampler and a flush
   simultaneously: 156 of 400 trials duplicated, e.g.
   `[\"hello\" \"hello\" \"world\" \"world\"]`. In the wild it showed up once as
   `[\"hello\" \"hello\" \"world\"]` in a loaded `bb test` run.

   `compare-and-set!` makes the offset advance the ACT of claiming, so exactly
   one caller owns any given region and a loser re-reads and emits only what is
   left. Emission happens after the claim, deliberately: holding a lock across
   `on-output` would let a slow consumer stall the sampler."
  [on-output eval-output !drained-offset flush?]
  (when on-output
    (let [^String s (.toString eval-output)
          len       (count s)
          ;; Claim [start end) atomically; [x x) means nothing to do.
          [start end]
          (loop []
            (let [offset @!drained-offset]
              (if (>= offset len)
                [offset offset]
                (let [^String new-text (subs s offset)
                      end (if flush?
                            len
                            (let [last-nl (.lastIndexOf new-text (int \newline))]
                              (if (>= last-nl 0) (+ offset (inc last-nl)) offset)))]
                  (cond
                    (= end offset)                              [offset offset]
                    (compare-and-set! !drained-offset offset end) [offset end]
                    :else                                        (recur))))))]
      (when (> end start)
        (doseq [line (str/split-lines (subs s start end))]
          (on-output line))))))

(defn make-future-adopt
  "Build the adopt map for Future-based evals (sandbox/nREPL/tools), for
   `adopt-detached!`.

   Same split as the sandbox/nREPL executors: completion is a blocking `deref`
   on `m/blk` rather than a polled `.isDone`, and the incremental drain becomes
   an `:on-drain` the manager schedules. Returns
   `{:task <Task> :make-on-drain (fn [on-output] -> (fn [flush?]))}`.

   The `!drained` offset lives out here, shared by every drain call, so the
   final flush picks up exactly where the last sampled one stopped."
  [^java.util.concurrent.Future fut ^java.io.StringWriter eval-output code project-fn]
  (let [!drained (atom 0)]
    {:task (fx/task-of
            (fn []
              (let [done-r (try (deref fut)
                                (catch java.util.concurrent.CancellationException _
                                  {:error "cancelled" :code code
                                   :output (.toString eval-output)})
                                (catch Exception e
                                  {:error (.getMessage e) :code code
                                   :output (.toString eval-output)}))]
                (project-fn done-r))))
     :make-on-drain (fn [on-output]
                      (fn [flush?]
                        (drain-incremental-output! on-output eval-output
                                                   !drained flush?)))}))

(defn make-heartbeat-adopt
  "Adopt map for futures that produce NO incremental output of their own —
   i.e. `:tool` jobs (including subagent-as-tool calls), which unlike
   sandbox/nREPL never bind `*out*` to a streamable writer, so their task
   output would otherwise stay frozen at the initial 'Invoking…' line.

   A background heartbeat appends
   `[label] running… elapsed Ns` to a shared StringWriter while `fut` runs, and
   the drain pipe surfaces those lines through `task$detail`. Only the
   completion half changes: `make-future-adopt` awaits the future instead of
   polling `.isDone`.

   The heartbeat still self-stops on `.isDone`, so it needs no cancellation of
   its own: a cancel makes that true within one interval and the loop exits."
  [^java.util.concurrent.Future fut label interval-ms t0]
  (let [writer (java.io.StringWriter.)]
    (when (pos? (long interval-ms))
      (future
        (try
          (loop []
            (Thread/sleep (long interval-ms))
            (when-not (.isDone fut)
              (let [elapsed-s (quot (- (System/currentTimeMillis) (long t0)) 1000)]
                (.write writer (str "[" label "] running… elapsed " elapsed-s "s\n")))
              (recur)))
          (catch InterruptedException _ nil)
          (catch Throwable _ nil))))
    (make-future-adopt fut writer label identity)))

(defrecord ClojureSandboxJobExecutor []
  tp/IJobExecutor
  (execute-job [_ task on-output]
    (let [{:keys [sandbox code]} (:job-config task)
          [thunk eval-output] (clj-sandbox/eval-sandbox-thunk sandbox code)
          tid (:id task)
          fut (future (binding [proto/*current-task* (atom tid)] (thunk)))
          !drained (atom 0)]
      (mulog/info ::sandbox-eval-detached
                  :task-id (:id task)
                  :code-preview (subs code 0 (min 80 (count code))))
      ;; Effect path (design Phase 3). The old `:on-poll` did two jobs at once —
      ;; sample the growing stdout AND check whether the eval had finished.
      ;; They separate cleanly: `deref` on `m/blk` waits for completion (no
      ;; polling), and `:on-drain` keeps the sampling the manager still
      ;; schedules at the same ~300ms. Sampling was always right for a
      ;; StringWriter; using it to detect completion was the accident.
      {:status    :detached
       :task      (fx/task-of
                   (fn []
                     (let [done-r (try (deref fut)
                                       (catch java.util.concurrent.CancellationException _
                                         {:error "cancelled" :code code
                                          :output (.toString eval-output)})
                                       (catch Exception e
                                         {:error (.getMessage e) :code code
                                          :output (.toString eval-output)}))]
                       (project-sandbox-result done-r))))
       :on-drain  (fn [flush?]
                    (drain-incremental-output! on-output eval-output !drained flush?))
       :on-cancel (fn []
                    (future-cancel fut))}))

  (cancel-job [_ _task]
    ;; The manager prefers the detach-handler's :on-cancel for detached tasks;
    ;; this fallback only fires on sync-path cancel-task (where the pool
    ;; thread is mid-deref). The pool thread's InterruptedException branch
    ;; in start-task handles the bookkeeping; nothing extra to do here.
    true)
  (job-type [_] :clj-sandbox-eval))

;; ============================================================================
;; NreplEvalJobExecutor
;; ============================================================================
;;
;; Job config:
;;   {:code        <string>               — required
;;    :session     <string or nil>        — optional nREPL session id
;;    :timeout-ms  <int, default 3600000> — nREPL CLIENT read timeout (default
;;                                          1 hour). MUST exceed the LLM-facing
;;                                          auto-background deadline in
;;                                          await-task so detach wins; a short
;;                                          value here would make the client
;;                                          give up before the eval finishes
;;                                          server-side, returning a partial /
;;                                          misleading result while the actual
;;                                          work continues as zombie state.}
;;
;; Symmetric to ClojureSandboxJobExecutor in lifecycle (kicks the eval in a
;; daemon future, returns :detached immediately, exposes :on-poll / :on-cancel)
;; but NOT in timeout semantics: the sandbox executor ignores :timeout-ms
;; entirely because eval-sandbox-thunk returns a thunk the caller wraps; the
;; nREPL executor passes :timeout-ms straight to clj-nrepl/eval-string as the
;; client read timeout — hence the long default.
;;
;; Output streaming: harvest-responses writes :out/:err chunks to a shared
;; StringWriter as nREPL messages arrive; drain-incremental-output! polls
;; it every ~300ms from the watcher thread, symmetric with the sandbox
;; executor.

(defrecord NreplEvalJobExecutor []
  tp/IJobExecutor
  (execute-job [_ task on-output]
    (let [{:keys [code session timeout-ms host port]
           :or {timeout-ms 3600000}} (:job-config task)
          ;; The session actually used. `session` is nil for any agent that
          ;; does not pin one, and the old :on-cancel below was guarded on it
          ;; — so an unpinned eval got NO interrupt at all, only a
          ;; future-cancel that cannot reach a blocked socket read.
          !live-session (atom session)
          endpoint (cond-> nil
                     host (assoc :host host)
                     port (assoc :port port))
          [thunk eval-output] (clj-nrepl/eval-nrepl-thunk code
                                                          :session session
                                                          :timeout-ms timeout-ms
                                                          :host host
                                                          :port port
                                                          :on-session #(reset! !live-session %))
          tid (:id task)
          fut (future (binding [proto/*current-task* (atom tid)] (thunk)))
          !drained (atom 0)]
      (mulog/info ::nrepl-eval-detached
                  :task-id (:id task)
                  :code-preview (subs code 0 (min 80 (count code))))
      ;; Effect path — same split as the sandbox executor directly above:
      ;; completion is a blocking `deref` on `m/blk`, output sampling moves to
      ;; `:on-drain`.
      {:status    :detached
       :task      (fx/task-of
                   (fn []
                     (let [done-r (try (deref fut)
                                       (catch CancellationException _
                                         {:error "cancelled" :code code :output ""})
                                       (catch Exception e
                                         {:error (.getMessage e) :code code :output ""}))]
                       (select-keys done-r [:code :output :result :error :ns]))))
       :on-drain  (fn [flush?]
                    (drain-incremental-output! on-output eval-output !drained flush?))
       ;; Best-effort, and neither half reliably stops the eval: future-cancel
       ;; cannot reach a thread blocked in a socket read, and `interrupt!` is
       ;; measured not to stop an eval on nREPL 1.3.0 (see its docstring). What
       ;; changed here is that the interrupt now fires for an UNPINNED session
       ;; too — it used to be guarded on `session`, which is nil for every
       ;; agent that does not pin one, so the common case sent nothing at all.
       :on-cancel (fn []
                    (when-let [sid @!live-session]
                      (try (clj-nrepl/interrupt! sid endpoint)
                           (catch Exception _ nil)))
                    (future-cancel fut))}))

  (cancel-job [_ _task] true)
  (job-type [_] :clj-nrepl-eval))
