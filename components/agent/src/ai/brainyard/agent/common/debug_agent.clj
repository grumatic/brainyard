;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.common.debug-agent
  "Live-runtime debug specialist.

   Drives a self-debugging loop against the running brainyard JVM via
   clj-nrepl. Sibling to explore-agent and exec-agent; CoAct-derived, so
   the BT loop, hooks, and channel discipline come for free.

   Per-instance config:
   - `:clj-backend :nrepl` (every clojure fence routes to the live
     runtime) and `:nrepl-enabled?` come from the defagent's
     `:config-extra`, so they are set while the record is built.
     `:clj-backend` is re-asserted by the created-hook as a backstop.

   Per-instance lifecycle:
   - On :agent.instance/created — ENSURE the in-process nREPL server is
     up (starting it if it isn't — same idempotent path as
     `clj-nrepl$start-server`), then open a server-issued session and pin
     it on this instance's per-agent config (`:nrepl-session-id`).
     CoAct's run-clj-nrepl-block reads backend + session from the agent
     config; there is no per-fence override (the fence accepts only the
     language token).
   - On :agent.instance/closed — close the session.

   nREPL is the full-trust backend: a reachable loopback server gives full
   eval; the only eval-path check is the deny-list. This agent is
   END-TO-END: it diagnoses live (ephemeral `def`/`alter-var-root` to
   validate a fix in the running image), THEN makes the fix permanent
   itself — editing the source file with the file tools (read-file,
   update-file, write-file, grep) and reloading the namespace via nREPL to
   confirm the on-disk version applies live. There is no handoff to
   edit-agent. For ISOLATED evaluation, the SCI sandbox backend is the
   tool, not this agent.

   Server pre-requisite — this agent is USELESS without a live channel, so
   creating an instance IS the opt-in: the created-hook starts the loopback
   server itself when one isn't already up. Nothing is bypassed — the agent
   already owns `clj-nrepl$start-server` (gated to `debug-*`), which starts
   unconditionally; the hook just takes that same path deterministically
   instead of depending on the LLM to run the lifecycle step before its first
   fence. Pre-enabling still works and makes the hook a no-op:
   - .brainyard/config.edn (durable): `:agent {:config {:nrepl-enabled? true}}`.
   - BY_NREPL_ENABLED env var (transient env-fallback of the same key),
     or the `clj-nrepl$start-server` command on demand.
   Bootstrap enablement (`:nrepl-enabled?`) still governs whether NON-debug
   agents get a live channel — the hook's start is scoped to debug-agent."
  (:require [ai.brainyard.agent.core.tool :refer [defagent defcommand]]
            [ai.brainyard.agent.core.protocol :as proto]
            [ai.brainyard.agent.core.hooks :as hooks]
            [ai.brainyard.agent.core.usage :as usage]
            [ai.brainyard.agent.common.coact-agent :as coact]
            [ai.brainyard.agent.common.nrepl-bindings :as nrepl-bind]
            ;; Loading code-eval ensures `code$eval` is in the registry
            ;; whenever debug-agent is on the classpath — the defagent's
            ;; :agent-tools vector references it.
            [ai.brainyard.agent.common.code-eval]
            [ai.brainyard.agent.core.config :as config]
            [ai.brainyard.agent.core.feature :as feature]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [ai.brainyard.clj-nrepl.interface :as clj-nrepl]
            [ai.brainyard.mulog.interface :as mulog]))

;; ============================================================================
;; nREPL server lifecycle — start / stop / status (debug-agent only)
;;
;; The embedded loopback nREPL server is started either at bootstrap via
;; BY_NREPL_ENABLED=true or — for this agent — by its own instance-created
;; hook (`ensure-server!` below). These commands let the debug-agent manage it
;; on demand without a process restart: status/restart, and the recovery path
;; when the automatic start failed. nREPL is full-trust: reaching the server
;; gives full eval (the only eval-path check is the deny-list); for isolation
;; use the SCI sandbox backend. Gated to debug-* via :tool-use-control AND
;; bound on debug-agent's :agent-tools.
;; ============================================================================

(defn- eval-capable?
  "False under a GraalVM native image, where this agent cannot work at all.

   Clojure compiles every form to JVM bytecode through a `DynamicClassLoader`;
   native-image has no runtime compiler, so nREPL eval fails there for even
   `(+ 1 2)` — not just for `defn` or `require`. A native `by` can still START
   a server (the socket and the port file are ordinary I/O), which is the trap:
   without this check debug-agent comes up looking healthy and every fence dies
   at eval time.

   Detected via `org.graalvm.nativeimage.imagecode`, the property the image
   sets to \"runtime\" in generated code — read as a string rather than through
   the GraalVM SDK's `ImageInfo`, which is not on the classpath in JVM runs.
   Deliberately NOT a `(instance? DynamicClassLoader (RT/baseLoader))` probe:
   that answers false on the main thread of a plain `java -jar` run — the very
   uberjar runtime this check tells people to switch to — because the dynamic
   loader is pushed per nREPL session, not process-wide."
  []
  (not= "runtime" (System/getProperty "org.graalvm.nativeimage.imagecode")))

(def ^:private native-image-remedy
  "Re-run on the JVM — the uberjar works and ships brainyard's own .clj sources: BY_JAR=1 by … (or `bb tui` in a source checkout).")

(defn- ensure-server!
  "Idempotent loopback nREPL start, gated on runtime eval support and
   `:nrepl-enabled?`. Returns `{:port :already-running}`, or
   `{:disabled? true :reason … :remedy …}` when either check refuses — in
   which case NO start is attempted.

   The SINGLE start path — shared by `clj-nrepl$start-server` and the
   instance-created hook, so the agent's implicit start takes exactly the path
   the tool takes, gate included. `port` nil falls back to the configured
   `:nrepl-port` (BY_NREPL_PORT / config.edn), then 0 = ephemeral — matching
   what the base's bootstrap start would have bound.

   The gate is read through the `:exec/nrepl` feature rather than a raw
   `get-config`, so an unmet requirement (`:exec/code-channel`) also resolves
   off, and `off-reason` can say WHY. debug-agent ships
   `:config-extra {:nrepl-enabled? true}`, so its instances resolve on from
   the per-agent layer; `BY_NREPL_ENABLED=false` still outranks that (env is
   the top of the precedence chain) and remains the operator kill-switch.
   Resolution needs the agent — a nil `agent` sees only env/global/default,
   which is the correct answer for a caller outside any agent.

   The native check comes FIRST, ahead of the already-running short-circuit:
   a reachable server in a native image is worse than no server, not better,
   so \"one is already up\" must not silence it.

   An already-running server then short-circuits BEFORE the gate: the gate
   governs starting, and reporting \"disabled\" while a live and usable server
   is reachable would be a lie.

   `start-server!` THROWS when a server is already running, so a concurrent
   starter losing the race re-checks `running?` and reports the winner's port
   rather than surfacing a spurious failure."
  [agent port]
  (cond
    (not (eval-capable?))
    {:disabled? true
     :reason "this is a GraalVM native image, where Clojure cannot compile at runtime — nREPL eval fails here for every form"
     :remedy native-image-remedy}

    (clj-nrepl/running?)
    {:port (clj-nrepl/server-port) :already-running true}

    (not (feature/on? agent :exec/nrepl))
    {:disabled? true
     :reason (str "the nREPL channel is disabled ("
                  (or (feature/off-reason agent :exec/nrepl) "unavailable") ")")
     :remedy "Enable it with BY_NREPL_ENABLED=true or :agent {:config {:nrepl-enabled? true}} in .brainyard/config.edn."}

    :else
    (do
      (clj-nrepl/cleanup-stale-ports!)
      (try
        {:port (:port (clj-nrepl/start-server!
                       :bind "127.0.0.1"
                       :port (or port (config/get-config agent :nrepl-port) 0)
                       :port-file (clj-nrepl/instance-port-file "by")))
         :already-running false}
        (catch Throwable t
          (if (clj-nrepl/running?)
            {:port (clj-nrepl/server-port) :already-running true}
            (throw t)))))))

(defcommand clj-nrepl$start-server
  "Start the embedded loopback-only nREPL server (idempotent — a no-op when one
   is already running; debug-agent's created-hook has usually started it for you
   already). Requires the :nrepl-enabled? gate — returns :running false with a
   :message when it is off. Writes a per-instance port file
   (~/.brainyard/nrepl-ports/by-<pid>.port) so external CIDER tooling can attach
   to the SAME live image. nREPL is full-trust: reaching the server gives full
   eval (the only eval-path check is the deny-list); isolation is the SCI
   sandbox backend's job."
  (fn [{:keys [port]}]
    (let [{:keys [port already-running disabled? reason remedy]}
          (ensure-server! proto/*current-agent* port)]
      (if disabled?
        {:running false
         :port nil
         :port-file (str (clj-nrepl/instance-port-file "by"))
         :already-running false
         :message (str "nREPL server not started — " reason ". " remedy)}
        {:running true
         :port port
         :port-file (str (clj-nrepl/instance-port-file "by"))
         :already-running already-running})))
  :input-schema  [:map
                  [:port {:optional true}
                   [:int {:desc "Fixed loopback port to bind. Default 0 = ephemeral."}]]]
  :output-schema [:map
                  [:running [:boolean {:desc "True once the server is up; false when the :nrepl-enabled? gate blocked the start."}]]
                  [:port [:any {:desc "Bound loopback port (int), or nil when the server was not started."}]]
                  [:port-file [:string {:desc "Per-instance port file path for external attach."}]]
                  [:already-running [:boolean {:desc "True when a server was already running (start was a no-op)."}]]
                  [:message {:optional true} [:string {:desc "Present when the start was refused — why, and how to enable."}]]]
  :tool-use-control {:allow ["debug-*"]})

(defcommand clj-nrepl$stop-server
  "Stop the embedded nREPL server if running and remove its per-instance port
   file. No-op (returns :stopped false) when no server is running."
  (fn [_]
    (if-not (clj-nrepl/running?)
      {:running false :stopped false :message "no nREPL server running"}
      (let [port (clj-nrepl/server-port)
            pf   (clj-nrepl/instance-port-file "by")]
        (clj-nrepl/stop-server!)
        (try (when (.exists pf) (.delete pf)) (catch Throwable _ nil))
        {:running false :stopped true :was-port port})))
  :input-schema  [:map]
  :output-schema [:map
                  [:running [:boolean {:desc "Server running state after the call (false on success)."}]]
                  [:stopped [:boolean {:desc "True when a running server was stopped."}]]
                  [:was-port {:optional true} [:int {:desc "Port the stopped server had been bound to."}]]
                  [:message {:optional true} [:string {:desc "Present when there was nothing to stop."}]]]
  :tool-use-control {:allow ["debug-*"]})

(def ^:private add-classpath-form
  "Source evaluated INSIDE the live session — `%s` is a vector of path strings.

   Runs on the nREPL thread, not the tool thread: the extensible loader lives
   in the session, so a URL added from anywhere else is invisible to the very
   fences that need it. Hence code through `eval-string` rather than in-process.

   It adds to the OUTERMOST `DynamicClassLoader` in the parent chain, not to
   `RT/baseLoader` directly. nREPL pushes a fresh DynamicClassLoader per
   EVALUATION, so baseLoader here is a throwaway child that is discarded when
   this eval returns — the paths would appear to be added and then be gone by
   the next fence. (`development/src/dev/repl_test.clj` uses baseLoader and is
   correct for its own usage, because it adds and `require`s inside ONE eval.)
   Walking to the topmost DynamicClassLoader — the same thing Pomegranate does
   — lands the URL on the loader those per-eval children inherit from.

   Re-adding a URL is a harmless no-op."
  "(let [top (loop [cl (clojure.lang.RT/baseLoader) found nil]
               (if cl
                 (recur (.getParent cl)
                        (if (instance? clojure.lang.DynamicClassLoader cl) cl found))
                 found))
         cl  top]
     (if-not cl
       {:error (str \"live classpath is not extensible here — no DynamicClassLoader in the chain from \"
                    (.getName (class (clojure.lang.RT/baseLoader))))}
       (reduce (fn [acc p]
                 (let [f (.getCanonicalFile (java.io.File. (str p)))]
                   (if (.isDirectory f)
                     (do (.addURL cl (.toURL (.toURI f)))
                         (update acc :added conj (.getPath f)))
                     (update acc :skipped conj (str (.getPath f) \" (not a directory)\")))))
               {:added [] :skipped []}
               %s)))")

(defn- default-classpath-roots
  "Where a project's namespaces most likely live: `<project-dir>/src` when it
   exists, else the project dir itself — scratch and single-file projects keep
   sources at the root, and `<project>/src` would silently add nothing."
  [agent]
  (when-let [pd (:project-dir (config/get-config agent :dirs))]
    (let [src (java.io.File. pd "src")]
      [(.getPath (if (.isDirectory src) src (java.io.File. ^String pd)))])))

(defcommand clj-nrepl$add-classpath
  "Add director(ies) to the LIVE classpath so `require` / `:reload` can resolve
   namespaces from them. Without this, only files already on the classpath are
   requirable and everything else must be `load-file`d by absolute path — so a
   file you just wrote into the project is NOT requirable until you add its
   root here. Defaults to <project-dir>/src (or the project dir when there is
   no src/). Adding the same path twice is a no-op."
  (fn [{:keys [paths]}]
    (let [agent proto/*current-agent*
          roots (cond
                  (string? paths)     [paths]
                  (sequential? paths) (vec paths)
                  :else               (default-classpath-roots agent))]
      (cond
        (not (clj-nrepl/running?))
        {:added [] :skipped [] :error "clj-nrepl server is not running"}

        (empty? roots)
        {:added [] :skipped [] :error "no paths given and no project dir to default to"}

        :else
        (let [sid (config/get-config agent :nrepl-session-id)
              r   (clj-nrepl/eval-string (format add-classpath-form (pr-str roots))
                                         :session sid :timeout-ms 15000)
              parsed (try (edn/read-string (:result r)) (catch Throwable _ nil))]
          (if (map? parsed)
            (merge {:added [] :skipped [] :session sid} parsed)
            {:added [] :skipped [] :session sid
             :error (or (not-empty (str (:error r))) (:result r) "add-classpath failed")})))))
  :input-schema  [:map
                  [:paths {:optional true}
                   [:any {:desc "Directory path, or vector of them. Omit to use <project-dir>/src (or the project dir)."}]]]
  :output-schema [:map
                  [:added [:any {:desc "Canonical paths now on the live classpath."}]]
                  [:skipped [:any {:desc "Paths not added, each with the reason (e.g. not a directory)."}]]
                  [:session {:optional true} [:any {:desc "nREPL session the paths were added to — the classloader is per-session, so only this session sees them."}]]
                  [:error {:optional true} [:string {:desc "Present when nothing could be added."}]]]
  :tool-use-control {:allow ["debug-*"]})

;; ============================================================================
;; Materializing brainyard's own sources (self-debugging without a checkout)
;;
;; The uberjar ships brainyard's .clj files next to the AOT classes, so a
;; binary install already carries its own source — it is just not editable
;; where it sits. Extracting it gives the read → live-patch → edit → reload
;; loop against brainyard itself on a machine with no git checkout. The fix
;; then travels as a PATCH: the running artifact is not rebuilt by any of this.
;; ============================================================================

(def ^:private source-probe
  "A resource that exists in every packaging of brainyard — this very file.
   Its URL tells us how the running process was packaged."
  "ai/brainyard/agent/common/debug_agent.clj")

(defn- source-origin
  "Where THIS process's own `ai.brainyard` sources live:
     {:kind :jar :jar \"/path/to.jar\"}   — packaged run (uberjar / BY_JAR=1)
     {:kind :directory :root \"/…/src\"}  — source checkout, already editable
     {:kind :unknown :url …}             — neither shape
   nil when the probe resource is missing entirely."
  []
  (when-let [u (io/resource source-probe)]
    (case (.getProtocol u)
      "jar"  {:kind :jar
              ;; jar:file:/path/to.jar!/ai/brainyard/… → /path/to.jar
              :jar (-> (.getPath u)
                       (str/replace #"^file:" "")
                       (str/split #"!/")
                       first)}
      "file" (let [p (.getPath u)]
               {:kind :directory
                ;; strip the ns path back to the classpath root
                :root (subs p 0 (max 0 (- (count p) (count source-probe) 1)))})
      {:kind :unknown :url (str u)})))

(defn- clj-file-count
  [^java.io.File dir]
  (if (.isDirectory dir)
    (count (filter #(and (.isFile ^java.io.File %)
                         (str/ends-with? (.getName ^java.io.File %) ".clj"))
                   (file-seq dir)))
    0))

(defn- extract-jar-sources!
  "Copy every `prefix`-matching .clj/.cljc entry out of `jar-path` into `dest`,
   preserving the entry paths so the tree mirrors the classpath layout.
   Returns the number of files written."
  [jar-path prefix ^java.io.File dest]
  (with-open [zf (java.util.zip.ZipFile. (io/file jar-path))]
    (let [entries (->> (enumeration-seq (.entries zf))
                       (remove #(.isDirectory ^java.util.zip.ZipEntry %))
                       (filter (fn [^java.util.zip.ZipEntry e]
                                 (let [n (.getName e)]
                                   (and (str/starts-with? n prefix)
                                        (or (str/ends-with? n ".clj")
                                            (str/ends-with? n ".cljc")))))))]
      (doseq [^java.util.zip.ZipEntry e entries]
        (let [out (io/file dest (.getName e))]
          (io/make-parents out)
          (with-open [in (.getInputStream zf e)]
            (io/copy in out))))
      (count entries))))

(defn- default-source-dest
  "~/.brainyard/src/<build-version> — versioned so two installs never share a
   tree, and so an upgrade materializes fresh rather than mixing vintages."
  []
  (let [v (or (some-> (io/resource "build-version.edn") slurp edn/read-string :version)
              "unknown")]
    (io/file (System/getProperty "user.home") ".brainyard" "src"
             (str/replace (str v) #"[^A-Za-z0-9._-]" "_"))))

(defcommand clj-nrepl$materialize-sources
  "Extract brainyard's OWN .clj sources out of the running artifact into a
   writable directory, so you can read and edit them with no git checkout — the
   uberjar ships its sources alongside the compiled classes. Idempotent: an
   already-populated destination is left alone (it may hold your edits) unless
   :force is set. A source-checkout runtime needs no extraction and just
   reports where the editable sources already are.

   Reload an edited file with (load-file \"<root>/<path>.clj\") — NOT
   require/:reload. The jar sits ahead of any added directory in the classloader
   chain, so require would silently re-read the jar's frozen copy while looking
   like it worked; load-file reads the path directly and bypasses that.

   The running binary is NOT rebuilt: edits live in the extracted tree, so the
   deliverable is a patch to carry upstream."
  (fn [{:keys [prefix dest force]}]
    (let [origin (source-origin)
          prefix (or prefix "ai/brainyard/")]
      (case (:kind origin)
        :directory
        {:kind "directory" :root (:root origin) :files (clj-file-count (io/file (:root origin)))
         :message (str "Running from a source checkout — these files are already editable in place at "
                       (:root origin) ". No extraction needed; edit and reload as usual.")}

        :jar
        (let [d        (io/file (or dest (default-source-dest)))
              existing (clj-file-count d)]
          (if (and (pos? existing) (not force))
            {:kind "jar" :root (.getPath d) :files existing :jar (:jar origin)
             :already-materialized true
             :message (str "Already materialized at " (.getPath d)
                           " (" existing " files). Left untouched — it may hold your edits. "
                           "Pass :force true to re-extract, which DISCARDS them.")}
            (let [n (extract-jar-sources! (:jar origin) prefix d)]
              {:kind "jar" :root (.getPath d) :files n :jar (:jar origin)
               :already-materialized false
               :message (str "Extracted " n " files from " (:jar origin) " into " (.getPath d)
                             ". Edit there, then reload with (load-file \"" (.getPath d)
                             "/ai/brainyard/…/<ns>.clj\") — not require/:reload. "
                             "The running artifact is unchanged; report your fix as a patch.")})))

        {:kind "unknown" :root nil :files 0
         :message (str "Could not locate brainyard's own sources from this runtime: "
                       (pr-str origin))})))
  :input-schema  [:map
                  [:prefix {:optional true} [:string {:desc "Entry-path prefix to extract. Default \"ai/brainyard/\" (brainyard's own code, not its dependencies)."}]]
                  [:dest {:optional true} [:string {:desc "Destination dir. Default ~/.brainyard/src/<build-version>."}]]
                  [:force {:optional true} [:boolean {:desc "Re-extract over a populated destination, DISCARDING any edits there. Default false."}]]]
  :output-schema [:map
                  [:kind [:string {:desc "jar (extracted) | directory (source checkout) | unknown."}]]
                  [:root [:any {:desc "Directory holding the editable sources, or nil when unknown."}]]
                  [:files [:any {:desc "Number of .clj files extracted, or already present."}]]
                  [:jar {:optional true} [:string {:desc "Artifact the sources came from."}]]
                  [:already-materialized {:optional true} [:boolean {:desc "True when a populated destination was left untouched."}]]
                  [:message [:string {:desc "What happened, and how to reload edits."}]]]
  :tool-use-control {:allow ["debug-*"]})

(defcommand clj-nrepl$status
  "Status of the live-runtime channel: whether the loopback nREPL server is
   running, its port, and the inventory of known per-instance port files."
  (fn [_]
    {:running    (clj-nrepl/running?)
     :port       (clj-nrepl/server-port)
     :port-files (vec (clj-nrepl/list-port-files))})
  :input-schema  [:map]
  :output-schema [:map
                  [:running [:boolean {:desc "True when an nREPL server is up in this process."}]]
                  [:port [:any {:desc "Loopback port (int) or nil when not running."}]]
                  [:port-files [:any {:desc "Known per-instance port files: {:pid :port :file :alive?}."}]]]
  :tool-use-control {:allow ["debug-*"]})

;; ============================================================================
;; Per-instance lifecycle — open / close nREPL session
;;
;; The execution-model prompt section is selected by coact-system-context
;; based on the agent's :clj-backend config; setting :clj-backend :nrepl
;; below is sufficient — no separate :execution-model write needed.
;; ============================================================================

(defn- debug-agent?
  "True when `agent` is a debug-agent instance (agent-id namespaced by
   :debug-agent)."
  [agent]
  (and agent (= :debug-agent (proto/defagent-type agent))))

(defn- write-config!
  "Per-instance config write. Bypasses agent.core.config/set-config! to
   avoid the global / .brainyard/config.edn write that 2- and 3-arity
   set-config! perform — instance-scoped state must not leak into
   global config. Mirrors the set-allowed-dirs! pattern."
  [agent k v]
  (when-let [smi (some-> agent :!state deref :st-memory-init)]
    (swap! smi assoc-in [:config k] v)))

(defn- restore-config-default!
  "Re-assert an author-declared `:config-extra` key that a caller's shallow-merged
   `:config-extra` DROPPED — and only then.

   Absent-only, unlike `write-config!`: a key present on the per-agent layer got
   there because the caller deliberately passed it, and a safety gate the caller
   explicitly turned off must stay off. (Restoring it unconditionally is exactly
   the regression this arity exists to prevent: it silently re-enabled the
   full-trust channel for a caller that asked for `:nrepl-enabled? false`.)"
  [agent k v]
  (when-let [smi (some-> agent :!state deref :st-memory-init)]
    (swap! smi update :config
           (fn [cfg] (if (contains? cfg k) cfg (assoc cfg k v))))))

(defn- on-instance-created
  "Ensure the live channel exists, then pin a server-issued nREPL session id
   + the :clj-backend route on the new debug-agent instance.

   Every ```clojure fence from this agent routes to :nrepl, so a missing
   server makes the instance inert. Rather than leaving the fix to the LLM
   remembering the `clj-nrepl$status` → `clj-nrepl$start-server` lifecycle
   step before its first fence (a documented failure mode), start it here.
   A start failure is non-fatal: the agent still comes up and the first
   code-eval surfaces the gate error so the LLM can report it."
  [{:keys [agent]}]
  (when (debug-agent? agent)
    ;; Backstop, not the primary write — the defagent already declares
    ;; :clj-backend :nrepl in :config-extra, which lands in this same slot
    ;; earlier (during setup-agent, before this hook fires). Re-asserting it
    ;; here costs one swap! and closes the one hole in that route:
    ;; `setup-agent-by-id` merges caller options over defagent meta SHALLOWLY,
    ;; so a caller passing its own :config-extra map replaces the author's
    ;; wholesale — and a debug-agent silently demoted to the SCI sandbox looks
    ;; like it is working while answering from an image it cannot see. This
    ;; write is last and unconditional, so it cannot be merged away.
    ;;
    (write-config! agent :clj-backend :nrepl)
    ;; The route alone leaves the clobber hole half-open: `resolve-clj-backend`
    ;; demotes :nrepl to :sandbox unless the SAME agent also resolves
    ;; :nrepl-enabled? true, so a restored route just got demoted one layer
    ;; down. It only looked fixed because the gate happened to resolve true from
    ;; a lower layer — on a dev box that is typically a GITIGNORED
    ;; .brainyard/config.edn, i.e. the behaviour differed per machine and
    ;; vanished on a fresh clone or in CI.
    ;;
    ;; Restore-if-absent, not the unconditional write above: :clj-backend is
    ;; this agent's identity (a debug-agent on the SCI sandbox is a
    ;; contradiction), whereas :nrepl-enabled? is a SAFETY GATE — a caller that
    ;; explicitly passed `:config-extra {:nrepl-enabled? false}` gets to keep
    ;; the full-trust channel closed. Env BY_NREPL_ENABLED=false outranks this
    ;; layer either way, so the operator kill-switch is untouched.
    (restore-config-default! agent :nrepl-enabled? true)
    ;; Always consult ensure-server!, even when a server is already up — it
    ;; owns the already-running short-circuit AND the native-image check, and
    ;; a reachable-but-unusable server (native) must still be reported.
    (try
      (let [{:keys [port disabled? reason remedy already-running]}
            (ensure-server! agent nil)]
        (cond
          disabled?       (mulog/warn ::debug-agent-nrepl-disabled
                                      :agent-id (proto/agent-id agent)
                                      :reason   reason
                                      :remedy   remedy)
          already-running nil
          :else           (mulog/info ::debug-agent-server-autostarted
                                      :agent-id (proto/agent-id agent)
                                      :port     port)))
      (catch Throwable t
        (mulog/warn ::debug-agent-server-autostart-failed
                    :agent-id (proto/agent-id agent)
                    :error    (.getMessage t))))
    (if (clj-nrepl/running?)
      (try
        (let [sid (clj-nrepl/new-session)]
          (write-config! agent :nrepl-session-id sid)
          (mulog/info ::debug-agent-session-opened
                      :agent-id (proto/agent-id agent)
                      :session  sid))
        (catch Throwable t
          (mulog/warn ::debug-agent-session-open-failed
                      :agent-id (proto/agent-id agent)
                      :error    (.getMessage t))))
      (mulog/warn ::debug-agent-no-server
                  :agent-id (proto/agent-id agent)
                  :message  "clj-nrepl server not running; debug-agent will fail at first eval"))))

(defn- on-instance-closed
  "Close the pinned nREPL session when the agent is torn down, and drop the
   per-instance tool namespace so a long-lived JVM doesn't accumulate one
   `by.tools.*` namespace per closed agent."
  [{:keys [agent]}]
  (when (debug-agent? agent)
    (when-let [sid (some-> agent :!state deref
                           :st-memory-init :config :nrepl-session-id)]
      (try (clj-nrepl/close-session sid) (catch Throwable _))
      (mulog/info ::debug-agent-session-closed
                  :agent-id (proto/agent-id agent)
                  :session  sid))
    (try (nrepl-bind/uninstall! agent) (catch Throwable _))))

(defn register-hooks!
  "(Re)register debug-agent's instance lifecycle hooks. Idempotent —
   `register-hook!` dedupes by [event-key handler-id], so calling this at ns
   load, across reloads, or from a test that has wiped the global registry
   (`hooks/reset-hooks!`) is safe. Exposed so tests can re-establish these hooks
   without depending on ambient registration surviving a prior test's reset."
  []
  (hooks/register-hook! :agent.instance/created ::debug-agent-created
                        on-instance-created :source :debug-agent)
  (hooks/register-hook! :agent.instance/closed ::debug-agent-closed
                        on-instance-closed :source :debug-agent))

(register-hooks!)

;; ============================================================================
;; Instruction
;; ============================================================================

(def ^:private debug-instruction
  "You operate INSIDE the live brainyard JVM via clj-nrepl. Every ```clojure
   fence you emit runs in the running process with full reflection — every
   loaded namespace, var, atom, and value is reachable. You handle three jobs
   END-TO-END: (A) DEBUG a fault in the running system, (B) UNDERSTAND how
   brainyard works by reading the real image rather than recalling from
   training, and (C) FIX it permanently yourself — editing the source and
   reloading via nREPL. You own the whole cycle; there is no handoff.

   Always prefer reading the live image over guessing. If a question is about
   brainyard's behavior, config, tools, or wiring, inspect it directly — the
   Tool Usage Guide below has a catalog of ready-to-run introspection snippets.

   Debug → fix loop (for a fault):
     1. Reproduce — bind the offending inputs to a var, call the failing
        function, read `*e` and the stack trace.
     2. Probe — inspect related state (config, the tool registry, hooks,
        atoms, agent sessions, and the namespace where the symbol lives).
        Use `(meta #'the-var)` `:file`/`:line` to locate the source on disk.
     3. Hypothesize — state your guess explicitly before testing.
     4. Validate live (ephemeral) — `def`/`alter-var-root`/`defmethod` a
        replacement in the running image and re-run the reproducer. This
        confirms the fix WITHOUT touching source — fast, reversible.
     5. Make it permanent — once the live patch is proven, edit the SOURCE
        file with the file tools (read-file to see context, then update-file
        for a targeted change or write-file for a new file). The edit must
        match the validated patch.
     6. Reload + verify — `(require 'the.ns :reload)` (or `(load-file \"…\")`)
        to pull the on-disk version into the live image, then re-run the
        reproducer to confirm the SOURCE fix — not just your ephemeral def —
        resolves it. Optionally run the brick's tests via `bash` / `task$run`.
     7. Report — source path(s) edited, what changed, and how you verified.

   Notes:
   - nREPL is full-trust: a reachable server gives full eval. The only
     eval-path check is the deny-list — catastrophic forms (System/exit,
     Runtime/.exec, credential namespaces) are rejected. For ISOLATED
     evaluation the SCI sandbox backend is the tool, not this agent.
   - Introspection (reading namespaces / config / registries / atoms) is SAFE
     and non-destructive — do it freely. `def` / `alter-var-root` / `defmethod`
     mutate the LIVE image only and are EPHEMERAL (they die on process restart
     and are NOT written to source) — that is exactly why they are the safe way
     to VALIDATE before you commit the change to disk. The source edit (step 5)
     is what makes it durable.
   - Reload discipline: prefer `(require 'ns :reload)` for a single namespace,
     or re-eval just the changed `def`/`defn` form, or `(load-file path)`. Do
     NOT `:reload-all` an interface namespace — it rebuilds protocols and
     orphans live record instances (e.g. running agents). `:reload` is a flag,
     not a key: `(require '[ns :as a] :reload)`, never inside the libspec vector.
   - You do NOT need the `:nrepl` info-arg — your code blocks route to the
     live runtime by default. Your session evaluates in a private namespace
     that holds your bound tools (`by.tools.…`) with `clojure.core` referred, so
     fully-qualify everything else (`clojure.pprint/pprint`, not bare `pprint`);
     slice big values (`(take 20 …)`, `(keys …)`) instead of dumping.
   - No parallel mode: do NOT emit `<!-- ParallelBlock -->` markers. The live
     session can't be forked, so multiple ```clojure fences in one turn run
     SEQUENTIALLY in the SAME session (each sees the prior blocks' defs/state).
     Lean into that — probe, bind a var, reuse it in the next block.")

;; debug-only preamble — prepended to the :nrepl guide in this agent's
;; tool-context. The lifecycle tools below are gated to debug-* and are not
;; general nREPL knowledge, so they live here, not in the shared guide.
(def ^:private debug-lifecycle-preamble
  "## nREPL lifecycle tools (start / stop / status) — TOOL channel ONLY

   The server is normally ALREADY RUNNING: it is started for you when this
   agent instance is created, so you can go straight to a ```clojure block —
   no status/start dance needed first. The tools below are the recovery path
   for the rare case where that automatic start failed (a ```clojure block
   comes back with \"clj-nrepl server is not running\").

   `clj-nrepl$start-server`, `clj-nrepl$stop-server`, and `clj-nrepl$status`
   MUST be invoked through the TOOL channel (a tool-call), NEVER from inside
   a ```clojure code block. Your ```clojure blocks are evaluated BY the live
   nREPL server — so when the server is NOT running, a code block fails
   immediately with \"clj-nrepl server is not running\" and can never reach
   the start-server call (a chicken-and-egg deadlock). Route these three
   through the tool channel:
     - clj-nrepl$status        — check whether the server is up
     - clj-nrepl$start-server  — start it (idempotent)
     - clj-nrepl$stop-server   — stop it
   Only AFTER status confirms the server is running do ```clojure blocks
   evaluate against the live image; use the code channel for everything else.

   `clj-nrepl$add-classpath` is a TOOL-channel call for the same reason: it
   makes project directories requirable in the live image (see \"Paths and the
   classpath\" below). Reach for it the moment you want
   `(require 'some.ns :reload)` on code that is not already on the classpath.

   If a start ever comes back saying this is a GraalVM native image, stop —
   the native binary has no runtime compiler, so NO form will ever evaluate
   here, and nothing you can call will change that. Report that the session
   must be re-run on the JVM (BY_JAR=1, or `bb tui` in a source checkout).")

;; The `:nrepl` usage guide — the SINGLE SOURCE for live-runtime methodology,
;; colocated with debug-agent (the registry's intended colocation pattern). It
;; is registered into agent.core.usage below, AND inlined into debug-agent's
;; tool-context — one string, two consumers (debug-agent inline + on-demand
;; `(usage$guide :topic :nrepl)` for any other agent).
(def ^:private nrepl-guide
  "## Live runtime (clj-nrepl)
On the `:nrepl` backend, every ```clojure fence runs INSIDE the live brainyard
JVM with full reflection: every loaded namespace, var, atom, and value is
reachable. nREPL is full-trust — the only eval-path check is the deny-list
(System/exit, Runtime/.exec, credential namespaces). For ISOLATED eval, use the
SCI sandbox instead — see `(usage$guide :topic :sandbox)`.

### Parallel blocks are not supported here — just emit blocks normally
The `:nrepl` backend has NO parallel mode: a single live session is stateful and
cannot be forked across concurrent evals. Do NOT emit `<!-- ParallelBlock -->`
markers — if you do, the blocks are simply run SEQUENTIALLY against the live JVM
(with a short notice in the output) rather than rejected, so it costs you
nothing but buys you nothing either. Multiple ```clojure fences in one turn
already evaluate in order in the SAME session, so each block sees the `def`s,
requires, and state the previous blocks established. Sequence is the only mode;
lean into it (probe → bind a var → reuse it in the next block).

   ## Inspecting the live brainyard image (read-only, safe)

   Your code runs in the real JVM, so any loaded namespace, var, or value is
   reachable. Every snippet below is non-destructive — run them to understand
   the system instead of guessing. Your session evaluates in a private namespace
   holding your bound tools, with `clojure.core` referred — so fully-qualify
   everything else (that is why the snippets below spell out
   `ai.brainyard.…/…`).

   ### Survey the codebase
   ```clojure
   ;; every brainyard namespace (~120+)
   (->> (all-ns) (map ns-name)
        (filter (fn [n] (clojure.string/starts-with? (str n) \"ai.brainyard\")))
        sort)
   ;; public vars of one namespace
   (sort (keys (ns-publics 'ai.brainyard.agent.core.config)))
   ;; a function's docstring, arglists, and SOURCE location (file + line)
   (:doc      (meta #'ai.brainyard.agent.core.config/get-config))
   (:arglists (meta #'ai.brainyard.agent.core.tool/get-tool-defs))
   (select-keys (meta #'ai.brainyard.agent.core.config/get-config) [:file :line])
   ```

   ### Tool / command / agent registry (what brainyard can do)
   ```clojure
   (count (ai.brainyard.agent.core.tool/get-tool-defs))                  ;; total tools
   (sort (keys (ai.brainyard.agent.core.tool/get-tool-defs :type :command)))
   (sort (keys (ai.brainyard.agent.core.tool/get-tool-defs :type :agent)))
   (ai.brainyard.agent.core.tool/get-tool-defs :id :code$eval)          ;; one def + schema
   ```

   ### Configuration
   ```clojure
   (ai.brainyard.agent.core.config/get-config-snapshot)        ;; effective merged config
   (sort (keys ai.brainyard.agent.core.config/config-schema))  ;; every config key
   (ai.brainyard.agent.core.config/get-config :max-iterations) ;; one resolved value
   ```

   ### Hooks / events / live agents
   ```clojure
   (ai.brainyard.agent.core.hooks/list-hooks)      ;; registered observers
   ai.brainyard.agent.core.hooks/event-catalog     ;; events you can hook into
   (ai.brainyard.agent.interface/list-agents)      ;; live agent instances
   ```

   ### Reproduce a fault / read runtime state
   ```clojure
   *e                                              ;; last exception this session
   (ex-message *e)  (ex-data *e)                   ;; its message + data
   (keys (Thread/getAllStackTraces))               ;; what every thread is doing
   @ai.brainyard.agent.core.tool/!tool-defs        ;; deref an atom for live state
   ;; call any internal fn directly to reproduce a bug:
   (ai.brainyard.agent.core.config/get-config :clj-backend)
   ;; locate the source on disk for the var you're about to fix:
   (select-keys (meta #'ai.brainyard.agent.core.config/get-config) [:file :line])
   ```

   ## Paths and the classpath — read this BEFORE your first load-file

   Two different roots are in play, and mixing them up is the most common way
   to waste an iteration here:

   - **The file tools** (read-file / write-file / update-file / grep) resolve
     relative paths against the agent's **working directory**.
   - **Your ```clojure code** runs in the nREPL JVM, whose **cwd is wherever
     that process was started** — usually NOT the working directory. So a
     relative `(load-file \"lab/greet.clj\")` looks in the wrong place and
     throws FileNotFoundException even though the file you just wrote is there.

   Rules that follow:

   1. **Always pass load-file an ABSOLUTE path.** Build it from the working
      directory reported in your system context, not from a bare relative path.
   2. **`require` only works for namespaces on the classpath.** A file you just
      wrote into the project is not on it, so `(require 'lab.greet)` throws
      FileNotFoundException while `load-file` of the same file succeeds. That is
      a classpath fact, not a bug.
   3. **To make `require` / `:reload` work for project code, add its root
      first** — call `clj-nrepl$add-classpath` on the TOOL channel (no args
      defaults to `<project-dir>/src`, or the project dir when there is no
      `src/`; pass `:paths` for anything else). After that,
      `(require 'lab.greet :reload)` resolves and the whole reload discipline
      below applies. Namespace-to-path still has to line up: `lab.greet` must
      live at `<root>/lab/greet.clj`.
   4. For a one-off file that is not worth a classpath entry, `load-file` with
      an absolute path is the right tool — it re-reads the file from disk every
      time, so it doubles as the reload.

   ## Debugging brainyard itself with no source checkout

   When the fault is in brainyard and there is no git checkout to edit, you are
   NOT stuck: the packaged artifact ships brainyard's own `.clj` files next to
   its compiled classes. Call `clj-nrepl$materialize-sources` (TOOL channel) to
   extract them to a writable tree (default `~/.brainyard/src/<version>`); it
   reports the root and leaves an existing tree alone so it cannot eat edits
   you already made. Running from a source checkout, it just tells you where
   the editable files already are.

   Then edit the extracted file and reload it with an ABSOLUTE `load-file`:

   ```clojure
   (load-file \"/Users/you/.brainyard/src/v0.5.2/ai/brainyard/agent/core/config.clj\")
   ```

   Do **not** use `require` / `:reload` for a namespace that came from the jar,
   and do not bother adding the extracted tree with `clj-nrepl$add-classpath`:
   the jar sits ahead of any directory you add in the classloader chain
   (delegation is parent-first), so `require` re-reads the jar's FROZEN copy and
   your edit appears to reload while changing nothing. `load-file` reads the
   path directly and is unaffected. (`add-classpath` is still the right tool for
   the user's OWN project namespaces, which are not in the jar.)

   Finally, be honest about what you produced: the running binary is not
   rebuilt by any of this. Your fix lives in the extracted tree and in the live
   image, so REPORT IT AS A PATCH — the file path, the change, and how you
   verified it after reload — for someone to apply upstream and rebuild.

   ## Making a fix permanent (edit source + reload)
   You own the whole cycle — validate the fix live, then write it to source
   and reload, all in this one agent. The file tools are bound as plain
   functions in your session namespace — `(read-file :path \"…\")`,
   `(update-file …)`, `(write-file …)`, `(grep …)`, `(bash …)` — callable from a
   ```clojure block with no `call-tool` wrapper, or through the tool channel,
   whichever fits the turn. Workflow:

   1. VALIDATE LIVE first (cheap, reversible). Patch the running image and
      re-run the reproducer:
      ```clojure
      ;; ephemeral hot-patch — proves the fix before touching disk
      (alter-var-root #'ai.brainyard.some.ns/buggy-fn (constantly (fn [x] …)))
      ;; …or redefine a defmethod / def, then re-run your reproducer
      ```
   2. LOCATE the source. The var's metadata gives the exact file + line:
      ```clojure
      (select-keys (meta #'ai.brainyard.some.ns/buggy-fn) [:file :line])
      ```
      `:file` is a classpath-relative path; the project-root path is usually
      `components/<brick>/src/<that-path>` (grep for the defn to confirm).
   3. EDIT the source to match the validated patch:
      - `read-file` the region for exact context.
      - `update-file` for a targeted replacement (preferred), or `write-file`
        for a brand-new file.
   4. RELOAD into the live image and re-verify against the SOURCE (not just
      your ephemeral def):
      ```clojure
      (require 'ai.brainyard.some.ns :reload)   ;; pull the on-disk version in
      ;; …re-run the reproducer — it must now pass from source.
      ```
      Reload discipline: single-namespace `:reload` (or re-eval the one changed
      form, or `(load-file \"…/some/ns.clj\")`). NEVER `:reload-all` an interface
      namespace — it rebuilds protocols and orphans live records (running
      agents). `:reload` is a flag: `(require '[ns :as a] :reload)`, not inside
      the libspec vector.
   5. (Optional) run the brick's tests to guard against regressions:
      ```clojure
      (task$run :job-type :bash :command \"bb test:component --brick agent\")
      ;; or a focused nREPL test run — see `bb repl:test <ns>`
      ```
   6. REPORT the source path(s) edited, the change, and how you verified.

   ### Invoking registered tools — they are bound as functions
   Registered tools are interned into your session namespace, exactly as they
   are in the SCI sandbox, so the `### Function Directory` in your system prompt
   is callable verbatim. Kwargs is the canonical shape; a single flat map also
   works.

   ```clojure
   ;; Discover / inspect
   (list-tools :pattern \"^memory\\$\")
   (list-tools :type \"agent\")
   (get-tool-info :tool-id \"task$run\")     ;; schema BEFORE calling anything new
   (search :query \"clj-backend\")

   ;; Read / grep files (project-root anchored)
   (read-file :path \"components/agent/src/ai/brainyard/agent/core/tool.clj\"
              :lines [450 510])
   (grep :pattern \"defn call-tool\" :path \"components/agent/src\"
         :include-exts [\".clj\"])

   ;; Background tasks
   (task$run    :job-type :bash :command \"ls -la .brainyard\")
   (task$list)
   (task$detail :task-id \"task-mkq8f3x2a1b\" :last-n \"50\")
   (task$cancel :task-id \"task-mkq8f3x2a1b\")

   ;; Agent-scoped tools — no :agent argument needed, see below
   (memory$status)
   (memory$recall :query \"recent commits\" :limit 5)

   ;; Sub-LLM (no tools, no iteration — cheap fan-out)
   (query$llm :prompt \"Summarize this stack trace: …\")

   ;; The binding carries the tool's own docs
   (:doc      (meta #'read-file))
   (:arglists (meta #'task$run))
   ```

   These bindings close over THIS agent instance, so `*current-agent*` is bound
   for you on every call — the reason `memory$*` / session tools work bare here
   rather than degrading to `{:error \"current agent is not running\"}`. They are
   refreshed at the start of every turn, so a tool you create mid-session
   (`tool-agent$create`, a newly connected MCP server) is callable next turn.

   ### `call-tool` — the escape hatch (two cases only)
   `ai.brainyard.agent.core.tool/call-tool` still dispatches any registered tool
   by id, and remains the way in when:

   1. You need to run a tool AS ANOTHER agent — e.g. reading a different
      agent's memory/session context:
      ```clojure
      (require '[ai.brainyard.agent.core.tool :as t]
               '[ai.brainyard.agent.core.agent :as ag]
               '[ai.brainyard.agent.core.protocol :as proto])
      (def coact (first (filter #(= \"coact-agent\" (namespace (proto/agent-id %)))
                                (ag/list-agents))))
      (t/call-tool :memory$status {} :agent coact)
      ```
   2. Your nREPL endpoint is a REMOTE host (`:nrepl-host`), where local bindings
      cannot exist — `(some-tool …)` will hit Unable-to-resolve and every call
      must go through `call-tool` or the tool channel.

   Notes:
   - Errors surface as `{:error …}` on the binding (`{:error-message …}` from
     raw `call-tool`) for permission denial / schema mismatch, or as a thrown
     exception from the tool fn — read `*e` / `(ex-data *e)` after.
   - Permission gating is unchanged: the global allow/deny/approval policy always
     applies, and per-agent visibility (`:tool-use-control`) applies because the
     bindings carry this agent — you only see what debug-agent may see.
   - For internal fns (not registered as tools), call them directly by their
     fully-qualified var — no binding and no `call-tool` involved.")

;; Register the guide so any agent can pull it with `(usage$guide :topic :nrepl)` and the
;; JIT nudge can surface it on first `clj-nrepl$*` use. :scope :user keeps it
;; OUT of the always-on system-prompt table (debug-agent inlines it directly,
;; below; others pull it on demand). :order 15 keeps it next to :sandbox in the
;; `(usage$guide)` catalog (see agent.common.usage-guides for the rest).
(usage/register-usage! :nrepl
                       {:guide    nrepl-guide
                        :title    "Live Runtime (nREPL)"
                        :category :debug
                        :scope    :user
                        :order    15
                        :consult  "On the `:nrepl` backend (debug-agent) — inspect/patch the running JVM, debug→fix loop."})

;; debug-agent's tool-context = debug-only preamble + the :nrepl guide, inlined
;; from the registry (single source — never hand-written twice).
(def ^:private debug-tool-context
  (str debug-lifecycle-preamble "\n\n" (usage/get-usage-guide :nrepl)))

;; ============================================================================
;; Defagent registration
;; ============================================================================

(defagent debug-agent
  "Live-runtime specialist for the running brainyard JVM via clj-nrepl. END-TO-END across three jobs: (A) DEBUG a fault with the reproduce → probe → hypothesize → validate-live loop; (B) UNDERSTAND how brainyard works by inspecting the live image (namespaces, tool registry, config, hooks, source locations) instead of guessing; (C) FIX it permanently itself — validate the patch live, then edit the source (read-file/update-file/write-file) and reload the namespace via nREPL to confirm the on-disk fix applies. Pins an nREPL session per instance; routes every ```clojure block to the live runtime. No edit-agent handoff."
  coact/run-coact-derived
  ;; Pin :bt-factory explicitly so direct-resolution entry points
  ;; (setup-agent-by-id used by `bb tui ask`) work without going
  ;; through run-coact-derived. See explore-agent for the pattern.
  :bt-factory (fn [{:keys [max-iterations]}]
                (coact/coact-behavior-tree max-iterations))
  ;; The live channel IS this agent — every ```clojure fence routes to :nrepl.
  ;; Both keys are schema keys, so `setup-agent` splits them out of
  ;; :config-extra into the per-agent override layer (st-memory-init :config)
  ;; while the record is being built — i.e. BEFORE the instance-created hook
  ;; runs, so nothing can observe a half-configured instance.
  ;;
  ;;   :nrepl-enabled? — the instance's own opt-in, so no operator pre-enable
  ;;     step is needed. `ensure-server!`'s :exec/nrepl gate reads exactly this,
  ;;     which is what makes the autostart gated rather than unconditional.
  ;;     Precedence keeps the operator in charge: BY_NREPL_ENABLED=false (env,
  ;;     the top layer) still wins and blocks the start; a persisted
  ;;     `.brainyard/config.edn` false does NOT, since it sits below the
  ;;     per-agent layer — that key governs the base's bootstrap start
  ;;     (whether NON-debug agents get a live channel).
  ;;   :clj-backend — the code-eval route. Declared here so it is correct from
  ;;     construction and discoverable in the registry meta; the hook re-asserts
  ;;     it afterwards as a backstop (see on-instance-created for the
  ;;     shallow-merge hole that backstop covers).
  :config-extra {:nrepl-enabled? true :clj-backend :nrepl}
  :tool-use-control {}
  :input-schema  [:map
                  [:question [:string {:desc "What to investigate: a bug/stack-trace/wedged-component, OR a question about how brainyard works (config, tools, wiring, where a function lives) that should be answered by reading the live image."}]]
                  [:agent-context {:optional true} [:string {:desc "Optional pointer to upstream context — a related explore-agent dossier, an issue link, prior debug notes."}]]]
  :output-schema [:map
                  [:answer [:string {:desc "Findings grounded in the live image: for a fault — root cause, what was probed, the permanent fix (source path(s) edited + how it was verified after reload), or revert note if not fixed; for a question — the answer with the namespaces/values/source-locations that prove it."}]]]
  :agent-tools {:tools [:code$eval
                        ;; Source editing — debug-agent makes its own
                        ;; permanent fixes (no edit-agent handoff): validate
                        ;; live via code$eval, then edit the file and reload.
                        :read-file
                        :update-file
                        :write-file
                        :grep
                        :search
                        :bash
                        ;; Background execution / inspection (e.g. running a
                        ;; brick's tests after a source edit)
                        :task$run
                        :task$detail
                        :task$list
                        :task$cancel
                        :clj-nrepl$start-server
                        :clj-nrepl$stop-server
                        :clj-nrepl$status
                        :clj-nrepl$add-classpath
                        :clj-nrepl$materialize-sources]}
  :instruction debug-instruction
  :tool-context debug-tool-context
  :max-iterations 30)
