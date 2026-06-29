# Config-Agent — LLM-Mediated Configuration Management with Snapshot/Revert (CoAct-derived)

> **Status:** Shipped — `config-agent` is registered in `components/agent` (`common/config_agent.clj`). This document is the original design proposal (revision 1 — draft); the shipped implementation may diverge in details. See [core/agent.md](../core/agent.md) for the current roster.
> **Scope:** new `components/agent/src/ai/brainyard/agent/common/config_agent.clj` and a thin command namespace `components/agent/src/ai/brainyard/agent/common/config.clj`
> **Built on:** `coact_agent.clj` via `coact/run-coact-derived`
> **Upstream:** `docs/design/bootstrapping-design.md` (the `bb tui config` wizard owns first-LLM bootstrap; this agent owns everything after)
> **Sibling of:** `edit-agent` (safe file edits), `mcp-agent` (MCP lifecycle), `explore-agent` (read-mostly discovery)
> **Related reading:** `components/agent/src/ai/brainyard/agent/core/config.clj`, `bases/agent-tui/src/ai/brainyard/agent_tui/config_wizard.clj`, `bases/agent-tui/src/ai/brainyard/agent_tui/commands.clj` (`/config`, `/effort`, `/model`)

---

## 1. Motivation

After the bootstrapping wizard exits, the user has a working `config.edn` (at either project or user scope — see §2A) and (by contract) a reachable `default-provider`/`default-model`. What they do *not* have is a good way to evolve the rest of that config over time. Today three distinct UX surfaces try to cover the gap and none of them does it well:

1. **The wizard (`bb tui config`)** is the only LLM-free way to edit persisted config. It's six fixed steps that re-prompt every time. There's no way to say "just add a Slack MCP server" — you have to walk all six steps and arrive at the right one. The wizard also doesn't know about the runtime-config schema (the ~30 keys in `agent/core/config.clj`) — those live in a parallel universe.
2. **The `/config` slash command** (in `commands.clj`) edits the **runtime** config only — the per-session knobs (`:max-iterations`, `:enable-context-budget`, `:eval-lm-config`, `:max-refinements`, ...). It's a flat `get/set` over keyword keys. To use it the user has to (a) know the key exists, (b) know its type, (c) type the exact name. There's no discovery, no validation beyond type coercion, no preview, and changes evaporate when the TUI exits — they never reach `config.edn`.
3. **Direct file editing.** Users routinely open `config.edn` (project or user — see §2A) in their editor when neither of the above gets them where they need to go. This works, but it's the only surface that exercises the full schema, it skips all validation, and it has no audit trail.

The result: configuration changes happen at three different layers (wizard / runtime / file) with three different vocabularies, and the user has to keep all three in their head. New users in particular fall off — they ran `bb tui config`, the wizard wrote a sensible default, and now they want to "make the agent ask before deleting files" or "stop reasking my home dir every time" and they have no idea which surface owns that.

A second problem is **trust**. Right now any change to persisted config is a fire-and-forget overwrite. There's no snapshot, no diff preview, no revert. Anyone (the wizard, a future agent, the user's text editor) can leave the system in a broken state — wrong `default-model`, an MCP server that refuses to start, an allowlist that locks the agent out of the project directory — with no path back besides "did you remember what it was before?"

**Thesis.** Add a CoAct-derived `config-agent` that:

1. **Is the one chat surface for non-bootstrap configuration.** Permissions, MCP servers, runtime knobs, agent defaults, sandbox mode, hooks — all editable through natural language ("make the agent ask before bash", "add the linear MCP server", "raise max-iterations to 200 for this session only"). The wizard stays for first-run bootstrap; the slash commands stay for power-user shortcuts; this agent is the conversational hub that supersedes "open the file in vim."
2. **Treats every persisted write as a transaction.** Read → propose → preview diff → confirm → snapshot the old file → write → smoke-test → record in a dossier. Any step can fail and roll back. The user can revert N steps backward at any time.
3. **Owns a narrow, explicit writable surface.** The keys this agent may set are listed in `writable-keys` (§7); anything else is read-only. `:llm.default-provider` and `:llm.default-model` in particular belong to bootstrap; this agent can *propose* a change there but the actual write goes through the bootstrap ladder (`bootstrap/re-run-rung`), not direct EDN.
4. **Bridges runtime and persisted configs cleanly.** When the user says "set max-iterations to 200" the agent asks "for this session only, or persist?" Runtime-only goes through `agent/set-config-value!` (existing path); persistent goes through `config-agent`'s transactional write. Both flows share validation.

   > **As-built:** the "session-only vs persist" choice was *removed*. `agent-runtime$config {:key K :value V}` now writes BOTH the per-agent override (immediate effect on the running agent) AND the persisted global value at `[:agent :config K]` in `config.edn`. There is no session-only path to opt into — every runtime-config write is persisted automatically. See the shipped instruction guidance (4) in `common/config_agent.clj` ("PERSISTED CONFIG IS A SINGLE STORE").
5. **Inherits the CoAct loop, sandbox, and accumulator** from `coact-agent`. No new BT, no new DSPy signature.

Same minimal-diff pattern as the other specialist agents: one new agent file, one new command namespace, a `config-agent/` artifact directory under `.brainyard/`. `agent/core/config.clj` is untouched.

---

## 2. Design Principles

1. **One writable surface per concern.** Persisted config goes through `config$apply`. Runtime config goes through the existing `agent-runtime$config` polymorphic command. MCP goes through the existing `mcp$server` polymorphic command. The agent never writes `config.edn` by hand and never bypasses validation.
2. **Read often, write rarely.** Every conversation starts with `config$read` and `env-detect/detect-all` — cheap, cached. Writes only happen after the user has seen a diff and confirmed.
3. **Snapshot before every persisted write.** Snapshots are kept **per-scope** at `<scope-dir>/config-agent/snapshots/<ts>-<reason>.edn` — project edits snapshot under `<repo>/.brainyard/`, user edits under `~/.brainyard/`. The last 20 snapshots per scope are kept; older are rotated. `config$revert` restores any of them, and revert is per-scope: a `:project` revert can only restore a project snapshot, same for `:user`.
4. **Validate against the schema, always.** Runtime keys validate against `agent.core.config/config-schema`. Persisted keys validate against an extended `persisted-schema` (§7) that covers `:llm`, `:permissions`, `:agent`, `:mcp`, `:environment`, `:bootstrap`. Unknown keys are rejected, not silently passed.
5. **Allowlist what the agent can change.** §7's `writable-keys` is the contract. The agent's *instruction* repeats it; the *tool layer* enforces it. Belt-and-suspenders — an LLM hallucinating a `:llm.default-provider` write is rejected at the tool boundary.
6. **Bootstrap concerns belong to bootstrap.** If the user asks "switch me to Claude," the agent doesn't edit `:llm.default-provider`. It calls `bootstrap/re-run-rung` (a thin facade over the wizard's ladder) which re-detects, smoke-tests, and writes. This keeps "is the configured LLM actually reachable?" in one place.
7. **Preview, don't surprise.** Every proposed multi-key change shows a unified diff against the current `config.edn`. The user sees exactly what will land before approving. `--dry-run` is the default for non-interactive ops; explicit confirmation flips it on.
8. **Dossier per conversation.** Every config-agent session produces a markdown dossier at `.brainyard/agents/config-agent/dossiers/<yyyyMMdd-HHmmss>-<slug>.md` summarising what was read, what was changed, which snapshot was taken, and what (if anything) needs follow-up. Mirrors the explore/plan/update agent pattern.
9. **No clone-self recursion.** Like the other CoAct-derived specialists, `config-agent` excludes `query$clone` from its tool roster. Cross-agent dispatch via flat `(call-tool "<other>" {…})` is fine — calling `mcp-agent` for a deep MCP debug session, `explore-agent` for "find which file references this server name," `edit-agent` for editing a `.env`.
10. **Be honest about durability.** Runtime config changes evaporate at TUI exit; persisted changes do not. The agent says this every time the user makes a runtime-only change ("Set for this session. Want me to persist it?") so the disconnect can never silently bite.

> **As-built:** principle 10 no longer applies as written. There is no session-only path — `agent-runtime$config` persists every set to `[:agent :config]` in `config.edn` *and* applies it to the running agent. The shipped instruction tells the agent to say "Active now and persisted" rather than offering a session-only/persist choice.

---

## 2A. Scope — Project vs User

`config.edn` exists at **two scopes**, both honored by the agent and the runtime:

| Scope | Path | Resolution | Use for |
|---|---|---|---|
| **Project** | `<repo>/.brainyard/config.edn` | `BY_PROJECT_DIR` env → nearest `.git` ancestor → **working-dir fallback** | Settings that travel with the repo: MCP servers used in this codebase, project-specific `:allowed-dirs`, repo-local sandbox mode |
| **User** | `~/.brainyard/config.edn` | `System/getProperty "user.home"` | Cross-project defaults: preferred `:default-agent`, global LLM cache, base `:allowed-dirs`, personal sandbox preferences |

The working-dir fallback in project resolution means `resolve-project-dir` is **never nil** in normal operation. Outside a git repo and without `BY_PROJECT_DIR`, project-scope writes land under `<cwd>/.brainyard/` instead of failing. The agent should still ask the user "is this really where you want it?" when running outside a repo — see SCOPE DISCIPLINE in §6.

**No auto-merge.** The runtime reads whichever scope's file the caller asks for; there is no deep-merge between them. When both files exist, `read-edn-config` with `:scope :auto` returns the **project** file (first-found-wins) — see `components/agent/src/ai/brainyard/agent/core/config.clj` `resolve-scope`. A key set in user scope but also present in project scope is shadowed by the project value as long as `:auto` resolves to project.

**Every persisted command takes `:scope`:**

```
(config$read :scope :project|:user|:auto)
(config$diff :scope :project|:user|:auto :proposed …)
(config$snapshot :scope :project|:user|:auto :reason …)
(config$list-snapshots :scope :project|:user|:auto)
(config$revert :scope :project|:user|:auto :steps N)
(config$apply :scope :project|:user|:auto :proposed … :reason …)
```

Default is `:auto`, which resolves to project-if-file-exists-else-user for reads, and project-if-dir-resolvable-else-user for writes. Every response includes `:scope` (the resolved value) and `:requested-scope` (the original arg). The agent's instruction (§6) tells it to:

- **Confirm scope with the user before any persisted write.** `:auto` is allowed but must be surfaced (`"Wrote to project: …"`) so the user is never surprised.
- **Read both scopes on disambiguation.** If a setting doesn't seem to take effect, the agent reads project AND user to find the shadowing layer.
- **Default project for repo-specific intents** (MCP server names, project allowed-dirs); **default user for personal defaults** (default-agent, sandbox preferences).

**Per-scope snapshot and dossier history.** Snapshots and dossiers live under each scope's `<scope-dir>/config-agent/`. A project-scope apply snapshots under `<repo>/.brainyard/agents/config-agent/snapshots/`; a user-scope apply under `~/.brainyard/agents/config-agent/snapshots/`. Revert is per-scope — you cannot restore a user file from a project snapshot or vice versa.

**`config-agent/` is one of two exceptions to the broader scope contract.** `agent.core.config/subdir-scope-policy` classifies every `.brainyard/<name>` entry: most `*-agent/` dirs (`explore-agent`, `plan-agent`, `todo-agent`, `workflow-agent`, `research-agent`, `edit-agent`, `eval-agent`, `exec-agent`) are **project-only**. `config-agent/` and `init-agent/` are the exceptions — their artifact dirs mirror the scope of the file they edit (`config.edn` and `BRAINYARD.md` respectively), since those files themselves are dual-scope. See [architecture.md](../architecture.md) for the full subdir scope table.

**Scope errors.** `:scope :project` is essentially always resolvable now (working-dir fallback). The only remaining nil case is a caller constructing a `dirs` map with `:project-dir nil` explicitly (tests do this). In normal use, `{:ok? false :stage :scope}` is hard to hit — but the agent should still confirm with the user when working outside a real repo, since the implicit cwd-as-project semantics may surprise them.

**Wizard / bootstrap-driver compatibility.** The bootstrap wizard (`bb tui config`) and `bootstrap$re-run-rung` continue to use `:auto` (project-first-else-user). They are not scope-aware because their job is "produce a working config.edn somewhere" — distinguishing scope is config-agent's concern.

---

## 3. Position in the Agent Stack

```
                        bb tui config            (one-shot wizard — bootstrap only)
                              │
                              ▼  writes config.edn with reachable LLM, exits
                              │
                              │  user runs:  bb tui run -a config-agent
                              ▼
   coact-agent  (parent — full BT, sandbox, router, accumulator)
     ├─ explore-agent       (read-mostly discovery)
     ├─ plan-agent          (planning + dossier)
     ├─ todo-agent / exec-agent / eval-agent
     ├─ edit-agent        (safe file edits — used by config-agent for .env / SKILL.md edits)
     ├─ mcp-agent           (MCP lifecycle — called by config-agent for "add server" flows)
     └─ config-agent        (THIS — conversational config hub)
```

`config-agent` is a peer of the other CoAct-derived specialists. It does not subclass them and they do not call it; it calls *them* when a config conversation needs a sibling specialist's capabilities (MCP server install, `.env` edit, "find me all places that reference `OPENAI_API_KEY`"). This keeps the agent focused — config-agent never reimplements MCP lifecycle, never reimplements safe file edits, just orchestrates them.

---

## 4. Capability Surface

Five conversation kinds the agent must handle competently. These are not branches in the loop — they're the user-facing categories the instruction (§6) explicitly tells the LLM to recognise.

1. **Show me what's set.** "Show my MCP servers." "What's `:enable-context-budget`?" "What permissions am I running with?" Pure read flow: `config$read` (whole or section) → render → done.
2. **Tune a single knob.** "Set `max-iterations` to 200." "Switch sandbox to `permissive`." Determine runtime-vs-persisted intent → validate value → snapshot if persisting → apply → confirm.
3. **Add/remove an integration.** "Add the Linear MCP server." "Remove the broken slack one." Hand off to `mcp-agent` for the lifecycle, then write the resulting server entry back into `:mcp.servers` through `config$apply`.
4. **Re-detect environment.** "I just exported `ANTHROPIC_API_KEY` — pick it up." Call `env-detect/detect-all`, surface what's new/changed, ask whether to (a) update `:llm.available-providers` cache only, or (b) re-run the bootstrap ladder via `bootstrap/re-run-rung` to switch default-provider.
5. **Roll back.** "Undo that." "Revert to before I added the MCP server." List recent snapshots, let the user pick one, write it back, dossier the revert.

What the agent must NOT do:
- Re-run the full wizard from chat (the wizard owns its own UX; agent calls into bootstrap helpers if needed).
- Edit `:bootstrap` (read-only — bootstrap owns this).
- Edit `<scope-dir>/.brainyard/agents/config-agent/snapshots/` directly (either scope; it's a sink).
- Write outside `:permissions.allowed-dirs`-allowed paths when proposing `edit-agent` edits (e.g. `.env` files outside the project — confirmation flow required).

---

## 5. Tool Roster

`config-agent` binds the standard CoAct primitives plus a small new command set. Inherited from the shared registry:

| Tool | Used for | Notes |
|---|---|---|
| `read-file` / `grep` | Reading config files, scanning for references | Through the standard sandbox tool surface. |
| `bash` (allowlisted) | `ollama list`, `claude --version`, `env`, `gpg --list-keys` (diagnostic) | Allowlist enforced at the tool layer; no writes. |
| `query$llm` | Sub-LLM calls for summarisation, value-coercion suggestions | Single-step; no recursion. |
| `agent-runtime$config` | Read or set config (per-agent override + persisted) | Existing polymorphic command in `agent/common/commands.clj`. No args → returns the merged config snapshot (per-agent override → global config → schema default). `:key K :value V` → validates against `config/config-keys`, coerces, applies. **As-built:** the set path writes BOTH the per-agent override AND the persisted global value at `[:agent :config K]` in `config.edn` — there is no session-only mode. Reused as-is — do not introduce parallel `runtime$get`/`runtime$set` shims. |
| `mcp$server` / `mcp$tools` / `mcp$lifecycle` | Inspect / add / remove / restart MCP servers | Existing trio in `mcp-agent`'s command set; reused as-is. |
| `edit-agent` (call-tool) | Edit `.env`, `BRAINYARD.md`, `~/.brainyard/permissions.edn`-style sidecar files | Same safe-edit pipeline as elsewhere. |
| `explore-agent` (call-tool) | "Find all places that reference X env var / server name" | Read-only discovery. |

New commands (defined in `agent/common/config.clj`, registered via `defcommand`):

| Command | Polymorphic on | Effect |
|---|---|---|
| `config$read` | `{:section :all|:llm|:permissions|:agent|:mcp|:environment|:bootstrap}` | Returns the requested slice of the persisted config plus the live runtime-config overlay. Pure read. |
| `config$diff` | `{:proposed <partial-config-map>}` | Returns a unified diff against the on-disk file plus a normalised summary (`{:adds [...] :removes [...] :changes [...]}`). No side effects. |
| `config$apply` | `{:proposed <map> :reason <str> :confirm? <bool>}` | The transactional write: validate → snapshot → write → smoke-test selected sections → dossier-append. Returns `{:ok? :snapshot-path :diff :smoke-test :dossier-path}`. Refuses without `:confirm? true` unless `--auto` is set. |
| `config$revert` | `{:snapshot-path <path>}` or `{:steps <int>}` | Restores a prior snapshot; itself snapshots the current file first (so revert is reversible). |
| `config$snapshot` | `{:reason <str>}` | Manual snapshot without a write — useful before risky exploratory edits. |
| `config$list-snapshots` | `{}` | Last 20 with timestamps, reasons, brief diffs. |
| `env-detect$rescan` | `{}` | Re-runs `env-detect/detect-all`; returns the fresh result. Pure read. |
| `bootstrap$re-run-rung` | `{:rung :a|:b|:c|:d|:e|:f}` | Calls the wizard's ladder programmatically for the rung specified. The only path through which `:llm.default-provider`/`:default-model` may change while config-agent is running. |

Runtime-config reads and writes are NOT in this new command set — they go through the existing `agent-runtime$config` listed in the inherited table above. Same goes for MCP: `mcp$server` / `mcp$tools` / `mcp$lifecycle` are reused, not re-wrapped.

The polymorphic `config$*` family mirrors the existing `mcp$*` / `plan$*` / `doc$*` / `agent-runtime$config` patterns: one keyword dispatcher per command, opaque body keys.

---

## 6. Instruction Shape

The CoAct instruction has a fixed template across the specialist agents (`docs/CoAct.md`). For `config-agent` it adds five guidances on top of the standard CoAct preamble:

1. **Start every conversation with a read.** Before suggesting anything, call `config$read :section :all` and `env-detect$rescan`. Surface what's actually set today; don't propose changes against an assumed baseline.
2. **Classify the user's intent into one of the five capability kinds (§4) before acting.** If ambiguous, ask one targeted question — not five. (Specifically: the agent should never re-prompt for things it could derive from the cached read.)
3. **For any write, follow read → propose → diff → confirm → apply.** Skipping the diff is a defect. The user sees the diff inline (rendered from `config$diff`'s output) and types `yes` / `no` / `change X to Y` — last form short-circuits back into "propose."
4. **Runtime vs persisted is the user's choice, not yours.** When the user names a runtime-config key, default to runtime-only with a follow-up offer to persist. When they name a persisted key, default to persisted. When ambiguous (`max-iterations` exists in both — runtime-config and `:agent.max-iterations`), ask once.
5. **Hand off when the right tool is a sibling.** "Add the Linear MCP" → call `mcp-agent` for the lifecycle, then write the result. "Find every place that uses this server name" → call `explore-agent`. "Edit my `.env` to add an API key" → call `edit-agent` (because `.env` is a normal file, not part of `config.edn`).

The instruction explicitly forbids:

- Writing `:llm.default-provider` or `:llm.default-model` through `config$apply` (use `bootstrap$re-run-rung` instead — enforced at the tool layer too).
- Touching `:bootstrap` (read-only).
- Writing outside the project / user-config dirs.
- Fabricating defaults — if a key isn't in the schema, refuse the write and tell the user.

---

## 7. Writable Keys (the Allowlist)

| Section | Keys this agent may write | Owner |
|---|---|---|
| `:llm` | `:available-providers` (cache only, refreshed by `env-detect$rescan`) | config-agent (cache) / bootstrap (defaults) |
| `:llm.default-provider`, `:llm.default-model` | — **NOT writable** via `config$apply` — | bootstrap (`bootstrap$re-run-rung`) |
| `:permissions` | `:mode`, `:allowed-dirs` | config-agent |
| `:agent` | `:default-agent`; `[:agent :config :*]` (any `config-schema` key — `:max-iterations`, `:enable-context-budget`, …) | config-agent |
| `:environment.sandbox-mode` | `:permissive | :standard | :restricted` | config-agent |
| `:environment.executables`, `:environment.sandbox-type`, `:environment.os` | — **NOT writable** — these are detection outputs | env-detect |
| `:mcp.servers` | full sub-map | config-agent (via `mcp$server` lifecycle) |
| `:bootstrap` | — **NOT writable** — | bootstrap |
| `:created-at`, `:updated-at`, `:version` | `:updated-at` only (auto-stamped on every write) | config-agent |

Runtime-config keys (the ~30 in `agent.core.config/config-schema`) are all writable through `agent-runtime$config {:key K :value V}`.

> **As-built:** the open question of "runtime-config persistence" (originally §13 Q1, the `:runtime-overrides` block) was resolved by collapsing the two layers. Every `config-schema` knob persists under a single `[:agent :config]` subtree in `config.edn`; an `agent-runtime$config` set writes the per-agent override *and* that persisted global in one call. `config$apply`'s allowlist allows the whole `[:agent :config]` prefix, type-checking each leaf against `config-schema` (the legacy flat `[:agent :max-iterations]` position is auto-migrated on write — see `migrate-legacy-edn-shape` in `agent.core.config` and `writable-prefixes` in `common/config.clj`).

The allowlist is checked twice. The instruction lists it for the LLM. The `config$apply` command rejects any proposed map that touches a non-allowed path, returning a structured error the LLM is told to surface (not to "work around"). Belt-and-suspenders is the rule for an LLM writing persistent state.

---

## 8. Snapshot / Revert / Dossier

Snapshot and dossier directories are **per-scope** — see §2A. The layout is identical for both:

```
<scope-dir>/config-agent/      ;; <scope-dir> = <repo>/.brainyard or ~/.brainyard
├── snapshots/
│   ├── 20260514-174214-add-linear-mcp.edn         ;; pre-write copy of config.edn
│   ├── 20260514-180102-set-max-iter-200.edn
│   └── …  (rotation: keep 20, oldest pruned)
├── dossiers/
│   ├── 20260514-174200-add-linear-mcp.md          ;; per-conversation artifact
│   └── …
└── INDEX.md                                        ;; append-only index, last 100 entries
```

**Snapshot.** Every `config$apply` and every `config$revert` writes a snapshot first **at the same scope as the write**. Filename is `<yyyyMMdd-HHmmss>-<slug>.edn`; slug derived from `:reason`. Snapshots are full file copies (not diffs) — config.edn is small (< 50 KB typical), so the simpler invariant ("snapshot is a drop-in replacement") wins over disk savings. First-time writes to a fresh scope return `:snapshot-skipped true` (nothing to preserve).

**Revert.** `config$revert {:snapshot-path P :scope S}` snapshots the current scope-S file (`reason: "revert-to-<source-slug>"`), then copies `P` back to the scope-S `config.edn`. `config$revert {:steps N :scope S}` does the same for the Nth most recent scope-S snapshot. The pre-revert snapshot means even revert is reversible. Cross-scope reverts are not supported by design — restoring a user file from a project snapshot would silently change semantics.

**Dossier.** One markdown file per conversation, written incrementally. Frontmatter pattern matches the explore/plan/update agents:

```markdown
---
agent: config-agent
session-id: 2026-05-14-config-37afa
question: "Add Linear MCP server and bump max-iterations to 200"
started: 2026-05-14T17:41:50Z
ended: 2026-05-14T17:44:08Z
config-path: <repo>/.brainyard/config.edn   # or ~/.brainyard/config.edn for user scope
scope: project                                # echoes :scope used for the write(s)
snapshots:
  - 20260514-174214-add-linear-mcp.edn
  - 20260514-180102-set-max-iter-200.edn
writes: 2
reverts: 0
next-steps: []
---

## What I read
- config$read :section :all → … (8 servers, mode :ask-each-time, …)
- env-detect$rescan → no provider changes since boot

## What changed
1. :mcp.servers — added :linear (transport :stdio, command "npx", args …)
2. :agent.max-iterations — 100 → 200

## Diffs
```diff
- {:mcp {:servers {}}}
+ {:mcp {:servers {:linear {:transport :stdio :command "npx" :args ["-y" "@linear/mcp-server"]}}}}
…
```

## Smoke tests
- mcp-agent connect :linear → ok (3 tools enumerated)
- agent settings load → ok
```

The dossier is the durable record of the conversation. Like the other CoAct dossiers it is consumable by downstream agents (notably `explore-agent` when the user later asks "what did I change last week?").

---

## 9. Conversation Patterns (worked examples)

### 9.1 Add an MCP server (capability kind 3)

> **User:** add the linear MCP server

1. `config$read :section :mcp` → empty servers map.
2. `query$llm` → ask which transport (stdio vs http) and what credentials are needed; surface known options.
3. User: "stdio, here's my API key."
4. Call `mcp-agent` with `(op :add :server :linear :transport :stdio :command "npx" :args ["-y" "@linear/mcp-server"] :env {"LINEAR_API_KEY" "…"})`. `mcp-agent` returns the resulting server entry.
5. `config$diff :proposed {:mcp {:servers {:linear …}}}` → render diff.
6. User: "yes."
7. `config$apply :proposed … :reason "add-linear-mcp" :confirm? true` → snapshot → write → smoke-test (`mcp-agent connect :linear`) → dossier-append.
8. Answer: "Added Linear MCP server. Snapshot: `20260514-174214-add-linear-mcp.edn`. Tools available: …"

### 9.2 Tune a runtime knob (capability kind 2)

> **User:** raise max-iterations to 200

1. `agent-runtime$config {}` → merged snapshot shows `:max-iterations 100`.
2. `agent-runtime$config {:key "max-iterations" :value "200"}` → writes the per-agent override (immediate) AND persists `[:agent :config :max-iterations] 200` to `config.edn`.
3. Answer: "Done. Active now and persisted (future agents). `:max-iterations` 100 → 200."

> **As-built:** the original "session-only or persist?" disambiguation (and a separate `config$apply :proposed {:agent {:max-iterations 200}}` write) is gone — `agent-runtime$config` does both in one call, so a single-knob tune needs no diff/confirm flow. `config$apply` remains for bulk/structural writes under `[:agent :config :*]` or other sections.

### 9.3 Re-detect and switch provider (capability kind 4)

> **User:** I exported ANTHROPIC_API_KEY, use it.

1. `env-detect$rescan` → providers map now shows `:anthropic :available? true`.
2. `config$read :section :llm` → currently `:ollama / glm-4.5-air`.
3. Recognise this is a bootstrap-owned change. Don't write `:llm.default-provider` directly.
4. Show summary: "Anthropic available. Currently using Ollama / glm-4.5-air. Switch?"
5. User: "yes."
6. `bootstrap$re-run-rung :rung :b` → wizard's rung (b) prompt (provider menu preselected on anthropic, model picker for sonnet/opus/haiku) runs interactively or programmatically.
7. Bootstrap writes the new `:llm` block (its own snapshot, its own log). Config-agent reads the result and answers: "Switched to anthropic / claude-sonnet-4-6."

### 9.4 Revert (capability kind 5)

> **User:** undo that last change

1. `config$list-snapshots :limit 5` → list, newest first.
2. Show the user the top 1–3 with reason + diff summary.
3. User: "the linear one."
4. `config$revert :snapshot-path "20260514-174214-add-linear-mcp.edn"` → pre-revert snapshot → copy → smoke-test (mcp-agent re-enumerates servers; linear no longer present) → dossier-append.
5. Answer: "Reverted. Linear server removed. Pre-revert snapshot kept at `20260514-181530-revert-to-add-linear-mcp.edn` in case you change your mind."

### 9.5 Show me what's set (capability kind 1)

> **User:** what's my sandbox mode?

1. `config$read :section :environment` → `:sandbox-mode :standard`.
2. Answer in one line: "`standard` — asks before dangerous ops. (Other options: `:permissive`, `:restricted`.)"

No write, no snapshot. The fast path matters — the agent should not turn one-line lookups into multi-turn flows.

---

## 10. Edge Cases

1. **`config.edn` missing or unreadable.** Bootstrap-incomplete state. Refuse to proceed; tell the user to run `bb tui config` (with a one-liner reason).
2. **Schema drift — key in file but not in schema.** Preserve it, surface a warning, do not propose a write that touches it. (Bootstrap from a newer build will eventually validate.)
3. **Smoke test failure after `config$apply`.** Write succeeded, smoke test failed (e.g. MCP server won't connect). Surface the failure and propose `config$revert :steps 1`. Don't auto-revert — the user may want to debug.
4. **Concurrent writes** (two TUI sessions editing the same file). `config$apply` reads the file's mtime before writing; if it changed since the conversation started, refuse and ask the user to re-read. (Future: add file lock through `agent.core.config` if this becomes common.)
5. **Snapshot dir full** (some user has 20+ pending). Rotate aggressively: drop oldest by `:created-at`, never block writes.
6. **`agent-runtime$config` for a key with overlapping `:agent.*` semantics** (`max-iterations` is the canonical case — exists in both the runtime-config schema and as `:agent.max-iterations` in `config.edn`). The agent always asks once ("session-only or persist?") and remembers the user's choice for the rest of the session.
7. **User pastes an EDN map directly** ("apply this: `{:permissions {:mode :restricted}}`"). Accept, validate, treat as a `:proposed` map; the diff/confirm flow still runs.
8. **Allowlist violation** (LLM proposes a write to `:llm.default-provider`). `config$apply` rejects with `{:error :allowlist-violation :path [:llm :default-provider]}`. The instruction tells the LLM to apologise and propose the right path (`bootstrap$re-run-rung`) instead of re-trying with a workaround.
9. **MCP credential lives in `.env`**, not `config.edn`. The agent reads `.env` (if present) but never writes secrets into `config.edn`. The `.env` write goes through `edit-agent` with a redacted diff in the dossier.
10. **`bootstrap$re-run-rung` requires interactivity but config-agent is in `--auto`.** Refuse; tell the user to drop `--auto` for provider switches.

---

## 11. Testing Plan

Three surfaces, same as the bootstrapping doc.

1. **Unit — write transaction** (`config$apply` against a temp dir). Fixtures: clean apply, allowlist violation, schema violation, mtime conflict, smoke-test failure (mocked). Assert snapshot exists / doesn't exist correctly.
2. **Integration — CoAct loop with stubbed LLM**. Canned LLM responses drive the agent through each capability kind (§4). Assert final on-disk config and dossier match goldens.
3. **End-to-end — manual / CI-opt-in**. Real `bb tui run -a config-agent` against a scratch `.brainyard/` (both project and user scopes exercised). Verifies per-scope snapshot rotation, scope-aware revert, and the bootstrap handoff. Slow; tagged `:slow`.

The dossier file is its own contract — every test reads the dossier and asserts the documented frontmatter fields are populated.

---

## 12. Migration / Phasing

Phase 1 (this design): commands + agent file + dossier scaffold. The `bb tui config` wizard's phase-3 "enter config-agent now?" prompt resolves to a real agent. `/config` slash command unchanged.

Phase 2 (follow-up): runtime-config-overrides persistence (the open question in §7). Adds `:runtime-overrides` key to `config.edn` schema; on agent start, `agent.core.runtime` loads them into the runtime config before the first turn. `/config` slash command grows a `persist` subcommand.

Phase 3 (follow-up): mirror the agent's writable surface into a programmatic API (`config-agent/apply!`) so the wizard's phase 2 itself can call into the same validation/snapshot/dossier pipeline. Today the wizard writes config.edn directly; this is a duplication we can collapse once config-agent has shipped and stabilised.

No on-disk data migration needed for phase 1.

---

## 13. Open Questions

1. **Should `:runtime-overrides` exist in `config.edn`?** ~~Lean (a); defer to phase 2.~~ **Resolved (as-built):** went with (a) — every `config-schema` knob persists under a single `[:agent :config]` subtree, and `agent-runtime$config` writes the per-agent override and that persisted global in one call. No separate `:runtime-overrides` block exists. See the §7 as-built note.
2. **Should config-agent be able to install MCP servers** (npm, uv tool install, etc.) **on its own?** Today `mcp-agent` enumerates and connects but does not install. If config-agent can shell out to install, it shortens the "add server" path; if not, it has to tell the user "now run `npm i -g @linear/mcp-server` and come back." Lean: install with explicit per-package confirmation (mirrors bootstrap's Ollama-install policy).
3. **Snapshot retention policy** — 20 is arbitrary. Make it `:bootstrap.snapshot-retention` configurable?
4. **Does config-agent run inside `bb tui` or as its own entry point?** Currently the design assumes `bb tui run -a config-agent`. A dedicated `bb tui config --interactive` (different from the wizard) could be more discoverable. Bikeshed.
5. **How does config-agent surface `BRAINYARD.md`?** Today `BRAINYARD.md` is a long-form rules file the agent reads; users routinely edit it by hand. Should config-agent's writable surface include "edit BRAINYARD.md sections" via `edit-agent`? Probably yes — but the contract there is "edit the file" not "edit a structured config," so it's adjacent rather than core. Out of scope for revision 1.
