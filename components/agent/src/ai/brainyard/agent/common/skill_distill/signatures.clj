;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.common.skill-distill.signatures
  "DSPy signature behind the skill-distillation scorer (R1 — self-improvement
   loop, docs/design/self-improve-design.md).

   `SkillDistillation` reads a just-finished turn's trajectory (the question +
   compacted iteration trace) plus the names of skills that already exist, and
   decides whether the turn embodies a NOVEL, REUSABLE procedure worth saving
   as a skill. When it does, it emits a complete `SKILL.md` draft in one call —
   cheaper than spinning the skill-agent for the draft, and the draft is staged
   as a *proposal* for the user to accept, not written live.

   Compiled lazily at namespace load — no LLM call until `chain-of-thought` is
   invoked (mirrors `memory-agent.signatures`)."
  (:require [ai.brainyard.clj-llm.interface :refer [defschemas defsignature]]))

;; ============================================================================
;; Shared schema fragments
;; ============================================================================

(defschemas skill-distill-domain
  {::score [:double {:min 0.0 :max 1.0
                     :desc "0.0..1.0 — confidence this turn is a novel reusable procedure worth a skill"}]
   ::skill-name [:string {:desc "Proposed skill name: lowercase-kebab, leading letter, ^[a-z][a-z0-9-]*$ (empty when not reusable)"}]})

;; ============================================================================
;; SkillDistillation
;; ============================================================================
;;
;; A turn is skill-worthy when it taught a GENERALIZABLE, repeatable procedure
;; — a multi-step recipe the agent (or user) would want to replay on a similar
;; future task. Most turns are NOT: one-off Q&A, a single edit, a lookup, or a
;; failure carry no reusable procedure. Empty/low-score is the common case; the
;; deterministic pre-filter already dropped the obviously-trivial turns before
;; this signature runs, so err toward a skill only when the procedure is clear
;; and not already covered by an existing skill.

(defsignature SkillDistillation
  "You judge whether ONE just-finished agent turn embodies a NOVEL, REUSABLE
procedure worth saving as a skill, and if so you draft the skill.

A turn is skill-worthy ONLY when ALL hold:
- It carried out a GENERALIZABLE, multi-step procedure (a recipe), not a
  one-off answer, a single file edit, or a simple lookup.
- That procedure would plausibly REPEAT on a similar future task.
- No skill in `existing-skills` already covers it (do not duplicate).

Most turns are NOT skill-worthy — return :reusable false with a low :score
and an empty :skill-md when in doubt. A weak or speculative skill is worse
than none.

When (and only when) the turn IS skill-worthy:
- :reusable     true
- :score        your confidence 0.0..1.0 (the caller stages a proposal only
                past a configured threshold)
- :proposed-name  lowercase-kebab, leading letter (^[a-z][a-z0-9-]*$), short
                  and descriptive; must not collide with an existing skill
- :rationale    one or two sentences: what the reusable procedure is and why
                it is worth keeping
- :skill-md     a COMPLETE SKILL.md document: YAML front-matter (name,
                description) followed by a concise, imperative how-to distilled
                from THIS turn's actual steps — the tool sequence, the order,
                the decision points. Write it as instructions for next time,
                not a narration of what happened. No secrets, no machine-
                specific absolute paths, no one-off data.

When NOT skill-worthy: :reusable false, :score low, :proposed-name \"\",
:rationale one line on why it is not generalizable, :skill-md \"\"."
  {:inputs  {:turn-question   [:string {:desc "The user's question/request that drove the turn"}]
             :turn-trajectory [:string {:desc "Compacted per-iteration trace of the turn: channels, thoughts, tool calls + results, code + output"}]
             :existing-skills [:string {:desc "Existing skill names + one-line descriptions, one per line — do NOT duplicate any of these"}]
             :project-context [:string {:desc "Optional one-line project context (or empty)"}]}
   :outputs {:reusable      [:boolean {:desc "True only when the turn is a novel, generalizable, repeatable procedure not already covered"}]
             :score         ::score
             :proposed-name ::skill-name
             :rationale     [:string {:desc "One or two sentences on the reusable procedure (or why it is not generalizable)"}]
             :skill-md      [:string {:desc "Complete SKILL.md draft (front-matter + imperative how-to) when reusable; empty string otherwise"}]}})

;; ============================================================================
;; SkillRefinement — Phase 2 (skill refinement)
;; ============================================================================
;;
;; Fired when a dynamic `skill$<name>` invocation FAILED. Given the current
;; SKILL.md, the call args, and the failure, decide whether the SKILL.md itself
;; is at fault (a missing step, a wrong assumption, an unhandled case) versus a
;; transient/environmental/user error that no doc change would fix. Only the
;; former warrants a revision. Output property keys avoid `?` (Anthropic
;; tool-schema property names forbid it) — hence `should-revise`, not
;; `should-revise?`.

(defsignature SkillRefinement
  "A run of the `{skill-name}` skill (a SKILL.md procedure) just FAILED. You
decide whether the SKILL.md itself should be revised, and if so you rewrite it.

Revise ONLY when the failure points to a defect in the DOCUMENT — a missing
step, a wrong/outdated assumption, an unhandled input, an ambiguous instruction
the runner plausibly followed into the failure. Do NOT revise for:
- transient/environmental failures (network, missing credential, flaky tool),
- user/caller error (bad input the skill can't be expected to handle),
- a one-off that no general doc change would prevent.

When (and only when) the document is at fault:
- :should-revise   true
- :revised-md      the COMPLETE updated SKILL.md (front-matter + body), a
                   focused edit of the current one that addresses THIS failure
                   without gratuitous rewrites; keep the same name/intent. No
                   secrets, no machine-specific absolute paths.
- :rationale       one or two sentences: what was wrong and what you changed.

When the document is NOT at fault: :should-revise false, :revised-md \"\",
:rationale one line on why the failure is not a doc defect."
  {:inputs  {:skill-name       [:string {:desc "The skill that failed (no skill$ prefix)"}]
             :current-skill-md [:string {:desc "The current SKILL.md content, verbatim"}]
             :invocation-args  [:string {:desc "The args the skill was called with (pr-str)"}]
             :failure-evidence [:string {:desc "The error/result the failed invocation returned"}]}
   :outputs {:should-revise [:boolean {:desc "True only when the SKILL.md document itself is at fault for the failure"}]
             :revised-md    [:string {:desc "Complete revised SKILL.md when should-revise; empty string otherwise"}]
             :rationale     [:string {:desc "One or two sentences: what was wrong and what changed (or why no revision)"}]}})
