# Explore-Agent — Unified Multi-Source Exploration Agent (CoAct-derived)

> **Status:** Shipped. search-agent retired (2026-05-16).
> **Scope:** `components/agent/src/ai/brainyard/agent/common/explore_agent.clj`
> **Built on:** `coact_agent.clj` via `coact/run-coact-derived`
> **Supersedes:** `search_agent.clj` (deleted)
> **Related reading:** `docs/CoAct.md`, `docs/rlm-agent-design.md`, `components/agent/.../mcp_agent.clj`, `components/agent/.../skill_agent.clj`

---

## 1. Motivation

`search-agent` (`components/agent/.../search_agent.clj`) is the current generalist for "go look something up": it bundles file-system tools (`grep`, `read-file`, `bash`, `search`), web tools (`web-search`, `fetch-url`), todo commands, and discovery tools. That toolset was sufficient when brainyard's exploration surface was effectively *files + web*. It is no longer.

Two surfaces have grown up around it:

1. **MCP servers.** Configured Model Context Protocol servers (Slack, Linear, Asana, Box, Jira, Notion, custom internal services, …) expose native tools, resources, and prompts via the `mcp$server` / `mcp$tools` / `mcp$lifecycle` polymorphic command trio (`components/agent/.../mcp_agent.clj`). When the answer to "what's blocking my sprint?" lives in Linear, search-agent has no path to it short of asking the user to use mcp-agent instead.
2. **CLI skills.** `skills$list` / `skills$find` / `skills$read` / `skills$write` / `skills$install` / `skills$sync` cover three skill backends (`:brainyard`, `:claude`, `:agents`). Skills are reusable procedures, sometimes with executable scripts. A user question like "is there a skill that can refactor my docx?" or "use the pdf skill to extract tables from this report" is exploration-shaped, but search-agent has no skill awareness.

The result: users have to *know in advance* which agent to invoke. They must pre-classify their question into "filesystem/web (search-agent), MCP (mcp-agent), skills (skill-agent)". That is the wrong job for the user. **Exploration is fundamentally a routing problem**, and the agent should do the routing.

The second issue is **handoff**. Even when search-agent finds the right answer, its output is a single inline markdown blob. When another agent (plan-agent, exec-agent, rlm-agent, a downstream user-facing summarizer) wants to *consume* that exploration, it has to receive it through the chat-history channel, where it competes with the user's actual question for context budget. There is no stable, addressable artifact for "the exploration I did 5 minutes ago". CoAct supports passing context via `:agent-context`, but only as a string — it is not durable across the conversation.

**Thesis.** Add a CoAct-derived `explore-agent` that:

1. **Routes across four exploration surfaces** — local files, web, MCP servers, CLI skills — picking the right one (or several in parallel) per sub-question.
2. **Persists every non-trivial exploration** under `.brainyard/agents/explore-agent/` as a structured markdown artifact with frontmatter metadata, then **returns a stable file reference alongside the inline summary**.
3. **Emits a handoff line** (`Saved exploration: <path>`) that downstream agents can consume by being passed *just the path* in their `:agent-context` — no context-window blow-up.
4. **Inherits the CoAct loop, sandbox, router, and accumulator** from `coact-agent` via `run-coact-derived`. No new BT, no new DSPy signature.
5. **Supersedes search-agent** along a clear deprecation timeline (§12).

This is the same minimal-diff pattern that `search-agent`, `skill-agent`, `mcp-agent`, and (the recently-designed) `rlm-agent` already follow. The whole feature is one new agent file plus an optional small helpers namespace.

---

## 2. Design Principles

1. **One agent, four surfaces.** The agent's job is to pick the right surface (or several in parallel) per sub-question. It does not require the user to pre-classify.
2. **Filesystem-first for project questions; MCP-first for tool questions; skills-first for procedural questions.** Encoded as a decision flow in the instruction (§5). Defaults bias toward the cheapest surface that could possibly answer.
3. **Persist by default.** Every non-trivial exploration writes a result file under `.brainyard/agents/explore-agent/`. Trivial Q/A (single fact, < 1KB output) skip persistence. The threshold is in the instruction so it is tunable.
4. **Stable, addressable artifacts.** File names are `<yyyyMMdd-HHmmss>-<slug>.md`. Slugs are deterministic (kebab-case from the question) so re-runs of the same question land near each other in the directory listing. No GUIDs.
5. **Frontmatter for downstream parsers.** Every artifact has YAML frontmatter with `question`, `created`, `slug`, `surfaces` (which surfaces were used), `entities` (file paths, URLs, MCP servers, skill names mentioned), and `summary` (one-line). Other agents can `read-file` just the frontmatter for cheap routing.
6. **Inline summary + path.** The agent's `:answer` output always includes both: the inline summary (what the user asked for) AND the persisted file path (for downstream handoff). One line near the end is dedicated to the path with a stable prefix the dispatcher can grep for.
7. **No clone-self recursion.** Like `rlm-agent`, `explore-agent` excludes `query$clone` from its tool roster — `query$clone` clones the CURRENT agent (explore-agent itself) and runs another copy with its own iteration loop. Sub-LLM calls go through `query$llm` for synthesis only. *Cross-agent dispatch — invoking a DIFFERENT registered agent via `(call-tool "<agent-name>" {:question …})` — is flat call-tool routing through the shared registry, not query$clone, and is fully allowed.* This is in fact the recommended in-loop handoff path to plan-agent / exec-agent / rlm-agent when the question genuinely needs another specialist's expertise mid-exploration.
8. **Conservative defaults for write-side MCP.** MCP tools whose native description mentions create/update/delete/send/post/execute are *enumerable* but require explicit user confirmation before invocation. The exploration surface is read-by-default.
9. **Keep search-agent's strengths.** All file/web/discovery tools that search-agent currently uses are retained. The migration path (§12) is purely additive: anyone using search-agent can swap in explore-agent and get a strict superset of capabilities.

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

> The rest of this document is preserved as historical motivation and contract reference for the explore-agent. References to search-agent below describe the agent's state before retirement; current code no longer registers search-agent. The migration plan in §12 is now complete.

`explore-agent` does **not** replace `mcp-agent` or `skill-agent`:

| Question | Use | Why |
|---|---|---|
| "What MCP servers are running and what do they expose?" | `explore-agent` | Discovery — exactly what explore is for. |
| "Restart the linear MCP server, then call its create-issue tool." | `mcp-agent` | Lifecycle + write operations. |
| "Find skills tagged `pdf` and show what they do." | `explore-agent` | Read-only skill discovery. |
| "Create a new skill that lints my markdown." | `skill-agent` | Skill authoring. |
| "Where is the loop guard implemented?" | `explore-agent` | File-system code search. |
| "Summarize patterns across 200 log files." | `rlm-agent` | MapReduce over too-big context. |

Rule of thumb: **explore-agent is for read-mostly discovery across mixed sources**; the specialist agents own write operations and deep workflows for their respective surface.

---

## 4. Surfaces & Tool Roster

`explore-agent` binds tools from four surfaces plus the bookkeeping primitives. The roster is a strict superset of search-agent's.

### 4.1 Surface A — File System & Local

Inherited from search-agent, unchanged:

| Tool | Use |
|---|---|
| `grep` | Regex search inside files (allow-listed dirs). |
| `read-file` | Read file content with `:lines` / `:offset` / `:limit`. |
| `write-file` | Write to `/tmp/`, `.brainyard/`, or other allow-listed paths. Used by explore-agent itself to persist results. |
| `update-file` | Pattern replacement with diff (rarely used in exploration; kept for parity). |
| `bash` | One shell command, 30s default. Used for `find`, `git log`, `tree`, `ls`, pipelines. |
| `search` | Project-files + config + memory + tools registry keyword search. |
| `fetch-url` | HTTP GET (also under web). |

### 4.2 Surface B — Web

Inherited from search-agent:

| Tool | Use |
|---|---|
| `web-search` | Tavily-backed web search. |
| `fetch-url` | HTTP GET on a discovered URL. |

### 4.3 Surface C — MCP Servers (NEW for explore-agent)

Brought over from `mcp-agent`'s roster, but the *write-side guidance* is added in the instruction:

| Tool | Use |
|---|---|
| `mcp$server` | `:list` / `:info` / `:config` / `:capabilities` / `:resources` / `:prompts` / `:health`. Discovery-only ops. |
| `mcp$tools` | `:list` (always read-only) / `:call` (gated — see safety rules) / `:read-resource` / `:get-prompt`. |
| `mcp$lifecycle` | `:start` / `:stop` / `:restart`. Allowed for *connecting* a configured server before reading from it; explicit user confirm before stop/restart. |

### 4.4 Surface D — CLI Skills (NEW for explore-agent)

Read-only subset of `skills-commands`:

| Tool | Use |
|---|---|
| `skills$list` | List skills across `:brainyard` / `:claude` / `:agents` backends. |
| `skills$find` | Keyword search across skill names + descriptions. |
| `skills$read` | Read a `SKILL.md` body + metadata. Auto-detects backend. |
| `skills$reload` | Refresh registry (rare; sometimes needed after `skills$sync`). |

**Excluded** from explore-agent: `skills$write` / `skills$install` / `skills$sync`. Authoring lives in `skill-agent`.

### 4.5 Bookkeeping & Discovery

| Tool | Use |
|---|---|
| `list-tools` | Enumerate everything in the registry (commands, skills, agents, MCP). |
| `get-tool-info` | Schema for one tool by id. |
| `call-tool` | Generic dispatch from a clojure fence. |
| `query$llm` | Sub-LLM call for synthesis across cross-surface results. **Single + batched** (max 20 prompts). Flat — no `query$clone`. |
| `task$run` (`:job-type :tool|:bash`) | Long-running operations (rarely needed in exploration; kept for `bash` jobs >5s). |

### 4.6 Final Roster

```clojure
(:require
  [ai.brainyard.agent.common.tools     :as common-tools]
  [ai.brainyard.agent.common.commands  :as common-cmds]
  [ai.brainyard.agent.common.skills    :as skills]
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
                                            ; (query$clone clones THIS agent;
                                            ;  to invoke a DIFFERENT registered
                                            ;  agent use call-tool "<agent-name>")

           ;; Background jobs
           task-cmds/task-commands))))
```

---

## 5. Output Discipline — Persistence in `.brainyard/agents/explore-agent/`

The single biggest behavioral change vs. search-agent.

### 5.1 Directory Layout

```
.brainyard/
├── explore-agent/
│   ├── results/             ; permanent — the artifact corpus
│   │   ├── 20260509-181244-loop-guard-implementation.md
│   │   ├── 20260509-191812-q2-revenue-mcp.md
│   │   └── 20260510-094501-skills-with-pdf-tag.md
│   ├── drafts/              ; in-progress, may be overwritten this turn
│   │   └── …
│   ├── INDEX.md             ; one-line entry per result, newest first
│   └── README.md            ; (optional) layout cheat-sheet for humans
```

- `results/` is durable. Other agents read from here.
- `drafts/` is per-turn scratch — anything written here this turn is fair game to overwrite. The agent is told it can use this for intermediate state without worrying about colliding with prior results.
- `INDEX.md` is appended to (not rewritten) by the agent at the end of every persisted run. It exists so a downstream agent (or the user) can do a `read-file` on the index alone to enumerate available explorations cheaply.

### 5.2 Result-File Layout

Every persisted result is a markdown file with YAML frontmatter:

```markdown
---
slug: loop-guard-implementation
question: "Where is the loop guard implemented and what does it block?"
created: 2026-05-09T18:12:44Z
agent: explore-agent
surfaces: [filesystem]
entities:
  files:
    - components/agent/src/ai/brainyard/agent/common/loop_guard_hook.clj
    - components/agent/src/ai/brainyard/agent/core/hooks.clj
  urls: []
  mcp_tools: []
  skills: []
summary: >
  Loop guard is a :agent.tool-use/pre hook that examines recent tool-call
  history for repetition and answers a :block verdict with a synthetic
  answer. Implementation in loop_guard_hook.clj; registered on agent boot
  via the hooks/register! call in core/hooks.clj.
turn_id: <id>
session_id: <id>
---

# Loop guard implementation

## What it is
…

## Where it lives
- `components/agent/.../loop_guard_hook.clj` (lines 23–88) — the predicate.
- `components/agent/.../hooks.clj` (line 412) — registration.

## How it integrates
…

## References
- file:components/agent/src/ai/brainyard/agent/common/loop_guard_hook.clj:23
- file:components/agent/src/ai/brainyard/agent/core/hooks.clj:412
```

Frontmatter keys (stable contract — downstream parsers may rely on these):

| Key | Type | Description |
|---|---|---|
| `slug` | string | Kebab-case, deterministic from the question. Reused across re-runs. |
| `question` | string | Verbatim user question (or first 200 chars). |
| `created` | ISO-8601 string | UTC timestamp. |
| `agent` | string | Always `explore-agent`. |
| `surfaces` | vector of `filesystem` / `web` / `mcp` / `skills` | Which surfaces were touched. |
| `entities.files` | vector of repo-relative paths | Files cited as evidence. |
| `entities.urls` | vector of URLs | Web pages cited. |
| `entities.mcp_tools` | vector of `<server>:<tool>` | MCP tools called. |
| `entities.skills` | vector of skill names | Skills consulted. |
| `summary` | string | One-paragraph distilled answer. Human + machine readable. |
| `turn_id` / `session_id` | string | For trajectory cross-reference. |

The body is freeform markdown but should follow a 3-section rhythm: **What was found**, **Where**, **How / Caveats**.

### 5.3 Slug Derivation

A small helper (in the optional helpers namespace, §10) produces the slug deterministically:

```
"Where is the loop guard implemented and what does it block?"
  → kebab-case → "where-is-the-loop-guard-implemented-and-what-does-it-block"
  → drop stopwords (where, is, the, and, what, does, it) → "loop-guard-implemented-block"
  → cap at 60 chars                                       → "loop-guard-implemented-block"
```

If a result with the same slug already exists, append `-N`. This is preferred over GUIDs because:

- Listings sort alphabetically into recognizable clusters.
- A user typing `bb tui ask "@.brainyard/agents/explore-agent/results/2026*loop-guard*"` can reasonably tab-complete.
- Other agents can `grep` the slug directly to find prior work on the same question.

### 5.4 Persistence Threshold

Trivial answers do not persist:

- Inline answer < 1000 chars AND only one surface used AND zero entities → skip persistence.
- Otherwise → persist.

The threshold is in the instruction, not hardcoded. Tunable per turn via `agent-runtime$config :key "explore-persist-threshold" :value "N"`.

### 5.5 INDEX.md Append Format

```markdown
- 2026-05-09 18:12 [loop-guard-implementation](results/20260509-181244-loop-guard-implementation.md) — filesystem · *Loop guard is a :agent.tool-use/pre hook that…*
- 2026-05-09 19:18 [q2-revenue-mcp](results/20260509-191812-q2-revenue-mcp.md) — mcp · *Q2 revenue from Linear MCP shows…*
```

Append-only. The agent may rewrite the **first 200 lines** when adding a new entry but NEVER touches earlier history. (Implementation hint: read first 200 lines, prepend new entry, write back; rest is concatenated unchanged. Optional helper in §10.)

---

## 6. Instruction (System Prompt Body)

Layered on top of `coact-agent`'s instruction by `run-coact-derived`.

```text
You are an EXPLORE-agent. You answer the user's question by gathering information
across FOUR exploration surfaces, picking the right surface(s) per sub-question,
synthesizing a cited answer, and PERSISTING the result to a stable file path
that other agents can consume.

────────────────────────────────────────────────────────────────────────────
THE FOUR SURFACES (route deliberately)
────────────────────────────────────────────────────────────────────────────
A. FILESYSTEM (local)  — code, docs, config, memory inside this project.
                         Tools: grep, read-file, bash (find/git/rg/tree),
                         search.
B. WEB                 — public internet. Tools: web-search, fetch-url.
C. MCP                 — configured external servers (Slack, Linear, Asana,
                         Box, Jira, custom).
                         Tools: mcp$server, mcp$tools, mcp$lifecycle.
D. SKILLS              — installed reusable procedures (brainyard / claude /
                         agents backends).
                         Tools: skills$list, skills$find, skills$read.

DECISION FLOW
1. Classify the question into surface intents:
   - "where in the code…", "what file…", "this repo…"      → A only
   - "what is X (general world knowledge)"                   → B only
   - "what does our team / project / org…", "in <SaaS>…"     → C (probe MCP);
                                                                fall back to A
   - "is there a skill that…", "use the <name> skill…"        → D (probe SKILLS)
   - mixed (most real questions)                              → multiple surfaces

2. Probe surfaces in parallel when the question is broad or unclear:
   - In ONE clojure fence with `<!-- ParallelBlock -->` separators, fan out
     a discovery probe per surface. Cheapest probes first:
       • A: `(call-tool "grep" {:pattern "<keyword>" :path ".brainyard"})`
            or `(call-tool "search" {:query "<keyword>"})`
       • C: `(mcp$server :op :list)` then targeted `(mcp$tools :op :list …)`
       • D: `(skills$find :query "<keyword>")`
       • B: `(call-tool "web-search" {:query "<keyword>" :max-results 5})`
   - Aggregate across the four results before deciding where to drill.

3. Drill on the surfaces that returned promising hits. Stay shallow on the rest.

4. SYNTHESIZE a single coherent answer with citations and persist if non-trivial.

────────────────────────────────────────────────────────────────────────────
SURFACE-SPECIFIC PLAYBOOKS
────────────────────────────────────────────────────────────────────────────
A. FILESYSTEM (carries over from search-agent)
   - Prefer grep → read-file for code. Use `bash` for find/git log/git grep/
     tree/rg/fd when grep is too coarse.
   - Cite as `file:<repo-relative-path>:<line>`.

B. WEB
   - web-search first (Tavily snippets often suffice). fetch-url only when
     the snippet is insufficient.
   - Cite as `<url>` (include title in prose).

C. MCP
   - Always `(mcp$server :op :list)` first to confirm what's available and
     connected. If the relevant server is :connected false, run
     `(mcp$lifecycle :op :start :server-name "<name>")` BEFORE listing tools.
   - `(mcp$tools :op :list :server-name "<server>")` to learn native tool
     names and input schemas. Never invent native names.
   - For READS (search/fetch/list/get/show/read): proceed without confirmation.
   - For WRITES (create/update/delete/send/post/execute): STOP. Surface the
     proposed call to the user in your `answer`, ask for confirmation. Only
     proceed in a follow-up turn after explicit approval.
   - Cite as `mcp:<server>:<tool>` and include the relevant return-shape excerpt.

D. SKILLS
   - `skills$find` is the keyword discovery entry point. `skills$list` to
     enumerate by type/scope. `skills$read` to inspect SKILL.md bodies.
   - When the user wants to USE a skill (apply its instructions to their
     content), read the SKILL.md and follow its instructions in subsequent
     iterations — DO NOT delegate to skill-agent. Skill-agent is for
     authoring (create/update/install), not consumption.
   - Cite as `skill:<backend>:<skill-name>`.

────────────────────────────────────────────────────────────────────────────
PERSISTENCE — `.brainyard/agents/explore-agent/`
────────────────────────────────────────────────────────────────────────────
EVERY non-trivial exploration is persisted as a markdown file with YAML
frontmatter, then the path is included in your `answer` for downstream handoff.

Layout:
   .brainyard/agents/explore-agent/
     results/<yyyyMMdd-HHmmss>-<slug>.md     ; durable artifact corpus
     drafts/                                  ; per-turn scratch (overwritable)
     INDEX.md                                 ; append-only, newest first

WHEN to persist:
- Inline answer >= 1000 chars                                      → PERSIST
- Two or more surfaces used                                        → PERSIST
- One or more entities cited (file paths, URLs, MCP tools, skills) → PERSIST
- Otherwise (truly trivial Q/A)                                    → SKIP

WHAT to write:
- Frontmatter keys (REQUIRED): slug, question, created, agent, surfaces,
                                entities, summary
- Body: 3-section rhythm — What was found / Where / How or Caveats.
- Close with explicit citations (file:path:line, url, mcp:server:tool,
  skill:backend:name).

SLUG: kebab-case from the question, stopwords dropped, capped at 60 chars.
      If a result with the same slug exists, suffix with -2, -3, …

INDEX UPDATE: append one line to INDEX.md after writing the result. Format:
   - <YYYY-MM-DD HH:MM> [<slug>](results/<file>.md) — <surfaces> · <one-line summary>

ANSWER FORMAT (always include both):
1. Inline summary — what the user asked for, with citations.
2. A final line: `Saved exploration: .brainyard/agents/explore-agent/results/<file>.md`
   — stable prefix `Saved exploration: ` so downstream agents can grep for it.

────────────────────────────────────────────────────────────────────────────
HARD RULES
────────────────────────────────────────────────────────────────────────────
1. NO `query$clone`. `query$clone` clones the CURRENT agent (you,
   explore-agent) and runs another copy of YOU with a child loop — that
   is clone-self recursion and is forbidden. Sub-LLM calls for synthesis
   go through `query$llm` (single or batched) only.

   To invoke a DIFFERENT registered agent (plan-agent, exec-agent,
   rlm-agent, mcp-agent, …) for a sub-task that legitimately belongs to
   another specialist, use:

       (call-tool "<agent-name>" {:question "<sub-question>"
                                  :agent-context "<optional path / context>"})

   Every defagent registers in the same tool registry, so cross-agent
   dispatch is flat call-tool — NOT query$clone. Use this sparingly: most
   exploration should resolve in this loop. Reach for it when a sub-task
   is unambiguously another agent's domain (e.g. "now plan how to fix
   this" → plan-agent; "now MapReduce-summarize all 200 of these files"
   → rlm-agent).
2. NO write-side MCP tools without explicit user confirmation. Reading is fine;
   create/update/delete/send/post/execute requires the user to say "yes" in
   a follow-up turn. Never assume prior consent applies to a new external call.
3. NO touching `.brainyard/agents/explore-agent/results/*` other than write-new and
   append-INDEX. Never overwrite an existing result file. To revise a prior
   exploration, write a NEW dated file with the same slug + `-N` suffix.
4. NO chunking / MapReduce on huge contexts. If the question is "summarize
   200 log files", route to rlm-agent — explore-agent is breadth-first
   discovery, not heavy transformation.
5. NO depth-first download. If a probe returns 50 files / 20 issues / many
   results, sample the most relevant 5–10 first and surface that. Do not
   read all 50.
6. CITE EVERYTHING. The `entities` frontmatter and the inline citations are
   the contract with downstream agents. Make them accurate.
7. STOP on ambiguity. If the question maps to surfaces with conflicting
   answers, surface the conflict in your answer rather than picking one.

────────────────────────────────────────────────────────────────────────────
HANDOFF DISCIPLINE
────────────────────────────────────────────────────────────────────────────
Other agents (plan-agent, exec-agent, rlm-agent, …) consume your output via
the file path you emit. When the user's NEXT request implies a follow-up
that another agent should handle, your answer should:

  1. Inline the short summary as usual.
  2. Emit `Saved exploration: <path>` on its own line near the end.
  3. (Optional) Suggest the next agent + how to invoke it, e.g.
     `Next: pass the path above as :agent-context to plan-agent.`

The other agent will be invoked with the file path in its `:agent-context`.
It can `read-file` the frontmatter alone (cheap) or the full body (rich) as
needed.
```

---

## 7. Tool-Context (How to Use the Bound Tools)

```text
## Explore Tools — by surface

### A. FILESYSTEM (allow-listed dirs only)
- grep           -- Regex search inside files. Args: pattern, path, include-exts, max-results.
- read-file      -- Read a file. Args: path, offset, limit, lines.
- write-file     -- Write a file (/tmp/, .brainyard/ always allowed). Args: path, content, append.
- update-file    -- Pattern replacement with diff. Rare in exploration.
- bash           -- One shell command, 30s default. Use for find / git log / git grep / tree / rg / fd.
- search         -- Project files + config + long-term memory + tools registry keyword search.

### B. WEB
- web-search     -- Tavily search. Args: query, max-results, search-depth, include-answer.
- fetch-url      -- HTTP GET. Args: url, timeout, max-chars.

### C. MCP (read-mostly here — write ops require user confirm)
- mcp$server :op <op>            -- :list | :info | :config | :capabilities |
                                    :resources | :prompts | :health
- mcp$tools  :op <op>             -- :list | :call | :read-resource | :get-prompt
- mcp$lifecycle :op <op>          -- :start | :stop | :restart (start is OK
                                    without confirm; stop/restart is not)

  Typical flow:
    (mcp$server :op :list)
      → (mcp$tools :op :list :server-name "<server>")
      → (mcp$tools :op :call
                   :tool-calls [{:server-name "<server>"
                                 :tool-name   "<native>"
                                 :tool-args   {<k> <v>, ...}}])

### D. SKILLS (read-only subset — authoring lives in skill-agent)
- skills$list    -- List skills across types. Args: type (brainyard|claude|agents),
                    scope (project|user, brainyard only).
- skills$find    -- Keyword search. Args: query, type.
- skills$read    -- Read SKILL.md + metadata. Args: skill-name, type, scope.
- skills$reload  -- Refresh registry (after skills$sync from skill-agent).

### Synthesis
- query$llm      -- Sub-LLM call (single or batched). Use for cross-surface
                    synthesis when results from A/B/C/D need to be merged
                    into one coherent answer. FLAT only — never recursive.

### Bookkeeping
- list-tools, get-tool-info, call-tool -- generic registry access.
- task$run with :job-type :tool|:bash         -- async for >5s operations.

## Persistence helpers (optional `explore$*` helpers when present)
- explore$slug     -- Deterministic slug from a question string.
- explore$write    -- Write a result file with frontmatter into .brainyard/agents/explore-agent/results/.
- explore$index    -- Append a one-line entry to .brainyard/agents/explore-agent/INDEX.md.
- explore$frontmatter -- Build a YAML frontmatter block from a metadata map.

If these helpers are not bound, build the equivalent inline with write-file
and a clojure fence (see the example in §9).

## Typical end-to-end flow
1. Classify the question into surface intents (A/B/C/D).
2. Parallel probe (one clojure fence with `<!-- ParallelBlock -->`):
   each surface's cheapest discovery call.
3. Drill on the promising surface(s) only.
4. (Optional) `query$llm` synthesis if results span 2+ surfaces.
5. Decide PERSIST vs SKIP per the threshold.
6. If PERSIST: build frontmatter, write to results/, append INDEX.md.
7. ANSWER: inline summary + `Saved exploration: <path>` line.
```

---

## 8. Behavior Tree — Inherited As-Is

`explore-agent` does **not** define its own BT. `run-coact-derived` falls back to `coact-agent`'s `:bt-factory`:

```
coact-behavior-tree
  ├─ preflight (question-present?)
  ├─ prepare-conversation / prepare-recalled-memory
  ├─ coact-init-action
  ├─ coact-loop-subtree                ; ThinkActCode → router → accumulate
  ├─ answer-present?
  ├─ optional finalize pass
  ├─ coact-store-results-action
  └─ trace/default-maintain-conversation
```

Iteration shape for a typical exploration:

| Iter | Channel | Body |
|---|---|---|
| 1 | code | Parallel probe across A/B/C/D (one fence with `<!-- ParallelBlock -->`); `def` results. |
| 2 | code | Drill on the surface(s) with promising hits (read-file / mcp$tools call / skills$read / fetch-url). |
| 3 | code (optional) | `(query$llm :prompt …)` synthesis over collected evidence. |
| 4 | code | Build frontmatter; `(call-tool "write-file" {:path "…" :content …})`; append INDEX.md. |
| 5 | answer | Inline summary + `Saved exploration: <path>` line. |

For trivial questions (all-A, < 1000 chars answer), iterations 1–2 collapse and persistence is skipped — answer arrives in iteration 1 or 2.

No new BT actions, no new schemas, no SCI binding additions are required for the baseline.

---

## 9. Demonstration: "Where do we configure MCP servers, and which ones are healthy right now?"

Mixed A+C question. The user wants both *code-side* configuration knowledge and *runtime* MCP state.

### Iteration 1 — Parallel probe (code channel)

````markdown
```clojure
;; A — filesystem: where is MCP config defined?
(def fs-hits
  (call-tool "search" {:query "mcp config server"}))
```
<!-- ParallelBlock -->
```clojure
;; C — MCP runtime: what's connected?
(def mcp-servers (mcp$server :op :list))
```
<!-- ParallelBlock -->
```clojure
;; D — skills: any MCP-related skills?
(def skill-hits (skills$find :query "mcp"))
```
````

The runtime forks the sandbox and runs the three blocks concurrently. After completion `fs-hits`, `mcp-servers`, `skill-hits` are all defined.

### Iteration 2 — Drill (code channel)

````markdown
```clojure
;; FS drill: read the most promising config file
(def cfg
  (:content
    (call-tool "read-file"
               {:path "components/agent/src/ai/brainyard/agent/mcp/integration.clj"
                :lines [1 80]})))

;; MCP drill: health-check each connected server
(def health
  (->> (:servers mcp-servers)
       (filter :connected)
       (map (fn [s] {:name (:name s)
                     :health (mcp$server :op :health :server-name (:name s))}))
       vec))

(println {:n-fs (count (:project-files fs-hits))
          :n-connected (count (filter :connected (:servers mcp-servers)))
          :n-skills (count (:results skill-hits))})
```
````

### Iteration 3 — Persist + synthesize (code channel)

````markdown
```clojure
(def slug "where-mcp-servers-configured-and-healthy")
(def ts (.format (java.text.SimpleDateFormat. "yyyyMMdd-HHmmss") (java.util.Date.)))
(def path (str ".brainyard/agents/explore-agent/results/" ts "-" slug ".md"))

(def frontmatter
  (str "---\n"
       "slug: " slug "\n"
       "question: \"Where do we configure MCP servers, and which ones are healthy right now?\"\n"
       "created: " (java.time.Instant/now) "\n"
       "agent: explore-agent\n"
       "surfaces: [filesystem, mcp]\n"
       "entities:\n"
       "  files: [components/agent/src/ai/brainyard/agent/mcp/integration.clj,\n"
       "          components/agent/src/ai/brainyard/agent/mcp/client.clj]\n"
       "  urls: []\n"
       "  mcp_tools: " (pr-str (mapv #(str (:name %) ":health") health)) "\n"
       "  skills: []\n"
       "summary: >\n"
       "  MCP servers are configured in <project>/.brainyard/config or via\n"
       "  the integration.clj wiring. As of this run, "
       (count (filter (comp :connected :health) health))
       " of "
       (count (:servers mcp-servers))
       " configured servers are reachable.\n"
       "---\n\n"))

(def body
  (str "# MCP server configuration and health\n\n"
       "## Where it's configured\n"
       "- `components/agent/src/ai/brainyard/agent/mcp/integration.clj` (lines 1-80) — Integrant lifecycle, transport selection.\n"
       "- `components/agent/src/ai/brainyard/agent/mcp/client.clj` — connection management.\n"
       "- Per-environment server entries live in `.brainyard/config` (project) or `~/.brainyard/config` (user).\n\n"
       "## Current health\n"
       (clojure.string/join "\n"
         (for [{:keys [name health]} health]
           (str "- **" name "** — " (if (:connected health) "✅ healthy" "❌ unreachable")
                " (" (or (:transport health) "?") ")")))
       "\n\n## How"
       "MCP servers are loaded at agent boot through Integrant. Each server is\n"
       "configured by name + transport (stdio/sse/http). Servers can be\n"
       "started/stopped at runtime via `mcp$lifecycle`.\n\n"
       "## References\n"
       "- file:components/agent/src/ai/brainyard/agent/mcp/integration.clj:1\n"
       "- file:components/agent/src/ai/brainyard/agent/mcp/client.clj:1\n"
       (clojure.string/join "\n"
         (for [{:keys [name]} health]
           (str "- mcp:" name ":health")))))

(call-tool "write-file" {:path path :content (str frontmatter body)})

;; Append to INDEX
(def index-line
  (str "- " (subs (str (java.time.Instant/now)) 0 16)
       " [" slug "](results/" ts "-" slug ".md) — filesystem, mcp · "
       "MCP config + " (count (filter (comp :connected :health) health))
       "/" (count (:servers mcp-servers)) " healthy.\n"))

(let [idx-path ".brainyard/agents/explore-agent/INDEX.md"
      existing (try (:content (call-tool "read-file" {:path idx-path :limit 200000}))
                    (catch Exception _ ""))]
  (call-tool "write-file"
             {:path idx-path
              :content (str index-line existing)}))

(println {:saved path})
```
````

### Iteration 4 — Answer

```markdown
## MCP servers — config + health

**Configured in**:
- `components/agent/src/ai/brainyard/agent/mcp/integration.clj` (lines 1–80) — Integrant lifecycle.
- `components/agent/src/ai/brainyard/agent/mcp/client.clj` — connection management.
- Per-environment server entries: `.brainyard/config` (project) or `~/.brainyard/config` (user).

**Current health (as of run)**:
- **linear** — ✅ healthy (sse)
- **slack** — ✅ healthy (stdio)
- **filesystem** — ✅ healthy (stdio)
- **box** — ❌ unreachable (sse) — last error in agent log was auth-token-expired

**How**: MCP servers are loaded at agent boot through Integrant. Each is configured by name + transport (`stdio`/`sse`/`http`). Lifecycle ops at runtime via `mcp$lifecycle`.

Saved exploration: .brainyard/agents/explore-agent/results/20260509-181244-where-mcp-servers-configured-and-healthy.md
```

A downstream `plan-agent` invocation that wants to draft "fix the unreachable box server" can be passed *just that path* in its `:agent-context`. It will `read-file` the frontmatter alone (cheap, ~500 bytes) and decide whether to read the full body, never bloating its own context window with a verbatim copy.

---

## 10. Optional `(explore$*)` Sandbox Helpers

These compress the most repetitive parts of the persistence flow. Like `rlm$*`, they live in a single new namespace `ai.brainyard.agent.common.explore`, are registered as `defcommand`s, and surface in the sandbox via the auto-binding path.

| Helper | Signature | What it does |
|---|---|---|
| `explore$slug` | `(explore$slug :question "<text>")` → `"<slug>"` | Deterministic kebab-case slug, stopwords dropped, capped at 60 chars. |
| `explore$frontmatter` | `(explore$frontmatter :question … :slug … :surfaces [...] :entities {...} :summary …)` → string | Build the YAML frontmatter block. |
| `explore$write` | `(explore$write :slug … :content …)` → `{:path "…"}` | Compute the timestamped filename, write to `.brainyard/agents/explore-agent/results/`, return the path. Auto-suffixes `-2/-3/...` on slug collision. |
| `explore$index-append` | `(explore$index-append :path … :slug … :surfaces [...] :summary …)` → `{:appended true}` | Read INDEX.md, prepend a one-line entry, write back. |
| `explore$read-frontmatter` | `(explore$read-frontmatter :path …)` → `{:slug … :question … :surfaces […] :entities {...} :summary …}` | Cheap parse — reads only the leading `---`/`---` block. Used by *downstream* agents that just want metadata, not the body. |
| `explore$find` | `(explore$find :query …)` → `[{:path :slug :summary} …]` | Search the results corpus by slug or summary substring. Lets explore-agent surface "we already explored this 2 days ago — see <path>" without re-running the whole probe. |

Iteration 3 from the demonstration in §9 collapses to roughly:

```clojure
(def fm (explore$frontmatter
          :question "Where do we configure MCP servers, and which ones are healthy right now?"
          :slug     (explore$slug :question "Where do we …")
          :surfaces [:filesystem :mcp]
          :entities {:files ["components/agent/src/ai/brainyard/agent/mcp/integration.clj"
                              "components/agent/src/ai/brainyard/agent/mcp/client.clj"]
                     :mcp_tools (mapv #(str (:name %) ":health") health)}
          :summary  (str "MCP servers are configured in .brainyard/config; "
                         (count (filter (comp :connected :health) health))
                         "/"
                         (count (:servers mcp-servers))
                         " currently reachable.")))

(def res (explore$write :slug "where-mcp-servers-configured-and-healthy"
                        :content (str fm body)))
(explore$index-append :path (:path res) :slug "where-mcp-servers-configured-and-healthy"
                      :surfaces [:filesystem :mcp]
                      :summary "MCP config + 3/4 healthy.")
```

Helpers are not strictly required (the agent works without them, as in the longer §9 example). They are an iteration-budget optimization: the prompt becomes shorter because the agent does not have to inline the same boilerplate every run.

---

## 11. Handoff Mechanics — How Other Agents Consume Results

The contract between explore-agent and downstream agents is **just the file path**.

### 11.1 The `Saved exploration:` Line

Every persisted answer ends with a line of the exact form:

```
Saved exploration: <repo-relative-path>
```

This prefix is stable. The agent dispatcher (whoever picks the next agent) can grep for `Saved exploration:` in the previous answer to extract the path mechanically. No structured-output parsing, no JSON envelope.

### 11.2 Two Levels of Cheap Read

A downstream agent can consume the result at two granularities:

**Cheap (~500 bytes):** read just the frontmatter.

```clojure
(def md (explore$read-frontmatter :path "<path>"))
;; or, without the helper:
(def head (:content (call-tool "read-file" {:path "<path>" :lines [1 25]})))
```

This gives the question, the surfaces touched, the entity references (file paths, URLs, MCP tools, skills), and a one-line summary. Sufficient for routing decisions ("does this exploration cover what plan-agent needs?") without paying for the full body.

**Full (~3–10 KB typically):** read the whole file.

```clojure
(def md (:content (call-tool "read-file" {:path "<path>"})))
```

When the downstream agent needs the actual narrative.

### 11.3 :agent-context Wiring

There are two legitimate ways to chain explore → plan-agent (or any other specialist agent).

**A. From inside explore-agent's loop** — flat cross-agent call-tool:

```clojure
(call-tool "plan-agent"
           {:question      "Draft a plan to fix the unreachable Box MCP server."
            :agent-context "Saved exploration: .brainyard/agents/explore-agent/results/20260509-181244-where-mcp-servers-configured-and-healthy.md"})
```

This is allowed because plan-agent is a DIFFERENT registered agent (a different `defagent`), not a clone of explore-agent. It surfaces in the registry like any other tool, and `call-tool` routes through the same dispatcher search-agent / mcp-agent / skill-agent already use for any registered tool. Hard Rule 1 only prohibits `query$clone` (clone-self). Cross-agent dispatch via `call-tool` is flat — no depth-2 of the *same* agent type.

**B. From an outer dispatcher** — the orchestrator picks the next agent based on the previous answer:

```
turn N   : run explore-agent → answer ends with `Saved exploration: <path>`
turn N+1 : dispatcher reads the answer, greps `^Saved exploration: `,
           extracts <path>, then invokes plan-agent with that path in
           :agent-context.
```

In both paths the receiving agent (plan-agent here) gets just the path string in its `:agent-context`. It uses `read-file` to load the frontmatter (cheap) or the full body (rich) as needed.

When to pick which:
- Path A — explore-agent already has the result *in this turn* and the next step is unambiguously another specialist's domain. Saves a turn round-trip.
- Path B — the user is in control of what comes next, or the next step is genuinely a separate user request (the explore answer is a deliverable in its own right).

### 11.4 Cross-Reference From Trajectory

`coact-store-results-action` (§7 of `docs/CoAct.md`) already writes a trajectory record per turn. We add one optional field:

```clojure
{... existing trajectory keys ...
 :explore-artifact-path ".brainyard/agents/explore-agent/results/20260509-..."}
```

This is set by explore-agent when it persists. Downstream tooling (the trajectory inspector, future analytics) can correlate trajectories with their persisted artifacts without re-parsing the answer text.

---

## 12. Migration Plan: Retiring `search-agent`

`search-agent` is a strict subset of `explore-agent`'s capabilities. Migration is staged so any caller currently dispatching to `search-agent` keeps working.

### Phase 0 — Land `explore-agent`

- New `explore_agent.clj` registered as a sibling agent.
- New optional `explore.clj` helpers namespace.
- New `.brainyard/agents/explore-agent/` layout with `INDEX.md` template + `README.md` (one-pager).
- Tests: smoke test (registration), persistence test (write → read-frontmatter round-trip).
- No changes to `search_agent.clj`. Both agents coexist.

### Phase 1 — Promote `explore-agent`

- Default agent for "exploration / discovery / find" intents in the dispatcher rules. `search-agent` remains available.
- `bb tui agents` documents `explore-agent` as the recommended choice.
- Feature flag `:explore-as-search` (defaults `true`) routes anyone explicitly asking for search-agent to explore-agent. Easy off-switch if regressions surface.
- Benchmark explore-agent against search-agent on a representative question set. Acceptance: explore-agent must NOT regress on filesystem-only queries (the only thing search-agent could do).

### Phase 2 — Soft-deprecate `search-agent`

- Add a one-time deprecation log at search-agent invocation: "search-agent is deprecated; switching to explore-agent. Disable the auto-switch with `agent-runtime$config :key \"explore-as-search\" :value \"false\"`."
- The agent registry continues to expose `#'search-agent` for one release.
- Docs in `docs/agent-tui-app/` updated to point at explore-agent.

### Phase 3 — Hard-deprecate

- Remove `search_agent.clj` source and the registration. `bases/agent-tui` and `bases/agent-web` strip any references.
- Search references in tests; rename to `explore-agent`.
- The `.brainyard/agents/search-agent/results/` directory (if it ever existed — search-agent doesn't actually write there today) is left alone in user repos; explore-agent does NOT migrate or read from it.
- Release note: "search-agent removed in vX.Y. Use explore-agent."

### Phase Acceptance Gates

| Phase → Phase | Gate |
|---|---|
| 0 → 1 | All explore-agent tests green; smoke test on a 4-surface mixed question passes locally. |
| 1 → 2 | Benchmark: explore-agent ≥ search-agent on filesystem-only questions; explore-agent has > 2 weeks of TUI runtime with no escalation tickets tagged `agent:explore`. |
| 2 → 3 | At least one minor release has shipped with the deprecation log; no callers still pinning `search-agent`. |

The phases are numbered for sequencing only — there is no fixed schedule. Treat each gate as a hard prerequisite.

---

## 13. Verification

`docs/RLM-BENCHMARK.md` already established the benchmark harness shape. Add benchmark cases targeting explore-agent's specific contract:

| Benchmark | Shape | What it verifies |
|---|---|---|
| Filesystem-only Q (carry-over) | Same as search-agent's existing test corpus | No regression vs. search-agent. |
| MCP discovery | "What X-MCP-server tools exist? Health?" | Surface-C routing is reached without prompting. |
| Skills discovery | "Find me a skill that does X." | Surface-D routing works; skill$find is preferred over `search` for skill questions. |
| Mixed (A+C) | "Where is config for service X both in code AND in the running MCP server?" | Parallel probe across A and C is used in iteration 1. |
| Trivial bypass | "What's the capital of France?" | Persistence is SKIPPED (Q < 1000 chars, zero entities). |
| Persistence integrity | Re-run any non-trivial Q twice | Two distinct timestamped files in `results/`; both indexed; same slug, suffixed `-2`. |
| Frontmatter round-trip | Persist → `explore$read-frontmatter` → assert keys | Downstream-handoff contract holds. |
| Conservative MCP write | Q implying a write op (e.g. "create a Linear issue for …") | Agent stops at the proposal, asks for confirm in `answer` — does NOT call `mcp$tools :op :call`. |
| Index append-only | Append 100 entries | INDEX.md has 100 lines, newest first; no entries lost or rewritten. |

Per-iteration mulog signals to add (mirroring the `::rlm.*` pattern):

- `::explore.probe` — `{:surfaces […] :elapsed-ms N}`
- `::explore.persist` — `{:slug … :path … :bytes N :surfaces […] :entities-count N}`
- `::explore.skip-persist` — `{:reason :trivial :answer-chars N}`

These are `mulog/log` calls in the helpers (§10) — no agent-loop changes required.

---

## 14. Files Summary

| File | What changes |
|---|---|
| `components/agent/src/ai/brainyard/agent/common/explore_agent.clj` | NEW — `instruction`, `tool-context`, `defagent explore-agent` mirroring `search-agent` shape; uses `coact/run-coact-derived`. |
| `components/agent/src/ai/brainyard/agent/common/explore.clj` (optional) | NEW — `explore$slug`, `explore$frontmatter`, `explore$write`, `explore$index-append`, `explore$read-frontmatter`, `explore$find` as `defcommand`s. |
| `components/agent/test/ai/brainyard/agent/explore_agent_test.clj` | NEW — registration smoke test, frontmatter round-trip, persistence threshold check, INDEX.md append-only check. |
| `.brainyard/agents/explore-agent/README.md` | NEW (templated by the helpers on first write) — directory layout cheat-sheet. |
| `bases/agent-tui` / `bases/agent-web` | NO CHANGES required at Phase 0/1. Update at Phase 3 to drop search-agent references. |
| `bb.edn` | OPTIONAL — `repl:explore` task mirroring `repl:component`. |
| `docs/explore-agent-design.md` | THIS FILE. |
| `components/agent/src/ai/brainyard/agent/common/search_agent.clj` | TOUCHED only at Phases 2 and 3 (deprecation log, then deletion). |
| `components/agent/src/ai/brainyard/agent/common/coact_agent.clj` | NO CHANGES. |

The whole feature ships as one new agent file (and one optional helpers file). Substrate, BT, sandbox, DSPy signature — untouched.

---

## 15. Open Questions

1. **Should `explore-agent` consult `INDEX.md` before exploring?** A "have we already answered this?" check at iteration 0 would avoid duplicate work. Trade-off: extra iteration cost + risk of returning a stale answer when the underlying data has shifted (especially MCP / web). Suggestion: opt-in via `agent-runtime$config :key "explore-prefer-cache" :value "true"` rather than default-on.
2. **Frontmatter as YAML vs. EDN?** YAML is more accessible to non-Clojure consumers (humans, other tooling). EDN is native to brainyard. The proposal is YAML for ergonomics — Clojure can parse it via `clj-yaml` if a downstream agent needs to. Reconsider if YAML parsing becomes a liability.
3. **Do we need a `.brainyard/agents/explore-agent/results/` retention policy?** Six months of weekly runs at 5 explorations/day is ~1100 files. Disk-cheap but listing-noisy. Suggestion: a `bb explore:archive` task that moves results older than 90 days into `archive/<YYYY-MM>/` subdirs. Out of scope for the agent itself.
4. **Should write-side MCP confirmation live in the agent or in a dispatch-time hook?** Right now the instruction tells the agent to stop and ask. A `:agent.tool-use/pre` hook on `mcp$tools :op :call` could enforce it mechanically — refusing the call when the tool description mentions write verbs. Stronger guarantee, but couples explore-agent's safety contract to the global hook registry. Consider in a follow-up if the prompt-only enforcement proves leaky.
5. **Should explore-agent call rlm-agent for fan-out work?** Currently Hard Rule 1 forbids `query$clone`. A user question like "explore what every team is shipping next quarter — search across Linear, Slack, and the planning docs" is genuinely MapReduce-shaped over MCP results and would benefit from rlm-agent's batched `query$llm`. The clean answer: explore-agent does the *enumeration*, persists the raw findings, and emits `Saved exploration: …`; the dispatcher (or the user) then invokes rlm-agent with that path. Keeps both agents flat. Worth calling out in the instruction so the agent doesn't try to do MapReduce inline.
