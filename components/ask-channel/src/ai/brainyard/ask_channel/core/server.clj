;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.ask-channel.core.server
  "Per-session AF_UNIX listener for the side ask channel.

   A running TUI session binds one of these at
   `<session-dir>/ask.sock`. Each accepted connection carries exactly one
   request frame; the injected `handle-fn` runs the turn (typically by
   enqueuing into the TUI input queue and awaiting a reply promise) and returns
   the response map written back to the client.

   The accept loop runs on a daemon thread so it never holds the JVM open."
  (:require [ai.brainyard.ask-channel.core.protocol :as proto]
            [ai.brainyard.mulog.interface :as mulog]
            [clojure.java.io :as io])
  (:import [java.io BufferedReader InputStreamReader OutputStreamWriter]
           [java.net StandardProtocolFamily UnixDomainSocketAddress]
           [java.nio.channels Channels ServerSocketChannel SocketChannel]
           [java.nio.file Files Path]
           [java.nio.file.attribute PosixFilePermission PosixFilePermissions]))

(defn- delete-quietly!
  [^Path path]
  (try (Files/deleteIfExists path) (catch Exception _ false)))

(defn- restrict-perms!
  "Best-effort chmod 0600 on the socket file. Posix-only; a no-op (and
   non-fatal) on filesystems without Posix attribute support."
  [^Path path]
  (try
    (Files/setPosixFilePermissions
     path (PosixFilePermissions/fromString "rw-------"))
    (catch Exception _ nil)))

(defn- handle-connection!
  "Read one request frame, dispatch via `handle-fn`, write the response. All
   errors are contained — a bad client must never take down the accept loop."
  [^SocketChannel ch handle-fn]
  (with-open [ch ch
              reader (BufferedReader. (InputStreamReader. (Channels/newInputStream ch) "UTF-8"))
              writer (OutputStreamWriter. (Channels/newOutputStream ch) "UTF-8")]
    (let [resp (try
                 (let [req (proto/read-msg reader)]
                   (cond
                     (nil? req)                 {:status :error :error "empty request"}
                     (not= :ask (:op req))      {:status :error :error (str "unknown op: " (:op req))}
                     :else                      (handle-fn req)))
                 (catch Throwable t
                   {:status :error :error (str "server error: " (.getMessage t))}))]
      (try (proto/write-msg! writer resp)
           (catch Exception _ nil)))))

(defn- accept-loop!
  [^ServerSocketChannel server handle-fn]
  (try
    (loop []
      (when (.isOpen server)
        (let [ch (try (.accept server) (catch java.nio.channels.AsynchronousCloseException _ nil)
                      (catch java.nio.channels.ClosedChannelException _ nil))]
          (when ch
            (future
              (try (handle-connection! ch handle-fn)
                   (catch Throwable t
                     (mulog/warn ::ask-connection-failed :error (.getMessage t)))))
            (recur)))))
    (catch Throwable t
      (mulog/warn ::ask-accept-loop-exited :error (.getMessage t)))))

(defn start-listener!
  "Bind an AF_UNIX socket at `path` (a path-like string/File/Path) and serve
   requests via `handle-fn` (`(fn [req] response-map)`) on a daemon thread.

   Unlinks a stale socket file first (a crashed prior process leaves one),
   then chmods the new socket 0600. Returns a handle map
   `{:server ch :path Path :thread t}` for `stop-listener!`, or throws on bind
   failure (caller decides whether that is fatal)."
  [path handle-fn]
  (let [^Path p (.toPath (io/file path))
        _       (delete-quietly! p)
        addr    (UnixDomainSocketAddress/of p)
        server  (doto (ServerSocketChannel/open StandardProtocolFamily/UNIX)
                  (.bind addr))]
    (restrict-perms! p)
    (let [t (doto (Thread. ^Runnable #(accept-loop! server handle-fn)
                           "by-ask-listener")
              (.setDaemon true)
              (.start))]
      {:server server :path p :thread t})))

(defn stop-listener!
  "Close the listener and unlink its socket file. Idempotent and non-throwing."
  [{:keys [^ServerSocketChannel server ^Path path] :as _handle}]
  (when server
    (try (.close server) (catch Exception _ nil)))
  (when path
    (delete-quietly! path))
  nil)
