;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent-tui.answer-descriptor-test
  "Re-rendering answers on resume, rather than re-wrapping them.

   `fit-rows` can only make replayed rows FIT a width — it has rows, not the
   values that drew them, so a box written at 130 and resumed at 80 came back as
   a WRAPPED 130-column box with its border fragments mid-screen.

   `tail-segments` closes that for emits that recorded a descriptor: it locates
   the rendered block inside the tail by content and hands that span a renderer
   built from the answer's source. Everything here pins one of the two halves —
   that the located span is genuinely re-drawn, or that an unlocatable
   descriptor degrades to exactly the old behaviour.

   See docs/design/answer-descriptor-resume.md."
  (:require [ai.brainyard.agent-tui.core :as core]
            [ai.brainyard.agent.interface.tui.format :as fmt]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(def ^:private segments #'core/tail-segments)

(defn- tail-renderer
  "The replay as it behaved before descriptors existed: the whole tail refitted
   to a width, nothing redrawn."
  [tail]
  (fn [cols] (#'core/fit-rows (str/split-lines tail) cols)))

(def ^:private answer
  (str "The wrap width is what changes between the session that wrote this "
       "answer and the session that resumes it, so the box has to be redrawn "
       "rather than merely refitted."))

(defn- render-all
  "Every segment rendered at `cols`, concatenated — what the replay puts on
   screen."
  [segs cols]
  (into [] (mapcat #((:render %) cols)) segs))

(defn- box-rows [s] (filter #(str/starts-with? (fmt/strip-ansi %) "│") s))

(defn- descriptor-for
  "The descriptor an emit at `cols` would have recorded — `:block` is filled by
   the tee from the exact string it wrote, so this mirrors that."
  ([cols] (descriptor-for cols :boxed answer))
  ([cols variant text]
   {:kind :answer :variant variant :text text
    :block (if (= :plain variant)
             (fmt/format-answer-plain text cols)
             (fmt/format-answer text cols))}))

;; ---------------------------------------------------------------------------
;; The regression guard: inert until something records a descriptor
;; ---------------------------------------------------------------------------

(deftest no-descriptors-is-byte-identical-to-the-old-renderer
  (testing "with an empty descriptor list the replay is unchanged"
    ;; The whole feature has to be invisible until an emit opts in — this is
    ;; the property that lets it ship without touching the resume of every
    ;; session already on disk.
    (let [tail (str (fmt/format-answer answer 130) "\n╰ done\n")]
      (doseq [cols [40 80 130 200]]
        (is (= ((tail-renderer tail) cols)
               (render-all (segments tail []) cols))
            (str "identical at " cols " columns"))))))

;; ---------------------------------------------------------------------------
;; Re-rendering
;; ---------------------------------------------------------------------------

(deftest a-located-answer-is-redrawn-at-the-resumed-width
  (testing "the box fits the new width instead of wrapping the old one"
    (let [tail (fmt/format-answer answer 130)
          out  (render-all (segments tail [(descriptor-for 130)]) 80)]
      (is (= (str/split-lines (fmt/format-answer answer 80)) out)
          "renders as a fresh 80-column box, not a re-wrapped 130-column one")
      (is (not= (str/split-lines tail) out)
          "which is emphatically not the rows that were on disk")
      (is (apply = (map fmt/display-width (box-rows out)))
          "every row of the frame is one width — the right border lines up")
      (is (every? #(<= (fmt/display-width %) 80) out)
          "and nothing overflows the resumed width"))))

(deftest an-unlocatable-descriptor-degrades-to-frozen-rows
  (testing "a descriptor whose block was cut by the byte tail changes nothing"
    ;; The tail is the last N BYTES, so the oldest emit in it is routinely a
    ;; fragment. That must cost the re-render of one answer, never the replay.
    (let [full (fmt/format-answer answer 130)
          tail (subs full (quot (count full) 2))]
      (doseq [cols [40 80]]
        (is (= ((tail-renderer tail) cols)
               (render-all (segments tail [(descriptor-for 130)]) cols))
            "identical to having recorded nothing")))))

(deftest matching-survives-a-theme-change
  (testing "the stored block locates by stripped text, not by raw bytes"
    ;; The block is only ever a needle; the re-render uses today's styling. Byte
    ;; matching would silently regress to frozen rows the first time the answer
    ;; box was recoloured between sessions.
    (let [tail  (fmt/format-answer answer 130)
          recol (str/replace (fmt/format-answer answer 130) "\033[92m" "\033[95m")
          out   (render-all (segments tail [(assoc (descriptor-for 130) :block recol)]) 80)]
      (is (= (str/split-lines (fmt/format-answer answer 80)) out)
          "located despite the colour drift, and redrawn at the new width"))))

;; ---------------------------------------------------------------------------
;; Ordering and coverage
;; ---------------------------------------------------------------------------

(deftest two-identical-boxes-resolve-in-order
  (testing "the second descriptor does not re-match the first box"
    ;; Same text at the same width renders byte-identically, so the ONLY thing
    ;; separating the two spans is the cursor that never rewinds.
    (let [one  (fmt/format-answer answer 130)
          tail (str one "\n" one)
          segs (segments tail [(descriptor-for 130) (descriptor-for 130)])]
      (is (= 2 (count (filter #(= :answer (:kind %)) segs)))
          "both located")
      ;; Their ROWS are identical — that is the premise, not a bug — so what
      ;; distinguishes "resolved in order" from "matched the first box twice"
      ;; is the partition: a search that restarted at 0 would cover box one
      ;; twice and box two never, and the tail would not reassemble.
      (is (= (str/split-lines tail) (into [] (mapcat :rows) segs))
          "and the segments still reassemble into the tail, so no span was double-covered"))))

(deftest segments-cover-every-row-exactly-once
  (testing "the split is a partition of the tail, in order"
    ;; A gap silently drops transcript; an overlap silently duplicates it.
    ;; Neither is visible in a width assertion, so it is pinned separately.
    (let [tail (str "before\n" (fmt/format-answer answer 130) "\nafter\n"
                    (fmt/format-answer-plain answer 130) "\ntrailing")
          segs (segments tail [(descriptor-for 130)
                               (descriptor-for 130 :plain answer)])]
      (is (= (str/split-lines tail) (into [] (mapcat :rows) segs))
          "concatenating the segments' source rows reproduces the tail"))))

(deftest the-recorded-variant-wins-not-the-current-mode
  (testing "a :plain descriptor redraws plain, a :boxed one redraws boxed"
    ;; Settled decision: the tail is a transcript of what happened. Resuming
    ;; into :quiet must not silently reformat history — reflow is about width.
    (let [plain-tail (fmt/format-answer-plain answer 130)
          boxed-tail (fmt/format-answer answer 130)
          plain-out  (render-all (segments plain-tail
                                           [(descriptor-for 130 :plain answer)]) 80)
          boxed-out  (render-all (segments boxed-tail [(descriptor-for 130)]) 80)]
      (is (empty? (filter #(str/includes? % "┌") plain-out))
          ":plain stays box-free")
      (is (seq (filter #(str/includes? % "┌") boxed-out))
          ":boxed keeps its frame"))))
