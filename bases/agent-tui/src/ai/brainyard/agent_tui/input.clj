;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent-tui.input
  "Input handling for the TUI: raw byte reading, Ctrl-C handling, UTF-8 decoding,
   and permission/feedback interception."
  (:require [ai.brainyard.agent-tui.session :as tui-session]
            [ai.brainyard.agent-tui.layout :as layout]
            [ai.brainyard.agent-tui.permissions :as permissions]
            [ai.brainyard.agent.interface.tui.ansi :as ansi]
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

(defn cancel-active-ask!
  "Cancel the currently-running ask on the ACTIVE tab (cooperative cancel +
   thread interrupt). With per-root concurrent queues each tab has its own ask
   thread (keyed by session-idx in `!ask-threads`); this targets only the
   foreground one, leaving background tabs' turns running. Returns true if a
   turn was actually running, else false (so callers fall back to the hint)."
  []
  (let [ag   (tui-session/get-active-agent)
        aidx (some-> ag tui-session/session-idx-for-agent)
        t    (when aidx (get @!ask-threads aidx))]
    (if t
      (do (when ag (try (agent/cancel-run (:!state ag)) (catch Throwable _)))
          (.interrupt ^Thread t)
          true)
      false)))

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

(defn handle-ctrl-backslash!
  "Handle Ctrl-\\ (ASCII 28, FS) press. Toggles cooperative pause on the
   active agent. No-op when no agent is running."
  []
  (when-let [ag (tui-session/get-active-agent)]
    (let [!state (:!state ag)
          paused? (try (agent/paused? !state) (catch Throwable _ false))]
      (try
        (if paused?
          (do (agent/resume-run !state)
              (tui-session/emit! (ansi/muted "[resumed]")))
          (do (agent/pause-run !state)
              (tui-session/emit! (ansi/muted "[paused] Ctrl-\\ resume · Ctrl-C cancel · or type a message + Enter to resume with it"))))
        (try (tui-session/update-status-bar!) (catch Throwable _))
        (catch Throwable t
          (tui-session/emit! (ansi/failure (str "pause-toggle failed: " (.getMessage t)))))))))

(defn cjk-wide-char?
  "Return true if character is a CJK full-width character (displays as 2 columns)."
  [^Character ch]
  (let [cp (int (.charValue ch))]
    (or (<= 0x1100  cp 0x115F)    ;; Hangul Jamo
        (<= 0x2E80  cp 0x303E)    ;; CJK Radicals, Kangxi, Ideographic
        (<= 0x3041  cp 0x33BF)    ;; Hiragana, Katakana, Bopomofo, CJK compat
        (<= 0x3400  cp 0x4DBF)    ;; CJK Unified Extension A
        (<= 0x4E00  cp 0x9FFF)    ;; CJK Unified Ideographs
        (<= 0xAC00  cp 0xD7AF)    ;; Hangul Syllables
        (<= 0xF900  cp 0xFAFF)    ;; CJK Compatibility Ideographs
        (<= 0xFE30  cp 0xFE4F)    ;; CJK Compatibility Forms
        (<= 0xFF01  cp 0xFF60)    ;; Fullwidth Forms
        (<= 0xFFE0  cp 0xFFE6)))) ;; Fullwidth Signs

(defn utf8-lead-byte-length
  "Return expected total byte count for a UTF-8 lead byte, or 0 if not a lead byte."
  [b]
  (cond
    (< b 0x80)  1  ;; ASCII
    (< b 0xC0)  0  ;; continuation byte (invalid as lead)
    (< b 0xE0)  2  ;; 2-byte
    (< b 0xF0)  3  ;; 3-byte (CJK)
    (< b 0xF8)  4  ;; 4-byte (emoji, rare CJK)
    :else       0))

(defn handle-feedback-key!
  "Validate + dispatch a single printable key string (from the readline editor,
   i.e. the sticky input line) against the pending :confirm / :select prompt.
   Single-key fast-path with reject-on-invalid:
     :confirm — a matching choice key (case-insensitive) delivers immediately;
                any other key is rejected (consumed, never echoed).
     :select  — a number 1-N delivers that option immediately (a :free-input
                option instead transitions to byte-driven text collection);
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
      nil                                    ;; free-input text — byte layer collects it
      (do
        (when-let [n (parse-long key)]
          (when (and (>= n 1) (<= n (count options)))
            (let [idx (dec n)
                  selected (nth options idx)]
              (if (:free-input selected)
                ;; Transition to byte-driven free-input text collection.
                (do (swap! tui-session/!pending-feedback assoc
                           :mode :awaiting-text :free-idx idx :buf (StringBuilder.))
                    (layout/dispose-live-block! permissions/user-feedback-block-id)
                    (tui-session/emit! (str "\n  " (ansi/muted "Type your response: "))))
                (do (deliver promise {:selected (:label selected) :index idx})
                    (reset! tui-session/!pending-feedback nil))))))
        true))))                             ;; consume every key (reject invalid)

(defn- intercept-text-byte!
  "Handle a byte in :awaiting-text mode — the shared line editor for the :text
   kind and for a :select :free-input option. Supports multi-byte UTF-8 (CJK)
   via byte buffering, backspace (width-aware), and printable echo. On Enter,
   delivers the typed text; the delivered shape depends on :kind — :text →
   {:input <text> :index 0}; :select free-input → {:selected … :index … :input}.
   Returns true if consumed."
  [{:keys [promise kind options buf free-idx] :as fb} b]
  (let [^StringBuilder sb buf
        utf8-buf (:utf8-buf fb)
        utf8-need (:utf8-need fb 0)]
    (cond
      ;; Collecting continuation bytes for a multi-byte sequence
      (pos? utf8-need)
      (if (and (>= b 0x80) (< b 0xC0))
        ;; Valid continuation byte
        (let [new-buf (conj utf8-buf b)
              remaining (dec utf8-need)]
          (if (zero? remaining)
            ;; Complete UTF-8 sequence — decode, append, echo
            (let [ba (byte-array (map unchecked-byte new-buf))
                  ch-str (String. ba "UTF-8")]
              (.append sb ch-str)
              (layout/write-raw-chars! ch-str)
              (swap! tui-session/!pending-feedback dissoc :utf8-buf :utf8-need)
              true)
            ;; Still need more bytes
            (do (swap! tui-session/!pending-feedback assoc
                       :utf8-buf new-buf :utf8-need remaining)
                true)))
        ;; Invalid continuation — discard buffer, don't consume byte
        (do (swap! tui-session/!pending-feedback dissoc :utf8-buf :utf8-need)
            true))

      ;; Enter — deliver the typed text (shape depends on :kind)
      (or (= b 13) (= b 10))
      (let [text (.toString sb)]
        (layout/write-raw-chars! "\r\n")
        (if (= kind :text)
          (deliver promise {:input text :index 0})
          (let [selected (nth options free-idx)]
            (deliver promise {:selected (:label selected) :index free-idx :input text})))
        true)

      ;; Backspace — erase last char (CJK = 2 columns, else 1)
      (or (= b 127) (= b 8))
      (do (when (pos? (.length sb))
            (let [last-ch (.charAt sb (dec (.length sb)))]
              (.deleteCharAt sb (dec (.length sb)))
              (if (cjk-wide-char? last-ch)
                (layout/write-raw-chars! "\b\b  \b\b")
                (layout/write-raw-chars! "\b \b"))))
          true)

      ;; Printable ASCII — echo on same line
      (and (>= b 32) (<= b 126))
      (do (.append sb (char b))
          (layout/write-raw-chars! (str (char b)))
          true)

      ;; UTF-8 multi-byte lead byte — start buffering
      (>= b 0xC0)
      (let [total (utf8-lead-byte-length b)]
        (if (pos? total)
          (do (swap! tui-session/!pending-feedback assoc
                     :utf8-buf [b] :utf8-need (dec total))
              true)
          true))  ;; invalid lead byte — consume silently

      ;; Consume other non-printable silently
      :else true)))

(defn try-intercept-byte
  "Try to intercept a raw byte for the pending user-feedback prompt. Returns
   true if intercepted (byte consumed), nil otherwise.

   Only the byte-driven free-input text collection (:select :awaiting-text,
   reached after picking a `:free-input` option) is handled here. :confirm,
   :select digits, and :text all pass through to !raw-input-queue → read-key!
   → the readline editor (the sticky input line), which validates + delivers
   via `handle-feedback-key!` / the editor's submit path. This keeps every
   user-feedback response flowing through one input channel."
  [b]
  (when-let [{:keys [mode] :as fb} @tui-session/!pending-feedback]
    (when (= mode :awaiting-text)
      (intercept-text-byte! fb b))))

(defn start-input-reader!
  "Start a daemon thread that polls /dev/tty for raw bytes and queues them.
   Uses available()+read() polling (not blocking read) so that Thread.interrupt
   can stop the thread immediately — blocking InputStream.read and .close both
   deadlock on macOS when called from different threads.
   Ctrl-C (byte 3) is intercepted and handled directly.
   Permission (y/n/a) and feedback (1-6) bytes are intercepted when pending."
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
                                 (try-intercept-byte b) nil  ;; consumed by permission/feedback
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
               (.put ^LinkedBlockingQueue !raw-input-queue :eof))
             "tui-input-reader")]
    (reset! !tty-stream tty)
    (.setDaemon t true)
    (.start t)
    (reset! !input-reader-thread t)))

(defn stop-input-reader!
  "Stop the input reader thread via interrupt (the polling loop checks it).
   Then close the /dev/tty stream."
  []
  (when-let [^Thread t @!input-reader-thread]
    (reset! !input-reader-thread nil)
    (.interrupt t))
  (when-let [tty @!tty-stream]
    (try (.close ^java.io.FileInputStream tty) (catch Exception _))
    (reset! !tty-stream nil))
  (.clear ^LinkedBlockingQueue !raw-input-queue))
