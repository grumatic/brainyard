;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.clj-nrepl.classifier-test
  (:require [clojure.test :refer [deftest is testing]]
            [ai.brainyard.clj-nrepl.core.classifier :as cls]))

;; --- mutating-heads classifier ---------------------------------------------

(deftest read-only-classification
  (testing "pure arithmetic / inspection is read-only"
    (is (= :read-only (cls/classify "(+ 1 2)")))
    (is (= :read-only (cls/classify "(map inc [1 2 3])")))
    (is (= :read-only (cls/classify "@(atom 1)")))
    (is (= :read-only (cls/classify "(meta #'map)"))))

  (testing "multi-form code keeps :read-only when no form mutates"
    (is (= :read-only (cls/classify "(+ 1 2)\n(prn :hi)")))))

(deftest mutate-classification
  (testing "top-level mutating heads are flagged"
    (doseq [code ["(def x 1)"
                  "(defn f [] 1)"
                  "(defonce z 0)"
                  "(alter-var-root #'map (constantly nil))"
                  "(require '[clojure.string])"
                  "(import '(java.io File))"
                  "(load-string \"(+ 1 2)\")"
                  "(eval '(+ 1 2))"]]
      (is (= :mutate (cls/classify code)) (str "expected :mutate for " code))))

  (testing "unreadable code rejects safely (Phase-1 safe default)"
    (is (= :mutate (cls/classify "(foo")))))

(deftest mutate-reason-reports-cause
  (is (= 'def (cls/mutate-reason "(def x 1)")))
  (is (= 'alter-var-root (cls/mutate-reason "(alter-var-root #'+ identity)")))
  (is (nil? (cls/mutate-reason "(+ 1 2)")))
  (is (= :unreadable (cls/mutate-reason "(foo"))))

;; --- deny-list (always-on, independent of mutate classification) -----------

(deftest deny-list-detection
  (testing "process-control + credential reaches are denied"
    (doseq [code ["(System/exit 0)"
                  "(prn :System/exit)"
                  "(.exec (Runtime/getRuntime) \"ls\")"
                  "(require 'ai.brainyard.aws-client.interface)"
                  "(shutdown-agents)"]]
      (is (cls/denied? code) (str "expected denied? for " code))
      (is (string? (cls/deny-reason code)))))

  (testing "innocent code is not denied"
    (is (not (cls/denied? "(+ 1 2)")))
    (is (nil? (cls/deny-reason "(prn :hi)")))))

(deftest deny-list-is-independent-of-classify
  ;; Phase-2 split: classify is about top-level forms only. The
  ;; deny-list rejection happens at the gate, not via classify.
  (is (= :read-only (cls/classify "(prn :System/exit)"))
      "deny-list substring no longer flips classify to :mutate")
  (is (cls/denied? "(prn :System/exit)")
      "but the deny-list gate still catches it"))
