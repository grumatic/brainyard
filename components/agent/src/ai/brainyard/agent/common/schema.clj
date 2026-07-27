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

   ;; A plain JSON object (MCP-style), NOT a name/value pair-list. The pair-list
   ;; shape induced malformed LLM emissions; a bare object is what models emit
   ;; naturally and matches the code-block channel's native Clojure map. `:any`
   ;; keys mirror MCP's `[:map-of :any :any]` and decouple this from the DSPy
   ;; parser's key-fn — `call-tool` keywordizes top-level keys downstream.
   ::tool-args [:map-of {:desc "Tool arguments as a JSON object, e.g. {\"url\": \"…\", \"n\": 3}. Use {} for no arguments."}
                :any :any]

   ::tool-call [:map {:desc "One tool invocation"}
                [:tool-name ::tool-name]
                [:tool-args ::tool-args]]

   ::tool-calls [:vector {:desc "Tool invocations to run this iteration. Empty when not calling tools (using another channel or answering)."}
                 ::tool-call]

   ;; Shared "object argument" type for a field the LLM may supply EITHER as a
   ;; native map (code-block / Clojure channel) OR as a JSON/EDN object string
   ;; (tool-calls channel) — the tool self-parses the string. Same tension the
   ;; task$run :tool-args [:or …] resolves. Reference it WITH a field-specific
   ;; description via a :schema wrapper so the arg's own doc survives:
   ;;   [:my-arg {:optional true} [:schema {:desc "…"} ::object-arg]]
   ::object-arg [:or [:string] [:map]]

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
