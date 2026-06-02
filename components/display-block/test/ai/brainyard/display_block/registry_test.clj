;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.display-block.registry-test
  (:require [ai.brainyard.display-block.core.providers.in-memory :as in-memory]
            [ai.brainyard.display-block.core.registry :as registry]
            [ai.brainyard.display-block.interface :as block]
            [clojure.test :refer [deftest is testing use-fixtures]]))

(use-fixtures :each (fn [t] (registry/clear!) (t) (registry/clear!)))

(deftest register-and-lookup
  (let [p  (in-memory/make "line1\nline2\nline3"
                           {:id "deadbeef" :label "X" :hidden-lines 0})
        id (registry/register! p)]
    (is (= "deadbeef" id))
    (is (some? (registry/get-block id)))
    (is (= "X" (:label (block/block-meta id))))))

(deftest dispose-removes-entry
  (let [p (in-memory/make "x\ny" {:id "abc111"})]
    (registry/register! p)
    (is (true? (registry/dispose! "abc111")))
    (is (nil? (registry/get-block "abc111")))
    (is (false? (registry/dispose! "abc111"))
        "second dispose returns false when entry missing")))

(deftest collapse-and-expand-via-interface
  (let [body  (clojure.string/join "\n" (map #(str "line " %) (range 50)))
        p     (in-memory/make body
                              {:id "len50" :label "Body" :hidden-lines 40
                               :max-expanded-lines 100})
        id    (registry/register! p)
        coll  (block/collapse-line id)
        exp   (block/expand-lines id)]
    (is (re-find block/marker-re coll))
    (is (= :collapsed (:state (block/parse-marker coll))))
    (is (vector? exp))
    (testing "expand returns tail lines then expanded marker"
      ;; The new design drops the `--- Expanded ---` open notice entirely
      ;; and only inserts the hidden tail (lines beyond the head already
      ;; in scrollback) followed by the expanded marker.
      (is (not (clojure.string/starts-with? (first exp) "--- Expanded"))
          "no open notice anymore")
      (is (= :expanded (:state (block/parse-marker (last exp))))))))
