;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.a2a-client.registry-test
  "Unit tests for the peer registry, peer records and result normalization.

   No network: peers are registered directly from a prebuilt card, which
   is exactly what `register-peer!` exists for. `connect!`'s network path
   is covered by the loopback E2E in Phase 5."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [ai.brainyard.a2a-client.core.client :as client]
            [ai.brainyard.a2a-client.core.registry :as registry]))

(def a-card
  {:name            "peer-b"
   :url             "https://peer-b.example/a2a"
   :protocolVersion "0.3"
   :capabilities    {:streaming true :pushNotifications false}
   :skills          [{:id "planner" :name "Planner"}
                     {:id "reviewer" :name "Reviewer"}]})

(defn- fresh-registry [f]
  (registry/reset-peers!)
  (f)
  (registry/reset-peers!))

(use-fixtures :each fresh-registry)

(defn- register-test-peer!
  ([] (register-test-peer! "b" a-card "sk-secret"))
  ([nm card auth]
   (registry/register-peer!
    (client/make-peer {:name nm :url (:url card) :card card :auth auth}))))

;; =============================================================================
;; Peer records
;; =============================================================================

(deftest make-peer-test
  (testing "the endpoint is resolved from the card"
    (is (= "https://peer-b.example/a2a"
           (:endpoint (client/make-peer {:name "b" :url "https://peer-b.example/a2a"
                                         :card a-card})))))

  (testing "an explicit endpoint wins over the card"
    (is (= "https://override/rpc"
           (:endpoint (client/make-peer {:name "b" :url "https://peer-b.example/a2a"
                                         :card a-card
                                         :endpoint "https://override/rpc"})))))

  (testing "auth is normalized on the way in"
    (is (= {:type :bearer :token "sk"}
           (:auth (client/make-peer {:name "b" :url "https://x" :auth "sk"})))))

  (testing "each peer gets its OWN request-id source"
    ;; Sharing a counter across peers would make concurrent conversations
    ;; interfere.
    (let [p1 (client/make-peer {:name "a" :url "https://x"})
          p2 (client/make-peer {:name "b" :url "https://y"})]
      ((:next-id p1)) ((:next-id p1)) ((:next-id p1))
      (is (= 1 ((:next-id p2)))))))

(deftest describe-peer-redacts-test
  (testing "a peer summary NEVER carries the credential"
    ;; This map is logged, listed by a2a$list, and rendered into LLM
    ;; context.
    (let [peer (client/make-peer {:name "b" :url (:url a-card)
                                  :card a-card :auth "sk-SUPERSECRET"})
          d    (client/describe-peer peer)]
      (is (not (str/includes? (pr-str d) "SUPERSECRET")))
      (is (= "bearer" (:auth d)))))

  (testing "the summary carries the useful facts"
    (let [d (client/describe-peer
             (client/make-peer {:name "b" :url (:url a-card) :card a-card}))]
      (is (= "b" (:name d)))
      (is (= ["planner" "reviewer"] (:skills d)))
      (is (true? (:streaming d)))
      (is (= "peer-b" (:agent-name d))))))

;; =============================================================================
;; Registry
;; =============================================================================

(deftest register-lookup-test
  (testing "a registered peer is retrievable and listed"
    (register-test-peer!)
    (is (some? (registry/get-peer "b")))
    (is (= 1 (count (registry/list-peers)))))

  (testing "an unknown peer is nil"
    (is (nil? (registry/get-peer "nope"))))

  (testing "describe-peers redacts every entry"
    (register-test-peer!)
    (is (not (str/includes? (pr-str (registry/describe-peers)) "sk-secret")))))

(deftest disconnect-test
  (testing "disconnect forgets the peer"
    (register-test-peer!)
    (is (:disconnected (registry/disconnect! "b")))
    (is (nil? (registry/get-peer "b"))))

  (testing "disconnecting an unknown peer is an error, not a silent no-op"
    (is (some? (:error (registry/disconnect! "nope"))))))

(deftest peer-name-validation-test
  (testing "the name regex accepts identifier-safe names"
    (doseq [n ["b" "peer-b" "a1" "my-peer-2"]]
      (is (re-matches registry/peer-name-re n) (str "should accept: " n))))

  (testing "it rejects names that would break the a2a$<peer>$<skill> tool id"
    (doseq [n ["" "-b" "1b" "Peer" "peer_b" "peer b" "peer$b" "peer.b"]]
      (is (not (re-matches registry/peer-name-re n)) (str "should reject: " n))))

  (testing "connect! rejects a bad name before touching the network"
    (let [{:keys [error]} (registry/connect! {:name "Bad Name"
                                              :url "https://x.example"})]
      (is (some? error))
      (is (str/includes? error "a2a$"))))

  (testing "connect! requires a url"
    (is (some? (:error (registry/connect! {:name "b"}))))))

;; =============================================================================
;; Skill resolution
;; =============================================================================

(deftest resolve-skill-test
  (testing "resolves to peer, skill and the call-chain token"
    (register-test-peer!)
    (let [{:keys [peer skill agent-id error]} (registry/resolve-skill "b" "planner")]
      (is (nil? error))
      (is (= "b" (:name peer)))
      (is (= "Planner" (:name skill)))
      (is (= "https://peer-b.example/a2a#planner" agent-id))))

  (testing "an unknown skill errors AND lists what is available"
    (register-test-peer!)
    (let [{:keys [error]} (registry/resolve-skill "b" "nope")]
      (is (some? error))
      (is (str/includes? error "planner"))
      (is (str/includes? error "reviewer"))))

  (testing "an unknown peer errors"
    (is (some? (:error (registry/resolve-skill "nope" "planner")))))

  (testing "call-chain tokens differ across peers exposing the SAME skill"
    ;; The property the cross-process cycle guard depends on.
    (register-test-peer! "b" a-card nil)
    (register-test-peer! "c" (assoc a-card :url "https://peer-c.example/a2a") nil)
    (is (not= (:agent-id (registry/resolve-skill "b" "planner"))
              (:agent-id (registry/resolve-skill "c" "planner"))))))

;; =============================================================================
;; Result normalization
;; =============================================================================

(deftest result->outcome-test
  (testing "a Task result normalizes"
    (let [o (client/result->outcome
             {:id "t-1" :contextId "c-1" :kind "task"
              :status {:state "completed"
                       :message {:messageId "m" :role "agent"
                                 :parts [{:kind "text" :text "done"}]}}})]
      (is (= "done" (:answer o)))
      (is (= "t-1" (:task-id o)))
      (is (= "c-1" (:context-id o)))
      (is (= :completed (:state o)))))

  (testing "a Task with no status message falls back to artifact text"
    (let [o (client/result->outcome
             {:id "t-1" :status {:state "completed"}
              :artifacts [{:artifactId "a" :parts [{:kind "text" :text "from-artifact"}]}]})]
      (is (= "from-artifact" (:answer o)))))

  (testing "a bare Message normalizes, and counts as COMPLETED"
    ;; An inline answer IS the finished work — there is nothing to poll.
    (let [o (client/result->outcome
             {:messageId "m" :role "agent" :kind "message"
              :parts [{:kind "text" :text "inline answer"}]})]
      (is (= "inline answer" (:answer o)))
      (is (= :completed (:state o)))
      (is (nil? (:task-id o)))))

  (testing "shape is trusted over :kind, which servers omit"
    (let [o (client/result->outcome {:id "t" :status {:state "working"}})]
      (is (= :working (:state o)))
      (is (= "t" (:task-id o)))))

  (testing "an unrecognized shape is an error, not a silent empty answer"
    (is (some? (:error (client/result->outcome {:weird true}))))
    (is (some? (:error (client/result->outcome nil)))))

  (testing "the raw object is always carried through"
    (let [raw {:id "t" :status {:state "working"}}]
      (is (= raw (:raw (client/result->outcome raw)))))))

(deftest skill-prompt-test
  (testing "a skill id is prefixed"
    (is (= "[skill: planner]\ndo it" (client/skill-prompt "planner" "do it"))))

  (testing "a blank skill id leaves the text alone"
    (is (= "do it" (client/skill-prompt nil "do it")))
    (is (= "do it" (client/skill-prompt "" "do it")))))
