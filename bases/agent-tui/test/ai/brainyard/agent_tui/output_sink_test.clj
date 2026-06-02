;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent-tui.output-sink-test
  (:require [ai.brainyard.agent-tui.output-sink :as out-sink]
            [clojure.test :refer [deftest is testing use-fixtures]]))

(use-fixtures :each
  (fn [f]
    (out-sink/revert!)
    (try (f)
         (finally (out-sink/revert!)))))

(deftest install-and-route
  (testing "no sink installed → route! returns false"
    (is (false? (out-sink/installed?)))
    (is (false? (out-sink/route! "anything"))))
  (testing "installed sink receives the string and route! returns true"
    (let [captured (atom [])]
      (out-sink/install! (fn [s] (swap! captured conj s)))
      (is (true? (out-sink/installed?)))
      (is (true? (out-sink/route! "hello")))
      (is (= ["hello"] @captured))))
  (testing "install! returns the previous sink"
    (let [a (fn [_])
          b (fn [_])]
      (out-sink/revert!)
      (is (nil? (out-sink/install! a)))
      (is (= a (out-sink/install! b)))
      (is (= b (out-sink/install! nil)))
      (is (false? (out-sink/installed?)))))
  (testing "route! swallows exceptions in the sink fn"
    (out-sink/install! (fn [_] (throw (RuntimeException. "boom"))))
    (is (true? (out-sink/route! "x")))))
