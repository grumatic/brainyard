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
     :claude-agent-acp   Claude Code over ACP, via npx (Anthropic).
     :gemini             Google gemini-cli with ACP mode.
     :codex              OpenAI codex CLI with ACP mode.

   Custom backends can be added at runtime via `register-backend!`."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
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
  "Merge user-supplied `:env` overrides on top of an opinionated default
   env (which copies through API key env vars when present)."
  [defaults user-env]
  (reduce-kv (fn [m k v]
               (if (some? v) (assoc m (str k) (str v)) m))
             (or user-env {})
             defaults))

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

(defn claude-agent-acp-launch-spec
  "Launch Anthropic's Claude Code over ACP via npx.

   Default command:
     npx -y @zed-industries/claude-code-acp

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
     - either ANTHROPIC_API_KEY in env, or a logged-in `claude` CLI."
  ([] (claude-agent-acp-launch-spec {}))
  ([{:keys [command working-dir env forward-env]
     :or   {command     ["npx" "-y" "@zed-industries/claude-code-acp"]
            forward-env ["ANTHROPIC_API_KEY" "ANTHROPIC_AUTH_TOKEN"
                         "PATH" "HOME"]}}]
   {:command     command
    :working-dir (or working-dir (System/getProperty "user.dir"))
    :env         (merge-env (copy-env forward-env) env)}))

(defn gemini-launch-spec
  "Launch Google's gemini-cli in ACP mode.

   Default command:
     gemini --experimental-acp

   Override with `:command` when the flag changes or when running a
   different gemini distribution.

   Options:
     :command          Vector of command tokens (default above).
     :working-dir      Cwd (default: user.dir).
     :env              Extra env vars on top of forwarded ones.
     :forward-env      Names of parent env vars to forward (default:
                       [\"GEMINI_API_KEY\" \"GOOGLE_API_KEY\"
                        \"PATH\" \"HOME\"]).

   Required prereqs:
     - `gemini` on PATH
     - GEMINI_API_KEY (or GOOGLE_API_KEY) in env."
  ([] (gemini-launch-spec {}))
  ([{:keys [command working-dir env forward-env]
     :or   {command     ["gemini" "--experimental-acp"]
            forward-env ["GEMINI_API_KEY" "GOOGLE_API_KEY"
                         "PATH" "HOME"]}}]
   {:command     command
    :working-dir (or working-dir (System/getProperty "user.dir"))
    :env         (merge-env (copy-env forward-env) env)}))

(defn codex-launch-spec
  "Launch OpenAI's codex CLI in ACP mode.

   Default command:
     codex --acp

   Override with `:command` if the flag differs in your installation.

   Options:
     :command          Vector of command tokens (default above).
     :working-dir      Cwd (default: user.dir).
     :env              Extra env vars on top of forwarded ones.
     :forward-env      Names of parent env vars to forward (default:
                       [\"OPENAI_API_KEY\" \"PATH\" \"HOME\"]).

   Required prereqs:
     - `codex` on PATH
     - OPENAI_API_KEY in env."
  ([] (codex-launch-spec {}))
  ([{:keys [command working-dir env forward-env]
     :or   {command     ["codex" "--acp"]
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

    :claude-agent-acp
    {:factory      claude-agent-acp-launch-spec
     :description  "Claude Code over ACP (Anthropic) via npx @zed-industries/claude-code-acp."
     :experimental true
     :prereqs      ["npx"]}

    :gemini
    {:factory      gemini-launch-spec
     :description  "Google gemini-cli with --experimental-acp flag."
     :experimental true
     :prereqs      ["gemini"]}

    :codex
    {:factory      codex-launch-spec
     :description  "OpenAI codex CLI with --acp flag."
     :experimental true
     :prereqs      ["codex"]}}))

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
     (throw (ex-info "unknown ACP backend"
                     {:backend backend
                      :supported (vec (keys @!backends))})))))
