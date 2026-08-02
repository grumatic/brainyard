;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.a2a.chain-test
  "Unit tests for the cross-process call chain — the one invariant with no
   in-process equivalent, and the one whose failure mode is an unbounded
   recursion of paid LLM turns.

   The multi-hop simulations at the bottom are the point of this file. An
   earlier design passed every unit-level test while being unable to detect
   `A -> B -> A` at all, because a node's local id and its remote id are
   different strings; only end-to-end hop simulation catches that."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [ai.brainyard.a2a.core.chain :as chain]))

;; =============================================================================
;; Node identity
;; =============================================================================

(deftest node-id-test
  (testing "a node id is stable within a process"
    (is (= (chain/node-id) (chain/node-id))))

  (testing "it is non-blank and recognizably ours"
    (is (str/starts-with? (chain/node-id) "by-node:")))

  (testing "it can be overridden for determinism"
    (let [orig (chain/node-id)]
      (try
        (chain/set-node-id! "test-node")
        (is (= "test-node" (chain/node-id)))
        (finally (chain/set-node-id! orig))))))

;; =============================================================================
;; Outbound
;; =============================================================================

(deftest stamp-test
  (testing "an originating call stamps [self] at depth 1"
    (let [m (chain/stamp {:chain [] :depth 0 :self-id "nodeA"})]
      (is (= ["nodeA"] (get m chain/CHAIN_KEY)))
      (is (= 1 (get m chain/DEPTH_KEY)))))

  (testing "the CALLER appends itself to the chain it received"
    ;; Not the callee — appending the callee would put the receiver's own
    ;; id in the chain it receives, and a membership check would then
    ;; refuse the very first hop.
    (let [m (chain/stamp {:chain ["nodeA"] :depth 1 :self-id "nodeB"})]
      (is (= ["nodeA" "nodeB"] (get m chain/CHAIN_KEY)))
      (is (= 2 (get m chain/DEPTH_KEY)))))

  (testing "self defaults to this process's node id"
    (is (= [(chain/node-id)] (get (chain/stamp {:chain [] :depth 0})
                                  chain/CHAIN_KEY))))

  (testing "existing entries are stringified"
    (is (= ["1" "nodeB"] (get (chain/stamp {:chain [1] :depth 0 :self-id "nodeB"})
                              chain/CHAIN_KEY))))

  (testing "context-id is included only when non-blank"
    (is (= "agt-1" (get (chain/stamp {:chain [] :depth 0 :context-id "agt-1"})
                        chain/CONTEXT_KEY)))
    (is (not (contains? (chain/stamp {:chain [] :depth 0}) chain/CONTEXT_KEY)))
    (is (not (contains? (chain/stamp {:chain [] :depth 0 :context-id "  "})
                        chain/CONTEXT_KEY))))

  (testing "nil depth is treated as 0"
    (is (= 1 (get (chain/stamp {:chain []}) chain/DEPTH_KEY))))

  (testing "keys are NAMESPACED, so they cannot collide with other extensions"
    (is (every? #(str/starts-with? % "ai.brainyard/")
                (keys (chain/stamp {:chain [] :depth 0}))))))

;; =============================================================================
;; Inbound
;; =============================================================================

(deftest read-string-keys-test
  (testing "reads the raw wire form (string keys)"
    (let [m {chain/CHAIN_KEY ["a" "b"] chain/DEPTH_KEY 2 chain/CONTEXT_KEY "c-1"}]
      (is (= ["a" "b"] (chain/read-chain m)))
      (is (= 2 (chain/read-depth m)))
      (is (= "c-1" (chain/read-context-id m))))))

(deftest read-keyword-keys-test
  (testing "reads the DECODED form (namespaced keyword keys)"
    ;; This is the form a handler actually sees: a2a/decode keywordizes with
    ;; :key-fn keyword, so "ai.brainyard/call-chain" arrives as
    ;; :ai.brainyard/call-chain. Reading only strings would silently never
    ;; match — and a guard that never matches passes everything.
    (let [m {(keyword chain/CHAIN_KEY) ["a" "b"]
             (keyword chain/DEPTH_KEY) 2
             (keyword chain/CONTEXT_KEY) "c-1"}]
      (is (= ["a" "b"] (chain/read-chain m)))
      (is (= 2 (chain/read-depth m)))
      (is (= "c-1" (chain/read-context-id m)))))

  (testing "a full stamp -> decode -> read round trip survives keywordization"
    (let [stamped (chain/stamp {:chain ["a"] :depth 1 :self-id "b"})
          decoded (into {} (map (fn [[k v]] [(keyword k) v])) stamped)]
      (is (= ["a" "b"] (chain/read-chain decoded)))
      (is (= 2 (chain/read-depth decoded)))))

  (testing "the guard itself works on decoded metadata"
    (is (= :cycle (:reason (chain/check {:metadata {(keyword chain/CHAIN_KEY) ["me"]}
                                         :self-id "me"}))))))

(deftest absent-metadata-test
  (testing "no metadata means chain [] and depth 0, NOT an error"
    ;; A non-brainyard client sends none — the common case. The guard then
    ;; degrades to "this server allows N hops of its own", which is the
    ;; right behaviour toward a stranger.
    (doseq [m [nil {} {:other "stuff"}]]
      (is (= [] (chain/read-chain m)))
      (is (= 0 (chain/read-depth m)))
      (is (nil? (chain/read-context-id m)))
      (is (nil? (chain/check {:metadata m :self-id "me"}))))))

(deftest malformed-metadata-test
  (testing "a malformed depth reads as 0 rather than throwing"
    ;; It comes from a remote peer; a bad value must not crash a handler.
    (is (= 0 (chain/read-depth {chain/DEPTH_KEY "abc"})))
    (is (= 0 (chain/read-depth {chain/DEPTH_KEY nil})))
    (is (= 0 (chain/read-depth {chain/DEPTH_KEY {:a 1}})))
    (is (= 3 (chain/read-depth {chain/DEPTH_KEY "3"})) "a numeric string still parses"))

  (testing "a negative depth clamps to 0"
    (is (= 0 (chain/read-depth {chain/DEPTH_KEY -5}))))

  (testing "a non-sequential chain reads as empty"
    (is (= [] (chain/read-chain {chain/CHAIN_KEY "not-a-vector"})))
    (is (= [] (chain/read-chain {chain/CHAIN_KEY 42}))))

  (testing "check never throws on hostile input"
    (doseq [m [{chain/CHAIN_KEY "x"} {chain/DEPTH_KEY "x"} {chain/CHAIN_KEY [nil]}
               {chain/DEPTH_KEY ##Inf} "not-a-map" 42]]
      (is (or (nil? (chain/check {:metadata m :self-id "me"}))
              (map? (chain/check {:metadata m :self-id "me"})))
          (str "should not throw on: " (pr-str m))))))

;; =============================================================================
;; The guard
;; =============================================================================

(deftest cycle-detection-test
  (testing "self already in the chain is a cycle"
    (is (chain/cycle? ["a" "nodeMe"] "nodeMe")))

  (testing "self absent is not a cycle"
    (is (not (chain/cycle? ["a" "b"] "nodeMe"))))

  (testing "an empty chain is never a cycle"
    (is (not (chain/cycle? [] "anything"))))

  (testing "self defaults to this node"
    (is (chain/cycle? [(chain/node-id)]))
    (is (not (chain/cycle? ["someone-else"])))))

(deftest check-test
  (testing "a fresh request from a stranger passes"
    (is (nil? (chain/check {:metadata nil :self-id "me" :max-depth 3}))))

  (testing "a cycle is REFUSED with :reason :cycle"
    (let [r (chain/check {:metadata {chain/CHAIN_KEY ["x" "me"]}
                          :self-id "me" :max-depth 3})]
      (is (= :cycle (:reason r)))
      (is (str/includes? (:error r) "cycle"))))

  (testing "reaching the depth limit is REFUSED with :reason :depth"
    (let [r (chain/check {:metadata {chain/DEPTH_KEY 3} :self-id "me" :max-depth 3})]
      (is (= :depth (:reason r)))
      (is (str/includes? (:error r) "depth"))))

  (testing "depth is a >= test, not a > test"
    ;; At depth == limit we must refuse: servicing it would make limit+1.
    (is (some? (chain/check {:metadata {chain/DEPTH_KEY 3} :self-id "me" :max-depth 3})))
    (is (nil? (chain/check {:metadata {chain/DEPTH_KEY 2} :self-id "me" :max-depth 3}))))

  (testing "a cycle wins over depth when both would fire"
    (is (= :cycle (:reason (chain/check {:metadata {chain/CHAIN_KEY ["me"]
                                                    chain/DEPTH_KEY 99}
                                         :self-id "me" :max-depth 3})))))

  (testing "max-depth defaults to 3"
    (is (some? (chain/check {:metadata {chain/DEPTH_KEY 3} :self-id "me"})))
    (is (nil? (chain/check {:metadata {chain/DEPTH_KEY 2} :self-id "me"})))))

(deftest inbound-chain-test
  (testing "the chain bound while servicing is what we received, unchanged"
    ;; We must NOT append ourselves here — the next outbound stamp does
    ;; that. Appending in both places would double-count and make a
    ;; two-hop chain look like four.
    (is (= ["a" "b"] (chain/inbound-chain {chain/CHAIN_KEY ["a" "b"]})))
    (is (= [] (chain/inbound-chain nil)))))

;; =============================================================================
;; Multi-hop simulations — what this whole namespace exists for
;; =============================================================================

(defn- hop
  "Simulate one hop: node `from` (holding `chain` at `depth`) calls a peer.
   Returns the metadata that peer receives."
  [from chain depth]
  (chain/stamp {:chain chain :depth depth :self-id from}))

(defn- serve
  "Simulate a node `self` receiving `metadata`. Returns
   `{:refused reason-or-nil :chain <chain to bind> :depth <depth>}`."
  [self metadata & {:keys [max-depth] :or {max-depth 5}}]
  (let [r (chain/check {:metadata metadata :self-id self :max-depth max-depth})]
    {:refused (:reason r)
     :chain   (chain/inbound-chain metadata)
     :depth   (chain/read-depth metadata)}))

(deftest single-hop-is-accepted-test
  (testing "A -> B is accepted"
    (let [m (hop "nodeA" [] 0)
          r (serve "nodeB" m)]
      (is (nil? (:refused r)))
      (is (= ["nodeA"] (:chain r)))
      (is (= 1 (:depth r))))))

(deftest ping-pong-is-refused-test
  (testing "A -> B -> A is REFUSED at A"
    ;; THE scenario. Without this, two brainyard instances pointed at each
    ;; other recurse until something times out, spending a real LLM turn on
    ;; every hop.
    (let [m1 (hop "nodeA" [] 0)                       ;; A calls B
          r1 (serve "nodeB" m1)
          _  (is (nil? (:refused r1)) "B accepts the first hop")
          m2 (hop "nodeB" (:chain r1) (:depth r1))    ;; B calls back to A
          r2 (serve "nodeA" m2)]
      (is (= :cycle (:refused r2)) "A must refuse to re-enter itself")))

  (testing "the refusal happens even with a generous depth limit"
    (let [m1 (hop "nodeA" [] 0)
          r1 (serve "nodeB" m1 :max-depth 100)
          m2 (hop "nodeB" (:chain r1) (:depth r1))]
      (is (= :cycle (:refused (serve "nodeA" m2 :max-depth 100)))))))

(deftest three-node-cycle-is-refused-test
  (testing "A -> B -> C -> B is refused at B's second entry"
    (let [m1 (hop "nodeA" [] 0)
          r1 (serve "nodeB" m1)
          m2 (hop "nodeB" (:chain r1) (:depth r1))
          r2 (serve "nodeC" m2)
          _  (is (nil? (:refused r2)) "C accepts — it has not been visited")
          m3 (hop "nodeC" (:chain r2) (:depth r2))
          r3 (serve "nodeB" m3)]
      (is (= :cycle (:refused r3))))))

(deftest linear-chain-is-bounded-by-depth-test
  (testing "a cycle-free chain A -> B -> C -> D is stopped by DEPTH, not cycle"
    (let [m1 (hop "nodeA" [] 0)
          r1 (serve "nodeB" m1 :max-depth 3)
          m2 (hop "nodeB" (:chain r1) (:depth r1))
          r2 (serve "nodeC" m2 :max-depth 3)
          m3 (hop "nodeC" (:chain r2) (:depth r2))
          r3 (serve "nodeD" m3 :max-depth 3)]
      (is (nil? (:refused r1)))
      (is (nil? (:refused r2)))
      (is (= :depth (:refused r3)) "depth 3 >= limit 3")
      (is (= ["nodeA" "nodeB" "nodeC"] (:chain r3))))))

(deftest depth-counts-every-hop-test
  (testing "depth increments once per hop and matches the chain length"
    (loop [node "nodeA" chain [] depth 0 n 0]
      (when (< n 5)
        (let [m (hop node chain depth)
              r (serve (str "node" n) m :max-depth 100)]
          (is (= (count (:chain r)) (:depth r))
              "chain length and depth must not drift apart")
          (recur (str "node" n) (:chain r) (:depth r) (inc n)))))))

(deftest describe-test
  (testing "renders a readable one-liner"
    (let [d (chain/describe {chain/CHAIN_KEY ["a" "b"] chain/DEPTH_KEY 2})]
      (is (str/includes? d "depth=2"))
      (is (str/includes? d "a -> b"))))

  (testing "handles absent metadata"
    (is (str/includes? (chain/describe nil) "<none>"))))
