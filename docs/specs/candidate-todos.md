# Candidate TODOs (derived from specs)

*Every contract tagged **Partial** or **Missing** across the spec docs,
collected here for review. Nothing here is committed to a tracker yet —
this is the decide-what-to-close list. Each item cites its `CR-…` ID so
it traces back to the exact obligation. Sizing is a rough first guess.*

Review actions per item: **Close** (do the work), **Accept** (it's a
deliberate, permanent gap — re-tag the contract with a rationale), or
**Split** (break into smaller contracts first).

---

## Functional gaps — worth closing

**T-3 · nREPL demoted to SCI in parallel blocks** — `CR-RSN-06` /
`CR-BT-08b` (Partial). Inside `<!-- ParallelBlock -->`, a `:nrepl`-backend
agent is silently demoted to the SCI sandbox (the fork+merge runner
doesn't support `:nrepl` session sharing — "Phase 1"). Live-JVM interop is
lost. *Close:* implement nREPL session sharing in the parallel runner.
*Or at minimum:* surface the demotion to the user more loudly. **Medium.**

**T-4 · `:parallel` BT node is uncancellable and untraced** — `CR-BT-08b`
(Partial). Unlike the other node types, `:parallel` has no
interrupt/cancel/pause checkpoint and no agent-layer tracing override.
*Close:* add the checkpoint + traced override. *Or accept:* document
parallel subtrees as intentionally short/opaque. **Medium.** (Pairs with
T-3.)

**T-5 · LLM L2→L3 reducer unimplemented** — `CR-MEM-07` (Partial).
`reduce-l2!` is heuristic-only; the `:reducer :llm` path returns
`::llm-reducer-not-implemented`. *Close:* implement the LLM reducer.
**Medium.**

**T-7 · Cron/scheduled tasks not implemented** — `CR-TASK-12` (Missing).
The `Task` `:schedule {:cron …}` field is reserved but no scheduler
consumes it. *Close:* implement an in-runtime scheduler. *Or accept:* drop
the field until needed. **Medium.**

**T-9 · Popup gaps (3, splittable)** — `CR-TUI-15` (Partial).
(a) multi-tab questionnaire navigation unsupported;
(b) Mode-B popup user-feedback doesn't support `:free-input`;
(c) `tui-confirm-mutation` is a v1 visibility-only auto-allow with no
blocking Y/n gate ("future v2"). *Close:* each independently. **Medium.**

---

## Documentation / hygiene — low effort

**T-10 · Router-precedence docstring is wrong** — `CR-RSN-03` (doc).
`ThinkActCode`'s docstring says *code > tool > answer*; the live router is
*answer > code > tool*. Fix the docstring. **Trivial.**

**T-12 · `get-stats` drift** — `CR-MEM` note (doc). Docstring promises
`:working-memory-keys`; `get-db-stats` returns only
`{:episodes :semantic-facts :schema-version}`. Add the field or fix the
docstring. **Trivial.**

**T-13 · Gated-hook API not public** — `CR-RT-27` (Partial).
`fire-decision!` / `gated-event?` are internal-only; only `fire!` is
re-exported. *Decide:* re-export, or document as intentionally internal.
**Small.**

**T-14 · Delegation-cap enforcement location** — `CR-RT-22` / `CR-TOOL-08`
(doc). Caps are enforced in the tool layer but the dynvars are declared in
`protocol.clj`. Add a cross-reference so readers don't look in the
runtime. **Doc-only.**

---

## Recorded as intentional (not TODOs unless reprioritized)

These are deliberate design choices, listed so they aren't re-discovered
as "bugs":

- `by-host`↔`by-ui` daemon split is retired (`CR-TUI-20`, May 2026).
  Single-process `by` won; `bases/agent-tui-ui/` deleted from tree;
  `agent-tui-tmux` control/host/sink namespaces kept test-only/internal.
- State-memory L1/L2 is a prompt-facing sandbox *view*, not a coded
  storage structure (`CR-MEM-02`, reconciled May 2026). The real
  per-iteration state lives in the `st-memory` atom (CR-MEM-01); the
  L1/L2 model is exposed via `(context-get [..])`. The retired "L3
  agent notes" entry corresponded to removed `remember-note` /
  `get-note` / `forget-note` bindings.
- `:acp-backend` defaults to `:stub` — real ACP agent is soft-coupled
  (`CR-CFG-03`).
- `code$eval` `:sandbox` arm is non-functional as a direct tool call by
  design (`reasoning.md` note).
- Inline thinking indicator is a static spinner, no live streaming
  (`CR-TUI-10`) — TODO only if inline streaming is wanted.
- Windows Ollama auto-install unsupported (`CR-TUI-22` note) — TODO only
  if Windows is a target.
- `(FINAL …)` termination is disabled in CoAct (belongs to the RLM path).

---

## Summary

| Bucket | Items |
|---|---|
| Functional (close) | T-3, T-4, T-5, T-7, T-9 |
| Doc/hygiene (low effort) | T-10, T-12, T-13, T-14 |
| Intentional (no action) | 8 recorded choices (incl. T-1 and T-2 resolved May 2026) |
| Closed | T-6, T-8, T-11 (CR-MEM-03 implemented + CR-TUI-07 reconciled + CR-MEM-08 resolved, May 2026) |

Next step once reviewed: promote the agreed items into the project's task
tracker, keeping the `CR-…` references so each task stays tied to the
contract it closes.
