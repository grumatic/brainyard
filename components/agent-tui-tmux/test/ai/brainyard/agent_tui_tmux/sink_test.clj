;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent-tui-tmux.sink-test
  (:require [ai.brainyard.agent-tui-tmux.core.sink :as sink]
            [clojure.test :refer [deftest is testing]])
  (:import [java.io StringWriter]))

(deftest writer-sink-test
  (let [sw (StringWriter.)
        s  (sink/writer-sink sw)]
    (sink/write! s "hello ")
    (sink/write! s "world\n")
    (sink/flush! s)
    (is (= "hello world\n" (.toString sw)))
    (sink/close* s)
    (sink/write! s "ignored")
    (is (= "hello world\n" (.toString sw)))))

(deftest null-sink-test
  (is (some? sink/null-sink))
  (sink/write! sink/null-sink "anything")
  (sink/flush! sink/null-sink)
  (sink/close* sink/null-sink))

(deftest write-string-coercions
  (let [sw (StringWriter.)
        s  (sink/writer-sink sw)]
    (sink/write-string! s "abc")
    (sink/write-string! s 123)
    (sink/write-string! s (StringBuilder. "ok"))
    (is (= "abc123ok" (.toString sw)))))

(deftest multi-sink-routing
  (let [a-sw (StringWriter.) b-sw (StringWriter.)
        ms (sink/multi-sink {:stream (sink/writer-sink a-sw)
                             :status (sink/writer-sink b-sw)})]
    (sink/write-stream! ms "<stream>")
    (sink/write-status! ms "<status>")
    (sink/write-activity! ms "<discarded>")
    (is (= "<stream>" (.toString a-sw)))
    (is (= "<status>" (.toString b-sw)))))

(deftest multi-sink-replace-channel
  (let [orig (StringWriter.) replaced (StringWriter.)
        ms (sink/multi-sink {:stream (sink/writer-sink orig)})]
    (sink/write-stream! ms "to-orig")
    (sink/set-channel! ms :stream (sink/writer-sink replaced))
    (sink/write-stream! ms "to-new")
    (is (= "to-orig" (.toString orig)))
    (is (= "to-new"  (.toString replaced)))))

(deftest multi-sink-close-all
  (let [a (StringWriter.)
        ms (sink/multi-sink {:stream (sink/writer-sink a)})]
    (sink/write-stream! ms "before-close")
    (sink/close-all! ms)
    ;; After close-all!, channels are reset to nothing — null routing.
    (sink/write-stream! ms "after-close")
    (is (= "before-close" (.toString a)))))

(deftest pipe-sink-fallbacks
  (testing "pipe-sink with a non-existent path doesn't blow up at construction"
    (let [s (sink/pipe-sink "/tmp/agent-tui-test-non-existent-fifo.does-not-exist")]
      (is (some? s))
      (sink/close* s))))
