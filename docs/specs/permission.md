# Spec: Permissions

*Area code `PERM`. Covers the agent runtime's permission policy: the
permission **modes** and how they resolve, the **file-access gates**
(the read allow-list and the write always-allowed set), the
**interactive prompt** and its session/persisted grant caches, **bash /
`task$run` path validation**, the **tool-level** allow/deny scaffold,
the **MCP tool** fail-closed gate, and the **`:agent.tool-use/pre`** hook
seam. Human-in-the-loop *action* permissions (promise-based) live in the
[agent runtime](agent-runtime.md) spec (CR-RT-17) and are only
cross-referenced here.*

Status legend and contract-ID conventions: see [README](README.md).

> **Two independent axes.** *Mode* (`:auto-approve` / `:ask-each-time` /
> `:deny-by-default` / `:auto`) decides **what happens when a request
> reaches the permission-fn**. The *always-allowed set* (scratch dirs)
> decides **which requests never reach it at all**. A path is gated only
> if it is outside the always-allowed set; then the mode governs. Reads
> and writes use **different** always-allowed rules — see §3 vs §4.

---

## 1. Permission modes

| ID | Contract | Status | Source |
|---|---|---|---|
| CR-PERM-01 | The configured mode MUST live at `[:permissions :mode]`, bridged to the flat schema key `:permission-mode` (default `:auto`). | Implemented | `agent/core/config.clj` (`config-schema`, `bridge-permissions-section`); see CFG CR-CFG-07 |
| CR-PERM-02 | `resolve-permission-mode` MUST resolve `:permission-mode` to one of `:auto-approve` / `:ask-each-time` / `:deny-by-default`, defaulting nil/unknown to `:ask-each-time`. | Implemented | `config.clj` (`resolve-permission-mode`) |
| CR-PERM-03 | `:auto` MUST resolve to `:auto-approve` when a container (Docker/devcontainer) is detected via env-detect, else `:ask-each-time` — so a disposable env skips prompts but a bare host still prompts. | Implemented | `config.clj` (`resolve-permission-mode`, `container-detected?`) |

---

## 2. Mode → permission-fn resolution

The mode is applied by choosing **which** file/bash permission-fn is
handed to the path-validating tools.

| ID | Contract | Status | Source |
|---|---|---|---|
| CR-PERM-04 | `:auto-approve` MUST use `allow-all-permission-fn` (`{:allowed true}` with no prompt, even headless); `:deny-by-default` MUST use `deny-all-permission-fn` (refuse every non-always-allowed op). | Implemented | `config.clj` (`session-permission-fn`, `allow-all-permission-fn`, `deny-all-permission-fn`); `agent/common/tools.clj` (`get-permission-fn`) |
| CR-PERM-05 | Any other mode (`:ask-each-time`) MUST use the session's interactive `:permission-fn` (approved-dir cache + prompt). | Implemented | `config.clj` (`session-permission-fn`); `tools.clj` (`get-permission-fn`) |

---

## 3. Read gate — the allowed-dirs allow-list

| ID | Contract | Status | Source |
|---|---|---|---|
| CR-PERM-06 | `default-allowed-dirs` MUST contain `/tmp`, the JVM system temp dir (`java.io.tmpdir` — `/var/folders/.../T` on macOS), the resolved `:project-dir` (git-root), and the user-config dir (`~/.brainyard`). | Implemented | `config.clj` (`default-allowed-dirs`) |
| CR-PERM-07 | A file **read** / grep / glob whose absolute path is within any allowed-dir MUST be auto-allowed (no prompt); both target and allowed-dir MUST be canonicalized so the macOS `/var`→`/private/var` and `/tmp`→`/private/tmp` symlinks resolve. | Implemented | `agent/common/reference.clj` (`resolve-allowed-path`, `read-file-content`) |
| CR-PERM-08 | An absolute read path **outside** allowed-dirs MUST call the permission-fn (mode-gated); with no permission-fn it MUST be denied. A fallback-dir hit outside allowed-dirs MUST still be gated (not silently allowed). | Implemented | `reference.clj` (`read-file-content`, fallback-dirs envelope) |

The effective allowed-dirs (`config/allowed-dirs` → `get-config
:allowed-dirs`) merges the default with any persisted
`[:permissions :allowed-dirs]` entries (see §6).

---

## 4. Write gate — the always-allowed scratch set

Writes deliberately do **not** honor the full allowed-dirs list at the
gate (that would let project source bypass the mode). Instead a minimal
scratch set is always-allowed and everything else defers to the
permission-fn.

| ID | Contract | Status | Source |
|---|---|---|---|
| CR-PERM-09 | `validate-write-path` MUST always-allow (bypass permission-fn regardless of mode): `/tmp`, `/private/tmp`, the canonicalized system temp dir, and `<base-dir>/.brainyard/`. These are the agent's own scratch/artifact roots. | Implemented | `reference.clj` (`validate-write-path`) |
| CR-PERM-10 | A write to project source **outside** `.brainyard/` MUST fall through to `write-project-file`'s permission-fn, so it is gated on permission-mode (`:auto-approve` allows silently, `:ask-each-time` prompts, `:deny-by-default` denies). | Implemented | `reference.clj` (`write-project-file`, `validate-write-path`); `tools.clj` (`update-file`, `write-file` pass `get-permission-fn`) |
| CR-PERM-11 | The read/write asymmetry is by design: `:project-dir` is in the read allow-list (reads never prompt) but is NOT in the write always-allowed set (writes are mode-gated). | Implemented (by-design asymmetry) | `config.clj` (`default-allowed-dirs`) vs `reference.clj` (`validate-write-path`) |

---

## 5. Interactive permission-fn (`:ask-each-time`)

Built by `make-permission-fn`; the `:permission-fn` stored on the
session. Handles `:path`/`:paths` file-access requests at
**parent-directory granularity**.

| ID | Contract | Status | Source |
|---|---|---|---|
| CR-PERM-12 | If every request path's parent dir is in the session `!session-allowed` cache MUST auto-allow; if any is in `!session-denied` (a prior `:never`) MUST auto-deny — both without re-prompting. | Implemented | `agent-tui/permissions.clj` (`make-permission-fn`) |
| CR-PERM-13 | If every request path is within the effective `allowed-dirs` (incl. the default project-dir and any `/allow-path` additions) the fn MUST auto-allow WITHOUT prompting — mirroring the read gate. The `allowed-dirs` lookup MUST NOT throw out of the callback. | Implemented | `permissions.clj` (`within-allowed-dir?`, `make-permission-fn`) |
| CR-PERM-14 | Otherwise, in an interactive channel (raw in-stream or tmux popup), the fn MUST prompt with `yes` / `no` / `always` (remember parent dir → `!session-allowed`) / `never` (remember → `!session-denied`), with a 30 s timeout that denies. | Implemented | `permissions.clj` (`make-permission-fn`) |
| CR-PERM-15 | In a non-interactive channel (inline/piped, no input reader, no popup) the fn MUST auto-deny with a `/allow-path <dir>` hint. | Implemented | `permissions.clj` (`make-permission-fn`) |
| CR-PERM-16 | Grants are session-scoped and parent-directory-granular — an `:always` grant covers only the file's parent dir for the current session, not the whole project or future sessions. | Partial (documented limitation) | `permissions.clj` (`make-permission-fn`) |

CR-PERM-16 is the reason a *persisted* allowed-dir (§6) is the durable
way to silence writes across directories and sessions; the session
`:always` cache is a same-session convenience only.

---

## 6. Persisted allowed-dirs (`/allow-path`)

| ID | Contract | Status | Source |
|---|---|---|---|
| CR-PERM-17 | `/allow-path <dir>` MUST append `<dir>` to the agent's `allowed-dirs` and persist it via `set-allowed-dirs!` (per-agent override + `[:permissions :allowed-dirs]`). | Implemented | `permissions.clj` (`handle-allow-path-command`); `config.clj` (`set-allowed-dirs!`) |
| CR-PERM-18 | A persisted allowed-dir MUST silence BOTH the read gate (§3) and the interactive write prompt (§5, CR-PERM-13), so `/allow-path` grants are read+write and survive the session. | Implemented | `reference.clj` (`resolve-allowed-path`) + `permissions.clj` (`within-allowed-dir?`) |

---

## 7. Bash / `task$run` path validation

| ID | Contract | Status | Source |
|---|---|---|---|
| CR-PERM-19 | `resolve-agent-dirs` MUST return `{:base-dir :canonical-allowed :permission-fn}` where `:base-dir` is `project-dir` (git-root, not JVM cwd) and `:canonical-allowed` is the canonicalized union of base-dir and the agent's allowed-dirs. | Implemented | `config.clj` (`resolve-agent-dirs`) |
| CR-PERM-20 | The interactive permission-fn MUST accept a multi-path `:paths` request (the bash security check's shape) and apply the same allow/deny/prompt logic across all paths — allowing only when every path clears. | Implemented | `permissions.clj` (`make-permission-fn` `:paths` branch); `config.clj` (`resolve-agent-dirs` supplies `:canonical-allowed`) |

---

## 8. Tool-level and MCP permission

| ID | Contract | Status | Source |
|---|---|---|---|
| CR-PERM-21 | `call-tool` MUST consult `check-permission` (tool-name against `permission-config` `:deny`/`:allow`/`:approval` lists) before dispatch; `:denied` MUST short-circuit with a denial result. | Implemented (lists empty by default → all `:allowed`) | `tool.clj` (`call-tool`, `check-permission`, `permission-config`, `match-items`) |
| CR-PERM-22 | MCP tool calls MUST use a **fail-closed** permission gate: unless allow-listed (`:mcp-allow-tools`) or approved via `mcp-permission-confirm`, a call is denied — including a non-interactive auto-deny with an allowlist hint. | Implemented | `permissions.clj` (`mcp-permission-confirm`); `agent/mcp/permission.clj` |
| CR-PERM-23 | A `:agent.tool-use/pre` decision hook MUST be able to `:allow` / `:modify-args` / `:replace` / `:block` a tool call before it runs; `:block` MUST yield a synthetic blocked result and skip the body. | Implemented | `tool.clj` (`:agent.tool-use/pre` decision hook + synthetic blocked-result); used by mcp-agent's fail-closed gate |

CR-PERM-21 records that `permission-config` ships with empty
`:deny`/`:allow`/`:approval` lists — the tool-name gate is a live but
currently permissive scaffold; per-tool policy is expressed instead via
tool *visibility* (`:tool-use-control` allow/deny/hidden per agent) and
the `:agent.tool-use/pre` hook (CR-PERM-23).

---

## 9. Human-in-the-loop action permissions (cross-ref)

Distinct from file/tool access: an agent can request a typed *action*
permission that a human answers asynchronously.

| ID | Contract | Status | Source |
|---|---|---|---|
| CR-PERM-24 | Action permissions MUST use promises (`create-action-promise` / `deliver-action-response`) persisted under `[:runtime :action-permissions]`. | Implemented (see RT CR-RT-17) | `agent/core/runtime.clj` |
