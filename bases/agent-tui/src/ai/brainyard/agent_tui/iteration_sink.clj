;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent-tui.iteration-sink
  "Sink protocol bridging iteration live-block updates to a rendering surface.

   The in-process TUI (`agent-tui.session`) wraps `agent-tui.layout`'s
   live-block primitives — splice into the in-process scrollback vector and
   redraw the viewport. The renderer fns in `session.clj`
   (`render-iteration-block-lines`, `render-iter-tool-line`, formatters) are
   pure; only the side-effecting `update-live-block!` /
   `freeze-live-block!` / `dispose-live-block!` calls go through this
   protocol so the same handler code can run against future surfaces too.

   Set the active sink at startup via `set-iteration-sink!`. The default
   sink is a no-op so requiring this ns from a context that doesn't yet
   have a TUI doesn't crash."
  (:require [ai.brainyard.mulog.interface :as mulog]))

(defprotocol IterationSink
  (-write-widget! [this widget-id lines]
    "Update the widget's rendered lines (or create the widget if absent).
     `widget-id` is a stable keyword unique to one live block.
     `lines` is a vector of ANSI-styled strings, one per row.")
  (-freeze-widget! [this widget-id]
    "Mark the widget as frozen — no further updates will be accepted.
     The lines stay in pane history (or scrollback) as a static record.")
  (-clear-widget! [this widget-id]
    "Remove the widget AND its lines from the surface."))

(def noop-sink
  "Default sink that swallows all calls. Active when no TUI is wired
   (REPL, tests that don't exercise rendering)."
  (reify IterationSink
    (-write-widget!  [_ _ _] nil)
    (-freeze-widget! [_ _]   nil)
    (-clear-widget!  [_ _]   nil)))

(defonce ^:private !current-sink (atom noop-sink))

(defn current-sink
  "Return the currently installed sink."
  []
  @!current-sink)

(defn set-iteration-sink!
  "Install `sink` as the iteration sink. Called once at TUI startup
   (in-process or tmux). Subsequent calls replace the prior sink."
  [sink]
  (when-not (satisfies? IterationSink sink)
    (throw (ex-info "set-iteration-sink!: argument does not satisfy IterationSink"
                    {:type (type sink)})))
  (mulog/log ::sink-installed :type (str (type sink)))
  (reset! !current-sink sink))

(defn write-widget!
  "Convenience: dispatch through the current sink."
  [widget-id lines]
  (-write-widget! @!current-sink widget-id lines))

(defn freeze-widget!
  [widget-id]
  (-freeze-widget! @!current-sink widget-id))

(defn clear-widget!
  [widget-id]
  (-clear-widget! @!current-sink widget-id))
