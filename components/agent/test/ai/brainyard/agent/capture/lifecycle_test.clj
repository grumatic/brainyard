;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.capture.lifecycle-test
  "Tests that the memory capture pipeline starts/stops automatically with
  the agent lifecycle when :enable-memory-capture is set in agent config.

  Capture is per memory-manager: when multiple agents share one manager,
  capture should run while at least one is alive and stop only when the
  last one closes."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [ai.brainyard.agent.interface :as agent]
            [ai.brainyard.agent.core.agent :as agent-core]
            [ai.brainyard.agent.core.hooks :as hooks]
            [ai.brainyard.agent.core.tool :as tool]
            [ai.brainyard.memory.interface :as mem]))

(use-fixtures :each
  (fn [f]
    (hooks/reset-hooks!)
    (agent/reset-agent-registry!)
    (try (f)
         (finally
           (hooks/reset-hooks!)
           (agent/reset-agent-registry!)))))

(defn- make-mm []
  (mem/create-memory-manager (str "u-" (random-uuid)) :in-memory true))

;; ============================================================================
;; Capture starts on agent creation, stops on close (single agent)
;; ============================================================================

(deftest capture-starts-and-stops-with-single-agent-test
  (testing "single agent with :enable-memory-capture true"
    (let [mm (make-mm)
          a  (agent/create-agent "u" "s" "agent-cap"
                                 :memory-manager mm
                                 :config {:name "Cap"
                                          :enable-memory-capture true})]
      (try
        (agent/start-agent a)
        (is (mem/capture-running? mm)
            "capture should be running after create-agent")
        (.close a)
        (is (not (mem/capture-running? mm))
            "capture should stop when the last agent closes")
        (finally
          (.close (:ds mm)))))))

(deftest capture-not-started-when-flag-disabled-test
  (testing "default config (flag missing/false) does NOT start capture"
    (let [mm (make-mm)
          a  (agent/create-agent "u" "s" "agent-no-cap"
                                 :memory-manager mm
                                 :config {:name "NoCap"})]
      (try
        (agent/start-agent a)
        (is (not (mem/capture-running? mm)))
        (.close a)
        (is (not (mem/capture-running? mm)))
        (finally
          (.close (:ds mm)))))))

;; ============================================================================
;; Capture lifetime spans multiple agents on the same manager
;; ============================================================================

(deftest capture-stops-only-after-last-agent-closes-test
  (testing "two agents sharing one mm; capture stops only after both close"
    (let [mm (make-mm)
          a1 (agent/create-agent "u" "s" "agent-a"
                                 :memory-manager mm
                                 :config {:name "A"
                                          :enable-memory-capture true})
          a2 (agent/create-agent "u" "s" "agent-b"
                                 :memory-manager mm
                                 :config {:name "B"
                                          :enable-memory-capture true})]
      (try
        (agent/start-agent a1)
        (agent/start-agent a2)
        (is (mem/capture-running? mm)
            "capture is running after first agent")
        (.close a1)
        (is (mem/capture-running? mm)
            "capture stays running while a sibling is alive")
        (.close a2)
        (is (not (mem/capture-running? mm))
            "capture stops only after the last agent on the manager closes")
        (finally
          (.close (:ds mm)))))))

;; ============================================================================
;; Manager shutdown is a safety net for capture
;; ============================================================================

(deftest manager-shutdown-stops-capture-test
  (testing "mem/shutdown auto-stops a running capture pipeline"
    (let [mm (make-mm)]
      (try
        (mem/start-capture! mm)
        (is (mem/capture-running? mm))
        (mem/shutdown mm)
        (is (not (mem/capture-running? mm))
            "shutdown must stop capture as a safety net")
        (finally
          (.close (:ds mm)))))))

;; ============================================================================
;; create-agent path — flag arrives via :st-memory-init :config
;; (this is what setup-agent does when extracting defagent metadata + caller options).
;; ============================================================================

(deftest capture-honors-flag-from-st-mem-config-test
  (testing "create-agent picks up :enable-memory-capture from st-memory-init :config"
    (let [mm (make-mm)
          a  (agent/create-agent
              "u" "s" "agent-rc"
              :memory-manager mm
              :config         {:name "RC"}
              :st-memory-init {:config {:enable-memory-capture true}})]
      (try
        (is (mem/capture-running? mm)
            "capture must start when flag lives in st-memory-init :config")
        (.close a)
        (is (not (mem/capture-running? mm)))
        (finally
          (.close (:ds mm))))))

  (testing "missing flag in either place leaves capture off"
    (let [mm (make-mm)
          a  (agent/create-agent
              "u" "s" "agent-rc-off"
              :memory-manager mm
              :config         {:name "RC-off"}
              :st-memory-init {:config {}})]
      (try
        (is (not (mem/capture-running? mm)))
        (.close a)
        (finally
          (.close (:ds mm)))))))

;; ============================================================================
;; setup-agent path — flag arriving via defagent's :config-extra
;; ============================================================================

(deftest capture-honors-flag-from-config-extra-test
  (testing "setup-agent threads :config-extra into :config and capture starts"
    (let [mm (make-mm)
          ;; setup-agent now requires a keyword :id (or routing via a registered
          ;; defagent that supplies :_deftool$id). Passing a string raised
          ;; "could not resolve a valid instance-id" — the legacy behavior of
          ;; coercing strings was removed.
          a  (agent-core/setup-agent
              :id :agent-ce
              :agent-session {:user-id "u" :session-id "s"}
              :config-extra {:enable-memory-capture true}
              :memory-opts {})]
      (try
        ;; setup-agent uses get-or-create-agent which calls create-agent
        ;; without passing :memory-manager, so we get a fresh in-memory mm.
        ;; Pull it off the agent state and test capture status there.
        (let [created-mm (:memory-manager @(:!state a))]
          (is (mem/capture-running? created-mm)
              ":config-extra {:enable-memory-capture true} must start capture")
          (.close a)
          (is (not (mem/capture-running? created-mm))))
        (finally
          (.close (:ds mm)))))))

(deftest capture-honors-flag-from-defagent-meta-test
  (testing "setup-agent treats top-level :enable-memory-capture (defagent meta) as a config-schema override"
    (let [a (agent-core/setup-agent
             :id :agent-meta
             :agent-session {:user-id "u" :session-id "s"}
             :enable-memory-capture true
             :memory-opts {})]
      (try
        (let [created-mm (:memory-manager @(:!state a))]
          (is (mem/capture-running? created-mm)
              "top-level :enable-memory-capture (config-schema key) must start capture")
          (.close a)
          (is (not (mem/capture-running? created-mm))))
        (finally
          nil)))))

;; ============================================================================
;; defagent + caller both supplying :config-extra (TUI scenario)
;; ============================================================================

(ai.brainyard.agent.core.tool/defagent test-merge-agent
  "Test agent for verifying :config-extra deep-merge."
  agent-core/run-agent
  :memory-opts {}
  :config-extra {:enable-memory-capture true})

(deftest config-extra-deep-merge-with-caller-test
  (testing "caller :config-extra (e.g. TUI :working-dir) does NOT clobber defagent's :config-extra"
    ;; This mirrors what the TUI does: it calls invoke-tool / setup-agent
    ;; with its own :config-extra. Before the deep-merge fix, that map
    ;; replaced the defagent's, so :enable-memory-capture from the author
    ;; silently vanished.
    ;;
    ;; Post-consolidation routing: setup-agent splits :config-extra into
    ;;   - non-schema keys (:working-dir, :permissions) → @!state :config
    ;;   - schema keys (:enable-memory-capture) → st-memory-init :config
    ;; This test confirms BOTH halves survive the caller-side deep-merge.
    (let [a (agent/invoke-tool :test-merge-agent
                               {:id            :merge-agent-1
                                :agent-session {:user-id "u" :session-id "s"}
                                :setup-only?   true
                                :config-extra  {:working-dir "/tmp"
                                                :permissions {}}})]
      (try
        (let [state-cfg  (:config @(:!state a))
              schema-cfg (some-> (:st-memory-init @(:!state a)) deref :config)
              created-mm (:memory-manager @(:!state a))]
          ;; Caller's non-schema keys present in agent-record :config slot.
          (is (= "/tmp" (:working-dir state-cfg)))
          (is (= {} (:permissions state-cfg)))
          ;; Author's schema key preserved across the merge, lands in
          ;; st-memory-init :config.
          (is (true? (:enable-memory-capture schema-cfg))
              ":enable-memory-capture from defagent's :config-extra must survive into st-memory-init :config")
          (is (mem/capture-running? created-mm)
              "Capture must start because the schema override carries :enable-memory-capture true")
          (.close a)
          (is (not (mem/capture-running? created-mm))))
        (finally
          ;; Only remove this test's defagent — never reset the whole
          ;; registry, which would wipe out the production defagent
          ;; entries that other tests depend on.
          (swap! tool/!tool-defs dissoc :test-merge-agent)
          (agent/reset-agent-registry!))))))
