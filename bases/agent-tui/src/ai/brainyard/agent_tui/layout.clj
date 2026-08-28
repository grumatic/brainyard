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
         :menu-height-hold 0      ;; >0 = the reservation is PINNED for the current input line (see hold-menu-height!); the menu may hide without giving the rows back.
         :input-height   1        ;; input area height in rows (grows with word-wrap / multi-line buffer)
         :input-height-max 6      ;; cap on input-height (recomputed on resize based on terminal rows)
         :input-cursor-col 3      ;; last known cursor column in input row (1-based)
         :input-cursor-row nil    ;; last known cursor terminal row (1-based, nil = top of input block)
         :input-active    false   ;; true when user is at input prompt (controls cursor visibility)
         :writer         nil}))    ;; captured java.io.Writer

(def layout-lock (Object.))

;; ----------------------------------------------------------------------------
;; Mouse reporting
;;
;; Seeded from the `:enable-mouse` config at startup (see `core/run!`), kept here
;; rather than read per-frame because `layout` sits below the config layer and
;; every alt-screen (re)entry needs the answer on the hot path.
;;
;; ON is the default, and the tradeoff is real: while mouse reporting is on the
;; terminal hands click-drag to us instead of selecting text, so selection needs
;; the terminal's bypass modifier (Shift almost everywhere; Option in the
;; xterm.js/`--web` path, which `playground-server/proxy.clj` already widens to
;; accept both). `:enable-mouse false` restores plain-drag selection and falls
;; back to `?1007h` alternate-scroll for the wheel.
(defonce ^:private !mouse-enabled? (atom true))

(defn set-mouse-enabled!
  "Enable/disable mouse reporting for subsequent alt-screen entries and
   repaints. Call BEFORE `init-fullscreen!`; flipping it mid-session only
   takes effect on the next repaint that re-emits the mode sequences."
  [enabled?]
  (reset! !mouse-enabled? (boolean enabled?)))

(defn mouse-enabled?
  "True when mouse reporting is on (the default)."
  []
  @!mouse-enabled?)

(defn- mouse-seq
  "The mouse-enable sequence to splice into an alt-screen setup write, or \"\"
   when reporting is off."
  []
  (if @!mouse-enabled? ansi/enable-mouse ""))

;; Row decorator — an optional last pass over each row `render-viewport!` paints,
;; used to underline clickable targets. Installed by the app (see `core/run!`)
;; rather than called directly, because deciding what is clickable needs the
;; agent's working directory, which `layout` sits below and cannot resolve.
;; Same shape as `sessions/install-tab-strip-builder!`.
;;
;; Unset by default, so nothing here costs anything unless the app opts in.
(defonce ^:private !row-decorator (atom nil))

(defn install-row-decorator!
  "Install `f` (String -> String) as the per-row decoration pass, or nil to
   remove it. `f` MUST preserve display width — it may only add escapes, never
   visible characters — since the row has already been clamped to `cols` by the
   time it runs."
  [f]
  (reset! !row-decorator f))

(defn- decorate
  "Apply the installed row decorator, falling back to the row unchanged. A
   decorator that throws must never take the frame down with it — a missing
   underline is invisible, a half-painted screen is not."
  [^String row]
  (if-let [f @!row-decorator]
    (try (or (f row) row) (catch Throwable _ row))
    row))

;; Scrollback buffer: stores all output lines written to the scroll region.
;; Dumped to normal screen on teardown so user can scroll back in terminal history.
(defonce !scrollback (atom []))

;; Live blocks: regions of !scrollback that can be updated in-place.
;; Live blocks are always at the tail of !scrollback. Normal output inserts before them.
;; {block-id {:start-idx int, :line-count int}}
(defonce !live-blocks (atom {}))

;; ----------------------------------------------------------------------------
;; Reflow source — what lets a resize re-wrap what is already on screen
;;
;; !scrollback holds RENDERED ROWS, hard-wrapped at whatever the width was when
;; they were formatted. That is the right representation to paint from (see
;; `terminal-owns-line-breaking?`), but it is the wrong one to KEEP: on a resize
;; the stored rows are still the old width, and `render-viewport!` replays them
;; verbatim. Narrower, each row overflows, and the next row's `erase-line` wipes
;; the spill — the tail of every line silently disappears, and a spill on the
;; bottom region row scrolls the DECSTBM region out from under the absolute row
;; accounting. Wider, text stays broken at the old column.
;;
;; So each logical emit also records HOW TO RENDER ITSELF at a given width.
;; `!scrollback-src` is an ordered list of entries covering exactly the rows in
;; !scrollback, in the same order:
;;
;;   {:render   (fn [cols] -> string | [rows])
;;    :n        row count this entry currently occupies
;;    :block-id live-block id, or nil for ordinary output
;;    :sticky?  the block's :sticky-bottom? flag}
;;
;; A caller that hands over a pre-formatted string gets `(constantly rows)` and
;; so re-renders to itself — exactly today's behaviour. Reflow is opt-in per
;; producer via `:render`, which is why this could be added without touching all
;; 22 `write-output!` call sites.
;;
;; The list is kept in step with !scrollback by the same functions that mutate
;; it, and `ensure-src!` rebuilds it from scratch whenever the two have drifted
;; (a session switch swaps !scrollback wholesale; a test resets it directly).
;; Drift therefore costs reflowability for the affected rows and nothing else —
;; it can never scramble the screen, because a reflow only ever runs against a
;; list that accounts for exactly the rows on screen.
(defonce !scrollback-src (atom []))

(defn- rows-of
  "Normalise what a `:render` fn returned into a row vector. Producers hand back
   a string with embedded newlines (`format-answer` and friends all do), so the
   split lives here rather than in every caller."
  [x]
  (cond
    (nil? x)    []
    (string? x) (vec (str/split-lines x))
    :else       (vec x)))

(defn- src-rebuild
  "Reconstruct an entry list that describes `rows` + `blocks` exactly, treating
   every row not owned by a live block as its own non-reflowable entry.

   This is the recovery path, and it is why drift is harmless: whatever state
   !scrollback is in, a valid list can always be derived from it. Rows recovered
   this way re-render to themselves, so they stop reflowing — which is precisely
   the behaviour they had before any of this existed."
  [rows blocks]
  (let [starts (into {} (map (fn [[id b]] [(:start-idx b) [id b]])) blocks)
        n      (count rows)]
    (loop [i 0, acc []]
      (if (>= i n)
        acc
        (if-let [[id b] (get starts i)]
          (let [cnt  (max 1 (int (:line-count b)))
                span (subvec rows i (min n (+ i cnt)))]
            (recur (+ i cnt)
                   (conj acc {:render   (constantly span)
                              :n        (count span)
                              :block-id id
                              :sticky?  (boolean (:sticky-bottom? b))})))
          (let [row (nth rows i)]
            (recur (inc i)
                   (conj acc {:render (constantly [row]) :n 1 :block-id nil}))))))))

(defn- src-consistent?
  "True when the entry list accounts for exactly the rows on screen AND names
   exactly the live blocks that exist. Both halves matter: a row-count match
   with a missing block would rebuild `!live-blocks` without it."
  [entries]
  (and (= (reduce + 0 (map :n entries)) (count @!scrollback))
       (= (set (keep :block-id entries)) (set (keys @!live-blocks)))))

(defn describes-exactly?
  "`src-consistent?` for rows and blocks held OUTSIDE the live atoms — a
   background session's snapshot. Same two halves, same reasons."
  [entries rows blocks]
  (and (= (reduce + 0 (map :n entries)) (count rows))
       (= (set (keep :block-id entries)) (set (keys blocks)))))

(defn rebuild-src
  "Pure `src-rebuild`, for callers holding a session snapshot rather than the
   live scrollback. `sessions` maintains the same entry list on the rows it
   buffers for a background tab, and needs the same recovery path when what it
   is handed does not describe them."
  [rows blocks]
  (src-rebuild (vec rows) (or blocks {})))

(defn- ensure-src!
  "Return the entry list, rebuilding it first if it has drifted from
   !scrollback. Called at the top of every mutation, so the invariant holds
   going in and each mutation only has to preserve it."
  []
  (let [entries @!scrollback-src]
    (if (src-consistent? entries)
      entries
      (reset! !scrollback-src (src-rebuild @!scrollback @!live-blocks)))))

(defn- entry-index-at-row
  "Index of the entry whose rows begin at scrollback row `row`, or nil when
   `row` falls inside an entry rather than on a boundary."
  [entries row]
  (loop [i 0, acc 0]
    (cond
      (= acc row)            i
      (> acc row)            nil
      (>= i (count entries)) nil
      :else                  (recur (inc i) (+ acc (long (:n (nth entries i))))))))

(defn- src-insert!
  "Record `entry` as beginning at scrollback row `row`. Falls back to a rebuild
   when `row` is not an entry boundary — the caller's row math and this list
   have diverged, and guessing a split point would be worse than losing reflow."
  [row entry]
  (let [entries (ensure-src!)]
    (if-let [i (entry-index-at-row entries row)]
      (reset! !scrollback-src
              (into (conj (subvec entries 0 i) entry) (subvec entries i)))
      (reset! !scrollback-src []))))

(defn- src-update-block!
  "Point `block-id`'s entry at a new renderer and row count."
  [block-id n render sticky?]
  (let [entries (ensure-src!)
        i (first (keep-indexed (fn [i e] (when (= block-id (:block-id e)) i)) entries))]
    (reset! !scrollback-src
            (if i
              (assoc entries i {:render render :n n :block-id block-id :sticky? sticky?})
              []))))

(defn- src-drop-block!
  "Remove `block-id`'s entry and its rows from the list (dispose)."
  [block-id]
  (let [entries (ensure-src!)
        i (first (keep-indexed (fn [i e] (when (= block-id (:block-id e)) i)) entries))]
    (reset! !scrollback-src
            (if i
              (into (subvec entries 0 i) (subvec entries (inc i)))
              []))))

(defn- src-freeze-block!
  "Detach `block-id` from its entry, leaving the rows as ordinary scrollback.
   The renderer is kept, so a frozen answer still reflows on resize."
  [block-id]
  (let [entries (ensure-src!)
        i (first (keep-indexed (fn [i e] (when (= block-id (:block-id e)) i)) entries))]
    (when i
      (reset! !scrollback-src
              (assoc entries i (-> (nth entries i)
                                   (assoc :block-id nil)
                                   (dissoc :sticky?)))))))

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

(defn terminal-owns-line-breaking?
  "True when the TERMINAL, not this process, decides where a line breaks.

   Inline mode writes a stream and lets the cursor advance on its own, so a
   long line soft-wraps under DECAWM and the terminal remembers it did —
   which is what lets a copy rejoin the paragraph.

   Fullscreen is the opposite: `render-viewport!` writes each scrollback
   entry to an absolutely-positioned row via `cursor-to`, and viewport
   offset, page scrolling and every live block's `:start-idx` all count
   entries AS rows. One soft-wrapped entry would occupy two rows, and every
   row after it would be off by one, cumulatively. Cursor-addressed
   rendering and terminal autowrap cannot both be in charge, so callers
   must pre-wrap there."
  []
  (not (fullscreen?)))

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

(defn- raw-write-direct!
  "Write string to writer, flush. Caller must hold layout-lock.
   The ONLY place bytes actually reach the terminal — every flush here is one
   thing the terminal can present, so anything that flushes twice for one user
   gesture is a visible two-step. Prefer `with-frame`."
  [^java.io.Writer w ^String s]
  (when w
    (try
      (.write w s)
      (.flush w)
      (catch Exception _ nil))))

;; The frame currently open on THIS thread, or nil. A dynamic var rather than a
;; ThreadLocal: `binding` is thread-local by construction, deliberately does not
;; propagate to threads the body spawns (a ticker thread must not join the input
;; thread's frame), and carries no native-image build-time state.
(def ^:private ^:dynamic *frame-batch* nil)

(defn- raw-write!
  "Contribute to the frame open on this thread, or write straight through when
   there is none. Caller must hold layout-lock."
  [^java.io.Writer w ^String s]
  (if-let [^StringBuilder sb *frame-batch*]
    (when (seq s) (.append sb ^String s))
    (raw-write-direct! w s)))

;; ----------------------------------------------------------------------------
;; Cursor ownership
;;
;; Exactly one thing decides where the cursor is and whether it is visible: the
;; frame epilogue below. Individual draw functions never emit show-cursor — they
;; used to, and the result was a cursor that appeared wherever a given repaint
;; happened to finish (`render-viewport!` ends on the last scrollback row, so a
;; viewport repaint parked a VISIBLE cursor in the content area until some later
;; write moved it back).
;; ----------------------------------------------------------------------------

;; What we believe DECTCEM is set to right now. Terminals start with the cursor
;; shown; `init-fullscreen!` hides it. Tracked so the epilogue can emit a
;; hide/show only on a TRANSITION: toggling visibility every frame is itself a
;; blink source, and during a turn frames arrive ~18x/second.
(defonce ^:private !cursor-shown? (atom true))

(defn note-cursor-shown!
  "Record that something outside the frame path made the cursor visible (the
   external-editor handoff in `display-block-ui`). Keeps the transition
   tracking honest; without it the next frame would skip a hide it owes."
  []
  (reset! !cursor-shown? true))

(defn note-cursor-hidden!
  "Counterpart of `note-cursor-shown!` — the autocomplete popover parks the
   cursor hidden at the input line and owns it until dismissed."
  []
  (reset! !cursor-shown? false))

(defn- frame
  "Envelope one repaint as a single, cursor-stable frame.

   A repaint is a sequence of cursor moves — `cursor-to` per line on the scroll
   path, per row in the viewport, per row of a live block. Written bare, a fast
   burst lets the terminal present intermediate states: the cursor flashing at
   column 1 of each row it is about to erase.

   Three mechanisms:

   - Synchronized output (DEC 2026) is the real fix where it exists (xterm.js
     and tmux 3.4+ honour it); an unknown private mode is ignored elsewhere.
   - Hiding the cursor for the duration removes the artifact on terminals
     without 2026.
   - The epilogue PARKS the cursor at the input line before showing it, so a
     frame can only ever end with the cursor hidden or on the input line —
     never mid-content, whatever the body happened to draw last.

   The hide/show pair is emitted only when it changes something. Mid-turn
   `:input-active` is false and the cursor is already hidden, so a whole turn of
   streaming emits ONE hide and then nothing — rather than a hide/show pair per
   frame, which reads as blinking in its own right.

   A POPOVER suspends the show half entirely: while the autocomplete menu owns
   the screen the cursor stays hidden, so arrowing through 20 items is one hide
   and then nothing. Without this the menu redraw was a hide/show pair PER
   KEYSTROKE — the input redraw's frame re-showed the cursor, and the menu paint
   that followed then walked that visible cursor down every one of its rows
   before hiding it again."
  ^String [^String body]
  (let [{:keys [input-active input-row input-cursor-row input-cursor-col]} @!layout
        park  (when (and input-active input-row (not (popover-active?)))
                (ansi/cursor-to (or input-cursor-row input-row)
                                (or input-cursor-col 3)))
        show? (some? park)
        prologue (if @!cursor-shown? ansi/hide-cursor "")
        epilogue (if show? (str park ansi/show-cursor) "")]
    (reset! !cursor-shown? show?)
    (str ansi/begin-sync prologue body epilogue ansi/end-sync)))

(defmacro with-frame
  "Run `body` so that everything it writes is presented as ONE frame.

   Reentrant: a nested `with-frame` (or any draw function called from inside
   one) just contributes to the frame already open on this thread, and only the
   outermost one flushes. That is what collapses a composite gesture into a
   single presentation — `redraw-chrome!` was four separate flushes with three
   separate show-cursors, and a page scroll was two (viewport, then separator).

   Holds `layout-lock` for the whole body, so a ticker thread cannot interleave
   its own repaint into the middle of a composite. Pass-through outside
   fullscreen, where there is no frame to speak of."
  [& body]
  `(locking layout-lock
     (if (or (not (fullscreen?)) (some? *frame-batch*))
       (do ~@body)
       (let [sb# (StringBuilder.)]
         (binding [*frame-batch* sb#]
           (try
             ~@body
             (finally
               (when (pos? (.length sb#))
                 (raw-write-direct! (get-writer) (frame (.toString sb#)))))))))))

(defn draw-frame!
  "Function form of `with-frame` for callers outside this namespace (the input
   redraw in `terminal`). `f` is a thunk."
  [f]
  (with-frame (f)))

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
   Inline: plain write + newline.

   `opts` may carry `:render` — a `(fn [cols] -> string | [rows])` that
   re-renders this emit at an arbitrary width. Supplying it makes the emit
   reflow on terminal resize instead of staying wrapped at the width it was
   formatted for; without it the rows are recorded as-is and behave exactly as
   before. See the `!scrollback-src` commentary above."
  ([s] (write-output! s nil))
  ([s opts]
   (when (and s (not (str/blank? s)))
     (with-frame
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
              ;; Record how to re-render this emit at another width BEFORE the
              ;; rows land, so `src-insert!` still sees the pre-insert list and
              ;; `insert-at` is a boundary in it.
               (src-insert! insert-at
                            {:render   (or (:render opts) (constantly new-lines))
                             :n        n
                             :block-id nil})
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
                                     ;; Decorate here too, or a row is plain
                                     ;; when it first appears and underlined
                                     ;; the moment anything repaints it. These
                                     ;; rows are formatted at the current width
                                     ;; already, so unlike the other two paths
                                     ;; there is no clamp to apply first.
                                     [(ansi/cursor-to scroll-bottom 1) "\n"
                                      (decorate line)])
                                   new-lines))))))
             (raw-write! w (str s "\n")))))))))

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
    (with-frame
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
    (with-frame
      (let [w (get-writer)
            {:keys [separator2-row cols]} @!layout]
        (when (and w separator2-row)
          (draw-plain-separator! w separator2-row cols))))))

;; `strip-ansi` + `count` used to measure the status bar. It is gone in favour
;; of `fmt/display-width`, which is the measure the terminal actually uses:
;; counting stripped CHARS says 1 for a CJK glyph the terminal advances 2
;; columns for, so the row overflowed on exactly the strings a byte-count
;; cannot see coming.

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

(defonce ^{:doc "What the input line currently holds, as `{:buffer :cursor-pos}`.

  The readline editor owns the buffer as a LOCAL, so anything that has to
  repaint the input line without being the editor — the SIGWINCH handler in
  `terminal` — has no other way to reach it. It lives here rather than in
  `terminal` because both ends of the input line write it and `session` (which
  paints the idle prompt) cannot require `terminal` without a cycle.

  It must be cleared by everything that BLANKS the line, not only set by what
  fills it. A submitted line stays in the editor's `!last-input` no longer than
  it stays on screen: the loop repaints an empty prompt on the next iteration,
  and a repaint from a stale record would put the submitted message back into
  the input box on the next resize."}
  !last-input
  (atom {:buffer "" :cursor-pos 0}))

(defn set-last-input!
  "Record the (buffer, cursor-pos) just painted into the input line."
  [buffer cursor-pos]
  (reset! !last-input {:buffer (str buffer) :cursor-pos (or cursor-pos 0)}))

(defn last-input
  "The last (buffer, cursor-pos) painted into the input line."
  []
  @!last-input)

(defn draw-status-bar!
  "Erase + write status bar with optional left text and right-aligned status text.
   Single-arity sets right text only (left cleared). Two-arity sets both.
   When a popover is active, the terminal write is deferred (cursor moves would
   disrupt the menu display). State is still updated so dirty flush picks it up."
  ([right-text] (draw-status-bar! nil right-text))
  ([left-text right-text]
   (swap! !layout assoc :status-text right-text :status-left left-text)
   (when (fullscreen?)
     (with-frame
       (if (popover-active?)
         (mark-dirty!)
         (let [w (get-writer)
               {:keys [status-row cols]} @!layout]
           (when (and w status-row)
             (let [;; The row is ONE line and must stay one line. Clamping only
                   ;; the gap (which is all this did) leaves `left + right`
                   ;; free to exceed `cols`, and the overflow wraps — onto the
                   ;; row below on a normal line, but this IS the bottom row, so
                   ;; the tail lands back on top of the row's own start. That is
                   ;; the "$0.0000agent [claude-code/opus] idle" garble you get
                   ;; by narrowing the terminal.
                   ;;
                   ;; Budget: a leading space, at least one column of gap, and
                   ;; `status-right-pad` free at the right edge.
                   avail          (max 0 (- cols 2 status-right-pad))
                   ;; The right side wins the space. It is the live state
                   ;; (running/idle, calls, tokens, cost) and it is what the
                   ;; user is watching change; the left is identity, which does
                   ;; not change and is also on the tab strip above.
                   right          (fmt/truncate-to-width (or right-text "") avail)
                   right-vis-len  (fmt/display-width right)
                   left           (fmt/truncate-to-width (or left-text "")
                                                         (- avail right-vis-len))
                   left-vis-len   (fmt/display-width left)
                   ;; Gap between left text and right text
                   gap            (max 1 (- cols left-vis-len right-vis-len
                                            status-right-pad 1))]
               ;; No cursor restore here — the frame epilogue parks the cursor
               ;; at the input line for every frame, so a body that ends on the
               ;; status row is fine.
               (raw-write! w (str (ansi/cursor-to status-row 1)
                                  ansi/erase-line
                                  " " left
                                  (apply str (repeat gap " "))
                                  right))))))))))

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
     (with-frame
       (if (popover-active?)
         (mark-dirty!)
         (let [w (get-writer)
               {:keys [tab-row]} @!layout]
           (when (and w tab-row)
             (raw-write! w (str (ansi/cursor-to tab-row 1)
                                ansi/erase-line
                                (or text ""))))))))))

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
  "Erase + write prompt at input-row, position cursor after prompt text.

   Every caller paints a PROMPT ONLY — the idle prompt at the loop top and the
   answer-mode refresh when a feedback question opens/closes — i.e. this is the
   call that BLANKS the input line. So it clears `!last-input` too: without
   that, the record still held the line the user had just submitted, and the
   next resize repainted it straight back into the (actually empty) input box."
  [prompt]
  (set-last-input! "" 0)
  (when (fullscreen?)
    (with-frame
      (let [w (get-writer)
            {:keys [input-row]} @!layout]
        (when (and w input-row)
          (raw-write! w (str (ansi/cursor-to input-row 1)
                             ansi/erase-line
                             prompt)))))))

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
    (with-frame
      (when-not (popover-active?)
        (let [w (get-writer)
              {:keys [input-row input-cursor-row input-cursor-col]} @!layout]
          (when (and w input-row)
            ;; The park is the frame epilogue's job; this body only has to be
            ;; non-empty so the frame actually flushes one.
            (raw-write! w (ansi/cursor-to (or input-cursor-row input-row)
                                          (or input-cursor-col 3)))))))))

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
    (with-frame
      (if (popover-active?)
        (mark-dirty!)
        (let [w (get-writer)
              {:keys [scroll-bottom viewport-offset collapse-highlight cols]} @!layout
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
              ;; Last line of defence against a row wider than the terminal.
              ;; `reflow-scrollback!` normally guarantees this can't happen, but
              ;; rows it could not re-render (a `(constantly …)` entry recovered
              ;; after drift) keep their old width. Autowrap is on — nothing
              ;; here touches DEC mode 7 — so an overlong row wraps onto the row
              ;; below, which the next iteration's `erase-line` then wipes; on
              ;; the bottom region row it scrolls the region instead, shifting
              ;; every absolutely-addressed row underneath the layout's model of
              ;; where they are. Clipping is a visible loss; both of those are
              ;; silent corruption.
              clamp (fn [^String line] (fmt/truncate-to-width line cols))
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
                      ;; Decorate AFTER clamping, never before: the clamp is a
                      ;; width-aware truncate, and a span marker inserted first
                      ;; can have its closing `underline-off` cut away — leaving
                      ;; the underline on for every row after it.
                      (.append sb ^String (decorate
                                           (clamp (if (= sb-idx highlight-idx)
                                                    (highlight-line line)
                                                    line))))))))
              (raw-write! w (.toString sb)))))))))

(defn row->scrollback-idx
  "Which `!scrollback` index is painted on 1-based terminal `row`?
   nil for a row outside the scroll region, or one in the blank padding above
   bottom-anchored content.

   This is `render-viewport!` read backwards, and it lives directly beneath it
   deliberately — the two must agree cell for cell. Content is bottom-anchored,
   so the mapping depends on how much scrollback exists AND where the viewport
   is; a click resolved against stale arithmetic lands on a different line than
   the one under the pointer, which presents as 'the click did nothing' rather
   than as a bug. `resize_reflow_test` pins them together."
  [row]
  (when (fullscreen?)
    (let [{:keys [scroll-bottom viewport-offset]} @!layout
          scroll-bottom (long (or scroll-bottom 0))
          row           (long row)]
      (when (and (>= row 1) (<= row scroll-bottom))
        (let [total         (count @!scrollback)
              end           (max 0 (- total (long (or viewport-offset 0))))
              start         (max 0 (- end scroll-bottom))
              visible-count (max 0 (- end start))
              blank-rows    (max 0 (- scroll-bottom visible-count))
              r             (dec row)]
          (when (>= r blank-rows)
            (let [idx (+ start (- r blank-rows))]
              ;; Guard the tail the same way render-viewport!'s `get` does:
              ;; rows past the last visible line paint nothing.
              (when (< idx end) idx))))))))

(def ^:private unwrap-cols
  "Width used to re-render an entry when recovering a target the visible wrap
   split across rows. Far past anything this TUI emits, so nothing wraps."
  100000)

(defn unwrapped-entry-text
  "The scrollback entry owning `idx`, re-rendered at a width where nothing
   wraps and joined into one string. nil when that is not possible.

   This is the reflow machinery used backwards. `!scrollback-src` entries
   already know how to render themselves at ANY width — that is what makes a
   resize re-wrap correctly — so asking one for its 100000-column form recovers
   the logical text behind rows the terminal had to break. `links/recover-target`
   then finds the whole URL or path there.

   LIVE BLOCKS ARE EXCLUDED, deliberately. Their renderers take width from
   `!layout`'s `:cols` rather than the argument (see the reflow notes in
   CLAUDE.md), so a wide render would return the same wrapped rows while
   inviting a re-entrant render of a block a ticker owns. Ordinary output —
   answers, tool results, anything with a real `:render` — is the part this
   works for, and the part where long URLs and paths actually appear.

   Rows recovered as `(constantly …)` re-render to themselves, so they yield
   the wrapped text and the caller simply finds nothing better. Degrading, not
   failing, is the same contract the rest of the reflow layer keeps."
  [idx]
  (when (and idx (nat-int? idx))
    (let [entries (ensure-src!)
          idx     (long idx)]
      (loop [i 0, acc 0]
        (when (< i (count entries))
          (let [e (nth entries i)
                n (long (:n e))]
            (if (< idx (+ acc n))
              (when (nil? (:block-id e))
                (try
                  (str/join "\n" (rows-of ((:render e) unwrap-cols)))
                  (catch Throwable _ nil)))
              (recur (inc i) (+ acc n)))))))))

(defn scroll-page-up!
  "Scroll viewport up by one page. Clamps to max offset."
  []
  (when (fullscreen?)
    (with-frame
      (let [{:keys [scroll-bottom]} @!layout
            total (count @!scrollback)
            max-offset (max 0 (- total scroll-bottom))]
        (swap! !layout update :viewport-offset
               (fn [off] (min max-offset (+ off scroll-bottom))))
        (render-viewport!)
        (draw-separator!)))))

(defn scroll-page-down!
  "Scroll viewport down by one page. Clamps to 0 (live)."
  []
  (when (fullscreen?)
    (with-frame
      (let [{:keys [scroll-bottom]} @!layout]
        (swap! !layout update :viewport-offset
               (fn [off] (max 0 (- off scroll-bottom))))
        (render-viewport!)
        (draw-separator!)))))

(defn scroll-lines-up!
  "Scroll viewport up by n lines (default 3). Clamps to max offset."
  ([] (scroll-lines-up! 3))
  ([n]
   (when (fullscreen?)
     (with-frame
       (let [{:keys [scroll-bottom]} @!layout
             total (count @!scrollback)
             max-offset (max 0 (- total scroll-bottom))]
         (swap! !layout update :viewport-offset
                (fn [off] (min max-offset (+ off n))))
         (render-viewport!)
         (draw-separator!))))))

(defn scroll-lines-down!
  "Scroll viewport down by n lines (default 3). Clamps to 0 (live)."
  ([] (scroll-lines-down! 3))
  ([n]
   (when (fullscreen?)
     (with-frame
       (swap! !layout update :viewport-offset
              (fn [off] (max 0 (- off n))))
       (render-viewport!)
       (draw-separator!)))))

(defn scroll-to-bottom!
  "Reset viewport to live output (offset 0). No-op if already at bottom.

   Framed like its four siblings: unframed, the viewport repaint and the
   separator were two flushes, so returning to live presented in two steps and
   toggled the cursor twice."
  []
  (when (and (fullscreen?) (pos? (:viewport-offset @!layout)))
    (with-frame
      (swap! !layout assoc :viewport-offset 0)
      (render-viewport!)
      (draw-separator!))))

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
   [block-start, block-start+line-count).

   THE hot path: every live-block tick lands here — the 150ms think ticker, the
   1s ACP/task/iteration/subagent tickers, and every streamed chunk — because
   `update-live-block!` only falls back to a full `render-viewport!` when the
   block's line COUNT changes. It must therefore be framed; unframed it was ~18
   naked presentations per second during a turn.

   When a popover is active, the terminal write is deferred (dirty flag set)."
  [block-start line-count]
  (when (fullscreen?)
    (with-frame
      (if (popover-active?)
        (mark-dirty!)
        (let [w (get-writer)
              {:keys [scroll-bottom viewport-offset cols]} @!layout
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
                  ;; Same clamp AND the same decoration as `render-viewport!`.
                  ;; All three paint paths must agree: a row decorated on one
                  ;; and not another changes appearance the moment something
                  ;; repaints it, which reads as a rendering glitch.
                  (.append sb ^String (decorate
                                       (fmt/truncate-to-width (get lines idx "") cols)))))
              ;; No cursor restore — the frame epilogue parks it at the input line.
              (raw-write! w (.toString sb)))))))))

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

   Selectively re-renders affected viewport rows.

   `opts` may carry `:render` — a `(fn [cols] -> string | [rows])` re-rendering
   the block at an arbitrary width, which makes it reflow on resize. Unlike
   `:sticky-bottom?` it IS honoured on update, because a block's content
   changes on every tick and the renderer has to describe the current content."
  ([block-id new-lines] (update-live-block! block-id new-lines nil))
  ([block-id new-lines opts]
   (let [sticky? (boolean (:sticky-bottom? opts))
         render  (or (:render opts) (constantly new-lines))]
     (with-frame
       (if-let [existing (get @!live-blocks block-id)]
         ;; Existing block: splice in-place (preserve original sticky flag)
         (let [{:keys [start-idx line-count sticky-bottom?]} existing
               old-count line-count
               new-count (count new-lines)
               ;; Re-point the entry BEFORE the rows move. `ensure-src!` inside
               ;; validates against the current rows, so it has to run while
               ;; they still match — afterwards the row count has changed and it
               ;; would rebuild, discarding this block's renderer.
               _ (src-update-block! block-id new-count render (boolean sticky-bottom?))
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
           ;; Entry first, for the same reason as the update path above.
           (src-insert! insert-at
                        {:render   render
                         :n        new-count
                         :block-id block-id
                         :sticky?  sticky?})
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
  (locking layout-lock
    ;; Detach the entry before the block goes, so `ensure-src!` still sees a
    ;; consistent pair. The renderer stays attached to the now-ordinary rows,
    ;; so a frozen block keeps reflowing on resize.
    (src-freeze-block! block-id)
    (swap! !live-blocks dissoc block-id)))

(defn dispose-live-block!
  "Remove a live block AND its lines from scrollback. Adjusts start-idx of any
   live blocks that came after it. Re-renders viewport so the gap is closed.
   When a popover is active, the terminal write is deferred (dirty flag set)."
  [block-id]
  (locking layout-lock
    (when-let [{:keys [start-idx line-count]} (get @!live-blocks block-id)]
      ;; Entry first, while rows and blocks still agree (see `update-live-block!`).
      (src-drop-block! block-id)
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
        (with-frame
          (render-viewport!)
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
  ;; One frame for the whole rebuild: scroll region, scrollback, sticky areas
  ;; and every piece of chrome. Separately these were seven presentations of a
  ;; half-drawn screen.
  (with-frame
    (let [w (get-writer)]
      (when w
        (raw-write! w (str (ansi/set-scroll-region 1 scroll-bottom)))))
    (render-viewport!)
    (draw-agent-activity-area!)
    (draw-task-activity-area!)
    (draw-separator!)
    (draw-bottom-separator!)
    (draw-tab-strip!)
    (let [{:keys [status-text status-left]} @!layout]
      (when (seq status-text)
        (draw-status-bar! status-left status-text)))))

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
   itself. Callers should NOT hold layout-lock (callees acquire it).

   While a hold is in effect (`hold-menu-height!`) the request is clamped UP to
   the held height: nothing may shrink the reservation until the hold is
   released, because every such shrink/grow moves the whole viewport."
  [menu-h]
  (when (and (fullscreen?)
             (not= (or (:menu-height @!layout) 0)
                   (max (long menu-h) (long (or (:menu-height-hold @!layout) 0)))))
    (let [was-popover? (popover-active?)]
      ;; Temporarily disable the popover gate so repaint-after-resize! actually
      ;; paints. This is safe because the resize is the popover's own layout
      ;; update — not a background writer that would conflict with the menu.
      (when was-popover? (set-popover-active! false))
      (try
        (let [{:keys [task-activity-height agent-activity-height menu-height-hold]} @!layout
              scroll-bottom (recalc-layout-rows! task-activity-height
                                                 agent-activity-height
                                                 (max (long menu-h)
                                                      (long (or menu-height-hold 0))))]
          (repaint-after-resize! scroll-bottom))
        (finally
          (when was-popover? (set-popover-active! true)))))))

;; ----------------------------------------------------------------------------
;; Menu reservation hold — why the popover keeps its rows after it hides
;;
;; The reservation IS the viewport geometry: `recalc-layout-rows!` takes
;; `menu-h` straight out of `scroll-bottom`, and `render-viewport!` bottom-
;; anchors into whatever is left. So every show/hide of the menu moves the whole
;; screen by ~30% of its height — up when the menu opens, back down when it
;; closes.
;;
;; That is fine for a gesture the user made (typing `/`), and unbearable for one
;; they did not: while typing a sentence containing an `@`-token, the match set
;; crosses zero and back on ordinary keystrokes, and each crossing was a
;; full-height flip of the text they were reading.
;;
;; So the reservation is scoped to the INPUT LINE, not to the menu: the first
;; menu of a line pins it, and it is given back when the line ends (Enter) or
;; when the user dismisses the menu outright (Esc). In between the menu may come
;; and go as often as the filter says — it paints into rows it already owns, and
;; hiding it blanks those rows rather than reclaiming them.
;; ----------------------------------------------------------------------------

(defn menu-height-held?
  "True while the popover reservation is pinned for the current input line."
  []
  (pos? (long (or (:menu-height-hold @!layout) 0))))

(defn hold-menu-height!
  "Reserve menu-h rows AND pin the reservation until `release-menu-height!`.
   Called by the popover on every draw, so a resize between two menus re-pins at
   the new height."
  [menu-h]
  (when (fullscreen?)
    (swap! !layout assoc :menu-height-hold (max 0 (long menu-h)))
    (set-menu-height! menu-h)))

(defn release-menu-height!
  "Drop the hold and give the rows back. Safe to call when nothing is held or
   the menu is already hidden — `set-menu-height!` no-ops when the height is
   already 0, so this is the single call that ends a reservation regardless of
   whether the menu happens to be on screen."
  []
  (when (fullscreen?)
    (swap! !layout assoc :menu-height-hold 0)
    (set-menu-height! 0)))

(defn hide-menu-rows!
  "Blank the held rows WITHOUT giving them back, and flush whatever the popover
   gate deferred while the menu was up.

   This is the hidden-but-held state: geometry is unchanged (so nothing moves),
   the menu's own rows are erased, and the scroll region / sticky areas / chrome
   repaint exactly as they would on a real dismiss. Caller should have cleared
   the popover gate first, or the repaint defers itself right back."
  []
  (when (fullscreen?)
    (let [{:keys [rows menu-height scroll-bottom]} @!layout
          menu-h (long (or menu-height 0))]
      (when (pos? menu-h)
        (with-frame
          (let [w (get-writer)]
            (when w
              (let [sb (StringBuilder.)
                    ;; Same span the menu paints into: it sits directly above
                    ;; separator2 (row rows-2), so its top is rows-menu-h-2.
                    menu-top (- (long rows) menu-h 2)]
                (dotimes [i menu-h]
                  (.append sb (ansi/cursor-to (+ menu-top i) 1))
                  (.append sb ^String ansi/erase-line))
                (raw-write! w (.toString sb)))))
          (repaint-after-resize! scroll-bottom))))))

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
              (with-frame
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
              (with-frame
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
  "Redraw separators + status bar + sticky areas as ONE frame.
   Fixes corruption from Enter-induced scrolling.

   Was four separate flushes carrying three separate show-cursors, so a single
   Enter presented the chrome in four steps with the cursor re-appearing in
   between. The cursor is not restored here at all any more — the frame epilogue
   parks it at the input line, which is the only place it may end up."
  []
  (when (fullscreen?)
    (with-frame
      (draw-separator!)
      (draw-agent-activity-area!)
      (draw-task-activity-area!)
      (draw-bottom-separator!)
      (draw-tab-strip!)
      (let [{:keys [status-text status-left]} @!layout]
        (when (seq status-text)
          (draw-status-bar! status-left status-text))))))

(defn- viewport-anchor
  "What the viewport is currently looking at, as `[entry-idx row-within-entry]`,
   or nil when it is pinned to the live tail.

   `viewport-offset` counts ROWS back from the tail, so a reflow that changes
   row counts invalidates it — the same number now lands somewhere else in the
   text. The entry a row belongs to survives a reflow (re-rendering maps
   entries 1:1, changing only their heights), so it is what the position has to
   be expressed in to be restorable.

   Offset 0 deliberately anchors to NOTHING. At the tail the user is following
   live output, and holding their top row fixed while content re-wraps below
   would walk them off the bottom — the one thing a resize must never do.

   Read this BEFORE the resize writes the new `:scroll-bottom`: which row is on
   top is a function of the height the viewport has right now."
  [entries]
  (let [{:keys [viewport-offset scroll-bottom]} @!layout]
    (when (and scroll-bottom (pos? (long (or viewport-offset 0))))
      (let [total (count @!scrollback)
            top   (max 0 (- total (long viewport-offset) (long scroll-bottom)))]
        (loop [i 0, acc 0]
          (cond
            (>= i (count entries)) nil
            (< top (+ acc (long (:n (nth entries i))))) [i (- top acc)]
            :else (recur (inc i) (+ acc (long (:n (nth entries i)))))))))))

(defn- restore-viewport-anchor!
  "Put `anchor` back on the top row of the viewport, against the post-reflow
   rows and the post-resize height. A nil anchor means the live tail.

   The row within the entry is clamped: an entry that re-wrapped from four rows
   to two has no row 3 any more, and the nearest surviving row is the honest
   answer. The resulting offset is clamped to the scrollable range, which is
   what handles a grow — fewer rows can mean there is no longer anything to
   scroll back through."
  [entries anchor]
  (let [scroll-bottom (long (or (:scroll-bottom @!layout) 0))
        total   (count @!scrollback)
        max-off (max 0 (- total scroll-bottom))
        offset  (if (and anchor (seq entries))
                  (let [[i k] anchor
                        i      (min (long i) (dec (count entries)))
                        before (reduce + 0 (map :n (take i entries)))
                        n-i    (long (:n (nth entries i)))
                        top    (+ before (min (long k) (max 0 (dec n-i))))]
                    (- total top scroll-bottom))
                  0)]
    (swap! !layout assoc :viewport-offset (max 0 (min max-off offset)))))

(defn- reflow-scrollback!
  "Re-render every scrollback entry at `cols` and rebuild the rows and the
   live-block index from the result. `anchor` is a `viewport-anchor` read
   before the geometry changed; the scroll position is restored onto it.

   This is what makes a resize non-destructive. Without it the stored rows keep
   the width they were formatted at, and `render-viewport!` replays them into a
   terminal that is no longer that wide.

   All-or-nothing: if any renderer throws, nothing changes. A half-reflowed
   scrollback would have rows at two different widths and block indices
   pointing at neither.

   `:cols` in `!layout` is set to `cols` first, and that is part of the
   contract: the live-block renderers in `session` are pure functions of
   (state, width) that read the width from `!layout` rather than taking it as
   an argument, so a `:render` for a block can ignore its parameter and still
   be correct. Both routes see the same number by construction."
  [cols anchor]
  (swap! !layout assoc :cols cols)
  (let [entries (ensure-src!)]
    (when-let [rendered (try
                          (mapv (fn [e]
                                  (let [rows (rows-of ((:render e) cols))]
                                    (assoc e :rows rows :n (count rows))))
                                entries)
                          (catch Throwable _ nil))]
      (reset! !scrollback (into [] (mapcat :rows) rendered))
      (reset! !scrollback-src (mapv #(dissoc % :rows) rendered))
      ;; Block positions are row offsets, and re-wrapping moved every row after
      ;; the first entry whose height changed. Recompute them from the order.
      (reset! !live-blocks
              (:blocks (reduce (fn [{:keys [off blocks]} e]
                                 {:off    (+ off (long (:n e)))
                                  :blocks (if-let [id (:block-id e)]
                                            (assoc blocks id
                                                   {:start-idx      off
                                                    :line-count     (:n e)
                                                    :sticky-bottom? (boolean (:sticky? e))})
                                            blocks)})
                               {:off 0 :blocks {}}
                               rendered)))
      ;; `viewport-offset` counts rows from the tail, and the tail just moved.
      ;; Put the reader back on the text they were reading rather than on the
      ;; row number they happened to be at.
      (restore-viewport-anchor! (mapv #(dissoc % :rows) rendered) anchor)
      true)))

(defn reflow-to-current-width!
  "Re-wrap whatever is in `!scrollback` to the width the terminal has NOW.

   `handle-resize!` only ever reaches the ACTIVE tab: a background session's
   rows live in its own session map, untouched by the resize that happened
   while the user was looking at another tab. Switching to it therefore
   installs rows formatted for a width that is no longer true — which for an
   output-only tab means the rows it collected in the background arrive too
   wide and are clipped at paint time.

   So a tab switch reflows what it loads, for exactly the reason a resize does.
   Entries with no renderer re-render to themselves, so this is cheap for rows
   that could not reflow anyway, and a no-op in the common case where nothing
   resized. Caller should have restored `:viewport-offset` first — the anchor
   is read from it."
  []
  (when (fullscreen?)
    (let [cols (or (:cols @!layout) 80)]
      (when-not (reflow-scrollback! cols (viewport-anchor (ensure-src!)))
        ;; A renderer threw, or the source had drifted: rows are untouched, but
        ;; the offset may now point past the end. Re-seat it.
        (restore-viewport-anchor! (ensure-src!) nil)))))

(defn handle-resize!
  "Handle terminal resize: refresh dimensions, recalculate row layout,
   re-wrap the scrollback to the new width, and redraw everything.
   No-op in inline mode."
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
              sl             (:status-left @!layout)
              ;; Read the scroll position while the OLD geometry is still in
              ;; effect — the top visible row depends on the height the
              ;; viewport has now, not the one it is about to have.
              anchor         (viewport-anchor (ensure-src!))]
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
                 :input-height-max input-h-max
                 ;; The tracked input cursor is an ABSOLUTE screen position,
                 ;; computed by `redraw-input-line!` against the geometry that
                 ;; just stopped being true — and both terms are stale: the row
                 ;; moved with the chrome, and a width change re-wraps the
                 ;; buffer underneath it. Dropping it makes the frame epilogue
                 ;; fall back to `(input-row, 3)`, which is correct for the new
                 ;; geometry; the input repaint that follows this resize
                 ;; restamps the exact position. Keeping it parked the cursor
                 ;; wherever the OLD input line used to be — mid-content when
                 ;; the terminal grew, on the chrome when it shrank.
                 :input-cursor-row nil
                 :input-cursor-col nil)
          ;; Re-wrap what is already on screen to the new width. Must happen
          ;; before the repaint below, which replays whatever rows it finds.
          ;; When the reflow bails (a renderer threw, or the source had drifted)
          ;; the rows are untouched — but `scroll-bottom` still changed, so the
          ;; old row-counted offset can now point past the end. Re-seat it.
          (when-not (reflow-scrollback! cols anchor)
            (restore-viewport-anchor! (ensure-src!) anchor))
          ;; One frame: clear, region, scrollback replay, sticky areas, chrome.
          ;; A resize that presents in seven steps is a visibly rebuilding screen.
          (with-frame
            (let [w (get-writer)]
              (when w
                (raw-write! w (str ansi/clear-screen
                                   (ansi/set-scroll-region 1 scroll-bottom)
                                   ansi/enable-alt-scroll
                                   (mouse-seq)
                                   (ansi/cursor-to 1 1)))))
            (render-viewport!)
            (draw-agent-activity-area!)
            (draw-task-activity-area!)
            (draw-separator!)
            (draw-bottom-separator!)
            (draw-tab-strip!)
            (when (seq st)
              (draw-status-bar! sl st))))))))

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
        (reset! !scrollback-src [])
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
               :menu-height-hold 0
               :input-height 1
               :input-height-max (max 3 (quot rows 3))
               ;; Same reason as in `handle-resize!`: entering fullscreen
               ;; establishes a NEW geometry, and a tracked cursor position
               ;; left over from a previous one (a prior fullscreen session, or
               ;; a re-entry after the external-editor handoff) is an absolute
               ;; row that means nothing here.
               :input-cursor-row nil
               :input-cursor-col nil
               :input-active false)
        (locking layout-lock
          (let [w (get-writer)]
            (when w
              (raw-write! w (str ansi/enter-alt-screen
                                 ansi/clear-screen
                                 ansi/hide-cursor
                                 (ansi/set-scroll-region 1 scroll-bottom)
                                 ansi/enable-alt-scroll
                                 (mouse-seq)
                                 ;; Start at bottom of scroll region so content
                                 ;; anchors near the input area, not at row 1
                                 (ansi/cursor-to scroll-bottom 1))))))
        ;; We just hid the cursor; keep the transition tracking in step so the
        ;; first frame does not emit a redundant hide.
        (note-cursor-hidden!)
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
            ;; Disable mouse reporting UNCONDITIONALLY, not via `mouse-seq`:
            ;; the flag may have been flipped off mid-session, and leaving a
            ;; terminal in `?1000h` after exit makes every click in the user's
            ;; shell emit escape garbage. Resetting a mode that was never set
            ;; is a no-op.
            (raw-write! w (str ansi/disable-mouse
                               ansi/disable-alt-scroll
                               ansi/reset-scroll-region
                               ansi/show-cursor
                               ansi/leave-alt-screen))
            (note-cursor-shown!)
            ;; Replay buffered output so it's in terminal scrollback
            (when (seq final-lines)
              (raw-write! w (str (str/join "\n" final-lines) "\n"))))))
      (reset! !scrollback [])
      (reset! !live-blocks {})
      (reset! !scrollback-src []))
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
           :menu-height 0
           :menu-height-hold 0
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
