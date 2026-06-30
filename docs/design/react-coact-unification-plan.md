# Unifying react-agent into the coact base — implementation plan

**Branch:** `worktree-unify-react-into-coact`
**Status:** IMPLEMENTED (Grade B). 270 unit tests green (coact+react 83/468; derived-agent
+ react-referencing sweep 187/1772); zero failures.
**Author:** scoped from a read-only analysis of the as-built agent fleet

> **As-built note.** Implementation confirmed CoAct's prompt is pervasively
> code-framed (`coact-role`, `coact-tools-overview`, `coact-tools-hotpath`, the
> instruction), so a faithful tool-only mode needed small tool-only **variants**
> of those blocks — not just section gating. The variants total ~25 lines vs.
> react's retired 1,231-line parallel implementation (net −2,361 lines incl.
> tests). The flag is read from one source (config `:code-channel?`): the
> assembler omits the four code sections + swaps role/overview; the BT guard
> (`coact-has-code-blocks?`) ignores any emitted fence so a stray code iteration
> falls through to the existing repair path (no new BT structure). Default
> `true` ⇒ all 20 derived agents unchanged (verified by the sweep).

---

## 1. Why

CoAct is a strict functional superset of ReAct. Its role prompt names channel (1)
as *"invoke registered tools via a JSON array (ReAct-style)"* — so CoAct already
*is* the ReAct loop plus a code channel.

Adoption confirms it:

- **20 agents derive from coact** via `run-coact-derived` (config, debug, edit,
  exec, explore, hook, main, eval, memory, mcp, plan, init, research, skill,
  meta, tool, rlm, todo, workflow, + coact itself).
- **0 agents derive from react.** `run-react-derived` does not exist.

`react-agent` survives only as a standalone, registered "advanced fallback"
(`main_agent.clj:173`: *"classic ReAct loop. Niche"*). It costs ~1,231 lines
(`react_agent.clj`) plus a parallel system-context assembler and a parallel DSPy
signature, all of which must track coact's shared roster + substrate changes by
hand. The roster-drift hazard is already called out in the code
(`react_agent.clj` defagent comment: *"defined once in agent-roster so it can't
drift"*).

**Goal:** fold react's one genuine differentiator — *a tool-only loop with no
code channel* — into the coact base as a flag, and re-express `react-agent` as a
flag-set coact-derived agent. Keep the capability; delete the duplicate.

---

## 2. The design decision (the only real fork)

Three reachable end-states. They differ in whether a *structural* tool-only mode
survives.

| Grade | What react-agent becomes | Touches coact core? | LOC | Honest verdict |
|---|---|---|---|---|
| **B — structural flag** | coact-derived, `:code-channel? false`; the BT never routes code and the code sections are absent from its prompt | Yes (default-true flag, non-regressing) | −~1,180 react / +~60 coact | **Recommended.** Delivers a real, maintained tool-only mode. |
| **C — delete** | gone; coact-agent is the only loop | No | −~1,231 react & tests | Cleanest LOC. Loses the tool-only loop entirely (matters for sandbox-less / strictly-RPC use). |
| **A — prompt overlay** | coact-derived + a "don't use code" instruction note | No | −~1,180 react / +~30 | **Not recommended.** react-agent still renders coact's full system-context (which *documents* the code channel), so it becomes a near-duplicate of coact-agent with only a weak discouraging note. Worst of both: keeps an agent without a real distinction. |

**Recommendation: Grade B.** It is the only option that matches the stated
intent ("unify *and keep* the capability"). The flag defaults to `true`, so all
20 existing derived agents are byte-identical (they pass nothing → flag true →
every section present). Only `react-agent` sets it `false`. Blast radius is
contained by the default.

If the tool-only mode is judged not worth keeping, fall back to **Grade C**
(straight delete). Do **not** ship Grade A.

The rest of this plan details **Grade B**, with Grade C deltas noted inline.

---

## 3. New config knob

`components/agent/src/ai/brainyard/agent/core/config.clj` — add to `config-schema`:

- **`:code-channel?`** — boolean, **default `true`**. When `false`, the coact
  loop suppresses the code-blocks channel (BT routing + prompt sections).
  Resolved per-agent via the standard precedence; `react-agent` pins it `false`
  through its meta / `:agent-config`.

Default-true is the non-regression guarantee: every current agent resolves
`true` and renders/behaves exactly as today.

*(Grade C: skip — no knob.)*

---

## 4. coact source edits — `components/agent/src/ai/brainyard/agent/common/coact_agent.clj`

Two read sites consume `:code-channel?`. Both must degrade to today's behavior
when it is `true`.

### 4a. `coact-system-context` (defn at ~878; section list at ~1012)

Gate the **code-only** sections behind `:code-channel?`. When `false`, omit:

- `:execution-model` (execution-model-core / -sandbox)
- `:channel-routing` (`coact-channel-routing`)
- `:code-blocks-format` (`coact-code-blocks-format`)
- `:sandbox-context-accessor`
- the function-directory / Sandbox-Categories sub-block inside `build-tools-section`
  (pass `:code-channel?` down; render tools without the SCI directory)

Keep in both modes: `:role` (see 4c), `:tool-call-format`, `:tools`,
`:critical-rules`, `:large-results-playbook`, `:instruction`, `:agent-context`,
BRAINYARD/project-memory, and all substrate sections.

Mechanics: thread `:code-channel?` into the input map and wrap each
code-only `(assoc …)` in the `cond->` with `(and code-channel? …)` /
restructure so the four code sections only land when the flag is set.

### 4b. `coact-behavior-tree` (defn at 4413) → `coact-loop-subtree` (4279)

When `:code-channel? false`, the loop must not route to or wait on the code
channel. Affected nodes (all in / under `coact-loop-subtree`):

- `coact-code-eval-action` (3298)
- `coact-prepare-eval-action` (4009)
- `coact-process-eval-in-loop-action` (4043)
- `coact-repair-action` code-path (3677) — keep the malformed-DSPy repair, drop
  the code-eval recovery branch
- `coact-await-pending-action` (2155) — code-block auto-detach has no source when
  code is off; safe to leave (no pending code tasks ever created) but cleaner to
  skip.

Cleanest implementation: `coact-behavior-tree` and `coact-loop-subtree` take the
flag and select a code-free loop branch (tool + answer + accumulate + refine
only) when `false`. The DSPy node still calls `#'ThinkActCode` (4310) — that is
fine; with no code sections in the prompt and the BT ignoring `:code-blocks`, the
model emits tool/answer only. (Optionally clear `:code-blocks` defensively in the
route action, mirroring how react's `tool-calls-action` zeroed unused fields.)

> Keep `ThinkActCode` as the single signature. Re-introducing a second signature
> would re-create the duplication we are removing. The behavioral delta vs. the
> old react signature (`ThinkActAndEvaluate`) is noted in §7.

### 4c. `coact-role` (322)

`coact-role` hard-names "three output channels." Either:
- split a `coact-role-3ch` / `coact-role-2ch` pair, or
- parameterize the one string and select per flag.

Pick whichever is least churn; the 2-channel variant drops clause (2) code-blocks
and the "code > tool > answer" conflict note.

*(Grade C: none of §4 — coact is untouched.)*

---

## 5. react_agent.clj — collapse to a thin defagent

`components/agent/src/ai/brainyard/agent/common/react_agent.clj` (1,231 lines).

**Delete** (now provided by the coact base): `ThinkActAndEvaluate` signature,
`react-role` / `react-critical-rules` / `react-tool-call-format` / `react-footer`,
`react-system-context` / `react-user-context`, `ReActAssembler` + `react-assembler`,
`react-init-action` / `react-rebudget-action`, `normalize-evaluation-action`,
`fallback-answer`, `tool-calls-action`, `thinking-loop-subtree`,
`react-behavior-tree`, `react-skill$thinking-loop`, the react-only schemas/sigs.

**Keep / rewrite** — a thin registration:

```clojure
(defagent react-agent
  "Tool-only agent — coact with the code channel disabled (no SCI/bash/py/js)."
  coact/run-coact-derived
  :bt-factory (fn [{:keys [max-iterations]}]
                (coact/coact-behavior-tree max-iterations :code-channel? false))
  :agent-config {:code-channel? false}          ; resolved by system-context too
  :tool-use-control {}
  :input-schema  [:map [:question [:string {:desc "..."}]]
                       [:agent-context {:optional true} [:string {:desc "..."}]]]
  :output-schema [:map [:answer [:string {:desc "..."}]]]
  :instruction "Use tool-calls and a final answer only. ..."
  :tool-context common-cmds/operational-recall-guidance)
```

(Exact arity of `coact-behavior-tree` / how the flag reaches `coact-system-context`
— via `:bt-factory` arg vs. resolved `get-config` at the init action — is an
implementation detail; prefer config resolution so both the BT *and* the
assembler read one source.)

Net: ~1,231 → ~40 lines.

*(Grade C: delete the file entirely.)*

---

## 6. Other source edits

- **`components/agent/src/ai/brainyard/agent/interface.clj`**
  - `:43` `[ai.brainyard.agent.common.react-agent]` require — **keep** (still
    loads the thin react-agent so it registers).
  - `:301–303` `(export-symbols … react-behavior-tree thinking-loop-subtree)` —
    **delete** (symbols no longer exist). *(Grade C: also drop the `:43` require.)*

- **`bases/agent-tui/src/ai/brainyard/agent_tui/helpers.clj:60–63`**
  `resolve-react-bt` — **delete**. It references the removed `react-behavior-tree`
  and has **zero callers** (verified) — dead code today.

- **`components/agent/src/ai/brainyard/agent/core/tool.clj:107–113`** — the
  `deftool` **docstring example** uses `react-agent` / `react-behavior-tree`.
  Cosmetic; update the example to a still-valid symbol. Non-blocking.

- **`components/agent/src/ai/brainyard/agent/common/main_agent.clj:173`** —
  routing note. Reword: react-agent is now *"coact with the code channel off
  (tool-only); use when you want strictly tool+answer behavior."*

- **`main_agent_hooks.clj` `specialist-agents` set (incl. `"react-agent"`)** —
  **no change** (react-agent stays registered & dispatchable). *(Grade C:
  remove `"react-agent"`.)*

---

## 7. Behavioral deltas to accept (Grade B)

Folding onto `ThinkActCode` drops three `ThinkActAndEvaluate`-only output fields:

- `next-user-prompt` (suggested follow-up line),
- `request-for-information` (explicit clarification flag),
- the prescriptive observe→evaluate→think→act *staging* in the signature prose.

CoAct reproduces the *function* (terminate on non-blank `answer`; reasoning via
the CoT layer) but not those exact fields. If `next-user-prompt` is valued, port
it as an optional `ThinkActCode` output in a follow-up — out of scope here.

react-agent will also now render the **compact** coact tools section (no verbose
roster). That is a rendering improvement, not a regression.

---

## 8. Tests

| File | Refs | Action |
|---|---|---|
| `components/agent/test/.../react_agent_test.clj` (1,307 ln) | `react-behavior-tree`, `thinking-loop-subtree`, `ThinkActAndEvaluate`, `normalize-evaluation-action`, `tool-calls-action`, `react-skill$thinking-loop` | **Rewrite.** Delete react-internal tests. **Salvage** the shared-machinery deftests that merely *live* here — `bind-tools-test`, `common-commands-registry-test`, `trace-helpers-test`, common `schema-validation-test` — into `coact_agent_test.clj` or a `common_*_test`. Keep one small test: react-agent is registered, is coact-derived, and its assembled system-context contains **no** code-blocks / channel-routing sections. |
| `components/agent/test/.../integration_test.clj:30,75–159` | `react-agent-end-to-end-test`, `react-agent-discovery-test` (`^:integration`, real LLM) | **Keep, verify.** Behavior is tool+answer, which the unified agent still does. Repoint the require; re-run against a live provider. |
| `components/agent/test/.../subagent_test.clj:92,99` | `tool-visible? :react-agent` | **No change** (still registered). |
| `components/agent/test/.../interface_test.clj:242,251` | `create-agent "react-agent"` + clone ns | **No change** (still dispatchable). |
| `components/agent-tui-persist/test/.../tree_test.clj:49,54` | string `"react-agent"` in persisted meta | **No change** (just a string). |
| `components/agent/test/.../coact_agent_test.clj` | coact BT/context | **Extend** with `:code-channel? false` cases: BT builds, omits code nodes; system-context omits the four code sections; `true`/default is byte-identical to today. |

---

## 9. Verification

Run in a live REPL per the project rule (dev classpath omits `*/test`; use
`bb repl:test <ns>` or `dev.repl-test/run`, never bare `require` of a test ns).

1. `bb test` green (unit).
2. New coact `:code-channel? false` tests: BT has no code-eval node; assembled
   system-context has none of {execution-model, channel-routing, code-blocks-format,
   sandbox-context-accessor}; default (`true`) context is **byte-identical** to
   pre-change (golden compare).
3. Non-regression for the 20 derived agents: assemble each agent's system-context
   before/after — must be identical (flag defaults true).
4. `^:integration` react e2e + discovery tests pass against a real provider
   (Bedrock `amazon.nova-lite-v1:0` works without keys via `AWS_PROFILE`).
5. Smoke: `bb tui -a react-agent` answers a tool-only question and **never**
   emits a code block.
6. JVM-from-source iteration first (`bb tui` / `-m … react-agent`); reserve the
   ~2-min native `bb build:ata` for the final check.

---

## 10. Risk register

- **Touching frozen coact core (§4).** The redesign synthesis froze the CoAct
  loop/BT/signature on purpose. Mitigation: the flag defaults `true`; prove
  byte-identical assembly for all 20 agents (§9.2–9.3) before merge.
- **`ThinkActCode` emitting `:code-blocks` despite a code-free prompt.** The BT
  ignores the field in 2-channel mode; optionally clear it defensively in the
  route action.
- **Salvaged shared tests.** Moving `bind-tools` / `common-commands` tests must
  not silently drop coverage — diff the deftest inventory before/after.
- **`ThinkActAndEvaluate`-only fields lost (§7).** Confirm no downstream consumer
  reads `next-user-prompt` / `request-for-information` from a react result. (Grep
  before deleting the signature.)
