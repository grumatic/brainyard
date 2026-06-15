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
            [ai.brainyard.clj-nrepl.interface :as clj-nrepl]
            [clojure.string :as str]))

(declare update-status-bar!)
(declare handle-session-change)
(declare find-session-for-agent)
(declare format-iter-elapsed)

;; ============================================================================
;; State
;; ============================================================================

(defonce !tui-state
  (atom {:agent       nil      ;; current Agent instance
         :agent-id  nil      ;; keyword — instance-id (e.g. :coact-agent/tui-1713000000)
         :defagent-id nil    ;; keyword — defagent type (e.g. :coact-agent) for display
         :writer      nil      ;; captured java.io.Writer for nREPL
         :watches     []       ;; [{:atom :key}] for cleanup
         :max-iterations nil
         :verbosity   :normal  ;; :quiet | :normal | :verbose
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
      :placeholder (help-tips/current-placeholder)})))

;; TUI mulog publisher handle (verbose mode only)
(defonce ^:private !tui-publisher (atom nil))

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
  "True when the TUI is in :quiet verbosity — only the final answer box
   should be rendered. All intermediate widgets (iteration block, think
   spinner, subagents block, TODO block) and intermediate scrollback
   emissions (observation, goal status) are suppressed."
  []
  (= :quiet (:verbosity @!tui-state)))

;; Per-task live block state
;; {task-id {:name str, :status :pending|:running|:completed|:failed|:cancelled,
;;           :start-time long, :output-lines [str ...] (last 2)}}
(defonce !task-blocks (atom {}))
(defonce ^:private !task-ticker-thread (atom nil))

;; Per-agent live compaction block state. Keyed by agent-id. Reset on post.
;; {agent-id {:trigger kw
;;            :before-tokens long
;;            :target-tokens long-or-nil
;;            :phases {<phase-kw> :start|:done}
;;            :start-ms long}}
(defonce !compaction-blocks (atom {}))

;; Singleton "thinking" live block for the active agent. Tail-of-scrollback
;; widget that animates a Knight Rider ticker + a randomly-picked synonym +
;; the latest LLM streaming snippet (multi-line, wrapped to terminal width,
;; capped at think-max-lines).
;; nil = no active thinking block.
;; {:word str :session-idx int :spinner-idx atom :running atom :thread Thread
;;  :st-mem-atom atom}
(defonce ^:private !think-block (atom nil))
(def ^:private think-block-id :think-block)

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

;; Activity from agent trace events. Single global atom — `{:text str :ts ms}`
;; or nil. Stamped by hook handlers (iteration/pre, dspy-action/pre,
;; tool-calls/pre, tool-use/pre, code-eval/pre). The think-block renderer
;; shows `:text` when fresher than `think-activity-ttl-ms`; otherwise it
;; falls back to the rotating word. Latest write wins across all agents.
(defonce ^:private !think-activity (atom nil))

;; Milliseconds an activity stays "fresh" before the think-block falls back
;; to the rotating thinking word. 3s — long enough to read a tool-call line,
;; short enough that the spinner doesn't feel stuck on a stale event.
(def ^:private think-activity-ttl-ms 3000)

(defn- stamp-think-activity!
  "Record the most recent agent activity for the thinking live block.
   `text` should be a single short line (will be truncated by the renderer)."
  [text]
  (when (string? text)
    (reset! !think-activity {:text text :ts (System/currentTimeMillis)})))

(defn- fresh-activity
  "Return {:text str :ts ms} if the latest activity is still within TTL,
   else nil. Read-only."
  []
  (when-let [{:keys [ts] :as a} @!think-activity]
    (when (< (- (System/currentTimeMillis) ts) think-activity-ttl-ms)
      a)))

;; Cap on Think-section line count in iteration widgets (re-used here for the
;; multi-line `Think:` block in `render-iteration-block-lines`). The thinking
;; live-block itself is one-line and no longer references this.
(def ^:private think-max-lines 10)

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

   The line is truncated with `…` when it exceeds terminal width."
  [shuffled-words spinner-idx frame start-time]
  (let [cols (or (:cols @layout/!layout) 80)
        bracketed-spinner (str (ansi/muted "[")
                               (ansi/spinner-active frame)
                               (ansi/muted "]"))
        n (count shuffled-words)
        idx (mod (quot spinner-idx think-word-rotate-ticks) n)
        word (nth shuffled-words idx)
        head-plain (str word "...")
        elapsed-ms (- (System/currentTimeMillis)
                      (or start-time (System/currentTimeMillis)))
        elapsed-str (format-iter-elapsed elapsed-ms)
        activity (some-> (fresh-activity) :text)
        suffix-plain (str " ("
                          elapsed-str
                          (when (and activity (not (str/blank? activity)))
                            (str " · " activity))
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
  "Re-render the thinking live block (single-line braille spinner + body).
   Skips rendering if the originating session isn't active.
   Marked `:sticky-bottom?` so the spinner stays anchored at the bottom
   of the live-block region — any task / iteration / sub-agent blocks
   spawned afterward insert above it instead of below."
  []
  (when-let [state @!think-block]
    (let [origin-idx (:session-idx state)]
      (when (or (nil? origin-idx) (= origin-idx (sessions/active-idx)))
        (let [{:keys [shuffled-words spinner-idx start-time]} state
              tick   @spinner-idx
              frame  (nth think-ticker-frames
                          (mod tick (count think-ticker-frames)))
              lines  (render-think-block-lines shuffled-words tick frame
                                               start-time)]
          (layout/update-live-block! think-block-id lines
                                     {:sticky-bottom? true}))))))

(defn- start-think-block-ticker!
  "Start a 150ms ticker that animates the thinking live block. Idempotent —
   does nothing if a ticker is already running."
  []
  (when-let [state @!think-block]
    (when-not (:thread state)
      (let [running (atom true)
            spinner-idx (:spinner-idx state)
            thread (Thread.
                    (fn []
                      (try
                        (loop []
                          (when @running
                            (try (update-think-block!)
                                 (catch Exception _))
                            (vswap! spinner-idx inc)
                            (Thread/sleep (long 150))
                            (recur)))
                        (catch InterruptedException _))))]
        (.setDaemon thread true)
        (swap! !think-block assoc :thread thread :running running)
        (.start thread)))))

;; Forward decl for spinner-idx volatile (used in atom — keep simple)
(defn- new-spinner-idx [] (volatile! 0))

(defn stop-thinking-indicator!
  "Stop spinner and clear its line. Thread-safe via spinner-lock."
  []
  (locking spinner-lock
    ;; Fullscreen: stop think-block ticker, then either dispose (default) or
    ;; freeze the live block based on :dispose-think-block runtime config.
    ;; - dispose (true): remove the block's lines from scrollback entirely
    ;; - freeze  (false): leave the block's lines as plain scrollback history,
    ;;   BUT only if the block actually has a streaming snippet (more than the
    ;;   bare "Thinking..." header). Empty think-blocks are always disposed.
    (when-let [{:keys [thread running st-mem-atom]} @!think-block]
      (when running (reset! running false))
      (when thread
        (try (.join ^Thread thread 200) (catch Exception _)))
      (let [ag (:agent (sessions/get-active-session))
            config-dispose? (boolean (agent/get-config ag :dispose-think-block))
            ;; Inspect the live block's content: empty snippet → just one line
            ;; (the header). Anything more means real content was streamed.
            block-info (get @layout/!live-blocks think-block-id)
            empty? (or (nil? block-info)
                       (<= (or (:line-count block-info) 0) 1))
            dispose? (or config-dispose? empty?)]
        (reset! !think-block nil)
        (try
          (if dispose?
            (layout/dispose-live-block! think-block-id)
            (layout/freeze-live-block! think-block-id))
          (catch Exception _)))
      ;; Refresh status bar (drop any spinner-left text left over)
      (let [brand-left (build-status-left nil)
            session-indicator (sessions/format-session-indicator)
            full-left (cond
                        (and session-indicator brand-left) (str brand-left " " session-indicator)
                        session-indicator session-indicator
                        :else brand-left)]
        (layout/draw-status-bar! full-left (:status-text @layout/!layout))))
    ;; Legacy / inline-mode spinner cleanup
    (when-let [{:keys [thread running]} @!spinner]
      (reset! running false)
      (reset! !spinner nil)
      (when thread
        (try (.join ^Thread thread 200) (catch Exception _)))
      (when-not (layout/fullscreen?)
        ;; In inline mode, clear spinner line with carriage return
        (locking output-lock
          (let [w (if (thread-bound? #'*out*) *out* (:writer @!tui-state))]
            (when w
              (.write ^java.io.Writer w "\r                         \r")
              (.flush ^java.io.Writer w))))))))

(defn start-thinking-indicator!
  "Start the thinking indicator for the active agent.
   In fullscreen mode: creates a singleton :think-block live block at the tail of
   scrollback that animates a spinner + iteration label + LLM streaming snippet
   every 300ms. Bound to the current session (skips updates when not active).
   On stop-thinking-indicator!, the live block is disposed (removed from scrollback).
   In inline mode: prints a static spinner line (no animation).
   When iteration-label is provided, it is appended after 'Thinking...'.
   Thread-safe via spinner-lock — prevents concurrent ticker threads."
  ([] (start-thinking-indicator! nil))
  ([iteration-label]
   (locking spinner-lock
     ;; Stop any existing think-block ticker (keeps think state alive briefly)
     (when-let [{:keys [thread running]} @!think-block]
       (when running (reset! running false))
       (when thread
         (try (.join ^Thread thread 200) (catch Exception _)))
       (reset! !think-block nil))
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
       ;; Clear any stale activity from a previous cycle — fresh cycles start
       ;; with the rotating word until a hook stamps the first real event.
       (reset! !think-activity nil)
       (if fs?
         ;; Fullscreen: create think live block + start ticker
         (do
           (reset! !think-block
                   {:shuffled-words shuffled
                    :iteration-label iteration-label
                    :session-idx (sessions/active-idx)
                    :spinner-idx (volatile! 0)
                    :st-mem-atom st-mem-atom
                    :start-time (System/currentTimeMillis)
                    :thread nil
                    :running nil})
           ;; Initial paint
           (try (update-think-block!) (catch Exception _))
           ;; Start animation ticker
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
   are responsible for any persistence."
  ([s] (emit! s nil))
  ([s session-idx]
   (when (and s (not (str/blank? s)))
     (when (render-active?) (stop-thinking-indicator!))
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
                   (when (and (help-tips/agent-suggestion)
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

(defn set-verbosity!
  "Set verbosity level: :quiet (answer only), :normal (iterations+tools+answer),
   :verbose (+BT traces)."
  [level]
  {:pre [(#{:quiet :normal :verbose} level)]}
  (swap! !tui-state assoc :verbosity level))

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

(defn- render-sub-agent-line
  "Build one line for a sub-agent inside the subagents block.
   Format: `{indent}{marker} {agent-id}  [{type}]  {status}  Iter N/M  ({tok})  {elapsed}`
   Children are indented under their parent with `↳ ` per depth level."
  [{:keys [agent-id defagent-id status start-time end-time
           st-mem-atom iter-rollup depth]}
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
                     (ansi/muted (str (or status "?"))))]
    (str prefix
         marker " "
         ;; Display the full agent-id (namespace/name), dropping the
         ;; leading colon. Matches the legacy agent-block label.
         (ansi/header (subs (str agent-id) 1))
         (when type-tag (str "  " type-tag))
         "  " status-str
         (when iter-text (str "  " (ansi/muted iter-text)))
         (when tokens   (str "  " (ansi/muted tokens)))
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

   Header (live mode) is a centered separator: `── subagents (N running, M done) ──`.
   Body is one line per sub-agent (depth-indented with ↳).

   When `final?` is true the running/done counter header is omitted — frozen
   scrollback only needs the per-sub-agent summary lines (each carries its
   own static ✓/✗ marker and final iter/token/elapsed numbers), and the
   running counter is meaningless once the block is no longer animating."
  ([state spinner-char] (render-subagents-block-lines state spinner-char false))
  ([{:keys [sub-agents]} spinner-char final?]
   (let [ordered (subagents-rendering-order sub-agents)
         body    (mapv #(render-sub-agent-line % spinner-char) ordered)]
     (if final?
       (vec body)
       (let [cols    (or (:cols @layout/!layout) 80)
             agents  (vals sub-agents)
             n-run   (count (filter #(= :running (:status %)) agents))
             n-done  (count (filter #(#{:done :error} (:status %)) agents))
             label   (str " subagents (" n-run " running, " n-done " done) ")
             label-len (count label)
             left-len  (max 3 (quot (- cols label-len) 2))
             right-len (max 3 (- cols label-len left-len))
             separator (str (ansi/style (apply str (repeat left-len ansi/h-line)) ansi/dim)
                            (ansi/style label ansi/bold ansi/bright-magenta)
                            (ansi/style (apply str (repeat right-len ansi/h-line)) ansi/dim))]
         (vec (cons separator body)))))))

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
   ;; Suppressed in :quiet verbosity (answer-only mode).
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
        body-lines   (vec (take-last 2 output-lines))
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
   Only renders if the task's originating session is currently active."
  [task-id]
  (when-let [state (get @!task-blocks task-id)]
    (let [origin-idx (:session-idx state)]
      (when (or (nil? origin-idx) (= origin-idx (sessions/active-idx)))
        (let [spinner-char (nth subagents-spinner-frames @!subagents-spinner-idx)
              lines (render-task-block-lines task-id state spinner-char)]
          (layout/update-live-block! (task-block-id task-id) lines))))))

;; ============================================================================
;; Iteration live block (per-iteration LLM step within a turn)
;; ============================================================================

;; Per-iteration block state, keyed by [agent-id repeat-id iteration].
;; {[aid rid iter] {:agent-id kw :repeat-id str :iteration int :max-iterations int
;;                  :stage :pre|:think|:tools|:code|:done
;;                  :reasoning   string|nil    ; from dspy-action/post
;;                  :streaming   string|nil    ; from dspy-action/chunk; cleared on /post
;;                  :tool-batch  [{:call-id :name :args :status :start-ms :end-ms
;;                                  :result-chars :error-msg}]
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
  "Compact pr-str of args, truncated to 60 chars."
  [args]
  (let [s (try (pr-str args) (catch Exception _ "<unprintable>"))]
    (if (> (count s) 60)
      (str (subs s 0 57) "...")
      s)))

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
  "One line per tool: '  → name(args): called [→ done|error, Ns, Mchars]'.
   Styling routes through the theme: `:tool/bullet` for the leading
   arrow, `:tool/name` for the tool name, `:tool/done` / `:tool/error`
   for the outcome marker, `:fg/muted` for the inline transition. A
   theme switch (e.g. `theme/set-active-theme!`) propagates here without
   any code change."
  [{:keys [name args status start-ms end-ms result-chars error-msg]}]
  (let [args-str    (format-iter-args args)
        styled-name (ansi/tool-name name)
        bullet      (ansi/tool-bullet ansi/arrow)
        head        (str "  " bullet " " styled-name "(" args-str "): called")
        elapsed     (when (and start-ms end-ms) (format-iter-elapsed (- end-ms start-ms)))]
    (case status
      :called head
      :done   (str head " " (ansi/muted "→") " "
                   (ansi/tool-done "done")
                   (when (seq elapsed) (str ", " elapsed))
                   (when result-chars (str ", " (format-iter-result-chars result-chars))))
      :error  (str head " " (ansi/muted "→") " "
                   (ansi/tool-error "error")
                   (when (seq elapsed) (str ", " elapsed))
                   (when error-msg (str ", " (pr-str (truncate-iter-line error-msg 40)))))
      head)))

(defn- render-iteration-block-lines
  "Build ANSI lines for an iteration block."
  [{:keys [iteration max-iterations stage reasoning streaming
           tool-batch eval-section-lines usage result start-ms end-ms]}
   spinner-char]
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
                     (mapv render-iter-tool-line tool-batch))]
    ;; Eval sections (Code / Result / Output / Error) are pre-rendered
    ;; into :eval-section-lines by the code-eval hook handlers. Each
    ;; section is a display-block-backed boxed segment, so long content
    ;; collapses with a marker the user can expand. Using a stable
    ;; id-prefix per iteration means the pre-render and post-render
    ;; calls overwrite the same providers instead of leaking new ones.
    (vec (concat [header]
                 (or think-block-lines [])
                 (or tool-lines [])
                 (or eval-section-lines [])))))

(defn- update-iteration-block!
  "Re-render an iteration block. Only renders if the originating session is active.
   Suppressed entirely in :quiet verbosity — only the answer box is shown."
  [agent-id repeat-id iteration]
  (when-not (quiet?)
    (when-let [state (get @!iteration-blocks [agent-id repeat-id iteration])]
      (let [origin-idx (:session-idx state)]
        (when (or (nil? origin-idx) (= origin-idx (sessions/active-idx)))
          (let [spinner-char (nth subagents-spinner-frames @!subagents-spinner-idx)
                lines (render-iteration-block-lines state spinner-char)]
            (iter-sink/write-widget!
             (iteration-block-id agent-id repeat-id iteration) lines)))))))

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
  ;; Suppressed in :quiet verbosity (answer-only mode).
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
  "Render a single sub-agent display event with agent name prefix."
  [agent-name stage data]
  (let [prefix (str (ansi/style (str "[" agent-name "] ") ansi/bold ansi/bright-magenta))]
    (when (not= :quiet (:verbosity @!tui-state))
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
  (when (= :verbose (:verbosity @!tui-state))
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
        ;; Output lines from primary (first) task
        primary-task (first running-tasks)
        all-output (when primary-task @(:output-lines primary-task))
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
   config (default true → dispose)."
  [block-id]
  (if (boolean (agent/get-config :dispose-task-block))
    (layout/dispose-live-block! block-id)
    (layout/freeze-live-block! block-id)))

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
  (when (get-in @!subagents-blocks [root-aid :sub-agents sub-agent-id])
    (swap! !subagents-blocks
           update-in [root-aid :sub-agents sub-agent-id]
           merge {:status   status
                  :end-time (System/currentTimeMillis)})
    (update-subagents-block! root-aid)
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
                       ;; Refresh output lines from task atoms
                       (doseq [[tid _] active]
                         (when-let [task (get @agent/!tasks tid)]
                           (let [output (when-let [ol (:output-lines task)]
                                          (if (instance? clojure.lang.IDeref ol) @ol ol))
                                 last-2 (vec (take-last 2 (or output [])))]
                             (swap! !task-blocks update tid assoc
                                    :output-lines last-2
                                    :output-count (count (or output [])))))
                         (update-task-block! tid))
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
          :session-idx (sessions/active-idx)
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
  (when (get @!task-blocks task-id)
    (let [bid (task-block-id task-id)]
      (finalize-task-block! bid)
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
          (= :running new-status)
          (when (= :foreground new-display)
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
            (let [bid (task-block-id task-id)]
              (future
                (Thread/sleep 2000)
                (finalize-task-block! bid)
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
          (when-not (get @!task-blocks task-id)
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
   Options: :max-iterations, :verbosity, :session-idx"
  [agent agent-id & {:keys [max-iterations verbosity session-idx]}]
  (let [sidx   (or session-idx (sessions/active-idx))
        def-id (if (namespace agent-id)
                 (keyword (namespace agent-id))
                 agent-id)
        now    (System/currentTimeMillis)
        updates (cond-> {:agent agent :agent-id agent-id
                         :defagent-id def-id :started-at now}
                  max-iterations (assoc :max-iterations max-iterations)
                  verbosity      (assoc :verbosity verbosity))]
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
                              :max-iterations nil
                              :started-at nil})
   ;; Keep !tui-state in sync
   (swap! !tui-state assoc
          :agent nil
          :agent-id nil
          :defagent-id nil
          :max-iterations nil
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
              :queue-count queue-count
              ;; Runtime-drift chip: visible iff at least one mutating
              ;; eval has reached the live runtime via clj-nrepl in
              ;; this process (debug-agent / live-runtime work). Cleared
              ;; only by clj-nrepl/drift-clear! or process restart.
              :drifted?    (clj-nrepl/drifted?)
              :drift-count (clj-nrepl/drift-count)}))))
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

(defn- find-session-for-parent
  "Find the TUI session index for the parent agent. Returns nil if not found."
  [agent]
  (when-let [parent (get-in @(:!state agent) [:runtime :parent-agent])]
    (:id (first (filter #(identical? (:agent %) parent) (sessions/session-list))))))

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
                       :parent-agent-id parent-aid
                       :depth           display-depth}))))

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
  (when (help-tips/agent-suggestion)
    (help-tips/clear-agent-suggestion!)
    (when (and (layout/input-active?)
               (layout/input-empty?)
               (not (layout/popover-active?)))
      (try (redraw-idle-prompt!) (catch Exception _))))
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
    (help-tips/set-agent-suggestion! prompt)
    (when (and (layout/input-active?)
               (layout/input-empty?)
               (not (layout/popover-active?)))
      (try (redraw-idle-prompt!) (catch Exception _)))))

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
              goal-achieved (:goal-achieved st)]
          (when (and (string? answer) (not (str/blank? answer)))
            (when (render-active?) (stop-thinking-indicator!))
            (emit! (fmt/format-answer answer))
            (when (some? goal-achieved)
              (emit! (fmt/format-goal-status goal-achieved)))
            ;; Suggested follow-up (:next-user-prompt). format-next-prompt
            ;; returns nil if blank.
            (when-let [np (fmt/format-next-prompt (:next-user-prompt st))]
              (emit! np)))))))
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
              goal-achieved (:goal-achieved st)]
          (when (and (string? answer) (not (str/blank? answer)))
            (sessions/emit-to-session! sidx (fmt/format-answer answer))
            (when (some? goal-achieved)
              (sessions/emit-to-session!
               sidx (fmt/format-goal-status goal-achieved))))
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
   No-op for the live block — block creation is deferred to the :pending
   → :running transition in `handle-tasks-change` so the user sees a single
   block render instead of a pending-then-running flicker (the :pending
   window is microseconds for the common create-then-start path)."
  [_event])

(defn task-completed-handler
  "Handler for :task/completed. Event: {:task}.
   Updates the live block to final status and schedules freeze."
  [{:keys [task]}]
  (let [tid (:id task)
        final-status (or (:status task) :completed)]
    (when (get @!task-blocks tid)
      (let [output (when-let [ol (:output-lines task)]
                     (if (instance? clojure.lang.IDeref ol) @ol ol))
            last-2 (vec (take-last 2 (or output [])))]
        (swap! !task-blocks update tid merge
               {:status final-status
                :output-lines last-2
                :output-count (count (or output []))})
        (update-task-block! tid)
        (let [bid (task-block-id tid)]
          (future
            (Thread/sleep 2000)
            (finalize-task-block! bid)
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
   :start-time) persists across every iteration of the turn."
  [{:keys [agent iteration max-iterations repeat-id]}]
  (stamp-think-activity!
   (str "Iter " iteration (when max-iterations (str "/" max-iterations))))
  (let [aid (:agent-id agent)
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
    (start-iteration-block-ticker!)))

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
  [{:keys [agent iteration repeat-id result observation]}]
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
             {:stage :done :streaming nil :result result-kw
              :end-ms (System/currentTimeMillis)})
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
          (let [final-state (assoc outgoing :stage :done :streaming nil :result result-kw)
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
    ;; Suppressed in :quiet verbosity (answer-only mode). The goal-achieved
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
  (stamp-think-activity! "Reasoning…")
  (when-let [[aid rid iter] (iter-current-key agent)]
    (when (get @!iteration-blocks [aid rid iter])
      (swap! !iteration-blocks update [aid rid iter]
             merge {:stage :think :streaming nil})
      (update-iteration-block! aid rid iter))))

(defn dspy-chunk-handler
  "Handler for :agent.dspy-action/chunk. Appends the chunk's accumulated text
   to the current iteration's :streaming field."
  [{:keys [agent accumulated]}]
  (when-let [[aid rid iter] (iter-current-key agent)]
    (when (get @!iteration-blocks [aid rid iter])
      (swap! !iteration-blocks update [aid rid iter]
             assoc :streaming accumulated)
      (update-iteration-block! aid rid iter))))

(defn dspy-post-handler
  "Handler for :agent.dspy-action/post. Records reasoning + usage; clears streaming."
  [{:keys [agent reasoning usage]}]
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
  (stamp-think-activity! "Calling tools…")
  (when-let [[aid rid iter] (iter-current-key agent)]
    (when (get @!iteration-blocks [aid rid iter])
      (swap! !iteration-blocks update [aid rid iter]
             merge {:stage :tools :tool-batch []})
      (update-iteration-block! aid rid iter))))

(defn tool-calls-post-handler
  "Handler for :agent.tool-calls/post. No per-tool changes — the per-tool
   handlers already populated :tool-batch. Just signals batch end."
  [{:keys [agent]}]
  (when-let [[aid rid iter] (iter-current-key agent)]
    (when (get @!iteration-blocks [aid rid iter])
      (update-iteration-block! aid rid iter))))

(defn- iter-tool-suppressed?
  "RLM caveat: when an iteration is in :code stage, tool-use events come
   from inside the SCI sandbox and would clutter the [Tools] section. Skip
   per-tool rendering in that case."
  [iter-state]
  (= :code (:stage iter-state)))

(defn tool-use-pre-handler
  "Handler for :agent.tool-use/pre. Appends a :called entry to the current
   iteration's :tool-batch (unless suppressed for code-stage)."
  [{:keys [agent tool-name args call-id]}]
  (stamp-think-activity! (str "→ " tool-name))
  (when-let [[aid rid iter] (iter-current-key agent)]
    (let [k [aid rid iter]
          state (get @!iteration-blocks k)]
      (when (and state (not (iter-tool-suppressed? state)))
        (swap! !iteration-blocks update-in [k :tool-batch] (fnil conj [])
               {:call-id call-id
                :name (str tool-name)
                :args args
                :status :called
                :start-ms (System/currentTimeMillis)})
        (update-iteration-block! aid rid iter)))))

(defn tool-use-post-handler
  "Handler for :agent.tool-use/post. Locates the matching tool-batch entry
   by :call-id and updates its status / timing / result-chars / error-msg."
  [{:keys [agent tool-name call-id result]}]
  (when-let [[aid rid iter] (iter-current-key agent)]
    (let [k [aid rid iter]
          state (get @!iteration-blocks k)]
      (when (and state (not (iter-tool-suppressed? state)))
        (let [now (System/currentTimeMillis)
              error? (and (map? result) (some? (:error-message result)))
              error-msg (when error? (str (:error-message result)))
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
                          :error-msg error-msg})
            (update-iteration-block! aid rid iter)))))))

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
  (stamp-think-activity! "Running code…")
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
  [{:keys [agent code result output error duration-ms
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
      (render-eval-display! aid rid iter))))

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
   nudge, or malformed output) so the backoff/retry isn't a silent pause."
  [{:keys [agent kind attempt max]}]
  (with-agent-render-session agent
    (let [active? (render-active?)]
      (when active? (stop-thinking-indicator!))
      (emit! (fmt/format-recovery-status kind attempt max))
      (when active? (start-thinking-indicator!)))))

(defn evaluation-started-handler
  "Handler for :agent.evaluation/started. Mirrors the legacy
   `:evaluation-status :phase :started` watch branch."
  [{:keys [agent round]}]
  (with-agent-render-session agent
    (let [active? (render-active?)]
      (when active? (stop-thinking-indicator!))
      (emit! (ansi/muted (str "\n  " ansi/v-line " Evaluating answer quality (round " round ")...")))
      (when active? (start-thinking-indicator!)))))

(defn evaluation-llm-calling-handler
  "Handler for :agent.evaluation/llm-calling. Mirrors the legacy
   `:evaluation-status :phase :llm-calling` watch branch."
  [{:keys [agent has-evidence evidence-length eval-lm-label]}]
  (with-agent-render-session agent
    (let [active? (render-active?)]
      (when active? (stop-thinking-indicator!))
      (emit! (ansi/muted (str "  " ansi/v-line " Checking against "
                              (if has-evidence
                                (str "sandbox evidence (" evidence-length " chars)")
                                "general completeness")
                              " via " (or eval-lm-label "LLM") "...")))
      (when active?
        (layout/draw-status-bar!
         (ansi/muted "Evaluating...")
         (:status-text @layout/!layout))
        (start-thinking-indicator!)))))

(defn evaluation-done-handler
  "Handler for :agent.evaluation/done. Mirrors the legacy
   `:evaluation-status :phase :done` watch branch."
  [{:keys [agent verdict detail]}]
  (with-agent-render-session agent
    (let [active? (render-active?)]
      (when active? (stop-thinking-indicator!))
      (emit! (str "  " (fmt/format-eval-verdict verdict detail) "\n"))
      ;; The turn continues after evaluation (refine / finalize / answer), so
      ;; resume the sticky-bottom indicator — otherwise the cursor is left on
      ;; the emitted verdict line instead of returning to the working/input
      ;; line. Mirrors evaluation-started / evaluation-llm-calling handlers.
      (when active? (start-thinking-indicator!)))))

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

