# Skill-Agent — Lifecycle Specialist + Skill Substrate

> **Status:** Shipped. Applies the
> [series](./agent-lightweight-redesign-synthesis.md) argument to the **skill
> lifecycle specialist**, and adds a **skill substrate** to the base agents so
> *any* agent can use skills. This doc is the as-built reference — there was no
> prior skill-agent design doc; the former `skill-agent-lightweight-redesign.md`
> has been folded in here and removed.
>
> **As-built (verify against `common/skill_agent.clj`, `common/skills.clj`,
> `common/agent_roster.clj`, `common/coact_agent.clj`, `common/react_agent.clj`):**
> - **Skill-agent is a thin wrapper over `skills$*`, and authoring was already
>   markdown.** `skills$write :op :create|:update` takes `:content` = the
>   **SKILL.md body** as a string. The lightweight half here is small: the agent
>   gained `write-file`/`read-file`/`bash` (file + shell tools, bound explicitly
>   in `:agent-tools`) so it can author a skill's *directory* directly —
>   `write-file` SKILL.md + each `scripts/<…>` / `resources/<…>`, `rm -r` to
>   remove. The `skills$write` map path (`:scripts`/`:resources`) was **kept for
>   back-compat** (it is the documented "legacy" path), not retired.
> - **The substantive contribution is the SKILL SUBSTRATE in the base agents.**
>   `agent-roster/skill-substrate-protocol` (a `## Using a skill` system-context
>   section) is installed into BOTH `coact-system-context` and
>   `react-system-context` (assoc'd as `:skill-substrate`, ordered next to
>   `:project-memory`), and the read subset
>   `skills/skills-read-subset` (`skills$find` / `skills$read` / `skills$list` /
>   `skills$reload`) is merged into `default-agent-roster`. So **any**
>   coact/react-derived agent can discover → read → follow a SKILL.md. Skill-agent
>   stays the **lifecycle** owner (create/update/remove/install/sync/import +
>   proposals); the substrate covers **use** only.
> - **Use is read-only by construction.** The write subset (`skills$write`,
>   `skills$install`, `skills$sync`, `skills$import`, `skill-proposal$*`) is NOT
>   on the base roster — it lives only on skill-agent. A base agent literally
>   cannot mutate a skill inline.
> - **Skills also auto-register as callable tools.** `skills$reload` walks every
>   available skill and registers each as `:skill$<name>` in `tool/!tool-defs`;
>   the registered fn reads its SKILL.md fresh and dispatches to `skill-agent`
>   with the body as `:agent-context`. The substrate accommodates both follow
>   modes — invoke `:skill$<name>` directly, or read + follow inline.
> - **Migration is complete** — purely additive. The substrate section + roster
>   add landed with no behavior removed; the on-disk skill layout is unchanged,
>   so existing skills stay discoverable.
>
> **Scope:** `components/agent/src/ai/brainyard/agent/common/skill_agent.clj`,
> `skills.clj`, plus the shared skill-substrate section + read-subset roster add
> in `agent_roster.clj` / `coact_agent.clj` / `react_agent.clj`.
> **Built on:** `coact_agent.clj` via `coact/run-coact-derived`
> **Related reading:** `docs/design/agent-lightweight-redesign-synthesis.md`,
> `docs/design/explore-agent-design.md`,
> `components/agent/.../mcp_agent.clj`

---

## 1. Why the lifecycle path is (only lightly) over-tooled

Be honest about the magnitude: skill-agent is already mostly on the right side of
the line — the *least* over-tooled functional agent.

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
`write-file` each file. That's the only real lightweight win here, and it's
modest — so the `skills$write` map path stays for back-compat while the agent
*prefers* direct `write-file`s.

So the lightweight half is small and surgical. The high-value half is the
substrate (§6) — which is *new capability*, not cleanup.

## 2. Thesis

Two parts, both shipped:

1. **Skill CRUD goes file-inherent.** A skill is a directory; author/edit it by
   `write-file`-ing `SKILL.md` and each script/resource directly under
   `.brainyard/skills/<name>/`. The deterministic seams stay: discovery
   (`skills$find/read/list`), the CLI lifecycle (`skills$install/sync`), registry
   refresh (`skills$reload`), import (`skills$import`), and the proposal loop.
   The legacy `skills$write` map path remains for back-compat.
2. **Skill *use* is a base substrate.** A base system-context protocol — sibling
   to the Project Memory protocol — teaches every coact/react-derived agent to
   *check for a relevant skill before doing a task, read its SKILL.md, and follow
   it*. Skill-agent remains the **lifecycle** owner; the substrate is **use-only**
   (read subset).

One line: **authoring a skill is writing its files; using a skill is reading and
following a markdown procedure — and every agent can do the latter.**

## 3. Design principles

1. **Authoring is files.** SKILL.md + scripts + resources are written directly;
   the `{filename content}` map is the legacy fallback, not the preferred path.
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

| Concern | Before | Shipped |
| --- | --- | --- |
| Author SKILL.md | `skills$write :content "<md>"` | `write-file .brainyard/skills/<name>/SKILL.md` (content already markdown). |
| Bundle scripts/resources | `skills$write :scripts {…} :resources {…}` (maps) | `write-file` each file under `scripts/` / `resources/` directly. (Map path kept as legacy back-compat.) |
| Discover skills | `skills$list` / `skills$find` | **Kept** — read seams (and the substrate's discovery, §6). |
| Read a skill | `skills$read` | **Kept** — read seam (parses SKILL.md + metadata). |
| Remove a skill | `skills$write :op :remove` | `rm -r` the skill dir (confirm) + `skills$reload`; the guarded `skills$write :op :remove` (brainyard-scope-enforcing) remains. |
| Install / sync CLI skills | `skills$install` / `skills$sync` | **Kept** — npx CLI wrappers (deterministic side effects). |
| Import external SKILL.md | `skills$import` | **Kept** — read + validate + copy. |
| Registry refresh | `skills$reload` | **Kept** — mechanism. |
| Proposal loop | `skill-proposal$list/read/accept/reject` | **Kept** — lifecycle (auto-distilled SKILL.md proposals → live skill). |

Net: the `:scripts`/`:resources` map construction is **demoted to a legacy
fallback** (→ direct `write-file`s preferred); SKILL.md content was already
markdown; everything else (discovery, CLI, registry, import, proposals) is
mechanism and stays. The agent gained `write-file`/`read-file`/`bash` for direct
authoring (bound explicitly in `:agent-tools` so the file-inherent CRUD path
works on the direct `bb tui -a skill-agent` entry too, not only when dispatched).

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

## 6. The skill substrate — any agent can use skills

Using a skill has three steps, all LLM-inherent: **find → read → follow**. It is a
base system-context protocol so the whole fleet gets it, modeled on the Project
Memory protocol.

### 6.1 The base section text (as shipped in `agent-roster/skill-substrate-protocol`)

```text
## Using a skill (skill substrate)
Skills are reusable, named procedures (a SKILL.md of imperative steps, sometimes
with helper scripts). Before reinventing a multi-step procedure, check for one:

1. DISCOVER — (skills$find {:query "<key nouns of the task>"}). Also
   (skills$list) to browse. Skills span backends: :brainyard (local), :claude,
   :agents.
2. READ — (skills$read {:skill-name "<name>"}) for the SKILL.md + its path.
3. FOLLOW — do what the SKILL.md says, in your own iterations: run its
   scripts/<…> via bash, read its resources/<…>, follow its imperative steps.
   If the skill is registered as a callable tool (:skill$<name>), you may
   instead invoke it by name and let it drive — its SKILL.md rides in as context.

RULES:
- Prefer an existing skill over hand-rolling the same steps — but only when a
  genuine multi-step procedure is involved; don't skills$find on every turn.
- USE is READ-ONLY: never create/update/install a skill inline — that's
  skill-agent's job. If no skill fits and one clearly should exist, finish the
  task, then suggest routing to skill-agent to capture it.
- CITE the skill you used (skill:<backend>:<name>) so the trail is auditable.
```

### 6.2 Two halves — guidance + a roster add

Unlike the todo/exec/edit substrates (whose tools already ride
`default-agent-roster`), the **skills read-subset was not in the base roster
before** — explore-agent bound `skills$list/find/read/reload` *explicitly*, which
is the tell. So the skill substrate needed both halves, and both shipped:

- **Guidance** — the §6.1 section, inserted into `coact-system-context` +
  `react-system-context` (shared `skill-substrate-protocol` string), next to
  Project Memory (`:skill-substrate`, ordered adjacent to `:project-memory`).
- **Tools** — the **read subset** `skills/skills-read-subset` (`skills$find`,
  `skills$read`, `skills$list`, `skills$reload`) merged into
  `default-agent-roster` so every derived agent can discover + read. The **write
  subset** (`skills$write`, `skills$install`, `skills$sync`, `skills$import`, the
  proposal commands) stays **only** on skill-agent — use is universal, management
  is not.

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
tables from a PDF" (explore-agent), "release checklist" (workflow-agent). Before
this, only explore-agent (read-only) and skill-agent (lifecycle) touched skills;
everyone else had to route. Putting **use** in the base means the whole fleet
leverages the skill library for free — arguably the highest-leverage substrate,
because skills are the codebase's reusable know-how and this is what makes them
ambient.

### 6.5 Relationship to dynamically-registered skill-tools

Skills are also auto-registered as callable tools: `skills$reload` registers each
available skill as `:skill$<name>` in `tool/!tool-defs`; the registered fn reads
its SKILL.md fresh on every call and dispatches the question to `skill-agent`
with the SKILL.md as `:agent-context`. The substrate accommodates both modes:
**invoke the registered skill-tool by name** when one exists, or **read + follow
inline** otherwise. `skills$find` surfaces both; the model picks. The substrate
makes the *discovery + follow* habit ambient regardless of which mode a given
skill uses.

(Registration is an explicit *runtime* call — each entry point calls
`reload-skills!` once after config/dirs init. It is deliberately NOT a
namespace-load `defonce`, because the native `by` binary initializes
`ai.brainyard.*` namespaces at build time and would otherwise bake the build
machine's skill snapshot into the image heap. See the note in `skills.clj`.)

## 7. The seams worth keeping

All deterministic; all stay:

- **`skills$find` / `skills$read` / `skills$list`** — discovery + read (the
  substrate's first two steps, and dup-check before authoring).
- **`skills$install` / `skills$sync`** — npx CLI lifecycle (skill-agent only).
- **`skills$import`** — read + validate + copy an external SKILL.md (agentskills.io
  open standard) into `:brainyard`.
- **`skills$reload`** — registry refresh after a file-level create/update/remove.
- **`skill-proposal$list/read/accept/reject`** — the self-improvement proposal
  loop (skill-agent only).

## 8. Skill use is LLM-inherent (nothing to retire there)

Following an imperative SKILL.md procedure — read the steps, run the scripts, do
the work — is precisely what an LLM does well; no tool improves it. So the
substrate is *guidance + a read seam*, not a new mechanism. This mirrors the
series' pattern: the *doing* (use) is judgment; only the *managing* (lifecycle)
needs the specialist's deterministic scaffolding.

## 9. Instruction & tool-context (as shipped)

**skill-agent** keeps its lifecycle role; authoring is file-inherent (with the
`skills$write` map path documented as legacy):

```text
CREATE/UPDATE a :brainyard skill — write the directory's files directly:
  write-file .brainyard/skills/<name>/SKILL.md  (frontmatter: title/description/tags)
  write-file .brainyard/skills/<name>/scripts/<…>  (per helper script)
  write-file .brainyard/skills/<name>/resources/<…>
  then skills$reload. (Dup-check with skills$find first.)
  The legacy (skills$write :op :create :content … :scripts {…}) map still works.
REMOVE — rm -r the dir (confirm) + skills$reload. :brainyard scope only.
  (skills$write :op :remove still works and enforces the scope guard.)
INSTALL/SYNC/IMPORT — skills$install / skills$sync / skills$import.
PROPOSALS — skill-proposal$list/read/accept/reject.
```

The tool-context surfaces both a MANAGEMENT section (`skills$*` + proposals) and a
FILE-INHERENT CRUD section (direct `write-file`s), plus the file/shell discovery
fallbacks (`read-file`, `write-file`, `update-file`, `bash`).

**Base agents** carry the §6.1 skill-substrate section + the read-subset roster
add.

## 10. `skills.clj` + base-agent changes (as built)

- **`skills.clj`**: all `skills$*` commands kept; the `create-skill`/`update-skill`
  fns stay for back-compat but the agent prefers direct `write-file`s. The
  `skills$write :op :remove` guard (brainyard-scope-only) remains for the
  specialist path. New def `skills-read-subset`
  (`[#'skills$find #'skills$read #'skills$list #'skills$reload]`) is the USE half
  exported for the base roster.
- **`agent_roster.clj`**: `default-agent-roster` merges `skills/skills-read-subset`
  (de-duped with the common tools/commands + MCP family). The write subset stays
  off the base. `skill-substrate-protocol` (the §6.1 string) is defined here.
- **Base system-context**: both `coact-system-context` (`coact_agent.clj`) and
  `react-system-context` (`react_agent.clj`) `assoc` the
  `:skill-substrate` section from `agent-roster/skill-substrate-protocol`,
  adjacent to `:project-memory` (they're the two "consult before acting"
  protocols).
- **skill-agent's roster**: `skill-distill.proposals/skill-proposal-commands` +
  `skills/skills-commands` + `common-tools/file-tools` + `common-tools/shell-tools`,
  bound **explicitly** in `:agent-tools` (not via the `default-agent-roster`
  merge) so the file-inherent CRUD path works on the direct `bb tui -a skill-agent`
  entry too.
- **explore-agent**: still binds `skills$list/find/read/reload` **explicitly**
  (it predates the base roster add). It now inherits the read subset from the
  base too, so the explicit bindings are redundant — the §10 "drop them" cleanup
  was deferred, not done. Harmless (de-duped at roster build).

## 11. Migration — Complete

- The read subset on the base roster + the substrate section landed as **purely
  additive** — no behavior removed, so it shipped independently.
- skill-agent's authoring swap (maps → direct `write-file`s) is internal; the
  skill on-disk layout is unchanged, so existing skills keep working and stay
  discoverable.
- No data migration. `skills$reload` after any file-level change keeps the
  registry coherent.
- Part of **Phase 1** of the synthesis rollout (base substrates), alongside the
  other base-agent section + roster adds.

## 12. Risks & mitigations

| Risk | Mitigation |
| --- | --- |
| Every agent now calls `skills$find` and adds latency. | Discovery is cheap (indexed list); the substrate says "check *when a multi-step procedure is involved*," not on every turn. |
| An agent follows a stale/wrong skill. | `skills$read` returns the SKILL.md + path; the agent cites `skill:<backend>:<name>` and applies judgment. Skills are advisory procedures, not contracts. |
| A non-skill-agent tries to write a skill inline. | The base roster excludes the write subset; the substrate's USE-is-read-only rule + "route to skill-agent to capture" keep lifecycle centralized. |
| Direct `write-file` skips skill-agent's dup-check/scope guard. | skill-agent's instruction keeps the `skills$find` dup-check first; brainyard-scope is enforced by writing only under `.brainyard/skills/`. The CLI backends stay read-only. |
| Registry out of sync after a direct write. | The authoring flow ends with `skills$reload`. |

## 13. Verification

| Check | What it verifies |
| --- | --- |
| **File-inherent CRUD** | Create a skill via `write-file` SKILL.md + a script; `skills$reload`; `skills$find` discovers it; the registered `:skill$<name>` tool invokes. |
| **Substrate discovery** | A non-skill agent (e.g. exec-agent) on a task with a matching skill calls `skills$find`, `skills$read`, and follows it — with no route to skill-agent. |
| **Use is read-only** | A base agent cannot `skills$write` (not in its roster); it suggests routing to skill-agent instead. |
| **Read subset inherited** | `skills$find/read/list/reload` resolve on any coact/react-derived agent after the roster add. |
| **Lifecycle intact** | skill-agent still creates/updates/removes/installs/syncs and runs the proposal loop. |
| **Cite** | An agent that used a skill emits `skill:<backend>:<name>`. |

## 14. Files Summary

| File | Role |
| --- | --- |
| `components/agent/src/ai/brainyard/agent/common/skill_agent.clj` | `defagent skill-agent` via `coact/run-coact-derived`; instruction + tool-context (file-inherent CRUD + lifecycle); roster = skills$* + proposals + file/shell tools. |
| `components/agent/src/ai/brainyard/agent/common/skills.clj` | `skills$*` commands, plain API, dynamic `:skill$<name>` registration, and the `skills-read-subset` export. |
| `components/agent/src/ai/brainyard/agent/common/agent_roster.clj` | `default-agent-roster` (merges `skills-read-subset`) + `skill-substrate-protocol` string. |
| `components/agent/src/ai/brainyard/agent/common/coact_agent.clj` | Installs `:skill-substrate` into `coact-system-context`. |
| `components/agent/src/ai/brainyard/agent/common/react_agent.clj` | Installs `:skill-substrate` into `react-system-context`. |
| `components/agent/test/ai/brainyard/agent/skills_test.clj` | Skill CRUD + discovery + registration tests. |

## 15. Open questions

1. **Read subset on `default-agent-roster` vs. only the base coact/react
   rosters?** Shipped on `default-agent-roster` (reaches user-defined / meta-agent
   personas too).
2. **Auto-reload after a `.brainyard/skills/` write** (file-watch or post-write
   hook) vs. explicit `skills$reload`? Explicit `skills$reload` shipped; a hook is
   the smoother follow-up.
3. **Combine the skill substrate with Project Memory** into one "Consult before
   acting" base section (procedures + facts), or keep separate? Kept separate but
   adjacent for now.
4. **Should skill *use* be metered/capped** to avoid an agent over-eagerly
   `skills$find`-ing every turn? Handled by the prompt gate ("multi-step task
   only") for now.
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

Authoring a skill is writing its files; using a skill is reading and following a
markdown procedure — and because that's pure LLM-inherent capability, the skill
library is in the base so every agent has it at hand, while skill-agent keeps the
keys to the library's *lifecycle*.
