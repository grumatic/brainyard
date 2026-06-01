;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: Apache-2.0
;; Licensed under the Apache License, Version 2.0 (the "License"); you may
;; not use this file except in compliance with the License. See LICENSE at
;; the repository root and CONTRIBUTING.md for contribution terms.

(ns ai.brainyard.memory.audit-test
  "Tests for the memory_audit table — `record-prompt!` and the
  `explain` / `explain-session` API."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [ai.brainyard.memory.interface :as mem]
            [ai.brainyard.memory.interface.protocol :as proto]))

(def ^:dynamic *mm* nil)

(use-fixtures :each
  (fn [f]
    (let [mm (mem/create-memory-manager (str "u-aud-" (random-uuid))
                                        :in-memory true)]
      (try
        (binding [*mm* mm] (f))
        (finally (.close (:ds mm)))))))

(defn- seed-l2! []
  (proto/write-entry (mem/store *mm*) :l2
                     {:kind :conversation :role "user"
                      :content "How do I deploy?"
                      :session-id "s-aud"
                      :tags #{"topic:deploy"}}))

(defn- contextual-recall! [opts]
  (proto/contextual-recall *mm* "deploy" opts))

;; =====================================================
;; Audit recording from contextual-recall
;; =====================================================

(deftest contextual-recall-without-turn-id-records-nothing-test
  (seed-l2!)
  (contextual-recall! {:session-id "s-aud"})
  (let [out (mem/explain *mm* "s-aud" "agt-x" 1)]
    (is (zero? (count (:entries out)))
        "Without :turn-id, no audit row is written")))

(deftest contextual-recall-with-turn-id-records-prompt-test
  (seed-l2!)
  (contextual-recall! {:session-id "s-aud" :agent-id "agt-1" :turn-id 1
                       :total-turns 1})
  (let [out (mem/explain *mm* "s-aud" "agt-1" 1)]
    (is (pos? (count (:entries out))))
    (is (every? :audit (:entries out)))
    (is (every? :entry (:entries out))
        "Each audit row hydrates the live entry")))

(deftest explain-includes-prompt-bytes-test
  (seed-l2!)
  (contextual-recall! {:session-id "s-aud" :agent-id "agt-1" :turn-id 7
                       :total-turns 7})
  (let [out (mem/explain *mm* "s-aud" "agt-1" 7)]
    (is (pos? (:prompt-bytes out)))
    (is (= 7 (:turn-id out)))
    (is (= "agt-1" (:agent-id out)))
    (is (= "s-aud" (:session-id out)))))

(deftest explain-empty-when-turn-not-recorded-test
  (let [out (mem/explain *mm* "s-aud" "agt-1" 999)]
    (is (= [] (:entries out)))
    (is (= 0 (:prompt-bytes out)))))

;; =====================================================
;; explain-session
;; =====================================================

(deftest explain-session-collects-all-turns-test
  (seed-l2!)
  (contextual-recall! {:session-id "s-aud" :agent-id "agt-1" :turn-id 1 :total-turns 1})
  (contextual-recall! {:session-id "s-aud" :agent-id "agt-1" :turn-id 2 :total-turns 2})
  (contextual-recall! {:session-id "s-aud" :agent-id "agt-1" :turn-id 3 :total-turns 3})
  (let [out (mem/explain-session *mm* "s-aud")]
    (is (= 3 (count (:turns out))))
    (is (= [1 2 3] (mapv :turn-id (:turns out)))
        "Per-agent turn-ids ascending")
    (is (every? #(= "agt-1" (:agent-id %)) (:turns out)))
    (is (= [1 2 3] (mapv :total-turns (:turns out)))
        "total-turns recorded alongside per-agent turn-id")))

(deftest explain-session-orders-by-total-turns-across-agents-test
  (seed-l2!)
  ;; Interleave asks across two agents in the same session.
  (contextual-recall! {:session-id "s-aud" :agent-id "root" :turn-id 1 :total-turns 1})
  (contextual-recall! {:session-id "s-aud" :agent-id "sub"  :turn-id 1 :total-turns 2})
  (contextual-recall! {:session-id "s-aud" :agent-id "root" :turn-id 2 :total-turns 3})
  (let [out (mem/explain-session *mm* "s-aud")]
    (is (= [1 2 3] (mapv :total-turns (:turns out)))
        "Slots ordered by total-turns ascending (true ask order)")
    (is (= ["root" "sub" "root"] (mapv :agent-id (:turns out))))
    (is (= [1 1 2] (mapv :turn-id (:turns out)))
        "Per-agent turn-ids reset per agent")))

;; =====================================================
;; Hydration survives forget
;; =====================================================

(deftest explain-after-forget-keeps-audit-loses-entry-body-test
  (testing "Tombstoning the underlying entry doesn't drop its audit row,
            but :entry body still hydrates because we use
            include-tombstoned in the explain path"
    (let [e (seed-l2!)]
      (contextual-recall! {:session-id "s-aud" :agent-id "agt-1" :turn-id 5
                           :total-turns 5})
      (proto/forget (mem/store *mm*) :l2 (:id e))
      (let [out (mem/explain *mm* "s-aud" "agt-1" 5)]
        (is (pos? (count (:entries out)))
            "Audit rows survive even after the entry is tombstoned")
        ;; Hydration uses :include-tombstoned so the body is still readable.
        (is (some :entry (:entries out))
            "Entry bodies remain retrievable for audit purposes")))))
