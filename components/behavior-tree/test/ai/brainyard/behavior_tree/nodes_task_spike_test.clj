;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.behavior-tree.nodes-task-spike-test
  "SPIKE (docs/design/functional-effect-system.md §15): does the BT translate
   to effects without changing meaning?

   The core of this file is `equivalent`, which builds ONE tree and runs it
   through both engines. A translation that compiles proves nothing; one that
   agrees with the synchronous engine on every shape proves the semantics
   survived. Everything else here answers a question §15 could only reason
   about."
  (:require [clojure.test :refer [deftest is testing]]
            [ai.brainyard.behavior-tree.interface :as bt]
            [ai.brainyard.effect.interface :as fx]
            [missionary.core :as m]))

(defn- run-both
  "Build `config` twice (each build makes its own st-memory atom, so the two
   runs cannot contaminate each other) and return [sync-result task-result]."
  [config context]
  [(bt/run (bt/build config context))
   (let [r (fx/run!! (bt/run-task (bt/build config context)) 10000)]
     (if (contains? r :ok) (:ok r) {:threw (ex-message (:err r))}))])

(defn- equivalent
  "Assert both engines agree, and return the shared result."
  [label config context]
  (let [[sync-r task-r] (run-both config context)]
    (is (= sync-r task-r) (str label " — engines disagree"))
    sync-r))

(defn- act [v] [:action (fn [_] v)])

;; ============================================================================
;; Every node type agrees
;; ============================================================================

(deftest sequence-agrees
  (is (= :success (equivalent "all success" [:sequence (act :success) (act :success)] {})))
  (is (= :failure (equivalent "short-circuits on failure"
                              [:sequence (act :success) (act :failure) (act :success)] {})))
  (is (= :success (equivalent "empty" [:sequence] {}))))

(deftest sequence-short-circuits-identically
  (testing "the failing child stops evaluation in BOTH engines — a translation
            that ran every child would still return :failure and look correct"
    (doseq [[label runner] [["sync" #(bt/run (bt/build % {}))]
                            ["task" #(:ok (fx/run!! (bt/run-task (bt/build % {})) 10000))]]]
      (let [seen (atom [])
            tree [:sequence
                  [:action (fn [_] (swap! seen conj :a) :success)]
                  [:action (fn [_] (swap! seen conj :b) :failure)]
                  [:action (fn [_] (swap! seen conj :c) :success)]]]
        (is (= :failure (runner tree)))
        (is (= [:a :b] @seen) (str label " must not evaluate past the failure"))))))

(deftest fallback-agrees
  (is (= :success (equivalent "first success wins"
                              [:fallback (act :failure) (act :success) (act :failure)] {})))
  (is (= :failure (equivalent "all fail" [:fallback (act :failure) (act :failure)] {})))
  (is (= :failure (equivalent "empty" [:fallback] {}))))

(deftest condition-agrees
  (is (= :success (equivalent "true" [:condition (fn [_] true)] {})))
  (is (= :failure (equivalent "false" [:condition (fn [_] false)] {})))
  (is (= :success (equivalent "reads st-memory"
                              [:condition (fn [{:keys [st-memory]}] (:ok @st-memory))]
                              {:st-memory {:ok true}}))))

(deftest parallel-agrees
  (is (= :success (equivalent "all succeed" [:parallel (act :success) (act :success)] {})))
  (is (= :failure (equivalent "all fail" [:parallel (act :failure) (act :failure)] {})))
  (is (= :success (equivalent "threshold met"
                              [:parallel {:success-threshold 1}
                               (act :success) (act :failure)] {}))))

(deftest nested-trees-agree
  (is (= :success
         (equivalent "sequence of fallbacks of conditions"
                     [:sequence
                      [:fallback (act :failure) [:condition (fn [_] true)]]
                      [:sequence (act :success)
                       [:fallback (act :failure) (act :success)]]]
                     {})))
  (is (= :failure
         (equivalent "deep failure propagates"
                     [:sequence
                      [:sequence [:sequence (act :success)]]
                      [:sequence [:sequence (act :failure)]]]
                     {}))))

(deftest st-memory-mutation-agrees
  (testing "leaves that write st-memory behave the same under both engines"
    (doseq [[label runner] [["sync" #(bt/run %)] ["task" #(:ok (fx/run!! (bt/run-task %) 10000))]]]
      (let [built (bt/build [:sequence
                             [:action (fn [{:keys [st-memory]}] (swap! st-memory assoc :n 1) :success)]
                             [:action (fn [{:keys [st-memory]}] (swap! st-memory update :n inc) :success)]]
                            {})]
        (is (= :success (runner built)))
        (is (= 2 (:n @(get-in built [:context :st-memory]))) label)))))

;; ============================================================================
;; QUESTION 1 — does `:running` survive the translation?
;; ============================================================================

(deftest running-survives
  (testing ":running is a STATUS, and must not be conflated with 'the Task has
            not settled yet'. A leaf returning :running settles its Task
            immediately, with the value :running."
    (is (= :running (equivalent "bare running leaf" (act :running) {})))
    (is (= :running (equivalent "sequence propagates running"
                                [:sequence (act :success) (act :running) (act :success)] {})))
    (is (= :running (equivalent "fallback propagates running"
                                [:fallback (act :failure) (act :running) (act :success)] {})))
    (is (= :running (equivalent "nested"
                                [:sequence [:fallback (act :failure) (act :running)]] {}))))

  (testing "and it settles promptly — :running does not mean 'pending'"
    (let [t0 (System/currentTimeMillis)
          r  (fx/run!! (bt/run-task (bt/build [:sequence (act :success) (act :running)] {})) 5000)]
      (is (= {:ok :running} r))
      (is (< (- (System/currentTimeMillis) t0) 1000)
          ":running must complete the Task, not leave it unsettled"))))

;; ============================================================================
;; QUESTION 2 — does Q4 reduce to one binding site?
;; ============================================================================

(def ^:dynamic *who* :nobody)

(defn- parking-leaf
  "A leaf that returns a Task which genuinely parks. This is what makes the
   test meaningful: without a real suspension the binding would survive by
   accident (§8 Q4 — parking on an already-settled task resumes synchronously)."
  []
  [:action (fn [_] (m/sp (m/? (m/sleep 60)) :success))])

(deftest q4-binding-across-a-park
  (let [seen (atom nil)
        tree [:sequence
              (parking-leaf)
              [:action (fn [_] (reset! seen *who*) :success)]]]

    (testing "CONTROL: binding around the run is lost at the park

             This is the hazard, reproduced. The binding is established outside
             the tree, the first leaf parks, and by the time the reading leaf
             runs it is on another thread with a root frame."
      (reset! seen nil)
      (binding [*who* :agent-7]
        (fx/run!! (bt/run-task (bt/build tree {})) 10000))
      (is (= :nobody @seen)
          "if this ever reads :agent-7, the park stopped being a real park"))

    (testing "FIX: :leaf-wrap re-establishes it at the leaf — ONE site

             The engine cannot bind *current-agent* itself (behavior-tree sits
             below agent), so the context carries a wrapper. Every leaf goes
             through it, after any park, so 123 read sites need no audit."
      (reset! seen nil)
      (let [ctx {:leaf-wrap (fn [thunk] (binding [*who* :agent-7] (thunk)))}]
        (fx/run!! (bt/run-task (bt/build tree ctx)) 10000))
      (is (= :agent-7 @seen)))))

;; ============================================================================
;; What the conversion BUYS
;; ============================================================================

(deftest cancellation-is-structural
  (testing "cancelling the tree's Task stops it mid-run — the sync engine has
            no equivalent, which is the entire point of §14"
    (let [reached (atom [])
          tree [:sequence
                [:action (fn [_] (swap! reached conj :first) :success)]
                [:action (fn [_] (m/sp (m/? (m/sleep 5000)) :success))]
                [:action (fn [_] (swap! reached conj :after) :success)]]
          !out (promise)
          cancel (fx/run (bt/run-task (bt/build tree {}))
                         #(deliver !out {:ok %}) #(deliver !out {:err %}))]
      (Thread/sleep 200)
      (cancel)
      (let [r (deref !out 3000 :TIMEOUT)]
        (is (not= :TIMEOUT r) "cancelling must settle the run, not hang it")
        (is (= [:first] @reached) "the node after the cancelled one must not run")))))

(deftest parallel-propagates-errors-and-cancels-siblings
  (testing "a THROWING child aborts the fan-out

           The sync :parallel derefs every future in order, so a throwing child
           surfaces only after its siblings have run to completion. m/join
           cancels them. Note this changes nothing about :failure, which is a
           value rather than an error — see the sibling assertion below."
    (let [finished (atom 0)
          tree [:parallel
                [:action (fn [_] (m/sp (m/? (m/sleep 30)) (throw (ex-info "boom" {}))))]
                [:action (fn [_] (m/sp (m/? (m/sleep 3000)) (swap! finished inc) :success))]]
          r (fx/run!! (bt/run-task (bt/build tree {})) 5000)]
      (is (= "boom" (ex-message (:err r))))
      (Thread/sleep 300)
      (is (zero? @finished) "the slow sibling must have been cancelled")))

  (testing ":failure is still a value, so it does NOT cancel siblings"
    (let [both (atom 0)
          tree [:parallel
                (act :failure)
                [:action (fn [_] (m/sp (m/? (m/sleep 50)) (swap! both inc) :success))]]
          r (fx/run!! (bt/run-task (bt/build tree {})) 5000)]
      (is (= 1 @both) "a :failure sibling must still be allowed to finish")
      (is (contains? #{:success :failure :running} (:ok r))))))

;; ============================================================================
;; Migration shape
;; ============================================================================

(deftest sync-and-task-leaves-coexist
  (testing "a leaf may return a status keyword OR a Task, so leaves convert one
            at a time rather than in a flag day"
    (is (= :success
           (:ok (fx/run!! (bt/run-task
                           (bt/build [:sequence
                                      (act :success)                              ; sync leaf
                                      [:action (fn [_] (m/sp :success))]          ; task leaf
                                      [:condition (fn [_] true)]]                 ; sync condition
                                     {}))
                          10000))))))
