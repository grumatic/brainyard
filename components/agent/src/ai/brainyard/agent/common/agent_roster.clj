;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.common.agent-roster
  "Shared default tool roster for the general-purpose agents (coact, react).

   Both agents advertise the SAME curated roster — all common deftool tools
   plus all common commands. It used to be duplicated byte-for-byte at each
   defagent's `:agent-tools` site; defined once here so it cannot drift.

   The two agents differ only in how they RENDER this roster, not in its
   membership:
     - coact reaches every tool through the SCI sandbox + hot-path primitives,
       so it renders the roster compactly (`:compact-agent-tools`) — the roster
       only scopes the category index and names the tools.
     - react has no code channel; this roster IS its advertised tool surface,
       so it renders verbose per-tool specs.

   `:agent-tools` is evaluated at defagent load time (NOT a macro-literal — only
   :type/:input-schema/:output-schema are parsed at macro-expand time), so the
   defagents reference `default-agent-roster` by symbol. This ns is required by
   both agents, which makes the native-image class-init order deterministic:
   it (and transitively common.tools / common.commands) loads before either
   agent's `def` runs."
  (:require [ai.brainyard.agent.common.tools :as common-tools]
            [ai.brainyard.agent.common.commands :as common-cmds]
            [ai.brainyard.agent.common.skills :as skills]
            [ai.brainyard.agent.mcp.commands :as mcp-cmds]
            [clojure.string :as str]))

(def default-agent-roster
  "Shared coact/react `:agent-tools` value: all common deftool tools + all
   common commands + the skills READ subset (the skill substrate's USE half) +
   the MCP command family (mcp$server/tools/lifecycle — the MCP substrate's USE
   half), de-duped. Shape matches the defagent `:agent-tools` slot
   (`{:tools [...]}`). The skills WRITE subset stays on skill-agent only; MCP
   side-effect safety is the tool-permission layer, not the roster."
  {:tools (vec (distinct (concat common-tools/all-common-tools
                                 common-cmds/all-common-commands
                                 skills/skills-read-subset
                                 mcp-cmds/all-mcp-commands)))})

(def todo-substrate-protocol
  "A `## Todo substrate` system-context section, installed in BOTH base agents
   (coact + react) so every derived agent inherits the checklist convention —
   modeled on coact's Project Memory protocol. The tools it references
   (write-file/update-file/read-file + todo$sync) already ride
   `default-agent-roster`, so this is guidance only, no roster change.

   The point: a todo is a markdown checklist, manageable inline with file tools
   — no todo-agent dispatch needed for routine 'what I'm doing now' work."
  "## Todo substrate (a todo is a markdown checklist)
A todo is a GitHub-style checklist file — your own \"what I'm doing now\"
scratchpad to stay on track, OR a durable cross-agent backlog. Manage it with
the ordinary file tools; `.brainyard/` writes never prompt for permission.

- CREATE: write-file a todo file. Frontmatter needs `file-type: todo` + `id` +
  `title`; the body is a `## Todo` section of `- [ ] <action> {via:…, covers:[…]}`
  lines. (Working checklists: keep them under your own agent dir, e.g.
  `.brainyard/agents/<agent>/todos/<slug>.md`.)
- FLIP done: update-file \"- [ ] <unique text>\" → \"- [x] <unique text>\". Match on
  the line TEXT — never a drifting numeric index, which breaks after an insert.
- ADD: write-file :append a `- [ ] …` line under `## Todo`, or update-file after
  an anchor line.
- RECONCILE: after ANY checklist edit, call `todo$sync` once — it re-parses the
  checklist and refreshes the live TUI/web view. READ-ONLY. For a working
  checklist under your own dir, pass the file path:
  `(todo$sync {:path \".brainyard/agents/<agent>/todos/<slug>.md\"})`. (A contract
  todo under todo-agent/todos/ can use `(todo$sync {:slug \"<slug>\"})`.)
- LIST: `(doc$list {:kind :todo})` to enumerate.

Manage working checklists yourself, inline — no todo-agent dispatch. Reserve
todo-agent for a VETTED, plan-derived, AUDITED contract backlog: it adds
pre/post-flight gating + a dossier handoff that exec-agent/eval-agent consume.")

(def exec-substrate-protocol
  "An `## Executing a checklist` system-context section, installed in BOTH base
   agents (coact + react) so any agent can DO a checklist item — not just track
   it — with evidence and safe writes, no exec-agent dispatch. Layers the
   execution discipline (route → verify → record → flip) over the todo
   substrate's checklist editing. Tools referenced (edit-agent, bash, mcp$tools,
   update-file, todo$sync) already ride default-agent-roster."
  "## Executing a checklist (exec substrate)
To DO a checklist item (not just track it), follow route → verify → record → flip:

1. ROUTE — decide how the item gets done:
   • source edit  → DELEGATE to edit-agent: (edit-agent {:question \"<item>\"
                    :agent-context \"<context>\" :dirty-ok? \"false\"}). It diffs,
                    verifies, and returns `Saved edit: <path>` + `Rollback: <cmd>`.
                    Prefer this over raw write-file for tracked source — you get a
                    reversible, verified edit.
   • shell check  → (bash {:command \"<cmd>\"}); ok = exit 0.
   • external/MCP → (mcp$tools …); reads proceed, writes need user confirm.
   • lookup       → read-file / grep / query$llm; keep a short evidence excerpt.
   • manual       → STOP, surface it, do NOT flip.

2. VERIFY — confirm it actually worked (edit-agent diff_match, exit 0, recorded excerpt).
3. RECORD — note the evidence (edit path + rollback, command + exit, excerpt).
4. FLIP — only after VERIFY passes: update-file \"- [ ] <text>\" → \"- [x] <text>\"
   on the checklist (match line TEXT, never an index), then todo$sync to
   reconcile progress + refresh the TUI.

RULES:
- NEVER flip a box without supporting evidence (step 2).
- Prefer edit-agent for tracked-source writes (reversible + verified).
- Bound your work: a few items per turn, then summarize and continue.

This is working execution — yours to run inline. Reserve exec-agent for CONTRACT
execution: gated on passed plan+todo dossiers, bounded, and audited with an
evidence dossier that eval-agent consumes.")

(def skill-substrate-protocol
  "A `## Using a skill` system-context section, installed in BOTH base agents
   (coact + react) so the WHOLE fleet can USE skills — not just explore-agent
   (read) and skill-agent (lifecycle). Paired with the skills READ subset on
   `default-agent-roster` (skills$find/read/list/reload). USE is read-only;
   lifecycle (create/update/install) stays skill-agent's. Cousin of the Project
   Memory protocol — both are 'consult the store before reinventing' (skills =
   procedures, memory = facts). Modeled on `project-memory-protocol`."
  "## Using a skill (skill substrate)
Skills are reusable, named procedures (a SKILL.md of imperative steps, sometimes
with helper scripts). Before reinventing a multi-step procedure, check for one:

1. DISCOVER — `(skills$find {:query \"<key nouns of the task>\"})`. Also
   `(skills$list)` to browse. Skills span backends: :brainyard (local), :claude,
   :agents.
2. READ — `(skills$read {:skill-name \"<name>\"})` for the SKILL.md + its path.
3. FOLLOW — do what the SKILL.md says, in your own iterations: run its
   `scripts/<…>` via bash, read its `resources/<…>`, follow its imperative steps.
   If the skill is registered as a callable tool (`:skill$<name>`), you may
   instead invoke it by name and let it drive — its SKILL.md rides in as context.

RULES:
- Prefer an existing skill over hand-rolling the same steps — but only when a
  genuine multi-step procedure is involved; don't `skills$find` on every turn.
- USE is READ-ONLY: never create/update/install a skill inline — that's
  skill-agent's job. If no skill fits and one clearly should exist, finish the
  task, then suggest routing to skill-agent to capture it.
- CITE the skill you used (`skill:<backend>:<name>`) so the trail is auditable.")

(def mcp-substrate-protocol
  "A `## Using MCP servers` system-context section, installed in BOTH base agents
   (coact + react) so the WHOLE fleet can USE configured MCP servers (external
   tools/resources/prompts — Linear, Slack, GitHub, …) — not just explore/exec/
   mcp-agent. Paired with the MCP command family on `default-agent-roster`
   (mcp$server/tools/lifecycle). USE is discover→inspect→invoke; the heavier
   management (:stop/:restart, troubleshooting, multi-server flows) stays
   mcp-agent's. Cousin of the skill substrate (both are discover→use).

   NOTE: MCP calls can have external SIDE EFFECTS. The safety boundary is the
   fail-closed permission gate in `mcp/permission.clj` (a `:agent.tool-use/pre`
   hook covering BOTH call paths — native `mcp$<server>$<tool>` bindings and the
   `mcp$tools :op :call` proxy): a side-effecting MCP call requires approval
   through the same UI as write-file/bash, honoring `[:permissions :mode]`.
   readOnlyHint tools and `:mcp-allow-tools`-matched tools are auto-allowed. The
   prose below reinforces this: surface external actions and don't assume a
   prior approval carries forward."
  "## Using MCP servers (mcp substrate)
Configured MCP servers expose external tools / resources / prompts (Linear, Slack,
Jira, GitHub, Notion, …). When a task needs external data or an external action,
use them:

1. DISCOVER — `(mcp$server :op :list)`. If the relevant server is `:connected
   false`, `(mcp$lifecycle :op :start :server-name \"<s>\")` to connect it.
   (`:stop` / `:restart` and troubleshooting a flaky server → route to mcp-agent.)
2. INSPECT — `(mcp$tools :op :list :server-name \"<s>\")` to learn the native
   tool-name + input-schema. NEVER invent server / tool names or arg keys.
3. INVOKE — call the native tool, EITHER via the proxy `(mcp$tools :op :call
   :tool-calls [{:server-name \"<s>\" :tool-name \"<native>\" :tool-args {…per
   schema…}}])` OR by its registered binding `mcp$<server>$<tool>`. Resources via
   `:read-resource`, prompts via `:get-prompt`. Cite `mcp:<server>:<tool>`.

RULES:
- INSPECT before INVOKE — never guess a tool's args; read the input-schema.
- A call that changes EXTERNAL state (create / update / post / send / delete) is a
  deliberate side effect: surface what you're about to do, and do NOT assume a
  prior approval carries to a new external call.
- USE covers discover / inspect / invoke / `:start`. Heavier management
  (`:stop`/`:restart`, troubleshooting, complex multi-server flows) → mcp-agent.")

(def subagent-substrate-protocol
  "A `## Persistent subagents` system-context section, installed in BOTH base
   agents (coact + react) so the whole fleet knows how to keep a dispatched
   subagent alive (`:keep-alive?`) and manage it via the `agent-registry$*`
   family. The tools it references ride `default-agent-roster` (the
   agent-registry$* commands) and every subagent tool advertises `:keep-alive?`
   via the defagent macro — so this is guidance only, no roster change. See
   docs/design/agent-lifecycle-management.md."
  "## Persistent subagents (agent lifecycle substrate)
By default a subagent you dispatch (explore-agent, exec-agent, …) is EPHEMERAL:
it runs once, answers, and is closed — a later call spawns a FRESH instance that
starts from zero (no memory of the last one). To keep one alive for multi-turn
work on the same context, pass `:keep-alive? true` and reuse it by id.

1. KEEP ALIVE — add `:keep-alive? true` when you expect follow-ups:
   `(explore-agent {:question \"map the auth module\" :keep-alive? true})`
   The result carries `:subagent-id \"explore-agent/<suffix>\"` + a `:resume-hint`.
   CAPTURE that id — it is the ONLY handle for resuming; a fresh dispatch is a
   different instance.
2. RESUME — follow up on the SAME instance (it still sees its ## Previous Turns):
   `(agent-registry$resume {:id \"explore-agent/<suffix>\" :question \"now check token refresh\"})`
3. LIST / INSPECT — `(agent-registry$list)` shows live instances with `:mode`,
   `:owner`, `:idle-ms`, `:last-question`; `(agent-registry$detail {:id \"…\"})`
   gives status + last answer + `:reap-eligible?`.
4. CLOSE — when done, free it: `(agent-registry$close {:id \"…\"})`. Idle
   persistent subagents are also reaped automatically (`agent-registry$sweep`).

RULES:
- Default to EPHEMERAL (omit `:keep-alive?`). Keep one alive ONLY when you
  genuinely need multi-turn follow-up with the same subagent's built-up context.
- Resume/close only an instance you dispatched, and only when it is `:idle` — a
  `:running` one is busy (poll `agent-registry$detail`, or `task$wait` if it was
  detached). Do not close it on a quiet-but-growing idle window alone.
- Per-session cap (`:max-persistent-agents`): at the cap a `:keep-alive?`
  dispatch falls back to ephemeral (see `:agent-cap-note` in the result) — close
  idle ones you no longer need, then retry.")

(def project-memory-protocol
  "Shared `## Project Memory` protocol prose, installed in BOTH base agents
   (coact + react) so every derived agent gets it — paired with
   `format-project-memory-section` which appends the live index. Single source
   of truth (coact + react alias it)."
  "Durable, project-scoped notes for THIS repo, kept as plain files under
`.brainyard/memory/` and persisting across sessions. The index below lists what
is stored; each entry points to a colocated `<slug>.md` topic file. You manage
these with the ordinary read-file / write-file / update-file tools — no special
tools, and `.brainyard/` writes never prompt for permission.

- RECALL: when a listed topic is relevant to the request, read its file
  (`read-file .brainyard/memory/<slug>.md`) BEFORE answering.
- REMEMBER: when you learn a durable project fact, decision, or convention worth
  keeping, write `.brainyard/memory/<slug>.md` (short YAML frontmatter —
  `title`, `tags`, `updated` — then the fact; link related notes with
  `[[other-slug]]`), and add or update its one-line pointer in
  `.brainyard/memory/index.md` (`- [Title](<slug>.md) — one-line hook`).
- One fact per file. Check the index first and UPDATE an existing file rather
  than creating a duplicate; delete a note that turns out wrong.
- Do NOT store transient task state, or anything already captured by the code,
  git history, or BRAINYARD.md.")

(defn format-project-memory-section
  "Render the `## Project Memory` system-context section: the static protocol
   followed by the live index.md contents (truncated to `max-chars`), or an
   empty-state stub when no index exists yet."
  [{:keys [content max-chars]}]
  (let [cap   (or max-chars 4000)
        idx   (when (and content (not (str/blank? content)))
                (if (> (count content) cap)
                  (str (subs content 0 cap)
                       "\n…(index truncated — read .brainyard/memory/index.md in full)")
                  content))
        body  (if idx
                (str "### Index\n" idx)
                "### Index\n(empty — no memories yet. Create `.brainyard/memory/index.md` with your first note.)")]
    (str "## Project Memory (.brainyard/memory/)\n"
         project-memory-protocol
         "\n\n" body)))
