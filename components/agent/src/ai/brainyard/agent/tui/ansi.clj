;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.tui.ansi
  "Inline ANSI escape code constants and helpers for terminal colorization.
   No external dependencies — pure string wrapping.

   Semantic helpers (`success`, `failure`, `muted`, `tool-name`, etc.) and
   the iteration-block helpers (`iter-marker-running`, `tool-bullet`, …)
   resolve their styling through `!theme` — a binding atom mapping
   theme-token ids to vectors of style modifiers. The defaults match the
   pre-theme hardcoded behavior. Bases that load a theme call
   `set-theme!` (or `theme/propagate-to-ansi!` from
   `agent-tui-ui.theme`) to push fresh bindings."
  (:require [clojure.string :as str]))

;; ============================================================================
;; Color Enable/Disable
;; ============================================================================

(defonce !color-enabled (atom true))

(defn no-color!
  "Disable ANSI color codes globally (for environments that strip ANSI)."
  []
  (reset! !color-enabled false))

(defn color!
  "Re-enable ANSI color codes globally."
  []
  (reset! !color-enabled true))

;; ============================================================================
;; ANSI Escape Codes
;; ============================================================================

(def ^:const esc "\033[")

;; Reset
(def ^:const reset (str esc "0m"))

;; Styles
(def ^:const bold (str esc "1m"))
(def ^:const dim (str esc "2m"))
(def ^:const italic (str esc "3m"))
(def ^:const underline (str esc "4m"))
(def ^:const reverse-video (str esc "7m"))

;; Standard colors (foreground)
(def ^:const black (str esc "30m"))
(def ^:const red (str esc "31m"))
(def ^:const green (str esc "32m"))
(def ^:const yellow (str esc "33m"))
(def ^:const blue (str esc "34m"))
(def ^:const magenta (str esc "35m"))
(def ^:const cyan (str esc "36m"))
(def ^:const white (str esc "37m"))

;; Bright colors (foreground)
(def ^:const bright-black (str esc "90m"))
(def ^:const bright-red (str esc "91m"))
(def ^:const bright-green (str esc "92m"))
(def ^:const bright-yellow (str esc "93m"))
(def ^:const bright-blue (str esc "94m"))
(def ^:const bright-magenta (str esc "95m"))
(def ^:const bright-cyan (str esc "96m"))
(def ^:const bright-white (str esc "97m"))

;; Background colors
(def ^:const bg-black (str esc "40m"))
(def ^:const bg-bright-black (str esc "100m"))
(def ^:const bg-256 "256-color background. Usage: (str bg-256 \"N\" \"m\")" (str esc "48;5;"))

;; ============================================================================
;; Core Style Function
;; ============================================================================

(defn style
  "Wrap string `s` in ANSI codes. Returns plain string when color disabled.
   Usage: (style \"hello\" bold red)"
  [s & codes]
  (if @!color-enabled
    (str (apply str codes) s reset)
    s))

;; ============================================================================
;; Theme bindings
;;
;; A binding atom maps theme-token ids to vectors of mod-keywords (e.g.
;; `[:bold :bright-cyan]`). Semantic helpers below read from it via
;; `theme-style`. Defaults match the historical hardcoded behavior so
;; no consumer breaks before a theme is installed.
;; ============================================================================

(def ^:private mod->code
  "Mod-keyword → SGR parameter string. Mirrors the table in
   `agent-tui-ui.theme/mod->code` so a theme map from there can be
   pushed in via `set-theme!` without translation."
  {:reset "0" :bold "1" :dim "2" :italic "3" :underline "4" :reverse "7"
   :black "30" :red "31" :green "32" :yellow "33"
   :blue "34" :magenta "35" :cyan "36" :white "37"
   :bright-black "90" :bright-red "91" :bright-green "92" :bright-yellow "93"
   :bright-blue "94" :bright-magenta "95" :bright-cyan "96" :bright-white "97"
   :bg-black "40" :bg-red "41" :bg-green "42" :bg-yellow "43"
   :bg-blue "44" :bg-magenta "45" :bg-cyan "46" :bg-white "47"
   :bg-bright-black "100" :bg-bright-red "101" :bg-bright-green "102"
   :bg-bright-yellow "103" :bg-bright-blue "104" :bg-bright-magenta "105"
   :bg-bright-cyan "106" :bg-bright-white "107"})

(defonce !theme
  (atom {:role/heading        [:bold :bright-white]
         :status/success      [:bold :bright-green]
         :status/error        [:bold :bright-red]
         :status/warning      [:bold :bright-yellow]
         :status/info         [:bold :bright-blue]
         :status/running      [:bold :bright-cyan]
         :fg/muted            [:dim]
         :role/comment        [:dim]
         :role/code           [:bright-cyan]
         :role/body           []
         :tool/name           [:bold :cyan]
         :tool/bullet         [:cyan]
         :tool/done           [:bright-green]
         :tool/error          [:bright-red]
         :spinner/active      [:bold :bright-yellow]
         :iter/marker-running [:bold :bright-cyan]
         :iter/marker-success [:bright-green]
         :iter/marker-failure [:bright-red]
         :iter/marker-done    [:dim]
         :iter/label          [:bold :bright-white]
         :iter/usage          [:dim]}))

(defn set-theme!
  "Merge `bindings` (a {token-id mods-vec} map) into the theme atom.
   Missing tokens keep their prior value — partial themes only override
   what they bind. Called by bases after loading a theme file."
  [bindings]
  (when (map? bindings) (swap! !theme merge bindings)))

(defn current-theme
  "Snapshot of the active bindings. Test/diagnostic helper."
  []
  @!theme)

(defn- mods->ansi
  "Compose mods into a single SGR escape. Empty → empty string so
   `theme-style` can leave unstyled tokens unwrapped."
  [mods]
  (if (empty? mods)
    ""
    (str esc (str/join ";" (keep mod->code mods)) "m")))

(defn theme-style
  "Wrap `s` with the ANSI codes bound to `token-id`. Returns `s`
   unmodified when the token isn't bound, the binding is empty, or
   color is disabled — matches `style`'s no-color contract."
  [s token-id]
  (let [mods (get @!theme token-id)]
    (if (and @!color-enabled (seq mods))
      (str (mods->ansi mods) s reset)
      s)))

;; ============================================================================
;; Semantic Helpers (theme-aware)
;;
;; Existing call sites (~543 across the repo) pick up theme changes for
;; free — only the binding atom moves under them.
;; ============================================================================

(defn header  [s] (theme-style s :role/heading))
(defn success [s] (theme-style s :status/success))
(defn failure [s] (theme-style s :status/error))
(defn warning [s] (theme-style s :status/warning))
(defn muted   [s] (theme-style s :fg/muted))
(defn thought [s] (style s magenta))
(defn tool-name [s] (theme-style s :tool/name))
(defn answer-text [s] (theme-style s :role/heading))
(defn user-text
  "Format user input with ❯ prefix and dark background.
   Renders as: ❯ <message text> with bg-bright-black."
  [s]
  (style (str "❯ " s) bold bright-cyan bg-bright-black))
(defn observation-text [s] (style s blue))

;; ----------------------------------------------------------------------------
;; Iteration-block helpers (used by render-iteration-block-lines)
;; ----------------------------------------------------------------------------

(defn iter-marker-running [s] (theme-style s :iter/marker-running))
(defn iter-marker-success [s] (theme-style s :iter/marker-success))
(defn iter-marker-failure [s] (theme-style s :iter/marker-failure))
(defn iter-marker-done    [s] (theme-style s :iter/marker-done))
(defn iter-label          [s] (theme-style s :iter/label))
(defn iter-usage          [s] (theme-style s :iter/usage))
(defn tool-bullet         [s] (theme-style s :tool/bullet))
(defn tool-done           [s] (theme-style s :tool/done))
(defn tool-error          [s] (theme-style s :tool/error))
(defn spinner-active      [s] (theme-style s :spinner/active))

;; ============================================================================
;; Box-Drawing Characters
;; ============================================================================

(def ^:const h-line "\u2500")       ;; ─
(def ^:const v-line "\u2502")       ;; │
(def ^:const tl-corner "\u250C")    ;; ┌
(def ^:const tr-corner "\u2510")    ;; ┐
(def ^:const bl-corner "\u2514")    ;; └
(def ^:const br-corner "\u2518")    ;; ┘
(def ^:const check "\u2713")        ;; ✓
(def ^:const cross-mark "\u2717")   ;; ✗
(def ^:const arrow "\u2192")        ;; →
(def ^:const left-arrow "\u2190")   ;; ←
(def ^:const bullet "\u2022")       ;; •
(def ^:const ellipsis "\u2026")     ;; …

;; ============================================================================
;; Horizontal Rules
;; ============================================================================

(def ^:const default-rule-width 60)

(defn rule
  "Horizontal rule with optional centered label and width."
  ([]
   (rule nil nil))
  ([label]
   (rule label nil))
  ([label width]
   (let [w (or width default-rule-width)]
     (if label
       (let [label-str (str " " label " ")
             label-len (count label-str)
             left-len  (max 3 (quot (- w label-len) 2))
             right-len (max 3 (- w label-len left-len))
             left      (apply str (repeat left-len h-line))
             right     (apply str (repeat right-len h-line))]
         (if @!color-enabled
           (str (style left dim) (style label-str bold bright-white) (style right dim))
           (str left label-str right)))
       (if @!color-enabled
         (style (apply str (repeat w h-line)) dim)
         (apply str (repeat w "-")))))))

;; ============================================================================
;; Cursor & Screen Control
;; ============================================================================

(defn cursor-to
  "Move cursor to row, col (1-based)."
  [row col]
  (str esc row ";" col "H"))

(def save-cursor    (str esc "s"))
(def restore-cursor (str esc "u"))
(def hide-cursor    (str esc "?25l"))
(def show-cursor    (str esc "?25h"))
(def enter-alt-screen (str esc "?1049h"))
(def leave-alt-screen (str esc "?1049l"))
(def clear-screen     (str esc "2J"))

(defn set-scroll-region
  "Set DECSTBM scroll region from top to bottom (1-based, inclusive)."
  [top bottom]
  (str esc top ";" bottom "r"))

(def reset-scroll-region (str esc "r"))
(def erase-line (str esc "2K"))

;; Alternate scroll mode — converts scroll wheel to arrow keys in alt screen.
;; Leaves mouse clicks unintercepted so terminal text selection works normally.
(def enable-alt-scroll  (str esc "?1007h"))
(def disable-alt-scroll (str esc "?1007l"))

;; Bracketed paste mode — wraps pasted text with ESC[200~ ... ESC[201~.
;; Allows the application to distinguish typed Enter from pasted newlines.
(def enable-bracketed-paste  (str esc "?2004h"))
(def disable-bracketed-paste (str esc "?2004l"))
