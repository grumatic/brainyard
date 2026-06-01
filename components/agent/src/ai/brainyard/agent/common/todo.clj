;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: Apache-2.0
;; Licensed under the Apache License, Version 2.0 (the "License"); you may
;; not use this file except in compliance with the License. See LICENSE at
;; the repository root and CONTRIBUTING.md for contribution terms.

(ns ai.brainyard.agent.common.todo
  "Persistent todo management for agents.
   A todo is a simplified plan: a goal plus a sequence of items to do.
   Stored as `<slug>.md` files in `.brainyard/agents/todo-agent/todos/` (separate
   from plans in `.brainyard/agents/plan-agent/plans/`). The legacy locations
   `.brainyard/todos/` and `.brainyard/plans/` are read-fallback for one
   release; new writes always go to the new paths. `bb migrate:todo-agent`
   copies legacy todos across in one shot.

   A plan and its associated todo MAY share the same slug — they live in
   different directories so there is no filesystem collision, and a shared
   slug is the natural way to link the two artifacts.

   The frontmatter `file-type: todo` field is still emitted as a
   defensive discriminator: if a file is moved between todos/ and plans/
   dirs by hand, the validator catches the mistake and refuses to treat
   it as the wrong kind.

   Each file has YAML-like frontmatter (id, file-type, title, scope, status,
   created, updated), a free-form `## Goal` section (one short paragraph),
   and a `## Todo` section with GitHub-style task checkboxes.

   Public API layers:
   - Plain functions (create-todo, read-todo, update-todo, delete-todo,
     list-todos, todo-exists?, mark-item-done, add-item, reopen-todo,
     update-goal, reset-item, todo-progress) — used from sandbox bindings
     and other Clojure callers.
   The user-facing tool surface is the polymorphic `doc$*` family in
   `ai.brainyard.agent.common.doc`."
  (:require [ai.brainyard.agent.core.config :as config]
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
  "Generate a unique random 3-word slug for a todo file.
   Checks the todos directory for `<slug>.md` collisions, retrying up
   to max-retries times. Plans live in a separate directory, so a todo
   may legitimately share a slug with an existing plan."
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
;; Item Parsing
;; ============================================================================

;; ============================================================================
;; Item :tags — inline routing/coverage hints (todo-agent v2)
;;
;; Item lines support an optional trailing flow-style block:
;;
;;     - [ ] description {via: update-agent, covers: [\"...\", \"...\"]}
;;
;; Backwards compatible: lines without the block parse as before (no :tags
;; key). When :tags is present, `:via` is a keyword (e.g. :update-agent,
;; :bash, :mcp, :manual, :explore-agent, :read-only) and `:covers` is a
;; vector of acceptance-criterion strings the item supports.
;; ============================================================================

(defn- parse-tags-block
  "Parse a `{via: kw, covers: [\"a\", \"b\"]}` flow-style tag bundle.
   Returns a `{:via :keyword :covers [strings]}` map (partial fields ok),
   or nil for an unrecognized shape. Tolerant of extra whitespace; ignores
   keys other than :via and :covers."
  [^String s]
  (let [trimmed (str/trim (or s ""))
        inner   (when (and (str/starts-with? trimmed "{")
                           (str/ends-with? trimmed "}"))
                  (subs trimmed 1 (dec (count trimmed))))]
    (when inner
      (let [;; Split on top-level commas (depth-0; not inside `[...]`).
            split-on-top-comma
            (loop [chars (seq inner) acc [] cur (StringBuilder.) depth 0]
              (cond
                (empty? chars) (conj acc (str cur))
                (= \[ (first chars))
                (recur (rest chars) acc (.append cur \[) (inc depth))
                (= \] (first chars))
                (recur (rest chars) acc (.append cur \]) (dec depth))
                (and (= \, (first chars)) (zero? depth))
                (recur (rest chars) (conj acc (str cur)) (StringBuilder.) 0)
                :else
                (recur (rest chars) acc (.append cur (first chars)) depth)))
            kvs (->> split-on-top-comma
                     (map str/trim)
                     (remove str/blank?)
                     (keep (fn [pair]
                             (when-let [[_ k v] (re-matches #"^([\w_-]+)\s*:\s*(.+)$" pair)]
                               [(keyword (str/trim k)) (str/trim v)])))
                     (into {}))
            via (when-let [v (:via kvs)]
                  (-> v
                      (str/replace #"^['\"]+|['\"]+$" "")
                      keyword))
            covers (when-let [v (:covers kvs)]
                     (let [v (str/trim v)]
                       (when (and (str/starts-with? v "[") (str/ends-with? v "]"))
                         (let [body (subs v 1 (dec (count v)))]
                           (if (str/blank? body)
                             []
                             (->> (str/split body #",")
                                  (map str/trim)
                                  (mapv (fn [tok]
                                          (-> tok
                                              (str/replace #"^[\"']+|[\"']+$" ""))))))))))]
        (cond-> {}
          via            (assoc :via via)
          (some? covers) (assoc :covers covers))))))

(def ^:private valid-via
  "Canonical :via routing tags (R5). The writer is the backstop: an
   out-of-set value from the LLM is dropped with a warning rather than
   trusted, instead of relying on LLM self-critique."
  #{:update-agent :bash :mcp :manual :explore-agent :read-only})

(defn- normalize-tags
  "Coerce a tags map from LLM wire format (string keys, string :via) to
   the canonical shape {:via :keyword :covers [strings]}. Accepts both
   keyword-keyed and string-keyed inputs. An unrecognized :via (not in
   `valid-via`) is dropped + logged — the todo still writes, just without a
   bogus routing tag. Returns nil for nil/empty."
  [tags]
  (when (and tags (map? tags) (seq tags))
    (let [raw-via (or (:via tags) (get tags "via"))
          via     (when raw-via (if (keyword? raw-via) raw-via (keyword (str raw-via))))
          via     (cond
                    (nil? via)       nil
                    (valid-via via)  via
                    :else            (do (mulog/warn ::todo.invalid-via
                                                     :via via :valid valid-via)
                                         nil))
          covers  (or (:covers tags) (get tags "covers"))]
      (cond-> {}
        via    (assoc :via via)
        covers (assoc :covers (mapv str covers))))))

(defn- render-tags-block
  "Render an item's :tags map as inline ` {via: x, covers: [\"a\", \"b\"]}`.
   Empty / missing tags → empty string. :via is a bareword keyword name;
   :covers is a flow-vector with double-quoted strings (quotes preserve
   commas + spaces inside criterion strings)."
  [tags]
  (let [via    (:via tags)
        covers (:covers tags)
        parts  (cond-> []
                 via
                 (conj (str "via: " (name via)))

                 (some? covers)
                 (conj (str "covers: ["
                            (str/join ", "
                                      (map (fn [c]
                                             (str "\""
                                                  (-> (str c)
                                                      (str/replace "\\" "\\\\")
                                                      (str/replace "\"" "\\\""))
                                                  "\""))
                                           covers))
                            "]")))]
    (if (seq parts)
      (str " {" (str/join ", " parts) "}")
      "")))

(defn- parse-items
  "Parse GitHub-style task checkboxes from markdown body.
   Todos use the simplified format (no `(independent)`, no `— result:`
   suffix), with an optional trailing `:tags` block: `{via: x, covers:
   [...]}`. Items without the block parse as before — backwards compat
   for legacy todos pre-redesign.
   Returns vector of item maps with :description, :done, and :tags
   (when present)."
  [body]
  (let [lines (str/split-lines body)
        ;; Match: `- [ ] description` with an optional `... {tag-block}` tail.
        ;; The tag block is the LAST `{...}` on the line; descriptions
        ;; with literal `{...}` content are uncommon enough that we match
        ;; greedily on description, then consume one trailing `{...}`.
        item-re #"^- \[([ xX])\]\s+(.+?)(?:\s+(\{[^{}]*\}))?\s*$"]
    (vec
     (keep (fn [line]
             (when-let [[_ check desc tags-raw] (re-matches item-re (str/trim line))]
               (cond-> {:description (str/trim desc)
                        :done (contains? #{"x" "X"} check)}
                 (and tags-raw (not (str/blank? tags-raw)))
                 (assoc :tags (or (parse-tags-block tags-raw) {})))))
           lines))))

(defn- render-items
  "Render items as GitHub-style task checkboxes with optional inline tags."
  [items]
  (str/join "\n"
            (map (fn [{:keys [description done tags]}]
                   (str "- [" (if done "x" " ") "] " description
                        (render-tags-block tags)))
                 items)))

;; ============================================================================
;; Todo <-> Markdown Conversion
;; ============================================================================

(def ^:const file-type
  "Frontmatter discriminator for todo files. Defensive — todos live in
   `.brainyard/todos/` while plans live in `.brainyard/agents/plan-agent/plans/`
   (legacy: `.brainyard/plans/`), so the directory normally distinguishes
   them. The frontmatter field guards against a misfiled file from being
   treated as the wrong kind."
  "todo")

(defn todo->md
  "Convert a todo map to markdown string with frontmatter.
   Always emits `file-type: todo` so the file is unambiguously a todo,
   regardless of filename. Format: frontmatter + # Title + ## Goal
   (optional) + ## Todo."
  [{:keys [id title scope status created updated goal items]}]
  (let [fm (render-frontmatter
            {:id id
             :file-type file-type
             :title title
             :scope (name (or scope :project))
             :status (name (or status :draft))
             :created (str (or created (Instant/now)))
             :updated (str (or updated (Instant/now)))})
        content (str "\n# " title "\n"
                     (when-not (str/blank? goal)
                       (str "\n## Goal\n" goal "\n"))
                     "\n## Todo\n" (render-items (or items [])) "\n")]
    (str fm "\n" content)))

(defn md->todo
  "Parse a markdown string into a todo map.
   Extracts the ## Goal section (text between `## Goal` and `## Todo`)
   and the ## Todo section (checkboxes)."
  [md-string]
  (let [[fm body-str] (parse-frontmatter md-string)
        ;; Extract goal section
        goal (when-let [[_ g] (re-find #"(?s)## Goal\s*\n(.*?)(?=\n## Todo\b|\z)" body-str)]
               (let [trimmed (str/trim g)]
                 (when-not (str/blank? trimmed) trimmed)))
        items (parse-items body-str)]
    (cond-> {:id (:id fm)
             :title (:title fm)
             :scope (keyword (or (:scope fm) "project"))
             :status (keyword (or (:status fm) "draft"))
             :created (:created fm)
             :updated (:updated fm)
             :items items}
      goal (assoc :goal goal))))

;; ============================================================================
;; Path Resolution
;; ============================================================================

(def ^:const todos-subpath
  "Repo-relative subpath under the project root where new todos live."
  ".brainyard/agents/todo-agent/todos")

(def ^:const legacy-todos-subpath
  "Read-only fallback location for todos authored before the storage
   migration. Removed in a future release after `bb migrate:todo-agent`
   has had a release to settle."
  ".brainyard/todos")

(defn- todos-dir
  "Resolve the todos directory for a given scope (write target + primary
   read). Returns a File, or nil if dirs config is missing."
  [dirs scope]
  (let [base (case scope
               :user (str (System/getProperty "user.home") "/" todos-subpath)
               (when-let [project-dir (or (:project-dir dirs)
                                          (:base-dir dirs))]
                 (str project-dir "/" todos-subpath)))]
    (when base (io/file base))))

(defn- legacy-todos-dir
  "Resolve the legacy todos directory for read-fallback. Mirrors
   `todos-dir` shape exactly except for the subpath."
  [dirs scope]
  (let [base (case scope
               :user (str (System/getProperty "user.home") "/" legacy-todos-subpath)
               (when-let [project-dir (or (:project-dir dirs)
                                          (:base-dir dirs))]
                 (str project-dir "/" legacy-todos-subpath)))]
    (when base (io/file base))))

(defn- todo-file
  "Resolve the file path for a todo slug in the given scope. Always points
   at the NEW location — `find-todo-file` below handles legacy fallback for
   read paths."
  [dirs scope slug]
  (when-let [dir (todos-dir dirs scope)]
    (io/file dir (str slug ".md"))))

(defn- find-todo-file
  "Read-side resolver: return the first existing file for `slug` across
   the new location and the legacy fallback, in that order. Returns nil
   when neither path has it."
  [dirs scope slug]
  (let [new-f    (todo-file dirs scope slug)
        legacy-f (when-let [dir (legacy-todos-dir dirs scope)]
                   (io/file dir (str slug ".md")))]
    (cond
      (and new-f    (.exists ^java.io.File new-f))    new-f
      (and legacy-f (.exists ^java.io.File legacy-f)) legacy-f
      :else                                            nil)))

(defn- todo-md?
  "Validity check: a todo markdown blob must declare `file-type: todo` in
   its frontmatter, plus :id and :title. Discriminates todos from plans
   (which carry `file-type: plan`) and from loose .md files."
  [md-string]
  (let [[fm _] (parse-frontmatter md-string)]
    (boolean (and (= file-type (:file-type fm))
                  (:id fm)
                  (:title fm)))))

(defn- safe-read-todo-file
  "Slurp + parse a todo file, returning {:fm <frontmatter> :body <str>} on
   success or nil on any failure (with mulog/warn). Skips non-todo .md
   files via `todo-md?`."
  [^java.io.File f]
  (try
    (let [md (slurp f)]
      (if (todo-md? md)
        (let [[fm body] (parse-frontmatter md)]
          {:fm fm :body body :md md})
        (do (mulog/warn ::todo-file-skipped
                        :reason :not-a-todo
                        :path (.getAbsolutePath f))
            nil)))
    (catch Exception e
      (mulog/warn ::todo-file-skipped
                  :reason :read-failed
                  :path (.getAbsolutePath f)
                  :error (ex-message e))
      nil)))

;; ============================================================================
;; CRUD Operations
;; ============================================================================

(defn create-todo
  "Create a new todo file with a random 3-word slug. Returns the todo
   map or {:error ...}. goal is a short free-form paragraph describing
   the objective. items is a vector of `{:description :tags}` maps —
   `:tags` is optional and carries `{:via :update-agent|:bash|:mcp|
   :manual|:explore-agent|:read-only :covers [<criterion strings>]}`.
   Items without `:tags` round-trip through the legacy markdown shape
   unchanged.
   The returned map includes :file-path with the absolute path."
  [dirs scope title goal items]
  (try
    (let [dir (todos-dir dirs scope)]
      (if-not dir
        {:error "Cannot resolve todos directory — dirs config missing"}
        (let [slug (generate-slug dir)
              f (io/file dir (str slug ".md"))
              now (str (Instant/now))
              todo (cond-> {:id (str (UUID/randomUUID))
                            :title title
                            :slug slug
                            :scope scope
                            :status :draft
                            :created now
                            :updated now
                            :items (vec (keep (fn [spec]
                                                (when-let [desc (:description spec)]
                                                  (let [nt (normalize-tags (:tags spec))]
                                                    (cond-> {:description desc :done false}
                                                      nt (assoc :tags nt)))))
                                              items))
                            :file-path (.getAbsolutePath f)}
                     (not (str/blank? goal)) (assoc :goal goal))
              md (todo->md todo)]
          (.mkdirs (.getParentFile f))
          (spit f md)
          todo)))
    (catch Exception e
      (mulog/warn ::create-todo-failed :error (ex-message e))
      {:error (str "create-todo failed: " (.getMessage e))})))

(defn read-todo
  "Read a todo by slug. Checks project scope first, then user scope, and
   within each scope falls back to the legacy `.brainyard/todos/` location
   when the new path doesn't have the file. Returns todo map with
   :file-path (absolute) or {:error ...}."
  [dirs slug & {:keys [scope]}]
  (try
    (let [scopes (if scope [scope] [:project :user])
          f (some (fn [s] (find-todo-file dirs s slug)) scopes)]
      (cond
        (nil? f)
        {:error (str "Todo not found: " slug)}

        :else
        (let [md (slurp f)]
          (if-not (todo-md? md)
            {:error (str "Not a valid todo file (frontmatter must declare "
                         "file-type: todo with id and title): "
                         (.getAbsolutePath ^java.io.File f))}
            (let [todo (md->todo md)]
              (assoc todo :slug slug
                     :file-path (.getAbsolutePath ^java.io.File f)))))))
    (catch Exception e
      (mulog/warn ::read-todo-failed :slug slug :error (ex-message e))
      {:error (str "read-todo failed: " (.getMessage e))})))

(defn update-todo
  "Write an updated todo map back to its file. Returns todo map with :file-path or {:error ...}."
  [dirs todo]
  (try
    (let [slug (:slug todo)
          scope (or (:scope todo) :project)
          f (todo-file dirs scope slug)]
      (if-not f
        {:error "Cannot resolve todo path"}
        (let [^java.io.File f f
              updated (assoc todo
                             :updated (str (Instant/now))
                             :file-path (.getAbsolutePath f))
              md (todo->md updated)]
          (.mkdirs (.getParentFile f))
          (spit f md)
          updated)))
    (catch Exception e
      {:error (str "update-todo failed: " (.getMessage e))})))

(defn delete-todo
  "Delete a todo file. Returns {:deleted slug} or {:error ...}.
   Refuses to delete a file whose frontmatter does not declare
   `file-type: todo`. Walks both new and legacy locations so a delete
   request hits whichever path actually has the file."
  [dirs slug & {:keys [scope]}]
  (try
    (let [scopes (if scope [scope] [:project :user])
          f (some (fn [s] (find-todo-file dirs s slug)) scopes)]
      (cond
        (nil? f)
        {:error (str "Todo not found: " slug)}

        (not (todo-md? (slurp f)))
        {:error (str "Refusing to delete: file is not a valid todo "
                     "(frontmatter must declare file-type: todo): "
                     (.getAbsolutePath ^java.io.File f))}

        :else
        (do (.delete ^java.io.File f) {:deleted slug})))
    (catch Exception e
      (mulog/warn ::delete-todo-failed :slug slug :error (ex-message e))
      {:error (str "delete-todo failed: " (.getMessage e))})))

(defn list-todos
  "List todos with lightweight metadata (frontmatter only).
   Scans every `<slug>.md` file across BOTH the new
   `.brainyard/agents/todo-agent/todos/` location and the legacy
   `.brainyard/todos/` fallback for each scope; tags each entry with
   `:layout :new` or `:layout :legacy` so callers can see migration status
   at a glance. When a slug appears in both locations the new layout wins
   (legacy entry is dropped). Skips any file whose frontmatter does not
   declare `file-type: todo` with id/title.

   Returns vector of todo summaries or {:error ...}."
  [dirs & {:keys [scope status]}]
  (try
    (let [scopes (if scope [scope] [:project :user])
          todo-re #"(.+)\.md$"
          read-dir (fn [^java.io.File dir s layout]
                     (when (and dir (.isDirectory dir))
                       (->> (.listFiles dir)
                            (keep (fn [^java.io.File f]
                                    (when-let [[_ slug] (re-matches todo-re (.getName f))]
                                      (when-let [{:keys [fm md]} (safe-read-todo-file f)]
                                        (let [items (parse-items md)
                                              done-count (count (filter :done items))
                                              total (count items)
                                              entry {:slug slug
                                                     :title (:title fm)
                                                     :scope s
                                                     :status (keyword (or (:status fm) "draft"))
                                                     :created (:created fm)
                                                     :updated (:updated fm)
                                                     :item-progress (str done-count "/" total)
                                                     :file-path (.getAbsolutePath f)
                                                     :layout layout}]
                                          (if status
                                            (when (= (keyword status) (:status entry))
                                              entry)
                                            entry)))))))))
          all-entries
          (mapcat
           (fn [s]
             (concat (read-dir (todos-dir dirs s) s :new)
                     (read-dir (legacy-todos-dir dirs s) s :legacy)))
           scopes)
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
      (mulog/warn ::list-todos-failed :error (ex-message e))
      {:error (str "list-todos failed: " (.getMessage e))})))

(defn todo-exists?
  "Check if a *valid* todo exists at the given slug, looking under both
   the new and legacy locations. Returns false for files whose frontmatter
   does not declare `file-type: todo` (covers plans and loose .md files)."
  [dirs slug & {:keys [scope]}]
  (let [scopes (if scope [scope] [:project :user])]
    (boolean
     (some (fn [s]
             (when-let [f (find-todo-file dirs s slug)]
               (try (todo-md? (slurp f))
                    (catch Exception _ false))))
           scopes))))

;; ============================================================================
;; Item Operations
;; ============================================================================

(defn mark-item-done
  "Mark an item as done by index (0-based). Returns updated todo map."
  [todo item-idx]
  (if (and (>= item-idx 0) (< item-idx (count (:items todo))))
    (assoc-in todo [:items item-idx :done] true)
    todo))

(defn add-item
  "Add an item to the todo. If after-idx given, inserts after that index.
   Otherwise appends at end. Returns updated todo map. `:tags` (optional)
   sets the per-item routing/coverage block — same shape as `create-todo`."
  [todo description & {:keys [after-idx tags]}]
  (let [ntags (normalize-tags tags)
        new-item (cond-> {:description description :done false}
                   ntags (assoc :tags ntags))
        items (:items todo)]
    (assoc todo :items
           (if after-idx
             (let [idx (min (inc (long after-idx)) (count items))]
               (vec (concat (take idx items) [new-item] (drop idx items))))
             (conj (vec items) new-item)))))

(defn todo-progress
  "Return progress summary for a todo."
  [todo]
  (let [items (:items todo)
        total (count items)
        completed (count (filter :done items))
        pending (- total completed)
        next-item (first (remove :done items))]
    {:completed completed
     :pending pending
     :total total
     :percent (if (zero? total) 100.0 (* 100.0 (/ completed total)))
     :next-item next-item}))

(defn reopen-todo
  "Reset all items to pending and set status to :draft.
   Used to re-run a completed or abandoned todo from scratch."
  [todo]
  (-> todo
      (update :items (fn [items] (mapv #(assoc % :done false) items)))
      (assoc :status :draft)))

(defn update-goal
  "Replace the goal text of a todo. Returns updated todo map."
  [todo new-goal]
  (if (str/blank? new-goal)
    (dissoc todo :goal)
    (assoc todo :goal new-goal)))

(defn reset-item
  "Reset a single item by index back to pending.
   Returns updated todo map (unchanged if index out of bounds)."
  [todo item-idx]
  (if (and (>= item-idx 0) (< item-idx (count (:items todo))))
    (assoc-in todo [:items item-idx :done] false)
    todo))

;; ============================================================================
;; Dirs resolution for defcommands
;; ============================================================================

(defn- current-agent
  "Return the agent bound in the current call, or nil."
  []
  (some-> (requiring-resolve 'ai.brainyard.agent.core.protocol/*current-agent*)
          deref))

(defn- current-dirs
  "Resolve dirs from the current agent session, falling back to init-dirs!.
   Uses requiring-resolve to avoid circular deps at cold start."
  []
  (or (when-let [a (current-agent)]
        (some-> (:!session a) deref
                ((or (requiring-resolve 'ai.brainyard.agent.core.session/get-session-config)
                     (constantly nil))
                 :dirs)))
      ((or (requiring-resolve 'ai.brainyard.agent.core.config/init-dirs!)
           (constantly {})))))

(defn- current-st-memory
  "Return the agent's BT st-memory atom, or nil if unavailable.
   Path mirrors ai.brainyard.agent.core.agent st-memory placement."
  []
  (when-let [a (current-agent)]
    (some-> a :!state deref (get-in [:behavior-tree :context :st-memory]))))

(defn mirror-to-st-memory!
  "Mirror a todo's items + slug to st-memory under :todo-list and
   :active-todo-slug so TUI, web bridge, and recall keep working.
   Fires the :todo/updated hook so subscribers (TUI live block) can react.

   Public so the polymorphic `doc$*` commands in `common/doc` can reuse
   the same st-memory contract for `:kind :todo` operations."
  [todo]
  (when (and (map? todo) (not (:error todo)))
    (let [items (vec (:items todo))
          slug  (:slug todo)]
      (when-let [st-memory (current-st-memory)]
        (swap! st-memory assoc
               :todo-list items
               :active-todo-slug slug))
      (hooks/fire! :todo/updated
                   {:agent (current-agent)
                    :todo-list items
                    :active-slug slug}))))

(defn clear-st-memory-if-active!
  "Clear :todo-list + :active-todo-slug from st-memory if the given slug
   matches the currently-active todo. Used on delete.
   Fires :todo/updated with an empty list so subscribers can dispose.

   Public so the polymorphic `doc$delete` command in `common/doc` can
   reuse the same st-memory cleanup on `:kind :todo` deletes."
  [slug]
  (when-let [st-memory (current-st-memory)]
    (when (= slug (:active-todo-slug @st-memory))
      (swap! st-memory assoc :todo-list [] :active-todo-slug nil)
      (hooks/fire! :todo/updated
                   {:agent (current-agent)
                    :todo-list []
                    :active-slug nil}))))

;; ============================================================================
;; Todo-agent dossier helpers
;;
;; A *dossier* is the per-turn handoff record todo-agent emits — pre-flight
;; verdict (incl. consumed plan-agent dossier reference), author action,
;; post-flight rubric (incl. acceptance_coverage map), recommended next
;; agent. Lives under `.brainyard/agents/todo-agent/dossiers/<yyyyMMdd-HHmmss>-
;; <slug>.md` per docs/todo-agent-design.md §7.2.
;;
;; Mirrors the plan-agent dossier helpers (1:1) with todo-specific
;; schema additions: `source` block (plan dossier reference) and
;; `post.acceptance_coverage` (criterion → item-idxs map).
;; ============================================================================

(def ^:private todo-dossiers-subdir "dossiers")
(def ^:private todo-dossiers-base ".brainyard/agents/todo-agent")
(def ^:private todo-dossiers-dir-rel (str todo-dossiers-base "/" todo-dossiers-subdir))
(def ^:private todo-dossiers-index-rel (str todo-dossiers-base "/INDEX.md"))

(def ^:private todo-slug-stopwords
  #{"a" "an" "the" "is" "are" "and" "or" "of" "in" "on" "at" "to" "for"
    "by" "with" "from" "as" "but" "if" "then" "than" "so"
    "what" "where" "when" "who" "whom" "why" "how" "which"
    "do" "does" "did" "can" "could" "would" "should" "shall" "will"
    "this" "that" "these" "those" "it" "its" "we" "they" "you" "i"
    "be" "been" "being" "was" "were" "have" "has" "had"
    "todo" "spawn"})

(defn- todo-dossier-default-base-dir
  "Resolve the project root for todo-agent dossiers. Delegates to
   `agent.core.config/project-dir` — the single source of truth shared
   by every functional agent's `default-base-dir` and the LLM tool
   channel. Kept as a named fn so existing test `with-redefs` sites work."
  []
  (config/project-dir))

(defn- td-now-ts []
  (.format (SimpleDateFormat. "yyyyMMdd-HHmmss") (Date.)))

(defn- td-now-iso []
  (str (Instant/now)))

(defn- td-now-yyyy-mm-dd-hh-mm []
  (-> (subs (td-now-iso) 0 16)
      (str/replace "T" " ")))

(defn- todo-slugify
  [request max-chars]
  (let [tokens (-> (str request)
                   str/lower-case
                   (str/replace #"[^a-z0-9]+" "-")
                   (str/split #"-"))
        kept   (->> tokens
                    (remove #(or (str/blank? %) (todo-slug-stopwords %)))
                    seq)
        joined (if kept (str/join "-" kept) "todo")]
    (subs joined 0 (min max-chars (count joined)))))

;; ---------------------------------------------------------------------------
;; YAML emission — same shape as plan.clj's, extended to support
;; string-keyed flow maps (used for post.acceptance_coverage).
;; ---------------------------------------------------------------------------

(def ^:private td-bareword-re #"^[A-Za-z0-9_./:-]+$")

(defn- td-yaml-string
  [s]
  (str "\"" (-> (str s)
                (str/replace "\\" "\\\\")
                (str/replace "\"" "\\\""))
       "\""))

(defn- td-yaml-scalar
  [v]
  (cond
    (nil? v)        "null"
    (boolean? v)    (if v "true" "false")
    (keyword? v)    (name v)
    (number? v)     (str v)
    (string? v)     (if (and (seq v) (re-matches td-bareword-re v))
                      v
                      (td-yaml-string v))
    :else           (td-yaml-string (str v))))

(defn- td-yaml-flow-vector
  [xs]
  (str "[" (str/join ", " (map td-yaml-scalar xs)) "]"))

(defn- td-yaml-flow-key
  "Render a flow-map key. Keywords use their name; strings that aren't
   barewords get double-quoted (so criterion strings with spaces remain
   round-trippable)."
  [k]
  (cond
    (keyword? k) (name k)
    (string? k)  (if (and (seq k) (re-matches td-bareword-re k))
                   k
                   (td-yaml-string k))
    :else        (td-yaml-string (str k))))

(defn- td-yaml-flow-map
  "Single-line flow-style flat map. Used for `pre.checks`, `post.rubric`
   (keyword keys, scalar values) AND for `post.acceptance_coverage`
   (string keys, flow-vector values). Mixed-mode — value rendering picks
   between scalar and flow-vector based on type."
  [m]
  (str "{"
       (str/join ", "
                 (map (fn [[k v]]
                        (str (td-yaml-flow-key k) ": "
                             (cond
                               (sequential? v) (td-yaml-flow-vector v)
                               :else           (td-yaml-scalar v))))
                      m))
       "}"))

(defn- td-yaml-value
  [v]
  (cond
    (sequential? v) (td-yaml-flow-vector v)
    (map? v)        (td-yaml-flow-map v)
    :else           (td-yaml-scalar v)))

(defn- td-yaml-block
  [block-key entries]
  (apply str
         (name block-key) ":\n"
         (mapv (fn [[k v]]
                 (str "  " (name k) ": " (td-yaml-value v) "\n"))
               entries)))

(defn- td-ordered-pairs
  [m preferred]
  (let [in-pref  (filter #(contains? m %) preferred)
        leftover (remove (set preferred) (keys m))]
    (mapv (fn [k] [k (get m k)]) (concat in-pref leftover))))

(def ^:private td-source-key-order
  [:plan_dossier :plan_path :plan_slug])

(def ^:private td-pre-key-order
  [:verdict :checks :acceptance :related_todos :gather_question :refuse_reason])

(def ^:private td-author-key-order
  [:action :item_count])

(def ^:private td-post-key-order
  [:verdict :rubric :revision_applied :revision_summary :holds
   :acceptance_coverage :item_count])

(def ^:private td-handoff-key-order
  [:next_agent :next_call])

(defn- build-todo-dossier-frontmatter*
  [{:keys [slug todo_path todo_status created turn-id session-id
           source pre author post handoff]}]
  (str "---\n"
       "slug: " (td-yaml-scalar slug) "\n"
       "agent: todo-agent\n"
       "created: " (or created (td-now-iso)) "\n"
       "todo_path: " (td-yaml-value todo_path) "\n"
       "todo_status: " (td-yaml-scalar (or todo_status "draft")) "\n"
       (when turn-id (str "turn_id: " (td-yaml-string turn-id) "\n"))
       (when session-id (str "session_id: " (td-yaml-string session-id) "\n"))
       "\n"
       (td-yaml-block :source  (td-ordered-pairs (or source {})  td-source-key-order))
       "\n"
       (td-yaml-block :pre     (td-ordered-pairs (or pre {})     td-pre-key-order))
       "\n"
       (td-yaml-block :author  (td-ordered-pairs (or author {})  td-author-key-order))
       "\n"
       (td-yaml-block :post    (td-ordered-pairs (or post {})    td-post-key-order))
       "\n"
       (td-yaml-block :handoff (td-ordered-pairs (or handoff {}) td-handoff-key-order))
       "---\n"))

;; ---------------------------------------------------------------------------
;; YAML parsing — flat scalars + named nested blocks; flow-style flat maps
;; preserved as raw strings.
;; ---------------------------------------------------------------------------

(defn- td-read-frontmatter-lines
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

(defn- td-parse-flow-vector
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

(defn- td-coerce-bare-value
  [s]
  (let [trimmed (str/trim s)]
    (cond
      (= trimmed "true")  true
      (= trimmed "false") false
      (= trimmed "null")  nil
      (re-matches #"-?\d+" trimmed)
      (try (Long/parseLong trimmed) (catch Exception _ trimmed))
      :else trimmed)))

(defn- td-parse-scalar
  [v]
  (let [v (str/trim v)]
    (cond
      (re-matches #"^\[.*\]$" v) (td-parse-flow-vector v)
      (re-matches #"^\{.*\}$" v) v ; flow-map kept as raw string for v1
      (and (str/starts-with? v "\"") (str/ends-with? v "\""))
      (-> v
          (subs 1 (dec (count v)))
          (str/replace "\\\"" "\"")
          (str/replace "\\\\" "\\"))
      :else (td-coerce-bare-value v))))

(def ^:private td-block-keys
  #{"source" "pre" "author" "post" "handoff"})

(defn- parse-todo-dossier-yaml
  [lines]
  (loop [ls    lines
         acc   {}
         block nil]
    (if (empty? ls)
      acc
      (let [ln    (first ls)
            rest* (rest ls)]
        (cond
          (and (vector? block) (= :flat-block (first block))
               (re-matches #"^\s{2,}\S.*" ln))
          (if-let [[_ k v] (re-matches #"^\s+([\w_-]+):\s*(.*)$" ln)]
            (recur rest*
                   (assoc-in acc [(second block) (keyword k)] (td-parse-scalar v))
                   block)
            (recur rest* acc nil))

          (re-matches #"^([\w_-]+):\s*$" ln)
          (let [[_ k] (re-matches #"^([\w_-]+):\s*$" ln)]
            (if (td-block-keys k)
              (recur rest* (assoc acc (keyword k) {}) [:flat-block (keyword k)])
              (recur rest* acc nil)))

          (re-matches #"^([\w_-]+):\s*(.*)$" ln)
          (let [[_ k v] (re-matches #"^([\w_-]+):\s*(.*)$" ln)]
            (recur rest* (assoc acc (keyword k) (td-parse-scalar v)) nil))

          :else
          (recur rest* acc block))))))

;; ---------------------------------------------------------------------------
;; todo$dossier-slug
;; ---------------------------------------------------------------------------

(defcommand todo$dossier-slug
  "Deterministic kebab-case slug from a question for todo-agent dossiers (used on GATHER/REFUSE turns); caps at 60 chars."
  (fn [& {:keys [question max-chars]
          :or   {max-chars 60}}]
    (cond
      (not (string? question))
      {:error ":question is required (string)"}

      (or (not (integer? max-chars)) (<= max-chars 0))
      {:error ":max-chars must be a positive integer"}

      :else
      {:slug (todo-slugify question max-chars)}))
  :input-schema  [:map
                  [:question  [:string {:desc "User question to slugify"}]]
                  [:max-chars {:optional true} [:int {:desc "Cap on slug length in chars (default 60)"}]]]
  :output-schema [:map
                  [:slug  [:string {:desc "Kebab-case slug, stopwords dropped"}]]
                  [:error [:string {:desc "Error if validation failed"}]]])

;; ---------------------------------------------------------------------------
;; todo$dossier-frontmatter
;; ---------------------------------------------------------------------------

(defcommand todo$dossier-frontmatter
  "Build a YAML frontmatter block for a todo-agent dossier with source/pre/author/post/handoff sub-blocks."
  (fn [& {:keys [slug todo-path todo-status source pre author post handoff
                 created turn-id session-id]}]
    (cond
      (not (string? slug)) {:error ":slug is required (string)"}
      :else
      (let [fm (build-todo-dossier-frontmatter*
                {:slug slug
                 :todo_path todo-path
                 :todo_status todo-status
                 :source source
                 :pre pre
                 :author author
                 :post post
                 :handoff handoff
                 :created created
                 :turn-id turn-id
                 :session-id session-id})]
        (mulog/log ::todo.dossier-frontmatter
                   :slug slug
                   :pre-verdict (or (:verdict pre) :n-a)
                   :post-verdict (or (:verdict post) :n-a)
                   :next-agent (:next_agent handoff))
        {:frontmatter fm})))
  :input-schema  [:map
                  [:slug         [:string {:desc "Todo slug (or dossier slug for GATHER/REFUSE)"}]]
                  [:todo-path    {:optional true} [:string {:desc "Repo-relative path to the todo body (or null)"}]]
                  [:todo-status  {:optional true} [:string {:desc "Todo status: draft | in-progress | completed | abandoned"}]]
                  [:source       {:optional true} [:map    {:desc "Source plan reference: {:plan_dossier :plan_path :plan_slug}"}]]
                  [:pre          {:optional true} [:map    {:desc "Pre-flight outcomes (verdict, checks, acceptance, related_todos, gather_question, refuse_reason)"}]]
                  [:author       {:optional true} [:map    {:desc "Author outcomes (action :spawned/:advanced/:unchanged, item_count)"}]]
                  [:post         {:optional true} [:map    {:desc "Post-flight outcomes (verdict, rubric, revision_applied, revision_summary, holds, acceptance_coverage, item_count)"}]]
                  [:handoff      {:optional true} [:map    {:desc "Handoff suggestion (next_agent, next_call)"}]]
                  [:created      {:optional true} [:string {:desc "ISO-8601 created timestamp (default: now)"}]]
                  [:turn-id      {:optional true} [:string {:desc "Trajectory turn-id"}]]
                  [:session-id   {:optional true} [:string {:desc "Trajectory session-id"}]]]
  :output-schema [:map
                  [:frontmatter [:string {:desc "Full YAML frontmatter block, trailing newline included"}]]
                  [:error       [:string {:desc "Error if validation failed"}]]])

;; ---------------------------------------------------------------------------
;; todo$dossier-write
;; ---------------------------------------------------------------------------

(defn- td-existing-slug-count
  [base-dir slug]
  (let [^java.io.File dir (io/file base-dir todo-dossiers-dir-rel)]
    (if (.isDirectory dir)
      (let [slug-re (re-pattern (str "(?i)-" (java.util.regex.Pattern/quote slug)
                                     "(-\\d+)?\\.md$"))]
        (->> (.listFiles dir)
             (filter (fn [^java.io.File f] (.isFile f)))
             (map (fn [^java.io.File f] (.getName f)))
             (filter #(re-find slug-re %))
             count))
      0)))

(defn- td-final-slug-with-suffix
  [base-dir base-slug]
  (let [n (td-existing-slug-count base-dir base-slug)]
    (if (zero? n)
      base-slug
      (str base-slug "-" (inc n)))))

(defcommand todo$dossier-write
  "Write a todo-agent dossier under .brainyard/agents/todo-agent/dossiers/ as <ts>-<slug>.md; appends -N suffix on slug collision."
  (fn [& {:keys [slug content base-dir]
          :or   {base-dir (todo-dossier-default-base-dir)}}]
    (cond
      (not (string? slug))    {:error ":slug is required (string)"}
      (not (string? content)) {:error ":content is required (string)"}
      (not (re-find #"(?s)\A---\n.+?\n---\n" content))
      {:error (str ":content must begin with a YAML frontmatter block "
                   "(---\\n...\\n---\\n). Build it via todo$dossier-frontmatter "
                   "and prepend before writing: "
                   "(todo$dossier-write :slug ... :content (str fm body))")}
      :else
      (let [final-slug (td-final-slug-with-suffix base-dir slug)
            ts         (td-now-ts)
            rel-path   (str todo-dossiers-dir-rel "/" ts "-" final-slug ".md")
            file       (io/file base-dir rel-path)]
        (.mkdirs (.getParentFile file))
        (spit file content)
        (mulog/log ::todo.dossier-write
                   :slug final-slug :path rel-path
                   :bytes (count content) :collision? (not= slug final-slug))
        {:path (.getAbsolutePath file) :slug final-slug :ts ts})))
  :input-schema  [:map
                  [:slug     [:string {:desc "Todo slug (or dossier slug)"}]]
                  [:content  [:string {:desc "Full file content (frontmatter + body)"}]]
                  [:base-dir {:optional true} [:string {:desc "Working directory (default: project root)"}]]]
  :output-schema [:map
                  [:path  [:string {:desc "Absolute path of the written dossier"}]]
                  [:slug  [:string {:desc "Final slug actually used (may have -N suffix)"}]]
                  [:ts    [:string {:desc "Timestamp portion of filename"}]]
                  [:error [:string {:desc "Error if validation failed"}]]])

;; ---------------------------------------------------------------------------
;; todo$dossier-index-append
;; ---------------------------------------------------------------------------

(defn- td-index-line
  [{:keys [path slug pre-verdict post-verdict next-agent]}]
  (let [filename (-> path (str/split #"/") last)
        pre-tok  (str "pre:" (name pre-verdict))
        post-tok (str "post:" (if (nil? post-verdict) "n/a" (name post-verdict)))
        next-tok (str "→ " (name (or next-agent :unspecified)))]
    (str "- " (td-now-yyyy-mm-dd-hh-mm)
         " [" slug "](" todo-dossiers-subdir "/" filename ") — "
         pre-tok " · " post-tok " · " next-tok "\n")))

(defcommand todo$dossier-index-append
  "Prepend a one-line entry to .brainyard/agents/todo-agent/INDEX.md (newest-first) with pre/post verdicts and next-agent."
  (fn [& {:keys [path slug pre-verdict post-verdict next-agent base-dir]
          :or   {base-dir (todo-dossier-default-base-dir)}}]
    (cond
      (not (string? path)) {:error ":path is required (string)"}
      (not (string? slug)) {:error ":slug is required (string)"}
      (not (or (keyword? pre-verdict) (string? pre-verdict)))
      {:error ":pre-verdict is required (keyword or string)"}
      :else
      (let [pre-kw  (if (keyword? pre-verdict) pre-verdict (keyword pre-verdict))
            post-kw (cond
                      (keyword? post-verdict) post-verdict
                      (string? post-verdict)  (keyword post-verdict)
                      :else                   nil)
            next-kw (cond
                      (keyword? next-agent) next-agent
                      (string? next-agent)  (keyword next-agent)
                      :else                 :unspecified)
            line    (td-index-line {:path path :slug slug
                                    :pre-verdict pre-kw
                                    :post-verdict post-kw
                                    :next-agent next-kw})
            file    (io/file base-dir todo-dossiers-index-rel)
            existing (if (.isFile file) (slurp file) "")]
        (.mkdirs (.getParentFile file))
        (spit file (str line existing))
        (mulog/log ::todo.dossier-index
                   :slug slug :path path
                   :pre-verdict pre-kw :post-verdict post-kw
                   :next-agent next-kw)
        {:appended true :line line})))
  :input-schema  [:map
                  [:path         [:string {:desc "Repo-relative path of the dossier"}]]
                  [:slug         [:string {:desc "Todo or dossier slug"}]]
                  [:pre-verdict  [:string {:desc "Pre-flight verdict: go | gather | refuse"}]]
                  [:post-verdict {:optional true} [:string {:desc "Post-flight verdict: pass | hold (omit when no AUTHOR ran)"}]]
                  [:next-agent   {:optional true} [:string {:desc "Recommended next agent (e.g. exec-agent | user)"}]]
                  [:base-dir     {:optional true} [:string {:desc "Working directory (default: project root)"}]]]
  :output-schema [:map
                  [:appended [:boolean {:desc "true on success"}]]
                  [:line     [:string  {:desc "The exact line that was prepended"}]]
                  [:error    [:string  {:desc "Error if validation failed"}]]])

;; ---------------------------------------------------------------------------
;; todo$read-dossier
;; ---------------------------------------------------------------------------

(defcommand todo$read-dossier
  "Read and parse just the leading YAML frontmatter from a todo-agent dossier (cheap; exec/eval-agents use this for verdicts and todo_path)."
  (fn [& {:keys [path base-dir]
          :or   {base-dir (todo-dossier-default-base-dir)}}]
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
          (if-let [lines (td-read-frontmatter-lines file)]
            (parse-todo-dossier-yaml lines)
            {:error (str "No frontmatter block at " path " (file did not start with ---)")})))))
  :input-schema  [:map
                  [:path     [:string {:desc "Repo-relative or absolute path to a todo-agent dossier"}]]
                  [:base-dir {:optional true} [:string {:desc "Working directory (default: project root)"}]]]
  :output-schema [:map
                  [:slug        {:optional true} [:string {:desc "Todo slug"}]]
                  [:todo_path   {:optional true} [:string {:desc "Repo-relative path to the todo body"}]]
                  [:todo_status {:optional true} [:string {:desc "Todo status at dossier time"}]]
                  [:source      {:optional true} [:map    {:desc "Source plan reference (plan_dossier, plan_path, plan_slug)"}]]
                  [:pre         {:optional true} [:map    {:desc "Pre-flight sub-block"}]]
                  [:author      {:optional true} [:map    {:desc "Author sub-block"}]]
                  [:post        {:optional true} [:map    {:desc "Post-flight sub-block"}]]
                  [:handoff     {:optional true} [:map    {:desc "Handoff sub-block"}]]
                  [:error       {:optional true} [:string {:desc "Error if file missing or no frontmatter"}]]])

;; ---------------------------------------------------------------------------
;; todo$next-handoff
;; ---------------------------------------------------------------------------

(defn- td-handoff-from-verdicts
  [pre-verdict post-verdict slug dossier-path]
  (let [ctx (cond-> "" dossier-path (str " :agent-context \"" dossier-path "\""))]
    (case (or post-verdict :n-a)
      :pass
      {:next-agent "exec-agent"
       :next-call (str "(exec-agent {:question \"Drive this todo to completion.\""
                       ctx "})")}

      :hold
      {:next-agent "user"
       :next-call (str "Resolve holds, then re-call todo-agent: "
                       "(todo-agent {:question \"<refined request>\""
                       ctx "})")}

      :n-a
      (case pre-verdict
        :gather
        {:next-agent "user"
         :next-call (str "Provide the missing input named in dossier.gather_question; "
                         "typically run plan-agent first, then re-call todo-agent.")}

        :refuse
        {:next-agent "none"
         :next-call (str "See dossier.refuse_reason for the redirect (typically plan-agent "
                         "first, e.g. when source plan-agent dossier was HOLD).")}

        {:next-agent "user"
         :next-call (str "Inspect the dossier and decide next step.")}))))

(defcommand todo$next-handoff
  "Compute recommended next agent and exact direct-invocation form from pre/post verdicts; single source of truth for dossier handoff block."
  (fn [& {:keys [pre post slug dossier-path]}]
    (let [pre-v  (when pre  (:verdict pre))
          post-v (when post (:verdict post))]
      (td-handoff-from-verdicts pre-v post-v slug dossier-path)))
  :input-schema  [:map
                  [:pre          {:optional true} [:map    {:desc "Pre-flight outcome map (only :verdict required)"}]]
                  [:post         {:optional true} [:map    {:desc "Post-flight outcome map (only :verdict required); omit on GATHER/REFUSE"}]]
                  [:slug         {:optional true} [:string {:desc "Todo slug"}]]
                  [:dossier-path {:optional true} [:string {:desc "Repo-relative path to the dossier"}]]]
  :output-schema [:map
                  [:next-agent [:string {:desc "Recommended next agent: exec-agent | user | none | todo-agent"}]]
                  [:next-call  [:string {:desc "Exact direct-invocation form (<agent-name> {…}) for the dispatcher"}]]])

;; ---------------------------------------------------------------------------
;; Public roster
;; ---------------------------------------------------------------------------

(def todo-dossier-helpers
  "Vector of all todo$dossier-* / todo$read-dossier / todo$next-handoff
   vars. todo-agent appends these to its :agent-tools roster so the SCI
   sandbox auto-binds them (callable as `(todo$dossier-slug ...)` etc.
   in a clojure fence)."
  [#'todo$dossier-slug
   #'todo$dossier-frontmatter
   #'todo$dossier-write
   #'todo$dossier-index-append
   #'todo$read-dossier
   #'todo$next-handoff])

;; ============================================================================
;; Auto-persist hook
;;
;; Mirror of plan-auto-persist (see plan.clj) — the LLM can forget to call
;; todo$dossier-write at PERSIST time, or hallucinate a Saved dossier:
;; path that doesn't exist on disk. This `:agent.ask/post` hook
;; reconstructs a minimal dossier from the answer text in either case.
;; Idempotency is path-existence-checked, not just marker-string-checked.
;; ============================================================================

(def saved-todo-prefix
  "Stable prefix the agent emits when a todo body was authored this turn."
  "Saved todo: ")

(def saved-dossier-prefix-todo
  "Stable prefix the agent emits when a dossier was persisted this turn.
   Suffixed `-todo` to avoid collision with plan.clj's same-named const
   when both nses are loaded into the same JVM."
  "Saved dossier: ")

(defn- td-todo-agent? [agent]
  (try
    (= :todo-agent (proto/defagent-type agent))
    (catch Throwable _ false)))

(defn- td-extract-line-after
  [^String answer ^String prefix]
  (when (and (string? answer) (string? prefix))
    (when-let [start (str/index-of answer prefix)]
      (let [after (subs answer (+ start (count prefix)))
            end   (or (str/index-of after "\n") (count after))]
        (-> (subs after 0 end)
            (str/replace #"^[`'\"\s]+" "")
            (str/replace #"[`'\"\.,\s]+$" "")
            str/trim)))))

(defn- td-absolute->repo-relative
  [base-dir ^String path]
  (cond
    (or (nil? path) (str/blank? path))
    nil

    (and base-dir (str/starts-with? path (str base-dir "/")))
    (subs path (inc (count base-dir)))

    :else path))

(defn- td-dossier-already-saved?
  "Truthy when the answer claims a dossier path AND the file exists on
   disk. Hallucinated paths are caught by the on-disk check."
  [^String answer base-dir]
  (when-let [claimed (td-extract-line-after answer saved-dossier-prefix-todo)]
    (let [file (if (.isAbsolute (io/file claimed))
                 (io/file claimed)
                 (io/file base-dir claimed))]
      (.isFile file))))

(defn- td-detect-pre-verdict [^String answer]
  (cond
    (re-find #"(?im)^\s*Refused:\s*\S" answer) :refuse
    (re-find #"(?im)^\s*Need:\s*\S"    answer) :gather
    :else                                       :go))

(defn- td-detect-post-verdict [^String answer pre-verdict]
  (cond
    (not= :go pre-verdict)                  nil
    (re-find #"(?im)^\s*Hold:\s*\S" answer) :hold
    :else                                   :pass))

(defn- td-slug-from-todo-path [^String path]
  (when (and path (str/ends-with? path ".md"))
    (-> path (str/split #"/") last (str/replace #"\.md$" ""))))

(defn- td-one-line-summary
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

(defn materialize-todo-auto-dossier!
  "Core of the todo auto-persist hook — agent-state-free. Given an answer
   string + question + base-dir, reconstruct a dossier from the answer
   and persist it via the standard helpers. Returns `{:path :slug
   :pre-verdict :post-verdict}` or nil when skipped."
  [{:keys [answer question base-dir]}]
  (cond
    (or (not (string? answer)) (str/blank? answer))
    nil

    (td-dossier-already-saved? answer base-dir)
    nil

    :else
    (let [pre-verdict   (td-detect-pre-verdict answer)
          post-verdict  (td-detect-post-verdict answer pre-verdict)
          todo-path-raw (td-extract-line-after answer saved-todo-prefix)
          todo-path     (when todo-path-raw
                          (td-absolute->repo-relative base-dir todo-path-raw))
          slug          (or (td-slug-from-todo-path todo-path-raw)
                            (:slug (todo$dossier-slug
                                    :question (or question "auto-persisted")))
                            "todo")
          summary       (td-one-line-summary answer 200)
          handoff       (let [pre-map  {:verdict pre-verdict}
                              post-map (when post-verdict {:verdict post-verdict})
                              kebab    (if post-map
                                         (#'todo$next-handoff :pre pre-map :post post-map :slug slug)
                                         (#'todo$next-handoff :pre pre-map :slug slug))]
                          {:next_agent (:next-agent kebab)
                           :next_call  (:next-call  kebab)})
          fm            (:frontmatter
                         (#'todo$dossier-frontmatter
                          :slug         slug
                          :todo-path    todo-path
                          :todo-status  "draft"
                          :pre          {:verdict pre-verdict}
                          :author       (cond-> {}
                                          todo-path-raw (assoc :action :spawned))
                          :post         (when post-verdict {:verdict post-verdict})
                          :handoff      handoff))
          body          (str "# Todo dossier (auto-persisted)\n\n"
                             "*Reconstructed from the agent's answer text — the LLM did "
                             "not call todo$dossier-write itself this turn.*\n\n"
                             "## Summary\n" summary "\n\n"
                             "## Original answer\n" answer "\n")
          write-result  (#'todo$dossier-write :slug slug
                                              :content (str fm body)
                                              :base-dir base-dir)]
      (if (:error write-result)
        (do (mulog/warn ::todo.auto-persist-write-failed
                        :slug slug :error (:error write-result))
            nil)
        (do
          (#'todo$dossier-index-append
           :path         (:path write-result)
           :slug         (:slug write-result)
           :pre-verdict  pre-verdict
           :post-verdict post-verdict
           :next-agent   (or (:next_agent handoff) :unspecified)
           :base-dir     base-dir)
          (mulog/log ::todo.auto-persist
                     :slug          (:slug write-result)
                     :path          (:path write-result)
                     :pre-verdict   pre-verdict
                     :post-verdict  post-verdict
                     :answer-chars  (count answer)
                     :had-todo-path? (boolean todo-path-raw))
          {:path         (:path write-result)
           :slug         (:slug write-result)
           :pre-verdict  pre-verdict
           :post-verdict post-verdict})))))

(defn todo-auto-persist
  "Gated handler for `:agent.ask/finalize`. Materializes the dossier when the
   LLM skipped the FINAL-STEP checklist AND returns a `:replace` decision
   injecting the absent `Saved dossier: <path>` handoff line into the answer.
   Idempotent — no-op when the line is present or nothing was persisted.
   Defensive — failures logged, never re-thrown."
  [{:keys [agent input result]}]
  (try
    (when (and (td-todo-agent? agent) (map? result))
      (let [question  (or (when (string? input) input)
                          (some-> input :question str)
                          "(question not captured)")
            answer    (str (:answer result))
            persisted (materialize-todo-auto-dossier!
                       {:answer   (:answer result)
                        :question question
                        :base-dir (todo-dossier-default-base-dir)})
            path      (:path persisted)]
        (when (and path (not (str/includes? answer "Saved dossier:")))
          {:result      :replace
           :reason      "injected absent Saved-dossier handoff line"
           :replacement (assoc result :answer (str answer "\n\nSaved dossier: " path))})))
    (catch Throwable t
      (mulog/error ::todo.auto-persist-failed
                   :exception t
                   :agent-id  (try (proto/agent-id agent)
                                   (catch Throwable _ "unknown")))
      nil)))

(defn install-todo-auto-persist!
  "Register the auto-persist hook on the gated `:agent.ask/finalize` event.
   Idempotent — `register-hook!` replaces by id. The :match predicate scopes
   the hook to todo-agent instances only.

   Tag `:source :todo-agent` lets apps opt out via
   `(hooks/unregister-source! :todo-agent)`."
  []
  (hooks/register-hook!
   :agent.ask/finalize
   ::todo-auto-persist
   todo-auto-persist
   :source   :todo-agent
   :match    (fn [{:keys [agent]}] (td-todo-agent? agent))
   :priority 50))

;; Self-install at namespace load.
(install-todo-auto-persist!)
