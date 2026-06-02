;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.common.sandbox-meta
  "Metadata catalog for sandbox binding functions.
   Used by TUI and web app to build interactive submenus for /sandbox command."
  (:require [clojure.string]))

(def sandbox-functions
  "Curated sandbox functions with metadata for interactive menu display.
   :args :none = no arguments, execute immediately.
   :args \"<desc>\" = user must supply arguments.
   :code-template = Clojure code string; %s is replaced by user args."
  [{:name "context-index"          :category :context :description "Overview of available context"     :args :none :code-template "(context-index)"}
   {:name "agent-info"              :category :context :description "Agent identity and status"         :args :none :code-template "(context-get [:agent-state :info])"}
   {:name "agent-config"            :category :context :description "Agent configuration"               :args :none :code-template "(context-get [:agent-state :config])"}
   {:name "runtime"                 :category :context :description "Live runtime state (introspect-fn)" :args :none :code-template "(context-get [:agent-state :runtime])"}
   {:name "pending-tasks"           :category :context :description "Snapshot of in-flight task-manager tasks (covers :clj-sandbox-eval + bash + tool)" :args :none :code-template "((:pending-tasks-fn (context-get [:agent-state :runtime])))"}
   ;; Knowledge management
   {:name "agent-knowledge$update"  :category :knowledge :description "Set/update a knowledge section"   :args "<field> <section> <content>" :code-template "(agent-knowledge$update {:field \"%s\" :section \"name\" :content \"...\"})"}
   {:name "agent-knowledge$remove"  :category :knowledge :description "Remove a knowledge section"       :args "<field> <section>" :code-template "(agent-knowledge$remove {:field \"%s\" :section \"name\"})"}
   {:name "agent-knowledge$list"    :category :knowledge :description "List all knowledge sections"      :args :none :code-template "(agent-knowledge$list {})"}
   {:name "list-tools"             :category :tools   :description "List available tools"              :args :none :code-template "(list-tools)"}
   {:name "get-tool-info"          :category :tools   :description "Tool schema details"               :args "<tool-id>" :code-template "(get-tool-info \"%s\")"}
   {:name "mcp$server"             :category :mcp     :description "Inspect MCP servers (op: list/info/config/capabilities/resources/prompts/health)" :args "<op> [<server-name>]" :code-template "(mcp$server :op \"%s\")"}
   {:name "mcp$tools"              :category :mcp     :description "Inspect/invoke MCP tools (op: list/call/read-resource/get-prompt)" :args "<op> [...]" :code-template "(mcp$tools :op \"%s\")"}
   {:name "mcp$lifecycle"          :category :mcp     :description "Start/stop/restart an MCP server" :args "<op> <server-name>" :code-template "(mcp$lifecycle :op \"%s\" :server-name \"\")"}
   ;; Doc (todo + plan) — polymorphic CRUD surface
   {:name "doc$list"               :category :doc     :description "List todos or plans"               :args "<kind=todo|plan>"  :code-template "(doc$list {:kind \"%s\"})"}
   {:name "doc$read"               :category :doc     :description "Read a todo or plan by slug"       :args "<kind> <slug>"     :code-template "(doc$read {:kind \"%s\" :slug \"<slug>\"})"}
   {:name "doc$create"             :category :doc     :description "Create a todo (with :goal/:items) or plan (with :body)" :args "<kind> <title>" :code-template "(doc$create {:kind \"%s\" :title \"<title>\"})"}
   {:name "doc$update"             :category :doc     :description "Update one sub-op: :status, :goal (todo), :body (plan), :item-idx + :item-done (todo), or :add-item (todo)" :args "<kind> <slug> <sub-op>" :code-template "(doc$update {:kind \"%s\" :slug \"<slug>\" :status \"completed\"})"}
   {:name "doc$delete"             :category :doc     :description "Delete a todo or plan by slug"     :args "<kind> <slug>"     :code-template "(doc$delete {:kind \"%s\" :slug \"<slug>\"})"}
   ;; Skill management
   {:name "skills$list"            :category :skill   :description "List skills"                       :args :none :code-template "(skills$list {})"}
   {:name "skills$find"            :category :skill   :description "Search skills by query"            :args "<query>" :code-template "(skills$find {:query \"%s\"})"}
   {:name "skills$read"            :category :skill   :description "Read skill SKILL.md + metadata"    :args "<name>" :code-template "(skills$read {:skill-name \"%s\"})"}
   {:name "skills$write"           :category :skill   :description "Mutate a skill (op: create | update | remove)" :args "<op> <name>" :code-template "(skills$write {:op \"%s\" :skill-name \"<name>\"})"}
   {:name "inspect"                :category :inspect :description "Pretty-print data structure"       :args "<value>" :code-template "(inspect %s)"}
   {:name "show-vars"              :category :inspect :description "User-defined sandbox variables"    :args :none :code-template "(show-vars)"}
   {:name "sandbox-help"           :category :inspect :description "Available namespaces & commands"   :args :none :code-template "(sandbox-help)"}
   {:name "search"                 :category :memory  :description "Search files/config/memory/tools"  :args "<query>" :code-template "(search \"%s\")"}
   {:name "log$turns"              :category :exec    :description "List turns with event counts"       :args :none :code-template "(log$turns)"}
   {:name "log$events"             :category :exec    :description "Get events for a turn"             :args "[turn-id]" :code-template "(log$events :turn-id %s)"}
   {:name "log$search"             :category :exec    :description "Search events by keyword"          :args "<query>" :code-template "(log$search :query \"%s\")"}
   ;; File & URL operations
   {:name "bash"                   :category :file    :description "Run a shell command (use for tree/find/ls/git/rg/etc.)" :args "<command>" :code-template "(bash \"%s\")"}
   {:name "read-file"              :category :file    :description "Read file content"                   :args "<path>" :code-template "(read-file \"%s\")"}
   {:name "write-file"             :category :file    :description "Write to /tmp or .brainyard"         :args "<path> <content>" :code-template "(write-file \"%s\" \"content\")"}
   {:name "grep"                   :category :file    :description "Search file contents by regex"       :args "<pattern> <path>" :code-template "(grep \"%s\" \".\")"}
   {:name "fetch-url"              :category :file    :description "Fetch URL content"                   :args "<url>" :code-template "(fetch-url \"%s\")"}])

(def sandbox-menu-items
  "Pre-formatted menu items as [[display-text description] ...] for TUI/web menus."
  (mapv (fn [{:keys [name description category]}]
          [(str "/sandbox " name)
           (str "(" (clojure.core/name category) ") " description)])
        sandbox-functions))

(defn sandbox-fn-by-name
  "Look up a sandbox function entry by its :name field."
  [fn-name]
  (first (filter #(= (:name %) fn-name) sandbox-functions)))

(defn format-sandbox-help
  "Format all sandbox functions grouped by category as plain text."
  []
  (let [grouped (group-by :category sandbox-functions)
        category-order [:context :knowledge :tools :mcp :doc :skill :inspect :memory :config :file :exec]]
    (str "Sandbox Functions:\n"
         (clojure.string/join
          "\n"
          (for [cat category-order
                :let [fns (get grouped cat)]
                :when (seq fns)]
            (str "  [" (name cat) "]\n"
                 (clojure.string/join
                  "\n"
                  (map (fn [{:keys [name args description]}]
                         (str "    " name
                              (when (not= args :none) (str " " args))
                              "  — " description))
                       fns))))))))
