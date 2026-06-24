;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.schedule-test
  "Tests for the scheduler (R2): cron engine, spec store, due-selection,
   run-spec!/run-due! (executor stubbed), and schedule$add command guards."
  (:require [clojure.test :refer [deftest testing is are use-fixtures]]
            [ai.brainyard.agent.common.schedule :as sched]
            [ai.brainyard.agent.core.config :as config]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.time Instant ZoneId ZonedDateTime]))

(def ^:dynamic *pdir* nil)

(defn temp-dir-fixture [f]
  (let [dir (io/file (System/getProperty "java.io.tmpdir")
                     (str "sched-test-" (System/currentTimeMillis) "-" (rand-int 100000)))]
    (.mkdirs dir)
    (binding [*pdir* (.getPath dir)]
      (try (f)
           (finally (doseq [^java.io.File x (reverse (file-seq dir))] (.delete x)))))))

(use-fixtures :each temp-dir-fixture)

(defn- zdt [^long ms] (ZonedDateTime/ofInstant (Instant/ofEpochMilli ms) (ZoneId/systemDefault)))

;; ============================================================================
;; Cron
;; ============================================================================

(deftest parse-cron-cases
  (testing "valid expressions"
    (is (= #{0} (:min (sched/parse-cron "0 9 * * 1-5"))))
    (is (= #{9} (:hour (sched/parse-cron "0 9 * * 1-5"))))
    (is (= #{1 2 3 4 5} (:dow (sched/parse-cron "0 9 * * 1-5"))))
    (is (= #{0 15 30 45} (:min (sched/parse-cron "*/15 * * * *"))))
    (is (= 60 (count (:min (sched/parse-cron "* * * * *")))))
    (is (= #{0} (:dow (sched/parse-cron "0 0 * * 7"))) "dow 7 normalizes to 0 (Sunday)")
    (is (= #{1 5 10} (:dom (sched/parse-cron "0 0 1,5,10 * *")))))
  (testing "malformed → nil"
    (are [s] (nil? (sched/parse-cron s))
      "bad" "0 9 * *" "0 9 * * * *" "99 0 * * *" "" nil)))

(deftest matches-and-next-fire
  (let [base (.toEpochMilli (.toInstant (-> (ZonedDateTime/now (ZoneId/systemDefault))
                                            (.withSecond 0) (.withNano 0))))
        cron (sched/parse-cron "30 14 * * *")]
    (testing "matches-cron? checks wall-clock fields"
      (is (sched/matches-cron? (sched/parse-cron "30 * * * *")
                               (-> (zdt base) (.withMinute 30))))
      (is (not (sched/matches-cron? (sched/parse-cron "30 * * * *")
                                    (-> (zdt base) (.withMinute 31))))))
    (testing "next-fire-after lands on the right minute/hour, strictly in the future"
      (let [nf (sched/next-fire-after cron base)
            z  (zdt nf)]
        (is (> nf base))
        (is (= 30 (.getMinute z)))
        (is (= 14 (.getHour z)))))))

;; ============================================================================
;; Store
;; ============================================================================

(deftest store-round-trip
  (is (= [] (sched/list-specs *pdir*)))
  (let [spec {:id "daily-report" :title "t" :prompt "p" :cron "0 9 * * *"
              :enabled true :next-fire 123 :created 1}]
    (sched/write-spec! *pdir* spec)
    (is (= spec (sched/read-spec *pdir* "daily-report")))
    (is (= ["daily-report"] (mapv :id (sched/list-specs *pdir*))))
    (is (true? (sched/delete-spec! *pdir* "daily-report")))
    (is (nil? (sched/read-spec *pdir* "daily-report")))
    (is (= [] (sched/list-specs *pdir*))))
  (testing "invalid id is rejected"
    (is (thrown? Exception (sched/write-spec! *pdir* {:id "Bad Id" :prompt "p"})))))

;; ============================================================================
;; due? / run-spec! / run-due!
;; ============================================================================

(deftest due-predicate
  (let [now 1000]
    (are [spec d?] (= d? (sched/due? spec now))
      {:enabled true  :next-fire 999}  true
      {:enabled true  :next-fire 1000} true
      {:enabled true  :next-fire 1001} false
      {:enabled false :next-fire 999}  false
      {:enabled true  :next-fire nil}  false)))

(deftest run-spec-claims-delivers-advances
  (let [now (System/currentTimeMillis)
        spec {:id "job1" :prompt "do it" :cron "*/5 * * * *"
              :enabled true :next-fire (- now 1000) :sink "file" :created 1}]
    (sched/write-spec! *pdir* spec)
    (binding [sched/*execute-job* (fn [_] {:answer "hello-output"})]
      (let [final (sched/run-spec! *pdir* spec now true)]
        (is (= :ok (:last-status final)))
        (is (= now (:last-run final)))
        (is (> (:next-fire final) now) "cron next-fire advanced into the future")
        (let [logged (slurp (:last-output final))]
          (is (str/includes? logged "hello-output")))
        ;; persisted state matches
        (is (= :ok (:last-status (sched/read-spec *pdir* "job1"))))))))

(deftest run-spec-records-error
  (let [now (System/currentTimeMillis)
        spec {:id "job-err" :prompt "x" :cron "*/5 * * * *" :enabled true
              :next-fire (- now 1) :sink "file" :created 1}]
    (sched/write-spec! *pdir* spec)
    (binding [sched/*execute-job* (fn [_] {:error "boom"})]
      (let [final (sched/run-spec! *pdir* spec now true)]
        (is (= :error (:last-status final)))
        (is (str/includes? (slurp (:last-output final)) "ERROR: boom"))))))

(deftest run-now-does-not-advance
  (let [now (System/currentTimeMillis)
        spec {:id "rn" :prompt "x" :cron "*/5 * * * *" :enabled true
              :next-fire 42 :sink "file" :created 1}]
    (sched/write-spec! *pdir* spec)
    (binding [sched/*execute-job* (fn [_] {:answer "ok"})]
      (let [final (sched/run-spec! *pdir* spec now false)]
        (is (= 42 (:next-fire final)) "run-now leaves next-fire untouched")))))

(deftest one-shot-disables-after-run
  (let [now (System/currentTimeMillis)
        spec {:id "once" :prompt "x" :fire-at (- now 1) :enabled true
              :next-fire (- now 1) :sink "file" :created 1}]
    (sched/write-spec! *pdir* spec)
    (binding [sched/*execute-job* (fn [_] {:answer "ok"})]
      (let [final (sched/run-spec! *pdir* spec now true)]
        (is (false? (:enabled final)))
        (is (nil? (:next-fire final)))))))

(deftest run-due-fires-only-due
  (let [now (System/currentTimeMillis)]
    (sched/write-spec! *pdir* {:id "due-one" :prompt "a" :cron "*/5 * * * *"
                               :enabled true :next-fire (- now 1000) :sink "file" :created 1})
    (sched/write-spec! *pdir* {:id "future" :prompt "b" :cron "*/5 * * * *"
                               :enabled true :next-fire (+ now 999999) :sink "file" :created 2})
    (sched/write-spec! *pdir* {:id "disabled" :prompt "c" :cron "*/5 * * * *"
                               :enabled false :next-fire (- now 1000) :sink "file" :created 3})
    (binding [sched/*execute-job* (fn [_] {:answer "ran"})]
      (let [res (sched/run-due! *pdir* now)]
        (is (= 1 (:count res)))
        (is (= ["due-one"] (:fired res)))
        (is (= (+ now 999999) (:next-fire (sched/read-spec *pdir* "future"))) "future untouched")))))

;; ============================================================================
;; schedule$add command
;; ============================================================================

(deftest add-command
  (with-redefs [config/project-dir (fn ([] *pdir*) ([_] *pdir*))
                config/get-config   (fn ([_k] false) ([_a _k] false))]
    (testing "valid cron schedules and computes next-fire + off-note"
      (let [res (sched/schedule$add :prompt "daily standup" :cron "0 9 * * 1-5" :title "Standup")]
        (is (string? (:id res)))
        (is (number? (:next-fire res)))
        (is (re-find #"Scheduler ticker is OFF" (:note res)))
        (is (= 1 (count (sched/list-specs *pdir*))))))
    (testing "guards"
      (is (re-find #":prompt" (:error (sched/schedule$add :cron "0 9 * * *"))))
      (is (re-find #":cron" (:error (sched/schedule$add :prompt "x"))))
      (is (re-find #"invalid :cron" (:error (sched/schedule$add :prompt "x" :cron "nope")))))))
