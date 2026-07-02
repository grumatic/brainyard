;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.gc
  "On-disk artifact garbage collection.

   Three classes, three sweeps:

     :tasks          <project>/.brainyard/tasks/task-N/   — count + age
     :coact-scratch  <project>/.brainyard/temp/coact-agent/scratch/ — age (hours)
     :sandbox-cache  <project>/.brainyard/temp/clj-sandbox/{truncation,file-backed}/
                                                          — count + bytes + age

   Each sweep returns {:scanned :deleted :kept :bytes-freed :class :dry-run?}.
   `run-all!` runs every sweep and aggregates per class. Wired to
   `:agent.session/created` so a fresh session triggers cleanup once,
   asynchronously (best-effort — failures are logged).

   Live state is preserved: `sweep-tasks!` skips dirs whose `meta.edn` reports
   a non-terminal `:status`, and the latest-N retention is applied AFTER
   the live skip. Sandbox-cache entries are file-only (no liveness signal)
   so they sort purely by mtime."
  (:require [ai.brainyard.agent.core.config :as config]
            [ai.brainyard.agent.task.persist :as task-persist]
            [ai.brainyard.mulog.interface :as mulog]
            [clojure.edn]
            [clojure.java.io :as io])
  (:import [java.io File]))

;; ============================================================================
;; Generic helpers
;; ============================================================================

(defn- now-ms ^long [] (System/currentTimeMillis))

(defn- delete-tree!
  "Depth-first delete. Returns total bytes freed (best-effort)."
  ^long [^File f]
  (if (and f (.exists f))
    (let [freed (volatile! 0)]
      (when (.isDirectory f)
        (doseq [^File child (.listFiles f)]
          (vswap! freed + (delete-tree! child))))
      (let [len (if (.isFile f) (.length f) 0)]
        (try (when (.delete f) (vswap! freed + len))
             (catch Exception _)))
      @freed)
    0))

(defn- ms->days  [^long ms] (/ (double ms) 86400000.0))
(defn- hours->ms [h] (long (* (or h 0) 3600000)))
(defn- days->ms  [d] (long (* (or d 0) 86400000)))

(defn- empty-result [class dry-run?]
  {:class class :scanned 0 :deleted 0 :kept 0 :bytes-freed 0 :dry-run? (boolean dry-run?)})

;; ============================================================================
;; :tasks sweep
;; ============================================================================

(defn- terminal-meta?
  "Read the task dir's meta.edn and return true when :status is terminal.
   Missing / unreadable / pre-terminal → false (skip from deletion)."
  [^File task-dir]
  (try
    (let [f (io/file task-dir "meta.edn")]
      (if (.exists f)
        (let [m (clojure.edn/read-string (slurp f))]
          (contains? #{:completed :failed :cancelled} (:status m)))
        ;; No meta.edn → dir was created but the writer never closed.
        ;; Treat as non-terminal so we don't race a live appender.
        false))
    (catch Exception _ false)))

(defn sweep-tasks!
  "Delete task-N directories outside the retention window. A terminal task is
   kept only if it is among the newest `:task-retention-count` (default 100)
   AND younger than `:task-retention-days` (default 7) — intersection, not
   union: count is a hard cap on how many are kept, days is a hard expiry
   regardless of count. A terminal task is deleted when it is beyond the newest
   N OR older than D days. Non-terminal (live) tasks are always kept.

   Opts:
     :dry-run?         true to report without deleting
     :retention-count  override config default
     :retention-days   override config default"
  [dirs & {:keys [dry-run? retention-count retention-days]}]
  (let [keep-n (or retention-count (config/get-config :task-retention-count))
        keep-d (or retention-days  (config/get-config :task-retention-days))
        cutoff (- (now-ms) (days->ms keep-d))
        ;; list-task-dirs sorts ASC by mtime; reverse → newest-first.
        all    (vec (reverse (task-persist/list-task-dirs dirs)))
        scanned (count all)
        result (volatile! (assoc (empty-result :tasks dry-run?) :scanned scanned))
        kept   (volatile! 0)
        del    (volatile! 0)
        freed  (volatile! 0)]
    (doseq [[idx {:keys [^File dir mtime] :as entry}] (map-indexed vector all)]
      (let [live?    (not (terminal-meta? dir))
            in-newest? (< idx keep-n)
            fresh?   (>= mtime cutoff)
            ;; Intersection: a terminal task survives only if it is BOTH among
            ;; the newest N AND younger than D days. Count caps the number kept;
            ;; age expires old tasks regardless of count. Live tasks always win.
            keep?    (or live? (and in-newest? fresh?))]
        (if keep?
          (vswap! kept inc)
          (do (vswap! del inc)
              (when-not dry-run?
                (vswap! freed + (delete-tree! dir)))))))
    (-> @result
        (assoc :kept @kept :deleted @del :bytes-freed @freed))))

;; ============================================================================
;; :coact-scratch sweep
;; ============================================================================

(defn- coact-scratch-dir
  "Resolve <project>/.brainyard/temp/coact-agent/scratch/ without creating it."
  ^File [dirs]
  (let [dirs (or dirs (config/init-dirs!))]
    (when-let [pcd (config/project-config-dir dirs)]
      (let [f (io/file pcd "temp" "coact-agent" "scratch")]
        (when (.exists f) f)))))

(defn sweep-coact-scratch!
  "Delete files in coact-agent/scratch/ older than
   `:coact-scratch-max-age-hours` (default 24). Plain file delete — no
   subdirs. Returns {:scanned :deleted :kept :bytes-freed}."
  [dirs & {:keys [dry-run? max-age-hours]}]
  (let [hrs    (or max-age-hours (config/get-config :coact-scratch-max-age-hours))
        cutoff (- (now-ms) (hours->ms hrs))]
    (if-let [^File d (coact-scratch-dir dirs)]
      (let [files (->> (.listFiles d) (filter #(.isFile ^File %)) vec)
            base  (assoc (empty-result :coact-scratch dry-run?) :scanned (count files))
            kept  (volatile! 0)
            del   (volatile! 0)
            freed (volatile! 0)]
        (doseq [^File f files]
          (if (>= (.lastModified f) cutoff)
            (vswap! kept inc)
            (do (vswap! del inc)
                (when-not dry-run?
                  (let [len (.length f)]
                    (try (when (.delete f) (vswap! freed + len))
                         (catch Exception _)))))))
        (assoc base :kept @kept :deleted @del :bytes-freed @freed))
      (empty-result :coact-scratch dry-run?))))

;; ============================================================================
;; :sandbox-cache sweep
;; ============================================================================

(defn- sandbox-cache-roots
  "Return the seq of File roots that hold sandbox-cache artifacts:
   <project>/.brainyard/temp/clj-sandbox/{truncation, file-backed}.
   Missing roots are skipped silently."
  [dirs]
  (let [dirs (or dirs (config/init-dirs!))]
    (when-let [pcd (config/project-config-dir dirs)]
      (->> ["temp/clj-sandbox/truncation" "temp/clj-sandbox/file-backed"]
           (map #(io/file pcd %))
           (filter #(.exists ^File %))))))

(defn- collect-cache-files
  "Walk each root collecting leaf files as {:file :mtime :len}."
  [roots]
  (->> roots
       (mapcat (fn [^File root]
                 (->> (file-seq root)
                      (filter #(.isFile ^File %))
                      (map (fn [^File f]
                             {:file  f
                              :mtime (.lastModified f)
                              :len   (.length f)})))))
       vec))

(defn sweep-sandbox-cache!
  "Trim sandbox-cache artifacts under <project>/.brainyard/temp/clj-sandbox/.
   Drops oldest first when ANY cap is exceeded:
     :sandbox-cache-max-files   (default 200) — total file count
     :sandbox-cache-max-bytes   (default 50 MiB) — total bytes
     :sandbox-cache-max-age-days (default 7) — per-file age cutoff

   Three passes: age cutoff first (cheap, no sort), then file-count cap
   (oldest-first), then byte cap (oldest-first). Order matters — an
   age-expired file is always dropped before count/byte trimming runs.
   Sweeps the truncation and file-backed roots together, not per-root,
   so a hot truncation cache can crowd out stale file-backed entries."
  [dirs & {:keys [dry-run? max-files max-bytes max-age-days]}]
  (let [max-files-n (or max-files     (config/get-config :sandbox-cache-max-files))
        max-bytes-n (or max-bytes     (config/get-config :sandbox-cache-max-bytes))
        max-age-d   (or max-age-days  (config/get-config :sandbox-cache-max-age-days))
        cutoff      (- (now-ms) (days->ms max-age-d))
        roots       (sandbox-cache-roots dirs)
        files       (collect-cache-files roots)
        scanned     (count files)
        drop!       (fn [acc {:keys [^File file len]}]
                      (when-not dry-run?
                        (try (.delete file) (catch Exception _)))
                      (-> acc (update :deleted inc) (update :bytes-freed + len)))
        ;; Pass 1: age.
        {age-survivors :surv :as p1}
        (reduce (fn [acc m]
                  (if (< (:mtime m) cutoff)
                    (-> acc (drop! m))
                    (update acc :surv conj m)))
                {:surv [] :deleted 0 :bytes-freed 0}
                files)
        ;; Pass 2: file count. Drop oldest.
        sorted-by-mtime (sort-by :mtime age-survivors)
        over-count      (max 0 (- (count sorted-by-mtime) max-files-n))
        [count-drops keep1] (split-at over-count sorted-by-mtime)
        p2 (reduce drop! p1 count-drops)
        ;; Pass 3: byte cap. Sum remaining and drop oldest until under.
        total-bytes (reduce + 0 (map :len keep1))
        [byte-drops keep2]
        (loop [drops [] total total-bytes remaining keep1]
          (if (and (> total max-bytes-n) (seq remaining))
            (recur (conj drops (first remaining))
                   (- total (:len (first remaining)))
                   (rest remaining))
            [drops remaining]))
        p3 (reduce drop! p2 byte-drops)]
    (-> (empty-result :sandbox-cache dry-run?)
        (assoc :scanned     scanned
               :deleted     (:deleted p3)
               :bytes-freed (:bytes-freed p3)
               :kept        (count keep2)))))

;; ============================================================================
;; Unified entrypoint
;; ============================================================================

(defn run-all!
  "Run every sweep. Returns a vector of per-class result maps. Each sweep is
   independent — one failure doesn't skip the others. `:dry-run?` propagates."
  [dirs & {:keys [dry-run?]}]
  (let [run (fn [sweep-fn class]
              (try (sweep-fn dirs :dry-run? dry-run?)
                   (catch Exception e
                     (mulog/warn ::sweep-failed :class class :exception e)
                     (assoc (empty-result class dry-run?) :error (.getMessage e)))))]
    [(run sweep-tasks!         :tasks)
     (run sweep-coact-scratch! :coact-scratch)
     (run sweep-sandbox-cache! :sandbox-cache)]))

;; ============================================================================
;; Session-edge hook
;; ============================================================================

;; Throttle: don't re-sweep more than once per hour even if many sessions
;; spin up in quick succession. (TUI bring-up + first ask can fire two
;; :agent.session/created events in <1s.)
(defonce ^:private !last-sweep-ms (atom 0))
(def     ^:private throttle-ms (long (* 60 60 1000)))

(defn- maybe-sweep-async!
  "Run all sweeps in a future, gated by the in-process throttle. Logs the
   aggregate result. Best-effort — exceptions never propagate to the hook
   caller. Skips entirely when the throttle blocks the call."
  []
  (let [now (now-ms)
        prev @!last-sweep-ms]
    (when (and (compare-and-set! !last-sweep-ms prev now)
               (> (- now prev) throttle-ms))
      (future
        (try
          (let [results (run-all! nil)]
            (mulog/log ::session-sweep
                       :results (mapv #(select-keys % [:class :scanned :deleted :kept :bytes-freed])
                                      results)))
          (catch Throwable t
            (mulog/warn ::session-sweep-failed :exception t)))))))

(defonce ^:private !session-hook-registered?
  (delay
    (require '[ai.brainyard.agent.core.hooks :as hooks])
    (let [register! (resolve 'ai.brainyard.agent.core.hooks/register-hook!)]
      (register! :agent.session/created
                 ::gc-session-sweep
                 (fn [_event] (maybe-sweep-async!))
                 :source ::agent-gc))
    true))

@!session-hook-registered?
