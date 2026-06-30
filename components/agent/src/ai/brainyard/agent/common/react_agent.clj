;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.common.react-agent
  "ReAct (Reasoning and Acting) agent — a tool-only projection of the CoAct base.

   Historically react-agent carried its own DSPy signature
   (ThinkActAndEvaluate), behavior tree, and system-context assembler — a full
   parallel implementation of CoAct minus the code channel. Nothing derived from
   it (every specialist derives from coact via run-coact-derived), so it cost a
   ~1.2k-line duplicate that had to track coact's shared roster + substrate
   changes by hand.

   It is now the canonical **tool-only** coact-derived agent: it runs CoAct's
   loop, signature (ThinkActCode), substrates, and roster with the code-blocks
   channel disabled via the `:code-channel? false` config flag. CoAct's
   system-context then advertises only the tool-calls + answer channels, and the
   BT never routes to code-eval. This is the one supported way to get a strictly
   tool+answer agent (e.g. sandbox-free or RPC-only contexts) without a code
   channel — see docs/design/react-coact-unification-plan.md.

   `:code-channel?` defaults true, so every other coact-derived agent is
   unchanged."
  (:require [ai.brainyard.agent.core.tool :refer [defagent]]
            [ai.brainyard.agent.common.coact-agent :as coact]
            [ai.brainyard.agent.common.agent-roster :as agent-roster]))

(def ^:private react-instruction
  "Tool-only overlay merged on top of coact's (channel-agnostic) instruction.
   Reinforces that there is no code channel for this agent."
  "You are a tool-only ReAct agent: answer using the `tool-calls` channel and a
final `answer` only. There is no code-blocks channel — never emit fenced code to
execute. To reach a tool beyond the bound set, discover it with `list-tools` /
`get-tool-info` and invoke it through `tool-calls`.")

;; ============================================================================
;; Agent Registration
;; ============================================================================

(defagent react-agent
  "ReAct agent — CoAct's loop with the code channel disabled (tool-only)."
  coact/run-coact-derived
  ;; Pin the CoAct BT so direct-resolution entry points (setup-agent-by-id, used
  ;; by `bb tui -a react-agent`) get it instead of a nil/default factory. Normal
  ;; dispatch routes through run-coact-derived, which would fall back to coact's
  ;; BT anyway. Mirrors skill/explore/etc.
  :bt-factory (fn [{:keys [max-iterations]}]
                (coact/coact-behavior-tree max-iterations))
  :tool-use-control {}
  :input-schema  [:map
                  [:question [:string {:desc "User request or question to answer"}]]
                  [:agent-context {:optional true} [:string {:desc "Additional contextual information"}]]]
  :output-schema [:map
                  [:answer [:string {:desc "Final answer to the user's question"}]]]
  ;; Same shared roster as coact (run-coact-derived merges coact's onto this
  ;; with distinct, so this is the de-facto surface either way).
  :agent-tools agent-roster/default-agent-roster
  ;; The unification knob: disable the code-blocks channel. Drops the code
  ;; system-context sections and makes the BT route tool/answer only.
  :config-extra {:code-channel? false}
  :instruction react-instruction)
  ;; :tool-context intentionally omitted — inherits coact's
  ;; operational-recall-guidance via run-coact-derived.
