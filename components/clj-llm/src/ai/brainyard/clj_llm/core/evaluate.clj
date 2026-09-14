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
  (loop [attempt 0]
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
            (recur (inc attempt)))
        (let [cost (trace-cost @trace)
              base {:index idx :cost cost :tokens (trace-tokens @trace)
                    :elapsed-ms (- (System/currentTimeMillis) t0)
                    :attempts (inc attempt)}]
          (if-let [e (:error outcome)]
            (assoc base :score 0.0 :error (ex-message e) :error-class (:class outcome))
            (let [score (try (score->double (metric ex (:result outcome) @trace))
                             (catch Exception me
                               (mulog/warn ::metric-failed :index idx :error (ex-message me))
                               0.0))]
              (assoc base :score score :outputs (get-in outcome [:result :outputs])))))))))

(defn evaluate
  "Run `program` over `examples`, scoring with `metric`. opts:
     :parallel               worker threads (default 1)
     :budget-usd             stop starting new examples once spend reaches it
     :max-transient-retries  per example (default 2)
     :retry-delay-ms         linear backoff base (default 1000)

   Returns
     {:score     mean over attempted examples (errors score 0)
      :n         examples given   :attempted  examples run
      :errors    count             :stopped    nil | :budget | :fatal
      :cost      usd               :tokens     {:in :out}
      :dataset   dataset-hash      :elapsed-ms …
      :per-example [{:index :score :outputs|:error :error-class :cost …}]}"
  [program examples metric & {:keys [parallel budget-usd max-transient-retries retry-delay-ms]
                              :or   {parallel 1 max-transient-retries 2 retry-delay-ms 1000}}]
  (let [examples (vec examples)
        t0       (System/currentTimeMillis)
        spent    (atom 0.0)
        stopped  (atom nil)
        run-opts {:max-transient-retries max-transient-retries :retry-delay-ms retry-delay-ms}
        may-start? #(and (nil? @stopped)
                         (or (nil? budget-usd)
                             (< @spent budget-usd)
                             (do (compare-and-set! stopped nil :budget) false)))
        record!  (fn [r]
                   (swap! spent + (:cost r))
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
     :tokens     {:in  (reduce + 0 (map #(get-in % [:tokens :in]) results))
                  :out (reduce + 0 (map #(get-in % [:tokens :out]) results))}
     :dataset    (dataset-hash examples)
     :elapsed-ms (- (System/currentTimeMillis) t0)
     :per-example results}))
