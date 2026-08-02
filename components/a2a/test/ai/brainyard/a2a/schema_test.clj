;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.a2a.schema-test
  "Unit tests for A2A payload schemas and wire<->keyword coercion.
   Pure data — no I/O.

   The fixtures below are shaped like real v0.3 JSON-RPC wire payloads
   (`kind` discriminators, camelCase ids, lowercase-kebab task states),
   because the whole point of these schemas is to accept what a
   conformant peer actually sends."
  (:require [clojure.set]
            [clojure.test :refer [deftest is testing]]
            [ai.brainyard.a2a.core.methods :as methods]
            [ai.brainyard.a2a.core.schema :as schema]))

;; =============================================================================
;; Fixtures
;; =============================================================================

(def text-msg
  {:messageId "m-1"
   :role      "user"
   :kind      "message"
   :parts     [{:kind "text" :text "hello"}]})

(def a-task
  {:id        "t-1"
   :contextId "c-1"
   :kind      "task"
   :status    {:state "working" :timestamp "2026-08-02T00:00:00Z"}})

;; =============================================================================
;; Part
;; =============================================================================

(deftest part-schema-test
  (testing "the discriminator is :kind, not :type (that is ACP's spelling)"
    (is (schema/valid? schema/Part {:kind "text" :text "hi"}))
    (is (not (schema/valid? schema/Part {:type "text" :text "hi"}))
        "a :type-discriminated part must NOT validate — silently accepting it
         would let an ACP-shaped payload through as if it were A2A"))

  (testing "all three declared kinds validate"
    (is (schema/valid? schema/Part {:kind "text" :text "hi"}))
    (is (schema/valid? schema/Part {:kind "file" :file {:uri "https://x/y.png"
                                                        :mimeType "image/png"}}))
    (is (schema/valid? schema/Part {:kind "data" :data {:a 1}})))

  (testing "a text part requires :text"
    (is (not (schema/valid? schema/Part {:kind "text"}))))

  (testing "an UNKNOWN kind is accepted, not rejected"
    ;; A peer on a newer minor version may introduce a part kind we have
    ;; never heard of. Rejecting the whole message would be stricter than
    ;; the protocol and would break forward compatibility by design.
    (is (schema/valid? schema/Part {:kind "video" :src "x"})))

  (testing "parts carry unknown keys through (schemas are open)"
    (is (schema/valid? schema/Part {:kind "text" :text "hi" :futureField 42}))))

;; =============================================================================
;; Message / Task / Artifact
;; =============================================================================

(deftest message-schema-test
  (testing "a minimal wire message validates"
    (is (schema/valid? schema/Message text-msg)))

  (testing ":messageId, :role and :parts are required"
    (is (not (schema/valid? schema/Message (dissoc text-msg :messageId))))
    (is (not (schema/valid? schema/Message (dissoc text-msg :role))))
    (is (not (schema/valid? schema/Message (dissoc text-msg :parts)))))

  (testing "role is constrained to the wire enum"
    (is (schema/valid? schema/Message (assoc text-msg :role "agent")))
    (is (not (schema/valid? schema/Message (assoc text-msg :role "system"))))
    (is (not (schema/valid? schema/Message (assoc text-msg :role "ROLE_USER")))
        "the protobuf enum spelling does not travel on this wire"))

  (testing "optional linkage fields validate when present"
    (is (schema/valid? schema/Message (assoc text-msg :taskId "t-1"
                                             :contextId "c-1"
                                             :referenceTaskIds ["t-0"])))))

(deftest task-schema-test
  (testing "a minimal wire task validates"
    (is (schema/valid? schema/Task a-task)))

  (testing ":id and :status are required"
    (is (not (schema/valid? schema/Task (dissoc a-task :id))))
    (is (not (schema/valid? schema/Task (dissoc a-task :status)))))

  (testing "every declared task state validates"
    (doseq [s methods/task-states]
      (is (schema/valid? schema/Task (assoc-in a-task [:status :state] s))
          (str "state should validate: " s))))

  (testing "an undeclared state does not validate"
    (is (not (schema/valid? schema/Task (assoc-in a-task [:status :state] "cancelled")))
        "two-L 'cancelled' is brainyard's spelling, NOT the wire's")
    (is (not (schema/valid? schema/Task (assoc-in a-task [:status :state]
                                                  "TASK_STATE_WORKING")))
        "the protobuf enum spelling does not travel on this wire"))

  (testing "history and artifacts validate when present"
    (is (schema/valid? schema/Task
                       (assoc a-task
                              :history [text-msg]
                              :artifacts [{:artifactId "a-1"
                                           :parts [{:kind "text" :text "out"}]}])))))

(deftest artifact-id-test
  (testing "reads the v0.3 :artifactId name"
    (is (= "a-1" (schema/artifact-id {:artifactId "a-1"}))))

  (testing "falls back to the v1.0 proto :id name"
    (is (= "a-2" (schema/artifact-id {:id "a-2"}))))

  (testing ":artifactId wins when a peer sends both"
    (is (= "a-1" (schema/artifact-id {:artifactId "a-1" :id "a-2"}))))

  (testing "nil when neither is present"
    (is (nil? (schema/artifact-id {:parts []})))))

;; =============================================================================
;; Streaming events
;; =============================================================================

(deftest stream-event-schema-test
  (testing "a status-update event validates"
    (is (schema/valid? schema/TaskStatusUpdateEvent
                       {:taskId "t-1" :kind "status-update"
                        :status {:state "working"} :final false})))

  (testing "an artifact-update event validates"
    (is (schema/valid? schema/TaskArtifactUpdateEvent
                       {:taskId "t-1" :kind "artifact-update"
                        :artifact {:artifactId "a-1"
                                   :parts [{:kind "text" :text "chunk"}]}
                        :append true :lastChunk false})))

  (testing "StreamResponse dispatches each frame kind"
    (is (schema/valid? schema/StreamResponse a-task))
    (is (schema/valid? schema/StreamResponse text-msg))
    (is (schema/valid? schema/StreamResponse
                       {:taskId "t-1" :kind "status-update" :status {:state "completed"}}))
    (is (schema/valid? schema/StreamResponse
                       {:taskId "t-1" :kind "artifact-update"
                        :artifact {:artifactId "a" :parts []}})))

  (testing "a frame with no :kind is tolerated — some servers omit it"
    (is (schema/valid? schema/StreamResponse {:something "else"}))))

;; =============================================================================
;; Method params
;; =============================================================================

(deftest params-schema-test
  (testing "message/send params require :message"
    (is (schema/valid? schema/MessageSendParams {:message text-msg}))
    (is (not (schema/valid? schema/MessageSendParams {}))))

  (testing "configuration is optional and validates when present"
    (is (schema/valid? schema/MessageSendParams
                       {:message text-msg
                        :configuration {:blocking false
                                        :acceptedOutputModes ["text/plain"]
                                        :historyLength 0}})))

  (testing "tasks/get params require :id"
    (is (schema/valid? schema/TaskQueryParams {:id "t-1"}))
    (is (not (schema/valid? schema/TaskQueryParams {:taskId "t-1"}))
        ":taskId is the Message field name; tasks/get takes :id")))

;; =============================================================================
;; Wire <-> keyword coercion — the one-L trap
;; =============================================================================

(deftest state-coercion-test
  (testing "every wire state round-trips to a keyword"
    (doseq [s methods/task-states]
      (is (= (keyword s) (schema/state->kw s)))))

  (testing "unknown and nil states degrade to :unknown rather than throwing"
    (is (= :unknown (schema/state->kw "not-a-state")))
    (is (= :unknown (schema/state->kw nil)))
    (is (= :unknown (schema/state->kw ""))))

  (testing "state->kw tolerates surrounding whitespace and case"
    (is (= :working (schema/state->kw " Working "))))

  (testing "BOTH spellings of cancelled map to the protocol's one-L form"
    ;; This is the trap the whole coercion layer exists for: A2A says
    ;; "canceled", brainyard's task manager says :cancelled.
    (is (= "canceled" (schema/kw->state :canceled)))
    (is (= "canceled" (schema/kw->state :cancelled))))

  (testing "kw->state maps the remaining states"
    (is (= "input-required" (schema/kw->state :input-required)))
    (is (= "auth-required"  (schema/kw->state :auth-required)))
    (is (= "completed"      (schema/kw->state :completed)))
    (is (= "unknown"        (schema/kw->state :nonsense)))))

(deftest terminal-vs-interrupted-test
  (testing "terminal states end the task"
    (doseq [s ["completed" "canceled" "failed" "rejected"]]
      (is (schema/terminal? s) (str s " should be terminal"))))

  (testing "in-flight states are not terminal"
    (is (not (schema/terminal? "submitted")))
    (is (not (schema/terminal? "working"))))

  (testing "INTERRUPTED states are not terminal — the expensive mistake"
    ;; input-required / auth-required mean the peer is still holding the
    ;; task open for us. Lumping them in with terminal abandons work we
    ;; could have resumed.
    (is (not (schema/terminal? "input-required")))
    (is (not (schema/terminal? "auth-required")))
    (is (schema/interrupted? "input-required"))
    (is (schema/interrupted? "auth-required")))

  (testing "terminal and interrupted are disjoint"
    (is (empty? (clojure.set/intersection methods/terminal-states
                                          methods/interrupted-states))))

  (testing "both predicates accept keywords as well as wire strings"
    (is (schema/terminal? :completed))
    (is (schema/terminal? :cancelled) "brainyard's two-L spelling still resolves")
    (is (schema/interrupted? :input-required))))

;; =============================================================================
;; Content helpers
;; =============================================================================

(deftest text-helpers-test
  (testing "text-part / data-part build valid Parts"
    (is (schema/valid? schema/Part (schema/text-part "hi")))
    (is (schema/valid? schema/Part (schema/data-part {:a 1}))))

  (testing "part-text extracts text and returns \"\" for non-text parts"
    (is (= "hi" (schema/part-text {:kind "text" :text "hi"})))
    (is (= "" (schema/part-text {:kind "data" :data {:a 1}})))
    (is (= "" (schema/part-text {:kind "file" :file {:uri "x"}})))
    (is (= "" (schema/part-text nil))))

  (testing "parts-text concatenates ONLY text parts"
    ;; A file or data part has no text; stringifying it would put junk
    ;; like a base64 blob into a transcript.
    (is (= "ab" (schema/parts-text [{:kind "text" :text "a"}
                                    {:kind "data" :data {:big "blob"}}
                                    {:kind "text" :text "b"}]))))

  (testing "message-text reads through a Message"
    (is (= "hello" (schema/message-text text-msg)))))

;; =============================================================================
;; Validation helpers
;; =============================================================================

(deftest validate-helpers-test
  (testing "validate returns true on success"
    (is (true? (schema/validate schema/Message text-msg))))

  (testing "validate throws with an explanation on failure"
    (is (thrown? clojure.lang.ExceptionInfo
                 (schema/validate schema/Message {:role "user"})))
    (try
      (schema/validate schema/Message {:role "user"})
      (catch clojure.lang.ExceptionInfo e
        (is (= :a2a/schema-error (:type (ex-data e))))
        (is (some? (:explain (ex-data e)))))))

  (testing "explain returns nil for a valid value"
    (is (nil? (schema/explain schema/Message text-msg)))
    (is (some? (schema/explain schema/Message {})))))
