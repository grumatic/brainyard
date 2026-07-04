;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.tui.format
  "Pure formatting functions for TUI output.
   All functions: data in -> string out. No I/O side effects."
  (:require [ai.brainyard.agent.tui.ansi :as ansi]
            [ai.brainyard.agent.core.config :as config]
            [ai.brainyard.display-block.interface :as block]
            [clojure.string :as str]))

;; ============================================================================
;; Terminal Size (cached, updated on SIGWINCH)
;; ============================================================================

(defonce !terminal-size (atom {:rows 24 :cols 80}))

(defn- query-stty-size
  "Query terminal dimensions via `stty size < /dev/tty`.
   Returns [rows cols] or nil on failure."
  []
  (try
    (let [proc (.start (ProcessBuilder. ^"[Ljava.lang.String;"
                        (into-array String ["/bin/sh" "-c" "stty size < /dev/tty"])))
          out  (str/trim (slurp (.getInputStream proc)))
          exit (.waitFor proc)]
      (when (zero? exit)
        (let [parts (str/split out #"\s+")]
          (when (= 2 (count parts))
            [(Integer/parseInt (first parts))
             (Integer/parseInt (second parts))]))))
    (catch Exception _ nil)))

(defn refresh-terminal-size!
  "Re-query terminal dimensions and update the cache.
   Returns {:rows R :cols C}."
  []
  (let [[rows cols] (or (query-stty-size) [24 80])]
    (reset! !terminal-size {:rows rows :cols cols})))

;; Initialize on load
(refresh-terminal-size!)

(defn terminal-columns
  "Return cached terminal width in columns."
  []
  (:cols @!terminal-size))

(defn terminal-rows
  "Return cached terminal height in rows."
  []
  (:rows @!terminal-size))

;; ============================================================================
;; Helpers
;; ============================================================================

(defn- wide-codepoint?
  "Return true if Unicode codepoint occupies 2 terminal columns
   (CJK, fullwidth, or emoji presentation)."
  [^long cp]
  (or ;; — CJK / Fullwidth —
   (<= 0x1100 cp 0x115F)      ;; Hangul Jamo
   (<= 0x2E80 cp 0x303F)      ;; CJK Radicals, Kangxi, CJK Symbols
   (<= 0x3040 cp 0x33FF)      ;; Hiragana, Katakana, Bopomofo, CJK Compat
   (<= 0x3400 cp 0x4DBF)      ;; CJK Extension A
   (<= 0x4E00 cp 0x9FFF)      ;; CJK Unified Ideographs
   (<= 0xAC00 cp 0xD7AF)      ;; Hangul Syllables
   (<= 0xF900 cp 0xFAFF)      ;; CJK Compatibility Ideographs
   (<= 0xFE30 cp 0xFE4F)      ;; CJK Compatibility Forms
   (<= 0xFF01 cp 0xFF60)      ;; Fullwidth Forms
   (<= 0xFFE0 cp 0xFFE6)      ;; Fullwidth Signs
      ;; — Emoji (only EAW=W codepoints; W=Wide chars in mixed-width
      ;;   ranges like 0x2600..0x27BF that are NOT wide — e.g. ✓ ✗ ★ —
      ;;   stay narrow and don't get over-counted in padding) —
   (<= 0x231A cp 0x231B)      ;; ⌚⌛
   (<= 0x23E9 cp 0x23F3)      ;; ⏩-⏳
   (<= 0x23F8 cp 0x23FA)      ;; ⏸⏹⏺
   (<= 0x25FD cp 0x25FE)      ;; ◽◾
      ;; --- Misc Symbols (0x2600–0x26FF): only EAW=W ---
   (<= 0x2614 cp 0x2615)      ;; ☔☕
   (<= 0x2648 cp 0x2653)      ;; zodiac signs
   (= cp 0x267F)              ;; ♿
   (= cp 0x2693)              ;; ⚓
   (= cp 0x26A1)              ;; ⚡
   (<= 0x26AA cp 0x26AB)      ;; ⚪⚫
   (<= 0x26BD cp 0x26BE)      ;; ⚽⚾
   (<= 0x26C4 cp 0x26C5)      ;; ⛄⛅
   (= cp 0x26CE)              ;; ⛎
   (= cp 0x26D4)              ;; ⛔
   (= cp 0x26EA)              ;; ⛪
   (<= 0x26F2 cp 0x26F3)      ;; ⛲⛳
   (= cp 0x26F5)              ;; ⛵
   (= cp 0x26FA)              ;; ⛺
   (= cp 0x26FD)              ;; ⛽
      ;; --- Dingbats (0x2700–0x27BF): only EAW=W ---
   (= cp 0x2705)              ;; ✅
   (<= 0x270A cp 0x270B)      ;; ✊✋
   (= cp 0x2728)              ;; ✨
   (= cp 0x274C)              ;; ❌
   (= cp 0x274E)              ;; ❎
   (<= 0x2753 cp 0x2755)      ;; ❓❔❕
   (= cp 0x2757)              ;; ❗
   (<= 0x2795 cp 0x2797)      ;; ➕➖➗
   (= cp 0x27B0)              ;; ➰
   (= cp 0x27BF)              ;; ➿
      ;; --- Other blocks ---
   (<= 0x2B1B cp 0x2B1C)      ;; ⬛⬜
   (= cp 0x2B50)              ;; ⭐
   (= cp 0x2B55)              ;; ⭕
   (= cp 0x3030)              ;; 〰
   (= cp 0x303D)              ;; 〽
   (= cp 0x3297)              ;; ㊗
   (= cp 0x3299)              ;; ㊙
   (<= 0x1F000 cp 0x1FAFF)    ;; Supplementary emoji blocks (incl. flags,
                              ;; symbols, pictographs, transport, supplemental)
   (<= 0x1FC00 cp 0x1FFFD)))  ;; Symbols for Legacy Computing + rest

(defn- zero-width-codepoint?
  "Return true if codepoint is zero-width (variation selectors, ZWJ, tags, etc.)."
  [^long cp]
  (or (= cp 0x200B)              ;; zero-width space
      (= cp 0x200C)              ;; ZWNJ
      (= cp 0x200D)              ;; ZWJ (emoji sequences)
      (<= 0xFE00 cp 0xFE0F)      ;; variation selectors
      (= cp 0xFEFF)              ;; BOM / ZWNBSP
      (<= 0xE0020 cp 0xE007F)))  ;; tag characters (flag sequences)

(defn- skip-ansi-seq
  "From index i (pointing at ESC), skip an escape sequence and return the index
   just past its terminator. Handles two shapes:
     - CSI `ESC[ ... <letter>` (e.g. SGR colour codes) -- skip to the final letter.
     - OSC `ESC] ... (BEL | ST)` where ST is `ESC \\` (e.g. OSC-8 hyperlinks). The
       payload (a URL) must NOT be counted as visible width, so skip to the
       BEL/ST terminator rather than stopping at the first letter in the URL."
  [^String s ^long i]
  (let [len (.length s)
        nxt (when (< (inc i) len) (.charAt s (inc i)))]
    (if (= nxt \])
      ;; OSC: ESC ] ... terminated by BEL (\u0007) or ST (ESC \\)
      (loop [j (+ i 2)]
        (cond
          (>= j len)                       j
          (= (.charAt s j) \u0007)          (inc j)
          (and (= (.charAt s j) \u001b)
               (< (inc j) len)
               (= (.charAt s (inc j)) \\)) (+ j 2)
          :else                            (recur (inc j))))
      ;; CSI / other: skip to the terminating letter
      (loop [j (inc i)]
        (if (>= j len)
          j
          (if (Character/isLetter (.charAt s j))
            (inc j)
            (recur (inc j))))))))

(defn- emoji-vs16-next?
  "True when char index `ni` (the position just after a base codepoint) holds
   U+FE0F, the emoji variation selector — which makes the preceding base render
   2 columns even when its default presentation is 1 (e.g. ⚠ U+26A0 → ⚠️)."
  [^String s ^long ni]
  (and (< ni (.length s))
       (= 0xFE0F (Character/codePointAt s (int ni)))))

(defn display-width
  "Terminal display width of a string. CJK/fullwidth/emoji chars count as 2
   columns. A base char followed by U+FE0F (emoji presentation, e.g. ⚠️) also
   counts as 2. Zero-width chars (ZWJ, variation selectors) count as 0. Skips
   ANSI escape sequences."
  [^String s]
  (let [len (.length s)]
    (loop [i 0, w 0]
      (if (>= i len)
        w
        (let [ch (.charAt s i)]
          (if (= ch \u001b)
            (recur (skip-ansi-seq s i) w)
            (let [cp (Character/codePointAt s (int i))
                  cw (cond
                       (zero-width-codepoint? cp) 0
                       (or (wide-codepoint? cp)
                           (emoji-vs16-next? s (+ i (Character/charCount cp)))) 2
                       :else                       1)]
              (recur (+ i (Character/charCount cp)) (+ w cw)))))))))

(defn- char-index-at-width
  "Find the char index where cumulative display-width reaches limit.
   Returns the index of the first codepoint that would exceed the limit.
   Handles surrogate pairs and skips ANSI escape sequences."
  [^String s limit]
  (let [len (.length s)]
    (loop [i 0, w 0]
      (if (>= i len)
        i
        (let [ch (.charAt s i)]
          (if (= ch \u001b)
            (recur (skip-ansi-seq s i) w)
            (let [cp (Character/codePointAt s (int i))
                  cw (cond
                       (zero-width-codepoint? cp) 0
                       (or (wide-codepoint? cp)
                           (emoji-vs16-next? s (+ i (Character/charCount cp)))) 2
                       :else                       1)]
              (if (and (pos? cw) (> (+ w cw) limit))
                i
                (recur (+ i (Character/charCount cp)) (+ w cw))))))))))

(defn- truncate
  "Truncate string to max-len, appending ellipsis if needed."
  [s max-len]
  (if (and s (> (count s) max-len))
    (str (subs s 0 (- max-len 1)) ansi/ellipsis)
    s))

(defn- indent
  "Indent each line of a multi-line string."
  [s prefix]
  (when s
    (->> (str/split-lines s)
         (map #(str prefix %))
         (str/join "\n"))))

(defn- word-wrap
  "Wrap a single line to max-width display columns, breaking at word boundaries.
   Accounts for CJK double-width characters.
   Returns a seq of lines, each <= max-width display columns."
  [line max-width]
  (if (<= (display-width line) max-width)
    [line]
    (loop [remaining line
           result []]
      (if (<= (display-width remaining) max-width)
        (conj result remaining)
        ;; Find char index where display-width hits the limit
        (let [limit-idx (char-index-at-width remaining max-width)
              ;; Look for last space at or before that char index
              break-at (let [idx (str/last-index-of remaining " " limit-idx)]
                         (if (and idx (pos? idx))
                           idx
                           limit-idx))]
          (recur (str/trim (subs remaining break-at))
                 (conj result (subs remaining 0 break-at))))))))

(defn format-number
  "Format number with comma separators. Handles negatives — the sign is kept
   separate from the grouped digits so e.g. -692 renders \"-692\", not \"-,692\"."
  [n]
  (if (nil? n)
    "0"
    (let [neg?    (neg? (long n))
          s       (str (Math/abs (long n)))
          grouped (loop [result "" remaining s]
                    (if (<= (count remaining) 3)
                      (str remaining result)
                      (recur (str "," (subs remaining (- (count remaining) 3)) result)
                             (subs remaining 0 (- (count remaining) 3)))))]
      (str (when neg? "-") grouped))))

;; ============================================================================
;; Markdown → ANSI
;; ============================================================================

(defn- strip-ansi
  "Remove all ANSI escape sequences from a string."
  [s]
  (str/replace s #"\033\[[0-9;]*m" ""))

;; --- Inline markdown --------------------------------------------------

(defn- convert-inline-code
  "Convert `code` to ANSI cyan."
  [s]
  (str/replace s #"`([^`]+)`"
               (fn [[_ code]] (ansi/style code ansi/cyan))))

(defn- convert-bold
  "Convert **bold** to ANSI bold bright-white."
  [s]
  (str/replace s #"\*\*(.+?)\*\*"
               (fn [[_ text]] (ansi/header text))))

(defn- convert-italic
  "Convert *italic* to ANSI italic. Follows CommonMark emphasis rules:
   the inner text must start AND end with a non-whitespace character,
   and the surrounding `*` must not be adjacent to a word character.
   This preserves literal `*` in Clojure-y text like `(* 1 2)` or
   `*var*` next to identifiers; only well-formed *italic spans* convert."
  [s]
  (str/replace s #"(?<![\w*])\*([^*\s][^*\n]*?[^*\s]|[^*\s])\*(?![\w*])"
               (fn [[_ text]] (ansi/style text ansi/italic))))

(defn- convert-inline-markdown
  "Apply inline markdown conversions: code → bold → italic."
  [s]
  (-> s convert-inline-code convert-bold convert-italic))

;; --- ANSI-aware word-wrap ---------------------------------------------

(defn- find-active-ansi-codes
  "Scan ANSI codes in s up to char-index end-pos.
   Return concatenated codes still active (after last reset)."
  [^String s end-pos]
  (let [prefix  (subs s 0 (min end-pos (.length s)))
        matcher (re-matcher #"\033\[[0-9;]*m" prefix)]
    (loop [active []]
      (if (.find matcher)
        (let [code (.group matcher)]
          (recur (if (= code ansi/reset) [] (conj active code))))
        (apply str active)))))

(defn- ansi-aware-word-wrap
  "Wrap line to max-width display columns.
   When splitting mid-span, closes and reopens active ANSI state."
  [line max-width]
  (if (<= (display-width line) max-width)
    [line]
    (loop [remaining line, result []]
      (if (<= (display-width remaining) max-width)
        (conj result remaining)
        (let [limit-idx (char-index-at-width remaining max-width)
              break-at  (let [idx (str/last-index-of remaining " " limit-idx)]
                          (if (and idx (pos? idx)) idx limit-idx))
              first-part   (subs remaining 0 break-at)
              rest-part    (str/trim (subs remaining break-at))
              active-codes (find-active-ansi-codes remaining break-at)
              first-closed (if (seq active-codes)
                             (str first-part ansi/reset)
                             first-part)
              rest-reopened (if (seq active-codes)
                              (str active-codes rest-part)
                              rest-part)]
          (recur rest-reopened (conj result first-closed)))))))

;; --- GFM table helpers ------------------------------------------------

(def ^:private table-row-re
  "Loose match: line contains at least one '|' separator (with optional
   leading/trailing pipes and whitespace)."
  #"^\s*\|?[^|\n]*\|.*$")

(def ^:private table-sep-re
  "GFM separator row: cells of the form '---', ':---', '---:', ':---:'."
  #"^\s*\|?\s*:?-{2,}:?(\s*\|\s*:?-{2,}:?)+\s*\|?\s*$")

(defn- table-row-line?
  "Plausible table row: contains a '|' and is not a separator row."
  [line]
  (and (some? line)
       (boolean (re-matches table-row-re line))
       (not (re-matches table-sep-re line))))

(defn- split-table-row
  "Split a GFM table row into trimmed cell strings.
   Strips optional leading/trailing '|' and splits on '|'."
  [^String line]
  (let [trimmed (-> line str/trim
                    (str/replace #"^\|" "")
                    (str/replace #"\|$" ""))]
    (mapv str/trim (str/split trimmed #"\|" -1))))

(defn- parse-alignment
  "Map a separator cell ':---', ':---:', '---:', '---' to :left/:center/:right."
  [cell]
  (let [c      (str/trim cell)
        starts (str/starts-with? c ":")
        ends   (str/ends-with? c ":")]
    (cond
      (and starts ends) :center
      ends              :right
      :else             :left)))

(defn- consume-table
  "Parse a table starting at header-line/sep-line and consume body rows from
   `remaining` until a non-row line appears.
   Returns [table-block tail-lines]."
  [header-line sep-line remaining]
  (let [header (split-table-row header-line)
        n      (count header)
        align  (vec (take n (concat (mapv parse-alignment (split-table-row sep-line))
                                    (repeat :left))))
        [rows tail] (loop [rs remaining, acc []]
                      (if (and (seq rs) (table-row-line? (first rs)))
                        (recur (rest rs) (conj acc (split-table-row (first rs))))
                        [acc rs]))
        rows   (mapv (fn [r] (vec (take n (concat r (repeat ""))))) rows)]
    [{:type :table :content {:header header :align align :rows rows}}
     tail]))

;; --- Block-level markdown parser --------------------------------------

(defn- parse-markdown-blocks
  "Parse markdown text into [{:type :keyword :content \"...\"}]."
  [text]
  (let [lines (str/split-lines text)]
    (loop [remaining lines, blocks [], in-code false, code-lines []]
      (if (empty? remaining)
        (if in-code
          (conj blocks {:type :code-block :content (str/join "\n" code-lines)})
          blocks)
        (let [line (first remaining)
              rst  (rest remaining)]
          (if in-code
            (if (re-matches #"^```.*" line)
              (recur rst
                     (conj blocks {:type :code-block :content (str/join "\n" code-lines)})
                     false [])
              (recur rst blocks true (conj code-lines line)))
            (cond
              (re-matches #"^```.*" line)
              (recur rst blocks true [])

              (re-matches #"^#{1,6}\s+.*" line)
              (recur rst (conj blocks {:type :header :content line}) false [])

              (re-matches #"^[-*_]{3,}$" line)
              (recur rst (conj blocks {:type :hr :content line}) false [])

              (re-matches #"^[-*+]\s+.*" line)
              (recur rst (conj blocks {:type :ul-item :content line}) false [])

              (re-matches #"^\d+\.\s+.*" line)
              (recur rst (conj blocks {:type :ol-item :content line}) false [])

              (re-matches #"^>\s?.*" line)
              (recur rst (conj blocks {:type :blockquote :content line}) false [])

              (str/blank? line)
              (recur rst (conj blocks {:type :empty :content ""}) false [])

              (and (table-row-line? line)
                   (let [nxt (first rst)]
                     (and nxt (re-matches table-sep-re nxt))))
              (let [[block tail] (consume-table line (first rst) (rest rst))]
                (recur tail (conj blocks block) false []))

              :else
              (recur rst (conj blocks {:type :paragraph :content line}) false []))))))))

;; --- Block renderers --------------------------------------------------

(defn- render-code-block
  "Render code block lines: dim cyan, no wrap, truncate if too wide."
  [content max-width]
  (let [lines (str/split-lines content)]
    (mapv (fn [line]
            (ansi/style
             (if (> (display-width line) max-width)
               (str (subs line 0 (char-index-at-width line (- max-width 1)))
                    ansi/ellipsis)
               line)
             ansi/dim ansi/cyan))
          lines)))

(defn- render-header
  "Render header: bold bright-white, wrapped."
  [content max-width]
  (let [text (str/replace content #"^#{1,6}\s+" "")]
    (ansi-aware-word-wrap (ansi/header text) max-width)))

(defn- render-list-item
  "Render a list item with prefix and hanging indent on wrap."
  [text prefix max-width]
  (let [prefix-w    (display-width prefix)
        styled-text (convert-inline-markdown text)
        wrapped     (ansi-aware-word-wrap styled-text (- max-width prefix-w))
        indent-str  (apply str (repeat prefix-w \space))]
    (map-indexed (fn [i line]
                   (if (zero? i)
                     (str prefix line)
                     (str indent-str line)))
                 wrapped)))

(defn- render-ul-item [content max-width]
  (let [text (str/replace content #"^[-*+]\s+" "")]
    (render-list-item text "  \u2022 " max-width)))

(defn- render-ol-item [content max-width]
  (let [[_ num text] (re-matches #"^(\d+)\.\s+(.*)" content)
        prefix (str "  " num ". ")]
    (render-list-item text prefix max-width)))

(defn- render-blockquote [content max-width]
  (let [text     (str/replace content #"^>\s?" "")
        bar      (ansi/style "\u2502 " ansi/dim ansi/green)
        bar-w    2
        styled   (convert-inline-markdown text)
        wrapped  (ansi-aware-word-wrap styled (- max-width bar-w))]
    (mapv (fn [line] (str bar line)) wrapped)))

(defn- render-hr [max-width]
  [(ansi/style (apply str (repeat max-width "\u2500")) ansi/dim)])

(defn- render-paragraph [content max-width]
  (ansi-aware-word-wrap (convert-inline-markdown content) max-width))

;; --- Table renderer ---------------------------------------------------

(defn- pad-cell
  "Pad a (possibly ANSI-styled) cell to target display-width respecting
   alignment. Truncates with ellipsis when content is wider than target."
  [styled-cell target-w align]
  (if (<= target-w 0)
    ""
    (let [w (display-width styled-cell)]
      (cond
        (= w target-w) styled-cell
        (< w target-w) (let [diff (- target-w w)]
                         (case align
                           :right  (str (apply str (repeat diff \space)) styled-cell)
                           :center (let [left  (quot diff 2)
                                         right (- diff left)]
                                     (str (apply str (repeat left \space))
                                          styled-cell
                                          (apply str (repeat right \space))))
                           (str styled-cell (apply str (repeat diff \space)))))
        :else (let [cut-idx (char-index-at-width styled-cell (max 0 (dec target-w)))
                    closed  (str (subs styled-cell 0 cut-idx) ansi/reset ansi/ellipsis)
                    new-w   (display-width closed)]
                (if (< new-w target-w)
                  (str closed (apply str (repeat (- target-w new-w) \space)))
                  closed))))))

(defn- compute-column-widths
  "Per-column widths sized to content, shrunk proportionally to fit max-width."
  [styled-header styled-rows max-width]
  (let [n-cols (count styled-header)
        sep-w  3 ;; " │ " between columns
        seps   (* sep-w (max 0 (dec n-cols)))
        raw    (mapv (fn [i]
                       (apply max 1
                              (display-width (nth styled-header i ""))
                              (map #(display-width (nth % i "")) styled-rows)))
                     (range n-cols))
        avail  (max n-cols (- max-width seps))]
    (if (<= (reduce + raw) avail)
      raw
      (let [total  (reduce + raw)
            min-w  3
            scaled (mapv (fn [w]
                           (max min-w
                                (long (Math/floor (* (/ w (double total)) avail)))))
                         raw)]
        (if (<= (reduce + scaled) avail)
          scaled
          (mapv (fn [_] (max 1 (quot avail n-cols))) raw))))))

(defn- render-table
  "Render a parsed GFM table as a vector of ANSI-styled lines.
   `content` is {:header [..] :align [..] :rows [[..] ..]}."
  [{:keys [header align rows]} max-width]
  (let [n-cols     (count header)
        align      (vec (take n-cols (concat align (repeat :left))))
        s-header   (mapv convert-inline-markdown header)
        s-rows     (mapv (fn [r] (mapv convert-inline-markdown r)) rows)
        col-w      (compute-column-widths s-header s-rows max-width)
        cell-sep   (ansi/style (str " " ansi/v-line " ") ansi/dim)
        header-ln  (->> (map-indexed
                         (fn [i cell]
                           (pad-cell (ansi/header cell) (nth col-w i) (nth align i)))
                         s-header)
                        (str/join cell-sep))
        sep-ln     (let [pieces (map (fn [w] (apply str (repeat w ansi/h-line))) col-w)
                         joiner (str ansi/h-line "┼" ansi/h-line)]
                     (ansi/style (str/join joiner pieces) ansi/dim))
        body-lns   (mapv (fn [row]
                           (->> (map-indexed
                                 (fn [i cell]
                                   (pad-cell cell (nth col-w i) (nth align i)))
                                 row)
                                (str/join cell-sep)))
                         s-rows)]
    (into [header-ln sep-ln] body-lns)))

;; --- Top-level renderer -----------------------------------------------

(defn- render-markdown
  "Convert markdown text to ANSI-styled lines, each ≤ max-width display columns."
  [text max-width]
  (let [blocks (parse-markdown-blocks text)]
    (mapcat (fn [{:keys [type content]}]
              (case type
                :code-block (render-code-block content max-width)
                :header     (render-header content max-width)
                :ul-item    (render-ul-item content max-width)
                :ol-item    (render-ol-item content max-width)
                :blockquote (render-blockquote content max-width)
                :hr         (render-hr max-width)
                :table      (render-table content max-width)
                :empty      [""]
                :paragraph  (render-paragraph content max-width)))
            blocks)))

;; ============================================================================
;; Help
;; ============================================================================

(def command-registry
  "Canonical list of all TUI slash commands: [cmd args description completions?].
   Used by format-help and autocomplete. When adding a new command to
   the case dispatch in commands.clj, add it here too. Entries are kept
   alphabetical by command name.
   Optional 4th element is a completion hint map with :completions key
   containing [[value description] ...] pairs for the submenu system."
  [["/activity"         " [show [dir [-p N]]|hide|toggle]" "Toggle activity side pane (Mode B)"
    {:completions [["show"   "Open activity side pane"]
                   ["hide"   "Close activity side pane"]
                   ["toggle" "Toggle activity side pane"]]}]
   ["/agent"            " [status|new|switch|close|trace]" "Manage agents"
    {:completions [["status" "Show current agent status"]
                   ["new"    "Create a new agent by type"]
                   ["switch" "Switch between existing instances"]
                   ["close"  "Close current instance"]
                   ["trace"  "Show BT trace entries (thinking)"]]}]
   ["/allow-path"       " PATH"                   "Whitelist a file path for agent access"]
   ["/capture"          " PATH"                   "Save scrollback buffer to file"]
   ["/clear"            ""                        "Restart the session: clear history, scrollback, and st-memory"]
   ["/compact"          " [ratio]"                "Compact context to ratio of max tokens (default 0.2)"]
   ["/config"           " [key [val]]"            "Show/set runtime config"]
   ["/continue"         " [N]"                    "Resume last answer with N more iterations"]
   ["/display-format"   " [level]"                "Show/set display detail level"
    {:completions [["quiet"   "Minimal output"]
                   ["normal"  "Default output"]
                   ["verbose" "Full BT traces"]]}]
   ["/effort"           " [level]"                "Set effort level"
    {:completions [["low"    "No finalize, no refinements"]
                   ["medium" "Finalize answer, no refinements"]
                   ["high"   "Finalize answer + 2 refinements"]]}]
   ["/help"             ""                        "Show this help"]
   ["/history"          ""                        "Show conversation history"]
   ["/init"             " [prompt|show|reseed|revert|list-snapshots]"
    "Author/maintain BRAINYARD.md (see /init help)"
    {:completions [["show"           "Render BRAINYARD.md (project + user)"]
                   ["list-snapshots" "Show recent BRAINYARD.md snapshots"]
                   ["reseed"         "Re-import from CLAUDE.md / AGENTS.md"]
                   ["revert"         "Restore a snapshot (give the path)"]
                   ["help"           "Show /init usage details"]]}]
   ["/log"              " [show [dir [-p N]]|hide]" "Toggle log tail side pane (Mode B)"
    {:completions [["show"   "Open log side pane"]
                   ["hide"   "Close log side pane"]]}]
   ["/mcp"              " [server [action]]"      "Manage MCP servers"]
   ["/login"            " [provider]"             "Auth-provider status / sign in (e.g. anthropic; not MCP servers)"]
   ["/logout"           " <provider>"             "Sign out of an auth provider (e.g. anthropic)"]
   ["/memory"           " [subcmd] [args]"        "Manage agent long-term memory"
    {:completions [["stats"       "Layer counts + schema version"]
                   ["remember"    "Store a fact (remember <content>)"]
                   ["consolidate" "Promote session memory to episodic"]
                   ["purge"       "Dry-run cleanup (add --apply to commit)"]
                   ["verify"      "Mark a fact verified (verify <fact-id>)"]
                   ["correct"     "Correct a recalled fact (correct <evidence>)"]
                   ["help"        "Show /memory usage details"]]}]
   ["/model"            " [name|#]"               "Show model picker / switch model"]
   ["/pause"            ""                        "Cooperatively pause the active BT run"]
   ["/popup"            " test"                    "Open a smoke-test popup (Mode B)"
    {:completions [["test"   "Open a no-op picker popup"]]}]
   ["/queue"            " [cancel [all|uuid]]"    "Show input queue or cancel items"
    {:completions [["list"   "Show input queue"]
                   ["cancel" "Cancel queued items (all or uuid)"]]}]
   ["/quit"             ""                        "Exit TUI"]
   ["/resume"           ""                        "Unpark a paused BT run on the active agent"]
   ["/sandbox"          " [fn|eval CODE]"         "Run sandbox function or eval code"
    {:completions [["eval" "Eval Clojure code in sandbox"]]}]
   ["/scrollback"       " dump"                    "Dump host pane scrollback to file (Mode B)"
    {:completions [["dump"   "Capture scrollback ANSI to file"]]}]
   ["/session"          " [N|subcmd] [args]"      "Manage TUI tabs + persisted sessions"
    {:completions [["tabs"   "List live runtime tabs"]
                   ["new"    "Create new tab (optional agent-id)"]
                   ["close"  "Close tab N (default: current)"]
                   ["switch" "Switch to live tab N"]
                   ["rename" "Rename current tab (also persists disk label)"]
                   ["list"   "List persisted on-disk sessions"]
                   ["show"   "Show one persisted session's detail (ID)"]
                   ["label"  "Set/clear the active session's disk label"]
                   ["tree"   "Render persisted session tree (parents + forks)"]
                   ["fork"   "Fork the active session on disk (optional label)"]]}]
   ["/status"           ""                        "Show agent status"]
   ["/task"             " [subcmd] [args]"        "Manage background tasks"
    {:completions [["list"   "List all background tasks"]
                   ["detail" "Show task detail (ID)"]
                   ["cancel" "Cancel a running task (ID)"]
                   ["del"    "Remove a task from the list (ID)"]
                   ["log"    "Show task output (ID [N])"]
                   ["run"    "Run bash command as background task (CMD)"]]}]
   ["/todo"             ""                        "Show TODO list"]
   ["/usage"            ""                        "Show token/cost summary + per-call latency"]])

(defn format-help
  "Format the /help command output listing all available TUI commands."
  []
  (let [col    22 ;; visible column width for command+args
        pad    (fn [cmd-str]
                 (let [n (max 1 (- col (count cmd-str)))]
                   (apply str (repeat n " "))))
        keys   [["PgUp / PgDn"            "Scroll output history (fullscreen mode)"]
                ["Shift+\u2190 / Shift+\u2192" "Navigate input prompt history"]
                ["Ctrl-N / Ctrl-P"       "Next / previous session"]
                ["Ctrl-T"                "Create new session"]
                ["Ctrl-W"                "Close current session"]
                ["Ctrl-O"                "Toggle TODO list expand/collapse"]]]
    (str (ansi/header "Commands") "\n"
         (->> command-registry
              (map (fn [[cmd args desc]]
                     (let [visible (str cmd args)]
                       (str "  " (ansi/style cmd ansi/bold ansi/bright-cyan) args
                            (pad visible) desc))))
              (str/join "\n"))
         "\n\n" (ansi/header "Keys") "\n"
         (->> keys
              (map (fn [[key-name desc]]
                     (str "  " (ansi/style key-name ansi/bold ansi/bright-cyan)
                          (pad key-name) desc)))
              (str/join "\n")))))

;; ============================================================================
;; Iteration
;; ============================================================================

(defn format-iteration-header
  "Format iteration header line.
   Returns: '[+] Iteration 2 / 5'"
  [iteration-count max-iterations]
  (let [label (if max-iterations
                (str "Iteration " iteration-count " / " max-iterations)
                (str "Iteration " iteration-count))]
    (ansi/style (str "[+] " label) ansi/bold ansi/bright-white)))

(defn format-iteration-exhausted
  "Format a warning when the agent hit the iteration limit."
  [iteration-count max-iterations]
  (let [msg (str "Reached iteration limit ("
                 (or iteration-count "?") "/" (or max-iterations "?")
                 "). Type /continue [N] to resume with more iterations.")]
    (str "\n" (ansi/warning msg))))

(defn format-recovery-status
  "Muted one-line progress notice for the CoAct loop working through a
   transient stall (`:agent.recovery/retrying`). `kind` ∈
   #{:empty-result :malformed-output :validation-failure :plain-text-output
     :provider-error :no-action}; `attempt`/`max` describe progress (`max` may be
   nil). For
   `:provider-error`, `reason` is the classifier's cause (e.g. \"provider error
   (HTTP 503)\") and replaces the generic label so the line is accurate rather
   than a blanket \"malformed output\"."
  ([kind attempt max] (format-recovery-status kind attempt max nil))
  ([kind attempt max reason]
   (let [progress (cond
                    max                        (str " (" attempt "/" max ")")
                    (and attempt (> attempt 1)) (str " (x" attempt ")")
                    :else                      "")
         reason*  (when (and reason (not (str/blank? reason)))
                    (let [r (str/trim reason)] (subs r 0 (min (count r) 80))))
         msg (case kind
               :empty-result       (str "Model returned an empty response — retrying" progress "…")
               :malformed-output   (str "Malformed model output — re-prompting" progress "…")
               :validation-failure (str "Model output didn't match the schema — re-prompting" progress "…")
               :plain-text-output  (str "Model replied in plain text — kept as thought, re-prompting for JSON" progress "…")
               :provider-error     (str (or reason* "Provider/network error") " — retrying" progress "…")
               :no-action          (str "No action this turn (no tool, code, or answer) — nudging the model" progress "…")
               (str "Recovering" progress "…"))]
     (ansi/muted (str "  " ansi/v-line " ⟳ " msg)))))

;; ============================================================================
;; Thought / Reasoning
;; ============================================================================

(def ^:private right-pad 1)

(defn- format-thought-segment
  "Format a single segment of thought text — either a code block or prose."
  [segment code-indent cols]
  (let [code-block-re #"```(?:\w+)?\n([\s\S]*?)```"]
    (if-let [[_ code] (re-find code-block-re segment)]
      ;; Code block: render with muted style, no word-wrapping, indented
      (let [lines (str/split-lines (str/trim code))
            max-w (- cols (count code-indent) 2)]
        (str code-indent (ansi/muted "┌─")
             "\n"
             (->> lines
                  (map (fn [line]
                         (str code-indent (ansi/muted "│ ")
                              (ansi/thought (truncate line max-w)))))
                  (str/join "\n"))
             "\n"
             code-indent (ansi/muted "└─")))
      ;; Prose: render markdown with thought styling for plain paragraphs
      (let [prefix-w (count code-indent)
            lines (render-markdown segment (- cols prefix-w))]
        (->> lines
             (map #(str code-indent %))
             (str/join "\n"))))))

(defn format-thought
  "Format agent reasoning/thought text.
   Detects ```clojure code blocks and renders them with box-drawing.
   Options: :max-len (default 2000), :label (default \"Thinking\")"
  [thought-str & {:keys [max-len label] :or {max-len 2000 label "Thinking"}}]
  (when (and thought-str (not (str/blank? thought-str)))
    (let [text     (truncate (str/trim thought-str) max-len)
          cols     (- (terminal-columns) right-pad)
          left     "  "
          prefix   (str left (ansi/muted (str ansi/bullet " " label ":")))
          indent   "    "  ;; 4 spaces for continuation
          ;; Split into code blocks and prose segments
          parts    (str/split text #"(?=```)|(?<=```)")
          ;; Re-assemble into segments: code blocks (```...```) and prose
          segments (loop [remaining parts
                          acc []
                          in-code? false]
                     (if (empty? remaining)
                       acc
                       (let [part (first remaining)]
                         (cond
                           ;; Start of code block
                           (and (not in-code?) (str/starts-with? part "```"))
                           (recur (rest remaining) (conj acc part) true)
                           ;; End of code block
                           (and in-code? (= part "```"))
                           (let [prev (peek acc)]
                             (recur (rest remaining)
                                    (conj (pop acc) (str prev "```"))
                                    false))
                           ;; Accumulate
                           :else
                           (recur (rest remaining)
                                  (if in-code?
                                    (conj (pop acc) (str (peek acc) part))
                                    (let [trimmed (str/trim part)]
                                      (if (str/blank? trimmed)
                                        acc
                                        (conj acc trimmed))))
                                  in-code?)))))]
      (str prefix "\n"
           (->> segments
                (map #(format-thought-segment % indent cols))
                (str/join "\n"))))))

;; ============================================================================
;; Tool Calls
;; ============================================================================

(defn format-tool-calls
  "Format tool call entries with word wrapping.
   Input: [{:tool-name :tool-args}]
   Returns multi-line string with arrow-prefixed calls."
  [tool-calls]
  (when (seq tool-calls)
    (let [cols     (- (terminal-columns) right-pad)
          prefix-w 4  ;; display width of "  → "
          indent   (apply str (repeat prefix-w \space))]
      (->> tool-calls
           (map (fn [{:keys [tool-name tool-args]}]
                  (let [name-str (ansi/tool-name (str tool-name))
                        args-str (when (seq tool-args)
                                   (let [pairs (if (sequential? tool-args)
                                                 (->> tool-args
                                                      (map (fn [a]
                                                             (str (:name a) "="
                                                                  (pr-str (:value a))))))
                                                 (->> tool-args
                                                      (map (fn [[k v]]
                                                             (str (name k) "="
                                                                  (pr-str v))))))]
                                     (str "(" (str/join ", " pairs) ")")))]
                    (->> (ansi-aware-word-wrap (str name-str (or args-str "()"))
                                               (- cols prefix-w))
                         (map-indexed (fn [i line]
                                        (if (zero? i)
                                          (str "  " (ansi/style ansi/arrow ansi/cyan) " " line)
                                          (str indent line))))
                         (str/join "\n")))))
           (str/join "\n")))))

;; ============================================================================
;; Tool Results
;; ============================================================================

(defn format-tool-results
  "Format tool result entries with word wrapping.
   Input: [{:tool-name :tool-result}]
   Options: :max-len (default 300)"
  [tool-results & {:keys [max-len] :or {max-len 300}}]
  (when (seq tool-results)
    (let [cols     (- (terminal-columns) right-pad)
          prefix-w 4  ;; display width of "  ← "
          indent   (apply str (repeat prefix-w \space))]
      (->> tool-results
           (map (fn [{:keys [tool-name tool-result]}]
                  (let [name-str   (ansi/tool-name (str tool-name))
                        result-str (truncate (str tool-result) max-len)
                        content    (str name-str ": " (ansi/muted result-str))]
                    (->> (ansi-aware-word-wrap content (- cols prefix-w))
                         (map-indexed (fn [i line]
                                        (if (zero? i)
                                          (str "  " (ansi/style ansi/left-arrow ansi/green) " " line)
                                          (str indent line))))
                         (str/join "\n")))))
           (str/join "\n")))))

;; ============================================================================
;; TODO List
;; ============================================================================

(defn format-todo-progress
  "Single-line compact TODO progress for scrollback.
   Returns: '  TODO [2/5] ✓✓✗✗✗'"
  [todo-list]
  (when (seq todo-list)
    (let [total (count todo-list)
          done  (count (filter :done todo-list))
          marks (->> todo-list
                     (map #(if (:done %) ansi/check ansi/cross-mark))
                     (apply str))]
      (ansi/muted (str "  TODO [" done "/" total "] " marks)))))

(defn format-todo-list
  "Format TODO list with progress.
   Input: [{:id :description :done :result :independent}]"
  [todo-list]
  (when (seq todo-list)
    (let [total    (count todo-list)
          done     (count (filter :done todo-list))
          progress (str done "/" total)]
      (str (ansi/header (str "  TODO [" progress "]")) "\n"
           (->> todo-list
                (map-indexed
                 (fn [_idx {:keys [description done result independent]}]
                   (let [check-str (if done
                                     (ansi/success (str "[" ansi/check "]"))
                                     (ansi/muted (str "[" ansi/cross-mark "]")))
                         ind-str   (when independent
                                     (ansi/muted " (parallel)"))
                         desc-str  (if done
                                     (ansi/muted description)
                                     description)
                         result-str (when (and done result (not (str/blank? (str result))))
                                      (str "\n      " (ansi/muted (truncate (str result) 200))))]
                     (str "    " check-str " " desc-str ind-str result-str))))
                (str/join "\n"))))))

;; ============================================================================
;; Observation
;; ============================================================================

(defn format-observation
  "Format observation text as REPL output with box-drawing.
   Options: :max-len (default 1000)"
  [observation-str & {:keys [max-len] :or {max-len 1000}}]
  (when (and observation-str (not (str/blank? observation-str)))
    (let [text     (truncate (str/trim observation-str) max-len)
          cols     (- (terminal-columns) right-pad)
          left     "  "
          prefix   (str left (ansi/muted (str ansi/bullet " Observation:")))
          indent   "    "
          max-w    (- cols (count indent) 2)
          ;; Check if it starts with Error:
          is-error? (str/starts-with? text "Error:")
          body-lines (if is-error?
                       ;; Errors: flat red styling per line, no markdown
                       (->> (str/split-lines text)
                            (map (fn [line]
                                   (ansi/failure (truncate line max-w)))))
                       ;; Normal: render markdown inside the box
                       (render-markdown text max-w))]
      (str prefix "\n"
           indent (ansi/muted "┌─") "\n"
           (->> body-lines
                (map (fn [line]
                       (str indent (ansi/muted "│ ") line)))
                (str/join "\n"))
           "\n"
           indent (ansi/muted "└─")))))

;; ============================================================================
;; Eval Display (multi-section iteration output)
;; ============================================================================

(defn- eval-block-fn
  "Pick the display-block factory for a section class string. The four
   accepted values match the ones produced by `format-eval-section`'s
   own callers."
  [class]
  (case class
    "eval-code"   block/eval-code-block
    "eval-result" block/eval-result-block
    "eval-output" block/eval-output-block
    "eval-error"  block/eval-error-block))

(defn- format-eval-section
  "Format a single labeled section with box-drawing.
   style-fn applies to body lines (e.g. ansi/thought for code, ansi/failure for errors).
   Options:
     :class     — enable display-block collapsible truncation; when the body
                  exceeds `:max-collapsed-lines` (config), the full content is
                  saved via display-block (file-backed) and the body shows the
                  first N lines plus a `[*Block:<id>* collapsed: …]` marker.
                  Must be one of \"eval-code\" / \"eval-result\" /
                  \"eval-output\" / \"eval-error\"; dispatches to the
                  matching `eval-*-block` factory in display-block.
     :id        — caller-supplied display-block id (alphanumeric lowercase).
                  When the same id is reused on a subsequent re-render with
                  identical content, the existing provider is overwritten
                  rather than duplicated — required for live re-rendered
                  surfaces. Omit for one-shot emits (random id is generated)."
  [label body-str style-fn & {:keys [class id]}]
  (when (and body-str (not (str/blank? body-str)))
    (let [cols     (- (terminal-columns) right-pad)
          indent   "    "
          max-w    (- cols (count indent) 2)
          ;; Wrap each body line into one or more box-prefixed visual rows.
          ;; Long lines are word-wrapped at `max-w` display columns
          ;; (ANSI-aware) so trailing content never falls off the right edge.
          ;; Display-block marker lines bypass wrapping so the closing `]`
          ;; survives for `block/marker-re` to match. `decorate-one` is the
          ;; per-row decorator passed to the display-block provider as
          ;; `:line-decorator` — the tail lines spliced in on expand stay
          ;; visually consistent with the head already in scrollback.
          decorate-one (fn [line]
                         (str indent (ansi/muted "│ ") (style-fn line)))
          decorate (fn [line]
                     (if (re-find block/marker-re line)
                       [(decorate-one line)]
                       (mapv decorate-one (ansi-aware-word-wrap line max-w))))
          ;; Trim only surrounding BLANK LINES — never the first content
          ;; line's leading indentation. A whole-string `str/trim` strips the
          ;; leading whitespace of the FIRST line only (e.g. `cat -n`'s left
          ;; padding), shifting that one row left relative to the rest. Tabs
          ;; are deliberately preserved (tab-stops are valid for text
          ;; rendering; the terminal expands them per visual row).
          trimmed  (-> body-str
                       (str/replace #"\A(?:[ \t]*\r?\n)+" "")  ; leading blank lines
                       str/trimr)                              ; trailing whitespace / blank lines
          text     ((eval-block-fn class) trimmed
                                          (cond-> {:max-collapsed-lines (config/get-config :max-collapsed-lines)
                                                   :max-expanded-lines  (config/get-config :max-expanded-lines)
                                                   :label               label
                                                   :line-decorator      decorate-one}
                                            id (assoc :id id)))
          lines    (str/split-lines text)]
      (str "  " (ansi/muted (str ansi/bullet " " label ":")) "\n"
           indent (ansi/muted "┌─") "\n"
           (->> lines (mapcat decorate) (str/join "\n"))
           "\n"
           indent (ansi/muted "└─")))))

(defn- ^:private section-id
  "Build a stable display-block id from id-prefix + optional entry index
   + section tag. Returns nil when id-prefix is nil so the underlying
   factory generates a random id."
  [id-prefix idx tag]
  (when id-prefix
    (str id-prefix (when idx idx) tag)))

(def ^:private eval-section-id-tag
  "Letter tags appended to id-prefix to make a stable per-section id.
   Kept short so ids stay compact (`<prefix><idx?><tag>`)."
  {:code "code" :result "res" :output "out" :error "err"})

(defn format-eval-sections
  "Render eval-display content as a flat vector of ANSI-styled lines.

   Each entry of `eval-display` is `{:code :result :output :error}`
   (any subset). Each populated section turns into a boxed segment
   backed by a display-block provider — long content collapses with a
   `[*Block:<id>* collapsed: …]` marker. Multi-entry input gets [N]
   suffixes (Code[1], Result[2], …) and entries are separated by a
   blank line.

   Side effect: registers display-block providers for over-threshold
   bodies. With `:id-prefix`, those providers are idempotent across
   re-renders (live surfaces); without it, each call mints fresh ids
   (one-shot emits).

   Options:
     :include    set of #{:code :result :output :error}
                 (default: all four). Sub-agent emits use this to render
                 only :code (pre-eval) or only :result/:output/:error
                 (post-eval) groups.
     :id-prefix  string in [a-z0-9]+. When supplied, each registered
                 provider gets a stable id derived from the prefix +
                 entry index + section tag, so re-rendering the same
                 content overwrites the existing provider rather than
                 duplicating it. Required for live re-rendered surfaces
                 (e.g. iteration live-block); omit for one-shot emits.

   Returns []. when there is nothing to render."
  [eval-display & {:keys [include id-prefix]
                   :or {include #{:code :result :output :error}}}]
  (if (empty? eval-display)
    []
    (let [multi?  (> (count eval-display) 1)
          per-entry
          (fn [idx {:keys [code result output error]}]
            (let [sfx       (if multi? (str "[" (inc idx) "]") "")
                  entry-idx (when (and id-prefix multi?) idx)
                  sec       (fn [kind label body style]
                              (format-eval-section
                               label body style
                               :class (str "eval-" (name kind))
                               :id (section-id id-prefix entry-idx
                                               (eval-section-id-tag kind))))]
              (->> (cond-> []
                     (and (contains? include :code) code)
                     (conj (sec :code   (str "Code"   sfx) code   ansi/thought))
                     (and (contains? include :result) result (not= result "nil"))
                     (conj (sec :result (str "Result" sfx) result identity))
                     (and (contains? include :output) output
                          (not (str/blank? (str/trim (or output "")))))
                     (conj (sec :output (str "Output" sfx) output identity))
                     (and (contains? include :error) error
                          (not (str/blank? (str/trim (or error "")))))
                     (conj (sec :error  (str "Error"  sfx) error  ansi/failure)))
                   (remove nil?)
                   (str/join "\n"))))
          joined (->> eval-display
                      (map-indexed per-entry)
                      (remove str/blank?)
                      (str/join "\n\n"))]
      (if (str/blank? joined)
        []
        (vec (str/split-lines joined))))))

(defn format-tool-call-block
  "Render a tool call's args as a boxed, display-block-backed section —
   the same visual + collapsible treatment as the `Code` eval section.

   Used when the inline `→ name(args)` one-liner would overflow the pane
   (e.g. a tool whose arg is a multi-line bash script), so the script stays
   readable as code and long bodies collapse with an expand marker instead
   of being `pr-str`-truncated.

   `body-str` is the already-stringified args; the caller is expected to
   have styled it (e.g. arg names dim, values in the code color), so the
   default `:style-fn` is `identity` — the body's own ANSI is preserved
   rather than re-wrapped.

   Options:
     :label    header label (default \"Call\").
     :id       stable display-block id so live re-renders (called→done→error)
               overwrite the same provider rather than leaking a new one each
               tick. Omit for one-shot emits.
     :style-fn per-line decorator (default `identity`). Pass a styling fn to
               colorize an unstyled body uniformly instead.

   Returns a flat vector of ANSI-styled lines (`[]` when body is blank)."
  [body-str & {:keys [label id style-fn] :or {label "Call" style-fn identity}}]
  (if-let [s (format-eval-section label body-str style-fn
                                  :class "eval-code" :id id)]
    (vec (str/split-lines s))
    []))

(defn format-tool-result-block
  "Render a tool call's result as a boxed, display-block-backed `Result`
   section — the same visual + collapsible treatment as the code-eval
   `Result` section. Long bodies collapse with a `[*Block:<id>* collapsed:
   …]` marker.

   Renders a SUCCESSFUL result in a neutral box; failures go through
   `format-tool-error-block` (a red `Error` box) instead.

   Options:
     :id  stable display-block id so live re-renders (called→done) overwrite
          the same provider rather than leaking a new one each tick. Omit for
          one-shot emits.

   Returns a flat vector of ANSI-styled lines (`[]` when body is blank)."
  [body-str & {:keys [id]}]
  (if-let [s (format-eval-section "Result" body-str identity
                                  :class "eval-result" :id id)]
    (vec (str/split-lines s))
    []))

(defn format-tool-error-block
  "Render a FAILED tool call's result as a boxed, display-block-backed `Error`
   section — the red-styled counterpart to `format-tool-result-block`. Used
   when a tool threw (its result carries `:error-message`) or returned an
   error map (`:error`), so the failure stands out from a normal Result box
   rather than being folded into a neutral one.

   Same `:id` semantics as `format-tool-result-block` (stable id so live
   re-renders overwrite the same provider). Returns a flat vector of
   ANSI-styled lines (`[]` when body is blank)."
  [body-str & {:keys [id]}]
  (if-let [s (format-eval-section "Error" body-str ansi/failure
                                  :class "eval-error" :id id)]
    (vec (str/split-lines s))
    []))

;; ============================================================================
;; Goal Status
;; ============================================================================

(defn format-goal-status
  "Format the goal-achieved verdict line. (The separate goal-reasoning output was
   removed; the verdict now stands on its own.)"
  [goal-achieved]
  (let [status-str (if goal-achieved
                     (ansi/success (str ansi/check " Goal achieved"))
                     (ansi/failure (str ansi/cross-mark " Goal not yet achieved")))]
    (str "  " status-str)))

(defn format-next-prompt
  "Format a suggested one-line follow-up prompt for the user (from
   FinalizeAnswer's :next-user-prompt output). Returns nil when blank."
  [next-prompt]
  (when (and next-prompt (not (str/blank? next-prompt)))
    (str "  " (ansi/muted (str "↳ Try next: "
                               (truncate (str/trim next-prompt) 200))))))

(defn format-eval-verdict
  "Render an answer-evaluation verdict line (`:agent.evaluation/done`): an icon
   plus label, colored by outcome. The `:detail` explanation is
   whitespace-collapsed to a single line and truncated WITH an ellipsis (via
   `truncate`) so a long HALLUCINATED/INCOMPLETE explanation can't break the
   one-line status or look abruptly cut. Returns the styled string (no
   leading indent)."
  [verdict detail]
  (let [detail*  (some-> detail str (str/replace #"\s+" " ") str/trim)
        has?     (and detail* (not (str/blank? detail*)))
        tail     (when has? (str " — " (truncate detail* 200)))
        icon     (case verdict
                   (:complete :accepted)       ansi/check
                   (:hallucinated :incomplete) ansi/cross-mark
                   "?")
        color-fn (case verdict
                   (:complete :accepted)       ansi/success
                   (:hallucinated :incomplete) ansi/failure
                   ansi/muted)
        label    (case verdict
                   :complete     "PASS — answer verified"
                   :accepted     (str "ACCEPTED" tail)
                   :hallucinated (str "HALLUCINATED" tail)
                   :incomplete   (str "INCOMPLETE" tail)
                   (str "UNKNOWN" (or tail (str ": " detail))))]
    (color-fn (str icon " " label))))

;; ============================================================================
;; Final Answer
;; ============================================================================

(defn- expand-tabs
  "Expand TAB characters to spaces on a tab stop of 4, per line. Applied to the
   raw (pre-ANSI) answer text so the fixed-width box layout measures what the
   terminal actually renders: a literal TAB advances to the next tab stop in the
   terminal, but `display-width` counts it as a single column — which desyncs
   right-bordered boxes. The classic trigger is the tab-indented file list from
   long-form `git status`. Fast-paths to the input when there is no TAB."
  [^String s]
  (if (neg? (.indexOf s (int \tab)))
    s
    (let [tab-stop 4]
      (str/join "\n"
                (for [line (str/split s #"\n" -1)]
                  (let [sb (StringBuilder.)]
                    (reduce (fn [col ch]
                              (if (= ch \tab)
                                (let [n (- tab-stop (mod col tab-stop))]
                                  (dotimes [_ n] (.append sb \space))
                                  (+ col n))
                                (do (.append sb ch) (inc col))))
                            0 line)
                    (.toString sb)))))))

(defn format-answer
  "Format the final answer with a highlighted box.
   Markdown is converted to ANSI styling. Border chars are bright-green.
   Accounts for CJK double-width characters in padding.

   1-arity uses the cached terminal width; 2-arity accepts an explicit
   pane width (used by the tmux daemon, whose host terminal differs from
   the pane that displays the answer)."
  ([answer-str] (format-answer answer-str (terminal-columns)))
  ([answer-str cols]
   (when (and answer-str (not (str/blank? answer-str)))
     (let [wrap-w   (-> cols (- 4) (max 40))
           ;; Expand TABs to spaces before layout so the box's width/padding math
           ;; (display-width-based) matches what the terminal renders — a raw TAB
           ;; would otherwise advance to a tab stop and shove the right border out
           ;; (e.g. the tab-indented file list from long-form `git status`).
           lines    (render-markdown (expand-tabs (str/trim answer-str)) wrap-w)
           ;; Fit-to-content: box width = longest rendered line, floored at 40
           ;; and capped at the pane width. Short answers no longer stretch
           ;; edge-to-edge on a wide pane.
           box-w    (-> (transduce (map display-width) max 0 lines)
                        (max 40)
                        (min wrap-w))
           bar      (apply str (repeat (+ box-w 2) ansi/h-line))
           top      (str ansi/tl-corner bar ansi/tr-corner)
           bottom   (str ansi/bl-corner bar ansi/br-corner)
           gb       (fn [s] (ansi/style s ansi/bright-green))
           pad-line (fn [line]
                      (let [padding (apply str (repeat (max 0 (- box-w (display-width line))) " "))]
                        (str (gb ansi/v-line) " " line padding " " (gb ansi/v-line))))]
       (str "\n"
            (gb top) "\n"
            (->> lines
                 (map pad-line)
                 (str/join "\n")) "\n"
            (gb bottom))))))

(defn format-answer-plain
  "Render the final answer as ANSI (markdown → styling) WITHOUT the highlighted
   box — the bare content lines. Used in :quiet display-format. Mirrors
   `format-answer`'s content rendering (tab-expansion, markdown, identical wrap
   width) so the text wraps the same way; it just drops the border.

   1-arity uses the cached terminal width; 2-arity accepts an explicit pane
   width (parity with `format-answer`)."
  ([answer-str] (format-answer-plain answer-str (terminal-columns)))
  ([answer-str cols]
   (when (and answer-str (not (str/blank? answer-str)))
     (let [wrap-w (-> cols (- 4) (max 40))
           lines  (render-markdown (expand-tabs (str/trim answer-str)) wrap-w)]
       ;; Indent 2 spaces so the answer aligns with the goal-status / next-prompt
       ;; lines (`format-goal-status` / `format-next-prompt` both indent by 2).
       (str "\n" (->> lines
                      (map #(if (str/blank? %) % (str "  " %)))
                      (str/join "\n")))))))

;; ============================================================================
;; Usage Summary
;; ============================================================================

(defn format-usage-summary
  "Format LLM usage summary.
   Accepts either:
   - Flat map: {:total-calls :total-tokens :total-cost}
   - Nested map: {:totals {:call-count :total-tokens :total-cost} :by-model {...}}"
  [usage]
  (when usage
    (let [totals (or (:totals usage) usage)
          calls  (or (:call-count totals) (:total-calls totals) 0)
          tokens (or (:total-tokens totals)
                     (+ (or (:input-tokens totals) (:total-input-tokens totals) 0)
                        (or (:output-tokens totals) (:total-output-tokens totals) 0)))
          cost   (or (:total-cost totals) 0)]
      (str (ansi/muted (str calls " calls " ansi/v-line " "
                            (format-number tokens) " tokens " ansi/v-line " "
                            "$" (format "%.4f" (double cost))))))))

;; ============================================================================
;; Unified Usage Table (perf + token attribution, with optional breakdown)
;; ============================================================================

(defn- non-cached-input-tokens
  "Tokens billed at the full input rate (NOT served from cache, NOT written
   to cache). Uniform across providers: :input-tokens represents the total
   processed input (including cache categories), so we subtract both
   :cache :read-tokens and :cache :write-tokens to isolate the fresh portion."
  [c]
  (let [base    (or (:input-tokens c) 0)
        cache-r (get-in c [:cache :read-tokens] 0)
        cache-w (get-in c [:cache :write-tokens] 0)]
    (max 0 (- base cache-r cache-w))))

(defn- compute-attrib-rows
  "Per-call attribution data drawn from `:input-token-breakdown`. Always
   computed; only displayed when `:breakdown?` is on."
  [calls]
  (mapv
   (fn [c]
     (let [bd (:input-token-breakdown c)
           sys-tok  (get-in bd [:system-prompt  :estimated-tokens] 0)
           sig-tok  (get-in bd [:dspy-signature :estimated-tokens] 0)
           user-tok (get-in bd [:user-message   :estimated-tokens] 0)
           ctx-tok  (get-in bd [:system-context :estimated-tokens] 0)]
       {:sys sys-tok :sig sig-tok :user user-tok
        :ctx ctx-tok :total-in (+ sys-tok sig-tok user-tok ctx-tok)}))
   calls))

(defn- group-calls-by-agent
  "Partition history by `:agent-instance-id`, preserving first-seen order so
   the main agent (whose first call is the oldest) renders first.

   Returns a vector of `[label calls]`. When no entries carry an
   `:agent-instance-id`, returns a single `[nil calls]` pair so callers can
   treat the ungrouped case uniformly."
  [calls]
  (let [order (->> calls (map :agent-instance-id) distinct vec)
        groups (group-by :agent-instance-id calls)]
    (mapv (fn [k] [k (vec (groups k))]) order)))

(defn format-usage-table
  "Render a per-call table combining latency, token, cache, and cost
   information for an LLM usage history.

   Options:
     :breakdown?  when true, append per-call prompt attribution columns
                  (System / Sig / UserMsg / SysCtx / TotalIn) and the
                  System/User Prompt Parts sections drawn from the
                  most-recent call's :input-token-breakdown.

   History is expected chronological (oldest first) — `collect-all-usage`
   in the TUI base sorts by :timestamp ascending before calling.

   Rendering modes:
   - If every entry shares the same (or absent) `:agent-instance-id`,
     renders a single table — #1 is the oldest call, #N the latest.
   - Otherwise, partitions by `:agent-instance-id` (preserving first-seen
     order so the main agent comes first) and renders one sub-table per
     agent under a `── <label> · N calls · Σ tok · $Σ ──` header. Per-group
     `#` columns restart at 1; the aggregate Usage header, cache-hit, and
     breakdown sections are computed across all calls."
  [history & [{:keys [breakdown?]}]]
  (when (seq history)
    (let [calls         (vec history)
          n             (count calls)
          latencies     (keep :latency-ms calls)
          total-ms      (reduce + 0 latencies)
          total-s       (/ total-ms 1000.0)
          avg-ms        (if (pos? (count latencies))
                          (/ total-ms (double (count latencies)))
                          0.0)
          cache-hits    (->> calls
                             (filter #(pos? (get-in % [:cache :read-tokens] 0)))
                             count)
          avg-in        (if (pos? n)
                          (/ (reduce + 0 (map non-cached-input-tokens calls))
                             (double n))
                          0.0)
          avg-out       (if (pos? n)
                          (/ (reduce + 0 (map #(or (:output-tokens %) 0) calls))
                             (double n))
                          0.0)
          fmt-ms        (fn [ms] (if ms (str (format-number ms) "ms") "-"))
          fmt-cost      (fn [c] (let [t (get-in c [:cost :total-cost] 0)]
                                  (if (pos? t) (str "$" (format "%.4f" (double t))) "-")))
          all-attrib    (compute-attrib-rows calls)
          any-attrib?   (boolean (some #(pos? (:total-in %)) all-attrib))
          show-attrib?  (and breakdown? any-attrib?)
          show-ctx?     (and show-attrib? (some #(pos? (:ctx %)) all-attrib))
          groups        (group-calls-by-agent calls)
          ;; A "real" group is one whose key is non-nil. When ≥2 real groups,
          ;; switch to sub-table rendering and let group headers carry the
          ;; agent label instead of a per-row column.
          multi-group?  (> (count (filter (comp some? first) groups)) 1)
          ;; Single-group mode keeps the legacy per-row Agent column only
          ;; when an id is present (preserving behavior pre-grouping).
          show-agent?   (and (not multi-group?)
                             (boolean (some :agent-instance-id calls)))
          ;; Column layout: always-on numeric block + (optional) breakdown
          ;; columns + (optional) agent + Model (last, no truncation so the
          ;; full id always survives — overflow doesn't break alignment).
          base-header   (str "  "
                             (ansi/style (format "%3s" "#")       ansi/dim) "  "
                             (ansi/style (format "%4s" "Turn")    ansi/dim) "  "
                             (ansi/style (format "%4s" "Iter")    ansi/dim) "  "
                             (ansi/style (format "%9s" "Latency") ansi/dim) "  "
                             (ansi/style (format "%6s" "In")      ansi/dim) "  "
                             (ansi/style (format "%6s" "CacheR")  ansi/dim) "  "
                             (ansi/style (format "%6s" "CacheW")  ansi/dim) "  "
                             (ansi/style (format "%6s" "Out")     ansi/dim) "  "
                             (ansi/style (format "%9s" "Cost")    ansi/dim))
          attrib-header (when show-attrib?
                          (str "  "
                               (ansi/style (format "%7s" "System")  ansi/dim) "  "
                               (ansi/style (format "%6s" "Sig")     ansi/dim) "  "
                               (ansi/style (format "%7s" "UserMsg") ansi/dim) "  "
                               (when show-ctx?
                                 (str (ansi/style (format "%6s" "SysCtx") ansi/dim) "  "))
                               (ansi/style (format "%7s" "TotalIn") ansi/dim)))
          agent-header  (when show-agent?
                          (str "  " (ansi/style (format "%-20s" "Agent") ansi/dim)))
          model-header  (str "  " (ansi/style "Model" ansi/dim))
          header-line   (str base-header attrib-header agent-header model-header)
          fmt-row       (fn [i c attrib]
                          (let [latency   (:latency-ms c)
                                in-tok    (non-cached-input-tokens c)
                                out-tok   (or (:output-tokens c) 0)
                                cache-r   (get-in c [:cache :read-tokens] 0)
                                cache-w   (get-in c [:cache :write-tokens] 0)
                                turn-id   (or (:turn-id c) "-")
                                iter      (or (:iteration c) "-")
                                provider  (:provider c)
                                model     (:model c)
                                model-str (if (and provider model)
                                            (str (name provider) ":" model)
                                            (or model "?"))
                                agent-id  (:agent-instance-id c)
                                agent-str (when agent-id (name agent-id))
                                base (str "  " (format "%3d" (inc i)) "  "
                                          (format "%4s" (str turn-id)) "  "
                                          (format "%4s" (str iter)) "  "
                                          (format "%9s" (fmt-ms latency)) "  "
                                          (format "%6s" (format-number in-tok)) "  "
                                          (format "%6s" (format-number cache-r)) "  "
                                          (format "%6s" (format-number cache-w)) "  "
                                          (format "%6s" (format-number out-tok)) "  "
                                          (format "%9s" (fmt-cost c)))
                                attrib-cols (when show-attrib?
                                              (str "  "
                                                   (format "%7s" (format-number (:sys attrib))) "  "
                                                   (format "%6s" (format-number (:sig attrib))) "  "
                                                   (format "%7s" (format-number (:user attrib))) "  "
                                                   (when show-ctx?
                                                     (str (format "%6s" (format-number (:ctx attrib))) "  "))
                                                   (format "%7s" (format-number (:total-in attrib)))))
                                agent-col   (when show-agent?
                                              (str "  " (format "%-20s" (or agent-str "-"))))
                                model-col   (str "  " model-str)]
                            (str base attrib-cols agent-col model-col)))
          render-group  (fn [[label group-calls]]
                          (let [group-attrib (compute-attrib-rows group-calls)
                                rows (map-indexed
                                      (fn [i c] (fmt-row i c (nth group-attrib i)))
                                      group-calls)
                                gn (count group-calls)
                                g-tokens (reduce + 0
                                                 (map #(+ (or (:input-tokens %) 0)
                                                          (or (:output-tokens %) 0))
                                                      group-calls))
                                g-cost (reduce + 0.0
                                               (map #(double (get-in % [:cost :total-cost] 0))
                                                    group-calls))
                                group-header (when (and multi-group? label)
                                               (str "  "
                                                    (ansi/muted "── ")
                                                    (ansi/style (name label) ansi/bold)
                                                    (ansi/muted (str " · " gn " calls · "
                                                                     (format-number g-tokens) " tok · "
                                                                     "$" (format "%.4f" g-cost)
                                                                     " ──"))))]
                            (str (when group-header (str group-header "\n\n"))
                                 header-line "\n"
                                 (str/join "\n" rows))))
          group-blocks  (map render-group groups)
          body          (str/join "\n\n" group-blocks)
          ;; Prompt-parts sections, drawn from the latest call (chronological last).
          latest        (last calls)
          latest-bd     (:input-token-breakdown latest)
          fmt-parts     (fn [group-key label]
                          (when-let [parts (get-in latest-bd [group-key :parts])]
                            (let [total  (get-in latest-bd [group-key :estimated-tokens] 1)
                                  sorted (sort-by (comp - :estimated-tokens val) parts)]
                              (str "\n  " (ansi/style label ansi/dim) "\n"
                                   (str/join "\n"
                                             (map (fn [[k {:keys [estimated-tokens]}]]
                                                    (let [pct (if (pos? total)
                                                                (int (* 100.0 (/ estimated-tokens (double total))))
                                                                0)]
                                                      (str "    " (format "%-24s" (name k))
                                                           (format "%6s" (format-number estimated-tokens)) " tok"
                                                           " (" (if (< pct 1) "<1" (str pct)) "%)")))
                                                  sorted))))))
          user-toks     (filterv pos? (map :user all-attrib))
          growth        (when (and show-attrib? (> (count user-toks) 1))
                          (let [first-t (first user-toks)
                                last-t  (last user-toks)
                                pct (if (pos? first-t)
                                      (int (* 100.0 (/ (- last-t first-t) (double first-t))))
                                      0)]
                            (str "\n\n  " (ansi/style "Growth:" ansi/dim) " User message "
                                 (format-number first-t) " → " (format-number last-t)
                                 " tokens (" (if (pos? pct) "+" "") pct "%) over "
                                 (count user-toks) " calls")))
          breakdown-tail (when show-attrib?
                           (str (fmt-parts :system-prompt "System Prompt Parts (latest):")
                                (fmt-parts :user-message  "User Message Parts (latest):")
                                growth))]
      (str (ansi/header (str "Usage (" n " calls, "
                             (format "%.1f" total-s) "s total, avg "
                             (format "%.1f" (/ avg-ms 1000.0)) "s/call)"))
           "\n\n"
           body
           "\n\n"
           (ansi/muted (str "  Cache hit rate:   "
                            (if (pos? n)
                              (str (int (* 100 (/ cache-hits (double n)))) "%"
                                   " (" cache-hits "/" n " calls)")
                              "N/A")))
           "\n"
           (ansi/muted (str "  Avg input tokens: " (format-number (long avg-in))
                            "  |  Avg output tokens: " (format-number (long avg-out))))
           breakdown-tail))))

;; ============================================================================
;; Conversation Messages
;; ============================================================================

(defn format-conversation-message
  "Format a single conversation message.
   Input: {:role :content}"
  [{:keys [role content agent-id]}]
  (let [role-str (case (str role)
                   "user"      (ansi/user-text "You")
                   "assistant" (if agent-id
                                 (ansi/style (str "Agent (" (name agent-id) ")")
                                             ansi/bold ansi/magenta)
                                 (ansi/style "Agent" ansi/bold ansi/magenta))
                   "system"    (ansi/muted "System")
                   "tool"      (ansi/tool-name "Tool")
                   (ansi/muted (str role)))
        content-str (truncate (str content) 500)]
    (str role-str ": " content-str)))

(defn format-conversation-history
  "Format conversation message list.
   Options: :last-n (default all)"
  [messages & {:keys [last-n]}]
  (when (seq messages)
    (let [msgs (if last-n (take-last last-n messages) messages)]
      (->> msgs
           (map format-conversation-message)
           (str/join "\n")))))

;; ============================================================================
;; Status Summary
;; ============================================================================

(defn format-status-summary
  "Format agent status snapshot.
   Input: {:agent-id :status :iteration :max-iterations :todo-progress :goal-achieved}"
  [{:keys [agent-id status iteration max-iterations todo-progress goal-achieved]}]
  (let [status-color (case status
                       :running ansi/bright-green
                       :idle    ansi/bright-yellow
                       (:cancelled :stopped) ansi/bright-red
                       ansi/white)]
    (str (ansi/header "Agent Status") "\n"
         "  Agent:      " (ansi/style (str agent-id) ansi/bold ansi/cyan) "\n"
         "  Status:     " (ansi/style (str (name status)) status-color) "\n"
         "  Iteration:  " (or iteration 0) (when max-iterations (str " / " max-iterations)) "\n"
         (when todo-progress
           (str "  TODO:       " todo-progress "\n"))
         (when (some? goal-achieved)
           (str "  Goal:       "
                (if goal-achieved
                  (ansi/success "achieved")
                  (ansi/muted "in progress"))
                "\n")))))

;; ============================================================================
;; BT Trace
;; ============================================================================

(defn format-trace
  "Format a BT trace entry with depth indentation and agent-id prefix.
   Input: {:agent-id :depth :content}"
  [{:keys [agent-id depth content]}]
  (let [indent (apply str (repeat (* 2 (or depth 0)) " "))
        prefix (when agent-id (str (ansi/muted (str "[" agent-id "]")) " "))]
    (str (ansi/muted "  [trace] ") indent prefix (ansi/muted (str content)))))

;; ============================================================================
;; Mulog Event Formatting
;; ============================================================================

(defn- format-duration
  "Format nanosecond duration as human-readable seconds."
  [nanos]
  (when nanos
    (format "%.1fs" (/ (double nanos) 1e9))))

(defn- mulog-event-name-suffix
  "Extract the short name from a namespaced mulog event keyword."
  [event-name]
  (when event-name
    (name event-name)))

(defn format-mulog-event
  "Format a mulog event map as a compact single-line string for TUI display.
   Returns nil if the event should be suppressed."
  [event]
  (let [event-name (:mulog/event-name event)
        suffix (mulog-event-name-suffix event-name)]
    (when suffix
      (let [line (case suffix
                   ;; LLM call events
                   "chat-completion"
                   (str "chat model=" (:model event)
                        (when-let [t (:total-tokens event)] (str " tokens=" t))
                        (when-let [c (:cost event)] (str " cost=$" (format "%.4f" (double c)))))

                   "openai-api-call-result"
                   (str "openai-result " (:model event)
                        (when-let [d (:duration-ms event)] (str " " (format-duration (* d 1000000))))
                        (when-let [pt (:prompt-tokens event)] (str " in=" pt))
                        (when-let [ct (:completion-tokens event)] (str " out=" ct)))

                   "anthropic-api-call-result"
                   (str "anthropic-result " (:model event)
                        (when-let [d (:duration-ms event)] (str " " (format-duration (* d 1000000))))
                        (when-let [it (:input-tokens event)] (str " in=" it))
                        (when-let [ot (:output-tokens event)] (str " out=" ot)))

                   ;; RLM context management events
                   "rlm-turn-start"
                   (str "rlm-start turns=" (:conversation-turns event)
                        " prev-turns=" (:previous-turns-count event)
                        " compaction=" (:enable-compaction event)
                        " budget=" (:enable-budget event))

                   "rlm-turn-complete"
                   (str "rlm-done terminated=" (:terminated-by event)
                        " iters=" (:total-iterations event)
                        " compactions=" (:compaction-count event))

                   ;; Agent trace/conversation (already displayed via watch, skip)
                   ("agent-trace" "agent-conversation") nil

                   ;; RLM iteration
                   "rlm-iteration"
                   (str "iter=" (:iteration event)
                        " blocks=" (count (or (:code-blocks event) []))
                        (when (:terminated event) " FINAL"))

                   ;; API calls (start)
                   "anthropic-api-call"
                   (str "anthropic " (:model event)
                        (when (:stream event) " stream"))

                   "openai-api-call"
                   (str "openai " (:model event)
                        (when (:stream event) " stream"))

                   ;; Embedding
                   "create-embedding"
                   (str "embed model=" (:model event))

                   "create-embeddings"
                   (str "embed model=" (:model event) " n=" (:count event))

                   ;; Claude Code CLI call start
                   "cli-call"
                   (str "cli-call " (or (:model event) "?")
                        (when (:stream event) " stream")
                        (when-let [p (:prompt event)] (str " prompt=" (count p) "chars")))

                   ;; Claude Code CLI call result
                   "cli-call-result"
                   (str "cli-result " (or (:model event) "?")
                        (when (:stream event) " stream")
                        (when-let [d (:duration-ms event)] (str " " (format-duration (* d 1000000))))
                        (when-let [it (:input-tokens event)] (str " in=" it))
                        (when-let [ot (:output-tokens event)] (str " out=" ot))
                        (when-let [c (:cli-cost event)] (str " cost=$" (format "%.4f" (double c))))
                        (when-let [n (:num-turns event)] (str " turns=" n)))

                   ;; Fallback: show event name + safe subset of keys
                   (let [skip-keys #{:messages :response-text :eval-results :code-results :usage :body :request :response}
                         user-keys (->> (keys event)
                                        (remove #(or (= "mulog" (namespace %))
                                                     (contains? skip-keys (keyword (name %)))))
                                        (take 4))
                         safe-val (fn [v]
                                    (let [s (pr-str v)]
                                      (if (> (count s) 60) (str (subs s 0 60) "...") s)))]
                     (str suffix
                          (when (seq user-keys)
                            (str " " (str/join " " (map #(str (name %) "=" (safe-val (get event %)))
                                                        user-keys)))))))]
        (when line
          (ansi/muted (str "  [mulog] " line)))))))

(defn format-mulog-event-data
  "Format a mulog event as structured data for web display.
   Returns nil if the event should be suppressed.
   Returns {:category str :summary str :detail map :timestamp long}"
  [event]
  (let [event-name (:mulog/event-name event)
        suffix (mulog-event-name-suffix event-name)]
    (when suffix
      (let [result
            (case suffix
              "chat-completion"
              {:category "chat"
               :summary (str "chat model=" (:model event)
                             (when-let [t (:total-tokens event)] (str " tokens=" t))
                             (when-let [c (:cost event)] (str " cost=$" (format "%.4f" (double c)))))
               :detail (cond-> {:model (:model event)}
                         (:total-tokens event) (assoc :tokens (:total-tokens event))
                         (:input-tokens event) (assoc :input-tokens (:input-tokens event))
                         (:output-tokens event) (assoc :output-tokens (:output-tokens event))
                         (:cost event) (assoc :cost (:cost event))
                         (:latency-ms event) (assoc :latency-ms (:latency-ms event)))}

              ;; OpenAI/Anthropic API call start — shows request
              ("openai-api-call" "anthropic-api-call")
              {:category "api"
               :summary (str (if (= suffix "openai-api-call") "openai" "anthropic")
                             " " (:model event)
                             (when (:stream event) " stream"))
               :detail (cond-> {:provider (name (or (:provider event) "?"))
                                :model (:model event)
                                :url (:url event)}
                         (:stream event) (assoc :stream true))
               :request-body (:request-body event)}

              "rlm-turn-start"
              {:category "rlm"
               :summary (str "rlm-start turns=" (:conversation-turns event)
                             " prev-turns=" (:previous-turns-count event)
                             " compaction=" (:enable-compaction event)
                             " budget=" (:enable-budget event))
               :detail {:turns (:conversation-turns event)
                        :prev-turns (:previous-turns-count event)
                        :compaction (:enable-compaction event)
                        :budget (:enable-budget event)}}

              "rlm-turn-complete"
              {:category "rlm"
               :summary (str "rlm-done terminated=" (:terminated-by event)
                             " iters=" (:total-iterations event)
                             " compactions=" (:compaction-count event))
               :detail {:terminated-by (:terminated-by event)
                        :iterations (:total-iterations event)
                        :compactions (:compaction-count event)}}

              ("agent-trace" "agent-conversation") nil

              ;; RLM iteration (code execution step)
              "rlm-iteration"
              (let [code-blocks (:code-blocks event)
                    code-count (count (or code-blocks []))
                    iter-num (:iteration event)
                    terminated (:terminated event)
                    code-preview (when (seq code-blocks)
                                   (let [first-block (first code-blocks)
                                         s (if (> (count first-block) 80)
                                             (str (subs first-block 0 80) "...")
                                             first-block)]
                                     s))]
                {:category "iter"
                 :summary (str "iter=" iter-num
                               " blocks=" code-count
                               (when terminated " FINAL"))
                 :detail {:iteration iter-num
                          :code-blocks (mapv #(if (> (count %) 200) (str (subs % 0 200) "...") %)
                                             (or code-blocks []))
                          :terminated terminated}})

              ;; Anthropic API call
              ;; Anthropic API call result
              "anthropic-api-call-result"
              (let [duration-ms (:duration-ms event)
                    input-tokens (:input-tokens event)
                    output-tokens (:output-tokens event)
                    stop-reason (:stop-reason event)
                    cache-create (:cache-creation-tokens event)
                    cache-read (:cache-read-tokens event)]
                {:category "api"
                 :summary (str "anthropic-result " (:model event)
                               (when duration-ms (str " " (format-duration (* duration-ms 1000000))))
                               (when input-tokens (str " in=" input-tokens))
                               (when output-tokens (str " out=" output-tokens)))
                 :detail (cond-> {:provider (str (:provider event))
                                  :model (:model event)}
                           duration-ms (assoc :duration-ms duration-ms)
                           (:http-status event) (assoc :http-status (:http-status event))
                           stop-reason (assoc :stop-reason stop-reason)
                           input-tokens (assoc :input-tokens input-tokens)
                           output-tokens (assoc :output-tokens output-tokens)
                           cache-create (assoc :cache-creation-tokens cache-create)
                           cache-read (assoc :cache-read-tokens cache-read)
                           (:response-id event) (assoc :response-id (:response-id event))
                           (:response-type event) (assoc :response-type (:response-type event)))
                 :response-body (:response-body event)})

              ;; OpenAI API call result
              "openai-api-call-result"
              (let [duration-ms (:duration-ms event)
                    prompt-tokens (:prompt-tokens event)
                    completion-tokens (:completion-tokens event)
                    finish-reason (:finish-reason event)]
                {:category "api"
                 :summary (str "openai-result " (:model event)
                               (when duration-ms (str " " (format-duration (* duration-ms 1000000))))
                               (when prompt-tokens (str " in=" prompt-tokens))
                               (when completion-tokens (str " out=" completion-tokens)))
                 :detail (cond-> {:provider (str (:provider event))
                                  :model (:model event)}
                           duration-ms (assoc :duration-ms duration-ms)
                           (:http-status event) (assoc :http-status (:http-status event))
                           finish-reason (assoc :finish-reason finish-reason)
                           prompt-tokens (assoc :prompt-tokens prompt-tokens)
                           completion-tokens (assoc :completion-tokens completion-tokens)
                           (:total-tokens event) (assoc :total-tokens (:total-tokens event))
                           (:response-id event) (assoc :response-id (:response-id event)))
                 :response-body (:response-body event)})

              "create-embedding"
              {:category "embed"
               :summary (str "embed model=" (:model event))
               :detail {:model (:model event)}}

              "create-embeddings"
              {:category "embed"
               :summary (str "embed model=" (:model event) " n=" (:count event))
               :detail {:model (:model event) :count (:count event)}}

              ;; Task execution (task$run background tasks)
              "task-execution"
              (let [duration-ms (:duration-ms event)]
                {:category "task"
                 :summary (str "task-execution task-id=" (:task-id event)
                               (when-let [ol (:output-lines event)]
                                 (str " output-lines=" (pr-str ol)))
                               (when duration-ms (str " duration-ms=" duration-ms))
                               " job-config=" (pr-str (:job-config event)))
                 :detail (cond-> {:task-id (:task-id event)
                                  :status (:status event)}
                           (:task-name event) (assoc :task-name (:task-name event))
                           (:job-type event) (assoc :job-type (:job-type event))
                           (:job-config event) (assoc :job-config (:job-config event))
                           duration-ms (assoc :duration-ms duration-ms)
                           (:output-lines event) (assoc :output-lines (:output-lines event)))})

              ;; Claude Code CLI call start
              "cli-call"
              (let [model (:model event)
                    args (:args event)
                    prompt (:prompt event)]
                {:category "cli"
                 :summary (str "cli-call " (or model "?")
                               (when (:stream event) " stream")
                               (when prompt (str " prompt=" (count prompt) "chars")))
                 :detail (cond-> {:provider "claude-code"
                                  :model model}
                           (:stream event) (assoc :stream true)
                           args (assoc :args args)
                           prompt (assoc :prompt prompt))})

              ;; Claude Code CLI call result
              "cli-call-result"
              (let [model (:model event)
                    duration-ms (:duration-ms event)
                    input-tokens (:input-tokens event)
                    output-tokens (:output-tokens event)
                    cli-cost (:cli-cost event)
                    num-turns (:num-turns event)]
                {:category "cli"
                 :summary (str "cli-result " (or model "?")
                               (when (:stream event) " stream")
                               (when duration-ms (str " " (format-duration (* duration-ms 1000000))))
                               (when input-tokens (str " in=" input-tokens))
                               (when output-tokens (str " out=" output-tokens))
                               (when cli-cost (str " cost=$" (format "%.4f" (double cli-cost))))
                               (when num-turns (str " turns=" num-turns)))
                 :detail (cond-> {:provider "claude-code"
                                  :model model}
                           (:stream event) (assoc :stream true)
                           duration-ms (assoc :duration-ms duration-ms)
                           input-tokens (assoc :input-tokens input-tokens)
                           output-tokens (assoc :output-tokens output-tokens)
                           cli-cost (assoc :cli-cost cli-cost)
                           num-turns (assoc :num-turns num-turns))
                 :response-body (:response event)})

              ;; Fallback — pick safe keys only (skip large values)
              (let [skip-keys #{:messages :response-text :eval-results :code-results :usage :body :request :response}
                    user-keys (->> (keys event)
                                   (remove #(or (= "mulog" (namespace %))
                                                (contains? skip-keys (keyword (name %)))))
                                   (take 4))
                    safe-val (fn [v]
                               (let [s (pr-str v)]
                                 (if (> (count s) 60) (str (subs s 0 60) "...") s)))]
                {:category (if (> (count suffix) 8) (subs suffix 0 8) suffix)
                 :summary (str suffix
                               (when (seq user-keys)
                                 (str " " (str/join " " (map #(str (name %) "=" (safe-val (get event %)))
                                                             user-keys)))))
                 :detail (into {} (map (fn [k]
                                         (let [v (get event k)
                                               s (pr-str v)]
                                           [(keyword (name k))
                                            (if (> (count s) 200) (str (subs s 0 200) "...") v)]))
                                       user-keys))}))]
        (when result
          ;; Use mulog's own timestamp for millisecond-resolution ordering
          ;; mulog/timestamp can be java.time.Instant (from trace) or Long millis (from log)
          (let [ts (:mulog/timestamp event)
                millis (cond
                         (instance? java.time.Instant ts)
                         (.toEpochMilli ^java.time.Instant ts)

                         (number? ts)
                         (long ts)

                         :else
                         (System/currentTimeMillis))]
            (assoc result :timestamp millis)))))))

;; ============================================================================
;; Welcome Banner
;; ============================================================================

(defn format-welcome-banner
  "Compact boxed greeting for TUI session start.
   Accepts an options map:
     :agent-id    - keyword agent ID
     :session-id  - string session ID
     :lm-provider - keyword provider (e.g. :claude-code) or nil
     :lm-model    - string model name or nil

   `:display-format` and `:agents` are accepted for caller compatibility but
   no longer rendered."
  [{:keys [agent-id session-id lm-provider lm-model]}]
  (let [sep      (ansi/style " · " ansi/dim)
        title    (ansi/style "Brainyard TUI" ansi/bold ansi/bright-cyan)
        agent    (ansi/style (name (or agent-id :unknown)) ansi/bold ansi/cyan)
        model    (when lm-provider
                   (ansi/style (str (name lm-provider)
                                    (when lm-model (str "/" lm-model)))
                               ansi/bright-magenta))
        session  (when session-id
                   (ansi/style (str "session " session-id) ansi/dim))
        head     (str title
                      (ansi/style " — " ansi/dim) agent
                      (when model (str sep model))
                      (when session (str sep session)))
        hint     (ansi/style "Type /help for commands. AI output may be inaccurate."
                             ansi/dim)
        inner    (max (display-width head) (display-width hint))
        pad      (fn [s] (apply str (repeat (- inner (display-width s)) " ")))
        bar      (apply str (repeat (+ inner 2) ansi/h-line))
        vbar     (ansi/style ansi/v-line ansi/dim)
        row      (fn [s] (str vbar " " s (pad s) " " vbar))
        top      (ansi/style (str ansi/tl-corner bar ansi/tr-corner) ansi/dim)
        bottom   (ansi/style (str ansi/bl-corner bar ansi/br-corner) ansi/dim)]
    (str "\n" top "\n"
         (row head) "\n"
         (row hint) "\n"
         bottom "\n")))
