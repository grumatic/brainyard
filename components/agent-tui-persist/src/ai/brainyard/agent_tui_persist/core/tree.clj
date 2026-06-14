;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent-tui-persist.core.tree
  "Session tree: per-session metadata that links a child to its parent
   (and the event index in the parent at which the branch diverged),
   plus utilities to walk and render the tree.

   Per docs/tmux-based-agent-tui.md §11.3 (\"Persistence layout\"), every
   session has its own directory under `<project>/.brainyard/sessions/<id>/`.
   This namespace owns just the `meta.edn` shape:

     {:id          \"<session-id>\"
      :parent-id   \"<parent-session-id>\" ; nil for root sessions
      :fork-point  N                       ; index into parent's
                                           ; messages.log; events 0..N-1
                                           ; are inherited by the child.
                                           ; nil for root sessions.
      :label       \"free-text label\"     ; optional, user-set
      :created-at  #inst \"...\"
      :last-active #inst \"...\"}

   The on-disk layout is unchanged — `messages.log` is still the
   append-only event log; `meta.edn` is just one more well-known file
   in the session directory.

   API:

     (read-meta sid)            ; → meta map or nil
     (merge-meta! sid patch)    ; read-merge-write (preserves snapshot fields)
     (touch! sid)               ; bump :last-active
     (set-label! sid label)
     (get-label sid)

     (fork-session! parent-id new-id opts)  ; opts: {:at int :label str}
     (tree-of)                              ; whole-tree from disk
     (lineage sid)                          ; root → … → sid path
     (render-tree tree opts)                ; ASCII vec of lines"
  (:require [ai.brainyard.agent-tui-persist.core.edn-io :as edn-io]
            [ai.brainyard.agent-tui-persist.core.messages :as messages]
            [ai.brainyard.agent-tui-persist.core.paths :as paths]
            [clojure.string :as str])
  (:import [java.io File]
           [java.util Date]))

;; ============================================================================
;; meta.edn read/write
;; ============================================================================

(defn read-meta
  "Return the meta map for `session-id`, or nil if the session directory
   has no `meta.edn`. The same file is read/written by
   `agent-tui-persist.core.snapshots/save-meta!` (which records
   :agent-id / :started-at / :working-dir / :model). Tree fields and
   snapshot fields share the file via merge."
  [session-id]
  (edn-io/read-edn (paths/file-of session-id :meta)))

(defn merge-meta!
  "Read `<session-id>/meta.edn`, merge `patch` on top, atomically write.
   Use this — never `atomic-write!` directly — so snapshot-side fields
   (:agent-id, :started-at, …) survive tree-side updates."
  [session-id patch]
  (let [prev (or (read-meta session-id) {})]
    (edn-io/atomic-write! (paths/file-of session-id :meta)
                          (merge prev patch))))

(defn- now-inst
  "Use java.util.Date (printed as `#inst`) so meta.edn round-trips
   through `edn-io/read-edn` without a custom data reader."
  ^Date []
  (Date.))

(defn touch!
  "Bump `:last-active` on the session's meta. Creates a minimal meta if
   none exists. Returns the merged map."
  [session-id]
  (let [prev (read-meta session-id)
        patch (cond-> {:id (or (:id prev) (name session-id))
                       :last-active (now-inst)}
                (nil? (:created-at prev)) (assoc :created-at (now-inst)))]
    (merge-meta! session-id patch)
    (merge prev patch)))

(defn ensure-meta!
  "Create a minimal meta.edn for a root session (no parent). Idempotent —
   if meta already exists, only touches `:last-active`. `label` is
   optional. Returns the merged map."
  ([session-id] (ensure-meta! session-id nil))
  ([session-id label]
   (let [prev  (read-meta session-id)
         patch (cond-> {:id (or (:id prev) (name session-id))
                        :last-active (now-inst)}
                 (nil? (:created-at prev)) (assoc :created-at (now-inst))
                 label (assoc :label label))]
     (merge-meta! session-id patch)
     (merge prev patch))))

(defn set-label!
  "Set the human-friendly label on `session-id`'s meta."
  [session-id label]
  (merge-meta! session-id {:label label :last-active (now-inst)}))

(defn get-label
  "Return the label, or nil."
  [session-id]
  (:label (read-meta session-id)))

;; ============================================================================
;; Forking
;; ============================================================================

(defn fork-session!
  "Create a new session `new-id` whose meta marks it as a child of
   `parent-id`. The fork point is `:at` (default: the parent's current
   event count). `:label` is optional. Returns the new child's meta.

   The child's `messages.log` is NOT pre-populated — readers reconstruct
   the inherited prefix by reading `parent-id`'s log up to `fork-point`
   and then continuing with the child's own appends. This keeps fork
   cheap (no copy) and lets multiple children share the parent's
   immutable history."
  [parent-id new-id {:keys [at label]}]
  (when (= (name parent-id) (name new-id))
    (throw (ex-info "fork-session!: child id must differ from parent"
                    {:parent-id parent-id :new-id new-id})))
  (let [fork-at (or at (messages/count-events parent-id))
        m {:id          (name new-id)
           :parent-id   (name parent-id)
           :fork-point  fork-at
           :label       label
           :created-at  (now-inst)
           :last-active (now-inst)}]
    ;; Realise the directory before write — paths/session-dir creates on demand.
    (paths/session-dir new-id)
    ;; A fresh session has no prior meta to merge; merge-meta! handles
    ;; the empty-prev case correctly (merge {} m == m).
    (merge-meta! new-id m)
    ;; Bump parent so most-recent ordering reflects the fork moment.
    (touch! parent-id)
    m))

;; ============================================================================
;; Tree
;; ============================================================================

(defn- read-all-meta
  "Walk every session under the root, returning {session-id meta-map}.
   Sessions without a meta.edn are still included with a stub map so
   they show up in the tree (callers can decide how to render them)."
  []
  (into {}
        (for [sid (paths/list-sessions)]
          ;; meta.edn written via snapshots/save-meta! (the common path) has no
          ;; :id — only ensure-meta!/touch!/fork! set it. Default :id to the sid
          ;; so the rendered tree/lineage always shows a session identifier.
          [sid (let [m (read-meta sid)]
                 (if m
                   (update m :id #(or % sid))
                   {:id sid :created-at nil :last-active nil}))])))

(defn tree-of
  "Build a full session tree from disk.

   Returns:

     {:roots    [<session-id> ...]
      :nodes    {session-id {:meta {...}
                             :parent <session-id or nil>
                             :children [<session-id> ...]}}}

   Roots are sessions whose `:parent-id` is nil OR points to a session
   that no longer exists on disk (orphan recovery — orphans become
   pseudo-roots so the tree stays connected). Children are sorted by
   `:created-at` ascending (oldest first), so left-to-right walks track
   the order in which forks happened."
  []
  (let [metas    (read-all-meta)
        sids     (set (keys metas))
        ;; First pass — adjacency.
        children (reduce
                  (fn [acc [sid m]]
                    (let [pid (when-let [p (:parent-id m)] (when (sids p) p))]
                      (update acc pid (fnil conj []) sid)))
                  {} metas)
        ;; Sort children by created-at (nil last).
        sort-fn  (fn [child-sids]
                   (vec (sort-by (fn [s] (or (:created-at (metas s))
                                             #inst "9999-01-01"))
                                 child-sids)))
        nodes    (reduce
                  (fn [acc [sid m]]
                    (let [pid (when-let [p (:parent-id m)] (when (sids p) p))
                          ch  (sort-fn (or (get children sid) []))]
                      (assoc acc sid
                             {:meta     m
                              :parent   pid
                              :children ch})))
                  {} metas)
        roots    (sort-fn (or (get children nil) []))]
    {:roots roots :nodes nodes}))

(defn lineage
  "Return the path from root to `session-id` as a vec of session-ids,
   reading meta from disk. Stops at an unknown parent (orphan recovery
   — the chain still terminates). Returns nil when `session-id` itself
   has no meta.edn."
  [session-id]
  (when (read-meta session-id)
    (loop [acc (list (name session-id))
           sid (name session-id)]
      (let [pid (some-> (read-meta sid) :parent-id)]
        (if (and pid (read-meta pid))
          (recur (conj acc pid) pid)
          (vec acc))))))

;; ============================================================================
;; ASCII tree rendering
;; ============================================================================

(defn- format-meta-line
  [{:keys [id label last-active]} active?]
  (let [marker  (if active? "▸" " ")
        lbl     (if (and label (not (str/blank? label)))
                  (str " — " label)
                  "")
        when    (when last-active
                  (str "  " (str last-active)))]
    (str marker " " id lbl when)))

;; ============================================================================
;; Picker items (data-only — UI rendering lives in `agent-tui-ui.resume-picker`)
;; ============================================================================

(defn tree-items
  "Walk `tree` (the value returned by `tree-of`) into a flat vec of
   selectable items: `[{:id :label} ...]`. Items are emitted in DFS
   pre-order so parents precede children. Use this in contexts that
   need to ship the picker rows without pulling the UI / ANSI deps —
   e.g. the daemon emitting an `:open-picker` frame to by-ui."
  [{:keys [roots nodes]}]
  (let [out (transient [])]
    (letfn [(walk [sid]
              (let [{:keys [meta children]} (get nodes sid)]
                (conj! out {:id (or (:id meta) sid)
                            :label (:label meta)})
                (doseq [c children] (walk c))))]
      (doseq [r roots] (walk r))
      (persistent! out))))

(defn render-tree
  "Pretty-print the tree as a vec of lines. `opts` recognises:
     :active   — id of the currently-attached session (gets a ▸ marker)
     :ascii?   — use plain ASCII chars instead of box-drawing (default
                 false — uses └─ ├─ │ characters)."
  ([tree] (render-tree tree {}))
  ([{:keys [roots nodes]} {:keys [active ascii?]}]
   (let [glyph (if ascii?
                 {:branch "+- " :last "`- " :stem "|  " :gap "   "}
                 {:branch "├─ " :last "└─ " :stem "│  " :gap "   "})
         out   (transient [])]
     (letfn [(walk [sid prefix top? last?]
               (let [{:keys [meta children]} (get nodes sid)
                     row-prefix (cond
                                  top?  ""
                                  last? (str prefix (:last glyph))
                                  :else (str prefix (:branch glyph)))
                     line (str row-prefix
                               (format-meta-line meta (= sid active)))]
                 (conj! out line)
                 (let [ch-prefix (cond
                                   top? (:gap glyph)
                                   last? (str prefix (:gap glyph))
                                   :else (str prefix (:stem glyph)))
                       ch       (vec children)
                       n        (count ch)]
                   (doseq [[i child-sid] (map-indexed vector ch)]
                     (walk child-sid ch-prefix false (= i (dec n)))))))]
       (let [n (count roots)]
         (doseq [[i root-sid] (map-indexed vector roots)]
           (walk root-sid "" true (= i (dec n)))))
       (persistent! out)))))
