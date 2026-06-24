;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.self-improve-nudge-test
  "Tests for the self-improvement nudge surface (R1 Phase 3): pending-proposal
   detection, fresh-vs-suppressed queueing, self-heal after accept, drain-once,
   and root/config gating."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [ai.brainyard.agent.common.self-improve-nudge :as nudge]
            [ai.brainyard.agent.common.skill-distill.proposals :as proposals]
            [ai.brainyard.agent.core.config :as config]
            [ai.brainyard.agent.core.protocol :as proto]
            [clojure.java.io :as io]))

(def ^:dynamic *project-dir* nil)

(defn temp-dir-fixture [f]
  (let [dir (io/file (System/getProperty "java.io.tmpdir")
                     (str "nudge-test-" (System/currentTimeMillis)))]
    (.mkdirs dir)
    (binding [*project-dir* (.getPath dir)]
      (try (f)
           (finally
             (doseq [^java.io.File x (reverse (file-seq dir))] (.delete x)))))))

(use-fixtures :each temp-dir-fixture)

(def md "---\nname: x\ndescription: d\n---\n# X\n")

(defn- stage! [name]
  (proposals/write-proposal! *project-dir* {:name name :skill-md md}))

;; ============================================================================
;; pending-proposal-names + render
;; ============================================================================

(deftest pending-names
  (is (= #{} (nudge/pending-proposal-names *project-dir*)))
  (stage! "deploy-flow")
  (stage! "audit-deps")
  (is (= #{"deploy-flow" "audit-deps"} (nudge/pending-proposal-names *project-dir*))))

(deftest render-notice-shape
  (let [s (nudge/render-notice #{"deploy-flow" "audit-deps"})]
    (is (re-find #"2 skill proposals awaiting review" s))
    (is (re-find #"`audit-deps`" s))
    (is (re-find #"`deploy-flow`" s))
    (is (re-find #"skill-proposal\$accept" s)))
  (testing "singular for one"
    (is (re-find #"1 skill proposal awaiting" (nudge/render-notice #{"x"})))))

;; ============================================================================
;; compute-and-queue!
;; ============================================================================

(deftest queue-when-fresh-then-suppress
  (let [init (atom {})
        st   (atom {})]
    (testing "no proposals → :none, nothing queued"
      (is (= :none (nudge/compute-and-queue! *project-dir* init st)))
      (is (nil? (:pending-self-improve-notice @st))))

    (stage! "deploy-flow")
    (testing "fresh proposal → :queued, notice in st-memory, name recorded"
      (is (= :queued (nudge/compute-and-queue! *project-dir* init st)))
      (is (re-find #"deploy-flow" (:pending-self-improve-notice @st)))
      (is (= #{"deploy-flow"} (:self-improve-nudged @init))))

    (testing "same proposal still pending → suppressed (:none), no re-queue"
      (swap! st dissoc :pending-self-improve-notice)
      (is (= :none (nudge/compute-and-queue! *project-dir* init st)))
      (is (nil? (:pending-self-improve-notice @st))))

    (testing "a NEW proposal → :queued again, only the fresh one in the notice"
      (stage! "audit-deps")
      (is (= :queued (nudge/compute-and-queue! *project-dir* init st)))
      (let [n (:pending-self-improve-notice @st)]
        (is (re-find #"audit-deps" n))
        (is (not (re-find #"deploy-flow" n))))   ;; already surfaced
      (is (= #{"deploy-flow" "audit-deps"} (:self-improve-nudged @init))))))

(deftest self-heal-after-accept
  (let [init (atom {})
        st   (atom {})]
    (stage! "deploy-flow")
    (is (= :queued (nudge/compute-and-queue! *project-dir* init st)))
    (testing "accept/remove the proposal, then re-stage same name → nudges again"
      (proposals/delete-proposal! *project-dir* "deploy-flow")
      ;; with it gone from disk, the surfaced set self-prunes
      (is (= :none (nudge/compute-and-queue! *project-dir* init st)))
      (is (= #{} (:self-improve-nudged @init)))
      (stage! "deploy-flow")
      (swap! st dissoc :pending-self-improve-notice)
      (is (= :queued (nudge/compute-and-queue! *project-dir* init st))))))

(deftest no-store-guard
  (is (= :no-store (nudge/compute-and-queue! *project-dir* nil (atom {}))))
  (is (= :no-store (nudge/compute-and-queue! *project-dir* (atom {}) nil))))

;; ============================================================================
;; drain-iteration-notice!
;; ============================================================================

(deftest drain-once
  (let [st (atom {:pending-self-improve-notice "hi"})]
    (is (= "hi" (nudge/drain-iteration-notice! st)))
    (is (nil? (nudge/drain-iteration-notice! st)))   ;; cleared
    (is (nil? (:pending-self-improve-notice @st))))
  (is (nil? (nudge/drain-iteration-notice! nil))))

;; ============================================================================
;; maybe-queue! gating (root + config)
;; ============================================================================

(defn- stub-agent [{:keys [parent]}]
  {:!state (atom {:runtime {:parent-agent parent}})})

(deftest maybe-queue-gating
  (stage! "deploy-flow")
  (let [init (atom {})]
    (testing "root + config on → queues"
      (let [st (atom {})]
        (with-redefs [config/get-config (fn [_ k] (= k :enable-self-improve-nudges))
                      config/project-dir (fn [_] *project-dir*)
                      proto/get-st-memory-init (fn [_] init)]
          (is (= :queued (nudge/maybe-queue! (stub-agent {:parent nil}) st)))
          (is (re-find #"deploy-flow" (:pending-self-improve-notice @st))))))

    (testing "config off → no-op (nil)"
      (let [st (atom {})]
        (with-redefs [config/get-config (fn [_ _] false)
                      config/project-dir (fn [_] *project-dir*)
                      proto/get-st-memory-init (fn [_] (atom {}))]
          (is (nil? (nudge/maybe-queue! (stub-agent {:parent nil}) st)))
          (is (nil? (:pending-self-improve-notice @st))))))

    (testing "sub-agent (has parent) → no-op even with config on"
      (let [st (atom {})]
        (with-redefs [config/get-config (fn [_ _] true)
                      config/project-dir (fn [_] *project-dir*)
                      proto/get-st-memory-init (fn [_] (atom {}))]
          (is (nil? (nudge/maybe-queue! (stub-agent {:parent {:agent-id :root/x}}) st)))
          (is (nil? (:pending-self-improve-notice @st))))))))
