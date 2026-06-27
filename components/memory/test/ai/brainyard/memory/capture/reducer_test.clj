;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

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

(defn- seed-varied!
  "Write episodes that share `base-tags` but each carry a UNIQUE `topic:` tag
   (mirrors real Q&A capture, which auto-extracts distinct per-turn topics).
   Returns nothing."
  [n base-tags base-time session-id]
  (dotimes [i n]
    (proto/write-entry (mem/store *mm*) :l2
                       {:kind :conversation
                        :role "user"
                        :content (str "msg-" i)
                        :session-id session-id
                        :tags (conj base-tags (str "topic:unique-" i))
                        :created-at (+ base-time (* i 1000))})))

(deftest reducer-merges-varied-topics-in-window-test
  ;; Regression: real Q&A episodes each get DISTINCT `topic:` tags
  ;; (topic:teal, topic:photon, …). The bucket key must ignore topic: so a
  ;; normal run of conversation in one window still batches; otherwise the
  ;; heuristic reducer never fires (produced 0) for real usage.
  (let [now (System/currentTimeMillis)]
    (seed-varied! 3 #{"role:conversation"} now "s-r1")
    (let [out (mem/consolidate-l2! *mm* :session-id "s-r1")]
      (is (= 1 (:produced out))
          "Three varied-topic turns in one (role, window) batch into ONE summary")
      (is (= 3 (:consumed out))))))

(deftest reducer-batches-by-role-and-window-test
  (let [now (System/currentTimeMillis)]
    ;; Same role, same 10-min window — varied topics merge into one batch.
    (seed-l2! 5 #{"topic:deploy" "role:user"} now "s-r1b")
    (seed-l2! 4 #{"topic:auth" "role:user"} (+ now 100) "s-r1b")
    ;; No role tag → a different (empty-tag) bucket; only 2 events → skipped.
    (seed-l2! 2 #{"topic:loose"} (+ now 200) "s-r1b")
    (let [out (mem/consolidate-l2! *mm* :session-id "s-r1b")]
      (is (= 1 (:produced out))
          "All 9 role:user events share one (role, window) bucket; the 2-event no-role group is skipped")
      (is (= 9 (:consumed out))
          "Consumed entries are the 5+4=9 role:user events"))))

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
