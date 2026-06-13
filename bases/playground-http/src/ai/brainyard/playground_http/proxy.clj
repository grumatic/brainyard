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
  (:import [java.io ByteArrayInputStream]
           [java.net URI]
           [java.net.http HttpClient HttpClient$Version HttpRequest
            HttpResponse$BodyHandlers WebSocket WebSocket$Listener]
           [java.nio ByteBuffer]
           [java.util Base64]
           [java.util.function Consumer]))

(defonce ^:private http-client (delay (HttpClient/newHttpClient)))

(defn- b64 ^String [^String s]
  (.encodeToString (Base64/getEncoder) (.getBytes s "UTF-8")))

(defn http-proxy
  "Proxy a GET to the container's ttyd at `path` (e.g. \"/\" the self-contained
   client page, or \"/token\"), injecting basic auth so the browser never sees
   the workspace credential. Returns a Ring response. Used to serve ttyd's OWN
   web client same-origin (the workspace iframe), which renders the TUI exactly
   as ttyd intends — no hand-rolled xterm client to keep in lock-step."
  [{:keys [host-port ttyd-user ttyd-pass]} ^String path]
  (let [cred (b64 (str ttyd-user ":" ttyd-pass))
        resp (.send @http-client
                    (-> (HttpRequest/newBuilder (URI/create (str "http://127.0.0.1:" host-port path)))
                        ;; ttyd (libwebsockets) is HTTP/1.1 only; java.net.http
                        ;; defaults to HTTP/2 and the h2c upgrade makes ttyd drop
                        ;; the connection (EOF). Pin HTTP/1.1.
                        (.version HttpClient$Version/HTTP_1_1)
                        (.header "Authorization" (str "Basic " cred))
                        (.GET) (.build))
                    (HttpResponse$BodyHandlers/ofByteArray))
        ctype (-> (.headers resp) (.firstValue "content-type")
                  (.orElse "application/octet-stream"))]
    {:status  (.statusCode resp)
     :headers {"Content-Type" ctype}
     :body    (ByteArrayInputStream. (.body resp))}))

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
        ;; Reassembly: java.net.http may deliver ONE ttyd WS message across
        ;; several onBinary/onText calls (last=false until the final part). A
        ;; ttyd frame carries its command byte ('0'=OUTPUT) only at byte 0 and
        ;; the browser strips byte 0 of every frame it gets — so forwarding a
        ;; continuation part as its own frame eats a byte and corrupts the
        ;; escape sequences (garbled menu redraws). Buffer until `last`.
        bin-acc  (java.io.ByteArrayOutputStream.)
        txt-acc  (StringBuilder.)
        cred     (b64 (str ttyd-user ":" ttyd-pass))
        origin   (str "http://127.0.0.1:" host-port)
        uri      (URI. (str "ws://127.0.0.1:" host-port "/ws"))
        up-listener
        (reify WebSocket$Listener
          (onOpen [_ up] (.request up 1))
          ;; ttyd → browser: reassemble fragments, then forward the COMPLETE
          ;; message as one frame so the browser strips byte 0 exactly once.
          (onText [_ up data last]
            (.append txt-acc ^CharSequence data)
            (when last
              (let [s' (.toString txt-acc)]
                (.setLength txt-acc 0)
                (when-let [s @sock-ref] (ws/send s s'))))
            (.request up 1) (java.util.concurrent.CompletableFuture/completedFuture nil))
          (onBinary [_ up data last]
            (.write bin-acc (bb->bytes data))
            (when last
              (let [b (.toByteArray bin-acc)]
                (.reset bin-acc)
                (when-let [s @sock-ref] (ws/send s (ByteBuffer/wrap b)))))
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
