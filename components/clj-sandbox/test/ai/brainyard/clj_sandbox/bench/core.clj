;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.clj-sandbox.bench.core
  "RLM benchmark runner. Runs benchmarks against real LLM APIs.

   Usage from REPL:
     (require '[ai.brainyard.clj-sandbox.bench.core :as bench])
     (require '[ai.brainyard.clj-sandbox.bench.sniah :as sniah])
     (require '[ai.brainyard.clj-sandbox.bench.oolong :as oolong])
     (require '[ai.brainyard.clj-sandbox.bench.retrieval :as retrieval])

     ;; Quick smoke test: 2 examples at smallest size
     (def r (bench/run-benchmark sniah/benchmark-def
              :context-sizes [8000] :examples-per-size 2))

     ;; Full S-NIAH run
     (def r (bench/run-benchmark sniah/benchmark-def))

     ;; Run all benchmarks
     (def results (bench/run-all))

     ;; With specific model
     (def results (bench/run-all :lm-config my-lm))

     ;; Save & print
     (bench/print-summary r)
     (bench/save-results r)

     ;; Compare two runs
     (bench/compare-runs r1 r2)

   Results are saved to benchmark-results/"
  (:require [ai.brainyard.clj-sandbox.interface :as clj-sandbox]
            [ai.brainyard.clj-sandbox.bench.scoring :as scoring]
            [ai.brainyard.clj-sandbox.bench.sniah :as sniah]
            [ai.brainyard.clj-sandbox.bench.oolong :as oolong]
            [ai.brainyard.clj-sandbox.bench.retrieval :as retrieval]
            [ai.brainyard.clj-sandbox.bench.rlm :as rlm]
            [clojure.java.io :as io]
            [clojure.pprint :as pp]
            [clojure.string :as str]))

;; ============================================================================
;; Run Single Example
;; ============================================================================

(defn run-one-example
  "Run a single benchmark example through clj-sandbox/completion.
   Returns the example map (without :context) merged with result data."
  [example {:keys [lm-config max-iterations max-depth score-fn on-example]}]
  (let [usage-tracker (atom {:totals {:input-tokens 0 :output-tokens 0
                                      :total-tokens 0 :total-cost 0.0
                                      :cache-read-tokens 0 :cache-write-tokens 0
                                      :call-count 0}
                             :by-model {} :history [] :history-cap 100})
        start (System/currentTimeMillis)
        result (try
                 (clj-sandbox/completion (:query example) (:context example)
                                         :lm-config lm-config
                                         :max-iterations (or max-iterations
                                                             (:max-iterations example) 20)
                                         :max-depth (or max-depth (:max-depth example) 1)
                                         :usage-tracker usage-tracker)
                 (catch Exception e
                   {:answer nil
                    :iterations []
                    :terminated-by :error
                    :total-iterations 0
                    :error (.getMessage e)}))
        elapsed (- (System/currentTimeMillis) start)
        score (score-fn example result)
        result-map (merge (dissoc example :context) ;; strip large context
                          {:answer (:answer result)
                           :iterations-count (:total-iterations result)
                           :terminated-by (:terminated-by result)
                           :elapsed-ms elapsed
                           :usage (:totals @usage-tracker)
                           :score-result score})]
    (when on-example
      (on-example result-map))
    result-map))

;; ============================================================================
;; Run Benchmark Suite
;; ============================================================================

(defn run-benchmark
  "Run a full benchmark suite.

   benchmark-def: {:name :generate-fn :score-fn :default-config}
   opts: override keys from default-config, plus :lm-config, :on-example

   Returns {:benchmark str :config map :results [...] :aggregate {...}
            :total-cost double :total-elapsed-ms long :timestamp inst}"
  [benchmark-def & {:as opts}]
  (let [config (merge (:default-config benchmark-def) opts)
        examples ((:generate-fn benchmark-def) config)
        n (count examples)
        _ (println (str "=== " (:name benchmark-def) " ==="))
        _ (println (str "  " n " examples, max-iterations="
                        (:max-iterations config) ", max-depth=" (:max-depth config)))
        start (System/currentTimeMillis)
        results (vec
                 (map-indexed
                  (fn [i ex]
                    (printf "  [%d/%d] %s ... " (inc i) n (:id ex))
                    (flush)
                    (let [r (run-one-example ex
                                             {:lm-config (:lm-config config)
                                              :max-iterations (:max-iterations config)
                                              :max-depth (:max-depth config)
                                              :score-fn (:score-fn benchmark-def)})]
                      (printf "score=%.2f iters=%d %dms%n"
                              (double (get-in r [:score-result :score]))
                              (:iterations-count r)
                              (:elapsed-ms r))
                      (flush)
                      r))
                  examples))
        elapsed (- (System/currentTimeMillis) start)
        scores (map :score-result results)
        agg (scoring/aggregate-scores scores)
        total-cost (reduce + 0.0 (keep #(get-in % [:usage :total-cost]) results))]
    (println)
    {:benchmark (:name benchmark-def)
     :description (:description benchmark-def)
     :config (dissoc config :lm-config :on-example)
     :results results
     :aggregate agg
     :total-cost total-cost
     :total-elapsed-ms elapsed
     :timestamp (java.util.Date.)}))

;; ============================================================================
;; Run All
;; ============================================================================

(def all-benchmarks
  [sniah/benchmark-def
   oolong/benchmark-def
   retrieval/benchmark-def
   rlm/multi-file-summary-def
   rlm/pairwise-dup-def])

(defn run-all
  "Run all registered benchmarks. Returns vector of results."
  [& {:as opts}]
  (mapv #(run-benchmark % opts) all-benchmarks))

;; ============================================================================
;; Results I/O
;; ============================================================================

(defn save-results
  "Save benchmark results to EDN file.
   Path: benchmark-results/{name}-{timestamp}.edn"
  [results & {:keys [dir] :or {dir "benchmark-results"}}]
  (let [ts (-> (java.text.SimpleDateFormat. "yyyy-MM-dd'T'HHmmss")
               (.format (:timestamp results)))
        filename (str (str/lower-case (:benchmark results)) "-" ts ".edn")
        path (io/file dir filename)]
    (.mkdirs (.getParentFile path))
    (spit path (with-out-str (pp/pprint results)))
    (println (str "Saved to " (.getPath path)))
    (.getPath path)))

(defn load-results
  "Load previously saved benchmark results from an EDN file."
  [path]
  (read-string (slurp path)))

;; ============================================================================
;; Reporting
;; ============================================================================

(defn print-summary
  "Print a human-readable summary of benchmark results."
  [results]
  (let [{:keys [benchmark aggregate total-cost total-elapsed-ms]} results
        {:keys [mean accuracy correct-count]} aggregate
        n-examples (:count aggregate)]
    (println (str "┌─ " benchmark " ─────────────────────────────────────"))
    (println (str "│ Examples:   " n-examples))
    (println (str "│ Accuracy:   " (format "%.1f%%" (* 100.0 accuracy))
                  " (" correct-count "/" n-examples ")"))
    (println (str "│ Mean Score: " (format "%.3f" (double mean))))
    (println (str "│ Mean Iters: " (format "%.1f"
                                           (double (/ (reduce + 0 (map :iterations-count
                                                                       (:results results)))
                                                      (max 1 n-examples))))))
    (println (str "│ Total Cost: $" (format "%.4f" total-cost)))
    (println (str "│ Total Time: " (format "%.1fs" (/ total-elapsed-ms 1000.0))))
    (println "└──────────────────────────────────────────────────")
    ;; Per-context-size breakdown if available
    (let [groups (group-by #(or (:context-tokens %) (:context-chars %)) (:results results))]
      (when (> (count groups) 1)
        (doseq [[ctx-size group-results] (sort-by (fn [[k _]] (or k 0)) groups)]
          (let [group-scores (map :score-result group-results)
                group-agg (scoring/aggregate-scores group-scores)]
            (println (str "  " (or ctx-size "?") " tokens: "
                          (format "%.1f%%" (* 100.0 (:accuracy group-agg)))
                          " accuracy, "
                          (format "%.1f" (double (/ (reduce + 0 (map :iterations-count group-results))
                                                    (max 1 (count group-results)))))
                          " avg iters"))))
        (println)))))

(defn compare-runs
  "Compare two result sets side-by-side."
  [results-a results-b]
  (println (str "┌─ Comparison ──────────────────────────────────────"))
  (println (str "│                    " (format "%-20s" (:benchmark results-a))
                (:benchmark results-b)))
  (println (str "│ Accuracy:          "
                (format "%-20s" (format "%.1f%%" (* 100.0 (get-in results-a [:aggregate :accuracy]))))
                (format "%.1f%%" (* 100.0 (get-in results-b [:aggregate :accuracy])))))
  (println (str "│ Mean Score:        "
                (format "%-20s" (format "%.3f" (double (get-in results-a [:aggregate :mean]))))
                (format "%.3f" (double (get-in results-b [:aggregate :mean])))))
  (let [avg-iters-a (/ (reduce + 0 (map :iterations-count (:results results-a)))
                       (max 1 (count (:results results-a))))
        avg-iters-b (/ (reduce + 0 (map :iterations-count (:results results-b)))
                       (max 1 (count (:results results-b))))]
    (println (str "│ Mean Iters:        "
                  (format "%-20s" (format "%.1f" (double avg-iters-a)))
                  (format "%.1f" (double avg-iters-b)))))
  (println (str "│ Total Cost:        "
                (format "%-20s" (format "$%.4f" (:total-cost results-a)))
                (format "$%.4f" (:total-cost results-b))))
  (println (str "│ Total Time:        "
                (format "%-20s" (format "%.1fs" (/ (:total-elapsed-ms results-a) 1000.0)))
                (format "%.1fs" (/ (:total-elapsed-ms results-b) 1000.0))))
  (println "└──────────────────────────────────────────────────"))
