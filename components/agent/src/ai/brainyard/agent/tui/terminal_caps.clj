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

   - **tmux never answers, so tmux gets measured instead.** It does not reply
     to DECRQM for 2027 (it answers 2004, so the query itself is fine), which
     is why the probe is still skipped there. What changed is the conclusion
     drawn from that silence: this used to assume tmux counts with `wcwidth`,
     and 3.6a does not — it folds every cluster kind into one cell. Measured
     by writing into a pane and reading `#{cursor_x}`, a ZWJ family, a flag, a
     skin-toned thumb and a keycap are all 2 columns, exactly the clustered
     table. So inside tmux we ask tmux (~50ms, see `tmux-clusters?`) rather
     than assume an answer that has been wrong since at least 3.6a.

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

(defn write-cache!
  "Merge one terminal's answer into the cache. Best-effort: a read-only home
   directory must not break the TUI, it just means we re-probe next time.

   Public for the same reason `read-cache` is: a test that exercises the
   measuring path must be able to stop it writing into the real config dir."
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
    (let [;; The hint is load-bearing, not decoration: an unhinted
          ;; ProcessBuilder ctor compiles to a reflective call, and
          ;; `bb reflect:check` gates the build on exactly that.
          ;; It must be an into-array with the array-class hint, matching
          ;; `query-stty-size` above — a `^java.util.List` on a LITERAL vector
          ;; is silently ignored (the reader attaches it as the vector's own
          ;; metadata), which is how this got past a first fix attempt.
          argv (into-array String ["/bin/sh" "-c" (str cmd " < /dev/tty 2>/dev/null")])
          pb   (doto (ProcessBuilder. ^"[Ljava.lang.String;" argv)
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
;; tmux
;; ============================================================================

(defn- sh
  "Run `argv`, returning trimmed stdout, or nil on a non-zero exit or a throw.

   No /dev/tty in sight, unlike `sh-tty` above: a tmux client command talks to
   the tmux SERVER over its socket and needs no controlling terminal — which is
   also why this works from a TUI whose own tty is busy being a TUI.

   Same array-class hint as `sh-tty`, for the same reason: the unhinted
   ProcessBuilder ctor compiles to a reflective call and `bb reflect:check`
   gates the build on it."
  [& argv]
  (try
    (let [pb   (doto (ProcessBuilder. ^"[Ljava.lang.String;" (into-array String argv))
                 (.redirectErrorStream true))
          proc (.start pb)
          out  (slurp (.getInputStream proc))]
      (when (zero? (.waitFor proc)) (str/trim out)))
    (catch Exception _ nil)))

(defn tmux-version
  "`tmux -V`, or nil when there is no tmux to ask.

   Part of the cached ENTRY rather than of the cache key: an upgrade can change
   how tmux counts, and a stale yes is a UI that drifts on every emoji. Keeping
   it out of the key also keeps `terminal-key` a pure function of the
   environment, which is what makes it testable."
  []
  (sh "tmux" "-V"))

(def ^:private cluster-probe
  "MAN ZWJ BOY ZWJ BOY: 6 columns counted per codepoint, 2 clustered.

   Octal escapes for `printf` rather than the characters themselves, so the
   bytes that reach tmux do not depend on how this JVM encodes argv — a
   mangled probe would still measure something, just not this."
  "\\360\\237\\221\\250\\342\\200\\215\\360\\237\\221\\246\\342\\200\\215\\360\\237\\221\\246")

(defn tmux-clusters?
  "Does the tmux we are inside fold a grapheme cluster into one cell?

   Asked of the binary rather than of a version table. A DETACHED scratch
   session prints the sequence and tmux reports where its own cursor landed:
   2 means clustered, 6 means per-codepoint. Detached and killed immediately,
   so nothing appears in the pane the user is looking at.

   Costs a handful of tmux round trips — tens of milliseconds against the
   ~500ms an unanswered DECRQM costs, and unlike DECRQM tmux always answers.

   False on anything unexpected: a tmux that cannot be measured gets the
   fail-safe this whole namespace is built on, which is the old behaviour."
  []
  (let [session (str "by-caps-probe-" (System/currentTimeMillis))]
    (try
      (boolean
       (when (sh "tmux" "new-session" "-d" "-s" session "-x" "40" "-y" "4"
                 "sh" "-c" (str "printf '" cluster-probe "'; sleep 2"))
         ;; The pane's shell runs on its own schedule, so poll for the cursor to
         ;; move rather than sleeping a guessed interval and hoping.
         (loop [tries 0]
           (let [x (some-> (sh "tmux" "display-message" "-p" "-t" session "#{cursor_x}")
                           parse-long)]
             (cond
               (and x (pos? x)) (= 2 x)
               (< tries 30)     (do (Thread/sleep 10) (recur (inc tries)))
               :else            false)))))
      (catch Exception _ false)
      (finally
        ;; Unconditional: a probe that died halfway must not leave a session in
        ;; the user's `tmux ls` forever.
        (sh "tmux" "kill-session" "-t" session)))))

;; ============================================================================
;; Negotiation (config-driven, cached)
;; ============================================================================

(defn negotiate!
  "Resolve grapheme-width handling and install it. Idempotent per process.

   `:grapheme-width` decides:
     :auto (the configured default) — resolve once per terminal identity and
           cache: DECRQM against the emulator, a direct measurement inside tmux
     :on   — force clustering on, ask nothing (for a terminal you know)
     :off  — never ask, clustering disabled. Also the fallback here when the
           config key is unset, since off is the safe half of a wrong guess

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
       ;; Inside tmux, tmux IS the terminal: it owns the grid this TUI writes
       ;; into, and the emulator behind it only draws what tmux decided. It
       ;; still never answers DECRQM, so the probe stays skipped — but the
       ;; answer is measured rather than assumed, because the assumption (tmux
       ;; counts per codepoint) is false on 3.6a.
       ;;
       ;; Cached like any other terminal, and invalidated by a tmux upgrade:
       ;; the whole point of measuring is that this changed once already.
       (not-empty (or (env-var "TMUX") ""))
       (let [tkey      (terminal-key)
             ver       (tmux-version)
             cached    (get (read-cache) tkey)
             cache-hit (and (contains? cached :clustering?)
                            (= ver (:tmux-version cached)))
             clusters? (if cache-hit (:clustering? cached) (tmux-clusters?))]
         (when-not cache-hit
           (write-cache! tkey {:clustering?  clusters?
                               :reason       :tmux-measured
                               :tmux-version ver}))
         (set-grapheme-clustering! clusters? :tmux)
         (mulog/log ::grapheme-width :mode :auto :clustering? clusters?
                    :reason (if cache-hit :cache-hit :tmux-measured)
                    :tmux-version ver)
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
