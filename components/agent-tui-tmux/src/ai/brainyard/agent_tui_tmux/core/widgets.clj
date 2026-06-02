;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent-tui-tmux.core.widgets
  "Widget abstraction over a tmux stream pane sink — the tmux analogue of
   `agent-tui.layout/update-live-block!` from the in-process TUI.

   In the in-process TUI we own the scrollback vector and can splice lines
   in place. Under tmux, scrollback is owned by the tmux pane: every byte
   we write is captured. So a 'widget' here means a region at the tail of
   the stream pane that updates by emitting ANSI cursor-up + erase-display
   followed by the new lines. The visible rendering is always current; the
   pane's scrollback retains the trail of historical updates (matching the
   pi-mono `setWidget` semantics under tmux).

   API mirrors `iter-sink/IterationSink`:

     (set-widget! ms id lines)        ; create or update
     (freeze-widget! ms id)            ; stop updating; lines stay
     (clear-widget! ms id)             ; ANSI-erase + dispose registry

   `:placement :tail-of-stream` is the only placement implemented in v1
   (the others — `:above-input`, `:below-input` from the design doc — only
   make sense once we wire pi-mono's separate input/footer panes; they
   raise `IllegalArgumentException` for now)."
  (:require [ai.brainyard.agent-tui-tmux.core.sink :as sink]
            [clojure.string :as str]))

;; ============================================================================
;; ANSI helpers
;; ============================================================================

(def ^:private ESC "\033")

(defn- cursor-up-and-home
  "ANSI: move cursor up `n` rows and to column 1 (CSI n F)."
  [n]
  (when (pos? n) (str ESC "[" n "F")))

(def ^:private erase-to-end-of-display
  "ANSI: erase from cursor to end of screen (CSI 0 J)."
  (str ESC "[0J"))

(defn- preamble-for-update
  "Bytes that move the cursor to the start of the previous render and clear
   from there to end of pane. Empty string if no previous render."
  [old-line-count]
  (if (pos? old-line-count)
    (str (cursor-up-and-home old-line-count)
         erase-to-end-of-display)
    ""))

(defn- bytes-for-lines
  "Each line gets a trailing newline so the cursor ends up on the row
   below the last content line — exactly where the next emit (or the
   widget's next update) starts."
  [lines]
  (if (seq lines)
    (str (str/join "\n" lines) "\n")
    ""))

;; ============================================================================
;; Per-multi-sink widget registry
;; ============================================================================

;; Registry: {multi-sink-record {widget-id {:line-count int :placement kw}}}
;; Multi-sink records have stable identity-based hash/equals, so they're
;; usable as map keys directly.
(defonce ^:private !registry (atom {}))

(defn- registry-of [ms]
  (get @!registry ms {}))

(defn- registered? [ms id]
  (some? (get (registry-of ms) id)))

(defn line-count
  "Inspect the current rendered line count for a widget. nil when the
   widget isn't tracked. Test/diagnostic helper."
  [ms id]
  (get-in @!registry [ms id :line-count]))

;; ============================================================================
;; Public API
;; ============================================================================

(def ^:private supported-placements
  #{:tail-of-stream})

(defn set-widget!
  "Create or update widget `id` in the stream channel of multi-sink `ms`
   with `lines`. Each call moves the cursor up and erases the previous
   render before writing, so the visible widget content is replaced
   in-place from the user's perspective.

   `opts` recognized keys:
     :placement — only :tail-of-stream in v1 (default)."
  ([ms id lines] (set-widget! ms id lines {}))
  ([ms id lines {:keys [placement] :or {placement :tail-of-stream}}]
   (when-not (contains? supported-placements placement)
     (throw (IllegalArgumentException.
             (str "Unsupported widget placement: " placement
                  " (only :tail-of-stream is implemented)"))))
   (let [prev (get-in @!registry [ms id])
         old-count (or (:line-count prev) 0)
         new-count (count lines)
         payload (str (preamble-for-update old-count)
                      (bytes-for-lines lines))]
     (when (seq payload)
       (sink/write-stream! ms payload))
     (swap! !registry assoc-in [ms id]
            {:line-count new-count :placement placement})
     ms)))

(defn freeze-widget!
  "Stop tracking widget `id`. Its current rendered lines remain in the
   pane (they become normal scrollback). Subsequent `set-widget!` calls
   for the same `id` will re-create from scratch."
  [ms id]
  (swap! !registry update ms dissoc id)
  ms)

(defn clear-widget!
  "Cursor-up + erase the widget's lines, then drop it from the registry.
   The pane's scrollback above the erased region is unaffected."
  [ms id]
  (when-let [{:keys [line-count]} (get-in @!registry [ms id])]
    (let [payload (preamble-for-update (or line-count 0))]
      (when (seq payload)
        (sink/write-stream! ms payload))))
  (swap! !registry update ms dissoc id)
  ms)

(defn forget-multi-sink!
  "Drop all widget tracking for `ms`. Called when a window's sinks are
   closed so the registry doesn't hold stale entries."
  [ms]
  (swap! !registry dissoc ms)
  ms)

(defn reset-registry!
  "Test helper — clear the registry."
  []
  (reset! !registry {}))
