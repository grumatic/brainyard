;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: Apache-2.0
;; Licensed under the Apache License, Version 2.0 (the "License"); you may
;; not use this file except in compliance with the License. See LICENSE at
;; the repository root and CONTRIBUTING.md for contribution terms.

(ns ai.brainyard.memory.policy-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [next.jdbc :as jdbc]
            [ai.brainyard.memory.interface :as mem]
            [ai.brainyard.memory.interface.protocol :as proto]
            [ai.brainyard.memory.core.policy :as policy]))

(def ^:dynamic *mm* nil)

(use-fixtures :each
  (fn [f]
    (let [mm (mem/create-memory-manager (str "u-pol-" (random-uuid))
                                        :in-memory true)]
      (try
        (binding [*mm* mm] (f))
        (finally (.close (:ds mm)))))))

(defn- write-l2! [content & {:keys [keep archived]
                             :or {keep false archived false}}]
  (proto/write-entry (mem/store *mm*) :l2
                     {:kind :conversation :role "user"
                      :content content
                      :session-id "s-pol"
                      :keep keep
                      :archived archived}))

;; =====================================================
;; mark-keep!
;; =====================================================

(deftest keep-pins-entry-test
  (let [e (write-l2! "Pinned message")]
    (is (mem/keep! *mm* :l2 (:id e))
        "keep! returns true for an existing entry")
    (let [r (jdbc/execute-one! (:ds *mm*)
                               ["SELECT keep_flag FROM episodes WHERE entry_id = ?"
                                (:id e)])]
      (is (= 1 (:episodes/keep_flag r))))))

(deftest unkeep-clears-flag-test
  (let [e (write-l2! "Once pinned" :keep true)]
    (mem/unkeep! *mm* :l2 (:id e))
    (let [r (jdbc/execute-one! (:ds *mm*)
                               ["SELECT keep_flag FROM episodes WHERE entry_id = ?"
                                (:id e)])]
      (is (= 0 (:episodes/keep_flag r))))))

(deftest keep-on-missing-entry-returns-false-test
  (is (not (mem/keep! *mm* :l2 "no-such-id"))))

;; =====================================================
;; archive!
;; =====================================================

(deftest archive-excludes-from-default-recall-test
  (let [e (write-l2! "Hidden message")]
    (mem/archive! *mm* :l2 (:id e))
    (is (zero? (count (proto/read-entries (mem/store *mm*) :l2
                                          {:session-id "s-pol"} {})))
        "Archived entries hidden from default reads")
    (is (= 1 (count (proto/read-entries (mem/store *mm*) :l2
                                        {:session-id "s-pol"}
                                        {:include-archived true})))
        "include-archived surfaces them again")))

(deftest unarchive-restores-test
  (let [e (write-l2! "Toggle me")]
    (mem/archive! *mm* :l2 (:id e))
    (mem/unarchive! *mm* :l2 (:id e))
    (is (= 1 (count (proto/read-entries (mem/store *mm*) :l2
                                        {:session-id "s-pol"} {}))))))

;; =====================================================
;; sweep-l2!
;; =====================================================

(deftest sweep-tombstones-old-non-kept-test
  ;; Insert two episodes — one fresh, one fake-aged via direct UPDATE.
  (let [fresh (write-l2! "fresh")
        old   (write-l2! "old")]
    ;; Backdate `old` 60 days
    (jdbc/execute! (:ds *mm*)
                   ["UPDATE episodes SET timestamp = datetime('now', '-60 days')
                     WHERE entry_id = ?" (:id old)])
    (let [n (mem/sweep-l2! *mm*)]
      (is (= 1 n) "Exactly one episode tombstoned"))
    ;; The fresh one survives in default reads; the old one is hidden.
    (let [visible (proto/read-entries (mem/store *mm*) :l2
                                      {:session-id "s-pol"} {})
          all     (proto/read-entries (mem/store *mm*) :l2
                                      {:session-id "s-pol"}
                                      {:include-tombstoned true})]
      (is (= 1 (count visible)))
      (is (= "fresh" (-> visible first :content)))
      (is (= 2 (count all))))))

(deftest sweep-respects-keep-flag-test
  (let [keep-me (write-l2! "always-keep" :keep true)
        drop-me (write-l2! "to-drop")]
    ;; Backdate both 60 days
    (doseq [id [(:id keep-me) (:id drop-me)]]
      (jdbc/execute! (:ds *mm*)
                     ["UPDATE episodes SET timestamp = datetime('now', '-60 days')
                       WHERE entry_id = ?" id]))
    (let [n (mem/sweep-l2! *mm*)]
      (is (= 1 n) "Only the un-pinned episode is tombstoned"))
    (let [visible (proto/read-entries (mem/store *mm*) :l2
                                      {:session-id "s-pol"} {})]
      (is (= 1 (count visible)))
      (is (= "always-keep" (-> visible first :content))))))

(deftest sweep-custom-retention-test
  (let [e (write-l2! "yesterday")]
    (jdbc/execute! (:ds *mm*)
                   ["UPDATE episodes SET timestamp = datetime('now', '-2 days')
                     WHERE entry_id = ?" (:id e)])
    ;; Default 30d → no tombstone
    (is (zero? (mem/sweep-l2! *mm*)))
    ;; But 1d retention → it's older than that
    (is (= 1 (mem/sweep-l2! *mm* :retention-days 1)))))

;; =====================================================
;; tombstone is idempotent (already-tombstoned rows skipped)
;; =====================================================

(deftest sweep-idempotent-test
  (let [e (write-l2! "old")]
    (jdbc/execute! (:ds *mm*)
                   ["UPDATE episodes SET timestamp = datetime('now', '-60 days')
                     WHERE entry_id = ?" (:id e)])
    (mem/sweep-l2! *mm*)
    (is (zero? (mem/sweep-l2! *mm*))
        "Re-running sweep on already-tombstoned data is a no-op")))
