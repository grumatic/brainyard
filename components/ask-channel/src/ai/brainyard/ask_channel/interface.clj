;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.ask-channel.interface
  "Public interface for the side ask channel.

   A running TUI session opens a per-session AF_UNIX socket
   (`<session-dir>/ask.sock`); `by ask --attach <session-id>` connects to it,
   injects a question into that session's turn queue, and prints the answer.
   See docs/design/ask-attach-channel.md.

   Server side (`start-listener!`/`stop-listener!`) lives in the agent-tui base;
   the client side (`ask-via-socket!`) is called from the agent-tui-app project."
  (:require [ai.brainyard.ask-channel.core.client :as client]
            [ai.brainyard.ask-channel.core.server :as server]))

;; -- Server -------------------------------------------------------------------

(defn start-listener!
  "Bind an AF_UNIX socket at `path` and serve `:ask` requests via `handle-fn`
   (`(fn [req] response-map)`) on a daemon thread. Unlinks a stale socket first,
   chmods 0600. Returns an opaque handle for `stop-listener!`; throws on bind
   failure."
  [path handle-fn]
  (server/start-listener! path handle-fn))

(defn stop-listener!
  "Close a listener handle and unlink its socket file. Idempotent."
  [handle]
  (server/stop-listener! handle))

;; -- Client -------------------------------------------------------------------

(defn ask-via-socket!
  "Send one `:ask` for `(:question opts)` to the socket at `(:path opts)` and
   return the response map. `:timeout-ms` advertises the turn cap. Throws if the
   socket can't be reached."
  [opts]
  (client/ask-via-socket! opts))
