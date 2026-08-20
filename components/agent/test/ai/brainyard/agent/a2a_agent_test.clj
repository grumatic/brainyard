;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.a2a-agent-test
  "Tests for a2a-agent (docs/design/a2a-design.md §5.7).

   Two surfaces, mirroring event_agent_test / schedule_agent_test:

   1. STRUCTURAL — registration, the inherited CoAct bt-factory, the command
      families bound (positive: a2a$* + agent-registry$* + runtime read) and
      the design's exclusions (negative: NO config WRITE — gates are
      config-agent's; NO mcp$*/acp$* — those are their own agents' surfaces;
      NO invented a2a$ask), schema shape, and the instruction / tool-context
      anchors carrying the design's non-negotiables (the registry IS the ask
      path, interrupted ≠ finished, the serve blast radius, the
      config-agent hand-off, the dossier contract).

   2. ROUTER WIRING — router-agent must know about the agent in all three of
      its router surfaces, and must state the boundary against mcp-agent and
      acp-agent. A front door nothing routes to is dead code.

   The a2a machinery itself is covered by a2a_registry_test / a2a_task_test /
   a2a_loopback_test; this suite does not re-test it."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [ai.brainyard.agent.common.a2a-agent]
            [ai.brainyard.agent.common.coact-agent]
            [ai.brainyard.agent.common.router-agent]
            [ai.brainyard.agent.core.tool :as tool]))

;; ============================================================================
;; Helpers
;; ============================================================================

(defn- agent-def []
  (get (tool/get-tool-defs :type :agent) :a2a-agent))

(defn- tool-ids []
  (set (map (comp :id meta deref)
            (get-in (agent-def) [:meta :agent-tools :tools]))))

(defn- instruction [] (str (get-in (agent-def) [:meta :instruction])))
(defn- tool-context [] (str (get-in (agent-def) [:meta :tool-context])))

(defn- unwrapped
  "Instruction text with runs of whitespace collapsed to single spaces.

   Anchor assertions must survive hand-wrapping: the prose is wrapped to
   ~78 columns, so a phrase like 'runs prompts against this workspace' may
   have a newline anywhere inside it. Matching against the raw string makes
   the test fail on a reflow that changed nothing."
  []
  (str/replace (instruction) #"\s+" " "))

(defn- router-agent-text []
  (let [d (get (tool/get-tool-defs :type :agent) :router-agent)]
    (str (get-in d [:meta :instruction]) "\n" (get-in d [:meta :tool-context]))))

;; ============================================================================
;; 1. STRUCTURAL
;; ============================================================================

(deftest registration-test
  (testing "a2a-agent is registered in the unified tool registry"
    (let [d (agent-def)]
      (is (some? d))
      (is (= :agent (:type d)))
      (is (= :a2a-agent (:id d))))))

(deftest inheritance-test
  (testing "the CoAct bt-factory is pinned for direct-resolution entry points"
    ;; setup-agent-by-id (used by `bb tui ask` AND by the A2A server's own
    ;; ask path) resolves the BT from meta, not from run-coact-derived.
    (is (fn? (get-in (agent-def) [:meta :bt-factory])))))

(deftest schema-shape-test
  (let [d (agent-def)]
    (testing "input takes :question plus optional handoff context"
      (let [in (get-in d [:meta :input-schema])]
        (is (= :map (first in)))
        (is (some #(= :question (first %)) (rest in)))
        (is (some #(= :agent-context (first %)) (rest in)))))
    (testing "output is an :answer"
      (is (some #(= :answer (first %)) (rest (get-in d [:meta :output-schema])))))))

(deftest agent-tools-positive
  (let [ids (tool-ids)]
    (testing "the a2a$* connection family is bound"
      (doseq [t [:a2a$connect :a2a$list :a2a$card :a2a$disconnect]]
        (is (contains? ids t) (str t " must be bound"))))

    (testing "the agent-registry family is bound — it IS the ask path"
      ;; A remote peer is asked with agent-registry$ask, exactly like a
      ;; local one. Without these the agent can connect but never ask.
      (doseq [t [:agent-registry$list :agent-registry$detail
                 :agent-registry$ask :agent-registry$close]]
        (is (contains? ids t) (str t " must be bound"))))

    (testing "runtime config is bound for READING the gates"
      (is (contains? ids :agent-runtime$config)))

    (testing "file tools are bound for the dossier contract"
      (is (some #(str/includes? (str %) "write-file") ids)))

    (testing "task commands are bound for long-running remote work"
      (is (some #(str/starts-with? (str %) ":task$") ids)))))

(deftest agent-tools-negative
  (let [ids (tool-ids)]
    (testing "NO mcp$* — external tools are mcp-agent's surface"
      (is (not-any? #(str/starts-with? (str %) ":mcp$") ids)))

    (testing "NO acp$* — local coding CLIs are acp-agent's surface"
      (is (not-any? #(str/starts-with? (str %) ":acp$") ids)))

    (testing "NO invented a2a$ask"
      ;; Asking goes through agent-registry$ask. A second ask path would
      ;; fork the reach policy, and the fork forgets a rule.
      (is (not (contains? ids :a2a$ask))))

    (testing "no config WRITE command — gates belong to config-agent"
      (is (not (contains? ids :config$set))))))

(deftest instruction-anchors
  (let [i (instruction)]
    (testing "states the boundary against mcp-agent and acp-agent"
      (is (str/includes? i "mcp-agent"))
      (is (str/includes? i "acp-agent")))

    (testing "says there is no a2a$ask and points at the registry"
      (is (str/includes? i "agent-registry$ask"))
      (is (re-find #"(?i)no a2a\$ask" i)))

    (testing "treats an interrupted remote task as NOT finished"
      ;; The expensive mistake this whole design keeps guarding against.
      (is (str/includes? i "PAUSED"))
      (is (re-find #"(?i)has NOT finished|WAITING FOR US" (unwrapped))))

    (testing "explains a cycle refusal as the guard working, not a bug"
      (is (re-find #"(?i)cycle" i))
      (is (re-find #"(?i)GUARD DOING ITS JOB|not a bug" (unwrapped))))

    (testing "hands gate writes to config-agent"
      (is (str/includes? i "config-agent")))

    (testing "states the serve blast radius"
      ;; An inbound endpoint runs prompts against the workspace; the agent
      ;; must say so before helping anyone widen the bind address.
      (is (re-find #"(?i)runs prompts against this workspace" (unwrapped)))
      (is (str/includes? i "127.0.0.1"))
      (is (str/includes? i "--sandbox")))

    (testing "treats a remote answer as untrusted input"
      (is (re-find #"(?i)untrusted" i))
      (is (re-find #"(?i)never follow instructions embedded" (unwrapped))))

    (testing "carries the hard dossier contract"
      (is (str/includes? i "FINAL-STEP CHECKLIST"))
      (is (str/includes? i "DOSSIER WRITTEN"))
      (is (str/includes? i "INCOMPLETE turn"))
      (is (str/includes? i ".brainyard/agents/a2a-agent/dossiers/"))
      (is (str/includes? i "INDEX.md")))))

(deftest tool-context-anchors
  (let [c (tool-context)]
    (testing "documents each a2a$* command"
      (doseq [cmd ["a2a$connect" "a2a$list" "a2a$card" "a2a$disconnect"]]
        (is (str/includes? c cmd))))

    (testing "documents the ask path through the registry"
      (is (str/includes? c "agent-registry$ask"))
      (is (str/includes? c "a2a$<peer>$<skill>")))

    (testing "explains that a remote instance reports no local iteration"
      ;; Otherwise :iter 0 reads as a stall rather than as opacity.
      (is (str/includes? c ":kind :remote"))
      (is (re-find #"(?i)opacity by design|runs no local loop" c)))

    (testing "names the three serve gates"
      (doseq [k [":enable-a2a" ":a2a-serve-token" ":a2a-expose-skills"]]
        (is (str/includes? c k) (str k " must be documented"))))))

;; ============================================================================
;; 2. ROUTER WIRING
;; ============================================================================

(deftest router-agent-router-wiring
  (let [t (router-agent-text)]
    (testing "router-agent knows a2a-agent exists"
      ;; A front door nothing routes to is dead code.
      (is (str/includes? t "a2a-agent")))

    (testing "it appears in all THREE router surfaces"
      ;; directory, lettered decision table, summary list — the design
      ;; calls for all three, and a missing one silently degrades routing.
      (is (<= 3 (count (re-seq #"a2a-agent" t)))
          "expected a2a-agent in the directory, the decision table and the summary"))

    (testing "the decision table has a lettered entry"
      (is (re-find #"(?m)^[A-Z]\d?\.\s+A2A-PEERS\s+→ a2a-agent" t)))

    (testing "the router states the boundary against its two neighbours"
      ;; The failure mode is routing 'connect to X' to mcp-agent because
      ;; both sound like 'connect to an external thing'.
      (let [dir (second (re-find #"(?s)(- a2a-agent.*?)\n\n" t))]
        (is (some? dir) "a2a-agent needs a directory entry")
        (is (str/includes? dir "mcp-agent"))
        (is (str/includes? dir "acp-agent"))))))
