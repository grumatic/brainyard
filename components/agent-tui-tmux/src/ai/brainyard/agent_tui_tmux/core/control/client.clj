;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent-tui-tmux.core.control.client
  "Unix-domain-socket client for the control protocol.

   **Retired substrate (May 2026).** Kept as test-only/internal after the
   `by-host`↔`by-ui` daemon split was retired. See
   `docs/tui/architecture.md` §9 and `docs/specs/tui.md` CR-TUI-20."
  (:require [ai.brainyard.agent-tui-tmux.core.control.protocol :as proto]
            [clojure.java.io :as io])
  (:import [java.io BufferedReader BufferedWriter InputStreamReader OutputStreamWriter PrintWriter]
           [java.net StandardProtocolFamily UnixDomainSocketAddress]
           [java.nio.channels Channels SocketChannel]
           [java.nio.charset StandardCharsets]
           [java.nio.file Path]))

(defn- ^Path ->path [p]
  (if (instance? Path p) p (.toPath (io/file p))))

(defn connect!
  "Open a Unix-domain socket connection to a control server.  Options:
     :path — socket path (required)

   Returns a client handle:
     {:send! (fn [msg]) :recv (fn []) :close! (fn []) :open? (fn [])}"
  [{:keys [path]}]
  (let [addr     (UnixDomainSocketAddress/of (->path path))
        ch       (SocketChannel/open StandardProtocolFamily/UNIX)
        _        (.connect ch addr)
        in       (-> (Channels/newInputStream ch)
                     (InputStreamReader. StandardCharsets/UTF_8)
                     (BufferedReader.))
        pw       (-> (Channels/newOutputStream ch)
                     (OutputStreamWriter. StandardCharsets/UTF_8)
                     (BufferedWriter.)
                     (PrintWriter. true))
        !closed (atom false)
        send!   (fn [msg]
                  (when @!closed
                    (throw (ex-info "client closed" {})))
                  (proto/write-frame! pw msg))
        recv    (fn [] (proto/read-frame in))
        close!  (fn []
                  (when (compare-and-set! !closed false true)
                    (try (.close ch) (catch Throwable _))))]
    {:send! send! :recv recv :close! close!
     :open? (fn [] (not @!closed))
     :channel ch
     :path (str path)}))

(defn request-response!
  "Convenience: send `msg` and read one reply frame (blocking).  Useful for
   one-shot RPC from CLI tools.  Throws on error."
  [client msg]
  ((:send! client) msg)
  (let [r ((:recv client))]
    (if (= :error (:type r))
      (throw (ex-info (or (:message r) "control error") {:reply r}))
      r)))
