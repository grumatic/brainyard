;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.clj-llm.core.evaluate
  "Examples, metrics and a budgeted evaluation harness — Phase 2 of
   docs/design/dspy-programming-model-proposal.md.

   The three shapes:

     example  {:inputs {…} :labels {…}? :meta {…}?}
              Labels are optional and, when present, describe only the FINAL
              outputs — the paper's label-efficiency point. An example without
              labels can still be scored by a metric that does not need them
              (schema validity, an LM judge).
     program  (fn [inputs] -> result)   result is what `run-predictor` returns
              ({:outputs … :reasoning …}); any function of that shape works,
              so a module composing several predictors is a program too.
     metric   (fn [example result trace] -> number | boolean | nil)
              `trace` is the vector of predictor calls the program made, so a
              metric can inspect intermediate steps. true/false/nil score as
              1.0/0.0/0.0.

   `evaluate` owns the failure policy, because a dev-set run is exactly where
   provider errors stop being rare:

     :transient  retried (bounded) — a 5xx mid-run must not score as a wrong
                 answer, or a flaky provider reads as a worse prompt
     :malformed  scored 0 — unparseable output IS the program being wrong
     :fatal      stops the run — auth/quota fails every remaining example the
                 same way, and spending the rest of the budget to learn that
                 is the wrong trade

   and the budget: spend is summed from the traced usage of each example and
   checked before starting the next. With `:parallel n` up to n-1 examples may
   already be in flight when the budget trips, so the overshoot is bounded by
   the cost of n-1 examples, not zero — reported as `:stopped :budget`."
  (:require [ai.brainyard.clj-llm.core.llm :as llm]
            [ai.brainyard.clj-llm.core.predictor :as predictor]
            [ai.brainyard.mulog.interface :as mulog]
            [clojure.set :as set]
            [clojure.string :as str])
  (:import [java.util.concurrent Executors ExecutorService Future TimeUnit]))

;; ============================================================================
;; Examples
;; ============================================================================

(defn example
  "Build an example. `labels` and `meta` are optional."
  ([inputs] (example inputs nil nil))
  ([inputs labels] (example inputs labels nil))
  ([inputs labels meta]
   (cond-> {:inputs inputs}
     (seq labels) (assoc :labels labels)
     (seq meta)   (assoc :meta meta))))

(defn- content-hash
  "Stable hash of an example's inputs — split assignment must not move when
   the dataset is reordered or grows."
  [ex]
  (let [md (java.security.MessageDigest/getInstance "SHA-256")
        bs (.digest md (.getBytes (pr-str (into (sorted-map) (:inputs ex))) "UTF-8"))]
    ;; first 4 bytes as an unsigned int
    (bit-and (reduce (fn [acc b] (+ (* acc 256) (bit-and b 0xff))) 0 (take 4 bs))
             0xffffffff)))

(defn split
  "Deterministic train/val/test split by content hash. `ratios` is
   {:train r :val r :test r} (normalized). Returns {:train [...] :val [...]
   :test [...]}; an example lands in the same split however the dataset is
   ordered, so a test set stays untouched as examples are added."
  [examples {:keys [train val test] :or {train 0.6 val 0.2 test 0.2}}]
  (let [total (double (+ train val test))
        t1 (/ train total)
        t2 (/ (+ train val) total)]
    (reduce (fn [acc ex]
              (let [u (/ (double (content-hash ex)) 4294967296.0)
                    k (cond (< u t1) :train (< u t2) :val :else :test)]
                (update acc k conj ex)))
            {:train [] :val [] :test []}
            examples)))

(defn dataset-hash
  "Short content hash identifying a set of examples, order-insensitive —
   recorded in reports so a score names exactly the data behind it."
  [examples]
  (let [md (java.security.MessageDigest/getInstance "SHA-256")]
    (doseq [h (sort (map content-hash examples))]
      (.update md (.getBytes (str h) "UTF-8")))
    (subs (apply str (map #(format "%02x" (bit-and % 0xff)) (.digest md))) 0 12)))

;; ============================================================================
;; Metrics
;; ============================================================================

(defn score->double
  "Normalize a metric's return value to [0.0, 1.0]-ish doubles."
  [v]
  (cond
    (true? v) 1.0
    (or (false? v) (nil? v)) 0.0
    (number? v) (double v)
    :else 0.0))

(defn- norm [v]
  (if (string? v) (str/lower-case (str/trim v)) v))

(defn exact-match
  "Metric: every labelled field (or just `fields`) equals the output, strings
   compared trimmed and case-insensitively. An example with no labels scores 0
   — exact match with nothing to match is not a pass."
  ([] (exact-match nil))
  ([fields]
   (fn [{:keys [labels]} result _trace]
     (let [ks (or (seq fields) (keys labels))]
       (boolean (and (seq ks)
                     (every? #(= (norm (get labels %)) (norm (get-in result [:outputs %]))) ks)))))))

(defn schema-valid
  "Metric: the call produced schema-valid outputs. `run-predictor` with a CoT
   strategy reports `:valid?`; a predict-strategy result has none, and counts
   as valid (predict already coerced and validated, logging on failure)."
  []
  (fn [_ex result _trace]
    (not (false? (:valid? result)))))

(defn set-f1
  "Metric: F1 between the label and output collections at `field`, each item
   projected to a comparison key by `key-fn` (default: the item, normalized).

     (set-f1 :entities #(str/lower-case (:name %)))

   Both empty ⇒ 1.0 (correctly found nothing). The graph extractor's commonest
   right answer is \"nothing durable here\", and scoring that 0 would reward a
   model for inventing entities."
  ([field] (set-f1 field norm))
  ([field key-fn]
   (fn [{:keys [labels]} result _trace]
     (let [gold (set (map key-fn (get labels field)))
           pred (set (map key-fn (get-in result [:outputs field])))]
       (cond
         (and (empty? gold) (empty? pred)) 1.0
         (or (empty? gold) (empty? pred))  0.0
         :else (let [tp (count (set/intersection gold pred))
                     p  (/ tp (double (count pred)))
                     r  (/ tp (double (count gold)))]
                 (if (zero? tp) 0.0 (/ (* 2 p r) (+ p r)))))))))

(defn weighted
  "Metric combinator: `[[metric weight] …]` → weighted mean of their scores."
  [metric-weights]
  (let [total (double (reduce + (map second metric-weights)))]
    (fn [ex result trace]
      (/ (reduce + (map (fn [[m w]] (* w (score->double (m ex result trace)))) metric-weights))
         total))))

(defn all-of
  "Metric combinator: 1.0 only when every metric scores ≥ its threshold
   (default: > 0.5 for each)."
  [& metrics]
  (fn [ex result trace]
    (every? #(> (score->double (% ex result trace)) 0.5) metrics)))

;; ============================================================================
;; Programs
;; ============================================================================

(defn predictor-program
  "Program running a predictor with fixed kwargs (e.g. :lm-config)."
  [p & {:as opts}]
  (fn [inputs]
    (apply predictor/run p inputs (mapcat identity opts))))

;; ============================================================================
;; Evaluate
;; ============================================================================

(defn- trace-cost [trace]
  (reduce + 0.0 (keep #(get-in % [:usage :cost :total-cost]) trace)))

(defn- trace-tokens [trace]
  {:in  (reduce + 0 (keep #(get-in % [:usage :input-tokens]) trace))
   :out (reduce + 0 (keep #(get-in % [:usage :output-tokens]) trace))})

(defn- run-one
  "Run the program on one example: retry transient failures, classify the
   rest. Returns a per-example record (never throws, except on interrupt)."
  [program metric ex idx {:keys [max-transient-retries retry-delay-ms]}]
  (loop [attempt 0 prior-calls 0]
    (let [trace (atom [])
          t0    (System/currentTimeMillis)
          outcome (try
                    (let [result (binding [predictor/*trace* trace]
                                   (program (:inputs ex)))]
                      {:result result})
                    (catch InterruptedException e (throw e))
                    (catch Exception e
                      {:error e :class (:class (llm/classify-error e))}))]
      (if (and (= :transient (:class outcome)) (< attempt max-transient-retries))
        (do (Thread/sleep (long (* retry-delay-ms (inc attempt))))
            ;; a failed attempt still made its calls — they count against
            ;; :max-calls even though their trace is discarded
            (recur (inc attempt) (+ prior-calls (max 1 (count @trace)))))
        (let [cost (trace-cost @trace)
              base {:index idx :cost cost :tokens (trace-tokens @trace)
                    ;; an exception before any trace entry was still one call
                    :calls (+ prior-calls (max (count @trace) (if (:error outcome) 1 0)))
                    :elapsed-ms (- (System/currentTimeMillis) t0)
                    :attempts (inc attempt)}]
          (if-let [e (:error outcome)]
            (assoc base :score 0.0 :error (ex-message e) :error-class (:class outcome))
            (let [score (try (score->double (metric ex (:result outcome) @trace))
                             (catch Exception me
                               (mulog/warn ::metric-failed :index idx :error (ex-message me))
                               0.0))]
              (assoc base :score score :outputs (get-in outcome [:result :outputs])))))))))

(defn- evaluate-pass
  "ONE pass of `program` over `examples`. opts:
     :parallel               worker threads (default 1)
     :budget-usd             stop starting new examples once spend reaches it
     :max-calls              stop starting new examples once this many predictor
                             calls were made — the budget that means something
                             on subscription providers, whose USD is notional
     :max-tokens             stop starting new examples once input+output tokens
                             reach this (input includes cache reads/writes) —
                             the budget that tracks a rate or context allowance
     :max-transient-retries  per example (default 2)
     :retry-delay-ms         linear backoff base (default 1000)

   Returns
     {:score     mean over attempted examples (errors score 0)
      :n         examples given   :attempted  examples run
      :errors    count             :stopped    nil | :budget | :fatal
      :cost      usd               :calls      predictor calls
      :tokens    {:in :out}        :dataset    dataset-hash
      :elapsed-ms …
      :per-example [{:index :score :outputs|:error :error-class :cost :calls …}]}"
  [program examples metric & {:keys [parallel budget-usd max-calls max-tokens
                                     max-transient-retries retry-delay-ms]
                              :or   {parallel 1 max-transient-retries 2 retry-delay-ms 1000}}]
  (let [examples (vec examples)
        t0       (System/currentTimeMillis)
        spent    (atom 0.0)
        calls    (atom 0)
        tokens   (atom 0)
        stopped  (atom nil)
        run-opts {:max-transient-retries max-transient-retries :retry-delay-ms retry-delay-ms}
        may-start? #(and (nil? @stopped)
                         (or (and (or (nil? budget-usd) (< @spent budget-usd))
                                  (or (nil? max-calls) (< @calls max-calls))
                                  (or (nil? max-tokens) (< @tokens max-tokens)))
                             (do (compare-and-set! stopped nil :budget) false)))
        record!  (fn [r]
                   (swap! spent + (:cost r))
                   (swap! calls + (:calls r))
                   (swap! tokens + (get-in r [:tokens :in] 0) (get-in r [:tokens :out] 0))
                   (when (= :fatal (:error-class r))
                     (compare-and-set! stopped nil :fatal))
                   r)
        results
        (if (<= parallel 1)
          (loop [i 0 acc []]
            (if (and (< i (count examples)) (may-start?))
              (recur (inc i) (conj acc (record! (run-one program metric (nth examples i) i run-opts))))
              acc))
          (let [^ExecutorService pool (Executors/newFixedThreadPool (int parallel))
                ;; bound-fn conveys *trace-context*/*params* into the workers,
                ;; so a candidate evaluated under with-params sees its params.
                task (fn [i] (bound-fn []
                               (when (may-start?)
                                 (record! (run-one program metric (nth examples i) i run-opts)))))]
            (try
              (let [futs (mapv #(.submit pool ^Callable (task %)) (range (count examples)))]
                (vec (keep #(.get ^Future %) futs)))
              (finally
                (.shutdownNow pool)
                (.awaitTermination pool 5 TimeUnit/SECONDS)))))
        results  (vec (sort-by :index results))
        attempted (count results)]
    {:score      (if (pos? attempted)
                   (/ (reduce + (map :score results)) (double attempted))
                   0.0)
     :n          (count examples)
     :attempted  attempted
     :errors     (count (filter :error results))
     :stopped    @stopped
     :cost       @spent
     :calls      @calls
     :tokens     {:in  (reduce + 0 (map #(get-in % [:tokens :in]) results))
                  :out (reduce + 0 (map #(get-in % [:tokens :out]) results))}
     :dataset    (dataset-hash examples)
     :elapsed-ms (- (System/currentTimeMillis) t0)
     :per-example results}))

;; ============================================================================
;; Repeats and variance
;; ============================================================================
;;
;; A single pass over a small valset is one draw from a noisy distribution:
;; the first real graph-extract runs scored the SAME model, instructions and
;; 12 examples at 0.559 and 0.605. Any candidate compared on one pass each is
;; being compared on that noise. `:repeats k` re-runs the whole set k times and
;; reports the spread of the per-pass means, so a comparison can ask whether a
;; difference exceeds it.
;;
;; What this does NOT cover: which examples happen to be in the valset.
;; Repeats measure run-to-run (sampling) noise for a fixed set; a 12-example
;; set can still be unrepresentative. Only more examples fix that.

(def ^:private t-crit-95
  "Two-sided 95% Student-t critical values for df 1..30."
  [12.706 4.303 3.182 2.776 2.571 2.447 2.365 2.306 2.262 2.228
   2.201 2.179 2.160 2.145 2.131 2.120 2.110 2.101 2.093 2.086
   2.080 2.074 2.069 2.064 2.060 2.056 2.052 2.048 2.045 2.042])

(defn t-critical
  "Two-sided 95% t critical value for (possibly fractional) df; 1.96 past 30.
   Fractional df (Welch) rounds DOWN — the conservative direction."
  [df]
  (let [d (long (Math/floor (double df)))]
    (cond (< d 1) (first t-crit-95)
          (<= d 30) (nth t-crit-95 (dec d))
          :else 1.96)))

(defn- mean [xs] (/ (reduce + xs) (double (count xs))))

(defn- sample-stddev [xs]
  (when (> (count xs) 1)
    (let [m (mean xs)]
      (Math/sqrt (/ (reduce + (map #(let [d (- % m)] (* d d)) xs))
                    (double (dec (count xs))))))))

(defn evaluate
  "Run `program` over `examples`, scoring with `metric`. opts:
     :repeats                full passes over the set (default 1)
     :parallel               worker threads per pass (default 1)
     :budget-usd / :max-calls / :max-tokens
                             shared across ALL passes; a pass starts only while
                             budget remains, and one cut short stops the run
     :max-transient-retries  per example (default 2)
     :retry-delay-ms         linear backoff base (default 1000)

   Returns
     {:score        mean of the COMPLETE passes' means (a budget-cut pass is
                    biased toward whichever examples ran first, so it is
                    excluded when any complete pass exists)
      :repeats      passes requested    :completed-repeats  passes that finished
      :repeat-scores [per-pass mean …]
      :stddev       sample stddev of the complete pass means (nil when < 2)
      :stderr       stddev / √completed (nil when < 2)
      :ci95         [lo hi] t-based interval on :score (nil when < 2)
      :n :attempted :errors :stopped :cost :calls :tokens :dataset :elapsed-ms
      :per-example  [{:index :score (mean over passes) :scores [...] :spread
                      :outputs (last pass) :errors n :cost :calls …}]}

   With :repeats 1 every field keeps its single-pass meaning; :stddev,
   :stderr and :ci95 are nil — one pass says nothing about its own noise."
  [program examples metric & {:keys [repeats budget-usd max-calls max-tokens]
                              :or   {repeats 1}
                              :as   opts}]
  (let [examples (vec examples)
        n        (count examples)
        t0       (System/currentTimeMillis)
        pass-opts (dissoc opts :repeats)
        passes
        (loop [i 0 acc [] spent 0.0 calls 0 tokens 0]
          (if (or (>= i repeats)
                  (some :stopped acc)
                  (and budget-usd (>= spent budget-usd))
                  (and max-calls (>= calls max-calls))
                  (and max-tokens (>= tokens max-tokens)))
            acc
            (let [r (apply evaluate-pass program examples metric
                           (mapcat identity
                                   (cond-> pass-opts
                                     budget-usd (assoc :budget-usd (- budget-usd spent))
                                     max-calls  (assoc :max-calls (- max-calls calls))
                                     max-tokens (assoc :max-tokens (- max-tokens tokens)))))]
              (recur (inc i) (conj acc r)
                     (+ spent (:cost r)) (+ calls (:calls r))
                     (+ tokens (get-in r [:tokens :in] 0) (get-in r [:tokens :out] 0))))))
        complete (filter #(and (nil? (:stopped %)) (= (:attempted %) n)) passes)
        scored   (if (seq complete) complete passes)
        pass-scores (mapv :score scored)
        sd       (when (seq complete) (sample-stddev (mapv :score complete)))
        k        (count complete)
        se       (when sd (/ sd (Math/sqrt k)))
        score    (if (seq pass-scores) (mean pass-scores) 0.0)
        ;; the run is complete only when EVERY requested pass completed
        stopped  (or (some :stopped passes)
                     (when (< (count passes) repeats) :budget))
        by-index (group-by :index (mapcat :per-example passes))]
    {:score             score
     :repeats           repeats
     :completed-repeats k
     :repeat-scores     (mapv :score passes)
     :stddev            sd
     :stderr            se
     :ci95              (when se
                          (let [h (* (t-critical (dec k)) se)] [(- score h) (+ score h)]))
     :n                 n
     :attempted         (if stopped
                          (quot (reduce + (map :attempted passes)) (max 1 repeats))
                          n)
     :errors            (reduce + (map :errors passes))
     :stopped           stopped
     :cost              (reduce + 0.0 (map :cost passes))
     :calls             (reduce + 0 (map :calls passes))
     :tokens            {:in  (reduce + 0 (map #(get-in % [:tokens :in] 0) passes))
                         :out (reduce + 0 (map #(get-in % [:tokens :out] 0) passes))}
     :dataset           (dataset-hash examples)
     :elapsed-ms        (- (System/currentTimeMillis) t0)
     :per-example
     (vec (for [i (range n)
                :let [rs (get by-index i)]
                :when (seq rs)]
            (let [ss (mapv :score rs)]
              (cond-> {:index  i
                       :score  (mean ss)
                       :scores ss
                       :spread (- (apply max ss) (apply min ss))
                       :errors (count (filter :error rs))
                       :cost   (reduce + 0.0 (map :cost rs))
                       :calls  (reduce + 0 (map :calls rs))
                       :tokens {:in  (reduce + 0 (map #(get-in % [:tokens :in] 0) rs))
                                :out (reduce + 0 (map #(get-in % [:tokens :out] 0) rs))}}
                (:outputs (last rs))     (assoc :outputs (:outputs (last rs)))
                (:error (last rs))       (assoc :error (:error (last rs))
                                                :error-class (:error-class (last rs)))
                (> (count rs) 1)         (assoc :attempts (reduce + (map :attempts rs)))
                (= (count rs) 1)         (assoc :attempts (:attempts (first rs)))))))}))

(defn compare-scores
  "Is evaluation result `a` better than `b` by more than their noise?

   Welch's t-test on the per-pass means: the difference must exceed
   t(df) × √(se_a² + se_b²). When either side has no variance estimate
   (a single pass) there is nothing to test against, so it falls back to a
   plain `>` and says so with :tested? false — the old single-pass behaviour,
   never a silent pass. Two deterministic sides (both stderr 0) also compare
   plainly: zero noise means any difference is real.

   Returns {:better? bool :diff d :margin m :tested? bool}."
  [a b]
  (let [diff (- (double (:score a)) (double (:score b)))
        sa (:stderr a) sb (:stderr b)]
    (if (or (nil? sa) (nil? sb))
      {:better? (pos? diff) :diff diff :margin 0.0 :tested? false}
      (let [va (* sa sa) vb (* sb sb)
            ka (:completed-repeats a) kb (:completed-repeats b)
            v  (+ va vb)]
        (if (zero? v)
          {:better? (pos? diff) :diff diff :margin 0.0 :tested? true}
          (let [df (/ (* v v)
                      (+ (/ (* va va) (max 1 (dec ka)))
                         (/ (* vb vb) (max 1 (dec kb)))))
                margin (* (t-critical df) (Math/sqrt v))]
            {:better? (> diff margin) :diff diff :margin margin :tested? true}))))))
