;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.common.script-bridge
  "The script bridge — P2 of docs/design/script-agent-design.md.

   A script-only agent has no tool channel and no clojure fence, so the tool
   REGISTRY is unreachable from it: no `memory$recall`, no `task$detail`, no
   sub-agent dispatch (§7 of the design names what that costs). This gives a
   bash or python block a way back in, WITHOUT restoring the tool channel: the
   model still emits only script fences, and the way in is an executable —
   `by-tool` — like every other capability it has.

   Mechanism: the agent binds its own AF_UNIX socket and exports the path as
   `BY_TOOL_SOCK` in the block environment. `by-tool` writes one EDN frame and
   reads one back. The transport is `components/ask-channel` unchanged —
   `start-listener!` is already \"bind a socket, serve `(fn [req] response)`\",
   so this supplies a handler and nothing else.

   Four things that are load-bearing:

   - **OFF by default** (`:enable-script-bridge`). This is the one part of the
     design that adds REACH rather than persistence: everything else a script
     does, a bash fence could already do. A new privilege surface is opt-in or
     it is a surprise.

   - **An ALLOWLIST, not the registry.** `:script-bridge-tools` names what is
     reachable. Exposing `call-tool` wholesale would hand a script the write
     surface of every agent in the process — `edit-agent`, `write-file`,
     `mcp$*` — through a door opened for memory recall. The default set is the
     read/observe half of what §7 said was lost.

   - **Its OWN socket, not the session's `ask.sock`.** That one is per-session,
     exists only under the TUI (a `by ask` run has none), and carries the
     USER's turn queue — injecting tool calls there would mix a script's
     bookkeeping into the human's conversation. This socket is per agent
     INSTANCE, so `memory$*` resolves the right identity, and it dies with the
     agent.

   - **A SHORT path, by construction.** AF_UNIX caps the path near 104 bytes,
     and a session dir nested under a deep project path blows that (the same
     trap `ask.sock` hit and had to add a fallback for). Naming the socket
     from a hash into `$TMPDIR` means there is no long case to fall back from."
  (:require [ai.brainyard.agent.core.config :as config]
            [ai.brainyard.agent.core.hooks :as hooks]
            [ai.brainyard.agent.core.protocol :as proto]
            [ai.brainyard.agent.core.tool :as tool]
            [ai.brainyard.ask-channel.interface :as ask-channel]
            [ai.brainyard.mulog.interface :as mulog]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.security MessageDigest]))

;; ============================================================================
;; Socket path
;; ============================================================================

(defn- sha8
  [^String s]
  (let [d (.digest (MessageDigest/getInstance "SHA-256") (.getBytes s "UTF-8"))]
    (str/join (map #(format "%02x" %) (take 4 d)))))

(defn socket-path
  "`<tmpdir>/by-tool-<8 hex>.sock` — short by construction.

   Keyed on the agent id AND the pid: two `by` processes in the same project
   must not collide on one socket, and `start-listener!` refuses to clobber a
   live owner, so a collision would surface as a bridge that silently never
   starts for the second process."
  [agent]
  (let [aid (str (try (proto/agent-id agent) (catch Throwable _ "anon")))
        pid (str (.pid (java.lang.ProcessHandle/current)))]
    (str (io/file (or (System/getenv "TMPDIR") "/tmp")
                  (str "by-tool-" (sha8 (str aid "|" pid)) ".sock")))))

;; ============================================================================
;; Request handling
;; ============================================================================

(defn- coerce-val
  "`--last-n 40` arrives as the STRING \"40\". Malli coercion in `call-tool`
   handles many cases, but not every tool declares a coercible schema, so the
   obvious scalars are recovered here where the shape is unambiguous.

   Deliberately NOT `read-string`: these values come from a script the model
   wrote, and evaluating them would turn an argument into code."
  [^String v]
  (cond
    (= v "true")  true
    (= v "false") false
    (re-matches #"-?\d+" v)              (parse-long v)
    (re-matches #"-?\d*\.\d+" v)         (parse-double v)
    (str/starts-with? v ":")             (keyword (subs v 1))
    :else v))

(defn argv->args
  "`[\"--query\" \"cache zones\" \"--last-n\" \"40\" \"--json\"]`
   → `{:query \"cache zones\" :last-n 40 :json true}`.

   Argument parsing happens SERVER-side on purpose. The alternative is the shim
   composing EDN in bash, which means quoting model-authored strings into a
   reader — one unbalanced quote away from a frame that parses as something
   else. Shipping raw argv keeps the shell side to a list of strings."
  [argv]
  (loop [[a b & more :as all] (seq argv), acc {}]
    (cond
      (empty? all) acc
      (and (string? a) (str/starts-with? a "--"))
      (let [k (keyword (subs a 2))]
        (if (and b (not (str/starts-with? b "--")))
          (recur more (assoc acc k (coerce-val b)))
          (recur (rest all) (assoc acc k true))))
      ;; A bare positional is not addressable — every registered tool takes a
      ;; named map — so record it rather than dropping it silently.
      :else (recur (rest all) (update acc :_positional (fnil conj []) a)))))

(defn allowed-tools
  "The reachable set, as keywords. Empty means the bridge answers nothing,
   which is what an operator who enabled the gate but cleared the list asked
   for — not an invitation to fall back to the whole registry."
  [cfg-snap]
  (into #{} (map keyword) (or (get cfg-snap :script-bridge-tools) [])))

(defn- shell-ate-the-dollar-hint
  "Every registered tool name contains a `$`, and in an unquoted bash word
   `$status` is a VARIABLE — so `by-tool memory$status` arrives here as
   `memory`, with the rest silently eaten. Measured on the first live run of
   the bridge.

   The shell has already destroyed the information by the time we see it, so
   this cannot be repaired — but when the mangled name is the prefix of exactly
   the tools that were allowed, saying so turns a confusing refusal into a
   self-correcting one, which is the difference between costing an iteration
   and costing a turn."
  [requested allow]
  (let [cands (filter #(str/starts-with? (name %) (str requested "$")) allow)]
    (when (and (not (str/includes? requested "$")) (seq cands))
      (str "\n\nNOTE: `$` is a variable sigil in bash and was almost certainly "
           "eaten before by-tool saw it. QUOTE the tool name: "
           (str/join " or " (map #(str "by-tool '" (name %) "'") (sort cands)))))))

(defn handle-req
  "Serve one frame. Ops:

     {:op :tool  :tool \"memory$recall\" :argv [\"--query\" \"x\"]}
     {:op :ping}

   Errors are VALUES with a `:status`, never thrown — the caller is a shell
   script reading one line, and a stack trace on the wire is not something it
   can act on."
  [agent req]
  (let [cfg (config/get-config-snapshot agent)]
    (try
      (case (:op req)
        :ping {:status :ok :agent (str (proto/agent-id agent))}

        :tool
        (let [tname (keyword (str (:tool req)))
              allow (allowed-tools cfg)]
          (if-not (contains? allow tname)
            {:status :error
             :error  (str "tool `" (name tname) "` is not on the script-bridge "
                          "allowlist. Allowed: "
                          (str/join ", " (sort (map name allow)))
                          (shell-ate-the-dollar-hint (name tname) allow))}
            (let [args (argv->args (:argv req))
                  r    (tool/call-tool tname args :agent agent)]
              (mulog/log ::bridge-call :tool tname :args (keys args))
              (if (and (map? r) (:error-message r))
                {:status :error :error (:error-message r)}
                {:status :ok :result r}))))

        {:status :error :error (str "unknown op: " (pr-str (:op req)))})
      (catch Throwable t
        {:status :error :error (str (.getSimpleName (class t)) ": " (ex-message t))}))))

;; ============================================================================
;; Lifecycle
;; ============================================================================

(defn enabled?
  [cfg-snap]
  (boolean (get cfg-snap :enable-script-bridge)))

(defn start!
  "Bind this agent's bridge socket and record the handle in `!state`. Returns
   the socket path, or nil when the bridge is off or the bind failed.

   Never throws: a bridge that cannot bind leaves an agent that simply has no
   `BY_TOOL_SOCK`, and `by-tool` says so plainly. Failing the turn instead
   would make an optional capability a hard dependency."
  [agent]
  (let [cfg (config/get-config-snapshot agent)]
    (when (and agent (enabled? cfg))
      (or (:path (get @(:!state agent) :script-bridge))
          (try
            (let [p (socket-path agent)
                  h (ask-channel/start-listener! p #(handle-req agent %))]
              (swap! (:!state agent) assoc :script-bridge (assoc h :path p))
              (mulog/info ::bridge-started :path p)
              p)
            (catch Throwable t
              (mulog/warn ::bridge-start-failed :error (ex-message t))
              nil))))))

(defn stop!
  "Close this agent's listener and unlink its socket. Idempotent."
  [agent]
  (when-let [h (and agent (get @(:!state agent) :script-bridge))]
    (try (ask-channel/stop-listener! h) (catch Throwable _ nil))
    (swap! (:!state agent) dissoc :script-bridge)
    nil))

(defn register-hooks!
  "Close the socket when the agent closes.

   A hook rather than a line in `core/agent`'s `Closeable/close`: the bridge is
   a `common/` concern and core must not learn about it to clean it up. Same
   shape as router-agent's lifecycle hooks. Idempotent — `register-hook!`
   dedupes on [event-key handler-id]."
  []
  (hooks/register-hook!
   :agent.instance/closed
   :script-bridge/close
   (fn [{:keys [agent]}] (stop! agent) nil)
   :source :script-bridge))

;; Registered at load, like core/agent's own `(register-hooks!)`. Idempotent —
;; `register-hook!` replaces on [event-key handler-id] — so a `:reload` cannot
;; accumulate duplicates, and a namespace that is merely on the classpath
;; costs one map entry when the bridge is off.
(register-hooks!)
