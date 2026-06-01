# Update-Agent — Safe File Edits via Pattern Replacement and Syntax-Aware Rewrites (CoAct-derived)

> **Status:** Shipped — `update-agent` is registered in `components/agent` (`common/update_agent.clj`). This document is the original design proposal (revision 1); the shipped implementation may diverge in details. See [core/agent.md](../core/agent.md) for the current roster.
>
> **Revision 2 (rollback semantics):** ROLLBACK is now transaction-scoped — it restores the file's pre-APPLY bytes captured at PROBE time, not `git checkout -- <file>` against HEAD. VERIFY's diff-match check is also transaction-scoped, comparing pre-APPLY vs post-APPLY bytes via `git diff --no-index` rather than working-tree-vs-HEAD. Together this makes `:dirty-ok? true` safe for legitimate layered edits: a failed transaction undoes only its own change and leaves prior uncommitted state intact. The `:rollback` operator hint is `git checkout -- <target>` for clean edits and `cp -- '<backup>' '<target>'` for `:dirty-ok?` edits (backup written to `.brainyard/agents/update-agent/backups/<ts>-<slug>.bak`). The section-level references to `git checkout` below describe revision-1 behavior; treat them as historical.
> **Scope:** new `components/agent/src/ai/brainyard/agent/common/update_agent.clj`
> **Built on:** `coact_agent.clj` via `coact/run-coact-derived`
> **Sibling of:** `exec-agent` (workflow advancement), `explore-agent` (discovery)
> **Related reading:** `docs/CoAct.md`, `docs/explore-agent-design.md`, `docs/agent-design.md`, `components/agent/src/ai/brainyard/agent/common/tools.clj` (the `update-file` / `write-file` / `bash` deftools), `components/agent/src/ai/brainyard/agent/common/exec_agent.clj`

---

## 1. Motivation

The brainyard agent stack already has solid primitives for *making file changes*:

- `update-file` (`components/agent/.../common/tools.clj` line 430) — pattern→replacement (literal or regex), `:all?` toggle, returns `{:path :replaced :diff :diff-source}` with a real `git diff --no-index` payload (or a fallback line-by-line diff when git is absent).
- `write-file` — overwrite or append; auto-allows `/tmp/*` and `<base>/.brainyard/*`, prompts for permission elsewhere.
- `bash` — one shell command, 30s default; the natural carrier for `git status`, `git diff`, `git log`, `clj-kondo`, formatters, and per-language compilers.
- `read-file` / `grep` — for anchoring and verification.

What is missing is a **policy layer** that knows how to use these tools *safely and reproducibly*. Today an agent that wants to "rename `foo` to `bar` in `core.clj`" can technically do it with one `update-file` call — but in practice the LLM frequently:

1. Picks a pattern that matches in 7 places when only 1 was intended.
2. Edits a file that already has uncommitted changes, blending its work with whatever the user was mid-way through.
3. Ships an edit that breaks parens / quotes / Python indentation, then has no rollback path because the change has already overwritten the file.
4. Reports "done" without ever re-reading the file or re-running `git diff` to confirm the change actually landed the way it claims.

`exec-agent` performs whole *workflows* (drives a todo list to completion), but its execution loop currently relies on the same raw tool surface — the same hazards apply when an exec item happens to be "edit file X". Pulling the **safe-edit policy out into a focused specialist** lets exec-agent (and `coact-agent`, `plan-agent`, the user directly) `(call-tool "update-agent" {…})` and get a deterministic safety pipeline around every write.

**Thesis.** Add a CoAct-derived `update-agent` that:

1. **Wraps `update-file` and `write-file` with a fixed safety pipeline** — pre-flight git status + anchor proof, apply, post-flight diff sanity + optional lint/test, rollback on verification failure.
2. **Supports two edit modes** — *pattern* (delegated to `update-file`) and *syntax-aware* (probe-anchor → re-emit a whole form via `write-file`). Picks the mode per-edit instead of forcing the caller to choose.
3. **Persists every applied edit** under `.brainyard/agents/update-agent/edits/` as a markdown record with the diff, verification result, and the exact `git checkout` command needed to undo it. Same handoff contract as `explore-agent` — emits `Saved edit: <path>` so downstream agents (and humans) can audit or revert mechanically.
4. **Inherits the CoAct loop, sandbox, router, and accumulator** from `coact-agent` via `run-coact-derived`. No new BT, no new DSPy signature, no new sandbox bindings required for the baseline.
5. **Is a *flat* call-tool target** for `exec-agent` / `plan-agent` / users. Not a replacement for them — a specialist they delegate to for the act of writing.

The whole feature ships as one new agent file plus an optional small helpers namespace (`update.clj`).

---

## 2. Design Principles

1. **Verify before, verify after.** Every edit is bracketed by a read. Pre-flight: confirm pattern matches the expected number of times AND the file has the expected git state. Post-flight: re-read the file and re-run `git diff` to confirm the on-disk content matches the diff `update-file` returned.
2. **Refuse to edit dirty files by default.** If `git status --porcelain -- <target>` shows uncommitted changes, STOP and surface them. The user can override per-turn with `:dirty-ok? true`. Reason: rollback (§5) only works cleanly when the pre-edit baseline was a committed state.
3. **One edit per iteration, one file per edit.** No batched multi-file writes inside a single iteration. The agent loops; each loop step is a complete probe→apply→verify→record cycle. Multi-file refactors are sequences of single edits, each independently revertible.
4. **`update-file` first, `write-file` only when forced.** `update-file` already produces a diff and avoids accidentally truncating the rest of the file. Reach for `write-file` only when the edit cannot be expressed as a single pattern (whole-form rewrites, cross-line restructuring, fresh files).
5. **Anchored patterns, not blind regexes.** Before any `update-file` call, the agent runs `grep` (or a line-range `read-file`) to confirm the pattern matches *exactly the expected number of occurrences*. A pattern that matches 7 times when `:all? false` is a refusal, not a guess.
6. **Rollback is a first-class output.** Every persisted edit record includes the literal `git checkout -- <relative-path>` command (and, for new files, `rm <relative-path>`) needed to undo it. Surfaced in the answer as a `Rollback: <cmd>` line so a downstream agent or the user can revert without parsing.
7. **No bash escape hatch for writes.** The agent is forbidden from using `bash` to invoke `cat > file`, `sed -i`, `tee`, or any other write-side shell command. Bash is read-only here (`git status`, `git diff`, `git log`, `clj-kondo`, formatters, `wc`, `find`). All writes go through `update-file` / `write-file` so the diff/permission/audit pipeline runs uniformly.
8. **No `git commit`, `git reset --hard`, `git push`, `git stash drop` from bash.** Verifiable via the `git ` prefix. The agent stages and proposes; the *user* commits. Non-destructive history operations (`git stash` for in-progress dirty content, `git stash pop`) are allowed only when the user explicitly opts in via `:dirty-ok? :stash`.
9. **Out-of-tree paths require explicit confirmation.** Any path outside the project root, or anything under `.git/`, is refused. `/tmp/*` is allowed but never persisted into the edit record (transient by definition).
10. **No clone-self recursion.** Like `explore-agent` and `rlm-agent`, `update-agent` excludes `query$clone` from its tool roster. Cross-agent dispatch via `(call-tool "<other-agent>" …)` is fine — `query$clone` clones THIS agent and is forbidden.

---

## 3. Position in the Agent Stack

```
coact-agent        (parent — full BT, sandbox, router, accumulator)
  ├─ explore-agent   (read-mostly multi-surface discovery)
  ├─ plan-agent      (planning)
  ├─ todo-agent      (todo authoring)
  ├─ exec-agent      (drives a todo list to completion)
  ├─ eval-agent      (evaluates exec results)
  ├─ rlm-agent       (MapReduce over large filesystem context)
  ├─ mcp-agent       (MCP lifecycle + write ops)
  ├─ skill-agent     (skill authoring)
  └─ update-agent    (NEW — safe single-file edits)
```

`update-agent` does **not** replace any existing agent. It is the specialist the others delegate to when an action requires *changing a file*:

| Question / Task                                                           | Use            | Why                                              |
| ------------------------------------------------------------------------- | -------------- | ------------------------------------------------ |
| "Where is the loop guard implemented?"                                    | explore-agent  | Read-only discovery.                             |
| "Plan how to rename the loop guard hook to `:agent.tool-use/intercept`."  | plan-agent     | Planning, no edits.                              |
| "Apply that plan."                                                        | exec-agent     | Drives the todo; delegates each edit.            |
| "Rename `loop-guard-hook` → `intercept-hook` in `loop_guard_hook.clj`."   | **update-agent** | Single-file edit with anchored pattern.          |
| "Rewrite the entire `instruction` string in `explore_agent.clj` to add a new section." | **update-agent** | Whole-form syntax-aware rewrite (§4.2).         |
| "Create new file `components/agent/src/.../update_agent.clj`."            | **update-agent** | New-file write (still goes through the pipeline). |
| "Refactor 12 files to use the new namespace." | exec-agent → update-agent (looped) | exec-agent owns the loop; update-agent owns each individual edit. |

Rule of thumb: **`update-agent` owns the act of writing**. Anything that produces a `git diff` line on a tracked file should flow through it.

---

## 4. Edit Modes

`update-agent` recognizes two edit modes. The mode is decided by the agent per-edit, not by the caller.

### 4.1 Pattern Mode (preferred)

Direct call to `update-file` with a literal or regex pattern. The vast majority of edits fall here:

- Single-line replacements (rename, retype, retag).
- Multi-line replacements where the boundaries are unambiguous (insert a docstring above a `defn`, swap a `:require` form, change a config value).
- Repeated rewrites with `:all? true` when every match really is intended.

Pre-flight specifically for pattern mode:

1. `grep` (or `bash "rg --count-matches"`) on the file with the literal pattern (or its de-anchored prefix when regex). The reported count must equal: `1` when `:all? false`, otherwise N (the agent commits to a number before the call).
2. `read-file` with `:lines` around each match, sampled if N > 3, to confirm the surrounding context is what the agent expected.
3. If either check fails — pattern not found, found-too-many, or context-mismatch — STOP. Re-anchor with a longer pattern that includes more context, or escalate to syntax-aware mode.

The actual call:

```clojure
(call-tool "update-file"
           {:path        "components/agent/src/.../loop_guard_hook.clj"
            :pattern     "loop-guard-hook"
            :replacement "intercept-hook"
            :all?        true})
```

`update-file` returns `{:path :replaced :diff :diff-source}` — the diff goes verbatim into the persisted record (§6).

### 4.2 Syntax-Aware Mode

Used when the edit cannot be expressed as a pattern: whole-form rewrites, cross-line restructuring, when the pattern would match too much, or when the file has multiple structurally-similar candidates and the agent needs to pick one.

The pipeline:

1. `read-file` the *enclosing form* with `:lines` (Clojure: paren-balanced top-level form; Python: indented block; JSON/YAML: matched braces). The agent computes the line range either by:
   - `bash "rg -n '^\\(defn?-? <name>' <file>"` for top-level Clojure forms (cheap, robust).
   - `bash "awk …"` or `bash "clj-kondo --lint --analysis <file>"` for harder cases.
   - In rare cases, a future `update$find-form` helper (§9) backed by `rewrite-clj`.
2. Construct the rewritten form *as a complete string* in the sandbox. The agent must `(println rewritten)` first so the diff (vs the originally-read region) is visible in the iteration record.
3. `update-file` with the *original region* as `:pattern` (literal) and the rewritten region as `:replacement`, `:all? false`. This delegates the actual write through the same diff-producing path as pattern mode — **the agent does NOT call `write-file` for in-place edits**.
4. The only legitimate `write-file` use is **fresh-file creation** (the file did not exist pre-flight). For those, `bash "test -f <file> && echo exists"` is the pre-flight; if it exists, the edit must go through `update-file` instead.

This means the *post-flight* path is identical for both modes (one `git diff` to verify), and the rollback command is uniform (`git checkout --` for tracked files, `rm` for newly-created ones).

### 4.3 Mode Selection Heuristic

```text
Can the edit be expressed as a single literal/regex pattern that
matches exactly the intended N occurrences?
  YES → Pattern mode.
  NO  → Read the enclosing form, rewrite it as a string, then issue
        update-file with the whole region as pattern (Syntax-aware mode).

Does the file already exist?
  YES → Edit goes through update-file (either mode).
  NO  → write-file (single allowed direct-write path), with the same
        record + rollback bookkeeping (rollback = rm).
```

---

## 5. Safety Pipeline

The pipeline runs around every edit, regardless of mode. It is encoded in the instruction (§7) as a checklist the agent walks per iteration; it is also reified as the optional `update$apply` helper (§9) when the helpers namespace is bound.

### 5.1 Pre-flight (PROBE)

| Step | Command                                                              | Pass criterion                                                            | On fail                                  |
| ---- | -------------------------------------------------------------------- | ------------------------------------------------------------------------- | ---------------------------------------- |
| P1   | `bash "git status --porcelain -- <target>"`                          | Empty output (file is clean) OR `:dirty-ok? true` set                     | STOP, surface `git status` to user.      |
| P2   | `bash "git log --oneline -n 5 -- <target>"`                          | Always passes — informational. Recorded in the edit record.               | n/a                                      |
| P3   | `read-file <target>` (header + `wc -l`)                              | File exists; size sane; encoding readable.                                | STOP, report read error.                 |
| P4   | `grep` / `rg --count-matches` for the pattern (pattern mode only)    | Match count equals the agent's pre-declared expectation (1 or N).         | STOP, re-anchor or escalate to syntax mode. |
| P5   | (Syntax-aware mode) `read-file` of the enclosing region              | Region content matches the literal `:pattern` the agent will pass next.   | STOP, recompute the region.              |

Pre-flight outputs a small map the agent stores under a `def` so the verification step can refer back to it:

```clojure
(def pre {:target "<path>"
          :head-rev (-> (bash "git rev-parse HEAD") :output str/trim)
          :recent (-> (bash "git log --oneline -n 5 -- <target>") :output)
          :match-count <N>
          :region-lines [start end]      ; syntax-aware mode only
          :region-sha (sha256 region)})  ; syntax-aware mode only
```

### 5.2 Apply (WRITE)

Exactly one of:

- `(call-tool "update-file" {:path … :pattern … :replacement … :all? …})`
- `(call-tool "write-file"  {:path … :content …})` — only for fresh files.

Returns the diff. The agent stores it under `(def post {…})` for verification.

### 5.3 Post-flight (VERIFY)

| Step | Command                                                                        | Pass criterion                                                                                                                          | On fail                                                                                              |
| ---- | ------------------------------------------------------------------------------ | --------------------------------------------------------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------- |
| V1   | `bash "git diff --no-color -- <target>"`                                       | Diff equals the `:diff` returned by `update-file` (modulo trailing whitespace). Detects external interference / partial writes / encoding drift. | ROLLBACK (§5.4).                                                                                     |
| V2   | (Pattern mode) `grep` for the OLD pattern                                      | Zero matches when `:all? true`; otherwise `original-count - 1` matches.                                                                  | ROLLBACK and report.                                                                                 |
| V3   | (Pattern mode) `grep` for the NEW pattern                                      | At least one match.                                                                                                                     | ROLLBACK.                                                                                            |
| V4   | (Optional) Linter call per file extension                                      | clj-kondo for `.clj`/`.cljs`/`.cljc`; `python -m py_compile` for `.py`; `jq empty <file>` for `.json`; `yamllint` for `.yaml`; etc.    | If lint fails AND the agent did not declare `:lint-ok-to-fail? true` → ROLLBACK. Otherwise warn.    |
| V5   | (Optional, opt-in) `bb test:component <name>` if `:run-tests? true`           | Exit 0.                                                                                                                                 | ROLLBACK and report failing tests.                                                                   |

V4 toolchain detection: the agent runs `bash "command -v <linter>"` once and skips the linter if absent. Skipping is recorded in the edit record so reviewers can spot it.

### 5.4 Rollback (REVERT)

Triggered automatically on V1/V2/V3/V5 failure (V4 is opt-in per above).

For a tracked file that existed pre-flight:

```bash
git checkout -- <relative-path>
```

For a newly-created file (fresh-file `write-file`):

```bash
rm -- <relative-path>
```

Rollback is performed via `bash`. After rollback the agent re-runs `git status -- <target>` to confirm clean. The rollback path *also* lands in the persisted record so a future agent can see what was attempted, what failed, and that the workspace was restored.

If rollback itself fails — extremely rare, e.g. permission denied — the agent STOPS, surfaces both errors to the user, and refuses any further iterations until the user resolves the workspace. **Never** issue a second corrective edit while a previous edit is in an unknown state.

---

## 6. Output Discipline — Persistence in `.brainyard/agents/update-agent/`

Same shape as `.brainyard/agents/explore-agent/` (see `docs/explore-agent-design.md` §5), tuned for edits.

### 6.1 Directory Layout

```
.brainyard/
├── update-agent/
│   ├── edits/                     ; permanent — the edit-record corpus
│   │   ├── 20260510-104503-rename-loop-guard-hook.md
│   │   ├── 20260510-104612-add-mode-flag-to-explore.md
│   │   └── 20260510-110131-create-update-agent-clj.md
│   ├── drafts/                    ; per-turn scratch
│   ├── INDEX.md                   ; one line per edit, newest first
│   └── README.md                  ; layout cheat-sheet
```

### 6.2 Edit-Record Layout

Every applied edit produces a markdown file with YAML frontmatter:

```markdown
---
slug: rename-loop-guard-hook
question: "Rename `loop-guard-hook` → `intercept-hook` in loop_guard_hook.clj"
created: 2026-05-10T10:45:03Z
agent: update-agent
mode: pattern                       # pattern | syntax | new-file
target: components/agent/src/ai/brainyard/agent/common/loop_guard_hook.clj
pre:
  head_rev: 4f2a91b
  status:   clean
  recent:
    - "4f2a91b refactor hooks registry"
    - "1c3d8e0 add :agent.tool-use/pre"
apply:
  pattern:     "loop-guard-hook"
  replacement: "intercept-hook"
  regex:       false
  all:         true
  replaced:    7
verify:
  diff_match:  true
  old_pattern_count_after: 0
  new_pattern_count_after: 7
  lint:        {tool: "clj-kondo", exit: 0}
  tests:       skipped
rollback: "git checkout -- components/agent/src/ai/brainyard/agent/common/loop_guard_hook.clj"
turn_id: <id>
session_id: <id>
---

# Rename `loop-guard-hook` → `intercept-hook`

## What changed
Renamed the symbol in 7 places inside `loop_guard_hook.clj` …

## Diff
```diff
@@ -23,4 +23,4 @@
-(defn loop-guard-hook
+(defn intercept-hook
@@ -88,1 +88,1 @@
-(register! :agent.tool-use/pre #'loop-guard-hook)
+(register! :agent.tool-use/pre #'intercept-hook)
```

## Verification
- diff matches update-file output ✅
- old symbol is gone (0 matches) ✅
- new symbol appears (7 matches) ✅
- clj-kondo: clean ✅
- tests: skipped (`:run-tests? false`)

## Rollback
```
git checkout -- components/agent/src/ai/brainyard/agent/common/loop_guard_hook.clj
```
```

Frontmatter contract (downstream parsers may rely on these):

| Key                | Type                                  | Description                                                                                          |
| ------------------ | ------------------------------------- | ---------------------------------------------------------------------------------------------------- |
| `slug`             | string                                | Kebab-case, deterministic from the request. Suffix `-2/-3/...` on collision.                          |
| `question`         | string                                | Verbatim user / caller request.                                                                       |
| `created`          | ISO-8601 string                       | UTC timestamp.                                                                                        |
| `agent`            | string                                | Always `update-agent`.                                                                                |
| `mode`             | enum                                  | `pattern` \| `syntax` \| `new-file`.                                                                  |
| `target`           | string                                | Repo-relative path of the edited file.                                                                |
| `pre.head_rev`     | string                                | Output of `git rev-parse HEAD` at probe time.                                                          |
| `pre.status`       | enum                                  | `clean` \| `dirty` (only `dirty` when `:dirty-ok?` was set).                                          |
| `pre.recent`       | vector of strings                     | First 5 lines of `git log --oneline -- <target>`.                                                     |
| `apply.*`          | mode-specific                         | `pattern`/`replacement`/`regex`/`all`/`replaced` for pattern; `region_lines`/`region_sha` for syntax. |
| `verify.diff_match`| bool                                  | Whether the post-flight `git diff` equaled `update-file`'s returned diff.                            |
| `verify.lint`      | `{:tool :exit}` map or `nil`          | Lint outcome (skipped → omitted).                                                                     |
| `verify.tests`     | `:skipped` \| `{:exit :report-path}`  | Test outcome.                                                                                         |
| `rollback`         | string                                | Literal shell command to undo this edit. Single line, copy-paste safe.                                |
| `turn_id`/`session_id` | string                            | For trajectory cross-reference.                                                                       |

### 6.3 INDEX.md Format

Newest-first prepend, one line per edit:

```markdown
- 2026-05-10 10:45 [rename-loop-guard-hook](edits/20260510-104503-rename-loop-guard-hook.md) — pattern · `loop_guard_hook.clj` · 7 replaced · ✅
- 2026-05-10 10:46 [add-mode-flag-to-explore](edits/20260510-104612-add-mode-flag-to-explore.md) — syntax · `explore_agent.clj` · ✅
- 2026-05-10 11:01 [create-update-agent-clj](edits/20260510-110131-create-update-agent-clj.md) — new-file · `update_agent.clj` · ✅
```

The trailing `✅`/`❌` indicates whether verification passed (a rollback was performed if `❌`). The append rule mirrors explore-agent's: read-first-200-lines + prepend + write-back.

### 6.4 ANSWER Format

Two stable lines at the end of the agent's `answer`:

```
Saved edit: .brainyard/agents/update-agent/edits/<file>.md
Rollback: git checkout -- <relative-path>
```

`Saved edit:` and `Rollback:` are the contract prefixes. Downstream agents (and the user) grep for them mechanically. Missing either line on a successful edit is a bug.

For verification failures, the answer ends with:

```
Saved edit: .brainyard/agents/update-agent/edits/<file>.md
Rolled back: <reason>
```

So `Saved edit:` is always present (the record exists for failed attempts too); `Rollback:` is replaced by `Rolled back:` to flag that the workspace is *already* restored.

---

## 7. Instruction (System Prompt Body)

Layered on top of `coact-agent`'s instruction by `run-coact-derived`.

```text
You are an UPDATE-agent. You change a single file safely: probe before, write
once, verify after, persist a record, and emit an exact rollback command.
You NEVER change more than one file per iteration. You NEVER use bash to
write to files. You NEVER commit, reset --hard, push, or drop stashes.

────────────────────────────────────────────────────────────────────────────
EDIT MODES (pick per-edit; do not ask the caller)
────────────────────────────────────────────────────────────────────────────
PATTERN  — single literal/regex pattern → replacement via update-file.
           Pick this when you can pre-state how many matches there are and
           every one of them is the intended target. (~80% of edits.)
SYNTAX   — read the enclosing form, rewrite it as a complete string,
           call update-file with the WHOLE region as :pattern. Use this
           when no pattern fits, or when "the symbol named X near line N"
           is ambiguous as a regex.
NEW-FILE — write-file for a path that does NOT exist pre-flight. The only
           legitimate direct write-file path. Pre-flight must include
           `bash "test -f <path> && echo exists"` returning empty.

If you cannot decide — default to PATTERN. If pre-flight P4 (match-count)
fails, escalate to SYNTAX.

────────────────────────────────────────────────────────────────────────────
SAFETY PIPELINE — every iteration follows this exact sequence
────────────────────────────────────────────────────────────────────────────
PROBE
  P1. bash "git status --porcelain -- <target>"
      → MUST be empty. If not empty AND :dirty-ok? is not set, STOP and
        surface the dirty state to the user.
  P2. bash "git log --oneline -n 5 -- <target>"
      → Informational only. Stash in `pre`.
  P3. read-file <target> (head + `bash "wc -l <target>"`).
      → Confirm size + encoding sane.
  P4. (PATTERN mode) Pre-state how many matches you expect, then verify:
      bash "rg --count-matches --fixed-strings '<pattern>' <target>"
      The reported count MUST equal: 1 if :all? false; N if :all? true.
      → Mismatch → STOP, re-anchor with a longer pattern, or escalate
        to SYNTAX mode.
  P5. (SYNTAX mode) Read the enclosing form via `read-file :lines [s e]`.
      Confirm the region you will pass as :pattern matches the file
      content byte-for-byte. Compute (sha256 region) and stash it.

  Stash everything in (def pre {…}) so verify can refer back.

APPLY  (exactly ONE call)
  PATTERN  → (call-tool "update-file" {:path … :pattern … :replacement …
                                       :regex? … :all? …})
  SYNTAX   → (call-tool "update-file" {:path … :pattern <region>
                                       :replacement <new-region>
                                       :all? false})
  NEW-FILE → (call-tool "write-file" {:path … :content …})

  Stash returned diff in (def post {…}).

VERIFY
  V1. bash "git diff --no-color -- <target>"
      → MUST equal post's :diff (modulo trailing whitespace).
  V2. (PATTERN) bash "rg --count-matches --fixed-strings '<old-pattern>' <target>"
      → MUST be 0 (when :all? true) or original-count-1 (when :all? false).
  V3. (PATTERN) bash "rg --count-matches --fixed-strings '<new-pattern>' <target>"
      → MUST be ≥ 1.
  V4. Lint (skip silently if tool absent):
        .clj/.cljs/.cljc → clj-kondo --lint <target>
        .py              → python -m py_compile <target>
        .json            → jq empty <target>
        .yaml/.yml       → yamllint <target>
        others           → skip
      → Non-zero exit AND :lint-ok-to-fail? not set → ROLLBACK.
  V5. Tests (only when :run-tests? true):
        bb test:component <inferred-component>
      → Non-zero exit → ROLLBACK.

ROLLBACK  (only on V1–V5 failure)
  Tracked file: bash "git checkout -- <relative-path>"
  New file:     bash "rm -- <relative-path>"
  Then re-run P1 to confirm clean. If clean, persist the record with
  ✅:false and a `Rolled back: <reason>` answer line.
  If rollback ITSELF fails, STOP completely. Do NOT issue another edit.

PERSIST
  Build the YAML frontmatter (see docs/update-agent-design.md §6.2 for the
  full schema). Write to .brainyard/agents/update-agent/edits/<ts>-<slug>.md via
  write-file. Prepend a one-line entry to .brainyard/agents/update-agent/INDEX.md.

  When the update$* helpers are bound, this collapses to:
    (def slug (:slug (update$slug :request "<verbatim request>")))
    (def fm   (:frontmatter (update$frontmatter
                              :request "<verbatim>" :mode :pattern
                              :target "<path>" :pre pre :apply apply :verify verify
                              :rollback "<cmd>")))
    (def res  (update$write :slug slug :content (str fm body)))
    (update$index-append :path (:path res) :slug (:slug res)
                         :mode :pattern :target "<path>" :ok? true
                         :summary "<one-line>")

ANSWER
  - Inline summary: what changed, mode, replaced count, verification result.
  - Final lines (stable prefixes):
        Saved edit: <path returned by update$write>
        Rollback: <git checkout … OR rm … command>
    On rollback, replace `Rollback:` with `Rolled back: <reason>`.

────────────────────────────────────────────────────────────────────────────
HARD RULES
────────────────────────────────────────────────────────────────────────────
1. NO bash write-side commands. Forbidden: `cat > …`, `tee`, `sed -i`,
   `awk -i inplace`, `>` redirection to a file path, `truncate`, `mv` to
   replace a file. All writes go through update-file or write-file.
2. NO git history mutation from bash. Forbidden: `git commit`, `git reset
   --hard`, `git reset --soft`, `git push`, `git rebase`, `git checkout
   <branch>`, `git stash drop`, `git clean -f`. The only allowed git
   write is `git checkout -- <file>` and ONLY as part of a rollback.
3. NO multi-file edits per iteration. One file per loop pass. The caller
   is responsible for looping over multiple files (exec-agent does this).
4. NO query$clone. Cross-agent dispatch via (call-tool "<agent>" …) is
   fine; cloning yourself is not.
5. NO editing inside .git/, node_modules/, target/, .clj-kondo/.cache/,
   or any path resolving outside the project root. Refuse and report.
6. NO retry-without-rollback. If APPLY succeeds but VERIFY fails, you
   MUST roll back before proposing a corrective edit. Never patch on top
   of an unverified state.
7. CLEAN-FIRST. Files with uncommitted changes are refused unless the
   caller explicitly passes :dirty-ok? true (or :dirty-ok? :stash, which
   triggers a one-shot `git stash push -- <target>` BEFORE PROBE and a
   `git stash pop` AFTER PERSIST). Without a clean baseline, rollback is
   not a guarantee.

────────────────────────────────────────────────────────────────────────────
INPUTS YOU WILL RECEIVE
────────────────────────────────────────────────────────────────────────────
:question        — The edit request. Free-form. Examples:
                   "Rename foo→bar in src/x.clj"
                   "Add a docstring to defn quux in core.clj"
                   "Create components/agent/src/.../update_agent.clj
                    with the skeleton from docs/update-agent-design.md."
:agent-context   — Optional. Often a `Saved exploration: <path>` from
                   explore-agent or a plan path from plan-agent. If
                   present, read its frontmatter first to learn the
                   target file(s) and constraints — do NOT re-discover.
:dirty-ok?       — Optional. true | false | :stash. Default false.
:run-tests?      — Optional. Default false. When true, V5 runs.
:lint-ok-to-fail? — Optional. Default false. When true, V4 warns instead
                   of rolling back.
```

---

## 8. Tool-Context (How to Use the Bound Tools)

```text
## Update Tools — by category

### File I/O (the only writes you may issue)
- update-file    -- Pattern→replacement with diff. Args: path, pattern,
                    replacement, regex?, all?. Returns {:path :replaced
                    :diff :diff-source}. /tmp + .brainyard/ auto-allowed;
                    other paths prompt for permission. THIS IS YOUR
                    PRIMARY WRITE TOOL.
- write-file     -- Overwrite/create. Args: path, content, append. ONLY
                    for new-file mode (file did not exist pre-flight).
                    NEVER for in-place edits — use update-file with the
                    whole region as pattern instead.

### Reads + probes (use freely)
- read-file      -- Args: path, offset, limit, lines.
- grep           -- Args: pattern, path, include-exts, max-results,
                    recursive.
- bash           -- One shell command, 30s default. Use for the read-only
                    git commands and linters listed below. Forbidden uses
                    are enumerated in HARD RULES §1–§2.

### Allowed bash invocations (read-only)
- git status --porcelain -- <path>
- git log --oneline -n N -- <path>
- git diff [--no-color] -- <path>
- git rev-parse HEAD
- git ls-files <path>
- git checkout -- <path>            (ROLLBACK only)
- git stash push -- <path>          (only when :dirty-ok? :stash)
- git stash pop                     (only when :dirty-ok? :stash)
- rg --count-matches …
- rg -n <pattern> <file>
- wc -l <file>
- test -f <path> && echo exists
- command -v <tool>
- clj-kondo --lint <file>
- python -m py_compile <file>
- jq empty <file>
- yamllint <file>
- bb test:component <name>          (only when :run-tests? true)
- rm -- <path>                      (ROLLBACK of new-file only)

ANY bash command not on this list requires the agent to PAUSE and ask
the user. The list is a positive allowlist; if in doubt, ask.

### Synthesis
- query$llm      -- Sub-LLM call for, e.g., generating a docstring or
                    formatting a region before re-emitting it. FLAT only
                    (no query$clone — Hard Rule 4).

### Cross-agent dispatch (sparingly)
- call-tool "explore-agent" {…}     — when the request lacks the target
                                       file path and you'd rather have
                                       explore-agent locate it than do
                                       discovery yourself.
- call-tool "plan-agent" {…}        — when the edit is one of many that
                                       should be planned out before
                                       applying. Common pattern: STOP,
                                       hand back to plan-agent.

### Bookkeeping
- list-tools, get-tool-info, call-tool — generic registry access.

### Persistence helpers (`update$*` — auto-bound when present)
- update$slug            — Deterministic slug from request text.
- update$frontmatter     — Build YAML frontmatter from a metadata map.
- update$write           — Write the record to .brainyard/agents/update-agent/
                           edits/ (auto-suffix on slug collision).
- update$index-append    — Prepend a line to INDEX.md.
- update$apply           — One-call pipeline wrapper:
                             (update$apply :request … :target …
                                           :mode :pattern :pattern …
                                           :replacement … :all? …
                                           :dirty-ok? false :run-tests? false)
                           Returns {:path :ok? :rollback :diff :verify}.
                           Performs the FULL pipeline (probe→apply→verify
                           →persist→rollback-on-fail) atomically. Prefer
                           this over assembling the steps inline once the
                           helpers ship.

### Typical end-to-end flow
1. Parse :question and :agent-context. Identify target + intent.
2. PROBE (P1–P5).
3. APPLY (one update-file or write-file call).
4. VERIFY (V1–V3 always; V4 if linter present; V5 if :run-tests?).
5. ROLLBACK if any verify step fails.
6. PERSIST the record + INDEX line.
7. ANSWER with `Saved edit:` and (`Rollback:` | `Rolled back:`) lines.
```

---

## 9. Behavior Tree — Inherited As-Is

`update-agent` does **not** define its own BT. `run-coact-derived` falls back to `coact-agent`'s `:bt-factory`. The pipeline (§5) is a *prompt* contract, not a BT-level contract — this is intentional. Reasons:

1. The pipeline is short enough (3 phases, < 10 steps) to fit cleanly in iteration shape without tree gymnastics.
2. Encoding it as a BT would couple update-agent's safety policy to the BT runtime; future tweaks to the policy would force runtime changes.
3. The same shape applies whether the LLM uses tool-call channel, code channel, or both — letting CoAct's router decide is strictly better than pre-committing to one.

Iteration shape for a typical single edit:

| Iter  | Channel       | Body                                                                                         |
| ----- | ------------- | -------------------------------------------------------------------------------------------- |
| 1     | code          | PROBE: P1–P3, plus P4 (pattern) or P5 (syntax). `def pre`.                                   |
| 2     | tool / code   | APPLY: one `update-file` (or `write-file`) call. `def post`.                                 |
| 3     | code          | VERIFY: V1–V3 (+ V4/V5 if enabled). On failure → ROLLBACK + record `:ok? false`.             |
| 4     | code          | PERSIST: build frontmatter, `update$write`, `update$index-append`.                           |
| 5     | answer        | Inline summary + `Saved edit: <path>` + (`Rollback: …` or `Rolled back: …`).                 |

For trivially small edits (e.g. typo fix in a doc file with no linter), iterations 1–2 collapse if the LLM bundles probe + apply in a single code fence.

---

## 10. Demonstration: "Rename `loop-guard-hook` to `intercept-hook` in `loop_guard_hook.clj`"

Pattern-mode edit, all occurrences.

### Iteration 1 — PROBE (code channel)

````markdown
```clojure
(def target "components/agent/src/ai/brainyard/agent/common/loop_guard_hook.clj")

;; P1 — clean?
(def status (-> (call-tool "bash"
                  {:command (str "git status --porcelain -- " target)})
                :output))

;; P2 — recent history (informational)
(def recent (-> (call-tool "bash"
                  {:command (str "git log --oneline -n 5 -- " target)})
                :output))

;; P3 — read header for sanity
(def head (call-tool "read-file" {:path target :lines [1 30]}))

;; P4 — match count for the literal pattern. We expect ALL of them to
;; be the rename target, so :all? true and we record the count up-front.
(def n-matches
  (-> (call-tool "bash"
        {:command (str "rg --count-matches --fixed-strings 'loop-guard-hook' " target)})
      :output clojure.string/trim Integer/parseInt))

(def pre {:target target
          :head-rev (-> (call-tool "bash" {:command "git rev-parse HEAD"})
                        :output clojure.string/trim)
          :status (if (clojure.string/blank? status) :clean :dirty)
          :recent (clojure.string/split-lines recent)
          :match-count n-matches})

(println pre)
```
````

If `:status` is `:dirty` and `:dirty-ok?` is not set, the agent STOPS in iteration 2 with a `Refusing — uncommitted changes:\n<git status output>` answer. Otherwise:

### Iteration 2 — APPLY (tool channel)

```clojure
(def post
  (call-tool "update-file"
             {:path        target
              :pattern     "loop-guard-hook"
              :replacement "intercept-hook"
              :all?        true}))

(println post)
;; => {:path "<abs>" :replaced 7 :diff "<unified diff>" :diff-source "git"}
```

### Iteration 3 — VERIFY (code channel)

````markdown
```clojure
;; V1 — does the on-disk diff equal what update-file said it would?
(def disk-diff
  (-> (call-tool "bash" {:command (str "git diff --no-color -- " target)})
      :output))
(def diff-match? (= (clojure.string/trim disk-diff)
                    (clojure.string/trim (:diff post))))

;; V2 — old symbol gone?
(def old-after
  (-> (call-tool "bash"
        {:command (str "rg --count-matches --fixed-strings 'loop-guard-hook' " target " || true")})
      :output clojure.string/trim))

;; V3 — new symbol present?
(def new-after
  (-> (call-tool "bash"
        {:command (str "rg --count-matches --fixed-strings 'intercept-hook' " target)})
      :output clojure.string/trim Integer/parseInt))

;; V4 — clj-kondo if available
(def kondo
  (let [present? (-> (call-tool "bash" {:command "command -v clj-kondo"})
                     :exit-code zero?)]
    (when present?
      (call-tool "bash" {:command (str "clj-kondo --lint " target)}))))

(def verify {:diff-match diff-match?
             :old-pattern-count-after (Integer/parseInt (or old-after "0"))
             :new-pattern-count-after new-after
             :lint (if kondo
                     {:tool "clj-kondo" :exit (:exit-code kondo)}
                     :skipped)
             :tests :skipped
             :ok? (and diff-match?
                       (zero? (Integer/parseInt (or old-after "0")))
                       (>= new-after 1)
                       (or (= :skipped (if kondo nil :skipped))
                           (zero? (:exit-code kondo))))})

(println verify)
```
````

### Iteration 4 — PERSIST (code channel)

```clojure
(def slug (:slug (update$slug :request "Rename loop-guard-hook to intercept-hook in loop_guard_hook.clj")))

(def fm (:frontmatter
          (update$frontmatter
            :request   "Rename loop-guard-hook to intercept-hook in loop_guard_hook.clj"
            :slug      slug
            :mode      :pattern
            :target    target
            :pre       pre
            :apply     {:pattern "loop-guard-hook" :replacement "intercept-hook"
                        :regex false :all true :replaced (:replaced post)}
            :verify    verify
            :rollback  (str "git checkout -- " target))))

(def body
  (str "# Rename `loop-guard-hook` → `intercept-hook`\n\n"
       "## What changed\n"
       "Renamed the symbol in " (:replaced post) " places inside `" target "`.\n\n"
       "## Diff\n```diff\n" (:diff post) "\n```\n\n"
       "## Verification\n"
       "- diff matches update-file output: " (:diff-match verify) "\n"
       "- old symbol after edit: " (:old-pattern-count-after verify) " (expected 0)\n"
       "- new symbol after edit: " (:new-pattern-count-after verify) " (expected ≥ 1)\n"
       "- lint: " (pr-str (:lint verify)) "\n"
       "- tests: " (pr-str (:tests verify)) "\n\n"
       "## Rollback\n```\n" "git checkout -- " target "\n```\n"))

(def res (update$write :slug slug :content (str fm body)))
(update$index-append :path     (:path res)
                     :slug     (:slug res)
                     :mode     :pattern
                     :target   target
                     :ok?      (:ok? verify)
                     :summary  (str (:replaced post) " replaced; verify "
                                    (if (:ok? verify) "✅" "❌")))
```

### Iteration 5 — ANSWER

```markdown
## Renamed `loop-guard-hook` → `intercept-hook`

- mode: pattern (literal, all occurrences)
- target: `components/agent/src/ai/brainyard/agent/common/loop_guard_hook.clj`
- replaced: 7
- verification: ✅ diff matches; old symbol gone (0); new symbol present (7); clj-kondo clean

Saved edit: .brainyard/agents/update-agent/edits/20260510-104503-rename-loop-guard-hook.md
Rollback: git checkout -- components/agent/src/ai/brainyard/agent/common/loop_guard_hook.clj
```

A downstream `eval-agent` or `exec-agent` reads the `Saved edit:` path to confirm the change landed. A user who decides to revert pastes the `Rollback:` line into a terminal.

---

## 11. Optional `(update$*)` Sandbox Helpers

Live in `ai.brainyard.agent.common.update`, registered as `defcommand`s, surfaced via the auto-binding path. None are required — the agent works without them (as in the longer §10 example) — but they compress the persistence and pipeline boilerplate substantially.

| Helper                       | Signature                                                                                                                                                              | What it does                                                                                                                                  |
| ---------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------- |
| `update$slug`                | `(update$slug :request "<text>") → {:slug "<slug>"}`                                                                                                                  | Deterministic kebab-case from the request, stopwords dropped, capped at 60 chars.                                                              |
| `update$frontmatter`         | `(update$frontmatter :request … :slug … :mode … :target … :pre {…} :apply {…} :verify {…} :rollback "…") → {:frontmatter "…"}`                                       | Build the YAML block. Trailing newline included so body can be concatenated directly.                                                          |
| `update$write`               | `(update$write :slug "<slug>" :content "<full markdown>") → {:path :slug :ts}`                                                                                        | Writes to `.brainyard/agents/update-agent/edits/<ts>-<slug>.md`. Auto-suffix on collision.                                                            |
| `update$index-append`        | `(update$index-append :path "…" :slug "…" :mode :pattern :target "…" :ok? true :summary "…") → {:appended true}`                                                      | Prepend one line to `.brainyard/agents/update-agent/INDEX.md` (newest first).                                                                         |
| `update$read-record`         | `(update$read-record :path "…") → parsed map`                                                                                                                          | Cheap parse — reads only the leading `---/---` block. Used by downstream agents to confirm an edit landed without paying for the full diff.    |
| `update$find`                | `(update$find :query "…") → {:matches [{:path :slug :target :ok? :created} …]}`                                                                                       | Search the records by slug or summary. Used at iteration 0 to surface "this exact edit already ran 3 minutes ago".                              |
| `update$apply`               | `(update$apply :request … :target … :mode … :pattern … :replacement … :regex? … :all? … :dirty-ok? … :run-tests? … :lint-ok-to-fail? …) → {:path :ok? :rollback …}` | **The full pipeline as one call.** Internally runs PROBE → APPLY → VERIFY → PERSIST → ROLLBACK-on-fail. Returns the record path and the rollback command. |

When `update$apply` is bound, the §10 demonstration collapses to roughly:

```clojure
(def res
  (update$apply
    :request   "Rename loop-guard-hook to intercept-hook in loop_guard_hook.clj"
    :target    "components/agent/src/ai/brainyard/agent/common/loop_guard_hook.clj"
    :mode      :pattern
    :pattern   "loop-guard-hook"
    :replacement "intercept-hook"
    :all?      true
    :run-tests? false))

(println res)
;; => {:path "<.brainyard/agents/update-agent/edits/...md>"
;;     :ok? true
;;     :replaced 7
;;     :rollback "git checkout -- components/agent/...loop_guard_hook.clj"}
```

The agent's `answer` then mostly assembles a human-readable summary around `res`. This is the recommended path once helpers ship — using the LLM to reimplement the pipeline inline every turn wastes iteration budget and increases the surface for mistakes.

---

## 12. Handoff Mechanics — How Other Agents Consume Edit Records

Same shape as explore-agent's handoff (`docs/explore-agent-design.md` §11), with edit-specific fields.

### 12.1 The `Saved edit:` and `Rollback:` Lines

Every persisted answer ends with two stable-prefix lines:

```
Saved edit: <repo-relative-path-to-record>
Rollback: <single-line shell command>
```

(Or, on verification failure: `Saved edit: …` followed by `Rolled back: <reason>` with no live rollback needed because the workspace is already restored.)

The dispatcher (or any downstream agent) extracts both with simple grep — no JSON envelope.

### 12.2 Two Levels of Cheap Read

A downstream agent can consume the record at two granularities:

**Cheap (~700 bytes):** read just the frontmatter.

```clojure
(def md (update$read-record :path "<path>"))
;; or, without the helper:
(def head (:content (call-tool "read-file" {:path "<path>" :lines [1 35]})))
```

This gives the target, mode, replaced count, verification booleans, and the rollback command. Sufficient for routing decisions ("did the edit pass verify? do I need to re-run?") without paying for the full diff.

**Full (~2–10 KB typically):** read the whole file.

```clojure
(def md (:content (call-tool "read-file" {:path "<path>"})))
```

When the downstream agent needs the actual diff or narrative.

### 12.3 Common Handoff Patterns

**Pattern A — exec-agent loops over update-agent.** Standard exec workflow:

```
exec-agent picks next todo item "Rename foo→bar in 3 files"
  → for each file:
       (call-tool "update-agent"
                  {:question "Rename foo→bar in <file>"
                   :agent-context "<plan path>"
                   :run-tests? false})
       → returns Saved edit: <path> + Rollback: <cmd>
       → exec-agent reads :ok? from frontmatter
       → if :ok? false, exec-agent stops the loop (don't keep editing
          when something already failed)
  → marks todo item done; emits a per-item record listing all edit
    record paths it produced
```

**Pattern B — plan-agent → user → update-agent.** User-in-the-loop:

```
plan-agent drafts ## Approach with proposed edits
  → user reviews
  → user picks one and runs:
       bb tui ask "@<plan path>" -a update-agent
  → update-agent reads :agent-context (plan path), follows the chosen
    Approach bullet, runs the pipeline, emits Saved edit: + Rollback:
```

**Pattern C — eval-agent verifies after the fact.**

```
eval-agent receives "did exec actually do what the plan asked?"
  → reads exec's per-item records
  → extracts each Saved edit: <path>
  → for each edit record, update$read-record to confirm :ok? true
  → cross-references diff against plan's acceptance criteria
```

### 12.4 :agent-context Wiring

`update-agent` reads `:agent-context` for hints — typically a `Saved exploration:` path or a plan path. It does NOT auto-act on the entire context; it uses it to *resolve the target file* when the request is loose ("rename foo→bar" with no path). If the context is a plan, the agent reads the plan's `## Approach` section to pick the matching bullet.

---

## 13. Migration / Rollout Plan

`update-agent` is purely additive — it does not deprecate any existing agent. Rollout is staged so callers can adopt at their own pace.

### Phase 0 — Land `update-agent`

- New `update_agent.clj` registered as a sibling agent.
- New optional `update.clj` helpers namespace (`update$slug`, `update$frontmatter`, `update$write`, `update$index-append`, `update$read-record`, `update$find`, `update$apply`).
- New `.brainyard/agents/update-agent/` layout with `INDEX.md` template + `README.md`.
- Tests:
  - registration smoke test
  - PROBE refuses dirty file
  - PROBE refuses on match-count mismatch (pattern mode)
  - APPLY → VERIFY → PERSIST happy path (pattern mode)
  - APPLY → VERIFY-fail → ROLLBACK (simulate by corrupting post)
  - new-file mode happy path
  - rollback record schema round-trip
- No changes to other agents.

### Phase 1 — exec-agent adoption

- Update exec-agent's instruction so that when an item involves writing to a file, the recommended path is `(call-tool "update-agent" {…})` instead of inlining `update-file` / `write-file` directly. Preserve the option for trivial edits to inline (avoid pipeline overhead for one-line README touches).
- Benchmark exec-agent on a representative refactor task. Acceptance: total turns are within +20% of the inline baseline (the safety pipeline costs iterations) AND zero broken-file regressions across 50 runs.

### Phase 2 — Plan-agent linkage

- plan-agent's tool-context documents the `(call-tool "update-agent" …)` handoff in its `## Approach` template, so plans reference the agent the executor will use.
- `bb tui agents` lists `update-agent` as the recommended choice for the "edit a file" intent.

### Phase 3 — Optional dispatcher rule

- The dispatcher recognizes intents like "rename", "replace", "edit", "add a docstring to", "create file" and offers to route to `update-agent` directly (skipping coact's generalist loop). Off by default; opt-in via `agent-runtime$config :key "update-as-default" :value "true"`.

### Phase Acceptance Gates

| Phase → Phase | Gate                                                                                                                             |
| ------------- | -------------------------------------------------------------------------------------------------------------------------------- |
| 0 → 1         | All update-agent tests green; manual smoke on 5 representative edits passes locally with no rollback.                            |
| 1 → 2         | exec-agent benchmark: zero broken-file regressions across 50 runs; per-item turn budget within +20% of inline baseline.          |
| 2 → 3         | plan-agent emits `update-agent` handoff hints in at least one shipped template; no escalation tickets tagged `agent:update`.     |

---

## 14. Verification

`docs/RLM-BENCHMARK.md` established the benchmark harness shape. Add benchmark cases targeting update-agent's specific contract:

| Benchmark                                  | Shape                                                                                          | What it verifies                                                                                          |
| ------------------------------------------ | ---------------------------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------- |
| Pattern, single match                      | "Rename a unique symbol in one file."                                                          | PROBE counts 1, APPLY, VERIFY clean. `:replaced = 1`.                                                     |
| Pattern, all-matches                       | "Rename a symbol that appears 7 times."                                                        | `:all? true` path. Old count after = 0.                                                                   |
| Pattern, ambiguous match                   | "Rename a symbol that appears in 2 files."                                                    | Either STOPs and asks (single-file rule) or refuses with a clear message naming both files.               |
| Pattern, count mismatch                    | Caller asks for 1, file has 3.                                                                 | PROBE P4 fails; agent escalates to SYNTAX or refuses.                                                     |
| Syntax-aware, whole-form rewrite           | "Rewrite the body of `defn foo` in core.clj."                                                  | Agent reads region, emits new region as `:pattern`/`:replacement`, V1 diff matches.                       |
| New-file create                            | "Create components/agent/src/.../foo.clj with skeleton."                                      | `bash test -f` returns false; write-file used; rollback is `rm`.                                          |
| Dirty-file refusal                         | Target has uncommitted changes; `:dirty-ok?` not set.                                          | Agent STOPs; no edit applied; record NOT persisted.                                                        |
| Dirty-file with `:dirty-ok? :stash`        | Target dirty; flag set.                                                                        | Stash → edit → verify → unstash. Record marks `pre.status :dirty`.                                        |
| VERIFY V1 mismatch (simulated)             | Inject a noop `bash 'echo'` that mutates the file between APPLY and VERIFY.                    | V1 fails; ROLLBACK runs; answer includes `Rolled back:`; record has `:ok? false`.                         |
| VERIFY V4 lint failure                     | Apply an edit that breaks Clojure parens; clj-kondo present.                                   | V4 fails; ROLLBACK runs; answer includes the kondo diagnostic + `Rolled back:`.                           |
| Tests opt-in (V5)                          | `:run-tests? true` on a component edit.                                                        | bb test:component runs; on failure → ROLLBACK; on success → record `:tests {:exit 0}`.                   |
| Out-of-tree refusal                        | Path resolves to `~/.bashrc` or under `.git/`.                                                 | Refused with a clear message; no record.                                                                  |
| Bash escape attempt                        | LLM emits `bash "echo new > <file>"`.                                                          | Forbidden — caught by Hard Rule 1; agent self-corrects or refuses.                                        |
| `git commit` attempt                       | LLM emits `bash "git commit -am ..."`.                                                         | Forbidden — caught by Hard Rule 2.                                                                        |
| INDEX.md prepend integrity                 | Apply 100 edits.                                                                               | INDEX.md has 100 lines, newest first; no entries lost or rewritten.                                       |
| Frontmatter round-trip                     | Persist → `update$read-record` → assert keys.                                                  | Downstream-handoff contract holds.                                                                        |

Per-iteration mulog signals to add (mirroring the `::explore.*` pattern):

- `::update.probe`    — `{:target :status :match-count :elapsed-ms}`
- `::update.apply`    — `{:target :mode :replaced :diff-bytes :elapsed-ms}`
- `::update.verify`   — `{:target :ok? :diff-match :lint :tests :elapsed-ms}`
- `::update.rollback` — `{:target :reason :ok? :elapsed-ms}`
- `::update.persist`  — `{:slug :path :ok? :bytes}`

These are `mulog/log` calls in the helpers (§11) — no agent-loop changes required.

---

## 15. Files Summary

| File                                                                                       | What changes                                                                                                                                                |
| ------------------------------------------------------------------------------------------ | ----------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `components/agent/src/ai/brainyard/agent/common/update_agent.clj`                          | NEW — `instruction`, `tool-context`, `defagent update-agent` mirroring `explore-agent` shape; uses `coact/run-coact-derived`.                                |
| `components/agent/src/ai/brainyard/agent/common/update.clj` (optional)                     | NEW — `update$slug`, `update$frontmatter`, `update$write`, `update$index-append`, `update$read-record`, `update$find`, `update$apply` as `defcommand`s.       |
| `components/agent/test/ai/brainyard/agent/update_agent_test.clj`                           | NEW — registration, dirty-refusal, count-mismatch, happy-path (pattern + syntax + new-file), rollback (V1 / V4 / V5), out-of-tree refusal, bash-escape refusal, INDEX append-only. |
| `.brainyard/agents/update-agent/README.md`                                                        | NEW (templated by helpers on first write) — directory layout cheat-sheet.                                                                                    |
| `bases/agent-tui` / `bases/agent-web`                                                      | NO CHANGES required at Phase 0/1.                                                                                                                            |
| `bb.edn`                                                                                   | OPTIONAL — `repl:update` task mirroring `repl:component`.                                                                                                    |
| `docs/update-agent-design.md`                                                              | THIS FILE.                                                                                                                                                   |
| `components/agent/src/ai/brainyard/agent/common/exec_agent.clj`                            | TOUCHED only at Phase 1 — instruction nudge to delegate writes to `update-agent` for non-trivial edits.                                                      |
| `components/agent/src/ai/brainyard/agent/common/coact_agent.clj`                           | NO CHANGES.                                                                                                                                                  |
| `components/agent/src/ai/brainyard/agent/common/tools.clj` (`update-file`, `write-file`)  | NO CHANGES — the existing `:diff` / `:diff-source` contract is exactly what update-agent's pipeline needs.                                                   |

The whole feature ships as one new agent file (and one optional helpers file). Substrate, BT, sandbox, DSPy signature, and the file deftools — untouched.

---

## 16. Open Questions

1. **Should syntax-aware mode get a real `rewrite-clj` backing?** Today's design routes syntax edits through `update-file` with a whole-region pattern. That works, but the LLM has to compute the region byte-for-byte. A `update$find-form :file … :form-name … :form-arity …` helper backed by `rewrite-clj` would be more robust for Clojure-heavy refactors. Out of scope for v1; revisit if Clojure-form edits dominate the benchmark.
2. **Per-language linter list — extend or pluggable?** The current V4 list (clj-kondo, py_compile, jq, yamllint) covers the common file types in this repo. A pluggable `:linters {".ext" "<cmd>"}` config would let users add Rust/Go/etc. without touching the agent. Suggestion: ship the hardcoded list for v1; add the override config when a third file type appears in actual usage.
3. **Should `update-agent` ever stage commits?** Strict reading of Hard Rule 2 forbids `git commit`. A weaker rule could allow `git commit -m "agent: <slug>"` *only* when `:auto-commit? true` is explicitly set, with the rollback line replaced by `git revert <sha>`. Trade-off: cleaner audit trail vs. risk of polluting the user's history with agent commits. The design errs on the side of NOT committing — let the user decide what becomes a commit.
4. **Multi-file atomicity?** A genuine refactor often needs N file edits to be atomically successful (compile gate). Today each edit is independently revertible but there is no transaction. A `:multi-file? true` mode could batch a list of edits, run all PROBEs first, then APPLY+VERIFY in sequence, and rollback ALL on any failure. Adds complexity; defer to a follow-up if exec-agent's Phase 1 benchmark shows partial-rollback as a real pain point.
5. **Should explore-agent / plan-agent's records auto-feed update-agent's `:agent-context`?** Right now the dispatcher (or the user) wires this manually. A `previous-artifact` channel passed automatically across agent calls would remove the friction. Out of scope for update-agent itself — belongs in the agent-runtime / dispatcher layer.
6. **Permissioning for non-`.brainyard/` targets.** The existing `update-file` / `write-file` permission-fn prompts on every non-allowlisted path. Inside an agent loop those prompts can't be answered interactively. Two options: (a) require the caller to pre-grant permission for the target before calling update-agent (current behavior — surfaces as a verify failure if the prompt times out); (b) add a `:granted-paths [...]` option that pre-authorizes for this turn only. Lean (b) for ergonomics, but only if it doesn't create a permission bypass for accidental requests. Decide based on Phase 1 testing.
