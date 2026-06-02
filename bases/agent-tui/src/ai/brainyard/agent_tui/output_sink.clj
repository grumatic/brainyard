;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent-tui.output-sink
  "Tiny indirection layer between `session.clj`'s `emit!` and its terminal
   writer, so daemon mode can fan output into per-pane FIFOs.

   See docs/tmux-based-agent-tui.md §12 — sinks own where ANSI bytes go.

   Contract:

   - `install!` registers a fn `(fn [^String s] ...)` that becomes the
     write target for `emit!`.  Pass nil to revert.
   - `installed?` returns true when a sink is currently installed.
   - `route!` invokes the installed sink with `s`, returning true on success
     and false when no sink is installed (so callers can fall back).

   No external deps; safe to require from anywhere in the base.")

(defonce !sink (atom nil))

(defn install!
  "Replace the active sink fn.  Returns the previous sink."
  [sink-fn]
  (let [prev @!sink]
    (reset! !sink sink-fn)
    prev))

(defn installed? [] (some? @!sink))

(defn route!
  "Invoke the installed sink with `s`.  Returns true if a sink was installed
   and the write was attempted, false otherwise."
  [^String s]
  (if-let [f @!sink]
    (do (try (f s) (catch Throwable _)) true)
    false))

(defn revert! [] (reset! !sink nil))

(defn sink-writer
  "Build a `java.io.Writer` whose `write()` methods call `f` with the resulting
   string fragment.  Used by daemon mode to install a Writer in
   `agent-tui.layout/!layout :writer` that routes every legacy-renderer write
   (status bar, live blocks, cursor positioning) into the multi-sink."
  ^java.io.Writer [f]
  (proxy [java.io.Writer] []
    (write
      ([cbuf-or-int]
       (cond
         (integer? cbuf-or-int)
         (f (str (char (int cbuf-or-int))))

         (string? cbuf-or-int)
         (f cbuf-or-int)

         (instance? (Class/forName "[C") cbuf-or-int)
         (f (String. ^chars cbuf-or-int))

         :else
         (f (str cbuf-or-int))))
      ([cbuf off len]
       (cond
         (string? cbuf) (f (.substring ^String cbuf (int off) (int (+ off len))))
         :else          (f (String. ^chars cbuf (int off) (int len))))))
    (flush [])
    (close [])))
