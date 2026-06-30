# Workflow-Agent — Lightweight Redesign + Template CRUD (revision 3)

> **Status:** Proposal. Applies the [series](./agent-lightweight-redesign-synthesis.md)
> argument to the **second orchestrator**. Workflow-agent is research-agent's
> sibling: a durable-dossier loop, but driven by **domain templates**
> (`.brainyard/workflows/<domain>.edn`) declaring a typical stage sequence. The
> same split applies — the state machine is inherent judgment; the brittleness is
> the structured-authoring helpers — and there's a workflow-specific addition the
> user asked for: **a managed CRUD lifecycle for workflow templates**, which
> today don't have one.
>
> Two pillars:
> 1. **Lightweight dossier authoring** (mirrors research): retire the
>    construct-a-map helpers; author the dossier files as markdown; **both
>    acceptance criteria and the stage roster are checklists → reuse the todo
>    substrate** (index-free status flip by stable id).
> 2. **Template CRUD, LLM-inherent.** Templates become **markdown** (frontmatter +
>    an acceptance checklist + a stages checklist) instead of hand-authored EDN,
>    so create/read/update/delete are plain file ops — owned by workflow-agent in
>    an explicit **authoring mode**, with the "no self-modify mid-run" separation
>    preserved.
>
> **Scope:** `components/agent/src/ai/brainyard/agent/common/workflow_agent.clj`,
> `workflow.clj`, and the template format under `.brainyard/workflows/`.

---

## 1. Why the current paths are error-prone

Like research-agent, workflow-agent is **already partly lightweight** (file tools
bound, no `remove` clause beyond `fetch-url`) and its orchestration loop is sound.
The brittleness is in two places, plus a gap the user flagged.

**The dossier-authoring helpers** make the model construct structured objects:

- **`workflow$bootstrap`** takes `:acceptance [{:id … :text … :status …}]` **and**
  `:stages [{:purpose … :recommended-agent … :gate-after … :acceptance-focus …}]`
  — *two* vectors-of-maps the model refines from the template before any stage
  runs, plus `:template-edn` (the whole template map).
- **`workflow$update-stage`** flips a stage's status in `stages.edn` by
  `:stage-id` and increments `:attempts`; **`workflow$update-acceptance`** flips a
  criterion's `status:` line by `:criterion-id`. These are the *repeated*
  mutations during a run — the same id-addressed status-flip shape as todo's
  checkbox flip and research's `update-status`.
- **`workflow$write-verdict`** emits the verdict frontmatter and derives
  `acceptance_outcome` / `stage_outcomes`; **`workflow$append-log`** builds an
  NDJSON line with a pointers map.

And the same per-turn-obligation pressure: the FINALIZE section is an entire
two-iteration protocol with a four-box pre-flight gate, because the model keeps
collapsing it and emitting an `:answer` that disagrees with the dossier.

**The template gap (the user's ask).** Templates are hand-authored **EDN**
(`.brainyard/workflows/<domain>.edn`) with `:workflow/id`, an `:acceptance`
vector-of-maps, a `:stages` vector-of-maps, `:defaults`. Two problems:

- Authoring/editing one means emitting *precise EDN* — the exact structured-
  construction brittleness the series retires, now applied to a config file.
- There is **no agent-managed lifecycle**. Hard Rule 3 forbids editing templates
  mid-run ("domain knowledge under version control; improvements come from the
  user"), and nothing else owns CREATE/UPDATE/DELETE. So a user who wants "make a
  workflow template for our release process" or "add a canary stage to
  feature-launch" has to hand-edit EDN. Tools, agents, and skills all have
  lifecycle owners (tool-agent, meta-agent, skill-agent); templates don't.

## 2. Thesis

Workflow-agent already does the hard part right: **orchestration is judgment** —
which stage runs, which agent, when to gate, when to re-run/insert/skip. Keep
that untouched. Then:

1. **Author the dossier files as markdown from templates** — retire the
   acceptance/stages vector-of-maps construction.
2. **Acceptance criteria AND the stage roster are checklists.** Both are
   id-addressed status lists — track them as markdown checklists, flip status with
   `update-file` by stable id, parse back with one read seam. *Reuse the todo
   substrate* for both.
3. **Templates become markdown, with a managed CRUD lifecycle.** A template is an
   id/name/description + an acceptance checklist + a stages checklist — i.e. the
   *same shape* as a bootstrapped dossier minus the run state. Make it a markdown
   file the model writes/edits directly; CRUD = file ops; owned by workflow-agent
   in an authoring mode.
4. **Keep the mechanism**: the resume key (`workflow$id`), the resume/parse seam,
   template discovery + load+validate (`workflow$list-templates` /
   `workflow$load-template`), the starter installer, and the verdict
   outcome-derivation + `:achieved` guard.

One line, the series refrain for an orchestrator: **drive in judgment, author in
prose, keep reads typed** — and **a template is just a checklist doc, so editing
one is editing a file.**

## 3. Design principles

1. **Orchestration is judgment — untouched.** The 9-move state machine
   (RUN-STAGE / EVAL / GATE / RE-RUN / INSERT / SKIP / SYNTHESIZE / CLARIFY /
   FINALIZE), HITL modes, and direct kebab-case dispatch stay.
2. **Acceptance and stages are checklists (substrate reuse).** Both ride the todo
   substrate's checklist + index-free flip + parse-back read seam — one mechanism
   for all three status-list concerns (todo items, research acceptance, workflow
   acceptance + stages).
3. **Templates are markdown, not EDN.** The same checklist shape the run uses;
   authored/edited with file tools; parsed + validated on load.
4. **Template CRUD has an owner.** workflow-agent in an explicit authoring mode —
   the missing lifecycle, matching tool-agent / meta-agent / skill-agent.
5. **Run/edit separation preserved.** No self-modify *during* a run (Hard Rule 3
   stays for run mode); template editing is a separate, explicit invocation.
6. **Keep deterministic discovery/validation/derivation.** list/load/validate
   templates, derive the verdict outcome, enforce the `:achieved` guard — all
   mechanism, all kept.
7. **Degrade gracefully.** Malformed checklist/template lines are caught by the
   lenient parser + load-validate; the verdict guard blocks a premature
   `:achieved`.

## 4. What stays, what goes

| Concern | Today | Redesign |
| --- | --- | --- |
| Pick the move (run the workflow) | reasoning over dossier + template | **Unchanged** — inherent judgment. |
| Resume key | `workflow$id` | **Keep** — deterministic. |
| Resume probe | `workflow$resume?` | **Keep** — read seam (parses the §5 checklists). |
| Discover templates | `workflow$list-templates` | **Keep** — enumerate project/user/built-in. |
| Load + validate a template | `workflow$load-template` | **Keep** — read + validate (now parses markdown templates, §6). |
| Seed starter templates | `workflow$install-starters` | **Keep** — copy starters (now markdown). |
| Bootstrap from `:acceptance [{…}]` + `:stages [{…}]` | `workflow$bootstrap` (two vectors-of-maps) | **Removed.** `write-file` purpose.md + the acceptance & stages **checklists** from templates (`bash mkdir`). |
| Flip a stage status | `workflow$update-stage :stage-id … :status …` | `update-file` the stages-checklist line (index-free, by stage id); attempts derivable from the log. |
| Flip a criterion status | `workflow$update-acceptance :criterion-id …` | `update-file` the acceptance-checklist line (index-free, by id). |
| Per-move findings | `workflow$append-log :pointers {…}` (NDJSON) | `write-file :append` a findings line; keep thin only if structured resume needs it. |
| Write the verdict | `workflow$write-verdict` (frontmatter + derive) | `write-file` verdict.md from a template; **keep** a read-side `workflow$verdict-outcome` (derive `acceptance_outcome`/`stage_outcomes` + `:achieved` guard). |
| Template CREATE / UPDATE / DELETE | **none** (hand-edit EDN) | `write-file` / `update-file` / `rm` on a markdown template — workflow-agent **authoring mode** (§6). |
| INDEX prepend | `workflow$index-append` | `write-file :append` (or keep thin). |

Net: the structured-construction helpers retire (`bootstrap`'s vectors,
`update-stage`/`update-acceptance` status-flips, `write-verdict`'s frontmatter,
opt. `append-log`/`index-append`); the discovery/validate/derive seams stay
(`id`, `resume?`, `list-templates`, `load-template`, `install-starters`,
`verdict-outcome`); template CRUD becomes file ops. No roster change — file tools
already bound.

## 5. Dossier: acceptance and stages as checklists (substrate reuse)

Today acceptance lives as a frontmatter vector-of-maps and stages as `stages.edn`.
Both are id-addressed status lists — i.e. the todo substrate. Recast both as
markdown checklists with status tokens (five states, so a token not a bare box):

**acceptance.md** (identical to research's acceptance, §5 of that doc):

```markdown
# Acceptance — <workflow id>
- [ ] a1 (open) — <workflow-level criterion>
- [x] a2 (satisfied) — <criterion>; evidence: <stage artifact path>
- [~] a3 (partial) — <criterion>; gap: <one line>
```

**stages.md** (the stage roster — a checklist with inline metadata tags, exactly
how todo items carry `{via:…, covers:…}`):

```markdown
# Stages — <workflow id>
- [x] s1 design (satisfied) — draft the schema {agent: plan-agent, gate: none, focus: [a1]}
- [>] s2 implement (in-progress) — apply the migration {agent: exec-agent, gate: user, focus: [a2, a3]}
- [ ] s3 verify (pending) — score acceptance {agent: eval-agent, gate: none, focus: [a2, a3]}
```

- **Author** (bootstrap): `write-file` both checklists from the loaded template —
  no vector-of-maps.
- **Flip status**: `update-file` the line by **stable id** (`a2`, `s2`) — same
  index-free safety as todo/research.
- **Derived fields**: `attempts` = count of RE-RUN entries for that stage id in
  findings.log; `completed-at` = the flip timestamp. No separate counter to
  maintain in a structured store.
- **Parse back**: one read seam (`workflow$resume?` / a shared checklist reader)
  yields `{:acceptance {a1 :open …} :stages {s1 :satisfied …} :pending [s3]}` for
  resume *and* for the verdict-outcome derivation.

This unifies **three** status-list concerns onto one substrate: todo items,
workflow acceptance, and workflow stages. The `(status)`-token extension (from
research §14 Q2) covers all of them with one parser.

## 6. Template CRUD — markdown templates with a managed lifecycle (the user's ask)

### 6.1 Templates become markdown

A workflow template is an id/name/description + defaults + acceptance criteria +
an ordered stage list. That is the *same shape* as a bootstrapped dossier minus
the run state — so make it the same markdown:

```markdown
---
workflow_id: feature-launch
name: Feature Launch
description: Ship a user-facing feature end-to-end with gates.
defaults: {hitl: gates, max_stage_attempts: 2}
---

# Acceptance
- [ ] a1 — feature flag toggleable in staging admin
- [ ] a2 — all affected component tests green
- [ ] a3 — rollback rehearsed

# Stages
- [ ] s1 research-feasibility — assess approach {agent: research-agent, gate: none, focus: [a1]}
- [ ] s2 plan-design — author the plan {agent: plan-agent, gate: user, focus: [a1, a2]}
- [ ] s3 implement — drive the todo {agent: exec-agent, gate: user, focus: [a2]}
- [ ] s4 verify — score acceptance {agent: eval-agent, gate: none, focus: [a2, a3]}
```

The acceptance/stages checklists in a *template* are the same format as in a
*run* dossier — the only difference is the template's are all `(open)`/unchecked
(no run state). So **one checklist format serves template, dossier, and the todo
substrate.** `workflow$load-template` parses this markdown (read + validate) into
the run's starting acceptance + stages.

### 6.2 The CRUD operations — plain file ops

| Op | How | Owner |
| --- | --- | --- |
| **Create** | `write-file .brainyard/workflows/<domain>.md` from the template-template | workflow-agent (authoring mode) |
| **Read** | `workflow$list-templates` (discover project/user/built-in) + `workflow$load-template` (parse + validate) | read seams (kept) |
| **Update** | `update-file` — add a criterion, insert/reorder a stage line, retag an agent (index-free, by id) | workflow-agent (authoring mode) |
| **Delete** | `rm .brainyard/workflows/<domain>.md` (with confirmation) | workflow-agent (authoring mode) |

Because templates are markdown checklists, CRUD is the file tools the agent
already binds — no EDN construction, no template-specific write helpers. Editing
a stage is editing a line; adding a criterion is appending a `- [ ]` line.

### 6.3 Who owns it — workflow-agent in an authoring mode

Workflow templates are domain-knowledge artifacts, exactly like user tools
(tool-agent), user agents (meta-agent), and skills (skill-agent) — each of which
has a lifecycle owner. Give templates the same: **workflow-agent owns template
CRUD in an explicit authoring mode**, distinct from run mode.

- **Run mode** (today's behavior): bootstrap a dossier, run stages, finalize. Hard
  Rule 3 stays — *no template self-modification during a run.*
- **Authoring mode** (new): triggered by template-CRUD-shaped requests ("create a
  workflow template for our release process", "add a canary stage to
  feature-launch", "delete the data-migration template", "show me the
  feature-launch template"). workflow-agent reads/writes/edits the markdown
  template via file tools, validating with `workflow$load-template` after a
  write. No dossier, no stage run.

The two modes are disambiguated up front: a request to *run* a workflow vs. to
*edit a template*. main-agent's decision table routes both to workflow-agent (it
already routes K=WORKFLOW); the agent picks mode from the request shape ("run the
X workflow" → run; "create/edit/delete a workflow template" → authoring). This
mirrors how tool-agent/meta-agent/skill-agent each handle both "use" and "manage"
within one specialist.

### 6.4 Starters + migration

Built-in starters become markdown (`classpath:workflows/<domain>.md`);
`workflow$install-starters` copies them as today. `workflow$load-template` gains a
**dual-read**: parse the new markdown templates *and* legacy `.edn` for one
release, so existing user/project EDN templates keep working. A
`bb migrate:workflow-templates` converts EDN → markdown (mechanical: the EDN keys
map 1:1 to the frontmatter + the two checklists). After the deprecation window,
EDN read is dropped.

### 6.5 One format, three uses

The payoff: the **same checklist markdown** is (a) the template a user authors/
edits, (b) the acceptance + stages of a running dossier, and (c) an instance of
the base todo substrate. Authoring a template, running a workflow, and tracking a
todo all speak one format and one index-free-flip discipline — fewer mechanisms,
one parser, and the template a user reads is byte-for-byte the shape the run
consumes.

## 7. The read/derive/validate seams worth keeping

- **`workflow$id`** — deterministic resume key. Keep.
- **`workflow$resume?` / checklist parser** — reads dossier frontmatter +
  acceptance/stages checklists → resume state. Keep (now parses §5 markdown).
- **`workflow$list-templates`** — discovery across project/user/built-in. Keep
  (the deterministic enumeration; the "is there a template for X" answer).
- **`workflow$load-template`** — parse + **validate** required keys/shape. Keep —
  this is the read seam that makes markdown templates safe (catches a malformed
  hand-edited template at load, not mid-run).
- **`workflow$install-starters`** — copy starters. Keep.
- **`workflow$verdict-outcome`** (new, carved from `write-verdict`) — derive
  `acceptance_outcome` + `stage_outcomes` from the checklists and **enforce the
  `:achieved` guard** (refuse `:achieved` unless every criterion is
  `:satisfied`/`:descoped`). The one load-bearing mechanical bit of `write-verdict`;
  kept as a read-side validator the model calls before `write-file`-ing verdict.md.

## 8. Orchestration is LLM-inherent (untouched)

The state machine, the template-as-recommendation philosophy (skip/insert/re-run/
reorder per real work), the HITL modes (auto/gates/checkpoint/co-pilot/step), the
gate discipline, and the hard rules are all the model reasoning about a domain
workflow. None of it changes — it's exactly the judgment the series keeps. As
with research and main, only the *persisting* was over-tooled.

## 9. Two kinds / substrate relationship

Like research-agent, workflow-agent **consumes** substrates rather than adding
one. Its value is the durable, resumable, **template-driven** multi-stage thread —
which justifies a dedicated agent. It reuses the **todo substrate** for acceptance
*and* stages (§5), and the casual "do a couple of steps" case is just main-agent
dispatching specialists. The new authoring mode (§6) is the workflow analog of the
tool/agent/skill lifecycles — a managed CRUD surface for a class of user
artifacts.

## 10. Instruction & tool-context (sketch)

Keep the state machine, HITL, and hard rules (for run mode). Changes:

```text
MODE SELECT (iter 1): is this a RUN request ("run the X workflow", "ship feature
  Y") or a TEMPLATE-AUTHORING request ("create/edit/delete/show a workflow
  template")?
  • RUN MODE — as today, but: bootstrap writes purpose.md + acceptance.md +
    stages.md (CHECKLISTS) from the loaded template via write-file; flip stage
    and acceptance status with update-file (index-free, by id); finalize via
    workflow$verdict-outcome (guard) + write-file verdict.md.
  • AUTHORING MODE — CRUD a markdown template under .brainyard/workflows/<domain>.md:
      create → write-file from the TEMPLATE-TEMPLATE
      read   → workflow$list-templates / workflow$load-template
      update → update-file (add criterion / edit-insert-reorder a stage line, by id)
      delete → rm (confirm first)
    After any write, call workflow$load-template to validate the result.
    Do NOT edit a template while RUNNING a workflow (Hard Rule 3, run mode only).
```

Tool-context: drop `workflow$bootstrap`'s structured construction,
`workflow$update-stage`, `workflow$update-acceptance`, and `workflow$write-verdict`'s
frontmatter from the helper list; keep `workflow$id`, `workflow$resume?`,
`workflow$list-templates`, `workflow$load-template`, `workflow$install-starters`,
the new `workflow$verdict-outcome`; add the acceptance/stages checklist templates,
the template-template, and the index-free flip note (pointing at the shared todo
substrate).

## 11. `workflow.clj` changes

- **Remove** the structured-construction paths: `workflow$bootstrap`'s
  acceptance/stages/template-edn object handling (replace with `bash mkdir` +
  template `write-file`s in the instruction), `workflow$update-stage`,
  `workflow$update-acceptance`, and `workflow$write-verdict`'s frontmatter
  emission.
- **Add** `workflow$verdict-outcome` (read + derive + `:achieved` guard).
- **Change** `workflow$load-template` to parse **markdown** templates (frontmatter
  + acceptance checklist + stages checklist) with a **dual-read** for legacy EDN;
  keep its validation. `workflow$list-templates` enumerates `*.md` (+ legacy
  `*.edn`) across project/user/built-in. `workflow$install-starters` ships
  markdown starters.
- **Keep** `workflow$id`, `workflow$resume?` (parse §5 checklists),
  `workflow$list-templates`, `workflow$load-template`, `workflow$install-starters`.
- **Optionally keep** thin `workflow$append-log` / `workflow$index-append`
  (mechanism; else inline `write-file :append`).
- **No roster change** for writes — file tools already bound. Acceptance + stage +
  template editing all ride the shared **todo substrate** once it lands.

## 12. Migration

- Land the slimmed `workflow.clj` + the run-mode persistence swap + the authoring
  mode.
- **Templates EDN → markdown**: `workflow$load-template` dual-reads for one
  release; `bb migrate:workflow-templates` converts EDN starters/templates to
  markdown (mechanical 1:1). Drop EDN read after the window.
- **Dossier acceptance/stages → checklists**: `workflow$resume?` dual-reads the
  legacy frontmatter-vector/`stages.edn` and the new `acceptance.md`/`stages.md`
  for one release; in-flight workflows finish on the old shape.
- Depends on the **todo substrate** landing (§5 reuses it); sequence after the
  todo redesign, alongside/after research (the other substrate consumer).
- Verdict/INDEX schema keys unchanged → consumers unaffected.

## 13. Risks & mitigations

| Risk | Mitigation |
| --- | --- |
| Hand-authored/edited template markdown malformed. | `workflow$load-template` validates on load (required frontmatter keys, ≥1 stage, every stage `focus` id exists in acceptance) and reports a precise error — caught at author/load time, not mid-run. |
| Status flip hits the wrong stage/criterion. | Match on **stable id** (`a2`, `s2`), not ordinal; `update-file` returns a diff. |
| Losing the richer per-stage EDN metadata (attempts, completed-at, gate). | `gate`/`agent`/`focus` ride inline `{…}` tags (parsed); `attempts` derived from the log; `completed-at` from the flip timestamp — no structured store needed. |
| Template authoring during a run corrupts a live workflow. | Authoring mode is a *separate invocation*; Hard Rule 3 still forbids template edits in run mode. |
| Premature `:achieved`. | `workflow$verdict-outcome` preserves the guard — same protection, now read-side. |
| EDN templates in the wild break. | Dual-read for one release + a mechanical migration task. |

## 14. Verification

- **Template CRUD** — create a `.md` template (write-file); `workflow$load-template`
  validates it; update a stage line (update-file) by id; delete (rm) — all without
  EDN construction or template-write helpers.
- **Bootstrap from markdown template** — run a workflow: bootstrap writes
  acceptance.md + stages.md checklists; `workflow$resume?` parses both.
- **Stage flip (index-free)** — `update-file` flips `s2 (pending)` → `s2
  (satisfied)`; resume reflects it; other stages untouched.
- **Acceptance flip + verdict guard** — `workflow$verdict-outcome` refuses
  `:achieved` while any criterion `:open`; accepts when all `:satisfied`/`:descoped`.
- **Dual-read** — a legacy `.edn` template still loads; a legacy `stages.edn`
  dossier still resumes, for the deprecation window.
- **Orchestration unchanged** — a multi-stage RUN-STAGE→GATE→EVAL→FINALIZE arc
  threads the dossier exactly as before.
- **Mode select** — "create a workflow template for X" enters authoring mode (no
  dossier); "run the X workflow" enters run mode.

## 15. Open questions

1. **Template format: markdown (this doc) vs. keep EDN?** Markdown unifies with the
   dossier + todo substrate and makes CRUD file-native; EDN is precise and already
   shipped. Lean markdown with dual-read — the unification (§6.5) is the win.
2. **Authoring mode in workflow-agent vs. a dedicated template lifecycle agent?**
   workflow-agent is the domain expert on template shape, and tool/agent/skill
   precedent is "the specialist owns its artifact's lifecycle." Lean: authoring
   mode inside workflow-agent.
3. **Stages as a checklist vs. keep `stages.edn`?** Checklist unifies with the
   substrate and is index-free; the inline `{agent, gate, focus}` tags + derived
   attempts cover the metadata. Lean checklist; keep `stages.edn` only if a
   consumer needs the richer structured store.
4. **Combine acceptance + stages into one dossier file** (two `#` sections) vs.
   two files? One file is fewer reads; two mirror the template's two sections.
   Minor; align with whatever the template uses (§6.1 uses two sections in one
   file).
5. **Validation depth at load.** Minimal (keys present) vs. cross-checks (every
   stage `focus` resolves to an acceptance id, agent names are registered). Lean
   cross-check — it's cheap and catches the common hand-edit error.

---

## Appendix — before/after

**Before — bootstrap + stage flip + a hand-authored EDN template:**

```clojure
;; template authoring today = hand-write EDN (no agent path):
;;   .brainyard/workflows/release.edn
;;   {:workflow/id :release :workflow/name "Release"
;;    :acceptance [{:id "a1" :text "…" :status :open} …]
;;    :stages [{:purpose "…" :recommended-agent :plan-agent :gate-after :user …} …]
;;    :defaults {:hitl :gates}}

;; run:
(workflow$bootstrap :id wid :purpose "…"
  :acceptance [{:id "a1" :text "…" :status :open}]
  :stages [{:purpose "…" :recommended-agent :exec-agent :gate-after :user}]
  :template-id :release :template-edn tmpl :hitl-mode :gates)
;; … later …
(workflow$update-stage :id wid :stage-id "s2" :status :satisfied :artifact "…")
(workflow$update-acceptance :id wid :criterion-id "a1" :status :satisfied)
(workflow$write-verdict :id wid :status :achieved :narrative "…")
```

**After — markdown template (CRUD = file ops) + index-free checklist flips:**

```clojure
;; CREATE a template — write the markdown the user reads:
(write-file {:path ".brainyard/workflows/release.md"
             :content "---\nworkflow_id: release\nname: Release\n…\n---\n# Acceptance\n- [ ] a1 — …\n# Stages\n- [ ] s1 plan — … {agent: plan-agent, gate: user, focus: [a1]}\n"})
(workflow$load-template :id :release)        ; validate the result (kept seam)

;; RUN — bootstrap writes checklists from the template; flip status index-free:
(bash {:command (str "mkdir -p .brainyard/agents/workflow-agent/" wid "/artifacts")})
(write-file {:path (str ".brainyard/agents/workflow-agent/" wid "/stages.md") :content "…"})
(update-file {:path (str ".brainyard/agents/workflow-agent/" wid "/stages.md")
              :pattern "- [ ] s2 implement (pending)" :replacement "- [x] s2 implement (satisfied)"})
(update-file {:path (str ".brainyard/agents/workflow-agent/" wid "/acceptance.md")
              :pattern "- [ ] a1 (open)" :replacement "- [x] a1 (satisfied)"})

;; FINALIZE — keep the guard, write the markdown:
(def vo (workflow$verdict-outcome :id wid))  ; derive + :achieved guard (kept)
(write-file {:path (str ".brainyard/agents/workflow-agent/" wid "/verdict.md") :content "<filled VERDICT TEMPLATE>"})
```

The model never hand-constructs EDN or vectors-of-maps — it writes the markdown
it's fluent at for both the template and the dossier, flips stages and criteria by
stable id through the shared checklist substrate, and keeps exactly the mechanism a
machine does better: **discovering + validating templates**, and **deriving +
guarding the verdict**. And workflow templates finally get the same managed CRUD
lifecycle that tools, agents, and skills already have.
