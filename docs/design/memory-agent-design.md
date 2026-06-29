# Memory-Agent — LLM-Driven Steward of the Layered Memory Stack (CoAct-derived)

> **Status:** Shipped (rev 2 — 2026-05-13). All five implementation phases landed. See §18 for the post-implementation status, decisions made during build, and the deferred follow-ups.
> **rev 3 (2026-06-28) — per-turn essence capture RETIRED.** The `:agent.ask/post` essence-capture hook (which spun a full memory-agent BT loop — 6–8 LLM iterations — on *every* root turn, even a bare "hello") is removed. L2→L3 promotion now runs as cheap **batch consolidation**: an every-N-turns cadence plus a session-end flush, both deterministic and LLM-free on the default heuristic path. The `:op :essence` playbook and `memory$essence-extract` tool survive as a manual/REPL surface. See §10 and §18.5 for the authoritative current behavior; the rev-1/rev-2 essence text below is kept as historical record.
> **Scope:** new agent `components/agent/src/ai/brainyard/agent/common/memory_agent.clj` + helpers namespace; consumes the existing `components/memory` interface; introduces a small `memory$*` tool family registered via `defcommand`.
> **Built on:** `coact_agent.clj` via a custom `run-memory-agent` ask-fn (inherits coact's instruction/BT but not its tool roster — see §18 for why we did not use `coact/run-coact-derived` directly).
> **Replaces / supersedes:** nothing. Augments today's implicit memory ownership (capture sidecar + L2 sweeper + manual `consolidate-l2!` calls) with a single delegate every other agent can reach.
> **Related reading:** `docs/core/memory.md`, `docs/core/agent.md`, `docs/design/research-agent-design.md` (CoAct-derived pattern), `docs/design/rlm-agent-design.md` (small-roster pattern), `docs/reference/SANDBOX-BINDINGS.md`.

## Revision history

- **rev 1 (2026-05-13)** — initial proposal. Establishes the memory-agent as the single LLM-driven steward of `.brainyard/memory/<user-id>.db`. Adds the `memory$*` primitive tool family. Defines per-turn essence capture, cross-session consolidation, purge of orphaned L2 episodes, L3 fact verification, and a stats/health surface that other agents (and the TUI status bar) can call into.
- **rev 2 (2026-05-13)** — implementation complete (Phases 1–5). Twenty `memory$*` primitives, three DSPy signatures (EssenceExtraction, LlmReducer, FactVerification), seven `:op`s end-to-end, write-guard + essence-capture hooks, `/memory` TUI slash-command. Default sub-LM is `claude-code:sonnet` (see §16 Q2). See §18 for full status and deferred items.
- **rev 3 (2026-06-28)** — **retired per-turn essence capture; replaced with batch consolidation.** Removed the `:agent.ask/post` essence-capture hook (per-turn memory-agent BT loop). Added two deterministic hooks in `memory_agent/hooks.clj`: a **consolidation cadence** (`:agent.ask/post`, runs the L2→L3 reducer every `:memory-consolidate-every-n-turns` turns) and a **session-end flush** (`:agent.instance/closed`, a final reduce when a root agent closes). Both gated on `:enable-memory-consolidation` (default false). Replaced config key `:enable-memory-essence` with `:enable-memory-consolidation` + `:memory-consolidate-every-n-turns`. The `memory$essence-extract` tool and `:op :essence` playbook are unchanged (manual/REPL only). See §18.5.

---

## 1. Motivation

The layered memory stack (`docs/core/memory.md`) is mature: L1 working notes, L2 episodic chronicle, L3 semantic facts, plus the `:system` knowledge layer. The **substrate** is solid — `IMemoryStore` is unified, FTS5 + BM25 + RRF recall works, the S0/S1/S2 capture pipeline auto-populates L2 from lifecycle hooks, `memory_audit` lets us reconstruct any past prompt.

What is **missing** is an LLM-driven owner of that substrate. Today:

1. **Capture is automatic but indiscriminate.** Every `:agent.ask/post`, `:agent.tool-use/post`, and `:agent.code-eval/post` writes an L2 episode through the S1 parser. The S2 reducer is a deterministic-prefix heuristic. There is no LLM in the loop deciding *what is actually worth remembering after this turn*. The chronicle grows linearly with iteration count; signal-to-noise drops as sessions get long.
2. **Consolidation is manual.** `(memory/consolidate-l2! manager …)` exists on the interface, but nothing calls it on a schedule. L3 fills only when a developer or operator invokes it. The heuristic reducer is conservative; the optional `:reducer :llm` slot still falls back to heuristic.
3. **Stats are invisible to the agent.** Operators can poke at `~/.brainyard/memory/<user-id>.db` with sqlite3 — but the running agent has no way to ask "how big is my chronicle, how many L3 facts back today's recall, which sessions are dead weight in L2?". There is no `memory$stats` tool.
4. **Sweep is blunt.** `sweep-l2!` tombstones non-kept entries older than `:retention-days` (default 30). It cannot drop episodes scoped to a session-id that no longer exists in the agent registry; it cannot identify L3 facts whose `:sources` were all tombstoned (orphan facts) or whose `confidence` has decayed without re-evidence.
5. **No verification loop on L3.** When the heuristic reducer fires, the resulting fact is taken at face value. There is no later "is this still true?" challenge against fresh evidence. Wrong summaries persist indefinitely with `confidence` unchanged.
6. **Lesson-and-learn is informal.** The brainyard pitch in `CLAUDE.md` is "self-improving agent" — incremental intelligence accumulated across turns and across sessions. The substrate supports it (L3 is indefinite, `:system` is cross-session per user), but no agent is responsible for *deciding what to lift out of a turn as a lesson*. The user has to do it manually with `memory$remember` or live with whatever the capture parser tagged.

**Thesis.** Promote memory ownership to a first-class CoAct agent. Borrow the research-agent pattern — small curated tool roster, durable working area, LLM owns sequencing — and apply it to memory stewardship. Other agents (coact, research, todo, exec, eval, …) reach for the memory-agent via `call-tool` at well-defined moments: end-of-turn essence capture, mid-session "what do we know so far", scheduled consolidation, ad-hoc "forget this fact, it was wrong".

The memory subsystem stops being something agents *bump into through hooks*. It becomes something they *delegate to*.

---

## 2. Design Principles

1. **One steward, many clients.** The memory-agent is the only agent that issues semantic writes to L2/L3 (capture pipeline still auto-fills L2 from hooks — that is operational telemetry, not curated memory). Everyone else reads via recall and delegates writes through `call-tool "memory-agent" {...}`.
2. **The LLM decides what is worth remembering.** Capture from hooks is the floor (we keep auditability). The *essence* of a turn — the irreversible lesson — is selected by an LLM call inside memory-agent, not by a regex over tags. DSPy signatures front each decision point so the prompt is versioned and the output is schema-checked.
3. **Idempotent operations.** Every move the memory-agent makes — write, promote, forget, archive, sweep — is idempotent against the underlying `IMemoryStore`. Re-running a stats turn is free. Re-running a consolidation is free (entries are upsert-by-`:entry_id`).
4. **Audit-respecting.** Forgets are tombstones, never hard deletes. The `memory_audit` table is never touched by the agent. Operators can always answer "why did the agent know X on turn Y" even after the memory-agent has pruned the fact.
5. **Bounded budget per call.** Each memory-agent invocation has a strict iteration cap (default **10**, vs CoAct's 20). The agent's job is bookkeeping, not open-ended reasoning. Long arcs are split into multiple calls.
6. **No clone-self recursion.** `query$clone` and `call-tool "memory-agent"` are excluded from memory-agent's own roster.
7. **Cross-session awareness is explicit.** Most operations are scoped to the current `:session-id` by default. Cross-session moves (e.g. "what did we learn across all sessions about provider X") require an explicit `:scope :user` argument so they cannot happen by accident.
8. **Cheap and quiet by default.** Stats reporting is read-only, uses `count`/`PRAGMA`-style cheap queries, runs without an LLM call. The expensive moves (essence extraction, L3 promotion, fact verification) require the LLM and are explicitly requested.
9. **Resumable.** The memory-agent's working area at `.brainyard/agents/memory-agent/<user-id>/` is the only state of record between invocations. A pending consolidation plan can be resumed; a verification queue can be drained across multiple turns.
10. **The chronicle is the source of truth — until it isn't.** When L3 promotion succeeds, the source L2 episodes are not deleted, but they become eligible for ordinary TTL sweep. Promotion stamps `:sources [{:type :promotion :id …}]` so provenance survives.

---

## 3. Position in the Agent Stack

```
coact-agent  (parent — full BT, sandbox, router, accumulator)
  ├─ explore-agent
  ├─ rlm-agent
  ├─ skill-agent
  ├─ mcp-agent
  ├─ plan-agent / todo-agent / exec-agent / eval-agent / edit-agent
  ├─ research-agent      (orchestrates the six specialists)
  └─ memory-agent        (steward of the memory.db — capture essences, consolidate,
                          purge, verify, report stats; reached via call-tool)
```

| Question shape | Use | Why |
|---|---|---|
| "What did we already learn about X?" | recall (no agent call) | Cross-layer RRF recall is a primitive; do not spawn an agent for a read. |
| "Save this fact: the user prefers Polylith layout" | `call-tool "memory-agent" {:op :remember :content "…"}` | LLM decides which layer, tags, and confidence. |
| "End of turn — capture the essence" | `call-tool "memory-agent" {:op :essence :turn-id N}` | Single short call per turn boundary. |
| "Consolidate the last hour of L2 into L3 facts" | `call-tool "memory-agent" {:op :consolidate :window :recent}` | LLM-grade reducer, beyond the heuristic. |
| "Stats" / health check | `call-tool "memory-agent" {:op :stats}` | Cheap, no LLM. |
| "Purge dead sessions and orphan facts" | `call-tool "memory-agent" {:op :purge :scope :user}` | Curation pass. |
| "I just learned that fact F is wrong — fix it" | `call-tool "memory-agent" {:op :correct :fact-id F :evidence "…"}` | Tombstone + counter-fact. |

Rule: **memory-agent owns writes; other agents own reads**. Recall (`agent.core.memory/recall`, sandbox `recall-note`, the briefing injection through `:stable-keys`) keeps working untouched.

---

## 4. The Memory-Agent Working Area

A small persistent directory mirrors the research-agent dossier pattern.

### 4.1 Directory layout

```
.brainyard/
└── memory-agent/
    ├── INDEX.md                              ; one line per significant op, newest first
    └── <user-id>/
        ├── stats.edn                         ; last-known stats snapshot (see §6)
        ├── essence.log                       ; append-only NDJSON: per-turn essences extracted
        ├── consolidations/<ts>-<slug>.md     ; per-consolidation report (which L2s rolled into which L3s)
        ├── purges/<ts>.edn                   ; per-purge record: counts + entry-ids tombstoned
        ├── verifications/<ts>-<fact-id>.md   ; per-fact verification verdicts
        └── pending/                          ; resumable queues
            ├── verify-queue.edn              ; fact-ids awaiting verification
            └── consolidate-queue.edn         ; session-ids awaiting L2→L3 reduction
```

This directory is *commentary on the database*, never a substitute for it. If it is deleted, no canonical memory is lost — only the agent's audit trail of its own moves. The canonical store remains `.brainyard/memory/<user-id>.db`.

### 4.2 Cross-session resumability

The `pending/` subdirectory makes long arcs resumable: if the user closes the TUI mid-consolidation, the next memory-agent invocation reads `consolidate-queue.edn` and resumes. Sessions are referenced by their UUID — the queue can outlive any specific session.

---

## 5. The `memory$*` Primitive Tool Family

These are thin `defcommand` wrappers over `components/memory/interface.clj`. They are **registered globally** (like `query$llm`) and bound into the memory-agent's sandbox. A subset is also exposed to other agents via the unified tool registry so direct reads work without spawning the memory-agent.

### 5.1 Read primitives (available to all agents)

| Tool | Signature | Wraps | Notes |
|---|---|---|---|
| `memory$recall` | `{:query :limit :weights :scope :match}` → entries | `contextual-recall` | Cross-layer RRF recall; default scope `:session`. |
| `memory$read` | `{:layer :query :limit :include-archived}` → entries | `read-entries` | Single-layer raw read. |
| `memory$stats` | `{:scope :session?}` → map | composite (see §6) | Cheap; no FTS scan. |
| `memory$explain` | `{:session-id :agent-id :turn-id}` → map | `explain` | Reconstruct a past prompt's recall. |
| `memory$keywords` | `{:text}` → vec | `extract-keywords` | For tag derivation in callers. |

### 5.2 Write primitives (memory-agent's roster only)

| Tool | Signature | Wraps | Notes |
|---|---|---|---|
| `memory$write` | `{:layer :entry}` → entry-id | `write-entry` | Validates `:kind`/`:confidence`/`:tags` shape. |
| `memory$promote` | `{:entry-id :from :to}` → entry-id | `promote-entry` | Stamps `:sources` chain. |
| `memory$forget` | `{:layer :entry-id :reason}` → :ok | `forget-entry` | Tombstone, never DELETE. |
| `memory$keep!` | `{:layer :entry-id}` → :ok | `keep!` | Pin against TTL sweep. |
| `memory$archive!` | `{:layer :entry-id}` → :ok | `archive!` | Exclude from default recall. |
| `memory$consolidate` | `{:scope :window :session-id?}` → report | LLM-driven reducer (§7.3) | Replaces heuristic for memory-agent calls. |
| `memory$sweep-l2` | `{:retention-days :scope}` → counts | `sweep-l2!` | Plus orphan-session filter (§7.4). |
| `memory$verify-fact` | `{:fact-id :evidence?}` → verdict | LLM call (§7.5) | Updates `:confidence`; can tombstone. |

### 5.3 Working-area primitives (memory-agent only)

| Tool | Signature | Purpose |
|---|---|---|
| `memory$state-read` | `{:slot}` → edn | Read `stats.edn`, `pending/*.edn`. |
| `memory$state-write` | `{:slot :content}` → :ok | Persist working state. |
| `memory$essence-append` | `{:turn-id :essence-edn}` → :ok | Append to `essence.log`. |

All write primitives go through a single `:agent.tool-use/pre` hook handler that enforces "memory$write/promote/forget/consolidate/sweep/verify are only callable from inside memory-agent" — other agents calling them directly get rejected with a redirect to `call-tool "memory-agent"`.

---

## 6. The Stats Surface

`memory$stats` returns a flat map suitable for both LLM consumption (memory-agent reasoning) and UI consumption (a TUI status bar widget).

```clojure
{:db {:path          ".brainyard/memory/jake.db"
      :bytes         42_318_592
      :wal-bytes     1_245_184
      :page-count    10_336
      :page-size     4096}
 :l1 {:count                 47
      :bytes                 18_240
      :quota-keys            100
      :quota-bytes           51_200
      :session-id            "…"
      :pinned                3}
 :l2 {:total                 12_847
      :current-session       312
      :sessions-known        37
      :sessions-orphan       4         ; in DB but not in agent-registry
      :keep-flagged          22
      :archived              5
      :tombstoned            411
      :oldest-at             #inst "2026-04-13T…"
      :newest-at             #inst "2026-05-13T…"
      :bytes-fts             8_512_000}
 :l3 {:total                 612
      :by-kind               {:fact 503 :observation 78 :user-context 31}
      :confidence-buckets    {:high 421 :medium 153 :low 38}   ; >=0.8 / 0.5..0.8 / <0.5
      :stale                 27         ; last-accessed > 60d ago
      :orphan                3          ; all :sources tombstoned
      :archived              12}
 :system {:count 14 :bytes 32_000}
 :capture {:running?   true
           :backlog    0
           :critical?  0
           :reducer    :heuristic}
 :audit  {:rows 84_120 :bytes 2_400_000}
 :health {:status :ok                  ; :ok | :warn | :critical
          :warnings []                  ; e.g. [:db-large :stale-l3 :sweep-overdue]
          :last-sweep-at #inst "…"
          :last-consolidate-at #inst "…"}}
```

All numbers come from cheap counting queries; no FTS scans. The `:health` block is computed against thresholds in `memory-agent`'s config (default: warn when `:db.bytes > 256MiB`, when `:l3.stale > 100`, when `:last-sweep-at` older than 7 days).

The TUI can call `memory$stats` directly without spawning the agent — this is a read primitive (§5.1).

---

## 7. Operations Catalogue

Each operation is an `:op` value the memory-agent dispatches on inside its CoAct loop. The LLM is given the operation name and arguments in the initial prompt; its first move is usually to call `memory$stats`, then take action.

### 7.1 `:op :essence` — End-of-turn essence capture

**When called:** From `:agent.ask/post` of any agent whose config opts in (`:memory.essence/enabled? true`). Typically the root coact-agent and the research-agent.

**Inputs:** `:session-id`, `:agent-id`, `:turn-id`, `:total-turns`, optional `:hint` (caller's summary of what just happened).

**LLM decision:** Given the just-finished turn's messages + a short window of L2 episodes from this turn, the LLM emits zero or more **essences**: short, generalizable statements worth remembering beyond this turn. Each essence carries `:kind` (`:fact` | `:observation` | `:user-context`), `:content`, `:tags`, `:confidence`, and pointers (`:sources`) back to the L2 episode ids.

**Effect:** Each essence is written via `memory$write` to **L2 with `keep_flag=1`** (so it survives the 30-day sweep), AND appended to `essence.log`. Essences with `:confidence >= 0.8` and `:kind :fact` are also immediately promoted to L3 with `memory$promote` (provenance preserved). Lower-confidence essences are queued in `pending/consolidate-queue.edn` for later batch consolidation.

**Bounded:** at most **3** essences per turn (LLM prompt enforces the limit). A turn that "produces no essence" is the common case — the LLM returns an empty vector and we just append `{:turn-id N :essences []}` to the log.

**Why this design:** Capture pipeline keeps L2 truthful and complete (every tool call is logged). Essence extraction is a separate, low-rate, LLM-graded *lifting* of what is actually worth carrying forward. The two signals do not conflict; essences just have higher `keep_flag` and higher `:confidence`.

### 7.2 `:op :remember` — Explicit fact registration

**When called:** Any other agent (or the user via slash-command) explicitly wants to save a fact. Common from `edit-agent` after a successful safe edit ("we now route exceptions through X"), from `eval-agent` after a verdict ("approach Y did not work because Z").

**Inputs:** `:content`, optional `:kind`, `:tags`, `:confidence`, `:scope :session | :user`.

**LLM decision:** Validate the content is fact-shaped (not a transient observation), choose the right layer (typically L3 for `:scope :user`, L2-with-keep for `:scope :session`), pick or refine the tags, dedupe against existing facts via `memory$recall`.

**Effect:** `memory$write` (and `memory$promote` if L3). Returns the entry-id.

### 7.3 `:op :consolidate` — LLM-driven L2 → L3 reduction

**When called:** On a schedule (memory-agent scheduled task; see §11), on demand, or when the `pending/consolidate-queue.edn` exceeds a threshold.

**Inputs:** `:scope :session | :user`, `:window {:hours N} | :recent | :session-id "…"`.

**LLM decision:** Given a windowed slice of L2 episodes, the LLM clusters them by tag-set + topic and emits a small set of distilled facts. This is the **LLM reducer slot** that the existing `core/capture/reducer.clj` documents but does not yet implement (it warns and falls back to heuristic).

**Effect:** New L3 facts written with `:sources` chained to all contributing L2 episodes. Source episodes get `keep_flag=1` (the heuristic reducer already does this; we preserve the rule). A consolidation report is written to `consolidations/<ts>-<slug>.md` listing source episode ids → output fact ids.

**Replaces:** The default heuristic reducer remains the fallback for the auto-capture pipeline. Memory-agent calls do not flow through it.

### 7.4 `:op :purge` — Curate orphans and stale entries

**When called:** Scheduled (weekly default) or on demand.

**Inputs:** `:scope :session | :user`, `:dry-run? true|false`.

**Three sub-passes:**

1. **L2 orphan-session purge.** Compute `sessions-in-db = SELECT DISTINCT session_id FROM episodes WHERE keep_flag=0`. Cross-reference with the agent registry's known sessions (`(agent.core.session/list-known-session-ids)`) and with the persistent session store (`agent-tui-persist`'s session directory). Sessions present in the DB but unknown to either registry are *orphan sessions* — their L2 episodes are tombstoned. Episodes with `keep_flag=1` survive regardless (they were essences, or explicitly pinned).
2. **L2 TTL sweep.** Standard `sweep-l2!` for non-kept, non-orphan, expired entries. No LLM call.
3. **L3 orphan-fact and stale-fact pass.** Facts whose `:sources` are *all* tombstoned become `:orphan`. Facts whose `:last-accessed` is > 60 days ago and `:confidence < 0.5` become `:stale`. The LLM is consulted per orphan/stale fact only if `:dry-run?` is false and the count is below a budget cap (default 10/turn): given the fact + a fresh recall on its content, the LLM decides `:keep | :archive | :tombstone`. Wrong summaries are tombstoned with a reason recorded in `purges/<ts>.edn`.

**Effect:** Tombstones (never hard deletes). A per-purge EDN record. The `memory_audit` table is untouched.

**Cap:** No purge run touches more than 500 entries total across passes (configurable). Larger runs are split across invocations.

### 7.5 `:op :verify-fact` — Challenge an L3 fact

**When called:** From the `:purge` stale-fact pass, from the `:correct` op below, or scheduled (drain `verify-queue.edn` N at a time).

**Inputs:** `:fact-id`, optional `:evidence` (free text or pointer).

**LLM decision:** Given the fact + a fresh `memory$recall` on its content + the evidence (if any), classify as `:still-true` | `:refine` | `:wrong`. Refinements rewrite `:content`, bump `:confidence`, and append a verification record. Wrong facts are tombstoned and replaced with a counter-fact whose `:sources` cite both the original fact (as `{:type :supersedes :id …}`) and the new evidence.

**Effect:** `memory$write` (counter-fact) and/or `memory$forget` and/or in-place update via `memory$write` (upsert by `:entry_id`). A verdict file in `verifications/`.

### 7.6 `:op :correct` — User-initiated correction

**When called:** User says "no, that's wrong — we don't deploy via X anymore". A slash-command or sandbox helper (`/memory correct …`) dispatches here.

**Inputs:** `:fact-id` (or `:query` to locate the offending fact), `:evidence` (the corrected truth).

**Effect:** Internally just `:op :verify-fact` with the user's evidence treated as authoritative. The user's correction is itself written as a new L3 fact with high confidence and `:sources [{:type :user-correction :session-id …}]`.

### 7.7 `:op :stats` — Read-only health report

**When called:** From the TUI status bar, from operator slash-commands, from other agents who want to budget their context aggressively (e.g. "is L3 large enough to warrant raising recall weights?").

**Inputs:** `:scope :session | :user`, `:format :map | :markdown`.

**Effect:** Returns the §6 map, or a rendered markdown summary, plus updates `stats.edn` with the snapshot.

**No LLM call.** Returns synchronously from the memory-agent or directly from the `memory$stats` primitive.

---

## 8. DSPy Signatures

Three signatures front the LLM decisions. They live in `components/agent/src/ai/brainyard/agent/common/memory_agent/signatures.clj` and are loaded into the memory-agent's BT.

### 8.1 `EssenceExtraction`

```clojure
(defsignature EssenceExtraction
  "From a just-finished agent turn, identify zero to three short statements
   worth remembering beyond this turn. Prefer facts about the user, the
   project, or generalizable lessons over tool-by-tool play-by-play.
   Return an empty vector if nothing is essence-worthy."
  {:inputs
   {:turn-summary    [:string {:desc "What just happened, in one paragraph"}]
    :turn-messages   [:string {:desc "The last few messages of the turn"}]
    :recent-episodes [:string {:desc "Last ~20 L2 episodes, formatted"}]
    :user-id         [:string]}
   :outputs
   {:essences
    [:vector
     [:map
      [:kind       [:enum :fact :observation :user-context]]
      [:content    :string]
      [:tags       [:vector :string]]
      [:confidence [:double {:min 0.0 :max 1.0}]]
      [:source-ids [:vector :string]]
      [:rationale  [:string {:desc "Why this is worth remembering"}]]]]}})
```

### 8.2 `FactVerification`

```clojure
(defsignature FactVerification
  "Decide whether a stored L3 fact is still true given fresh evidence.
   You may refine the wording, mark it wrong (we will tombstone), or
   confirm it as-is. Be conservative — confirmation requires that the
   evidence directly supports the fact; absence of evidence is not
   refutation."
  {:inputs
   {:fact          [:map [:id :string] [:content :string]
                         [:confidence :double] [:tags [:vector :string]]]
    :fresh-recall  [:string {:desc "Cross-layer recall on the fact's content"}]
    :evidence      [:maybe :string]}
   :outputs
   {:verdict       [:enum :still-true :refine :wrong]
    :refined-content [:maybe :string]
    :new-confidence  [:double {:min 0.0 :max 1.0}]
    :rationale     :string}})
```

### 8.3 `LlmReducer`

```clojure
(defsignature LlmReducer
  "Cluster a windowed slice of L2 episodes into a small set of distilled
   L3 facts. Each fact must cite its source episode ids. Aim for high
   information density — one fact per real-world topic, not one fact
   per episode."
  {:inputs
   {:episodes     [:vector
                   [:map
                    [:id :string]
                    [:content :string]
                    [:tags [:vector :string]]
                    [:created-at :string]]]
    :window-desc  :string
    :existing-l3-hits [:string {:desc "Existing L3 facts that recall on this window"}]}
   :outputs
   {:facts
    [:vector
     [:map
      [:content :string]
      [:kind [:enum :fact :observation]]
      [:tags [:vector :string]]
      [:confidence [:double {:min 0.0 :max 1.0}]]
      [:source-episode-ids [:vector :string]]
      [:supersedes-fact-ids [:vector :string]]]]}})
```

All three are invoked through `query$llm` inside the sandbox; outputs are Malli-validated before reaching `memory$write`.

---

## 9. The CoAct Loop — Memory-Agent's Instruction & Roster

Memory-agent is a thin CoAct configuration:

```clojure
(defagent memory-agent
  {:description "Steward of the layered memory stack. Capture essences,
                 consolidate L2 → L3, purge orphans and stale facts,
                 verify and correct, report stats."
   :input-signature MemoryAgentInputs
   :iteration-cap 10
   :tool-roster
   ["memory$stats" "memory$recall" "memory$read" "memory$explain"
    "memory$write" "memory$promote" "memory$forget" "memory$keep!"
    "memory$archive!" "memory$consolidate" "memory$sweep-l2"
    "memory$verify-fact" "memory$state-read" "memory$state-write"
    "memory$essence-append" "query$llm"]
   :forbidden #{"query$clone" "call-tool"}     ;; no spawning, no self-recursion
   :instruction-body
   "You are the memory steward. You receive an :op and arguments.
    Your job:
      1. Run memory$stats first to ground yourself.
      2. Carry out exactly the requested operation; do not freelance.
      3. Use the appropriate DSPy signature for any LLM decision —
         do not hand-write JSON.
      4. Idempotency matters. If a fact already exists with the same
         content, do not write a duplicate; bump its confidence instead.
      5. Tombstones are permanent in spirit (we keep them for audit
         reproducibility). Do not chain forget → write of the same content.
      6. Stop when the requested op is done. Do not invent follow-ups.
        :essence    → emit 0–3 essences, write/promote, append log, stop.
        :consolidate → run LlmReducer over window, write facts, report, stop.
        :purge       → three passes (§7.4) with caps, write record, stop.
        :verify-fact → one fact, one verdict, write verification, stop.
        :stats       → call memory$stats, return, stop.
    Output: a structured summary map (no prose padding) that the caller
    can attach to its own audit."})
```

The agent's input signature accepts a discriminated union over `:op`. Internally the dispatch is a `case` in the agent's preflight that picks the matching prompt fragment and the matching DSPy signature.

> **As-built:** The pseudo-`defagent` above is illustrative. The shipped `defagent memory-agent`
> (in `common/memory_agent.clj`) differs in three ways verified against code:
> 1. **Roster is 20 tools, not 16.** The shipped `memory-agent-tools` adds the three signature
>    wrappers (`memory$essence-extract`, `memory$llm-consolidate`, `memory$verify-fact`) and the
>    deterministic `memory$purge-plan` planner. See the §18.1 table for the authoritative roster.
> 2. **No `:forbidden` key.** `call-tool`/`query$clone` are excluded by **omission** from the roster,
>    not via a `:forbidden` set; the write-guard hook enforces gating from the other direction.
> 3. **Custom `run-memory-agent` ask-fn**, not `run-coact-derived` — it inherits coact's
>    `:instruction`/`:tool-context`/`:bt-factory` but deliberately does NOT merge coact's tool roster
>    (§18.2 decision 1). Default `:max-iterations` is 10; `:sub-lm-config` defaults to `claude-code:sonnet`.

---

## 10. Hooks Integration

Memory-agent does not register *new* hook keys. It is a *consumer* of existing ones, self-installed at namespace load in `memory_agent/hooks.clj`.

> **rev 3 (2026-06-28):** the per-turn essence-capture hook described in §10.1 below is **retired**. The current shipped hooks are the **write-guard** (§9) plus the two **batch-consolidation** hooks described in §10.0. The §10.1/§10.2 text is kept as the rev-1/rev-2 historical record of the original (now-removed) design.

### 10.0 Batch consolidation (rev 3 — current)

Two deterministic hooks, both gated on `:enable-memory-consolidation` (default false; opt-in per agent-type, root-only, never memory-agent itself):

- **Consolidation cadence** — `:agent.ask/post`, id `::consolidation-cadence`. Increments a per-session turn counter (a plain atom — *no LLM*) and, every `:memory-consolidate-every-n-turns` turns (default 12), fire-and-forget runs the memory pipeline's L2→L3 reducer over the session: `mem/consolidate-graph!` (community / CR-MEM-24) when `:enable-graph-memory` is on, else the LLM-free heuristic `mem/consolidate-l2!`.
- **Session-end flush** — `:agent.instance/closed`, id `::session-end-flush`. When a root agent instance closes (`/quit`, EOF, `/agent close`), runs one final reduce so a session ending between cadence boundaries still promotes its tail of episodes. Fires *synchronously* (the event fires before `mem/stop-capture!`, so the manager is still live) but is bounded by a 10s timeout so a slow community/LLM reduce can't wedge shutdown. Clears the session's turn counter.

Why this shape: per-turn essence extraction (§10.1) wrapped a *single* sub-LM call (`memory$essence-extract`) inside a full memory-agent BT loop, costing 6–8 main-LM iterations **every** turn — even a "hello" — and overlapping the L2→L3 reducer the pipeline already had. Batching the reduce and triggering it deterministically removes both the per-turn cost and the redundancy. The `memory$essence-extract` signature and the `:op :essence` playbook remain available for manual/REPL use.

### 10.1 Turn-boundary essence capture *(retired rev 3 — historical)*

Registered handler on `:agent.ask/post` with `:source :memory-agent.essence`:

```clojure
(register-hook!
  :agent.ask/post :memory-agent/essence
  (fn [{:keys [agent result]}]
    (when (essence-eligible? agent)
      (future                                      ;; async — never blocks the parent agent
        (call-tool "memory-agent"
                   {:op :essence
                    :session-id (-> agent :session :session-id)
                    :agent-id   (:agent-id agent)
                    :turn-id    (:turn-id result)
                    :total-turns (:total-turns result)
                    :hint       (:summary result)}))))
  :priority 100
  :source :memory-agent)
```

The handler fired-and-forgot — essence extraction running ~2s should never block the user's next turn. **Removed in rev 3** (see §10.0): the BT-loop-per-turn cost outweighed the benefit and duplicated the batch reducer.

### 10.2 Other hook consumers *(historical / aspirational)*

- `:agent.instance/closed` → **shipped in rev 3** as the session-end consolidation flush (§10.0). (The original sketch named `:agent.session/closed`, but that event fires only on session *deletion*, not a normal quit — sessions persist for resume — so the flush hangs off the agent-instance close instead.)
- `:agent.compaction/post` → after a CoAct context compaction, queue a consolidation for the compacted window so distilled lessons survive even though the raw messages were truncated. *(Not built.)*
- `:agent/exception` → write the exception + tool-call snapshot as an L2 episode with `keep_flag=1` and `:tags #{"event:exception"}` so postmortems can recall failure modes. *(Not built; the always-on capture pipeline already records tool/eval errors.)*

---

## 11. Scheduled Tasks

> **Status (rev 2):** **Not yet wired.** The on-demand path through `(call-tool :memory-agent {:op :consolidate ...})` and `... {:op :purge ...}` is fully functional; the auto-run loop below is deferred. See §18 for the rationale and the planned shape of the followup.

Two scheduled tasks (via the existing `schedule` skill) live under `.brainyard/agents/memory-agent/<user-id>/schedule.edn`. They invoke memory-agent through `call-tool` like any other caller.

- **`memory-consolidate-recent`** — hourly, scope `:session`, window `{:hours 1}`. Drains `pending/consolidate-queue.edn`. No-op when queue is empty (cheap).
- **`memory-purge-weekly`** — weekly, scope `:user`, dry-run on the first three runs (operator inspects `purges/*.edn` before opting into live tombstoning). Cap 500 entries.

Both tasks are user-editable via the standard scheduled-tasks UI; defaults ship with the agent.

---

## 12. Termination & Output Shape

Every memory-agent invocation terminates in one of:

- `:ok` — operation completed; `:result` carries the structured summary.
- `:no-op` — operation had nothing to do (empty queue, no episodes in window, fact already verified within freshness window). `:result :reason` names which.
- `:error` — schema validation failed, DB write rejected, or the LLM call exceeded retry budget. `:result :exception` carries the throwable.

Callers always get a map shaped:

```clojure
{:status   :ok | :no-op | :error
 :op       :essence | :consolidate | :purge | :stats | …
 :scope    :session | :user
 :counts   {:written N :promoted N :tombstoned N :verified N}
 :artifact "<path to .brainyard/agents/memory-agent/.../…>"     ;; when applicable
 :duration-ms N
 :iterations  N}
```

This shape is fixed regardless of operation, so other agents can pattern-match without dispatching on `:op`.

---

## 13. Interactions With Existing Subsystems

### 13.1 With the auto-capture sidecar

The S0/S1/S2 capture pipeline keeps running. Memory-agent does **not** replace it:

- S0/S1 continue to populate L2 from hooks — this is the *floor* of memory, never lossy.
- S2's heuristic reducer continues to fire on its tag-window groups for the *unattended* path.
- Memory-agent's `:op :consolidate` is the *attended* path — invoked explicitly, uses the LLM, produces higher-quality L3.

Both paths write through the same `IMemoryStore` and can coexist. When both produce a fact about the same topic in the same window, `memory$write`'s upsert-by-`:entry_id` keeps a single row; the LLM reducer's output supersedes the heuristic's because the LLM reducer carries `:supersedes-fact-ids`.

### 13.2 With `agent-tui-persist`

`agent-tui-persist` knows which sessions have ever existed on disk (`<project>/.brainyard/sessions/<id>/`). Memory-agent's purge pass cross-references that directory plus the in-memory agent registry to detect orphan sessions. A session is "orphan" only if it appears in neither — sessions on disk but not in the registry (e.g. a tab the user closed but might reopen) are *not* orphan.

### 13.3 With `memory_audit`

Memory-agent never writes to `memory_audit`. Recall writes its own audit rows via the existing pipeline; `:op :explain` only reads. This keeps audit reproducibility independent of curation decisions — operators can always answer "why did the agent know X on turn Y" even after memory-agent has tombstoned X.

### 13.4 With `:system` knowledge sections

The `:system` layer (skill instructions, user-pinned context) is **off-limits** to memory-agent's writes by default. It is owned by `agent.core.context/set-knowledge-section!` and the skill/plugin lifecycle. Memory-agent can *read* `:system` for grounding (e.g. when deciding essence relevance) but cannot tombstone or promote into/out of it.

If a future revision wants to let memory-agent curate `:system` (e.g. archive a no-longer-loaded skill's context), that requires an explicit `:scope :system` argument and a separate config flag (`:memory.system/writable? false` by default).

---

## 14. Sample Sessions

### 14.1 End-of-turn essence

```
[coact-agent/root finishes a turn at total-turns=42]
  → :agent.ask/post fires
  → memory-agent invoked async: {:op :essence :turn-id 42 ...}

memory-agent CoAct loop:
  i0: (memory$stats {:scope :session})
      → :l2.current-session 312, :l3.total 612, …
  i1: (memory$read {:layer :l2 :session-id "…" :limit 20 :order :recent})
      → [20 most-recent episodes]
  i2: (query$llm EssenceExtraction { …turn-summary, recent-episodes, … })
      → {:essences [{:kind :user-context
                     :content "User is migrating auth middleware due to compliance, not tech debt"
                     :tags ["topic:auth" "topic:compliance" "session:42"]
                     :confidence 0.85
                     :source-ids ["ep-281" "ep-287"]
                     :rationale "User said this explicitly; project memory candidate"}]}
  i3: (memory$write :l2 {…essence…, :keep true})    → "ess-091"
  i4: (memory$promote {:entry-id "ess-091" :from :l2 :to :l3})  → "fact-218"
  i5: (memory$essence-append {:turn-id 42 :essences [...]})
  i6: stop → returns {:status :ok :op :essence :counts {:written 1 :promoted 1}}
```

### 14.2 Stats query from the TUI

```
[TUI status bar refresh; no agent spawn]
  (memory$stats {:scope :session :format :markdown})
  → "L1 47/100 (18 KB), L2 312/12.8K, L3 612 facts (421 high-conf), DB 41 MB"
```

### 14.3 User-initiated correction

```
User: "/memory correct The auth migration is compliance-driven, not session-token cleanup"

  → call-tool "memory-agent" {:op :correct :query "auth migration" :evidence "..."}

memory-agent:
  i0: memory$recall {:query "auth migration"} → finds fact-218 (compliance) and fact-201 (session cleanup)
  i1: query$llm FactVerification on fact-201 with user evidence
      → {:verdict :wrong, :rationale "Superseded by user correction"}
  i2: memory$forget {:layer :l3 :entry-id "fact-201" :reason "user-correction"}
  i3: memory$write {:layer :l3 :entry {:content "…compliance…"
                                       :sources [{:type :supersedes :id "fact-201"}
                                                 {:type :user-correction :session-id "…"}]
                                       :confidence 0.95
                                       :keep true}}
  i4: stop → {:status :ok :op :correct :counts {:written 1 :tombstoned 1}}
```

### 14.4 Weekly purge (dry-run)

```
[scheduled task fires]
  → call-tool "memory-agent" {:op :purge :scope :user :dry-run? true}

memory-agent:
  i0: memory$stats → :l2.sessions-orphan 4, :l3.orphan 3, :l3.stale 27
  i1: memory$read :l2 + cross-ref with persist sessions → list of 4 orphan session-ids
  i2: memory$read :l3 with archived/tombstoned filter → list of orphan/stale fact-ids
  i3: for each stale fact (cap 10): query$llm FactVerification with fresh recall, dry-run
  i4: memory$state-write {:slot "purges/2026-05-13T03-00.edn" :content {…proposed actions…}}
  i5: stop → {:status :ok :op :purge :counts {:would-tombstone 87 :would-archive 12} :artifact "…"}
```

Operator inspects the artifact, then re-runs with `:dry-run? false` if happy.

---

## 15. Implementation Plan

Five phases, each shippable independently. Each phase ends with tests under `components/agent/test/ai/brainyard/agent/common/memory_agent/` and at least one passing eval against the existing `memory` component tests.

**Phase 1 — `memory$*` primitives** (no agent yet). **✓ Shipped (rev 2).**
Wire `defcommand` wrappers around the existing `components/memory/interface.clj` functions. Add the `memory$stats` composite. Register the `:agent.tool-use/pre` guard that gates the write primitives. *Outcome:* every existing agent can already call `memory$stats` and `memory$recall` by name.

**Phase 2 — `memory-agent` skeleton + `:op :stats` and `:op :remember`.** **✓ Shipped (rev 2).**
`defagent memory-agent` with the roster above; CoAct preflight dispatches on `:op`; implements the two simplest ops. *Outcome:* `(call-tool "memory-agent" {:op :stats})` works end-to-end.

**Phase 3 — Essence capture + hook wiring.** **✓ Shipped (rev 2).**
Add `EssenceExtraction` signature, `:op :essence` op, the `:agent.ask/post` handler, and the `essence.log` working-area file. Default-enable for root coact-agent only at first; opt-in elsewhere. *Outcome:* end of every coact turn writes 0–3 essences to L2-keep + L3 if confidence high.

**Phase 4 — Consolidation + purge.** **✓ Shipped (rev 2), with deferrals.**
Add `LlmReducer` and the `:op :consolidate`/`:op :purge` ops, the orphan-session detection, the L3 orphan/stale pass, and the two scheduled tasks (initially dry-run). *Outcome:* weekly purge produces an actionable report; on-demand consolidation produces higher-quality L3 than the heuristic.
- *Deferred:* the two scheduled tasks (§11) — auto-run loop not yet built. On-demand consolidate/purge works.
- *Deferred:* L3 orphan-fact detection in `memory$purge-plan` — currently returns `[]`. The `:op :verify-fact` path via fresh recall is the more reliable signal anyway.
- *Deferred:* per-run artifact files (`consolidations/<ts>-<slug>.md`, `purges/<ts>.edn`) — the LLM stashes the structured summary in its `:answer` block; a dedicated `memory$state-write` slot is the natural followup.

**Phase 5 — Verification + correction.** **✓ Shipped (rev 2), with deferrals.**
Add `FactVerification`, `:op :verify-fact`, `:op :correct`, and the `/memory` slash-command. Wire the `verify-queue.edn` drain into the existing scheduled-tasks framework. *Outcome:* facts age gracefully; user corrections are first-class memory writes.
- *Deferred:* `pending/verify-queue.edn` drain — the queue is populated by `:op :purge`'s dry-run path but no scheduled drainer is wired (blocked on the §11 scheduler).
- *Deferred:* `verifications/<ts>-<fact-id>.md` artifact files — same as the Phase 4 artifact-file deferral.
- *Deferred:* `:memory-agent/op-completed` observability hook (Open Q5) — not added. Easy to layer on later.

After phase 5: revisit defaults (essence-capture for more agents, live purge), measure DB size/L3 quality over a multi-week internal dogfood.

---

## 16. Open Questions

1. **Async essence capture vs. blocking.** Phase 3 dispatches essence extraction asynchronously via `future`. If the user starts the next turn before the LLM call returns, the essence still lands eventually but may reference a stale `:total-turns`. Acceptable? Or worth offering a `:memory.essence/mode :sync | :async` knob?
   > **Resolved (rev 2):** shipped async-only via `future` in `essence-capture-handler`. The stale `:total-turns` risk hasn't surfaced in practice — memory-agent loads its own L2 window so the `:total-turns` argument is only telemetry, not load-bearing for the essence content. The `:memory.essence/mode` knob is **not implemented**; a flag could be added if needed.
2. **Cost budget.** Each turn potentially adds one extra LLM call (essence extraction). For long sessions with cheap models that is fine; with Opus-class reasoning that is non-trivial. Should we default to a cheap sub-LLM (`clj-llm` already supports sub-LLM dispatch helpers) for essence work? Probably yes — a haiku-class model is plenty for "is this worth remembering".
   > **Resolved (rev 2):** shipped with `default-lm-str = "claude-code:sonnet"` set on memory-agent's `:sub-lm-config`. This is one notch above the original haiku-class suggestion — sonnet's stronger structured-output reliability paid for itself across EssenceExtraction / LlmReducer / FactVerification. Override with `:sub-lm-config "<model>"` in the call args.
3. **L3 dedupe strategy.** Today's `entry_id` unique constraint relies on the caller computing a stable id. The LLM-driven path needs a content-hash-based id strategy (e.g. SHA-256 of normalized content) so repeated extractions of the same essence converge on one row. Sketch: `(entry-id-for :l3 :content "…")` helper that hashes after FTS-style normalization.
   > **Resolved (rev 2):** `entry-id-for` shipped in `memory_agent/commands.clj` — SHA-256 of normalized content (lowercase, punctuation stripped, whitespace collapsed) → `"l3/<16-hex>"`. `memory$write` auto-mints when `:id` is omitted on L3 writes. Discovered during build that the underlying store does `INSERT` not `INSERT OR REPLACE`, so true duplicates fall back to a read-by-id path — the caller still sees a stable id either way.
4. **Cross-user memory.** Today's DB path is per-user (`.brainyard/memory/<user-id>.db`). Should team-level shared facts (project conventions everyone benefits from) live somewhere shared? Out of scope for v1, but the design must not preclude it — keeping `:user-id` on every entry already supports this.
   > **Status (rev 2):** unchanged — still out of scope. v1 design honors the door-open posture.
5. **Memory-agent observability.** Should the agent fire its own custom hook (`:memory-agent/op-completed`) so the observability stack (`docs/design/observability.md`) can chart essence-rate, consolidation throughput, purge volume? Likely yes, but trivially additive — defer to phase 5.
   > **Resolved (rev 2):** **deferred again.** Not yet added. Each `:op` invocation already produces a structured `{:status :op :counts ...}` block in `:answer`, which is enough to derive metrics from logs. A custom hook is trivially additive when a metrics consumer needs push-style notification.
6. **Re-entrancy.** Two parallel agents both finishing a turn simultaneously will both spawn memory-agent. The SQLite WAL handles concurrent writes correctly, but the `pending/*.edn` files are not lock-protected. A simple file lock under `.brainyard/agents/memory-agent/<user-id>/.lock` is probably sufficient; revisit if contention shows up.
   > **Status (rev 2):** **no lock yet.** Re-entrancy hasn't surfaced as a problem in single-user workflows. The `pending/*.edn` slots use whole-file replacement (`spit` in `wa/write-slot!`), so concurrent writes lose the older write but don't corrupt the file. Revisit when multi-user / multi-session scenarios start writing `pending/` concurrently.

---

## 17. Summary

Memory-agent is the missing curator of an otherwise complete memory stack. It takes the LLM out of the implicit position (everyone's hooks scribble into L2; nobody decides what is worth lifting to L3) and into an explicit one (a single CoAct-derived steward that other agents reach for at well-defined moments). It is small — one agent file, one signatures namespace, a handful of `memory$*` primitive wrappers, a working directory of EDN/NDJSON commentary. It composes with what is already there: capture sidecar still runs, audit table is untouched, recall path is unchanged, `:system` layer is off-limits.

What it adds is *intent*. Every essence written, every fact promoted, every orphan tombstoned now has a turn-id, an agent-id, and a rationale you can read in a markdown file. The memory stops being a side effect and starts being an artifact the agent — and the user — can reason about.

---

## 18. Implementation Status (rev 2 — 2026-05-13)

All five phases shipped end-to-end. Test coverage: 135 tests / 900 assertions across the six `memory_agent/*_test.clj` namespaces plus the sibling-agent regression set. `clj -M:poly check` clean.

### 18.1 Shipped surface

**Files (all under `components/agent/`):**

- `src/ai/brainyard/agent/common/memory_agent.clj` — `defagent memory-agent` + custom `run-memory-agent` ask-fn + 20-tool roster (`memory-agent-tools`).
- `src/ai/brainyard/agent/common/memory_agent/commands.clj` — every `memory$*` primitive.
- `src/ai/brainyard/agent/common/memory_agent/signatures.clj` — `EssenceExtraction`, `LlmReducer`, `FactVerification`.
- `src/ai/brainyard/agent/common/memory_agent/instruction.clj` — master instruction + tool-context.
- `src/ai/brainyard/agent/common/memory_agent/working_area.clj` — paths, EDN slot I/O, NDJSON essence-log append.
- `src/ai/brainyard/agent/common/memory_agent/hooks.clj` — write-guard + (rev 3) the two batch-consolidation hooks (`::consolidation-cadence` on `:agent.ask/post`, `::session-end-flush` on `:agent.instance/closed`), all self-installing on namespace load. *(rev 2 shipped `::essence-capture` here instead; retired in rev 3 — see §10.0/§18.5.)*
- `test/ai/brainyard/agent/common/memory_agent/*_test.clj` — six test namespaces (`commands_test`, `working_area_test`, `memory_agent_test`, `essence_test`, `consolidate_test`, `verify_test`).

**Cross-cutting modifications:**

- `components/agent/src/ai/brainyard/agent/core/config.clj` — rev 2 added `:enable-memory-essence` (default `false`). **rev 3 replaced it** with `:enable-memory-consolidation` (default `false`) + `:memory-consolidate-every-n-turns` (default `12`).
- `components/agent/src/ai/brainyard/agent/common/coact_agent.clj` — rev 2 originally set `:enable-memory-essence true` in `:config-extra` (research-agent inherits), then commented it out so coact resorts to the schema default (essence shipped **off**). **rev 3** updated that `:config-extra` reference comment to `:enable-memory-consolidation` (still commented = off by default; one-line opt-in). The always-on capture pipeline (`:enable-memory-capture`, default `true`) is unaffected.
- `bases/agent-tui/src/ai/brainyard/agent_tui/commands.clj` — `/memory <subcmd>` slash-command.

**Tool surface (20 primitives):**

| Group | Count | Tools |
|---|---|---|
| Reads (open) | 5 | `memory$stats`, `memory$read`, `memory$recall`, `memory$explain`, `memory$keywords` |
| Writes (gated) | 7 | `memory$write`, `memory$promote`, `memory$forget`, `memory$keep!`, `memory$archive!`, `memory$consolidate`, `memory$sweep-l2` |
| Working-area (gated) | 3 | `memory$state-read`, `memory$state-write`, `memory$essence-append` |
| Signature wrappers (gated) | 3 | `memory$essence-extract`, `memory$llm-consolidate`, `memory$verify-fact` |
| Purge planner (gated) | 1 | `memory$purge-plan` |
| Reasoning | 1 | `query$llm` (re-exported from `agent.common.commands`) |

### 18.2 Decisions made during implementation

A handful of choices diverged from the proposal text in ways worth pinning down:

1. **`run-memory-agent` instead of `run-coact-derived`.** Using coact's `run-coact-derived` would also merge coact-agent's ~50-tool roster into memory-agent's. Memory-agent is a leaf bookkeeper; we want the narrow 20-tool surface visible to its LLM. `run-memory-agent` inherits coact's `:instruction` / `:tool-context` / `:bt-factory` only.
2. **Sub-LM default = `claude-code:sonnet`.** Open Q2 originally suggested haiku-class. We landed one notch up because EssenceExtraction / LlmReducer / FactVerification benefit measurably from sonnet's stronger structured-output reliability. Override per call via `:sub-lm-config`.
3. **`memory$verify-fact` is a tool, not a hand-crafted `query$llm` prompt.** Same for `memory$essence-extract` and `memory$llm-consolidate`. Each wraps `clj-llm/chain-of-thought` so the LLM that drives memory-agent doesn't have to construct prompts or parse JSON — Malli validates the output schema.
4. **`memory$verify-fact` deferred from Phase 1's primitive list.** Originally listed as a thin wrapper in §5.2; in practice it requires the FactVerification signature and so naturally lives with `:op :verify-fact` in Phase 5.
5. **`memory$write` is read-after-write idempotent on duplicate-key.** The unified store does plain `INSERT`; the unique `(user_id, entry_id)` constraint rejects duplicates rather than upserting. The command falls back to a read-by-id when the insert returns nil so callers see a stable id regardless. A future store-level `INSERT OR REPLACE` would make this fallback redundant.
6. **L3 orphan detection is empty for now.** `memory$purge-plan` returns `[]` for `:l3-orphan-facts` (Phase 4 deferral). Phase 5's `:op :verify-fact` via fresh recall catches the same cases more reliably, so this isn't blocking.
7. **`/memory` direct-dispatch.** The slash-command calls `agent/call-tool :memory-agent {...}` directly rather than enqueuing a hint to the user's current agent — `/memory` is a UI shortcut, not a nudge.

### 18.3 Deferred follow-ups

Five items are documented but not built. None are blocking the core "memory-agent works" outcome; each is an additive followup.

1. **Scheduled-task runner** (§11). The on-demand path (`(call-tool :memory-agent {:op :consolidate})`, `... :purge`) is live. A future-based scheduler that fires `memory-consolidate-recent` hourly and `memory-purge-weekly` weekly is the natural next step — pattern after `memory/start-sweeper!`. Once landed, this also unblocks #2 below.
2. **`pending/verify-queue.edn` drain.** Populated by `:op :purge`'s dry-run path; not yet drained automatically. The drain is one `:op :verify-fact` call per queued fact-id, capped at N per scheduler tick. Trivial once the scheduler exists.
3. **L3 orphan-fact detection in `memory$purge-plan`.** Today returns `[]`. The full implementation walks each fact's `:sources` JSON and cross-references against tombstoned ids — moderately fiddly because source-shape varies across producers (essence, consolidation, user-correction all carry different `:type` tags). The recall-based path in `:op :verify-fact` is the reliable fallback.
4. **Per-run artifact files**. Design §4.1 lists `consolidations/<ts>-<slug>.md`, `purges/<ts>.edn`, `verifications/<ts>-<fact-id>.md`. Today the LLM stashes the structured summary in its `:answer` block (a fenced clojure map). A dedicated `memory$state-write` slot whitelist for these patterns plus a small writer would persist the audit trail to disk — useful when log-grep becomes unwieldy.
5. **`:memory-agent/op-completed` observability hook** (Open Q5). Not added. Each `:op` invocation already produces a structured result in `:answer`; a custom hook fires nothing new today. Add when a metrics consumer needs push-style notification.

### 18.4 What to watch in dogfood

- DB growth rate vs. consolidation throughput. **rev 3:** the per-turn essence LLM call is gone; the only recurring cost is the batch reducer every N turns (LLM-free on the heuristic path; one summary call per cluster on the community path). Watch L3 growth vs. the cadence N — too small an N over-consolidates, too large drops episodes that age out before a flush.
- L3 fact churn. `:op :verify-fact` should produce mostly `:still-true` verdicts; a high `:wrong` rate means earlier consolidation passes are too aggressive.
- Consolidation tail coverage. The session-end flush (§10.0) is the safety net for the last `<N` turns. Confirm it fires on the real exit paths (`/quit`, EOF, `/agent close`) and that its 10s timeout is generous enough for the community/LLM path on large graphs.

### 18.5 rev 3 — essence retirement / batch consolidation (2026-06-28)

**Problem.** Per-turn essence capture (§10.1) dispatched a full memory-agent BT loop on *every* root turn. Observed cost: a bare "hello" turn ran 6 iterations / ~5 min before concluding "no essence"; an "AWS tools" listing ran 8. The actual judgement — `memory$essence-extract` — is a *single* sub-LM `chain-of-thought` call; the rest was the agentic envelope (stats → read L2 → extract → write/promote → append → answer), plus arg-shape fights and stale-sandbox `def` leakage across turns. It also duplicated the L2→L3 reducer (`capture/reducer.clj`) and community consolidation (CR-MEM-24) the pipeline already had — the same promotion, done eagerly and expensively per turn instead of cheaply in batch.

**Change.** Retired the `:agent.ask/post` essence-capture hook. L2→L3 promotion now runs as deterministic batch consolidation via two hooks in `memory_agent/hooks.clj` (see §10.0): the **cadence** (every N turns) and the **session-end flush** (root agent close). Both are root-only, gated on `:enable-memory-consolidation` (default false), and LLM-free on the default heuristic path. Modeled on the sibling `skill_distill` handler (deterministic pre-filter → single call, no agent loop). Config `:enable-memory-essence` → `:enable-memory-consolidation` + `:memory-consolidate-every-n-turns`.

**Kept.** `memory$essence-extract`, the `EssenceExtraction` signature, and the `:op :essence` playbook (now manual/REPL only). The always-on capture pipeline (`:enable-memory-capture`) is untouched.

**Tests.** `memory_agent/essence_test.clj`'s `essence-extract-*` tests are unchanged; the `essence-capture-*` tests were replaced with `consolidation-cadence-*` and `session-end-flush-*` coverage (14 tests / 38 assertions in that ns; the four sibling memory-agent namespaces + capture-lifecycle stay green). A real-LLM end-to-end harness — `scripts/test-memory-auto-consolidate.sh` (`bb test:memory:auto`) — drives `by ask` with `BY_ENABLE_MEMORY_CONSOLIDATION=true` and asserts L2→L3 promotion happens automatically at agent close (no `by memory consolidate` call), with a gated-off negative control. Validated 9/9 against `claude-code:haiku`. The `BY_ENABLE_MEMORY_CONSOLIDATION` env-fn was added to `:enable-memory-consolidation` for this (mirrors `:enable-graph-memory`).
