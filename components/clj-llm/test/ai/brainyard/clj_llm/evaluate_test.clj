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
