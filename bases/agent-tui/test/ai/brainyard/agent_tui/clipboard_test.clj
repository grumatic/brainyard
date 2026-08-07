;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent-tui.clipboard-test
  "Tests for the OSC 52 clipboard path.

   Deliberately hermetic: nothing here shells out to `pbcopy`/`tmux` or writes
   to a terminal. The parts worth pinning are the SEQUENCE bytes (a malformed
   escape prints garbage into the user's scrollback) and the guard rails around
   empty and oversized payloads.

   Control characters are built with `(char 27)` / `(char 7)` rather than
   pasted literally — a raw ESC byte in a source file is invisible in diffs
   and in most editors, so a corrupted one would survive review."
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.string :as str]
            [ai.brainyard.agent-tui.clipboard :as clipboard]
            [ai.brainyard.agent.interface.tui.ansi :as ansi])
  (:import [java.util Base64]))

(def ^:private ESC (str (char 27)))
(def ^:private BEL (str (char 7)))

(def ^:private osc52-re
  ;; re-pattern, not a #"..." literal, for the same visibility reason.
  (re-pattern (str "(?s)" ESC "\\]52;([a-z]);([A-Za-z0-9+/=]*)" BEL)))

(defn- decode-osc52
  "Pull the payload back out of an OSC 52 sequence and decode it, so the test
   asserts on round-tripped TEXT rather than on a base64 literal."
  [^String s]
  (when-let [m (re-matches osc52-re s)]
    {:selection (nth m 1)
     :text      (String. (.decode (Base64/getDecoder) ^String (nth m 2)) "UTF-8")}))

;; ============================================================================
;; Sequence construction
;; ============================================================================

(deftest osc52-copy-builds-a-well-formed-sequence
  (testing "OSC intro, CLIPBOARD selector, base64 payload, BEL terminator"
    (let [s (ansi/osc52-copy "hello")]
      (is (str/starts-with? s (str ESC "]52;c;"))
          "OSC 52 with the `c` (CLIPBOARD) selector")
      (is (str/ends-with? s BEL) "BEL-terminated")
      (is (= {:selection "c" :text "hello"} (decode-osc52 s)))))

  (testing "the intro is OSC (ESC ]) — not the CSI (ESC [) that `esc` holds"
    (is (not (str/starts-with? (ansi/osc52-copy "x") (str ESC "[")))
        "ESC [ would make the terminal parse this as a CSI and print the payload")))

(deftest osc52-copy-round-trips-non-ascii
  (testing "UTF-8 survives base64 — the emoji/CJK case that defeats width math"
    (doseq [text ["검색 결과" "✅ done 👨‍👩‍👧‍👦" "naïve — résumé" "tab\there\nnewline"]]
      (is (= text (:text (decode-osc52 (ansi/osc52-copy text))))
          (str "round-trips: " (pr-str text))))))

(deftest osc52-copy-refuses-empty-payload
  (testing "an empty OSC 52 payload CLEARS the clipboard — never emit one by accident"
    (is (= "" (ansi/osc52-copy nil)))
    (is (= "" (ansi/osc52-copy "")))))

(deftest osc52-copy-honors-selection-arg
  (testing "`p` targets the X11 PRIMARY selection"
    (is (= {:selection "p" :text "sel"} (decode-osc52 (ansi/osc52-copy "sel" "p"))))))

;; ============================================================================
;; copy! guard rails (no mechanism runs — these all short-circuit)
;; ============================================================================

(deftest copy-rejects-blank-text
  (doseq [blank [nil "" "   " "\n\t"]]
    (let [{:keys [ok? detail bytes]} (clipboard/copy! blank)]
      (is (false? ok?) (str "blank input is not a copy: " (pr-str blank)))
      (is (zero? bytes))
      (is (= "nothing to copy" detail)))))

(deftest copy-rejects-oversized-text
  (testing "past the OSC 52 control-string cap we refuse — terminals TRUNCATE
            silently, handing over a partial answer that looks complete"
    (let [big (apply str (repeat (inc clipboard/osc52-max-source-bytes) "x"))
          {:keys [ok? detail]} (clipboard/copy! big)]
      (is (false? ok?))
      (is (str/includes? detail "too large for OSC 52")))))

(deftest osc52-source-limit-derives-from-base64-budget
  (testing "a source payload AT the limit must still encode within the cap.
            base64 pads to whole 4-char groups, so the encoded size is
            ceil(n/3)*4 — deriving the source cap as n*4/3 overshoots and
            lets through a payload that encodes 2 bytes past the limit."
    (let [at-limit (count (.encodeToString (Base64/getEncoder)
                                           (byte-array clipboard/osc52-max-source-bytes)))]
      (is (<= at-limit clipboard/osc52-max-base64-bytes)))

    (testing "and one group more does NOT fit — the cap is tight, not merely safe"
      (let [over (count (.encodeToString (Base64/getEncoder)
                                         (byte-array (+ 3 clipboard/osc52-max-source-bytes))))]
        (is (> over clipboard/osc52-max-base64-bytes))))))

;; ============================================================================
;; describe — the honesty contract
;; ============================================================================

(deftest describe-distinguishes-verified-from-sent
  (testing "a verified mechanism may say 'copied'"
    (let [s (clipboard/describe {:ok? true :via :pbcopy :bytes 42 :verified? true})]
      (is (str/includes? s "Copied 42 bytes"))
      (is (str/includes? s "pbcopy"))))

  (testing "OSC 52 is unverifiable, so it must NOT claim the copy happened"
    (let [s (clipboard/describe {:ok? true :via :osc52 :bytes 42 :verified? false})]
      (is (str/includes? s "Sent 42 bytes"))
      (is (not (str/includes? s "Copied"))
          "claiming success on Terminal.app — which drops OSC 52 — is worse than silence")
      (is (str/includes? s "Terminal.app") "names the terminals that will silently drop it")))

  (testing "failures surface the reason"
    (is (str/includes? (clipboard/describe {:ok? false :detail "no clipboard mechanism available"})
                       "no clipboard mechanism available")))

  (testing "byte count is singularized"
    (is (str/includes? (clipboard/describe {:ok? true :via :pbcopy :bytes 1 :verified? true})
                       "1 byte to"))))
