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
   [ai.brainyard.agent-tui.helpers :as helpers]
   [ai.brainyard.agent-tui.mode :as mode]
   [ai.brainyard.agent-tui.log :as tui-log]
   [ai.brainyard.agent-tui.config-wizard :as config-wizard]
   [ai.brainyard.agent-tui-app.dotenv :as dotenv]
   [ai.brainyard.agent.interface :as agent]
   [ai.brainyard.agent-tui-persist.interface :as persist]
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
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.string :as str]))

;; ============================================================================
;; Shared option definitions
;; ============================================================================

(def app-version
  "Single source of truth for the build's version string.  Surfaced both in
   the cli-matic config (`:version`) and in the daemon's status snapshot
   (chrome row of the status pane).

   Value is baked at build time from `git describe --tags --always --dirty`
   by `bb version:ata` (see bb.edn), which writes `resources/build-version.edn`
   before `compile:ata` runs. Falls back to `\"dev\"` when the resource is
   missing — e.g. running from a fresh REPL before any build, or from a
   clean source tarball with no `.git/`."
  (or (some-> (io/resource "build-version.edn")
              slurp
              edn/read-string
              :version)
      "dev"))

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
  "Stop the mulog file publisher.  Paired with `setup-app-log!`."
  []
  (try (tui-log/stop-file-publisher!) (catch Exception _)))

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

(declare format-bytes format-age-millis)

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
  (let [summaries (->> (persist/summarise-sessions)
                       (filter #(contains? existing-ids (:session-id %)))
                       (sort-by (fn [s]
                                  (- 0 (long (or (:last-attached-at s)
                                                 (:started-at s)
                                                 0))))))
        n (count summaries)]
    (when (pos? n)
      (println)
      (println (str n " persisted session(s) — pick one to resume, or [N] for a new session:"))
      (println (apply str (repeat 72 \-)))
      (println (format " %3s  %-30s %-14s %-18s %-10s %s"
                       "#" "session-id" "label" "agent" "size" "last"))
      (println (apply str (repeat 88 \-)))
      (doseq [[i s] (map-indexed vector summaries)]
        (println (format " %3d  %-30s %-14s %-18s %-10s %s"
                         (inc i)
                         (:session-id s)
                         (or (:label s) "-")
                         (or (some-> (:defagent-id s) name)
                             (some-> (:agent-id s) name)
                             "-")
                         (format-bytes (or (:bytes s) 0))
                         (or (format-age-millis (or (:last-attached-at s)
                                                    (:started-at s)))
                             "-"))))
      (println)
      (print "Choice [1-" n "] / (N)ew: ")
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
              (:session-id (nth summaries (dec i))))))))))

;; ============================================================================
;; Subcommand: run — interactive TUI
;; ============================================================================

(defn- run-tui!
  "Start the interactive TUI agent session in this process.
   Config precedence: CLI flags > config.edn > hardcoded defaults.
   `opts` is assumed already normalized by `parse-legacy-provider`."
  [opts]
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

        inline? (:inline opts)
        verbose? (:verbose opts)
        max-iter (:max-iterations opts)

        ;; Resume wiring.  Default (no flags) starts a fresh session.
        ;;   --resume <id>   → resume that session (error+exit if absent)
        ;;   --resume (bare) → interactive picker (fresh if none)
        ;;                     (-main rewrites bare --resume to the sentinel)
        ;; --new is a deprecated no-op (fresh is already the default).
        resume-arg (:resume opts)
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

          :else
          [nil false])

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

        run-args (cond-> [:agent-id agent-id
                          :lm-provider provider
                          :mode (:mode probe)]
                   model      (into [:lm-model model])
                   inline?    (into [:inline true])
                   verbose?   (into [:verbosity :verbose])
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
    (into ["-r" (:resume opts)])))

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
    (:inline opts)         (conj "-i")
    (:verbose opts)        (conj "-v")
    (:max-iterations opts) (into ["-n" (str (:max-iterations opts))])
    (:with-tmux opts)      (conj "--with-tmux")
    ;; See web-child-argv: forward bare `-r` for the picker sentinel, never
    ;; its `--`-prefixed value (which the child would mis-parse as a flag).
    (= (:resume opts) resume-pick-sentinel) (conj "-r")
    (and (:resume opts)
         (not= (:resume opts) resume-pick-sentinel))
    (into ["-r" (:resume opts)])))

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
  "Install the `--working-dir`/`-C` override before any agent/tool boots, then
   pin the project-scoped sessions root so persistence lands under
   `<project>/.brainyard/sessions/` rather than the user-global dir.

   The flag is strict: a non-existent / non-directory path exits 1. A nil/blank
   flag clears the override, so resolution falls back to `BY_WORKING_DIR` env
   then `user.dir`. Installing here (the parent) also validates the path early,
   before a --web/--sandbox launcher spawns its child.

   The sessions-root injection MUST follow the working-dir override (so
   `project-dir` is correct) and precede any persistence read (resume picker,
   `run-tui!`, sessions list). `agent/sessions-root` honors `-C` / `BY_PROJECT_DIR`;
   `persist/set-root!` installs it process-wide via `alter-var-root`, so the
   host, control-server threads, and GC sweeps all observe it. Re-exec'd
   --web/--sandbox children re-enter through this path with `-C` forwarded, so
   they pin the same root."
  [opts]
  (try (agent/set-working-dir-override! (:working-dir opts))
       (catch clojure.lang.ExceptionInfo e
         (exit-err! (str "Error: " (.getMessage e)))))
  (persist/set-root! (agent/sessions-root)))

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

(defn cmd-ask
  "Ask a one-shot question and print the answer.
   Config precedence: CLI flags > config.edn > hardcoded defaults."
  [opts]
  (let [opts (parse-legacy-provider opts)
        _ (install-working-dir! opts)
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
        max-iter (:max-iterations opts)]
    (when (or (nil? question) (str/blank? question))
      (println "Error: question argument is required.")
      (println "Usage: by ask [options] QUESTION")
      (System/exit 1))

    ;; Suppress JUL cookie warnings
    (helpers/suppress-jul-cookie-warnings!)

    ;; Setup LM
    (helpers/setup-lm! provider :model model)

    ;; Setup mulog (slf4j bridge + file publisher) — without setup-app-log!
    ;; the per-iteration trace events from one-shot ask runs never reach
    ;; /tmp/agent-tui-app.log, so post-mortems are blind. setup-app-log!
    ;; is idempotent (defonce-protected).
    (mulog/setup-slf4j-bridge!)
    (setup-app-log!)

    (let [sess-id (str "ask-" (System/currentTimeMillis))
          max-iterations (or max-iter
                             (:max-iterations (:meta (agent/get-tool-defs :id agent-id)))
                             (get agent/default-config :max-iterations 20))
          ag (agent/setup-agent-by-id agent-id
                                      :agent-session {:user-id (helpers/resolve-user-id (:user-id opts))
                                                      :session-id sess-id}
                                      :max-iterations max-iterations)]
      (try
        (let [result (agent/ask ag question)]
          (if (:error result)
            (do (println (str "Error: " (:error result)))
                (teardown-app-log!)
                (System/exit 1))
            (do (println (or (:answer result) (:result result) ""))
                (teardown-app-log!)
                (System/exit 0))))
        (catch Exception e
          (println (str "Error: " (.getMessage e)))
          (teardown-app-log!)
          (System/exit 1))
        (finally
          (.close ^java.io.Closeable ag))))))

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
  "List available agents."
  [_opts]
  (let [agent-defs (agent/get-tool-defs :type :agent)]
    (if (empty? agent-defs)
      (println "No agents registered.")
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

(defn cmd-models
  "List available LLM models (provider/model) from the curated popular-models
   catalog in clj-llm. Optionally filter to a single provider with -p/--provider."
  [opts]
  (let [models (clj-llm/get-popular-models)
        provider-filter (some-> (:provider opts) keyword)]
    (if (empty? models)
      (println "No models registered.")
      (let [n (format-models-table models provider-filter)]
        (if (and provider-filter (zero? n))
          (println (str "No models found for provider: " (name provider-filter)))
          (println (str n " model(s) listed."
                        (when provider-filter
                          (str " (filtered to " (name provider-filter) ")")))))))))

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

(defn- format-bytes [n]
  (cond
    (< n 1024)            (str n " B")
    (< n (* 1024 1024))   (format "%.1f KB" (/ (double n) 1024))
    :else                 (format "%.1f MB" (/ (double n) 1024 1024))))

(defn- format-age-millis [ms]
  (when ms
    (let [age (- (System/currentTimeMillis) (long ms))
          mins (quot age 60000)
          hrs  (quot mins 60)
          days (quot hrs 24)]
      (cond
        (> days 1) (str days "d ago")
        (> hrs 1)  (str hrs "h ago")
        (> mins 1) (str mins "m ago")
        :else      "just now"))))

(defn cmd-sessions-list
  "Print a summary of every persisted agent session (project-scoped)."
  [opts]
  ;; Pin the project-scoped sessions root (from cwd's project-dir) before reading.
  (install-working-dir! opts)
  (let [sessions (persist/summarise-sessions)]
    (if (empty? sessions)
      (println "No persisted sessions.")
      (do
        (println (format "%-30s %-14s %-18s %-10s %s"
                         "session-id" "label" "agent" "size" "last-attached"))
        (println (apply str (repeat 88 \-)))
        (doseq [{:keys [session-id label defagent-id agent-id bytes
                        last-attached-at started-at]} sessions]
          (println (format "%-30s %-14s %-18s %-10s %s"
                           session-id
                           (or label "-")
                           (or (some-> defagent-id name)
                               (some-> agent-id name)
                               "-")
                           (format-bytes (or bytes 0))
                           (or (format-age-millis (or last-attached-at started-at))
                               "-"))))))))

(defn- pick-session-to-prune!
  "Show a numbered list of persisted sessions (newest first) and prompt the
   user to pick one to delete. Returns the chosen session-id, or nil for
   cancel."
  []
  (let [summaries (->> (persist/summarise-sessions)
                       (sort-by (fn [s]
                                  (- 0 (long (or (:last-attached-at s)
                                                 (:started-at s)
                                                 0))))))
        n (count summaries)]
    (when (pos? n)
      (println)
      (println (str n " persisted session(s) — pick one to prune, or (C)ancel:"))
      (println (apply str (repeat 88 \-)))
      (println (format " %3s  %-30s %-14s %-18s %-10s %s"
                       "#" "session-id" "label" "agent" "size" "last"))
      (println (apply str (repeat 88 \-)))
      (doseq [[i s] (map-indexed vector summaries)]
        (println (format " %3d  %-30s %-14s %-18s %-10s %s"
                         (inc i)
                         (:session-id s)
                         (or (:label s) "-")
                         (or (some-> (:defagent-id s) name)
                             (some-> (:agent-id s) name)
                             "-")
                         (format-bytes (or (:bytes s) 0))
                         (or (format-age-millis (or (:last-attached-at s)
                                                    (:started-at s)))
                             "-"))))
      (println)
      (print "Choice [1-" n "] / (C)ancel: ")
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
              (:session-id (nth summaries (dec i))))))))))

(defn cmd-sessions-prune
  "Delete a persisted session's directory. When no session-id is given,
   shows an interactive picker so the user can choose one (like the
   first-start session picker)."
  [opts]
  ;; Pin the project-scoped sessions root (from cwd's project-dir) before reading.
  (install-working-dir! opts)
  (let [arg (or (first (:_arguments opts)) (:session-id opts))
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
      (println (str "Session not found: " target)))))

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
                                user-id-opt
                                working-dir-opt
                                {:option "inline" :short "i" :as "Inline mode (no alt screen)"
                                 :type :with-flag :default false}
                                {:option "verbose" :short "v" :as "Verbose output"
                                 :type :with-flag :default false}
                                {:option "with-tmux"
                                 :as "Require tmux side panes / popups (exit 1 if not in a tmux session)"
                                 :type :with-flag :default false}
                                max-iter-opt
                                {:option "resume" :short "r"
                                 :as "Resume a persisted session: bare = pick from a menu; --resume <id> = that session"
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
                  :description "Ask a one-shot question (non-interactive)"
                  :opts        [agent-opt
                                provider-opt
                                model-opt
                                user-id-opt
                                working-dir-opt
                                max-iter-opt]
                  :args        [{:arg "question" :as "Question to ask" :type :string}]
                  :runs        cmd-ask}
                 {:command     "agents"
                  :description "List available agents"
                  :runs        cmd-agents}
                 {:command     "models"
                  :description "List available LLM models (provider/model)"
                  :opts        [{:option "provider" :short "p"
                                 :as "Filter to a single provider (e.g. anthropic, openai, bedrock)"
                                 :type :string}]
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
                  :description "List or prune persisted agent sessions"
                  :subcommands [{:command     "list"
                                 :description "List all persisted sessions"
                                 :runs        cmd-sessions-list}
                                {:command     "prune"
                                 :description "Delete a persisted session"
                                 :opts        [{:option "session-id" :short "s"
                                                :as "Session ID" :type :string}]
                                 :runs        cmd-sessions-prune}]}]})

;; ============================================================================
;; Entry point
;; ============================================================================

(def ^:private known-subcommands #{"run" "ask" "agents" "models" "config" "sessions"})
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
    (println (str "by " app-version))
    (-dispatch args)))

(defn- -dispatch [args]
  ;; Bridge project-local `.env` into JVM properties so the native `by`
  ;; binary picks up keys without the `bb` shell wrapper. Real env vars take
  ;; precedence; see dotenv.clj for resolution order.
  (let [{:keys [paths loaded-count]} (dotenv/load-from-dotenv!)]
    (when (pos? loaded-count)
      (binding [*err* *err*]
        (println (format "[dotenv] loaded %d key(s) from %s"
                         loaded-count
                         (str/join ", " (map :path paths)))))))
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
