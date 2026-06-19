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
           [java.nio.file Files LinkOption Path]
           [java.nio.file.attribute BasicFileAttributes PosixFilePermission PosixFilePermissions]))

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

(defn- file-identity
  "Filesystem identity of `p` — its provider fileKey (device+inode on unix), or
   nil when the file is missing/unreadable. A delete+rebind at the same path
   yields a different key, so this lets stop-listener! tell our own socket from a
   successor's. (Caveat: inode numbers can be reused after deletion; that residual
   window sits inside the same narrow race the live-owner guard already closes.)"
  [^Path p]
  (try
    (.fileKey (Files/readAttributes p BasicFileAttributes
                                    (make-array LinkOption 0)))
    (catch Exception _ nil)))

(defn- should-unlink?
  "Decide whether stop-listener! may unlink the socket file. Unlink only the file
   we actually bound — same identity, or (degenerate) when we never captured one,
   in which case fall back to legacy cleanup. Never unlink a foreign identity (a
   successor's live socket) or a path whose file is already gone."
  [stored-key current-key]
  (cond
    (nil? current-key) false
    (nil? stored-key)  true
    :else              (= stored-key current-key)))

(defn- live-owner?
  "True when a process is actively listening on the AF_UNIX socket at `p`. A
   successful connect means the socket is bound (a live owner); a leftover file
   from a crashed process is not bound, so connect is refused — treat as stale and
   replaceable. This distinguishes 'in use by a live process' from 'stale socket
   file' without relying on PID files."
  [^Path p]
  (and (.exists (.toFile p))
       (try
         (with-open [_ (SocketChannel/open (UnixDomainSocketAddress/of p))]
           true)
         (catch Exception _ false))))

(defn- handle-connection!
  "Read one request frame, dispatch via `handle-fn`, write the response. All
   errors are contained — a bad client must never take down the accept loop.

   `handle-fn` owns op-dispatch (`:ask`, `:status`, …) and returns the
   unknown-op error for verbs it doesn't recognize — the transport only frames
   requests and guarantees a response."
  [^SocketChannel ch handle-fn]
  (with-open [ch ch
              reader (BufferedReader. (InputStreamReader. (Channels/newInputStream ch) "UTF-8"))
              writer (OutputStreamWriter. (Channels/newOutputStream ch) "UTF-8")]
    (let [resp (try
                 (if-let [req (proto/read-msg reader)]
                   (handle-fn req)
                   {:status :error :error "empty request"})
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

   Refuses (throws `ex-info` with `{:reason :live-owner}`) when a live process is
   already listening at `path` — never clobbers another owner's socket. Only a
   stale file from a crashed process is unlinked before re-bind, then the new
   socket is chmod'd 0600. Returns a handle map
   `{:server ch :path Path :thread t :file-key k}` for `stop-listener!`, or throws
   on a live owner / bind failure (caller decides whether that is fatal)."
  [path handle-fn]
  (let [^Path p (.toPath (io/file path))]
    (when (live-owner? p)
      (throw (ex-info "ask socket already bound by a live process"
                      {:reason :live-owner :path (str p)})))
    (delete-quietly! p)
    (let [addr   (UnixDomainSocketAddress/of p)
          server (doto (ServerSocketChannel/open StandardProtocolFamily/UNIX)
                   (.bind addr))]
      (restrict-perms! p)
      (let [t (doto (Thread. ^Runnable #(accept-loop! server handle-fn)
                             "by-ask-listener")
                (.setDaemon true)
                (.start))]
        {:server server :path p :thread t :file-key (file-identity p)}))))

(defn stop-listener!
  "Close the listener and unlink its socket file — but only when the file still at
   `path` is the one WE bound (identity match). A successor that rebound a fresh
   socket here has a different identity and is left intact, so a closing orphan
   can't sever the live owner. Idempotent and non-throwing."
  [{:keys [^ServerSocketChannel server ^Path path file-key] :as _handle}]
  (when server
    (try (.close server) (catch Exception _ nil)))
  (when (and path (should-unlink? file-key (file-identity path)))
    (delete-quietly! path))
  nil)
