# ACP-Agent — Why the Lightweight Series Stops Here (the boundary case)

> **Status:** Scope note, not a redesign. Asks whether the
> [series](./agent-lightweight-redesign-synthesis.md) arguments apply to
> acp-agent. The honest answer is **no — and that's the useful finding.**
> acp-agent is categorically different from every other agent in the fleet: it is
> a **transport adapter**, not a CoAct reasoning agent. It authors nothing, has no
> micro-tools, and runs no LLM loop on the brainyard side — so there is no
> structured authoring to make lightweight, no substrate to inherit, and no
> dossier to retire. It is the series' **limit case**, and documenting that
> precisely keeps anyone from trying to force a redesign onto it.
>
> **Scope:** `components/agent/src/ai/brainyard/agent/common/acp_agent.clj`.
> No change proposed.

---

## 1. What acp-agent actually is

Every agent the series touched is **CoAct-derived** (`coact/run-coact-derived`),
with an `:instruction`, a `:tool-context`, an `:agent-tools` roster, and a family
of `*$` helpers it calls to author artifacts. acp-agent is none of that:

- It uses **`agent/run-agent` directly** with its **own one-iteration BT**
  (`acp-behavior-tree`: `:condition has-question → :repeat max-n=1 [:action
  acp-prompt] → :condition has-answer`). It is not coact/react-derived.
- **`:agent-tools nil :instruction nil :tool-context nil`.** No roster, no
  prompt, no helper family. There is nothing to bind, retire, or rewrite.
- **The external ACP backend owns the iteration loop.** acp-agent forwards the
  `:question` to a backend (default `:stub`; real `:claude-code` / `:gemini`
  / `:codex`) over the Agentic Context Protocol and **streams the
  answer back**. The reasoning, tool use, planning, and editing all happen on the
  *external* side.

What the brainyard side *does* is pure plumbing:

- **Transport**: soft-resolved `spawn! / initialize! / new-session! / prompt! /
  close!` against `ai.brainyard/acp-client` (via `requiring-resolve`, so the ns
  loads even without the dep).
- **Translation**: each `session/update` notification → a brainyard hook event
  (`:agent.dspy-action/chunk`, `:todo/updated`, `:agent.tool-use/pre|post`) via
  `translate-update`, so the existing TUI renders an external agent's stream with
  no new UI code.
- **Permission bridge**: ACP `session/request_permission` → the TUI's N-option
  picker (`:user-feedback-fn`), with deny-by-default when no interactive session
  is wired.
- **Lifecycle**: per-agent AcpClient cache + an `:agent.instance/closed` cleanup
  hook.

That is the whole agent. It is an **adapter between an external agent process and
brainyard's TUI/hook substrate.**

## 2. Why the series' arguments don't apply

Each pillar of the series presupposes something acp-agent doesn't have:

| Series pillar | Presupposes | acp-agent |
| --- | --- | --- |
| Retire structured-authoring micro-tools (dossier-frontmatter chains) | The agent *authors* an artifact via helpers | **Authors nothing.** No `acp$*` helpers exist; no dossier, plan, todo, verdict, or record is written on this side. Nothing to retire. |
| Author markdown directly | There's an LLM *on this side* writing content | **No brainyard-side LLM step.** The model is the external backend; the brainyard side only relays bytes. |
| Keep deterministic read seams | The agent *reads* typed artifacts | No artifacts to read. The "reads" here are protocol RPC (`prompt!`, `translate-update`) — already pure mechanism. |
| Base substrates (track / execute / edit / use-a-skill) | The agent is coact/react-derived and inherits base system-context | **Not derived from the base agents.** It can't inherit a substrate, and it has no inline work to do — the external backend does the work with its *own* tools. |
| Judgment vs. mechanism split | A mix of LLM judgment + deterministic helpers to separate | **100% mechanism on this side.** Judgment is fully externalized; there is no judgment/mechanism boundary to redraw. |

So there is no brittle constructor, no over-tooled persistence, no per-turn
authoring obligation — the failure modes the series targets simply don't exist
here.

## 3. acp-agent is the series' limit case (and confirms the principle)

The redesign series converged on one rule: **let the LLM author (prose/code);
keep the machine doing mechanism (parse / diff / validate / register / transport)
— and the more an agent's job is mechanism, the less there is to make
lightweight.** Walk that gradient and acp-agent is the endpoint:

- plan/exec/eval — mostly authoring → lots to retire.
- edit / skill / tool / meta — mostly mechanism (the `*$apply` / `*$validate` /
  register kind) → little to retire, "keep the mechanism."
- **acp-agent — entirely mechanism, judgment fully externalized → nothing to
  retire.** It is already exactly what the series argues *for*: the brainyard
  side is pure deterministic plumbing, and the LLM-authoring lives where the LLM
  is (the external backend).

So acp-agent isn't a counterexample to the series — it's the **clean confirmation
at the boundary**: when an agent is a pure adapter, "make authoring
LLM-inherent" has no target, and "keep the mechanism" endorses the whole agent
as-is.

## 4. The substrate boundary (one genuinely useful clarification)

There *is* a subtle interaction worth stating, because it defines where the
substrates' authority ends. The substrates (todo / exec / edit / skill) are base
**system-context** protocols inherited by **brainyard-side CoAct agents**. acp-agent
isn't one, and the external backend it drives has *its own* loop, tools, and
discipline. So:

> **Substrates govern brainyard-side CoAct agents. An ACP backend's work is the
> backend's own — brainyard does not impose its checklist/evidence/safe-write
> discipline on it; it *observes* it.**

The observation channel is the hook bridge: when the external agent edits a file
or updates a todo, that surfaces as `:agent.tool-use/*` / `:todo/updated` hook
events — so brainyard's TUI *renders* the external agent's work using the same
machinery the substrates feed, **without** owning or constraining it. That's the
right boundary: the substrates are how *our* agents behave; the hook bridge is how
we *display* someone else's agent behaving.

## 5. The one forward-looking question (Phase 6 interop, not a redesign)

When real backends land (Phase 6), one series idea *could* become relevant — not
as a lightweight redesign of acp-agent, but as an **interop contract**:

- If an external ACP backend produces durable artifacts (a plan, a todo, an edit
  record), should they conform to brainyard's **markdown-dossier / checklist
  formats** so brainyard-side readers (`plan$read-dossier`, `todo$read`,
  `eval$read-verdict`, …) can consume them — letting an ACP backend slot into the
  `plan → todo → exec → eval` pipeline as a stage?

That is a backend-contract question (the backend chooses its output shape), and
forcing it is out of scope here. It's worth a line in the ACP design doc as the
*one* place the series and ACP touch: the series defines the artifact formats; an
ACP backend that wants to interoperate with brainyard's pipeline would emit them.
acp-agent itself — the adapter — still changes nothing.

## 6. Recommendation

**No redesign.** Leave acp-agent as the bare-BT transport adapter it is; it is
already pure mechanism (the kind the series keeps). Record the boundary:

- The lightweight series applies to **CoAct-derived agents that author artifacts
  via micro-tools**. acp-agent is neither, so it's out of scope by construction.
- The substrate authority ends at the brainyard/ACP seam (§4).
- The only future touchpoint is an **artifact-format interop contract** for ACP
  backends that want into the pipeline (§5) — a Phase-6 design note, not a change
  to this adapter.

This marks the boundary of the sweep: the series covers the CoAct-derived
**authoring specialists** (plan / explore / todo / exec / edit / eval / research /
main / workflow / skill / tool / meta). Other CoAct-derived agents that don't
author artifacts via micro-tools — e.g. mcp-agent (its own pending note),
rlm-agent, init / config / memory / debug / hook-agent — are out of scope for the
same reason, and acp-agent is the extreme of that: a pure transport adapter that
sits outside the series by design.
