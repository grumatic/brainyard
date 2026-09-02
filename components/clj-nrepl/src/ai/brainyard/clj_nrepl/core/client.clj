;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.clj-nrepl.core.client
  "Loopback nREPL client wrapping nrepl.core.

   Sends code to the in-process server, harvests
   {:result :output :error :ns}, and exposes `eval-nrepl-thunk` for the task
   executor (mirroring clj-sandbox/eval-sandbox-thunk).

   nREPL is the FULL-TRUST live-runtime backend: like any CIDER-attachable
   nREPL, a client that reaches the server gets full `eval`. The only check on
   the eval path is the deny-list (catastrophic substrings). There is NO grant,
   scope, confirmation, drift, or audit machinery — static analysis can't
   soundly isolate a live nREPL, so isolation is delegated to the SCI sandbox
   backend (:clj-backend :sandbox), the sound controlled-bindings interpreter.
   The only structural safety is that the socket is loopback-only.

   Gate order (every eval passes through, in order):
     1. server-up   — clj-nrepl/start-server! has run
     2. deny-list   — code does NOT contain forbidden substrings
                      (System/exit, Runtime/.exec, credential namespaces, …)
   Eval runs when both pass."
  (:require [nrepl.core :as nrepl]
            [ai.brainyard.clj-nrepl.core.server :as server]
            [ai.brainyard.clj-nrepl.core.classifier :as classifier]))

(def ^:const default-timeout-ms 30000)

(defn- harvest-responses
  "Walk an nrepl.core response seq into {:result :output :error :ns}.

   :output accumulates :out + :err in arrival order.
   Last :value wins for :result. Status \"error\" sets :error.
   When output-writer is non-nil, :out/:err chunks are also written to it
   as they arrive, enabling incremental polling from another thread."
  [responses & {:keys [output-writer]}]
  (reduce
   (fn [acc msg]
     (let [{:keys [value out err ex root-ex ns status]} msg]
       (when output-writer
         (when out (.write ^java.io.Writer output-writer ^String out))
         (when err (.write ^java.io.Writer output-writer ^String err)))
       (cond-> acc
         value (assoc :result value)
         ns    (assoc :ns ns)
         out   (update :output str out)
         err   (update :output str err)
         (or ex root-ex) (update :error
                                 (fn [e] (or e (str (or root-ex ex)))))
         (some #{"error"} status)
         (update :error (fn [e] (or e "nREPL eval error"))))))
   {:result nil :output "" :error nil :ns nil}
   responses))

(defn- err-result [code msg]
  {:code code :result nil :output "" :error msg :ns nil})

(def ^:private loopback-hosts
  "Hosts that mean 'the in-process server'. A non-loopback host is a remote
   endpoint, whose liveness the local `server/running?` atom can't observe."
  #{"127.0.0.1" "localhost" "::1" "0:0:0:0:0:0:0:1"})

(def ^:private max-connect-ms
  "Ceiling on the TCP handshake, independent of the message round-trip budget.

   `nrepl.core/connect` builds its socket with `(java.net.Socket. host port)` —
   the blocking constructor, which takes NO connect timeout and therefore falls
   back to the OS default (~75s on macOS). `:timeout-ms` only ever reached
   `nrepl/client`, which bounds the message round-trip AFTER a socket exists, so
   an unreachable remote `:nrepl-host` (R4) hung the caller for 75s no matter
   what timeout it asked for — measured, not theorized.

   The effective budget is `(min timeout-ms max-connect-ms)`: deriving it from
   the caller's own timeout avoids a second knob that could drift out of sync
   with the first, while this ceiling keeps a generous message timeout (the eval
   path uses an hour) from re-inheriting the OS default."
  5000)

(defn- reachable?
  "Bounded TCP pre-flight. Returns nil when `host:port` accepts a connection
   within `budget-ms`, else a short reason string.

   Done as a separate probe rather than by handing `nrepl/connect` a socket:
   its `:socket` option takes a UNIX domain socket, not a connected TCP one.
   The probe socket is closed immediately; the real connect that follows is
   against a host already known to be listening, so it returns promptly."
  [^String host port budget-ms]
  (let [sock (java.net.Socket.)]
    (try
      (.connect sock (java.net.InetSocketAddress. host (int port)) (int budget-ms))
      nil
      (catch java.net.SocketTimeoutException _
        (str "connect timed out after " budget-ms "ms"))
      (catch java.io.IOException e
        (str "connect failed: " (.getMessage e)))
      (finally
        (try (.close sock) (catch Exception _))))))

(defn- gate
  "Return an error result map when the eval should be rejected; nil to allow.
   nREPL is full-trust: the only checks are the deny-list (catastrophic
   substrings) and, for the LOCAL server, that it is up. A remote endpoint's
   liveness is surfaced as a transport error at connect time instead. Isolation
   is the SCI sandbox's job."
  [code local?]
  (cond
    (and local? (not (server/running?)))
    (err-result code "clj-nrepl server is not running")

    (classifier/denied? code)
    (err-result code
                (str "denied by clj-nrepl allow/deny policy: "
                     (classifier/deny-reason code)))

    :else nil))

(defn eval-string
  "Send `code` to an nREPL server and return a result map.

   Options:
     :session     — nREPL session id (uses fresh server-side session when omitted)
     :timeout-ms  — round-trip ceiling (default 30000)
     :host        — endpoint host (default 127.0.0.1 = the in-process server)
     :port        — endpoint port (default = the local server's port)
     :on-session  — (fn [session-id]) called as soon as the session is known,
                    BEFORE the eval is sent. This is the only way a caller can
                    learn the id of a session it did not pin, and it has to
                    arrive early: the id is for addressing an eval that is
                    STILL RUNNING, so returning it in the result map would hand
                    it over exactly one moment too late. Throwing from it
                    cannot fail the eval. (Note the id lets you ADDRESS a
                    running eval, not necessarily stop one — `interrupt!` is
                    measured ineffective on nREPL 1.3.0; see its docstring.)

   The default endpoint is the local loopback server (today's behavior). Pass a
   remote `:host` + `:port` to run on a remote nREPL server — FULL-TRUST, so only
   a server you own (R4). Returns {:code :result :output :error :ns}."
  [code & {:keys [session timeout-ms output-writer host port on-session]
           :or {timeout-ms default-timeout-ms}}]
  (let [host*  (or host "127.0.0.1")
        local? (contains? loopback-hosts host*)
        port*  (or port (when local? (server/server-port)))]
    (if-let [gate-err (gate code local?)]
      gate-err
      (if (nil? port*)
        (err-result code (if local?
                           "clj-nrepl server is not running"
                           "remote nREPL endpoint requires :port"))
        (try
          (if-let [why (reachable? host* port* (min timeout-ms max-connect-ms))]
            (err-result code (str "nREPL transport error: " why))
            (with-open [conn (nrepl/connect :host host* :port port*)]
              ;; Resolve the session id HERE rather than letting client-session
              ;; clone one out of sight. Behaviour-identical — client-session
              ;; calls new-session itself when not given one, so this is the
              ;; same round-trip — but the id now exists as a value we can hand
              ;; to `on-session` before the eval starts. Without that, an eval
              ;; on an unpinned session was uninterruptible by construction:
              ;; the only handle on it lived inside a closure in nrepl.core.
              (let [base    (nrepl/client conn timeout-ms)
                    sid     (or session (nrepl/new-session base))
                    _       (when on-session
                              (try (on-session sid) (catch Throwable _ nil)))
                    client* (nrepl/client-session base :session sid)
                    msg     {:op "eval" :code code :session sid}
                    harvested (harvest-responses (nrepl/message client* msg)
                                                 :output-writer output-writer)]
                (assoc harvested :code code))))
          (catch Exception e
            (err-result code
                        (str "nREPL transport error: " (.getMessage e)))))))))

(defn eval-nrepl-thunk
  "Build a zero-arg thunk that evaluates `code` on the live nREPL server.
   Caller owns the future + timeout (used by NreplEvalJobExecutor).
   Returns [thunk eval-output]. The StringWriter receives :out/:err
   chunks incrementally as they arrive from nREPL, enabling progressive
   output polling via drain-incremental-output!.

   Pass `:on-session` through `opts` when the caller needs to cancel: the
   thunk blocks in a socket read, so `future-cancel` cannot stop the eval and
   `interrupt!` on the session id is the only mechanism that can."
  [code & {:as opts}]
  (let [eval-output (java.io.StringWriter.)]
    [(fn [] (apply eval-string code
                   (mapcat identity (assoc opts :output-writer eval-output))))
     eval-output]))
