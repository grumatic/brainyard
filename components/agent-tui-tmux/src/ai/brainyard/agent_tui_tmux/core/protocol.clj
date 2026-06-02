;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent-tui-tmux.core.protocol
  "Protocol abstraction over tmux(1).

   Per docs/tmux-based-agent-tui.md R-8 — `by-ui` is implemented against this
   protocol so it can be unit-tested with a `StubTmux` recorder.  Production
   uses `RealTmux` which shells out to the system tmux."
  (:refer-clojure :exclude [list]))

(defprotocol Tmux
  (version
    [this]
    "Return the running tmux version as a string (e.g. \"3.4\"), or nil if
     tmux is not available.")

  (probe-version
    [this]
    "Return a parsed major.minor pair like [3 4], or nil.  Used to gate the
     popup feature (3.2+).")

  (running?
    [this]
    "True iff a tmux server is reachable on this client's socket.")

  (new-session!
    [this opts]
    "Create a tmux session.  Opts:
       :name     — session name (required)
       :detached — when true, do not attach client (default true)
       :command  — command to run in initial pane (string or vector)
       :width / :height — initial geometry (optional)
     Returns the session name on success.")

  (kill-session!
    [this name]
    "Kill the named session.")

  (list-sessions
    [this]
    "Return a vector of session-name strings.")

  (new-window!
    [this opts]
    "Open a new window in `:target` (a session name).  Opts:
       :target  — session name (required)
       :name    — window name (optional)
       :command — command to run (string or vector)
     Returns the window target string `<session>:<window-name>`.")

  (kill-window!
    [this target]
    "Kill a window targeted as <session>:<window>.")

  (kill-pane!
    [this target]
    "Kill a single pane (tmux: kill-pane).  `target` is a pane id `%N`.")

  (rename-window!
    [this target new-name])

  (split-pane!
    [this opts]
    "Split a pane.  Opts:
       :target     — pane id (required)
       :orientation — :h | :v
       :percentage — 0..100 of the parent pane
       :size       — absolute size (rows for :v, cols for :h).  Wins over
                     :percentage when both are supplied.
       :command    — command (string or vector)
     Returns the new pane id.")

  (resize-pane!
    [this opts]
    "Resize a pane.  Opts: :target, :width, :height.")

  (select-pane!
    [this target])

  (select-window!
    [this target]
    "Make the named window the active window in its session.  `target` is a
     window id (`@N`) or `<session>:<window>` form.")

  (send-keys!
    [this opts]
    "Send keystrokes to a pane.  Opts:
       :target — pane id
       :keys   — vector of strings/symbols (\"Enter\", \"Escape\", \"C-c\", or arbitrary literal text)
       :literal? — if true, do not interpret as keynames")

  (pipe-pane!
    [this opts]
    "Start (or stop) a pipe of a pane's output.  Opts:
       :target  — pane id (required)
       :path    — destination FIFO/file (when starting); nil/missing stops the pipe
       :start?  — true to start, false to stop
       :open    — when true, opens output in append mode")

  (capture-pane
    [this opts]
    "Capture pane contents.  Opts:
       :target — pane id
       :start  — start line (negative for scrollback)
       :end    — end line
       :ansi?  — include ANSI escapes (default true)
     Returns the captured text as a string.")

  (display-popup!
    [this opts]
    "Show a popup overlay (tmux 3.2+).  Opts:
       :command   — command to run inside the popup (required, string)
       :width     — width in cells
       :height    — height in cells
       :title     — popup title (tmux 3.4+)
       :border    — :rounded | :double | :heavy | :simple | :none
       :env       — map of env vars to set
       :close-on-exit? — terminate when command exits (default true)
     Returns 0 on success or an exit code.")

  (set-option!
    [this opts]
    "tmux set-option: keys :name :value, optional :target, :scope :global/:server
     /:session/:window/:pane.")

  (display-message
    [this opts]
    "Run tmux display-message -p <fmt>.  Opts: :format (\"#{pane_top}\" etc)
     and optional :target.")

  (signal!
    [this name]
    "Send a tmux wait-for signal.  Used to release a process that is blocking
     inside `tmux wait-for <name>` — typically a popup whose Clojure-side
     state has been finalised and which now should close.")

  (run-shell
    [this opts]
    "Run a one-off tmux command.  Opts:
       :args — vector of args to pass to tmux (e.g. [\"display-popup\" \"-E\" \"...\"])
     Returns {:exit :stdout :stderr}."))

(defn supports-popup?
  "True when tmux is at least version 3.2 (when display-popup landed)."
  [t]
  (when-let [[major minor] (probe-version t)]
    (or (> major 3) (and (= major 3) (>= minor 2)))))
