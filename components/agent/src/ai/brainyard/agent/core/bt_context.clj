;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.core.bt-context
  "CR-BT-25 — the declared contract for the behavior tree's shared context.

   The tree is declarative and validated by construction; the `st-memory` atom
   it runs against is not. Measured over the source, that bus carries at least
   54 distinct keys across 617 references in 46 files and 5 bricks, written by
   13 namespaces and read by 33 more. This namespace is where what is known
   about those keys is written down.

   Design: docs/design/bt-context-schema-design.md §3.1-§3.2, §5.2.

   ## What this is NOT (yet)

   Phase 0 is DECLARATION ONLY. Nothing here validates, gates, or derives at
   runtime; the consumers named in §5.2 are Phase 2+. What it buys today is
   that \"who writes :evaluation-status\" and \"does :sandbox survive a turn\"
   have an answer that is not a grep, and that the answer is checked against
   the source by `bt_context_test.clj` rather than left as prose to rot.

   ## The map is OPEN

   An undeclared key is legal and unchecked. Closing it would break the escape
   hatch that lets `usage-nudge`, `self-improve-nudge` and `procedure-nudge`
   stash `:pending-*` keys without touching a central file, which is a property
   worth keeping. Coverage grows by adding rows. `deliberately-undeclared`
   records the keys that were LOOKED AT and left out, so \"absent from the
   registry\" is distinguishable from \"nobody got to it yet\".

   ## No dependencies, on purpose

   `:schema` values are fully-qualified KEYWORDS rather than required vars, and
   `:writers` are unresolved SYMBOLS. Both are resolved late — the schema
   through clj-llm's process-wide malli registry at validation time, the
   writers by the test. So this namespace requires nothing, cannot form a cycle
   with the huge `common/coact-agent` where most of the schemas and all of the
   writers live, and can be required from anywhere a later phase needs it.

   ## Placement

   §7's open question stands: the eventual MECHANISM (the fold, the per-node
   checks) belongs in the `behavior-tree` component, which must not depend on
   `agent` (CR-BT-23). These DECLARATIONS are agent-level and live here. Phase 0
   has no mechanism to place, so nothing was speculatively pushed down."
  (:refer-clojure :exclude [key]))

;; ============================================================================
;; The declaration shape
;; ============================================================================

;; Each entry is {:doc _ :lifetime _ :persist? _ :writers #{sym} :schema kw?
;;                :opaque? bool?}
;;
;; :lifetime — WHEN the value is wiped. The two resets are real code, not
;;   convention, and the test pins the registry against both:
;;     :iteration — cleared by `coact-inc-iter-action`'s per-iteration assoc.
;;     :turn      — wiped by `agent.core.bt/reset-st-memory!`, which resets
;;                  st-memory to `st-memory-init` + :question on every turn.
;;     :session   — survives that reset because it lives in `st-memory-init`.
;;
;; :persist? — could this value be restored by `--resume`? False for anything
;;   holding a function or a live object (see :opaque?), and false for values
;;   that are cheaper to re-derive than to carry. This is the field that makes
;;   the SCI fn-serialization limit a declared fact rather than a discovery.
;;
;; :writers — every fn observed to write the key, as an unresolved symbol.
;;   PLURAL on purpose. The design doc proposed a single `:owner`; the source
;;   says that is a fiction for the keys that matter most — :display-stage has
;;   ten writers, :answer eight, :iterations seven. A key with one writer is
;;   the exception, not the rule, and a registry claiming otherwise would have
;;   been wrong on its most-used rows. See §3.1 as-built.
;;
;; :schema — a registry keyword, resolved through malli's default registry.
;;   Present only where a schema already exists; Phase 0 invents none.

(def ^:private coact "ai.brainyard.agent.common.coact-agent")
(def ^:private ctx   "ai.brainyard.agent.common.context-actions")

(defn- w
  "Build a `:writers` set from unqualified names in namespace `ns-str`."
  [ns-str & names]
  (into #{} (map #(symbol ns-str (str %))) names))

;; ============================================================================
;; Declarations
;; ============================================================================

(def context-keys
  "Declared st-memory keys. See the ns docstring: open map, plural writers,
   late-resolved schemas and writers."
  {;; ── Session: lives in st-memory-init, survives the per-turn reset ───────
   :previous-turns
   {:doc "Compacted Q/A + iteration summaries from earlier turns. The ONLY key a turn writes BACK into st-memory-init, which is what makes it outlive the reset."
    :lifetime :session :persist? true
    :writers (w coact "coact-store-results-action" "coact-strategies")}

   :config
   {:doc "Per-agent config override layer. Outranks session config in get-config's precedence; read via agent.core.config/agent-config-overrides."
    :lifetime :session :persist? true
    :writers #{}}

   :tools
   {:doc "The agent's tool roster, seeded at setup. Read back by agent.core.agent for the tool list."
    :lifetime :session :persist? true
    :writers #{}}

   :tools-fn-map
   {:doc "name -> fn map for the agent's tools. Holds FUNCTIONS, so it cannot be serialized."
    :lifetime :session :persist? false :opaque? true
    :writers #{}}

   :purpose
   {:doc "The derived agent's stated purpose, seeded at setup from its defagent meta."
    :lifetime :session :persist? true
    :writers #{}}

   :parent-trail
   {:doc "Last k previous-turns handed down from a parent agent at dispatch (derive-parent-handoff -> st-memory-init-overrides)."
    :lifetime :session :persist? true
    :writers (w coact "coact-strategies")}

   :share-parent-output-session
   {:doc "Subagent flag: render output into the parent's session rather than its own."
    :lifetime :session :persist? true
    :writers #{}}

   ;; ── Turn: wiped by reset-st-memory!, re-established each turn ───────────
   :question
   {:doc "The user's input for this turn. Stamped by reset-st-memory! itself, which is why it is the one key that survives the reset it is part of."
    :schema :ai.brainyard.agent.common.schema/question
    :lifetime :turn :persist? true
    :writers #{'ai.brainyard.agent.core.bt/reset-st-memory!
               'ai.brainyard.agent.core.bt/skill-behavior-fn}}

   :answer
   {:doc "The turn's final markdown answer. Non-blank is what terminates the loop."
    :schema :ai.brainyard.agent.common.schema/answer
    :lifetime :turn :persist? true
    :writers (w coact "coact-stamp-answer-action" "coact-ensure-answer-action"
                "coact-loop-fallback-action" "abort-turn-with-llm-error!"
                "append-evaluation-record!" "coact-init-action" "repair-no-action!")}

   :iterations
   {:doc "The model's replay buffer: capped at 10 records, truncated for context budget. A declared ThinkActCode input, so CR-BT-26 validates it on every LLM call."
    :schema :ai.brainyard.agent.common.coact-agent/iterations
    :lifetime :turn :persist? true
    :writers (w coact "coact-accumulate-iteration-action" "append-evaluation-record!"
                "harvest-pending-tasks!" "inject-in-flight-roster!"
                "resolve-pending-entries!" "coact-init-action" "coact-strategies")}

   :trajectory-iterations
   {:doc "Uncapped mirror of :iterations for trajectory recording — takes the answer iteration too, which :iterations deliberately does not."
    :lifetime :turn :persist? false
    :writers (w coact "coact-accumulate-iteration-action" "harvest-pending-tasks!"
                "coact-init-action")}

   :iteration-count
   {:doc "1-based iteration index within the turn."
    :lifetime :turn :persist? false
    :writers (w coact "coact-inc-iter-action" "coact-init-action")}

   :terminated
   {:doc "The loop's ONLY exit signal, read by the :repeat node's condition-fn. Set by an accepted answer or a fatal LLM error."
    :lifetime :turn :persist? false
    :writers (w coact "coact-stamp-answer-action" "coact-ensure-answer-action"
                "abort-turn-with-llm-error!" "coact-init-action"
                "coact-tool-dispatch-action" "repair-no-action!")}

   :context-briefing
   {:doc "Per-turn briefing: latest tool specs, agent-context snapshot, this turn's instruction. A declared ThinkActCode input."
    :schema :ai.brainyard.agent.common.coact-agent/context-briefing
    :lifetime :turn :persist? false
    :writers (w coact "coact-init-action")}

   :recalled-memory
   {:doc "Layer-grouped markdown rendering of cross-layer recall. Always a string — empty when there are no hits or no memory manager, which is why an absent-vs-blank distinction never reaches the prompt."
    :schema :ai.brainyard.agent.common.schema/recalled-memory
    :lifetime :turn :persist? false
    :writers (w ctx "prepare-recalled-memory-action")}

   :conversation
   {:doc "Per-turn conversation history assembled for the user-context block."
    :lifetime :turn :persist? true
    :writers (into (w ctx "prepare-conversation-action") (w coact "coact-strategies"))}

   :agent-core
   {:doc "Cache zone 1: static agent rules + substrates. Most stable; first in the :stable-keys order, so it is the outermost cache breakpoint."
    :lifetime :turn :persist? false
    :writers (w coact "coact-init-action" "repair-transient-failure!")}

   :session-context
   {:doc "Cache zone 2: session-stable overlays."
    :lifetime :turn :persist? false
    :writers (w coact "coact-init-action" "repair-transient-failure!")}

   :history-context
   {:doc "Cache zone 3: conversation/previous-turn history."
    :lifetime :turn :persist? false
    :writers (w coact "coact-init-action" "repair-transient-failure!")}

   :user-context
   {:doc "Cache zone 4: the volatile tail. Rendered in the system text with NO breakpoint of its own (:no-zone-keys) — the user-message marker covers it."
    :lifetime :turn :persist? false
    :writers (w coact "coact-rebudget-action" "repair-transient-failure!")}

   :sandbox
   {:doc "The live SCI evaluation context for ```clojure blocks. A runtime object: never serialized, and the reason --resume cannot restore user vars directly."
    :lifetime :turn :persist? false :opaque? true
    :writers (w coact "coact-init-action")}

   :signature
   {:doc "The ThinkActCode signature compiled for THIS turn's channel flags. Written once per turn so the prompt and the output schema cannot disagree; read by the dspy node via the from-st-memory sentinel."
    :lifetime :turn :persist? false
    :writers (w coact "coact-init-action")}

   :live-artifacts
   {:doc "Reference files and dynamic artifacts resolved for this turn's prompt."
    :lifetime :turn :persist? false
    :writers (w coact "coact-init-action" "coact-strategies")}

   :script-entries
   {:doc "Script-bridge entries available to this turn."
    :lifetime :turn :persist? false
    :writers (w coact "coact-init-action")}

   :code-langs
   {:doc "Executable code-block languages for this turn; narrows the code-blocks output schema."
    :lifetime :turn :persist? false
    :writers (w coact "coact-init-action")}

   :tools-disabled-tiers
   {:doc "Tool tiers switched off for this turn."
    :lifetime :turn :persist? false
    :writers (w coact "coact-init-action" "coact-strategies")}

   :tools-section-config
   {:doc "How the tool directory section is rendered into the prompt."
    :lifetime :turn :persist? false
    :writers (w coact "coact-init-action")}

   :turn-id
   {:doc "Per-agent turn identifier; qualifies memory audit rows and usage-tracker history entries."
    :lifetime :turn :persist? true
    :writers (w coact "coact-init-action")}

   :total-turns
   {:doc "Session-cumulative turn counter, for cross-agent ordering of memory rows."
    :lifetime :turn :persist? true
    :writers (w coact "coact-init-action")}

   :evaluation-status
   {:doc "Answer-evaluation state machine: {:phase :skipped|:llm-calling|:done :round n ...}."
    :lifetime :turn :persist? false
    :writers (w coact "coact-prepare-eval-action" "coact-process-eval-in-loop-action")}

   :last-repair-iter
   {:doc "Idempotence guard: which iteration coact-repair-action already handled, so the router-slot re-entry is a no-op."
    :lifetime :turn :persist? false
    :writers (w coact "coact-repair-action" "coact-init-action")}

   :consecutive-llm-failures
   {:doc "Malformed-output retry budget; compared against :max-retries-on-llm-malformed-output."
    :lifetime :turn :persist? false
    :writers (w coact "coact-stamp-answer-action" "coact-stamp-channel"
                "repair-malformed-output!" "repair-no-action!" "coact-init-action")}

   :last-trajectory-turn
   {:doc "Which turn-id was last appended to trajectory.edn; prevents a double append."
    :lifetime :turn :persist? false
    :writers (w coact "coact-store-results-action")}

   :dspy-raw-text
   {:doc "The model's raw reply text, kept when a parse failed so a pure-prose answer can be preserved AS the iteration thought."
    :lifetime :turn :persist? false
    :writers #{'ai.brainyard.behavior-tree.core.dspy-action/dspy}}

   :dspy-no-json-envelope?
   {:doc "True when the model replied in prose with no JSON envelope at all — routes repair to :notices rather than a fake code-result error."
    :lifetime :turn :persist? false
    :writers #{'ai.brainyard.behavior-tree.core.dspy-action/dspy}}

   :dspy-validation-errors
   {:doc "Output-schema validation failures from clj-llm, so repair re-prompts with a schema reminder instead of blindly retrying."
    :lifetime :turn :persist? false
    :writers #{'ai.brainyard.behavior-tree.core.dspy-action/dspy}}

   :pending-format-guidance
   {:doc "Schema correction queued by a pure-prose repair; drained onto the next iteration record's :notices."
    :lifetime :turn :persist? false
    :writers (w coact "repair-malformed-output!")}

   :llm-streaming-text
   {:doc "Accumulated streaming text for the TUI. Written from the chunk handler at streaming rates, so nothing should derive control flow from it."
    :lifetime :turn :persist? false
    :writers #{'ai.brainyard.agent.core.bt/chunk-factory-handler}}

   :todo-list
   {:doc "Mirror of the durable todo list, refreshed into st-memory for the prompt. The durable copy is the authority."
    :lifetime :turn :persist? false
    :writers #{'ai.brainyard.agent.common.todo/mirror-to-st-memory!
               'ai.brainyard.agent.common.todo/clear-st-memory-if-active!}}

   :active-todo-slug
   {:doc "Which todo the turn is working on; pairs with :todo-list."
    :lifetime :turn :persist? false
    :writers #{'ai.brainyard.agent.common.todo/mirror-to-st-memory!
               'ai.brainyard.agent.common.todo/clear-st-memory-if-active!}}

   ;; ── Iteration: cleared by coact-inc-iter-action's per-iteration assoc ────
   ;; This set is pinned by test — it must equal exactly what that fn resets, or
   ;; a key added to the reset (or dropped from it) silently changes lifetime.
   :display-stage
   {:doc "Which live block the TUI is rendering. Ten writers: every display action advances it, and it is a UI state machine rather than an owned value."
    :lifetime :iteration :persist? false
    :writers (w coact "coact-inc-iter-action" "coact-display-think-action"
                "coact-display-code-action" "coact-display-eval-action"
                "coact-display-tools-action" "coact-display-tool-results-action"
                "coact-stamp-answer-action" "coact-ensure-answer-action"
                "append-evaluation-record!" "repair-no-action!")}

   :tool-calls
   {:doc "This iteration's tool-call channel output. Reset to [] each iteration so a stale call cannot re-dispatch."
    :schema :ai.brainyard.agent.common.schema/tool-calls
    :lifetime :iteration :persist? false
    :writers (w coact "coact-inc-iter-action" "coact-strip-unbound-tool-calls-action"
                "abort-turn-with-llm-error!" "repair-malformed-output!")}

   :code-blocks
   {:doc "This iteration's code-block channel output. Reset to \"\" each iteration."
    :schema :ai.brainyard.agent.common.coact-agent/code-blocks
    :lifetime :iteration :persist? false
    :writers (w coact "coact-inc-iter-action" "abort-turn-with-llm-error!"
                "repair-malformed-output!")}

   :last-reasoning
   {:doc "The CoT reasoning for this iteration; becomes the record's :thought."
    :lifetime :iteration :persist? false
    :writers (into #{'ai.brainyard.behavior-tree.core.dspy-action/dspy}
                   (w coact "coact-inc-iter-action" "repair-malformed-output!"))}

   :last-channel
   {:doc "Which channel the router dispatched this iteration (:tool / :code / :answer / nil)."
    :lifetime :iteration :persist? false
    :writers (w coact "coact-inc-iter-action" "coact-stamp-channel")}

   :last-tool-results
   {:doc "Raw tool results from this iteration, before sanitization into the record."
    :lifetime :iteration :persist? false
    :writers (w coact "coact-inc-iter-action" "coact-tool-dispatch-action"
                "coact-code-eval-action" "repair-malformed-output!" "repair-no-action!")}

   :last-code-results
   {:doc "Raw eval entries from this iteration, before sanitization into the record."
    :lifetime :iteration :persist? false
    :writers (w coact "coact-inc-iter-action" "coact-code-eval-action"
                "coact-tool-dispatch-action" "abort-turn-with-llm-error!"
                "repair-malformed-output!" "repair-no-action!")}

   :eval-display
   {:doc "Structured code/result display accumulator for the iteration block. Cleared each iteration so a non-code iteration never re-renders the previous one's output."
    :lifetime :iteration :persist? false
    :writers (w coact "coact-inc-iter-action" "coact-display-code-action"
                "coact-display-eval-action" "inject-in-flight-roster!")}

   :goal-achieved
   {:doc "The model's self-assessment on an answer turn. Cleared each iteration so an omitted optional output cannot leak a prior value into the refine gate."
    :schema :ai.brainyard.agent.common.coact-agent/goal-achieved
    :lifetime :iteration :persist? false
    :writers (w coact "coact-inc-iter-action" "coact-capture-answer-meta")}

   :next-user-prompt
   {:doc "Suggested follow-up the user could send next. Cleared each iteration for the same leak reason as :goal-achieved."
    :schema :ai.brainyard.agent.common.coact-agent/next-user-prompt
    :lifetime :iteration :persist? false
    :writers (w coact "coact-inc-iter-action" "coact-stamp-answer-action"
                "coact-ensure-answer-action" "append-evaluation-record!"
                "coact-init-action")}

   :answer-decision
   {:doc "Per-answer routing: :accept | :refine-self | :needs-eval. Consumed by the answer-gate fallback."
    :lifetime :iteration :persist? false
    :writers (w coact "coact-inc-iter-action" "coact-capture-answer-meta"
                "append-evaluation-record!" "coact-init-action")}

   :dspy-error
   {:doc "Message from a failed LLM call. Its presence is what routes coact-repair-action to the malformed-output branch."
    :lifetime :iteration :persist? false
    :writers (into #{'ai.brainyard.behavior-tree.core.dspy-action/dspy}
                   (w coact "coact-inc-iter-action" "abort-turn-with-llm-error!"
                      "repair-malformed-output!" "repair-transient-failure!"))}

   :dspy-error-class
   {:doc "Failure classification — :malformed re-prompts, :transient re-runs, :fatal aborts. CR-BT-26 uses :fatal for a missing declared input, since neither of the other two can fix a key no action wrote."
    :lifetime :iteration :persist? false
    :writers (into #{'ai.brainyard.behavior-tree.core.dspy-action/dspy}
                   (w coact "coact-inc-iter-action" "abort-turn-with-llm-error!"
                      "repair-malformed-output!" "repair-transient-failure!"))}

   :dspy-error-reason
   {:doc "Human-readable cause shown when a :fatal error aborts the turn."
    :lifetime :iteration :persist? false
    :writers (into #{'ai.brainyard.behavior-tree.core.dspy-action/dspy}
                   (w coact "coact-inc-iter-action" "abort-turn-with-llm-error!"
                      "repair-malformed-output!" "repair-transient-failure!"))}})

(def deliberately-undeclared
  "Keys that were LOOKED AT and left out, with why. This exists so that
   `absent from the registry` is distinguishable from `nobody got to it`, which
   is the difference between a coverage gap and a decision."
  {:pending-usage-guides        "usage-nudge's own queue — owned end-to-end by one ns, read-and-cleared within a turn; the registry adds nothing."
   :pending-self-improve-notice "self-improve-nudge's queue; same reasoning."
   :procedure-action-count      "procedure-nudge's counter; same reasoning."
   :procedure-graph-id          "procedure-nudge's; same reasoning."
   :last-procedure              "procedure-nudge's; same reasoning."
   :continuation                "TUI-only — set by the /continue command, read once by the loop guard."
   :last-failure                "Written by the BT tracing overrides for debugging; no reader derives behaviour from it."
   :st-memory-atom              "Not an st-memory key — the TUI session map's handle TO the atom."
   :recalled-memory-hits        "Observability sibling of :recalled-memory; structured hits for skill callers, never prompted."
   :prompt-token-breakdown      "Rebudget's accounting detail, consumed only by the token breakdown."
   :cached-sections             "Rebudget's internal cache."
   :eval-context                "Set by prepare-eval for the EvaluateAnswer call; already schema'd as that signature's declared input, which CR-BT-26 checks."
   :evidence                    "Same as :eval-context."
   :previous-answer             "Refinement bookkeeping, read only by the refine gate in the same fn that writes it."
   :refinement-round            "Same as :previous-answer."
   :answer-complete             "Same as :previous-answer."
   :iterations-exhausted        "Same as :previous-answer."
   :pending-answer              "Same as :previous-answer."
   :events-inbox                "Event-bus delivery buffer; owned by the events ns."
   :sandbox-context             "Derived view of :sandbox, rebuilt per turn."
   :existing-sandbox-reused     "Init telemetry flag."})

;; ============================================================================
;; Accessors
;; ============================================================================

(defn declared?
  "Is `k` a declared context key?"
  [k]
  (contains? context-keys k))

(defn declaration
  "The declaration map for `k`, or nil."
  [k]
  (get context-keys k))

(defn keys-with-lifetime
  "Declared keys whose `:lifetime` is `lifetime` (:session / :turn / :iteration)."
  [lifetime]
  (into #{} (keep (fn [[k v]] (when (= lifetime (:lifetime v)) k))) context-keys))

(defn persistable-keys
  "Declared keys a `--resume` could restore: `:persist? true` and not `:opaque?`.

   This is the field §5.2 is about — today nothing distinguishes a serialisable
   value from a runtime handle, which is why a key holding a function silently
   cannot come back."
  []
  (into #{} (keep (fn [[k v]] (when (and (:persist? v) (not (:opaque? v))) k)))
        context-keys))

(defn opaque-keys
  "Declared keys holding a live object or a function: never serialized, and
   never structurally validated."
  []
  (into #{} (keep (fn [[k v]] (when (:opaque? v) k))) context-keys))

(defn writers-of
  "Every fn observed to write `k`, as unresolved symbols. Plural — see the
   `:writers` note above."
  [k]
  (:writers (declaration k) #{}))

(defn schema-of
  "The registry keyword naming `k`'s malli schema, or nil. Resolve it through
   clj-llm's default registry; it is deliberately not required here."
  [k]
  (:schema (declaration k)))

(defn coverage
  "Declared / deliberately-undeclared / total counts — what the registry
   actually covers, so the gap is a number rather than an impression."
  []
  {:declared     (count context-keys)
   :undeclared   (count deliberately-undeclared)
   :by-lifetime  (frequencies (map :lifetime (vals context-keys)))
   :persistable  (count (persistable-keys))
   :opaque       (count (opaque-keys))})
