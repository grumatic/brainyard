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
;; Render dispatch
;; ---------------------------------------------------------------------------

(defn ^:private emit! [s]
  (tui-session/emit! s)
  (layout/restore-input-cursor!))

(defn render
  "Renderer registered with the agent MCP client. Dispatches on `:event`."
  [{:keys [event account-id verification_uri user_code verification_uri_complete authorize_uri]
    :as ev}]
  (case event
    :prompt
    (do
      (cond
        (and verification_uri user_code) (emit! (device-box ev))
        authorize_uri                    (emit! (paste-box ev)))
      (when-let [qr (qr-block verification_uri_complete)]
        (emit! qr)))

    :slow-down
    (emit! (ansi/muted "  ↳ provider asked to slow down — still waiting…"))

    :authorized
    (emit! (ansi/success (str ansi/check " authorized — " account-id " connected")))

    ;; :pending fires every poll; the box already says "waiting" — stay quiet.
    nil))

(defn register!
  "Install this renderer as the agent MCP client's OAuth prompt sink."
  []
  (agent/set-oauth-prompt-renderer! render))
