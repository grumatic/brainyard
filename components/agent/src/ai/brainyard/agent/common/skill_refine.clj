;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.common.skill-refine
  "Skill-refinement trigger — R1 Phase 2 of the self-improvement loop
   (docs/design/self-improve-design.md).

   A `:agent.tool-use/post` observer that watches dynamic `skill$<name>`
   invocations. When one FAILS (the cheap, deterministic divergence signal: the
   skill's documented procedure didn't carry the run to success), it asks
   whether the SKILL.md *document* is at fault — a missing step, a wrong
   assumption — versus a transient/environmental/user error. When the document
   is at fault it stages a `:refinement` proposal (a revised SKILL.md) under
   `.brainyard/skills/proposals/` for review; `skill-proposal$accept` then runs
   `skills$write :op :update`.

   Same shape as `skill-distill` (Phase 1): config-gated, fire-and-forget in a
   `future`, a cheap pre-filter before any LLM call, runtime-only install via a
   `compare-and-set!` atom. Lower volume — only fires on failed skill runs."
  (:require [ai.brainyard.agent.common.skill-distill.proposals :as proposals]
            [ai.brainyard.agent.common.skill-distill.signatures :as sig]
            [ai.brainyard.agent.core.config :as config]
            [ai.brainyard.agent.core.hooks :as hooks]
            [ai.brainyard.agent.core.protocol :as proto]
            [ai.brainyard.agent.core.tool :as tool]
            [ai.brainyard.clj-llm.interface :as clj-llm]
            [ai.brainyard.mulog.interface :as mulog]
            [clojure.string :as str]))

;; ============================================================================
;; Divergence pre-filter (deterministic, free)
;; ============================================================================

(defn skill-invocation?
  "True when `tool-name` is a dynamic skill tool (`skill$<name>`)."
  [tool-name]
  (boolean (and tool-name (str/starts-with? (name tool-name) "skill$"))))

(defn skill-name-of
  "Resolve the skill name for a `skill$<name>` invocation — from the result's
   `:skill` (the dynamic-skill fn stamps it) or by stripping the tool prefix."
  [tool-name result]
  (or (when (map? result) (:skill result))
      (when tool-name (subs (name tool-name) (count "skill$")))))

(defn result-error?
  "True when a tool result map signals failure (the shapes the tool layer and
   the dynamic-skill fn emit)."
  [result]
  (and (map? result)
       (boolean (or (:error result) (:error-message result)))))

(defn divergence?
  "Cheap deterministic pre-filter: a failed dynamic-skill invocation. This is
   the clearest 'outcome diverged from documented steps' signal and gates the
   LLM judge so non-skill / successful calls cost nothing."
  [tool-name result]
  (and (skill-invocation? tool-name) (result-error? result)))

(defn- error-text [result]
  (str (or (:error-message result) (:error result) "")))

;; ============================================================================
;; Refinement scorer (LLM)
;; ============================================================================

(defn- resolve-usage-tracker [agent]
  (try (get-in @(:!session agent) [:config :usage-tracker])
       (catch Exception _ nil)))

(defn current-skill-md
  "Best-effort fetch of a skill's current SKILL.md via skills$read; nil on
   error / absence."
  [skill-name]
  (try
    (let [res (tool/invoke-tool :skills$read :skill-name skill-name)]
      (when-not (:error res) (:content res)))
    (catch Exception _ nil)))

(defn score-refinement
  "Run SkillRefinement over a failed skill invocation. Returns the signature's
   `:outputs` ({:should-revise :revised-md :rationale}) or nil on failure."
  [agent skill-name current-md args evidence]
  (try
    (:outputs
     (clj-llm/chain-of-thought
      sig/SkillRefinement
      {:skill-name       (str skill-name)
       :current-skill-md (str current-md)
       :invocation-args  (pr-str args)
       :failure-evidence (str evidence)}
      :lm-config     (config/resolve-sub-lm agent)
      :usage-tracker (resolve-usage-tracker agent)))
    (catch Exception e
      (mulog/warn ::score-refinement-failed :skill skill-name :exception e)
      nil)))

;; ============================================================================
;; Staging decision (pure-ish — no LLM; unit-testable)
;; ============================================================================

(defn stage-refinement!
  "Given a `scored` SkillRefinement result for `skill-name`, stage a
   `:refinement` proposal under `project-dir` when the document is at fault.
   Returns: :staged | :no-revision | :empty-revised-md | :no-score."
  [project-dir skill-name scored evidence session]
  (let [{:keys [should-revise revised-md rationale]} scored]
    (cond
      (nil? scored)                       :no-score
      (not should-revise)                 :no-revision
      (str/blank? revised-md)             :empty-revised-md
      :else
      (do (proposals/write-proposal!
           project-dir
           {:name      skill-name
            :skill-md  revised-md
            :rationale rationale
            :evidence  (str evidence)
            :session   (str session)
            :kind      :refinement})
          :staged))))

;; ============================================================================
;; Handler
;; ============================================================================

(defn- refine-eligible?
  "True when `:enable-skill-refinement` resolves true for the agent."
  [agent]
  (when agent
    (try (boolean (config/get-config agent :enable-skill-refinement))
         (catch Exception _ false))))

(defn refine-handler
  "`:agent.tool-use/post` handler. Fire-and-forget: when a dynamic skill
   invocation failed and the SKILL.md looks at fault, stage a refinement
   proposal. Never blocks the caller and never propagates exceptions."
  [{:keys [agent tool-name args result]}]
  (when (and (refine-eligible? agent)
             (divergence? tool-name result))
    (let [skill-name  (skill-name-of tool-name result)
          project-dir (config/project-dir agent)
          sid         (proto/session-id agent)
          evidence    (error-text result)]
      (future
        (try
          (if-let [md (current-skill-md skill-name)]
            (let [scored  (score-refinement agent skill-name md args evidence)
                  outcome (stage-refinement! project-dir skill-name scored evidence sid)]
              (mulog/log ::refine-outcome :skill skill-name :outcome outcome
                         :should-revise (:should-revise scored)))
            (mulog/log ::refine-skip-no-content :skill skill-name))
          (catch Exception e
            (mulog/warn ::refine-handler-failed :skill skill-name :exception e)
            nil)))))
  nil)

;; ============================================================================
;; Runtime install (idempotent, never at build time)
;; ============================================================================

(defonce ^:private !installed (atom false))

(defn ensure-global-hooks!
  "Install the `:agent.tool-use/post` refinement observer once per process at
   RUNTIME (guarded by a runtime atom so native-image bakes `false` and the
   first real turn installs). Safe to call every turn. Tagged
   `:source :skill-refine`."
  []
  (when (compare-and-set! !installed false true)
    (hooks/register-hook! :agent.tool-use/post ::skill-refine refine-handler
                          :source :skill-refine :priority 30)
    (mulog/info ::global-hooks-installed))
  nil)
