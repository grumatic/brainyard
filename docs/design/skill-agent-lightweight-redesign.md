# Skill-Agent — Lightweight Redesign + Skill Substrate (revision 3)

> **Status:** Proposal. Applies the [series](./agent-lightweight-redesign-synthesis.md)
> argument to the **skill lifecycle specialist**. Skill-agent is already the
> *least* over-tooled functional agent — a thin wrapper over `skills$*` whose
> authoring path already takes the SKILL.md body as markdown. So the lightweight
> half is small. The larger contribution is the user's ask:
>
> **A skill substrate in the base agents, so *any* agent can use skills.** Using a
> skill = discover it, read its SKILL.md procedure, and follow it. That's pure
> LLM-inherent capability, and it should be available to the whole fleet — not
> gated behind a route to skill-agent (or explore-agent). Skill-agent stays the
> **lifecycle** owner (create / update / remove / install / sync); the substrate
> covers **use**.
>
> **Scope:** `components/agent/src/ai/brainyard/agent/common/skill_agent.clj`,
> `skills.clj`, plus a shared skill-substrate section + a read-subset roster add
> in the base agents.

---

## 1. Why the current path is (only lightly) error-prone

Be honest about the magnitude, as with main-agent: skill-agent is already mostly
on the right side of the line.

- **Authoring is already markdown.** `skills$write :op :create|:update` takes
  `:content` = the **SKILL.md body** as a string. The model writes the procedure
  in markdown — exactly what the series wants. No structured object to build.
- **Discovery is already read seams.** `skills$list` / `skills$find` /
  `skills$read` parse skill directories deterministically — the keep-as-mechanism
  kind.
- **Install/sync are CLI wrappers.** `skills$install` / `skills$sync` shell out to
  `npx skills add/update` — deterministic side effects the model can't perform by
  emitting text. Keep.

The one structured-construction wrinkle: `skills$write`'s **`:scripts`** /
**`:resources`** are `{filename → content}` **maps** the model must assemble for a
skill that bundles helper scripts. A skill is, on disk, a *directory* (`SKILL.md`
+ `scripts/` + `resources/`), so the LLM-inherent way to author it is to
`write-file` each file — not to construct a map and hand it to a writer. That's
the only real lightweight win here, and it's modest.

So the redesign's lightweight half is small and surgical. The high-value half is
the substrate (§6) — which is *new capability*, not cleanup.

## 2. Thesis

Two parts:

1. **Skill CRUD goes file-inherent.** A skill is a directory; author/edit it by
   `write-file`-ing `SKILL.md` and each script/resource directly under
   `.brainyard/skills/<name>/`. Retire the `:scripts`/`:resources` map
   construction. Keep the deterministic seams: discovery (`skills$find/read/list`),
   the CLI lifecycle (`skills$install/sync`), registry refresh (`skills$reload`),
   import (`skills$import`), and the proposal loop.
2. **Skill *use* becomes a base substrate.** A base system-context protocol —
   sibling to the Project Memory protocol — teaches every coact/react-derived
   agent to *check for a relevant skill before doing a task, read its SKILL.md, and
   follow it*. Skill-agent remains the **lifecycle** owner; the substrate is
   **use-only** (read subset).

One line: **authoring a skill is writing its files; using a skill is reading and
following a markdown procedure — and every agent should be able to do the latter.**

## 3. Design principles

1. **Authoring is files.** SKILL.md + scripts + resources are written directly;
   no `{filename content}` map.
2. **Discovery / CLI / registry stay deterministic.** `skills$find/read/list`
   (read seams), `skills$install/sync` (CLI mechanism), `skills$reload`,
   `skills$import` — all kept.
3. **Use is a base substrate; lifecycle is the specialist.** Any agent can
   discover + follow a skill (substrate); only skill-agent creates/updates/
   installs (the contract path). The two-kinds split, applied to skills.
4. **Use is LLM-inherent.** Following an imperative SKILL.md procedure is exactly
   what models do well — no tool can do it better. The substrate is guidance +
   the read seam, nothing more.
5. **Consult before acting.** The skill substrate pairs with Project Memory: both
   are "check the store before reinventing" base protocols (skills = procedures,
   memory = facts).
6. **Degrade gracefully.** No skill found → proceed normally (and optionally offer
   to create one via skill-agent). A malformed SKILL.md is caught by the parser in
   `skills$read`.

## 4. What stays, what goes (the CRUD side)

| Concern | Today | Redesign |
| --- | --- | --- |
| Author SKILL.md | `skills$write :content "<md>"` | `write-file .brainyard/skills/<name>/SKILL.md` (content already markdown). |
| Bundle scripts/resources | `skills$write :scripts {…} :resources {…}` (maps) | `write-file` each file under `scripts/` / `resources/` directly. |
| Discover skills | `skills$list` / `skills$find` | **Keep** — read seams (and the substrate's discovery, §6). |
| Read a skill | `skills$read` | **Keep** — read seam (parses SKILL.md + metadata). |
| Remove a skill | `skills$write :op :remove` | `rm -r` the skill dir (confirm) — or keep a thin guarded remover (it enforces `:brainyard` scope). |
| Install / sync CLI skills | `skills$install` / `skills$sync` | **Keep** — npx CLI wrappers (deterministic side effects). |
| Import external SKILL.md | `skills$import` | **Keep** — read + validate + copy. |
| Registry refresh | `skills$reload` | **Keep** — mechanism. |
| Proposal loop | `skill-proposal$list/read/accept/reject` | **Keep** — lifecycle (auto-distilled SKILL.md proposals → live skill). |

Net: only the `:scripts`/`:resources` map construction retires (→ direct
`write-file`s); the SKILL.md content was already markdown; everything else
(discovery, CLI, registry, import, proposals) is mechanism and stays. The
agent gains `write-file`/`read-file` for direct authoring (it currently relies on
`skills$write` for all writes).

## 5. Skills as directories — file-inherent CRUD

A skill is a directory; treat it like one. To create a skill with a helper
script:

```clojure
;; check for a near-duplicate first (kept read seam)
(skills$find :query "lint markdown")
;; author the files directly — no {filename content} map
(write-file {:path ".brainyard/skills/lint-markdown/SKILL.md"
             :content "---\ntitle: Lint Markdown\ndescription: Lint and autofix markdown files\ntags: [markdown, lint]\n---\n# Lint Markdown\nRun `scripts/lint.sh <path>` to check and autofix.\n…imperative steps…\n"})
(write-file {:path ".brainyard/skills/lint-markdown/scripts/lint.sh"
             :content "#!/bin/sh\nnpx markdownlint-cli2 --fix \"$1\"\n"})
(skills$reload)   ; refresh the registry so the new skill is discoverable/invocable
```

- **Create** = `write-file` SKILL.md + each script/resource; `skills$reload`.
- **Update** = `update-file` SKILL.md (edit a step) or `write-file` a
  changed/added script; `skills$reload`.
- **Remove** = `rm -r .brainyard/skills/<name>/` (confirm) + `skills$reload`.

The SKILL.md frontmatter (title/description/tags) is what `skills$read`/`find`
index, so authoring it correctly keeps discovery working. `:brainyard`-scope only
for writes (the `:claude`/`:agents` backends stay read-only / CLI-managed) — the
substrate and the instruction both enforce this.

## 6. The skill substrate — any agent can use skills (the user's ask)

Using a skill has three steps, all LLM-inherent: **find → read → follow**. Make
that a base system-context protocol so the whole fleet gets it, modeled on
`coact-project-memory-protocol`.

### 6.1 The base section text

```text
## Using a skill (skill substrate)
Skills are reusable, named procedures (a SKILL.md of imperative steps, sometimes
with helper scripts). Before reinventing a multi-step procedure, check for one:

1. DISCOVER — (skills$find :query "<key nouns of the task>"). Also (skills$list)
   to browse. Skills span backends: :brainyard (local), :claude, :agents.
2. READ — (skills$read :skill-name "<name>") to get the SKILL.md + its path.
3. FOLLOW — do what the SKILL.md says, in your own iterations: run its
   scripts/<...> via bash, read its resources/<...>, follow its imperative steps.
   If the skill is registered as a callable tool, you may instead invoke it by
   name and let it drive — its SKILL.md rides in as context.

RULES:
- Prefer an existing skill over hand-rolling the same steps.
- USE is read-only: never create/update/install a skill inline — that's
  skill-agent's job. If no skill fits and one clearly should exist, finish the
  task, then suggest: route to skill-agent to capture it.
- Cite the skill you used (skill:<backend>:<name>) so the trail is auditable.
```

### 6.2 Two halves — guidance + a roster add

Unlike the todo/exec/edit substrates (whose tools already ride
`default-agent-roster`), the **skills read-subset is not in the base roster
today** — explore-agent binds `skills$list/find/read/reload` *explicitly*, which
is the tell. So the skill substrate needs both halves:

- **Guidance** — the §6.1 section, inserted into `coact-system-context` +
  `react-system-context` (shared string), next to Project Memory.
- **Tools** — add the **read subset** (`skills$find`, `skills$read`,
  `skills$list`, `skills$reload`) to `default-agent-roster` (or to the base
  agents' rosters) so every derived agent can discover + read. The **write subset**
  (`skills$write`, `skills$install`, `skills$sync`, `skills$import`, the proposal
  commands) stays **only** on skill-agent — use is universal, management is not.

### 6.3 Two kinds: USE (substrate) vs. MANAGE (skill-agent)

| | (A) Use a skill | (B) Manage a skill |
| --- | --- | --- |
| **What** | find → read → follow a SKILL.md procedure | create / update / remove / install / sync / import; review proposals |
| **Who** | any agent (the substrate) | skill-agent (the lifecycle owner) |
| **Tools** | `skills$find/read/list/reload` (read subset, base roster) | `skills$write/install/sync/import`, `skill-proposal$*` (skill-agent only) |
| **Ceremony** | none — read markdown, do the steps | scope checks, npx, registry, dup-check, proposal promotion |

This is the same split the series uses everywhere: the common case (use) is a
base capability; the management case (lifecycle) is the specialist.

### 6.4 Why this is high-value

Skills are **cross-cutting procedures** any agent might benefit from — "how we
write an ADR" (plan-agent), "run the component test suite" (exec-agent), "extract
tables from a PDF" (explore-agent), "release checklist" (workflow-agent). Today
only explore-agent (read-only) and skill-agent (lifecycle) touch skills; everyone
else has to route. Putting **use** in the base means the whole fleet leverages the
skill library for free — arguably the highest-leverage substrate, because skills
are the codebase's reusable know-how and this is what makes them ambient.

### 6.5 Relationship to dynamically-registered skill-tools

Skills are also auto-registered as callable tools (a skill's fn reads its SKILL.md
fresh and follows it, with the SKILL.md as context). The substrate accommodates
both modes: **invoke the registered skill-tool by name** when one exists, or
**read + follow inline** otherwise. `skills$find` surfaces both; the model picks.
The substrate makes the *discovery + follow* habit ambient regardless of which
mode a given skill uses.

## 7. The seams worth keeping

All deterministic; all stay:

- **`skills$find` / `skills$read` / `skills$list`** — discovery + read (the
  substrate's first two steps, and dup-check before authoring).
- **`skills$install` / `skills$sync`** — npx CLI lifecycle (skill-agent only).
- **`skills$import`** — read + validate + copy an external SKILL.md.
- **`skills$reload`** — registry refresh after a file-level create/update/remove.
- **`skill-proposal$list/read/accept/reject`** — the self-improvement proposal
  loop (skill-agent only).

## 8. Skill use is LLM-inherent (nothing to retire there)

Following an imperative SKILL.md procedure — read the steps, run the scripts, do
the work — is precisely what an LLM does well; no tool improves it. So the
substrate is *guidance + a read seam*, not a new mechanism. This mirrors the
series' pattern: the *doing* (use) is judgment; only the *managing* (lifecycle)
needs the specialist's deterministic scaffolding.

## 9. Instruction & tool-context (sketch)

**skill-agent** keeps its lifecycle role; only authoring changes:

```text
CREATE/UPDATE a :brainyard skill — write the directory's files directly:
  write-file .brainyard/skills/<name>/SKILL.md  (frontmatter: title/description/tags)
  write-file .brainyard/skills/<name>/scripts/<...>  (per helper script)
  write-file .brainyard/skills/<name>/resources/<...>
  then skills$reload. (Dup-check with skills$find first.)
REMOVE — rm -r the dir (confirm) + skills$reload. :brainyard scope only.
INSTALL/SYNC/IMPORT — skills$install / skills$sync / skills$import (unchanged).
PROPOSALS — skill-proposal$list/read/accept/reject (unchanged).
```

Tool-context: drop the `:scripts`/`:resources` map args from the create/update
guidance (point at direct `write-file`s); keep all read/CLI/registry/proposal
helpers.

**Base agents** gain the §6.1 skill-substrate section + the read-subset roster
add.

## 10. `skills.clj` + base-agent changes

- **`skills.clj`**: keep all `skills$*` commands; the `create-skill`/`update-skill`
  fns can stay for back-compat but the agent prefers direct `write-file`s. Ensure
  the `skills$write :remove` guard (brainyard-scope-only) remains for the
  specialist path.
- **Base roster**: add `[#'skills/skills$find #'skills/skills$read
  #'skills/skills$list #'skills/skills$reload]` to `default-agent-roster` (or to
  the base coact/react rosters) so the read subset is inherited everywhere. Keep
  the write subset off the base — it lives on skill-agent only.
- **Base system-context**: define a shared `skill-substrate-protocol` string
  (§6.1) and insert a `:skill-substrate` section into `coact-system-context` and
  `react-system-context`, adjacent to `:project-memory` (they're the two
  "consult before acting" protocols).
- **explore-agent** can drop its now-redundant explicit skills-read bindings (it
  inherits them from the base) — a small cleanup, not required.

## 11. Migration

- Add the read subset to the base roster + the substrate section — purely
  additive; no behavior removed, so it can land independently.
- skill-agent's authoring swap (maps → direct `write-file`s) is internal; skill
  on-disk layout is unchanged, so existing skills keep working and stay
  discoverable.
- No data migration. `skills$reload` after any file-level change keeps the
  registry coherent.
- Pairs naturally with **Phase 1** of the synthesis rollout (base substrates),
  since it's another base-agent section + roster add.

## 12. Risks & mitigations

| Risk | Mitigation |
| --- | --- |
| Every agent now calls `skills$find` and adds latency. | Discovery is cheap (indexed list); the substrate says "check *when a multi-step procedure is involved*," not on every turn. |
| An agent follows a stale/wrong skill. | `skills$read` returns the SKILL.md + path; the agent cites `skill:<backend>:<name>` and applies judgment. Skills are advisory procedures, not contracts. |
| A non-skill-agent tries to write a skill inline. | The base roster excludes the write subset; the substrate's USE-is-read-only rule + "route to skill-agent to capture" keep lifecycle centralized. |
| Direct `write-file` skips skill-agent's dup-check/scope guard. | skill-agent's instruction keeps the `skills$find` dup-check first; brainyard-scope is enforced by writing only under `.brainyard/skills/`. The CLI backends stay read-only. |
| Registry out of sync after a direct write. | The authoring flow ends with `skills$reload`; optionally a file-watch could auto-reload `.brainyard/skills/`. |

## 13. Verification

- **File-inherent CRUD** — create a skill via `write-file` SKILL.md + a script;
  `skills$reload`; `skills$find` discovers it; the registered skill-tool invokes.
- **Substrate discovery** — a non-skill agent (e.g. exec-agent) on a task with a
  matching skill calls `skills$find`, `skills$read`, and follows it — with no
  route to skill-agent.
- **Use is read-only** — a base agent cannot `skills$write` (not in its roster);
  it suggests routing to skill-agent instead.
- **Read subset inherited** — `skills$find/read/list/reload` resolve on any
  coact/react-derived agent after the roster add.
- **Lifecycle intact** — skill-agent still creates/updates/removes/installs/syncs
  and runs the proposal loop.
- **Cite** — an agent that used a skill emits `skill:<backend>:<name>`.

## 14. Open questions

1. **Read subset on `default-agent-roster` vs. only the base coact/react
   rosters?** The former reaches user-defined agents too; the latter is tighter.
   Lean `default-agent-roster` so meta-agent personas also get skill use.
2. **Auto-reload after a `.brainyard/skills/` write** (file-watch or post-write
   hook) vs. explicit `skills$reload`? Hook is smoother; same prompt-vs-hook
   question as the other substrates.
3. **Combine the skill substrate with Project Memory** into one "Consult before
   acting" base section (procedures + facts), or keep separate? They're close
   cousins; one section reads well. Align with the synthesis's combined-section
   decision.
4. **Should skill *use* be metered/capped** to avoid an agent over-eagerly
   `skills$find`-ing every turn? A per-turn budget or a "multi-step task only"
   gate. Lean on the prompt gate first.
5. **Do dynamically-registered skill-tools make the substrate redundant?** No —
   the substrate adds the *discovery + follow* habit and covers skills not
   registered as tools; the registered-tool path is one of its two follow modes
   (§6.5).

---

## Appendix — before/after

**Before — author a skill with a bundled script (map construction):**

```clojure
(skills$find :query "lint markdown")
(skills$write :op :create
  :skill-name "lint-markdown"
  :content "---\ntitle: Lint Markdown\n…\n---\n# Lint Markdown\n…"
  :scripts {"lint.sh" "#!/bin/sh\nnpx markdownlint-cli2 --fix \"$1\"\n"}
  :scope :project)
```

**After — write the directory's files; and any agent can *use* it:**

```clojure
;; AUTHOR (skill-agent) — files directly, no maps:
(skills$find :query "lint markdown")                    ; dup-check (kept)
(write-file {:path ".brainyard/skills/lint-markdown/SKILL.md" :content "---\ntitle: Lint Markdown\n…\n---\n# Lint Markdown\n…"})
(write-file {:path ".brainyard/skills/lint-markdown/scripts/lint.sh" :content "#!/bin/sh\nnpx markdownlint-cli2 --fix \"$1\"\n"})
(skills$reload)

;; USE (ANY agent, via the base substrate) — find → read → follow:
(skills$find :query "lint markdown")                    ; → {:matches [{:name "lint-markdown" …}]}
(def s (skills$read :skill-name "lint-markdown"))       ; SKILL.md + path
(bash {:command "sh .brainyard/skills/lint-markdown/scripts/lint.sh docs/x.md"})
;; … follow the rest of the SKILL.md steps inline …
```

Authoring a skill becomes writing its files; using a skill becomes reading and
following a markdown procedure — and because that's pure LLM-inherent capability,
the redesign puts it in the base so every agent has the skill library at hand,
while skill-agent keeps the keys to the library's *lifecycle*.
