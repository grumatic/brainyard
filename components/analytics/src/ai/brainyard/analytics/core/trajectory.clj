;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.analytics.core.trajectory
  "Pure session analytics over a vector of `:v 2` trajectory turn records.

   The agent command supplies the records (it owns the file I/O); everything
   here is pure and native-image-safe. Each turn record is one map produced by
   `agent.common.trajectory/build-turn-trajectory`:

     {:v 2 :turn N :question s :answer s :success bool :terminated-by kw
      :total-iterations N :iterations [{:n :channel :thought :code :result
                                        :output :error :tools :async?}]
      :model s :cost N :usage {:in :out :cache-read :cache-write} :duration-ms N}

   `analyze-all` runs the full metric suite (PQS, TCE, ICE, TUR, LT, cache, OGA,
   waste, composite SHS) in one pass and returns a map keyed for the
   `session$analytics` command and the formatter."
  (:require [clojure.string :as str]
            [clojure.set :as set]
            [ai.brainyard.analytics.core.pqs :as pqs]
            [ai.brainyard.analytics.core.cost :as cost]
            [ai.brainyard.analytics.core.waste :as waste]))

;; ============================================================================
;; Small numeric helpers
;; ============================================================================

(defn- round ^long [x] (long (Math/round (double x))))

(defn- clamp [x lo hi] (-> x (max lo) (min hi)))

(defn- mean [xs]
  (if (seq xs) (/ (double (reduce + xs)) (count xs)) 0.0))

(defn- percentile
  "Nearest-rank percentile (p in [0,1]) over a numeric coll. 0 when empty."
  [xs p]
  (if (empty? xs)
    0
    (let [sorted (vec (sort xs))
          idx    (clamp (int (Math/floor (* p (count sorted)))) 0 (dec (count sorted)))]
      (nth sorted idx))))

(defn- safe-div [n d] (if (and d (pos? d)) (/ (double n) d) 0.0))

(defn- round2
  "Round to 2 decimals without locale-sensitive string formatting."
  [x] (/ (Math/round (* (double x) 100.0)) 100.0))

;; ============================================================================
;; Text similarity (local, mirrors pqs/waste internals — kept tiny + private)
;; ============================================================================

(def ^:private stop-words
  #{"the" "a" "an" "is" "are" "to" "of" "in" "for" "on" "with" "and" "or"
    "it" "this" "that" "please" "can" "you" "i" "we" "do" "my"})

(defn- word-set [text]
  (when (seq text)
    (->> (str/split (str/lower-case text) #"[^a-z0-9_\-]+")
         (remove str/blank?)
         (remove stop-words)
         set)))

(defn- jaccard [s1 s2]
  (if (and (seq s1) (seq s2))
    (safe-div (count (set/intersection s1 s2)) (count (set/union s1 s2)))
    0.0))

;; ============================================================================
;; Projection — trajectory records → analyzer inputs (inverse of usage->compact)
;; ============================================================================

(defn records->messages
  "Project turn records into a `[{:role :content}]` conversation: each turn's
   `:question` becomes a user message, its `:answer` an assistant message."
  [records]
  (vec
   (mapcat (fn [{:keys [question answer]}]
             (cond-> []
               (some? question) (conj {:role "user" :content (str question)})
               (some? answer)   (conj {:role "assistant" :content (str answer)})))
           records)))

(defn record->usage-call
  "Synthesize one usage-history call record from a turn — the inverse of
   `agent.common.trajectory/usage->compact` plus cost/latency."
  [{:keys [model usage cost duration-ms]}]
  {:model              (or model "unknown")
   :input-tokens       (get usage :in 0)
   :output-tokens      (get usage :out 0)
   :cache-read-tokens  (get usage :cache-read 0)
   :cache-write-tokens (get usage :cache-write 0)
   :cost               {:total-cost (or cost 0)}
   :latency-ms         duration-ms})

(defn records->usage-history [records] (mapv record->usage-call records))

(defn records->usage-summary
  "Aggregate turn records into a `{:totals … :by-model …}` usage summary shaped
   like clj-llm's, so the existing cost/waste analyzers run unchanged."
  [records]
  (let [calls (records->usage-history records)
        sum   (fn [k] (reduce + (map #(get % k 0) calls)))
        in    (sum :input-tokens)
        out   (sum :output-tokens)
        totals {:input-tokens       in
                :output-tokens      out
                :total-tokens       (+ in out)
                :cache-read-tokens  (sum :cache-read-tokens)
                :cache-write-tokens (sum :cache-write-tokens)
                :total-cost         (reduce + (map #(get-in % [:cost :total-cost] 0) calls))
                :call-count         (count calls)}
        by-model (reduce
                  (fn [m c]
                    (let [mk (:model c)]
                      (-> m
                          (update-in [mk :input-tokens]  (fnil + 0) (:input-tokens c))
                          (update-in [mk :output-tokens] (fnil + 0) (:output-tokens c))
                          (update-in [mk :total-cost]    (fnil + 0) (get-in c [:cost :total-cost] 0))
                          (update-in [mk :call-count]    (fnil + 0) 1))))
                  {}
                  calls)]
    {:totals totals :by-model by-model}))

;; ============================================================================
;; 6.1 PQS — Prompt Quality (per-turn, adjustments from trajectory signals)
;; ============================================================================

(defn- one-turn-completion? [{:keys [total-iterations success terminated-by]}]
  (and success
       (= terminated-by :answer)
       (<= (or total-iterations 0) 2)))

(defn- trajectory-adjustments
  "Recompute PQS conversation adjustments from trajectory signals rather than
   message-text inference (doc §6.1). Same scoring weights as
   `pqs/compute-adjustments`: corrections -3 each, one-turn completions +5
   (capped at 3), retry storms -10 each."
  [records]
  (let [qs       (mapv #(str (:question %)) records)
        wsets    (mapv word-set qs)
        n        (count records)
        ;; A turn retries the prior one (jaccard > 0.5) AND that prior turn
        ;; failed or exhausted iterations.
        corrections (reduce
                     (fn [acc i]
                       (let [prev (nth records (dec i))
                             prev-bad (or (not (:success prev))
                                          (= (:terminated-by prev) :max-iterations))]
                         (if (and prev-bad
                                  (> (jaccard (nth wsets i) (nth wsets (dec i))) 0.5))
                           (inc acc) acc)))
                     0 (range 1 n))
        one-turns   (count (filter one-turn-completion? records))
        ;; Retry storm: a window of ≥3 mutually jaccard-similar questions.
        storms (loop [i 0 used #{} s 0]
                 (if (>= i n)
                   s
                   (if (used i)
                     (recur (inc i) used s)
                     (let [cluster (reduce (fn [g j]
                                             (if (and (not (used j))
                                                      (> (jaccard (nth wsets i) (nth wsets j)) 0.6))
                                               (conj g j) g))
                                           #{i} (range (inc i) n))]
                       (if (>= (count cluster) 3)
                         (recur (inc i) (into used cluster) (inc s))
                         (recur (inc i) used s))))))
        c-adj (* -3 corrections)
        o-adj (* 5 (min one-turns 3))
        s-adj (* -10 storms)]
    {:correction-turns c-adj
     :one-turn-completions o-adj
     :retry-storms s-adj
     :total (+ c-adj o-adj s-adj)}))

(defn pqs-metric
  "Per-turn PQS + session rollup. `analysis-lm` (non-nil) adds LLM-refined
   recommendations over the projected conversation."
  [records messages analysis-lm]
  (if (empty? records)
    {:overall-score 0
     :dimensions (zipmap (keys pqs/dimension-weights) (repeat 0))
     :adjustments {:correction-turns 0 :one-turn-completions 0 :retry-storms 0 :total 0}
     :per-turn [] :recommendations []}
    (let [per-turn (mapv (fn [t]
                           (let [s (pqs/score-prompt (str (:question t)))]
                             {:turn (:turn t)
                              :score (:total s)
                              :dimensions (dissoc s :total)}))
                         records)
          avg-dims (reduce (fn [m dim]
                             (assoc m dim (round (mean (map #(get-in % [:dimensions dim]) per-turn)))))
                           {} (keys pqs/dimension-weights))
          adj      (trajectory-adjustments records)
          base     (reduce + (vals avg-dims))
          overall  (clamp (+ base (:total adj)) 0 100)
          recs     (when analysis-lm
                     (try (:recommendations (pqs/score-pqs messages :lm-config analysis-lm))
                          (catch Exception _ nil)))]
      {:overall-score overall
       :dimensions avg-dims
       :adjustments adj
       :per-turn per-turn
       :recommendations (or recs [])})))

;; ============================================================================
;; 6.2 TCE — Token & Cost Efficiency (cost reuse + cache/share/efficiency)
;; ============================================================================

(defn tce-metric
  [records usage-summary usage-history total-dur-ms]
  (let [cost-result (cost/calculate-session-cost usage-summary usage-history
                                                 :session-duration-ms total-dur-ms)
        totals      (:totals usage-summary)
        in          (:input-tokens totals 0)
        out         (:output-tokens totals 0)
        cache-read  (:cache-read-tokens totals 0)
        cache-hit   (safe-div cache-read (+ cache-read in))
        out-share   (safe-div out (+ in out))
        succ-turns  (count (filter :success records))
        cost-per-succ (safe-div (:total-cost totals 0) succ-turns)
        actual-cost (get-in cost-result [:actual :total-cost] 0)
        savings     (:savings-potential cost-result 0)
        rightsizing (- 1.0 (clamp (safe-div savings actual-cost) 0.0 1.0))
        out-comp    (clamp (safe-div out-share 0.25) 0.0 1.0)
        efficiency  (round (* 100 (+ (* 0.5 rightsizing)
                                     (* 0.3 cache-hit)
                                     (* 0.2 out-comp))))]
    (assoc cost-result
           :cache-hit-rate cache-hit
           :output-share out-share
           :cost-per-successful-turn cost-per-succ
           :efficiency-score (clamp efficiency 0 100))))

;; ============================================================================
;; 6.3 ICE — Iteration / Convergence Efficiency
;; ============================================================================

(defn ice-metric
  [records]
  (let [iters    (mapv #(or (:total-iterations %) 0) records)
        n        (count records)
        one-shot (safe-div (count (filter #(<= % 2) iters)) n)
        term     (frequencies (map :terminated-by records))
        answer-share  (safe-div (get term :answer 0) n)
        maxiter-share (safe-div (get term :max-iterations 0) n)
        refinement (->> records
                        (mapcat :iterations)
                        (filter #(and (= (:channel %) "none") (:thought %)))
                        count)
        score (round (* 100 (clamp (- (+ (* 0.4 one-shot) (* 0.6 answer-share))
                                      (* 0.3 maxiter-share))
                                   0.0 1.0)))]
    {:avg-iterations (round2 (mean iters))
     :p50 (percentile iters 0.50)
     :p90 (percentile iters 0.90)
     :one-shot-rate one-shot
     :terminated-by term
     :refinement-loops refinement
     :score score}))

;; ============================================================================
;; 6.4 TUR — Tool Utilization & Reliability
;; ============================================================================

(defn- turn-redundant-calls [turn]
  (let [calls (->> (:iterations turn) (mapcat :tools) (map #(select-keys % [:name :args])))
        dups  (->> (frequencies calls) vals (filter #(> % 1)) (map dec))]
    (reduce + 0 dups)))

(defn tur-metric
  [records]
  (let [all-iters (mapcat :iterations records)
        tool-calls (mapcat :tools all-iters)
        total      (count tool-calls)
        active     (filter #(or (seq (:tools %)) (seq (:code %)) (seq (:error %))) all-iters)
        errored    (count (filter #(seq (:error %)) all-iters))
        error-rate (safe-div errored (count active))
        redundant  (reduce + 0 (map turn-redundant-calls records))
        redund-rate (safe-div redundant total)
        by-name    (->> tool-calls (map :name) frequencies
                        (sort-by val >) (take 5)
                        (mapv (fn [[nm c]] {:name nm :count c})))
        channels   (frequencies (map #(or (:channel %) "none") all-iters))]
    {:total-tool-calls total
     :calls-per-turn (round2 (safe-div total (count records)))
     :unique-tools (count (distinct (map :name tool-calls)))
     :top-tools by-name
     :error-rate error-rate
     :redundant-calls redundant
     :channel-mix channels
     ;; N/A (nil) when the session did no code/tool work → SHS renormalizes.
     :score (when (seq active)
              (round (* 100 (* (- 1.0 (clamp error-rate 0.0 1.0))
                               (- 1.0 (clamp redund-rate 0.0 1.0))))))}))

;; ============================================================================
;; 6.5 LT — Latency & Throughput
;; ============================================================================

(def ^:private throughput-target-tps 40.0)

(defn lt-metric
  [records usage-history total-dur-ms]
  (let [durs (keep :duration-ms records)
        tp   (cost/throughput-stats usage-history total-dur-ms)
        tps  (:output-tokens-per-sec tp 0)
        slowest (->> records
                     (filter :duration-ms)
                     (sort-by :duration-ms >)
                     (take 3)
                     (mapv #(select-keys % [:turn :duration-ms])))]
    {:total-ms (reduce + 0 durs)
     :p50-ms (percentile durs 0.50)
     :p90-ms (percentile durs 0.90)
     :output-tokens-per-sec tps
     :throughput tp
     :slowest-turns slowest
     ;; nil when no latency was captured → SHS renormalizes.
     :score (when (seq durs)
              (round (* 100 (clamp (safe-div tps throughput-target-tps) 0.0 1.0))))}))

;; ============================================================================
;; 6.6 Cache Efficiency
;; ============================================================================

(defn cache-metric
  [records]
  (let [per-turn (mapv (fn [t]
                         (let [in (get-in t [:usage :in] 0)
                               cr (get-in t [:usage :cache-read] 0)]
                           {:turn (:turn t) :rate (safe-div cr (+ cr in))}))
                       records)
        cache-read (reduce + 0 (map #(get-in % [:usage :cache-read] 0) records))
        fresh-in   (reduce + 0 (map #(get-in % [:usage :in] 0) records))
        rates      (map :rate per-turn)
        third      (max 1 (quot (count rates) 3))
        early      (mean (take third rates))
        late       (mean (take-last third rates))]
    {:hit-rate (safe-div cache-read (+ cache-read fresh-in))
     :cached-input cache-read
     :fresh-input fresh-in
     :per-turn per-turn
     :degrading? (and (> (count rates) 2) (< late (* 0.8 early)))}))

;; ============================================================================
;; 6.7 OGA — Outcome / Goal Achievement
;; ============================================================================

(defn oga-metric
  [records]
  (let [n        (count records)
        succ     (count (filter :success records))
        failed   (->> records
                      (remove :success)
                      (mapv #(select-keys % [:turn :terminated-by])))
        ;; trailing consecutive failures from the end
        trailing (->> records reverse (take-while #(not (:success %))) count)
        ;; longest run of consecutive failures (stuck detection)
        stuck    (->> records
                      (reduce (fn [{:keys [run mx]} t]
                                (if (:success t)
                                  {:run 0 :mx mx}
                                  (let [r (inc run)] {:run r :mx (max mx r)})))
                              {:run 0 :mx 0})
                      :mx)
        base     (* 100.0 (safe-div succ n))
        penalty  (min base (* 10 trailing))
        score    (round (clamp (- base penalty) 0 100))]
    {:success-rate (safe-div succ n)
     :failed-turns failed
     :trailing-failures trailing
     :max-consecutive-failures stuck
     :score score}))

;; ============================================================================
;; 6.9 SHS — Composite Session Health Score
;; ============================================================================

(def default-shs-weights
  {:pqs 0.20 :tce 0.20 :oga 0.20 :ice 0.15 :tur 0.15 :lt 0.10})

(defn- letter-grade [score]
  (cond (>= score 90) "A"  (>= score 85) "A-" (>= score 80) "B+"
        (>= score 75) "B"  (>= score 70) "B-" (>= score 65) "C+"
        (>= score 60) "C"  (>= score 55) "C-" (>= score 50) "D"
        :else "F"))

(defn compute-shs
  "Roll component scores into a composite 0–100 + grade. Components with a nil
   score (N/A — e.g. no tool calls) are dropped and the remaining weights
   renormalized so absent signal doesn't inflate the result (doc §12)."
  [components weights]
  (let [weights (let [w (or weights default-shs-weights)]
                  (if (< (Math/abs (- 1.0 (double (reduce + (vals w))))) 1.0E-6)
                    w default-shs-weights))
        present (into {} (filter (comp some? val) components))
        tw      (reduce + (map #(get weights % 0) (keys present)))
        score   (if (pos? tw)
                  (round (/ (reduce + (map (fn [[k v]] (* v (get weights k 0))) present)) tw))
                  0)]
    {:score score
     :grade (letter-grade score)
     :components components
     :weights weights}))

;; ============================================================================
;; Orchestrator
;; ============================================================================

(defn analyze-all
  "Run the full analyzer suite over a vector of `:v 2` turn records. Always
   computes all metrics. Pure — caller supplies records and (for the deep pass)
   the resolved lm-config. Returns a result map keyed for the command/formatter."
  [records & {:keys [lm-config skip-llm-analysis shs-weights]
              :or {skip-llm-analysis true}}]
  (let [records       (vec records)
        analysis-lm   (when-not skip-llm-analysis lm-config)
        messages      (records->messages records)
        usage-history (records->usage-history records)
        usage-summary (records->usage-summary records)
        total-dur     (reduce + 0 (keep :duration-ms records))
        pqs   (pqs-metric records messages analysis-lm)
        cost  (tce-metric records usage-summary usage-history total-dur)
        ice   (ice-metric records)
        tur   (tur-metric records)
        lt    (lt-metric records usage-history total-dur)
        cache (cache-metric records)
        oga   (oga-metric records)
        waste (waste/detect-all-waste messages usage-summary usage-history
                                      :lm-config analysis-lm)
        shs   (compute-shs {:pqs (:overall-score pqs)
                            :tce (:efficiency-score cost)
                            :oga (:score oga)
                            :ice (:score ice)
                            :tur (:score tur)
                            :lt  (:score lt)}
                           shs-weights)]
    {:turns (count records)
     :health-score shs
     :pqs pqs
     :cost cost
     :iteration ice
     :tools tur
     :latency lt
     :cache cache
     :outcome oga
     :waste waste}))
