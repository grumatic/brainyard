;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.coact-agent-test
  "Tests for the CoAct agent implementation.
   Covers: BT structure, registration, signature shape, action-level unit tests,
   critical rule content, FINAL promotion and parallel-block FINAL-rejection,
   stable-keys wiring.

   End-to-end scenarios (full LLM loop) are out of scope here — see design
   docs/CoAct.md §9 Phase 5 for the benchmark harness plan."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [clojure.string :as str]
            [ai.brainyard.agent.common.coact-agent :as rca]
            [ai.brainyard.agent.common.react-agent]
            [ai.brainyard.agent.common.agent-roster :as agent-roster]
            [ai.brainyard.agent.core.config :as config]
            [ai.brainyard.agent.common.sandbox-bindings :as sb-bind]
            [ai.brainyard.agent.core.hooks :as hooks]
            [ai.brainyard.agent.core.tool :as tool]
            [ai.brainyard.agent.task.manager :as task-mgr]
            [ai.brainyard.agent.task.protocol :as tp]
            [ai.brainyard.behavior-tree.interface :as bt]
            [ai.brainyard.clj-llm.interface :as clj-llm]
            [ai.brainyard.clj-sandbox.interface :as clj-sandbox]))

;; ============================================================================
;; Test fixtures — mock command for tool-dispatch tests
;; ============================================================================

(tool/defcommand test-coact-echo
  "Echo back the input string as a result."
  (fn [& {:keys [text]}]
    {:result (str "echo: " text)})
  :input-schema [:map [:text [:string {:desc "Text to echo"}]]])

(tool/defcommand test-coact-enum
  "Echo back the chosen mode — used to exercise a keyword-member [:enum] field
   through both CoAct dispatch channels."
  (fn [& {:keys [mode]}]
    {:mode mode :result (str "mode=" mode)})
  :input-schema [:map [:mode [:enum {:desc "Mode"} :alpha :beta]]])

(defn cleanup-test-tools [f]
  (f)
  (swap! tool/!tool-defs dissoc :test-coact-echo :test-coact-enum))

(defn reset-task-manager [f]
  ;; Post-Step-F, run-clj-sandbox-block / run-script-block dispatch through the
  ;; task manager. Reset between tests so a stale TaskManager instance
  ;; (built against an older protocol incarnation during REPL development)
  ;; can't poison the next test with "No implementation of method" errors.
  (when-let [mgr (task-mgr/peek-default-manager)]
    (try (tp/shutdown mgr) (catch Exception _)))
  (task-mgr/set-default-manager! nil)
  (f)
  (when-let [mgr (task-mgr/peek-default-manager)]
    (try (tp/shutdown mgr) (catch Exception _)))
  (task-mgr/set-default-manager! nil))

(use-fixtures :once cleanup-test-tools)
(use-fixtures :each reset-task-manager)

;; ============================================================================
;; 1. BT Structure
;; ============================================================================

(deftest bt-structure-test
  (testing "coact-behavior-tree produces a valid top-level BT"
    (let [bt-config (rca/coact-behavior-tree 20)]
      (is (= :sequence (first bt-config)))
      (is (= :coact.sequence/main (get-in bt-config [1 :id])))
      ;; Flat tree (no outer refinement :repeat, no finalize subtree — refinement
      ;; moved INTO the loop's answer path; FinalizeAnswer merged into
      ;; ThinkActCode):
      ;; [:sequence opts cond-q prep-conv prep-recall init loop-guard
      ;;  ensure-answer cond-a store maintain] = 11
      (is (= 11 (count bt-config)))))

  (testing "top-level BT runs init once, then the loop guard (no outer refine loop)"
    (let [bt-config (rca/coact-behavior-tree 20)
          ids (->> bt-config
                   (filter vector?)
                   (map #(get-in % [1 :id]))
                   set)]
      ;; init runs ONCE at top level now — not inside an outer :repeat.
      (is (contains? ids :coact.action/init))
      (is (contains? ids :coact.fallback/loop-guard))
      (is (contains? ids :coact.action/ensure-answer))
      (is (contains? ids :coact.action/store-results))
      ;; The outer refinement :repeat and finalize subtree are gone.
      (is (nil? (first (filter #(and (vector? %)
                                     (= :repeat (first %))
                                     (= :coact.repeat/refine (get-in % [1 :id])))
                               bt-config)))
          "outer refinement :repeat node is removed")
      (is (not (contains? ids :coact.fallback/finalize-guard))
          "finalize subtree is removed")))

  (testing "BT builds successfully with the BT engine"
    (let [bt-config (rca/coact-behavior-tree 10)
          context {:st-memory {:question "test question"
                               :tools []
                               :tools-fn-map {}}}
          built (bt/build bt-config context)]
      (is (some? (:tree built)))
      (is (some? (:context built)))
      (is (instance? clojure.lang.Atom (get-in built [:context :st-memory])))
      (is (= "test question" (:question @(get-in built [:context :st-memory])))))))

(deftest loop-subtree-structure-test
  (testing "loop subtree uses :repeat with a :terminated exit condition"
    (let [sub (rca/coact-loop-subtree 5)
          cond-fn (get-in sub [1 :condition-fn])]
      (is (= :repeat (first sub)))
      (is (= :coact.repeat/iterate (get-in sub [1 :id])))
      (is (fn? cond-fn))
      (is (fn? (get-in sub [1 :max-n])))
      ;; Exit only on :terminated (stamp-answer / fatal repair) — a non-blank
      ;; answer alone no longer exits (a rejected answer is blanked + re-looped).
      (is (true? (cond-fn {:st-memory (atom {:terminated true})})))
      (is (not (cond-fn {:st-memory (atom {:answer "non-blank but not terminated"})})))))

  (testing "iteration sequence contains router; answer path carries the hybrid gate"
    (let [sub (rca/coact-loop-subtree 5)
          iter-seq (nth sub 2)]
      (is (= :sequence (first iter-seq)))
      (is (= :coact.sequence/iteration (get-in iter-seq [1 :id])))
      (let [router (first (filter #(and (vector? %)
                                        (= :fallback (first %))
                                        (= :coact.fallback/router
                                           (get-in % [1 :id])))
                                  iter-seq))]
        (is (some? router))
        ;; Router is [:fallback opts answer-path code-path tool-path repair] = 6
        (is (= 6 (count router)))
        ;; The answer path now carries capture-answer-meta + the answer-gate
        ;; fallback (accept | refine-self | eval-confirm).
        (let [answer-path (first (filter #(and (vector? %)
                                               (= :coact.sequence/answer-path
                                                  (get-in % [1 :id])))
                                         router))
              ids (->> (tree-seq coll? seq answer-path)
                       (filter #(and (vector? %) (map? (second %))))
                       (keep #(get-in % [1 :id]))
                       set)]
          (is (contains? ids :coact.action/capture-answer-meta))
          (is (contains? ids :coact.fallback/answer-gate))
          (is (contains? ids :coact.action/stamp-answer))
          (is (contains? ids :coact.action/refine-self))
          (is (contains? ids :coact.action/prepare-eval))
          (is (contains? ids :coact.action/evaluate-answer))
          (is (contains? ids :coact.action/process-eval)))))))

;; ============================================================================
;; 2. ThinkActCode Signature Shape
;; ============================================================================

(deftest signature-shape-test
  (testing "ThinkActCode signature has 4 inputs and 5 outputs"
    (let [{:keys [input-keys output-keys]} (clj-llm/extract-signature-metadata
                                            @#'rca/ThinkActCode)]
      ;; :system-context and :user-context are stable-keys (embedded in the
      ;; system prompt by the BT dspy action), NOT signature inputs.
      ;; :recalled-memory IS a signature input (separate from :context-briefing
      ;; per coact_agent.clj L224-227 — wired by prepare-recalled-memory-action
      ;; and primed into st-memory before each turn).
      (is (= #{:question :context-briefing :iterations :recalled-memory}
             (set input-keys)))
      ;; :thought is NOT a signature output — reasoning comes from the
      ;; DSPy chain-of-thought layer (:last-reasoning in st-memory).
      ;; :goal-achieved / :next-user-prompt are the answer-channel
      ;; self-assessment outputs (the old standalone FinalizeAnswer pass, now
      ;; folded in — populated only when `answer` is non-blank).
      (is (= #{:tool-calls :code-blocks :answer :goal-achieved :next-user-prompt}
             (set output-keys))))))

(deftest stable-keys-wiring-test
  (testing "ThinkActCode BT node declares :system-context and :user-context as stable-keys"
    (let [sub (rca/coact-loop-subtree 3)
          iter-seq (nth sub 2)
          ;; Find the fallback containing the ThinkActCode action
          llm-guard (first (filter #(and (vector? %)
                                         (= :fallback (first %))
                                         (= :coact.fallback/llm-guard
                                            (get-in % [1 :id])))
                                   iter-seq))
          think-act-code (nth llm-guard 2)
          opts (second think-act-code)]
      (is (= :coact.action/think-act-code (:id opts)))
      ;; Ordered vector (prompt-cache Phase 1): declared order = zone/cache-
      ;; breakpoint order, most-stable first.
      (is (= [:system-context :user-context] (:stable-keys opts)))
      (is (= :chain-of-thought (:operation opts))))))

;; ============================================================================
;; 3. Agent Registration
;; ============================================================================

(deftest registration-test
  (testing "coact-agent is registered in the tool registry"
    (let [agent-defs (tool/get-tool-defs :type :agent)]
      (is (contains? agent-defs :coact-agent))
      (let [agent-def (get agent-defs :coact-agent)]
        (is (= :coact-agent (:id agent-def)))
        (is (some? (:fn agent-def)))
        (is (= :agent (:type agent-def)))))))

;; ============================================================================
;; 3a. Shared roster (agent-roster/default-agent-roster)
;; ============================================================================

(deftest shared-agent-roster-test
  (testing "coact and react both register the single shared roster, byte-identical"
    (let [agent-defs (tool/get-tool-defs :type :agent)
          coact-roster (get-in agent-defs [:coact-agent :meta :agent-tools])
          react-roster (get-in agent-defs [:react-agent :meta :agent-tools])]
      ;; Both resolve to the same shared def — no drift possible.
      (is (= agent-roster/default-agent-roster coact-roster))
      (is (= agent-roster/default-agent-roster react-roster))
      (is (= coact-roster react-roster)
          "coact and react must advertise the identical roster")
      ;; Sanity: the roster is the {:tools [...]} shape defagent expects and
      ;; is non-empty (all-common-tools ++ all-common-commands, de-duped).
      ;; Entries are raw tool refs (vars/fns) here — they become {:name …}
      ;; descriptors only later via tool/bind-tools — so uniqueness is checked
      ;; at the entry level, which is what the production `(distinct …)` ensures.
      (is (vector? (:tools coact-roster)))
      (is (pos? (count (:tools coact-roster))))
      (is (apply distinct? (:tools coact-roster))
          "shared roster must be de-duped"))))

;; ============================================================================
;; 3b. Tools-section compaction (:compact-agent-tools / :agent-tools-details)
;; ============================================================================

(deftest compact-agent-tools-tier-test
  (testing "build-tools-section renders the roster compactly when the
            :agent-tools-details tier is disabled, verbose otherwise — while
            keeping the roster present either way (so curated-syms still scopes
            the category index)."
    (let [build (resolve 'ai.brainyard.agent.common.coact-agent/build-tools-section)
          tools [{:name "test-coact-echo"
                  :description "Echo back the input string."
                  :tool-fn-type :command
                  :parameters {:properties {"text" {:type "string" :desc "Text to echo"}}
                               :required ["text"]}}]
          verbose (build {:agent-tools tools :disabled-tiers #{}})
          compact (build {:agent-tools tools :disabled-tiers #{:agent-tools-details}})]
      ;; Both render the roster (the tool id appears in each).
      (is (str/includes? verbose "test-coact-echo"))
      (is (str/includes? compact "test-coact-echo"))
      ;; Verbose carries full specs (an Inputs block); compact does not.
      (is (str/includes? verbose "Inputs:")
          "verbose block must carry per-tool input specs")
      (is (not (str/includes? compact "Inputs:"))
          "compact block must drop per-tool input specs")
      ;; Compact uses the bare `- `id`` one-liner shape (no registry type tag).
      (is (str/includes? compact "- `test-coact-echo`")
          "compact block must render the one-line `- `id`` shape")
      (is (not (str/includes? compact "test-coact-echo`\n  Inputs"))
          "compact block must not carry per-tool specs")))

  (testing ":compact-agent-tools config default is true (compact-by-default)"
    (is (true? (get config/default-config :compact-agent-tools)))))

;; ============================================================================
;; 3c. Code channel flag (:code-channel?) — the react-agent unification knob
;; ============================================================================

(deftest code-channel-flag-test
  (testing ":code-channel? defaults true (every non-react agent unchanged)"
    (is (true? (get config/default-config :code-channel?))))

  (let [sysctx (resolve 'ai.brainyard.agent.common.coact-agent/coact-system-context)
        code-keys [:execution-model :channel-routing :code-blocks-format
                   :sandbox-context-accessor]
        with-code (sysctx {:agent-tools [] :code-channel? true}  :return-breakdown? true)
        no-code   (sysctx {:agent-tools [] :code-channel? false} :return-breakdown? true)]

    (testing "default (code-channel? true) carries the code-blocks sections + 3-channel role"
      (doseq [k code-keys]
        (is (contains? (:sections with-code) k)
            (str "code-channel? true must keep " k)))
      (is (str/includes? (get-in with-code [:sections :role]) "three output channels")))

    (testing "code-channel? false drops every code-blocks section + uses the tool-only role"
      (doseq [k code-keys]
        (is (not (contains? (:sections no-code) k))
            (str "code-channel? false must drop " k)))
      (is (str/includes? (get-in no-code [:sections :role]) "two output channels"))
      ;; tool-calls format survives (still the live channel); large-results too.
      (is (contains? (:sections no-code) :tool-call-format))))

  (testing "build-tools-section is tool-only when code-channel? false (no sandbox hot-path)"
    (let [build (resolve 'ai.brainyard.agent.common.coact-agent/build-tools-section)
          tools [{:name "test-coact-echo" :description "Echo." :tool-fn-type :command
                  :parameters {:properties {"text" {:type "string" :desc "t"}} :required ["text"]}}]
          tool-only (build {:agent-tools tools :code-channel? false})]
      (is (str/includes? tool-only "test-coact-echo")
          "the agent-tools roster is still rendered")
      (is (not (str/includes? tool-only "Hot-path primitives"))
          "tool-only mode drops the sandbox hot-path table"))))

;; ============================================================================
;; 4. Critical Rules & Prompt Content
;; ============================================================================

(deftest critical-rules-content-test
  (testing "critical-rules contains the iteration-1 no-history rule"
    ;; Access the private var via #'. The rules text was tightened in the
    ;; CoAct refactor — the "answer terminates the loop" / "pick one channel"
    ;; teaching moved into coact-role and the signature docstring. What stays
    ;; pinned here is the three discipline rules that the LLM needs to obey
    ;; turn-by-turn: iteration-1 history-honesty, FINAL is disabled, and the
    ;; (usage$guide :topic) pointer for deep-dive guides.
    (let [rules @(resolve 'ai.brainyard.agent.common.coact-agent/coact-critical-rules)]
      (is (str/includes? rules "Iteration 1 has no history"))
      (is (str/includes? rules "FINAL")
          "must teach that (FINAL ...) is disabled in CoAct")
      (is (str/includes? rules "populating the signature's `answer`")
          "must teach that termination is via the :answer output, not (FINAL …)")
      (is (str/includes? rules "(usage$guide :")
          "must point at the (usage$guide :topic) dispatcher")))

  (testing "channel-routing section mentions iteration-1 answer"
    (let [routing @(resolve 'ai.brainyard.agent.common.coact-agent/coact-channel-routing)]
      (is (str/includes? routing "iteration 1"))
      (is (str/includes? routing "greetings"))))

  (testing "coact-role teaches the three channels + one-channel-per-turn rule"
    ;; The "pick exactly one of (1), (2), (3)" teaching that used to live
    ;; in critical-rules now lives in coact-role.
    (let [role @(resolve 'ai.brainyard.agent.common.coact-agent/coact-role)]
      (is (str/includes? role "tool-calls"))
      (is (str/includes? role "code-blocks"))
      (is (str/includes? role "answer"))
      (is (str/includes? role "Pick exactly one")
          "must enforce one-channel-per-turn discipline"))))

(deftest project-memory-section-test
  (let [fmt (resolve 'ai.brainyard.agent.common.coact-agent/format-project-memory-section)
        sys (resolve 'ai.brainyard.agent.common.coact-agent/coact-system-context)]
    (testing "renders the protocol + live index when content is present"
      (let [out (fmt {:content "- [Auth](auth.md) — jwt flow" :max-chars 4000})]
        (is (str/includes? out "## Project Memory (.brainyard/memory/)"))
        (is (str/includes? out "RECALL"))
        (is (str/includes? out "REMEMBER"))
        (is (str/includes? out "### Index"))
        (is (str/includes? out "- [Auth](auth.md) — jwt flow"))))

    (testing "empty-state stub when no index exists yet"
      (let [out (fmt {:content nil :max-chars 4000})]
        (is (str/includes? out "## Project Memory"))
        (is (str/includes? out "empty — no memories yet"))))

    (testing "truncates an oversized index to ~max-chars (+ protocol preamble)"
      (let [big  (apply str (repeat 5000 "x"))
            full (fmt {:content big :max-chars 4000})
            out  (fmt {:content big :max-chars 100})]
        (is (str/includes? out "index truncated"))
        ;; capped index keeps the output far below the untruncated render
        (is (< (count out) (count full)))
        (is (< (count out) 2000))))

    (testing "coact-system-context includes the section only when :project-memory is non-nil"
      (let [with    (:sections (sys {:project-memory {:content "- [X](x.md) — note"}}
                                    :return-breakdown? true))
            without (:sections (sys {:project-memory nil} :return-breakdown? true))
            order   (:order (sys {:project-memory {:content "x"}} :return-breakdown? true))]
        (is (contains? with :project-memory))
        (is (str/includes? (:project-memory with) "- [X](x.md) — note"))
        (is (not (contains? without :project-memory)))
        ;; sits between project- and user-instructions in display order
        (is (< (.indexOf ^java.util.List order :project-instructions)
               (.indexOf ^java.util.List order :project-memory)
               (.indexOf ^java.util.List order :user-instructions)))))))

(deftest skill-substrate-test
  (testing "coact-system-context always carries the ## Using a skill section"
    (let [sys      (deref #'rca/coact-system-context)
          {:keys [sections order]} (sys {:instruction "I"} :return-breakdown? true)]
      (is (contains? sections :skill-substrate))
      (is (str/includes? (:skill-substrate sections) "Using a skill"))
      (is (str/includes? (:skill-substrate sections) "skills$find"))
      ;; consult-before-acting cousins sit together: project-memory then skill
      (is (< (.indexOf ^java.util.List order :project-memory)
             (.indexOf ^java.util.List order :skill-substrate)
             (.indexOf ^java.util.List order :user-instructions)))))

  (testing "the skills READ subset rides default-agent-roster; WRITE subset does not"
    (let [ids (set (map (comp :id meta deref)
                        (:tools ai.brainyard.agent.common.agent-roster/default-agent-roster)))]
      (is (contains? ids :skills$find))
      (is (contains? ids :skills$read))
      (is (contains? ids :skills$list))
      (is (contains? ids :skills$reload))
      (is (not (contains? ids :skills$write)))
      (is (not (contains? ids :skills$install))))))

(deftest mcp-substrate-test
  (testing "coact-system-context always carries the ## Using MCP servers section"
    (let [sys      (deref #'rca/coact-system-context)
          {:keys [sections order]} (sys {:instruction "I"} :return-breakdown? true)]
      (is (contains? sections :mcp-substrate))
      (is (str/includes? (:mcp-substrate sections) "Using MCP servers"))
      (is (str/includes? (:mcp-substrate sections) "mcp$server"))
      ;; discover→use cousins together: skill-substrate then mcp-substrate
      (is (< (.indexOf ^java.util.List order :skill-substrate)
             (.indexOf ^java.util.List order :mcp-substrate)
             (.indexOf ^java.util.List order :user-instructions)))))

  (testing "the MCP command family rides default-agent-roster"
    (let [ids (set (map (comp :id meta deref)
                        (:tools ai.brainyard.agent.common.agent-roster/default-agent-roster)))]
      (is (contains? ids :mcp$server))
      (is (contains? ids :mcp$tools))
      (is (contains? ids :mcp$lifecycle)))))

;; ============================================================================
;; 5. Action-Level Unit Tests
;; ============================================================================

(defn- fresh-st-memory [& {:as kvs}]
  (atom (merge {:iteration-count 0
                :iterations []
                :answer ""
                :terminated false
                :tool-calls []
                :code-blocks ""}
               kvs)))

(deftest inc-iter-action-test
  (testing "coact-inc-iter-action increments counter and resets scratch"
    (let [st (fresh-st-memory :iteration-count 3
                              :tool-calls [{:tool-name "x" :tool-args []}]
                              :code-blocks "```clojure\n1\n```"
                              :last-reasoning "prior CoT reasoning"
                              :last-channel :code
                              :last-tool-results [{:tool-name "x"}]
                              :last-code-results [{:lang "clojure"}]
                              :eval-display [{:code "(+ 1 2)" :result "3"}])]
      (rca/coact-inc-iter-action {:st-memory st})
      (is (= 4 (:iteration-count @st)))
      (is (empty? (:tool-calls @st)))
      (is (str/blank? (:code-blocks @st)))
      ;; :last-reasoning is cleared so the DSPy CoT layer re-populates it
      (is (nil? (:last-reasoning @st)))
      (is (nil? (:last-channel @st)))
      (is (empty? (:last-tool-results @st)))
      (is (empty? (:last-code-results @st)))
      ;; :eval-display must be nil — the TUI's :eval-result render-branch uses
      ;; `when-let` on :eval-display, so nil prevents a non-code iteration from
      ;; re-rendering the previous iteration's output.
      (is (nil? (:eval-display @st))))))

;; --- Predicates ---

(deftest predicates-test
  (testing "answer-non-blank? predicate"
    (is (false? (rca/coact-answer-non-blank? {:st-memory (fresh-st-memory :answer "")})))
    (is (false? (rca/coact-answer-non-blank? {:st-memory (fresh-st-memory :answer "   ")})))
    (is (false? (rca/coact-answer-non-blank? {:st-memory (fresh-st-memory :answer nil)})))
    (is (true? (rca/coact-answer-non-blank? {:st-memory (fresh-st-memory :answer "## done")}))))

  (testing "has-code-blocks? predicate"
    (is (false? (rca/coact-has-code-blocks? {:st-memory (fresh-st-memory :code-blocks "")})))
    (is (false? (rca/coact-has-code-blocks?
                 {:st-memory (fresh-st-memory :code-blocks "   ")}))
        "blank/whitespace-only field → false")
    (is (false? (rca/coact-has-code-blocks?
                 {:st-memory (fresh-st-memory :code-blocks
                                              "I will now write a Clojure code block to filter the servers.")}))
        "prose without a fenced block → false (root cause of the :blocks 0 loop)")
    (is (false? (rca/coact-has-code-blocks?
                 {:st-memory (fresh-st-memory :code-blocks "(println :hello)")}))
        "bare Clojure expression without ```lang fences → false")
    (is (true? (rca/coact-has-code-blocks?
                {:st-memory (fresh-st-memory :code-blocks "```clojure\n1\n```")}))
        "properly fenced clojure block → true"))

  (testing "has-tool-calls? predicate"
    (is (false? (rca/coact-has-tool-calls? {:st-memory (fresh-st-memory :tool-calls [])})))
    (is (true? (rca/coact-has-tool-calls?
                {:st-memory (fresh-st-memory
                             :tool-calls [{:tool-name "x" :tool-args []}])})))))

;; --- Stamp channel ---

(deftest stamp-channel-factory-test
  (testing "coact-stamp-channel returns an action that sets :last-channel"
    (let [st (fresh-st-memory)
          stamp-code (rca/coact-stamp-channel :code)
          result (stamp-code {:st-memory st})]
      (is (= bt/success result))
      (is (= :code (:last-channel @st)))))
  (testing "a populated channel resets the malformed-output streak"
    (let [st (fresh-st-memory :consecutive-llm-failures 2)]
      ((rca/coact-stamp-channel :tool) {:st-memory st})
      (is (= 0 (:consecutive-llm-failures @st))
          "a productive (tool/code) iteration breaks any prior malformed streak"))))

(deftest stamp-answer-action-test
  (testing "coact-stamp-answer-action fires the TUI watcher path"
    (let [st (fresh-st-memory :answer "## ok"
                              :display-stage :think
                              ;; Stale entries from a prior code iteration
                              :eval-display [{:code "(+ 1 2)" :result "3"}])
          _ (swap! st assoc :consecutive-llm-failures 2)
          result (rca/coact-stamp-answer-action {:st-memory st})]
      (is (= bt/success result))
      (is (= :answer (:last-channel @st)))
      (is (true? (:terminated @st)))
      (is (= :answer-channel (:terminated-by @st)))
      (is (= 0 (:consecutive-llm-failures @st))
          "terminal answer resets the malformed-output streak")))
  (testing "a pre-set :terminated-by is preserved (e.g. an llm-error abort that
            stamped :answer in the llm-guard slot before the router's Path A)"
    (let [st (fresh-st-memory :answer "Agent stopped: boom"
                              :terminated-by :llm-error)]
      (rca/coact-stamp-answer-action {:st-memory st})
      (is (= :llm-error (:terminated-by @st))
          "router Path A must not overwrite the abort reason with :answer-channel")
      ;; Flips to :eval-result so the TUI's final-code-shown? watcher fires
      (is (= :eval-result (:display-stage @st)))
      ;; Clears :eval-display so the TUI doesn't re-render the prior
      ;; iteration's Code/Output on this (code-less) answer iteration.
      (is (nil? (:eval-display @st))))))

;; --- In-loop refinement: hybrid gate, feedback injection, eval processing ---

(deftest capture-answer-meta-decision-test
  (testing "budget exhausted (round >= max-refinements) -> :accept"
    (with-redefs [config/get-config (fn [_ k] (when (= k :max-refinements) 2))]
      (let [st (fresh-st-memory :answer "## done" :goal-achieved false
                                :refinement-round 2)]
        (rca/coact-capture-answer-meta {:st-memory st :agent :stub})
        (is (= :accept (:answer-decision @st)))
        (is (= "## done" (:pending-answer @st))))))
  (testing "goal-achieved=true with budget remaining -> :needs-eval"
    (with-redefs [config/get-config (fn [_ k] (when (= k :max-refinements) 2))]
      (let [st (fresh-st-memory :answer "## done" :goal-achieved true
                                :refinement-round 0)]
        (rca/coact-capture-answer-meta {:st-memory st :agent :stub})
        (is (= :needs-eval (:answer-decision @st))))))
  (testing "goal-achieved=false with budget remaining -> :refine-self"
    (with-redefs [config/get-config (fn [_ k] (when (= k :max-refinements) 2))]
      (let [st (fresh-st-memory :answer "## partial" :goal-achieved false
                                :refinement-round 0)]
        (rca/coact-capture-answer-meta {:st-memory st :agent :stub})
        (is (= :refine-self (:answer-decision @st))))))
  (testing "max-refinements=0 always accepts (low/medium effort)"
    ;; Hermetic: pin max-refinements to 0 like the sibling branches rather than
    ;; relying on the nil-agent fallthrough, which reads the ambient
    ;; .brainyard/config.edn (this repo sets :max-refinements 2, so the
    ;; fallthrough would resolve 2 and yield :needs-eval).
    (with-redefs [config/get-config (fn [_ k] (when (= k :max-refinements) 0))]
      (let [st (fresh-st-memory :answer "## done" :goal-achieved true
                                :refinement-round 0)]
        (rca/coact-capture-answer-meta {:st-memory st :agent nil})
        (is (= :accept (:answer-decision @st)))))))

(deftest answer-decision-is-test
  (testing "factory matches the stamped :answer-decision"
    (let [st (fresh-st-memory :answer-decision :refine-self)]
      (is (true? ((rca/answer-decision-is? :refine-self) {:st-memory st})))
      (is (not ((rca/answer-decision-is? :accept) {:st-memory st}))))))

(deftest refine-self-action-test
  (testing "injects an [evaluation] record, blanks answer, bumps round, does NOT terminate"
    (let [st (fresh-st-memory :answer "## partial"
                              :pending-answer "## partial"
                              :next-user-prompt "add the missing totals"
                              :iteration-count 3
                              :refinement-round 0
                              :iterations [{:iteration 1 :channel "code"
                                            :tool-results [] :code-results []}])
          result (rca/coact-refine-self-action {:st-memory st})
          iters (:iterations @st)
          eval-rec (last iters)]
      (is (= bt/success result))
      (is (= "" (:answer @st)) "answer blanked so the loop continues")
      (is (not (:terminated @st)) "refinement does not terminate the loop")
      (is (= 1 (:refinement-round @st)) "round incremented")
      (is (= "## partial" (:previous-answer @st)))
      (is (= "evaluation" (:channel eval-rec)))
      (is (= "SELF" (:verdict eval-rec)))
      (is (str/includes? (:feedback eval-rec) "add the missing totals"))
      (is (= "## partial" (:rejected-answer eval-rec))))))

(deftest process-eval-in-loop-test
  (testing "COMPLETE verdict -> accept and terminate"
    (let [st (fresh-st-memory :answer "## done" :pending-answer "## done"
                              :goal-achieved true :verdict "COMPLETE"
                              :detail "looks good" :refinement-round 0)
          result (rca/coact-process-eval-in-loop-action {:st-memory st :agent nil})]
      (is (= bt/success result))
      (is (true? (:terminated @st)) "COMPLETE terminates via stamp-answer")
      (is (= :answer (:last-channel @st)))
      (is (true? (:goal-achieved @st)))))
  (testing "INCOMPLETE verdict -> inject feedback and continue"
    (let [st (fresh-st-memory :answer "## thin" :pending-answer "## thin"
                              :goal-achieved true :verdict "INCOMPLETE"
                              :detail "missing the per-region breakdown"
                              :iteration-count 4 :refinement-round 0
                              :iterations [])
          result (rca/coact-process-eval-in-loop-action {:st-memory st :agent nil})
          eval-rec (last (:iterations @st))]
      (is (= bt/success result))
      (is (not (:terminated @st)) "INCOMPLETE keeps looping")
      (is (= "" (:answer @st)))
      (is (= 1 (:refinement-round @st)))
      (is (= "INCOMPLETE" (:verdict eval-rec)))
      (is (str/includes? (:feedback eval-rec) "per-region breakdown"))))
  (testing "HALLUCINATED verdict -> inject feedback and continue"
    (let [st (fresh-st-memory :answer "## made-up" :pending-answer "## made-up"
                              :goal-achieved true :verdict "HALLUCINATED"
                              :detail "invented the revenue figure"
                              :iteration-count 4 :refinement-round 1
                              :iterations [])
          result (rca/coact-process-eval-in-loop-action {:st-memory st :agent nil})
          eval-rec (last (:iterations @st))]
      (is (= bt/success result))
      (is (not (:terminated @st)))
      (is (= 2 (:refinement-round @st)))
      (is (= "HALLUCINATED" (:verdict eval-rec)))
      (is (str/includes? (:feedback eval-rec) "HALLUCINATED")))))

(deftest prepare-eval-action-test
  (testing "no evidence -> phase :skipped (eval-needs-llm? false -> accept-on-skip)"
    (let [st (fresh-st-memory :answer "general-knowledge answer"
                              :iterations [{:iteration 1 :channel "none"
                                            :tool-results [] :code-results []}])]
      (rca/coact-prepare-eval-action {:st-memory st :agent nil})
      (is (= :skipped (get-in @st [:evaluation-status :phase])))
      (is (not (rca/coact-eval-needs-llm? {:st-memory st})))))
  (testing "with evidence -> phase :llm-calling (eval-needs-llm? true)"
    (let [st (fresh-st-memory
              :answer "## from tools"
              :iterations [{:iteration 1 :channel "tool"
                            :tool-results [{:tool-name "fetch"
                                            :tool-args {}
                                            :tool-result "REGION=us rev=42"}]
                            :code-results []}])]
      (rca/coact-prepare-eval-action {:st-memory st :agent nil})
      (is (= :llm-calling (get-in @st [:evaluation-status :phase])))
      (is (rca/coact-eval-needs-llm? {:st-memory st})))))

(deftest format-iterations-evaluation-record-test
  (testing "format-iterations-block renders an [evaluation] record with verdict + feedback"
    (let [render @#'rca/format-iterations-block
          out (render [{:iteration 5 :channel "evaluation"
                        :rejected-answer "## partial answer text"
                        :verdict "INCOMPLETE"
                        :feedback "add the per-region totals"
                        :tool-results [] :code-results []}])]
      (is (str/includes? out "[evaluation]"))
      (is (str/includes? out "REJECTED INCOMPLETE"))
      (is (str/includes? out "add the per-region totals"))
      (is (str/includes? out "## partial answer text")))))

;; --- Tool dispatch (via a mock defcommand) ---

(deftest tool-dispatch-action-test
  (testing "coact-tool-dispatch-action calls the tool and populates :last-tool-results"
    (let [st (fresh-st-memory
              :tool-calls [{:tool-name "test-coact-echo"
                            :tool-args [{:name "text" :value "hi"}]}])
          ctx {:st-memory st :agent nil :opts {}}]
      (rca/coact-tool-dispatch-action ctx)
      (is (= :tool (:last-channel @st)))
      (is (= 1 (count (:last-tool-results @st))))
      (let [{:keys [tool-name tool-result]} (first (:last-tool-results @st))]
        (is (= "test-coact-echo" tool-name))
        (is (str/includes? tool-result "echo: hi"))))))

;; --- Code eval: empty / sequential clojure / FINAL promotion ---

(defn- make-minimal-sandbox []
  (clj-sandbox/create-sandbox :context {} :bindings {}))

(deftest code-eval-empty-blocks-test
  (testing "empty code-blocks produces an error eval-entry"
    (let [sb (make-minimal-sandbox)
          st (fresh-st-memory :sandbox sb :code-blocks "")]
      (rca/coact-code-eval-action {:st-memory st :agent nil})
      (is (= :code (:last-channel @st)))
      (is (= 1 (count (:last-code-results @st))))
      (is (str/includes? (:error (first (:last-code-results @st)))
                         "No fenced code blocks")))))

(deftest code-eval-sequential-clojure-test
  (testing "sequential clojure fence evaluates in the sandbox"
    (let [sb (make-minimal-sandbox)
          st (fresh-st-memory :sandbox sb
                              :code-blocks "```clojure\n(+ 1 2 3)\n```")]
      (rca/coact-code-eval-action {:st-memory st :agent nil})
      (let [entries (:last-code-results @st)]
        (is (= 1 (count entries)))
        (let [e (first entries)]
          (is (= "clojure" (:lang e)))
          (is (= "6" (:result e)))
          (is (false? (:parallel? e))))))))

(deftest code-eval-final-disabled-test
  (testing "FINAL in a clojure fence is disabled — surfaces error nudge, no :answer promotion"
    (let [sb (make-minimal-sandbox)
          st (fresh-st-memory :sandbox sb
                              :code-blocks "```clojure\n(FINAL \"done-via-FINAL\")\n```")]
      (rca/coact-code-eval-action {:st-memory st :agent nil})
      ;; FINAL is intercepted as an error entry; :answer stays blank so the
      ;; loop does not terminate. The LLM must use the signature's :answer
      ;; output field instead.
      (is (= "" (:answer @st))
          ":answer must remain blank — FINAL no longer terminates CoAct")
      (is (= :code (:last-channel @st)))
      (let [entry (first (:last-code-results @st))]
        (is (= "clojure" (:lang entry)))
        (is (clojure.string/includes? (:error entry) "FINAL is not supported"))))))

(deftest code-eval-parallel-final-rejected-test
  (testing "FINAL inside a parallel partition does NOT promote :answer"
    (let [sb (make-minimal-sandbox)
          code-blocks (str "```clojure\n(def x 1)\n```\n"
                           "<!-- ParallelBlock -->\n"
                           "```clojure\n(def y 2)\n```")
          st (fresh-st-memory :sandbox sb
                              :code-blocks code-blocks
                              :answer "")]
      (rca/coact-code-eval-action {:st-memory st :agent nil})
      ;; Even with completely benign parallel blocks, the answer should remain blank
      ;; (no FINAL was called, and parallel mode disables the promotion path entirely).
      (is (str/blank? (:answer @st)))
      (is (= :code (:last-channel @st)))
      (is (every? :parallel? (:last-code-results @st))))))

;; --- Parallel-mode detection ---

(deftest parallel-mode-detection-test
  (testing "presence of <!-- ParallelBlock --> flips the whole iteration to parallel"
    (let [parallel-re (partial re-find #"(?m)^\s*<!--\s*ParallelBlock\s*-->\s*$")]
      (is (nil? (parallel-re "```bash\nls\n```")))
      (is (some? (parallel-re "```bash\nls\n```\n<!-- ParallelBlock -->\n```bash\npwd\n```")))
      (is (some? (parallel-re "   <!-- ParallelBlock -->   \n"))))))

;; --- run-script-block timeout surface (parity with run-clj-sandbox-block's :status :pending) ---

(deftest run-script-block-bash-success-test
  (testing "run-script-block returns exit-code \"0\" for a fast bash script"
    (let [entry (@#'rca/run-script-block "bash" "echo hello-from-script" 5000)]
      (is (= "bash" (:lang entry)))
      (is (= "0" (:result entry)))
      (is (nil? (:status entry)) "no :status key on normal completion")
      (is (re-find #"hello-from-script" (:output entry)))
      (is (= "" (:error entry))))))

;; --- auto-background detach: every slow code-block surfaces :pending + :task-id ---

(deftest run-clj-sandbox-block-auto-detach-yields-pending-test
  (testing "a slow clojure block auto-detaches into background → :status :pending + :task-id"
    (let [sb (make-minimal-sandbox)
          entry (@#'rca/run-clj-sandbox-block sb "(do (Thread/sleep 5000) :done)" 200)]
      (is (= "clojure" (:lang entry)))
      (is (= :pending (:status entry))
          "auto-detach path → :status :pending; task survives in the manager registry")
      (is (string? (:task-id entry)) ":task-id surfaced so the next iteration can harvest")
      (is (re-find #"\[detached to background after 200ms" (:output entry)))
      (is (= "" (:error entry))))))

(deftest run-script-block-auto-detach-yields-pending-test
  (testing "a slow bash block auto-detaches into background → :status :pending + :task-id"
    (let [start (System/currentTimeMillis)
          entry (@#'rca/run-script-block "bash" "sleep 5" 300)
          elapsed (- (System/currentTimeMillis) start)]
      (is (= "bash" (:lang entry)))
      (is (= :pending (:status entry)) "shell blocks share the same auto-detach contract")
      (is (string? (:task-id entry)))
      (is (re-find #"\[detached to background after 300ms" (:output entry)))
      (is (< elapsed 3000)
          (str "returned within 3s of auto-bg deadline (actual " elapsed "ms)")))))

(deftest code-eval-action-uses-auto-background-timeout-config-test
  (testing "coact-code-eval-action reads :auto-background-timeout-ms from agent config; slow eval → :pending"
    (let [sb (make-minimal-sandbox)
          st (fresh-st-memory :sandbox sb
                              :code-blocks "```clojure\n(do (Thread/sleep 3000) :slow)\n```")
          orig-get-config ai.brainyard.agent.core.config/get-config]
      (with-redefs [ai.brainyard.agent.core.config/get-config
                    (fn
                      ([k]
                       (case k
                         :auto-background-timeout-ms 200
                         :fast-eval-timeout-ms       50
                         (orig-get-config k)))
                      ([agent k]
                       (case k
                         :auto-background-timeout-ms 200
                         :fast-eval-timeout-ms       50
                         (orig-get-config agent k))))]
        (rca/coact-code-eval-action {:st-memory st :agent nil}))
      (let [entry (first (:last-code-results @st))]
        (is (= :pending (:status entry))
            "slow eval auto-detaches at the configured auto-bg deadline")
        (is (some? (:task-id entry))
            ":task-id lets a later iteration's harvest pick it up")))))

;; --- Accumulate iteration record ---

(deftest accumulate-tool-channel-test
  (testing "accumulate records a tool-channel iteration (thought from :last-reasoning)"
    (let [st (fresh-st-memory
              :iteration-count 1
              :last-reasoning "reason"
              :last-channel :tool
              :last-tool-results [{:tool-name "t" :tool-args {} :tool-result "r"}])]
      (rca/coact-accumulate-iteration-action {:st-memory st})
      (let [iters (:iterations @st)]
        (is (= 1 (count iters)))
        (let [rec (first iters)]
          (is (= 1 (:iteration rec)))
          (is (= "reason" (:thought rec))
              "iteration :thought sourced from :last-reasoning (CoT layer)")
          (is (= "tool" (:channel rec)))
          (is (= 1 (count (:tool-results rec))))
          (is (empty? (:code-results rec))))))))

(deftest accumulate-code-channel-test
  (testing "accumulate records a code-channel iteration"
    (let [st (fresh-st-memory
              :iteration-count 2
              :last-reasoning "compose"
              :last-channel :code
              :last-code-results [{:lang "clojure" :code "(+ 1 2)"
                                   :result "3" :output "" :error ""
                                   :parallel? false}])]
      (rca/coact-accumulate-iteration-action {:st-memory st})
      (let [rec (last (:iterations @st))]
        (is (= "code" (:channel rec)))
        (is (= "compose" (:thought rec)))
        (is (= 1 (count (:code-results rec))))
        (is (empty? (:tool-results rec)))))))

(deftest accumulate-none-channel-test
  (testing "repair (:none) channel also accumulates the code-results note"
    (let [st (fresh-st-memory
              :iteration-count 3
              :last-reasoning nil
              :last-channel :none
              :last-code-results [{:lang "other" :code "" :result ""
                                   :output "" :error "no action"
                                   :parallel? false}])]
      (rca/coact-accumulate-iteration-action {:st-memory st})
      (let [rec (last (:iterations @st))]
        (is (= "none" (:channel rec)))
        (is (= "" (:thought rec))
            "nil :last-reasoning falls back to empty string")
        (is (str/includes? (:error (first (:code-results rec))) "no action"))))))

(deftest accumulate-answer-channel-skipped-test
  (testing "answer channel (:last-channel nil because no stamp) is NOT appended"
    (let [st (fresh-st-memory :iteration-count 4
                              :last-channel nil
                              :answer "## done"
                              :last-reasoning "final")]
      (rca/coact-accumulate-iteration-action {:st-memory st})
      (is (empty? (:iterations @st))))))

(deftest accumulate-caps-at-10-test
  (testing "iterations are capped at the most recent 10"
    (let [existing (mapv (fn [i]
                           {:iteration i :thought "" :channel "code"
                            :tool-results [] :code-results []})
                         (range 1 11))
          st (fresh-st-memory :iteration-count 11
                              :last-reasoning "next"
                              :last-channel :tool
                              :last-tool-results [{:tool-name "t" :tool-args {}
                                                   :tool-result "r"}]
                              :iterations existing)]
      (rca/coact-accumulate-iteration-action {:st-memory st})
      (let [iters (:iterations @st)]
        (is (= 10 (count iters)))
        (is (= 2 (:iteration (first iters))))
        (is (= 11 (:iteration (last iters))))))))

;; --- Repair ---

(deftest repair-action-test
  (testing "repair-action marks :none channel and pushes a nudge eval-result"
    (let [st (fresh-st-memory :tool-calls [] :code-blocks "" :answer "")]
      (rca/coact-repair-action {:st-memory st})
      (is (= :none (:last-channel @st)))
      (is (= 1 (count (:last-code-results @st))))
      (is (str/includes? (:error (first (:last-code-results @st)))
                         "no action this iteration"))
      (is (false? (boolean (:terminated @st)))
          "1st :none must NOT terminate the turn"))))

(deftest repair-action-second-none-still-nudges-test
  (testing "second consecutive :none still nudges, does not yet escalate"
    (let [st (fresh-st-memory
              :tool-calls [] :code-blocks "" :answer ""
              :iterations [{:iteration 1 :channel "none"
                            :tool-results [] :code-results []}])]
      (rca/coact-repair-action {:st-memory st})
      (is (= :none (:last-channel @st)))
      (is (false? (boolean (:terminated @st)))
          "2nd :none must NOT terminate the turn — the LLM still gets a nudge")
      (is (str/includes? (:error (first (:last-code-results @st)))
                         "no action this iteration")))))

(deftest repair-action-loop-guard-escalation-test
  (testing "exceeding max-retries-on-llm-no-action (default 3) escalates: terminate with synthesized answer"
    (let [st (fresh-st-memory
              :tool-calls [] :code-blocks "" :answer ""
              :iteration-count 5
              :iterations [{:iteration 1 :channel "tool"
                            :tool-results [{:tool-name "mcp$server"
                                            :tool-args {:op "list"}
                                            :tool-result "{:servers […]}"}]
                            :code-results []}
                           {:iteration 2 :channel "none"
                            :tool-results [] :code-results []}
                           {:iteration 3 :channel "none"
                            :tool-results [] :code-results []}
                           {:iteration 4 :channel "none"
                            :tool-results [] :code-results []}])]
      (rca/coact-repair-action {:st-memory st})
      (is (true? (boolean (:terminated @st)))
          "4th consecutive :none (streak ≥ 3) must terminate the turn")
      (is (= :answer (:last-channel @st)))
      (is (= :none-channel-loop-guard (:terminated-by @st)))
      (is (= :eval-result (:display-stage @st))
          "must mirror coact-stamp-answer-action's TUI-watcher state")
      (is (str/includes? (:answer @st) "Loop guard")
          "answer must mention the loop guard so the user knows why we stopped")
      (is (str/includes? (:answer @st) "{:servers")
          "answer must surface the latest tool-result so progress isn't lost"))))

(deftest repair-action-loop-guard-no-prior-tool-result-test
  (testing "loop-guard escalation still terminates when no tool ran this turn"
    (let [st (fresh-st-memory
              :tool-calls [] :code-blocks "" :answer ""
              :iterations [{:iteration 1 :channel "none"
                            :tool-results [] :code-results []}
                           {:iteration 2 :channel "none"
                            :tool-results [] :code-results []}
                           {:iteration 3 :channel "none"
                            :tool-results [] :code-results []}])]
      (rca/coact-repair-action {:st-memory st})
      (is (true? (boolean (:terminated @st))))
      (is (str/includes? (:answer @st) "Loop guard")
          "still terminates with the loop-guard notice even with no tool result"))))

(deftest repair-action-loop-guard-surfaces-latest-thought-test
  (testing "with no tool result but a reasoning trail, the guard surfaces the
            latest thought instead of a content-free placeholder"
    (let [st (fresh-st-memory
              :tool-calls [] :code-blocks "" :answer ""
              :iterations [{:iteration 1 :channel "none"
                            :thought "I should look at the config file first"
                            :tool-results [] :code-results []}
                           {:iteration 2 :channel "none"
                            :thought "Checking the next option"
                            :tool-results [] :code-results []}
                           {:iteration 3 :channel "none"
                            :thought "Still deciding how to start"
                            :tool-results [] :code-results []}])]
      (rca/coact-repair-action {:st-memory st})
      (is (true? (boolean (:terminated @st))))
      (is (str/includes? (:answer @st) "Still deciding how to start")
          "surfaces the most recent reasoning so the user sees where it got stuck")
      (is (not (str/includes? (:answer @st) "no progress captured"))))))

(deftest repair-action-non-consecutive-none-test
  (testing "an interrupting tool-channel iter resets the :none streak"
    (let [st (fresh-st-memory
              :tool-calls [] :code-blocks "" :answer ""
              :iterations [{:iteration 1 :channel "none"
                            :tool-results [] :code-results []}
                           {:iteration 2 :channel "tool"
                            :tool-results [{:tool-name "t"
                                            :tool-args {}
                                            :tool-result "r"}]
                            :code-results []}
                           {:iteration 3 :channel "none"
                            :tool-results [] :code-results []}])]
      (rca/coact-repair-action {:st-memory st})
      (is (false? (boolean (:terminated @st)))
          "only 1 trailing :none → still in nudge mode, not escalation"))))

;; --- Ensure-answer: max-iterations exhaustion must never exit with nil answer ---

(deftest ensure-answer-backfills-on-exhaustion-test
  (testing "a blank answer after the loop is backfilled from the trajectory"
    ;; The :none-streak guard only catches CONSECUTIVE empties; a turn that
    ;; keeps calling tools/code productively but never sets :answer reaches
    ;; max-iterations with a blank answer. The :repeat returns :success on
    ;; exhaustion, so without ensure-answer the downstream answer-present gate
    ;; fails → BT :failure with a nil answer.
    (let [st (fresh-st-memory
              :answer ""
              :iteration-count 100
              :iterations [{:iteration 99 :channel "tool"
                            :thought "grepping"
                            :tool-results [{:tool-name "grep" :tool-args {}
                                            :tool-result "found 3 matches"}]
                            :code-results []}
                           {:iteration 100 :channel "code"
                            :thought "still working" :tool-results []
                            :code-results []}])]
      (rca/coact-ensure-answer-action {:st-memory st})
      (is (not (str/blank? (:answer @st)))
          "exhaustion must leave a non-blank answer so :cond/answer-present passes")
      (is (true? (boolean (:terminated @st))))
      (is (= :max-iterations-exhausted (:terminated-by @st)))
      (is (= :answer (:last-channel @st)))
      (is (= :eval-result (:display-stage @st))
          "must mirror coact-stamp-answer-action's TUI-watcher state")
      (is (str/includes? (:answer @st) "found 3 matches")
          "best-effort answer surfaces the latest captured progress"))))

(deftest ensure-answer-noop-when-answer-present-test
  (testing "a real answer is left completely untouched"
    (let [st (fresh-st-memory :answer "the real answer" :terminated true
                              :iterations [{:iteration 1 :channel "tool"
                                            :tool-results [] :code-results []}])]
      (rca/coact-ensure-answer-action {:st-memory st})
      (is (= "the real answer" (:answer @st)))
      (is (nil? (:terminated-by @st))
          "no-op path must not stamp a terminated-by reason"))))

(deftest ensure-answer-empty-iterations-test
  (testing "no progress at all still yields a non-blank answer (never a BT failure)"
    (let [st (fresh-st-memory :answer "" :iterations [])]
      (rca/coact-ensure-answer-action {:st-memory st})
      (is (not (str/blank? (:answer @st))))
      (is (= :max-iterations-exhausted (:terminated-by @st))))))

;; --- Empty-result retry with exponential backoff ---

(deftest backoff-delay-exponential-test
  (testing "backoff-delay-ms grows exponentially: base * 2^(attempt-1)"
    (let [f #'rca/backoff-delay-ms]
      (is (= 1000 (f 1 1000)))
      (is (= 2000 (f 2 1000)))
      (is (= 4000 (f 3 1000)))
      (is (= 8000 (f 4 1000)))
      (is (= 250  (f 1 250)))
      (is (= 1000 (f 3 250))))))

(deftest repair-empty-result-retry-recovers-test
  (testing "empty LLM result → repair retries with exponential backoff; recovered answer dispatched same iteration"
    (let [sleeps     (atom [])
          dspy-calls (atom 0)
          st (fresh-st-memory :tool-calls [] :code-blocks "" :answer ""
                              :last-reasoning nil :iteration-count 1)
          orig-get-config ai.brainyard.agent.core.config/get-config]
      (with-redefs [ai.brainyard.agent.core.config/get-config
                    (fn
                      ([k] (orig-get-config k))
                      ([agent k]
                       (case k
                         :max-retries-on-llm-empty-result 2
                         :empty-result-retry-base-ms 1000
                         (orig-get-config agent k))))
                    rca/retry-sleep! (fn [ms] (swap! sleeps conj ms))
                    bt/dspy (fn [ctx]
                              (let [n (swap! dspy-calls inc)]
                                (when (>= n 2)
                                  (swap! (:st-memory ctx) assoc
                                         :last-reasoning "recovered"
                                         :answer "RECOVERED ANSWER"))
                                :success))]
        (rca/coact-repair-action {:st-memory st :agent :fake-agent}))
      (is (= 2 @dspy-calls) "retried until a non-empty result (2 dspy calls)")
      (is (= [1000 2000] @sleeps) "exponential backoff before each attempt")
      (is (= "RECOVERED ANSWER" (:answer @st)) "recovered answer dispatched")
      (is (= :answer (:last-channel @st)) "answer channel stamped")
      (is (true? (boolean (:terminated @st))) "answer dispatch terminates the turn"))))

(deftest repair-empty-result-retry-exhausted-falls-through-test
  (testing "empty result that never recovers → backoff exhausts, falls through to no-action nudge"
    (let [sleeps     (atom [])
          dspy-calls (atom 0)
          st (fresh-st-memory :tool-calls [] :code-blocks "" :answer ""
                              :last-reasoning nil :iteration-count 1)
          orig-get-config ai.brainyard.agent.core.config/get-config]
      (with-redefs [ai.brainyard.agent.core.config/get-config
                    (fn
                      ([k] (orig-get-config k))
                      ([agent k]
                       (case k
                         :max-retries-on-llm-empty-result 3
                         :empty-result-retry-base-ms 1000
                         (orig-get-config agent k))))
                    rca/retry-sleep! (fn [ms] (swap! sleeps conj ms))
                    bt/dspy (fn [_ctx] (swap! dspy-calls inc) :success)]
        (rca/coact-repair-action {:st-memory st :agent :fake-agent}))
      (is (= 3 @dspy-calls) "retried up to max-retries")
      (is (= [1000 2000 4000] @sleeps) "exponential backoff across 3 attempts")
      (is (= :none (:last-channel @st)) "still empty → no-action nudge")
      (is (str/includes? (:error (first (:last-code-results @st)))
                         "no action this iteration")))))

(deftest repair-no-retry-when-agent-absent-test
  (testing "without an agent (unit-test path), repair does NOT retry — nudges directly"
    (let [dspy-calls (atom 0)]
      (with-redefs [bt/dspy (fn [_] (swap! dspy-calls inc) :success)]
        (let [st (fresh-st-memory :tool-calls [] :code-blocks "" :answer ""
                                  :last-reasoning nil)]
          (rca/coact-repair-action {:st-memory st})
          (is (= 0 @dspy-calls) "no agent → no LLM retry")
          (is (= :none (:last-channel @st))))))))

(deftest repair-deliberate-no-action-skips-retry-test
  (testing "reasoning present but no channel = deliberate no-action → no retry, just nudge"
    (let [dspy-calls (atom 0)]
      (with-redefs [bt/dspy (fn [_] (swap! dspy-calls inc) :success)
                    rca/retry-sleep! (fn [_] nil)]
        (let [st (fresh-st-memory :tool-calls [] :code-blocks "" :answer ""
                                  :last-reasoning "I considered options but will wait")]
          (rca/coact-repair-action {:st-memory st :agent :fake-agent})
          (is (= 0 @dspy-calls) "reasoning present → not an empty result → no retry")
          (is (= :none (:last-channel @st))))))))

;; --- Recovery progress surface (:agent.recovery/retrying) ---

(defn- capture-recovery-events
  "Run `body-fn` with a temporary capturing hook on :agent.recovery/retrying;
   return the vector of captured event-maps."
  [body-fn]
  (let [events (atom [])]
    (hooks/register-hook! :agent.recovery/retrying ::test-capture
                          (fn [ev] (swap! events conj ev))
                          :source ::recovery-test)
    (try (body-fn) (finally (hooks/unregister-source! ::recovery-test)))
    @events))

(deftest repair-empty-result-fires-recovery-events-test
  (testing "each empty-result retry attempt fires :agent.recovery/retrying with progress"
    (let [orig-get-config ai.brainyard.agent.core.config/get-config
          events
          (capture-recovery-events
           (fn []
             (with-redefs [ai.brainyard.agent.core.config/get-config
                           (fn ([k] (orig-get-config k))
                             ([agent k] (case k
                                          :max-retries-on-llm-empty-result 3
                                          :empty-result-retry-base-ms 1000
                                          (orig-get-config agent k))))
                           rca/retry-sleep! (fn [_] nil)
                           bt/dspy (fn [_ctx] :success)]  ;; never recovers
               (let [st (fresh-st-memory :tool-calls [] :code-blocks "" :answer ""
                                         :last-reasoning nil :iteration-count 1)]
                 (rca/coact-repair-action {:st-memory st :agent :fake-agent})))))
          ;; The trailing no-channel nudge (retries exhausted → fall-through) is
          ;; expected; assert on the empty-result retry events specifically.
          retry-events (filter #(= :empty-result (:kind %)) events)]
      (is (= 3 (count retry-events)) "one event per retry attempt (max-retries 3)")
      (is (= [1 2 3] (mapv :attempt retry-events)) "attempt counter increments")
      (is (every? #(= 3 (:max %)) retry-events)))))

(deftest repair-no-action-fires-recovery-event-test
  (testing "a deliberate no-action nudge fires one :no-action recovery event"
    (let [events
          (capture-recovery-events
           (fn []
             (let [st (fresh-st-memory
                       :tool-calls [] :code-blocks "" :answer ""
                       :last-reasoning "thinking it over"  ;; not empty → no retry
                       :iterations [{:iteration 1 :channel "none"
                                     :tool-results [] :code-results []}])]
               (rca/coact-repair-action {:st-memory st :agent :fake-agent}))))]
      (is (= 1 (count events)))
      (is (= :no-action (:kind (first events))))
      (is (= 2 (:attempt (first events))) "2nd consecutive none (1 prior + this)")
      (is (= 3 (:max (first events))) "bounded by max-retries-on-llm-no-action (default 3)"))))

(deftest repair-loop-guard-fires-no-recovery-event-test
  (testing "the terminal loop-guard escalation does NOT fire a recovery (retry) event"
    (let [events
          (capture-recovery-events
           (fn []
             ;; :last-reasoning present → a deliberate no-action (not an empty
             ;; result), so it skips the retry loop and goes straight to the
             ;; nudge/guard branch; streak ≥ 3 (default) → terminal escalation.
             (let [st (fresh-st-memory
                       :tool-calls [] :code-blocks "" :answer ""
                       :last-reasoning "I have nothing actionable to add"
                       :iterations [{:iteration 1 :channel "none"
                                     :tool-results [] :code-results []}
                                    {:iteration 2 :channel "none"
                                     :tool-results [] :code-results []}
                                    {:iteration 3 :channel "none"
                                     :tool-results [] :code-results []}])]
               (rca/coact-repair-action {:st-memory st :agent :fake-agent}))))]
      (is (empty? events) "escalation terminates the turn — not a 'retrying' state"))))

;; --- Malformed-output recovery (unified into coact-repair-action; routed by
;;     :dspy-error, which bt/dspy sets when the ThinkActCode call throws) ---

(deftest repair-malformed-non-fatal-test
  (testing "non-fatal LLM error increments counter and pushes a FORMAT ERROR note"
    (let [st (fresh-st-memory :dspy-error "malformed JSON" :dspy-error-class :malformed
                              :consecutive-llm-failures 0)]
      (rca/coact-repair-action {:st-memory st})
      (is (= 1 (:consecutive-llm-failures @st)))
      (is (false? (boolean (:terminated @st))))
      (is (= :none (:last-channel @st)))
      (is (nil? (:dspy-error @st)) "malformed handler clears :dspy-error")
      (is (str/includes? (:error (first (:last-code-results @st))) "FORMAT ERROR")))))

(deftest repair-malformed-plain-text-test
  (testing "a pure-prose reply (no JSON envelope) is kept AS the thought, not dumped as an error"
    (let [prose "I have the full modal source. I'll rewrite MemoryModal.tsx cleanly, drop the This turn section."
          st (fresh-st-memory :iteration-count 9
                              :dspy-error (str "LLM response was plain text with no JSON object."
                                               "\nLLM raw text: " prose)
                              :dspy-error-class :malformed
                              :dspy-raw-text prose
                              :dspy-no-json-envelope? true
                              :consecutive-llm-failures 0)]
      (rca/coact-repair-action {:st-memory st})
      (is (= 1 (:consecutive-llm-failures @st)) "still counts as a retry")
      (is (false? (boolean (:terminated @st))))
      (is (= :none (:last-channel @st)))
      (is (= prose (:last-reasoning @st)) "prose becomes the iteration thought")
      (is (empty? (:last-code-results @st)) "no fake FORMAT ERROR eval-result")
      (is (nil? (:dspy-no-json-envelope? @st)) "the flag is cleared so it can't leak forward")
      ;; The schema correction rides :notices (a model-visible advisory), queued
      ;; here and folded into the record by coact-accumulate-iteration-action.
      (rca/coact-accumulate-iteration-action {:st-memory st})
      (let [rec (last (:iterations @st))]
        (is (= prose (:thought rec)))
        (is (= "none" (:channel rec)))
        (is (empty? (:code-results rec)))
        (is (str/includes? (:notices rec) "plain prose")
            "the descriptive guide lives in :notices, not the code-result error")
        (is (nil? (:pending-format-guidance @st)) "guidance drained once")))))

(deftest repair-malformed-fatal-test
  (testing "fatal LLM error (auth) terminates with apology answer regardless of count"
    ;; dspy-action stamps :dspy-error-class :fatal for auth/4xx/quota errors.
    (let [st (fresh-st-memory :dspy-error "HTTP 401 unauthorized" :dspy-error-class :fatal
                              :dspy-error-reason "request rejected (HTTP 401)"
                              :consecutive-llm-failures 0)]
      (rca/coact-repair-action {:st-memory st})
      (is (true? (boolean (:terminated @st))))
      (is (str/includes? (:answer @st) "Agent stopped"))
      (is (= :llm-error (:terminated-by @st))))))

(deftest repair-transient-aborts-when-retries-disabled-test
  (testing "a transient provider error with retries disabled aborts with the cause"
    ;; agent nil ⇒ retries disabled in repair-transient-failure! ⇒ abort path.
    (let [st (fresh-st-memory :dspy-error "HTTP 503" :dspy-error-class :transient
                              :dspy-error-reason "provider error (HTTP 503)")]
      (rca/coact-repair-action {:st-memory st})
      (is (true? (boolean (:terminated @st))))
      (is (= :llm-error (:terminated-by @st)))
      (is (str/includes? (:answer @st) "provider error (HTTP 503)")
          "aborts with the provider cause, not a malformed-output message"))))

;; ============================================================================
;; 6. Prompt token breakdown (system + user contexts)
;; ============================================================================

(deftest prompt-token-breakdown-shape-test
  (testing "coact-system-context :return-breakdown? returns content + sections"
    (let [sys-fn (deref #'rca/coact-system-context)
          {:keys [content sections]} (sys-fn {:instruction   "do the thing"
                                              :agent-context "react agent"
                                              :tool-context  "tools to use"}
                                             :return-breakdown? true)
          ;; Stable section keys live in coact-system-context's section-order
          ;; (coact_agent.clj §6 build). SCI string restrictions were folded
          ;; into :code-blocks-format; the sandbox accessor renamed from
          ;; :sci-sandbox-context-accessor to :sandbox-context-accessor; and
          ;; tool-context overlay now merges into :tools.
          stable-sections #{:role :execution-model :channel-routing
                            :tool-call-format :code-blocks-format
                            :sandbox-context-accessor
                            :tools
                            :critical-rules :large-results-playbook :footer}
          ;; :instruction and :agent-context are still per-call optional —
          ;; they only appear when the caller supplies non-blank text. Tool
          ;; overlay no longer surfaces as a separate :tool-context section;
          ;; it ends up inside :tools.
          optional-sections #{:instruction :agent-context}]
      (is (string? content))
      (is (pos? (count content)))
      (is (every? #(contains? sections %) stable-sections)
          "All stable sections must be present.")
      (is (every? #(contains? sections %) optional-sections)
          "Optional sections present when text supplied.")
      (is (str/includes? (:instruction sections) "do the thing"))
      (is (str/includes? (:agent-context sections) "react agent"))
      (is (str/includes? (:tools sections) "tools to use")
          ":tool-context overlay must surface inside the unified :tools section")))

  (testing "coact-system-context omits optional sections when blank"
    (let [sys-fn (deref #'rca/coact-system-context)
          {:keys [sections]} (sys-fn {:instruction "" :agent-context nil :tool-context "  "}
                                     :return-breakdown? true)]
      (is (not (contains? sections :instruction)))
      (is (not (contains? sections :agent-context)))
      (is (not (contains? sections :tools))
          ":tools is skipped when there's no sandbox/bindings/overlay content")))

  (testing "coact-user-context :return-breakdown? returns content + sections"
    ;; P4.6: :project-instructions / :user-instructions live in
    ;; coact-system-context now (above the cross-turn cache breakpoint).
    ;; coact-user-context only carries per-turn-volatile sections.
    (let [user-fn (deref #'rca/coact-user-context)
          {:keys [content sections]}
          (user-fn {:conversation [{:role "user" :content "hi"}]
                    :live-artifacts ["skill.md"]}
                   :return-breakdown? true)]
      (is (string? content))
      (is (contains? sections :conversation-history))
      (is (contains? sections :live-artifacts))
      (is (not (contains? sections :project-instructions)))
      (is (not (contains? sections :user-instructions))))

    (testing "coact-system-context now carries BRAINYARD.md sections (P4.6)"
      (let [sys-fn (deref #'rca/coact-system-context)
            {:keys [sections order]}
            (sys-fn {:brainyard-instructions {:project-instructions "PROJ"
                                              :user-instructions    "USER"}
                     :instruction "I"}
                    :return-breakdown? true)]
        (is (contains? sections :project-instructions))
        (is (contains? sections :user-instructions))
      ;; Order places BRAINYARD.md (+ project-memory) between :agent-context and :footer
        (is (= (vec (drop-while #(not= % :project-instructions) order))
               [:project-instructions :project-memory :skill-substrate :mcp-substrate
                :user-instructions :todo-substrate :exec-substrate :subagent-substrate :footer])))))

  (testing "coact-user-context returns blank content + empty sections when nothing supplied"
    (let [user-fn (deref #'rca/coact-user-context)
          {:keys [content sections]} (user-fn {} :return-breakdown? true)]
      (is (= "" content))
      (is (empty? sections))))

  (testing "merged sections feed clj-llm/build-token-breakdown into a flat per-section map"
    (let [sys-fn  (deref #'rca/coact-system-context)
          user-fn (deref #'rca/coact-user-context)
          ;; P4.6: brainyard-instructions flow through coact-system-context.
          sys     (sys-fn {:instruction "I" :agent-context "A" :tool-context "T"
                           :brainyard-instructions {:project-instructions "P"}}
                          :return-breakdown? true)
          user    (user-fn {:conversation [{:role "user" :content "hi"}]}
                           :return-breakdown? true)
          breakdown (clj-llm/build-token-breakdown
                     (merge (:sections sys) (:sections user)))]
      (is (map? breakdown))
      (is (every? (fn [[_ v]]
                    (and (contains? v :text-length)
                         (contains? v :estimated-tokens)
                         (pos? (:estimated-tokens v))))
                  breakdown))
      (is (contains? breakdown :role))
      (is (contains? breakdown :critical-rules))
      (is (contains? breakdown :instruction))
      (is (contains? breakdown :project-instructions)))))

(deftest live-artifacts-helpers-test
  (testing "format-live-artifacts badges origin/pin and honors :max-chars"
    (let [fmt (deref #'rca/format-live-artifacts)
          txt (fmt [{:name "Ref" :content "abcdefghij" :origin :system
                     :pinned? true :max-chars 4}
                    {:name "Note" :content "hello" :origin :llm}])]
      (is (str/includes? txt "### Ref (system 📌)"))
      (is (str/includes? txt "abcd…"))
      (is (str/includes? txt "### Note\nhello"))))

  (testing "format-live-artifacts previews :file artifacts at 400 chars + read-file pointer"
    (let [fmt (deref #'rca/format-live-artifacts)
          long-body (apply str (repeat 500 "x"))
          txt (fmt [{:name "CLAUDE.md" :source :file :path "/abs/CLAUDE.md"
                     :content long-body :origin :system :pinned? true}
                    {:name "Short" :source :file :path "/abs/short.md"
                     :content "tiny"}])]
      ;; long file → cut at 400 with a read-file pointer carrying the path
      (is (str/includes? txt "read-file"))
      (is (str/includes? txt "/abs/CLAUDE.md"))
      (is (str/includes? txt "truncated"))
      (is (str/includes? txt (subs long-body 0 400)))
      (is (not (str/includes? txt (apply str (repeat 401 "x")))))
      ;; short file → full content, no pointer
      (is (str/includes? txt "### Short\ntiny"))
      (is (not (str/includes? txt "/abs/short.md")))))

  (testing "resolve-artifacts loads :file fresh, drops missing, passes inline, stamps max-chars"
    (let [resolve* (deref #'rca/resolve-artifacts)
          f (java.io.File/createTempFile "art" ".md")
          _ (spit f "file body")
          out (resolve* [{:id "f" :name "F" :source :file :path (.getPath f)}
                         {:id "miss" :name "M" :source :file :path "/no/such.md"}
                         {:id "i" :name "I" :source :inline :content "inline body"}
                         {:name "legacy" :content "legacy body"}]
                        4000)]
      (is (= 3 (count out)))
      (is (= "file body" (:content (first out))))
      (is (= 4000 (:max-chars (first out))))
      (is (= #{"f" "i" nil} (set (map :id out))))))

  (testing "merge-artifact-descriptors keeps system first, dedupes by id (first wins)"
    (let [m (rca/merge-artifact-descriptors
             [{:id "x" :name "sys-x" :origin :system}]
             [{:id "x" :name "dyn-x" :origin :llm}
              {:id "y" :name "dyn-y" :origin :llm}])]
      (is (= ["x" "y"] (mapv :id m)))
      (is (= "sys-x" (:name (first m))))))

  (testing "drop-live-artifacts evicts oldest unpinned :llm, protects system/pinned"
    (let [st (atom {:live-artifacts
                    [{:id "ref:s" :origin :system :pinned? true :content "s"}
                     {:id "note:a" :origin :llm :content "a"}
                     {:id "note:b" :origin :llm :pinned? true :content "b"}
                     {:id "note:c" :origin :llm :content "c"}]})
          drop-fn (:drop-live-artifacts ((deref #'rca/coact-strategies) st))
          secs0 {:live-artifacts "## Live Artifacts\nx"}
          secs1 (drop-fn secs0)]
      (is (= ["ref:s" "note:b" "note:c"] (mapv :id (:live-artifacts @st))))
      (let [secs2 (drop-fn secs1)]
        (is (= ["ref:s" "note:b"] (mapv :id (:live-artifacts @st))))
        ;; only pinned/system remain -> strategy returns sections unchanged
        (is (= secs2 (drop-fn secs2)))))))

(deftest repair-malformed-consecutive-test
  (testing "reaching max-retries-on-llm-malformed-output (default 3) terminates the turn"
    (let [st-below (fresh-st-memory :dspy-error "parse error"
                                    :consecutive-llm-failures 2)]
      (rca/coact-repair-action {:st-memory st-below})
      (is (false? (boolean (:terminated @st-below)))
          "2 prior failures (< default 3) still re-prompts, does not abort")
      (is (= 3 (:consecutive-llm-failures @st-below))))
    (let [st-at (fresh-st-memory :dspy-error "parse error"
                                 :consecutive-llm-failures 3)]
      (rca/coact-repair-action {:st-memory st-at})
      (is (true? (boolean (:terminated @st-at)))
          "3rd prior failure (≥ default 3) aborts")
      (is (= :llm-error (:terminated-by @st-at))))))

(deftest repair-idempotent-within-iteration-test
  (testing "after a malformed re-prompt (llm-guard slot), the router-slot re-entry
            is a no-op — not re-classified as a no-action"
    (let [st (fresh-st-memory :dspy-error "malformed JSON"
                              :consecutive-llm-failures 0
                              :iteration-count 7)]
      ;; First call: llm-guard slot handles the malformed output.
      (rca/coact-repair-action {:st-memory st})
      (is (= 1 (:consecutive-llm-failures @st)))
      (is (= 7 (:last-repair-iter @st)) "stamps the handled iteration")
      (is (str/includes? (:error (first (:last-code-results @st))) "FORMAT ERROR"))
      ;; Second call in the SAME iteration (router Path D re-entry): no-op.
      (rca/coact-repair-action {:st-memory st})
      (is (= 1 (:consecutive-llm-failures @st))
          "guard prevents a second increment / no-action re-classification")
      (is (str/includes? (:error (first (:last-code-results @st))) "FORMAT ERROR")
          "eval-result unchanged — not overwritten by a no-action nudge"))))

;; ============================================================================
;; M5 — Tiered :tools compaction
;; ============================================================================

(def ^:private sample-agent-tools
  [{:name        "echo-tool"
    :description "Echo a string back."
    :tool-fn-type :tool
    :parameters  {:type "object"
                  :properties {:text {:type "string"
                                      :description "input text"}}
                  :required ["text"]}}
   {:name        "add-tool"
    :description "Add two integers."
    :tool-fn-type :command
    :parameters  {:type "object"
                  :properties {:a {:type "integer"}
                               :b {:type "integer"}}
                  :required ["a" "b"]}}])

(def ^:private sample-bindings
  {'echo-tool (with-meta (fn [& _] nil)
                {:doc "Echo a string back." :arglists '([& _]) :category :tools})
   'add-tool  (with-meta (fn [& _] nil)
                {:doc "Add two integers." :arglists '([& _]) :category :tools})})

(def ^:private sample-overlay
  "Use echo-tool for trivial roundtrips; add-tool for arithmetic.")

(defn- build-sample-tools
  [disabled-tiers]
  (@#'rca/build-tools-section
   {:sandbox-bindings     sample-bindings
    :agent-tools          sample-agent-tools
    :tool-context-overlay sample-overlay
    :include-directory?   false
    :disabled-tiers       (or disabled-tiers #{})}))

(deftest tools-section-baseline-test
  (testing "build-tools-section with no disabled tiers renders every block"
    (let [out (build-sample-tools nil)]
      (is (str/includes? out "## Tools"))
      (is (str/includes? out "### Hot-path primitives"))
      (is (str/includes? out "### Sandbox Categories"))
      (is (str/includes? out "### Agent Tools"))
      (is (str/includes? out "### Discovery"))
      (is (str/includes? out "### Usage Guides"))
      (is (str/includes? out "### Agent-specific guidance"))
      ;; Verbose agent-tools block — exposes Inputs section.
      (is (str/includes? out "Inputs:")))))

(deftest tools-section-tier-disables-test
  ;; Note: bare strings like "### Usage Guides" appear in static prose
  ;; (hot-path table, critical rules) even when the dedicated section is
  ;; suppressed. Tests use the full section heading or unique content to
  ;; distinguish the *inserted* block from textual references.
  (testing ":usage-guides drops the dedicated Usage Guides table"
    (let [out (build-sample-tools #{:usage-guides})]
      (is (not (str/includes? out "### Usage Guides (on-demand")))
      ;; The actual table content (a unique row from coact-usage-guide-table)
      ;; should also be gone.
      (is (not (str/includes? out "| `:llm-query`")))
      ;; Other tiers untouched
      (is (str/includes? out "### Agent-specific guidance\n"))
      (is (str/includes? out "### Sandbox Categories (counts only"))))

  (testing ":tool-context-overlay drops the per-agent guidance"
    (let [out (build-sample-tools #{:tool-context-overlay})]
      ;; The overlay text itself is unique to the test fixture.
      (is (not (str/includes? out sample-overlay)))
      (is (str/includes? out "### Usage Guides (on-demand"))))

  (testing ":function-index drops the Sandbox Categories block"
    (let [out (build-sample-tools #{:function-index})]
      (is (not (str/includes? out "### Sandbox Categories (counts only")))
      (is (str/includes? out "### Usage Guides (on-demand"))
      (is (str/includes? out "### Agent Tools (bound for THIS turn"))))

  (testing ":agent-tools-details replaces verbose block with one-liner"
    (let [out (build-sample-tools #{:agent-tools-details})]
      (is (str/includes? out "### Agent Tools"))
      ;; Compact one-liner format: bare `- `echo-tool`` (no type tag)
      (is (str/includes? out "- `echo-tool`"))
      (is (str/includes? out "- `add-tool`"))
      ;; Verbose Inputs block absent
      (is (not (str/includes? out "Inputs:"))))))

(deftest tools-tier-strategy-ordering-test
  (testing ":tools-tier strategy walks tiers in cheapest-first order"
    (let [st-memory
          (atom {:tools-section-config
                 {:sandbox-bindings     sample-bindings
                  :agent-tools          sample-agent-tools
                  :tool-context-overlay sample-overlay
                  :include-directory?   false}
                 :tools-disabled-tiers #{}})
          strategies (@#'rca/coact-strategies st-memory)
          tools-tier (:tools-tier strategies)
          initial-text (build-sample-tools nil)
          secs0 {:tools initial-text}
          ;; Cheapest first per the design: usage-guides → tool-context-overlay
          ;; → function-index → agent-tools-details.
          secs1 (tools-tier secs0)
          tier1 (:tools-disabled-tiers @st-memory)
          secs2 (tools-tier secs1)
          tier2 (:tools-disabled-tiers @st-memory)
          secs3 (tools-tier secs2)
          tier3 (:tools-disabled-tiers @st-memory)
          secs4 (tools-tier secs3)
          tier4 (:tools-disabled-tiers @st-memory)
          ;; 5th invocation: all tiers exhausted → return unchanged.
          secs5 (tools-tier secs4)]
      (is (= #{:usage-guides} tier1))
      (is (= #{:usage-guides :tool-context-overlay} tier2))
      (is (= #{:usage-guides :tool-context-overlay :function-index} tier3))
      (is (= #{:usage-guides :tool-context-overlay
               :function-index :agent-tools-details} tier4))
      ;; Each step monotonically shrinks the tools text.
      (is (< (count (:tools secs1)) (count (:tools secs0))))
      (is (< (count (:tools secs2)) (count (:tools secs1))))
      (is (< (count (:tools secs3)) (count (:tools secs2))))
      (is (< (count (:tools secs4)) (count (:tools secs3))))
      ;; Once exhausted, strategy returns the input unchanged.
      (is (= secs4 secs5)))))

(deftest tools-tier-no-progress-without-config-test
  (testing "without :tools-section-config in st-memory, the strategy is a no-op"
    (let [st-memory   (atom {})
          strategies  (@#'rca/coact-strategies st-memory)
          tools-tier  (:tools-tier strategies)
          secs        {:tools "some tools"}
          result      (tools-tier secs)]
      (is (= secs result)))))

;; ============================================================================
;; M4 — :collapse-iterations strategy + rebudget action
;; ============================================================================

(defn- make-iteration
  [n channel]
  {:iteration n
   :channel channel
   :thought (str "thought for iter " n)
   :tool-results (if (= channel "tool")
                   [{:tool-name "echo-tool"
                     :tool-args {:text "hi"}
                     :tool-result (str "echo: hi-" n)}]
                   [])
   :code-results (if (= channel "code")
                   [{:lang "clojure"
                     :code (str "(+ 1 " n ")")
                     :result (str (+ 1 n))
                     :output ""
                     :error ""
                     :parallel? false}]
                   [])})

(deftest format-iterations-block-shape-test
  (testing "format-iterations-block emits one line per iteration"
    (let [iters [(make-iteration 1 "tool")
                 (make-iteration 2 "code")
                 (make-iteration 3 "tool")]
          out (@#'rca/format-iterations-block iters)]
      (is (str/includes? out "(1) [tool]"))
      (is (str/includes? out "(2) [code]"))
      (is (str/includes? out "(3) [tool]"))
      (is (str/includes? out "thought for iter 1"))
      (is (str/includes? out "echo-tool→"))
      (is (str/includes? out "clojure:")))))

(deftest format-iterations-block-empty-test
  (testing "empty iterations vector returns nil (caller treats as missing)"
    (is (nil? (@#'rca/format-iterations-block [])))
    (is (nil? (@#'rca/format-iterations-block nil)))))

(deftest collapse-iterations-strategy-test
  (testing "with <= 3 iterations, strategy returns secs unchanged"
    (let [iters [(make-iteration 1 "tool") (make-iteration 2 "code")]
          st-memory (atom {:iterations iters})
          strategies (@#'rca/coact-strategies st-memory)
          collapse (:collapse-iterations strategies)
          secs {:iterations "current text"}
          result (collapse secs)]
      (is (= secs result))
      (is (= iters (:iterations @st-memory)))))

  (testing "with > 3 iterations, strategy keeps last 3 verbatim + 1 summary"
    (let [iters (mapv #(make-iteration % "tool") (range 1 16))
          st-memory (atom {:iterations iters})
          strategies (@#'rca/coact-strategies st-memory)
          collapse (:collapse-iterations strategies)
          secs {:iterations "x"}
          result (collapse secs)
          new-iters (:iterations @st-memory)]
      (is (= 4 (count new-iters)))
      ;; First entry is the summary record.
      (is (= "summary" (:channel (first new-iters))))
      (is (= 0 (:iteration (first new-iters))))
      (is (str/includes? (:thought (first new-iters)) "thought for iter 1"))
      (is (str/includes? (:thought (first new-iters)) "thought for iter 12"))
      ;; Last 3 verbatim
      (is (= [13 14 15] (mapv :iteration (rest new-iters))))
      ;; secs[:iterations] reflects the new vector.
      (is (string? (:iterations result)))
      (is (str/includes? (:iterations result) "[summary]")))))

(deftest rebudget-action-skips-iteration-zero-test
  (testing "rebudget action no-ops at iteration 0 (init has already enforced)"
    (let [hook-fires (atom 0)
          st-memory (atom {:iteration-count 0
                           :iterations []
                           :cached-sections {:role "r"}
                           :sys-order [:role]
                           :usr-order []
                           :merged-order [:role]
                           :budget-tokens 100000})
          old-system-context (:system-context @st-memory)
          result (with-redefs [ai.brainyard.agent.core.hooks/fire!
                               (fn [& _] (swap! hook-fires inc))]
                   (rca/coact-rebudget-action {:st-memory st-memory :agent nil}))]
      (is (= bt/success result))
      ;; No state mutation, no hook fired.
      (is (= old-system-context (:system-context @st-memory)))
      (is (zero? @hook-fires)))))

(deftest rebudget-action-respects-cadence-test
  (testing ":rebudget-every-n-iter = 3 skips iters 1, 2 and runs on iter 3"
    (let [fired (atom [])
          fake-agent {:!state (atom {:st-memory-init
                                     (atom {:config {:enable-context-budget true
                                                     :rebudget-every-n-iter 3}})})}
          base {:iterations []
                :cached-sections {:role "r"}
                :sys-order [:role]
                :usr-order []
                :merged-order [:role]
                :budget-tokens 100000}]
      (with-redefs [ai.brainyard.agent.core.hooks/fire!
                    (fn [event-key _] (swap! fired conj event-key))]
        (doseq [n [1 2 3 4 5 6]]
          (let [st-memory (atom (assoc base :iteration-count n))
                _ (rca/coact-rebudget-action {:st-memory st-memory :agent fake-agent})]
            nil)))
      ;; iters 3 and 6 should fire; 1,2,4,5 should not.
      (is (= 2 (count @fired)))
      (is (every? #(= % :agent.context/budgeted) @fired)))))

;; ============================================================================
;; M9 — Sub-agent context handoff
;; ============================================================================

;; parent-handoff-st-memory only needs (proto/agent-id ...) and access
;; to (:!state agent) as an atom whose value has :st-memory-init (also
;; an atom). A minimal record implementing just agent-id suffices.
(defrecord StubParent [agent-id-kw !state]
  ai.brainyard.agent.core.protocol/IAgent
  (agent-id [_] agent-id-kw)
  (agent-name [_] nil)
  (agent-description [_] nil)
  (user-id [_] "u")
  (session-id [_] "s")
  (defagent-type [_] :coact-agent)
  (process [_ _ _] nil)
  (get-tools [_] nil)
  (get-state [_] nil))

(defn- stub-parent
  ([parent-prev] (stub-parent parent-prev nil))
  ([parent-prev cfg]
   (->StubParent
    :plan-agent/parent-1
    (atom {:st-memory-init (atom {:previous-turns parent-prev
                                  :config (or cfg {})})}))))

(deftest parent-handoff-extracts-trail-and-id
  (testing "Given a parent with previous-turns, handoff has trail + agent-id"
    (let [parent (stub-parent
                  [{:question "q1" :answer "a1" :depth :full :iterations []}
                   {:question "q2" :answer "a2" :depth :full :iterations []}
                   {:question "q3" :answer "a3" :depth :full :iterations []}
                   {:question "q4" :answer "a4" :depth :full :iterations []}])
          handoff (@#'rca/parent-handoff-st-memory parent)]
      (is (= :plan-agent/parent-1 (:parent-agent-id handoff)))
      (is (= 3 (count (:parent-trail handoff))))
      ;; Last K=3 turns kept
      (is (= ["q2" "q3" "q4"] (map :question (:parent-trail handoff)))))))

(deftest parent-handoff-honours-runtime-config-k
  (testing "Runtime-config :parent-trail-k overrides the default K"
    (let [parent (stub-parent
                  [{:question "q1"} {:question "q2"}
                   {:question "q3"} {:question "q4"}]
                  {:parent-trail-k 1})
          handoff (@#'rca/parent-handoff-st-memory parent)]
      (is (= 1 (count (:parent-trail handoff))))
      (is (= "q4" (:question (first (:parent-trail handoff))))))))

(deftest parent-handoff-empty-parent
  (testing "nil parent → nil handoff"
    (is (nil? (@#'rca/parent-handoff-st-memory nil))))

  (testing "parent with no previous-turns → handoff has id only"
    (let [parent (stub-parent [])
          handoff (@#'rca/parent-handoff-st-memory parent)]
      (is (= :plan-agent/parent-1 (:parent-agent-id handoff)))
      (is (not (contains? handoff :parent-trail))))))

(deftest parent-trail-renders-in-user-context
  (testing "coact-user-context emits a :parent-trail section when present"
    (let [user-fn (deref #'rca/coact-user-context)
          parent-trail [{:question "q1" :answer "a1" :depth :full :iterations []}
                        {:question "q2" :answer "a2" :depth :full :iterations []}]
          {:keys [sections]} (user-fn {:parent-trail parent-trail}
                                      :return-breakdown? true)]
      (is (contains? sections :parent-trail))
      (is (str/includes? (:parent-trail sections) "## Parent Trail"))
      (is (str/includes? (:parent-trail sections) "q1"))
      (is (str/includes? (:parent-trail sections) "q2")))))

(deftest bump-parent-trail-strategy-drops-oldest
  (testing ":bump-parent-trail drops oldest entry per call until empty"
    (let [trail [{:question "q1" :answer "a1" :depth :full}
                 {:question "q2" :answer "a2" :depth :full}
                 {:question "q3" :answer "a3" :depth :full}]
          st-memory (atom {:parent-trail trail})
          strategies (@#'rca/coact-strategies st-memory)
          bump (:bump-parent-trail strategies)
          secs {:parent-trail "x"}
          r1 (bump secs)
          _ (is (= 2 (count (:parent-trail @st-memory))))
          _ (is (str/includes? (:parent-trail r1) "q2"))
          r2 (bump r1)
          _ (is (= 1 (count (:parent-trail @st-memory))))
          r3 (bump r2)
          ;; Last call empties the vector and drops the section.
          _ (is (= 0 (count (:parent-trail @st-memory))))]
      (is (not (contains? r3 :parent-trail))))))

;; ============================================================================
;; Async-completion formatters
;; ============================================================================

(deftest format-iterations-block-async-completion-test
  (let [format-fn @#'rca/format-iterations-block
        normal-rec {:iteration 1
                    :channel "code"
                    :thought "executing slow research call"
                    :code-results [{:lang "clojure"
                                    :result "{:answer 42}"}]}
        async-rec  {:iteration 4
                    :channel "code"
                    :thought "(async eval from iter 2 completed: task-7)"
                    :async-completion? true
                    :code-results [{:lang "clojure"
                                    :result "{:answer 42}"
                                    :status :resolved
                                    :task-id "task-7"
                                    :from-iteration 2}]}]

    (testing "normal record uses the legacy [channel] shape"
      (let [out (format-fn [normal-rec])]
        (is (str/includes? out "(1) [code]")
            "channel tag uses bracket form")
        (is (str/includes? out "{:answer 42}"))))

    (testing "async-completion record uses [↺ async from iter M] shape"
      (let [out (format-fn [async-rec])]
        (is (str/includes? out "(4) [↺ async from iter 2]")
            "row has the async marker + originating iter")
        (is (str/includes? out "task-id=task-7")
            "task-id surfaces for cross-iter correlation")
        (is (str/includes? out "status=resolved"))
        (is (str/includes? out "{:answer 42}")
            "resolved value still appears after =>")
        (is (not (str/includes? out "[code]"))
            "normal channel marker is suppressed for async records")))

    (testing "missing :from-iteration falls back to '?'"
      (let [out (format-fn [(assoc async-rec
                                   :code-results
                                   [{:lang "clojure"
                                     :result "{}"
                                     :status :resolved
                                     :task-id "task-9"}])])]
        (is (str/includes? out "from iter ?")
            "no provenance metadata → '?'")))

    (testing "multiple sources are combined: 'from iters 2,5'"
      (let [out (format-fn
                 [{:iteration 7
                   :channel "code"
                   :async-completion? true
                   :code-results [{:lang "clojure" :result "a"
                                   :status :resolved :task-id "task-1"
                                   :from-iteration 2}
                                  {:lang "clojure" :result "b"
                                   :status :resolved :task-id "task-2"
                                   :from-iteration 5}]}])]
        (is (str/includes? out "from iters 2,5"))
        (is (str/includes? out "task-id=task-1"))
        (is (str/includes? out "task-id=task-2"))))

    (testing "mixed normal + async iterations render side-by-side"
      (let [out (format-fn [normal-rec async-rec])
            lines (str/split-lines out)]
        (is (= 2 (count lines)))
        (is (str/starts-with? (first lines) "(1) [code]"))
        (is (str/starts-with? (second lines) "(4) [↺ async"))))))

(deftest compact-iteration-record-async-completion-test
  (let [compact @#'rca/compact-iteration-record
        async-rec {:iteration 4
                   :channel "code"
                   :async-completion? true
                   :code-results [{:lang     "clojure"
                                   :code     "(research-agent {...})"
                                   :result   "{:answer 42}"
                                   :output   "ok"
                                   :error    ""
                                   :status   :resolved
                                   :task-id  "task-7"
                                   :from-iteration 2}]}]

    (testing "produces the briefing shape with the async marker as :code"
      (let [out (compact async-rec)]
        (is (= 4 (:iteration out)))
        (is (true? (:async-completion? out))
            "marker is preserved so downstream renderers can dispatch on it")
        (is (= ["[↺ async-completion from iter 2 · task-id=task-7 · status=resolved]"]
               (:code out))
            "code line is replaced with a single marker — original source is not re-emitted")
        (is (= ["{:answer 42}"] (:result out)))
        (is (= ["ok"] (:output out)))
        (is (not (contains? out :error))
            "empty errors are stripped, same as the legacy projection")))

    (testing "normal records bypass the async branch and use the legacy code projection"
      (let [normal-rec {:iteration 1
                        :channel "code"
                        :code-results [{:lang "clojure"
                                        :code "(+ 1 2)"
                                        :result "3"
                                        :output ""}]}
            out (compact normal-rec)]
        (is (= ["(+ 1 2)"] (:code out))
            "normal records re-emit the source")
        (is (not (contains? out :async-completion?)))))

    (testing "error-status async completion carries the error"
      (let [out (compact (-> async-rec
                             (update-in [:code-results 0]
                                        assoc
                                        :status :error
                                        :error "boom"
                                        :result nil)))]
        (is (str/includes? (first (:code out)) "status=error"))
        (is (= ["boom"] (:error out)))
        (is (not (contains? out :result)))))))

(deftest async-completion-end-to-end-via-coact-inc-iter-test
  (testing "pending task → harvest → :iterations carries :from-iteration on the entry"
    ;; Post-Step-F dispatch routes through the task manager. The pending task
    ;; is created via run-clj-sandbox-block (which tags :coact/pending-from-iter
    ;; on the task's metadata); coact-inc-iter-action's harvest-pending-tasks!
    ;; walks the manager and synthesizes the async-completion record.
    (let [sandbox (clj-sandbox/create-sandbox)
          st (fresh-st-memory :iteration-count 2 :sandbox sandbox)
          ;; Iter 2 emits a slow eval — :from-iteration baked into the task's
          ;; metadata. A 50ms waiter deadline against a 300ms eval guarantees
          ;; the run-clj-sandbox-block returns :status :pending.
          pending (@#'rca/run-clj-sandbox-block sandbox
                                                "(do (Thread/sleep 300) {:ok true})"
                                                50
                                                :from-iteration 2)
          _ (is (= :pending (:status pending)))
          ;; Let the eval finish — task transitions to :completed in the manager.
          _ (Thread/sleep 500)]
      ;; Iter 3 starts → harvest folds the completion into :iterations.
      (rca/coact-inc-iter-action {:st-memory st})
      (let [iters (:iterations @st)
            rec   (first iters)
            entry (first (:code-results rec))]
        (is (= 1 (count iters)))
        (is (true? (:async-completion? rec)))
        (is (= 3 (:iteration rec)) "lands on the current iter")
        (is (= 2 (:from-iteration entry)) ":from-iteration round-trips")
        (is (= :resolved (:status entry)))
        (is (str/includes? (:thought rec) "from iter 2")
            "thought captures provenance for log-readers")
        (is (str/includes? (:thought rec) (:task-id entry)))
        ;; And the formatters render the metadata.
        (let [block (@#'rca/format-iterations-block iters)
              compact (@#'rca/compact-iteration-record rec)]
          (is (str/includes? block "from iter 2"))
          (is (str/includes? block "task-id="))
          (is (str/includes? (first (:code compact)) "from iter 2")))))))

;; ============================================================================
;; Enum dispatch through BOTH CoAct channels (regression for the keyword->enum
;; tool-field work). A JSON tool-call delivers the enum value as a wire STRING;
;; a sandbox code-fence delivers it as a KEYWORD. The bidirectional :enum
;; decoder must accept both forms and reject out-of-vocabulary values before the
;; handler runs — identically on each path.
;; ============================================================================

(deftest coact-enum-dispatch-tool-path
  (testing "tool-calls channel: string enum arg coerces to keyword; bad value rejected"
    (let [st (atom {:tool-calls [{:tool-name "test-coact-enum" :tool-args {:mode "alpha"}}
                                 {:tool-name "test-coact-enum" :tool-args {:mode "bogus"}}]
                    :iteration-count 1})]
      (rca/coact-tool-dispatch-action {:st-memory st :agent nil})
      (let [[ok bad] (:last-tool-results @st)]
        (is (str/includes? (:tool-result ok) "mode=:alpha")
            "wire string \"alpha\" must reach the handler as keyword :alpha")
        (is (str/includes? (:tool-result bad) "should be either")
            "out-of-enum string is rejected at the Malli layer")))))

(deftest coact-enum-dispatch-code-path
  (testing "code-blocks channel: keyword enum arg passes through; bad value rejected"
    (let [bindings (sb-bind/make-tool-bindings nil)
          sandbox  (clj-sandbox/create-sandbox :bindings bindings)
          st (atom {:sandbox sandbox
                    :code-blocks (str "```clojure\n"
                                      "(:mode (test-coact-enum {:mode :alpha}))\n"
                                      "```\n"
                                      "```clojure\n"
                                      "(test-coact-enum {:mode :bogus})\n"
                                      "```")
                    :iteration-count 1})]
      (rca/coact-code-eval-action {:st-memory st :agent nil})
      (let [[ok bad] (:last-code-results @st)]
        (is (= ":alpha" (:result ok))
            "code-fence keyword :alpha reaches the handler and is echoed")
        (is (str/includes? (str (:result bad)) "should be either")
            "out-of-enum keyword is rejected at the Malli layer")))))
