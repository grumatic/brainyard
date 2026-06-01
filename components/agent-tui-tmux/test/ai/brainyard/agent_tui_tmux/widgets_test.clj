;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: Apache-2.0
;; Licensed under the Apache License, Version 2.0 (the "License"); you may
;; not use this file except in compliance with the License. See LICENSE at
;; the repository root and CONTRIBUTING.md for contribution terms.

(ns ai.brainyard.agent-tui-tmux.widgets-test
  "Tests for the widget abstraction (live-block analogue at the tail of a
   tmux stream pane). Uses an in-memory `WriterSink` so the emitted ANSI
   bytes can be inspected directly."
  (:require [ai.brainyard.agent-tui-tmux.core.sink :as sink]
            [ai.brainyard.agent-tui-tmux.core.widgets :as w]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]])
  (:import [java.io StringWriter]))

(defn- fixture [t]
  (w/reset-registry!)
  (try (t) (finally (w/reset-registry!))))

(use-fixtures :each fixture)

(defn- ms-with-stream-buffer
  "Build a multi-sink whose :stream channel writes into a StringWriter,
   plus the writer itself for inspection."
  []
  (let [sw (StringWriter.)
        ms (sink/multi-sink {:stream (sink/writer-sink sw)})]
    [ms sw]))

(defn- raw [^StringWriter sw] (.toString sw))

;; ----------------------------------------------------------------------------
;; Create / read line-count
;; ----------------------------------------------------------------------------

(deftest set-widget-creates-and-tracks-line-count
  (let [[ms sw] (ms-with-stream-buffer)]
    (w/set-widget! ms :iter ["line 1" "line 2" "line 3"])
    (is (= 3 (w/line-count ms :iter)))
    (let [out (raw sw)]
      (is (str/includes? out "line 1"))
      (is (str/includes? out "line 2"))
      (is (str/includes? out "line 3"))
      ;; First render has no preamble (no previous lines to erase).
      (is (not (str/includes? out "\033[0J"))
          "first render should not emit erase-display preamble"))))

;; ----------------------------------------------------------------------------
;; Update overwrites previous render
;; ----------------------------------------------------------------------------

(deftest second-set-widget-emits-cursor-up-and-erase
  (let [[ms sw] (ms-with-stream-buffer)]
    (w/set-widget! ms :iter ["one" "two"])
    (w/set-widget! ms :iter ["one" "two-prime" "three"])
    (let [out (raw sw)]
      (is (= 3 (w/line-count ms :iter))
          "line-count tracks the new render")
      ;; Second render must emit cursor-up-2-rows (CSI 2 F) then erase
      ;; from cursor to end of display (CSI 0 J).
      (is (re-find #"\033\[2F\033\[0J" out)
          (str "expected cursor-up-2 + erase-display preamble; got: "
               (pr-str out)))
      (is (str/includes? out "two-prime") "new content present")
      (is (str/includes? out "three")))))

(deftest update-with-same-line-count-still-redraws
  (let [[ms sw] (ms-with-stream-buffer)]
    (w/set-widget! ms :iter ["a" "b"])
    (w/set-widget! ms :iter ["X" "Y"])
    (is (= 2 (w/line-count ms :iter)))
    (is (re-find #"\033\[2F" (raw sw)))))

;; ----------------------------------------------------------------------------
;; Freeze
;; ----------------------------------------------------------------------------

(deftest freeze-stops-tracking-but-leaves-content
  (let [[ms sw] (ms-with-stream-buffer)]
    (w/set-widget! ms :iter ["a" "b"])
    (w/freeze-widget! ms :iter)
    (is (nil? (w/line-count ms :iter))
        "frozen widget is no longer tracked")
    ;; Subsequent set-widget! with the same id treats it as new — no
    ;; cursor-up preamble (since the previous render is now plain
    ;; scrollback, not a live widget).
    (let [size-before (count (raw sw))]
      (w/set-widget! ms :iter ["fresh"])
      (let [delta (subs (raw sw) size-before)]
        (is (str/includes? delta "fresh"))
        (is (not (re-find #"\033\[\dF\033\[0J" delta))
            "fresh post-freeze render must not erase prior lines")))))

;; ----------------------------------------------------------------------------
;; Clear
;; ----------------------------------------------------------------------------

(deftest clear-erases-and-disposes
  (let [[ms sw] (ms-with-stream-buffer)]
    (w/set-widget! ms :iter ["a" "b" "c"])
    (let [size-before (count (raw sw))]
      (w/clear-widget! ms :iter)
      (let [delta (subs (raw sw) size-before)]
        (is (re-find #"\033\[3F\033\[0J" delta)
            "clear emits cursor-up-3 + erase-display, no replacement content")
        (is (not (str/includes? delta "a"))
            "clear must not write any content lines")))
    (is (nil? (w/line-count ms :iter)))))

(deftest clear-on-unknown-widget-is-noop
  (let [[ms sw] (ms-with-stream-buffer)]
    (w/clear-widget! ms :nope)
    (is (= "" (raw sw)))))

;; ----------------------------------------------------------------------------
;; Multi-multi-sink isolation
;; ----------------------------------------------------------------------------

(deftest two-multi-sinks-track-independently
  (let [[ms-a _] (ms-with-stream-buffer)
        [ms-b _] (ms-with-stream-buffer)]
    (w/set-widget! ms-a :iter ["A"])
    (w/set-widget! ms-b :iter ["B" "B2"])
    (is (= 1 (w/line-count ms-a :iter)))
    (is (= 2 (w/line-count ms-b :iter)))))

(deftest forget-multi-sink-clears-its-entries
  (let [[ms-a _] (ms-with-stream-buffer)
        [ms-b _] (ms-with-stream-buffer)]
    (w/set-widget! ms-a :iter ["A"])
    (w/set-widget! ms-b :iter ["B"])
    (w/forget-multi-sink! ms-a)
    (is (nil? (w/line-count ms-a :iter)))
    (is (= 1 (w/line-count ms-b :iter))
        "forget on one sink must not affect another")))

;; ----------------------------------------------------------------------------
;; Placement validation
;; ----------------------------------------------------------------------------

(deftest unsupported-placement-throws
  (let [[ms _] (ms-with-stream-buffer)]
    (is (thrown-with-msg?
         IllegalArgumentException #"Unsupported widget placement"
         (w/set-widget! ms :iter ["x"] {:placement :above-input})))))
