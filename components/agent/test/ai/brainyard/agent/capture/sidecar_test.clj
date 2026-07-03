;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.capture.sidecar-test
  "End-to-end tests for the capture pipeline: dispatcher + sidecar +
  parser writing to L2 via the unified store.

  Capture policy (see memory/core/capture/parser.clj): the conversation is
  one Q&A episode at `:agent.ask/post` (the bare `:agent.ask/pre` question is
  NOT captured); tool-use / code-eval are captured only on error."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.string :as str]
            [ai.brainyard.memory.interface :as mem]
            [ai.brainyard.memory.interface.protocol :as proto]
            [ai.brainyard.agent.core.hooks :as hooks]))

(def ^:dynamic *mm* nil)

(use-fixtures :each
  (fn [f]
    (hooks/reset-hooks!)
    (let [mm (mem/create-memory-manager (str "u-" (random-uuid)) :in-memory true)]
      (try
        (binding [*mm* mm]
          (f))
        (finally
          (when (mem/capture-running? mm)
            (mem/stop-capture! mm))
          (.close (:ds mm)))))
    (hooks/reset-hooks!)))

(defn- l2-entries
  ([sid] (l2-entries sid 100))
  ([sid limit]
   (proto/read-entries (mem/store *mm*) :l2
                       {:session-id sid}
                       {:limit limit})))

(defn- await-count
  "Spin until L2 for `sid` reaches at least `n` entries or `timeout-ms`
  elapses. Returns the final count."
  [sid n timeout-ms]
  (let [end (+ (System/currentTimeMillis) timeout-ms)]
    (loop []
      (let [c (count (l2-entries sid))]
        (if (or (>= c n) (> (System/currentTimeMillis) end))
          c
          (do (Thread/sleep 20) (recur)))))))

;; =====================================================
;; End-to-end capture
;; =====================================================

(deftest captures-qa-at-ask-post-test
  (testing "the turn's question+answer are captured as ONE Q&A episode at post"
    (mem/start-capture! *mm*)
    (hooks/fire! :agent.ask/post {:session-id "s1" :user-id "u"
                                  :input "Deploy how?" :result "Run bb build:ata"})
    (is (= 1 (await-count "s1" 1 1000)))
    (let [[e] (l2-entries "s1")]
      (is (str/includes? (:content e) "Q: Deploy how?"))
      (is (str/includes? (:content e) "A: Run bb build:ata"))
      (is (contains? (:tags e) "kind:qa"))
      (is (= 1 (count (:sources e)))))))

(deftest capture-quiesce-flushes-pending-writes-test
  (testing "capture-quiesce! blocks until in-flight L2 writes are committed —
            the barrier that lets at-consolidation batch extraction see the
            just-captured turn (asserted WITHOUT polling)"
    (mem/start-capture! *mm*)
    (hooks/fire! :agent.ask/post {:session-id "sq" :user-id "u" :input "q" :result "a"})
    (is (true? (mem/capture-quiesce! *mm* 5000)) "quiesce drains within timeout")
    (is (= 1 (count (l2-entries "sq"))) "write visible immediately after quiesce")))

(deftest ask-pre-not-captured-test
  (testing "the bare question (ask/pre) is NOT written — only ask/post is"
    (mem/start-capture! *mm*)
    (hooks/fire! :agent.ask/pre {:session-id "sp" :user-id "u" :input "just the question"})
    (Thread/sleep 150)
    (is (zero? (count (l2-entries "sp"))))))

(deftest tool-eval-exception-events-not-captured-test
  (testing "tool-use / code-eval / agent-exception are NOT captured — L2 keeps
            only the Q&A episode (errors are operational, live in logs)"
    (mem/start-capture! *mm*)
    ;; None of these should produce an L2 episode anymore (not subscribed):
    (hooks/fire! :agent.tool-use/post {:session-id "s2" :user-id "u"
                                       :tool-name "bash" :args {:cmd "boom"} :result {:error "exit 1"}})
    (hooks/fire! :agent.code-eval/post {:session-id "s2" :user-id "u"
                                        :code "(/ 1 0)" :result nil :error "divide by zero"})
    (hooks/fire! :agent/exception {:session-id "s2" :user-id "u"
                                   :exception (ex-info "boom" {})})
    ;; Only the Q&A is captured:
    (hooks/fire! :agent.ask/post {:session-id "s2" :user-id "u" :input "Q" :result "A"})
    (is (= 1 (await-count "s2" 1 1000)))
    (let [tags (set (mapcat :tags (l2-entries "s2")))]
      (is (contains? tags "kind:qa"))
      (is (not (contains? tags "kind:tool-error")) "tool errors no longer captured")
      (is (not (contains? tags "kind:tool-result"))))))

(deftest match-predicate-scopes-capture-test
  (testing ":match passed to start-capture! filters writes end-to-end — the
            mechanism behind root-only capture (subagent ask/post is dropped)"
    ;; Stand in for root-agent-capture-event? with a simple flag on the event.
    (mem/start-capture! *mm* :match (fn [m] (:keep? m)))
    (hooks/fire! :agent.ask/post {:session-id "sm" :user-id "u"
                                  :input "root turn" :result "a" :keep? true})
    (hooks/fire! :agent.ask/post {:session-id "sm" :user-id "u"
                                  :input "subagent turn" :result "b" :keep? false})
    (is (= 1 (await-count "sm" 1 1000)))
    (is (str/includes? (:content (first (l2-entries "sm"))) "root turn"))
    (is (not (str/includes? (:content (first (l2-entries "sm"))) "subagent turn")))))

(deftest qa-upsert-dedup-test
  (testing "re-asking the same question upserts the Q&A episode (no dup)"
    (mem/start-capture! *mm*)
    (hooks/fire! :agent.ask/post {:session-id "s3" :user-id "u" :input "same q" :result "first"})
    (is (= 1 (await-count "s3" 1 1000)))
    (hooks/fire! :agent.ask/post {:session-id "s3" :user-id "u" :input "SAME  Q" :result "second"})
    (Thread/sleep 200)
    (is (= 1 (count (l2-entries "s3"))) "same normalized question deduped to one episode")
    (is (str/includes? (:content (first (l2-entries "s3"))) "A: second") "latest answer kept")))

(deftest capture-disabled-by-default-test
  ;; No start-capture! call — verify no entries are written.
  (hooks/fire! :agent.ask/post {:session-id "s5" :user-id "u" :input "Quiet" :result "x"})
  (Thread/sleep 100)
  (is (zero? (count (l2-entries "s5")))))

(deftest start-capture-idempotent-test
  (let [h1 (mem/start-capture! *mm*)
        h2 (mem/start-capture! *mm*)]
    (is (identical? h1 h2)
        "Calling start-capture! twice returns the same handle")))

(deftest stop-capture-tears-down-hooks-test
  (mem/start-capture! *mm*)
  (mem/stop-capture! *mm*)
  (is (not (mem/capture-running? *mm*)))
  ;; After stop, firing the captured event must not produce an entry
  (hooks/fire! :agent.ask/post {:session-id "s6" :user-id "u" :input "post-stop" :result "x"})
  (Thread/sleep 100)
  (is (zero? (count (l2-entries "s6")))))
