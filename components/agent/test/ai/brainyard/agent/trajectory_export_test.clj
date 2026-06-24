;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.trajectory-export-test
  "Tests for trajectory export (R5b): the OpenAI tool-calling serializer,
   secret redaction, and the trajectory$export command (hermetic — trajectory
   reads stubbed, output to a temp path)."
  (:require [clojure.test :refer [deftest testing is]]
            [ai.brainyard.agent.common.trajectory-export :as tx]
            [ai.brainyard.agent.common.trajectory :as traj]
            [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]))

;; ============================================================================
;; Serializer
;; ============================================================================

(deftest tool-iteration->messages
  (let [msgs (tx/iteration->messages 1 {:n 1 :channel "tool" :thought "listing"
                                        :tools [{:name "bash" :args {:cmd "ls"} :result "deps.edn"}]})]
    (is (= 2 (count msgs)))
    (let [[asst tool] msgs]
      (is (= "assistant" (:role asst)))
      (is (= "listing" (:content asst)))
      (is (= 1 (count (:tool_calls asst))))
      (let [call (first (:tool_calls asst))]
        (is (= "bash" (-> call :function :name)))
        (is (= {:cmd "ls"} (json/read-str (-> call :function :arguments) :key-fn keyword)))
        (is (= "tool" (:role tool)))
        (is (= (:id call) (:tool_call_id tool)) "tool message links to the call id")
        (is (= "deps.edn" (:content tool)))))))

(deftest code-iteration->messages
  (let [msgs (tx/iteration->messages 1 {:n 2 :channel "code" :code ["(+ 1 2)"] :output ["3"] :result ["3"]})]
    (is (= 2 (count msgs)))
    (let [[asst tool] msgs]
      (is (= "code_eval" (-> asst :tool_calls first :function :name)))
      (is (= {:code "(+ 1 2)"} (json/read-str (-> asst :tool_calls first :function :arguments) :key-fn keyword)))
      (is (str/includes? (:content tool) "3")))))

(deftest reasoning-only-and-empty-iterations
  (is (= [{:role "assistant" :content "just thinking"}]
         (tx/iteration->messages 1 {:n 1 :channel "none" :thought "just thinking"})))
  (is (= [] (tx/iteration->messages 1 {:n 1 :channel "none"}))))

(deftest record->example-sequence
  (let [ex (tx/record->example
            {:turn 1 :question "deploy v2" :answer "Done."
             :iterations [{:n 1 :channel "tool" :tools [{:name "bash" :args {} :result "ok"}]}]})
        roles (mapv :role (:messages ex))]
    (is (= ["user" "assistant" "tool" "assistant"] roles))
    (is (= "deploy v2" (-> ex :messages first :content)))
    (is (= "Done." (-> ex :messages last :content))))
  (testing "a turn with no question/answer/iterations yields nil"
    (is (nil? (tx/record->example {:turn 9 :iterations []})))))

;; ============================================================================
;; Redaction
;; ============================================================================

(deftest redaction
  (testing "secret-like tokens are scrubbed"
    (is (= "[REDACTED]" (tx/redact-str "gck_15705dabcdef1234567890")))
    (is (= "[REDACTED]" (tx/redact-str "sk-ABCDEFGHIJKLMNOP1234")))
    (is (str/includes? (tx/redact-str "x Bearer abcdef12345678 y") "[REDACTED]"))
    (is (= "no secrets here" (tx/redact-str "no secrets here"))))
  (testing "redact-example walks nested string leaves"
    (let [ex {:messages [{:role "assistant" :content "token sk-ABCDEFGHIJKLMNOP1234"}]}
          red (tx/redact-example ex)]
      (is (str/includes? (-> red :messages first :content) "[REDACTED]"))
      (is (not (str/includes? (-> red :messages first :content) "sk-A"))))))

;; ============================================================================
;; trajectory$export command (hermetic)
;; ============================================================================

(def canned
  [{:turn 1 :question "deploy v2" :answer "Done."
    :iterations [{:n 1 :channel "tool" :thought "list" :tools [{:name "bash" :args {:cmd "ls"} :result "deps.edn"}]}
                 {:n 2 :channel "code" :code ["(+ 1 2)"] :output ["3"]}]}
   {:turn 2 :question "show key" :answer "key is sk-ABCDEFGHIJKLMNOP1234" :iterations []}])

(deftest export-command-openai-jsonl
  (let [out (str (io/file (System/getProperty "java.io.tmpdir")
                          (str "tx-test-" (System/currentTimeMillis) ".jsonl")))]
    (try
      (with-redefs [traj/read-trajectories (fn [_sid] canned)]
        (let [res (tx/trajectory$export :session-id "s1" :out out :format "openai-jsonl")]
          (is (nil? (:error res)))
          (is (= "openai-jsonl" (:format res)))
          (is (= 2 (:turns res)))
          (is (= 2 (:examples res)))
          (is (true? (:redacted res)))
          (let [lines (->> (slurp out) str/split-lines (remove str/blank?) vec)
                ex1 (json/read-str (first lines) :key-fn keyword)]
            (is (= 2 (count lines)))
            (is (= ["user" "assistant" "tool" "assistant" "tool" "assistant"]
                   (mapv :role (:messages ex1))))
            (testing "redaction scrubbed the secret in turn 2"
              (is (str/includes? (second lines) "[REDACTED]"))
              (is (not (str/includes? (second lines) "sk-A")))))))
      (finally (io/delete-file out true)))))

(deftest export-command-edn-and-guards
  (testing "edn format emits one raw record per line"
    (let [out (str (io/file (System/getProperty "java.io.tmpdir")
                            (str "tx-edn-" (System/currentTimeMillis) ".edn")))]
      (try
        (with-redefs [traj/read-trajectories (fn [_sid] canned)]
          (let [res (tx/trajectory$export :session-id "s1" :out out :format "edn" :redact false)]
            (is (= "edn" (:format res)))
            (is (= 2 (count (->> (slurp out) str/split-lines (remove str/blank?)))))))
        (finally (io/delete-file out true)))))
  (testing "unknown format / no session are rejected"
    (with-redefs [traj/read-trajectories (fn [_sid] canned)]
      (is (:error (tx/trajectory$export :session-id "s1" :format "bogus"))))
    (is (:error (tx/trajectory$export)))))
