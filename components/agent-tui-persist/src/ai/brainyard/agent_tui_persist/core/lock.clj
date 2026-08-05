;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent-tui-persist.core.lock
  "Per-session PID lockfile so two `by-host` processes cannot compete for the
   same session directory.  Per docs/tmux-based-agent-tui.md R-6."
  (:require [ai.brainyard.agent-tui-persist.core.paths :as paths]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.io File RandomAccessFile]
           [java.lang ProcessHandle ProcessHandle$Info]
           [java.nio.channels FileChannel FileLock OverlappingFileLockException]
           [java.nio.file StandardOpenOption]
           [java.time Instant]
           [java.util Optional]))

(defn- pid []
  (.pid (ProcessHandle/current)))

(defn- info-of
  "`ProcessHandle$Info` for `candidate-pid`, or nil when no such process."
  ^ProcessHandle$Info [^long candidate-pid]
  (try
    ;; Every Optional here is hinted: an unhinted .orElse is reflective, which
    ;; `bb reflect:check` fails the build over (and native-image would break on).
    (let [^Optional opt (ProcessHandle/of candidate-pid)]
      (when-let [^ProcessHandle ph (.orElse opt nil)]
        (.info ph)))
    (catch Throwable _ nil)))

(defn- current-user
  "This process's OS user, or nil when the JVM won't report it."
  []
  (try
    (let [^ProcessHandle$Info info (.info (ProcessHandle/current))
          ^Optional u              (.user info)]
      (.orElse u nil))
    (catch Throwable _ nil)))

(defn- started-after?
  "True when the process now holding a pid demonstrably started AFTER `lockfile`
   was written — i.e. it cannot be the process that wrote it.

   `lockfile`'s mtime is the moment the owner claimed the lock, and an owner
   always starts before it claims. So `start > mtime` is positive proof of pid
   reuse. Returns false whenever either timestamp is unavailable."
  [^ProcessHandle$Info info ^File lockfile]
  (let [^Optional so (.startInstant info)
        ^Instant start (.orElse so nil)
        m              (.lastModified lockfile)]
    (boolean
     (when (and start (pos? m))
       ;; A second of slack: mtime and process start come from different clocks
       ;; and filesystems vary in timestamp granularity.
       (> (.toEpochMilli start) (+ m 1000))))))

(defn- owner-alive?
  "True when `candidate-pid` names a live process that can still plausibly be the
   owner recorded in `lockfile`.

   A bare `ProcessHandle/of` presence check is NOT enough: a pid is not a durable
   identity. When an owner dies the OS is free to reissue its pid, and every
   session whose lockfile names that pid then reads as live again — observed in
   the field as seven sessions still 'live' on a pid that had been reissued to a
   root-owned system daemon. So we also require that the process could actually
   be the one that wrote the lock: same OS user, and not started after the
   lockfile was written.

   Deliberately conservative — it answers false only on POSITIVE evidence of
   reuse. When the JVM cannot report the user or the start time we say `true`,
   because the dangerous error is the other way: `try-acquire!` steals a lock it
   believes dead, so a false 'dead' would let two processes own one session,
   whereas a false 'alive' merely leaves a stale lock to be cleaned up later."
  [^long candidate-pid ^File lockfile]
  ;; Hinted at the binding: `if-let` does not carry `info-of`'s return tag
  ;; through, so `.user` below would resolve against Object and be reflective.
  (if-let [^ProcessHandle$Info info (info-of candidate-pid)]
    (let [^Optional ou (.user info)
          owner        (.orElse ou nil)
          me           (current-user)]
      (cond
        ;; Someone else's process wearing our old pid.
        (and owner me (not= owner me))  false
        (started-after? info lockfile)  false
        :else                           true))
    false))

(defn- read-pid
  [^File f]
  (when (.exists f)
    (let [s (str/trim (slurp f))]
      (when (re-matches #"\d+" s) (Long/parseLong s)))))

(defn try-acquire!
  "Try to claim the lockfile for `session-id`.  Returns a lock handle map
   (containing the underlying `FileLock` and `RandomAccessFile` for release)
   on success.  Returns nil and leaves the file untouched if another live
   process already owns the lock.

   The lockfile contains the owning PID; stale locks left behind by a crashed
   `by-host` are detected and overwritten."
  [session-id]
  (let [^File f (paths/file-of session-id :lock)
        _      (when-let [^File parent (.getParentFile f)]
                 (when-not (.exists parent) (.mkdirs parent)))
        prior  (read-pid f)]
    (when (or (nil? prior) (= prior (pid)) (not (owner-alive? prior f)))
      (let [raf (RandomAccessFile. f "rw")
            ch  (.getChannel raf)
            lock (try
                   (.tryLock ch)
                   (catch OverlappingFileLockException _ nil))]
        (if (nil? lock)
          (do (.close raf) nil)
          (do (.setLength raf 0)
              (.writeBytes raf (str (pid) "\n"))
              {:file f
               :raf  raf
               :channel ch
               :lock lock
               :pid (pid)}))))))

(defn owner-pid
  "Return the PID recorded in `session-id`'s lockfile, or nil when no lockfile
   exists / it is unreadable. Pure read — does NOT acquire or modify the lock,
   so it is safe for a pre-flight liveness probe."
  [session-id]
  (read-pid (paths/file-of session-id :lock)))

(defn held-by-other-live-process?
  "True when `session-id`'s lockfile names a PID that is both (a) not this
   process and (b) currently alive. Read-only — the basis for refusing to open
   a session another running `by` already owns. A stale lock (dead PID) or no
   lockfile yields false."
  [session-id]
  (boolean
   (when-let [p (owner-pid session-id)]
     (and (not= p (pid))
          (owner-alive? p (paths/file-of session-id :lock))))))

(defn session-live?
  "True when `session-id`'s lockfile names a currently-alive PID — i.e. some
   `by` process owns it right now, whether or not that's this process. Read-only.
   The basis for `by sessions list` liveness: a clean exit unlinks the lockfile
   (→ false), a crash leaves a stale lockfile whose dead PID also reads false —
   as does a lockfile whose PID has since been REUSED by an unrelated process
   (see `owner-alive?`)."
  [session-id]
  (boolean
   (when-let [p (owner-pid session-id)]
     (owner-alive? p (paths/file-of session-id :lock)))))

(defn release!
  "Release a lock handle returned by `try-acquire!`."
  [{:keys [^FileLock lock ^RandomAccessFile raf ^File file]}]
  (when lock (try (.release lock) (catch Throwable _)))
  (when raf (try (.close raf) (catch Throwable _)))
  (when file (try (.delete file) (catch Throwable _))))

(defmacro with-lock
  "Run `body` while holding the lock for `session-id`.  Throws ex-info on
   contention."
  [session-id & body]
  `(let [handle# (try-acquire! ~session-id)]
     (when-not handle#
       (throw (ex-info "Session is locked by another process"
                       {:session-id ~session-id})))
     (try
       ~@body
       (finally (release! handle#)))))
