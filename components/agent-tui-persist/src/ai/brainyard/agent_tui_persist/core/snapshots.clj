;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent-tui-persist.core.snapshots
  "Atomic snapshot files for transient agent state — pending dialogs, queue,
   permissions cache, todo, status, layout, meta.

   Per docs/tmux-based-agent-tui.md §11.3 each snapshot is a single EDN map
   written via `edn-io/atomic-write!`.  The file is the recovery anchor on
   `by-host` startup — if `pending-dialogs.edn` is non-empty, the persisted
   questionnaires are re-emitted to the next attaching `by-ui`."
  (:require [ai.brainyard.agent-tui-persist.core.edn-io :as edn-io]
            [ai.brainyard.agent-tui-persist.core.paths :as paths]
            [clojure.java.io :as io]))

(defn- snap-fn
  "Build a {read,write} pair against the file tagged `kind` (e.g. :meta)."
  [kind]
  {:read  (fn read-snap
            ([session-id] (edn-io/read-edn (paths/file-of session-id kind) nil))
            ([session-id default]
             (edn-io/read-edn (paths/file-of session-id kind) default)))
   :write (fn write-snap [session-id value]
            (edn-io/atomic-write! (paths/file-of session-id kind) value))})

(def ^:private snap-handles
  (into {} (for [kind [:meta :pending-dialogs :permissions :queue :todo :status :layout :session
                       :input-history :usage-tracker]]
             [kind (snap-fn kind)])))

(defn read-snap
  "Read snapshot value for `kind` (one of :meta, :pending-dialogs, :permissions,
   :queue, :todo, :status, :layout, :session, :input-history, :usage-tracker).
   Returns `default` when the file is missing or empty.

   THROWS on an unreadable file.  Anything on a path that must survive one bad
   session — resume, listing, discovery — wants `safe-read-snap` instead."
  ([session-id kind] (read-snap session-id kind nil))
  ([session-id kind default]
   (when-let [{:keys [read]} (get snap-handles kind)]
     (read session-id default))))

(defn- snap-filename
  "The on-disk name behind a snapshot tag.  Warnings quote the FILE, not the
   tag — whoever reads one has to go find it."
  [kind]
  (get paths/filenames kind (name kind)))

(defn- warn-unreadable!
  [session-id kind t]
  (binding [*out* *err*]
    (println (str "[persist] skipping unreadable " (snap-filename kind)
                  " for " session-id ": " (.getMessage ^Throwable t)))))

(defn safe-read-snap
  "Read `kind`'s snapshot but NEVER throw — an unreadable file yields `default`
   with a one-line stderr warning, so the corruption is degraded rather than
   silent.

   A snapshot holds whatever the writer's in-memory value printed as, and not
   every value round-trips: a Java object prints as `#object[…]` and a keyword
   coerced from free text can embed a delimiter, both of which `edn/read`
   rejects on the way back in.  One such file used to abort the ENTIRE resume —
   the caller in the TUI base swallows the throw, so the session came back with
   no history and no scrollback and said nothing about why."
  ([session-id kind] (safe-read-snap session-id kind nil))
  ([session-id kind default]
   (try
     (read-snap session-id kind default)
     (catch Throwable t
       (warn-unreadable! session-id kind t)
       default))))

(defn write-snap!
  "Atomically write `value` as the snapshot for `kind`.  Returns the file."
  [session-id kind value]
  (when-let [{:keys [write]} (get snap-handles kind)]
    (write session-id value)))

(defn update-snap!
  "Read-modify-write a snapshot via `f`.  Not atomic across concurrent updaters
   — caller is responsible for serialisation (typically by holding a per-session
   ReentrantLock)."
  ([session-id kind f]
   (let [v0 (read-snap session-id kind nil)]
     (write-snap! session-id kind (f v0))))
  ([session-id kind f & args]
   (update-snap! session-id kind #(apply f % args))))

;; -- Specialised wrappers for the most common cases ---------------------------

;; -- Identity sanitisation (write side) ---------------------------------------

(def ^:private unreadable-kw-chars
  #"[\s(){}\[\]\"@^`~,;\\]")

(defn- unreadable-keyword?
  "True when `x` is a keyword whose printed form cannot be read back by the EDN
   reader — i.e. its name/namespace embeds whitespace or a delimiter char."
  [x]
  (and (keyword? x)
       (let [s (subs (str x) 1)]
         (or (empty? s)
             (boolean (re-find unreadable-kw-chars s))))))

(defn sanitise-identity
  "Drop `:agent-id` / `:defagent-id` values that would serialise as UNREADABLE
   EDN.  A free-form string coerced to a keyword (e.g. a prompt landing as
   `:reply this: OK`) writes out fine but throws `Invalid token` on the way
   back in, and ONE such file used to break `by sessions list` for the whole
   project.  Dropping the key keeps meta.edn readable; the field is re-derived
   on the next `:agent.instance/created`.  Strings are left alone — they are
   always readable."
  [meta]
  (reduce (fn [m k]
            (if (unreadable-keyword? (get m k))
              (do (binding [*out* *err*]
                    (println (str "[persist] dropping unreadable " k " "
                                  (pr-str (get m k)) " from meta.edn")))
                  (dissoc m k))
              m))
          meta
          [:agent-id :defagent-id]))

(defn- quarantine!
  "Move an unreadable snapshot file aside to `<name>.corrupt` so the next write
   starts from a clean file instead of failing forever, without destroying the
   evidence.  Never overwrites an existing `.corrupt` — the FIRST corruption is
   the interesting one; a later one is usually a consequence.  Best-effort:
   a failed rename just means the caller overwrites in place."
  [session-id kind]
  (try
    (when-let [^java.io.File f (paths/file-of session-id kind)]
      (when (.exists f)
        (let [bak (io/file (.getParentFile f) (str (.getName f) ".corrupt"))]
          (when-not (.exists bak)
            (.renameTo f bak)))))
    (catch Throwable _ nil)))

(defn save-meta!
  "Write or merge into the session's meta.edn (agent-id, started-at, working-
   dir, model, etc.).  Identity keys are sanitised so a bad keyword can never
   render the file unreadable (see `sanitise-identity`).

   Read-modify-write, and the READ is the tolerant one: a meta.edn that will not
   parse is quarantined to `meta.edn.corrupt` and the merge proceeds from `{}`.
   Merging onto nothing loses whatever was in the bad file, but that content was
   already unreadable to every reader in the process — and throwing here aborted
   the resume that was only trying to stamp `:last-attached-at`."
  [session-id meta]
  (let [prev (try
               (read-snap session-id :meta {})
               (catch Throwable t
                 ;; Its own wording, not `warn-unreadable!`'s: this path does
                 ;; not SKIP the file, it moves it aside and writes over the
                 ;; name.  A warning that understated that would send someone
                 ;; looking for content that is no longer where they expect.
                 (binding [*out* *err*]
                   (println (str "[persist] quarantining unreadable "
                                 (snap-filename :meta) " for " session-id
                                 " → " (snap-filename :meta) ".corrupt: "
                                 (.getMessage t))))
                 (quarantine! session-id :meta)
                 {}))]
    (write-snap! session-id :meta
                 (-> (merge prev meta)
                     sanitise-identity
                     (update :started-at #(or % (System/currentTimeMillis)))))))

(defn read-meta
  [session-id]
  (read-snap session-id :meta {}))

(defn safe-read-meta
  "Read a session's meta.edn but NEVER throw — a corrupt or unparseable file
   just yields nil, with a one-line stderr warning so the corruption isn't
   silent.  This is THE reader to use anywhere sessions are iterated: without
   it one bad meta.edn blocks `by sessions list` / `/session list` and
   discover-attach-target for the entire project.

   Note the default differs from `safe-read-snap`: a MISSING meta.edn still
   reads as `{}` (the session exists, we just know nothing about it), and only
   an UNREADABLE one yields nil."
  [session-id]
  (try
    (read-meta session-id)
    (catch Throwable t
      (warn-unreadable! session-id :meta t)
      nil)))

(defn pending-dialogs
  "Return the queue of pending dialog questionnaires, de-duplicated by
   `:id`.  Older daemons appended the same questionnaire on every
   reattach (orchestrator added on every incoming `:popup`, including
   on-attach replays), so existing files may contain dozens of copies
   of the same id.  Returning the FIRST occurrence of each id keeps
   on-attach replay sane without a destructive migration."
  [session-id]
  (let [raw (read-snap session-id :pending-dialogs [])]
    (->> raw
         (reduce (fn [{:keys [seen out]} d]
                   (if (contains? seen (:id d))
                     {:seen seen :out out}
                     {:seen (conj seen (:id d))
                      :out  (conj out d)}))
                 {:seen #{} :out []})
         :out)))

(defn save-pending-dialogs!
  [session-id dialogs]
  (write-snap! session-id :pending-dialogs (vec dialogs)))

(defn add-pending-dialog!
  "Add a questionnaire payload to the pending-dialogs queue, idempotent
   by `:id` — calling twice for the same questionnaire keeps a single
   entry instead of duplicating.  Returns the updated vector."
  [session-id dialog]
  (update-snap! session-id :pending-dialogs
                (fn [prev]
                  (let [prev (or prev [])
                        already? (some #(= (:id %) (:id dialog)) prev)]
                    (if already? prev (conj prev dialog))))))

(defn remove-pending-dialog!
  "Drop the dialog whose `:id` matches `id`.  Returns the updated vector."
  [session-id id]
  (update-snap! session-id :pending-dialogs
                (fnil (fn [xs] (vec (remove #(= (:id %) id) xs))) [])))

(defn read-permissions
  [session-id]
  (read-snap session-id :permissions {}))

(defn save-permissions!
  [session-id permissions]
  (write-snap! session-id :permissions permissions))
