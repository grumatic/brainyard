;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.ask-channel.core.client
  "AF_UNIX client for the side ask channel — used by `by ask --attach`."
  (:require [ai.brainyard.ask-channel.core.protocol :as proto]
            [clojure.java.io :as io])
  (:import [java.io BufferedReader InputStreamReader OutputStreamWriter]
           [java.net StandardProtocolFamily UnixDomainSocketAddress]
           [java.nio.channels Channels SocketChannel]))

(defn send-op!
  "Connect to the AF_UNIX socket at `path`, write the single request frame `req`
   (an EDN-safe map with an `:op` key), and return the server's one-shot response
   map. Use this for the non-streaming verbs (`:ask`, `:status`, `:config`,
   `:inject`, `:cancel`). A connection failure (no listener / stale socket)
   surfaces as a thrown exception — the caller maps it to a friendly
   'session not attachable' message."
  [path req]
  (let [^java.nio.file.Path p (.toPath (io/file path))
        addr (UnixDomainSocketAddress/of p)]
    (with-open [ch (SocketChannel/open addr)
                writer (OutputStreamWriter. (Channels/newOutputStream ch) "UTF-8")
                reader (BufferedReader. (InputStreamReader. (Channels/newInputStream ch) "UTF-8"))]
      (proto/write-msg! writer req)
      (or (proto/read-msg reader)
          {:status :error :error "no response from session"}))))

(defn ask-via-socket!
  "Connect to the AF_UNIX socket at `path`, send a single `:ask` request for
   `question`, and return the server's response map
   (`{:status :ok :answer …}` or `{:status :error :error …}`).

   `:timeout-ms` is advertised to the server (its turn cap). A connection
   failure (no listener / stale socket) surfaces as a thrown exception — the
   caller maps it to a friendly 'session not attachable' message."
  [{:keys [path question timeout-ms]}]
  (send-op! path {:op :ask :question question :timeout-ms timeout-ms}))
