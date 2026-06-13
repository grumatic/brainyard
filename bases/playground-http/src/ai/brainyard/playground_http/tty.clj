;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
(ns ai.brainyard.playground-http.tty
  "Stub ttyd WebSocket. Speaks just enough of ttyd's wire protocol to drive the
   front-end terminal: it greets on open and echoes keystrokes back as output.
   It stands in for `playground-proxy`, which in production bridges this socket
   to the real ttyd running `by --web-tmux` inside the user's workspace.

   ttyd protocol (subprotocol \"tty\"): frames are bytes whose first character
   is a command. Server->client: '0' OUTPUT. Client->server: '0' INPUT, plus a
   leading JSON auth frame ({\"AuthToken\":...}) with no command prefix.

   Implemented with the Ring-WebSocket response API (`:ring.websocket/listener`)
   rather than http-kit's `as-channel`, because the browser offers the `tty`
   subprotocol and per RFC 6455 closes the socket unless the server echoes a
   selected one. Only http-kit's Ring-WebSocket path sets `Sec-WebSocket-Protocol`
   (via `:ring.websocket/protocol`); `as-channel` does not. Keeping the client
   handshake identical to production (SPA -> proxy -> ttyd all negotiate `tty`)."
  (:require [clojure.string :as str]
            [ring.websocket :as ws])
  (:import [java.nio ByteBuffer]))

(def ^:private OUTPUT (byte \0))   ; server -> client
(def ^:private INPUT  (byte \0))   ; client -> server
(def ^:private LBRACE (byte \{))   ; start of the JSON auth handshake

;; ESC (0x1b) built programmatically so the source file carries no literal
;; control byte — those don't survive text tooling/round-trips intact.
(def ^:private ESC (str (char 27)))

(defn- output-frame
  "Build a binary OUTPUT frame: '0' followed by the UTF-8 payload, wrapped as a
   ByteBuffer so Ring sends it as a binary frame (the client reads arraybuffer)."
  ^ByteBuffer [^String s]
  (let [body (.getBytes s "UTF-8")
        buf  (byte-array (inc (alength body)))]
    (aset-byte buf 0 OUTPUT)
    (System/arraycopy body 0 buf 1 (alength body))
    (ByteBuffer/wrap buf)))

(defn- ->bytes
  "Normalize a received frame (String text or ByteBuffer binary) to a byte[]."
  ^bytes [message]
  (if (string? message)
    (.getBytes ^String message "UTF-8")
    (let [^ByteBuffer bb message
          arr (byte-array (.remaining bb))]
      (.get bb arr)
      arr)))

;; ANSI SGR bold-green banner (ESC[1;32m ... ESC[0m).
(def ^:private greeting
  (str ESC "[1;32mBrainyard Playground" ESC "[0m  (stub echo)\r\n"
       "This terminal echoes what you type. Replace with playground-proxy -> ttyd.\r\n\r\n$ "))

(defn handler
  "Returns a Ring handler that upgrades to a `tty`-subprotocol WebSocket for
   `session-id`. The listener greets on open and echoes INPUT frames."
  [_session-id]
  (fn [_req]
    {:ring.websocket/protocol "tty"
     :ring.websocket/listener
     {:on-open    (fn [socket]
                    (ws/send socket (output-frame greeting)))
      :on-message (fn [socket message]
                    (let [^bytes b (->bytes message)]
                      (when (pos? (alength b))
                        (let [cmd (aget b 0)]
                          (cond
                            (= cmd LBRACE) nil           ; JSON auth frame -- ignore
                            (= cmd INPUT)
                            (let [data (java.util.Arrays/copyOfRange b 1 (alength b))
                                  ;; CR -> CRLF + prompt so the echo reads like a shell
                                  s    (-> (String. data "UTF-8")
                                           (str/replace "\r" "\r\n$ "))]
                              (ws/send socket (output-frame s)))
                            :else nil)))))
      :on-close   (fn [_socket _code _reason] nil)}}))
