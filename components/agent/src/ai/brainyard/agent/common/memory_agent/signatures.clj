;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.common.memory-agent.signatures
  "DSPy signatures front the LLM decisions inside memory-agent ops.
   Each lives here so the prompt is one file to read for reviewers and
   the schema is one place to evolve. Compiled lazily at namespace
   load — no LLM call happens until `chain-of-thought` is invoked.

   Phase 3 ships EssenceExtraction (per-turn essence capture).
   Phase 4 will add LlmReducer (L2 → L3 consolidation).
   Phase 5 will add FactVerification (L3 fact age-check).

   Outputs are Malli-validated; the `memory$essence-extract` command
   in `memory_agent.commands` wraps the signature so the LLM that
   drives memory-agent doesn't have to hand-craft JSON."
  (:require [ai.brainyard.clj-llm.interface :refer [defschemas defsignature]]))

;; ============================================================================
;; Shared schema fragments
;; ============================================================================

(defschemas memory-agent-domain
  {::confidence    [:double {:min 0.0 :max 1.0
                             :desc "0.0..1.0 — >= 0.8 = high, 0.5..0.8 = medium, < 0.5 = low"}]
   ::tag-vec       [:vector {:desc "Short stable labels"} :string]
   ::source-id-vec [:vector {:desc "Source entry ids this row is distilled from"} :string]})

;; ============================================================================
;; EssenceExtraction — Phase 3
;; ============================================================================
;;
;; Inputs: a just-finished turn's summary, last few messages, and a recent
;; slice of L2 episodes. Output: zero to three short statements worth
;; remembering beyond this turn, each kind-tagged and confidence-scored
;; with pointers back to the source L2 episode ids.
;;
;; The LLM is instructed to EMIT EMPTY when nothing is essence-worthy —
;; most turns are operational chatter and should not lift a fact to L3.
;; The downstream `:op :essence` handler treats an empty vector as
;; `:status :no-op` and just appends an empty record to essence.log.

(defschemas essence-domain
  {::essence-kind  [:enum {:desc "Essence category"}
                    "fact" "observation" "user-context"]})

(defsignature EssenceExtraction
  "You distill one just-finished agent turn into ZERO to THREE short
statements worth remembering beyond the turn. Prefer facts about the
user, the project, or generalizable lessons over tool-by-tool play-by-
play. Empty output is the COMMON case — most turns are operational
chatter and produce nothing essence-worthy.

For each essence emitted:
- :kind         — \"fact\" (durable, generalizable) | \"observation\"
                  (this-session timeline note) | \"user-context\" (user
                  preference, role, or stated constraint)
- :content      — one sentence, no examples, no filler
- :tags         — short stable labels (topic:* / user:* / project:*)
- :confidence   — 0.0..1.0. >=0.8 will be auto-promoted to L3
- :source-ids   — L2 episode ids the essence is distilled from
- :rationale    — one short line on WHY this is worth carrying forward

Do NOT lift tool failures, transient errors, or play-by-play of one
tool call into an essence — those are already in L2 and carry no
generalizable signal. Do NOT exceed three essences per turn — if the
turn surfaced more than three, prioritize user-context > fact >
observation."
  {:inputs  {:turn-summary    [:string {:desc "Caller's one-paragraph summary of what just happened (or empty)"}]
             :turn-messages   [:string {:desc "Last few messages of the turn, newline-delimited"}]
             :recent-episodes [:string {:desc "Last ~20 L2 episodes, one per line: <id> <kind> <content-truncated>"}]
             :user-id         [:string {:desc "User id (so essences can carry user-scoped tags)"}]}
   :outputs {:essences
             [:vector {:desc "Zero to three essence maps; empty when nothing is essence-worthy"}
              [:map
               [:kind        ::essence-kind]
               [:content     [:string {:desc "One-sentence essence body"}]]
               [:tags        ::tag-vec]
               [:confidence  ::confidence]
               [:source-ids  ::source-id-vec]
               [:rationale   [:string {:desc "Why this is worth remembering"}]]]]}})

;; ============================================================================
;; FactVerification — Phase 5
;; ============================================================================
;;
;; Challenge a stored L3 fact against fresh evidence. Verdicts:
;;   :still-true — fact remains accurate; no change needed.
;;   :refine     — fact is mostly right but needs wording/scope refinement;
;;                 we rewrite :content + bump :confidence.
;;   :wrong      — fact is contradicted by evidence; we tombstone it and
;;                 (when user-evidence is authoritative) write a counter-fact.
;;
;; Conservatism rule: absence of evidence is NOT refutation. Only mark
;; :wrong when the evidence directly contradicts the fact.

(defschemas verification-domain
  {::verdict [:enum {:desc "Verification verdict"}
              "still-true" "refine" "wrong"]})

(defsignature FactVerification
  "Decide whether a stored L3 fact is still true given fresh evidence.

You may:
  :still-true — keep the fact as-is (the common case for well-cited facts
                with no contradicting evidence).
  :refine     — rewrite the wording or scope. Output :refined-content
                MUST be a complete replacement sentence; we upsert by
                the fact's stable :id. Bump :new-confidence to reflect
                the additional grounding.
  :wrong      — the evidence directly contradicts the fact. We will
                tombstone it. Set :refined-content to a counter-fact
                statement (what is now believed to be true) — the
                caller decides whether to write it as a new L3 fact.

Conservatism rules:
1. Absence of evidence is NOT refutation. If :evidence is empty AND
   :fresh-recall doesn't directly contradict, prefer :still-true.
2. User-supplied :evidence is authoritative — when present and it
   contradicts, return :wrong. When present and it elaborates,
   return :refine.
3. Set :new-confidence in [0.0, 1.0]:
     :still-true → keep original confidence (or bump by ≤ 0.1 if
                   fresh-recall reinforces).
     :refine     → 0.7 .. 0.95 depending on evidence strength.
     :wrong      → reflects the COUNTER-fact's confidence (typically
                   >= 0.9 when user-supplied; 0.7..0.85 otherwise).
4. :rationale — one short sentence on WHY you reached this verdict.
   Cite the strongest evidence/recall signal driving the decision."
  {:inputs  {:fact          [:map {:desc "The stored L3 fact under review"}
                             [:id         [:string {:desc "Fact entry-id"}]]
                             [:content    [:string {:desc "Current fact body"}]]
                             [:confidence [:double {:min 0.0 :max 1.0 :desc "Current confidence"}]]
                             [:tags       ::tag-vec]]
             :fresh-recall  [:string {:desc "Cross-layer recall on the fact's content (empty when no hits)"}]
             :evidence      [:string {:desc "User-supplied evidence body (empty when no evidence)"}]}
   :outputs {:verdict         ::verdict
             :refined-content [:string {:desc "Replacement content (refine) or counter-fact (wrong); empty when still-true"}]
             :new-confidence  ::confidence
             :rationale       [:string {:desc "One sentence justifying the verdict"}]}})

;; ============================================================================
;; LlmReducer — Phase 4
;; ============================================================================
;;
;; Cluster a windowed slice of L2 episodes into a small set of distilled
;; L3 facts. Each fact must cite its source episode ids. Aim for high
;; information density — one fact per real-world topic, not one fact
;; per episode. Output is empty when the window has nothing worth
;; consolidating (operational chatter, isolated tool errors).

(defsignature LlmReducer
  "You cluster a windowed slice of L2 episodes into a small set of
distilled L3 facts. The goal is HIGH INFORMATION DENSITY — one fact
per real-world topic, not one fact per episode.

Rules:
1. Empty output is fine. Operational chatter, isolated errors, and
   short-lived tool outputs do NOT belong in L3.
2. Aim for AT MOST 5 facts per window. If you would produce more, the
   window is too wide — pick the most generalizable ones.
3. Each fact must cite its source episode ids in :source-episode-ids.
4. If an existing L3 fact already covers the topic and your distillation
   would refine it, cite the prior fact id in :supersedes-fact-ids and
   write a refined version with higher confidence. Otherwise leave
   :supersedes-fact-ids empty.
5. :confidence — be conservative. Multi-source agreement → >= 0.8.
   Single-source claims → 0.5..0.7. Inferred or interpretive → < 0.5.
6. :tags — short stable labels (topic:* / user:* / project:*). Reuse
   tags from the source episodes when applicable so future recall
   surfaces both."
  {:inputs  {:episodes
             [:vector {:desc "L2 episodes in the window, in chronological order"}
              [:map
               [:id         [:string {:desc "L2 entry id"}]]
               [:content    [:string {:desc "Episode content"}]]
               [:tags       ::tag-vec]
               [:created-at [:string {:desc "ISO-8601 or epoch ms (callers may pass either form)"}]]]]
             :window-desc      [:string {:desc "Human description of the window (e.g. \"last 2 hours\", \"session s-42\")"}]
             :existing-l3-hits [:string {:desc "Existing L3 facts that recall on this window (one per line: <id> <content-truncated>); empty when no overlap"}]
             :user-id          [:string {:desc "User id (so facts can carry user-scoped tags)"}]}
   :outputs {:facts
             [:vector {:desc "Distilled L3 facts (0..5); empty when nothing in the window warrants consolidation"}
              [:map
               [:content             [:string {:desc "One-sentence fact body"}]]
               [:kind                [:enum {:desc "Fact category"} "fact" "observation"]]
               [:tags                ::tag-vec]
               [:confidence          ::confidence]
               [:source-episode-ids  ::source-id-vec]
               [:supersedes-fact-ids [:vector {:desc "Prior L3 fact ids this distillation refines/replaces (empty when no overlap)"} :string]]]]}})
