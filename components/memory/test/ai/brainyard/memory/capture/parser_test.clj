;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: Apache-2.0
;; Licensed under the Apache License, Version 2.0 (the "License"); you may
;; not use this file except in compliance with the License. See LICENSE at
;; the repository root and CONTRIBUTING.md for contribution terms.

(ns ai.brainyard.memory.capture.parser-test
  (:require [clojure.test :refer [deftest is testing]]
            [ai.brainyard.memory.core.capture.parser :as parser]))

(defn- base-event [m]
  (merge {:session-id "s" :user-id "u" :event-id "e1"} m))

;; =====================================================
;; agent.ask/pre, agent.ask/post
;; =====================================================

(deftest ask-pre-test
  (testing ":agent.ask/pre becomes a user-message episode"
    (let [out (parser/parse (base-event {:event-key :agent.ask/pre
                                         :input "How do I deploy?"}))]
      (is (= :episode (:kind out)))
      (is (= "How do I deploy?" (:content out)))
      (is (contains? (:tags out) "role:user"))
      (is (contains? (:tags out) "kind:user-message"))
      (is (= [{:type :agent.ask/pre :id "e1"}] (:sources out))))))

(deftest ask-post-test
  (testing ":agent.ask/post extracts :answer/result/output from result map"
    (let [out (parser/parse (base-event {:event-key :agent.ask/post
                                         :input "Hello"
                                         :result {:answer "Hi back"}}))]
      (is (= :episode (:kind out)))
      (is (= "Hi back" (:content out)))
      (is (contains? (:tags out) "role:assistant"))
      (is (contains? (:tags out) "kind:assistant-answer")))))

(deftest ask-post-string-result-test
  (testing ":agent.ask/post handles plain string :result"
    (let [out (parser/parse (base-event {:event-key :agent.ask/post
                                         :input "x" :result "y"}))]
      (is (= "y" (:content out))))))

;; =====================================================
;; agent.tool-use/post
;; =====================================================

(deftest tool-post-test
  (let [out (parser/parse (base-event {:event-key :agent.tool-use/post
                                       :tool-name "bash"
                                       :args {:cmd "ls"}
                                       :result "deploy.sh\n"}))]
    (is (= :episode (:kind out)))
    (is (re-find #"tool=bash" (:content out)))
    (is (contains? (:tags out) "tool:bash"))
    (is (contains? (:tags out) "kind:tool-result"))
    (is (= "bash" (-> out :data :tool-name)))))

(deftest tool-post-error-outcome-test
  (testing "tool result containing :error tags as outcome:error"
    (let [out (parser/parse (base-event {:event-key :agent.tool-use/post
                                         :tool-name "bash"
                                         :args {}
                                         :result {:error "boom"}}))]
      (is (contains? (:tags out) "outcome:error")))))

;; =====================================================
;; code-eval
;; =====================================================

(deftest code-eval-test
  (let [out (parser/parse (base-event {:event-key :agent.code-eval/post
                                       :code "(+ 1 2)"
                                       :result 3
                                       :output ""
                                       :error ""
                                       :duration-ms 12}))]
    (is (re-find #"\[12ms\]" (:content out)))
    (is (contains? (:tags out) "kind:code-eval"))
    (is (= "(+ 1 2)" (-> out :data :code)))))

;; =====================================================
;; exception
;; =====================================================

(deftest exception-test
  (let [ex  (ex-info "boom" {:cause :test})
        out (parser/parse (base-event {:event-key :agent/exception
                                       :phase :ask
                                       :exception ex}))]
    (is (re-find #"exception in ask: boom" (:content out)))
    (is (contains? (:tags out) "outcome:error"))
    (is (contains? (:tags out) "phase:ask"))))

;; The knowledge-section-set hook + parser branch was removed in the
;; L1 simplification refactor. System context is now operator-only and
;; not reflected in the capture pipeline.

;; =====================================================
;; Cross-cutting fields
;; =====================================================

(deftest sources-and-ids-test
  (let [out (parser/parse (base-event {:event-key :agent.ask/pre :input "x"}))]
    (is (= "s" (:session-id out)))
    (is (= "u" (:user-id out)))
    (is (number? (:created-at out)))
    (is (= [{:type :agent.ask/pre :id "e1"}] (:sources out)))))

(deftest unknown-event-fallback-test
  (let [out (parser/parse (base-event {:event-key :wat :stuff "ok"}))]
    (is (= :episode (:kind out)))
    (is (contains? (:tags out) "kind:unknown"))
    (is (contains? (:tags out) "event:wat"))))
