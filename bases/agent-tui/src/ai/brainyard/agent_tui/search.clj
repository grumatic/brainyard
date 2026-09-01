;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent-tui.search
  "Keyword search over the rendered scrollback rows — the pure half of Ctrl-F.

   Nothing here touches the terminal or `!layout`: `scan` takes rows and a
   query and returns hits, `highlight-row` takes a row and spans and returns a
   marked row. The stateful half — where the viewport goes, what is current,
   when a rescan is due — lives in `layout`, next to the scrollback and the
   offset it has to derive. Same split as `links`.

   SEARCH RUNS ON THE STRIPPED ROW, and the spans are indices into that plain
   text. Searching the styled string instead fails three ways: a query that
   spans a style boundary never matches, escape bodies contribute phantom hits
   (`m`, `1`, `38;5`), and the resulting indices could not be handed to a span
   painter that has to insert BETWEEN escapes. `links/detect-in-row` reached
   the same conclusion for the same reason.

   Known limit, shared with `links`: a query broken across a render wrap is not
   present in any single row and will not be found. Fullscreen pre-wraps
   because `render-viewport!` addresses rows absolutely (see CLAUDE.md); the
   fix is the logical-lines refactor, not this namespace."
  (:require [ai.brainyard.agent.interface.tui.ansi :as ansi]
            [ai.brainyard.agent.interface.tui.format :as fmt]
            [clojure.string :as str]))

;; ============================================================================
;; Matching
;; ============================================================================

(defn case-sensitive?
  "Smart case: a query carrying an uppercase character is matched
   case-sensitively, otherwise case is ignored.

   The rg/vim convention, chosen because it needs no flag and no UI: a user who
   types lowercase means \"I don't care\", and a user who types `Error` has
   already said which one they want."
  [^String query]
  (boolean (and query (some #(Character/isUpperCase ^char %) query))))

(def regex-prefix
  "What opts a query into regex matching. `/` because a terminal user already
   reads it as \"pattern follows\", and because a literal leading slash stays
   reachable as `//` — see `compile-query`."
  "/")

(defn compile-query
  "Turn a typed query into a matcher spec, or nil when there is nothing to
   match. One of:

     {:kind :literal :needle \"…\" :cs? bool}
     {:kind :regex   :re #\"…\"}
     {:kind :invalid}                  ;; a regex that does not compile

   `:invalid` IS A VALUE, NOT AN EXCEPTION. The query recompiles on every
   keystroke, and nearly every prefix of a real pattern is broken on the way in
   (`(`, `[a`, a trailing backslash), so throwing would make the ordinary act
   of typing a regex an error path. The caller shows \"bad pattern\" and waits
   for the next key.

   A leading `/` selects regex; `//` escapes it, so a literal search for `/foo`
   is `//foo`. Smart case applies to both."
  [^String query]
  (cond
    (str/blank? query) nil

    (str/starts-with? query regex-prefix)
    (let [pat (subs query 1)]
      (cond
        (str/blank? pat) nil
        ;; `//foo` — the user meant a literal beginning with a slash.
        (str/starts-with? pat regex-prefix)
        {:kind :literal :needle pat :cs? (case-sensitive? pat)}
        :else
        (try {:kind :regex
              :re (re-pattern (if (case-sensitive? pat) pat (str "(?i)" pat)))}
             (catch Exception _ {:kind :invalid}))))

    :else {:kind :literal :needle query :cs? (case-sensitive? query)}))

(defn- literal-spans
  [^String s ^String needle cs?]
  (let [lower (when-not cs? (str/lower-case s))
        ;; `lower-case` is not length-preserving for every codepoint (İ, ﬁ),
        ;; and a length change makes every index after it point at the wrong
        ;; character. Fall back to a case-SENSITIVE scan rather than report
        ;; spans that highlight the wrong text.
        folded? (and lower (= (.length ^String lower) (.length s)))
        ^String hay (if folded? lower s)
        ^String ndl (if folded? (str/lower-case needle) needle)
        n (.length ndl)]
    (if (zero? n)
      []
      (loop [from 0, acc []]
        (let [i (.indexOf hay ndl (int from))]
          (if (neg? i)
            acc
            (recur (+ i n) (conj acc [i (+ i n)]))))))))

(defn- regex-spans
  [^String s re]
  (let [m   (re-matcher re s)
        len (.length s)]
    (loop [acc [], from 0]
      (if (or (> from len) (not (.find m (int from))))
        acc
        (let [a (.start m), b (.end m)]
          ;; A zero-width match (`x*`, `^`, a word boundary) matches at EVERY
          ;; position and never advances the cursor. Step past it without
          ;; recording, or the hit list fills with nothing-spans and the loop
          ;; does not terminate.
          (if (= a b)
            (recur acc (inc b))
            (recur (conj acc [a b]) b)))))))

(defn row-spans
  "Every `[start end)` span of the compiled `spec` in PLAIN `s`, ascending and
   non-overlapping. Empty when there is no match.

   ALL occurrences on the row, not just the first — otherwise \"next\" silently
   skips matches inside a long row and the `3/17` counter is a lie."
  [^String s spec]
  (if (or (str/blank? s) (nil? spec))
    []
    (case (:kind spec)
      :literal (literal-spans s (:needle spec) (:cs? spec))
      :regex   (regex-spans s (:re spec))
      [])))

(defn scan
  "Every hit of `query` across `rows` — a vector of RENDERED, styled scrollback
   rows — as `[{:idx :start :end} …]` in scrollback order.

   `:idx` is the scrollback index; `:start`/`:end` are indices into the row's
   STRIPPED text, which is the space `highlight-row` walks in.

   Takes a raw query string or an already-compiled spec, so a caller that needs
   to know WHY a query found nothing (`:invalid`) can compile once and pass the
   spec here rather than compiling twice."
  [rows query]
  (let [spec (if (map? query) query (compile-query query))]
    (if (or (nil? spec) (= :invalid (:kind spec)) (empty? rows))
      []
      (into []
            (comp (map-indexed
                   (fn [idx ^String row]
                     (when-not (str/blank? row)
                       ;; A row with no ESC needs no strip, and the strip is
                       ;; the expensive part of a full-scrollback scan.
                       (let [plain (if (neg? (.indexOf row "\u001b"))
                                     row
                                     (fmt/strip-ansi row))]
                         (seq (map (fn [[a b]] {:idx idx :start a :end b})
                                   (row-spans plain spec)))))))
                  (remove nil?)
                  cat)
            rows))))

(defn hit-at-or-before
  "Index into `hits` of the last hit at scrollback index `<= idx`, else 0.

   Which hit a fresh query lands on. Searching a scrollback is nearly always
   \"find the thing that scrolled past\", so the search starts from where the
   user is looking and works BACKWARDS; at the live tail that is simply the
   most recent occurrence."
  [hits idx]
  (if (empty? hits)
    -1
    (or (last (keep-indexed (fn [i h] (when (<= (long (:idx h)) (long idx)) i))
                            hits))
        0)))

(defn nearest-hit
  "Index into `hits` of the hit whose scrollback index is closest to `idx`.

   Used to re-seat after a rescan moved everything (a reflow, an expand/
   collapse splice): the old `:cur` is meaningless against a new hit list, but
   \"the match nearest where you were\" survives both."
  [hits idx]
  (if (empty? hits)
    -1
    (first (apply min-key
                  (fn [[_ h]] (abs (- (long (:idx h)) (long idx))))
                  (map-indexed vector hits)))))

;; ============================================================================
;; Highlighting
;; ============================================================================

(defn highlight-row
  "`row` — a rendered, styled scrollback row — with each span in `spans`
   marked. `spans` are `{:start :end :current?}` maps in the row's PLAIN index
   space, ascending and non-overlapping.

   The walk is `links/decorate-row*`'s, and deliberately so: one pass over the
   STYLED row tracking the plain index alongside it, so a marker is only ever
   inserted between escapes and never inside one. Two things it must keep
   doing:

   - RE-ASSERT the mark after every escape the row itself carries. Those
     escapes are not ours to interpret and one may be a full SGR reset, which
     would otherwise drop the highlight partway through a match.
   - Close before opening at a shared boundary, so two adjacent matches do not
     merge into one run.

   Width-preserving: it only ever adds escapes. That is what lets it run on the
   paint path after the width clamp."
  [^String row spans]
  (if (or (str/blank? row) (empty? spans))
    row
    (let [on-match  (ansi/mark-on :search/match)
          off-match (ansi/mark-off :search/match)
          on-cur    (ansi/mark-on :search/current)
          off-cur   (ansi/mark-off :search/current)
          starts (into {} (map (fn [s] [(long (:start s)) (boolean (:current? s))])) spans)
          ends   (into {} (map (fn [s] [(long (:end s)) (boolean (:current? s))])) spans)
          len (.length row)
          sb  (StringBuilder. (+ len 32))]
      (loop [i 0, p 0, cur nil]
        (if (>= i len)
          (do (when (some? cur) (.append sb ^String (if cur off-cur off-match)))
              (.toString sb))
          (let [c (.charAt row i)]
            (if (= 27 (int c))
              (let [e (long (fmt/ansi-seq-end row i))]
                (.append sb (.substring row i e))
                (when (some? cur) (.append sb ^String (if cur on-cur on-match)))
                (recur e p cur))
              (let [end-kind   (get ends p)
                    start-kind (get starts p)]
                (when (and (some? cur) (some? end-kind))
                  (.append sb ^String (if cur off-cur off-match)))
                (when (some? start-kind)
                  (.append sb ^String (if start-kind on-cur on-match)))
                (.append sb c)
                (recur (inc i) (inc p)
                       (cond (some? start-kind) start-kind
                             (some? end-kind)   nil
                             :else              cur))))))))))
