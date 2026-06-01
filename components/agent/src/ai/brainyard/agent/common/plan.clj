;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: Apache-2.0
;; Licensed under the Apache License, Version 2.0 (the "License"); you may
;; not use this file except in compliance with the License. See LICENSE at
;; the repository root and CONTRIBUTING.md for contribution terms.

(ns ai.brainyard.agent.common.plan
  "Persistent plan management — plans as pure blueprints.

   A plan is a comprehensive design doc with frontmatter + free-form body
   (Context, Approach, Risks/Open Questions, References, Acceptance). Stored
   as `<slug>.md` markdown files in `.brainyard/agents/plan-agent/plans/`, scoped
   to user (`~/.brainyard/agents/plan-agent/plans/`) or project
   (`<project>/.brainyard/agents/plan-agent/plans/`).

   The legacy location `.brainyard/plans/` is read for one release as a
   fallback so existing local checkouts keep working; new writes always go
   to the new path. `bb migrate:plan-agent` copies legacy plans across.

   Plan files are identified by frontmatter — every plan must declare an
   :id and :title between `---` markers. Other .md files in the plans
   directory (READMEs, notes, todos saved with `.todo.md` naming) are
   silently skipped during list-plans and rejected by read-plan/delete-plan.

   Plans are READ-ONLY during execution. They do NOT carry steps, per-task
   results, or progress data — that runtime state belongs in todos (see
   ai.brainyard.agent.common.todo). Editing a plan means revising its
   blueprint (body), not logging execution.

   Public API:
   - Plain functions (create-plan, read-plan, update-plan, delete-plan,
     list-plans, plan-exists?, update-body, reopen-plan) — used from sandbox
     bindings and other Clojure callers.
   - The LLM-facing CRUD surface is the polymorphic `doc$*` family in
     `ai.brainyard.agent.common.doc` (with `:kind :plan`). Per-verb plan
     shims (plan$list / plan$create / plan$status / plan$complete / …)
     have been removed."
  (:require [ai.brainyard.agent.common.guard :as guard]
            [ai.brainyard.agent.core.config :as config]
            [ai.brainyard.agent.core.hooks :as hooks]
            [ai.brainyard.agent.core.protocol :as proto]
            [ai.brainyard.agent.core.tool :refer [defcommand]]
            [ai.brainyard.mulog.interface :as mulog]
            [ai.brainyard.util.interface :as util]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.text SimpleDateFormat]
           [java.time Instant]
           [java.util Date UUID]))

;; ============================================================================
;; Slug Generation
;; ============================================================================

(defn- generate-slug
  "Generate a unique random 3-word slug for a plan file.
   Checks the target directory to avoid collisions, retrying up to max-retries times."
  [dir & {:keys [max-retries] :or {max-retries 3}}]
  (loop [attempt 0]
    (let [slug (util/gen-random-words)]
      (if (and dir (.exists (io/file dir (str slug ".md"))))
        (if (< attempt max-retries)
          (recur (inc attempt))
          (str slug "-" (System/currentTimeMillis)))
        slug))))

;; ============================================================================
;; Frontmatter Parsing
;; ============================================================================

(defn- parse-frontmatter
  "Parse YAML-like frontmatter from between --- markers.
   Returns [frontmatter-map remaining-body-string]."
  [md-string]
  (let [lines (str/split-lines md-string)]
    (if (and (seq lines) (= "---" (str/trim (first lines))))
      (let [rest-lines (rest lines)
            end-idx (some (fn [[i line]]
                            (when (= "---" (str/trim line)) i))
                          (map-indexed vector rest-lines))]
        (if end-idx
          (let [fm-lines (take end-idx rest-lines)
                body-lines (drop (inc end-idx) rest-lines)
                frontmatter (into {}
                                  (keep (fn [line]
                                          (let [[k v] (str/split line #":\s+" 2)]
                                            (when (and k v)
                                              [(keyword (str/trim k)) (str/trim v)])))
                                        fm-lines))]
            [frontmatter (str/join "\n" body-lines)])
          [{} md-string]))
      [{} md-string])))

(defn- render-frontmatter
  "Render frontmatter map as YAML-like string between --- markers."
  [fm]
  (let [key-order [:id :file-type :title :scope :status :created :updated]
        lines (keep (fn [k]
                      (when-let [v (get fm k)]
                        (str (name k) ": " v)))
                    key-order)]
    (str "---\n" (str/join "\n" lines) "\n---")))

;; ============================================================================
;; Plan <-> Markdown Conversion
;; ============================================================================

(def ^:const file-type
  "Frontmatter discriminator for plan files. Defensive — plans live in
   `.brainyard/agents/plan-agent/plans/` (legacy: `.brainyard/plans/`) while todos
   live in `.brainyard/agents/todo-agent/todos/` (legacy: `.brainyard/todos/`), so
   the directory normally distinguishes
   them. The frontmatter field guards against a misfiled file from being
   treated as the wrong kind."
  "plan")

(defn plan->md
  "Convert a plan map to markdown string: frontmatter + # Title + body.
   Always emits `file-type: plan` so the file is unambiguously a plan,
   regardless of filename. Body is LLM-written markdown (Context,
   Approach, Risks, References, Acceptance, etc.)."
  [{:keys [id title scope status created updated body]}]
  (let [fm (render-frontmatter
            {:id id
             :file-type file-type
             :title title
             :scope (name (or scope :project))
             :status (name (or status :draft))
             :created (str (or created (Instant/now)))
             :updated (str (or updated (Instant/now)))})
        content (str "\n# " title "\n"
                     (when (not (str/blank? body))
                       (str "\n" body "\n")))]
    (str fm "\n" content)))

(defn md->plan
  "Parse a markdown string into a plan map.
   Body is everything after the # Title line — verbatim, including any
   legacy ## Steps / ## Notes / etc. sections from older plan files."
  [md-string]
  (let [[fm body-str] (parse-frontmatter md-string)
        body (-> (str/replace body-str #"(?m)^# .+\n?" "")
                 str/trim)]
    (cond-> {:id (:id fm)
             :title (:title fm)
             :scope (keyword (or (:scope fm) "project"))
             :status (keyword (or (:status fm) "draft"))
             :created (:created fm)
             :updated (:updated fm)}
      (not (str/blank? body)) (assoc :body body))))

;; ============================================================================
;; Path Resolution
;; ============================================================================

(def ^:const plans-subpath
  "Repo-relative subpath under the project root where new plans live."
  ".brainyard/agents/plan-agent/plans")

(def ^:const legacy-plans-subpath
  "Read-only fallback location for plans authored before the storage
   migration. Removed in a future release after `bb migrate:plan-agent`
   has had a release to settle."
  ".brainyard/plans")

(defn- plans-dir
  "Resolve the plans directory for a given scope (write target + primary
   read). Returns a File, or nil if dirs config is missing. Unknown scopes
   default to :project."
  [dirs scope]
  (let [base (case scope
               :user (str (System/getProperty "user.home") "/" plans-subpath)
               (when-let [project-dir (or (:project-dir dirs)
                                          (:base-dir dirs))]
                 (str project-dir "/" plans-subpath)))]
    (when base (io/file base))))

(defn- legacy-plans-dir
  "Resolve the legacy plans directory for read-fallback. Mirrors
   `plans-dir` shape exactly except for the subpath."
  [dirs scope]
  (let [base (case scope
               :user (str (System/getProperty "user.home") "/" legacy-plans-subpath)
               (when-let [project-dir (or (:project-dir dirs)
                                          (:base-dir dirs))]
                 (str project-dir "/" legacy-plans-subpath)))]
    (when base (io/file base))))

(defn- plan-file
  "Resolve the file path for a plan slug in the given scope. Always points
   at the NEW location — `find-plan-file` below handles legacy fallback for
   read paths."
  [dirs scope slug]
  (when-let [dir (plans-dir dirs scope)]
    (io/file dir (str slug ".md"))))

(defn- find-plan-file
  "Read-side resolver: return the first existing file for `slug` across
   the new location and the legacy fallback, in that order. Returns nil
   when neither path has it."
  [dirs scope slug]
  (let [new-f    (plan-file dirs scope slug)
        legacy-f (when-let [dir (legacy-plans-dir dirs scope)]
                   (io/file dir (str slug ".md")))]
    (cond
      (and new-f    (.exists ^java.io.File new-f))    new-f
      (and legacy-f (.exists ^java.io.File legacy-f)) legacy-f
      :else                                            nil)))

(defn- plan-md?
  "Validity check: a plan markdown blob must declare `file-type: plan`
   in its frontmatter, plus :id and :title. Discriminates plans from
   misfiled todos (which carry `file-type: todo`) and from loose .md
   files (READMEs, notes) that may end up in the plans directory."
  [md-string]
  (let [[fm _] (parse-frontmatter md-string)]
    (boolean (and (= file-type (:file-type fm))
                  (:id fm)
                  (:title fm)))))

(defn- safe-read-plan-file
  "Slurp + parse a plan file, returning {:fm <frontmatter> :body <str>} on
   success or nil on any failure (with mulog/warn). Skips non-plan .md
   files via `plan-md?`."
  [^java.io.File f]
  (try
    (let [md (slurp f)]
      (if (plan-md? md)
        (let [[fm body] (parse-frontmatter md)]
          {:fm fm :body body :md md})
        (do (mulog/warn ::plan-file-skipped
                        :reason :not-a-plan
                        :path (.getAbsolutePath f))
            nil)))
    (catch Exception e
      (mulog/warn ::plan-file-skipped
                  :reason :read-failed
                  :path (.getAbsolutePath f)
                  :error (ex-message e))
      nil)))

;; ============================================================================
;; CRUD Operations
;; ============================================================================

(defn create-plan
  "Create a new plan file with a random 3-word slug. Returns the plan map
   or {:error ...}. body is free-form markdown — Context, Approach, Risks,
   References, Acceptance. The returned map includes :file-path with the
   absolute path to the plan file."
  [dirs scope title body]
  (try
    (let [dir (plans-dir dirs scope)]
      (if-not dir
        {:error "Cannot resolve plans directory — dirs config missing"}
        (let [slug (generate-slug dir)
              f (io/file dir (str slug ".md"))
              now (str (Instant/now))
              plan (cond-> {:id (str (UUID/randomUUID))
                            :title title
                            :slug slug
                            :scope scope
                            :status :draft
                            :created now
                            :updated now
                            :file-path (.getAbsolutePath f)}
                     (not (str/blank? body)) (assoc :body body))
              md (plan->md plan)]
          (if-let [v (guard/content-violation md)]
            v
            (do (.mkdirs (.getParentFile f))
                (spit f md)
                plan)))))
    (catch Exception e
      {:error (str "create-plan failed: " (.getMessage e))})))

(defn read-plan
  "Read a plan by slug. Checks project scope first, then user scope, and
   within each scope falls back to the legacy `.brainyard/plans/` location
   when the new path doesn't have the file. Returns plan map with
   :file-path (absolute) or {:error ...}. Returns an :error if the file is
   missing or its frontmatter does not declare `file-type: plan` with
   id/title."
  [dirs slug & {:keys [scope]}]
  (try
    (let [scopes (if scope [scope] [:project :user])
          f (some (fn [s] (find-plan-file dirs s slug)) scopes)]
      (cond
        (nil? f)
        {:error (str "Plan not found: " slug)}

        :else
        (let [md (slurp f)]
          (if-not (plan-md? md)
            {:error (str "Not a valid plan file (frontmatter must declare "
                         "file-type: plan with id and title): "
                         (.getAbsolutePath ^java.io.File f))}
            (let [plan (md->plan md)]
              (assoc plan :slug slug
                     :file-path (.getAbsolutePath ^java.io.File f)))))))
    (catch Exception e
      (mulog/warn ::read-plan-failed :slug slug :error (ex-message e))
      {:error (str "read-plan failed: " (.getMessage e))})))

(defn update-plan
  "Write an updated plan map back to its file. Returns plan map with
   :file-path or {:error ...}."
  [dirs plan]
  (try
    (let [slug (:slug plan)
          scope (or (:scope plan) :project)
          f (plan-file dirs scope slug)]
      (if-not f
        {:error "Cannot resolve plan path"}
        (let [^java.io.File f f
              updated (assoc plan
                             :updated (str (Instant/now))
                             :file-path (.getAbsolutePath f))
              md (plan->md updated)]
          (if-let [v (guard/content-violation md)]
            v
            (do (.mkdirs (.getParentFile f))
                (spit f md)
                updated)))))
    (catch Exception e
      {:error (str "update-plan failed: " (.getMessage e))})))

(defn delete-plan
  "Delete a plan file. Returns {:deleted slug} or {:error ...}.
   Refuses to delete a file whose frontmatter does not declare
   `file-type: plan`. Walks both new and legacy locations so a delete
   request hits whichever path actually has the file."
  [dirs slug & {:keys [scope]}]
  (try
    (let [scopes (if scope [scope] [:project :user])
          f (some (fn [s] (find-plan-file dirs s slug)) scopes)]
      (cond
        (nil? f)
        {:error (str "Plan not found: " slug)}

        (not (plan-md? (slurp f)))
        {:error (str "Refusing to delete: file is not a valid plan "
                     "(frontmatter must declare file-type: plan): "
                     (.getAbsolutePath ^java.io.File f))}

        :else
        (do (.delete ^java.io.File f) {:deleted slug})))
    (catch Exception e
      (mulog/warn ::delete-plan-failed :slug slug :error (ex-message e))
      {:error (str "delete-plan failed: " (.getMessage e))})))

(defn list-plans
  "List plans with lightweight metadata (frontmatter only).
   Scans every `<slug>.md` file across BOTH the new `.brainyard/agents/plan-agent/
   plans/` location and the legacy `.brainyard/plans/` fallback for each
   scope; tags each entry with `:layout :new` or `:layout :legacy` so
   callers can see migration status at a glance. When a slug appears in
   both locations the new layout wins (legacy entry is dropped from the
   result). Skips any file whose frontmatter does not declare
   `file-type: plan` with id/title — each skip emits ::plan-file-skipped
   via mulog/warn for visibility.

   Returns vector of plan summaries or {:error ...}."
  [dirs & {:keys [scope status]}]
  (try
    (let [scopes (if scope [scope] [:project :user])
          plan-re #"(.+)\.md$"
          read-dir (fn [^java.io.File dir s layout]
                     (when (and dir (.isDirectory dir))
                       (->> (.listFiles dir)
                            (keep (fn [^java.io.File f]
                                    (when-let [[_ slug] (re-matches plan-re (.getName f))]
                                      (when-let [{:keys [fm]} (safe-read-plan-file f)]
                                        (let [entry {:slug slug
                                                     :title (:title fm)
                                                     :scope s
                                                     :status (keyword (or (:status fm) "draft"))
                                                     :created (:created fm)
                                                     :updated (:updated fm)
                                                     :file-path (.getAbsolutePath f)
                                                     :layout layout}]
                                          (if status
                                            (when (= (keyword status) (:status entry))
                                              entry)
                                            entry)))))))))
          all-entries
          (mapcat
           (fn [s]
             (concat (read-dir (plans-dir dirs s) s :new)
                     (read-dir (legacy-plans-dir dirs s) s :legacy)))
           scopes)
          ;; Drop legacy duplicates when the same slug exists under :new.
          new-slugs (->> all-entries
                         (filter #(= :new (:layout %)))
                         (map (juxt :scope :slug))
                         set)
          deduped (remove (fn [{:keys [layout scope slug]}]
                            (and (= :legacy layout)
                                 (contains? new-slugs [scope slug])))
                          all-entries)]
      (vec (remove nil? deduped)))
    (catch Exception e
      (mulog/warn ::list-plans-failed :error (ex-message e))
      {:error (str "list-plans failed: " (.getMessage e))})))

(defn plan-exists?
  "Check if a *valid* plan exists at the given slug, looking under both
   the new and legacy locations. Returns false for files whose frontmatter
   does not declare `file-type: plan` (covers todos and loose .md files)."
  [dirs slug & {:keys [scope]}]
  (let [scopes (if scope [scope] [:project :user])]
    (boolean
     (some (fn [s]
             (when-let [f (find-plan-file dirs s slug)]
               (try (plan-md? (slurp f))
                    (catch Exception _ false))))
           scopes))))

;; ============================================================================
;; Body / Status Operations
;; ============================================================================

(defn update-body
  "Replace the free-form body of a plan. Returns updated plan map."
  [plan new-body]
  (if (str/blank? new-body)
    (dissoc plan :body)
    (assoc plan :body new-body)))

(defn reopen-plan
  "Set status back to :draft. Used to flag a completed/abandoned plan as
   re-active. The plan body and references stay as-is — no per-step state
   to reset (steps live in todos)."
  [plan]
  (assoc plan :status :draft))

;; ============================================================================
;; Dirs resolution for defcommands
;; ============================================================================

(defn- current-dirs
  "Resolve dirs from the current agent session, falling back to init-dirs!.
   Uses requiring-resolve to avoid circular deps at cold start."
  []
  (or (when-let [a (some-> (requiring-resolve 'ai.brainyard.agent.core.protocol/*current-agent*)
                           deref)]
        (when a
          (some-> (:!session a) deref
                  ((or (requiring-resolve 'ai.brainyard.agent.core.session/get-session-config)
                       (constantly nil))
                   :dirs))))
      ((or (requiring-resolve 'ai.brainyard.agent.core.config/init-dirs!)
           (constantly {})))))

;; ============================================================================
;; Plan-agent dossier helpers
;;
;; A *dossier* is the per-turn handoff record plan-agent emits — pre-flight
;; verdict, author action, post-flight rubric, recommended next agent. Lives
;; under `.brainyard/agents/plan-agent/dossiers/<yyyyMMdd-HHmmss>-<slug>.md` with
;; YAML frontmatter per docs/plan-agent-design.md §7.2. Mirrors the
;; explore-agent / update-agent helper patterns: deterministic slug, hand-
;; rolled YAML emitter, lenient parser scoped to the shape we emit.
;; ============================================================================

(def ^:private dossiers-subdir "dossiers")
(def ^:private dossiers-base ".brainyard/agents/plan-agent")
(def ^:private dossiers-dir-rel (str dossiers-base "/" dossiers-subdir))
(def ^:private dossiers-index-rel (str dossiers-base "/INDEX.md"))

(def ^:private plan-slug-stopwords
  #{"a" "an" "the" "is" "are" "and" "or" "of" "in" "on" "at" "to" "for"
    "by" "with" "from" "as" "but" "if" "then" "than" "so"
    "what" "where" "when" "who" "whom" "why" "how" "which"
    "do" "does" "did" "can" "could" "would" "should" "shall" "will"
    "this" "that" "these" "those" "it" "its" "we" "they" "you" "i"
    "be" "been" "being" "was" "were" "have" "has" "had"
    "plan"})

(defn- dossier-default-base-dir
  "Resolve the project root for plan-agent dossiers. Delegates to
   `agent.core.config/project-dir` — the single source of truth shared
   by every functional agent's `default-base-dir` and the LLM tool
   channel. Kept as a named fn so existing test `with-redefs` sites work."
  []
  (config/project-dir))

(defn- dossier-now-ts []
  (.format (SimpleDateFormat. "yyyyMMdd-HHmmss") (Date.)))

(defn- dossier-now-iso []
  (str (Instant/now)))

(defn- dossier-now-yyyy-mm-dd-hh-mm []
  (-> (subs (dossier-now-iso) 0 16)
      (str/replace "T" " ")))

(defn- plan-slugify
  [request max-chars]
  (let [tokens (-> (str request)
                   str/lower-case
                   (str/replace #"[^a-z0-9]+" "-")
                   (str/split #"-"))
        kept   (->> tokens
                    (remove #(or (str/blank? %) (plan-slug-stopwords %)))
                    seq)
        joined (if kept (str/join "-" kept) "plan")]
    (subs joined 0 (min max-chars (count joined)))))

;; ---------------------------------------------------------------------------
;; YAML emission — slightly extended from explore/update to support
;; flow-style flat maps (used for pre.checks / post.rubric).
;; ---------------------------------------------------------------------------

(def ^:private dossier-bareword-re #"^[A-Za-z0-9_./:-]+$")

(defn- dossier-yaml-string
  [s]
  (str "\"" (-> (str s)
                (str/replace "\\" "\\\\")
                (str/replace "\"" "\\\""))
       "\""))

(defn- dossier-yaml-scalar
  [v]
  (cond
    (nil? v)        "null"
    (boolean? v)    (if v "true" "false")
    (keyword? v)    (name v)
    (number? v)     (str v)
    (string? v)     (if (and (seq v) (re-matches dossier-bareword-re v))
                      v
                      (dossier-yaml-string v))
    :else           (dossier-yaml-string (str v))))

(defn- dossier-yaml-flow-vector
  [xs]
  (str "[" (str/join ", " (map dossier-yaml-scalar xs)) "]"))

(defn- dossier-yaml-flow-map
  "Single-line flow-style flat map: `{k1: v1, k2: v2}`. Used for
   `pre.checks` and `post.rubric` — each is a flat map of keyword → :pass/
   :fail values, where one-line emission is far more readable than block
   style and our minimalist parser already handles bare scalars per slot."
  [m]
  (str "{"
       (str/join ", "
                 (map (fn [[k v]]
                        (str (name k) ": " (dossier-yaml-scalar v)))
                      m))
       "}"))

(defn- dossier-yaml-value
  [v]
  (cond
    (sequential? v) (dossier-yaml-flow-vector v)
    (map? v)        (dossier-yaml-flow-map v)
    :else           (dossier-yaml-scalar v)))

(defn- dossier-yaml-block
  "Top-level block: `<key>:` then 2-space-indented `<sub>: <value>` lines.
   `entries` is a seq of [k v] pairs to preserve insertion order."
  [block-key entries]
  (apply str
         (name block-key) ":\n"
         (mapv (fn [[k v]]
                 (str "  " (name k) ": " (dossier-yaml-value v) "\n"))
               entries)))

(defn- dossier-ordered-pairs
  [m preferred]
  (let [in-pref  (filter #(contains? m %) preferred)
        leftover (remove (set preferred) (keys m))]
    (mapv (fn [k] [k (get m k)]) (concat in-pref leftover))))

(def ^:private pre-key-order
  [:verdict :checks :exploration_path :owner :related_plans
   :gather_question :refuse_reason])

(def ^:private author-key-order
  [:action :body_bytes])

(def ^:private post-key-order
  [:verdict :rubric :revision_applied :revision_summary :holds :acceptance])

(def ^:private handoff-key-order
  [:next_agent :next_call])

(defn- build-dossier-frontmatter*
  [{:keys [slug plan_path plan_status created turn-id session-id
           pre author post handoff]}]
  (str "---\n"
       "slug: " (dossier-yaml-scalar slug) "\n"
       "agent: plan-agent\n"
       "created: " (or created (dossier-now-iso)) "\n"
       "plan_path: " (dossier-yaml-value plan_path) "\n"
       "plan_status: " (dossier-yaml-scalar (or plan_status "draft")) "\n"
       (when turn-id   (str "turn_id: "   (dossier-yaml-string turn-id) "\n"))
       (when session-id (str "session_id: " (dossier-yaml-string session-id) "\n"))
       "\n"
       (dossier-yaml-block :pre     (dossier-ordered-pairs (or pre {})     pre-key-order))
       "\n"
       (dossier-yaml-block :author  (dossier-ordered-pairs (or author {})  author-key-order))
       "\n"
       (dossier-yaml-block :post    (dossier-ordered-pairs (or post {})    post-key-order))
       "\n"
       (dossier-yaml-block :handoff (dossier-ordered-pairs (or handoff {}) handoff-key-order))
       "---\n"))

;; ---------------------------------------------------------------------------
;; YAML parsing — flat scalars + named nested blocks; flow-map values are
;; preserved as raw strings (downstream callers split as needed).
;; ---------------------------------------------------------------------------

(defn- dossier-read-frontmatter-lines
  [^java.io.File file]
  (with-open [r (io/reader file)]
    (let [reader (java.io.BufferedReader. r)
          first-line (.readLine reader)]
      (when (= "---" first-line)
        (loop [acc []]
          (let [ln (.readLine reader)]
            (cond
              (nil? ln)        nil
              (= "---" ln)     acc
              :else            (recur (conj acc ln)))))))))

(defn- dossier-parse-flow-vector
  [s]
  (let [trimmed (str/trim (str s))]
    (if (and (str/starts-with? trimmed "[") (str/ends-with? trimmed "]"))
      (let [inner (subs trimmed 1 (dec (count trimmed)))]
        (if (str/blank? inner)
          []
          (->> (str/split inner #",")
               (map str/trim)
               (map (fn [tok]
                      (if (and (str/starts-with? tok "\"") (str/ends-with? tok "\""))
                        (-> tok
                            (subs 1 (dec (count tok)))
                            (str/replace "\\\"" "\"")
                            (str/replace "\\\\" "\\"))
                        tok)))
               vec)))
      [])))

(defn- dossier-coerce-bare-value
  [s]
  (let [trimmed (str/trim s)]
    (cond
      (= trimmed "true")  true
      (= trimmed "false") false
      (= trimmed "null")  nil
      (re-matches #"-?\d+" trimmed)
      (try (Long/parseLong trimmed) (catch Exception _ trimmed))
      :else trimmed)))

(defn- dossier-parse-scalar
  [v]
  (let [v (str/trim v)]
    (cond
      (re-matches #"^\[.*\]$" v)
      (dossier-parse-flow-vector v)

      (re-matches #"^\{.*\}$" v)
      ;; Flow-style flat map — keep as raw string for now. Downstream code
      ;; that wants per-key access can str/split on `,` and `:`.
      v

      (and (str/starts-with? v "\"") (str/ends-with? v "\""))
      (-> v
          (subs 1 (dec (count v)))
          (str/replace "\\\"" "\"")
          (str/replace "\\\\" "\\"))

      :else
      (dossier-coerce-bare-value v))))

(def ^:private dossier-block-keys
  #{"pre" "author" "post" "handoff"})

(defn- parse-dossier-yaml
  [lines]
  (loop [ls    lines
         acc   {}
         block nil]
    (if (empty? ls)
      acc
      (let [ln    (first ls)
            rest* (rest ls)]
        (cond
          ;; Inside a flat block: 2-space indented `subkey: value` line
          (and (vector? block) (= :flat-block (first block))
               (re-matches #"^\s{2,}\S.*" ln))
          (if-let [[_ k v] (re-matches #"^\s+([\w_-]+):\s*(.*)$" ln)]
            (recur rest*
                   (assoc-in acc [(second block) (keyword k)] (dossier-parse-scalar v))
                   block)
            (recur rest* acc nil))

          ;; Block header (matches our nested-map keys)
          (re-matches #"^([\w_-]+):\s*$" ln)
          (let [[_ k] (re-matches #"^([\w_-]+):\s*$" ln)]
            (if (dossier-block-keys k)
              (recur rest* (assoc acc (keyword k) {}) [:flat-block (keyword k)])
              (recur rest* acc nil)))

          ;; Standard flat key: value
          (re-matches #"^([\w_-]+):\s*(.*)$" ln)
          (let [[_ k v] (re-matches #"^([\w_-]+):\s*(.*)$" ln)]
            (recur rest* (assoc acc (keyword k) (dossier-parse-scalar v)) nil))

          :else
          (recur rest* acc block))))))

;; ---------------------------------------------------------------------------
;; plan$dossier-slug
;; ---------------------------------------------------------------------------

(defcommand plan$dossier-slug
  "Deterministic kebab-case slug from a question for plan-agent dossiers (used on GATHER/REFUSE turns); caps at 60 chars."
  (fn [& {:keys [question max-chars]
          :or   {max-chars 60}}]
    (cond
      (not (string? question))
      {:error ":question is required (string)"}

      (or (not (integer? max-chars)) (<= max-chars 0))
      {:error ":max-chars must be a positive integer"}

      :else
      {:slug (plan-slugify question max-chars)}))
  :input-schema  [:map
                  [:question  [:string {:desc "User question to slugify"}]]
                  [:max-chars {:optional true} [:int {:desc "Cap on slug length in chars (default 60)"}]]]
  :output-schema [:map
                  [:slug  [:string {:desc "Kebab-case slug, stopwords dropped"}]]
                  [:error [:string {:desc "Error if validation failed"}]]])

;; ---------------------------------------------------------------------------
;; plan$dossier-frontmatter
;; ---------------------------------------------------------------------------

(defcommand plan$dossier-frontmatter
  "Build a YAML frontmatter block for a plan-agent dossier with pre/author/post/handoff sub-blocks."
  (fn [& {:keys [slug plan-path plan-status pre author post handoff
                 created turn-id session-id]}]
    (cond
      (not (string? slug))    {:error ":slug is required (string)"}
      :else
      (let [fm (build-dossier-frontmatter*
                {:slug         slug
                 :plan_path    plan-path
                 :plan_status  plan-status
                 :pre          pre
                 :author       author
                 :post         post
                 :handoff      handoff
                 :created      created
                 :turn-id      turn-id
                 :session-id   session-id})]
        (mulog/log ::plan.dossier-frontmatter
                   :slug slug
                   :pre-verdict (or (:verdict pre) :n-a)
                   :post-verdict (or (:verdict post) :n-a)
                   :next-agent (:next_agent handoff))
        {:frontmatter fm})))
  :input-schema  [:map
                  [:slug         [:string {:desc "Plan slug (or dossier slug for GATHER/REFUSE turns)"}]]
                  [:plan-path    {:optional true} [:string {:desc "Repo-relative path to the plan body (or null when no plan was authored)"}]]
                  [:plan-status  {:optional true} [:string {:desc "Plan status: draft | in-progress | completed | abandoned"}]]
                  [:pre          {:optional true} [:map    {:desc "Pre-flight outcomes (verdict, checks, exploration_path, owner, related_plans, gather_question, refuse_reason)"}]]
                  [:author       {:optional true} [:map    {:desc "Author outcomes (action :created/:updated/:unchanged, body_bytes)"}]]
                  [:post         {:optional true} [:map    {:desc "Post-flight outcomes (verdict, rubric, revision_applied, revision_summary, holds, acceptance)"}]]
                  [:handoff      {:optional true} [:map    {:desc "Handoff suggestion (next_agent, next_call)"}]]
                  [:created      {:optional true} [:string {:desc "ISO-8601 created timestamp (default: now)"}]]
                  [:turn-id      {:optional true} [:string {:desc "Trajectory turn-id for cross-reference"}]]
                  [:session-id   {:optional true} [:string {:desc "Trajectory session-id for cross-reference"}]]]
  :output-schema [:map
                  [:frontmatter [:string {:desc "Full YAML frontmatter block, trailing newline included"}]]
                  [:error       [:string {:desc "Error if validation failed"}]]])

;; ---------------------------------------------------------------------------
;; plan$dossier-write
;; ---------------------------------------------------------------------------

(defn- dossier-existing-slug-count
  [base-dir slug]
  (let [dir (io/file base-dir dossiers-dir-rel)]
    (let [^java.io.File dir dir]
      (if (.isDirectory dir)
        (let [slug-re (re-pattern (str "(?i)-" (java.util.regex.Pattern/quote slug)
                                       "(-\\d+)?\\.md$"))]
          (->> (.listFiles dir)
               (filter (fn [^java.io.File f] (.isFile f)))
               (map (fn [^java.io.File f] (.getName f)))
               (filter #(re-find slug-re %))
               count))
        0))))

(defn- dossier-final-slug-with-suffix
  [base-dir base-slug]
  (let [n (dossier-existing-slug-count base-dir base-slug)]
    (if (zero? n)
      base-slug
      (str base-slug "-" (inc n)))))

(defcommand plan$dossier-write
  "Write a plan-agent dossier under .brainyard/agents/plan-agent/dossiers/ as <ts>-<slug>.md; appends -N suffix on slug collision."
  (fn [& {:keys [slug content base-dir]
          :or   {base-dir (dossier-default-base-dir)}}]
    (cond
      (not (string? slug))    {:error ":slug is required (string)"}
      (not (string? content)) {:error ":content is required (string)"}
      (not (re-find #"(?s)\A---\n.+?\n---\n" content))
      {:error (str ":content must begin with a YAML frontmatter block "
                   "(---\\n...\\n---\\n). Build it via plan$dossier-frontmatter "
                   "and prepend before writing: "
                   "(plan$dossier-write :slug ... :content (str fm body))")}
      :else
      (let [final-slug (dossier-final-slug-with-suffix base-dir slug)
            ts         (dossier-now-ts)
            rel-path   (str dossiers-dir-rel "/" ts "-" final-slug ".md")
            file       (io/file base-dir rel-path)]
        (.mkdirs (.getParentFile file))
        (spit file content)
        (mulog/log ::plan.dossier-write
                   :slug final-slug :path rel-path
                   :bytes (count content) :collision? (not= slug final-slug))
        {:path (.getAbsolutePath file) :slug final-slug :ts ts})))
  :input-schema  [:map
                  [:slug     [:string {:desc "Plan slug (or dossier slug — will get -N suffix on collision)"}]]
                  [:content  [:string {:desc "Full file content (frontmatter + body)"}]]
                  [:base-dir {:optional true} [:string {:desc "Working directory (default: project root)"}]]]
  :output-schema [:map
                  [:path  [:string {:desc "Absolute path of the written dossier"}]]
                  [:slug  [:string {:desc "Final slug actually used (may have -N suffix)"}]]
                  [:ts    [:string {:desc "Timestamp portion of filename (yyyyMMdd-HHmmss)"}]]
                  [:error [:string {:desc "Error if validation failed"}]]])

;; ---------------------------------------------------------------------------
;; plan$dossier-index-append
;; ---------------------------------------------------------------------------

(def ^:private valid-pre-verdicts
  "v1 pre-flight verdicts. The writer is the backstop — an unknown value
   (incl. the v1.5-only :revise) is coerced to :n-a so INDEX tokens stay
   grep-clean, instead of trusting whatever the LLM emitted."
  #{:go :gather :refuse})

(def ^:private valid-post-verdicts
  "v1 post-flight verdicts (:revise is deferred to v1.5)."
  #{:pass :hold})

(defn- coerce-verdict
  "Coerce `v` to a keyword in `valid`; an unknown value → :n-a (logged).
   nil passes through as nil (post-verdict is optional)."
  [v valid kind]
  (let [k (cond (keyword? v) v (string? v) (keyword v) :else nil)]
    (cond
      (nil? k)  nil
      (valid k) k
      :else     (do (mulog/warn ::plan.invalid-verdict :kind kind :verdict k :valid valid)
                    :n-a))))

(defn- dossier-index-line
  [{:keys [path slug pre-verdict post-verdict next-agent]}]
  (let [filename (-> path (str/split #"/") last)
        pre-tok  (str "pre:" (name pre-verdict))
        post-tok (str "post:" (if (nil? post-verdict) "n/a" (name post-verdict)))
        next-tok (str "→ " (name (or next-agent :unspecified)))]
    (str "- " (dossier-now-yyyy-mm-dd-hh-mm)
         " [" slug "](" dossiers-subdir "/" filename ") — "
         pre-tok " · " post-tok " · " next-tok "\n")))

(defcommand plan$dossier-index-append
  "Prepend a one-line entry to .brainyard/agents/plan-agent/INDEX.md (newest-first) with pre/post verdicts and next-agent."
  (fn [& {:keys [path slug pre-verdict post-verdict next-agent base-dir]
          :or   {base-dir (dossier-default-base-dir)}}]
    (cond
      (not (string? path)) {:error ":path is required (string)"}
      (not (string? slug)) {:error ":slug is required (string)"}
      (not (or (keyword? pre-verdict) (string? pre-verdict)))
      {:error ":pre-verdict is required (keyword or string)"}
      :else
      (let [pre-kw   (or (coerce-verdict pre-verdict valid-pre-verdicts :pre) :n-a)
            post-kw  (coerce-verdict post-verdict valid-post-verdicts :post)
            next-kw  (cond
                       (keyword? next-agent) next-agent
                       (string? next-agent)  (keyword next-agent)
                       :else                 :unspecified)
            line     (dossier-index-line {:path path :slug slug
                                          :pre-verdict pre-kw
                                          :post-verdict post-kw
                                          :next-agent next-kw})
            file     (io/file base-dir dossiers-index-rel)
            existing (if (.isFile file) (slurp file) "")]
        (.mkdirs (.getParentFile file))
        (spit file (str line existing))
        (mulog/log ::plan.dossier-index
                   :slug slug :path path
                   :pre-verdict pre-kw :post-verdict post-kw
                   :next-agent next-kw)
        {:appended true :line line})))
  :input-schema  [:map
                  [:path         [:string {:desc "Repo-relative path of the dossier (from plan$dossier-write)"}]]
                  [:slug         [:string {:desc "Plan or dossier slug used"}]]
                  [:pre-verdict  [:string {:desc "Pre-flight verdict: go | gather | refuse"}]]
                  [:post-verdict {:optional true} [:string {:desc "Post-flight verdict: pass | revise | hold (or omit when no AUTHOR ran)"}]]
                  [:next-agent   {:optional true} [:string {:desc "Recommended next agent (e.g. todo-agent | user)"}]]
                  [:base-dir     {:optional true} [:string {:desc "Working directory (default: project root)"}]]]
  :output-schema [:map
                  [:appended [:boolean {:desc "true on success"}]]
                  [:line     [:string  {:desc "The exact line that was prepended"}]]
                  [:error    [:string  {:desc "Error if validation failed"}]]])

;; ---------------------------------------------------------------------------
;; plan$read-dossier
;; ---------------------------------------------------------------------------

(defcommand plan$read-dossier
  "Read and parse just the leading YAML frontmatter from a plan-agent dossier (cheap; downstream agents use this to inspect verdicts)."
  (fn [& {:keys [path base-dir]
          :or   {base-dir (dossier-default-base-dir)}}]
    (cond
      (not (string? path))
      {:error ":path is required (string)"}

      :else
      (let [file (if (.isAbsolute (io/file path))
                   (io/file path)
                   (io/file base-dir path))]
        (cond
          (not (.isFile file))
          {:error (str "File not found: " path)}

          :else
          (if-let [lines (dossier-read-frontmatter-lines file)]
            (parse-dossier-yaml lines)
            {:error (str "No frontmatter block at " path " (file did not start with ---)")})))))
  :input-schema  [:map
                  [:path     [:string {:desc "Repo-relative or absolute path to a plan-agent dossier"}]]
                  [:base-dir {:optional true} [:string {:desc "Working directory (default: project root)"}]]]
  :output-schema [:map
                  [:slug        {:optional true} [:string {:desc "Plan slug"}]]
                  [:plan_path   {:optional true} [:string {:desc "Repo-relative path to the plan body (or null on GATHER/REFUSE)"}]]
                  [:plan_status {:optional true} [:string {:desc "Plan status at dossier time"}]]
                  [:pre         {:optional true} [:map    {:desc "Pre-flight sub-block"}]]
                  [:author      {:optional true} [:map    {:desc "Author sub-block"}]]
                  [:post        {:optional true} [:map    {:desc "Post-flight sub-block"}]]
                  [:handoff     {:optional true} [:map    {:desc "Handoff sub-block"}]]
                  [:error       {:optional true} [:string {:desc "Error if file missing or no frontmatter"}]]])

;; ---------------------------------------------------------------------------
;; plan$next-handoff
;; ---------------------------------------------------------------------------

(defn- handoff-from-verdicts
  [pre-verdict post-verdict slug dossier-path]
  (let [ctx (cond-> "" dossier-path (str " :agent-context \"" dossier-path "\""))]
    (case (or post-verdict :n-a)
      :pass
      {:next-agent "todo-agent"
       :next-call (str "(todo-agent {:question \"Spawn a todo for this plan.\""
                       ctx "})")}

      :hold
      {:next-agent "user"
       :next-call (str "Resolve holds, then re-call plan-agent: "
                       "(plan-agent {:question \"<refined request>\""
                       ctx "})")}

      :revise
      ;; Revise → automatic plan body re-run via doc$update; don't recommend next agent yet.
      {:next-agent "plan-agent"
       :next-call (str "(plan-agent {:question \"Re-run post-flight with revision applied.\""
                       ctx "})")}

      ;; No POST-FLIGHT ran (GATHER / REFUSE) — derive from pre-verdict.
      :n-a
      (case pre-verdict
        :gather
        {:next-agent "user"
         :next-call (str "Provide the missing input named in the dossier's gather_question; "
                         "then re-call plan-agent.")}

        :refuse
        {:next-agent "none"
         :next-call (str "See dossier.refuse_reason for the redirect (typically update-agent or "
                         "explore-agent).")}

        ;; Unknown — fall through.
        {:next-agent "user"
         :next-call (str "Inspect the dossier and decide next step.")}))))

(defcommand plan$next-handoff
  "Compute recommended next agent and exact direct-invocation form from pre/post verdicts; single source of truth for dossier handoff block."
  (fn [& {:keys [pre post slug dossier-path]}]
    (let [pre-v  (when pre  (:verdict pre))
          post-v (when post (:verdict post))]
      (handoff-from-verdicts pre-v post-v slug dossier-path)))
  :input-schema  [:map
                  [:pre          {:optional true} [:map    {:desc "Pre-flight outcome map (only :verdict required)"}]]
                  [:post         {:optional true} [:map    {:desc "Post-flight outcome map (only :verdict required); omit on GATHER/REFUSE"}]]
                  [:slug         {:optional true} [:string {:desc "Plan slug"}]]
                  [:dossier-path {:optional true} [:string {:desc "Repo-relative path to the dossier file"}]]]
  :output-schema [:map
                  [:next-agent [:string {:desc "Recommended next agent: todo-agent | user | none | plan-agent"}]]
                  [:next-call  [:string {:desc "Exact direct-invocation form (<agent-name> {…}) for the dispatcher"}]]])

;; ---------------------------------------------------------------------------
;; Plan-agent dossier helper roster
;; ---------------------------------------------------------------------------

(def plan-dossier-helpers
  "Vector of all plan$dossier-* / plan$read-dossier / plan$next-handoff
   vars. plan-agent appends these to its :agent-tools roster so the SCI
   sandbox auto-binds them (callable as `(plan$dossier-slug ...)` etc. in
   a clojure fence)."
  [#'plan$dossier-slug
   #'plan$dossier-frontmatter
   #'plan$dossier-write
   #'plan$dossier-index-append
   #'plan$read-dossier
   #'plan$next-handoff])

;; ============================================================================
;; Auto-persist hook
;;
;; The agent instruction tells the LLM to call plan$dossier-write at PERSIST
;; time. Capable models usually do; smaller models often skip the helpers and
;; just ASSERT in the answer text that they wrote a dossier — sometimes with
;; hallucinated paths. This `:agent.ask/post` hook is the safety net: when
;; plan-agent emits an answer that lacks a verifiable `Saved dossier: <path>`
;; line (path missing OR claimed-but-doesn't-exist-on-disk), the hook
;; reconstructs a minimal dossier from the answer text and persists it.
;;
;; Caveat: `:agent.ask/post` is observe-only (return values ignored). The
;; hook can write the artifact and append INDEX.md, but it CANNOT inject a
;; `Saved dossier:` line into the answer the caller already received.
;; Downstream agents that need to discover auto-persisted dossiers should
;; scan `.brainyard/agents/plan-agent/dossiers/` (newest-first by filename) rather
;; than greppning the prior answer text.
;; ============================================================================

(def saved-plan-prefix
  "Stable prefix the agent emits in its answer when a plan body was
   authored this turn. Public so tests + downstream consumers can grep
   for it without re-defining the constant."
  "Saved plan: ")

(def saved-dossier-prefix
  "Stable prefix the agent emits in its answer when a dossier was
   persisted this turn. Public so tests + downstream consumers can grep
   for it without re-defining the constant."
  "Saved dossier: ")

(defn- plan-agent? [agent]
  (try
    (= :plan-agent (proto/defagent-type agent))
    (catch Throwable _ false)))

(defn- extract-line-after
  "Find the first line that starts with `prefix` and return what follows
   (trimmed, up to the newline). Tolerant of leading punctuation/whitespace."
  [^String answer ^String prefix]
  (when (and (string? answer) (string? prefix))
    (when-let [start (str/index-of answer prefix)]
      (let [after (subs answer (+ start (count prefix)))
            end   (or (str/index-of after "\n") (count after))]
        (-> (subs after 0 end)
            ;; Strip surrounding `code` markers and quotes the LLM sometimes adds.
            (str/replace #"^[`'\"\s]+" "")
            (str/replace #"[`'\"\.,\s]+$" "")
            str/trim)))))

(defn- absolute->repo-relative
  "Turn an absolute path under base-dir into a repo-relative one. Pass-through
   if the path is already relative or lies outside base-dir."
  [base-dir ^String path]
  (cond
    (or (nil? path) (str/blank? path))
    nil

    (and base-dir (str/starts-with? path (str base-dir "/")))
    (subs path (inc (count base-dir)))

    :else path))

(defn- dossier-already-saved?
  "Truthy when the answer claims a dossier path AND the file actually exists
   on disk. A claim with no on-disk file means the LLM hallucinated the
   path — the hook will replace it. The answer-text check from
   explore-agent's hook isn't enough here because we've seen capable models
   emit fake timestamps and paths verbatim."
  [^String answer base-dir]
  (when-let [claimed (extract-line-after answer saved-dossier-prefix)]
    (let [file (if (.isAbsolute (io/file claimed))
                 (io/file claimed)
                 (io/file base-dir claimed))]
      (.isFile file))))

(defn- detect-pre-verdict
  "Infer pre-flight verdict from the answer's stable-prefix lines:
   - `Refused: <reason>`  → :refuse
   - `Need: <input>`       → :gather
   - otherwise             → :go (the only state where AUTHOR runs)"
  [^String answer]
  (cond
    (re-find #"(?im)^\s*Refused:\s*\S" answer) :refuse
    (re-find #"(?im)^\s*Need:\s*\S"    answer) :gather
    :else                                       :go))

(defn- detect-post-verdict
  "Infer post-flight verdict only when AUTHOR ran (pre = :go):
   - `Hold: <reason>` → :hold
   - otherwise         → :pass
   Returns nil when pre ≠ :go (no AUTHOR happened, so no rubric)."
  [^String answer pre-verdict]
  (cond
    (not= :go pre-verdict)                  nil
    (re-find #"(?im)^\s*Hold:\s*\S" answer) :hold
    :else                                   :pass))

(defn- slug-from-plan-path
  "Turn a plan-file path (`...path/<slug>.md`) into the bare slug."
  [^String path]
  (when (and path (str/ends-with? path ".md"))
    (-> path
        (str/split #"/")
        last
        (str/replace #"\.md$" ""))))

(defn- one-line-summary
  "First non-blank prose paragraph, whitespace-collapsed, capped at
   `max-chars`. Skips markdown headings, code fences, and the agent's
   stable-prefix answer lines (Saved/Need/Refused/Hold/Suggested/Next)."
  [^String answer max-chars]
  (let [paragraphs (->> (str/split (or answer "") #"\n\n")
                        (map str/trim)
                        (remove str/blank?))
        prose? (fn [^String p]
                 (and (not (str/starts-with? p "#"))
                      (not (str/starts-with? p "|"))
                      (not (str/starts-with? p "```"))
                      (not (str/starts-with? p "Saved "))
                      (not (str/starts-with? p "Need:"))
                      (not (str/starts-with? p "Refused:"))
                      (not (str/starts-with? p "Hold:"))
                      (not (str/starts-with? p "Suggested:"))
                      (not (str/starts-with? p "Next:"))))
        chosen (or (first (filter prose? paragraphs))
                   (first paragraphs)
                   "")
        flat   (-> chosen
                   (str/replace #"\s+" " ")
                   (str/replace #"^#+\s*" "")
                   str/trim)]
    (subs flat 0 (min max-chars (count flat)))))

(defn materialize-auto-dossier!
  "Core of the auto-persist hook. Given an answer string + question + base-dir
   (no agent-state required), reconstruct a minimal dossier from answer text
   and write it via the standard helpers. Returns
   `{:path :slug :pre-verdict :post-verdict}` on success, `nil` when skipped
   (already-saved-on-disk).

   Extracted as a public fn so unit tests can drive it directly without
   needing a real agent instance."
  [{:keys [answer question base-dir]}]
  (cond
    (or (not (string? answer)) (str/blank? answer))
    nil

    (dossier-already-saved? answer base-dir)
    nil

    :else
    (let [pre-verdict   (detect-pre-verdict answer)
          post-verdict  (detect-post-verdict answer pre-verdict)
          plan-path-raw (extract-line-after answer saved-plan-prefix)
          plan-path     (when plan-path-raw
                          (absolute->repo-relative base-dir plan-path-raw))
          slug          (or (slug-from-plan-path plan-path-raw)
                            (:slug (plan$dossier-slug
                                    :question (or question "auto-persisted")))
                            "plan")
          summary       (one-line-summary answer 200)
          handoff       (let [pre-map  {:verdict pre-verdict}
                              post-map (when post-verdict {:verdict post-verdict})
                              ;; plan$next-handoff returns Clojure-idiomatic
                              ;; :next-agent / :next-call; the schema (§7.2)
                              ;; specs `next_agent` / `next_call`. Translate
                              ;; before passing to the frontmatter builder so
                              ;; the on-disk layout matches the contract.
                              kebab     (if post-map
                                          (#'plan$next-handoff :pre pre-map :post post-map :slug slug)
                                          (#'plan$next-handoff :pre pre-map :slug slug))]
                          {:next_agent (:next-agent kebab)
                           :next_call  (:next-call  kebab)})
          fm            (:frontmatter
                         (#'plan$dossier-frontmatter
                          :slug         slug
                          :plan-path    plan-path
                          :plan-status  "draft"
                          :pre          {:verdict pre-verdict}
                          :author       (cond-> {}
                                          plan-path-raw (assoc :action :created))
                          :post         (when post-verdict {:verdict post-verdict})
                          :handoff      handoff))
          body          (str "# Plan dossier (auto-persisted)\n\n"
                             "*Reconstructed from the agent's answer text — the LLM did "
                             "not call plan$dossier-write itself this turn.*\n\n"
                             "## Summary\n" summary "\n\n"
                             "## Original answer\n" answer "\n")
          write-result  (#'plan$dossier-write :slug slug
                                              :content (str fm body)
                                              :base-dir base-dir)]
      (if (:error write-result)
        (do (mulog/warn ::plan.auto-persist-write-failed
                        :slug slug :error (:error write-result))
            nil)
        (do
          (#'plan$dossier-index-append
           :path         (:path write-result)
           :slug         (:slug write-result)
           :pre-verdict  pre-verdict
           :post-verdict post-verdict
           :next-agent   (or (:next_agent handoff) :unspecified)
           :base-dir     base-dir)
          (mulog/log ::plan.auto-persist
                     :slug          (:slug write-result)
                     :path          (:path write-result)
                     :pre-verdict   pre-verdict
                     :post-verdict  post-verdict
                     :answer-chars  (count answer)
                     :had-plan-path? (boolean plan-path-raw))
          {:path         (:path write-result)
           :slug         (:slug write-result)
           :pre-verdict  pre-verdict
           :post-verdict post-verdict})))))

(defn plan-auto-persist
  "Gated handler for `:agent.ask/finalize`. Materializes the dossier when the
   LLM skipped the FINAL-STEP checklist AND returns a `:replace` decision
   injecting the absent `Saved dossier: <path>` handoff line into the answer.
   Idempotent — no-op when the line is present or nothing was persisted.
   Defensive — any failure is logged but never re-thrown."
  [{:keys [agent input result]}]
  (try
    (when (and (plan-agent? agent) (map? result))
      (let [question  (or (when (string? input) input)
                          (some-> input :question str)
                          "(question not captured)")
            answer    (str (:answer result))
            persisted (materialize-auto-dossier!
                       {:answer   (:answer result)
                        :question question
                        :base-dir (dossier-default-base-dir)})
            path      (:path persisted)]
        (when (and path (not (str/includes? answer "Saved dossier:")))
          {:result      :replace
           :reason      "injected absent Saved-dossier handoff line"
           :replacement (assoc result :answer (str answer "\n\nSaved dossier: " path))})))
    (catch Throwable t
      (mulog/error ::plan.auto-persist-failed
                   :exception t
                   :agent-id  (try (proto/agent-id agent)
                                   (catch Throwable _ "unknown")))
      nil)))

(defn install-auto-persist!
  "Register the auto-persist hook on the gated `:agent.ask/finalize` event.
   Idempotent — `register-hook!` replaces by id. The :match predicate scopes
   the hook to plan-agent instances only, so other agents are unaffected.

   Tag `:source :plan-agent` lets apps opt out via
   `(hooks/unregister-source! :plan-agent)`."
  []
  (hooks/register-hook!
   :agent.ask/finalize
   ::plan-auto-persist
   plan-auto-persist
   :source   :plan-agent
   :match    (fn [{:keys [agent]}] (plan-agent? agent))
   :priority 50))

;; Self-install at namespace load so anyone requiring plan-agent (which
;; transitively requires this ns) gets the safety net for free.
(install-auto-persist!)
