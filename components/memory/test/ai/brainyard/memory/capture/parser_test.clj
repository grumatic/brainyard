;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.memory.capture.parser-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [ai.brainyard.memory.core.capture.parser :as parser]))

(defn- base-event [m]
  (merge {:session-id "s" :user-id "u" :event-id "e1"} m))

;; =====================================================
;; Conversation: one Q&A episode at agent.ask/post
;; =====================================================

(deftest ask-post-qa-episode-test
  (testing ":agent.ask/post folds question + answer into one Q&A episode"
    (let [out (parser/parse (base-event {:event-key :agent.ask/post
                                         :input "How do I deploy?"
                                         :result {:answer "Run bb build:ata"}}))]
      (is (= :episode (:kind out)))
      (is (str/includes? (:content out) "Q: How do I deploy?"))
      (is (str/includes? (:content out) "A: Run bb build:ata"))
      (is (contains? (:tags out) "kind:qa"))
      (is (contains? (:tags out) "role:conversation"))
      (testing "carries a content-addressable id for dedup"
        (is (str/starts-with? (:id out) "qa/s/"))))))

(deftest ask-post-string-result-test
  (testing ":agent.ask/post handles a plain string :result"
    (let [out (parser/parse (base-event {:event-key :agent.ask/post :input "x" :result "y"}))]
      (is (str/includes? (:content out) "Q: x"))
      (is (str/includes? (:content out) "A: y")))))

(deftest qa-id-dedup-test
  (testing "same normalized question ⇒ same id (upserts); different ⇒ different"
    (let [a (parser/parse (base-event {:event-key :agent.ask/post :input "How  do I DEPLOY?" :result "1"}))
          b (parser/parse (base-event {:event-key :agent.ask/post :input "how do i deploy?" :result "2"}))
          c (parser/parse (base-event {:event-key :agent.ask/post :input "something else"   :result "3"}))]
      (is (= (:id a) (:id b)) "case/whitespace-insensitive question collapses")
      (is (not= (:id a) (:id c))))))

;; =====================================================
;; Tool-use / code-eval: errors only
;; =====================================================

(deftest tool-success-skipped-test
  (testing "a successful tool call is NOT captured (errors only)"
    (is (nil? (parser/parse (base-event {:event-key :agent.tool-use/post
                                         :tool-name "bash" :args {:cmd "ls"}
                                         :result "deploy.sh\n"}))))))

(deftest tool-error-captured-test
  (testing "a tool error IS captured, tagged as an error"
    (let [out (parser/parse (base-event {:event-key :agent.tool-use/post
                                         :tool-name "bash" :args {}
                                         :result {:error "boom"}}))]
      (is (some? out))
      (is (str/includes? (:content out) "FAILED"))
      (is (str/includes? (:content out) "boom"))
      (is (contains? (:tags out) "outcome:error"))
      (is (contains? (:tags out) "kind:tool-error"))
      (is (contains? (:tags out) "tool:bash")))))

(deftest code-eval-success-skipped-test
  (testing "a successful eval is NOT captured"
    (is (nil? (parser/parse (base-event {:event-key :agent.code-eval/post
                                         :code "(+ 1 2)" :result 3 :output "" :error ""
                                         :duration-ms 12}))))))

(deftest code-eval-error-captured-test
  (testing "an eval error IS captured"
    (let [out (parser/parse (base-event {:event-key :agent.code-eval/post
                                         :code "(/ 1 0)" :error "Divide by zero"
                                         :duration-ms 5}))]
      (is (some? out))
      (is (str/includes? (:content out) "FAILED"))
      (is (str/includes? (:content out) "Divide by zero"))
      (is (contains? (:tags out) "kind:code-eval-error"))
      (is (contains? (:tags out) "outcome:error")))))

;; =====================================================
;; exception
;; =====================================================

(deftest exception-test
  (let [ex  (ex-info "boom" {:cause :test})
        out (parser/parse (base-event {:event-key :agent/exception :phase :ask :exception ex}))]
    (is (re-find #"exception in ask: boom" (:content out)))
    (is (contains? (:tags out) "outcome:error"))
    (is (contains? (:tags out) "phase:ask"))))

;; =====================================================
;; Cross-cutting fields
;; =====================================================

(deftest sources-and-ids-test
  (let [out (parser/parse (base-event {:event-key :agent.ask/post :input "x" :result "y"}))]
    (is (= "s" (:session-id out)))
    (is (= "u" (:user-id out)))
    (is (number? (:created-at out)))
    (is (= [{:type :agent.ask/post :id "e1"}] (:sources out)))))

(deftest unknown-event-fallback-test
  (let [out (parser/parse (base-event {:event-key :wat :stuff "ok"}))]
    (is (= :episode (:kind out)))
    (is (contains? (:tags out) "kind:unknown"))
    (is (contains? (:tags out) "event:wat"))))

;; =====================================================
;; Long-text truncation
;; =====================================================

(defn- last-codepoint-ok?
  "True when `s` does not end in a lone (dangling) UTF-16 surrogate — i.e. it
   is a well-formed string that will round-trip through UTF-8."
  [^String s]
  (or (zero? (count s))
      (not (Character/isHighSurrogate (.charAt s (dec (count s)))))))

(deftest qa-truncation-bounds-test
  (testing "long question/answer are bounded at the default caps (q 8000, a 16000)"
    (let [q   (apply str (repeat 10000 "Q"))
          a   (apply str (repeat 20000 "A"))
          out (parser/parse (base-event {:event-key :agent.ask/post :input q :result a}))
          c   (:content out)]
      ;; "Q: " + 8000 + "…" + "\nA: " + 16000 + "…" ≈ 24010 chars (raw was 30000).
      (is (< (count c) 24100) (str "content should be bounded, got " (count c)))
      (is (str/includes? c "Q…") "question half truncated with ellipsis")
      (is (str/includes? c "A…") "answer half truncated with ellipsis"))))

(deftest qa-truncation-honors-override-test
  (testing "the 2-arity limits override the default caps"
    (let [q   (apply str (repeat 500 "Q"))
          a   (apply str (repeat 500 "A"))
          out (parser/parse (base-event {:event-key :agent.ask/post :input q :result a})
                            {:question 100 :answer 200})]
      ;; "Q: " + 100 + "…" + "\nA: " + 200 + "…" ≈ 311 chars.
      (is (< (count (:content out)) 320))
      (is (str/includes? (:content out) "Q…"))
      (is (str/includes? (:content out) "A…")))))

(deftest truncation-is-surrogate-safe-test
  (testing "cutting mid-emoji never leaves a dangling high surrogate"
    ;; 😀 is one codepoint = a UTF-16 surrogate pair. Place one straddling each
    ;; default cut boundary (q@8000, a@16000) so a naive char-index cut splits it.
    (let [q   (str (apply str (repeat 7999 "a")) "😀" (apply str (repeat 200 "b")))
          a   (str (apply str (repeat 15999 "c")) "😀" (apply str (repeat 200 "d")))
          out (parser/parse (base-event {:event-key :agent.ask/post :input q :result a}))]
      (is (last-codepoint-ok? (subs (:content out) 0 (.indexOf ^String (:content out) "\nA:")))
          "no dangling surrogate at the question cut")
      ;; The whole stored content must be well-formed (round-trips to UTF-8).
      (is (= (:content out)
             (-> (:content out)
                 (.getBytes "UTF-8")
                 (String. "UTF-8")))
          "content round-trips through UTF-8 without replacement chars"))))
