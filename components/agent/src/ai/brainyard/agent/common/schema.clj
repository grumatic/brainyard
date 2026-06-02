;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.common.schema
  "Common schemas for agent system.
   Ported from cloudcast.backend.agent.common.schema."
  (:require [ai.brainyard.clj-llm.interface :refer [defschemas]]
            [malli.core :as m]))

(defschemas domain
  {::conversation [:vector {:desc "History of conversation"}
                   [:map
                    [:role :string]
                    [:content :string]]]

   ::reasoning-chain [:vector {:desc "Chain of thoughts"} [:string {:desc "LLM thought/reason"}]]

   ::question [:string {:desc "User question"}]

   ::answer [:string {:desc "Answer to the question"}]

   ::instruction [:string {:desc "Instruction to help produce an objective"}]

   ::tool-context [:string {:desc "Context information for using tools"}]

   ::tool-spec [:map
                [:name [:string {:desc "Name of tool function"}]]
                [:description [:string {:desc "Description of tool function"}]]
                [:parameters [:map {:desc "JSON schema for tool function parameters"}
                              [:type [:enum {:desc "object type"} "object"]]
                              [:properties [:map {:desc "object properties"}]]
                              [:required [:vector {:desc "required properties"} :string]]]]]

   ::tools [:vector {:desc "List of available tools"} ::tool-spec]

   ::tool-name [:string {:desc "tool name"}]

   ::tool-args [:vector {:desc "tool arguments. Use empty [] for no arguments."}
                [:map
                 [:name [:string {:desc "argument name"}]]
                 [:value [:string {:desc "argument value"}]]]]

   ::tool-calls [:vector {:desc "tools to call"}
                 [:map
                  [:tool-name ::tool-name]
                  [:tool-args ::tool-args]]]

   ::issues-identified [:vector {:desc "Issues identified from the past tool use"}
                        [:map
                         [:issue [:string {:desc "the description of issue"}]]
                         [:impact [:string {:desc "the impact the issue has on the instruction objective"}]]]]

   ::tool-results [:vector {:desc "Results from tool calls"}
                   [:or
                    [:string {:desc "Compacted results from tool calls"}]
                    [:map
                     [:tool-name ::tool-name]
                     [:tool-args ::tool-args]
                     [:tool-result [:any {:desc "Result of tool call"}]]]]]

   ::tool-use-completed [:boolean {:desc "True if no more action is required in tool use"}]

   ::agent-id [:string {:desc "an agent identifier (e.g., 'xyz$abc', 'xyz$abc/extension')"}]

   ::agent-context [:string {:desc "agent context information"}]

   ::agent-response [:string {:desc "the response of agent"}]

   ::recalled-memory [:string {:desc "Layer-grouped markdown rendering of cross-layer recall hits. Sections (when present): ### Episodes (L2 — recent activity), ### Facts (L3 — long-term), ### System overlays (L1). Empty string when no hits or no memory manager. Treat the rendering as read-only context — do not echo it back verbatim."}]})

(defn get-schema-info [m]
  (cond
    (map? m) (into {} (map (fn [[k v]] [k (-> (m/deref-recursive v) m/form)])) m)
    (vector? m) (mapv #(-> (m/deref-recursive %) m/form) m)
    (keyword? m) (-> (m/deref-recursive m) m/form)
    :else (throw (ex-info "get-schema error!" {:input m}))))
