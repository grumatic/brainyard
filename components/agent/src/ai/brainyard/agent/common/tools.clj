;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.common.tools
  "Sandbox-oriented deftool definitions.
   Registered globally via deftool and available to sandbox code."
  (:require [ai.brainyard.agent.common.plan :as plan]
            [ai.brainyard.agent.common.reference :as ref]
            [ai.brainyard.agent.common.skills :as skill]
            [ai.brainyard.agent.core.config :as config]
            [ai.brainyard.agent.core.protocol :as proto]
            [ai.brainyard.agent.core.session :as session]
            [ai.brainyard.agent.core.timeutil :as timeutil]
            [ai.brainyard.agent.core.tool :as tool :refer [deftool]]
            [ai.brainyard.memory.interface :as mem]
            [ai.brainyard.mulog.interface :as mulog]
            [ai.brainyard.util.interface :as util]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [clojure.walk :refer [postwalk]]))

;; ============================================================================
;; Helpers
;; ============================================================================

(defn- meta->data
  "Walk metadata, converting fns to strings so results are serializable."
  [m]
  (postwalk (fn [x] (if (fn? x) (str x) x)) m))

(defn- list-tool-defs
  "Retrieve tool defs from registry, optionally filtered by type.
   Collapses to summary fields by default."
  [& {:keys [type collapse?] :or {collapse? true}}]
  (let [collapse-fields [:id :type :description :input-schema :output-schema]]
    (reduce-kv (fn [acc id tool-def]
                 (let [meta-raw (:meta tool-def)
                       meta-sel (if (and collapse? (vector? collapse-fields))
                                  (select-keys meta-raw collapse-fields)
                                  meta-raw)]
                   (assoc acc id (meta->data meta-sel))))
               {}
               (if type
                 (tool/get-tool-defs :type type)
                 (tool/get-tool-defs)))))

(defn- invoke-tool-safe
  "Call a registered tool, returning {:error msg} on failure instead of throwing.
   Resolves any returned clojure.lang.Agent ref (from agent-type tools that
   dispatch via ask-async) via `tool/resolve-agent-ref`, so callers never see
   a raw agent ref — which would blow up `println`/serialization with circular
   references."
  [tool-name args-map]
  (try
    (let [result (-> (tool/invoke-tool (keyword tool-name) args-map)
                     tool/resolve-agent-ref)
          err (:error-message result)]
      (if err {:error err} result))
    (catch Exception e {:error (str "call-tool failed: " (.getMessage e))})))

(defn- non-blank? [s] (and (string? s) (not (str/blank? s))))

(defn- first-line
  "First non-blank-trimmed line of a (possibly multi-line) string — keeps the
   grouped tool index scannable when a description spans lines."
  [s]
  (some-> s str str/split-lines first str/trim))

(defn- tool-family
  "Grouping key for a tool id: the segment before the first `$`
   (e.g. \"memory$recall\" → \"memory\"), or \"_ungrouped\" for ids without a
   `$` (e.g. bash, read-file)."
  [id-str]
  (if-let [i (str/index-of id-str "$")]
    (subs id-str 0 i)
    "_ungrouped"))

(defn- collapsed-visible-tools
  "Flat vector of collapsed tool maps {:id :type :description :input-schema
   :output-schema} for tools visible to the current agent, optionally filtered
   by `type` and a case-insensitive `pattern` (matched against id/name/
   description). Shared by `list-tools` and the `search` dispatcher."
  [{:keys [pattern type]}]
  (let [type-kw (when (non-blank? type) (keyword type))
        current-agent-id (when proto/*current-agent*
                           (proto/agent-id proto/*current-agent*))
        all-tools (->> (list-tool-defs :type type-kw)
                       vals
                       (filter #(tool/tool-visible? {:meta %} current-agent-id))
                       (mapv #(assoc % :id (util/kw->str (:id %)))))]
    (if (non-blank? pattern)
      (let [re (re-pattern (str "(?i)" pattern))]
        (filterv #(or (re-find re (str (:id %)))
                      (re-find re (str (:name %)))
                      (re-find re (str (:description %))))
                 all-tools))
      all-tools)))

;; ============================================================================
;; Tool Definitions
;; ============================================================================

(deftool list-tools
  "List registered tools. With NO args returns a grouped index
   `{:total N :families {family [{:id :description} ...]}}` — one entry per
   visible tool, descriptions reduced to a single line and schemas omitted so the
   ~200-tool roster stays scannable; drill into a tool with `get-tool-info`.
   Pass `:pattern` (regex over id/name/description) and/or `:type` to get a flat,
   DETAILED vector `[{:id :type :description :input-schema :output-schema} ...]`.
   `:grouped false` forces the flat detailed list even with no filter."
  (fn [{:keys [pattern type grouped]}]
    (let [tools (collapsed-visible-tools {:pattern pattern :type type})]
      (if (or (non-blank? pattern) (non-blank? type) (false? grouped))
        tools
        {:total    (count tools)
         :families (into (sorted-map)
                         (map (fn [[fam ts]]
                                [fam (mapv (fn [t] {:id          (:id t)
                                                    :description (first-line (:description t))})
                                           ts)]))
                         (group-by #(tool-family (:id %)) tools))})))
  :input-schema  [:map
                  [:pattern {:optional true} [:string {:desc "Regex (case-insensitive) over id/name/description; returns a flat detailed list"}]]
                  [:type    {:optional true} [:enum {:desc "Filter by tool type; returns a flat detailed list"}
                                              "tool" "command" "skill" "agent"]]
                  [:grouped {:optional true} [:boolean {:desc "Default true for the no-arg grouped index; pass false to force a flat detailed list"}]]]
  :output-schema [:or
                  [:map {:desc "Grouped index, returned when no :pattern/:type is given"}
                   [:total    [:int {:desc "Total visible tools"}]]
                   [:families [:map {:desc "Family (segment before `$`, or \"_ungrouped\") → vector of {:id :description}; drill in via get-tool-info"}]]]
                  [:vector {:desc "Flat detailed list, returned when :pattern/:type is given or :grouped false"} :map]])

(deftool get-tool-info
  "Get a registered tool's details (schema, type, description) by tool-id."
  (fn [{:keys [tool-id]}]
    (if (str/blank? (str tool-id))
      {:error-message "tool-id is required"}
      (let [id (keyword (str tool-id))
            id-ns (namespace id)
            def-id (if id-ns (keyword id-ns) id)
            tool-def (tool/get-tool-defs :id def-id)]
        (if tool-def
          (some-> (:meta tool-def)
                  meta->data
                  (assoc :id (str tool-id)))
          {:error-message (format "tool(ID:%s) is not defined in system" (name def-id))}))))
  :input-schema  [:map
                  [:tool-id [:string {:desc "Tool ID registered in system"}]]]
  :output-schema [:map
                  [:id            [:string {:desc "Tool ID"}]]
                  [:type          [:string {:desc "Tool type: command, skill, or agent"}]]
                  [:description   [:string {:desc "Tool description"}]]
                  [:input-schema  [:string {:desc "Input parameter schema"}]]
                  [:output-schema [:string {:desc "Output schema"}]]
                  [:error-message [:string {:desc "Error if tool not found"}]]])

(defn- call-mcp-tool
  "Call a tool via MCP with server-name."
  [tool-name server-name tool-args]
  (let [result (invoke-tool-safe "mcp$tools"
                                 {:op "call"
                                  :tool-calls [{:server-name server-name
                                                :tool-name (str tool-name)
                                                :tool-args (or tool-args {})}]})]
    (or (get-in result [:result :tool-results 0 :tool-result])
        result)))

(deftool call-tool
  "Invoke a registered or MCP tool by name."
  (fn [{:keys [tool-name source server-name tool-args]}]
    (let [tool-args (or tool-args {})
          registry-id (keyword tool-name)]
      (if (= source "mcp")
        ;; Explicit MCP call
        (call-mcp-tool tool-name server-name tool-args)
        ;; Registered tool first; fall back to MCP when server-name given.
        ;; Route through tool/call-tool (not bare invoke-tool) so agent-type
        ;; targets go through do-call-tool--agent — which propagates the caller's
        ;; agent-session, enforces call-depth/circular guards, and awaits the
        ;; returned Agent ref via resolve-agent-ref.
        (if (tool/get-tool-defs :id registry-id)
          (try
            (let [result (tool/call-tool registry-id tool-args
                                         :agent proto/*current-agent*)
                  err (:error-message result)]
              (if err {:error err} result))
            (catch Exception e {:error (str "call-tool failed: " (.getMessage e))}))
          (if server-name
            (call-mcp-tool tool-name server-name tool-args)
            {:error (format "%s is not registered. Pass :server-name to dispatch via MCP."
                            tool-name)})))))
  :input-schema  [:map
                  [:tool-name   [:string {:desc "Name of the tool to invoke"}]]
                  [:tool-args   {:optional true} [:map {:desc "Map of arguments to forward to the target tool (e.g. {:slug \"x\" :description \"...\"})"}]]
                  [:source      {:optional true} [:enum {:desc "\"mcp\" to force MCP call (default: auto-detect)"}
                                                  "mcp"]]
                  [:server-name {:optional true} [:string {:desc "MCP server name (enables MCP fallback if registered tool not found)"}]]]
  :output-schema [:any {:desc "Return value from the target tool (shape varies)"}]
  ;; Sandbox-only — invoked from SCI code, never via the LLM tool-calls
  ;; channel. Hidden from every agent's roster.
  :tool-use-control {:visibility :hidden})

;; ============================================================================
;; Shell Tools
;; ============================================================================

(defn- run-bash-inline
  "Direct ProcessBuilder execution — no task creation. The outer call site
   (call-tool-with-fast-eval or code-eval task) handles timeout/detach.
   When timeout-ms is nil, waits indefinitely for the process to exit.

   `dir` anchors the shell's working directory. The `bash` tool passes
   `project-dir` (git-root) so relative paths like `.brainyard/agents/…`
   resolve where the functional agents actually write their artifacts —
   NOT the JVM cwd, which under `bb tui` is the launcher's
   `projects/agent-tui-app/` subdir. When `dir` is nil the shell inherits
   the JVM cwd (the historical behavior; used by direct unit tests)."
  ([command timeout-ms] (run-bash-inline command timeout-ms nil))
  ([command timeout-ms dir]
   (try
     (let [infinite? (nil? timeout-ms)
           pb (ProcessBuilder. ^"[Ljava.lang.String;"
               (into-array String ["/bin/sh" "-c" command]))
           _ (when dir (.directory pb (java.io.File. ^String dir)))
           _ (.redirectErrorStream pb true)
           ^Process proc (.start pb)
           _ (.close (.getOutputStream proc))
           ^java.io.StringWriter sw (java.io.StringWriter.)
           reader (future
                    (let [^java.io.InputStreamReader rdr
                          (java.io.InputStreamReader. (.getInputStream proc))
                          buf (char-array 1024)]
                      (try
                        (loop []
                          (let [n (.read rdr buf)]
                            (when (pos? n)
                              (.write sw buf 0 ^int n)
                              (recur))))
                        (catch Exception _))))
           completed? (if infinite?
                        (do (.waitFor proc) true)
                        (.waitFor proc (long timeout-ms)
                                  java.util.concurrent.TimeUnit/MILLISECONDS))]
       (if completed?
         (do (deref reader 2000 nil)
             (let [exit-code (.exitValue proc)
                   output    (.toString sw)]
               {:status    (if (zero? exit-code) "completed" "failed")
                :exit-code exit-code
                :output    output}))
         (do (.destroyForcibly proc)
             (future-cancel reader)
             {:status     "timeout"
              :exit-code  nil
              :output     (.toString sw)
              :timeout-ms timeout-ms})))
     (catch Exception e
       {:error (str "bash inline failed: " (.getMessage e))}))))

(deftool bash
  "Run a shell command. Timeout defaults to infinite (outer task handles deadline)."
  (fn [{:keys [command timeout]}]
    ;; Anchor at project-dir (git-root), matching read-file/write-file/grep.
    ;; Relative `.brainyard/…` paths then resolve where functional agents
    ;; write their artifacts, not the JVM cwd subdir under `bb tui`.
    (run-bash-inline command timeout (config/project-dir)))
  :input-schema  [:map
                  [:command [:string {:desc "Shell command to execute"}]]
                  [:timeout {:optional true} [:int {:desc "Timeout in ms (default: no limit)"}]]]
  :output-schema [:map
                  [:status    [:string {:desc "\"completed\", \"failed\", or \"timeout\""}]]
                  [:exit-code [:int {:desc "Process exit code (nil on timeout)"}]]
                  [:output    [:string {:desc "Combined stdout+stderr"}]]])

;; ============================================================================
;; Interaction & Memory Tools
;; ============================================================================

(deftool get-user-feedback
  "Ask the user. kind=select (pick from options), text (free-form), or confirm (yes/no). Blocks until answered."
  (fn [{:keys [question options timeout kind]}]
    (let [timeout (or timeout 300000)
          kind    (keyword (or kind "select"))
          agent   proto/*current-agent*
          feedback-fn (when agent
                        (some-> (:!session agent) deref
                                (session/get-session-config :user-feedback-fn)))]
      (cond
        (nil? feedback-fn)
        {:error "User feedback not available (no interactive session)"}

        (and (= kind :select) (< (count options) 2))
        {:error "select requires 2-6 options"}

        :else
        (let [result (feedback-fn (cond-> {:kind kind :question question :timeout-ms timeout}
                                    (seq options) (assoc :options options)))]
          (mulog/log ::user-feedback
                     :agent-id (when agent (:agent-id agent))
                     :kind kind :question question
                     :options-count (count options) :result result)
          (cond
            (nil? result)     {:error "User feedback cancelled"}
            (:error result)   result
            (:timeout result) result
            :else
            ;; Normalize free-text input here so every backend (in-stream raw,
            ;; stdin, tmux popup, free-input select) trims consistently.
            (let [result (cond-> result
                           (string? (:input result)) (update :input str/trim))]
              (assoc result :answer
                     (case kind
                       :text    (:input result)
                       :confirm (some-> (:value result) name)
                       (str (:selected result)
                            (when-let [in (:input result)] (str ": " in)))))))))))
  ;; Interactive prompt — must block the calling thread for terminal I/O.
  ;; :sync true routes it through call-tool directly, bypassing the fast-eval/
  ;; auto-background-detach path so a slow human answer never detaches the
  ;; prompt into a background task.
  :sync true
  :input-schema  [:map
                  [:question [:string {:desc "Question to present to user"}]]
                  [:options  [:vector {:desc "Options for select; pass [] for text/confirm"} :string]]
                  [:kind     {:optional true} [:enum {:desc "select (default), text, or confirm"} "select" "text" "confirm"]]
                  [:timeout  {:optional true} [:int {:desc "Timeout in milliseconds (default 300000)" :default 300000}]]]
  :output-schema [:map
                  [:answer [:string {:desc "User's selected option or typed response"}]]])

;; ============================================================================
;; File & URL Tools
;; ============================================================================

(defn- resolve-path
  "Resolve a path relative to base-dir. Absolute paths pass through."
  [base-dir path]
  (if (str/starts-with? (str path) "/") path (str base-dir "/" path)))

(defn- get-base-dir []
  ;; `project-dir` (git-root) not `working-dir` (JVM cwd) — bb tui's
  ;; launcher cd's into projects/agent-tui-app/ before exec'ing the JVM,
  ;; so working-dir points at the subdir while functional agents
  ;; (plan/todo/explore/...) anchor their .brainyard/ artifacts at the
  ;; brainyard repo root. Anchoring the tool channel at project-dir lets
  ;; read-file / write-file / grep / update-file agree with where those
  ;; artifacts actually live.
  (config/project-dir))

(defn- get-fallback-dirs []
  ;; Secondary bases tried (in order) when a relative path is NOT found under
  ;; project-dir: the JVM working-dir (under `bb tui` this is the launcher's
  ;; projects/agent-tui-app/ subdir) then the user home. project-dir stays
  ;; primary so read-file/grep agree with bash; the fallbacks only widen
  ;; discovery for files that live in a sibling tree or under ~/.brainyard.
  ;; The user-home reach is still gated by allowed-dirs/permission in
  ;; read-file-content. See project_main_agent_path_divergence.
  (->> [(config/working-dir) (System/getProperty "user.home")]
       (remove nil?)
       distinct
       vec))

(defn- get-allowed-dirs [] (config/allowed-dirs))

(defn- get-permission-fn []
  (let [agent proto/*current-agent*]
    (when agent
      (some-> (:!session agent) deref (session/get-session-config :permission-fn)))))

(defn- get-dirs []
  (let [agent proto/*current-agent*]
    (or (when agent
          (some-> (:!session agent) deref
                  (session/get-session-config :dirs)))
        (config/init-dirs!))))

;; `list-files` deftool removed — agents call `bash` with `find`/`ls`. The
;; internal `ref/list-files` helper is still used by the `search` deftool for
;; import-aware file enumeration.

(deftool read-file
  "Read file content."
  (fn [{:keys [path offset limit lines]}]
    (ref/read-file-content (get-base-dir) path
                           :allowed-dirs (get-allowed-dirs)
                           :fallback-dirs (get-fallback-dirs)
                           :permission-fn (get-permission-fn)
                           :offset offset :limit limit :lines lines))
  :input-schema [:map
                 [:path   [:string {:desc "File path to read"}]]
                 [:offset {:optional true} [:int {:desc "Byte offset to start reading from"}]]
                 [:limit  {:optional true} [:int {:desc "Max characters to read"}]]
                 [:lines  {:optional true} [:vector {:desc "Line range [start end] (1-based)"} :int]]])

(deftool write-file
  "Write file. /tmp and .brainyard/ always allowed; other paths prompt for permission."
  (fn [{:keys [path content append]}]
    (ref/write-project-file (get-base-dir) path content
                            :append? append
                            :permission-fn (get-permission-fn)))
  :input-schema [:map
                 [:path    [:string {:desc "File path to write"}]]
                 [:content [:string {:desc "Content to write"}]]
                 [:append  {:optional true} [:boolean {:desc "If true, append instead of overwrite" :default false}]]])

;; ---------- update-file helpers ----------

(defn- apply-replacement
  "Apply pattern→replacement on content. Returns {:replaced N :new-content str}."
  [content pattern replacement regex? all?]
  (let [p (if regex?
            (re-pattern pattern)
            (re-pattern (java.util.regex.Pattern/quote pattern)))
        ;; In literal mode, escape replacement so $1 / \1 are treated literally.
        ;; In regex mode, leave replacement as-is so backrefs are honored.
        r (if regex?
            replacement
            (java.util.regex.Matcher/quoteReplacement replacement))
        total (count (re-seq p content))]
    (if (zero? total)
      {:replaced 0 :new-content content}
      {:replaced    (if all? total 1)
       :new-content (if all?
                      (str/replace content p r)
                      (str/replace-first content p r))})))

(defn- git-available? []
  (try (zero? (:exit (shell/sh "git" "--version")))
       (catch Exception _ false)))

(defn- git-diff
  "Run `git diff --no-index --no-color` between old and new content. Returns diff
   string, or nil on error. git diff exits 1 when there IS a diff — both 0 and 1
   are success."
  [old-content new-path]
  (let [old-file (doto (java.io.File/createTempFile "update-file-old-" ".txt")
                   (.deleteOnExit))]
    (try
      (spit old-file old-content)
      (let [{:keys [exit out]} (shell/sh "git" "diff" "--no-index" "--no-color"
                                         "--" (.getPath old-file) (str new-path))]
        (when (#{0 1} exit) out))
      (catch Exception _ nil)
      (finally
        (try (.delete old-file) (catch Exception _))))))

(defn- fallback-diff
  "Minimal line-by-line diff used when `git` is not on PATH. Not a full Myers
   diff — best-effort textual summary."
  [old-content new-content path]
  (let [old-lines (str/split-lines (or old-content ""))
        new-lines (str/split-lines (or new-content ""))
        n (max (count old-lines) (count new-lines))]
    (->> (range n)
         (keep (fn [i]
                 (let [o (get old-lines i)
                       n (get new-lines i)]
                   (when (not= o n)
                     (str (when o (str "- " o "\n"))
                          (when n (str "+ " n)))))))
         (str/join "\n")
         (str "--- " path " (old)\n+++ " path " (new)\n"))))

(defn- compute-diff
  "Return {:diff str :diff-source \"git\"|\"fallback\"}."
  [old-content new-path]
  (if-let [gd (and (git-available?) (git-diff old-content new-path))]
    {:diff gd :diff-source "git"}
    (let [new-content (try (slurp (str new-path)) (catch Exception _ ""))]
      {:diff        (fallback-diff old-content new-content new-path)
       :diff-source "fallback"})))

(deftool update-file
  "Find/replace a pattern in a file and return a diff. Literal + first-match by default; see :regex?/:all? inputs."
  (fn [{:keys [path pattern replacement regex? all?]}]
    (let [base-dir      (get-base-dir)
          allowed-dirs  (get-allowed-dirs)
          permission-fn (get-permission-fn)
          read-result   (ref/read-file-content base-dir path
                                               :allowed-dirs allowed-dirs
                                               :fallback-dirs (get-fallback-dirs)
                                               :permission-fn permission-fn)]
      (cond
        (:error read-result)
        {:error (:error read-result)}

        :else
        (let [resolved-path (:path read-result)
              original      (try (slurp resolved-path)
                                 (catch Exception e
                                   (throw (ex-info (.getMessage e) {:path resolved-path}))))
              {:keys [replaced new-content]}
              (apply-replacement original pattern replacement
                                 (boolean regex?) (boolean all?))]
          (cond
            (zero? replaced)
            {:error (format "Pattern not found in %s" resolved-path)
             :path  resolved-path}

            (= original new-content)
            {:error "Replacement produced identical content"
             :path  resolved-path}

            :else
            ;; Write back to the path the read actually resolved (which may be
            ;; a fallback-dir under bb tui's cwd), NOT a re-resolution of the
            ;; original relative `path` against base-dir — otherwise a
            ;; fallback-resolved read would write a new file in the wrong tree.
            (let [write-result (ref/write-project-file base-dir resolved-path new-content
                                                       :permission-fn permission-fn)]
              (if (:error write-result)
                (assoc write-result :path resolved-path)
                (let [final-path (:path write-result)
                      {:keys [diff diff-source]} (compute-diff original final-path)]
                  {:path        final-path
                   :replaced    replaced
                   :diff        diff
                   :diff-source diff-source}))))))))
  :input-schema  [:map
                  [:path        [:string  {:desc "File path to update"}]]
                  [:pattern     [:string  {:desc "Pattern to find (literal by default; regex when :regex? true)"}]]
                  [:replacement [:string  {:desc "Replacement text"}]]
                  [:regex?      {:optional true} [:boolean {:desc "Treat :pattern as a Java regex (default false)" :default false}]]
                  [:all?        {:optional true} [:boolean {:desc "Replace every match (default false — first match only)" :default false}]]]
  :output-schema [:map
                  [:path        [:string {:desc "Absolute path of the updated file"}]]
                  [:replaced    [:int    {:desc "Number of replacements made"}]]
                  [:diff        [:string {:desc "Unified diff of the change"}]]
                  [:diff-source [:string {:desc "\"git\" when produced by git diff, \"fallback\" otherwise"}]]
                  [:error       {:optional true} [:string {:desc "Error message on failure"}]]])

(deftool grep
  "Regex search in files."
  (fn [{:keys [pattern path max-results include-exts recursive]}]
    (let [base-dir (get-base-dir)]
      (ref/grep-files base-dir (get-allowed-dirs) pattern
                      (resolve-path base-dir path)
                      :max-results (or max-results 50)
                      :include-exts include-exts
                      :recursive (if (some? recursive) recursive true))))
  :input-schema [:map
                 [:pattern      [:string {:desc "Regex pattern to search for"}]]
                 [:path         [:string {:desc "Directory or file path to search in"}]]
                 [:max-results  {:optional true} [:int {:desc "Maximum matches to return (default 50)" :default 50}]]
                 [:include-exts {:optional true} [:vector {:desc "File extensions to include (e.g. [\".clj\" \".edn\"])"} :string]]
                 [:recursive    {:optional true} [:boolean {:desc "Recursive search (default true)" :default true}]]])

(deftool fetch-url
  "HTTP GET a URL."
  (fn [{:keys [url timeout max-chars]}]
    (ref/fetch-url url
                   :timeout (or timeout 10000)
                   :max-chars (or max-chars 100000)))
  :input-schema  [:map
                  [:url       [:string {:desc "URL to fetch"}]]
                  [:timeout   {:optional true} [:int {:desc "Timeout in milliseconds (default 10000)" :default 10000}]]
                  [:max-chars {:optional true} [:int {:desc "Maximum characters to return (default 100000)" :default 100000}]]]
  :output-schema [:map
                  [:url          [:string {:desc "Fetched URL"}]]
                  [:status       [:int {:desc "HTTP status code"}]]
                  [:body         [:string {:desc "Response body text"}]]
                  [:content-type [:string {:desc "Response content-type header"}]]
                  [:size         [:int {:desc "Body length in characters"}]]
                  [:truncated    [:boolean {:desc "True if body was truncated at max-chars"}]]])

;; ============================================================================
;; Web Search Tools
;; ============================================================================

(defn- get-tavily-api-key [] (config/get-config proto/*current-agent* :tavily-api-key))

(deftool web-search
  "Search the web via Tavily. Requires TAVILY_API_KEY env or agent :tavily-api-key."
  (fn [{:keys [query max-results search-depth include-answer]}]
    (let [api-key (get-tavily-api-key)]
      (if (str/blank? api-key)
        {:error "TAVILY_API_KEY not set (configure env or agent :tavily-api-key in config)"}
        (ref/tavily-search query
                           :api-key api-key
                           :max-results (or max-results 5)
                           :search-depth (or search-depth "basic")
                           :include-answer? (if (some? include-answer) include-answer true)))))
  :input-schema  [:map
                  [:query          [:string {:desc "Search query"}]]
                  [:max-results    {:optional true} [:int {:desc "Max results (default 5)" :default 5}]]
                  [:search-depth   {:optional true} [:enum {:desc "\"basic\" (default) or \"advanced\""}
                                                     "basic" "advanced"]]
                  [:include-answer {:optional true} [:boolean {:desc "Include Tavily-generated answer (default true)" :default true}]]]
  :output-schema [:map
                  [:answer  [:string {:desc "Tavily-generated summary answer"}]]
                  [:query   [:string {:desc "Echoed search query"}]]
                  [:results [:vector {:desc "Search results"} [:map
                                                               [:title [:string {:desc "Page title"}]]
                                                               [:url [:string {:desc "Page URL"}]]
                                                               [:content [:string {:desc "Snippet text"}]]
                                                               [:score [:double {:desc "Relevance score 0-1"}]]]]]])

;; ============================================================================
;; Discovery Tool (search)
;; ============================================================================

(defn- case-insensitive-matcher
  "Return a predicate that matches strings case-insensitively against all tokens.
   Every token must appear (AND). Returns nil when `tokens` is empty."
  [tokens]
  (when (seq tokens)
    (let [pats (mapv #(re-pattern (str "(?i)" (java.util.regex.Pattern/quote %))) tokens)]
      (fn [s]
        (when s
          (let [text (str s)]
            (every? #(re-find % text) pats)))))))

(defn- safe-exec
  "Execute f, returning nil on exception."
  [_label f]
  (try (f) (catch Exception _ nil)))

(deftool search
  "Search project files, config files, long-term memory, and registered tools."
  (fn [{:keys [query memory-limit]}]
    (let [agent       proto/*current-agent*
          base-dir    (get-base-dir)
          q           (str/trim (str query))
          tokens      (if (str/blank? q) [] (str/split q #"\s+"))
          kept-tokens (filterv #(>= (count %) 3) tokens)
          matches?    (case-insensitive-matcher kept-tokens)
          result      (atom {})]
      ;; 1. Project files (gated on matches?)
      (when matches?
        (when-let [files (safe-exec :project-files
                                    #(->> (:files (ref/list-files base-dir (get-allowed-dirs)
                                                                  "**/*" :max-results 500))
                                          (filter (fn [f] (matches? (:path f))))
                                          (mapv :path)
                                          seq))]
          (swap! result assoc :project-files (vec (take 50 files)))))
      ;; 2. Config files (gated on matches?)
      (when matches?
        (when-let [configs (safe-exec :config-files
                                      #(let [r (config/list-config-files (get-dirs) :pattern "*")]
                                         (->> (concat (:user-files r) (:project-files r))
                                              (filter matches?)
                                              seq)))]
          (swap! result assoc :config-files (vec configs))))
      ;; 3. Registered tools (gated on matches?)
      (when matches?
        (when-let [tools (safe-exec :tools
                                    #(->> (binding [proto/*current-agent* agent]
                                            (collapsed-visible-tools {}))
                                          (filter (fn [t] (or (matches? (:id t))
                                                              (matches? (:description t)))))
                                          seq))]
          (swap! result assoc :tools
                 (mapv #(select-keys % [:id :type :description]) tools))))
      ;; 4. Memory recall — uses raw query, runs even when kept-tokens is empty.
      (when (and agent (not (str/blank? q)))
        (when-let [mem-results
                   (safe-exec :memory
                              #(when-let [mm (some-> agent :!state deref :memory-manager)]
                                 (let [results (mem/contextual-recall
                                                mm q
                                                :weights {:semantic 0.6 :episodic 0.4}
                                                :match :and
                                                :limit (or memory-limit 5))]
                                   (when (seq results)
                                     (mapv (fn [r]
                                             {:content (:content r)
                                              :type    (keyword (or (:_layer r) "unknown"))})
                                           results)))))]
          (swap! result assoc :memory mem-results)))
      @result))
  :input-schema  [:map
                  [:query        [:string {:desc "Search query. Whitespace-split; tokens <3 chars dropped for file/config/tool match (AND, case-insensitive). Raw query used for memory recall."}]]
                  [:memory-limit {:optional true} [:int {:desc "Max results for memory recall (default 5)" :default 5}]]]
  :output-schema [:map
                  [:project-files {:optional true} [:vector {:desc "Matching file paths"} :string]]
                  [:config-files  {:optional true} [:vector {:desc "Matching config file paths"} :string]]
                  [:tools         {:optional true} [:vector {:desc "Matching tools [{:id :type :description}]"}
                                                    [:map [:id :keyword] [:type :keyword] [:description :string]]]]
                  [:memory        {:optional true} [:vector {:desc "Matching memory entries [{:content :type}]"}
                                                    [:map [:content :string] [:type :keyword]]]]])

;; `tree` deftool removed — agents render directory trees via
;; `bash` (e.g. `tree -L 4`, or `find . -maxdepth 4 -type d`).

;; ============================================================================
;; Time Tools
;; ============================================================================

;; Shared "instant" shape — one source of truth so every instant-returning
;; time tool (time$now, time$add) advertises identical field names. See
;; `core.timeutil` for the canonical map.
(def ^:private time-instant-schema
  [:map
   [:iso               [:string  {:desc "ISO-8601 offset date-time"}]]
   [:epoch-ms          [:int     {:desc "Milliseconds since the Unix epoch"}]]
   [:epoch-sec         [:int     {:desc "Seconds since the Unix epoch"}]]
   [:tz-iana           [:string  {:desc "IANA timezone the instant is rendered in"}]]
   [:tz-offset-minutes [:int     {:desc "UTC offset in minutes"}]]
   [:day-of-week       [:string  {:desc "English day-of-week name"}]]
   [:error             {:optional true} [:string {:desc "Error message on failure"}]]])

(deftool time$now
  "Current wall-clock time. Optional :tz (IANA zone) renders it in another zone."
  (fn [{:keys [tz]}]
    (try (timeutil/now-map tz)
         (catch Exception e {:error (.getMessage e)})))
  :input-schema  [:map
                  [:tz {:optional true} [:string {:desc "IANA timezone (e.g. \"UTC\", \"Asia/Seoul\"); default system zone"}]]]
  :output-schema time-instant-schema)

(deftool time$add
  "Calendar-correct date math (DST/month/leap aware). Shift :base (ISO string or epoch-ms; default now) by any of :years/:months/:weeks/:days/:hours/:minutes/:seconds (negative allowed)."
  (fn [{:keys [base tz] :as args}]
    (try (timeutil/add-map base tz (select-keys args [:years :months :weeks :days
                                                      :hours :minutes :seconds]))
         (catch Exception e {:error (.getMessage e)})))
  :input-schema  [:map
                  [:base    {:optional true} [:string {:desc "Start time: ISO-8601 string or epoch-ms; default now"}]]
                  [:tz      {:optional true} [:string {:desc "IANA timezone for the shift and rendering; default system zone"}]]
                  [:years   {:optional true} [:int {:desc "Years to add (may be negative)"}]]
                  [:months  {:optional true} [:int {:desc "Months to add (may be negative)"}]]
                  [:weeks   {:optional true} [:int {:desc "Weeks to add (may be negative)"}]]
                  [:days    {:optional true} [:int {:desc "Days to add (may be negative)"}]]
                  [:hours   {:optional true} [:int {:desc "Hours to add (may be negative)"}]]
                  [:minutes {:optional true} [:int {:desc "Minutes to add (may be negative)"}]]
                  [:seconds {:optional true} [:int {:desc "Seconds to add (may be negative)"}]]]
  :output-schema time-instant-schema)

(deftool time$diff
  "Elapsed time between :from and :to (each ISO string or epoch-ms; :to defaults to now). Returns signed totals, a humanized magnitude, and a calendar breakdown."
  (fn [{:keys [from to tz]}]
    (try (timeutil/diff-map from to tz)
         (catch Exception e {:error (.getMessage e)})))
  :input-schema  [:map
                  [:from [:string {:desc "Start time: ISO-8601 string or epoch-ms"}]]
                  [:to   {:optional true} [:string {:desc "End time: ISO-8601 string or epoch-ms; default now"}]]
                  [:tz   {:optional true} [:string {:desc "IANA timezone for the calendar breakdown; default system zone"}]]]
  :output-schema [:map
                  [:from      [:map    {:desc "Canonical instant map for :from"}]]
                  [:to        [:map    {:desc "Canonical instant map for :to"}]]
                  [:direction [:string {:desc "\"future\" (to after from), \"past\", or \"same\""}]]
                  [:ms        [:int    {:desc "Signed total milliseconds (to − from)"}]]
                  [:seconds   [:int    {:desc "Signed total seconds"}]]
                  [:minutes   [:int    {:desc "Signed total minutes (truncated)"}]]
                  [:hours     [:int    {:desc "Signed total hours (truncated)"}]]
                  [:days      [:int    {:desc "Signed total days (truncated)"}]]
                  [:humanized [:string {:desc "Compact magnitude, e.g. \"2d 4h 15m\""}]]
                  [:calendar  [:map    {:desc "Calendar magnitude {:years :months :days :hours :minutes :seconds}"}]]
                  [:error     {:optional true} [:string {:desc "Error message on failure"}]]])

;; ============================================================================
;; Tool Categories
;; ============================================================================

(def bootstrap-tools
  "Tools for discovering and inspecting what's available to the agent:
   list/get-tool-info inspect the tool registry; search spans project
   files, config files, long-term memory, and registered tools."
  [#'list-tools
   #'get-tool-info
   #'search])

(def invocation-tools
  "Tools for invoking other registered tools (incl. MCP fallback)"
  [#'call-tool])

(def shell-tools
  "Tools for executing shell commands."
  [#'bash])

(def interaction-tools
  "Tools for soliciting user feedback"
  [#'get-user-feedback])

(def file-tools
  "Tools for file/URL access (read, write, grep, fetch)"
  [#'read-file
   #'write-file
   #'update-file
   #'grep
   #'fetch-url])

(def web-tools
  "Tools for searching and fetching from the web (web-search, fetch-url)"
  [#'web-search
   #'fetch-url])

(def time-tools
  "Wall-clock tools: current time, calendar-correct date math, elapsed time."
  [#'time$now
   #'time$add
   #'time$diff])

(def all-common-tools
  "All common deftool tools available for sandbox/agent use"
  (vec (distinct (concat bootstrap-tools
                         invocation-tools
                         shell-tools
                         interaction-tools
                         file-tools
                         web-tools
                         time-tools))))
