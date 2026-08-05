;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.core.feature
  "Feature registry — a *view* over `core.config/config-schema`, not a rewrite.

   `config-schema` is a flat map of 137 keys. That is the right storage model
   (one namespace, one precedence chain, one persistence path) but the wrong
   presentation model: the schema knows what each key *is* and nothing knows
   what each key *belongs to*. This namespace supplies that missing relation.

   Three kinds of key, partitioned exactly (enforced by `feature_test.clj`):

     gate         24  on/off for a capability — 22 boolean, 2 numeric-zero
     knob         86  tunes a capability; meaningless when its gate is off
     presentation 16  TUI rendering (the `:ui` family)
     ambient      10  always in effect, belongs to no capability
     unclassified  0  parking spot for keys nothing reads, see below

   A **feature** is {gate, knobs, lifecycle, requires, implies}. A **family**
   is a namespace of features. Ambient keys live in an explicit set — named,
   so the invariant test can prove the partition is total.

   Dependency direction: this namespace requires `core.config`, never the
   reverse. `config-schema` stays the leaf. Callers wanting a `:feature`
   annotation on `config/search-config-keys` hits use `annotate-hits` here
   rather than pushing the dependency down into config.

   Resolution (`feature-state` / `on?` / `on?*` / `off-reason`) applies
   `:implies` and `:requires` on top of the raw gate; an unmet hard requirement
   resolves a feature OFF (fail-safe) with `:unmet` populated. Gates are always
   read live — where a `:startup` gate configures a long-lived artifact, the
   artifact is the authority and its consumer asks it directly rather than
   re-deriving the flag. See docs/design/feature-flags-design.md §10.6.

   Two deliberate escape hatches, both temporary and both named on purpose so
   they read as standing to-dos rather than silent gaps:

   - `:proposed true` marks a feature whose *gate key does not exist in
     config-schema yet*. Its knobs are real and ARE claimed; only the gate is
     excluded from the invariant. P3 creates the six keys and drops the marker.
   - `unclassified-keys` quarantines schema keys that belong to no feature
     because nothing reads them. P3 wires or deletes them."
  (:require [clojure.string :as str]
            [ai.brainyard.agent.core.config :as config]
            [ai.brainyard.agent.core.runtime :as runtime]))

;; ============================================================================
;; Registry
;; ============================================================================

(def lifecycles
  "When a feature's gate is read — which is what makes a key restart-sensitive.

     :startup     baked once at boot (a change needs a restart)
     :session     installed once per session
     :live        re-read per turn/event
     :per-action  re-read at each action dispatch
     :per-call    re-read at each call site"
  #{:startup :session :live :per-action :per-call})

(def feature-registry
  "feature-id → {:title :family :gate :gate-pred :keys :live-keys :requires
                 :implies :requires-partial :lifecycle :proposed :presentation
                 :doc}

   `:gate` is a `config-schema` key (nil for an *ungated grouping* — a feature
   with no on/off knob, which exists so its knobs have a discoverable home).
   `:keys` are the knobs it owns. Every schema key appears in exactly one
   feature's gate-or-keys, or in `ambient-keys`, or in `unclassified-keys`.

   `:requires` — a set whose elements are either a feature id (hard
   requirement) or a *nested set* of feature ids meaning \"any of these\".
   An unmet requirement resolves the feature off (P1); it is fail-safe.

   `:requires-partial` — {feature-id consequence-string}. Does NOT flip the
   feature off; annotates a degraded state. This is the honest encoding for
   relations like FSM/scheduler, where the feature still works but loses a
   capability.

   `:implies` — features switched on transitively when this one is on.

   `:live-keys` — keys exempt from their feature's `:startup` lifecycle
   because they are re-read rather than baked."
  {;; --- memory --------------------------------------------------------------
   :memory/capture
   {:title     "Episode capture (L1/L2)"
    :family    :memory
    :gate      :enable-memory-capture
    :keys      [:memory-question-max-chars :memory-answer-max-chars]
    :lifecycle :startup
    :doc       "Q&A capture into the layered memory store at ask/post."}

   :memory/recall
   {:title     "Memory recall"
    :family    :memory
    :gate      :enable-memory-recall
    :keys      [:recall-limit :memory-recall-snippet-chars]
    ;; Deliberately does NOT require :memory/capture. The store is USER-scoped
    ;; (~/.brainyard/memory/<user-id>.db) and long-lived, so a session can
    ;; usefully read a corpus it does not write to — the one-shot `by ask`
    ;; session is exactly that: it should answer with the benefit of prior
    ;; memory without adding its own throwaway Q&A. Nothing mechanical couples
    ;; them either: the memory manager is created unconditionally in
    ;; core.agent/create-agent ("always created"), and only `start-capture!`
    ;; is gated on :memory/capture.
    :lifecycle :live
    :doc       "FTS (+ optional graph/vector) recall of prior episodes into
                the prompt. Read-only: works with capture off."}

   :memory/mid-turn-recall
   {:title     "Mid-turn recall"
    :family    :memory
    :gate      :enable-mid-turn-recall
    :keys      []
    :requires  #{:memory/recall}
    :lifecycle :live
    :doc       "Additional recall pass partway through a turn."}

   :memory/consolidation
   {:title     "Memory consolidation (L2→L3)"
    :family    :memory
    :gate      :enable-memory-consolidation
    :root-only true
    :keys      [:memory-consolidate-every-n-turns]
    :requires  #{:memory/capture}
    :lifecycle :live
    :doc       "Periodic reduction of episodes into durable summaries."}

   :memory/graph
   {:title     "Context-graph memory overlay"
    :family    :memory
    :gate      :enable-graph-memory
    :keys      [:graph-embed-model :graph-extract-model :graph-extract-mode
                :graph-extract-max-input-chars :graph-max-entities-per-episode
                :graph-max-relations-per-episode :graph-extract-batch-episodes
                :graph-max-nodes :graph-max-edges :graph-prune-orphans?]
    ;; startup feature, but this key is re-read at each graph-build/reduce
    ;; (its own :doc says so) — without the exemption the derived
    ;; restart-required set would be 14 where the schema says 13.
    :live-keys #{:graph-extract-batch-episodes}
    :requires  #{:memory/capture}
    :implies   #{:memory/consolidation}
    :lifecycle :startup
    :doc       "Typed entity/relationship graph + vector index layered over the
                L1/L2/L3 FTS store as extra RRF recall signals."}

   :memory/project
   {:title     "Project memory"
    :family    :memory
    :gate      :enable-project-memory
    :keys      [:project-memory-max-chars]
    :lifecycle :live
    :doc       "Plain-file project notes under .brainyard/memory/."}

   ;; --- self-improve --------------------------------------------------------
   :self-improve/distillation
   {:title     "Skill distillation"
    :family    :self-improve
    :gate      :enable-skill-distillation
    :root-only true
    :keys      [:skill-distill-mode :skill-distill-every-n-turns
                :skill-distill-threshold]
    :lifecycle :live
    :doc       "Batch extraction of reusable skills from session trajectories."}

   :self-improve/refinement
   {:title     "Skill refinement"
    :family    :self-improve
    :gate      :enable-skill-refinement
    :keys      []
    :lifecycle :live
    :doc       "Revision of existing skills against new evidence."}

   :self-improve/nudges
   {:title     "Self-improvement nudges"
    :family    :self-improve
    :gate      :enable-self-improve-nudges
    :root-only true
    :keys      []
    ;; nested set = \"any of\": the nudge surfaces pending proposals, so it can
    ;; never fire unless at least one producer is on. Today that is silent.
    :requires  #{#{:self-improve/distillation :self-improve/refinement}}
    :lifecycle :live
    :doc       "Surfaces pending skill proposals to the user."}

   ;; --- automation ----------------------------------------------------------
   :automation/scheduler
   {:title     "Scheduler"
    :family    :automation
    :gate      :enable-scheduler
    :keys      [:scheduler-tick-ms]
    :lifecycle :session
    :doc       "Time-triggered prompt jobs and the tick that drives them."}

   :automation/reactions
   {:title     "Event reactions"
    :family    :automation
    :gate      :enable-reactions
    :root-only true
    :keys      [:max-reaction-fires-per-session]
    :lifecycle :session
    :doc       "trigger → action rules over the event bus."}

   :automation/fsm
   {:title            "User-defined state machines"
    :family           :automation
    :gate             :enable-fsm
    :root-only        true
    :keys             [:fsm-allow-code]
    ;; NOT a hard :requires — an FSM still works event-driven without the
    ;; ticker, it just cannot advance eventless transitions. Silence is what
    ;; we have today; this makes the degradation visible.
    :requires-partial {:automation/scheduler
                       "timed/eventless (:always/:after) transitions never advance"}
    :lifecycle        :session
    :doc              "Stateful states/transitions graphs plus their per-session runtime."}

   :automation/hooks
   {:title     "User hooks"
    :family    :automation
    :gate      :enable-user-hooks
    :keys      []
    :lifecycle :live
    :doc       "User-authored lifecycle hooks."}

   :automation/gateway
   {:title     "Pairing gateway"
    :family    :automation
    :gate      :enable-gateway
    :keys      [:gateway-pair-code-ttl-ms]
    :lifecycle :session
    :doc       "Device-pairing gateway."}

   ;; --- context -------------------------------------------------------------
   :context/budget
   {:title     "Context budgeting"
    :family    :context
    :gate      :enable-context-budget
    :keys      [:context-budget-safety-ratio :max-context-tokens
                :rebudget-every-n-iter]
    :lifecycle :live
    :doc       "Turn-init budget and per-iteration rebudget of the prompt."}

   :context/compaction
   {:title     "Cross-turn auto-compaction"
    :family    :context
    :gate      :enable-compaction
    :keys      [:compaction-target-ratio]
    :requires  #{:context/budget}
    :lifecycle :live
    :doc       "Compacts conversation history across turns when the budget tightens."}

   :context/live-artifacts
   {:title     "Live artifacts"
    :family    :context
    :gate      :enable-live-artifacts
    :keys      [:reference-artifact-paths :live-artifact-max-chars
                :skill-artifact-max-chars]
    :lifecycle :live
    :doc       "Reference files + dynamic artifacts injected into the prompt."}

   :context/console-activity
   {:title     "Console activity"
    :family    :context
    :gate      :enable-console-activity
    :keys      [:console-activity-max-entries :console-activity-result-chars]
    :lifecycle :live
    :doc       "Records colon-command interactions as prompt context."}

   :context/conversation
   {:title     "Conversation window"
    :family    :context
    :gate      nil
    :keys      [:conversation-limit :conversation-style :conversation-keep-verbatim]
    :lifecycle :live
    :doc       "Sliding window over prior turns."}

   ;; --- exec ----------------------------------------------------------------
   :exec/code-channel
   {:title     "Code channel"
    :family    :exec
    :gate      :code-channel?
    :keys      [:clj-backend :exec-backend :sandbox-interop]
    :lifecycle :session
    :doc       "The in-process code-eval channel and its backends."}

   :exec/tool-channel
   {:title     "Tool channel"
    :family    :exec
    :gate      :tool-channel?
    :keys      []
    :lifecycle :session
    :doc       "The JSON tool-calls emission channel. Off ⇒ code-only; tools stay reachable as sandbox callables."}

   :exec/sandbox-persistence
   {:title     "Sandbox persistence"
    :family    :exec
    :gate      :enable-sandbox-persistence
    :keys      []
    :requires  #{:exec/code-channel}
    :lifecycle :live
    :doc       "Persists sandbox bindings across turns."}

   :exec/nrepl
   {:title     "nREPL server"
    :family    :exec
    :gate      :nrepl-enabled?
    :keys      [:nrepl-port :nrepl-host]
    :requires  #{:exec/code-channel}
    :lifecycle :session
    :doc       "In-process nREPL for live inspection."}

   :exec/tasks
   {:title     "Task lifecycle thresholds"
    :family    :exec
    :gate      nil
    ;; The three thresholds in the order they apply: fast-eval decides whether
    ;; work is promoted to a tracked task at all, task-timeout bounds it, and
    ;; auto-background decides when a still-running foreground task detaches.
    ;; :fast-eval-timeout-ms lives here rather than under code-channel because
    ;; it governs EVERY tool call, not just code — so it stays live even with
    ;; :code-channel? false.
    :keys      [:task-timeout-ms :task-heartbeat-interval-ms
                :fast-eval-timeout-ms :auto-background-timeout-ms]
    :lifecycle :live
    :doc       "Thresholds governing a unit of work's promotion to a task."}

   :exec/task-notify
   {:title     "Auto task notification"
    :family    :exec
    :gate      :enable-auto-task-notify
    :root-only true
    :keys      [:auto-park-after-polls]
    :lifecycle :live
    :doc       "Wakes the agent when a background task completes."}

   :exec/iteration-hold
   {:title     "Iteration hold"
    :family    :exec
    :gate      :enable-iteration-hold
    :keys      [:hold-max-wait-ms]
    :lifecycle :per-action
    :doc       "Holds the loop briefly for an in-flight task to settle."}

   :exec/gc
   {:title     "Artifact GC"
    :family    :exec
    :gate      :enable-artifact-gc
    :keys      [:task-retention-count :task-retention-days
                :coact-scratch-max-age-hours :sandbox-cache-max-files
                :sandbox-cache-max-bytes :sandbox-cache-max-age-days]
    :lifecycle :live
    :doc       "Retention sweeps over task output, scratch and sandbox cache."}

   :exec/catalog-refresh
   {:title     "Model catalog refresh"
    :family    :exec
    :gate      :enable-catalog-refresh
    :keys      [:catalog-refresh-ttl-hours]
    :lifecycle :live
    :doc       "Refresh the model catalog from each provider's model-list endpoint."}

   ;; --- agents --------------------------------------------------------------
   :agents/subagents
   {:title     "Subagent calls"
    :family    :agents
    :gate      :enable-subagent-calls
    :keys      [:max-agent-call-depth :max-subagents-per-session :parent-trail-k]
    :lifecycle :per-call
    :doc       "Agent-to-agent invocation, depth and roster limits."}

   :agents/acp
   {:title     "ACP backends"
    :family    :agents
    :gate      :enable-acp
    :keys      [:acp-backend :acp-backend-opts :acp-client-fs :acp-timeout-ms
                :acp-permission-timeout-ms :max-acp-agents-per-session]
    :requires  #{:agents/subagents}
    :lifecycle :session
    :doc       "External agents driven over the Agent Client Protocol."}

   :agents/a2a
   {:title     "A2A peers"
    :family    :agents
    :gate      :enable-a2a
    :keys      [:a2a-peers :a2a-timeout-ms :a2a-stream? :a2a-max-peers-per-session
                :a2a-dialect
                :a2a-serve-host :a2a-serve-port :a2a-serve-token :a2a-expose-skills
                :a2a-max-contexts :a2a-context-ttl-ms]
    ;; A remote peer IS a subagent — it is registered, asked and evicted
    ;; through the same machinery. Turning off subagent calls must therefore
    ;; stop remote traffic too, so there is ONE kill-switch rather than two
    ;; that can disagree.
    :requires  #{:agents/subagents}
    :lifecycle :session
    :doc       "Remote agents reached over the Agent2Agent protocol, and the local A2A server."}

   :agents/explore
   {:title     "Explore agent"
    :family    :agents
    :gate      nil
    :keys      [:explore-persist-threshold :explore-auto-persist
                :explore-reuse-volatile-hours]
    :lifecycle :live
    :doc       "Exploration result persistence and reuse."}

   :agents/workflow
   {:title     "Workflow finalization"
    :family    :agents
    :gate      nil
    :keys      [:workflow-auto-finalize :research-auto-finalize]
    :lifecycle :live
    :doc       "Auto-finalization of workflow and research runs."}

   ;; --- reasoning -----------------------------------------------------------
   :reasoning/loop
   {:title     "Agent loop"
    :family    :reasoning
    :gate      nil
    :keys      [:max-iterations]
    :lifecycle :live
    :doc       "Iteration ceiling and per-loop retention windows."}

   :reasoning/refinement
   {:title     "Answer refinement pass"
    :family    :reasoning
    :gate      :max-refinements
    :gate-pred pos?                     ; numeric gate: 0 = off
    :keys      [:eval-lm-config]
    :lifecycle :live
    :doc       "Post-evaluation refinement passes over the draft answer."}

   :reasoning/sub-llm
   {:title     "Sub-LLM queries"
    :family    :reasoning
    :gate      nil
    :keys      [:sub-lm-config :llm-query-max-depth]
    :lifecycle :live
    :doc       "The cheaper model used for nested llm-query calls."}

   :reasoning/recovery
   {:title     "LLM failure recovery"
    :family    :reasoning
    :gate      nil
    :keys      [:max-retries-on-llm-empty-result
                :max-retries-on-llm-malformed-output
                :max-retries-on-llm-transient :max-retries-on-llm-no-action
                :empty-result-retry-base-ms]
    :lifecycle :live
    :doc       "Retry budgets per DSPy error classification."}

   ;; --- tools ---------------------------------------------------------------
   :tools/cache
   {:title     "Tool result cache"
    :family    :tools
    :gate      :tool-cache-ttl
    :gate-pred pos?                     ; numeric gate: 0 = off
    :keys      [:tool-cache-readers]
    :lifecycle :live
    :doc       "TTL cache over read-only tool results."}

   :tools/mcp
   {:title     "MCP tools"
    :family    :tools
    :gate      nil
    :keys      [:mcp-allow-tools]
    :lifecycle :live
    :doc       "Allowlist over tools exposed by MCP servers."}

   :tools/ask-channel
   {:title     "Ask channel"
    :family    :tools
    :gate      :ask-channel-enabled?
    :keys      [:ask-timeout-ms]
    :lifecycle :session
    :doc       "The AF_UNIX channel subagents use to ask the user."}

   :tools/oauth
   {:title     "OAuth"
    :family    :tools
    :gate      nil
    :keys      [:oauth-qr? :oauth-token-store :oauth-flow]
    :lifecycle :live
    :doc       "Device-flow OAuth and its token store."}

   ;; --- analytics -----------------------------------------------------------
   :analytics/trajectory
   {:title     "Trajectory recording"
    :family    :analytics
    :gate      :enable-trajectory-recording
    :keys      []
    :lifecycle :live
    :doc       "Append-only per-session trajectory log."}

   :analytics/scoring
   {:title     "Trajectory scoring"
    :family    :analytics
    :gate      nil
    :keys      [:analytics-lm-config :analytics-shs-weights]
    :requires  #{:analytics/trajectory}
    :lifecycle :live
    :doc       "SHS scoring over recorded trajectories."}

   ;; --- ui ------------------------------------------------------------------
   :ui/display
   {:title        "Display"
    :family       :ui
    :gate         nil
    :presentation true
    :keys         [:show-llm-streaming :display-format :max-collapsed-lines
                   :max-expanded-lines :resume-scrollback-bytes
                   :show-memory-activity :enable-tmux-popup
                   :enable-input-suggestions]
    :lifecycle    :live
    :doc          "Terminal rendering and input affordances."}

   :ui/blocks
   {:title        "Live blocks"
    :family       :ui
    :gate         nil
    :presentation true
    :keys         [:dispose-think-block :dispose-iteration-block
                   :dispose-task-block :dispose-agent-block :dispose-acp-block
                   :acp-message-max-lines :acp-show-thoughts
                   :acp-show-final-answer]
    :lifecycle    :live
    :doc          "Disposal and sizing of the TUI's live blocks."}})

(def ambient-keys
  "Always in effect; belongs to no capability. An explicit named set, not a
   fallthrough — the invariant test needs it enumerated to prove the partition
   is total.

   `:allowed-dirs` and `:permission-mode` must never become gateable: they are
   the security floor, and the config-agent's hard rules already treat them as
   human-approval-only.

   `:feature-profile` is ambient for a different reason: it configures the
   feature system rather than any capability within it, and gating it with a
   feature would be circular.

   `:enable-tool-binding` is `:enable-*`-shaped but ambient, and that is the
   §4.11 case for giving such a key an explicit home rather than a gate: it
   removes no capability. With it off, every tool stays callable through the
   registry and discoverable via `list-tools` — only the up-front roster in
   the prompt goes away. It sits beside its two siblings
   (`:compact-agent-tools`, `:include-function-directory`), which tune the
   same prompt real estate."
  #{:lm-config :dirs :allowed-dirs :permission-mode :max-output-tokens
    :max-output-chars :max-thought-chars :claude-code-max-turns
    :include-function-directory
    :compact-agent-tools :enable-tool-binding :inline-usage-guides
    :feature-profile})

(def unclassified-keys
  "Schema keys claimed by no feature because nothing reads them. Empty, and the
   mechanism is kept deliberately.

   A key that loses its last reader gets parked here BY NAME rather than
   quietly excluded from the partition test, so it reads as a standing to-do.
   The resolution is then either to wire it or to delete it — and wiring only
   counts if the code it would drive is actually reachable; pointing a settable
   key at unreachable code is worse than the dead key, because it invites
   belief that it does something."
  #{})

;; ============================================================================
;; Derived indexes
;; ============================================================================

(defn gate-of
  "The feature's gate key, or nil when it is an ungated grouping OR its gate is
   still `:proposed` (not yet a schema key)."
  [f]
  (when-not (:proposed f) (:gate f)))

(defn feature-keys
  "Every schema key this feature owns: its knobs plus its gate when that gate
   actually exists in the schema.

   Must not blindly `cons` the gate — 10 of the features are ungated groupings
   whose `:gate` is nil, and a nil in the derived set breaks both the
   restart-key derivation and the partition test. `:proposed` features
   contribute their knobs (which are real schema keys today) but not their
   gate (which is not)."
  [f]
  (let [g (gate-of f)]
    (cond-> (vec (:keys f)) g (conj g))))

(def all-features
  "Registry values, order-independent."
  (vals feature-registry))

(def claimed-keys
  "Every schema key claimed by some feature. Union with `ambient-keys` and
   `unclassified-keys` must equal `config/config-keys` — see feature_test."
  (into #{} (mapcat feature-keys) all-features))

(def feature-of-key
  "schema key → feature id. Inverted index; a key belongs to exactly one
   feature (enforced by `no-key-claimed-twice`)."
  (into {}
        (for [[fid f] feature-registry
              k       (feature-keys f)]
          [k fid])))

(def family->features
  "family keyword → sorted vector of feature ids."
  (->> feature-registry
       (group-by (comp :family val))
       (reduce-kv (fn [m fam entries]
                    (assoc m fam (vec (sort (map key entries)))))
                  {})))

(def families
  "All family keywords, sorted."
  (vec (sort (keys family->features))))

(def gate-keys
  "Schema keys serving as a feature gate today (excludes `:proposed` gates)."
  (into #{} (keep gate-of) all-features))

(def restart-required-keys
  "Keys whose feature is read at boot — derived from `:lifecycle :startup`
   minus each feature's `:live-keys` exemptions.

   Restart-ness is not a property of a key; it is a property of *when its
   feature is read*. P3 replaces `config-schema`'s per-key `:requires-restart`
   flag with this derivation, at which point a key added to a startup feature
   inherits the warning automatically. Until then this must agree with
   `config/restart-required-keys` — asserted by feature_test."
  (into #{}
        (comp (filter #(= :startup (:lifecycle %)))
              (mapcat #(remove (:live-keys % #{}) (feature-keys %))))
        all-features))

(def family-gates
  "family → master switch key in `config-schema` (§9 Q1).

   ANDed into every **gated** feature in the family: false forces them off,
   true defers to each feature's own gate. Ungated groupings are unaffected —
   the family switch is a master over the family's switches, not a way to turn
   off a group of knobs, and `/feature reasoning off` should not claim to have
   disabled the agent loop.

   A plain boolean defaulting true, not a tri-state: under AND semantics
   `true` and \"never set\" do the same thing, so a third state would buy only
   the ability to report \"explicitly enabled\" — at the cost of a schema type
   that `valid-config-value?` and `coerce-config-value` would both have to
   learn. Non-destructiveness (turn a family off, then on, and per-feature
   settings come back) comes from the key being SEPARATE from the member
   gates, which both designs give.

   `:ui` has no entry: it is `:presentation`, and turning off \"the UI\" is not
   a coherent operation."
  {:memory       :enable-memory
   :self-improve :enable-self-improve
   :automation   :enable-automation
   :context      :enable-context
   :exec         :enable-exec
   :agents       :enable-agents
   :reasoning    :enable-reasoning
   :tools        :enable-tools
   :analytics    :enable-analytics})

(def family-gate-keys
  "The master-switch keys. Classified separately from feature keys — a family
   gate belongs to a family, not to any one feature — so the partition test
   unions this in alongside `claimed-keys` and `ambient-keys`."
  (set (vals family-gates)))

(def ^:private implied-by-index
  "feature id → set of features that `:implies` it. Reverse of `:implies`, so
   resolution can ask \"who turns this on?\" without sweeping the registry."
  (reduce-kv (fn [m fid f]
               (reduce (fn [m t] (update m t (fnil conj #{}) fid)) m (:implies f)))
             {} feature-registry))

;; ============================================================================
;; Resolution
;; ============================================================================

;; ============================================================================
;; BY_FEATURES — one-line bulk override for containers and CI
;; ============================================================================

;; Defined below with the other id/name parsers; needed here by the BY_FEATURES
;; parser, which resolution calls.
(declare resolve-feature)

(defonce ^:private !features-env-cache
  ;; [raw-string parsed-map]. A defonce atom holding nil is native-image-safe:
  ;; the env var is never read at namespace load, so the BUILD machine's
  ;; environment cannot be baked into the binary.
  (atom nil))

(defn- parse-features-spec
  "\"+memory.graph,-automation.reactions\" → {:memory/graph true
                                              :automation/reactions false}
   A bare token is treated as `+`. Unresolvable tokens are dropped rather than
   throwing — a typo in a container env var should not stop the binary."
  [raw]
  (when-let [raw (some-> raw str/trim not-empty)]
    (into {}
          (keep (fn [tok]
                  (let [tok (str/trim tok)]
                    (when (seq tok)
                      (let [on? (not= \- (first tok))
                            nm  (if (contains? #{\+ \-} (first tok)) (subs tok 1) tok)]
                        (when-let [fid (resolve-feature nm)]
                          [fid on?]))))))
          (str/split raw #","))))

(defn features-env-overrides
  "Parsed `BY_FEATURES`, or nil. Re-read each call (a JVM env lookup is cheap)
   and memoized by the raw string so the parse happens once per distinct value.

   Families are deliberately not accepted here: every family master switch has
   its own `BY_ENABLE_*` variable already, so this covers what those cannot."
  []
  (let [raw (System/getenv "BY_FEATURES")
        [cached parsed] @!features-env-cache]
    (if (and (some? @!features-env-cache) (= raw cached))
      parsed
      (let [p (parse-features-spec raw)]
        (reset! !features-env-cache [raw p])
        p))))

(defn- gate-env-set?
  "True when the gate key's own `:env-fn` variable is set (e.g.
   BY_ENABLE_GRAPH_MEMORY). Those names predate the registry and stay
   authoritative: a specific per-key variable beats the bulk BY_FEATURES list."
  [g]
  (not= config/env-unset (config/schema-env-value g)))

(defn- family-on?
  "The family's master switch. True when the family has none (`:ui`).

   Only an explicit `false` disables: a nil read means the key resolved to
   nothing, and the schema default is true, so nil defers rather than kills.
   This is what makes the switch a kill-switch rather than an opt-in — and it
   means a caller reading config through a partial view (a test stub, a
   snapshot missing the key) is not silently switched off by a key it never
   heard of."
  [read-fn fam]
  (if-let [k (family-gates fam)]
    (let [v (read-fn k)]
      (if (nil? v) true (boolean v)))
    true))

(defn- gate-passes?
  "Apply a feature's gate predicate to a raw config value. A `:gate-pred`
   feature is numeric (0 = off), so a nil/non-numeric value reads as off
   rather than throwing."
  [v pred]
  (if pred
    (boolean (and (number? v) (pred v)))
    (boolean v)))

(defn- base-on?
  "The feature's own gate, deps ignored. Ungated groupings are always on.

   Always reads live through `read-fn`, including for `:startup` features.
   Resolution deliberately does NOT snapshot boot values: where a startup gate
   configures a long-lived artifact, the artifact itself is the authority, and
   the consumer asks it rather than re-deriving the flag. `:enable-graph-memory`
   is the worked example — `core.memory/create-memory-manager` stamps
   `:graph-enabled?` on the manager and the memory-agent hooks read that.
   Snapshotting here instead would have made the boot value outrank per-agent
   overrides for every startup gate. See feature-flags-design.md §10.6."
  [read-fn fid f]
  (if (nil? (:gate f))
    ;; Ungated grouping: no switch of its own, and the family master switch
    ;; deliberately does not reach it (see `family-gates`).
    true
    (and (family-on? read-fn (:family f))
         (let [g (gate-of f)]
           ;; `gate-of` is nil for a :proposed feature — its own gate is not a
           ;; schema key yet, so only the family switch can turn it off.
           (cond
             (nil? g) true
             ;; A specific BY_ENABLE_* beats the bulk BY_FEATURES list.
             (gate-env-set? g) (gate-passes? (read-fn g) (:gate-pred f))
             :else
             (if-some [ov (get (features-env-overrides) fid)]
               ov
               (gate-passes? (read-fn g) (:gate-pred f))))))))

(declare ^:private final-on?)

(defn- memo!
  "Cache `(f)` under `[bucket fid]` in the per-resolution memo. Stores false
   values correctly (a plain `get` miss would recompute them forever)."
  [!memo bucket fid f]
  (let [path [bucket fid]]
    (if-some [hit (get-in @!memo path)]
      (:v hit)
      (let [v (f)]
        (vswap! !memo assoc-in path {:v v})
        v))))

(defn- closed-on?
  "Implication closure: on if its own gate is on, or if anything that
   `:implies` it is closed-on. Monotone increasing over an acyclic graph
   (`implies-graph-is-acyclic` enforces that), so it terminates."
  [read-fn !memo fid]
  (memo! !memo :closed fid
         (fn []
           (let [f (feature-registry fid)]
             (or (base-on? read-fn fid f)
                 (boolean (some #(closed-on? read-fn !memo %)
                                (implied-by-index fid))))))))

(defn- req-met?
  "A `:requires` element: a feature id (hard) or a nested set (any-of)."
  [read-fn !memo r]
  (if (set? r)
    (boolean (some #(final-on? read-fn !memo %) r))
    (final-on? read-fn !memo r)))

(defn- final-on?
  "Requirement pruning over the closure. Monotone decreasing over an acyclic
   graph (`requires-graph-is-acyclic`), so it terminates.

   Two monotone passes — closure first, then pruning — rather than one
   interleaved fixpoint. Interleaving could oscillate (an implication turns a
   feature on, its unmet requirement turns it off, repeat); this cannot."
  [read-fn !memo fid]
  (memo! !memo :final fid
         (fn []
           (let [f (feature-registry fid)]
             (and (closed-on? read-fn !memo fid)
                  (every? #(req-met? read-fn !memo %) (:requires f)))))))

(defn- unmet-requires
  "The `:requires` elements that are not satisfied."
  [read-fn !memo f]
  (into #{} (remove #(req-met? read-fn !memo %)) (:requires f)))

(defn- degraded-notes
  "`:requires-partial` targets that are off, with their stated consequence.
   Does NOT affect `:on?` — the feature still works, minus a capability."
  [read-fn !memo f]
  (into {} (for [[target msg] (:requires-partial f)
                 :when (not (final-on? read-fn !memo target))]
             [target msg])))

(defn- state*
  [read-fn fid]
  (when-let [f (feature-registry fid)]
    (let [!memo   (volatile! {})
          on      (final-on? read-fn !memo fid)
          base    (base-on? read-fn fid f)
          impliers (into #{} (filter #(closed-on? read-fn !memo %))
                         (implied-by-index fid))]
      {:feature    fid
       :on?        on
       :source     (cond (not on)  :off
                         base      :base
                         :else     :implied-by)
       :implied-by (if base #{} impliers)
       :unmet      (unmet-requires read-fn !memo f)
       :degraded   (degraded-notes read-fn !memo f)
       :lifecycle  (:lifecycle f)})))

(defn- snap-get
  "Read a key from a `config/get-config-snapshot` map. The snapshot omits
   `:default-fn`-only schema keys, so fall back to the static default."
  [cfg-snap k]
  (if (contains? cfg-snap k)
    (get cfg-snap k)
    (get-in config/config-schema [k :default])))

(defn- root-agent?
  "True when `agent` is the session's ROOT — no parent (axis 1). A nil agent —
   the global/programmatic context — counts as root, and anything unreadable
   does too: this decides whether to WITHHOLD a capability, so an unknown shape
   must not silently disable one.

   STRICT on purpose. Every `:root-only` feature here (memory consolidation,
   skill distillation, self-improve nudges, reactions, FSM, task-wakeup) is a
   per-session SINGLETON, and there is exactly one root per session to drive it.
   A session-sharing subagent (acp-agent) must NOT qualify: it and the root
   would both advance the same session's cadence and double-count it. Sharing is
   about whose TURN it is (axis 2 — see `runtime/dispatched-subagent-state?`),
   not about who owns the session."
  [agent]
  (if (nil? agent)
    true
    (runtime/root-state? (:!state agent))))

(defn feature-state
  "Full resolution for one feature against live config.

   → {:feature :memory/graph :on? true :source :base :implied-by #{}
      :unmet #{} :degraded {} :lifecycle :startup}

   Only the feature's own dependency subgraph is touched — no registry-wide
   sweep — so this is cheap enough for per-turn use. Returns nil for an
   unknown feature."
  [agent fid]
  (let [st (state* #(config/get-config agent %) fid)]
    ;; :root-only is applied HERE rather than in `state*` because it is the one
    ;; input that is not config — it needs the agent, and the resolver is
    ;; deliberately a pure function of (registry, read-fn). Sub-agents share
    ;; their root's session, so a feature the root drives must not also be
    ;; driven by each child.
    (if (and st (:root-only (feature-registry fid)) (not (root-agent? agent)))
      (assoc st :on? false :source :off :root-only-blocked true)
      st)))

(defn feature-state*
  "`feature-state` against a `config/get-config-snapshot` map instead of live
   config. Same algorithm, no I/O."
  [cfg-snap fid]
  (state* #(snap-get cfg-snap %) fid))

(defn on?
  "Is this feature on, accounting for `:implies` and `:requires`?

   The chokepoint that replaces scattered `get-config :enable-…` reads. An
   unmet hard requirement resolves to **off** (fail-safe) — see
   `feature-state` for why, and `off-reason` for a user-facing explanation."
  [agent fid]
  (boolean (:on? (feature-state agent fid))))

(defn on?*
  "Snapshot arity of `on?`.

   `coact_agent` takes one `get-config-snapshot` per turn and reads keys out
   of that map rather than calling `get-config` per key. Without this arity
   those readers would either sit outside the chokepoint — which is how
   `:enable-graph-memory` acquired two contradictory read paths — or force a
   `get-config` per gate per turn on the hot path."
  [cfg-snap fid]
  (boolean (:on? (feature-state* cfg-snap fid))))

(defn- gate-label
  "`enable-x=false` for a boolean gate; `tool-cache-ttl=0` for a numeric one —
   reporting `=false` for a numeric gate would name a value the user never set."
  [agent fid]
  (let [f (feature-registry fid)]
    (when-let [g (gate-of f)]
      (if (:gate-pred f)
        (str (name g) "=" (pr-str (config/get-config agent g)))
        (str (name g) "=false")))))

(defn off-reason
  "Why `fid` is off, as a short fragment to embed in the caller's own error
   sentence — or nil when it is on.

   Returns a reason rather than a finished error map because the call sites it
   replaces use two different shapes (`{:error-message …}` in core/tool,
   `{:error …}` in the command layer) and four different subjects. Message
   shared, shape and subject local:

     (when-let [r (off-reason agent :agents/subagents)]
       {:error (str \"Subagent management is disabled (\" r \").\")})"
  [agent fid]
  (let [{:keys [on? unmet root-only-blocked]} (feature-state agent fid)]
    (when-not on?
      (if root-only-blocked
        "root-only — this agent has a parent, and its root drives the feature"
      ;; An unmet dependency is the more specific answer and takes precedence:
      ;; reporting `enable-x=false` when the user DID set it true, and the real
      ;; cause is a missing requirement, is exactly the confusion §1.4 is about.
        (or (when-let [u (seq unmet)]
              (str "requires "
                   (str/join " and "
                             (for [r u]
                               (if (set? r)
                                 (str/join " or " (map #(str (symbol %)) (sort r)))
                                 (str (symbol r)))))))
            (gate-label agent fid)
            "unavailable")))))

;; ============================================================================
;; Query helpers
;; ============================================================================

(defn requires-restart-key?
  "True when `k` is read once at boot and a change needs a restart.

   Derived from `:lifecycle :startup` minus each feature's `:live-keys`, which
   is the honest statement: restart-ness is not a property of a key, it is a
   property of WHEN its feature is read. A key added to a startup feature now
   inherits the warning automatically, instead of relying on someone
   remembering a per-key flag."
  [k]
  (contains? restart-required-keys k))

(defn feature-doc
  "Registry entry for `fid`, or nil."
  [fid]
  (get feature-registry fid))

(defn family-of
  "Family keyword owning `fid`, or nil."
  [fid]
  (:family (feature-doc fid)))

(defn ambient-key?
  [k]
  (contains? ambient-keys k))

(defn presentation-key?
  "True when `k` belongs to a `:presentation` feature — rendering only, never a
   capability gate."
  [k]
  (boolean (some-> (feature-of-key k) feature-doc :presentation)))

(defn annotate-hit
  "Add `:feature` (and `:family`) to one `config/search-config-keys` hit.

   Hits carry `:key` as a *string* (the search surface is LLM-facing), so this
   keywordizes before the index lookup. Ambient and unclassified keys get no
   `:feature` — absence is meaningful, not an oversight."
  [{:keys [key] :as hit}]
  (let [k (keyword key)]
    (cond-> hit
      (feature-of-key k)
      (assoc :feature (str (symbol (feature-of-key k)))
             :family  (name (family-of (feature-of-key k))))
      ;; Moved here from `search-config-keys` along with the derivation itself:
      ;; config-schema no longer carries a per-key :requires-restart flag, and
      ;; config.clj cannot ask the registry without closing a cycle.
      (requires-restart-key? k)
      (assoc :requires-restart true))))

(defn annotate-hits
  "Map `annotate-hit` over search results.

   Lives here rather than inside `config/search-config-keys` to keep the
   dependency pointing one way: feature → config, never the reverse."
  [hits]
  (mapv annotate-hit hits))

(defn- resolve-family
  "Accept a family as keyword, string, or `\"memory.graph\"`-style prefix and
   return the family keyword, or nil."
  [x]
  (let [s (-> x str (str/replace #"^:" "") (str/split #"[./]") first str/lower-case)]
    (first (filter #(= s (name %)) families))))

(defn set-feature!
  "Turn feature `fid` on or off by writing its gate key through
   `config/set-config!` — same persistence, allowlist and dossier behaviour as
   any other config write. No new storage.

   Returns `{:feature :gate :set :on? :unmet :degraded}` on success, or
   `{:error …}` when the feature has no settable gate. `:set` is the value
   written; `:on?` is the RESOLVED state afterwards, and the two can differ —
   that is exactly what declared requirements do, so callers should report
   `:on?` rather than assuming the write took.

   The guards live here rather than in the command so the TUI and the LLM
   surface cannot drift apart on what is settable."
  [agent fid on?]
  (if-let [f (feature-registry fid)]
    (let [gate (:gate f)]
      (cond
        ;; BEFORE the gateless check: every presentation feature is also
        ;; ungated, so the generic message would win and say the less useful
        ;; thing. §9 Q2 kept `ui` as a family precisely so these keys are
        ;; discoverable — the refusal should then explain what they are.
        (:presentation f)
        {:error (format "%s is presentation-only — rendering, never a capability gate. Set its keys directly with agent-runtime$config."
                        (str (symbol fid)))}

        (nil? gate)
        {:error (format "%s is an ungated grouping — it has no on/off switch. Its knobs are set with agent-runtime$config."
                        (str (symbol fid)))}

        (:proposed f)
        {:error (format "%s is not gateable yet — %s is planned but not in config-schema."
                        (str (symbol fid)) (name gate))}

        (:gate-pred f)
        {:error (format "%s is gated by the numeric key %s (0 = off) — set a value with agent-runtime$config rather than on/off."
                        (str (symbol fid)) (name gate))}

        :else
        (do
          (config/set-config! agent gate (boolean on?))
          (let [st (feature-state agent fid)]
            {:feature  (str (symbol fid))
             :gate     (name gate)
             :set      (boolean on?)
             :on?      (:on? st)
             :unmet    (:unmet st)
             :degraded (:degraded st)}))))
    {:error (format "Unknown feature '%s'." (pr-str fid))}))

(defn set-family!
  "Turn a whole family on or off by writing its master switch.

   Non-destructive by construction: the member gates are untouched, so
   `off` then `on` restores whatever each feature was set to individually,
   rather than flattening them to a single value.

   Returns `{:family :gate :set :features}` where `:features` reports each
   member's RESOLVED state afterwards, or `{:error …}` for an unknown family
   or one with no master switch (`:ui`)."
  [agent fam on?]
  (let [fam (if (keyword? fam) fam (keyword (str fam)))]
    (cond
      (not (contains? family->features fam))
      {:error (format "Unknown family '%s'." (name fam))}

      (nil? (family-gates fam))
      {:error (format "%s has no master switch — it is presentation-only." (name fam))}

      :else
      (let [k (family-gates fam)]
        (config/set-config! agent k (boolean on?))
        {:family   (name fam)
         :gate     (name k)
         :set      (boolean on?)
         :features (vec (for [fid (family->features fam)]
                          {:feature (str (symbol fid))
                           :on?     (:on? (feature-state agent fid))}))}))))

(defn resolve-feature
  "Accept a feature as a keyword or string in either `family/name` or
   `family.name` form, case-insensitively, and return the registry id — or nil
   if it names no feature.

   Both separators are accepted because the two surfaces disagree by nature:
   the registry and EDN callers use `:memory/graph`, while a command line and
   the env-var spelling (`BY_FEATURES=+memory.graph`) read better dotted."
  [x]
  (when (some? x)
    (let [s     (-> x str (str/replace #"^:" "") str/lower-case)
          parts (str/split s #"[./]" 2)]
      (when (= 2 (count parts))
        (let [want (str/join "/" parts)]
          (first (filter #(= want (str (symbol %))) (keys feature-registry))))))))

(defn family-view
  "Everything about one family: each feature with its gate, lifecycle, deps,
   and every member key with its resolved value and schema default.

   This is what turns \"what does the memory feature consist of?\" from an
   unanswerable question into one call. Returns nil for an unknown family.

   `:on?` is the RESOLVED state (gate + `:implies` + `:requires`), which is
   not always the gate's own value — `:source`, `:implied-by` and `:unmet`
   say which. `:gate-value` is the raw gate for comparison."
  ([agent fam] (family-view agent fam nil))
  ([agent fam _opts]
   (when-let [fam (resolve-family fam)]
     {:family   (name fam)
      :features (vec (for [fid (family->features fam)
                           :let [f  (feature-doc fid)
                                 st (feature-state agent fid)]]
                       (cond-> {:feature   (str (symbol fid))
                                :title     (:title f)
                                :on?       (:on? st)
                                :source    (:source st)
                                :lifecycle (:lifecycle f)
                                :doc       (some-> (:doc f) (str/replace #"\s+" " "))
                                :keys      (vec (for [k (:keys f)]
                                                  {:key     (name k)
                                                   :value   (config/get-config agent k)
                                                   :default (get-in config/config-schema
                                                                    [k :default])}))}
                         (:gate f)
                         (assoc :gate       (name (:gate f))
                                :gate-value (when-not (:proposed f)
                                              (config/get-config agent (:gate f))))

                         (:proposed f)
                         (assoc :proposed true
                                :note "gate key not in config-schema yet (planned)")

                         (:presentation f)
                         (assoc :presentation true)

                         (seq (:requires f))
                         (assoc :requires (mapv (fn [r]
                                                  (if (set? r)
                                                    {:any-of (mapv #(str (symbol %)) (sort r))}
                                                    (str (symbol r))))
                                                (:requires f)))

                         (seq (:implies f))
                         (assoc :implies (mapv #(str (symbol %)) (sort (:implies f))))

                         (seq (:requires-partial f))
                         (assoc :requires-partial
                                (into {} (for [[k v] (:requires-partial f)]
                                           [(str (symbol k)) v])))

                         ;; Resolution outcome — why it is off, what is missing,
                         ;; what still works but degraded, whether a restart is
                         ;; owed. This is what lets a caller answer "why isn't
                         ;; graph memory working?" without reading six files.
                         (seq (:implied-by st))
                         (assoc :implied-by (mapv #(str (symbol %)) (sort (:implied-by st))))

                         (seq (:unmet st))
                         (assoc :unmet (mapv (fn [r]
                                               (if (set? r)
                                                 {:any-of (mapv #(str (symbol %)) (sort r))}
                                                 (str (symbol r))))
                                             (:unmet st)))

                         (seq (:degraded st))
                         (assoc :degraded (into {} (for [[k v] (:degraded st)]
                                                     [(str (symbol k)) v]))))))})))

(defn family-summary
  "One line per family: feature count and how many are gated. Cheap overview
   for the `agent-runtime$config` hint and, later, `/feature`."
  []
  (vec (for [fam families
             :let [fids (family->features fam)]]
         {:family   (name fam)
          :features (count fids)
          :gated    (count (filter #(:gate (feature-doc %)) fids))})))
