;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.clj-llm.evaluate-test
  (:require [ai.brainyard.clj-llm.core.evaluate :as ev]
            [ai.brainyard.clj-llm.core.llm :as llm]
            [ai.brainyard.clj-llm.core.predictor :as p]
            [ai.brainyard.clj-llm.core.signature :as sig]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(def qa (sig/compile-signature "QA" "Answer." {:question :string} {:answer :string}))

(defn- examples [n]
  (mapv #(ev/example {:question (str "q" %)} {:answer (str "a" %)}) (range n)))

(defn- fake-traced-program
  "A program that records one trace entry costing `cost` and answers with
   (f inputs)."
  [cost f]
  (fn [inputs]
    (when-let [t p/*trace*]
      (swap! t conj {:predictor-id "t/qa" :usage {:cost {:total-cost cost}
                                                  :input-tokens 10 :output-tokens 2}}))
    {:outputs (f inputs)}))

;; ---------------------------------------------------------------------------
;; Splits
;; ---------------------------------------------------------------------------

(deftest split-is-deterministic-and-order-insensitive
  (let [exs (examples 200)
        a (ev/split exs {})
        b (ev/split (reverse exs) {})]
    (is (= 200 (reduce + (map count (vals a)))))
    (is (= (set (:test a)) (set (:test b))) "reordering never moves an example between splits")
    (is (< 80 (count (:train a)) 160) "roughly 60% train")
    (testing "adding examples does not move existing ones"
      (let [c (ev/split (into exs (examples 250)) {})]
        (is (every? (set (:test c)) (:test a)))))
    (is (= (ev/dataset-hash exs) (ev/dataset-hash (reverse exs))))
    (is (not= (ev/dataset-hash exs) (ev/dataset-hash (rest exs))))))

;; ---------------------------------------------------------------------------
;; Metrics
;; ---------------------------------------------------------------------------

(deftest metrics
  (let [ex (ev/example {:q "x"} {:answer " Paris " :n 1})]
    (testing "exact-match normalizes strings and requires labels"
      (is (true? ((ev/exact-match) ex {:outputs {:answer "paris" :n 1}} [])))
      (is (false? ((ev/exact-match) ex {:outputs {:answer "paris" :n 2}} [])))
      (is (true? ((ev/exact-match [:answer]) ex {:outputs {:answer "PARIS" :n 2}} [])))
      (is (false? ((ev/exact-match) (ev/example {:q "x"}) {:outputs {}} []))))
    (testing "schema-valid"
      (is (true? ((ev/schema-valid) ex {:valid? true} [])))
      (is (true? ((ev/schema-valid) ex {} [])))
      (is (false? ((ev/schema-valid) ex {:valid? false} []))))
    (testing "set-f1"
      (let [m (ev/set-f1 :entities #(str/lower-case (:name %)))
            gold (ev/example {} {:entities [{:name "A"} {:name "B"}]})]
        (is (= 1.0 (m gold {:outputs {:entities [{:name "b"} {:name "a"}]}} [])))
        (is (== 0.5 (m gold {:outputs {:entities [{:name "A"} {:name "C"}]}} [])))
        (is (= 0.0 (m gold {:outputs {:entities []}} [])))
        (is (= 1.0 (m (ev/example {} {:entities []}) {:outputs {:entities []}} []))
            "correctly finding nothing is a full score")))
    (testing "combinators"
      (let [yes (constantly true) half (constantly 0.5) no (constantly false)]
        (is (== 0.75 ((ev/weighted [[yes 1] [half 1]]) ex {} [])))
        (is (true? ((ev/all-of yes yes) ex {} [])))
        (is (false? ((ev/all-of yes no) ex {} [])))))))

;; ---------------------------------------------------------------------------
;; evaluate
;; ---------------------------------------------------------------------------

(deftest evaluate-scores-costs-and-hashes
  (let [prog (fake-traced-program 0.01 (fn [{:keys [question]}]
                                         {:answer (if (= "q1" question) "wrong" (str/replace question "q" "a"))}))
        r (ev/evaluate prog (examples 4) (ev/exact-match))]
    (is (== 0.75 (:score r)))
    (is (= 4 (:attempted r)))
    (is (< 0.0399 (:cost r) 0.0401))
    (is (= {:in 40 :out 8} (:tokens r)))
    (is (= [1.0 0.0 1.0 1.0] (mapv :score (:per-example r))))
    (is (nil? (:stopped r)))))

(deftest evaluate-stops-at-budget
  (let [prog (fake-traced-program 0.5 (fn [_] {:answer "x"}))
        r (ev/evaluate prog (examples 10) (ev/exact-match) :budget-usd 1.0)]
    (is (= :budget (:stopped r)))
    (is (= 2 (:attempted r)) "sequential: starts no example once spend reached the cap")))

(deftest evaluate-error-policy
  (testing "malformed output scores 0 and the run continues"
    (let [prog (fn [{:keys [question]}]
                 (if (= "q0" question)
                   (throw (ex-info "Failed to parse JSON" {:raw-text "nope"}))
                   {:outputs {:answer (str/replace question "q" "a")}}))
          r (ev/evaluate prog (examples 3) (ev/exact-match))]
      (is (= 3 (:attempted r)))
      (is (= 1 (:errors r)))
      (is (= :malformed (:error-class (first (:per-example r)))))
      (is (nil? (:stopped r)))))
  (testing "transient failures are retried before scoring"
    (let [calls (atom 0)
          prog (fn [_]
                 (if (< (swap! calls inc) 3)
                   (throw (ex-info "HTTP 503" {:status 503}))
                   {:outputs {:answer "a0"}}))
          r (ev/evaluate prog (examples 1) (ev/exact-match) :retry-delay-ms 1)]
      (is (= 1.0 (:score r)))
      (is (= 3 (:attempts (first (:per-example r)))))))
  (testing "a fatal error stops the run"
    (let [prog (fn [_] (throw (ex-info "HTTP 401 Unauthorized" {:status 401})))
          r (ev/evaluate prog (examples 5) (ev/exact-match))]
      (is (= :fatal (:stopped r)))
      (is (= 1 (:attempted r))))))

(deftest evaluate-parallel-matches-sequential
  (let [prog (fake-traced-program 0.0 (fn [{:keys [question]}] {:answer (str/replace question "q" "a")}))
        seq-r (ev/evaluate prog (examples 12) (ev/exact-match))
        par-r (ev/evaluate prog (examples 12) (ev/exact-match) :parallel 4)]
    (is (= (:score seq-r) (:score par-r) 1.0))
    (is (= (mapv :index (:per-example seq-r)) (mapv :index (:per-example par-r))))))

;; ---------------------------------------------------------------------------
;; Repeats and variance
;; ---------------------------------------------------------------------------

(deftest single-pass-reports-no-variance
  (let [r (ev/evaluate (fake-traced-program 0.0 (fn [_] {:answer "x"})) (examples 3) (ev/exact-match))]
    (is (= 1 (:repeats r) (:completed-repeats r)))
    (is (nil? (:stddev r)))
    (is (nil? (:stderr r)))
    (is (nil? (:ci95 r)) "one pass says nothing about its own noise")))

(deftest deterministic-repeats-have-zero-spread
  (let [prog (fake-traced-program 0.01 (fn [{:keys [question]}]
                                         {:answer (if (= "q1" question) "wrong" (str/replace question "q" "a"))}))
        r (ev/evaluate prog (examples 4) (ev/exact-match) :repeats 3)]
    (is (= 3 (:completed-repeats r)))
    (is (= [0.75 0.75 0.75] (:repeat-scores r)))
    (is (== 0.75 (:score r)))
    (is (== 0.0 (:stddev r) (:stderr r)))
    (is (= [0.75 0.75] (mapv double (:ci95 r))))
    (is (< 0.1199 (:cost r) 0.1201) "cost sums across passes")
    (is (= 12 (:calls r)))
    (is (= [[1.0 1.0 1.0] [0.0 0.0 0.0]] (mapv :scores (take 2 (:per-example r)))))
    (is (every? #(== 0.0 (:spread %)) (:per-example r)))))

(deftest noisy-repeats-report-spread-and-interval
  ;; q0 flips between right and wrong on every call; q1..q3 always right
  (let [flip (atom 0)
        prog (fn [{:keys [question]}]
               {:outputs {:answer (if (and (= "q0" question) (odd? (swap! flip inc)))
                                    "wrong"
                                    (str/replace question "q" "a"))}})
        r (ev/evaluate prog (examples 4) (ev/exact-match) :repeats 4)]
    (is (= [0.75 1.0 0.75 1.0] (:repeat-scores r)))
    (is (== 0.875 (:score r)))
    (is (< 0.144 (:stddev r) 0.145) "sample sd of [0.75 1 0.75 1]")
    (let [[lo hi] (:ci95 r)]
      (is (< lo 0.875 hi))
      (is (< 0.22 (- hi 0.875) 0.24) "t(3)=3.182 × se 0.0722"))
    (is (= 1.0 (:spread (first (:per-example r)))) "the flipping example is visible per-example")
    (is (= [0.0 1.0 0.0 1.0] (:scores (first (:per-example r)))))))

(deftest budget-cut-repeats-score-only-complete-passes
  (let [prog (fake-traced-program 0.5 (fn [_] {:answer "a0"}))
        r (ev/evaluate prog (examples 2) (ev/exact-match) :repeats 3 :budget-usd 2.5)]
    ;; pass 1 costs 1.0, pass 2 costs 1.0, pass 3 starts at 2.0 and is cut after one example
    (is (= :budget (:stopped r)))
    (is (= 2 (:completed-repeats r)))
    (is (== 0.5 (:score r)) "the partial third pass is excluded from the mean")))

(deftest compare-scores-tests-against-noise
  (testing "no variance estimate: plain comparison, flagged untested"
    (let [c (ev/compare-scores {:score 0.6} {:score 0.5})]
      (is (true? (:better? c)))
      (is (false? (:tested? c)))
      (is (== 0.0 (:margin c)))
      (is (< 0.0999 (:diff c) 0.1001))))
  (testing "a gap inside the noise is not a win"
    (let [c (ev/compare-scores {:score 0.605 :stderr 0.03 :completed-repeats 3}
                               {:score 0.575 :stderr 0.03 :completed-repeats 3})]
      (is (:tested? c))
      (is (false? (:better? c)))
      (is (> (:margin c) (:diff c)))))
  (testing "a gap well past the noise is a win"
    (is (:better? (ev/compare-scores {:score 0.9 :stderr 0.01 :completed-repeats 5}
                                     {:score 0.5 :stderr 0.01 :completed-repeats 5}))))
  (testing "zero noise on both sides: any positive gap is real"
    (is (:better? (ev/compare-scores {:score 0.51 :stderr 0.0 :completed-repeats 3}
                                     {:score 0.50 :stderr 0.0 :completed-repeats 3}))))
  (testing "t critical values"
    (is (= 4.303 (ev/t-critical 2)))
    (is (= 4.303 (ev/t-critical 2.9)) "fractional df rounds down, the conservative way")
    (is (= 1.96 (ev/t-critical 100)))))

(deftest parallel-workers-see-with-params
  (let [pred (p/predictor {:id "t/qa" :signature qa})
        seen (atom #{})]
    (with-redefs [llm/chat-completion (fn [_ messages & _]
                                        (swap! seen conj (-> messages first :content))
                                        {})
                  llm/extract-content (constantly (json/write-str {:answer "a"}))]
      (p/with-params {"t/qa" {:instructions "CANDIDATE-7"}}
        (ev/evaluate (ev/predictor-program pred :lm-config {:provider :openai :model "m"})
                     (examples 4) (ev/schema-valid) :parallel 3)))
    (is (seq @seen))
    (is (every? #(str/includes? % "CANDIDATE-7") @seen)
        "a candidate's params reach every worker thread")))
