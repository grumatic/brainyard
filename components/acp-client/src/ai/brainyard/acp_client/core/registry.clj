;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.acp-client.core.registry
  "Named ACP backends. Each backend is a launch spec — given an `opts`
   map it returns a map suitable for `acp/create-stdio-transport`:

     {:command     [\"...\" ...]   ;; required
      :working-dir \"...\"          ;; optional; defaults to user.dir
      :env         {\"K\" \"V\"}}     ;; optional extra env vars

   Built-in backends:

     :stub               In-tree deterministic agent (bases/acp-stub-agent).
     :claude-code        Claude Code over ACP, via npx (Anthropic).
     :gemini             Google gemini-cli in ACP mode, via npx.
     :codex              OpenAI Codex over ACP, via npx (adapter package).

   The three real backends all launch through `npx`; their default
   commands track the ACP registry
   (https://cdn.agentclientprotocol.com/registry/v1/latest/registry.json),
   which is the authority on how ACP clients are expected to spawn a
   given agent. Verified against it 2026-08-29.

   Custom backends can be added at runtime via `register-backend!`."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [ai.brainyard.acp.interface :as acp]
            [ai.brainyard.mulog.interface :as mulog]))

;; =============================================================================
;; Workspace-root discovery (used by the :stub backend)
;; =============================================================================

(defn find-workspace-root
  "Walk up from `start-dir` (default cwd) until a `workspace.edn` is
   found; return its absolute path. Throws if none found above the
   start dir."
  ([] (find-workspace-root (System/getProperty "user.dir")))
  ([start-dir]
   (loop [d (io/file start-dir)]
     (cond
       (nil? d)
       (throw (ex-info "workspace.edn not found above start-dir"
                       {:start-dir start-dir}))

       (.exists (io/file d "workspace.edn"))
       (.getCanonicalPath d)

       :else
       (recur (.getParentFile d))))))

;; =============================================================================
;; Helpers — env merging + PATH lookup
;; =============================================================================

(defn- merge-env
  "Merge user-supplied `:env` overrides ON TOP of an opinionated default env
   (which copies through API key env vars when present).

   Normalization and precedence live in the shared ACP helper
   (`acp/merge-envs` -> `ai.brainyard.acp.core.env`): both sides are coerced
   to string->string BEFORE merging, so keyword/symbol keys and values
   cannot silently produce a \":ANTHROPIC_MODEL\" variable, nor survive as a
   second entry colliding with their string twin. User entries win — an
   explicit `:env` override beats a forwarded parent-process value."
  [defaults user-env]
  (acp/merge-envs defaults user-env))

(defn- copy-env
  "Build a map of env-var pairs for any keys whose value is set in the
   parent process — useful for forwarding API keys without hard-coding."
  [vars]
  (into {}
        (keep (fn [v] (when-let [val (System/getenv v)]
                        [v val])))
        vars))

(defn which
  "Return the absolute path of `cmd` on PATH, or nil. Used by
   `backend-available?` to gate experimental backends."
  [cmd]
  (some (fn [dir]
          (let [f (io/file dir cmd)]
            (when (and (.exists f) (.canExecute f))
              (.getAbsolutePath f))))
        (some-> (System/getenv "PATH")
                (str/split #":"))))

;; =============================================================================
;; Built-in launch-spec factories
;; =============================================================================

(defn stub-launch-spec
  "Launch the in-tree :stub backend via clj from the projects/acp-stub-agent
   project (which composes the bases/acp-stub-agent base with its brick
   deps per the Polylith convention — bases declare third-party deps only).

   Options:
     :workspace-root  Override workspace discovery (default: walk-up).
     :chunk-delay-ms  Inter-token streaming delay (default 5)."
  ([] (stub-launch-spec {}))
  ([{:keys [workspace-root chunk-delay-ms] :or {chunk-delay-ms 5}}]
   (let [root (or workspace-root (find-workspace-root))
         project-dir (str root "/projects/acp-stub-agent")]
     {:command     ["clj" "-M" "-m" "ai.brainyard.acp-stub-agent.core"
                    "--echo"
                    (str "--chunk-delay-ms=" chunk-delay-ms)]
      :working-dir project-dir})))

(defn claude-code-launch-spec
  "Launch Anthropic's Claude Code over ACP via npx.

   Default command:
     npx -y @agentclientprotocol/claude-agent-acp

   The adapter moved from the `@zed-industries` scope to
   `@agentclientprotocol` when the ACP registry took over distribution.
   The old package is DEPRECATED on npm and frozen at 0.16.2 (last
   published 2026-03-26) — `npx -y` on it silently keeps installing that
   build forever, so this is a rename we cannot decline. The registry
   entry (id `claude-acp`) pins 0.70.0 as of 2026-08-29; we deliberately
   stay unpinned so `npx -y` tracks latest, as before.

   Override with `:command` (a vector of strings) when using a different
   adapter package, a globally-installed binary, or local development.

   Options:
     :command          Vector of command tokens (default above).
     :working-dir      Cwd for the spawned process (default: user.dir).
     :env              Extra env vars on top of the forwarded ones.
     :forward-env      Names of parent env vars to forward (default:
                       [\"ANTHROPIC_API_KEY\" \"ANTHROPIC_AUTH_TOKEN\"
                        \"PATH\" \"HOME\"]).

   Required prereqs:
     - `npx` on PATH (Node.js installed)
     - either ANTHROPIC_API_KEY in env, or a logged-in `claude` CLI
       (subscription / Pro / Max)."
  ([] (claude-code-launch-spec {}))
  ([{:keys [command working-dir env forward-env]
     :or   {command     ["npx" "-y" "@agentclientprotocol/claude-agent-acp"]
            forward-env ["ANTHROPIC_API_KEY" "ANTHROPIC_AUTH_TOKEN"
                         "PATH" "HOME"]}}]
   {:command     command
    :working-dir (or working-dir (System/getProperty "user.dir"))
    :env         (merge-env (copy-env forward-env) env)}))

(defn gemini-launch-spec
  "Launch Google's gemini-cli in ACP mode.

   Default command:
     npx -y @google/gemini-cli --acp

   Two changes from the original `gemini --experimental-acp`, both taken
   from the ACP registry entry (id `gemini`, 0.57.0 as of 2026-08-29):
   the flag dropped its `--experimental-` prefix (the old spelling still
   works but is deprecated), and the registry launches via npx rather
   than assuming a global install — which is also what makes the backend
   usable on a machine that has Node but not gemini-cli.

   Override with `:command` to use a globally-installed `gemini` binary
   (`[\"gemini\" \"--acp\"]`) or a different distribution.

   Options:
     :command          Vector of command tokens (default above).
     :working-dir      Cwd (default: user.dir).
     :env              Extra env vars on top of forwarded ones.
     :forward-env      Names of parent env vars to forward (default:
                       [\"GEMINI_API_KEY\" \"GOOGLE_API_KEY\"
                        \"PATH\" \"HOME\"]).

   Required prereqs:
     - `npx` on PATH (Node.js installed)
     - GEMINI_API_KEY (or GOOGLE_API_KEY) in env, or a logged-in gemini CLI."
  ([] (gemini-launch-spec {}))
  ([{:keys [command working-dir env forward-env]
     :or   {command     ["npx" "-y" "@google/gemini-cli" "--acp"]
            forward-env ["GEMINI_API_KEY" "GOOGLE_API_KEY"
                         "PATH" "HOME"]}}]
   {:command     command
    :working-dir (or working-dir (System/getProperty "user.dir"))
    :env         (merge-env (copy-env forward-env) env)}))

(defn codex-launch-spec
  "Launch OpenAI's Codex over ACP via npx.

   Default command:
     npx -y @agentclientprotocol/codex-acp

   ACP support is a SEPARATE adapter package, not a `--acp` flag on the
   codex CLI — the original spec here assumed a flag the CLI does not
   have. Registry entry id `codex-acp`, pinned 1.7.0 as of 2026-08-29;
   we stay unpinned so `npx -y` tracks latest.

   Override with `:command` for a globally-installed adapter or local
   development.

   Options:
     :command          Vector of command tokens (default above).
     :working-dir      Cwd (default: user.dir).
     :env              Extra env vars on top of forwarded ones.
     :forward-env      Names of parent env vars to forward (default:
                       [\"OPENAI_API_KEY\" \"PATH\" \"HOME\"]).

   Required prereqs:
     - `npx` on PATH (Node.js installed)
     - OPENAI_API_KEY in env, or a logged-in codex CLI."
  ([] (codex-launch-spec {}))
  ([{:keys [command working-dir env forward-env]
     :or   {command     ["npx" "-y" "@agentclientprotocol/codex-acp"]
            forward-env ["OPENAI_API_KEY" "PATH" "HOME"]}}]
   {:command     command
    :working-dir (or working-dir (System/getProperty "user.dir"))
    :env         (merge-env (copy-env forward-env) env)}))

;; =============================================================================
;; Backend registry
;;
;; Each entry is a map:
;;   {:factory      (fn [opts] launch-spec-map)
;;    :description  one-line summary
;;    :experimental boolean — true for backends that depend on
;;                  external CLIs and may need user setup
;;    :prereqs      vec of executable names whose presence on PATH
;;                  indicates the backend is usable. Empty for :stub.}
;; =============================================================================

(def ^:private !backends
  (atom
   {:stub
    {:factory      stub-launch-spec
     :description  "In-tree deterministic stub agent (echoes the prompt token-by-token)."
     :experimental false
     :prereqs      ["clj"]}

    :claude-code
    {:factory      claude-code-launch-spec
     :description  "Claude Code over ACP (Anthropic) via npx @agentclientprotocol/claude-agent-acp."
     :experimental true
     :prereqs      ["npx"]}

    :gemini
    {:factory      gemini-launch-spec
     :description  "Google gemini-cli in ACP mode via npx @google/gemini-cli --acp."
     :experimental true
     :prereqs      ["npx"]}

    :codex
    {:factory      codex-launch-spec
     :description  "OpenAI Codex over ACP via npx @agentclientprotocol/codex-acp."
     :experimental true
     :prereqs      ["npx"]}}))

;; =============================================================================
;; Public API
;; =============================================================================

(defn list-backends
  "Return the registry as a map of `kw -> {:description :experimental
   :prereqs}` (without the `:factory` fn — easier to print/inspect)."
  []
  (into {}
        (map (fn [[k v]] [k (dissoc v :factory)]))
        @!backends))

(defn register-backend!
  "Register or replace a backend at runtime.

   Args:
     backend-key   Keyword identifier.
     factory       (fn [opts] -> launch-spec-map). Pure; no I/O.

   Optional kwargs:
     :description    One-line summary (string).
     :experimental   Boolean (default true for non-:stub).
     :prereqs        Vec of executable names whose PATH presence
                     indicates the backend is usable."
  [backend-key factory & {:keys [description experimental prereqs]
                          :or   {description  ""
                                 experimental true
                                 prereqs      []}}]
  (when-not (keyword? backend-key)
    (throw (ex-info "backend-key must be a keyword" {:backend-key backend-key})))
  (when-not (fn? factory)
    (throw (ex-info "factory must be a function" {:factory factory})))
  (swap! !backends assoc backend-key
         {:factory      factory
          :description  description
          :experimental experimental
          :prereqs      (vec prereqs)})
  (mulog/info ::backend-registered :backend backend-key)
  backend-key)

(defn unregister-backend!
  "Remove a backend from the registry. Returns true if removed, false
   if it wasn't registered. Refuses to remove `:stub`."
  [backend-key]
  (when (= :stub backend-key)
    (throw (ex-info ":stub backend cannot be unregistered" {})))
  (let [had? (contains? @!backends backend-key)]
    (swap! !backends dissoc backend-key)
    (boolean had?)))

(defn backend-available?
  "Check whether a registered backend's prereq executables are all
   present on PATH. Returns:
     :unregistered  unknown backend keyword
     :missing-prereqs  some prereqs not on PATH (with details)
     :ok            ready to spawn"
  [backend-key]
  (if-let [{:keys [prereqs]} (get @!backends backend-key)]
    (let [missing (filter (complement which) prereqs)]
      (if (seq missing)
        {:status :missing-prereqs :missing (vec missing) :prereqs prereqs}
        {:status :ok :prereqs prereqs}))
    {:status :unregistered :backend backend-key}))

(defn resolve-backend
  "Look up a backend by keyword and return its launch spec. Caller may
   pass `opts` (e.g. :chunk-delay-ms for :stub, :command for an
   experimental backend) to override defaults.

   Throws if the backend is unknown."
  ([backend] (resolve-backend backend {}))
  ([backend opts]
   (if-let [{:keys [factory]} (get @!backends backend)]
     (factory opts)
     (let [supported (vec (keys @!backends))]
       (throw (ex-info (str "unknown ACP backend " (pr-str backend)
                            "; supported: " (pr-str supported))
                       {:backend backend
                        :supported supported}))))))
