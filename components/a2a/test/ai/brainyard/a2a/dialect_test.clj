;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.a2a.dialect-test
  "Tests for the v0.3 / v1.0 wire-dialect codec.

   The v1.0 fixtures below are payloads CAPTURED VERBATIM from a running
   `a2a-sdk` 1.1.0 (the official `a2a-samples` helloworld agent), not
   hand-written from the same understanding that produced the code. That
   distinction matters here more than usual: the gap this codec exists to
   close survived a green suite precisely because every fixture encoded what
   we already believed."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [ai.brainyard.a2a.core.dialect :as d]
            [ai.brainyard.a2a.core.methods :as methods]))

;; =============================================================================
;; Captured wire payloads
;; =============================================================================

(def captured-v1-send-result
  "The `result` of a real `SendMessage` against a2a-sdk 1.1.0."
  {:task
   {:id "417f68cc-ff77-4394-a125-cd076d6fb7c9"
    :contextId "2175a181-f661-4fa0-9ebe-454673f289c9"
    :status {:state "TASK_STATE_COMPLETED"
             :message {:messageId "0422bad6-e98e-4fff-be08-16b787d67814"
                       :role "ROLE_AGENT"
                       :parts [{:text "Request is completed!"}]}
             :timestamp "2026-08-02T22:44:49.479909Z"}
    :artifacts [{:artifactId "7b4b8ff7-aa21-476b-b9ba-b7d45734c6a3"
                 :parts [{:text "Hello, World! I have received your request (hi)"
                          :mediaType "text/plain"}]}]}})

(def captured-v1-card
  "The Agent Card served by that same agent."
  {:name "Hello World Agent"
   :supportedInterfaces [{:url "http://127.0.0.1:9999"
                          :protocolBinding "JSONRPC"
                          :protocolVersion "1.0"}]
   :capabilities {:streaming true :extendedAgentCard true}
   :skills [{:id "echo_bot" :name "Echo Bot"}]})

(def v03-card
  {:name "peer-b" :url "https://b/a2a" :protocolVersion "0.3"})

;; =============================================================================
;; Detection
;; =============================================================================

(deftest of-version-test
  (testing "major >= 1 is v1.0"
    (doseq [v ["1.0" "1" "1.2" "2.0"]]
      (is (= :v1.0 (d/of-version v)) (str v " should be v1.0"))))

  (testing "0.x is v0.3"
    (doseq [v ["0.3" "0.2" "0.9"]]
      (is (= :v0.3 (d/of-version v)))))

  (testing "absent / blank / junk defaults to v0.3"
    ;; The spec reads an absent A2A-Version as 0.3, and assuming the OLDER
    ;; dialect fails loudly (MethodNotFound) rather than silently
    ;; mis-encoding against a newer one.
    (doseq [v [nil "" "   " "abc" "vNext"]]
      (is (= :v0.3 (d/of-version v)) (str (pr-str v) " should default to v0.3")))))

(deftest of-card-test
  (testing "a REAL v1.0 card resolves to v1.0"
    (is (= :v1.0 (d/of-card captured-v1-card))))

  (testing "a v0.3 card resolves to v0.3"
    (is (= :v0.3 (d/of-card v03-card))))

  (testing "supportedInterfaces alone implies v1.0 even with no version"
    ;; It is a v1.0-only field; its presence is the signal.
    (is (= :v1.0 (d/of-card {:name "x" :supportedInterfaces [{:url "u"
                                                              :protocolBinding "JSONRPC"}]}))))

  (testing "a DUAL card (both generations) prefers v1.0"
    ;; brainyard's own card advertises both. When a peer offers a choice,
    ;; take the current dialect.
    (is (= :v1.0 (d/of-card (merge v03-card captured-v1-card)))))

  (testing "a card declaring nothing defaults to v0.3"
    (is (= :v0.3 (d/of-card {:name "bare"})))))

(deftest version-of-test
  (is (= "1.0" (d/version-of :v1.0)))
  (is (= "0.3" (d/version-of :v0.3))))

;; =============================================================================
;; Methods
;; =============================================================================

(deftest method-name-test
  (testing "v0.3 uses the slash names"
    (is (= "message/send" (d/method-name :v0.3 :message-send)))
    (is (= "tasks/get" (d/method-name :v0.3 :tasks-get))))

  (testing "v1.0 uses the gRPC service names"
    (is (= "SendMessage" (d/method-name :v1.0 :message-send)))
    (is (= "GetTask" (d/method-name :v1.0 :tasks-get)))
    (is (= "SendStreamingMessage" (d/method-name :v1.0 :message-stream))))

  (testing "resubscribe is SubscribeToTask in v1.0, not a slash rename"
    (is (= "SubscribeToTask" (d/method-name :v1.0 :tasks-resubscribe))))

  (testing "the push setter is Create in v1.0, not set"
    (is (= "CreateTaskPushNotificationConfig" (d/method-name :v1.0 :push-config-set)))
    (is (= "tasks/pushNotificationConfig/set" (d/method-name :v0.3 :push-config-set))))

  (testing "both dialects cover the SAME method keywords"
    ;; A keyword present in one table and missing from the other is a
    ;; call that silently works on one peer and throws on another.
    (is (= (set (keys methods/client-methods)) (set (keys d/v1-methods)))))

  (testing "an unknown keyword throws rather than producing a bad wire name"
    (is (thrown? clojure.lang.ExceptionInfo (d/method-name :v1.0 :nope)))))

(deftest method->kw-test
  (testing "round-trips in both dialects"
    (doseq [dl [:v0.3 :v1.0]
            k   (keys methods/client-methods)]
      (is (= k (d/method->kw dl (d/method-name dl k)))
          (str dl " " k))))

  (testing "a name from the OTHER dialect does not resolve"
    (is (nil? (d/method->kw :v0.3 "SendMessage")))
    (is (nil? (d/method->kw :v1.0 "message/send"))))

  (testing "any-method->kw resolves under either, reporting which"
    (is (= [:v0.3 :message-send] (d/any-method->kw "message/send")))
    (is (= [:v1.0 :message-send] (d/any-method->kw "SendMessage")))
    (is (nil? (d/any-method->kw "nonsense")))))

;; =============================================================================
;; Enums
;; =============================================================================

(deftest state-coercion-test
  (testing "v1.0 proto constants decode to the canonical v0.3 strings"
    (is (= "completed" (d/decode-state :v1.0 "TASK_STATE_COMPLETED")))
    (is (= "input-required" (d/decode-state :v1.0 "TASK_STATE_INPUT_REQUIRED")))
    (is (= "auth-required" (d/decode-state :v1.0 "TASK_STATE_AUTH_REQUIRED")))
    (is (= "canceled" (d/decode-state :v1.0 "TASK_STATE_CANCELED"))))

  (testing "UNSPECIFIED maps to v0.3's 'unknown'"
    ;; v1.0 dropped `unknown` and added `UNSPECIFIED` for the same idea.
    (is (= "unknown" (d/decode-state :v1.0 "TASK_STATE_UNSPECIFIED")))
    (is (= "TASK_STATE_UNSPECIFIED" (d/encode-state :v1.0 "unknown"))))

  (testing "v0.3 states pass through"
    (is (= "completed" (d/decode-state :v0.3 "completed")))
    (is (= "completed" (d/encode-state :v0.3 "completed"))))

  (testing "every canonical state round-trips through v1.0"
    (doseq [s methods/task-states]
      (is (= s (d/decode-state :v1.0 (d/encode-state :v1.0 s)))
          (str s " must survive a v1.0 round trip"))))

  (testing "blank is nil, not an empty enum"
    (is (nil? (d/decode-state :v1.0 nil)))
    (is (nil? (d/encode-state :v1.0 "")))))

(deftest role-coercion-test
  (is (= "user"  (d/decode-role :v1.0 "ROLE_USER")))
  (is (= "agent" (d/decode-role :v1.0 "ROLE_AGENT")))
  (is (= "ROLE_USER" (d/encode-role :v1.0 "user")))
  (is (= "user" (d/decode-role :v0.3 "user")))
  (is (= "user" (d/encode-role :v0.3 "user")))
  (testing "round-trips"
    (doseq [r ["user" "agent"]]
      (is (= r (d/decode-role :v1.0 (d/encode-role :v1.0 r)))))))

;; =============================================================================
;; Part — the widest gap
;; =============================================================================

(deftest part-coercion-test
  (testing "a v1.0 text part gains the canonical :kind"
    ;; v1.0 uses a protobuf one-of: the variant is implied by WHICH field is
    ;; set, with no discriminator at all.
    (is (= {:kind "text" :text "hi"} (d/decode-part :v1.0 {:text "hi"}))))

  (testing "a v1.0 data part"
    (is (= {:kind "data" :data {:a 1}} (d/decode-part :v1.0 {:data {:a 1}}))))

  (testing "raw becomes a file part carrying bytes, keeping filename/mediaType"
    (is (= {:kind "file" :file {:name "d.pdf" :mimeType "application/pdf" :bytes "YmFzZTY0"}}
           (d/decode-part :v1.0 {:raw "YmFzZTY0" :filename "d.pdf"
                                 :mediaType "application/pdf"}))))

  (testing "url becomes a file part carrying a uri"
    (is (= {:kind "file" :file {:mimeType "image/png" :uri "https://x/y.png"}}
           (d/decode-part :v1.0 {:url "https://x/y.png" :mediaType "image/png"}))))

  (testing "a part with NO one-of field set decodes to nil, not a throw"
    ;; Malformed input from a remote peer must be skippable.
    (is (nil? (d/decode-part :v1.0 {:metadata {}})))
    (is (nil? (d/decode-part :v1.0 {}))))

  (testing "encoding drops :kind and emits the matching one-of field"
    (is (= {:text "hi"} (d/encode-part :v1.0 {:kind "text" :text "hi"})))
    (is (= {:data {:a 1}} (d/encode-part :v1.0 {:kind "data" :data {:a 1}})))
    (is (= {:raw "b64" :filename "f" :mediaType "text/plain"}
           (d/encode-part :v1.0 {:kind "file" :file {:bytes "b64" :name "f"
                                                     :mimeType "text/plain"}})))
    (is (= {:url "u"} (d/encode-part :v1.0 {:kind "file" :file {:uri "u"}}))))

  (testing "a file part with neither bytes nor uri encodes to nil"
    (is (nil? (d/encode-part :v1.0 {:kind "file" :file {:name "f"}}))))

  (testing "v0.3 parts pass through untouched"
    (let [p {:kind "text" :text "hi"}]
      (is (= p (d/decode-part :v0.3 p)))
      (is (= p (d/encode-part :v0.3 p)))))

  (testing "round-trips through v1.0"
    (doseq [p [{:kind "text" :text "hi"}
               {:kind "data" :data {:a 1}}
               {:kind "file" :file {:uri "https://x" :mimeType "image/png"}}
               {:kind "file" :file {:bytes "b64" :name "f.txt"}}]]
      (is (= p (d/decode-part :v1.0 (d/encode-part :v1.0 p)))
          (str "round trip: " (pr-str p))))))

;; =============================================================================
;; Envelopes — results and stream frames
;; =============================================================================

(deftest send-result-test
  (testing "a REAL captured v1.0 result unwraps to a canonical Task"
    (let [t (d/decode-send-result :v1.0 captured-v1-send-result)]
      (is (= "task" (:kind t)) "canonical Tasks carry :kind; v1.0 does not")
      (is (= "completed" (get-in t [:status :state])))
      (is (= "agent" (get-in t [:status :message :role])))
      (is (= "Request is completed!"
             (get-in t [:status :message :parts 0 :text])))
      (is (= "text" (get-in t [:status :message :parts 0 :kind])))
      (is (= "7b4b8ff7-aa21-476b-b9ba-b7d45734c6a3"
             (get-in t [:artifacts 0 :artifactId]))
          "artifactId is unchanged between dialects")
      (is (= "text" (get-in t [:artifacts 0 :parts 0 :kind])))))

  (testing "a v1.0 bare-message result unwraps too"
    (let [m (d/decode-send-result :v1.0 {:message {:messageId "m" :role "ROLE_AGENT"
                                                   :parts [{:text "hi"}]}})]
      (is (= "message" (:kind m)))
      (is (= "agent" (:role m)))))

  (testing "v0.3 results pass through"
    (let [t {:id "t" :kind "task" :status {:state "completed"}}]
      (is (= t (d/decode-send-result :v0.3 t)))))

  (testing "encoding re-wraps into the v1.0 one-of"
    (is (contains? (d/encode-send-result :v1.0 {:id "t" :kind "task"
                                                :status {:state "completed"}})
                   :task))
    (is (contains? (d/encode-send-result :v1.0 {:kind "message" :messageId "m"
                                                :role "agent" :parts []})
                   :message)))

  (testing "a Task round-trips through v1.0"
    ;; The fixture is fully canonical, INCLUDING :kind on the nested status
    ;; message. `:kind` is optional in the schema, so a message without one
    ;; is still valid input — decode simply normalizes it in. Asserting
    ;; equality against a non-normalized fixture would be testing the
    ;; fixture, not the codec.
    (let [t {:id "t" :kind "task" :contextId "c"
             :status {:state "completed"
                      :message {:messageId "m" :role "agent" :kind "message"
                                :parts [{:kind "text" :text "x"}]}}}]
      (is (= t (d/decode-send-result :v1.0 (d/encode-send-result :v1.0 t))))))

  (testing "decoding is IDEMPOTENT — normalization settles after one pass"
    ;; The property that matters operationally: a frame decoded twice (a
    ;; retry, a replay) must not drift.
    (let [once  (d/decode-send-result :v1.0 captured-v1-send-result)
          twice (d/decode-send-result :v1.0 (d/encode-send-result :v1.0 once))]
      (is (= once twice)))))

(deftest stream-frame-test
  (testing "a v1.0 statusUpdate unwraps and gains :kind"
    (let [f (d/decode-stream-frame
             :v1.0 {:statusUpdate {:taskId "t" :contextId "c"
                                   :status {:state "TASK_STATE_WORKING"}}})]
      (is (= "status-update" (:kind f)))
      (is (= "working" (get-in f [:status :state])))
      (is (= "t" (:taskId f)))))

  (testing ":final is SYNTHESISED from the state — v1.0 has no such field"
    ;; Keeps the canonical form uniform so events/translate is untouched.
    (is (false? (:final (d/decode-stream-frame
                         :v1.0 {:statusUpdate {:taskId "t"
                                               :status {:state "TASK_STATE_WORKING"}}}))))
    (is (true? (:final (d/decode-stream-frame
                        :v1.0 {:statusUpdate {:taskId "t"
                                              :status {:state "TASK_STATE_COMPLETED"}}}))))
    (testing "an interrupted state is NOT final — the peer still holds the task"
      (is (false? (:final (d/decode-stream-frame
                           :v1.0 {:statusUpdate
                                  {:taskId "t"
                                   :status {:state "TASK_STATE_INPUT_REQUIRED"}}}))))))

  (testing "a v1.0 artifactUpdate unwraps"
    (let [f (d/decode-stream-frame
             :v1.0 {:artifactUpdate {:taskId "t" :lastChunk true
                                     :artifact {:artifactId "a"
                                                :parts [{:text "body"}]}}})]
      (is (= "artifact-update" (:kind f)))
      (is (= "a" (get-in f [:artifact :artifactId])))
      (is (= "text" (get-in f [:artifact :parts 0 :kind])))
      (is (true? (:lastChunk f)))))

  (testing "v1.0 task and message frames unwrap"
    (is (= "task" (:kind (d/decode-stream-frame
                          :v1.0 {:task {:id "t" :status {:state "TASK_STATE_WORKING"}}}))))
    (is (= "message" (:kind (d/decode-stream-frame
                             :v1.0 {:message {:messageId "m" :role "ROLE_AGENT"
                                              :parts []}})))))

  (testing "v0.3 frames pass through"
    (let [f {:taskId "t" :kind "status-update" :status {:state "working"} :final false}]
      (is (= f (d/decode-stream-frame :v0.3 f)))))

  (testing "encoding wraps into the one-of and DROPS :final"
    (let [w (d/encode-stream-frame :v1.0 {:taskId "t" :kind "status-update"
                                          :final true
                                          :status {:state "completed"}})]
      (is (contains? w :statusUpdate))
      (is (not (contains? (:statusUpdate w) :final))
          "v1.0 TaskStatusUpdateEvent has no final field")
      (is (= "TASK_STATE_COMPLETED" (get-in w [:statusUpdate :status :state])))))

  (testing "status frames round-trip through v1.0 apart from :final"
    (let [f {:taskId "t" :contextId "c" :kind "status-update"
             :status {:state "completed"}}]
      (is (= (assoc f :final true)
             (d/decode-stream-frame :v1.0 (d/encode-stream-frame :v1.0 f)))))))

;; =============================================================================
;; Params
;; =============================================================================

(deftest send-params-test
  (let [canonical {:message {:messageId "m" :role "user" :kind "message"
                             :parts [{:kind "text" :text "hi"}]}
                   :configuration {:blocking true}}]
    (testing "v1.0 encoding produces exactly what the SDK accepted"
      ;; Established by driving the real server: ROLE_USER, bare parts, and
      ;; no :kind on the Message.
      (let [w (d/encode-send-params :v1.0 canonical)]
        (is (= "ROLE_USER" (get-in w [:message :role])))
        (is (= [{:text "hi"}] (get-in w [:message :parts])))
        (is (not (contains? (:message w) :kind)))
        ;; NOT {:blocking true} — v1.0 replaced that field with its logical
        ;; negation. See config-blocking-is-INVERTED-in-v1-test.
        (is (= {:returnImmediately false} (:configuration w)))))

    (testing "v0.3 encoding is unchanged"
      (is (= canonical (d/encode-send-params :v0.3 canonical))))

    (testing "server-side decode is the inverse"
      (is (= canonical (d/decode-send-params
                        :v1.0 (d/encode-send-params :v1.0 canonical)))))))

(deftest config-blocking-is-INVERTED-in-v1-test
  ;; The most dangerous difference found. v1.0 replaced `blocking` with
  ;; `returnImmediately`, which is its LOGICAL NEGATION — not a rename.
  ;; Passing `blocking` through unchanged is what produced -32602 against
  ;; the real SDK; passing it through under a tolerant server would have
  ;; been worse, giving exactly the opposite behaviour silently.
  (testing "blocking true becomes returnImmediately false"
    (is (= {:returnImmediately false}
           (d/encode-config :v1.0 {:blocking true}))))

  (testing "blocking false becomes returnImmediately true"
    (is (= {:returnImmediately true}
           (d/encode-config :v1.0 {:blocking false}))))

  (testing "the v0.3 field name never reaches a v1.0 peer"
    (is (not (contains? (d/encode-config :v1.0 {:blocking true}) :blocking))))

  (testing "the push-config field is renamed too"
    (is (= {:taskPushNotificationConfig {:url "u"}}
           (d/encode-config :v1.0 {:pushNotificationConfig {:url "u"}}))))

  (testing "unchanged fields stay unchanged"
    (is (= {:acceptedOutputModes ["text/plain"] :historyLength 5}
           (d/encode-config :v1.0 {:acceptedOutputModes ["text/plain"]
                                   :historyLength 5}))))

  (testing "v0.3 configs pass through"
    (is (= {:blocking true} (d/encode-config :v0.3 {:blocking true}))))

  (testing "the inversion round-trips, in BOTH senses"
    ;; A one-way inversion bug reads correctly in one direction only.
    (doseq [b [true false]]
      (is (= {:blocking b}
             (d/decode-config :v1.0 (d/encode-config :v1.0 {:blocking b})))
          (str "blocking " b " must survive a round trip"))))

  (testing "an absent blocking stays absent — not defaulted"
    ;; Omitting it means "server default"; inventing one would change
    ;; behaviour for callers who deliberately said nothing.
    (is (not (contains? (d/encode-config :v1.0 {:historyLength 1})
                        :returnImmediately)))))

;; =============================================================================
;; Robustness
;; =============================================================================

(deftest never-throws-on-junk-test
  (testing "every codec tolerates non-map and empty input"
    (doseq [dl [:v0.3 :v1.0]
            v  [nil {} "string" 42 []]]
      (doseq [f [d/decode-part d/encode-part d/decode-message d/encode-message
                 d/decode-task d/encode-task d/decode-status d/encode-status
                 d/decode-send-result d/encode-send-result
                 d/decode-stream-frame d/encode-stream-frame
                 d/encode-send-params d/decode-send-params]]
        (is (or (nil? (f dl v)) (some? (f dl v)))
            (str "should not throw: " dl " " (pr-str v)))))))
