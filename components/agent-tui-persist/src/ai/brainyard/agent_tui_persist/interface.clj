;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent-tui-persist.interface
  "Public API for persisted agent TUI sessions.

   Per docs/tmux-based-agent-tui.md §11 — every persistent session lives at
   <project>/.brainyard/sessions/<agent-session-id>/.  This namespace is the
   only entry point exposed to bases; internal namespaces are implementation
   detail. The base wires the project-scoped root once via `set-root-resolver!`
   — `(fn [] (io/file (config/sessions-root)))` — so this dependency-free
   component never has to know about project-dir resolution."
  (:require [ai.brainyard.agent-tui-persist.core.edn-io :as edn-io]
            [ai.brainyard.agent-tui-persist.core.eviction :as eviction]
            [ai.brainyard.agent-tui-persist.core.lock :as lock]
            [ai.brainyard.agent-tui-persist.core.messages :as messages]
            [ai.brainyard.agent-tui-persist.core.paths :as paths]
            [ai.brainyard.agent-tui-persist.core.restore :as restore]
            [ai.brainyard.agent-tui-persist.core.scrollback :as scrollback]
            [ai.brainyard.agent-tui-persist.core.snapshots :as snapshots]
            [ai.brainyard.agent-tui-persist.core.tree :as tree]))

;; -- Paths --------------------------------------------------------------------

(def root-dir              paths/root-dir)
(def set-root-resolver!    paths/set-root-resolver!)
(def session-dir           paths/session-dir)
(def session-file          paths/session-file)
(def file-of               paths/file-of)
(def list-sessions         paths/list-sessions)
(def delete-session-dir!   paths/delete-session-dir!)

(defn with-root*
  "Run `f` with the persistence root pinned to `dir` (a path-like) — used by
   tests. Binds the resolver to a constant thunk."
  [dir f]
  (binding [paths/*root-resolver* (constantly (clojure.java.io/file dir))]
    (f)))

(defmacro with-root
  "Pin the persistence root to `dir` for `body`.  Used by tests."
  [dir & body]
  `(binding [paths/*root-resolver* (constantly (clojure.java.io/file ~dir))]
     ~@body))

;; -- Append-only event log ----------------------------------------------------

(def append-event!  messages/append!)
(def read-events    messages/read-all)
(def read-events-since messages/read-since)
(def last-event     messages/last-event)
(def count-events   messages/count-events)
(def scan-session   messages/scan-log)

;; -- ANSI scrollback streams --------------------------------------------------

(def append-scrollback!     scrollback/append!)
(def read-scrollback        scrollback/read-all)
(def tail-scrollback        scrollback/tail-bytes)
(def truncate-scrollback!   scrollback/truncate!)
(def scrollback-bytes       scrollback/total-bytes)
(def repair-scrollback!     scrollback/repair-concat!)
(def repair-all-scrollbacks! scrollback/repair-all!)

;; -- Snapshots ----------------------------------------------------------------

(def read-snap     snapshots/read-snap)
(def write-snap!   snapshots/write-snap!)
(def update-snap!  snapshots/update-snap!)

(def save-meta!         snapshots/save-meta!)
(def read-meta          snapshots/read-meta)
(def pending-dialogs    snapshots/pending-dialogs)
(def save-pending-dialogs! snapshots/save-pending-dialogs!)
(def add-pending-dialog!   snapshots/add-pending-dialog!)
(def remove-pending-dialog! snapshots/remove-pending-dialog!)
(def read-permissions   snapshots/read-permissions)
(def save-permissions!  snapshots/save-permissions!)

;; -- Session reconstruction (for resume) --------------------------------------

(def restore-session-map restore/restore-session-map)

;; -- Locks --------------------------------------------------------------------

(def try-acquire-lock! lock/try-acquire!)
(def release-lock!     lock/release!)

(defmacro with-session-lock
  "Run `body` while holding the per-session lock.  Throws ex-info on contention."
  [session-id & body]
  `(ai.brainyard.agent-tui-persist.core.lock/with-lock ~session-id ~@body))

;; -- Eviction -----------------------------------------------------------------

(def enforce-size!     eviction/enforce-size!)
(def expired?          eviction/expired?)
(def purge-expired!    eviction/purge-expired!)
(def summarise-sessions eviction/summarise)

;; -- Lower-level EDN helpers (re-exported for ergonomics) ---------------------

(def read-edn       edn-io/read-edn)
(def atomic-write!  edn-io/atomic-write!)
(def append-line!   edn-io/append-line!)
(def read-lines     edn-io/read-lines)

;; -- Session tree (parent / fork / labels) -----------------------------------

(def merge-session-meta!  tree/merge-meta!)
(def touch-session!       tree/touch!)
(def ensure-session-meta! tree/ensure-meta!)
(def fork-session!        tree/fork-session!)
(def session-tree         tree/tree-of)
(def session-tree-items   tree/tree-items)
(def session-lineage      tree/lineage)
(def set-session-label!   tree/set-label!)
(def session-label        tree/get-label)
(def render-session-tree  tree/render-tree)
