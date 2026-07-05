;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.common.timeline-history-test
  "Aggressive property + simulation harness for TIMELINE-based conversation
   history (conversation-style \"timeline\", commit c076705).

   Timeline history is two cooperating pieces:
     1. the append-only `:previous-turns` chain (previous-turns/append-turn) —
        full Q/A + iterations for recent turns, progressively compressed;
     2. the volatile, sliding `:conversation` window, where completed own-turn
        Q/A pairs OLDER than `:conversation-keep-verbatim` collapse to
        `{:role \"turn-ref\" :turn N}` references pointing INTO that chain
        (context-actions/timeline-transform).

   The fragile, under-tested part is the SEAM between them: the ref turn
   numbers must tail-align to real chain entries (`base = chain-count - m`),
   nothing may be double-rendered (the G9 de-dup), and the last K exchanges
   must stay verbatim. This harness simulates whole sessions and fuzzes the
   real production fns to pin those invariants — rather than asserting a few
   hand-built windows.

   Layer 2 (the real-agent `by run` + `ask --attach` end-to-end harness) lives
   in scripts/test-conversation-timeline.sh."
  (:require [ai.brainyard.agent.common.context-actions :as ctx-actions]
            [ai.brainyard.agent.common.previous-turns :as prev-turns]
            [clojure.set :as set]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]))

;; ── production fns under test (private → deref the vars) ───────────────────
(def ^:private timeline-transform    @#'ctx-actions/timeline-transform)
(def ^:private completed-turn-units  @#'ctx-actions/completed-turn-units)
(def ^:private fmt-conversation
  (requiring-resolve 'ai.brainyard.agent.core.context.formatters/format-conversation))
(def ^:private fmt-previous-turns
  @(requiring-resolve 'ai.brainyard.agent.common.coact-agent/format-previous-turns))

(def ^:private self :root)

;; ── session simulator ─────────────────────────────────────────────────────
;; One completed turn N renders to messages whose content is labelled by N so a
;; converted turn is detectable by the (dis)appearance of "Q<N>" / "A<N>":
;;   user "Q<N>" · [interleaved dispatch/sub-agent/system] · self-answer "A<N>"

(defn- unit-msgs
  "Messages for one completed turn `t`, with `interleaves` (a seq of
   :dispatch|:sub|:system) sitting between the question and this agent's answer."
  [t interleaves]
  (-> [{:role "user" :content (str "Q" t)}]
      (into (map (fn [el]
                   (case el
                     :dispatch {:role "assistant" :content (str "D" t)
                                :agent-id self :kind :dispatch}
                     :sub      {:role "assistant" :content (str "S" t)
                                :agent-id :explore-agent :kind :turn-answer}
                     :system   {:role "system" :content (str "SYS" t)}))
                 interleaves))
      (conj {:role "assistant" :content (str "A" t)
             :agent-id self :kind :turn-answer})))

(def ^:private gen-interleaves
  (gen/vector (gen/elements [:dispatch :sub :system]) 0 3))

(def ^:private gen-scenario
  "A sliding-window scenario: `w` completed turns are VISIBLE in the window;
   `slide` older completed turns have scrolled off but remain in the chain, so
   chain-count = w + slide. Window turns carry absolute labels (slide+1 .. w+slide)
   so the newest visible unit is exactly the chain's last entry (tail-aligned)."
  (gen/let [w     (gen/choose 1 6)
            inter (gen/vector gen-interleaves w w)
            slide (gen/choose 0 5)
            keepv (gen/choose 0 6)]
    (let [chain-count (+ w slide)
          units  (mapv (fn [i] (unit-msgs (+ slide i 1) (nth inter i))) (range w))
          window (vec (mapcat identity units))]
      {:window window :chain-count chain-count :keep keepv :w w :slide slide})))

;; ── invariant helpers ─────────────────────────────────────────────────────

(defn- guards-pass?
  "timeline-transform converts only when there is something to convert
   (convert-n > 0) AND the chain is at least as long as the window's unit count
   (base = chain-count - m ≥ 0). Otherwise the window is returned untouched."
  [chain-count w keepv]
  (and (pos? (- w keepv)) (>= (- chain-count w) 0)))

(defn- ref-turns
  "Absolute turn numbers carried by the output's turn-ref entries (ranges
   expanded)."
  [out]
  (set (mapcat (fn [{:keys [turn to]}] (range turn (inc (or to turn))))
               (filter #(= "turn-ref" (:role %)) out))))

(defn- expected-converted
  "The contiguous absolute-turn set timeline-transform should collapse: the
   window's units minus the newest `keepv`, tail-aligned to the chain."
  [chain-count w keepv]
  (set (range (inc (- chain-count w)) (inc (- chain-count keepv)))))

(defn- contents [out] (set (keep :content out)))

(defn- subsequence?
  "Is `sub` an order-preserving subsequence of `full`?"
  [sub full]
  (loop [s (seq sub) f (seq full)]
    (cond (nil? s) true
          (nil? f) false
          (= (first s) (first f)) (recur (next s) (next f))
          :else (recur s (next f)))))

;; ── properties ─────────────────────────────────────────────────────────────

(defspec timeline-never-throws-and-preserves-order 300
  (prop/for-all [{:keys [window chain-count keep]} gen-scenario]
                (let [out (timeline-transform window self chain-count keep)]
                  (and
       ;; non-ref survivors are an in-order subsequence of the input (nothing
       ;; reordered; refs only ever REPLACE Q/A in place)
                   (subsequence? (remove #(= "turn-ref" (:role %)) out) window)
       ;; ref turn numbers are non-decreasing (timeline order == chain order)
                   (apply <= 0 (map :turn (filter #(= "turn-ref" (:role %)) out)))))))

(defspec timeline-refs-are-in-range-and-tail-aligned 300
  (prop/for-all [{:keys [window chain-count keep w slide]} gen-scenario]
                (let [out  (timeline-transform window self chain-count keep)
                      refs (filter #(= "turn-ref" (:role %)) out)]
                  (if (guards-pass? chain-count w keep)
                    (and
         ;; every ref points at a REAL chain entry: 1 ≤ turn ≤ to ≤ chain-count
                     (every? (fn [{:keys [turn to]}]
                               (and (<= 1 turn) (<= turn (or to turn)) (<= (or to turn) chain-count)))
                             refs)
         ;; the collapsed set is exactly the tail-aligned window prefix
                     (= (expected-converted chain-count w keep) (ref-turns out))
         ;; the newest converted turn is chain-count - keep (nothing newer refs)
                     (or (empty? refs)
                         (= (- chain-count keep) (apply max (ref-turns out)))))
        ;; guards fail ⇒ window returned untouched, no refs at all
                    (and (empty? refs) (= window out))))))

(defspec timeline-dedups-converted-keeps-tail-verbatim 300
  (prop/for-all [{:keys [window chain-count keep w slide]} gen-scenario]
                (let [out  (timeline-transform window self chain-count keep)
                      cs   (contents out)
                      conv (ref-turns out)
          ;; window's absolute turn labels
                      win-turns (set (range (inc slide) (inc chain-count)))
                      kept      (set/difference win-turns conv)]
                  (and
       ;; G9: a converted turn's Q/A text is GONE from the window (lives only in
       ;; the previous-turns chain the ref points at) — no double-render
                   (every? (fn [n] (and (not (contains? cs (str "Q" n)))
                                        (not (contains? cs (str "A" n))))) conv)
       ;; kept (verbatim) turns still carry their Q AND A text
                   (every? (fn [n] (and (contains? cs (str "Q" n))
                                        (contains? cs (str "A" n)))) kept)))))

(defspec timeline-never-drops-non-qa-messages 300
  (prop/for-all [{:keys [window chain-count keep]} gen-scenario]
                (let [out (timeline-transform window self chain-count keep)
                      cs  (contents out)
                      ;; dispatch (D*), sub-agent answers (S*), system notes
                      ;; (SYS*) are not own-turn answers and must survive the
                      ;; collapse. NB: don't use clojure.core/keep here — `keep`
                      ;; is bound to the keep-verbatim value (a Long).
                      non-qa (->> (map :content window)
                                  (filter #(and % (or (str/starts-with? % "D")
                                                      (str/starts-with? % "S")))))]
                  (every? cs non-qa))))

(defspec timeline-refs-resolve-in-a-real-appended-chain 200
  ;; The composition invariant: build the ACTUAL previous-turns chain with
  ;; append-turn, render it, and confirm every [Turn N] the conversation refs
  ;; resolves to a real "[Turn N ...]" entry in the Previous Turns section.
  (prop/for-all [{:keys [window chain-count keep w]} gen-scenario]
                (let [out   (timeline-transform window self chain-count keep)
                      chain (reduce (fn [acc n]
                                      (prev-turns/append-turn acc {:question (str "Q" n)
                                                                   :answer   (str "A" n)
                                                                   :iterations []}))
                                    [] (range 1 (inc chain-count)))
                      rendered (or (fmt-previous-turns chain) "")]
                  (every? (fn [n] (str/includes? rendered (str "[Turn " n)))
                          (ref-turns out)))))

;; ── explicit edge cases (regressions + boundary conditions) ────────────────

(deftest empty-window-does-not-throw-regression
  (testing "empty conversation window (fresh session, first turn) returns []
            instead of IndexOutOfBoundsException (regression: seg-starts is
            always seeded [0], so (nth msgs 0) blew up on an empty vector)"
    (is (= [] (completed-turn-units [] self)))
    (is (= [] (timeline-transform [] self 0 3)))
    (is (= [] (timeline-transform [] self 5 2)))))

(deftest single-user-message-window
  (testing "a lone in-flight user message (no answer yet) yields no units and
            is returned untouched"
    (let [msgs [{:role "user" :content "Q1"}]]
      (is (= [] (completed-turn-units msgs self)))
      (is (= msgs (timeline-transform msgs self 3 0))))))

(deftest all-system-window-untouched
  (testing "a window with no own-turn Q/A (only system/sub-agent noise) has no
            units to collapse and survives verbatim"
    (let [msgs [{:role "system" :content "[wakeup] task done"}
                {:role "assistant" :content "sub" :agent-id :explore-agent :kind :turn-answer}]]
      (is (= [] (completed-turn-units msgs self)))
      (is (= msgs (timeline-transform msgs self 4 1))))))

(deftest keep-verbatim-zero-collapses-everything-visible
  (testing "keep-verbatim 0 with a full chain collapses ALL visible units to refs"
    (let [window (vec (mapcat #(unit-msgs % []) [3 4 5]))
          out    (timeline-transform window self 5 0)]
      (is (= #{3 4 5} (ref-turns out)) "all three visible turns → refs 3,4,5")
      (is (empty? (filter #(and (:content %) (re-matches #"[QA]\d" (:content %))) out))
          "no verbatim Q/A remains"))))

(deftest deep-compaction-chain-shorter-than-window-untouched
  (testing "chain shorter than the window's unit count (deep compaction dropped
            entries the window still shows) → window untouched, no dangling refs"
    (let [window (vec (mapcat #(unit-msgs % []) [1 2 3 4]))]
      (is (= window (timeline-transform window self 1 0)))
      (is (= window (timeline-transform window self 2 0))))))

(deftest adjacent-refs-merge-into-ranges
  (testing "consecutive collapsed units with nothing between them merge into a
            single ranged ref; an interleaved message breaks the range"
    ;; turns 1,2,3 back-to-back → one [1–3] range
    (let [contiguous (vec (mapcat #(unit-msgs % []) [1 2 3 4 5]))
          out (timeline-transform contiguous self 5 1)
          refs (filter #(= "turn-ref" (:role %)) out)]
      (is (= 1 (count refs)) "1–4 collapse into ONE range ref")
      (is (= {:role "turn-ref" :turn 1 :to 4} (first refs))))
    ;; a system note between turn 2 and 3 splits the range into [1–2] and [3]
    (let [split (-> []
                    (into (unit-msgs 1 []))
                    (into (unit-msgs 2 []))
                    (conj {:role "system" :content "note"})
                    (into (unit-msgs 3 []))
                    (into (unit-msgs 4 [])))
          out  (timeline-transform split self 4 1)
          refs (filter #(= "turn-ref" (:role %)) out)]
      (is (some #(= {:role "turn-ref" :turn 1 :to 2} %) refs) "1–2 merged")
      (is (some #(= {:role "turn-ref" :turn 3} %) refs) "3 stands alone")
      (is (some #(= "note" (:content %)) out) "the interleaved note survives"))))

(deftest full-cycle-conversation-and-previous-turns-agree
  (testing "end-to-end: refs rendered in the conversation section all resolve to
            entries in the previous-turns section, and no Q/A is double-rendered"
    (let [chain-count 6, keep 2
          ;; back-to-back turns (no interleaves) so 1..4 collapse to one range
          window (vec (mapcat #(unit-msgs % []) (range 1 (inc chain-count))))
          out    (timeline-transform window self chain-count keep)
          conv-str (or (fmt-conversation out) "")
          chain  (reduce (fn [acc n]
                           (prev-turns/append-turn acc {:question (str "Q" n)
                                                        :answer   (str "A" n)
                                                        :iterations []}))
                         [] (range 1 (inc chain-count)))
          prev-str (or (fmt-previous-turns chain) "")]
      ;; turns 1..4 collapse; 5,6 stay verbatim
      (is (str/includes? conv-str "[Turns 1–4]") "old turns rendered as one range ref")
      (is (str/includes? conv-str "see Previous Turns"))
      (is (str/includes? conv-str "Q5") "kept turn 5 verbatim")
      (is (str/includes? conv-str "A6") "kept turn 6 verbatim")
      (is (not (str/includes? conv-str "Q1")) "collapsed turn 1 NOT double-rendered")
      ;; every referenced turn resolves in Previous Turns
      (doseq [n (ref-turns out)]
        (is (str/includes? prev-str (str "[Turn " n)) (str "Turn " n " resolves in Previous Turns"))))))
