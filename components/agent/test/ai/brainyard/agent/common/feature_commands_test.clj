;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.common.feature-commands-test
  "The feature$* surface.

   Every write path is exercised with `config/set-config!` redefined to a
   capture atom — these tests must never touch the real
   .brainyard/config.edn."
  (:require [clojure.test :refer [deftest testing is]]
            [ai.brainyard.agent.core.config :as config]
            [ai.brainyard.agent.core.feature :as feature]
            [ai.brainyard.agent.common.feature-commands :as fc]))

(defn- stub-cfg
  "A `get-config` stand-in: `overrides` first, else the schema default. Covers
   the 1-, 2- and 3-arity shapes the resolution path uses."
  [overrides]
  (fn ([k]     (if (contains? overrides k) (get overrides k)
                   (get-in config/config-schema [k :default])))
    ([_ k]   (if (contains? overrides k) (get overrides k)
                 (get-in config/config-schema [k :default])))
    ([_ k d] (if (contains? overrides k) (get overrides k) d))))

(def ^:private all-gates-on
  (into {} (for [g feature/gate-keys]
             [g (if (= "boolean" (get-in config/config-schema [g :type])) true 1)])))

(defn- with-gates [overrides f]
  (with-redefs [config/get-config    (stub-cfg (merge all-gates-on overrides))
                config/config-source (fn ([_] :global) ([_ _] :global))]
    (f)))

;; ============================================================================
;; feature$list
;; ============================================================================

(deftest list-summarises-every-family
  (with-gates {}
    (fn []
      (let [rows (:families (fc/feature$list))]
        (is (= (count feature/families) (count rows)))
        (is (every? #(and (:family %) (int? (:on %)) (int? (:of %))) rows))
        (is (= (count feature/feature-registry) (reduce + (map :of rows))))
        (testing "with every gate on, every feature resolves on"
          (is (every? #(= (:on %) (:of %)) rows)))))))

(deftest list-counts-reflect-resolution-not-raw-gates
  (with-gates {:enable-memory-capture false}
    (fn []
      (let [mem (first (filter #(= "memory" (:family %)) (:families (fc/feature$list))))]
        (is (< (:on mem) (:of mem))
            "capture off must prune the features that require it")))))

(deftest list-expands-one-family
  (with-gates {}
    (fn []
      (let [v (fc/feature$list :family "memory")]
        (is (= "memory" (:family v)))
        (is (= 6 (count (:features v))))
        (is (every? :feature (:features v))))))
  (is (:error (fc/feature$list :family "nope"))))

;; ============================================================================
;; feature$explain
;; ============================================================================

(deftest explain-reports-gate-and-winning-layer
  (with-gates {}
    (fn []
      (let [r (fc/feature$explain :feature "memory/graph")]
        (is (true? (:on? r)))
        (is (= "enable-graph-memory" (:gate r)))
        (is (= :global (:gate-source r)))
        (is (= :startup (:lifecycle r)))
        (is (true? (:requires-restart r)))
        (is (re-find #"(?i)^on because" (:why r)))))))

(deftest explain-accepts-both-separators
  (with-gates {}
    (fn []
      (is (= "memory/graph" (:feature (fc/feature$explain :feature "memory.graph"))))
      (is (= "memory/graph" (:feature (fc/feature$explain :feature "memory/graph")))))))

(deftest explain-names-the-implier
  (with-gates {:enable-memory-consolidation false :enable-graph-memory true}
    (fn []
      (let [r (fc/feature$explain :feature "memory/consolidation")]
        (is (true? (:on? r)))
        (is (= ["memory/graph"] (:implied-by r)))
        (is (re-find #"memory/graph implies it" (:why r)))))))

(deftest explain-names-unmet-requirements
  (with-gates {:enable-memory-capture false}
    (fn []
      (let [r (fc/feature$explain :feature "memory/graph")]
        (is (false? (:on? r)))
        (is (= ["memory/capture"] (:unmet r)))
        (is (re-find #"requirements are unmet" (:why r)))))))

(deftest explain-renders-a-disjunction
  (with-gates {:enable-skill-distillation false :enable-skill-refinement false}
    (fn []
      (let [r (fc/feature$explain :feature "self-improve/nudges")]
        (is (false? (:on? r)))
        (is (= [{:any-of ["self-improve/distillation" "self-improve/refinement"]}]
               (:unmet r)))
        (is (re-find #"any of" (:why r)))))))

(deftest explain-reports-degraded-without-disabling
  (with-gates {:enable-scheduler false}
    (fn []
      (let [r (fc/feature$explain :feature "automation/fsm")]
        (is (true? (:on? r)) "a partial requirement must not flip it off")
        (is (= {"automation/scheduler"
                "timed/eventless (:always/:after) transitions never advance"}
               (:degraded r)))))))

(deftest explain-handles-gateless-and-proposed
  (with-gates {}
    (fn []
      (testing "ungated grouping — must not try to name a gate that does not exist"
        (let [r (fc/feature$explain :feature "context/conversation")]
          (is (true? (:on? r)))
          (is (nil? (:gate r)))
          (is (re-find #"ungated grouping" (:why r)))))
      (testing "proposed gate"
        (let [r (fc/feature$explain :feature "memory/recall")]
          (is (true? (:proposed r)))
          (is (nil? (:gate-source r)) "a gate that is not a schema key has no layer")
          (is (re-find #"[Nn]ot gateable yet" (:why r)))))
      (testing "presentation"
        (is (true? (:presentation (fc/feature$explain :feature "ui/display"))))))))

(deftest explain-unknown-feature-errors
  (is (:error (fc/feature$explain :feature "bogus/thing")))
  (is (:error (fc/feature$explain :feature "memory"))
      "a bare family is not a feature"))

;; ============================================================================
;; feature$set
;; ============================================================================

(defn- capture-set
  "Run `f` with set-config! captured; returns [result captured-writes]."
  [overrides f]
  (let [writes (atom [])]
    (with-redefs [config/get-config    (stub-cfg (merge all-gates-on overrides))
                  config/config-source (fn ([_] :global) ([_ _] :global))
                  config/set-config!   (fn [_ k v] (swap! writes conj [k v]) v)]
      [(f) @writes])))

(deftest set-writes-the-gate-key
  (let [[r writes] (capture-set {} #(fc/feature$set :feature "memory/graph" :state "off"))]
    (is (= [[:enable-graph-memory false]] writes))
    (is (= "enable-graph-memory" (:gate r)))
    (is (false? (:set r)))
    (is (true? (:requires-restart r)) "startup gate must say a restart is owed")))

(deftest set-accepts-common-spellings
  (doseq [[state expected] [["on" true] ["true" true] ["enable" true]
                            ["off" false] ["false" false] ["disable" false]]]
    (let [[_ writes] (capture-set {} #(fc/feature$set :feature "automation/hooks" :state state))]
      (is (= [[:enable-user-hooks expected]] writes) (str "state " state)))))

(deftest set-rejects-a-bad-state-without-writing
  (let [[r writes] (capture-set {} #(fc/feature$set :feature "memory/graph" :state "sideways"))]
    (is (:error r))
    (is (empty? writes) "a rejected state must not write")))

(deftest set-refuses-non-gates-without-writing
  (doseq [[f pat] [["context/conversation" #"ungated"]
                   ["memory/recall"        #"not gateable yet"]
                   ["tools/cache"          #"numeric key"]
                   ["bogus/thing"          #"Unknown feature"]]]
    (let [[r writes] (capture-set {} #(fc/feature$set :feature f :state "on"))]
      (is (re-find pat (:error r)) (str f))
      (is (empty? writes) (str f " must not write")))))

(deftest set-warns-when-the-write-does-not-take
  (testing "turning a feature on whose requirement is unmet reports the real state"
    (let [[r writes] (capture-set {:enable-memory-capture false}
                                  #(fc/feature$set :feature "memory/graph" :state "on"))]
      (is (= [[:enable-graph-memory true]] writes) "the gate is still written")
      (is (true? (:set r)))
      (is (false? (:on? r)) "but it resolves off")
      (is (re-find #"Still off" (:warning r)))
      (is (= ["memory/capture"] (:unmet r))))))

(deftest set-surfaces-degradation
  (let [[r _] (capture-set {:enable-scheduler false}
                           #(fc/feature$set :feature "automation/fsm" :state "on"))]
    (is (true? (:on? r)))
    (is (= {"automation/scheduler"
            "timed/eventless (:always/:after) transitions never advance"}
           (:degraded r)))))

;; ============================================================================
;; Roster
;; ============================================================================

(deftest commands-are-registered
  (is (= 3 (count fc/feature-commands)))
  (is (every? var? fc/feature-commands))
  (testing "and ride all-common-commands"
    (require 'ai.brainyard.agent.common.commands)
    (let [all (set @(resolve 'ai.brainyard.agent.common.commands/all-common-commands))]
      (is (every? all fc/feature-commands)))))
