# Todo-Agent — Lightweight File-Tool Redesign + Root-Agent-Managed Todos (revision 3)

> **Status:** Proposal. Third in the series after
> [`plan-agent-lightweight-redesign.md`](./plan-agent-lightweight-redesign.md)
> and [`explore-agent-lightweight-redesign.md`](./explore-agent-lightweight-redesign.md).
> Applies the same "write markdown directly, retire the construction helpers"
> treatment, and adds two pillars todo-agent specifically needs:
>
> 1. **LLM-inherent todo-item management.** A todo is a GitHub-style markdown
>    checklist. Flip/add/edit items by *editing the markdown* with
>    `update-file`/`write-file`, not through `doc$update :item-idx N :item-done`
>    item micro-tools.
> 2. **Root-agent-managed todos without a subagent.** Because a checklist is
>    just a file, *any* agent (the root/main agent included) can manage it with
>    file tools — no `todo-agent` dispatch required for routine work. This
>    section also corrects the framing: there are really *two* kinds of todo,
>    and only one of them needs the subagent. The substrate is installed in the
>    **base agents** (`coact-agent` + `react-agent`) so every derived agent
>    inherits it — modeled on the existing Project Memory protocol (§8.2, §10b).
>
> **Scope:** `components/agent/src/ai/brainyard/agent/common/todo_agent.clj`,
> `todo.clj`, and a shared "todo substrate" section added to the base
> `coact_agent.clj` / `react_agent.clj` system-context (inherited by all
> derived agents).

---

## 1. Why the current paths are error-prone (there are two)

Todo-agent stacks **two** layers of micro-tool brittleness, where plan/explore
had one.

**Layer 1 — the dossier helper chain** (identical to plan-agent): build `pre` /
`source` / `author` / `post` / `handoff` maps with exact keys, feed them to
`todo$dossier-frontmatter` (strict ordered YAML, nested `author.items[]` with
`tags: {via, covers}`), `todo$dossier-write` (frontmatter-prefix validated),
`todo$dossier-index-append` (verdicts re-passed), `todo$next-handoff`. Same
auto-persist hook bolted on as a crutch.

**Layer 2 — the item-mutation micro-tools** (todo-agent's own): the todo *body*
is manipulated through structured verbs —

```clojure
(doc$update {:kind :todo :slug s :item-idx 3 :item-done true})   ; flip checkbox by INDEX
(doc$update {:kind :todo :slug s :add-item "desc" :after-idx 2 :tags {…}})
(doc$update {:kind :todo :slug s :item-idx 1 :item-done false})  ; reset by INDEX
```

This second layer is the worse offender, because unlike a plan or an
exploration (write-once, mostly-read), a todo is **mutated over and over** as
work proceeds. The micro-tool design forces the model to:

- **Track numeric `:item-idx` across turns** — and those indices *drift* the
  moment `:add-item :after-idx` inserts a row, so "mark item 3 done" silently
  targets the wrong line after an insertion.
- **Pick the right structured verb** for each mutation (`:item-done` vs
  `:add-item` vs `:status`) and get its schema right.
- **Round-trip through `doc$read`** to recover indices it can't see.

Yet the todo body is, on disk, exactly this (`todo.clj` `render`/`parse-checkboxes`):

```markdown
## Todo
- [ ] Wire LD flag `checkout-v2` in src/checkout/flags.clj {via: edit-agent, covers: ["…"]}
- [x] Update payment validator for legacy carts {via: edit-agent, covers: ["…"]}
```

Flipping a checkbox is changing one character: `- [ ]` → `- [x]`. Adding an item
is appending a line. **This is the single most LLM-native edit there is** —
it's how every markdown TODO list, GitHub issue checklist, and the harness's own
TodoWrite/TaskUpdate work. Routing it through index-addressed structured verbs
fights the model's fluency for no benefit.

## 2. Thesis

Three shifts:

1. **Dossier authoring goes LLM-inherent** (as in plan/explore): `write-file` the
   dossier from a template; retire `todo$dossier-*`.
2. **Item management goes LLM-inherent (NEW):** treat the todo as a markdown
   checklist and mutate it with `update-file` (flip a checkbox, edit a line) and
   `write-file`/append (add an item). Retire the `:item-idx`/`:item-done`/
   `:add-item` mutation surface. Keep a *deterministic read seam* that parses the
   checklist into a progress map and mirrors it to st-memory (the TUI depends on
   that — §6).
3. **Todos become root-agent-managable without the subagent (NEW):** the
   checklist + read seam is a shared substrate any agent can use directly;
   `todo-agent` is reserved for the formal, gated, audited authoring path (§8).

One line: **editing a checkbox should be editing a checkbox** — and you
shouldn't need to spawn a subagent to do it.

## 3. Design principles

1. **Writes are LLM-inherent.** Dossier *and* todo items authored/mutated with
   file tools. No frontmatter constructors, no index-addressed item verbs.
2. **Reads stay deterministic.** Two read seams survive: the dossier
   frontmatter reader (`todo$read-dossier`) and a **checklist reader** that
   yields `{:completed :pending :total :percent :next-item :items}` and mirrors
   to st-memory. Parsing checkboxes is where a machine beats the model.
3. **Index-free mutation.** Items are addressed by their *text* (the line you
   edit), never by a drifting ordinal. Insertions can't misalign a later flip.
4. **The contract is the file + the template.** Checklist format and dossier
   keys are unchanged from today, enforced by showing them — not by constructors.
5. **The subagent is optional, not the gate.** Managing a todo does not require
   `todo-agent`. The subagent adds *discipline* (plan-coverage gating, the
   acceptance rubric, the exec handoff dossier) for the cases that want it (§8).
6. **Degrade gracefully.** A checkbox edit can't fail a schema. A bad line is
   caught by the lenient checklist parser; the slimmed auto-persist hook still
   backstops a skipped dossier.

## 4. What stays, what goes

| Concern | Today | Redesign |
| --- | --- | --- |
| Spawn a todo | `doc$create :kind :todo :items [{:description :tags}]` | `write-file` the todo markdown (goal + `## Todo` checklist) from a template. |
| Flip a checkbox | `doc$update :item-idx N :item-done true` | `update-file :pattern "- [ ] <text>" :replacement "- [x] <text>"`. Index-free. |
| Add an item | `doc$update :add-item "…" :after-idx N` | `update-file` (insert after an anchor line) or `write-file :append`. |
| Edit goal | `doc$update :goal "…"` | `update-file` on the goal line/section. |
| Status flip | `doc$update :status :completed` | `update-file` the `status:` frontmatter line (or a `todo$set-status` reader-writer if guard logic matters — §10). |
| Read progress / items | `doc$read :kind :todo` (parses + mirrors st-memory) | **Keep** as a read-only `todo$read`/`todo$sync` seam (§6). |
| List todos (dup check C4) | `doc$list :kind :todo` | **Keep** — deterministic enumeration. |
| Dossier build/write/index | `todo$dossier-frontmatter`/`write`/`index-append`/`next-handoff` | **Removed.** `write-file` from the dossier template (§7). |
| Plan dossier read (C1/C2) | `plan$read-dossier` | **Keep** — read-only cross-agent seam. |
| Dossier read (downstream) | `todo$read-dossier` | **Keep.** |
| Auto-persist net | rebuilds via helpers | **Keep, simplified** — fills the template, `spit`s one file. |

Net: the four write-side dossier helpers **and** the item-mutation verbs retire;
the read/enumerate seams (`todo$read`/`todo$sync`, `doc$list`, `plan$read-dossier`,
`todo$read-dossier`) stay; `write-file`/`update-file`/`read-file` get bound (today
they're stripped by the `remove` clause in `todo_agent.clj` `:agent-tools`).

## 5. The todo as a markdown checklist

The instruction carries this template; the model `write-file`s it to
`.brainyard/agents/todo-agent/todos/<slug>.md`. Format is exactly what
`todo.clj`'s `parse-checkboxes` already reads, so the read seam is unchanged.

```markdown
---
slug: <kebab-slug>
title: <title>
status: <draft | in-progress | completed | abandoned>
goal: <one line: source plan slug + dossier path>
created: <ISO-8601>
---

# <title>

## Goal
<one paragraph: what done looks like; names the source plan dossier>

## Todo
- [ ] <verb-led, atomically markable action> {via: edit-agent, covers: ["<criterion>"]}
- [ ] <…> {via: bash, covers: ["<criterion>"]}
- [ ] <…> {via: manual, covers: ["<criterion>"]}
```

Mutations, as plain edits:

- **Flip done:** `update-file :pattern "- [ ] Wire LD flag \`checkout-v2\`" :replacement "- [x] Wire LD flag \`checkout-v2\`"`. Match on enough of the description to be unique; no index needed.
- **Add item:** `update-file` inserting a line after an anchor, or `write-file :append` a `- [ ] …` line under `## Todo`.
- **Edit description / retag:** `update-file` on that line.

`{via:…, covers:[…]}` inline tags are preserved verbatim — `parse-checkboxes`
already extracts them, so routing/coverage downstream is unaffected.

## 6. The read seam — checklist parser + st-memory mirror (must keep)

This is the critical nuance that makes index-free editing safe. Today
`doc$read :kind :todo` does two things beyond returning the body: it computes a
`:progress` map and calls `mirror-to-st-memory!`, which populates `:todo-list`
and `:active-todo-slug` — **the TUI and web bridge render the live checklist
from st-memory.** If we let the model edit the file with raw `update-file` and
nothing re-parses, the TUI goes stale.

So keep a read-only seam — call it `todo$read` (or a thinner `todo$sync`) — that:

1. Parses the checklist file (`parse-checkboxes`) → `{:completed :pending :total
   :percent :next-item :items [{:description :done? :tags}]}`.
2. Mirrors to st-memory (`mirror-to-st-memory!`) so TUI/web/recall stay live.

Convention: **after any item edit, call `todo$read` (or `todo$sync`) once** to
re-derive progress and refresh st-memory. The *write* is LLM-inherent; the
*reconciliation* is one deterministic call. This is the same read/write split as
plan/explore — writes are prose, reads are typed — applied to a mutable artifact.

(Implementation: `todo$read`'s parse + mirror logic already exists in `todo.clj`;
we keep it and drop only the *mutation* verbs from `doc$update :kind :todo`.)

## 7. The dossier as a template

Unchanged in spirit from plan-agent §5; keys match `todo-agent-design.md` §7.2 so
exec/eval consumers don't change. The model fills `<…>` and `write-file`s it to
`dossiers/<yyyyMMdd-HHmmss>-<slug>.md`. The `author.items[]` block — which today
the model builds as a precisely-keyed nested structure for the helper — becomes
a markdown list it writes directly:

```markdown
---
slug: <slug>
agent: todo-agent
created: <ISO-8601>
todo_path: <.brainyard/agents/todo-agent/todos/<slug>.md>
todo_status: <draft | in-progress | completed | abandoned>

source: {plan_dossier: <path>, plan_path: <path>, plan_slug: <slug>}

pre:
  verdict: <go | gather | refuse>
  checks: {c1: pass, c2: pass, c3: pass, c4: pass, c5: pass, c6: pass, c7: informational}
  acceptance: ["<criterion 1>", "<criterion 2>"]
  related_todos: []
  gather_question: <or null>
  refuse_reason: <or null>

author:
  action: <spawned | advanced | unchanged>
  item_count: <int>

post:
  verdict: <pass | hold>     # omit block when no AUTHOR ran
  rubric: {r1: pass, r2: pass, r3: pass, r4: pass, r5: pass, r6: pass, r7: pass}
  holds: []
  acceptance_coverage: {"<criterion>": [0], "<criterion>": [1, 2]}

handoff: {next_agent: <exec-agent | user | plan-agent>, next_call: '<exact call>'}
---

# Todo dossier — <title>
## Pre-flight summary
## Item summary
## Post-flight notes
## Handoff
```

The flow-style `checks`/`rubric`/`source`/`handoff`/`acceptance_coverage` maps are
one line each — easy to emit, and the existing lenient parser reads them.

## 8. Root-agent-managed todos without a subagent (the correction)

You asked whether a root agent can handle todo items *without* resorting to the
todo-agent subagent. Short answer: **yes — and it should, for most cases.** But
the clean way to say it requires separating two things that "todo" currently
conflates.

### 8.1 Two kinds of todo

| | (A) Working checklist | (B) Contract backlog |
| --- | --- | --- |
| **Purpose** | The agent's own "what I'm doing now" scratchpad to stay on track within a task. | The durable plan→exec→eval handoff: items tagged with `via`/`covers`, acceptance coverage, dossier. |
| **Owner** | Whatever agent is doing the work (often the root agent). | The pipeline; todo-agent authors it, exec-agent drives it, eval-agent scores it. |
| **Lifetime** | Ephemeral, this task. | Durable, audited, cross-agent. |
| **Ceremony** | None. Read/edit a checklist file. | Pre-flight gating on a sound plan, post-flight rubric, dossier. |
| **Analogy** | Claude Code's `TodoWrite`; this harness's `TaskCreate`/`TaskUpdate`. | A tracked execution ticket. |

The current architecture only offers (B) — every todo touch goes through a
heavyweight gated subagent. That's overkill for (A), which is the *common* case.
**The correction to the framing:** the question isn't "subagent or not" globally;
it's "which kind of todo is this." Most interactive/ad-hoc work wants (A); only
the formal autoresearch spine wants (B).

### 8.2 A shared todo substrate — installed in the base agents

Don't make the substrate an opt-in block each agent remembers to bind. **Put it
in the base agents (`coact-agent` and `react-agent`) so every derived agent
inherits it for free** — exactly the mechanism that already ships for project
memory.

There are two halves, and the key realization is that **the tools half is
already done**:

1. **Tools — already inherited.** The shared roster `agent-roster/default-agent-roster`
   is `all-common-tools + all-common-commands`, which already includes
   `read-file`/`write-file`/`update-file` and the `doc$*`/`todo$*` commands.
   `run-coact-derived` concatenates that base roster onto every derived agent
   (`merge-derived-tools`). So `main-agent` and the other coact/react-derived
   agents *already hold* the tools needed to create, edit, and reconcile a
   checklist. (The specialist subagents that strip `write-file` via a `remove`
   clause are the exception, not the base.)
2. **Guidance — the missing piece.** What's absent is the *convention*: "a todo
   is a markdown checklist; flip a box with `update-file` on the line text;
   reconcile with `todo$read` after edits." Add this as a **base
   system-context section**, modeled 1:1 on `coact-project-memory-protocol` /
   the `## Project Memory (.brainyard/memory/)` section.

**The precedent.** `coact_agent.clj` already defines `coact-project-memory-protocol`
(a prose protocol for managing `.brainyard/memory/<slug>.md` with plain
read/write/update-file) and renders it as a `## Project Memory` section inside
`coact-system-context`'s `section-order`. Every coact-derived agent gets it with
zero per-agent wiring. The todo substrate is the same shape:

- Define a shared `todo-substrate-protocol` string (the §9 "Todo substrate"
  block) once, in a place both base agents can read (e.g. `agent-roster` or a
  small `common` ns).
- **CoAct:** add a `:todo-substrate` section to `coact-system-context`'s
  `cond->` and to `section-order` (next to `:project-memory`).
- **ReAct:** `react-system-context` has **no** project-memory section today, so
  add the same `:todo-substrate` section there too (this is the one place the
  precedent doesn't already cover both base agents — closing it is part of this
  change).

Net: one shared string + a section insert in each of the two base assemblers.
No change to any derived agent file; they inherit the substrate the moment the
base carries it. This is precisely the harness's own pattern — a root agent
maintaining a lightweight checklist inline (TodoWrite / TaskUpdate) rather than
delegating list bookkeeping to a child.

### 8.3 Root-agent inline management

With the substrate bound, `main-agent` (or any orchestrator) handles todos
itself:

```clojure
;; create a working checklist for the task at hand
(write-file {:path ".brainyard/agents/<root>/todos/refactor-loop-guard.md"
             :content "<filled TODO TEMPLATE>"})
;; … do some work, then mark an item done — index-free
(update-file {:path "…/refactor-loop-guard.md"
              :pattern "- [ ] Extract the predicate"
              :replacement "- [x] Extract the predicate"})
(todo$read {:slug "refactor-loop-guard"})   ; reconcile progress + refresh TUI
```

No pre-flight, no rubric, no dossier — because a working checklist is the
agent's own scratchpad, not a cross-agent contract. This is the natural,
subagent-free path you were reaching for.

### 8.4 When you still want todo-agent

Reserve the subagent for **(B) contract backlogs**, where the ceremony pays for
itself:

- The formal `plan → todo → exec → eval` spine (autoresearch), where eval-agent
  needs `acceptance_coverage` and exec-agent needs a dossier handoff.
- When you want the guarantee that items were *gated on a passed plan* and
  *self-critiqued* (the C1–C7 / R1–R7 rubric) before execution.

In other words: the root agent dispatches `todo-agent` not to "make a list," but
to "make a *vetted, plan-derived, audited* list." Everything else it does itself.

### 8.5 Interop & ownership

Because both paths use the **same checklist format**, they interoperate: a root
agent can hand a working checklist to todo-agent to "formalize" (add tags,
coverage, dossier), and exec-agent flips checkboxes in the same file the root
agent reads. One caution worth stating: pick a **single-writer convention** per
todo at a time (the agent currently driving it), and have everyone re-`todo$read`
to reconcile — last-write-wins is fine for the single-user TUI, but two agents
editing the same checklist concurrently is the one footgun. (Not a new problem;
today the `:item-idx` race exists too, and is worse because indices drift.)

### 8.6 Net architectural recommendation

- Install the checklist substrate in the **base agents** (`coact-agent` +
  `react-agent`) as a system-context section, mirroring the Project Memory
  protocol (§8.2). Every derived agent inherits it; the tools are already in
  `default-agent-roster`.
- Let the root agent own working checklists inline (§8.3) — no dispatch.
- Keep `todo-agent` as the **opt-in formalizer** for contract backlogs (§8.4),
  itself rewritten to author via file tools (§4–§7).
- Net effect: far fewer subagent dispatches, index-free edits, a TUI that still
  updates because the one deterministic seam (`todo$read`) is preserved, and the
  checklist convention available to *every* agent from one base-level edit.

## 9. New instruction & tool-context (sketch)

todo-agent keeps its three-phase discipline (it's the formalizer now), but its
AUTHOR/ADVANCE and PERSIST sections swap to file edits:

```text
AUTHOR (SPAWN) — write-file the todo from the TODO TEMPLATE; items are
  `- [ ] <action> {via:…, covers:[…]}` lines under ## Todo.
ADVANCE — edit the checklist directly:
  • flip:  update-file  "- [ ] <text>" → "- [x] <text>"   (match on text, NOT index)
  • add:   update-file insert a "- [ ] …" line, or write-file :append
  • after ANY edit: todo$read <slug>  (re-derive progress, refresh st-memory)
PERSIST — fill the DOSSIER TEMPLATE and write-file it; append one INDEX line.
  Do NOT construct frontmatter maps or call dossier helpers.
```

The "Todo substrate" tool-context block (shareable with root agents):

```text
## Todo substrate — a todo is a markdown checklist
- Create:  write-file the todo (## Todo with `- [ ] desc {via:…, covers:[…]}` lines).
- Flip:    update-file "- [ ] <unique text>" → "- [x] <unique text>". No indices.
- Add:     update-file (insert after an anchor line) or write-file :append.
- Reconcile: todo$read :slug <s>  — READ-ONLY. Parses checkboxes → progress map
             and refreshes the TUI/web (st-memory). Call after edits.
- List:    doc$list :kind :todo   — enumerate todos (dup check).
```

## 10. `todo.clj` changes

- **Remove** the item-mutation paths from `doc$update :kind :todo`
  (`:item-idx`/`:item-done`, `:add-item`/`:after-idx`, `:goal` rewrite) and the
  dossier write helpers (`todo$dossier-slug/frontmatter/write/index-append`,
  `todo$next-handoff`) + their YAML emitters.
- **Keep** `create-todo`'s parse/render (`parse-checkboxes`, `render`,
  `todo->md`/`md->todo`), now used by the read seam and the template.
- **Keep & expose** `todo$read` (or add a thin `todo$sync`) wrapping
  `parse-checkboxes` + `mirror-to-st-memory!` — the reconciliation seam (§6).
  Keep `clear-st-memory-if-active!` for deletes.
- **Keep** `todo$read-dossier`, `plan$read-dossier` (read seams), `doc$list`.
- **Optionally keep** a `todo$set-status` reader-writer if status transitions
  need guard logic (e.g., refuse `:completed` while pending>0) — a one-line
  edit otherwise.
- **Simplify the auto-persist hook** to fill the §7 template and `spit` (same as
  plan/explore).
- In `todo_agent.clj` `:agent-tools`, **drop the `remove` clause** stripping
  `:write-file`/`:update-file`; **drop** `todo/todo-dossier-helpers` from the
  `concat` (or replace with `[#'todo/todo$read #'todo/todo$read-dossier]`).

### 10b. Base-agent wiring (the substrate)

To make the substrate inherited rather than opt-in (§8.2):

- **New shared string** `todo-substrate-protocol` (the §9 "Todo substrate"
  block) — place it where both base agents can require it without a cycle
  (e.g. `ai.brainyard.agent.common.agent-roster`, alongside
  `default-agent-roster`, or a tiny dedicated ns). Model the prose on
  `coact-project-memory-protocol`.
- **`coact_agent.clj`** — add a `format-todo-substrate-section`-style helper (or
  just the static string) and wire `:todo-substrate` into `coact-system-context`'s
  `cond->` and `section-order`, adjacent to `:project-memory`.
- **`react_agent.clj`** — add the same `:todo-substrate` section to
  `react-system-context`'s `sections`/`section-order`. (ReAct has no
  project-memory section, so this is net-new there.)
- **No roster change** — `read-file`/`write-file`/`update-file` and `todo$read`
  already ride `default-agent-roster`, which every coact/react-derived agent
  inherits via `merge-derived-tools`. Verify `todo$read`/`todo$sync` is exported
  as a common command (it is, via the `doc$*`/`todo$*` family); if a thinner
  read-only `todo$sync` is introduced (§6), add it to `all-common-commands`.

## 11. Exec-agent implication

Exec-agent "drives the todo to completion" — meaning *it* flips checkboxes as
items finish. Today that's `doc$update :item-idx N :item-done true`. Under this
redesign exec-agent flips via `update-file` on the description text and calls
`todo$read` to reconcile — the same index-free path. This makes exec-agent's job
*simpler* (it edits the line it just completed, by text, instead of resolving an
index), and is a natural companion change when exec-agent gets its own redesign.

## 12. Migration

- Land the new `todo_agent.clj` instruction/roster and slimmed `todo.clj`.
- On-disk todo format is **unchanged** (still GitHub checkboxes + inline tags),
  so existing todos keep working and `parse-checkboxes`/st-memory mirroring is
  untouched — only the *mutation API* changes from index-verbs to file edits.
- Remove the item-mutation + dossier-write helper tests; keep parser/mirror/read
  tests; add checklist-edit and root-substrate tests (§14).
- Expose the "Todo substrate" tool-context to main-agent behind a flag so root
  agents can manage working checklists; measure dispatch reduction.

## 13. Risks & mitigations

| Risk | Mitigation |
| --- | --- |
| `update-file` checkbox flip matches the wrong/again line. | Match on enough description text to be unique; `update-file` is first-match + diff-returning, so the model sees what changed. For dup descriptions, include a distinguishing token. |
| TUI/web goes stale after a raw file edit. | The §6 read seam: mandate a `todo$read`/`todo$sync` after edits to re-mirror st-memory. Optionally a file-watch or a post-edit hook that auto-syncs. |
| Two agents edit the same checklist concurrently. | Single-writer convention per todo (§8.5) + reconcile-on-read. Strictly better than today's drifting-index race. |
| Losing auto-computed `:progress`. | Preserved — `todo$read` still computes it from the parsed checkboxes. |
| Root agents create unvetted "todos" that masquerade as contract backlogs. | Keep them in the root agent's own dir (working checklist) vs. `todo-agent/todos/` (contract). Only todo-agent writes dossiers; absence of a dossier = it's a working list, not a pipeline artifact. |
| Status guard (`:completed` while pending) lost. | Keep a thin `todo$set-status` reader-writer, or have the dossier post-flight assert pending=0 before `status: completed`. |

## 14. Verification

- **Spawn** — write-file a todo; `parse-checkboxes` reads N items; st-memory
  `:todo-list` populated via `todo$read`.
- **Flip (index-free)** — `update-file` a checkbox; `todo$read` shows
  completed+1, correct `:next-item`; st-memory refreshed.
- **Insert then flip** — add an item, then flip an *earlier* item by text;
  assert the right line flipped (the test that today's `:item-idx` drift fails).
- **Dossier** — write-file from template; `todo$read-dossier` returns all
  contract keys incl. `acceptance_coverage`.
- **Downstream unchanged** — feed the dossier to an exec-agent fixture; reads
  `via`/`covers`/coverage as before.
- **Root substrate** — main-agent (with the substrate block) creates + advances a
  working checklist with zero todo-agent dispatches; st-memory stays live.
- **Auto-persist backstop** — skipped dossier → hook materializes a parseable one.

New mulog: `::todo.item-edit` (`{:slug :action :matched-text}`), `::todo.sync`
(`{:slug :completed :total}`), alongside the existing dossier signals.

## 15. Open questions

1. **Auto-sync on edit?** A `:agent.tool-use/post` hook on `update-file` targeting
   a `todos/*.md` path could call `todo$read` automatically, so the model never
   forgets to reconcile. Cleaner UX; couples the hook registry to the todo path
   convention. Prototype it.
2. **Where does the working/contract boundary live** — directory (`<root>/todos/`
   vs `todo-agent/todos/`), a frontmatter `kind: working|contract`, or presence
   of a dossier? Directory is simplest; frontmatter is most explicit.
3. **Should `main-agent` get the substrate by default**, or opt-in? Default
   reduces dispatches but widens main-agent's tool surface. Lean opt-in first,
   measure.
4. **Status as frontmatter edit vs. guarded helper.** Pure `update-file` is
   uniform; a `todo$set-status` enforces "no complete while pending." Pick per how
   load-bearing the guard is.
5. **Roll index-free editing to plan/explore?** They're mostly write-once, so the
   payoff is smaller, but the consistency is nice. Defer.

---

## Appendix — before/after

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
(todo$read   {:slug "ship-v2-checkout"})   ; reconcile progress + refresh TUI
```

And for routine work, the root agent does the above itself — no `todo-agent`
dispatch at all. The subagent comes out only when you want a *vetted,
plan-derived, audited* backlog with a dossier for exec/eval.
