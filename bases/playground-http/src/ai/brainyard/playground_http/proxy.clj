;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
(ns ai.brainyard.playground-http.proxy
  "Authz'd WebSocket reverse proxy: browser <-> the container's ttyd. This is
   the data path the design calls `playground-proxy` — it owns ONLY the WS
   bridge (no lifecycle, no secrets beyond the per-session ttyd credential it is
   handed). Kept as a base namespace for Phase 0; graduates to
   `components/playground-proxy` with the broker/store.

   The browser speaks ttyd's protocol to us (subprotocol `tty`); we speak it to
   the upstream ttyd over a java.net.http WebSocket. Validated handshake
   (ttyd 1.7.7): the upgrade needs HTTP Basic auth + an Origin matching the
   upstream host + subprotocol `tty`; the FIRST client message must be a TEXT
   frame `{\"AuthToken\":\"<base64(user:pass)>\",\"columns\":C,\"rows\":R}`;
   everything after is binary, command-prefixed ('0' input/output, '1' resize).

   Auth indirection: the BROWSER authenticated to US via the control plane, so
   the random token it sends in its own `{\"AuthToken\":...}` frame is OURS, not
   ttyd's. We DROP that frame and inject the correct ttyd AuthToken upstream
   ourselves — the browser never sees the workspace credential."
  (:require [ring.websocket :as ws])
  (:import [java.net URI]
           [java.net.http HttpClient WebSocket WebSocket$Listener]
           [java.nio ByteBuffer]
           [java.util Base64]
           [java.util.function Consumer]))

(defonce ^:private http-client (delay (HttpClient/newHttpClient)))

(defn- b64 ^String [^String s]
  (.encodeToString (Base64/getEncoder) (.getBytes s "UTF-8")))

(defn- bb->bytes ^bytes [^ByteBuffer bb]
  (let [a (byte-array (.remaining bb))] (.get bb a) a))

(def ^:private LBRACE (byte \{))

(defn listener
  "Build a Ring-WebSocket Listener (map form) bridging the browser socket to the
   upstream ttyd described by `{:host-port :ttyd-user :ttyd-pass}`. Returned as a
   plain map; ring-core extends IPersistentMap to the Listener protocol."
  [{:keys [host-port ttyd-user ttyd-pass]}]
  (let [up-ref   (atom nil)     ; upstream WebSocket, set once connected
        sock-ref (atom nil)     ; browser-side ring Socket
        pending  (atom [])      ; client frames buffered until upstream is up
        cred     (b64 (str ttyd-user ":" ttyd-pass))
        origin   (str "http://127.0.0.1:" host-port)
        uri      (URI. (str "ws://127.0.0.1:" host-port "/ws"))
        up-listener
        (reify WebSocket$Listener
          (onOpen [_ up] (.request up 1))
          ;; ttyd → browser frames arrive as binary; forward verbatim. (ttyd
          ;; never sends text post-handshake, but forward those too for safety.)
          (onText [_ up data last]
            (when-let [s @sock-ref] (ws/send s (str data)))
            (.request up 1) (java.util.concurrent.CompletableFuture/completedFuture nil))
          (onBinary [_ up data last]
            (when-let [s @sock-ref] (ws/send s (ByteBuffer/wrap (bb->bytes data))))
            (.request up 1) (java.util.concurrent.CompletableFuture/completedFuture nil))
          (onError [_ up err]
            (when-let [s @sock-ref] (ws/close s)))
          (onClose [_ up code reason]
            (when-let [s @sock-ref] (ws/close s))
            (java.util.concurrent.CompletableFuture/completedFuture nil)))]
    {:on-open
     (fn [browser-sock]
       (reset! sock-ref browser-sock)
       (-> (.buildAsync (-> (.newWebSocketBuilder ^HttpClient @http-client)
                            (.header "Authorization" (str "Basic " cred))
                            (.header "Origin" origin)
                            (.subprotocols "tty" (into-array String [])))
                        uri up-listener)
           (.thenAccept
            (reify Consumer
              (accept [_ up]
                (reset! up-ref up)
                ;; Inject the real ttyd auth frame (TEXT), then flush anything
                ;; the browser sent while we were connecting.
                (.sendText ^WebSocket up
                           (str "{\"AuthToken\":\"" cred "\",\"columns\":80,\"rows\":24}") true)
                (doseq [^bytes b @pending]
                  (.sendBinary ^WebSocket up (ByteBuffer/wrap b) true))
                (reset! pending []))))
           (.exceptionally
            (reify java.util.function.Function
              (apply [_ _err]
                (when-let [s @sock-ref] (ws/close s))
                nil)))))
     :on-message
     (fn [_browser-sock message]
       ;; Browser frames are binary. Drop its own auth frame (leading '{') — we
       ;; authed upstream ourselves. Forward/queue INPUT ('0') and RESIZE ('1').
       (let [^bytes b (if (string? message)
                        (.getBytes ^String message "UTF-8")
                        (bb->bytes message))]
         (when (and (pos? (alength b)) (not= (aget b 0) LBRACE))
           (if-let [^WebSocket up @up-ref]
             (.sendBinary up (ByteBuffer/wrap b) true)
             (swap! pending conj b)))))
     :on-close
     (fn [_browser-sock _code _reason]
       (when-let [^WebSocket up @up-ref]
         (.sendClose up WebSocket/NORMAL_CLOSURE "")))}))

(defn handler
  "Ring handler upgrading the browser request to a `tty` WebSocket proxied to
   the session's container ttyd (`upstream` = {:host-port :ttyd-user :ttyd-pass})."
  [upstream]
  (fn [_req]
    {:ring.websocket/protocol "tty"
     :ring.websocket/listener (listener upstream)}))
