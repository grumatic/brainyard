;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.task.executor
  "Job executors for task management system.
   BashJobExecutor — shell commands via ProcessBuilder
   ToolJobExecutor — invoke registered tools from !tool-defs
   CliClientJobExecutor — drive CliClient processes
   ClojureSandboxJobExecutor — evaluate Clojure code in a clj-sandbox SCI ctx
   NreplEvalJobExecutor — evaluate Clojure code in the LIVE runtime via clj-nrepl"
  (:require [ai.brainyard.agent.task.protocol :as tp]
            [ai.brainyard.agent.core.protocol :as proto]
            [ai.brainyard.agent.core.tool :as tool]
            [ai.brainyard.agent.stdio.client :as stdio-client]
            [ai.brainyard.clj-sandbox.interface :as clj-sandbox]
            [ai.brainyard.clj-nrepl.interface :as clj-nrepl]
            [ai.brainyard.mulog.interface :as mulog]
            [clojure.string :as str])
  (:import [java.io InputStreamReader]
           [java.lang Process ProcessHandle]
           [java.util.concurrent CancellationException]))

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
          pb (ProcessBuilder. ^"[Ljava.lang.String;" (into-array String ["/bin/sh" "-c" command]))]
      (.redirectErrorStream pb true)
      (when working-dir (.directory pb (java.io.File. ^String working-dir)))
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
        {:status    :detached
         :on-poll   (fn []
                      (if (.isAlive ^Process proc)
                        tp/still-running
                        (finalize-result)))
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

(defn- emit-captured-output!
  "Split the captured StringWriter output into lines and fan out to on-output,
   so `task$detail :last-n N` callers see streaming-style lines (the sandbox itself
   only flushes at the end of an eval, so this is end-of-eval bulk fanout)."
  [on-output captured]
  (when (and on-output (string? captured) (pos? (count captured)))
    (doseq [line (str/split-lines captured)]
      (on-output line))))

(defn drain-incremental-output!
  "Drain new stdout from a live StringWriter into on-output, line by line.
   Tracks chars-already-emitted via !drained-offset atom.  Intermediate
   polls emit only complete lines (up to last \\n); the final drain
   (flush? true) emits any trailing partial line too."
  [on-output eval-output !drained-offset flush?]
  (when on-output
    (let [^String s (.toString eval-output)
          offset @!drained-offset
          len (count s)]
      (when (> len offset)
        (let [^String new-text (subs s offset)]
          (if flush?
            (do (doseq [line (str/split-lines new-text)]
                  (on-output line))
                (reset! !drained-offset len))
            (let [last-nl (.lastIndexOf new-text (int \newline))]
              (when (>= last-nl 0)
                (doseq [line (str/split-lines (subs new-text 0 (inc last-nl)))]
                  (on-output line))
                (reset! !drained-offset (+ offset (inc last-nl)))))))))))

(defn make-future-poll-fn
  "Build a make-on-poll for Future-based evals (sandbox/nREPL/tools).
   Returns (fn [on-output] -> poll-fn) suitable for adopt-detached!."
  [^java.util.concurrent.Future fut eval-output code project-fn]
  (let [!drained (atom 0)]
    (fn [on-output]
      (fn []
        (drain-incremental-output! on-output eval-output !drained false)
        (if (.isDone fut)
          (let [done-r (try (deref fut)
                            (catch java.util.concurrent.CancellationException _
                              {:error "cancelled" :code code
                               :output (.toString eval-output)})
                            (catch Exception e
                              {:error (.getMessage e) :code code
                               :output (.toString eval-output)}))]
            (drain-incremental-output! on-output eval-output !drained true)
            (project-fn done-r))
          tp/still-running)))))

(defn make-heartbeat-poll-fn
  "make-on-poll builder for adopted futures that produce NO incremental output
   of their own — i.e. :tool jobs (including subagent-as-tool calls), which
   unlike sandbox/nREPL never bind *out* to a streamable writer, so their task
   output is otherwise frozen at the initial 'Invoking…' line for the whole run.

   A background heartbeat appends a liveness line to a shared StringWriter every
   `interval-ms` while `fut` runs; the standard make-future-poll-fn drain pipe
   then surfaces those lines through task$detail. This gives a polling LLM a
   growing-output liveness signal so a long-running subagent isn't mistaken for
   a wedged task and cancelled.

   `t0` is the epoch-ms the underlying work actually started, so elapsed stays
   accurate across the pre-adopt fast-eval window. The heartbeat self-stops when
   `fut` finishes — cancel makes .isDone true within one interval, so the loop
   exits and no thread leaks. The future's deref remains the terminal result
   (project-fn identity); heartbeat lines live only on the streaming surface, not
   in :result. Returns (fn [on-output] -> poll-fn) for adopt-detached!."
  [^java.util.concurrent.Future fut label interval-ms t0]
  (let [writer (java.io.StringWriter.)]
    (future
      (try
        (loop []
          (Thread/sleep (long interval-ms))
          (when-not (.isDone fut)
            (let [elapsed-s (quot (- (System/currentTimeMillis) (long t0)) 1000)]
              (.write writer (str "[" label "] running… elapsed " elapsed-s "s\n")))
            (recur)))
        (catch InterruptedException _ nil)
        (catch Throwable _ nil)))
    (make-future-poll-fn fut writer label identity)))

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
      {:status    :detached
       :on-poll   (fn []
                    ;; Drain any new stdout since last poll (complete lines only).
                    (drain-incremental-output! on-output eval-output !drained false)
                    (if (.isDone ^java.util.concurrent.Future fut)
                      (let [done-r (try (deref fut)
                                        (catch java.util.concurrent.CancellationException _
                                          {:error "cancelled" :code code
                                           :output (.toString eval-output)})
                                        (catch Exception e
                                          {:error (.getMessage e) :code code
                                           :output (.toString eval-output)}))]
                        ;; Final drain: flush any remaining partial line.
                        (drain-incremental-output! on-output eval-output !drained true)
                        (project-sandbox-result done-r))
                      tp/still-running))
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
    (let [{:keys [code session timeout-ms]
           :or {timeout-ms 3600000}} (:job-config task)
          [thunk eval-output] (clj-nrepl/eval-nrepl-thunk code
                                                          :session session
                                                          :timeout-ms timeout-ms)
          tid (:id task)
          fut (future (binding [proto/*current-task* (atom tid)] (thunk)))
          !drained (atom 0)]
      (mulog/info ::nrepl-eval-detached
                  :task-id (:id task)
                  :code-preview (subs code 0 (min 80 (count code))))
      {:status    :detached
       :on-poll   (fn []
                    (drain-incremental-output! on-output eval-output !drained false)
                    (if (.isDone ^java.util.concurrent.Future fut)
                      (let [done-r (try (deref fut)
                                        (catch CancellationException _
                                          {:error "cancelled" :code code :output ""})
                                        (catch Exception e
                                          {:error (.getMessage e) :code code :output ""}))]
                        (drain-incremental-output! on-output eval-output !drained true)
                        (select-keys done-r [:code :output :result :error :ns]))
                      tp/still-running))
       :on-cancel (fn []
                    (when session
                      (try (clj-nrepl/interrupt! session)
                           (catch Exception _ nil)))
                    (future-cancel fut))}))

  (cancel-job [_ _task] true)
  (job-type [_] :clj-nrepl-eval))
