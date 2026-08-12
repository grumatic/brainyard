;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent-tui-app.main
  "GraalVM native-image entry point for the 'by' binary.
   Uses cli-matic for subcommand routing: run, ask, agents.

   Built-in defagent namespaces are registered transitively via
   `ai.brainyard.agent.interface` (which side-effecting-:require's every
   agent ns). No explicit per-agent require list is needed here — adding a
   new agent only requires updating agent.interface."
  (:gen-class)
  (:require
   [ai.brainyard.agent-tui.core :as tui]
   [ai.brainyard.agent-tui.session-summary :as ssum]
   [ai.brainyard.agent-tui.helpers :as helpers]
   [ai.brainyard.agent-tui.mode :as mode]
   [ai.brainyard.agent-tui.log :as tui-log]
   [ai.brainyard.agent-tui.config-wizard :as config-wizard]
   [ai.brainyard.agent-tui-app.dotenv :as dotenv]
   [ai.brainyard.agent.interface :as agent]
   [ai.brainyard.memory.interface :as mem]
   [ai.brainyard.agent-tui-persist.interface :as persist]
   [ai.brainyard.ask-channel.interface :as ask-channel]
   [ai.brainyard.web-share.interface :as web-share]
   [ai.brainyard.os-sandbox.interface :as os-sandbox]
   [ai.brainyard.clj-llm.interface :as clj-llm]
   ;; Force-include cognitect.aws + aws-client for the GraalVM native-image
   ;; static analyzer. clj-llm's bedrock.clj uses requiring-resolve to keep
   ;; AWS optional for non-Bedrock builds, but native-image then strips the
   ;; classes — the runtime resolve fails with "Could not locate". Importing
   ;; here (the project that ships `by`) guarantees inclusion without
   ;; forcing static deps on other clj-llm consumers.
   ;;
   ;; cognitect aws-api also dynaloads HTTP backend + per-protocol impl at
   ;; first use (cognitect.aws.dynaload/load-ns). Pre-require those too —
   ;; bedrock-runtime is rest-json. java HTTP client is selected on JDK 11+.
   [cognitect.aws.client.api]
   [cognitect.aws.http.default]
   [cognitect.aws.http.java]
   [cognitect.aws.protocols.rest-json]
   [ai.brainyard.aws-client.interface]
   [ai.brainyard.mulog.interface :as mulog]
   [cli-matic.core :as cli]
   [clojure.data.json :as json]
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.string :as str]))

;; ============================================================================
;; Shared option definitions
;; ============================================================================

(def build-info
  "Full git provenance for this build, baked in at build time by
   `bb version:ata` (see bb.edn), which writes `resources/build-version.edn`
   before `compile:ata` runs — so AOT compile + native-image carry it into
   the shipped artifact and any binary can be traced back to a commit.

   Keys: `:version` `:sha` `:sha-short` `:branch` `:commit-time` `:dirty?`
   `:built-at`.  Falls back to `{:version \"dev\"}` when the resource is
   missing — e.g. running from a fresh REPL before any build, or from a
   clean source tarball with no `.git/`."
  (or (some-> (io/resource "build-version.edn")
              slurp
              edn/read-string)
      {:version "dev"}))

(def app-version
  "Single source of truth for the build's version string.  Surfaced both in
   the cli-matic config (`:version`) and in the daemon's status snapshot
   (chrome row of the status pane).

   Value is baked at build time from `git describe --tags --always --dirty`;
   see `build-info` for the full provenance map (SHA, branch, build time)."
  (or (:version build-info) "dev"))

(def app-version-long
  "`app-version` plus the git SHA build stamp, e.g.
   `\"v0.5.2 (f6d60fc, main, 2026-07-31T18:07:02+09:00)\"`.  Printed by
   `by --version` so a shipped uberjar/native binary always names the exact
   commit it was built from.  Degrades to plain `app-version` when the build
   had no git metadata."
  (let [{:keys [sha-short branch commit-time dirty?]} build-info
        known? (fn [s] (and (string? s) (seq s) (not= "unknown" s)))
        parts  (cond-> []
                 (known? sha-short)   (conj (str sha-short (when dirty? "-dirty")))
                 (known? branch)      (conj branch)
                 (known? commit-time) (conj commit-time))]
    (if (seq parts)
      (str app-version " (" (str/join ", " parts) ")")
      app-version)))

(defn- app-log-path
  "Mulog file-publisher path. Delegates to tui-log/default-log-path
   (`~/.brainyard/logs/agent-tui-app.log` by default; `/tmp/` fallback).
   Resolved per-call so unit-test rebindings of user.home are honored."
  []
  (tui-log/default-log-path))

(defn- setup-app-log!
  "Start the mulog file publisher and register the path with the agent
   runtime's turn-log query system.  Idempotent — calling twice is a
   no-op (legacy `tui-log` uses a defonce-protected handle).  Failures
   are swallowed because logging must never break a boot."
  []
  (let [path (app-log-path)]
    (try (tui-log/start-file-publisher! path) (catch Exception _))
    (try (agent/set-app-log-path! path) (catch Exception _))))

(defn- teardown-app-log!
  "Drain and stop the mulog file publisher.  Paired with `setup-app-log!`.

   Drains via `flush-file-publisher!`. It used to merely stop the
   publisher, which cancels the publish task without writing what is still
   buffered, and so discarded the tail of every one-shot command —
   including the detached `by memory reduce` child, whose only durable
   audit trail is this log."
  []
  (try (tui-log/flush-file-publisher!) (catch Exception _)))

(defonce ^:private !log-flush-hook
  ;; Guards one-time shutdown-hook registration. `defonce` + CAS rather than
  ;; a bare `when-not`, because -dispatch can be re-entered in a REPL and
  ;; the JVM throws on registering the same hook twice.
  (atom nil))

(defn- install-log-flush-hook!
  "Flush the app log on process exit, for every command path.

   The file publisher is ASYNC — it batches on a 500 ms timer — so events
   emitted by a short one-shot command are still sitting in the ring buffer
   when the process ends, and are lost unless the publisher is STOPPED
   (`stop-publisher!` drains before releasing). That is what the
   `teardown-app-log!` calls in `cmd-ask` and `with-memory-manager` are
   really for: not tidiness, a flush.

   Those two paths keep their own explicit teardown. This hook is what
   makes the log real for everything else — `by agents`, `by sessions
   list`, `by projects`, and `by a2a serve`, which runs until Ctrl-C and
   so can only ever flush from a signal.

   Idempotent with the explicit teardowns: stopping twice is a no-op."
  []
  (when (compare-and-set! !log-flush-hook nil ::installed)
    (try
      (.addShutdownHook (Runtime/getRuntime)
                        (Thread. ^Runnable (fn [] (teardown-app-log!))
                                 "by-log-flush"))
      (catch Exception _))))

(def agent-opt
  {:option "agent" :short "a" :as "Agent ID" :type :string
   :default "coact-agent"})

(def provider-opt
  {:option "provider" :short "p" :as "LM provider (claude-code, anthropic, openai, ollama)"
   :type :string :default "claude-code"})

(def model-opt
  {:option "model" :short "m" :as "Model name override" :type :string})

(def max-iter-opt
  {:option "max-iterations" :short "n" :as "Max agent iterations" :type :int})

(def user-id-opt
  {:option "user-id" :short "u"
   :as "User identity for sessions/memory (default: $BY_USER_ID, else OS login name)"
   :type :string})

(def working-dir-opt
  {:option "working-dir" :short "C"
   :as "Effective working directory for tools/agents (default: $BY_WORKING_DIR, else process cwd)"
   :type :string})

(def config-opt
  {:option "config"
   :as (str "Agent config overrides for this session, as an EDN map — e.g. "
            "'{:acp-backend-opts {:model \"claude-opus-5\" :env {:ANTHROPIC_MODEL \"claude-opus-5\"}}}'. "
            "Keys must be config-schema keys. Seeded as per-agent overrides, and they win over the "
            "equivalent flags.")
   :type :string})

(defn- parse-config-overrides
  "Read `--config` into a validated map of config-schema keys, or nil when the
   flag was absent.

   Refuses rather than warns. A typo'd key here is silent by nature — the
   session starts, the knob keeps its default, and the only symptom is behaviour
   that looks like the flag was ignored, which is exactly what happened. The
   read-only check is the same argument: those keys are reported by the runtime,
   so accepting one would mean promising something the session cannot deliver.

   Returns [overrides nil] or [nil error-message]."
  [s]
  (if (str/blank? (str s))
    [nil nil]
    (let [v (try (edn/read-string s)
                 (catch Exception e
                   {::unreadable (.getMessage e)}))]
      (cond
        (::unreadable v)
        [nil (str "--config is not readable EDN: " (::unreadable v))]

        (not (map? v))
        [nil (str "--config must be an EDN map, got " (some-> v class .getSimpleName))]

        :else
        (let [ks       (set (keys v))
              non-kw   (remove keyword? ks)
              unknown  (remove agent/config-keys (filter keyword? ks))
              readonly (filter agent/read-only-key? (filter keyword? ks))]
          (cond
            (seq non-kw)
            [nil (str "--config keys must be keywords; got " (str/join ", " (map pr-str non-kw)))]

            (seq unknown)
            [nil (str "--config names unknown config keys: " (str/join ", " (map pr-str unknown))
                      ". See `by config` / the config schema for valid keys.")]

            (seq readonly)
            [nil (str "--config names read-only config keys: " (str/join ", " (map pr-str readonly)))]

            :else [v nil]))))))

(def json-opt
  {:option "json" :as "Output machine-readable JSON instead of a table"
   :type :with-flag :default false})

(defn- emit-err!
  "Print an error/usage line to stderr (so it never pollutes --json stdout)."
  [msg]
  (binding [*out* *err*] (println msg)))

(defn- confirm!
  "Read a y/N confirmation from the console. False (decline) when there is no
   interactive console."
  [prompt]
  (if (some? (System/console))
    (do (print prompt) (flush)
        (boolean (#{"y" "Y" "yes"} (some-> (try (read-line) (catch Throwable _ nil))
                                           str str/trim))))
    false))

(defn- print-json!
  "Serialize `data` to a single line of JSON on stdout. Keyword keys/values
   become strings; any value data.json can't encode is stringified via str so a
   --json call never throws mid-stream."
  [data]
  (println (json/write-str data
                           :key-fn (fn [k] (if (keyword? k) (name k) (str k)))
                           :value-fn (fn [_k v]
                                       (cond
                                         (keyword? v) (name v)
                                         (or (nil? v) (number? v) (boolean? v)
                                             (string? v) (map? v) (sequential? v)) v
                                         :else (str v))))))

(def attach-opt
  {:option "attach" :short "A"
   :as "Ask a RUNNING session over its side channel (session-id; see `by sessions list`)"
   :type :string})

(def ask-timeout-opt
  {:option "timeout" :short "t"
   :as "Seconds to wait for an --attach answer (default 120)"
   :type :int})

(def session-opt
  {:option "session" :short "s"
   :as "Pin the session-id (default: a fresh ephemeral ask-<millis>). Reuse the same id across one-shot asks to share session-scoped (L1/L2) memory recall between them."
   :type :string})

(def reducer-opt
  {:option "reducer" :short "r"
   :as "L2→L3 reducer: heuristic (default; per-(role,window) digest) | community (graph-community summaries; needs BY_ENABLE_GRAPH_MEMORY + an extract model)"
   :type :string :default "heuristic"})

;; Context-graph size knobs for `memory reduce` / `memory graph-build`. Each
;; defaults to its `:graph-max-*` config value when the flag is omitted
;; (resolved at run time so env/config.edn overrides still apply).
(def max-nodes-opt
  {:option "max-nodes"
   :as "Total node budget for the context graph (default: :graph-max-nodes). Over budget, lowest-retention nodes are evicted. 0 disables."
   :type :int})

(def max-edges-opt
  {:option "max-edges"
   :as "Total edge budget for the context graph (default: :graph-max-edges). Over budget, lowest-confidence/stalest edges are evicted. 0 disables."
   :type :int})

(def max-entities-per-episode-opt
  {:option "max-entities-per-episode"
   :as "Max entities (nodes) a single episode may add during extraction (default: :graph-max-entities-per-episode)"
   :type :int})

(def max-relations-per-episode-opt
  {:option "max-relations-per-episode"
   :as "Max relations (edges) a single episode may add during extraction (default: :graph-max-relations-per-episode)"
   :type :int})

(def rebuild-opt
  ;; graph-build / reduce are INCREMENTAL by default (a persisted per-scope
  ;; watermark means only episodes newer than the last run are extracted). This
  ;; forces a full re-extraction — use it after changing the extract model/prompt.
  {:option "rebuild"
   :as "Re-extract ALL episodes, ignoring the incremental watermark (use after changing the extract model/prompt). Default: incremental — only episodes newer than the last run."
   :type :with-flag :default false})

;; Read-verb knobs for `memory list/get/search/explain/graph` (Phase 1
;; management CLI). These inspect the user-scoped L1/L2/L3/graph store without
;; a live session — the CLI counterpart to poking the sqlite file by hand.
(def layer-opt
  {:option "layer" :short "l"
   :as "Memory layer to read: l1 (session working-set) | l2 (episodes) | l3 (semantic facts)"
   :type :string})

(def kind-opt
  {:option "kind" :short "k"
   :as "Filter rows by :kind (e.g. qa, summary, system-context)"
   :type :string})

(def limit-opt
  {:option "limit" :short "L"
   :as "Max rows to return (default 20)"
   :type :int})

(def include-archived-opt
  {:option "include-archived"
   :as "Include archived entries (excluded from recall by default)"
   :type :with-flag :default false})

(def node-opt
  {:option "node"
   :as "Seed the graph dump on a node name (returns its bounded neighborhood instead of the whole graph)"
   :type :string})

(def turn-opt
  {:option "turn"
   :as "Explain a single (per-agent) turn id; omit to explain every turn in the session"
   :type :int})

;; Write-verb knobs for `memory forget/edit/keep/archive/promote/sweep/prune`
;; (Phase 2 management CLI). Mutations curate the user-scoped store in place.
(def content-opt
  {:option "content" :short "c"
   :as "New body text for `memory edit`"
   :type :string})

(def confidence-opt
  {:option "confidence"
   :as "New confidence (0.0–1.0) for `memory edit` on an L3 fact"
   :type :string})

(def undo-opt
  {:option "undo"
   :as "Reverse the flag: `keep --undo` unpins, `archive --undo` unarchives"
   :type :with-flag :default false})

(def from-opt
  {:option "from"
   :as "Source layer for `memory promote` (l2)"
   :type :string})

(def to-opt
  {:option "to"
   :as "Target layer for `memory promote` (l3)"
   :type :string})

(def retention-days-opt
  {:option "retention-days"
   :as "Tombstone L2 episodes older than N days that are not kept (default 30)"
   :type :int})

(def yes-opt
  {:option "yes" :short "y"
   :as "Skip the interactive confirmation for destructive verbs (forget/sweep/prune)"
   :type :with-flag :default false})

;; ============================================================================
;; Legacy provider:model parsing
;; ============================================================================

(def ^:private legacy-provider-model-re
  "Strict regex for the `<provider>:<model>` legacy positional syntax.
   Examples that match: `claude-code:sonnet`, `openai:gpt-4o`,
   `anthropic:claude-opus-4-7`, `ollama:llama3.2`.

   Deliberately tight — provider and model are each single tokens of
   `[a-z][a-z0-9._-]*`, no whitespace, exactly one colon. This prevents
   free-form question text like `\"hello :beta gamma\"` or
   `\"call update$apply with :base-dir ...\"` from being misclassified
   as a legacy provider:model shorthand and silently stripped from the
   positional arguments."
  #"^[a-z][a-z0-9._-]*:[a-z0-9][a-z0-9._-]*$")

(defn- legacy-provider-model? [s]
  (and (string? s) (re-matches legacy-provider-model-re s)))

(defn- parse-legacy-provider
  "If _arguments contains a 'provider:model' string (from legacy `-- provider:model`
   syntax), split on first colon and merge into opts. Returns updated opts.

   Matches only the strict `<provider>:<model>` shape (see
   `legacy-provider-model-re`); ordinary question text containing `:` is
   left alone."
  [opts]
  (let [args (:_arguments opts)]
    (if-let [pm-str (first (filter legacy-provider-model? args))]
      (let [i (str/index-of pm-str ":")]
        (-> opts
            (assoc :provider (subs pm-str 0 i))
            (assoc :model (subs pm-str (inc i)))
            (update :_arguments (fn [a] (remove #{pm-str} a)))))
      opts)))

;; ============================================================================
;; Resume picker (plain stdin/stdout, runs before alt-screen)
;; ============================================================================

;; Defined further down (web/env helpers) but used by run-tui!'s resume logic.
(declare env-truthy?)

(def ^:private resume-pick-sentinel
  "Placeholder value injected by `-main` when bare `--resume` is given (no id),
   meaning 'open the interactive session picker'.  The leading dashes guarantee
   it can never collide with a real (timestamp/uuid-shaped) session-id."
  "--by-resume-pick--")

(defn- pick-session-interactive!
  "Show a numbered list of persisted sessions (newest first) plus an [N]ew
   option, prompt the user on stdin/stdout for a choice, and return the
   chosen session-id or `nil` (= start fresh).  Anything we can't parse
   counts as 'new', so a stray newline doesn't strand the user.

   This runs BEFORE the renderer enters alt-screen, so the print/read use
   the cooked terminal directly.  No-op when there are no persisted
   sessions (caller filters)."
  [existing-ids]
  (let [rows (->> (ssum/enriched-summaries)
                  (filterv #(contains? existing-ids (:session-id %))))
        n (count rows)]
    (when (pos? n)
      (println)
      (println (str n " persisted session(s) — pick one to resume, or [N] for a new session:"))
      (doseq [line (ssum/format-table rows {:numbered? true})] (println line))
      (println)
      (print (str "Choice [1-" n "] / (N)ew: "))
      (flush)
      (let [line (try (read-line) (catch Throwable _ nil))
            choice (some-> line str/trim)]
        (cond
          (nil? choice)                  nil
          (str/blank? choice)            nil
          (#{"n" "N" "new"} choice)      nil
          :else
          (when-let [i (try (Long/parseLong choice) (catch Throwable _ nil))]
            (when (and (>= i 1) (<= i n))
              (:session-id (nth rows (dec i))))))))))

(defn- latest-session-id
  "The newest persisted session-id (by last-attached-at, then started-at) among
   `existing-ids`, or nil if none. The non-interactive counterpart to the picker:
   used by `--resume-latest` / `BY_RESUME_LATEST` so a relaunched session (e.g. a
   playground workspace whose container was recreated) reattaches to where the
   user left off, falling back to a fresh session when there are none."
  [existing-ids]
  (->> (ssum/enriched-summaries)
       (filter #(contains? existing-ids (:session-id %)))
       first
       :session-id))

;; ============================================================================
;; Subcommand: run — interactive TUI
;; ============================================================================

(defn- sh-single-quote
  "POSIX single-quote `s` so it survives as one literal word inside a `sh -c`
   command line (handles the `/`, `:`, spaces in model strings and paths)."
  [s]
  (str \' (str/replace (str s) "'" "'\\''") \'))

(defn- reduce-self-argv
  "Resolve the argv prefix that re-execs THIS `by` for the detached memory-reduce
   child. Deliberately does NOT honor BY_WEB_SELF: that override answers 'what
   command should ttyd relaunch' (a web-share concern) and is commonly set to a
   stand-in that is not a real `by` — the smoke tests use `BY_WEB_SELF=cat` /
   `BY_WEB_SELF='bb tui'`, and a --web child inherits it from the parent env.
   Re-execing that here would silently break consolidation. So we resolve the
   real binary — native-image current-executable, else `which by` — by reusing
   web-share's resolver with BY_WEB_SELF stripped from the env it sees, with a
   dedicated BY_MEMORY_SELF escape hatch for dev/source testing (parallel to
   BY_WEB_SELF / BY_SANDBOX_SELF). Returns {:ok? bool :argv [...]}."
  []
  (let [override (some-> (System/getenv "BY_MEMORY_SELF") str/trim not-empty)]
    (if override
      {:ok? true :argv (vec (str/split override #"\s+"))}
      (web-share/self-exec-argv (dissoc (into {} (System/getenv)) "BY_WEB_SELF")))))

(defn- spawn-detached-reduce!
  "Re-exec `by memory reduce -u <uid> -s <sid>` as a DETACHED child and return
   the launcher pid, or nil if the `by` argv can't be resolved / the spawn fails.
   The child inherits our full environment (BY_ENABLE_GRAPH_MEMORY, the extract/
   embed model vars, AWS_PROFILE, …) — required for it to do graph work — and its
   combined output is appended to a per-session log under $TMPDIR for post-mortem.

   Detachment matters because the flush fires as the TUI is exiting: a plain
   ProcessBuilder child stays in our process group and on the controlling
   terminal, so the terminal teardown at /quit delivers it SIGHUP and it dies
   mid-run (nohup alone is NOT enough — a real tmux e2e confirmed the nohup
   child was still killed). Two layers make the child survive:

     1. `trap '' HUP INT TERM` FIRST in the wrapping `/bin/sh`. SIG_IGN is
        inherited across exec, so the whole chain ignores those signals through
        the vulnerable window — this closes the RACE where the TUI tears the
        terminal down before the child has finished detaching.
     2. A BRAND-NEW SESSION before the child ever starts the JVM: `setsid` when
        available (Linux), else the `perl -MPOSIX` equivalent (perl ships with
        stock macOS, which has no setsid binary).

   The shell `exec`s down a pure exec chain (sh → setsid/perl → by) with NO `&`,
   so every hop keeps the same pid and `(.pid proc)` — the ProcessBuilder child —
   IS the final `by memory reduce` pid we report to the user. (An earlier `… &` +
   `echo $!` version reported the detacher's pid, which forks/exits and is gone by
   the time the message prints.) ProcessBuilder.start returns immediately, so we
   never block; it owns the child's IO redirection to the per-session log."
  [{:keys [user-id session-id]}]
  (let [self (reduce-self-argv)]                 ;; {:ok? true :argv [...]} | {:ok? false}
    (when (:ok? self)
      (let [uid  (some-> user-id str str/trim not-empty)
            sid  (some-> session-id str str/trim not-empty)
            argv (cond-> (into (vec (:argv self)) ["memory" "reduce"])
                   uid (into ["-u" uid])
                   sid (into ["-s" sid]))
            log  (io/file (or (System/getenv "TMPDIR")
                              (System/getProperty "java.io.tmpdir") "/tmp")
                          (str "by-memory-reduce-" (or sid "session") ".log"))
            cmd  (str/join " " (map sh-single-quote argv))   ; no shell redirection: ProcessBuilder redirects
            ;; Pure exec chain (no &): trap first, then exec setsid/perl → by, so
            ;; the ProcessBuilder child pid == the final `by` pid.
            script (str "trap '' HUP INT TERM; "
                        "if command -v setsid >/dev/null 2>&1; then exec setsid " cmd "; "
                        "elif command -v perl >/dev/null 2>&1; then "
                        "exec perl -MPOSIX -e 'POSIX::setsid() or exit 1; exec @ARGV' " cmd "; "
                        "else exec " cmd "; fi")
            pb   (doto (ProcessBuilder. ^java.util.List ["/bin/sh" "-c" script])
                   (.redirectErrorStream true)
                   (.redirectOutput (java.lang.ProcessBuilder$Redirect/appendTo log)))
            proc (.start pb)]
        (.pid proc)))))

(defn- install-consolidation-offload!
  "Install the detached session-end consolidation launcher into the memory-agent
   hook (idempotent). With it installed, a root session closing in graph mode
   hands its L2→graph→L3 tail to `spawn-detached-reduce!` and returns from close
   immediately, instead of blocking /quit for up to 5 minutes on batch extraction
   + community summaries. Returning nil (unresolved binary / spawn failure) makes
   the hook fall back to the bounded in-process flush."
  []
  (agent/set-offload-fn!
   (fn [ctx]
     (try
       (spawn-detached-reduce! ctx)
       (catch Exception e
         (mulog/warn ::consolidation-offload-spawn-failed :exception e)
         nil)))))

(defn- register-project!
  "Record this project in the user-scope registry
   (`~/.brainyard/projects/<slug>/project.edn`) and stamp `:last-opened-at`.

   Call sites are the two paths that actually open a session — `run-tui!` and
   `cmd-ask` — and always AFTER `install-working-dir!`, so `-C` /
   `BY_PROJECT_DIR` are already in effect and we register the project the
   session will really work in.

   Deliberately NOT hooked into `agent/ensure-config-dirs!`: that runs on
   every `init-dirs!` (including each memory-manager construction), so
   stamping there would be write churn on a hot path. Read-only invocations
   (`--version`, `--help`, `by projects list`) never register.

   Failure is swallowed inside `agent/register-project!` — the registry is
   auxiliary bookkeeping and must never block a session from starting."
  []
  (agent/register-project! (agent/init-dirs!)))

(defn- install-catalog-cache!
  "Point the catalog overlay at `~/.brainyard/catalog` and load whatever is
   already cached.

   `clj-llm` sits below the agent component and cannot resolve `~/.brainyard`
   itself, so the app installs the root — the same shape as
   `agent/sessions-root` -> `persist/set-root!`. Pure local file reads, so it
   is safe on the startup path; failure is swallowed, because a stale or
   unreadable cache must never stop `by` from starting with the baked
   catalog."
  []
  (try
    (let [dirs (agent/init-dirs!)]
      (when-let [root (agent/brainyard-subdir dirs "catalog" :user)]
        (clj-llm/set-catalog-cache-root! root)
        (clj-llm/load-catalog-overlay!)))
    (catch Throwable _ nil)))

(defn- maybe-refresh-catalog!
  "Kick a background refresh when the cache is past its TTL.

   Gated by `:enable-catalog-refresh`, non-blocking, and best-effort: it exists
   so the catalog keeps up with providers between releases, never so that a
   user waits on one."
  []
  (try
    ;; `feature-on?` rather than a raw get-config: it accounts for the family
    ;; master switch and :requires, which a direct key read would bypass.
    (when (agent/feature-on? nil :exec/catalog-refresh)
      (clj-llm/refresh-catalog-async!
       {:ttl-hours (agent/get-config nil :catalog-refresh-ttl-hours)}))
    (catch Throwable _ nil)))

(defn- run-tui!
  "Start the interactive TUI agent session in this process.
   Config precedence: CLI flags > config.edn > hardcoded defaults.
   `opts` is assumed already normalized by `parse-legacy-provider`."
  [opts]
  (register-project!)
  ;; Install the cached model catalog, then let a background refresh top it up
  ;; if it is past its TTL. Load is local file reads; the refresh is on a
  ;; daemon thread and never gates startup — same contract as the project
  ;; registry above, for the same reason: neither may block a session.
  (install-catalog-cache!)
  (maybe-refresh-catalog!)
  ;; Offload the heavy graph-mode session-end consolidation to a detached
  ;; `by memory reduce` child so /quit never blocks on it (this process knows how
  ;; to re-exec the binary; components/agent can't). No-op unless graph memory is
  ;; on — the hook only calls the launcher in graph mode.
  (install-consolidation-offload!)
  (let [;; Load persisted config for fallback defaults
        file-config (agent/read-edn-config (agent/init-dirs!))

        ;; Support bare agent-id as positional arg (e.g. `by coact-agent`).
        ;; Skip args matching the legacy `<provider>:<model>` shape so the
        ;; agent-id slot doesn't pick that up; ordinary text containing `:`
        ;; is no longer an issue here.
        positional-agent (first (remove legacy-provider-model? (:_arguments opts)))
        cli-agent (:agent opts)
        agent-id (keyword (or positional-agent
                              (when-not (= cli-agent "coact-agent")
                                cli-agent)
                              (some-> (get-in file-config [:agent :default-agent]) name)
                              cli-agent))

        ;; Provider: CLI flag > config.edn > "claude-code"
        cli-provider (:provider opts)
        provider (keyword (or (when-not (= cli-provider "claude-code") cli-provider)
                              (some-> (get-in file-config [:llm :default-provider]) name)
                              cli-provider))

        ;; Model: CLI flag > config.edn > nil
        model (or (:model opts)
                  (get-in file-config [:llm :default-model]))

        ;; NOTE: a missing provider key is handled INSIDE the TUI (create-tui-agent!
        ;; step 2 notifies and boots without a default LM so the user can `/model`
        ;; or `by config` and keep going) — deliberately no exit here, unlike the
        ;; one-shot `by ask` path which pre-flights and exits (can't recover
        ;; mid-turn). See helpers/missing-provider-key.

        inline? (:inline opts)
        serve? (:serve opts)
        verbose? (:verbose opts)
        max-iter (:max-iterations opts)

        ;; Resume wiring.  Default (no flags) starts a fresh session.
        ;;   --resume <id>   → resume that session (error+exit if absent)
        ;;   --resume (bare) → interactive picker (fresh if none)
        ;;                     (-main rewrites bare --resume to the sentinel)
        ;; --new is a deprecated no-op (fresh is already the default).
        resume-arg (:resume opts)
        ;; --resume-latest / BY_RESUME_LATEST: non-interactive "resume newest,
        ;; else fresh". Lets a relaunched workspace reattach without a stdin
        ;; picker. An explicit --resume <id> / bare picker still takes precedence.
        resume-latest? (or (:resume-latest opts) (env-truthy? "BY_RESUME_LATEST"))
        ;; -s/--session <id>: start a NEW session with an explicit id (scripting
        ;; / `ask --attach` targeting). Errors on collision — reattaching an
        ;; existing session is --resume's job, not this flag's. Ignored when a
        ;; resume flag is also present (resume is the more specific intent).
        new-id (some-> (:session opts) str/trim not-empty)
        existing (set (persist/list-sessions))
        [session-id resume?]
        (cond
          (= resume-arg resume-pick-sentinel)
          (if-let [picked (pick-session-interactive! existing)]
            [picked true]
            [nil false])

          resume-arg
          (do
            (when-not (contains? existing resume-arg)
              (binding [*out* *err*]
                (println (str "Error: no persisted session named '" resume-arg "'.")))
              (System/exit 1))
            [resume-arg true])

          resume-latest?
          (if-let [latest (latest-session-id existing)]
            [latest true]
            [nil false])

          new-id
          (do
            (when (contains? existing new-id)
              (binding [*out* *err*]
                (println (str "Error: session '" new-id "' already exists; "
                              "use --resume " new-id " to reattach.")))
              (System/exit 1))
            [new-id false])

          :else
          [nil false])

        ;; Refuse to resume a session already owned by another LIVE `by` process.
        ;; Co-ownership silently corrupts the session's snapshots and clobbers its
        ;; ask.sock (last-opener-wins). Read-only PID-checked probe — a stale lock
        ;; from a crashed process does not block. See
        ;; docs/design/session-channel-extensions.md §1.
        _ (when (and resume? session-id
                     (persist/held-by-other-live-process? session-id))
            (binding [*out* *err*]
              (println (str "Error: session '" session-id "' is already open in another "
                            "running `by` process (pid " (persist/owner-pid session-id) ").")))
            (System/exit 1))

        ;; Decide Mode A / B / C before booting the renderer. On Mode C the
        ;; user explicitly asked for tmux integration (`--with-tmux`) and we
        ;; can't deliver it — print guidance and exit 1 so CI scripts get a
        ;; clear failure signal. The default no-`$TMUX` path is :A.
        probe (mode/probe {:with-tmux (:with-tmux opts)})
        _     (when (= :C (:mode probe))
                (binding [*out* *err*] (print (:guidance probe)) (flush))
                (System/exit 1))

        ;; Only thread an explicit --user-id; when unset, leave it nil so the
        ;; downstream default (create-tui-agent! → helpers/resolve-user-id)
        ;; resolves BY_USER_ID / user.name once at session creation.
        user-id (some-> (:user-id opts) str/trim not-empty)

        ;; Refused before the session exists, not after: a bad --config that
        ;; started a session anyway would leave one to clean up, configured the
        ;; way the caller was trying to avoid.
        [config-overrides config-err] (parse-config-overrides (:config opts))
        _ (when config-err
            (emit-err! config-err)
            (System/exit 1))

        base-args (if (= agent-id :acp-agent)
                    (cond-> [:agent-id agent-id
                             :acp-backend provider
                             :mode (:mode probe)]
                      model (into [:acp-backend-opts {:model model}]))
                    (cond-> [:agent-id agent-id
                             :lm-provider provider
                             :mode (:mode probe)]
                      model (into [:lm-model model])))
        run-args (cond-> base-args
                   (seq config-overrides) (into [:config-overrides config-overrides])
                   inline?    (into [:inline true])
                   serve?     (into [:serve true])
                   verbose?   (into [:display-format :verbose])
                   max-iter   (into [:max-iterations max-iter])
                   session-id (into [:session-id session-id])
                   user-id    (into [:user-id user-id])
                   resume?    (into [:resume? true]))]
    (apply tui/run! run-args)))

;; ============================================================================
;; --web — share the TUI over the web via ttyd
;; ============================================================================
;;
;; `by --web` is a thin launcher: it wraps `by run …` in ttyd, which spawns the
;; child inside a real PTY served to browsers. The child therefore behaves like
;; a normal terminal session (raw mode, alt-screen, SIGWINCH all work). The
;; child carries BY_WEB_CHILD=1 so the re-entered process runs the TUI instead
;; of recursing into another ttyd. Auth is always required; bind defaults to
;; localhost. See components/web-share.

(defn- env*
  "Read an env var, falling back to a JVM system property (the dotenv loader
   bridges `.env` keys into properties; see dotenv.clj)."
  [k]
  (or (System/getenv k) (System/getProperty k)))

(defn- env-truthy? [k]
  (contains? #{"1" "true" "yes" "on"} (some-> (env* k) str/trim str/lower-case)))

(defn- env-int [k]
  (try (some-> (env* k) str/trim not-empty Long/parseLong) (catch Exception _ nil)))

(defn- web-tmux?
  "True when Tier 2 (tmux-backed live co-drive) is requested."
  [opts]
  (or (:web-tmux opts) (env-truthy? "BY_WEB_TMUX")))

(defn- web-requested?
  "True when the user asked to share over the web (any --web* flag or env)."
  [opts]
  (or (:web opts) (env-truthy? "BY_WEB") (web-tmux? opts)))

(defn- web-child?
  "True when this process IS the ttyd child (set by the launcher); the
   re-entrancy guard that stops `--web` from spawning ttyd recursively."
  []
  (= "1" (System/getenv "BY_WEB_CHILD")))

(defn- web-child-argv
  "Reconstruct the `<self> run …` command ttyd should run, forwarding the
   user's run flags but NOT any --web* flag (those would recurse — and the
   child also gets BY_WEB_CHILD=1 as a backstop). `self-argv` comes from
   web-share/self-exec-argv (the native binary path, `which by`, or
   BY_WEB_SELF)."
  [opts self-argv]
  (cond-> (-> (vec self-argv)
              (conj "run")
              (into (:_arguments opts)))            ; positional agent-id, if any
    (not= (:agent opts) "coact-agent")    (into ["-a" (:agent opts)])
    (not= (:provider opts) "claude-code") (into ["-p" (:provider opts)])
    (:model opts)          (into ["-m" (:model opts)])
    (:user-id opts)        (into ["-u" (:user-id opts)])
    (:working-dir opts)    (into ["-C" (:working-dir opts)])
    (:session opts)        (into ["-s" (:session opts)])
    (:inline opts)         (conj "-i")
    (:verbose opts)        (conj "-v")
    (:max-iterations opts) (into ["-n" (str (:max-iterations opts))])
    (:with-tmux opts)      (conj "--with-tmux")
    ;; Resume: a concrete id forwards as `-r <id>`. Bare `--resume` (the
    ;; picker sentinel) must NOT be forwarded as a value — its `--` prefix
    ;; would make the child's inject-bare-resume-sentinel double-inject and
    ;; strand a stray positional. Forward a bare `-r` (emitted last) so the
    ;; child re-derives the sentinel and runs the picker in its own PTY.
    (= (:resume opts) resume-pick-sentinel) (conj "-r")
    (and (:resume opts)
         (not= (:resume opts) resume-pick-sentinel))
    (into ["-r" (:resume opts)])
    (:resume-latest opts) (conj "--resume-latest")))

(defn- print-web-banner!
  [{:keys [url session socket]} {:keys [user pass generated?]} bind port writable? tmux?]
  (let [localhost? (contains? #{"127.0.0.1" "localhost" "::1"} bind)]
    (println)
    (println "🌐 Brainyard web session (ttyd)")
    (println (str "   URL:    " url))
    (println (str "   Auth:   " user " / " pass
                  (when generated? "   (auto-generated)")))
    (println (str "   Mode:   " (if writable? "writable" "read-only")
                  " · shared · " (if localhost? "localhost-only" (str "bound to " bind))))
    (when tmux?
      (println (str "   Tmux:   live session " session " — all clients share one live pane")))
    (when (zero? (long (or port 0)))
      (println "   Note:   port 0 = random; see ttyd's \"Listening on port\" line above."))
    (if localhost?
      (do (println "   Remote: localhost only. To reach it from another machine, tunnel:")
          (println (str "             ssh -L " (or port 7681) ":127.0.0.1:" (or port 7681) " <this-host>")))
      (println (str "   ⚠  Bound beyond localhost — anyone who can reach this port with the\n"
                    "      credentials above can drive this agent (it runs code & tools).")))
    (when tmux?
      (println "   Local:  drive from this machine in another terminal:")
      (println (str "             tmux -L " socket " attach -t " session))
      (println "           …or just open the URL above."))
    (println "   Press Ctrl-C to stop sharing.")
    (println)
    (flush)))

(defn- exit-err!
  [msg]
  (binding [*out* *err*] (println msg))
  (System/exit 1))

(defn- resolve-web-config!
  "Resolve the shared ttyd/share config: probe ttyd, resolve how to relaunch
   the TUI, then merge CLI flags > BY_WEB_* env > defaults. Exits 1 with
   guidance when ttyd or the self-exec path can't be resolved.
   Returns a map of serve!/serve-tmux! options plus :cred."
  [opts]
  (let [avail (web-share/available?)]
    (when-not (:ok? avail) (exit-err! (:hint avail)))
    (let [self (web-share/self-exec-argv)]
      (when-not (:ok? self) (exit-err! (str "Error (--web): " (:reason self))))
      (let [cred (web-share/resolve-credential
                  {:user (or (:web-user opts) (env* "BY_WEB_USER"))
                   :pass (or (:web-pass opts) (env* "BY_WEB_PASS"))})]
        {:ttyd-path   (:path avail)
         :bind        (or (not-empty (:web-bind opts)) (not-empty (env* "BY_WEB_BIND")) "127.0.0.1")
         :port        (or (:web-port opts) (env-int "BY_WEB_PORT") 7681)
         :writable?   (not (or (:web-readonly opts) (env-truthy? "BY_WEB_READONLY")))
         :max-clients (or (:web-max-clients opts) (env-int "BY_WEB_MAX_CLIENTS") 0)
         :once?       (or (:web-once opts) (env-truthy? "BY_WEB_ONCE"))
         :credential  (:credential cred)
         :cred        cred
         :child-argv  (web-child-argv opts (:argv self))}))))

(defn- launch-web!
  "Tier 1: spawn ttyd wrapping a fresh `by run …` session and block until it
   exits. All browser clients share that one session."
  [opts]
  (let [{:keys [cred bind port writable?] :as cfg} (resolve-web-config! opts)
        handle (web-share/serve! (dissoc cfg :cred))]
    (.addShutdownHook (Runtime/getRuntime)
                      (Thread. ^Runnable (fn [] ((:stop handle)))))
    (print-web-banner! handle cred bind port writable? false)
    (System/exit (.waitFor ^Process (:proc handle)))))

(defn- launch-web-tmux!
  "Tier 2: run the TUI in a private detached tmux session served by ttyd. The
   launching terminal stays a dashboard showing the connection info (URL +
   credentials) for as long as the share is up — drive locally from another
   terminal with the printed `tmux attach` command, or just open the URL.
   Blocks until Ctrl-C (or the session ends), then tears down ttyd + the
   private tmux server. Not auto-attaching keeps the banner visible, which is
   what you need to copy and share."
  [opts]
  (when-not (web-share/tmux-available?)
    (exit-err! (str "Error (--web-tmux): tmux is not on PATH.\n"
                    "  macOS:  brew install tmux\n"
                    "  Debian: sudo apt-get install tmux")))
  (let [{:keys [cred bind port writable?] :as cfg} (resolve-web-config! opts)
        handle (web-share/serve-tmux! (dissoc cfg :cred))
        ttyd   ^Process (:proc handle)]
    (.addShutdownHook (Runtime/getRuntime)
                      (Thread. ^Runnable (fn [] ((:stop handle)))))
    (print-web-banner! handle cred bind port writable? true)
    ;; Block until either ttyd exits (Ctrl-C) or the tmux session ends — the
    ;; latter is what /quit (from any client) does. ttyd is a server and does
    ;; NOT exit just because the session ended, so we must watch the session
    ;; ourselves and reap ttyd, otherwise the share would hang open.
    (loop []
      (when (and (.isAlive ttyd) ((:alive? handle)))
        (Thread/sleep 500)
        (recur)))
    ((:stop handle))
    (System/exit 0)))

;; ----------------------------------------------------------------------------
;; --sandbox: contain the session in a macOS seatbelt sandbox (write-containment)
;;
;; A thin launcher, parallel to --web: it re-execs `by run …` under
;; `sandbox-exec` with a generated write-containment profile. Because seatbelt
;; mediates only new syscalls and leaves inherited fds untouched, the child runs
;; in the SAME terminal (unlike --web's PTY-over-network). The child carries
;; BY_SANDBOX_CHILD=1 so the re-entered process runs the TUI instead of
;; recursing into another sandbox-exec. macOS-only; see components/os-sandbox.

(defn- sandbox-child?
  "True when this process IS the sandboxed child (the re-entrancy guard)."
  []
  (= "1" (System/getenv "BY_SANDBOX_CHILD")))

(defn- sandbox-requested?
  "True when the user asked to sandbox the session (--sandbox flag or BY_SANDBOX)."
  [opts]
  (or (:sandbox opts) (env-truthy? "BY_SANDBOX")))

(defn- sandbox-child-argv
  "Reconstruct the `<self> run …` command sandbox-exec should run, forwarding
   the user's run flags but NOT any --web*/--sandbox* flag (those would recurse;
   the child also gets BY_SANDBOX_CHILD=1 as a backstop). Mirrors web-child-argv."
  [opts self-argv]
  (cond-> (-> (vec self-argv)
              (conj "run")
              (into (:_arguments opts)))            ; positional agent-id, if any
    (not= (:agent opts) "coact-agent")    (into ["-a" (:agent opts)])
    (not= (:provider opts) "claude-code") (into ["-p" (:provider opts)])
    (:model opts)          (into ["-m" (:model opts)])
    (:user-id opts)        (into ["-u" (:user-id opts)])
    (:working-dir opts)    (into ["-C" (:working-dir opts)])
    (:session opts)        (into ["-s" (:session opts)])
    (:inline opts)         (conj "-i")
    (:verbose opts)        (conj "-v")
    (:max-iterations opts) (into ["-n" (str (:max-iterations opts))])
    (:with-tmux opts)      (conj "--with-tmux")
    ;; See web-child-argv: forward bare `-r` for the picker sentinel, never
    ;; its `--`-prefixed value (which the child would mis-parse as a flag).
    (= (:resume opts) resume-pick-sentinel) (conj "-r")
    (and (:resume opts)
         (not= (:resume opts) resume-pick-sentinel))
    (into ["-r" (:resume opts)])
    (:resume-latest opts) (conj "--resume-latest")))

(defn- resolve-sandbox-config!
  "Resolve the sandbox launch config: probe sandbox-exec + macOS, resolve how to
   relaunch the TUI, then merge CLI flags > BY_SANDBOX_* env > defaults. Exits 1
   with guidance when sandbox-exec or the self-exec path can't be resolved.
   Returns a map of serve! options."
  [opts]
  (let [avail (os-sandbox/available?)]
    (when-not (:ok? avail) (exit-err! (str "Error (--sandbox): " (:reason avail))))
    (let [self (os-sandbox/self-exec-argv (System/getenv) "BY_SANDBOX_SELF")]
      (when-not (:ok? self) (exit-err! (str "Error (--sandbox): " (:reason self))))
      (let [home     (System/getProperty "user.home")
            cwd      (System/getProperty "user.dir")
            tmpdir   (or (env* "TMPDIR") (System/getProperty "java.io.tmpdir"))
            network? (not (or (:sandbox-no-network opts) (env-truthy? "BY_SANDBOX_NO_NETWORK")))
            extra    (os-sandbox/parse-allow-writes
                      (or (:sandbox-allow-write opts) (env* "BY_SANDBOX_ALLOW_WRITE"))
                      cwd home)
            profile-path (or (not-empty (:sandbox-profile opts))
                             (not-empty (env* "BY_SANDBOX_PROFILE")))]
        {:profile-path   profile-path
         :profile-string (when-not profile-path
                           (os-sandbox/build-profile-string
                            {:home home :cwd cwd :project-dir cwd :tmpdir tmpdir
                             :network? network? :extra-writes extra}))
         :params         {:home home :cwd cwd :project-dir cwd :tmpdir tmpdir}
         :network?       network?
         :extra-writes   extra
         :child-argv     (sandbox-child-argv opts (:argv self))}))))

(defn- print-sandbox-banner!
  [{:keys [network? extra-writes params profile-path]}]
  (println)
  (println "🛡  Brainyard sandboxed session (sandbox-exec)")
  (if profile-path
    (println (str "   Profile: " profile-path " (custom)"))
    (println (str "   Writes:  contained to ~/.brainyard, " (:cwd params)
                  ", $TMPDIR, /tmp"
                  (when (seq extra-writes) (str ", +" (count extra-writes) " extra")))))
  (println (str "   Network: " (if network? "allowed (LLM calls work)" "DENIED")))
  (println "   Press Ctrl-C to stop.")
  (println)
  (flush))

(defn- launch-sandbox!
  "Re-exec `by run …` under sandbox-exec and block until it exits, propagating
   the child's exit code."
  [opts]
  (let [cfg    (resolve-sandbox-config! opts)
        handle (os-sandbox/serve! cfg)]
    (.addShutdownHook (Runtime/getRuntime)
                      (Thread. ^Runnable (fn [] ((:stop handle)))))
    (print-sandbox-banner! cfg)
    (System/exit (.waitFor ^Process (:proc handle)))))

(defn- install-working-dir!
  "Install the `--working-dir`/`-C` override before any agent/tool boots.

   The flag is strict: a non-existent / non-directory path exits 1. A nil/blank
   flag clears the override, so resolution falls back to `BY_WORKING_DIR` env
   then `user.dir`. Installing here (the parent) also validates the path early,
   before a --web/--sandbox launcher spawns its child.

   The project-scoped sessions root is NOT pinned here — the agent-tui base
   wires `persist/set-root-resolver!` to `(agent/sessions-root)` at load, and
   that thunk resolves project-dir fresh on every persistence call. So this just
   needs to install the working-dir override (which `sessions-root` reads via
   `resolve-project-dir`) before any persistence happens. Re-exec'd
   --web/--sandbox children re-enter through this path with `-C` forwarded."
  [opts]
  (try (agent/set-working-dir-override! (:working-dir opts))
       (catch clojure.lang.ExceptionInfo e
         (exit-err! (str "Error: " (.getMessage e))))))

(defn cmd-run
  "Dispatch the `run` subcommand. When already the guarded child of either
   launcher, run the TUI. Otherwise: --sandbox contains the session in a macOS
   seatbelt sandbox (mutually exclusive with --web in v1); --web/--web-tmux
   share it over the web; else run locally."
  [opts]
  (let [opts (parse-legacy-provider opts)]
    (install-working-dir! opts)
    (cond
      ;; Guarded child of EITHER launcher → just run the TUI.
      (or (web-child?) (sandbox-child?)) (run-tui! opts)

      ;; v1: --web and --sandbox don't compose. (Future: sandbox the ttyd child
      ;; by propagating BY_SANDBOX_CHILD through serve!.)
      (and (sandbox-requested? opts) (web-requested? opts))
      (exit-err! (str "Error: --web and --sandbox can't be combined yet.\n"
                      "  Run one or the other. (Sandboxing the web child is planned.)"))

      (sandbox-requested? opts)
      (if (os-sandbox/macos?)
        (launch-sandbox! opts)
        (do (binding [*out* *err*]
              (println "⚠  --sandbox is macOS-only (sandbox-exec); running unsandboxed."))
            (run-tui! opts)))

      (web-tmux? opts)      (launch-web-tmux! opts)
      (web-requested? opts) (launch-web! opts)
      :else                 (run-tui! opts))))

;; ============================================================================
;; Subcommand: ask — one-shot non-interactive question
;; ============================================================================

(defn cmd-ask-attach
  "Ask a question of an already-running session over its side ask channel.
   Resolves <project>/.brainyard/sessions/<session-id>/ask.sock, sends the
   question, prints the answer. Bypasses all agent setup — the live TUI runs
   the turn. See docs/design/ask-attach-channel.md."
  [opts session-id]
  (install-working-dir! opts)            ;; so the sessions root resolves to this project
  (let [json?    (:json opts)
        question (first (:_arguments opts))]
    (when (or (nil? question) (str/blank? question))
      (if json?
        (print-json! {:success false :error "question argument is required" :session-id session-id})
        (do (println "Error: question argument is required.")
            (println "Usage: by ask --attach <session-id> [options] QUESTION")))
      (System/exit 1))
    (let [^java.io.File sock (persist/file-of session-id :ask-sock)]
      (when-not (and sock (.exists sock))
        (if json?
          (print-json! {:success false :session-id session-id
                        :error (str "session '" session-id "' is not attachable (no live ask socket)")})
          (do (println (str "Error: session '" session-id "' is not attachable "
                            "(no live ask socket)."))
              (println "  It must be open in a running `by run` TUI in this project.")
              (println "  List sessions with: by sessions list")))
        (System/exit 1))
      ;; --attach delegates to the LIVE session's agent, so the LM-selection
      ;; flags don't apply — the session answers with its OWN provider/model/
      ;; agent. Warn (on stderr, keeping stdout a clean answer) rather than
      ;; silently ignoring them. Provider/agent defaults ("claude-code" /
      ;; "coact-agent") aren't flagged; model/max-iterations have no default,
      ;; so any presence is an explicit pass.
      (let [ignored (cond-> []
                      (some? (:model opts))                 (conj "-m/--model")
                      (some? (:max-iterations opts))        (conj "-n/--max-iterations")
                      (not= "claude-code" (:provider opts)) (conj "-p/--provider")
                      (not= "coact-agent" (:agent opts))    (conj "-a/--agent"))]
        (when (seq ignored)
          (binding [*out* *err*]
            (println (str "⚠  --attach uses the running session's own provider/model/agent; "
                          (str/join ", " ignored)
                          " ignored.")))))
      (let [timeout-ms (* 1000 (or (:timeout opts) 120))
            resp (try
                   (ask-channel/ask-via-socket!
                    {:path (.getAbsolutePath sock)
                     :question question
                     :timeout-ms timeout-ms})
                   (catch Exception e
                     {:status :error
                      :error (str "could not reach session: " (.getMessage e)
                                  " (is it still running?)")}))]
        (let [ok? (= :ok (:status resp))]
          (if json?
            (print-json! (if ok?
                           (cond-> {:success true
                                    :answer (or (:answer resp) "")
                                    :provider (:provider resp)
                                    :model (:model resp)
                                    :agent (:agent resp)
                                    :session-id session-id}
                             (:usage resp) (assoc :usage (:usage resp)))
                           {:success false :error (:error resp) :session-id session-id}))
            (if ok?
              (println (or (:answer resp) ""))
              (println (str "Error: " (:error resp)))))
          (System/exit (if ok? 0 1)))))))

(defn cmd-ask
  "Ask a one-shot question and print the answer.
   With --attach <session-id>, ask a running session over its side channel
   instead of spinning up a throwaway agent.
   Config precedence: CLI flags > config.edn > hardcoded defaults."
  [opts]
  (when-let [sid (:attach opts)]
    (cmd-ask-attach opts sid))           ;; exits the process; never returns
  (let [json?    (:json opts)
        real-out *out*
        opts (parse-legacy-provider opts)
        _ (install-working-dir! opts)
        _ (register-project!)
        file-config (agent/read-edn-config (agent/init-dirs!))
        question (first (:_arguments opts))
        cli-agent (:agent opts)
        agent-id (keyword (or (when-not (= cli-agent "coact-agent") cli-agent)
                              (some-> (get-in file-config [:agent :default-agent]) name)
                              cli-agent))
        cli-provider (:provider opts)
        provider (keyword (or (when-not (= cli-provider "claude-code") cli-provider)
                              (some-> (get-in file-config [:llm :default-provider]) name)
                              cli-provider))
        model (or (:model opts) (get-in file-config [:llm :default-model]))
        max-iter (:max-iterations opts)
        ;; An acp-agent has no LM of its own — the external ACP backend owns
        ;; the loop — so for it `-p` names a BACKEND (:stub, :claude-code,
        ;; :gemini, :codex) and `-m` a backend model. `run` has always routed
        ;; them that way (see the :acp-agent branch of base-args); `ask` did
        ;; not, and got both halves wrong at once:
        ;;
        ;;   * `-p` never reached the backend. It was passed to setup-lm! as
        ;;     an LM provider and otherwise dropped, so `ask -p stub` silently
        ;;     ran whatever :acp-backend config.edn happened to hold.
        ;;   * setup-lm! then defaulted the model off an LM table that knows
        ;;     nothing of ACP backends, leaving it nil for every backend but
        ;;     :claude-code — and create-lm NPEs on a nil model. So
        ;;     `ask -a acp-agent -p stub` crashed, while `-p claude-code`
        ;;     survived purely because it collides with an LM provider name.
        ;;
        ;; Explicit-`-p` detection reuses the sentinel convention already used
        ;; for agent-id and provider above: provider-opt carries :default
        ;; "claude-code", so a bare `-p claude-code` is indistinguishable from
        ;; passing nothing. Treating the sentinel as unset means a configured
        ;; :acp-backend keeps winning when no flag is given, rather than being
        ;; clobbered by a CLI default the user never typed.
        acp?     (= agent-id :acp-agent)
        acp-backend (when-not (= cli-provider "claude-code") cli-provider)
        ;; Raw :model, NOT `model` above — that one folds in the LLM
        ;; :default-model, which is not a backend model.
        acp-model   (:model opts)]
    (when (or (nil? question) (str/blank? question))
      (if json?
        (print-json! {:success false :error "question argument is required"})
        (do (println "Error: question argument is required.")
            (println "Usage: by ask [options] QUESTION")))
      (System/exit 1))

    ;; Pre-flight the provider credential. In --json mode a setup failure is
    ;; surfaced as JSON below (the catch around `run`); in plain mode, notify
    ;; with actionable guidance and exit cleanly rather than letting setup-lm!
    ;; throw a raw stack trace up to cli-matic.
    ;; Skipped for an acp-agent: `provider` is a backend name there, so asking
    ;; whether it has an LM API key is a category error (and would refuse
    ;; `-p gemini` for want of a GEMINI key it never uses).
    (when (and (not json?) (not acp?) (helpers/missing-provider-key provider))
      (binding [*out* *err*]
        (println (str "⚠  " (helpers/no-provider-message provider))))
      (System/exit 1))

    ;; Run setup + ask, collecting the result and the LM actually used. When
    ;; --json, run under *out*→*err* so incidental console output ("LM
    ;; configured", any agent emit!) lands on stderr and stdout stays pure JSON.
    ;; (In one-shot mode emit!/write-output! resolve to *out*, so this captures
    ;; them; JUL/SQLite warnings already go to System.err.)
    (let [;; Emit the answer EXACTLY ONCE, and — on the normal path — before the
          ;; agent is closed (see `run` below). Session close fires the
          ;; memory-agent's `:agent.instance/closed` flush, which in graph mode
          ;; can take minutes; printing after it left stdout empty that whole
          ;; time even though the answer was ready in seconds.
          ;; The atom guards the one double-print window: `run`'s `finally`
          ;; (teardown / close) throwing AFTER a successful emit, which would
          ;; otherwise fall into the --json catch below and print again.
          !emitted (atom false)
          emit-result!
          (fn [{:keys [result sess-id] rprov :provider rmodel :model}]
            (when (compare-and-set! !emitted false true)
              (let [err    (:error result)
                    answer (or (:answer result) (:result result))]
                (if json?
                  ;; stdout stays pure JSON even though `run` executes under
                  ;; *out*→*err* in --json mode.
                  (binding [*out* real-out]
                    (print-json! (cond-> {:success (not (boolean err))
                                          :answer (when-not err (or answer ""))
                                          :provider rprov
                                          :model rmodel
                                          :agent (name agent-id)
                                          :session-id sess-id}
                                   (:usage result) (assoc :usage (:usage result))
                                   err             (assoc :error err))))
                  (binding [*out* real-out]
                    (if err
                      (println (str "Error: " err))
                      (println (or answer ""))))))))
          run (fn []
                (helpers/suppress-jul-cookie-warnings!)
                ;; No LM for an acp-agent — the backend owns the loop, and
                ;; setting one up is what produced the nil-model NPE.
                (when-not acp?
                  (helpers/setup-lm! provider :model model))
                ;; The SLF4J bridge and the app log are both installed by
                ;; `-dispatch` now, for every command. `setup-app-log!` is
                ;; kept because this path also pairs it with an explicit
                ;; teardown below; it is an idempotent no-op here.
                (setup-app-log!)
                ;; Hand the graph-mode session-end consolidation to a detached
                ;; `by memory reduce` child, exactly as the TUI does. Without
                ;; this the `:agent.instance/closed` flush has no launcher and
                ;; falls back to the bounded IN-PROCESS path, which in graph
                ;; mode blocks `by ask` for the full
                ;; `session-end-flush-timeout-graph-ms` (5 min) and then
                ;; discards the work.
                (install-consolidation-offload!)
                (let [named-session (some-> (:session opts) str/trim not-empty)
                      ;; `--session` presence IS the durability signal: naming a
                      ;; session says "this one matters". An auto-generated
                      ;; `ask-<millis>` is a throwaway (smoke test, script, CI).
                      explicit-session? (some? named-session)
                      sess-id (or named-session
                                  (str "ask-" (System/currentTimeMillis)))
                      max-iterations (or max-iter
                                         (:max-iterations (:meta (agent/get-tool-defs :id agent-id)))
                                         (get agent/default-config :max-iterations 20))
                      ag (apply
                          agent/setup-agent-by-id agent-id
                          (concat
                           [:agent-session {:user-id (helpers/resolve-user-id (:user-id opts))
                                            :session-id sess-id}
                            :max-iterations max-iterations
                            ;; The one-shot `ask-<millis>` session is ephemeral — don't
                            ;; leave a `.brainyard/sessions/ask-*/trajectory.edn` behind.
                            ;; A top-level schema key flows into the per-agent override
                            ;; (st-memory-init :config), NOT .brainyard/config.edn, so it
                            ;; scopes to this run only and never affects TUI sessions.
                            :enable-trajectory-recording false]
                           ;; An UNNAMED ask is a throwaway: its Q&A ("what is 2+2?")
                           ;; is noise in a user-scoped, long-lived recall corpus. Turn
                           ;; capture off, which also prunes graph extraction and the
                           ;; session-end consolidation (both :require it) — so no
                           ;; detached `by memory reduce` child spawns either.
                           ;; RECALL deliberately survives: the store is user-scoped, so
                           ;; the turn still answers with the benefit of prior memory
                           ;; (see :memory/recall in core.feature — it intentionally has
                           ;; no :requires on capture).
                           ;; Naming a session (`ask -s foo`) is the opt-in to full
                           ;; memory behaviour; only set the key in the throwaway case,
                           ;; never force it true, or a user's own
                           ;; `:enable-memory-capture false` would be overridden.
                           (when-not explicit-session?
                             [:enable-memory-capture false])
                           ;; Route -p/-m to the ACP backend, mirroring `run`.
                           ;; Only when actually supplied, so a configured
                           ;; :acp-backend / :acp-backend-opts still applies
                           ;; when the flag is absent.
                           (when acp?
                             (cond-> []
                               acp-backend (into [:acp-backend (keyword acp-backend)])
                               acp-model   (into [:acp-backend-opts {:model acp-model}])))))]
                  (try
                    (let [result (agent/ask ag question)
                          lm     (clj-llm/get-default-lm)
                          out    {:result result :sess-id sess-id
                                  :provider (some-> (:provider lm) name) :model (:model lm)}]
                      ;; Print here, INSIDE the try — the `finally` below closes
                      ;; the agent, and that close can block on the session-end
                      ;; memory flush.
                      (emit-result! out)
                      out)
                    (catch Exception e
                      (let [out {:result {:error (.getMessage e)} :sess-id sess-id
                                 :provider (name provider) :model model}]
                        (emit-result! out)
                        out))
                    (finally
                      (teardown-app-log!)
                      (.close ^java.io.Closeable ag)))))
          {:keys [result]}
          (if json?
            ;; In --json mode a setup failure (e.g. missing API key, thrown by
            ;; setup-lm! before the inner try) must still surface as JSON, not a
            ;; raw stack trace. Non-json mode keeps the original behavior (let it
            ;; propagate to cli-matic's error handler).
            (binding [*out* *err*]
              (try (run)
                   (catch Exception e
                     ;; `run` never got far enough to emit (or its finally threw
                     ;; after emitting — the atom makes that a no-op).
                     (let [out {:result {:error (.getMessage e)}
                                :provider (name provider) :model model}]
                       (emit-result! out)
                       out))))
            (run))]
      ;; The answer is already on stdout — `emit-result!` printed it before the
      ;; agent was closed. Only the exit code is left.
      (System/exit (if (:error result) 1 0)))))

;; ============================================================================
;; Subcommand: memory — maintenance on the user-scoped L1/L2/L3 store
;; ============================================================================
;;
;; Deterministic, non-LLM operations on `~/.brainyard/memory/<user-id>.db`,
;; outside any agent session. `consolidate` is the deterministic trigger for
;; L2→L3 promotion (the agent-driven essence/consolidation paths are async /
;; LLM-mediated and unsuitable for scripting); `stats` reports layer counts.
;; Used by the memory test harnesses (scripts/test-memory-l3-*.sh).

(defn- with-memory-manager
  "Create a user-scoped memory manager (graph opts wired from config when
   `:enable-graph-memory` is on), initialize it, run `f` on it, and always
   shut it down. Returns f's value.

   Starts the mulog file publisher for the duration so the memory pipeline's
   structured events (graph extraction, `::graph-consolidated`,
   `::communities-summarized`, errors) persist to the app log — the detached
   `by memory reduce` child leaves no other durable audit trail (its stdout goes
   to a transient $TMPDIR log). `teardown-app-log!` in the finally flushes the
   async publisher before the one-shot process exits, else the events are lost."
  [user-id f]
  ;; The SLF4J bridge is installed once by `-dispatch`. `setup-app-log!`
  ;; stays because of the paired teardown in the finally below — an
  ;; idempotent no-op when the process already started it.
  (setup-app-log!)
  (let [mm (agent/create-memory-manager user-id)]
    (try
      (mem/initialize mm)
      (f mm)
      (finally
        (mem/shutdown mm)
        (teardown-app-log!)))))

(defn cmd-memory-consolidate
  "Run L2→L3 consolidation for a user (optionally one session). Deterministic
   with the default heuristic reducer; `--reducer community` uses the
   graph-community summarizer (requires the graph tier configured)."
  [opts]
  (helpers/suppress-jul-cookie-warnings!)
  (let [json?   (:json opts)
        uid     (helpers/resolve-user-id (:user-id opts))
        sid     (some-> (:session opts) str/trim not-empty)
        reducer (keyword (or (:reducer opts) "heuristic"))]
    (try
      (let [report (with-memory-manager
                     uid
                     (fn [mm]
                       (apply mem/consolidate-l2! mm
                              (cond-> [:reducer reducer]
                                sid (into [:session-id sid])))))]
        (if json?
          (print-json! {:success true :user-id uid :session sid
                        :reducer (name reducer) :report report})
          (println (format "Consolidated [%s%s] reducer=%s → produced=%s consumed=%s"
                           uid (if sid (str " / " sid) "")
                           (name reducer)
                           (:produced report) (:consumed report)))))
      (catch Exception e
        (if json?
          (print-json! {:success false :error (.getMessage e) :user-id uid})
          (println "Error:" (.getMessage e)))
        (System/exit 1)))))

(defn cmd-memory-stats
  "Report L1/L2/L3 counts for a user's memory store."
  [opts]
  (helpers/suppress-jul-cookie-warnings!)
  (let [json? (:json opts)
        uid   (helpers/resolve-user-id (:user-id opts))]
    (try
      (let [stats (with-memory-manager uid (fn [mm] (mem/get-stats mm)))]
        (if json?
          (print-json! (assoc stats :success true :user-id uid))
          (println (format "Memory[%s]: episodes=%s semantic-facts=%s schema=%s"
                           uid (:episodes stats) (:semantic-facts stats)
                           (:schema-version stats)))))
      (catch Exception e
        (if json?
          (print-json! {:success false :error (.getMessage e) :user-id uid})
          (println "Error:" (.getMessage e)))
        (System/exit 1)))))

(defn- graph-memory-enabled?
  "Ground-truth read of whether the context-graph tier is on for this process —
   the `BY_ENABLE_GRAPH_MEMORY` env var parsed as a boolean. Informational only:
   the dump works regardless (an off/never-populated graph just dumps empty), but
   surfaces let the UI explain an empty graph as \"not enabled\" vs \"no data yet\"."
  []
  (boolean (some-> (System/getenv "BY_ENABLE_GRAPH_MEMORY")
                   str/trim str/lower-case
                   #{"1" "true" "yes" "on"})))

(defn cmd-memory-graph
  "Dump a user's context-graph (nodes + valid edges + counts) as JSON for
   visualisation/export. Unscoped full-graph read over
   `~/.brainyard/memory/<user-id>.db`; degrades to empty collections when the
   graph tier was never populated. `--json` is the only useful format.

   With `--node <name>` the dump is scoped to that node's bounded neighborhood
   (relational recall via `graph-related`) instead of the whole graph — the
   quick 'what's connected to X' probe that replaces a hand-written JOIN."
  [opts]
  (helpers/suppress-jul-cookie-warnings!)
  (let [json?   (:json opts)
        uid     (helpers/resolve-user-id (:user-id opts))
        node    (some-> (:node opts) str/trim not-empty)
        limit   (or (:limit opts) 20)
        enabled (graph-memory-enabled?)]
    (try
      (if node
        ;; Neighborhood probe: relationship entries seeded on the node name.
        (let [rels (with-memory-manager
                     uid (fn [mm] (mem/graph-related mm [node] {:limit limit})))
              out  {:success true :user-id uid :enabled? enabled
                    :node node :relations (vec rels) :count (count rels)}]
          (if json?
            (print-json! out)
            (do (println (format "Graph[%s] neighborhood of \"%s\" (%s relation(s)):"
                                 uid node (count rels)))
                (doseq [r rels] (println "  •" (:content r))))))
        (let [snap (with-memory-manager uid (fn [mm] (mem/graph-snapshot mm)))
              out  (assoc snap :success true :user-id uid :enabled? enabled)]
          (if json?
            (print-json! out)
            (println (format "Graph[%s]: nodes=%s edges=%s enabled=%s"
                             uid (get-in snap [:counts :nodes])
                             (get-in snap [:counts :edges]) enabled)))))
      (catch Exception e
        (if json?
          (print-json! {:success false :error (.getMessage e)
                        :user-id uid :enabled? enabled
                        :nodes [] :edges [] :counts {:nodes 0 :edges 0}})
          (println "Error:" (.getMessage e)))
        (System/exit 1)))))

;; ---------------------------------------------------------------------------
;; Phase 1 read verbs — inspect the user-scoped L1/L2/L3/graph store
;; ---------------------------------------------------------------------------

(defn- parse-layer
  "Coerce a --layer string to :l1/:l2/:l3, or nil when absent/invalid."
  [s]
  (some-> s str/trim str/lower-case #{"l1" "l2" "l3"} keyword))

(defn cmd-memory-list
  "List raw entries from one memory layer (`--layer l1|l2|l3`), the CLI
   counterpart to a `SELECT … LIMIT` against the store. Filters: `--session`
   (L1/L2), `--kind`, `--limit` (default 20), `--include-archived`. `--json`
   emits the row vector verbatim."
  [opts]
  (helpers/suppress-jul-cookie-warnings!)
  (let [json?    (:json opts)
        uid      (helpers/resolve-user-id (:user-id opts))
        layer    (parse-layer (:layer opts))
        sid      (some-> (:session opts) str/trim not-empty)
        kind     (some-> (:kind opts) str/trim not-empty)
        limit    (or (:limit opts) 20)
        archived (boolean (:include-archived opts))]
    (cond
      (nil? layer)
      (do (if json?
            (print-json! {:success false :user-id uid
                          :error "--layer is required (l1|l2|l3)"})
            (emit-err! "Usage: by memory list --layer l1|l2|l3 [--session S] [--kind K] [--limit N]"))
          (System/exit 1))
      :else
      (try
        (let [query (cond-> {}
                      sid  (assoc :session-id sid)
                      kind (assoc :kind (keyword kind)))
              rows  (with-memory-manager
                      uid (fn [mm]
                            (mem/read-entries mm layer query
                                              {:limit limit :include-archived archived})))]
          (if json?
            (print-json! {:success true :user-id uid :layer layer
                          :count (count rows) :entries (vec rows)})
            (do (println (format "Memory[%s] %s — %s row(s):" uid (name layer) (count rows)))
                (doseq [r rows]
                  (println (format "  %-40s %s"
                                   (str (:id r))
                                   (let [c (str (:content r))]
                                     (if (> (count c) 100) (str (subs c 0 99) "…") c))))))))
        (catch Exception e
          (if json?
            (print-json! {:success false :error (.getMessage e) :user-id uid})
            (emit-err! (str "Error: " (.getMessage e))))
          (System/exit 1))))))

(defn cmd-memory-get
  "Fetch a single entry by its stable entry-id from one layer
   (`--layer l1|l2|l3`). Exits 1 when not found."
  [opts]
  (helpers/suppress-jul-cookie-warnings!)
  (let [json? (:json opts)
        uid   (helpers/resolve-user-id (:user-id opts))
        layer (parse-layer (:layer opts))
        id    (some-> (first (:_arguments opts)) str/trim not-empty)]
    (cond
      (nil? layer)
      (do (if json? (print-json! {:success false :error "--layer is required (l1|l2|l3)" :user-id uid})
              (emit-err! "Usage: by memory get <entry-id> --layer l1|l2|l3"))
          (System/exit 1))
      (nil? id)
      (do (if json? (print-json! {:success false :error "entry-id argument is required" :user-id uid})
              (emit-err! "Usage: by memory get <entry-id> --layer l1|l2|l3"))
          (System/exit 1))
      :else
      (try
        (let [row (with-memory-manager
                    uid (fn [mm]
                          (first (mem/read-entries mm layer {:id id}
                                                   {:limit 1 :include-archived true}))))]
          (cond
            (nil? row)
            (do (if json? (print-json! {:success false :user-id uid :layer layer
                                        :id id :error "not found"})
                    (emit-err! (str "Not found: " id " in " (name layer))))
                (System/exit 1))
            :else
            (do (if json?
                  (print-json! {:success true :user-id uid :layer layer :id id :entry row})
                  (println (pr-str row)))
                (System/exit 0))))
        (catch Exception e
          (if json? (print-json! {:success false :error (.getMessage e) :user-id uid})
              (emit-err! (str "Error: " (.getMessage e))))
          (System/exit 1))))))

(defn cmd-memory-search
  "Cross-layer recall for a natural-language query — the SAME weighted-RRF
   pipeline used to assemble a turn's memory briefing (L1/L2/L3 + graph/vec
   signals), so it reproduces what the agent would actually recall. NOT a raw
   SQL LIKE. `--session` restricts L1/L2; `--limit`/`--total-limit` cap results;
   `--match or|and|phrase` sets multi-word FTS mode."
  [opts]
  (helpers/suppress-jul-cookie-warnings!)
  (let [json? (:json opts)
        uid   (helpers/resolve-user-id (:user-id opts))
        query (some-> (str/join " " (:_arguments opts)) str/trim not-empty)
        sid   (some-> (:session opts) str/trim not-empty)
        limit (or (:limit opts) 10)]
    (cond
      (nil? query)
      (do (if json? (print-json! {:success false :error "a query argument is required" :user-id uid})
              (emit-err! "Usage: by memory search \"<query>\" [--session S] [--limit N]"))
          (System/exit 1))
      :else
      (try
        (let [rows (with-memory-manager
                     uid (fn [mm]
                           (apply mem/contextual-recall mm query
                                  (cond-> [:limit limit]
                                    sid (into [:session-id sid])))))]
          (if json?
            (print-json! {:success true :user-id uid :query query
                          :count (count rows) :entries (vec rows)})
            (do (println (format "Recall[%s] \"%s\" — %s result(s):" uid query (count rows)))
                (doseq [r rows]
                  (println (format "  [%s] %s"
                                   (name (or (:_layer r) :?))
                                   (let [c (str (:content r))]
                                     (if (> (count c) 100) (str (subs c 0 99) "…") c))))))))
        (catch Exception e
          (if json? (print-json! {:success false :error (.getMessage e) :user-id uid})
              (emit-err! (str "Error: " (.getMessage e))))
          (System/exit 1))))))

(defn cmd-memory-explain
  "Show which memory entries informed a session's prompt(s) — the recall audit
   trail. `--session` (required) picks the session; `--turn N` narrows to one
   (per-agent) turn, else every turn in the session is explained."
  [opts]
  (helpers/suppress-jul-cookie-warnings!)
  (let [json? (:json opts)
        uid   (helpers/resolve-user-id (:user-id opts))
        sid   (some-> (:session opts) str/trim not-empty)
        turn  (:turn opts)]
    (cond
      (nil? sid)
      (do (if json? (print-json! {:success false :error "--session is required" :user-id uid})
              (emit-err! "Usage: by memory explain --session <session-id> [--turn N]"))
          (System/exit 1))
      :else
      (try
        (let [result (with-memory-manager
                       uid (fn [mm]
                             (if turn
                               (mem/explain mm sid turn)
                               (mem/explain-session mm sid))))]
          (if json?
            (print-json! {:success true :user-id uid :session sid :turn turn :explain result})
            (println (pr-str result)))
          (System/exit 0))
        (catch Exception e
          (if json? (print-json! {:success false :error (.getMessage e) :user-id uid})
              (emit-err! (str "Error: " (.getMessage e))))
          (System/exit 1))))))

(defn cmd-memory-status
  "Health/inventory of the user's memory store: L1/L2/L3 counts plus the graph
   vector-index staleness (embedding-model fingerprint). Superset of `stats`."
  [opts]
  (helpers/suppress-jul-cookie-warnings!)
  (let [json? (:json opts)
        uid   (helpers/resolve-user-id (:user-id opts))]
    (try
      (let [{:keys [stats vec]}
            (with-memory-manager
              uid (fn [mm]
                    {:stats (mem/get-stats mm)
                     :vec   (try (mem/graph-vec-status mm) (catch Throwable _ nil))}))]
        (if json?
          (print-json! {:success true :user-id uid :stats stats :vec-status vec
                        :graph-enabled? (graph-memory-enabled?)})
          (do (println (format "Memory[%s]: episodes=%s semantic-facts=%s schema=%s graph=%s"
                               uid (:episodes stats) (:semantic-facts stats)
                               (:schema-version stats) (graph-memory-enabled?)))
              (when vec
                (println (format "  vec-index: %s (%s vectors%s)"
                                 (if (:stale? vec) "STALE — run reembed" "ok")
                                 (:count vec)
                                 (if (:stale? vec)
                                   (format ", %s → %s" (:was vec) (:now vec)) "")))))))
      (catch Exception e
        (if json? (print-json! {:success false :error (.getMessage e) :user-id uid})
            (emit-err! (str "Error: " (.getMessage e))))
        (System/exit 1)))))

;; ---------------------------------------------------------------------------
;; Phase 2 write verbs — curate/mutate the user-scoped store (audited)
;; ---------------------------------------------------------------------------

(defn- mem-audit!
  "Emit a durable mulog record for every memory mutation issued from the CLI —
   the audit trail that a raw `sqlite3 UPDATE` never leaves. `info` merges into
   the event so counts/ids are queryable in the app log."
  [verb uid info]
  (mulog/log ::memory-mutation :verb verb :user-id uid :source :cli
             :info info))

(defn- require-confirm!
  "Gate a destructive verb: true to proceed. `--json` and `--yes` bypass the
   prompt (scripting); otherwise ask, and refuse when there's no TTY."
  [opts prompt]
  (or (:json opts) (:yes opts) (confirm! prompt)))

(defn cmd-memory-forget
  "Tombstone an L2/L3 entry by id (excluded from recall; row retained for audit).
   Prompts for confirmation unless --yes/--json. Exits 1 when the id is unknown."
  [opts]
  (helpers/suppress-jul-cookie-warnings!)
  (let [json? (:json opts)
        uid   (helpers/resolve-user-id (:user-id opts))
        layer (parse-layer (:layer opts))
        id    (some-> (first (:_arguments opts)) str/trim not-empty)]
    (cond
      (or (nil? layer) (nil? id))
      (do (if json? (print-json! {:success false :error "--layer and <entry-id> are required" :user-id uid})
              (emit-err! "Usage: by memory forget <entry-id> --layer l2|l3 [--yes]"))
          (System/exit 1))
      :else
      (try
        (let [existing (with-memory-manager
                         uid (fn [mm]
                               (first (mem/read-entries mm layer {:id id}
                                                        {:limit 1 :include-archived true}))))]
          (cond
            (nil? existing)
            (do (if json? (print-json! {:success false :user-id uid :layer layer :id id :error "not found"})
                    (emit-err! (str "Not found: " id " in " (name layer))))
                (System/exit 1))
            (not (require-confirm! opts (format "Tombstone %s in %s? [y/N] " id (name layer))))
            (do (emit-err! "Aborted.") (System/exit 1))
            :else
            (let [ok (with-memory-manager uid (fn [mm] (mem/forget-entry mm layer id)))]
              (mem-audit! :forget uid {:layer layer :id id :ok (boolean ok)})
              (if json?
                (print-json! {:success (boolean ok) :user-id uid :layer layer :id id :forgot (boolean ok)})
                (println (if ok (format "Tombstoned %s in %s." id (name layer))
                             (format "No change (%s not found)." id))))
              (System/exit (if ok 0 1)))))
        (catch Exception e
          (if json? (print-json! {:success false :error (.getMessage e) :user-id uid})
              (emit-err! (str "Error: " (.getMessage e))))
          (System/exit 1))))))

(defn cmd-memory-edit
  "Update an existing L2/L3 entry in place (--content, --kind, --confidence for
   L3), preserving its id and re-indexing the L3 embedding on a content change.
   At least one field is required. Exits 1 when the id is unknown."
  [opts]
  (helpers/suppress-jul-cookie-warnings!)
  (let [json?   (:json opts)
        uid     (helpers/resolve-user-id (:user-id opts))
        layer   (parse-layer (:layer opts))
        id      (some-> (first (:_arguments opts)) str/trim not-empty)
        content (:content opts)
        kind    (some-> (:kind opts) str/trim not-empty)
        conf    (some-> (:confidence opts) str/trim not-empty parse-double)
        updates (cond-> {}
                  (some? content) (assoc :content content)
                  kind            (assoc :kind kind)
                  (some? conf)    (assoc :confidence conf))]
    (cond
      (or (nil? layer) (nil? id))
      (do (if json? (print-json! {:success false :error "--layer and <entry-id> are required" :user-id uid})
              (emit-err! "Usage: by memory edit <entry-id> --layer l2|l3 --content … [--kind K] [--confidence F]"))
          (System/exit 1))
      (= :l1 layer)
      (do (if json? (print-json! {:success false :error "edit supports l2/l3 only" :user-id uid})
              (emit-err! "edit supports l2/l3 only (L1 is the session working-set)."))
          (System/exit 1))
      (empty? updates)
      (do (if json? (print-json! {:success false :error "nothing to update (pass --content/--kind/--confidence)" :user-id uid})
              (emit-err! "Nothing to update — pass --content, --kind, and/or --confidence."))
          (System/exit 1))
      :else
      (try
        (let [updated (with-memory-manager uid (fn [mm] (mem/update-entry! mm layer id updates)))]
          (mem-audit! :edit uid {:layer layer :id id :fields (vec (keys updates)) :ok (boolean updated)})
          (cond
            (nil? updated)
            (do (if json? (print-json! {:success false :user-id uid :layer layer :id id :error "not found"})
                    (emit-err! (str "Not found: " id " in " (name layer))))
                (System/exit 1))
            :else
            (do (if json?
                  (print-json! {:success true :user-id uid :layer layer :id id
                                :fields (vec (keys updates)) :entry updated})
                  (println (format "Updated %s in %s (%s)." id (name layer)
                                   (str/join ", " (map name (keys updates))))))
                (System/exit 0))))
        (catch Exception e
          (if json? (print-json! {:success false :error (.getMessage e) :user-id uid})
              (emit-err! (str "Error: " (.getMessage e))))
          (System/exit 1))))))

(defn cmd-memory-keep
  "Pin (or, with --undo, unpin) an L2/L3 entry against the retention sweep."
  [opts]
  (helpers/suppress-jul-cookie-warnings!)
  (let [json? (:json opts)
        uid   (helpers/resolve-user-id (:user-id opts))
        layer (parse-layer (:layer opts))
        id    (some-> (first (:_arguments opts)) str/trim not-empty)
        undo? (boolean (:undo opts))]
    (cond
      (or (nil? layer) (nil? id))
      (do (if json? (print-json! {:success false :error "--layer and <entry-id> are required" :user-id uid})
              (emit-err! "Usage: by memory keep <entry-id> --layer l2|l3 [--undo]"))
          (System/exit 1))
      :else
      (try
        (let [ok (with-memory-manager uid (fn [mm] (if undo? (mem/unkeep! mm layer id)
                                                       (mem/keep! mm layer id))))]
          (mem-audit! (if undo? :unkeep :keep) uid {:layer layer :id id :ok (boolean ok)})
          (if json?
            (print-json! {:success (boolean ok) :user-id uid :layer layer :id id :undo undo?
                          :kept (boolean (and ok (not undo?)))})
            (println (if ok (format "%s %s in %s." (if undo? "Unpinned" "Pinned") id (name layer))
                         (format "No change (%s not found)." id))))
          (System/exit (if ok 0 1)))
        (catch Exception e
          (if json? (print-json! {:success false :error (.getMessage e) :user-id uid})
              (emit-err! (str "Error: " (.getMessage e))))
          (System/exit 1))))))

(defn cmd-memory-archive
  "Archive (or, with --undo, unarchive) an L2/L3 entry — excluded from default
   recall but still retrievable with --include-archived."
  [opts]
  (helpers/suppress-jul-cookie-warnings!)
  (let [json? (:json opts)
        uid   (helpers/resolve-user-id (:user-id opts))
        layer (parse-layer (:layer opts))
        id    (some-> (first (:_arguments opts)) str/trim not-empty)
        undo? (boolean (:undo opts))]
    (cond
      (or (nil? layer) (nil? id))
      (do (if json? (print-json! {:success false :error "--layer and <entry-id> are required" :user-id uid})
              (emit-err! "Usage: by memory archive <entry-id> --layer l2|l3 [--undo]"))
          (System/exit 1))
      :else
      (try
        (let [ok (with-memory-manager uid (fn [mm] (if undo? (mem/unarchive! mm layer id)
                                                       (mem/archive! mm layer id))))]
          (mem-audit! (if undo? :unarchive :archive) uid {:layer layer :id id :ok (boolean ok)})
          (if json?
            (print-json! {:success (boolean ok) :user-id uid :layer layer :id id :undo undo?
                          :archived (boolean (and ok (not undo?)))})
            (println (if ok (format "%s %s in %s." (if undo? "Unarchived" "Archived") id (name layer))
                         (format "No change (%s not found)." id))))
          (System/exit (if ok 0 1)))
        (catch Exception e
          (if json? (print-json! {:success false :error (.getMessage e) :user-id uid})
              (emit-err! (str "Error: " (.getMessage e))))
          (System/exit 1))))))

(defn cmd-memory-promote
  "Copy an entry up a layer (default l2 → l3) with a provenance link back to the
   source. The source entry is left intact (forget it separately if desired)."
  [opts]
  (helpers/suppress-jul-cookie-warnings!)
  (let [json? (:json opts)
        uid   (helpers/resolve-user-id (:user-id opts))
        id    (some-> (first (:_arguments opts)) str/trim not-empty)
        from  (or (parse-layer (:from opts)) :l2)
        to    (or (parse-layer (:to opts)) :l3)]
    (cond
      (nil? id)
      (do (if json? (print-json! {:success false :error "<entry-id> is required" :user-id uid})
              (emit-err! "Usage: by memory promote <entry-id> [--from l2] [--to l3]"))
          (System/exit 1))
      :else
      (try
        (let [result (with-memory-manager
                       uid (fn [mm]
                             (when-let [src (first (mem/read-entries mm from {:id id}
                                                                     {:limit 1 :include-archived true}))]
                               (mem/promote-entry mm src from to))))]
          (mem-audit! :promote uid {:from from :to to :id id :ok (boolean result)})
          (cond
            (nil? result)
            (do (if json? (print-json! {:success false :user-id uid :from from :to to :id id
                                        :error "source not found"})
                    (emit-err! (str "Not found: " id " in " (name from))))
                (System/exit 1))
            :else
            (do (if json?
                  (print-json! {:success true :user-id uid :from from :to to
                                :id id :new-id (:id result) :entry result})
                  (println (format "Promoted %s (%s → %s) as %s."
                                   id (name from) (name to) (:id result))))
                (System/exit 0))))
        (catch Exception e
          (if json? (print-json! {:success false :error (.getMessage e) :user-id uid})
              (emit-err! (str "Error: " (.getMessage e))))
          (System/exit 1))))))

(defn cmd-memory-sweep
  "Run the L2 retention sweep: tombstone episodes older than --retention-days
   (default 30) that are not pinned. Confirms unless --yes/--json."
  [opts]
  (helpers/suppress-jul-cookie-warnings!)
  (let [json? (:json opts)
        uid   (helpers/resolve-user-id (:user-id opts))
        days  (:retention-days opts)]
    (if-not (require-confirm! opts (format "Tombstone unpinned L2 episodes older than %s days? [y/N] "
                                           (or days 30)))
      (do (emit-err! "Aborted.") (System/exit 1))
      (try
        (let [n (with-memory-manager
                  uid (fn [mm] (apply mem/sweep-l2! mm (when days [:retention-days days]))))]
          (mem-audit! :sweep uid {:retention-days (or days 30) :tombstoned n})
          (if json?
            (print-json! {:success true :user-id uid :retention-days (or days 30) :tombstoned n})
            (println (format "Swept L2[%s]: tombstoned %s episode(s)." uid n)))
          (System/exit 0))
        (catch Exception e
          (if json? (print-json! {:success false :error (.getMessage e) :user-id uid})
              (emit-err! (str "Error: " (.getMessage e))))
          (System/exit 1))))))

(defn cmd-memory-prune
  "Enforce total-size budgets on the context graph, evicting the lowest-retention
   nodes/edges over --max-nodes / --max-edges (default: the :graph-max-* config).
   Confirms unless --yes/--json."
  [opts]
  (helpers/suppress-jul-cookie-warnings!)
  (let [json?     (:json opts)
        uid       (helpers/resolve-user-id (:user-id opts))
        max-nodes (or (:max-nodes opts) (agent/get-config :graph-max-nodes))
        max-edges (or (:max-edges opts) (agent/get-config :graph-max-edges))]
    (if-not (require-confirm! opts (format "Prune graph to max-nodes=%s max-edges=%s? [y/N] "
                                           max-nodes max-edges))
      (do (emit-err! "Aborted.") (System/exit 1))
      (try
        (let [report (with-memory-manager
                       uid (fn [mm] (mem/prune-graph-to-budget! mm :max-nodes max-nodes :max-edges max-edges)))]
          (mem-audit! :prune uid (assoc report :max-nodes max-nodes :max-edges max-edges))
          (if json?
            (print-json! {:success true :user-id uid :report report
                          :max-nodes max-nodes :max-edges max-edges})
            (println (format "Pruned graph[%s]: evicted nodes=%s edges=%s → now nodes=%s edges=%s"
                             uid (:nodes-evicted report) (:edges-evicted report)
                             (:nodes report) (:edges report))))
          (System/exit 0))
        (catch Exception e
          (if json? (print-json! {:success false :error (.getMessage e) :user-id uid})
              (emit-err! (str "Error: " (.getMessage e))))
          (System/exit 1))))))

(defn cmd-memory-reembed
  "Rebuild the graph vector index (graph_vec) for the current embedder and resume
   semantic recall — the guided recovery after an embed-model change flags the
   index stale. Re-embeds every L3 fact + node summary."
  [opts]
  (helpers/suppress-jul-cookie-warnings!)
  (let [json? (:json opts)
        uid   (helpers/resolve-user-id (:user-id opts))]
    (try
      (let [report (with-memory-manager uid (fn [mm] (mem/reembed-graph-vec! mm)))]
        (mem-audit! :reembed uid (or report {:no-embedder true}))
        (if json?
          (print-json! {:success true :user-id uid :report report})
          (if report
            (println (format "Re-embedded[%s]: facts=%s nodes=%s"
                             uid (:facts report) (:nodes report)))
            (println "No embedder configured — nothing to re-embed.")))
        (System/exit 0))
      (catch Exception e
        (if json? (print-json! {:success false :error (.getMessage e) :user-id uid})
            (emit-err! (str "Error: " (.getMessage e))))
        (System/exit 1)))))

(defn cmd-memory-graph-build
  "Synchronously extract a user's L2 episodes into the context-graph
   (graph_nodes/graph_edges). Requires the graph tier configured
   (BY_ENABLE_GRAPH_MEMORY + BY_GRAPH_EXTRACT_MODEL). This is the deterministic
   precursor to `consolidate --reducer community`; the async capture-time
   extractor would otherwise be dropped by the 1s shutdown drain on one-shot
   `ask`."
  [opts]
  (helpers/suppress-jul-cookie-warnings!)
  (let [json?         (:json opts)
        uid           (helpers/resolve-user-id (:user-id opts))
        sid           (some-> (:session opts) str/trim not-empty)
        rebuild?      (boolean (:rebuild opts))
        max-entities  (or (:max-entities-per-episode opts)
                          (agent/get-config :graph-max-entities-per-episode))
        max-relations (or (:max-relations-per-episode opts)
                          (agent/get-config :graph-max-relations-per-episode))]
    (try
      (let [report (with-memory-manager
                     uid
                     (fn [mm]
                       (apply mem/extract-l2-graph! mm
                              (cond-> [:max-entities max-entities
                                       :max-relations max-relations
                                       :max-input-chars (or (agent/get-config :graph-extract-max-input-chars) 400000)
                                       :max-episodes-per-window (or (agent/get-config :graph-extract-batch-episodes) 10)]
                                sid      (into [:session-id sid])
                                rebuild? (into [:rebuild? true])))))]
        (if json?
          (print-json! {:success true :user-id uid :session sid :rebuild rebuild? :report report})
          (println (format "Graph-build [%s%s] → attempted=%s/%s in %s call(s) (%s)%s"
                           uid (if sid (str " / " sid) "")
                           (:attempted report) (:total report) (:calls report 0)
                           (if rebuild? "full rebuild" "incremental")
                           (if (:no-extract-fn report)
                             " (no extract model configured — graph tier off)" "")))))
      (catch Exception e
        (if json?
          (print-json! {:success false :error (.getMessage e) :user-id uid})
          (println "Error:" (.getMessage e)))
        (System/exit 1)))))

(defn cmd-memory-reduce
  "One-shot 'finalize this session into L3': batch-extract the session's L2 tail
   into the context-graph, then run the community reducer — i.e. `graph-build`
   followed by `consolidate --reducer community` in a single manager lifecycle.

   This is the entrypoint the DETACHED session-end offload re-execs
   (`by memory reduce -u <uid> -s <sid>`), so the interactive TUI doesn't block
   /quit on minutes of graph work; it is equally runnable by hand. Requires the
   graph tier configured (BY_ENABLE_GRAPH_MEMORY + an extract model); the child
   inherits those env vars from the launching process.

   Graph-size knobs (each defaults to its `:graph-max-*` config value):
   `--max-entities-per-episode` / `--max-relations-per-episode` cap per-episode
   extraction growth; `--max-nodes` / `--max-edges` cap the resulting total
   graph (lowest-retention nodes/edges evicted once extraction finishes).
   Per-stage progress is written to stderr (suppressed under `--json`)."
  [opts]
  (helpers/suppress-jul-cookie-warnings!)
  (let [json?         (:json opts)
        uid           (helpers/resolve-user-id (:user-id opts))
        sid           (some-> (:session opts) str/trim not-empty)
        rebuild?      (boolean (:rebuild opts))
        ;; CLI flag wins; else fall back to the config default (env/config.edn
        ;; still resolve through get-config).
        max-nodes     (or (:max-nodes opts) (agent/get-config :graph-max-nodes))
        max-edges     (or (:max-edges opts) (agent/get-config :graph-max-edges))
        max-entities  (or (:max-entities-per-episode opts)
                          (agent/get-config :graph-max-entities-per-episode))
        max-relations (or (:max-relations-per-episode opts)
                          (agent/get-config :graph-max-relations-per-episode))
        progress!     (fn [fmt & args]
                        (when-not json?
                          (binding [*out* *err*]
                            (println (apply format (str "reduce: " fmt) args)))))]
    (try
      (let [report (with-memory-manager
                     uid
                     (fn [mm]
                       (progress! "extracting L2 → graph (%s; max-entities=%s max-relations=%s)…"
                                  (if rebuild? "full rebuild" "incremental")
                                  max-entities max-relations)
                       (let [g (apply mem/extract-l2-graph! mm
                                      (cond-> [:max-entities max-entities
                                               :max-relations max-relations
                                               :max-input-chars (or (agent/get-config :graph-extract-max-input-chars) 400000)
                                               :max-episodes-per-window (or (agent/get-config :graph-extract-batch-episodes) 10)]
                                        sid      (into [:session-id sid])
                                        rebuild? (into [:rebuild? true])))
                             _ (progress! "  extracted %s/%s episode(s) in %s call(s)"
                                          (:attempted g) (:total g) (:calls g 0))
                             _ (progress! "pruning graph to budget (max-nodes=%s max-edges=%s)…"
                                          max-nodes max-edges)
                             p (mem/prune-graph-to-budget! mm :max-nodes max-nodes
                                                           :max-edges max-edges)
                             _ (progress! "  graph now %s node(s) / %s edge(s) (evicted %s node(s), %s edge(s))"
                                          (:nodes p) (:edges p)
                                          (:nodes-evicted p) (:edges-evicted p))
                             _ (progress! "consolidating L2 → L3 (community reducer)…")
                             c (apply mem/consolidate-l2! mm
                                      (cond-> [:reducer :community]
                                        sid (into [:session-id sid])))
                             _ (progress! "  produced %s summary/summaries, consumed %s episode(s)"
                                          (:produced c) (:consumed c))]
                         {:graph-build g :prune p :consolidate c})))]
        (if json?
          (print-json! {:success true :user-id uid :session sid :rebuild rebuild? :report report})
          (println (format "Reduced [%s%s] → graph-attempted=%s/%s nodes=%s edges=%s (evicted %s/%s) consolidate-produced=%s consumed=%s"
                           uid (if sid (str " / " sid) "")
                           (get-in report [:graph-build :attempted])
                           (get-in report [:graph-build :total])
                           (get-in report [:prune :nodes])
                           (get-in report [:prune :edges])
                           (get-in report [:prune :nodes-evicted])
                           (get-in report [:prune :edges-evicted])
                           (get-in report [:consolidate :produced])
                           (get-in report [:consolidate :consumed])))))
      (catch Exception e
        (if json?
          (print-json! {:success false :error (.getMessage e) :user-id uid})
          (println "Error:" (.getMessage e)))
        (System/exit 1)))))

;; ============================================================================
;; Subcommand: agents — list available agents
;; ============================================================================

(def ^:private agent-desc-max-lines
  "Cap on lines printed per agent description in `format-agents-table`.
   Longer descriptions are truncated and the final line is suffixed
   with `...`."
  2)

(defn- truncate-lines
  "Return at most `max-lines` lines of `s` (flush-left: every line after
   the first has its leading whitespace stripped). If truncated, append
   `...` to the last kept line."
  [s max-lines]
  (let [raw (str/split-lines (or s ""))
        ;; First line stays as-is so it can sit next to the agent id;
        ;; continuation lines are stripped of source-doc indentation.
        normalized (if (empty? raw)
                     raw
                     (into [(first raw)] (map str/triml) (rest raw)))]
    (if (<= (count normalized) max-lines)
      (str/join \newline normalized)
      (let [kept (vec (take max-lines normalized))
            last-line (peek kept)]
        (str/join \newline
                  (conj (pop kept)
                        (str (str/trimr last-line) " ...")))))))

(defn- format-agents-table
  "Format agent definitions as a simple text table.
   Two columns: AGENT, DESCRIPTION. Descriptions are capped at
   `agent-desc-max-lines` lines (truncated with `...` if longer);
   continuation lines are indented to sit under the DESCRIPTION column,
   with any source-docstring leading whitespace stripped first so the
   alignment is consistent."
  [agent-defs]
  (let [rows (map (fn [[id entry]]
                    {:id (name id)
                     :description (truncate-lines
                                   (or (get-in entry [:meta :description]) "")
                                   agent-desc-max-lines)})
                  (sort-by key agent-defs))
        id-width (max 10 (apply max (map #(count (:id %)) rows)))
        fmt-str (str "  %-" id-width "s  %s")
        ;; Prefix before description content = "  " + id padded to
        ;; id-width + "  ".  Continuation lines re-create that prefix
        ;; so they sit flush under the DESCRIPTION column.
        cont-indent (apply str (repeat (+ 2 id-width 2) \space))
        indent-cont (fn [desc]
                      (str/join (str \newline cont-indent)
                                (str/split-lines desc)))]
    (println)
    (println (format fmt-str "AGENT" "DESCRIPTION"))
    (println (format fmt-str
                     (apply str (repeat id-width "-"))
                     "-----------"))
    (doseq [{:keys [id description]} rows]
      (println (format fmt-str id (indent-cont description))))
    (println)))

(defn cmd-agents
  "List available agents. `--json` emits a machine-readable array instead of
   the table."
  [opts]
  (let [agent-defs (agent/get-tool-defs :type :agent)]
    (cond
      (:json opts)
      (print-json! (->> (sort-by key agent-defs)
                        (mapv (fn [[id entry]]
                                {:id (name id)
                                 :description (get-in entry [:meta :description])}))))

      (empty? agent-defs)
      (println "No agents registered.")

      :else
      (do
        (println (str (count agent-defs) " agent(s) available:"))
        (format-agents-table agent-defs)))))

;; ============================================================================
;; Subcommand: models — list available LLM models
;; ============================================================================

(defn- format-models-table
  "Format the popular-models catalog as a simple text table.
   Rows are sorted by provider, then model. Optionally filtered to one
   provider via the `provider-filter` keyword."
  [models provider-filter]
  (let [rows (->> models
                  (filter (fn [m]
                            (or (nil? provider-filter)
                                (= provider-filter (:provider m)))))
                  (map (fn [{:keys [provider model description region]}]
                         {:provider (name provider)
                          :model    (cond-> model region (str " (" region ")"))
                          :description (or description "")}))
                  (sort-by (juxt :provider :model)))
        prov-width  (max 10 (apply max 8 (map #(count (:provider %)) rows)))
        model-width (max 18 (apply max 5 (map #(count (:model %)) rows)))
        fmt-str (str "  %-" prov-width "s  %-" model-width "s  %s")]
    (println)
    (println (format fmt-str "PROVIDER" "MODEL" "DESCRIPTION"))
    (println (format fmt-str
                     (apply str (repeat prov-width "-"))
                     (apply str (repeat model-width "-"))
                     "-----------"))
    (doseq [{:keys [provider model description]} rows]
      (println (format fmt-str provider model
                       (if (> (count description) 60)
                         (str (subs description 0 57) "...")
                         description))))
    (println)
    (count rows)))

(defn cmd-models-refresh
  "Ask every reachable provider which models it serves, now, ignoring the TTL.

   Only providers this machine can actually reach are contacted — one with no
   API key in the environment is skipped without a request. Ollama and other
   local servers need no credentials, which is the point: a baked list can
   never know what someone has pulled locally."
  [opts]
  (install-catalog-cache!)
  (let [candidates (clj-llm/refreshable-providers)]
    (if (empty? candidates)
      (println "No refreshable providers — none reachable or credentialed here.")
      (do
        (when-not (:json opts)
          (println (str "Refreshing: " (str/join ", " (map name candidates)) " …")))
        (let [refreshed (clj-llm/refresh-catalog! {:force? true})
              drift     (clj-llm/catalog-drift)]
          (if (:json opts)
            (print-json! {:refreshed (vec refreshed) :drift drift})
            (do
              (doseq [p refreshed]
                (let [n (count (:models (get (clj-llm/catalog-overlay) p)))]
                  (println (format "  %-12s %d model(s)" (name p) n))))
              (when-let [skipped (seq (remove (set refreshed) candidates))]
                (println (str "  (unreachable: " (str/join ", " (map name skipped)) ")")))
              (println (str (count refreshed) " provider(s) refreshed."))
              (when (seq drift)
                (println "Run `by models --drift` to see what changed.")))))))))

(defn cmd-models-drift
  "Show how the live providers differ from the baked catalog.

   `retired` entries are catalogued models the provider no longer serves —
   the failure this whole mechanism exists to surface, since a stale entry is
   otherwise only discovered when a call fails. `discovered` are models the
   provider serves that the catalog has never heard of: usable immediately,
   but deliberately NOT in the picker until a human gives them a rank and a
   description.

   Bedrock reports no retirements by design — it is enumerated one region at a
   time, and one region's inventory cannot prove a model is globally gone."
  [opts]
  (install-catalog-cache!)
  (let [drift (clj-llm/catalog-drift)]
    (cond
      (:json opts) (print-json! drift)

      (empty? drift)
      (println (if (empty? (clj-llm/catalog-overlay))
                 "No refresh cached yet — run `by models --refresh`."
                 "No drift: the catalog matches every refreshed provider."))

      :else
      (doseq [[provider {:keys [retired discovered fetched-at]}] drift]
        (println (str (name provider)
                      (when fetched-at (str "  (refreshed " fetched-at ")"))))
        (doseq [r retired]
          (println (format "  - retired    %-42s %s"
                           (:model r)
                           (if (:curated-rank r) "[was in picker]" ""))))
        (doseq [d discovered]
          (println (format "  + discovered %s" d)))
        (println)))))

(declare cmd-models-refresh cmd-models-drift)

(defn cmd-models
  "List available LLM models (provider/model) from the curated popular-models
   catalog in clj-llm. Optionally filter to a single provider with -p/--provider.

   `--refresh` and `--drift` select a mode rather than listing, the same way
   `sessions prune --expired/--all` does. They are flags rather than
   subcommands because this command already has a `:runs`, and the router
   dispatches to either `:runs` or `:subcommands` — never both, so a
   `models refresh` subcommand would silently fall through to the listing."
  [opts]
  (cond
    (:refresh opts) (cmd-models-refresh opts)
    (:drift opts)   (cmd-models-drift opts)
    :else
    (let [models (do (install-catalog-cache!) (clj-llm/get-popular-models))
          provider-filter (some-> (:provider opts) keyword)]
      (if (empty? models)
        (println "No models registered.")
        (let [n (format-models-table models provider-filter)]
          (if (and provider-filter (zero? n))
            (println (str "No models found for provider: " (name provider-filter)))
            (println (str n " model(s) listed."
                          (when provider-filter
                            (str " (filtered to " (name provider-filter) ")"))))))))))

;; ============================================================================
;; Subcommand: config — interactive environment bootstrap
;; ============================================================================

(defn cmd-config
  "Run the three-phase bootstrap pipeline.
   Opts: --auto, --profile, --skip-handoff, --re-bootstrap, --dry-run, --log"
  [opts]
  (let [run-opts (-> (select-keys opts [:auto :profile :skip-handoff :re-bootstrap :dry-run :log])
                     (cond-> (:profile opts)
                       (update :profile keyword)))]
    (config-wizard/run! run-opts)))

;; ============================================================================
;; Subcommand: sessions — list / prune persisted sessions
;; ============================================================================

(defn- known-session? [id]
  (and id (contains? (set (persist/list-sessions)) id)))

(defn cmd-sessions-list
  "List every persisted agent session (project-scoped). `--tree` renders the
   fork/lineage tree instead of the flat table; `--live` keeps only sessions
   open in a running `by` process. Each row carries `:live?`/`:owner-pid` (see
   docs/design/session-channel-extensions.md §2). Shared formatting with the TUI
   `/session list` via `agent-tui.session-summary`."
  [opts]
  ;; Pin the project-scoped sessions root (from cwd's project-dir) before reading.
  (install-working-dir! opts)
  (let [live-only? (:live opts)
        rows       (cond->> (ssum/enriched-summaries)
                     live-only? (filterv :live?))]
    (cond
      ;; --json wins over --tree: the flat array carries :parent-id so a consumer
      ;; can rebuild the lineage itself. Always valid JSON ([] when empty).
      (:json opts)
      (print-json! rows)

      ;; --tree ignores --live (lineage needs the full set); honored only without --json.
      (and (:tree opts) (not live-only?))
      (doseq [line (ssum/format-tree {})] (println line))

      :else
      (if (empty? rows)
        (println (if live-only? "No live sessions." "No persisted sessions."))
        (doseq [line (ssum/format-table rows {})] (println line))))))

(defn cmd-sessions-show
  "Print full detail for one persisted session (meta, lineage, counts, first
   user message, last answer)."
  [opts]
  (install-working-dir! opts)
  (let [id (or (:session-id opts) (first (:_arguments opts)))]
    (cond
      (str/blank? (str id))
      (do (emit-err! "Usage: by sessions show <session-id>") (System/exit 1))

      (not (known-session? id))
      (do (emit-err! (str "Session not found: " id)) (System/exit 1))

      :else
      (let [row (first (filter #(= id (:session-id %)) (ssum/enriched-summaries)))]
        (doseq [line (ssum/format-detail row)] (println line))))))

(defn- format-config-lines
  "Human-readable rendering of a `:config` op response — a header line plus the
   overrides (or `--query` matches). The machine path is `--json`; this is the
   terminal-friendly fallback."
  [{:keys [session-id agent provider model total overrides query matches]}]
  (concat
   [(str "session " session-id
         (when agent (str "  agent=" agent))
         (when provider (str "  provider=" provider))
         (when model (str "  model=" model)))]
   (if query
     (cons (str "config keys matching \"" query "\":")
           (if (seq matches)
             (for [{:keys [key value default]} matches]
               (str "  " key " = " (pr-str value)
                    (when (some? default) (str "  (default " (pr-str default) ")"))))
             ["  (no matching keys)"]))
     (cons (str (count overrides) " of " total " keys differ from their default:")
           (if (seq overrides)
             (for [[k v] (sort-by (comp name key) overrides)]
               (str "  " (name k) " = " (pr-str v)))
             ["  (all keys at their default)"])))))

(defn cmd-sessions-config
  "Read the effective configuration of a LIVE session over its ask channel
   (read-only — never injects a turn). Resolves the session's `:ask-socket-path`
   from the project-scoped session index (honoring `-C`), sends a `:config` op,
   and prints the snapshot. `--json` emits machine-readable JSON (the playground
   path); `--query TERM` narrows to config keys matching a term. Adapts the
   `by config --snapshot` idea to the existing `sessions` subcommand tree
   (`by config` is already the bootstrap wizard)."
  [opts]
  (install-working-dir! opts)
  (let [json? (:json opts)
        id    (or (:session-id opts) (first (:_arguments opts)))
        query (:query opts)]
    (cond
      (str/blank? (str id))
      (do (if json?
            (print-json! {:success false :error "session-id argument is required"})
            (emit-err! "Usage: by sessions config <session-id> [--json] [--query TERM]"))
          (System/exit 1))

      :else
      (let [row  (first (filter #(= id (:session-id %)) (ssum/enriched-summaries)))
            sock (:ask-socket-path row)]
        (cond
          (nil? row)
          (do (if json?
                (print-json! {:success false :session-id id :error (str "session not found: " id)})
                (emit-err! (str "Session not found: " id)))
              (System/exit 1))

          (or (str/blank? (str sock)) (not (.exists (io/file ^String sock))))
          (do (if json?
                (print-json! {:success false :session-id id :attachable false
                              :error (str "session '" id "' is not attachable (no live ask socket)")})
                (emit-err! (str "Error: session '" id "' is not attachable "
                                "(not open in a running `by`).")))
              (System/exit 1))

          :else
          (let [req  (cond-> {:op :config}
                       (not (str/blank? (str query))) (assoc :query query))
                resp (try (ask-channel/send-op! sock req)
                          (catch Exception e
                            {:status :error
                             :error (str "could not reach session: " (.getMessage e)
                                         " (is it still running?)")}))
                ok?  (= :ok (:status resp))]
            (if json?
              (print-json! (if ok?
                             (assoc resp :success true :session-id id)
                             {:success false :session-id id :attachable true
                              :error (:error resp)}))
              (if ok?
                (doseq [line (format-config-lines resp)] (println line))
                (emit-err! (str "Error: " (:error resp)))))
            (System/exit (if ok? 0 1))))))))

(defn- attachable-session-rows
  "Session index rows whose ask socket is live (path present + file exists)."
  []
  (->> (ssum/enriched-summaries)
       (filter #(let [s (:ask-socket-path %)]
                  (and (not (str/blank? (str s))) (.exists (io/file ^String s)))))))

(defn cmd-a2a-serve
  "Serve local agents over the Agent2Agent protocol.

   Runs in the FOREGROUND until interrupted: this is a server, and
   daemonising it would hide both its logs and the fact that it is
   listening. Ctrl-C stops it.

   Three things must be true before it binds, and each refusal names its
   own fix rather than failing generically:
     - `:enable-a2a` is on (BY_ENABLE_A2A=1)
     - `:a2a-serve-token` is set (BY_A2A_SERVE_TOKEN) — there is no
       unauthenticated mode
     - `:a2a-expose-skills` names at least one agent — nothing is exposed
       by default

   Inbound A2A executes prompts against this workspace with tools and disk
   access. The default bind address is loopback; exposing it further should
   be paired with `--sandbox`. See docs/design/a2a-design.md §8."
  [opts]
  (install-working-dir! opts)
  (let [json? (:json opts)
        host  (or (:host opts) (agent/get-config nil :a2a-serve-host))
        port  (or (:port opts) (agent/get-config nil :a2a-serve-port))
        result (agent/a2a-serve! nil {:host host :port port})]
    (if (:error result)
      (do (if json?
            (print-json! {:success false :error (:error result)})
            (emit-err! (str "Error: " (:error result))))
          (System/exit 1))
      (do
        (if json?
          (print-json! {:success true :url (:url result) :card-url (:card-url result)
                        :host (:host result) :port (:port result)})
          (do (println (str "A2A server listening on " (:url result)))
              (println (str "  agent card: " (:card-url result)))
              (println (str "  skills:     "
                            (str/join ", " (agent/a2a-exposed-skill-ids nil))))
              (println "  auth:       Bearer <BY_A2A_SERVE_TOKEN>")
              (when-not (contains? #{"127.0.0.1" "localhost" "::1"} (str (:host result)))
                (println (str "  WARNING: bound to " (:host result)
                              " — reachable beyond this host. Inbound A2A runs"
                              " prompts against this workspace; consider --sandbox.")))
              (println "\nCtrl-C to stop.")))
        ;; Park the main thread. The server's own threads are daemons, so
        ;; without this the JVM would exit immediately and the listener
        ;; would vanish the moment it started.
        @(promise)))))

(defn cmd-events-emit
  "Fire a user-defined event INTO a live session over its ask channel
   (external → agent — the CLI twin of `by ask --attach`). Resolves the
   session's `:ask-socket-path` from the project-scoped index (honoring `-C`)
   and sends `{:op :emit …}`. With no `-s` and exactly one live session, targets
   it. `--payload` takes an EDN map. See docs/design/event-bus-and-reactor.md."
  [opts]
  (install-working-dir! opts)
  (let [json?   (:json opts)
        event   (or (:event opts) (first (:_arguments opts)))
        pstr    (:payload opts)
        payload (when-not (str/blank? (str pstr))
                  (try (edn/read-string pstr) (catch Exception _ ::bad)))
        rows    (attachable-session-rows)
        id      (or (:session-id opts)
                    (when (= 1 (count rows)) (:session-id (first rows))))]
    (cond
      (str/blank? (str event))
      (do (if json?
            (print-json! {:success false :error "--event is required"})
            (emit-err! "Usage: by events emit --event <name> [--payload '{…}'] [-s <session-id>]"))
          (System/exit 1))

      (= ::bad payload)
      (do (if json?
            (print-json! {:success false :error "invalid --payload EDN"})
            (emit-err! "Error: --payload must be an EDN map, e.g. '{:order-id \"A-91\"}'"))
          (System/exit 1))

      (str/blank? (str id))
      (do (if json?
            (print-json! {:success false :error "no live session (specify -s <session-id>)"})
            (emit-err! (str "Error: no live session to emit into"
                            (when (> (count rows) 1) " (multiple live — specify -s <session-id>)")
                            ".")))
          (System/exit 1))

      :else
      (let [row  (first (filter #(= id (:session-id %)) (ssum/enriched-summaries)))
            sock (:ask-socket-path row)]
        (if (or (nil? row) (str/blank? (str sock)) (not (.exists (io/file ^String sock))))
          (do (if json?
                (print-json! {:success false :session-id id :attachable false
                              :error (str "session '" id "' is not attachable (no live ask socket)")})
                (emit-err! (str "Error: session '" id "' is not attachable "
                                "(not open in a running `by`).")))
              (System/exit 1))
          (let [req  (cond-> {:op :emit :event event}
                       (map? payload) (assoc :payload payload))
                resp (try (ask-channel/send-op! sock req)
                          (catch Exception e
                            {:status :error
                             :error (str "could not reach session: " (.getMessage e)
                                         " (is it still running?)")}))
                ok?  (= :ok (:status resp))]
            (if json?
              (print-json! (if ok?
                             (assoc resp :success true :session-id id)
                             {:success false :session-id id :error (:error resp)}))
              (if ok?
                (println (str "Emitted " (:emitted resp)
                              " → " (:subscribers resp) " subscriber(s)"
                              (when (:note resp) (str " — " (:note resp)))))
                (emit-err! (str "Error: " (:error resp)))))
            (System/exit (if ok? 0 1))))))))

(defn cmd-sessions-label
  "Set (or clear) a persisted session's label. Usage:
     by sessions label <session-id> <text…>   ; set
     by sessions label <session-id>           ; clear"
  [opts]
  (install-working-dir! opts)
  (let [args  (:_arguments opts)
        id    (or (:session-id opts) (first args))
        ;; Everything after the id is the label (so multi-word works unquoted).
        words (if (:session-id opts) args (rest args))
        text  (when (seq words) (str/trim (str/join " " words)))
        label (when-not (str/blank? (str text)) text)]
    (cond
      (str/blank? (str id))
      (do (emit-err! "Usage: by sessions label <session-id> <text>") (System/exit 1))

      (not (known-session? id))
      (do (emit-err! (str "Session not found: " id)) (System/exit 1))

      :else
      (do (persist/set-session-label! id label)
          ;; If the session is open in a running `by`, push the rename over its
          ;; ask socket so the live tab strip updates without a restart.
          ;; Best-effort: a closed/stale socket degrades to persist-only.
          (let [row  (first (filter #(= id (:session-id %)) (ssum/enriched-summaries)))
                sock (:ask-socket-path row)
                live? (and (not (str/blank? (str sock)))
                           (.exists (io/file ^String sock))
                           (= :ok (:status
                                   (try (ask-channel/send-op! sock {:op :rename-session :label label})
                                        (catch Exception _ nil)))))]
            (println (cond
                       (and label live?)  (str "Labeled " id ": " label " (live tab renamed)")
                       label              (str "Labeled " id ": " label)
                       live?              (str "Cleared label on " id " (live tab reset)")
                       :else              (str "Cleared label on " id))))))))

(defn- pick-session-to-prune!
  "Show a numbered list of persisted sessions (newest first) and prompt the
   user to pick one to delete. Returns the chosen session-id, or nil for
   cancel."
  []
  (let [rows (ssum/enriched-summaries)
        n    (count rows)]
    (when (pos? n)
      (println)
      (println (str n " persisted session(s) — pick one to prune, or (C)ancel:"))
      (doseq [line (ssum/format-table rows {:numbered? true})] (println line))
      (println)
      (print (str "Choice [1-" n "] / (C)ancel: "))
      (flush)
      (let [line (try (read-line) (catch Throwable _ nil))
            choice (some-> line str/trim)]
        (cond
          (nil? choice)              nil
          (str/blank? choice)        nil
          (#{"c" "C" "cancel"} choice) nil
          :else
          (when-let [i (try (Long/parseLong choice) (catch Throwable _ nil))]
            (when (and (>= i 1) (<= i n))
              (:session-id (nth rows (dec i))))))))))

(defn cmd-sessions-prune
  "Delete persisted session(s):
     by sessions prune <id>        ; one session
     by sessions prune             ; interactive picker
     by sessions prune --expired   ; every session past its TTL (--ttl-days N)
     by sessions prune --all       ; every session (confirm or --yes)"
  [opts]
  ;; Pin the project-scoped sessions root (from cwd's project-dir) before reading.
  (install-working-dir! opts)
  (cond
    (:expired opts)
    (let [ttl (or (:ttl-days opts) 14)
          deleted (persist/purge-expired! ttl)]
      (println (str "Purged " (count deleted) " expired session(s)"
                    (when (seq deleted) (str ": " (str/join ", " deleted))))))

    (:all opts)
    (let [ids (persist/list-sessions)]
      (if (empty? ids)
        (println "No persisted sessions.")
        (if (or (:yes opts)
                (confirm! (str "Delete ALL " (count ids) " persisted session(s)? [y/N] ")))
          (do (doseq [id ids] (persist/delete-session-dir! id))
              (println (str "Deleted " (count ids) " session(s).")))
          (println "Cancelled."))))

    :else
    (let [arg    (or (first (:_arguments opts)) (:session-id opts))
          target (or arg
                     (if (some? (System/console))
                       (pick-session-to-prune!)
                       (throw (ex-info "Usage: by sessions prune <session-id>" {}))))]
      (cond
        (nil? target)
        (println "Cancelled.")

        (persist/delete-session-dir! target)
        (println (str "Deleted session: " target))

        :else
        (println (str "Session not found: " target))))))

;; ============================================================================
;; Subcommand: projects — the user-scope project registry
;; ============================================================================

(defn- local-date-str
  "`inst` as `yyyy-MM-dd` in the local zone, or \"\" when absent/unparseable.
   NOT `(subs (str date) 0 10)` — `java.util.Date`'s string form starts
   `Tue Aug 04 …`, so slicing it yields a weekday, not a date."
  [inst]
  (try
    (if inst
      (-> (java.time.Instant/ofEpochMilli (inst-ms inst))
          (.atZone (java.time.ZoneId/systemDefault))
          (.toLocalDate)
          str)
      "")
    (catch Exception _ "")))

(defn- format-projects-table
  "Render registry rows as an aligned table. Returns a seq of lines."
  [rows]
  (let [col    (fn [k hdr] (apply max (count hdr) (map #(count (str (get % k))) rows)))
        w-slug (col :slug "SLUG")
        w-name (col :name "NAME")
        fmt    (str "%-" w-slug "s  %-" w-name "s  %-10s  %s")]
    (cons (format fmt "SLUG" "NAME" "OPENED" "PATH")
          (map (fn [r]
                 (format fmt
                         (str (:slug r))
                         (str (:name r))
                         (local-date-str (:last-opened-at r))
                         (str (:path r) (when (:missing? r) "  (missing)"))))
               rows))))

(defn cmd-projects-list
  "List every project registered under `~/.brainyard/projects/`, newest first.

   Rows whose directory no longer exists are tagged `(missing)` rather than
   removed — a moved or unmounted project should be visible, not silently
   dropped. Reclaiming those entries is a separate concern, in keeping with
   how task artifacts are GC-swept rather than deleted inline."
  [opts]
  (let [rows (agent/list-projects (agent/init-dirs!))]
    (if (:json opts)
      (print-json! rows)
      (if (empty? rows)
        (println "No registered projects.")
        (doseq [line (format-projects-table rows)] (println line))))))

(defn cmd-projects-path
  "Print the absolute project path for a registry slug — the reverse of the
   slug derivation. Exits 1 when the slug isn't registered."
  [opts]
  (let [slug (or (first (:_arguments opts)) (:slug opts))]
    (when (str/blank? (str slug))
      (exit-err! "Usage: by projects path <slug>"))
    (if-let [p (agent/project-path-for-slug (agent/init-dirs!) slug)]
      (println p)
      (exit-err! (str "Not a registered project slug: " slug)))))

(defn cmd-projects-add
  "Register a project WITHOUT opening a session in it.

   Until now the registry only gained entries as a side effect of `run-tui!` /
   `cmd-ask`, which means a tool that wants to offer \"add an existing repo\"
   had to boot a whole agent just to record a path. That is the entire reason
   this exists; the work itself is one call to the same idempotent
   `register-project!` those paths use, so re-adding preserves `:created-at`
   and simply re-stamps `:last-opened-at`.

   Defaults to the effective working directory, so a bare `by projects add`
   inside a repo registers that repo. Exits 1 when the path is not a directory
   — registering a typo would put a permanently `(missing)` row in the registry."
  [opts]
  (let [raw (or (first (:_arguments opts)) (:path opts) ".")
        f   (io/file (str raw))
        ;; Hinted: `try` erases to Object, so without this the .isDirectory /
        ;; .getPath below become reflective — which `bb reflect:check` fails the
        ;; build over, since reflection is a native-image break waiting to happen.
        ^java.io.File dir (try (.getCanonicalFile f)
                               (catch Exception _ (.getAbsoluteFile f)))]
    (when-not (.isDirectory dir)
      (exit-err! (str "Not a directory: " dir)))
    (if-let [rec (agent/register-project! (agent/init-dirs!) (.getPath dir))]
      (if (:json opts)
        (print-json! rec)
        (println (:slug rec) (:path rec)))
      (exit-err! (str "Could not register " dir
                      " (is ~/.brainyard writable?)")))))

(defn cmd-projects-remove
  "Forget ONE registered project by slug.

   `prune` reclaims every missing record at once, which is the wrong tool for
   \"drop this one\" — and useless for a project that still exists but is no
   longer worked on. Takes the slug rather than the path because the slug is
   what `list` prints and what a UI already holds; `by projects path <slug>`
   goes the other way.

   Confirms first (`--yes` skips it, for scripting and for callers with no TTY),
   and removes only the user-scope registry folder — never the project."
  [opts]
  (let [slug (some-> (or (first (:_arguments opts)) (:slug opts)) str str/trim not-empty)
        dirs (agent/init-dirs!)]
    (when-not slug
      (exit-err! "Usage: by projects remove <slug>  (see `by projects list`)"))
    (if-let [rec (first (filterv #(= slug (:slug %)) (agent/list-projects dirs)))]
      (if (or (:yes opts)
              (confirm! (str "Forget \"" (:name rec) "\" (" (:path rec) ")?\n"
                             "This drops only the user-scope registry folder"
                             ", never the project itself. [y/N] ")))
        (if-let [removed (agent/remove-project! dirs slug)]
          (if (:json opts)
            (print-json! removed)
            (println "Removed:" (:slug removed) (:path removed)))
          (exit-err! (str "Could not remove " slug " (is ~/.brainyard writable?)")))
        (println "Cancelled."))
      (exit-err! (str "No such registered project: " slug)))))

(defn cmd-projects-prune
  "Drop registry entries whose project directory no longer exists.

   `list` tags those rows `(missing)` and keeps them, so before this the
   registry only ever grew — every scratch dir, every temp checkout, every
   moved repo left a permanent row (the slug is path-derived, so moving a
   project orphans the old entry rather than re-homing it).

   Confirms first, because `(missing)` is not proof the project is gone: an
   unmounted volume or a disconnected network share reports exactly the same
   way and comes back later. `--yes` skips the prompt for scripting; `--json`
   reports the pruned records."
  [opts]
  (let [dirs (agent/init-dirs!)
        gone (filterv :missing? (agent/list-projects dirs))]
    (cond
      (empty? gone)
      (if (:json opts)
        (print-json! [])
        (println "Nothing to prune — every registered project still exists."))

      (not (or (:yes opts)
               (confirm! (str "Remove " (count gone)
                              " registry entr" (if (= 1 (count gone)) "y" "ies")
                              " for missing project(s)?\n  "
                              (str/join "\n  " (map :path gone))
                              "\nThis drops only the user-scope registry folder"
                              ", never the project itself. [y/N] "))))
      (println "Cancelled.")

      :else
      (let [pruned (agent/prune-projects! dirs)]
        (if (:json opts)
          (print-json! pruned)
          (do (doseq [r pruned] (println "removed" (:slug r) (:path r)))
              (println (str "Pruned " (count pruned) " entr"
                            (if (= 1 (count pruned)) "y" "ies") "."))))))))

;; ============================================================================
;; CLI configuration
;; ============================================================================

(def cli-config
  {:command     "by"
   :description "Brainyard Agent CLI"
   :version     app-version
   :subcommands [{:command     "run"
                  :description "Start interactive TUI agent session (default)"
                  :opts        [agent-opt
                                provider-opt
                                model-opt
                                config-opt
                                user-id-opt
                                working-dir-opt
                                {:option "inline" :short "i" :as "Inline mode (no alt screen)"
                                 :type :with-flag :default false}
                                {:option "verbose" :short "v" :as "Verbose output"
                                 :type :with-flag :default false}
                                {:option "with-tmux"
                                 :as "Require tmux side panes / popups (exit 1 if not in a tmux session)"
                                 :type :with-flag :default false}
                                {:option "serve"
                                 :as "Headless daemon: no interactive input; keep the session alive to serve `by ask -s <id>` over its ask socket until SIGTERM/SIGINT (pair with -s <id>)"
                                 :type :with-flag :default false}
                                max-iter-opt
                                {:option "resume" :short "r"
                                 :as "Resume a persisted session: bare = pick from a menu; --resume <id> = that session"
                                 :type :string}
                                {:option "resume-latest"
                                 :as "Resume the most-recent persisted session non-interactively (fresh if none); env BY_RESUME_LATEST"
                                 :type :with-flag :default false}
                                {:option "session" :short "s"
                                 :as "Start a NEW session with this exact id (deterministic paths for scripting/attach); errors if the id already exists — use --resume <id> to reattach"
                                 :type :string}
                                {:option "new"
                                 :as "(deprecated; sessions start fresh by default — accepted as a no-op)"
                                 :type :with-flag :default false}
                                {:option "web"
                                 :as "Share this session over the web via ttyd (requires ttyd on PATH; also BY_WEB=1)"
                                 :type :with-flag :default false}
                                {:option "web-port"
                                 :as "ttyd listen port (default 7681; 0 = random; env BY_WEB_PORT)"
                                 :type :int}
                                {:option "web-bind"
                                 :as "Address ttyd binds (default 127.0.0.1 = localhost-only; env BY_WEB_BIND)"
                                 :type :string}
                                {:option "web-user"
                                 :as "Basic-auth username for the web session (default: by; env BY_WEB_USER)"
                                 :type :string}
                                {:option "web-pass"
                                 :as "Basic-auth password (default: auto-generated and printed; env BY_WEB_PASS)"
                                 :type :string}
                                {:option "web-readonly"
                                 :as "Web clients may watch but not type (env BY_WEB_READONLY)"
                                 :type :with-flag :default false}
                                {:option "web-max-clients"
                                 :as "Max simultaneous web clients (0 = unlimited; env BY_WEB_MAX_CLIENTS)"
                                 :type :int}
                                {:option "web-once"
                                 :as "Stop sharing after the first web client disconnects (env BY_WEB_ONCE)"
                                 :type :with-flag :default false}
                                {:option "web-tmux"
                                 :as "Tier 2: share via a private tmux session; the launching terminal stays a dashboard, drive locally from another terminal or the browser (env BY_WEB_TMUX)"
                                 :type :with-flag :default false}
                                {:option "sandbox"
                                 :as "Run this session inside a macOS sandbox (sandbox-exec write-containment; macOS only; env BY_SANDBOX=1)"
                                 :type :with-flag :default false}
                                {:option "sandbox-profile"
                                 :as "Path to a custom .sb seatbelt profile (overrides the generated default; env BY_SANDBOX_PROFILE)"
                                 :type :string}
                                {:option "sandbox-allow-write"
                                 :as "Extra writable root inside the sandbox; repeat or comma-separate (env BY_SANDBOX_ALLOW_WRITE)"
                                 :type :string :multiple true}
                                {:option "sandbox-no-network"
                                 :as "Deny all network from the sandboxed session (blocks LLM calls; env BY_SANDBOX_NO_NETWORK)"
                                 :type :with-flag :default false}]
                  :runs        cmd-run}
                 {:command     "ask"
                  :description "Ask a one-shot question (non-interactive); --attach to ask a running session"
                  :opts        [agent-opt
                                provider-opt
                                model-opt
                                user-id-opt
                                working-dir-opt
                                max-iter-opt
                                attach-opt
                                ask-timeout-opt
                                session-opt
                                json-opt]
                  :args        [{:arg "question" :as "Question to ask" :type :string}]
                  :runs        cmd-ask}
                 {:command     "agents"
                  :description "List available agents"
                  :opts        [json-opt]
                  :runs        cmd-agents}
                 {:command     "models"
                  :description "List available LLM models (provider/model)"
                  :opts        [{:option "provider" :short "p"
                                 :as "Filter to a single provider (e.g. anthropic, openai, bedrock)"
                                 :type :string}
                                {:option "refresh"
                                 :as "Ask each reachable provider which models it serves, now"
                                 :type :with-flag :default false}
                                {:option "drift"
                                 :as "Show how live providers differ from the baked catalog"
                                 :type :with-flag :default false}
                                json-opt]
                  :runs        cmd-models}
                 {:command     "config"
                  :description "Bootstrap pipeline (detect → ladder → handoff)"
                  :opts        [{:option "auto" :as "Non-interactive mode; apply profile defaults"
                                 :type :with-flag :default false}
                                {:option "profile" :as "Named profile (dev, ci, offline, cloud)"
                                 :type :string}
                                {:option "skip-handoff"
                                 :as "Run phases 1-2 only; skip config-agent prompt"
                                 :type :with-flag :default false}
                                {:option "re-bootstrap"
                                 :as "Force rung re-evaluation even if existing LLM is reachable"
                                 :type :with-flag :default false}
                                {:option "dry-run" :as "Compute the config but do not write it"
                                 :type :with-flag :default false}
                                {:option "log" :as "Override bootstrap-log path" :type :string}]
                  :runs        cmd-config}
                 {:command     "sessions"
                  :description "List, show, label, or prune persisted agent sessions"
                  :subcommands [{:command     "list"
                                 :description "List all persisted sessions"
                                 :opts        [{:option "tree"
                                                :as "Render the fork/lineage tree instead of a flat list"
                                                :type :with-flag :default false}
                                               {:option "live"
                                                :as "Only sessions open in a running `by` process right now"
                                                :type :with-flag :default false}
                                               working-dir-opt
                                               json-opt]
                                 :runs        cmd-sessions-list}
                                {:command     "show"
                                 :description "Show full detail for one session"
                                 :opts        [{:option "session-id" :short "s"
                                                :as "Session ID" :type :string}
                                               working-dir-opt]
                                 :runs        cmd-sessions-show}
                                {:command     "config"
                                 :description "Read a live session's effective configuration over its ask channel"
                                 :opts        [{:option "session-id" :short "s"
                                                :as "Session ID" :type :string}
                                               {:option "query" :short "q"
                                                :as "Only config keys matching this term" :type :string}
                                               working-dir-opt
                                               json-opt]
                                 :runs        cmd-sessions-config}
                                {:command     "label"
                                 :description "Set or clear a session's label (no text = clear)"
                                 :opts        [{:option "session-id" :short "s"
                                                :as "Session ID" :type :string}
                                               working-dir-opt]
                                 :runs        cmd-sessions-label}
                                {:command     "prune"
                                 :description "Delete persisted session(s)"
                                 :opts        [{:option "session-id" :short "s"
                                                :as "Session ID" :type :string}
                                               {:option "expired"
                                                :as "Delete sessions past their TTL"
                                                :type :with-flag :default false}
                                               {:option "ttl-days"
                                                :as "TTL in days for --expired (default 14)"
                                                :type :int}
                                               {:option "all"
                                                :as "Delete ALL persisted sessions"
                                                :type :with-flag :default false}
                                               {:option "yes" :short "y"
                                                :as "Skip the confirmation prompt for --all"
                                                :type :with-flag :default false}
                                               working-dir-opt]
                                 :runs        cmd-sessions-prune}]}
                 {:command     "projects"
                  :description "Inspect the user-scope project registry (~/.brainyard/projects)"
                  :subcommands [{:command     "list"
                                 :description "List every registered project, newest first"
                                 :opts        [json-opt]
                                 :runs        cmd-projects-list}
                                {:command     "path"
                                 :description "Print the absolute project path for a registry slug"
                                 :opts        []
                                 :runs        cmd-projects-path}
                                {:command     "add"
                                 :description "Register a project (default: the working dir) without opening a session"
                                 :opts        [json-opt]
                                 :runs        cmd-projects-add}
                                {:command     "prune"
                                 :description "Drop registry entries whose project directory no longer exists (confirm or --yes)"
                                 :opts        [yes-opt json-opt]
                                 :runs        cmd-projects-prune}
                                {:command     "remove"
                                 :description "Forget one registered project by slug (confirm or --yes)"
                                 :opts        [yes-opt json-opt]
                                 :runs        cmd-projects-remove}]}
                 {:command     "memory"
                  :description "Maintenance on the user-scoped L1/L2/L3 memory store"
                  :subcommands [{:command     "consolidate"
                                 :description "Run L2→L3 consolidation (deterministic heuristic; --reducer community for graph summaries)"
                                 :opts        [user-id-opt session-opt reducer-opt json-opt]
                                 :runs        cmd-memory-consolidate}
                                {:command     "graph-build"
                                 :description "Synchronously extract L2 episodes into the context-graph (graph tier; precedes --reducer community). Incremental by default; --rebuild re-extracts all"
                                 :opts        [user-id-opt session-opt rebuild-opt
                                               max-entities-per-episode-opt
                                               max-relations-per-episode-opt json-opt]
                                 :runs        cmd-memory-graph-build}
                                {:command     "reduce"
                                 :description "graph-build + consolidate --reducer community in one shot; entrypoint for the detached session-end offload. Incremental by default; --rebuild re-extracts all"
                                 :opts        [user-id-opt session-opt rebuild-opt
                                               max-nodes-opt max-edges-opt
                                               max-entities-per-episode-opt
                                               max-relations-per-episode-opt json-opt]
                                 :runs        cmd-memory-reduce}
                                {:command     "stats"
                                 :description "Report L1/L2/L3 counts for a user"
                                 :opts        [user-id-opt json-opt]
                                 :runs        cmd-memory-stats}
                                {:command     "status"
                                 :description "Store health/inventory: L1/L2/L3 counts + graph vector-index staleness"
                                 :opts        [user-id-opt json-opt]
                                 :runs        cmd-memory-status}
                                {:command     "list"
                                 :description "List raw entries from a layer (--layer l1|l2|l3), with --session/--kind/--limit filters"
                                 :opts        [user-id-opt layer-opt session-opt kind-opt
                                               limit-opt include-archived-opt json-opt]
                                 :runs        cmd-memory-list}
                                {:command     "get"
                                 :description "Fetch a single entry by id from a layer (--layer l1|l2|l3)"
                                 :opts        [user-id-opt layer-opt json-opt]
                                 :runs        cmd-memory-get}
                                {:command     "search"
                                 :description "Cross-layer weighted-RRF recall for a query (the real prompt-briefing pipeline, not raw SQL)"
                                 :opts        [user-id-opt session-opt limit-opt json-opt]
                                 :runs        cmd-memory-search}
                                {:command     "explain"
                                 :description "Recall audit: which entries informed a session's prompt(s) (--session, optional --turn)"
                                 :opts        [user-id-opt session-opt turn-opt json-opt]
                                 :runs        cmd-memory-explain}
                                {:command     "graph"
                                 :description "Dump the context-graph (nodes + edges + counts) as JSON; --node <name> scopes to a neighborhood"
                                 :opts        [user-id-opt node-opt limit-opt json-opt]
                                 :runs        cmd-memory-graph}
                                {:command     "forget"
                                 :description "Tombstone an entry by id (excluded from recall; row kept for audit)"
                                 :opts        [user-id-opt layer-opt yes-opt json-opt]
                                 :runs        cmd-memory-forget}
                                {:command     "edit"
                                 :description "Update an entry in place (--content/--kind/--confidence), preserving its id"
                                 :opts        [user-id-opt layer-opt content-opt kind-opt confidence-opt json-opt]
                                 :runs        cmd-memory-edit}
                                {:command     "keep"
                                 :description "Pin an entry against the retention sweep (--undo to unpin)"
                                 :opts        [user-id-opt layer-opt undo-opt json-opt]
                                 :runs        cmd-memory-keep}
                                {:command     "archive"
                                 :description "Archive an entry (excluded from default recall; --undo to unarchive)"
                                 :opts        [user-id-opt layer-opt undo-opt json-opt]
                                 :runs        cmd-memory-archive}
                                {:command     "promote"
                                 :description "Copy an entry up a layer (default l2 → l3) with provenance"
                                 :opts        [user-id-opt from-opt to-opt json-opt]
                                 :runs        cmd-memory-promote}
                                {:command     "sweep"
                                 :description "Run the L2 retention sweep (tombstone old, unpinned episodes)"
                                 :opts        [user-id-opt retention-days-opt yes-opt json-opt]
                                 :runs        cmd-memory-sweep}
                                {:command     "prune"
                                 :description "Evict lowest-retention graph nodes/edges over --max-nodes/--max-edges budget"
                                 :opts        [user-id-opt max-nodes-opt max-edges-opt yes-opt json-opt]
                                 :runs        cmd-memory-prune}
                                {:command     "reembed"
                                 :description "Rebuild the graph vector index for the current embedder (resume semantic recall)"
                                 :opts        [user-id-opt json-opt]
                                 :runs        cmd-memory-reembed}]}
                 {:command     "events"
                  :description "Fire user-defined events into a live session over its ask channel"
                  :subcommands [{:command     "emit"
                                 :description "Emit an event into a live session (external → agent)"
                                 :opts        [{:option "event" :short "e"
                                                :as "Event name (namespaced keyword, e.g. order/shipped)"
                                                :type :string}
                                               {:option "payload" :short "p"
                                                :as "EDN payload map, e.g. '{:order-id \"A-91\"}'"
                                                :type :string}
                                               {:option "session-id" :short "s"
                                                :as "Target session (default: the sole live session)"
                                                :type :string}
                                               working-dir-opt
                                               json-opt]
                                 :runs        cmd-events-emit}]}
                 {:command     "a2a"
                  :description "Serve local agents to other agents over the Agent2Agent protocol"
                  :subcommands [{:command     "serve"
                                 :description "Run the A2A server (foreground; Ctrl-C to stop)"
                                 :opts        [{:option "host"
                                                :as "Bind address (default: BY_A2A_SERVE_HOST, else 127.0.0.1)"
                                                :type :string}
                                               {:option "port" :short "p"
                                                :as "Port (default: BY_A2A_SERVE_PORT, else 41241)"
                                                :type :int}
                                               working-dir-opt
                                               json-opt]
                                 :runs        cmd-a2a-serve}]}]})

;; ============================================================================
;; Entry point
;; ============================================================================

(def ^:private known-subcommands #{"run" "ask" "agents" "models" "config" "sessions" "projects" "memory" "events" "a2a"})
(def ^:private help-flags #{"--help" "-?" "-h"})
;; `-v` is taken by `run --verbose`, so the short version flag is capital `-V`.
(def ^:private version-flags #{"--version" "-V"})

(defn- inject-bare-resume-sentinel
  "cli-matic treats `--resume` as a required-value option, so a bare
   `--resume`/`-r` (= resume the latest session) would fail to parse.  Detect
   that shape — a `-r`/`--resume` token whose successor is missing or is itself
   a flag — and splice the sentinel value in right after it.  Leaves
   `--resume=<id>` and `--resume <id>` untouched."
  [args]
  (let [v (vec args)]
    (reduce
     (fn [acc i]
       (let [tok (nth v i)
             nxt (get v (inc i))]
         (if (and (#{"-r" "--resume"} tok)
                  (or (nil? nxt) (str/starts-with? nxt "-")))
           (conj acc tok resume-pick-sentinel)
           (conj acc tok))))
     []
     (range (count v)))))

(declare -dispatch)

(defn -main [& args]
  ;; `--version`/`-V` is a global flag: short-circuit before dotenv loading and
  ;; subcommand dispatch so it prints just the version (no `[dotenv]` noise) and
  ;; never gets rerouted into the default `run` subcommand.
  (if (contains? version-flags (first args))
    (println (str "by " app-version-long))
    (-dispatch args)))

(defn- -dispatch [args]
  ;; Bridge project-local `.env` into JVM properties so the native `by`
  ;; binary picks up keys without the `bb` shell wrapper. Real env vars take
  ;; precedence; see dotenv.clj for resolution order.
  (let [{:keys [paths loaded-count]} (dotenv/load-from-dotenv!)]
    (when (pos? loaded-count)
      ;; Diagnostic banner → stderr, so stdout stays clean for piping
      ;; (`by ask`, `--json`, etc.). (Was a no-op `*err* *err*` binding that
      ;; left it on stdout.)
      (binding [*out* *err*]
        (println (format "[dotenv] loaded %d key(s) from %s"
                         loaded-count
                         (str/join ", " (map :path paths)))))))
  ;; The app log belongs to the PROCESS, not to a subcommand. It used to be
  ;; started inside `cmd-ask` and `with-memory-manager` only, so every other
  ;; path ran unlogged — `by a2a serve` emitted `::server-started` (carrying
  ;; the node id) and every request event into a publisher that did not
  ;; exist, which is why a running server had no audit trail at all.
  ;;
  ;; Started AFTER dotenv so a `.env` is in effect first, and paired with a
  ;; shutdown-hook flush because the publisher is async (see
  ;; `install-log-flush-hook!`). Both calls are idempotent, so the paths that
  ;; still set up and tear down explicitly are unaffected.
  ;;
  ;; Deliberately NOT in `-main`: `--version` short-circuits before this and
  ;; should stay a bare println, touching no dirs and writing no log.
  ;;
  ;; pid FIRST, so every event the log goes on to publish carries the process
  ;; that emitted it. Several `by` processes share one app log, and until this
  ;; there was no way to tell their events apart. Note the gap: events emitted
  ;; while namespaces LOAD (side-effecting requires such as
  ;; `common/a2a.clj`'s `install-stream-hooks!`) happen before `-main` runs at
  ;; all, so they carry no pid. Closing that would mean a load-time side
  ;; effect, which native-image would evaluate at BUILD time and freeze.
  (mulog/install-process-context!)
  (setup-app-log!)
  (install-log-flush-hook!)
  ;; The SLF4J bridge is hoisted for the same reason and in the same place —
  ;; library warnings are a property of the process too, and capturing them
  ;; only on the two paths that used to set it up meant an AWS or HTTP
  ;; warning raised by any other command went to logback's DEFAULT console
  ;; appender (there is no logback.xml) instead of the app log.
  ;;
  ;; AFTER `setup-app-log!`, so the forwarded events have a publisher to
  ;; land in. It detaches the console appender rather than adding alongside
  ;; it: leaving it attached would print library output onto the TUI's
  ;; rendered screen. Idempotent, so this is the only call site that has to
  ;; exist.
  (mulog/setup-slf4j-bridge!)
  (let [first-arg (first args)
        ;; Default to "run" when no subcommand given or when first arg is a flag
        args (cond
               ;; No args → run
               (nil? first-arg)
               (cons "run" args)

               ;; Known subcommand → pass through
               (contains? known-subcommands first-arg)
               args

               ;; Help flags → pass through to cli-matic top-level
               (contains? help-flags first-arg)
               args

               ;; Other flags (starts with -) → prepend run
               (str/starts-with? first-arg "-")
               (cons "run" args)

               ;; Bare agent-id (e.g. `bb tui coact-agent`) → run with positional
               :else
               (cons "run" args))]
    (cli/run-cmd (inject-bare-resume-sentinel args) cli-config)))
