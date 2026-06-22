;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent-tui.oauth-render
  "Rich TUI renderer for OAuth device/auth verification prompts.

   Registered with the agent MCP client at startup (`register!`); the client's
   device/auth-code login routes its `:on-user-prompt`/`:on-status` callbacks
   here instead of the headless stderr fallback. Draws a code box (verification
   URL + user code + scopes), optionally a terminal QR for
   `verification_uri_complete` (best-effort via the `qrencode` CLI, gated by the
   `:oauth-qr?` config), and streams poll-status lines. Never renders a token.

   Emits through the same `tui-session/emit!` + `layout/restore-input-cursor!`
   path the async MCP `on-settle` banner uses, so prompts land cleanly while the
   user sits at the input line. See docs/design/oauth.md §7.3."
  (:require [ai.brainyard.agent-tui.session :as tui-session]
            [ai.brainyard.agent-tui.layout :as layout]
            [ai.brainyard.agent.interface :as agent]
            [ai.brainyard.agent.interface.tui.ansi :as ansi]
            [clojure.java.shell :as shell]
            [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; Box drawing — lay out from PLAIN text (so widths are correct), colorize after
;; by substituting exact substrings (ANSI codes don't change visible width).
;; ---------------------------------------------------------------------------

(defn ^:private pad [s w] (str s (apply str (repeat (max 0 (- w (count s))) " "))))

;; ansi/bold, /cyan, /underline are raw escape-code strings (not fns); wrap with
;; reset. ANSI codes have zero visible width, so this never disturbs box layout.
(defn ^:private emph [codes s] (str codes s ansi/reset))

(defn frame
  "Box-draw `lines` (plain strings). Returns the framed plain string."
  [lines]
  (let [iw     (reduce max 0 (map count lines))
        bar    (apply str (repeat (+ iw 2) "─"))
        top    (str "┌" bar "┐")
        bottom (str "└" bar "┘")
        rows   (map (fn [l] (str "│ " (pad l iw) " │")) lines)]
    (str/join "\n" (concat [top] rows [bottom]))))

(defn ^:private mins [secs]
  (when (and secs (pos? secs)) (str (long (Math/ceil (/ secs 60.0))) "m")))

(defn device-box
  "Plain + colorized code box for a device-flow prompt."
  [{:keys [account-id verification_uri user_code expires_in scopes]}]
  (let [scope-str (when (seq scopes) (str/join " " scopes))
        title     (str "Authorize \"" account-id "\"")
        lines     (cond-> [title
                           ""
                           "1. Open this URL in any browser:"
                           (str "     " verification_uri)
                           ""
                           "2. Enter this code:"
                           (str "     " user_code)]
                    scope-str        (conj "" (str "Scopes:  " scope-str))
                    (mins expires_in) (conj (str "Expires: in " (mins expires_in))))
        boxed     (frame lines)]
    (-> boxed
        (str/replace-first title (emph ansi/bold title))
        (str/replace-first user_code (emph (str ansi/bold ansi/cyan) user_code))
        (str/replace-first verification_uri (emph ansi/underline verification_uri)))))

(defn paste-box
  "Plain + colorized box for the headless authorization-code paste fallback."
  [{:keys [account-id authorize_uri scopes]}]
  (let [scope-str (when (seq scopes) (str/join " " scopes))
        title     (str "Authorize \"" account-id "\"")
        lines     (cond-> [title
                           ""
                           "1. Open this URL in any browser:"
                           (str "     " authorize_uri)
                           ""
                           "2. Paste the code back here when prompted."]
                    scope-str (conj "" (str "Scopes:  " scope-str)))]
    (-> (frame lines)
        (str/replace-first title (emph ansi/bold title))
        (str/replace-first authorize_uri (emph ansi/underline authorize_uri)))))

(defn loopback-box
  "Plain + colorized box for the loopback flow — the browser opens and the code
   is captured automatically; nothing to paste."
  [{:keys [account-id authorize_uri scopes]}]
  (let [scope-str (when (seq scopes) (str/join " " scopes))
        title     (str "Authorize \"" account-id "\"")
        lines     (cond-> [title
                           ""
                           "Your browser is opening to authorize."
                           "If it doesn't, open this URL:"
                           (str "     " authorize_uri)
                           ""
                           "Approve there — this connects automatically."]
                    scope-str (conj "" (str "Scopes:  " scope-str)))]
    (-> (frame lines)
        (str/replace-first title (emph ansi/bold title))
        (str/replace-first authorize_uri (emph ansi/underline authorize_uri)))))

;; ---------------------------------------------------------------------------
;; QR (best-effort via `qrencode`, gated by :oauth-qr?)
;; ---------------------------------------------------------------------------

(defn ^:private qrencode-available? []
  (try (zero? (:exit (shell/sh "sh" "-c" "command -v qrencode")))
       (catch Throwable _ false)))

(defn qr-block
  "Terminal QR for `url` via `qrencode -t UTF8`, or nil if disabled / unavailable."
  [url]
  (when (and url
             (try (agent/get-config :oauth-qr?) (catch Throwable _ true))
             (qrencode-available?))
    (try
      (let [{:keys [exit out]} (shell/sh "qrencode" "-t" "UTF8" "-m" "1" url)]
        (when (and (zero? exit) (not (str/blank? out)))
          (str (ansi/muted "  Scan to authorize:") "\n" out)))
      (catch Throwable _ nil))))

;; ---------------------------------------------------------------------------
;; Boot-time emit gate
;;
;; A server that connects during boot (background future) can fire its device
;; prompt before `run!` enters the alt-screen live loop — those emits would land
;; in the hidden primary buffer. `run!` arms the gate before start! and flushes
;; it once the live loop is up, so a prompt fired in that window is buffered and
;; replayed; prompts after the loop is live emit straight through. REPL/inline
;; callers never arm it, so emits pass through unbuffered.
;; ---------------------------------------------------------------------------

(defonce ^:private !gate (atom {:ready? true :buffer []}))

(defn arm-deferral!
  "Buffer subsequent OAuth emits until `flush-deferred!` (called by run! before
   start! so boot-time prompts survive the alt-screen transition)."
  []
  (swap! !gate assoc :ready? false))

(defn ^:private raw-emit! [s]
  (tui-session/emit! s)
  (layout/restore-input-cursor!))

(defn flush-deferred!
  "Open the gate and replay anything buffered while it was armed (called by run!
   once the live loop is up). Idempotent."
  []
  (let [[old _] (swap-vals! !gate assoc :ready? true :buffer [])]
    (doseq [s (:buffer old)] (raw-emit! s))))

;; ---------------------------------------------------------------------------
;; Render dispatch
;; ---------------------------------------------------------------------------

(defn ^:private emit! [s]
  ;; swap-vals! makes the ready-check and the buffer-append atomic against a
  ;; concurrent flush-deferred!, so a prompt racing the flush is never lost.
  (let [[old _] (swap-vals! !gate (fn [st] (if (:ready? st) st (update st :buffer conj s))))]
    (when (:ready? old) (raw-emit! s))))

(defn render
  "Renderer registered with the agent MCP client. Dispatches on `:event`."
  [{:keys [event account-id verification_uri user_code verification_uri_complete authorize_uri mode]
    :as ev}]
  (case event
    :prompt
    (do
      (cond
        (and verification_uri user_code)       (emit! (device-box ev))
        (and authorize_uri (= :loopback mode)) (emit! (loopback-box ev))
        authorize_uri                          (emit! (paste-box ev)))
      (when-let [qr (qr-block verification_uri_complete)]
        (emit! qr)))

    :slow-down
    (emit! (ansi/muted "  ↳ provider asked to slow down — still waiting…"))

    :authorized
    (emit! (ansi/success (str ansi/check " authorized — " account-id " connected")))

    ;; :pending fires every poll; the box already says "waiting" — stay quiet.
    nil))

;; ---------------------------------------------------------------------------
;; Paste-flow code input
;;
;; The headless authorization-code flow shows an authorize URL (paste-box, via
;; the :prompt event) then blocks waiting for the user to paste the code. The
;; connect runs on a background future, so it parks on a promise; the next line
;; the user submits is delivered here (handle-input-line consults consume-code!)
;; instead of going to the agent as a chat turn.
;; ---------------------------------------------------------------------------

(defonce ^:private !pending-code (atom nil))   ; nil | {:account-id :promise}
(def ^:private read-code-timeout-ms (* 5 60 1000))

(defn read-code-provider
  "Registered with the MCP client as the paste-flow read-code source. Prompts
   the user to paste, blocks up to 5 min for the next submitted line, returns it."
  [account-id]
  (let [p (promise)]
    (reset! !pending-code {:account-id account-id :promise p})
    (emit! (ansi/muted (str "  Paste the authorization code for \"" account-id
                            "\" here and press Enter:")))
    (let [code (deref p read-code-timeout-ms ::timeout)]
      (reset! !pending-code nil)
      (if (= ::timeout code)
        (throw (ex-info "Timed out waiting for the pasted authorization code"
                        {:account-id account-id}))
        code))))

(defn pending-code?
  "True when a paste flow is waiting for the user to submit a code."
  []
  (some? @!pending-code))

(defn consume-code!
  "If a paste flow is waiting, deliver `input` to it and return true (so the
   caller treats the line as the code, not a chat turn). Blank input is ignored."
  [input]
  (when-let [{:keys [promise]} @!pending-code]
    (when-not (str/blank? input)
      (deliver promise (str/trim input))
      (emit! (ansi/muted "  Code received — completing authorization…"))
      true)))

(defn register!
  "Install this renderer + paste-code provider as the MCP client's OAuth sinks."
  []
  (agent/set-oauth-prompt-renderer! render)
  (agent/set-oauth-read-code! read-code-provider))
