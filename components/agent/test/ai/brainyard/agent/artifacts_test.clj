;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.artifacts-test
  (:require [clojure.test :refer [deftest testing is]]
            [ai.brainyard.agent.common.artifacts :as art]
            [ai.brainyard.agent.core.protocol :as proto])
  (:import [java.io File]))

(defn- mock-agent
  "Minimal agent exposing st-memory-init (cross-turn registry) and an empty
   bt st-memory (so the 'effective' view falls back to the registry)."
  [init-atom bt-atom]
  (reify
    proto/IAgent
    (agent-id [_] :mock)
    (agent-name [_] "mock")
    (agent-description [_] "mock")
    (user-id [_] "u")
    (session-id [_] "s")
    (defagent-type [_] :mock)
    (process [_ _ _] nil)
    (get-tools [_] nil)
    (get-state [_] {})
    proto/IAgentState
    (get-st-memory-init [_] init-atom)
    proto/IAgentBTIntegration
    (get-bt-st-memory [_] bt-atom)))

(defn- ids [init] (mapv :id (:live-artifacts @init)))

(deftest artifact-add-inline-test
  (testing "artifact$add with :content stores an inline :origin :llm descriptor"
    (let [init (atom {})]
      (binding [proto/*current-agent* (mock-agent init (atom {}))]
        (let [r (art/artifact$add :content "hello world" :name "My Note")]
          (is (= "note:my-note" (:id r)))
          (is (nil? (:error r)))
          (let [d (first (:live-artifacts @init))]
            (is (= :inline (:source d)))
            (is (= :llm (:origin d)))
            (is (= "hello world" (:content d)))
            (is (false? (:pinned? d)))))))))

(deftest artifact-add-file-test
  (testing "artifact$add with :path stores a :source :file descriptor; missing path errors"
    (let [f (File/createTempFile "artifact" ".md")]
      (spit f "doc body")
      (let [init (atom {})]
        (binding [proto/*current-agent* (mock-agent init (atom {}))]
          (let [r (art/artifact$add :path (.getPath f) :pinned true)]
            (is (nil? (:error r)))
            (let [d (first (:live-artifacts @init))]
              (is (= :file (:source d)))
              (is (= (.getCanonicalPath f) (:path d)))
              (is (true? (:pinned? d)))))
          (is (some? (:error (art/artifact$add :path "/no/such/file.md")))))))))

(deftest artifact-add-requires-a-source-test
  (testing "artifact$add with no source returns an error"
    (binding [proto/*current-agent* (mock-agent (atom {}) (atom {}))]
      (is (some? (:error (art/artifact$add)))))))

(deftest artifact-upsert-dedupes-by-id-test
  (testing "re-adding the same note id replaces rather than duplicates"
    (let [init (atom {})]
      (binding [proto/*current-agent* (mock-agent init (atom {}))]
        (art/artifact$add :content "v1" :name "Note")
        (art/artifact$add :content "v2" :name "Note")
        (is (= 1 (count (:live-artifacts @init))))
        (is (= "v2" (:content (first (:live-artifacts @init)))))))))

(deftest artifact-list-test
  (testing "artifact$list reflects the registry with origin/source/pinned/size"
    (let [init (atom {})]
      (binding [proto/*current-agent* (mock-agent init (atom {}))]
        (art/artifact$add :content "abc" :name "N")
        (let [r (art/artifact$list)]
          (is (= 1 (:count r)))
          (let [e (first (:artifacts r))]
            (is (= "note:n" (:id e)))
            (is (= "llm" (:origin e)))
            (is (= "inline" (:source e)))
            (is (= 3 (:size e)))))))))

(deftest artifact-remove-test
  (testing "artifact$remove drops a dynamic artifact by id"
    (let [init (atom {})]
      (binding [proto/*current-agent* (mock-agent init (atom {}))]
        (art/artifact$add :content "x" :name "Gone")
        (is (= ["note:gone"] (ids init)))
        (let [r (art/artifact$remove :id "note:gone")]
          (is (nil? (:error r)))
          (is (= [] (ids init))))
        (is (some? (:error (art/artifact$remove :id "nope"))))))))

(deftest artifact-remove-refuses-system-test
  (testing "artifact$remove refuses a system artifact unless :force"
    ;; Seed a system artifact directly into the registry (as the per-turn merge
    ;; would surface it) and confirm the LLM cannot remove it without force.
    (let [init (atom {:live-artifacts
                      [{:id "ref:/x/CLAUDE.md" :name "CLAUDE.md"
                        :origin :system :pinned? true :source :file}]})]
      (binding [proto/*current-agent* (mock-agent init (atom {}))]
        (is (some? (:error (art/artifact$remove :id "ref:/x/CLAUDE.md"))))
        (is (= 1 (count (:live-artifacts @init))))
        (let [r (art/artifact$remove :id "ref:/x/CLAUDE.md" :force true)]
          (is (nil? (:error r)))
          (is (= [] (:live-artifacts @init))))))))

(deftest artifact-union-view-test
  ;; Regression (found via live e2e): list/remove must see registry artifacts
  ;; even when the per-turn bt store already holds system artifacts. A freshly
  ;; artifact$add'ed item lives only in st-memory-init until the next turn; if
  ;; effective-artifacts read bt alone (non-empty), the new item was invisible.
  (testing "list/remove see registry artifacts while bt already holds system ones"
    (let [init (atom {})
          bt   (atom {:live-artifacts
                      [{:id "ref:/x/CLAUDE.md" :name "CLAUDE.md"
                        :origin :system :pinned? true :source :file}]})]
      (binding [proto/*current-agent* (mock-agent init bt)]
        (art/artifact$add :content "fresh" :name "Fresh")
        (testing "list is the union of bt (system) + registry (dynamic)"
          (let [lst (art/artifact$list)]
            (is (= 2 (:count lst)))
            (is (= #{"ref:/x/CLAUDE.md" "note:fresh"}
                   (set (map :id (:artifacts lst)))))))
        (testing "just-added dynamic artifact is removable despite non-empty bt"
          (is (nil? (:error (art/artifact$remove :id "note:fresh"))))
          (is (= [] (:live-artifacts @init))))
        (testing "system artifact (only in bt) is still refused"
          (is (some? (:error (art/artifact$remove :id "ref:/x/CLAUDE.md")))))))))

(deftest artifact-pin-test
  (testing "artifact$pin toggles the :pinned? flag"
    (let [init (atom {})]
      (binding [proto/*current-agent* (mock-agent init (atom {}))]
        (art/artifact$add :content "y" :name "P")
        (art/artifact$pin :id "note:p" :pinned true)
        (is (true? (:pinned? (first (:live-artifacts @init)))))
        (art/artifact$pin :id "note:p" :pinned false)
        (is (false? (:pinned? (first (:live-artifacts @init)))))
        (is (some? (:error (art/artifact$pin :id "missing" :pinned true))))))))

(deftest artifact-tools-require-running-agent-test
  (testing "tools error cleanly when no agent is bound"
    (binding [proto/*current-agent* nil]
      (is (some? (:error (art/artifact$add :content "x"))))
      (is (some? (:error (art/artifact$remove :id "a"))))
      (is (some? (:error (art/artifact$pin :id "a" :pinned true)))))))
