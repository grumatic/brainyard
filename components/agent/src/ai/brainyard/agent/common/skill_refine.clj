;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.common.skill-refine
  "Skill-refinement trigger — R1 Phase 2 of the self-improvement loop
   (docs/design/self-improve-design.md).

   Watches dynamic `skill$<name>` usage for evidence that a SKILL.md *document*
   is at fault — a missing step, a wrong assumption — versus a
   transient/environmental/user error. When the document is at fault it stages a
   `:refinement` proposal (a revised SKILL.md) under
   `.brainyard/skills/proposals/` for review; `skill-proposal$accept` then runs
   `skills$write :op :update`.

   TWO TRIGGERS, because the two dispatch paths fail differently:

   - DELEGATED (`dispatch: agent`) — the skill runs in a sub-agent, so a wrong
     procedure surfaces as a FAILED INVOCATION. `divergence?` +
     `refine-handler` on `:agent.tool-use/post`.
   - LOADED (the default) — `skill$<name>` hands the SKILL.md to the calling
     agent and succeeds; a wrong procedure cannot fail at the tool boundary at
     all. It surfaces one level up, as a TURN that never reached its goal. So
     `track-load-handler` records which skills a turn loaded and
     `turn-refine-handler` judges them on `:agent.ask/post` when that turn
     failed. Root-only, and the loaded set is cleared each turn so a skill is
     judged against the turn that loaded it.

   Both feed the SAME judge (`score-refinement` / `stage-refinement!`).

   Same shape as `skill-distill` (Phase 1): config-gated, a cheap deterministic
   pre-filter before any LLM call, scoring handed to a background `:fn` task via
   `common.background` (single-flight per skill name, so a skill failing
   repeatedly queues one judge, not one per failure), runtime-only install via
   a `compare-and-set!` atom."
  (:require [ai.brainyard.agent.common.background :as bg]
            [ai.brainyard.agent.common.skill-distill :as distill]
            [ai.brainyard.agent.common.skill-distill.proposals :as proposals]
            [ai.brainyard.agent.common.skill-distill.signatures :as sig]
            [ai.brainyard.agent.common.trajectory :as traj]
            [ai.brainyard.agent.core.config :as config]
            [ai.brainyard.agent.core.feature :as feature]
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
   LLM judge so non-skill / successful calls cost nothing.

   SCOPE: this covers DELEGATED skills (`dispatch: agent`), which execute in a
   sub-agent and can fail mid-procedure. It does not cover the default LOAD
   path — there `skill$<name>` hands over the SKILL.md and succeeds, so a
   failure would only mean the file could not be read. Loaded skills are covered
   by the turn-level trigger (`turn-refine-handler`) instead."
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
;; Turn-level trigger — skills LOADED into the calling agent
;;
;; A loaded skill cannot fail at the tool boundary the way a delegated one can:
;; `skill$<name>` hands over the SKILL.md and succeeds. Wrong steps surface
;; later, as a turn that never reached its goal. So the divergence signal for
;; loaded skills is a FAILED TURN that loaded one — evaluated by the same judge.
;; ============================================================================

(defn skill-loaded?
  "True when a finished `skill$<name>` call took the LOAD path (the default),
   i.e. it handed the procedure to the caller rather than running it."
  [tool-name result]
  (and (skill-invocation? tool-name)
       (map? result)
       (true? (:loaded result))))

(defonce ^:private !loaded-this-turn (atom {}))   ;; {session-id #{skill-name}}

(defn loaded-skills
  "Skill names loaded during the current turn of `sid`."
  [sid]
  (get @!loaded-this-turn (str sid) #{}))

(defn- record-loaded! [sid skill-name]
  (when skill-name
    (swap! !loaded-this-turn update (str sid) (fnil conj #{}) skill-name))
  nil)

(defn- clear-loaded! [sid]
  (swap! !loaded-this-turn dissoc (str sid))
  nil)

(defn turn-failed?
  "True when a trajectory turn record exists and reports failure. A MISSING
   record is not a failure — absence of evidence is not evidence of divergence,
   and firing the judge on it would burn a sub-LM call per unreadable turn."
  [record]
  (and (map? record) (not (:success record))))

(def ^:const max-turn-evidence-chars
  "Cap on the rendered failed-turn evidence handed to the judge."
  6000)

(defn turn-evidence
  "Failure evidence for a turn that loaded a skill: how the turn ended, plus a
   bounded trace. Reuses `skill-distill/trajectory->text` — already the compact,
   truncating renderer for exactly this — rather than growing a second one."
  [record]
  (let [s (str "The turn did NOT succeed"
               (when-let [t (:terminated-by record)]
                 (str " (terminated-by " t ")"))
               ".\nQuestion: " (:question record)
               "\nFinal answer: " (:answer record)
               "\n\nTrace:\n" (distill/trajectory->text record))]
    (if (> (count s) max-turn-evidence-chars)
      (str (subs s 0 max-turn-evidence-chars) "\n…[evidence truncated]")
      s)))

;; ============================================================================
;; Handler
;; ============================================================================

(defn- refine-eligible?
  "True when `:enable-skill-refinement` resolves true for the agent."
  [agent]
  (when agent
    (try (feature/on? agent :self-improve/refinement)
         (catch Exception _ false))))

(defn- root-agent?
  "True when `agent` has no parent — a STRICT parent test, deliberately.

   The TURN-level trigger must be root-only: sub-agents share the session id, so
   a sub-agent finishing its own ask would consume and clear the root turn's
   loaded set before the root turn ever ended. This is a local check rather than
   `:root-only` on the feature, because the TOOL-level trigger below legitimately
   fires for sub-agents — a delegated skill that errors is attributable whoever
   called it.

   NOT migrated to `runtime/dispatched-subagent-state?` like the other root-only
   sites. Those ask 'whose user turn is this?', and a session-sharing subagent
   owns its own. This one guards state keyed by SESSION-ID, and a sharing
   sibling shares the session id exactly as a dispatched worker does — so
   admitting it here would let it consume and clear the root's loaded set early,
   which is precisely the failure this predicate exists to prevent. The
   distinction is the keying, not the ownership of the turn."
  [agent]
  (try (nil? (get-in @(:!state agent) [:runtime :parent-agent]))
       (catch Exception _ false)))

(defn track-load-handler
  "`:agent.tool-use/post` handler — remember which skills this turn loaded, so an
   unsuccessful turn can be attributed to them. Fire-and-forget; never throws."
  [{:keys [agent tool-name result]}]
  (try
    (when (and agent (refine-eligible? agent) (skill-loaded? tool-name result))
      (record-loaded! (proto/session-id agent) (skill-name-of tool-name result)))
    (catch Exception e
      (mulog/warn ::track-load-failed :tool tool-name :exception e)))
  nil)

(defn turn-refine-handler
  "`:agent.ask/post` handler. When a root turn that LOADED skills did not
   succeed, ask the judge whether each loaded SKILL.md is at fault — the
   loaded-skill counterpart to `refine-handler`'s failed-invocation signal.

   The loaded set is cleared every turn whatever the outcome, so a skill is
   judged against the turn that loaded it and never against a later unrelated
   failure. One background judge per skill (single-flight on `:kind :refine`,
   keyed by skill name, shared with the tool-level trigger)."
  [{:keys [agent]}]
  (when (and agent (refine-eligible? agent) (root-agent? agent))
    (let [sid    (proto/session-id agent)
          loaded (loaded-skills sid)]
      (try
        (when (seq loaded)
          (let [record (try (traj/latest-trajectory sid)
                            (catch Exception e
                              (mulog/warn ::turn-trajectory-read-failed
                                          :session (str sid) :exception e)
                              nil))]
            (if-not (turn-failed? record)
              (mulog/log ::turn-refine-skip :session (str sid)
                         :loaded (count loaded) :reason :turn-not-failed)
              (let [project-dir (config/project-dir agent)
                    evidence    (turn-evidence record)]
                (doseq [skill-name loaded]
                  (bg/run-off-turn!
                   :kind  :refine
                   :key   skill-name
                   :label (str "skill-refine " skill-name " (failed turn)")
                   :thunk (fn []
                            (try
                              (if-let [md (current-skill-md skill-name)]
                                (let [scored  (score-refinement
                                               agent skill-name md
                                               {:turn (:turn record)} evidence)
                                      outcome (stage-refinement!
                                               project-dir skill-name scored
                                               evidence sid)]
                                  (mulog/log ::refine-outcome :skill skill-name
                                             :outcome outcome :trigger :failed-turn
                                             :should-revise (:should-revise scored))
                                  outcome)
                                (do (mulog/log ::refine-skip-no-content :skill skill-name)
                                    :no-content))
                              (catch Exception e
                                (mulog/warn ::turn-refine-failed
                                            :skill skill-name :exception e)
                                nil)))))))))
        (catch Exception e
          (mulog/warn ::turn-refine-handler-failed :session (str sid) :exception e))
        (finally
          (clear-loaded! sid)))))
  nil)

(defn session-end-clear-handler
  "`:agent.instance/closed` handler — drop a session's loaded set so a session
   ending mid-turn cannot leak an entry. Never throws.

   ROOT-ONLY, for the same reason as `turn-refine-handler` and found the hard
   way: sub-agents share the root's session id, and every dispatched sub-agent
   is auto-closed when its call returns. Without this guard the FIRST sub-agent
   a turn dispatched would wipe the loaded set before the turn ended — so any
   turn that loaded a skill and then delegated anything (the common case) would
   silently lose the refinement signal."
  [{:keys [agent]}]
  (when (and agent (root-agent? agent))
    (try (clear-loaded! (proto/session-id agent))
         (catch Exception _ nil)))
  nil)

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
      (bg/run-off-turn!
       :kind  :refine
       :key   skill-name
       :label (str "skill-refine " skill-name)
       :thunk (fn []
                (try
                  (if-let [md (current-skill-md skill-name)]
                    (let [scored  (score-refinement agent skill-name md args evidence)
                          outcome (stage-refinement! project-dir skill-name scored evidence sid)]
                      (mulog/log ::refine-outcome :skill skill-name :outcome outcome
                                 :should-revise (:should-revise scored))
                      outcome)
                    (do (mulog/log ::refine-skip-no-content :skill skill-name)
                        :no-content))
                  (catch Exception e
                    (mulog/warn ::refine-handler-failed :skill skill-name :exception e)
                    nil))))))
  nil)

;; ============================================================================
;; Runtime install (idempotent, never at build time)
;; ============================================================================

(defonce ^:private !installed (atom false))

(defn ensure-global-hooks!
  "Install the refinement observers once per process at RUNTIME (guarded by a
   runtime atom so native-image bakes `false` and the first real turn installs).
   Safe to call every turn. Tagged `:source :skill-refine`.

   Four hooks, two triggers:
   - DELEGATED skills (`dispatch: agent`) — `refine-handler` on a failed
     `skill$<name>` invocation.
   - LOADED skills (the default) — `track-load-handler` records what a turn
     loaded, `turn-refine-handler` judges them when that turn fails, and
     `session-end-clear-handler` drops the set if a session ends mid-turn."
  []
  (when (compare-and-set! !installed false true)
    (hooks/register-hook! :agent.tool-use/post ::skill-refine refine-handler
                          :source :skill-refine :priority 30)
    (hooks/register-hook! :agent.tool-use/post ::skill-refine-track
                          track-load-handler
                          :source :skill-refine :priority 30)
    (hooks/register-hook! :agent.ask/post ::skill-refine-turn
                          turn-refine-handler
                          :source :skill-refine :priority 30)
    (hooks/register-hook! :agent.instance/closed ::skill-refine-clear
                          session-end-clear-handler
                          :source :skill-refine :priority 30)
    (mulog/info ::global-hooks-installed))
  nil)
