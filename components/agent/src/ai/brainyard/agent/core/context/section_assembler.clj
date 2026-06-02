;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.core.context.section-assembler
  "SectionAssembler protocol — generic prompt-section assembly for agents.

   Each agent (CoAct, ReAct, …) provides a record implementing this protocol.
   `coact-init-action` (and the future ReAct equivalent) calls the protocol
   methods to build per-section text, run `context-budget/enforce`, and then
   re-compose per-message strings.

   Design notes:
     - `sections`     returns the merged {section-kw text} map for the turn.
                      Caller passes a `state` map containing every input the
                      assembler needs (sandbox-bindings, instruction,
                      conversation, …). Stateless w.r.t. the assembler value.
     - `system-order` / `user-order` partition section keys into the two
                      message slots. The current dual-`stable-keys` layout puts
                      both system-context and user-context into the system
                      message; downstream design (cache breakpoints) keeps the
                      partition because it drives the within-turn breakpoint.
     - `policies`     returns the section-policy table consumed by
                      `context-budget/enforce`. Defaults to
                      `cb/default-section-policies` for most agents.
     - `strategies`   returns the compaction closures keyed by strategy. The
                      closures capture the `st-memory` atom so they can both
                      mutate per-turn state and re-render the affected
                      section text — matching the contract of `enforce`.")

(defprotocol SectionAssembler
  (sections [this state]
    "Return {section-kw text} for ALL sections this turn (system + user
     combined). Sections not present in the returned map are skipped when
     composing the prompt.")
  (system-order [this]
    "Render order for sections that ride the system message. Sections
     missing from `sections` are skipped silently.")
  (user-order [this]
    "Render order for sections that ride the user message. Sections
     missing from `sections` are skipped silently.")
  (policies [this]
    "Return {section-kw {:priority N :compact strategy-kw}}. Used by
     context-budget/enforce. Sections without `:compact` are immutable —
     enforce reports `:over-budget? true` rather than dropping them.")
  (strategies [this st-memory]
    "Return {strategy-kw (fn [sections] -> sections')}. Each strategy
     should both mutate the relevant slice of `@st-memory` and return a
     section map reflecting the post-mutation state. Strategies that make
     no progress (same token count for the targeted section) are taken as
     a signal to drop the section."))

(defn order
  "Global render order: system sections followed by user sections."
  [assembler]
  (into (vec (system-order assembler)) (user-order assembler)))
