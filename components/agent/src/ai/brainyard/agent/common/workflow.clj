;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: Apache-2.0
;; Licensed under the Apache License, Version 2.0 (the "License"); you may
;; not use this file except in compliance with the License. See LICENSE at
;; the repository root and CONTRIBUTING.md for contribution terms.

(ns ai.brainyard.agent.common.workflow
  "Workflow-agent quality-of-life helpers — mechanical defcommands that compress
   the dossier-bootstrap / template-load / append-log / update-stage /
   update-acceptance / write-verdict / index-append flow described in
   docs/workflow-agent-design.md §9.

   Each helper is a `defcommand` so it surfaces in the unified tool registry
   and is auto-bound into the SCI sandbox (callable as `(workflow$id ...)` in
   a clojure fence). Workflow-agent works without them — the agent instruction
   has an inline `mkdir + write-file` skeleton — but binding them shrinks the
   prompt because the dossier mechanics no longer have to be inlined every
   iteration.

   Storage layout (per docs/workflow-agent-design.md §5):
     .brainyard/agents/workflow-agent/INDEX.md          — newest-first registry
     .brainyard/agents/workflow-agent/<workflow-id>/
       dossier.md       — durable workflow context (hand-rolled YAML frontmatter)
       purpose.md       — immutable after iteration 1
       acceptance.md    — workflow-level criteria
       stages.edn       — current stage roster + status (source of truth; pure EDN)
       findings.log     — append-only NDJSON
       template.edn     — copy of the source template (for diff/audit)
       verdict.md       — written at termination
       artifacts/       — pointers (symlinks) into other agents' outputs

   Template lookup chain (workflow$list-templates / workflow$load-template):
     1. Project-local: <project-root>/.brainyard/workflows/*.edn
     2. User-local:    ~/.brainyard/workflows/*.edn
     3. Built-in:      classpath:workflows/*.edn  (from resources/workflows/)

   `workflow$install-starters` copies the built-in starters to project-local on
   first run; later runs are no-ops. This matches `.brainyard/skills/` UX.

   `workflow$summarize-log` (LLM-backed roll-up of findings.log into a
   refreshed dossier ## Stage progress) is intentionally NOT in this file. The
   agent calls `query$llm` directly with the log content as `:sub-context` —
   keeping the helper layer mechanical and side-effect-only. Mirrors the
   research.clj decision."
  (:require [clojure.pprint]
            [ai.brainyard.agent.common.guard :as guard]
            [ai.brainyard.agent.core.config :as config]
            [ai.brainyard.agent.core.hooks :as hooks]
            [ai.brainyard.agent.core.protocol :as proto]
            [ai.brainyard.agent.core.tool :refer [defcommand]]
            [ai.brainyard.mulog.interface :as mulog]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]))

;; ============================================================================
;; Constants
;; ============================================================================

(def ^:private results-base ".brainyard/agents/workflow-agent")
(def ^:private index-rel (str results-base "/INDEX.md"))
(def ^:private templates-rel ".brainyard/workflows")
(def ^:private builtin-templates-cp "workflows")

(def ^:private slug-stopwords
  #{"a" "an" "the" "is" "are" "and" "or" "of" "in" "on" "at" "to" "for"
    "by" "with" "from" "as" "but" "if" "then" "than" "so"
    "what" "where" "when" "who" "whom" "why" "how" "which"
    "do" "does" "did" "can" "could" "would" "should" "shall" "will"
    "this" "that" "these" "those" "it" "its" "we" "they" "you" "i"
    "be" "been" "being" "was" "were" "have" "has" "had"
    "run" "runs" "running" "ship" "ships" "shipped" "shipping"})

(def ^:private valid-workflow-statuses
  #{:in-progress :achieved :partial :abandoned})

(def ^:private valid-criterion-statuses
  #{:open :satisfied :partial :descoped :contradicted})

(def ^:private valid-stage-statuses
  #{:pending :in-progress :satisfied :failed :skipped :abandoned})

(def ^:private valid-hitl-modes
  #{:auto :gates :checkpoint :co-pilot :step})

;; ============================================================================
;; Time formatters
;; ============================================================================

(defn- now-iso
  []
  (str (java.time.Instant/now)))

(defn- now-yyyy-mm-dd-hh-mm
  []
  (-> (subs (now-iso) 0 16)
      (str/replace "T" " ")))

;; ============================================================================
;; Slugify — mirrors research$slug
;; ============================================================================

(defn- slugify
  [text max-chars]
  (let [tokens (-> (str text)
                   str/lower-case
                   (str/replace #"[^a-z0-9]+" "-")
                   (str/split #"-"))
        kept   (->> tokens
                    (remove #(or (str/blank? %) (slug-stopwords %)))
                    seq)
        joined (if kept (str/join "-" kept) "workflow")]
    (subs joined 0 (min max-chars (count joined)))))

;; ============================================================================
;; YAML emission helpers
;; ============================================================================

(defn- yaml-string
  [s]
  (str "\""
       (-> (str s)
           (str/replace "\\" "\\\\")
           (str/replace "\"" "\\\""))
       "\""))

(def ^:private bareword-re #"^[A-Za-z0-9_./:-]+$")

(defn- yaml-flow-vector
  [xs]
  (str "["
       (str/join ", " (map (fn [x]
                             (cond
                               (keyword? x) (name x)
                               (string? x) (if (re-matches bareword-re x)
                                             x
                                             (yaml-string x))
                               :else (str x)))
                           xs))
       "]"))

(defn- yaml-folded-scalar
  "Emit `>` folded scalar, collapsing internal whitespace to one line indented
   under the key."
  [s]
  (let [collapsed (-> (str s) (str/replace #"\s+" " ") str/trim)]
    (str ">\n  " collapsed)))

(defn- yaml-acceptance-block
  "Emit
     acceptance:
       - id: a1
         text: \"...\"
         status: open
   for the dossier frontmatter."
  [items]
  (str/join ""
            (for [{:keys [id text status]} items]
              (str "  - id: " (name id) "\n"
                   "    text: " (yaml-string text) "\n"
                   "    status: " (name (or status :open)) "\n"))))

(defn- yaml-stages-summary-block
  "Emit a compact stages summary in dossier.md frontmatter. The authoritative
   roster lives in stages.edn; this block is a human-readable mirror.

   Format:
     stages:
       - id: research-feasibility
         agent: research-agent
         status: pending
   Optional :artifact, :plan-slug, :todo-slug omitted when nil."
  [stages]
  (str/join ""
            (for [{:keys [id agent status artifact plan-slug todo-slug]} stages]
              (str "  - id: " (name id) "\n"
                   "    agent: " (name agent) "\n"
                   "    status: " (name (or status :pending)) "\n"
                   (when artifact  (str "    artifact: " (yaml-string artifact) "\n"))
                   (when plan-slug (str "    plan_slug: " (yaml-string plan-slug) "\n"))
                   (when todo-slug (str "    todo_slug: " (yaml-string todo-slug) "\n"))))))

;; ============================================================================
;; Dossier composition
;; ============================================================================

(defn- build-dossier-md
  "Compose the dossier.md from a fully-populated map. Frontmatter shape
   matches docs/workflow-agent-design.md §5.2."
  [{:keys [id template-id created last-iteration status purpose acceptance
           stages hitl-mode artifacts]
    :or   {last-iteration 1
           status         :in-progress
           hitl-mode      :gates
           artifacts      {}}}]
  (let [{:keys [research_dossier plan_slug todo_slug evals]
         :or   {evals []}} artifacts]
    (str "---\n"
         "workflow_id: " id "\n"
         "workflow_template: " (if template-id (name template-id) "ad-hoc") "\n"
         "created: " (or created (now-iso)) "\n"
         "last_iteration: " last-iteration "\n"
         "status: " (name status) "\n"
         "hitl_mode: " (name hitl-mode) "\n"
         "purpose: " (yaml-folded-scalar purpose) "\n"
         "acceptance:\n"
         (yaml-acceptance-block acceptance)
         "stages:\n"
         (yaml-stages-summary-block stages)
         "artifacts:\n"
         "  research_dossier: " (if research_dossier (yaml-string research_dossier) "null") "\n"
         "  plan_slug: " (if plan_slug (yaml-string plan_slug) "null") "\n"
         "  todo_slug: " (if todo_slug (yaml-string todo_slug) "null") "\n"
         "  evals: " (yaml-flow-vector evals) "\n"
         "calls_log: findings.log\n"
         "stages_roster: stages.edn\n"
         "template: template.edn\n"
         "---\n\n"
         "## Purpose\n" purpose "\n\n"
         "## Acceptance criteria (workflow-level)\n"
         (str/join "" (for [{:keys [id text status]} acceptance]
                        (str "- **" (name id) "** [" (name (or status :open)) "]: " text "\n")))
         "\n## Stage progress\n"
         "_(populated as stages run — see findings.log for the raw log)_\n")))

;; ============================================================================
;; workflow$id — deterministic kebab-case workflow id
;; ============================================================================

(defcommand workflow$id
  "Deterministic kebab-case workflow id. Shape:
     <template-id>--<question-slug>   when :template is named
     <question-slug>                   when :template is :ad-hoc / nil

   Drops a small stopword set; caps question slug at `:max-chars` (default
   60). Same template+question always yields the same id — re-runs land in
   the same dossier.

   Returns `{:slug \"<id>\"}`. Empty/all-stopwords question yields
   `\"workflow\"` (or `\"<template>--workflow\"`)."
  (fn [& {:keys [template question max-chars]
          :or   {max-chars 60}}]
    (cond
      (not (string? question))
      {:error ":question is required (string)"}

      (or (not (integer? max-chars)) (<= max-chars 0))
      {:error ":max-chars must be a positive integer"}

      :else
      (let [q-slug   (slugify question max-chars)
            tmpl-id  (cond
                       (nil? template)              nil
                       (= :ad-hoc template)         nil
                       (keyword? template)          (name template)
                       (string? template)           template
                       :else                        nil)]
        {:slug (if tmpl-id
                 (str tmpl-id "--" q-slug)
                 q-slug)})))
  :input-schema  [:map
                  [:template  {:optional true} [:any    {:desc "Template id (keyword like :feature-launch, string, or :ad-hoc / nil)"}]]
                  [:question  [:string {:desc "User question to slugify"}]]
                  [:max-chars {:optional true} [:int    {:desc "Cap on question-slug length (default 60)"}]]]
  :output-schema [:map
                  [:slug  [:string {:desc "Kebab-case workflow id"}]]
                  [:error {:optional true} [:string {:desc "Error if validation failed"}]]])

;; ============================================================================
;; Template discovery — list + load
;; ============================================================================

(defn- list-edn-files
  "List `.edn` files inside `dir`. Returns a vector of File objects; empty
   when dir is missing or non-directory."
  [^java.io.File dir]
  (if (and (some? dir) (.isDirectory dir))
    (vec (filter #(and (.isFile ^java.io.File %)
                       (str/ends-with? (.getName ^java.io.File %) ".edn"))
                 (.listFiles dir)))
    []))

(defn- list-classpath-templates
  "Enumerate built-in templates under `<classpath>/workflows/*.edn`. Returns
   a vector of `{:id :name :description :source :resource}` maps. Source is
   `:built-in`. The agent does NOT read these directly — `workflow$load-template`
   resolves the resource on demand."
  []
  (try
    (when-let [url (io/resource builtin-templates-cp)]
      (let [conn (.openConnection url)]
        (.setUseCaches conn false)
        (cond
          ;; Plain directory on classpath (dev / source tree)
          (= "file" (.getProtocol url))
          (let [dir (io/file url)]
            (mapv (fn [^java.io.File f]
                    (let [base (.getName f)
                          id   (str/replace base #"\.edn$" "")]
                      {:id          id
                       :name        id
                       :description nil
                       :source      :built-in
                       :resource    (str builtin-templates-cp "/" base)}))
                  (list-edn-files dir)))
          ;; JAR-packed resources (shipped `by` binary / uberjar): enumerate
          ;; the jar's entries under `workflows/` so install-starters isn't a
          ;; silent no-op in the packaged binary. Specific-resource reads
          ;; already work from a jar via io/resource — only enumeration needs
          ;; the JarFile. No extra dep: JarURLConnection is JDK-native.
          (= "jar" (.getProtocol url))
          (let [jar    (.getJarFile ^java.net.JarURLConnection conn)
                prefix (str builtin-templates-cp "/")]
            (->> (enumeration-seq (.entries jar))
                 (map #(.getName ^java.util.jar.JarEntry %))
                 (filter #(and (str/starts-with? % prefix)
                               (str/ends-with? % ".edn")))
                 (mapv (fn [^String entry]
                         (let [base (subs entry (count prefix))
                               id   (str/replace base #"\.edn$" "")]
                           {:id          id
                            :name        id
                            :description nil
                            :source      :built-in
                            :resource    (str builtin-templates-cp "/" base)})))))

          ;; Unknown protocol: cannot enumerate. Specific templates still
          ;; load by id via workflow$load-template (io/resource).
          :else [])))
    (catch Throwable _ [])))

(defn- read-template-meta
  "Read just the metadata fields off an EDN template file (id/name/desc).
   Returns nil on any read failure (silent — list-templates is best-effort)."
  [^java.io.File f source]
  (try
    (let [edn (edn/read-string (slurp f))]
      (when (map? edn)
        {:id          (or (some-> (:workflow/id edn) name)
                          (str/replace (.getName f) #"\.edn$" ""))
         :name        (or (:workflow/name edn)
                          (str/replace (.getName f) #"\.edn$" ""))
         :description (:workflow/description edn)
         :source      source
         :path        (.getPath f)}))
    (catch Throwable _ nil)))

(defcommand workflow$list-templates
  "Enumerate workflow templates from the three lookup locations:
     1. Project-local: <project-root>/.brainyard/workflows/*.edn
     2. User-local:    ~/.brainyard/workflows/*.edn
     3. Built-in:      classpath:workflows/*.edn  (resources/workflows/)

   Returns `{:templates [{:id … :name … :description … :source :project|:user|:built-in
                          :path …} …]}`. When the same id appears in multiple
   locations, all entries are returned (caller decides precedence — typically
   project > user > built-in via workflow$load-template's resolution order).

   Args:
     :base-dir — project root (default: resolved)
     :include-built-in? — include classpath starters (default true)"
  (fn [& {:keys [base-dir include-built-in?]
          :or   {base-dir          (config/project-dir)
                 include-built-in? true}}]
    (let [project-dir (io/file base-dir templates-rel)
          home        (System/getProperty "user.home")
          user-dir    (when home (io/file home templates-rel))
          project (keep #(read-template-meta % :project) (list-edn-files project-dir))
          user    (keep #(read-template-meta % :user)    (list-edn-files user-dir))
          builtin (when include-built-in? (list-classpath-templates))]
      {:templates (vec (concat project user (or builtin [])))}))
  :input-schema  [:map
                  [:base-dir          {:optional true} [:string  {:desc "Project root (default: resolved)"}]]
                  [:include-built-in? {:optional true} [:boolean {:desc "Include classpath starters (default true)"}]]]
  :output-schema [:map
                  [:templates [:vector {:desc "Vector of template descriptor maps"} :any]]])

(defn- resolve-template-source
  "Resolve a template id (or path) to a readable source. Order:
     1. explicit :path arg
     2. project-local .brainyard/workflows/<id>.edn
     3. user-local    ~/.brainyard/workflows/<id>.edn
     4. built-in      classpath:workflows/<id>.edn
   Returns a String for slurpable inputs (file path or resource path that
   `io/resource` can open), tagged with :source. On miss returns nil."
  [{:keys [id path base-dir]}]
  (let [home (System/getProperty "user.home")]
    (cond
      path
      (let [f (io/file path)]
        (when (.isFile f) {:source :explicit :reader f :display-path path}))

      :else
      (let [id-name (cond
                      (keyword? id) (name id)
                      (string? id)  id
                      :else         nil)
            file-name (str id-name ".edn")
            project-f (io/file base-dir templates-rel file-name)
            user-f    (when home (io/file home templates-rel file-name))
            cp-path   (str builtin-templates-cp "/" file-name)
            cp-url    (io/resource cp-path)]
        (cond
          (and id-name (.isFile project-f))
          {:source :project :reader project-f :display-path (.getPath project-f)}

          (and id-name user-f (.isFile ^java.io.File user-f))
          {:source :user :reader user-f :display-path (.getPath user-f)}

          (and id-name cp-url)
          {:source :built-in :reader cp-url :display-path (str "classpath:" cp-path)}

          :else nil)))))

(defn- validate-template
  "Lightweight schema sanity check. Returns nil when OK, or a string error."
  [t]
  (cond
    (not (map? t))                       "template is not a map"
    (not (keyword? (:workflow/id t)))    ":workflow/id must be a keyword"
    (not (string? (:workflow/name t)))   ":workflow/name must be a string"
    (not (vector? (:stages t)))          ":stages must be a vector"
    (not (every? map? (:stages t)))      ":stages entries must be maps"
    (not (vector? (:acceptance t)))      ":acceptance must be a vector"
    (not (every? map? (:acceptance t)))  ":acceptance entries must be maps"
    :else                                nil))

(defcommand workflow$load-template
  "Load and validate a workflow template. Resolution order:
     1. explicit :path arg          (relative or absolute file path)
     2. project-local <root>/.brainyard/workflows/<id>.edn
     3. user-local    ~/.brainyard/workflows/<id>.edn
     4. built-in      classpath:workflows/<id>.edn

   Args:
     :id       — template id (keyword or string, e.g. :feature-launch)
     :path     — explicit path (alternative to :id)
     :base-dir — project root (default: resolved)

   Returns `{:template <edn-map> :source :project|:user|:built-in|:explicit
             :path \"<display-path>\"}` on success, or `{:error \"…\"}`."
  (fn [& {:keys [id path base-dir]
          :or   {base-dir (config/project-dir)}}]
    (cond
      (and (nil? id) (nil? path))
      {:error "supply :id or :path"}

      :else
      (if-let [src (resolve-template-source {:id id :path path :base-dir base-dir})]
        (try
          (let [{:keys [source reader display-path]} src
                content (slurp reader)
                tmpl    (edn/read-string content)]
            (if-let [err (validate-template tmpl)]
              {:error (str "invalid template at " display-path ": " err)}
              {:template tmpl
               :source   source
               :path     display-path}))
          (catch Throwable t
            {:error (str "failed to read template: " (.getMessage t))}))
        {:error (str "template not found: "
                     (if path
                       path
                       (str (some-> id name) " (looked in project, user, built-in)")))})))
  :input-schema  [:map
                  [:id       {:optional true} [:any    {:desc "Template id (keyword like :feature-launch or string)"}]]
                  [:path     {:optional true} [:string {:desc "Explicit path (alternative to :id)"}]]
                  [:base-dir {:optional true} [:string {:desc "Project root (default: resolved)"}]]]
  :output-schema [:map
                  [:template {:optional true} [:map    {:desc "Loaded EDN template"}]]
                  [:source   {:optional true} [:keyword {:desc ":project | :user | :built-in | :explicit"}]]
                  [:path     {:optional true} [:string  {:desc "Display path of the loaded template"}]]
                  [:error    {:optional true} [:string  {:desc "Error if not found or invalid"}]]])

;; ============================================================================
;; workflow$install-starters — copy built-in starters to project-local
;; ============================================================================

(defcommand workflow$install-starters
  "Copy built-in workflow starters from classpath to <project>/.brainyard/
   workflows/. Idempotent: existing files are NOT overwritten (returns
   `:skipped` for each). Useful on first-run bootstrap when the user hasn't
   authored any project-local templates yet.

   Args:
     :base-dir   — project root (default: resolved)
     :overwrite? — overwrite existing files (default false)

   Returns `{:installed [<id> …] :skipped [<id> …] :dir <path>}`."
  (fn [& {:keys [base-dir overwrite?]
          :or   {base-dir   (config/project-dir)
                 overwrite? false}}]
    (let [dest-dir (io/file base-dir templates-rel)
          _        (.mkdirs dest-dir)
          builtins (list-classpath-templates)
          results  (reduce
                    (fn [acc {:keys [id resource]}]
                      (let [url      (io/resource resource)
                            dest-f   (io/file dest-dir (str id ".edn"))
                            exists?  (.isFile dest-f)]
                        (cond
                          (nil? url)
                          (update acc :skipped conj id)

                          (and exists? (not overwrite?))
                          (update acc :skipped conj id)

                          :else
                          (try
                            (spit dest-f (slurp url))
                            (update acc :installed conj id)
                            (catch Throwable _
                              (update acc :skipped conj id))))))
                    {:installed [] :skipped []}
                    builtins)]
      (mulog/log ::workflow.install-starters
                 :installed (:installed results)
                 :skipped (:skipped results))
      (assoc results :dir (.getPath dest-dir))))
  :input-schema  [:map
                  [:base-dir   {:optional true} [:string  {:desc "Project root (default: resolved)"}]]
                  [:overwrite? {:optional true} [:boolean {:desc "Overwrite existing files (default false)"}]]]
  :output-schema [:map
                  [:installed [:vector  {:desc "Template ids newly written"} :string]]
                  [:skipped   [:vector  {:desc "Template ids skipped (already exist or unreadable)"} :string]]
                  [:dir       [:string  {:desc "Destination directory path"}]]])

;; ============================================================================
;; Frontmatter parsing (shared by workflow$resume? and write-verdict)
;; ============================================================================

(defn- read-frontmatter-lines
  "Read lines from the file until the second `---` (frontmatter close).
   Returns the inner lines, or nil if the file doesn't start with `---`."
  [^java.io.File file]
  (with-open [r (io/reader file)]
    (let [reader (java.io.BufferedReader. r)
          first-line (.readLine reader)]
      (when (= "---" first-line)
        (loop [acc []]
          (let [ln (.readLine reader)]
            (cond
              (nil? ln)    nil
              (= "---" ln) acc
              :else        (recur (conj acc ln)))))))))

(defn- extract-flat
  "Targeted regex for `key: <value>` lines (single line, no nested blocks)."
  [lines key]
  (some (fn [ln]
          (when-let [[_ v] (re-matches (re-pattern (str "^" key ":\\s*(.*)$")) ln)]
            (let [v (str/trim v)]
              (cond
                (and (str/starts-with? v "\"") (str/ends-with? v "\""))
                (subs v 1 (dec (count v)))
                (= "null" v) nil
                :else v))))
        lines))

(defn- extract-acceptance-state
  "Parse the `acceptance:` block out of frontmatter lines. Returns a map of
   `{<id-keyword> <status-keyword>}`."
  [lines]
  (loop [ls    lines
         in?   false
         pending {}
         acc   {}]
    (if (empty? ls)
      acc
      (let [ln (first ls)
            r  (rest ls)]
        (cond
          (re-matches #"^acceptance:\s*$" ln)
          (recur r true {} acc)

          (and in? (re-matches #"^[a-z_]+:.*$" ln))
          (if-let [pid (:id pending)]
            (assoc acc (keyword pid) (or (some-> (:status pending) keyword) :open))
            acc)

          (and in? (re-matches #"^\s*- id:\s*(\S+)\s*$" ln))
          (let [[_ id-str] (re-matches #"^\s*- id:\s*(\S+)\s*$" ln)
                acc' (if-let [pid (:id pending)]
                       (assoc acc (keyword pid) (or (some-> (:status pending) keyword) :open))
                       acc)]
            (recur r true {:id id-str} acc'))

          (and in? (re-matches #"^\s+status:\s*(\S+)\s*$" ln))
          (let [[_ s] (re-matches #"^\s+status:\s*(\S+)\s*$" ln)]
            (recur r true (assoc pending :status s) acc))

          (and in? (re-matches #"^\s+text:.*$" ln))
          (recur r true pending acc)

          :else
          (if (and in? (empty? r))
            (if-let [pid (:id pending)]
              (assoc acc (keyword pid) (or (some-> (:status pending) keyword) :open))
              acc)
            (recur r in? pending acc)))))))

;; ============================================================================
;; stages.edn read/write — pure EDN, much simpler than frontmatter regexing
;; ============================================================================

(defn- read-stages-edn
  "Read stages.edn. Returns the parsed map, or nil if missing/invalid."
  [^java.io.File file]
  (when (.isFile file)
    (try
      (edn/read-string (slurp file))
      (catch Throwable _ nil))))

(defn- write-stages-edn
  "Write stages.edn (pretty-printed for diff-ability)."
  [^java.io.File file data]
  (spit file (with-out-str (clojure.pprint/pprint data))))

(defn- stages-of [stages-map]
  (or (:stages stages-map) []))

;; ============================================================================
;; workflow$resume? — cheap probe
;; ============================================================================

(defcommand workflow$resume?
  "Cheap probe: does a workflow dossier exist for this id, and what is its
   current state? Reads dossier.md frontmatter + stages.edn (no body, no
   findings.log walk) so it's safe to call on every iteration's bootstrap
   gate.

   Returns `{:exists? false}` if no dossier exists. If one exists, returns:
     {:exists? true
      :status :keyword
      :last-iteration int
      :hitl-mode :keyword
      :acceptance-state {<criterion-id-kw> <status-kw>}
      :pending-stages [<stage-id-kw> …]   ; status not in #{:satisfied :skipped :abandoned}
      :stage-count int
      :n-pending int}

   `:base-dir` defaults to the project root (git ancestor)."
  (fn [& {:keys [id base-dir]
          :or   {base-dir (config/project-dir)}}]
    (cond
      (not (string? id))
      {:error ":id is required (string)"}

      :else
      (let [dossier (io/file base-dir results-base id "dossier.md")]
        (if-not (.isFile dossier)
          {:exists? false}
          (let [lines (read-frontmatter-lines dossier)]
            (if (nil? lines)
              {:exists? false :error "dossier.md present but lacks frontmatter"}
              (let [status     (some-> (extract-flat lines "status") keyword)
                    last-i     (some-> (extract-flat lines "last_iteration")
                                       (str)
                                       (Integer/parseInt))
                    hitl       (some-> (extract-flat lines "hitl_mode") keyword)
                    accept     (extract-acceptance-state lines)
                    stages-edn (read-stages-edn
                                (io/file base-dir results-base id "stages.edn"))
                    stages     (stages-of stages-edn)
                    pending    (mapv #(let [sid (:id %)]
                                        (cond
                                          (keyword? sid) sid
                                          (string? sid)  (keyword sid)
                                          :else          (keyword (str sid))))
                                     (remove #(contains? #{:satisfied :skipped :abandoned}
                                                         (:status %))
                                             stages))]
                {:exists?          true
                 :status           (or status :in-progress)
                 :last-iteration   (or last-i 1)
                 :hitl-mode        (or hitl :gates)
                 :acceptance-state accept
                 :pending-stages   pending
                 :stage-count      (count stages)
                 :n-pending        (count pending)})))))))
  :input-schema  [:map
                  [:id       [:string {:desc "Workflow id"}]]
                  [:base-dir {:optional true} [:string {:desc "Working directory (default: project root)"}]]]
  :output-schema [:map
                  [:exists?          [:boolean {:desc "true if the dossier directory exists with a dossier.md"}]]
                  [:status           {:optional true} [:keyword {:desc "Overall workflow status"}]]
                  [:last-iteration   {:optional true} [:int     {:desc "last_iteration from frontmatter"}]]
                  [:hitl-mode        {:optional true} [:keyword {:desc "HITL mode"}]]
                  [:acceptance-state {:optional true} [:map     {:desc "{<criterion-id-kw> <status-kw>}"}]]
                  [:pending-stages   {:optional true} [:vector  {:desc "Stage ids with status not in {satisfied skipped abandoned}"} :keyword]]
                  [:stage-count      {:optional true} [:int     {:desc "Total stages"}]]
                  [:n-pending        {:optional true} [:int     {:desc "Count of pending stages"}]]
                  [:error            {:optional true} [:string  {:desc "Error if validation failed"}]]])

;; ============================================================================
;; workflow$bootstrap — create dossier directory + files (idempotent)
;; ============================================================================

(defn- stage-from-template
  "Normalize a template stage map to the stages.edn shape. Defaults `:status`
   to `:pending` and `:attempts` to 0. Templates use `:recommended-agent`; the
   live stages.edn shape uses `:agent` — copy the former into the latter when
   `:agent` is absent. Keys otherwise preserved verbatim."
  [s]
  (-> s
      (update :id #(cond (keyword? %) % (string? %) (keyword %) :else %))
      (update :status #(or % :pending))
      (update :attempts #(or % 0))
      (#(if (and (nil? (:agent %)) (some? (:recommended-agent %)))
          (assoc % :agent (let [a (:recommended-agent %)]
                            (cond (keyword? a) a
                                  (string? a)  (keyword a)
                                  :else        a)))
          %))))

(defn- compose-stages-edn
  "Build stages.edn from a template (or an explicit stage vector for :ad-hoc).
   Adds workflow / template / created bookkeeping."
  [{:keys [workflow-id template-id stages]}]
  {:workflow/id     workflow-id
   :template-id     (or template-id :ad-hoc)
   :created         (now-iso)
   :stages          (mapv stage-from-template stages)})

(defcommand workflow$bootstrap
  "Create the workflow dossier directory and write the baseline files for a
   new workflow run. Idempotent: existing directory returns
   `{:exists? true :dir … :dossier-path …}`.

   Files created:
     purpose.md, acceptance.md, stages.edn, dossier.md, findings.log,
     template.edn (if :template-path supplied or :template-edn given),
     artifacts/ (empty directory)

   Args:
     :id            — workflow id (from workflow$id)
     :purpose       — verbatim purpose string
     :acceptance    — vector of `{:id :text :status}` maps (status default :open)
     :stages        — vector of stage maps (from template :stages OR a hand-
                      drafted list for :ad-hoc). Each stage map needs at
                      least :id and :agent. :status defaults to :pending.
     :template-id   — keyword (e.g. :feature-launch) or :ad-hoc
     :template-path — path to the source template.edn (for audit copy)
     :template-edn  — alternative: the full template map (skips file read)
     :hitl-mode     — :auto | :gates | :checkpoint | :co-pilot | :step
                      (default :gates). Recorded in the dossier and honored by
                      the agent as gate discipline — it is NOT a code-enforced
                      safety interlock (no hook blocks tool calls per mode).
     :base-dir      — working directory (default: project root)

   Returns `{:dir <path> :dossier-path <path> :stages-path <path>}` on fresh
   create, or `{:exists? true …}` if already bootstrapped."
  (fn [& {:keys [id purpose acceptance stages template-id template-path
                 template-edn hitl-mode base-dir]
          :or   {base-dir  (config/project-dir)
                 hitl-mode :gates}}]
    (let [hitl-kw (cond
                    (keyword? hitl-mode) hitl-mode
                    (string? hitl-mode)  (keyword hitl-mode)
                    :else                :gates)]
      (cond
        (not (string? id))             {:error ":id is required (string)"}
        (not (string? purpose))        {:error ":purpose is required (string)"}
        (not (sequential? acceptance)) {:error ":acceptance must be a vector of {:id :text :status} maps"}
        (not (sequential? stages))     {:error ":stages must be a vector of stage maps"}
        (not (valid-hitl-modes hitl-kw)) {:error (str ":hitl-mode must be one of "
                                                      (sort (map name valid-hitl-modes)))}
        (guard/content-violation (str purpose "\n" (pr-str acceptance)))
        (guard/content-violation (str purpose "\n" (pr-str acceptance)))
        :else
        (let [dir         (io/file base-dir results-base id)
              dir-rel     (str results-base "/" id)
              dossier-rel (str dir-rel "/dossier.md")
              stages-rel  (str dir-rel "/stages.edn")]
          (if (.isDirectory dir)
            (do
              (mulog/log ::workflow.bootstrap-skip
                         :id id :reason :already-exists)
              {:exists? true :dir dir-rel :dossier-path dossier-rel :stages-path stages-rel})
            (let [_ (.mkdirs (io/file dir "artifacts"))
                  acceptance-norm (mapv (fn [{:keys [id text status]}]
                                          {:id     (if (keyword? id) (name id) (str id))
                                           :text   text
                                           :status (or status :open)})
                                        acceptance)
                  stages-norm     (mapv stage-from-template stages)
                  template-kw     (cond
                                    (keyword? template-id) template-id
                                    (string? template-id)  (keyword template-id)
                                    :else                  :ad-hoc)
                  purpose-md      (str purpose "\n")
                  acceptance-md   (str "# Acceptance criteria (workflow-level)\n\n"
                                       (str/join ""
                                                 (for [{:keys [id text status]} acceptance-norm]
                                                   (str "- **" (name id) "** ["
                                                        (name (or status :open)) "]: " text "\n"))))
                  stages-data     (compose-stages-edn
                                   {:workflow-id id
                                    :template-id template-kw
                                    :stages      stages-norm})
                  dossier-md      (build-dossier-md
                                   {:id             id
                                    :template-id    template-kw
                                    :created        (now-iso)
                                    :last-iteration 1
                                    :status         :in-progress
                                    :hitl-mode      hitl-kw
                                    :purpose        purpose
                                    :acceptance     acceptance-norm
                                    :stages         stages-norm
                                    :artifacts      {}})]
              (spit (io/file dir "purpose.md") purpose-md)
              (spit (io/file dir "acceptance.md") acceptance-md)
              (write-stages-edn (io/file dir "stages.edn") stages-data)
              (spit (io/file dir "dossier.md") dossier-md)
              (spit (io/file dir "findings.log") "")
              (cond
                template-edn  (spit (io/file dir "template.edn")
                                    (with-out-str (clojure.pprint/pprint template-edn)))
                template-path (try (spit (io/file dir "template.edn")
                                         (slurp template-path))
                                   (catch Throwable _ nil))
                :else         (spit (io/file dir "template.edn")
                                    (with-out-str
                                      (clojure.pprint/pprint {:template :ad-hoc
                                                              :workflow/id id}))))
              (mulog/log ::workflow.bootstrap
                         :id id
                         :template (name template-kw)
                         :acceptance-count (count acceptance-norm)
                         :stage-count (count stages-norm)
                         :hitl-mode hitl-kw)
              {:dir dir-rel :dossier-path dossier-rel :stages-path stages-rel}))))))
  :input-schema  [:map
                  [:id            [:string  {:desc "Workflow id"}]]
                  [:purpose       [:string  {:desc "Verbatim purpose / user question"}]]
                  [:acceptance    [:vector  {:desc "Vector of {:id :text :status} criterion maps"} :any]]
                  [:stages        [:vector  {:desc "Vector of stage maps (template :stages or ad-hoc)"} :any]]
                  [:template-id   {:optional true} [:any     {:desc "Template id (keyword like :feature-launch, or :ad-hoc)"}]]
                  [:template-path {:optional true} [:string  {:desc "Path to source template.edn for audit copy"}]]
                  [:template-edn  {:optional true} [:map     {:desc "Inline template map (alternative to :template-path)"}]]
                  [:hitl-mode     {:optional true} [:any     {:desc "Keyword or string: :auto|:gates|:checkpoint|:co-pilot|:step (default :gates). Gate discipline honored by the agent, NOT a code-enforced interlock."}]]
                  [:base-dir      {:optional true} [:string  {:desc "Working directory (default: project root)"}]]]
  :output-schema [:map
                  [:dir          {:optional true} [:string  {:desc "Created (or existing) dossier directory"}]]
                  [:dossier-path {:optional true} [:string  {:desc "Path to dossier.md"}]]
                  [:stages-path  {:optional true} [:string  {:desc "Path to stages.edn"}]]
                  [:exists?      {:optional true} [:boolean {:desc "true if directory already existed (idempotent skip)"}]]
                  [:error        {:optional true} [:string  {:desc "Error if validation failed"}]]])

;; ============================================================================
;; workflow$append-log — NDJSON append to findings.log
;; ============================================================================

(defn- json-escape [s]
  (-> (str s)
      (str/replace "\\" "\\\\")
      (str/replace "\"" "\\\"")
      (str/replace "\n" "\\n")
      (str/replace "\r" "\\r")
      (str/replace "\t" "\\t")))

(defn- json-kv [[k v]]
  (str "\"" (json-escape (if (keyword? k) (name k) (str k))) "\":"
       (cond
         (number? v)  (str v)
         (boolean? v) (if v "true" "false")
         (nil? v)     "null"
         (sequential? v)
         (str "["
              (str/join ","
                        (map (fn [x]
                               (cond
                                 (number? x)  (str x)
                                 (boolean? x) (if x "true" "false")
                                 (nil? x)     "null"
                                 :else        (str "\"" (json-escape x) "\"")))
                             v))
              "]")
         :else        (str "\"" (json-escape v) "\""))))

(defcommand workflow$append-log
  "Append one NDJSON line to `<dossier-dir>/findings.log`. One line per stage
   call (or gate / synthesize / insert / skip / re-run — any state-machine
   move worth recording).

   Args:
     :id       — workflow id
     :iter     — iteration number
     :stage    — stage id (string \"research-feasibility\" or keyword)
     :agent    — agent name (\"research-agent\", \"plan-agent\", …)
                 OR \"system\" for non-stage events (gate / synthesize)
     :action   — optional action name (\"gate\" / \"synthesize\" / \"insert\" / …)
     :summary  — one-line summary of the call's outcome
     :pointers — optional map of `{:research_dossier … :plan_slug … :todo_slug …
                 :eval_path … :items_done […]}` — flattened into the JSON line
     :base-dir — working directory (default: project root)

   Returns `{:appended true}`."
  (fn [& {:keys [id iter stage agent action summary pointers base-dir]
          :or   {base-dir (config/project-dir)
                 pointers {}}}]
    (let [stage-name (cond
                       (keyword? stage) (name stage)
                       (string? stage)  stage
                       :else            nil)]
      (cond
        (not (string? id))      {:error ":id is required (string)"}
        (not (integer? iter))   {:error ":iter is required (integer)"}
        (nil? stage-name)       {:error ":stage is required (string or keyword)"}
        (not (string? agent))   {:error ":agent is required (string)"}
        (not (string? summary)) {:error ":summary is required (string)"}
        :else
        (let [log-file (io/file base-dir results-base id "findings.log")]
          (if-not (.isFile log-file)
            {:error (str "findings.log not found at "
                         (str results-base "/" id "/findings.log")
                         " — run workflow$bootstrap first")}
            (let [entry  (merge {:iter iter :stage stage-name :agent agent
                                 :summary summary}
                                (when action {:action action})
                                pointers)
                  line   (str "{" (str/join "," (map json-kv entry)) "}\n")]
              (spit log-file line :append true)
              (mulog/log ::workflow.stage-call
                         :id id :iter iter :stage stage-name :agent agent
                         :action action
                         :pointer-keys (sort (keys pointers)))
              {:appended true}))))))
  :input-schema  [:map
                  [:id       [:string {:desc "Workflow id"}]]
                  [:iter     [:int    {:desc "Iteration number"}]]
                  [:stage    [:any    {:desc "Stage id (keyword or string)"}]]
                  [:agent    [:string {:desc "Agent name (or 'system' for non-stage events)"}]]
                  [:action   {:optional true} [:string {:desc "Optional action: gate|synthesize|insert|skip|re-run"}]]
                  [:summary  [:string {:desc "One-line summary of the move's outcome"}]]
                  [:pointers {:optional true} [:map    {:desc "Optional map flattened into the JSON line"}]]
                  [:base-dir {:optional true} [:string {:desc "Working directory (default: project root)"}]]]
  :output-schema [:map
                  [:appended {:optional true} [:boolean {:desc "true on success"}]]
                  [:error    {:optional true} [:string  {:desc "Error if validation failed or dossier missing"}]]])

;; ============================================================================
;; workflow$update-stage — flip one stage's :status in stages.edn
;; ============================================================================

(defcommand workflow$update-stage
  "Targeted edit of one stage's entry in stages.edn. Flips :status, optionally
   sets :artifact / :plan-slug / :todo-slug / :item-progress / :gate /
   :completed-at, and ALWAYS increments :attempts.

   Args:
     :id            — workflow id
     :stage-id      — stage id (string or keyword)
     :status        — new stage status (:pending :in-progress :satisfied
                      :failed :skipped :abandoned)
     :artifact      — optional artifact path
     :plan-slug     — optional plan slug
     :todo-slug     — optional todo slug
     :item-progress — optional progress string (e.g. \"3/5\")
     :gate          — optional gate map (e.g. {:status :approved :at \"...\"})
     :note          — optional one-line note (e.g. SKIP rationale)
     :base-dir      — working directory (default: project root)

   When status flips to a terminal value (:satisfied / :failed / :skipped /
   :abandoned), :completed-at is set to now (unless explicit :completed-at
   is passed).

   Returns `{:updated true :from <old-status> :to <new-status>
             :attempts <int>}` on success."
  (fn [& {:keys [id stage-id status artifact plan-slug todo-slug
                 item-progress gate note completed-at base-dir]
          :or   {base-dir (config/project-dir)}}]
    (let [stage-name (cond
                       (keyword? stage-id) (name stage-id)
                       (string? stage-id)  stage-id
                       :else               nil)
          status-kw  (cond
                       (keyword? status) status
                       (string? status)  (keyword status)
                       :else             nil)
          terminal?  (contains? #{:satisfied :failed :skipped :abandoned} status-kw)]
      (cond
        (not (string? id))         {:error ":id is required (string)"}
        (nil? stage-name)          {:error ":stage-id is required (string or keyword)"}
        (or (nil? status-kw)
            (not (valid-stage-statuses status-kw)))
        {:error (str ":status must be one of " (sort (map name valid-stage-statuses)))}
        :else
        (let [stages-file (io/file base-dir results-base id "stages.edn")
              data        (read-stages-edn stages-file)]
          (cond
            (nil? data)
            {:error (str "stages.edn not found or unreadable for id " id)}

            :else
            (let [target-kw   (keyword stage-name)
                  found?      (volatile! false)
                  old-status  (volatile! nil)
                  attempts*   (volatile! 0)
                  new-stages  (mapv
                               (fn [s]
                                 (if (= target-kw (keyword (str (name (or (:id s) :_unknown)))))
                                   (let [_         (vreset! found? true)
                                         _         (vreset! old-status (:status s))
                                         attempts  (inc (or (:attempts s) 0))
                                         _         (vreset! attempts* attempts)]
                                     (cond-> (assoc s
                                                    :status status-kw
                                                    :attempts attempts)
                                       artifact      (assoc :artifact artifact)
                                       plan-slug     (assoc :plan-slug plan-slug)
                                       todo-slug     (assoc :todo-slug todo-slug)
                                       item-progress (assoc :item-progress item-progress)
                                       gate          (assoc :gate gate)
                                       note          (assoc :note note)
                                       (and terminal? (not completed-at))
                                       (assoc :completed-at (now-iso))
                                       completed-at  (assoc :completed-at completed-at)))
                                   s))
                               (stages-of data))]
              (if-not @found?
                {:error (str "stage " stage-name " not found in stages.edn")}
                (let [new-data (assoc data :stages new-stages)]
                  (write-stages-edn stages-file new-data)
                  (mulog/log ::workflow.stage-update
                             :id id
                             :stage-id stage-name
                             :from @old-status
                             :to   status-kw
                             :attempts @attempts*)
                  {:updated  true
                   :from     (some-> @old-status keyword)
                   :to       status-kw
                   :attempts @attempts*}))))))))
  :input-schema  [:map
                  [:id             [:string {:desc "Workflow id"}]]
                  [:stage-id       [:any    {:desc "Stage id (keyword or string)"}]]
                  [:status         [:any    {:desc "New status (keyword or string): :pending|:in-progress|:satisfied|:failed|:skipped|:abandoned"}]]
                  [:artifact       {:optional true} [:string {:desc "Path to stage output (research dossier / plan / etc.)"}]]
                  [:plan-slug      {:optional true} [:string {:desc "Plan slug (if stage produced one)"}]]
                  [:todo-slug      {:optional true} [:string {:desc "Todo slug (if stage produced one)"}]]
                  [:item-progress  {:optional true} [:string {:desc "Progress string (e.g. \"3/5\")"}]]
                  [:gate           {:optional true} [:map    {:desc "Gate state map {:status :approved|:rejected :at \"...\"}"}]]
                  [:note           {:optional true} [:string {:desc "One-line note (SKIP rationale, etc.)"}]]
                  [:completed-at   {:optional true} [:string {:desc "ISO-8601 timestamp; auto-filled for terminal statuses"}]]
                  [:base-dir       {:optional true} [:string {:desc "Working directory (default: project root)"}]]]
  :output-schema [:map
                  [:updated  {:optional true} [:boolean {:desc "true on success"}]]
                  [:from     {:optional true} [:keyword {:desc "Previous status"}]]
                  [:to       {:optional true} [:keyword {:desc "New status"}]]
                  [:attempts {:optional true} [:int     {:desc "Post-update attempt count"}]]
                  [:error    {:optional true} [:string  {:desc "Error if validation failed or stage missing"}]]])

;; ============================================================================
;; workflow$update-acceptance — flip one workflow-level criterion's status
;; ============================================================================

(defcommand workflow$update-acceptance
  "Targeted edit of a single workflow-level criterion's `status:` line in
   dossier.md frontmatter. Other criteria, the body, and other frontmatter
   keys are untouched.

   Args:
     :id           — workflow id
     :criterion-id — criterion id (string \"a1\" or keyword :a1)
     :status       — new status (:open :satisfied :partial :descoped :contradicted)
     :base-dir     — working directory (default: project root)

   Returns `{:updated true :from <old-status> :to <new-status>}` on success."
  (fn [& {:keys [id criterion-id status base-dir]
          :or   {base-dir (config/project-dir)}}]
    (let [criterion-name (cond
                           (keyword? criterion-id) (name criterion-id)
                           (string? criterion-id)  criterion-id
                           :else                   nil)
          status-name    (cond
                           (keyword? status) (name status)
                           (string? status)  status
                           :else             nil)]
      (cond
        (not (string? id))
        {:error ":id is required (string)"}

        (nil? criterion-name)
        {:error ":criterion-id is required (string or keyword)"}

        (or (nil? status-name) (not (valid-criterion-statuses (keyword status-name))))
        {:error (str ":status must be one of " (sort (map name valid-criterion-statuses)))}

        :else
        (let [dossier (io/file base-dir results-base id "dossier.md")]
          (if-not (.isFile dossier)
            {:error (str "dossier.md not found for id " id)}
            (let [content (slurp dossier)
                  pat (re-pattern
                       (str "(?m)^(\\s*-\\s*id:\\s*"
                            (java.util.regex.Pattern/quote criterion-name)
                            "\\s*\\n\\s+text:[^\\n]*\\n\\s+status:\\s*)(\\S+)"))
                  m   (re-find pat content)]
              (if-not m
                {:error (str "criterion " criterion-name " not found in dossier.md")}
                (let [old-status (last m)
                      new-content (str/replace content pat (str "$1" status-name))]
                  (spit dossier new-content)
                  (mulog/log ::workflow.acceptance-flip
                             :id id
                             :criterion-id criterion-name
                             :from (keyword old-status)
                             :to   (keyword status-name))
                  {:updated true
                   :from    (keyword old-status)
                   :to      (keyword status-name)}))))))))
  :input-schema  [:map
                  [:id           [:string {:desc "Workflow id"}]]
                  [:criterion-id [:any    {:desc "Criterion id (keyword or string, e.g. \"a1\")"}]]
                  [:status       [:any    {:desc "New status (keyword or string): :open|:satisfied|:partial|:descoped|:contradicted"}]]
                  [:base-dir     {:optional true} [:string {:desc "Working directory (default: project root)"}]]]
  :output-schema [:map
                  [:updated {:optional true} [:boolean {:desc "true on success"}]]
                  [:from    {:optional true} [:keyword {:desc "Previous status"}]]
                  [:to      {:optional true} [:keyword {:desc "New status"}]]
                  [:error   {:optional true} [:string  {:desc "Error if validation failed or criterion missing"}]]])

;; ============================================================================
;; workflow$write-verdict — terminal verdict.md (source of truth)
;; ============================================================================

(defn- read-acceptance-from-dossier
  "Best-effort extraction of acceptance state from the dossier for the
   verdict.md `acceptance_outcome:` map."
  [^java.io.File dossier-file]
  (when (.isFile dossier-file)
    (when-let [lines (read-frontmatter-lines dossier-file)]
      (let [state (extract-acceptance-state lines)]
        (vec state)))))

(defn- read-stage-summary-from-stages
  "Read stages.edn and produce a vector of [stage-id-kw status-kw] pairs for
   the verdict frontmatter's `stage_outcomes` block."
  [stages-file]
  (when-let [data (read-stages-edn stages-file)]
    (mapv (fn [s]
            [(keyword (or (some-> (:id s) name) "_unknown"))
             (or (:status s) :pending)])
          (stages-of data))))

(defn- strip-leading-verdict-heading
  [narrative]
  (str/replace (str narrative) #"\A#+\s*(Workflow\s+)?Verdict\s*\n+" ""))

(defn- block-mismatched-achieved
  "Validation guard for :achieved status — every workflow-level acceptance
   criterion must be :satisfied or :descoped."
  [status-kw acceptance]
  (when (and (= :achieved status-kw) (seq acceptance))
    (let [bad (->> acceptance
                   (remove (fn [[_ v]] (#{:satisfied :descoped} v)))
                   (mapv (fn [[id v]] (str (name id) ":" (name v)))))]
      (when (seq bad)
        {:error (str ":achieved requires every criterion to be :satisfied or "
                     ":descoped; the following are not: "
                     (str/join ", " bad)
                     ". Call workflow$update-acceptance to flip each "
                     "criterion's status before writing the verdict, or use "
                     ":partial / :abandoned instead.")}))))

(defcommand workflow$write-verdict
  "Compose verdict.md from the current workflow state. Source-of-truth at
   termination — the agent's :answer should be DERIVED from this file.

   Args:
     :id        — workflow id
     :status    — terminal status (:achieved :partial :abandoned)
     :narrative — markdown body for the `## Verdict` section. A leading
                  `## Verdict` heading in the narrative is stripped so it
                  doesn't duplicate the template's own heading.
     :base-dir  — working directory (default: project root)

   Validation: when `:status :achieved` is claimed, every criterion in
   dossier.md must be `:satisfied` or `:descoped`. Mismatches return an
   error pointing at the offending criterion ids.

   The function reads dossier.md to derive acceptance + iteration count, and
   reads stages.edn to derive stage outcomes. Both are included in the
   verdict frontmatter when present.

   Returns `{:path <verdict.md path>}` on success, or `{:error \"...\"}`."
  (fn [& {:keys [id status narrative base-dir]
          :or   {base-dir (config/project-dir)}}]
    (let [status-kw (cond
                      (keyword? status) status
                      (string? status)  (keyword status)
                      :else             nil)]
      (cond
        (not (string? id))                  {:error ":id is required (string)"}
        (not (valid-workflow-statuses status-kw))
        {:error (str ":status must be one of "
                     (sort (map name valid-workflow-statuses)))}
        (not (string? narrative))           {:error ":narrative is required (string)"}
        (guard/content-violation narrative) (guard/content-violation narrative)
        :else
        (let [dir          (io/file base-dir results-base id)
              _            (.mkdirs dir)
              dossier      (io/file dir "dossier.md")
              dossier-fm   (when (.isFile dossier)
                             (read-frontmatter-lines dossier))
              last-iter    (when dossier-fm
                             (some-> (extract-flat dossier-fm "last_iteration")
                                     (str)
                                     Integer/parseInt))
              template-id  (when dossier-fm
                             (extract-flat dossier-fm "workflow_template"))
              acceptance   (read-acceptance-from-dossier dossier)
              stage-pairs  (read-stage-summary-from-stages (io/file dir "stages.edn"))]
          (or (block-mismatched-achieved status-kw acceptance)
              (let [acceptance-block (when (seq acceptance)
                                       (str "acceptance_outcome:\n"
                                            (str/join ""
                                                      (for [[k v] acceptance]
                                                        (str "  " (name k) ": " (name v) "\n")))))
                    stage-block      (when (seq stage-pairs)
                                       (str "stage_outcomes:\n"
                                            (str/join ""
                                                      (for [[k v] stage-pairs]
                                                        (str "  " (name k) ": " (name v) "\n")))))
                    verdict-file     (io/file dir "verdict.md")
                    cleaned          (strip-leading-verdict-heading narrative)
                    content (str "---\n"
                                 "workflow_id: " id "\n"
                                 (when template-id (str "workflow_template: " template-id "\n"))
                                 "status: " (name status-kw) "\n"
                                 "terminated: " (now-iso) "\n"
                                 (when last-iter (str "iterations: " last-iter "\n"))
                                 acceptance-block
                                 stage-block
                                 "---\n\n"
                                 "## Verdict\n"
                                 (str/trim cleaned)
                                 "\n")]
                (spit verdict-file content)
                (mulog/log ::workflow.terminate
                           :id id :status status-kw
                           :iterations last-iter
                           :n-criteria (count acceptance)
                           :stages-run (count stage-pairs))
                {:path (.getAbsolutePath verdict-file)}))))))
  :input-schema  [:map
                  [:id        [:string {:desc "Workflow id"}]]
                  [:status    [:any    {:desc "Terminal status (keyword or string): :achieved|:partial|:abandoned"}]]
                  [:narrative [:string {:desc "Markdown body for the ## Verdict section"}]]
                  [:base-dir  {:optional true} [:string {:desc "Working directory (default: project root)"}]]]
  :output-schema [:map
                  [:path  {:optional true} [:string {:desc "Path to verdict.md"}]]
                  [:error {:optional true} [:string {:desc "Error if validation failed"}]]])

;; ============================================================================
;; workflow$index-append — prepend one line to INDEX.md (newest first)
;; ============================================================================

(defcommand workflow$index-append
  "Prepend a one-line entry to `.brainyard/agents/workflow-agent/INDEX.md`.
   Newest-first.

   Format:
     `- YYYY-MM-DD HH:MM [<id>](<id>/) — <status> · <one-line>`

   Args:
     :id       — workflow id
     :status   — terminal status (:achieved :partial :abandoned)
     :one-line — one-line distillation (truncated to 200 chars)
     :base-dir — working directory (default: project root)

   Returns `{:appended true :line \"…\"}`."
  (fn [& {:keys [id status one-line base-dir]
          :or   {base-dir (config/project-dir)}}]
    (let [status-name (cond
                        (keyword? status) (name status)
                        (string? status)  status
                        :else             nil)]
      (cond
        (not (string? id))           {:error ":id is required (string)"}
        (or (nil? status-name)
            (not (valid-workflow-statuses (keyword status-name))))
        {:error (str ":status must be one of " (sort (map name valid-workflow-statuses)))}
        (not (string? one-line))     {:error ":one-line is required (string)"}
        :else
        (let [trimmed (-> one-line (str/replace #"\s+" " ") str/trim)
              capped  (if (> (count trimmed) 200)
                        (str (subs trimmed 0 197) "…")
                        trimmed)
              line    (str "- " (now-yyyy-mm-dd-hh-mm)
                           " [" id "](" id "/) — "
                           status-name " · " capped "\n")
              file    (io/file base-dir index-rel)
              existing (if (.isFile file) (slurp file) "")]
          (.mkdirs (.getParentFile file))
          (spit file (str line existing))
          (mulog/log ::workflow.index
                     :id id :status (keyword status-name))
          {:appended true :line line}))))
  :input-schema  [:map
                  [:id       [:string {:desc "Workflow id"}]]
                  [:status   [:any    {:desc "Terminal status (keyword or string): :achieved|:partial|:abandoned"}]]
                  [:one-line [:string {:desc "One-line distillation (truncated to 200 chars)"}]]
                  [:base-dir {:optional true} [:string {:desc "Working directory (default: project root)"}]]]
  :output-schema [:map
                  [:appended {:optional true} [:boolean {:desc "true on success"}]]
                  [:line     {:optional true} [:string  {:desc "The exact line that was prepended"}]]
                  [:error    {:optional true} [:string  {:desc "Error if validation failed"}]]])

;; ============================================================================
;; Public roster (for workflow-agent's :agent-tools)
;; ============================================================================

(def workflow-helpers
  "Vector of all workflow$* helper vars. workflow-agent appends these to its
   :agent-tools roster so the SCI sandbox auto-binds them (callable as
   `(workflow$id ...)` in a clojure fence).

   `workflow$summarize-log` is intentionally absent — the agent calls
   `query$llm` directly with findings.log content as `:sub-context`. Mirrors
   the research-helpers decision."
  [#'workflow$id
   #'workflow$resume?
   #'workflow$list-templates
   #'workflow$load-template
   #'workflow$install-starters
   #'workflow$bootstrap
   #'workflow$append-log
   #'workflow$update-stage
   #'workflow$update-acceptance
   #'workflow$write-verdict
   #'workflow$index-append])

;; ============================================================================
;; Auto-finalize hook
;;
;; Same shape as research.clj's auto-finalize: when workflow-agent emits a
;; non-blank answer without writing verdict.md, this `:agent.ask/post` hook
;; writes verdict.md + appends INDEX.md if (and only if) the dossier exists
;; AND all workflow-level acceptance criteria have moved off :open.
;;
;; The dossier is the only state of record; we never retroactively bootstrap.
;; ============================================================================

(def ^:private saved-workflow-prefix
  "If :answer already contains this exact prefix, the LLM honored the
   FINALIZE step itself and the hook is a no-op."
  "Saved workflow dossier:")

(defn- workflow-agent? [agent]
  (try
    (= :workflow-agent (proto/defagent-type agent))
    (catch Throwable _ false)))

(defn- already-finalized? [^String answer]
  (boolean (and (string? answer)
                (str/includes? answer saved-workflow-prefix))))

(defn- terminal-status
  "Derive a terminal workflow status from acceptance-state. Rules mirror
   research.clj — conservative:
     - empty acceptance-state → nil (degenerate; do not fabricate verdict)
     - any :open value           → nil (mid-flight / CLARIFY)
     - all :satisfied / :descoped → :achieved
     - all :contradicted          → :abandoned
     - any other mix             → :partial"
  [acceptance-state]
  (let [vs (set (vals acceptance-state))]
    (cond
      (empty? vs)                            nil
      (contains? vs :open)                   nil
      (every? #{:satisfied :descoped} vs)    :achieved
      (every? #{:contradicted} vs)           :abandoned
      :else                                  :partial)))

(defn- one-line-summary
  [^String answer max-chars]
  (let [stripped (-> answer
                     (str/replace #"^---\n[\s\S]*?\n---\n" "")
                     (str/replace #"(?m)^Saved workflow dossier:.*$" "")
                     str/trim)
        paragraphs (->> (str/split stripped #"\n\n")
                        (map str/trim)
                        (remove str/blank?))
        prose? (fn [p]
                 (and (not (str/starts-with? p "#"))
                      (not (str/starts-with? p "|"))
                      (not (str/starts-with? p "```"))
                      (not (re-matches #"^[-*_]{3,}$" p))))
        chosen (or (first (filter prose? paragraphs))
                   (first paragraphs)
                   "")
        flat   (-> chosen
                   (str/replace #"\s+" " ")
                   (str/replace #"^#+\s*" "")
                   str/trim)]
    (subs flat 0 (min max-chars (count flat)))))

(defn- finalize-config
  "Per-turn override of auto-finalize via config."
  [agent]
  (try
    {:enabled? (boolean (config/get-config agent :workflow-auto-finalize))}
    (catch Throwable _
      {:enabled? true})))

(defn workflow-auto-finalize
  "Auto-write verdict.md + append INDEX.md when workflow-agent emits a
   non-blank answer without finalizing itself. Strict trigger: only fires
   when the dossier exists AND no acceptance criterion is :open AND
   verdict.md doesn't already exist. Idempotent — defensive against repeat
   invocations.

   Failure inside the hook is logged but never re-thrown — the user-facing
   answer must not be affected by hook errors."
  [{:keys [agent input result]}]
  (try
    (when (and (workflow-agent? agent) (map? result))
      (let [answer (:answer result)
            {:keys [enabled?]} (finalize-config agent)]
        (when (and enabled?
                   (string? answer)
                   (not (str/blank? answer))
                   (not (already-finalized? answer)))
          (let [question (or (when (string? input) input)
                             (some-> input :question str)
                             "")
                ;; Cannot reconstruct full template--question-slug shape from
                ;; the question alone; we look for a matching directory by
                ;; question-slug. If none matches, log and bail.
                base-dir (config/project-dir)
                candidate-slug (when-not (str/blank? question)
                                 (slugify question 60))
                root (io/file base-dir results-base)
                wid  (when (and candidate-slug (.isDirectory root))
                       (->> (.listFiles root)
                            (filter #(and (.isDirectory ^java.io.File %)
                                          (let [n (.getName ^java.io.File %)]
                                            (or (= n candidate-slug)
                                                (str/ends-with? n (str "--" candidate-slug))))))
                            first
                            (#(when % (.getName ^java.io.File %)))))]
            (when wid
              (let [dir (io/file base-dir results-base wid)]
                (cond
                  (not (.isDirectory dir))
                  (mulog/log ::workflow.no-dossier-skip
                             :id wid
                             :answer-chars (count answer))

                  (.isFile (io/file dir "verdict.md"))
                  (mulog/log ::workflow.auto-finalize-skip
                             :id wid
                             :reason :verdict-exists)

                  :else
                  (let [resume-state (workflow$resume? :id wid :base-dir base-dir)
                        accept       (:acceptance-state resume-state)
                        status       (terminal-status accept)]
                    (cond
                      (nil? status)
                      (mulog/log ::workflow.auto-finalize-skip
                                 :id wid
                                 :reason (cond
                                           (empty? (or accept {})) :no-acceptance
                                           (contains? (set (vals accept)) :open) :open-criteria-remain
                                           :else :status-undetermined)
                                 :acceptance-state accept)

                      :else
                      (let [summary (one-line-summary answer 200)
                            v (workflow$write-verdict
                               :id wid
                               :status status
                               :narrative answer
                               :base-dir base-dir)]
                        (when-not (:error v)
                          (workflow$index-append
                           :id wid
                           :status status
                           :one-line (if (str/blank? summary)
                                       "(auto-finalized; no summary extracted)"
                                       summary)
                           :base-dir base-dir)
                          (mulog/log ::workflow.auto-finalize
                                     :id wid
                                     :status status
                                     :answer-chars (count answer)
                                     :n-criteria (count accept))
                          ;; Inject the absent handoff line. This value
                          ;; propagates up through the enclosing when/cond/let
                          ;; to the hook's return — a :replace decision the
                          ;; :agent.ask/finalize runner applies to the answer.
                          (when-not (str/includes? answer "Saved workflow dossier:")
                            {:result      :replace
                             :reason      "injected absent Saved-workflow-dossier handoff line"
                             :replacement (assoc result :answer
                                                 (str answer "\n\nSaved workflow dossier: " (:path v)))}))))))))))))
    (catch Throwable t
      (mulog/error ::workflow.auto-finalize-failed
                   :exception t
                   :agent-id (try (proto/agent-id agent)
                                  (catch Throwable _ "unknown"))))))

(defn install-auto-finalize!
  "Register the auto-finalize hook globally. Idempotent — safe to call
   multiple times. The `:match` predicate scopes the hook to workflow-agent
   instances only.

   Tag `:source :workflow-agent` lets apps opt out via
   `(hooks/unregister-source! :workflow-agent)`. Per-turn opt-out is via
   `agent-runtime$config :key \"workflow-auto-finalize\" :value \"false\"`."
  []
  (hooks/register-hook!
   :agent.ask/finalize
   ::workflow-auto-finalize
   workflow-auto-finalize
   :source   :workflow-agent
   :match    (fn [{:keys [agent]}] (workflow-agent? agent))
   :priority 50))

;; Self-install at namespace load. Idempotent — register-hook! replaces by id.
(install-auto-finalize!)
