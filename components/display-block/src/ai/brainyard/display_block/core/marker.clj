;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.display-block.core.marker
  "Marker line format for display-blocks.

   A marker is a single scrollback line that carries:
     - the block id (so the registry can resolve a provider)
     - the state (`collapsed` or `expanded`)
     - a short summary string (provider-defined; e.g. '+500 lines')
     - an optional hint (provider-defined; e.g. 'Enter: expand, Ctrl-O: edit')

   Wire format:
     [*Block:<id>* collapsed: <summary> | <hint>]
     [*Block:<id>* expanded:  <summary> | <hint>]

   The hint and the leading '|' are optional. The summary is everything
   between ':' and either '|' or ']'."
  (:require [clojure.string :as str]))

(def ^:const id-pattern
  "Allowed characters in a block id."
  "[a-z0-9]+")

(def marker-re
  "Regex matching either state. Captures [whole-match id state summary-and-hint].
   - group 1: id
   - group 2: state (\"collapsed\" or \"expanded\")
   - group 3: summary + optional hint (everything between ':' and ']')"
  ;; The summary is non-greedy so a stray ']' inside another span on the
  ;; same line doesn't get swallowed.
  #"\[\*Block:([a-z0-9]+)\*\s+(collapsed|expanded):\s*([^\]]*)\]")

(defn build-line
  "Build a marker line for the given id/state.
     state    — :collapsed or :expanded
     summary  — short human string, e.g. '+500 lines' or '100 of 500 lines'
     hint     — optional short hint, e.g. 'Enter: expand, Ctrl-O: edit'

   Returns a single-line string (no trailing newline)."
  [id state summary & {:keys [hint]}]
  (let [state-name (case state
                     :collapsed "collapsed"
                     :expanded  "expanded")
        body (if (str/blank? hint)
               summary
               (str summary " | " hint))]
    (str "[*Block:" id "* " state-name ": " body "]")))

(defn collapsed-line
  "Convenience: build a `collapsed` marker line."
  [id summary & {:keys [hint]}]
  (build-line id :collapsed summary :hint hint))

(defn expanded-line
  "Convenience: build an `expanded` marker line."
  [id summary & {:keys [hint]}]
  (build-line id :expanded summary :hint hint))

;; ----------------------------------------------------------------------------
;; :line-decorator output shapes
;;
;; A provider's `:line-decorator` maps one logical line of block content to what
;; should appear in scrollback. Producers use it to keep the tail spliced in on
;; expand visually consistent with the head they already emitted — which means
;; it must be allowed to be 1→N, not just 1→1: the head was WORD-WRAPPED to the
;; pane, and a tail line that is not gets truncated at the right edge by the
;; renderer's width clamp. That loses text silently, and `Ctrl-O` then shows the
;; full line from the backing file — expand and edit disagreeing about the same
;; content, with no hint that anything was dropped.
;;
;; So providers normalise through these two: `decorated-rows` wherever content
;; lines are emitted, `decorated-line` for the marker lines, which must stay on
;; ONE scrollback row for `marker-re` to find them.

(defn decorated-rows
  "Normalise a `:line-decorator` result (string, seq of strings, or nil) into a
   row vector."
  [x]
  (cond
    (nil? x)    []
    (string? x) [x]
    :else       (vec x)))

(defn decorated-line
  "A `:line-decorator` result collapsed back to ONE row, for marker lines.
   Safe because every wrapping decorator passes marker lines through unwrapped —
   a split marker would be unfindable and so untoggleable."
  [x]
  (or (first (decorated-rows x)) ""))

(defn parse
  "Parse a single line. Returns {:id :state :summary :hint} or nil if no match.
   When the body has no `|` separator, :hint is nil."
  [line]
  (when-let [m (and (string? line) (re-find marker-re line))]
    (let [[_ id state body] m
          [summary hint] (let [parts (str/split (str body) #"\s*\|\s*" 2)]
                           (if (= 1 (count parts))
                             [(str/trim (first parts)) nil]
                             [(str/trim (first parts)) (str/trim (second parts))]))]
      {:id      id
       :state   (keyword state)
       :summary summary
       :hint    hint})))

(defn scan-lines
  "Scan a vector of scrollback lines (with optional [start end) bounds)
   and return a vector of {:line-idx :id :state :summary :hint} in
   scrollback order."
  ([lines] (scan-lines lines 0 (count lines)))
  ([lines start end]
   (let [start (max 0 start)
         end   (min (count lines) end)]
     (vec
      (for [i (range start end)
            :let [parsed (parse (get lines i))]
            :when parsed]
        (assoc parsed :line-idx i))))))
