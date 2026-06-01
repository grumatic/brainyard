;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: Apache-2.0
;; Licensed under the Apache License, Version 2.0 (the "License"); you may
;; not use this file except in compliance with the License. See LICENSE at
;; the repository root and CONTRIBUTING.md for contribution terms.

(ns ai.brainyard.agent-tui-tmux.core.control.server
  "Unix-domain-socket server for the control protocol.

   **Retired substrate (May 2026).** Kept as test-only/internal after the
   `by-host`↔`by-ui` daemon split was retired. See
   `docs/tui/architecture.md` §9 and `docs/specs/tui.md` CR-TUI-20.

   Each accepted connection runs a per-connection handler thread that reads
   EDN frames and dispatches via a user-provided `:on-message` callback.
   The callback receives `[connection message]` where `connection` is a
   handle that exposes `send!` and `close!`.

   Built on JDK ≥16 `UnixDomainSocketAddress`.  Tests use a temp-directory
   socket path under `/var/folders/.../sock`."
  (:require [ai.brainyard.agent-tui-tmux.core.control.protocol :as proto]
            [clojure.java.io :as io])
  (:import [java.io BufferedReader BufferedWriter InputStreamReader OutputStreamWriter
            PrintWriter Closeable]
           [java.net StandardProtocolFamily UnixDomainSocketAddress]
           [java.nio.channels Channels ServerSocketChannel SocketChannel]
           [java.nio.charset StandardCharsets]
           [java.nio.file Files Path Paths LinkOption]
           [java.nio.file.attribute FileAttribute]
           [java.util.concurrent ConcurrentHashMap]))

(defrecord ^:private Connection [id ^SocketChannel channel ^PrintWriter writer
                                 ^BufferedReader reader !closed])

(defn send!
  "Send a frame to the connected `by-ui`.  No-op if the connection is closed."
  [{:keys [^PrintWriter writer !closed] :as conn} msg]
  (when-not @!closed
    (try
      (proto/write-frame! writer msg)
      (catch java.io.IOException _ (reset! !closed true))))
  conn)

(defn close-connection!
  [{:keys [^SocketChannel channel !closed]}]
  (when (compare-and-set! !closed false true)
    (try (.close channel) (catch Throwable _))))

(defn open?
  [{:keys [!closed]}]
  (not @!closed))

(defn- ^Path ->path [p]
  (cond
    (instance? Path p) p
    :else (.toPath (io/file p))))

(defn- delete-quietly [^Path path]
  (try (Files/deleteIfExists path) (catch Throwable _)))

(defn- accept-loop
  [^ServerSocketChannel server-channel handler !state]
  (try
    (while (not @(:closed? !state))
      (let [^SocketChannel ch (.accept server-channel)]
        (try
          (let [in   (-> (Channels/newInputStream ch)
                         (InputStreamReader. StandardCharsets/UTF_8)
                         (BufferedReader.))
                pw   (-> (Channels/newOutputStream ch)
                         (OutputStreamWriter. StandardCharsets/UTF_8)
                         (BufferedWriter.)
                         (PrintWriter. true))
                conn (->Connection (str (java.util.UUID/randomUUID))
                                   ch pw in (atom false))
                _    (.put ^ConcurrentHashMap (:connections !state) (:id conn) conn)
                ;; bound-fn propagates dynamic bindings (e.g. tests' rooted
                ;; `agent-tui-persist/*root*`) into the per-connection
                ;; reader thread.  Without it, `on-input` callbacks that
                ;; touch persistence — `persist/append-event!`,
                ;; `persist/session-dir`, etc. — write into the *real*
                ;; ~/.brainyard/sessions/ even when the test fixture
                ;; rebinds the root to a temp dir.
                ^Runnable runnable
                (bound-fn []
                  (try
                    (loop []
                      (let [msg (proto/read-frame in)]
                        (cond
                          (= :eof msg)
                          (close-connection! conn)

                          (= :malformed msg)
                          (do (send! conn (proto/error-msg "malformed frame"))
                              (recur))

                          :else
                          (do
                            (try
                              (handler conn msg)
                              (catch Throwable t
                                (send! conn (proto/error-msg (str (.getMessage t))))))
                            (when (open? conn) (recur))))))
                    (catch java.nio.channels.AsynchronousCloseException _ nil)
                    (catch java.nio.channels.ClosedChannelException _ nil)
                    (catch java.io.IOException _ nil)
                    (finally
                      (.remove ^ConcurrentHashMap (:connections !state) (:id conn))
                      (close-connection! conn))))
                t    (Thread. runnable (str "agent-tui-control-conn-" (:id conn)))]
            (.setDaemon t true)
            (.start t))
          (catch Throwable _ (try (.close ch) (catch Throwable _))))))
    (catch java.nio.channels.AsynchronousCloseException _ nil)
    (catch java.nio.channels.ClosedChannelException _ nil)
    (catch Throwable t
      (when-not @(:closed? !state)
        (.printStackTrace t)))))

(defn start!
  "Start a control-socket server.  Options:
     :path        — socket path (required).  Parent dirs are created.  Existing
                    file at the path is unlinked.
     :on-message  — fn `[connection message]`.  Called for each frame.
     :on-connect  — fn `[connection]`.  Called once per accepted connection.
     :on-disconnect — fn `[connection]`.  Called when a connection closes.

   Returns a control handle:
     {:path ... :stop! (fn []) :connections (fn [] ...)}"
  [{:keys [path on-message on-connect on-disconnect]
    :or {on-message (fn [_ _]) on-connect (fn [_]) on-disconnect (fn [_])}}]
  (let [^Path socket-path (->path path)
        _      (when-let [parent (.getParent socket-path)]
                 (Files/createDirectories parent (make-array FileAttribute 0)))
        _      (delete-quietly socket-path)
        addr   (UnixDomainSocketAddress/of socket-path)
        server (-> (ServerSocketChannel/open StandardProtocolFamily/UNIX)
                   (.bind addr))
        connections (ConcurrentHashMap.)
        !state {:closed? (atom false)
                :connections connections
                :server server
                :path socket-path}
        handler (fn [conn msg]
                  (when (= :hello (:type msg)) (on-connect conn))
                  (on-message conn msg))
        ;; bound-fn so the accept-loop captures the caller's dynamic
        ;; bindings (e.g. tests' `agent-tui-persist/*root*`) — those
        ;; propagate to per-connection threads spawned inside
        ;; accept-loop, which themselves re-wrap with bound-fn.
        ^Runnable accept-runnable (bound-fn [] (accept-loop server handler !state))
        thread (Thread. accept-runnable "agent-tui-control-server")
        _      (.setDaemon thread true)
        _      (.start thread)
        stop! (fn []
                (when (compare-and-set! (:closed? !state) false true)
                  (try (.close server) (catch Throwable _))
                  (doseq [^Connection c (vals connections)]
                    (try (close-connection! c) (catch Throwable _))
                    (on-disconnect c))
                  (delete-quietly socket-path)))]
    {:path        (.toString socket-path)
     :address     addr
     :stop!       stop!
     :connections (fn [] (vec (vals connections)))}))
