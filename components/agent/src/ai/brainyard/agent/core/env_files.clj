;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.core.env-files
  "Per-agent `.env` files — `docs/design/env-files-design.md` §3.2.

   `<project>/.brainyard/agents/<agent>/.env`, beside the dossiers and INDEX.md
   that agent already writes there.

   **Why this is not the property table.** `.env` values normally become JVM
   System Properties, because the JVM environment is immutable. That table is
   process-global and is written once in `-dispatch`, before any agent exists.
   Agents are not one-at-a-time — TUI tabs hold several sessions open, and
   sub-agent dispatch runs specialists concurrently under a parent — so a
   per-agent value written there would be visible to every other agent in the
   process, with the last writer winning. The mechanism has no scope to put it
   in, so this namespace keeps the values in an agent-keyed cache instead and
   feeds them to `config/env-policy`, which is already agent-scoped.

   **Why the value reaches CHILDREN rather than this process.** That is where
   the demand is: `gh`, an MCP server, a shell fence, an ACP backend. Merged
   into a policy's `:vars`, a per-agent secret inherits everything the scoping
   note already settled — an ancestor's `:env-deny` still removes it, additions
   still precede removals, and `env-policies` still composes the chain. A global
   reader asking a global question (`/login`, the `/model` picker) must not get
   one agent's answer, and does not.

   **Per agent TYPE, not per instance.** The directory is
   `agents/<defagent-type>` — `explore-agent`, `config-agent` — matching every
   existing artifact dir and matching what `:config-extra` scopes. The `agt-…`
   instance id names a level below that, and only for router-agent's routing
   logs."
  (:require [ai.brainyard.agent.core.config :as config]
            [ai.brainyard.agent.core.protocol :as proto]
            [ai.brainyard.mulog.interface :as mulog]
            [ai.brainyard.util.interface :as util]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(defn defagent-type
  "The defagent type NAME for `agent` — the directory under `agents/` — or nil.

   An instance id is `:<type>/<suffix>` for a dispatched specialist and a bare
   `:<type>` for a root agent, so the type is the namespace when there is one
   and the name otherwise. Same derivation `hooks/match-defagent-type` uses."
  [agent]
  ;; `proto/agent-id` throws on anything that is not an IAgent, and callers
  ;; reach here from a spawn site that may hold a stub, a bare map, or nil.
  ;; Answering "no type" is always safe — it means no per-agent file — where
  ;; throwing would take down the spawn.
  (let [aid (try (some-> agent proto/agent-id) (catch Throwable _ nil))]
    (when (keyword? aid)
      (or (namespace aid) (name aid)))))

(defn agent-env-path
  "Absolute path of `<project>/.brainyard/agents/<type>/.env` for `agent`, or
   nil when there is no project or no resolvable type. Does not create it."
  ([agent] (agent-env-path agent (config/project-dir)))
  ([agent project-dir]
   (when-let [t (defagent-type agent)]
     (when project-dir
       (str project-dir "/.brainyard/agents/" t "/.env")))))

(defonce ^:private !cache
  ;; {path -> {:mtime long :vars {name value}}}
  ;;
  ;; Keyed by PATH rather than by agent: two instances of the same specialist
  ;; share one file and should share one read. Re-read on an mtime change, so
  ;; `by env set` takes effect on the next spawn without a restart — the
  ;; property `config$reload` had to be built by hand for config.edn, available
  ;; here for free because there is no process-global cache to invalidate.
  (atom {}))

(defn- read-cached [^String path]
  (let [f (io/file path)]
    (if-not (.isFile f)
      (do (swap! !cache dissoc path) nil)
      (let [mt     (.lastModified f)
            cached (get @!cache path)]
        (if (and cached (= mt (:mtime cached)))
          (:vars cached)
          (let [vars (or (util/parse-env-file f) {})]
            (swap! !cache assoc path {:mtime mt :vars vars})
            ;; NAMES and the path, never a value — these files exist to hold
            ;; credentials, and the log is the easiest place to leak one.
            (mulog/log ::agent-env-loaded :path path :names (vec (sort (keys vars))))
            vars))))))

(defn agent-env
  "`{name value}` from `agent`'s own `.env`, or `{}`.

   Never throws: a missing file, an unresolvable project, an agent with no type
   and an unreadable file all resolve to `{}`. An agent's environment failing
   open to 'nothing extra' is correct; failing to start a spawn because a file
   is malformed is not."
  ([] (agent-env proto/*current-agent*))
  ([agent]
   (try
     (if-let [p (agent-env-path agent)]
       (or (read-cached p) {})
       {})
     (catch Throwable _ {}))))

(defn invalidate-cache!
  "Drop the cache. For tests and for a writer that wants its own next read to
   be fresh without waiting on mtime granularity."
  []
  (reset! !cache {})
  nil)

(defn resolve-for
  "The value of `var-name` as THIS AGENT sees it: its own `.env` first, then the
   process environment, then the `.env` layer in the property table.

   **Why this is not an arity of `util/resolve-var`.** That function is
   process-global by construction and is called from ~90 places where an agent
   means nothing — 69 of them `:env-fn` startup knobs. A scope parameter there
   would tax every one of them for the handful of readers that can supply one.
   The scoped read belongs where the scope does.

   **Why the agent file outranks the environment**, which is the opposite of
   the global chain. `apply-policy!` puts a policy's `:vars` into a child's
   environment unconditionally, overriding what the child inherited — so a
   child ALREADY sees agent-scope-over-process-env. An in-process read ordering
   them the other way would contradict the environment of the very children
   that agent spawns.

   **What it is for.** Without it a per-agent credential is split-brained: give
   `explore-agent` a read-only `GH_TOKEN` and `gh` invoked as a subprocess uses
   it while an in-process HTTP call from a tool uses the global one. One agent,
   two identities, differing on whether the call happened to shell out — and
   nothing announces it.

   Only for readers with an agent genuinely in scope. A global question —
   `/login`, the `/model` picker, `env-detect` asking whether this MACHINE is
   configured — must keep getting the global answer, and calls `resolve-var`."
  ([var-name] (resolve-for proto/*current-agent* var-name))
  ([agent var-name]
   (when-let [n (some-> var-name name not-empty)]
     (let [v (get (agent-env agent) n)]
       (if-not (str/blank? v)
         v
         ;; Blank falls through here too — the rule the whole tree shares, and
         ;; the one that stops an accidental empty line in a `.env` masking a
         ;; credential that is really set.
         (util/resolve-var n))))))

;; ============================================================================
;; Writing — `by env set` / `unset` / `import`
;;
;; These files hold credentials, so three rules apply to every write: mode
;; 0600, a `.gitignore` beside them, and never a value in a log line.
;; ============================================================================

(defn scope-path
  "Absolute path of the `.env` for a scope, or nil when unresolvable.

     {:agent \"explore-agent\"}  → <project>/.brainyard/agents/<agent>/.env
     {:scope :project}          → <project>/.brainyard/.env
     {:scope :user}             → ~/.brainyard/.env

   `:agent` wins over `:scope`, because naming an agent IS choosing a scope and
   the pair being contradictory is a caller error worth resolving one way."
  ([sel] (scope-path sel (config/init-dirs!)))
  ([{:keys [agent scope] :or {scope :project}} dirs]
   (cond
     agent (when-let [p (config/project-config-dir dirs)]
             (str p "/agents/" (name agent) "/.env"))
     (= :user scope)    (when-let [u (config/user-config-dir dirs)]
                          (str u "/.env"))
     (= :project scope) (when-let [p (config/project-config-dir dirs)]
                          (str p "/.env"))
     :else nil)))

(def ^:private gitignore-lines
  [".env" "agents/*/.env"])

(defn ensure-gitignore!
  "Ensure `<.brainyard>/.gitignore` excludes the env files. Returns the path
   when it wrote, nil when nothing was needed.

   This repository ignores `.brainyard/` wholly, but a project is documented as
   COMMITTING `<project>/.brainyard/` so its config travels with the codebase —
   and under that reading a `.brainyard/.env` would be committed. Assuming
   otherwise, silently, is the one way this feature could hurt someone, so the
   guard is written beside the file rather than left to the user's `.gitignore`.

   Idempotent, and additive: an existing file keeps whatever else it holds."
  [^String brainyard-dir]
  (when brainyard-dir
    (let [f       (io/file brainyard-dir ".gitignore")
          current (if (.isFile f) (slurp f) "")
          have    (into #{} (comp (map str/trim) (remove str/blank?))
                        (str/split-lines current))
          missing (remove have gitignore-lines)]
      (when (seq missing)
        (io/make-parents f)
        (spit f (str current
                     (when (and (seq current) (not (str/ends-with? current "\n"))) "\n")
                     (when (empty? have) "# brainyard env files — never commit\n")
                     (str/join "\n" missing) "\n"))
        (.getPath f)))))

(defn- render
  "`{name value}` → file text, names sorted so a diff is readable.

   Values are written verbatim, wrapped in double quotes only when they contain
   whitespace or a `#` — the two cases the parser would otherwise mis-read."
  [vars]
  (str/join "\n"
            (for [[k v] (sort-by key vars)]
              (str k "=" (if (re-find #"[\s#]" (str v))
                           (str "\"" v "\"")
                           v)))))

(defn- write-vars!
  "Replace `path`'s contents with `vars`, mode 0600, gitignore ensured."
  [^String path vars]
  (let [f (io/file path)]
    (io/make-parents f)
    (spit f (str (render vars) "\n"))
    ;; 0600 before anything else can read it. setReadable/setWritable with
    ;; owner-only=true is the portable form; the explicit false-for-all first
    ;; is what actually strips group/other.
    (doto f
      (.setReadable false false) (.setWritable false false)
      (.setReadable true true)   (.setWritable true true))
    ;; The .brainyard root is two or three levels up depending on scope; walk to
    ;; the nearest ancestor named .brainyard rather than reconstructing it.
    (loop [d (.getParentFile f) n 0]
      (when (and d (< n 4))
        (if (= ".brainyard" (.getName d))
          (ensure-gitignore! (.getPath d))
          (recur (.getParentFile d) (inc n)))))
    (invalidate-cache!)
    path))

(defn read-file
  "`{name value}` at `path`, or `{}`."
  [^String path]
  (or (some-> path io/file util/parse-env-file) {}))

(defn set-var!
  "Set `name` to `value` in the scope's file. Returns
   `{:path :name :created? :replaced?}`."
  [sel ^String var-name ^String value]
  (when-let [path (scope-path sel)]
    (let [existed? (.isFile (io/file path))
          current  (read-file path)]
      (write-vars! path (assoc current var-name value))
      (mulog/log ::env-set :path path :name var-name)   ; NAME only, never value
      {:path path :name var-name
       :created? (not existed?) :replaced? (contains? current var-name)})))

(defn unset-var!
  "Remove `name` from the scope's file. Returns `{:path :name :removed?}`."
  [sel ^String var-name]
  (when-let [path (scope-path sel)]
    (let [current (read-file path)]
      (if-not (contains? current var-name)
        {:path path :name var-name :removed? false}
        (do (write-vars! path (dissoc current var-name))
            (mulog/log ::env-unset :path path :name var-name)
            {:path path :name var-name :removed? true})))))

(defn import-file!
  "Merge the env file at `src` into the scope's file.

   Without `:overwrite?` an existing name is KEPT and reported as skipped — the
   destructive reading of \"import\" is the one a user regrets, so it has to be
   asked for. Returns `{:path :added :skipped :overwritten}` (vectors of names)."
  [sel ^String src {:keys [overwrite?]}]
  (let [f (io/file src)]
    (cond
      (not (.isFile f)) {:error (str "No such file: " src)}
      :else
      (when-let [path (scope-path sel)]
        (let [incoming (or (util/parse-env-file f) {})
              current  (read-file path)
              collide  (filter #(contains? current %) (keys incoming))
              fresh    (remove #(contains? current %) (keys incoming))
              merged   (if overwrite?
                         (merge current incoming)
                         (merge incoming current))]
          (write-vars! path merged)
          (mulog/log ::env-import :path path :src src
                     :added (vec fresh) :collisions (vec collide))
          {:path path
           :added       (vec (sort fresh))
           :skipped     (if overwrite? [] (vec (sort collide)))
           :overwritten (if overwrite? (vec (sort collide)) [])})))))
