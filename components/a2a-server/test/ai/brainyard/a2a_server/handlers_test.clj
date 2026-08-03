;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.a2a-server.handlers-test
  "Tests for the A2A server: dispatch, auth, the inbound cycle/depth guard,
   and SSE framing.

   Driven against a STUB service map — no agent runtime — which is the
   payoff of injecting capabilities instead of depending on
   `components/agent`. The whole protocol surface is exercised here; the
   agent-side wiring is tested separately."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [ai.brainyard.a2a.interface :as a2a]
            [ai.brainyard.a2a-server.core.handlers :as handlers]
            [ai.brainyard.a2a-server.core.http :as http]
            [ai.brainyard.a2a-server.core.sse :as sse]
            [ai.brainyard.a2a-server.interface :as server]))

;; =============================================================================
;; Stub service
;; =============================================================================

(def a-card
  (a2a/build-card {:name "stub-by" :url "http://127.0.0.1:1/a2a"
                   :capabilities {:streaming true}
                   :skills [(a2a/build-skill {:id "echo" :name "Echo"})]}))

(defn stub-service
  [& {:keys [ask-fn tasks max-depth]
      :or   {max-depth 3}}]
  {:card-fn     (constantly a-card)
   :ask-fn      (or ask-fn (fn [{:keys [text]}] {:answer (str "echo: " text)
                                                 :state :completed}))
   :auth-token  "test-token"
   :max-depth   max-depth
   :get-task-fn (fn [id] (get tasks id))
   :cancel-fn   (fn [id] (when-let [t (get tasks id)]
                           (assoc-in t [:status :state] "canceled")))})

(defn- send-req
  ([service text] (send-req service text nil))
  ([service text metadata]
   (server/dispatch service
                    (a2a/request 1 "message/send"
                                 {:message (cond-> {:messageId "m" :role "user"
                                                    :kind "message"
                                                    :parts [(a2a/text-part text)]}
                                             metadata (assoc :metadata metadata))}))))

;; =============================================================================
;; message/send
;; =============================================================================

(deftest message-send-test
  (testing "a well-formed send returns a Task carrying the answer"
    (let [r (send-req (stub-service) "hello")]
      (is (nil? (:error r)))
      (is (= "task" (get-in r [:result :kind])))
      (is (= "completed" (get-in r [:result :status :state])))
      (is (= "echo: hello"
             (a2a/message-text (get-in r [:result :status :message]))))))

  (testing "the returned Task validates against the schema"
    (let [r (send-req (stub-service) "hello")]
      (is (a2a/valid? a2a/Task (:result r)))))

  (testing "an empty message is invalid params, not an internal error"
    (let [r (send-req (stub-service) "")]
      (is (= (a2a/error-code :invalid-params) (get-in r [:error :code])))))

  (testing "an ask-fn failure is reported as internal, not as the caller's fault"
    (let [r (send-req (stub-service :ask-fn (fn [_] {:error "agent exploded"})) "hi")]
      (is (= (a2a/error-code :internal-error) (get-in r [:error :code])))
      (is (str/includes? (get-in r [:error :data :detail]) "agent exploded")))))

;; =============================================================================
;; Dual dialect — serving v1.0 clients
;; =============================================================================

(defn- send-req-v1
  "A v1.0 SendMessage: PascalCase method, ROLE_USER, bare parts."
  [service text]
  (server/dispatch service
                   (a2a/request 1 "SendMessage"
                                {:message {:messageId "m" :role "ROLE_USER"
                                           :parts [{:text text}]}})
                   :v1.0))

(deftest serves-v1-clients-test
  (let [r (send-req-v1 (stub-service) "hello")]
    (testing "a v1.0 SendMessage is served"
      (is (nil? (:error r))))

    (testing "the result is WRAPPED in the v1.0 one-of"
      ;; v0.3 returns the Task bare; v1.0 returns {task: …}.
      (is (contains? (:result r) :task))
      (is (not (contains? (:result r) :status))))

    (testing "the state is a proto enum, and :kind is gone"
      (let [t (get-in r [:result :task])]
        (is (= "TASK_STATE_COMPLETED" (get-in t [:status :state])))
        (is (not (contains? t :kind)))))

    (testing "the reply message uses ROLE_AGENT and bare parts"
      (let [m (get-in r [:result :task :status :message])]
        (is (= "ROLE_AGENT" (:role m)))
        (is (= [{:text "echo: hello"}] (:parts m)))
        (is (not (contains? m :kind)))))))

(deftest v1-request-is-decoded-before-the-service-sees-it-test
  (let [!seen (atom nil)]
    (send-req-v1 (stub-service :ask-fn (fn [req] (reset! !seen req)
                                         {:answer "ok" :state :completed}))
                 "decoded?")
    (testing "ask-fn receives plain text, never a dialect-shaped payload"
      ;; The service is agent code; it must never learn a wire dialect exists.
      (is (= "decoded?" (:text @!seen))))))

(deftest v03-clients-still-served-test
  (testing "the v0.3 shape is unaffected by v1.0 support"
    (let [r (send-req (stub-service) "hello")]
      (is (nil? (:error r)))
      (is (= "task" (get-in r [:result :kind])))
      (is (= "completed" (get-in r [:result :status :state])))
      (is (not (contains? (:result r) :task)) "v0.3 results are NOT wrapped"))))

(deftest dialect-resolution-test
  (testing "the header selects the dialect"
    (is (= [:v1.0 :message-send] (handlers/resolve-dialect "1.0" "SendMessage")))
    (is (= [:v0.3 :message-send] (handlers/resolve-dialect "0.3" "message/send"))))

  (testing "the METHOD NAME wins when the header is missing"
    ;; Deliberately more liberal than the reference SDK, which rejects a
    ;; missing header outright. The two vocabularies are disjoint, so the
    ;; name determines the dialect exactly — there is no ambiguity to
    ;; resolve wrongly, and a client that forgot the header gets served.
    (is (= [:v1.0 :message-send] (handlers/resolve-dialect nil "SendMessage")))
    (is (= [:v0.3 :message-send] (handlers/resolve-dialect nil "message/send"))))

  (testing "the method name also wins when the header CONTRADICTS it"
    (is (= [:v1.0 :message-send] (handlers/resolve-dialect "0.3" "SendMessage")))
    (is (= [:v0.3 :message-send] (handlers/resolve-dialect "1.0" "message/send"))))

  (testing "a method in neither vocabulary is unresolvable"
    (is (nil? (handlers/resolve-dialect "1.0" "nonsense/method")))))

(deftest v1-cycle-guard-still-fires-test
  (let [!called (atom 0)
        service (stub-service :ask-fn (fn [_] (swap! !called inc) {:answer "x"}))
        r (server/dispatch service
                           (a2a/request 1 "SendMessage"
                                        {:message {:messageId "m" :role "ROLE_USER"
                                                   :parts [{:text "hi"}]
                                                   :metadata {a2a/CHAIN_KEY [(a2a/node-id)]}}})
                           :v1.0)]
    (testing "the cross-process guard is dialect-independent"
      (is (some? (:error r)))
      (is (zero? @!called)))))

;; =============================================================================
;; The inbound cycle guard — the payoff
;; =============================================================================

(deftest cycle-is-refused-before-any-work-test
  (let [!called (atom 0)
        service (stub-service :ask-fn (fn [_] (swap! !called inc)
                                        {:answer "should not happen"}))
        ;; A chain that already contains THIS node.
        md      {a2a/CHAIN_KEY ["by-node:other" (a2a/node-id)]
                 a2a/DEPTH_KEY 2}
        r       (send-req service "hi" md)]

    (testing "the request is refused"
      (is (some? (:error r)))
      (is (str/includes? (get-in r [:error :data :detail]) "cycle")))

    (testing "ask-fn was NEVER called"
      ;; The entire point: refusing after spending an LLM turn would make
      ;; the guard pointless.
      (is (zero? @!called)))))

(deftest depth-limit-is-refused-test
  (let [!called (atom 0)
        service (stub-service :ask-fn (fn [_] (swap! !called inc) {:answer "x"})
                              :max-depth 3)
        r       (send-req service "hi" {a2a/DEPTH_KEY 3})]
    (is (some? (:error r)))
    (is (str/includes? (get-in r [:error :data :detail]) "depth"))
    (is (zero? @!called) "no work is done past the depth limit")))

(deftest stranger-with-no-metadata-is-allowed-test
  (testing "a non-brainyard client (no chain metadata) is served normally"
    ;; Absence must not be treated as suspicious — most A2A clients will
    ;; never send our extension.
    (let [r (send-req (stub-service) "hi")]
      (is (nil? (:error r)))
      (is (= "completed" (get-in r [:result :status :state]))))))

(deftest chain-guard-reads-decoded-metadata-test
  (testing "the guard fires on keyword-keyed metadata, as decoded from JSON"
    ;; a2a/decode keywordizes, so a handler sees :ai.brainyard/call-chain.
    ;; Reading only the string form would mean the guard never fires.
    (let [md {(keyword a2a/CHAIN_KEY) [(a2a/node-id)]}
          r  (send-req (stub-service) "hi" md)]
      (is (some? (:error r))))))

;; =============================================================================
;; Tasks
;; =============================================================================

(deftest tasks-get-test
  (let [task {:id "t-1" :kind "task" :status {:state "working"}}
        service (stub-service :tasks {"t-1" task})]

    (testing "a known task comes back"
      (is (= task (:result (server/dispatch service (a2a/request 1 "tasks/get"
                                                                 {:id "t-1"}))))))

    (testing "an unknown task is not-found"
      (is (= (a2a/error-code :task-not-found)
             (get-in (server/dispatch service (a2a/request 1 "tasks/get" {:id "nope"}))
                     [:error :code]))))

    (testing "missing :id is invalid params"
      (is (= (a2a/error-code :invalid-params)
             (get-in (server/dispatch service (a2a/request 1 "tasks/get" {}))
                     [:error :code]))))))

(deftest not-found-is-not-an-enumeration-oracle-test
  (testing "unknown and unauthorized tasks are INDISTINGUISHABLE"
    ;; The spec requires this: otherwise a caller can probe for which task
    ;; ids exist by diffing the error responses.
    (let [service (stub-service :tasks {"mine" {:id "mine" :kind "task"
                                                :status {:state "working"}}})
          r1 (server/dispatch service (a2a/request 1 "tasks/get" {:id "does-not-exist"}))
          r2 (server/dispatch service (a2a/request 1 "tasks/get" {:id "someone-elses"}))]
      (is (= r1 r2))
      (is (not (contains? (:error r1) :data))
          "the response must not carry the id back")))

  (testing "cancel of an unknown task is the same not-found"
    (let [service (stub-service :tasks {})]
      (is (= (a2a/error-code :task-not-found)
             (get-in (server/dispatch service (a2a/request 1 "tasks/cancel" {:id "x"}))
                     [:error :code]))))))

(deftest tasks-cancel-test
  (let [service (stub-service :tasks {"t-1" {:id "t-1" :kind "task"
                                             :status {:state "working"}}})
        r (server/dispatch service (a2a/request 1 "tasks/cancel" {:id "t-1"}))]
    (is (= "canceled" (get-in r [:result :status :state])))))

;; =============================================================================
;; Unimplemented optional methods
;; =============================================================================

(deftest optional-methods-answer-unsupported-not-method-not-found-test
  (testing "an unimplemented OPTIONAL method is UnsupportedOperation"
    ;; The method exists in the protocol; we simply do not offer it.
    ;; MethodNotFound would say the opposite.
    (doseq [m ["tasks/list"
               "tasks/pushNotificationConfig/set"
               "tasks/pushNotificationConfig/get"
               "tasks/pushNotificationConfig/list"
               "tasks/pushNotificationConfig/delete"]]
      (is (= (a2a/error-code :unsupported-operation)
             (get-in (server/dispatch (stub-service) (a2a/request 1 m {}))
                     [:error :code]))
          (str m " should be unsupported-operation"))))

  (testing "a method outside the protocol IS method-not-found"
    (is (= (a2a/error-code :method-not-found)
           (get-in (server/dispatch (stub-service) (a2a/request 1 "nonsense/method" {}))
                   [:error :code]))))

  (testing "an unconfigured extended card says so specifically"
    (is (= (a2a/error-code :extended-card-not-configured)
           (get-in (server/dispatch (stub-service)
                                    (a2a/request 1 "agent/getAuthenticatedExtendedCard" {}))
                   [:error :code])))))

(deftest handler-exception-does-not-escape-test
  (testing "a throwing ask-fn becomes an internal error, not a crash"
    (let [service (stub-service :ask-fn (fn [_] (throw (ex-info "boom" {}))))
          r (send-req service "hi")]
      (is (= (a2a/error-code :internal-error) (get-in r [:error :code]))))))

;; =============================================================================
;; Streaming classification
;; =============================================================================

(deftest streaming-method-classification-test
  (is (server/streaming-method? "message/stream"))
  (is (server/streaming-method? "tasks/resubscribe"))
  (is (not (server/streaming-method? "message/send")))
  (is (not (server/streaming-method? "tasks/get"))))

;; =============================================================================
;; SSE framing
;; =============================================================================

(defn- collect-frames [f]
  (let [!out (atom [])]
    (f #(swap! !out conj %))
    @!out))

(defn- parse-frames
  "Decode the JSON-RPC envelope out of each `data:` frame."
  [frames]
  (->> frames
       (remove #(str/starts-with? % ":"))
       (map #(-> % (str/replace #"^data: " "") (str/replace #"\n\n$" "")))
       (map a2a/decode)))

(deftest sse-turn-frames-test
  (let [service (stub-service)
        frames  (collect-frames
                 #(sse/stream-turn! service 1
                                    {:message {:messageId "m" :role "user"
                                               :parts [(a2a/text-part "hi")]}}
                                    %))
        decoded (parse-frames frames)]

    (testing "every frame is a full JSON-RPC envelope, not a bare object"
      ;; A bare object parses on a lenient client and fails on a strict one.
      (is (every? #(= "2.0" (:jsonrpc %)) decoded))
      (is (every? #(contains? % :result) decoded)))

    (testing "the FIRST frame carries the task id"
      ;; Without it, a client that drops mid-turn has no id to resubscribe with.
      (is (= "task" (get-in (first decoded) [:result :kind])))
      (is (some? (get-in (first decoded) [:result :id])))
      (is (= "submitted" (get-in (first decoded) [:result :status :state]))))

    (testing "the LAST frame is a terminal status marked final"
      (let [l (last decoded)]
        (is (= "status-update" (get-in l [:result :kind])))
        (is (= "completed" (get-in l [:result :status :state])))
        (is (true? (get-in l [:result :final])))))

    (testing "frames end with the SSE terminator"
      (is (every? #(str/ends-with? % "\n\n") frames)))))

(deftest sse-streams-chunks-when-the-service-reports-them-test
  (let [service (stub-service
                 :ask-fn (fn [{:keys [on-chunk]}]
                           (on-chunk "part " "part ")
                           (on-chunk "two" "part two")
                           {:answer "part two" :state :completed}))
        decoded (parse-frames
                 (collect-frames
                  #(sse/stream-turn! service 1
                                     {:message {:messageId "m" :role "user"
                                                :parts [(a2a/text-part "hi")]}}
                                     %)))]
    (testing "each chunk becomes a working status-update"
      (let [working (filter #(= "working" (get-in % [:result :status :state])) decoded)]
        (is (= 2 (count working)))
        (is (= "part " (a2a/message-text (get-in (first working) [:result :status :message]))))
        (is (= "part two" (a2a/message-text (get-in (second working) [:result :status :message]))))))))

(deftest sse-cycle-refusal-emits-an-error-frame-test
  (let [!called (atom 0)
        service (stub-service :ask-fn (fn [_] (swap! !called inc) {:answer "x"}))
        r       (atom nil)
        frames  (collect-frames
                 #(reset! r (sse/stream-turn!
                             service 1
                             {:message {:messageId "m" :role "user"
                                        :parts [(a2a/text-part "hi")]
                                        :metadata {a2a/CHAIN_KEY [(a2a/node-id)]}}}
                             %)))]
    (testing "the stream refuses and does no work"
      (is (:refused @r))
      (is (zero? @!called)))

    (testing "the refusal is delivered as a JSON-RPC error frame"
      (let [d (first (parse-frames frames))]
        (is (some? (:error d)))))))

(deftest sse-interrupted-is-not-marked-final-test
  (let [service (stub-service :ask-fn (fn [_] {:answer "which file?"
                                               :state :input-required}))
        decoded (parse-frames
                 (collect-frames
                  #(sse/stream-turn! service 1
                                     {:message {:messageId "m" :role "user"
                                                :parts [(a2a/text-part "hi")]}}
                                     %)))]
    (testing "an interrupted turn does NOT set final:true"
      ;; The task stays open awaiting the client; final would tell them to
      ;; stop listening.
      (let [l (last decoded)]
        (is (= "input-required" (get-in l [:result :status :state])))
        (is (not (true? (get-in l [:result :final]))))))))

(deftest sse-client-disconnect-is-not-fatal-test
  (testing "a write that throws stops framing without propagating"
    ;; A client hanging up mid-stream is normal, not an error.
    (let [service (stub-service)
          r (sse/stream-turn! service 1
                              {:message {:messageId "m" :role "user"
                                         :parts [(a2a/text-part "hi")]}}
                              (fn [_] (throw (java.io.IOException. "broken pipe"))))]
      (is (map? r))
      (is (zero? (:frames r))))))

;; =============================================================================
;; Service validation
;; =============================================================================

(deftest service-validation-test
  (testing "a complete service validates"
    (is (empty? (server/validate-service (stub-service)))))

  (testing "a MISSING TOKEN is a hard failure"
    ;; Inbound A2A runs prompts against this workspace. There is no
    ;; unauthenticated mode, because that flag ends up set in production.
    (let [problems (server/validate-service (dissoc (stub-service) :auth-token))]
      (is (= 1 (count problems)))
      (is (str/includes? (first problems) "refuses to bind"))))

  (testing "a blank token is also refused"
    (is (seq (server/validate-service (assoc (stub-service) :auth-token "   ")))))

  (testing "card-fn and ask-fn are required"
    (is (seq (server/validate-service (dissoc (stub-service) :card-fn))))
    (is (seq (server/validate-service (dissoc (stub-service) :ask-fn)))))

  (testing "start! refuses rather than binding an invalid service"
    (let [r (server/start! {} {:port 0})]
      (is (some? (:error r)))
      (is (str/includes? (:error r) "cannot start")))))

(deftest constant-time-compare-test
  (testing "authorized? accepts the right token and rejects others"
    ;; Exercised through the private helper's behaviour via the public
    ;; predicate: correctness first, timing properties are a property of
    ;; MessageDigest/isEqual.
    (is (true? (#'http/constant-time= "abc" "abc")))
    (is (false? (boolean (#'http/constant-time= "abc" "abd"))))
    (is (false? (boolean (#'http/constant-time= "abc" "ab"))))
    (is (false? (boolean (#'http/constant-time= nil "abc"))))
    (is (false? (boolean (#'http/constant-time= "abc" nil))))))
