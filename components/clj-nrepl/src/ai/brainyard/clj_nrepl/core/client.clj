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

   The default endpoint is the local loopback server (today's behavior). Pass a
   remote `:host` + `:port` to run on a remote nREPL server — FULL-TRUST, so only
   a server you own (R4). Returns {:code :result :output :error :ns}."
  [code & {:keys [session timeout-ms output-writer host port]
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
          (with-open [conn (nrepl/connect :host host* :port port*)]
            (let [base    (nrepl/client conn timeout-ms)
                  client* (if session
                            (nrepl/client-session base :session session)
                            (nrepl/client-session base))
                  msg     (cond-> {:op "eval" :code code}
                            session (assoc :session session))
                  harvested (harvest-responses (nrepl/message client* msg)
                                               :output-writer output-writer)]
              (assoc harvested :code code)))
          (catch Exception e
            (err-result code
                        (str "nREPL transport error: " (.getMessage e)))))))))

(defn eval-nrepl-thunk
  "Build a zero-arg thunk that evaluates `code` on the live nREPL server.
   Caller owns the future + timeout (used by NreplEvalJobExecutor).
   Returns [thunk eval-output]. The StringWriter receives :out/:err
   chunks incrementally as they arrive from nREPL, enabling progressive
   output polling via drain-incremental-output!."
  [code & {:as opts}]
  (let [eval-output (java.io.StringWriter.)]
    [(fn [] (apply eval-string code
                   (mapcat identity (assoc opts :output-writer eval-output))))
     eval-output]))
