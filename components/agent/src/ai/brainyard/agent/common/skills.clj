;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.common.skills
  "Unified skill management — brainyard, claude, agents.

   Three skill types:
   - :brainyard — local FS under ~/.brainyard/skills (user) or <project>/.brainyard/skills
                  Managed by the agent itself: create/read/list/update/remove.
   - :claude    — ~/.claude/skills, installed via `npx skills add -g` with
                  `--target claude` (or equivalent). Read-only from the agent.
   - :agents    — ~/.agents/skills, installed via `npx skills add -g`.
                  Read-only from the agent.

   claude/agents share the same `npx skills` CLI — they differ only by install
   directory. This namespace routes CLI ops to the correct target.

   Public API is split in two layers:
   - Plain functions (list-skills, read-skill, create-skill, update-skill,
     remove-skill, find-skills, search-marketplace, install-skill, sync-skills)
     — used from sandbox bindings and other Clojure callers.
   - defcommand forms — read paths (skills$list, skills$find, skills$read),
     polymorphic mutation (skills$write with :op :create|:update|:remove),
     and package management (skills$install, skills$search, skills$sync,
     skills$reload) — used from the tool registry so the LLM can call them as
     tools.

   Discovery vs. marketplace: `find-skills` / `skills$find` search only what is
   INSTALLED — ranked, local, offline. Searching the `npx skills` marketplace is
   `search-marketplace` / `skills$search`, a separate command kept off the base
   agent roster because it spawns a subprocess per backend.

   Dynamic skill registration: `skills$reload` walks every available skill and
   registers each as `:skill$<name>` in `tool/!tool-defs`. The registered fn
   reads SKILL.md fresh on every invocation and asks the agent's LM to follow
   it against the user's question. Stale dynamic skills (no longer on disk)
   are dropped on each reload. Mirrors `mcp/integration/register-mcp-tools-for-server!`."
  (:require [ai.brainyard.agent.core.tool :as tool :refer [defcommand]]
            [ai.brainyard.agent.core.protocol :as proto]
            [ai.brainyard.mulog.interface :as mulog]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as str])
  (:import [java.io File]
           [java.time Instant]
           [java.util.concurrent TimeUnit]))

;; ============================================================================
;; Shared helpers
;; ============================================================================

(defn- strip-ansi
  [s]
  (str/replace s #"\033\[[0-9;]*[A-Za-z]|\033\[\?[0-9]*[hl]" ""))

(defn- run-cmd-sync
  "Run a bash command. Returns {:output str :exit-code int :error str}.
   ANSI escape codes are stripped from output."
  [cmd timeout-ms]
  (try
    (let [pb (ProcessBuilder. ^"[Ljava.lang.String;" (into-array String ["/bin/sh" "-c" cmd]))
          _ (.redirectErrorStream pb true)
          proc (.start pb)
          output (slurp (.getInputStream proc))
          finished (.waitFor proc (long timeout-ms) TimeUnit/MILLISECONDS)]
      (if finished
        {:output (strip-ansi (str/trim output)) :exit-code (.exitValue proc)}
        (do (.destroyForcibly proc)
            {:output "" :exit-code -1 :error "timed out"})))
    (catch Exception e
      {:output "" :exit-code -1 :error (.getMessage e)})))

(defn- sanitize-skill-name
  [name]
  (-> (str name)
      str/lower-case
      str/trim
      (str/replace #"[^a-z0-9\s-]" "")
      (str/replace #"\s+" "-")
      (str/replace #"-+" "-")
      (str/replace #"^-|-$" "")))

;; ============================================================================
;; Brainyard backend (FS-based, under .brainyard/skills)
;; ============================================================================

(defn- ^File brainyard-skills-dir
  "Resolve the brainyard skills directory for scope (:user or :project)."
  [dirs scope]
  (let [base (case scope
               :user (str (System/getProperty "user.home") "/.brainyard/skills")
               :project (when-let [project-dir (or (:project-dir dirs)
                                                   (:base-dir dirs))]
                          (str project-dir "/.brainyard/skills"))
               nil)]
    (when base (io/file base))))

(defn- ^File brainyard-skill-dir
  [dirs scope skill-name]
  (when-let [dir (brainyard-skills-dir dirs scope)]
    (io/file dir skill-name)))

(defn- ^File brainyard-skill-md
  [dirs scope skill-name]
  (when-let [dir (brainyard-skill-dir dirs scope skill-name)]
    (io/file dir "SKILL.md")))

(defn- parse-frontmatter
  "Parse simple YAML frontmatter lines into a keyword-keyed map. Supports
   inline `key: value` AND block scalars `key: >` / `key: |` (folded / literal)
   whose value is the following more-indented lines — e.g. a multi-line
   `description: >`. Chomping / indentation indicators (`>-`, `|+`, `|2`, …)
   are tolerated. Lines that aren't a top-level `key:` (block-scalar
   continuations, list items) are skipped."
  [fm-lines]
  (loop [lines (vec fm-lines), acc {}]
    (if (empty? lines)
      acc
      (let [line (first lines)
            m    (re-matches #"([A-Za-z0-9_.-]+):[ \t]*(.*)" line)]
        (if-not m
          (recur (subvec lines 1) acc)
          (let [k (keyword (nth m 1))
                v (str/trim (nth m 2))]
            (if (re-matches #"[>|][+-]?\d*" v)
              ;; Block scalar — value is the following indented/blank lines.
              (let [[block remaining] (split-with #(or (str/blank? %)
                                                       (re-find #"^[ \t]" %))
                                                  (subvec lines 1))
                    folded? (str/starts-with? v ">")
                    text    (->> block
                                 (map str/trim)
                                 (remove str/blank?)
                                 (str/join (if folded? " " "\n")))]
                (recur (vec remaining) (assoc acc k text)))
              (recur (subvec lines 1) (assoc acc k v)))))))))

(defn- parse-skill-md
  "Parse a SKILL.md file. Extracts title/description, with optional
   `---` frontmatter supporting `title`, `description`, `tags`, `version`
   (inline or `>`/`|` block-scalar values)."
  [content skill-name]
  (let [lines (str/split-lines content)
        [frontmatter body-lines]
        (if (and (seq lines) (= "---" (str/trim (first lines))))
          (let [rest-lines (rest lines)
                end-idx (some (fn [[i line]]
                                (when (= "---" (str/trim line)) i))
                              (map-indexed vector rest-lines))]
            (if end-idx
              (let [fm-lines (take end-idx rest-lines)
                    fm (parse-frontmatter fm-lines)]
                [fm (drop (inc end-idx) rest-lines)])
              [{} lines]))
          [{} lines])
        body (str/join "\n" body-lines)
        title (or (:title frontmatter)
                  ;; agentskills.io open standard keys on `name`; accept it as
                  ;; a title source alongside Brainyard's legacy `title`.
                  (:name frontmatter)
                  (second (re-find #"(?m)^#\s+(.+)$" body))
                  skill-name)
        description (or (:description frontmatter)
                        ;; First non-heading body line, if any. `some->` guards
                        ;; the nil case (heading-only body) — str/trim on nil NPEs.
                        (some-> (->> body-lines
                                     (remove str/blank?)
                                     (remove #(str/starts-with? (str/trim %) "#"))
                                     first)
                                str/trim))]
    (cond-> {:name skill-name
             :title title
             :description (or description "")}
      (:tags frontmatter)
      (assoc :tags (mapv str/trim (str/split (:tags frontmatter) #",")))
      (:version frontmatter)
      (assoc :version (:version frontmatter))
      ;; Surface the raw front-matter `name` (open-standard) so import can
      ;; derive the canonical skill name from it.
      (:name frontmatter)
      (assoc :fm-name (str/trim (:name frontmatter))))))

(defn- brainyard-list
  "List brainyard skills. `scope` nil = :project + :user."
  [dirs scope]
  (try
    (let [scopes (if scope [scope] [:project :user])
          entries (mapcat
                   (fn [s]
                     (when-let [dir (brainyard-skills-dir dirs s)]
                       (when (.isDirectory dir)
                         (->> (.listFiles dir)
                              (filter #(.isDirectory ^File %))
                              (keep (fn [^File skill-dir]
                                      (let [skill-name (.getName skill-dir)
                                            skill-md (io/file skill-dir "SKILL.md")]
                                        (when (.exists skill-md)
                                          (let [content (slurp skill-md)
                                                meta (parse-skill-md content skill-name)
                                                files (->> (file-seq skill-dir)
                                                           (filter #(.isFile ^File %))
                                                           count)]
                                            (assoc meta
                                                   :type :brainyard
                                                   :scope s
                                                   :path (.getPath skill-dir)
                                                   :file-count files))))))))))
                   scopes)]
      (vec entries))
    (catch Exception e
      {:error (str "brainyard-list failed: " (.getMessage e))})))

(defn- brainyard-read
  "Read a brainyard skill. Checks project scope first, then user scope,
   unless a specific `scope` is given."
  [dirs skill-name scope]
  (try
    (let [scopes (if scope [scope] [:project :user])
          found (some (fn [s]
                        (when-let [f (brainyard-skill-md dirs s skill-name)]
                          (when (.exists f)
                            {:file f :scope s})))
                      scopes)]
      (if found
        (let [{:keys [^File file scope]} found
              content (slurp file)
              ^File dir (.getParentFile file)
              files (->> (file-seq dir)
                         (filter #(.isFile ^File %))
                         (mapv (fn [^File f]
                                 {:path (subs (.getPath f) (inc (count (.getPath dir))))
                                  :size (.length f)})))]
          (merge (parse-skill-md content skill-name)
                 {:type :brainyard
                  :content content
                  :path (.getPath dir)
                  :scope scope
                  :size (count content)
                  :files files}))
        {:error (str "Brainyard skill not found: " skill-name)}))
    (catch Exception e
      {:error (str "brainyard-read failed: " (.getMessage e))})))

(defn- write-skill-extras!
  [^File dir {:keys [scripts resources]}]
  (when (seq scripts)
    (let [scripts-dir (io/file dir "scripts")]
      (.mkdirs scripts-dir)
      (doseq [[filename file-content] scripts]
        (let [f (io/file scripts-dir (str filename))]
          (spit f file-content)
          (when (str/ends-with? (str filename) ".sh")
            (.setExecutable f true))))))
  (when (seq resources)
    (let [res-dir (io/file dir "resources")]
      (.mkdirs res-dir)
      (doseq [[filename file-content] resources]
        (spit (io/file res-dir (str filename)) file-content)))))

(defn- brainyard-create
  "Create a new brainyard skill directory with SKILL.md + optional
   scripts/resources maps."
  [dirs scope skill-name content opts]
  (try
    (let [name (sanitize-skill-name skill-name)]
      (if (str/blank? name)
        {:error "Invalid skill name"}
        (let [dir (brainyard-skill-dir dirs scope name)]
          (if-not dir
            {:error "Cannot resolve brainyard skills directory — dirs config missing"}
            (let [skill-md (io/file dir "SKILL.md")]
              (if (.exists skill-md)
                {:error (str "Skill already exists: " name ". Use update-skill to modify.")}
                (do (.mkdirs dir)
                    (spit skill-md content)
                    (write-skill-extras! dir opts)
                    (merge (parse-skill-md content name)
                           {:type :brainyard
                            :scope scope
                            :path (.getPath dir)
                            :created (str (Instant/now))}))))))))
    (catch Exception e
      {:error (str "brainyard-create failed: " (.getMessage e))})))

(defn- brainyard-update
  "Update a brainyard skill's SKILL.md content and/or extras.
   `scope` nil = prefer project, fall back to user."
  [dirs skill-name content scope opts]
  (try
    (let [scopes (if scope [scope] [:project :user])
          found (some (fn [s]
                        (when-let [f (brainyard-skill-md dirs s skill-name)]
                          (when (.exists f)
                            {:dir (.getParentFile f) :md f :scope s})))
                      scopes)]
      (if-not found
        {:error (str "Brainyard skill not found: " skill-name)}
        (let [{:keys [^File dir ^File md scope]} found]
          (when (some? content)
            (spit md content))
          (write-skill-extras! dir opts)
          (let [new-content (slurp md)]
            (merge (parse-skill-md new-content skill-name)
                   {:type :brainyard
                    :scope scope
                    :path (.getPath dir)
                    :updated (str (Instant/now))})))))
    (catch Exception e
      {:error (str "brainyard-update failed: " (.getMessage e))})))

(defn- brainyard-remove
  "Remove a brainyard skill directory."
  [dirs skill-name scope]
  (try
    (let [scopes (if scope [scope] [:project :user])
          found (some (fn [s]
                        (when-let [d (brainyard-skill-dir dirs s skill-name)]
                          (when (.isDirectory d) {:dir d :scope s})))
                      scopes)]
      (if-not found
        {:error (str "Brainyard skill not found: " skill-name)}
        (let [{:keys [^File dir scope]} found]
          (doseq [f (reverse (file-seq dir))] (.delete ^File f))
          {:deleted skill-name :type :brainyard :scope scope})))
    (catch Exception e
      {:error (str "brainyard-remove failed: " (.getMessage e))})))

;; ============================================================================
;; CLI backend (npx skills — used for :claude and :agents)
;; ============================================================================

(defn- cli-target-flag
  "Return the `--target` flag fragment for a CLI skill type, or \"\" for
   unspecified. `npx skills` supports both agents (default) and claude scopes."
  [type]
  (case type
    :claude " --target claude"
    :agents " --target agents"
    ""))

(defn- cli-dirs-for
  "Return candidate install directories under HOME for a CLI type.
   Used for local filesystem inspection (list, read) since `npx skills`
   doesn't offer a stable JSON read API."
  [type]
  (let [home (System/getProperty "user.home")]
    (case type
      :claude [(str home "/.claude/skills")]
      :agents [(str home "/.agents/skills")]
      ;; default: both
      [(str home "/.agents/skills") (str home "/.claude/skills")])))

(defn- cli-list-from-fs
  "List CLI skills by scanning install dirs directly (fast, no npx call)."
  [type]
  (try
    (let [dirs (cli-dirs-for type)]
      (->> dirs
           (mapcat (fn [d]
                     (let [f (io/file d)]
                       (when (.isDirectory f)
                         (->> (.listFiles f)
                              (filter #(.isDirectory ^File %))
                              (mapv (fn [^File skill-dir]
                                      (let [n (.getName skill-dir)
                                            md (io/file skill-dir "SKILL.md")
                                            meta (if (.exists md)
                                                   (parse-skill-md (slurp md) n)
                                                   {:name n :title n :description ""})
                                            t (if (str/includes? (.getPath skill-dir) "/.claude/skills/")
                                                :claude
                                                :agents)]
                                        (assoc meta
                                               :type t
                                               :path (.getPath skill-dir))))))))))
           vec))
    (catch Exception e
      {:error (str "cli-list failed: " (.getMessage e))})))

(defn- cli-find
  "Search CLI skills by query (delegates to `npx skills find`)."
  [type query]
  (let [r (run-cmd-sync (str "npx skills find " (pr-str (str query))
                             (cli-target-flag type)) 15000)]
    (if (:error r) {:error (:error r)} {:result (:output r) :type (or type :cli)})))

(defn- cli-read-from-fs
  "Read a CLI skill's SKILL.md from local install dirs."
  [type skill-name]
  (let [candidates (map (fn [d] (str d "/" skill-name "/SKILL.md"))
                        (cli-dirs-for type))
        found (first (filter #(.exists (io/file ^String %)) candidates))]
    (if found
      (try
        (let [content (slurp found)
              dir (.getParentFile (io/file found))]
          (merge (parse-skill-md content skill-name)
                 {:type (cond (str/includes? found "/.claude/skills/") :claude
                              :else :agents)
                  :content content
                  :path (.getPath dir)
                  :size (count content)}))
        (catch Exception e
          {:error (str "cli-read failed: " (.getMessage e))}))
      {:error (str "CLI skill '" skill-name "' not found. Searched: "
                   (str/join ", " candidates))})))

(defn- cli-install
  "Install a CLI skill package. Format: 'owner/repo' or 'owner/repo@skill'."
  [type package]
  (let [r (run-cmd-sync (str "npx skills add " package " -g -y"
                             (cli-target-flag type)) 30000)]
    (if (:error r) {:error (:error r)} {:result (:output r) :type (or type :cli)})))

(defn- cli-remove
  [type skill-name]
  (let [r (run-cmd-sync (str "npx skills remove " skill-name " -g -y"
                             (cli-target-flag type)) 15000)]
    (if (:error r) {:error (:error r)} {:result (:output r) :type (or type :cli)})))

(defn- cli-sync
  "Update all CLI skills (`npx skills update`)."
  [type]
  (let [r (run-cmd-sync (str "npx skills update" (cli-target-flag type)) 30000)]
    (if (:error r) {:error (:error r)} {:result (:output r) :type (or type :cli)})))

;; ============================================================================
;; Unified plain API — dispatch by :type
;; ============================================================================

(defn- coerce-type
  "Coerce :type value (kw or string) to a keyword, or nil."
  [t]
  (cond
    (nil? t)     nil
    (keyword? t) t
    (string? t)  (when-not (str/blank? t) (keyword t))
    :else        nil))

(defn- coerce-scope
  [s]
  (cond
    (nil? s)     nil
    (keyword? s) s
    (string? s)  (when-not (str/blank? s) (keyword s))
    :else        nil))

(defn list-skills
  "List skills across all types (or a single type).

   Options:
   - :type  — :brainyard | :claude | :agents | nil (all)
   - :scope — brainyard only: :project | :user | nil (both)"
  [dirs & {:keys [type scope]}]
  (let [t (coerce-type type)
        s (coerce-scope scope)]
    (case t
      :brainyard (brainyard-list dirs s)
      :claude    (cli-list-from-fs :claude)
      :agents    (cli-list-from-fs :agents)
      (vec (concat (or (brainyard-list dirs s) [])
                   (or (cli-list-from-fs :claude) [])
                   (or (cli-list-from-fs :agents) []))))))

;; ============================================================================
;; Local relevance ranking
;;
;; Discovery is the substrate's hot path — `skill-substrate-protocol` tells every
;; coact/react-derived agent to check for a skill before any multi-step
;; procedure. So it must be LOCAL-ONLY and offline: no subprocess, no network.
;; Marketplace search is a separate, explicit concern (see `search-marketplace`).
;; ============================================================================

(def ^:const min-token-length
  "Query tokens shorter than this are dropped. Two-letter fragments occur inside
   unrelated words — \"no\" sits in \"autonomous\", \"id\" in \"video\" — and with
   substring matching they flood the ranking with coincidences."
  3)

(defn- query-tokens
  "Lower-cased, de-duplicated word tokens of a query. Any non-alphanumeric run
   is a separator, so \"lint markdown!\" and \"lint-markdown\" tokenize alike.
   Tokens below `min-token-length` are discarded as noise."
  [query]
  (->> (str/split (str/lower-case (str query)) #"[^a-z0-9]+")
       (remove #(< (count %) min-token-length))
       distinct
       vec))

(defn- word-start-match?
  "True when `token` starts a word within `haystack`. Plain substring matching
   is too loose for prose: it makes \"lint\" match \"glint\" and \"cli\" match
   \"client\". Anchoring to a word boundary keeps partial-word typing useful
   (\"mark\" still finds \"markdown\") without the false hits."
  [haystack token]
  (boolean (re-find (re-pattern (str "\\b" (java.util.regex.Pattern/quote token)))
                    haystack)))

(def ^:private backend-rank
  "Precedence when the same skill name exists in several backends. Skills the
   user authored locally outrank CLI-installed ones, and project outranks user —
   most specific wins. Drives both find ordering and which backend keeps the
   bare `:skill$<name>` id (see `resolve-registrations`)."
  {[:brainyard :project] 0
   [:brainyard :user]    1
   [:claude    nil]      2
   [:agents    nil]      3})

(defn skill-rank
  "Backend precedence of a skill map — lower is more local. Unknown backends
   sort last."
  [{:keys [type scope]}]
  (or (backend-rank [type scope])
      (case type :brainyard 1, :claude 2, :agents 3, 4)))

(defn- backend-label
  "Human-readable backend of a skill — \"brainyard/project\", \"claude\", …"
  [{:keys [type scope]}]
  (str (name (or type :unknown)) (when scope (str "/" (name scope)))))

(defn- field-score
  "Score one token against one field: exact match beats field-prefix beats a
   word-start hit anywhere in the field."
  [haystack token exact-pts starts-pts contains-pts]
  (let [h (str/lower-case (str haystack))]
    (cond
      (str/blank? h)               0
      (= h token)                  exact-pts
      (str/starts-with? h token)   starts-pts
      (word-start-match? h token)  contains-pts
      :else                        0)))

(defn score-skill
  "Deterministic relevance score for a skill map against query tokens; 0 means
   no field matched any token. Name dominates, then tags, then title, then
   description — so a query of \"pdf\" ranks the `pdf` skill above one that only
   mentions PDFs in its prose."
  [{:keys [name title description tags]} tokens]
  (reduce
   (fn [acc token]
     (+ acc
        (field-score name token 100 40 25)
        (reduce + 0 (map #(field-score % token 20 12 8) tags))
        (field-score title token 30 15 10)
        (if (word-start-match? (str/lower-case (str description)) token) 6 0)))
   0
   tokens))

(def ^:const min-match-score
  "Floor a skill must clear to count as a match. One description mention scores
   6, so the floor keeps single prose coincidences out while any name, title or
   tag hit — or two independent description hits — qualifies."
  10)

(def ^:const default-find-limit
  "Default cap on `find-skills` matches. A machine with several CLI backends
   installed can carry 50+ skills; an unbounded dump is unreadable to the model
   and crowds the turn."
  20)

(defn- collapse-duplicate-names
  "Collapse same-named matches to one row — the most local backend — recording
   the others under `:also-in`.

   The same skill is routinely installed to both `~/.claude/skills` and
   `~/.agents/skills`, which surfaced as two identical rows with nothing to
   choose between them. One row plus its provenance is what the caller can act
   on, and it mirrors registration precedence (`resolve-registrations`), where
   the most local backend likewise wins."
  [ranked]
  (->> (group-by :name ranked)
       (map (fn [[_ dupes]]
              (let [[winner & others] (sort-by skill-rank dupes)]
                (cond-> winner
                  (seq others) (assoc :also-in (mapv backend-label others))))))
       (sort-by (juxt (comp - :score) skill-rank :name))
       vec))

(defn rank-skills
  "Score, filter and order skill maps for `query`: matches only, best first,
   each carrying the `:score` that ranked it. Same-named skills from several
   backends collapse to the most local one (`:also-in` names the rest). Ties
   break toward the more local backend, then name, so ordering is stable."
  [skills query & {:keys [limit] :or {limit default-find-limit}}]
  (let [tokens (query-tokens query)]
    (if (empty? tokens)
      []
      (->> skills
           (filter map?)
           (keep (fn [s]
                   (let [sc (score-skill s tokens)]
                     (when (>= sc min-match-score) (assoc s :score sc)))))
           ;; collapse-duplicate-names does the ordering — it has to re-sort
           ;; after picking a winner per name anyway.
           collapse-duplicate-names
           (take limit)
           vec))))

(defn find-skills
  "Search INSTALLED skills by query across backends. Local-only and offline —
   no subprocess, no network — because every agent runs this via the skill
   substrate before multi-step work.

   Returns a ranked vector of skill maps (best first), each carrying its
   `:type`, `:scope` and the `:score` that ranked it. No match returns `[]`.

   Options:
   - :type  — restrict to :brainyard | :claude | :agents (nil = all)
   - :limit — max matches (default `default-find-limit`)

   To search the marketplace for skills NOT installed, call `search-marketplace`
   explicitly — it shells out to `npx` and returns install candidates, which is
   a different question from 'what can I use right now'."
  [dirs query & {:keys [type limit]}]
  (rank-skills (list-skills dirs :type (coerce-type type))
               query
               :limit (or limit default-find-limit)))

(defn search-marketplace
  "Search the `npx skills` MARKETPLACE for installable packages. Deliberately
   separate from `find-skills`: this spawns a subprocess per backend (~5 s round
   trip) and answers 'what could I install', not 'what do I have'.

   Options: :type — :claude | :agents (nil = both)."
  [query & {:keys [type]}]
  (let [t (coerce-type type)]
    (case t
      :claude {:claude (cli-find :claude query)}
      :agents {:agents (cli-find :agents query)}
      {:claude (cli-find :claude query)
       :agents (cli-find :agents query)})))

(defn read-skill
  "Read a skill's SKILL.md + metadata.
   Options:
   - :type  — force a backend. nil = try brainyard, then claude, then agents.
   - :scope — brainyard only."
  [dirs skill-name & {:keys [type scope]}]
  (let [t (coerce-type type)
        s (coerce-scope scope)]
    (case t
      :brainyard (brainyard-read dirs skill-name s)
      :claude    (cli-read-from-fs :claude skill-name)
      :agents    (cli-read-from-fs :agents skill-name)
      (let [by (brainyard-read dirs skill-name s)]
        (if-not (:error by)
          by
          (let [cl (cli-read-from-fs :claude skill-name)]
            (if-not (:error cl)
              cl
              (cli-read-from-fs :agents skill-name))))))))

(defn create-skill
  "Create a skill. Only :brainyard is creatable — CLI skills are installed
   (see install-skill).

   Options:
   - :type      — defaults to :brainyard. Any other value returns an error.
   - :scope     — :project (default) | :user
   - :scripts   — {filename content} written under scripts/
   - :resources — {filename content} written under resources/"
  [dirs skill-name content & {:keys [type scope scripts resources]
                              :or {type :brainyard scope :project}}]
  (let [t (coerce-type type)
        s (or (coerce-scope scope) :project)]
    (if (and t (not= t :brainyard))
      {:error (str "create-skill only supports :brainyard type (got " t
                   "). Use install-skill for :claude or :agents.")}
      (brainyard-create dirs s skill-name content
                        {:scripts scripts :resources resources}))))

(defn update-skill
  "Update a skill. Only :brainyard supports direct edits — CLI skills
   are refreshed via sync-skills.

   Options:
   - :type      — defaults to :brainyard.
   - :scope     — brainyard only; nil = auto-detect.
   - :content   — new SKILL.md content (optional if only replacing extras).
   - :scripts / :resources — same shape as create-skill; merges/overwrites."
  [dirs skill-name & {:keys [type scope content scripts resources]
                      :or {type :brainyard}}]
  (let [t (coerce-type type)
        s (coerce-scope scope)]
    (if (and t (not= t :brainyard))
      {:error (str "update-skill only supports :brainyard type (got " t
                   "). Use sync-skills for :claude or :agents.")}
      (brainyard-update dirs skill-name content s
                        {:scripts scripts :resources resources}))))

(defn remove-skill
  "Remove a skill.
   Options:
   - :type  — :brainyard | :claude | :agents. nil = try brainyard, then claude, then agents.
   - :scope — brainyard only."
  [dirs skill-name & {:keys [type scope]}]
  (let [t (coerce-type type)
        s (coerce-scope scope)]
    (case t
      :brainyard (brainyard-remove dirs skill-name s)
      :claude    (cli-remove :claude skill-name)
      :agents    (cli-remove :agents skill-name)
      (let [by (brainyard-remove dirs skill-name s)]
        (if-not (:error by)
          by
          (let [cl (cli-remove :claude skill-name)]
            (if-not (:error cl)
              cl
              (cli-remove :agents skill-name))))))))

(defn install-skill
  "Install a CLI skill package (via `npx skills add -g`). Format: 'owner/repo' or 'owner/repo@skill'.
   Options:
   - :type — :claude | :agents (default :agents)."
  [package & {:keys [type] :or {type :agents}}]
  (let [t (coerce-type type)]
    (if (and t (not (#{:claude :agents} t)))
      {:error (str "install-skill only supports :claude or :agents (got " t ").")}
      (cli-install (or t :agents) package))))

(defn sync-skills
  "Update all installed CLI skills to latest versions.
   Options:
   - :type — :claude | :agents | nil (both)."
  [& {:keys [type]}]
  (let [t (coerce-type type)]
    (case t
      :claude (cli-sync :claude)
      :agents (cli-sync :agents)
      {:claude (cli-sync :claude) :agents (cli-sync :agents)})))

;; ============================================================================
;; Dirs resolution for defcommands
;; ============================================================================

(defn- current-dirs
  "Resolve dirs from the current agent session, falling back to init-dirs!.
   Uses requiring-resolve to avoid circular deps: this ns is loaded before
   core.agent / core.dirs in some cold-start paths."
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
;; defcommand wrappers
;; ============================================================================

(defcommand skills$list
  "List skills across types. Optional :type (brainyard|claude|agents) and :scope (brainyard only)."
  (fn [& {:as args}]
    (let [r (list-skills (current-dirs)
                         :type (:type args)
                         :scope (:scope args))]
      (if (map? r)
        r                                    ; an {:error …} from a backend
        {:result (vec r) :count (count r)})))
  :input-schema  [:map
                  [:type  {:optional true} [:string {:desc "brainyard | claude | agents (omit for all)"}]]
                  [:scope {:optional true} [:string {:desc "brainyard scope: project | user (omit for both)"}]]]
  :output-schema [:map
                  [:result [:any    {:desc "Vector of skill summaries (name/title/description/type/scope/path)"}]]
                  [:count  [:int    {:desc "Number of skills listed"}]]
                  [:error  [:string {:desc "Error message if failed"}]]])

(defcommand skills$find
  "Search INSTALLED skills by query; ranked, local-only, no network. Optional :type and :limit."
  (fn [& {:as args}]
    (if (str/blank? (:query args))
      {:error "query is required"}
      (let [r (find-skills (current-dirs) (:query args)
                           :type  (:type args)
                           :limit (:limit args))]
        {:result r :count (count r)})))
  :input-schema  [:map
                  [:query [:string {:desc "Search query"}]]
                  [:type  {:optional true} [:string {:desc "brainyard | claude | agents (omit for all)"}]]
                  [:limit {:optional true} [:int {:desc "Max matches (default 20)"}]]]
  :output-schema [:map
                  [:result [:any    {:desc "Ranked vector of matching skills, best first; each has :name :type :scope :description :score, plus :also-in when the same name is installed in other backends. Empty when nothing matches."}]]
                  [:count  [:int    {:desc "Number of matches returned"}]]
                  [:error  [:string {:desc "Error message if failed"}]]])

(defcommand skills$search
  "Search the npx skills MARKETPLACE for installable packages (slow, network). Use skills$find for skills you already have."
  (fn [& {:as args}]
    (if (str/blank? (:query args))
      {:error "query is required"}
      {:result (search-marketplace (:query args) :type (:type args))}))
  :input-schema  [:map
                  [:query [:string {:desc "Search query"}]]
                  [:type  {:optional true} [:string {:desc "claude | agents (omit for both)"}]]]
  :output-schema [:map
                  [:result [:any    {:desc "Marketplace results by backend — install candidates, NOT installed skills"}]]
                  [:error  [:string {:desc "Error message if failed"}]]])

(defcommand skills$read
  "Read a skill's SKILL.md + metadata. Auto-detects type when :type is omitted."
  (fn [& {:as args}]
    (if (str/blank? (:skill-name args))
      {:error "skill-name is required"}
      (read-skill (current-dirs) (:skill-name args)
                  :type (:type args) :scope (:scope args))))
  :input-schema  [:map
                  [:skill-name [:string {:desc "Skill name"}]]
                  [:type       {:optional true} [:string {:desc "brainyard | claude | agents (omit to auto-detect)"}]]
                  [:scope      {:optional true} [:string {:desc "brainyard scope: project | user"}]]]
  :output-schema [:map
                  [:name        [:string {:desc "Skill name"}]]
                  [:title       [:string {:desc "Skill title"}]]
                  [:description [:string {:desc "Skill description"}]]
                  [:content     [:string {:desc "SKILL.md content"}]]
                  [:path        [:string {:desc "Path to skill directory"}]]
                  [:type        [:string {:desc "Skill type"}]]
                  [:scope       [:string {:desc "Brainyard scope (when applicable)"}]]
                  [:size        [:int {:desc "Content size in bytes"}]]
                  [:files       [:any {:desc "File listing (brainyard only)"}]]
                  [:error       [:string {:desc "Error message if not found"}]]])

;; ============================================================================
;; Per-op helpers — used by skills$write
;; ============================================================================

(defn- do-skill-create
  [{:keys [skill-name content type scope scripts resources]}]
  (cond
    (str/blank? skill-name) {:error "skill-name is required"}
    (str/blank? content)    {:error "content is required"}
    :else (create-skill (current-dirs) skill-name content
                        :type (or type :brainyard)
                        :scope (or scope :project)
                        :scripts scripts
                        :resources resources)))

(defn- do-skill-update
  [{:keys [skill-name content type scope scripts resources]}]
  (if (str/blank? skill-name)
    {:error "skill-name is required"}
    (update-skill (current-dirs) skill-name
                  :type (or type :brainyard)
                  :scope scope
                  :content content
                  :scripts scripts
                  :resources resources)))

(defn- do-skill-remove
  [{:keys [skill-name type scope]}]
  (if (str/blank? skill-name)
    {:error "skill-name is required"}
    (remove-skill (current-dirs) skill-name :type type :scope scope)))

;; ============================================================================
;; Polymorphic command — preferred surface for skill mutation
;; ============================================================================

(defcommand skills$write
  "Mutate a skill on disk via :op — :create (needs :skill-name+:content), :update (needs :skill-name), or :remove (needs :skill-name)."
  (fn [& {:keys [op] :as args}]
    (case (some-> op keyword)
      :create (do-skill-create args)
      :update (do-skill-update args)
      :remove (do-skill-remove args)
      nil     {:error ":op is required (one of :create :update :remove)"}
      {:error (str "Unknown :op '" op "'. Valid: :create :update :remove")}))
  :input-schema  [:map
                  [:op         [:enum {:desc "Operation: create | update | remove"}
                                "create" "update" "remove"]]
                  [:skill-name [:string {:desc "Skill name (required for every op)"}]]
                  [:content    {:optional true} [:string {:desc "For :create — SKILL.md content. For :update — new SKILL.md content (omit to leave unchanged)."}]]
                  [:type       {:optional true} [:string {:desc "brainyard | claude | agents. Defaults to :brainyard for :create/:update; auto-detected for :remove."}]]
                  [:scope      {:optional true} [:string {:desc "Brainyard scope: project | user. :create defaults to project; :update/:remove auto-detect when omitted."}]]
                  [:scripts    {:optional true} [:map-of {:desc "Map of {filename content} for scripts/ subdir (create/update only)"} :string :string]]
                  [:resources  {:optional true} [:map-of {:desc "Map of {filename content} for resources/ subdir (create/update only)"} :string :string]]]
  :output-schema [:map
                  [:name    [:string {:desc "Skill name"}]]
                  [:path    [:string {:desc "Path to skill dir (create/update)"}]]
                  [:created [:string {:desc "Creation timestamp (create)"}]]
                  [:updated [:string {:desc "Update timestamp (update)"}]]
                  [:deleted [:string {:desc "Deleted skill name (remove)"}]]
                  [:type    [:string {:desc "Skill type (remove)"}]]
                  [:result  [:string {:desc "CLI output when remove targets claude/agents"}]]
                  [:error   [:string {:desc "Error message if failed"}]]])

(defcommand skills$install
  "Install a CLI skill package (npx skills add). Format: 'owner/repo' or 'owner/repo@skill'."
  (fn [& {:as args}]
    (if (str/blank? (:package args))
      {:error "package is required"}
      (install-skill (:package args) :type (or (:type args) :agents))))
  :input-schema  [:map
                  [:package [:string {:desc "Skill package: 'owner/repo' or 'owner/repo@skill'"}]]
                  [:type    {:optional true} [:string {:desc "claude | agents (default: agents)"}]]]
  :output-schema [:map
                  [:result [:string {:desc "Install output"}]]
                  [:type   [:string {:desc "Install target"}]]
                  [:error  [:string {:desc "Error message if failed"}]]])

(defcommand skills$import
  "Import an external SKILL.md (agentskills.io open standard) from a local path into the brainyard backend."
  (fn [& {:keys [path name scope]}]
    (if (str/blank? path)
      {:error "path is required (a SKILL.md file or a directory containing one)"}
      (let [f (io/file path)
            md-file (cond
                      (and (.isDirectory f) (.exists (io/file f "SKILL.md"))) (io/file f "SKILL.md")
                      (.isFile f) f
                      :else nil)]
        (if (or (nil? md-file) (not (.exists ^File md-file)))
          {:error (str "No SKILL.md found at " path)}
          (let [content    (slurp md-file)
                parsed     (parse-skill-md content "imported")
                skill-name (sanitize-skill-name
                            (or (not-empty (some-> name str/trim))
                                (:fm-name parsed)
                                (when (.isDirectory f) (.getName f))))]
            (cond
              (str/blank? skill-name)
              {:error "Could not determine skill name — pass :name or add a `name:` front-matter field"}
              (str/blank? (:description parsed))
              {:error "SKILL.md has no description — the open standard requires name + description"}
              :else
              (create-skill (current-dirs) skill-name content
                            :scope (if (str/blank? scope) :project (keyword scope)))))))))
  :input-schema  [:map
                  [:path  [:string {:desc "Path to a SKILL.md file or a directory containing one"}]]
                  [:name  {:optional true} [:string {:desc "Override the imported skill name (else front-matter `name` / dir name)"}]]
                  [:scope {:optional true} [:string {:desc "brainyard scope: project (default) | user"}]]]
  :output-schema [:map
                  [:name        {:optional true} [:string {:desc "Imported skill name"}]]
                  [:title       {:optional true} [:string {:desc "Skill title"}]]
                  [:description {:optional true} [:string {:desc "Skill description"}]]
                  [:path        {:optional true} [:string {:desc "Path to the new skill dir"}]]
                  [:created     {:optional true} [:string {:desc "Creation timestamp"}]]
                  [:error       {:optional true} [:string {:desc "Error message if failed"}]]])

(defcommand skills$sync
  "Update all installed CLI skills to latest versions. Optional :type."
  (fn [& {:as args}]
    (sync-skills :type (:type args)))
  :input-schema  [:map
                  [:type {:optional true} [:string {:desc "claude | agents (omit for both)"}]]]
  :output-schema [:map
                  [:result [:any {:desc "Update output (single target) or map by type"}]]
                  [:error  [:string {:desc "Error message if failed"}]]])

;; ============================================================================
;; Dynamic skill registration (parallel to mcp/integration register-mcp-tools)
;;
;; Each available skill (brainyard / claude / agents) is registered in
;; `tool/!tool-defs` under id `:skill$<name>`. The registered fn reads the
;; skill's SKILL.md fresh on every call and asks the agent's LM to follow it
;; against the caller's :question. `skills$reload` re-registers everything and
;; drops entries whose source skill no longer exists.
;; ============================================================================

(defonce ^:private dynamic-skills-ns
  (create-ns 'ai.brainyard.agent.common.dynamic-skills))

(defonce ^:private !registered-dynamic-skill-ids
  (atom #{}))

(def ^:private clj-symbol-name-re
  #"[A-Za-z_][A-Za-z0-9_\-.+!?<>=]*")

(defn- safe-skill-symbol-name? [s]
  (boolean (and s (re-matches clj-symbol-name-re (str s)))))

(defn- intern-dynamic-skill-var
  "Intern a synthetic var carrying the skill wrapper fn so def->tool's
   `(meta @tool-fn)` path keeps working."
  [id meta-info fn-impl]
  (let [vsym (symbol (name id))
        f    (with-meta fn-impl meta-info)]
    (intern dynamic-skills-ns (with-meta vsym meta-info) f)))

(defn- unintern-dynamic-skill-var [id]
  (ns-unmap dynamic-skills-ns (symbol (name id))))

(defn- skill-system-prompt
  [skill-name skill-content]
  (str "You are executing the skill `" skill-name "`. "
       "Read its definition below and follow its instructions to answer "
       "the user's question. Be concise and accurate.\n\n"
       "## SKILL DEFINITION\n"
       skill-content))

(defn- format-skill-output
  [output skill-name skill-type]
  (let [base {:skill skill-name
              :type  (name (or skill-type :unknown))}]
    (cond
      (and (map? output) (:error output))
      (assoc base :error-message (:error output))

      (and (map? output) (:answer output))
      (cond-> (assoc base :answer (:answer output))
        (:usage output) (assoc :usage (:usage output)))

      :else
      (assoc base :answer (str output)))))

(defn- make-dynamic-skill-fn
  "Build the :fn body for a dynamically-registered skill. Reads SKILL.md
   fresh on each call so edits propagate without another reload. Dispatches
   the question to the registered `:skill-agent` defagent (via invoke-tool),
   passing the SKILL.md content as :agent-context.

   Returns `{:answer ... :skill ... :type ... :usage?}` on success or
   `{:error-message ... :skill ...}` on failure."
  [skill-name skill-type skill-scope]
  (fn [& {:keys [question]}]
    (let [q (or question "")]
      (cond
        (str/blank? q)
        {:error-message "question is required" :skill skill-name}

        (nil? (tool/get-tool-defs :id :skill-agent))
        {:error-message "skill-agent not registered — load ai.brainyard.agent.common.skill-agent"
         :skill skill-name}

        :else
        (let [skill (read-skill (current-dirs) skill-name
                                :type skill-type :scope skill-scope)]
          (if (:error skill)
            {:error-message (:error skill) :skill skill-name}
            (try
              (let [parent proto/*current-agent*
                    sess   (when parent
                             {:user-id    (proto/user-id parent)
                              :session-id (proto/session-id parent)})
                    ctx    (skill-system-prompt skill-name (:content skill))
                    raw    (tool/invoke-tool :skill-agent
                                             :question      q
                                             :agent-context ctx
                                             :parent-agent  parent
                                             :agent-session sess
                                             :auto-close?   true)
                    output (tool/resolve-agent-ref raw)]
                (format-skill-output output skill-name (:type skill)))
              (catch Exception e
                (mulog/error ::dynamic-skill-call-failed
                             :skill skill-name :error (ex-message e))
                {:error-message (str "Skill `" skill-name "` failed: " (ex-message e))
                 :skill skill-name}))))))))

(defn- dynamic-skill-id [skill-name]
  (keyword (str "skill$" skill-name)))

(defn- qualified-skill-id
  "Backend-qualified id (`:skill$<backend>$<name>`) for a skill that lost the
   bare `:skill$<name>` id to a more local backend."
  [skill]
  (keyword (str "skill$" (name (or (:type skill) :unknown)) "$" (:name skill))))

(defn resolve-registrations
  "Decide the id each discovered skill registers under.

   Skill names are NOT unique across backends — `~/.claude/skills/pdf` and
   `~/.agents/skills/pdf` are different skills with the same name, and a project
   skill can collide with either. Registering them all as `:skill$<name>` meant
   the last one written to `!tool-defs` silently won, in discovery order — so
   CLI-installed skills shadowed the user's own, which is backwards.

   The most LOCAL backend (`skill-rank`: brainyard/project > brainyard/user >
   claude > agents) keeps the bare `:skill$<name>` id; every other skill sharing
   that name registers under `:skill$<backend>$<name>` so it stays invocable
   instead of disappearing.

   Returns `[registrations shadowed]` — registrations are skill maps with `:id`
   assoc'd; shadowed are the losing skill maps (for logging)."
  [skills]
  (reduce (fn [[regs shadowed] [_ group]]
            (let [[winner & losers] (sort-by (juxt skill-rank backend-label) group)]
              [(into (conj regs (assoc winner :id (dynamic-skill-id (:name winner))))
                     (map #(assoc % :id (qualified-skill-id %)) losers))
               (into shadowed losers)]))
          [[] []]
          (group-by :name (filter #(and (map? %) (:name %)) skills))))

(defn- dynamic-skill-meta
  [id {:keys [name description type scope]}]
  {:id id
   :type :skill
   ;; Name the backend: with name-shadowing resolved by qualified ids, two
   ;; distinct skills can share a name (`:skill$pdf` vs `:skill$agents$pdf`),
   ;; and the description is how the model tells them apart.
   :description (let [d   (or description "")
                      ;; `name` is shadowed by the destructured skill name here.
                      src (str "skill: `" name "` [" (clojure.core/name (or type :unknown))
                               (when scope (str "/" (clojure.core/name scope))) "]")]
                  (if (str/blank? d)
                    (str "Run the `" name "` skill against the user question. (" src ")")
                    (str d " (" src ")")))
   :input-schema [:map
                  [:question [:string {:desc "The user question to answer using this skill"}]]]
   :output-schema [:map
                   [:answer        [:string {:desc "Skill answer produced by the LM"}]]
                   [:skill         [:string {:desc "Skill name that produced the answer"}]]
                   [:type          [:string {:desc "Skill source type (brainyard | claude | agents)"}]]
                   [:error-message [:string {:desc "Error message if the skill failed"}]]]
   :skill-source type
   :skill-scope  scope})

(defn- register-dynamic-skill!
  "Register a single skill in tool/!tool-defs. Returns the id on success,
   nil if skipped (bad name). Honours a precomputed `:id` (from
   `resolve-registrations`) so a name-shadowed skill lands on its qualified id;
   falls back to the bare id for direct callers."
  [{:keys [name type scope] :as skill}]
  (cond
    (not (safe-skill-symbol-name? name))
    (do (mulog/warn ::dynamic-skill-skipped
                    :reason :bad-skill-name :skill name)
        nil)

    :else
    (try
      (let [id      (or (:id skill) (dynamic-skill-id name))
            meta    (dynamic-skill-meta id skill)
            fn-impl (make-dynamic-skill-fn name type scope)
            v       (intern-dynamic-skill-var id meta fn-impl)]
        (swap! tool/!tool-defs assoc id
               {:id id :type :skill :fn v :meta meta})
        (swap! !registered-dynamic-skill-ids conj id)
        id)
      (catch Exception e
        (mulog/warn ::dynamic-skill-registration-failed
                    :skill name :error (ex-message e))
        nil))))

(defn- unregister-dynamic-skill!
  [id]
  (swap! tool/!tool-defs dissoc id)
  (unintern-dynamic-skill-var id)
  (swap! !registered-dynamic-skill-ids disj id)
  id)

(defn reload-skills!
  "Discover every available skill and (re)register it in tool/!tool-defs
   as `:skill$<name>`. Drops previously-registered dynamic skills whose
   source is no longer present.

   Returns {:registered [...] :unregistered [...] :total <n>}."
  []
  (let [raw      (list-skills (current-dirs))
        skills   (if (sequential? raw) raw [])
        [regs shadowed] (resolve-registrations skills)
        prior    @!registered-dynamic-skill-ids
        registered (into #{}
                         (keep register-dynamic-skill!)
                         regs)
        stale    (set/difference prior registered)]
    (doseq [id stale] (unregister-dynamic-skill! id))
    (doseq [s shadowed]
      (mulog/warn ::dynamic-skill-name-shadowed
                  :skill        (:name s)
                  :backend      (backend-label s)
                  :qualified-id (qualified-skill-id s)))
    (when (seq registered)
      (mulog/info ::dynamic-skills-reloaded
                  :registered (count registered)
                  :unregistered (count stale)
                  :shadowed (count shadowed)))
    {:registered   (mapv name (sort registered))
     :unregistered (mapv name (sort stale))
     :shadowed     (mapv (fn [s] {:name (:name s)
                                  :backend (backend-label s)
                                  :id (name (qualified-skill-id s))})
                         shadowed)
     :total        (count registered)}))

(defcommand skills$reload
  "Reload dynamic skill registrations as :skill$<name> tools; drops skills no longer on disk. Call after installing or editing skills."
  (fn [& _]
    (try
      (reload-skills!)
      (catch Exception e
        {:error (str "skills$reload failed: " (ex-message e))})))
  :input-schema  [:map]
  :output-schema [:map
                  [:registered   [:any    {:desc "Names of skills now registered as :skill$<name>"}]]
                  [:unregistered [:any    {:desc "Names dropped because the skill is no longer available"}]]
                  [:shadowed     [:any    {:desc "Skills whose name was taken by a more local backend; each registered under the backend-qualified :id instead"}]]
                  [:total        [:int    {:desc "Total registered dynamic skills"}]]
                  [:error        [:string {:desc "Error message if reload failed"}]]])

;; NOTE: Dynamic skills are registered at *runtime startup* by each entry
;; point (e.g. the TUI's `start!` calls `agent/reload-skills!` once after
;; config/dirs init), NOT by a namespace-load `defonce`.
;;
;; A `(defonce _reload-skills (reload-skills!))` here would fire during
;; namespace class-initialization. For the native `by` binary that init
;; happens at *build time* (the native-image policy initializes all
;; `ai.brainyard.*` namespaces at build time), so the bootstrap would scan
;; the build machine's skill directories and bake that snapshot into the
;; image heap — the user's own ~/.brainyard, ~/.claude, ~/.agents skills
;; would never be read at runtime. Registration must therefore be an
;; explicit runtime call. See `reload-skills!` above and `skills$reload`.

(def skills-commands
  "All skill management commands. `skills$write` is the polymorphic mutation
   surface (`:op :create|:update|:remove`); `skills$search` is the marketplace
   lookup that pairs with `skills$install`."
  [#'skills$list #'skills$find #'skills$read #'skills$write
   #'skills$install #'skills$import #'skills$sync #'skills$reload
   #'skills$search])

(def skills-read-subset
  "The USE half of the skill lifecycle — discover + read + registry refresh.
   Added to `default-agent-roster` so every coact/react-derived agent can use
   skills (the skill substrate, docs/design/skill-agent-design.md
   §6). The WRITE half (skills$write / install / import / sync + the proposal
   commands) stays on skill-agent only — use is universal, management is not.

   `skills$search` is deliberately NOT here: it shells out to `npx` per backend
   (~5 s) and answers 'what could I install', which is an install-flow question,
   not a use-a-skill-now question. Keeping it off the base roster is what makes
   the substrate's discovery step reliably fast and offline."
  [#'skills$find #'skills$read #'skills$list #'skills$reload])
