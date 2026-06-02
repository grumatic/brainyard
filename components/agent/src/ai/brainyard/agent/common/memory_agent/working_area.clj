;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.common.memory-agent.working-area
  "Filesystem substrate for memory-agent's commentary on the memory DB.

   Layout (canonical):

     .brainyard/agents/memory-agent/<user-id>/
       ├── stats.edn                            ; last-known stats snapshot
       ├── essence.log                          ; append-only NDJSON
       ├── consolidations/<ts>-<slug>.md
       ├── purges/<ts>.edn
       ├── verifications/<ts>-<fact-id>.md
       └── pending/
           ├── verify-queue.edn
           └── consolidate-queue.edn

   This directory is *commentary on* the database, never canonical. If it
   is deleted, no canonical memory is lost — only the agent's audit trail
   of its own moves. The canonical store remains `.brainyard/memory/<user-id>.db`.

   Phase 1 surface: paths, slot read/write of EDN, essence.log NDJSON
   append. File-lock and pending-queue helpers arrive in later phases as
   they become load-bearing."
  (:require [ai.brainyard.agent.core.config :as config]
            [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(defn user-root
  "Absolute path to `<project-dir>/.brainyard/agents/memory-agent/<user-id>/`.
   Anchors at `config/project-dir` (git-root) — same root every other
   functional agent and the LLM tool channel use. Tests inject a temp
   project root via `(with-redefs [config/project-dir (constantly <tmp>)]
   …)` — same pattern every other agent uses."
  [user-id]
  (str (config/project-dir) "/.brainyard/agents/memory-agent/" user-id))

(def ^:private allowed-slots
  "Whitelist of relative slot paths the agent may touch via state-read /
   state-write. Anything outside this set is rejected so an LLM-supplied
   slot string cannot traverse the filesystem.

   Per design §4.1 — `stats.edn` and the two `pending/` queues are the
   only slots used by the memory-agent ops. Per-run artifact files
   (consolidations/<ts>-<slug>.md, purges/<ts>.edn, verifications/<ts>-<fact-id>.md)
   are written through dedicated commands in later phases, not through
   state-write."
  #{"stats.edn"
    "pending/verify-queue.edn"
    "pending/consolidate-queue.edn"})

(defn allowed-slot?
  "True when `slot` is one of the whitelisted relative paths."
  [slot]
  (contains? allowed-slots (str slot)))

(defn slot-file
  "Resolve a slot string to an absolute `java.io.File` under the user's
   working area. Returns nil for slots that fail `allowed-slot?`."
  [user-id slot]
  (when (allowed-slot? slot)
    (io/file (user-root user-id) (str slot))))

(defn- ensure-parent! [^java.io.File f]
  (when-let [p (.getParentFile f)]
    (.mkdirs p)))

(defn read-slot
  "Read a slot's EDN content. Returns nil when the file is absent or
   when `slot` is rejected by `allowed-slot?`."
  [user-id slot]
  (when-let [^java.io.File f (slot-file user-id slot)]
    (when (.isFile f)
      (try (edn/read-string (slurp f))
           (catch Exception _ nil)))))

(defn write-slot!
  "Write `content` (any EDN-printable value) to the slot. Returns the
   absolute path of the file. Rejects unknown slots with an
   `IllegalArgumentException`."
  [user-id slot content]
  (let [^java.io.File f (slot-file user-id slot)]
    (when-not f
      (throw (IllegalArgumentException.
              (str "memory-agent: slot not allowed: " (pr-str slot)))))
    (ensure-parent! f)
    (spit f (pr-str content))
    (.getAbsolutePath f)))

(defn ^java.io.File essence-log-file
  "Absolute file handle for the user's `essence.log`. Does not create
   the file."
  [user-id]
  (io/file (user-root user-id) "essence.log"))

(defn append-essence!
  "Append one NDJSON record `{:turn-id N :agent-id ... :essences [...]}`
   to `essence.log`. Returns the path. Creates the parent directory on
   first call. Each record is one line; consumers can stream-tail the
   file or read it with `line-seq`."
  [user-id record]
  (let [f (essence-log-file user-id)]
    (ensure-parent! f)
    (with-open [w (io/writer f :append true)]
      (.write w ^String (json/write-str record))
      (.write w "\n"))
    (.getAbsolutePath f)))

(defn read-essence-log
  "Return the essence.log records as a vector. Skips blank lines and
   lines that fail JSON parsing (defensive — the file is append-only
   NDJSON but partial writes can happen on host crash)."
  [user-id]
  (let [f (essence-log-file user-id)]
    (if (.isFile f)
      (into []
            (keep (fn [line]
                    (let [s (str/trim line)]
                      (when-not (str/blank? s)
                        (try (json/read-str s :key-fn keyword)
                             (catch Exception _ nil))))))
            (str/split-lines (slurp f)))
      [])))

(defn truthy-slot-list
  "List which whitelisted slots currently exist on disk for `user-id`.
   Cheap — only stats the whitelisted paths. Used by stats helpers."
  [user-id]
  (into #{}
        (filter (fn [slot]
                  (when-let [^java.io.File f (slot-file user-id slot)]
                    (.isFile f))))
        allowed-slots))
