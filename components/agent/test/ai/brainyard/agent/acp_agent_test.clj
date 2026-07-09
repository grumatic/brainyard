;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.acp-agent-test
  "Tests for the acp-agent defagent.

   The pure-registry test runs fast (no subprocess). The integration
   test spawns the in-tree :stub backend via setup-agent-by-id + ask,
   verifies the BT iteration hooks fire, the streamed chunks land in
   st-memory, and a final :answer is produced."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [ai.brainyard.agent.interface :as agent]
            [ai.brainyard.agent.common.acp-agent :as acp-agent]
            [ai.brainyard.agent.core.config :as config]
            [ai.brainyard.agent.core.tool :as tool]
            [ai.brainyard.agent.core.hooks :as hooks]))

(use-fixtures :each
  (fn [t]
    ;; A prior test in the same JVM may have wiped the global hook registry
    ;; (hooks/reset-hooks! in hooks_test / capture_*); acp-agent's cleanup hook
    ;; is registered only at ns-load, and descriptor/live-health reports
    ;; :unconnected only if that hook clears the client cache on close. Re-establish
    ;; it here to keep these tests order-independent.
    (acp-agent/register-hooks!)
    (t)))

(deftest registry-test
  (testing "acp-agent is in the unified tool registry as a :agent type"
    (let [entry (tool/get-tool-defs :id :acp-agent)]
      (is (some? entry))
      (is (= :agent (:type entry)))
      (is (some? (-> entry :meta :bt-factory))
          "metadata exposes a bt-factory")
      (is (= [:question :purpose :agent-context :acp-backend :acp-backend-opts]
             (mapv first (rest (get-in entry [:meta :input-schema]))))
          "all inputs declared (including the :purpose label)")
      (is (= [:answer]
             (mapv first (rest (get-in entry [:meta :output-schema]))))))))

;; =============================================================================
;; Permission bridge — ACP session/request_permission → TUI user-feedback
;; =============================================================================

(def ^:private make-permission-callback #'acp-agent/make-permission-callback)

(defn- fake-agent
  "Minimal agent stub carrying a session config with (optionally) a
   :user-feedback-fn."
  [feedback-fn]
  {:!session (atom {:config (cond-> {}
                              feedback-fn (assoc :user-feedback-fn feedback-fn))})})

(def ^:private perm-options
  [{:optionId "allow_once"  :name "Allow once"  :kind "allow_once"}
   {:optionId "reject_once" :name "Reject once" :kind "reject_once"}])

(def ^:private perm-params
  {:toolCall {:title "Write /etc/x" :kind "edit"} :options perm-options})

(deftest permission-bridge-test
  ;; Pin the picker timeout so we don't depend on global config state.
  (with-redefs [config/get-config (fn [_ _] 5000)]
    (testing "interactive choice maps the picked index back to its optionId"
      (let [cb (make-permission-callback
                (fake-agent (fn [_] {:selected "Allow once" :index 0})))]
        (is (= {:outcome {:outcome "selected" :optionId "allow_once"}}
               (cb perm-params))))
      (let [cb (make-permission-callback
                (fake-agent (fn [_] {:selected "Reject once" :index 1})))]
        (is (= {:outcome {:outcome "selected" :optionId "reject_once"}}
               (cb perm-params)))))

    (testing "timeout / dismissal (no :index) → cancelled outcome"
      (let [cb (make-permission-callback
                (fake-agent (fn [_] {:timeout true})))]
        (is (= {:outcome {:outcome "cancelled"}} (cb perm-params)))))

    (testing "empty options → cancelled without prompting the user"
      (let [cb (make-permission-callback
                (fake-agent (fn [_] (throw (ex-info "should not prompt" {})))))]
        (is (= {:outcome {:outcome "cancelled"}}
               (cb {:toolCall {} :options []})))))

    (testing "no interactive session → deny by selecting a reject_ option"
      (let [cb (make-permission-callback (fake-agent nil))]
        (is (= {:outcome {:outcome "selected" :optionId "reject_once"}}
               (cb perm-params)))))

    (testing "feedback question carries the toolCall title + kind"
      (let [seen (atom nil)
            cb   (make-permission-callback
                  (fake-agent (fn [req] (reset! seen req) {:index 0})))]
        (cb perm-params)
        (is (= "Permission requested: Write /etc/x [edit]" (:question @seen)))
        (is (= ["Allow once" "Reject once"] (:options @seen)))))))

;; =============================================================================
;; Descriptor + management API (the acp management overlay)
;; =============================================================================

(deftest ^:integration descriptor-and-api-test
  (testing "ensure-connected! stamps a descriptor; the acp management API reads/mutates it"
    (let [sess-id (str "acp-desc-" (System/currentTimeMillis))
          ag (agent/setup-agent-by-id
              :acp-agent
              :agent-session {:user-id "test-user" :session-id sess-id}
              :acp-backend :stub
              :acp-backend-opts {:chunk-delay-ms 5})]
      (try
        (is (acp-agent/acp-instance? ag) "recognized as an acp instance by id")
        (is (nil? (acp-agent/descriptor ag)) "no descriptor before connecting")
        (let [d (acp-agent/ensure-connected! ag)]
          (is (= :stub (:backend d)) "descriptor records the backend")
          (is (some? (:session-id d)) "descriptor records the ACP session id")
          (is (= :open (:health d)) "health probed live as :open once connected")
          (is (string? (:purpose d)) "purpose falls back to a derived label")
          (is (false? (boolean (:provisioned? d))) "not provisioned until marked"))
        (acp-agent/mark-provisioned! ag)
        (is (:provisioned? (acp-agent/descriptor ag)) "mark-provisioned! flips the flag")
        (acp-agent/set-purpose! ag "refactor payments")
        (is (= "refactor payments" (:purpose (acp-agent/descriptor ag)))
            "set-purpose! overrides the derived label")
        ;; advertised-models is nil-or-vector depending on what the stub advertises;
        ;; either way the accessor must not throw.
        (is (or (nil? (acp-agent/advertised-models ag))
                (vector? (acp-agent/advertised-models ag))))
        (finally
          (.close ag)))
      (testing "after close the client/session cache is cleared → health :unconnected"
        (is (= :unconnected (:health (acp-agent/descriptor ag)))
            "live-health reports :unconnected once the client cache is gone")))))

(defn- record-events
  "Build a hook handler fn that records `[event-key data]` pairs into !log."
  [!log event-key]
  (fn [data] (swap! !log conj [event-key data])))

(deftest ^:integration end-to-end-against-stub-test
  (testing "ask drives one ACP turn through the :stub backend, streams chunks, ends with end_turn"
    (let [!log (atom [])]
      ;; Subscribe to the events the bridge fires from session/update.
      (doseq [k [:agent.iteration/pre
                 :agent.iteration/post
                 :agent.dspy-action/chunk
                 :agent.dspy-action/post]]
        (hooks/register-hook! k ::recorder (record-events !log k)
                              :source ::test))
      (try
        (let [sess-id (str "acp-test-" (System/currentTimeMillis))
              ag (agent/setup-agent-by-id
                  :acp-agent
                  :agent-session {:user-id "test-user" :session-id sess-id}
                  :max-iterations 1
                  :st-memory-extra {:config {:max-iterations 1
                                             :acp-backend :stub
                                             :acp-backend-opts {:chunk-delay-ms 5}}})]
          (try
            (let [result (agent/ask ag "hello acp agent")
                  events @!log
                  by-event (group-by first events)]
              (is (some? result))
              (is (string? (:answer result)))
              (is (re-find #"hello" (:answer result))
                  "answer contains user's text (echoed by the stub)")
              ;; Iteration boundary hooks
              (is (seq (by-event :agent.iteration/pre))
                  "iteration/pre fired")
              (is (seq (by-event :agent.iteration/post))
                  "iteration/post fired")
              ;; Streamed chunks
              (is (seq (by-event :agent.dspy-action/chunk))
                  "at least one streamed chunk fired")
              (let [chunk-text (->> (by-event :agent.dspy-action/chunk)
                                    (map (fn [[_ d]] (:chunk d)))
                                    (apply str))]
                (is (re-find #"hello" chunk-text)
                    "chunks reconstruct to include user's text"))
              ;; Final marker
              (is (seq (by-event :agent.dspy-action/post))
                  "dspy-action/post fired so TUI can clear streaming state"))
            (finally
              (.close ag))))
        (finally
          (doseq [k [:agent.iteration/pre
                     :agent.iteration/post
                     :agent.dspy-action/chunk
                     :agent.dspy-action/post]]
            (hooks/unregister-source! ::test)))))))

(def ^:private on-event-handler #'acp-agent/on-event-handler)

(deftest thought-chunk-excluded-from-accumulator-test
  (testing "agent_thought_chunk fires the hook but does not append to the answer accumulator"
    (let [acc     (StringBuilder.)
          agent   {:agent-id :acp-agent/x}
          handler (on-event-handler agent acc)
          update! (fn [su text]
                    (handler {:method "session/update"
                              :params {:sessionId "s"
                                       :update {:sessionUpdate su
                                                :content {:type "text" :text text}}}}))]
      (update! "agent_thought_chunk" "REASONING ")
      (is (= "" (str acc))
          "a thought chunk leaves the accumulator empty")
      (update! "agent_message_chunk" "ANSWER")
      (is (= "ANSWER" (str acc))
          "only message-chunk text accumulates into the answer")
      (update! "agent_thought_chunk" " MORE-REASONING")
      (is (= "ANSWER" (str acc))
          "a later thought chunk still does not pollute the accumulated answer"))))

(deftest ^:integration accumulated-text-on-chunk-test
  (testing "chunk events carry both :chunk (delta) and :accumulated (running text)"
    (let [!log (atom [])]
      (hooks/register-hook! :agent.dspy-action/chunk ::recorder
                            (record-events !log :agent.dspy-action/chunk)
                            :source ::test)
      (try
        (let [sess-id (str "acp-acc-" (System/currentTimeMillis))
              ag (agent/setup-agent-by-id
                  :acp-agent
                  :agent-session {:user-id "test-user" :session-id sess-id}
                  :max-iterations 1
                  :st-memory-extra {:config {:max-iterations 1
                                             :acp-backend :stub
                                             :acp-backend-opts {:chunk-delay-ms 5}}})]
          (try
            (agent/ask ag "alpha beta gamma")
            (let [events @!log
                  accumulated-snapshots (->> events
                                             (map (fn [[_ d]] (:accumulated d))))]
              (is (seq accumulated-snapshots))
              (is (apply <= (map count accumulated-snapshots))
                  ":accumulated grows monotonically across chunk events")
              (is (re-find #"alpha" (last accumulated-snapshots))
                  "final accumulated includes user's text"))
            (finally
              (.close ag))))
        (finally
          (hooks/unregister-source! ::test))))))
