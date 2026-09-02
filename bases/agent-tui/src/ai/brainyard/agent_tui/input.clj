;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent-tui.input
  "Input handling for the TUI: raw byte reading, Ctrl-C / Ctrl-\\ / ESC handling,
   and the dispatch of permission + user-feedback answers typed into the input
   line (`handle-feedback-key!` for single-key answers, `handle-feedback-submit!`
   for typed lines)."
  (:require [clojure.string :as str]
            [ai.brainyard.agent-tui.session :as tui-session]
            [ai.brainyard.agent-tui.layout :as layout]
            [ai.brainyard.agent-tui.permissions :as permissions]
            [ai.brainyard.agent.interface.tui.ansi :as ansi]
            [ai.brainyard.agent.interface.tui.format :as fmt]
            [ai.brainyard.agent.interface :as agent])
  (:import [java.io InputStream]
           [java.util.concurrent LinkedBlockingQueue TimeUnit]))

;; ============================================================================
;; Ctrl-C / Cancellation State
;; ============================================================================

;; Per-SESSION ask threads: {session-idx → Thread}. Tabs run concurrently
;; (one input queue + worker per root agent), so the currently-running ask is
;; no longer a singleton — Ctrl-C must target the ACTIVE tab's turn.
(defonce !ask-threads (atom {}))
(defonce !last-ctrl-c-ms (atom 0))

;; Input reader thread infrastructure: a daemon thread reads stdin and queues
;; bytes. Ctrl-C (byte 3) is handled inline — cancel agent or emit hint.
;; This avoids SIGINT entirely (isig is disabled in raw mode), so the parent
;; bb process never sees SIGINT and won't kill the child JVM.
(defonce !raw-input-queue (LinkedBlockingQueue.))
(defonce !input-reader-thread (atom nil))
;; Bracketed paste mode: true when inside ESC[200~ ... ESC[201~ sequence.
;; When pasting, Enter/CR/LF inserts a newline instead of submitting.
(def !pasting? (volatile! false))

(defonce !tty-stream (atom nil))

;; ============================================================================
;; Pause tips (sticky live-block shown while the active turn is paused)
;; ============================================================================

(def pause-tips-block-id
  "Stable id for the sticky-bottom live-block that lists the available actions
   while a turn is paused. Public so the resume/cancel paths in core.clj and
   commands.clj can dispose it without re-deriving the id."
  :pause-tips)

(defn- pause-tips-lines
  "Rows for the paused 'what next' tips block.

   Key-hint rows are a two-column table held together by fixed padding, so they
   are TRUNCATED rather than wrapped when the pane is too narrow — a wrapped
   keybinding table reads as noise, and the key (the part the user needs) is on
   the left where truncation preserves it. `cols` nil means \"ask the layout\"."
  ([] (pause-tips-lines nil))
  ([cols]
   (let [w   (max 20 (- (or cols (:cols @layout/!layout) 80) 1))
         fit (fn [s] (fmt/truncate-to-width s w))]
     (mapv fit
           [""
            (str "  " (ansi/warning "⏸ Paused")
                 (ansi/muted " — agent stops at the next safe checkpoint"))
            (str "    ESC                      " (ansi/muted "continue"))
            (str "    type a message + Enter   " (ansi/muted "continue, steering the agent"))
            (str "    Ctrl-C                   " (ansi/muted "cancel this turn"))]))))

(defn show-pause-tips!
  "Render the paused-state tips as a sticky-bottom live-block (anchored below
   the think/iteration/task blocks). Falls back to a plain emit when not in
   fullscreen TUI mode."
  []
  (if (layout/fullscreen?)
    (layout/update-live-block! pause-tips-block-id (pause-tips-lines)
                               {:sticky-bottom? true
                                :render pause-tips-lines})
    (tui-session/emit! (str/join "\n" (pause-tips-lines)))))

(defn hide-pause-tips!
  "Dispose the paused-state tips live-block. No-op when none is showing or when
   not in fullscreen mode. Idempotent — safe to call from every resume/cancel
   path."
  []
  (when (layout/fullscreen?)
    (layout/dispose-live-block! pause-tips-block-id)))

(defn cancel-ask-for-agent!
  "Cancel the currently-running ask for `ag`'s session (cooperative cancel +
   thread interrupt), regardless of which tab is on screen. Targets the ask
   thread keyed by `ag`'s session-idx in `!ask-threads`. Returns true if a turn
   was actually running, else false. Backs both Ctrl-C (active tab) and the ask
   socket's `:op :cancel` (a specific session)."
  [ag]
  (let [aidx (some-> ag tui-session/session-idx-for-agent)
        t    (when aidx (get @!ask-threads aidx))]
    (if t
      (do (when ag (try (agent/cancel-run (:!state ag)) (catch Throwable _)))
          (.interrupt ^Thread t)
          ;; If the turn was paused, the tips block is still on screen — a
          ;; cancel ends the turn, so clear it.
          (hide-pause-tips!)
          true)
      false)))

(defn cancel-active-ask!
  "Cancel the currently-running ask on the ACTIVE tab (cooperative cancel +
   thread interrupt). With per-root concurrent queues each tab has its own ask
   thread; this targets only the foreground one, leaving background tabs' turns
   running. Returns true if a turn was actually running, else false (so callers
   fall back to the hint)."
  []
  (cancel-ask-for-agent! (tui-session/get-active-agent)))

(defn handle-ctrl-c!
  "Handle Ctrl-C press. Called from the input reader thread.
   Single press: cancel the active tab's running ask, or emit hint.
   Double press within 1s: exit."
  []
  (let [now  (System/currentTimeMillis)
        last @!last-ctrl-c-ms]
    (reset! !last-ctrl-c-ms now)
    (if (< (- now last) 1000)
      ;; Double Ctrl-C within 1s → exit (shutdown hook cleans up terminal)
      (System/exit 0)
      ;; Single Ctrl-C → cancel active tab's ask, else queue hint
      (when-not (cancel-active-ask!)
        ;; No ask running on the active tab — queue :sigint so read-line-raw!
        ;; can show the hint.
        (.put ^LinkedBlockingQueue !raw-input-queue :sigint)))))

(defn turn-in-flight?
  "True when a turn is running (or paused) on `ag`'s tab — i.e. an ask thread is
   registered for its session-idx. Mirrors the `running?` gate in core.clj and
   is the signal used to decide whether a lone ESC means 'pause' (turn in
   flight) or should pass through to the readline editor (idle)."
  [ag]
  (boolean (when-let [aidx (some-> ag tui-session/session-idx-for-agent)]
             (get @!ask-threads aidx))))

(defn toggle-pause!
  "Toggle cooperative pause on the active agent. Shared by ESC and Ctrl-\\.
   On pause: request the cooperative pause and show the sticky tips block.
   On resume: clear the pause and dispose the tips block. No-op when no agent
   exists on the active tab."
  []
  (when-let [ag (tui-session/get-active-agent)]
    (let [!state (:!state ag)
          paused? (try (agent/paused? !state) (catch Throwable _ false))]
      (try
        (if paused?
          (do (agent/resume-run !state)
              (hide-pause-tips!)
              (tui-session/emit! (ansi/muted "[resumed]")))
          (do (agent/pause-run !state)
              (show-pause-tips!)))
        (try (tui-session/update-status-bar!) (catch Throwable _))
        (catch Throwable t
          (tui-session/emit! (ansi/failure (str "pause-toggle failed: " (.getMessage t)))))))))

(defn handle-ctrl-backslash!
  "Handle Ctrl-\\ (ASCII 28, FS) press. Toggles cooperative pause on the
   active agent. No-op when no agent is running."
  []
  (toggle-pause!))

(defn handle-esc!
  "Handle a raw ESC byte (27) from the input reader thread.

   ESC is the lead byte of escape sequences (arrow keys, bracketed paste
   ESC[200~). Disambiguate by peeking the tty the same way `terminal/read-key!`
   does: sleep briefly, then check for more bytes.
     - More bytes waiting → it's a sequence: enqueue the ESC and return so the
       following bytes flow to the readline editor unchanged.
     - ESC stands alone → if a turn is in flight on the active tab, toggle
       pause/resume. Otherwise (idle, editing the input line) enqueue the ESC so
       the editor keeps its normal :escape behavior."
  [^java.io.FileInputStream tty]
  (Thread/sleep (long 2))
  (if (pos? (long (.available tty)))
    (.put ^LinkedBlockingQueue !raw-input-queue (int 27))
    (if (turn-in-flight? (tui-session/get-active-agent))
      (toggle-pause!)
      (.put ^LinkedBlockingQueue !raw-input-queue (int 27)))))

;; NOTE: a `cjk-wide-char?` / `utf8-lead-byte-length` pair used to live here,
;; feeding a byte-level line editor that collected free-input feedback answers
;; behind the readline editor's back. Both are gone with it: `read-key!`
;; already decodes UTF-8 into whole characters, and the editor measures widths
;; with `fmt/display-width`, which knows about grapheme clustering — something
;; a per-codepoint CJK range check never did.

(defn handle-feedback-key!
  "Validate + dispatch a single printable key string (from the readline editor,
   i.e. the sticky input line) against the pending :confirm / :select prompt.
   Single-key fast-path with reject-on-invalid:
     :confirm — a matching choice key (case-insensitive) delivers immediately;
                any other key is rejected (consumed, never echoed).
     :select  — a number 1-N delivers that option immediately (a :free-input
                option instead flips the prompt into :awaiting-text, where the
                typed answer is edited in the input line like a :text prompt);
                non-digits / out-of-range are rejected (consumed).
   Returns true when the key is consumed (delivered or rejected), so the editor
   does not treat it as line input. Returns nil for kinds the editor edits
   normally (:text, or a :select already in :awaiting-text free-input mode).
   Delivering also clears !pending-feedback so a fast follow-up key can't be
   mis-routed before the agent thread wakes."
  [{:keys [kind choices options mode promise] :as _fb} ^String key]
  (case (or kind :select)
    :confirm
    (let [ch  (Character/toLowerCase (.charAt key 0))
          hit (some #(when (= ch (Character/toLowerCase ^char (:key %))) %) choices)]
      (when hit
        (deliver promise {:value (:value hit) :key (:key hit)})
        (reset! tui-session/!pending-feedback nil))
      true)                                  ;; consume every key (reject invalid)

    :text nil                                ;; free text — editor edits the line

    ;; :select (default)
    (if (= mode :awaiting-text)
      nil                                    ;; free-input text — editor edits the line
      (do
        (when-let [n (parse-long key)]
          (when (and (>= n 1) (<= n (count options)))
            (let [idx (dec n)
                  selected (nth options idx)]
              (if (:free-input selected)
                ;; Free-input option — flip into :awaiting-text and hand the
                ;; typing to the readline editor (the input line), exactly as
                ;; a :text prompt is handled. The question block stays up so
                ;; the user can still see what they are answering, and both it
                ;; and the input line survive a resize.
                (do (swap! tui-session/!pending-feedback assoc
                           :mode :awaiting-text :free-idx idx)
                    ;; Neither surface repaints itself: the block only
                    ;; re-renders when told to, and the editor is parked in
                    ;; read-key!. Flip both now — the block to mark the pick
                    ;; and drop its "Select [1-N]" trailer, the input line to
                    ;; ask for text — rather than on the user's first keystroke.
                    (permissions/refresh-user-feedback-block!)
                    (tui-session/redraw-idle-prompt!))
                (do (deliver promise {:selected (:label selected) :index idx})
                    (reset! tui-session/!pending-feedback nil))))))
        true))))                             ;; consume every key (reject invalid)

(defn handle-feedback-submit!
  "Dispatch a SUBMITTED line (Enter in the readline editor) against the pending
   prompt. The counterpart to `handle-feedback-key!`, which handles the
   single-key answers; this handles the ones that need a whole typed line.

     :text                    → {:input <line> :index 0}
     :select + :awaiting-text → {:selected <label> :index <idx> :input <line>}
     :confirm / :select       → not answerable by a line. A bare Enter is
                                swallowed (the box clears, the prompt stays)
                                rather than submitted as a blank turn.

   Returns true when the line was consumed by the prompt — the editor then
   clears the box and keeps reading — and nil when no prompt is open, i.e. the
   line is an ordinary turn. Clears !pending-feedback on delivery so a fast
   follow-up keystroke can't be mis-routed before the agent thread wakes.

   With this, EVERY user-feedback response — single key or typed line — flows
   through the one input channel: !raw-input-queue → read-key! → the readline
   editor. Nothing collects raw bytes behind the editor's back. That is what
   makes a typed answer survive a resize: it lives in the editor's buffer, so
   `redraw-input-line!` re-wraps and repaints it like any other input, instead
   of being characters echoed straight at the terminal that no repaint knows
   how to reproduce."
  [^String line]
  (let [{:keys [kind mode promise options free-idx] :as fb} @tui-session/!pending-feedback]
    (when (and fb promise (not (realized? promise)))
      (let [text (str/trim (or line ""))
            done! (fn [answer]
                    (deliver promise answer)
                    (reset! tui-session/!pending-feedback nil)
                    true)]
        (cond
          (= :text kind)
          (done! {:input text :index 0})

          (= :awaiting-text mode)
          (let [selected (nth options (or free-idx 0) nil)]
            (done! (cond-> {:index (or free-idx 0) :input text}
                     selected (assoc :selected (:label selected)))))

          :else true)))))

(defn start-input-reader!
  "Start a daemon thread that polls /dev/tty for raw bytes and queues them.
   Uses available()+read() polling (not blocking read) so that Thread.interrupt
   can stop the thread immediately — blocking InputStream.read and .close both
   deadlock on macOS when called from different threads.
   Ctrl-C (byte 3), Ctrl-\\ (28) and a lone ESC (27) are handled inline; every
   other byte is queued for `read-key!`. Permission / feedback answers are NOT
   intercepted here — they are typed into the input line like anything else and
   dispatched by `handle-feedback-key!` / `handle-feedback-submit!`."
  [^InputStream _in]
  (.clear ^LinkedBlockingQueue !raw-input-queue)
  (let [tty (java.io.FileInputStream. "/dev/tty")
        t   (Thread.
             (fn []
               (try
                 (loop []
                   (when-not (.isInterrupted (Thread/currentThread))
                     (if (pos? (.available tty))
                       (let [b (.read tty)]
                         (if (= b -1)
                           (.put ^LinkedBlockingQueue !raw-input-queue :eof)
                           (do (cond
                                 (= b 3)                (handle-ctrl-c!)
                                 (= b 28)               (handle-ctrl-backslash!)
                                 (= b 27)               (handle-esc! tty)  ;; lone ESC → pause toggle; sequence → pass through
                                 :else                  (.put ^LinkedBlockingQueue !raw-input-queue (int b)))
                               (recur))))
                       ;; No data — sleep briefly then poll again
                       (do (Thread/sleep (long 5))
                           (recur)))))
                 (catch InterruptedException _)
                 (catch Exception e
                   (try
                     (let [logs (agent/brainyard-subdir!
                                 (agent/init-dirs!) "logs" :user)
                           path (if logs
                                  (str logs "/by-input-crash.log")
                                  "/tmp/by-input-crash.log")]
                       (spit path
                             (str "INPUT READER CRASH: " (.getMessage e) "\n"
                                  (with-out-str (.printStackTrace e (java.io.PrintWriter. *out*))))))
                     (catch Exception _))))
               ;; Announce EOF only if this thread is STILL the installed
               ;; reader. `stop-input-reader!` nils the atom before it
               ;; interrupts, so a reader we deliberately deposed cannot claim
               ;; the terminal ended — a suspend is not an EOF. Without the
               ;; check, suspending input while anything was parked in
               ;; `read-key!` ended the session: `:eof` → -1 → `:ctrl-d` →
               ;; `read-line-raw!` returns nil on an empty buffer → the loop
               ;; exits → `System/exit 0`. A clean, unrequested quit. The
               ;; `.clear` in `stop-input-reader!` cannot prevent that: a
               ;; parked `.take` receives an item the instant it is enqueued,
               ;; so no clear can beat it.
               ;;
               ;; The try is not decorative either. This put used to sit
               ;; outside the try above, and whether it threw decided which
               ;; failure you got: interrupted inside `Thread/sleep`, the
               ;; exception clears the interrupt flag and the put SUCCEEDS
               ;; (spurious quit); interrupted while runnable, the flag is
               ;; still set, `.put` throws immediately and the thread dies
               ;; with an uncaught exception the crash-log catch never sees
               ;; (silent hang instead). One 5 ms poll loop decided which.
               (try
                 (when (identical? (Thread/currentThread) @!input-reader-thread)
                   (.put ^LinkedBlockingQueue !raw-input-queue :eof))
                 (catch InterruptedException _)))
             "tui-input-reader")]
    (reset! !tty-stream tty)
    (.setDaemon t true)
    ;; Install BEFORE starting: the thread's own exit path reads this atom to
    ;; decide whether it may announce EOF, and a reader that died before it was
    ;; installed would otherwise be unable to report a tty that really had
    ;; closed.
    (reset! !input-reader-thread t)
    (.start t)))

(defn stop-input-reader!
  "Stop the input reader thread via interrupt (the polling loop checks it).
   Then close the /dev/tty stream.

   This is a SUSPEND, not a shutdown: it says nothing about the terminal, and
   the deposed thread is barred from enqueueing `:eof` (see the comment on its
   exit path above). Anything parked in `read-key!` therefore stays parked
   until `start-input-reader!` feeds the queue again — which is what a suspend
   should look like, and is why the editor handover in `display-block-ui` can
   bracket its `$EDITOR` call with this pair.

   A caller that needs to WAKE a parked reader rather than leave it waiting can
   enqueue any keyword other than `:eof` / `:sigint`: `read-key!` maps those to
   `:unknown`, which the readline loop recurs on harmlessly.

   The queue clear drops half-typed bytes, which is right for a suspend — but
   it is not a guard against a stale sentinel: a taker parked in `.take`
   receives an item the instant it is enqueued, so no clear can outrun it."
  []
  (when-let [^Thread t @!input-reader-thread]
    (reset! !input-reader-thread nil)
    (.interrupt t))
  (when-let [tty @!tty-stream]
    (try (.close ^java.io.FileInputStream tty) (catch Exception _))
    (reset! !tty-stream nil))
  (.clear ^LinkedBlockingQueue !raw-input-queue))
