# Init-Agent — BRAINYARD.md Authoring and Maintenance (CoAct-derived)

> **Status:** Shipped — `init-agent` is registered in `components/agent` (`common/init_agent.clj`). This document is the original design proposal (revision 1 — draft); the shipped implementation may diverge in details. See [core/agent.md](../core/agent.md) for the current roster.
> **Scope:** new `components/agent/src/ai/brainyard/agent/common/init_agent.clj` and a thin command namespace `components/agent/src/ai/brainyard/agent/common/init.clj`
> **Built on:** `coact_agent.clj` via `coact/run-coact-derived`
> **Sibling of:** `config-agent` (config.edn), `update-agent` (safe file edits — used here for the actual write), `explore-agent` (read-mostly discovery — used for project sniffing)
> **Related reading:** `docs/design/config-agent-design.md`, `docs/design/update-agent-design.md`, `components/agent/src/ai/brainyard/agent/core/config.clj` (`load-brainyard-instructions`, `user-config-dir`, `project-config-dir`), `components/agent/src/ai/brainyard/agent/common/coact_agent.clj` (`coact-user-context`)

---

## 1. Motivation

`BRAINYARD.md` is the **base user-context file** that every brainyard agent reads. The plumbing already exists:

- `agent.core.config/load-brainyard-instructions` reads two files — `<project>/.brainyard/BRAINYARD.md` and `~/.brainyard/BRAINYARD.md` — and returns them as `{:user-instructions :project-instructions}`.
- `coact_agent.clj`'s `coact-user-context` (lines 778–797) folds both into the agent's `:user-context` block on every turn, before the conversation history. Every CoAct-derived agent (coact, explore, plan, todo, exec, eval, update, research, mcp, skill, config-agent) inherits this for free.

What's missing is the **authoring loop**. Today:

1. **There is no `/init` slash command.** New users land in the TUI with no BRAINYARD.md, no prompt to create one, and no path to one except "open the file in vim." The empty-file branch in `load-brainyard-instructions` returns `nil`, so the agent silently runs without any project- or user-specific context — and the user has no idea this is happening.
2. **There is no maintenance path.** Users can edit the file by hand, but every other persistent file in the project (`config.edn`, `MEMORY.md`, plan dossiers, todo dossiers) has an agent that owns it. BRAINYARD.md is the odd one out — the most important user-context artifact in the system has the worst editing UX.
3. **Sibling tools have already solved the seed problem differently.** Claude Code writes a project-level `CLAUDE.md`. Codex / Cursor / Aider / others write `AGENTS.md`. Both files contain exactly the kind of content BRAINYARD.md needs — the project's build commands, conventions, architecture notes, and house rules. Today a brainyard user with an existing `CLAUDE.md` (every repo using Claude Code) or `AGENTS.md` (every repo using Codex) has to manually retype that content into BRAINYARD.md, or live with a thinner context than their other tools enjoy. This is friction that should not exist.
4. **Other agents can't keep BRAINYARD.md up to date.** When `coact-agent` learns something important mid-session — "we use Polylith, top namespace `ai.brainyard`", "tests live under `components/<name>/test/`", "don't commit on Fridays" — it has no path to persist that into the user-context for future sessions. The learning evaporates at session end. (Memory partly fills this gap, but memory is recall-shaped, not always-on-context-shaped.)

**Thesis.** Add a CoAct-derived `init-agent` that:

1. **Owns BRAINYARD.md** at both scopes (project: `<project>/.brainyard/BRAINYARD.md`, user: `~/.brainyard/BRAINYARD.md`). All authoring/updating goes through it; the slash command, other agents, and follow-up turns all converge on one writer.
2. **Seeds from `CLAUDE.md` and `AGENTS.md`** (project and user scope) when BRAINYARD.md doesn't exist yet. The first-time experience is "I noticed CLAUDE.md in your repo — should I bring it forward?" rather than a blank file.
3. **Is exposed via `/init`** in the TUI (interactive slash command) AND callable directly as `(init-agent {…})` from any other agent's sandbox (every `defagent` registers as a callable in the SCI sandbox; no `call-tool` wrapper needed). The same engine drives both — the `/init` handler just spawns init-agent with the user's args as the question.
4. **Treats every write as a transaction** — snapshot, diff, confirm, smoke-validate (markdown-parses cleanly + sections retained), dossier. Same discipline as `config-agent`: BRAINYARD.md is too load-bearing to overwrite without a revert path.
5. **Inherits the CoAct loop** from `coact-agent` via `run-coact-derived`. No new BT, no new DSPy signature.

Minimal-diff, same pattern as the rest of the specialists.

---

## 2. Design Principles

1. **One writer.** Every change to BRAINYARD.md goes through `init$apply` (the transactional write in §6). Slash command, other-agent calls, "edit by hand then ask agent to re-format" — all funnel here. Direct file overwrites by other code are a bug.
2. **Two scopes, never merged on disk.** Project BRAINYARD.md and user BRAINYARD.md stay as two distinct files. `load-brainyard-instructions` already presents them as two named sections in the agent's user-context — init-agent preserves that separation. A user-scope change is never written into the project file or vice-versa.
3. **Seed once, maintain forever.** Source ingestion (CLAUDE.md, AGENTS.md) is a *one-time* operation gated on "BRAINYARD.md missing or empty at this scope" or an explicit `--reseed` flag. After that, BRAINYARD.md is the source of truth at each scope; init-agent does not re-read CLAUDE.md / AGENTS.md on every turn.
4. **Append-and-curate, not overwrite.** When the user (or a calling agent) says "remember we use Polylith," the default action is `:append` to the relevant section — not a wholesale rewrite. Section-level curation is the LLM's job; the section model is light (§4.3).
5. **Snapshot before every write — per-scope artifact dirs.** Project BRAINYARD.md edits snapshot under `<repo>/.brainyard/agents/init-agent/snapshots/<ts>-project-<reason>.md`; user edits under `~/.brainyard/agents/init-agent/snapshots/<ts>-user-<reason>.md`. Last 20 retained per scope. Revert is `init$revert :snapshot-path P` (same UX as config-agent's snapshots). See §2A for the broader scope contract.
6. **Other agents can call but cannot bypass.** When `coact-agent` calls `init-agent` mid-session ("persist this insight"), the call goes through the same read → propose → diff → apply pipeline. Other agents do NOT get a privileged "skip confirmation" path. In `--auto`/non-interactive contexts the confirmation is an in-band rule (§7.4) rather than a TTY prompt.
7. **BRAINYARD.md stays readable to humans.** No EDN, no fenced agent-only blobs. The file is a plain markdown document a teammate could open in any editor and understand. Init-agent enforces this by keeping the section model human-shaped and refusing to write structural markers (HTML comments excepted) that break casual reading.
8. **Treat CLAUDE.md / AGENTS.md as drafts, not specs.** When seeding, init-agent *summarises and reorganises* — it does not copy. The seeded BRAINYARD.md is a brainyard-shaped reflection of the source, with sections in the order init-agent uses (§4.3), the same content normalised for the brainyard agent's prompt budget.
9. **No clone-self recursion.** `query$clone` is excluded (matches the other specialists). Cross-agent dispatch happens via the direct callable form — every `defagent` is auto-bound into the sandbox as a kebab-case function, so `(plan-agent {…})` / `(explore-agent {…})` / `(update-agent {…})` are the call sites, not `query$clone` and not a `call-tool` wrapper.
10. **Honest about silence, but still useful.** If BRAINYARD.md is missing AND no CLAUDE.md / AGENTS.md is found AND the user said nothing more than `/init`, init-agent says so plainly and asks the user whether to proceed by exploring the project directory itself. On confirmation, it dispatches `explore-agent` to sniff the repo (top-level files, `deps.edn` / `package.json` / `Cargo.toml` / `pyproject.toml`, top-level directories, `README.md` if present) and synthesises a first-draft BRAINYARD.md anchored on what it observed plus the §4.3 section template. It NEVER invents project facts not present in the explore output; gaps in the draft are left as TODO placeholders the user fills in. If the user declines, init-agent exits without writing.

---

## 2A. Scope — Project vs User

BRAINYARD.md exists at **two scopes**, both consumed by every agent's user-context:

| Scope | Path | Resolution | What lives here |
|---|---|---|---|
| **Project** | `<repo>/.brainyard/BRAINYARD.md` | `BRAINYARD_PROJECT_DIR` env → nearest `.git` ancestor → **working-dir fallback** | Repo-specific: build commands, conventions, architecture notes, house rules for THIS codebase |
| **User** | `~/.brainyard/BRAINYARD.md` | `System/getProperty "user.home"` | Cross-project: personal coding preferences, global tooling notes, glossary terms you reuse |

**No auto-merge.** `load-brainyard-instructions` returns both as a `{:user-instructions :project-instructions}` map; `coact-user-context` renders them as two labelled sections. The runtime never merges them — they are independent files.

**Every init$ command takes `:scope`:**

```
(init$read :scope :project|:user|:both)
(init$diff :scope :project|:user :body …)
(init$snapshot :scope :project|:user :reason …)
(init$list-snapshots :scope :project|:user|:both)
(init$revert :scope :project|:user :steps N)
(init$apply :scope :project|:user :op … :body … :reason …)
```

`init$list-snapshots :scope :both` reads from BOTH scope dirs and merges the results, newest-first. `init$revert` resolves its pre-revert snapshot in the same scope dir as the snapshot being restored — cross-scope reverts are not possible (a `:project` snapshot only restores into the project BRAINYARD.md).

**Per-scope snapshot, dossier, and INDEX history.** Identical to config-agent's per-scope split: project edits anchor under `<repo>/.brainyard/agents/init-agent/`, user edits under `~/.brainyard/agents/init-agent/`. Each scope has independent rotation and its own INDEX.md.

**`init-agent/` is one of two exceptions to the broader scope contract.** `agent.core.config/subdir-scope-policy` makes most `*-agent/` dirs project-only; `init-agent/` and `config-agent/` are the exceptions because the files they edit (BRAINYARD.md and config.edn) are themselves dual-scope. See [architecture.md](../architecture.md) for the full subdir scope table.

**Wizard / bootstrap compatibility.** The bootstrap wizard and `bootstrap$re-run-rung` do not interact with BRAINYARD.md — init-agent is the only writer. Auto-scope rules for `/init` invocation are in §4.2.

---

## 3. Position in the Agent Stack

```
   coact-agent  (parent — full BT, sandbox, router, accumulator)
     │  every CoAct-derived agent reads BRAINYARD.md via coact-user-context
     │
     ├─ explore-agent       (read-mostly discovery; called by init-agent for repo sniffing)
     ├─ update-agent        (safe file edits; THE WRITER underneath init$apply)
     ├─ config-agent        (config.edn; sibling — same snapshot/dossier idiom)
     ├─ plan-agent / todo-agent / exec-agent / eval-agent
     └─ init-agent          (THIS — BRAINYARD.md authoring + maintenance)
                                 ▲
                                 │  invoked via:
                                 │    /init <prompt>                          (TUI slash command)
                                 │    (init-agent {:question Q})              (any agent's sandbox — direct call)
                                 │    bb tui run -a init-agent                (direct entry — rare)
```

Init-agent **uses** update-agent for the actual file write (so the safe-edit pipeline — diff, pattern-match safety, git status check — is exercised). It **uses** explore-agent for project sniffing during seed (read `deps.edn`, `package.json`, `Cargo.toml`, top-level dirs, `README.md`). It is **used by** every other agent that wants to persist a learning into the always-on user-context. The CoAct loop, sandbox, BT, and DSPy signatures are untouched.

---

## 4. The BRAINYARD.md Contract

### 4.1 Locations (already established)

| Scope | Path | When init-agent edits | Who else reads |
|---|---|---|---|
| Project | `<project>/.brainyard/BRAINYARD.md` | `--scope :project` (default) or autodetect | every agent via `load-brainyard-instructions` |
| User | `~/.brainyard/BRAINYARD.md` | `--scope :user` | every agent, across all projects |

`load-brainyard-instructions` (in `agent.core.config`) is unchanged. It already returns both as `{:user-instructions :project-instructions}` and `coact-user-context` already renders them as two labelled sections (`## Project Instructions (.brainyard/BRAINYARD.md)` and `## User Instructions (~/.brainyard/BRAINYARD.md)`). Init-agent never edits these readers — only the files behind them.

### 4.2 Auto-scope rules

When the user gives no `--scope`:

- `/init` from inside a project directory with a `.brainyard/` dir → project scope.
- `/init` from outside a project (no `.brainyard/`) OR the user passes `--global` → user scope.
- `(init-agent …)` called from another agent's sandbox → mirrors the calling agent's `:dirs` map (so the agent running in a project edits the project file; the user-config-only case edits the user file). This keeps "the agent's view of BRAINYARD.md" and "init-agent's write target" symmetric.
- Ambiguous → ask once.

### 4.3 Section model

BRAINYARD.md is plain markdown with a fixed-ish set of top-level sections. Init-agent recognises the following section headings (case-insensitive, leading `## `):

```
## Overview              — what this project is, in 2–4 lines
## Build & Run           — how to build, test, REPL, deploy (commands, not prose)
## Conventions           — coding rules, naming, file layout, style preferences
## Architecture          — components, key abstractions, "why"
## House Rules           — things to always or never do (e.g. "don't commit on Fridays")
## Tooling               — required executables, MCP servers, integrations
## Glossary              — project-local terms agents should know
## Notes                 — anything else; init-agent never auto-edits this section
```

The model is **light**: any of these may be missing, sections may appear in any order, and **`## Notes` is always preserved untouched** so users have a safe scratchpad init-agent will leave alone. Unknown sections are also preserved (init-agent appends rather than reformatting unfamiliar structures).

For the user-scope file, the recommended sections are a subset (`## Conventions`, `## House Rules`, `## Tooling`, `## Glossary`, `## Notes`) — the user-scope file is about *you*, not about a project. Init-agent's instruction biases section choice accordingly.

### 4.4 Size budget

Both files are loaded on every turn into every agent's user-context. Each is hard-capped at **8 KB** in init-agent's writer; the soft target is **4 KB** at project scope, **2 KB** at user scope. If a proposed write would exceed the hard cap, init-agent refuses and proposes a curation pass (`init$apply :op :curate`) that summarises sections rather than appending.

This budget is enforced in `init$apply` (a write that would push the file past 8 KB returns `{:error :budget-exceeded :size N :cap 8192}`) and surfaced in the instruction.

---

## 5. Capability Surface

Five kinds of conversation init-agent must handle.

1. **Initialize.** "No BRAINYARD.md yet — seed one." Detection-phase: search project for `CLAUDE.md`, `AGENTS.md`, `~/.claude/CLAUDE.md`, `~/.codex/AGENTS.md`, optionally `README.md`. If any source is found, propose a draft per §8. If none are found, ask the user whether to proceed by exploring the project directory; on confirmation dispatch `explore-agent` to gather the project's top-level layout, build files (`deps.edn` / `package.json` / `Cargo.toml` / etc.), and `README.md` if present, then synthesise a first draft from those observations plus the §4.3 section template. Either path: diff → confirm → write.
2. **Append a guide.** "Remember that we use Polylith." "Always run `bb test` before pushing." Determine target section (§4.3); propose a minimal append; diff → confirm → write.
3. **Curate / shrink.** "Trim this — it's getting long." Read the file, summarise per-section, propose a curation diff. The user retains the right to reject any individual change. Same shape as a normal write transaction.
4. **Re-seed from source.** "I just ran Claude Code's `/init` — bring those changes forward." Re-read CLAUDE.md / AGENTS.md; diff against current BRAINYARD.md; surface what's new. User opts each chunk in or out.
5. **Show / explain.** "What's in my BRAINYARD.md?" "Why does the agent think I use Polylith?" Read and answer. Useful as a debugging surface — when an agent does something surprising and the user wonders if BRAINYARD.md is to blame, init-agent's lookup is the way to confirm.

What init-agent must NOT do:
- Edit any file other than BRAINYARD.md (and snapshots / dossiers under `.brainyard/agents/init-agent/`).
- Edit `CLAUDE.md` or `AGENTS.md` — those are sibling-tool files, read-only here.
- Write outside `.brainyard/` directories.
- Mirror BRAINYARD.md back into CLAUDE.md / AGENTS.md (one-way ingest only — bidirectional sync is a future feature; see §15).

---

## 6. Tool Roster

Inherited from the shared registry:

| Tool | Used for | Notes |
|---|---|---|
| `read-file` / `grep` | Reading BRAINYARD.md, CLAUDE.md, AGENTS.md, deps.edn, package.json, README.md | Standard sandbox surface. |
| `bash` (allowlisted) | `ls .brainyard/`, `git rev-parse --show-toplevel`, `cat -A` for whitespace-inspection | Read-only allowlist. |
| `query$llm` | Section-level summarisation, "is this an append or an overwrite?" classification | Single-step; no recursion. |
| `(update-agent {…})` | The actual write to BRAINYARD.md (pattern-replace for appends, full rewrite for curate) | Direct sandbox callable; same safe-edit pipeline as elsewhere — diff, git status check, snapshot. |
| `(explore-agent {…})` | "What are the top-level dirs / what build tool is in use?" sniffing during seed and during the no-sources fallback (§2.10, §5.1, §12.10) | Direct sandbox callable; read-only. |

New commands (defined in `agent/common/init.clj`, registered via `defcommand`):

| Command | Polymorphic on | Effect |
|---|---|---|
| `init$read` | `{:scope :project|:user|:both}` | Returns the requested BRAINYARD.md contents (raw + parsed section map). Pure read. |
| `init$detect-sources` | `{:scope :project|:user|:both}` | Looks for CLAUDE.md / AGENTS.md / README.md at the given scope(s). Returns `[{:source :claude-md :path … :size …} …]`. Pure read. |
| `init$diff` | `{:scope :project|:user :proposed <markdown-string>}` | Returns a unified diff against the on-disk file + a section-level summary. No side effects. |
| `init$apply` | `{:scope :project|:user :op :init|:append|:curate|:reseed|:replace-section :body <md> :reason <str> :confirm? <bool>}` | The transactional write. Validates size budget → snapshot → calls `update-agent` to write → markdown-parse smoke test → dossier-append. Returns `{:ok? :snapshot-path :diff :size :dossier-path}`. |
| `init$revert` | `{:snapshot-path <path>}` or `{:scope :project|:user :steps <int>}` | Restores a prior snapshot (itself snapshots the current file first). |
| `init$list-snapshots` | `{:scope :project|:user :limit <int>?}` | Last 20 with timestamps, reasons, brief diffs. |
| `init$smoke-test` | `{:scope :project|:user}` | Re-runs `load-brainyard-instructions` against the on-disk file and asserts (a) parses, (b) fits in the agent's user-context budget, (c) hasn't grown past the 8 KB hard cap. Useful after a manual edit. |

The polymorphic `init$*` family matches the existing `config$*` / `mcp$*` / `plan$*` patterns — one keyword dispatcher per command.

---

## 7. Instruction Shape

The CoAct preamble plus six guidances specific to init-agent:

1. **Start every conversation with a read.** Always call `init$read :scope :both` and `init$detect-sources :scope :both` before proposing anything. The cost is negligible (two small file reads) and the benefit is that proposals are anchored to what's actually on disk, not what the LLM guesses is there.
2. **Pick the scope explicitly before any write.** If the user is in a project, default to `:project`. If the user said something obviously user-wide ("I prefer 4-space indents everywhere"), default to `:user`. If ambiguous, ask one question — never default silently to a scope the user didn't intend.
3. **Read → propose → diff → confirm → apply, every time.** Same discipline as config-agent. Skip the diff and you've shipped a defect.
4. **In `--auto` mode (no TTY)**, the confirmation rule is: `:op :append` writes that add < 200 chars to a known section pass without confirmation; everything else (init, curate, reseed, replace-section, large appends) requires either `:confirm? true` in the call or refuses with `{:error :auto-confirmation-required}`. This is the in-band substitute for a TTY prompt. Callers (other agents) read this and either pass `:confirm? true` (explicit, auditable) or surface the refusal to the user.
5. **Treat CLAUDE.md / AGENTS.md as drafts.** When seeding, summarise into brainyard's section model (§4.3). Do NOT copy verbatim — Claude Code's CLAUDE.md often has Claude-Code-specific guidance (slash commands, MCP setup advice, "Claude should…") that's noise in a brainyard context. Keep the project facts, drop the tool-specific framing.
6. **Hand off when the right tool is a sibling.** `update-agent` for the write itself, `explore-agent` for project sniffing. Don't reimplement either inside init-agent.

The instruction explicitly forbids:

- Editing any file outside BRAINYARD.md or `.brainyard/agents/init-agent/`.
- Writing past the 8 KB hard cap (refuse and propose `:curate`).
- Touching `## Notes` content during `:curate` or `:reseed` (preserve verbatim).
- Calling `init$apply` without a prior `init$diff` showing exactly what will land.

---

## 8. Source Ingestion (CLAUDE.md / AGENTS.md / README.md)

Triggered only on `:op :init` (no BRAINYARD.md yet) or `:op :reseed` (explicit re-import).

### 8.1 Sources, in priority order

| # | Source | Scope | Priority | Why |
|---|---|---|---|---|
| 1 | `<project>/CLAUDE.md` | project | high | Claude Code's standard; usually the richest project-local context. |
| 2 | `<project>/AGENTS.md` | project | high | Codex / Cursor / Aider standard; sibling to CLAUDE.md. |
| 3 | `<project>/README.md` | project | medium | Falls back when neither CLAUDE.md nor AGENTS.md exists. Build/run sections often map cleanly to `## Build & Run`. |
| 4 | `~/.claude/CLAUDE.md` | user | medium | User-scope Claude Code preferences. |
| 5 | `~/.codex/AGENTS.md` (or `~/.config/codex/AGENTS.md`) | user | medium | User-scope Codex preferences. |
| 6 | `~/.cursorrules` | user | low | Older Cursor convention; one-file format. |

The exact file list lives in `init-agent/sources.edn` (new) so adding sources is one place to edit, not three. Init-agent calls `init$detect-sources` which returns `{:found [{:source :claude-md :path P :size N} …] :missing [...]}` — no reads happen until the user opts in.

### 8.2 Merge strategy

For each detected source the LLM (via `query$llm`) produces a section-keyed summary:

```clojure
{:overview      "Polylith Clojure monorepo, top namespace ai.brainyard"
 :build-and-run ["bb repl" "bb test" "bb tui"]
 :conventions   ["Top namespace: ai.brainyard" "Interface namespace: interface"]
 :architecture  "..."
 :house-rules   ["Use Polylith CLI for new components"]
 :tooling       ["Datomic Pro credentials in ~/.m2/settings.xml"]
 :glossary      {}}
```

Multiple sources are merged: CLAUDE.md wins ties at project scope, the user-scope source wins at user scope. The merged map is rendered to markdown via init-agent's section model (§4.3). The user sees the result as a diff against the empty file (init) or against the existing BRAINYARD.md (reseed) and confirms.

### 8.3 What gets dropped

Init-agent's summarisation drops:

- Tool-specific framing ("Claude should…", "Codex should…", "When you start a session…").
- Slash-command lists (those are tool-specific).
- Personal prose like "I tend to…" — kept as `## House Rules` bullets if action-shaped, dropped if purely descriptive.
- Anything that's literally a duplicate of `README.md`'s first paragraph (the user-context budget is precious).

What's kept: project facts, build commands, conventions, file-layout rules, "don't" rules, glossary.

### 8.4 Smoke test after seed

After a seed write, init-agent calls `init$smoke-test :scope :<scope>` to verify the result parses cleanly and fits in budget. On failure, the seed is auto-reverted (using the snapshot that `init$apply` took before the write) and the user is told why.

---

## 9. Snapshot / Revert / Dossier

```
.brainyard/agents/init-agent/
├── snapshots/
│   ├── 20260514-201442-init-project.md           ;; pre-write snapshot
│   ├── 20260514-203117-append-polylith-note.md
│   └── …  (rotation: keep 20 per scope, oldest pruned)
├── dossiers/
│   ├── 20260514-201400-init.md                   ;; one per conversation
│   └── …
└── sources.edn                                    ;; the source priority list (§8.1)
```

**Snapshot.** Every `init$apply` and `init$revert` writes a full file snapshot first. Filename: `<yyyyMMdd-HHmmss>-<scope>-<reason>.md`. Snapshots are full copies — BRAINYARD.md is small (< 8 KB) so the simpler invariant ("snapshot is a drop-in replacement") wins over disk savings, same call as config-agent.

**Revert.** `init$revert :snapshot-path P` snapshots the current file with `reason: revert-to-<source-slug>`, then copies `P` back. The pre-revert snapshot means revert is itself reversible.

**Dossier.** One markdown file per conversation, with frontmatter:

```markdown
---
agent: init-agent
session-id: 2026-05-14-init-3b5a4
question: "/init  (seed from CLAUDE.md)"
scope: project
started: 2026-05-14T20:14:00Z
ended:   2026-05-14T20:14:42Z
brainyard-path: /Users/jane/code/brainyard/.brainyard/BRAINYARD.md
sources-detected:
  - {source: claude-md, path: /Users/jane/code/brainyard/CLAUDE.md, size: 6182}
  - {source: agents-md, path: /Users/jane/code/brainyard/AGENTS.md, size: 0}
snapshots:
  - 20260514-201442-init-project.md
writes: 1
reverts: 0
final-size: 3812
next-steps: []
---

## What I read
- init$read :scope :project → empty
- init$detect-sources :scope :project → CLAUDE.md (6.1 KB), AGENTS.md (missing)

## What changed
1. Created .brainyard/BRAINYARD.md (3.8 KB) seeded from CLAUDE.md

## Diff
```diff
+ # BRAINYARD.md (project)
+ ## Overview
+ Polylith Clojure monorepo, top namespace ai.brainyard.
+ ...
```

## Smoke test
- markdown parse: ok
- size: 3812 B (< 4 KB target, < 8 KB cap)
- load-brainyard-instructions returns project-instructions: yes
```

The dossier is the durable record — consumable by `explore-agent` ("what did init-agent do last week?") and by the user for audit.

---

## 10. Conversation Patterns (worked examples)

### 10.1 First-time seed (capability kind 1)

> **User:** `/init`

1. `init$read :scope :both` → both empty.
2. `init$detect-sources :scope :both` → project CLAUDE.md (6.1 KB), no AGENTS.md, no user-scope sources.
3. Tell the user: "No BRAINYARD.md yet. Found `CLAUDE.md` (6.1 KB) — bring it forward?" with one-line per source.
4. User: "yes."
5. `query$llm` over CLAUDE.md → section-keyed summary (§8.2).
6. Render to markdown per §4.3.
7. `init$diff :scope :project :proposed <md>` → unified diff (vs empty).
8. Show the diff to the user. They say "yes" / "drop the architecture section" / "shorter."
9. `init$apply :scope :project :op :init :body <md> :reason "init-from-claude-md" :confirm? true`.
10. Smoke test → ok.
11. Answer: "Wrote `.brainyard/BRAINYARD.md` (3.8 KB). Snapshot: `20260514-201442-init-project.md`. Active on next agent turn."

### 10.2 Append a guide (capability kind 2 — typical use)

> **User:** `/init remember we always run lint before push`

1. `init$read :scope :project` → existing file (3.8 KB).
2. Classify the prompt: "house rule, action-shaped." Target section: `## House Rules`.
3. Propose: append `- Always run lint before push (\`bb lint\` / \`clj-kondo\`).` under `## House Rules`.
4. `init$diff :scope :project :proposed <md>` → one-line addition diff.
5. Show diff.
6. User: "yes."
7. `init$apply :scope :project :op :append :body <md> :reason "house-rule-lint-before-push" :confirm? true`.
8. Answer: "Appended. Snapshot kept."

### 10.3 Called from another agent (capability kind 2, programmatic)

Inside a coact-agent conversation the LLM emits a direct sandbox call:

```clojure
(init-agent
  {:question "persist: tests live under components/<name>/test/, run with `bb test:component <name>`"
   :scope :project
   :confirm? true})
```

(`init-agent` is auto-bound into the sandbox by virtue of being a `defagent` registration — no `call-tool` wrapper required.)

1. Init-agent runs the same flow: read, classify (target `## Conventions` or `## Build & Run` — `query$llm` picks).
2. `--auto` rule (§7.4) applies: append < 200 chars → no further confirmation needed. Write goes through.
3. Returns `{:ok? true :snapshot-path … :section :build-and-run :appended-chars 84}`.
4. The calling agent surfaces the result in its own answer ("Persisted to BRAINYARD.md.").

### 10.4 Curate (capability kind 3)

> **User:** `/init trim — this is over 7 KB`

1. `init$read :scope :project` → 7.4 KB.
2. `query$llm` over the file with the curate prompt: "shrink to ≤ 4 KB, preserve `## Notes` verbatim, keep house rules whole, summarise architecture, drop duplicated tool advice."
3. Render proposed file.
4. `init$diff` → multi-section diff.
5. Show. User: "yes but keep the full glossary."
6. Re-`query$llm` with the constraint.
7. `init$apply :op :curate`.
8. Answer: "Curated. 7.4 KB → 3.6 KB. Snapshot at `…-curate.md`."

### 10.5 Reseed (capability kind 4)

> **User:** `/init reseed — I just updated CLAUDE.md`

1. `init$detect-sources :scope :project` → CLAUDE.md exists.
2. `init$read :scope :project` → existing BRAINYARD.md.
3. `query$llm` over CLAUDE.md → fresh section-keyed summary.
4. Three-way diff: current BRAINYARD.md vs proposed-from-CLAUDE.md vs the original seed (read from oldest matching snapshot).
5. Surface "new since last seed" as candidate chunks. User opts in chunk-by-chunk.
6. `init$apply :op :reseed`.
7. Answer: "Reseed merged. 4 additions, 1 update. Snapshot kept."

### 10.6 Show (capability kind 5)

> **User:** `/init show`

1. `init$read :scope :both`.
2. Render both files inline, with paths and sizes.
3. No write, no snapshot.

---

## 11. `/init` Slash Command and `(init-agent …)` Direct Call

### 11.1 TUI slash command

Added to `bases/agent-tui/.../commands.clj`'s slash dispatch (after `/effort`, before `/help`):

```
"/init"  (do (emit-command-header! input) (handle-init-command args reader) :continue)
```

`handle-init-command` is a thin wrapper:

- No args → spawn init-agent with `:question "/init"` (init-agent figures out scope, seeds or shows status).
- `<text>` → spawn init-agent with `:question text` (the agent classifies it: append vs curate vs reseed vs show).
- `show` / `read` / `list-snapshots` / `revert` → direct command shortcuts that bypass the LLM loop for free, like `/memory` already does for trivial reads.
- `--scope :user|:project|:both` flag → passed through as init-agent `:scope`.
- `--reseed` → forces `:op :reseed`.
- `--diff` → runs read + diff + render, but does NOT apply (read-only preview).

### 11.2 Help text

```
/init                  Show BRAINYARD.md status (project + user). If missing, offer to seed from CLAUDE.md / AGENTS.md.
/init <prompt>         Update BRAINYARD.md with the given instruction (e.g. "/init we use Polylith").
/init show             Read both BRAINYARD.md files inline.
/init reseed           Re-import from CLAUDE.md / AGENTS.md, diff against current.
/init revert           List snapshots, pick one to revert to.
/init --diff           Dry-run: show the diff init-agent would propose, but don't write.
/init --scope :user|:project|:both
                       Override the auto-scope choice.
```

### 11.3 Callable from any agent

Other agents call init-agent as a direct sandbox function — every `defagent` registers as a kebab-case callable in the SCI sandbox alongside `defcommand` / `defskill` entries, so there is no `call-tool` wrapper to go through:

```clojure
(init-agent
  {:question "<the instruction>"
   :scope    :project          ;; optional; defaults per §4.2
   :op       :append           ;; optional; init-agent classifies if omitted
   :confirm? true})            ;; required for writes > 200 chars under :op :append, or :op :init/:curate/:reseed
```

The result map is the same one `init$apply` returns (`{:ok? :snapshot-path :diff :size :dossier-path}`) so the caller can surface it cleanly.

A small helper namespace `agent/common/init/calling.clj` provides a one-liner for the common case ("persist this fact") that picks the section and sets sane defaults — used by `coact-agent` (and any future agent that wants the "remember this for next time" idiom) so the call site stays short.

---

## 12. Edge Cases

1. **Both BRAINYARD.md and CLAUDE.md exist, contents differ.** Init-agent surfaces the diff but does not auto-merge. User picks `:reseed` if they want a fresh import.
2. **CLAUDE.md is huge (>20 KB).** Summarisation will lose detail. Init-agent warns and offers two paths: full summarise (lossy) or split into project + user scope so each fits.
3. **Markdown parse failure on smoke test.** Auto-revert to the pre-write snapshot. Surface the parse error to the user.
4. **`.brainyard/` directory missing at write target.** Create it (`(.mkdirs)` — same path `agent/core/config` already uses for `config.edn`). Log the directory creation in the dossier.
5. **User edits BRAINYARD.md by hand between sessions.** Detected via mtime check at conversation start; agent surfaces "BRAINYARD.md was edited externally — re-read?" The next write base is the current file, not the previous snapshot.
6. **Concurrent edits from two TUI sessions.** `init$apply` reads mtime before writing; if changed since the conversation started, refuse and ask the user to re-read. Same idiom as config-agent.
7. **Snapshot dir full.** Rotate aggressively (oldest first, per scope). Never block writes.
8. **`(init-agent …)` from inside an `--auto` (non-interactive) run with a write > 200 chars.** Refuse per §7.4 with `:error :auto-confirmation-required`. The calling agent must explicitly pass `:confirm? true` if its caller has authorised the write.
9. **User scope file edited in a non-project context (no `.brainyard/` in cwd).** Default to user scope; `init$apply` writes to `~/.brainyard/BRAINYARD.md`.
10. **`/init` with no BRAINYARD.md AND no sources detected AND no prompt.** Ask the user once: "No BRAINYARD.md, no CLAUDE.md/AGENTS.md to seed from — want me to explore the project directory and draft one?" On `yes`, dispatch `explore-agent` to sniff the repo (top-level layout, build files, README), synthesise a first draft mapped onto the §4.3 section template (gaps left as `TODO` placeholders the user fills in), then run the standard read → propose → diff → confirm → apply flow. On `no`, exit without writing. Init-agent never invents project facts; the draft only contains observations from explore-agent's actual output plus the §4.3 scaffold.
11. **User puts secrets in BRAINYARD.md** ("here's my API key"). Init-agent's write path runs a simple secret-pattern scan (entropy + common-key-prefix regex) before snapshot; on match, refuses the write and tells the user to put the secret in `.env` instead. Same hooks `update-agent` uses for diff scanning.

---

## 13. Testing Plan

1. **Unit — write transaction** (`init$apply` against a temp dir). Fixtures: empty → init from CLAUDE.md; existing → append under known section; size-cap violation; mtime conflict; smoke-test failure (mocked markdown parse error). Assert snapshot exists / doesn't exist correctly.
2. **Integration — CoAct loop with stubbed LLM.** Canned LLM responses drive the agent through each capability kind (§5). Assert final on-disk file and dossier match goldens. The seed kind has the richest golden: CLAUDE.md input → BRAINYARD.md output.
3. **End-to-end — manual / CI-opt-in.** Real `/init` in a scratch project with a real CLAUDE.md. Verifies the slash command wiring, the smoke test, and that subsequent `bb tui run` reads the new file via `load-brainyard-instructions`.

The dossier file is its own contract — every test reads the dossier and asserts the frontmatter fields are populated.

A fourth test surface validates **callable-from-other-agents**: a unit test spawns a stub agent whose sandbox emits `(init-agent {…})` directly, asserts the call routes through the sandbox auto-binding correctly and that the auto-confirmation rule (§7.4) applies as specified.

---

## 14. Migration / Phasing

Phase 1 (this design): commands + agent file + slash command + dossier scaffold.

- New: `agent/common/init.clj`, `agent/common/init_agent.clj`, `.brainyard/agents/init-agent/sources.edn`.
- Modified: `bases/agent-tui/.../commands.clj` (one slash entry, one handler, one help-text update).
- `agent/core/config.clj`'s `load-brainyard-instructions` is unchanged — init-agent only changes what's *in* the file, not who reads it. Every existing agent picks up the new content on its next turn for free.

No data migration. Pre-existing BRAINYARD.md files (if any user has hand-authored one) are respected; init-agent's first read at each scope checks for one before offering to seed.

Phase 2 (follow-up): a `coact-agent` hook (`:agent.ask/post`) that, after a substantive answer, asks "anything in this conversation worth persisting to BRAINYARD.md?" and conditionally calls init-agent. Opt-in via a runtime-config flag (`:enable-init-suggestions`) — off by default to avoid noise.

Phase 3 (follow-up): bidirectional sync — propagate selected changes back to CLAUDE.md / AGENTS.md so a user editing through brainyard also updates the sibling tools' view. Currently out of scope because the write-direction question deserves its own design.

---

## 15. Open Questions

1. **Should the `:agent.ask/post` hook (phase 2) be init-agent-specific or general?** A "post-turn distill into memory or BRAINYARD.md" hook is broader than init-agent — memory-agent wants the same callback. Likely a single shared hook with two consumers; defer to phase 2.
2. **How does init-agent handle `BRAINYARD.local.md`** (gitignored, per-developer overrides at project scope)? Adds value but doubles the section model. Defer; today the user-scope file covers this use case.
3. **Section model rigidity.** §4.3's eight sections are advisory. Should `init$apply` enforce them, or stay permissive (accept any markdown, only the autocurate path nudges toward the canonical set)? Lean permissive — power users can structure however they want.
4. **Markdown parser choice.** Smoke-test parsing needs to be cheap and deterministic. `commonmark-java` is overkill; a regex-based section extractor is probably enough. Decide before phase 1 ship.
5. **Should `/init` be exposed in the agent-web UI as well?** Yes, eventually — same `init-agent` engine, different shell. Out of scope here.
6. **Reseed conflict resolution.** When the user has hand-edited a section AND CLAUDE.md has changed the same section, which wins? Today's plan: surface the conflict, user picks per-section. Could become a structured 3-way merge if the volume justifies it.
7. **Should init-agent be able to *read* memory (L3) and suggest persisting durable facts into BRAINYARD.md?** Promising — memory and BRAINYARD.md serve overlapping purposes (always-on context vs recall-on-demand). Could be the phase-2 hook's natural input.
