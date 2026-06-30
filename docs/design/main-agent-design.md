# Main-Agent — The Front-Door Orchestrator (CoAct-derived)

> **Status:** Shipped. main-agent is registered in `components/agent`
> (`common/main_agent.clj`) and is the front-door router; the lightweight,
> hook-derived redesign shipped (2026-06). This doc is the as-built reference —
> the former `main-agent-lightweight-redesign.md` has been folded in here and
> removed.
>
> **As-built (verify against `common/main_agent.clj`, `common/main.clj`,
> `common/main_agent_hooks.clj`):**
> - **main-agent is the front-door router.** Each user turn it routes to the
>   right specialist (or answers inline via CoAct's own answer / tool / code
>   channels). Routing is pure LLM judgment over the §6 decision table; main-agent
>   authors no plan/edit/verdict and drives no research arc of its own.
> - **It already binds the file tools and is already hook-driven.** The roster
>   includes `file-tools`/`shell-tools` with no `remove` clause; `pointers.md` is
>   auto-captured by a `:agent.tool-use/post` hook and the session `INDEX.md` by a
>   `:agent.session/closed` hook (`common/main_agent_hooks.clj`).
> - **The routing-log line is HOOK-DERIVED, not LLM-constructed.** The redesign
>   moved the per-move routing line off an LLM-facing constructor
>   (`main$append-log`, now **retired** as a tool) onto a **turn-derived hook**
>   (`record-routing-line` on `:agent.ask/post`, the sole writer of
>   `routing.log`). The model just routes, states a one-sentence reason, and adds
>   a `Routing: <shape> — <reason>` line for self-answered moves; the hook derives
>   `routed-to`/`shape`/`artifact` from the turn and appends the NDJSON line.
>   `routing.log` format is unchanged, so `main$resume?`/`main$last-shape` parse it
>   identically. See §4.2, §5, §10.
> - **Five hooks shipped** in `common/main_agent_hooks.clj` (not the three this
>   doc originally proposed): `:agent.session/created` (bootstrap),
>   `:agent.ask/pre` (snapshot max-turn), `:agent.tool-use/post` (capture `Saved
>   <kind>:` → pointers.md), `:agent.ask/post` (**record the routing line** — the
>   redesign's new derivation hook), `:agent.session/closed` (finalize INDEX.md).
> - **Roster grew to 21 specialists / 22 shapes.** Adds **`tool-agent`**
>   (user-defined `(fn [args] …)` tools under `.brainyard/tools` — `tool-agent$*`)
>   and **`meta-agent`** (user-defined CoAct-derived agents/personas under
>   `.brainyard/agents/user$agent` — `meta-agent$*`). The decision table has 22
>   shapes (adds `U. TOOL-LIFECYCLE` → tool-agent, `V. AGENT-LIFECYCLE` →
>   meta-agent); `coerce-shape` validates against the 22-keyword `valid-shapes` set
>   with an `:unspecified` fallback that never fails the turn.
> - **Cross-agent dispatch is direct kebab-case**, not `call-tool`. The shipped
>   instruction invokes `(plan-agent {…})` / `(research-agent {…})` directly (or
>   via the tool-channel JSON). The `call-tool` forms shown elsewhere are
>   equivalent but not how the shipped prompt phrases it.
> - **Hard Rule 1 is "STAY FLAT — no clone-self dispatch,"** not literally "NO
>   `query$clone`." `query$clone` is excluded by simply not being in the roster
>   (gated to rlm-agent). Intent is identical.
> - **`task$run` for specialists is explicitly forbidden** (Hard Rule 9):
>   specialists return promptly and polling them trips the loop guard.
>   `task$run :job-type :bash` is for arbitrary subprocesses only.
> - **Names align with the shipped agents:** the safe-edit specialist is
>   `edit-agent` (not `update-agent`); the cherry-picked eval reader is
>   `eval$read-verdict` (not `eval$read-dossier`).
>
> **Scope:** `components/agent/src/ai/brainyard/agent/common/main_agent.clj`,
> `common/main.clj`, `common/main_agent_hooks.clj`
> **Built on:** `coact_agent.clj` via `coact/run-coact-derived`
> **Related reading:** `docs/core/agent.md`, `docs/core/reasoning.md`,
> `docs/core/tool.md`, `docs/design/research-agent-design.md`,
> `docs/design/workflow-agent-design.md`, `docs/design/explore-agent-design.md`,
> `docs/design/agent-lightweight-redesign-synthesis.md`

---

## 1. Motivation

Before main-agent, the user landed on `coact-agent` by default (`bb tui run` with no `-a`). `coact-agent` is the right *substrate* — three channels, a persistent SCI sandbox, a curated set of generic tools, no domain prejudice. It is intentionally minimal: a clean reasoning loop with no awareness of the specialist agents stacked on top of it.

That neutrality is a problem the moment the agent population grows beyond a handful. The stack ships a large `defagent` population (coact / react / explore / plan / todo / exec / eval / edit / memory / init / config / skill / mcp / tool / meta / rlm / research / workflow / acp / debug / main), each with non-trivial when-to-use / when-to-avoid rules baked into their own design docs. A first-time user asking *"can you draft a plan to migrate our auth middleware and execute it?"* has to either:

1. Know in advance to type `bb tui run -a research-agent`, or
2. Land on `coact-agent` and watch it solve a three-specialist problem with `bash`, `read-file`, and `query$llm` — bypassing the pre/post-flight gating, dossier handoff, and acceptance criteria that the dedicated specialists were designed for.

**The same routing problem `explore-agent` solved at the discovery surface ("filesystem? web? MCP? skills?") now exists at the agent-population surface.** Users should not pre-classify their question into the right `defagent`. The agent should route.

Three structural defects in the current default:

1. **Coact-agent under-uses the specialist roster.** Its instruction tells it about tool/code/answer channels and `task$run`. It does not tell it that `plan-agent` exists, when to reach for it, or that `research-agent` would handle a multi-step engineering question more coherently than a 20-iteration code-channel arc. The specialist agents are registered in `!tool-defs` and reachable via `(<agent-name> {…})` or `call-tool`, but coact-agent's prompt never names them. The LLM rediscovers them turn-by-turn — or doesn't.

2. **No durable per-user-question routing log.** When a question takes 3–5 specialists to answer (research-agent or workflow-agent territory), brainyard already has dossier files for cross-agent state. But for the *front door* — the single question a user just asked — there is no record of "main-agent decided this was a research task and handed off to research-agent". Subsequent questions in the same session re-derive routing from scratch.

3. **Channel discipline is generic.** coact-agent's `## When to Use Which Channel` covers tool / code / answer, but says nothing about *which sub-agent* the tool channel should reach for in different question shapes. Sub-agent dispatch *is* a tool call (every `defagent` registers in `!tool-defs`), but the agent's instruction has to teach the routing rules, not just the call mechanics.

**The CoAct lesson — applied at the front door.** `research-agent` showed that a CoAct loop with a curated instruction and a small specialist roster outperforms a bespoke BT for multi-specialist orchestration. `workflow-agent` showed the same recipe scales up to domain workflows. `main-agent` is the recipe applied one level further out: at the *user-question* boundary, where the right move could be anything from "answer in one iteration" to "delegate to workflow-agent for a 50-iteration arc".

**Thesis.** A CoAct-derived `main-agent` that:

1. **Owns the routing instruction** — a curated decision table mapping question shape to the right specialist (or to coact-agent's own channels when no specialist fits). Routing is pure LLM judgment; nothing about it is over-tooled.
2. **Reaches every registered specialist via flat dispatch** — `(plan-agent {…})`, `(research-agent {…})`, `(workflow-agent {…})`, … — through the unified `!tool-defs` registry. No new dispatch substrate.
3. **Records a thin session log that records itself** — `.brainyard/agents/main-agent/<session-id>/routing.log` — capturing each routing decision (which specialist was picked + why) so subsequent questions in the same session can short-circuit re-derivation. The line is **hook-derived, not LLM-constructed**: the model routes and states a reason; a `:agent.ask/post` hook observes the turn and appends the structured NDJSON line (§5). The root agent ends each turn with **zero structured artifacts to author**.
4. **Inherits the CoAct loop, sandbox, router, and accumulator** from `coact-agent` via `run-coact-derived` — no new BT, no new DSPy signature.
5. **Is the front-door router** — the front-door entry that routes by question shape, with `coact-agent` retained as the bare-substrate fallback (`-a coact-agent`) for power users who want zero routing assistance.

This is the same minimal-diff pattern that `explore-agent`, `rlm-agent`, `research-agent`, and `workflow-agent` follow, and the same judgment-vs-mechanism split as the rest of the [lightweight-redesign series](./agent-lightweight-redesign-synthesis.md): **route in prose; record the route with a hook; keep reads typed.** The whole feature is one agent file, a small helpers namespace, and a hooks namespace — no substrate changes.

---

## 2. Design Principles

1. **Routing is the agent's identity.** main-agent does not solve sub-problems itself. Its job is to pick the right specialist (or coact channel) per question shape, hand off cleanly, and report back. When it does answer directly (greetings, factual recall, single-fact lookups), it uses CoAct's own answer channel — same substrate, no separate codepath.

2. **Specialists own their domains; main-agent owns the seam between them.** main-agent never writes to `.brainyard/agents/plan-agent/`, `.brainyard/agents/todo-agent/`, `.brainyard/agents/exec-agent/`, etc. Those are specialist territories. main-agent writes only to `.brainyard/agents/main-agent/<session-id>/routing.log` and reads sibling outputs through their cherry-picked read-helpers.

3. **The instruction encodes the routing decisions; routing is judgment.** A decision-table layer in the instruction (§6) names every question shape and the right move. The LLM uses the table; the BT does not branch on it. Same `coact-loop-subtree`. The deciding — pick the move, state a one-sentence reason — is inherent LLM capability and is left entirely to the model.

   3a. **The routing log is observation, not authoring.** Most of a routing-log line is *observable from the turn itself* — which specialist was dispatched, what `Saved <kind>:` artifact came back, the user's question, the turn/iter counters. So a hook derives the line; the model never assembles a structured map. This is the main-agent analog of eval's "scoring is judgment" and research's "orchestration is judgment": the *deciding* is inherent; only the *logging* was over-tooled. See §5.

4. **Small tool registry.** main-agent reaches the world through (a) every other `defagent` via direct kebab-case dispatch (they are in `!tool-defs`), (b) basic CoAct primitives for the routing log (read-file / write-file / bash), (c) bootstrap discovery (list-tools / get-tool-info / search) so the agent can introspect when an unfamiliar question requires it. That's it.

5. **Acceptance lives one level down.** main-agent does NOT manage acceptance criteria itself. If a question is research-shaped, acceptance lives in the research dossier; if workflow-shaped, in the workflow dossier; if it's a single specialist call, in that specialist's pre/post-flight. The front-door agent has nothing to commit to — its only contract is "I picked the right downstream agent and surfaced their result coherently."

6. **No clone-self recursion.** `query$clone` is excluded — calling main-agent from inside main-agent is the depth-2 anti-pattern. Calling *other* agents through their kebab-case dispatch is flat call-tool routing and IS the design.

7. **Generous iteration cap, but typically uses few.** Default 20 iterations (CoAct's default). Most user questions resolve in 1–3 iterations (route → one specialist call → finalize). The budget exists for questions that need light pre-research before routing, or for multi-specialist questions where main-agent threads two specialists itself rather than handing the whole arc to research-agent.

8. **Session-scoped routing memory.** The routing log is per agent-session (the shared `:user-id`/`:session-id` map), not per-question. Subsequent questions in the same session read the prior log to decide whether a routing decision is already in progress or settled. A research-agent run that the user just initiated stays visible to subsequent main-agent turns in the same session.

9. **Honest hand-off.** When main-agent delegates to a specialist, the specialist's `Saved <kind>: <path>` contract line is surfaced verbatim in main-agent's `:answer` so the user always sees the durable artifact path. The user can then `bb tui run -a <specialist> "@<id>"` to resume directly with the specialist on a future turn — main-agent's routing was a one-time decision, not a sticky binding.

10. **Falls back to coact-agent's channels.** If no specialist fits, main-agent uses CoAct's own tool / code / answer channels directly — same registry, same sandbox, same finalize. main-agent is a *thin specialization* of coact-agent, not a replacement.

---

## 3. Position in the Agent Stack

```
coact-agent  (substrate — full BT, sandbox, router, accumulator)
  ├─ explore-agent     (multi-surface read-mostly discovery)
  ├─ rlm-agent         (MapReduce over too-big context)
  ├─ skill-agent       (skills$* lifecycle: create/update/install)
  ├─ mcp-agent         (MCP lifecycle + write ops)
  ├─ plan-agent        (plan authoring; pre/post-flight gated; emits dossier)
  ├─ todo-agent        (todo decomposition from plan; per-item :tags routing)
  ├─ exec-agent        (advance a todo; delegates writes to edit-agent)
  ├─ eval-agent        (verdict against plan acceptance)
  ├─ edit-agent        (safe single-file edit; probe→apply→verify→rollback)
  ├─ memory-agent      (long-term memory: read/write/consolidate)
  ├─ init-agent        (BRAINYARD.md / project bootstrap)
  ├─ config-agent      (.brainyard/config tuning)
  ├─ acp-agent         (Agentic Context Protocol — external backends)
  ├─ tool-agent        (user-defined tool lifecycle — tool-agent$* family)
  ├─ meta-agent        (user-defined agent/persona lifecycle — meta-agent$*)
  ├─ research-agent    (end-to-end multi-specialist research thread)
  ├─ workflow-agent    (domain workflow templates; multi-stage automation)
  └─ main-agent        (front-door router — the user-facing entry)
```

`main-agent` does **not** replace any existing agent. It is the layer above all of them.

| Question shape | Use | Why |
|---|---|---|
| "Hi" / "What is 2+2?" / "Tell me about CoAct" | main-agent answers directly (answer channel) | Conversational / direct knowledge; no tool needed. |
| "What's in this file?" / "List tools matching X" | main-agent uses tool channel (read-file / list-tools / get-tool-info) | One-shot RPC; no specialist warranted. |
| "Find me where the loop guard lives" | main-agent → explore-agent | Discovery across files/web/MCP/skills. |
| "Rename foo to bar in src/x.clj" | main-agent → edit-agent | Safe single-file edit; no plan/todo arc. |
| "Compose three tool results into a table" | main-agent uses code channel (clojure fence) | Composition; no specialist. |
| "Search Slack for messages about Q4 launches" | main-agent → mcp-agent (or tool channel if direct call sufficient) | MCP read/write; specialist owns lifecycle. |
| "Draft a plan to migrate the auth middleware" | main-agent → plan-agent | Plan authoring; pre/post-flight gating. |
| "Spawn a todo from plan migration-auth" | main-agent → todo-agent | Decomposition from existing plan dossier. |
| "Drive the migration-auth todo to completion" | main-agent → exec-agent | Per-item routing; delegates writes. |
| "Score whether migration-auth met its acceptance" | main-agent → eval-agent | Verdict against plan acceptance. |
| "Research how to reduce `bb tui` cold start to <1s and implement it" | main-agent → research-agent | Multi-specialist research thread with dossier. |
| "Run the feature-launch workflow for feature F" | main-agent → workflow-agent | Multi-stage domain workflow. |
| "Summarize patterns across 200 log files" | main-agent → rlm-agent | MapReduce over too-big context. |
| "Save this note to memory" / "What do you remember about X?" | main-agent → memory-agent | Long-term memory ops. |
| "Bootstrap BRAINYARD.md for this repo" | main-agent → init-agent | Project bootstrap. |
| "Help me prepare for a meeting using ACP" | main-agent → acp-agent | External agent backend. |

Rule: **main-agent is for the first hop**. After hand-off, the specialist owns the arc. On the next user turn, main-agent looks at the routing log to decide whether to resume the same specialist (`bb tui ask "@<id>"`) or route fresh.

---

## 4. The Routing Log — `.brainyard/agents/main-agent/<session-id>/`

The single piece of state main-agent itself owns. Thin, append-only, session-scoped.

### 4.1 Directory Layout

```
.brainyard/
└── main-agent/
    ├── INDEX.md                       ; one-line entry per session, newest first
    └── <session-id>/                  ; one directory per agent-session
        ├── routing.log                ; append-only NDJSON; one line per routing decision
        └── pointers.md                ; markdown summary of artifacts cited so far this session
```

The `<session-id>` is the agent-session's `:session-id` (the same identity carried by sub-agents). main-agent does NOT manufacture its own ids — it piggybacks on the session machinery already in place (see `docs/core/agent.md` §Two-layer session model).

### 4.2 `routing.log` — NDJSON Decision Trail (hook-written)

One line per routing decision. Cheap to read selectively (jq, grep, or `read-file :lines [N M]`). The line is **written by the `:agent.ask/post` routing-line hook**, not by the LLM — main-agent no longer calls `main$append-log` (retired as a tool). The hook derives `routed-to`/`shape`/`artifact` from the turn and lifts `reason` from the model's answer; the format below is unchanged so `main$resume?`/`main$last-shape` parse it identically. See §5 for the derivation.

```ndjson
{"turn":1,"iter":1,"question":"draft a plan to migrate auth middleware","shape":"plan-author","routed-to":"plan-agent","artifact":".brainyard/agents/plan-agent/plans/migrate-auth.md","reason":"explicit 'draft a plan'"}
{"turn":1,"iter":2,"question":"now spawn a todo from it","shape":"decompose","routed-to":"todo-agent","artifact":".brainyard/agents/todo-agent/todos/migrate-auth.md","reason":"existing plan dossier present"}
{"turn":2,"iter":1,"question":"drive it to completion","shape":"execute","routed-to":"exec-agent","artifact":".brainyard/agents/exec-agent/dossiers/20260516-094812-migrate-auth.md","reason":"todo exists with post.verdict :pass; user says 'drive it'"}
{"turn":3,"iter":1,"question":"what was that about?","shape":"meta-resume","routed-to":null,"artifact":null,"reason":"session memory lookup — answered from routing.log + pointers.md"}
```

Fields:

| Field | Type | Purpose |
|---|---|---|
| `turn` | int | 1-based user-turn index within this session. |
| `iter` | int | main-agent iteration within that turn. |
| `question` | string | Distilled user-question or sub-question. |
| `shape` | enum | One of the 22 `valid-shapes` keywords (decision-table letters A–V): `direct-answer` / `tool-fetch` / `code-compose` / `explore` / `update` / `plan-author` / `decompose` / `execute` / `evaluate` / `research` / `workflow` / `rlm` / `memory` / `skill-lifecycle` / `mcp-lifecycle` / `init` / `config` / `acp` / `meta-resume` / `clarify` / `tool-lifecycle` / `agent-lifecycle`. Coerced by `coerce-shape`; an unknown value falls back to `:unspecified` (never fails the turn). |
| `routed-to` | string or null | The specialist's kebab-case name (derived from the turn's dispatch), or `null` for self-answered. |
| `artifact` | string or null | Path emitted by the specialist (parsed from its `Saved <kind>: <path>` line). |
| `reason` | string | One-sentence rationale lifted from the model's `:answer` (its `Routing:` line, or the first prose line). |

### 4.3 `pointers.md` — Session Artifact Roll-up

Human-readable companion to the log. Newest first. Lets the user see "what files did this session produce".

```markdown
# Session pointers

- 2026-05-16 09:48 [exec dossier — migrate-auth](computer://...) — `Done: items 0–5`
- 2026-05-16 09:30 [todo body — migrate-auth](computer://...)
- 2026-05-16 09:14 [plan body — migrate-auth](computer://...) + dossier `post.verdict=:pass`
```

Both files are append-only. `pointers.md` is auto-captured by the `:agent.tool-use/post` hook (§10.2); on session close (`:agent.session/closed`), the `INDEX.md` at `.brainyard/agents/main-agent/` gets a one-line entry with the session-id, turn count, and the headline routing shapes seen.

### 4.4 The Routing Log as Hook-Derived Observation (the main-agent-specific move)

main-agent is the **least brittle** agent in the set — most of its bookkeeping was already hooks + read seams. Before the redesign there was exactly one authoring micro-tool left: `main$append-log`, which made the model assemble a per-move map `{:turn :iter :question :shape :routed-to :artifact :reason}` and classify `:shape` against the multi-element enum every single turn. The instruction leaned on it hard (*"the log is the contract with the next turn; failing to update it makes resume-shaped questions ambiguous"*) — the same per-turn-obligation pressure with the same failure mode: a smaller model skips it, and continuation detection silently degrades. Because the root agent runs on *every* turn, that tax was paid the most often here.

The redesign replaces that with a hook that **observes** the turn:

**What the hook derives on its own (no model effort):**

- **`routed-to`** — the specialist defagent dispatched this turn, scanned from the turn's tool-calls across all iterations (`routed-to-of`). `nil` if the turn was self-answered.
- **`shape`** — for specialist moves, a deterministic map from the routed-to agent (`specialist->shape`: `plan-agent → :plan-author`, `todo-agent → :decompose`, `exec-agent → :execute`, `eval-agent → :evaluate`, `explore-agent → :explore`, `edit-agent → :update`, `research-agent → :research`, …). The decision table is essentially a shape↔agent bijection on the specialist rows, so the hook reads shape off the dispatch for free.
- **`artifact`** — the first `Saved <kind>: <path>` line the specialist emitted (`parse-saved-lines`), already extracted by the capture hook.
- **`question`** — the user's turn input (capped at 200 chars).
- **`turn` / `iter`** — the loop already tracks these (the `:agent.ask/pre` hook snapshots `max(turn)` so the post hook knows the next turn number and can no-op a double-fire).

**What the model still supplies (judgment, in prose it writes anyway):**

- **`reason`** — the one-sentence routing rationale. The instruction *already* requires the model to surface "what you did (one-sentence routing decision)" in its `:answer`. The hook lifts that line (the `Routing:` reason, or the first prose line of the answer); no separate field.
- **`shape` for self-answered moves** — `:direct-answer` / `:tool-fetch` / `:code-compose` / `:meta-resume` / `:clarify` have no dispatch to infer from. For these the model emits one stable line in its answer — `Routing: <shape> — <reason>` — and the hook parses it (`routing-answer-re`). One line, one enum token, not a seven-key map. If the line is absent, shape falls back through a channel heuristic (`:code` → `:code-compose`, any tool call → `:tool-fetch`, else `:direct-answer`) and finally `:unspecified` — the entry is thinner but never missing.

The `:shape` enum is still validated — but at *parse* time in the hook (`coerce-shape` against the 22-keyword `valid-shapes` set), with an `:unspecified` fallback that never fails the turn, instead of an eager hard-reject on a typo. The result: **the routing trail is complete (and arguably more complete, since the hook can't forget), while the root agent ends each turn having done only judgment.**

---

## 5. Tool Roster

```clojure
(:require
  [ai.brainyard.agent.common.tools     :as common-tools]
  [ai.brainyard.agent.common.commands  :as common-cmds]
  [ai.brainyard.agent.common.plan      :as plan-helpers]
  [ai.brainyard.agent.common.todo      :as todo-helpers]
  [ai.brainyard.agent.common.exec      :as exec-helpers]
  [ai.brainyard.agent.common.eval      :as eval-helpers]
  [ai.brainyard.agent.common.edit      :as edit-helpers]
  [ai.brainyard.agent.common.main      :as main]
  [ai.brainyard.agent.task.commands    :as task-cmds])

(def main-tools
  (vec (distinct
         (concat
           ;; Filesystem — for main-agent's OWN routing-log dir.
           ;; Hard Rule 2 forbids writes to sibling-specialist dirs.
           common-tools/file-tools          ; read-file, write-file, update-file, grep, fetch-url
           common-tools/shell-tools         ; bash (mkdir, ls)

           ;; Discovery — main-agent answers some questions directly via tool
           ;; channel without delegating (list-tools / get-tool-info / search).
           common-tools/bootstrap-tools     ; list-tools, get-tool-info, search
           common-tools/invocation-tools    ; call-tool

           ;; Web — direct one-off lookups; explore-agent for non-trivial discovery
           common-tools/web-tools           ; web-search, fetch-url

           ;; Read-only sibling dossier/record helpers (cherry-picked).
           ;; Lets main-agent inspect upstream artifacts cheaply when deciding
           ;; whether to resume a specialist arc vs. start fresh.
           [#'plan-helpers/plan$read-dossier
            #'todo-helpers/todo$read-dossier
            #'exec-helpers/exec$read-dossier
            #'eval-helpers/eval$read-verdict
            #'edit-helpers/edit$read-record]

           ;; Sub-LLM synthesis (flat only) — intentionally excludes #'query$clone
           [#'common-cmds/query$llm]

           ;; User-interaction (clarification questions go through CoAct's
           ;; answer channel — no special tool needed)

           ;; Background jobs — rarely needed; specialists own their long ops
           task-cmds/task-commands

           ;; Runtime config — for tuning :max-iterations mid-session
           common-cmds/runtime-commands

           ;; main$* helpers (see §8) — session-id / resume? / bootstrap /
           ;; append-pointer / last-shape / index-append. main$append-log is
           ;; NOT here: the per-turn routing LINE is hook-recorded (§5, §10).
           main/main-helpers))))
```

The specialist `defagent`s (`explore-agent`, `plan-agent`, `todo-agent`, `exec-agent`, `eval-agent`, `edit-agent`, `research-agent`, `workflow-agent`, `memory-agent`, `init-agent`, `config-agent`, `skill-agent`, `mcp-agent`, `rlm-agent`, `acp-agent`, `debug-agent`, plus `coact-agent` / `react-agent` themselves as advanced fallbacks) are not bound as direct sandbox functions — they self-register in `!tool-defs` through their own `defagent` forms and are reached by direct kebab-case dispatch from a clojure fence, OR via the tool channel:

```clojure
;; From a clojure fence in code-blocks:
(plan-agent {:question "..." :agent-context "..."})

;; From the tool channel (JSON):
[{"tool-name": "plan-agent",
  "tool-args": [{"name": "question", "value": "..."},
                {"name": "agent-context", "value": "..."}]}]
```

The instruction's job (§6) is to teach the LLM **when to reach for each specialist**. The roster above is the *substrate* main-agent uses for everything else (routing log, direct answers, light pre-research before routing).

What is *deliberately omitted*:

| Excluded | Reason |
|---|---|
| `query$clone` | Clones main-agent itself = clone-self recursion. Forbidden. Cross-agent dispatch is direct kebab-case + flat. |
| `main$append-log` (as an LLM tool) | **Retired.** The per-turn routing line is hook-derived (§5, §10) — the model no longer assembles it. A private `append-log!` fn renders the same NDJSON line and is called only from the routing-line hook. |
| Direct `plan$dossier-write`, `todo$dossier-write`, `exec$dossier-write`, `eval$dossier-write`, `eval$verdict-write`, `edit$apply` | Sibling-specialist writes go through their specialists, never directly. The read-only cherry-pick (`plan$/todo$/exec$read-dossier` + `eval$read-verdict` + `edit$read-record`) is the deliberate asymmetry. |
| `mcp$tools :call` (write-side), `skills$write` / `skills$install` | These belong to mcp-agent / skill-agent. main-agent routes; it does not bypass. |

What is *bound directly* but should be reached for sparingly:

| Bound | When to use directly | When to route to a specialist |
|---|---|---|
| `web-search`, `fetch-url` | Quick one-off lookup mid-routing where the result confirms a single fact (e.g. "is X still the current API endpoint?"). | Multi-source discovery, ambiguous question, or any lookup whose result will be cited in a downstream artifact — call `explore-agent`. |
| `read-file`, `grep` | Reading a SPECIFIC sibling dossier paragraph the frontmatter cherry-picks don't carry. | Use the `*$read-dossier` cherry-picks first — parsed frontmatter for free. |
| `bash` | `mkdir`, `ls`, basic git inspection. | Long-running subprocess, build step, test run — route to exec-agent (with edit-agent for any file writes). |
| `list-tools`, `get-tool-info`, `search` | Discovering which specialist owns a domain the user named obliquely ("can you do X?"). | Don't over-use — most decisions should be table-driven from §6, not registry-introspecting. |

---

## 6. Instruction (System Prompt Body)

Layered on top of `coact-agent`'s instruction by `run-coact-derived`.

> The full instruction body lives in `components/agent/src/ai/brainyard/agent/common/main_agent.clj` under `(def ^:private instruction …)`. This section reproduces the section headers + key contracts; consult the source for the authoritative current text.

```text
You are MAIN-agent, the front-door router for brainyard. Each user turn is
your chance to pick the RIGHT next move: answer directly, fetch a quick
tool, compose with code, or — most often — DELEGATE to a specialist agent
whose domain fits the question. You decide. The specialists own their work.

The CoAct loop and the routing log are your only fixed scaffolding.

────────────────────────────────────────────────────────────────────────────
THE SPECIALIST ROSTER (direct kebab-case dispatch — flat, NOT recursive)
────────────────────────────────────────────────────────────────────────────
Each specialist self-registers in !tool-defs through its own defagent.
Invoke directly by kebab-case name OR via the tool channel; arguments are
always {:question "<sub-question>" :agent-context "<context>"}.

DISCOVERY & READ-MOSTLY
- explore-agent  → multi-surface discovery (files / web / MCP / skills).
                   Saves under .brainyard/agents/explore-agent/results/ and emits
                   `Saved exploration: <path>` in its answer. Use for any
                   "find me X" / "what's in Y" / "is there a Z?" question
                   that crosses surfaces or warrants a durable artifact.

- rlm-agent      → MapReduce over too-big context (>200K tokens or
                   200+ files). Use for "summarize patterns across N
                   files", "extract all X from this corpus", "consolidate
                   findings across these sources".

PIPELINE (plan/todo/exec/eval — pre/post-flight gated + dossier handoff)
- plan-agent     → authors plan blueprint at
                   .brainyard/agents/plan-agent/plans/<slug>.md. Pre-flight
                   gates goal-clarity / no-duplicate / explored / refs-
                   exist / scope. Emits `Saved plan:` AND `Saved dossier:`.
                   Use when the user wants a plan WRITTEN — not when they
                   want execution. ("draft a plan", "scope this work",
                   "what's the approach to X").

- todo-agent     → decomposes plan into executable items. Pre-flight
                   reads plan-agent dossier; refuses if post.verdict :hold.
                   Items carry per-item :tags {:via :covers}. Emits
                   `Saved todo:` AND `Saved dossier:`. Use when a plan
                   exists and the user wants concrete items.

- exec-agent     → drives a todo to completion. Pre-flight reads todo
                   + plan dossiers. Per-item routes via :tags.via —
                   :edit-agent items delegate to edit-agent. Emits
                   `Saved dossier:` plus `Done:` / `Manual:` / `Hold:`.
                   Use when a todo exists and the user wants the work
                   DONE (not just listed).

- eval-agent     → scores executed todo vs plan acceptance. First agent
                   reading THREE upstream dossiers. Emits `Saved verdict:`
                   AND `Saved dossier:` plus `Verdict: ACHIEVED|
                   PARTIALLY_ACHIEVED|NOT_ACHIEVED`. Use when the user
                   asks for a status check, score, or sign-off.

WRITES & FIXES
- edit-agent   → safe single-file edit (probe → apply → verify →
                   persist → rollback-on-fail). Emits `Saved edit:` AND
                   `Rollback: <cmd>`. Use directly for one-off edits the
                   user spelled out concretely ("rename foo to bar in
                   src/x.clj", "add a comment to line 42 of …"). NOT for
                   open-ended refactor — route those through plan/todo/exec.

MULTI-SPECIALIST ORCHESTRATION
- research-agent → end-to-end research thread; threads PURPOSE,
                   DIRECTION, ACCEPTANCE across explore/plan/todo/exec/
                   eval/update via its own dossier under
                   .brainyard/agents/research-agent/<id>/. Use when the user's
                   question is research-shaped: investigate, decide,
                   plan, do, evaluate — and they want ONE agent to drive
                   the arc. Default :max-iterations 30.

- workflow-agent → domain-specific multi-stage automation. Reads
                   templates from .brainyard/workflows/<domain>.edn.
                   Use for named workflows the user invokes by domain
                   ("run the feature-launch workflow for F",
                   "incident-response runbook for outage O").
                   Default :max-iterations 50.

LIFECYCLE & EXTERNAL
- skill-agent    → skills$* lifecycle (write/install/sync; not just
                   read). Use when the user says "create a skill that
                   does X" or "install this skill from URL".

- mcp-agent      → MCP lifecycle (start/stop/restart) and write-side
                   tools (mcp$tools :call on a write-flagged tool). Use
                   for "connect to Linear MCP", "create issue in Jira",
                   "post to Slack channel".

- memory-agent   → long-term memory read/write/consolidate. Use for
                   "remember that …", "what do you remember about …",
                   "forget …", "consolidate L2 into L3".

- init-agent     → project bootstrap. Reads BRAINYARD.md, detects
                   sources, snapshots state. Use for "set up brainyard
                   for this repo", "initialize from .env".

- config-agent   → .brainyard/config tuning. Use for "update the
                   default model to …", "snapshot current config",
                   "revert last config change".

- acp-agent      → Agentic Context Protocol backend bridge. Use when
                   the user explicitly names an external ACP agent.

ADVANCED FALLBACKS (rare — only when no specialist fits)
- coact-agent    → bare-substrate CoAct (this agent without the routing
                   instruction). Reach for it only when the user
                   explicitly requests "no routing" or when the question
                   is so generic that even the routing log can't decide.
- react-agent    → classic ReAct loop. Niche — use when the user
                   explicitly wants ReAct semantics.

Invoke each by direct kebab-case dispatch from a clojure fence:
    (plan-agent      {:question "..." :agent-context "..."})
    (research-agent  {:question "..." :agent-context "..."})
    (workflow-agent  {:question "..." :agent-context "..."})

OR via the tool channel (JSON):
    [{"tool-name": "<agent-name>",
      "tool-args": [{"name": "question",      "value": "..."},
                    {"name": "agent-context", "value": "..."}]}]

────────────────────────────────────────────────────────────────────────────
TURN 1 — SESSION PROBE (the routing-log dir is bootstrapped for you)
────────────────────────────────────────────────────────────────────────────
The routing-log directory is created automatically when the session opens (an
`:agent.session/created` hook), and the per-turn routing line is hook-recorded
(see ROUTING LOG below) — so you have NO bootstrap obligation. On the first
iteration, just probe whether this session already has history:

```clojure
(def sid (:session-id (main$session-id)))   ; current agent-session id
(def state (main$resume? :session-id sid))  ; {:exists? … :last-shape … :last-artifact …}
;; If (:exists? state): RESUME — read routing.log / pointers.md and surface a
;; one-paragraph 'where this session has been' in your :thought before deciding
;; this turn's move. Otherwise it's a fresh session — route normally.
```

Subsequent turns in the same session re-read `routing.log` to detect:
- An in-flight specialist arc the user is continuing ("now spawn a todo
  from it" after a prior plan-agent call → DECOMPOSE).
- A completed specialist arc the user is following up on ("what was
  that verdict again?" → answer from pointers.md, no new specialist
  call needed).
- A pivot to a new question ("OK, different topic: …" → fresh route).

────────────────────────────────────────────────────────────────────────────
DECISION TABLE — question shape ↔ move
────────────────────────────────────────────────────────────────────────────
Every iteration picks ONE move. There is no fixed order; the table
maps question SHAPE to the right specialist. When in doubt, prefer the
specialist over self-answering — specialists emit durable artifacts; you
do not.

A. DIRECT-ANSWER   (answer channel)
   Shapes: greeting, casual chat, factual knowledge question, "explain X",
           clarification request, meta-question about brainyard itself.
   Example: "Hi", "What is CoAct?", "Can you explain Polylith?"

B. TOOL-FETCH      (tool channel — generic tools, no specialist)
   Shapes: one-shot RPC, "show me file X", "list tools matching Y",
           "search the registry for Z", "fetch URL <url>".
   Example: "Show me bb.edn", "List all defagents".

C. CODE-COMPOSE    (code channel — clojure / bash / python / js)
   Shapes: composition of prior results, filter/map/reduce, parallel
           sub-queries, raw scripts with nested quotes.
   Example: "Pretty-print the tools where description matches /agent/".

D. EXPLORE         → explore-agent
   Shapes: open-ended discovery, "find me X", "where does Y live",
           cross-surface inquiry, ambiguous lookup likely to produce
           an artifact worth citing.
   Example: "Where is the loop guard implemented?", "What MCP servers
            are configured?".

E. UPDATE          → edit-agent
   Shapes: single concrete edit, "rename A to B in file F", "add line
            to file F", "fix typo in F line N".
   Example: "Rename `foo-bar` to `foo-baz` in src/x.clj".

F. PLAN-AUTHOR     → plan-agent
   Shapes: "draft a plan to X", "scope this work", "what's the approach
           to X?", "write me a plan body".
   Example: "Draft a plan to migrate the auth middleware".

G. DECOMPOSE       → todo-agent
   Shapes: "spawn a todo from plan Z", "decompose plan into items",
           "break X into tasks".
   Example: "Spawn a todo from the migrate-auth plan".

H. EXECUTE         → exec-agent
   Shapes: "drive the todo to completion", "execute the work",
           "advance todo Z", "do the items".
   Example: "Drive the migrate-auth todo to completion".

I. EVALUATE        → eval-agent
   Shapes: "score whether Z met acceptance", "what's the verdict on
           todo Z", "did we meet the criteria?".
   Example: "Score whether the migration met acceptance".

J. RESEARCH        → research-agent
   Shapes: end-to-end multi-specialist arc, "investigate X end-to-end",
           "research and implement Y", "figure out and fix Z".
   Example: "Research how to reduce `bb tui` cold start and implement
            it" (this needs explore+plan+todo+exec+eval — research-agent
            owns the thread).

K. WORKFLOW        → workflow-agent
   Shapes: named multi-stage domain workflow ("feature-launch",
           "incident-response", "data-migration"), "run the X workflow",
           "kick off the Y runbook".
   Example: "Run the feature-launch workflow for feature F".

L. RLM             → rlm-agent
   Shapes: too-big context, "summarize across 200 files", "find pattern
           in a 50MB log corpus", "consolidate findings from N sources".
   Example: "Summarize all error patterns across logs/2026-Q2/*".

M. MEMORY          → memory-agent
   Shapes: "remember that X", "what do you remember about Y", "forget
           Z", explicit memory ops.
   Example: "Remember that we standardized on http-kit for server".

N. SKILL-LIFECYCLE → skill-agent
   Shapes: "create a skill that does X", "install skill from <url>",
           "sync skills". NOT for "what skills exist?" (that's EXPLORE).
   Example: "Create a skill that lints my markdown".

O. MCP-LIFECYCLE   → mcp-agent
   Shapes: "connect to MCP X", "restart MCP server Y", "call write-side
           MCP tool Z", "post to Slack".
   Example: "Restart the Linear MCP server".

P. INIT            → init-agent
   Shapes: "set up brainyard for this repo", "bootstrap BRAINYARD.md",
           "init from .env".
   Example: "Set up brainyard for this project".

Q. CONFIG          → config-agent
   Shapes: "update default model", "snapshot config", "revert config".
   Example: "Set default model to claude-sonnet-4-6".

R. ACP             → acp-agent
   Shapes: "use ACP backend X", "forward this to my <named external>
           agent".
   Example: "Forward this to my Cursor agent over ACP".

S. META-RESUME     (answer channel — no specialist call)
   Shapes: "what was that artifact path again?", "what did we decide
           about X?", continuation of a prior session arc.
   Example: After a plan/todo/exec arc completed: "Where's the verdict
            stored?" → read routing.log + pointers.md, answer directly.

T. CLARIFY         (answer channel)
   Shapes: the question is too underspecified to route. Ask 1–2
           targeted questions BEFORE picking a move. The loop exits;
           user replies; you resume next turn.

U. TOOL-LIFECYCLE  → tool-agent
   Shapes: "make me a tool that …", "add a command for …", "what tools
           have I built", "fix my <name> tool", "delete <name>". For
           user-defined (fn [args] …) tools under .brainyard/tools. NOT
           skills (N), NOT MCP (O), NOT one-off inline computation.

V. AGENT-LIFECYCLE → meta-agent
   Shapes: "make me an agent that …", "what agents have I built", "tweak
           my <name> agent", "delete <name>". For user-defined
           CoAct-derived agents (personas = instruction + tool-context)
           under .brainyard/agents/user$agent. A whole reusable
           specialist, NOT a single tool (U), skill (N), or MCP (O). Do
           NOT route here to merely USE an existing user agent — call it.

The self-answered shape tokens (the only ones you ever name — see ROUTING LOG
below) are: :direct-answer :tool-fetch :code-compose :meta-resume :clarify.
(Specialist moves don't need a token — the hook derives the shape from which
specialist you dispatched.)

────────────────────────────────────────────────────────────────────────────
ROUTING LOG — recorded automatically (you do NOT assemble a log line)
────────────────────────────────────────────────────────────────────────────
A hook records the per-turn routing line for you, derived from this turn:
which specialist you dispatched (→ routed-to + shape), the `Saved <kind>:`
artifact it returned, and your one-sentence routing reason. You do NOT call any
log helper (main$append-log is retired). Your only obligations:

1. State the one-sentence routing REASON in your :answer (you do this already —
   see HAND-OFF SURFACING: 'what you did, one-sentence routing decision'). The
   hook lifts that line.
2. For a SELF-ANSWERED move (DIRECT-ANSWER / TOOL-FETCH / CODE-COMPOSE /
   META-RESUME / CLARIFY) there is no dispatch for the hook to infer the shape
   from, so add ONE line to your :answer:
       Routing: <shape> — <reason>
   e.g. `Routing: direct-answer — greeting, no specialist needed`. The hook
   parses the shape + reason from it.
3. Specialist `Saved <kind>: <path>` lines are ALSO auto-captured into
   pointers.md by a separate hook. You may call `main$append-pointer` explicitly
   for an artifact you computed yourself, but it's rarely needed.

Continuation detection still works: `main$resume?` / `main$last-shape` read the
hook-written routing.log. Failing to state a reason just yields a thinner log
entry — never a failed turn.

────────────────────────────────────────────────────────────────────────────
PASSING CONTEXT TO SPECIALISTS
────────────────────────────────────────────────────────────────────────────
Every specialist call's :agent-context should include:

    User session: <session-id>
    Routing log: .brainyard/agents/main-agent/<session-id>/routing.log
    [Optional] Prior artifacts:
      - plan:    <path>
      - todo:    <path>
      - explore: <path>
    [Optional] Hint: <any direction-specific guidance>

For research-agent / workflow-agent specifically, you may pass an
existing dossier id (`@<id>`) when the user is resuming. The specialist
parses the id and skips its own bootstrap.

Do NOT inline a sibling specialist's dossier body into :agent-context —
pass the path. The specialist read-files what it needs.

────────────────────────────────────────────────────────────────────────────
HAND-OFF SURFACING (after every specialist call)
────────────────────────────────────────────────────────────────────────────
The specialist's answer always ends with a `Saved <kind>: <path>` line.
Surface it VERBATIM in your :answer, plus a one-paragraph distillation
of the specialist's headline finding. The user should see:

1. What you did (one-sentence routing decision).
2. The specialist's distilled result.
3. The durable artifact path.
4. The optional follow-up move (e.g. "next: `(eval-agent {…})` to score").

Example :answer:

    Routed to plan-agent — your question reduced to plan authoring.

    plan-agent drafted a 6-item approach focused on incremental migration
    with feature-flag fallback. Post-flight verdict: :pass. Acceptance
    carries forward as 3 criteria (a1 schema-compat, a2 zero-downtime,
    a3 rollback rehearsed).

    Saved plan: .brainyard/agents/plan-agent/plans/migrate-auth.md
    Saved dossier: .brainyard/agents/plan-agent/dossiers/20260516-091412-migrate-auth.md

    Next: `(todo-agent {:question "spawn a todo from migrate-auth"})` when
    you're ready to decompose.

────────────────────────────────────────────────────────────────────────────
HARD RULES
────────────────────────────────────────────────────────────────────────────
1. STAY FLAT — no clone-self dispatch. `query$clone` clones main-agent itself
   = clone-self recursion, and is omitted from your roster. Cross-specialist
   dispatch is direct kebab-case on each registered defagent —
   `(plan-agent {…})`, `(research-agent {…})`, etc.

2. NO direct writes to sibling-specialist storage:
     .brainyard/agents/plan-agent/plans/        .brainyard/agents/plan-agent/dossiers/
     .brainyard/agents/todo-agent/todos/        .brainyard/agents/todo-agent/dossiers/
     .brainyard/agents/exec-agent/dossiers/
     .brainyard/agents/eval-agent/verdicts/     .brainyard/agents/eval-agent/dossiers/
     .brainyard/agents/edit-agent/edits/
     .brainyard/agents/explore-agent/results/
     .brainyard/agents/research-agent/<id>/     .brainyard/agents/workflow-agent/<id>/
   These are owned by their respective specialists. You read-file them
   freely; you NEVER write them. Reach for the specialist via direct
   kebab-case dispatch when new content is needed.

3. DO NOT re-derive routing decisions silently. Always STATE a one-sentence
   reason for the move in your :answer — the routing-log hook records it as
   your contract with the user (they can audit why you picked research-agent
   vs. plan-agent). You don't write the log line; you must supply the reason.

4. DO NOT solve a multi-specialist problem inline. If a question
   genuinely needs explore + plan + todo + exec + eval, route to
   research-agent. Don't hand-roll the arc inside main-agent.

5. DO NOT swallow a specialist's `Saved <kind>: <path>` line. Always
   surface it in your :answer — the user needs the durable path to
   resume on a later turn.

6. CITE EVERYTHING. Every routing decision points at a question shape
   in §6's decision table. Every artifact reference points at a
   specialist's emitted `Saved …` line. No invented paths.

7. Iteration budget: 20 by default (CoAct's default). Most user
   questions resolve in 1–3 iterations. If you cross 80% of budget
   without finalizing — STOP and route to research-agent for the
   remaining arc; do not improvise.

8. CLARIFY OVER SPECULATE. If the question is ambiguous, ask 1–2
   targeted questions in your :answer before routing. A misrouted
   specialist call wastes more time than the round-trip.

9. NO task$run for specialists. Specialist defagents return promptly;
   wrapping them in task$run forces you to poll, which trips the loop
   guard. Use direct kebab-case dispatch or the tool channel JSON.
   task$run is for arbitrary bash subprocesses only.

────────────────────────────────────────────────────────────────────────────
RESUMING A SESSION
────────────────────────────────────────────────────────────────────────────
The agent-session is shared across turns; routing.log persists. On
every turn after the first:

1. Read routing.log frontmatter + last 20 lines.
2. Read pointers.md to know what artifacts exist.
3. Detect whether the current question is a continuation (most common:
   "now do X with the thing we just did") or a pivot (less common:
   "OK, different topic: …"). Continuation = re-route to the same
   specialist family; pivot = fresh decision-table consult.
4. Surface a one-sentence "session context" in :thought before the
   first move of the turn.
```

---

## 7. Tool-Context (How to Use the Bound Tools)

```text
## Main-agent tools — specialists + routing-log substrate

### Specialists (direct kebab-case dispatch — NOT bound directly)
All registered specialist `defagent`s are reachable by name. See §6 for the
full per-agent decision table. Headline:

- explore-agent    → discovery across files / web / MCP / skills.
- edit-agent       → safe single-file edit.
- plan-agent       → plan authoring (pre/post-flight gated).
- todo-agent       → decomposition (pre/post-flight gated).
- exec-agent       → advance a todo (per-item routing).
- eval-agent       → verdict against acceptance.
- research-agent   → end-to-end multi-specialist research.
- workflow-agent   → domain-template multi-stage workflow.
- rlm-agent        → MapReduce over too-big context.
- skill-agent      → skill lifecycle (write/install).
- mcp-agent        → MCP lifecycle + write-side calls.
- tool-agent       → user-defined tool lifecycle (tool-agent$* author/refine/remove).
- meta-agent       → user-defined agent/persona lifecycle (meta-agent$*).
- memory-agent     → long-term memory read/write.
- init-agent       → project bootstrap.
- config-agent     → .brainyard/config tuning.
- acp-agent        → ACP external-backend bridge.
- coact-agent      → bare-substrate fallback (rarely used).
- react-agent      → classic ReAct fallback (rarely used).

Invocation pattern (all identical):
    (<agent-name> {:question      "<directed sub-question>"
                   :agent-context "User session: <session-id>\n
                                   Routing log: .brainyard/agents/main-agent/<session-id>/routing.log\n
                                   [Optional Prior artifacts: …]\n
                                   [Optional Hint: …]"})

### Routing-log substrate (your direct work surface)
- read-file      -- Read routing.log / pointers.md (your own dir only)
                    or sibling dossier files (read-only).
- write-file     -- Update pointers.md. USE :append true for both
                    routing.log entries (NDJSON) and pointers.md bullets.
- update-file    -- Rarely needed — both your files are append-only.
- grep           -- Cheap content scan inside your own log files.
- bash           -- mkdir -p, ls, find. NOT for builds or test runs —
                    those go to exec-agent (with edit-agent for writes).
- search         -- Cross-project keyword search (rare — usually use
                    explore-agent instead).

### Sibling dossier read-helpers (cherry-picked, READ-ONLY)
- plan$read-dossier   -- Parse plan-agent dossier frontmatter.
                          Returns :post.acceptance, :post.verdict.
                          Use to detect "plan exists; is post-flight pass?"
                          before routing to todo-agent.
- todo$read-dossier   -- Parse todo-agent dossier frontmatter.
                          Returns :post.acceptance_coverage. Use to
                          detect "todo exists; route to exec?"
- exec$read-dossier   -- Parse exec-agent dossier frontmatter.
                          Returns :execute.evidence, :post.acceptance_progress.
                          Use to detect "exec ran; route to eval?"
- eval$read-verdict   -- Parse eval-agent verdict frontmatter.
                          Returns :verdict, :confidence, :criteria,
                          :recommendations. Use to detect "did acceptance
                          pass; should we route back to plan/todo/exec?"
- edit$read-record    -- Parse an edit-agent record. Returns :apply
                          :verify :rollback for diff-level audit.

### Synthesis
- query$llm      -- Cross-specialist synthesis. Use sparingly — most
                    distillation happens inside specialists. Reach for
                    it when reconciling two specialist outputs for the
                    user's final-answer view.

### Bootstrap & discovery
- list-tools, get-tool-info, search — generic registry access.
- call-tool                          — generic dispatch from a clojure
                                       fence. Prefer direct kebab-case
                                       dispatch; reach for call-tool
                                       when you're already constructing
                                       args dynamically.

### Web
- web-search, fetch-url — direct one-off lookups when a single fact is
                          needed mid-routing. Multi-source discovery
                          routes through explore-agent.

### Background tasks
- task$run (:job-type :bash) — async wrapper for arbitrary long-running
                                bash subprocesses (build, log scan, data
                                pipeline). NEVER wrap a specialist defagent
                                in task$run — specialists are reached by
                                direct kebab-case dispatch / the tool channel;
                                polling for their result trips the loop guard
                                (Hard Rule 9).

### Runtime config
- agent-runtime$config — view (no args) or tune (`:key`/`:value`)
                         settings. Tune `:max-iterations` mid-run if
                         a question's arc legitimately needs more.

## main$* helpers (auto-bound in the sandbox)

The per-turn routing LINE is hook-recorded — there is NO `main$append-log`
helper. These accessors/probes remain (all read-only except append-pointer);
use them in clojure fences instead of inlining mkdir / write-file logic.

| Helper | Signature | What it does |
|---|---|---|
| `main$session-id` | `(main$session-id)` → `{:session-id "<id>"}` | Returns current agent-session id. |
| `main$resume?` | `(main$resume? :session-id …)` → `{:exists? bool :turn-count int :last-shape kw :last-artifact "<path>"}` | Cheap probe — does the routing-log dir exist + its last state? |
| `main$bootstrap` | `(main$bootstrap :session-id …)` → `{:dir … :log-path …}` | Creates dir + initial routing.log + pointers.md. Idempotent. Normally unnecessary — the `:agent.session/created` hook bootstraps for you. |
| `main$append-pointer` | `(main$append-pointer :session-id … :path "<path>" :caption "<one-line>")` → `{:appended true}` | One bullet into pointers.md. Usually the `:agent.tool-use/post` hook captures specialist `Saved <kind>:` lines for you. |
| `main$last-shape` | `(main$last-shape :session-id …)` → `{:shape :plan-author :routed-to … :artifact … :turn N}` | Last routing decision in the current session — drives continuation detection. |
| `main$index-append` | `(main$index-append :session-id … :turn-count N :shapes [...])` → `{:appended true}` | Appended on session close by the `:agent.session/closed` hook; you rarely call it. |

ROUTING LINE (hook-recorded, not a helper): an `:agent.ask/post` hook appends
the per-turn routing.log line — routed-to + shape (from your dispatch),
artifact (the surfaced `Saved <kind>:` path), and your one-sentence reason. For
a SELF-ANSWERED move, add `Routing: <shape> — <reason>` to your :answer so the
hook can record the shape.

## Typical flow (no specific iteration count required)
1. iter 1 — probe the session (main$resume?); surface session context in
   :thought. (The routing-log dir is already bootstrapped by a hook.)
2. iter 2 — pick a decision-table move; either route to a specialist
   (tool / code channel invocation) or answer directly (answer channel).
3. State your one-sentence routing reason in :answer (and a `Routing: <shape>
   — <reason>` line for self-answered moves). The hooks record the routing.log
   line + capture any `Saved <kind>:` paths into pointers.md.
4. iter N — finalize :answer with the specialist's surfaced result + the
   durable artifact path + the recommended next move.
```

---

## 8. `(main$*)` Sandbox Helpers

Mirrors the helpers introduced in `research-agent`, `workflow-agent`, and `explore-agent`. They live in `ai.brainyard.agent.common.main`, register as `defcommand`s, and surface in the sandbox via auto-binding. They are **read seams + accessors** — the only structured-authoring helper, `main$append-log`, was **retired as an LLM tool** in the redesign: a private `append-log!` fn renders the same NDJSON line and is called only from the routing-line hook (§5, §10.4). The bound roster (`main-helpers`) is `main$session-id`, `main$resume?`, `main$bootstrap`, `main$append-pointer`, `main$last-shape`, `main$index-append`.

| Helper | Signature | What it does |
|---|---|---|
| `main$session-id` | `(main$session-id)` → `{:session-id "<id>"}` | Returns the current agent-session id (reads `*current-agent*` and pulls the `:session-id` via `proto/session-id`). A runtime accessor — the model cannot construct it. |
| `main$resume?` | `(main$resume? :session-id "<id>")` → `{:exists? bool :line-count int :turn-count int :last-shape kw :last-artifact "<path>"}` | Cheap probe: does the routing-log dir exist? How many lines/turns has it seen? What was the last decision shape/artifact? Single pass over `routing.log`. |
| `main$bootstrap` | `(main$bootstrap :session-id "<id>")` → `{:dir "<path>" :log-path "<path>" :pointers-path "<path>" :exists? bool}` | Creates `.brainyard/agents/main-agent/<session-id>/` with empty `routing.log` and a header in `pointers.md`. Idempotent — if dir exists, returns its current state instead of overwriting. **Normally driven by the `:agent.session/created` hook**, so the model rarely calls it. |
| `main$append-pointer` | `(main$append-pointer :session-id … :path "<path>" :caption "<one-line>")` → `{:appended true}` | Appends one markdown bullet to `pointers.md` with a timestamp + caption. Usually unnecessary — the `:agent.tool-use/post` hook auto-captures specialist `Saved <kind>:` lines. |
| `main$last-shape` | `(main$last-shape :session-id …)` → `{:exists? bool :shape kw :routed-to "<agent>" :artifact "<path>" :turn N :iter M :question "…" :reason "…"}` | Reads + parses the last line of `routing.log`. Drives continuation detection ("user said 'now do X with the thing' — what was the thing?"). |
| `main$index-append` | `(main$index-append :session-id … :turn-count N :shapes [:plan-author :decompose :execute])` → `{:appended true}` | Append a one-line entry to `.brainyard/agents/main-agent/INDEX.md`. Driven by the `:agent.session/closed` hook. |
| `coerce-shape` / `append-log!` (private) | `(append-log! :session-id … :turn … :shape … :routed-to … :artifact … :reason …)` | NOT bound as an LLM tool. `append-log!` renders one NDJSON line (coercing `:shape` via `coerce-shape` against the 22-keyword `valid-shapes` set → `:unspecified` on a miss); the routing-line hook is its sole caller. |

The model works with read seams + accessors only; the routing-log mechanics no longer have to be inlined every iteration, and there is **no per-turn structured-authoring obligation**.

---

## 9. Behavior Tree — Inherited As-Is

`main-agent` does **not** define its own BT. `run-coact-derived` falls back to `coact-agent`'s `:bt-factory`:

```
coact-behavior-tree
  ├─ preflight (question-present?)
  ├─ prepare-conversation / prepare-recalled-memory
  ├─ coact-init-action
  ├─ coact-loop-subtree            ; ThinkActCode → router → accumulate
  ├─ answer-present?
  ├─ optional finalize pass
  ├─ coact-store-results-action
  └─ trace/default-maintain-conversation
```

The `coact-loop-subtree`'s router (answer / code / tool / repair) handles all four channels main-agent uses; the decision table in the instruction (§6) tells the LLM *which* channel to pick per question shape and *which specialist to dispatch* when the tool channel is the answer.

Default iteration cap stays at **20** — main-agent is a router, not a worker. Most turns resolve in 1–3 iterations. The cap can be raised via `agent-runtime$config :key "max-iterations" :value "N"` for sessions where the user wants main-agent to drive light pre-research before delegating.

No new BT actions, no new schemas, no SCI binding additions are required for the baseline (beyond the optional `main$*` helpers in §8).

---

## 10. Hooks Integration

main-agent uses the existing hooks registry (see `docs/core/agent.md` §Hooks) — no new event types. They ship in `common/main_agent_hooks.clj`, all `:source ::main-agent`, self-installing at namespace load via `(install!)` and each wrapped in try/catch so a hook failure never propagates into the user answer. **Five** handlers ship (the redesign added the `:agent.ask/pre` snapshot + the `:agent.ask/post` routing-line recorder — together they make the routing log hook-derived, retiring `main$append-log`):

| Event | Handler | Role |
|---|---|---|
| `:agent.session/created` | `routing-log-bootstrap` | Bootstrap the routing-log dir before the first turn (idempotent). |
| `:agent.ask/pre` | `capture-pre-turn` | Snapshot `max(turn)` in `routing.log` onto the agent `!state` so the post hook knows the next turn number + can no-op a double-fire. |
| `:agent.tool-use/post` | `capture-saved-artifacts` | Parse specialist `Saved <kind>:` lines → `pointers.md` bullets. |
| `:agent.ask/post` | `record-routing-line` | **Derive + append the per-turn routing line** (the redesign's core hook; sole writer of `routing.log`). |
| `:agent.session/closed` | `finalize-index` | Append a turn-count + distinct-shapes summary to `INDEX.md`. |

### 10.1 `:agent.session/created` — bootstrap routing log

A side-effecting handler (`routing-log-bootstrap`) runs on session creation to ensure `.brainyard/agents/main-agent/<session-id>/` exists before the first user turn. Idempotent, and runs for every session (any agent may invoke main-agent later).

```clojure
(hooks/register-hook!
  :agent.session/created
  ::routing-log-bootstrap
  routing-log-bootstrap            ; (fn [{:keys [session-id]}] (main/main$bootstrap :session-id session-id))
  :source ::main-agent)
```

### 10.2 `:agent.tool-use/post` — capture `Saved <kind>:` lines

A read-only observer parses every sub-agent's result for `Saved <kind>: <path>` lines and writes the corresponding `pointers.md` bullet. This means main-agent's instruction doesn't have to inline the parsing logic — the hook does it as a side effect.

```clojure
(hooks/register-hook!
  :agent.tool-use/post
  ::capture-saved-artifacts
  capture-saved-artifacts          ; main-agent? + (specialist-agents tool-name) →
                                   ; (doseq [{:keys [kind path]} (main/parse-saved-lines answer)]
                                   ;   (main/main$append-pointer :session-id sid :path path :caption …))
  :source ::main-agent
  :match  (fn [{:keys [agent]}] (main-agent? agent)))
```

This decouples artifact tracking from instruction discipline — even if the LLM forgets to call `main$append-pointer` itself, the hook catches it. `pointers.md` was already hook-driven before the redesign and stays so.

### 10.3 `:agent.ask/pre` — snapshot the turn boundary

A tiny observer records `max(turn)` currently in `routing.log` onto the agent's `!state` (`::pre-max-turn`) so the post hook (§10.4) can compute the next turn number and no-op a double-fire.

```clojure
(hooks/register-hook!
  :agent.ask/pre ::capture-pre-turn capture-pre-turn
  :source ::main-agent
  :match  (fn [{:keys [agent]}] (main-agent? agent)))
```

### 10.4 `:agent.ask/post` — record the routing line (the redesign's core hook)

The **sole writer** of `routing.log`. main-agent no longer calls `main$append-log`; this hook derives the per-turn line from the turn itself (§5) and appends it via the private `main/append-log!`:

- **`routed-to`** — `routed-to-of`: the last specialist defagent in the turn's tool-calls (`nil` if self-answered).
- **`shape`** — `derive-shape`: a dispatched specialist is authoritative (`specialist->shape`); else the `Routing: <shape> — <reason>` answer line; else a channel fallback (`:code` → `:code-compose`, any tool call → `:tool-fetch`, else `:direct-answer`). All coerced through `coerce-shape` (→ `:unspecified` on a miss).
- **`artifact`** — the first `Saved <kind>: <path>` path (`parse-saved-lines`).
- **`reason`** — the `Routing:` reason, else the first prose line of the answer.
- **`question`** — the turn's input, capped at 200 chars.

```clojure
(hooks/register-hook!
  :agent.ask/post ::record-routing-line record-routing-line
  :source ::main-agent
  :match  (fn [{:keys [agent]}] (main-agent? agent)))
```

Because the hook observes rather than asks the model to author, the trail can't be forgotten — strictly better than the old "model skipped `main$append-log` entirely" failure mode. Shape validation moved from an eager hard-reject to a parse-time coerce (`:unspecified` fallback), so a mis-derived shape never fails the turn.

### 10.5 `:agent.session/closed` — finalize INDEX.md

Appends one line to `.brainyard/agents/main-agent/INDEX.md` summarizing the session's turn count and distinct routing shapes (no-op when main-agent was never invoked).

```clojure
(hooks/register-hook!
  :agent.session/closed
  ::finalize-index
  finalize-index                   ; reads main/read-routing-log → main/main$index-append
  :source ::main-agent)
```

---

## 11. Demonstration — Three Turns in One Session

### Turn 1 — User: *"Draft a plan to migrate the auth middleware off legacy session storage to JWT"*

#### Iteration 1 — Probe + route (code channel)

The routing-log dir is already bootstrapped by the `:agent.session/created` hook, so the model just probes for prior history before routing.

````markdown
```clojure
(def sid (:session-id (main$session-id)))
(main$resume? :session-id sid)
;; => {:exists? true :line-count 0 :turn-count 0}   ; fresh session, nothing to resume

;; Shape detection: "draft a plan to X" → F. PLAN-AUTHOR → plan-agent.
:next-move :plan-author
```
````

#### Iteration 2 — Dispatch plan-agent (tool channel)

````markdown
```json
[{"tool-name": "plan-agent",
  "tool-args": [{"name": "question",
                 "value": "Draft a plan to migrate the auth middleware off legacy session storage to JWT. Approach must enumerate compatibility, downtime, and rollback trade-offs."},
                {"name": "agent-context",
                 "value": "User session: sess-7f3a\nRouting log: .brainyard/agents/main-agent/sess-7f3a/routing.log\nHint: legal/compliance is the driving force per the user's earlier mention — favor compliance posture over ergonomics."}]}]
```
````

plan-agent runs, returns:

```
plan-agent drafted a 6-item approach with feature-flag fallback. Post-flight verdict: :pass.

Saved plan: .brainyard/agents/plan-agent/plans/migrate-auth-jwt.md
Saved dossier: .brainyard/agents/plan-agent/dossiers/20260516-091412-migrate-auth-jwt.md
```

#### Iteration 3 — Finalize (answer channel)

main-agent writes no log line itself. It just surfaces the specialist's result + its one-sentence routing reason; the `:agent.ask/post` hook then derives and appends the routing.log line (`routed-to: plan-agent`, `shape: plan-author`, the captured plan path, the reason lifted from the first prose line), and the `:agent.tool-use/post` hook has already captured the `Saved`-line bullets into `pointers.md`.

Final `:answer`:

```markdown
Routed to plan-agent — your question reduced to plan authoring.

plan-agent drafted a 6-item approach with feature-flag fallback for incremental
migration, 3 acceptance criteria (schema-compat, zero-downtime, rollback-rehearsed),
and explicit trade-offs between compatibility and rollout speed. Post-flight verdict:
:pass.

Saved plan: .brainyard/agents/plan-agent/plans/migrate-auth-jwt.md
Saved dossier: .brainyard/agents/plan-agent/dossiers/20260516-091412-migrate-auth-jwt.md

Next: `(todo-agent {:question "spawn a todo from migrate-auth-jwt"})` when you're
ready to decompose.
```

The hook derives `{turn 1, iter 1, question "draft a plan…", routed-to "plan-agent", shape :plan-author, artifact ".../migrate-auth-jwt.md", reason "Routed to plan-agent — your question reduced to plan authoring."}` and appends it. No map was built by the model.

### Turn 2 — User: *"now spawn a todo from it"*

#### Iteration 1 — Resume + continuation detection (code channel)

````markdown
```clojure
;; main$last-shape sees :plan-author + plan path. "now spawn a todo from it" is
;; a clear continuation → G. DECOMPOSE → todo-agent.
(def last (main$last-shape :session-id sid))
;; => {:shape :plan-author :routed-to "plan-agent"
;;     :artifact ".brainyard/agents/plan-agent/plans/migrate-auth-jwt.md"}

:next-move :decompose
```
````

#### Iteration 2 — Dispatch todo-agent (tool channel)

````markdown
```json
[{"tool-name": "todo-agent",
  "tool-args": [{"name": "question",
                 "value": "Spawn a todo from the migrate-auth-jwt plan. Decompose the 6-item approach with per-item :tags routing."},
                {"name": "agent-context",
                 "value": "Saved dossier: .brainyard/agents/plan-agent/dossiers/20260516-091412-migrate-auth-jwt.md\n\nUser session: sess-7f3a\nRouting log: .brainyard/agents/main-agent/sess-7f3a/routing.log\nPrior artifacts:\n  - plan: .brainyard/agents/plan-agent/plans/migrate-auth-jwt.md"}]}]
```
````

todo-agent returns with `Saved todo: …` + `Saved dossier: …`. main-agent surfaces the lines and the post.acceptance_coverage summary in the final `:answer`.

### Turn 3 — User: *"what was that verdict path again?"*

#### Iteration 1 — Meta-resume (answer channel — no specialist call)

````markdown
```clojure
(def log-lines (read-file {:path ".brainyard/agents/main-agent/sess-7f3a/routing.log"}))
;; Scan log: no eval-agent entry yet → there is no verdict for this todo yet.

:next-move :meta-resume
```
````

`:answer`:

```markdown
No verdict has been produced yet — the migration arc has gone plan → todo. To
produce a verdict, you'd need to run `(exec-agent {…})` to drive the todo to
completion first, then `(eval-agent {…})` to score it against acceptance.

Session pointers so far (most recent first):
- 2026-05-16 09:31 [todo body — migrate-auth-jwt](computer://...) (`post.acceptance_coverage`: 6/6)
- 2026-05-16 09:14 [plan body — migrate-auth-jwt](computer://...) (`post.verdict`: :pass)

Want me to route to exec-agent and start driving the todo?

Routing: meta-resume — answered from routing.log; no verdict exists yet.
```

Note: turn 3 uses NO specialist — main-agent answers from the routing log directly. This is `S. META-RESUME` from §6's decision table. Because there's no dispatch for the hook to infer from, the model adds the `Routing: meta-resume — …` line so the `:agent.ask/post` hook records the shape (`routed-to: null`).

---

## 12. Failure Modes & Mitigations

| Failure | Symptom | Mitigation |
|---|---|---|
| **Misrouting** | Routed to research-agent for a question that was really a one-shot tool call. Wasted iterations + specialist dossier created for nothing. | Decision-table in §6 is concrete; reason field in routing.log is auditable. Iteration cap of 20 means even a misroute caps wasted work. Hard Rule 4 prevents main-agent itself from solving multi-specialist arcs inline. |
| **Specialist returns no `Saved …` line** | pointers.md captures nothing; user sees a result but no durable artifact. | Decision-table § for specialists that emit artifacts is explicit. If a specialist's result lacks the contract line, log the move with `artifact: null` and flag to user. |
| **Routing log corruption** | NDJSON parse fails on resume. | `main$resume?` validates the log; on parse failure, falls through to `:exists? false :recovery-needed true`. The instruction handles this as a CLARIFY (T): surface the broken state and offer to bootstrap fresh. |
| **Cycle: user keeps asking variants of the same question** | Same shape routes to the same specialist repeatedly with no progress. | The loop-guard hook (`common/loop_guard_hook.clj`) is already registered as a `:agent.tool-use/pre` gate and catches pathological repeat-call patterns. main-agent inherits it for free. |
| **Specialist hits its own iteration cap mid-arc** | User sees `Hold:` or `abandoned` status without main-agent's framing. | Specialist's `Saved dossier:` line is surfaced verbatim; main-agent's `:answer` adds a one-paragraph "what this means + recommended next move" layer on top. |
| **Concurrent main-agent instances in the same session** | Routing log race. | `routing.log` writes use `:append true` (atomic on POSIX for small writes); the per-session directory has a lock (mirroring `agent-tui-persist`'s session-store lock). |
| **User pivots mid-arc** | "OK forget that, different question" — but routing.log shows the prior arc as in-flight. | Continuation detection in §6 explicitly handles pivots — fresh decision-table consult, log the pivot reason. The prior specialist's artifacts remain on disk; they're not "cancelled", just not continued. |

---

## 13. Differences from coact-agent

main-agent is a thin specialization of coact-agent. The diffs:

| | coact-agent | main-agent |
|---|---|---|
| **Instruction** | Tool / code / answer channel mechanics + sandbox contract + critical rules. | All of coact-agent's, **plus** the §6 decision table + §7 specialist roster context + routing-log discipline. |
| **Tool roster** | Generic CoAct tools (file/shell/web/bootstrap/invocation/discovery). | coact-agent's roster **plus** the read-only sibling dossier helpers + the `main$*` helpers namespace (read seams/accessors — no `main$append-log`). |
| **State** | None of its own; sandbox `def`s reset per agent instance. | Persists `.brainyard/agents/main-agent/<session-id>/routing.log` + `pointers.md` — both **hook-written**, not LLM-authored. |
| **Default `:max-iterations`** | 20. | 20 (most turns finish in 1–3). |
| **Hooks** | Inherits the global registry. | Adds **five** handlers (`:agent.session/created`, `:agent.ask/pre`, `:agent.tool-use/post`, `:agent.ask/post`, `:agent.session/closed`) under `:source ::main-agent` — the `:agent.ask/post` recorder is the sole writer of `routing.log`. |
| **clone-self** | `query$clone` available (clones coact-agent). | `query$clone` excluded (gated to rlm-agent; not in roster) — Hard Rule 1 phrased as "STAY FLAT — no clone-self dispatch." |

The minimal diff is intentional. Anything coact-agent already does well (channel discipline, sandbox state, parallel blocks, finalize) main-agent inherits unchanged. The only new thing is the *routing knowledge*.

---

## 14. Migration — Complete

main-agent shipped as additive, alongside coact-agent — no code was removed.

### 14.1 The lightweight, hook-derived redesign (2026-06, done)

The redesign removed main-agent's last per-turn structured-authoring obligation. It landed as:

1. **Routing-line hook.** `record-routing-line` on `:agent.ask/post` (plus the `:agent.ask/pre` `capture-pre-turn` snapshot) became the **sole writer** of `routing.log`, deriving the line from the turn (§5, §10.4).
2. **`main$append-log` retired** as an LLM tool. Its NDJSON rendering moved to the private `main/append-log!`, called only from the hook. The bound roster (`main-helpers`) dropped it.
3. **Instruction simplified** — the per-turn "assemble + submit a log map" obligation became "state a one-sentence reason (you already do); add a `Routing: <shape> — <reason>` line for self-answered moves."
4. **`routing.log` format unchanged** (same NDJSON keys) — only the *writer* moved from the model to the hook, so `main$resume?` / `main$last-shape` parse it identically. **No data migration.**

The change paired naturally with the base-substrate rollout (main-agent is the chief beneficiary of the inline track/execute/edit substrates — see [the synthesis](./agent-lightweight-redesign-synthesis.md)), but was independent and could land any time.

### 14.2 Front-door entry & compatibility

- **Sub-agent dispatch unaffected.** coact-agent is a leaf-level reasoning style; nothing dispatches to it as a substrate. main-agent is the *user-facing* front door, not a substrate. coact-agent stays available via explicit `-a coact-agent`.
- **Compatibility with `bb tui ask`.** `bb tui ask -m opus 'What is 2+2?'` works — main-agent's `A. DIRECT-ANSWER` move handles it in one iteration via the answer channel; the routing-log overhead is one hook-written NDJSON line.
- **ACP & web app.** `bases/agent-web` and `bases/acp-stub-agent` choose their own entry agent; a backend wanting the front-door behavior switches its entry to `main-agent`. Default per-base resolution lives in each base's `core.clj`.

---

## 15. File Map

| File | Purpose |
|---|---|
| `components/agent/src/ai/brainyard/agent/common/main_agent.clj` | The `defagent` form + instruction + tool-context + roster wiring. Routing-log obligation is hook-recorded; the instruction states only the reason + the `Routing:` self-answer convention. |
| `components/agent/src/ai/brainyard/agent/common/main.clj` | The `main$*` helpers (`main$session-id`, `main$resume?`, `main$bootstrap`, `main$append-pointer`, `main$last-shape`, `main$index-append`) as `defcommand`s, plus the **private** `append-log!` + `coerce-shape` (the 22-keyword `valid-shapes`) and `parse-saved-lines`. `main$append-log` is **not** a bound tool. |
| `components/agent/src/ai/brainyard/agent/common/main_agent_hooks.clj` | The **five** hook handlers (bootstrap / capture-pre-turn / capture-saved-artifacts / record-routing-line / finalize-index), self-installing at load. |
| `components/agent/test/ai/brainyard/agent/main_agent_test.clj` | Tests: routing-log bootstrap idempotency, decision-table dispatch, session continuation detection, hook-derived routing line + self-answer `Routing:` parsing + `:unspecified` fallback. |
| `docs/design/main-agent-design.md` | This document (as-built). |
| `docs/core/agent.md` | Lists `main-agent` in the built-in agents table. |

---

## 16. Open Questions

1. **Should main-agent surface a routing-decision "reason" to the user, or keep it internal to routing.log?** **Resolved (as-built):** the model states a one-sentence routing reason at the top of `:answer` ("Routed to plan-agent — your question reduced to plan authoring."), which the `:agent.ask/post` hook lifts into `routing.log` — so the reason is both user-visible and logged, with no separate authoring step. A `:show-routing? true|false` runtime knob to mute the user-facing line remains a possible follow-up.

2. **Should `agent-context` to specialists include the FULL routing.log path or a digest?** Path is cheaper but forces the specialist to read-file it. Digest in-line wastes context. Current proposal: path-only (specialists read-file as needed). May need revisiting if specialists complain about extra round-trips.

3. **How does main-agent interact with `acp-agent` when ACP is the front door?** Today an ACP client sends prompts directly to a brainyard agent. If the ACP backend wires its entry to `main-agent`, the routing-log lives on the brainyard side and the ACP client gets back final `:answer` strings + Saved-line paths. Multi-hop ACP (ACP → main-agent → ACP-bridged third-party agent) is out of scope for v1.

4. **Should main-agent's decision table be data-driven (EDN) instead of prompt-embedded?** Current proposal: embedded in the instruction (mirrors research-agent's approach). A future revision could lift `:shape :routed-to :examples` into `.brainyard/agents/main-agent/decision-table.edn` so power users can tune routing without forking the agent file. This is a "phase 3" idea.

5. **Should the read-only cherry-picks include `workflow$read-dossier` and `research$read-dossier` when those agents emit dossiers main-agent might inspect?** Probably yes — to be added once those agents stabilize their dossier formats (research-agent has; workflow-agent is still design-phase).

6. **What's the right default model for main-agent?** Routing is cheap (typically <500 tokens of LLM output per turn). A small/fast model (haiku) is plausibly fine and saves cost. But misroutes are expensive. Current proposal: same default as coact-agent (`claude-code:haiku`), with a runtime knob to bump to sonnet for high-stakes sessions.

---

## 17. Summary

`main-agent` is the front-door router for brainyard: a thin CoAct specialization whose entire job is to look at a user question and pick the right specialist (or coact-agent's own channels) per question shape. The substrate, the loop, the sandbox — all inherited from coact-agent. What it adds:

- A curated **decision table** in the instruction (§6) covering 22 question shapes (A–V, including `tool-agent` and `meta-agent`) and the right move for each. Routing is pure LLM judgment.
- A **self-recording routing log** under `.brainyard/agents/main-agent/<session-id>/` that gives session continuity, audit trail, and continuation detection — **hook-written, not LLM-authored**. The model routes and states a reason; the `:agent.ask/post` hook derives and appends the structured line (`main$append-log` retired).
- A **flat dispatch model** — every other `defagent` is reachable by direct kebab-case call, no recursion, no clone-self.
- **Five hook handlers** that own routing-log discipline entirely (bootstrap on session-created, snapshot on ask/pre, capture Saved-lines on tool-use/post, **record the routing line** on ask/post, finalize INDEX on session-closed).

The root agent ends each turn having done only judgment — pick the route, state the reason — while the structured trail records itself. The user no longer has to pre-classify their question into the right `-a <agent>` flag: they ask, and the right specialist runs. The specialists keep their pre/post-flight gating, their dossiers, their hard rules. main-agent owns the seam.
