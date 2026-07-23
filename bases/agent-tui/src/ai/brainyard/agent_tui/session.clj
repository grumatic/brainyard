;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent-tui.session
  "TUI session state management. Iteration progress renders through the
   hook-driven iteration live-blocks (`iteration-pre-handler` and
   friends, fired from BT / dspy-action / tool-calls / code-eval); a
   small watch on the agent's :!session atom drives the activity panel
   and another on the global !tasks atom drives the task ticker.
   Captures *out* at start time for nREPL compatibility. Multi-session
   aware: per-session state lives in sessions/!sessions, global state
   (pending-permission, spinner, writer) stays in !tui-state."
  (:require [ai.brainyard.agent.interface.tui.format :as fmt]
            [ai.brainyard.agent.interface.tui.ansi :as ansi]
            [ai.brainyard.agent-tui.help-tips :as help-tips]
            [ai.brainyard.agent-tui.iteration-sink :as iter-sink]
            [ai.brainyard.agent-tui.layout :as layout]
            [ai.brainyard.agent-tui.output-sink :as out-sink]
            [ai.brainyard.agent-tui.persist-bridge :as persist-bridge]
            [ai.brainyard.agent-tui.sessions :as sessions]
            [ai.brainyard.agent.interface :as agent]
            [ai.brainyard.mulog.interface :as mulog]
            [ai.brainyard.clj-llm.interface :as clj-llm]
            [clojure.string :as str]))

(declare update-status-bar!)
(declare handle-session-change)
(declare get-active-agent)
(declare find-session-for-agent)
(declare session-idx-for-agent-session-id)
(declare root-of-agent)
(declare format-iter-elapsed)
;; ACP transcript block — the family is defined after the shared tool helpers
;; (it reuses `render-iter-tool-line` / `tool-result->body`); the iteration hook
;; handlers above it route here for acp-agent instances. Runtime resolution, so
;; forward declares suffice.
(declare acp-agent-instance?
         acp-create-block! acp-freeze-block!
         acp-append-chunk! acp-add-usage! acp-tool-pre! acp-tool-post!)

;; ============================================================================
;; State
;; ============================================================================

(defonce !tui-state
  (atom {:agent       nil      ;; current Agent instance
         :agent-id  nil      ;; keyword — instance-id (e.g. :coact-agent/tui-1713000000)
         :defagent-id nil    ;; keyword — defagent type (e.g. :coact-agent) for display
         :writer      nil      ;; captured java.io.Writer for nREPL
         :watches     []       ;; [{:atom :key}] for cleanup
         :queue-count 0        ;; number of items in the input queue
         :sub-trackers []      ;; [{:tracker atom :label str}] from sub-agents
         :mode        nil      ;; :A | :B (Mode C exits before start!) — see agent-tui.mode
         :started-at  nil}))

;; Pending user feedback — the single interactive-input channel. Set by the
;; unified feedback-fn (and its permission adapter), read/driven by the input
;; handler. One channel for all kinds — permission no longer has its own atom.
;; Value (nil when idle); shape depends on :kind:
;;   :select  {:promise p :kind :select :options <vec of {:label :description? :free-input?}>}
;;             — a :free-input pick adds :mode :awaiting-text :buf StringBuilder
;;               :free-idx int (byte-driven text collection)
;;   :text    {:promise p :kind :text}   — typed into the sticky input line
;;   :confirm {:promise p :kind :confirm :choices <vec of {:key char :label :value}>}
(defonce !pending-feedback (atom nil))

(defn feedback-prompt-parts
  "Input-line prompt look for a feedback `fb-kind` (nil = idle). Shared by the
   editor's redraw (`terminal/redraw-input-line!`) and the open/close refresh
   (`permissions`) so both agree on the answer-mode indicator. Returns
   {:prompt <styled 2-col string> :placeholder <raw hint string>}. While a
   prompt is open the prompt is a yellow '? ' with a per-kind hint; idle it is
   the normal cyan '> '. Both prompts are 2 visible columns."
  ([] (feedback-prompt-parts (:kind @!pending-feedback)))
  ([fb-kind]
   (if fb-kind
     {:prompt (ansi/style "? " ansi/bold ansi/bright-yellow)
      :placeholder (case fb-kind
                     :text    "Answer the prompt above, Enter to submit"
                     :confirm "Press a highlighted key to answer"
                     :select  "Press the option number to answer"
                     "Answer the prompt above")}
     {:prompt (ansi/style "> " ansi/bold ansi/bright-cyan)
      ;; Idle help tip: agent suggestion (top priority) or a rotating static
      ;; hint. The redraw mutes this string just like the old fixed placeholder.
      :placeholder (help-tips/current-placeholder (sessions/active-idx))})))

;; TUI mulog publisher handle (verbose mode only)
(defonce ^:private !tui-publisher (atom nil))

;; Dedicated always-on mulog publisher handle for background memory milestones
;; (L2→L3 consolidation, graph extraction). Independent of the verbose firehose
;; above; gated at event time by the :show-memory-activity config key.
(defonce ^:private !memory-activity-publisher (atom nil))

;; ----------------------------------------------------------------------
;; Subagents block — one consolidated live block per ROOT agent that lists
;; all of its descendant sub-agents (one line each). Replaces the legacy
;; per-sub-agent agent-block + the dead :agent-activity panel.
;;
;; Lifecycle: a fresh block instance is opened the first time a sub-agent
;; appears under a root that has no current instance. Sub-agents are added
;; into the open instance as they spawn. When the LAST sub-agent in an
;; instance transitions to :done/:error, the block is rendered one final
;; time (all markers settled), frozen via `layout/freeze-live-block!` so
;; the lines stay in scrollback as a permanent record, and the tracking
;; entry is dissoc'd from `!subagents-blocks`. A subsequent sub-agent
;; under the same root opens a NEW instance with a new block-id.
;;
;; Shape: {root-agent-id
;;         {:block-id   :subagents/<root-name>:<creation-ms>
;;          :session-idx int        ; root's TUI session
;;          :sub-agents (sorted-map sub-agent-id sub-state)}}
;; Sub-state: {:agent-id        kw
;;             :defagent-id     kw          ; e.g. :research-agent
;;             :status          :created|:running|:done|:error
;;             :start-time      long
;;             :end-time        long|nil
;;             :st-mem-atom     atom|nil    ; live iteration count
;;             :iter-rollup     {:total-iterations :total-tokens
;;                               :last-iteration :last-result}|nil
;;             :parent-agent-id kw          ; immediate parent (not root)
;;             :depth           int}        ; 0 = direct child of root
(defonce !subagents-blocks (atom {}))

;; ----------------------------------------------------------------------
;; Shared sub-output sessions — one consolidated `:output` tab per ROOT
;; agent that aggregates the streamed output of all of its sub-agents.
;; Replaces the legacy "one :output tab per sub-agent ask" behavior, which
;; produced a tab explosion in deep multi-specialist runs.
;;
;; Shape: {root-agent-id session-idx}.
;; Lifecycle: lazily created on the first sub-agent ask under a root that
;; isn't in :share-parent-output-session mode. Closed when the root's chat
;; session closes (cascade via sessions/!before-close-hooks). Sub-agents
;; without :share-parent-output-session route ALL of their output (header
;; lines, iteration blocks, answer box) into the root's shared tab.
(defonce !root-output-sessions (atom {}))

;; Spinner frames for the subagents block animation.
(def ^:private subagents-spinner-frames
  ["●" "○"])
(defonce ^:private !subagents-spinner-idx (atom 0))
(defonce ^:private !subagents-ticker-thread (atom nil))

(defn- quiet?
  "True when the TUI is in :quiet display-format — only the final answer box
   should be rendered. All intermediate widgets (iteration block, think
   spinner, subagents block, TODO block) and intermediate scrollback
   emissions (observation, goal status) are suppressed."
  []
  (= :quiet (agent/get-config (get-active-agent) :display-format)))

;; Per-task live block state
;; {task-id {:name str, :status :pending|:running|:completed|:failed|:cancelled,
;;           :start-time long, :output-lines [str ...] (last 2)}}
(defonce !task-blocks (atom {}))
(defonce ^:private !task-ticker-thread (atom nil))

;; Bridge: task-id → owning TUI session index, captured at `:task/created`
;; time (where the creating agent's `*current-agent*` binding is live) so the
;; per-task block can be anchored to the AGENT'S session rather than whatever
;; session happens to be active when the :pending→:running watch fires. See
;; `task-created-handler` / `create-task-block!`.
(defonce ^:private !task-owner-session (atom {}))

;; Per-agent live compaction block state. Keyed by agent-id. Reset on post.
;; {agent-id {:trigger kw
;;            :before-tokens long
;;            :target-tokens long-or-nil
;;            :phases {<phase-kw> :start|:done}
;;            :start-ms long}}
(defonce !compaction-blocks (atom {}))

;; Per-ROOT-agent "thinking" live block. Tail-of-scrollback widget that
;; animates a Knight Rider ticker + a randomly-picked synonym + the latest
;; activity snippet. Keyed by the ROOT agent's id so multiple chat tabs each
;; animate their own spinner concurrently — and a sub-agent's activity rolls
;; up to its root's tab. (Previously a global singleton, which meant two live
;; tabs fought over one spinner and one tab's activity bled into another.)
;; Each entry:
;; {:session-idx int :shuffled-words vec :iteration-label str|nil
;;  :spinner-idx volatile<int> :st-mem-atom atom :start-time long
;;  :activity {:text str :ts long}|nil}
(defonce ^:private !think-blocks (atom {}))
;; Single shared animation ticker (150ms) iterating every root's think block —
;; mirrors the task/iteration tickers. nil when no think blocks are live.
(defonce ^:private !think-ticker-thread (atom nil))

(defn- think-block-id
  "Live-block id for a root agent's think spinner."
  [root-aid]
  (keyword "think-block" (name root-aid)))

(defn- think-key
  "Resolve `[root-aid session-idx]` for `agent`'s think block: keyed per ROOT
   agent and anchored to the root's chat session (so a sub-agent's activity
   rolls up to its root's tab). Returns nil when `agent` or its root has no
   resolvable id."
  [agent]
  (when agent
    (try
      (when-let [root (first (root-of-agent agent))]
        (when-let [root-aid (:agent-id root)]
          [root-aid (:id (find-session-for-agent root))]))
      (catch Throwable _ nil))))

;; Render context: when bound to a session-idx, emit! routes output to that session's
;; scrollback buffer (via emit-to-session!) instead of the active terminal.
;; Terminal-only ops (spinner, todo, status bar) are skipped when rendering for a
;; background session.
(def ^:dynamic *render-session-idx* nil)

(defn- render-active?
  "True when rendering for the currently active session (or no render context set)."
  []
  (or (nil? *render-session-idx*)
      (= *render-session-idx* (sessions/active-idx))))

(declare find-session-for-agent)

(defmacro with-agent-render-session
  "Pin emit-routing to the session that owns `agent` for the duration of
   `body`. `emit!` calls inside route through `emit-to-session!` to that
   exact session — they no longer follow whichever session is currently
   active. Terminal-only ops (spinner, status bar) skip when the agent's
   session is not active. When the agent has no associated session
   (e.g. shared-parent sub-agent mode), falls back to the default
   active-session routing."
  [agent & body]
  `(binding [*render-session-idx* (some-> (find-session-for-agent ~agent) :id)]
     ~@body))

;; ============================================================================
;; Output
;; ============================================================================

(def ^:private output-lock (Object.))

(defonce ^:private !spinner (atom nil)) ;; {:thread Thread :running atom<bool>}
(def ^:private spinner-lock (Object.))  ;; guards start/stop to prevent concurrent spinner threads

(def ^:private brand-label
  "Per-letter colorized 'BRAINYARD' brand for the status bar."
  (let [letters "BRAINYARD"
        colors  [ansi/bright-red ansi/bright-yellow ansi/bright-green
                 ansi/bright-cyan ansi/bright-blue ansi/bright-magenta
                 ansi/bright-red ansi/bright-yellow ansi/bright-green]]
    (apply str
           (map (fn [ch color] (ansi/style (str ch) ansi/bold color))
                letters colors))))

(defn- build-status-left
  "Build status bar left text: colorized BRAINYARD brand followed by the
   spinner frame (if any)."
  [spinner-text]
  (if spinner-text
    (str brand-label " " spinner-text)
    brand-label))

;; ============================================================================
;; Thinking live block (replaces status-bar thinking display in fullscreen)
;; ============================================================================

;; Standard braille rotation. 10 frames at 300ms = 3s per cycle.
(def ^:private think-ticker-frames
  ["⠋" "⠙" "⠹" "⠸" "⠼" "⠴" "⠦" "⠧" "⠇" "⠏"])

(def ^:private think-ticker-width 1)

;; Synonyms displayed next to the ticker. Rotated every `think-word-rotate-ticks`
;; frames so the user always has something to read while idle. Pre-shuffled at
;; start-thinking-indicator! time; the renderer advances through the shuffled
;; vector via `(quot spinner-idx think-word-rotate-ticks) mod count`.
(def ^:private think-words
  ["Thinking" "Pondering" "Cogitating" "Ruminating" "Reasoning"
   "Deliberating" "Contemplating" "Reflecting" "Mulling" "Calculating"
   "Computing" "Considering" "Inferring"])

;; Tick count between word rotations. 10 frames * 300ms = 3s per word.
(def ^:private think-word-rotate-ticks 10)

;; Milliseconds an activity stays "fresh" before the think-block falls back
;; to the rotating thinking word. 3s — long enough to read a tool-call line,
;; short enough that the spinner doesn't feel stuck on a stale event.
(def ^:private think-activity-ttl-ms 3000)

(defn- stamp-think-activity!
  "Record the most recent activity for `agent`'s (root) thinking live block.
   `text` should be a single short line (truncated by the renderer). No-op
   when the agent's root has no live think block yet — the block is created at
   turn start (`start-thinking-indicator!`); activity only refines it.
   Stamped by hook handlers (iteration/pre, dspy-action/pre, tool-calls/pre,
   tool-use/pre, code-eval/pre)."
  [agent text]
  (when (string? text)
    (when-let [[root-aid _] (think-key agent)]
      (when (get @!think-blocks root-aid)
        (swap! !think-blocks assoc-in [root-aid :activity]
               {:text text :ts (System/currentTimeMillis)})))))

(defn- fresh-activity-text
  "Return the activity text if `activity` ({:text :ts}) is still within TTL,
   else nil."
  [activity]
  (when-let [{:keys [text ts]} activity]
    (when (< (- (System/currentTimeMillis) ts) think-activity-ttl-ms)
      text)))

;; Cap on Think-section line count in iteration widgets (re-used here for the
;; multi-line `Think:` block in `render-iteration-block-lines`). The thinking
;; live-block itself is one-line and no longer references this.
(def ^:private think-max-lines 10)

;; Cap for the advisory-notice section in an iteration block (usage guide /
;; self-improvement nudge). A short nudge shows in full; a long usage guide
;; truncates with a `[-N lines]` marker instead of dominating the block.
(def ^:private notice-max-lines 6)

(defn- wrap-snippet-to-width
  "Word-wrap a single-line snippet to fit `width` display columns per line.
   Returns a vector of plain (unstyled) line strings. Newlines/whitespace runs
   are NOT collapsed — caller should pre-normalize if desired."
  [^String s ^long width]
  (let [width (max 1 width)
        len (.length s)]
    (loop [i 0, acc (transient [])]
      (if (>= i len)
        (persistent! acc)
        (let [end-i (loop [j i, w 0]
                      (if (>= j len)
                        j
                        (let [cp (Character/codePointAt s (int j))
                              n  (Character/charCount cp)
                              cw (fmt/display-width (.substring s j (+ j n)))]
                          (if (and (pos? cw) (> (+ w cw) width))
                            j
                            (recur (+ j n) (+ w cw))))))
              break-i (if (>= end-i len)
                        end-i
                        ;; Prefer last whitespace before end-i
                        (let [ws (loop [k (dec end-i)]
                                   (cond
                                     (< k i) -1
                                     (Character/isWhitespace (.charAt s k)) (inc k)
                                     :else (recur (dec k))))]
                          (if (pos? ws) ws end-i)))]
          (recur break-i (conj! acc (subs s i break-i))))))))

(defn- truncate-to-display-width
  "Truncate `s` (plain text, no ANSI) so it fits `width` display columns.
   Appends `…` when truncation happens. Width-aware for wide unicode chars."
  [^String s ^long width]
  (let [width (max 1 width)
        len (.length s)]
    (loop [i 0, w 0]
      (if (>= i len)
        s
        (let [cp (Character/codePointAt s (int i))
              n  (Character/charCount cp)
              cw (fmt/display-width (.substring s i (+ i n)))]
          (cond
            (> (+ w cw) width) (str (subs s 0 i) "…")
            :else (recur (+ i n) (+ w cw))))))))

(defn- render-think-block-lines
  "Build the ANSI lines for the thinking live block — a single line:

     `[<braille>] <rotating-word>... (<elapsed> · <activity?>)`

   - `[<braille>]` — bracketed spinner frame from `think-ticker-frames`.
   - `<rotating-word>` — bright-cyan; index is
     `(quot spinner-idx think-word-rotate-ticks)` mod `(count think-words)`.
   - `<elapsed>` — wall-clock since `start-time` (think-block creation),
     formatted via `format-iter-elapsed` (`Nms` / `N.Ns` / `Nm Ns`).
   - `<activity?>` — the latest stamped activity (`Running code…`,
     `Reasoning…`, `→ tool-name`, …) when within `think-activity-ttl-ms`;
     dropped otherwise. Shown muted, separated from elapsed by ` · `.

   When `paused-at` is non-nil the agent is parked at a checkpoint: the braille
   frame is replaced by a static `⏸`, elapsed is frozen at `paused-at` (so the
   timer stops climbing), the live activity is dropped, and a muted ` · paused`
   marker is appended. The ticker stops advancing the frame, so the line is
   completely still while paused.

   The line is truncated with `…` when it exceeds terminal width."
  [shuffled-words spinner-idx frame start-time activity paused-at]
  (let [cols (or (:cols @layout/!layout) 80)
        paused? (some? paused-at)
        bracketed-spinner (str (ansi/muted "[")
                               (if paused? (ansi/warning "⏸") (ansi/spinner-active frame))
                               (ansi/muted "]"))
        n (count shuffled-words)
        idx (mod (quot spinner-idx think-word-rotate-ticks) n)
        word (nth shuffled-words idx)
        head-plain (str word "...")
        elapsed-ms (- (long (or paused-at (System/currentTimeMillis)))
                      (long (or start-time (System/currentTimeMillis))))
        elapsed-str (format-iter-elapsed elapsed-ms)
        suffix-plain (str " ("
                          elapsed-str
                          (cond
                            paused? " · paused"
                            (and activity (not (str/blank? activity))) (str " · " activity))
                          ")")
        full-plain (str head-plain suffix-plain)
        ;; Budget: cols - bracketed-spinner(3) - " "(1) - safety(1) = cols - 5
        max-body (max 1 (- cols 5))
        truncated (truncate-to-display-width full-plain max-body)
        ;; Style: cyan for the head (word+...), muted for the suffix. If
        ;; truncation chopped into the suffix, fall back to cyan for the
        ;; whole survivor so the visible portion still reads as "thinking".
        body-styled (if (and (>= (count truncated) (count head-plain))
                             (= head-plain (subs truncated 0 (count head-plain))))
                      (str (ansi/style head-plain ansi/bright-cyan)
                           (ansi/muted (subs truncated (count head-plain))))
                      (ansi/style truncated ansi/bright-cyan))]
    [(str bracketed-spinner " " body-styled)]))

(defn- update-think-block!
  "Re-render root `root-aid`'s thinking live block (single-line braille spinner
   + body). Skips rendering when that block's session isn't active — a
   backgrounded tab's spinner stays frozen in its saved scrollback and resumes
   when the user switches back. Marked `:sticky-bottom?` so the spinner stays
   anchored at the bottom of the live-block region — any task / iteration /
   sub-agent blocks spawned afterward insert above it instead of below."
  [root-aid]
  (when-let [state (get @!think-blocks root-aid)]
    (let [origin-idx (:session-idx state)]
      (when (or (nil? origin-idx) (= origin-idx (sessions/active-idx)))
        (let [{:keys [shuffled-words spinner-idx start-time activity paused-at]} state
              tick   @spinner-idx
              frame  (nth think-ticker-frames
                          (mod tick (count think-ticker-frames)))
              lines  (render-think-block-lines shuffled-words tick frame
                                               start-time
                                               (fresh-activity-text activity)
                                               paused-at)]
          (layout/update-live-block! (think-block-id root-aid) lines
                                     {:sticky-bottom? true}))))))

(defn- think-root-paused?
  "True when `root-aid`'s agent is parked on a cooperative pause. Resolved via
   the agent registry; defaults to false when the agent can't be found."
  [root-aid]
  (try
    (when-let [ag (agent/get-agent root-aid)]
      (boolean (agent/paused? (:!state ag))))
    (catch Throwable _ false)))

(defn- start-think-block-ticker!
  "Start the single shared 150ms ticker that animates EVERY root's thinking
   live block. Idempotent — does nothing if the ticker is already running.
   Self-stops when no think blocks remain. Each block's spinner advances
   independently (per-block `:spinner-idx`); only blocks whose session is
   active actually re-render (see `update-think-block!`).

   Pause-aware: while a root is parked, its block is frozen — the frame stops
   advancing, the elapsed timer is pinned (`:paused-at`), and a static `⏸ …
   paused` line is rendered exactly once. On resume the paused duration is
   added back to `:start-time` so the elapsed clock excludes the pause."
  []
  (when-not @!think-ticker-thread
    (let [thread (Thread.
                  (fn []
                    (try
                      (loop []
                        (let [blocks @!think-blocks]
                          (when (seq blocks)
                            (doseq [[root-aid state] blocks]
                              (try
                                (if (think-root-paused? root-aid)
                                  ;; Parked: stamp :paused-at + render the static
                                  ;; paused line once, then sit still.
                                  (when-not (:paused-at state)
                                    (swap! !think-blocks assoc-in [root-aid :paused-at]
                                           (System/currentTimeMillis))
                                    (update-think-block! root-aid))
                                  ;; Running: clear any paused marker (rolling the
                                  ;; pause out of elapsed), then advance + render.
                                  (do
                                    (when-let [pa (:paused-at state)]
                                      (swap! !think-blocks update root-aid
                                             (fn [s] (-> s
                                                         (dissoc :paused-at)
                                                         (update :start-time + (- (System/currentTimeMillis) pa))))))
                                    (vswap! (:spinner-idx state) inc)
                                    (update-think-block! root-aid)))
                                (catch Throwable _)))
                            (Thread/sleep (long 150))
                            (recur))))
                      (catch InterruptedException _))
                    (reset! !think-ticker-thread nil)))]
      (.setDaemon thread true)
      (reset! !think-ticker-thread thread)
      (.start thread))))

;; Forward decl for spinner-idx volatile (used in atom — keep simple)
(defn- new-spinner-idx [] (volatile! 0))

(defn- finalize-think-block-in-session!
  "Dispose (or freeze) the think live block, routed to its `origin-idx`
   session. When that session is backgrounded — the user switched to another
   tab mid-turn — go through the session-aware helpers so the spinner is
   cleared from the origin session's SAVED scrollback rather than left frozen
   there (and a no-op disposal aimed at the foreground tab). Falls back to the
   foreground layout when origin is the active session or unknown. Mirrors the
   per-task / iteration block origin handling."
  [root-aid origin-idx dispose?]
  (let [bid (think-block-id root-aid)]
    (if (and origin-idx (not= origin-idx (sessions/active-idx)))
      (if dispose?
        (sessions/dispose-live-block-in-session! origin-idx bid)
        (sessions/freeze-live-block-in-session! origin-idx bid))
      (if dispose?
        (layout/dispose-live-block! bid)
        (layout/freeze-live-block! bid)))))

(defn- finalize-root-think-block!
  "Dispose (default) or freeze root `root-aid`'s think block per the
   `:dispose-think-block` config (empty blocks are always disposed), routed to
   its origin session so a backgrounded tab is cleared from its SAVED
   scrollback. Removes the entry and refreshes the status bar. Caller holds
   spinner-lock."
  [root-aid origin-idx]
  (when-let [state (get @!think-blocks root-aid)]
    (let [origin-idx  (or origin-idx (:session-idx state))
          background?  (and origin-idx (not= origin-idx (sessions/active-idx)))
          bid         (think-block-id root-aid)
          ;; Read config from the block's OWNING session's agent (not the
          ;; currently-active tab, which may be a different agent / a
          ;; sub-output tab with no agent).
          ag (:agent (or (when origin-idx (sessions/get-session origin-idx))
                         (sessions/get-active-session)))
          config-dispose? (boolean (agent/get-config ag :dispose-think-block))
          ;; Inspect content: empty snippet → just the one header line. When
          ;; backgrounded, the block lives in the origin session's SAVED
          ;; :live-blocks, not the foreground layout.
          block-info (if background?
                       (get-in (sessions/get-session origin-idx) [:live-blocks bid])
                       (get @layout/!live-blocks bid))
          empty? (or (nil? block-info)
                     (<= (or (:line-count block-info) 0) 1))
          dispose? (or config-dispose? empty?)]
      (swap! !think-blocks dissoc root-aid)
      (try (finalize-think-block-in-session! root-aid origin-idx dispose?)
           (catch Exception _))
      ;; Refresh status bar (drop any spinner-left text left over)
      (let [brand-left (build-status-left nil)
            session-indicator (sessions/format-session-indicator)
            full-left (cond
                        (and session-indicator brand-left) (str brand-left " " session-indicator)
                        session-indicator session-indicator
                        :else brand-left)]
        (layout/draw-status-bar! full-left (:status-text @layout/!layout))))))

(defn- stop-inline-spinner!
  "Stop the legacy inline-mode spinner (single global spinner — inline mode has
   no tabs). Caller holds spinner-lock."
  []
  (when-let [{:keys [thread running]} @!spinner]
    (reset! running false)
    (reset! !spinner nil)
    (when thread
      (try (.join ^Thread thread 200) (catch Exception _)))
    (when-not (layout/fullscreen?)
      (locking output-lock
        (let [w (if (thread-bound? #'*out*) *out* (:writer @!tui-state))]
          (when w
            (.write ^java.io.Writer w "\r                         \r")
            (.flush ^java.io.Writer w)))))))

(defn stop-thinking-indicator!
  "Stop and finalize a thinking spinner. With `agent`, finalizes that agent's
   ROOT think block. With no arg (emit! / session-switch cleanup), finalizes
   the think block belonging to the currently-active session. Either way the
   block is disposed/frozen via its origin session and the shared ticker
   self-stops once no blocks remain. Thread-safe via spinner-lock."
  ([]
   (locking spinner-lock
     (let [aidx (sessions/active-idx)]
       (doseq [[root-aid state] @!think-blocks]
         (when (= aidx (:session-idx state))
           (finalize-root-think-block! root-aid (:session-idx state)))))
     (stop-inline-spinner!)))
  ([agent]
   (locking spinner-lock
     (when-let [[root-aid origin-idx] (think-key agent)]
       (finalize-root-think-block! root-aid origin-idx))
     (stop-inline-spinner!))))

(defn start-thinking-indicator!
  "Start `agent`'s (root) thinking spinner.
   In fullscreen mode: create/replace the ROOT agent's think live block (keyed
   per root so concurrent chat tabs each animate their own spinner) anchored to
   the root's chat session, and ensure the single shared 150ms ticker is
   running. A backgrounded tab's spinner stays frozen in its scrollback and
   resumes on switch-back.
   In inline mode: print a static spinner line (no animation, single session).
   Thread-safe via spinner-lock."
  ([agent] (start-thinking-indicator! agent nil))
  ([agent iteration-label]
   (locking spinner-lock
     ;; Stop legacy spinner (inline mode)
     (when-let [{:keys [thread running]} @!spinner]
       (reset! running false)
       (reset! !spinner nil)
       (when thread
         (try (.join ^Thread thread 200) (catch Exception _))))
     (let [shuffled    (vec (shuffle think-words))
           static-line (str (first think-ticker-frames) " " (first shuffled) "…")
           st-mem-atom (or (:st-memory-atom (sessions/get-active-session))
                           (:st-memory-atom @!tui-state))
           fs?         (layout/fullscreen?)]
       (if fs?
         ;; Fullscreen: create/replace this root's think live block + ensure the
         ;; shared ticker runs. `:activity` starts nil — fresh cycles show the
         ;; rotating word until a hook stamps the first real event.
         (when-let [[root-aid sidx] (think-key agent)]
           (swap! !think-blocks assoc root-aid
                  {:shuffled-words shuffled
                   :iteration-label iteration-label
                   :session-idx sidx
                   :spinner-idx (volatile! 0)
                   :st-mem-atom st-mem-atom
                   :start-time (System/currentTimeMillis)
                   :activity nil})
           (try (update-think-block! root-aid) (catch Exception _))
           (start-think-block-ticker!))
         ;; Inline: print once, no animation loop (static, no live streaming)
         (do
           (locking output-lock
             (let [w (if (thread-bound? #'*out*) *out* (:writer @!tui-state))]
               (when w
                 (.write ^java.io.Writer w
                         (str "\r" (ansi/muted static-line) "  "))
                 (.flush ^java.io.Writer w))))
           (reset! !spinner {:thread nil :running (atom true)
                             :iteration-label iteration-label})))))))

(defn detach-think-block-for-session!
  "Remove (from the foreground layout) the think block(s) belonging to session
   `sidx`, WITHOUT dropping their `!think-blocks` entry. Called on switch-AWAY,
   before the leaving session's layout is saved, so the spinner isn't persisted
   into the snapshot at a stale position — task/iteration blocks created while
   the tab is backgrounded land at the snapshot tail (sticky-bottom is a
   foreground-only invariant) and would otherwise sit BELOW the frozen spinner.
   The block is recreated fresh at the sticky bottom on switch-back. Caller
   must NOT hold switch-lock (this takes spinner-lock; switch-to! orders them
   spinner→switch to match emit!)."
  [sidx]
  (locking spinner-lock
    (doseq [[root-aid state] @!think-blocks]
      (when (= sidx (:session-idx state))
        (try (layout/dispose-live-block! (think-block-id root-aid))
             (catch Exception _))))))

(defn reattach-think-block-for-session!
  "Recreate (at the sticky bottom) the think block(s) belonging to session
   `sidx`. Called on switch-BACK, after the entering session's layout is loaded,
   so a still-running agent's spinner reappears anchored at the bottom of the
   live-block region. Disposes any restored/stale instance FIRST, then
   re-renders — this normalizes the spinner to the bottom regardless of where it
   may have been persisted (e.g. if detach lost a race, or task/iteration blocks
   landed below it while backgrounded). No-op for sessions whose agent has since
   finished (no `!think-blocks` entry)."
  [sidx]
  (locking spinner-lock
    (doseq [[root-aid state] @!think-blocks]
      (when (= sidx (:session-idx state))
        (try
          (layout/dispose-live-block! (think-block-id root-aid))
          (update-think-block! root-aid)
          (catch Exception _))))))

(defn install-output-sink!
  "Install a daemon-mode output sink (see `agent-tui.output-sink`).  Pass
   nil to revert."
  [sink-fn]
  (out-sink/install! sink-fn))

(defn emit!
  "Thread-safe write to output. Routes based on context:
   1. Explicit session-idx arg → emit-to-session! (for post-ask routing)
   2. *render-session-idx* bound → emit-to-session! (for background watch rendering)
   3. agent-tui.output-sink installed → daemon-mode sink (e.g. per-pane FIFO writer)
   4. None of the above → layout/write-output! (active session terminal)

   Path 1 / 2 already tee to the persisted scrollback inside `emit-to-session!`.
   Path 4 tees here so the default \"active session\" write path also reaches
   `<project>/.brainyard/sessions/<asid>/scrollback.stream.txt`. Path 3 (out-sink) is
   the daemon mode — the daemon writes through its own per-pane FIFOs which
   are responsible for any persistence.

   Emitting normally finalizes the active session's thinking spinner — output
   appearing means a thinking phase produced its result. Pass
   `:keep-thinking? true` for INTERMEDIATE status lines emitted while the agent
   is still working (e.g. quiet-mode sub-agent milestones dispatched mid-turn):
   `write-output!` splices them in *above* the sticky-bottom think block, so the
   spinner keeps animating instead of being torn down mid-turn."
  ([s] (emit! s nil nil))
  ([s session-idx] (emit! s session-idx nil))
  ([s session-idx {:keys [keep-thinking?]}]
   (when (and s (not (str/blank? s)))
     (when (and (render-active?) (not keep-thinking?)) (stop-thinking-indicator!))
     (let [target (or session-idx *render-session-idx*)]
       (cond
         target                  (sessions/emit-to-session! target s)
         (out-sink/route! s)     nil
         :else                   (do
                                   (when-let [asid (:agent-session-id (sessions/get-active-session))]
                                     (persist-bridge/tee-scrollback! asid s))
                                   (layout/write-output! s)))))))

(defn emit-inline!
  "Thread-safe write without trailing newline. Routes through the daemon
   sink when installed; otherwise renders only when the session is active."
  [s]
  (when s
    (cond
      (out-sink/route! s)  nil
      (render-active?)     (layout/write-inline! s))))

(defn redraw-idle-prompt!
  "Repaint the idle input prompt line (empty buffer) with the current
   placeholder — prompt + muted help tip, cursor just after the prompt.

   Shared by the loop-top `commands/draw-prompt!` and the agent-suggestion
   hook: because a turn is dispatched asynchronously, the loop redraws the
   prompt before the turn sets a suggestion, so the hook calls this to make a
   freshly-captured tip appear without waiting for a keystroke. Marks the
   input buffer empty (this only ever draws the empty/idle line)."
  []
  (let [{:keys [prompt placeholder]} (feedback-prompt-parts)
        display (str prompt (ansi/muted placeholder))
        cursor-col3 (str ansi/esc "3G")]
    (layout/set-input-empty! true)
    (if (layout/fullscreen?)
      (layout/draw-input-prompt! (str display cursor-col3))
      (emit-inline! (str display cursor-col3)))))

;; ============================================================================
;; Idle-Tip Ticker (alternates suggestion / static tip on the idle prompt)
;; ============================================================================

(defonce ^:private !idle-tip-ticker-thread (atom nil))

(def ^:private idle-tip-interval-ms
  "How long each placeholder frame (suggestion vs. static tip) stays on screen."
  15000)

(defn stop-idle-tip-ticker!
  "Stop the idle-tip ticker thread. Idempotent."
  []
  (when-let [t @!idle-tip-ticker-thread]
    (reset! !idle-tip-ticker-thread nil)
    (try (.interrupt ^Thread t) (catch Exception _))))

(defn start-idle-tip-ticker!
  "Start a daemon thread that, while the user is sitting at an empty idle
   prompt with a live agent suggestion, alternates the placeholder between the
   suggestion and a rotating static help tip every `idle-tip-interval-ms`.

   Self-guards on every tick (only repaints when input is active, empty, no
   popover, and a suggestion is live), so it is a no-op during a turn, while
   the user is typing, or when no suggestion has been captured. Runs for the
   process lifetime; idempotent — won't start a second ticker."
  []
  (when-not @!idle-tip-ticker-thread
    (let [t (Thread.
             (fn []
               (try
                 (loop []
                   (Thread/sleep (long idle-tip-interval-ms))
                   (when (and (help-tips/agent-suggestion (sessions/active-idx))
                              (layout/input-active?)
                              (layout/input-empty?)
                              (not (layout/popover-active?)))
                     (help-tips/tick-frame!)
                     (try (redraw-idle-prompt!) (catch Exception _)))
                   (recur))
                 (catch InterruptedException _))
               (reset! !idle-tip-ticker-thread nil)))]
      (.setDaemon t true)
      (.setName t "idle-tip-ticker")
      (.start t)
      (reset! !idle-tip-ticker-thread t))))

(defn capture-writer!
  "Capture current *out* as the output target."
  []
  (swap! !tui-state assoc :writer *out*)
  (layout/set-writer! *out*))

(defn set-display-format!
  "Set display-format level: :quiet (answer only), :normal (iterations+tools+answer),
   :verbose (+BT traces). Writes the config key `:display-format` (the single
   source of truth) on the active agent — per-agent override + persisted."
  [level]
  {:pre [(#{:quiet :normal :verbose} level)]}
  (agent/set-config! (get-active-agent) :display-format level))

;; ============================================================================
;; TUI Mulog Publisher (verbose mode)
;; ============================================================================

(defn start-tui-publisher!
  "Start a custom mulog publisher that routes events through the TUI scroll region.
   Also stops the default console publisher to prevent raw stdout writes.
   Idempotent — no-op if already running."
  []
  (when-not @!tui-publisher
    ;; Stop the default console publisher first
    (mulog/stop-default-publisher!)
    ;; Start TUI publisher
    (let [publisher (mulog/make-fn-publisher (fn [event]
                                               (when-let [formatted (fmt/format-mulog-event event)]
                                                 (emit! formatted))))
          handle (mulog/start-publisher! {:type :inline :publisher publisher})]
      (reset! !tui-publisher handle))))

(defn stop-tui-publisher!
  "Stop the TUI mulog publisher. Idempotent."
  []
  (when-let [handle @!tui-publisher]
    (try (handle) (catch Exception _))
    (reset! !tui-publisher nil))
  ;; Also ensure default console publisher is stopped
  (mulog/stop-default-publisher!))

(defn start-memory-activity-publisher!
  "Start a dedicated, always-on mulog publisher that surfaces background memory
   milestones (`fmt/format-memory-activity-event`: L2→L3 consolidation, graph
   extraction) as muted `🧠 memory · …` lines in the active session's
   scrollback — so the user can see memory working even in normal/quiet mode.

   Distinct from `start-tui-publisher!` (the verbose firehose): this one does
   NOT stop the console publisher and only ever emits the curated milestone
   lines. Gated at event time by `:show-memory-activity` (default true), so it
   can be silenced live via config without tearing the publisher down. The
   memory work runs off the agent thread (async sidecar / fire-and-forget
   consolidation futures), so mulog — not an agent hook — is the signal source.
   Idempotent."
  []
  (when-not @!memory-activity-publisher
    (let [publisher (mulog/make-fn-publisher
                     (fn [event]
                       ;; Always live alongside the verbose firehose — no double-print,
                       ;; since `format-mulog-event` skips these curated events.
                       (when (not (false? (agent/get-config :show-memory-activity)))
                         (when-let [formatted (fmt/format-memory-activity-event event)]
                           (emit! formatted)))))
          handle    (mulog/start-publisher! {:type :inline :publisher publisher})]
      (reset! !memory-activity-publisher handle))))

(defn stop-memory-activity-publisher!
  "Stop the memory-activity mulog publisher. Idempotent."
  []
  (when-let [handle @!memory-activity-publisher]
    (try (handle) (catch Exception _))
    (reset! !memory-activity-publisher nil)))

;; Forward declaration for update-status-bar! (used by watch callbacks, defined below)
(declare update-status-bar!)

;; ============================================================================
;; Watch Callbacks
;; ============================================================================

(defn- make-session-watch
  "Create a session-scoped watch callback for agent session-atom changes.
   Only fires handle-session-change when the owning session is active.
   Binds `*render-session-idx*` so emits inside the handler stay pinned
   to this watch's session even if the active session flips mid-fire."
  [session-idx]
  (fn [key ref old new]
    (when (= session-idx (sessions/active-idx))
      (binding [*render-session-idx* session-idx]
        (handle-session-change key ref old new)))))

;; ============================================================================
;; Agent Activity Panel (fullscreen sticky area)
;; ============================================================================

(defn- truncate-plain
  "Truncate plain string to max chars with ellipsis (best-effort, not ANSI-aware)."
  [s max-len]
  (if (and s (> (count s) max-len))
    (str (subs s 0 (max 0 (dec max-len))) ansi/ellipsis)
    s))

;; ----------------------------------------------------------------------
;; Subagents block — helpers + renderer
;; ----------------------------------------------------------------------

(defn- root-of-agent
  "Walk `agent`'s :runtime/:parent-agent chain up to the topmost ancestor.
   Returns `[root-agent depth]` where depth = number of ancestors above
   `agent` (0 if `agent` IS the root)."
  [agent]
  (loop [a agent depth 0]
    (if-let [p (get-in @(:!state a) [:runtime :parent-agent])]
      (recur p (inc depth))
      [a depth])))

(defn- subagents-block-id
  "Live-block id for one subagents-block instance under a root.
   Per-instance (timestamp-suffixed) so a freshly-opened block after a
   prior frozen one doesn't collide on registry."
  [root-agent-id created-ms]
  (keyword "subagents"
           (str (clojure.core/name root-agent-id) ":" created-ms)))

(defn- format-elapsed-short
  "Compact elapsed-time string for the subagents block: '12s', '1.2m', '2h'."
  [ms]
  (cond
    (or (nil? ms) (neg? ms)) "―"
    (< ms 60000)             (format "%ds"  (long (/ ms 1000)))
    (< ms 3600000)           (format "%.1fm" (/ ms 60000.0))
    :else                    (format "%.1fh" (/ ms 3600000.0))))

(defn- sub-activity-summary
  "Compact ` · N tools · M code-blocks` segment from a sub-agent's retained
   activity counters, or nil when it did neither. Singular-aware. Shared by the
   subagents-block summary line and the quiet-mode close line so a sub-agent's
   activity reads identically wherever it's surfaced."
  [tools-used code-blocks-used]
  (let [tu (or tools-used 0)
        cu (or code-blocks-used 0)
        parts (cond-> []
                (pos? tu) (conj (str tu " tool" (when (not= 1 tu) "s")))
                (pos? cu) (conj (str cu " code-block" (when (not= 1 cu) "s"))))]
    (when (seq parts)
      (str " · " (str/join " · " parts)))))

(defn- render-sub-agent-lines
  "Build the line(s) for a sub-agent inside the subagents block.

   Summary line:
     `{indent}{marker} {agent-id}  [{type}]  {status}  Iter N/M  ({tok})  {elapsed}{activity}`
   where `{activity}` is a ` · N tools · M code-blocks` segment (present for any
   status once the sub-agent has done either — so it survives the
   `:running → :done` collapse into the frozen record). Children are indented
   under their parent with `↳ ` per depth level.

   While `:running`, up to two muted detail lines follow the summary (recent
   tool usage and recent code-blocks evaluated). Finished sub-agents
   (:done/:error) and not-yet-started ones (:created) collapse to just the
   summary line (which still carries the activity segment). Returns a vector of
   ANSI strings."
  [{:keys [agent-id defagent-id status start-time end-time
           st-mem-atom iter-rollup depth
           recent-tools recent-code tools-used code-blocks-used]}
   spinner-char]
  (let [indent (apply str (repeat (or depth 0) "  "))
        prefix (if (pos? (or depth 0)) (str indent "↳ ") indent)
        marker (case status
                 :created (ansi/style (or spinner-char "◌") ansi/bright-yellow)
                 :running (ansi/iter-marker-running (or spinner-char "●"))
                 :done    (ansi/iter-marker-success "✓")
                 :error   (ansi/iter-marker-failure "✗")
                 (ansi/muted "○"))
        st  (when st-mem-atom (try @st-mem-atom (catch Exception _ nil)))
        iter-n   (:iteration-count st)
        ;; max-iterations: prefer the agent's resolved config (looked up by
        ;; agent-id) so we get the full precedence chain; fall back to
        ;; whatever the live BT memory carries directly (used by tests that
        ;; seed `:config` without registering an agent record).
        sub-ag   (when agent-id (try (agent/get-agent agent-id) (catch Exception _ nil)))
        max-iter (or (when sub-ag (agent/get-config sub-ag :max-iterations))
                     (get-in st [:config :max-iterations]))
        iter-text (cond
                    (and iter-n max-iter) (str "Iter " iter-n "/" max-iter)
                    iter-n                (str "Iter " iter-n)
                    :else                 nil)
        elapsed-ms (when start-time
                     (- (or end-time (System/currentTimeMillis)) start-time))
        elapsed    (when elapsed-ms (format-elapsed-short elapsed-ms))
        tokens     (when-let [tot (:total-tokens iter-rollup)]
                     (when (pos? tot) (format "(%d tok)" tot)))
        type-tag   (when defagent-id
                     (ansi/muted (str "[" (name defagent-id) "]")))
        status-str (case status
                     :created (ansi/muted "created")
                     :running (ansi/style "running" ansi/bright-cyan)
                     :done    (ansi/iter-marker-success "done")
                     :error   (ansi/iter-marker-failure "error")
                     (ansi/muted (str (or status "?"))))
        ;; Activity totals (` · N tools · M code-blocks`) ride the summary line
        ;; itself — so a finished sub-agent's frozen scrollback record still
        ;; shows WHAT it did, not just that it ran. The counters are retained
        ;; across the :running→:done transition, so this renders for every status.
        activity (some-> (sub-activity-summary tools-used code-blocks-used)
                         ansi/muted)
        summary (str prefix
                     marker " "
                     ;; Display the full agent-id (namespace/name), dropping the
                     ;; leading colon. Matches the legacy agent-block label.
                     (ansi/header (subs (str agent-id) 1))
                     (when type-tag (str "  " type-tag))
                     "  " status-str
                     (when iter-text (str "  " (ansi/muted iter-text)))
                     (when tokens   (str "  " (ansi/muted tokens)))
                     (when elapsed  (str "  " (ansi/muted elapsed)))
                     activity)]
    (if (not= status :running)
      ;; Collapsed: created / done / error show only the summary line.
      [summary]
      ;; Running: append recent-activity lines + a muted totals footer.
      (let [cols       (or (:cols @layout/!layout) 80)
            child-ind  (str indent "  ")
            max-text   (max 8 (- cols (count child-ind) 4))
            trunc      (fn [s] (let [s (str s)]
                                 (if (> (count s) max-text)
                                   (str (subs s 0 (max 1 (- max-text 1))) "…")
                                   s)))
            act-items  (cond-> []
                         (seq recent-tools)
                         (conj (str "tools: "
                                    (str/join " · " (take-last 3 recent-tools))))
                         (seq recent-code)
                         (conj (str "code-blocks: "
                                    (str/join " · "
                                              (map #(str (:lang %) " " (:lines %) " lines")
                                                   (take-last 3 recent-code))))))
            n          (count act-items)
            tree-lines (map-indexed
                        (fn [i text]
                          (let [branch (if (= i (dec n)) "└ " "├ ")]
                            (str child-ind (ansi/muted (str branch (trunc text))))))
                        act-items)]
        ;; Totals footer removed — the `· N tools · M code-blocks` segment on the
        ;; summary line supersedes it (and, unlike the footer, survives to :done).
        (into [summary] (filterv some? tree-lines))))))

(defn- quiet-sub-milestone-line
  "Compact one-line sub-agent milestone for :quiet display-format, where the
   animated subagents block is suppressed so it's the only surface. `kind` is
   :started | :done | :error. Matches the `↳`/`●` bullet aesthetic of quiet's
   think bullets; the :done/:error line carries the activity summary + elapsed."
  [{:keys [agent-id defagent-id depth kind tools-used code-blocks-used elapsed-ms]}]
  (let [indent (apply str (repeat (or depth 0) "  "))
        prefix (if (pos? (or depth 0)) (str indent "↳ ") indent)
        marker (case kind
                 :started (ansi/iter-marker-running "●")
                 :done    (ansi/iter-marker-success "✓")
                 :error   (ansi/iter-marker-failure "✗")
                 (ansi/muted "○"))
        label  (if defagent-id (name defagent-id) (subs (str agent-id) 1))
        word   (case kind :started "started" :done "done" :error "error" "?")
        activity (when (not= kind :started)
                   (sub-activity-summary tools-used code-blocks-used))
        elapsed  (when (and (not= kind :started) elapsed-ms)
                   (format-elapsed-short elapsed-ms))]
    (str prefix marker " " (ansi/header (str "[" label "]"))
         "  " (ansi/muted word)
         (when activity (ansi/muted activity))
         (when elapsed  (str "  " (ansi/muted elapsed))))))

(defn- subagents-rendering-order
  "Sort sub-agents for display: chronological within each parent, with
   children grouped immediately beneath their parent (depth-first)."
  [sub-agents]
  (let [by-parent (group-by :parent-agent-id (vals sub-agents))]
    (letfn [(emit [parent-id]
              (let [siblings (sort-by :start-time (get by-parent parent-id []))]
                (mapcat (fn [s]
                          (cons s (emit (:agent-id s))))
                        siblings)))]
      ;; Top-level entries are those whose parent is the ROOT (not in the
      ;; sub-agents map). Identify them as anything whose :parent-agent-id
      ;; isn't the id of another tracked sub-agent.
      (let [tracked-ids (set (map :agent-id (vals sub-agents)))
            roots (->> (vals sub-agents)
                       (remove #(contains? tracked-ids (:parent-agent-id %)))
                       (sort-by :start-time))]
        (vec (mapcat (fn [r] (cons r (emit (:agent-id r)))) roots))))))

(defn- render-subagents-block-lines
  "Build the ANSI lines for the consolidated subagents block.

   No separator header — the block is just the per-sub-agent lines: one summary
   line each (depth-indented with ↳), plus, for running sub-agents, recent
   tool/code-block activity lines and a totals footer (see
   `render-sub-agent-lines`). `final?` is retained for call-site compatibility;
   it no longer changes the output (finished sub-agents already collapse to
   their summary line, so live and frozen renders converge)."
  ([state spinner-char] (render-subagents-block-lines state spinner-char false))
  ([{:keys [sub-agents]} spinner-char _final?]
   (let [ordered (subagents-rendering-order sub-agents)]
     (into [] (mapcat #(render-sub-agent-lines % spinner-char) ordered)))))

(defn- update-subagents-block!
  "Re-render the consolidated subagents block for the given root.

   The block lives in the root agent's chat session — but iteration handlers
   and the ticker may fire while the user is looking at a different session
   (e.g. the root's shared sub-output tab). To keep one consolidated block
   per root that always reaches its final settled state, route the update
   through `sessions/update-live-block-in-session!`: it splices into the
   active scrollback when the root's session is foreground, or patches the
   root session's saved :scrollback + :live-blocks directly when it's in
   the background — so the final state survives any session-switch race.

   When `final?` is true the running-counter header is omitted (frozen
   scrollback only needs the per-sub-agent summary lines)."
  ([root-agent-id] (update-subagents-block! root-agent-id false))
  ([root-agent-id final?]
   ;; Suppressed in :quiet display-format (answer-only mode).
   (when-not (quiet?)
     (when-let [{:keys [block-id session-idx] :as st}
                (get @!subagents-blocks root-agent-id)]
       (let [spinner-char (nth subagents-spinner-frames
                               (mod @!subagents-spinner-idx
                                    (count subagents-spinner-frames)))
             lines (render-subagents-block-lines st spinner-char final?)]
         (if session-idx
           (sessions/update-live-block-in-session! session-idx block-id lines)
           (layout/update-live-block! block-id lines)))))))

(defn- task-block-id
  "Live block key for a task."
  [task-id]
  (keyword "task-block" (name task-id)))

(defn- render-task-block-lines
  "Build ANSI lines for a single task's live block. Max 3 lines.
   Line 1: spinner + task-id - job-type · lang · N lines  status  (elapsed)
   Lines 2-3: last 2 output lines"
  [task-id {:keys [status start-time output-lines output-count
                   job-type lang]} spinner-char]
  (let [cols (or (:cols @layout/!layout) 80)
        elapsed (when start-time
                  (let [ms (- (System/currentTimeMillis) start-time)
                        secs (/ ms 1000.0)]
                    (if (< secs 60)
                      (format "%.1fs" secs)
                      (format "%.0fm" (/ secs 60.0)))))
        spinner (case status
                  :pending   (ansi/muted "○")
                  :running   (ansi/spinner-active (or spinner-char "⠋"))
                  :completed (ansi/iter-marker-success "●")
                  :failed    (ansi/iter-marker-failure "✗")
                  :cancelled (ansi/muted "○")
                  (ansi/muted "○"))
        status-plain (case status
                       :pending   "pending"
                       :running   "running"
                       :completed "done"
                       :failed    "failed"
                       :cancelled "cancelled"
                       (str (or status "?")))
        status-str (case status
                     :pending   (ansi/muted status-plain)
                     :running   (ansi/style status-plain ansi/bright-yellow)
                     :completed (ansi/iter-marker-success status-plain)
                     :failed    (ansi/iter-marker-failure status-plain)
                     :cancelled (ansi/muted status-plain)
                     (ansi/muted status-plain))
        tid-str    (ansi/style (clojure.core/name task-id) ansi/bold)
        jt-str     (when job-type
                     (ansi/muted (clojure.core/name job-type)))
        lang-str   (when lang (ansi/muted lang))
        n-lines    (or output-count 0)
        lines-str  (ansi/muted (str n-lines (if (= 1 n-lines) " line" " lines")))
        meta-parts (filterv some? [jt-str lang-str lines-str])
        meta-str   (str/join (ansi/muted " · ") meta-parts)
        header (str spinner " "
                    tid-str " - " meta-str
                    "  " status-str
                    (when elapsed (str "  " (ansi/muted (str "(" elapsed ")")))))
        ;; Drop blank/whitespace-only lines so the block shows the last 2
        ;; meaningful lines, not empty tree rows.
        body-lines   (vec (take-last 2 (remove str/blank? output-lines)))
        n (count body-lines)
        tree (map-indexed
              (fn [i line]
                (let [prefix (if (= i (dec n)) "  └ " "  ├ ")
                      max-text (max 1 (- cols (count prefix)))
                      text (str line)
                      truncated (if (> (count text) max-text)
                                  (str (subs text 0 (max 1 (- max-text 3))) "...")
                                  text)]
                  (str prefix (ansi/muted truncated))))
              body-lines)]
    (vec (cons header tree))))

(defn- update-task-block!
  "Re-render a task's live block with the current spinner frame.

   Routes through `sessions/update-live-block-in-session!` keyed on the task's
   originating session, so the update lands in that session's saved scrollback
   even when the user has switched to a different tab (e.g. the shared
   sub-output session) while the task is still running. Without it, a task that
   finishes while its origin session is backgrounded would leave a stale
   `running` block frozen in that session's scrollback. Mirrors
   `update-subagents-block!`."
  [task-id]
  (when-let [state (get @!task-blocks task-id)]
    (let [origin-idx   (:session-idx state)
          spinner-char (nth subagents-spinner-frames @!subagents-spinner-idx)
          lines        (render-task-block-lines task-id state spinner-char)]
      (if origin-idx
        (sessions/update-live-block-in-session!
         origin-idx (task-block-id task-id) lines)
        (layout/update-live-block! (task-block-id task-id) lines)))))

;; ============================================================================
;; Iteration live block (per-iteration LLM step within a turn)
;; ============================================================================

;; Per-iteration block state, keyed by [agent-id repeat-id iteration].
;; {[aid rid iter] {:agent-id kw :repeat-id str :iteration int :max-iterations int
;;                  :stage :pre|:think|:tools|:code|:done
;;                  :reasoning   string|nil    ; from dspy-action/post
;;                  :streaming   string|nil    ; from dspy-action/chunk; cleared on /post
;;                  :tool-batch  [{:call-id :name :args :status :start-ms :end-ms
;;                                  :result-chars :error-msg :result-body}]
;;                                  ; :result-body = stringified success result
;;                                  ; for the boxed Result section (:error-msg
;;                                  ; drives the Error box); both collapsible.
;;                  :code        string|nil
;;                  :code-output {:result :output :error :duration-ms}|nil
;;                  :eval-section-lines [str]|nil  ; pre-rendered Code/Result/
;;                                                 ; Output/Error sections,
;;                                                 ; built once per code-eval
;;                                                 ; event by the hook handlers
;;                                                 ; so re-renders don't churn
;;                                                 ; display-block providers.
;;                  :usage       {:in :out :total}|nil
;;                  :result      :success|:failure|nil
;;                  :session-idx int|nil
;;                  :start-ms    long}}
(defonce !iteration-blocks (atom {}))
(defonce ^:private !iteration-ticker-thread (atom nil))

(defn- iteration-block-id
  "Live block key for an iteration."
  [agent-id repeat-id iteration]
  (keyword "iter-block"
           (str (clojure.core/name agent-id) ":"
                (clojure.core/name (or repeat-id :_)) ":" iteration)))

(defn- iter-id-prefix
  "Stable lowercase-alphanumeric id-prefix for the display-block providers
   registered by an iteration's eval sections. Compact (~6-13 chars) base-36
   encoding of a hash so that re-renders under the same (agent-id, repeat-id,
   iteration) key reuse the same provider ids and overwrite content rather
   than duplicate it."
  [agent-id repeat-id iteration]
  (let [h (Math/abs (long (hash [agent-id repeat-id iteration])))]
    (str "i" (Long/toString h 36))))

(defn- format-iter-args
  "Compact pr-str of args, truncated to 120 chars. Kept in lockstep with
   `render-iter-tool-line`'s boxing trigger: args within this budget render
   inline, longer args move into a boxed `Call` section, so the inline path
   never actually truncates in practice."
  [args]
  (let [s (try (pr-str args) (catch Exception _ "<unprintable>"))]
    (if (> (count s) 120)
      (str (subs s 0 117) "...")
      s)))

(defn- args-multiline?
  "True when any string-valued arg carries a newline — i.e. the args are
   really code (a bash script, a heredoc) rather than a flat scalar map.
   Such calls render as a boxed `Call` section instead of an inline
   one-liner so the script stays readable."
  [args]
  (boolean
   (cond
     (map? args)        (some #(and (string? %) (str/includes? % "\n")) (vals args))
     (string? args)     (str/includes? args "\n")
     :else              false)))

(defn- render-kv-body
  "Readable, uniform `name: value` rendering of a map for a boxed section
   (the `Call` args box and the `Result` box both use this).

   Every entry renders as `name: value`, regardless of how many there are
   or which one is the long/code-like one:
     - a scalar or single-line string sits inline after the colon
         path: foo.clj
     - a multi-line string value (a bash script / heredoc, or a tool's
       combined stdout+stderr) drops to its own 2-space-indented block
       beneath the name so the text stays readable
         output:
           hello
           world
   String values render raw (unquoted) so newlines/quotes read naturally;
   non-strings go through `pr-str` (`:kw`, `42`, `[1 2]`).

   Names and values are styled distinctly so they read apart inside the box:
   the name is a dim label (`ansi/tool-arg-name`) and the value pops in the
   code color (`ansi/tool-arg-value`). The box renders this body with an
   identity style-fn, so this embedded styling is what shows."
  [m]
  (letfn [(nm [k] (ansi/tool-arg-name
                   (str (if (keyword? k) (clojure.core/name k) (str k)) ":")))
          (render-pair [k v]
            (if (and (string? v) (str/includes? v "\n"))
              ;; multi-line: dim `name:` on its own line, value indented + styled
              (str (nm k) "\n"
                   (->> (str/split-lines v)
                        (map #(str "  " (ansi/tool-arg-value %)))
                        (str/join "\n")))
              ;; scalar / single-line: `name: value` with value styled inline
              (str (nm k) " " (ansi/tool-arg-value (if (string? v) v (pr-str v))))))]
    (->> m
         (map (fn [[k v]] (render-pair k v)))
         (str/join "\n"))))

(defn- iter-args-body
  "Readable, uniform rendering of tool args for a boxed `Call` section — a
   map renders as `name: value` lines via `render-kv-body`; a non-map arg
   falls back to a styled `pr-str` of the whole value."
  [args]
  (if (map? args)
    (render-kv-body args)
    (ansi/tool-arg-value (try (pr-str args) (catch Exception _ "<unprintable>")))))

(defn- tool-call-box-id
  "Stable display-block id for a tool call's boxed `Call` section, derived
   from its call-id so the called→done→error re-renders overwrite the same
   provider instead of leaking a new one each tick."
  [call-id]
  (str "tc" (Long/toString (Math/abs (long (hash call-id))) 36)))

(defn- tool-result-box-id
  "Stable display-block id for a tool call's boxed `Result` / `Error`
   section — distinct from `tool-call-box-id` (the args `Call` box) so the
   two providers don't collide. A call resolves to exactly one of done/error,
   so one id per call-id serves both kinds."
  [call-id]
  (str "tr" (Long/toString (Math/abs (long (hash call-id))) 36)))

(defn- format-iter-elapsed
  "Format elapsed millis as 'Nms', 'N.Ns', or 'Nm Ns'."
  [ms]
  (cond
    (nil? ms)    ""
    (< ms 1000)  (str ms "ms")
    (< ms 60000) (format "%.1fs" (/ ms 1000.0))
    :else        (let [s  (long (/ ms 1000))
                       m  (long (/ s 60))
                       rs (- s (* 60 m))]
                   (format "%dm %ds" m rs))))

(defn- format-iter-result-chars
  [n]
  (cond
    (nil? n)   ""
    (< n 1000) (str n " chars")
    :else      (format "%.1fk chars" (/ n 1000.0))))

(defn- truncate-iter-line
  [s max-len]
  (let [s (or s "")]
    (if (> (count s) max-len)
      (str (subs s 0 (max 1 (- max-len 3))) "...")
      s)))

(defn- render-iter-tool-line
  "Lines for one tool call. Normally a single compact line:
     '  → name(args): called [→ done|error, Ns, Mchars]'.

   When the args are code-like — a multi-line string (bash script /
   heredoc) or longer than the 60-char inline limit — the args move into a
   boxed, collapsible `Call` section (the same display-block treatment as
   the `Code` eval section) and the head line drops the inline args:
     '  → name: called → done, …'
     '    • Call:'
     '      ┌─ … └─'
   so a script-valued arg stays readable instead of being pr-str-truncated.

   Styling routes through the theme: `:tool/bullet` for the leading arrow,
   `:tool/name` for the tool name, `:tool/done` / `:tool/error` for the
   outcome marker, `:fg/muted` for the inline transition. A theme switch
   (e.g. `theme/set-active-theme!`) propagates here without any code change.

   Returns a vector of lines."
  [{:keys [name args status start-ms end-ms result-chars error-msg result-body call-id]}]
  (let [styled-name (ansi/tool-name name)
        bullet      (ansi/tool-bullet ansi/arrow)
        elapsed     (when (and start-ms end-ms) (format-iter-elapsed (- end-ms start-ms)))
        outcome     (case status
                      :done  (str " " (ansi/muted "→") " "
                                  (ansi/tool-done "done")
                                  (when (seq elapsed) (str ", " elapsed))
                                  (when result-chars (str ", " (format-iter-result-chars result-chars))))
                      :error (str " " (ansi/muted "→") " "
                                  (ansi/tool-error "error")
                                  (when (seq elapsed) (str ", " elapsed))
                                  (when error-msg (str ", " (pr-str (truncate-iter-line error-msg 40)))))
                      "")
        ;; pr-str length is the established "one-line limit" (`format-iter-args`
        ;; truncates past 120); a code-like multi-line arg always boxes.
        boxed?      (or (args-multiline? args)
                        (> (count (try (pr-str args) (catch Exception _ ""))) 120))
        ;; Head line(s): the compact `→ name(args): called → done/error`
        ;; summary, with the args in a boxed `Call` section when they're
        ;; code-like / overflow the inline budget.
        head-lines  (if boxed?
                      (let [head (str "  " bullet " " styled-name ": called" outcome)
                            body (iter-args-body args)]
                        (if (str/blank? (str body))
                          [head]
                          (into [head] (fmt/format-tool-call-block body :id (tool-call-box-id call-id)))))
                      [(str "  " bullet " " styled-name "(" (format-iter-args args) "): called" outcome)])
        ;; Result body in a boxed, collapsible section — the same treatment
        ;; as the code-eval `Result` section. A failed call (:error status —
        ;; a thrown exception or a returned error map) renders a red `Error`
        ;; box so it stands out; a success renders a neutral `Result` box.
        result-box  (when (and result-body (not (str/blank? (str result-body))))
                      (if (= status :error)
                        (fmt/format-tool-error-block result-body
                                                     :id (tool-result-box-id call-id))
                        (fmt/format-tool-result-block result-body
                                                      :id (tool-result-box-id call-id))))]
    (into head-lines (or result-box []))))

(defn- render-iteration-quiet-lines
  "Minimal iteration render for :quiet display-format: the iteration's think
   text (streaming, else final reasoning) as a single bulleted entry — no
   header, tool, eval, or notice lines. A leading blank line separates the
   bullet block from whatever precedes it. Wrapped to width and capped at
   `think-max-lines` (with a `[-N lines]` indicator), mirroring the normal
   `Think:` section. Empty when the iteration has no think text yet.

   With `label` (a pre-styled string of visible width `label-w`) the label is
   inserted right after the bullet on the first line — e.g. a sub-agent name so
   its think reads `● [name] …`; continuation lines stay indented under the
   bullet only."
  ([state] (render-iteration-quiet-lines state nil 0))
  ([{:keys [reasoning streaming]} label label-w]
   (let [text-for-think (or streaming reasoning)]
     (if-not (and (string? text-for-think) (not (str/blank? text-for-think)))
       []
       (let [cols          (or (:cols @layout/!layout) 80)
             normalized    (-> text-for-think (str/replace #"\s+" " ") str/trim)
             head-prefix   "● "
             head-prefix-w (fmt/display-width head-prefix)
             cont-prefix   (apply str (repeat head-prefix-w \space))
             first-w       (max 10 (- cols head-prefix-w (or label-w 0)))
             rest-w        (max 10 (- cols head-prefix-w))
             first-fit     (or (first (wrap-snippet-to-width normalized first-w)) "")
             remainder     (str/triml (subs normalized (count first-fit)))
             extra-lines   (if (str/blank? remainder) [] (wrap-snippet-to-width remainder rest-w))
             extras-cap    (max 0 (dec think-max-lines))
             head-line     (str (ansi/style head-prefix ansi/bright-white) (or label "") (ansi/muted first-fit))
             body          (if (<= (count extra-lines) extras-cap)
                             (vec (cons head-line (map #(str cont-prefix (ansi/muted %)) extra-lines)))
                             (let [hidden      (- (count extra-lines) extras-cap)
                                   kept        (subvec (vec extra-lines) (- (count extra-lines) extras-cap))
                                   indicator   (ansi/style (str "[-" hidden " lines] ") ansi/dim)
                                   first-extra (str cont-prefix indicator (ansi/muted (first kept)))
                                   rest-extras (map #(str cont-prefix (ansi/muted %)) (rest kept))]
                               (vec (concat [head-line first-extra] rest-extras))))]
         ;; Leading blank line so the bullet block breathes from what precedes it.
         (into [""] body))))))

(defn- render-iteration-block-lines
  "Build ANSI lines for an iteration block. In :quiet display-format, renders
   only the think text as a bulleted entry (see `render-iteration-quiet-lines`);
   otherwise the full header + Think + tools + eval + notices block."
  [{:keys [iteration max-iterations stage reasoning streaming
           tool-batch eval-section-lines usage result start-ms end-ms notices]
    :as state}
   spinner-char]
  (if (quiet?)
    (render-iteration-quiet-lines state)
    (let [cols (or (:cols @layout/!layout) 80)
          ;; Bracketed indicator matches the legacy `[+] Iteration` style — `+`
          ;; while running, `✓` on success, `✗` on failure. Each marker
          ;; goes through its theme token so the live indicator switches
          ;; with the active theme.
          marker (case result
                   :success (ansi/iter-marker-success "✓")
                   :failure (ansi/iter-marker-failure "✗")
                   (case stage
                     :done (ansi/iter-marker-done "●")
                     (ansi/iter-marker-running "+")))
          usage-tot (:total usage)
          elapsed-str (when start-ms
                        (format-iter-elapsed (- (or end-ms (System/currentTimeMillis))
                                                start-ms)))
          label (if max-iterations
                  (format "Iteration %d / %d" iteration max-iterations)
                  (format "Iteration %d" iteration))
          header (str (ansi/muted "[") marker (ansi/muted "] ")
                      (ansi/iter-label label)
                      (when usage-tot (str "  " (ansi/iter-usage (format "(%d tok)" usage-tot))))
                      (when elapsed-str (str "  " (ansi/muted (str "(" elapsed-str ")")))))
          text-for-think (or streaming reasoning)
          ;; Multi-line Think section (per docs/simplified-agent-tui-arch-design.md
          ;; Phase 9): keep the same cap (`think-max-lines`) as the live-block so
          ;; the block doesn't dominate the iteration widget. Header line is
          ;;   `  Think: first wrapped line ...`
          ;; with continuation lines indented under the colon.
          think-block-lines
          (when (and (string? text-for-think) (not (str/blank? text-for-think)))
            (let [normalized (-> text-for-think
                                 (str/replace #"\s+" " ")
                                 str/trim)
                  head-prefix "  Think: "
                  head-prefix-w (count head-prefix)
                  cont-prefix (apply str (repeat head-prefix-w \space))
                  first-w (max 10 (- cols head-prefix-w))
                  rest-w  (max 10 (- cols head-prefix-w))
                  first-fit (or (first (wrap-snippet-to-width normalized first-w)) "")
                  remainder (str/triml (subs normalized (count first-fit)))
                  extra-lines (if (str/blank? remainder)
                                []
                                (wrap-snippet-to-width remainder rest-w))
                  extras-cap (max 0 (dec think-max-lines))
                  head-line (str (ansi/muted head-prefix) (ansi/muted first-fit))]
              (if (<= (count extra-lines) extras-cap)
                (vec (cons head-line
                           (map #(str cont-prefix (ansi/muted %)) extra-lines)))
                (let [hidden (- (count extra-lines) extras-cap)
                      kept (subvec (vec extra-lines)
                                   (- (count extra-lines) extras-cap))
                      indicator (ansi/style (str "[-" hidden " lines] ") ansi/dim)
                      first-extra (str cont-prefix indicator
                                       (ansi/muted (first kept)))
                      rest-extras (map #(str cont-prefix (ansi/muted %))
                                       (rest kept))]
                  (vec (concat [head-line first-extra] rest-extras))))))
          tool-lines (when (seq tool-batch)
                       (vec (mapcat render-iter-tool-line tool-batch)))
          ;; Advisory notice (usage guide / self-improvement nudge) the LLM also
          ;; reads via the record's :notices. Wrapped + capped so a long usage
          ;; guide can't dominate the iteration block; a short nudge shows in full.
          notice-lines
          (when (and (string? notices) (not (str/blank? notices)))
            (let [normalized (-> notices (str/replace #"\s+" " ") str/trim)
                  prefix "  "
                  w (max 10 (- cols (count prefix)))
                  wrapped (wrap-snippet-to-width normalized w)
                  cap notice-max-lines]
              (if (<= (count wrapped) cap)
                (mapv #(str prefix (ansi/muted %)) wrapped)
                (let [kept (subvec (vec wrapped) 0 cap)
                      hidden (- (count wrapped) cap)]
                  (conj (mapv #(str prefix (ansi/muted %)) kept)
                        (str prefix (ansi/style (str "[-" hidden " lines]") ansi/dim)))))))]
      ;; Eval sections (Code / Result / Output / Error) are pre-rendered
      ;; into :eval-section-lines by the code-eval hook handlers. Each
      ;; section is a display-block-backed boxed segment, so long content
      ;; collapses with a marker the user can expand. Using a stable
      ;; id-prefix per iteration means the pre-render and post-render
      ;; calls overwrite the same providers instead of leaking new ones.
      (vec (concat [header]
                   (or think-block-lines [])
                   (or tool-lines [])
                   (or eval-section-lines [])
                   (or notice-lines []))))))

(defn- update-iteration-block!
  "Re-render an iteration block.

   When the originating session is active (or unknown), render through the
   iteration sink to the foreground surface. When it is backgrounded, patch
   the origin session's saved scrollback via
   `sessions/update-live-block-in-session!` so a status transition that lands
   while the user is on another tab — e.g. a tool entry flipping
   `:called → :done` during a sub-agent's run on a different session — is
   preserved when they switch back. Without this the block would freeze with
   the stale pre-switch snapshot (a `called` tool line that never settles),
   because `iteration-post-handler`'s `already-buffered?` guard trusts the
   last rendered snapshot. Mirrors the per-task and subagents blocks.

   In :quiet display-format the block renders only the think text as a bullet
   (`render-iteration-block-lines` delegates to `render-iteration-quiet-lines`),
   so an iteration with no think text yet produces no lines and is skipped."
  [agent-id repeat-id iteration]
  (when-let [state (get @!iteration-blocks [agent-id repeat-id iteration])]
    (let [origin-idx   (:session-idx state)
          block-id     (iteration-block-id agent-id repeat-id iteration)
          spinner-char (nth subagents-spinner-frames @!subagents-spinner-idx)
          lines        (render-iteration-block-lines state spinner-char)]
      (when (seq lines)
        (if (or (nil? origin-idx) (= origin-idx (sessions/active-idx)))
          (iter-sink/write-widget! block-id lines)
          (sessions/update-live-block-in-session! origin-idx block-id lines))))))

(def ^:private todo-block-id :todo)
(def ^:private todo-auto-dispose-ms 5000)

;; Tracks the most recent TODO update so the timestamp-check future can
;; decide whether to dispose. {:last-updated-ms long :session-idx int} or nil.
(defonce ^:private !todo-state (atom nil))

(defn- render-todo-lines
  "Build ANSI lines for the TODO live block. Renders a centered separator
   labeled 'TODO [done/total]' followed by all items (capped by terminal
   height). Items use check/cross icons and truncate to terminal width."
  [items]
  (when (seq items)
    (let [cols (or (:cols @layout/!layout) 80)
          rows (or (:rows @layout/!layout) 24)
          n-total (count items)
          n-done  (count (filter :done items))
          max-items (max 2 (- (long (Math/floor (* rows 0.4))) 1))
          visible (take max-items items)
          progress (str "TODO [" n-done "/" n-total "]")
          label (str " " progress " ")
          label-len (count label)
          left-len (max 3 (quot (- cols label-len) 2))
          right-len (max 3 (- cols label-len left-len))
          separator (str (ansi/muted (apply str (repeat left-len ansi/h-line)))
                         (ansi/header label)
                         (ansi/muted (apply str (repeat right-len ansi/h-line))))
          item-lines (mapv (fn [item]
                             (let [check (if (:done item)
                                           (ansi/iter-marker-success ansi/check)
                                           (ansi/iter-marker-failure ansi/cross-mark))
                                   desc (or (:description item) "")
                                   max-w (- cols 4)
                                   trunc (if (> (count desc) max-w)
                                           (str (subs desc 0 (dec max-w)) ansi/ellipsis)
                                           desc)]
                               (str "  " check " " trunc)))
                           visible)]
      (into [separator] item-lines))))

(defn- dispose-todo-block!
  "Remove the TODO live block and clear the tracker atom."
  []
  (layout/dispose-live-block! todo-block-id)
  (reset! !todo-state nil))

(defn- schedule-todo-auto-dispose!
  "Spawn a future that disposes the TODO live block after
   `todo-auto-dispose-ms` if no new update has arrived and the user
   has not switched sessions."
  [sidx updated-ms]
  (future
    (Thread/sleep (long todo-auto-dispose-ms))
    (let [state @!todo-state]
      (when (and state
                 (= updated-ms (:last-updated-ms state))
                 (= sidx (sessions/active-idx)))
        (dispose-todo-block!)))))

(defn todo-updated-handler
  "Handler for :todo/updated. Event: {:agent :todo-list :active-slug}.
   Renders the todo-list into the :todo live block when the event's
   agent owns the active TUI session. Disposes the block when:
   - the todo-list is empty, or
   - every item is :done (renders once, then disposes), or
   - 5 seconds elapse since the last update with no new events."
  [{:keys [agent todo-list]}]
  ;; Suppressed in :quiet display-format (answer-only mode).
  (when-not (quiet?)
    (let [items (vec (or todo-list []))
          session (find-session-for-agent agent)
          sidx (:id session)
          active? (and sidx (= sidx (sessions/active-idx)) (layout/fullscreen?))]
      (when active?
        (cond
          (empty? items)
          (dispose-todo-block!)

          (every? :done items)
          (do (layout/update-live-block! todo-block-id (render-todo-lines items))
              (dispose-todo-block!))

          :else
          (let [now (System/currentTimeMillis)]
            (layout/update-live-block! todo-block-id (render-todo-lines items))
            (reset! !todo-state {:last-updated-ms now :session-idx sidx})
            (schedule-todo-auto-dispose! sidx now)))))))

(defn- render-agent-activity-entry!
  "Render a single sub-agent display event with agent name prefix.
   In :quiet display-format, mirror the main-agent iteration block: render only
   the sub-agent's think text as a name-prefixed `•` bullet and skip every other
   stage (iteration headers, tool calls/results, observations, eval sections)."
  [agent-name stage data]
  (let [prefix (str (ansi/style (str "[" agent-name "] ") ansi/bold ansi/bright-magenta))]
    (if (quiet?)
      (when (and (= stage :think) (:last-reasoning data))
        (let [label-w (count (str "[" agent-name "] "))
              lines   (render-iteration-quiet-lines {:reasoning (:last-reasoning data)}
                                                    prefix label-w)]
          (when (seq lines)
            (emit! (str/join "\n" lines)))))
      (case stage
        :iteration-start
        (emit! (str "\n" prefix
                    (ansi/style (str "[+] Iteration " (:iteration-count data))
                                ansi/bold ansi/bright-white)))

        :think
        (when (:last-reasoning data)
          (emit! (str prefix (fmt/format-thought (:last-reasoning data)))))

        :tool-calls
        (when (:tool-calls data)
          (emit! (str prefix (fmt/format-tool-calls (:tool-calls data)))))

        :tool-results
        (when (seq (:tool-results data))
          (emit! (str prefix (fmt/format-tool-results (:tool-results data)))))

        ;; :observe / :observe-v2 — surface only the observation. The
        ;; goal-achieved verdict is no longer shown per-iteration (for sub-agents
        ;; either); it surfaces once per turn in ask-post-handler.
        :observe
        (when (:observation data)
          (emit! (str prefix (fmt/format-observation (:observation data)))))

        :observe-v2
        (when (:observation data)
          (emit! (str prefix (fmt/format-observation (:observation data)))))

        :code-display
        (when-let [ed (:eval-display data)]
          (let [lines (fmt/format-eval-sections ed :include #{:code})]
            (when (seq lines)
              (emit! (str prefix (str/join "\n" lines))))))

        :eval-result
        (when-let [ed (:eval-display data)]
          (let [lines (fmt/format-eval-sections ed :include #{:result :output :error})]
            (when (seq lines)
              (emit! (str prefix (str/join "\n" lines))))))

        ;; :goal-status — removed. The goal-achieved verdict surfaces once per
        ;; turn in ask-post-handler, not per-iteration.

        ;; :todo-update — skip for sub-agents (too noisy)
        ;; Unknown stages — ignore
        nil))))

(defn- handle-session-change
  "Watch callback for session atom changes.
   Renders sub-agent activity entries and (at :verbose level) BT trace entries.
   Should only be called when the owning session is active (guarded by make-session-watch)."
  [_ _ old new]
  ;; ── Sub-agent activity stream ──
  (let [old-seq (or (:agent-activity-seq old) 0)
        new-seq (or (:agent-activity-seq new) 0)
        cols (or (:cols @layout/!layout) 80)]
    (when (> new-seq old-seq)
      (let [new-activity (or (:agent-activity new) [])
            new-entries (filterv #(> (:seq %) old-seq) new-activity)]
        (doseq [{:keys [agent-id agent-name stage data]} new-entries]
          (when (not= stage :agent-done)
            ;; The consolidated subagents block is driven directly from
            ;; the iteration / instance hooks (no need to peek into the
            ;; activity stream here). The watch's only remaining job is
            ;; the inline-mode pretty-print of activity entries.
            (when-not (layout/fullscreen?)
              (render-agent-activity-entry! agent-name stage data)))))))
  ;; ── Verbose BT traces (existing) ──
  (when (= :verbose (agent/get-config (get-active-agent) :display-format))
    (let [old-traces (get-in old [:data :traces])
          new-traces (get-in new [:data :traces])]
      (when (and new-traces (> (count new-traces) (count (or old-traces []))))
        (let [new-entries (subvec (vec new-traces) (count (or old-traces [])))]
          (doseq [entry new-entries]
            (emit! (if (map? entry)
                     (fmt/format-trace entry)
                     (str (ansi/muted (str "  [trace] " entry)))))))))))

;; ============================================================================
;; Task Activity Display
;; ============================================================================

(def ^:private bullet-frames ["○" "●"])

(defonce ^:private !task-activity-ticker (atom nil)) ;; {:thread Thread :running atom<bool>}
(defonce ^:private !ticker-frame-idx (atom 0))

(defn- build-task-activity-lines
  "Build pre-rendered lines for the task activity area.
   Returns a vector of ANSI-formatted strings, max 5 lines total.
   Layout: task headers (with bullet), then output lines for primary task."
  [running-tasks bullet-char cols]
  (let [n (count running-tasks)
        ;; Determine line budget
        max-headers (min n 2)
        show-more-tasks? (> n 2)
        header-rows (+ max-headers (if show-more-tasks? 1 0))
        output-budget (max 0 (- 5 header-rows))
        ;; Build header lines
        header-lines (mapv (fn [task]
                             (let [styled-bullet (ansi/style bullet-char ansi/bold ansi/bright-yellow)]
                               (str styled-bullet " "
                                    (ansi/style (str (name (:id task)) ": ") ansi/bold)
                                    (let [task-name (:name task)
                                          max-name (max 1 (- cols 15))]
                                      (if (> (count task-name) max-name)
                                        (str (subs task-name 0 (max 1 (- max-name 3))) "...")
                                        task-name)))))
                           (take max-headers running-tasks))
        more-tasks-line (when show-more-tasks?
                          [(ansi/muted (str "  +" (- n 2) " more task"
                                            (when (> (- n 2) 1) "s")))])
        ;; Output lines from primary (first) task. Drop blank/whitespace-only
        ;; lines so the activity area shows meaningful output, not empty rows.
        primary-task (first running-tasks)
        all-output (when primary-task (vec (remove str/blank? @(:output-lines primary-task))))
        output-count (count (or all-output []))
        show-overflow? (and (pos? output-budget) (> output-count output-budget))
        visible-output (cond
                         (zero? output-budget) []
                         show-overflow? (vec (take-last (dec output-budget) all-output))
                         :else (vec (take-last output-budget all-output)))
        overflow-count (- output-count (count visible-output))
        output-lines (cond-> (mapv (fn [line]
                                     (let [prefix "  \u2502 "
                                           max-text (max 1 (- cols (count prefix)))
                                           text (str line)
                                           truncated (if (> (count text) max-text)
                                                       (str (subs text 0 (max 1 (- max-text 3))) "...")
                                                       text)]
                                       (ansi/muted (str prefix truncated))))
                                   visible-output)
                       show-overflow?
                       (conj (ansi/muted (str "  +" overflow-count " lines more"))))]
    (vec (concat header-lines more-tasks-line output-lines))))

(defn- finalize-task-block!
  "Freeze or dispose `block-id` based on the global :dispose-task-block
   config (default true → dispose).

   With a `session-idx`, routes through the session-aware helpers so the
   action lands in that session's saved state even when the user has switched
   tabs (mirrors the subagents-block finalize path). Without it, operates on
   the foreground layout directly — used by the session-agnostic
   `:task-activity` aggregate block."
  ([block-id] (finalize-task-block! block-id nil))
  ([block-id session-idx]
   (if (boolean (agent/get-config :dispose-task-block))
     (if session-idx
       (sessions/dispose-live-block-in-session! session-idx block-id)
       (layout/dispose-live-block! block-id))
     (if session-idx
       (sessions/freeze-live-block-in-session! session-idx block-id)
       (layout/freeze-live-block! block-id)))))

(defn- refresh-task-activity!
  "Read current running tasks and update the task activity display.
   Called by ticker thread and on task status transitions."
  [bullet-char]
  (let [tasks (vals @agent/!tasks)
        running (sort-by :created-at (filter #(= :running (:status %)) tasks))
        cols (or (:cols @layout/!layout) 80)]
    (if (seq running)
      (let [lines (build-task-activity-lines running bullet-char cols)]
        (layout/update-live-block! :task-activity lines))
      (finalize-task-block! :task-activity))))

(defn stop-task-activity-ticker!
  "Stop the task activity ticker thread. Idempotent."
  []
  (when-let [{:keys [thread running]} @!task-activity-ticker]
    (reset! running false)
    (when thread
      (try (.join ^Thread thread 1500) (catch Exception _)))
    (reset! !task-activity-ticker nil)))

(defn start-task-activity-ticker!
  "Start daemon thread that animates task activity bullets every 1s
   and refreshes output. Self-stops when no running tasks remain."
  []
  (stop-task-activity-ticker!)
  (reset! !ticker-frame-idx 0)
  (let [running (atom true)
        thread (Thread.
                (fn []
                  (loop []
                    (when @running
                      (let [idx (swap! !ticker-frame-idx #(mod (inc %) 2))
                            bullet (nth bullet-frames idx)
                            tasks (vals @agent/!tasks)
                            any-running? (some #(= :running (:status %)) tasks)]
                        (if any-running?
                          (do (refresh-task-activity! bullet)
                              (Thread/sleep (long 1000))
                              (recur))
                          ;; No more running tasks — show final state briefly, then clear
                          (do (reset! running false)
                              (reset! !task-activity-ticker nil)
                              (future
                                (Thread/sleep (long 2000))
                                (finalize-task-block! :task-activity)))))))))]
    (.setDaemon thread true)
    (.start thread)
    (reset! !task-activity-ticker {:thread thread :running running})))

;; ============================================================================
;; Subagents Block — lifecycle helpers + ticker
;; ============================================================================

(defn- new-subagents-block!
  "Open a fresh subagents block instance under `root-aid` (and pin it to
   `session-idx`, the root's session). Returns the new block-id."
  [root-aid session-idx]
  (let [created-ms (System/currentTimeMillis)
        bid (subagents-block-id root-aid created-ms)]
    (swap! !subagents-blocks assoc root-aid
           {:block-id    bid
            :session-idx session-idx
            :sub-agents  (sorted-map)})
    bid))

(defn- ensure-subagents-block!
  "Return the existing block-id for `root-aid`'s currently-open subagents
   block, or open a new one when none exists."
  [root-aid session-idx]
  (or (:block-id (get @!subagents-blocks root-aid))
      (new-subagents-block! root-aid session-idx)))

(declare start-subagents-ticker!)

(defn- add-sub-agent!
  "Add (or upsert) `sub-agent-id` into the subagents block under `root-aid`.
   Creates the block on first sub-agent. Starts the ticker if not running."
  [root-aid session-idx sub-state]
  (ensure-subagents-block! root-aid session-idx)
  (swap! !subagents-blocks
         assoc-in [root-aid :sub-agents (:agent-id sub-state)] sub-state)
  (update-subagents-block! root-aid)
  (start-subagents-ticker!))

(defn- update-sub-agent!
  "Merge `updates` into the existing sub-agent entry under `root-aid`.
   No-op when the sub-agent isn't tracked. Triggers a re-render."
  [root-aid sub-agent-id updates]
  (when (get-in @!subagents-blocks [root-aid :sub-agents sub-agent-id])
    (swap! !subagents-blocks
           update-in [root-aid :sub-agents sub-agent-id]
           merge updates)
    (update-subagents-block! root-aid)))

(defn- keep-last
  "Append `xs` (a seq) onto vector `v`, keeping at most the last `n`."
  [v xs n]
  (vec (take-last n (into (or v []) xs))))

(defn- accumulate-sub-activity!
  "When `agent` is a tracked sub-agent, compute activity updates via
   `(f sub-state)` and merge them into its entry in the root's subagents block.
   No-op for root agents or untracked sub-agents. Mirrors the iter-rollup bump
   in `iteration-post-handler` — driven by tool-calls/post + code-eval/post."
  [agent f]
  (when (get-in @(:!state agent) [:runtime :parent-agent])
    (let [[root _]  (root-of-agent agent)
          root-aid  (:agent-id root)
          aid       (:agent-id agent)
          sub-state (get-in @!subagents-blocks [root-aid :sub-agents aid])]
      (when sub-state
        (update-sub-agent! root-aid aid (f sub-state))))))

(defn- all-sub-agents-done?
  [block-state]
  (every? #(#{:done :error} (:status %))
          (vals (:sub-agents block-state))))

(defn- mark-sub-agent-done!
  "Stamp `sub-agent-id` as `:done` (or `:error`) under `root-aid`. When
   this transition leaves NO running sub-agents, render the final state,
   freeze the live block (so the lines stay in scrollback as a permanent
   record), and dissoc the tracking entry. A future sub-agent under the
   same root opens a fresh block instance."
  [root-aid sub-agent-id status]
  (when-let [prior (get-in @!subagents-blocks [root-aid :sub-agents sub-agent-id])]
    (let [;; Guard on the real transition: this funnel is reachable twice (once
          ;; at :agent.ask/post when the sub-agent answers, once at
          ;; :agent.instance/closed), so only the first — from a non-terminal
          ;; status — should fire the one-shot quiet close line.
          transition? (not (#{:done :error} (:status prior)))
          now         (System/currentTimeMillis)]
      (swap! !subagents-blocks
             update-in [root-aid :sub-agents sub-agent-id]
             merge {:status   status
                    :end-time now})
      (update-subagents-block! root-aid)
      ;; Quiet mode: the animated subagents block is suppressed, so surface a
      ;; compact close milestone line to the root's session instead (emitted
      ;; before the all-done freeze/dissoc, while session-idx is still tracked).
      (when (and transition? (quiet?))
        (emit! (quiet-sub-milestone-line
                {:agent-id         sub-agent-id
                 :defagent-id      (:defagent-id prior)
                 :depth            (:depth prior)
                 :kind             status
                 :tools-used       (:tools-used prior)
                 :code-blocks-used (:code-blocks-used prior)
                 :elapsed-ms       (when-let [st (:start-time prior)] (- now st))})
               (:session-idx (get @!subagents-blocks root-aid))
               ;; Parent is still mid-turn — keep its think spinner alive.
               {:keep-thinking? true})))
    (when (all-sub-agents-done? (get @!subagents-blocks root-aid))
      ;; Re-render in `final?` mode — drops the running-counter header so the
      ;; frozen scrollback record carries only the per-sub-agent summary
      ;; lines (each line already has its own static ✓/✗ marker).
      (update-subagents-block! root-aid true)
      ;; Then freeze/dispose + dissoc. Route through the session-aware helper
      ;; so the action lands in the root chat session's saved state even when
      ;; the user is currently looking at a different tab.
      (let [{:keys [block-id session-idx]} (get @!subagents-blocks root-aid)
            dispose? (boolean (agent/get-config :dispose-agent-block))]
        (when block-id
          (try (if dispose?
                 (if session-idx
                   (sessions/dispose-live-block-in-session! session-idx block-id)
                   (layout/dispose-live-block! block-id))
                 (if session-idx
                   (sessions/freeze-live-block-in-session! session-idx block-id)
                   (layout/freeze-live-block! block-id)))
               (catch Exception _)))
        (swap! !subagents-blocks dissoc root-aid)))))

(defn- stop-subagents-ticker!
  "Stop the subagents-block ticker thread. Idempotent."
  []
  (when-let [t @!subagents-ticker-thread]
    (reset! !subagents-ticker-thread nil)
    (try (.interrupt ^Thread t) (catch Exception _))))

(defn- start-subagents-ticker!
  "Start daemon thread that animates the subagents-block spinners every
   400ms. Self-stops when no root has any non-done sub-agents. Idempotent
   — won't start a second ticker if one is already running."
  []
  (when-not @!subagents-ticker-thread
    (let [t (Thread.
             (fn []
               (try
                 (loop []
                   (let [active-roots
                         (->> @!subagents-blocks
                              (keep (fn [[rid st]]
                                      (when (some #(not (#{:done :error}
                                                         (:status %)))
                                                  (vals (:sub-agents st)))
                                        rid))))]
                     (when (seq active-roots)
                       (swap! !subagents-spinner-idx
                              #(mod (inc %) (count subagents-spinner-frames)))
                       (doseq [rid active-roots]
                         (update-subagents-block! rid))
                       (Thread/sleep (long 400))
                       (recur))))
                 (catch InterruptedException _))
               (reset! !subagents-ticker-thread nil)))]
      (.setDaemon t true)
      (.setName t "subagents-ticker")
      (.start t)
      (reset! !subagents-ticker-thread t))))

;; ============================================================================
;; Task Block Ticker (spinner animation for per-task live blocks)
;; ============================================================================

(defn- stop-task-block-ticker!
  "Stop the task block ticker thread. Idempotent."
  []
  (when-let [t @!task-ticker-thread]
    (reset! !task-ticker-thread nil)
    (try (.interrupt ^Thread t) (catch Exception _))))

(defn- start-task-block-ticker!
  "Start daemon thread that animates task block spinners every 1000ms.
   Also refreshes output lines from task atoms.
   Self-stops when no running tasks remain. Idempotent."
  []
  (when-not @!task-ticker-thread
    (let [t (Thread.
             (fn []
               (try
                 (loop []
                   (let [blocks @!task-blocks
                         active (filterv #(= :running (:status (val %))) blocks)]
                     (when (seq active)
                       (swap! !subagents-spinner-idx #(mod (inc %) (count subagents-spinner-frames)))
                       ;; Refresh output lines from task atoms. Guard each
                       ;; task's update so a transient render error doesn't kill
                       ;; the ticker that animates every other task block.
                       (doseq [[tid _] active]
                         (try
                           (when-let [task (get @agent/!tasks tid)]
                             (let [output (when-let [ol (:output-lines task)]
                                            (if (instance? clojure.lang.IDeref ol) @ol ol))
                                   last-2 (vec (take-last 2 (or output [])))]
                               (swap! !task-blocks update tid assoc
                                      :output-lines last-2
                                      :output-count (count (or output [])))))
                           (update-task-block! tid)
                           (catch Throwable e
                             (mulog/log ::task-block-tick-error :task-id tid
                                        :error (ex-message e)))))
                       (Thread/sleep (long 1000))
                       (recur))))
                 (catch InterruptedException _))
               (reset! !task-ticker-thread nil)))]
      (.setDaemon t true)
      (.setName t "task-block-ticker")
      (.start t)
      (reset! !task-ticker-thread t))))

;; ============================================================================
;; Iteration Block Ticker (elapsed-time refresh for per-iteration live blocks)
;; ============================================================================

(defn- start-iteration-block-ticker!
  "Start daemon thread that refreshes iteration block elapsed-time counters
   every 1000ms while any iteration is still running (`:stage` not `:done`).
   Without this ticker the `(N.Ns)` counter freezes between event hooks —
   during long quiet windows (a slow Thread/sleep, an LLM stream that flushes
   infrequently) the user sees stale elapsed time. Self-stops when no
   running iterations remain. Idempotent."
  []
  (when-not @!iteration-ticker-thread
    (let [t (Thread.
             (fn []
               (try
                 (loop []
                   (let [blocks @!iteration-blocks
                         active (filterv #(not= :done (:stage (val %))) blocks)]
                     (when (seq active)
                       (doseq [[[aid rid iter] _] active]
                         (try (update-iteration-block! aid rid iter)
                              (catch Exception _)))
                       (Thread/sleep (long 1000))
                       (recur))))
                 (catch InterruptedException _))
               (reset! !iteration-ticker-thread nil)))]
      (.setDaemon t true)
      (.setName t "iteration-block-ticker")
      (.start t)
      (reset! !iteration-ticker-thread t))))

;; ============================================================================
;; Task Watch
;; ============================================================================

(defn- subagent-task?
  "True when `task` is a detached subagent dispatch — a tool call whose tool
   type is :agent, which `adopt-tool-into-task` stamps with `:coact/subagent-id`
   (the live subagent's instance-id). Such a task is already surfaced by the
   consolidated subagents block (started / tools / code / closed), so its
   redundant per-task live block is suppressed. The marker's presence is the
   exact redundancy condition: it means a subagent instance exists for this
   task, i.e. the subagents block is (or will be) tracking it."
  [task]
  (some? (get-in task [:metadata :coact/subagent-id])))

(defn- create-task-block!
  "Helper: stamp a fresh entry in !task-blocks, render the live block,
   and ensure the ticker is running. Used both on :pending→:running and
   on user-driven :background→:foreground transitions."
  [task-id new-task]
  (swap! !task-blocks assoc task-id
         {:name (:name new-task)
          :job-type (:job-type new-task)
          :lang (get-in new-task [:metadata :coact/lang])
          :status :running
          ;; Anchor to the OWNING agent's session, NOT whatever session is
          ;; active now — a task created by a background agent, or while the
          ;; user switched tabs, must still settle in its origin session.
          ;; Resolution order:
          ;;   1. :owner-session-id stamped into task metadata at creation
          ;;      (the reliable signal — travels with the task; set by the
          ;;      fast-eval/detach tool path where *current-agent* is NOT bound
          ;;      on the adopting BT thread).
          ;;   2. the !task-owner-session bridge captured at :task/created
          ;;      (for paths where *current-agent* IS bound at creation).
          ;;   3. the active session (last resort).
          :session-idx (or (some-> (get-in new-task [:metadata :owner-session-id])
                                   session-idx-for-agent-session-id)
                           (get @!task-owner-session task-id)
                           (sessions/active-idx))
          :start-time (or (:started-at new-task) (System/currentTimeMillis))
          :output-lines []
          :output-count 0})
  (update-task-block! task-id)
  (start-task-block-ticker!))

(defn- dispose-task-block-with-marker!
  "Helper: dispose the per-task block immediately (no 2 s grace) and emit a
   single-line scrollback marker noting the transition. Used when display-mode
   flips :foreground → :background while the task is still running."
  [task-id reason]
  (when-let [state (get @!task-blocks task-id)]
    (let [bid  (task-block-id task-id)
          sidx (:session-idx state)]
      (finalize-task-block! bid sidx)
      (swap! !task-blocks dissoc task-id)
      (emit! (ansi/muted
              (str "[" (clojure.core/name task-id)
                   " moved to background"
                   (when reason (str " — " reason))
                   "]"))))))

(defn- handle-tasks-change
  "Watch callback for !tasks atom. Owns per-task live block lifecycle.

   Block creation/disposal is driven by `:metadata :display-mode` (not by
   raw status transitions):
     - :pending → :running AND :display-mode :foreground  → create block
     - :foreground → :background (while :running)         → dispose block + emit marker
     - :background → :foreground (while :running)         → create block (re-show)
     - :running → terminal (any block)                    → update + dispose after 2 s"
  [_ _ old-tasks new-tasks]
  (doseq [[task-id new-task] new-tasks]
    (let [old-task    (get old-tasks task-id)
          old-status  (:status old-task)
          new-status  (:status new-task)
          old-display (get-in old-task [:metadata :display-mode])
          new-display (or (get-in new-task [:metadata :display-mode]) :foreground)]
      ;; Status transitions
      (when (and old-status (not= old-status new-status))
        (cond
          ;; Task started running → create block iff display-mode :foreground
          ;; AND it isn't a subagent dispatch (those are already shown by the
          ;; consolidated subagents block — see `subagent-task?`).
          (= :running new-status)
          (when (and (= :foreground new-display)
                     (not (subagent-task? new-task)))
            (create-task-block! task-id new-task))
          ;; Task finished → update block, schedule freeze/dispose
          (#{:completed :failed :cancelled} new-status)
          (when (get @!task-blocks task-id)
            (let [output (when-let [ol (:output-lines new-task)]
                           (if (instance? clojure.lang.IDeref ol) @ol ol))
                  last-2 (vec (take-last 2 (or output [])))]
              (swap! !task-blocks update task-id merge
                     {:status new-status
                      :output-lines last-2
                      :output-count (count (or output []))}))
            (update-task-block! task-id)
            (let [bid  (task-block-id task-id)
                  sidx (:session-idx (get @!task-blocks task-id))]
              (future
                (Thread/sleep 2000)
                (finalize-task-block! bid sidx)
                (swap! !task-blocks dissoc task-id)))))
        ;; Refresh output-lines for running tasks with an active block
        (when (and (= :running new-status) (get @!task-blocks task-id))
          (let [output (when-let [ol (:output-lines new-task)]
                         (if (instance? clojure.lang.IDeref ol) @ol ol))
                last-2 (vec (take-last 2 (or output [])))]
            (swap! !task-blocks assoc-in [task-id :output-lines] last-2)
            (update-task-block! task-id)))
        (update-status-bar!))
      ;; Display-mode transitions (independent of status changes — the executor
      ;; detach path flips display via `set-display-mode!` while leaving status
      ;; at :running, so we cannot piggy-back on status-change branches).
      (when (and old-display (not= old-display new-display)
                 (= :running new-status))
        (case new-display
          :background
          (when (get @!task-blocks task-id)
            (dispose-task-block-with-marker! task-id nil))
          :foreground
          (when (and (not (get @!task-blocks task-id))
                     (not (subagent-task? new-task)))
            (create-task-block! task-id new-task))
          nil)
        (update-status-bar!)))))

;; ============================================================================
;; Watch Lifecycle
;; ============================================================================

(defn attach-watches!
  "Add watches on the agent's session atom (for task lifecycle / agent
   activity rendering) and the global !tasks atom.

   The agent's st-memory itself is no longer watched — iteration progress
   renders through the hook-driven iteration live-blocks (see
   `iteration-pre-handler` and friends). Stamps :st-memory-atom on the
   session map so /context, /memory, etc. that read st-memory directly
   still find it."
  ([agent] (attach-watches! agent (sessions/active-idx)))
  ([agent session-idx]
   (let [st-mem-atom (agent/get-bt-st-memory agent)
         session-atom (:!session agent)
         session-watch-key (keyword "tui-session" (str session-idx))]
     ;; Stamp st-memory-atom so direct readers (/context, /memory) find it.
     (when st-mem-atom
       (sessions/update-session! session-idx assoc :st-memory-atom st-mem-atom)
       ;; Keep !tui-state in sync for backward compatibility
       (swap! !tui-state assoc :st-memory-atom st-mem-atom))
     (let [watches (cond-> []
                     session-atom
                     (conj (do (add-watch session-atom session-watch-key
                                          (make-session-watch session-idx))
                               {:atom session-atom :key session-watch-key})))]
       ;; Task watch is global (not per-agent)
       (add-watch agent/!tasks ::tui-tasks handle-tasks-change)
       ;; Store watches in both session and !tui-state
       (sessions/update-session! session-idx assoc :watches watches)
       (swap! !tui-state assoc :watches watches)
       watches))))

(defn detach-watches!
  "Remove all TUI watches for the given session (or active session).
   Idempotent."
  ([] (detach-watches! (sessions/active-idx)))
  ([session-idx]
   (let [session (sessions/get-session session-idx)
         watches (or (:watches session) (:watches @!tui-state))]
     (doseq [{:keys [atom key]} watches]
       (when atom
         (remove-watch atom key)))
     (remove-watch agent/!tasks ::tui-tasks)
     (stop-task-activity-ticker!)
     (stop-task-block-ticker!)
     (stop-subagents-ticker!)
     (sessions/update-session! session-idx assoc :watches [])
     (swap! !tui-state assoc :watches []))))

(defn set-agent!
  "Set the TUI agent for a session. Detaches old watches, attaches new ones.
   Derives defagent-id from agent-id namespace (e.g. :coact-agent/tui-123 → :coact-agent).
   Options: :session-idx"
  [agent agent-id & {:keys [session-idx]}]
  (let [sidx   (or session-idx (sessions/active-idx))
        def-id (if (namespace agent-id)
                 (keyword (namespace agent-id))
                 agent-id)
        now    (System/currentTimeMillis)
        updates {:agent agent :agent-id agent-id
                 :defagent-id def-id :started-at now}]
    (detach-watches! sidx)
    (sessions/update-session! sidx merge updates)
    (swap! !tui-state merge updates)
    (attach-watches! agent sidx)
    (update-status-bar!)))

(defn clear-agent!
  "Detach watches and clear agent state for the given session (or active session)."
  ([] (clear-agent! (sessions/active-idx)))
  ([session-idx]
   (detach-watches! session-idx)
   (sessions/update-session! session-idx merge
                             {:agent nil
                              :agent-id nil
                              :defagent-id nil
                              :started-at nil})
   ;; Keep !tui-state in sync
   (swap! !tui-state assoc
          :agent nil
          :agent-id nil
          :defagent-id nil
          :started-at nil)))

(defn get-active-agent
  "Get the currently active agent instance from the active TUI session."
  []
  (:agent (sessions/get-active-session)))

(defn get-active-agent-id
  "Get the currently active agent instance-id from the active TUI session."
  []
  (:agent-id (sessions/get-active-session)))

(defn get-active-defagent-id
  "Get the currently active agent's defagent type keyword (e.g. :coact-agent).
   Falls back to agent-id if defagent-id is not set."
  []
  (let [session (sessions/get-active-session)]
    (or (:defagent-id session) (:agent-id session))))

(defn register-sub-tracker!
  "Register a sub-agent's usage tracker for aggregated /usage display.
   Deduplicates by tracker identity — re-registering the same atom is a no-op.
   Stores in both the active session and !tui-state for backward compatibility."
  [tracker label]
  (when tracker
    (let [add-fn (fn [trackers]
                   (if (some #(identical? (:tracker %) tracker) trackers)
                     trackers
                     (conj (or trackers []) {:tracker tracker :label label})))]
      (sessions/update-session! (sessions/active-idx) update :sub-trackers add-fn)
      (swap! !tui-state update :sub-trackers add-fn))))

(defn clear-sub-trackers!
  "Remove all registered sub-agent trackers."
  []
  (sessions/update-session! (sessions/active-idx) assoc :sub-trackers [])
  (swap! !tui-state assoc :sub-trackers []))

;; ============================================================================
;; Status Bar
;; ============================================================================

(defn update-status-bar!
  "Refresh the status bar from current agent state. No-op in inline mode.
   When multiple sessions exist, prepends a session indicator on the left."
  ([]
   (update-status-bar! nil))
  ([override-status]
   (when (layout/fullscreen?)
     (let [active-session (sessions/get-active-session)
           ag (or (:agent active-session) (:agent @!tui-state))
           session-indicator (sessions/format-session-indicator)
           ;; Build spinner/task left text fresh from source atoms (not from stored status-left,
           ;; which would already contain the session indicator from a previous render)
           spinner-task-left (build-status-left nil)
           ;; Combine session indicator with spinner/task text
           left-text (cond
                       (and session-indicator spinner-task-left)
                       (str spinner-task-left " " session-indicator)
                       session-indicator session-indicator
                       spinner-task-left spinner-task-left
                       :else nil)]
       (if-not ag
         (layout/draw-status-bar! left-text (layout/format-status {:status :idle}))
         (let [;; Accumulate usage from ALL agent instances in this TUI session
               all-instances (or (:agent-instances active-session) [])
               instance-trackers (keep (fn [inst]
                                         (agent/get-session-config @(:!session inst) :usage-tracker))
                                       all-instances)
               sub-trackers-coll (or (:sub-trackers active-session) (:sub-trackers @!tui-state))
               sub-trackers (keep :tracker sub-trackers-coll)
               all-trackers (concat instance-trackers sub-trackers)
               all-summaries (keep clj-llm/get-usage-summary all-trackers)
               combined (when (seq all-summaries)
                          (clj-llm/merge-usage-summaries all-summaries))
               usage  (:totals combined)
               calls  (or (:call-count usage) 0)
               tokens (or (:total-tokens usage)
                          (+ (or (:input-tokens usage) (:total-input-tokens usage) 0)
                             (or (:output-tokens usage) (:total-output-tokens usage) 0)))
               cost   (or (:total-cost usage) 0.0)
               ;; Most-recent call's prompt tokens + delta vs. the one before it,
               ;; merged across every tracker so sub-agent calls participate too.
               {:keys [last-input-tokens input-tokens-delta]}
               (clj-llm/last-input-tokens-with-delta all-trackers)
               tasks-running (count (filter #(= :running (:status %)) (vals @agent/!tasks)))
               queue-count  (or (:queue-count active-session) (:queue-count @!tui-state) 0)]
           (layout/draw-status-bar!
            left-text
            (layout/format-status
             {:status (or override-status
                          (get-in @(:!state ag) [:status])
                          :idle)
              :calls  calls
              :tokens tokens
              :cost   cost
              :last-input-tokens last-input-tokens
              :input-tokens-delta input-tokens-delta
              :tasks-running tasks-running
              :queue-count queue-count}))))
       ;; Refresh the tab strip alongside the status bar so the active-tab
       ;; running indicator (* vs ●) stays in sync with the agent state.
       (sessions/redraw-tab-strip!)))))

;; ============================================================================
;; Tool Result Hook (display switching for sub-agent tools)
;; ============================================================================

;; Default no-op handlers retained as registration anchors. Per-tool detail
;; renders inside the iteration widget via `tool-use-pre-handler` /
;; `tool-use-post-handler` further down this file.

(defn tool-pre-handler
  "Handler for :agent.tool-use/pre. Event: {:agent :tool-name :args}."
  [_event]
  nil)

(defn tool-post-handler
  "Handler for :agent.tool-use/post. Event: {:agent :tool-name :args :result}."
  [_event]
  nil)

;; ============================================================================
;; Agent Lifecycle Hooks (agent-level, stored in st-memory-init)
;; ============================================================================

(defn- share-parent-output?
  "True when this sub-agent's bt st-memory carries the
   :share-parent-output-session flag — meaning its output should land on the
   parent's chat tab, not the root's shared sub-output tab."
  [agent]
  (boolean
   (when-let [st-atom (try (agent/get-bt-st-memory agent) (catch Throwable _ nil))]
     (:share-parent-output-session @st-atom))))

(defn- find-session-for-agent
  "Find the TUI session map for the given agent instance.
   Resolution order:
   1. Literal session whose `:agent` is `agent` (root agents, legacy
      per-sub-agent tabs).
   2. Sub-agent fallback: the root's shared sub-output session, when the
      sub-agent is NOT in :share-parent-output-session mode. This is what
      consolidates per-sub-agent output into one tab per root.
   Returns nil when nothing matches (e.g. share-parent-output sub-agent —
   callers fall through to `find-session-for-parent` for those)."
  [agent]
  (or (first (filter #(identical? (:agent %) agent) (sessions/session-list)))
      (when (and agent
                 (get-in @(:!state agent) [:runtime :parent-agent])
                 (not (share-parent-output? agent)))
        (let [[root _] (root-of-agent agent)
              root-aid (:agent-id root)
              sidx (get @!root-output-sessions root-aid)]
          (when sidx (sessions/get-session sidx))))))

(defn session-idx-for-agent
  "Return the TUI session index (`:id`) that owns `agent`, or nil when the
   agent has no literal session (e.g. its tab was closed, or a shared-parent
   sub-agent). Public accessor over find-session-for-agent — used to route a
   wakeup resume to the parked agent's session rather than the active one."
  [agent]
  (:id (find-session-for-agent agent)))

(defn root-agent-id
  "The root agent's instance id for `agent` (the agent itself when it is a
   root). Used to key the per-root input queue + think block so each chat tab
   runs independently. Returns nil for a nil agent."
  [agent]
  (when agent
    (try (:agent-id (first (root-of-agent agent)))
         (catch Throwable _ nil))))

(defn- find-session-for-parent
  "Find the TUI session index for the parent agent. Returns nil if not found."
  [agent]
  (when-let [parent (get-in @(:!state agent) [:runtime :parent-agent])]
    (:id (first (filter #(identical? (:agent %) parent) (sessions/session-list))))))

(defn- session-idx-for-agent-session-id
  "Resolve an agent's stable session-id (string, e.g. \"agt-…\") to the TUI
   session index that owns it, by matching the session map's
   `:agent-session-id`. Returns nil when no session matches (e.g. a sub-agent
   that shares its parent's output tab — callers fall back to the active
   session)."
  [agent-session-id]
  (when agent-session-id
    (:id (first (filter #(= (:agent-session-id %) agent-session-id)
                        (sessions/session-list))))))

(defn agent-created-handler
  "Handler for :agent.instance/created. Event: {:agent}.

   For sub-agents (those with a :runtime/:parent-agent), add an entry
   into the consolidated subagents block belonging to this sub-agent's
   ROOT ancestor — there is exactly one block per root, listing all
   descendants one line each. The block is pinned to the root's TUI
   session so a session switch doesn't cause spinner updates to land
   in the wrong scrollback."
  [{:keys [agent]}]
  (when (get-in @(:!state agent) [:runtime :parent-agent])
    (let [[root-agent root-hops] (root-of-agent agent)
          root-aid    (:agent-id root-agent)
          agent-id    (:agent-id agent)
          parent      (get-in @(:!state agent) [:runtime :parent-agent])
          parent-aid  (:agent-id parent)
          defid       (when (namespace agent-id) (keyword (namespace agent-id)))
          st-mem-atom (try (agent/get-bt-st-memory agent) (catch Exception _ nil))
          ;; Display depth = root-hops - 1 (root itself doesn't count as
          ;; an indentation level; a direct child of root sits at depth 0,
          ;; a grandchild at depth 1, …).
          display-depth (max 0 (dec root-hops))
          ;; Pin the block to the ROOT's session — that's the chat
          ;; session the user typed their question into.
          root-sidx   (or (some-> (find-session-for-agent root-agent) :id)
                          (sessions/active-idx))]
      (add-sub-agent! root-aid root-sidx
                      {:agent-id        agent-id
                       :defagent-id     defid
                       :status          :created
                       :start-time      (System/currentTimeMillis)
                       :end-time        nil
                       :st-mem-atom     st-mem-atom
                       :iter-rollup     nil
                       ;; Activity counters accumulated by tool-calls/post +
                       ;; code-eval/post hooks (see accumulate-sub-activity!).
                       :tools-used       0
                       :recent-tools     []
                       :code-blocks-used 0
                       :recent-code      []
                       :parent-agent-id parent-aid
                       :depth           display-depth})
      ;; Quiet mode: the animated subagents block is suppressed, so surface a
      ;; compact `● [type] started` milestone line to the root's session.
      (when (quiet?)
        (emit! (quiet-sub-milestone-line
                {:agent-id agent-id :defagent-id defid
                 :depth display-depth :kind :started})
               root-sidx
               ;; Parent is still mid-turn — keep its think spinner alive.
               {:keep-thinking? true})))))

(defn agent-closed-handler
  "Handler for :agent.instance/closed. Event: {:agent}.

   For sub-agents, mark the entry :done in the root's subagents block
   (idempotent). When this transition leaves no running sub-agents the
   block is frozen so the lines stay in scrollback as a permanent
   record (handled inside `mark-sub-agent-done!`)."
  [{:keys [agent]}]
  (when (get-in @(:!state agent) [:runtime :parent-agent])
    (let [[root-agent _] (root-of-agent agent)]
      (mark-sub-agent-done! (:agent-id root-agent) (:agent-id agent) :done))))

(defn- ensure-shared-sub-output-session!
  "Look up (or lazily create) the shared sub-output tab for `root-agent`.
   Returns its session idx. Stored in `!root-output-sessions` keyed by the
   root's agent-id so all of the root's descendants land on the same tab."
  [root-agent root-chat-sidx]
  (let [root-aid (:agent-id root-agent)]
    (or (get @!root-output-sessions root-aid)
        ;; CAS-style create-once: build outside the swap! and only keep the
        ;; one that won the race. (A race here is rare but possible when two
        ;; sub-agents under the same root ask in parallel.)
        ;; Inherit the root tab's label so multiple roots get distinguishable
        ;; sub-output tabs. With root labels of the form `mainN` (assigned by
        ;; `sessions/next-root-tab-label!`), the rendered sub-output becomes
        ;; `mainN↓` — the `↓` glyph is appended by `format-tab-strip` for any
        ;; `:session-type :output` tab.
        (let [root-session (sessions/get-session root-chat-sidx)
              defid (:defagent-id root-session)
              label (or (:label root-session)
                        (sessions/short-label defid)
                        (name root-aid))
              new-sidx (sessions/create-session!
                        {:label             label
                         :agent             nil
                         :agent-id          (keyword "sub-output" (name root-aid))
                         :defagent-id       defid
                         :session-type      :output
                         :sub-output-of     root-chat-sidx
                         :status            :running
                         :skip-agent-creation true
                         :started-at        (System/currentTimeMillis)})
              winner (-> (swap! !root-output-sessions
                                (fn [m] (if (contains? m root-aid)
                                          m
                                          (assoc m root-aid new-sidx))))
                         (get root-aid))]
          (when-not (= winner new-sidx)
            ;; We lost the race — discard ours.
            (sessions/close-session! new-sidx))
          winner))))

(defn- emit-sub-agent-ask-header!
  "Paint a centered `── <agent-id> · ask ──` separator + the `❯ <input>`
   prompt into the shared sub-output tab. Makes it easy to see which
   sub-agent is asking what when several share the same tab."
  [sidx agent-id input]
  (let [cols      (or (:cols @layout/!layout) 80)
        label     (str " " (name agent-id) " · ask ")
        label-len (count label)
        left-len  (max 3 (quot (- cols label-len) 2))
        right-len (max 3 (- cols label-len left-len))
        sep       (str (ansi/style (apply str (repeat left-len ansi/h-line)) ansi/dim)
                       (ansi/style label ansi/bold ansi/bright-cyan)
                       (ansi/style (apply str (repeat right-len ansi/h-line)) ansi/dim))]
    (sessions/emit-to-session! sidx (str "\n" sep))
    (when (string? input)
      (sessions/emit-to-session!
       sidx (ansi/style (str "❯ " input) ansi/bold ansi/bright-cyan)))))

(defn ask-pre-handler
  "Handler for :agent.ask/pre. Event: {:agent :input}.

   For root agents: no-op (the user's own prompt goes through their chat
   session via `read-input-line`).

   For sub-agents: route the ask header + iteration output through the
   ROOT's shared sub-output tab — one tab per root, NOT per sub-agent.
   `:share-parent-output-session true` is still honored: those sub-agents
   emit a `❯❯ [agent-id] question` prompt on the parent's chat session and
   their iteration output follows the same path via the
   iteration/dspy-action/tool-calls hook handlers (which read the routing
   from `find-session-for-agent` → `with-agent-render-session`)."
  [{:keys [agent input]}]
  ;; A new turn makes any prior next-user-prompt suggestion stale — drop it so
  ;; the idle prompt falls back to the rotating static tips until the agent
  ;; emits a fresh suggestion at turn end. Repaint the idle prompt if the user
  ;; is sitting at it empty, so a stale tip doesn't linger when this turn
  ;; produces no follow-up of its own.
  (let [sidx (session-idx-for-agent agent)]
    (when (help-tips/agent-suggestion sidx)
      (help-tips/clear-agent-suggestion! sidx)
      (when (and (layout/input-active?)
                 (layout/input-empty?)
                 (not (layout/popover-active?)))
        (try (redraw-idle-prompt!) (catch Exception _)))))
  (when-let [_parent (get-in @(:!state agent) [:runtime :parent-agent])]
    (let [agent-id (:agent-id agent)
          st-mem-atom (try (agent/get-bt-st-memory agent) (catch Throwable _ nil))
          share? (when st-mem-atom (:share-parent-output-session @st-mem-atom))
          [root-agent _] (root-of-agent agent)
          root-aid (:agent-id root-agent)]
      ;; Stamp the sub-agent as :running in the consolidated subagents
      ;; block (and refresh the :st-mem-atom in case it wasn't set when
      ;; agent-created fired). No-op when this sub-agent isn't tracked.
      (update-sub-agent! root-aid agent-id
                         (cond-> {:status :running}
                           st-mem-atom (assoc :st-mem-atom st-mem-atom)))
      (if share?
        ;; share-parent mode — emit the "❯❯ [sub-agent] question" prompt on
        ;; the parent's chat session; iteration output follows via hooks.
        (when-let [parent-sidx (find-session-for-parent agent)]
          (sessions/emit-to-session!
           parent-sidx
           (str "\n" (ansi/style (str "❯❯ [" (name agent-id) "] "
                                      (when (string? input) input))
                                 ansi/bold ansi/bright-cyan))))
        ;; Consolidated mode — route into the root's shared sub-output tab.
        (let [root-chat-sidx (or (some-> (find-session-for-agent root-agent) :id)
                                 (sessions/active-idx))
              shared-sidx (ensure-shared-sub-output-session! root-agent root-chat-sidx)]
          (emit-sub-agent-ask-header! shared-sidx agent-id input))))))

(defn- freeze-pending-iterations!
  "Freeze any `!iteration-blocks` entries belonging to `agent` that never
   received a matching `:agent.iteration/post` event.

   Normal flow: iteration-post-handler swaps `:stage :done` + `:result`
   on the entry, re-renders, calls `iter-sink/freeze-widget!`, and drops
   the entry from `!iteration-blocks`. That happens on EVERY successful
   iteration.

   Abnormal turn endings (Ctrl-C cancel, BT exception, max-iterations
   exhaust, :none-channel-loop-guard early-termination, etc.) can skip
   the matching iteration-post for the currently-running iteration —
   the BT unwinds before it fires. Without finalization the live region
   keeps the `[+]` glyph and the next turn's iteration-pre overwrites
   on top of it, leaving a stale `[+]` block in scrollback below the
   answer box (see commit 8889cf7's symptoms in /tmp/c.txt).

   Stamps `:stage :done` (leaving `:result nil` so the marker resolves
   to the neutral `[●]` — neither success nor failure, just \"stopped\"),
   re-renders, freezes the widget, and drops the entry."
  [agent]
  (let [aid (:agent-id agent)
        keys-to-freeze (filter (fn [[block-aid _rid _iter]] (= block-aid aid))
                               (keys @!iteration-blocks))]
    (doseq [[aid rid iteration :as k] keys-to-freeze]
      (when-let [entry (get @!iteration-blocks k)]
        ;; Only force-finalize entries still in progress; an entry that
        ;; already reached :stage :done via iteration-post-handler would
        ;; have been dissoc'd from this atom in the same swap, so this
        ;; is defensive — but harmless if it happens.
        (when (not= :done (:stage entry))
          (swap! !iteration-blocks update k merge
                 {:stage :done :streaming nil})
          (update-iteration-block! aid rid iteration)
          (let [bid (iteration-block-id aid rid iteration)
                dispose? (boolean (agent/get-config agent :dispose-iteration-block))]
            (try (if dispose?
                   (iter-sink/clear-widget! bid)
                   (iter-sink/freeze-widget! bid))
                 (catch Exception _)))
          (swap! !iteration-blocks dissoc k))))))

(defn agent-suggestion-handler
  "Handler for :agent.suggestion/next-user-prompt. Event: {:agent :prompt :input}.

   Records the agent's self-reported follow-up so the idle input line can offer
   it as a top-priority help tip (and right-arrow can accept it into the
   buffer). Registered with `match-root-agent`, so only root answers feed the
   shared input bar — sub-agent suggestions are filtered out. The tip is shown
   on the next prompt draw (the ask wrapper returns to the input loop right
   after firing this), so no manual redraw is needed here.

   Gated by the `:enable-input-suggestions` config key (default true); when
   off, only the rotating static help tips show.

   Because the turn is dispatched asynchronously, the input loop has already
   redrawn the (static) idle prompt by the time this fires. Repaint it so the
   tip shows immediately — but only when the user is sitting at an empty idle
   prompt (not mid-typing, no popover open), so we never clobber input."
  [{:keys [agent prompt]}]
  (when (agent/get-config agent :enable-input-suggestions)
    ;; Store the follow-up on the root agent's OWN tab (handler is registered
    ;; with match-root-agent), so a background tab's suggestion never clobbers
    ;; the active tab's idle line.
    (let [sidx (session-idx-for-agent agent)]
      (help-tips/set-agent-suggestion! sidx prompt)
      ;; Only repaint the idle prompt when this is the foreground tab (its
      ;; suggestion is what the active idle line would show).
      (when (and (= sidx (sessions/active-idx))
                 (layout/input-active?)
                 (layout/input-empty?)
                 (not (layout/popover-active?)))
        (try (redraw-idle-prompt!) (catch Exception _))))))

(defn ask-post-handler
  "Handler for :agent.ask/post. Event: {:agent :input :result}.

   Renders the answer box for parent agents, after all iteration widgets
   have frozen, and runs the sub-agent block-freeze + per-session
   completion bookkeeping. First freezes any pending iteration widget
   so a cancel / loop-guard / exception path doesn't leave a stale
   `[+]` block hanging below the answer."
  [{:keys [agent result]}]
  (freeze-pending-iterations! agent)
  ;; Parent agent: render answer to the parent's own session, regardless
  ;; of which session is currently active (the user may have switched
  ;; to a sub-agent's :output session by the time the parent finishes).
  ;;
  ;; Force-freeze the consolidated subagents block (if any) BEFORE
  ;; emitting the answer so the answer is the last thing in scrollback,
  ;; not interleaved with subsequent ticker updates from sub-agents that
  ;; haven't yet hit their own ask-post.
  (when (nil? (get-in @(:!state agent) [:runtime :parent-agent]))
    (with-agent-render-session agent
      (let [root-aid (:agent-id agent)]
        (when-let [{:keys [block-id session-idx]} (get @!subagents-blocks root-aid)]
          ;; Force a final settle render before freezing so the in-scrollback
          ;; record reflects the true terminal state (all markers settled),
          ;; even when the user is on a different session right now. `final?
          ;; true` also drops the running-counter header from the frozen
          ;; record (only the per-sub-agent summary lines remain).
          (try (update-subagents-block! root-aid true) (catch Exception _))
          (let [dispose? (boolean (agent/get-config agent :dispose-agent-block))]
            (try (if dispose?
                   (if session-idx
                     (sessions/dispose-live-block-in-session! session-idx block-id)
                     (layout/dispose-live-block! block-id))
                   (if session-idx
                     (sessions/freeze-live-block-in-session! session-idx block-id)
                     (layout/freeze-live-block! block-id)))
                 (catch Exception _)))
          (swap! !subagents-blocks dissoc root-aid)))
      (when-let [st-atom (try (agent/get-bt-st-memory agent) (catch Throwable _ nil))]
        (let [st     @st-atom
              answer (or (:answer st)
                         (when (string? result) result))
              ;; goal-achieved / next-user-prompt now come from ThinkActCode's
              ;; answer channel (the old FinalizeAnswer pass was merged in), so
              ;; they're surfaced unconditionally whenever present.
              goal-achieved (:goal-achieved st)
              ;; acp-agent turns render the full streamed message in the ACP
              ;; transcript block, so the turn-end answer box + goal verdict are
              ;; redundant — suppress them unless :acp-show-final-answer is on.
              hide-final? (and (acp-agent-instance? agent)
                               (not (agent/get-config agent :acp-show-final-answer)))]
          (when (and (string? answer) (not (str/blank? answer)))
            (when (render-active?) (stop-thinking-indicator!))
            (when-not hide-final?
              (emit! (if (quiet?)
                       (fmt/format-answer-plain answer)
                       (fmt/format-answer answer))))
            ;; In :quiet the box-less answer needs a blank line after it —
            ;; prepend one to the first of the goal / next-prompt lines.
            (when (and (some? goal-achieved) (not hide-final?))
              (emit! (str (when (quiet?) "\n") (fmt/format-goal-status goal-achieved))))
            ;; Suggested follow-up (:next-user-prompt). format-next-prompt
            ;; returns nil if blank.
            (when-let [np (fmt/format-next-prompt (:next-user-prompt st))]
              (emit! (str (when (and (quiet?) (nil? goal-achieved)) "\n") np))))))))
  ;; Sub-agent: stamp :done in the consolidated subagents block (handled
  ;; centrally in `mark-sub-agent-done!` — also auto-freezes the block
  ;; if this was the last running sub-agent under the root) and emit the
  ;; sub-agent's answer + completion line on its own :output session
  ;; (the parent session is typically active during a sub-agent run, so
  ;; plain `emit!` would route to the wrong scrollback — route through
  ;; the origin session via `emit-to-session!`).
  (when (get-in @(:!state agent) [:runtime :parent-agent])
    (let [agent-id   (:agent-id agent)
          [root _]   (root-of-agent agent)
          root-aid   (:agent-id root)]
      (mark-sub-agent-done! root-aid agent-id :done)
      (when-let [session (find-session-for-agent agent)]
        (let [sidx    (:id session)
              st-atom (try (agent/get-bt-st-memory agent) (catch Throwable _ nil))
              st      (when st-atom @st-atom)
              answer  (or (:answer st)
                          (when (string? result) result))
              goal-achieved (:goal-achieved st)
              hide-final? (and (acp-agent-instance? agent)
                               (not (agent/get-config agent :acp-show-final-answer)))]
          (when (and (string? answer) (not (str/blank? answer)) (not hide-final?))
            (sessions/emit-to-session! sidx (if (quiet?)
                                              (fmt/format-answer-plain answer)
                                              (fmt/format-answer answer)))
            ;; In :quiet the box-less answer needs a blank line after it —
            ;; prepend one to the goal-status line (mirrors the root path).
            (when (some? goal-achieved)
              (sessions/emit-to-session!
               sidx (str (when (quiet?) "\n") (fmt/format-goal-status goal-achieved)))))
          (sessions/emit-to-session!
           sidx (str (ansi/muted (str "[" (name agent-id) " completed]"))))
          ;; Only mark the session :completed when it's a legacy per-sub-agent
          ;; :output tab (i.e. `:agent` identical to this sub-agent). The
          ;; shared sub-output tab serves all of the root's descendants, so
          ;; it must stay :running until the root chat session closes.
          (when (identical? (:agent session) agent)
            (sessions/update-session! sidx assoc :status :completed)))))))

;; ============================================================================
;; Task Activity Hook Handlers (registered against :task/created / :task/completed)
;; ============================================================================

(defn task-created-handler
  "Handler for :task/created. Event: {:task}.

   Block creation itself is deferred to the :pending → :running transition in
   `handle-tasks-change` (so the user sees a single block render instead of a
   pending-then-running flicker). But THIS event fires synchronously on the
   creating agent's thread, where `*current-agent*` is still bound — the only
   point at which the task's owning session is knowable. (`handle-tasks-change`
   is a global `!tasks` watch with no agent in scope, and the :running flip may
   land on a task-pool thread.) Record task-id → owning TUI session index so
   `create-task-block!` can anchor the block to the agent's session instead of
   whatever session is active when the watch fires. Unresolved owners (e.g. a
   shared-output sub-agent) leave no entry; `create-task-block!` falls back to
   the active session."
  [{:keys [task]}]
  (when-let [sidx (session-idx-for-agent-session-id (agent/get-current-session-id))]
    (swap! !task-owner-session assoc (:id task) sidx)))

(defn task-completed-handler
  "Handler for :task/completed. Event: {:task}.
   Updates the live block to final status and schedules freeze."
  [{:keys [task]}]
  (let [tid (:id task)
        final-status (or (:status task) :completed)]
    ;; Drop the owner-session bridge entry on any terminal task — including
    ;; fast-eval tasks that completed without ever creating a block — so the
    ;; map doesn't grow unbounded.
    (swap! !task-owner-session dissoc tid)
    (when (get @!task-blocks tid)
      (let [output (when-let [ol (:output-lines task)]
                     (if (instance? clojure.lang.IDeref ol) @ol ol))
            last-2 (vec (take-last 2 (or output [])))]
        (swap! !task-blocks update tid merge
               {:status final-status
                :output-lines last-2
                :output-count (count (or output []))})
        (update-task-block! tid)
        (let [bid  (task-block-id tid)
              sidx (:session-idx (get @!task-blocks tid))]
          (future
            (Thread/sleep 2000)
            (finalize-task-block! bid sidx)
            (swap! !task-blocks dissoc tid)))))))

;; ============================================================================
;; Iteration Block Hook Handlers
;;
;; Registered from core.clj (in-process TUI) and tmux_iteration.clj
;; (daemon path) against:
;;   :agent.iteration/pre  /  :agent.iteration/post
;;   :agent.dspy-action/pre  /  :agent.dspy-action/chunk  /  :agent.dspy-action/post
;;   :agent.tool-calls/pre  /  :agent.tool-calls/post
;;   :agent.tool-use/pre  /  :agent.tool-use/post     (per-tool detail)
;;   :agent.code-eval/pre  /  :agent.code-eval/post
;; ============================================================================

(defn- iter-current-key
  "Lookup the current iteration's [aid rid iter] key from agent st-memory.
   Used by handlers that get only the agent (e.g. tool-use/code-eval) and
   need to find which iteration block to mutate. Returns nil when not in
   an iteration."
  [agent]
  (let [aid (:agent-id agent)]
    (when-let [st-atom (try (agent/get-bt-st-memory agent) (catch Throwable _ nil))]
      (let [{:keys [iter-block/repeat-id iter-block/iteration]} @st-atom]
        (when (and aid repeat-id iteration)
          [aid repeat-id iteration])))))

(defn- iter-stamp-current!
  "Record the current iteration coordinates in agent st-memory so handlers
   that don't get :repeat-id / :iteration in their payload (tool-use,
   code-eval) can correlate. Cleared on iteration/post."
  [agent repeat-id iteration]
  (when-let [st-atom (try (agent/get-bt-st-memory agent) (catch Throwable _ nil))]
    (swap! st-atom assoc
           :iter-block/repeat-id repeat-id
           :iter-block/iteration iteration)))

(defn- iter-clear-current!
  [agent]
  (when-let [st-atom (try (agent/get-bt-st-memory agent) (catch Throwable _ nil))]
    (swap! st-atom dissoc :iter-block/repeat-id :iter-block/iteration)))

(defn iteration-pre-handler
  "Handler for :agent.iteration/pre. Event:
   {:agent :iteration :max-iterations :repeat-id}.
   Creates an iteration live block.

   The thinking indicator (singleton :think-block) lives ONE-PER-TURN and
   is sticky-bottom anchored — any newly-created non-sticky block (this
   iter-block, task blocks, sub-agent blocks) is inserted ABOVE it
   automatically by `layout/update-live-block!`. No dispose+recreate dance
   is needed here; the think-block's state (incl. its elapsed-time
   :start-time) persists across every iteration of the turn.

   For an acp-agent instance the turn is one long external ACP stream, not a
   discrete iteration — route to the ACP transcript block instead."
  [{:keys [agent iteration max-iterations repeat-id] :as ev}]
  (if (acp-agent-instance? agent)
    (acp-create-block! ev)
    (let [_ (stamp-think-activity!
             agent
             (str "Iter " iteration (when max-iterations (str "/" max-iterations))))
          aid (:agent-id agent)
          rid (str (or repeat-id "_"))
          sidx (:id (find-session-for-agent agent))]
      ;; The think-block lives ONE-PER-TURN, not per-iter. Its `!think-block`
      ;; state (incl. :start-time used by the elapsed counter) was stamped
      ;; once at start-thinking-indicator! time and persists across every
      ;; iteration of this turn. The iter-block we're about to create is
      ;; non-sticky; layout/update-live-block! inserts it ABOVE the
      ;; sticky-bottom think-block automatically — no dispose+recreate dance
      ;; needed here.
      (iter-stamp-current! agent rid iteration)
      (swap! !iteration-blocks assoc [aid rid iteration]
             {:agent-id aid
              :repeat-id rid
              :iteration iteration
              :max-iterations max-iterations
              :stage :pre
              :reasoning nil
              :streaming nil
              :tool-batch []
              :code nil
              :code-output nil
              :eval-display nil
              :eval-section-lines nil
              :usage nil
              :result nil
              :session-idx sidx
              :start-ms (System/currentTimeMillis)})
      (update-iteration-block! aid rid iteration)
      ;; Refresh the (N.Ns) elapsed-time counter every 1s while the iter is
      ;; non-:done. Self-stops when no running iters remain. Idempotent —
      ;; a single ticker covers all iterations across all sessions.
      (start-iteration-block-ticker!))))

(defn iteration-post-handler
  "Handler for :agent.iteration/post. Event:
   {:agent :iteration :max-iterations :repeat-id :result :observation}.
   Sets the final result, freezes the live block immediately, and removes the
   in-memory entry. Lines stay in scrollback as a frozen record.

   After the widget freezes, emits the iteration's observation as a separate
   scrollback line (when present) — this used to be rendered by the legacy
   `:display-stage :observe` watch branch and has no surface inside the
   iter-block widget. The goal-achieved verdict + suggested next-user-prompt
   are NO LONGER shown per-iteration — they surface once per turn in
   `ask-post-handler` (the turn-completion handler) instead.

   When this iteration belongs to a sub-agent (an entry under its
   root's consolidated subagents block, established by
   `agent-created-handler`), also bumps that entry's `:iter-rollup` —
   total iterations, total tokens, and the last iteration's outcome —
   so the one-line summary in the subagents block reflects the latest
   completed iteration."
  [{:keys [agent iteration repeat-id result observation notices] :as ev}]
  ;; acp-agent instances freeze the ACP transcript block instead; they never
  ;; populate !iteration-blocks, so the body below is a no-op for them
  ;; (`outgoing` is nil) — the guard just adds the ACP freeze.
  (when (acp-agent-instance? agent)
    (acp-freeze-block! ev))
  (let [aid (:agent-id agent)
        rid (str (or repeat-id "_"))
        k [aid rid iteration]
        outgoing (get @!iteration-blocks k)
        result-kw (cond
                    (= result :success) :success
                    (= result :failure) :failure
                    :else (when result :success))
        iter-tokens (or (get-in outgoing [:usage :total]) 0)]
    (when outgoing
      (swap! !iteration-blocks update k merge
             (cond-> {:stage :done :streaming nil :result result-kw
                      :end-ms (System/currentTimeMillis)}
               (not (str/blank? notices)) (assoc :notices notices)))
      (update-iteration-block! aid rid iteration)
      (let [dispose? (boolean (agent/get-config agent :dispose-iteration-block))
            block-id (iteration-block-id aid rid iteration)
            origin-idx (:session-idx outgoing)
            active-idx (sessions/active-idx)]
        (if dispose?
          ;; Dispose mode: drop the widget AND its lines from the active
          ;; session's scrollback; also clear any buffered copy in the origin
          ;; session so a switch-back doesn't reveal a stale record.
          (do (iter-sink/clear-widget! block-id)
              (when (and origin-idx (not= origin-idx active-idx))
                (sessions/dispose-live-block-in-session! origin-idx block-id)))
          ;; Freeze mode (default): widget freezes into scrollback. If the
          ;; iteration's origin session is NOT the active session, the
          ;; live-block sink (which writes to the global !scrollback = active
          ;; session's scrollback) deliberately skipped every render to avoid
          ;; leaking lines into the wrong session. Buffer the final frozen
          ;; lines into the origin session's scrollback so they're visible
          ;; when the user switches over — UNLESS the live-block was already
          ;; rendered to origin's saved scrollback during the part of its run
          ;; when origin == active (i.e. the user was on origin at iter-pre,
          ;; then switched away mid-iter). In that case the prior `[+]`/`[…]`
          ;; snapshot is already at a known position in origin's saved
          ;; scrollback; appending the final state would duplicate.
          (let [final-state (cond-> (assoc outgoing :stage :done :streaming nil :result result-kw)
                              (not (str/blank? notices)) (assoc :notices notices))
                already-buffered? (some-> (sessions/get-session origin-idx)
                                          :live-blocks
                                          (get block-id))]
            (iter-sink/freeze-widget! block-id)
            (when (and origin-idx
                       (not= origin-idx active-idx)
                       (not already-buffered?))
              (let [lines (render-iteration-block-lines final-state \space)]
                (when (seq lines)
                  (sessions/emit-to-session! origin-idx
                                             (clojure.string/join "\n" lines)))))
            ;; Either way, drop the (now-frozen) block from origin's saved
            ;; :live-blocks so a later switch-back doesn't restore a stale
            ;; entry pointing at the live region.
            (when (and origin-idx (not= origin-idx active-idx))
              (sessions/update-session! origin-idx update :live-blocks dissoc block-id)))))
      (swap! !iteration-blocks dissoc k))
    ;; Surface the iteration's observation as a separate scrollback line,
    ;; matching the legacy `:observe` watch branch. Emitted after the widget
    ;; freezes so it appears just below the frozen iteration record. Route
    ;; through the origin session so a sub-agent's line lands in the sub-agent's
    ;; :output session even when the parent session is currently active.
    ;; Suppressed in :quiet display-format (answer-only mode). The goal-achieved
    ;; verdict + next-user-prompt are surfaced once per turn by ask-post-handler.
    (when-not (quiet?)
      (let [origin-idx (:session-idx outgoing)]
        (when (and observation (not (clojure.string/blank? (str observation))))
          (emit! (fmt/format-observation observation) origin-idx))))
    ;; Sub-agent rollup: when this iteration belongs to a sub-agent
    ;; tracked in the consolidated subagents block (under its root),
    ;; bump that entry's :iter-rollup so the one-line summary stays
    ;; current. Top-level (root) agents have no entry here — their own
    ;; iteration block in scrollback is the source of truth.
    (when (get-in @(:!state agent) [:runtime :parent-agent])
      (let [[root _]   (root-of-agent agent)
            root-aid   (:agent-id root)
            sub-state  (get-in @!subagents-blocks
                               [root-aid :sub-agents aid])]
        (when sub-state
          (let [updated (-> (or (:iter-rollup sub-state) {})
                            (assoc :last-iteration iteration)
                            (assoc :last-result result-kw)
                            (update :total-iterations (fnil inc 0))
                            (update :total-tokens (fnil + 0) iter-tokens))]
            (update-sub-agent! root-aid aid {:iter-rollup updated})))))
    (iter-clear-current! agent)))

(defn dspy-pre-handler
  "Handler for :agent.dspy-action/pre. Stamps stage :think on the current iter."
  [{:keys [agent]}]
  (stamp-think-activity! agent "Reasoning…")
  (when-let [[aid rid iter] (iter-current-key agent)]
    (when (get @!iteration-blocks [aid rid iter])
      (swap! !iteration-blocks update [aid rid iter]
             merge {:stage :think :streaming nil})
      (update-iteration-block! aid rid iter))))

(defn dspy-chunk-handler
  "Handler for :agent.dspy-action/chunk. Appends the chunk's accumulated text
   to the current iteration's :streaming field.

   acp-agent instances route the delta chunk into the ACP transcript block's
   thought/message segments instead (the body below no-ops — no iteration block)."
  [{:keys [agent accumulated] :as ev}]
  (when (acp-agent-instance? agent)
    (acp-append-chunk! ev))
  (when-let [[aid rid iter] (iter-current-key agent)]
    (when (get @!iteration-blocks [aid rid iter])
      (swap! !iteration-blocks update [aid rid iter]
             assoc :streaming accumulated)
      (update-iteration-block! aid rid iter))))

(defn dspy-post-handler
  "Handler for :agent.dspy-action/post. Records reasoning + usage; clears streaming.

   acp-agent instances fold usage into the ACP block's token counter instead."
  [{:keys [agent reasoning usage] :as ev}]
  (when (acp-agent-instance? agent)
    (acp-add-usage! ev))
  (when-let [[aid rid iter] (iter-current-key agent)]
    (when (get @!iteration-blocks [aid rid iter])
      (swap! !iteration-blocks update [aid rid iter]
             (fn [s]
               (let [prev-usage (:usage s)
                     in  (or (:input-tokens usage) (:input usage) 0)
                     out (or (:output-tokens usage) (:output usage) 0)
                     tot (+ in out)
                     merged-usage {:in (+ (or (:in prev-usage) 0) in)
                                   :out (+ (or (:out prev-usage) 0) out)
                                   :total (+ (or (:total prev-usage) 0) tot)}]
                 (cond-> (assoc s :streaming nil)
                   reasoning (assoc :reasoning reasoning)
                   (or (pos? in) (pos? out)) (assoc :usage merged-usage)))))
      (update-iteration-block! aid rid iter))))

(defn tool-calls-pre-handler
  "Handler for :agent.tool-calls/pre. Stamps stage :tools, resets tool-batch."
  [{:keys [agent]}]
  (stamp-think-activity! agent "Calling tools…")
  (when-let [[aid rid iter] (iter-current-key agent)]
    (when (get @!iteration-blocks [aid rid iter])
      (swap! !iteration-blocks update [aid rid iter]
             merge {:stage :tools :tool-batch []})
      (update-iteration-block! aid rid iter))))

(defn tool-calls-post-handler
  "Handler for :agent.tool-calls/post. No per-tool changes — the per-tool
   handlers already populated :tool-batch. Just signals batch end.

   For sub-agents, also accumulates this batch's tool usage into the
   consolidated subagents block (count + recent names)."
  [{:keys [agent calls]}]
  (when-let [[aid rid iter] (iter-current-key agent)]
    (when (get @!iteration-blocks [aid rid iter])
      (update-iteration-block! aid rid iter)))
  (when (seq calls)
    (let [names (keep #(some-> (:tool-name %) str) calls)]
      (accumulate-sub-activity!
       agent
       (fn [s] {:tools-used   (+ (:tools-used s 0) (count calls))
                :recent-tools (keep-last (:recent-tools s) names 3)})))))

(defn- iter-tool-suppressed?
  "RLM caveat: when an iteration is in :code stage, tool-use events come
   from inside the SCI sandbox and would clutter the [Tools] section. Skip
   per-tool rendering in that case."
  [iter-state]
  (= :code (:stage iter-state)))

(defn upsert-tool-call
  "Add or merge a `:called` entry into `tool-batch` for one
   `:agent.tool-use/pre` event. A streaming ACP backend can emit
   `tool_call` twice for a single `call-id` — a placeholder with empty
   input, then the real input — so a blind append would show two lines
   (an empty `name({})` that reaches `:done` and a full `name(args)`
   stuck at `:called`). When an entry with the same non-nil `call-id`
   already exists, merge into it — take the latest `:name` and any
   non-empty `:args` — instead of appending a duplicate. New / nil
   call-ids append as before, so the single-emit coact path is unchanged."
  [tool-batch {:keys [call-id tool-name args now]}]
  (let [tb  (or tool-batch [])
        idx (when call-id
              (some (fn [[i e]] (when (= (:call-id e) call-id) i))
                    (map-indexed vector tb)))]
    (if idx
      (update tb idx (fn [e]
                       (cond-> (assoc e :name (str tool-name))
                         (seq args) (assoc :args args))))
      (conj tb {:call-id  call-id
                :name     (str tool-name)
                :args     args
                :status   :called
                :start-ms now}))))

(defn tool-use-pre-handler
  "Handler for :agent.tool-use/pre. Upserts a :called entry into the current
   iteration's :tool-batch (unless suppressed for code-stage).

   acp-agent instances push the tool segment onto the ACP transcript block
   instead (the body below no-ops — no iteration block)."
  [{:keys [agent tool-name args call-id] :as ev}]
  (when (acp-agent-instance? agent)
    (acp-tool-pre! ev))
  (stamp-think-activity! agent (str "→ " tool-name))
  (when-let [[aid rid iter] (iter-current-key agent)]
    (let [k [aid rid iter]
          state (get @!iteration-blocks k)]
      (when (and state (not (iter-tool-suppressed? state)))
        (swap! !iteration-blocks update-in [k :tool-batch]
               upsert-tool-call {:call-id call-id :tool-name tool-name :args args
                                 :now (System/currentTimeMillis)})
        (update-iteration-block! aid rid iter)))))

(def ^:private tool-result-body-cap
  "Max chars of a successful tool result stashed for the iteration-block
   `Result` box. The display-block collapses long content visually; this
   bounds the raw string held in state / written to the file-backed
   provider so a multi-MB blob can't blow up memory."
  20000)

(defn- tool-result->body
  "Stringify a tool result for the iteration-block `Result` / `Error` box.
   Returns a bounded string, or nil when there's nothing worth showing
   (nil / boolean / empty map / blank string).

   A map's `:answer` is surfaced directly (mirrors `render/tool-post-line`).
   A normal (non-error) map result — e.g. `bash`'s `{:status … :exit-code …
   :output …}` — renders as `name: value` lines via `render-kv-body`, the
   same readable key-value treatment as the `Call` box (mirrors
   `iter-args-body`) rather than a single cramped `pr-str` blob. An ERROR
   map (`:error` / `:error-message`, matching `tool-use-post-handler`'s
   `error?`) stays a compact `pr-str` so the red `Error` box styles it
   uniformly instead of nesting the key-value styling under the red wrap.
   Anything else is `pr-str`'d."
  [result]
  (let [error-map? (and (map? result)
                        (or (some? (:error result))
                            (some? (:error-message result))))
        raw (cond
              (string? result)                     result
              (and (map? result) (:answer result)) (str (:answer result))
              (or (nil? result) (boolean? result)) nil
              (and (map? result) (empty? result))  nil
              (and (map? result) (not error-map?)) (render-kv-body result)
              :else                                 (pr-str result))]
    (when (and raw (not (str/blank? (str raw))))
      (let [s (str raw)]
        (if (> (count s) tool-result-body-cap)
          (str (subs s 0 tool-result-body-cap) "\n… [truncated]")
          s)))))

(defn tool-use-post-handler
  "Handler for :agent.tool-use/post. Locates the matching tool-batch entry
   by :call-id and updates its status / timing / result-chars / error-msg /
   result-body (the boxed Result/Error section body).

   acp-agent instances resolve the matching ACP transcript tool segment instead."
  [{:keys [agent tool-name call-id result] :as ev}]
  (when (acp-agent-instance? agent)
    (acp-tool-post! ev))
  (when-let [[aid rid iter] (iter-current-key agent)]
    (let [k [aid rid iter]
          state (get @!iteration-blocks k)]
      (when (and state (not (iter-tool-suppressed? state)))
        (let [now (System/currentTimeMillis)
              ;; A tool signals failure two ways: a THROWN exception (wrapped by
              ;; call-tool into `:error-message`) or a RETURNED error map
              ;; (`:error`, the common deftool convention). Flag both so the
              ;; head line shows a red `✗ error` and the body renders in the
              ;; red `Error` box — not a neutral green `done` + `Result` box.
              error? (and (map? result)
                          (or (some? (:error-message result)) (some? (:error result))))
              error-msg (when error? (str (or (:error-message result) (:error result))))
              result-body (tool-result->body result)
              chars (try (count (pr-str result)) (catch Exception _ nil))
              tb (or (:tool-batch state) [])
              idx (or (some (fn [[i e]]
                              (when (= (:call-id e) call-id) i))
                            (map-indexed vector tb))
                      ;; Fallback: match by name on the last :called entry
                      (some (fn [[i e]]
                              (when (and (= (:name e) (str tool-name))
                                         (= (:status e) :called))
                                i))
                            (reverse (map-indexed vector tb))))]
          (when idx
            (swap! !iteration-blocks update-in [k :tool-batch idx]
                   merge {:status (if error? :error :done)
                          :end-ms now
                          :result-chars chars
                          :error-msg error-msg
                          :result-body result-body})
            (update-iteration-block! aid rid iter)))))))

;; ============================================================================
;; ACP transcript block
;;
;; An acp-agent (backend :claude-code / :gemini / :codex / :stub) hands the whole
;; turn to an external ACP loop that STREAMS an interleaved sequence of reasoning
;; (`agent_thought_chunk`), assistant message (`agent_message_chunk`) and tool
;; calls within a SINGLE BT iteration (its tree is `:repeat max-n=1`). The ReAct
;; iteration block — one discrete think→act→observe step under an `Iteration N/M`
;; header — is a poor fit: it flattens the interleave into a Think blob + tool
;; list and mislabels the turn.
;;
;; This block renders the ACP event stream chronologically as an ordered vector
;; of `:segments` (`:thought` | `:message` | `:tool`) under a backend/model
;; header. It is driven by the SAME hooks the acp-agent fires (iteration/pre|post,
;; dspy-action/chunk|post, tool-use/pre|post) — the handlers above route here via
;; `acp-agent-instance?`. Keyed like the iteration block
;; ([agent-id repeat-id iteration]); reuses the iteration sink, spinner,
;; `render-iter-tool-line`, `tool-result->body`, and the same background-session
;; buffering dance on freeze.
;; ============================================================================

(defonce !acp-blocks (atom {}))
(defonce ^:private !acp-ticker-thread (atom nil))

;; Live message-tail / thought caps. `:acp-message-max-lines` (config) overrides
;; the message cap per agent; thoughts use a tighter fixed cap since reasoning is
;; transient context, not the deliverable.
(def ^:private acp-thought-max-lines 4)

(defn- acp-block-id
  "Live block key for an ACP turn."
  [agent-id repeat-id iteration]
  (keyword "acp-block"
           (str (clojure.core/name agent-id) ":"
                (clojure.core/name (or repeat-id :_)) ":" iteration)))

(defn- acp-wrap-lines
  "Wrap `text` to `cols` display columns. When `collapse-ws?`, internal
   whitespace runs collapse to single spaces (right for streamed reasoning);
   otherwise newlines are preserved and each line wrapped independently (right
   for assistant prose). Returns plain (unstyled) wrapped lines."
  [text cols collapse-ws?]
  (let [cols (max 1 cols)]
    (if collapse-ws?
      (let [t (-> text (str/replace #"\s+" " ") str/trim)]
        (if (str/blank? t) [] (wrap-snippet-to-width t cols)))
      (->> (str/split-lines text)
           (mapcat (fn [ln] (if (str/blank? ln) [""] (wrap-snippet-to-width ln cols))))
           vec))))

(defn- acp-prefixed-block
  "Render `text` as a live-block section with a styled `head-prefix` on the
   first line and continuation lines indented under it by the prefix's display
   width. Wrapped to `cols`; the first line is always kept, and the continuation
   lines are TAIL-capped so `max-lines` total show (recent content — right for a
   streaming tail), with a dim `[-N lines]` indicator when truncated. Mirrors the
   `Think:` section of `render-iteration-block-lines`.

   `text-style-fn` styles the wrapped body; `prefix-style-fn` styles the (plain)
   head-prefix. Returns a vector of ANSI line strings, or [] when text is blank."
  [text {:keys [head-prefix cols max-lines collapse-ws? text-style-fn prefix-style-fn]
         :or {head-prefix "" cols 80 max-lines 10 text-style-fn identity
              prefix-style-fn identity}}]
  (let [head-w (count head-prefix)
        cont   (apply str (repeat head-w \space))
        wrapped (acp-wrap-lines text (max 10 (- cols head-w)) collapse-ws?)]
    (if (empty? wrapped)
      []
      (let [head-line  (str (prefix-style-fn head-prefix) (text-style-fn (first wrapped)))
            extras     (vec (rest wrapped))
            extras-cap (max 0 (dec max-lines))]
        (if (<= (count extras) extras-cap)
          (vec (cons head-line (map #(str cont (text-style-fn %)) extras)))
          (let [hidden      (- (count extras) extras-cap)
                kept        (subvec extras (- (count extras) extras-cap))
                indicator   (ansi/style (str "[-" hidden " lines] ") ansi/dim)
                first-extra (str cont indicator (text-style-fn (first kept)))
                rest-extras (map #(str cont (text-style-fn %)) (rest kept))]
            (vec (concat [head-line first-extra] rest-extras))))))))

(defn- render-acp-header
  "One-line header: `[<marker>] <backend> · <model>  (<elapsed>, <tok>)`. Marker
   is a live spinner while running, `●` when done with no verdict, `✓`/`✗` on
   success/failure."
  [{:keys [backend model-label usage result stage start-ms end-ms]} spinner-char]
  (let [marker (case result
                 :success (ansi/iter-marker-success "✓")
                 :failure (ansi/iter-marker-failure "✗")
                 (if (= stage :done)
                   (ansi/iter-marker-done "●")
                   (ansi/spinner-active (str spinner-char))))
        label (str (clojure.core/name (or backend :acp))
                   (when (and model-label (not (str/blank? (str model-label))))
                     (str " · " model-label)))
        usage-tot (:total usage)
        elapsed-str (when start-ms
                      (format-iter-elapsed (- (or end-ms (System/currentTimeMillis))
                                              start-ms)))
        meta (str/join ", "
                       (remove str/blank?
                               [(or elapsed-str "")
                                (when usage-tot (format "%d tok" usage-tot))]))]
    (str (ansi/muted "[") marker (ansi/muted "] ")
         (ansi/iter-label label)
         (when (seq meta) (str "  " (ansi/muted (str "(" meta ")")))))))

(defn- acp-message-lines
  "Render an ACP `:message` segment as **markdown** (headers, bold/italic/inline
   code, code fences, ul/ol lists, blockquotes, hr, GFM tables) via the shared
   `fmt/render-markdown` — the same renderer `format-answer` uses — indented two
   columns under the block. Assistant messages arrive as markdown; rendering them
   raw would leak literal `**bold**` / `#` / fences.

   Tail-capped to `max-lines`: when the message overflows, a dim `[-N lines]`
   indicator replaces the elided head and the most-recent lines are kept (recent
   content wins for a streaming tail). Falls back to plain wrapped text if
   markdown rendering throws (e.g. on a mid-stream partial fence)."
  [text cols max-lines]
  (let [indent "  "
        w      (max 10 (- cols 2))
        md     (try (fmt/render-markdown text w)
                    (catch Throwable _
                      (wrap-snippet-to-width (str/replace (str text) #"\s+" " ") w)))
        lines  (mapv #(str indent %) md)]
    (cond
      (empty? lines)              []
      (<= (count lines) max-lines) lines
      :else
      (let [keep   (max 1 (dec max-lines))
            hidden (- (count lines) keep)
            tail   (subvec lines (- (count lines) keep))]
        (into [(str indent (ansi/style (str "[-" hidden " lines]") ansi/dim))] tail)))))

(defn- render-acp-block-lines
  "Build ANSI lines for an ACP transcript block: the header followed by its
   `:segments` rendered IN ORDER — dim `● Thinking:` reasoning, the streaming
   assistant message (markdown-rendered, tail-capped to `:message-max-lines`),
   and tool calls via the shared `render-iter-tool-line`. In :quiet
   display-format only the message segments render (answer-only markdown, no
   header/thoughts/tools)."
  [{:keys [segments show-thoughts? message-max-lines] :as state} spinner-char]
  (let [cols (or (:cols @layout/!layout) 80)]
    (if (quiet?)
      (vec (mapcat
            (fn [seg]
              (when (= :message (:type seg))
                (acp-message-lines (:text seg) cols (or message-max-lines 100))))
            segments))
      (let [header (render-acp-header state spinner-char)
            seg-lines
            (mapcat
             (fn [seg]
               (case (:type seg)
                 :thought (when show-thoughts?
                            (acp-prefixed-block (:text seg)
                                                {:head-prefix "  ● Thinking: " :cols cols
                                                 :max-lines acp-thought-max-lines
                                                 :collapse-ws? true
                                                 :text-style-fn ansi/muted
                                                 :prefix-style-fn ansi/muted}))
                 :message (acp-message-lines (:text seg) cols (or message-max-lines 100))
                 :tool    (render-iter-tool-line seg)
                 nil))
             segments)]
        (vec (cons header seg-lines))))))

(defn- update-acp-block!
  "Re-render an ACP block through the iteration sink, or into the origin
   session's saved scrollback when that session is backgrounded (same rule as
   `update-iteration-block!`)."
  [agent-id repeat-id iteration]
  (when-let [state (get @!acp-blocks [agent-id repeat-id iteration])]
    (let [origin-idx   (:session-idx state)
          block-id     (acp-block-id agent-id repeat-id iteration)
          spinner-char (nth subagents-spinner-frames @!subagents-spinner-idx)
          lines        (render-acp-block-lines state spinner-char)]
      (when (seq lines)
        (if (or (nil? origin-idx) (= origin-idx (sessions/active-idx)))
          (iter-sink/write-widget! block-id lines)
          (sessions/update-live-block-in-session! origin-idx block-id lines))))))

(defn- start-acp-block-ticker!
  "Refresh ACP block elapsed/spinner every 1s while any block is still running.
   Self-stops when none remain. Idempotent — one ticker covers all ACP blocks."
  []
  (when-not @!acp-ticker-thread
    (let [t (Thread.
             (fn []
               (try
                 (loop []
                   (let [blocks @!acp-blocks
                         active (filterv #(not= :done (:stage (val %))) blocks)]
                     (when (seq active)
                       (doseq [[[aid rid iter] _] active]
                         (try (update-acp-block! aid rid iter) (catch Exception _)))
                       (Thread/sleep (long 1000))
                       (recur))))
                 (catch InterruptedException _))
               (reset! !acp-ticker-thread nil)))]
      (.setDaemon t true)
      (.setName t "acp-block-ticker")
      (.start t)
      (reset! !acp-ticker-thread t))))

;; --- Pure segment-vector transforms ----------------------------------------

(defn- acp-append-text
  "Append streamed `text` of `kind` (:thought | :message) to `segments`.
   Coalesces into the trailing segment when it's the same kind, else opens a new
   one — preserving the chronological think→tool→think→message interleave."
  [segments kind text]
  (let [segments (or segments [])
        last-idx (dec (count segments))
        last-seg (when (>= last-idx 0) (nth segments last-idx))]
    (if (and last-seg (= (:type last-seg) kind))
      (update segments last-idx update :text str text)
      (conj segments {:type kind :text text}))))

(defn- acp-upsert-tool
  "Push or merge a `:tool` segment for a tool-use/pre event. Mirrors
   `upsert-tool-call`: a streaming ACP backend can emit `tool_call` twice per
   call-id (placeholder then real input), so an existing same-call-id segment is
   merged rather than duplicated."
  [segments {:keys [call-id tool-name args now]}]
  (let [segments (or segments [])
        idx (when call-id
              (some (fn [[i s]] (when (and (= (:type s) :tool)
                                           (= (:call-id s) call-id)) i))
                    (map-indexed vector segments)))]
    (if idx
      (update segments idx (fn [s]
                             (cond-> (assoc s :name (str tool-name))
                               (seq args) (assoc :args args))))
      (conj segments {:type :tool :call-id call-id :name (str tool-name)
                      :args args :status :called :start-ms now}))))

(defn- acp-resolve-tool
  "Merge a tool-use/post outcome into the matching `:tool` segment (by call-id,
   falling back to the last `:called` segment of the same name)."
  [segments {:keys [call-id tool-name status end-ms result-chars error-msg result-body]}]
  (let [segments (or segments [])
        idx (or (some (fn [[i s]] (when (and (= (:type s) :tool)
                                             (= (:call-id s) call-id)) i))
                      (map-indexed vector segments))
                (some (fn [[i s]] (when (and (= (:type s) :tool)
                                             (= (:name s) (str tool-name))
                                             (= (:status s) :called)) i))
                      (reverse (map-indexed vector segments))))]
    (if idx
      (update segments idx merge {:status status :end-ms end-ms
                                  :result-chars result-chars
                                  :error-msg error-msg :result-body result-body})
      segments)))

;; --- Hook entry points (routed to from the iteration handlers) --------------

(defn- acp-agent-instance?
  "True when `agent` is an acp-agent instance (its instance-id is namespaced
   `acp-agent`, e.g. :acp-agent/silver-otter-7). Mirrors
   `acp-agent/acp-instance?` without a hard dependency on that ns."
  [agent]
  (let [aid (:agent-id agent)]
    (and (keyword? aid) (= "acp-agent" (namespace aid)))))

;; Agent-ids already warned about an unmatched `-m` model, so the notice fires
;; once per agent (not every turn).
(defonce ^:private !acp-model-notified (atom #{}))

(defn- maybe-notify-unmatched-model!
  "When the ACP session resolved the requested `-m` model to something else
   because it didn't match any advertised model, surface a one-time visible
   notice naming the served model and the available ids — the resolution is
   otherwise only a silent log warning (`::acp-model-unmatched`) and the flag
   would appear to have taken. Reads the descriptor, which exists only once the
   session has connected (first turn opens it lazily), so this fires from the
   turn AFTER the first — the status bar already shows the served model meanwhile."
  [agent aid]
  (let [desc (try (agent/descriptor agent) (catch Exception _ nil))]
    (when (and (false? (:model-matched? desc))
               (not (contains? @!acp-model-notified aid)))
      (swap! !acp-model-notified conj aid)
      (let [avail (seq (:available-models desc))]
        (layout/write-output!
         (str (ansi/muted
               (str "⚠ acp: model '" (:model-label desc) "' unavailable — serving '"
                    (:effective-model desc) "'"
                    (when avail (str " (available: " (clojure.string/join ", " avail) ")"))
                    "."))
              "\n"))))))

(defn- acp-create-block!
  "Handler for :agent.iteration/pre on an acp instance — creates the ACP
   transcript block for this turn and starts the ticker."
  [{:keys [agent iteration repeat-id]}]
  (let [aid     (:agent-id agent)
        rid     (str (or repeat-id "_"))
        sidx    (:id (find-session-for-agent agent))
        backend (or (agent/get-config agent :acp-backend) :acp)]
    (maybe-notify-unmatched-model! agent aid)
    (stamp-think-activity! agent (str (clojure.core/name backend) " …"))
    (iter-stamp-current! agent rid iteration)
    (swap! !acp-blocks assoc [aid rid iteration]
           {:agent-id          aid
            :repeat-id         rid
            :iteration         iteration
            :backend           backend
            ;; Same arrow-aware served-model display as the status bar (shared
            ;; `sessions/acp-model-part`), so the header and bar always agree:
            ;; `opus→default` when a matched request was renamed, plain served id
            ;; otherwise, requested string before the session connects.
            :model-label       (sessions/acp-model-part agent)
            :show-thoughts?    (not= false (agent/get-config agent :acp-show-thoughts))
            :message-max-lines (or (agent/get-config agent :acp-message-max-lines) 100)
            :stage             :running
            :result            nil
            :segments          []
            :usage             nil
            :session-idx       sidx
            :start-ms          (System/currentTimeMillis)})
    (update-acp-block! aid rid iteration)
    (start-acp-block-ticker!)))

(defn- acp-append-chunk!
  "Handler for :agent.dspy-action/chunk on an acp instance. Routes the DELTA
   `chunk` into a :thought or :message segment by the translated `:meta :kind`."
  [{:keys [agent chunk meta]}]
  (when (and (string? chunk) (seq chunk))
    (when-let [[aid rid iter] (iter-current-key agent)]
      (when (get @!acp-blocks [aid rid iter])
        (let [kind (if (= :thought (:kind meta)) :thought :message)]
          (swap! !acp-blocks update-in [[aid rid iter] :segments]
                 acp-append-text kind chunk)
          (update-acp-block! aid rid iter))))))

(defn- acp-add-usage!
  "Handler for :agent.dspy-action/post on an acp instance — folds usage into the
   header's running token counter."
  [{:keys [agent usage]}]
  (when-let [[aid rid iter] (iter-current-key agent)]
    (when (get @!acp-blocks [aid rid iter])
      (swap! !acp-blocks update-in [[aid rid iter] :usage]
             (fn [prev]
               (let [in  (or (:input-tokens usage) (:input usage) 0)
                     out (or (:output-tokens usage) (:output usage) 0)]
                 {:in    (+ (or (:in prev) 0) in)
                  :out   (+ (or (:out prev) 0) out)
                  :total (+ (or (:total prev) 0) in out)})))
      (update-acp-block! aid rid iter))))

(defn- acp-tool-pre!
  "Handler for :agent.tool-use/pre on an acp instance — push/merge a :tool
   segment in chronological order."
  [{:keys [agent tool-name args call-id]}]
  (stamp-think-activity! agent (str "→ " tool-name))
  (when-let [[aid rid iter] (iter-current-key agent)]
    (when (get @!acp-blocks [aid rid iter])
      (swap! !acp-blocks update-in [[aid rid iter] :segments]
             acp-upsert-tool {:call-id call-id :tool-name tool-name :args args
                              :now (System/currentTimeMillis)})
      (update-acp-block! aid rid iter))))

(defn- acp-tool-post!
  "Handler for :agent.tool-use/post on an acp instance — resolve the tool
   segment's status / result. Reuses `tool-result->body` and the same error
   detection as `tool-use-post-handler`."
  [{:keys [agent tool-name call-id result]}]
  (when-let [[aid rid iter] (iter-current-key agent)]
    (when (get @!acp-blocks [aid rid iter])
      (let [now         (System/currentTimeMillis)
            error?      (and (map? result)
                             (or (some? (:error-message result)) (some? (:error result))))
            error-msg   (when error? (str (or (:error-message result) (:error result))))
            result-body (tool-result->body result)
            chars       (try (count (pr-str result)) (catch Exception _ nil))]
        (swap! !acp-blocks update-in [[aid rid iter] :segments]
               acp-resolve-tool {:call-id call-id :tool-name tool-name
                                 :status (if error? :error :done)
                                 :end-ms now :result-chars chars
                                 :error-msg error-msg :result-body result-body})
        (update-acp-block! aid rid iter)))))

(defn- acp-freeze-block!
  "Handler for :agent.iteration/post on an acp instance — set the final result,
   freeze the widget into scrollback, and drop the in-memory entry. Mirrors
   `iteration-post-handler`'s freeze + background-session buffering."
  [{:keys [agent iteration repeat-id result]}]
  (let [aid       (:agent-id agent)
        rid       (str (or repeat-id "_"))
        k         [aid rid iteration]
        outgoing  (get @!acp-blocks k)
        result-kw (cond
                    (= result :success) :success
                    (= result :failure) :failure
                    :else (when result :success))]
    (when outgoing
      (swap! !acp-blocks update k merge
             {:stage :done :result result-kw :end-ms (System/currentTimeMillis)})
      (update-acp-block! aid rid iteration)
      (let [dispose?   (boolean (agent/get-config agent :dispose-acp-block))
            block-id   (acp-block-id aid rid iteration)
            origin-idx (:session-idx outgoing)
            active-idx (sessions/active-idx)]
        (if dispose?
          (do (iter-sink/clear-widget! block-id)
              (when (and origin-idx (not= origin-idx active-idx))
                (sessions/dispose-live-block-in-session! origin-idx block-id)))
          (let [final-state (assoc outgoing :stage :done :result result-kw
                                   :end-ms (System/currentTimeMillis))
                already-buffered? (some-> (sessions/get-session origin-idx)
                                          :live-blocks (get block-id))]
            (iter-sink/freeze-widget! block-id)
            (when (and origin-idx
                       (not= origin-idx active-idx)
                       (not already-buffered?))
              (let [lines (render-acp-block-lines final-state \space)]
                (when (seq lines)
                  (sessions/emit-to-session! origin-idx (str/join "\n" lines)))))
            (when (and origin-idx (not= origin-idx active-idx))
              (sessions/update-session! origin-idx update :live-blocks dissoc block-id)))))
      (swap! !acp-blocks dissoc k))
    (iter-clear-current! agent)))

(defn- accumulate-eval-entry
  "Slot a code-eval entry into the iteration-block's `:eval-display`
   vector. Parallel mode places at the supplied index (padding nil
   slots as needed); sequential pre appends a new entry; sequential
   post updates the last entry. Returns the new state."
  [st phase parallel? batch-index code result output error]
  (let [display (or (:eval-display st) [])
        idx (cond
              parallel?       (or batch-index 0)
              (= phase :pre)  (count display)
              ;; sequential :post → update the last entry (the matching :pre
              ;; appended it just before us in the same thread).
              :else           (max 0 (dec (count display))))
        padded (if (>= idx (count display))
                 (vec (concat display (repeat (- (inc idx) (count display)) nil)))
                 display)
        merged (merge (get padded idx {})
                      (cond-> {:code code}
                        (= phase :post) (assoc :result result
                                               :output output
                                               :error error)))]
    (assoc st :eval-display (assoc padded idx merged))))

(defn- render-eval-display!
  "Re-render the iteration block's eval sections from its current
   `:eval-display` vector. Drops nil slots (padding from out-of-order
   parallel entries) and routes the survivors through
   `format-eval-sections` so multi-entry batches get Code[1]/Code[2]/…
   labels."
  [aid rid iter]
  (let [st (get @!iteration-blocks [aid rid iter])
        display (vec (remove nil? (:eval-display st)))
        lines (fmt/format-eval-sections display
                                        :id-prefix (iter-id-prefix aid rid iter))]
    (swap! !iteration-blocks update [aid rid iter]
           assoc :eval-section-lines lines)
    (update-iteration-block! aid rid iter)))

(defn code-eval-pre-handler
  "Handler for :agent.code-eval/pre. Slots the block's :code into the
   iteration's :eval-display vector (parallel mode uses
   :parallel-batch-index; sequential mode appends) and re-renders the
   eval section with stable display-block provider ids."
  [{:keys [agent code parallel? parallel-batch-index]}]
  (stamp-think-activity! agent "Running code…")
  (when-let [[aid rid iter] (iter-current-key agent)]
    (when (get @!iteration-blocks [aid rid iter])
      (swap! !iteration-blocks update [aid rid iter]
             (fn [st]
               (-> (accumulate-eval-entry st :pre parallel? parallel-batch-index
                                          code nil nil nil)
                   (assoc :stage :code :code code))))
      (render-eval-display! aid rid iter))))

(defn code-eval-post-handler
  "Handler for :agent.code-eval/post. Updates the corresponding entry
   in the iteration's :eval-display vector with result/output/error and
   re-renders. Parallel events arrive in any order — :parallel-batch-index
   targets the right slot. Sequential events update the most-recently
   appended entry (the matching :pre)."
  [{:keys [agent code result output error duration-ms lang
           parallel? parallel-batch-index]}]
  (when-let [[aid rid iter] (iter-current-key agent)]
    (when (get @!iteration-blocks [aid rid iter])
      (swap! !iteration-blocks update [aid rid iter]
             (fn [st]
               (-> (accumulate-eval-entry st :post parallel? parallel-batch-index
                                          code result output error)
                   (assoc :code code
                          :code-output {:result result
                                        :output output
                                        :error error
                                        :duration-ms duration-ms}))))
      (render-eval-display! aid rid iter)))
  ;; For sub-agents, record this code-block in the consolidated subagents block
  ;; (count + recent {lang, line-count}).
  (when-not (str/blank? (str code))
    (let [lang  (or lang "?")
          lines (count (str/split-lines (str code)))]
      (accumulate-sub-activity!
       agent
       (fn [s] {:code-blocks-used (inc (:code-blocks-used s 0))
                :recent-code      (keep-last (:recent-code s)
                                             [{:lang lang :lines lines}] 3)})))))

;; ============================================================================
;; Hook handlers for events that don't have a per-iteration widget surface
;; (compaction, iteration exhaustion, evaluation status, analytics). They
;; render plain scrollback lines via `emit!`.
;; ============================================================================

(defn- format-trigger-label
  "Human-readable label for a compaction trigger."
  [trigger]
  (case trigger
    :manual "manual"
    :auto   "auto"
    (some-> trigger name)))

(defn- compaction-block-id
  "Per-agent live-block id so concurrent compactions across agent instances
   don't collide."
  [agent-id]
  (keyword "compaction-block" (str (some-> agent-id name))))

(def ^:private compaction-phase-order
  "Render order for phase rows."
  [:previous-turns])

(defn- render-compaction-lines
  "Build the muted line set for an in-flight compaction. Header carries
   trigger + before-tokens; one row per phase that has reported anything,
   marker shows running vs done."
  [{:keys [trigger before-tokens phases]}]
  (let [header (str "⟳ Compacting context (" (format-trigger-label trigger) ") · "
                    (fmt/format-number (or before-tokens 0)) " est. tokens")]
    (vec
     (cons
      (ansi/muted header)
      (keep (fn [phase]
              (when-let [status (get phases phase)]
                (let [marker (case status :start "  ⏳" :done "  ✓ " "  · ")]
                  (ansi/muted (str marker " " (name phase))))))
            compaction-phase-order)))))

(defn- format-compaction-summary
  "One-line frozen summary: ✓ Compacted (trigger): before → after est. tokens
   (delta) · Nms · phase1, phase2"
  [{:keys [trigger before-tokens after-tokens compacted-keys duration-ms]}]
  (let [before (or before-tokens 0)
        after  (or after-tokens before)
        delta  (- after before)
        already? (and (zero? delta) (empty? compacted-keys))]
    (str (ansi/success (if already? "● No-op" "✓ Compacted"))
         " (" (format-trigger-label trigger) "): "
         (fmt/format-number before) " → " (fmt/format-number after) " est. tokens"
         (when (neg? delta)
           (str " (" (fmt/format-number delta) ")"))
         (when (and duration-ms (pos? duration-ms))
           (str " · " duration-ms "ms"))
         (when (seq compacted-keys)
           (str " · " (str/join ", " (map name compacted-keys)))))))

(defn compaction-pre-handler
  "Handler for :agent.compaction/pre. Open a live block under the originating
   agent's session, ready to take phase updates."
  [{:keys [agent before-tokens target-tokens trigger]}]
  (when agent
    (let [aid   (agent/agent-id agent)
          bid   (compaction-block-id aid)
          block {:trigger trigger
                 :before-tokens before-tokens
                 :target-tokens target-tokens
                 :phases {}
                 :start-ms (System/currentTimeMillis)}]
      (swap! !compaction-blocks assoc aid block)
      (with-agent-render-session agent
        (layout/update-live-block! bid (render-compaction-lines block))))))

(defn compaction-phase-handler
  "Handler for :agent.compaction/phase. Update the block's phase status row."
  [{:keys [agent phase status]}]
  (when (and agent phase status)
    (let [aid (agent/agent-id agent)
          bid (compaction-block-id aid)
          updated (swap! !compaction-blocks
                         (fn [m]
                           (if (contains? m aid)
                             (assoc-in m [aid :phases phase] status)
                             m)))]
      (when-let [block (get updated aid)]
        (with-agent-render-session agent
          (layout/update-live-block! bid (render-compaction-lines block)))))))

(defn compaction-post-handler
  "Handler for :agent.compaction/post. Replace the live block's contents with
   a one-line frozen summary, then freeze the block so it stays pinned in
   scrollback. Falls back to the pre-hook's :before-tokens when the post
   payload omits it (e.g. legacy BT path before this commit)."
  [{:keys [agent before-tokens after-tokens compacted-keys trigger duration-ms]
    :as event}]
  (when agent
    (let [aid (agent/agent-id agent)
          bid (compaction-block-id aid)
          pre (get @!compaction-blocks aid)
          event' (-> event
                     (update :before-tokens #(or % (:before-tokens pre)))
                     (update :trigger       #(or % (:trigger pre))))
          summary (format-compaction-summary event')]
      (swap! !compaction-blocks dissoc aid)
      (with-agent-render-session agent
        (if (contains? @layout/!live-blocks bid)
          (do (layout/update-live-block! bid [summary])
              (layout/freeze-live-block! bid))
          ;; No pre block (e.g. handler missed it) — emit the summary inline.
          (emit! summary))))))

(defn iteration-exhausted-handler
  "Handler for :agent.iteration/exhausted. Mirrors the legacy
   `:iterations-exhausted` watch branch — emits the iteration-cap warning."
  [{:keys [agent iteration-count max-iterations]}]
  (with-agent-render-session agent
    (emit! (fmt/format-iteration-exhausted iteration-count max-iterations))))

(defn recovery-retrying-handler
  "Handler for :agent.recovery/retrying. Emits a muted progress line while the
   CoAct loop works through a transient stall (empty model response, no-channel
   nudge, or malformed output) so the backoff/retry isn't a silent pause.

   The turn CONTINUES through the retry, so the one-per-turn think spinner must
   keep running — `:keep-thinking? true` splices the progress line in ABOVE the
   sticky-bottom spinner instead of tearing it down and recreating it, which
   would reset its elapsed clock (and reshuffle the rotating word) on every
   errored iteration."
  [{:keys [agent kind attempt max reason]}]
  (with-agent-render-session agent
    (emit! (fmt/format-recovery-status kind attempt max reason)
           nil {:keep-thinking? true})))

(defn auto-parked-handler
  "Handler for :agent.iteration/auto-parked. Emits a muted line when the loop
   force-parks the turn after repeated polls of a still-running background task
   (the agent will be auto-resumed when the task completes)."
  [{:keys [agent task-id]}]
  (with-agent-render-session agent
    (let [active? (render-active?)]
      (when active? (stop-thinking-indicator! agent))
      (emit! (ansi/muted (str "\n  " ansi/v-line " ⏸ Parked on " task-id
                              " — will auto-resume on completion.")))
      (when active? (start-thinking-indicator! agent)))))

(defn auto-resumed-handler
  "Handler for :agent.task/auto-resumed. Emits a muted line when a backgrounded
   task completed while idle and the runtime injected a resume turn."
  [{:keys [agent task-ids]}]
  (with-agent-render-session agent
    (emit! (ansi/muted (str "\n  " ansi/v-line " ↺ Auto-resumed: background task(s) "
                            (clojure.string/join ", " task-ids) " completed.")))))

(defn evaluation-started-handler
  "Handler for :agent.evaluation/started. Mirrors the legacy
   `:evaluation-status :phase :started` watch branch."
  [{:keys [agent round]}]
  (with-agent-render-session agent
    ;; Turn continues through evaluation — keep the one-per-turn spinner running
    ;; (see recovery-retrying-handler) rather than restarting it every round.
    (emit! (ansi/muted (str "\n  " ansi/v-line " Evaluating answer quality (round " round ")..."))
           nil {:keep-thinking? true})))

(defn evaluation-llm-calling-handler
  "Handler for :agent.evaluation/llm-calling. Mirrors the legacy
   `:evaluation-status :phase :llm-calling` watch branch."
  [{:keys [agent has-evidence evidence-length eval-lm-label]}]
  (with-agent-render-session agent
    ;; Turn continues — keep the spinner alive (see recovery-retrying-handler)
    ;; and splice the progress line above it; just refresh the status-bar text.
    (emit! (ansi/muted (str "  " ansi/v-line " Checking against "
                            (if has-evidence
                              (str "sandbox evidence (" evidence-length " chars)")
                              "general completeness")
                            " via " (or eval-lm-label "LLM") "..."))
           nil {:keep-thinking? true})
    (when (render-active?)
      (layout/draw-status-bar!
       (ansi/muted "Evaluating...")
       (:status-text @layout/!layout)))))

(defn evaluation-done-handler
  "Handler for :agent.evaluation/done. Mirrors the legacy
   `:evaluation-status :phase :done` watch branch."
  [{:keys [agent verdict detail]}]
  (with-agent-render-session agent
    ;; The turn continues after evaluation (refine / finalize / answer), so the
    ;; one-per-turn spinner keeps running: `:keep-thinking? true` splices the
    ;; verdict line above the sticky-bottom indicator instead of tearing it down
    ;; and recreating it. Mirrors evaluation-started / evaluation-llm-calling.
    (emit! (str "  " (fmt/format-eval-verdict verdict detail) "\n")
           nil {:keep-thinking? true})))

;; ============================================================================
;; In-process iteration sink (wraps `agent-tui.layout` live-block primitives)
;; ============================================================================

(defn make-layout-iteration-sink
  "Build the in-process iteration sink. Calls go to `layout/update-live-block!`
   / `freeze-live-block!` / `dispose-live-block!` — the existing splice-into-
   scrollback machinery used by the non-tmux TUI.

   The tmux daemon installs a different sink that routes through
   `agent-tui-tmux.core.widgets`. Both share the same handler code in this
   file; only the rendering surface changes."
  []
  (reify iter-sink/IterationSink
    (-write-widget!  [_ id lines] (layout/update-live-block! id lines))
    (-freeze-widget! [_ id]       (layout/freeze-live-block! id))
    (-clear-widget!  [_ id]       (layout/dispose-live-block! id))))

(defn install-layout-iteration-sink!
  "Idempotent: install the layout-backed sink as the active iteration sink.
   Called by `core/start!` before `register-tui-hooks!`."
  []
  (iter-sink/set-iteration-sink! (make-layout-iteration-sink)))

;; ============================================================================
;; Close cascade installation (shared sub-output tabs)
;; ============================================================================

(defn- before-close-hook-cascade-shared-output
  "When a chat session closes, also close its root's shared sub-output tab
   (if any) and drop the `!root-output-sessions` entry. When a shared sub-
   output tab itself is closed, just drop the registry entry — the chat
   session stays alive."
  [_idx session]
  (cond
    ;; Chat session — find its shared sub-output (if any) and cascade close.
    (= :chat (:session-type session))
    (when-let [root-aid (:agent-id session)]
      (when-let [shared-sidx (get @!root-output-sessions root-aid)]
        (swap! !root-output-sessions dissoc root-aid)
        ;; Cascade close. The hook on the shared output will run too but
        ;; it short-circuits (entry already removed).
        (try (sessions/close-session! shared-sidx) (catch Throwable _))))
    ;; Shared sub-output tab — clear its entry so the next sub-agent under
    ;; this root creates a fresh tab. (No agent to close on this branch —
    ;; the shared tab carries `:agent nil`.)
    (and (= :output (:session-type session))
         (:sub-output-of session))
    (let [target-root-aid
          (some (fn [[root-aid sidx]]
                  (when (= sidx (:id session)) root-aid))
                @!root-output-sessions)]
      (when target-root-aid
        (swap! !root-output-sessions dissoc target-root-aid)))))

(defn install-shared-output-cascade!
  "Idempotent: register the before-close hook that closes a root's shared
   sub-output tab when the root's chat session closes (and clears the
   `!root-output-sessions` entry in either direction).
   Called by `core/start!` after the layout iteration sink is in place."
  []
  (sessions/register-before-close-hook!
   ::cascade-shared-output before-close-hook-cascade-shared-output))

