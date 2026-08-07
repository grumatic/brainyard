;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent-tui.clipboard
  "Put text on the user's clipboard from inside the TUI.

   Why this exists: the terminal owns mouse selection, and it selects screen
   CELLS — so a drag over rendered output picks up box chrome, gutter rails,
   and the hard line breaks our own word-wrap inserted.  An explicit copy path
   sidesteps all three by copying the SOURCE string, never the rendering.

   Two families of mechanism, and which one is correct depends on where the
   user's clipboard actually is:

     OSC 52   — the terminal emulator does the write, so the text reaches the
                machine the USER sits at.  The only thing that works through
                ssh, and the only thing that works in the `--web` (ttyd /
                xterm.js) path where the \"terminal\" is a browser tab.
                Unverifiable (no reply, no error) and not universally
                supported: Terminal.app, GNOME Terminal and Konsole ignore it.

     native   — `pbcopy` / `wl-copy` / `xclip` / `clip.exe`.  Always works,
                always verifiable — but copies to the host the PROCESS runs
                on, which over ssh is the wrong machine entirely.

   `copy!` picks per environment and reports which one ran, so the caller can
   tell the user \"copied\" when that is true and \"sent\" when it is a hope."
  (:require [ai.brainyard.agent-tui.layout :as layout]
            [ai.brainyard.agent.interface.tui.ansi :as ansi]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.io File]
           [java.util.concurrent TimeUnit]))

;; xterm caps a control string at 74994 bytes, and that budget covers the
;; BASE64 payload — which is 4/3 of the source.  Terminals that exceed the cap
;; do not error, they TRUNCATE, silently handing the user a partial answer that
;; looks complete.  So we refuse past the limit rather than corrupt quietly.
(def ^:const osc52-max-base64-bytes 74994)

;; Base64 emits one 4-char group per 3 source bytes and PADS the last group,
;; so the encoded size is `ceil(n/3)*4` — not `n*4/3`. Deriving the source cap
;; by the ratio overshoots by up to a group and lets a payload through that
;; encodes 2 bytes past the limit. Floor to whole groups instead.
(def ^:const osc52-max-source-bytes
  (* 3 (quot osc52-max-base64-bytes 4)))

;; ============================================================================
;; Environment probes
;; ============================================================================

(defn- env [k] (System/getenv k))

(defn- remote?
  "True when this process runs on a different machine from the user's
   terminal.  Native clipboard tools are worse than useless here — they'd
   succeed against the remote host's clipboard and report success."
  []
  (boolean (or (env "SSH_TTY") (env "SSH_CONNECTION") (env "SSH_CLIENT"))))

(defn- tmux? [] (boolean (env "TMUX")))

(defn- on-path?
  "Resolve `cmd` against PATH without shelling out."
  [^String cmd]
  (boolean
   (when-let [path (env "PATH")]
     (some (fn [dir]
             (let [f (File. (str dir File/separator cmd))]
               (and (.isFile f) (.canExecute f))))
           (str/split path (re-pattern (java.util.regex.Pattern/quote File/pathSeparator)))))))

(defn- native-copy-argv
  "The platform's clipboard-writing command, or nil when none is installed.
   Ordered so the display server actually in use wins: a Wayland session with
   XWayland present has both `wl-copy` and `xclip`, and only `wl-copy` targets
   the compositor the user is looking at."
  []
  (let [os (str/lower-case (or (System/getProperty "os.name") ""))]
    (cond
      (str/includes? os "mac")
      (when (on-path? "pbcopy") ["pbcopy"])

      (str/includes? os "win")
      (when (on-path? "clip.exe") ["clip.exe"])

      :else
      (cond
        ;; WSL: a Linux os.name, but the clipboard lives on the Windows side.
        (and (env "WSL_DISTRO_NAME") (on-path? "clip.exe")) ["clip.exe"]
        (and (env "WAYLAND_DISPLAY") (on-path? "wl-copy"))  ["wl-copy"]
        (and (env "DISPLAY") (on-path? "xclip"))            ["xclip" "-selection" "clipboard"]
        (and (env "DISPLAY") (on-path? "xsel"))             ["xsel" "--input" "--clipboard"]
        :else nil))))

;; ============================================================================
;; Mechanisms
;; ============================================================================

(defn- pipe-to-process!
  "Run `argv`, write `text` to its stdin, wait briefly.  Returns true on a
   clean exit.  Bounded wait: a clipboard helper that hangs (a stale X11
   connection is the classic) must not wedge the TUI's input loop."
  [argv ^String text]
  (try
    (let [pb   (doto (ProcessBuilder. ^java.util.List (vec argv))
                 (.redirectErrorStream true))
          proc (.start pb)]
      (with-open [w (io/writer (.getOutputStream proc) :encoding "UTF-8")]
        (.write w text))
      (if (.waitFor proc 3 TimeUnit/SECONDS)
        (zero? (.exitValue proc))
        (do (.destroyForcibly proc) false)))
    (catch Exception _ false)))

(defn- write-osc52!
  "Emit the OSC 52 sequence on the TUI's own terminal writer.

   Goes through `draw-overlay!` because that is the one path holding
   layout-lock: an escape sequence interleaved with a concurrent render would
   land in the middle of another sequence and be swallowed (or worse, print).
   `*out*` is deliberately not used — it is rebound to a StringWriter during
   sandboxed code eval, which would silently capture the copy instead of
   sending it."
  [^String text]
  (try
    (let [seq-str (ansi/osc52-copy text)]
      (when (seq seq-str)
        (layout/draw-overlay! (fn [w] (layout/raw-write-unsafe! w seq-str)))
        true))
    (catch Exception _ false)))

(defn- tmux-load-buffer!
  "`tmux load-buffer -w -` fills the tmux paste buffer AND (via `-w`) has tmux
   itself forward an OSC 52 outward to the real terminal.  Letting tmux own
   that hop is more reliable than emitting OSC 52 into it and hoping: tmux
   only relays ours when `set-clipboard` is `on`/`external`, and users turn
   it off.  `-w` is tmux >= 3.2; older tmux fails the flag, so fall back to a
   plain buffer load (in-tmux paste still works, outer clipboard does not)."
  [^String text]
  (or (pipe-to-process! ["tmux" "load-buffer" "-w" "-"] text)
      (pipe-to-process! ["tmux" "load-buffer" "-"] text)))

;; ============================================================================
;; Public API
;; ============================================================================

(defn copy!
  "Copy `text` to the user's clipboard.  Returns

     {:ok? bool :via kw :bytes n :verified? bool :detail str?}

   `:via` is the mechanism that ran — `:tmux`, `:osc52`, or the native command
   keyword (`:pbcopy` …).  `:verified? false` means the mechanism cannot
   confirm delivery (OSC 52 always; the caller should say \"sent\", not
   \"copied\").  `:ok? false` carries a `:detail` explaining why.

   Order of preference is about WHERE the clipboard is, not what's fastest:

     1. tmux      — inside tmux, tmux intercepts the terminal anyway; going
                    through it sets both the tmux buffer and the outer clipboard.
     2. remote    — over ssh only OSC 52 crosses the wire back to the user.
     3. native    — local and verifiable, so preferred over a blind OSC 52.
     4. OSC 52    — last resort: unverifiable, but often right (it is what
                    covers `--web`, where no native tool exists in the container)."
  [text]
  (let [text (str text)
        ;; UTF-8 bytes, not characters: the OSC 52 cap is a byte budget, and
        ;; the answers most likely to approach it are the ones full of CJK and
        ;; emoji, where a character costs three or four.
        n    (alength (.getBytes text "UTF-8"))]
    (cond
      (str/blank? text)
      {:ok? false :via nil :bytes 0 :verified? false
       :detail "nothing to copy"}

      (> n osc52-max-source-bytes)
      ;; Only OSC 52 has this ceiling, but refusing uniformly keeps the
      ;; behavior predictable instead of "works on your machine, truncates
      ;; on mine".
      {:ok? false :via nil :bytes n :verified? false
       :detail (str "too large for OSC 52 (" n " bytes > "
                    osc52-max-source-bytes " limit)")}

      :else
      (let [argv  (native-copy-argv)
            done  (fn [via verified?]
                    {:ok? true :via via :bytes n :verified? verified?})]
        (cond
          (and (tmux?) (tmux-load-buffer! text))
          (done :tmux true)

          (remote?)
          (if (write-osc52! text)
            (done :osc52 false)
            {:ok? false :via :osc52 :bytes n :verified? false
             :detail "could not write to the terminal"})

          (and argv (pipe-to-process! argv text))
          (done (keyword (first argv)) true)

          (write-osc52! text)
          (done :osc52 false)

          :else
          {:ok? false :via nil :bytes n :verified? false
           :detail "no clipboard mechanism available"})))))

(defn describe
  "One-line human summary of a `copy!` result, for echoing back to the user.
   Distinguishes verified copies from unverifiable OSC 52 sends — telling a
   user on Terminal.app that text was 'copied' when the escape was dropped on
   the floor is worse than telling them nothing."
  [{:keys [ok? via bytes verified? detail]}]
  (if-not ok?
    (str "Copy failed: " (or detail "unknown error"))
    (let [size (str bytes " byte" (when (not= 1 bytes) "s"))]
      (if verified?
        (str "Copied " size " to the clipboard (" (name via) ")")
        (str "Sent " size " via OSC 52 — lands on the clipboard if your "
             "terminal supports it (Terminal.app / GNOME Terminal / Konsole do not)")))))
