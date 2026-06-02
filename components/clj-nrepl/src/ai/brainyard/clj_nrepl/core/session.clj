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
  "Run `f` with a fresh nrepl client bound to the running server. Returns
   nil when no server is running."
  [f]
  (when-let [port (server/server-port)]
    (with-open [conn (nrepl/connect :port port)]
      (let [client (nrepl/client conn default-msg-timeout-ms)]
        (f client)))))

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
  "Send an interrupt op to a session (cooperative)."
  [session-id]
  (with-connect
    (fn [client]
      (nrepl/message client {:op "interrupt" :session session-id})
      (mulog/info ::session-interrupted :session session-id))))
