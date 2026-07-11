;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.watch-test
  "Tests for the watch loop (Phase 4 of docs/design/event-bus-and-reactor.md):
   probe predicates, default-run-probe (shell/file), and run-watch! firing an
   event on the hooks bus + persisting the observation, plus :every scheduling."
  (:require [clojure.test :refer [deftest testing is are use-fixtures]]
            [ai.brainyard.agent.common.schedule :as sched]
            [ai.brainyard.agent.core.hooks :as hooks]
            [clojure.java.io :as io]))

(def ^:dynamic *pdir* nil)

(defn fixture [f]
  (let [dir (io/file (System/getProperty "java.io.tmpdir")
                     (str "watch-test-" (System/currentTimeMillis) "-" (rand-int 100000)))]
    (.mkdirs dir)
    (binding [*pdir* (.getPath dir)]
      (try (f)
           (finally
             (hooks/reset-hooks!)
             (doseq [^java.io.File x (reverse (file-seq dir))] (.delete x)))))))

(use-fixtures :each fixture)

;; ============================================================================
;; Predicates
;; ============================================================================

(deftest predicates
  (are [when-spec prev obs expect] (= expect (sched/predicate-met? when-spec prev obs))
    nil                                    {:value "a"} {:value "b"}          true
    nil                                    {:value "a"} {:value "a"}          false
    {:op :increased}                       {:value "3"} {:value "5"}          true
    {:op :increased}                       nil          {:value "5"}          false
    {:op :increased}                       {:value "9"} {:value "5"}          false
    {:op :matches :re "err"}               nil          {:value "an error"}   true
    {:op :matches :re "err"}               nil          {:value "ok"}         false
    {:op :nonzero-exit}                    nil          {:value "" :exit 2}   true
    {:op :zero-exit}                       nil          {:value "" :exit 0}   true
    {:op :threshold :cmp :gt :value 10}    nil          {:value "12"}         true
    {:op :threshold :cmp :gt :value 10}    nil          {:value "8"}          false
    {:op :threshold :cmp :le :value 10}    nil          {:value "10"}         true))

;; ============================================================================
;; default-run-probe
;; ============================================================================

(deftest probe-shell
  (is (= "hi" (:value (sched/default-run-probe {:type :shell :cmd "echo hi"}))))
  (is (= 0    (:exit  (sched/default-run-probe {:type :shell :cmd "true"}))))
  (is (= 3    (:exit  (sched/default-run-probe {:type :shell :cmd "exit 3"})))))

(deftest probe-file
  (let [tf (java.io.File/createTempFile "watch" ".txt")]
    (try
      (is (= 0 (:exit (sched/default-run-probe {:type :file :path (.getPath tf)}))))
      (is (= 1 (:exit (sched/default-run-probe {:type :file :path "/no/such/path/xyz"}))))
      (finally (.delete tf)))))

(deftest probe-unknown-type
  (is (:error (sched/default-run-probe {:type :bogus}))))

;; ============================================================================
;; run-watch! (via run-spec! dispatch)
;; ============================================================================

(defn- write-watch! [extra]
  (sched/write-spec! *pdir*
                     (merge {:id "w1" :kind :watch :probe {:x 1} :emit :order/shipped
                             :when {:op :increased} :every 1000 :enabled true :created 1}
                            extra)))

(deftest watch-fires-event-and-persists
  (write-watch! {:last-observation {:value "3"}})
  (let [seen (atom nil)]
    (hooks/register-hook! :order/shipped ::probe (fn [m] (reset! seen m)) :source ::t)
    (binding [sched/*run-probe* (fn [_] {:value "5" :exit 0})]
      (let [final (sched/run-spec! *pdir* (sched/read-spec *pdir* "w1") 1000 false)]
        (is (= :fired (:last-status final)))
        (is (= "5" (:value (:last-observation final))))
        (is (= 1000 (:last-fired final)))))
    (is (= "5" (:value @seen)))
    (is (= :watch (:source @seen)))
    (is (= :order/shipped (:event @seen)))))

(deftest watch-no-fire-when-predicate-unmet
  (write-watch! {:last-observation {:value "5"}})
  (let [seen (atom nil)]
    (hooks/register-hook! :order/shipped ::probe (fn [m] (reset! seen m)) :source ::t)
    (binding [sched/*run-probe* (fn [_] {:value "5" :exit 0})]  ; not increased
      (is (= :ok (:last-status (sched/run-spec! *pdir* (sched/read-spec *pdir* "w1") 2000 false)))))
    (is (nil? @seen))))

(deftest watch-probe-error-does-not-fire
  (write-watch! {})
  (let [seen (atom nil)]
    (hooks/register-hook! :order/shipped ::probe (fn [m] (reset! seen m)) :source ::t)
    (binding [sched/*run-probe* (fn [_] {:error "boom"})]
      (is (= :error (:last-status (sched/run-spec! *pdir* (sched/read-spec *pdir* "w1") 3000 false)))))
    (is (nil? @seen))))

(deftest watch-every-advances-next-fire-on-claim
  (write-watch! {})
  (binding [sched/*run-probe* (fn [_] {:value "1" :exit 0})]
    (let [final (sched/run-spec! *pdir* (sched/read-spec *pdir* "w1") 5000 true)]
      (is (= 6000 (:next-fire final)) "next-fire = now + :every"))))

(deftest schedule-list-excludes-watches
  (write-watch! {})
  ;; a watch spec is a :kind :watch schedule spec; list-specs sees it,
  ;; but schedule$list filters it out (watches surface via watch$list).
  (is (= 1 (count (sched/list-specs *pdir*))))
  (is (= :watch (:kind (sched/read-spec *pdir* "w1")))))
