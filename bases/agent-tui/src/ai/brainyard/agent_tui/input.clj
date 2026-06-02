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

(defonce !ask-thread (atom nil))
(defonce !last-ctrl-c-ms (atom 0))

;; Input queue for non-blocking ask processing
(defonce !input-queue (atom nil))

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

(defn handle-ctrl-c!
  "Handle Ctrl-C press. Called from the input reader thread.
   Single press: cancel running ask or emit hint.
   Double press within 1s: exit."
  []
  (let [now  (System/currentTimeMillis)
        last @!last-ctrl-c-ms]
    (reset! !last-ctrl-c-ms now)
    (if (< (- now last) 1000)
      ;; Double Ctrl-C within 1s → exit (shutdown hook cleans up terminal)
      (System/exit 0)
      ;; Single Ctrl-C → cancel if agent running, else queue hint
      (if-let [t @!ask-thread]
        (do (when-let [ag (tui-session/get-active-agent)]
              (agent/cancel-run (:!state ag)))
            (.interrupt ^Thread t))
        ;; No ask running — queue :sigint so read-line-raw! can show hint
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
              (tui-session/emit! (ansi/muted "[paused] (Ctrl-\\ to resume, Ctrl-C to cancel)"))))
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

(defn try-intercept-byte
  "Try to intercept a raw byte for pending permission or feedback prompts.
   Returns true if intercepted (byte consumed), nil if not.
   Permission: y/Y(yes), n/N(no), a/A(always).
   Feedback: 1-6 (digit keys) in :selecting mode, full line in :awaiting-text mode.
   Awaiting-text supports multi-byte UTF-8 (CJK) via byte buffering."
  [b]
  (or (when-let [p @tui-session/!pending-permission]
        (cond
          (or (= b 121) (= b 89)) (do (deliver p :yes)    true)
          (or (= b 110) (= b 78)) (do (deliver p :no)     true)
          (or (= b 97)  (= b 65)) (do (deliver p :always) true)
          :else nil))
      (when-let [{:keys [promise options mode buf free-idx] :as fb} @tui-session/!pending-feedback]
        (case (or mode :selecting)
          :selecting
          (let [n (- b 48)]  ;; byte 49='1' → n=1, byte 54='6' → n=6
            (when (and (>= n 1) (<= n (count options)))
              (let [idx (dec n)
                    selected (nth options idx)]
                (if (:free-input selected)
                  ;; Transition to text input mode
                  (do (swap! tui-session/!pending-feedback assoc
                             :mode :awaiting-text
                             :free-idx idx
                             :buf (StringBuilder.))
                      ;; Drop the sticky live-block before the text-mode prompt
                      ;; so chars echo right after "Type your response: " in the
                      ;; scroll region (write-raw-chars! leaves cursor there).
                      (layout/dispose-live-block! permissions/user-feedback-block-id)
                      (tui-session/emit! (str "\n  " (ansi/muted "Type your response: ")))
                      true)
                  ;; Normal selection
                  (do (deliver promise {:selected (:label selected) :index idx})
                      true)))))

          :awaiting-text
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

              ;; Enter — deliver the typed text
              (or (= b 13) (= b 10))
              (let [text (.toString sb)
                    selected (nth options free-idx)]
                (layout/write-raw-chars! "\r\n")
                (deliver promise {:selected (:label selected) :index free-idx :input text})
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
              :else true))))))

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
