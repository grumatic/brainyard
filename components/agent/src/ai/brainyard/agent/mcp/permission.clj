(ns ai.brainyard.agent.mcp.permission
  "Fail-closed permission gate for MCP tool calls.

   MCP tools reach external systems (Linear, Slack, GitHub, …) and can have
   real side effects, so by default a side-effecting MCP call must be approved
   before it runs — through the *same* `permission-fn` UI that gates
   `write-file`/`bash` (the in-stream / tmux-popup confirm with an
   always/never session cache).

   The gate is a single `:agent.tool-use/pre` decision hook (like eval-agent's
   read-only `eval-bash-guard`). It fires inside `dispatch-with-hooks`, which
   wraps BOTH MCP call paths:

     - native bindings  `mcp$<server>$<tool>`  (registry dispatch), and
     - the proxy        `mcp$tools :op :call`  (mcp$tools is itself a
                                                registered tool, so the hook
                                                sees its `:op :call` args).

   Policy (config `[:permissions :mode]` / `:permission-mode`, `:auto` resolved
   via `resolve-permission-mode` before this branch):
     :auto-approve     → allow everything (no prompt).
     :deny-by-default  → block every side-effecting MCP call (no prompt).
     :ask-each-time    → prompt via `permission-fn`; when there is no
                         interactive channel (headless / sub-agent with no
                         permission-fn) the call is REFUSED — fail-closed,
                         mirroring write-file's headless behavior.
     :auto             → (default) :auto-approve in a detected container, else
                         :ask-each-time (bare host still prompts).

   Downgraded to auto-allow (never prompts):
     - tools whose MCP `annotations.readOnlyHint` is true, and
     - tools matching a `:mcp-allow-tools` `server/tool` glob.

   On refusal the hook returns a `:replace` verdict carrying an `{:error …}`
   result, so the model sees a clear denial and the turn continues (the MCP
   call never executes)."
  (:require
   [ai.brainyard.agent.core.config :as config]
   [ai.brainyard.agent.core.hooks :as hooks]
   [ai.brainyard.agent.core.session :as session]
   [ai.brainyard.agent.core.tool :as tool]
   [ai.brainyard.agent.mcp.integration :as mcp-int]
   [clojure.string :as str]
   [ai.brainyard.mulog.interface :as mulog]))

;; ---------------------------------------------------------------------------
;; Classification
;; ---------------------------------------------------------------------------

(defn- glob-match?
  "Match a `server/tool` glob (only `*` is special — any run of chars) against
   a concrete `server/tool` target string."
  [glob target]
  (let [parts (str/split (str glob) #"\*" -1)
        re    (re-pattern (str "^"
                               (str/join ".*" (map #(java.util.regex.Pattern/quote %) parts))
                               "$"))]
    (boolean (re-matches re (str target)))))

(defn- registry-annotations
  "MCP annotations captured on the native binding's registry `:meta` at
   registration (`register-mcp-tools-for-server!`). nil when the tool isn't
   registered (e.g. :auto-register-tools off) or the server sent none."
  [server tool]
  (get-in (tool/get-tool-defs :id (keyword (str "mcp$" server "$" tool)))
          [:meta :mcp-annotations]))

(defn- tool-annotations
  "MCP annotations for a (server, tool): the registered binding's :meta first,
   else the connect-time tools/list cache (no live RPC). The cache fallback lets
   a proxy-only / unregistered tool still be classified read-only."
  [server tool]
  (or (registry-annotations server tool)
      (mcp-int/cached-tool-annotations server tool)))

(defn- read-only?
  "True when the MCP tool declares `readOnlyHint`. Absent ⇒ false (fail-closed)."
  [server tool]
  (let [ann (tool-annotations server tool)]
    (boolean (or (:readOnlyHint ann) (get ann "readOnlyHint")))))

(defn- allowlisted?
  "True when `server/tool` matches any `:mcp-allow-tools` glob."
  [agent server tool]
  (let [globs (config/get-config agent :mcp-allow-tools)]
    (boolean (some #(glob-match? % (str server "/" tool)) globs))))

(defn- needs-approval?
  "A target needs approval unless it is read-only-hinted or allowlisted."
  [agent server tool]
  (not (or (read-only? server tool)
           (allowlisted? agent server tool))))

;; ---------------------------------------------------------------------------
;; Target extraction (both call paths)
;; ---------------------------------------------------------------------------

(defn native-target
  "For a native binding `mcp$<server>$<tool>`, return {:server :tool}; else nil.
   Server/tool names can't contain `$` (see safe-symbol-name?), so a valid id
   splits into exactly [\"mcp\" server tool]."
  [tool-name]
  (let [parts (str/split (str tool-name) #"\$")]
    (when (and (= 3 (count parts)) (= "mcp" (first parts)))
      {:server (nth parts 1) :tool (nth parts 2)})))

(defn proxy-call?
  "True when this is the `mcp$tools :op :call` proxy invocation."
  [tool-name args]
  (and (= "mcp$tools" (str tool-name))
       (= "call" (some-> (:op args) name))))

(defn- proxy-targets
  "Extract {:targets [{:server :tool}…] :parseable? bool} from a proxy :call's
   `:tool-calls`. When tool-calls isn't a decoded vector (e.g. a raw JSON
   string), :parseable? is false → the gate treats it as approval-required."
  [args]
  (let [tcs (:tool-calls args)]
    (if (vector? tcs)
      {:parseable? true
       :targets (mapv (fn [tc] {:server (:server-name tc) :tool (:tool-name tc)}) tcs)}
      {:parseable? false :targets []})))

;; ---------------------------------------------------------------------------
;; Verdict
;; ---------------------------------------------------------------------------

(defn- permission-fn [agent]
  (some-> (:!session agent) deref (session/get-session-config :permission-fn)))

(defn- target-display [targets]
  (if (= :unknown targets)
    "one or more MCP tools (unrecognized batch)"
    (str/join ", " (map (fn [{:keys [server tool]}] (str server "/" tool)) targets))))

(defn- deny-replace [targets reason]
  (let [display (target-display targets)]
    {:result      :replace
     :by          ::mcp-permission-gate
     :reason      (str "MCP permission refused: " display " — " reason)
     :replacement {:error (str "MCP tool call refused (permission): " display ". "
                               reason ". "
                               "To allow it: approve interactively, add a matching "
                               ":mcp-allow-tools glob, or set [:permissions :mode] :auto-approve.")}}))

(defn- mcp-request
  "Build the permission-fn request for an MCP approval prompt. The TUI's
   make-permission-fn renders/caches `:type :mcp-tool` requests."
  [targets]
  (let [ts (if (= :unknown targets) [] targets)]
    {:type    :mcp-tool
     :action  :call
     :tools   (mapv (fn [{:keys [server tool]}] (str server "/" tool)) ts)
     :servers (vec (distinct (map :server ts)))
     :display (target-display targets)}))

(defn- gate-verdict
  "Decide allow (nil) / refuse (:replace) for a set of approval-needing
   targets, honoring [:permissions :mode] (`:auto` resolved via
   `resolve-permission-mode` — :auto-approve in a container, else prompt)."
  [agent targets]
  (case (config/resolve-permission-mode agent)
    :auto-approve    nil
    :deny-by-default (deny-replace targets "permission mode is :deny-by-default")
    ;; :ask-each-time (default) and anything else → prompt, fail-closed headless
    (if-let [pfn (permission-fn agent)]
      (let [resp (try (pfn (mcp-request targets))
                      (catch Exception e
                        {:denied true :reason (str "permission prompt error: " (ex-message e))}))]
        (if (:allowed resp)
          nil
          (deny-replace targets (or (:reason resp) "denied by user"))))
      (deny-replace targets "no interactive permission channel (headless)"))))

(defn mcp-permission-gate
  "`:agent.tool-use/pre` handler. Returns a `:replace` refusal for an
   unapproved side-effecting MCP call, else nil (allow)."
  [{:keys [agent tool-name args]}]
  (let [tname (name (or tool-name ""))]
    (cond
      ;; native binding mcp$<server>$<tool>
      (native-target tname)
      (let [{:keys [server tool]} (native-target tname)]
        (when (needs-approval? agent server tool)
          (gate-verdict agent [{:server server :tool tool}])))

      ;; proxy mcp$tools :op :call (possibly a batch)
      (proxy-call? tname args)
      (let [{:keys [parseable? targets]} (proxy-targets args)]
        (if-not parseable?
          ;; can't introspect the batch → fail-closed
          (gate-verdict agent :unknown)
          (let [needing (filterv #(needs-approval? agent (:server %) (:tool %)) targets)]
            (when (seq needing)
              (gate-verdict agent needing)))))

      :else nil)))

(defn- mcp-call-event?
  "Cheap :match predicate — only fire the gate for MCP call paths."
  [{:keys [tool-name args]}]
  (let [t (name (or tool-name ""))]
    (boolean (or (native-target t) (proxy-call? t args)))))

(defn install-mcp-permission-gate!
  "Register the fail-closed MCP permission gate on `:agent.tool-use/pre`.
   Idempotent — register-hook! replaces by id. High priority so it vetoes
   before lighter pre-hooks dispatch the call."
  []
  (hooks/register-hook!
   :agent.tool-use/pre
   ::mcp-permission-gate
   mcp-permission-gate
   :source   :mcp
   :match    mcp-call-event?
   :priority 80)
  (mulog/info ::mcp-permission-gate-installed))

(install-mcp-permission-gate!)
