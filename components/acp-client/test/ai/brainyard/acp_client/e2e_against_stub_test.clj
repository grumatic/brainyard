;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.acp-client.e2e-against-stub-test
  "End-to-end tests for the ACP client against the in-tree stub.

   Spawns `bases/acp-stub-agent` via the `:stub` registry entry,
   runs the full lifecycle (initialize → new-session → prompt →
   close), and asserts on the events captured by an atom-recording
   `:on-event` callback.

   Slow (~10s) due to subprocess JVM startup; keep test count low."
  (:require [clojure.test :refer [deftest is testing]]
            [ai.brainyard.acp-client.interface :as acp-client]))

(defn- record-events
  "Build an :on-event callback that translates each ACP notification
   to a brainyard hook event descriptor and conjs it onto `!log`."
  [!log]
  (fn [msg]
    (when (= "session/update" (:method msg))
      (when-let [evt (acp-client/translate-update (:params msg))]
        (swap! !log conj evt)))))

(defn- spawn-stub-client! [!log]
  (acp-client/spawn! :stub
                     {:on-event     (record-events !log)
                      :backend-opts {:chunk-delay-ms 5}}))

(deftest ^:integration end-to-end-flow-test
  (testing "spawn → initialize → new session → prompt → end_turn"
    (let [!log (atom [])
          client (spawn-stub-client! !log)]
      (try
        (let [init-result (acp-client/initialize! client {:timeout-ms 30000})]
          (is (string? (:protocolVersion init-result))))

        (let [sess (acp-client/new-session! client)]
          (is (string? (:session-id sess)))

          (let [{:keys [stop-reason raw end-event]}
                (acp-client/prompt-text! sess "echo me back")]
            (is (= "end_turn" stop-reason))
            (is (= "end_turn" (:stopReason raw)))
            (is (= :agent.iteration/post (:event end-event)))
            (is (true? (-> end-event :data :goal-achieved)))

            ;; Events captured during the turn
            (let [events @!log
                  chunk-events (filter #(= :agent.dspy-action/chunk (:event %)) events)
                  concat-text  (->> chunk-events
                                    (map #(get-in % [:data :chunk]))
                                    (apply str))]
              (is (seq chunk-events) "saw at least one streamed chunk")
              (is (re-find #"echo" concat-text)
                  "the stub's echo prefix is in the stream")
              (is (re-find #"back" concat-text)
                  "the user's text is in the stream"))))

        (finally
          (acp-client/close! client))))))

(deftest ^:integration unknown-backend-throws-test
  (testing "unrecognized registry backend keyword raises"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"unknown ACP backend"
                          (acp-client/spawn! :nope-not-a-backend)))))

(deftest ^:integration permission-fallback-policy-test
  (testing "pick-option-id is exposed through the public interface"
    (let [opts [{:optionId "allow_once"  :name "Allow once"}
                {:optionId "reject_once" :name "Reject once"}]]
      (is (= "allow_once"  (acp-client/pick-option-id :allow opts)))
      (is (= "reject_once" (acp-client/pick-option-id :block opts))))))
