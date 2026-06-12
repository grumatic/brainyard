;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
(ns ai.brainyard.playground-ui.terminal
  "The one imperative corner of the UI: an xterm.js terminal bridged to the
   per-session ttyd WebSocket. It is fully contained behind Replicant life-cycle
   hooks — `terminal` returns a plain hiccup node; on mount we build xterm +
   open the socket, on unmount we tear both down. The pure render path only ever
   sees connection status in `state/:conn`.

   ttyd WebSocket protocol (subprotocol \"tty\"): messages are bytes whose first
   character is a command. Client→server: '0' INPUT, '1' RESIZE {columns,rows}.
   Server→client: '0' OUTPUT, '1' SET_WINDOW_TITLE, '2' SET_PREFERENCES. The
   FIRST client message is a JSON auth frame: {\"AuthToken\": \"...\"}.

   xterm.js + addon-fit load as UMD globals from a CDN in index.html, NOT bundled
   through Closure: the advanced compiler chokes on xterm's minified ES module
   (\"Illegal variable reference before declaration\"). The CDN version MUST match
   `@xterm/*` in package.json (kept for dev/lockfile only)."
  (:require [ai.brainyard.playground-ui.api :as api]
            [ai.brainyard.playground-ui.state :as state]))

(def ^:private Terminal js/Terminal)             ; window.Terminal — the class
(def ^:private FitAddon (.-FitAddon js/FitAddon)) ; window.FitAddon.FitAddon

(def ^:private OUTPUT "0")   ; server -> client
(def ^:private INPUT  "0")   ; client -> server
(def ^:private RESIZE "1")   ; client -> server

(def ^:private enc (js/TextEncoder.))

(defn- ws-url [id]
  (let [scheme (if (= "https:" (.. js/location -protocol)) "wss:" "ws:")]
    (str scheme "//" (.. js/location -host) "/api/sessions/" id "/tty")))

(defn- ws-open? [ws]
  (and ws (= 1 (.-readyState ws))))           ; WebSocket.OPEN

(defn- send! [ws ^string s]
  (when (ws-open? ws)
    (.send ws (.encode enc s))))

(defn- send-resize! [ws term]
  (send! ws (str RESIZE (js/JSON.stringify
                         #js {:columns (.-cols term) :rows (.-rows term)}))))

(defn- set-conn! [id status]
  (swap! state/app-state assoc-in [:conn id] status))

(defn- connect!
  "Mint a per-socket token, open the ttyd WS, and wire it to `term`. Stores the
   live socket in `ws-ref` so resize/unmount can reach it."
  [id term fit ws-ref]
  (set-conn! id :connecting)
  (-> (api/tty-token! id)
      (.then
       (fn [{:keys [token]}]
         (let [ws (js/WebSocket. (ws-url id) #js ["tty"])]
           (set! (.-binaryType ws) "arraybuffer")
           (reset! ws-ref ws)
           (set! (.-onopen ws)
                 (fn [_]
                   (set-conn! id :open)
                   (send! ws (js/JSON.stringify #js {:AuthToken token})) ; auth frame first
                   (.fit fit)
                   (send-resize! ws term)))
           (set! (.-onmessage ws)
                 (fn [ev]
                   (let [bytes (js/Uint8Array. (.-data ev))
                         cmd   (.fromCharCode js/String (aget bytes 0))]
                     (when (= cmd OUTPUT)
                       (.write term (.subarray bytes 1))))))
           (set! (.-onclose ws) (fn [_] (set-conn! id :closed)))
           (set! (.-onerror ws) (fn [_] (set-conn! id :error))))))
      (.catch (fn [_] (set-conn! id :error)))))

(defn- on-mount
  "Returns a life-cycle hook closure (fires once when the node mounts)."
  [id]
  (fn [{:replicant/keys [node remember]}]
    (let [term   (Terminal. #js {:fontFamily  "ui-monospace, monospace"
                                 :fontSize    13
                                 :cursorBlink true
                                 :theme       #js {:background "#0b0e14"}})
          fit    (FitAddon.)
          ws-ref (atom nil)
          on-win-resize (fn [_]
                          (.fit fit)
                          (send-resize! @ws-ref term))]
      (.loadAddon term fit)
      (.open term node)
      (.fit fit)
      ;; keystrokes -> server (guarded until the socket is open)
      (.onData term (fn [data] (send! @ws-ref (str INPUT data))))
      (js/window.addEventListener "resize" on-win-resize)
      (connect! id term fit ws-ref)
      ;; hand state to on-unmount via Replicant's WeakMap-backed memory
      (remember {:term term :ws-ref ws-ref :on-win-resize on-win-resize}))))

(defn- on-unmount [{:replicant/keys [memory]}]
  (when-let [{:keys [term ws-ref on-win-resize]} memory]
    (js/window.removeEventListener "resize" on-win-resize)
    (when-let [ws @ws-ref] (.close ws))
    (.dispose term)))

(defn terminal
  "Hiccup node hosting the xterm.js terminal for session `id`. The stable
   `:replicant/key` keeps it mounted across re-renders, so the agent's live
   session (running under `by --web-tmux`) is never torn down by a UI update."
  [id]
  [:div.terminal-host
   {:replicant/key       (str "terminal-" id)
    :replicant/on-mount  (on-mount id)
    :replicant/on-unmount on-unmount}])
