;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent-tui.links
  "Detecting and acting on clickable targets in scrollback text — file
   locations (`path:line:col`) and http(s) URLs.

   DETECTION IS DONE AT CLICK TIME, from the rendered row, rather than
   registered by producers. That is the whole design decision, and it buys
   three things a producer-side registry could not without touching every
   formatter: it works retroactively on ALL existing output (answers, tool
   results, tracebacks, dossier paths) with no emit-site changes, it survives
   a resize for free because it re-runs against whatever is on screen now, and
   it cannot go stale relative to the text the user is actually looking at.

   What it costs is precision, and the file case pays for that with an
   EXISTENCE CHECK rather than with a stricter regex: `path-candidates` is
   deliberately loose, and a candidate that does not resolve to a real file is
   simply inert. A regex tight enough to avoid every false positive would also
   miss real paths, and a false positive here costs nothing.

   Detection runs on ONE rendered row, so a target the renderer hard-wrapped is
   only a fragment there. `recover-target` widens it against the same entry
   re-rendered at a width where nothing wraps — NOT by joining the visible rows,
   which would let whoever wrote the text choose the seam and forge a target.
   See that function.

   `decorate-row` is the visible half: it marks what is clickable, since nothing
   else on screen says so. What earns a mark is deliberately narrower than what
   is clickable — see `worth-marking?`.

   Nothing here touches the terminal: detection and resolution are pure, and
   `open-url!` shells out. The TUI-side orchestration (suspending for $EDITOR,
   emitting feedback) lives at the click site."
  (:require [ai.brainyard.agent.interface.tui.ansi :as ansi]
            [ai.brainyard.agent.interface.tui.format :as fmt]
            [clojure.java.io :as io]
            [clojure.string :as str]))

;; ============================================================================
;; Detection
;; ============================================================================

(def url-re
  "http(s) URLs. The trailing class excludes whitespace, quotes, and the
   bracket/brace pairs that routinely wrap a URL in prose.

   Parentheses are deliberately NOT excluded: they occur inside real URLs often
   enough to matter (`…/wiki/Foo_(bar)`, MDN, MSDN), and excluding them silently
   truncates the target to something that still looks plausible. The wrapping
   case — `(see https://x/a)` — is handled after the match by `strip-url-junk`,
   which can count them and drop only an UNBALANCED trailing `)`. A character
   class cannot do that; it has no memory."
  #"https?://[^\s\"'`<>\[\]{}|\\^]+")

(def ^:private url-trailing-junk
  "Sentence punctuation that ends up glued to a URL in prose. Stripped from the
   END of a match only."
  #"[.,;:!?]+$")

(def path-candidate-re
  "A maximal run of path-ish characters, including the `:` that carries a
   `:line:col` suffix. Deliberately loose — see the ns docstring: the file's
   existence, not this pattern, is what decides whether a click does anything."
  #"[A-Za-z0-9._/~+@:-]+")

(defn- strip-url-junk
  "Trim trailing sentence punctuation, and any unbalanced closing paren, from a
   URL match. `(see https://x.com/a)` should not open `.../a)`."
  [^String u]
  (let [u (str/replace u url-trailing-junk "")]
    (if (and (str/ends-with? u ")")
             (< (count (re-seq #"\(" u)) (count (re-seq #"\)" u))))
      (subs u 0 (dec (count u)))
      u)))

(defn- parse-path-candidate
  "Split a candidate token into `{:path :line :col}`, peeling an optional
   trailing `:LINE` and `:LINE:COL`.

   Returns nil when what is left is not path-shaped. The one structural rule —
   the path must contain a `/` or a `.` — is what stops every bare word in
   prose from becoming a stat() call; `README` alone is not treated as a path,
   `./README` and `README.md` are."
  [^String token]
  (let [[_ path line col] (re-matches #"(.*?)(?::(\d+))?(?::(\d+))?" token)]
    (when (and path
               (seq path)
               (or (str/includes? path "/") (str/includes? path "."))
               ;; `https://…` is a URL, and is matched before we ever get here;
               ;; guard anyway so a URL in a non-URL position cannot be
               ;; misread as a relative path called `https`.
               (not (str/includes? path "://")))
      (cond-> {:path path}
        line (assoc :line (parse-long line))
        col  (assoc :col (parse-long col))))))

(defn- match-spans
  "All `[start end text]` spans of `re` in `s`."
  [re ^String s]
  (let [m (re-matcher re s)]
    (loop [acc []]
      (if (.find m)
        (recur (conj acc [(.start m) (.end m) (.group m)]))
        acc))))

(defn detect-at
  "The clickable target covering char index `idx` in the PLAIN line `s`, or nil.

   URLs win over file paths wherever both match, because a URL is also a
   perfectly good path-candidate token and resolving it as one would be
   nonsense. Returns:
     {:kind :url  :text \"https://…\" :start i :end j}
     {:kind :file :path \"src/a.clj\" :line 42 :col 7 :text \"…\" :start i :end j}
   `:line` / `:col` are absent when the text carried no suffix."
  [^String s idx]
  (when (and s idx (nat-int? idx) (< (long idx) (.length s)))
    (let [idx (long idx)
          in? (fn [[st en _]] (and (>= idx (long st)) (< idx (long en))))]
      (or (when-let [[st _ raw] (first (filter in? (match-spans url-re s)))]
            (let [text (strip-url-junk raw)]
              ;; The junk trim can pull the end back past the click; a click on
              ;; the trailing `)` of `(https://x)` is a click on the paren.
              (when (< idx (+ (long st) (count text)))
                {:kind :url :text text :start st :end (+ (long st) (count text))})))
          (when-let [[st en raw] (first (filter in? (match-spans path-candidate-re s)))]
            (when-let [parsed (parse-path-candidate raw)]
              (assoc parsed :kind :file :text raw :start st :end en)))))))

(defn detect-in-row
  "`detect-at` against a 1-based display COLUMN of a rendered (styled) row —
   the shape a click actually arrives in. nil when the column lands past the
   text, or on nothing clickable.

   Strips ANSI first and maps the column through `fmt/column->index`, so the
   index handed to `detect-at` is an index into the same plain text the user
   sees. Doing it the other way round — searching the styled string — finds
   OSC-8 URL payloads that occupy no columns at all."
  [^String styled-row col]
  (let [plain (fmt/strip-ansi (or styled-row ""))]
    (when-let [idx (fmt/column->index plain col)]
      (detect-at plain idx))))

;; ============================================================================
;; Recovering a target the visible wrap split across rows
;; ============================================================================

(defn recover-target
  "Widen `t` — a target detected on ONE rendered row — using `unwrapped`, the
   same scrollback entry re-rendered at a width where nothing wraps. Returns a
   target, `t` unchanged when nothing better is found.

   The visible row may hold only a FRAGMENT: a long URL or path that the
   renderer hard-wrapped is split across two rows, and neither half is the real
   target. `unwrapped` is the whole entry as the renderer would draw it if the
   terminal were wide enough, so the target appears in it intact.

   THIS IS NOT ROW CONCATENATION, and the difference is a security property,
   not a nicety. Splicing row N's tail onto row N+1's head lets whoever wrote
   the text choose where the seam falls, and a seam is enough to forge a
   target: `https://safe.com` ending a row and `@evil.com/x` beginning the next
   concatenate to `https://safe.com@evil.com/x`, which navigates to evil.com
   while both visible halves read as harmless. Here the candidate comes from
   the RENDERER's own output, so the text was never under the author's control
   at the seam, and the fragment must appear inside it as a substring.

   Ambiguity yields `t`: if the fragment occurs in two different candidates
   there is no way to know which row the user was looking at, and guessing is
   how you open the wrong thing."
  [t unwrapped]
  (if (or (nil? t) (str/blank? unwrapped))
    t
    (let [url?  (= :url (:kind t))
          frag  (:text t)
          hits  (->> (match-spans (if url? url-re path-candidate-re) unwrapped)
                     (map (fn [[_ _ raw]] (if url? (strip-url-junk raw) raw)))
                     (filter #(str/includes? % frag))
                     distinct)]
      (if (= 1 (count hits))
        (let [full (first hits)]
          (if (> (count full) (count frag))
            (if url?
              {:kind :url :text full}
              ;; A widened path that no longer parses is a reason to keep the
              ;; fragment, not to drop the click on the floor.
              (or (some-> (parse-path-candidate full) (assoc :kind :file :text full))
                  t))
            t))
        t))))

;; ============================================================================
;; Resolution
;; ============================================================================

(defn- expand-home
  [^String p]
  (if (or (= p "~") (str/starts-with? p "~/"))
    (str (System/getProperty "user.home") (subs p 1))
    p))

(defn resolve-file
  "Absolute `File` for `path` when it names an EXISTING regular file, else nil.

   Relative paths resolve against `base-dir` (the agent's working directory at
   the call site). Passed in rather than read here so this stays pure and
   testable — and so the caller decides which of working-dir / project-dir
   applies.

   Directories deliberately return nil: opening one in $EDITOR is rarely what
   a click on a path meant, and `.` / `..` are extremely common false
   positives from the loose candidate pattern."
  [path base-dir]
  (when (seq path)
    (try
      (let [p (expand-home path)
            f (let [f0 (io/file p)]
                (if (.isAbsolute f0)
                  f0
                  (io/file (or base-dir ".") p)))]
        (when (and (.exists f) (.isFile f))
          (.getCanonicalFile f)))
      (catch Exception _ nil))))

;; ============================================================================
;; Actions
;; ============================================================================

(defn- mac? [] (str/starts-with? (str (System/getProperty "os.name")) "Mac"))

(defn openable-url?
  "Only http(s). The clicked text comes from model output, so the scheme is an
   allowlist rather than a denylist — `file:`, `javascript:` and every custom
   handler a machine happens to have registered stay unreachable."
  [^String u]
  (boolean (and u (re-matches #"(?i)https?://.+" u))))

(defn open-url!
  "Hand `url` to the OS opener. Returns true when the process was launched.

   argv, never a shell string: the URL is model-authored text, and `sh -c`
   would make every shell metacharacter in it executable. Fire-and-forget —
   the opener detaches, so a non-zero exit is not observable and is not worth
   pretending to report."
  [^String url]
  (when (openable-url? url)
    (try
      (let [argv (if (mac?) ["open" url] ["xdg-open" url])
            pb   (ProcessBuilder. ^java.util.List (vec argv))]
        (.redirectOutput pb java.lang.ProcessBuilder$Redirect/DISCARD)
        (.redirectError pb java.lang.ProcessBuilder$Redirect/DISCARD)
        (.start pb)
        true)
      (catch Exception _ false))))

(defn editor-argv-suffix
  "How to tell `$EDITOR` to jump to `line`, as the argument fragment that
   follows the editor name.

   Three conventions, and no way to detect which an unknown editor speaks:
   VS Code and its forks want `--goto path:line`; Sublime and Helix take
   `path:line` directly; everything in the vi/emacs/nano/less family takes a
   separate `+line` BEFORE the path. Unknown editors get `+line`, the oldest
   and most widely honoured of the three. With no line, just the path."
  [^String editor ^String path line]
  (let [base (-> editor (str/split #"\s+") first (or "") (str/replace #".*/" ""))]
    (cond
      (nil? line)
      [path]

      (contains? #{"code" "code-insiders" "codium" "cursor" "windsurf"} base)
      ["--goto" (str path ":" line)]

      (contains? #{"subl" "sublime_text" "hx" "helix"} base)
      [(str path ":" line)]

      :else
      [(str "+" line) path])))

;; ============================================================================
;; Affordance — underlining what is clickable
;; ============================================================================
;;
;; Without this the feature is invisible: nothing on screen says a path or a URL
;; will do anything. `render-viewport!` runs each visible row through
;; `decorate-row` (installed by the app only when mouse reporting is on — an
;; underline promising a click that cannot happen is worse than no underline).
;;
;; Two things make it affordable on the paint path. The result is MEMOISED on
;; the row string, and rows are immutable and repaint constantly, so the regex
;; scan and the stat() run once per distinct row rather than once per frame.
;; And the cache never needs invalidating, because THE UNDERLINE IS ONLY A HINT
;; — every click re-detects and re-resolves from scratch. A stale underline on
;; a since-deleted file is cosmetic; the click that follows it correctly does
;; nothing.

(def ^:private decorate-cache-max
  "Bound on the memo. Cleared wholesale on overflow rather than evicted in LRU
   order: rows arrive in scrollback order, so the entries worth keeping are the
   recent ones the next frame will ask for again, and they are re-derived in
   microseconds anyway."
  4096)

(defonce ^:private !decorate-cache (atom {}))

(defn- worth-marking?
  "Whether a resolvable path candidate earns a visible mark.

   MARKING EVERY RESOLVABLE PATH IS TOO LOUD. This TUI names files constantly —
   tool args, results, dossier paths, echoed commands — and a bare relative
   filename mid-sentence (`deps.edn`, `README.md`) is usually being *mentioned*,
   not offered. Marking those turns ordinary prose into a field of underlines
   and devalues the mark where it matters.

   So the mark goes to the two shapes that read as a LOCATION rather than a
   mention: anything carrying a `:line` suffix — the traceback/error case, and
   the one where clicking saves the most work — and anything absolute, which is
   already being given as a place to go.

   This narrows the AFFORDANCE only. Clicking is unchanged: a bare `deps.edn`
   still opens, it just is not advertised. The mark is a hint; the click stays
   authoritative."
  [p]
  (let [path (str (:path p))]
    (boolean (or (:line p)
                 (str/starts-with? path "/")
                 (str/starts-with? path "~")))))

(defn- mark-spans
  "`[[start end] …]` over PLAIN `s` for every target worth marking, in
   ascending order and never overlapping.

   URLs need no I/O and are always marked — they are rarer than paths and a
   click is the only reasonable thing to do with one. File paths are filtered
   by `worth-marking?` and then checked against the filesystem, because a mark
   on a path that does not resolve promises a click that will do nothing."
  [^String s base-dir]
  (let [urls  (map (fn [[st _ raw]]
                     (let [text (strip-url-junk raw)]
                       [(long st) (+ (long st) (count text))]))
                   (match-spans url-re s))
        url-covered? (fn [i] (some (fn [[a b]] (and (>= i (long a)) (< i (long b)))) urls))
        files (keep (fn [[st en raw]]
                      (when-not (url-covered? (long st))
                        (when-let [p (parse-path-candidate raw)]
                          (when (and (worth-marking? p)
                                     (resolve-file (:path p) base-dir))
                            [(long st) (long en)]))))
                    (match-spans path-candidate-re s))]
    (sort-by first (concat urls files))))

(defn- decorate-row*
  [^String row base-dir]
  (let [plain (fmt/strip-ansi row)
        spans (mark-spans plain base-dir)]
    (if (empty? spans)
      row
      (let [on     (ansi/link-mark-on)
            off    (ansi/link-mark-off)
            starts (into {} (map (fn [[a _]] [a true])) spans)
            ends   (into {} (map (fn [[_ b]] [b true])) spans)
            len    (.length row)
            sb     (StringBuilder. (+ len 32))]
        ;; One walk over the STYLED row, tracking the plain index alongside it,
        ;; so a marker is only ever inserted between escapes — never inside one.
        (loop [i 0, p 0, in? false]
          (if (>= i len)
            (do (when in? (.append sb ^String off))
                (.toString sb))
            (let [c (.charAt row i)]
              (if (= 27 (int c))
                (let [e (long (fmt/ansi-seq-end row i))]
                  (.append sb (.substring row i e))
                  ;; Re-assert inside a span. The row's own escapes are not ours
                  ;; to interpret, and one of them may well be a full SGR reset
                  ;; — which would silently drop the underline for the rest of
                  ;; the target. Cheap to re-emit; invisible when redundant.
                  (when in? (.append sb ^String on))
                  (recur e p in?))
                (let [end?   (contains? ends p)
                      start? (contains? starts p)]
                  ;; Close before open, so two targets that abut do not merge
                  ;; into one underlined run.
                  (when (and in? end?) (.append sb ^String off))
                  (when start? (.append sb ^String on))
                  (.append sb c)
                  (recur (inc i) (inc p)
                         (cond start? true end? false :else in?)))))))))))

(defonce ^:private !cache-mark
  ;; The mark the cached rows were built with. `:link/target` can be rebound at
  ;; runtime by a theme, and every cached row would then carry the OLD escapes
  ;; forever — the memo is keyed on the input row, which does not change when
  ;; the theme does.
  (atom nil))

(defn decorate-row
  "`row` with every clickable target marked. Memoised — see the section comment
   for why that is both safe and necessary."
  [^String row base-dir]
  (if (str/blank? row)
    row
    (let [mark (ansi/link-mark-on)]
      (when (not= mark @!cache-mark)
        (reset! !decorate-cache {})
        (reset! !cache-mark mark))
      (if-let [hit (get @!decorate-cache row)]
        hit
        (let [out (decorate-row* row base-dir)]
          (swap! !decorate-cache
                 (fn [m] (assoc (if (> (count m) decorate-cache-max) {} m) row out)))
          out)))))

(defn reset-decorate-cache!
  "Drop the memo. For tests, and for anything that changes what resolves."
  []
  (reset! !decorate-cache {}))
