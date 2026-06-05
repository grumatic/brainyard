;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.os-sandbox.core
  "Contain a Brainyard TUI session in a macOS sandbox by wrapping `by run` in
   `sandbox-exec` (seatbelt).

   sandbox-exec is the macOS userspace front-end to the kernel sandbox: it
   reads an SBPL (Scheme-like) profile and execs a child command under it.
   Because seatbelt mediates only NEW syscalls and leaves inherited file
   descriptors untouched, the child runs in the SAME terminal (unlike `--web`,
   which serves a PTY over the network) — the TUI's raw mode, alt-screen and
   SIGWINCH all work. `--sandbox` is purely a launcher.

   The launcher resolves how to relaunch itself (`self-exec-argv`), generates a
   parameterized profile (`build-profile-string`, pure + unit-tested), builds
   the sandbox-exec command (`build-sandbox-argv`, pure), and spawns it
   (`serve!`). The spawned child carries `BY_SANDBOX_CHILD=1` so the re-entered
   process runs the TUI instead of recursing into another sandbox-exec.

   Default policy is WRITE-CONTAINMENT: reads, network and subprocess exec are
   all allowed (an agent's whole job is running tools and calling LLMs), but
   filesystem WRITES are confined to an allowlist (~/.brainyard, the project /
   cwd subtree, $TMPDIR, /tmp, ~/Library/Caches, /dev). This stops an agent —
   or a tool it runs — from clobbering ~/.ssh, ~/.aws/credentials, /etc, or
   unrelated repos, without breaking LLM calls or binary loading.

   sandbox-exec is Apple-DEPRECATED but still shipping and relied on by Apple's
   own daemons, Chrome and OpenAI's Codex CLI. The component name is os-sandbox
   (not seatbelt) so a future Linux backend (bubblewrap/landlock) can slot in
   behind the same interface, and to avoid confusion with clj-sandbox (the
   in-process SCI code-eval layer, a different concern)."
  (:require [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.util.concurrent TimeUnit]))

(def ^:private sandbox-exec-path
  "Fixed path to the macOS sandbox-exec binary (not resolved via PATH so a
   shadowing binary can't redirect the launcher)."
  "/usr/bin/sandbox-exec")

;; ============================================================================
;; Availability probe
;; ============================================================================

(defn- which
  "Find an executable on PATH via `which`. Returns path string or nil."
  [name]
  (try
    (let [proc (.start (ProcessBuilder. ^"[Ljava.lang.String;"
                        (into-array String ["which" name])))
          out  (str/trim (slurp (.getInputStream proc)))
          exit (.waitFor proc)]
      (when (and (zero? exit) (not (str/blank? out)))
        out))
    (catch Exception _ nil)))

(defn macos?
  "True when running on macOS (the only platform seatbelt supports)."
  []
  (str/starts-with? (str/lower-case (or (System/getProperty "os.name") "")) "mac"))

(defn available?
  "Probe for sandbox-exec support.
   Returns {:ok? true :path str} on macOS with an executable sandbox-exec,
   else {:ok? false :reason str}."
  []
  (cond
    (not (macos?))
    {:ok? false :reason "--sandbox is macOS-only (uses sandbox-exec / seatbelt)."}

    (not (.canExecute (io/file sandbox-exec-path)))
    {:ok? false
     :reason (str sandbox-exec-path " not found or not executable; cannot sandbox.")}

    :else
    {:ok? true :path sandbox-exec-path}))

;; ============================================================================
;; Self-exec resolution — how sandbox-exec should relaunch the TUI
;; ============================================================================

(defn- native-image?
  "True when running inside a GraalVM native image (the shipping `by` binary)."
  []
  (some? (System/getProperty "org.graalvm.nativeimage.imagecode")))

(defn- current-process-command
  "Absolute path to the current process's executable, or nil. For the native
   binary this is the `by` path; on the JVM it is the `java` launcher path."
  []
  (try
    (-> (java.lang.ProcessHandle/current) (.info) (.command) (.orElse nil))
    (catch Exception _ nil)))

(defn self-exec-argv
  "Resolve the argv prefix sandbox-exec should use to relaunch the TUI.

   Resolution order:
     1. `override-var` env override (whitespace-split) — escape hatch for dev/jar.
     2. Native image → the current executable path (the `by` binary).
     3. `which by` on PATH → that path.
   Returns {:ok? true :argv [str ...]} or {:ok? false :reason str}.

   `env` is a map of env-var name → value (defaults to the real environment);
   injected in tests to exercise each branch deterministically. `override-var`
   is the env var consulted for the explicit override (e.g. \"BY_SANDBOX_SELF\")."
  ([] (self-exec-argv (System/getenv) "BY_SANDBOX_SELF"))
  ([env override-var]
   (let [override (get env override-var)]
     (cond
       (and override (not (str/blank? override)))
       {:ok? true :argv (vec (str/split (str/trim override) #"\s+"))}

       (native-image?)
       (if-let [cmd (current-process-command)]
         {:ok? true :argv [cmd]}
         {:ok? false :reason (str "could not resolve the native binary path; set " override-var)})

       :else
       (if-let [by (which "by")]
         {:ok? true :argv [by]}
         {:ok? false
          :reason (str "could not resolve how to relaunch the TUI. "
                       "Set " override-var " to the command, e.g. "
                       override-var "='bb tui' or " override-var "=/path/to/by")})))))

;; ============================================================================
;; Writable-roots parsing
;; ============================================================================

(defn- strip-trailing-slash
  "Drop trailing slashes (SBPL `subpath` wants a canonical, slash-free root),
   but never reduce \"/\" to the empty string."
  [s]
  (let [t (str/replace s #"/+$" "")]
    (if (str/blank? t) "/" t)))

(defn parse-allow-writes
  "Normalize the `--sandbox-allow-write` value(s) into a deduped vec of absolute
   `subpath` roots. Accepts a single string (comma-separated, e.g. from the env
   var) OR a vector (cli-matic `:multiple`). Tilde expands against `home`,
   relative paths resolve against `cwd`, blanks are dropped, trailing slashes
   trimmed."
  [raw cwd home]
  (let [items (cond (nil? raw)        []
                    (sequential? raw) raw
                    :else             [raw])]
    (->> items
         (mapcat #(str/split (str %) #","))
         (map str/trim)
         (remove str/blank?)
         (map (fn [p]
                (cond
                  (= p "~")              home
                  (str/starts-with? p "~/") (str home (subs p 1))
                  (str/starts-with? p "/")  p
                  :else                  (.getPath (io/file cwd p)))))
         (map strip-trailing-slash)
         distinct
         vec)))

;; ============================================================================
;; Profile construction (pure) — the unit-test surface
;; ============================================================================

(defn- sb-str
  "Quote a string as an SBPL literal: wrap in double quotes, escaping
   backslashes and embedded quotes so paths with spaces/quotes are safe."
  [s]
  (str \" (-> (str s) (str/replace "\\" "\\\\") (str/replace "\"" "\\\"")) \"))

(defn- write-rule
  "An `(allow file-write* (subpath \"…\"))` line for a canonical root."
  [root]
  (str "(allow file-write* (subpath " (sb-str (strip-trailing-slash root)) "))"))

(defn build-profile-string
  "Build the full SBPL profile text (the `-p` payload). Pure — the primary
   unit-test surface.

   opts:
     :home         absolute home dir (REQUIRED)
     :cwd          absolute working dir (REQUIRED)
     :project-dir  absolute project root (defaults to :cwd)
     :tmpdir       per-user temp dir (defaults to /tmp)
     :network?     when true, emits `(allow network*)`; otherwise network falls
                   through to `(deny default)` (default true)
     :extra-writes seq of extra absolute writable roots (from parse-allow-writes)

   Philosophy: write-containment. Deny by default, then re-allow the broad,
   harmless capabilities (read, exec, fork, network) and tightly scope WRITES."
  [{:keys [home cwd project-dir tmpdir network? extra-writes]
    :or   {network? true}}]
  (let [project-dir (or project-dir cwd)
        tmpdir      (or tmpdir "/tmp")
        ;; Canonical write roots. macOS resolves /tmp→/private/tmp and
        ;; $TMPDIR→/private/var/folders before matching, so allow BOTH the
        ;; symlinked and canonical forms — the #1 cause of "works in dev,
        ;; denies in prod" sandbox bugs.
        write-roots (concat [tmpdir
                             "/private/var/folders"
                             "/var/folders"
                             "/tmp"
                             "/private/tmp"
                             (str home "/.brainyard")
                             (str home "/Library/Caches")
                             cwd
                             project-dir]
                            (or extra-writes []))
        write-roots (->> write-roots (map strip-trailing-slash) distinct)]
    (str/join
     "\n"
     (concat
      ["(version 1)"
       ""
       ";; Write-containment: deny all, then re-allow read/exec/network broadly"
       ";; and confine WRITES to the workspace + caches + temp + devices."
       "(deny default)"
       ""
       ";; Process: the agent's job is running tools/code — bash, code-eval,"
       ";; git, which, ttyd, tmux all need exec + fork. Denying breaks the agent."
       "(allow process-exec*)"
       "(allow process-fork)"
       "(allow signal (target self))"
       ""
       ";; Reads: broad. Not the threat model (write/exfil-to-disk is). Needed to"
       ";; load the `by` binary + system dylibs, CA bundle, locale, zoneinfo,"
       ";; /dev/urandom, the cwd/.env/.git walks, and config dirs."
       "(allow file-read*)"
       "(allow file-read-metadata)"
       ""
       ";; Devices: the TUI + JVM need these. file-ioctl is REQUIRED or tcsetattr"
       ";; (raw mode) on the inherited TTY fails and the TUI renders wrong."
       "(allow file-write* (subpath \"/dev\"))"
       "(allow file-ioctl (subpath \"/dev\"))"
       "(allow sysctl-read)"
       "(allow mach-lookup)"
       ""
       ";; POSIX named semaphores: the GraalVM native-image runtime opens one at"
       ";; startup for signal dispatch (CSunMiscSignal — macOS has no unnamed"
       ";; sem_init, so it uses sem_open). Without this `by` dies immediately with"
       ";; \"CSunMiscSignal.open() failed errno 1 Operation not permitted\"."
       "(allow ipc-posix-sem*)"
       ""]
      (if network?
        [";; Network: all-or-nothing in seatbelt (no per-host). Allows LLM HTTPS"
         ";; (Anthropic/OpenAI/Bedrock/Google/Groq), Tavily, Ollama localhost,"
         ";; MCP HTTP, ttyd, nREPL. Omitted entirely under --sandbox-no-network."
         "(allow network*)"
         ""]
        [";; Network DENIED (--sandbox-no-network): falls through to deny default."
         ""])
      [";; WRITE ALLOWLIST — the actual containment."]
      (map write-rule write-roots)
      [""]))))

;; ============================================================================
;; sandbox-exec argv construction (pure)
;; ============================================================================

(defn build-sandbox-argv
  "Build the full sandbox-exec command vector. Pure.

   opts:
     :profile-path    path to a custom .sb profile — uses `-f` and passes the
                      `-D` params so the custom profile can reference them.
     :profile-string  generated SBPL — uses `-p` (only when :profile-path nil).
     :params          {:home :cwd :project-dir :tmpdir} → `-D KEY=val` (only
                      emitted for the custom-profile path; the generated profile
                      bakes literal paths so it needs no params).
     :child-argv      REQUIRED — the relaunch command (from self-exec-argv).

   sandbox-exec uses getopt: the first non-option token (the absolute `by`
   path) terminates option parsing, so the child's own flags that follow are
   passed through untouched — no `--` separator needed."
  [{:keys [profile-path profile-string params child-argv]}]
  (vec
   (concat
    [sandbox-exec-path]
    (if profile-path
      (concat ["-f" profile-path]
              (mapcat (fn [[k v]] ["-D" (str (name k) "=" v)])
                      (filter (comp some? val)
                              {:HOME (:home params)
                               :CWD (:cwd params)
                               :PROJECT_DIR (:project-dir params)
                               :TMPDIR (:tmpdir params)})))
      ["-p" profile-string])
    child-argv)))

;; ============================================================================
;; Spawn
;; ============================================================================

(defn- destroy-proc!
  "Terminate `proc`: SIGTERM, then SIGKILL after a short grace period.
   The `(long 3)` hint forces the timed `waitFor` overload — without it
   native-image strips the reflective dispatch (see web-share core.clj)."
  [^Process proc]
  (try
    (.destroy proc)
    (when-not (.waitFor proc (long 3) TimeUnit/SECONDS)
      (.destroyForcibly proc))
    (catch Exception _ nil)))

(defn- put-child-env!
  "Apply `child-env` over the ProcessBuilder env and force BY_SANDBOX_CHILD=1
   (the re-entrancy guard that stops the relaunched TUI from spawning another
   sandbox-exec)."
  [^ProcessBuilder pb child-env]
  (let [env (.environment pb)]
    (doseq [[k v] child-env] (.put env (str k) (str v)))
    (.put env "BY_SANDBOX_CHILD" "1")))

(defn serve!
  "Spawn the sandboxed TUI in the current terminal and return a handle.

   opts (build-sandbox-argv keys) plus:
     :child-env  map of extra env vars for the child (merged over inherited);
                 `BY_SANDBOX_CHILD` is always forced to \"1\".

   Returns {:proc Process :argv [str ...] :stop (fn [])}.
   `stop` destroys the process. Throws if :child-argv is empty."
  [{:keys [child-argv child-env] :as opts}]
  (when (empty? child-argv)
    (throw (ex-info "serve! requires non-empty :child-argv" {:opts opts})))
  (let [argv (build-sandbox-argv opts)
        pb   (doto (ProcessBuilder. ^java.util.List argv)
               (.inheritIO))
        _    (put-child-env! pb child-env)
        proc (.start pb)]
    {:proc proc
     :argv argv
     :stop (fn [] (destroy-proc! proc))}))
