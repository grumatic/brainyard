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

   Scope limit worth knowing: detection runs on ONE rendered row. A target
   long enough to be hard-wrapped across two rows is not found. Rejoining an
   entry's rows via `!scrollback-src` sounds like the fix and is not — most
   output is boxed, so consecutive rows carry `│ ` borders and trailing pad
   that would be spliced into the middle of the target. Recovering a wrapped
   URL needs the box structure, not just the rows.

   Nothing here touches the terminal: detection and resolution are pure, and
   `open-url!` shells out. The TUI-side orchestration (suspending for $EDITOR,
   emitting feedback) lives at the click site."
  (:require [ai.brainyard.agent.interface.tui.format :as fmt]
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
