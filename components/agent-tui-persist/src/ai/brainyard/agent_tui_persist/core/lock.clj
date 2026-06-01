;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: Apache-2.0
;; Licensed under the Apache License, Version 2.0 (the "License"); you may
;; not use this file except in compliance with the License. See LICENSE at
;; the repository root and CONTRIBUTING.md for contribution terms.

(ns ai.brainyard.agent-tui-persist.core.lock
  "Per-session PID lockfile so two `by-host` processes cannot compete for the
   same session directory.  Per docs/tmux-based-agent-tui.md R-6."
  (:require [ai.brainyard.agent-tui-persist.core.paths :as paths]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.io File RandomAccessFile]
           [java.lang ProcessHandle]
           [java.nio.channels FileChannel FileLock OverlappingFileLockException]
           [java.nio.file StandardOpenOption]))

(defn- pid []
  (.pid (ProcessHandle/current)))

(defn- alive? [^long candidate-pid]
  (try
    (.isPresent (ProcessHandle/of candidate-pid))
    (catch Throwable _ false)))

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
    (when (or (nil? prior) (= prior (pid)) (not (alive? prior)))
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
