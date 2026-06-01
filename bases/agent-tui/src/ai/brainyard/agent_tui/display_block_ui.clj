;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: Apache-2.0
;; Licensed under the Apache License, Version 2.0 (the "License"); you may
;; not use this file except in compliance with the License. See LICENSE at
;; the repository root and CONTRIBUTING.md for contribution terms.

(ns ai.brainyard.agent-tui.display-block-ui
  "TUI-side glue for `display-block` markers in scrollback.

   Producers register a `display-block` (e.g. via `display-block/text-block`)
   and embed a single MARKER LINE into scrollback. This namespace handles:
     - scanning markers visible in the viewport
     - splicing expand / collapse over the marker line
     - launching $EDITOR for blocks that expose a resource path

   All block-shape concerns (provider protocols, marker format, file
   storage) live in the `display-block` component. This namespace owns
   only the dependency on `agent-tui.layout` (scrollback / live-blocks)
   and the editor-suspend dance."
  (:require [ai.brainyard.agent-tui.layout :as layout]
            [ai.brainyard.display-block.interface :as block]
            [clojure.java.io :as io])
  (:import [java.io File]))

;; ============================================================================
;; Scrollback scanning
;; ============================================================================

(def marker-re
  "Backwards-compatible re-export — prefer `display-block/marker-re`."
  block/marker-re)

(defn find-markers-in-range
  "Scan scrollback indices [start, end) for any block markers (collapsed
   or expanded). Returns a vector of {:line-idx :id :state :summary :hint}
   in scrollback order. :state is :collapsed or :expanded.

   Note: the legacy keyword :kind (`:collapsed` / `:expanded`) is also
   surfaced as an alias of :state so callers that haven't been migrated
   yet keep working."
  [start end]
  (let [sb @layout/!scrollback
        hits (block/scan-lines sb start end)]
    (mapv (fn [m] (assoc m :kind (:state m))) hits)))

(defn find-markers-in-viewport
  "Markers visible in the current viewport (bottom-anchored)."
  []
  (let [sb @layout/!scrollback
        total (count sb)
        {:keys [scroll-bottom viewport-offset]} @layout/!layout
        end (- total (or viewport-offset 0))
        start (max 0 (- end (or scroll-bottom 24)))]
    (find-markers-in-range start end)))

;; ============================================================================
;; Splice + toggle
;; ============================================================================

(defn- splice-scrollback!
  "Replace [start, start+delete-count) lines in scrollback with new-lines.
   Shifts live-block start-idx by the delta for any block after `start`.
   Returns delta = (count new-lines - delete-count)."
  [start delete-count new-lines]
  (let [new-count (count new-lines)
        delta (- new-count delete-count)]
    (swap! layout/!scrollback
           (fn [sb]
             (into (into (subvec sb 0 start) new-lines)
                   (subvec sb (+ start delete-count)))))
    (when (not= delta 0)
      (swap! layout/!live-blocks
             (fn [blocks]
               (reduce-kv
                (fn [m id b]
                  (assoc m id (if (> (:start-idx b) start)
                                (update b :start-idx + delta)
                                b)))
                {} blocks))))
    delta))

;; Per-id memo of expand state so collapse can restore the pre-expand
;; visual. Stores:
;;   :original-line — verbatim collapsed-marker line (preserves ANSI /
;;                    box-drawing prefix that wrapped it at emit time)
;;   :expanded-len  — number of scrollback lines occupied by the
;;                    expanded form (= tail lines + optional trailer +
;;                    expanded marker; replaces the original 1 line).
;; Cleared when a block is disposed.
(defonce ^:private !collapsed-line-memo (atom {}))

(defn- fallback-expanded-len
  "When the memo is empty (e.g. process restart) compute how many lines
   `-expanded-lines` would currently produce, so collapse still snaps the
   right span. Returns 1 (just the marker) when no provider is registered."
  [id]
  (or (some-> (block/expand-lines id) count) 1))

(defn expand!
  "Replace the single collapsed marker at line-idx with the expanded form
   from the registered provider. Records the expanded length in the memo
   so a subsequent `collapse!` can snap the right span back to a single
   marker. Repaints viewport. Returns delta (lines added).

   If no provider is registered for `id` (e.g. process restarted, registry
   empty), surfaces a one-line notice below the marker and leaves it in
   place; returns 0."
  [id line-idx]
  (let [original-line (get @layout/!scrollback line-idx)
        new-lines (block/expand-lines id)]
    (if (seq new-lines)
      (let [delta (splice-scrollback! line-idx 1 new-lines)]
        (swap! !collapsed-line-memo assoc id
               {:original-line original-line
                :expanded-len  (count new-lines)})
        (try (layout/render-viewport!) (catch Exception _))
        (try (layout/draw-separator!) (catch Exception _))
        delta)
      (do
        (splice-scrollback! (inc line-idx) 0
                            [(str "    (no provider registered for block " id ")")])
        (try (layout/render-viewport!) (catch Exception _))
        (try (layout/draw-separator!) (catch Exception _))
        0))))

(defn collapse!
  "Replace the expanded form with the original single marker line. Uses
   the memo recorded by `expand!` to know how many lines to fold back;
   falls back to recomputing via the provider when the memo is empty
   (e.g. after a process restart). Repaints viewport. Returns delta."
  [id line-idx]
  (let [{:keys [original-line expanded-len]} (get @!collapsed-line-memo id)
        marker-line (or original-line (block/collapse-line id))
        len         (or expanded-len (fallback-expanded-len id))]
    (when marker-line
      (let [start (max 0 (- line-idx (dec len)))
            end   (inc line-idx)
            delta (splice-scrollback! start (- end start) [marker-line])]
        (swap! !collapsed-line-memo dissoc id)
        (try (layout/render-viewport!) (catch Exception _))
        (try (layout/draw-separator!) (catch Exception _))
        delta))))

(defn toggle!
  "Toggle collapse/expand for the marker at line-idx of the given :state
   (or legacy :kind)."
  [id line-idx kind-or-state]
  (case kind-or-state
    :collapsed (expand! id line-idx)
    :expanded  (collapse! id line-idx)
    nil))

;; ============================================================================
;; Editor invocation
;; ============================================================================

(defn view-in-editor!
  "Suspend the TUI (leave alt-screen, restore cooked tty mode), then exec
   $EDITOR (default `less`) on the block's `resource-path` via `sh -c`
   with stdin/stdout/stderr wired to /dev/tty. On exit, re-enter alt-screen,
   re-apply raw tty mode, and trigger a full redraw.

   Returns the path opened, or nil if the block has no resource path
   (e.g. in-memory provider) or the file is missing.

   Why the shell wrapper? When the JVM's own stdin is already in raw mode
   from stty, ProcessBuilder/inheritIO passes that same descriptor to the
   editor, and the editor can't correctly read keystrokes. Redirecting
   via /dev/tty gives the editor a clean controlling terminal."
  [id]
  (when-let [path (block/resource-path id)]
    (when (.exists (File. ^String path))
      (let [editor (or (System/getenv "EDITOR") "less")
            fs? (layout/fullscreen?)
            term-ns (requiring-resolve 'ai.brainyard.agent-tui.terminal/restore-cooked-mode!)
            set-raw (requiring-resolve 'ai.brainyard.agent-tui.terminal/set-raw-mode!)
            stop-input (requiring-resolve 'ai.brainyard.agent-tui.input/stop-input-reader!)
            start-input (requiring-resolve 'ai.brainyard.agent-tui.input/start-input-reader!)]
        (try
          (when stop-input (stop-input))
          (when fs?
            (layout/draw-overlay!
             (fn [w]
               (.write ^java.io.Writer w "[r")
               (.write ^java.io.Writer w "[?1049l")
               (.write ^java.io.Writer w "[?25h")
               (.write ^java.io.Writer w "[2J[H")
               (.flush ^java.io.Writer w))))
          (when term-ns (term-ns))
          (let [cmd-str (str editor " "
                             (pr-str path)
                             " < /dev/tty > /dev/tty 2> /dev/tty")
                pb (ProcessBuilder. ^java.util.List (vec ["/bin/sh" "-c" cmd-str]))]
            (.waitFor (.start pb)))
          (finally
            (when set-raw (set-raw))
            (when fs?
              (layout/draw-overlay!
               (fn [w]
                 (.write ^java.io.Writer w "[?1049h")
                 (.write ^java.io.Writer w "[?25l")
                 (.flush ^java.io.Writer w)))
              (try (layout/handle-resize!) (catch Exception _)))
            (when start-input (start-input System/in))))
        path))))

;; ============================================================================
;; Disposal
;; ============================================================================

(defn dispose!
  "Drop the block from the registry, free backing resources, clear the
   per-id memoised collapsed line. Idempotent."
  [id]
  (swap! !collapsed-line-memo dissoc id)
  (block/dispose! id))
