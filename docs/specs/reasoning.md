# Spec: Reasoning Loops

*Area code `RSN`. Covers the CoAct loop (default), the ReAct loop, the
code-execution backends (`:sandbox` / `:nrepl`), and the fenced
code-block contract. Both loops are behavior-tree subtrees — see
[behavior-tree](behavior-tree.md). The tool registry they dispatch into
is specified in [tool-system](tool-system.md).*

Status legend and contract-ID conventions: see [README](README.md).

---

## 1. CoAct loop (default)

CoAct makes one LLM call per iteration that can emit up to three output
channels — tool-calls, code-blocks, answer — and treats executable code
as a first-class action space.

| ID | Contract | Status | Source |
|---|---|---|---|
| CR-RSN-01 | One signature per iteration (`ThinkActCode`) MUST expose outputs `{:tool-calls :code-blocks :answer}`; reasoning is captured via `:chain-of-thought`, with no separate `thought` output. | Implemented | `defsignature ThinkActCode`, `agent/common/coact_agent.clj` |
| CR-RSN-02 | A CoAct iteration MUST follow: inc-iteration → rebudget → compact-context → `ThinkActCode` call (under an LLM-guard fallback) → display reasoning → route → accumulate; the loop's `:max-n` MUST read `config :max-iterations`. | Implemented | `coact-loop-subtree`, `coact_agent.clj` |
| CR-RSN-03 | The router MUST resolve channels with precedence **answer > code > tool**, and MUST require code-blocks to actually parse (≥1 real fence) before taking the code branch (guarding against prose-in-code-fence loops). | Implemented | `:fallback/router`, `coact-has-code-blocks?`, `coact_agent.clj` |
| CR-RSN-04 | The loop MUST terminate when `:answer` is non-blank or `:terminated` is true; stamping an answer MUST set `:terminated-by :answer-channel`. | Implemented | `coact-stamp-answer-action`, `coact_agent.clj` |
| CR-RSN-05 | Field-consistency repair MUST nudge on the 1st/2nd consecutive no-channel (`:none`) iteration and escalate to a loop-guard termination on the 3rd+ (`:terminated-by :none-channel-loop-guard`). | Implemented | `coact-repair-action`, `coact_agent.clj` |
| CR-RSN-05b | Tool dispatch from CoAct MUST cap at `:max-tool-calls` (default 30) and terminate early if a tool returns a `{:hook-blocked true}` sentinel, lifting its `:answer`. | Implemented | `coact-tool-dispatch-action`, `coact_agent.clj` |
| CR-RSN-06 | Parallel code blocks (marked `<!-- ParallelBlock -->`) MUST run clojure via the sandbox parallel runner and shell/py/js via futures. | **Partial** | `parallel-mode?`, `run-blocks-concurrently`, `coact_agent.clj` |

**CR-RSN-06 (Partial):** in parallel-block mode, an agent configured for
the `:nrepl` backend is **demoted to the SCI sandbox** with a demotion
marker — the fork+merge runner does not yet support `:nrepl` session
sharing ("Phase 1 of clj-nrepl-eval"). So parallel blocks silently lose
live-JVM interop. Candidate TODO: implement nREPL session sharing in the
parallel runner, or surface the demotion to the user more loudly. This
interacts with [behavior-tree](behavior-tree.md) CR-BT-08b (the
`:parallel` node is itself uncancellable/untraced).

**Doc/code note (not a code gap):** the `ThinkActCode` signature
docstring claims the router prefers *code > tool > answer*, but the BT
router is *answer > code > tool* (CR-RSN-03). The code is authoritative;
the docstring should be corrected. Candidate TODO (doc-only).

**FINAL termination is disabled in CoAct.** `(FINAL …)` and `FINAL-VAR`
belong to the RLM / clj-sandbox path; in CoAct the sandbox executor
projects a `FINAL` call as an `:error "FINAL termination"` that is
rewritten into an LLM nudge. CoAct terminates only via the answer channel
(CR-RSN-04) or the loop-guard (CR-RSN-05).

---

## 2. ReAct loop

ReAct is the classic Thought → Action → Observation → Evaluation loop,
now collapsed to a single LLM call per iteration (the old multi-call
ThinkAndSelectTools / ObserveAndEvaluate / FinalizeAnswer signatures were
removed).

| ID | Contract | Status | Source |
|---|---|---|---|
| CR-RSN-07 | ReAct MUST make one LLM call per iteration via `ThinkActAndEvaluate`, exposing `{:tool-calls :observation :goal-achieved :goal-reasoning :request-for-information :answer}`. | Implemented | `defsignature ThinkActAndEvaluate`, `agent/common/react_agent.clj` |
| CR-RSN-08 | The thinking loop MUST exit when `goal-achieved` or `request-for-information` is set; when `goal-achieved` is true the LLM MUST fill `:answer` in the same response (no separate finalize call). | Implemented | `thinking-loop-subtree`, `react_agent.clj` |
| CR-RSN-09 | Tool execution MUST dispatch via `tool/call-tool`, truncate results, and handle a `{:hook-blocked true}` sentinel by flipping `:goal-achieved` and lifting `:answer`. | Implemented | `tool-calls-action`, `react_agent.clj` |
| CR-RSN-10 | Accumulators (`:thoughts :observations :iterations`) MUST grow per iteration and be re-budgeted every `:rebudget-every-n-iter` (default 10) with per-list floors (default 3 each). | Implemented | `react-rebudget-action`, `react_agent.clj` |
| CR-RSN-11 | On repeated failure, ReAct MUST synthesize a partial fallback answer from the last observation/thought. | Implemented | `fallback-answer`, `react_agent.clj` |

---

## 3. Code-execution backends

There are two backends for ` ```clojure ` blocks, fixed **per-agent**
(not per-fence) by the `:clj-backend` config key.

| ID | Contract | Status | Source |
|---|---|---|---|
| CR-RSN-12 | The backend MUST be selected per-agent from `config :clj-backend` (schema default `:sandbox`); there MUST be no per-fence override. | Implemented | `agent-clj-backend`, `run-single-block`, `coact_agent.clj`; `agent/core/config.clj` |
| CR-RSN-13 | `:sandbox` MUST run in the SCI sandbox: `def` persists across iterations and turns within a session, output is captured, errors are non-fatal, **no interop** (System/Runtime/ProcessBuilder/ClassLoader denied), with auto-background detach at `:auto-background-timeout-ms` (default 120s). | Implemented | `execution-model-sandbox`, `coact_agent.clj` |
| CR-RSN-14 | `:nrepl` MUST run in the live JVM: full interop/reflection, a pinned session via `:nrepl-session-id`, and read-only/mutate grant gating with audit/drift markers. | Implemented | `execution-model-nrepl`, `agent-nrepl-session-id`, `coact_agent.clj` (gating in `clj-nrepl`) |
| CR-RSN-15 | The fenced-block parser MUST accept only the language tokens `clojure`/`clj`, `python`/`py`, `bash`/`sh` (plus `javascript`/`js` handled in CoAct); any trailing non-whitespace token (e.g. ` ```clojure :nrepl`) MUST become a `:fence-error` that short-circuits to an error entry. | Implemented | `extract-all-code-blocks-multi` (clj-sandbox `core/prompt.clj`), `run-single-block` |

**Note on `code$eval`:** the `code$eval` command fronts both backends,
but its `:sandbox` arm is intentionally non-functional as a *direct* tool
call (returns a `sandbox-arm-error` pointing back to the CoAct fence
path). Only the `:nrepl` arm works as a direct `code$eval`. This is
by-design, not a gap — recorded here so it isn't mistaken for one.

---

## Gaps & candidate TODOs (this spec)

- **CR-RSN-06 — nREPL demoted to SCI in parallel blocks.** Parallel
  fork+merge doesn't support `:nrepl` session sharing yet; agents lose
  live-JVM interop silently inside `<!-- ParallelBlock -->`. Implement
  session sharing in the parallel runner, or make the demotion visible.
  *(Medium; ties to CR-BT-08b.)*
- **CR-RSN-03 (doc) — router-precedence docstring is wrong.** The
  `ThinkActCode` docstring says *code > tool > answer*; the live router
  is *answer > code > tool*. Fix the docstring. *(Doc-only.)*

Outside these, the CoAct/ReAct/backend files carry no `TODO`/`FIXME`/stub
markers — the reasoning subsystem is broadly Implemented.
