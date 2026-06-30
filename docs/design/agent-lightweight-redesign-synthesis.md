# Agent Lightweight Redesign — Synthesis & Rollout

> **Status:** Umbrella note for the six per-agent redesign proposals. Read this
> first; it states the one principle behind all of them, what each agent
> contributes, the shared invariants, the cross-cutting decisions to settle
> once, and a phased rollout order.
>
> The docs:
> [plan](./plan-agent-lightweight-redesign.md) ·
> [explore](./explore-agent-lightweight-redesign.md) ·
> [todo](./todo-agent-lightweight-redesign.md) ·
> [exec](./exec-agent-lightweight-redesign.md) ·
> [edit](./edit-agent-design.md) (renamed from update-agent) ·
> [eval](./eval-agent-lightweight-redesign.md) ·
> [research](./research-agent-lightweight-redesign.md) (the orchestrator) ·
> [workflow](./workflow-agent-lightweight-redesign.md) (template-driven orchestrator) ·
> [skill](./skill-agent-lightweight-redesign.md) (lifecycle + skill substrate) ·
> [mcp](./mcp-agent-lightweight-redesign.md) (lifecycle + MCP substrate) ·
> [tool + meta](./tool-meta-agent-lightweight-redesign.md) (user-artifact lifecycle pair) ·
> [main](./main-agent-lightweight-redesign.md) (the front-door router).
>
> Boundary: [acp-agent](./acp-agent-redesign-boundary.md) sits *outside* the
> series — a pure transport adapter (not CoAct-derived, authors nothing), so the
> arguments don't apply. It's the limit case that defines where the series stops.

---

## 1. The one principle: judgment vs. mechanism

Every functional agent (plan / explore / todo / exec / edit / eval) is a
CoAct-derived loop that does two separable things each turn:

- **Judgment / authoring** — reason about the task and *produce* an artifact: a
  plan body, an exploration write-up, a checklist, an evidence record, an edit, a
  verdict. This is what LLMs are inherently good at.
- **Mechanism** — deterministic bookkeeping around that artifact: build YAML
  frontmatter, validate it, diff, count matches, parse a dossier, roll back, run
  a linter. This is what machines are good at and models are bad at.

The shipped agents got this backwards on the authoring half. They make the model
**construct a structured object** (`pre`/`post`/`score`/`execute` maps with exact
keys and nested flow-maps) and hand it to a renderer (`*$dossier-frontmatter` →
`*$dossier-write` → `*$dossier-index-append` → `*$next-handoff`). That fights the
model's fluency: a mis-keyed map, a string-where-a-keyword-belongs, or a missing
leading `---` errors the call and burns an iteration. The tell-tale that this
path leaks is in the codebase already — every one of these agents ships an
`:agent.ask/post` **auto-persist hook** that reconstructs the artifact from the
answer text *because models keep skipping or fumbling the helper chain.*

**The rule the whole series applies:**

> Retire micro-tools that make the LLM *construct* an artifact (authoring → write
> markdown directly from a template). Keep micro-tools that do *deterministic
> mechanical work* (parsing, diffing, counting, rollback, dossier reads).

Said most compactly: **authoring becomes prose; reading stays typed.** The edit
doc sharpens it (mechanism like `edit$apply` is a *good* micro-tool worth
keeping); the eval doc proves the reading half (it consumes four typed dossiers
and parsing them by hand would dwarf the scoring in error rate).

## 2. The six agents at a glance

| Agent | Retire (authoring micro-tools) | Keep (mechanism / read seams) | Agent-specific contribution |
| --- | --- | --- | --- |
| **plan** | `plan$dossier-slug/frontmatter/write/index-append/next-handoff` | `plan$read-dossier`, `doc$list` | Established the **template** pattern: write the dossier as markdown; downstream schema unchanged. |
| **explore** | `explore$slug/frontmatter/write/index-append` | `explore$read-frontmatter`, `explore$find` | **Reuse-via-references**: an iteration-0 prior-art gate (`explore$find`), `related:` lineage, and a `freshness:` field so agents don't re-explore. |
| **todo** | `todo$dossier-*` **and** the item-mutation verbs (`:item-idx`/`:item-done`/`:add-item`) | `todo$read` (progress + st-memory mirror), `todo$read-dossier`, `doc$list` | **Index-free checklist editing** (flip `- [ ]`→`- [x]` by text, not ordinal); the **two kinds of todo** (working vs. contract); the **todo substrate**. |
| **exec** | `exec$dossier-*` **and** the `:item-idx` flip | `exec$read-dossier`, `exec$find`, upstream `todo$/plan$read-dossier`, `todo$read` | **Exec substrate** (route → verify → record → flip; *evidence-before-flip*; *safe-write-via-edit-agent*); working vs. contract execution. |
| **edit** (was update) | `edit$slug/frontmatter/write/index-append` | **`edit$apply`** (the transaction — kept!), `edit$read-record`, `edit$find` | **Why it exists vs. raw `update-file`** (transaction = probe/verify/rollback/audit); the **edit substrate**; **reverse-diff rollback**; the rename. |
| **eval** | `eval$dossier-*`, `eval$verdict-write`, `eval$next-handoff` | `eval$read-verdict`, `eval$find`, four upstream readers (`exec$/plan$/todo$read-dossier`, `edit$read-record`) | Scoring is **inherent judgment** (SCORE untouched); **unify verdict+dossier** into one frontmatter+body file; the proof case for "keep reads typed". |
| **research** (orchestrator) | `research$bootstrap` (acceptance vector-of-maps), `research$update-status`, `research$write-verdict` frontmatter, opt. `append-log`/`index-append` | `research$id` (resume key), `research$resume?`/parser, `research$verdict-outcome` (derive + `:achieved` guard), **five sibling readers** (`plan$/todo$/exec$/eval$read-dossier`, `edit$read-record`) | Orchestration is **inherent judgment** (the move state machine — untouched); **acceptance criteria are a checklist → reuse the todo substrate** (index-free status flip by stable id); the heaviest *reader* in the stack, so the strongest "keep reads typed" case. |
| **workflow** (template orchestrator) | `workflow$bootstrap` (acceptance+stages vectors-of-maps), `workflow$update-stage`, `workflow$update-acceptance`, `workflow$write-verdict` frontmatter | `workflow$id`, `workflow$resume?`/parser, `workflow$list-templates`/`load-template`+validate, `workflow$install-starters`, `workflow$verdict-outcome` (derive + `:achieved` guard) | Orchestration is **inherent judgment** (the stage state machine — untouched); **acceptance AND stages are checklists → reuse the todo substrate**. Plus: **templates become markdown with a managed CRUD lifecycle** (create=write-file, read=list/load, update=update-file, delete=rm) owned by an authoring mode — the lifecycle templates lacked, matching tool/agent/skill. One checklist format serves template, dossier, and todo. |
| **skill** (lifecycle specialist) | `skills$write`'s `:scripts`/`:resources` `{filename content}` maps → direct `write-file`s | `skills$find/read/list` (discovery), `skills$install/sync` (CLI), `skills$reload`, `skills$import`, `skill-proposal$*` | Already mostly lightweight (SKILL.md content was always markdown). The real win: a **skill substrate in the base agents** — find → read → follow a SKILL.md — so *any* agent can **use** skills inline; skill-agent keeps the **lifecycle**. Needs a read-subset roster add (skills aren't in `default-agent-roster` today). Pairs with Project Memory as a "consult before acting" base protocol. |
| **mcp** (lifecycle specialist) | **nothing** — 3 polymorphic commands (`mcp$server`/`tools`/`lifecycle`) are all RPC/discovery/invocation; authors nothing | all three commands (discover / invoke / manage a connection) | Already all-mechanism (keep). The win: an **MCP substrate in the base agents** — discover → inspect schema → invoke — so any agent can **use** MCP. Side-effect safety is **not** a model-side read/write classifier: MCP tools are registered (`mcp$<server>$<tool>`) and every call flows through `call-tool`'s **existing permission mechanism, fail-closed** (default `:approval-required`) — uniform across the proxy and the direct binding. Needs an MCP-command roster add + the fail-closed `:tool-use-control` stamp at registration. mcp-agent keeps lifecycle/troubleshooting. |
| **tool + meta** (user-artifact lifecycle pair) | **almost nothing** — content (a `(fn …)` body / an agent's instruction + tool-context) is already code/markdown the LLM writes | `*$validate` (dry-run: eval-smoke in a fork / structural + sample), `*$create` (persist + **register**), `*$list`/`*$read` (dup-check) | **The lightest cases + the "keep the mechanism" exemplar.** Validate and register are deterministic acts the model can't hand-roll → make validate-before-create a hard rule. **No substrate, correctly** — a created tool/agent is *registered*, so "use" is ambient (a registry call), unlike a skill ("use" = read+follow a procedure). Completes the substrate theory. |
| **main** (front-door router) | `main$append-log` (per-turn routing-line map) → **moved to a hook** | `main$session-id` (accessor), `main$resume?`/`main$last-shape`, hook-driven `main$append-pointer`/`index-append`, five sibling readers | Routing is **inherent judgment** (the decision table — untouched); **the routing log is observation, not authoring → derive it from the turn in a hook** (shape-from-dispatched-agent). The **chief beneficiary of the substrates**: the front door does casual track/execute/edit inline instead of always dispatching. |

## 3. Invariants — what does NOT change

The redesigns are deliberately contained. Across all six:

- **The dossier/record frontmatter schemas are unchanged** — only *how they're
  written* changes (a `write-file` from a template instead of a helper). So
  **downstream consumers don't change**: todo reads plan's dossier, exec reads
  todo's, eval reads all three + edit records, exactly as before.
- **The `Saved <X>:` handoff lines stay** (`Saved plan:`, `Saved exploration:`,
  `Saved todo:`, `Saved dossier:`, `Saved edit:`, `Saved verdict:`) — the
  dispatcher's grep contract is untouched.
- **The three-phase contract stays** (PRE-FLIGHT → AUTHOR/SCORE/EXECUTE →
  POST-FLIGHT). It was always a *prompt* contract, not a tool pipeline.
- **The auto-persist hook stays, simplified** — it now writes the *same*
  templated markdown the happy path writes, so the safety net and the primary
  path can no longer diverge.
- **CoAct loop, BT, sandbox, DSPy signature — untouched.**
- **`update-file` / `write-file` (base file primitives) keep their names** — only
  the *edit specialist* and its `update$*`→`edit$*` helpers rename.

## 4. The substrate strategy

Three of the agents do work that recurs *informally* — a root agent often needs
to track a checklist, do the items, or make a safe edit without the ceremony of
a gated subagent. For those, the redesign moves the **common-case capability
into the base agents** so any derived agent inherits it.

**Two kinds, one format.** Each of these splits into (A) a *working* form — the
root agent does it inline via base-level tools + a protocol — and (B) a
*contract* form — the subagent adds gating, a rubric, and an audited dossier for
the pipeline. Both use the same on-disk format, so they interoperate.

| Substrate | What it gives any agent | Subagent (contract form) keeps |
| --- | --- | --- |
| **todo** (track) | A markdown checklist + index-free edits + `todo$read` reconcile | plan-coverage gating, acceptance rubric, exec handoff dossier |
| **exec** (execute) | route → verify → record → flip; evidence-before-flip; safe-write | C1–C8 gating, R1–R7 evidence rubric, eval dossier |
| **edit** (safe-write) | `edit$apply` (probe/verify/rollback) + "no raw write on tracked source" | mode judgment, refusal/escalation, audited edit record |
| **skill** (use) | find → read → follow a SKILL.md procedure (`skills$find/read/list`) | create/update/remove/install/sync/import; the proposal loop |
| **mcp** (use) | discover → inspect schema → invoke MCP tools (`mcp$server/tools/lifecycle` + `mcp$<server>$<tool>`); side effects gated by the existing permission layer | `:stop`/`:restart`, troubleshooting, multi-server flows |

The first three (todo/exec/edit) are facets of "work on the repo safely" and
share `default-agent-roster`'s already-inherited tools — so they need only
guidance. The **skill substrate is a different facet — "use a reusable
procedure"** — a cousin of the Project Memory protocol (both are "consult the
store before reinventing": skills = procedures, memory = facts). It's also the
one substrate that needs a **tool add**: the skills read-subset isn't in
`default-agent-roster` today (explore-agent binds it explicitly), so the skill
substrate ships guidance *and* the read-subset roster add.

**When does a thing get a substrate?** The tool/meta-agent doc completes the
theory. A substrate is worth installing when *using* a thing is a **multi-step
LLM-inherent procedure** that should become an ambient habit (track a checklist,
execute with evidence, edit safely, follow a skill). It is *not* worth installing
when "use" is a single **registry call** the runtime performs: a user-defined
tool (`user$tool$<name>`) or agent (`user$agent$<name>`) is *registered* on
create, so any agent can already call/dispatch it and `list-tools` already
discovers it — the registry **is** the substrate. So:

> Install a substrate when "use" is a procedure the model performs; rely on the
> registry when "use" is a call the runtime performs.

The **mcp** substrate refines this by separating two things that are easy to
conflate: *should use be ambient* (the substrate question) vs. *how are side
effects gated* (a permission question). MCP qualifies for a substrate because
using one is a connect → inspect-schema → invoke **procedure**, like skills. But
its side effects (a call can post to Slack or create a Linear issue) are **not**
handled by making the model classify read vs. write — MCP tools are *registered*
(`mcp$<server>$<tool>`) and every call (proxy or direct binding) flows through
`call-tool`'s **existing, fail-closed permission mechanism** (the same one that
gates `write-file`/`bash`). A bespoke read/write analyzer would be both impossible
to get right (the server defines semantics) and leaky (a proxy-scoped hook misses
the direct binding); the registry-wide permission seam is correct, uniform, and
already there. So the sharper rule: **install a substrate when "use" is a procedure
that should be ambient; gate side effects with the one existing permission layer,
not a per-substrate classifier.** Skills (procedure) and MCP (procedure) qualify;
user-tools/agents (a bare registry call, no procedure) don't — and side-effect
safety is orthogonal to all of it. The same seam covers **bash**: un-classifiable,
side-effecting, gated identically.

In every case the **lifecycle** (create/validate/register/delete) stays the
specialist's — authoring a persistent, sandboxed, registered artifact is a
deliberate validated act, never scattered into every agent.

**The precedent + the mechanism.** This isn't new machinery — it copies the
existing **Project Memory protocol**: `coact-project-memory-protocol` is a base
system-context section teaching every derived agent to manage `.brainyard/memory/`
files with plain read/write/update-file. A substrate is the same: one shared
prose string, inserted as a `:section` into both `coact-system-context` and
`react-system-context`. The *tools* are already inherited — `default-agent-roster`
(common tools + commands) rides onto every derived agent via
`run-coact-derived`'s `merge-derived-tools`, so `update-file`, `edit$apply`,
`todo$read`, `bash` are already bound everywhere. **Only the guidance is missing.**

**eval has no substrate** — and that closes the specialist set symmetrically.
Casual self-assessment ("did I meet the goal?") already lives in the base loop's
`goal-achieved` output every turn; formal criterion-by-criterion scoring against
a plan's `## Acceptance` doesn't recur informally. So eval-agent is *always* the
contract path.

**research (the orchestrator) consumes the substrates rather than adding one.**
Its value is the *durable, resumable, acceptance-tracked thread* — which
justifies a dedicated agent, not a base substrate. But its acceptance criteria
are a checklist, so it **reuses the todo substrate** (§5 of the research doc) for
index-free status tracking, and it leans entirely on the sibling read seams to
drive its moves. So research validates two series claims at once: substrates are
*reused* across agents (not reinvented), and "keep reads typed" matters most for
the agent whose whole job is reading.

**workflow (the template-driven orchestrator) consumes substrates and adds a CRUD
lifecycle.** Like research, it reuses the todo substrate — for *both* its
workflow-level acceptance and its stage roster (two status-lists, one substrate).
Its distinct contribution answers a gap: workflow **templates** had no managed
lifecycle (they were hand-authored EDN, and editing mid-run was forbidden). The
redesign makes templates **markdown** — frontmatter + an acceptance checklist + a
stages checklist, i.e. the *same* shape as a running dossier — so CRUD is plain
file ops, owned by workflow-agent in an explicit authoring mode. This both removes
the EDN-construction brittleness and gives templates the lifecycle that tools
(tool-agent), agents (meta-agent), and skills (skill-agent) already have. The
payoff is one checklist format across template, dossier, and todo.

**main (the front-door router) is the chief *beneficiary* of the substrates.**
There is no substrate below the root agent — the "two kinds" framing terminates
at main-agent, which *is* the casual path's home and delegates to the contract
path when work earns it. The substrate work across the series is in large part
*for* main-agent: once todo/exec/edit substrates land, the front door handles a
trivially-scoped request (track three follow-ups, rename A→B in F, do-this-then-
that) **inline** instead of dispatching a subagent for every small thing — while
still routing genuinely contract-shaped work (gated plan→todo→exec→eval, audited
edits, durable research) to the specialists. Main-agent's own redesign is the
lightest of all: it's already hook-driven, so the only change is moving its last
per-turn authoring obligation (the routing-log line) onto a hook too.

## 5. Cross-cutting decisions to settle once

These recur as open questions across the docs; decide them centrally.

1. **Enforcement: prompt vs. hook.** The substrates are prose discipline today
   (reconcile after a flip; prefer `edit$apply` for tracked source). A
   `:agent.tool-use/pre|post` hook could *enforce* them (auto-`todo$read` after a
   `todos/*.md` edit; refuse raw `write-file` on tracked source). Stronger
   guarantee, bigger blast radius. **Recommendation:** ship prompt-only first,
   add hooks if telemetry shows leakage.
2. **Substrate availability: default-on vs. opt-in.** Default reduces subagent
   dispatch but widens every agent's surface/prompt. **Recommendation:** opt-in
   behind one flag for the first release; measure dispatch reduction; flip to
   default if clean.
3. **One combined base section vs. three.** todo + exec + edit substrates are
   facets of "work on the repo safely." **Recommendation:** ship as a single
   **"Working on the repo"** base section (track · execute · edit) — one
   cache-stable block, reads as one protocol.
4. **INDEX strategy.** append-only (cheapest) vs. read-modify-write prepend vs.
   derive-on-demand. **Recommendation:** append-only; `*$find` already tolerates
   unsorted INDEX via its per-file fallback.
5. **Dossier read: strict vs. lenient.** Validate-and-reject vs. warn-and-continue
   on a malformed hand-authored frontmatter. **Recommendation:** lenient parse +
   a read-time validator that flags a *missing required key* (so consumers fail
   loud, not silent), not a hard reject.
6. **The edit-agent rename sequencing.** The `:via :update-agent` → `:via
   :edit-agent` tag is *data* in todo/exec/eval dossiers. **Recommendation:**
   land the agent/helper/directory rename first (aliased), and the `:via`
   normalizer **with or after** the todo+exec substrate work (which already
   revisits `:via` routing) so the normalizer lands once.

## 6. Rollout sequence

Ordered to minimize churn and risk; each phase is independently shippable.

**Phase 0 — settle §5 decisions.** Especially the combined-substrate question
and the enforcement model, since they shape the base-agent edits.

**Phase 1 — base substrates + main-agent's hook (additive, low-risk).** Add the
"Working on the repo" section to `coact-system-context` + `react-system-context`
(shared string). Nothing is removed; the tools are already inherited. This alone
unlocks root-agent-inline track/execute/edit and starts cutting subagent
dispatches. Depends only on the read seams (`todo$read`, `edit$apply`) already
existing — they do. **Also add the skill substrate** (a base section: find → read
→ follow a SKILL.md) — additive too, but it additionally needs the skills
read-subset (`skills$find/read/list/reload`) added to `default-agent-roster`,
since skills aren't inherited today. **And the MCP substrate** (discover → inspect
→ invoke MCP tools) — additive, also needs the MCP command family added to the
base roster; its side-effect safety is the **existing tool-permission mechanism,
fail-closed** (stamp `:approval-required` on `mcp$<server>$<tool>` at
registration), *not* a bespoke read/write classifier. **Pair all of this with main-agent's routing-log
hook** (move `main$append-log` to a `:agent.ask/finalize` hook): every piece here
touches the base/root agent, and main-agent is the chief substrate beneficiary, so
they land together.

**Phase 2 — per-agent lightweight rewrites.** Retire the authoring helpers,
author markdown from templates, simplify each auto-persist hook. Order by
dependency and simplicity:
1. **plan** — the template pattern, no substrate, smallest blast radius.
2. **explore** — adds the reuse gate (`explore$find` already exists).
3. **todo** — index-free checklist edits + retire item-mutation verbs (pairs
   with the Phase-1 todo substrate).
4. **exec** — index-free flip *depends on todo's* substrate landing first.
5. **eval** — retire persist helpers + unify verdict/dossier (SCORE untouched).
6. **research** — retire the structured authoring helpers; acceptance reuses the
   todo substrate, so sequence it *after* todo (alongside/after exec). Keep
   `research$verdict-outcome` (the `:achieved` guard) and the sibling readers.
7. **workflow** — same as research (acceptance + stages reuse the todo substrate),
   *plus* the markdown-template CRUD lifecycle + dual-read migration off EDN.
   Sequence after todo; can land alongside research.
8. **skill** — small CRUD cleanup (maps → direct `write-file`s); the substantive
   change is the **skill substrate** (Phase 1: a base section + the read-subset
   roster add). Independent of the rewrites; can land with Phase 1.
9. **mcp** — no CRUD change (already all-mechanism); the substantive change is the
   **MCP substrate** (Phase 1: a base section + the MCP-command roster add + a
   fail-closed `:tool-use-control` stamp on registered MCP tools — safety via the
   existing permission layer, no model classification). Independent; lands with
   Phase 1.
10. **tool + meta** — almost no change (the lightest cases): make
    validate-before-create a hard rule, optional file-first authoring. Independent;
    the optional validate-enforcing hook fits Phase 4. **No substrate** (use is
    ambient via the registry).

**Out of scope:** [acp-agent](./acp-agent-redesign-boundary.md) — a pure transport
adapter (not CoAct-derived, authors nothing), so the series doesn't apply; it's
the boundary case.

**Phase 3 — the edit-agent rename.** Register `edit-agent`/`edit$*` with
`update-agent`/`update$*` aliases; dual-read the directory; land the `:via`
normalizer (after Phase 2's todo/exec). Drop aliases one release later behind a
grep gate.

**Phase 4 — optional hardening.** `update-file :expect-count`; reverse-diff
rollback in `edit$apply`; promote substrate discipline to hooks if §5.1 says so;
flip substrates to default-on if §5.2 telemetry is clean.

Each phase keeps the system shippable: schemas and handoff lines are invariant
(§3), so a half-migrated pipeline still interoperates.

## 7. Net effect

When the series lands:

- The brittle construct-a-map-then-render-YAML authoring path is gone; agents
  write the markdown they're fluent at, and a keyword typo can't fail a write.
- The deterministic half (dossier reads, diffing, rollback, the `edit$apply`
  transaction) stays as tools — where reliability actually comes from.
- Root agents can track, execute, and safely edit **inline**, via inherited base
  substrates, so the subagents are dispatched only for their **gated, audited
  contract role** — fewer hops, less context bloat.
- The pipeline's contracts (dossier schemas, `Saved X:` lines, the three phases)
  are unchanged, so the migration is incremental and reversible.

One sentence: **stop making the model do the machine's job and the machine do
the model's job** — author in prose, read with tools, and let any agent do the
common-case work without a subagent.
