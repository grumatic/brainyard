;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent-tui-persist.core.rotate
  "Seed a NEW, empty session id as the continuation of a live one — the disk
   half of `/clear`.

   This is `archive.clj` run in the other direction, and the direction is the
   whole decision. Archiving moves the CONVERSATION out to a new id and leaves
   the live id in place; rotating leaves the conversation exactly where it is
   and moves the LIVE PROCESS onto a new id. Both end with a cleared screen and
   a recoverable history, but only rotation leaves a session id naming one
   immutable conversation — which is what makes `by --resume <id>` mean
   something stable, and what lets the exit banner name an id that will still
   hold the same transcript tomorrow.

   The cost archiving was written to avoid is real and is paid here: `ask.sock`
   is keyed on the session id, so an attached `by ask -s <old-id>` loses its
   socket at the moment of the clear. The caller rebinds the listener onto the
   new id (see `core/rotate-session-id!`); the old meta keeps a `:rotated-to`
   breadcrumb so a discovery client can say where the live process went rather
   than reporting a dead socket.

   What travels forward is the mirror image of what archiving leaves behind,
   and for the same reasons. `input-history.edn` and `permissions.edn` belong
   to the PERSON, not to the transcript — recalled input is ergonomics and
   remembered approvals are preferences, and a user should not have to re-grant
   an approval because they cleared. They are COPIED rather than moved, because
   the old id has to stay a self-contained, resumable session. Everything else
   — `messages.log`, `session.edn`, `usage-tracker.edn`, the scrollback streams,
   `todo.edn` — stays with the conversation it describes, which is precisely
   what makes the new session empty."
  (:require [ai.brainyard.agent-tui-persist.core.paths :as paths]
            [ai.brainyard.agent-tui-persist.core.snapshots :as snapshots])
  (:import [java.io File]
           [java.nio.file CopyOption Files StandardCopyOption]
           [java.util Date]))

(def carried-tags
  "Snapshot tags copied forward onto the new id. See the namespace docstring:
   these describe the user, not the conversation."
  [:input-history :permissions])

(def identity-keys
  "meta.edn keys that say WHAT KIND of session this is, and so must travel or a
   later `--resume <new-id>` comes back as the wrong agent on the wrong model.
   Deliberately an allow-list rather than a `dissoc` of the known-bad keys: a
   copy-everything would carry `:pid`, `:ask-socket-path` and `:cleared-from`
   into a directory none of them describe, and every meta key added later would
   default to travelling whether or not that is true of it."
  [:agent-id :defagent-id :model :provider :working-dir :user-id
   :acp-backend :acp-backend-opts :label])

(defn- copy-file!
  "Copy `src` to `dst` when `src` exists. Returns the bytes copied, else nil."
  [^File src ^File dst]
  (when (and src (.exists src))
    (Files/copy (.toPath src) (.toPath dst)
                ^"[Ljava.nio.file.CopyOption;"
                (into-array CopyOption [StandardCopyOption/REPLACE_EXISTING]))
    (.length src)))

(defn rotate-session!
  "Create `new-id` as an empty continuation of `old-id` and return
   `{:new-id :rotated-from :carried}`.

   `new-id` must not already hold a session — a collision would drop a fresh
   session on top of somebody else's transcript, so it is refused rather than
   resolved.

   Order is the safety property, and it is the opposite of `archive-session!`'s.
   Nothing is moved and nothing is destroyed, so the only failure that matters
   is a half-identified pair: the new session's `meta.edn` is written FIRST (so
   a crash leaves a directory that is already resumable), the carried files
   follow, and `old-id` is stamped with `:rotated-to` LAST — that stamp is a
   claim about a session that by then definitely exists.

   `opts`:
     :label — label for the NEW session. Defaults to the old session's label,
              since the tab keeps its name across a clear."
  ([old-id new-id] (rotate-session! old-id new-id {}))
  ([old-id new-id {:keys [label]}]
   (when (= (name old-id) (name new-id))
     (throw (ex-info "rotate-session!: new id must differ from the live id"
                     {:old-id (name old-id) :new-id (name new-id)})))
   (when (some #{(name new-id)} (paths/list-sessions))
     (throw (ex-info "rotate-session!: new session id already exists"
                     {:new-id (name new-id)})))
   (let [old-meta (or (snapshots/safe-read-meta old-id) {})
         ;; meta.edn carries two timestamp conventions and both matter here.
         ;; `:created-at`/`:last-active` are `#inst` — `tree/tree-items` SORTS
         ;; on `:created-at`, and a long mixed in among Dates throws rather
         ;; than sorting badly. `:started-at`/`:last-attached-at` are epoch
         ;; millis, read by `eviction/expired?` and by the resume picker's
         ;; ordering. The new session gets both because nothing else will give
         ;; them to it: `:agent.session/created` does not fire on a rotation
         ;; (the session atom is continuing, not being created), so the
         ;; persist-bridge handler that normally stamps them never runs — and
         ;; a session with no `:last-attached-at` sorts last in the picker and
         ;; reads as never-attached to the TTL sweep.
         now-ms   (System/currentTimeMillis)
         now-inst (Date.)]
     (paths/session-dir new-id)
     (snapshots/write-snap! new-id :meta
                            (cond-> (assoc (select-keys old-meta identity-keys)
                                           :id               (name new-id)
                                           :rotated-from     (name old-id)
                                           :created-at       now-inst
                                           :last-active      now-inst
                                           :started-at       now-ms
                                           :last-attached-at now-ms)
                              label (assoc :label label)))
     (let [carried (into []
                         (keep (fn [tag]
                                 (when (copy-file! (paths/file-of old-id tag)
                                                   (paths/file-of new-id tag))
                                   tag)))
                         carried-tags)]
       ;; The breadcrumb is what turns a vanished ask.sock into an answer. A
       ;; discovery client holding the old id can follow it to the process that
       ;; is actually live, instead of reporting the session as dead.
       ;;
       ;; The same write RETIRES the old session's discovery descriptor.
       ;; `:pid`, `:ask-socket-path` and `:ops` describe a live owner, and this
       ;; process has just stopped being one — the socket is about to be closed
       ;; and the lockfile deleted. Every consumer happens to `.exists`-check
       ;; the path first, so the stale values are not load-bearing; they are
       ;; simply the file telling a lie about itself, in exactly the fields
       ;; `by sessions list` exists to report. nil rather than dissoc because
       ;; `save-meta!` MERGES, and every reader treats a nil here as absent.
       ;;
       ;; `:last-attached-at` moves to now for the opposite reason: the session
       ;; you just cleared is the likeliest one you want back, and the resume
       ;; picker orders on it.
       (snapshots/save-meta! old-id {:rotated-to       (name new-id)
                                     :last-attached-at now-ms
                                     :last-active      now-inst
                                     :pid              nil
                                     :ask-socket-path  nil
                                     :ops              nil})
       {:new-id       (name new-id)
        :rotated-from (name old-id)
        :carried      carried}))))
