# Agent Test Harnesses

This document describes how the `by` agents are tested from the outside — as
running processes, not as unit-under-test functions — and lays out a per-agent
harness design keyed to each agent's *agentic functions*: the concrete jobs an
agent is supposed to do, derived from its instruction (system prompt) and its
tool-context (the roster it is allowed to reach).

The organizing idea: the coact agent is the **base** agent. Its system prompt
defines the channels, the tool-call format, the execution model, and the
critical rules that every other agent inherits (`react-agent` and all the
coact-derived specialists). So we test coact by its *substrate* functions
(channel routing, code eval, tool dispatch, memory, tasks, delegation), and we
test each specialist by the *added* function it layers on top (plan authoring,
todo decomposition, safe edits, evaluation, config changes, …). A harness is a
black-box script that drives `by` and asserts on observable output plus durable
side effects.

See also: `scripts/` (the existing harnesses), `docs/design/` (agent design
notes), and `components/agent/src/ai/brainyard/agent/common/` (the `defagent`
sources).

---

## 1. How agents are driven for testing

There are two entry surfaces, and both existing harness families use one of
them.

**One-shot `ask` (headless, scriptable).** The primary surface. Each turn is a
separate `by ask` process with an empty in-memory session, so nothing carries
between turns except what is persisted (memory DB, session store, artifacts on
disk). This is what makes recall/consolidation testable: the only channel from
turn *n* to turn *n+1* is durable state.

```bash
by ask --json -u <user-id> -s <session-id> -p <provider> -m <model> -a <agent> "<question>"
```

- `--json` emits a single-line JSON object on stdout: `{"success":true,"answer":"…"}`
  (incidental agent output goes to stderr). Parse with `jq -r '.answer'`; check
  `.success` and `.error` for runner-level failures.
- `-a <agent>` selects the agent. `coact-agent` is the default, so it is omitted
  in the `-a` plumbing; any other agent name routes to that `defagent`.
- `-u` / `-s` pin identity and session — the levers every harness uses for
  isolation (see §4).
- `-p` / `-m` pin provider/model for the matrix (see §5).
- When there is no native binary, harnesses fall back to `bb tui ask …`.

**Interactive TUI via tmux (real-terminal).** For behavior that only exists in
the interactive loop — the autocomplete menu, the spinner/idle lifecycle, the
per-turn usage footer, multi-turn in-process recall. A detached tmux session
boots `bb tui …`, `tmux send-keys` drives the input loop, and `tmux
capture-pane` snapshots the plaintext to assert on. This is the only way to test
the rendering layer and the in-process (non-persisted) turn chain.

`tmux capture-pane` reads whichever layout mode the TUI is in — it does **not**
require `--inline`. The two modes are `:inline` (pass-through: no alternate
screen, no scroll-region redraws) and `:fullscreen` (managed alternate screen),
and both are capturable: `test-tui-autocomplete.sh` boots in fullscreen and
`test-tui-tmux.sh` passes `--inline`. Prefer `--inline` when you want the
simplest, most stable captures (no alt-screen repaint churn while an answer
streams); use fullscreen when the case is specifically about full-screen
rendering (menus, scroll regions). Note the TUI falls back to inline on a small
pane (< 12 rows), so size the tmux pane accordingly with `-x/-y`.

**Deterministic side-effect probes.** For anything with a durable artifact,
assert on the artifact directly rather than on LLM prose — it removes model
variance from the signal:

- **Memory:** `sqlite3 ~/.brainyard/memory/<uid>.db` and `by memory stats`.
- **Sessions:** `<project>/.brainyard/sessions/<id>/`.
- **Dossiers / plans / todos / eval verdicts:** the files under
  `<project>/.brainyard/agents/<agent>/…` and `.brainyard/{plans,todos}/…`,
  read back via the typed readers (`plan$read`, `doc$read`, `exec$find`) or by
  grepping the files.
- **Edits:** the `Saved edit: <path>` + `Rollback: <cmd>` lines an edit emits.

A good harness pairs a **deterministic probe** (proves the machinery ran) with
an **LLM-output assertion** (proves the agent surfaced the result). When they
split, the failure localizes cleanly — e.g. capture passed but recall failed
isolates a regression to the recall path, not the write path.

---

## 2. Existing harnesses

All live in `scripts/`. They already encode the conventions this doc
generalizes.

| Script | Surface | What it proves |
|---|---|---|
| `test-memory-recall.sh` | `ask --json` | Session-scoped L1/L2 recall: facts stated in early turns resurface in later fresh processes purely through SQLite recall. Two-layer (sqlite3 capture check + LLM recall). |
| `test-memory-l3-recall.sh` | `ask --json` + `by memory consolidate` | Cross-session L3 via the heuristic reducer: facts in session A recalled in a *different* session B (only L3 spans sessions). |
| `test-memory-l3-graph.sh` | `ask --json` + `graph-build` + `consolidate --reducer community` | The context-graph tier (CR-MEM-24): entity/relation extraction → community summaries → cross-session recall. Needs `BY_ENABLE_GRAPH_MEMORY` + an extract model. |
| `test-memory-auto-consolidate.sh` | `ask --json` | Automatic L2→L3 promotion via the session-end flush hook (no manual `consolidate`), plus a negative control with the gate off. |
| `test-tui-tmux.sh` | tmux TUI | Agent-level smoke test: boot, simple Q&A, in-process multi-turn recall, usage footer. Parameterized by `<agent> <provider> <model>`. |
| `test-tui-matrix.sh` | tmux TUI | Runs `test-tui-tmux.sh` across `{react-agent, coact-agent} × {providers}` and prints a pass/fail matrix. |
| `test-tui-autocomplete.sh` | tmux TUI | The slash/colon autocomplete menu: filtering, submenus, dismiss semantics. Never sends Enter for slash-commands (Tab-to-accept only) so cases can't fire `/quit`. |

Shared conventions worth copying (they are the house style):

- **Isolation by throwaway identity.** `USER_ID="…-$$-$(date +%s)"` gives each
  run its own memory DB; a `trap cleanup EXIT` removes it (`--keep-db` retains).
  The real user's memory is never touched.
- **Exit-code contract.** `0` = all pass · `1` = an assertion failed · `2` =
  cannot run (missing tool, provider auth, runner crash). The matrix relies on
  this to distinguish "agent broke" from "environment broke".
- **Synthetic, non-colliding fixtures.** Names like `Wexler` / `teal` /
  `Photon-7` so a "hit" can't leak from this repo's own `.brainyard` memory.
- **Tool guards.** `command -v jq sqlite3` up front, failing with exit 2.
- **`assert_*` helpers** that tally `PASS`/`FAIL`, print the pattern and a tail
  of the actual output on failure, and never abort the suite early.
- **Idle detection** (tmux): a turn is idle only when the spinner is gone, the
  input prompt is present, *and* the capture is stable across N polls.

---

## 3. The testing model: agentic functions

An agent's system prompt plus its tool roster define a finite set of
**agentic functions** — testable units of "given this kind of request, the
agent should take this kind of action and produce this observable result." We
test at that granularity rather than at the "does it answer well" granularity,
because functions have deterministic side effects we can probe.

The coact base agent contributes the **substrate functions** every agent
inherits:

- **Channel routing** — per turn it must pick exactly one of *tool-calls*,
  *code-blocks*, *answer*, with router precedence `answer > code > tool` and
  field-consistency (a populated channel blanks the others). A non-blank
  `answer` terminates the loop.
- **Tool dispatch** — ReAct-style JSON `[{"tool-name":…, "tool-args":[…]}]`,
  dispatched in parallel, with pre/post hooks.
- **Code-as-action** — markdown-fenced blocks: `clojure` in a persistent SCI
  sandbox (`def` survives across iterations/turns), `bash`/`python`/`javascript`
  in fresh subprocesses, `<!-- ParallelBlock -->` for concurrent fan-out, and
  four-backtick verbatim fences that write to a file instead of executing.
- **Auto-background tasks** — a block over `:auto-background-timeout-ms` (120s)
  detaches into a task; `task$run`/`task$detail`/`task$cancel` manage explicit
  background jobs; later iterations harvest completions.
- **Memory** — recall via `(context-get [:recalled-memory])` / `memory$recall`;
  automatic L2 capture (`:enable-memory-capture` default on for coact).
- **Delegation** — spawning a specialist inherits coact's substrate with a
  parent-trail handoff.
- **Answer discipline** — critical rules: `(FINAL …)` is disabled (terminate via
  the `answer` field only); use typed artifact readers, never bare `ls`/`find`;
  bash/file tools anchor at git-root; the large-results playbook (read spilled
  `/tmp` files by line-range, grep first, don't re-print).

`react-agent` is coact **with the code channel disabled** (`:code-channel?
false`) — the tool-only role. Its harness is coact's minus every code-eval case,
plus a **negative** case: it must *never* emit an executable fence and must reach
for a tool instead.

Each specialist adds one dominant function on top of the substrate. The rest of
this doc gives a harness sketch per agent focused on that added function.

---

## 4. Base agent harness — `coact-agent`

`coact-agent` is the reference implementation; its harness is the deepest and is
the template all others specialize. Group cases by substrate function.

### 4.1 Channel routing

| Case | Prompt shape | Assertion (side effect > prose) |
|---|---|---|
| tool → answer | "List the registered tools matching `memory`." | one tool-call turn, then an answer citing tool names |
| code → answer | "Compute the 10th Fibonacci number by defining a function." | a `clojure` block ran; answer contains `55` |
| direct answer | "What is 2+2? Reply with just the digit." | terminates in one turn; answer is `4`; no tool/code turn |
| precedence | a request that could be answered directly | never emits both a channel *and* an answer in the same turn |

Deterministic hook: enable trajectory capture and assert on the recorded
`:channel` per iteration in `<project>/.brainyard/sessions/<id>/trajectory.edn`,
so routing is checked structurally, not inferred from prose.

### 4.2 Code-as-action

Cover each runtime and the state model:

- **SCI persistence:** turn 1 `(def x 41)`; turn 2 `(inc x)` → `42`. Requires
  `:enable-sandbox-persistence`; proves `def` survives across turns.
- **bash / python / javascript:** each in a fresh subprocess, raw (no escaping);
  assert the captured stdout.
- **ParallelBlock:** two independent blocks with the marker; assert both results
  merge back.
- **Verbatim fence:** a four-backtick `markdown name.md` block writes the file
  and returns its path; assert the file exists with the exact bytes and that it
  was **not** executed.
- **Auto-background detach:** a deliberately long block (sleep beyond the
  timeout) detaches to a task; a later iteration harvests it as an
  `[↺ async-completion]`. Assert a `task-id` appears and the final answer
  reflects the completed result.

### 4.3 Tool dispatch

- Single tool, no post-processing → one tool turn.
- Parallel tool-calls in one array → all dispatched (assert via hook counts or
  trajectory).
- Unknown/MCP tool via `(call-tool "<id>" {…} :server-name "<srv>")` fallback.
- Hook gating: a `tool-use/pre` hook that blocks → the turn terminates with the
  hook's answer.

### 4.4 Memory (substrate)

Reuse `test-memory-recall.sh` as-is (it already drives coact). Add: capture is
**on** by default — assert episodes land in the DB after a plain turn with no
special flags.

### 4.5 Tasks

- `task$run :job-type bash` returns `{task-id,status}`; `task$detail` polls;
  `task$cancel` stops. Assert lifecycle transitions and that
  `<project>/.brainyard/tasks/<id>/{output.log,meta.edn}` exist.
- Retention: after `task$sweep`, removed-task dirs are GC'd but a live task's dir
  survives (see the GC decision in `CLAUDE.md`).

### 4.6 Delegation

Ask coact something that routes to a specialist (e.g. "explore the codebase for
X") and assert the sub-agent ran with a parent-trail (the sub-agent's prompt
carries the last K previous turns). Probe the child's dossier/artifact.

### 4.7 Critical-rule guards (negative cases)

- Prompt that tempts `(FINAL …)` → the agent must terminate via `answer`, not
  emit `FINAL`.
- A large result spilled to `/tmp` → the agent reads it by line-range / greps
  first, and does **not** bare-re-read or re-`println` the whole value.
- File/bash operations resolve at git-root (`.brainyard/…`), not the JVM cwd.

The tmux smoke test (`test-tui-tmux.sh`) already covers coact's boot + simple +
multi-turn + footer path; the above extend it into the headless `ask` surface
where side effects are probeable.

---

## 5. Provider × agent matrix

Behavior must hold across providers. `test-tui-matrix.sh` already runs
`{react-agent, coact-agent} × {claude-code:opus, bedrock:…sonnet, openai:gpt-4o}`.
The per-agent harnesses below should be matrix-able the same way: parameterize
`PROVIDER`/`MODEL`, keep model-specific expectations out of assertions (assert on
*structure* and *side effects*, which are model-invariant; avoid asserting exact
phrasing). Reserve LLM-dependent tiers (graph extraction, deep evaluation) for a
validated default model and treat their prose assertions as softer signals than
the deterministic DB/file probes.

---

## 6. Per-specialist harnesses

Each specialist is coact-derived, so it inherits §4. Its own harness targets the
**added function** and the **hard rules** its roster enforces. For every
specialist, drive it directly with `by ask -a <agent> …` and assert on its
durable artifact (the dossier / file it is contracted to emit) plus the stable
handoff lines.

### Exploration & discovery

- **`react-agent`** — tool-only base. Run the coact tool/answer/memory cases;
  add the negative: it must never emit an executable code fence. Assert no
  `code` channel appears in the trajectory for any turn.
- **`explore-agent`** — read-mostly multi-surface discovery (files, web, MCP,
  skills). Function: gather and **persist results as a durable artifact**. Ask
  it to find something spanning ≥2 surfaces; assert an explore artifact is
  written and re-readable via `explore$…`/`doc$read`. Negative: it does not
  mutate source.
- **`research-agent`** — orchestrates explore→plan→todo→exec→eval. Function:
  dossier threading across stages. Assert each stage's dossier exists and the
  final synthesis references them. Long arc (max-iter 30) — allow generous turn
  timeout.
- **`rlm-agent`** — chunk/map/reduce over oversized input. Feed input beyond a
  single call; assert the reduced output covers content from multiple chunks.

### Planning & execution pipeline

- **`plan-agent`** — authors a plan with pre-flight sufficiency (C1–C3) and
  post-flight rubric (R1–R6) gating; emits a dossier every turn. Assert a plan
  file with the required frontmatter (PURPOSE / DIRECTION / ACCEPTANCE_CRITERIA)
  and a `Saved plan: <path>` line. Negative: on insufficient input it gates
  rather than fabricating a plan.
- **`todo-agent`** — decomposes a plan into a routed checklist (`{via: …}` tags:
  edit-agent / bash / mcp / explore-agent / read-only / manual). Feed a saved
  plan via `agent-context`; assert the todo file has `- [ ] … {via:…}` lines and
  `todo$sync` reconciled. Hard rule: it authors todos; it does not execute them.
- **`exec-agent`** — executes todo items by tag (route→verify→record→flip),
  delegating writes to edit-agent; emits a dossier per turn. Assert boxes flip
  only with recorded evidence and `todo$sync` runs. **Hard rule:** exec-agent
  cannot create todos (`doc$create` forbidden) — negative case must confirm the
  refusal.
- **`edit-agent`** — a single-file safe edit transaction: probe→apply→verify
  (lint+test)→persist→rollback. Assert the stable `Saved edit: <path>` +
  `Rollback: <cmd>` lines, that the diff applied, and that running the rollback
  command restores the original. This is the most deterministically testable
  specialist — lean on it.
- **`eval-agent`** — read-only scoring against a plan's acceptance criteria;
  produces a unified verdict file. Feed a plan + an execution dossier; assert a
  verdict file with per-criterion scores. **Hard rule:** read-only toward
  upstream — negative case confirms it writes no source.

### Routing & meta-control

- **`main-agent`** — front-door router. Function: route to the right specialist
  and maintain a per-session routing log. Give it prompts of several shapes
  (explore-y, plan-y, config-y) and assert the routing log records the chosen
  specialist for each.
- **`workflow-agent`** — multi-stage domain workflows (max-iter 50). Assert a
  workflow dossier with stage checklists and cross-stage context threading.

### Configuration & development

- **`config-agent`** — read/propose/apply/revert config; snapshot-safe. Apply a
  change, assert `.brainyard/config.edn` updated per the precedence rules, then
  revert and assert the snapshot restored. Provider/model changes route via the
  bootstrap re-run rung.
- **`init-agent`** — BRAINYARD.md authoring (seed/append/curate), transactional
  and reversible. Assert `init$apply` writes the file and `init$revert` restores.
- **`debug-agent`** — live-JVM debugging via nREPL (`:clj-backend :nrepl`, not
  the SCI sandbox). Requires a live `.nrepl-port`. Assert `code$eval` reaches the
  running process (inspect a known namespace/var) — distinct from coact's
  in-process sandbox.
- **`tool-agent` / `hook-agent` / `meta-agent`** — user-defined tool/hook/agent
  lifecycle (create/validate/list/read/delete). For each: create a synthetic
  definition, assert it validates and lists, exercise it, then delete and assert
  it's gone. Use a throwaway name so it can't collide with real user defs.

### Context & memory

- **`memory-agent`** — layered stewardship: stats / remember / essence /
  consolidate / purge / verify-fact / correct across L1/L2/L3. The existing
  `test-memory-*.sh` scripts already exercise the consolidation paths; extend
  with `remember` (assert an L3 fact appears), `correct` (assert a fact is
  updated), and `verify-fact`. Isolate with a throwaway `-u`.
- **`mcp-agent`** — MCP server/tool/resource discovery and permission-gated
  invocation. Assert reads proceed and writes require confirmation. Needs a test
  MCP server (or a stub) on the roster.

### External

- **`acp-agent`** — hands off to an external ACP backend; roster is nil. Test
  the bridge, not the reasoning: stub the backend and assert responses / plans /
  tool-calls stream through the TUI hook. (Cannot use the deterministic-artifact
  approach — the backend owns the work.)

---

## 7. Assertion patterns (reference)

Copy these into new harnesses so behavior stays consistent with `scripts/`.

**Headless runner** (from `test-memory-recall.sh`):

```bash
by_ask() {                      # prints .answer; exit 2 on runner failure
  local q="$1" raw json
  raw="$(${BY_BIN:-bb tui} ask --json -u "$USER_ID" -s "$SESSION_ID" \
           -p "$PROVIDER" -m "$MODEL" ${AGENT:+-a "$AGENT"} "$q" 2>/dev/null)"
  json="$(grep -E '^\{.*\}$' <<<"$raw" | tail -1)"
  [[ -z "$json" ]] && { echo "FATAL: no JSON for: $q" >&2; exit 2; }
  [[ "$(jq -r '.success' <<<"$json")" == "true" ]] || {
    echo "FATAL: $(jq -r '.error // "unknown"' <<<"$json")" >&2; exit 2; }
  jq -r '.answer // ""' <<<"$json"
}
```

**Assertion tally** (case-insensitive substring; never aborts the suite):

```bash
PASS=0; FAIL=0
assert_contains() {   # <name> <needle> <haystack>
  local name="$1" needle="$2" hay="$3"
  if grep -qiF -- "$needle" <<<"$hay"; then
    echo "  ✓ $name"; PASS=$((PASS+1))
  else
    echo "  ✗ $name (expected: '$needle')"; echo "$hay" | head -4 | sed 's/^/    /'
    FAIL=$((FAIL+1))
  fi
}
```

**Isolation + cleanup + exit contract:**

```bash
USER_ID="agent-harness-$$-$(date +%s)"
SESSION_ID="sess-$$-$(date +%s)"
trap 'rm -f "$MEM_DIR/$USER_ID.db"*' EXIT INT TERM
# … cases …
echo "== pass=$PASS fail=$FAIL =="
exit $(( FAIL > 0 ? 1 : 0 ))     # 0 pass · 1 assertion failed · 2 cannot run
```

**Deterministic side-effect probes:**

```bash
# memory capture
sqlite3 "$MEM_DIR/$USER_ID.db" \
  "SELECT content FROM episodes WHERE user_id='$USER_ID' ORDER BY timestamp;"
# routing channel per iteration
grep -o ':channel :[a-z]*' "$PROJ/.brainyard/sessions/$SESSION_ID/trajectory.edn"
# dossier / plan / edit artifacts
ls "$PROJ/.brainyard/agents/$AGENT/"    # or plan$read / doc$read via `by`
```

---

## 8. Running

```bash
# a single agent smoke across the real terminal
scripts/test-tui-tmux.sh coact-agent claude-code opus

# the provider × agent matrix
AGENTS='react-agent coact-agent' \
PROVIDERS='claude-code:opus bedrock:global.anthropic.claude-sonnet-4-6 openai:gpt-4o' \
  scripts/test-tui-matrix.sh

# memory family (headless, isolated dbs)
scripts/test-memory-recall.sh
scripts/test-memory-l3-recall.sh
BY_ENABLE_GRAPH_MEMORY=true scripts/test-memory-l3-graph.sh

# point any harness at the native binary instead of `bb tui`
BY_BIN=projects/agent-tui-app/target/by scripts/test-memory-recall.sh
```

New per-agent harnesses should follow the naming `test-agent-<name>.sh`, take
`PROVIDER`/`MODEL`/`AGENT` overrides, honor the 0/1/2 exit contract, and pair a
deterministic side-effect probe with an LLM-output assertion so a failure
localizes to the machinery or the surfacing, not just "the answer looked wrong."
