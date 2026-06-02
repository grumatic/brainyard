;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.core.memory-test
  "Tests for agent.core.memory — the per-layer recall/remember facade
  over the unified IMemoryStore."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [ai.brainyard.agent.core.memory :as agent-mem]
            [ai.brainyard.memory.interface :as mem]
            [ai.brainyard.memory.interface.protocol :as memp]))

(def ^:dynamic *mm* nil)

(use-fixtures :each
  (fn [f]
    (let [mm (mem/create-memory-manager (str "u-mem-" (random-uuid))
                                        :in-memory true)]
      (try
        (binding [*mm* mm] (f))
        (finally
          (when (mem/capture-running? mm) (mem/stop-capture! mm))
          (.close (:ds mm)))))))

(defn- l2-count [sid]
  (count (memp/read-entries (mem/store *mm*) :l2 {:session-id sid} {:limit 100})))

;; =====================================================
;; remember — per-layer writes
;; =====================================================

(deftest remember-l2-conversation-and-thoughts-test
  (testing "remember writes per-layer entries via the unified store"
    (let [out (agent-mem/remember
               *mm*
               :l2 [{:session-id "s1" :kind :conversation :role "user"      :content "hi"}
                    {:session-id "s1" :kind :conversation :role "assistant" :content "hello back"}
                    {:session-id "s1" :kind :thought      :role "assistant" :content "thinking..."}
                    {:session-id "s1" :kind :observation  :role "system"    :content "seen"}])]
      (is (= 4 (count (:l2 out))) "All 4 L2 entries persisted")
      (is (= 4 (l2-count "s1"))))))

(deftest remember-accepts-single-entry-map-test
  (testing "remember accepts a bare map and wraps it as one entry"
    (let [out (agent-mem/remember
               *mm*
               :l2 {:session-id "s1" :kind :conversation :role "user" :content "solo"})]
      (is (= 1 (count (:l2 out))))
      (is (= 1 (l2-count "s1"))))))

(deftest remember-l3-fact-test
  (testing "remember writes L3 semantic facts with layer-specific keys"
    (let [out (agent-mem/remember
               *mm*
               :l3 [{:kind :preference :content "user prefers dark mode" :confidence 0.9}])]
      (is (= 1 (count (:l3 out))))
      (let [facts (memp/read-entries (mem/store *mm*) :l3 {:kind :preference} {:limit 10})]
        (is (= 1 (count facts)))
        (is (= "user prefers dark mode" (-> facts first :content)))))))

(deftest remember-l1-system-context-test
  (testing "remember writes L1 entries with required :session-id"
    (let [out (agent-mem/remember
               *mm*
               :l1 [{:session-id "s1"
                     :kind :system-context
                     :content "naming conventions ..."
                     :data {:field :tool-context :section "naming"}}])]
      (is (= 1 (count (:l1 out))))
      (let [l1-entries (memp/read-entries (mem/store *mm*) :l1 {:session-id "s1"} {:limit 10})]
        (is (= 1 (count l1-entries)))))))

(deftest remember-multilayer-test
  (testing "remember writes to multiple layers in one call"
    (let [out (agent-mem/remember
               *mm*
               :l2 [{:session-id "s1" :kind :conversation :role "user" :content "hi"}]
               :l3 [{:kind :fact :content "an L3 fact" :confidence 1.0}])]
      (is (= 1 (count (:l2 out))))
      (is (= 1 (count (:l3 out))))
      (is (nil? (:l1 out)) "Layers not provided are absent from the result"))))

;; =====================================================
;; recall — per-layer reads
;; =====================================================

(deftest recall-per-layer-test
  (testing "recall reads each requested layer with its own opts"
    (agent-mem/remember
     *mm*
     :l1 [{:session-id "s1"
           :kind :system-context
           :content "naming context"
           :data {:field :tool-context :section "naming"}}]
     :l2 [{:session-id "s1" :kind :conversation :role "user"      :content "deploy the staging app"}
          {:session-id "s1" :kind :conversation :role "assistant" :content "deploying..."}]
     :l3 [{:kind :fact :content "staging deploys take 3 minutes" :confidence 0.8}])
    ;; Allow FTS triggers to settle.
    (Thread/sleep 50)
    (let [out (agent-mem/recall
               *mm*
               :l1 {:session-id "s1" :limit 5}
               :l2 {:text "deploy" :session-id "s1" :match :or :limit 5}
               :l3 {:text "deploys" :match :or :limit 5})]
      (is (pos? (count (:l1 out))) "L1 returns the system-context entry")
      (is (pos? (count (:l2 out))) "L2 FTS finds the deploy turns")
      (is (pos? (count (:l3 out))) "L3 FTS finds the deploys fact"))))

(deftest recall-skips-omitted-layers-test
  (testing "Layers not given are not read and are absent from the output"
    (let [out (agent-mem/recall
               *mm*
               :l2 {:session-id "s-empty" :limit 5})]
      (is (contains? out :l2))
      (is (not (contains? out :l1)))
      (is (not (contains? out :l3))))))

(deftest recall-falls-back-to-contextual-recall-test
  (testing "When no :l1/:l2/:l3 is provided, recall delegates to contextual-recall"
    (agent-mem/remember
     *mm*
     :l2 [{:session-id "s1" :kind :conversation :role "user"      :content "deploy the staging app"}
          {:session-id "s1" :kind :conversation :role "assistant" :content "deploying..."}]
     :l3 [{:kind :fact :content "staging deploys take 3 minutes" :confidence 0.8}])
    (Thread/sleep 50)
    (let [out (agent-mem/recall *mm*
                                :query "deploy"
                                :session-id "s1"
                                :limit 10
                                :match :or)]
      (is (vector? out) "contextual-recall returns the RRF-merged combined vector")
      (is (pos? (count out)) "Cross-layer fallback finds entries across L2 and L3")
      (is (every? :_layer out) "Each entry is annotated with :_layer by the RRF merger"))))
