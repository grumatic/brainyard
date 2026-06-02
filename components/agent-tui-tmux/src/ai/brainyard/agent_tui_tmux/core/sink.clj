;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent-tui-tmux.core.sink
  "Per-pane Sinks — see docs/tmux-based-agent-tui.md §12.

   **Retired substrate (May 2026).** Kept as test-only/internal after the
   `by-host`↔`by-ui` daemon split was retired. No shipping consumer (only
   the per-component test suite). See `docs/tui/architecture.md` §9 and
   `docs/specs/tui.md` CR-TUI-20.

   A `Sink` is a thin abstraction over \"where to send ANSI bytes for one of
   the panes that make up an agent window\".  The current TUI has three
   addressable surfaces:

     :stream    — main scrollback + streaming LLM output + tool I/O.  Activity
                  blocks (agent / tasks / todo) ride along here as sticky live
                  blocks unless an :activity sink is also registered.
     :activity  — the optional dedicated activity pane (`/activity show`).
     :status    — the one-line status bar.

   Three concrete implementations are provided:

     `pipe-sink`  — writes to a Unix named pipe (FIFO).  Used by `by-host`
                    when its corresponding `by-ui` has wired the FIFO to a
                    tmux pane via `tmux pipe-pane`.
     `writer-sink` — writes to any `java.io.Writer`.  Used for inline mode
                    (route to `*out*`) and tests (route to a `StringWriter`).
     `null-sink`  — discards output.  Used to suppress an optional surface
                    (e.g. activity when the dedicated pane is hidden).

   A `multi-sink` aggregates one sink per channel; that is what callers
   actually hold and what hook handlers route through."
  (:require [clojure.java.io :as io])
  (:import [java.io File Writer OutputStreamWriter FileOutputStream Closeable]
           [java.nio.charset StandardCharsets]))

(def channels
  "All recognised channel keys in routing order."
  [:stream :activity :status])

(defprotocol Sink
  (write!  [this s] "Write a string of ANSI bytes to the sink.")
  (flush!  [this]   "Force any buffered output to its destination.")
  (close*  [this]   "Close this sink.  Idempotent."))

;; -- Helpers ------------------------------------------------------------------

(defn write-string!
  "Convenience: convert non-string `payload` (CharSequence, byte[], etc.) to a
   String and write."
  [sink payload]
  (let [s (cond
            (string? payload) payload
            (instance? CharSequence payload) (.toString ^CharSequence payload)
            (bytes? payload) (String. ^bytes payload StandardCharsets/UTF_8)
            :else (str payload))]
    (write! sink s)))

;; -- writer-sink --------------------------------------------------------------

(deftype WriterSink [^Writer w ^:unsynchronized-mutable closed?]
  Sink
  (write! [this s]
    (when-not closed?
      (.write w (str s))
      this))
  (flush! [_] (.flush w))
  (close* [_]
    (when-not closed?
      (set! closed? true)
      (try (.flush w) (catch Throwable _))
      (try (.close w) (catch Throwable _)))))

(defn writer-sink
  "Build a sink that writes to a `java.io.Writer`."
  [^Writer w]
  (->WriterSink w false))

;; -- pipe-sink ----------------------------------------------------------------
;;
;; Pipes are POSIX FIFOs (mkfifo) — `by-ui` calls `tmux pipe-pane "cat >> path"`
;; which keeps the consumer side open; `by-host` opens the writer side in
;; append mode.  The `auto-create?` flag tries `mkfifo path` if the file is
;; missing.

(defn ensure-fifo!
  "Create a FIFO at `path` if it does not exist.  No-op when present.
   Returns true when the FIFO is now in place."
  [path]
  (let [^File f (io/file path)]
    (if (.exists f)
      true
      (let [parent (.getParentFile f)
            _      (when parent (.mkdirs parent))
            res    (try
                     (let [pb (ProcessBuilder. ^"[Ljava.lang.String;"
                               (into-array String ["mkfifo" (.getAbsolutePath f)]))]
                       (.waitFor (.start (.redirectErrorStream pb true))))
                     (catch Throwable _ -1))]
        (or (zero? res) (.exists f))))))

(defn- ^Writer open-fifo-writer
  "Open a writer for a FIFO at `path`.

   `FileOutputStream(path, true)` requests write-only (O_WRONLY) and BLOCKS
   on FIFOs until a reader connects.  This is correct for production
   (tmux's `pipe-pane` is the long-lived reader), but problematic in tests
   that don't have a reader: the open hangs the calling thread.

   `RandomAccessFile(path, \"rw\")` opens with read+write (O_RDWR), which
   on POSIX FIFOs does NOT block — the kernel pairs both endpoints
   internally.  Writes go to the kernel pipe buffer; an external reader
   (when it appears) consumes them.  We wrap the underlying file
   descriptor in a `FileOutputStream` so subsequent writes go through the
   normal Stream/Writer API."
  [^File path]
  (let [raf (java.io.RandomAccessFile. path "rw")
        fd  (.getFD raf)]
    (-> (FileOutputStream. fd)
        (OutputStreamWriter. StandardCharsets/UTF_8))))

(defn- attempt-write
  "Write `s` to the writer; return ::ok on success, ::failed on IOException."
  [^Writer w s]
  (try
    (.write w (str s))
    (.flush w)
    ::ok
    (catch java.io.IOException _
      (try (.close w) (catch Throwable _))
      ::failed)))

(deftype PipeSink [^File path
                   ^{:unsynchronized-mutable true :tag Writer} writer
                   ^:unsynchronized-mutable closed?]
  Sink
  (write! [this s]
    (when-not closed?
      (when (nil? writer)
        (set! writer (open-fifo-writer path)))
      (let [outcome (attempt-write writer s)]
        (when (= outcome ::failed)
          (set! writer nil))
        this)))
  (flush! [_] (when writer (try (.flush ^Writer writer) (catch Throwable _))))
  (close* [_]
    (when-not closed?
      (set! closed? true)
      (when writer
        (try (.flush ^Writer writer) (catch Throwable _))
        (try (.close ^Writer writer) (catch Throwable _))
        (set! writer nil)))))

(defn pipe-sink
  "Build a sink that writes to the FIFO at `path`.

   Options:
     :auto-create? — call `mkfifo path` if absent (default false)."
  ([path] (pipe-sink path {}))
  ([path {:keys [auto-create?]}]
   (let [^File f (io/file path)]
     (when auto-create? (ensure-fifo! f))
     (->PipeSink f nil false))))

;; -- null-sink ----------------------------------------------------------------

(deftype NullSink []
  Sink
  (write!  [this _] this)
  (flush!  [_])
  (close*  [_]))

(def null-sink (->NullSink))

;; -- multi-sink ---------------------------------------------------------------

(defprotocol MultiSink
  (sink-of  [this channel] "Return the Sink for `channel` (e.g. :stream).")
  (write-channel! [this channel s] "Write to a specific channel by tag.")
  (close-all!     [this] "Close every member sink.  Idempotent.")
  (set-channel!   [this channel sink] "Install or replace one channel sink."))

(defrecord ChannelSinks [!channels]
  MultiSink
  (sink-of [_ channel] (or (get @!channels channel) null-sink))
  (write-channel! [this channel s]
    (write-string! (sink-of this channel) s)
    this)
  (close-all! [_]
    (doseq [[_ s] @!channels]
      (try (close* s) (catch Throwable _)))
    (reset! !channels {}))
  (set-channel! [this channel sink]
    (when-let [old (get @!channels channel)]
      (try (close* old) (catch Throwable _)))
    (swap! !channels assoc channel sink)
    this))

(defn multi-sink
  "Build a MultiSink from a `{channel sink}` map.  Each channel listed in
   `channels` defaults to `null-sink` if absent."
  ([] (multi-sink {}))
  ([channel-map]
   (let [base   (zipmap channels (repeat null-sink))
         merged (merge base channel-map)]
     (->ChannelSinks (atom merged)))))

;; -- routing helpers ----------------------------------------------------------

(defn write-stream!   [ms s] (write-channel! ms :stream s))
(defn write-activity! [ms s] (write-channel! ms :activity s))
(defn write-status!   [ms s] (write-channel! ms :status s))

(defn flush-all!
  "Flush every member sink."
  [^ChannelSinks ms]
  (doseq [[_ s] @(:!channels ms)]
    (try (flush! s) (catch Throwable _))))
