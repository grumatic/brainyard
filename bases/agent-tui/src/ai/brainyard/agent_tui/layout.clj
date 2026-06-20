;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent-tui.layout
  "Terminal layout manager for split-screen TUI.
   Two modes:
   - :fullscreen — alt screen + DECSTBM scroll region, fixed status bar + input prompt
   - :inline     — pass-through (current behavior, used by REPL start!/ask/stop!)

   All write functions acquire `layout-lock` for thread safety."
  (:require [ai.brainyard.agent.interface.tui.ansi :as ansi]
            [ai.brainyard.agent.interface.tui.format :as fmt]
            [clojure.string :as str]))

;; ============================================================================
;; State
;; ============================================================================

(defonce !layout
  (atom {:mode           :inline   ;; :inline | :fullscreen
         :rows           24
         :cols           80
         :scroll-bottom  nil       ;; last row of scroll region (1-based)
         :separator-row  nil       ;; top separator (between scroll region and input)
         :input-row      nil       ;; input prompt row
         :separator2-row nil       ;; bottom separator (between input and status)
         :tab-row        nil       ;; tab strip row (above status row)
         :tab-strip-text ""        ;; last rendered tab strip text (cached for repaint)
         :status-row     nil       ;; status bar row (right-aligned)
         :status-text    ""        ;; last rendered status bar text
         :viewport-offset 0        ;; 0 = live (showing latest), >0 = scrolled up N lines
         :task-activity-height 0  ;; current task activity area height (0 = hidden)
         :task-activity-data nil  ;; vector of task snapshots for rendering
         :agent-activity-height 0  ;; current agent activity panel height (0 = hidden)
         :agent-activity-data nil  ;; vector of pre-rendered ANSI strings for agent panel
         :menu-height    0        ;; popover menu height (0 = hidden). Inserts between input block and separator2, shifts input + scroll region up.
         :input-height   1        ;; input area height in rows (grows with word-wrap / multi-line buffer)
         :input-height-max 6      ;; cap on input-height (recomputed on resize based on terminal rows)
         :input-cursor-col 3      ;; last known cursor column in input row (1-based)
         :input-cursor-row nil    ;; last known cursor terminal row (1-based, nil = top of input block)
         :input-active    false   ;; true when user is at input prompt (controls cursor visibility)
         :writer         nil}))    ;; captured java.io.Writer

(def layout-lock (Object.))

;; Scrollback buffer: stores all output lines written to the scroll region.
;; Dumped to normal screen on teardown so user can scroll back in terminal history.
(defonce !scrollback (atom []))

;; Live blocks: regions of !scrollback that can be updated in-place.
;; Live blocks are always at the tail of !scrollback. Normal output inserts before them.
;; {block-id {:start-idx int, :line-count int}}
(defonce !live-blocks (atom {}))

;; Popover state: when an overlay (autocomplete menu, etc.) is active, defer
;; terminal paints from background writers (live block tickers, viewport renders).
;; Data updates to !scrollback / !live-blocks still happen — only the terminal
;; writes are gated. The dirty flag triggers a full redraw on popover dismiss.
(defonce ^:private !popover-active? (atom false))
(defonce ^:private !dirty? (atom false))

(defn popover-active?
  "True when a popover (e.g., autocomplete menu) owns the screen and
   background writers should defer their terminal paints."
  []
  @!popover-active?)

(defn set-popover-active!
  "Toggle popover-active state. Caller should hold layout-lock for atomicity
   with adjacent paint operations."
  [active?]
  (reset! !popover-active? (boolean active?)))

(defn dirty?
  "True if any background paint was deferred while popover was active."
  []
  @!dirty?)

(defn clear-dirty! [] (reset! !dirty? false))

(defn- mark-dirty! [] (reset! !dirty? true))

;; ============================================================================
;; Predicates
;; ============================================================================

(defn fullscreen?
  "Return true when layout is in fullscreen mode."
  []
  (= :fullscreen (:mode @!layout)))

;; ============================================================================
;; Writer Access
;; ============================================================================

(defn set-writer!
  "Store writer ref for background threads."
  [w]
  (swap! !layout assoc :writer w))

(defn- get-writer
  "Get the terminal writer. Prefers the stored writer to avoid leaking into
   sandbox StringWriter when *out* is rebound during SCI evaluation."
  []
  (or (:writer @!layout) *out*))

;; ============================================================================
;; Low-level Terminal Writes (must be called inside locking layout-lock)
;; ============================================================================

(defn- raw-write!
  "Write string to writer, flush. Caller must hold layout-lock."
  [^java.io.Writer w ^String s]
  (when w
    (try
      (.write w s)
      (.flush w)
      (catch Exception _ nil))))

;; ============================================================================
;; Public Overlay Primitives
;; ============================================================================

(defn draw-overlay!
  "Execute render-fn inside layout-lock with the writer.
   render-fn receives (w) and should call raw-write-unsafe! with built content."
  [render-fn]
  (locking layout-lock
    (let [w (get-writer)]
      (when w (render-fn w)))))

(defn raw-write-unsafe!
  "Direct terminal write. Caller MUST hold layout-lock (use inside draw-overlay!)."
  [^java.io.Writer w ^String s]
  (raw-write! w s))

;; ============================================================================
;; Output Functions
;; ============================================================================

(declare earliest-live-block-idx)
(declare render-viewport!)
(declare sticky-bottom-entry)

(defn write-output!
  "Write a line of output.
   Fullscreen: inserts at the scrollback tail (or just before a sticky-bottom
   live block when one exists, so anchored blocks like the input/status bar
   stay visually pinned). Non-sticky live blocks — iteration widgets, task
   blocks, etc. — live at fixed scrollback positions and are NOT used as the
   insertion point: new emit-output goes *after* them so the natural flow
   ('iter → answer') is preserved even when a background task block is
   still alive (e.g. soft-timeout detach keeps the task widget visible while
   the answer is being emitted by ask-post). Auto-snaps viewport to bottom.
   Inline: plain write + newline."
  [s]
  (when (and s (not (str/blank? s)))
    (locking layout-lock
      (let [w (get-writer)]
        (when w
          (if (fullscreen?)
            (let [{:keys [scroll-bottom]} @!layout
                  new-lines (str/split-lines s)
                  n (count new-lines)
                  sticky-bot (sticky-bottom-entry)
                  insert-at (if sticky-bot
                              (:start-idx (second sticky-bot))
                              (count @!scrollback))
                  needs-shift? (some? sticky-bot)]
              ;; Auto-snap to bottom if scrolled up
              (when (pos? (:viewport-offset @!layout))
                (swap! !layout assoc :viewport-offset 0))
              ;; Insert into scrollback. When a sticky-bottom anchor exists,
              ;; splice in just above it and shift live blocks at/after the
              ;; insert point forward. Otherwise just append at the tail.
              (if needs-shift?
                (do (swap! !scrollback
                           (fn [sb]
                             (into (into (subvec sb 0 insert-at) new-lines)
                                   (subvec sb insert-at))))
                    (when (pos? n)
                      (swap! !live-blocks
                             (fn [blocks]
                               (reduce-kv
                                (fn [m id block]
                                  (assoc m id
                                         (if (>= (:start-idx block) insert-at)
                                           (update block :start-idx + n)
                                           block)))
                                {} blocks)))))
                (swap! !scrollback into new-lines))
              ;; When live blocks exist, use render-viewport! to avoid ghost
              ;; duplication from hardware scroll conflicting with cursor-positioned
              ;; block rendering. Without live blocks, use fast hardware scroll.
              ;; When a popover is active, defer the terminal write — scrollback
              ;; data above is already updated; the popover dismissal flushes via render-viewport!.
              (if (popover-active?)
                (mark-dirty!)
                (if (seq @!live-blocks)
                  (render-viewport!)
                  ;; Hardware-scroll path. Position cursor at column 1 of
                  ;; the scroll-bottom row BEFORE each line so embedded
                  ;; `\n`s in multi-line `s` don't rely on tty `ONLCR`
                  ;; (which is off in raw / alt-screen contexts) to
                  ;; bring the cursor back to column 1. Without this,
                  ;; subsequent lines stay at the previous column and
                  ;; visually overlay each other at scroll-bottom — the
                  ;; "no newlines, multiple emits run together" symptom.
                  (raw-write!
                   w
                   (apply str
                          (mapcat (fn [line]
                                    [(ansi/cursor-to scroll-bottom 1) "\n" line])
                                  new-lines))))))
            (raw-write! w (str s "\n"))))))))

(defn write-inline!
  "Write without trailing newline (for streaming).
   Fullscreen: positions in scroll region. Cursor left in region (hidden).
   Inline: plain write."
  [s]
  (when s
    (locking layout-lock
      (let [w (get-writer)]
        (when w
          (if (fullscreen?)
            (let [{:keys [scroll-bottom]} @!layout]
              (raw-write! w (str (ansi/cursor-to scroll-bottom 1)
                                 "\n" s)))
            (raw-write! w (str s))))))))

(defn write-raw-chars!
  "Write raw characters to the terminal without any positioning or newlines.
   Used for character-by-character echo (e.g. free-text input mode)."
  [s]
  (when s
    (locking layout-lock
      (let [w (get-writer)]
        (when w
          (raw-write! w s))))))

;; ============================================================================
;; Chrome Drawing (status bar, separator, input prompt)
;; ============================================================================

(defn- draw-plain-separator!
  "Draw a plain dim ─── line at the given row. Caller must hold layout-lock."
  [w row cols]
  (raw-write! w (str (ansi/cursor-to row 1)
                     ansi/erase-line
                     (ansi/style (apply str (repeat cols ansi/h-line)) ansi/dim))))

(defn draw-separator!
  "Draw top separator (between scroll region and input).
   When scrolled up, shows scroll indicator in bright-yellow.
   When at live position, draws plain dim ─── line.
   When a popover is active, the terminal write is deferred (dirty flag set)."
  []
  (when (fullscreen?)
    (locking layout-lock
      (if (popover-active?)
        (mark-dirty!)
        (let [w (get-writer)
              {:keys [separator-row cols viewport-offset scroll-bottom]} @!layout]
          (when (and w separator-row)
            (if (and viewport-offset (pos? viewport-offset))
              ;; Scrolled up — show position indicator
              (let [total  (count @!scrollback)
                    end    (- total viewport-offset)
                    start  (max 0 (- end scroll-bottom))
                    label  (str " lines " (inc start) "-" end " of " total " (PgUp/PgDn) ")
                    label-len (count label)
                    left-len  (max 3 (quot (- cols label-len) 2))
                    right-len (max 3 (- cols label-len left-len))
                    left  (apply str (repeat left-len ansi/h-line))
                    right (apply str (repeat right-len ansi/h-line))]
                (raw-write! w (str (ansi/cursor-to separator-row 1)
                                   ansi/erase-line
                                   (ansi/muted left)
                                   (ansi/warning label)
                                   (ansi/muted right))))
              ;; At live position — plain dim line
              (draw-plain-separator! w separator-row cols))))))))

(defn draw-bottom-separator!
  "Draw bottom separator (between input and status). Always a plain dim ─── line."
  []
  (when (fullscreen?)
    (locking layout-lock
      (let [w (get-writer)
            {:keys [separator2-row cols]} @!layout]
        (when (and w separator2-row)
          (draw-plain-separator! w separator2-row cols))))))

(defn- strip-ansi
  "Remove ANSI escape sequences for visible-length calculation."
  [^String s]
  (str/replace s #"\033\[[0-9;]*m" ""))

(def ^:private status-right-pad 1)

(defn set-input-active!
  "Mark whether the user is at the input prompt.
   When false, show-cursor sequences are suppressed in render functions."
  [active?]
  (swap! !layout assoc :input-active (boolean active?)))

(defn input-active?
  "True when the user is at the input prompt (loop top → submit)."
  []
  (boolean (:input-active @!layout)))

(defn set-input-empty!
  "Record whether the input buffer is currently empty (the placeholder/help
   tip is showing). Stamped by the input redraw so background writers can
   tell an idle prompt from one the user is mid-typing into."
  [empty?]
  (swap! !layout assoc :input-empty? (boolean empty?)))

(defn input-empty?
  "True when the input buffer is currently empty (placeholder visible)."
  []
  (boolean (:input-empty? @!layout)))

(defn- maybe-show-cursor
  "Return show-cursor only when :input-active is true, else empty string."
  []
  (if (:input-active @!layout) ansi/show-cursor ""))

(defn draw-status-bar!
  "Erase + write status bar with optional left text and right-aligned status text.
   Single-arity sets right text only (left cleared). Two-arity sets both.
   When a popover is active, the terminal write is deferred (cursor moves would
   disrupt the menu display). State is still updated so dirty flush picks it up."
  ([right-text] (draw-status-bar! nil right-text))
  ([left-text right-text]
   (swap! !layout assoc :status-text right-text :status-left left-text)
   (when (fullscreen?)
     (locking layout-lock
       (if (popover-active?)
         (mark-dirty!)
         (let [w (get-writer)
               {:keys [status-row input-row cols]} @!layout]
           (when (and w status-row)
             (let [left           (or left-text "")
                   right          (or right-text "")
                   left-vis-len   (count (strip-ansi left))
                   right-vis-len  (count (strip-ansi right))
                   ;; Gap between left text and right text
                   gap            (max 1 (- cols left-vis-len right-vis-len
                                            status-right-pad 1))]
               (raw-write! w (str (ansi/cursor-to status-row 1)
                                  ansi/erase-line
                                  " " left
                                  (apply str (repeat gap " "))
                                  right
                                  ;; Return cursor to input prompt at last known position
                                  ;; (use tracked row so multi-row input keeps cursor on user's line)
                                  (when input-row
                                    (str (ansi/cursor-to (or (:input-cursor-row @!layout) input-row)
                                                         (or (:input-cursor-col @!layout) 3))
                                         (maybe-show-cursor)))))))))))))

(defn draw-tab-strip!
  "Paint the tab strip row (between separator2 and status). `text` is a pre-styled
   ANSI string built by the caller (sessions/format-tab-strip). Caller is
   responsible for truncating to terminal width.
   The text is written starting at column 1 (no extra leading space) — each
   segment in `format-tab-strip` already carries its own leading space, which
   keeps the first tab visually aligned at column 2 to match `draw-status-bar!`.
   Mirrors draw-status-bar!'s cursor-restore behavior so input focus survives."
  ([] (draw-tab-strip! (:tab-strip-text @!layout)))
  ([text]
   (swap! !layout assoc :tab-strip-text (or text ""))
   (when (fullscreen?)
     (locking layout-lock
       (if (popover-active?)
         (mark-dirty!)
         (let [w (get-writer)
               {:keys [tab-row input-row]} @!layout]
           (when (and w tab-row)
             (raw-write! w (str (ansi/cursor-to tab-row 1)
                                ansi/erase-line
                                (or text "")
                                (when input-row
                                  (str (ansi/cursor-to (or (:input-cursor-row @!layout) input-row)
                                                       (or (:input-cursor-col @!layout) 3))
                                       (maybe-show-cursor))))))))))))

(defn set-input-cursor-col!
  "Track the current cursor column in the input row (1-based).
   Called by redraw-input-line! so that draw-status-bar! / redraw-chrome!
   can restore the cursor to the correct position."
  [col]
  (swap! !layout assoc :input-cursor-col col))

(defn set-input-cursor-pos!
  "Track both the row and column of the cursor in the input area (1-based).
   For multi-row input, row may be > input-row (the top of the input block).
   Used by chrome restorers to put the cursor back at the user's actual line."
  [row col]
  (swap! !layout assoc :input-cursor-row row :input-cursor-col col))

(defn draw-input-prompt!
  "Erase + write prompt at input-row, position cursor after prompt text."
  [prompt]
  (when (fullscreen?)
    (locking layout-lock
      (let [w (get-writer)
            {:keys [input-row]} @!layout]
        (when (and w input-row)
          (raw-write! w (str (ansi/cursor-to input-row 1)
                             ansi/erase-line
                             prompt
                             (maybe-show-cursor))))))))

(defn restore-input-cursor!
  "Reposition the hardware cursor back to the input prompt at its last
   tracked position, WITHOUT redrawing buffer content. For async writers
   (e.g. background MCP connect settles) that emit into the scroll region
   while the user sits idle at the prompt: `write-output!`'s fast path
   leaves the cursor at the scroll-bottom (end of the emitted line); this
   returns it to where the user is typing. Mirrors the cursor-restore tail
   of `draw-status-bar!`. No-op outside fullscreen, when no input-row is
   known, or under a popover (which owns the cursor)."
  []
  (when (fullscreen?)
    (locking layout-lock
      (when-not (popover-active?)
        (let [w (get-writer)
              {:keys [input-row input-cursor-row input-cursor-col]} @!layout]
          (when (and w input-row)
            (raw-write! w (str (ansi/cursor-to (or input-cursor-row input-row)
                                               (or input-cursor-col 3))
                               (maybe-show-cursor)))))))))

;; ============================================================================
;; Viewport Scrolling
;; ============================================================================

(defn render-viewport!
  "Clear scroll region and redraw from !scrollback at current viewport offset.
   Content is bottom-anchored: blank rows at top, content near the input area.
   Uses cursor-to per row + erase-line + content. Single raw-write! call.
   Must be called inside locking layout-lock.
   When a popover is active, the terminal write is deferred (dirty flag set)."
  []
  (when (fullscreen?)
    (locking layout-lock
      (if (popover-active?)
        (mark-dirty!)
        (let [w (get-writer)
              {:keys [scroll-bottom viewport-offset collapse-highlight]} @!layout
              lines @!scrollback
              total (count lines)
              ;; viewport-offset 0 = show latest (tail), N = scrolled up N lines
              end   (- total viewport-offset)
              start (max 0 (- end scroll-bottom))
              visible (subvec lines (max 0 start) (max 0 end))
              visible-count (count visible)
              ;; Bottom-anchor: blank rows at top, content at bottom
              blank-rows (max 0 (- scroll-bottom visible-count))
              highlight-idx (:start-idx collapse-highlight)
              highlight-id (:id collapse-highlight)
              ;; Marker format mirrors `display-block.core.marker/marker-re`:
              ;; `[*Block:<id>* collapsed: …]` / `[*Block:<id>* expanded: …]`.
              ;; Inlined to avoid a cross-ns require on the hot render path.
              marker-substr (when highlight-id (str "*Block:" highlight-id "*"))
              marker-re-collapsed #"\[\*Block:[a-z0-9]+\* collapsed:[^\]]*\]"
              marker-re-expanded  #"\[\*Block:[a-z0-9]+\* expanded:[^\]]*\]"
              highlight-line (fn [^String line]
                               (if (and marker-substr (str/includes? line marker-substr))
                                 (let [marker (or (re-find marker-re-collapsed line)
                                                  (re-find marker-re-expanded line))]
                                   (if marker
                                     (str/replace line marker
                                                  (str ansi/reverse-video marker ansi/reset))
                                     line))
                                 line))]
          (when w
            (let [sb (StringBuilder.)]
              (dotimes [row scroll-bottom]
                (.append sb (ansi/cursor-to (inc row) 1))
                (.append sb ^String ansi/erase-line)
                (when (>= row blank-rows)
                  (let [sb-idx (+ start (- row blank-rows))]
                    (when-let [line (get visible (- row blank-rows))]
                      (.append sb ^String (if (= sb-idx highlight-idx)
                                            (highlight-line line)
                                            line))))))
              (raw-write! w (.toString sb)))))))))

(defn scroll-page-up!
  "Scroll viewport up by one page. Clamps to max offset."
  []
  (when (fullscreen?)
    (let [{:keys [scroll-bottom]} @!layout
          total (count @!scrollback)
          max-offset (max 0 (- total scroll-bottom))]
      (swap! !layout update :viewport-offset
             (fn [off] (min max-offset (+ off scroll-bottom))))
      (render-viewport!)
      (draw-separator!))))

(defn scroll-page-down!
  "Scroll viewport down by one page. Clamps to 0 (live)."
  []
  (when (fullscreen?)
    (let [{:keys [scroll-bottom]} @!layout]
      (swap! !layout update :viewport-offset
             (fn [off] (max 0 (- off scroll-bottom))))
      (render-viewport!)
      (draw-separator!))))

(defn scroll-lines-up!
  "Scroll viewport up by n lines (default 3). Clamps to max offset."
  ([] (scroll-lines-up! 3))
  ([n]
   (when (fullscreen?)
     (let [{:keys [scroll-bottom]} @!layout
           total (count @!scrollback)
           max-offset (max 0 (- total scroll-bottom))]
       (swap! !layout update :viewport-offset
              (fn [off] (min max-offset (+ off n))))
       (render-viewport!)
       (draw-separator!)))))

(defn scroll-lines-down!
  "Scroll viewport down by n lines (default 3). Clamps to 0 (live)."
  ([] (scroll-lines-down! 3))
  ([n]
   (when (fullscreen?)
     (swap! !layout update :viewport-offset
            (fn [off] (max 0 (- off n))))
     (render-viewport!)
     (draw-separator!))))

(defn scroll-to-bottom!
  "Reset viewport to live output (offset 0). No-op if already at bottom."
  []
  (when (and (fullscreen?) (pos? (:viewport-offset @!layout)))
    (swap! !layout assoc :viewport-offset 0)
    (render-viewport!)
    (draw-separator!)))

;; ============================================================================
;; Live Blocks — in-scrollback regions that update in-place
;; ============================================================================

(defn- splice-scrollback!
  "Replace scrollback lines [start-idx, start-idx+delete-count) with new-lines.
   Returns the delta (count new-lines - delete-count).
   Caller must hold layout-lock."
  [start-idx delete-count new-lines]
  (let [new-count (count new-lines)
        delta (- new-count delete-count)]
    (swap! !scrollback
           (fn [sb]
             (into (into (subvec sb 0 start-idx) new-lines)
                   (subvec sb (+ start-idx delete-count)))))
    delta))

(defn- adjust-blocks-after!
  "Shift start-idx of all live blocks positioned after changed-start by delta.
   Caller must hold layout-lock."
  [changed-start delta]
  (when (not= delta 0)
    (swap! !live-blocks
           (fn [blocks]
             (reduce-kv
              (fn [m id block]
                (if (> (:start-idx block) changed-start)
                  (assoc m id (update block :start-idx + delta))
                  (assoc m id block)))
              {}
              blocks)))))

(defn- render-block-rows!
  "Re-render only the viewport rows that overlap with scrollback range
   [block-start, block-start+line-count). Restores cursor to input prompt.
   Caller must hold layout-lock.
   When a popover is active, the terminal write is deferred (dirty flag set)."
  [block-start line-count]
  (when (fullscreen?)
    (if (popover-active?)
      (mark-dirty!)
      (let [w (get-writer)
            {:keys [scroll-bottom viewport-offset input-row input-cursor-col input-cursor-row]} @!layout
            lines @!scrollback
            total (count lines)
            view-end   (- total viewport-offset)
            view-start (max 0 (- view-end scroll-bottom))
            block-end  (+ block-start line-count)
            vis-start  (max block-start view-start)
            vis-end    (min block-end view-end)]
        (when (and w (< vis-start vis-end))
          (let [sb (StringBuilder.)
                blank-rows (max 0 (- scroll-bottom (- view-end view-start)))]
            (doseq [idx (range vis-start vis-end)]
              (let [row (+ 1 blank-rows (- idx view-start))]
                (.append sb (ansi/cursor-to row 1))
                (.append sb ^String ansi/erase-line)
                (.append sb ^String (get lines idx ""))))
            ;; Restore cursor to input prompt — use tracked row/col so multi-row
            ;; input keeps the cursor on the user's actual line, not the top.
            (when input-row
              (.append sb (ansi/cursor-to (or input-cursor-row input-row)
                                          (or input-cursor-col 3))))
            (raw-write! w (.toString sb))))))))

(defn- sticky-bottom-entry
  "Return [id block] of the first sticky-bottom live block, or nil.
   Caller should hold layout-lock to keep the result consistent."
  []
  (some (fn [[id b]] (when (:sticky-bottom? b) [id b])) @!live-blocks))

(defn update-live-block!
  "Update (or create) a live block with new content lines.

   If the block exists, replaces its scrollback lines in-place (and the
   `:sticky-bottom?` flag on the entry is preserved from the original
   create call — `opts` is ignored on update).

   If new, appends to the tail of scrollback — except when a sticky-bottom
   live block already exists AND the new block is not itself sticky-bottom:
   in that case the new lines are inserted just *before* the sticky-bottom
   block so the sticky block stays anchored at the bottom of the live-block
   region. The sticky block's `:start-idx` (and any other block at or after
   the insert point) is shifted forward by the new line count.

   Selectively re-renders affected viewport rows."
  ([block-id new-lines] (update-live-block! block-id new-lines nil))
  ([block-id new-lines opts]
   (let [sticky? (boolean (:sticky-bottom? opts))]
     (locking layout-lock
       (if-let [existing (get @!live-blocks block-id)]
         ;; Existing block: splice in-place (preserve original sticky flag)
         (let [{:keys [start-idx line-count sticky-bottom?]} existing
               old-count line-count
               new-count (count new-lines)
               delta (splice-scrollback! start-idx old-count new-lines)]
           (swap! !live-blocks assoc block-id
                  {:start-idx start-idx
                   :line-count new-count
                   :sticky-bottom? sticky-bottom?})
           (when (not= delta 0)
             (adjust-blocks-after! start-idx delta))
           (if (= old-count new-count)
             (render-block-rows! start-idx new-count)
             (do (render-viewport!)
                 (draw-separator!))))
         ;; New block: anchor sticky bottoms below all other blocks
         (let [new-count (count new-lines)
               sticky-bot (when-not sticky? (sticky-bottom-entry))
               insert-at (if sticky-bot
                           (:start-idx (second sticky-bot))
                           (count @!scrollback))]
           (swap! !scrollback
                  (fn [sb]
                    (into (into (subvec sb 0 insert-at) new-lines)
                          (subvec sb insert-at))))
           ;; Record the new block AND shift any block whose start-idx is
           ;; >= insert-at (i.e. the sticky bottom, if we pushed it forward).
           (swap! !live-blocks
                  (fn [blocks]
                    (let [shifted (reduce-kv
                                   (fn [m id b]
                                     (if (>= (:start-idx b) insert-at)
                                       (assoc m id (update b :start-idx + new-count))
                                       (assoc m id b)))
                                   {} blocks)]
                      (assoc shifted block-id
                             {:start-idx insert-at
                              :line-count new-count
                              :sticky-bottom? sticky?}))))
           ;; Render the new block rows
           (render-viewport!)
           (draw-separator!)))))))

(defn freeze-live-block!
  "Freeze a live block — its lines become normal scrollback. No more updates."
  [block-id]
  (swap! !live-blocks dissoc block-id))

(defn dispose-live-block!
  "Remove a live block AND its lines from scrollback. Adjusts start-idx of any
   live blocks that came after it. Re-renders viewport so the gap is closed.
   When a popover is active, the terminal write is deferred (dirty flag set)."
  [block-id]
  (locking layout-lock
    (when-let [{:keys [start-idx line-count]} (get @!live-blocks block-id)]
      ;; Remove the block's lines from scrollback
      (swap! !scrollback
             (fn [sb]
               (into (subvec sb 0 start-idx)
                     (subvec sb (+ start-idx line-count)))))
      ;; Remove the block entry
      (swap! !live-blocks dissoc block-id)
      ;; Shift any later live blocks up by line-count
      (when (pos? line-count)
        (swap! !live-blocks
               (fn [blocks]
                 (reduce-kv
                  (fn [m id b]
                    (assoc m id (if (> (:start-idx b) start-idx)
                                  (update b :start-idx - line-count)
                                  b)))
                  {} blocks))))
      ;; Repaint scrollback so the removed rows are gone
      (if (popover-active?)
        (mark-dirty!)
        (do (render-viewport!)
            (draw-separator!))))))

(defn- earliest-live-block-idx
  "Return the start-idx of the earliest live block, or nil if none."
  []
  (when-let [blocks (seq (vals @!live-blocks))]
    (apply min (map :start-idx blocks))))

(defn- recalc-layout-rows!
  "Recalculate row positions given current sticky area heights, popover menu
   height, and input-height (multi-row input area).
   Clamps all to ensure scroll-bottom >= 3. Stacking order below scroll region:
   agent-activity → task-activity → separator → input-h → menu-h → separator2 → tab → status.
   When menu-h > 0, the menu inserts between the input block and separator2,
   so the menu appears directly below the input line. The bottom chrome
   (separator2 + tab + status) stays pinned at the very bottom; input/
   separator/scroll-region shift up to make room for the menu.
   Input row returned is the TOP of the input block; input block spans
   [input-row .. input-row + input-h - 1].
   Clamping priority (most expendable first): agent-activity → task-activity."
  ([task-activity-height agent-activity-height]
   (recalc-layout-rows! task-activity-height agent-activity-height
                        (or (:menu-height @!layout) 0)
                        (or (:input-height @!layout) 1)))
  ([task-activity-height agent-activity-height menu-height]
   (recalc-layout-rows! task-activity-height agent-activity-height
                        menu-height
                        (or (:input-height @!layout) 1)))
  ([task-activity-height agent-activity-height menu-height input-height]
   (let [{:keys [rows]} @!layout
         menu-h (max 0 (min menu-height (max 0 (- rows 7))))
         ;; Cap input-height so chrome + scroll region stays viable
         input-h-max (max 1 (min (max 3 (quot rows 3))
                                 (max 1 (- rows menu-h 3 3))))
         input-h (max 1 (min input-height input-h-max))
         ;; Chrome block height: 1 (separator) + input-h + 1 (separator2) + 1 (tab) + 1 (status) = input-h + 4
         chrome-h (+ 4 input-h)
         ;; Clamp: ensure scroll-bottom >= 3 (accounting for menu reservation + input growth)
         available (max 0 (- rows chrome-h menu-h 3))
         aa-h (min agent-activity-height available)
         ta-h (min task-activity-height (max 0 (- available aa-h)))
         scroll-bottom  (- rows chrome-h menu-h ta-h aa-h)
         ;; Bottom chrome (separator2/tab/status) is pinned to the bottom.
         ;; Menu (menu-h rows) sits directly above separator2, below the
         ;; input block. Input + separator + scroll region shift up by
         ;; menu-h to make room.
         separator-row  (- rows menu-h input-h 3)
         input-row      (- rows menu-h input-h 2)  ;; TOP of input block
         separator2-row (- rows 2)
         tab-row        (- rows 1)
         status-row     rows]
     (swap! !layout assoc
            :scroll-bottom scroll-bottom
            :separator-row separator-row
            :input-row input-row
            :separator2-row separator2-row
            :tab-row tab-row
            :status-row status-row
            :task-activity-height ta-h
            :agent-activity-height aa-h
            :menu-height menu-h
            :input-height input-h
            :input-height-max input-h-max)
     scroll-bottom)))

(declare draw-task-activity-area!)
(declare draw-agent-activity-area!)

(defn- repaint-after-resize!
  "After recalc-layout-rows! has shifted the layout, push a new scroll region
   ANSI code and redraw everything (scrollback + sticky areas + chrome).
   Caller should NOT hold layout-lock (callees acquire it)."
  [scroll-bottom]
  ;; Update scroll region
  (locking layout-lock
    (let [w (get-writer)]
      (when w
        (raw-write! w (str (ansi/set-scroll-region 1 scroll-bottom))))))
  ;; Redraw scrollback in resized region
  (render-viewport!)
  ;; Redraw sticky areas
  (draw-agent-activity-area!)
  (draw-task-activity-area!)
  ;; Redraw all chrome
  (draw-separator!)
  (draw-bottom-separator!)
  (draw-tab-strip!)
  (let [{:keys [status-text status-left]} @!layout]
    (when (seq status-text)
      (draw-status-bar! status-left status-text))))

(defn resize-sticky-areas!
  "Resize scroll region and redraw everything for new sticky area heights.
   Preserves the current :menu-height when recalculating. When a popover is
   active, defer the terminal writes (layout state still updates so coordinate
   math stays correct). The dirty flag triggers a full redraw on popover dismiss."
  [new-task-activity-h new-agent-activity-h]
  (let [scroll-bottom (recalc-layout-rows! new-task-activity-h new-agent-activity-h)]
    (if (popover-active?)
      ;; Layout state already updated above; defer all paints
      (locking layout-lock (mark-dirty!))
      (repaint-after-resize! scroll-bottom))))

(defn set-input-height!
  "Grow/shrink the input area to input-h rows. Shifts chrome up (separator +
   input + separator2 + status all move), shrinks scroll region. input-h=1
   restores single-row input.
   No-op if input-h equals the current :input-height.
   Called from redraw-input-line! when the buffer's word-wrapped visual-line
   count changes. Bypasses the popover gate (legitimate layout shift)."
  [input-h]
  (when (and (fullscreen?)
             (not= (or (:input-height @!layout) 1) input-h))
    (let [was-popover? (popover-active?)]
      ;; Clear cursor-row tracking — old row may now be outside the input block.
      ;; The next redraw-input-line! will set it correctly. Until then, chrome
      ;; restorers fall back to input-row (top of block).
      (swap! !layout assoc :input-cursor-row nil)
      (when was-popover? (set-popover-active! false))
      (try
        (let [{:keys [task-activity-height agent-activity-height menu-height]} @!layout
              scroll-bottom (recalc-layout-rows! task-activity-height
                                                 agent-activity-height
                                                 (or menu-height 0)
                                                 input-h)]
          (repaint-after-resize! scroll-bottom))
        (finally
          (when was-popover? (set-popover-active! true)))))))

(defn set-menu-height!
  "Reserve menu-h rows at the bottom of the screen for a popover menu.
   Shifts chrome block (separator + input + separator2 + status) up by menu-h;
   shrinks scroll region by menu-h. menu-h=0 restores normal layout.
   No-op if menu-h equals the current :menu-height (avoids wiping an active menu
   on idempotent redraws).

   This is the legitimate layout-shift operation that accompanies menu show/hide —
   it always paints (bypasses the popover gate) because the caller is the popover
   itself. Callers should NOT hold layout-lock (callees acquire it)."
  [menu-h]
  (when (and (fullscreen?)
             (not= (or (:menu-height @!layout) 0) menu-h))
    (let [was-popover? (popover-active?)]
      ;; Temporarily disable the popover gate so repaint-after-resize! actually
      ;; paints. This is safe because the resize is the popover's own layout
      ;; update — not a background writer that would conflict with the menu.
      (when was-popover? (set-popover-active! false))
      (try
        (let [{:keys [task-activity-height agent-activity-height]} @!layout
              scroll-bottom (recalc-layout-rows! task-activity-height
                                                 agent-activity-height
                                                 menu-h)]
          (repaint-after-resize! scroll-bottom))
        (finally
          (when was-popover? (set-popover-active! true)))))))

;; ============================================================================
;; Sticky Task Activity Area
;; ============================================================================

(defn- draw-task-activity-area!
  "Draw the sticky task activity area between scroll-bottom + agent-activity and the chrome block.
   Renders pre-built line strings from :task-activity-data.
   Must NOT be called inside locking layout-lock (acquires it internally).
   When a popover is active, the terminal write is deferred (dirty flag set)."
  []
  (cond
    (not (fullscreen?)) nil
    (popover-active?) (mark-dirty!)
    :else
    (let [{:keys [task-activity-height task-activity-data
                  agent-activity-height scroll-bottom cols]} @!layout]
      (when (and (pos? task-activity-height) (seq task-activity-data))
        (let [w (get-writer)]
          (when w
            (let [sb (StringBuilder.)
                  ;; Task activity starts after scroll-bottom + agent-activity
                  start-row (+ (inc scroll-bottom) agent-activity-height)
                  n-lines (min task-activity-height (count task-activity-data))]
              ;; Render each line at its row position
              (doseq [idx (range n-lines)]
                (let [row (+ start-row idx)
                      line (nth task-activity-data idx)]
                  (.append sb (ansi/cursor-to row 1))
                  (.append sb ^String ansi/erase-line)
                  (.append sb ^String (str line))))
              ;; Clear any remaining rows in the area
              (doseq [idx (range n-lines task-activity-height)]
                (let [row (+ start-row idx)]
                  (.append sb (ansi/cursor-to row 1))
                  (.append sb ^String ansi/erase-line)))
              (locking layout-lock
                (raw-write! w (.toString sb))))))))))

(defn update-task-activity!
  "Update the sticky task activity area with pre-rendered lines.
   lines: vector of ANSI-formatted strings (one per row, max 5).
   In fullscreen: resizes scroll region if height changed, redraws area.
   In inline mode: no-op."
  [lines]
  (when (fullscreen?)
    (let [lines-vec (when (seq lines) (vec (take 5 lines)))
          new-height (count (or lines-vec []))
          {:keys [task-activity-height agent-activity-height]} @!layout]
      (swap! !layout assoc :task-activity-data lines-vec)
      (if (not= new-height task-activity-height)
        (resize-sticky-areas! new-height agent-activity-height)
        (draw-task-activity-area!)))))

(defn clear-task-activity!
  "Clear the task activity area and restore scroll region."
  []
  (when (fullscreen?)
    (swap! !layout assoc :task-activity-data nil)
    (when (pos? (:task-activity-height @!layout))
      (resize-sticky-areas! 0 (:agent-activity-height @!layout)))))

;; ============================================================================
;; Sticky Agent Activity Panel
;; ============================================================================

(defn- draw-agent-activity-area!
  "Draw the sticky agent activity panel between scroll-bottom and task-activity.
   Renders pre-built line strings from :agent-activity-data.
   Must NOT be called inside locking layout-lock (acquires it internally).
   When a popover is active, the terminal write is deferred (dirty flag set)."
  []
  (cond
    (not (fullscreen?)) nil
    (popover-active?) (mark-dirty!)
    :else
    (let [{:keys [agent-activity-height agent-activity-data
                  scroll-bottom cols]} @!layout]
      (when (and (pos? agent-activity-height) (seq agent-activity-data))
        (let [w (get-writer)]
          (when w
            (let [sb (StringBuilder.)
                  ;; Agent activity starts right after scroll-bottom
                  start-row (inc scroll-bottom)
                  n-lines (min agent-activity-height (count agent-activity-data))]
              ;; Render each line at its row position
              (doseq [idx (range n-lines)]
                (let [row (+ start-row idx)
                      line (nth agent-activity-data idx)]
                  (.append sb (ansi/cursor-to row 1))
                  (.append sb ^String ansi/erase-line)
                  (.append sb ^String (str line))))
              ;; Clear any remaining rows in the area
              (doseq [idx (range n-lines agent-activity-height)]
                (let [row (+ start-row idx)]
                  (.append sb (ansi/cursor-to row 1))
                  (.append sb ^String ansi/erase-line)))
              (locking layout-lock
                (raw-write! w (.toString sb))))))))))

(defn update-agent-activity!
  "Update the sticky agent activity panel with pre-rendered lines.
   lines: vector of ANSI-formatted strings (one per row, max 15).
   In fullscreen: resizes scroll region if height changed, redraws area.
   In inline mode: no-op."
  [lines]
  (when (fullscreen?)
    (let [lines-vec (when (seq lines) (vec (take 15 lines)))
          new-height (count (or lines-vec []))
          {:keys [agent-activity-height task-activity-height]} @!layout]
      (swap! !layout assoc :agent-activity-data lines-vec)
      (if (not= new-height agent-activity-height)
        (resize-sticky-areas! task-activity-height new-height)
        (draw-agent-activity-area!)))))

(defn clear-agent-activity!
  "Clear the agent activity panel and restore scroll region."
  []
  (when (fullscreen?)
    (swap! !layout assoc :agent-activity-data nil)
    (when (pos? (:agent-activity-height @!layout))
      (resize-sticky-areas! (:task-activity-height @!layout) 0))))

;; ============================================================================
;; Status Bar Formatting
;; ============================================================================

(defn- format-signed-delta
  "Render a signed token delta as e.g. `+1,234 tok` / `-280 tok` /
   `+0 tok`.  Used inside the calls segment of the status bar."
  [delta]
  (str (if (neg? delta) "-" "+")
       (fmt/format-number (Math/abs (long delta)))
       " tok"))

(defn format-status
  "Build status bar string from agent state as right-aligned columns.
   {:status :idle|:running, :calls N, :tokens N, :cost 0.0,
    :last-input-tokens N|nil, :input-tokens-delta M|nil,
    :tasks-running N, :queue-count N}

   When `:last-input-tokens` is supplied, the calls segment expands to
   `N calls (last <K> in[, +/-M tok])`; the delta parenthetical is
   omitted on the very first recorded call (no previous to compare)."
  [{:keys [status calls tokens cost tasks-running queue-count
           last-input-tokens input-tokens-delta]}]
  (let [status-str  (case status
                      :running (ansi/success "running")
                      :paused  (ansi/warning "paused")
                      :idle    (ansi/muted "idle")
                      (ansi/muted (if (keyword? status)
                                    (name status)
                                    (str (or status "idle")))))
        tasks-str   (when (and tasks-running (pos? tasks-running))
                      (ansi/warning (str tasks-running " task"
                                         (when (> tasks-running 1) "s"))))
        queue-str   (when (and queue-count (pos? queue-count))
                      (ansi/style (str queue-count " queued")
                                  ansi/bold ansi/bright-yellow))
        calls-n     (or calls 0)
        calls-base  (str calls-n " call" (when (not= 1 calls-n) "s"))
        calls-suffix (when last-input-tokens
                       (str " (last " (fmt/format-number last-input-tokens) " in"
                            (when input-tokens-delta
                              (str ", " (format-signed-delta input-tokens-delta)))
                            ")"))
        calls-str   (ansi/muted (str calls-base calls-suffix))
        tokens-str  (ansi/muted (str (fmt/format-number (or tokens 0)) " tokens"))
        cost-str    (ansi/muted (str "$" (format "%.4f" (double (or cost 0.0)))))
        sep         (ansi/muted (str " " ansi/v-line " "))]
    (cond-> (str status-str)
      queue-str (str sep queue-str)
      tasks-str (str sep tasks-str)
      true      (str sep calls-str sep tokens-str sep cost-str))))

;; ============================================================================
;; Fullscreen Lifecycle
;; ============================================================================

(defn redraw-chrome!
  "Redraw separators + status bar + sticky areas, then return cursor to input prompt.
   Fixes corruption from Enter-induced scrolling."
  []
  (when (fullscreen?)
    (draw-separator!)
    (draw-agent-activity-area!)
    (draw-task-activity-area!)
    (draw-bottom-separator!)
    (draw-tab-strip!)
    (let [{:keys [status-text status-left input-row input-cursor-col input-cursor-row]} @!layout]
      (if (seq status-text)
        ;; draw-status-bar! restores cursor to input row
        (draw-status-bar! status-left status-text)
        ;; No status text — manually return cursor to input prompt
        (when input-row
          (locking layout-lock
            (when-let [w (get-writer)]
              (raw-write! w (str (ansi/cursor-to (or input-cursor-row input-row)
                                                 (or input-cursor-col 3))
                                 (maybe-show-cursor))))))))))

(defn handle-resize!
  "Handle terminal resize: refresh dimensions, recalculate row layout,
   update scroll region, and redraw everything. No-op in inline mode."
  []
  (when (fullscreen?)
    (fmt/refresh-terminal-size!)
    (let [rows (fmt/terminal-rows)
          cols (fmt/terminal-columns)]
      (when (>= rows 12)
        (let [{:keys [task-activity-height agent-activity-height menu-height input-height]} @!layout
              ta-h           (or task-activity-height 0)
              aa-h           (or agent-activity-height 0)
              menu-h         (or menu-height 0)
              input-h-max    (max 1 (min (max 3 (quot rows 3))
                                         (max 1 (- rows menu-h 3 3))))
              input-h        (max 1 (min (or input-height 1) input-h-max))
              chrome-h       (+ 4 input-h)
              ;; Clamp to ensure scroll-bottom >= 3 (accounting for menu reservation + input)
              available      (max 0 (- rows chrome-h menu-h 3))
              clamped-aa-h   (min aa-h available)
              clamped-ta-h   (min ta-h (max 0 (- available clamped-aa-h)))
              scroll-bottom  (- rows chrome-h menu-h clamped-ta-h clamped-aa-h)
              separator-row  (- rows menu-h input-h 3)
              input-row      (- rows menu-h input-h 2)
              separator2-row (- rows menu-h 2)
              tab-row        (- rows menu-h 1)
              status-row     (- rows menu-h)
              st             (:status-text @!layout)
              sl             (:status-left @!layout)]
          (swap! !layout assoc
                 :rows rows
                 :cols cols
                 :scroll-bottom scroll-bottom
                 :separator-row separator-row
                 :input-row input-row
                 :separator2-row separator2-row
                 :tab-row tab-row
                 :status-row status-row
                 :task-activity-height clamped-ta-h
                 :agent-activity-height clamped-aa-h
                 :menu-height menu-h
                 :input-height input-h
                 :input-height-max input-h-max)
          (locking layout-lock
            (let [w (get-writer)]
              (when w
                (raw-write! w (str ansi/clear-screen
                                   (ansi/set-scroll-region 1 scroll-bottom)
                                   ansi/enable-alt-scroll
                                   (ansi/cursor-to 1 1))))))
          ;; Replay visible scrollback in the new scroll region
          (render-viewport!)
          (draw-agent-activity-area!)
          (draw-task-activity-area!)
          (draw-separator!)
          (draw-bottom-separator!)
          (draw-tab-strip!)
          (when (seq st)
            (draw-status-bar! sl st)))))))

(defn init-fullscreen!
  "Enter alt screen, set up scroll region + chrome.
   Row layout (N = terminal height):
     Rows 1..N-5   scroll region (DECSTBM)
     Row  N-4      top separator (dim ─── / scroll indicator)
     Row  N-3      input prompt
     Row  N-2      bottom separator (dim ─── line)
     Row  N-1      tab strip
     Row  N        status bar (right-aligned)
   Falls back to :inline if terminal too small (< 12 rows)."
  []
  ;; Re-query terminal size at runtime (build-time value may be stale in native image)
  (fmt/refresh-terminal-size!)
  (let [rows (fmt/terminal-rows)
        cols (fmt/terminal-columns)]
    (if (< rows 12)
      ;; Too small — stay inline
      (do (swap! !layout assoc :mode :inline :rows rows :cols cols)
          false)
      ;; Set up fullscreen — 5 static rows (separator, input, separator2, tab, status)
      (let [scroll-bottom  (- rows 5)
            separator-row  (- rows 4)
            input-row      (- rows 3)
            separator2-row (- rows 2)
            tab-row        (- rows 1)
            status-row     rows]
        (reset! !scrollback [])
        (reset! !live-blocks {})
        (swap! !layout assoc
               :mode :fullscreen
               :rows rows
               :cols cols
               :scroll-bottom scroll-bottom
               :separator-row separator-row
               :input-row input-row
               :separator2-row separator2-row
               :tab-row tab-row
               :status-row status-row
               :status-text ""
               :tab-strip-text ""
               :viewport-offset 0
               :task-activity-height 0
               :task-activity-data nil
               :agent-activity-height 0
               :agent-activity-data nil
               :menu-height 0
               :input-height 1
               :input-height-max (max 3 (quot rows 3))
               :input-active false)
        (locking layout-lock
          (let [w (get-writer)]
            (when w
              (raw-write! w (str ansi/enter-alt-screen
                                 ansi/clear-screen
                                 ansi/hide-cursor
                                 (ansi/set-scroll-region 1 scroll-bottom)
                                 ansi/enable-alt-scroll
                                 ;; Start at bottom of scroll region so content
                                 ;; anchors near the input area, not at row 1
                                 (ansi/cursor-to scroll-bottom 1))))))
        true))))

(defn teardown!
  "Reset scroll region, show cursor, leave alt screen.
   Dumps scrollback buffer to normal screen so output is in terminal history.
   Idempotent."
  []
  (when (fullscreen?)
    (let [final-lines @!scrollback]
      (locking layout-lock
        (let [w (get-writer)]
          (when w
            ;; Leave alt screen first, then dump scrollback to normal terminal
            (raw-write! w (str ansi/disable-alt-scroll
                               ansi/reset-scroll-region
                               ansi/show-cursor
                               ansi/leave-alt-screen))
            ;; Replay buffered output so it's in terminal scrollback
            (when (seq final-lines)
              (raw-write! w (str (str/join "\n" final-lines) "\n"))))))
      (reset! !scrollback [])
      (reset! !live-blocks {}))
    (swap! !layout assoc
           :mode :inline
           :scroll-bottom nil
           :separator-row nil
           :input-row nil
           :separator2-row nil
           :tab-row nil
           :status-row nil
           :status-text ""
           :tab-strip-text ""
           :viewport-offset 0
           :task-activity-height 0
           :task-activity-data nil
           :agent-activity-height 0
           :agent-activity-data nil
           :input-active false)))

(defn init-inline!
  "Reset to inline mode (no screen management)."
  []
  (swap! !layout assoc
         :mode :inline
         :scroll-bottom nil
         :separator-row nil
         :input-row nil
         :separator2-row nil
         :status-row nil
         :status-text ""
         :task-activity-height 0
         :task-activity-data nil
         :agent-activity-height 0
         :agent-activity-data nil))
