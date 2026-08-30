
;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.task.drain-concurrency-test
  "The incremental output drain must not emit a line twice.

   The manager runs a periodic sampler (~300ms) AND a final flush at
   completion, and they can overlap. The drain used to read the offset, emit,
   then reset -- non-atomically -- so both callers read the same offset and
   both emitted the same lines. It surfaced once as a duplicated line in a
   loaded `bb test` run and was invisible in isolation, because an eval that
   finishes before the first sample never overlaps."
  (:require [clojure.test :refer [deftest is testing]]
            [ai.brainyard.agent.task.executor :as ex])
  (:import [java.io StringWriter]
           [java.util.concurrent CountDownLatch]))

(defn- race-once
  "Fire a sampler and a final flush simultaneously over the same writer."
  []
  (let [w      (StringWriter.)
        !drain (atom 0)
        !out   (atom [])
        on-out (fn [l] (swap! !out conj l))
        go     (CountDownLatch. 1)
        mk     (fn [flush?]
                 (doto (Thread. (fn []
                                  (.await go)
                                  (ex/drain-incremental-output! on-out w !drain flush?)))
                   (.setDaemon true)))]
    (.write w "hello\nworld\n")
    (let [a (mk false) b (mk true)]
      (.start a) (.start b)
      (.countDown go)
      (.join a 5000) (.join b 5000))
    @!out))

(deftest concurrent-drains-never-duplicate-a-line
  (testing "a sampler racing the final flush emits each line exactly once

           400 trials: before the fix this failed ~40% of the time with output
           like [\"hello\" \"hello\" \"world\" \"world\"]. One trial would be a
           coin flip, which is why the count is high."
    (let [results (repeatedly 200 race-once)
          bad     (remove #(= ["hello" "world"] %) results)]
      (is (empty? bad)
          (str "every trial must yield exactly [\"hello\" \"world\"]; got "
               (pr-str (vec (take 3 (distinct bad)))))))))

(deftest sequential-drain-still-streams-then-flushes
  (testing "the ordinary case is unchanged: a sampler emits complete lines
            only, and the flush picks up exactly where it stopped"
    (let [w (StringWriter.) !drain (atom 0) !out (atom [])
          on-out (fn [l] (swap! !out conj l))]
      (.write w "alpha\nbeta")                    ;; trailing partial, no newline
      (ex/drain-incremental-output! on-out w !drain false)
      (is (= ["alpha"] @!out) "a sampler must not emit the partial line")
      (ex/drain-incremental-output! on-out w !drain true)
      (is (= ["alpha" "beta"] @!out) "the flush emits the remainder, once"))))

(deftest drain-with-nothing-new-emits-nothing
  (testing "repeated drains over unchanged output stay silent"
    (let [w (StringWriter.) !drain (atom 0) !out (atom [])
          on-out (fn [l] (swap! !out conj l))]
      (.write w "only\n")
      (ex/drain-incremental-output! on-out w !drain true)
      (ex/drain-incremental-output! on-out w !drain true)
      (ex/drain-incremental-output! on-out w !drain false)
      (is (= ["only"] @!out)))))
