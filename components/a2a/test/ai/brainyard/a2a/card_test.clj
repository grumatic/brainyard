;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.a2a.card-test
  "Unit tests for Agent Card parsing, endpoint resolution, peer identity
   and version negotiation. Pure data — no network."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [ai.brainyard.a2a.core.card :as card]
            [ai.brainyard.a2a.core.methods :as methods]
            [ai.brainyard.a2a.core.schema :as schema]))

(def a-card
  {:name            "peer-b"
   :description     "A test peer"
   :url             "https://peer-b.example/a2a"
   :version         "1.4.2"
   :protocolVersion "0.3"
   :capabilities    {:streaming true :pushNotifications false}
   :skills          [{:id "planner" :name "Planner"
                      :description "Plans things"}
                     {:id "reviewer" :name "Reviewer"}]})

;; =============================================================================
;; URL normalization + discovery
;; =============================================================================

(deftest base-url-test
  (testing "trims whitespace and trailing slashes"
    (is (= "https://x.example" (card/base-url "  https://x.example  ")))
    (is (= "https://x.example" (card/base-url "https://x.example/")))
    (is (= "https://x.example" (card/base-url "https://x.example///"))))

  (testing "blank input yields nil"
    (is (nil? (card/base-url nil)))
    (is (nil? (card/base-url "")))
    (is (nil? (card/base-url "   ")))))

(deftest card-url-test
  (testing "appends the well-known path to a base URL"
    (is (= (str "https://x.example" methods/AGENT_CARD_PATH)
           (card/card-url "https://x.example")))
    (is (= (str "https://x.example" methods/AGENT_CARD_PATH)
           (card/card-url "https://x.example/"))))

  (testing "is IDEMPOTENT when handed the card URL itself"
    ;; A user pasting what their browser showed them should not get a
    ;; doubled path.
    (let [u (str "https://x.example" methods/AGENT_CARD_PATH)]
      (is (= u (card/card-url u)))
      (is (= 1 (count (re-seq #"/\.well-known/" (card/card-url u)))))))

  (testing "nil for blank input"
    (is (nil? (card/card-url nil)))))

;; =============================================================================
;; Parsing
;; =============================================================================

(deftest parse-test
  (testing "a valid card parses"
    (is (= a-card (:card (card/parse a-card))))
    (is (nil? (:error (card/parse a-card)))))

  (testing "a non-map is an error, not an exception"
    (is (some? (:error (card/parse "nope"))))
    (is (some? (:error (card/parse nil)))))

  (testing "a card with no :name is an error"
    (is (some? (:error (card/parse (dissoc a-card :name)))))
    (is (some? (:error (card/parse (assoc a-card :name "   "))))))

  (testing "parse NEVER throws — a malformed peer is an ordinary failure"
    (doseq [bad [nil "" 42 [] {} {:name 1} {:name "x" :skills "not-a-vector"}]]
      (is (map? (card/parse bad)) (str "should return a map for: " (pr-str bad)))))

  (testing "unknown top-level keys are preserved (extensions must survive)"
    (let [{:keys [card]} (card/parse (assoc a-card :futureField {:a 1}))]
      (is (= {:a 1} (:futureField card))))))

(deftest skills-test
  (testing "skills returns the declared vector"
    (is (= 2 (count (card/skills a-card)))))

  (testing "an empty vector when none are declared"
    (is (= [] (card/skills (dissoc a-card :skills)))))

  (testing "find-skill locates by id and returns nil when absent"
    (is (= "Planner" (:name (card/find-skill a-card "planner"))))
    (is (nil? (card/find-skill a-card "nonexistent"))))

  (testing "find-skill accepts a KEYWORD id — brainyard's native form"
    (is (= "Planner" (:name (card/find-skill a-card :planner))))))

;; =============================================================================
;; Endpoint resolution
;; =============================================================================

(deftest jsonrpc-endpoint-test
  (testing "an absent :preferredTransport means the primary URL is JSON-RPC"
    ;; JSONRPC is the spec default, so omitting the field is a positive
    ;; statement, not a missing one.
    (is (= "https://peer-b.example/a2a"
           (card/jsonrpc-endpoint (dissoc a-card :preferredTransport)))))

  (testing "an explicit JSONRPC preferred transport uses the primary URL"
    (is (= "https://peer-b.example/a2a"
           (card/jsonrpc-endpoint (assoc a-card :preferredTransport "JSONRPC")))))

  (testing "transport matching is case-insensitive"
    (is (= "https://peer-b.example/a2a"
           (card/jsonrpc-endpoint (assoc a-card :preferredTransport "jsonrpc")))))

  (testing "a gRPC-first agent is still reached via additionalInterfaces"
    (is (= "https://peer-b.example/rpc"
           (card/jsonrpc-endpoint
            (assoc a-card
                   :preferredTransport "GRPC"
                   :additionalInterfaces [{:url "https://peer-b.example/grpc"
                                           :transport "GRPC"}
                                          {:url "https://peer-b.example/rpc"
                                           :transport "JSONRPC"}])))))

  (testing "nil when the peer offers no JSON-RPC binding at all"
    (is (nil? (card/jsonrpc-endpoint
               (assoc a-card
                      :preferredTransport "GRPC"
                      :additionalInterfaces [{:url "https://peer-b.example/grpc"
                                              :transport "GRPC"}]))))))

;; =============================================================================
;; Peer identity — the call-chain token
;; =============================================================================

(deftest peer-agent-id-test
  (testing "identity is <endpoint>#<skill-id>"
    (is (= "https://peer-b.example/a2a#planner"
           (card/peer-agent-id a-card "planner"))))

  (testing "two DIFFERENT peers exposing the SAME skill name do not collide"
    ;; This is the property the cross-process cycle detector depends on.
    ;; If these collapsed, the guard would fire on the wrong pair — or,
    ;; worse, fail to fire on the right one.
    (let [b (assoc a-card :url "https://peer-b.example/a2a")
          c (assoc a-card :url "https://peer-c.example/a2a")]
      (is (not= (card/peer-agent-id b "planner")
                (card/peer-agent-id c "planner")))))

  (testing "the same peer + skill is STABLE across calls"
    ;; Equally load-bearing: an id that varied per call would defeat the
    ;; cycle check entirely.
    (is (= (card/peer-agent-id a-card "planner")
           (card/peer-agent-id a-card "planner"))))

  (testing "degrades to a usable id when the card has no URL"
    (is (str/includes? (card/peer-agent-id (dissoc a-card :url) "planner")
                       "#planner")))

  (testing "a keyword skill-id does not smuggle a colon into the chain token"
    (is (= "https://peer-b.example/a2a#planner"
           (card/peer-agent-id a-card :planner)))))

;; =============================================================================
;; Capabilities
;; =============================================================================

(deftest capabilities-test
  (testing "declared capabilities read true"
    (is (card/supports? a-card :streaming)))

  (testing "explicitly-false capabilities read false"
    (is (not (card/supports? a-card :pushNotifications))))

  (testing "ABSENT capabilities read false, not true"
    ;; A2A capability negotiation is opt-in: anything a card does not
    ;; claim must be treated as unavailable.
    (is (not (card/supports? a-card :stateTransitionHistory)))
    (is (not (card/supports? (dissoc a-card :capabilities) :streaming))))

  (testing "extended-card? reads the flag"
    (is (not (card/extended-card? a-card)))
    (is (card/extended-card?
         (assoc a-card :supportsAuthenticatedExtendedCard true)))))

;; =============================================================================
;; Version negotiation
;; =============================================================================

(deftest parse-version-test
  (testing "Major.Minor and Major.Minor.Patch both parse"
    (is (= [0 3] (card/parse-version "0.3")))
    (is (= [1 2] (card/parse-version "1.2.7")))
    (is (= [1 0] (card/parse-version "1"))))

  (testing "unparseable input yields nil"
    (is (nil? (card/parse-version nil)))
    (is (nil? (card/parse-version "")))
    (is (nil? (card/parse-version "abc")))))

(deftest compatible-test
  (testing "same major is compatible in either minor direction"
    (is (card/compatible? "0.3" "0.3"))
    (is (card/compatible? "0.9" "0.3"))
    (is (card/compatible? "0.1" "0.3")))

  (testing "a different major is NOT compatible"
    (is (not (card/compatible? "1.0" "0.3")))
    (is (not (card/compatible? "0.3" "1.0"))))

  (testing "an absent or unparseable peer version is treated as compatible"
    ;; :protocolVersion is optional and plenty of live cards omit it.
    ;; Refusing to talk to them would be stricter than the protocol.
    (is (card/compatible? nil))
    (is (card/compatible? ""))
    (is (card/compatible? "not-a-version")))

  (testing "defaults to our own PROTOCOL_VERSION"
    (is (card/compatible? methods/PROTOCOL_VERSION))))

(deftest version-error-test
  (testing "nil for a compatible card"
    (is (nil? (card/version-error a-card)))
    (is (nil? (card/version-error (dissoc a-card :protocolVersion)))))

  (testing "an error map naming both versions for an incompatible one"
    (let [{:keys [error]} (card/version-error (assoc a-card :protocolVersion "9.0"))]
      (is (some? error))
      (is (str/includes? error "9.0"))
      (is (str/includes? error methods/PROTOCOL_VERSION)))))

;; =============================================================================
;; Construction
;; =============================================================================

(deftest build-test
  (let [built (card/build {:name "brainyard"
                           :description "by"
                           :url "https://localhost:41241/a2a/"
                           :version "0.2.0"
                           :capabilities {:streaming true}
                           :skills [(card/skill {:id "explore-agent"
                                                 :name "Explore"
                                                 :description "Explores"})]})]
    (testing "the built card validates against the schema"
      (is (schema/valid? schema/AgentCard built)))

    (testing "protocolVersion is stamped from the single source"
      (is (= methods/PROTOCOL_VERSION (:protocolVersion built))))

    (testing "preferredTransport is declared"
      (is (= "JSONRPC" (:preferredTransport built))))

    (testing "the URL is normalized"
      (is (= "https://localhost:41241/a2a" (:url built))))

    (testing "nil-valued optional keys are OMITTED, not emitted as null"
      ;; A card is a public document; empty keys read as broken.
      (let [minimal (card/build {:name "x" :skills []})]
        (is (not (contains? minimal :description)))
        (is (not (contains? minimal :url)))
        (is (not (contains? minimal :provider)))
        (is (not (contains? minimal :supportsAuthenticatedExtendedCard)))))))

(deftest skill-test
  (testing "a KEYWORD id loses its colon, not its name"
    ;; !tool-defs is keyed by keywords, so this is the normal case, not an
    ;; edge case. A bare (str :explore-agent) would put ":explore-agent"
    ;; into the public Agent Card and into every call-chain token.
    (let [s (card/skill {:id :explore-agent :name "Explore"})]
      (is (= "explore-agent" (:id s)))
      (is (not (str/starts-with? (:id s) ":")))
      (is (= "Explore" (:name s)))
      (is (schema/valid? schema/AgentSkill s))))

  (testing "a NAMESPACED keyword keeps its namespace"
    ;; Instance ids are :<defagent-type>/<suffix>; dropping the namespace
    ;; would collapse distinct agents onto one skill id.
    (is (= "coact-agent/crimson-parrot-42"
           (:id (card/skill {:id :coact-agent/crimson-parrot-42 :name "C"})))))

  (testing "a string id passes through unchanged"
    (is (= "explore-agent" (:id (card/skill {:id "explore-agent" :name "E"})))))

  (testing "empty collections are omitted rather than emitted empty"
    (let [s (card/skill {:id "a" :name "A" :tags [] :examples nil})]
      (is (not (contains? s :tags)))
      (is (not (contains? s :examples)))))

  (testing "populated collections are carried"
    (let [s (card/skill {:id "a" :name "A" :tags ["x"] :input-modes ["text/plain"]})]
      (is (= ["x"] (:tags s)))
      (is (= ["text/plain"] (:inputModes s))))))
