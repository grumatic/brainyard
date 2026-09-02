;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.clj-nrepl.core.session
  "Thin wrappers over nREPL session lifecycle: open/close + interrupt.

   Each session keeps its own namespace + bindings on the server side,
   so a multi-step `clojure :nrepl` investigation accumulates context
   naturally across calls that share a `:session`."
  (:require [nrepl.core :as nrepl]
            [ai.brainyard.clj-nrepl.core.server :as server]
            [ai.brainyard.mulog.interface :as mulog]))

(def ^:private default-msg-timeout-ms 5000)

(defn- with-connect
  "Run `f` with a fresh nrepl client bound to an nREPL endpoint. Defaults to
   the local in-process server; `opts` may carry `:host`/`:port` to reach the
   remote endpoint an eval actually ran on.

   The endpoint has to be a parameter, not a constant: a session id is only
   meaningful to the server that issued it, so sending an interrupt for a
   remote session to the loopback server either no-ops (no local server) or
   addresses a DIFFERENT session that happens to share the id's namespace.
   Both look like a cancel that silently did nothing."
  ([f] (with-connect nil f))
  ([{:keys [host port]} f]
   (when-let [port* (or port (server/server-port))]
     (with-open [conn (nrepl/connect :host (or host "127.0.0.1") :port port*)]
       (let [client (nrepl/client conn default-msg-timeout-ms)]
         (f client))))))

(defn new-session
  "Open a new nREPL session. Returns the server-issued session id (string)
   or nil when the server is not running."
  []
  (with-connect
    (fn [client]
      (-> (nrepl/message client {:op "clone"})
          nrepl/combine-responses
          :new-session))))

(defn close-session
  "Close an nREPL session by id."
  [session-id]
  (with-connect
    (fn [client]
      (nrepl/message client {:op "close" :session session-id})
      (mulog/info ::session-closed :session session-id))))

(defn interrupt!
  "Send an interrupt op to a session. `opts` may carry `:host`/`:port` for a
   remote endpoint (default: the local server). BEST-EFFORT — see the
   measurement below before relying on it.

   The shape is right: the caller's thread is blocked in a socket read inside
   `nrepl/message`, which no `Thread.interrupt` reaches, so `future-cancel`
   abandons the WAIT and leaves the server evaluating. This op travels on a
   SEPARATE connection and asks the server to stop its own thread — the only
   route that does not need the blocked thread to cooperate.

   MEASURED, nREPL 1.3.0, and it does NOT currently stop an eval. Against a
   canonical client (eval on a session, interrupt from a second connection,
   response realized) a `(Thread/sleep 25000)` survived both the bare form and
   the `:interrupt-id`-qualified form. The op is ACCEPTED — status `[\"done\"]`,
   no `session-idle`, no `interrupt-id-mismatch` — the evaluation simply keeps
   going. A control in the same JVM confirms plain threads interrupt normally,
   so this is the server's behaviour, not a dead `Thread.interrupt`.

   Kept anyway: it costs one message, it is the only lever the protocol
   offers, and it will start working if the server side improves. But do not
   write code that DEPENDS on an nREPL eval stopping — today a cancelled
   nREPL eval abandons the waiter and the server runs the work to completion.
   Omitting `:interrupt-id` targets whatever the session is currently
   evaluating, which is what a cancel means here."
  ([session-id] (interrupt! session-id nil))
  ([session-id opts]
   (with-connect opts
     (fn [client]
       (nrepl/message client {:op "interrupt" :session session-id})
       (mulog/info ::session-interrupted :session session-id)))))
