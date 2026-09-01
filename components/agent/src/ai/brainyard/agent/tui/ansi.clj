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
;; SGR 24 turns underline OFF specifically, leaving colour and weight alone —
;; unlike `reset`, which would drop whatever style the surrounding row set. That
;; is what lets a span be underlined INSIDE already-styled text (see
;; `links/decorate-row`) without repainting the rest of the row.
(def ^:const underline-off (str esc "24m"))
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
         ;; Boxed `Call` section: arg name is a dim label, the value pops
         ;; in the code color (values are often scripts / content).
         :tool/arg-name       [:dim]
         :tool/arg-value      [:bright-cyan]
         :spinner/active      [:bold :bright-yellow]
         :iter/marker-running [:bold :bright-cyan]
         :iter/marker-success [:bright-green]
         :iter/marker-failure [:bright-red]
         :iter/marker-done    [:dim]
         :iter/label          [:bold :bright-white]
         :iter/usage          [:dim]
         ;; What marks a clickable target — a link, a file location, a session
         ;; tab. Rebind this to tune how loud the affordance is; it is the one
         ;; lever for "too many underlines", and it moves every call site at
         ;; once.
         ;;
         ;; ONLY the cleanly-toggleable mods belong here: `:bold` `:dim`
         ;; `:italic` `:underline` `:reverse` (see `mod->off-code`). A COLOUR
         ;; has no "off" — ending it needs a `reset`, which would also discard
         ;; whatever styling the surrounding row had set, and the mark is
         ;; inserted MID-ROW into text we do not own. Bind a colour here and
         ;; `link-mark-off` cannot restore what came before it.
         :link/target         [:underline]
         ;; Scrollback search (Ctrl-F). Same restriction as `:link/target` and
         ;; for the same reason — these are inserted MID-ROW into text we do
         ;; not own, so only the cleanly-toggleable mods are legal.
         ;;
         ;; The two must be tellable apart at a glance: `:search/match` is the
         ;; quiet "there is one here", `:search/current` is where the viewport
         ;; is actually parked. Reverse is the loud one, so it goes to the
         ;; current hit rather than to all of them — and it is exactly
         ;; reversible (27), unlike a background colour, which is what makes it
         ;; usable mid-row at all.
         :search/match        [:underline]
         :search/current      [:reverse]}))

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

(def ^:private mod->off-code
  "Mod-keyword → the SGR parameter that turns JUST it off again.

   Only these five attributes can be ended precisely, which is why the
   `:link/target` binding is restricted to them. Note 22 clears bold AND dim
   together — the terminal has no separate code for either — so a `:dim` mark
   inside bold text ends the bold too. `:underline` (24) is the default for
   being the one attribute that is both quiet and exactly reversible."
  {:bold "22" :dim "22" :italic "23" :underline "24" :reverse "27"})

(defn mark-on
  "SGR that starts the mark bound to `token-id`. \"\" when colour is off or the
   token is unbound.

   A MARK is not the same thing as a style: it is one half of a pair, inserted
   into the middle of a row whose surrounding styling belongs to whoever wrote
   it. Only tokens bound to `mod->off-code` mods can be used this way — see the
   `:link/target` comment on the theme map."
  [token-id]
  (let [mods (get @!theme token-id)]
    (if (and @!color-enabled (seq mods)) (mods->ansi mods) "")))

(defn mark-off
  "SGR that ends the mark `mark-on` started for `token-id`, turning off exactly
   those attributes and leaving the surrounding row's styling alone. Unknown
   mods contribute nothing rather than forcing a `reset`, so a mis-bound theme
   degrades to a mark that does not end — visible, not corrupting."
  [token-id]
  (let [mods (get @!theme token-id)
        offs (distinct (keep mod->off-code mods))]
    (if (and @!color-enabled (seq offs))
      (str esc (str/join ";" offs) "m")
      "")))

(defn link-mark-on
  "SGR that starts the clickable-target mark, per the `:link/target` theme
   binding."
  []
  (mark-on :link/target))

(defn link-mark-off
  "SGR that ends the mark `link-mark-on` started."
  []
  (mark-off :link/target))

(defn link-mark
  "Wrap `s` in the clickable-target mark. For whole strings a caller owns
   (a tab label); `links/decorate-row` inserts the two halves itself when
   marking a span inside a row it does not own."
  [s]
  (str (link-mark-on) s (link-mark-off)))

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
(defn tool-arg-name       [s] (theme-style s :tool/arg-name))
(defn tool-arg-value      [s] (theme-style s :tool/arg-value))
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

;; Synchronized output (DEC private mode 2026). Between these two, a terminal
;; that understands them buffers everything and presents it as ONE frame instead
;; of painting as bytes arrive — so a repaint made of many cursor moves can never
;; be shown half-finished. Terminals that don't understand it ignore an unknown
;; private mode, which is why this is safe to emit unconditionally.
(def begin-sync (str esc "?2026h"))
(def end-sync   (str esc "?2026l"))
(def enter-alt-screen (str esc "?1049h"))
(def leave-alt-screen (str esc "?1049l"))
(def clear-screen     (str esc "2J"))

(defn set-scroll-region
  "Set DECSTBM scroll region from top to bottom (1-based, inclusive)."
  [top bottom]
  (str esc top ";" bottom "r"))

(def reset-scroll-region (str esc "r"))
(def erase-line (str esc "2K"))

;; Erase from the cursor to end of line, leaving everything to its LEFT intact.
;;
;; The difference from `erase-line` is a flicker difference, not a cosmetic one.
;; `erase-line` has to be emitted BEFORE a row's content, so every repaint is
;; blank-then-fill: two states the terminal can present, on every row, whether
;; or not the row's text actually changed. Emitting the content first and then
;; `erase-eol` overwrites the cells in place and clears only the tail the new
;; content is shorter than — one state, no blank frame. That matters because
;; DEC 2026 synchronized output is not universally in effect (tmux only wraps
;; output for clients whose terminfo advertises `Sync`), so the renderer cannot
;; rely on it to hide a two-phase paint.
;;
;; Emit `reset` before it when the row's own escapes may have left a background
;; colour set: erase honours BCE, and the cleared tail would otherwise be
;; painted in that colour.
(def erase-eol (str esc "0K"))

;; Scroll the DECSTBM region by n lines, discarding what falls off the far edge
;; and exposing blank lines at the near one. The CURSOR DOES NOT MOVE, so a
;; caller repaints the exposed rows by absolute position afterwards.
;;
;; This is how a renderer avoids rewriting a whole screen of text that merely
;; MOVED. Bottom-anchored content shifts up by one whenever a line is appended,
;; which changes what every row says while changing almost nothing about what
;; the screen shows; asking the terminal to move it costs one escape instead of
;; one rewrite per row.
;;
;; Both honour BCE — the exposed lines are filled with the current background —
;; so emit `reset` first unless that background is wanted.
;;
;; Safe only while the region really is what the caller thinks it is; these
;; move whatever DECSTBM currently spans, not a range passed in.
(defn scroll-up   "Scroll the region up n lines (content moves toward row 1)."   [n] (str esc n "S"))
(defn scroll-down "Scroll the region down n lines (content moves away from row 1)." [n] (str esc n "T"))

;; Alternate scroll mode — converts scroll wheel to arrow keys in alt screen.
;; Leaves mouse clicks unintercepted, so it is what the TUI falls back to when
;; mouse reporting is off (`:enable-mouse false`) and terminal text selection
;; must keep working with no modifier.
(def enable-alt-scroll  (str esc "?1007h"))
(def disable-alt-scroll (str esc "?1007l"))

;; Mouse reporting — button press/release, SGR-encoded coordinates.
;;
;; `?1000h` reports BUTTONS ONLY. 1002 (drag) and 1003 (any motion) additionally
;; stream an event per cell the pointer crosses; the TUI acts on clicks, so that
;; traffic buys nothing and costs a flooded input thread.
;;
;; `?1006h` is not optional. The default X10 encoding packs each coordinate into
;; a single byte as `32 + n`, so it cannot address a column past 223 — past that
;; a click silently reports the wrong cell. SGR sends decimal digits
;; (`ESC [ < btn ; col ; row M`) and has no ceiling.
;;
;; NOTE this SUPERSEDES `?1007h` rather than joining it: alternate-scroll only
;; synthesises arrow keys while mouse reporting is OFF. With 1000h on, the wheel
;; instead arrives as buttons 64/65, which `terminal/read-key!` maps back to
;; :scroll-up / :scroll-down. Enabling mouse reporting without that mapping
;; silently breaks scrolling, so the two changes belong in one commit.
;;
;; Cost of having this on: the terminal stops treating a plain click-drag as a
;; text selection, because the drag now belongs to the application. Every
;; terminal keeps a bypass modifier (Shift on most; Option on macOS xterm.js) —
;; which is why this is behind `:enable-mouse`.
(def enable-mouse  (str esc "?1000h" esc "?1006h"))
(def disable-mouse (str esc "?1006l" esc "?1000l"))

;; Bracketed paste mode — wraps pasted text with ESC[200~ ... ESC[201~.
;; Allows the application to distinguish typed Enter from pasted newlines.
(def enable-bracketed-paste  (str esc "?2004h"))
(def disable-bracketed-paste (str esc "?2004l"))

;; ============================================================================
;; OSC 52 — set the system clipboard from inside the terminal
;; ============================================================================
;;
;; `ESC ] 52 ; <selection> ; <base64-of-utf8> BEL`.  The *terminal emulator*
;; performs the write, so the text lands on the clipboard of the machine the
;; USER is sitting at — through ssh, through tmux, through ttyd/xterm.js.
;; That is the whole reason to prefer it over `pbcopy` and friends, which
;; copy to whichever host the process happens to run on.
;;
;; Fire-and-forget: there is no reply and no error channel, so a caller can
;; honestly report "sent", never "copied".  Support is not universal —
;; iTerm2, Kitty, Alacritty, WezTerm, Ghostty and Windows Terminal honor it;
;; macOS Terminal.app, GNOME Terminal and Konsole do not.
;;
;; NOTE the intro is OSC (`ESC ]`), not the CSI that `esc` holds (`ESC [`).

(def ^:const osc "\033]")

(def ^:const bel "\007")

(defn osc52-copy
  "Build the OSC 52 sequence asking the terminal to put `text` on the
   clipboard.  `selection` is xterm's selector: \"c\" = CLIPBOARD (the one
   ⌘V / Ctrl-V pastes), \"p\" = X11 PRIMARY (middle-click).  Terminals
   ignore selectors they don't implement, so \"c\" is the portable default.

   Returns \"\" for nil/blank text: an empty OSC 52 payload CLEARS the
   user's clipboard, which must never happen by accident."
  ([text] (osc52-copy text "c"))
  ([^String text ^String selection]
   (if (or (nil? text) (zero? (.length ^String text)))
     ""
     (let [b64 (.encodeToString (java.util.Base64/getEncoder)
                                (.getBytes ^String text "UTF-8"))]
       (str osc "52;" selection ";" b64 bel)))))
