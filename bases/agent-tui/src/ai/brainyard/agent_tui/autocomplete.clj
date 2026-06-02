;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent-tui.autocomplete
  "Autocomplete menu and raw line-reading for the TUI."
  (:require [ai.brainyard.agent-tui.layout :as layout]
            [ai.brainyard.agent-tui.session :as tui-session]
            [ai.brainyard.agent-tui.sessions :as sessions]
            [ai.brainyard.agent-tui.input :as input]
            [ai.brainyard.agent-tui.terminal :as terminal]
            [ai.brainyard.agent-tui.display-block-ui :as block-ui]
            [ai.brainyard.agent.interface.tui.ansi :as ansi]
            [ai.brainyard.agent.interface.tui.format :as fmt]
            [ai.brainyard.agent.interface :as agent]
            [ai.brainyard.agent-tui-persist.interface :as persist]
            [ai.brainyard.clj-llm.interface :as clj-llm]
            [clojure.string :as str]
            [clojure.java.io :as io])
  (:import [java.io InputStream]))

;; ============================================================================
;; Autocomplete Menu
;; ============================================================================

(def tui-command-defs
  "All TUI slash commands with descriptions for autocomplete menu.
   Derived from the canonical command-registry in format.clj."
  (mapv (fn [[cmd _args desc]] [cmd desc]) fmt/command-registry))

(defn- tool-command-defs
  "Generate command defs from !tool-defs for autocomplete under a given prefix
   (`/` for slash-commands, `:` for colon-commands). Includes <prefix><id> plus
   one <prefix><alias> entry per alias."
  [^String prefix]
  (into []
        (mapcat (fn [[id {:keys [meta]}]]
                  (let [desc (or (:description meta) "")]
                    (into [[(str prefix (name id)) desc]]
                          (map (fn [a] [(str prefix a) desc]))
                          (:aliases meta)))))
        @agent/!tool-defs))

(defn prefix-first-sort-key
  "Sort key for ordering autocomplete entries — entries whose command
   name starts with `query-body` come first, then alphabetical tiebreak.
   Strips a leading `/` or `:` from the cmd name before comparing so
   `\"/help\"` and `\"he\"` align properly. `query-body` is expected to
   already be lowercased."
  [cmd query-body]
  (let [lc       (str/lower-case (str cmd))
        stripped (str/replace lc #"^[/:]" "")]
    [(if (str/starts-with? stripped query-body) 0 1)
     lc]))

(defn filter-commands
  "Partial-match autocomplete entries. The leading `/` or `:` selects the
   candidate pool (slash-commands match the TUI registry + tools; colon-commands
   match only tools) and is stripped from the query; the remainder is matched
   as a case-insensitive substring against the command name only. Results are
   sorted so entries whose command name starts with the query come first, then
   alphabetically within each bucket.

   Note: description text is intentionally NOT searched. Matching descriptions
   pulled in unrelated commands and obscured the prefix-first signal (e.g.,
   typing `/he` would keep `/clear` visible because its description contains
   `history`)."
  [prefix]
  (let [pre-char     (when (seq prefix) (first prefix))
        query-body   (str/lower-case
                      (if (#{\/ \:} pre-char) (subs prefix 1) prefix))
        all-commands (if (= pre-char \:)
                       (tool-command-defs ":")
                       (into tui-command-defs (tool-command-defs "/")))]
    (->> all-commands
         (filter (fn [[cmd _desc]]
                   (or (str/blank? query-body)
                       (str/includes? (str/lower-case cmd) query-body))))
         (sort-by (fn [[cmd _]] (prefix-first-sort-key cmd query-body)))
         vec)))

(defn available-models
  "Return up to `n` popular models filtered by available auth.
   - Providers with an :api-key-env shown only when that env var is set.
   - :bedrock shown only when an AWS credential source is detected.
   - No-auth providers (:claude-code, :ollama, :apple-fm) always shown."
  [n]
  (let [all-models  (clj-llm/get-popular-models)
        provs       clj-llm/providers
        no-auth?    #{:claude-code :ollama :apple-fm}
        has-auth?   (fn [{:keys [provider]}]
                      (cond
                        (no-auth? provider)   true
                        (= :bedrock provider) (clj-llm/aws-credentials-detected?)
                        :else
                        (let [env-var (get-in provs [provider :api-key-env])]
                          (or (nil? env-var)
                              (some? (System/getenv env-var))))))]
    (->> all-models
         (filter has-auth?)
         (take n)
         vec)))

(defn- format-file-size
  "Format byte count as human-readable string."
  [bytes]
  (cond
    (< bytes 1024)        (str bytes " B")
    (< bytes 1048576)     (format "%.1f KB" (/ (double bytes) 1024))
    (< bytes 1073741824)  (format "%.1f MB" (/ (double bytes) 1048576))
    :else                 (format "%.1f GB" (/ (double bytes) 1073741824))))

(defn list-path-matches
  "Given a prefix string (without @), return [[display desc] ...] of matching
   files and directories relative to the current working directory."
  [prefix]
  (let [prefix    (or prefix "")
        ;; Split prefix into parent-dir and name-prefix
        [parent-dir name-prefix]
        (if (str/ends-with? prefix "/")
          [prefix ""]
          (let [last-slash (str/last-index-of prefix "/")]
            (if last-slash
              [(subs prefix 0 (inc last-slash)) (subs prefix (inc last-slash))]
              ["" prefix])))
        dir-file  (if (str/blank? parent-dir)
                    (io/file ".")
                    (io/file parent-dir))
        children  (when (.isDirectory dir-file)
                    (seq (.listFiles dir-file)))
        lc-prefix (str/lower-case name-prefix)
        filtered  (->> children
                       (filter (fn [^java.io.File f]
                                 (let [n (.getName f)]
                                   (and (not (str/starts-with? n "."))
                                        (str/starts-with? (str/lower-case n) lc-prefix)))))
                       (sort-by (fn [^java.io.File f]
                                  [(if (.isDirectory f) 0 1)
                                   (str/lower-case (.getName f))]))
                       (take 50))]
    (mapv (fn [^java.io.File f]
            (let [is-dir? (.isDirectory f)
                  display (str parent-dir (.getName f) (when is-dir? "/"))
                  desc    (if is-dir? "dir" (format-file-size (.length f)))]
              [display desc]))
          filtered)))

(defn extract-at-token
  "Scan backwards from cursor-pos in buffer string to find an @-token.
   Returns [start-idx token-str] or nil. The @ must be at position 0 or
   preceded by whitespace."
  [^String s cursor-pos]
  (when (and (pos? (count s)) (pos? cursor-pos))
    (let [region (subs s 0 cursor-pos)
          at-idx (str/last-index-of region "@")]
      (when (and at-idx
                 (or (zero? at-idx)
                     (Character/isWhitespace (.charAt s (dec at-idx)))))
        (let [token (subs s at-idx cursor-pos)]
          ;; Ensure no whitespace inside the token
          (when-not (re-find #"\s" (subs token 1))
            [at-idx token]))))))

(defn- menu-reserved-rows
  "Menu popover always reserves 30% of screen rows (fullscreen mode).
   Minimum 1 row; `recalc-layout-rows!` additionally clamps to `(- rows 7)`
   to preserve chrome."
  []
  (let [rows (or (:rows @layout/!layout) 24)]
    (max 1 (long (Math/ceil (* rows 0.30))))))

(defn- menu-item-rows
  "Number of menu rows available for item display. The last reserved
   row is for the scroll indicator (or blank when nothing is hidden).
   Floors at 1 so very small terminals still show one item."
  []
  (max 1 (dec (menu-reserved-rows))))

(defn format-scroll-indicator
  "Build the muted scroll-state line that occupies the last reserved
   row of the autocomplete menu.

   `hidden-above` — items scrolled past the top.
   `hidden-below` — items below the visible window.
   `width`        — terminal cols to right-align within.

   Returns a styled string ready to paint, or an empty string when
   nothing is hidden in either direction (the caller renders a blank
   row in that case to keep menu height stable)."
  [hidden-above hidden-below width]
  (let [parts (cond-> []
                (pos? hidden-above) (conj (str "↑ " hidden-above))
                (pos? hidden-below) (conj (str "↓ " hidden-below)))
        text  (when (seq parts)
                (str (str/join " · " parts) " more"))]
    (if (str/blank? text)
      ""
      (let [pad (max 0 (- width (count text)))]
        (str (apply str (repeat pad " "))
             (ansi/muted text))))))

;; ============================================================================
;; Sub-Menu Registry
;; ============================================================================

;; Sorted map of command prefix → submenu spec.
;; Spec keys: :items (static), :items-fn (dynamic), :custom-fn (escape hatch)
(defonce !submenu-registry (atom (sorted-map)))

(defn register-submenu!
  "Register a sub-menu spec for a command prefix."
  [prefix spec]
  (swap! !submenu-registry assoc prefix spec))

(defn- resolve-submenu
  "Find the best-matching submenu spec for a buffer string.
   Tries longest prefix first (reverse sorted-map)."
  [s]
  (some (fn [[prefix spec]]
          (let [with-space (str prefix " ")]
            (when (str/starts-with? s with-space)
              [prefix spec (subs s (count with-space))])))
        (reverse (seq @!submenu-registry))))

(defn- get-submenu-items
  "Resolve items from a submenu spec."
  [spec buffer-str]
  (cond
    (:custom-fn spec) ((:custom-fn spec) buffer-str)
    (:items-fn spec)  ((:items-fn spec))
    (:items spec)     (:items spec)
    :else             []))

;; --- Item provider functions ---

(defn- model-menu-items
  "Build [[display desc] ...] for /model submenu."
  []
  (let [models    (available-models 50)
        cur-model (:model (clj-llm/get-default-lm))]
    (mapv (fn [{:keys [model description]}]
            [(str "/model " model)
             (str description
                  (when (= model cur-model) " \u2190 current"))])
          models)))

(defn- config-menu-items
  "Build [[display desc] ...] for /config submenu."
  []
  (let [agent-obj (:agent @tui-session/!tui-state)
        snapshot  (when agent-obj (agent/get-config-snapshot agent-obj))]
    (mapv (fn [[k {:keys [type default]}]]
            (let [current  (get snapshot k default)
                  changed? (not= current default)]
              [(str "/config " (name k))
               (str "(" type ") = " current
                    (when changed?
                      (str " (default: " default ")")))]))
          (sort-by key agent/config-schema))))

(defn- kw->str
  "Convert a keyword to its full string representation (without colon).
   Handles namespaced keywords: :ns/name → \"ns/name\"."
  [kw]
  (subs (str kw) 1))

(defn- agent-new-menu-items
  "Build [[display desc] ...] for /agent new submenu."
  []
  (let [agents ((requiring-resolve 'ai.brainyard.agent-tui.commands/available-agent-types))]
    (mapv (fn [{:keys [id description]}]
            [(str "/agent new " (kw->str id)) description])
          agents)))

(defn- agent-switch-menu-items
  "Build [[display desc] ...] for /agent switch submenu."
  []
  (let [current-id (tui-session/get-active-agent-id)
        instances  ((requiring-resolve 'ai.brainyard.agent-tui.commands/session-instances))]
    (when (> (count instances) 1)
      (mapv (fn [ag]
              (let [ag-id (:agent-id ag)
                    status (get-in @(:!state ag) [:status])
                    msg-cnt (count (agent/get-messages @(:!session ag)))]
                [(str "/agent switch " (kw->str ag-id))
                 (str (when status (str (name status) " "))
                      msg-cnt " msgs"
                      (when (= ag-id current-id) " \u2190 current"))]))
            (sort-by #(str (:agent-id %)) instances)))))

(defn- session-new-agent-items
  "Build [[display desc] ...] for /session new submenu."
  []
  (let [agents ((requiring-resolve 'ai.brainyard.agent-tui.commands/available-agent-types))]
    (mapv (fn [{:keys [id description]}]
            [(str "/session new " (kw->str id)) description])
          agents)))

(defn- mcp-submenu-fn
  "Custom handler for /mcp two-level completion."
  [buffer-str]
  (let [prefix    (str/lower-case (subs buffer-str 5)) ;; strip "/mcp "
        parts     (str/split prefix #"\s+" 2)
        srv-part  (first parts)
        act-part  (second parts)
        configured (agent/list-configured-servers)
        active     (set (agent/list-active-clients))]
    (if act-part
      ;; Second level: show actions for selected server
      (let [actions  [["start" "Connect server"]
                      ["stop"  "Disconnect server"]
                      ["status" "Show server status"]]
            all     (mapv (fn [[act desc]]
                            [(str "/mcp " srv-part " " act) desc])
                          actions)]
        (if (str/blank? act-part)
          all
          (filterv (fn [[cmd _]]
                     (str/includes?
                      (str/lower-case cmd) (str/lower-case (str "/mcp " srv-part " " act-part))))
                   all)))
      ;; First level: show server names
      (let [all (mapv (fn [sn]
                        [(str "/mcp " sn)
                         (if (contains? active sn) "connected" "disconnected")])
                      configured)]
        (if (str/blank? srv-part)
          all
          (filterv (fn [[cmd _]]
                     (str/includes? (str/lower-case cmd) (str/lower-case (str "/mcp " srv-part))))
                   all))))))

;; --- Registry initialization ---

(defn- init-static-submenus!
  "Populate submenu registry from command-registry completion hints."
  []
  (doseq [[cmd _args _desc hints] fmt/command-registry
          :when (:completions hints)]
    (let [items (mapv (fn [[val desc]] [(str cmd " " val) desc])
                      (:completions hints))]
      (register-submenu! cmd {:items items}))))

(defn- init-dynamic-submenus!
  "Register submenus that need runtime data."
  []
  (register-submenu! "/model" {:items-fn model-menu-items})
  (register-submenu! "/config" {:items-fn config-menu-items})
  (register-submenu! "/sandbox" {:items-fn (fn []
                                             (into [["/sandbox eval" "Eval Clojure code in sandbox"]]
                                                   agent/sandbox-menu-items))})
  (register-submenu! "/mcp" {:custom-fn mcp-submenu-fn})
  (register-submenu! "/agent new" {:items-fn agent-new-menu-items})
  (register-submenu! "/agent switch" {:items-fn agent-switch-menu-items})
  (register-submenu! "/session new" {:items-fn session-new-agent-items}))

(defn init-submenus!
  "Initialize the submenu registry. Call at TUI startup."
  []
  (reset! !submenu-registry (sorted-map))
  (init-static-submenus!)
  (init-dynamic-submenus!))

;; ============================================================================
;; Menu Rendering
;; ============================================================================

(defn- normalize-desc
  "Collapse newlines + tabs + runs of whitespace to single spaces so the
   description renders on exactly one row."
  [desc]
  (-> (or desc "")
      (str/replace #"\s+" " ")
      str/trim))

(defn- truncate-to-width
  "Truncate s so its display-width fits in max-w columns. If truncated,
   appends a [+N chars] indicator that itself fits within max-w."
  [s ^long max-w]
  (let [s (str s)
        full-w (fmt/display-width s)]
    (if (or (<= full-w max-w) (zero? max-w))
      s
      ;; Need to truncate. Reserve space for indicator " [+N chars]".
      (let [hidden-count (- (count s) 0)  ;; placeholder; computed below
            ;; We choose truncation char count by trying decreasing keep-lengths
            ;; until both kept text and indicator fit within max-w.
            try-fit
            (fn [keep-chars]
              (let [kept (subs s 0 (max 0 keep-chars))
                    hidden (- (count s) keep-chars)
                    indicator (str " [+" hidden " chars]")
                    total-w (+ (fmt/display-width kept)
                               (fmt/display-width indicator))]
                (when (<= total-w max-w)
                  (str kept indicator))))
            ;; Binary-search-ish: start from a sensible guess and shrink.
            ;; Keep ~max-w - 12 chars (rough room for indicator), then refine.
            initial (max 0 (- max-w 12))]
        (or (some try-fit (range initial -1 -1))
            ;; Fallback: just hard-truncate to max-w
            (let [end (min (count s) max-w)]
              (subs s 0 end)))))))

(defn draw-autocomplete-menu!
  "Render autocomplete menu as a bottom-anchored popover. Reserves vis-count
   rows at the bottom of the screen (shifts chrome up, shrinks scroll region)
   and renders menu items in those rows. Scrollback content remains visible.
   items is [[cmd desc] ...], selected is 0-based index (-1 = none),
   scroll-offset is first visible index, cursor-pos/buf-str locate the cursor.
   Marks the popover as active so concurrent live-block ticker writes are
   deferred (prevents flicker).
   Each item description is collapsed to a single line and truncated with
   [+N chars] if it would exceed the available row width."
  [items selected scroll-offset cursor-pos buf-str]
  (if (layout/fullscreen?)
    (let [reserved     (menu-reserved-rows)
          item-rows    (menu-item-rows)
          n            (count items)
          vis-start    scroll-offset
          vis-end      (min n (+ scroll-offset item-rows))
          vis-items    (subvec (vec items) vis-start vis-end)
          vis-count    (count vis-items)
          hidden-above (long vis-start)
          hidden-below (max 0 (long (- n vis-end)))
          max-cmd-w    (reduce max 0 (map (fn [[cmd _]] (count cmd)) vis-items))]
      ;; Reserve a fixed 30%-of-screen row count regardless of item count.
      ;; Shifts chrome up and redraws everything via repaint-after-resize!.
      ;; Called OUTSIDE the popover gate so the repaint actually happens.
      (layout/set-menu-height! reserved)
      ;; Redraw the input line in the (now-shifted) input-row so the user's
      ;; buffer appears at the prompt — repaint-after-resize! only repaints
      ;; scrollback + chrome, not the input buffer.
      (terminal/redraw-input-line! buf-str cursor-pos)
      ;; Now paint the menu in the reserved rows and activate the popover gate
      ;; so subsequent background writers defer their paints.
      (layout/draw-overlay!
       (fn [w]
         (layout/set-popover-active! true)
         (let [{:keys [rows cols input-row]} @layout/!layout
               ;; Menu sits directly below the input block and above the
               ;; bottom chrome (separator2 row at `rows - 2`). So the
               ;; menu's bottom row is `rows - 3` and its top is
               ;; `rows - 2 - reserved`.
               menu-top (- rows reserved 2)
               sb (StringBuilder.)]
           (dotimes [i reserved]
             (let [row (+ menu-top i)]
               (.append sb (ansi/cursor-to row 1))
               (.append sb ^String ansi/erase-line)
               (cond
                 ;; Item rows.
                 (< i item-rows)
                 (when (< i vis-count)
                   (let [abs-idx    (+ vis-start i)
                         [cmd desc] (nth vis-items i)
                         highlight? (= abs-idx selected)
                         padded-cmd (format (str "%-" max-cmd-w "s") cmd)
                         max-desc-w (max 0 (- cols max-cmd-w 6))
                         one-line   (normalize-desc desc)
                         trunc-desc (truncate-to-width one-line max-desc-w)
                         line-text  (if highlight?
                                      (ansi/style (str " \u25B8 " padded-cmd "  " trunc-desc " ")
                                                  ansi/reverse-video)
                                      (str "   " (ansi/tool-name padded-cmd)
                                           "  " (ansi/muted trunc-desc)))]
                     (.append sb ^String line-text)))
                 ;; Reserved last row: scroll indicator. `erase-line`
                 ;; above already blanked the row, so when nothing is
                 ;; hidden `format-scroll-indicator` returns "" and we
                 ;; simply leave the row blank.
                 (= i item-rows)
                 (let [indicator (format-scroll-indicator hidden-above hidden-below cols)]
                   (when (seq indicator)
                     (.append sb ^String indicator))))))
           ;; Reposition cursor back to the (now shifted) input line
           (let [text-before-cursor (if (pos? cursor-pos) (subs buf-str 0 cursor-pos) "")
                 cursor-col (+ (fmt/display-width text-before-cursor) 3)]
             (when input-row
               (.append sb (ansi/cursor-to input-row cursor-col))
               (.append sb ^String ansi/hide-cursor)))
           (layout/raw-write-unsafe! w (.toString sb))))))
    ;; Inline: print menu lines below current cursor position (fixed-height
    ;; reservation only applies to fullscreen mode).
    (let [n          (count items)
          vis-start  scroll-offset
          vis-end    (min n (+ scroll-offset (menu-reserved-rows)))
          vis-items  (subvec (vec items) vis-start vis-end)
          max-cmd-w  (reduce max 0 (map (fn [[cmd _]] (count cmd)) vis-items))
          sb (StringBuilder.)
          cols (or (:cols @layout/!layout) 80)
          max-desc-w (max 0 (- cols max-cmd-w 6))]
      (doseq [[i [cmd desc]] (map-indexed vector vis-items)]
        (let [abs-idx    (+ vis-start i)
              highlight? (= abs-idx selected)
              padded-cmd (format (str "%-" max-cmd-w "s") cmd)
              one-line   (normalize-desc desc)
              trunc-desc (truncate-to-width one-line max-desc-w)
              line-text  (if highlight?
                           (ansi/style (str " \u25B8 " padded-cmd "  " trunc-desc " ")
                                       ansi/reverse-video)
                           (str "   " (ansi/tool-name padded-cmd)
                                "  " (ansi/muted trunc-desc)))]
          (.append sb (str line-text "\n"))))
      (tui-session/emit-inline! (.toString sb)))))

(defn clear-autocomplete-menu!
  "Clear the bottom-anchored autocomplete menu.
   On dismiss (default): deactivates the popover gate, then restores the
   original layout via (set-menu-height! 0) which shifts chrome back down
   and redraws scrollback + chrome — this naturally flushes any writes that
   were deferred while the popover was up.
   On :redraw? true: keeps the popover active and menu-height reserved
   (caller will immediately redraw the menu, avoiding flicker)."
  [n-visible & {:keys [redraw?] :or {redraw? false}}]
  (when (pos? n-visible)
    (if (layout/fullscreen?)
      (if redraw?
        ;; Redraw cycle: the caller will immediately draw a new menu. Keep
        ;; popover active and menu-height reserved; no need to clear rows
        ;; explicitly since draw-autocomplete-menu! will paint over them
        ;; (and adjust menu-height if vis-count changed).
        nil
        ;; Dismiss: deactivate popover, then restore layout (which repaints)
        (do
          (layout/set-popover-active! false)
          (layout/clear-dirty!)
          (layout/set-menu-height! 0)))
      ;; Inline: cursor up N lines + erase each
      (let [sb (StringBuilder.)]
        (dotimes [_ n-visible]
          (.append sb (str ansi/esc "A" ansi/erase-line)))
        (tui-session/emit-inline! (.toString sb))))))

(defn read-line-raw!
  "Read a line character-by-character in raw mode.
   Supports Page Up/Down for viewport scrolling, Shift+Left/Right for
   input history navigation, Left/Right for cursor movement within the line,
   Ctrl-A/Ctrl-E for beginning/end of line, Ctrl-K to kill to end of line,
   Alt+Enter for newline insertion (multi-line input), backspace, and Enter.
   Typing / activates an autocomplete menu for commands.
   Bracketed pastes spanning more than 5 lines collapse to a
   [paste #N +M lines] marker in the visible buffer; the full content is
   spliced back in on submit. Smaller pastes inline verbatim.
   Returns the line string (may contain newlines), or nil on Ctrl-D/EOF."
  [^InputStream in]
  (let [^StringBuilder buf (StringBuilder.)
        hist-idx      (volatile! -1)        ;; -1 = not navigating history
        saved-inp     (volatile! "")        ;; preserves in-progress typing
        cursor-pos    (volatile! 0)         ;; 0-based cursor position within buffer
        preferred-col (volatile! nil)       ;; preferred visual column for up/down nav (nil = derive)
        ;; Scroll-mode collapse-marker navigation state.
        ;; When viewport-offset > 0 and Tab is pressed, step through visible
        ;; [*Collapsed:<id>*] and [*Expanded:<id>*] markers. Enter toggles.
        selected-mark (volatile! nil)       ;; {:id :line-idx :kind} or nil
        ;; Autocomplete menu state
        menu-active?  (volatile! false)
        menu-items    (volatile! [])        ;; current filtered [[cmd desc] ...]
        menu-selected (volatile! -1)        ;; -1 = no selection
        menu-scroll   (volatile! 0)         ;; scroll offset (first visible index)
        menu-prev-vis (volatile! 0)         ;; previous visible count (for clearing)
        at-token-start (volatile! -1)       ;; char index where current @-token begins
        ;; Bracketed-paste collapse state. While input/!pasting? is true,
        ;; printable chars and CR/LF accumulate in paste-buf instead of buf.
        ;; On paste-end, content >5 lines collapses to a [paste #N +M lines]
        ;; marker stored in paste-map; smaller pastes inline verbatim.
        ;; At submit time, expand-paste! restores marker content.
        paste-mode?   (volatile! false)
        paste-buf     (volatile! nil)       ;; StringBuilder during paste, else nil
        paste-anchor  (volatile! 0)         ;; cursor-pos captured at paste start
        paste-counter (volatile! 0)         ;; per-input paste id sequence
        paste-map     (volatile! {})        ;; id -> {:marker :content}
        ;; Local helpers
        dismiss-menu! (fn []
                        (when @menu-active?
                          (clear-autocomplete-menu! @menu-prev-vis)
                          (vreset! menu-active? false)
                          (vreset! menu-items [])
                          (vreset! menu-selected -1)
                          (vreset! menu-scroll 0)
                          (vreset! menu-prev-vis 0)
                          (vreset! at-token-start -1)
                          ;; After layout restore, redraw the input line so the
                          ;; buffer text + cursor land in the restored input-row.
                          (terminal/redraw-input-line! (.toString buf) @cursor-pos)))
        show-menu!    (fn show-menu!
                        ([matches] (show-menu! matches nil))
                        ([matches query-body]
                         (let [n (count matches)]
                           (if (zero? n)
                             (do (let [was-active? @menu-active?]
                                   (when was-active?
                                     (clear-autocomplete-menu! @menu-prev-vis)))
                                 (vreset! menu-active? false)
                                 (vreset! menu-items [])
                                 (vreset! menu-selected -1)
                                 (vreset! menu-scroll 0)
                                 (vreset! menu-prev-vis 0)
                                 (vreset! at-token-start -1)
                                 ;; Redraw input so buffer text reappears in the
                                 ;; restored input-row after layout shift.
                                 (terminal/redraw-input-line! (.toString buf) @cursor-pos))
                             (let [prev-sel   @menu-selected
                                   prev-items @menu-items
                                   qb         (some-> query-body str/lower-case)
                                   ;; bucket-0? = name (with leading /:: stripped)
                                   ;; starts with the query body. Mirrors
                                   ;; prefix-first-sort-key so the "top" of
                                   ;; the menu and "bucket 0" agree.
                                   bucket-0?  (fn [[cmd _]]
                                                (when (and cmd qb (not (str/blank? qb)))
                                                  (let [lc (str/lower-case (str cmd))
                                                        stripped (str/replace lc #"^[/:]" "")]
                                                    (str/starts-with? stripped qb))))
                                   has-b0?    (and qb (boolean (some bucket-0? matches)))
                                   ;; Preserve selection: find previously selected item in new list
                                   ;; Default to 0 (first item) so the menu always has a highlight
                                   new-sel (cond
                                             ;; Single match — auto-select it
                                             (= n 1) 0
                                             ;; Had a selection — try to find same item in new matches
                                             (and (>= prev-sel 0)
                                                  (< prev-sel (count prev-items)))
                                             (let [prev-item (nth prev-items prev-sel)
                                                   idx (.indexOf ^java.util.List (vec matches) prev-item)]
                                               (cond
                                                 (neg? idx) 0
                                                 ;; The previous selection was auto-picked (index 0
                                                 ;; before this keystroke). If the new query has any
                                                 ;; bucket-0 (name-prefix) matches and prev-item
                                                 ;; itself is no longer bucket-0, snap to the new
                                                 ;; top so typing a longer prefix doesn't strand
                                                 ;; the user on a substring/alphabetical leftover.
                                                 ;; Explicit arrow-key selections (prev-sel > 0)
                                                 ;; are preserved.
                                                 (and (zero? prev-sel)
                                                      has-b0?
                                                      (not (bucket-0? prev-item)))
                                                 0
                                                 :else idx))
                                             ;; No prior selection — highlight first item
                                             :else 0)]
                               (vreset! menu-active? true)
                               (vreset! menu-items matches)
                               (vreset! menu-selected new-sel)
                               (vreset! menu-scroll 0)
                               (let [vis (min n (menu-reserved-rows))]
                                 ;; Always clear previous menu before redraw to prevent
                                 ;; stale rows leaking into scrollback when layout shifts.
                                 ;; :redraw? true keeps the popover gate active so background
                                 ;; writers stay deferred across the clear→draw transition.
                                 (when (pos? @menu-prev-vis)
                                   (clear-autocomplete-menu! @menu-prev-vis :redraw? true))
                                 (draw-autocomplete-menu! matches @menu-selected @menu-scroll @cursor-pos (.toString buf))
                                 (vreset! menu-prev-vis vis)))))))
        update-menu!  (fn []
                        (try
                          (let [s (.toString buf)]
                            (cond
                              ;; Top-level slash-command prefix (no space in buffer)
                              (and (str/starts-with? s "/")
                                   (not (str/includes? s " ")))
                              (do (vreset! at-token-start -1)
                                  (show-menu! (filter-commands s) (subs s 1)))

                              ;; Registry-based sub-menu dispatch (slash-commands only)
                              (str/starts-with? s "/")
                              (if-let [[_prefix spec typed-suffix] (resolve-submenu s)]
                                (let [items   (get-submenu-items spec s)
                                      lc-q    (str/lower-case (str typed-suffix))
                                      matches (if (str/blank? typed-suffix)
                                                items
                                                ;; Filter by case-insensitive substring on the
                                                ;; command name only, then sort prefix-matches
                                                ;; first while preserving the registered order
                                                ;; within each bucket (stable sort, single key).
                                                ;; Description text is not searched — see
                                                ;; filter-commands for the rationale.
                                                (->> items
                                                     (filter (fn [[cmd _desc]]
                                                               (str/includes? (str/lower-case (str cmd)) lc-q)))
                                                     (sort-by (fn [[cmd _]]
                                                                (if (str/starts-with?
                                                                     (str/lower-case (str cmd)) lc-q)
                                                                  0 1)))
                                                     vec))]
                                  (vreset! at-token-start -1)
                                  ;; Submenu items share the parent-command prefix (e.g.
                                  ;; "/agent new coact-agent"), so the top-level bucket-0
                                  ;; reset heuristic doesn't apply cleanly. Pass nil and
                                  ;; rely on the legacy preserve-if-found behavior.
                                  (show-menu! matches nil))
                                (dismiss-menu!))

                              ;; Top-level colon-command prefix (tools only, no submenus).
                              ;; Stop completing once the user starts typing kwargs.
                              (and (str/starts-with? s ":")
                                   (not (str/includes? s " ")))
                              (do (vreset! at-token-start -1)
                                  (show-menu! (filter-commands s) (subs s 1)))

                              ;; @-prefix file/directory menu
                              :else
                              (if-let [[start token] (extract-at-token s @cursor-pos)]
                                (let [prefix  (subs token 1) ;; strip leading @
                                      matches (list-path-matches prefix)]
                                  (vreset! at-token-start start)
                                  (show-menu! matches))
                                (dismiss-menu!))))
                          (catch Exception _ (dismiss-menu!))))
        ;; Multi-row input helpers
        buf-visual    (fn []
                        (let [cols (or (:cols @layout/!layout) 80)
                              width (max 1 (- cols 2))]
                          (terminal/layout-buffer (.toString buf) @cursor-pos width)))
        ;; Map (visual-row, target-col) back to a linear cursor-pos index.
        ;; Returns the char index at or just past `target-col` display cols
        ;; within that visual line's text.
        seek-to       (fn [visual-lines row target-col]
                        (if (or (neg? row) (>= row (count visual-lines)))
                          nil
                          (let [{:keys [^String text start]} (nth visual-lines row)
                                len (count text)
                                ;; Walk characters until display width reaches target-col
                                idx (loop [i 0, w 0]
                                      (if (or (>= i len) (>= w target-col))
                                        i
                                        (let [cp (Character/codePointAt text (int i))
                                              n  (Character/charCount cp)
                                              cw (fmt/display-width (.substring text i (+ i n)))]
                                          (if (and (pos? cw) (> (+ w cw) target-col))
                                            i
                                            (recur (+ i n) (+ w cw))))))]
                            (+ start idx))))
        move-cursor-visual! (fn [direction]
                              ;; direction = :up or :down
                              ;; Returns true if cursor moved within buffer, false to fall through.
                              (let [{:keys [visual-lines cursor-row cursor-col]} (buf-visual)
                                    n-vl (count visual-lines)
                                    target-row (if (= direction :up) (dec cursor-row) (inc cursor-row))]
                                (if (or (neg? target-row) (>= target-row n-vl))
                                  false
                                  (let [pref (or @preferred-col cursor-col)
                                        new-pos (seek-to visual-lines target-row pref)]
                                    (when new-pos
                                      (vreset! cursor-pos new-pos)
                                      (vreset! preferred-col pref)
                                      (terminal/redraw-input-line! (.toString buf) @cursor-pos)
                                      true)))))
        logical-line-start (fn []
                             ;; Move cursor to start of current logical line
                             ;; (right after previous '\n', or 0 if none).
                             (let [s   (.toString buf)
                                   pos @cursor-pos
                                   prev-nl (str/last-index-of (subs s 0 pos) "\n")
                                   new-pos (if prev-nl (inc (long prev-nl)) 0)]
                               (vreset! cursor-pos new-pos)
                               (vreset! preferred-col nil)
                               (terminal/redraw-input-line! s new-pos)))
        logical-line-end   (fn []
                             ;; Move cursor to end of current logical line
                             ;; (just before next '\n', or buffer end if none).
                             (let [s   (.toString buf)
                                   pos @cursor-pos
                                   next-nl (.indexOf s "\n" (int pos))
                                   new-pos (if (neg? next-nl) (.length buf) next-nl)]
                               (vreset! cursor-pos new-pos)
                               (vreset! preferred-col nil)
                               (terminal/redraw-input-line! s new-pos)))
        ;; Bracketed-paste helpers
        begin-paste!  (fn []
                        (vreset! paste-mode? true)
                        (vreset! paste-buf (StringBuilder.))
                        (vreset! paste-anchor @cursor-pos))
        commit-paste! (fn []
                        ;; Called when !pasting? flips back to false.
                        ;; Splices paste content (or its collapsed marker)
                        ;; into buf at the captured anchor position.
                        (let [^StringBuilder pb @paste-buf
                              content (or (some-> pb .toString) "")]
                          (vreset! paste-mode? false)
                          (vreset! paste-buf nil)
                          (when (pos? (count content))
                            (let [n-lines (inc (count (filter #(= % \newline) content)))
                                  anchor  (long @paste-anchor)
                                  ins     (if (> n-lines 5)
                                            (let [id (vswap! paste-counter inc)
                                                  marker (str "[paste #" id " +" n-lines " lines]")]
                                              (vswap! paste-map assoc id
                                                      {:marker marker :content content})
                                              marker)
                                            content)]
                              (.insert buf (int anchor) ^String ins)
                              (vreset! cursor-pos (+ anchor (count ins))))
                            (vreset! preferred-col nil)
                            (vreset! hist-idx -1)
                            (dismiss-menu!)
                            (terminal/redraw-input-line! (.toString buf) @cursor-pos))))
        expand-paste! (fn [s]
                        ;; Replace every active marker in s with its stored content.
                        (reduce-kv (fn [acc _ {:keys [marker content]}]
                                     (str/replace acc marker content))
                                   s @paste-map))
        scroll-mode?  (fn [] (pos? (or (:viewport-offset @layout/!layout) 0)))
        refresh-highlight!
        (fn []
          (if-let [m @selected-mark]
            (swap! layout/!layout assoc :collapse-highlight
                   {:start-idx (:line-idx m) :id (:id m)})
            (swap! layout/!layout dissoc :collapse-highlight))
          (try (layout/render-viewport!) (catch Exception _))
          (try (layout/draw-separator!) (catch Exception _)))
        maybe-deselect!
        (fn []
          ;; When scroll mode has exited (viewport back to live), clear any
          ;; collapse-marker selection so the next PgUp starts fresh.
          (when (and (not (scroll-mode?)) @selected-mark)
            (vreset! selected-mark nil)
            (swap! layout/!layout dissoc :collapse-highlight)
            (try (layout/render-viewport!) (catch Exception _))
            (try (layout/draw-separator!) (catch Exception _))))
        step-mark!    (fn [dir]
                        ;; Cycle through visible markers. dir = :next or :prev.
                        (let [marks (block-ui/find-markers-in-viewport)]
                          (if (empty? marks)
                            (do (vreset! selected-mark nil)
                                (refresh-highlight!)
                                false)
                            (let [cur (or @selected-mark (first marks))
                                  idx (or (first (keep-indexed
                                                  (fn [i m] (when (= (:id m) (:id cur)) i))
                                                  marks))
                                          0)
                                  n (count marks)
                                  new-idx (mod (if (= dir :prev) (dec idx) (inc idx)) n)
                                  new-mark (nth marks new-idx)]
                              (vreset! selected-mark new-mark)
                              (refresh-highlight!)
                              true))))
        toggle-mark!  (fn []
                        (when-let [{:keys [id line-idx kind]} @selected-mark]
                          (let [new-delta (block-ui/toggle! id line-idx kind)
                                ;; After toggle, re-locate marker (expand may have
                                ;; inserted many lines; collapse shifts back to 1).
                                marks (block-ui/find-markers-in-viewport)
                                updated (first (filter #(= (:id %) id) marks))]
                            (vreset! selected-mark updated)
                            (refresh-highlight!)
                            new-delta)))
        accept-sel!   (fn []
                        (when (and @menu-active?
                                   (>= @menu-selected 0)
                                   (< @menu-selected (count @menu-items)))
                          (let [[cmd desc] (nth @menu-items @menu-selected)
                                is-at-menu? (>= @at-token-start 0)]
                            (clear-autocomplete-menu! @menu-prev-vis)
                            (if is-at-menu?
                              ;; @-prefix menu: replace only the @-token span
                              (let [start @at-token-start
                                    end   @cursor-pos
                                    is-dir? (str/ends-with? cmd "/")
                                    replacement (str "@" cmd)]
                                ;; Reset menu state
                                (vreset! menu-active? false)
                                (vreset! menu-items [])
                                (vreset! menu-selected -1)
                                (vreset! menu-scroll 0)
                                (vreset! menu-prev-vis 0)
                                (vreset! at-token-start -1)
                                ;; Replace the @-token in the buffer
                                (.replace buf (int start) (int end) ^String replacement)
                                (vreset! cursor-pos (+ start (count replacement)))
                                (vreset! preferred-col nil)
                                (terminal/redraw-input-line! (.toString buf) @cursor-pos)
                                ;; If directory selected, immediately re-open menu for drill-down
                                (when is-dir?
                                  (vreset! at-token-start start)
                                  (let [prefix (subs cmd 0) ;; cmd already has trailing /
                                        matches (list-path-matches prefix)]
                                    (show-menu! matches))))
                              ;; Slash-command menu: replace entire buffer
                              (let [has-child? (contains? @!submenu-registry cmd)]
                                (vreset! menu-active? false)
                                (vreset! menu-items [])
                                (vreset! menu-selected -1)
                                (vreset! menu-scroll 0)
                                (vreset! menu-prev-vis 0)
                                (vreset! at-token-start -1)
                                (.replace buf 0 (.length buf) ^String cmd)
                                (vreset! preferred-col nil)
                                (if has-child?
                                  ;; Drill-down: append space and re-trigger submenu
                                  (do (.append buf " ")
                                      (vreset! cursor-pos (.length buf))
                                      (terminal/redraw-input-line! (.toString buf) @cursor-pos)
                                      (update-menu!))
                                  (do (vreset! cursor-pos (.length buf))
                                      (terminal/redraw-input-line! (.toString buf) @cursor-pos))))))))]
    (loop []
      ;; Catch-all: if scroll mode exited (e.g., new output auto-snapped viewport
      ;; back to live), drop any stale marker selection so the next PgUp starts
      ;; fresh.
      (maybe-deselect!)
      ;; Sync bracketed-paste mode with the global !pasting? flag. The flag
      ;; flips inside the previous read-key! when ESC[200~/201~ is consumed,
      ;; so transitions show up at the start of the next iteration.
      (cond
        (and @input/!pasting? (not @paste-mode?)) (begin-paste!)
        (and (not @input/!pasting?) @paste-mode?) (commit-paste!))
      (let [key (terminal/read-key! in)
            ;; Permission prompt intercept — when pending, route y/n/a keys
            handled-permission? (when-let [p @tui-session/!pending-permission]
                                  (when (string? key)
                                    (case (str/lower-case key)
                                      "y" (deliver p :yes)
                                      "n" (deliver p :no)
                                      "a" (deliver p :always)
                                      nil))
                                  true)
            ;; User feedback intercept — route number keys when pending
            handled-feedback? (when (and (not handled-permission?)
                                         (string? key))
                                (when-let [{:keys [promise options]} @tui-session/!pending-feedback]
                                  (when-let [n (parse-long key)]
                                    (when (and (>= n 1) (<= n (count options)))
                                      (let [idx (dec n)
                                            selected (nth options idx)]
                                        (deliver promise {:selected (:label selected) :index idx})
                                        true)))))]
        (cond
          handled-permission? (recur)
          handled-feedback?   (recur)
          ;; Bracketed paste in progress: capture printable chars and CR/LF
          ;; into paste-buf and drop everything else. The :unknown returned by
          ;; ESC[201~ falls here too — the top-of-loop check on the next
          ;; iteration will then run commit-paste!.
          @paste-mode?
          (do (when-let [^StringBuilder pb @paste-buf]
                (cond
                  (string? key)      (.append pb ^String key)
                  (= key :alt-enter) (.append pb "\n")))
              (recur))
          :else
          (case key
            nil       (do (dismiss-menu!) nil)
            :ctrl-d   (if (zero? (.length buf))
                        (do (dismiss-menu!) nil)
                        (recur))

            :sigint   (do (dismiss-menu!)
                          (tui-session/emit!
                           (ansi/muted "Press Ctrl-C again to quit, or type /quit"))
                          (terminal/redraw-input-line! (.toString buf) @cursor-pos)
                          (recur))

            :tab      (cond
                        @menu-active?
                        (do (accept-sel!) (recur))
                        ;; Tab cycles through visible display-block markers
                        ;; whether the user is in live or scroll mode — the
                        ;; live iteration block sits at the tail and is
                        ;; never reachable through scroll mode alone.
                        ;; Gated on an empty input buffer so a Tab while
                        ;; the user is typing doesn't silently steal focus
                        ;; into marker mode.
                        (zero? (.length buf))
                        (do (step-mark! :next) (recur))
                        :else (recur))

            :escape   (cond
                        @menu-active?
                        (do (dismiss-menu!)
                            (terminal/redraw-input-line! (.toString buf) @cursor-pos)
                            (recur))
                        ;; Scroll mode with a selected marker: clear the selection
                        @selected-mark
                        (do (vreset! selected-mark nil)
                            (swap! layout/!layout dissoc :collapse-highlight)
                            (try (layout/render-viewport!) (catch Exception _))
                            (try (layout/draw-separator!) (catch Exception _))
                            (recur))
                        :else (recur))

            :alt-enter
            (do
              ;; Insert newline at cursor position
              (.insert buf (int @cursor-pos) "\n")
              (vswap! cursor-pos inc)
              (vreset! preferred-col nil)
              (dismiss-menu!)
              (terminal/redraw-input-line! (.toString buf) @cursor-pos)
              (recur))

            :ctrl-k
            (let [s (.toString buf)
                  pos @cursor-pos]
              (if (< pos (count s))
                (let [;; Find next newline or end of buffer
                      next-nl (let [idx (.indexOf s "\n" (int pos))]
                                (if (neg? idx) (count s) idx))
                      ;; If cursor is right at a newline, delete just that newline
                      delete-end (if (= (.charAt ^String s (int pos)) \newline)
                                   (inc pos)
                                   next-nl)]
                  (.delete buf (int pos) (int delete-end)))
                ;; At end of buffer — nothing to kill
                nil)
              (vreset! preferred-col nil)
              (dismiss-menu!)
              (terminal/redraw-input-line! (.toString buf) @cursor-pos)
              (recur))

            :enter    (cond
                        (and @menu-active? (>= @menu-selected 0)
                             (let [[cmd _] (nth @menu-items @menu-selected)
                                   is-at-menu? (>= @at-token-start 0)
                                   has-child?  (contains? @!submenu-registry cmd)]
                               (or is-at-menu?
                                   has-child?
                                   (not= (.toString buf) cmd))))
                        ;; Menu active with a selection AND either: it's an
                        ;; @-token completion (always accept), the highlight
                        ;; is a parent command with a submenu (accept → drill
                        ;; down), or the buffer doesn't yet match the
                        ;; highlight (accept fills the rest). Otherwise the
                        ;; user typed a leaf command in full — fall through
                        ;; so a single Enter submits.
                        (do (accept-sel!) (recur))
                        ;; Selected marker AND empty input → toggle expand/
                        ;; collapse. With pending input, Enter must submit
                        ;; — otherwise typing a follow-up about the
                        ;; highlighted block would silently toggle it.
                        (and @selected-mark (zero? (.length buf)))
                        (do (toggle-mark!) (recur))
                        :else
                        ;; Normal enter → dismiss menu + submit. Expand any
                        ;; paste markers in the buffer back to full content
                        ;; before returning the line.
                        (let [_ (dismiss-menu!)
                              line (expand-paste! (.toString buf))]
                          (layout/scroll-to-bottom!)
                          (maybe-deselect!)
                          (layout/set-input-cursor-col! 3)
                          ;; Collapse multi-row input area back to single row
                          (layout/set-input-height! 1)
                          (when-not (str/blank? line)
                            (let [hist @terminal/!input-history]
                              (when (or (empty? hist) (not= (peek hist) line))
                                (let [hist' (swap! terminal/!input-history
                                                   (fn [h]
                                                     (let [h' (conj h line)]
                                                       (if (> (count h') terminal/max-history-size)
                                                         (subvec h' (- (count h') terminal/max-history-size))
                                                         h'))))]
                                  ;; Persist per-session so resume restores history.
                                  ;; Soft-failure: never crash the input loop on a
                                  ;; disk hiccup — the worst case is a lost line.
                                  (when-let [asid (:agent-session-id (sessions/get-active-session))]
                                    (try (persist/write-snap! asid :input-history hist')
                                         (catch Throwable _)))))))
                          line))

            ;; Up/Down: menu navigation when active, scroll when inactive
            :scroll-up
            (if @menu-active?
              (let [n (count @menu-items)]
                (when (pos? n)
                  (let [new-sel (if (or (= @menu-selected -1) (zero? @menu-selected))
                                  (dec n)  ;; wrap to last
                                  (dec @menu-selected))]
                    (vreset! menu-selected new-sel)
                    ;; Adjust scroll window
                    (when (< new-sel @menu-scroll)
                      (vreset! menu-scroll new-sel))
                    (when (= new-sel (dec n))
                      ;; Wrapped to bottom — scroll to show last items.
                      ;; Use `menu-item-rows` (visible item count) so
                      ;; the last item lands on the bottom item row,
                      ;; not on the indicator row.
                      (vreset! menu-scroll (max 0 (- n (menu-item-rows)))))
                    (let [vis (min n (menu-reserved-rows))]
                      (when (pos? @menu-prev-vis)
                        (clear-autocomplete-menu! @menu-prev-vis :redraw? true))
                      (draw-autocomplete-menu! @menu-items @menu-selected @menu-scroll @cursor-pos (.toString buf))
                      (vreset! menu-prev-vis vis))))
                (recur))
              ;; Menu inactive: try to move cursor up within multi-row input;
              ;; if cursor is already on the first visual line, scroll viewport.
              (do (or (move-cursor-visual! :up)
                      (do (layout/scroll-lines-up! 1)
                          (terminal/redraw-input-line! (.toString buf) @cursor-pos)))
                  (recur)))

            :scroll-down
            (if @menu-active?
              (let [n (count @menu-items)]
                (when (pos? n)
                  (let [new-sel (if (or (= @menu-selected -1)
                                        (= @menu-selected (dec n)))
                                  0  ;; wrap to first
                                  (inc @menu-selected))]
                    (vreset! menu-selected new-sel)
                    ;; Adjust scroll window — overflow vs. the visible
                    ;; item window (reserved minus the indicator row).
                    (when (>= new-sel (+ @menu-scroll (menu-item-rows)))
                      (vswap! menu-scroll inc))
                    (when (zero? new-sel)
                      (vreset! menu-scroll 0))
                    (let [vis (min n (menu-reserved-rows))]
                      (when (pos? @menu-prev-vis)
                        (clear-autocomplete-menu! @menu-prev-vis :redraw? true))
                      (draw-autocomplete-menu! @menu-items @menu-selected @menu-scroll @cursor-pos (.toString buf))
                      (vreset! menu-prev-vis vis))))
                (recur))
              ;; Menu inactive: try to move cursor down within multi-row input;
              ;; if cursor is already on the last visual line, scroll viewport.
              (do (or (move-cursor-visual! :down)
                      (do (layout/scroll-lines-down! 1)
                          (maybe-deselect!)
                          (terminal/redraw-input-line! (.toString buf) @cursor-pos)))
                  (recur)))

            ;; Cursor movement
            :arrow-left
            (do (when (pos? @cursor-pos)
                  (vswap! cursor-pos dec)
                  (vreset! preferred-col nil)
                  (terminal/redraw-input-line! (.toString buf) @cursor-pos))
                (recur))

            :arrow-right
            (do (when (< @cursor-pos (.length buf))
                  (vswap! cursor-pos inc)
                  (vreset! preferred-col nil)
                  (terminal/redraw-input-line! (.toString buf) @cursor-pos))
                (recur))

            :ctrl-a
            (do (logical-line-start) (recur))

            :ctrl-e
            (do (logical-line-end) (recur))

            ;; History navigation: dismiss menu first
            :shift-arrow-left
            (do (dismiss-menu!)
                (let [hist @terminal/!input-history]
                  (when (seq hist)
                    (if (= @hist-idx -1)
                      (do (vreset! saved-inp (.toString buf))
                          (vreset! hist-idx (dec (count hist)))
                          (.replace buf 0 (.length buf) ^String (nth hist @hist-idx))
                          (vreset! cursor-pos (.length buf))
                          (vreset! preferred-col nil)
                          (terminal/redraw-input-line! (.toString buf) @cursor-pos))
                      (when (pos? @hist-idx)
                        (vswap! hist-idx dec)
                        (.replace buf 0 (.length buf) ^String (nth hist @hist-idx))
                        (vreset! cursor-pos (.length buf))
                        (vreset! preferred-col nil)
                        (terminal/redraw-input-line! (.toString buf) @cursor-pos)))))
                (recur))

            :shift-arrow-right
            (do (dismiss-menu!)
                (let [hist @terminal/!input-history]
                  (when (not= @hist-idx -1)
                    (if (< @hist-idx (dec (count hist)))
                      (do (vswap! hist-idx inc)
                          (.replace buf 0 (.length buf) ^String (nth hist @hist-idx))
                          (vreset! cursor-pos (.length buf))
                          (vreset! preferred-col nil)
                          (terminal/redraw-input-line! (.toString buf) @cursor-pos))
                      (do (vreset! hist-idx -1)
                          (.replace buf 0 (.length buf) ^String @saved-inp)
                          (vreset! cursor-pos (.length buf))
                          (vreset! preferred-col nil)
                          (terminal/redraw-input-line! (.toString buf) @cursor-pos)))))
                (recur))

            :ctrl-n   (do (dismiss-menu!)
                          (sessions/next-session!)
                          (tui-session/update-status-bar!)
                          (terminal/redraw-input-line! (.toString buf) @cursor-pos)
                          (recur))

            :ctrl-o   (cond
                        ;; A selected marker → open saved file in $EDITOR
                        ;; (works in live or scroll mode).
                        @selected-mark
                        (let [id (:id @selected-mark)
                              result (try (block-ui/view-in-editor! id)
                                          (catch Exception e
                                            (tui-session/emit!
                                             (ansi/failure
                                              (str "Editor failed: " (.getMessage e))))
                                            nil))]
                          (when-not result
                            ;; Diagnose: surface WHY view-in-editor! returned nil
                            (tui-session/emit!
                             (ansi/warning
                              (str "No file found for marker " id
                                   " (scanned /tmp/.../tui-*/" id ".txt)"))))
                          (terminal/redraw-input-line! (.toString buf) @cursor-pos)
                          (recur))
                        :else
                        (do (dismiss-menu!)
                            (terminal/redraw-input-line! (.toString buf) @cursor-pos)
                            (recur)))

            :ctrl-p   (do (dismiss-menu!)
                          (sessions/prev-session!)
                          (tui-session/update-status-bar!)
                          (terminal/redraw-input-line! (.toString buf) @cursor-pos)
                          (recur))

            :ctrl-t   (do (dismiss-menu!)
                          ;; Create a new session with its own agent (inherits current agent type)
                          (try
                            (let [agent-id (or (tui-session/get-active-defagent-id) :coact-agent)
                                  idx (sessions/create-session! {:agent-id agent-id})]
                              (sessions/switch-to! idx)
                              (tui-session/emit! (ansi/muted (str "Created session " idx " [" (name agent-id) "]"))))
                            (catch Exception e
                              (tui-session/emit! (ansi/failure (str "Failed to create session: " (.getMessage e))))))
                          (tui-session/update-status-bar!)
                          (terminal/redraw-input-line! (.toString buf) @cursor-pos)
                          (recur))

            :ctrl-w   (do (dismiss-menu!)
                          (let [n (sessions/session-count)]
                            (if (<= n 1)
                              (tui-session/emit! (ansi/warning "Cannot close the last session."))
                              (let [idx (sessions/active-idx)]
                                (sessions/close-session! idx)
                                (tui-session/emit! (ansi/muted (str "Closed session " idx "."))))))
                          (tui-session/update-status-bar!)
                          (terminal/redraw-input-line! (.toString buf) @cursor-pos)
                          (recur))

            :page-up  (do (dismiss-menu!)
                          (layout/scroll-page-up!)
                          ;; Entering/staying in scroll mode — auto-select first visible marker
                          (when (and (scroll-mode?) (nil? @selected-mark))
                            (when-let [first-mark (first (block-ui/find-markers-in-viewport))]
                              (vreset! selected-mark first-mark)
                              (refresh-highlight!)))
                          (terminal/redraw-input-line! (.toString buf) @cursor-pos)
                          (recur))

            :page-down (do (dismiss-menu!)
                           (layout/scroll-page-down!)
                           (maybe-deselect!)
                           (terminal/redraw-input-line! (.toString buf) @cursor-pos)
                           (recur))

            :backspace (do (when (pos? @cursor-pos)
                             (.deleteCharAt buf (int (dec @cursor-pos)))
                             (vswap! cursor-pos dec)
                             (vreset! preferred-col nil)
                             (terminal/redraw-input-line! (.toString buf) @cursor-pos))
                           (vreset! hist-idx -1)
                           (update-menu!)
                           (recur))

            :unknown  (recur)

            ;; Printable character (string)
            (do (.insert buf (int @cursor-pos) ^String key)
                (vswap! cursor-pos + (.length ^String key))
                (vreset! preferred-col nil)
                (vreset! hist-idx -1)
                (terminal/redraw-input-line! (.toString buf) @cursor-pos)
                (update-menu!)
                (recur))))))))
