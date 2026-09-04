;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent-tui.links-test
  "Tests for click-time link detection.

   The load-bearing property is asymmetric: a MISSED target costs a click that
   does nothing, while a WRONG target opens something the user did not point
   at. So the file tests care most about what must NOT resolve, and lean on
   `resolve-file`'s existence check rather than on regex tightness."
  (:require [ai.brainyard.agent-tui.layout :as layout]
            [ai.brainyard.agent-tui.links :as links]
            [ai.brainyard.agent.interface.tui.ansi :as ansi]
            [ai.brainyard.agent.interface.tui.format :as fmt]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]))

(def ^:private ESC (str (char 27)))
(def ^:private BEL (str (char 7)))

(def ^:private tmp-root
  (delay
    (let [d (io/file (System/getProperty "java.io.tmpdir")
                     (str "by-links-test-" (System/nanoTime)))]
      (.mkdirs d)
      d)))

(defn- tmp-file! [name content]
  (let [f (io/file @tmp-root name)]
    (io/make-parents f)
    (spit f content)
    f))

(use-fixtures :once
  (fn [t]
    (t)
    (doseq [f (reverse (file-seq @tmp-root))] (.delete ^java.io.File f))))

;; ---------------------------------------------------------------------------
;; URLs
;; ---------------------------------------------------------------------------

(deftest detects-a-url-under-the-click
  (let [line "see https://example.com/a/b?q=1 now"]
    (testing "anywhere inside the URL resolves to the whole URL"
      (doseq [idx [4 10 29]]
        (let [t (links/detect-at line idx)]
          (is (= :url (:kind t)))
          (is (= "https://example.com/a/b?q=1" (:text t))))))
    (testing "outside it resolves to something else or nothing"
      (is (not= :url (:kind (links/detect-at line 0)))))))

(deftest url-does-not-swallow-its-surroundings
  (testing "trailing sentence punctuation is not part of the target"
    (is (= "https://example.com/a"
           (:text (links/detect-at "go to https://example.com/a." 10))))
    (is (= "https://example.com/a"
           (:text (links/detect-at "go to https://example.com/a, then" 10)))))
  (testing "an unbalanced closing paren belongs to the prose"
    (is (= "https://example.com/a"
           (:text (links/detect-at "(see https://example.com/a)" 12)))))
  (testing "a balanced paren inside the path is kept"
    (is (= "https://en.wikipedia.org/wiki/Foo_(bar)"
           (:text (links/detect-at "https://en.wikipedia.org/wiki/Foo_(bar)" 5)))))
  (testing "wrapped AND containing parens: only the unbalanced one is dropped"
    (is (= "https://en.wikipedia.org/wiki/Foo_(bar)"
           (:text (links/detect-at "(see https://en.wikipedia.org/wiki/Foo_(bar))" 12)))))
  (testing "a box border ends the match"
    (is (= "https://example.com/a"
           (:text (links/detect-at "│ https://example.com/a │" 5))))))

(deftest clicking_the_trailing_paren_is_not_a_url_click
  (testing "after the junk trim the click must still land inside the target"
    (let [line "(see https://example.com/a)"
          idx  (dec (count line))]           ;; the `)`
      (is (not= :url (:kind (links/detect-at line idx)))))))

(deftest only-http-schemes-are-openable
  (testing "the clicked text is model-authored, so schemes are an allowlist"
    (is (true?  (links/openable-url? "https://x.com")))
    (is (true?  (links/openable-url? "HTTP://x.com")))
    (is (false? (links/openable-url? "file:///etc/passwd")))
    (is (false? (links/openable-url? "javascript:alert(1)")))
    (is (false? (links/openable-url? "ssh://host")))
    (is (false? (links/openable-url? nil))))
  (testing "open-url! refuses anything the allowlist rejects"
    (is (nil? (links/open-url! "file:///etc/passwd")))
    (is (nil? (links/open-url! "javascript:alert(1)")))))

;; ---------------------------------------------------------------------------
;; File locations
;; ---------------------------------------------------------------------------

(deftest detects-a-file-location-with-line-and-column
  (let [t (links/detect-at "  at src/core.clj:42:7 in handler" 8)]
    (is (= :file (:kind t)))
    (is (= "src/core.clj" (:path t)))
    (is (= 42 (:line t)))
    (is (= 7 (:col t)))))

(deftest file-suffixes-are-optional
  (let [t (links/detect-at "see src/core.clj:42 here" 8)]
    (is (= "src/core.clj" (:path t)))
    (is (= 42 (:line t)))
    (is (nil? (:col t))))
  (let [t (links/detect-at "see src/core.clj here" 8)]
    (is (= "src/core.clj" (:path t)))
    (is (nil? (:line t)))))

(deftest absolute-and-home-paths-are-detected
  (is (= "/Users/x/p/a.clj" (:path (links/detect-at "at /Users/x/p/a.clj:9" 6))))
  (is (= "~/notes/todo.md"  (:path (links/detect-at "see ~/notes/todo.md now" 8)))))

(deftest prose-and-clock-like-tokens-are-not-paths
  (testing "a bare word has no / or . and is rejected structurally"
    (is (nil? (links/detect-at "just a word here" 6)))
    (is (nil? (links/detect-at "run the thing" 4))))
  (testing "a status-bar clock is not a path"
    (is (nil? (links/detect-at "time 10:15:26 now" 7))))
  (testing "a URL is never re-read as a relative path called `https`"
    (is (= :url (:kind (links/detect-at "https://example.com/a" 3))))))

(deftest resolution-is-what-makes-loose-detection-safe
  (let [f (tmp-file! "real.clj" "(ns real)")]
    (testing "an existing file resolves to its canonical path"
      (is (= (.getCanonicalPath f)
             (.getPath (links/resolve-file "real.clj" (.getPath @tmp-root))))))
    (testing "an absolute path resolves regardless of base"
      (is (some? (links/resolve-file (.getPath f) "/nowhere"))))
    (testing "a detected candidate that names nothing is simply inert"
      (is (nil? (links/resolve-file "no/such/file.clj" (.getPath @tmp-root))))
      ;; `3.14` and `e.g.` pass the loose pattern; existence is what stops them.
      (is (nil? (links/resolve-file "3.14" (.getPath @tmp-root))))
      (is (nil? (links/resolve-file "e.g." (.getPath @tmp-root)))))
    (testing "a directory is not opened — `.` and `..` are constant false positives"
      (is (nil? (links/resolve-file "." (.getPath @tmp-root))))
      (is (nil? (links/resolve-file ".." (.getPath @tmp-root)))))
    (testing "blank and nil are safe"
      (is (nil? (links/resolve-file "" (.getPath @tmp-root))))
      (is (nil? (links/resolve-file nil (.getPath @tmp-root)))))))

;; ---------------------------------------------------------------------------
;; Column mapping on a rendered row
;; ---------------------------------------------------------------------------

(deftest detect-in-row-maps-columns-through-ansi
  (testing "a styled row resolves at the column the user actually clicked"
    (let [row (str ESC "[2m  " ESC "[0m" ESC "[36msrc/core.clj:9" ESC "[0m")]
      ;; Plain text is "  src/core.clj:9" — the path starts at column 3.
      (is (= "src/core.clj" (:path (links/detect-in-row row 3))))
      (is (= 9 (:line (links/detect-in-row row 3))))
      (is (nil? (links/detect-in-row row 1)) "leading blank columns hit nothing")))
  (testing "a wide glyph shifts every later column"
    (let [row "日本 src/core.clj"]
      ;; 日本 is 4 columns, space is 1, so the path starts at column 6.
      (is (nil? (links/detect-in-row row 1)))
      (is (= "src/core.clj" (:path (links/detect-in-row row 6)))))))

(deftest an-osc8-payload-is-never-detected
  (testing "only what is ON SCREEN can be clicked — a hidden OSC-8 target is
            stripped before detection, so a label that reads as safe text can
            never open the URL annotated behind it"
    (let [row (str ESC "]8;;https://evil.example/x" BEL "docs" ESC "]8;;" BEL)]
      (is (= "docs" (fmt/strip-ansi row)))
      (doseq [col (range 1 6)]
        (let [t (links/detect-in-row row col)]
          (is (not= :url (:kind t))
              "the annotated URL occupies no column and must not be reachable"))))))

;; ---------------------------------------------------------------------------
;; Recovering a target the wrap split across rows
;; ---------------------------------------------------------------------------

(deftest recovers-a-url-split-across-rows
  (testing "the visible row holds a fragment; the unwrapped entry holds it whole"
    (let [frag {:kind :url :text "https://example.com/very/long/pa"}
          full "see https://example.com/very/long/path/that/continues here"]
      (is (= "https://example.com/very/long/path/that/continues"
             (:text (links/recover-target frag full)))))))

(deftest recovers-a-file-path-split-across-rows
  (let [frag {:kind :file :path "/Users/x/very/long/pa" :text "/Users/x/very/long/pa"}
        full "at /Users/x/very/long/path/core.clj:42 boom"
        t    (links/recover-target frag full)]
    (is (= "/Users/x/very/long/path/core.clj" (:path t)))
    (is (= 42 (:line t)))
    (is (= :file (:kind t)))))

(deftest recovery-is-a-no-op-when-the-row-already-had-it-all
  (let [t {:kind :url :text "https://example.com/a"}]
    (is (= t (links/recover-target t "see https://example.com/a here")))))

(deftest recovery-never-forges-a-target-from-a-seam
  (testing "row concatenation would let the AUTHOR pick the seam and build a
            target neither visible half shows — the userinfo spoof. Recovery
            searches the RENDERER's own unwrapped output instead, so a target
            that appears in no rendered candidate is never produced."
    (let [frag {:kind :url :text "https://safe.com"}
          ;; What naive row-joining would have produced from
          ;;   row N:   "… https://safe.com"
          ;;   row N+1: "@evil.com/x …"
          ;; is https://safe.com@evil.com/x — which navigates to evil.com.
          ;; The unwrapped entry shows they are two separate tokens.
          unwrapped "text https://safe.com and then @evil.com/x more"
          t (links/recover-target frag unwrapped)]
      (is (= "https://safe.com" (:text t))
          "the fragment stands; nothing is spliced across the seam")
      (is (not (str/includes? (:text t) "evil.com"))))))

(deftest ambiguity-declines-rather-than-guesses
  (testing "a fragment inside two DIFFERENT candidates cannot be resolved to
            one, and opening the wrong one is worse than opening nothing"
    (let [frag {:kind :url :text "https://example.com/a"}
          unwrapped "https://example.com/a/one and https://example.com/a/two"]
      (is (= "https://example.com/a" (:text (links/recover-target frag unwrapped))))))
  (testing "the SAME candidate appearing twice is not ambiguous"
    (let [frag {:kind :url :text "https://example.com/a/on"}
          unwrapped "https://example.com/a/one and again https://example.com/a/one"]
      (is (= "https://example.com/a/one"
             (:text (links/recover-target frag unwrapped)))))))

(deftest recovery-tolerates-nothing-to-recover-from
  (let [t {:kind :url :text "https://example.com/a"}]
    (is (= t (links/recover-target t nil)))
    (is (= t (links/recover-target t "")))
    (is (nil? (links/recover-target nil "anything")))))

;; ---------------------------------------------------------------------------
;; Underline affordance
;; ---------------------------------------------------------------------------

(defn- underlined-spans
  "The VISIBLE text of each underlined run in `decorated`.

   Strips ANSI from each capture, because a run legitimately contains escapes:
   the decorator re-asserts the underline after any escape it passes over,
   since one of them may be a full SGR reset."
  [^String decorated]
  (mapv (comp fmt/strip-ansi second)
        (re-seq (re-pattern (str (java.util.regex.Pattern/quote ansi/underline)
                                 "(.*?)"
                                 (java.util.regex.Pattern/quote ansi/underline-off)))
                decorated)))

(deftest underlines-urls-without-touching-the-filesystem
  (links/reset-decorate-cache!)
  (let [out (links/decorate-row "see https://example.com/a now" "/nonexistent")]
    (is (= ["https://example.com/a"] (underlined-spans out)))
    (testing "display width is unchanged — only escapes were added"
      (is (= (fmt/display-width "see https://example.com/a now")
             (fmt/display-width out))))))

(deftest underlines-only-files-that-exist
  (links/reset-decorate-cache!)
  (tmp-file! "here.clj" "x")
  (let [base (.getPath @tmp-root)
        out  (links/decorate-row "at here.clj:7 and gone.clj:9 end" base)]
    (is (= ["here.clj:7"] (underlined-spans out))
        "the affordance must not promise a click that will do nothing")))

(deftest a-bare-mention-of-a-file-is-not-marked
  (testing "a relative filename with no line number is usually being MENTIONED,
            not offered — marking every one turns prose into a field of
            underlines and devalues the mark where it matters"
    (links/reset-decorate-cache!)
    (tmp-file! "quiet.clj" "x")
    (let [base (.getPath @tmp-root)]
      (is (empty? (underlined-spans
                   (links/decorate-row "I edited quiet.clj for you" base)))
          "bare relative mention: resolvable, but not advertised")
      (testing "it stays CLICKABLE though — the mark is a hint, not the gate"
        (let [t (links/detect-at "I edited quiet.clj for you" 12)]
          (is (= "quiet.clj" (:path t)))
          (is (some? (links/resolve-file (:path t) base))))))))

(deftest a-location-is-marked
  (links/reset-decorate-cache!)
  (let [f    (tmp-file! "loc.clj" "x")
        base (.getPath @tmp-root)]
    (testing "carrying a line number reads as a location, not a mention"
      (is (= ["loc.clj:42"]
             (underlined-spans (links/decorate-row "at loc.clj:42 boom" base)))))
    (testing "so does an absolute path, with or without a line"
      (is (= [(.getPath f)]
             (underlined-spans
              (links/decorate-row (str "see " (.getPath f) " here") base)))))))

(deftest urls-are-always-marked
  (testing "URLs are rarer than paths and clicking is the only thing to do with
            one, so they are not subject to the path selectivity rule"
    (links/reset-decorate-cache!)
    (is (= ["https://example.com/a"]
           (underlined-spans
            (links/decorate-row "read https://example.com/a now" "/nonexistent"))))))

;; ---------------------------------------------------------------------------
;; The mark is a theme token
;; ---------------------------------------------------------------------------

(deftest the-mark-follows-the-theme-and-the-cache-follows-the-mark
  (let [saved (get (ansi/current-theme) :link/target)]
    (try
      (links/reset-decorate-cache!)
      (let [row  "go https://example.com/a"
            base "/nonexistent"
            a    (links/decorate-row row base)]
        (is (str/includes? a ansi/underline))
        (testing "rebinding changes the mark on the very next call — the memo is
                  keyed on the ROW, which does not change when a theme does"
          (ansi/set-theme! {:link/target [:italic]})
          (let [b (links/decorate-row row base)]
            (is (not= a b) "a stale cache would have returned the old escapes")
            (is (str/includes? b ansi/italic))
            (is (not (str/includes? b ansi/underline)))
            (testing "and it is ended precisely, not with a blanket reset"
              (is (str/includes? b (str ansi/esc "23m")))
              (is (not (str/includes? b ansi/reset)))))))
      (finally
        (ansi/set-theme! {:link/target saved})
        (links/reset-decorate-cache!)))))

(deftest an-unmarkable-binding-degrades-visibly-not-corruptingly
  (testing "a colour has no clean off mid-row; rather than emit a reset that
            would discard the surrounding row's styling, the mark simply does
            not end"
    (let [saved (get (ansi/current-theme) :link/target)]
      (try
        (ansi/set-theme! {:link/target [:bright-blue]})
        (links/reset-decorate-cache!)
        (is (= "" (ansi/link-mark-off))
            "no off-code for a colour — documented limitation, not a crash")
        (is (str/includes? (links/decorate-row "go https://example.com/a" "/x")
                           (ansi/link-mark-on)))
        (finally
          (ansi/set-theme! {:link/target saved})
          (links/reset-decorate-cache!))))))

(deftest decoration-inserts-between-escapes-never-inside-one
  (links/reset-decorate-cache!)
  (let [row (str ESC "[2mat " ESC "[0m" ESC "[36mhttps://example.com/a" ESC "[0m done")
        out (links/decorate-row row "/nonexistent")]
    (is (= ["https://example.com/a"] (underlined-spans out)))
    (testing "the original styling survives intact"
      (is (str/includes? out (str ESC "[36m")))
      (is (= (fmt/strip-ansi row) (fmt/strip-ansi out))
          "no visible character was added or lost"))))

(deftest an-sgr-reset-inside-a-target-does-not-drop-the-underline
  (testing "a row may reset style mid-target; the underline must be re-asserted
            or it silently stops partway through"
    (links/reset-decorate-cache!)
    (let [row (str "go https://example.com/" ESC "[0m" "a/b done")
          out (links/decorate-row row "/nonexistent")]
      (is (= ["https://example.com/a/b"] (underlined-spans out)))
      (testing "and the underline is re-opened after the reset"
        (is (str/includes? out (str ESC "[0m" ansi/underline)))))))

(deftest a-row-with-nothing-clickable-is-returned-unchanged
  (links/reset-decorate-cache!)
  (let [row "just some ordinary prose here"]
    (is (identical? row (links/decorate-row row "/nonexistent"))))
  (is (= "" (links/decorate-row "" "/nonexistent"))))

(deftest underline-is-always-closed
  (testing "an unterminated underline would bleed into every row below it,
            since SGR state survives a cursor move"
    (links/reset-decorate-cache!)
    (let [out (links/decorate-row "go https://example.com/a" "/nonexistent")]
      (is (str/ends-with? out ansi/underline-off)
          "a target running to end-of-row still closes"))))

(deftest decoration-is-memoised-on-the-row
  (links/reset-decorate-cache!)
  (let [row "see https://example.com/a"
        a   (links/decorate-row row "/nonexistent")
        b   (links/decorate-row row "/nonexistent")]
    (is (identical? a b) "the second call returns the cached string")))

;; ---------------------------------------------------------------------------
;; Editor argument conventions
;; ---------------------------------------------------------------------------

(deftest editor-line-argument-conventions
  (testing "vi/emacs/nano family take a separate +N before the path"
    (is (= ["+42" "/a/b.clj"] (links/editor-argv-suffix "vim" "/a/b.clj" 42)))
    (is (= ["+42" "/a/b.clj"] (links/editor-argv-suffix "nano" "/a/b.clj" 42)))
    (is (= ["+42" "/a/b.clj"] (links/editor-argv-suffix "less" "/a/b.clj" 42))))
  (testing "VS Code and forks want --goto path:line"
    (is (= ["--goto" "/a/b.clj:42"] (links/editor-argv-suffix "code" "/a/b.clj" 42)))
    (is (= ["--goto" "/a/b.clj:42"] (links/editor-argv-suffix "cursor" "/a/b.clj" 42))))
  (testing "the editor may be an absolute path or carry flags"
    (is (= ["--goto" "/a/b.clj:42"]
           (links/editor-argv-suffix "/usr/local/bin/code -w" "/a/b.clj" 42))))
  (testing "sublime/helix take path:line directly"
    (is (= ["/a/b.clj:42"] (links/editor-argv-suffix "subl" "/a/b.clj" 42)))
    (is (= ["/a/b.clj:42"] (links/editor-argv-suffix "hx" "/a/b.clj" 42))))
  (testing "an unknown editor gets the most widely honoured form"
    (is (= ["+42" "/a/b.clj"] (links/editor-argv-suffix "myeditor" "/a/b.clj" 42))))
  (testing "no line means just the path"
    (is (= ["/a/b.clj"] (links/editor-argv-suffix "code" "/a/b.clj" nil)))
    (is (= ["/a/b.clj"] (links/editor-argv-suffix "vim" "/a/b.clj" nil)))))

;; ---------------------------------------------------------------------------
;; format helpers this depends on
;; ---------------------------------------------------------------------------

(deftest strip-ansi-drops-osc-payloads-not-just-sgr
  (testing "SGR"
    (is (= "red/a.clj" (fmt/strip-ansi (str ESC "[31mred" ESC "[0m/a.clj")))))
  (testing "OSC-8 — the case an SGR-only regex leaves behind"
    (is (= "LABEL" (fmt/strip-ansi
                    (str ESC "]8;;https://h.example/x" BEL "LABEL" ESC "]8;;" BEL)))))
  (testing "plain text is returned untouched"
    (is (= "nothing here" (fmt/strip-ansi "nothing here")))))

(deftest column->index-steps-by-display-unit
  (testing "ASCII is 1:1"
    (is (= 0 (fmt/column->index "abcdef" 1)))
    (is (= 4 (fmt/column->index "abcdef" 5))))
  (testing "both columns of a wide glyph resolve to that glyph"
    (is (= 0 (fmt/column->index "日本ab" 1)))
    (is (= 0 (fmt/column->index "日本ab" 2)))
    (is (= 1 (fmt/column->index "日本ab" 3)))
    (is (= 2 (fmt/column->index "日本ab" 5))))
  (testing "past the end is nil, so a click on padding does nothing"
    (is (nil? (fmt/column->index "abc" 4)))
    (is (nil? (fmt/column->index "" 1)))
    (is (nil? (fmt/column->index "abc" 0)))))

;; ---------------------------------------------------------------------------
;; finish-row's TAB net
;; ---------------------------------------------------------------------------

(deftest finish-row-substitutes-a-tab-that-slipped-through
  ;; A TAB moves the cursor without WRITING the cells it skips, and `paint-row`
  ;; erases only from the cursor rightward — so the skipped cells keep whatever
  ;; the previous frame painted there. Worse, the painted-row diff compares the
  ;; row STRING, so the row then counts as unchanged and the garbage is never
  ;; repainted away. Formatters expand tabs at the source; this is the net for
  ;; a row that reaches the grid without that having happened.
  (testing "one TAB becomes exactly one space"
    (is (= "a b" (#'layout/finish-row "a\tb" 0 nil)))
    (is (= "  1  # x" (#'layout/finish-row "  1 \t# x" 0 nil))))
  (testing "the substitution is width-preserving, so it cannot overflow the
            clamp that already ran"
    (let [row "     1\t#!/usr/bin/env bash"]
      (is (= (fmt/display-width row)
             (fmt/display-width (#'layout/finish-row row 0 nil))))))
  (testing "a row with no TAB is returned untouched"
    (let [row "     1  #!/usr/bin/env bash"]
      (is (= row (#'layout/finish-row row 0 nil))))))
