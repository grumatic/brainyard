;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: Apache-2.0
;; Licensed under the Apache License, Version 2.0 (the "License"); you may
;; not use this file except in compliance with the License. See LICENSE at
;; the repository root and CONTRIBUTING.md for contribution terms.

(ns ai.brainyard.agent-tui-tmux.questionnaire-test
  (:require [ai.brainyard.agent-tui-tmux.core.questionnaire :as q]
            [clojure.test :refer [deftest is testing]]))

(deftest validate-test
  (testing "valid one-tab radio"
    (is (q/validate {:id "x" :title "t"
                     :tabs [{:id :a :prompt "?" :type :radio
                             :options [{:value 1 :label "one"}]}]})))
  (testing "rejects empty tabs"
    (is (thrown? Exception (q/validate {:id "x" :title "t" :tabs []}))))
  (testing "rejects duplicate tab ids"
    (is (thrown? Exception
                 (q/validate {:id "x" :title "t"
                              :tabs [{:id :a :prompt "?" :type :text}
                                     {:id :a :prompt "?" :type :text}]}))))
  (testing "rejects unknown tab type"
    (is (thrown? Exception
                 (q/validate {:id "x" :title "t"
                              :tabs [{:id :a :prompt "?" :type :picker}]})))))

(deftest make-test
  (testing "auto-generates :id when absent and labels missing tab labels"
    (let [m (q/make {:title "x"
                     :tabs [{:id :note :prompt "?" :type :text}]})]
      (is (string? (:id m)))
      (is (= "Note" (-> m :tabs first :label))))))

(deftest single-tab-test
  (is (q/single-tab? (q/make {:title "x" :tabs [{:id :a :prompt "?" :type :text}]})))
  (is (not (q/single-tab? (q/make {:title "x"
                                   :tabs [{:id :a :prompt "?" :type :text}
                                          {:id :b :prompt "?" :type :text}]})))))

(deftest default-answers-test
  (testing "fills in tab :default values"
    (let [q (q/make {:title "x"
                     :tabs [{:id :strategy :prompt "?" :type :radio
                             :options [{:value :run :label "Run" :default? true}
                                       {:value :skip :label "Skip"}]}
                            {:id :note :prompt "?" :type :text :default "default note"}]})
          a (q/default-answers q)]
      (is (= :run (get-in a [:strategy :value])))
      (is (= "default note" (get-in a [:note :input]))))))

(deftest readiness-test
  (let [q (q/make {:title "x"
                   :tabs [{:id :a :prompt "?" :type :radio :required? true
                           :options [{:value 1 :label "1"}]}
                          {:id :b :prompt "?" :type :text}]})]
    (is (not (q/ready-to-submit? q {})))
    (is (not (q/ready-to-submit? q {:b {:input "anything"}}))) ; :a still missing
    (is (q/ready-to-submit? q {:a {:value 1}}))
    (is (q/ready-to-submit? q {:a {:value 1} :b {:input "x"}}))))

(deftest replies-test
  (let [q (q/make {:title "x" :tabs [{:id :a :prompt "?" :type :text}]})]
    (is (= :submitted (:status (q/submitted-reply q {:a {:input "hi"}}))))
    (is (= :cancelled (:status (q/cancelled-reply q))))
    (is (= :timeout   (:status (q/timeout-reply q))))))

(deftest permission-questionnaire-test
  (let [q (q/permission-questionnaire {:tool "bash.run" :path "/Users/.git/"})
        opts (-> q :tabs first :options)]
    (is (= "Permission required" (:title q)))
    (is (= 4 (count opts)))
    (is (= [:yes :no :always :never] (mapv :value opts)))
    (is (= :yes (q/permission-decision (q/submitted-reply q {:decision {:value :yes}}))))
    (is (= :cancel (q/permission-decision (q/cancelled-reply q))))))

(deftest confirm-questionnaire-test
  (let [q (q/confirm-questionnaire {:title "/quit" :prompt "Really quit?"})]
    (is (= "/quit" (:title q)))
    (is (= [:confirm :cancel] (mapv :value (-> q :tabs first :options))))))

(deftest feedback-questionnaire-test
  (let [q (q/feedback-questionnaire
           {:question "What next?"
            :options ["Restart" "Edit code" "Try different approach"]})]
    (is (= "Feedback" (:title q)))
    (is (= 3 (count (-> q :tabs first :options))))
    (is (= [0 1 2] (mapv :value (-> q :tabs first :options))))))
