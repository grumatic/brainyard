;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: Apache-2.0
;; Licensed under the Apache License, Version 2.0 (the "License"); you may
;; not use this file except in compliance with the License. See LICENSE at
;; the repository root and CONTRIBUTING.md for contribution terms.

(ns ai.brainyard.agent.core.context-test
  "Tests for context/assemble-field.

  After the L1 simplification: assemble-field takes (agent, kind, field
  & {:title}). Both :system-context and :user-context kinds live at
  L1, differentiated by `:kind`. BASE comes from BT short-term memory.
  When base is missing, falls back to `:title` (if provided) or the
  kind name as a string."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.string :as str]
            [ai.brainyard.agent.core.context :as ctx]
            [ai.brainyard.agent.core.protocol :as ap]
            [ai.brainyard.agent.core.agent :as agent-core]
            [ai.brainyard.memory.interface :as mem]
            [ai.brainyard.memory.interface.protocol :as mem-proto]))

;; create-agent is private in agent.core.agent — reach it via the var so
;; the test can construct an Agent record directly. The public interface
;; exposes setup-agent / setup-agent-by-id; this test wants the lower-level
;; factory to bypass instance-id generation and registry registration.
(def ^:private create-agent* @#'agent-core/create-agent)

(def ^:dynamic *test-agent* nil)

(defn- write-section!
  "Write an L1 entry of the given kind for the agent's session."
  [agent kind field section content]
  (let [mm (:memory-manager @(:!state agent))]
    (mem/write-entry
     mm :l1
     {:kind       kind
      :id         (mem/l1-entry-id kind field section)
      :content    content
      :session-id (ap/session-id agent)
      :user-id    (ap/user-id agent)
      :data       {:field field :section section}})))

(defn- inject-st-memory!
  "Bare test agents have no BT, so st-memory doesn't exist by default.
  Inject a fresh atom at the conventional path so assemble-field can
  read base values from it."
  [agent]
  (let [st-mem (atom {})]
    (swap! (:!state agent) assoc-in [:behavior-tree :context :st-memory] st-mem)
    st-mem))

(defn with-agent [f]
  (reset! @#'agent-core/!agent-registry {})
  (let [a (create-agent* (str "u-" (random-uuid))
                         (str "s-" (random-uuid))
                         (str "agent-" (random-uuid))
                         :config {:name "context-test"})]
    (try
      (binding [*test-agent* a]
        (inject-st-memory! a)
        (f))
      (finally
        (.close a)))))

(use-fixtures :each with-agent)

;; =====================================================
;; Reads from BT st-memory (not st-memory-init)
;; =====================================================

(deftest base-from-st-memory-test
  (testing "BASE is read from BT st-memory at the field key"
    (swap! (ap/get-bt-st-memory *test-agent*) assoc :tool-context "BASE TC")
    (is (= "BASE TC"
           (ctx/assemble-field *test-agent* :system-context :tool-context)))))

(deftest base-ignores-st-memory-init-test
  (testing "Values held in st-memory-init are NOT consulted by assemble-field"
    (swap! (ap/get-st-memory-init *test-agent*) assoc :tool-context "FROM SMI")
    ;; st-memory has no :tool-context — fallback should kick in, not SMI.
    (let [out (ctx/assemble-field *test-agent* :system-context :tool-context)]
      (is (not= "FROM SMI" out))
      (is (= "system-context" out)
          "Fallback to (name kind) when both base and :title are absent"))))

;; =====================================================
;; Default base: kind name or :title
;; =====================================================

(deftest default-base-is-kind-name-test
  (is (= "system-context"
         (ctx/assemble-field *test-agent* :system-context :tool-context))
      "No base + no title → kind name as base")
  (is (= "user-context"
         (ctx/assemble-field *test-agent* :user-context :preferences))))

(deftest default-base-honors-title-override-test
  (is (= "Tool Usage Guide"
         (ctx/assemble-field *test-agent* :system-context :tool-context
                             :title "Tool Usage Guide")))
  (is (= "User Preferences"
         (ctx/assemble-field *test-agent* :user-context :preferences
                             :title "User Preferences"))))

(deftest base-overrides-title-test
  (testing "Explicit base in st-memory wins over :title"
    (swap! (ap/get-bt-st-memory *test-agent*) assoc :tool-context "ACTUAL BASE")
    (is (= "ACTUAL BASE"
           (ctx/assemble-field *test-agent* :system-context :tool-context
                               :title "ignored title")))))

(deftest blank-base-falls-through-to-title-test
  (testing "Empty / whitespace-only base counts as missing"
    (swap! (ap/get-bt-st-memory *test-agent*) assoc :tool-context "")
    (is (= "Tool Title"
           (ctx/assemble-field *test-agent* :system-context :tool-context
                               :title "Tool Title")))))

;; =====================================================
;; Assembly with sections
;; =====================================================

(deftest assembles-sections-with-base-test
  (swap! (ap/get-bt-st-memory *test-agent*) assoc :tool-context "BASE")
  (write-section! *test-agent* :system-context :tool-context "alpha" "first")
  (write-section! *test-agent* :system-context :tool-context "beta" "second")
  (let [out (ctx/assemble-field *test-agent* :system-context :tool-context)]
    (is (str/starts-with? out "BASE\n\n"))
    (is (str/includes? out "### alpha\nfirst"))
    (is (str/includes? out "### beta\nsecond"))
    (is (< (.indexOf out "### alpha") (.indexOf out "### beta"))
        "Sections sorted alphabetically")))

(deftest assembles-sections-with-default-base-test
  (testing "Sections appear under the kind-name default base when no
            st-memory base is set"
    (write-section! *test-agent* :system-context :tool-context "x" "X content")
    (let [out (ctx/assemble-field *test-agent* :system-context :tool-context)]
      (is (str/starts-with? out "system-context\n\n"))
      (is (str/includes? out "### x\nX content")))))

(deftest assembles-sections-with-title-default-test
  (testing "Sections appear under :title default base"
    (write-section! *test-agent* :system-context :tool-context "x" "X content")
    (let [out (ctx/assemble-field *test-agent* :system-context :tool-context
                                  :title "Tool Guide")]
      (is (str/starts-with? out "Tool Guide\n\n"))
      (is (str/includes? out "### x\nX content")))))

;; =====================================================
;; Kind isolation
;; =====================================================

(deftest kind-isolates-system-vs-user-context-test
  (testing "system-context and user-context entries don't mix even at the
            same field"
    (write-section! *test-agent* :system-context :tool-context "sys" "system content")
    (write-section! *test-agent* :user-context   :tool-context "usr" "user content")
    (let [sys (ctx/assemble-field *test-agent* :system-context :tool-context)
          usr (ctx/assemble-field *test-agent* :user-context :tool-context)]
      (is (str/includes? sys "system content"))
      (is (not (str/includes? sys "user content")))
      (is (str/includes? usr "user content"))
      (is (not (str/includes? usr "system content"))))))

(deftest field-isolates-tool-vs-agent-context-test
  (write-section! *test-agent* :system-context :tool-context  "x" "tool x")
  (write-section! *test-agent* :system-context :agent-context "y" "agent y")
  (let [tc (ctx/assemble-field *test-agent* :system-context :tool-context)
        ac (ctx/assemble-field *test-agent* :system-context :agent-context)]
    (is (str/includes? tc "tool x"))
    (is (not (str/includes? tc "agent y")))
    (is (str/includes? ac "agent y"))
    (is (not (str/includes? ac "tool x")))))

;; =====================================================
;; Session isolation
;; =====================================================

(deftest session-isolates-entries-test
  (testing "Sections written under another session don't appear here"
    (write-section! *test-agent* :system-context :tool-context "mine" "owned")
    ;; Write a section under a different session via direct store call
    (let [mm (:memory-manager @(:!state *test-agent*))]
      (mem/write-entry mm :l1
                       {:kind       :system-context
                        :id         (mem/l1-entry-id :system-context :tool-context "alien")
                        :content    "from another session"
                        :session-id "other-session"
                        :user-id    (ap/user-id *test-agent*)
                        :data       {:field :tool-context :section "alien"}}))
    (let [out (ctx/assemble-field *test-agent* :system-context :tool-context)]
      (is (str/includes? out "owned"))
      (is (not (str/includes? out "from another session"))))))

;; =====================================================
;; G9+G14: size guard via :max-chars
;; =====================================================

(deftest assemble-field-size-cap-warns-on-overflow-test
  (testing "When assembled size exceeds :max-chars, report-overflow! fires"
    (let [base (apply str (repeat 500 "X"))
          warns (atom [])]
      (swap! (ap/get-bt-st-memory *test-agent*) assoc :tool-context base)
      (with-redefs [ctx/report-overflow! (fn [m] (swap! warns conj m))]
        (let [out (ctx/assemble-field *test-agent* :system-context :tool-context
                                      :max-chars 100)]
          (is (= base out)
              "assembled string is still returned in full")))
      (is (= 1 (count @warns)))
      (let [w (first @warns)]
        (is (= :system-context (:kind w)))
        (is (= :tool-context (:field w)))
        (is (= 500 (:size-chars w)))
        (is (= 100 (:max-chars w)))))))

(deftest assemble-field-size-cap-silent-under-limit-test
  (testing "Under-limit case fires no overflow"
    (let [warns (atom 0)]
      (swap! (ap/get-bt-st-memory *test-agent*) assoc :tool-context "small")
      (with-redefs [ctx/report-overflow! (fn [_] (swap! warns inc))]
        (ctx/assemble-field *test-agent* :system-context :tool-context
                            :max-chars 100))
      (is (zero? @warns)))))

(deftest assemble-field-size-cap-zero-disables-check-test
  (testing ":max-chars 0 explicitly disables the guard"
    (let [warns (atom 0)
          base (apply str (repeat 5000 "X"))]
      (swap! (ap/get-bt-st-memory *test-agent*) assoc :tool-context base)
      (with-redefs [ctx/report-overflow! (fn [_] (swap! warns inc))]
        (ctx/assemble-field *test-agent* :system-context :tool-context
                            :max-chars 0))
      (is (zero? @warns)))))
