;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.clj-llm.core.optimize
  "Optimizers (DSPy teleprompters) — Phase 3 of
   docs/design/dspy-programming-model-proposal.md.

   Every optimizer returns the same shape, and NONE of them applies anything:

     {:params {predictor-id params …}   ; a proposal, valid per core.predictor
      :report {:optimizer … :cost … …}}

   Writing a proposal where `resolve-params` will find it is a separate,
   reviewed step owned by the caller. An optimizer's output is prompt text —
   including demos harvested from whatever the teacher read — and the whole
   design rests on a human looking at it before it reaches a system message.

   ## The bootstrap pool, and how it departs from the paper

   The paper's BootstrapFewShotWithRandomSearch re-runs the teacher inside
   every trial. Here the teacher runs ONCE over the trainset
   (`bootstrap-pool`), keeping the traces whose end-to-end result passes the
   metric; candidates are then subsets of that pool. That is the paper's own
   Optuna variant (App. E.3: bootstrap a pool, search selections over it), and
   it matters more here than there: a teacher is the `:deep` tier, every
   re-run is billed, and at temperature 0 a re-run mostly reproduces the same
   trace anyway. The cost is diversity — a pool cannot contain a trace the
   teacher would only have produced on a second sampling.

   A demo is taken from EVERY predictor call in a passing trace, not only the
   last one: the example's label says the pipeline's final answer was right,
   and that is the evidence each intermediate step was good enough — the
   paper's central label-efficiency trick."
  (:require [ai.brainyard.clj-llm.core.evaluate :as evaluate]
            [ai.brainyard.clj-llm.core.llm :as llm]
            [ai.brainyard.clj-llm.core.predictor :as predictor]
            [ai.brainyard.mulog.interface :as mulog]))

;; ============================================================================
;; Helpers
;; ============================================================================

(defn- shuffle-seeded
  "Deterministic shuffle — a trial's seed must reproduce its candidate."
  [coll seed]
  (let [al (java.util.ArrayList. ^java.util.Collection (vec coll))]
    (java.util.Collections/shuffle al (java.util.Random. (long seed)))
    (vec al)))

(defn- trace-cost [trace]
  (reduce + 0.0 (keep #(get-in % [:usage :cost :total-cost]) trace)))

(defn- demo-from-entry [entry ex-index]
  (cond-> {:inputs  (:inputs entry)
           :outputs (:outputs entry)
           :source  {:kind :bootstrap :example ex-index}}
    (:reasoning entry) (assoc :reasoning (:reasoning entry))))

(defn- labeled-demo [ex ex-index]
  {:inputs  (:inputs ex)
   :outputs (:labels ex)
   :source  {:kind :labeled :example ex-index}})

;; ============================================================================
;; LabeledFewShot
;; ============================================================================

(defn labeled-few-shot
  "k labelled examples as demos for ONE predictor — the baseline every
   bootstrapped candidate must beat. Labels are used as the demo's outputs, so
   they must be complete outputs for that predictor's signature; examples with
   no labels are skipped. No LM calls."
  [predictor-id trainset {:keys [k seed] :or {k 4 seed 0}}]
  (let [labeled (keep-indexed (fn [i ex] (when (seq (:labels ex)) [i ex])) trainset)
        picked  (take k (shuffle-seeded labeled seed))]
    {:params {predictor-id {:demos (mapv (fn [[i ex]] (labeled-demo ex i)) picked)}}
     :report {:optimizer :labeled-few-shot :k k :seed seed
              :available (count labeled) :demos (count picked) :cost 0.0}}))

;; ============================================================================
;; Bootstrap pool
;; ============================================================================

(defn bootstrap-pool
  "Run `teacher` (a program: inputs → result) over `trainset`, keeping demos
   from traces whose result scores ≥ `threshold` under `metric`.

   opts:
     :threshold        default 1.0 — only fully-passing traces teach
     :max-per-predictor stop once every predictor seen has this many (default 16)
     :budget-usd       stop starting new examples at this spend
     :max-calls        stop starting new examples after this many predictor calls
     :teacher-params   {pid params} bound while the teacher runs; `{pid {}}`
                       bootstraps from the zero-shot program even when a params
                       file exists (else the teacher sees current params)

   Returns {:pool {pid [demo …]}
            :report {:attempted :passed :errors :cost :calls :scores :stopped}}.
   `:scores` is every teacher score in trainset order — a pass rate alone hides
   whether the threshold was missed by 0.01 or by 0.6.
   Transient errors are not retried here (a skipped example only shrinks the
   pool); a :fatal error stops the run."
  [teacher trainset metric {:keys [threshold max-per-predictor budget-usd max-calls teacher-params]
                            :or   {threshold 1.0 max-per-predictor 16}}]
  (let [pool (atom {}) spent (atom 0.0) calls (atom 0)
        attempted (atom 0) passed (atom 0) errors (atom 0) scores (atom [])
        report (fn [stopped & {:as extra}]
                 (merge {:attempted @attempted :passed @passed :errors @errors
                         :cost @spent :calls @calls :scores @scores :stopped stopped}
                        extra))]
    (loop [[[i ex] & more] (map-indexed vector trainset)]
      (let [full? (and (seq @pool) (every? #(>= (count %) max-per-predictor) (vals @pool)))
            over? (or (and budget-usd (>= @spent budget-usd))
                      (and max-calls (>= @calls max-calls)))]
        (if (or (nil? ex) full? over?)
          {:pool @pool :report (report (cond over? :budget full? :full :else nil))}
          (let [trace (atom [])
                outcome (try
                          {:result (binding [predictor/*trace* trace]
                                     (predictor/with-params (or teacher-params {})
                                       (teacher (:inputs ex))))}
                          (catch InterruptedException e (throw e))
                          (catch Exception e
                            {:error e :class (:class (llm/classify-error e))}))]
            (swap! spent + (trace-cost @trace))
            (swap! calls + (max (count @trace) (if (:error outcome) 1 0)))
            (swap! attempted inc)
            (cond
              (= :fatal (:class outcome))
              (do (swap! errors inc)
                  {:pool @pool :report (report :fatal :error (ex-message (:error outcome)))})

              (:error outcome)
              (do (swap! errors inc)
                  (swap! scores conj nil)
                  (recur more))

              :else
              (let [score (try (evaluate/score->double (metric ex (:result outcome) @trace))
                               (catch Exception e
                                 (mulog/warn ::metric-failed :error (ex-message e))
                                 0.0))]
                (swap! scores conj score)
                (when (>= score threshold)
                  (swap! passed inc)
                  (doseq [entry @trace
                          :when (and (not (:error entry)) (:predictor-id entry))]
                    (swap! pool update (:predictor-id entry) (fnil conj []) (demo-from-entry entry i))))
                (recur more)))))))))

;; ============================================================================
;; BootstrapFewShot
;; ============================================================================

(defn- pool->params [pool k]
  (into {} (map (fn [[pid demos]] [pid {:demos (vec (take k demos))}])) pool))

(defn bootstrap-few-shot
  "The paper's BootstrapFewShot: up to `:max-bootstrapped` (default 4)
   teacher-generated demos per predictor, in trainset order. For a
   single-predictor program, `:max-labeled` (default 0) tops each predictor up
   with labelled examples not already represented — only when
   `:predictor-id` names it, since labels describe final outputs and cannot be
   attributed to an intermediate step."
  [teacher trainset metric {:keys [max-bootstrapped max-labeled predictor-id]
                            :or   {max-bootstrapped 4 max-labeled 0}
                            :as   opts}]
  (let [{:keys [pool report]} (bootstrap-pool teacher trainset metric
                                              (assoc opts :max-per-predictor max-bootstrapped))
        params (pool->params pool max-bootstrapped)
        params (if (and predictor-id (pos? max-labeled))
                 (let [used (set (keep #(get-in % [:source :example]) (get-in params [predictor-id :demos])))
                       fill (->> (map-indexed vector trainset)
                                 (remove (fn [[i ex]] (or (used i) (empty? (:labels ex)))))
                                 (take max-labeled)
                                 (map (fn [[i ex]] (labeled-demo ex i))))]
                   (update-in params [predictor-id :demos] (fnil into []) fill))
                 params)]
    {:params params
     :report (assoc report :optimizer :bootstrap-few-shot
                    :max-bootstrapped max-bootstrapped :max-labeled max-labeled
                    :demos (into {} (map (fn [[pid p]] [pid (count (:demos p))])) params))}))

;; ============================================================================
;; Random search over the pool
;; ============================================================================

(defn- evaluate-candidate
  [program valset metric params {:keys [parallel budget-usd max-calls]}]
  (predictor/with-params params
    (evaluate/evaluate program valset metric :parallel (or parallel 1)
                       :budget-usd budget-usd :max-calls max-calls)))

(defn bootstrap-random-search
  "The paper's BootstrapFewShotWithRandomSearch, over a single bootstrap pool.

   Rows, all evaluated on `valset`:
     :zero-shot      the STUDENT with {pid {}} for every predictor in the pool
     :teacher        the TEACHER, zero-shot — a REFERENCE row, never a winner
                     (it is a different model, so it is not a proposal). It is
                     the number the tier question needs: how close do demos
                     bring the cheap model to the expensive one?
                     `:teacher-baseline? false` skips it.
     :labeled        labeled-few-shot (only when :predictor-id is given)
     :bootstrap      first `max-bootstrapped` pool demos per predictor
     :trial-N        a seeded shuffle of the pool, 1..max-bootstrapped demos

   opts: :trials (default 6) :max-bootstrapped (4) :predictor-id :seed (0)
         :threshold :teacher-params :parallel, and :budget-usd / :max-calls
         (both shared by the pool and every row).

   Returns {:params best-candidate-params
            :report {:best … :teacher-score …
                     :leaderboard [{:candidate :score :cost :calls :stopped :reference?}…]
                     :pool … :cost … :calls … :stopped …}}.
   Ties keep the EARLIER candidate — zero-shot first — so a proposal that adds
   prompt tokens has to earn a strictly better score than one that adds none."
  [teacher program trainset valset metric
   {:keys [trials max-bootstrapped predictor-id seed budget-usd max-calls teacher-baseline?]
    :or   {trials 6 max-bootstrapped 4 seed 0 teacher-baseline? true}
    :as   opts}]
  (let [{:keys [pool report]} (bootstrap-pool teacher trainset metric
                                              (assoc opts :max-per-predictor (* 3 max-bootstrapped)))
        pids      (cond-> (set (keys pool)) predictor-id (conj predictor-id))
        spent     (atom (:cost report))
        calls     (atom (:calls report))
        remaining #(when budget-usd (max 0.0 (- budget-usd @spent)))
        remaining-calls #(when max-calls (max 0 (- max-calls @calls)))
        exhausted? #(or (and budget-usd (<= (remaining) 0.0))
                        (and max-calls (<= (remaining-calls) 0)))
        zero      (into {} (map #(vector % {})) pids)
        candidates
        (concat
         [[:zero-shot zero]]
         (when teacher-baseline?
           [[:teacher zero :reference]])
         (when predictor-id
           [[:labeled (:params (labeled-few-shot predictor-id trainset {:k max-bootstrapped :seed seed}))]])
         (when (seq pool)
           (cons [:bootstrap (pool->params pool max-bootstrapped)]
                 (for [t (range trials)]
                   (let [rng (java.util.Random. (long (+ seed t 1)))
                         k   (inc (.nextInt rng (int max-bootstrapped)))]
                     [(keyword (str "trial-" t))
                      (into {} (map (fn [[pid demos]]
                                      [pid {:demos (vec (take k (shuffle-seeded demos (+ seed t 1))))}]))
                            pool)])))))
        leaderboard
        (loop [[[cname params reference] & more] candidates acc []]
          (if (or (nil? cname) (exhausted?))
            acc
            (let [r (evaluate-candidate (if reference teacher program) valset metric params
                                        (assoc opts :budget-usd (remaining)
                                               :max-calls (remaining-calls)))]
              (swap! spent + (:cost r))
              (swap! calls + (:calls r))
              (recur more (conj acc (cond-> {:candidate cname :params params :score (:score r)
                                             :attempted (:attempted r) :n (:n r)
                                             :cost (:cost r) :calls (:calls r) :stopped (:stopped r)}
                                      reference (assoc :reference? true)))))))
        complete? #(and (nil? (:stopped %)) (= (:attempted %) (:n %)))
        ;; Only fully-evaluated STUDENT candidates may win: a budget-truncated
        ;; run scored on fewer examples is not comparable, and the teacher row
        ;; is a different model.
        eligible (filter #(and (complete? %) (not (:reference? %))) leaderboard)
        best     (reduce (fn [b c] (if (or (nil? b) (> (:score c) (:score b))) c b)) nil eligible)
        teacher-row (first (filter #(and (:reference? %) (complete? %)) leaderboard))]
    {:params (:params best)
     :report {:optimizer     :bootstrap-random-search
              :best          (:candidate best)
              :best-score    (:score best)
              :teacher-score (:score teacher-row)
              :leaderboard   (mapv #(dissoc % :params) leaderboard)
              :pool          (assoc report :sizes (into {} (map (fn [[k v]] [k (count v)])) pool))
              :valset        (evaluate/dataset-hash valset)
              :cost          @spent
              :calls         @calls
              :stopped       (cond (nil? best) :no-complete-candidate
                                   (< (count leaderboard) (count candidates)) :budget
                                   :else nil)}}))
