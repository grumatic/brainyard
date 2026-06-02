;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.mcp.commands
  "MCP management commands for agent system.

   Three polymorphic commands group all MCP operations:
   - `mcp$server`    — inspect servers (list, info, config, capabilities,
                       resources, prompts, health)
   - `mcp$tools`     — work with MCP tools / resources / prompts
                       (list, call, read-resource, get-prompt)
   - `mcp$lifecycle` — start / stop / restart a server"
  (:require [ai.brainyard.agent.core.tool :refer [defcommand]]
            [ai.brainyard.agent.mcp.integration :as mcp-int]
            [ai.brainyard.agent.mcp.client :as mcp-client]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [ai.brainyard.mulog.interface :as mulog]))

;; ============================================================================
;; Helpers — one private fn per atomic operation.
;; ============================================================================

(defn- blank-server-name-error []
  {:error "server-name is required"})

(defn- disconnected-result [server-name]
  {:result {:name server-name :status :disconnected
            :message "Server is not connected"}})

(defn- with-active-server
  "Run f on server-name when it's connected; otherwise return a disconnected
   result. f returns the success map."
  [server-name f]
  (if (str/blank? server-name)
    (blank-server-name-error)
    (let [active (set (mcp-client/list-active-clients))]
      (if (contains? active server-name)
        (f)
        (disconnected-result server-name)))))

(defn- do-list-servers []
  (try
    (let [configured (mcp-int/list-configured-servers)
          active (set (mcp-client/list-active-clients))
          servers (mapv (fn [server-name]
                          (let [config (mcp-int/get-mcp-server-config server-name)]
                            {:name server-name
                             :connected (contains? active server-name)
                             :transport (:transport config)}))
                        configured)]
      {:result {:servers servers
                :total (count servers)
                :connected (count (filter :connected servers))}})
    (catch Exception e
      (mulog/error ::list-servers-failed :error (ex-message e))
      {:error (str "Failed to list MCP servers: " (ex-message e))})))

(defn- do-server-config [server-name]
  (if (str/blank? server-name)
    (blank-server-name-error)
    (if-let [config (mcp-int/get-mcp-server-config server-name)]
      {:result {:name server-name :config config}}
      {:error (format "MCP server '%s' not found in configuration" server-name)})))

(defn- do-server-info [server-name]
  (with-active-server server-name
    (fn []
      (try
        {:result {:name server-name :server-info (mcp-int/get-server-info server-name)}}
        (catch Exception e
          {:error (format "Failed to get server info for '%s': %s" server-name (ex-message e))})))))

(defn- do-server-capabilities [server-name]
  (with-active-server server-name
    (fn []
      (try
        {:result {:name server-name :capabilities (mcp-int/get-server-capabilities server-name)}}
        (catch Exception e
          {:error (format "Failed to get capabilities for '%s': %s" server-name (ex-message e))})))))

(defn- do-server-resources [server-name]
  (with-active-server server-name
    (fn []
      (try
        {:result {:name server-name :resources (mcp-int/list-server-resources server-name)}}
        (catch Exception e
          {:error (format "Failed to list resources for '%s': %s" server-name (ex-message e))})))))

(defn- do-server-prompts [server-name]
  (with-active-server server-name
    (fn []
      (try
        {:result {:name server-name :prompts (mcp-int/list-server-prompts server-name)}}
        (catch Exception e
          {:error (format "Failed to list prompts for '%s': %s" server-name (ex-message e))})))))

(defn- do-server-health [server-name]
  (with-active-server server-name
    (fn []
      (try
        (let [health (get (mcp-int/mcp-health-check) server-name)]
          {:result (assoc health :name server-name)})
        (catch Exception e
          {:error (format "Failed to health-check '%s': %s" server-name (ex-message e))})))))

(defn- do-read-resource [server-name resource-uri]
  (cond
    (str/blank? server-name)  (blank-server-name-error)
    (str/blank? resource-uri) {:error "resource-uri is required"}
    :else (with-active-server server-name
            (fn []
              (try
                {:result {:name server-name :uri resource-uri
                          :resource (mcp-int/read-server-resource server-name resource-uri)}}
                (catch Exception e
                  {:error (format "Failed to read resource '%s' from '%s': %s"
                                  resource-uri server-name (ex-message e))}))))))

(defn- coerce-prompt-arguments [a]
  (cond
    (map? a) a
    (and (string? a) (not (str/blank? a)))
    (try (json/read-str a :key-fn keyword)
         (catch Exception _ {}))
    :else {}))

(defn- do-get-prompt [server-name prompt-name arguments]
  (let [args (coerce-prompt-arguments arguments)]
    (cond
      (str/blank? server-name) (blank-server-name-error)
      (str/blank? prompt-name) {:error "prompt-name is required"}
      :else (with-active-server server-name
              (fn []
                (try
                  {:result {:name server-name :prompt-name prompt-name
                            :prompt (mcp-int/get-server-prompt server-name prompt-name args)}}
                  (catch Exception e
                    {:error (format "Failed to get prompt '%s' from '%s': %s"
                                    prompt-name server-name (ex-message e))})))))))

(defn- do-start-server [server-name]
  (if (str/blank? server-name)
    (blank-server-name-error)
    (if-let [config (mcp-int/get-mcp-server-config server-name)]
      (try
        (mcp-int/start-mcp-server! server-name config)
        {:result (format "MCP server '%s' started successfully" server-name)}
        (catch Exception e
          (mulog/error ::start-server-failed :server server-name :error (ex-message e))
          {:error (format "Failed to start MCP server '%s': %s" server-name (ex-message e))}))
      {:error (format "MCP server '%s' not found in configuration" server-name)})))

(defn- do-stop-server [server-name]
  (if (str/blank? server-name)
    (blank-server-name-error)
    (try
      (mcp-int/stop-mcp-server! server-name)
      {:result (format "MCP server '%s' stopped successfully" server-name)}
      (catch Exception e
        (mulog/error ::stop-server-failed :server server-name :error (ex-message e))
        {:error (format "Failed to stop MCP server '%s': %s" server-name (ex-message e))}))))

(defn- do-restart-server [server-name]
  (if (str/blank? server-name)
    (blank-server-name-error)
    (if (mcp-int/get-mcp-server-config server-name)
      (try
        (mcp-int/reconnect-mcp-server! server-name)
        {:result (format "MCP server '%s' restarted successfully" server-name)}
        (catch Exception e
          (mulog/error ::restart-server-failed :server server-name :error (ex-message e))
          {:error (format "Failed to restart MCP server '%s': %s" server-name (ex-message e))}))
      {:error (format "MCP server '%s' not found in configuration" server-name)})))

(defn- do-list-tools [server-name refresh?]
  (try
    (let [tool-list (if (and server-name (not (str/blank? server-name)))
                      (mcp-int/cached-server-tools server-name (boolean refresh?))
                      (mcp-int/cached-all-server-tools (boolean refresh?)))]
      {:result {:tools (vec tool-list)
                :total (count tool-list)}})
    (catch Exception e
      (mulog/error ::list-tools-failed :error (ex-message e))
      {:error (str "Failed to list MCP tools: " (ex-message e))})))

(defn- normalize-tool-args
  "Convert tool-args from LLM format to flat argument map.
   Handles two formats:
   - Standard: [{:name \"x\" :value v}, ...] -> {\"x\" v}
   - Compact:  [{:param-key value, ...}]     -> {\"param-key\" value}
   Also handles tool-args passed as a flat map (e.g. {:node_names [\"Alice\"]})."
  [tool-args]
  (cond
    (and (map? tool-args) (not (vector? tool-args)))
    (reduce-kv (fn [acc k v] (assoc acc (name k) v)) {} tool-args)

    :else
    (let [tool-args (if (vector? tool-args) tool-args [])
          valid-tool-args (filterv #(and (:name %) (contains? % :value)) tool-args)]
      (if (seq valid-tool-args)
        (reduce #(assoc %1 (:name %2) (:value %2)) {} valid-tool-args)
        (reduce (fn [acc m]
                  (reduce-kv (fn [a k v] (assoc a (name k) v)) acc m))
                {} tool-args)))))

(defn- parse-tool-calls
  "Parse and normalize tool-calls from LLM input.
   Handles: native vector, JSON string, or EDN string.
   Keywordizes all map keys so destructuring with :keys works."
  [tool-calls]
  (let [parsed (cond
                 (vector? tool-calls) tool-calls
                 (string? tool-calls) (try (json/read-str tool-calls)
                                           (catch Exception _
                                             (try (read-string tool-calls)
                                                  (catch Exception _ nil))))
                 :else nil)]
    (when (and (vector? parsed) (seq parsed))
      (mapv #(if (map? %) (walk/keywordize-keys %) %) parsed))))

(defn- do-call-tools [tool-calls-input]
  (let [tool-calls (parse-tool-calls tool-calls-input)]
    (if (or (nil? tool-calls) (empty? tool-calls))
      {:error "tool-calls is required and must be a non-empty vector of {:server-name :tool-name :tool-args} maps"}
      (try
        (let [results (mapv (fn [{:keys [server-name tool-name] :as tool-call}]
                              (let [tool-args (or (:tool-args tool-call)
                                                  (:parameters tool-call)
                                                  (:args tool-call)
                                                  (:arguments tool-call))]
                                (cond
                                  (str/blank? server-name)
                                  {:server-name server-name :tool-name tool-name :tool-args tool-args
                                   :tool-result {:error "server-name is required"}}

                                  (str/blank? tool-name)
                                  {:server-name server-name :tool-name tool-name :tool-args tool-args
                                   :tool-result {:error "tool-name is required"}}

                                  :else
                                  (let [args-map (normalize-tool-args tool-args)
                                        result (try
                                                 (mcp-int/call-server-tool server-name tool-name args-map)
                                                 (catch Exception e
                                                   {:error (ex-message e)}))]
                                    {:server-name server-name
                                     :tool-name tool-name
                                     :tool-args tool-args
                                     :tool-result result}))))
                            tool-calls)]
          {:result {:tool-results results
                    :total (count results)}})
        (catch Exception e
          (mulog/error ::execute-tool-calls-failed :error (ex-message e))
          {:error (str "Failed to execute MCP tool calls: " (ex-message e))})))))

;; ============================================================================
;; Polymorphic commands — preferred surface
;; ============================================================================

(defcommand mcp$server
  "Inspect MCP servers. Pick the operation via `:op`.

   :list          → no other args. Returns
                    `{:result {:servers [...] :total N :connected N}}`.
   :info          → needs :server-name. Returns server name/version.
   :config        → needs :server-name. Returns the configured entry.
   :capabilities  → needs :server-name. Returns declared capabilities.
   :resources     → needs :server-name. Returns the server's resource list.
   :prompts       → needs :server-name. Returns the server's prompt list.
   :health        → needs :server-name. Pings a connected server.

   When the targeted server is not currently connected, returns
   `{:result {:name <s> :status :disconnected :message ...}}` rather than
   an error."
  (fn [& {:keys [op server-name]}]
    (case (some-> op keyword)
      :list         (do-list-servers)
      :info         (do-server-info server-name)
      :config       (do-server-config server-name)
      :capabilities (do-server-capabilities server-name)
      :resources    (do-server-resources server-name)
      :prompts      (do-server-prompts server-name)
      :health       (do-server-health server-name)
      nil           {:error ":op is required (one of :list :info :config :capabilities :resources :prompts :health)"}
      {:error (format "Unknown :op '%s'. Valid: :list :info :config :capabilities :resources :prompts :health" op)}))
  :input-schema  [:map
                  [:op          [:enum {:desc "Operation: list | info | config | capabilities | resources | prompts | health"}
                                 "list" "info" "config" "capabilities" "resources" "prompts" "health"]]
                  [:server-name {:optional true} [:string {:desc "MCP server name (required for every op except :list)"}]]]
  :output-schema [:map
                  [:result [:string {:desc "Operation result. Shape depends on :op"}]]
                  [:error  [:string {:desc "Error message if validation failed or the underlying call threw"}]]])

(defcommand mcp$tools
  "Inspect or invoke MCP tools, resources, and prompts. Pick the action via `:op`.

   :list          → list available MCP tools across servers. Optional
                    :server-name filters to a single server. Served from the
                    connect-time cache (no RPC); pass :refresh true to force a
                    live tools/list re-fetch.
   :call          → execute one or more MCP tool calls. Required:
                    :tool-calls — a vector of `{:server-name :tool-name :tool-args}`
                    maps. Each call's result is returned in the same order
                    inside `{:result {:tool-results [...] :total N}}`.
   :read-resource → read a resource. Required: :server-name + :resource-uri.
   :get-prompt    → render a prompt. Required: :server-name + :prompt-name.
                    Optional: :arguments (map or JSON string)."
  (fn [& {:keys [op server-name tool-calls resource-uri prompt-name arguments refresh]}]
    (case (some-> op keyword)
      :list          (do-list-tools server-name refresh)
      :call          (do-call-tools tool-calls)
      :read-resource (do-read-resource server-name resource-uri)
      :get-prompt    (do-get-prompt server-name prompt-name arguments)
      nil            {:error ":op is required (one of :list :call :read-resource :get-prompt)"}
      {:error (format "Unknown :op '%s'. Valid: :list :call :read-resource :get-prompt" op)}))
  :input-schema  [:map
                  [:op           [:enum {:desc "Operation: list | call | read-resource | get-prompt"}
                                  "list" "call" "read-resource" "get-prompt"]]
                  [:server-name  {:optional true} [:string {:desc "MCP server name (optional for :list, required for the others)"}]]
                  [:tool-calls   {:optional true} [:vector {:desc "For :call — vector of {:server-name :tool-name :tool-args} maps"}
                                                   [:map
                                                    [:server-name :string]
                                                    [:tool-name :string]
                                                    [:tool-args {:optional true} [:maybe [:map-of :any :any]]]]]]
                  [:resource-uri {:optional true} [:string {:desc "For :read-resource — the resource URI"}]]
                  [:prompt-name  {:optional true} [:string {:desc "For :get-prompt — the prompt name"}]]
                  [:arguments    {:optional true} [:string {:desc "For :get-prompt — arguments map (object literal or JSON string)"}]]
                  [:refresh      {:optional true} [:boolean {:desc "For :list — force a live tools/list re-fetch instead of the connect-time cache"}]]]
  :output-schema [:map
                  [:result [:string {:desc "Operation result. Shape depends on :op"}]]
                  [:error  [:string {:desc "Error message if validation failed or the underlying call threw"}]]])

(defcommand mcp$lifecycle
  "Start, stop, or restart a configured MCP server.

   :op          — :start | :stop | :restart (required)
   :server-name — server name (required)"
  (fn [& {:keys [op server-name]}]
    (case (some-> op keyword)
      :start   (do-start-server server-name)
      :stop    (do-stop-server server-name)
      :restart (do-restart-server server-name)
      nil      {:error ":op is required (one of :start :stop :restart)"}
      {:error (format "Unknown :op '%s'. Valid: :start :stop :restart" op)}))
  :input-schema  [:map
                  [:op          [:enum {:desc "Operation: start | stop | restart"}
                                 "start" "stop" "restart"]]
                  [:server-name [:string {:desc "MCP server name (required)"}]]]
  :output-schema [:map
                  [:result [:string {:desc "Success message"}]]
                  [:error  [:string {:desc "Error message if failed"}]]])

;; ============================================================================
;; Command Categories
;; ============================================================================

(def mcp-commands
  "Polymorphic MCP commands — the canonical surface for agent configs."
  [#'mcp$server
   #'mcp$tools
   #'mcp$lifecycle])

(def all-mcp-commands
  "All MCP commands."
  mcp-commands)
