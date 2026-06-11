;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.common.user-agents
  "Runtime-defined agents — the LLM authors a persistent specialist from two
   prose blocks (an :instruction and a :tool-context) and it becomes a
   first-class, CoAct-derived agent: discoverable, routable, and callable as a
   sub-agent on the very next turn. See docs/design/meta-agent-design.md.

   Unlike user tools/hooks there is NO body to eval and NO sandbox to rehydrate
   — an authored agent is a *persona over the inherited CoAct palette*, not a new
   primitive. It grants no capability `coact-agent` lacks. The whole authored
   surface is the two prose blocks:

     :instruction   — who the agent is and how it works (role, decision flow,
                      content-handling, safety).
     :tool-context  — which tools from the inherited palette to reach for, and
                      the typical user-ask → tool-sequence flows.

   Persistence is a DIRECTORY per agent (prose wants real Markdown files, not a
   one-line escaped string), mirroring how skills persist:

     <project>/.brainyard/agents/user$agent/<name>/
       agent.edn          {:name :description :scope :version :created :updated}
       instruction.md     the :instruction block (verbatim prose)
       tool-context.md    the :tool-context block (verbatim prose)

   Registration goes into the SAME `agent.core.tool/!tool-defs` registry every
   `defagent` uses (`register-agent!`, §5A), so a user agent immediately shows up
   in `list-tools` / `search`, flows through `call-tool`'s coercion + the
   sub-agent depth/circular guards in `do-call-tool--agent`, can be a routing
   target for `main-agent`, and can be composed by a peer agent's tool-call
   channel — all without a recompile.

   The coact-agent functions are resolved LAZILY (requiring-resolve) rather than
   via a static :require: coact-agent statically requires THIS ns for its
   session-boot loader, so a static require back would cycle. Mirrors how
   user-tools lazily resolves sandbox-bindings."
  (:require [ai.brainyard.agent.core.tool :as tool :refer [defcommand]]
            [ai.brainyard.mulog.interface :as mulog]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.pprint :as pp]
            [clojure.string :as str]))

;; ============================================================================
;; Lazy coact refs (avoid a static require cycle — see ns docstring)
;; ============================================================================

(def ^:private !run-coact-derived
  (delay (requiring-resolve 'ai.brainyard.agent.common.coact-agent/run-coact-derived)))
(def ^:private !coact-behavior-tree
  (delay (requiring-resolve 'ai.brainyard.agent.common.coact-agent/coact-behavior-tree)))

(defonce ^{:doc "Set of agents-dirs already loaded this process, so `ensure-loaded!`
  is a no-op after the first session-boot for a given user/project."}
  !loaded
  (atom #{}))

(defn reset-loaded!
  "Drop the loaded-dirs set (next ensure-loaded! reloads). For tests / reload."
  []
  (reset! !loaded #{}))

;; ============================================================================
;; Naming / paths
;; ============================================================================

(def ^:private agent-name-re
  "User agent names: lowercase kebab, leading letter. Keeps `user$agent$<name>` a
   clean symbol/keyword and a safe directory name."
  #"^[a-z][a-z0-9-]*$")

(defn- now-iso
  "Current wall-clock as an ISO-8601 string (mirrors skills/plan/explore)."
  []
  (str (java.time.Instant/now)))

(defn- agents-dir
  "`.brainyard/agents/user$agent` under the project (fallback working dir, then
   cwd). Project-scoped, mirroring `.brainyard/tools` / `.brainyard/skills`."
  [dirs]
  (str (or (:project-dir dirs) (:working-dir dirs) ".")
       "/.brainyard/agents/user$agent"))

(defn- agent-dir [dirs name] (str (agents-dir dirs) "/" name))

(defn- agent-id [name] (keyword (str "user$agent$" name)))

;; ============================================================================
;; Persistence — a directory of companion files per agent (§2B)
;; ============================================================================

(defn- rm-rf! [^java.io.File f]
  (when (.isDirectory f) (doseq [c (.listFiles f)] (rm-rf! c)))
  (.delete f))

(defn write-agent!
  "Write the three companion files under `<dir>/`: agent.edn (pretty-printed
   metadata), instruction.md and tool-context.md (verbatim prose). Creates the
   directory as needed. Returns `{:dir <path>}`."
  [dir {:keys [name description scope version created updated]} instruction tool-context]
  (.mkdirs (io/file dir))
  (let [meta-map {:name name :description description
                  :scope (or scope :project) :version (or version 1)
                  :created created :updated updated}]
    (spit (io/file dir "agent.edn")
          (binding [*print-length* nil *print-level* nil]
            (with-out-str (pp/pprint meta-map))))
    (spit (io/file dir "instruction.md") (or instruction ""))
    (spit (io/file dir "tool-context.md") (or tool-context ""))
    {:dir dir}))

(defn read-agent
  "Read the full record `{:name :description :scope :instruction :tool-context}`
   for `<dir>/`, or nil when there is no agent.edn."
  [dir]
  (let [ef (io/file dir "agent.edn")]
    (when (.exists ef)
      (let [m  (edn/read-string (slurp ef))
            rd (fn [f] (let [file (io/file dir f)]
                         (when (.exists file) (slurp file))))]
        (assoc m
               :instruction  (rd "instruction.md")
               :tool-context (rd "tool-context.md"))))))

(defn- delete-agent-dir! [dir]
  (let [d (io/file dir)
        had (.exists d)]
    (when had (rm-rf! d))
    had))

;; ============================================================================
;; Runtime registration — the one new primitive (§5A)
;; ============================================================================

(defn- coact-bt-factory [{:keys [max-iterations]}]
  (@!coact-behavior-tree max-iterations))

(defn register-agent!
  "Register (or replace) a user-defined agent in !tool-defs as a CoAct-derived
   `:type :agent`. The `:fn` splices the persisted instruction/tool-context into
   `run-coact-derived` and pins the CoAct BT — there is NO `:agent-tools`, so the
   agent rides coact-agent's inherited palette (see design §2). Mirrors
   user-tools/register!. Returns the registry id."
  [{:keys [name description instruction tool-context]}]
  (let [id    (agent-id name)
        desc  (or description (str "User-defined agent " name))
        invoke (fn [opts]
                 (@!run-coact-derived
                  (merge {:bt-factory          coact-bt-factory
                          :instruction         instruction
                          :tool-context        tool-context
                          :_deftool$id         id
                          :_deftool$description desc}
                         opts)))]
    (swap! tool/!tool-defs assoc id
           {:id   id
            :type :agent
            :fn   invoke
            :meta {:id            id
                   :type          :agent
                   :description   desc
                   ;; Carried so direct-resolution entry points (setup-agent-by-id)
                   ;; pick up the CoAct BT + the persona's prose. The full coact
                   ;; palette is merged by run-coact-derived on the :fn path.
                   :bt-factory    coact-bt-factory
                   :instruction   instruction
                   :tool-context  tool-context
                   :tool-use-control {}
                   :input-schema  [:map
                                   [:question [:string {:desc "Request for this agent"}]]
                                   [:agent-context {:optional true} [:string {:desc "Extra context"}]]]
                   :output-schema [:map [:answer [:string {:desc "Agent's answer"}]]]
                   :category      :user
                   :user-defined  true}})
    id))

;; ============================================================================
;; Definition (validate + persist + register)
;; ============================================================================

(defn define-agent
  "Define a reusable, persistent user agent from its two prose blocks.

   Kwargs:
     :name         - lowercase-kebab string (matches #\"^[a-z][a-z0-9-]*$\")
     :description  - one-line description (shown in discovery / to the router)
     :instruction  - the agent's :instruction block (REQUIRED, non-blank prose)
     :tool-context - the agent's :tool-context block (optional prose)
     :dirs         - {:project-dir ...} resolving where to persist
     :now          - optional ISO timestamp string stamped into agent.edn

   Effects: validates, persists the three companion files to
   `.brainyard/agents/user$agent/<name>/`, and registers it into !tool-defs.
   Returns {:id :name :persisted} (:persisted is the agent directory)."
  [& {:keys [name description instruction tool-context dirs now]}]
  (when-not (and (string? name) (re-matches agent-name-re name))
    (throw (ex-info "agent :name must match ^[a-z][a-z0-9-]*$" {:name name})))
  (when (str/blank? instruction)
    (throw (ex-info "agent :instruction must be a non-blank string" {:name name})))
  (let [dir      (agent-dir dirs name)
        existing (read-agent dir)
        now      (or now (now-iso))]
    (write-agent! dir
                  {:name name :description description :scope :project
                   :version (inc (or (:version existing) 0))
                   :created (or (:created existing) now)
                   :updated now}
                  instruction tool-context)
    (let [id (register-agent! {:name name :description description
                               :instruction instruction :tool-context tool-context})]
      (mulog/info ::define-agent :id id :dir dir)
      {:id id :name name :persisted dir})))

;; ============================================================================
;; Startup loading (mirrors user-tools; no body/sandbox, so it cannot fail-eval)
;; ============================================================================

(defn load-user-agents!
  "Startup loader: re-register every persisted agent under
   `.brainyard/agents/user$agent/`. Call once when an agent session boots.
   Returns the names loaded. One pass — there is no body to eval, so peer
   references (an instruction naming `user$agent$<peer>`) resolve regardless of
   directory order."
  [& {:keys [dirs]}]
  (let [base (io/file (agents-dir dirs))]
    (if (.isDirectory base)
      (->> (.listFiles base)
           (filter #(.isDirectory ^java.io.File %))
           (keep (fn [^java.io.File d]
                   (try
                     (when-let [rec (read-agent (.getPath d))]
                       (register-agent! rec)
                       (:name rec))
                     (catch Exception e
                       (mulog/warn ::load-user-agent-failed
                                   :dir (.getName d) :error (ex-message e))
                       nil))))
           vec)
      [])))

(defn ensure-loaded!
  "Idempotent session-boot loader: load this user/project's persisted agents the
   first time the dir is seen this process, and no-op thereafter. Safe to call on
   every turn. Returns the names loaded (or nil when already loaded)."
  [& {:keys [dirs]}]
  (let [dir (agents-dir dirs)]
    (when-not (contains? @!loaded dir)
      (swap! !loaded conj dir)
      (load-user-agents! :dirs dirs))))

;; ============================================================================
;; Management (list / read / delete)
;; ============================================================================

(defn- current-dirs
  "Resolve dirs from the current agent session, falling back to init-dirs!.
   Uses requiring-resolve to avoid a static require cycle."
  []
  (or (when-let [a (some-> (requiring-resolve 'ai.brainyard.agent.core.protocol/*current-agent*)
                           deref)]
        (some-> (:!session a) deref
                ((or (requiring-resolve 'ai.brainyard.agent.core.session/get-session-config)
                     (constantly nil))
                 :dirs)))
      ((or (requiring-resolve 'ai.brainyard.agent.core.config/init-dirs!)
           (constantly {})))))

(defn- current-agent []
  (some-> (requiring-resolve 'ai.brainyard.agent.core.protocol/*current-agent*) deref))

(defn list-user-agents
  "Summaries of every registered user-defined agent, sorted by id."
  []
  (->> (vals @tool/!tool-defs)
       (filter #(and (= :agent (:type %)) (get-in % [:meta :user-defined])))
       (mapv (fn [td] {:id          (name (:id td))
                       :description (get-in td [:meta :description])}))
       (sort-by :id)
       vec))

(defn read-user-agent
  "Full record for one user agent — `{:name :description :instruction
   :tool-context}` — read from the persisted directory, falling back to registry
   metadata (without prose) when the directory is absent."
  [dirs name]
  (or (read-agent (agent-dir dirs name))
      (when (contains? @tool/!tool-defs (agent-id name))
        (-> (select-keys (:meta (get @tool/!tool-defs (agent-id name)))
                         [:description :instruction :tool-context])
            (assoc :name name :note "directory not on disk")))
      {:error (str "no user agent named " (pr-str name))}))

(defn delete-user-agent!
  "Unregister a user agent and delete its persisted directory."
  [dirs name]
  (let [id (agent-id name)]
    (if (contains? @tool/!tool-defs id)
      (do
        (swap! tool/!tool-defs dissoc id)
        (delete-agent-dir! (agent-dir dirs name))
        (mulog/info ::delete-user-agent :id id)
        {:deleted name})
      {:error (str "no user agent named " (pr-str name))})))

;; ============================================================================
;; Validation helpers
;; ============================================================================

(def ^:private tool-token-re
  "Candidate `$`-style command ids mentioned in a tool-context (e.g.
   `mcp$tools`, `plan$update`). Bare tool names (bash, read-file) are NOT
   checked — flagging arbitrary prose words would be noise — so :unknown-tools
   is a best-effort warning over namespaced command ids only."
  #"\b[a-z][a-z0-9-]*\$[a-z][a-z0-9$-]*\b")

(defn- unknown-tools
  "Namespaced command ids named in `tool-context` that don't resolve in
   !tool-defs. Returns a sorted vector (a warning, never a hard failure)."
  [tool-context]
  (if (str/blank? tool-context)
    []
    (->> (re-seq tool-token-re tool-context)
         distinct
         (remove #(contains? @tool/!tool-defs (keyword %)))
         sort
         vec)))

(defn- sample-ask
  "Behavioral smoke for a draft: snapshot the registry, register the draft, run
   ONE synchronous ask (as a sub-agent of the current agent so it returns the
   answer inline), then restore the registry in a finally — nothing leaks. Needs
   a live *current-agent* for the synchronous path; returns a note otherwise."
  [{:keys [name instruction tool-context]} question]
  (let [parent (current-agent)]
    (if-not parent
      {:note "sample skipped — no live agent context for a synchronous ask"}
      (let [snapshot @tool/!tool-defs
            nm       (or name "__sample")]
        (try
          (register-agent! {:name nm :description "draft sample"
                            :instruction instruction :tool-context tool-context})
          ;; Sub-agent of the current agent → call-tool builds the agent-session
          ;; from the parent and runs synchronously, returning the answer inline.
          (let [r (tool/call-tool (agent-id nm) {:question question} :agent parent)]
            (or (:answer r) r))
          (catch Exception e {:error (ex-message e)})
          (finally (reset! tool/!tool-defs snapshot)))))))

;; ============================================================================
;; Commands — the meta-agent$* family (mirrors tool-agent$* / hook-agent$*)
;; ============================================================================

(defcommand meta-agent$create
  "Author a reusable, PERSISTENT user-defined agent from two prose blocks. The
   agent is CoAct-derived — it inherits the full CoAct loop and tool palette, so
   you NEVER bind tools; you shape it entirely through :instruction (who it is /
   how it works) and :tool-context (which inherited tools to reach for). It
   survives restarts, registers as `user$agent$<name>`, and is callable as a
   first-class agent on the next turn."
  (fn [& {:as args}]
    (try
      (define-agent :name (:name args)
        :description (:description args)
        :instruction (:instruction args)
        :tool-context (:tool-context args)
        :dirs (current-dirs))
      (catch Exception e {:error (str "meta-agent$create failed: " (.getMessage e))})))
  :input-schema  [:map
                  [:name         [:string {:desc "lowercase-kebab agent name (no user$agent$ prefix)"}]]
                  [:instruction  [:string {:desc "The agent's instruction block: role, decision flow, content-handling, safety"}]]
                  [:description  {:optional true} [:string {:desc "one-line description shown in discovery / to the router"}]]
                  [:tool-context {:optional true} [:string {:desc "Which inherited tools to reach for + typical user-ask→tool flows"}]]]
  :output-schema [:map
                  [:id        [:string {:desc "Registered agent id, e.g. user$agent$tf-reviewer"}]]
                  [:name      [:string {:desc "Agent name"}]]
                  [:persisted [:string {:desc "Directory the agent was written to"}]]
                  [:error     [:string {:desc "Error if definition failed"}]]])

(defcommand meta-agent$validate
  "Dry-run a user-agent draft: structural checks on name/instruction/tool-context
   plus a collision check — persists nothing, registers nothing, mutates no live
   state. Use before meta-agent$create to iterate safely. Optionally pass :sample
   (a representative question) to register the draft into a restored-registry
   fork and run ONE ask (costs an LLM round-trip — opt-in). Mirrors
   meta-agent$create's arg names. Returns a structured report (never throws)."
  (fn [& {:as args}]
    (try
      (let [{:keys [name instruction tool-context sample]} args
            name-ok        (when name (boolean (re-matches agent-name-re name)))
            collision      (boolean (and name (contains? @tool/!tool-defs (agent-id name))))
            instruction-ok (not (str/blank? instruction))
            unknown        (unknown-tools tool-context)
            sample-answer  (when (and instruction-ok (string? sample) (not (str/blank? sample)))
                             (sample-ask {:name name :instruction instruction
                                          :tool-context tool-context}
                                         sample))
            errors         (cond-> []
                             (false? name-ok)     (conj "name must match ^[a-z][a-z0-9-]*$")
                             (not instruction-ok) (conj ":instruction must be a non-blank string"))]
        (cond-> {:valid          (empty? errors)
                 :collision      collision
                 :instruction-ok instruction-ok
                 :unknown-tools  unknown
                 :errors         errors}
          (some? name-ok)        (assoc :name-ok name-ok)
          (some? sample-answer)  (assoc :sample-answer sample-answer)))
      (catch Exception e
        {:valid false :collision false :instruction-ok false :unknown-tools []
         :errors [(str "meta-agent$validate failed: " (.getMessage e))]})))
  :input-schema  [:map
                  [:instruction  [:string {:desc "The agent's instruction block to validate"}]]
                  [:name         {:optional true} [:string {:desc "Proposed name; enables name + collision check"}]]
                  [:description  {:optional true} [:string {:desc "one-line description"}]]
                  [:tool-context {:optional true} [:string {:desc "Tool-context prose; named command ids are checked against the registry"}]]
                  [:sample       {:optional true} [:string {:desc "A representative question; if given, the draft is run once (LLM call)"}]]]
  :output-schema [:map
                  [:valid          [:boolean {:desc "True iff the hard checks (name + instruction) passed"}]]
                  [:name-ok        {:optional true} [:boolean {:desc "Name matches ^[a-z][a-z0-9-]*$"}]]
                  [:collision      [:boolean {:desc "An agent with this name already exists (create would overwrite)"}]]
                  [:instruction-ok [:boolean {:desc "Instruction is present and non-blank"}]]
                  [:unknown-tools  [:any {:desc "tool-context command ids that don't resolve (warning)"}]]
                  [:sample-answer  {:optional true} [:any {:desc "Answer from running the draft on :sample"}]]
                  [:errors         [:any {:desc "Vector of human-readable hard-failure lines"}]]])

(defcommand meta-agent$list
  "List user-defined agents (authored via meta-agent$create). Returns id + description."
  (fn [& _] {:agents (list-user-agents)})
  :input-schema  [:map]
  :output-schema [:map [:agents [:any {:desc "Vector of {:id :description}"}]]])

(defcommand meta-agent$read
  "Read a user-defined agent's instruction + tool-context by name (no prefix)."
  (fn [& {:as args}]
    (if (str/blank? (:name args))
      {:error "name is required"}
      (read-user-agent (current-dirs) (:name args))))
  :input-schema  [:map [:name [:string {:desc "User agent name, e.g. \"tf-reviewer\" (no user$agent$ prefix)"}]]]
  :output-schema [:map
                  [:name         [:string {:desc "Agent name"}]]
                  [:description  [:string {:desc "Description"}]]
                  [:instruction  [:string {:desc "The agent's instruction block"}]]
                  [:tool-context [:string {:desc "The agent's tool-context block"}]]
                  [:error        [:string {:desc "Error if not found"}]]])

(defcommand meta-agent$delete
  "Delete a user-defined agent: unregister it and remove its persisted directory."
  (fn [& {:as args}]
    (if (str/blank? (:name args))
      {:error "name is required"}
      (delete-user-agent! (current-dirs) (:name args))))
  :input-schema  [:map [:name [:string {:desc "User agent name to delete (no user$agent$ prefix)"}]]]
  :output-schema [:map
                  [:deleted [:string {:desc "Name of the deleted agent"}]]
                  [:error   [:string {:desc "Error if not found"}]]])

(def meta-agent-commands
  "All user-agent management commands, for binding into meta-agent. Mirrors
   `user-tools/tools-commands` and `user-hooks/hooks-commands`."
  [#'meta-agent$create #'meta-agent$validate #'meta-agent$list #'meta-agent$read #'meta-agent$delete])
