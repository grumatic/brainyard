;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.bt-task-spike-test
  "SPIKE §16 continued: the five AGENT tick overrides as effects.

   `core.nodes-task` alone never runs in production — `agent.core.bt` overrides
   five of the six node types, so these are the ticks the real tree executes.

   Results are compared, but so are TRACES. The overrides exist for their
   tracing, depth threading, hooks and st-memory writes; a translation that
   returned the right status while emitting different trace lines would have
   broken the TUI and passed a result-only test."
  (:require [clojure.test :refer [deftest is testing]]
            [ai.brainyard.agent.core.bt :as abt]
            [ai.brainyard.agent.core.protocol :as proto]
            [ai.brainyard.behavior-tree.interface :as bt]
            [ai.brainyard.behavior-tree.interface.protocol :as p]
            [ai.brainyard.effect.interface :as fx]
            [missionary.core :as m]))

;; A record, not a reify: the overrides read `(:agent-id agent)` by keyword as
;; well as calling protocol methods by interop.
(defrecord MockAgent [agent-id !traces !paused? !cancelled? !st]
  proto/IAgentBTIntegration
  (update-session-data [_ data]
    (when-let [t (:trace data)] (swap! !traces conj (:content t)))
    nil)
  (check-run-cancelled? [_] @!cancelled?)
  (check-run-paused? [_] @!paused?)
  (await-resume [_] (if @!cancelled? :cancelled :resumed))
  (apply-resume-note! [_] nil)
  (create-action-promise [_ _] (promise))
  (get-action-permission [_ _] nil)
  (set-action-permission [_ _ _] nil)
  (get-bt-st-memory [_] !st))

(defn- mock []
  (->MockAgent :spike/mock (atom []) (atom false) (atom false) (atom {})))

(defn- run-both
  "Build the tree twice and run each engine with its own mock agent, so traces
   are attributable. Returns {:sync [result traces] :task [result traces]}."
  [config]
  (let [a1 (mock)
        r1 (bt/run (bt/build config {:agent a1}))
        a2 (mock)
        rr (fx/run!! (bt/run-task (bt/build config {:agent a2})) 15000)
        r2 (if (contains? rr :ok) (:ok rr) {:threw (ex-message (:err rr))})]
    {:sync [r1 @(:!traces a1)] :task [r2 @(:!traces a2)]}))

(defn- act [id v] [:action {:id id} (fn [_] v)])

;; ============================================================================
;; Results AND traces agree, for every override
;; ============================================================================

(deftest sequence-and-fallback-overrides-agree
  (doseq [[label cfg]
          [["sequence success" [:sequence {:id "S"} (act "a" :success) (act "b" :success)]]
           ["sequence failure" [:sequence {:id "S"} (act "a" :success) (act "b" :failure) (act "c" :success)]]
           ["fallback success" [:fallback {:id "F"} (act "a" :failure) (act "b" :success)]]
           ["fallback failure" [:fallback {:id "F"} (act "a" :failure) (act "b" :failure)]]
           ["nested"           [:sequence {:id "S"}
                                [:fallback {:id "F"} (act "a" :failure) (act "b" :success)]
                                (act "c" :success)]]]]
    (let [{:keys [sync task]} (run-both cfg)]
      (is (= (first sync) (first task)) (str label " — result"))
      (is (= (second sync) (second task)) (str label " — trace")))))

(deftest condition-override-agrees
  (doseq [[label cfg]
          [["true"  [:sequence {:id "S"} [:condition {:id "C"} (fn [_] true)]]]
           ["false" [:sequence {:id "S"} [:condition {:id "C"} (fn [_] false)]]]]]
    (let [{:keys [sync task]} (run-both cfg)]
      (is (= (first sync) (first task)) (str label " — result"))
      (is (= (second sync) (second task)) (str label " — trace")))))

(deftest action-override-agrees
  (let [{:keys [sync task]} (run-both [:sequence {:id "S"} (act "a" :success) (act "b" :failure)])]
    (is (= (first sync) (first task)))
    (is (= (second sync) (second task)))
    (testing "the action's started/finished trace pair survives"
      (is (some #(re-find #"a action \*\*started\*\*" %) (second task)))
      (is (some #(re-find #"a action \*\*:success\*\*" %) (second task))))))

(deftest repeat-override-agrees
  (doseq [[label cfg]
          [["succeeds first pass"
            [:repeat {:id "R" :max-n 3 :emit-iteration-events? false} (act "a" :success)]]
           ["child failure stops"
            [:repeat {:id "R" :max-n 3 :emit-iteration-events? false} (act "a" :failure)]]
           ["exhausts"
            [:repeat {:id "R" :max-n 3 :emit-iteration-events? false
                      :condition-fn (fn [_] false)}
             (act "a" :success)]]]]
    (let [{:keys [sync task]} (run-both cfg)]
      (is (= (first sync) (first task)) (str label " — result"))
      (is (= (second sync) (second task)) (str label " — trace")))))

(deftest depth-threading-agrees
  (testing "depth reaches the leaves identically — it drives TUI indentation"
    (let [{:keys [sync task]}
          (run-both [:sequence {:id "S1"} [:sequence {:id "S2"} [:sequence {:id "S3"} (act "leaf" :success)]]])]
      (is (= (first sync) (first task)))
      (is (= (second sync) (second task))))))

(deftest st-memory-last-failure-agrees
  (testing "a failing leaf records :last-failure in st-memory under both engines"
    (doseq [[label runner] [["sync" #(bt/run %)] ["task" #(:ok (fx/run!! (bt/run-task %) 15000))]]]
      (let [built (bt/build [:sequence {:id "S"} (act "boom" :failure)] {:agent (mock)})]
        (runner built)
        (is (re-find #"boom action" (:last-failure @(get-in built [:context :st-memory]))) label)))))

;; ============================================================================
;; The payoff
;; ============================================================================

(deftest cancellation-without-the-cooperative-flag
  (testing "cancelling the tree's Task stops it, with :cancelled? never set

           This is what §14's four mechanisms were for. The task engine's
           checkpoint (`check-pause!`) does not consult `check-run-cancelled?`
           at all — cancellation is structural."
    (let [a (mock)
          reached (atom [])
          tree [:sequence {:id "S"}
                [:action {:id "first"} (fn [_] (swap! reached conj :first) :success)]
                [:action {:id "slow"}  (fn [_] (m/sp (m/? (m/sleep 5000)) :success))]
                [:action {:id "after"} (fn [_] (swap! reached conj :after) :success)]]
          !out (promise)
          cancel (fx/run (bt/run-task (bt/build tree {:agent a}))
                         #(deliver !out {:ok %}) #(deliver !out {:err %}))]
      (Thread/sleep 250)
      (cancel)
      (is (not= :TIMEOUT (deref !out 3000 :TIMEOUT)) "cancel must settle the run")
      (is (= [:first] @reached) "the node after the cancelled one must not run")
      (is (false? @(:!cancelled? a))
          "and it stopped WITHOUT anyone setting the cooperative flag"))))

(deftest pause-still-parks
  (testing "pause is NOT cancellation and does not reduce the same way — it is
            a wait for a human, so it stays a park and still works"
    (let [a (mock)
          _ (reset! (:!paused? a) true)
          r (fx/run!! (bt/run-task (bt/build [:sequence {:id "S"} (act "a" :success)] {:agent a})) 5000)]
      (is (= {:ok :success} r))
      (is (some #(re-find #"paused" %) @(:!traces a)))
      (is (some #(re-find #"resumed" %) @(:!traces a))))))

(deftest leaf-wrap-carries-a-binding-past-a-park
  (testing "§16 Q2, now through the AGENT overrides rather than the base engine"
    (let [seen (atom nil)
          tree [:sequence {:id "S"}
                [:action {:id "park"} (fn [_] (m/sp (m/? (m/sleep 60)) :success))]
                [:action {:id "read"} (fn [_] (reset! seen proto/*call-depth*) :success)]]]
      (fx/run!! (bt/run-task
                 (bt/build tree {:agent (mock)
                                 :leaf-wrap (fn [t] (binding [proto/*call-depth* 42] (t)))}))
                15000)
      (is (= 42 @seen) "the leaf-wrap must re-establish the binding after the park"))))
