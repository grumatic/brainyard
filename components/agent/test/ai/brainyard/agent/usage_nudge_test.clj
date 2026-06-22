;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.usage-nudge-test
  (:require [clojure.test :refer [deftest testing is]]
            [ai.brainyard.agent.common.usage-nudge :as un]
            [ai.brainyard.agent.core.protocol :as proto]
            [ai.brainyard.agent.core.hooks :as hooks]
            [clojure.string :as str]))

(defn- mock-agent
  "Agent exposing the two stores usage-nudge touches: the cross-turn registry
   (st-memory-init) and the per-turn bt st-memory."
  [init-atom bt-atom]
  (reify
    proto/IAgent
    (agent-id [_] :mock)
    (agent-name [_] "mock")
    (agent-description [_] "mock")
    (user-id [_] "u")
    (session-id [_] "s")
    (defagent-type [_] :mock)
    (process [_ _ _] nil)
    (get-tools [_] nil)
    (get-state [_] {})
    proto/IAgentState
    (get-st-memory-init [_] init-atom)
    proto/IAgentBTIntegration
    (get-bt-st-memory [_] bt-atom)))

(deftest topic-for-tool-test
  (testing "family segment before $ maps to the usage topic"
    (is (= :artifacts (un/topic-for-tool "artifact$add")))
    (is (= :memory    (un/topic-for-tool "memory$recall")))
    (is (= :plans     (un/topic-for-tool "plan$dossier-write")))
    (is (= :llm-query (un/topic-for-tool "query$llm")))
    (is (= :skills    (un/topic-for-tool "skills$find")))
    (is (= :todo      (un/topic-for-tool "todo$read-dossier")))
    (is (= :mcp       (un/topic-for-tool "mcp$server")))
    (is (= :mcp       (un/topic-for-tool "mcp$fetch$get"))))
  (testing "keyword/symbol tool-names resolve too"
    (is (= :artifacts (un/topic-for-tool :artifact$list)))
    (is (= :memory    (un/topic-for-tool 'memory$remember))))
  (testing "unguided / nil tools return nil"
    (is (nil? (un/topic-for-tool "read-file")))
    (is (nil? (un/topic-for-tool "bash")))
    (is (nil? (un/topic-for-tool nil)))))

(deftest first-use-queues-once-test
  (testing "first use of a family queues a pending guide and marks the topic shown"
    (let [init (atom {}) bt (atom {})
          ag   (mock-agent init bt)]
      (un/note-tool-use! ag "artifact$add" {:result "ok"})
      (is (= #{:artifacts} (:usage-tips-shown @init)))
      (let [pending (:pending-usage-guides @bt)]
        (is (= 1 (count pending)))
        (is (= {:topic :artifacts :reason :first-use :tool "artifact$add"}
               (first pending))))))
  (testing "a second use of the same family is a no-op (once per session)"
    (let [init (atom {}) bt (atom {})
          ag   (mock-agent init bt)]
      (un/note-tool-use! ag "artifact$add" {:result "ok"})
      (un/note-tool-use! ag "artifact$remove" {:result "ok"})
      (is (= 1 (count (:pending-usage-guides @bt))))))
  (testing "unguided tools never queue"
    (let [init (atom {}) bt (atom {})
          ag   (mock-agent init bt)]
      (un/note-tool-use! ag "read-file" {:content "x"})
      (is (empty? (:usage-tips-shown @init)))
      (is (empty? (:pending-usage-guides @bt))))))

(deftest error-reason-test
  (testing "a failing first call is queued with :reason :error"
    (let [init (atom {}) bt (atom {})
          ag   (mock-agent init bt)]
      (un/note-tool-use! ag "artifact$add" {:error "provide :path or :content"})
      (is (= :error (:reason (first (:pending-usage-guides @bt)))))))
  (testing ":error-message shape also counts as a failure"
    (let [init (atom {}) bt (atom {})
          ag   (mock-agent init bt)]
      (un/note-tool-use! ag "memory$recall" {:error-message "boom"})
      (is (= :error (:reason (first (:pending-usage-guides @bt))))))))

(deftest inlined-topics-suppress-jit-test
  (testing "a permanently-inlined topic is never re-surfaced by first-use"
    (let [init (atom {}) bt (atom {})
          ag   (mock-agent init bt)]
      (un/seed-inlined-topics! ag [:artifacts])
      (is (= #{:artifacts} (:usage-inlined-topics @init)))
      (un/note-tool-use! ag "artifact$add" {:result "ok"})
      (is (empty? (:pending-usage-guides @bt)) "inlined topic stays off the JIT path")
      ;; a different, non-inlined family still fires
      (un/note-tool-use! ag "memory$recall" {:result "ok"})
      (is (= [:memory] (mapv :topic (:pending-usage-guides @bt)))))))

(deftest drain-renders-and-clears-test
  (testing "drain returns rendered guide text and empties the queue"
    (let [bt (atom {:pending-usage-guides
                    [{:topic :artifacts :reason :first-use :tool "artifact$add"}]})
          out (un/drain-iteration-notices! bt)]
      (is (string? out))
      (is (str/includes? out "artifact$add"))
      (is (str/includes? out "Live Artifacts"))      ; from the :artifacts guide body
      (is (str/includes? out "(usage :artifacts)"))
      (is (nil? (:pending-usage-guides @bt)))))
  (testing "drain on an empty queue returns nil and is harmless"
    (is (nil? (un/drain-iteration-notices! (atom {}))))
    (is (nil? (un/drain-iteration-notices! nil)))))

(deftest rejected-hook-wiring-test
  (testing "ensure-global-hooks! registers usage-nudge on post AND rejected events"
    (un/ensure-global-hooks!)               ; idempotent; may already be installed
    (is (some #(= :usage-nudge (:source %)) (hooks/list-hooks :agent.tool-use/post)))
    (is (some #(= :usage-nudge (:source %)) (hooks/list-hooks :agent.tool-use/rejected))))
  (testing "firing :agent.tool-use/rejected queues the family guide with :reason :error"
    ;; A malformed first call to a real family (arg-validation reject) never
    ;; reaches :agent.tool-use/post, so the rejected event is what surfaces it.
    (let [init (atom {}) bt (atom {})
          ag   (mock-agent init bt)
          src  ::test-rejected]
      (try
        (hooks/register-hook! :agent.tool-use/rejected ::test-handler
                              (fn [{:keys [agent tool-name result]}]
                                (un/note-tool-use! agent tool-name result))
                              :source src)
        (hooks/fire! :agent.tool-use/rejected
                     {:agent ag :tool-name "plan$read-dossier"
                      :result {:error-message "Invalid tool args for plan$read-dossier"}
                      :reason :invalid-args})
        (is (= #{:plans} (:usage-tips-shown @init)))
        (is (= [{:topic :plans :reason :error :tool "plan$read-dossier"}]
               (:pending-usage-guides @bt)))
        (finally (hooks/unregister-source! src))))))

(deftest inline-guides-overlay-test
  (testing "overlay renders the full guide text for given topics"
    (let [s (un/inline-guides-overlay [:artifacts])]
      (is (string? s))
      (is (str/includes? s "always in context"))
      (is (str/includes? s "Live Artifacts"))))
  (testing "empty / unknown topics yield nil"
    (is (nil? (un/inline-guides-overlay [])))
    (is (nil? (un/inline-guides-overlay [:no-such-topic])))))
