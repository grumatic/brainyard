;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.fsm-test
  "Tests for the user-defined state machine engine (Phase 1 of
   docs/design/state-machine-design.md): definition store + normalization,
   declarative guards, :assign / action normalization, and step! (transitions,
   context updates, lifecycle events, persistence, guard-blocking)."
  (:require [clojure.test :refer [deftest testing is are use-fixtures]]
            [ai.brainyard.agent.common.fsm :as fsm]
            [ai.brainyard.agent.core.hooks :as hooks]
            [ai.brainyard.agent.core.config :as config]
            [clojure.java.io :as io]))

(def ^:dynamic *pdir* nil)
(def ^:private ag {:!state (atom {:status :idle})})

(defn fixture [f]
  (let [dir (io/file (System/getProperty "java.io.tmpdir")
                     (str "fsm-test-" (System/currentTimeMillis) "-" (rand-int 100000)))]
    (.mkdirs dir)
    (binding [*pdir* (.getPath dir)]
      (try (f)
           (finally
             (fsm/reset-state!)
             (hooks/reset-hooks!)
             (doseq [^java.io.File x (reverse (file-seq dir))] (.delete x)))))))

(use-fixtures :each fixture)

(def ^:private assoc-fn @#'fsm/apply-assign)
(def ^:private norm      @#'fsm/normalize-action)

;; ============================================================================
;; Store + normalization
;; ============================================================================

(deftest store-and-normalize
  (fsm/write-machine! *pdir*
                      {:id "gate" :initial "idle"
                       :states {"idle" {:on {"go" [{:target "running"}]}}
                                "running" {:on {"done" [{:target "done"}]}}
                                "done" {:type :final}}})
  (let [m (fsm/read-machine *pdir* "gate")]
    (is (= :idle (:initial m)) "string names coerce to keywords")
    (is (contains? (:states m) :idle))
    (is (contains? (get-in m [:states :idle :on]) :go))
    (is (= :running (:target (first (get-in m [:states :idle :on :go]))))))
  (is (= ["gate"] (mapv :id (fsm/list-machines *pdir*))))
  (is (true? (fsm/delete-machine! *pdir* "gate")))
  (is (nil? (fsm/read-machine *pdir* "gate"))))

(deftest id-validation
  (are [id ok?] (= ok? (fsm/valid-id? id))
    "deploy-gate" true
    "a1"          true
    "Bad Id"      false
    ""            false))

;; ============================================================================
;; Guards
;; ============================================================================

(deftest guards
  (are [guard gctx expect] (= expect (fsm/guard-pass? guard gctx))
    nil                        {}                                    true
    {:event/match {:region "us"}} {:event {:region "us" :x 1}}       true
    {:event/match {:region "us"}} {:event {:region "eu"}}            false
    {:context/gte {:n 3}}         {:context {:n 5}}                  true
    {:context/gte {:n 3}}         {:context {:n 1}}                  false
    {:context/= {:s "x"}}         {:context {:s "x"}}                true
    {:context/all [:a :b]}        {:context {:a true :b 1}}          true
    {:context/all [:a :b]}        {:context {:a true}}               false
    {:context/any [:a :b]}        {:context {:b 1}}                  true)
  (testing "agent-state guards"
    (is (true?  (fsm/guard-pass? {:agent/idle? true} {:agent ag})))
    (is (false? (fsm/guard-pass? {:agent/running? true} {:agent ag}))))
  (testing "vector guard is AND"
    (is (true?  (fsm/guard-pass? [{:context/gte {:n 1}} {:event/match {:ok true}}]
                                 {:context {:n 2} :event {:ok true}})))
    (is (false? (fsm/guard-pass? [{:context/gte {:n 1}} {:event/match {:ok true}}]
                                 {:context {:n 0} :event {:ok true}})))))

;; ============================================================================
;; Assign + action normalization
;; ============================================================================

(deftest assign-and-normalize
  (is (= {:n 3}      (assoc-fn {:n 2} {:n [:inc]} {})))
  (is (= {:n 1}      (assoc-fn {:n 2} {:n [:dec]} {})))
  (is (= {:s "x"}    (assoc-fn {} {:s "x"} {})))
  (is (= {:oid "A"}  (assoc-fn {} {:oid [:from-event :order-id]} {:order-id "A"})))
  (is (= {:as :emit :event :notify/x} (norm [:emit :notify/x])))
  (is (= {:as :turn :text "hi"}       (norm [:turn "hi"])))
  (is (= {:as :assign :assign {:n [:inc]}} (norm [:assign {:n [:inc]}])))
  (is (= {:as :emit :event :y}        (norm {:as :emit :event :y}))))

;; ============================================================================
;; step! end to end
;; ============================================================================

(def ^:private machine
  {:id "gate2" :initial :idle :context {:n 0}
   :states
   {:idle    {:on {:go [{:target :running :do [[:assign {:n [:inc]}] [:emit :started/x]]}]}}
    :running {:on {:done  [{:guard {:context/gte {:n 1}} :target :done}]
                   :retry [{:target :running :do [[:assign {:n [:inc]}]]}]}}
    :done    {:type :final}}})

(deftest step-transitions-actions-lifecycle
  (fsm/write-machine! *pdir* machine)
  (let [fired (atom {})]
    (hooks/register-hook! :started/x      ::a (fn [m] (swap! fired assoc :started m)) :source ::s)
    (hooks/register-hook! :fsm/transition ::b (fn [m] (swap! fired update :trans (fnil conj []) [(:from m) (:to m)])) :source ::s)
    (hooks/register-hook! :fsm/final      ::c (fn [m] (swap! fired assoc :final (:state m))) :source ::s)
    (with-redefs [config/project-dir (fn ([] *pdir*) ([_] *pdir*))]
      (is (= :running (fsm/step! ag *pdir* (fsm/read-machine *pdir* "gate2") "s" :go {})))
      (is (= :running (fsm/step! ag *pdir* (fsm/read-machine *pdir* "gate2") "s" :retry {})))
      (is (= :done    (fsm/step! ag *pdir* (fsm/read-machine *pdir* "gate2") "s" :done {}))))
    (is (= 2 (:n (:context (fsm/current-runtime *pdir* machine "s")))) "context incremented twice")
    (is (= :done (:state (fsm/current-runtime *pdir* machine "s"))) "final state persisted")
    (is (some? (:started @fired)) ":emit action fired an event onto the bus")
    (is (= [[:idle :running] [:running :running] [:running :done]] (:trans @fired)))
    (is (= :done (:final @fired)) ":fsm/final fired on the terminal state")))

(deftest step-guard-blocks
  (fsm/write-machine! *pdir* machine)
  ;; in :running with n=0, the :done transition's guard {:context/gte {:n 1}} fails
  (fsm/write-runtime! *pdir* "gate2" "s2" {:machine "gate2" :state :running :context {:n 0} :history []})
  (with-redefs [config/project-dir (fn ([] *pdir*) ([_] *pdir*))]
    (is (nil? (fsm/step! ag *pdir* (fsm/read-machine *pdir* "gate2") "s2" :done {}))
        "no transition when the guard fails"))
  (is (= :running (:state (fsm/current-runtime *pdir* machine "s2"))) "state unchanged when blocked"))

;; ============================================================================
;; Phase 2 — timed & eventless transitions
;; ============================================================================

(def ^:private eventless @#'fsm/eventless-transitions)

(deftest elapsed-guard
  (let [now 1000000]
    (is (true?  (fsm/guard-pass? {:elapsed/gte 500}  {:entered-at (- now 1000) :now now})))
    (is (false? (fsm/guard-pass? {:elapsed/gte 2000} {:entered-at (- now 1000) :now now})))
    (is (false? (fsm/guard-pass? {:elapsed/gte 500}  {:now now}))
        "no :entered-at → never elapsed")))

(deftest eventless-expansion
  (let [ts (eventless {:always [{:target :a}] :after [{:after 500 :target :b}]})]
    (is (= 2 (count ts)))
    (is (= :a (:target (first ts))))
    (is (= {:elapsed/gte 500} (:guard (second ts))) ":after gains an elapsed guard")
    (is (= :b (:target (second ts))))))

(def ^:private timer-machine
  {:id "timer" :initial :waiting :context {:ready false}
   :states {:waiting {:always [{:guard {:context/all [:ready]} :target :go}]
                      :after   [{:after 100 :target :timeout}]}
            :go {:type :final} :timeout {:type :final}}})

;; ============================================================================
;; Phase 3 — observability snapshot
;; ============================================================================

(deftest session-states-snapshot
  (fsm/write-machine! *pdir* {:id "m1" :initial :a :states {:a {:on {:go [{:target :b}]}} :b {}}})
  (fsm/write-machine! *pdir* {:id "m2" :initial :x :states {:x {}}})
  (fsm/write-runtime! *pdir* "m1" "s" {:machine "m1" :state :b :context {:k 1}
                                       :history [{:from :a :to :b :event :go :ts 1}]})
  (let [ss (fsm/session-states *pdir* "s")
        m1 (first (filter #(= "m1" (:id %)) ss))
        m2 (first (filter #(= "m2" (:id %)) ss))]
    (is (= 2 (count ss)) "every defined machine appears")
    (is (= :b (:state m1)))
    (is (= {:k 1} (:context m1)))
    (is (= {:from :a :to :b :event :go} (select-keys (:last m1) [:from :to :event])))
    (is (= :x (:state m2)) "never-run machine shows its initial state")
    (is (nil? (:last m2)))))

(deftest tick-eventless-and-timed
  (fsm/write-machine! *pdir* timer-machine)
  (let [m   (fsm/read-machine *pdir* "timer")
        now (System/currentTimeMillis)]
    (with-redefs [config/project-dir (fn ([] *pdir*) ([_] *pdir*))]
      (testing "not ready + not elapsed → no eventless transition"
        (fsm/write-runtime! *pdir* "timer" "s1"
                            {:machine "timer" :state :waiting :context {:ready false} :entered-at now :history []})
        (is (nil? (fsm/tick! ag *pdir* m "s1"))))
      (testing ":always fires when its context guard passes"
        (fsm/write-runtime! *pdir* "timer" "s1"
                            {:machine "timer" :state :waiting :context {:ready true} :entered-at now :history []})
        (is (= :go (fsm/tick! ag *pdir* m "s1"))))
      (testing ":after fires once the elapsed guard passes"
        (fsm/write-runtime! *pdir* "timer" "s2"
                            {:machine "timer" :state :waiting :context {:ready false}
                             :entered-at (- (System/currentTimeMillis) 200) :history []})
        (is (= :timeout (fsm/tick! ag *pdir* m "s2")))))))
