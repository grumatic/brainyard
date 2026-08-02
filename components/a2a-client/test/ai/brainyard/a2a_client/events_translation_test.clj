;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.a2a-client.events-translation-test
  "Unit tests for A2A stream frame -> hook descriptor translation.
   Pure — no network, no agent, no hooks bus."
  (:require [clojure.test :refer [deftest is testing]]
            [ai.brainyard.a2a-client.core.events :as events]))

(defn- status-frame
  ([state] (status-frame state nil))
  ([state text & {:keys [final task-id]}]
   {:result (cond-> {:taskId (or task-id "t-1")
                     :kind   "status-update"
                     :status (cond-> {:state state}
                               text (assoc :message
                                           {:messageId "m" :role "agent"
                                            :parts [{:kind "text" :text text}]}))}
              final (assoc :final true))}))

(defn- events-of [payloads]
  (:events (events/translate-all payloads)))

(defn- kinds-of [payloads]
  (mapv :event (events-of payloads)))

;; =============================================================================
;; Frame classification
;; =============================================================================

(deftest frame-kind-test
  (testing "classifies by :kind when present"
    (is (= :status-update (events/frame-kind {:kind "status-update"})))
    (is (= :artifact-update (events/frame-kind {:kind "artifact-update"})))
    (is (= :task (events/frame-kind {:kind "task"})))
    (is (= :message (events/frame-kind {:kind "message"}))))

  (testing "falls back to SHAPE when :kind is absent"
    ;; Servers omit :kind, notably on the initial Task frame. Trusting
    ;; :kind alone would drop those frames silently.
    (is (= :artifact-update (events/frame-kind {:artifact {}})))
    (is (= :status-update (events/frame-kind {:taskId "t" :status {:state "working"}})))
    (is (= :task (events/frame-kind {:id "t" :status {:state "working"}})))
    (is (= :message (events/frame-kind {:parts []}))))

  (testing "an unrecognizable frame is :unknown, not an error"
    (is (= :unknown (events/frame-kind {:something "else"})))))

;; =============================================================================
;; Text streaming
;; =============================================================================

(deftest text-chunk-test
  (testing "a status message becomes a real brainyard chunk hook"
    (let [evs (events-of [(status-frame "working" "Hello")])]
      (is (some #(= events/event-dspy-chunk (:event %)) evs))
      (is (= "Hello" (-> (filter #(= events/event-dspy-chunk (:event %)) evs)
                         first :data :chunk)))))

  (testing "a server sending CUMULATIVE text emits only the new suffix"
    ;; A2A status messages are not guaranteed to be deltas. Emitting each
    ;; one raw would duplicate text in the transcript.
    (let [evs (->> [(status-frame "working" "Hello")
                    (status-frame "working" "Hello world")]
                   events-of
                   (filter #(= events/event-dspy-chunk (:event %))))]
      (is (= ["Hello" " world"] (mapv #(-> % :data :chunk) evs)))
      (is (= "Hello world" (-> evs last :data :accumulated)))))

  (testing "a server sending true DELTAS concatenates them"
    (let [evs (->> [(status-frame "working" "Hello")
                    (status-frame "working" "!!!")]
                   events-of
                   (filter #(= events/event-dspy-chunk (:event %))))]
      (is (= ["Hello" "!!!"] (mapv #(-> % :data :chunk) evs)))
      (is (= "Hello!!!" (-> evs last :data :accumulated)))))

  (testing "an unchanged repeated message emits NO chunk"
    (let [evs (->> [(status-frame "working" "Hello")
                    (status-frame "working" "Hello")]
                   events-of
                   (filter #(= events/event-dspy-chunk (:event %))))]
      (is (= 1 (count evs)))))

  (testing "a status update with no message emits no chunk"
    (is (not-any? #(= events/event-dspy-chunk (:event %))
                  (events-of [(status-frame "working")])))))

;; =============================================================================
;; State transitions
;; =============================================================================

(deftest state-events-test
  (testing "every status update reports the state"
    (let [ev (first (filter #(= events/event-task-state (:event %))
                            (events-of [(status-frame "working")])))]
      (is (= :working (-> ev :data :state)))
      (is (= "t-1" (-> ev :data :task-id)))))

  (testing "a terminal state emits the terminal descriptor"
    (is (some #(= events/event-terminal (:event %))
              (events-of [(status-frame "completed" "done" :final true)]))))

  (testing "the terminal descriptor carries the accumulated answer"
    (let [ev (first (filter #(= events/event-terminal (:event %))
                            (events-of [(status-frame "working" "partial ")
                                        (status-frame "completed" "answer")])))]
      (is (= :completed (-> ev :data :state)))
      (is (= "partial answer" (-> ev :data :answer)))))

  (testing "every terminal state emits it"
    (doseq [s ["completed" "failed" "canceled" "rejected"]]
      (is (some #(= events/event-terminal (:event %))
                (events-of [(status-frame s)]))
          (str s " should emit a terminal descriptor"))))

  (testing "in-flight states do NOT emit a terminal descriptor"
    (doseq [s ["submitted" "working"]]
      (is (not-any? #(= events/event-terminal (:event %))
                    (events-of [(status-frame s)]))))))

(deftest interrupted-states-test
  (testing "input-required emits its OWN descriptor and is NOT terminal"
    ;; The expensive mistake: treating this as terminal abandons a task
    ;; the peer is still holding open for us.
    (let [ks (kinds-of [(status-frame "input-required" "Which file?")])]
      (is (some #{events/event-input-required} ks))
      (is (not-any? #{events/event-terminal} ks))))

  (testing "the input-required descriptor carries the peer's prompt"
    (let [ev (first (filter #(= events/event-input-required (:event %))
                            (events-of [(status-frame "input-required" "Which file?")])))]
      (is (= "Which file?" (-> ev :data :prompt)))
      (is (= "t-1" (-> ev :data :task-id)))))

  (testing "auth-required emits its own descriptor and is NOT terminal"
    (let [ks (kinds-of [(status-frame "auth-required" "Need a token")])]
      (is (some #{events/event-auth-required} ks))
      (is (not-any? #{events/event-terminal} ks)))))

;; =============================================================================
;; Artifacts
;; =============================================================================

(deftest artifact-events-test
  (testing "an artifact-update becomes an artifact descriptor"
    (let [ev (first (events-of
                     [{:result {:taskId "t-1" :kind "artifact-update"
                                :artifact {:artifactId "a-1" :name "report.md"
                                           :parts [{:kind "text" :text "body"}]}
                                :append false :lastChunk true}}]))]
      (is (= events/event-artifact (:event ev)))
      (is (= "a-1" (-> ev :data :artifact-id)))
      (is (= "report.md" (-> ev :data :name)))
      (is (= "body" (-> ev :data :text)))
      (is (true? (-> ev :data :last-chunk)))
      (is (false? (-> ev :data :append)))))

  (testing "the raw artifact is carried for the agent layer"
    (let [ev (first (events-of [{:result {:taskId "t" :kind "artifact-update"
                                          :artifact {:artifactId "a" :parts []}}}]))]
      (is (map? (-> ev :data :artifact))))))

;; =============================================================================
;; What we deliberately DON'T synthesize
;; =============================================================================

(deftest no-invented-tool-calls-test
  (testing "A2A traffic never produces tool-use or todo hook events"
    ;; A2A's streaming vocabulary is only status-update and
    ;; artifact-update. Synthesizing :agent.tool-use/* or :todo/updated
    ;; would put fabricated tool activity in the user's transcript.
    (let [ks (set (kinds-of [(status-frame "working" "thinking")
                             (status-frame "completed" "done")
                             {:result {:taskId "t" :kind "artifact-update"
                                       :artifact {:artifactId "a" :parts []}}}]))]
      (is (not (contains? ks :agent.tool-use/pre)))
      (is (not (contains? ks :agent.tool-use/post)))
      (is (not (contains? ks :todo/updated)))
      (is (every? events/all-events ks)
          "every emitted descriptor must be in the declared vocabulary"))))

;; =============================================================================
;; Robustness
;; =============================================================================

(deftest robustness-test
  (testing "an error payload surfaces as a state descriptor carrying the error"
    (let [ev (first (events-of [{:error "boom"}]))]
      (is (= events/event-task-state (:event ev)))
      (is (= "boom" (-> ev :data :error)))))

  (testing "an empty payload produces no events"
    (is (= [] (events-of [{}])))
    (is (= [] (events-of [{:result nil}]))))

  (testing "an UNKNOWN frame kind is skipped without derailing the stream"
    ;; A newer minor version may add a frame type. Later frames still matter.
    (let [ks (kinds-of [{:result {:kind "future-thing" :data 1}}
                        (status-frame "completed")])]
      (is (some #{events/event-terminal} ks))))

  (testing "translate never throws on malformed input"
    (doseq [p [{} {:result "string"} {:result 42} {:result []}
               {:result {:kind "status-update"}}]]
      (is (map? (events/translate (events/initial-acc) p))
          (str "should not throw on: " (pr-str p)))))

  (testing "the accumulator tracks task and context ids across frames"
    (let [{:keys [acc]} (events/translate-all
                         [{:result {:taskId "t-9" :contextId "c-9"
                                    :kind "status-update"
                                    :status {:state "working"}}}])]
      (is (= "t-9" (:task-id acc)))
      (is (= "c-9" (:context-id acc))))))
