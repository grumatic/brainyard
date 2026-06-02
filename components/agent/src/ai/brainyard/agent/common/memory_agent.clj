;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.common.memory-agent
  "Memory-agent — the LLM-driven steward of the layered memory stack.

   Other agents (coact, research, todo, …) reach memory-agent through
   `(call-tool \"memory-agent\" {:op ... ...})` whenever they need
   targeted bookkeeping that should not happen blindly from a hook:
   end-of-turn essence capture (Phase 3), explicit fact registration,
   scheduled consolidation (Phase 4), purge of orphaned L2 episodes,
   L3 fact verification (Phase 5), stats reporting.

   Architecture:
   - Built on the CoAct BT (via `coact/coact-behavior-tree`) — same
     substrate as research-agent — so the LLM sees the same three-channel
     output contract (tool-calls / code-blocks / answer) and uses the
     same loop semantics. We do NOT use `coact/run-coact-derived`
     directly because that would also merge coact-agent's bloated
     :agent-tools roster into ours; memory-agent's roster is
     deliberately narrow (read primitives + write primitives + the
     working-area triplet + `query$llm`).
   - Default `:max-iterations` is 10 — bookkeeping should finish in
     2-4 iterations. Hitting the cap usually means an arg-shape
     mismatch; the instruction tells the LLM to bail with :error
     rather than grind.
   - Forbidden by exclusion: `call-tool` (no spawning, Hard Rule 1) is
     simply not in the roster. The write-guard hook (`memory-agent/hooks`) makes
     this concrete from the OTHER direction — non-memory-agent
     callers cannot invoke the gated `memory$*` primitives.

   Phase 2 implements :op :stats and :op :remember end-to-end. Other
   ops are declared in the input schema and documented in the
   instruction so the LLM knows they exist (with a clean :error path),
   but the handlers ship in Phases 3-5."
  (:require [clojure.string]
            [ai.brainyard.agent.common.coact-agent :as coact]
            [ai.brainyard.agent.common.memory-agent.commands :as ma-cmds]
            [ai.brainyard.agent.common.memory-agent.hooks]
            [ai.brainyard.agent.common.memory-agent.instruction :as ma-instr]
            [ai.brainyard.agent.common.commands :as common-cmds]
            [ai.brainyard.agent.core.agent :as agent-core]
            [ai.brainyard.agent.core.tool :refer [defagent]]))

;; ============================================================================
;; Ask-fn — inherits coact's instruction/BT but NOT its tools
;; ============================================================================

(defn- merge-instruction
  "Concatenate two instruction strings with a blank line between. Either
   side being nil yields the other side."
  [base addition]
  (cond
    (nil? addition) base
    (nil? base)     addition
    :else           (str base "\n\n" addition)))

(def ^:const default-lm-str
  "Default sub-LM string for memory-agent's signature wrappers
   (`memory$essence-extract`, `memory$llm-consolidate`, etc.).
   Sonnet-class is the recommended default: cheap enough to run on
   every turn, smart enough for essence judgement and L2 → L3
   consolidation. Override by passing `:sub-lm-config` in the
   agent-call opts. The main CoAct loop's LLM follows the global
   default — set it via `clj-llm/configure-default-lm!` if you want
   something other than the global."
  "claude-code:sonnet")

(defn- synthesize-question
  "memory-agent uses :op/:hint/:content rather than :question, but
   `run-agent` enforces a non-empty :question. Build a readable string
   from the relevant input slots so the guard passes and the BT still
   reads the structured fields via inputs."
  [opts]
  (let [op (or (some-> (:op opts) name) "stats")
        bits (cond-> [(str ":op " op)]
               (:scope opts)      (conj (str ":scope " (name (:scope opts))))
               (:hint opts)       (conj (str ":hint " (pr-str (:hint opts))))
               (:content opts)    (conj (str ":content " (pr-str (:content opts))))
               (:kind opts)       (conj (str ":kind " (name (:kind opts))))
               (:fact-id opts)    (conj (str ":fact-id " (pr-str (:fact-id opts))))
               (:evidence opts)   (conj (str ":evidence " (pr-str (:evidence opts)))))]
    (clojure.string/join " " bits)))

(defn run-memory-agent
  "Tool-fn for memory-agent. Inherits coact-agent's `:instruction`,
   `:tool-context`, and `:bt-factory` so the LLM sees the same
   three-channel CoAct contract — but DOES NOT merge coact's
   `:agent-tools` roster. Memory-agent's roster (the primitives
   declared on the defagent) is the runtime tool surface.

   Defaults `:sub-lm-config` to `claude-code:sonnet` when the caller
   does not override — the sub-LM drives EssenceExtraction /
   LlmReducer / FactVerification chain-of-thought calls."
  [opts]
  (let [coact-meta (meta @#'coact/coact-agent)
        opts (cond-> opts
               (some? (:instruction coact-meta))
               (update :instruction merge-instruction (:instruction coact-meta))

               (some? (:tool-context coact-meta))
               (update :tool-context merge-instruction (:tool-context coact-meta))

               (some? (:bt-factory coact-meta))
               (update :bt-factory #(or % (:bt-factory coact-meta)))

               ;; Sub-LM default for signature wrappers. The runtime
               ;; selector (commands.clj/resolve-sub-lm) parses this
               ;; string lazily, so a string default is fine here.
               (nil? (:sub-lm-config opts))
               (assoc :sub-lm-config default-lm-str)

               ;; Synthesize :question from structured inputs so the
               ;; non-empty guard in run-agent passes. Callers that
               ;; supply their own :question (e.g. `bb tui ask`) are
               ;; left untouched.
               (clojure.string/blank? (str (:question opts)))
               (assoc :question (synthesize-question opts)))]
    (apply agent-core/run-agent (mapcat identity opts))))

;; ============================================================================
;; Tool roster — 20 primitives, frozen for Phase 2
;; ============================================================================
;;
;; Read primitives (open to all agents via the registry; bound here too
;; so memory-agent's own LLM dispatch reaches them):
;;   memory$stats memory$recall memory$read memory$explain memory$keywords
;;
;; Write primitives (gated to memory-agent by the write-guard hook):
;;   memory$write memory$promote memory$forget memory$keep! memory$archive!
;;   memory$consolidate memory$sweep-l2
;;
;; Working-area primitives (gated):
;;   memory$state-read memory$state-write memory$essence-append
;;
;; Reasoning:
;;   query$llm
;;
;; Phase 5 will add memory$verify-fact. NOT in this roster yet.
;; ============================================================================

(def memory-agent-tools
  "Vector of var refs for memory-agent's roster. Order matches the
   read → write → working-area → reasoning grouping in the docstring."
  [;; Read primitives
   #'ma-cmds/memory$read
   #'ma-cmds/memory$stats
   #'ma-cmds/memory$keywords
   #'common-cmds/memory$recall
   #'common-cmds/memory$explain

   ;; Write primitives — gated by the write-guard hook
   #'ma-cmds/memory$write
   #'ma-cmds/memory$promote
   #'ma-cmds/memory$forget
   #'ma-cmds/memory$keep!
   #'ma-cmds/memory$archive!
   #'ma-cmds/memory$consolidate
   #'ma-cmds/memory$sweep-l2

   ;; Working-area primitives — gated
   #'ma-cmds/memory$state-read
   #'ma-cmds/memory$state-write
   #'ma-cmds/memory$essence-append

   ;; LLM-backed signature wrappers — gated
   #'ma-cmds/memory$essence-extract
   #'ma-cmds/memory$llm-consolidate
   #'ma-cmds/memory$verify-fact

   ;; Purge planner (deterministic candidate-list builder) — gated
   #'ma-cmds/memory$purge-plan

   ;; Reasoning
   #'common-cmds/query$llm])

;; ============================================================================
;; defagent
;; ============================================================================

(defagent memory-agent
  "LLM-driven steward of the layered memory stack. Call as (memory-agent {:op ...}); :op ∈ stats | remember | essence | consolidate | purge | verify-fact | correct."
  run-memory-agent
  ;; Pin :bt-factory explicitly so direct entry-points (setup-agent-by-id
  ;; used by `bb tui ask` and tests) that resolve agent metadata without
  ;; going through run-memory-agent still get the CoAct BT. Default
  ;; :max-iterations is 10 (vs CoAct's 20 / research-agent's 30).
  :bt-factory (fn [{:keys [max-iterations]}]
                (coact/coact-behavior-tree (or max-iterations 10)))
  :tool-use-control {}
  :input-schema  [:map
                  ;; Discriminator — every op routes off this.
                  [:op          [:string {:desc "Operation: stats | remember | essence | consolidate | purge | verify-fact | correct"
                                          :default "stats"}]]
                  ;; Scope is shared across many ops.
                  [:scope       {:optional true} [:string {:desc ":session (default) | :user"}]]
                  ;; :stats — formatting hint.
                  [:format      {:optional true} [:string {:desc ":map (default) | :markdown — only for :op :stats"}]]
                  ;; :remember — what to save.
                  [:content     {:optional true} [:string {:desc ":remember — content to save"}]]
                  [:kind        {:optional true} [:string {:desc ":remember — entry kind (fact | preference | observation | …)"}]]
                  [:tags        {:optional true} [:vector {:desc ":remember — tag strings"} :string]]
                  [:confidence  {:optional true} [:double {:desc ":remember — confidence 0.0..1.0 (default 1.0)"}]]
                  [:session-id  {:optional true} [:string {:desc "Override session scope (default: current session)"}]]
                  ;; Forward-compat input slots — declared so Phase 3+ callers can
                  ;; address us with the right arg shape without schema errors.
                  [:turn-id     {:optional true} [:int    {:desc ":essence — per-agent turn id"}]]
                  [:total-turns {:optional true} [:int    {:desc ":essence — session total-turns at call time"}]]
                  [:hint        {:optional true} [:string {:desc ":essence — caller's one-line summary of the turn"}]]
                  [:window      {:optional true} [:any    {:desc ":consolidate — window selector {:hours N} | :recent | session-id"}]]
                  [:fact-id     {:optional true} [:string {:desc ":verify-fact / :correct — target fact id"}]]
                  [:evidence    {:optional true} [:string {:desc ":verify-fact / :correct — fresh evidence body"}]]
                  [:dry-run?    {:optional true} [:boolean {:desc ":purge — produce a proposal artifact without tombstoning"}]]]
  :output-schema [:map
                  [:answer [:string {:desc "Final answer in markdown; ends with one fenced clojure block carrying the §12 structured result (`{:status :op :counts ...}`). Callers parse the block as EDN."}]]]
  :agent-tools {:tools memory-agent-tools}
  ;; Sub-LM default for signature wrappers (essence-extract,
  ;; llm-consolidate, etc.). Set here so it lands in the agent's
  ;; per-agent config at setup-agent time via the config-schema
  ;; select-keys path.
  ;; Callers may override by passing :sub-lm-config in the call args.
  :sub-lm-config default-lm-str
  :instruction  ma-instr/instruction
  :tool-context ma-instr/tool-context)
