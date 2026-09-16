;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent-tui.tmux-side
  "Mode-B side-channel: split tmux panes for activity/log streams and route
   bytes through per-pane FIFOs. Owned by the in-process renderer; activates
   only when `mode/probe` returned :B.

   Lifecycle:
     install!   on Mode-B start — caches the Tmux impl + the renderer's pane id
     uninstall! on stop — kills any spawned side panes, closes FIFO writers,
                  and puts the server-global wheel bindings back as found

   The pane that hosts the renderer (`by` itself) is *never* a side pane — we
   only ever split *off* it. Input is never routed through a side channel.

   This namespace deliberately exposes a small API; the slash commands in
   Phase 4 (`/activity show|hide`, `/log show|hide`, `/scrollback dump`,
   `/popup test`) drive it. Tests use `tmux-iface/stub-tmux` and assert via
   `stub-calls-of`."
  (:require [ai.brainyard.agent-tui-tmux.interface :as tmux-iface]
            [ai.brainyard.agent-tui.log :as tui-log]
            [clojure.string :as str])
  (:import [java.io Writer]))

;; ----------------------------------------------------------------------------
;; State
;; ----------------------------------------------------------------------------

(defonce ^:private !state
  (atom {:tmux        nil    ;; tmux impl (RealTmux / StubTmux), nil = uninstalled
         :host-pane   nil    ;; pane id that hosts the renderer (never killed)
         :activity    nil    ;; {:pane-id, :fifo-path, :writer} or nil
         :log         nil    ;; same shape
         :session-dir nil    ;; <project>/.brainyard/sessions/<id>/ for FIFO/scrollback files
         :prior-mouse nil    ;; pre-install tmux `mouse` value, restored on uninstall!
         :prior-wheel nil})) ;; pre-install WheelUp/DownPane bind-key lines, ditto

(defn state
  "Read-only snapshot of side-channel state. Tests use this; production code
   should call the operations below."
  [] @!state)

(defn installed?
  "True when a Tmux impl is wired up (Mode B). Operations below short-circuit
   to nil when this is false."
  []
  (some? (:tmux @!state)))

;; ----------------------------------------------------------------------------
;; Pane discovery
;; ----------------------------------------------------------------------------

(defn- current-pane-id
  "Resolve the pane id of the renderer's tmux pane. Reads `$TMUX_PANE` first
   (set by tmux when the process starts inside a pane); falls back to `tmux
   display -p '#{pane_id}'` against the impl. Returns the `%N` id or nil."
  [tmux]
  (or (some-> (System/getenv "TMUX_PANE") str/trim not-empty)
      (try
        (let [s (tmux-iface/display-message tmux {:format "#{pane_id}"})]
          (when (and s (re-find #"^%\d+" (str/trim s)))
            (str/trim s)))
        (catch Throwable _ nil))))

;; ----------------------------------------------------------------------------
;; Wheel-to-arrow bindings (the fallback for a TUI with the mouse OFF)
;; ----------------------------------------------------------------------------
;;
;; Inside tmux the host terminal's DECSET ?1007h alternate-scroll-mode (which
;; the TUI relies on outside tmux to translate wheel ticks into ESC[A / ESC[B)
;; never reaches the inner client.  As a workaround we install root-table
;; bindings that translate WheelUpPane / WheelDownPane into literal `Up` /
;; `Down` keys for a pane on the alt-screen that has NOT asked for the mouse.
;;
;; `#{mouse_any_flag}` is tested FIRST, and the order is the whole point.  Key
;; bindings are SERVER-global — every pane on this tmux server gets them, not
;; only the ones we started — and a pane that turned mouse reporting on already
;; scrolls its own view on a real wheel event.  That is Claude Code in the
;; desktop app's terminal tabs, and it is also THIS TUI whenever `:enable-mouse`
;; is left at its default: `ansi/enable-mouse` sends `?1000h ?1006h` and
;; `terminal/decode-sgr-mouse` turns wheel buttons 64/65 into `:scroll-up` /
;; `:scroll-down` without any help from tmux.  Testing `#{alternate_on}` first,
;; as this did, handed every one of those panes a bare `Up` instead — which a
;; full-screen agent reads as history navigation, not as a scroll.
;;
;; So the arrow translation is only the fallback it was always meant to be:
;; `:enable-mouse false` (or BY_MOUSE=false) leaves the TUI on the alt-screen
;; with no mouse, and that is the one case these bindings still rescue.
;;
;; The target token is `=`, NOT `{mouse}`.  In a mouse-binding context tmux
;; resolves `=` to the pane the mouse event hit.  `{mouse}` would also point
;; there, but tmux's command parser treats `{...}` as a brace-block — passing
;; `{mouse}` as a `-t` arg causes "unknown command: mouse" because tmux tries
;; to run `mouse` as the body of a block.
;;
;; A pane already in copy-mode rides along with the mouse-driven ones: tmux's
;; copy-mode consumes `send-keys -M` itself, so it needs no arrow.
;;
;; For everything left over (less, man, plain shell, the `/log` tail pane) we
;; mirror tmux's default WheelUpPane behavior and enter copy-mode with
;; `copy-mode -et=`.  Earlier versions only did `send-keys -M` here, which
;; forwarded a raw mouse byte to (e.g.) `tail -F` — the process ignored it and
;; the user saw no scroll at all in sibling panes.
;;
;; WheelDownPane's else branch stays at `send-keys -M`, matching tmux's
;; default (scroll-down past the latest output is a no-op outside copy-mode).
;;
;; This mirrors the non-tmux code path: terminal.clj `read-key!` maps
;; `ESC[A` / `ESC[B` to `:scroll-up` / `:scroll-down`.

(defn- mouse-setting
  "Read tmux's current global `mouse` option value (\"on\"/\"off\"); nil on failure."
  [tmux]
  (try
    (let [v (tmux-iface/display-message tmux {:format "#{mouse}"})]
      (some-> v str/trim not-empty))
    (catch Throwable _ nil)))

;; The two root-table keys we take over, and give back.
(def ^:private wheel-keys ["WheelUpPane" "WheelDownPane"])

(defn- capture-wheel-bindings
  "The root-table `bind-key` lines for the wheel keys exactly as tmux prints
   them, so `uninstall!` can put them back verbatim.

   `list-keys` output IS tmux config syntax — that is what makes a round trip
   through `source-file` safe, and why this keeps the raw text instead of
   parsing it into args we would then have to re-quote. A key that is unbound
   prints nothing (or errors), and comes back as no line at all, which restores
   to unbound.

   Returns the joined lines, or nil when neither key was bound."
  [tmux]
  (let [lines (keep (fn [k]
                      (try
                        (let [{:keys [exit stdout]}
                              (tmux-iface/run-shell tmux {:args ["list-keys" "-T" "root" k]})]
                          (when (and (zero? (long (or exit 1))) (not (str/blank? stdout)))
                            (str/trim stdout)))
                        (catch Throwable _ nil)))
                    wheel-keys)]
    (when (seq lines) (str/join "\n" lines))))

(defn- restore-wheel-bindings!
  "Put the wheel keys back the way `capture-wheel-bindings` found them.

   Unbind FIRST, then replay what was saved: a key that was unbound before we
   arrived has no saved line, and unbinding is the whole of restoring it.

   Replayed through a temp file and `source-file` rather than a rebuilt
   `bind-key` argv — the saved text is already tmux syntax, and re-splitting a
   line whose arguments are themselves quoted command strings is exactly the
   quoting bug this avoids."
  [tmux saved]
  (doseq [k wheel-keys]
    (try
      (tmux-iface/run-shell tmux {:args ["unbind-key" "-T" "root" k]})
      (catch Throwable _)))
  (when-not (str/blank? saved)
    (try
      (let [f (java.io.File/createTempFile "by-wheel-" ".tmux")]
        (.deleteOnExit f)
        (try
          (spit f (str saved "\n"))
          (tmux-iface/run-shell tmux {:args ["source-file" (.getAbsolutePath f)]})
          (finally (.delete f))))
      (catch Throwable _))))

(defn- install-wheel-bindings!
  "Enable `mouse on` and register WheelUp/Down -> Up/Down bindings scoped to
   alt-screen apps that did NOT ask for the mouse.

   Returns `{:mouse <prior mouse value or nil> :wheel <prior bind-key lines or
   nil>}` so `uninstall!` can put both back. Capturing the bindings matters
   because they are SERVER-global: unbinding on the way out, as this used to
   do, left every OTHER pane on the server — a plain shell, `less`, the `/log`
   tail — with no wheel at all until the user re-sourced their tmux.conf.

   Failures are tolerated — a missing binding beats a crashed renderer."
  [tmux]
  (let [prior-wheel (capture-wheel-bindings tmux)
        prior       (mouse-setting tmux)]
    (try
      (tmux-iface/set-option! tmux {:name "mouse" :value "on" :scope :global})
      (catch Throwable _))
    (try
      (tmux-iface/run-shell tmux
                            {:args ["bind-key" "-T" "root" "WheelUpPane"
                                    "if-shell" "-F" "-t" "="
                                    "#{||:#{mouse_any_flag},#{pane_in_mode}}"
                                    "send-keys -M"
                                    "if-shell -F -t = '#{alternate_on}' 'send-keys -t = Up' 'copy-mode -et='"]})
      (catch Throwable _))
    (try
      (tmux-iface/run-shell tmux
                            {:args ["bind-key" "-T" "root" "WheelDownPane"
                                    "if-shell" "-F" "-t" "="
                                    "#{||:#{mouse_any_flag},#{pane_in_mode}}"
                                    "send-keys -M"
                                    "if-shell -F -t = '#{alternate_on}' 'send-keys -t = Down' 'send-keys -M'"]})
      (catch Throwable _))
    {:mouse prior :wheel prior-wheel}))

(defn- uninstall-wheel-bindings!
  "Put the wheel bindings back the way we found them and restore the saved
   `mouse` setting."
  [tmux {:keys [mouse wheel]}]
  (restore-wheel-bindings! tmux wheel)
  (when mouse
    (try
      (tmux-iface/set-option! tmux {:name "mouse" :value mouse :scope :global})
      (catch Throwable _))))

;; ----------------------------------------------------------------------------
;; Install / uninstall
;; ----------------------------------------------------------------------------

(defn install!
  "Wire the side channel to a Tmux implementation. `opts`:
     :tmux        — the impl (defaults to `(tmux-iface/real-tmux)`)
     :session-dir — `<project>/.brainyard/sessions/<id>/` for FIFO and scrollback files
   Returns the resolved state map. Idempotent: re-installing replaces state.

   Side effects: enables `mouse on` and installs WheelUp/Down -> Up/Down
   bindings for alt-screen panes that did not ask for the mouse. Both the
   previous `mouse` value and the previous wheel bindings are captured, in
   `:prior-mouse` and `:prior-wheel`, so `uninstall!` can restore them."
  ([] (install! {}))
  ([{:keys [tmux session-dir]}]
   (let [t     (or tmux (tmux-iface/real-tmux))
         pid   (current-pane-id t)
         prior (install-wheel-bindings! t)]
     (reset! !state {:tmux        t
                     :host-pane   pid
                     :activity    nil
                     :log         nil
                     :session-dir session-dir
                     :prior-mouse (:mouse prior)
                     :prior-wheel (:wheel prior)})
     @!state)))

(defn retarget!
  "Point the side channel's file outputs (FIFOs, `/scrollback dump`) at a
   different `<project>/.brainyard/sessions/<id>/`, keeping any open panes and
   their writers. Used when `/clear` rotates the live session onto a new id.

   Deliberately not `install!`: that resets `:activity`/`:log` to nil, which
   would ORPHAN an open side pane — the pane keeps running with nothing left
   holding its id to kill it. Returns the new session dir, or nil when the side
   channel is not installed (Mode A)."
  [session-dir]
  (when (installed?)
    (:session-dir (swap! !state assoc :session-dir session-dir))))

(defn- close-channel!
  "Tear down a single side channel (kill its pane + close its FIFO writer).
   Tolerates missing pieces — used both by `uninstall!` and the `hide` slash
   commands."
  [tmux ch]
  (when ch
    (when-let [^Writer w (:writer ch)]
      (try (.close w) (catch Throwable _)))
    (when-let [pane (:pane-id ch)]
      (try (tmux-iface/kill-pane! tmux pane) (catch Throwable _)))))

(defn uninstall!
  "Tear down side panes, put the wheel bindings and the `mouse` setting back
   the way we found them, and forget the Tmux impl. Safe to call when not
   installed."
  []
  (let [{:keys [tmux activity log prior-mouse prior-wheel]} @!state]
    (when tmux
      (close-channel! tmux activity)
      (close-channel! tmux log)
      (uninstall-wheel-bindings! tmux {:mouse prior-mouse :wheel prior-wheel}))
    (reset! !state {:tmux        nil
                    :host-pane   nil
                    :activity    nil
                    :log         nil
                    :session-dir nil
                    :prior-mouse nil
                    :prior-wheel nil})
    :ok))

;; ----------------------------------------------------------------------------
;; Side panes
;; ----------------------------------------------------------------------------

(defn- ^String fifo-path
  "Compute a per-channel FIFO path under the session dir (when set) or /tmp."
  [channel]
  (let [base (or (:session-dir @!state)
                 (str (System/getProperty "java.io.tmpdir") "/by-" (System/currentTimeMillis)))
        ;; ensure parent exists
        f (java.io.File. ^String base)]
    (when-not (.exists f) (.mkdirs f))
    (str base "/" (name channel) ".fifo")))

(def ^:private orientation-map
  {:right {:orientation :h :before? false}
   :left  {:orientation :h :before? true}
   :bottom {:orientation :v :before? false}
   :top   {:orientation :v :before? true}})

(defn- app-log-path
  "Mulog file-publisher path. The /log side pane tails this directly.
   Delegates to tui-log/default-log-path so it tracks the runtime layout
   (`~/.brainyard/logs/agent-tui-app.log` by default; `/tmp/` fallback)."
  []
  (tui-log/default-log-path))

(defn- open-fifo-writer
  "Open a writer on `path` (must be an existing FIFO). Uses RandomAccessFile
   in 'rw' mode so the open does NOT block when no reader has connected yet
   (POSIX FIFO semantics: O_RDWR pairs both endpoints in the kernel). Returns
   a `java.io.Writer`, or nil on failure."
  ^java.io.Writer [^String path]
  (try
    (let [raf (java.io.RandomAccessFile. path "rw")
          fd  (.getFD raf)
          out (java.io.FileOutputStream. fd)]
      (java.io.BufferedWriter.
       (java.io.OutputStreamWriter. out "UTF-8")))
    (catch Throwable _ nil)))

(defn open-pane!
  "Split a side pane and route a content stream to it. Idempotent: closes any
   existing channel first.

     channel    :activity | :log
     direction  :right | :left | :bottom | :top (default :right)
     percentage int 1..99 (default 30)

   The :activity channel uses a per-session FIFO (`activity.fifo`) that
   producers write to via `append-activity!`. The :log channel tails the
   mulog file (`/tmp/agent-tui-app.log`) directly — no FIFO, no producer."
  [channel {:keys [direction percentage] :or {direction :right percentage 30}}]
  (when-not (installed?)
    (throw (ex-info "tmux side channel not installed (Mode A?)" {:channel channel})))
  (let [{:keys [tmux host-pane] :as st} @!state
        existing (get st channel)]
    (when existing (close-channel! tmux existing))
    (let [{:keys [orientation]} (orientation-map direction)
          [reader-cmd fifo-path]
          (case channel
            :activity (let [p (fifo-path channel)]
                        (tmux-iface/ensure-fifo! p)
                        [(str "cat " p) p])
            :log      [(str "tail -F " (app-log-path)) nil])
          new-pane (tmux-iface/split-pane! tmux
                                           {:target host-pane
                                            :orientation orientation
                                            :percentage percentage
                                            :command reader-cmd})
          writer   (when fifo-path (open-fifo-writer fifo-path))
          ch {:pane-id   new-pane
              :fifo-path fifo-path
              :writer    writer}]
      (swap! !state assoc channel ch)
      ch)))

(defn append-activity!
  "Append `s` (with a trailing CRLF) to the activity FIFO. No-op when the
   activity pane isn't open. Failures are swallowed — the renderer must
   never crash because the side pane went away."
  [^String s]
  (when-let [^java.io.Writer w (get-in @!state [:activity :writer])]
    (try
      (.write w s)
      (.write w "\r\n")
      (.flush w)
      (catch Throwable _ nil))))

(defn close-pane!
  "Close a side channel's pane and forget it. Returns true when something was
   torn down, false when not previously open."
  [channel]
  (when-let [ch (get @!state channel)]
    (close-channel! (:tmux @!state) ch)
    (swap! !state assoc channel nil)
    true))

(defn capture-host-pane!
  "Capture the host pane's scrollback to `out-file`. Returns the path on
   success or nil on failure."
  [out-file]
  (when (installed?)
    (try
      (let [{:keys [tmux host-pane]} @!state
            ansi (tmux-iface/capture-pane tmux {:target host-pane
                                                :start "-" :ansi? true})]
        (when ansi
          (let [f (java.io.File. ^String out-file)]
            (.mkdirs (.getParentFile f))
            (spit f ansi))
          out-file))
      (catch Throwable _ nil))))
