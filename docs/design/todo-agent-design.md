# Todo-Agent — Pre-flight & Post-flight Gated Todo Authoring with Dossier Handoff (CoAct-derived)

> **Status:** Shipped — `todo-agent` is registered in `components/agent` (`common/todo_agent.clj`). This document is the original design proposal (revision 2); the shipped implementation may diverge in details. See [core/agent.md](../core/agent.md) for the current roster.
>
> **As-built (verify against `common/todo_agent.clj`, `common/todo.clj`):**
> - **POST-FLIGHT verdict is PASS / HOLD only.** The design's REVISE auto-round (§6.3) is **deferred to v1.5** — shipped: "1+ fail → HOLD," no automatic split/retag round.
> - **C7 (NO VETO) is INFORMATIONAL, not a GATHER gate.** The shipped pre-flight records `:no-auto-spawn?` but does not block; the design's "GATHER for explicit confirm" is a future tightening.
> - **Todo CRUD is `doc$*` with `:kind :todo`** (see the API-rename block below). SPAWN uses `doc$create :kind :todo`; ADVANCE uses `doc$update :kind :todo` (`:item-idx`/`:item-done`, `:add-item`, `:goal`, `:status`). Item `:tags {:via :covers}` ship as designed.
> - **Cross-agent dispatch is direct kebab-case** — `(plan-agent {…})`, `(exec-agent {…})` — not `call-tool`. Hard Rule 4 reads "NO clone-self dispatch."
> - **`write-file` / `update-file` / `fetch-url` are NOT bound** — todos and dossiers go through `doc$*` + `todo$dossier-*` (Hard Rule 5).
> - **Shipped helper roster:** `todo$dossier-slug`, `todo$dossier-frontmatter`, `todo$dossier-write`, `todo$dossier-index-append`, `todo$read-dossier`, `todo$next-handoff`. No `todo$preflight` / `todo$postflight` helpers shipped (despite §12 listing them).
> - **An `:agent.ask/post` auto-persist hook** (not in this design) reconstructs a minimal dossier from the answer text if the helpers were skipped, and flags hallucinated `Saved dossier:` paths. It is a safety net, not the primary path.
> **Scope:** redesign of `components/agent/src/ai/brainyard/agent/common/todo_agent.clj` + `todo.clj`
> **Built on:** `coact_agent.clj` via `coact/run-coact-derived`
> **Sibling of:** `plan-agent`, `exec-agent`, `eval-agent`, `edit-agent`, `explore-agent`
> **Related reading:** `docs/plan-agent-design.md` (dossier schema is the template), `docs/exec-agent-design.md`, `docs/explore-agent-design.md`, `docs/edit-agent-design.md`

> **API rename (2026-05):** the per-verb `todo$list / todo$read / todo$create / todo$update-item / todo$update-goal / todo$add-item / todo$status / todo$complete / todo$abandon / todo$reopen / todo$reset-item / todo$delete / todo$exists` shims have been removed. Use the polymorphic `doc$*` family with `:kind :todo`:
> - `todo$list` / `todo$read` / `todo$create` / `todo$delete` → `(doc$list|read|create|delete :kind :todo …)`
> - `todo$update-item` → `(doc$update :kind :todo :slug … :item-idx N :item-done true)`
> - `todo$update-goal` → `(doc$update :kind :todo :slug … :goal "…")`
> - `todo$add-item` → `(doc$update :kind :todo :slug … :add-item "…" [:after-idx N])`
> - `todo$status` → use `:progress` map returned by `(doc$read :kind :todo …)`
> - `todo$complete` / `todo$abandon` / `todo$reopen` → `(doc$update :kind :todo … :status :completed|:abandoned|:reopen)`
> - `todo$reset-item` → `(doc$update :kind :todo :slug … :item-idx N :item-done false)`
> - `todo$exists` → check `:not-found true` on the result of `(doc$read :kind :todo …)`
> The dossier helpers (`todo$dossier-*`, `todo$read-dossier`, `todo$next-handoff`, `todo$preflight`, `todo$postflight`) are NOT deprecated and keep their per-verb names. The body of this doc still references the old `todo$*` names for historical clarity; treat them as the doc$* equivalents above.

---

## 1. Motivation

The current `todo-agent` (`components/agent/.../common/todo_agent.clj`) is a thin wrapper over the `todo$*` command set. Its instruction asks the LLM to "derive items from the plan's `## Approach`" — and trusts the LLM to do that well. Three problems surface:

1. **Items are spawned without confirming the plan is sound.** Today the agent reads the plan body via `plan$read` and immediately starts authoring items. If the plan was authored before pre/post-flight gating (`docs/plan-agent-design.md`), or if the user dispatches todo-agent against a half-baked plan, the resulting items inherit every weakness of the source. The executor then fails, and the failure looks like an exec-agent bug rather than a planning gap.
2. **No post-flight check on the items themselves.** Items are written and the agent stops. There is no pass that asks: *Does each item map to a `## Approach` bullet? Are items truly atomic? Do the items collectively cover every `## Acceptance` criterion? Is the count sane?* These are exactly the things that make exec-agent's life easy or miserable.
3. **The handoff to exec-agent is unstructured.** Today exec-agent receives a slug or a path in `:agent-context` and re-derives everything — the plan body, the acceptance criteria, the list of items. There is no schema'd channel where todo-agent can tell exec-agent "items 0–2 are read-only discovery, items 3–7 require edit-agent, item 8 is a deploy gate."

The same redesign also folds in the layout migration begun by `plan-agent`. Today todos live at `.brainyard/todos/`; the redesign moves them to `.brainyard/agents/todo-agent/todos/<slug>.md` and adds sibling `dossiers/`, `drafts/`, `INDEX.md`.

**Thesis.** Redesign `todo-agent` so every authoring or advancement run runs through a fixed three-phase pipeline that mirrors plan-agent's:

1. **PRE-FLIGHT (sufficiency check)** — has plan-agent's post-flight passed? Does the plan dossier exist and carry usable acceptance? Does a near-duplicate todo already exist? Output: GO / GATHER / REFUSE.
2. **AUTHOR / ADVANCE** — `todo$create` (spawn) or `todo$update-item` / `todo$add-item` / `todo$complete` / etc. (advance). Writes go to the new layout.
3. **POST-FLIGHT (confirmation check)** — re-read the just-authored items and self-critique against a fixed rubric: each item maps to an approach bullet, items are atomic, items collectively cover acceptance, count is sane. Output: PASS / REVISE / HOLD.

Every run produces a dossier under `.brainyard/agents/todo-agent/dossiers/<slug>-<ts>.md` that exec-agent consumes. Dossier schema follows plan-agent's template (`docs/plan-agent-design.md` §7.2) with todo-specific frontmatter additions.

---

## 2. Design Principles

1. **No items are authored on a half-baked plan.** Pre-flight requires either a plan-agent dossier with `post.verdict :pass` OR an explicit user override (`:plan-ok? true`) for plans that pre-date the dossier era.
2. **Items are atomically markable, no exceptions.** An item that can't be flipped done in one stroke is a planning failure. Post-flight catches these and either splits them automatically (REVISE) or surfaces them as holds (HOLD).
3. **Items collectively cover acceptance.** Every criterion in the plan dossier's `post.acceptance` list must map to at least one item (or be explicitly noted as "covered out-of-band — see X"). This is THE check that makes eval-agent useful: if the items don't cover acceptance, the verdict will always be PARTIAL.
4. **Dossier is the contract with exec-agent.** Exec-agent reads the todo-agent dossier in `:agent-context`, NOT the raw todo body. The dossier carries the todo path, the source plan dossier reference, the per-item routing tags (read-only / writes-required / network-side-effect), and the acceptance-coverage map.
5. **Per-item routing tags.** Each item carries a tag indicating *which downstream agent or tool* exec-agent should reach for. `:via :edit-agent`, `:via :bash` (read-only shell), `:via :mcp <server>:<tool>`, `:via :manual` (user must do this). Reduces exec-agent's per-item routing decisions to a lookup.
6. **Layout matches the rest of the ecosystem.** Todos move to `.brainyard/agents/todo-agent/todos/<slug>.md`; dossiers/drafts/index sit alongside.
7. **One auto-revise round; then HOLD.** Same budget rule as plan-agent.
8. **No clone-self recursion.** No `query$clone`. Cross-agent dispatch via `(call-tool …)` is fine.
9. **Plans are read-only here.** Pre-flight reads `plan$read-dossier` and `plan$read`; the agent NEVER calls `plan$update-body` or any other plan-mutating command.

---

## 3. Position in the Agent Stack

See `docs/plan-agent-design.md` §3 for the full pipeline diagram. Todo-agent sits in the second slot:

```
plan-agent → Saved dossier: <plan-agent dossier path>
                          │
                          ▼
todo-agent → Saved todo:    <todo path>
            → Saved dossier: <todo-agent dossier path>
                          │
                          ▼
exec-agent → ...
```

Rule of thumb: **todo-agent is the contract layer between intent (plan) and action (exec)**. Anything that should affect what work is queued belongs here; anything that's about *doing* the work belongs to exec-agent.

---

## 4. PRE-FLIGHT — Sufficiency Check (NEW)

Runs before any `todo$create` / `todo$update-item` / `todo$add-item` call. Walks a fixed checklist; produces a `pre` map; result is GO / GATHER / REFUSE.

### 4.1 The Checklist

| Check | What it verifies                                                                                                         | How                                                                                                                                                                                                                                          | Fail → action                                                                                                                                                       |
| ----- | ------------------------------------------------------------------------------------------------------------------------ | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| C1    | A plan-agent dossier was supplied (preferred) OR a plan slug + override.                                                | Look for `Saved dossier: <path>` in `:agent-context`. If absent but `:agent-context` is a plan slug or path, accept with a `:plan-ok? true` override OR with `pre.fallback-plan-only true`.                                                  | GATHER — recommend `(call-tool "plan-agent" {…})` first OR ask the user to confirm fallback.                                                                        |
| C2    | The plan dossier's post-flight passed.                                                                                  | `plan$read-dossier :path <dossier path>` → check `post.verdict = :pass`.                                                                                                                                                                     | If `:hold` → REFUSE with pointer ("plan-agent emitted holds: <list> — resolve those first"). If `:revise` and the user has not opted in → GATHER for confirmation. |
| C3    | The plan body is readable and contains `## Approach` and `## Acceptance`.                                                | `plan$read :slug <slug>`; grep for sections.                                                                                                                                                                                                | GATHER — recommend plan-agent re-author.                                                                                                                            |
| C4    | A near-duplicate todo does NOT already exist for this plan.                                                              | `todo$list :status :draft|:in-progress`; fuzzy-match against the plan slug + title.                                                                                                                                                          | If exact slug match → GATHER ("extend existing todo via todo$add-item, or confirm a fresh attempt").                                                                |
| C5    | The plan's `## Approach` decomposes into 3+ bullets.                                                                     | Count `^- ` lines under `## Approach` in the plan body.                                                                                                                                                                                      | REFUSE if 0; GATHER if 1–2 (recommend plan-agent revisit; plans this small are probably one-edit work).                                                             |
| C6    | If the plan body cites file paths that the items will need, those paths still exist.                                    | Cross-check `bash "test -f <path>"` for each `file:` reference in the plan body.                                                                                                                                                             | GATHER — list missing paths; recommend the user confirm the plan or run explore-agent.                                                                              |
| C7    | The user (or session) has not vetoed an automatic spawn.                                                                | Check session config for `:no-auto-spawn? true` (e.g., a per-project policy that all todo creations require explicit confirmation).                                                                                                          | GATHER — surface a one-line confirmation prompt.                                                                                                                    |

Same short-circuit rule as plan-agent: STOP on the first fail.

### 4.2 The `pre` Map

```clojure
(def pre
  {:verdict       :go            ; or :gather | :refuse
   :checks        {:c1 :pass :c2 :pass :c3 :pass :c4 :pass
                   :c5 :pass :c6 :pass :c7 :pass}
   :plan-dossier  ".brainyard/agents/plan-agent/dossiers/20260510-104503-ship-v2-checkout.md"
   :plan-slug     "ship-v2-checkout"
   :plan-path     ".brainyard/agents/plan-agent/plans/ship-v2-checkout.md"
   :acceptance    ["feature-flag checkout-v2 toggleable from staging admin"
                   "all checkout/* unit tests green"
                   "p99 checkout latency unchanged within ±5%"]
   :related-todos []
   :gather-question nil
   :refuse-reason   nil})
```

`pre.acceptance` is read directly from the plan dossier's `post.acceptance` field — the structured handoff plan-agent produced. Todo-agent never re-parses the plan markdown for acceptance.

---

## 5. AUTHOR / ADVANCE — Core Operation

Two sub-modes:

### 5.1 SPAWN

When no todo exists for the plan slug. Standard call:

```clojure
(call-tool "todo$create"
           {:title (str "Execute: " (:title plan))
            :goal  (str "Execute plan '" (:slug plan) "' (project scope). "
                        "Acceptance handed in via plan-agent dossier "
                        (:plan-dossier pre) ".")
            :items [{:description "Wire LD flag `checkout-v2` in src/checkout/flags.clj"
                     :tags {:via :edit-agent
                            :covers ["feature-flag checkout-v2 toggleable from staging admin"]}}
                    {:description "Update payment validator for legacy carts"
                     :tags {:via :edit-agent
                            :covers ["all checkout/* unit tests green"]}}
                    {:description "Run bb test:component checkout"
                     :tags {:via :bash
                            :covers ["all checkout/* unit tests green"]}}
                    {:description "Sample p99 from Grafana dashboard <url>"
                     :tags {:via :manual
                            :covers ["p99 checkout latency unchanged within ±5%"]}}]
            :scope :project})
```

The `:tags` per item are NEW; they extend `todo$create`'s schema (see §16 Files Summary). When tags are absent (legacy todos pre-redesign), exec-agent falls back to its old behavior of inferring routing from the description.

### 5.2 ADVANCE

When the user is updating an existing todo (mark items done, add an item, retarget the goal). Same set of `todo$*` calls as today; pre/post-flight still run.

For ADVANCE turns, pre-flight C5 is relaxed (the plan's approach is no longer the source of truth — the existing todo's items are). Post-flight skips R1 (plan-coverage) for ADVANCE turns where the user is just flipping a checkbox.

---

## 6. POST-FLIGHT — Confirmation Check (NEW)

Runs immediately after AUTHOR. Re-reads the just-written todo and grades it against a fixed rubric using `query$llm`.

### 6.1 The Rubric (SPAWN turns)

| Item | Rubric question                                                                                                                                                                  | Pass criterion                                                                                              |
| ---- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------- |
| R1   | Does every item correspond to a bullet (or sub-bullet) in the plan's `## Approach`?                                                                                              | Each item maps to ≥ 1 approach bullet via fuzzy-match (LLM check). Items with no map → flag.                |
| R2   | Is every item ATOMICALLY MARKABLE — small enough to flip done in one stroke?                                                                                                     | LLM check: would a careful reader say "yes, this is a single action"?                                        |
| R3   | Do the items collectively cover every criterion in `pre.acceptance`?                                                                                                             | For each criterion, at least one item's `tags.covers` lists it (or LLM judges fuzzy coverage).              |
| R4   | Item count is sane (3–30).                                                                                                                                                       | `(<= 3 (count items) 30)`. Fewer → likely under-decomposed; more → likely too granular.                     |
| R5   | Per-item routing tags are present and plausible.                                                                                                                                 | Every item has a `:via` tag in `#{:edit-agent :bash :mcp :manual :explore-agent :read-only}`.             |
| R6   | No two items overlap so much that flipping one half-completes the other.                                                                                                         | LLM cross-check: pairs of items judged as "redundant" or "overlapping" are flagged.                          |
| R7   | No dangling LLM artifacts in item descriptions (`TODO`, `???`, `<...>`, `[fill in]`).                                                                                            | Mechanical grep on each `:description`.                                                                     |

### 6.2 The Rubric (ADVANCE turns)

For checkbox flips and item insertions only R5 + R7 apply (plus a lightweight "did the right item flip?" check). The full rubric is reserved for SPAWN.

### 6.3 Verdict + `post` Map

Same shape as plan-agent's:

- **PASS** — every applicable rubric item passes.
- **REVISE** — auto-fix one round: split items that fail R2, append/edit items to close R3 gaps, drop redundant items per R6. Apply via `todo$add-item` / `todo$update-item` / `todo$reset-item`. Re-run the rubric ONCE.
- **HOLD** — failures persist or require user input (e.g., R3 says a criterion is uncoverable from the current plan — that's a plan-agent problem, surface it as `Hold:` in the answer).

```clojure
(def post
  {:verdict          :pass            ; or :revise | :hold
   :rubric           {:r1 :pass :r2 :pass :r3 :pass :r4 :pass
                      :r5 :pass :r6 :pass :r7 :pass}
   :revision-applied? false
   :revision-summary  nil
   :holds            []
   :acceptance-coverage
                     {"feature-flag checkout-v2 toggleable from staging admin" [0]
                      "all checkout/* unit tests green"                         [1 2]
                      "p99 checkout latency unchanged within ±5%"               [3]}
   :item-count       4})
```

`post.acceptance-coverage` is the explicit map exec-agent and eval-agent consume to decide which items support which criterion.

---

## 7. Output Discipline — `.brainyard/agents/todo-agent/`

Same shape as plan-agent (`docs/plan-agent-design.md` §7), tuned for todos.

### 7.1 Directory Layout

```
.brainyard/
├── todo-agent/
│   ├── todos/                     ; the durable todo corpus (replaces .brainyard/todos/)
│   │   ├── ship-v2-checkout.md
│   │   └── ...
│   ├── dossiers/                  ; per-turn handoff records
│   │   ├── 20260510-105612-ship-v2-checkout.md
│   │   └── ...
│   ├── drafts/                    ; per-turn scratch
│   ├── INDEX.md                   ; one line per dossier, newest first
│   └── README.md
```

### 7.2 Dossier Schema

```markdown
---
slug: ship-v2-checkout
agent: todo-agent
created: 2026-05-10T10:56:12Z
todo_path: .brainyard/agents/todo-agent/todos/ship-v2-checkout.md
todo_status: in-progress
turn_id: <id>
session_id: <id>

source:
  plan_dossier: .brainyard/agents/plan-agent/dossiers/20260510-104503-ship-v2-checkout.md
  plan_path:    .brainyard/agents/plan-agent/plans/ship-v2-checkout.md
  plan_slug:    ship-v2-checkout

pre:
  verdict: go
  checks:
    c1_plan_dossier:     pass
    c2_plan_postflight:  pass
    c3_plan_readable:    pass
    c4_no_duplicate:     pass
    c5_approach_decomposes: pass
    c6_refs_exist:       pass
    c7_no_veto:          pass
  acceptance:
    - "feature-flag checkout-v2 toggleable from staging admin"
    - "all checkout/* unit tests green"
    - "p99 checkout latency unchanged within ±5%"
  related_todos: []
  gather_question: null
  refuse_reason: null

author:
  action: spawned                   # spawned | advanced | unchanged
  item_count: 4
  items:
    - idx: 0
      description: "Wire LD flag `checkout-v2` in src/checkout/flags.clj"
      tags: {via: edit-agent,
             covers: ["feature-flag checkout-v2 toggleable from staging admin"]}
    - idx: 1
      description: "Update payment validator for legacy carts"
      tags: {via: edit-agent,
             covers: ["all checkout/* unit tests green"]}
    - idx: 2
      description: "Run bb test:component checkout"
      tags: {via: bash,
             covers: ["all checkout/* unit tests green"]}
    - idx: 3
      description: "Sample p99 from Grafana dashboard <url>"
      tags: {via: manual,
             covers: ["p99 checkout latency unchanged within ±5%"]}

post:
  verdict: pass
  rubric:
    r1_items_map_to_approach:    pass
    r2_items_atomic:             pass
    r3_acceptance_covered:       pass
    r4_count_sane:               pass
    r5_routing_tags_present:     pass
    r6_no_overlap:               pass
    r7_no_artifacts:             pass
  revision_applied: false
  revision_summary: null
  holds: []
  acceptance_coverage:
    "feature-flag checkout-v2 toggleable from staging admin": [0]
    "all checkout/* unit tests green":                         [1, 2]
    "p99 checkout latency unchanged within ±5%":               [3]

handoff:
  next_agent: exec-agent
  next_call: '(call-tool "exec-agent" {:question "Drive this todo to completion." :agent-context "<this dossier path>"})'
---

# Todo dossier — Ship v2 checkout

## Pre-flight summary
Sourced from plan-agent dossier `20260510-104503-ship-v2-checkout`. All 7 checks passed.

## Item summary
4 items spawned. Routing tags assigned per the table above.

## Post-flight notes
All rubric items passed first try. Acceptance coverage matrix complete: every criterion has at least one item.

## Handoff
Pass `<this dossier path>` to exec-agent in `:agent-context`. Exec-agent will pre-flight against the plan dossier as well.
```

Frontmatter contract:

| Key                          | Type                       | Description                                                                               |
| ---------------------------- | -------------------------- | ----------------------------------------------------------------------------------------- |
| `slug`                       | string                     | Todo slug. Often shared with the source plan slug.                                        |
| `agent`                      | string                     | Always `todo-agent`.                                                                      |
| `todo_path`                  | string                     | Repo-relative path to the todo body.                                                      |
| `todo_status`                | enum                       | `draft` \| `in-progress` \| `completed` \| `abandoned`.                                   |
| `source.plan_dossier`        | string                     | Path to the consumed plan-agent dossier.                                                  |
| `source.plan_path`           | string                     | Path to the plan body.                                                                    |
| `pre.*`                      | map                        | Verbatim copy of the `pre` map (§4.2).                                                    |
| `author.action`              | enum                       | `spawned` \| `advanced` \| `unchanged`.                                                   |
| `author.items[].tags.via`    | enum                       | `edit-agent` \| `bash` \| `mcp` \| `manual` \| `explore-agent` \| `read-only`.          |
| `author.items[].tags.covers` | vector of strings          | Acceptance criteria this item supports.                                                   |
| `post.*`                     | map                        | Verbatim copy of the `post` map (§6.3).                                                   |
| `post.acceptance_coverage`   | map criterion → vec idx    | Which items cover which criterion. Critical for eval-agent.                               |
| `handoff.next_agent`         | string                     | `exec-agent` on PASS; `plan-agent` on REFUSE/GATHER (re-author plan); `user` on HOLD.    |
| `handoff.next_call`          | string                     | Exact `(call-tool …)` form.                                                               |

### 7.3 ANSWER Format

Three stable lines at the end of the agent's `answer`:

```
Saved todo: <todo path>          (omitted when author.action = unchanged)
Saved dossier: <dossier path>
Next: <handoff.next_call>
```

GATHER / REFUSE / HOLD variants follow plan-agent's pattern (`docs/plan-agent-design.md` §7.5).

---

## 8. Instruction (System Prompt Body)

Layered on top of `coact-agent`'s instruction.

```text
You are a TODO-agent. You spawn or advance an executable todo list AFTER
confirming the source plan is sound, and you confirm the items are sound
BEFORE handing off to exec-agent. You ALWAYS produce a dossier — even
when you refuse or stop early.

Todos are stored at `.brainyard/agents/todo-agent/todos/<slug>.md`. (Legacy
location `.brainyard/todos/` is read for one release; all writes go to
the new path.) Plan dossiers live at `.brainyard/agents/plan-agent/dossiers/`.

────────────────────────────────────────────────────────────────────────────
THE THREE PHASES (every turn)
────────────────────────────────────────────────────────────────────────────
PRE-FLIGHT  — sufficiency check. Output: GO | GATHER | REFUSE.
AUTHOR      — only on GO. todo$create (SPAWN) or todo$* (ADVANCE).
POST-FLIGHT — only on AUTHOR. Self-critique against a 7-item rubric
              (full for SPAWN, lite for ADVANCE).
PERSIST     — always. Dossier under .brainyard/agents/todo-agent/dossiers/.
ANSWER      — `Saved todo:`, `Saved dossier:`, `Next:` (or variants).

────────────────────────────────────────────────────────────────────────────
PRE-FLIGHT CHECKLIST (short-circuit on first fail)
────────────────────────────────────────────────────────────────────────────
C1. PLAN DOSSIER. :agent-context contains `Saved dossier: <path>` for a
    plan-agent dossier. Fallback: a plan slug + :plan-ok? true override.
    GATHER otherwise — recommend plan-agent first.

C2. PLAN POST-FLIGHT PASSED. plan$read-dossier the path; assert
    post.verdict = :pass. If :hold → REFUSE (resolve plan holds first).
    If :revise → GATHER unless user opted in.

C3. PLAN READABLE. plan$read :slug <slug>; assert ## Approach and
    ## Acceptance present. GATHER otherwise.

C4. NO DUPLICATE TODO. todo$list; fuzzy-match the plan slug. Exact match
    → GATHER ("extend or fresh attempt?").

C5. APPROACH DECOMPOSES. Count `^- ` under ## Approach. 0 → REFUSE.
    1–2 → GATHER (probably one-edit work; recommend plan-agent revisit).

C6. REFS EXIST. For each `file:` reference in the plan body, bash test -f.
    Missing → GATHER.

C7. NO VETO. Session config :no-auto-spawn? true → GATHER for explicit
    confirm.

Stash `pre` (§4.2 schema).

────────────────────────────────────────────────────────────────────────────
AUTHOR — only on GO
────────────────────────────────────────────────────────────────────────────
SPAWN (no todo exists for this plan slug):
  todo$create with:
    :title (mirror plan title)
    :goal  (one paragraph naming the source plan slug + dossier path)
    :items (vector of {:description ... :tags {:via ... :covers [...]}})
    :scope :project

  Each :description is an ATOMICALLY MARKABLE action — verb-led.
  Each :tags.via picks the downstream tool: :edit-agent | :bash |
  :mcp | :manual | :explore-agent | :read-only. (When unsure → :manual.)
  Each :tags.covers lists the acceptance criteria from pre.acceptance
  this item supports.

ADVANCE (existing todo):
  todo$update-item to flip a checkbox.
  todo$add-item to insert when an item turns out to be over-coarse.
  todo$reset-item / todo$update-goal as needed.
  todo$complete only after :pending = 0 AND user confirms.

NEVER touch plan-mutating commands. NEVER auto-dispatch exec-agent.

Stash:
   (def authored {:slug "..." :path "..." :action :spawned|:advanced|:unchanged
                  :item-count N :items [<the items>]})

────────────────────────────────────────────────────────────────────────────
POST-FLIGHT RUBRIC
────────────────────────────────────────────────────────────────────────────
Re-read via todo$read. For SPAWN, score every item (R1–R7); for ADVANCE,
score R5 + R7 + a "right item flipped?" sanity check.

R1. ITEMS MAP TO APPROACH — each item maps to ≥ 1 ## Approach bullet.
    Use query$llm for fuzzy matching.
R2. ITEMS ATOMIC — each item is one stroke. query$llm judgement.
R3. ACCEPTANCE COVERED — every pre.acceptance entry appears in at least
    one item's :tags.covers (or fuzzy LLM judgement). Build the
    acceptance_coverage map.
R4. COUNT SANE — 3 ≤ count(items) ≤ 30.
R5. ROUTING TAGS — every item has :via in the allowed set.
R6. NO OVERLAP — query$llm flags pair-wise redundancy.
R7. NO ARTIFACTS — grep each :description for TODO / ??? / <...> / tk /
    [fill in].

VERDICT:
- All pass             → PASS.
- 1+ fail, fixable     → REVISE. Apply ONE round: todo$add-item to close
                         R3 gaps, todo$update-item to retag, etc. Re-run
                         the rubric ONCE.
- 1+ fail, needs user  → HOLD. Record in dossier; surface in answer.

Stash `post` (§6.3 schema).

────────────────────────────────────────────────────────────────────────────
PERSIST + ANSWER
────────────────────────────────────────────────────────────────────────────
Write `.brainyard/agents/todo-agent/dossiers/<yyyyMMdd-HHmmss>-<slug>.md` per
the schema in docs/todo-agent-design.md §7.2. PREPEND a line to
.brainyard/agents/todo-agent/INDEX.md.

When todo$* helpers (§12) are bound:
   (def fm (:frontmatter
             (todo$dossier-frontmatter
               :slug slug :pre pre :source pre :author authored :post post
               :handoff (todo$next-handoff :pre pre :post post :slug slug))))
   (def res (todo$dossier-write :slug slug :content (str fm body)))
   (todo$dossier-index-append :path (:path res) :slug slug
                              :pre-verdict (:verdict pre)
                              :post-verdict (or (:verdict post) :n-a)
                              :next-agent (:next-agent (:handoff fm)))

ANSWER (PASS):
    Saved todo: <todo-path>
    Saved dossier: <dossier-path>
    Next: (call-tool "exec-agent" {:question "Drive this todo to completion."
                                   :agent-context "<dossier-path>"})

GATHER / REFUSE / HOLD variants follow plan-agent's pattern.

────────────────────────────────────────────────────────────────────────────
HARD RULES
────────────────────────────────────────────────────────────────────────────
1. NO authoring on a plan whose post-flight did not pass. C2 is a hard
   gate.
2. NO chaining clarifying questions. ONE question per turn.
3. NO unbounded revision. POST-FLIGHT allows ONE auto-revise round.
4. NO mutating plans or exec records. Plans are read-only here.
5. NO query$clone. Cross-agent dispatch via call-tool is fine.
6. NO writing outside .brainyard/agents/todo-agent/.
7. NO inventing items unmoored from ## Approach. Every item must trace
   back to either an approach bullet or an acceptance criterion (and the
   trace lives in :tags.covers).
8. NEVER skip the dossier — even REFUSE turns produce one.
```

---

## 9. Tool-Context

```text
## Todo Tools — execution trackers under .brainyard/agents/todo-agent/todos/

TODO TRACKING (todo$*)
- todo$list         -- List todos. Args: scope, status (optional). Reads
                       NEW path AND legacy .brainyard/todos/ (one release).
- todo$read         -- Read a todo by slug. Loads it as the active todo.
                       Args: slug, scope (optional). Returns frontmatter +
                       goal + items (with new :tags) + :file-path.
- todo$create       -- Spawn a todo. Args: title, goal, items (vector of
                       {:description :tags}), scope. Item :tags is
                       NEW: {:via #{:edit-agent :bash :mcp :manual
                                    :explore-agent :read-only}
                             :covers [<criterion strings>]}.
                       Writes to .brainyard/agents/todo-agent/todos/<slug>.md.
- todo$update-item  -- Flip checkbox to done. Args: slug, item-idx.
- todo$update-goal  -- Replace goal paragraph. Args: slug, goal.
- todo$add-item     -- Append/insert. Args: slug, description, tags
                       (optional new), after-idx (optional).
- todo$status       -- :completed/:pending/:total/:percent/:next-item.
- todo$complete     -- Args: slug. Fails if items pending.
- todo$abandon      -- Args: slug. Confirm.
- todo$reopen       -- Reset all + status :draft. Destructive — confirm.
- todo$reset-item   -- Reset a single item. Args: slug, item-idx.
- todo$delete       -- Args: slug, scope. Destructive — confirm.
- todo$exists       -- Args: slug, scope (optional).

PLAN ACCESS (READ-ONLY — pre-flight only)
- plan$read-dossier -- Args: path. Returns parsed frontmatter map. THE
                       primary pre-flight tool — gives you :acceptance,
                       :post.verdict, :plan_path, :plan_slug.
- plan$read         -- Args: slug, scope. Read the plan body. Use to
                       confirm ## Approach / ## Acceptance presence and
                       to derive item descriptions.
- plan$exists       -- Args: slug.
- plan$status       -- Args: slug. Lifecycle check.

NOT BOUND (deliberate):
- All plan-mutating commands → plan-agent
- All exec-only commands (none today, but future)

DISCOVERY (fallbacks)
- read-file, search, grep, bash (read-only — `test -f`, `wc -l`,
  `git log -- <plan-path>`)
- list-tools, get-tool-info, call-tool

SUB-LLM
- query$llm — used heavily in POST-FLIGHT R1, R2, R3, R6 (fuzzy
              matching, atomicity judgement, coverage inference).

PERSISTENCE HELPERS (todo$* dossier suite — auto-bound when present)
- todo$dossier-slug         — slug helper
- todo$dossier-frontmatter  — YAML block per §7.2
- todo$dossier-write        — write to dossiers/
- todo$dossier-index-append — prepend INDEX.md
- todo$read-dossier         — frontmatter-only parse for downstream
- todo$next-handoff         — single source of truth for `Next:`
- todo$preflight            — full pre-flight as one call
- todo$postflight           — full post-flight as one call
                              (includes the auto-revise round)

CROSS-AGENT DISPATCH
- (call-tool "plan-agent" {…}) — when C1/C2 fail.
- (call-tool "exec-agent" {…}) — RECOMMENDED via `Next:`. Do NOT
                                   auto-dispatch.

## Typical end-to-end flow
1. Parse :question and :agent-context (a `Saved dossier: <path>`).
2. PRE-FLIGHT C1–C7. Stash `pre`.
3. If GATHER/REFUSE → skip AUTHOR/POST-FLIGHT, go to PERSIST + ANSWER.
4. AUTHOR — todo$create (SPAWN) or todo$* (ADVANCE). Stash `authored`.
5. POST-FLIGHT — re-read; rubric R1–R7 with query$llm. One revise round
   if needed. Stash `post`.
6. PERSIST — dossier; INDEX prepend.
7. ANSWER — `Saved todo:` + `Saved dossier:` + `Next:`.
```

---

## 10. Behavior Tree — Inherited As-Is

Same as plan-agent (`docs/plan-agent-design.md` §10). No new BT. Iteration shape:

| Iter | Channel       | Body                                                                           |
| ---- | ------------- | ------------------------------------------------------------------------------ |
| 1    | code          | PRE-FLIGHT C1–C7. `def pre`.                                                    |
| 2    | tool / code   | (only on GO) AUTHOR: todo$create or todo$update-item. `def authored`.           |
| 3    | code          | POST-FLIGHT rubric. Optional one revise. `def post`.                            |
| 4    | code          | PERSIST dossier; prepend INDEX.                                                 |
| 5    | answer        | `Saved todo:` + `Saved dossier:` + `Next:`.                                    |

---

## 11. Demonstration: "Spawn a todo for the Ship v2 checkout plan"

`:agent-context = "Saved dossier: .brainyard/agents/plan-agent/dossiers/20260510-104503-ship-v2-checkout.md"`.

### Iteration 1 — PRE-FLIGHT

```clojure
(def dossier-path
  (last (re-find #"Saved dossier: (\S+)"
                 ":agent-context value here")))

(def plan-dossier (call-tool "plan$read-dossier" {:path dossier-path}))

(def pre
  {:verdict (cond
              (not= :pass (-> plan-dossier :post :verdict)) :refuse
              :else :go)
   :checks {:c1 :pass
            :c2 (if (= :pass (-> plan-dossier :post :verdict)) :pass :fail)
            :c3 (if (and (re-find #"## Approach"
                                  (:body (call-tool "plan$read"
                                                    {:slug (:plan_slug plan-dossier)})))
                         (re-find #"## Acceptance"
                                  (:body (call-tool "plan$read"
                                                    {:slug (:plan_slug plan-dossier)}))))
                  :pass :fail)
            :c4 (if (zero? (count
                             (filter #(= (:slug %) (:plan_slug plan-dossier))
                                     (:todos (call-tool "todo$list" {:status :draft})))))
                  :pass :fail)
            :c5 :pass :c6 :pass :c7 :pass}
   :plan-dossier dossier-path
   :plan-slug    (:plan_slug plan-dossier)
   :plan-path    (:plan_path plan-dossier)
   :acceptance   (-> plan-dossier :post :acceptance)})
```

### Iteration 2 — AUTHOR (SPAWN)

```clojure
(def authored
  (let [items [{:description "Wire LD flag `checkout-v2` in src/checkout/flags.clj"
                :tags {:via :edit-agent
                       :covers ["feature-flag checkout-v2 toggleable from staging admin"]}}
               {:description "Update payment validator for legacy carts"
                :tags {:via :edit-agent
                       :covers ["all checkout/* unit tests green"]}}
               {:description "Run bb test:component checkout"
                :tags {:via :bash
                       :covers ["all checkout/* unit tests green"]}}
               {:description "Sample p99 from Grafana dashboard <url>"
                :tags {:via :manual
                       :covers ["p99 checkout latency unchanged within ±5%"]}}]
        res   (call-tool "todo$create"
                         {:title "Ship v2 checkout"
                          :goal  (str "Execute plan '" (:plan-slug pre)
                                      "' (project scope). Acceptance per "
                                      (:plan-dossier pre) ".")
                          :items items
                          :scope :project})]
    {:slug (:slug res)
     :path (:file-path res)
     :action :spawned
     :item-count (count items)
     :items items}))
```

### Iteration 3 — POST-FLIGHT (rubric)

```clojure
(def items (:items authored))

;; R1, R2, R6 — query$llm
(def llm-result
  (:result (query$llm
             :prompt (str "Score these todo items:\n"
                          (clojure.string/join "\n" (map-indexed
                                                      (fn [i it] (str i ". " (:description it)))
                                                      items))
                          "\n\nAgainst this plan ## Approach:\n"
                          (->> (call-tool "plan$read" {:slug (:plan-slug pre)})
                               :body
                               (re-find #"(?s)## Approach.*?(?=\n## )"))
                          "\n\nReturn EDN: {:r1 :pass|:fail :r2 :pass|:fail "
                          ":r6 :pass|:fail :notes \"...\"}"))))

;; R3 — explicit coverage map
(def coverage
  (reduce (fn [acc crit]
            (assoc acc crit (vec (keep-indexed (fn [i it]
                                                 (when (some #{crit} (-> it :tags :covers))
                                                   i))
                                               items))))
          {}
          (:acceptance pre)))

(def r3
  (if (every? seq (vals coverage)) :pass :fail))

;; R4
(def r4 (if (<= 3 (count items) 30) :pass :fail))

;; R5
(def r5
  (if (every? #(contains? #{:edit-agent :bash :mcp :manual :explore-agent :read-only}
                          (-> % :tags :via))
              items)
    :pass :fail))

;; R7
(def r7
  (if (some #(re-find #"\b(TODO|tk|\?\?\?|<\.{3}>|\[fill in\])\b" (:description %))
            items)
    :fail :pass))

(def post
  {:verdict (if (every? #(= :pass %) [(:r1 llm-result) (:r2 llm-result) r3 r4 r5
                                       (:r6 llm-result) r7])
              :pass :revise)
   :rubric  {:r1 (:r1 llm-result) :r2 (:r2 llm-result) :r3 r3 :r4 r4 :r5 r5
             :r6 (:r6 llm-result) :r7 r7}
   :revision-applied? false
   :revision-summary  nil
   :holds             []
   :acceptance-coverage coverage
   :item-count (count items)})
```

### Iteration 4 — PERSIST

```clojure
(def fm (:frontmatter
          (todo$dossier-frontmatter
            :slug    (:slug authored)
            :pre     pre
            :source  {:plan-dossier (:plan-dossier pre)
                      :plan-path    (:plan-path pre)
                      :plan-slug    (:plan-slug pre)}
            :author  authored
            :post    post
            :handoff {:next-agent "exec-agent"
                      :next-call  (str "(call-tool \"exec-agent\" "
                                       "{:question \"Drive this todo to completion.\" "
                                       ":agent-context \"<this dossier path>\"})")})))

(def body
  (str "# Todo dossier — Ship v2 checkout\n\n"
       "## Pre-flight summary\nSourced from " (:plan-dossier pre) ".\n\n"
       "## Item summary\n"
       (clojure.string/join "\n"
         (map-indexed (fn [i it]
                        (str "- " i ". " (:description it)
                             " — :via " (-> it :tags :via)))
                      (:items authored))) "\n\n"
       "## Post-flight notes\nAll rubric items passed first try.\n\n"
       "## Handoff\nPass `<this dossier path>` to exec-agent.\n"))

(def res (todo$dossier-write :slug (:slug authored) :content (str fm body)))
(todo$dossier-index-append :path (:path res) :slug (:slug authored)
                           :pre-verdict :go :post-verdict :pass
                           :next-agent "exec-agent")
```

### Iteration 5 — ANSWER

```markdown
## Todo spawned — Ship v2 checkout

PRE-FLIGHT: GO (all checks passed; consumed plan dossier 20260510-104503)
AUTHOR: spawned (4 items)
POST-FLIGHT: PASS (R1–R7 all passed)

Acceptance coverage:
- "feature-flag checkout-v2 toggleable from staging admin" → item 0
- "all checkout/* unit tests green" → items 1, 2
- "p99 checkout latency unchanged within ±5%" → item 3

Saved todo: .brainyard/agents/todo-agent/todos/ship-v2-checkout.md
Saved dossier: .brainyard/agents/todo-agent/dossiers/20260510-105612-ship-v2-checkout.md
Next: (call-tool "exec-agent" {:question "Drive this todo to completion." :agent-context ".brainyard/agents/todo-agent/dossiers/20260510-105612-ship-v2-checkout.md"})
```

---

## 12. Optional `(todo$*)` Dossier Helpers

Live in `ai.brainyard.agent.common.todo`. Same shape as `plan$*` dossier helpers (`docs/plan-agent-design.md` §12) — `todo$dossier-slug`, `todo$dossier-frontmatter`, `todo$dossier-write`, `todo$dossier-index-append`, `todo$read-dossier`, `todo$next-handoff`, `todo$preflight`, `todo$postflight`. The `todo$preflight` helper takes `:agent-context` and returns the `pre` map; `todo$postflight` re-reads the just-authored todo and returns `post`. With both bound the demonstration in §11 collapses to the same compact shape as plan-agent's §12 example.

---

## 13. Handoff Mechanics

Exec-agent reads the todo-agent dossier in `:agent-context`. Two read levels (cheap frontmatter-only via `todo$read-dossier`; full body via `read-file`). The frontmatter gives:

- `todo_path` and `slug` for `todo$read`.
- `source.plan_dossier` and `source.plan_path` for plan context.
- `pre.acceptance` (carried forward from plan-agent) — exec-agent uses this to know what "done" means per item.
- `author.items[].tags.via` — the routing tag exec-agent uses to decide whether to delegate to edit-agent / bash / etc.
- `post.acceptance_coverage` — eval-agent uses this directly when scoring.

For ADVANCE turns (todo-agent flipped a checkbox), the dossier records the *single* item that changed and the new `todo_status`; downstream tooling treats it as an incremental record.

---

## 14. Migration Plan — `.brainyard/todos/` → `.brainyard/agents/todo-agent/todos/`

Mirror plan-agent's migration phases (`docs/plan-agent-design.md` §14).

- Phase 0: land redesigned agent + helpers; dual-read; tests.
- Phase 1: `bb migrate:todo-agent` one-shot file move; legacy preserved.
- Phase 2: exec-agent + eval-agent updated to consume todo-agent dossiers.
- Phase 3: legacy fallback emits one-time warning.
- Phase 4: legacy fallback removed.

The acceptance gates mirror plan-agent's. The migration tasks for plan, todo, exec, and eval can be combined into one `bb migrate:agent-storage` umbrella task at the user's discretion.

---

## 15. Verification

| Benchmark                                  | Shape                                                                                   | What it verifies                                                                                                       |
| ------------------------------------------ | --------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------- |
| Pre-flight GO happy path                   | `:agent-context` with valid plan dossier (post.verdict :pass)                          | All C1–C7 pass; SPAWN runs; POST-FLIGHT PASS; one dossier with full coverage map.                                     |
| Pre-flight GATHER (no plan dossier)        | `:agent-context = ""`; user request "build a todo for foo"                              | C1 fails; agent recommends plan-agent; dossier records GATHER.                                                         |
| Pre-flight REFUSE (plan post HOLD)         | Plan dossier has `post.verdict :hold`                                                  | C2 fails; agent refuses; dossier names the plan holds.                                                                 |
| Pre-flight GATHER (duplicate todo)         | Existing todo with same slug                                                            | C4 fails; agent suggests todo$add-item or fresh attempt.                                                               |
| Post-flight PASS                           | Clean SPAWN; LLM produces well-formed items                                            | All R1–R7 pass; acceptance_coverage map complete.                                                                      |
| Post-flight REVISE (R3 gap)                | LLM forgets to cover criterion C; auto-revise adds an item                              | R3 fails first pass; one auto-round closes the gap; dossier records `revision-applied? true`.                          |
| Post-flight HOLD (R5 needs user)           | Item with no `:via` tag and ambiguous routing                                          | R5 fails; agent surfaces hold; NO `Next:` to exec-agent.                                                               |
| ADVANCE checkbox flip                      | Existing todo; user marks item 2 done                                                   | Lite rubric; dossier records the single update; `author.action :advanced`.                                             |
| Plan dossier consumption                   | Read `acceptance` directly from dossier without re-parsing plan markdown                | Dossier `pre.acceptance` matches plan-agent dossier `post.acceptance` exactly.                                         |
| Routing tag enumeration                    | Spawn 10 mixed items                                                                    | Every item carries a `:via` tag in the allowed set.                                                                    |
| Dual-read fallback                         | Legacy todo at `.brainyard/todos/<slug>.md`                                            | `todo$read` returns body; `todo$list` tags it `:layout :legacy`.                                                       |
| Migration                                  | `bb migrate:todo-agent` against fixture                                                 | All files copied; legacy preserved.                                                                                    |
| Index integrity                            | Append 100 dossiers                                                                     | INDEX.md has 100 lines, newest first.                                                                                  |

mulog signals: `::todo.preflight`, `::todo.author`, `::todo.postflight`, `::todo.dossier-write`, `::todo.handoff`.

---

## 16. Files Summary

| File                                                                                       | What changes                                                                                                                                                                            |
| ------------------------------------------------------------------------------------------ | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `components/agent/src/ai/brainyard/agent/common/todo_agent.clj`                            | REDESIGNED — three-phase pipeline (PRE-FLIGHT / AUTHOR / POST-FLIGHT), new instruction + tool-context per §8 / §9, dossier emission per §7.                                             |
| `components/agent/src/ai/brainyard/agent/common/todo.clj`                                  | EXTENDED — `todo$create` / `todo$add-item` accept `:tags` per item; reads/writes new path with dual-read fallback; new dossier helpers (`todo$dossier-*`, `todo$preflight`, `todo$postflight`). |
| `components/agent/test/ai/brainyard/agent/todo_agent_test.clj`                             | EXTENDED — new tests per §15.                                                                                                                                                           |
| `.brainyard/agents/todo-agent/README.md`                                                          | NEW (templated by helpers).                                                                                                                                                              |
| `bb.edn`                                                                                   | NEW task `migrate:todo-agent`.                                                                                                                                                          |
| `docs/todo-agent-design.md`                                                                | THIS FILE.                                                                                                                                                                              |
| `components/agent/src/ai/brainyard/agent/common/{exec_agent,eval_agent}.clj`               | TOUCHED at Phase 2 to consume todo-agent dossiers.                                                                                                                                       |
| `docs/agent-design.md` / `docs/AUTORESEARCH.md`                                            | TOUCHED — references updated.                                                                                                                                                            |

---

## 17. Open Questions

1. **Should `:tags.via` be a required field or default to `:manual`?** Required forces deliberation but makes simple "manual checklist" todos painful to spawn. Default `:manual` is forgiving but invites lazy tagging. Suggestion: warn (not block) on missing tags in v1; tighten when exec-agent benchmarks show routing accuracy improves.
2. **Should `:tags.covers` be a required field?** Without it, R3 (acceptance coverage) falls back to fuzzy LLM matching — slower and noisier. Suggestion: require for SPAWN; optional for ADVANCE. Same pattern as `:tags.via`.
3. **Multi-plan todos.** A todo that executes against two plans simultaneously (rare, but real for cross-cutting refactors). Today the schema assumes one source plan dossier. Could extend `source` to a vector. Defer until use case appears.
4. **Should the dossier carry the items inline?** Frontmatter currently lists every item, which is fine for small todos but could bloat to >2KB for 30-item todos. Trade-off: keep dossier self-contained (current design) vs. delta dossier referencing the todo body (faster reads). Stick with self-contained for v1; revisit if dossier read latency dominates.
5. **Auto-dispatch exec-agent.** When the user is in a session with `:auto-flow? true`, todo-agent could `(call-tool "exec-agent" …)` directly instead of recommending. Saves a turn but takes the user out of the loop. Suggestion: opt-in only.
6. **R3 coverage by criterion vs. by sub-criterion.** Plan acceptance criteria are sometimes multi-clause ("p99 latency unchanged AND error rate < 0.1%"). Today R3 treats each criterion as one unit. Extending to sub-criteria would require structured plan acceptance — a plan-agent change. Defer.
