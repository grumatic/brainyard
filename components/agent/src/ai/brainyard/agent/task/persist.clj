;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.task.persist
  "Per-task on-disk persistence for stdout/stderr and lifecycle status.

   Layout (project-scoped, like *-agent dossiers):
     <project>/.brainyard/tasks/<task-id>/
       meta.edn    — atomic-rename rewrite at open and on terminal transitions
       output.log  — combined stdout+stderr, one append-line! per line

   The in-memory `:output-lines` ring buffer on the Task record is untouched;
   this namespace adds a parallel disk channel so the LLM can read full status
   at any iteration boundary, even after the ring buffer evicts old lines.

   Retention: dirs are never deleted by the task lifecycle itself — `remove-task`
   leaves them behind for post-mortem inspection. Disk cleanup is the GC layer's
   job (`ai.brainyard.agent.gc/sweep-tasks!` and the `task$sweep` command);
   `manager/remove-task-and-artifacts!` is the opt-in caller helper that does
   both at once. Bounds live in `core.config/config-schema` under
   `:task-retention-count` (default 100) and `:task-retention-days` (default 7)."
  (:require [ai.brainyard.agent.core.config :as config]
            [ai.brainyard.mulog.interface :as mulog]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.io BufferedWriter File FileWriter RandomAccessFile]
           [java.util.concurrent ConcurrentHashMap]))

;; ============================================================================
;; Appender registry
;; ============================================================================

;; ^ConcurrentHashMap hint: without it, .get/.put/.remove dispatch reflectively
;; and GraalVM native-image throws MissingReflectionRegistrationError, killing
;; the reader-future silently via its IOException-only catch.
(defonce ^:private ^ConcurrentHashMap !appenders
  ;; task-id (keyword) -> {:dir File :writer BufferedWriter :lock Object}
  (ConcurrentHashMap.))

(defn- task-id->name [task-id]
  (if (keyword? task-id) (name task-id) (str task-id)))

;; ============================================================================
;; Path resolution
;; ============================================================================

(defn- resolve-dirs [dirs]
  (or dirs (config/init-dirs!)))

(defn task-dir
  "Resolve and ensure `<project>/.brainyard/tasks/<task-id>/`. Returns a File
   pointing at the per-task directory, or nil when the project scope can't be
   resolved."
  ^File [dirs task-id]
  (let [dirs (resolve-dirs dirs)]
    (when-let [base (config/brainyard-subdir! dirs "tasks" :project)]
      (let [d (io/file base (task-id->name task-id))]
        (.mkdirs d)
        d))))

(defn output-path
  "Absolute path string of the per-task combined output log."
  [dirs task-id]
  (when-let [d (task-dir dirs task-id)]
    (.getAbsolutePath (io/file d "output.log"))))

(defn meta-path
  "Absolute path string of the per-task meta.edn (lifecycle snapshot)."
  [dirs task-id]
  (when-let [d (task-dir dirs task-id)]
    (.getAbsolutePath (io/file d "meta.edn"))))

;; ============================================================================
;; meta.edn — atomic rename writes
;; ============================================================================

(defn- edn-safe?
  "Lightweight pre-flight: roundtrip via pr-str + edn/read-string. Records
   and opaque objects (Future, atoms, defrecord instances) won't roundtrip
   because edn/read-string has no record reader."
  [v]
  (try
    (= v (edn/read-string (pr-str v)))
    (catch Exception _ false)))

(defn- sanitize-job-config
  "Strip non-EDN-safe entries from :job-config — for :tool tasks the
   `:agent` key is a defrecord that won't roundtrip via edn/read-string."
  [job-config]
  (when (map? job-config)
    (into {} (filter (fn [[_ v]] (edn-safe? v)) job-config))))

(defn- task->meta
  "Sanitize a Task record into a serializable map. Drops the future-ref and
   the output-lines atom (which would print as a #object<…> form). Also
   strips non-EDN-safe entries from :job-config so meta.edn roundtrips
   through edn/read-string."
  [task]
  (when task
    (let [safe-config (sanitize-job-config (:job-config task))
          result      (:result task)
          safe-result (when (edn-safe? result) result)]
      (cond-> {:id           (:id task)
               :name         (:name task)
               :job-type     (:job-type task)
               :status       (:status task)
               :created-at   (:created-at task)
               :started-at   (:started-at task)
               :completed-at (:completed-at task)}
        (seq safe-config) (assoc :job-config safe-config)
        safe-result       (assoc :result safe-result)
        (some? (get-in task [:result :exit-code]))
        (assoc :exit-code (get-in task [:result :exit-code]))))))

(defn- write-meta!
  "Write meta.edn atomically: spit to .tmp sibling, then rename. Readers
   either see the previous version or the new one, never a partial EDN."
  [^File dir task]
  (try
    (let [tmp (io/file dir "meta.edn.tmp")
          dst (io/file dir "meta.edn")]
      (spit tmp (pr-str (task->meta task)))
      (.renameTo tmp dst))
    (catch Exception e
      (mulog/log ::meta-write-failed :task-id (:id task) :exception e))))

;; ============================================================================
;; Appender lifecycle
;; ============================================================================

(defn open-appender!
  "Create the per-task directory, open a BufferedWriter on output.log
   (truncating any prior content), and write the initial meta.edn snapshot.
   Idempotent for the same task-id — closes a stale writer first.

   Returns the registry entry, or nil if the project scope couldn't be
   resolved (in which case writes silently degrade to no-ops)."
  [dirs task]
  (try
    (when-let [^File d (task-dir dirs (:id task))]
      (let [task-id (:id task)
            log-file (io/file d "output.log")
            writer (BufferedWriter. (FileWriter. log-file false))
            entry {:dir d :writer writer :lock (Object.)}]
        (when-let [old (.put !appenders task-id entry)]
          (try (.close ^BufferedWriter (:writer old)) (catch Exception _)))
        (write-meta! d task)
        entry))
    (catch Exception e
      (mulog/log ::open-appender-failed :task-id (:id task) :exception e)
      nil)))

(defn append-line!
  "Append a single line to the per-task output.log. Thread-safe; per-task lock
   serializes write+flush. Disk errors are logged and swallowed — the
   in-memory ring buffer remains the authoritative source for that line."
  [task-id line]
  (when-let [entry (.get !appenders task-id)]
    (try
      (locking (:lock entry)
        (let [^BufferedWriter w (:writer entry)]
          (.write w (str line))
          (.write w "\n")
          (.flush w)))
      (catch Exception e
        (mulog/log ::append-line-failed :task-id task-id :exception e)))))

(defn close-appender!
  "Flush + close the per-task writer and rewrite meta.edn with terminal
   state. Safe to call even when no appender was opened (e.g. when project
   scope is unavailable)."
  [task-id task]
  (when-let [entry (.remove !appenders task-id)]
    (try
      (locking (:lock entry)
        (.close ^BufferedWriter (:writer entry)))
      (catch Exception e
        (mulog/log ::close-writer-failed :task-id task-id :exception e)))
    (write-meta! (:dir entry) task)))

;; ============================================================================
;; Tail read — cheap last-N for context briefing
;; ============================================================================

(def ^:private tail-buffer-bytes
  ;; Cap the backwards scan to avoid pathological reads on huge logs. 16 KiB
  ;; holds ~200 typical terminal lines.
  16384)

(defn read-tail
  "Return up to last N lines of the per-task output.log as a vector of strings.
   Reads only the tail of the file (bounded by `tail-buffer-bytes`) — safe to
   call from the iteration loop. Returns [] when the file is missing or empty.

   The returned vector is in chronological order (oldest line first).
   Individual lines are NOT char-capped here; callers that render to a budget
   should truncate per line."
  [dirs task-id n]
  (try
    (let [path (output-path dirs task-id)
          f (some-> path io/file)]
      (if (and f (.exists ^File f) (pos? (.length ^File f)))
        (with-open [raf (RandomAccessFile. ^File f "r")]
          (let [len (.length raf)
                from (max 0 (- len tail-buffer-bytes))
                _ (.seek raf from)
                buf (byte-array (- len from))
                _ (.readFully raf buf)
                s (String. buf "UTF-8")
                ;; Drop a leading partial line when we didn't start at byte 0.
                s (if (zero? from) s (subs s (inc (.indexOf s (int \newline)))))
                lines (str/split-lines s)
                lines (if (and (pos? (count lines)) (= "" (peek lines)))
                        (pop lines)
                        lines)]
            (vec (take-last n lines))))
        []))
    (catch Exception e
      (mulog/log ::read-tail-failed :task-id task-id :exception e)
      [])))

(defn line-count
  "Count lines in a task's output.log on disk. Returns 0 when the file is
   absent (e.g. dirs couldn't be resolved or open-appender! silently
   degraded). Streams the file via line-seq — bounded memory even on
   pathologically large logs.

   Note: this reflects what's been *flushed* to disk; for an in-flight task
   the BufferedWriter may not yet have written its last buffered lines.
   Used at terminal-projection time when the appender has been closed via
   `close-appender!` — at that point the on-disk count is authoritative."
  [dirs task-id]
  (try
    (let [path (output-path dirs task-id)
          f    (some-> path io/file)]
      (if (and f (.exists ^File f) (pos? (.length ^File f)))
        (with-open [r (java.io.BufferedReader. (java.io.FileReader. ^File f))]
          (reduce (fn [n _] (inc n)) 0 (line-seq r)))
        0))
    (catch Exception e
      (mulog/log ::line-count-failed :task-id task-id :exception e)
      0)))

(defn read-meta
  "Read the persisted meta.edn for a task. Returns nil when the file is
   missing or unreadable. Useful for post-mortem inspection of a task that
   has been removed from the in-memory registry."
  [dirs task-id]
  (try
    (when-let [path (meta-path dirs task-id)]
      (let [f (io/file path)]
        (when (.exists ^File f)
          (edn/read-string (slurp f)))))
    (catch Exception e
      (mulog/log ::read-meta-failed :task-id task-id :exception e)
      nil)))

(defn max-existing-task-id
  "Scan <project>/.brainyard/tasks/ for `task-N` subdirectories and return
   the largest N seen, or 0 when none exist. Used at TaskManager startup to
   seed the counter so freshly-issued IDs don't collide with — and silently
   truncate — on-disk artifacts from a prior session (or from this JVM's
   prior `shutdown`, which resets the counter to 0)."
  [dirs]
  (try
    (let [dirs (resolve-dirs dirs)]
      (if-let [base (config/brainyard-subdir! dirs "tasks" :project)]
        (let [^File f (io/file base)]
          (if (.exists f)
            (->> (.list f)
                 (keep #(when-let [[_ n] (re-matches #"task-(\d+)" %)]
                          (parse-long n)))
                 (reduce max 0))
            0))
        0))
    (catch Exception e
      (mulog/log ::scan-max-task-id-failed :exception e)
      0)))

;; ============================================================================
;; Disk artifact lifecycle — delete + scan
;; ============================================================================

(defn- delete-recursively!
  "Depth-first delete of a File. Returns the number of leaf entries deleted
   (files + the dir itself). Swallows IOExceptions on individual entries —
   GC is best-effort by design."
  [^File f]
  (when (and f (.exists f))
    (let [n (volatile! 0)]
      (when (.isDirectory f)
        (doseq [^File child (.listFiles f)]
          (vswap! n + (or (delete-recursively! child) 0))))
      (try
        (when (.delete f) (vswap! n inc))
        (catch Exception _))
      @n)))

(defn project-tasks-base
  "Return the <project>/.brainyard/tasks/ File without creating it. Returns
   nil when the project scope can't be resolved. Use for scans / sweeps that
   shouldn't materialize an empty directory."
  ^File [dirs]
  (let [dirs (resolve-dirs dirs)]
    (when-let [pcd (config/project-config-dir dirs)]
      (let [f (io/file pcd "tasks")]
        (when (.exists f) f)))))

(defn delete-task-dir!
  "Remove the per-task directory `<project>/.brainyard/tasks/<task-id>/` and
   all artifacts (output.log, meta.edn, meta.edn.tmp). Closes a stale
   appender first if one is still registered — safe even when the task
   never reached terminal state. Returns `true` when the directory was
   removed, `false` when nothing was there to delete or scope is
   unresolvable. Errors are logged and swallowed."
  [dirs task-id]
  (try
    (let [tid task-id]
      ;; Close any lingering writer before unlinking files.
      (when-let [entry (.remove !appenders tid)]
        (try (locking (:lock entry)
               (.close ^BufferedWriter (:writer entry)))
             (catch Exception _)))
      (if-let [^File d (when-let [base (project-tasks-base dirs)]
                         (let [f (io/file base (task-id->name tid))]
                           (when (.exists f) f)))]
        (do (delete-recursively! d) true)
        false))
    (catch Exception e
      (mulog/log ::delete-task-dir-failed :task-id task-id :exception e)
      false)))

(defn list-task-dirs
  "Return a vector of `{:id :dir :mtime}` for every `task-N` directory under
   `<project>/.brainyard/tasks/`. Sorted by `:mtime` ascending (oldest
   first). Empty when project scope is unresolvable or the dir is missing.

   `:id` is the keyword `:task-N`; `:dir` is the File; `:mtime` is epoch ms.
   Tasks with malformed names are skipped silently."
  [dirs]
  (if-let [^File base (project-tasks-base dirs)]
    (->> (.listFiles base)
         (keep (fn [^File f]
                 (when (.isDirectory f)
                   (when-let [[_ n] (re-matches #"task-(\d+)" (.getName f))]
                     {:id    (keyword (str "task-" n))
                      :dir   f
                      :mtime (.lastModified f)}))))
         (sort-by :mtime)
         vec)
    []))
