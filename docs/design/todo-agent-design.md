# Todo-Agent — Pre-flight & Post-flight Gated Todo Authoring with Dossier Handoff (CoAct-derived)

> **Status:** Shipped. Pre/post-flight gated authoring is registered in
> `components/agent` (`common/todo_agent.clj`); the lightweight file-tool
> redesign + root-agent-managed todos shipped (2026-06). This doc is the
> as-built reference — the former `todo-agent-lightweight-redesign.md` has been
> folded in here and removed.
>
> **As-built (verify against `common/todo_agent.clj`, `common/todo.clj`,
> `common/agent-roster.clj`, `common/coact_agent.clj`, `common/react_agent.clj`):**
> - **Item management is LLM-inherent, not item micro-tools.** A todo is a
>   GitHub-style markdown checklist; the model flips/adds/edits items by editing
>   the markdown with `update-file` / `write-file` (§5), matching on the line
>   *text* — never a drifting `:item-idx`. `doc$update :kind :todo`
>   (`:item-idx`/`:item-done`/`:add-item`) remains **bound as a fallback** but
>   the file-edit path is the documented primary. (Divergence from the redesign,
>   which proposed *retiring* the item-mutation verbs outright; they were kept
>   bound for compatibility.)
> - **Root-agent-managed todos via a base-agent substrate.** A
>   `## Todo substrate` system-context section
>   (`agent-roster/todo-substrate-protocol`) is installed in BOTH base agents —
>   `coact-system-context` (`coact_agent.clj`) and `react-system-context`
>   (`react_agent.clj`), in each `section-order` next to the other substrates.
>   Every derived agent (main-agent included) inherits the checklist convention
>   for free, modeled 1:1 on the Project Memory protocol. **No roster change** —
>   `write-file`/`update-file`/`read-file` + `todo$sync` already ride
>   `default-agent-roster`. Routine "what I'm doing now" checklists need no
>   `todo-agent` dispatch.
> - **The read seam is `todo$sync`** (not a `todo$read` rename): a READ-ONLY
>   reconcile that re-parses the checklist (`parse-items`), recomputes progress,
>   and mirrors to st-memory (`mirror-to-st-memory!`) so the TUI/web live block
>   stays current. It addresses a todo by `:slug` (canonical
>   `todo-agent/todos/` dir) OR by `:path` (any working-checklist file). Call it
>   once after any checklist edit.
> - **Write-side dossier helpers are retired.** `todo-dossier-helpers` ships
>   exactly `[todo$read-dossier todo$sync]`. The dossier is authored as markdown
>   directly via `write-file` from the §7.2 template. The old
>   `todo$dossier-slug`/`-frontmatter`/`-write`/`-index-append`/`todo$next-handoff`
>   are gone (their private YAML emitters survive only to back the auto-persist
>   hook).
> - **POST-FLIGHT verdict is PASS / HOLD only.** The REVISE auto-round (§6.3) is
>   **deferred to v1.5** — shipped: "1+ fail → HOLD," no automatic split/retag.
> - **C7 (NO VETO) is INFORMATIONAL, not a GATHER gate.** Pre-flight records
>   `:no-auto-spawn?` but does not block; the "GATHER for explicit confirm" is a
>   future tightening.
> - **`write-file` / `update-file` / `read-file` ARE bound** (with `fetch-url`
>   removed — web discovery lives in explore-agent). This reverses the original
>   proposal's "writes NOT bound."
> - **Cross-agent dispatch is direct kebab-case** — `(plan-agent {…})`,
>   `(exec-agent {…})` — not `call-tool`. Hard Rule 4 reads "NO clone-self dispatch."
> - **An `:agent.ask/finalize` auto-persist hook** (gated to `:todo-agent`)
>   reconstructs a minimal dossier from the answer text if the helpers were
>   skipped, and flags hallucinated `Saved dossier:` paths via an on-disk check.
>   It is a safety net, not the primary path.
>
> **Scope:** `components/agent/src/ai/brainyard/agent/common/todo_agent.clj`,
> `todo.clj`, plus the shared todo substrate in `agent-roster.clj` wired into the
> base `coact_agent.clj` / `react_agent.clj` system-context.
> **Built on:** `coact_agent.clj` via `coact/run-coact-derived`
> **Sibling of:** `plan-agent`, `exec-agent`, `eval-agent`, `edit-agent`, `explore-agent`
> **Related reading:** `docs/design/plan-agent-design.md` (dossier schema is the
> template), `docs/design/exec-agent-design.md`,
> `docs/design/explore-agent-design.md`, `docs/design/edit-agent-design.md`,
> `docs/design/agent-lightweight-redesign-synthesis.md`

---

## 1. Motivation

The original `todo-agent` stacked **two** layers of brittleness, where plan/explore had one.

**Layer 1 — the dossier helper chain** (identical to plan-agent): build `pre` /
`source` / `author` / `post` / `handoff` maps with exact keys, feed them to
`todo$dossier-frontmatter` (strict ordered YAML, nested `author.items[]`),
`todo$dossier-write`, `todo$dossier-index-append`, `todo$next-handoff`, with an
auto-persist hook bolted on as a crutch.

**Layer 2 — the item-mutation micro-tools** (todo-agent's own): the todo *body*
was manipulated through structured, index-addressed verbs —

```clojure
(doc$update {:kind :todo :slug s :item-idx 3 :item-done true})   ; flip checkbox by INDEX
(doc$update {:kind :todo :slug s :add-item "desc" :after-idx 2 :tags {…}})
```

This second layer was the worse offender. Unlike a plan or an exploration
(write-once, mostly-read), a todo is **mutated over and over** as work proceeds,
and the micro-tool design forced the model to:

- **Track numeric `:item-idx` across turns** — and those indices *drift* the
  moment `:add-item :after-idx` inserts a row, so "mark item 3 done" silently
  targets the wrong line after an insertion.
- **Pick the right structured verb** for each mutation and get its schema right.
- **Round-trip through `doc$read`** to recover indices it can't see.

Yet the todo body is, on disk, exactly this (`todo.clj` `render-items` /
`parse-items`):

```markdown
## Todo
- [ ] Wire LD flag `checkout-v2` in src/checkout/flags.clj {via: edit-agent, covers: ["…"]}
- [x] Update payment validator for legacy carts {via: edit-agent, covers: ["…"]}
```

Flipping a checkbox is changing one character: `- [ ]` → `- [x]`. Adding an item
is appending a line. **This is the single most LLM-native edit there is** — it's
how every markdown TODO list, GitHub issue checklist, and the harness's own
TodoWrite/TaskUpdate work. Routing it through index-addressed structured verbs
fought the model's fluency for no benefit.

Three further problems the gated pipeline was designed to solve remain:

1. **Items spawned without confirming the plan is sound.** A half-baked plan's
   weaknesses are inherited by the items, and the executor's failure then looks
   like an exec-agent bug rather than a planning gap.
2. **No post-flight check on the items themselves** — do items map to approach
   bullets, are they atomic, do they collectively cover acceptance?
3. **Unstructured handoff to exec-agent** — no schema'd channel carrying the
   per-item routing tags and acceptance-coverage map.

**Thesis (shipped).** Three shifts on top of the existing three-phase pipeline:

1. **Dossier authoring is LLM-inherent** (as in plan/explore): `write-file` the
   dossier from a template; the `todo$dossier-*` write helpers are retired.
2. **Item management is LLM-inherent:** treat the todo as a markdown checklist
   and mutate it with `update-file` (flip a checkbox, edit a line) and
   `write-file`/append (add an item) — addressed by *text*, not a drifting
   ordinal. Keep a deterministic **read seam** (`todo$sync`) that parses the
   checklist into a progress map and mirrors it to st-memory (the TUI depends on
   that — §6).
3. **Todos are root-agent-managable without the subagent:** the checklist +
   read seam is a shared substrate installed in the base agents, so any agent
   can use it directly; `todo-agent` is reserved for the formal, gated, audited
   contract path (§8).

One line: **editing a checkbox is editing a checkbox** — and you don't need to
spawn a subagent to do it.

---

## 2. Design Principles

1. **Writes are LLM-inherent.** Dossier *and* todo items are authored/mutated
   with file tools. No frontmatter constructors, no index-addressed item verbs
   on the primary path (the `doc$*` CRUD surface stays bound only as a fallback).
2. **Reads stay deterministic.** Two read seams survive: the dossier frontmatter
   reader (`todo$read-dossier`) and a **checklist reader** (`todo$sync`) that
   yields `{:completed :pending :total :percent :next-item :items}` and mirrors
   to st-memory. Parsing checkboxes is where a machine beats the model.
3. **Index-free mutation.** Items are addressed by their *text* (the line you
   edit), never by a drifting ordinal. Insertions can't misalign a later flip.
4. **The contract is the file + the template.** Checklist format and dossier
   keys are enforced by *showing* them in the instruction — not by constructors.
5. **The subagent is optional, not the gate.** Managing a todo does not require
   `todo-agent`. The subagent adds *discipline* (plan-coverage gating, the
   acceptance rubric, the exec handoff dossier) for the cases that want it (§8).
6. **No items are authored on a half-baked plan.** Pre-flight requires a
   plan-agent dossier whose `post.verdict` is `pass` (C2 is a hard gate).
7. **Items collectively cover acceptance.** Every criterion in the plan
   dossier's `post.acceptance` list must map to at least one item via
   `tags.covers`. This is THE check that makes eval-agent useful.
8. **Dossier is the contract with exec-agent.** Exec-agent reads the todo-agent
   dossier in `:agent-context`, NOT the raw todo body. It carries the todo path,
   the source plan dossier reference, the per-item routing tags, and the
   acceptance-coverage map.
9. **Per-item routing tags.** Each item carries `{:via … :covers […]}`:
   `:edit-agent`, `:bash`, `:mcp`, `:manual`, `:explore-agent`, `:read-only`.
   Reduces exec-agent's per-item routing to a lookup.
10. **Layout matches the rest of the ecosystem.** Todos live at
    `.brainyard/agents/todo-agent/todos/<slug>.md`; dossiers/drafts/index sit
    alongside. (Legacy `.brainyard/todos/` is read-fallback for one release.)
11. **One verdict pair for now.** POST-FLIGHT is PASS / HOLD; the auto-revise
    round is deferred to v1.5.
12. **No clone-self recursion.** No `query$clone`. Cross-agent dispatch is direct
    kebab-case (`(plan-agent {…})`).
13. **Plans are read-only here.** Pre-flight reads `plan$read-dossier` and
    `doc$read :kind :plan`; the agent NEVER mutates a plan.
14. **Degrade gracefully.** A checkbox edit can't fail a schema; a bad line is
    caught by the lenient `parse-items`; the slimmed auto-persist hook backstops
    a skipped dossier.

---

## 3. Position in the Agent Stack

See `docs/design/plan-agent-design.md` §3 for the full pipeline diagram.
Todo-agent sits in the second slot:

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

Rule of thumb: **todo-agent is the contract layer between intent (plan) and
action (exec)**. Anything that should affect what work is queued belongs here;
anything about *doing* the work belongs to exec-agent.

But note the shipped reality: only **contract backlogs** (the formal
plan→todo→exec→eval spine) need todo-agent. **Working checklists** — an agent's
own "what I'm doing now" scratchpad — are handled inline by whatever agent is
doing the work, via the base-agent todo substrate (§8). The subagent comes out
only when you want a *vetted, plan-derived, audited* backlog with a dossier.

---

## 4. PRE-FLIGHT — Sufficiency Check

Runs before any AUTHOR. Walks a fixed checklist; produces a `pre` map; result is
GO / GATHER / REFUSE. Same short-circuit rule as plan-agent: STOP on the first fail.

### 4.1 The Checklist

| Check | What it verifies | How | Fail → action |
| ----- | ---------------- | --- | ------------- |
| C1 | A plan-agent dossier was supplied (preferred) OR a plan slug + override. | Look for `Saved dossier: <path>` in `:agent-context`; else accept a plan slug + `:plan-ok? true`. | GATHER — recommend plan-agent first OR confirm fallback. |
| C2 | The plan dossier's post-flight passed. | `plan$read-dossier :path <path>` → `post.verdict = "pass"`. | If `hold` → REFUSE ("plan-agent emitted holds — resolve those first"). |
| C3 | The plan body is readable and has `## Approach` and `## Acceptance`. | `doc$read :kind :plan :slug <slug>`; grep for sections. | GATHER — recommend plan-agent re-author. |
| C4 | No near-duplicate todo already exists for this plan. | `doc$list :kind :todo`; fuzzy-match against the plan slug. | Exact slug match → GATHER ("extend existing or fresh attempt?"). |
| C5 | The plan's `## Approach` decomposes into 3+ bullets. | Count `^- ` lines under `## Approach`. | REFUSE if 0; GATHER if 1–2. |
| C6 | File paths the items will need still exist. | `bash "test -f <path>"` per `file:` reference. | GATHER — list missing paths. |
| C7 | The user/session has not vetoed an automatic spawn. | Check session config for `:no-auto-spawn?`. | **INFORMATIONAL only (as-built)** — recorded, does not block. A future version may make it a GATHER gate. |

### 4.2 The `pre` Map

```clojure
(def pre
  {:verdict       :go            ; or :gather | :refuse
   :checks        {:c1 :pass :c2 :pass :c3 :pass :c4 :pass
                   :c5 :pass :c6 :pass :c7 :informational}
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

`pre.acceptance` is read directly from the plan dossier's `post.acceptance`
field — the structured handoff plan-agent produced. Todo-agent never re-parses
the plan markdown for acceptance.

---

## 5. AUTHOR / ADVANCE — A Todo Is a Markdown Checklist

A todo is a GitHub-style checklist file. The agent authors and advances it with
**file tools** — the most LLM-native edit there is — not index-addressed verbs.
(`doc$create` / `doc$update :kind :todo` remain bound as a fallback, but the
file-edit path is the documented primary; it is index-free and can't misalign a
later flip after an insertion.)

### 5.1 SPAWN — `write-file` the TODO TEMPLATE

When no todo exists for the plan slug, `write-file` to
`.brainyard/agents/todo-agent/todos/<slug>.md`:

```markdown
---
id: <slug>
file-type: todo
title: <mirror plan title>
scope: project
status: draft
created: <ISO-8601>
updated: <ISO-8601>
---

# <title>

## Goal
<one paragraph naming the source plan slug + dossier path>

## Todo
- [ ] <verb-led, atomically markable action> {via: edit-agent, covers: ["<criterion>"]}
- [ ] <…> {via: bash, covers: ["<criterion>"]}
- [ ] <…> {via: manual, covers: ["<criterion>"]}
```

The `file-type: todo` + `id` + `title` frontmatter are **required** — the read
seam (`todo-md?`) rejects a file without them. Each `- [ ]` line is an
atomically markable action; pick `{via:…}` from `#{edit-agent bash mcp manual
explore-agent read-only}` (default `:manual` when unsure); `{covers:[…]}` names
the `pre.acceptance` criteria the item supports.

The inline `{via:…, covers:[…]}` block is parsed by `todo.clj`'s
`parse-tags-block` / `parse-items` and rendered by `render-tags-block`, so
routing/coverage downstream is unaffected by hand-authoring. The writer also
normalizes `:via` against the canonical set (`valid-via`) and drops an
out-of-set value with a `::todo.invalid-via` warning rather than trusting it.

### 5.2 ADVANCE — edit the checklist directly, INDEX-FREE

When the user is updating an existing todo:

- **Flip done:**
  `(update-file {:path "…/<slug>.md" :pattern "- [ ] <unique text>" :replacement "- [x] <unique text>"})`.
  Match on enough description text to be unique; no index needed.
- **Add item:**
  `(write-file {:path "…/<slug>.md" :append true :content "- [ ] <action> {via:…, covers:[…]}\n"})`,
  or `update-file` inserting after an anchor line.
- **Edit description / retag:** `(update-file …)` on that line.
- **After ANY edit:** `(todo$sync {:slug "<slug>"})` — re-derive progress and
  refresh the TUI/web live block (§6).

For ADVANCE turns, pre-flight C5 is relaxed (the existing todo's items are the
source of truth, not the plan's approach) and the post-flight rubric runs lite
(§6.2).

```clojure
(def authored {:slug <s> :path <repo-rel> :action :spawned|:advanced|:unchanged
               :item-count N :items [<the items>]})
```

NEVER mutate plans (no `doc$update :kind :plan`). NEVER auto-dispatch exec-agent.

### Before / After

**Before — advance a todo (drifting indices, structured verbs):**

```clojure
(doc$read   {:kind :todo :slug "ship-v2-checkout"})              ; recover indices
(doc$update {:kind :todo :slug "ship-v2-checkout" :add-item "Backfill cache" :after-idx 1 :tags {…}})
(doc$update {:kind :todo :slug "ship-v2-checkout" :item-idx 3 :item-done true})  ; idx shifted by the insert above!
```

**After — advance a todo (index-free text edits):**

```clojure
(update-file {:path "…/ship-v2-checkout.md"
              :pattern "- [ ] Run bb test:component checkout"
              :replacement "- [x] Run bb test:component checkout"})
(write-file  {:path "…/ship-v2-checkout.md" :append true
              :content "- [ ] Backfill cache {via: bash, covers: [\"…\"]}\n"})
(todo$sync   {:slug "ship-v2-checkout"})   ; reconcile progress + refresh TUI
```

For routine work the root agent does the above itself — no `todo-agent` dispatch
(§8). The subagent comes out only for a vetted, plan-derived, audited backlog.

---

## 6. The Read Seam — `todo$sync` (checklist parser + st-memory mirror)

This is the nuance that makes index-free editing safe. Beyond returning the
body, a read must compute a `:progress` map and call `mirror-to-st-memory!`,
which populates `:todo-list` and `:active-todo-slug` — **the TUI and web bridge
render the live checklist from st-memory.** If the model edits the file with raw
`update-file` and nothing re-parses, the TUI goes stale.

So `todo.clj` ships a READ-ONLY reconcile seam, **`todo$sync`**, that:

1. Parses the checklist file (`parse-items` via `md->todo`) →
   `{:completed :pending :total :percent :next-item :items [{:description :done? :tags}]}`.
2. Mirrors to st-memory (`mirror-to-st-memory!`) so TUI/web/recall stay live,
   firing the `:todo/updated` hook.

It addresses a todo two ways:

- **`:slug`** — the canonical `.brainyard/agents/todo-agent/todos/` dir
  (resolves via `read-todo` with legacy read-fallback).
- **`:path`** — any checklist file, including a **working checklist** under a
  root agent's own dir (which is outside `todo-agent/todos/`).

Convention: **after any item edit, call `todo$sync` once** to re-derive progress
and refresh st-memory. The *write* is LLM-inherent; the *reconciliation* is one
deterministic call — the same read/write split as plan/explore, applied to a
mutable artifact. (`clear-st-memory-if-active!` handles deletes.)

---

## 7. Output Discipline — `.brainyard/agents/todo-agent/`

Same shape as plan-agent (`docs/design/plan-agent-design.md` §7), tuned for todos.

### 7.1 Directory Layout

```
.brainyard/
├── agents/
│   └── todo-agent/
│       ├── todos/                     ; the durable todo corpus
│       │   ├── ship-v2-checkout.md
│       │   └── ...
│       ├── dossiers/                  ; per-turn handoff records
│       │   ├── 20260510-105612-ship-v2-checkout.md
│       │   └── ...
│       ├── drafts/                    ; per-turn scratch
│       ├── INDEX.md                   ; one line per dossier
│       └── README.md
```

### 7.2 Dossier Schema (authored as markdown directly)

The model fills this template and `write-file`s it to
`dossiers/<yyyyMMdd-HHmmss>-<slug>.md` — there is **no** dossier-construction
helper chain. The `author.items[]` block becomes a markdown list the model
writes directly, not a precisely-keyed nested structure for a helper.

```markdown
---
slug: ship-v2-checkout
agent: todo-agent
created: 2026-05-10T10:56:12Z
todo_path: .brainyard/agents/todo-agent/todos/ship-v2-checkout.md
todo_status: in-progress

source:
  plan_dossier: .brainyard/agents/plan-agent/dossiers/20260510-104503-ship-v2-checkout.md
  plan_path:    .brainyard/agents/plan-agent/plans/ship-v2-checkout.md
  plan_slug:    ship-v2-checkout

pre:
  verdict: go
  checks: {c1: pass, c2: pass, c3: pass, c4: pass, c5: pass, c6: pass, c7: informational}
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

post:                               # OMIT this block when no AUTHOR ran
  verdict: pass                     # pass | hold
  rubric: {r1: pass, r2: pass, r3: pass, r4: pass, r5: pass, r6: pass, r7: pass}
  holds: []
  acceptance_coverage:
    "feature-flag checkout-v2 toggleable from staging admin": [0]
    "all checkout/* unit tests green":                         [1, 2]
    "p99 checkout latency unchanged within ±5%":               [3]

handoff:
  next_agent: exec-agent
  next_call: "(exec-agent {:question \"Drive this todo to completion.\" :agent-context \"<this dossier path>\"})"
---

# Todo dossier — Ship v2 checkout

## Pre-flight summary
Sourced from plan-agent dossier `20260510-104503-ship-v2-checkout`. All checks passed.

## Item summary
4 items spawned. Routing tags assigned; every acceptance criterion covered.

## Post-flight notes
All applicable rubric items passed.

## Handoff
Pass `<this dossier path>` to exec-agent in `:agent-context`.
```

The flow-style `checks`/`rubric`/`acceptance_coverage`/`source`/`handoff` maps
and the `acceptance`/`holds`/`related_todos` vectors are one line each — easy to
emit and read back by `todo$read-dossier`'s lenient parser
(`parse-todo-dossier-yaml`). `source`/`pre`/`author`/`post`/`handoff` are
indented named blocks.

Frontmatter contract:

| Key | Type | Description |
| --- | ---- | ----------- |
| `slug` | string | Todo slug. Often shared with the source plan slug. |
| `agent` | string | Always `todo-agent`. |
| `todo_path` | string | Repo-relative path to the todo body (`null` on GATHER/REFUSE). |
| `todo_status` | enum | `draft` \| `in-progress` \| `completed` \| `abandoned`. |
| `source.plan_dossier` / `plan_path` / `plan_slug` | string | Consumed plan-agent references. |
| `pre.*` | map | Verbatim copy of the `pre` map (§4.2). |
| `author.action` | enum | `spawned` \| `advanced` \| `unchanged`. |
| `author.item_count` | int | Item count. |
| `post.*` | map | Verbatim copy of the `post` map (§6.3) — omitted when no AUTHOR ran. |
| `post.acceptance_coverage` | map criterion → vec idx | Which items cover which criterion. Critical for eval-agent. |
| `handoff.next_agent` | string | `exec-agent` on PASS; `user` on HOLD/GATHER; `none` on REFUSE. |
| `handoff.next_call` | string | Exact `(exec-agent {…})` form, or a one-line instruction. |

### 7.3 ANSWER Format

Stable-prefix lines at the end of the agent's `answer`:

```
On AUTHOR + POST-FLIGHT = PASS:
    Saved todo: <todo path>
    Saved dossier: <dossier path>
    Next: (exec-agent {:question "Drive this todo to completion." :agent-context "<dossier path>"})

On POST-FLIGHT = HOLD:
    Saved todo: <todo path>
    Saved dossier: <dossier path>
    Hold: <one line per hold>
    Suggested: <user action OR re-call todo-agent after amending>

On PRE-FLIGHT = GATHER:
    Saved dossier: <dossier path>
    Need: <missing input>
    Suggested: (plan-agent {…}) OR <one-line user question>

On PRE-FLIGHT = REFUSE:
    Saved dossier: <dossier path>
    Refused: <reason>
    Suggested: <redirect — typically plan-agent re-run>
```

`Saved todo:` / `Saved dossier:` are the prefixes the auto-persist hook and
downstream dispatchers grep for.

---

## 8. Root-Agent-Managed Todos Without the Subagent (the substrate)

The shipped architecture separates two things "todo" used to conflate.

### 8.1 Two kinds of todo

| | (A) Working checklist | (B) Contract backlog |
| --- | --- | --- |
| **Purpose** | The agent's own "what I'm doing now" scratchpad. | The durable plan→exec→eval handoff: items tagged `via`/`covers`, acceptance coverage, dossier. |
| **Owner** | Whatever agent is doing the work (often the root agent). | The pipeline; todo-agent authors, exec-agent drives, eval-agent scores. |
| **Lifetime** | Ephemeral, this task. | Durable, audited, cross-agent. |
| **Ceremony** | None. Read/edit a checklist file. | Pre-flight gating, post-flight rubric, dossier. |
| **Analogy** | Claude Code's `TodoWrite`; this harness's `TaskCreate`/`TaskUpdate`. | A tracked execution ticket. |

Most interactive/ad-hoc work wants (A); only the formal autoresearch spine wants
(B). The question isn't "subagent or not" globally — it's "which kind of todo is
this."

### 8.2 A shared todo substrate — installed in the base agents (as-built)

The substrate is **not** an opt-in block each agent remembers to bind. It lives
in the base agents so every derived agent inherits it — exactly the mechanism
that ships for project memory.

There are two halves, and the tools half was already done:

1. **Tools — already inherited.** The shared roster
   `agent-roster/default-agent-roster` already includes
   `read-file`/`write-file`/`update-file` and the `doc$*`/`todo$*` commands
   (`todo$sync` among them). `run-coact-derived` concatenates that base roster
   onto every derived agent (`merge-derived-tools`), so `main-agent` and the
   other coact/react-derived agents already hold the tools needed to create,
   edit, and reconcile a checklist. (The specialist subagents that strip
   `write-file` via a `remove` clause are the exception, not the base.)
2. **Guidance — the shipped piece.** The *convention* — "a todo is a markdown
   checklist; flip a box with `update-file` on the line text; reconcile with
   `todo$sync` after edits" — ships as a base system-context section, modeled
   1:1 on the Project Memory protocol.

**As-built wiring:**

- The shared string is **`agent-roster/todo-substrate-protocol`** (in
  `common/agent-roster.clj`), a `## Todo substrate (a todo is a markdown
  checklist)` block, modeled on `coact_agent.clj`'s `coact-project-memory-protocol`.
- **CoAct:** `coact-system-context` adds `:todo-substrate
  agent-roster/todo-substrate-protocol` to its `cond->` and places
  `:todo-substrate` in `section-order` (next to `:exec-substrate` and the other
  substrates).
- **ReAct:** `react-system-context` does the same. (ReAct has no project-memory
  section today, so this is net-new there — and was added as part of this change.)

Net: one shared string + a section insert in each of the two base assemblers. No
change to any derived agent file; they inherit the substrate the moment the base
carries it. This mirrors the harness's own pattern — a root agent maintaining a
lightweight checklist inline (TodoWrite / TaskUpdate) rather than delegating list
bookkeeping to a child. (The companion **exec substrate**,
`exec-substrate-protocol`, ships alongside it so any agent can also *do* a
checklist item route→verify→record→flip — see `docs/design/exec-agent-design.md`.)

### 8.3 Root-agent inline management

With the substrate inherited, `main-agent` (or any orchestrator) handles working
todos itself:

```clojure
;; create a working checklist for the task at hand
(write-file {:path ".brainyard/agents/<root>/todos/refactor-loop-guard.md"
             :content "<filled TODO TEMPLATE>"})
;; … do some work, then mark an item done — index-free
(update-file {:path "…/refactor-loop-guard.md"
              :pattern "- [ ] Extract the predicate"
              :replacement "- [x] Extract the predicate"})
(todo$sync {:path ".brainyard/agents/<root>/todos/refactor-loop-guard.md"})  ; reconcile + refresh TUI
```

No pre-flight, no rubric, no dossier — a working checklist is the agent's own
scratchpad, not a cross-agent contract. (For a working checklist outside
`todo-agent/todos/`, reconcile by `:path`, as shown.)

### 8.4 When you still want todo-agent

Reserve the subagent for **(B) contract backlogs**, where the ceremony pays for
itself:

- The formal `plan → todo → exec → eval` spine (autoresearch), where eval-agent
  needs `acceptance_coverage` and exec-agent needs a dossier handoff.
- When you want the guarantee that items were *gated on a passed plan* and
  *self-critiqued* (the C1–C7 / R1–R7 rubric) before execution.

The root agent dispatches `todo-agent` not to "make a list," but to "make a
*vetted, plan-derived, audited* list." Everything else it does itself.

### 8.5 Interop & ownership

Both paths use the **same checklist format**, so they interoperate: a root agent
can hand a working checklist to todo-agent to "formalize" (add tags, coverage,
dossier), and exec-agent flips checkboxes in the same file the root agent reads.
One caution: pick a **single-writer convention** per todo at a time (the agent
currently driving it), and have everyone re-`todo$sync` to reconcile.
Last-write-wins is fine for the single-user TUI; two agents editing the same
checklist concurrently is the one footgun (not a new problem — the old
`:item-idx` race was worse, because indices drift).

---

## 9. POST-FLIGHT — Confirmation Check

Runs immediately after AUTHOR. Re-reads the just-written items and grades them
against a fixed rubric, using `query$llm` for the fuzzy judgements.

### 9.1 The Rubric (SPAWN turns)

| Item | Rubric question | Pass criterion |
| ---- | --------------- | -------------- |
| R1 | Does every item correspond to a `## Approach` bullet? | Each item maps to ≥ 1 bullet via fuzzy LLM match. |
| R2 | Is every item ATOMICALLY MARKABLE? | LLM check: "is this a single action?" |
| R3 | Do items collectively cover every `pre.acceptance` criterion? | Each criterion appears in ≥ 1 item's `tags.covers` (or fuzzy LLM coverage). Builds the `acceptance_coverage` map. |
| R4 | Item count sane (3–30). | `(<= 3 (count items) 30)`. |
| R5 | Per-item routing tags present + plausible. | Every item has `:via` in `#{:edit-agent :bash :mcp :manual :explore-agent :read-only}`. Missing `:via` WARNs; invalid `:via` FAILs. |
| R6 | No two items overlap so much that flipping one half-completes the other. | LLM pair-wise redundancy check. |
| R7 | No dangling LLM artifacts (`TODO`, `???`, `<...>`, `[fill in]`, `tk`). | Mechanical grep on each `:description`. |

### 9.2 The Rubric (ADVANCE turns)

For checkbox flips and item insertions only R5 + R7 apply, plus a lightweight
"did the right item flip?" check. The full rubric is reserved for SPAWN.

### 9.3 Verdict + `post` Map (PASS / HOLD)

As-built, POST-FLIGHT has two verdicts:

- **PASS** — every applicable rubric item passes.
- **HOLD** — 1+ rubric item fails. Record specific holds in the dossier; do NOT
  dispatch exec-agent; surface the holds in the answer. The user decides whether
  to amend the todo and re-run todo-agent, or proceed despite the hold.

> The original design's third verdict, **REVISE** (an automatic one-round
> split/retag fix), is **deferred to v1.5**. Until then any fixable failure is
> surfaced as a HOLD rather than auto-corrected.

```clojure
(def post
  {:verdict          :pass            ; or :hold
   :rubric           {:r1 :pass :r2 :pass :r3 :pass :r4 :pass
                      :r5 :pass :r6 :pass :r7 :pass}
   :holds            []               ; [{:item :r3 :description "..."}]
   :acceptance-coverage
                     {"feature-flag checkout-v2 toggleable from staging admin" [0]
                      "all checkout/* unit tests green"                         [1 2]
                      "p99 checkout latency unchanged within ±5%"               [3]}
   :item-count       4})
```

`post.acceptance-coverage` is the explicit map exec-agent and eval-agent consume
to decide which items support which criterion.

---

## 10. The Dossier as a Template + Auto-Persist Backstop

### 10.1 Authoring is `write-file`

PERSIST is one `write-file` of the §7.2 template, then one appended INDEX line
(`write-file :append true`). The model does NOT construct frontmatter maps or
call dossier helpers — it writes the markdown. If the dossier body contains
` ``` ` code fences, the agent authors the whole dossier as a four-backtick
verbatim `markdown` block (inner fences pass through), then `write-file`s the
recovered content.

The handoff block is filled from a fixed 4-case table (in the instruction):

```
pre=go,  post=pass → next_agent: exec-agent ; next_call: (exec-agent {…})
pre=go,  post=hold → next_agent: user       ; resolve holds, re-run todo-agent
pre=gather         → next_agent: user       ; run plan-agent / supply gather_question
pre=refuse         → next_agent: none        ; see refuse_reason (typically plan-agent first)
```

### 10.2 `todo.clj` — what shipped vs. retired

- **Retired (write-side):** `todo$dossier-slug`, `todo$dossier-frontmatter`,
  `todo$dossier-write`, `todo$dossier-index-append`, `todo$next-handoff`. The
  user-facing roster (`todo-dossier-helpers`) is now exactly
  **`[#'todo$read-dossier #'todo$sync]`**.
- **Kept as read seams:** `todo$read-dossier` (cheap frontmatter parse for
  exec/eval downstream) and `todo$sync` (§6).
- **Kept private (back the auto-persist hook only):** the YAML emitters
  (`build-todo-dossier-frontmatter*`, `td-yaml-*`, `td-append-index!`,
  `td-handoff-from-verdicts`, `render-todo-dossier-md`). These are no longer
  exposed as tools; they exist so the backstop can `spit` a byte-compatible
  dossier.
- **Kept for the checklist/read path:** `parse-items`/`render-items`,
  `parse-tags-block`/`render-tags-block`/`normalize-tags`, `md->todo`/`todo->md`,
  `mirror-to-st-memory!`, `clear-st-memory-if-active!`, `read-todo`/`list-todos`
  (with legacy read-fallback), and the plain item helpers (`mark-item-done`,
  `add-item`, `todo-progress`, …) used by the `doc$*` fallback CRUD.

### 10.3 Auto-Persist Backstop (`:agent.ask/finalize`)

`todo.clj` installs a gated `:agent.ask/finalize` hook
(`install-todo-auto-persist!`, self-installs at namespace load, scoped to
`:todo-agent`). When the LLM skips PERSIST — or hallucinates a `Saved dossier:`
path that isn't on disk — `materialize-todo-auto-dossier!` reconstructs a
minimal §7.2 dossier from the answer text (`render-todo-dossier-md` → `spit` +
one INDEX line) and `:replace`s the answer to inject the absent `Saved dossier:`
line.

It is **not** the primary path — author your own dossier; the regex
reconstruction is thinner than a hand-authored one. Idempotency is
**path-existence-checked** (`td-dossier-already-saved?` reads the claimed path
off disk), so a hallucinated path is caught and superseded. Failures are logged,
never re-thrown.

---

## 11. Behavior Tree — Inherited As-Is

Same as plan-agent (`docs/design/plan-agent-design.md` §10). No new BT;
`todo-agent`'s `defagent` pins `:bt-factory` to
`coact/coact-behavior-tree` explicitly (so direct-resolution entry points pick
up the right CoAct BT). Iteration shape:

| Iter | Channel | Body |
| ---- | ------- | ---- |
| 1 | code | PRE-FLIGHT C1–C7. `def pre`. |
| 2 | code | (only on GO) AUTHOR: `write-file` (SPAWN) or `update-file` (ADVANCE). `def authored`. |
| 3 | code | POST-FLIGHT rubric R1–R7 with `query$llm`. `def post` (PASS / HOLD). |
| 4 | code | PERSIST: `write-file` the dossier; append INDEX. |
| 5 | answer | `Saved todo:` + `Saved dossier:` + `Next:` (or GATHER/REFUSE/HOLD variants). |

---

## 12. Demonstration: "Spawn a todo for the Ship v2 checkout plan"

`:agent-context = "Saved dossier: .brainyard/agents/plan-agent/dossiers/20260510-104503-ship-v2-checkout.md"`.

### Iteration 1 — PRE-FLIGHT

```clojure
(def dossier-path
  (second (re-find #"Saved dossier: (\S+)" agent-context)))

(def plan-dossier (plan$read-dossier {:path dossier-path}))

(def pre
  {:verdict (if (= "pass" (-> plan-dossier :post :verdict)) :go :refuse)
   :checks  {:c1 :pass
             :c2 (if (= "pass" (-> plan-dossier :post :verdict)) :pass :fail)
             :c3 :pass :c4 :pass :c5 :pass :c6 :pass :c7 :informational}
   :plan-dossier dossier-path
   :plan-slug    (:plan_slug (:source plan-dossier))
   :plan-path    (:plan_path (:source plan-dossier))
   :acceptance   (-> plan-dossier :pre :acceptance)})  ; carried from plan dossier
```

### Iteration 2 — AUTHOR (SPAWN, write the checklist file)

```clojure
(def slug "ship-v2-checkout")
(def todo-path (str ".brainyard/agents/todo-agent/todos/" slug ".md"))
(write-file
  {:path todo-path
   :content (str
     "---\nid: " slug "\nfile-type: todo\ntitle: Ship v2 checkout\n"
     "scope: project\nstatus: draft\ncreated: " (java.time.Instant/now)
     "\nupdated: " (java.time.Instant/now) "\n---\n\n"
     "# Ship v2 checkout\n\n## Goal\nExecute plan '" slug "' (project scope). "
     "Acceptance per " (:plan-dossier pre) ".\n\n## Todo\n"
     "- [ ] Wire LD flag `checkout-v2` in src/checkout/flags.clj {via: edit-agent, covers: [\"feature-flag checkout-v2 toggleable from staging admin\"]}\n"
     "- [ ] Update payment validator for legacy carts {via: edit-agent, covers: [\"all checkout/* unit tests green\"]}\n"
     "- [ ] Run bb test:component checkout {via: bash, covers: [\"all checkout/* unit tests green\"]}\n"
     "- [ ] Sample p99 from Grafana dashboard {via: manual, covers: [\"p99 checkout latency unchanged within ±5%\"]}\n")})

(def authored {:slug slug :path todo-path :action :spawned :item-count 4})
```

### Iteration 3 — POST-FLIGHT (rubric → PASS/HOLD)

```clojure
(def items (:items (todo$sync {:slug slug})))   ; reconcile + read parsed items

;; R1, R2, R6 — query$llm fuzzy judgements; R3 coverage map; R4/R5/R7 mechanical.
(def coverage
  (reduce (fn [acc crit]
            (assoc acc crit (vec (keep-indexed
                                   (fn [i it] (when (some #{crit} (-> it :tags :covers)) i))
                                   items))))
          {} (:acceptance pre)))
(def post
  {:verdict :pass   ; or :hold if any applicable rubric item failed
   :rubric  {:r1 :pass :r2 :pass :r3 :pass :r4 :pass :r5 :pass :r6 :pass :r7 :pass}
   :holds   []
   :acceptance-coverage coverage
   :item-count (count items)})
```

### Iteration 4 — PERSIST (write the dossier markdown directly)

```clojure
(def ts (.format (java.text.SimpleDateFormat. "yyyyMMdd-HHmmss") (java.util.Date.)))
(def dossier-path (str ".brainyard/agents/todo-agent/dossiers/" ts "-" slug ".md"))
(write-file {:path dossier-path :content "<filled §7.2 DOSSIER TEMPLATE>"})
(write-file {:path ".brainyard/agents/todo-agent/INDEX.md" :append true
             :content (str "- " ts " [" slug "](dossiers/" ts "-" slug ".md) — pre:go · post:pass · → exec-agent\n")})
```

### Iteration 5 — ANSWER

```markdown
## Todo spawned — Ship v2 checkout

PRE-FLIGHT: GO (consumed plan dossier 20260510-104503)
AUTHOR: spawned (4 items)
POST-FLIGHT: PASS (R1–R7 all passed)

Saved todo: .brainyard/agents/todo-agent/todos/ship-v2-checkout.md
Saved dossier: .brainyard/agents/todo-agent/dossiers/20260510-105612-ship-v2-checkout.md
Next: (exec-agent {:question "Drive this todo to completion." :agent-context ".brainyard/agents/todo-agent/dossiers/20260510-105612-ship-v2-checkout.md"})
```

---

## 13. Handoff Mechanics

Exec-agent reads the todo-agent dossier in `:agent-context`. Two read levels:
cheap frontmatter-only via `todo$read-dossier`, full body via `read-file`. The
frontmatter gives:

- `todo_path` and `slug` for `doc$read :kind :todo` / `todo$sync`.
- `source.plan_dossier` and `source.plan_path` for plan context.
- `pre.acceptance` (carried forward from plan-agent) — what "done" means per item.
- `author.items[].tags.via` — the routing tag exec-agent uses to decide whether
  to delegate to edit-agent / bash / etc.
- `post.acceptance_coverage` — eval-agent uses this directly when scoring.

For ADVANCE turns the dossier records the single item that changed and the new
`todo_status`; downstream tooling treats it as an incremental record.

### 13.1 Exec-agent implication

Exec-agent "drives the todo to completion" — it flips checkboxes as items
finish. Under this design it flips via `update-file` on the description text and
calls `todo$sync` to reconcile — the same index-free path. This makes
exec-agent's job *simpler* (it edits the line it just completed, by text,
instead of resolving an index). See `docs/design/exec-agent-design.md` and the
exec substrate (§8.2).

---

## 14. Migration — Complete

The lightweight file-tool redesign + root-agent substrate landed as a single
change; the storage migration to the new layout was completed alongside it.

### 14.1 File-tool authoring + substrate (done)

- `todo_agent.clj` instruction/tool-context swapped AUTHOR/ADVANCE and PERSIST to
  file edits; bound `write-file`/`update-file`/`read-file` (dropped the old
  `remove` clause that stripped them; `fetch-url` is the only file-tool kept out).
- `todo.clj`: item-mutation paths stay available behind the `doc$*` fallback but
  are no longer the documented surface; the write-side dossier helpers were
  removed from the roster; `todo$sync` added; the auto-persist hook simplified to
  fill the §7.2 template and `spit`.
- **Substrate:** `todo-substrate-protocol` added to `agent-roster.clj` and wired
  into `coact-system-context` + `react-system-context` `section-order`. No
  derived-agent file changed; the tools already rode `default-agent-roster`.

### 14.2 Storage layout (done)

- New todos write to `.brainyard/agents/todo-agent/todos/<slug>.md`; the legacy
  `.brainyard/todos/` is read-fallback for one release (`find-todo-file`,
  `list-todos` tags entries `:layout :new` / `:layout :legacy`).
- `bb migrate:todo-agent` copies legacy todos across in one shot; legacy
  preserved.
- On-disk todo format is **unchanged** (GitHub checkboxes + inline tags), so
  existing todos keep working and `parse-items` / st-memory mirroring is
  untouched — only the *mutation surface* moved from index-verbs to file edits.

---

## 15. Verification

| Benchmark | Shape | What it verifies |
| --------- | ----- | ---------------- |
| Pre-flight GO happy path | Valid plan dossier (`post.verdict pass`) | All C1–C7 pass; SPAWN runs; POST-FLIGHT PASS; one dossier with full coverage map. |
| Pre-flight GATHER (no plan dossier) | `:agent-context = ""` | C1 fails; agent recommends plan-agent; dossier records GATHER. |
| Pre-flight REFUSE (plan post HOLD) | Plan dossier `post.verdict hold` | C2 fails; agent refuses; dossier names the plan holds. |
| Pre-flight GATHER (duplicate todo) | Existing todo same slug | C4 fails; agent suggests extend or fresh attempt. |
| Pre-flight C7 informational | Session `:no-auto-spawn? true` | Recorded as `:informational`; does NOT block (does not flip GO→GATHER). |
| **Spawn (file-write)** | Clean SPAWN | `write-file` authors the checklist; `parse-items` reads N items; `todo$sync` populates st-memory `:todo-list`. |
| **Flip (index-free)** | `update-file` a checkbox | `todo$sync` shows completed+1, correct `:next-item`; st-memory refreshed. |
| **Insert then flip** | Add an item, then flip an *earlier* item by text | The right line flips (the test today's `:item-idx` drift fails). |
| Post-flight PASS | Well-formed items | All R1–R7 pass; `acceptance_coverage` complete. |
| Post-flight HOLD | A rubric item fails (e.g. R3 gap) | Verdict HOLD; holds recorded; NO `Next:` to exec-agent. (No REVISE auto-round in v1.) |
| ADVANCE checkbox flip | User marks item 2 done | Lite rubric; dossier records the single update; `author.action advanced`. |
| **Dossier (file-write)** | Non-trivial run | `write-file` from template; `todo$read-dossier` returns all contract keys incl. `acceptance_coverage`. |
| Routing tag enumeration | Spawn 10 mixed items | Every item carries a `:via` tag in the allowed set; out-of-set dropped with warning. |
| **Root substrate** | main-agent (with the substrate section) creates + advances a working checklist | Zero todo-agent dispatches; `todo$sync :path …` keeps st-memory live. |
| Downstream unchanged | New dossier → exec-agent fixture | Reads `via`/`covers`/coverage as before. |
| Dual-read fallback | Legacy todo at `.brainyard/todos/<slug>.md` | `read-todo` returns body; `list-todos` tags it `:layout :legacy`. |
| Migration | `bb migrate:todo-agent` against fixture | All files copied; legacy preserved. |
| Index integrity | Append 100 dossiers | INDEX.md has 100 lines. |
| Auto-persist backstop | Skipped / hallucinated dossier | `:agent.ask/finalize` hook materializes a parseable one; injects `Saved dossier:`; on-disk idempotency catches a hallucinated path. |

mulog signals: `::todo.invalid-via`, `::todo.sync`, `::todo.dossier-index`,
`::todo.auto-persist`, `::todo.auto-persist-failed`.

---

## 16. Files Summary

| File | Role |
| ---- | ---- |
| `components/agent/src/ai/brainyard/agent/common/todo_agent.clj` | `instruction` (3-phase pipeline, file-tool AUTHOR/ADVANCE, write-markdown PERSIST), `tool-context`, `defagent todo-agent` via `coact/run-coact-derived`. Binds `write-file`/`update-file`/`read-file` (drops `fetch-url`); `doc$*` CRUD kept as fallback. |
| `components/agent/src/ai/brainyard/agent/common/todo.clj` | Checklist parse/render + `:tags` parsing; `todo$sync` (read seam); `todo$read-dossier`; `mirror-to-st-memory!`; legacy read-fallback; `:agent.ask/finalize` auto-persist hook. Write-side dossier helpers removed from the roster (`todo-dossier-helpers = [todo$read-dossier todo$sync]`). |
| `components/agent/src/ai/brainyard/agent/common/agent_roster.clj` | `todo-substrate-protocol` (the `## Todo substrate` section) — tools already ride `default-agent-roster`. |
| `components/agent/src/ai/brainyard/agent/common/coact_agent.clj` | `coact-system-context` wires `:todo-substrate` into its `cond->` + `section-order`. Substrate, BT, sandbox, DSPy signature otherwise untouched. |
| `components/agent/src/ai/brainyard/agent/common/react_agent.clj` | `react-system-context` wires the same `:todo-substrate` section (net-new there). |
| `components/agent/test/ai/brainyard/agent/todo_agent_test.clj` | Pre/post-flight, file-edit (flip/insert), dossier-read, root-substrate, dual-read, auto-persist tests. |
| `.brainyard/agents/todo-agent/README.md` | Directory layout cheat-sheet. |
| `bb.edn` | `migrate:todo-agent` one-shot file-move task. |

---

## 17. Open Questions

1. **REVISE auto-round (v1.5).** Re-introduce the automatic one-round split/retag
   fix (close R3 gaps, drop R6 overlaps) before falling through to HOLD.
2. **Auto-sync on edit?** A `:agent.tool-use/post` hook on `update-file`
   targeting a `todos/*.md` path could call `todo$sync` automatically so the
   model never forgets to reconcile — at the cost of coupling the hook registry
   to the todo path convention.
3. **Where does the working/contract boundary live** — directory
   (`<root>/todos/` vs `todo-agent/todos/`), a frontmatter `kind: working|contract`,
   or presence of a dossier? Directory is simplest; frontmatter is most explicit.
4. **Should `main-agent` get the substrate by default**, or opt-in? Default
   reduces dispatches but widens the tool surface. (Shipped: it rides the base
   section, so it is present.)
5. **Status as frontmatter edit vs. guarded helper.** Pure `update-file` is
   uniform; a `todo$set-status` could enforce "no complete while pending."
6. **`:tags.via` / `:tags.covers` required vs. optional.** Required forces
   deliberation; optional is forgiving. Currently warn (not block) on missing
   `:via`; require `:covers` semantically for R3.
7. **Multi-plan todos.** A todo executing against two plans simultaneously
   (cross-cutting refactors). The schema assumes one source plan; extend `source`
   to a vector if the use case appears.
8. **Roll index-free editing to plan/explore?** They're mostly write-once, so the
   payoff is smaller, but the consistency is nice. Deferred.
