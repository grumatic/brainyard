;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent-tui.colon-agent-dispatch-test
  "Dispatching a defagent from the console — `:explore-agent :question \"…\"`.

   `invoke-tool` is the BARE dispatcher: no hooks, no permissions, and no agent
   bookkeeping either. Every LLM-facing path injects `:agent-session` before the
   tool-fn sees it (`do-call-tool--agent` from the calling agent,
   `do-call-tool--bound-fn` likewise, `bind-tools`' wrapper from
   `*current-agent*`), so an `:agent`-type tool always arrives with the scope it
   needs. The colon-command path called `invoke-tool` directly and injected
   nothing, so `setup-agent` destructured a nil user-id out of a missing
   `:agent-session` and the dispatch died two layers down in
   `UnifiedStore requires :user-id`.

   Every agent-type colon-command was broken; command/skill/tool ones were fine,
   because they need no session scope. Both halves are covered here: the console
   supplies the scope, and `setup-agent` names what is missing if anyone else
   ever doesn't."
  (:require [ai.brainyard.agent-tui.commands :as commands]
            [ai.brainyard.agent.interface :as agent]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(defn- invoke!
  "Drive the colon-command path for `tool-def`, capturing what reached
   `invoke-tool` instead of dispatching for real."
  [tool-id tool-def args]
  (let [seen (atom nil)]
    (with-redefs [agent/invoke-tool (fn [id & {:as opts}] (reset! seen [id opts]) {:ok true})]
      (#'commands/handle-tool-invoke tool-id tool-def args))
    @seen))

(def ^:private agent-tool
  {:type :agent
   :meta {:input-schema [:map [:question [:string {:desc "q"}]]]
          :description "a defagent"}})

(def ^:private command-tool
  {:type :command
   :meta {:input-schema [:map [:path [:string {:desc "p"}]]]
          :description "a plain command"}})

(deftest an-agent-type-colon-command-carries-its-session-scope
  (testing ":agent-session is injected, with a non-blank user-id"
    (let [[id opts] (invoke! :explore-agent agent-tool ":question \"hi\"")]
      (is (= :explore-agent id))
      (is (= "hi" (:question opts)) "the user's own args still arrive")
      (is (map? (:agent-session opts))
          "an agent-type tool cannot be constructed without this")
      (is (not (str/blank? (:user-id (:agent-session opts))))
          "and a blank user-id is what UnifiedStore rejects")
      (is (some? (:session-id (:agent-session opts))))
      (is (contains? opts :parent-agent)
          "dispatched like the LLM path does, so routing and lifecycle match"))))

(deftest a-plain-colon-command-is-left-alone
  (testing "command/skill/tool types get no agent bookkeeping"
    ;; They never needed it, and injecting it would put unexpected keys through
    ;; their :input-schema.
    (let [[_ opts] (invoke! :read-file command-tool ":path \"/tmp/x\"")]
      (is (= "/tmp/x" (:path opts)))
      (is (not (contains? opts :agent-session)))
      (is (not (contains? opts :parent-agent))))))

(deftest setup-agent-names-the-missing-scope
  (testing "a caller that skips :agent-session gets told which argument it is"
    ;; The old failure surfaced as `UnifiedStore requires :user-id`, which reads
    ;; like a memory-subsystem fault and sends the diagnosis to the wrong
    ;; component entirely.
    (let [e (try (agent/setup-agent :id :probe-agent/x :bt-factory (fn [_] nil))
                 nil
                 (catch Exception e e))]
      (is (some? e) "it must fail — the agent cannot be built without a user")
      (is (str/includes? (ex-message e) "agent-session")
          (str "expected the message to name :agent-session, got: " (ex-message e)))
      (is (not (str/includes? (ex-message e) "UnifiedStore"))
          "and not to blame the memory store for a caller error"))))
