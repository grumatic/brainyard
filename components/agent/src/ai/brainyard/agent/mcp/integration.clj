;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.mcp.integration
  "Integration layer between MCP clients and Brainyard agent framework.

   This namespace provides tools and utilities for agents to interact with MCP servers,
   including tool registration, resource access, and dynamic capability discovery.

   On `connect-mcp-server!` each tool exposed by the server is auto-registered
   in `tool/!tool-defs` under the id `:mcp$<server>$<tool>`. From there the
   sandbox `auto-tool-bindings` produces a `mcp$<server>$<tool>` callable
   automatically, the registry hooks/visibility apply, and the tool surfaces
   in `(list-tools)` / `(get-tool-info)`. `disconnect-mcp-server!` unwinds
   the registration."
  (:require [ai.brainyard.mulog.interface :as mulog]
            [clojure.string :as str]
            [ai.brainyard.agent.mcp.client :as mcp-client]
            [ai.brainyard.agent.core.tool :as tool]
            [ai.brainyard.agent.core.config :as core-config]
            [ai.brainyard.clj-llm.interface :refer [defschemas]]))

;; =============================================================================
;; Validation Helper
;; =============================================================================

(defn- ensure!
  "Validate val with pred, returning val on success or throwing ExceptionInfo on failure."
  ([pred val]
   (ensure! pred val (str "Validation failed: (" pred " " (pr-str val) ")")))
  ([pred val msg]
   (when-not (pred val)
     (throw (ex-info msg {:pred pred :val val})))
   val))

;; =============================================================================
;; MCP Integration Schemas
;; =============================================================================

(defschemas mcp-schemas
  {::mcp-server-config
   [:map
    [:name :string]
    [:transport [:enum :stdio :http :http-sse]]
    [:config :map]
    [:enabled {:optional true} :boolean]
    [:auto-register-tools {:optional true} :boolean]]})

;; =============================================================================
;; MCP Server Configuration Management
;; =============================================================================

(defonce ^:private mcp-servers-config (atom {}))

(defonce ^:private !mcp-initialized (atom false))

(defn mcp-initialized?
  "Return true if the MCP system has been initialized."
  []
  @!mcp-initialized)

(declare connect-mcp-server!
         register-mcp-tools-for-server!
         unregister-mcp-tools-for-server!)

(defn- connect-and-settle!
  "Connect one server, isolating failures, and notify `on-settle` (when given)
   with [server-name :ok nil] on success or [server-name :error throwable] on
   failure — used by the TUI to surface per-server status. Returns the client
   or nil. Runs on the caller's thread; `connect-server-async!` wraps it in a
   future for the background path."
  [server-name server-config on-settle]
  (try
    (let [c (connect-mcp-server! server-name server-config)]
      (when on-settle (on-settle server-name :ok nil))
      c)
    (catch Throwable e
      (mulog/error ::auto-connect-failed
                   :server server-name :error (ex-message e))
      (when on-settle (on-settle server-name :error e))
      nil)))

(defn- connect-server-async!
  "Background-future wrapper around `connect-and-settle!` so a slow/OAuth
   handshake never blocks the caller or the other servers."
  [server-name server-config on-settle]
  (future (connect-and-settle! server-name server-config on-settle)))

(defn load-mcp-servers!
  "Load configuration and auto-connect enabled servers. By default each enabled
   server connects in the BACKGROUND — one daemon future per server — so a slow
   or OAuth-gated server never blocks the others or the startup path. Servers
   flagged `:lazy true` are loaded into the runtime config but NOT connected;
   they connect on demand via `/mcp <name> start`. Returns a summary
   `{:connecting [names] :lazy [names]}`.

   Optional second arg opts:
     :on-settle    (fn [server-name status throwable]) — status is :ok | :error,
                   called when each connect settles.
     :connect-mode :background (default) one future per server, non-blocking;
                   :eager restores the old synchronous, in-order connect (escape
                   hatch for debugging)."
  ([config] (load-mcp-servers! config nil))
  ([config {:keys [on-settle connect-mode] :or {connect-mode :background}}]
   (ensure! map? config "config must be a map")
   (reset! mcp-servers-config config)
   (mulog/info ::servers-config-loaded :servers (keys config))

   (let [enabled (for [[server-name server-config] config
                       :when (:enabled server-config true)]
                   [server-name server-config])
         lazy?   (fn [[_ sc]] (boolean (:lazy sc)))
         deferred (filter lazy? enabled)
         eager    (remove lazy? enabled)]
     (doseq [[server-name server-config] eager]
       (if (= connect-mode :eager)
         (connect-and-settle! server-name server-config on-settle)
         (connect-server-async! server-name server-config on-settle)))
     (when (seq deferred)
       (mulog/info ::servers-deferred-lazy :servers (mapv first deferred)))
     {:connecting (mapv first eager)
      :lazy (mapv first deferred)})))

(defn get-mcp-server-config
  "Get configuration for specific MCP server"
  [server-name]
  (get @mcp-servers-config server-name))

(defn list-configured-servers
  "List all configured MCP servers"
  []
  (keys @mcp-servers-config))

;; =============================================================================
;; AWS Profile Management
;; =============================================================================

(defn- aws-server-name?
  "Returns true if server-name starts with \"aws-\" (e.g. aws-lambda, aws-iac)."
  [server-name]
  (str/starts-with? (str server-name) "aws-"))

(defn update-aws-env-vars!
  "Update AWS_PROFILE and AWS_REGION env vars in all aws-* MCP server configs.
   Only updates keys that already exist in a server's :env map.
   Returns vector of updated server names."
  [profile region]
  (let [updated (atom [])]
    (swap! mcp-servers-config
           (fn [configs]
             (reduce-kv
              (fn [acc server-name server-config]
                (if (and (aws-server-name? server-name)
                         (get-in server-config [:config :env]))
                  (let [env (get-in server-config [:config :env])
                        new-env (cond-> env
                                  (and profile (contains? env "AWS_PROFILE"))
                                  (assoc "AWS_PROFILE" profile)
                                  (and region (contains? env "AWS_REGION"))
                                  (assoc "AWS_REGION" region))]
                    (when (not= env new-env)
                      (swap! updated conj server-name))
                    (assoc acc server-name (assoc-in server-config [:config :env] new-env)))
                  (assoc acc server-name server-config)))
              {}
              configs)))
    (let [result @updated]
      (when (seq result)
        (mulog/info ::aws-env-vars-updated :servers result :profile profile :region region))
      result)))

;; =============================================================================
;; MCP Server Connection Management
;; =============================================================================

(defn connect-mcp-server!
  "Connect to an MCP server using its configuration. Once connected, the
   server's tools are auto-registered in `tool/!tool-defs` so they show up
   as `mcp$<server>$<tool>` sandbox bindings via auto-tool-bindings."
  [server-name server-config]
  (ensure! string? server-name "server-name must be a string")
  (ensure! map? server-config "server-config must be a map")

  (let [{:keys [transport config]} server-config
        client (mcp-client/create-client transport config)
        connected-client (mcp-client/connect! client config)]

    (mcp-client/register-client! server-name connected-client)
    (mulog/info ::server-connected :server server-name :transport transport)

    (try
      (register-mcp-tools-for-server! server-name)
      (catch Exception e
        (mulog/warn ::mcp-tool-registration-batch-failed
                    :server server-name :error (ex-message e))))

    connected-client))

(defn disconnect-mcp-server!
  "Disconnect from an MCP server and unregister any tools it had registered."
  [server-name]
  (try
    (unregister-mcp-tools-for-server! server-name)
    (catch Exception e
      (mulog/warn ::mcp-tool-unregistration-failed
                  :server server-name :error (ex-message e))))
  (mcp-client/unregister-client! server-name)
  (mulog/info ::server-disconnected :server server-name))

(defn reconnect-mcp-server!
  "Reconnect to an MCP server"
  [server-name]
  (when-let [config (get-mcp-server-config server-name)]
    (disconnect-mcp-server! server-name)
    (connect-mcp-server! server-name config)))

;; =============================================================================
;; Direct MCP Tool Access (no registry)
;; =============================================================================

(defn list-server-tools
  "List tools from a specific connected MCP server.
   Returns vector of {:server-name :name :description :parameters}."
  [server-name]
  (when-let [client (mcp-client/get-client server-name)]
    (try
      (let [tools-list (mcp-client/list-tools client)]
        (mapv (fn [tool]
                {:server-name server-name
                 :name (:name tool)
                 :description (:description tool)
                 :parameters (:inputSchema tool)})
              (:tools tools-list)))
      (catch Exception e
        (mulog/error ::list-tools-failed
                     :server server-name :error (ex-message e))
        []))))

(defn list-all-server-tools
  "List tools from all connected MCP servers, optionally filtered by server names.
   Returns vector of {:server-name :name :description :parameters}."
  ([]
   (let [active-clients (mcp-client/list-active-clients)]
     (into [] (mapcat list-server-tools) active-clients)))
  ([server-names]
   (into [] (mapcat list-server-tools) server-names)))

;; --- tools/list cache -------------------------------------------------------
;; `register-mcp-tools-for-server!` already fetches each server's tools at
;; connect time; cache that snapshot so `mcp$tools :op :list` can answer from
;; memory instead of a fresh tools/list RPC on every call. An explicit
;; :refresh re-fetches (and a reconnect re-registers, refreshing the cache).

(defonce ^:private !server-tools-cache
  ;; server-name -> [{:server-name :name :description :parameters} ...]
  (atom {}))

(defn refresh-server-tools!
  "Live tools/list RPC for `server-name`; updates the cache. Returns the vector."
  [server-name]
  (let [tools (list-server-tools server-name)]
    (swap! !server-tools-cache assoc server-name tools)
    tools))

(defn cached-server-tools
  "A server's tools from the connect-time cache; live-fetches + caches on a
   miss. `refresh?` forces a live re-fetch."
  ([server-name] (cached-server-tools server-name false))
  ([server-name refresh?]
   (if refresh?
     (refresh-server-tools! server-name)
     (or (get @!server-tools-cache server-name)
         (refresh-server-tools! server-name)))))

(defn cached-all-server-tools
  "Tools across all connected servers, cache-first. `refresh?` forces live."
  ([] (cached-all-server-tools false))
  ([refresh?]
   (into [] (mapcat #(cached-server-tools % refresh?))
         (mcp-client/list-active-clients))))

(defn call-server-tool
  "Call a tool on a specific MCP server using its native tool name."
  [server-name tool-name arguments]
  (if-let [client (mcp-client/get-client server-name)]
    (try
      (let [result (mcp-client/call-tool client tool-name arguments)]
        {:success true :result result})
      (catch Exception e
        (mulog/error ::tool-call-failed :exception e)
        {:success false :error (ex-message e)}))
    {:success false :error (str "MCP client not found: " server-name)}))

(defn get-server-info
  "Get server information for a specific connected MCP server."
  [server-name]
  (when-let [client (mcp-client/get-client server-name)]
    (mcp-client/get-server-info client)))

(defn get-server-capabilities
  "Get capabilities for a specific connected MCP server."
  [server-name]
  (when-let [client (mcp-client/get-client server-name)]
    (mcp-client/get-capabilities client)))

(defn list-server-resources
  "List resources from a specific connected MCP server."
  [server-name]
  (when-let [client (mcp-client/get-client server-name)]
    (mcp-client/list-resources client)))

(defn list-server-prompts
  "List prompts from a specific connected MCP server."
  [server-name]
  (when-let [client (mcp-client/get-client server-name)]
    (mcp-client/list-prompts client)))

(defn read-server-resource
  "Read a specific resource by URI from a connected MCP server."
  [server-name uri]
  (when-let [client (mcp-client/get-client server-name)]
    (mcp-client/read-resource client uri)))

(defn get-server-prompt
  "Get a specific prompt with arguments from a connected MCP server."
  [server-name prompt-name arguments]
  (when-let [client (mcp-client/get-client server-name)]
    (mcp-client/get-prompt client prompt-name arguments)))

(defn mcp-health-check
  "Perform health check on all MCP servers"
  []
  (let [active-clients (mcp-client/list-active-clients)]
    (into {}
          (for [server-name active-clients]
            [server-name
             (try
               (when-let [client (mcp-client/get-client server-name)]
                 ;; Try to ping the server
                 (mcp-client/send-request! client "ping" {})
                 {:status :healthy :timestamp (System/currentTimeMillis)})
               (catch Exception e
                 {:status :unhealthy
                  :error (ex-message e)
                  :timestamp (System/currentTimeMillis)}))]))))

;; =============================================================================
;; MCP → !tool-defs Auto-Registration
;;
;; Each tool from a connected MCP server is registered in `tool/!tool-defs`
;; under the id `:mcp$<server-name>$<tool-name>`. The registry's auto-binding
;; pipeline then surfaces it as a sandbox callable, OpenAI tool descriptor,
;; etc. — same path as deftool/defcommand entries.
;; =============================================================================

(defonce ^:private dynamic-tools-ns
  (create-ns 'ai.brainyard.agent.mcp.dynamic-tools))

(defonce ^:private !registered-mcp-tool-ids
  ;; {server-name #{:mcp$server$tool ...}}
  (atom {}))

(def ^:private clj-symbol-name-re
  #"[A-Za-z_][A-Za-z0-9_\-.+!?<>=]*")

(defn- safe-symbol-name? [s]
  (boolean (and s (re-matches clj-symbol-name-re (str s)))))

(defn- mcp-tool-id [server-name tool-name]
  (keyword (str "mcp$" server-name "$" tool-name)))

(defn- attach-meta
  "Weave project metadata (:desc/:default/:optional) into a Malli schema's
   props slot so `clj-llm/parse-malli-field` can extract it."
  [schema meta-map]
  (cond
    (empty? meta-map) schema
    (keyword? schema) [schema meta-map]
    (and (vector? schema) (keyword? (first schema)))
    (let [[head & tail] schema]
      (if (and (seq tail) (map? (first tail)))
        (vec (cons head (cons (merge (first tail) meta-map) (rest tail))))
        (vec (cons head (cons meta-map tail)))))
    :else schema))

(defn- json-schema->malli
  "Convert one JSON Schema property spec to a Malli schema. Handles enum,
   primitives, arrays, and nested objects (recursively, honoring per-level
   :required). Falls back to :any for exotic features (oneOf, regex,
   multi-type) — the MCP server still validates server-side."
  ([spec] (json-schema->malli spec false))
  ([spec optional?]
   (let [t        (or (:type spec) (get spec "type"))
         desc     (or (:description spec) (get spec "description"))
         dflt-key (or (find spec :default) (find spec "default"))
         enum*    (or (:enum spec) (get spec "enum"))
         items    (or (:items spec) (get spec "items"))
         props    (or (:properties spec) (get spec "properties"))
         required (set (map name (or (:required spec) (get spec "required") [])))
         meta-map (cond-> {}
                    desc      (assoc :desc desc)
                    dflt-key  (assoc :default (val dflt-key))
                    optional? (assoc :optional true))
         bare     (cond
                    enum*           (vec (cons :enum enum*))
                    (= t "string")  :string
                    (= t "integer") :int
                    (= t "number")  [:or :int :double]
                    (= t "boolean") :boolean
                    (= t "array")   [:vector (if items (json-schema->malli items false) :any)]
                    (= t "object")  (if (seq props)
                                      (into [:map]
                                            (map (fn [[k spec*]]
                                                   (let [k-str (name k)
                                                         opt?  (not (contains? required k-str))
                                                         inner (json-schema->malli spec* opt?)]
                                                     (if opt?
                                                       [(keyword k-str) {:optional true} inner]
                                                       [(keyword k-str) inner]))))
                                            props)
                                      :map)
                    :else :any)]
     (attach-meta bare meta-map))))

(defn- mcp-json-schema->input-schema
  "Convert a top-level MCP inputSchema into a deftool :input-schema (Malli [:map ...]).
   Returns [:map] for missing schemas."
  [schema]
  (let [s          (or schema {})
        properties (or (:properties s) (get s "properties") {})
        required   (set (map name (or (:required s) (get s "required") [])))]
    (into [:map]
          (map (fn [[k spec]]
                 (let [k-str (name k)
                       opt?  (not (contains? required k-str))
                       malli (json-schema->malli spec false)]
                   (if opt?
                     [(keyword k-str) {:optional true} malli]
                     [(keyword k-str) malli]))))
          properties)))

(defn- intern-mcp-tool-var
  "Intern a synthetic var carrying the MCP wrapper fn so def->tool's
   `(meta @tool-fn)` path keeps working. Both var-meta and value-meta carry
   the registry meta map."
  [id meta-info fn-impl]
  (let [vsym (symbol (name id))
        f    (with-meta fn-impl meta-info)]
    (intern dynamic-tools-ns (with-meta vsym meta-info) f)))

(defn- unintern-mcp-tool-var [id]
  (ns-unmap dynamic-tools-ns (symbol (name id))))

(defn- make-mcp-wrapper-fn
  "The :fn body for a registered MCP tool. Receives a flat keyword-map
   (post-validation) and dispatches to call-server-tool with string keys.
   Returns {:error-message ...} on failure to match registry conventions."
  [server-name tool-name]
  (fn [& {:as call-args}]
    (let [string-args (reduce-kv (fn [m k v] (assoc m (name k) v))
                                 {} (or call-args {}))]
      (try
        (let [r (call-server-tool server-name tool-name string-args)]
          (if (:success r)
            (or (:result r) {})
            {:error-message (or (:error r) "MCP tool returned no result")}))
        (catch Exception e
          {:error-message (str "MCP call failed: " (.getMessage e))})))))

(defn unregister-mcp-tools-for-server!
  "Drop all !tool-defs entries previously registered for `server-name` and
   ns-unmap their synthetic vars. Also evicts the tools/list cache. Idempotent."
  [server-name]
  (swap! !server-tools-cache dissoc server-name)
  (when-let [ids (get @!registered-mcp-tool-ids server-name)]
    (swap! tool/!tool-defs #(apply dissoc % ids))
    (doseq [id ids] (unintern-mcp-tool-var id))
    (swap! !registered-mcp-tool-ids dissoc server-name)
    (mulog/info ::mcp-tools-unregistered :server server-name :count (count ids))
    ids))

(defn register-mcp-tools-for-server!
  "Discover tools from a connected MCP server and register each one in
   !tool-defs under id `:mcp$<server>$<tool>`. Always unregisters prior
   entries first, so this is safe to call on reconnect. Tools whose names
   can't form a valid Clojure symbol are skipped (logged)."
  [server-name]
  (unregister-mcp-tools-for-server! server-name)
  (let [tools (try (list-server-tools server-name) (catch Exception _ []))
        ;; Cache the connect-time snapshot so `mcp$tools :op :list` serves
        ;; from memory instead of a live RPC (see cached-server-tools).
        _ (swap! !server-tools-cache assoc server-name tools)
        registered
        (into #{}
              (keep (fn [{:keys [name description parameters]}]
                      (cond
                        (not (safe-symbol-name? server-name))
                        (do (mulog/warn ::mcp-tool-skipped
                                        :reason :bad-server-name
                                        :server server-name)
                            nil)
                        (not (safe-symbol-name? name))
                        (do (mulog/warn ::mcp-tool-skipped
                                        :reason :bad-tool-name
                                        :server server-name :tool name)
                            nil)
                        :else
                        (try
                          (let [id           (mcp-tool-id server-name name)
                                input-schema (mcp-json-schema->input-schema parameters)
                                meta    {:id id
                                         :type :tool
                                         :description (or description "MCP tool")
                                         :input-schema input-schema
                                         :output-schema [:map]
                                         :mcp-server server-name
                                         :mcp-tool name}
                                fn-impl (make-mcp-wrapper-fn server-name name)
                                v       (intern-mcp-tool-var id meta fn-impl)]
                            (swap! tool/!tool-defs assoc id
                                   {:id id :type :tool :fn v :meta meta})
                            id)
                          (catch Exception e
                            (mulog/warn ::mcp-tool-registration-failed
                                        :server server-name :tool name
                                        :error (ex-message e))
                            nil)))))
              tools)]
    (when (seq registered)
      (swap! !registered-mcp-tool-ids assoc server-name registered)
      (mulog/info ::mcp-tools-registered
                  :server server-name :count (count registered)))
    registered))

;; =============================================================================
;; Configuration Loading Utilities
;; =============================================================================

(defn load-mcp-config-from-file
  "Load MCP configuration from a file"
  [config-file]
  (try
    (let [config (mcp-client/load-mcp-servers-config config-file)]
      (load-mcp-servers! config)
      config)
    (catch Exception e
      (mulog/error ::load-config-failed :file config-file :error (ex-message e))
      (throw e))))

(defn create-seed-mcp-config
  "Built-in MCP server definitions. These are deep-merged *under* config.edn
   `[:mcp :servers]` at startup (config.edn wins per leaf); they are never
   written to disk by default."
  []
  {"aws-mcp"
   {:transport :stdio,
    :config
    {:command "/opt/homebrew/bin/uvx",
     :args
     ["mcp-proxy-for-aws@latest"
      "https://aws-mcp.us-east-1.api.aws/mcp"]},
    :enabled false,
    :auto-register-tools true},
   "filesystem"
   {:transport :stdio,
    :config
    {:command "npx",
     :args
     ["-y" "@modelcontextprotocol/server-filesystem" "/private/tmp"]},
    :enabled false,
    :auto-register-tools true},
   "github"
   {:transport :stdio,
    :config
    {:command "npx",
     :args ["@modelcontextprotocol/server-github"],
     :env {"GITHUB_PERSONAL_ACCESS_TOKEN" "your-token-here"}},
    :enabled false,
    :auto-register-tools true},
   "postgres"
   {:transport :stdio,
    :config
    {:command "npx",
     :args ["@modelcontextprotocol/server-postgres"],
     :env
     {"POSTGRES_CONNECTION_STRING"
      "postgresql://user:pass@localhost/db"}},
    :enabled false,
    :auto-register-tools true},
   "api-server"
   {:transport :http,
    :config
    {:url "https://api.example.com",
     :headers
     {"Authorization" "Bearer your-token-here",
      "X-API-Version" "2024-11-05"}},
    :enabled false,
    :auto-register-tools true},
   "clojure-mcp"
   {:transport :stdio,
    :config
    {:command "/opt/homebrew/bin/bash",
     :args
     ["-c"
      "clojure -X:mcp :port 7888 | tee /tmp/clojure-mcp-stdout.log"],
     :working-dir "/tmp"},
    :enabled false,
    :auto-register-tools true},
   "redis"
   {:transport :stdio,
    :config
    {:command "npx",
     :args
     ["-y"
      "@modelcontextprotocol/server-redis"
      "redis://localhost:6379"]},
    :enabled false,
    :auto-register-tools true},
   "playwright"
   {:transport :stdio,
    :config {:command "npx", :args ["@playwright/mcp@latest"]},
    :enabled false,
    :auto-register-tools true}

   ;; notion / linear are remote OAuth servers. brainyard's :http transport
   ;; does a plain initialize POST (no OAuth handshake), which their endpoints
   ;; reject. Bridge through `mcp-remote` (stdio) — it runs the browser OAuth
   ;; flow on first start and proxies to the hosted server.
   "notion"
   {:transport :stdio
    :config {:command "npx"
             :args ["-y" "mcp-remote" "https://mcp.notion.com/mcp"]}
    :enabled false
    :auto-register-tools true}

   "linear"
   {:transport :stdio
    :config {:command "npx"
             :args ["-y" "mcp-remote" "https://mcp.linear.app/sse"]}
    :enabled false
    :auto-register-tools true}

   ;; gmail / google-calendar use Google's official hosted remote MCP servers
   ;; (HTTP + OAuth 2.0). brainyard's native :http transport can't run the OAuth
   ;; handshake (same reason as notion/linear above), so bridge through
   ;; mcp-remote (stdio) — it runs the browser consent flow on first start.
   ;; ── Google: Gmail + Calendar ──────────────────────────────────────────────
   ;; PRIMARY: taylorwilsdon/google_workspace_mcp (`uvx workspace-mcp`) — a local
   ;; stdio server that runs its OWN OAuth loopback callback
   ;; (http://localhost:8000/oauth2callback) and manages its own token cache.
   ;;
   ;; This replaces the hosted-Google + mcp-remote seeds (kept commented below).
   ;; mcp-remote never binds its callback listener for servers that allow an
   ;; unauthenticated `initialize` and defer auth to tool-call time — which
   ;; gmailmcp/calendarmcp.googleapis.com do — so its browser redirect always
   ;; landed on a dead port (ERR_CONNECTION_REFUSED). Confirmed via --debug:
   ;; discovery + "Browser opened" happen, but no callback server is ever started
   ;; (upstream race; PR #260 unreleased). This server owns the callback
   ;; end-to-end, sidestepping the bug.
   ;;
   ;; Setup: a GCP **Desktop** OAuth client (loopback any port) with the Gmail +
   ;; Calendar APIs enabled; export its id/secret as GCP_OAUTH_CLIENT_ID /
   ;; GCP_OAUTH_CLIENT_SECRET (e.g. in .env). The `bash -c` wrapper remaps those
   ;; to the GOOGLE_OAUTH_* names the server expects (a directly-spawned process
   ;; wouldn't expand $VAR); OAUTHLIB_INSECURE_TRANSPORT=1 is required for the
   ;; http://localhost callback. Ships :enabled false — turn on via
   ;; `/mcp google-workspace start`, then complete the browser consent once.
   "google-workspace"
   {:transport :stdio
    :config {:command "bash"
             ;; First `uvx` run resolves+installs workspace-mcp — allow generous
             ;; connect time. :timeout is the per-request budget (slow Gmail).
             :connect-timeout-ms 180000
             :timeout 120000
             :args ["-c"
                    ;; --single-user: local one-person stdio setup — bypass
                    ;; multi-user session mapping so OAuth is a simple one-time
                    ;; browser consent. stdio is the default transport.
                    "GOOGLE_OAUTH_CLIENT_ID=\"$GCP_OAUTH_CLIENT_ID\" GOOGLE_OAUTH_CLIENT_SECRET=\"$GCP_OAUTH_CLIENT_SECRET\" OAUTHLIB_INSECURE_TRANSPORT=1 exec uvx workspace-mcp --single-user --tools gmail calendar"]}
    :enabled false
    :auto-register-tools true}

   ;; ── DISABLED — non-functional, kept for reference only ────────────────────
   ;; Hosted Google MCP via mcp-remote. Do NOT re-enable without an mcp-remote
   ;; that binds its callback before browser auth. Full form in git history.
   ;;   "gmail" {:transport :stdio
   ;;            :config {:command "bash" :timeout 90000
   ;;                     :args ["-c" "npx -y mcp-remote https://gmailmcp.googleapis.com/mcp/v1 3334 --auth-timeout 300 --static-oauth-client-info {...$GCP_OAUTH_CLIENT_ID...}"]}
   ;;            :enabled false :auto-register-tools true}
   ;;   "google-calendar" {:transport :stdio
   ;;            :config {:command "bash" :timeout 90000
   ;;                     :args ["-c" "npx -y mcp-remote https://calendarmcp.googleapis.com/mcp/v1 3335 --auth-timeout 300 --static-oauth-client-info {...$GCP_OAUTH_CLIENT_ID...}"]}
   ;;            :enabled false :auto-register-tools true}
   })

;; =============================================================================
;; config.edn-backed configuration (builtin defaults + config.edn overlay)
;; =============================================================================
;;
;; The active MCP server set = the built-in defaults (`create-seed-mcp-config`)
;; deep-merged with `.brainyard/config.edn` `[:mcp :servers]`. config.edn wins
;; per leaf, so a user can override just `:enabled` (or any field) of a builtin,
;; and add new servers, but never silently loses a builtin by omission. Nothing
;; is written to config.edn at startup (no seeding); only explicit actions
;; (`/mcp start|stop`, the wizard, hand edits) persist there. The runtime atom
;; `mcp-servers-config` is keyed by STRING server-name (what users type and what
;; `connect-mcp-server!` expects); config.edn is keyword-keyed, so keys are
;; stringified on load and re-keyworded on write.

(defn- deep-merge
  "Recursively merge maps; for non-map leaves, `b` wins. Used to overlay
   config.edn server entries onto the built-in defaults."
  [a b]
  (if (and (map? a) (map? b))
    (merge-with deep-merge a b)
    b))

(defn- existing-config-scope
  "Scope whose config.edn we read/write: :project when a project-level
   config.edn exists on disk, else :user. Keeps read and write on the same
   file (mirrors read-edn-config's :auto project-first/user-fallback)."
  [dirs]
  (let [pcd (core-config/project-config-dir dirs)]
    (if (and pcd (.isFile (java.io.File. ^String pcd "config.edn")))
      :project
      :user)))

(defn builtin-default-servers
  "Built-in default MCP server set, keyword-keyed for config.edn storage.
   Every entry is `:enabled false` — editable placeholders the user turns on
   via `/mcp <name> start`, the wizard, or by editing config.edn directly."
  []
  (into {} (map (fn [[k v]] [(keyword k) v])) (create-seed-mcp-config)))

(defn- stringify-server-keys
  "config.edn stores keyword server names; the runtime atom is string-keyed."
  [servers]
  (into {} (map (fn [[k v]] [(name k) v])) servers))

(defn persist-server-enabled!
  "Persist a server's `:enabled` flag to config.edn at
   `[:mcp :servers <name> :enabled]` and mirror it into the runtime atom.
   Writes only that leaf — a builtin server with no config.edn entry yet gets a
   partial `{:enabled <bool>}` map, which `init-mcp-from-config!` deep-merges
   back over the builtin definition on next start. No-op for names unknown to
   the runtime (ad-hoc connects to never-configured servers aren't written)."
  [server-name enabled?]
  (try
    (when (contains? @mcp-servers-config server-name)
      (let [dirs  (core-config/resolve-dirs)
            scope (existing-config-scope dirs)
            cfg   (core-config/read-edn-config dirs scope)
            k     (keyword server-name)
            en?   (boolean enabled?)]
        (core-config/write-edn-config!
         dirs (assoc-in cfg [:mcp :servers k :enabled] en?) scope)
        (swap! mcp-servers-config assoc-in [server-name :enabled] en?)
        (mulog/info ::server-enabled-persisted :server server-name :enabled en?)))
    (catch Exception e
      (mulog/warn ::persist-enabled-failed :server server-name :error (ex-message e)))))

(defn start-mcp-server!
  "Connect a server and persist `:enabled true` to config.edn so the choice
   survives restart. Use this from UI/tool surfaces instead of the lower-level
   `connect-mcp-server!` (which never touches config.edn)."
  [server-name server-config]
  (let [c (connect-mcp-server! server-name server-config)]
    (persist-server-enabled! server-name true)
    c))

(defn stop-mcp-server!
  "Disconnect a server and persist `:enabled false` to config.edn."
  [server-name]
  (disconnect-mcp-server! server-name)
  (persist-server-enabled! server-name false))

(defn init-mcp-from-config!
  "Populate the MCP runtime with the built-in defaults (`create-seed-mcp-config`)
   deep-merged with config.edn `[:mcp :servers]` — config.edn wins per leaf.
   Builtin servers are always present; config.edn entries override fields and
   add new servers. The merged set is loaded into the runtime atom and the
   `:enabled true` ones are auto-connected. Nothing is written to config.edn
   here (no seeding). Idempotent per process via `!mcp-initialized`.

   Connects in the background by default (per `load-mcp-servers!`); pass through
   config.edn `[:mcp :connect-mode]` (`:background` | `:eager`) and a global
   `[:mcp :connect-timeout-ms]` that is injected into each server's `:config`
   (a server's own `:config :connect-timeout-ms` still wins). Optional opts:
     :on-settle (fn [server-name status throwable]) — forwarded to each connect,
                used by the TUI to surface per-server ✓/✗ status.

   Runtime replacement for the old namespace-load auto-init, which loaded a
   hardcoded sample and — under the native image — baked it at build time.
   Call once at app startup (e.g. the TUI's `start!`)."
  ([] (init-mcp-from-config! nil))
  ([{:keys [on-settle]}]
   (if @!mcp-initialized
     {:already-initialized true}
     (try
       (let [dirs    (core-config/resolve-dirs)
             scope   (existing-config-scope dirs)
             cfg     (core-config/read-edn-config dirs scope)
             mcp     (get cfg :mcp {})
             user    (get mcp :servers {})
             mode    (get mcp :connect-mode :background)
             timeout (:connect-timeout-ms mcp)
             merged  (stringify-server-keys
                      (deep-merge (builtin-default-servers) user))
             ;; Fill the global connect timeout into any server that doesn't
             ;; set its own (client.clj's stdio connect! reads :connect-timeout-ms
             ;; off the server's :config map).
             prepared (if timeout
                        (reduce-kv
                         (fn [m sn sc]
                           (assoc m sn
                                  (update sc :config
                                          (fn [c]
                                            (if (contains? c :connect-timeout-ms)
                                              c
                                              (assoc c :connect-timeout-ms timeout))))))
                         {} merged)
                        merged)
             summary (load-mcp-servers! prepared {:on-settle on-settle
                                                  :connect-mode mode})]
         (reset! !mcp-initialized true)
         (merge {:scope scope
                 :overrides (mapv name (keys user))
                 :servers (mapv name (keys merged))}
                summary))
       (catch Exception e
         (mulog/warn ::init-from-config-failed :error (ex-message e))
         nil)))))

;; =============================================================================
;; Startup and Lifecycle Management
;; =============================================================================

(defn initialize-mcp-system!
  "Initialize the MCP system with default configuration"
  [& [config-file]]
  (try
    (if config-file
      (load-mcp-config-from-file config-file)
      (do
        (mulog/info ::using-sample-config)
        (load-mcp-servers! (create-seed-mcp-config))))

    (reset! !mcp-initialized true)
    (mulog/info ::system-initialized)

    (catch Exception e
      (mulog/error ::system-init-failed :error (ex-message e))
      (throw e))))

(defn shutdown-mcp-system!
  "Shutdown the MCP system and disconnect all servers"
  []
  (let [active-clients (mcp-client/list-active-clients)]
    (doseq [server-name active-clients]
      (disconnect-mcp-server! server-name))
    (reset! mcp-servers-config {})
    (reset! !mcp-initialized false)
    (mulog/info ::system-shutdown-complete)))

;; NOTE: No namespace-load auto-init. MCP servers are loaded at runtime via
;; `init-mcp-from-config!` (built-in defaults deep-merged with config.edn),
;; called once at app startup (the TUI's `start!`). A `defonce` here would run
;; at namespace-load time — i.e. native-image BUILD time — loading the hardcoded
;; `create-seed-mcp-config` into the image heap and ignoring config.edn
;; entirely. See `init-mcp-from-config!` above.

(comment
  ;; Example usage

  ;; Initialize with sample config
  (initialize-mcp-system!)

  ;; Or load from file
  (initialize-mcp-system! "/path/to/mcp-config.json")

  ;; Connect to a specific server
  (connect-mcp-server! "filesystem"
                       {:transport :stdio
                        :config {:command "python" :args ["-m" "mcp_server_filesystem"]}})

  ;; List and call tools directly
  (list-all-server-tools)
  (call-server-tool "filesystem" "read_file" {"path" "/tmp/test.txt"})

  ;; Health check
  (mcp-health-check)

  ;; Shutdown
  (shutdown-mcp-system!))
