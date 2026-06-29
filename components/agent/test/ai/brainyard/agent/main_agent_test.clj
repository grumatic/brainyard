;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.main-agent-test
  "Tests for main-agent (lightweight redesign): registration, inherited
   bt-factory (CoAct), the curated agent-tools roster (the per-turn routing
   line is now HOOK-DERIVED, so main$append-log is retired as an LLM tool),
   instruction-content anchors, unit tests for the surviving main$* seams
   (bootstrap idempotence, last-shape round-trip, pointers append, index
   append, resume probe, Saved-line parsing) plus the internal append-log! /
   coerce-shape writers, the post-tool-use pointers capture, and the
   record-routing-line hook that derives + writes the per-turn routing line."
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
      (is (contains? ids :eval$read-verdict))
      (is (contains? ids :edit$read-record)))

    (testing "the surviving main$* seams are present; main$append-log is retired"
      (is (contains? ids :main$session-id))
      (is (contains? ids :main$resume?))
      (is (contains? ids :main$bootstrap))
      (is (contains? ids :main$append-pointer))
      (is (contains? ids :main$last-shape))
      (is (contains? ids :main$index-append))
      (is (not (contains? ids :main$append-log))
          "the per-turn routing line is hook-derived — main$append-log is not an LLM tool"))

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
      (is (str/includes? ins "edit-agent"))
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

    (testing "session-probe + hook-recorded routing line are documented"
      (is (str/includes? ins "main$resume?"))
      ;; The routing line is hook-derived now — the instruction must NOT tell the
      ;; LLM to call main$append-log, and must teach the self-answer convention.
      (is (not (str/includes? ins "main$append-log")))
      (is (str/includes? ins "Routing: <shape>"))
      (is (str/includes? ins "hook")))))

;; ============================================================================
;; Unit tests for main$* helpers
;; ============================================================================

(deftest valid-shapes-test
  (testing "valid-shapes covers exactly the 22 §6 decision-table moves"
    (is (= 22 (count main/valid-shapes)))
    (doseq [s [:direct-answer :tool-fetch :code-compose :explore :update
               :plan-author :decompose :execute :evaluate :research
               :workflow :rlm :memory :skill-lifecycle :mcp-lifecycle
               :tool-lifecycle :init :config :acp :meta-resume :clarify
               :agent-lifecycle]]
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

(deftest coerce-shape-test
  (testing "known shapes pass through (keyword or string)"
    (is (= :plan-author (main/coerce-shape :plan-author)))
    (is (= :explore (main/coerce-shape "explore")))
    (is (= :direct-answer (main/coerce-shape :direct-answer))))
  (testing "unknown / nil → :unspecified (never fails the turn)"
    (is (= :unspecified (main/coerce-shape :not-a-real-shape)))
    (is (= :unspecified (main/coerce-shape "bogus")))
    (is (= :unspecified (main/coerce-shape nil)))))

(deftest append-log!-test
  ;; append-log! is the internal NDJSON writer the routing-log hook calls
  ;; (main$append-log was retired as an LLM tool). It coerces unknown shapes
  ;; and creates the dir if missing — it never rejects.
  (let [base (tempdir)
        sid  "test-session-2"]
    (main/main$bootstrap :session-id sid :base-dir base)

    (testing "writes one NDJSON line with routed-to + artifact"
      (let [r (main/append-log!
               :session-id sid :base-dir base
               :turn 1 :iter 1
               :question "draft a plan to migrate auth"
               :shape :plan-author
               :routed-to "plan-agent"
               :artifact ".brainyard/agents/plan-agent/plans/migrate-auth.md"
               :reason "explicit 'draft a plan'")]
        (is (:appended r))
        (is (str/includes? (:line r) "plan-author"))
        (is (str/includes? (:line r) "plan-agent"))))

    (testing "unknown shape is coerced to :unspecified (not rejected)"
      (let [r (main/append-log!
               :session-id sid :base-dir base
               :turn 1 :iter 2
               :question "what is X?"
               :shape :not-a-real-shape
               :reason "test")]
        (is (:appended r))
        (is (str/includes? (:line r) "unspecified"))))

    (testing "creates the routing-log dir if missing (no main$bootstrap needed)"
      (let [r (main/append-log!
               :session-id "never-bootstrapped"
               :base-dir base
               :turn 1 :iter 1
               :question "?" :shape :clarify :reason "test")]
        (is (:appended r))
        (is (.isFile (io/file base ".brainyard/agents/main-agent" "never-bootstrapped" "routing.log")))))))

(deftest last-shape-roundtrip-test
  (let [base (tempdir)
        sid  "test-session-3"]
    (main/main$bootstrap :session-id sid :base-dir base)

    (testing "returns :exists? false when log is empty"
      (let [r (main/main$last-shape :session-id sid :base-dir base)]
        (is (not (:exists? r)))))

    (main/append-log!
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

    (main/append-log!
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

    (main/append-log!
     :session-id sid :base-dir base
     :turn 1 :iter 1 :question "Hi" :shape :direct-answer :reason "greeting")
    (main/append-log!
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

(defrecord StubAgent [type sid !state bt-mem]
  ai.brainyard.agent.core.protocol/IAgent
  (agent-id [_] (keyword (str (name type) "/stub")))
  (defagent-type [_] type)
  (session-id [_] sid)
  (user-id [_] "test-user")
  ai.brainyard.agent.core.protocol/IAgentBTIntegration
  (get-bt-st-memory [_] bt-mem))

(defn- mk-stub
  "Stub main-agent. `bt-mem` (optional) is an atom of short-term memory
   (`{:iterations [...]}`) so the routing-line hook can derive routed-to from
   the turn's specialist tool-calls; nil → no dispatch (self-answered turn)."
  ([type sid] (->StubAgent type sid (atom {}) nil))
  ([type sid bt-mem] (->StubAgent type sid (atom {}) bt-mem)))

(defn- bt-mem-with-dispatch
  "An st-memory atom whose last iteration dispatched `agent-name` (CoAct shape)."
  [agent-name]
  (atom {:iterations [{:channel :code
                       :tool-results [{:tool-name agent-name}]}]}))

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
;; Routing-line recorder — :agent.ask/pre + :agent.ask/post pair
;;
;; The routing line is HOOK-DERIVED (main-agent no longer calls main$append-log):
;; record-routing-line is the SOLE writer. It derives routed-to from the turn's
;; dispatch, shape from specialist→shape / the `Routing:` answer line / a
;; channel fallback, artifact from the surfaced `Saved <kind>:` path, and reason
;; from the model's one-sentence routing decision.
;; ============================================================================

(deftest record-routing-line-test
  (let [base (tempdir)
        sid  "routing-line-session-1"]

    (testing "specialist dispatch → routed-to + specialist→shape + artifact + reason"
      (let [stub (mk-stub :main-agent sid (bt-mem-with-dispatch "plan-agent"))]
        (with-redefs [config/project-dir (constantly base)]
          (main/main$bootstrap :session-id sid :base-dir base)
          (main-hooks/capture-pre-turn {:agent stub})
          (main-hooks/record-routing-line
           {:agent stub
            :input  {:question "draft a plan to migrate auth"}
            :result {:answer (str "Routed to plan-agent — your question reduced to plan authoring.\n\n"
                                  "Saved plan: .brainyard/agents/plan-agent/plans/migrate-auth.md")}}))
        (let [ln (first (main/read-routing-log sid :base-dir base))]
          (is (= 1 (count (main/read-routing-log sid :base-dir base))))
          (is (= "plan-author" (:shape ln)) "shape derived from the dispatched specialist")
          (is (= "plan-agent" (:routed-to ln)))
          (is (= ".brainyard/agents/plan-agent/plans/migrate-auth.md" (:artifact ln))
              "artifact lifted from the surfaced Saved plan: line")
          (is (str/includes? (:reason ln) "plan authoring")
              "reason lifted from the model's one-sentence routing decision")
          (is (= 1 (:turn ln))))))

    (testing "self-answered move → shape + reason parsed from the `Routing:` line"
      (let [stub (mk-stub :main-agent sid)]  ; no dispatch
        (with-redefs [config/project-dir (constantly base)]
          (main-hooks/capture-pre-turn {:agent stub})
          (main-hooks/record-routing-line
           {:agent stub
            :input  {:question "what is CoAct?"}
            :result {:answer "CoAct is a behavior-tree loop.\n\nRouting: direct-answer — factual question, no specialist needed"}}))
        (let [ln (last (main/read-routing-log sid :base-dir base))]
          (is (= "direct-answer" (:shape ln)))
          (is (nil? (:routed-to ln)) "self-answered → no routed-to")
          (is (str/includes? (:reason ln) "factual question"))
          (is (= 2 (:turn ln))))))

    (testing "unknown `Routing:` shape → coerced to :unspecified (never fails)"
      (let [stub (mk-stub :main-agent sid)]
        (with-redefs [config/project-dir (constantly base)]
          (main-hooks/capture-pre-turn {:agent stub})
          (main-hooks/record-routing-line
           {:agent stub
            :input  {:question "weird"}
            :result {:answer "Routing: bananas — nonsense shape"}}))
        (let [ln (last (main/read-routing-log sid :base-dir base))]
          (is (= "unspecified" (:shape ln))))))

    (testing "idempotent — a re-fire on the same snapshot does not double-write"
      (let [stub (mk-stub :main-agent sid)
            pre  (count (main/read-routing-log sid :base-dir base))]
        (with-redefs [config/project-dir (constantly base)]
          (main-hooks/capture-pre-turn {:agent stub})  ; snapshots current max
          (main-hooks/record-routing-line
           {:agent stub :input {:question "x"} :result {:answer "Routing: clarify — ambiguous"}})
          ;; Re-fire WITHOUT re-snapshotting: post-turn now advanced past the
          ;; stale ::pre-max-turn, so the guard makes this a no-op.
          (main-hooks/record-routing-line
           {:agent stub :input {:question "x"} :result {:answer "Routing: clarify — ambiguous"}}))
        (is (= (inc pre) (count (main/read-routing-log sid :base-dir base)))
            "exactly one line added across the two fires")))

    (testing "Blank :answer → no line (CoAct loop exhausted, not user-facing)"
      (let [stub (mk-stub :main-agent sid)
            pre  (count (main/read-routing-log sid :base-dir base))]
        (with-redefs [config/project-dir (constantly base)]
          (main-hooks/capture-pre-turn {:agent stub})
          (main-hooks/record-routing-line
           {:agent stub :input {:question "x"} :result {:answer ""}}))
        (is (= pre (count (main/read-routing-log sid :base-dir base))))))

    (testing "Non-main-agent caller → no line"
      (let [pre  (count (main/read-routing-log sid :base-dir base))
            stub (mk-stub :coact-agent sid)]
        (with-redefs [config/project-dir (constantly base)]
          (main-hooks/capture-pre-turn {:agent stub})
          (main-hooks/record-routing-line
           {:agent stub :input {:question "x"} :result {:answer "y"}}))
        (is (= pre (count (main/read-routing-log sid :base-dir base))))))))
