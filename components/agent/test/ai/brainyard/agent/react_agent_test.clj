;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.react-agent-test
  "Tests for react-agent — now a tool-only projection of the CoAct base
   (the code channel disabled via :code-channel? false). The former parallel
   ReAct signature / BT / assembler were retired; their behavior is covered by
   the CoAct suite (coact_agent_test) plus the :code-channel? flag tests there.
   These tests assert the registration + the unification knob.
   See docs/design/react-coact-unification-plan.md."
  (:require [clojure.test :refer [deftest testing is]]
            [ai.brainyard.agent.common.coact-agent]
            [ai.brainyard.agent.common.react-agent]
            [ai.brainyard.agent.common.agent-roster :as agent-roster]
            [ai.brainyard.agent.core.tool :as tool]))

(deftest registration-test
  (testing "react-agent is registered as an agent in the tool registry"
    (let [defs (tool/get-tool-defs :type :agent)]
      (is (contains? defs :react-agent))
      (let [d (get defs :react-agent)]
        (is (= :react-agent (:id d)))
        (is (= :agent (:type d)))
        (is (some? (:fn d)))))))

(deftest tool-only-config-test
  (testing "react-agent pins :code-channel? false — the unification knob that
            makes it tool-only (no code-blocks channel)"
    (let [d (get (tool/get-tool-defs :type :agent) :react-agent)]
      (is (false? (get-in d [:meta :config-extra :code-channel?]))
          "react-agent must disable the code channel via :config-extra"))))

(deftest shared-roster-test
  (testing "react-agent advertises the single shared roster (identical to coact —
            no drift)"
    (let [d (get (tool/get-tool-defs :type :agent) :react-agent)]
      (is (= agent-roster/default-agent-roster (get-in d [:meta :agent-tools]))))))
