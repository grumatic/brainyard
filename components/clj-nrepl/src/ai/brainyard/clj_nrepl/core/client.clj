;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.clj-nrepl.core.client
  "Loopback nREPL client wrapping nrepl.core.

   Sends code to the in-process server, harvests
   {:result :output :error :ns}, and exposes `eval-nrepl-thunk` for the task
   executor (mirroring clj-sandbox/eval-sandbox-thunk).

   Gate order (every eval passes through, in order):
     1. server-up        — clj-nrepl/start-server! has run
     2. grant-active     — non-expired grant exists
     3. deny-list        — code does NOT contain forbidden substrings
                            (applies regardless of scope, per §8.2 #4)
     4. mutating-scope   — :read-only grants reject mutating top-level
                            forms; :mutate grants allow them
     5. confirmation     — first mutating eval per session requires
                            operator approval (host-installed fn)
   Eval runs only when all five pass. On a successful mutating eval,
   the drift marker is recorded. Every outcome (allow / reject /
   error) is audited via mulog."
  (:require [nrepl.core :as nrepl]
            [ai.brainyard.clj-nrepl.core.server :as server]
            [ai.brainyard.clj-nrepl.core.classifier :as classifier]
            [ai.brainyard.clj-nrepl.core.grant :as grant]
            [ai.brainyard.clj-nrepl.core.confirm :as confirm]
            [ai.brainyard.clj-nrepl.core.drift :as drift]
            [ai.brainyard.clj-nrepl.core.audit :as audit]))

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

(defn- gate
  "Return an error result map when the eval should be rejected; nil to allow.
   See ns docstring for the full gate order."
  [code session]
  (let [mutating? (classifier/mutating? code)
        scope     (grant/scope)]
    (cond
      (not (server/running?))
      (err-result code "clj-nrepl server is not running")

      (not (grant/active?))
      (err-result code
                  "no clj-nrepl grant active — set BY_NREPL_GRANT=read-only:15m")

      (classifier/denied? code)
      (err-result code
                  (str "denied by clj-nrepl allow/deny policy: "
                       (classifier/deny-reason code)))

      (and mutating? (not= :mutate scope))
      (err-result code
                  (str "mutating form rejected under " (name scope)
                       " grant: " (classifier/explain code)))

      (and mutating? (not (confirm/confirm-mutation! session code)))
      (err-result code
                  "operator declined mutation confirmation")

      :else nil)))

(defn eval-string
  "Send `code` to the live nREPL server and return a result map.

   Options:
     :session     — nREPL session id (uses fresh server-side session when omitted)
     :timeout-ms  — round-trip ceiling (default 30000)

   Returns {:code :result :output :error :ns}. Records a runtime-drift
   marker (see clj-nrepl.core.drift) when a mutating block REACHES the
   server — even if a later form in the same block errored, because
   Clojure's top-level forms evaluate sequentially and side effects
   from earlier forms aren't unwound by later errors. The marker
   means \"this session has touched live-runtime mutation paths\",
   not \"all forms succeeded\"."
  [code & {:keys [session timeout-ms output-writer]
           :or {timeout-ms default-timeout-ms}}]
  (let [start-ms (System/currentTimeMillis)
        gate-err (gate code session)
        [result reached-server?]
        (cond
          gate-err
          [gate-err false]

          :else
          (try
            (with-open [conn (nrepl/connect :port (server/server-port))]
              (let [base    (nrepl/client conn timeout-ms)
                    client* (if session
                              (nrepl/client-session base :session session)
                              (nrepl/client-session base))
                    msg     (cond-> {:op "eval" :code code}
                              session (assoc :session session))
                    harvested (harvest-responses (nrepl/message client* msg)
                                                 :output-writer output-writer)]
                [(assoc harvested :code code) true]))
            (catch Exception e
              [(err-result code
                           (str "nREPL transport error: " (.getMessage e)))
               false])))]
    (when (and reached-server? (classifier/mutating? code))
      (drift/mark! session code))
    (audit/audit-eval {:code code
                       :session session
                       :result result
                       :duration-ms (- (System/currentTimeMillis) start-ms)})
    result))

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
