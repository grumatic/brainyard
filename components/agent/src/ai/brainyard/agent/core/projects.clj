;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.core.projects
  "User-scope project registry — `~/.brainyard/projects/<slug>/`.

   Gives every project a stable, per-account folder in user scope, so
   user-scoped state ABOUT a project has somewhere to live that is not the
   repo's own `<project>/.brainyard/` (which travels with the codebase and is
   typically gitignored per-repo). v1 stores registry metadata only; nothing
   moves out of the project-scoped dir.

   `projects` is `:user-only` in `core.config/subdir-scope-policy`, so the root
   resolves through the normal `brainyard-subdir` machinery and a caller that
   asks for it at `:project` scope gets nil rather than a stray dir inside a
   repo.

   ## Slug

   `<sanitized-basename>-<8 hex of SHA-256(canonical path)>`, e.g.
   `brainyard-3f9a1c2d`. Readable at a glance, always space-free, and stable:
   the same canonical path always yields the same slug, which is what makes
   registration idempotent. The hash suffix is what keeps two checkouts that
   share a basename (`~/Projects/x/brainyard` vs `~/MyDev/brainyard`) in
   separate folders.

   ## Reverse mapping is a LOOKUP, not an algorithm

   slug -> absolute path is recovered by reading `<slug>/project.edn`, which is
   exact and lossless. This is deliberate. The obvious alternative — encoding
   the path into the name by substituting separators
   (`/Users/me/my-app` -> `-Users-me-my-app`) — cannot be reversed
   unambiguously once a path segment contains the separator character itself,
   and real paths do. Percent-encoding the whole path reverses cleanly but is
   unreadable in `ls`. A short readable name plus an authoritative record file
   gets both properties.

   ## index.edn is a derived cache

   `index.edn` at the registry root maps slug -> path for fast listing. It is
   rebuilt by scanning the per-slug `project.edn` files and is NEVER read as
   the source of truth. That is what makes concurrent `by` processes safe:
   each writes only its own slug's `project.edn` (no contention), and a stale
   or torn index heals on the next refresh."
  (:require [ai.brainyard.agent.core.config :as config]
            [ai.brainyard.mulog.interface :as mulog]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.pprint :as pprint]
            [clojure.string :as str])
  (:import [java.io File]
           [java.nio.charset StandardCharsets]
           [java.nio.file CopyOption Files StandardCopyOption]
           [java.security MessageDigest]))

(def registry-subdir
  "Name of the registry dir under `~/.brainyard/`. Declared `:user-only` in
   `core.config/subdir-scope-policy`."
  "projects")

(def project-file
  "Per-project record filename. Authoritative for slug -> path."
  "project.edn")

(def index-file
  "Registry-root index filename. DERIVED cache only — rebuilt from the
   per-slug `project.edn` files, never read as the source of truth."
  "index.edn")

(def schema-version
  "Version stamped into `project.edn` / `index.edn`. Lets a later phase (a
   user-scope per-project `config.edn`, a project-partitioned memory DB)
   evolve the record shape without having to guess at old files."
  1)

(def ^:private max-name-len
  "Cap on the readable half of a slug, so a deeply-named directory can't
   produce an absurd folder name. The hash suffix carries uniqueness, not
   this part, so truncating is safe."
  48)

;; ============================================================================
;; Slug
;; ============================================================================

(defn- canonical-path
  "Canonical absolute path string for `path`, or nil for blank/nil input.
   Falls back to the absolute (non-canonical) path when canonicalization
   fails — a missing or unreadable dir should still get a stable slug, since
   we register paths that may later disappear."
  [path]
  (when-let [p (some-> path str str/trim not-empty)]
    (let [f (io/file p)]
      (try (.getCanonicalPath f)
           (catch Exception _ (.getAbsolutePath f))))))

(defn- sanitize-name
  "Reduce a directory basename to `[A-Za-z0-9._-]`, collapsing runs of
   replaced characters and trimming leading/trailing separators. Guarantees a
   non-blank, space-free result — `root` when nothing survives (e.g. `/`)."
  [s]
  (let [cleaned (-> (or s "")
                    (str/replace #"[^A-Za-z0-9._-]+" "-")
                    (str/replace #"-{2,}" "-")
                    (str/replace #"^[-.]+" "")
                    (str/replace #"[-.]+$" ""))
        capped  (cond-> cleaned
                  (> (count cleaned) max-name-len) (subs 0 max-name-len))
        ;; Re-trim: truncation can leave a trailing separator.
        capped  (str/replace capped #"[-.]+$" "")]
    (if (str/blank? capped) "root" capped)))

(defn- path-hash
  "First 4 bytes of SHA-256 over the canonical path, as 8 lowercase hex
   chars. Collision-resistant enough to separate a user's checkouts while
   staying short enough to read."
  [^String canonical]
  (->> (.digest (MessageDigest/getInstance "SHA-256")
                (.getBytes canonical StandardCharsets/UTF_8))
       (take 4)
       (map #(format "%02x" (bit-and (int %) 0xff)))
       (apply str)))

(defn project-slug
  "Registry folder name for `path`: `<sanitized-basename>-<8-hex>`.
   Space-free and stable across calls. Returns nil for blank/nil input.

   Note the slug is derived from the path, so MOVING a project yields a new
   slug and orphans the old entry (surfaced as `:missing?` by
   `list-projects`); it is not silently re-homed."
  [path]
  (when-let [cp (canonical-path path)]
    (str (sanitize-name (.getName (io/file cp))) "-" (path-hash cp))))

;; ============================================================================
;; Paths
;; ============================================================================

(defn projects-root
  "Absolute path of `~/.brainyard/projects`, or nil when the user dir can't
   be resolved. Does not create the directory.

   Resolved per call from the passed `dirs` map — never cached in a top-level
   `def`, which under native-image would bake the BUILD machine's home dir
   into the binary."
  [dirs]
  (config/brainyard-subdir dirs registry-subdir :user))

(defn project-user-dir
  "Absolute path of this project's user-scope folder
   (`~/.brainyard/projects/<slug>`), or nil when unresolvable. Does not
   create the directory.

   Arity-1 uses `(:project-dir dirs)`; arity-2 takes an explicit path."
  ([dirs] (project-user-dir dirs (:project-dir dirs)))
  ([dirs path]
   (when-let [root (projects-root dirs)]
     (when-let [slug (project-slug path)]
       (str root "/" slug)))))

;; ============================================================================
;; Record I/O
;; ============================================================================

(defn- read-record
  "Read an EDN map from `f`, or nil when absent / unparseable / not a map.
   Tolerant by design: a corrupt record must not break startup, it just gets
   rewritten on the next `register-project!`."
  [^File f]
  (when (.isFile f)
    (try (let [v (edn/read-string (slurp f))]
           (when (map? v) v))
         (catch Exception _ nil))))

(defn- atomic-spit!
  "Write `content` to `target` via a same-directory temp file + move, so a
   concurrent reader never observes a half-written record."
  [^File target ^String content]
  (let [dir (.getParentFile target)
        tmp (File/createTempFile ".by-projects-" ".tmp" dir)]
    (try
      (spit tmp content)
      (Files/move (.toPath tmp) (.toPath target)
                  (into-array CopyOption [StandardCopyOption/REPLACE_EXISTING]))
      (catch Exception e
        (.delete tmp)
        (throw e)))))

(defn- edn-str [m] (with-out-str (pprint/pprint m)))

;; ============================================================================
;; Git remote (read from .git/config — no subprocess)
;; ============================================================================

(defn- git-dir
  "The project's `.git` directory as a `File`, following the `gitdir:`
   pointer when `.git` is a FILE (worktrees and submodules). nil when absent."
  ^File [project-path]
  (let [g (io/file project-path ".git")]
    (cond
      (.isDirectory g) g
      (.isFile g)      (let [line (str/trim (slurp g))]
                         (when (str/starts-with? line "gitdir:")
                           (let [p (str/trim (subs line (count "gitdir:")))
                                 f (io/file p)]
                             (if (.isAbsolute f) f (io/file project-path p)))))
      :else nil)))

(defn- parse-origin-url
  "First `url = …` inside the `[remote \"origin\"]` section of a git config,
   or nil."
  [config-text]
  (loop [[line & more] (str/split-lines config-text)
         in-origin?    false]
    (when line
      (let [t (str/trim line)]
        (cond
          (str/starts-with? t "[")
          (recur more (some? (re-matches #"\[remote\s+\"origin\"\]" t)))

          (and in-origin? (re-find #"^url\s*=" t))
          (some-> (subs t (inc (str/index-of t "="))) str/trim not-empty)

          :else (recur more in-origin?))))))

(defn- read-git-remote
  "Origin URL for the project, read straight from `.git/config`. Deliberately
   parses the file rather than shelling out to `git`: this runs at every
   startup, and a subprocess there costs latency and adds a native-image
   process-spawn dependency for a purely informational field. nil on any
   failure."
  [project-path]
  (try
    (when-let [gd (git-dir project-path)]
      (let [cfg (io/file gd "config")]
        (when (.isFile cfg)
          (parse-origin-url (slurp cfg)))))
    (catch Exception _ nil)))

;; ============================================================================
;; Registry API
;; ============================================================================

(defn- opened-ms
  "`:last-opened-at` as epoch millis for sorting, 0 when absent/unparseable.
   Sorts on the numeric instant, NOT on `(str date)` — `java.util.Date`'s
   string form (`Tue Aug 04 22:19:33 KST 2026`) does not sort chronologically."
  [r]
  (try (or (some-> (:last-opened-at r) inst-ms) 0)
       (catch Exception _ 0)))

(defn list-projects
  "Every registered project, newest `:last-opened-at` first. Each entry is the
   stored `project.edn` map plus `:missing? true` when its `:path` no longer
   names a directory. Returns `[]` when the registry doesn't exist yet."
  [dirs]
  (or (when-let [root (projects-root dirs)]
        (when-let [fs (.listFiles (io/file root))]
          (->> fs
               (filter #(.isDirectory ^File %))
               (keep (fn [^File d] (read-record (io/file d project-file))))
               (map (fn [r]
                      (assoc r :missing?
                             (not (.isDirectory (io/file (str (:path r))))))))
               (sort-by opened-ms >)
               vec)))
      []))

(defn refresh-projects-index!
  "Rebuild `index.edn` from the per-slug `project.edn` files. The index is a
   derived cache — this is the only thing that writes it, and nothing reads it
   as authority. Returns the slug -> path map, or nil when unresolvable."
  [dirs]
  (when-let [root (projects-root dirs)]
    (let [entries (into (sorted-map)
                        (map (juxt :slug :path))
                        (list-projects dirs))]
      (.mkdirs (io/file root))
      (atomic-spit! (io/file root index-file)
                    (edn-str {:schema-version schema-version
                              :projects       entries}))
      entries)))

(defn- delete-record-dir!
  "Delete `<root>/<slug>` and its contents. Returns true when the directory
   was removed.

   Refuses anything that does not resolve to a DIRECT CHILD of `root`. The
   slug is read back off disk rather than computed here, so it is untrusted
   input: a record carrying `..` or an absolute path would otherwise aim a
   recursive delete anywhere the user can write. Compares canonical paths so
   a symlinked slug dir cannot escape either."
  [root slug]
  (boolean
   (try
     ;; Reject anything that is not a BARE directory name before building a
     ;; File from it. `(.getName (io/file s))` strips every separator and
     ;; parent ref, so `..`, `../x` and `/abs/x` all fail this equality —
     ;; without ever constructing the escaping path. Checking first also keeps
     ;; the function total: `io/file` throws on some absolute-child inputs,
     ;; and an exception escaping here would abort the whole prune partway,
     ;; leaving the index describing directories that are already gone.
     (when (and (string? slug)
                (not (str/blank? slug))
                (= slug (.getName (io/file slug))))
       (let [canon-d    (canonical-path (str root "/" slug))
             canon-root (canonical-path root)]
         (when (and canon-d canon-root
                    ;; Defence in depth: canonicalization resolves symlinks,
                    ;; so a slug dir symlinked elsewhere is caught here even
                    ;; though its name is bare.
                    (= canon-root (.getParent (io/file canon-d)))
                    (.isDirectory (io/file canon-d)))
           ;; Depth-first: file-seq yields parents before children, so reverse
           ;; it or the directory is never empty when .delete reaches it.
           (doseq [^File f (reverse (file-seq (io/file canon-d)))]
             (.delete f))
           (not (.exists (io/file canon-d))))))
     (catch Exception e
       (mulog/log ::project-prune-failed :slug slug :error (.getMessage e))
       false))))

(defn prune-projects!
  "Remove registry records whose `:path` no longer names a directory, then
   rebuild the index. Returns the vector of pruned records (as
   `list-projects` reported them); `[]` when there was nothing to reclaim.

   Deliberately manual, and never a side effect of opening a session. An entry
   goes `:missing?` when its directory is deleted OR merely unreachable — an
   unmounted volume, a detached external disk, a network share that is down —
   and those come back. Reclaiming automatically would quietly discard the
   user-scope folder of a project that still exists, which is why
   `list-projects` tags rather than drops them and why this is a command the
   user runs.

   Same reasoning as task artifacts being GC-swept rather than deleted inline
   with the task: removal is bulk, explicit, and separable from the thing that
   created the record."
  [dirs]
  (if-let [root (projects-root dirs)]
    (let [gone (filterv :missing? (list-projects dirs))
          removed (filterv #(delete-record-dir! root (:slug %)) gone)]
      (when (seq removed)
        (refresh-projects-index! dirs)
        (mulog/log ::projects-pruned :count (count removed)
                   :slugs (mapv :slug removed)))
      removed)
    []))

(defn remove-project!
  "Remove ONE registry record by slug, then rebuild the index. Returns the
   record as `list-projects` reported it, or nil when the slug is not
   registered.

   The per-slug counterpart to `prune-projects!`, which can only ever take
   every missing record at once — useful for reclaiming a batch, useless for
   \"drop this one\". Deliberately does NOT require `:missing?`: a repo you have
   stopped working on has not stopped existing, and forgetting it is a
   legitimate thing to ask for.

   Like prune, this removes only the user-scope record dir. The project itself
   is never touched, and re-registering it later restores a record with the
   same path-derived slug."
  [dirs slug]
  (when-let [root (projects-root dirs)]
    (when-let [rec (first (filterv #(= slug (:slug %)) (list-projects dirs)))]
      (when (delete-record-dir! root slug)
        (refresh-projects-index! dirs)
        (mulog/log ::project-removed :slug slug :path (:path rec))
        rec))))

(defn register-project!
  "Register (or refresh) a project's user-scope folder and return its record.

   Idempotent: the slug is path-derived, `:created-at` is preserved across
   calls, and unknown keys written by a future version are merged through
   rather than dropped. Stamps `:last-opened-at`, then refreshes the derived
   index. Returns nil when the registry root can't be resolved.

   Arity-1 registers `(:project-dir dirs)`; arity-2 takes an explicit path."
  ([dirs] (register-project! dirs (:project-dir dirs)))
  ([dirs path]
   (when-let [dir (project-user-dir dirs path)]
     (try
       (let [cp   (canonical-path path)
             d    (io/file dir)
             f    (io/file d project-file)
             prev (read-record f)
             now  (java.util.Date.)
             rec  (merge (or prev {})
                         {:path           cp
                          :slug           (project-slug cp)
                          :name           (.getName (io/file cp))
                          :git-remote     (read-git-remote cp)
                          :created-at     (or (:created-at prev) now)
                          :last-opened-at now
                          :schema-version schema-version})]
         (.mkdirs d)
         (atomic-spit! f (edn-str rec))
         (refresh-projects-index! dirs)
         (mulog/debug ::project-registered
                      :slug (:slug rec) :path cp :new? (nil? prev))
         rec)
       (catch Exception e
         ;; The registry is auxiliary bookkeeping — a failure here (read-only
         ;; home, full disk) must never take down a session.
         (mulog/warn ::project-register-failed
                     :path path :error (.getMessage e))
         nil)))))

(defn ensure-project-registered!
  "Register the project only if it has no record yet; otherwise return the
   existing record untouched (no `:last-opened-at` churn). For consumers that
   need the folder to exist but aren't a session start."
  ([dirs] (ensure-project-registered! dirs (:project-dir dirs)))
  ([dirs path]
   (or (when-let [dir (project-user-dir dirs path)]
         (read-record (io/file dir project-file)))
       (register-project! dirs path))))

(defn project-path-for-slug
  "Reverse a slug to its absolute project path by reading the authoritative
   `<slug>/project.edn`. nil when the slug isn't registered.

   Deliberately does NOT consult `index.edn` — that file is derived from these
   records, so it can never know a path they don't."
  [dirs slug]
  (when-let [root (projects-root dirs)]
    (when-let [slug (some-> slug str str/trim not-empty)]
      (:path (read-record (io/file root slug project-file))))))
