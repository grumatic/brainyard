;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.main-agent-test
  "Tests for main-agent: registration, inherited bt-factory (CoAct), curated
   agent-tools roster across the routing-log substrate + the seven main$*
   helpers (positive + negative assertions per Hard Rules 1, 2 of the design
   doc), instruction-content anchors that pin the bootstrap / decision-table
   / hard-rules contracts, unit tests for the main$* helper commands
   (bootstrap idempotence, NDJSON append-only with shape-enum validation,
   last-shape round-trip, pointers append, index append, Saved-line
   parsing), and a hook-side-effects test that confirms the post-tool-use
   capture writes pointers.md bullets."
  (:require [ai.brainyard.agent.common.main :as main]
            [ai.brainyard.agent.common.main-agent]
            [ai.brainyard.agent.common.main-agent-hooks :as main-hooks]
            [ai.brainyard.agent.core.config :as config]
            [ai.brainyard.agent.core.tool :as tool]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]])
  (:import (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

;; ============================================================================
;; Tempdir helper
;; ============================================================================

(defn- tempdir
  "Create a fresh tempdir, returning its absolute path string."
  []
  (-> (Files/createTempDirectory "main-agent-test-" (make-array FileAttribute 0))
      .toFile
      .getAbsolutePath))

;; ============================================================================
;; Registration
;; ============================================================================

(deftest registration-test
  (testing "main-agent is registered as an agent"
    (let [agent-defs (tool/get-tool-defs :type :agent)]
      (is (contains? agent-defs :main-agent))
      (let [agent-def (get agent-defs :main-agent)]
        (is (= :main-agent (:id agent-def)))
        (is (some? (:fn agent-def)))
        (is (some? (:meta agent-def)))))))

;; ============================================================================
;; Inheritance via run-coact-derived
;; ============================================================================

(deftest inheritance-test
  (require 'ai.brainyard.agent.common.coact-agent)
  (let [m-def (get (tool/get-tool-defs :type :agent) :main-agent)]

    (testing "main-agent's :fn is the wrap-fn (CoAct entry)"
      (is (some? (:fn m-def))))

    (testing "main-agent pins :bt-factory explicitly (so setup-agent-by-id picks it up)"
      (let [bt-factory (get-in m-def [:meta :bt-factory])]
        (is (fn? bt-factory))
        (let [bt (bt-factory {:max-iterations 3})]
          (is (vector? bt))
          (is (= :sequence (first bt))))))

    (testing "default :max-iterations is 20 (CoAct default — main-agent is a router, not a worker)"
      (let [bt-factory (get-in m-def [:meta :bt-factory])
            bt (bt-factory {})]
        (is (some? bt))))))

;; ============================================================================
;; Tool roster — positive and negative anchors per Hard Rules
;; ============================================================================

(defn- agent-tool-ids
  "Collect the :id keywords (or kebab-case names) of every var in the
   defagent's :agent-tools :tools vector."
  [agent-def]
  (->> (get-in agent-def [:meta :agent-tools :tools])
       (map (fn [v]
              (let [m (meta v)]
                (or (:tool-id m)
                    (when-let [nm (:name m)]
                      (keyword (str/replace (name nm) #"_" "-")))))))
       (filter some?)
       set))

(deftest agent-tools-roster-test
  (let [m-def (get (tool/get-tool-defs :type :agent) :main-agent)
        ids   (agent-tool-ids m-def)]

    (testing "Hard Rule 1: query$clone is NOT in the roster (no clone-self)"
      (is (not (contains? ids :query$clone))))

    (testing "query$llm IS in the roster (flat sub-LLM allowed)"
      (is (contains? ids :query$llm)))

    (testing "Hard Rule 2: read-only sibling dossier helpers ARE present"
      (is (contains? ids :plan$read-dossier))
      (is (contains? ids :todo$read-dossier))
      (is (contains? ids :exec$read-dossier))
      (is (contains? ids :eval$read-dossier))
      (is (contains? ids :update$read-record)))

    (testing "all seven main$* helpers are present"
      (is (contains? ids :main$session-id))
      (is (contains? ids :main$resume?))
      (is (contains? ids :main$bootstrap))
      (is (contains? ids :main$append-log))
      (is (contains? ids :main$append-pointer))
      (is (contains? ids :main$last-shape))
      (is (contains? ids :main$index-append)))

    (testing "routing-log substrate tools are present"
      (is (contains? ids :read-file))
      (is (contains? ids :write-file))
      (is (contains? ids :bash)))))

;; ============================================================================
;; Instruction-content anchors — pin the contracts
;; ============================================================================

(deftest instruction-content-anchors-test
  (let [m-def (get (tool/get-tool-defs :type :agent) :main-agent)
        ins   (get-in m-def [:meta :instruction])]

    (testing "instruction names every specialist family"
      (is (str/includes? ins "explore-agent"))
      (is (str/includes? ins "plan-agent"))
      (is (str/includes? ins "todo-agent"))
      (is (str/includes? ins "exec-agent"))
      (is (str/includes? ins "eval-agent"))
      (is (str/includes? ins "update-agent"))
      (is (str/includes? ins "research-agent"))
      (is (str/includes? ins "workflow-agent"))
      (is (str/includes? ins "memory-agent")))

    (testing "instruction enumerates all 20 decision-table shapes"
      (is (str/includes? ins "DIRECT-ANSWER"))
      (is (str/includes? ins "TOOL-FETCH"))
      (is (str/includes? ins "CODE-COMPOSE"))
      (is (str/includes? ins "META-RESUME"))
      (is (str/includes? ins "CLARIFY")))

    (testing "Hard Rules 1 and 2 are spelled out"
      (is (str/includes? ins "clone-self"))
      (is (str/includes? ins "NO direct writes to sibling-specialist storage")))

    (testing "bootstrap obligation is named"
      (is (str/includes? ins "main$bootstrap"))
      (is (str/includes? ins "main$resume?"))
      (is (str/includes? ins "main$append-log")))))

;; ============================================================================
;; Unit tests for main$* helpers
;; ============================================================================

(deftest valid-shapes-test
  (testing "valid-shapes covers exactly the 21 §6 decision-table moves"
    (is (= 21 (count main/valid-shapes)))
    (doseq [s [:direct-answer :tool-fetch :code-compose :explore :update
               :plan-author :decompose :execute :evaluate :research
               :workflow :rlm :memory :skill-lifecycle :mcp-lifecycle
               :tool-lifecycle :init :config :acp :meta-resume :clarify]]
      (is (contains? main/valid-shapes s)
          (str "Missing shape: " s)))))

(deftest bootstrap-idempotence-test
  (let [base (tempdir)
        sid  "test-session-1"]
    (testing "first call creates dir + empty routing.log + pointers.md header"
      (let [r (main/main$bootstrap :session-id sid :base-dir base)]
        (is (some? (:dir r)))
        (is (some? (:log-path r)))
        (is (some? (:pointers-path r)))
        (is (not (:exists? r)))
        (is (.isFile (io/file base ".brainyard/agents/main-agent" sid "routing.log")))
        (is (.isFile (io/file base ".brainyard/agents/main-agent" sid "pointers.md")))
        (let [pointers-body (slurp (io/file base ".brainyard/agents/main-agent" sid "pointers.md"))]
          (is (str/includes? pointers-body "# Session pointers")))))

    (testing "second call is idempotent — returns :exists? true, does not overwrite"
      (spit (io/file base ".brainyard/agents/main-agent" sid "routing.log")
            "{\"turn\":1,\"shape\":\"direct-answer\"}\n")
      (let [r (main/main$bootstrap :session-id sid :base-dir base)]
        (is (:exists? r))
        (is (some? (:dir r))))
      ;; The hand-spat line should still be there.
      (let [log-body (slurp (io/file base ".brainyard/agents/main-agent" sid "routing.log"))]
        (is (str/includes? log-body "direct-answer"))))))

(deftest append-log-shape-validation-test
  (let [base (tempdir)
        sid  "test-session-2"]
    (main/main$bootstrap :session-id sid :base-dir base)

    (testing "rejects unknown shape with informative error"
      (let [r (main/main$append-log
               :session-id sid :base-dir base
               :turn 1 :iter 1
               :question "what is X?"
               :shape :not-a-real-shape
               :reason "test")]
        (is (some? (:error r)))
        (is (str/includes? (:error r) ":shape must be one of"))))

    (testing "accepts a valid shape and writes one NDJSON line"
      (let [r (main/main$append-log
               :session-id sid :base-dir base
               :turn 1 :iter 1
               :question "draft a plan to migrate auth"
               :shape :plan-author
               :routed-to "plan-agent"
               :artifact ".brainyard/agents/plan-agent/plans/migrate-auth.md"
               :reason "explicit 'draft a plan'")]
        (is (:appended r))
        (is (some? (:line r)))
        (is (str/includes? (:line r) "plan-author"))
        (is (str/includes? (:line r) "plan-agent"))))

    (testing "accepts shape as a string (coerced to keyword)"
      (let [r (main/main$append-log
               :session-id sid :base-dir base
               :turn 1 :iter 2
               :question "what's in foo.clj?"
               :shape "tool-fetch"
               :reason "single-shot RPC")]
        (is (:appended r))))

    (testing "errors when routing-log dir is missing"
      (let [r (main/main$append-log
               :session-id "never-bootstrapped"
               :base-dir base
               :turn 1 :iter 1
               :question "?" :shape :clarify :reason "test")]
        (is (some? (:error r)))
        (is (str/includes? (:error r) "main$bootstrap"))))))

(deftest last-shape-roundtrip-test
  (let [base (tempdir)
        sid  "test-session-3"]
    (main/main$bootstrap :session-id sid :base-dir base)

    (testing "returns :exists? false when log is empty"
      (let [r (main/main$last-shape :session-id sid :base-dir base)]
        (is (not (:exists? r)))))

    (main/main$append-log
     :session-id sid :base-dir base
     :turn 1 :iter 1
     :question "research how to reduce cold start"
     :shape :research
     :routed-to "research-agent"
     :artifact ".brainyard/agents/research-agent/cold-start/dossier.md"
     :reason "end-to-end research arc")

    (testing "returns the last logged decision parsed correctly"
      (let [r (main/main$last-shape :session-id sid :base-dir base)]
        (is (:exists? r))
        (is (= :research (:shape r)))
        (is (= "research-agent" (:routed-to r)))
        (is (= ".brainyard/agents/research-agent/cold-start/dossier.md" (:artifact r)))
        (is (= 1 (:turn r)))
        (is (str/includes? (:question r) "cold start"))))

    (main/main$append-log
     :session-id sid :base-dir base
     :turn 2 :iter 1
     :question "what was that path again?"
     :shape :meta-resume
     :reason "user asked for prior artifact path")

    (testing "always returns the most recent line"
      (let [r (main/main$last-shape :session-id sid :base-dir base)]
        (is (= :meta-resume (:shape r)))
        (is (= 2 (:turn r)))
        (is (nil? (:routed-to r)))))))

(deftest append-pointer-test
  (let [base (tempdir)
        sid  "test-session-4"]
    (main/main$bootstrap :session-id sid :base-dir base)

    (testing "appends a markdown bullet with the path and caption"
      (let [r (main/main$append-pointer
               :session-id sid :base-dir base
               :path ".brainyard/agents/plan-agent/plans/migrate-auth.md"
               :caption "plan body — migrate-auth")]
        (is (:appended r)))
      (let [body (slurp (io/file base ".brainyard/agents/main-agent" sid "pointers.md"))]
        (is (str/includes? body "migrate-auth.md"))
        (is (str/includes? body "plan body — migrate-auth"))))

    (testing "errors when dir is missing"
      (let [r (main/main$append-pointer
               :session-id "missing" :base-dir base
               :path "x" :caption "y")]
        (is (some? (:error r)))))))

(deftest resume-probe-test
  (let [base (tempdir)
        sid  "test-session-5"]

    (testing ":exists? false before bootstrap"
      (let [r (main/main$resume? :session-id sid :base-dir base)]
        (is (not (:exists? r)))))

    (main/main$bootstrap :session-id sid :base-dir base)

    (testing ":exists? true with zero turns post-bootstrap"
      (let [r (main/main$resume? :session-id sid :base-dir base)]
        (is (:exists? r))
        (is (= 0 (:line-count r)))
        (is (= 0 (:turn-count r)))
        (is (nil? (:last-shape r)))))

    (main/main$append-log
     :session-id sid :base-dir base
     :turn 1 :iter 1 :question "Hi" :shape :direct-answer :reason "greeting")
    (main/main$append-log
     :session-id sid :base-dir base
     :turn 2 :iter 1 :question "Run X" :shape :execute
     :routed-to "exec-agent"
     :artifact ".brainyard/agents/exec-agent/dossiers/x.md"
     :reason "drive todo to completion")

    (testing "reports line count, max turn, and last shape"
      (let [r (main/main$resume? :session-id sid :base-dir base)]
        (is (:exists? r))
        (is (= 2 (:line-count r)))
        (is (= 2 (:turn-count r)))
        (is (= :execute (:last-shape r)))
        (is (= ".brainyard/agents/exec-agent/dossiers/x.md" (:last-artifact r)))))))

(deftest index-append-test
  (let [base (tempdir)]
    (testing "first call creates INDEX.md and writes one line"
      (let [r (main/main$index-append
               :session-id "s-1" :base-dir base
               :turn-count 3 :shapes [:plan-author :decompose :execute])]
        (is (:appended r))
        (is (some? (:line r))))
      (let [body (slurp (io/file base ".brainyard/agents/main-agent/INDEX.md"))]
        (is (str/includes? body "session s-1"))
        (is (str/includes? body "turns: 3"))
        (is (str/includes? body "plan-author"))))

    (testing "second call appends rather than overwriting"
      (main/main$index-append
       :session-id "s-2" :base-dir base
       :turn-count 1 :shapes [:direct-answer])
      (let [body (slurp (io/file base ".brainyard/agents/main-agent/INDEX.md"))]
        (is (str/includes? body "session s-1"))
        (is (str/includes? body "session s-2"))))))

;; ============================================================================
;; Saved-line parsing — used by the capture hook
;; ============================================================================

(deftest parse-saved-lines-test
  (testing "extracts every Saved <kind>: <path> line"
    (let [text "plan-agent drafted a 6-item approach.\n\nSaved plan: .brainyard/agents/plan-agent/plans/migrate-auth.md\nSaved dossier: .brainyard/agents/plan-agent/dossiers/20260516-091412-migrate-auth.md\n\nNext: spawn a todo."
          out  (main/parse-saved-lines text)]
      (is (= 2 (count out)))
      (is (= "plan" (:kind (first out))))
      (is (= ".brainyard/agents/plan-agent/plans/migrate-auth.md" (:path (first out))))
      (is (= "dossier" (:kind (second out))))))

  (testing "empty / blank text returns empty vector"
    (is (= [] (main/parse-saved-lines nil)))
    (is (= [] (main/parse-saved-lines "")))
    (is (= [] (main/parse-saved-lines "no saved lines here"))))

  (testing "rejects lines that don't start with `Saved <kind>:`"
    (is (= [] (main/parse-saved-lines "  Saved foo: indented but with space"))
        "regex anchors require start-of-line")))

;; ============================================================================
;; Hook side-effects — capture-saved-artifacts writes pointers.md bullets
;; ============================================================================

(defrecord StubAgent [type sid !state]
  ai.brainyard.agent.core.protocol/IAgent
  (agent-id [_] (keyword (str (name type) "/stub")))
  (defagent-type [_] type)
  (session-id [_] sid)
  (user-id [_] "test-user"))

(defn- mk-stub
  ([type sid] (->StubAgent type sid (atom {})))
  ([type sid initial-state] (->StubAgent type sid (atom initial-state))))

(deftest capture-hook-test
  (let [base       (tempdir)
        sid        "hook-session-1"
        stub       (mk-stub :main-agent sid)
        not-main   (mk-stub :coact-agent sid)
        answer     "plan-agent drafted a 4-item plan.\n\nSaved plan: .brainyard/agents/plan-agent/plans/x.md\nSaved dossier: .brainyard/agents/plan-agent/dossiers/x.md"
        bootstrap! #(main/main$bootstrap :session-id sid :base-dir base)]
    (bootstrap!)

    (testing "main-agent + specialist tool-call → captures Saved lines"
      ;; The hook's `main/main$append-pointer` call resolves base-dir from
      ;; the bound `*current-agent*`. Since the stub doesn't carry a project
      ;; dir, we redef default-base-dir for the duration of the test so
      ;; pointers.md lands in our tempdir.
      (with-redefs [config/project-dir (constantly base)]
        (main-hooks/capture-saved-artifacts
         {:agent stub :tool-name "plan-agent" :result {:answer answer}}))
      (let [body (slurp (io/file base ".brainyard/agents/main-agent" sid "pointers.md"))]
        (is (str/includes? body "plan-agent/plans/x.md"))
        (is (str/includes? body "plan-agent/dossiers/x.md"))))

    (testing "non-main-agent caller → no capture"
      (let [pre-body (slurp (io/file base ".brainyard/agents/main-agent" sid "pointers.md"))]
        (with-redefs [config/project-dir (constantly base)]
          (main-hooks/capture-saved-artifacts
           {:agent not-main :tool-name "plan-agent" :result {:answer answer}}))
        (is (= pre-body (slurp (io/file base ".brainyard/agents/main-agent" sid "pointers.md"))))))

    (testing "specialist returned no Saved lines → no capture"
      (let [pre-body (slurp (io/file base ".brainyard/agents/main-agent" sid "pointers.md"))]
        (with-redefs [config/project-dir (constantly base)]
          (main-hooks/capture-saved-artifacts
           {:agent stub :tool-name "plan-agent"
            :result {:answer "I couldn't draft a plan — clarify the goal first."}}))
        (is (= pre-body (slurp (io/file base ".brainyard/agents/main-agent" sid "pointers.md"))))))

    (testing "non-specialist tool-name → no capture"
      (let [pre-body (slurp (io/file base ".brainyard/agents/main-agent" sid "pointers.md"))]
        (with-redefs [config/project-dir (constantly base)]
          (main-hooks/capture-saved-artifacts
           {:agent stub :tool-name "read-file" :result {:answer answer}}))
        (is (= pre-body (slurp (io/file base ".brainyard/agents/main-agent" sid "pointers.md"))))))))

;; ============================================================================
;; Auto-log fallback — :agent.ask/pre + :agent.ask/post pair
;; ============================================================================

(deftest auto-log-missing-decision-test
  (let [base (tempdir)
        sid  "auto-log-session-1"]

    (testing "When the LLM forgot to log, the post hook appends one inferred line"
      (let [stub (mk-stub :main-agent sid)]
        (with-redefs [config/project-dir (constantly base)]
          (main/main$bootstrap :session-id sid :base-dir base)
          (main-hooks/capture-pre-turn {:agent stub})
          (main-hooks/auto-log-missing-decision
           {:agent stub
            :input  {:question "Hi"}
            :result {:answer "Hello! How can I help?"}}))
        (let [log (main/read-routing-log sid :base-dir base)]
          (is (= 1 (count log)))
          (is (= "direct-answer" (:shape (first log))))
          (is (= 1 (:turn (first log))))
          (is (str/includes? (:reason (first log)) "auto-logged")))))

    (testing "When the LLM did log, the post hook is a no-op (pre/post counts differ)"
      (let [stub (mk-stub :main-agent sid)]
        (with-redefs [config/project-dir (constantly base)]
          (main-hooks/capture-pre-turn {:agent stub})
          ;; Simulate the LLM appending its own log line for turn 2.
          (main/main$append-log
           :session-id sid :base-dir base
           :turn 2 :iter 1
           :question "draft a plan to migrate auth"
           :shape :plan-author
           :routed-to "plan-agent"
           :reason "explicit 'draft a plan'")
          (main-hooks/auto-log-missing-decision
           {:agent stub
            :input  {:question "draft a plan to migrate auth"}
            :result {:answer "Routed to plan-agent."}}))
        (let [log (main/read-routing-log sid :base-dir base)]
          (is (= 2 (count log)) "post hook must not double-log")
          (is (= "plan-author" (:shape (second log)))))))

    (testing "Blank :answer → no auto-log (CoAct loop exhausted, not user-facing)"
      (let [stub (mk-stub :main-agent sid)
            pre  (count (main/read-routing-log sid :base-dir base))]
        (with-redefs [config/project-dir (constantly base)]
          (main-hooks/capture-pre-turn {:agent stub})
          (main-hooks/auto-log-missing-decision
           {:agent stub :input {:question "x"} :result {:answer ""}}))
        (is (= pre (count (main/read-routing-log sid :base-dir base))))))

    (testing "Non-main-agent caller → no auto-log"
      (let [pre  (count (main/read-routing-log sid :base-dir base))
            stub (mk-stub :coact-agent sid)]
        (with-redefs [config/project-dir (constantly base)]
          (main-hooks/capture-pre-turn {:agent stub})
          (main-hooks/auto-log-missing-decision
           {:agent stub :input {:question "x"} :result {:answer "y"}}))
        (is (= pre (count (main/read-routing-log sid :base-dir base))))))))
