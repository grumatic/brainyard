;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.acp.core.transport.stdio
  "Stdio transport for ACP — JSON-RPC 2.0 over NDJSON on a subprocess's
   stdin/stdout.

   Lifecycle pattern mirrors `agent.stdio.client` (ProcessBuilder + a
   daemon reader thread that pushes to an atom), but with two
   differences required for JSON-RPC multiplexing:

     1. The reader thread parses each line as JSON-RPC and pushes the
        resulting map onto a `LinkedBlockingQueue`. Consumers block on
        `read-message!` instead of polling an atom.

     2. Writes are serialized through a write lock so concurrent
        `write-message!` calls don't interleave bytes on stdin.

   Stderr is drained on a separate daemon thread — losing it would cause
   subprocesses with full pipe buffers to deadlock. Each line goes to
   mulog at debug level AND into a bounded in-memory tail (`stderr-tail`).

   The tail exists because a backend routinely explains a failure on
   stderr and then answers the JSON-RPC request with a generic
   `Internal error`. Debug-level logs are off by default, so the only
   actionable half of the failure was being discarded: `claude-code-acp`
   printing \"Claude Code cannot be launched inside another Claude Code
   session\" surfaced to the user as an unexplained \"ACP error: Internal
   error\". Callers attach this tail when they render an ACP failure."
  (:require [ai.brainyard.acp.core.env :as env]
            [ai.brainyard.acp.core.jsonrpc :as jsonrpc]
            [ai.brainyard.acp.core.transport :as transport]
            [ai.brainyard.mulog.interface :as mulog]
            [clojure.java.io :as io])
  (:import [ai.brainyard.acp.core.transport ITransport]
           [java.io BufferedReader InputStreamReader OutputStreamWriter Closeable]
           [java.util.concurrent LinkedBlockingQueue TimeUnit]))

;; =============================================================================
;; Sentinel — placed on the inbox queue when the reader stream closes,
;; so `read-message!` callers wake up and return `nil` rather than block
;; forever.
;; =============================================================================

(def ^:private EOF-SENTINEL ::eof)

;; =============================================================================
;; Reader thread
;; =============================================================================

(defn- start-reader-thread
  "Spawn a daemon thread that reads lines from `reader`, parses each as
   JSON-RPC, and pushes the resulting map onto `inbox`. On EOF or close
   it places `EOF-SENTINEL` on `inbox` and exits.

   Parse errors are pushed as ExceptionInfo objects so the dispatcher
   can decide whether to log + continue or close the transport."
  [^BufferedReader reader ^LinkedBlockingQueue inbox !running thread-name]
  (let [t (Thread.
           (fn []
             (try
               (loop []
                 (when @!running
                   (let [line (.readLine reader)]
                     (cond
                       (nil? line)
                       (do
                         (mulog/debug ::reader-eof :thread thread-name)
                         (.put inbox EOF-SENTINEL))

                       (clojure.string/blank? line)
                       (recur)

                       :else
                       (do
                         (try
                           (.put inbox (jsonrpc/decode line))
                           (catch Exception e
                             (mulog/warn ::parse-error
                                         :thread thread-name
                                         :line line
                                         :error (ex-message e))
                             (.put inbox e)))
                         (recur))))))
               (catch InterruptedException _
                 (mulog/debug ::reader-interrupted :thread thread-name)
                 (.put inbox EOF-SENTINEL))
               (catch Exception e
                 (when @!running
                   (mulog/warn ::reader-error
                               :thread thread-name
                               :error (ex-message e)))
                 (.put inbox EOF-SENTINEL)))))]
    (.setDaemon t true)
    (.setName t thread-name)
    (.start t)
    t))

(def ^:const STDERR_TAIL_LINES
  "How many recent stderr lines to retain per transport. Small on purpose:
   this is diagnostic context appended to an error message, not a log."
  20)

(def ^:const STDERR_LINE_MAX
  "Longest retained stderr line. A backend that dumps a whole stack trace
   on one line must not be able to grow this buffer without bound."
  500)

(defn- start-stderr-drain
  "Drain stderr on a daemon thread to prevent pipe-full deadlock.
   Each line is logged at debug level and appended to `!tail`, a bounded
   ring of the most recent `STDERR_TAIL_LINES` lines."
  [^BufferedReader reader !running !tail thread-name]
  (let [t (Thread.
           (fn []
             (try
               (loop []
                 (when @!running
                   (let [line (.readLine reader)]
                     (when line
                       (mulog/debug ::subprocess-stderr
                                    :thread thread-name
                                    :line line)
                       (when !tail
                         (let [trimmed (if (> (count line) STDERR_LINE_MAX)
                                         (str (subs line 0 STDERR_LINE_MAX) "…")
                                         line)]
                           (swap! !tail (fn [v]
                                          (let [v' (conj (or v []) trimmed)]
                                            (if (> (count v') STDERR_TAIL_LINES)
                                              (subvec v' (- (count v') STDERR_TAIL_LINES))
                                              v'))))))
                       (recur)))))
               (catch InterruptedException _ nil)
               (catch Exception _ nil))))]
    (.setDaemon t true)
    (.setName t thread-name)
    (.start t)
    t))

;; =============================================================================
;; StdioTransport
;; =============================================================================

(defrecord StdioTransport [command         ;; vector<string> — process command
                           working-dir     ;; string?         — cwd for spawned process
                           env             ;; map?            — extra env vars
                           !process        ;; atom<Process?>
                           !stdin          ;; atom<OutputStreamWriter?>
                           !inbox          ;; atom<LinkedBlockingQueue?>
                           !running        ;; atom<boolean>
                           !reader-thread  ;; atom<Thread?>
                           !stderr-thread  ;; atom<Thread?>
                           !stderr-tail    ;; atom<vector<string>> — bounded recent stderr
                           write-lock]     ;; Object — held while writing

  ITransport
  (open! [this]
    (when @!running
      (throw (ex-info "transport already open" {:command command})))
    (when-not (seq command)
      (throw (ex-info "command is required" {:command command})))
    (let [pb (ProcessBuilder. ^java.util.List (vec command))]
      (when working-dir
        (.directory pb (io/file working-dir)))
      ;; The child inherits this JVM's environment wholesale, including any
      ;; "you are inside a coding-agent session" marker set by whoever
      ;; started `by`. Drop those first (see `env/nested-session-markers` —
      ;; an inherited CLAUDECODE=1 makes claude-code-acp refuse to spawn),
      ;; then apply the spec's `:env` on top so an explicit override wins.
      (let [dropped (env/strip-nested-session-markers! (.environment pb))]
        (when (seq dropped)
          (mulog/debug ::stdio-dropped-inherited-env
                       :vars (vec dropped) :command command)))
      ;; STRICT: `:env` must ALREADY be plain string->string. Coercing here
      ;; would paper over caller bugs — a keyword key renders as
      ;; ":ANTHROPIC_MODEL", a variable the child never reads, and the
      ;; failure only resurfaces later as odd backend behaviour. Builders
      ;; normalize up front (`ai.brainyard.acp.core.env/normalize`); this
      ;; boundary refuses to spawn rather than guess.
      (when (some? env)
        (let [env-map (.environment pb)]
          (doseq [[k v] (env/validate! env {:context {:command command}})]
            (.put env-map ^String k ^String v))))
      (let [proc (.start pb)
            stdin (OutputStreamWriter. (.getOutputStream proc))
            stdout (BufferedReader. (InputStreamReader. (.getInputStream proc)))
            stderr (BufferedReader. (InputStreamReader. (.getErrorStream proc)))
            inbox (LinkedBlockingQueue.)]
        (reset! !process proc)
        (reset! !stdin stdin)
        (reset! !inbox inbox)
        (reset! !running true)
        (reset! !reader-thread
                (start-reader-thread stdout inbox !running
                                     (str "acp-stdio-reader[" (first command) "]")))
        ;; A reopened transport starts with a clean tail — stale stderr from a
        ;; previous process would be worse than none, since it would be
        ;; appended to a failure it had nothing to do with.
        (when !stderr-tail (reset! !stderr-tail []))
        (reset! !stderr-thread
                (start-stderr-drain stderr !running !stderr-tail
                                    (str "acp-stdio-stderr[" (first command) "]")))
        (mulog/info ::stdio-transport-opened
                    :command command :working-dir working-dir)
        this)))

  (read-message! [this]
    (transport/read-message! this nil))

  (read-message! [_this timeout-ms]
    (when-let [^LinkedBlockingQueue inbox @!inbox]
      (let [msg (if timeout-ms
                  (.poll inbox (long timeout-ms) TimeUnit/MILLISECONDS)
                  (.take inbox))]
        (cond
          (nil? msg)              nil      ;; timeout
          (= EOF-SENTINEL msg)    (do
                                    ;; Re-deposit so subsequent reads also see EOF
                                    (.put inbox EOF-SENTINEL)
                                    nil)
          (instance? Throwable msg) (throw msg)
          :else                   msg))))

  (write-message! [_this msg]
    (when-not @!running
      (throw (ex-info "transport is closed" {:msg msg})))
    (let [^OutputStreamWriter stdin @!stdin]
      (when-not stdin
        (throw (ex-info "transport not open" {:msg msg})))
      ;; ^String is load-bearing under native-image, not decoration. Writer
      ;; declares BOTH write(String) and write(char[]) at arity 1, so an
      ;; unhinted arg leaves the call unresolved (reflection warning here) and
      ;; the overload gets picked at runtime. On the JVM the Reflector picks
      ;; write(String) from the actual argument type; in a native image it
      ;; binds write(char[]), and every ACP turn dies on the first outbound
      ;; message with "java.lang.String cannot be cast to char[]". The literal
      ;; "\n" below was always fine — it is typed at the call site.
      (let [^String line (jsonrpc/encode msg)]
        (locking write-lock
          (.write stdin line)
          (.write stdin "\n")
          (.flush stdin)))))

  (open? [_this]
    (and @!running
         (when-let [^Process p @!process]
           (.isAlive p))))

  (close! [_this]
    (when @!running
      (reset! !running false)
      (mulog/info ::closing-stdio-transport :command command)
      (when-let [^Thread t @!reader-thread]
        (.interrupt t))
      (when-let [^Thread t @!stderr-thread]
        (.interrupt t))
      (when-let [^OutputStreamWriter w @!stdin]
        (try (.close w) (catch Exception _ nil)))
      (when-let [^Process p @!process]
        (try
          (when (.isAlive p)
            (.destroy p)
            (when-not (.waitFor p 2 TimeUnit/SECONDS)
              (.destroyForcibly p)))
          (catch Exception e
            (mulog/debug ::process-destroy-error :error (ex-message e)))))
      (when-let [^LinkedBlockingQueue inbox @!inbox]
        (.put inbox EOF-SENTINEL)))
    nil)

  Closeable
  (close [this] (transport/close! this)))

(defn stderr-tail
  "The most recent stderr lines from `transport`'s subprocess, oldest first,
   or nil when it keeps no tail.

   Deliberately duck-typed on the `:!stderr-tail` key rather than typed to
   `StdioTransport`: callers hold an `ITransport` that may be an in-memory
   or socket transport with no subprocess at all, and \"this transport has
   no stderr\" is an ordinary answer (nil), not an error."
  [transport]
  (some-> transport :!stderr-tail deref not-empty vec))

(defmethod print-method StdioTransport [t ^java.io.Writer w]
  (.write w (str "#StdioTransport{:command " (pr-str (:command t))
                 ", :open? " (boolean (transport/open? t)) "}")))

;; =============================================================================
;; Factory
;; =============================================================================

(defn create
  "Create a new (unopened) StdioTransport.

   Options:
     :command     — vector<string>, required. e.g. [\"node\" \"-e\" \"…\"]
     :working-dir — cwd for the spawned process (string)
     :env         — map<string,string> of additional env vars. STRICT:
                    `open!` throws `{:type :acp/invalid-env}` on non-string
                    keys/values, blank or untrimmed keys, and `:K`/\"K\"
                    collisions. Pre-normalize with `acp.core.env/normalize`.

   The child otherwise inherits this JVM's environment, minus the
   nested-session markers `open!` drops (`acp.core.env`). An explicit `:env`
   entry for such a marker is honoured — only inheritance is refused.

   Call `open!` to spawn the process and start I/O threads."
  [{:keys [command working-dir env]}]
  (->StdioTransport command working-dir env
                    (atom nil) (atom nil) (atom nil)
                    (atom false) (atom nil) (atom nil)
                    (atom []) (Object.)))
