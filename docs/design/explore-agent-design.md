# Explore-Agent — Unified Multi-Source Exploration Agent (CoAct-derived)

> **Status:** Shipped. search-agent retired (2026-05-16); lightweight authoring
> redesign + reuse-via-references shipped (2026-06). This doc is the as-built
> reference — the former `explore-agent-lightweight-redesign.md` has been folded
> in here and removed.
>
> **As-built (verify against `common/explore_agent.clj`, `common/explore.clj`):**
> - **Authoring is direct markdown, not a helper chain.** The dossier is a
>   markdown file; the model fills the RESULT TEMPLATE (§5.2) and `write-file`s
>   it. The old write-side helpers `explore$slug` / `explore$frontmatter` /
>   `explore$write` / `explore$index-append` are **retired** (§10). Only three
>   read/discovery helpers survive: `explore$find`, `explore$read-frontmatter`,
>   `explore$reuse?`.
> - **Every run starts with a reuse check.** STEP 0 is a mandatory
>   `explore$find` prior-art gate (§5.0); a fresh on-topic dossier short-circuits
>   the probe. Dossiers carry a `related:` lineage list and a `freshness:`
>   class so reuse is judged, not assumed.
> - **Tool invocation is direct, not via `call-tool`.** The shipped instruction
>   probes with `(grep {…})` / `(search {…})` / `(web-search {…})` /
>   `(skills$find …)` and dispatches other agents with direct kebab-case
>   `(plan-agent {…})`. The `(call-tool "grep" {…})` / `(call-tool "<agent>" {…})`
>   forms below are equivalent but not how the shipped prompt phrases it.
> - **Hard Rule 1 reads "STAY FLAT — no clone-self dispatch."** `query$clone` is
>   simply omitted from the roster (gated to rlm-agent), so the §6 wording
>   differs slightly from the long "NO `query$clone`" rule; intent is identical.
> - **An `:agent.ask/finalize` auto-persist hook** fills the same RESULT TEMPLATE
>   and `spit`s one file when the LLM skips the persistence checklist (common on
>   smaller models). It writes the *same one-file path* the happy path uses (no
>   divergence) and injects the `Saved exploration:` line if absent. A missing
>   line therefore does NOT mean nothing was saved; consumers fall back to
>   `(explore$find …)` or the newest `INDEX.md` entry.
> - Migration plan §12 (retiring search-agent) is **complete** —
>   `search_agent.clj` is gone from the roster.
>
> **Scope:** `components/agent/src/ai/brainyard/agent/common/explore_agent.clj`
> **Built on:** `coact_agent.clj` via `coact/run-coact-derived`
> **Supersedes:** `search_agent.clj` (deleted)
> **Related reading:** `docs/CoAct.md`, `docs/design/rlm-agent-design.md`,
> `docs/design/agent-lightweight-redesign-synthesis.md`,
> `components/agent/.../mcp_agent.clj`, `components/agent/.../skill_agent.clj`

---

## 1. Motivation

`search-agent` (the retired generalist) bundled file-system tools (`grep`,
`read-file`, `bash`, `search`), web tools (`web-search`, `fetch-url`), todo
commands, and discovery tools. That toolset was sufficient when brainyard's
exploration surface was effectively *files + web*. It was no longer.

Two surfaces grew up around it:

1. **MCP servers.** Configured Model Context Protocol servers (Slack, Linear,
   Asana, Box, Jira, Notion, custom internal services, …) expose native tools,
   resources, and prompts via the `mcp$server` / `mcp$tools` / `mcp$lifecycle`
   polymorphic command trio (`components/agent/.../mcp_agent.clj`). When the
   answer to "what's blocking my sprint?" lives in Linear, search-agent had no
   path to it short of asking the user to use mcp-agent instead.
2. **CLI skills.** `skills$list` / `skills$find` / `skills$read` / `skills$write`
   / `skills$install` / `skills$sync` cover three skill backends (`:brainyard`,
   `:claude`, `:agents`). A question like "is there a skill that can refactor my
   docx?" is exploration-shaped, but search-agent had no skill awareness.

The result: users had to *know in advance* which agent to invoke, pre-classifying
their question into "filesystem/web (search-agent), MCP (mcp-agent), skills
(skill-agent)". That is the wrong job for the user. **Exploration is fundamentally
a routing problem**, and the agent should do the routing.

The second issue is **handoff**. Even when search-agent found the right answer,
its output was a single inline markdown blob. When another agent (plan-agent,
exec-agent, rlm-agent, a downstream summarizer) wanted to *consume* that
exploration, it had to receive it through the chat-history channel, competing
with the user's actual question for context budget. There was no stable,
addressable artifact for "the exploration I did 5 minutes ago".

A third issue surfaced once exploration results were durable: **amnesia.** The
agent re-walked the same files / servers / skills every turn, even when a fresh
dossier already answered the question on disk. Persisting results without
*reusing* them turns the corpus into a write-only log.

**Thesis.** A CoAct-derived `explore-agent` that:

1. **Routes across four exploration surfaces** — local files, web, MCP servers,
   CLI skills — picking the right one (or several in parallel) per sub-question.
2. **Persists every non-trivial exploration** under
   `.brainyard/agents/explore-agent/` as a markdown *dossier* — authored
   **directly** by the model from a fixed template — then **returns a stable file
   reference alongside the inline summary**.
3. **Reuses before re-exploring.** Every run begins with an `explore$find`
   prior-art check; a fresh, on-topic dossier short-circuits the probe. Dossiers
   link the prior ones they build on (`related:` lineage), so the corpus is a
   navigable cache rather than a flat pile.
4. **Emits a handoff line** (`Saved exploration: <path>`) that downstream agents
   consume by being passed *just the path* in their `:agent-context` — no
   context-window blow-up.
5. **Inherits the CoAct loop, sandbox, router, and accumulator** from
   `coact-agent` via `run-coact-derived`. No new BT, no new DSPy signature.

This is the same minimal-diff pattern that `skill-agent`, `mcp-agent`, and
`rlm-agent` follow. The whole feature is one agent file plus a small read-only
helpers namespace.

---

## 2. Design Principles

1. **One agent, four surfaces.** The agent picks the right surface (or several in
   parallel) per sub-question. It does not require the user to pre-classify.
2. **Filesystem-first for project questions; MCP-first for tool questions;
   skills-first for procedural questions.** Encoded as a decision flow in the
   instruction (§6). Defaults bias toward the cheapest surface that could answer.
3. **Reuse before re-explore.** Iteration 0 of every run is a mandatory
   `explore$find` prior-art check (§5.0). A sufficiently fresh, on-topic dossier
   short-circuits the probe. This is what converts the corpus from a write-only
   log into a reuse cache.
4. **Writes are LLM-inherent; reads stay deterministic.** The result *is* a
   markdown document, so the model `write-file`s it from a fixed template — no
   construct-and-render helper chain. Reading and discovery
   (`explore$read-frontmatter`, `explore$find`, `explore$reuse?`) stay typed and
   deterministic, exactly where a machine beats the model. (This is the
   cross-agent principle in `agent-lightweight-redesign-synthesis.md`: separate
   *judgment* — authoring prose — from *mechanism* — parsing/freshness/dossier
   ops.)
5. **Persist by default.** Every non-trivial exploration writes a dossier.
   Trivial Q/A (single fact, < 1KB output, zero entities) skip persistence. The
   threshold lives in the instruction so it is tunable.
6. **Stable, addressable artifacts.** File names are `<yyyyMMdd-HHmmss>-<slug>.md`.
   The model derives a deterministic kebab slug inline; the timestamp prefix
   handles collisions. No GUIDs.
7. **Frontmatter for downstream parsers.** Every dossier has YAML frontmatter
   with `question`, `created`, `slug`, `surfaces`, `entities`, `related`,
   `freshness`, and `summary`. Other agents can read just the frontmatter for
   cheap routing.
8. **Dossiers reference dossiers.** A `related:` list records prior dossiers a
   run builds on; a `## Builds on` body section says what was reused vs. newly
   found. Lineage is advisory — a missing target is a soft warning, not an error.
9. **Freshness is explicit.** `freshness: static|volatile` encodes the dependency
   class so reuse is judged, not assumed: `static` (filesystem/code) is valid
   while cited files are unchanged; `volatile` (web/MCP) goes stale after a short
   window.
10. **Inline summary + path.** The agent's `:answer` always includes both: the
    inline summary AND the persisted/reused file path, on a line with the stable
    `Saved exploration: ` prefix the dispatcher can grep for.
11. **No clone-self recursion.** Like `rlm-agent`, `explore-agent` excludes
    `query$clone` (which clones the CURRENT agent). Sub-LLM calls go through
    `query$llm` for synthesis only. *Cross-agent dispatch — invoking a DIFFERENT
    registered agent — is flat call-tool routing through the shared registry,
    fully allowed, and is the recommended in-loop handoff path to
    plan-agent / exec-agent / rlm-agent.*
12. **Conservative defaults for write-side MCP.** MCP tools whose native
    description mentions create/update/delete/send/post/execute are *enumerable*
    but require explicit user confirmation before invocation. The exploration
    surface is read-by-default.

---

## 3. Position in the Agent Stack

```
coact-agent  (parent — full BT, sandbox, router, accumulator)
  ├─ explore-agent     (multi-surface read-mostly exploration; replaces the retired search-agent)
  ├─ mcp-agent         (MCP-only deep dives, lifecycle ops)
  ├─ skill-agent       (skills$* lifecycle: create/update/install)
  ├─ rlm-agent         (sibling — MapReduce over large filesystem context)
  └─ plan-agent / todo-agent / exec-agent / eval-agent
```

`explore-agent` does **not** replace `mcp-agent` or `skill-agent`:

| Question | Use | Why |
|---|---|---|
| "What MCP servers are running and what do they expose?" | `explore-agent` | Discovery — exactly what explore is for. |
| "Restart the linear MCP server, then call its create-issue tool." | `mcp-agent` | Lifecycle + write operations. |
| "Find skills tagged `pdf` and show what they do." | `explore-agent` | Read-only skill discovery. |
| "Create a new skill that lints my markdown." | `skill-agent` | Skill authoring. |
| "Where is the loop guard implemented?" | `explore-agent` | File-system code search. |
| "Summarize patterns across 200 log files." | `rlm-agent` | MapReduce over too-big context. |

Rule of thumb: **explore-agent is for read-mostly discovery across mixed
sources**; the specialist agents own write operations and deep workflows for
their respective surface.

---

## 4. Surfaces & Tool Roster

`explore-agent` binds tools from four surfaces plus the bookkeeping primitives.

### 4.1 Surface A — File System & Local

| Tool | Use |
|---|---|
| `grep` | Regex search inside files (allow-listed dirs). |
| `read-file` | Read file content with `:lines` / `:offset` / `:limit`. |
| `write-file` | Write to `/tmp/`, `.brainyard/`, or other allow-listed paths. **Used by explore-agent itself to author dossiers** (creates parent dirs). |
| `update-file` | Pattern replacement with diff (rarely used in exploration; kept for parity). |
| `bash` | One shell command, 30s default. Used for `find`, `git log`, `tree`, `ls`, pipelines, and `git diff --quiet` freshness checks. |
| `search` | Project-files + config + memory + tools registry keyword search. |
| `fetch-url` | HTTP GET (also under web). |

### 4.2 Surface B — Web

| Tool | Use |
|---|---|
| `web-search` | Tavily-backed web search. |
| `fetch-url` | HTTP GET on a discovered URL. |

### 4.3 Surface C — MCP Servers

| Tool | Use |
|---|---|
| `mcp$server` | `:list` / `:info` / `:config` / `:capabilities` / `:resources` / `:prompts` / `:health`. Discovery-only ops. |
| `mcp$tools` | `:list` (always read-only) / `:call` (gated — see safety rules) / `:read-resource` / `:get-prompt`. |
| `mcp$lifecycle` | `:start` / `:stop` / `:restart`. Allowed for *connecting* a configured server before reading from it; explicit user confirm before stop/restart. |

### 4.4 Surface D — CLI Skills (read-only subset)

| Tool | Use |
|---|---|
| `skills$list` | List skills across `:brainyard` / `:claude` / `:agents` backends. |
| `skills$find` | Keyword search across skill names + descriptions. |
| `skills$read` | Read a `SKILL.md` body + metadata. Auto-detects backend. |
| `skills$reload` | Refresh registry (rare; sometimes needed after `skills$sync`). |

**Excluded** from explore-agent: `skills$write` / `skills$install` /
`skills$sync`. Authoring lives in `skill-agent`.

### 4.5 Bookkeeping, Discovery & Reuse

| Tool | Use |
|---|---|
| `list-tools` | Enumerate everything in the registry (commands, skills, agents, MCP). |
| `get-tool-info` | Schema for one tool by id. |
| `call-tool` | Generic dispatch from a clojure fence. |
| `query$llm` | Sub-LLM call for synthesis across cross-surface results. **Single + batched** (max 20 prompts). Flat — no `query$clone`. |
| `explore$find` | **Read-only** corpus search by slug/summary/surfaces (INDEX-first, per-file `question` fallback). The iteration-0 reuse gate. |
| `explore$read-frontmatter` | **Read-only** cheap metadata read of one dossier's `---/---` block. For freshness judgments, resolving `related:` links, and downstream routing. |
| `explore$reuse?` | **Read-only** freshness verdict for one dossier: `{:reuse? :freshness :age-hours :changed :reason}`, applying the §5.4 rule (mtime/git for `static`, age window for `volatile`). |
| `task$run` (`:job-type :tool|:bash`) | Long-running operations (rarely needed; kept for `bash` jobs >5s). |

### 4.6 Final Roster

```clojure
(:require
  [ai.brainyard.agent.common.tools     :as common-tools]
  [ai.brainyard.agent.common.commands  :as common-cmds]
  [ai.brainyard.agent.common.skills    :as skills]
  [ai.brainyard.agent.common.explore   :as explore]   ; read-only helpers
  [ai.brainyard.agent.mcp.commands     :as mcp-cmds]
  [ai.brainyard.agent.task.commands    :as task-cmds])

;; Conceptually:
(def explore-tools
  (vec (distinct
         (concat
           common-tools/file-tools          ; read-file, write-file, grep, fetch-url, update-file
           common-tools/shell-tools         ; bash
           common-tools/web-tools           ; web-search, fetch-url
           common-tools/bootstrap-tools     ; list-tools, get-tool-info, search
           common-tools/invocation-tools    ; call-tool

           ;; MCP (read-mostly)
           mcp-cmds/all-mcp-commands

           ;; Skills (read-only subset — explicit allow-list)
           [#'skills/skills$list
            #'skills/skills$find
            #'skills/skills$read
            #'skills/skills$reload]

           ;; Sub-LLM synthesis (flat only)
           [#'common-cmds/query$llm]        ; intentionally excludes #'query$clone

           ;; Explore read/discovery/reuse helpers (NO write-side helpers — authoring is write-file)
           explore/explore-helpers          ; [#'explore$find #'explore$read-frontmatter #'explore$reuse?]

           ;; Background jobs
           task-cmds/task-commands))))
```

Note: authoring needs **no** dedicated roster entry — `write-file` is already
bound for filesystem exploration and is what writes dossiers. The redesign was
contained in the *instruction* + the shrunk helpers namespace, not the roster
shape.

---

## 5. Output Discipline — Reuse, then Persistence in `.brainyard/agents/explore-agent/`

The single biggest behavioral difference vs. the retired search-agent: every
non-trivial exploration first *checks the corpus*, then (if it must) *authors a
dossier directly as markdown*.

### 5.0 Iteration-0 Reuse Check (mandatory)

Before probing any surface, search the corpus for prior work:

```clojure
(def prior (explore$find :query "<key nouns/entities from the question, not the full sentence>"))
;; for a promising hit, judge staleness:
(def verdict (explore$reuse? :path "<that dossier's path>"))
```

Decision rule (in the instruction):

- **Hit, fresh, on-topic** → do NOT re-explore. Read the prior dossier
  (`explore$read-frontmatter`, or full body if needed), answer from it, and emit
  its path as the `Saved exploration:` line with a `Reused: <slug> (<created>)`
  note. No new full dossier is written (pointer dossiers are opt-in, default off).
- **Hit, but stale or partial** → read it, probe ONLY the gap, and write a new
  dossier whose `related:` lists the prior path (§5.5). Don't re-walk what the
  prior dossier already covered.
- **No hit** → full exploration; `related: []`.

This single gate is what makes exploration cumulative: explore-agent won't redo a
probe it already has on disk, and a root agent can avoid even dispatching
explore-agent when the corpus already answers (§11.5).

### 5.1 Directory Layout

```
.brainyard/agents/explore-agent/
├── results/             ; permanent — the dossier corpus
│   ├── 20260509-181244-loop-guard-implementation.md
│   ├── 20260509-191812-q2-revenue-mcp.md
│   └── 20260510-094501-skills-with-pdf-tag.md
├── drafts/              ; in-progress, overwritable this turn
├── INDEX.md             ; one-line entry per result, newest first
└── README.md            ; (optional) layout cheat-sheet for humans
```

- `results/` is durable. Other agents read from here.
- `drafts/` is per-turn scratch — anything written here this turn is fair game to
  overwrite.
- `INDEX.md` gets one appended line per persisted run (§5.6). A downstream agent
  (or the user) can read the index alone to enumerate available explorations
  cheaply.

### 5.2 The Result-Dossier Template (authored directly)

The instruction carries this template verbatim. The model fills the `<…>` slots
and `write-file`s it to `results/<yyyyMMdd-HHmmss>-<slug>.md`. There is **no**
`explore$frontmatter` / `explore$write` construction step — the model writes the
markdown.

```markdown
---
slug: <kebab-slug>
question: "<verbatim question, or first 200 chars>"
created: <ISO-8601, e.g. 2026-06-29T14:03:11Z>
agent: explore-agent
surfaces: [<filesystem, web, mcp, skills — those actually used>]
entities:
  files: [<repo-relative paths cited>]
  urls: [<URLs cited>]
  mcp_tools: [<server:tool entries called>]
  skills: [<skill names read>]
related: [<prior dossier paths this builds on — from STEP 0; [] if none>]
freshness: <static | volatile>   # static = filesystem/code; volatile = web/MCP/time-sensitive
summary: >
  <one-paragraph distilled answer, folded to one line on read>
---

# <Title>

## What was found
<the answer, with citations>

## Where
<file:path:line · url · mcp:server:tool · skill:backend:name>

## Builds on
<bullet links to related: dossiers, one line each on what was reused vs. newly found;
 "None — first exploration of this area." if related is empty.>

## Caveats / freshness
<what could go stale: named files (static) or "captured <date>; re-check if older
 than N days" (volatile)>
```

Frontmatter keys (stable contract — downstream parsers may rely on these):

| Key | Type | Description |
|---|---|---|
| `slug` | string | Kebab-case, derived from the question. Reused across re-runs. |
| `question` | string | Verbatim user question (or first 200 chars). |
| `created` | ISO-8601 string | UTC timestamp. |
| `agent` | string | Always `explore-agent`. |
| `surfaces` | flow vector of `filesystem` / `web` / `mcp` / `skills` | Which surfaces were touched. |
| `entities.files` | vector of repo-relative paths | Files cited as evidence. |
| `entities.urls` | vector of URLs | Web pages cited. |
| `entities.mcp_tools` | vector of `<server>:<tool>` | MCP tools called. |
| `entities.skills` | vector of skill names | Skills consulted. |
| `related` | vector of repo-relative dossier paths | Lineage — prior dossiers this builds on. `[]` if none. |
| `freshness` | `static` \| `volatile` | Dependency class governing reuse (§5.4). |
| `summary` | string | One-paragraph distilled answer. Human + machine readable. |

Why hand-authoring is safe: `surfaces`/`entities`/`related` are single-line flow
vectors (the lenient `parse-flow-vector` accepts them quoted or bare), the keys
are fixed and shown so the model fills blanks rather than inventing structure,
and a keyword typo can't fail a write because markdown has no schema. Malformed
frontmatter is caught by the lenient `parse-flat-yaml` on read.

### 5.3 Slug Derivation (inline)

The model derives the slug inline — kebab-case from the question, stopwords
dropped, capped at ~60 chars:

```
"Where is the loop guard implemented and what does it block?"
  → "loop-guard-implemented-block"
```

The `<yyyyMMdd-HHmmss>` filename prefix guarantees uniqueness, so re-runs of the
same question land near each other in the listing under the same slug without a
collision-suffix helper. Slugs are preferred over GUIDs because listings sort
into recognizable clusters and `explore$find` / `grep` can match the slug
directly.

### 5.4 Freshness Policy

Reuse is only safe if staleness is judged. `freshness:` encodes the dependency
class, and `explore$reuse?` applies the rule so the logic is tested, not vibes:

- **`static`** (filesystem/code/docs): valid until the cited files change.
  `explore$reuse?` checks `git diff --quiet -- <entities.files>` / mtimes;
  unchanged ⇒ reuse freely (generous window, days–weeks).
- **`volatile`** (web / MCP / "what's the state right now"): the answer reflects a
  moment. Dossiers older than a short window (default 24h, tunable via
  `agent-runtime$config :key "explore-reuse-volatile-hours"`) are treated as
  stale ⇒ re-probe.

The point isn't a perfect cache-invalidation engine; it's giving the reuse
decision enough signal to avoid both blind re-exploration and blindly trusting a
week-old "is the Box server healthy?" answer.

### 5.5 Lineage (`related:` + `## Builds on`)

When a run builds on prior dossiers it records them in frontmatter `related:`
(machine-readable) and in a `## Builds on` body section (one line per prior
dossier saying what was reused vs. newly discovered). This makes the corpus
navigable — a reader can walk back to the explorations a dossier stands on, and
`explore$find` surfaces the whole cluster for a topic. It also keeps new dossiers
*small*: they cite prior coverage rather than re-stating it.

### 5.6 Persistence Threshold

Trivial answers do not persist:

- Inline answer < 1000 chars AND only one surface used AND zero entities → SKIP.
- Otherwise → PERSIST.

The threshold is in the instruction, not hardcoded. Tunable per turn via
`agent-runtime$config :key "explore-persist-threshold" :value "N"`.

### 5.7 INDEX.md

Append-only (`write-file :append true`), one line per persisted run:

```markdown
- 2026-05-09 18:12 [loop-guard-implementation](results/20260509-181244-loop-guard-implementation.md) — filesystem · *Loop guard is a :agent.tool-use/pre hook that…*
- 2026-05-09 19:18 [q2-revenue-mcp](results/20260509-191812-q2-revenue-mcp.md) — mcp · *Q2 revenue from Linear MCP shows…*
```

Append-only is newest-last and needs zero extra reads. `explore$find` is
INDEX-first with a per-file fallback that already sorts newest-first by filename,
so discovery works even on an unsorted INDEX. (A read-modify-write prepend to
keep newest-first is an option at one extra read/turn; v1 ships append-only.)

---

## 6. Instruction (System Prompt Body)

Layered on top of `coact-agent`'s instruction by `run-coact-derived`. The
four-surface routing, hard rules, and handoff discipline are unchanged from the
original design; the reuse gate is prepended and the persistence section tells
the model to write markdown directly.

```text
You are an EXPLORE-agent. You answer the user's question by gathering information
across FOUR exploration surfaces, picking the right surface(s) per sub-question,
synthesizing a cited answer, and PERSISTING the result as a markdown dossier at a
stable file path that other agents can consume. Before any of that, you REUSE
prior work so the same ground is never explored twice.

────────────────────────────────────────────────────────────────────────────
STEP 0 — REUSE CHECK (always first; do NOT skip)
────────────────────────────────────────────────────────────────────────────
Before probing ANY surface, search the corpus for prior work:
    (explore$find :query "<key nouns/entities from the question, not the full sentence>")
For a promising hit, judge freshness:
    (explore$reuse? :path "<that dossier's path>")
- Fresh, on-topic hit → DON'T re-explore. Read it (explore$read-frontmatter, or
  full body), answer from it, and end with:
      Reused: <slug> (<created date>)
      Saved exploration: <that dossier's path>
- Stale/partial hit → read it, probe ONLY the gap, then write a NEW dossier whose
  related: lists the prior path.
- No hit → full exploration; related: [].
A `static` (filesystem/code) dossier stays reusable while its cited files are
unchanged; a `volatile` (web/MCP) dossier goes stale after the reuse window (~24h).

────────────────────────────────────────────────────────────────────────────
THE FOUR SURFACES (route deliberately)
────────────────────────────────────────────────────────────────────────────
A. FILESYSTEM (local)  — code, docs, config, memory. Tools: grep, read-file,
                         bash (find/git/rg/tree), search.
B. WEB                 — public internet. Tools: web-search, fetch-url.
C. MCP                 — configured external servers (Slack, Linear, Asana, Box,
                         Jira, custom). Tools: mcp$server, mcp$tools, mcp$lifecycle.
D. SKILLS              — installed reusable procedures (brainyard / claude /
                         agents backends). Tools: skills$list, skills$find, skills$read.

DECISION FLOW
1. Classify the question into surface intents:
   - "where in the code…", "what file…", "this repo…"      → A only
   - "what is X (general world knowledge)"                   → B only
   - "what does our team / project / org…", "in <SaaS>…"     → C (probe MCP);
                                                                fall back to A
   - "is there a skill that…", "use the <name> skill…"        → D (probe SKILLS)
   - mixed (most real questions)                              → multiple surfaces
2. Probe surfaces in parallel when the question is broad: in ONE clojure fence
   with `<!-- ParallelBlock -->` separators, fan out a discovery probe per
   surface. Cheapest probes first.
3. Drill on the surfaces that returned promising hits. Stay shallow on the rest.
4. SYNTHESIZE a single coherent answer with citations and persist if non-trivial.

[surface-specific playbooks A/B/C/D — unchanged from §6 of the original design]

────────────────────────────────────────────────────────────────────────────
PERSISTENCE — write the dossier as markdown (one write-file)
────────────────────────────────────────────────────────────────────────────
The result IS a markdown document. Author it DIRECTLY with `write-file` — do NOT
construct entity maps or call frontmatter/slug/write helpers.
Fill the RESULT TEMPLATE (frontmatter + ## What was found / ## Where /
## Builds on / ## Caveats) and write-file it to
    .brainyard/agents/explore-agent/results/<yyyyMMdd-HHmmss>-<slug>.md
Then append one INDEX line (write-file :append true).
Set related: from STEP 0. Set freshness: static (filesystem) or volatile (web/mcp).

WHEN to persist:
- Inline answer >= 1000 chars                                      → PERSIST
- Two or more surfaces used                                        → PERSIST
- One or more entities cited (file paths, URLs, MCP tools, skills) → PERSIST
- Otherwise (truly trivial Q/A)                                    → SKIP

ANSWER FORMAT (always include both):
1. Inline summary — what the user asked for, with citations.
2. A final line: `Saved exploration: .brainyard/agents/explore-agent/results/<file>.md`
   (or the reused dossier's path) — stable prefix so downstream agents can grep it.

────────────────────────────────────────────────────────────────────────────
HARD RULES
────────────────────────────────────────────────────────────────────────────
1. STAY FLAT — no clone-self dispatch. `query$clone` clones the CURRENT agent
   (you) and is omitted from your roster. Sub-LLM synthesis goes through
   `query$llm` (single or batched). To invoke a DIFFERENT registered agent
   (plan-agent, exec-agent, rlm-agent, mcp-agent, …) for a sub-task that
   legitimately belongs to another specialist, dispatch it directly, e.g.
   `(plan-agent {:question "…" :agent-context "<path>"})`. That is flat
   cross-agent routing through the shared registry — allowed. Use it sparingly.
2. NO write-side MCP tools without explicit user confirmation. Reading is fine;
   create/update/delete/send/post/execute requires the user to say "yes" in a
   follow-up turn.
3. NO touching results/* other than write-new and append-INDEX. Never overwrite
   an existing dossier. To revise, write a NEW dated file with the same slug.
4. NO chunking / MapReduce on huge contexts. Route "summarize 200 log files" to
   rlm-agent — explore is breadth-first discovery, not heavy transformation.
5. NO depth-first download. If a probe returns 50 files / 20 issues, sample the
   most relevant 5–10 first.
6. CITE EVERYTHING. The `entities` frontmatter and inline citations are the
   contract with downstream agents.
7. STOP on ambiguity. Surface conflicts rather than picking one.

────────────────────────────────────────────────────────────────────────────
HANDOFF DISCIPLINE
────────────────────────────────────────────────────────────────────────────
Other agents consume your output via the file path you emit. Inline the short
summary, emit `Saved exploration: <path>` on its own line near the end, and
optionally suggest the next agent + how to invoke it. The receiving agent gets
just the path in its `:agent-context` and read-files the frontmatter (cheap) or
full body (rich) as needed.

NOTE — AUTO-PERSIST BACKSTOP: an :agent.ask/finalize hook fills this same RESULT
TEMPLATE and writes one file if you skip the PERSISTENCE checklist, then injects
the `Saved exploration:` line. It writes the same path the happy path would, so
the two cannot diverge. Follow the checklist anyway — the hook is a safety net,
not the primary path.
```

---

## 7. Tool-Context (How to Use the Bound Tools)

```text
## Explore Tools — by surface

### A. FILESYSTEM (allow-listed dirs only)
- grep, read-file, write-file, update-file, bash, search   (as in §4.1)
  write-file authors dossiers — .brainyard/ and /tmp auto-allowed, parents created.

### B. WEB
- web-search, fetch-url

### C. MCP (read-mostly — write ops require user confirm)
- mcp$server :op <op>     -- :list | :info | :config | :capabilities | :resources | :prompts | :health
- mcp$tools  :op <op>     -- :list | :call | :read-resource | :get-prompt
- mcp$lifecycle :op <op>  -- :start | :stop | :restart  (start OK without confirm; stop/restart not)

### D. SKILLS (read-only subset — authoring lives in skill-agent)
- skills$list, skills$find, skills$read, skills$reload

### Synthesis
- query$llm  -- Sub-LLM call (single or batched). Cross-surface synthesis. FLAT only.

## Persistence — write markdown directly
- write-file / read-file / update-file are bound; .brainyard/ and /tmp are
  auto-allowed; write-file creates parent dirs.
- Author the dossier as markdown from the RESULT TEMPLATE in the instruction.
  NO frontmatter/slug/write construction tools — they were retired.

## Reuse & discovery (READ-ONLY)
- explore$find             -- corpus search. ALWAYS call first (STEP 0) to reuse
                              prior work. Returns {:matches [{:path :slug :summary
                              :surfaces :created}…]}.
- explore$read-frontmatter -- cheap metadata read of one dossier (judge freshness,
                              resolve related: links, downstream routing).
- explore$reuse?           -- freshness verdict for one dossier:
                              {:reuse? :freshness :age-hours :changed :reason}.

## Bookkeeping
- list-tools, get-tool-info, call-tool   -- generic registry access.
- task$run with :job-type :tool|:bash     -- async for >5s operations.

## Typical end-to-end flow
0. STEP 0 reuse check: (explore$find …) → (explore$reuse? …). Fresh hit → answer
   from it, emit its path, done.
1. Classify the question into surface intents (A/B/C/D).
2. Parallel probe (one clojure fence with `<!-- ParallelBlock -->`): each
   surface's cheapest discovery call.
3. Drill on the promising surface(s) only.
4. (Optional) query$llm synthesis if results span 2+ surfaces.
5. Decide PERSIST vs SKIP per the threshold.
6. If PERSIST: fill the RESULT TEMPLATE (related: from STEP 0; freshness:),
   write-file to results/, append one INDEX line.
7. ANSWER: inline summary + `Saved exploration: <path>` line.
```

---

## 8. Behavior Tree — Inherited As-Is

`explore-agent` does **not** define its own BT. `run-coact-derived` falls back to
`coact-agent`'s `:bt-factory`:

```
coact-behavior-tree
  ├─ preflight (question-present?)
  ├─ prepare-conversation / prepare-recalled-memory
  ├─ coact-init-action
  ├─ coact-loop-subtree                ; ThinkActCode → router → accumulate
  ├─ answer-present?
  ├─ optional finalize pass            ; auto-persist hook fires on :agent.ask/finalize
  ├─ coact-store-results-action
  └─ trace/default-maintain-conversation
```

Iteration shape for a typical exploration:

| Iter | Channel | Body |
|---|---|---|
| 0 | code | `(explore$find …)` reuse check; `(explore$reuse? …)` on a hit. Fresh hit → jump to answer. |
| 1 | code | Parallel probe across A/B/C/D (one fence with `<!-- ParallelBlock -->`); `def` results. |
| 2 | code | Drill on the surface(s) with promising hits. |
| 3 | code (optional) | `(query$llm :prompt …)` synthesis over collected evidence. |
| 4 | code | Fill the RESULT TEMPLATE; `(write-file {:path … :content …})`; append INDEX line. |
| 5 | answer | Inline summary + `Saved exploration: <path>` line. |

For trivial questions, iterations collapse and persistence is skipped. A fresh
reuse hit at iteration 0 short-circuits straight to the answer. No new BT
actions, schemas, or SCI bindings are required.

---

## 9. Demonstration: "Where do we configure MCP servers, and which ones are healthy right now?"

Mixed A+C question. The user wants both *code-side* configuration knowledge and
*runtime* MCP state.

### Iteration 0 — Reuse check (code channel)

```clojure
(def prior (explore$find :query "mcp servers config health"))
;; suppose one hit: a `volatile` dossier from 3 days ago.
(def verdict (explore$reuse? :path (-> prior :matches first :path)))
;; => {:reuse? false :freshness :volatile :age-hours 72 :reason "volatile, older than 24h window"}
;; stale → read it for context, then probe ONLY the gap (current health).
```

### Iteration 1 — Parallel probe (code channel)

````markdown
```clojure
;; A — filesystem: where is MCP config defined?
(def fs-hits (search {:query "mcp config server"}))
```
<!-- ParallelBlock -->
```clojure
;; C — MCP runtime: what's connected?
(def mcp-servers (mcp$server :op :list))
```
````

### Iteration 2 — Drill (code channel)

````markdown
```clojure
(def cfg
  (:content (read-file {:path "components/agent/src/ai/brainyard/agent/mcp/integration.clj"
                        :lines [1 80]})))
(def health
  (->> (:servers mcp-servers)
       (filter :connected)
       (map (fn [s] {:name (:name s) :health (mcp$server :op :health :server-name (:name s))}))
       vec))
```
````

### Iteration 3 — Author the dossier directly (code channel)

The model fills the RESULT TEMPLATE and writes it — no helper chain:

````markdown
```clojure
(def ts (.format (java.text.SimpleDateFormat. "yyyyMMdd-HHmmss") (java.util.Date.)))
(def path (str ".brainyard/agents/explore-agent/results/" ts "-mcp-servers-config-health.md"))

(write-file
  {:path path
   :content (str
     "---\n"
     "slug: mcp-servers-config-health\n"
     "question: \"Where do we configure MCP servers, and which ones are healthy right now?\"\n"
     "created: " (java.time.Instant/now) "\n"
     "agent: explore-agent\n"
     "surfaces: [filesystem, mcp]\n"
     "entities:\n"
     "  files: [components/agent/src/ai/brainyard/agent/mcp/integration.clj]\n"
     "  urls: []\n"
     "  mcp_tools: " (pr-str (mapv #(str (:name %) ":health") health)) "\n"
     "  skills: []\n"
     "related: [" (-> prior :matches first :path) "]\n"
     "freshness: volatile\n"
     "summary: >\n"
     "  MCP servers are configured in integration.clj / .brainyard/config; "
     (count (filter (comp :connected :health) health)) " of "
     (count (:servers mcp-servers)) " reachable as of this run.\n"
     "---\n\n"
     "# MCP server configuration and health\n\n"
     "## What was found\n…\n\n"
     "## Where\n- file:components/agent/src/ai/brainyard/agent/mcp/integration.clj:1\n"
     (clojure.string/join "\n" (for [{:keys [name]} health] (str "- mcp:" name ":health"))) "\n\n"
     "## Builds on\n- " (-> prior :matches first :slug)
     " — reused config locations; re-probed live health (was stale, 72h old).\n\n"
     "## Caveats / freshness\nvolatile — health captured now; re-check if older than 24h.\n")})

;; INDEX append (newest-last, append-only)
(write-file
  {:path ".brainyard/agents/explore-agent/INDEX.md"
   :append true
   :content (str "- " (subs (str (java.time.Instant/now)) 0 16)
                 " [mcp-servers-config-health](results/" ts "-mcp-servers-config-health.md)"
                 " — filesystem, mcp · MCP config + "
                 (count (filter (comp :connected :health) health)) "/"
                 (count (:servers mcp-servers)) " healthy.\n")})
```
````

### Iteration 4 — Answer

```markdown
## MCP servers — config + health

**Configured in**: `components/agent/.../mcp/integration.clj` (lines 1–80) +
per-environment entries in `.brainyard/config`.

**Current health (as of run)**: linear ✅ (sse) · slack ✅ (stdio) ·
filesystem ✅ (stdio) · box ❌ unreachable (sse) — auth-token-expired.

Builds on the 3-day-old config dossier (reused locations; live health re-probed).

Saved exploration: .brainyard/agents/explore-agent/results/20260630-181244-mcp-servers-config-health.md
```

A downstream `plan-agent` invocation can be passed *just that path* in its
`:agent-context`; it read-files the frontmatter alone (~500 bytes) and decides
whether to read the full body, never bloating its own context window.

---

## 10. `explore.clj` — Read-Only Helpers Only

The write-side helper chain is **retired**. `explore.clj` ships exactly three
read/discovery helpers, registered in `explore-helpers` and auto-bound in the
SCI sandbox:

| Helper | Signature | What it does |
|---|---|---|
| `explore$find` | `(explore$find :query …)` → `{:matches [{:path :slug :summary :surfaces :created} …]}` | Substring search over INDEX (slug+summary+surfaces) with a per-file `question` frontmatter fallback. The iteration-0 reuse gate. |
| `explore$read-frontmatter` | `(explore$read-frontmatter :path …)` → parsed map (incl. `related`, `freshness`) | Reads only the leading `---/---` block. Cheap routing for downstream agents and `related:` resolution. |
| `explore$reuse?` | `(explore$reuse? :path …)` → `{:reuse? :freshness :age-hours :changed :reason}` | Applies the §5.4 freshness rule (git/mtime for `static`, age window for `volatile`). Keeps freshness logic in one tested place rather than in the prompt. |

**Retired** (do not exist anymore): `explore$slug`, `explore$frontmatter`,
`explore$write`, `explore$index-append`, and the YAML-emission internals
(`yaml-string`, `yaml-flow-vector`, `format-summary`, `build-frontmatter*`,
collision-suffix helpers). Authoring is a direct `write-file` from the §5.2
template.

`parse-flat-yaml` (the lenient reader behind `explore$read-frontmatter`) surfaces
`related` and `freshness` for free — it already handles flat scalars + flow
vectors. Both are documented in its `:output-schema`.

### 10.1 Auto-Persist Backstop

`explore.clj` installs an `:agent.ask/finalize` hook that materializes a dossier
when the LLM skips the persistence checklist (common on smaller models). It is
**not** the primary path — it is a safety net — and it is deliberately built on
the *same* one-file write the happy path uses, so the two cannot diverge:

- `render-dossier` fills the §5.2 RESULT TEMPLATE from regex-detected
  entities/surfaces (`detect-entities`, `detect-surfaces`, `one-line-summary`)
  and `spit`s **one** file (not via the retired helpers).
- It injects the `Saved exploration: <path>` line into the answer if absent.
- It is idempotent (an `already-saved?` check) and self-installs at namespace
  load.

Because the hook is observe-only on the answer text, a missing `Saved
exploration:` line does **not** mean nothing was saved — consumers fall back to
`explore$find` or the newest INDEX entry.

---

## 11. Handoff & Reuse Mechanics — How Other Agents Consume Results

The contract between explore-agent and downstream agents is **just the file
path**.

### 11.1 The `Saved exploration:` Line

Every persisted (or reused) answer ends with a line of the exact form:

```
Saved exploration: <repo-relative-path>
```

The prefix is stable. The dispatcher can grep for `Saved exploration:` in the
previous answer to extract the path mechanically — no structured-output parsing.
On a pure-reuse turn the path points at the existing dossier and the answer also
carries a `Reused: <slug> (<date>)` note; the dispatcher can't tell (and needn't)
whether the dossier was freshly written or reused.

### 11.2 Two Levels of Cheap Read

**Cheap (~500 bytes):** `(explore$read-frontmatter :path "<path>")` — question,
surfaces, entities, `related`, `freshness`, one-line summary. Sufficient for
routing decisions.

**Full (~3–10 KB):** `(read-file {:path "<path>"})` — the whole narrative.

### 11.3 :agent-context Wiring

Two legitimate ways to chain explore → plan-agent (or any specialist):

**A. From inside explore-agent's loop** — flat cross-agent dispatch:

```clojure
(plan-agent {:question      "Draft a plan to fix the unreachable Box MCP server."
             :agent-context "Saved exploration: .brainyard/agents/explore-agent/results/<file>.md"})
```

Allowed because plan-agent is a DIFFERENT registered agent, not a clone of
explore-agent. Hard Rule 1 only prohibits `query$clone` (clone-self).

**B. From an outer dispatcher** — the orchestrator reads the previous answer,
greps `^Saved exploration: `, extracts the path, and invokes the next agent with
that path in `:agent-context`.

In both paths the receiving agent gets just the path string and read-files the
frontmatter (cheap) or full body (rich) as needed.

### 11.4 Lineage Across Dossiers

`related:` makes the corpus navigable: from any dossier a reader walks back to the
explorations it stands on, and `explore$find` surfaces the whole topic cluster.
Lineage is advisory — a missing/archived target is a soft warning on read, not an
error.

### 11.5 Reuse at the Root-Agent Boundary

The bigger win is letting the *dispatching* agent avoid even spawning
explore-agent, with no new infrastructure:

1. **Pre-dispatch check.** Before a root agent calls `(explore-agent {…})`, it
   runs `(explore$find :query "<topic>")`. A fresh on-topic hit means it passes
   that dossier path straight into its own `:agent-context` and skips the explore
   call. (plan-agent's pre-flight "EXPLORED?" check already looks for a `Saved
   exploration:` path — extended to consult `explore$find` when the context
   doesn't carry one.)
2. **Reused handoff line.** When explore-agent short-circuits (§5.0), its answer
   still ends with `Saved exploration: <existing path>`, so the root agent's
   normal grep-the-handoff flow works unchanged.

Reuse compounds: explore-agent won't redo a probe, and a root agent won't even
dispatch one when the corpus already answers.

---

## 12. Migration — Complete

### 12.1 search-agent retirement (2026-05, done)

`search-agent` was a strict subset of explore-agent. It has been removed:
`search_agent.clj` is gone from the roster, and `bases/agent-tui` /
`bases/agent-web` carry no references. explore-agent is the default for
"exploration / discovery / find" intents.

### 12.2 Lightweight authoring + reuse (2026-06, done)

The on-disk dossier schema is a **superset** of the pre-redesign one (adds
`related`, `freshness`), so existing results stayed readable and downstream
agents were untouched. The change landed as:

1. New `explore_agent.clj` instruction/tool-context (STEP 0 reuse gate +
   write-markdown PERSISTENCE section + RESULT TEMPLATE).
2. Slimmed `explore.clj`: removed the three write-side helpers + their tests;
   kept/retargeted the reader + find tests; added `explore$reuse?`, reuse-gate,
   and lineage tests (§13).
3. Auto-persist hook rewritten to fill the template + `spit` one file.
4. Backfill optional: old dossiers simply have no `related`/`freshness` (parsers
   treat missing keys as absent).

---

## 13. Verification

| Benchmark | Shape | What it verifies |
|---|---|---|
| Filesystem-only Q (carry-over) | search-agent's old corpus | No regression vs. the retired search-agent. |
| MCP discovery | "What X-MCP-server tools exist? Health?" | Surface-C routing reached without prompting. |
| Skills discovery | "Find me a skill that does X." | Surface-D routing; `skills$find` preferred over `search`. |
| Mixed (A+C) | "Where is config for X in code AND in the running MCP server?" | Parallel probe across A and C in iteration 1. |
| Trivial bypass | "What's the capital of France?" | Persistence SKIPPED (Q < 1000 chars, zero entities). |
| **Authoring** | Non-trivial run | Writes exactly one dossier via `write-file`; frontmatter parses; `surfaces`/`entities` populated; body has the 4 sections. |
| **Reuse — fresh hit** | Identical Q within the window | No new full dossier; re-emits the prior path with a `Reused:` line; no surface probe runs (mulog `::explore.probe` absent / `::explore.reuse` present). |
| **Reuse — stale volatile** | `volatile` dossier older than window | Re-probe + new dossier. |
| **Reuse — static invalidation** | Touch a cited file (git change) | `static` dossier becomes non-reusable. |
| **Lineage** | Gap-fill run | Writes `related: [<prior path>]` + a `## Builds on` section; `explore$read-frontmatter` returns the `related` list. |
| **Root-agent reuse** | plan-agent pre-flight | Finds a prior dossier via `explore$find` and skips dispatching explore-agent. |
| Downstream unchanged | New-schema dossier → plan-agent fixture | Reads `entities`/`summary` exactly as before. |
| Conservative MCP write | Q implying a write op | Agent stops at the proposal, asks for confirm — does NOT call `mcp$tools :op :call`. |
| Auto-persist backstop | Skipped dossier | Hook materializes a parseable one from answer text; `Saved exploration:` line injected. |

Per-iteration mulog signals:

- `::explore.probe` — `{:surfaces […] :elapsed-ms N}`
- `::explore.persist` — `{:slug … :path … :bytes N :surfaces […] :entities-count N}`
- `::explore.skip-persist` — `{:reason :trivial :answer-chars N}`
- `::explore.reuse` — `{:slug :path :reason :age-h}`
- `::explore.gap-fill` — `{:slug :related [paths]}`

---

## 14. Files Summary

| File | Role |
|---|---|
| `components/agent/src/ai/brainyard/agent/common/explore_agent.clj` | `instruction` (STEP 0 reuse + write-markdown PERSISTENCE + RESULT TEMPLATE), `tool-context`, `defagent explore-agent` via `coact/run-coact-derived`. |
| `components/agent/src/ai/brainyard/agent/common/explore.clj` | Read-only helpers (`explore$find`, `explore$read-frontmatter`, `explore$reuse?`) + the `:agent.ask/finalize` auto-persist hook. Write-side helpers removed. |
| `components/agent/test/ai/brainyard/agent/explore_agent_test.clj` | Registration smoke test, template-authoring test, reuse-gate + freshness + lineage tests. |
| `.brainyard/agents/explore-agent/README.md` | Directory layout cheat-sheet. |
| `components/agent/src/ai/brainyard/agent/common/coact_agent.clj` | NO CHANGES — substrate, BT, sandbox, DSPy signature untouched. |

The feature is one agent file plus a slim read-only helpers file. The redesign
was contained in the instruction + the shrunk helpers namespace — the roster,
BT, sandbox, and DSPy signature were untouched.

---

## 15. Open Questions

1. **Pointer dossiers on pure reuse — on or off by default?** Off keeps INDEX
   clean; on keeps a complete turn-by-turn lineage. Currently off (§5.0).
2. **Where does freshness checking live** — prompt rule, `explore$reuse?` reader,
   or a `:agent.tool-use/pre` hook on `(explore-agent …)` dispatch? The reader is
   the tested middle ground; a dispatch hook would let *any* root agent get reuse
   for free without prompt changes. Worth prototyping.
3. **Should reuse be global or per-user/project?** Dossiers are project-scoped
   under `.brainyard/`. A cross-project `~/.brainyard/agents/explore-agent/`
   corpus could help but mixes contexts. Deferred.
4. **`results/` retention.** ~1100 files over six months of weekly runs.
   Disk-cheap but listing-noisy. A `bb explore:archive` task moving results older
   than 90 days into `archive/<YYYY-MM>/` is out of scope for the agent itself.
5. **Strict vs. lenient dossier read** — reject malformed frontmatter, or warn
   and continue? Lenient+warn keeps the pipeline flowing; strict catches drift.
   Currently lenient.
6. **Roll the same reuse-via-references treatment to plan-agent dossiers?** The
   plan-agent redesign already does the file-tool half; the reuse half maps
   cleanly onto its duplicate-plan check. Follow-up.
