;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.tui.terminal-caps
  "Terminal capability negotiation — currently just DEC private mode 2027,
   grapheme clustering.

   The problem it solves: a terminal and the app each carry their own idea of
   how wide a grapheme is, and when they disagree every right-hand edge in the
   UI drifts. The disagreement is not hypothetical and it is not small — a
   four-person ZWJ family emoji is 8 columns under per-codepoint `wcwidth` and
   2 columns under grapheme clustering. Mode 2027 lets us ASK which regime the
   terminal is in instead of guessing.

   Three things about this negotiation are load-bearing:

   - **Silence is the common case, and it costs.** A terminal that has never
     heard of DECRQM sends nothing back, so the read must be time-bounded —
     and that timeout is then paid in full. Measured on the native binary: a
     single unanswered probe adds ~500ms, roughly 4x the binary's entire
     startup. That is why the result is CACHED and why tmux is skipped.

   - **tmux can never say yes.** Measured against tmux 3.6a: it does not answer
     DECRQM for 2027 (it answers 2004, so the query itself is fine), and tmux
     computes widths with `wcwidth` internally. Inside tmux the legacy regime
     is both the only reachable answer and the correct one, so we skip the
     probe entirely rather than burn the timeout to learn it.

   - **Fail-safe means OFF.** No tty, no reply, a garbled reply, an exception,
     a probe we never ran — all resolve to clustering disabled, which is
     byte-identical to the behavior before this namespace existed. The feature
     can only ever turn ON by an affirmative answer from the terminal.

   The cache lives at `<user-config-dir>/terminal-caps.edn`, keyed by terminal
   identity rather than by nothing: the same machine runs iTerm2 and Ghostty
   and ssh'd sessions, and they do not share an answer."
  (:require [ai.brainyard.agent.core.config :as config]
            [ai.brainyard.mulog.interface :as mulog]
            [clojure.edn :as edn]
            [clojure.string :as str])
  (:import [java.io File]))

;; ============================================================================
;; State
;; ============================================================================

;; Resolved answer to "does the attached terminal group graphemes into one
;; cell?". Read on every display-width call, so it is a plain atom deref.
;; Starts false: until something proves otherwise we behave exactly as before.
(defonce ^:private !grapheme-clustering? (atom false))

;; Whether negotiation has run at all this process. Kept separate from the
;; answer so `status` can distinguish "probed, answer was no" from "never
;; probed" — they are the same rendering behavior but very different bugs.
(defonce ^:private !negotiated (atom nil))

(defn grapheme-clustering?
  "True when the attached terminal was confirmed to group grapheme clusters
   into a single cell. False until proven otherwise, including when the probe
   never ran."
  []
  @!grapheme-clustering?)

(defn status
  "Diagnostic snapshot for `/config` and tests."
  []
  {:grapheme-clustering? @!grapheme-clustering?
   :negotiated           @!negotiated})

(defn set-grapheme-clustering!
  "Force the flag. For tests and for the `:on` / `:off` config settings, which
   deliberately bypass negotiation."
  [v source]
  (reset! !grapheme-clustering? (boolean v))
  (reset! !negotiated {:source source})
  (boolean v))

(defn reset-negotiation!
  "Forget the resolved state (tests)."
  []
  (reset! !grapheme-clustering? false)
  (reset! !negotiated nil))

;; ============================================================================
;; Terminal identity + cache
;; ============================================================================

(defn env-var
  "Read an environment variable.

   A var rather than a direct `System/getenv` call because `getenv` is a
   static method and cannot be redefined — routing through here is what makes
   the tmux-skip and cache-key policy testable without mutating the real
   process environment."
  [k]
  (System/getenv k))

(defn terminal-key
  "Identity of the terminal we're talking to, used as the cache key.

   TERM alone is far too coarse — every modern emulator claims
   `xterm-256color` — so the program name and version join it. TMUX is part of
   the identity because tmux is itself the terminal when present, and its
   answer differs from the emulator hosting it."
  []
  (let [g #(or (not-empty (env-var %)) "")]
    (str/join "|" [(g "TERM")
                   (g "TERM_PROGRAM")
                   (g "TERM_PROGRAM_VERSION")
                   (if (not-empty (g "TMUX")) "tmux" "")])))

(defn- cache-file ^File []
  (when-let [ucd (config/user-config-dir (config/resolve-dirs))]
    (File. ^String ucd "terminal-caps.edn")))

(defn read-cache
  "Cached capability map, or {} when absent/unreadable. A corrupt cache is a
   cache miss, never an error — it is a performance record, not state we own."
  []
  (try
    (let [f (cache-file)]
      (if (and f (.isFile f))
        (or (edn/read-string (slurp f)) {})
        {}))
    (catch Exception _ {})))

(defn- write-cache!
  "Merge one terminal's answer into the cache. Best-effort: a read-only home
   directory must not break the TUI, it just means we re-probe next time."
  [tkey entry]
  (try
    (when-let [f (cache-file)]
      (.mkdirs (.getParentFile f))
      (spit f (pr-str (assoc (read-cache) tkey entry))))
    (catch Exception _ nil)))

;; ============================================================================
;; The probe
;; ============================================================================

(defn- sh-tty
  "Run `cmd` with /dev/tty on stdin. Returns stdout, or nil when there is no
   controlling terminal (the pipe / CI / `by ask > file` case)."
  [cmd]
  (try
    (let [pb   (doto (ProcessBuilder. ["/bin/sh" "-c" (str cmd " < /dev/tty 2>/dev/null")])
                 (.redirectErrorStream true))
          proc (.start pb)
          out  (slurp (.getInputStream proc))]
      (when (zero? (.waitFor proc)) out))
    (catch Exception _ nil)))

(def ^:private decrqm-2027
  "DECRQM for private mode 2027. Reply is `CSI ? 2027 ; Ps $ y`."
  (str (char 27) "[?2027$p"))

(defn- read-reply
  "Write the DECRQM query to /dev/tty and read whatever comes back.

   The bound is the terminal driver's, not ours: `stty min 0 time 5` makes the
   read return after at most 0.5s with nothing, which is the case we must
   survive. Doing this with a thread + interrupt instead would leave a blocked
   read on a shared fd."
  []
  (try
    (with-open [os (java.io.FileOutputStream. "/dev/tty")
                is (java.io.FileInputStream. "/dev/tty")]
      (.write os (.getBytes ^String decrqm-2027 "US-ASCII"))
      (.flush os)
      (let [buf (byte-array 64)
            n   (.read is buf)]
        (if (pos? n) (String. buf 0 (int n) "US-ASCII") "")))
    (catch Exception _ "")))

(defn parse-decrqm-reply
  "Interpret a DECRQM reply for mode 2027.

   Ps: 0 = not recognized, 1 = set, 2 = reset, 3 = permanently set,
       4 = permanently reset. Only 1 and 3 mean clustering is active.

   Anything unparseable is `nil` (unknown), never a guess — a terminal that
   answered something we don't understand has told us nothing."
  [reply]
  (when (seq reply)
    (when-let [[_ ps] (re-find #"\[\?2027;(\d+)\$y" (str reply))]
      (let [ps (parse-long ps)]
        {:ps ps :clustering? (contains? #{1 3} ps)}))))

(defn probe!
  "Run the DECRQM exchange against the attached terminal. Returns
   {:clustering? bool :ps int|nil :reason kw}. Never throws."
  []
  (if-let [saved (sh-tty "stty -g")]
    (do
      (sh-tty "stty raw -echo min 0 time 5")
      (try
        (let [reply  (read-reply)
              parsed (parse-decrqm-reply reply)]
          (cond
            (nil? parsed) {:clustering? false :ps nil :reason :no-reply}
            :else         (assoc parsed :reason :answered)))
        (finally
          ;; Restoring the tty is not optional — leaving it raw would break
          ;; the shell the user returns to, not just this process.
          (sh-tty (str "stty " (str/trim saved))))))
    {:clustering? false :ps nil :reason :no-tty}))

;; ============================================================================
;; Negotiation (config-driven, cached)
;; ============================================================================

(defn negotiate!
  "Resolve grapheme-width handling and install it. Idempotent per process.

   `:grapheme-width` decides:
     :off  (default) — never probe, clustering disabled
     :on             — force clustering on, no probe (for a terminal you know)
     :auto           — probe once per terminal identity, cache the answer

   `tty?` is the caller's own answer to \"is stdout a real terminal\" — a
   daemon / piped run must not emit escape sequences at all.

   Returns the status map."
  ([] (negotiate! true))
  ([tty?]
   (let [mode (or (config/get-config :grapheme-width) :off)]
     (cond
       (= mode :on)
       (do (set-grapheme-clustering! true :config)
           (mulog/log ::grapheme-width :mode :on :clustering? true)
           (status))

       (or (= mode :off) (not tty?))
       (do (set-grapheme-clustering! false (if tty? :config :no-tty))
           (status))

       ;; :auto below.
       ;; tmux is skipped rather than probed: measured against tmux 3.6a it
       ;; never answers, so probing only buys a ~500ms startup stall on the
       ;; way to the answer we already know.
       (not-empty (or (env-var "TMUX") ""))
       (do (set-grapheme-clustering! false :tmux)
           (mulog/log ::grapheme-width :mode :auto :clustering? false :reason :tmux)
           (status))

       :else
       (let [tkey   (terminal-key)
             cached (get (read-cache) tkey)]
         (if (contains? cached :clustering?)
           (do (set-grapheme-clustering! (:clustering? cached) :cache)
               (mulog/log ::grapheme-width :mode :auto :reason :cache-hit
                          :clustering? (:clustering? cached))
               (status))
           (let [{:keys [clustering? ps reason]} (probe!)]
             ;; A :no-tty probe result is NOT cached — the absence of a
             ;; terminal says nothing about the terminal, and caching it
             ;; would poison every later run under this same TERM.
             (when (not= reason :no-tty)
               (write-cache! tkey {:clustering? clustering?
                                   :ps          ps
                                   :reason      reason}))
             (set-grapheme-clustering! clustering? :probe)
             (mulog/log ::grapheme-width :mode :auto :reason reason
                        :ps ps :clustering? clustering?)
             (status))))))))
