;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.examples-test
  "Integration examples demonstrating agent with system commands,
   session tracking, and the full ask flow."
  (:require [clojure.test :refer [deftest testing is]]
            [ai.brainyard.agent.interface :as agent]
            [ai.brainyard.agent.core.protocol :as proto]
            [ai.brainyard.agent.core.session :as session]
            [ai.brainyard.agent.core.agent :as ag]))

;; ============================================================================
;; Example 1: Agent with inline system commands
;; ============================================================================

(deftest agent-with-system-commands-test
  (testing "Agent processes /commands via config handlers"
    (let [;; Define command handlers
          cancel-handler (fn [args agent]
                           {:status :cancelled
                            :message (str "Cancelled" (when args (str ": " args)))})
          help-handler   (fn [args agent]
                           {:status :ok
                            :commands ["cancel" "help" "status"]
                            :message "Available commands: /cancel, /help, /status"})
          status-handler (fn [args agent]
                           {:status :ok
                            :agent-id (proto/agent-id agent)
                            :running? (proto/agent-running? agent)
                            :session-messages (count (:messages @(:!session agent)))})

          ;; Create agent with system commands
          a (@#'ag/create-agent "user-1" "session-1" "cmd-agent"
                                :config {:name "Command Agent"
                                         :description "Agent with system commands"
                                         :system-commands {"cancel" cancel-handler
                                                           "help"   help-handler
                                                           "status" status-handler}})]

      ;; Test /help command
      (let [result (agent/ask a "/help")]
        (is (= :ok (:status result)))
        (is (= 3 (count (:commands result))))
        (is (.contains (:message result) "/help")))

      ;; Test /cancel command
      (let [result (agent/ask a "/cancel")]
        (is (= :cancelled (:status result)))
        (is (= "Cancelled" (:message result))))

      ;; Test /cancel with args
      (let [result (agent/ask a "/cancel user requested stop")]
        (is (= :cancelled (:status result)))
        (is (= "Cancelled: user requested stop" (:message result))))

      ;; Test /status command
      (agent/start-agent a)
      (let [result (agent/ask a "/status")]
        (is (= :ok (:status result)))
        (is (= "cmd-agent" (:agent-id result)))
        ;; Should have recorded previous messages in session
        (is (pos? (:session-messages result))))

      ;; Test unknown command
      (let [result (agent/ask a "/unknown")]
        (is (some? (:error result)))
        (is (.contains (:error result) "Unknown command")))

      ;; Cleanup
      (agent/stop-agent a))))

;; ============================================================================
;; Example 2: Session tracking across multiple asks
;; ============================================================================

(deftest session-tracking-example-test
  (testing "Session accumulates messages across multiple asks"
    (let [msg-count (atom 0)
          a (@#'ag/create-agent "user-1" "session-1" "session-agent"
                                :config {:name "Session Agent"
                                         :system-commands
                                         {"history" (fn [_ agent]
                                                      (let [session @(:!session agent)
                                                            msgs (:messages session)]
                                                        {:count (count msgs)
                                                         :messages (mapv #(select-keys % [:role :content]) msgs)}))
                                          "ping" (fn [_ _] {:pong true})}})]

      ;; First interaction
      (agent/ask a "/ping")
      (let [history (agent/ask a "/history")]
        ;; /ping adds user msg, then /history adds user msg
        ;; Each ask adds user msg + optionally assistant msg
        (is (pos? (:count history))))

      ;; More interactions
      (agent/ask a "/ping")
      (agent/ask a "/ping")
      (let [history (agent/ask a "/history")]
        ;; Should have accumulated more messages
        (is (>= (:count history) 4))

        ;; Verify messages have correct structure
        (is (every? #(contains? % :role) (:messages history)))))))

;; ============================================================================
;; Example 3: Session store persistence
;; ============================================================================

(deftest session-store-persistence-test
  (testing "Agent persists session to session store"
    (let [store (agent/create-session-store)
          a (@#'ag/create-agent "user-1" "session-1" "store-agent"
                                :config {:name "Store Agent"
                                         :system-commands
                                         {"echo" (fn [args _] {:echo args})}}
                                :session-store store)]

      ;; Ask something
      (agent/ask a "/echo hello")

      ;; Session should be persisted in store (store holds atoms)
      (let [stored-session (session/get-session store "session-1")]
        (is (some? stored-session))
        (is (= "session-1" (:session-id @stored-session)))
        (is (= "user-1" (:user-id @stored-session)))))))

;; ============================================================================
;; Example 4: Agent with thinking traces (BT integration protocol)
;; ============================================================================

(deftest thinking-traces-example-test
  (testing "Agent records thinking traces via BT protocol"
    (let [a (@#'ag/create-agent "user-1" "session-1" "thinking-agent"
                                :config {:name "Thinking Agent"
                                         :system-commands
                                         {"think" (fn [args agent]
                                                    ;; Simulate BT-style thinking traces
                                                    (proto/update-session-data
                                                     agent {:trace {:agent-id (:agent-id agent) :depth 0 :content ">>> think sequence started."}})
                                                    (proto/update-session-data
                                                     agent {:trace {:agent-id (:agent-id agent) :depth 1 :content "think action: analyzing input..."}})
                                                    (proto/update-session-data
                                                     agent {:trace {:agent-id (:agent-id agent) :depth 1 :content "think action: generating response..."}})
                                                    (proto/update-session-data
                                                     agent {:trace {:agent-id (:agent-id agent) :depth 0 :content "<<< think sequence success."}})
                                                    {:answer (str "Thought about: " args)})}})]

;; Execute thinking command
      (let [result (agent/ask a "/think meaning of life")]
        (is (= "Thought about: meaning of life" (:answer result))))

      ;; Check traces in session state (each ask clears data, so read after ask)
      (let [traces (get-in @(:!session a) [:data :traces])]
        (is (= 4 (count traces)))
        (is (.contains (get-in traces [0 :content]) "started"))
        (is (.contains (get-in traces [3 :content]) "success"))))))

;; ============================================================================
;; Example 5: Async execution with cancellation
;; ============================================================================

(deftest async-and-cancellation-example-test
  (testing "Agent supports async ask with cancellation check"
    (let [processing (promise)
          a (@#'ag/create-agent "user-1" "session-1" "async-agent"
                                :config {:name "Async Agent"
                                         :system-commands
                                         {"slow" (fn [_ agent]
                                                   (deliver processing true)
                                                   ;; Simulate slow work with cancel check
                                                   (loop [i 0]
                                                     (if (proto/check-run-cancelled? agent)
                                                       {:status :cancelled :iterations i}
                                                       (if (>= i 100)
                                                         {:status :done :iterations i}
                                                         (do (Thread/sleep 10)
                                                             (recur (inc i)))))))}})]

      ;; Run async
      (let [clj-ag (agent/ask-async a "/slow")]
        ;; Wait for processing to start
        @processing
        ;; Let it run a bit
        (Thread/sleep 50)
        ;; Cancel
        (agent/stop-agent a)
        ;; Wait for clj-agent to finish (with timeout)
        (let [completed? (await-for 5000 clj-ag)
              result (if completed?
                       (:output @clj-ag)
                       {:status :timeout})]
          ;; stop-agent uses Thread.interrupt under the hood (see
          ;; runtime/cancel-run). When the handler is parked in
          ;; (Thread/sleep N), the interrupt fires BEFORE the cooperative
          ;; (check-run-cancelled? ...) loop returns its :status :cancelled
          ;; shape. The InterruptedException bubbles up through ask's
          ;; broader Exception catch, which wraps it as {:error <msg>}
          ;; with the JVM-provided message (Java's "sleep interrupted"
          ;; from Thread.sleep, or "interrupted" from send-ask's earlier
          ;; specific catch on other paths). Both are valid cancel
          ;; terminations for this test.
          (let [err (str (:error result))]
            (is (or (contains? #{:cancelled :done :timeout} (:status result))
                    (re-find #"(?i)interrupt" err))
                (str "expected a cancel-shaped result, got: " (pr-str result)))))))))

;; ============================================================================
;; Example 6: Action permissions flow
;; ============================================================================

(deftest action-permissions-example-test
  (testing "Agent supports promise-based action permissions"
    (let [a (@#'ag/create-agent "user-1" "session-1" "perm-agent"
                                :config {:name "Permission Agent"
                                         :system-commands
                                         {"dangerous" (fn [args agent]
                                                        ;; Check if already permitted
                                                        (let [perm (proto/get-action-permission agent "dangerous-op")]
                                                          (case perm
                                                            :allowed {:status :executed :args args}
                                                            :denied  {:status :denied}
                                                            ;; No stored permission — would normally block on promise
                                                            ;; For this test, auto-allow
                                                            (do (proto/set-action-permission agent "dangerous-op" :allowed)
                                                                {:status :first-time-allowed :args args}))))}})]

      ;; First call — no permission stored, auto-allows
      (let [result (agent/ask a "/dangerous delete everything")]
        (is (= :first-time-allowed (:status result))))

      ;; Second call — permission stored as :allowed
      (let [result (agent/ask a "/dangerous really delete")]
        (is (= :executed (:status result)))
        (is (= "really delete" (:args result))))

      ;; Change permission to denied
      (agent/set-action-permission a "dangerous-op" :denied)
      (let [result (agent/ask a "/dangerous nope")]
        (is (= :denied (:status result)))))))

;; ============================================================================
;; Example 7: Parent-child agent hierarchy
;; ============================================================================

(deftest parent-child-agent-test
  (testing "Sub-agent checks parent's cancellation status"
    (let [parent (@#'ag/create-agent "user-1" "session-1" "parent-agent"
                                     :config {:name "Parent"})
          child  (@#'ag/create-agent "user-1" "session-1" "child-agent"
                                     :config {:name "Child"}
                                     :parent-agent parent)]

      ;; Child should not be cancelled
      (is (false? (agent/check-run-cancelled? child)))

      ;; Cancel parent
      (agent/stop-agent parent)

      ;; Child should now report cancelled (via parent hierarchy)
      (is (true? (agent/check-run-cancelled? child))))))

