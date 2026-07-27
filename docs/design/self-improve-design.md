# Self-Improvement Loop — Implementation Plan (R1)

> Status: **implemented + live-verified** (plan 2026-06-24; shipped + verified
> 2026-06-25). Implements **R1** from `docs/design/hermes-comparison.md` —
> "Close the self-improvement loop": experience-triggered skill distillation,
> skill refinement, and TUI nudges *on top of existing bricks* — wiring triggers
> and a review gate, not new substrate. All three phases are committed behind
> default-off flags and **verified live in `bb tui`** (a real turn distilled a
> `git-pre-commit-audit` skill at sub-LM score 0.8 → staged → nudge surfaced next
> turn → accept promoted it to a live skill); the code also builds into the
> GraalVM native binary. Per-phase "Status: implemented" notes are inline below.
>
> Related: `docs/design/hermes-comparison.md` (R1), `docs/design/skills.md`,
> `docs/design/meta-agent-design.md`, `docs/design/memory-agent-design.md`,
> `docs/core/memory.md`.

## 1. Goal

Make the agent *autonomously* propose reusable skills and memory revisions off
trajectory signals, instead of waiting for an operator/LLM to invoke
`skills$write` / `memory$remember`. Every loop already exists as an
operator-invoked path; R1 adds the automatic **trigger** and a human **review
gate**.

## 2. What already exists (the templates we clone)

| Machinery | Location | Role here |
|---|---|---|
| Hook pub/sub (`fire!`, `fire-decision!`) | `components/agent/src/.../core/hooks.clj` | `:agent.ask/post`, `:agent.tool-use/post`, `:agent.session/closed`; self-install-at-load idiom |
| **Post-turn trigger template** | `.../common/memory_agent/hooks.clj` `consolidation-cadence-handler` (and `skill_distill.clj` `distill-handler`) | `:agent.ask/post` observer, root-only, config-gated, fires `future`. Phase 1 cloned this pattern. *(Originally cloned from `essence-capture-handler`, retired 2026-06-28; the cadence handler is the current live example of the same shape.)* |
| Review-gated authoring | `.../common/meta_agent.clj` | `meta-agent$validate` (dry-run) → `meta-agent$create` (persist). Our proposal→accept split mirrors it. |
| Skill drafting / write | `.../common/skill_agent.clj`, `skills.clj` `skills$write :op :create/:update` | Drafts and persists `SKILL.md`. |
| Memory revision ops | `.../common/memory_agent.clj` `:op :essence/:consolidate/:verify-fact/:correct` | Reused by Phase 2 conceptually. |
| **TUI nudge surface template** | `.../common/usage_nudge.clj` | `:agent.tool-use/post` → `:pending-usage-guides` in `bt-st-memory` → iteration `:notices`; plus `:agent.suggestion/next-user-prompt` → input-bar tip. |
| Trajectory source | `sessions/<id>/trajectory.edn` (1 record/turn, all iterations+answer) | Scorer input. |
| Config schema + precedence | `.../core/config.clj` (`:enable-memory-consolidation`, `:enable-skill-distillation` at config-schema) | Where gate keys go; env > per-agent > session > file > default. |

**Gap (at the time of writing):** all loops are operator/LLM-invoked; the
memory/distillation gate keys default false; scheduled consolidation is unwired;
skills are authored manually; nudges aren't surfaced. *(Update 2026-06-28: memory
L2→L3 consolidation now auto-runs via the `:enable-memory-consolidation` cadence +
session-end flush — see memory-agent-design.md §10.0.)*

## 3. Locked design decisions (2026-06-24)

1. **Review gate = staged proposal + user approval.** The distiller drafts to
   `<project>/.brainyard/skills/proposals/<name>/` (`SKILL.md` + `proposal.edn`),
   inert until a `skill-proposal$accept` runs `skills$write :op :create`. Mirrors
   meta-agent `validate→create`. Nothing goes live unreviewed.
2. **Pre-filter, then LLM judge.** A cheap deterministic gate (≥N tool calls,
   multi-iteration, no existing skill matches the gist) precedes any LLM scoring
   call — no LLM call on trivial turns. Mirrors the capture pipeline's debounce.
3. **Off by default, opt-in.** Every trigger key defaults `false` (exactly like
   `:enable-memory-consolidation`), enabled per-config for the root coact-agent only.

## 4. Phase 1 — Skill-distillation trigger

> **Status: implemented (2026-06-24).** Shipped behind
> `:enable-skill-distillation` (default false). Refinement vs. the original
> sketch: the `SkillDistillation` signature **emits the complete `SKILL.md`
> draft itself** (one sub-LM call), rather than spinning skill-agent to draft
> it — cheaper, and the draft is still staged for review, not written live.
> 10 tests / 61 assertions green.

New ns: `ai.brainyard.agent.common.skill-distill`.

1. **DSPy signature `SkillDistillation`** (model on `EssenceExtraction` in
   `memory_agent/signatures.clj`): in = compacted trajectory of the finished
   turn; out = `{:reusable :score 0..1 :proposed-name :rationale :skill-md}`.
   (Output key is `:reusable`, not `:reusable?` — Anthropic tool-schema
   property names forbid `?`.)
   Sub-LM default `claude-code:sonnet`.
2. **Deterministic pre-filter** — skip trivial turns (single tool call, pure Q&A,
   errored) before any LLM call. It counts **action steps, not iterations**:
   every tool call is a step, and a code block contributes one step per
   top-level statement. Counting inside the block matters — a capable model
   batches a whole multi-step shell procedure into ONE code block (exactly the
   turn most worth distilling), and an iteration-level count scored that as 1
   and dropped it silently. Verified against real trajectories: a 3-part
   release-readiness audit written as a single 27-statement script now scores
   27 and reaches the scorer, while a turn that genuinely ran one command still
   scores 1 and is skipped.
3. **`:agent.ask/post` observer** (cloned from the then-current
   `essence-capture-handler`; that handler was retired 2026-06-28 — the
   structurally identical `consolidation-cadence-handler` is now the live
   reference): `root-agent?` + not-a-specialist + gated on `:enable-skill-distillation`;
   reads latest `trajectory.edn` and pre-filters INLINE (free, sub-ms), then
   hands scoring to a background task (see §7); scorer;
   if `score ≥ :skill-distill-threshold` (default 0.7), call skill-agent to draft
   `SKILL.md`, then **stage** (do not persist as live). Self-install via
   `install-skill-distillation!`, idempotent, `:source :skill-distill`, runtime
   atom guard for native-image.
4. **Proposal staging + review gate.** Stage under
   `.brainyard/skills/proposals/<name>/SKILL.md` + `proposal.edn` (source
   session/turn, score, rationale). New command family
   `skill-proposal$list/read/accept/reject`; `accept` calls
   `skills$write :op :create`; `reject` removes the staging dir.
5. **Config keys** (`core/config.clj`): `:enable-skill-distillation`
   (bool, false, env `BY_ENABLE_SKILL_DISTILLATION`), `:skill-distill-threshold`
   (double, 0.7), `:skill-distill-mode` (`:at-cadence` | `:per-turn`) and
   `:skill-distill-every-n-turns` (12) — see §4a.

## 4a. Batched scoring — `:at-cadence` (default)

Scoring every qualifying turn on its own was one sub-LM call per turn, and it
could only ever see one turn at a time. Both are fixed by batching, modelled on
`:graph-extract-mode`:

- **Accumulate, don't score.** The free pre-filter still runs inline every turn;
  a qualifying turn's NUMBER is recorded (`!candidates`, keyed by session) and
  nothing else happens. Only turn numbers are held — the window is re-read from
  `trajectory.edn` at batch time, so the atom stays small however long a session
  runs and a crash costs a window, not memory.
- **Fire on the cadence.** Every `:skill-distill-every-n-turns` turns, the
  accumulated window is judged in ONE call via `SkillDistillationBatch`. The
  counter advances on every turn, not just qualifying ones, so batch timing is
  predictable; a boundary reached with no candidates is skipped with no LLM
  call at all.
- **Flush at session end.** `:agent.instance/closed` batches whatever is
  pending, so a session that ends mid-window — or one shorter than the window,
  which would otherwise *never* distill anything — still gets judged. The window
  is taken from the accumulator before submission, so turns are never scored
  twice, and per-session state is cleared on close so nothing leaks across a
  resume.
- **One proposal per window.** `SkillDistillationBatch` returns the single best
  candidate, not a list: the review gate is a human, and burying them in five
  proposals per window is worse than handing them the strongest one. The staged
  proposal records `:turns` — every turn that fed the judgment.

The reach matters as much as the cost. A reusable procedure often spans turns
(investigate → change → verify); judged one turn at a time each looks like a
fragment, and the batch view is the only one that sees it whole.

`:per-turn` keeps the original behaviour for anyone who wants a proposal the
moment a turn qualifies.

**Ordering note:** the session-end flush submits a task, so the TUI's `stop!`
runs `await-self-improve!` + `task-shutdown` *after* the session-close block —
shutting the manager down first would leave that submission with no pool to run
on and nothing to drain it.

Tests: pre-filter units; stubbed scorer; hook eligibility (root-only,
config-gated, off-on-specialists); staging round-trip; accept → `skills$write`.

## 5. Phase 2 — Skill-refinement trigger

> **Status: implemented (2026-06-24).** Shipped behind
> `:enable-skill-refinement` (default false). 6 tests / 31 assertions green
> (full R1 suite: 24 / 132).

New ns: `ai.brainyard.agent.common.skill-refine`.

Dynamic SKILL.md skills register as `:skill$<name>` tools (skills.clj) and
return `{:error-message … :skill <name>}` on failure. The refine hook:

1. `:agent.tool-use/post` observer, config-gated (`:enable-skill-refinement`).
2. **Divergence pre-filter** (free, deterministic): `divergence?` = the call is
   a `skill$*` tool AND the result is an error. A failed run is the clearest
   "outcome ≠ documented steps" signal; it gates the LLM judge so non-skill /
   successful calls cost nothing. (LLM-judged *non-error* divergence is
   deferred.)
3. Background task (see §7): fetch the current SKILL.md (`skills$read`), run the
   **`SkillRefinement`** signature (`{:should-revise :revised-md :rationale}` —
   note `should-revise`, no `?`, per the Anthropic tool-schema key rule). The
   judge only revises when the *document* is at fault (missing step, wrong
   assumption), not a transient/environmental/user error.
4. `stage-refinement!` (pure, unit-tested) stages a `:kind :refinement` proposal
   into the same `.brainyard/skills/proposals/` area, carrying the failure
   `:evidence`.
5. **Kind-aware accept:** `proposals/accept-proposal!` now branches on the
   proposal's `:kind` — `:refinement` → `skills$write :op :update` (scope
   auto-detected), `:distillation` → `:op :create` (project scope). Conceptually
   the memory-agent `:op :correct` shape.

Tests: skill-invocation / error / divergence detection; `stage-refinement!`
matrix; accept refinement → `:update` (no forced scope) and distillation →
`:create` (project scope).

## 6. Phase 3 — Surface nudges in the TUI

> **Status: implemented (2026-06-24).** Shipped behind
> `:enable-self-improve-nudges` (default false). 7 tests / 34 assertions green
> (full Phase 1+3 suite: 17 / 95).

New ns: `ai.brainyard.agent.common.self-improve-nudge` (sibling to
`usage_nudge`).

- **Per-turn LLM notice** — chosen surface. At turn start (`maybe-queue!`,
  called from `coact-init`, root + config gated), compare the proposals on disk
  to the set already surfaced this session (`:self-improve-nudged` in the
  agent's cross-turn `st-memory-init`). Any FRESH proposal queues a one-line
  notice into `bt-st-memory` (`:pending-self-improve-notice`); the coact
  iteration-record builder drains it (`drain-iteration-notice!`) into the
  record's `:notices` — the same LLM-facing field `usage_nudge` uses (combined
  at the drain site). The model relays it to the user.
- **Self-heal:** the surfaced set is re-intersected with what's still on disk
  every turn (persisted each call, not only when queueing), so an
  accepted/rejected proposal drops out and a later re-stage nudges again, while
  a still-pending one never re-nags.
- **System-context was rejected** as the home (e.g. mirroring `## Project
  Memory`): a volatile pending-proposals note there would thrash the
  session-stable prefix cache. `:notices` is per-turn and uncached — correct.
- **TUI display:** the notice also renders **in the iteration display block**,
  not only to the LLM. `bt.clj` adds the just-built record's `:notices` to the
  `:agent.iteration/post` event; the agent-tui `iteration-post-handler` stores
  it in the block state; `render-iteration-block-lines` draws a wrapped section
  capped at `notice-max-lines` (6) with a `[-N lines]` marker — so a short nudge
  shows in full while a long usage guide can't dominate the block. (This makes
  every `:notices` source visible, including usage-nudge guides.)
- **Deferred:** the `memory$stats`-threshold nudge and a dedicated status-bar /
  `:agent.suggestion/next-user-prompt` surface — `:notices` (LLM relays + the
  iteration block) covers the proposal case; a pure-answer turn with zero
  tool/code iterations carries no record, so the nudge waits for the next acting
  turn (minor).

Gated by `:enable-self-improve-nudges` (false). Tests: queue-when-fresh,
suppress-while-pending, self-heal-after-accept, drain-once, root/config gating.

## 7. Cross-cutting

- **No new substrate** — hook handlers + one DSPy signature + a small command
  family + config keys.
- **Off-turn execution runs on the task manager**, not a bare `future`
  (`skill_distill/background.clj`, `run-off-turn!` → a `:fn` job). Scoring is a
  tens-of-seconds sub-LM call that must never touch the turn thread, but a
  `future` per job was unbounded (one call per eligible turn, no damper),
  invisible, and killed on a daemon thread at exit *after* the tokens were
  spent. As `:fn` tasks the jobs are **bounded** by the manager's fixed pool,
  **single-flight** per (kind, key) — session-id for distillation, skill name
  for refinement, which also removes the race where two scorers staging the
  same proposal name interleave `SKILL.md` and `proposal.edn` — **visible** in
  `/task` with a per-task `output.log` recording the decision, and **drained**
  for a bounded grace period at `/quit` (`agent/await-self-improve!`, before
  `task-shutdown` cancels). Two deliberate omissions: no
  `:coact/pending-from-iter` metadata, so the jobs never reach the model's
  in-flight surfaces or hold a turn; and `:display-mode :background`, so they
  render no TUI block. Submission uses `get-default-manager` (which lazily
  auto-initializes) — peeking instead would fall back to a `future` in any
  session that had not already run a task; the reads (`in-flight-tasks`,
  `await-quiet!`) do peek, so the exit drain never resurrects a manager just to
  find it empty. A `future` fallback remains for the case where the task layer
  itself faults.
- **Native-image** — self-install behind a `compare-and-set!` runtime atom (the
  `usage_nudge` `!installed` idiom) so baking can't freeze `true`.
- **Polylith** — all in `components/agent`; new commands exposed via the
  component interface, not internal namespaces.

## 8. Sequencing

Phase 1 → Phase 3 → Phase 2. Phase 3 follows 1 so proposals are visible the
moment they're produced; Phase 2 last (lowest volume, depends on Phase 1's
staging machinery).

> **All three phases implemented (2026-06-24)**, each behind its own
> default-off config key (`:enable-skill-distillation`,
> `:enable-self-improve-nudges`, `:enable-skill-refinement`). Full R1 test suite:
> **24 tests / 132 assertions green**; Phase 1 additionally real-LLM-verified
> with `claude-code:opus`. Three runtime-installed hooks
> (`:agent.ask/post` distill, `:agent.ask/post`→`:notices` nudge,
> `:agent.tool-use/post` refine), one shared staging store + review-gate command
> family (`skill-proposal$*`), and two DSPy signatures (`SkillDistillation`,
> `SkillRefinement`). Deferred follow-ups: skill interop / `agentskills.io`
> alignment (R5a), the `memory$stats`-threshold nudge, a dedicated status-bar
> surface, and LLM-judged non-error skill divergence.
