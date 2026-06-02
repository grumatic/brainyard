;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.acp.core.transport
  "Transport-agnostic protocol for ACP message exchange.

   A transport is a bidirectional, line-oriented connection to an ACP
   peer. Implementations (`stdio`, future `http`, future `websocket`)
   carry already-parsed JSON-RPC message maps in both directions.

   Decoding from a wire string into a Clojure map happens inside the
   transport — callers receive maps, never raw lines. This keeps the
   dispatcher (in components/acp-client) focused on routing rather than
   parsing.")

(defprotocol ITransport
  "Bidirectional ACP transport.

   Lifecycle:  open! → (read-message! / write-message!)* → close!

   Concurrency: `write-message!` may be called from any thread; the
   transport must serialize writes. `read-message!` is called from a
   single dispatcher pump thread."

  (open! [this]
    "Open the underlying connection. For stdio this spawns the
     subprocess and starts the reader thread. Returns `this`.")

  (read-message!
    [this]
    [this timeout-ms]
    "Block until a parsed JSON-RPC message map arrives, or the
     transport closes. Returns the map, or `nil` on EOF / timeout.

     With `timeout-ms`, returns `nil` if no message arrives within the
     window. The 1-arity blocks indefinitely.")

  (write-message! [this msg]
    "Encode `msg` as JSON, append a newline, write to the peer, flush.
     Thread-safe. Throws if the transport is closed.")

  (open? [this]
    "True while the transport is usable. Becomes false after close!,
     after EOF on the read side, or if the underlying process exits.")

  (close! [this]
    "Release resources: stop the reader thread, close the subprocess /
     socket. Idempotent."))
