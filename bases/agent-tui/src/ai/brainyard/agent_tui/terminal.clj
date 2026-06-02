;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent-tui.terminal
  "Terminal mode management: raw/cooked mode, keystroke reading, input line
   rendering, signal handlers (SIGWINCH, SIGINT), and input history."
  (:require [ai.brainyard.agent-tui.layout :as layout]
            [ai.brainyard.agent-tui.session :as tui-session]
            [ai.brainyard.agent-tui.input :as input]
            [ai.brainyard.agent.interface.tui.format :as fmt]
            [ai.brainyard.agent.interface.tui.ansi :as ansi]
            [ai.brainyard.agent.interface :as agent]
            [clojure.string :as str])
  (:import [java.io InputStream]
           [java.util.concurrent LinkedBlockingQueue]))

;; ============================================================================
;; Raw Terminal Input
;; ============================================================================

(defn stty!
  "Run stty command targeting the controlling terminal via /dev/tty."
  [args]
  (try
    (let [cmd  ["/bin/sh" "-c" (str "stty " args " < /dev/tty")]
          proc (.start (ProcessBuilder. ^java.util.List cmd))]
      (.waitFor proc 2 java.util.concurrent.TimeUnit/SECONDS))
    (catch Exception _ nil)))

(defn set-raw-mode!
  "Set terminal to raw input mode (no line buffering, no echo, no signals).
   Disables isig so Ctrl-C produces byte 3 instead of SIGINT — prevents
   parent process (bb) from catching SIGINT and killing the JVM child.

   Also disables tty-level control characters that would otherwise eat
   keystrokes before they reach the application:
     -ixon         disable Ctrl-S/Ctrl-Q flow control (pause/resume output)
     discard undef disable Ctrl-O 'discard output' (was silently eating ^O)

   Ctrl-V (lnext) is intentionally left enabled because paste handling
   relies on it via the bracketed-paste sequence below.

   Enables bracketed paste so pasted text is wrapped with ESC[200~/ESC[201~."
  []
  (stty! "-icanon -echo -isig -ixon discard undef")
  (print ansi/enable-bracketed-paste)
  (flush))

(defn restore-cooked-mode!
  "Restore terminal to normal (cooked) mode."
  []
  (print ansi/disable-bracketed-paste)
  (flush)
  (vreset! input/!pasting? false)
  (stty! "sane"))

(defn stdin-terminal?
  "Check if stdin is a real terminal (not piped input)."
  []
  (try
    (let [proc (.start (ProcessBuilder. ^"[Ljava.lang.String;"
                        (into-array String ["/bin/sh" "-c" "test -t 0 < /dev/tty"])))
          exit (.waitFor proc)]
      (zero? exit))
    (catch Exception _ false)))

(defn read-key!
  "Read a single keystroke in raw mode.
   When the input reader thread is active, reads from the input queue.
   Otherwise reads directly from stdin (fallback).
   Returns keyword for special keys, string for printable chars, or nil for EOF.
   Special keys: :page-up, :page-down, :enter, :alt-enter, :backspace,
                 :ctrl-a, :ctrl-e, :ctrl-k, :ctrl-n, :ctrl-o, :ctrl-p, :ctrl-t, :ctrl-w,
                 :ctrl-d, :arrow-left, :arrow-right,
                 :shift-arrow-left, :shift-arrow-right, :sigint, :unknown"
  [^InputStream in]
  (let [;; Unified byte reader: from queue if reader thread active, else direct
        read-byte (fn [] (if @input/!input-reader-thread
                           (let [item (.take ^LinkedBlockingQueue input/!raw-input-queue)]
                             (cond
                               (= item :eof)    (int -1)
                               (= item :sigint) (int 3)
                               (keyword? item)  (int -2)
                               :else            (int item)))
                           (.read in)))
        ;; Non-blocking check: bytes available in queue or stream
        available (fn [] (if @input/!input-reader-thread
                           (.size ^LinkedBlockingQueue input/!raw-input-queue)
                           (.available in)))
        ;; Drain remaining bytes from an escape sequence
        drain (fn [] (while (pos? (long (available))) (read-byte)))
        b     (long (read-byte))]
    (cond
      (= b -1)  :ctrl-d                ;; EOF
      (= b -2)  :unknown               ;; unknown sentinel
      (= b 3)   :sigint                ;; Ctrl-C (queued as hint by reader thread)
      (= b 4)   :ctrl-d                ;; Ctrl-D
      (= b 1)   :ctrl-a                ;; Ctrl-A (beginning of line)
      (= b 5)   :ctrl-e                ;; Ctrl-E (end of line)
      (= b 11)  :ctrl-k                ;; Ctrl-K (kill to end of line)
      (= b 14)  :ctrl-n                ;; Ctrl-N (next session)
      (= b 15)  :ctrl-o                ;; Ctrl-O (open selected marker in $EDITOR, scroll mode)
      (= b 16)  :ctrl-p                ;; Ctrl-P (previous session)
      (= b 20)  :ctrl-t                ;; Ctrl-T (new session)
      (= b 23)  :ctrl-w                ;; Ctrl-W (close session)
      (= b 9)   :tab                   ;; Tab
      (= b 13)  (if @input/!pasting? :alt-enter :enter)  ;; CR — newline during paste
      (= b 10)  (if @input/!pasting? :alt-enter :enter)  ;; LF — newline during paste
      (= b 127) :backspace             ;; DEL
      (= b 8)   :backspace             ;; BS

      ;; ESC — could be escape sequence or standalone ESC
      (= b 27)
      (do
        (Thread/sleep (long 2))          ;; brief wait for rest of sequence
        (if (pos? (long (available)))
          (let [b2 (long (read-byte))]
            (cond
              ;; Alt+Enter: ESC followed by CR (13) or LF (10)
              (or (= b2 13) (= b2 10)) :alt-enter

              ;; CSI sequence: ESC [ ...
              (= b2 (long (int \[)))
              (let [b3 (long (read-byte))]
                (cond
                  ;; Arrow keys: ESC[A = Up, ESC[B = Down
                  ;; ?1007h alternate scroll mode sends these for scroll wheel
                  (= b3 (long (int \A))) :scroll-up
                  (= b3 (long (int \B))) :scroll-down
                  (= b3 (long (int \C))) :arrow-right
                  (= b3 (long (int \D))) :arrow-left

                  (= b3 (long (int \~)))
                  :unknown

                  ;; ESC[1;2C = Shift+Right, ESC[1;2D = Shift+Left
                  (= b3 (long (int \1)))
                  (if (pos? (long (available)))
                    (let [b-semi (long (read-byte))]
                      (if (and (= b-semi (long (int \;))) (pos? (long (available))))
                        (let [b-mod (long (read-byte))]
                          (if (pos? (long (available)))
                            (let [b-final (long (read-byte))]
                              (cond
                                (and (= b-mod (long (int \2))) (= b-final (long (int \C)))) :shift-arrow-right
                                (and (= b-mod (long (int \2))) (= b-final (long (int \D)))) :shift-arrow-left
                                :else (do (drain) :unknown)))
                            :unknown))
                        (do (drain) :unknown)))
                    :unknown)

                  ;; Bracketed paste: ESC[200~ (start) / ESC[201~ (end)
                  ;; and other multi-digit CSI sequences: ESC[N~ or ESC[NNN~
                  :else
                  (if (pos? (long (available)))
                    (let [b4 (long (read-byte))]
                      (cond
                        ;; ESC[5~ = Page Up
                        (and (= b3 (long (int \5))) (= b4 (long (int \~)))) :page-up
                        ;; ESC[6~ = Page Down
                        (and (= b3 (long (int \6))) (= b4 (long (int \~)))) :page-down
                        ;; ESC[2... = could be bracketed paste (200~/201~)
                        (and (= b3 (long (int \2))) (= b4 (long (int \0))))
                        (if (pos? (long (available)))
                          (let [b5 (long (read-byte))]
                            (if (pos? (long (available)))
                              (let [b6 (long (read-byte))]
                                (cond
                                  ;; ESC[200~ = paste start
                                  (and (= b5 (long (int \0))) (= b6 (long (int \~))))
                                  (do (vreset! input/!pasting? true) :unknown)
                                  ;; ESC[201~ = paste end
                                  (and (= b5 (long (int \1))) (= b6 (long (int \~))))
                                  (do (vreset! input/!pasting? false) :unknown)
                                  :else (do (drain) :unknown)))
                              (do (drain) :unknown)))
                          (do (drain) :unknown))
                        :else
                        (do (drain) :unknown)))
                    ;; Only 3 bytes: CSI + letter (e.g., arrow keys C/D)
                    :unknown)))
              ;; ESC + non-[ : alt-key or unknown
              :else (do (drain) :unknown)))
          ;; Standalone ESC
          :escape))

      ;; UTF-8 multi-byte sequences
      (>= b 0xC0)
      (let [byte-count (cond
                         (< b 0xE0) 2
                         (< b 0xF0) 3
                         :else      4)
            bytes (byte-array byte-count)]
        (aset bytes 0 (unchecked-byte b))
        (dotimes [i (dec byte-count)]
          (aset bytes (inc i) (unchecked-byte (read-byte))))
        (String. bytes "UTF-8"))

      ;; Regular printable ASCII
      :else
      (String. (byte-array [(unchecked-byte b)]) "UTF-8"))))

(defn- char-width
  "Column width of the Unicode codepoint at char-index idx in s.
   Returns [cw next-idx] where cw is 0/1/2 and next-idx skips surrogate pairs."
  [^String s ^long idx]
  (let [cp (Character/codePointAt s (int idx))
        n  (Character/charCount cp)
        ;; Quick classification via display-width of a 1-codepoint substring
        cw (fmt/display-width (.substring s idx (+ idx n)))]
    [cw (+ idx n)]))

(defn- wrap-line-to-width
  "Word-wrap a single logical line (no \\n inside) to visual segments that each
   fit within width display columns. Returns a vector of {:text :start :end}
   where start/end are char indices into the buffer (offset by line-start-idx).
   Prefers whitespace break points; falls back to hard breaks when no whitespace fits."
  [^String line-str line-start-idx ^long width]
  (let [len (.length line-str)]
    (if (zero? len)
      [{:text "" :start line-start-idx :end line-start-idx}]
      (loop [i 0, segs (transient [])]
        (if (>= i len)
          (persistent! segs)
          ;; Find how many chars fit starting from i within `width` columns
          (let [end-i (loop [j i, w 0]
                        (if (>= j len)
                          j
                          (let [[cw nxt] (char-width line-str j)]
                            (if (and (pos? cw) (> (+ w cw) width))
                              j
                              (recur nxt (+ w cw))))))
                ;; If we're at end of line, take it all
                break-i (if (>= end-i len)
                          end-i
                          ;; Try to break at last whitespace before end-i
                          (let [ws-idx (loop [k (dec end-i)]
                                         (cond
                                           (< k i) -1
                                           (Character/isWhitespace (.charAt line-str k))
                                           (inc k)  ;; include the whitespace in the segment
                                           :else (recur (dec k))))]
                            (if (pos? ws-idx) ws-idx end-i)))
                seg-text (subs line-str i break-i)]
            (recur break-i
                   (conj! segs {:text seg-text
                                :start (+ line-start-idx i)
                                :end (+ line-start-idx break-i)}))))))))

(defn layout-buffer
  "Given buffer text and available width, return
   {:visual-lines [{:text :start :end :hard-break?} ...]
    :cursor-row <0-based> :cursor-col <0-based>}.

   Hard breaks from \\n split the buffer; each resulting segment is word-wrapped
   to fit within `width` display columns.

   cursor-pos is a linear 0-based char index into the buffer.
   cursor-row/col are the visual-line row/col where the cursor sits."
  [^String buffer ^long cursor-pos ^long width]
  (let [width (max 1 width)
        hard-lines (str/split (str buffer) #"\n" -1)  ;; preserves trailing empties
        ;; Build visual lines with absolute char indices in buffer
        visual-lines
        (loop [idx 0, hard-idx 0, acc (transient [])]
          (if (>= hard-idx (count hard-lines))
            (persistent! acc)
            (let [hl (nth hard-lines hard-idx)
                  segs (wrap-line-to-width hl idx width)
                  ;; Mark first segment of each hard line with :hard-break? true (except first overall)
                  n-segs (count segs)
                  segs' (map-indexed
                         (fn [i s] (assoc s :hard-break? (and (zero? i) (pos? hard-idx))))
                         segs)
                  next-idx (+ idx (count hl) 1)]  ;; +1 for the \n consumed
              (recur next-idx (inc hard-idx) (reduce conj! acc segs')))))
        ;; Locate cursor in visual-lines.
        ;; A visual line owns cursor positions [start, end). The EXCEPTION is
        ;; cursor = end of line i when:
        ;;   (a) line i is the very last visual line (cursor at EOF), OR
        ;;   (b) line (i+1) is a HARD break (there is a \n between them):
        ;;       gap = (:start line-(i+1)) - (:end line-i) > 0
        ;;       In this case cursor-pos == end sits at end of current line
        ;;       (before the \n), not at start of the next line.
        ;; For SOFT wraps (no gap: start-of-next == end-of-current), cursor ==
        ;; end belongs to the START of the next line.
        n-vl (count visual-lines)
        [cursor-row cursor-col]
        (loop [i 0]
          (if (>= i n-vl)
            (let [last-line (last visual-lines)]
              [(dec n-vl) (fmt/display-width (:text last-line))])
            (let [{:keys [start end]} (nth visual-lines i)
                  is-last? (= i (dec n-vl))
                  next-start (when-not is-last? (:start (nth visual-lines (inc i))))
                  hard-break-after? (and (some? next-start) (> next-start end))
                  in-range? (or (and (>= cursor-pos start) (< cursor-pos end))
                                (and (= cursor-pos end)
                                     (or is-last? hard-break-after?)))]
              (if in-range?
                (let [text-before (subs (:text (nth visual-lines i))
                                        0
                                        (max 0 (- cursor-pos start)))]
                  [i (fmt/display-width text-before)])
                (recur (inc i))))))]
    {:visual-lines visual-lines
     :cursor-row cursor-row
     :cursor-col cursor-col}))

(defn redraw-input-line!
  "Redraw the input prompt with current buffer content and cursor position.
   Shows placeholder hint when buffer is empty. Supports multi-row rendering:
   the input buffer is word-wrapped to the terminal width, hard newlines
   (Alt+Enter) split into separate visual lines, and the chrome layout grows
   to accommodate up to `:input-height-max` rows.

   cursor-pos is the 0-based char position within the buffer text."
  [buffer cursor-pos]
  (let [prompt (ansi/style "> " ansi/bold ansi/bright-cyan)
        prompt-w 2                          ;; visible columns of "> "
        indent (apply str (repeat prompt-w \space))
        buf-empty? (empty? buffer)
        cols (or (:cols @layout/!layout) 80)
        rows (or (:rows @layout/!layout) 24)
        fullscreen? (layout/fullscreen?)
        ;; Reserve available width = cols minus prompt
        avail-w (max 1 (- cols prompt-w))
        {:keys [visual-lines cursor-row cursor-col]}
        (if buf-empty?
          ;; Placeholder as a single empty visual line
          {:visual-lines [{:text "" :start 0 :end 0 :hard-break? false}]
           :cursor-row 0 :cursor-col 0}
          (layout-buffer buffer cursor-pos avail-w))
        n-vl (count visual-lines)
        max-h (if fullscreen?
                (or (:input-height-max @layout/!layout) (max 3 (quot rows 3)))
                n-vl)
        input-h (max 1 (min n-vl max-h))]
    (if fullscreen?
      (do
        ;; Grow/shrink the input area to fit the visual line count
        (when-not (= input-h (or (:input-height @layout/!layout) 1))
          (layout/set-input-height! input-h))
        ;; Viewport of visual lines: keep cursor-row on screen (scroll when needed)
        (let [vl-start (cond
                         (<= n-vl input-h) 0
                         (< cursor-row input-h) 0
                         :else (- cursor-row (dec input-h)))
              vl-end   (min n-vl (+ vl-start input-h))
              input-row (:input-row @layout/!layout)
              screen-cursor-row (+ input-row (- cursor-row vl-start))
              screen-cursor-col (+ prompt-w cursor-col 1)
              sb (StringBuilder.)]
          ;; Paint each visible visual line in the input block
          (loop [screen-i 0, vl-idx vl-start]
            (when (< vl-idx vl-end)
              (let [row (+ input-row screen-i)
                    {:keys [text]} (nth visual-lines vl-idx)
                    line-prefix (if (zero? vl-idx) prompt indent)
                    body (if (and buf-empty? (zero? screen-i))
                           (ansi/muted "Alt+Enter: newline, /help for commands")
                           text)]
                (.append sb (ansi/cursor-to row 1))
                (.append sb ^String ansi/erase-line)
                (.append sb ^String line-prefix)
                (.append sb ^String body)
                (recur (inc screen-i) (inc vl-idx)))))
          ;; Erase any unused input rows (when input-h shrunk mid-redraw)
          (loop [screen-i (- vl-end vl-start)]
            (when (< screen-i input-h)
              (.append sb (ansi/cursor-to (+ input-row screen-i) 1))
              (.append sb ^String ansi/erase-line)
              (recur (inc screen-i))))
          ;; Position cursor and remember row+col so chrome restorers (live block
          ;; tickers, status bar) put the cursor back on the user's actual line
          ;; instead of snapping to the top of the input block.
          (.append sb (ansi/cursor-to screen-cursor-row screen-cursor-col))
          (layout/set-input-cursor-pos! screen-cursor-row screen-cursor-col)
          (layout/draw-overlay!
           (fn [w] (layout/raw-write-unsafe! w (.toString sb))))))
      ;; Inline mode — single-line with placeholder behavior preserved
      (let [line (if buf-empty?
                   (str prompt (ansi/muted "Alt+Enter: newline, /help for commands"))
                   (str prompt buffer))
            col-num (if buf-empty? 3 (+ prompt-w (fmt/display-width (subs buffer 0 cursor-pos)) 1))
            cursor-col (str ansi/esc (str col-num) "G")]
        (layout/set-input-cursor-col! col-num)
        (tui-session/emit-inline! (str "\r" line "  " cursor-col))))))

(defonce !input-history (atom []))
(def max-history-size 100)

;; ============================================================================
;; SIGWINCH — Terminal Resize
;; ============================================================================

(defonce !old-winch-handler (atom nil))

(defn install-sigwinch-handler!
  "Install SIGWINCH handler via sun.misc.Signal to detect terminal resize.
   Calls layout/handle-resize! then redraws the input prompt."
  []
  (try
    (let [signal  (sun.misc.Signal. "WINCH")
          handler (proxy [sun.misc.SignalHandler] []
                    (handle [_sig]
                      (layout/handle-resize!)
                      (layout/draw-input-prompt!
                       (ansi/style "> " ansi/bold ansi/bright-cyan))))]
      (reset! !old-winch-handler (sun.misc.Signal/handle signal handler)))
    (catch Exception _ nil)))

(defn remove-sigwinch-handler!
  "Restore the previous SIGWINCH handler."
  []
  (try
    (when-let [old @!old-winch-handler]
      (sun.misc.Signal/handle (sun.misc.Signal. "WINCH") old)
      (reset! !old-winch-handler nil))
    (catch Exception _ nil)))

;; ============================================================================
;; SIGINT — Ctrl-C Cancellation
;; ============================================================================

(defonce !old-sigint-handler (atom nil))

(defn install-sigint-handler!
  "Install SIGINT handler as fallback for non-raw mode (piped input).
   In raw mode, Ctrl-C is handled via the input reader thread instead."
  []
  (try
    (let [signal  (sun.misc.Signal. "INT")
          handler (proxy [sun.misc.SignalHandler] []
                    (handle [_sig]
                      (let [now  (System/currentTimeMillis)
                            last @input/!last-ctrl-c-ms]
                        (reset! input/!last-ctrl-c-ms now)
                        (if (< (- now last) 1000)
                          (System/exit 0)
                          (if-let [t @input/!ask-thread]
                            (do (when-let [ag (tui-session/get-active-agent)]
                                  (agent/cancel-run (:!state ag)))
                                (.interrupt ^Thread t))
                            (tui-session/emit!
                             (ansi/muted "Press Ctrl-C again to quit, or type /quit")))))))]
      (reset! !old-sigint-handler (sun.misc.Signal/handle signal handler)))
    (catch Exception _ nil)))

(defn remove-sigint-handler!
  "Restore the previous SIGINT handler."
  []
  (when-let [old @!old-sigint-handler]
    (try
      (sun.misc.Signal/handle (sun.misc.Signal. "INT") old)
      (catch Exception _))
    (reset! !old-sigint-handler nil)))
