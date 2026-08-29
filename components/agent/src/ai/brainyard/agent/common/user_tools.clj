;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.common.user-tools
  "Runtime-defined tools — the LLM authors a tool from Clojure source and it
   becomes a first-class, persistent, discoverable tool.

   Why source (not a closure): SCI closures are not EDN-serializable, so the
   existing sandbox persistence drops them (see
   clj-sandbox extract-user-vars-with-survival `:non-edn`). We therefore persist
   the SOURCE form to `.brainyard/tools/<name>.edn` and RE-EVAL it in a sandbox
   to rehydrate the tool on call and on session start. That is the capability a
   plain `defn` in the agent's sandbox cannot provide.

   The tool body runs in a dedicated, long-lived `!tools-sandbox` (forked per
   call for isolation). The body is a `(fn [args] ...)` of one map argument and
   may compose other registered tools by their DIRECT symbol — builtins like
   `(bash :command …)` / `(read-file :path …)` (supplied via :extra-bindings) and other
   user tools as `(user$tool$<name> :k v)` (bound on registration). call-tool is
   intentionally hidden, so composition is by symbol, not via call-tool. User
   tools are macros over the existing tool palette, not new host primitives (no
   new privilege beyond what the sandbox already grants).

   Registration goes into the SAME `agent.core.tool/!tool-defs` registry that
   `deftool` uses, so user tools immediately show up in `list-tools` / `search`,
   flow through `call-tool`'s Malli coercion + hook/permission/depth guards, and
   get auto-bound into agent sandboxes as `user$tool$<name>` callables."
  (:require [ai.brainyard.agent.core.tool :as tool :refer [defcommand]]
            [ai.brainyard.agent.core.config :as config]
            [ai.brainyard.agent.common.schema :as acs]
            [ai.brainyard.agent.common.def-store :as def-store]
            [ai.brainyard.clj-sandbox.interface :as sb]
            [ai.brainyard.mulog.interface :as mulog]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]))

;; ============================================================================
;; Tools sandbox (holds every user tool body as a real SCI var)
;; ============================================================================

(defonce ^{:doc "The long-lived sandbox holding user tool bodies as `__ut_<name>`
  vars. Forked per call so concurrent invocations don't share mutable state."}
  !tools-sandbox
  (atom nil))

(defn- tools-sandbox
  "The live tools sandbox, created on first use. `extra-bindings` (typically the
   agent's `auto-tool-bindings`) expose registered tools as DIRECT symbols a body
   can call — builtins like `(bash :command …)` / `(read-file :path …)` and other
   user tools as `(user$tool$<name> :k v)`. There is no generic `call-tool` helper here:
   call-tool is intentionally hidden (`:visibility :hidden`), so a tool is
   composed by its own symbol, not through call-tool."
  ([] (tools-sandbox nil))
  ([extra-bindings]
   (let [sbx (or @!tools-sandbox
                 (reset! !tools-sandbox
                         (sb/create-sandbox :bindings (or extra-bindings {})
                                            :interop (config/resolve-sandbox-interop))))]
     (when (seq extra-bindings)
       (sb/update-bindings! sbx extra-bindings))
     sbx)))

(defonce ^{:doc "Set of tools-dirs whose METADATA is registered in !tool-defs this
  process (phase 1). Guards `ensure-registered!`, which runs at process boot
  before any agent exists."}
  !registered
  (atom #{}))

(defonce ^{:doc "Set of tools-dirs whose BODIES are installed in the tools sandbox
  this process (phase 2), so `ensure-loaded!` is a no-op after the first
  session-boot for a given user/project."}
  !loaded
  (atom #{}))

(defn reset-tools-sandbox!
  "Drop the tools sandbox and both phase guards (next call rebuilds / reloads).
   For tests / reload."
  []
  (reset! !tools-sandbox nil)
  (reset! !registered #{})
  (reset! !loaded #{}))

;; ============================================================================
;; Definition / persistence / registration
;; ============================================================================

(def ^:private tool-name-re
  "User tool names: lowercase kebab, leading letter. Keeps `user$tool$<name>` a clean
   symbol/keyword and a safe filename."
  #"^[a-z][a-z0-9-]*$")

(defn- tools-dir
  "`.brainyard/tools` under the project (fallback working dir, then cwd).
   Project-scoped, mirroring `.brainyard/skills` / `.brainyard/plans`: a tool
   authored in a checkout is a shared project asset, and project-dir is already
   the user's working directory. (A `:scope :user` variant rooted at the
   user-dir could be added later, exactly as skills do.)"
  [dirs]
  (str (or (:project-dir dirs) (:working-dir dirs) ".") "/.brainyard/tools"))

(defn- tool-id [name] (keyword (str "user$tool$" name)))

(defn- install-body!
  "Eval `(def __ut_<name> <body>)` into the tools sandbox so it is a callable
   SCI var. Throws if the body fails to parse/eval."
  [name body-str]
  (let [r (sb/eval-code (tools-sandbox)
                        (str "(def __ut_" name " " body-str ")"))]
    (when-let [err (:error r)]
      (throw (ex-info (str "tool body failed to eval: " err)
                      {:name name :body body-str})))
    r))

(defn bind-into-live-sandbox!
  "Bind the just-registered tool/agent as its `user$tool$<name>` /
   `user$agent$<name>` symbol into the CURRENT agent's live code-eval sandbox, so
   a create-then-call in the SAME turn resolves the symbol instead of failing
   until the next turn (the turn-start binding snapshot in coact-agent predates
   this registration). Generic over any `!tool-defs` entry — user-agents/register-agent!
   reuses it for `:type :agent` defs. Best-effort: no current agent, no live
   sandbox, or a binding failure is a silent no-op — the def is still live via the
   registry (JSON tool-call channel) and rebinds normally next turn.

   Runtime-resolved (protocol/*current-agent* + sandbox-bindings/bind-one-tool)
   to avoid a static require cycle — sandbox-bindings requires this ns for
   registration (see current-extra-bindings)."
  [tool-def]
  (try
    (when-let [agent (some-> (requiring-resolve 'ai.brainyard.agent.core.protocol/*current-agent*)
                             deref)]
      (when-let [sbx (get-in @(:!state agent) [:sandbox])]
        (let [bind-one (requiring-resolve 'ai.brainyard.agent.common.sandbox-bindings/bind-one-tool)
              [sym f]  (bind-one tool-def agent)]
          (when sym (sb/update-bindings! sbx {sym f})))))
    (catch Exception e
      (mulog/warn ::bind-into-live-sandbox-failed :error (ex-message e)))))

(defn- bind-peer-symbol!
  "Bind the direct `user$tool$<name>` symbol in the TOOLS sandbox, so other user
   tool bodies can compose this tool by symbol. Routes through the registry
   (`tool/call-tool`) so composition still gets Malli coercion +
   hook/permission/depth guards — not through the hidden call-tool helper.

   Sandbox-touching, so it belongs to body installation (phase 2), NOT to
   registration: `register!` runs at process boot, where creating an SCI context
   would be work done for a `by agents` that never evaluates anything."
  [name]
  (sb/set-var! (tools-sandbox)
               (symbol (str "user$tool$" name))
               (fn [args]
                 (let [r (tool/call-tool (tool-id name) (or args {}))]
                   (if (:error-message r) {:error (:error-message r)} r)))))

(defn- register!
  "Register (or replace) the tool in the shared !tool-defs registry. PURE
   registry work — it never touches the tools sandbox — so it is safe to run at
   process boot before any agent exists. The registry :fn rehydrates by forking
   the tools sandbox and calling `__ut_<name>` with the args map bound as
   `args`, which requires `install-bodies!` to have run first.

   Also binds the symbol into the current agent's live code-eval sandbox so a
   create-then-call in the same turn resolves (see bind-into-live-sandbox!); at
   boot there is no current agent and that is a no-op.

   `:file` is the `.edn` this record came from — the registry's attribution, so
   two files declaring the same `:name` shadow VISIBLY instead of silently (see
   tool/register-def!). Every caller has it: the loader threads it on in
   `read-persisted`, `define-tool` passes the path `write-def!` just returned."
  [{:keys [name description input-schema file]}]
  (let [id     (tool-id name)
        schema (or input-schema [:map])
        invoke (fn [args]
                 (let [clean (dissoc args :agent :parent-agent :agent-session
                                     :_deftool$id :_deftool$type
                                     :_deftool$description :_deftool$input-schema
                                     :_deftool$output-schema)
                       fork  (sb/fork-sandbox (tools-sandbox))]
                   (sb/set-var! fork 'args clean)
                   (let [r (sb/eval-code fork (str "(__ut_" name " args)"))]
                     (if-let [err (:error r)] {:error err} (:result r)))))
        tool-def {:id   id
                  :type :tool
                  :fn   invoke
                  :meta {:id            id
                         :type          :tool
                         :description   description
                         :input-schema  schema
                         :output-schema [:map]
                         :category      :user
                         :user-defined  true}}]
    (tool/register-def! id tool-def file)
    ;; Same-turn callability from the LLM's clojure code blocks.
    (bind-into-live-sandbox! tool-def)
    id))

(defn define-tool
  "Define a reusable, persistent tool from source.

   Kwargs:
     :name          - lowercase-kebab string (matches #\"^[a-z][a-z0-9-]*$\")
     :description   - one-line description
     :input-schema  - Malli [:map ...] (default [:map]); drives coercion/validation
     :body          - a string `(fn [args] ...)` of ONE map arg
     :dirs          - {:project-dir ...} resolving where to persist

   Effects: validates + eval-smoke-tests the body, persists the metadata to
   `.brainyard/tools/<name>.edn` and the verbatim body source to the
   `.brainyard/tools/<name>.clj` sidecar, and registers it into !tool-defs.
   Returns {:id :name :persisted} (:persisted is the .edn path)."
  [& {:keys [name description input-schema body dirs extra-bindings]}]
  (when-not (and (string? name) (re-matches tool-name-re name))
    (throw (ex-info "tool :name must match ^[a-z][a-z0-9-]*$"
                    {:name name})))
  (when-not (string? body)
    (throw (ex-info "tool :body must be a string `(fn [args] ...)`" {:body body})))
  (when (and input-schema
             (not (and (vector? input-schema) (= :map (first input-schema)))))
    (throw (ex-info "tool :input-schema must be a [:map ...] schema"
                    {:input-schema input-schema})))
  (tools-sandbox extra-bindings)                  ;; ensure + refresh tool palette
  (install-body! name body)                       ;; compile-now smoke test
  (let [dir   (tools-dir dirs)
        rec   {:name name :description description
               :input-schema (or input-schema [:map]) :body body}
        {:keys [edn]} (def-store/write-def! dir name (dissoc rec :body) body)]
    ;; Register under the path `write-def!` just wrote, which is byte-identical
    ;; to what `read-persisted` will compute for the same file — so re-creating
    ;; an existing tool is a same-source replace and stays silent, while a
    ;; SECOND file claiming this `:name` is a shadow and does not.
    (let [id (register! (assoc rec :file edn))]
      ;; `register!` is sandbox-free (it runs at boot too), so the peer symbol
      ;; other bodies compose by is bound here, next to the body install above.
      (bind-peer-symbol! name)
      (mulog/info ::define-tool :id id :file edn)
      {:id id :name name :persisted edn})))

(defn- read-persisted
  "Every persisted tool record under `dir-str`, as `{:name :description
   :input-schema :body :file}` maps. An unreadable file is warned about and
   skipped — one corrupt `.edn` must not cost the user every other tool.

   `:file` is carried so BOTH later phases can name a file: registration uses it
   to attribute the id (tool/register-def!) and `install-bodies!` reports it when
   a body fails to eval. Attached here rather than in `def-store/read-def`
   because that record is returned verbatim to the LLM by `read-user-tool`.

   The listing is SORTED. Two `.edn` files can declare the same `:name` (the
   file basename and the `:name` inside it are independent once a file is
   hand-edited), and one then overwrites the other — `.listFiles` order is
   undefined, so which one won was a property of the filesystem."
  [dir-str]
  (let [dir (io/file dir-str)]
    (if (.isDirectory dir)
      (->> (.listFiles dir)
           (filter #(str/ends-with? (.getName ^java.io.File %) ".edn"))
           (sort-by #(.getName ^java.io.File %))
           (keep (fn [^java.io.File f]
                   (let [base (subs (.getName f) 0 (- (count (.getName f)) 4))]
                     (try (some-> (def-store/read-def dir-str base)
                                  (assoc :file (.getPath f)))
                          (catch Exception e
                            (mulog/warn ::load-user-tool-read-failed
                                        :file (.getPath f) :error (ex-message e))
                            nil)))))
           vec)
      [])))

(defn register-persisted!
  "PHASE 1 — register every persisted tool's METADATA into !tool-defs. Pure
   metadata: no sandbox, no tool palette, no agent, so this runs at process
   BOOT (see agent.common.boot) and is what makes a user tool discoverable to
   `list-tools`, the sandbox binding pass and the CLI before any turn starts.

   Registering all names up front is also what lets phase 2 install bodies in
   any order — a body may compose a peer by its `user$tool$<name>` symbol and
   `.edn` file order is undefined.

   A tool registered here is NOT yet callable: its `__ut_<name>` body var is
   installed by `install-bodies!` at the first turn, when the agent's tool
   palette exists to eval it against. Returns the names registered."
  [& {:keys [dirs]}]
  (let [recs (read-persisted (tools-dir dirs))]
    (doseq [rec recs] (register! rec))
    (mapv :name recs)))

(defn install-bodies!
  "PHASE 2 — eval every persisted body into the tools sandbox, so the tools
   registered by `register-persisted!` become callable. Needs `extra-bindings`
   (the agent's `auto-tool-bindings`): SCI resolves symbols during analysis, so
   a body calling `(bash :command …)` cannot eval without the palette bound.
   That is the whole reason this is a separate phase from registration.

   Rolls a tool back OUT of !tool-defs if its body fails to eval, so the LLM is
   never shown a tool that cannot run. Returns the names installed."
  [& {:keys [dirs extra-bindings]}]
  (tools-sandbox extra-bindings)                  ;; ensure + refresh tool palette
  (let [recs (read-persisted (tools-dir dirs))]
    ;; Bind every peer `user$tool$<name>` symbol BEFORE installing any body:
    ;; a body may compose a peer and `.edn` file order is undefined.
    (doseq [rec recs] (bind-peer-symbol! (:name rec)))
    (->> recs
         (keep (fn [rec]
                 (try (install-body! (:name rec) (:body rec))
                      (:name rec)
                      (catch Exception e
                        (swap! tool/!tool-defs dissoc (tool-id (:name rec)))
                        ;; Name the FILE, not just the tool: this is the phase
                        ;; where SCI evaluates a body, so the failure is in
                        ;; source the user can open and fix.
                        (mulog/warn ::load-user-tool-failed
                                    :name (:name rec) :file (:file rec)
                                    :error (ex-message e))
                        nil))))
         vec)))

(defn load-user-tools!
  "Both phases, unguarded: register every persisted tool and install its body.
   Returns the names whose bodies loaded."
  [& {:keys [dirs extra-bindings]}]
  (register-persisted! :dirs dirs)
  (install-bodies! :dirs dirs :extra-bindings extra-bindings))

(defn ensure-registered!
  "Idempotent PHASE 1 for boot: register this project's persisted tool metadata
   the first time the dir is seen this process, and no-op thereafter. Returns
   the names registered (or nil when already registered)."
  [& {:keys [dirs]}]
  (let [dir (tools-dir dirs)]
    (when-not (contains? @!registered dir)
      (swap! !registered conj dir)
      (register-persisted! :dirs dirs))))

(defn ensure-loaded!
  "Idempotent session-boot loader: make this user/project's persisted tools
   CALLABLE the first time the dir is seen this process, and no-op thereafter.
   Safe to call on every turn.

   Runs `ensure-registered!` first, which no-ops when boot already registered
   the metadata and does the work when this process never booted through one of
   the `boot-registries!` entry points (a2a serve, ACP, tests). Returns the
   names installed (or nil when already loaded)."
  [& {:keys [dirs extra-bindings]}]
  (let [dir (tools-dir dirs)]
    (when-not (contains? @!loaded dir)
      (swap! !loaded conj dir)
      (ensure-registered! :dirs dirs)
      (install-bodies! :dirs dirs :extra-bindings extra-bindings))))

;; ============================================================================
;; Management (list / read / delete) — mirrors the skills$* command family
;; ============================================================================

(defn- current-dirs
  "Resolve dirs from the current agent session, falling back to init-dirs!.
   Uses requiring-resolve to avoid a static require cycle (sandbox-bindings,
   which this ns is required by, sits above core.config in the load graph)."
  []
  (or (when-let [a (some-> (requiring-resolve 'ai.brainyard.agent.core.protocol/*current-agent*)
                           deref)]
        (some-> (:!session a) deref
                ((or (requiring-resolve 'ai.brainyard.agent.core.session/get-session-config)
                     (constantly nil))
                 :dirs)))
      ((or (requiring-resolve 'ai.brainyard.agent.core.config/init-dirs!)
           (constantly {})))))

(defn- current-extra-bindings
  "Resolve the current agent's `auto-tool-bindings` — the full tool palette as
   direct symbols a tool body may compose ((bash :command …), (read-file :path …),
   (user$tool$peer :k v)). Runtime-resolved to avoid a static require cycle
   (sandbox-bindings requires this ns for registration). Returns {} when no
   agent is bound (e.g. in tests)."
  []
  (let [agent (some-> (requiring-resolve 'ai.brainyard.agent.core.protocol/*current-agent*)
                      deref)]
    ((requiring-resolve 'ai.brainyard.agent.common.sandbox-bindings/auto-tool-bindings)
     agent)))

(defn list-user-tools
  "Summaries of every registered user-defined tool, sorted by id."
  []
  (->> (vals @tool/!tool-defs)
       (filter #(get-in % [:meta :user-defined]))
       (mapv (fn [td]
               (let [m (:meta td)]
                 {:id           (name (:id m))
                  :description  (:description m)
                  :input-schema (:input-schema m)})))
       (sort-by :id)
       vec))

(defn read-user-tool
  "Full record for one user tool — `{:name :description :input-schema :body}` —
   read from the persisted metadata `.edn` + body `.clj` sidecar, falling back
   to registry metadata (without source) when neither file is present."
  [dirs name]
  (or (def-store/read-def (tools-dir dirs) name)
      (when (contains? @tool/!tool-defs (tool-id name))
        (-> (select-keys (:meta (get @tool/!tool-defs (tool-id name)))
                         [:description :input-schema])
            (assoc :name name :body nil :note "source not on disk")))
      {:error (str "no user tool named " (pr-str name))}))

(defn delete-user-tool!
  "Unregister a user tool and delete its persisted source. The orphaned
   `__ut_<name>` / `user$tool$<name>` sandbox vars are harmless (registry dispatch is
   gone) and clear on the next sandbox rebuild."
  [dirs name]
  (let [id (tool-id name)]
    (if (contains? @tool/!tool-defs id)
      (do
        (swap! tool/!tool-defs dissoc id)
        (def-store/delete-def! (tools-dir dirs) name)
        (mulog/info ::delete-user-tool :id id)
        {:deleted name})
      {:error (str "no user tool named " (pr-str name))})))

(defn- coerce-input-schema
  "Normalize a caller-supplied :input-schema into a Malli schema vector.

   Tool-call args arrive as JSON, which cannot express a keyword-headed vector,
   so the LLM passes the schema as an EDN STRING (e.g. \"[:map [:x :int]]\").
   In-process callers (tests, body composition) pass the real vector. This
   bridges both: nil/blank -> nil (define-tool defaults to [:map]), a vector
   passes through unchanged, a string is read as EDN. Throws ex-info with a
   clear message on unreadable EDN so the [:map ...] check downstream sees a
   value, not a parse crash."
  [v]
  (cond
    (nil? v)    nil
    (vector? v) v
    (string? v) (let [s (str/trim v)]
                  (when-not (str/blank? s)
                    (try (edn/read-string s)
                         (catch Exception e
                           (throw (ex-info (str "tool :input-schema is not readable EDN: "
                                                (ex-message e))
                                           {:input-schema v}))))))
    :else       (throw (ex-info "tool :input-schema must be an EDN string like \"[:map ...]\""
                                {:input-schema v}))))

(defcommand tool-agent$create
  "Author a reusable, PERSISTENT tool from Clojure source. :body is a string
   `(fn [args] ...)` taking one map; :input-schema is a Malli [:map ...] passed
   as an EDN string. The tool survives restarts, registers as `user$tool$<name>`
   (callable directly as a tool in the SAME turn it is created), and its body may
   compose other tools by their direct symbol, e.g. (bash :command …) or (user$tool$other :k v)."
  (fn [& {:as args}]
    (try
      (let [extra (current-extra-bindings)]
        (define-tool :name (:name args)
          :description (:description args)
          :input-schema (coerce-input-schema (:input-schema args))
          :body (:body args)
          :dirs (current-dirs)
          :extra-bindings extra))
      (catch Exception e {:error (str "tool-agent$create failed: " (.getMessage e))})))
  :input-schema  [:map
                  [:name        [:string {:desc "lowercase-kebab tool name (no user$tool$ prefix)"}]]
                  [:body        [:string {:desc "Clojure source: a `(fn [args] ...)` of one map"}]]
                  [:description {:optional true} [:string {:desc "one-line description"}]]
                  [:input-schema {:optional true :desc "Malli arg schema: a native vector (code channel) or an EDN string (tool-calls channel), e.g. [:map [:x :int]] (default [:map])"} ::acs/vector-object-arg]]
  :output-schema [:map
                  [:id        [:string {:desc "Registered tool id, e.g. user$tool$shout"}]]
                  [:name      [:string {:desc "Tool name"}]]
                  [:persisted [:string {:desc "Path the source was written to"}]]
                  [:error     [:string {:desc "Error if definition failed"}]]])

(defcommand tool-agent$list
  "List user-defined tools (authored via tool-agent$create). Returns id, description, schema."
  (fn [& _] {:tools (list-user-tools)})
  :input-schema  [:map]
  :output-schema [:map [:tools [:any {:desc "Vector of {:id :description :input-schema}"}]]])

(defcommand tool-agent$read
  "Read a user-defined tool's source + schema by name (without the user$tool$ prefix)."
  (fn [& {:as args}]
    (if (str/blank? (:name args))
      {:error "name is required"}
      (read-user-tool (current-dirs) (:name args))))
  :input-schema  [:map [:name [:string {:desc "User tool name, e.g. \"shout\" (no user$tool$ prefix)"}]]]
  :output-schema [:map
                  [:name         [:string {:desc "Tool name"}]]
                  [:description  [:string {:desc "Description"}]]
                  [:input-schema [:any {:desc "Malli [:map ...] schema"}]]
                  [:body         [:string {:desc "Clojure source `(fn [args] ...)`"}]]
                  [:error        [:string {:desc "Error if not found"}]]])

(defcommand tool-agent$delete
  "Delete a user-defined tool: unregister it and remove its persisted source."
  (fn [& {:as args}]
    (if (str/blank? (:name args))
      {:error "name is required"}
      (delete-user-tool! (current-dirs) (:name args))))
  :input-schema  [:map [:name [:string {:desc "User tool name to delete (no user$tool$ prefix)"}]]]
  :output-schema [:map
                  [:deleted [:string {:desc "Name of the deleted tool"}]]
                  [:error   [:string {:desc "Error if not found"}]]])

(defcommand tool-agent$validate
  "Dry-run a user-tool draft: parse + eval-smoke-test the body and check the
   schema/name in a THROWAWAY fork of the tools sandbox — persists nothing,
   registers nothing, mutates no live state. Use before tool-agent$create to iterate
   safely. Optionally pass :sample (an args map) to run the body once and see
   its result. Mirrors tool-agent$create's arg names so a validated draft promotes
   to a create call with no reshaping. Returns a structured report (never throws)."
  (fn [& {:as args}]
    (try
      (let [{:keys [name body sample]} args
            input-schema (coerce-input-schema (:input-schema args))
            name-ok    (when name (boolean (re-matches tool-name-re name)))
            collision  (boolean (and name (contains? @tool/!tool-defs (tool-id name))))
            schema-ok  (or (nil? input-schema)
                           (and (vector? input-schema) (= :map (first input-schema))))
            ;; Fork the LIVE tools sandbox WITH the agent's tool palette bound,
            ;; so a draft body that composes (read-file :path …) / (bash :command …) /
            ;; (user$tool$peer :k v) evals here exactly as it would under tool-agent$create.
            ;; The fork is discarded on return — nothing leaks into the live sandbox.
            fork       (sb/fork-sandbox (tools-sandbox (current-extra-bindings)))
            evald      (when (string? body)
                         (sb/eval-code fork (str "(def __probe " body ")")))
            body-ok    (boolean (and (string? body) (nil? (:error evald))))
            sample-res (when (and body-ok (map? sample))
                         (sb/set-var! fork 'args sample)
                         (let [r (sb/eval-code fork "(__probe args)")]
                           (if (:error r) {:error (:error r)} (:result r))))
            errors     (cond-> []
                         (false? name-ok)   (conj "name must match ^[a-z][a-z0-9-]*$")
                         (false? schema-ok) (conj ":input-schema must be a [:map ...] schema")
                         (not (string? body)) (conj ":body is required (a string `(fn [args] ...)`)")
                         (and (string? body) (not body-ok))
                         (conj (str "body failed to eval: " (:error evald))))]
        (cond-> {:valid     (empty? errors)
                 :collision collision
                 :schema-ok schema-ok
                 :body-ok   body-ok
                 :errors    errors}
          (some? name-ok) (assoc :name-ok name-ok)
          (map? sample)   (assoc :sample-result sample-res)))
      (catch Exception e
        {:valid false :collision false :schema-ok false :body-ok false
         :errors [(str "tool-agent$validate failed: " (.getMessage e))]})))
  :input-schema  [:map
                  [:body         [:string {:desc "Clojure source: a `(fn [args] ...)` of one map"}]]
                  [:name         {:optional true} [:string {:desc "Proposed name; enables name + collision check"}]]
                  [:input-schema {:optional true :desc "Malli arg schema to validate: a native vector (code channel) or an EDN string (tool-calls channel), e.g. [:map [:x :int]]"} ::acs/vector-object-arg]
                  [:sample       {:optional true} [:map {:desc "Example args map; if given, body is run once on it"}]]]
  :output-schema [:map
                  [:valid         [:boolean {:desc "True iff all checks passed"}]]
                  [:name-ok       {:optional true} [:boolean {:desc "Name matches ^[a-z][a-z0-9-]*$"}]]
                  [:collision     [:boolean {:desc "A tool with this name already exists (create would overwrite)"}]]
                  [:schema-ok     [:boolean {:desc "input-schema is a well-formed [:map ...]"}]]
                  [:body-ok       [:boolean {:desc "Body parses and evals"}]]
                  [:sample-result {:optional true} [:any {:desc "Result of running the body on :sample"}]]
                  [:errors        [:any {:desc "Vector of human-readable failure lines"}]]])

(def tools-commands
  "All user-tool management commands, for binding into tool-agent. Mirrors
   `skills/skills-commands`."
  [#'tool-agent$create #'tool-agent$validate #'tool-agent$list #'tool-agent$read #'tool-agent$delete])
