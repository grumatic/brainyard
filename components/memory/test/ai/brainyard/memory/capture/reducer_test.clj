;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: Apache-2.0
;; Licensed under the Apache License, Version 2.0 (the "License"); you may
;; not use this file except in compliance with the License. See LICENSE at
;; the repository root and CONTRIBUTING.md for contribution terms.

(ns ai.brainyard.memory.capture.reducer-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [ai.brainyard.memory.interface :as mem]
            [ai.brainyard.memory.interface.protocol :as proto]
            [ai.brainyard.memory.core.capture.reducer :as reducer]))

(def ^:dynamic *mm* nil)

(use-fixtures :each
  (fn [f]
    (let [mm (mem/create-memory-manager (str "u-red-" (random-uuid))
                                        :in-memory true)]
      (try
        (binding [*mm* mm] (f))
        (finally (.close (:ds mm)))))))

(defn- seed-l2!
  "Write `n` episodes sharing tag-set `tags` and a base time."
  [n tags base-time session-id]
  (dotimes [i n]
    (proto/write-entry (mem/store *mm*) :l2
                       {:kind :conversation
                        :role "user"
                        :content (str "msg-" i)
                        :session-id session-id
                        :tags tags
                        :created-at (+ base-time (* i 1000))})))

;; =====================================================
;; Heuristic reducer
;; =====================================================

(deftest reducer-batches-by-tag-and-window-test
  (let [now (System/currentTimeMillis)]
    ;; Batch A: 5 events with topic:deploy in the same minute
    (seed-l2! 5 #{"topic:deploy" "role:user"} now "s-r1")
    ;; Batch B: 4 events with topic:auth in the same minute
    (seed-l2! 4 #{"topic:auth" "role:user"} (+ now 100) "s-r1")
    ;; Below threshold: 2 events alone
    (seed-l2! 2 #{"topic:loose"} (+ now 200) "s-r1")
    (let [out (mem/consolidate-l2! *mm* :session-id "s-r1")]
      (is (= 2 (:produced out))
          "Two batches qualify (≥3 events each); the 2-event group is skipped")
      (is (= 9 (:consumed out))
          "All consumed entries are 5+4=9"))))

(deftest reducer-writes-l3-with-provenance-test
  (let [now (System/currentTimeMillis)]
    (seed-l2! 4 #{"topic:deploy"} now "s-r2")
    (mem/consolidate-l2! *mm* :session-id "s-r2")
    (let [facts (proto/read-entries (mem/store *mm*) :l3 {:text "summary"} {})]
      (is (>= (count facts) 1))
      (let [f (first facts)]
        (is (= :summary (:kind f)))
        (is (>= (count (:sources f)) 4)
            "Sources chain back to all source episode ids")
        (is (every? #(= "consolidation" (str (:type %))) (:sources f)))))))

(deftest reducer-skips-when-below-threshold-test
  (let [now (System/currentTimeMillis)]
    (seed-l2! 2 #{"topic:rare"} now "s-r3")
    (let [out (mem/consolidate-l2! *mm* :session-id "s-r3")]
      (is (zero? (:produced out))))))

(deftest llm-reducer-falls-back-to-heuristic-test
  ;; LLM reducer is deferred to P4 — calling with :reducer :llm should
  ;; not throw, just warn and fall back.
  (let [now (System/currentTimeMillis)]
    (seed-l2! 3 #{"topic:test"} now "s-r4")
    (let [out (mem/consolidate-l2! *mm*
                                   :session-id "s-r4"
                                   :reducer :llm)]
      (is (pos? (:produced out))))))
