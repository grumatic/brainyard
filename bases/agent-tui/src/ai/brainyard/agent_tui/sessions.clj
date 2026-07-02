;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent-tui.sessions
  "Multi-session manager for the TUI. Each session has its own agent, scrollback,
   watches, and state. Sits above session.clj (per-session state) and layout.clj
   (shared terminal rendering). Only the active session renders to the terminal."
  (:require [ai.brainyard.agent-tui.layout :as layout]
            [ai.brainyard.agent-tui.persist-bridge :as persist-bridge]
            [ai.brainyard.agent.interface :as agent]
            [ai.brainyard.agent.interface.tui.ansi :as ansi]
            [ai.brainyard.agent.interface.tui.format :as fmt]
            [ai.brainyard.clj-llm.interface :as clj-llm]
            [clojure.string :as str]))

;; ============================================================================
;; State
;; ============================================================================

(defonce !sessions
  (atom {:active-idx 0
         :next-id    1
         :sessions   (sorted-map)}))

;; Monotonic counter for root (chat) tab labels. Each root agent gets a
;; label `mainN` where N is the value of this counter at creation time.
;; This is process-local and reset on `reset-sessions!`. The counter is
;; independent of session id (which can have gaps from closes) so labels
;; stay stable and human-readable: main0, main1, main2, …
(defonce ^:private !root-tab-counter (atom 0))

(defn next-root-tab-label!
  "Return the next UNUSED `mainN` label and advance the counter past it. Used
   at every root-tab creation site (core.clj session 0, /session new in
   commands.clj, Ctrl-T in autocomplete.clj) so each live root tab — and the
   sub-output tabs that inherit the root's label + `↓` — stays distinguishable.

   Skips any `mainN` already held by a live session, so labels stay unique even
   when the counter trails the live set (e.g. a resumed session carries a
   persisted `mainN` while the process-local counter restarted at 0)."
  []
  (let [in-use (into #{} (map :label) (vals (:sessions @!sessions)))]
    (loop []
      (let [n     @!root-tab-counter
            label (str "main" n)]
        (swap! !root-tab-counter inc)
        (if (contains? in-use label) (recur) label)))))

;; Guards against race between emit-to-session! (checking active-idx + writing)
;; and switch-to!/close-session! (swapping scrollback + changing active-idx).
;; Both acquire this lock so they can't interleave.
(def switch-lock (Object.))

;; Before-close hooks. Each is a `(fn [idx session]) -> nil` invoked right
;; before a session is removed (and before its agents are closed). Used by
;; session.clj to cascade-close the root's shared sub-output tab and clear
;; the !root-output-sessions registry. Hooks must be quick and must not
;; re-enter close-session! on the SAME idx (re-entry on a different idx —
;; typical cascade target — is fine).
;; Map shape: {key fn} — registration is idempotent on `key`.
(defonce !before-close-hooks (atom {}))

(defn register-before-close-hook!
  "Register `(fn [idx session])` to run before a session is removed.
   Idempotent on `key`: a second call with the same key replaces the
   previous fn (handy for namespace reloads)."
  [key f]
  (swap! !before-close-hooks assoc key f))

;; ============================================================================
;; Tab-strip redraw callbacks. session.clj installs the actual builder
;; here so sessions.clj doesn't need to know how the strip is formatted.
;; ============================================================================

(defonce ^:private !tab-strip-builder (atom nil))

(defn install-tab-strip-builder!
  "Install `(fn []) -> ansi-styled-string` that produces the current tab
   strip text. Called whenever the session list mutates."
  [f]
  (reset! !tab-strip-builder f))

(defn redraw-tab-strip!
  "Rebuild and paint the tab strip from the current session list. No-op
   when no builder is installed or when not in fullscreen mode."
  []
  (when-let [f @!tab-strip-builder]
    (try
      (layout/draw-tab-strip! (f))
      (catch Throwable _))))

;; ============================================================================
;; Helpers
;; ============================================================================

(def ^:private label-max-chars 10)

(defn short-label
  "Produce a short, informative tab label from a defagent-id keyword.
   Strips the conventional `-agent` suffix, truncates at `label-max-chars`,
   appends `suffix` (e.g. \"↓\" for shared sub-output tabs). Returns nil for
   nil input — caller can fall back to its own default."
  ([defagent-id] (short-label defagent-id nil))
  ([defagent-id suffix]
   (when defagent-id
     (let [base (-> (name defagent-id)
                    (str/replace #"-agent$" ""))
           truncated (if (> (count base) label-max-chars)
                       (str (subs base 0 (dec label-max-chars)) "…")
                       base)]
       (cond-> truncated suffix (str suffix))))))

(defn- make-session
  "Create a new session map with default values.

   `:agent-id` (kw) is the per-instance id (e.g. :coact-agent/lavender-koala-244)
   used for in-memory dispatch. `:agent-session-id` (string) is the agent's
   stable session id (e.g. \"agt-1700000000-1234\") that keys the on-disk
   directory at `<project>/.brainyard/sessions/<agent-session-id>/`. Per design doc
   §6.3 the persistence layer is keyed on the latter; the TUI's `:id` is
   process-local and never used as a primary key on disk."
  [id opts]
  (let [label (or (:label opts)
                  (short-label (:agent-id opts))
                  (str "session-" id))]
    (merge {:id               id
            :label            label
            :agent            nil
            :agent-id         nil
            :defagent-id      nil
            :agent-session-id nil
            :scrollback       []
            :watches          []
            :st-memory-atom   nil
            :sub-trackers     []
            :max-iterations   20
            :started-at       nil
            :agent-instances  []
            :session-type     :chat      ;; :chat (interactive) or :output (read-only)
            :sub-output-of    nil        ;; chat-session id when this is a shared sub-output tab
            :status           :idle
            :has-unread?      false
            :viewport-offset  0
            :live-blocks      {}
            :queue-count      0}
           (assoc opts :label label))))

;; ============================================================================
;; Read Accessors
;; ============================================================================

(defn active-idx
  "Return the index of the currently active session."
  []
  (:active-idx @!sessions))

(defn session-count
  "Return the number of sessions."
  []
  (count (:sessions @!sessions)))

(defn get-session
  "Get session map by index. Returns nil if not found."
  [idx]
  (get-in @!sessions [:sessions idx]))

(defn get-active-session
  "Get the currently active session map."
  []
  (get-session (active-idx)))

(defn session-list
  "Return a seq of all session maps, ordered by index."
  []
  (vals (:sessions @!sessions)))

(defn session-indices
  "Return sorted seq of all session indices."
  []
  (keys (:sessions @!sessions)))

;; ============================================================================
;; Mutation Helpers
;; ============================================================================

(defn update-session!
  "Update a session by index using a function. Thread-safe via swap!."
  [idx f & args]
  (apply swap! !sessions update-in [:sessions idx] f args))

;; ============================================================================
;; Layout State Save/Load (used by close-session! and switch-to!)
;; ============================================================================

(defn- save-current-session-state!
  "Save the current layout state (scrollback, viewport, live-blocks) into the active session map."
  []
  (let [current-idx (:active-idx @!sessions)]
    (when (get-session current-idx)
      (let [current-scrollback @layout/!scrollback
            current-viewport (:viewport-offset @layout/!layout)
            current-live-blocks @layout/!live-blocks]
        (swap! !sessions update-in [:sessions current-idx] merge
               {:scrollback      current-scrollback
                :viewport-offset current-viewport
                :live-blocks     current-live-blocks})))))

(defn- load-session-into-layout!
  "Load a session's state into the layout for display. Redraws in fullscreen mode."
  [session]
  ;; Load scrollback
  (reset! layout/!scrollback (or (:scrollback session) []))
  ;; Restore live-blocks (start-idx values are valid for this session's scrollback)
  (reset! layout/!live-blocks (or (:live-blocks session) {}))
  ;; Restore viewport offset
  (swap! layout/!layout assoc :viewport-offset (or (:viewport-offset session) 0))
  ;; Clear unread flag
  (swap! !sessions assoc-in [:sessions (:id session) :has-unread?] false)
  ;; Redraw terminal
  (when (layout/fullscreen?)
    (layout/render-viewport!)
    (layout/redraw-chrome!)))

;; ============================================================================
;; Session Lifecycle
;; ============================================================================

(defn create-session!
  "Create a new session with the given options. Returns the session index.
   If :id is provided in opts, uses that as the index; otherwise auto-increments.
   If no :agent is provided but :agent-id is given (or defaults to :coact-agent),
   automatically creates a fresh agent for the session.
   The :agent-id in opts is the defagent type keyword; the actual instance-id
   is generated by create-tui-agent! and stored as :agent-id in the session."
  [opts]
  (let [state @!sessions
        id (or (:id opts) (:next-id state))
        ;; Auto-create agent if not provided
        defagent-id (or (:agent-id opts) :coact-agent)
        agent-instance (or (:agent opts)
                           ;; Only auto-create if create-tui-agent! is available
                           ;; (not during initial start! which creates its own agent)
                           (when-not (:skip-agent-creation opts)
                             (when-let [create-fn (requiring-resolve 'ai.brainyard.agent-tui.core/create-tui-agent!)]
                               (create-fn defagent-id
                                          :session-id (agent/generate-session-id "agt")
                                          :max-iterations (:max-iterations opts)))))
        ;; Use the agent's actual instance-id if available
        instance-id (if agent-instance
                      (:agent-id agent-instance)
                      defagent-id)
        ;; Build agent-instances from :agent-instances opt or the single agent
        agent-instances (or (:agent-instances opts)
                            (if agent-instance [agent-instance] []))
        ;; Agent's stable session id — what persistence keys the on-disk
        ;; dir on. Read via the protocol accessor so it works regardless of
        ;; whether the agent record exposes it as a field.
        agent-session-id (when agent-instance
                           (try (agent/session-id agent-instance)
                                (catch Throwable _ nil)))
        session (make-session id (-> (dissoc opts :id :skip-agent-creation :agent-instances)
                                     (assoc :agent agent-instance
                                            :agent-id instance-id
                                            :defagent-id defagent-id
                                            :agent-session-id agent-session-id
                                            :agent-instances agent-instances)))
        next-id (if (:id opts)
                  (max (:next-id state) (inc id))
                  (inc id))]
    (swap! !sessions (fn [s]
                       (-> s
                           (assoc-in [:sessions id] session)
                           (assoc :next-id next-id))))
    ;; Persist TUI-session fields (label, defagent-id) keyed by the agent's
    ;; on-disk session id so they survive a restart and can be restored on
    ;; resume. Safe to call with nil agent-session-id (no-op).
    (persist-bridge/save-tui-session-meta!
     agent-session-id
     {:label (:label session) :defagent-id defagent-id})
    ;; Auto-attach watches if agent was created
    (when (and agent-instance (not (:agent opts)))
      (when-let [set-agent-fn (requiring-resolve 'ai.brainyard.agent-tui.session/set-agent!)]
        (set-agent-fn agent-instance instance-id
                      :session-idx id)))
    (redraw-tab-strip!)
    id))

(defn rename-session!
  "Set the active session's label and persist it. Used by `/session rename`."
  ([new-label] (rename-session! (active-idx) new-label))
  ([idx new-label]
   (update-session! idx assoc :label new-label)
   (when-let [sid (:agent-session-id (get-session idx))]
     (persist-bridge/save-tui-session-meta! sid {:label new-label}))
   (redraw-tab-strip!)
   new-label))

(defn close-session!
  "Close a session: close its agent, remove watches, remove from sessions map.
   If closing the active session, picks an adjacent session, loads its display
   state into the layout, and redraws. Acquires switch-lock for scrollback swap.
   Before removal, invokes `!before-close-hooks` (used to cascade-close shared
   sub-output tabs)."
  [idx]
  (let [state @!sessions
        session (get-in state [:sessions idx])
        was-active? (= idx (:active-idx state))]
    (when session
      ;; Run before-close hooks (e.g. cascade-close root's shared sub-output tab).
      ;; Hooks run BEFORE we close agents / remove the session so they can read
      ;; session state if needed.
      (doseq [[_k f] @!before-close-hooks]
        (try (f idx session) (catch Throwable _)))
      ;; Close agents: for :output sessions, only close the session's own agent.
      ;; For :chat sessions, close all agent instances tracked in :agent-instances.
      (if (= :output (:session-type session))
        (when-let [^java.io.Closeable ag (:agent session)]
          (try (.close ag) (catch Exception _)))
        (doseq [^java.io.Closeable ag (:agent-instances session)]
          (try (.close ag) (catch Exception _))))
      ;; Tear down this root's per-root input queue (chat sessions only).
      ;; requiring-resolve avoids a circular require with core / session.
      (when (not= :output (:session-type session))
        (when-let [root-id (requiring-resolve 'ai.brainyard.agent-tui.session/root-agent-id)]
          (when-let [stop-q (requiring-resolve 'ai.brainyard.agent-tui.core/stop-input-queue-for-root!)]
            (try (stop-q (root-id (:agent session))) (catch Throwable _)))))
      ;; Remove watches
      (doseq [{:keys [atom key]} (:watches session)]
        (when atom (remove-watch atom key)))
      ;; Remove session and switch under lock to prevent emit race
      (locking switch-lock
        (swap! !sessions update :sessions dissoc idx)
        (when was-active?
          (let [remaining (session-indices)]
            (when (seq remaining)
              (let [next-idx (or (first (filter #(> % idx) remaining))
                                 (last (filter #(< % idx) remaining))
                                 (first remaining))]
                (swap! !sessions assoc :active-idx next-idx)
                (when-let [new-session (get-session next-idx)]
                  (load-session-into-layout! new-session)))))))
      (redraw-tab-strip!)
      true)))

;; ============================================================================
;; Session Switching
;; ============================================================================

(defn switch-to!
  "Switch the active session to the given index.
   Acquires switch-lock to prevent race with emit-to-session!.
   Saves current session's scrollback/viewport, stops the spinner,
   loads the new session's state, and redraws the terminal."
  [idx]
  (let [state @!sessions
        current-idx (:active-idx state)
        new-session (get-in state [:sessions idx])]
    (when (and new-session (not= idx current-idx))
      ;; Think blocks are per-root-agent. We do NOT globally stop the spinner on
      ;; switch (that would kill a still-running agent's). Instead: DETACH the
      ;; leaving tab's spinner from the layout BEFORE saving (so it isn't
      ;; persisted at a stale position — task/iteration blocks created while the
      ;; tab is backgrounded would otherwise land below it, breaking the
      ;; sticky-bottom anchor), then REATTACH the entering tab's spinner at the
      ;; sticky bottom after its layout loads. Both acquire spinner-lock and run
      ;; OUTSIDE switch-lock (emit! orders spinner→switch; matching that here
      ;; avoids a lock-order-reversal deadlock).
      (when-let [detach (requiring-resolve
                         'ai.brainyard.agent-tui.session/detach-think-block-for-session!)]
        (try (detach current-idx) (catch Throwable _)))
      (locking switch-lock
        ;; 1. Save current session state
        (save-current-session-state!)
        ;; 2. Update active index
        (swap! !sessions assoc :active-idx idx)
        ;; 3. Sync !tui-state with new session's agent (session-id from agent record)
        (when-let [tui-state (requiring-resolve 'ai.brainyard.agent-tui.session/!tui-state)]
          (let [ag (:agent new-session)]
            (swap! @tui-state assoc
                   :agent ag
                   :agent-id (:agent-id new-session)
                   :defagent-id (:defagent-id new-session)
                   :session-id (when ag (:session-id ag)))))
        ;; 4. Load new session into layout and redraw
        (load-session-into-layout! new-session)
        ;; 5. Swap input-history to the destination session's. Submits in
        ;;    the OLD session already persisted on each line, so we only
        ;;    need to load the NEW. `requiring-resolve` avoids a circular
        ;;    require with `core` (which requires this ns).
        (when-let [asid (:agent-session-id new-session)]
          (when-let [load-fn (requiring-resolve
                              'ai.brainyard.agent-tui.core/load-input-history-for-session!)]
            (try (load-fn asid) (catch Throwable _)))))
      ;; Recreate the entering tab's spinner at the sticky bottom (after layout
      ;; load, outside switch-lock). No-op if its agent already finished.
      (when-let [reattach (requiring-resolve
                           'ai.brainyard.agent-tui.session/reattach-think-block-for-session!)]
        (try (reattach idx) (catch Throwable _)))
      (redraw-tab-strip!))))

(defn next-session!
  "Switch to the next session (wrapping around)."
  []
  (let [^java.util.List indices (vec (session-indices))
        n (count indices)]
    (when (> n 1)
      (let [current (active-idx)
            pos (.indexOf indices current)
            next-idx (nth indices (mod (inc pos) n))]
        (switch-to! next-idx)))))

(defn prev-session!
  "Switch to the previous session (wrapping around)."
  []
  (let [^java.util.List indices (vec (session-indices))
        n (count indices)]
    (when (> n 1)
      (let [current (active-idx)
            pos (.indexOf indices current)
            prev-idx (nth indices (mod (dec pos) n))]
        (switch-to! prev-idx)))))

;; ============================================================================
;; Session Output Routing
;; ============================================================================

(defn- patch-live-block-in-snapshot
  "Pure: return an updated session map with `block-id`'s lines replaced by
   `new-lines` in the session's saved :scrollback + :live-blocks. If the
   block isn't recorded in the snapshot, the lines are appended at the tail
   (creating a fresh live-block entry). Other blocks positioned after the
   patched block have their :start-idx shifted to keep alignment."
  [{:keys [scrollback live-blocks] :as session} block-id new-lines]
  (let [scrollback (or scrollback [])
        live-blocks (or live-blocks {})
        new-count (count new-lines)]
    (if-let [{:keys [start-idx line-count]} (get live-blocks block-id)]
      (let [delta (- new-count line-count)
            sb-next (into (into (subvec scrollback 0 start-idx) new-lines)
                          (subvec scrollback (+ start-idx line-count)))
            blocks-next (reduce-kv
                         (fn [m id b]
                           (assoc m id
                                  (if (> (:start-idx b) start-idx)
                                    (update b :start-idx + delta)
                                    b)))
                         {}
                         (assoc live-blocks block-id
                                {:start-idx start-idx :line-count new-count}))]
        (assoc session :scrollback sb-next :live-blocks blocks-next))
      (let [start-idx (count scrollback)
            blocks-next (assoc live-blocks block-id
                               {:start-idx start-idx :line-count new-count})]
        (-> session
            (update :scrollback into new-lines)
            (assoc :live-blocks blocks-next))))))

(defn update-live-block-in-session!
  "Update a live block in the (possibly background) session at `sidx`.

   - When `sidx` IS the active session: delegate to `layout/update-live-block!`
     so the block also re-renders to the terminal.
   - When `sidx` is in the background: patch the session's saved :scrollback
     + :live-blocks directly. The next `switch-to!` will pick up the change.

   Used by cross-session widgets whose lifecycle can span session switches
   (e.g. the consolidated subagents block, which lives in the root chat
   session but may need its final settle-and-freeze while the user is looking
   at the shared sub-output tab)."
  [sidx block-id new-lines]
  (locking switch-lock
    (if (= sidx (active-idx))
      (layout/update-live-block! block-id new-lines)
      (when (get-session sidx)
        (swap! !sessions update-in [:sessions sidx]
               patch-live-block-in-snapshot block-id new-lines)))))

(defn freeze-live-block-in-session!
  "Freeze a live block belonging to session `sidx`. When `sidx` is active,
   delegates to `layout/freeze-live-block!`. When background, removes the
   block from the session's saved :live-blocks map (the lines stay in the
   saved :scrollback at the same position — they become permanent record)."
  [sidx block-id]
  (locking switch-lock
    (if (= sidx (active-idx))
      (layout/freeze-live-block! block-id)
      (when (get-session sidx)
        (swap! !sessions update-in [:sessions sidx :live-blocks] dissoc block-id)))))

(defn- remove-live-block-from-snapshot
  "Pure: drop `block-id`'s lines from the session's saved :scrollback and
   :live-blocks, shifting any subsequent blocks' :start-idx by the deleted
   line-count. No-op when the block isn't recorded."
  [{:keys [scrollback live-blocks] :as session} block-id]
  (let [scrollback (or scrollback [])
        live-blocks (or live-blocks {})]
    (if-let [{:keys [start-idx line-count]} (get live-blocks block-id)]
      (let [sb-next (into (subvec scrollback 0 start-idx)
                          (subvec scrollback (+ start-idx line-count)))
            blocks-next (reduce-kv
                         (fn [m id b]
                           (assoc m id (if (> (:start-idx b) start-idx)
                                         (update b :start-idx - line-count)
                                         b)))
                         {}
                         (dissoc live-blocks block-id))]
        (assoc session :scrollback sb-next :live-blocks blocks-next))
      session)))

(defn dispose-live-block-in-session!
  "Dispose a live block belonging to session `sidx` — remove the block AND
   its lines from scrollback. When `sidx` is active, delegates to
   `layout/dispose-live-block!`. When background, strips the lines from the
   session's saved :scrollback and shifts subsequent block start-idx values."
  [sidx block-id]
  (locking switch-lock
    (if (= sidx (active-idx))
      (layout/dispose-live-block! block-id)
      (when (get-session sidx)
        (swap! !sessions update-in [:sessions sidx]
               remove-live-block-from-snapshot block-id)))))

(defn emit-to-session!
  "Write output to a specific session's scrollback.
   If the session is active, also writes to the terminal via layout.
   If the session is in the background, buffers the output and marks unread.
   Acquires switch-lock to prevent race with switch-to!/close-session!.

   Always tees the bytes to the session's on-disk scrollback file (keyed on
   `:agent-session-id`), so resume can replay them via `tail-scrollback`."
  [idx s]
  (when (and s (not (clojure.string/blank? s)))
    (when-let [asid (:agent-session-id (get-session idx))]
      (persist-bridge/tee-scrollback! asid s))
    (locking switch-lock
      (if (= idx (active-idx))
        ;; Active session — write to terminal (which also updates !scrollback)
        (layout/write-output! s)
        ;; Background session — buffer in session's scrollback, mark unread.
        ;; Refresh the tab strip when the unread marker flips so the user
        ;; can see new activity in background tabs at a glance.
        (let [had-unread? (:has-unread? (get-session idx))]
          (swap! !sessions update-in [:sessions idx]
                 (fn [session]
                   (-> session
                       (update :scrollback into (clojure.string/split-lines s))
                       (assoc :has-unread? true))))
          (when-not had-unread? (redraw-tab-strip!)))))))

;; ============================================================================
;; Formatting
;; ============================================================================

(defn format-session-indicator
  "Format the active-session indicator for the status bar as
   `agent-id [provider/model]`. Tabs themselves live in the dedicated tab
   row (see `format-tab-strip`). Returns nil when no sessions exist."
  []
  (when (pos? (session-count))
    (let [current (get-active-session)
          lm (try (agent/get-config (:agent current) :lm-config)
                  (catch Exception _ nil))
          label (clj-llm/format-lm-label (:provider lm) (:model lm))
          agent-id (or (some-> (:defagent-id current) name)
                       (:agent-id current))]
      (ansi/muted (str (when agent-id (str agent-id " "))
                       "[" label "]")))))

(defn- agent-running?
  "True when `session`'s agent is currently in an :ask/:running state."
  [session]
  (boolean
   (when-let [ag (:agent session)]
     (try
       (= :running (get-in @(:!state ag) [:status]))
       (catch Throwable _ false)))))

(defn format-tab-strip
  "Build the ANSI string for the tab row from the current session list.
   Format per tab: ` label` where label is the session's short label.
   Active tab is bold + bright-cyan; tabs with unread output get a trailing
   `●`. Sub-output tabs get a trailing `↓` inside the label (added at
   creation time by `short-label` callers). Truncates the strip on the
   right with `…` if it exceeds terminal width. Falls back to '(no sessions)'
   when empty."
  []
  (let [sessions (session-list)
        cur (active-idx)
        cols (or (:cols @layout/!layout) 80)
        ;; Reserve a couple of columns for the leading space and possible '…'.
        max-vis (max 10 (- cols 3))
        segments (map (fn [{:keys [id label session-type has-unread?] :as s}]
                        (let [active? (= id cur)
                              running? (agent-running? s)
                              base-label (or label (str "session-" id))
                              type-glyph (when (= session-type :output) "↓")
                              full-label (str base-label (or type-glyph ""))
                              ;; Format: ` <label><marker?>`
                              ;; - active marker (* idle / ● running) is suffixed without space
                              ;; - unread marker `?` is also suffixed without space
                              core (str " " full-label
                                        (cond
                                          active?     (if running? "●" "*")
                                          has-unread? "?"
                                          :else       ""))]
                          (if active?
                            (ansi/style core ansi/bold ansi/bright-cyan)
                            (if has-unread?
                              (ansi/style core ansi/bright-yellow)
                              (ansi/muted core)))))
                      sessions)
        joined (apply str segments)
        plain (str/replace joined #"\033\[[0-9;]*m" "")
        plain-len (count plain)]
    (if (<= plain-len max-vis)
      joined
      ;; Over budget — best-effort truncate. Walk segments until we'd exceed,
      ;; then append a muted '…'.
      (loop [segs (seq segments) used 0 acc (StringBuilder.)]
        (if (or (nil? segs) (>= used max-vis))
          (str (.toString acc) (ansi/muted "…"))
          (let [seg (first segs)
                seg-plain (str/replace seg #"\033\[[0-9;]*m" "")
                seg-len (count seg-plain)]
            (if (> (+ used seg-len) max-vis)
              (str (.toString acc) (ansi/muted "…"))
              (do (.append acc seg)
                  (recur (next segs) (+ used seg-len) acc)))))))))

(defn format-session-list
  "Format a list of all sessions for display."
  []
  (let [sessions (session-list)
        current-idx (active-idx)]
    (str (ansi/header "Sessions") "\n"
         (clojure.string/join "\n"
                              (map (fn [{:keys [id label defagent-id agent-id scrollback has-unread?]}]
                                     (let [active? (= id current-idx)
                                           display-id (or defagent-id agent-id)
                                           marker (cond active? (ansi/success "•")
                                                        has-unread? (ansi/warning "•")
                                                        :else (ansi/muted "•"))
                                           label-str (ansi/style (or label (str "session-" id)) ansi/bold)
                                           agent-str (when display-id
                                                       (ansi/muted (str " [" (name display-id) "]")))
                                           sb-count (if active?
                                                      (count @layout/!scrollback)
                                                      (count (or scrollback [])))
                                           size-str (ansi/muted (str sb-count " lines"))]
                                       (str " " marker " " label-str (or agent-str "")
                                            "  " size-str)))
                                   sessions))
         "\n")))

;; ============================================================================
;; Reset
;; ============================================================================

(defn reset-sessions!
  "Reset all session state. Used during stop!. Also resets the root-tab
   counter so the next process boot starts cleanly at `main0`."
  []
  (reset! !sessions {:active-idx 0
                     :next-id    1
                     :sessions   (sorted-map)})
  (reset! !root-tab-counter 0))

;; Install the default tab-strip builder. session.clj overrides this if it
;; wants richer info (e.g. running spinner state); the default works fine
;; for the layout-only path used by tests.
(install-tab-strip-builder! format-tab-strip)
