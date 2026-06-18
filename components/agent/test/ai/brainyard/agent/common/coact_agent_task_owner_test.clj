;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.common.coact-agent-task-owner-test
  "The in-flight task surfaces (roster / harvest / resolve) must be scoped to
   the agent instance that created each task. Otherwise a sub-agent (e.g. a
   skill-agent) surfaces the PARENT task that wraps it and reacts to it as if
   it were its own background work. `task-owned-by-agent?` is the predicate
   that scopes them; ownership keys on the per-instance agent-id (a sub-agent
   inherits its parent's session-id, so session-id can't distinguish them)."
  (:require [clojure.test :refer [deftest is testing]]
            [ai.brainyard.agent.common.coact-agent :as coact]
            [ai.brainyard.agent.core.protocol :as proto]))

(defn- stub-agent [aid]
  (reify proto/IAgent
    (agent-id [_] aid)))

(def ^:private owned-by? #'coact/task-owned-by-agent?)

(deftest task-owned-by-agent-scopes-by-instance-id
  (let [a-x      (stub-agent :coact-agent/x)
        a-y      (stub-agent :coact-agent/y)
        untagged {:metadata {}}
        owned-x  {:metadata {:coact/owner-agent-id :coact-agent/x}}]
    (testing "untagged (legacy) tasks stay visible to everyone"
      (is (true? (boolean (owned-by? untagged a-x))))
      (is (true? (boolean (owned-by? untagged a-y)))))
    (testing "a tagged task is visible only to its owning agent instance"
      (is (true?  (boolean (owned-by? owned-x a-x))))
      (is (false? (boolean (owned-by? owned-x a-y)))
          "the parent task (owner :x) is filtered out of agent :y's surfaces"))
    (testing "nil agent disables the filter (e.g. global /tasks views)"
      (is (true? (boolean (owned-by? owned-x nil)))))))
