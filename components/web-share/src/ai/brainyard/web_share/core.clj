;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.web-share.core
  "Share a Brainyard TUI session over the web by wrapping `by run` in ttyd
   (https://github.com/tsl0922/ttyd).

   ttyd is an external binary that spawns a child command inside a real PTY and
   bridges that PTY to a browser over WebSocket. Because the child gets a genuine
   PTY, the TUI's terminal assumptions (raw mode, /dev/tty, SIGWINCH, alt-screen)
   work unchanged inside it — `--web` is purely a launcher.

   The launcher resolves how to relaunch itself (`self-exec-argv`), builds the
   ttyd command (`build-ttyd-argv`, pure + unit-tested), and spawns it
   (`serve!`). The spawned child carries `BY_WEB_CHILD=1` so the re-entered
   process runs the TUI instead of recursing into another ttyd."
  (:require [clojure.string :as str])
  (:import [java.io BufferedReader InputStreamReader]
           [java.util.concurrent TimeUnit]))

;; ============================================================================
;; ttyd discovery
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

(defn- ttyd-version
  "Run `ttyd --version` and return the trimmed first line, or nil."
  [path]
  (try
    (let [proc (.start (doto (ProcessBuilder. ^"[Ljava.lang.String;"
                              (into-array String [path "--version"]))
                         (.redirectErrorStream true)))]
      (with-open [r (BufferedReader. (InputStreamReader. (.getInputStream proc)))]
        (let [line (first (line-seq r))]
          (.waitFor proc)
          (some-> line str/trim not-empty))))
    (catch Exception _ nil)))

(def ^:private install-hint
  (str "ttyd is not on PATH. Install it, then retry:\n"
       "  macOS:  brew install ttyd\n"
       "  Debian: sudo apt install ttyd\n"
       "  other:  https://github.com/tsl0922/ttyd#installation"))

(defn available?
  "Probe for the ttyd binary on PATH.
   Returns {:ok? true :path str :version str-or-nil}
        or {:ok? false :hint str}."
  []
  (if-let [path (which "ttyd")]
    {:ok? true :path path :version (ttyd-version path)}
    {:ok? false :hint install-hint}))

;; ============================================================================
;; Self-exec resolution — how ttyd should relaunch the TUI
;; ============================================================================

(defn- native-image?
  "True when running inside a GraalVM native image (the shipping `by` binary)."
  []
  (some? (System/getProperty "org.graalvm.nativeimage.imagecode")))

(defn- current-process-command
  "Absolute path to the current process's executable, or nil.
   For the native binary this is the `by` path; on the JVM it is the `java`
   launcher path."
  []
  (try
    (-> (java.lang.ProcessHandle/current) (.info) (.command) (.orElse nil))
    (catch Exception _ nil)))

(defn self-exec-argv
  "Resolve the argv prefix ttyd should use to relaunch the TUI.

   Resolution order:
     1. BY_WEB_SELF env override (whitespace-split) — escape hatch for dev/jar.
     2. Native image → the current executable path (the `by` binary).
     3. `which by` on PATH → that path.
   Returns {:ok? true :argv [str ...]} or {:ok? false :reason str}.

   `env` is a map of env-var name → value (defaults to the real environment);
   injected in tests to exercise each branch deterministically."
  ([] (self-exec-argv (System/getenv)))
  ([env]
   (let [override (get env "BY_WEB_SELF")]
     (cond
       (and override (not (str/blank? override)))
       {:ok? true :argv (vec (str/split (str/trim override) #"\s+"))}

       (native-image?)
       (if-let [cmd (current-process-command)]
         {:ok? true :argv [cmd]}
         {:ok? false :reason "could not resolve the native binary path; set BY_WEB_SELF"})

       :else
       (if-let [by (which "by")]
         {:ok? true :argv [by]}
         {:ok? false
          :reason (str "could not resolve how to relaunch the TUI. "
                       "Set BY_WEB_SELF to the command, e.g. "
                       "BY_WEB_SELF='bb tui' or BY_WEB_SELF=/path/to/by")})))))

;; ============================================================================
;; Credentials
;; ============================================================================

(defn gen-password
  "Generate a short random password (12 hex chars)."
  []
  (subs (str/replace (str (java.util.UUID/randomUUID)) "-" "") 0 12))

(defn resolve-credential
  "Resolve the ttyd basic-auth credential `user:pass`.
   Auth is mandatory: a password is auto-generated when none is supplied.
   Returns {:user str :pass str :credential str :generated? bool}."
  [{:keys [user pass]}]
  (let [user (or (not-empty (some-> user str/trim)) "by")
        generated? (empty? (some-> pass str/trim not-empty))
        pass (if generated? (gen-password) (str/trim pass))]
    {:user user
     :pass pass
     :credential (str user ":" pass)
     :generated? generated?}))

;; ============================================================================
;; ttyd argv construction (pure)
;; ============================================================================

(defn build-ttyd-argv
  "Build the full ttyd command vector. Pure — the unit-test surface.

   opts:
     :ttyd-path    path to the ttyd binary            (default \"ttyd\")
     :bind         interface/address to bind          (default \"127.0.0.1\")
     :port         listen port, 0 = random            (default 7681)
     :credential   basic-auth \"user:pass\" (required) — omitted only if nil
     :writable?    allow client keystrokes            (default true)
     :max-clients  connection cap, 0 = unlimited (omitted when 0)
     :once?        exit after the first client disconnects
     :child-argv   the command ttyd runs (e.g. [\"by\" \"run\" \"-a\" \"coder\"])

   Always sets `-O` (check-origin) to mitigate cross-site WebSocket hijacking."
  [{:keys [ttyd-path bind port credential writable? max-clients once? child-argv]
    :or   {ttyd-path "ttyd" bind "127.0.0.1" port 7681 writable? true max-clients 0}}]
  (-> [ttyd-path
       "-i" (str bind)
       "-p" (str port)
       "-O"]
      (cond-> credential          (conj "-c" credential)
              writable?           (conj "-W")
              (pos? (long (or max-clients 0))) (conj "-m" (str max-clients))
              once?               (conj "-o"))
      (conj "--")
      (into child-argv)))

;; ============================================================================
;; Spawn
;; ============================================================================

(defn- display-host
  "Host to print in the URL. ttyd binds the given interface; 0.0.0.0 is shown
   as-is so the user knows it is reachable beyond localhost."
  [bind]
  (if (str/blank? bind) "127.0.0.1" bind))

(defn- destroy-proc!
  "Terminate `proc`: SIGTERM, then SIGKILL after a short grace period.
   The `(long 3)` hint forces the timed `waitFor` overload — without it
   native-image strips the reflective dispatch (see agent-tui mode.clj)."
  [^Process proc]
  (try
    (.destroy proc)
    (when-not (.waitFor proc (long 3) TimeUnit/SECONDS)
      (.destroyForcibly proc))
    (catch Exception _ nil)))

(defn- kill-pid!
  "SIGTERM the process `pid` via ProcessHandle, escalating to SIGKILL if it
   doesn't exit within ~1.5s. No-op if pid is nil or already gone.

   Crucially this spawns NO subprocess — unlike shelling out to
   `tmux kill-server`, it is safe to call from a JVM shutdown hook. During
   Ctrl-C the whole process group is signalled, and a `ProcessBuilder.start`
   from the hook fails (its jspawnhelper fork is killed mid-exec); a direct
   ProcessHandle kill avoids that path entirely."
  [pid]
  (when pid
    (try
      (let [opt (java.lang.ProcessHandle/of (long pid))]
        (when (.isPresent opt)
          (let [h (.get opt)]
            (.destroy h)
            (loop [n 30] (when (and (pos? n) (.isAlive h)) (Thread/sleep 50) (recur (dec n))))
            (when (.isAlive h) (.destroyForcibly h)))))
      (catch Exception _ nil))))

(defn- pid-alive?
  "True when a process with `pid` currently exists. Subprocess-free."
  [pid]
  (boolean (and pid (.isPresent (java.lang.ProcessHandle/of (long pid))))))

(defn- put-child-env!
  "Apply `child-env` over the ProcessBuilder env and force BY_WEB_CHILD=1
   (the re-entrancy guard that stops the relaunched TUI from spawning ttyd)."
  [^ProcessBuilder pb child-env]
  (let [env (.environment pb)]
    (doseq [[k v] child-env] (.put env (str k) (str v)))
    (.put env "BY_WEB_CHILD" "1")))

(defn serve!
  "Spawn ttyd wrapping the TUI and return a handle (Tier 1: a fresh session
   shared by all browser clients).

   opts (all from build-ttyd-argv) plus:
     :child-argv   REQUIRED — the relaunch command (from self-exec-argv).
     :child-env    map of extra env vars for the child (merged over inherited);
                   `BY_WEB_CHILD` is always forced to \"1\".

   Returns {:proc Process :url str :argv [str ...] :stop (fn [])}.
   `stop` destroys the process tree. Throws if :child-argv is empty."
  [{:keys [child-argv child-env bind port] :as opts}]
  (when (empty? child-argv)
    (throw (ex-info "serve! requires non-empty :child-argv" {:opts opts})))
  (let [argv (build-ttyd-argv opts)
        pb   (doto (ProcessBuilder. ^java.util.List argv)
               (.inheritIO))
        _    (put-child-env! pb child-env)
        proc (.start pb)]
    {:proc proc
     :argv argv
     :url  (str "http://" (display-host bind) ":" (or port 7681))
     :stop (fn [] (destroy-proc! proc))}))

;; ============================================================================
;; Tier 2 — tmux-backed live co-drive
;; ============================================================================
;;
;; The TUI runs inside a detached tmux session on a DEDICATED socket (`-L`), so
;; it never collides with the user's own tmux and the child inherits a clean,
;; controlled environment. ttyd attaches to that session (`tmux attach`), as can
;; the launcher's local terminal — every client drives one live pane. Because
;; the child sees $TMUX, it runs in tmux Mode B (side panes / popups render in
;; the shared session).

(defn- gen-suffix
  "Short random socket suffix so concurrent shares get isolated tmux servers."
  []
  (subs (str/replace (str (java.util.UUID/randomUUID)) "-" "") 0 8))

(defn- run-tmux!
  "Run a tmux subcommand on socket `sock`, returning {:exit :out}. `env` is an
   optional map applied to the process environment (used to seed the server)."
  [tmux sock args env]
  (let [pb (doto (ProcessBuilder. ^java.util.List (into [tmux "-L" sock] args))
             (.redirectErrorStream true))]
    (when (seq env) (put-child-env! pb env))
    (let [proc (.start pb)
          out  (slurp (.getInputStream proc))
          exit (.waitFor proc)]
      {:exit exit :out out})))

(defn tmux-available?
  "True when the tmux binary is on PATH."
  []
  (some? (which "tmux")))

(defn serve-tmux!
  "Tier 2: run the TUI inside a detached tmux session and serve it via ttyd so
   the local terminal and browsers co-drive one live process.

   opts (build-ttyd-argv keys) plus:
     :child-argv   REQUIRED — `<self> run …` (from self-exec-argv + flags).
     :child-env    extra env for the tmux server (BY_WEB_CHILD forced to \"1\").
     :session-suffix / :cols / :rows  — optional overrides.

   Returns {:proc(ttyd) :url :session :socket :tmux-path :attach-argv
            :log-file :alive? (fn) :stop (fn)}.
   Throws if tmux is absent or the session fails to start."
  [{:keys [child-argv child-env bind port session-suffix cols rows] :as opts}]
  (when (empty? child-argv)
    (throw (ex-info "serve-tmux! requires non-empty :child-argv" {:opts opts})))
  (let [tmux (or (which "tmux")
                 (throw (ex-info "tmux is not on PATH (required for --web-tmux)" {})))
        sock    (str "by-web-" (or session-suffix (gen-suffix)))
        session "brainyard"
        cols    (or cols 120)
        rows    (or rows 40)
        ;; Fresh server on a private socket → inherits this env (BY_WEB_CHILD=1).
        new-res (run-tmux! tmux sock
                           (into ["new-session" "-d" "-s" session
                                  "-x" (str cols) "-y" (str rows) "--"]
                                 child-argv)
                           child-env)]
    (when-not (zero? (:exit new-res))
      (throw (ex-info "tmux new-session failed"
                      {:exit (:exit new-res) :out (:out new-res) :socket sock})))
    (let [attach-argv [tmux "-L" sock "attach" "-t" session]
          ttyd-argv   (build-ttyd-argv (assoc opts :child-argv attach-argv))
          log-file    (str (System/getProperty "java.io.tmpdir")
                           "/by-web-ttyd-" sock ".log")
          ;; Capture the server PID and socket path up front. The PID lets us
          ;; tear the (daemonized) tmux server down with a direct signal — no
          ;; subprocess — so cleanup is safe in a shutdown hook; it also lets
          ;; the launcher cheaply detect when the session ends (e.g. /quit).
          ;; The socket path is unlinked on stop (stale sockets otherwise
          ;; accumulate in the tmpdir).
          info        (-> (run-tmux! tmux sock
                                     ["display-message" "-t" session "-p" "#{pid},#{socket_path}"] nil)
                          :out str/trim)
          [pid-str sock-path] (str/split info #"," 2)
          server-pid  (try (Long/parseLong (str/trim (or pid-str ""))) (catch Exception _ nil))
          sock-path   (not-empty (some-> sock-path str/trim))
          pb          (doto (ProcessBuilder. ^java.util.List ttyd-argv)
                        (.redirectErrorStream true)
                        (.redirectOutput (java.io.File. ^String log-file)))
          proc        (.start pb)]
      {:proc        proc
       :argv        ttyd-argv
       :url         (str "http://" (display-host bind) ":" (or port 7681))
       :session     session
       :socket      sock
       :socket-path sock-path
       :server-pid  server-pid
       :tmux-path   tmux
       :log-file    log-file
       :attach-argv attach-argv
       ;; Subprocess-free liveness: true while the tmux server process is up.
       ;; Goes false when the session ends (the agent /quit-s, last window
       ;; closes, server exits) — the launcher polls this to know when to reap.
       :alive?      (fn [] (pid-alive? server-pid))
       ;; Subprocess-free teardown (safe in a shutdown hook): signal the tmux
       ;; server directly, reap ttyd, unlink the socket file.
       :stop        (fn []
                      (kill-pid! server-pid)
                      (destroy-proc! proc)
                      (when sock-path
                        (try (.delete (java.io.File. ^String sock-path)) (catch Exception _ nil))))})))
