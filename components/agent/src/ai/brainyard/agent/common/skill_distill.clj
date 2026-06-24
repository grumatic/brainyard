;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.common.skill-distill
  "Skill-distillation trigger — the headline of the self-improvement loop
   (R1 — docs/design/self-improve-design.md).

   An `:agent.ask/post` observer that, after a root agent's turn finishes,
   asks: did this turn embody a novel reusable procedure worth a skill? The
   path is deliberately cheap-by-default:

     1. CONFIG GATE — only when `:enable-skill-distillation` resolves true for
        the agent (default false; opt-in per agent type), and only for root
        agents (sub-agents share the session — the root handles it).
     2. PRE-FILTER (free) — `worth-scoring?` drops trivial turns (failed,
        single-step, pure Q&A) before any LLM call.
     3. LLM SCORER — `SkillDistillation` over the turn's trajectory; past
        `:skill-distill-threshold` it returns a drafted SKILL.md.
     4. STAGE — `proposals/write-proposal!` stages the draft under
        `.brainyard/skills/proposals/`. It NEVER writes a live skill; a human
        promotes it via `skill-proposal$accept`.

   All work runs fire-and-forget in a `future` (cloning the memory-agent
   essence-capture handler) so the user's next turn never waits on scoring, and
   a scorer failure never tanks the parent ask.

   Hooks are installed at RUNTIME via `ensure-global-hooks!` (a
   `compare-and-set!` atom, called from coact-init) so native-image bakes
   `false` and the first real turn installs — never a build-time registration."
  (:require [ai.brainyard.agent.common.skill-distill.proposals :as proposals]
            [ai.brainyard.agent.common.skill-distill.signatures :as sig]
            [ai.brainyard.agent.common.trajectory :as traj]
            [ai.brainyard.agent.core.config :as config]
            [ai.brainyard.agent.core.hooks :as hooks]
            [ai.brainyard.agent.core.protocol :as proto]
            [ai.brainyard.agent.core.tool :as tool]
            [ai.brainyard.clj-llm.interface :as clj-llm]
            [ai.brainyard.mulog.interface :as mulog]
            [clojure.string :as str]))

;; ============================================================================
;; Pre-filter — deterministic, free, runs before any LLM call
;; ============================================================================

(def ^:const min-action-steps
  "A turn must take at least this many action steps (tool/code iterations) to
   count as a multi-step procedure worth scoring. Pure Q&A and single edits
   fall below it and are skipped without an LLM call."
  2)

(defn- action-iteration?
  "True when a projected trajectory iteration actually DID something — invoked
   a tool or ran code — as opposed to pure reasoning."
  [{:keys [channel tools code]}]
  (boolean (or (= "tool" channel) (seq tools) (seq code))))

(defn worth-scoring?
  "Deterministic pre-filter over a trajectory turn record. True only for a
   successful, non-empty, multi-step turn — the shape a reusable procedure
   takes. Keeps the LLM scorer off trivial turns."
  [record]
  (boolean
   (and (map? record)
        (:success record)
        (not (str/blank? (str (:answer record))))
        (>= (count (filter action-iteration? (:iterations record)))
            min-action-steps))))

;; ============================================================================
;; Trajectory → scorer inputs
;; ============================================================================

(def ^:const max-trajectory-chars 8000)

(defn- iteration->text
  [{:keys [n channel thought tools code result output error]}]
  (let [hdr (str "— iteration " n " [" (or channel "none") "]")
        thought' (when-not (str/blank? thought) (str "  thought: " thought))
        tools'   (when (seq tools)
                   (str/join "\n" (map (fn [{:keys [name args result]}]
                                         (str "  tool " name
                                              (when args (str " args=" (pr-str args)))
                                              (when-not (str/blank? result)
                                                (str " -> " result))))
                                       tools)))
        code'    (when (seq code)   (str "  code: "   (str/join " ; " code)))
        result'  (when (seq result) (str "  result: " (str/join " ; " result)))
        output'  (when (seq output) (str "  output: " (str/join " ; " output)))
        error'   (when (seq error)  (str "  error: "  (str/join " ; " error)))]
    (->> [hdr thought' tools' code' result' output' error']
         (remove nil?)
         (str/join "\n"))))

(defn trajectory->text
  "Render a trajectory turn record's iterations into a compact, bounded text
   block for the SkillDistillation signature."
  [record]
  (let [s (->> (:iterations record) (map iteration->text) (str/join "\n"))]
    (if (> (count s) max-trajectory-chars)
      (str (subs s 0 max-trajectory-chars) "\n…[trajectory truncated]")
      s)))

(defn- existing-skills-text
  "Best-effort list of existing skills (name — description, one per line) so
   the scorer avoids duplicating one. Blank on any failure — it is a dedup
   hint, not a correctness requirement."
  [_agent]
  (try
    (let [res    (tool/invoke-tool :skills$list)
          skills (or (:skills res) (:brainyard res) (when (sequential? res) res) [])]
      (->> skills
           (keep (fn [s]
                   (when (map? s)
                     (let [nm (or (:name s) (:skill-name s) (:id s))]
                       (when nm
                         (str nm (when-let [d (:description s)] (str " — " d))))))))
           (str/join "\n")))
    (catch Exception _ "")))

;; ============================================================================
;; Scorer
;; ============================================================================

(defn- resolve-usage-tracker [agent]
  (try (get-in @(:!session agent) [:config :usage-tracker])
       (catch Exception _ nil)))

(defn score-turn
  "Run SkillDistillation over a turn record using the agent's sub-LM. Returns
   the signature's `:outputs` map ({:reusable :score :proposed-name :rationale
   :skill-md}), or nil on failure."
  [agent record]
  (try
    (:outputs
     (clj-llm/chain-of-thought
      sig/SkillDistillation
      {:turn-question   (str (:question record))
       :turn-trajectory (trajectory->text record)
       :existing-skills (existing-skills-text agent)
       :project-context ""}
      :lm-config     (config/resolve-sub-lm agent)
      :usage-tracker (resolve-usage-tracker agent)))
    (catch Exception e
      (mulog/warn ::score-turn-failed :exception e)
      nil)))

;; ============================================================================
;; Eligibility
;; ============================================================================

(defn- root-agent?
  "True when `agent` has no parent — only root agents drive distillation;
   sub-agents share the session turn and would double-fire."
  [agent]
  (try (nil? (get-in @(:!state agent) [:runtime :parent-agent]))
       (catch Exception _ false)))

(defn distill-eligible?
  "True when the just-finished agent should attempt skill distillation:
     1. Is a root agent (sub-agents share the session).
     2. `:enable-skill-distillation` resolves true (per-agent override →
        global config → schema default false)."
  [agent]
  (when agent
    (try
      (and (root-agent? agent)
           (boolean (config/get-config agent :enable-skill-distillation)))
      (catch Exception _ false))))

;; ============================================================================
;; Staging decision (pure-ish — no LLM, no agent; unit-testable)
;; ============================================================================

(defn stage-proposal!
  "Given a `scored` SkillDistillation result for `record`, stage a proposal
   under `project-dir` when it qualifies. Returns a keyword outcome:
   :staged | :not-reusable | :below-threshold | :invalid-name | :empty-skill-md
   | :no-score. Side effect (the proposal write) happens only on :staged."
  [project-dir record scored threshold session]
  (let [{:keys [reusable score proposed-name rationale skill-md]} scored]
    (cond
      (nil? scored)                              :no-score
      (not reusable)                             :not-reusable
      (< (or score 0) (or threshold 0))          :below-threshold
      (not (proposals/valid-name? proposed-name)) :invalid-name
      (str/blank? skill-md)                      :empty-skill-md
      :else
      (do (proposals/write-proposal!
           project-dir
           {:name            proposed-name
            :skill-md        skill-md
            :score           score
            :rationale       rationale
            :session         (str session)
            :turn            (:turn record)
            :source-question (:question record)
            :kind            :distillation})
          :staged))))

;; ============================================================================
;; Handler
;; ============================================================================

(defn distill-handler
  "`:agent.ask/post` handler. Fire-and-forget: scores the just-finished turn
   and stages a skill proposal when it clears the threshold. Never blocks the
   caller and never propagates exceptions."
  [{:keys [agent]}]
  (when (distill-eligible? agent)
    (let [sid         (proto/session-id agent)
          project-dir (config/project-dir agent)
          threshold   (or (config/get-config agent :skill-distill-threshold) 0.7)]
      (future
        (try
          (let [record (traj/latest-trajectory sid)]
            (if-not (worth-scoring? record)
              (mulog/log ::pre-filter-skip :session (str sid))
              (let [scored  (score-turn agent record)
                    outcome (stage-proposal! project-dir record scored threshold sid)]
                (mulog/log ::distill-outcome :session (str sid) :outcome outcome
                           :score (:score scored) :name (:proposed-name scored)))))
          (catch Exception e
            (mulog/warn ::distill-handler-failed :session (str sid) :exception e)
            nil)))))
  nil)

;; ============================================================================
;; Runtime install (idempotent, never at build time)
;; ============================================================================

(defonce ^:private !installed (atom false))

(defn ensure-global-hooks!
  "Install the `:agent.ask/post` distillation observer once per process at
   RUNTIME (guarded by a runtime atom so native-image bakes `false` and the
   first real turn installs). Safe to call every turn. Tagged
   `:source :skill-distill` for bulk teardown."
  []
  (when (compare-and-set! !installed false true)
    (hooks/register-hook! :agent.ask/post ::skill-distill distill-handler
                          :source :skill-distill :priority 40)
    (mulog/info ::global-hooks-installed))
  nil)
