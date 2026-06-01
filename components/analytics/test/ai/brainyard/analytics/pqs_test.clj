;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: Apache-2.0
;; Licensed under the Apache License, Version 2.0 (the "License"); you may
;; not use this file except in compliance with the License. See LICENSE at
;; the repository root and CONTRIBUTING.md for contribution terms.

(ns ai.brainyard.analytics.pqs-test
  (:require [clojure.test :refer [deftest testing is are]]
            [ai.brainyard.analytics.core.pqs :as pqs]))

;; ============================================================================
;; Dimension Scorer Tests
;; ============================================================================

(deftest test-score-specificity
  (testing "High specificity — file paths, function names, values"
    (let [score (pqs/score-specificity
                 "In /src/services/payment.ts, the processPayment() function throws TypeError when amount is null. Expected: return 400 status code.")]
      (is (>= score 15) "Should score high with file path + function + value")))

  (testing "Low specificity — vague prompt"
    (let [score (pqs/score-specificity "Fix the thing that's broken somehow")]
      (is (<= score 5) "Should score low with vague words")))

  (testing "Medium specificity — some concrete details"
    (let [score (pqs/score-specificity "Update the login page to show an error message")]
      (is (and (>= score 0) (<= score 20)) "Should score medium")))

  (testing "Very short prompt penalized"
    (let [score (pqs/score-specificity "Fix it")]
      (is (<= score 3) "Very short and vague"))))

(deftest test-score-task-atomicity
  (testing "Single task — high score"
    (let [score (pqs/score-task-atomicity "Add a null check to the processPayment function")]
      (is (>= score 20) "Single clear task")))

  (testing "Multi-task — low score"
    (let [score (pqs/score-task-atomicity
                 "Add a button to the header and also refactor the database schema, then update the docs and fix the tests")]
      (is (<= score 15) "Multiple tasks bundled")))

  (testing "Many bullet items penalized"
    (let [score (pqs/score-task-atomicity
                 "Please do the following:\n- Fix login\n- Update dashboard\n- Refactor auth\n- Add tests\n- Update docs")]
      (is (<= score 22) "Too many bullet items"))))

(deftest test-score-context-completeness
  (testing "Rich context — code + error + file"
    (let [score (pqs/score-context-completeness
                 "The error in /src/auth.ts:\n```typescript\nconst user = await getUser(id);\n```\nThrows: TypeError: Cannot read property 'name' of undefined")]
      (is (>= score 15) "Code block + error + file path")))

  (testing "No context"
    (let [score (pqs/score-context-completeness "Make it work better")]
      (is (= score 0) "No context provided"))))

(deftest test-score-acceptance-criteria
  (testing "Clear criteria"
    (let [score (pqs/score-acceptance-criteria
                 "The function should return a JSON object with {id, name, email}. Verify with the existing test suite.")]
      (is (>= score 10) "Has output format + test criteria")))

  (testing "No criteria"
    (let [score (pqs/score-acceptance-criteria "Change the colors")]
      (is (<= score 5) "No acceptance criteria"))))

(deftest test-score-clarity
  (testing "Clear, concise prompt"
    (let [score (pqs/score-clarity "Add a null check before accessing user.name")]
      (is (>= score 8) "Short, clear, active voice")))

  (testing "Unclear prompt with double negative"
    (let [score (pqs/score-clarity
                 "The system should not be unable to handle the cases where the input is not invalid and the output has been processed by the handler that was not removed")]
      (is (<= score 8) "Long passive sentence with double negative"))))

;; ============================================================================
;; Score Prompt Integration
;; ============================================================================

(deftest test-score-prompt
  (testing "Good prompt scores high across dimensions"
    (let [result (pqs/score-prompt
                  "In /src/services/payment.ts, the processPayment() function throws TypeError when amount is null. Add a null check and return {success: false, error: 'invalid_amount'} for null or negative values. Verify with: npm test")]
      (is (map? result))
      (is (contains? result :total))
      (is (>= (:total result) 40) "Good prompt should score well")))

  (testing "Bad prompt scores low"
    (let [result (pqs/score-prompt "Fix the thing")]
      (is (<= (:total result) 40) "Vague prompt should score poorly"))))

;; ============================================================================
;; Outcome Adjustments
;; ============================================================================

(deftest test-compute-adjustments
  (testing "Correction turn detected"
    (let [messages [{:role "user" :content "Fix the payment service"}
                    {:role "assistant" :content "I've updated the user service..."}
                    {:role "user" :content "No, I meant the payment service, not user service"}
                    {:role "assistant" :content "Fixed the payment service."}]
          adj (pqs/compute-adjustments messages)]
      (is (neg? (:correction-turns adj)) "Correction turn should subtract points")))

  (testing "Clean conversation — no corrections"
    (let [messages [{:role "user" :content "Add a button to the header"}
                    {:role "assistant" :content "Done, added the button."}
                    {:role "user" :content "Now change the footer color to blue"}
                    {:role "assistant" :content "Updated footer color."}]
          adj (pqs/compute-adjustments messages)]
      (is (>= (:one-turn-completions adj) 0) "Clean conversation should have positive or zero adjustment")))

  (testing "Single message — no adjustments"
    (let [messages [{:role "user" :content "Hello"}
                    {:role "assistant" :content "Hi there"}]
          adj (pqs/compute-adjustments messages)]
      (is (= 0 (:total adj)) "Single exchange has no adjustments"))))

;; ============================================================================
;; Full PQS Heuristic
;; ============================================================================

(deftest test-score-pqs-heuristic
  (testing "Returns all expected keys"
    (let [result (pqs/score-pqs-heuristic
                  [{:role "user" :content "Fix the bug in /src/auth.ts"}
                   {:role "assistant" :content "Fixed."}])]
      (is (contains? result :overall-score))
      (is (contains? result :dimensions))
      (is (contains? result :adjustments))
      (is (contains? result :per-prompt))
      (is (<= 0 (:overall-score result) 100))))

  (testing "Empty messages returns zero"
    (let [result (pqs/score-pqs-heuristic [])]
      (is (= 0 (:overall-score result)))))

  (testing "High quality session scores higher than low quality"
    (let [good (pqs/score-pqs-heuristic
                [{:role "user" :content "In /src/services/payment.ts, processPayment() throws TypeError when amount is null. Add a null check that returns {success: false, error: 'invalid_amount'}."}
                 {:role "assistant" :content "Done."}])
          bad (pqs/score-pqs-heuristic
               [{:role "user" :content "Fix the thing"}
                {:role "assistant" :content "What thing?"}
                {:role "user" :content "The thing that's broken"}
                {:role "assistant" :content "Done."}])]
      (is (> (:overall-score good) (:overall-score bad))
          "Good prompts should score higher"))))

;; ============================================================================
;; Main Entry Point
;; ============================================================================

(deftest test-score-pqs
  (testing "score-pqs without LLM returns heuristic + empty recommendations"
    (let [result (pqs/score-pqs
                  [{:role "user" :content "Add logging to the auth module"}
                   {:role "assistant" :content "Done."}])]
      (is (contains? result :overall-score))
      (is (contains? result :recommendations))
      (is (vector? (:recommendations result))))))
