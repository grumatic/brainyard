;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.tui.terminal-caps-test
  "Mode-2027 grapheme-width negotiation and its effect on display-width.

   Nothing here touches a real terminal: `probe!` is redefed wherever a test
   could reach it, and a test that reaches it anyway FAILS rather than hanging
   for the probe timeout. Non-ASCII is written as \\uXXXX escapes so an editor
   or a transfer that mangles UTF-8 cannot silently change what is asserted."
  (:require [clojure.test :refer [deftest testing is are use-fixtures]]
            [clojure.string :as str]
            [ai.brainyard.agent.tui.format :as fmt]
            [ai.brainyard.agent.tui.terminal-caps :as caps]
            [ai.brainyard.agent.core.config :as config]))

;; The flag is process-global (it describes the one terminal we are attached
;; to), so every test must hand it back the way it found it.
(use-fixtures :each (fn [t] (caps/reset-negotiation!) (t) (caps/reset-negotiation!)))

(defmacro with-tmux
  "Run body with TMUX set to `v` (nil = unset), leaving other env lookups alone."
  [v & body]
  `(let [real# caps/env-var]
     (with-redefs [caps/env-var (fn [n#] (if (= n# "TMUX") ~v (real# n#)))]
       ~@body)))

(def FAMILY "\uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC67\u200D\uD83D\uDC66")
(def FLAG   "\uD83C\uDDF0\uD83C\uDDF7")
(def THUMB  "\uD83D\uDC4D\uD83C\uDFFD")
(def KEYCAP "1\uFE0F\u20E3")
(def WARN   "\u26A0\uFE0F")
(def CJK    "\uAC80\uC0C9")

;; ============================================================================
;; display-width under each regime
;; ============================================================================

(deftest clustering-changes-only-joined-sequences
  (testing "clustering OFF -- widths are exactly what they were before this feature"
    (caps/set-grapheme-clustering! false :test)
    (are [expected s] (= expected (fmt/display-width s))
      5 "hello"
      4 CJK          ;; two 2-cell syllables
      2 WARN         ;; VS16 makes the base wide
      8 FAMILY       ;; four bases summed, ZWJ zero-width
      4 FLAG         ;; two regional indicators
      4 THUMB        ;; base + skin-tone modifier
      2 KEYCAP))

  (testing "clustering ON -- only the JOINED sequences change"
    (caps/set-grapheme-clustering! true :test)
    (are [expected s] (= expected (fmt/display-width s))
      5 "hello"
      4 CJK          ;; unchanged: separate clusters
      2 WARN         ;; unchanged: already one cluster
      2 FAMILY       ;; 8 -> 2
      2 FLAG         ;; 4 -> 2
      2 THUMB        ;; 4 -> 2
      2 KEYCAP)))

(deftest ansi-escapes-never-reach-the-segmenter
  (testing "styled text measures identically in both regimes. A segmenter fed raw
            escapes turns ESC, the bracket, the semicolon and the final letter
            into clusters of their own and counts every one."
    (let [styled (str \u001b "[1;32m" "OK \u2705" \u001b "[0m")]
      (caps/set-grapheme-clustering! false :test)
      (is (= 5 (fmt/display-width styled)))
      (caps/set-grapheme-clustering! true :test)
      (is (= 5 (fmt/display-width styled))))))

(deftest clustering-is-a-no-op-below-u0300
  (testing "nothing under U+0300 can join or modify a neighbour, so the regimes
            must agree byte-for-byte on ordinary text -- this is the fast path"
    (doseq [s ["plain ascii" "tabs\tand\nnewlines" "punct !@#$ ^&*()" ""]]
      (caps/set-grapheme-clustering! false :test)
      (let [off (fmt/display-width s)]
        (caps/set-grapheme-clustering! true :test)
        (is (= off (fmt/display-width s)) (str "regimes disagree on " (pr-str s)))))))

(deftest wrapping-cuts-on-cluster-boundaries
  (testing "a wrap must not slice a ZWJ sequence -- half a joined emoji renders as
            two unrelated glyphs, WIDENING the line the cut was narrowing"
    (caps/set-grapheme-clustering! true :test)
    (let [line (str "ab " FAMILY " cd")]
      (doseq [limit (range 2 12)]
        (doseq [row (#'fmt/ansi-aware-word-wrap line limit)]
          (is (not (str/starts-with? row "\u200D"))
              (str "row begins with a bare ZWJ at limit " limit
                   " -- the cut landed inside a cluster")))))))

;; ============================================================================
;; DECRQM reply parsing
;; ============================================================================

(deftest parse-decrqm-reply-only-trusts-set-values
  (testing "Ps 1 (set) and 3 (permanently set) mean clustering is active"
    (are [ps clustering?]
         (= clustering? (:clustering? (caps/parse-decrqm-reply
                                       (str \u001b "[?2027;" ps "$y"))))
      1 true
      3 true
      2 false      ;; reset: supported but off
      4 false      ;; permanently reset
      0 false))    ;; mode not recognized

  (testing "an unparseable reply tells us NOTHING and must not become a guess"
    (are [reply] (nil? (caps/parse-decrqm-reply reply))
      ""
      nil
      "garbage"
      (str \u001b "[?2004;2$y")      ;; a DIFFERENT mode's reply
      (str \u001b "[?2027$y"))))     ;; malformed: no Ps

;; ============================================================================
;; Negotiation policy
;; ============================================================================

(deftest negotiation-is-fail-safe
  (testing ":off never probes and leaves clustering disabled"
    (with-redefs [config/get-config (constantly :off)
                  caps/probe! (fn [] (throw (AssertionError. "probed while :off")))]
      (is (false? (:grapheme-clustering? (caps/negotiate! true))))))

  (testing "no tty resolves to OFF even under :auto -- a piped run must never
            emit a DECRQM query into the pipe"
    (with-redefs [config/get-config (constantly :auto)
                  caps/probe! (fn [] (throw (AssertionError. "probed without a tty")))]
      (is (false? (:grapheme-clustering? (caps/negotiate! false))))))

  (testing ":on forces clustering on without probing"
    (with-redefs [config/get-config (constantly :on)
                  caps/probe! (fn [] (throw (AssertionError. "probed while :on")))]
      (is (true? (:grapheme-clustering? (caps/negotiate! true)))))))

(deftest auto-measures-tmux-rather-than-probing-it
  (testing "tmux never answers DECRQM for 2027 (measured against 3.6a), so the
            probe stays skipped — but the answer comes from measuring tmux, not
            from assuming it counts per codepoint, which 3.6a does not"
    (with-redefs [config/get-config   (constantly :auto)
                  caps/probe!         (fn [] (throw (AssertionError. "probed inside tmux")))
                  caps/read-cache     (constantly {})
                  caps/write-cache!   (fn [_ _] nil)
                  caps/tmux-version   (constantly "tmux 3.6a")
                  caps/tmux-clusters? (constantly true)]
      (with-tmux "/tmp/tmux-1000/default,4242,0"
        (let [st (caps/negotiate! true)]
          (is (true? (:grapheme-clustering? st)))
          (is (= :tmux (:source (:negotiated st)))))))))

(deftest a-tmux-that-does-not-cluster-still-resolves-to-off
  (testing "the measurement decides, both ways — an older tmux that counts per
            codepoint must keep the legacy regime, which is what makes this
            safe to run against a tmux nobody here has"
    (with-redefs [config/get-config   (constantly :auto)
                  caps/probe!         (fn [] (throw (AssertionError. "probed inside tmux")))
                  caps/read-cache     (constantly {})
                  caps/write-cache!   (fn [_ _] nil)
                  caps/tmux-version   (constantly "tmux 3.0a")
                  caps/tmux-clusters? (constantly false)]
      (with-tmux "/tmp/tmux-1000/default,4242,0"
        (is (false? (:grapheme-clustering? (caps/negotiate! true))))))))

(deftest a-tmux-upgrade-invalidates-the-cached-answer
  (testing "the cache entry carries the tmux version, because an upgrade is
            exactly the event that changes the answer — a stale yes is a UI
            that drifts on every emoji"
    (let [measured (atom 0)]
      (with-redefs [config/get-config   (constantly :auto)
                    caps/probe!         (fn [] (throw (AssertionError. "probed inside tmux")))
                    caps/read-cache     (fn [] {(caps/terminal-key)
                                                {:clustering? false :tmux-version "tmux 3.0a"}})
                    caps/write-cache!   (fn [_ _] nil)
                    caps/tmux-version   (constantly "tmux 3.6a")
                    caps/tmux-clusters? (fn [] (swap! measured inc) true)]
        (with-tmux "/tmp/tmux-1000/default,4242,0"
          (is (true? (:grapheme-clustering? (caps/negotiate! true)))
              "the stale entry must not win")
          (is (= 1 @measured) "and the new tmux must actually be measured"))))))

(deftest a-matching-tmux-version-is-a-cache-hit
  (testing "same tmux, same answer, no scratch session — the measurement is
            cheap but it is not free, and it runs before the first render"
    (with-redefs [config/get-config   (constantly :auto)
                  caps/probe!         (fn [] (throw (AssertionError. "probed inside tmux")))
                  caps/read-cache     (fn [] {(caps/terminal-key)
                                              {:clustering? true :tmux-version "tmux 3.6a"}})
                  caps/tmux-version   (constantly "tmux 3.6a")
                  caps/tmux-clusters? (fn [] (throw (AssertionError. "measured on a cache hit")))]
      (with-tmux "/tmp/tmux-1000/default,4242,0"
        (is (true? (:grapheme-clustering? (caps/negotiate! true))))))))

(deftest auto-prefers-the-cache-over-reprobing
  (testing "a cached answer short-circuits the probe -- the cache exists so the
            ~500ms timeout is paid at most once per terminal identity"
    (with-redefs [config/get-config (constantly :auto)
                  caps/read-cache  (fn [] {(caps/terminal-key) {:clustering? true}})
                  caps/probe!      (fn [] (throw (AssertionError. "probed on a cache hit")))]
      (with-tmux nil
        (let [st (caps/negotiate! true)]
          (is (true? (:grapheme-clustering? st)))
          (is (= :cache (:source (:negotiated st)))))))))

(deftest terminal-key-separates-terminals-that-disagree
  (testing "TERM alone is useless as a key -- every modern emulator claims
            xterm-256color, and they do not share an answer"
    (let [k (fn [env] (with-redefs [caps/env-var (fn [n] (get env n))]
                        (caps/terminal-key)))]
      (is (not= (k {"TERM" "xterm-256color" "TERM_PROGRAM" "iTerm.app"})
                (k {"TERM" "xterm-256color" "TERM_PROGRAM" "ghostty"}))
          "same TERM, different emulator must not share a cache entry")
      (is (not= (k {"TERM" "xterm-256color" "TERM_PROGRAM" "ghostty"})
                (k {"TERM" "xterm-256color" "TERM_PROGRAM" "ghostty"
                    "TMUX" "/tmp/tmux-1000/default,1,0"}))
          "tmux is its own terminal and answers differently"))))

;; ============================================================================
;; next-unit -- the stepping counterpart to display-width
;; ============================================================================

(defn- walk
  "Step the whole string with next-unit, returning [unit-strings total-width].
   This is the shape every caller that walks a string uses."
  [s]
  (loop [i 0, units [], w 0]
    (if (>= i (count s))
      [units w]
      (let [[cw nxt] (fmt/next-unit s i)]
        (recur nxt (conj units (subs s i nxt)) (+ w cw))))))

(deftest next-unit-steps-in-the-same-regime-display-width-measures
  (testing "walking with next-unit must total exactly what display-width says --
            if they disagree, a wrap enforces a width its own measurement denies"
    (doseq [regime [false true]]
      (caps/set-grapheme-clustering! regime :test)
      (doseq [s ["hello" CJK WARN FAMILY FLAG THUMB KEYCAP
                 (str "ab " FAMILY " cd") (str CJK " x " FLAG)]]
        (is (= (fmt/display-width s) (second (walk s)))
            (str "walk total != display-width, clustering=" regime
                 ", s=" (pr-str s)))))))

(deftest next-unit-never-splits-a-cluster-when-clustering
  (testing "a joined sequence is ONE step, so no caller can stop inside it"
    (caps/set-grapheme-clustering! true :test)
    (are [s] (= 1 (count (first (walk s))))
      FAMILY FLAG THUMB KEYCAP WARN))

  (testing "without clustering the same sequences step per codepoint -- this is
            the behavior every caller had before, preserved exactly"
    (caps/set-grapheme-clustering! false :test)
    (is (= 7 (count (first (walk FAMILY)))))   ;; 4 bases + 3 ZWJ
    (is (= 2 (count (first (walk FLAG)))))))   ;; 2 regional indicators

(deftest next-unit-never-splits-a-surrogate-pair
  (testing "in EITHER regime -- half a surrogate pair is not a character"
    (doseq [regime [false true]]
      (caps/set-grapheme-clustering! regime :test)
      (doseq [unit (first (walk (str "a" FAMILY "b" FLAG)))]
        (is (not (Character/isHighSurrogate (.charAt ^String unit (dec (count unit)))))
            (str "unit ends on a high surrogate: " (pr-str unit)))
        (is (not (Character/isLowSurrogate (.charAt ^String unit 0)))
            (str "unit starts on a low surrogate: " (pr-str unit)))))))

(deftest next-unit-terminates-past-the-end
  (testing "callers loop on (>= i len); a bad end-index would spin forever"
    (doseq [regime [false true]]
      (caps/set-grapheme-clustering! regime :test)
      (is (= [0 5] (fmt/next-unit "hello" 5)))
      (is (= [0 5] (fmt/next-unit "hello" 99))))))

(deftest next-unit-always-advances
  (testing "every step must move the index forward, in both regimes, or the
            loops built on it hang"
    (doseq [regime [false true]]
      (caps/set-grapheme-clustering! regime :test)
      (doseq [s ["hello" CJK FAMILY FLAG (str "x" WARN "y")]]
        (loop [i 0, guard 0]
          (when (and (< i (count s)) (< guard 1000))
            (let [[_ nxt] (fmt/next-unit s i)]
              (is (> nxt i) (str "next-unit did not advance at " i " in " (pr-str s)))
              (recur nxt (inc guard)))))))))

(deftest bi-reattach-is-correct-across-interleaved-strings
  (testing "the iterator is cached per thread against the last string it saw.
            Interleaving two strings must not leak one's boundaries into the
            other -- the identity check is what prevents it."
    (caps/set-grapheme-clustering! true :test)
    (let [a (str "ab " FAMILY)
          b (str FLAG " cd")]
      (dotimes [_ 3]
        (is (= 1 (count (first (walk FAMILY)))))
        (is (= (fmt/display-width a) (second (walk a))))
        (is (= (fmt/display-width b) (second (walk b))))))))
