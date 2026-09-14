;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.clj-llm.optimize-test
  "Optimizers against a fake LM whose accuracy depends on the demos it sees —
   so 'the optimizer picked the better candidate' is testable offline."
  (:require [ai.brainyard.clj-llm.core.evaluate :as ev]
            [ai.brainyard.clj-llm.core.llm :as llm]
            [ai.brainyard.clj-llm.core.optimize :as opt]
            [ai.brainyard.clj-llm.core.predictor :as p]
            [ai.brainyard.clj-llm.core.signature :as sig]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(def upper (sig/compile-signature "Upper" "Uppercase the word." {:word :string} {:out :string}))
(def pred (p/predictor {:id "t/upper" :signature upper}))

(def teacher-lm {:provider :openai :model "teacher" :message-format :openai})
(def student-lm {:provider :openai :model "student" :message-format :openai})

(defn- trainset [words]
  (mapv #(ev/example {:word %} {:out (str/upper-case %)}) words))

(defn- fake-llm
  "Teacher always right. Student right only when its system prompt carries at
   least `need` demos. Each call costs 0.01."
  [need calls]
  {:chat (fn [lm messages & _]
           (swap! calls conj (:model lm))
           {::llm/usage {:cost {:total-cost 0.01}}
            :lm (:model lm)
            :sys (-> messages first :content)
            :word (second (re-find #"word: (\S+)" (-> messages second :content)))})
   :extract (fn [{:keys [lm sys word]} _]
              (let [demos (count (re-seq #"Example \d+" sys))
                    right? (or (= lm "teacher") (>= demos need))]
                (json/write-str {:out (if right? (str/upper-case word) "??")})))})

(defmacro with-fake-llm [need calls & body]
  `(let [f# (fake-llm ~need ~calls)]
     (with-redefs [llm/chat-completion (:chat f#)
                   llm/extract-content (:extract f#)]
       ~@body)))

(def teacher (ev/predictor-program pred :lm-config teacher-lm))
(def student (ev/predictor-program pred :lm-config student-lm))
(def metric (ev/exact-match))

(deftest labeled-few-shot-needs-no-lm
  (let [{:keys [params report]} (opt/labeled-few-shot "t/upper" (conj (trainset ["a" "b" "c"]) (ev/example {:word "x"})) {:k 2})]
    (is (= 2 (count (get-in params ["t/upper" :demos]))))
    (is (every? #(= :labeled (get-in % [:source :kind])) (get-in params ["t/upper" :demos])))
    (is (= 3 (:available report)) "unlabelled examples are skipped")
    (is (:valid? (p/validate-params (get params "t/upper"))))))

(deftest bootstrap-keeps-only-passing-traces
  (let [calls (atom [])
        wrong-teacher (fn [inputs]
                        ;; teacher is wrong on "b": that trace must not become a demo
                        (let [r (teacher inputs)]
                          (if (= "b" (:word inputs)) (assoc-in r [:outputs :out] "nope") r)))]
    (with-fake-llm 99 calls
      (let [{:keys [params report]} (opt/bootstrap-few-shot wrong-teacher (trainset ["a" "b" "c" "d"]) metric
                                                            {:max-bootstrapped 2})]
        (is (= [{:word "a"} {:word "c"}] (mapv :inputs (get-in params ["t/upper" :demos]))))
        (is (= {:out "A"} (:outputs (first (get-in params ["t/upper" :demos])))))
        (is (= :full (:stopped report)) "stops once the predictor has enough demos")
        (is (= 3 (:attempted report)))
        (is (:valid? (p/validate-params (get params "t/upper"))))))))

(deftest bootstrap-tops-up-with-labels
  (with-fake-llm 99 (atom [])
    (let [always-wrong (fn [inputs] (assoc-in (teacher inputs) [:outputs :out] "nope"))
          {:keys [params]} (opt/bootstrap-few-shot always-wrong (trainset ["a" "b"]) metric
                                                   {:max-bootstrapped 2 :max-labeled 2 :predictor-id "t/upper"})]
      (is (= [:labeled :labeled] (mapv #(get-in % [:source :kind]) (get-in params ["t/upper" :demos])))))))

(deftest random-search-picks-the-candidate-that-helps-the-student
  (let [calls (atom [])]
    (with-fake-llm 2 calls
      (let [{:keys [params report]}
            (opt/bootstrap-random-search teacher student
                                         (trainset ["a" "b" "c" "d" "e" "f"])
                                         (trainset ["p" "q" "r"])
                                         metric
                                         {:trials 3 :max-bootstrapped 3 :predictor-id "t/upper"})
            board (into {} (map (juxt :candidate :score)) (:leaderboard report))]
        (is (= 0.0 (:zero-shot board)) "the student fails with no demos")
        (is (= 1.0 (:bootstrap board)))
        (is (= 1.0 (:best-score report)))
        (is (>= (count (get-in params ["t/upper" :demos])) 2))
        (testing "ties keep the earlier candidate, so zero-shot must be beaten strictly"
          (is (not= :zero-shot (:best report))))
        (is (every? #{"teacher" "student"} @calls))
        (is (= 6 (count (filter #{"teacher"} @calls)))
            "the teacher runs once over the trainset (a pool), not once per trial")))))

(deftest random-search-prefers-zero-shot-when-demos-do-not-help
  (with-fake-llm 0 (atom [])
    (let [{:keys [params report]}
          (opt/bootstrap-random-search teacher student (trainset ["a" "b"]) (trainset ["p"]) metric
                                       {:trials 2 :max-bootstrapped 2})]
      (is (= :zero-shot (:best report)))
      (is (= {"t/upper" {}} params) "the winning proposal adds no prompt tokens"))))

(deftest budget-bounds-the-whole-search
  (with-fake-llm 2 (atom [])
    (let [{:keys [report]} (opt/bootstrap-random-search teacher student
                                                        (trainset ["a" "b" "c" "d"]) (trainset ["p" "q"]) metric
                                                        {:trials 5 :max-bootstrapped 2 :budget-usd 0.05})]
      (is (<= (:cost report) 0.0601) "sequential: at most one call past the cap")
      (is (= :budget (:stopped report))))))
