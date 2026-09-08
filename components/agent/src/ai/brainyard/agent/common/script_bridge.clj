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
     reachable, as literal names or glob patterns (`mcp$*`, `user$tool$*`,
     `*`). The default is the read/observe half of what §7 said was lost, plus
     `list-tools`/`get-tool-info` — knowing what exists is not reach. `{:op
     :list}` (`by-tool --list`) resolves the patterns against the registry, so
     a script DISCOVERS the set instead of paying an iteration to be refused.

     What the allowlist is NOT is a security boundary — this agent has a bash
     fence, so it already runs arbitrary code as the user, and `call-tool`
     runs no permission check worth the name (`core/tool`'s `permission-config`
     is a hardcoded empty map and `:approval-required` is computed and
     discarded). It is a BLAST-RADIUS and LEGIBILITY control, and that is why
     `*` is a supported pattern while the `call-tool` TOOL stays off the
     default: both grant the same reach, but `*` leaves `::bridge-call`
     naming `config$apply`, where routing through `call-tool` makes every
     line read `:tool \"call-tool\"` and the audit trail stops saying
     anything. Widening is an operator's call; blurring is nobody's.

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

(defn- glob->re
  "`user$*` → `^\\Quser$\\E.*$`.

   The literal segments are `Pattern/quote`d rather than escaped by hand, and
   that is the whole reason this is not `tool/glob->regex`: EVERY registered
   tool name contains `$`, which inside a regex is an end-of-input ANCHOR. The
   naive `(str/replace pat \"*\" \".*\")` compiles `mcp$*` to `^mcp$.*$` —
   a pattern that matches the empty-ish string and NOTHING that starts with
   `mcp$`. It would fail open or closed depending on the tool, silently.

   `*` spans `$` deliberately, so `user$*` covers `user$tool$create` and the
   nested `user$tool$*` / `user$agent$*` forms are refinements rather than
   additions."
  [pat]
  (re-pattern (str "^"
                   (->> (str/split pat #"\*" -1)
                        (map #(java.util.regex.Pattern/quote %))
                        (str/join ".*"))
                   "$")))

(def ^:private glob->re* (memoize glob->re))

(defn allow-patterns
  "The configured entries as pattern STRINGS. Keywords and strings both, since
   `:memory$recall` and `\"mcp$*\"` are the two natural ways to write one and a
   config file that mixes them should not half-work."
  [cfg-snap]
  (into [] (map #(if (keyword? %) (name %) (str %)))
        (or (get cfg-snap :script-bridge-tools) [])))

(defn allowed?
  "The GATE. Matches a name against the patterns directly, never against a
   resolved set — so a tool registered mid-session (a `user$tool$*` the agent
   just authored) is reachable immediately, which is the only thing that makes
   a family pattern worth writing.

   Empty patterns deny everything: an operator who enabled the gate and cleared
   the list asked for that, and it is not an invitation to fall back to the
   registry."
  [cfg-snap ^String tool-name]
  (boolean (some #(re-matches (glob->re* %) tool-name)
                 (allow-patterns cfg-snap))))

(defn registry-tool-names
  "Every registered tool name, sorted. Includes ones hidden from agent rosters
   (`call-tool` is `:visibility :hidden`): `tool-use-control` governs what a
   PROMPT advertises to an LLM, which is a different question from what an
   operator's `*` reaches."
  []
  (sort (map name (keys (tool/get-tool-defs)))))

(defn match-names
  "Pure: which of `candidates` any pattern admits, sorted and distinct.
   Separate from `allowed?` so the display path can be tested without a
   registry, and so the gate never pays a registry read."
  [patterns candidates]
  (let [res (mapv glob->re* patterns)]
    (into [] (comp (filter (fn [c] (some #(re-matches % c) res))) (distinct))
          (sort candidates))))

(defn allowed-tools
  "The reachable set RESOLVED against the registry, as sorted name strings —
   what `--list` prints and what a denial names. Distinct from `allowed?`,
   which answers about one name: with `user$*` configured the two can disagree
   about a tool that is not registered yet, and each is right for its job."
  [cfg-snap]
  (match-names (allow-patterns cfg-snap) (registry-tool-names)))

(def ^:private max-listed
  "How many names an ERROR may carry. `--list` is unbounded — it was asked for.
   A denial under `*` would otherwise paste 300+ names into a turn."
  20)

(defn- brief-list
  [names]
  (cond
    (empty? names)               ""
    (<= (count names) max-listed) (str/join ", " names)
    :else (str (str/join ", " (take max-listed names))
               ", …and " (- (count names) max-listed)
               " more — run `by-tool --list`")))

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
  (let [cands (take 4 (filter #(str/starts-with? % (str requested "$")) allow))]
    (when (and (not (str/includes? requested "$")) (seq cands))
      (str "\n\nNOTE: `$` is a variable sigil in bash and was almost certainly "
           "eaten before by-tool saw it. QUOTE the tool name: "
           (str/join " or " (map #(str "by-tool '" % "'") cands))))))

(defn handle-req
  "Serve one frame. Ops:

     {:op :tool  :tool \"memory$recall\" :argv [\"--query\" \"x\"]}
     {:op :list}
     {:op :ping}

   Errors are VALUES with a `:status`, never thrown — the caller is a shell
   script reading one line, and a stack trace on the wire is not something it
   can act on."
  [agent req]
  (let [cfg (config/get-config-snapshot agent)]
    (try
      (case (:op req)
        :ping {:status :ok :agent (str (proto/agent-id agent))}

        ;; Discovery is an OP rather than a line in the shim's `--help`, for the
        ;; same reason the allowlist is config: the set is per agent and an
        ;; operator can edit it, so anything baked into the shim is a claim
        ;; about the defaults rather than an answer about THIS agent. Without
        ;; it a script's only way to learn the set is a deliberately-failing
        ;; call, which costs an iteration and reads as an error in the
        ;; transcript.
        :list {:status :ok
               :tools  (vec (allowed-tools cfg))}

        :tool
        (let [tname (str (:tool req))]
          (cond
            (not (allowed? cfg tname))
            {:status :error
             :error  (str "tool `" tname "` is not on the script-bridge "
                          "allowlist. Allowed: "
                          (brief-list (allowed-tools cfg))
                          (shell-ate-the-dollar-hint tname (allowed-tools cfg)))}

            ;; A pattern authorizes a NAME, so `*` happily admits `memory` —
            ;; the shell-mangled form of `memory$status`. Before the wildcard
            ;; existed the allowlist refused that by accident and the sigil
            ;; hint fired on the denial; now the same mistake reaches dispatch
            ;; and would come back as a bare "not registered". So the
            ;; membership check is its own step, and carries the same hint.
            (nil? (tool/get-tool-defs :id (keyword tname)))
            {:status :error
             :error  (str "tool `" tname "` is not registered."
                          (shell-ate-the-dollar-hint tname (allowed-tools cfg)))}

            :else
            (let [args (argv->args (:argv req))
                  r    (tool/call-tool (keyword tname) args :agent agent)]
              ;; The real tool name, never a `call-tool` indirection: the
              ;; allowlist doubles as this log's vocabulary, and a wildcard
              ;; widens what may be called without blurring what WAS.
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
