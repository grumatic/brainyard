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
     3. LLM SCORER — one call, in one of two modes (`:skill-distill-mode`):
        `:at-cadence` (default) accumulates qualifying turns and judges the
        whole window with `SkillDistillationBatch` every
        `:skill-distill-every-n-turns` turns, plus a flush when the session
        closes — one call per window instead of per turn, and a procedure
        spanning several turns is visible as one procedure. `:per-turn` scores
        each qualifying turn on its own with `SkillDistillation`.
        Past `:skill-distill-threshold` either returns a drafted SKILL.md.
     4. STAGE — `proposals/write-proposal!` stages the draft under
        `.brainyard/skills/proposals/`. It NEVER writes a live skill; a human
        promotes it via `skill-proposal$accept`.

   Steps 1–2 run inline on the turn thread (both are free); step 3 onward runs
   as a background `:fn` task via `skill-distill.background` — bounded by the
   task pool, single-flight per session, visible in `/task`, and given a grace
   period at exit. The user's next turn never waits on scoring, and a scorer
   failure never tanks the parent ask.

   Hooks are installed at RUNTIME via `ensure-global-hooks!` (a
   `compare-and-set!` atom, called from coact-init) so native-image bakes
   `false` and the first real turn installs — never a build-time registration."
  (:require [ai.brainyard.agent.common.skill-distill.background :as bg]
            [ai.brainyard.agent.common.skill-distill.proposals :as proposals]
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
  "A turn must take at least this many action steps — tool calls, or distinct
   top-level statements inside a code block — to count as a multi-step
   procedure worth scoring. Pure Q&A and single edits fall below it and are
   skipped without an LLM call."
  2)

(defn- action-iteration?
  "True when a projected trajectory iteration actually DID something — invoked
   a tool or ran code — as opposed to pure reasoning."
  [{:keys [channel tools code]}]
  (boolean (or (= "tool" channel) (seq tools) (seq code))))

(def ^:private comment-line-re
  "Leading comment marker across the languages a code block can carry (shell /
   Clojure / C-family / SQL)."
  #"^\s*(?:#|;|//|--)")

(def ^:private closing-line-re
  "A line that only CLOSES or continues a block rather than starting a new
   statement: a run of closing delimiters, or a shell block keyword."
  #"^[)\}\]\s;,]*$|^(?:fi|done|esac|end|else|elif\b.*|;;)\s*$")

(defn- statement-line?
  "True when a code line starts a new top-level statement. Statements begin at
   column 0 — shell commands, Clojure top-level forms and Python/JS top-level
   statements all share that convention, while continuations and block bodies
   are indented. Comments and bare block-closers start nothing."
  [line]
  (boolean (and (re-find #"^\S" line)
                (not (re-find comment-line-re line))
                (not (re-find closing-line-re line)))))

(defn code-steps
  "Number of distinct top-level statements in ONE code block. A non-blank block
   always counts as at least one step, so an unparseable or single-expression
   block is never counted as zero."
  [code]
  (let [s (str code)]
    (if (str/blank? s)
      0
      (max 1 (count (filter statement-line? (str/split-lines s)))))))

(defn iteration-steps
  "Action steps taken in ONE trajectory iteration: every tool call is a step,
   and a code block contributes one step per top-level statement.

   Counting INSIDE the block is the point. A capable model batches a multi-step
   shell procedure into a single code block — exactly the turn most worth
   distilling — and an iteration-level count scores that as 1, silently dropping
   it. Count what the turn DID, not how many iterations it took to do it.

   Pure-reasoning iterations score 0; an action iteration always scores ≥ 1."
  [{:keys [tools code] :as iteration}]
  (if (action-iteration? iteration)
    (max 1 (+ (count tools) (reduce + 0 (map code-steps code))))
    0))

(defn turn-steps
  "Total action steps across a turn record's iterations."
  [record]
  (reduce + 0 (map iteration-steps (:iterations record))))

(defn worth-scoring?
  "Deterministic pre-filter over a trajectory turn record. True only for a
   successful, non-empty, multi-step turn — the shape a reusable procedure
   takes. Keeps the LLM scorer off trivial turns."
  [record]
  (boolean
   (and (map? record)
        (:success record)
        (not (str/blank? (str (:answer record))))
        (>= (turn-steps record) min-action-steps))))

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

(def ^:const max-batch-chars
  "Total budget for a rendered batch window. Split evenly across the window's
   turns (with a floor), so one sprawling turn can't crowd out the rest."
  24000)

(def ^:const min-batch-turn-chars 1500)

(defn batch->text
  "Render candidate turn records into one window block for
   `SkillDistillationBatch`: each turn headed by its number and question, then
   its compacted trace. Per-turn budget is the total split across the window,
   so the prompt stays bounded however many turns accumulated."
  [records]
  (let [n   (max 1 (count records))
        per (max min-batch-turn-chars (long (/ max-batch-chars n)))]
    (->> records
         (map (fn [r]
                (let [q    (str (:question r))
                      head (str "=== turn " (:turn r) " — "
                                (if (> (count q) 200) (str (subs q 0 200) "…") q)
                                " ===")
                      body (trajectory->text r)]
                  (str head "\n"
                       (if (> (count body) per)
                         (str (subs body 0 per) "\n…[turn truncated]")
                         body)))))
         (str/join "\n\n"))))

(defn score-batch
  "Run SkillDistillationBatch over several turn records in ONE sub-LM call.
   Returns the signature's `:outputs` map (same shape as `score-turn`), or nil
   on failure."
  [agent records]
  (try
    (:outputs
     (clj-llm/chain-of-thought
      sig/SkillDistillationBatch
      {:turn-window     (batch->text records)
       :turn-count      (str (count records))
       :existing-skills (existing-skills-text agent)
       :project-context ""}
      :lm-config     (config/resolve-sub-lm agent)
      :usage-tracker (resolve-usage-tracker agent)))
    (catch Exception e
      (mulog/warn ::score-batch-failed :exception e)
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
  "Given a `scored` SkillDistillation result, stage a proposal under
   `project-dir` when it qualifies. Returns a keyword outcome:
   :staged | :not-reusable | :below-threshold | :invalid-name | :empty-skill-md
   | :no-score. Side effect (the proposal write) happens only on :staged.

   `record` is a PROVENANCE map, read for `:turn`, `:question` and (batch mode)
   `:turns`. A trajectory turn record satisfies it directly; the batch path
   passes a synthesized descriptor for the window."
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
            :turns           (:turns record)
            :source-question (:question record)
            :kind            :distillation})
          :staged))))

;; ============================================================================
;; Per-session batching state (:at-cadence mode)
;; ============================================================================
;;
;; Only turn NUMBERS are held, never records: `trajectory.edn` already has the
;; turns, so a window is re-read at batch time. Keeps the atom tiny however
;; long a session runs, and a crash costs a window rather than memory.

(defonce ^:private !candidates (atom {}))     ;; {session-id #{turn-number}}
(defonce ^:private !turn-counters (atom {}))  ;; {session-id turns-since-start}

(defn candidate-turns
  "Turn numbers accumulated for `sid`, ascending."
  [sid]
  (vec (sort (get @!candidates (str sid) #{}))))

(defn- record-candidate! [sid turn]
  (when turn
    (swap! !candidates update (str sid) (fnil conj #{}) turn)))

(defn- clear-session-state! [sid]
  (swap! !candidates dissoc (str sid))
  (swap! !turn-counters dissoc (str sid)))

(defn window-records
  "Re-read `sid`'s trajectory and return the records for `turns`, ascending.
   Missing turns are simply absent — a pruned or rewritten trajectory degrades
   to a smaller window rather than failing the batch."
  [sid turns]
  (let [wanted (set turns)]
    (try
      (->> (traj/read-trajectories sid)
           (filter #(contains? wanted (:turn %)))
           (sort-by :turn)
           vec)
      (catch Exception e
        (mulog/warn ::window-read-failed :session (str sid) :exception e)
        []))))

(defn- batch-provenance
  "Synthesize the provenance map `stage-proposal!` reads for a window."
  [records]
  {:turn     (:turn (last records))
   :turns    (mapv :turn records)
   :question (str "batch of " (count records) " turns: "
                  (str/join " | " (map #(str "[" (:turn %) "] " (:question %)) records)))})

(defn run-batch!
  "Score `sid`'s accumulated candidate window in ONE sub-LM call and stage the
   best proposal. Clears the window first so a failure can't make the same
   turns re-score forever. Returns the staging outcome, or :no-candidates /
   :no-window when there is nothing to judge."
  [agent sid project-dir threshold turns]
  (let [records (window-records sid turns)]
    (if (empty? records)
      (do (mulog/log ::batch-skip-no-window :session (str sid) :turns turns)
          :no-window)
      (let [scored  (score-batch agent records)
            prov    (batch-provenance records)
            outcome (stage-proposal! project-dir prov scored threshold sid)]
        (mulog/log ::distill-outcome :session (str sid) :outcome outcome
                   :mode :at-cadence :window (count records)
                   :score (:score scored) :name (:proposed-name scored))
        outcome))))

(defn- submit-batch!
  "Hand `turns` to a background scoring task. The window is taken from the
   accumulator BEFORE submission so turns are never scored twice."
  [agent sid project-dir threshold turns reason]
  (swap! !candidates dissoc (str sid))
  (bg/run-off-turn!
   :kind  :distill
   :key   sid
   :label (str "skill-distill batch " sid " (" reason ", " (count turns) " turns)")
   :thunk (fn []
            (try
              (run-batch! agent sid project-dir threshold turns)
              (catch Exception e
                (mulog/warn ::distill-handler-failed :session (str sid) :exception e)
                nil)))))

;; ============================================================================
;; Handlers
;; ============================================================================

(defn distill-handler
  "`:agent.ask/post` handler. Runs the free pre-filter inline, then either
   accumulates the turn for the next batch (`:at-cadence`, default) or scores
   it on its own (`:per-turn`). Never blocks the caller and never propagates
   exceptions.

   The pre-filter runs on the turn thread deliberately: it is deterministic,
   LLM-free and sub-millisecond (one trajectory read — measured 0.55 ms on a
   5-turn session), and keeping it here means a turn that will never be judged
   costs nothing beyond that read. The trajectory record for the just-finished
   turn is already on disk — `coact-store-results-action` writes it inside the
   behavior tree, before `ask` returns and this hook fires.

   In `:at-cadence` mode the counter advances on EVERY turn, not just
   qualifying ones, so batch timing is predictable; a boundary reached with no
   accumulated candidates is skipped without an LLM call."
  [{:keys [agent]}]
  (when (distill-eligible? agent)
    (let [sid         (proto/session-id agent)
          project-dir (config/project-dir agent)
          threshold   (or (config/get-config agent :skill-distill-threshold) 0.7)
          per-turn?   (= :per-turn (config/get-config agent :skill-distill-mode))
          record      (try (traj/latest-trajectory sid)
                           (catch Exception e
                             (mulog/warn ::trajectory-read-failed
                                         :session (str sid) :exception e)
                             nil))
          worth?      (worth-scoring? record)]
      (when-not worth?
        (mulog/log ::pre-filter-skip :session (str sid)))
      (if per-turn?
        (when worth?
          (bg/run-off-turn!
           :kind  :distill
           :key   sid
           :label (str "skill-distill " sid)
           :thunk (fn []
                    (try
                      (let [scored  (score-turn agent record)
                            outcome (stage-proposal! project-dir record scored threshold sid)]
                        (mulog/log ::distill-outcome :session (str sid) :outcome outcome
                                   :mode :per-turn
                                   :score (:score scored) :name (:proposed-name scored))
                        outcome)
                      (catch Exception e
                        (mulog/warn ::distill-handler-failed :session (str sid) :exception e)
                        nil)))))
        ;; :at-cadence — accumulate, and fire a batch on every Nth turn.
        (let [n     (long (or (config/get-config agent :skill-distill-every-n-turns) 12))
              tally (get (swap! !turn-counters update (str sid) (fnil inc 0)) (str sid))]
          (when worth? (record-candidate! sid (:turn record)))
          (when (and (pos? n) (zero? (mod tally n)))
            (let [turns (candidate-turns sid)]
              (if (empty? turns)
                (mulog/log ::batch-skip-no-candidates :session (str sid) :turn tally)
                (submit-batch! agent sid project-dir threshold turns "cadence"))))))))
  nil)

(defn session-end-flush-handler
  "`:agent.instance/closed` handler. A session that ends between cadence
   boundaries would otherwise drop its accumulated tail — and a session shorter
   than `:skill-distill-every-n-turns` would never distill anything at all — so
   flush whatever is pending when a root agent closes.

   Per-session state is cleared regardless, so nothing leaks across a resume.
   Returns nil and never throws."
  [{:keys [agent]}]
  (when (distill-eligible? agent)
    (let [sid   (proto/session-id agent)
          turns (candidate-turns sid)]
      (try
        (when (and (not= :per-turn (config/get-config agent :skill-distill-mode))
                   (seq turns))
          (submit-batch! agent sid
                         (config/project-dir agent)
                         (or (config/get-config agent :skill-distill-threshold) 0.7)
                         turns "session-end"))
        (catch Exception e
          (mulog/warn ::session-end-flush-failed :session (str sid) :exception e))
        (finally
          (clear-session-state! sid)))))
  nil)

;; ============================================================================
;; Runtime install (idempotent, never at build time)
;; ============================================================================

(defonce ^:private !installed (atom false))

(defn ensure-global-hooks!
  "Install the distillation observers once per process at RUNTIME (guarded by a
   runtime atom so native-image bakes `false` and the first real turn
   installs). Safe to call every turn. Tagged `:source :skill-distill` for bulk
   teardown.

   Two hooks: the per-turn `:agent.ask/post` observer, and the
   `:agent.instance/closed` flush that batches a session's accumulated tail
   (a no-op in `:per-turn` mode)."
  []
  (when (compare-and-set! !installed false true)
    (hooks/register-hook! :agent.instance/closed ::skill-distill-flush
                          session-end-flush-handler
                          :source :skill-distill :priority 40)
    (hooks/register-hook! :agent.ask/post ::skill-distill distill-handler
                          :source :skill-distill :priority 40)
    (mulog/info ::global-hooks-installed))
  nil)
