;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent-tui-persist.core.archive
  "Move a live session's CONVERSATION into a new session id, leaving the live
   id in place and empty.

   This is what `/clear` runs on. The old `/clear` truncated the scrollback
   streams and deleted `messages.log` outright, so a cleared session's history
   was gone — and a resume months later could not tell that destruction apart
   from corruption.

   Two decisions shape this namespace:

   - **The LIVE id does not change.** `ask.sock` is keyed on the session id, so
     minting a new id for the continuing session would move the socket out from
     under an attached `by ask -s <id>` mid-flight. The archive takes the new
     id; the session the user is sitting in keeps its own. The cost is that a
     session id no longer names one immutable conversation — accepted
     deliberately, because the alternative breaks live attachers.
   - **Files are MOVED, not copied.** A rename is O(1) where a copy is O(bytes),
     and scrollback reaches tens of MiB (5 MiB per stream, ten rotations, two
     streams, and nothing trims it). More importantly the move IS the clear:
     there is never an instant where the conversation has been destroyed but
     not yet saved, which a truncate-then-copy ordering cannot promise.

   What does NOT move is as deliberate as what does. `ask.sock` and
   `by-host.lock` belong to the LIVE PROCESS: copying a lock file carrying a
   live pid would make `held-by-other-live-process?` refuse to resume the
   archive as \"already open in another running by\". `input-history.edn`,
   `todo.edn` and `permissions.edn` stay with the live session too — recalled
   input is ergonomics rather than conversation, and remembered approvals are
   preferences the user should not have to re-grant because they cleared."
  (:require [ai.brainyard.agent-tui-persist.core.paths :as paths]
            [ai.brainyard.agent-tui-persist.core.scrollback :as scrollback]
            [ai.brainyard.agent-tui-persist.core.snapshots :as snapshots])
  (:import [java.io File]
           [java.nio.file Files StandardCopyOption CopyOption]))

(def conversation-tags
  "Snapshot tags whose content IS the conversation, and so travel with it.
   `:meta` is absent on purpose — it is COPIED, not moved, because the live
   session still needs its own identity."
  [:messages :session :usage-tracker])

(def scrollback-tags
  "Scrollback streams to relocate. Each is several files once rotated.
   `:sub-output` is the root's shared sub-output tab — part of this session's
   record, so it travels with it; left out, archiving would strand it in the
   emptied live directory."
  [:stream :activity :sub-output])

(defn- move-file!
  "Relocate `src` to `dst`, preferring an atomic rename. Returns the bytes
   moved, or nil when there was nothing to move.

   Falls back to copy-then-delete: both paths live under the same sessions
   root and so normally the same filesystem, but a root that straddles a mount
   would make `ATOMIC_MOVE` throw, and a failed clear is worse than a
   non-atomic one."
  [^File src ^File dst]
  (when (and src (.exists src))
    (let [n (.length src)]
      (try
        (Files/move (.toPath src) (.toPath dst)
                    (into-array CopyOption [StandardCopyOption/ATOMIC_MOVE
                                            StandardCopyOption/REPLACE_EXISTING]))
        (catch Throwable _
          ;; The array is hinted because `Files/copy` is OVERLOADED three ways
          ;; — (Path, Path, CopyOption…), (InputStream, Path, CopyOption…) and
          ;; (Path, OutputStream) — and `into-array` is statically Object, so
          ;; without the hint the call is left to reflection. That is the exact
          ;; shape that broke v0.5.1: the JVM's Reflector picks an overload from
          ;; the runtime argument, a native image binds from metadata, and the
          ;; two disagree. `Files/move` above needs no hint — it has one
          ;; overload, so there is nothing to pick.
          (Files/copy (.toPath src) (.toPath dst)
                      ^"[Ljava.nio.file.CopyOption;"
                      (into-array CopyOption [StandardCopyOption/REPLACE_EXISTING]))
          (.delete src)))
      n)))

(defn archive-session!
  "Move `live-id`'s conversation into `archive-id`, and return a summary
   `{:archive-id :moved :bytes}`.

   `archive-id` must not already hold a session — a collision would merge two
   conversations into one directory, so it is refused rather than resolved.

   Order matters and is the safety property: the archive's `meta.edn` is
   written FIRST, so a crash midway leaves a directory that is already
   identifiable and resumable rather than an orphan pile of files. Each
   subsequent rename either happened or did not; nothing is destroyed without
   having landed.

   `opts`:
     :label — human label for the archive, surfaced by `by sessions list`.
              Worth setting: the id alone is unmemorable and the message
              naming it scrolls away."
  ([live-id archive-id] (archive-session! live-id archive-id {}))
  ([live-id archive-id {:keys [label]}]
   (when (= (name live-id) (name archive-id))
     (throw (ex-info "archive-session!: archive id must differ from the live id"
                     {:live-id live-id :archive-id archive-id})))
   (when (some #{(name archive-id)} (paths/list-sessions))
     (throw (ex-info "archive-session!: archive id already exists"
                     {:archive-id archive-id})))
   ;; Identity first. Copied rather than moved: the live session keeps its own
   ;; meta.edn, and the archive needs :agent-id / :defagent-id / :model or a
   ;; later `--resume <archive-id>` comes back as the wrong kind of agent.
   ;; :cleared-from is deliberately NOT :parent-id — that edge belongs to
   ;; `tree/fork-session!`, which means something else and is reserved.
   (let [live-meta (or (snapshots/safe-read-meta live-id) {})]
     (paths/session-dir archive-id)
     (snapshots/write-snap! archive-id :meta
                            (cond-> (assoc live-meta
                                           :id (name archive-id)
                                           :cleared-from (name live-id))
                              label (assoc :label label)))
     (let [moved   (volatile! [])
           total   (volatile! 0)
           record! (fn [^File src ^File dst]
                     (when-let [n (move-file! src dst)]
                       (vswap! moved conj (.getName src))
                       (vswap! total + n)))]
       (doseq [tag conversation-tags]
         (record! (paths/file-of live-id tag) (paths/file-of archive-id tag)))
       ;; A stream is several files once rotated; each keeps its own name so
       ;; the archive's rotation ordering survives the move intact.
       (doseq [tag scrollback-tags
               ^File src (scrollback/stream-files live-id tag)]
         (record! src (paths/session-file archive-id (.getName src))))
       {:archive-id (name archive-id)
        :moved      @moved
        :bytes      @total}))))
