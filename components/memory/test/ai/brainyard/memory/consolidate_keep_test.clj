;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: Apache-2.0
;; Licensed under the Apache License, Version 2.0 (the "License"); you may
;; not use this file except in compliance with the License. See LICENSE at
;; the repository root and CONTRIBUTING.md for contribution terms.

(ns ai.brainyard.memory.consolidate-keep-test
  "P4: when the S2 reducer produces L3 facts from a batch of L2
  episodes, those source episodes get keep_flag=1 so they survive the
  retention sweep and the fact's :sources chain stays valid."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [next.jdbc :as jdbc]
            [ai.brainyard.memory.interface :as mem]
            [ai.brainyard.memory.interface.protocol :as proto]))

(def ^:dynamic *mm* nil)

(use-fixtures :each
  (fn [f]
    (let [mm (mem/create-memory-manager (str "u-ck-" (random-uuid))
                                        :in-memory true)]
      (try
        (binding [*mm* mm] (f))
        (finally (.close (:ds mm)))))))

(defn- seed-batch!
  "Write n episodes sharing tag-set `tags`. The reducer requires
  numeric :created-at; in the unified store we pass it explicitly so
  bucket-key works for the in-memory L1Store path. For L2 (SQL), the
  DB stamps timestamp from CURRENT_TIMESTAMP so the bucket-key
  ->millis path will use the parsed string timestamp."
  [n tags]
  (dotimes [i n]
    (proto/write-entry (mem/store *mm*) :l2
                       {:kind :conversation :role "user"
                        :content (str "msg-" i)
                        :session-id "s-ck"
                        :tags tags})))

(deftest consolidate-marks-source-episodes-keep-test
  (seed-batch! 4 #{"topic:deploy"})
  (let [out (mem/consolidate-l2! *mm* :session-id "s-ck"
                                 :window-ms 600000 :min-batch 3)]
    (is (pos? (:produced out))
        "At least one batch produced an L3 fact")
    (is (pos? (:auto-kept out))
        "Source episodes were auto-kept")
    ;; All 4 episodes should now have keep_flag = 1
    (let [r (jdbc/execute-one! (:ds *mm*)
                               ["SELECT COUNT(*) AS n FROM episodes
                                  WHERE session_id = 's-ck' AND keep_flag = 1"])]
      (is (= 4 (:n r))))))

(deftest consolidated-episodes-survive-sweep-test
  (seed-batch! 4 #{"topic:deploy"})
  (mem/consolidate-l2! *mm* :session-id "s-ck"
                       :window-ms 600000 :min-batch 3)
  ;; Backdate every episode 60 days
  (jdbc/execute! (:ds *mm*)
                 ["UPDATE episodes SET timestamp = datetime('now', '-60 days')
                   WHERE session_id = 's-ck'"])
  ;; Run the sweep — kept episodes survive
  (let [n (mem/sweep-l2! *mm*)]
    (is (zero? n)
        "Consolidated episodes are auto-kept and skip the sweep"))
  (let [visible (proto/read-entries (mem/store *mm*) :l2 {:session-id "s-ck"} {})]
    (is (= 4 (count visible))
        "All 4 source episodes remain visible after sweep")))
