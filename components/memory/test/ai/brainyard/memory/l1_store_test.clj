;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: Apache-2.0
;; Licensed under the Apache License, Version 2.0 (the "License"); you may
;; not use this file except in compliance with the License. See LICENSE at
;; the repository root and CONTRIBUTING.md for contribution terms.

(ns ai.brainyard.memory.l1-store-test
  (:require [clojure.test :refer [deftest is testing]]
            [ai.brainyard.memory.core.l1-store :as l1]
            [ai.brainyard.memory.interface.protocol :as proto]))

;; =====================================================
;; ID helper
;; =====================================================

(deftest l1-entry-id-format-test
  (testing "Canonical {kind}/{field}/{section} format"
    (is (= "system-context/tool-context/naming"
           (l1/l1-entry-id :system-context :tool-context "naming")))
    (is (= "user-context/preferences/timezone"
           (l1/l1-entry-id :user-context :preferences "timezone")))
    (is (= "system-context/tool-context/naming"
           (l1/l1-entry-id "system-context" "tool-context" "naming"))
        "Strings work for all three segments")))

;; =====================================================
;; Round-trip
;; =====================================================

(deftest write-read-roundtrip-test
  (testing "round-trip preserves entry shape"
    (let [s   (l1/create-l1-store)
          sid "sess-1"
          id  (l1/l1-entry-id :system-context :tool-context "naming")]
      (proto/write-entry s :l1
                         {:kind       :system-context
                          :id         id
                          :content    "Use kebab-case."
                          :session-id sid
                          :user-id    "u1"
                          :data       {:field :tool-context :section "naming"}})
      (let [[e] (proto/read-entries s :l1 {:id id} {})]
        (is (= "Use kebab-case." (:content e)))
        (is (= :l1 (:layer e)))
        (is (= :system-context (:kind e)))
        (is (= sid (:session-id e)))
        (is (= id (:id e)))
        (is (= :tool-context (-> e :data :field)))
        (is (= "naming"       (-> e :data :section)))))))

(deftest entry-id-stability-test
  (testing "re-writing the same (sid, entry-id) updates rather than duplicates"
    (let [s   (l1/create-l1-store)
          sid "sess-2"
          id  (l1/l1-entry-id :user-context :preferences "tz")]
      (proto/write-entry s :l1 {:kind :user-context :id id :content "v1"
                                :session-id sid :user-id "u" :data {:field :preferences :section "tz"}})
      (proto/write-entry s :l1 {:kind :user-context :id id :content "v2"
                                :session-id sid :user-id "u" :data {:field :preferences :section "tz"}})
      (is (= 1 (count (proto/read-entries s :l1 {:session-id sid} {}))))
      (is (= "v2" (-> (proto/read-entries s :l1 {:id id} {}) first :content))))))

;; =====================================================
;; Required session-id
;; =====================================================

(deftest write-without-session-id-throws-test
  (let [s (l1/create-l1-store)]
    (is (thrown? clojure.lang.ExceptionInfo
                 (proto/write-entry s :l1
                                    {:kind :system-context
                                     :content "no session"
                                     :user-id "u1"
                                     :data {:field :tool-context :section "x"}}))
        "L1 entries require a non-nil :session-id")))

;; =====================================================
;; Session scoping (composite key allows two sessions to share an entry-id)
;; =====================================================

(deftest two-sessions-same-entry-id-test
  (testing "Sessions A and B can both have the same entry-id without collision"
    (let [s  (l1/create-l1-store)
          id (l1/l1-entry-id :user-context :preferences "tz")]
      (proto/write-entry s :l1 {:kind :user-context :id id :content "Asia/Seoul"
                                :session-id "sA" :user-id "u" :data {:field :preferences :section "tz"}})
      (proto/write-entry s :l1 {:kind :user-context :id id :content "America/NY"
                                :session-id "sB" :user-id "u" :data {:field :preferences :section "tz"}})
      (let [a (-> (proto/read-entries s :l1 {:session-id "sA"} {}) first :content)
            b (-> (proto/read-entries s :l1 {:session-id "sB"} {}) first :content)]
        (is (= "Asia/Seoul" a))
        (is (= "America/NY" b))))))

;; =====================================================
;; Match by kind / field / section
;; =====================================================

(deftest match-filters-test
  (let [s   (l1/create-l1-store)
        sid "sf"
        sys (l1/l1-entry-id :system-context :tool-context "naming")
        usr (l1/l1-entry-id :user-context :preferences "tz")]
    (proto/write-entry s :l1 {:kind :system-context :id sys :content "kebab"
                              :session-id sid :user-id "u"
                              :data {:field :tool-context :section "naming"}})
    (proto/write-entry s :l1 {:kind :user-context :id usr :content "Asia/Seoul"
                              :session-id sid :user-id "u"
                              :data {:field :preferences :section "tz"}})
    (testing ":kind filter"
      (is (= 1 (count (proto/read-entries s :l1 {:kind :system-context :session-id sid} {}))))
      (is (= 1 (count (proto/read-entries s :l1 {:kind :user-context :session-id sid} {})))))
    (testing ":field filter"
      (is (= 1 (count (proto/read-entries s :l1 {:field :tool-context :session-id sid} {}))))
      (is (= 1 (count (proto/read-entries s :l1 {:field :preferences :session-id sid} {})))))
    (testing ":section filter"
      (is (= 1 (count (proto/read-entries s :l1 {:section "naming" :session-id sid} {})))))
    (testing "no filter returns both"
      (is (= 2 (count (proto/read-entries s :l1 {:session-id sid} {})))))))

;; =====================================================
;; Forget
;; =====================================================

(deftest forget-test
  (let [s   (l1/create-l1-store)
        sid "sf"
        id  (l1/l1-entry-id :system-context :instruction "safety")]
    (proto/write-entry s :l1 {:kind :system-context :id id :content "be careful"
                              :session-id sid :user-id "u"
                              :data {:field :instruction :section "safety"}})
    (is (proto/forget s :l1 [sid id]) "forget by composite [sid id]")
    (is (zero? (count (proto/read-entries s :l1 {:session-id sid} {}))))
    (is (not (proto/forget s :l1 [sid id])) "second forget returns false")))

;; =====================================================
;; Concurrency smoke (no quota, just proves no torn state)
;; =====================================================

(deftest concurrent-write-test
  (let [s   (l1/create-l1-store)
        sid "concurrent"
        n   200
        futures (mapv (fn [i]
                        (future
                          (let [id (l1/l1-entry-id :user-context :scratch (str "k" i))]
                            (proto/write-entry s :l1
                                               {:kind :user-context :id id :content (str i)
                                                :session-id sid :user-id "u"
                                                :data {:field :scratch :section (str "k" i)}}))))
                      (range n))]
    (run! deref futures)
    (is (= n (count (proto/read-entries s :l1 {:session-id sid} {:limit (* n 2)}))))))
