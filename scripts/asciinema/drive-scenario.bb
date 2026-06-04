#!/usr/bin/env bb

;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

;; drive-scenario.bb — scenario parser, planner, and tmux driver for asciinema
;; tutorial recording. The shell orchestrator (record-scenario.sh) owns the
;; asciinema + tmux + by lifecycle; this script owns scenario semantics:
;;
;;   parse  <scenario.edn>                    validate against the schema (0/1)
;;   plan   <scenario.edn>                    print planned keystrokes + est.
;;   meta   <scenario.edn>                    emit shell-sourceable launch fields
;;   list                                     list scenarios + recorded status
;;   doctor                                   probe prerequisites
;;   embed                                    regenerate docs/tutorials/index.md
;;   output                                   self-contained local-view HTML
;;   drive  <scenario.edn> --tmux S --sid I   run preamble->chapters->postamble
;;   check  <scenario.edn> --frame F          assert :expect predicates vs a frame
;;
;; `drive` shells into the existing `bb tui:drive` task for each chapter prompt,
;; so settling waits on the same ~/.brainyard/sessions/<sid>/turn.complete stamp
;; the live-debugging tooling uses — no sleep-based heuristics.
(ns drive-scenario
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [cheshire.core :as json]
            [babashka.process :as proc]
            [babashka.fs :as fs]))

;; =============================================================================
;; Paths
;; =============================================================================

(def ^:private scenarios-dir "docs/tutorials/scenarios")
(def ^:private casts-dir "docs/tutorials/casts")
(def ^:private assets-dir "docs/tutorials/assets")
(def ^:private index-path "docs/tutorials/index.md")
(def ^:private output-path "docs/tutorials/output/index.html")

;; =============================================================================
;; Scenario schema / validation (narrow by design — §4)
;; =============================================================================

(def ^:private step-types #{:narrate :keys :prompt :sleep :annotate})

(defn- err [errors msg] (conj errors msg))

(defn- validate-step [errors where i step]
  (let [t (:type step)]
    (cond
      (not (map? step)) (err errors (str where " step " i " is not a map"))
      (not (contains? step-types t))
      (err errors (str where " step " i " has unknown :type " (pr-str t)
                       " (allowed: " (str/join " " (sort step-types)) ")"))
      :else
      (case t
        :narrate  (cond-> errors (not (string? (:text step)))
                          (err (str where " step " i " :narrate needs :text string")))
        :keys     (cond-> errors (not (string? (:send step)))
                          (err (str where " step " i " :keys needs :send string")))
        :sleep    (cond-> errors (not (number? (:ms step)))
                          (err (str where " step " i " :sleep needs :ms number")))
        :annotate (cond-> errors (not (string? (:text step)))
                          (err (str where " step " i " :annotate needs :text string")))
        :prompt   (cond-> errors (not (string? (:prompt step)))
                          (err (str where " step " i " :prompt needs :prompt string")))
        errors))))

(defn- validate-chapter [errors i ch]
  (let [where (str "chapter " i)]
    (cond-> errors
      (not (map? ch))               (err (str where " is not a map"))
      (not (keyword? (:id ch)))     (err (str where " needs a keyword :id"))
      (not (string? (:prompt ch)))  (err (str where " needs a :prompt string"))
      (and (:expect ch) (not (map? (:expect ch))))
      (err (str where " :expect must be a map")))))

(defn validate-scenario
  "Return a vector of human-readable error strings ([] when valid)."
  [m]
  (let [launch (:launch m)]
    (cond-> []
      (not (map? m))                  (err "scenario is not an EDN map")
      (not (string? (:id m)))         (err "missing :id (string)")
      (not (string? (:title m)))      (err "missing :title (string)")
      (not (map? launch))             (err "missing :launch map")
      (and (map? launch) (not (string? (:binary launch))))
      (err ":launch :binary must be a string (e.g. \"by\", \"bb tui\", \"bb tui:acp\")")
      (and (:terminal m) (not (map? (:terminal m))))
      (err ":terminal must be a map")
      (and (:chapters m) (empty? (:chapters m)))
      (err ":chapters is present but empty")
      (and (:workspace m) (not (map? (:workspace m))))
      (err ":workspace must be a map (e.g. {:git-init true :seed [{:path .. :content ..}]})")
      (and (:walkthrough m) (not (string? (:walkthrough m))))
      (err ":walkthrough must be a string (markdown, rendered verbatim under the player)")
      :always
      (as-> errs
            (reduce-kv (fn [e i s] (validate-step e "preamble" i s))
                       errs (vec (:preamble m)))
        (reduce-kv (fn [e i c] (validate-chapter e i c))
                   errs (vec (:chapters m)))
        (reduce-kv (fn [e i s] (validate-step e "postamble" i s))
                   errs (vec (:postamble m)))))))

(defn load-scenario
  "Read + parse + validate a scenario EDN file. Exits non-zero on any failure."
  [path]
  (when-not (fs/exists? path)
    (binding [*out* *err*] (println "ERROR: scenario not found:" path))
    (System/exit 1))
  (let [m (try (edn/read-string (slurp path))
               (catch Exception e
                 (binding [*out* *err*]
                   (println "ERROR: cannot parse" path "-" (ex-message e)))
                 (System/exit 1)))
        errors (validate-scenario m)]
    (when (seq errors)
      (binding [*out* *err*]
        (println "ERROR: invalid scenario" path)
        (doseq [e errors] (println "  -" e)))
      (System/exit 1))
    m))

;; =============================================================================
;; Planning (dry-run) — §5 --dry-run
;; =============================================================================

(defn- est-step-ms [step]
  (case (:type step)
    :narrate  (+ (or (:pause-ms step) 0) 400)   ;; type + hold + clear
    :keys     (or (:pause-ms step) 0)
    :sleep    (or (:ms step) 0)
    :annotate 0
    0))

(defn- chapter-budget-ms [ch]
  (* 1000 (or (-> ch :settle :timeout-secs) 60)))

(defn plan [scenario-path]
  (let [scn    (load-scenario scenario-path)
        launch (:launch scn)
        cmd    (str (:binary launch) " " (str/join " " (:args launch)))
        pre    (vec (:preamble scn))
        chs    (vec (:chapters scn))
        post   (vec (:postamble scn))
        est-ms (+ (* 1000 (or (:startup-timeout-secs scn) 20))
                  (reduce + (map est-step-ms pre))
                  (reduce + (map chapter-budget-ms chs))
                  (reduce + (map est-step-ms post)))]
    (println (str "Scenario: " (:id scn) " — " (:title scn)))
    (println (str "Launch:   " cmd))
    (when-let [env (:env launch)]
      (doseq [[k v] env] (println (str "  env " k "=" v))))
    (println (str "Terminal: " (:terminal scn)))
    (println)
    (println (str "Preamble (" (count pre) " step(s)):"))
    (doseq [s pre] (println "  -" (:type s) (or (:text s) (:send s) (:ms s) "")))
    (println (str "Chapters (" (count chs) "):"))
    (doseq [c chs]
      (println (str "  [" (name (:id c)) "] " (:label c)))
      (println (str "      prompt: " (pr-str (:prompt c))))
      (when (:expect c) (println (str "      expect: " (pr-str (:expect c)))))
      (println (str "      settle: " (or (-> c :settle :timeout-secs) 60) "s")))
    (println (str "Postamble (" (count post) " step(s)):"))
    (doseq [s post] (println "  -" (:type s) (or (:text s) (:send s) (:ms s) "")))
    (println)
    (println (str "Estimated worst-case runtime: ~"
                  (long (/ est-ms 1000)) "s (settle timeouts are upper bounds)"))))

;; =============================================================================
;; Driving — send keystrokes into the tmux pane / call bb tui:drive
;; =============================================================================

(defn- tmux-send-literal! [session text]
  ;; -l = literal (don't interpret key names); used for typed text.
  (proc/shell "tmux" "send-keys" "-t" session "-l" text))

(defn- tmux-send-keys! [session keyspec]
  ;; key-name mode: "C-m", "C-a", "Enter", "/quit" handled by tmux.
  (proc/shell "tmux" "send-keys" "-t" session keyspec))

(defn- backspace! [session n]
  ;; Inline-mode input has no readline editing (C-a/C-k render literally), so
  ;; clear typed text with BSpace. tmux accepts many key args in one call.
  (when (pos? n)
    (apply proc/shell "tmux" "send-keys" "-t" session (repeat n "BSpace"))))

(defn- sleep-ms [ms] (when (and ms (pos? ms)) (Thread/sleep (long ms))))

(defn- run-step! [session step]
  (case (:type step)
    ;; Type the narration so it shows on screen, hold, then backspace it out
    ;; (NOT submitted as a turn). +4 covers any stray prior residue.
    :narrate  (let [text (:text step)]
                (tmux-send-literal! session text)
                (sleep-ms (or (:pause-ms step) 1200))
                (backspace! session (+ 4 (count text))))
    :keys     (do (tmux-send-keys! session (:send step))
                  (sleep-ms (:pause-ms step)))
    :sleep    (sleep-ms (:ms step))
    :annotate nil  ;; marker handled by the recorder/post-processor, no on-screen effect
    nil))

(defn- pane-text [tmux]
  (let [{:keys [out]} (proc/shell {:out :string :err :string :continue true}
                                  "tmux" "capture-pane" "-t" tmux "-p")]
    (or out "")))

(defn- wait-for-idle!
  "After tui:drive returns, confirm the TOP-LEVEL turn is really finished by
   waiting for the status line to read `idle` (stable across two reads), not
   `running`. Necessary because a coordination turn's sub-agent (query$clone →
   explore/plan/…) advances the same session's turn.complete stamp when IT
   finishes its nested ask — which can release tui:drive before the outer turn
   ends and let the postamble /quit truncate the recording. Bounded by the
   chapter settle. Returns :idle, :timeout, or :unknown."
  [tmux timeout-secs]
  (let [deadline (+ (System/currentTimeMillis) (* 1000 (long timeout-secs)))]
    (loop [streak 0]
      (let [pane    (pane-text tmux)
            running (boolean (re-find #"running\s*│|Calculating|Reasoning…|Reflecting" pane))
            idle    (boolean (re-find #"idle\s*│" pane))]
        (cond
          (>= streak 2)                              :idle
          (> (System/currentTimeMillis) deadline)    :timeout
          :else (do (Thread/sleep 1500)
                    (recur (if (and idle (not running)) (inc streak) 0))))))))

(defn- drive-chapter! [scn ch tmux sid]
  (let [timeout (or (-> ch :settle :timeout-secs) 60)
        label   (or (:label ch) (name (:id ch)))]
    (binding [*out* *err*] (println (str ">> chapter [" (name (:id ch)) "] " label)))
    ;; Reuse the existing driver: it types the prompt, submits, and polls the
    ;; per-session turn.complete stamp until it updates or the timeout expires.
    (let [{:keys [exit]} (proc/shell {:continue true}
                                     "bb" "tui:drive"
                                     "-s" tmux "-S" sid "-T" (str timeout)
                                     (:prompt ch))]
      (when-not (zero? exit)
        (binding [*out* *err*]
          (println (str "WARN: tui:drive exit " exit " for chapter " (name (:id ch))
                        " (timeout or driver error)")))))
    ;; Belt-and-suspenders for coordination turns: the stamp can bump on a
    ;; sub-agent's completion before the outer turn ends. Wait for the pane to
    ;; actually go idle so the next chapter / postamble doesn't truncate it.
    (let [r (wait-for-idle! tmux timeout)]
      (when (= r :timeout)
        (binding [*out* *err*]
          (println (str "WARN: chapter " (name (:id ch))
                        " still not idle after " timeout "s settle")))))))

(defn drive [scenario-path opts]
  (let [scn  (load-scenario scenario-path)
        tmux (:tmux opts)
        sid  (:sid opts)]
    (when (or (str/blank? tmux) (str/blank? sid))
      (binding [*out* *err*]
        (println "ERROR: drive requires --tmux <session> and --sid <by-session-id>"))
      (System/exit 1))
    (doseq [s (:preamble scn)] (run-step! tmux s))
    (doseq [c (:chapters scn)] (drive-chapter! scn c tmux sid))
    ;; Verify captures the frame BEFORE teardown, so it skips the postamble.
    (when-not (:no-postamble opts)
      (doseq [s (:postamble scn)] (run-step! tmux s)))
    (binding [*out* *err*] (println "drive: complete"))))

;; =============================================================================
;; check — assert a scenario's :expect predicates against a captured frame
;; =============================================================================

(defn- frame-check [scn frame-text]
  (reduce
   (fn [acc ch]
     (let [cid (name (:id ch))]
       (reduce
        (fn [[ok msgs] [pred val]]
          (case pred
            :contains
            (let [missing (remove #(str/includes? frame-text %) val)]
              (if (empty? missing)
                [ok (conj msgs (str "  ✓ [" cid "] contains all " (count val) " substring(s)"))]
                [false (conj msgs (str "  ✗ [" cid "] missing: " (pr-str (vec missing))))]))
            :matches
            (if (re-find (re-pattern val) frame-text)
              [ok (conj msgs (str "  ✓ [" cid "] matches " (pr-str val)))]
              [false (conj msgs (str "  ✗ [" cid "] no match for " (pr-str val)))])
            ;; Not derivable from the rendered frame alone — note + skip.
            (:tool-called :max-tokens)
            [ok (conj msgs (str "  ⊘ [" cid "] " (name pred)
                                " not frame-checkable (skipped; needs session log)"))]
            [ok msgs]))
        acc (:expect ch))))
   [true []] (:chapters scn)))

(defn check [scenario-path opts]
  (let [scn       (load-scenario scenario-path)
        frame     (slurp (:frame opts))
        [ok msgs] (frame-check scn frame)]
    (doseq [m msgs] (println m))
    (when-not ok
      (binding [*out* *err*] (println "check: one or more expectations failed"))
      (System/exit 1))
    (println "check: all expectations satisfied")))

;; =============================================================================
;; embed — (re)generate docs/tutorials/index.md with vendored-player embeds
;; =============================================================================

(defn- read-cast-header
  "Parse the first JSON line of an asciicast file. Returns a map or nil."
  [cast-path]
  (try
    (with-open [r (clojure.java.io/reader cast-path)]
      (json/parse-string (.readLine r) true))
    (catch Exception _ nil)))

(defn- html-escape [s]
  (-> (str s)
      (str/replace "&" "&amp;") (str/replace "<" "&lt;") (str/replace ">" "&gt;")))

(defn- cast-poster-npt
  "Poster time (`npt:mm:ss`) ~80% into the recording, so the thumbnail lands on
   rendered content (the agent working / its answer) rather than the startup
   frame or — for fullscreen casts — the blank restored terminal after `/quit`."
  [cast-path]
  (try
    (let [last-t (->> (str/split-lines (slurp cast-path))
                      rest (remove str/blank?) last
                      json/parse-string first double)]
      (when (pos? last-t)
        (let [s (long (* 0.80 last-t))]
          (format "npt:%d:%02d" (quot s 60) (mod s 60)))))
    (catch Exception _ "npt:0:02")))

(defn- player-block [{:keys [id title description walkthrough version cols rows idle poster cast share-url]}]
  (str "## " (html-escape title) "\n\n"
       (when (seq description)
         (str (str/trim (str/replace description #"\s+" " ")) "\n\n"))
       ;; :walkthrough renders as markdown (NOT whitespace-collapsed like
       ;; :description), so a multi-line turn-by-turn breakdown survives. Strip
       ;; per-line leading indentation so the author can keep the EDN string
       ;; nicely indented without markdown treating it as a code block.
       (when (seq walkthrough)
         (str (->> (str/split-lines walkthrough)
                   (map str/triml)
                   (str/join "\n")
                   str/trim)
              "\n\n"))
       (if cast
         (str "<div class=\"ascii-cast\"\n"
              "     data-cast=\"casts/" id ".cast\"\n"
              "     data-cols=\"" (or cols 100) "\" data-rows=\"" (or rows 30) "\"\n"
              "     data-idle=\"" (or idle 2) "\" data-poster=\"" (or poster "npt:0:02") "\"></div>\n\n"
              "_Recorded against `by` version: `" (html-escape (or version "unknown")) "`._"
              ;; The offline player above is the default; the share link is an
              ;; opt-in extra written by `bb tutorial:upload` (cached in
              ;; casts/<id>.url).
              (when (seq share-url)
                (str " · [▶ Play on asciinema.org](" (html-escape share-url) ")")))
         ;; No cast: docs-only entry (e.g. a launcher demo the recorder can't
         ;; drive). Render the prose above and note the absence.
         "_No terminal recording for this walkthrough — see the linked guide._")
       "\n"))

(defn- index-entries
  "Vector of player entries (one per recorded scenario, sorted by id)."
  []
  (let [scns (->> (when (fs/exists? scenarios-dir) (fs/glob scenarios-dir "*.edn"))
                  (map str) sort)]
    (vec
     (for [f scns
           :let [m    (try (edn/read-string (slurp f)) (catch Exception _ nil))
                 id   (:id m)
                 cast (str casts-dir "/" id ".cast")
                 has-cast? (fs/exists? cast)]
           ;; Include every scenario with an :id. Cast-less scenarios still
           ;; render their docs (title/description/walkthrough) — just without
           ;; a player (e.g. launcher demos like `--web` that the recorder
           ;; can't drive). Cast-derived fields stay nil for those.
           :when id]
       (let [hdr     (when has-cast? (read-cast-header cast))
             url-f   (str casts-dir "/" id ".url")
             share   (when (and has-cast? (fs/exists? url-f)) (str/trim (slurp url-f)))]
         {:id id
          :cast (when has-cast? cast)
          :title (or (:title m) (:title hdr) id)
          :description (:description m)
          :walkthrough (:walkthrough m)
          :version (when has-cast? (get-in hdr [:env :BRAINYARD_VERSION]))
          :cols (or (-> m :terminal :cols) (:width hdr))
          :rows (or (-> m :terminal :rows) (:height hdr))
          :idle (or (:idle-time-limit m) (:idle_time_limit hdr))
          :poster (when has-cast? (cast-poster-npt cast))
          :share-url (when (seq share) share)})))))

(defn- build-index []
  (let [entries (index-entries)
        body
        (str "<!-- GENERATED by `bb tutorial:embed` — do not edit by hand. -->\n"
             "# Brainyard `by` — Tutorials\n\n"
             "Terminal walkthroughs of the `by` CLI, recorded with "
             "[asciinema](https://asciinema.org) and played back with a vendored, "
             "offline player (no CDN). Source scenarios live under "
             "`docs/tutorials/scenarios/`; regenerate this page with "
             "`bb tutorial:embed`.\n\n"
             ;; Vendored player assets (relative to this file).
             "<link rel=\"stylesheet\" href=\"assets/asciinema-player.css\">\n\n"
             (if (seq entries)
               (str/join "\n---\n\n" (map player-block entries))
               "_No recordings yet. Run `bb tutorial:record <id>`._\n")
             "\n\n"
             ;; One shared init script — iterates every .ascii-cast div.
             "<script src=\"assets/asciinema-player.min.js\"></script>\n"
             "<script>\n"
             "  document.querySelectorAll('.ascii-cast').forEach(function (el) {\n"
             "    AsciinemaPlayer.create(el.dataset.cast, el, {\n"
             "      cols: +el.dataset.cols, rows: +el.dataset.rows,\n"
             "      idleTimeLimit: +el.dataset.idle, fit: 'width',\n"
             "      poster: el.dataset.poster, theme: 'asciinema'\n"
             "    });\n"
             "  });\n"
             "</script>\n")]
    {:body body :count (count entries)}))

(defn embed [opts]
  (let [{:keys [body count]} (build-index)]
    (if (:check opts)
      ;; CI gate: assert the committed page matches regenerated output.
      (let [current (when (fs/exists? index-path) (slurp index-path))]
        (if (= current body)
          (println (str "embed --check: " index-path " is up to date (" count " player(s))"))
          (do (binding [*out* *err*]
                (println (str "embed --check: " index-path
                              " is STALE — run `bb tutorial:embed` and commit.")))
              (System/exit 1))))
      (do (spit index-path body)
          (binding [*out* *err*]
            (println (str "embed: wrote " index-path " (" count " player(s))")))))))

;; =============================================================================
;; output — self-contained local-view HTML (casts + player inlined; no server)
;; =============================================================================

(defn- file->b64 [path]
  (.encodeToString (java.util.Base64/getEncoder) (fs/read-all-bytes path)))

(defn- md-inline
  "Inline markdown (bold, italic, `code`) on already-HTML-escaped text."
  [s]
  (-> s
      (str/replace #"\*\*([^*]+)\*\*" "<strong>$1</strong>")
      (str/replace #"\*([^*]+)\*" "<em>$1</em>")
      (str/replace #"`([^`]+)`" "<code>$1</code>")))

(defn- md->html
  "Tiny markdown→HTML for the :walkthrough block in the output page: bold,
   italic, inline code, `- ` bullet lists, and blank-line-separated paragraphs.
   CONSECUTIVE non-blank lines join into one paragraph / list item (markdown
   soft-wrap) so an EDN string hard-wrapped for readability doesn't render as a
   stack of one-line paragraphs. Not a full parser — index.md renders the same
   markdown natively."
  [md]
  (let [flush-p  (fn [out para]    (if (seq para)    (conj out (str "<p>" (str/join " " para) "</p>")) out))
        flush-ul (fn [out bullets] (if (seq bullets) (conj out (str "<ul>" (apply str (map #(str "<li>" % "</li>") bullets)) "</ul>")) out))
        esc      (fn [s] (md-inline (html-escape s)))]
    (loop [ls (str/split-lines (str/trim (str md))), out [], para [], bullets []]
      (if (empty? ls)
        (str/join "\n" (-> out (flush-p para) (flush-ul bullets)))
        (let [l (str/trim (first ls))]
          (cond
            ;; blank line ends the current block
            (str/blank? l)
            (recur (rest ls) (-> out (flush-p para) (flush-ul bullets)) [] [])
            ;; a new bullet — flush any pending paragraph first
            (str/starts-with? l "- ")
            (recur (rest ls) (flush-p out para) [] (conj bullets (esc (subs l 2))))
            ;; non-bullet line while a list is open → lazy continuation of it
            (seq bullets)
            (recur (rest ls) out para
                   (conj (vec (butlast bullets)) (str (last bullets) " " (esc l))))
            ;; otherwise accumulate into the current paragraph
            :else
            (recur (rest ls) out (conj para (esc l)) bullets)))))))

(defn- output-player-section [{:keys [title description walkthrough version cols rows idle poster cast share-url]}]
  (str "<section class=\"tut\">\n"
       "  <h2>" (html-escape title) "</h2>\n"
       (when (seq description)
         (str "  <p class=\"desc\">"
              (html-escape (str/trim (str/replace description #"\s+" " "))) "</p>\n"))
       (when (seq walkthrough)
         (str "  <div class=\"walkthrough\">" (md->html walkthrough) "</div>\n"))
       (if cast
         (str "  <div class=\"ascii-cast\""
              " data-cols=\"" (or cols 100) "\" data-rows=\"" (or rows 30) "\""
              " data-idle=\"" (or idle 2) "\" data-poster=\"" (or poster "npt:0:02") "\""
              " data-b64=\"" (file->b64 cast) "\"></div>\n"
              "  <p class=\"meta\">Recorded against <code>by</code> "
              (html-escape (or version "?"))
              (when (seq share-url)
                (str " · <a href=\"" (html-escape share-url) "\">▶ asciinema.org</a>"))
              "</p>\n")
         "  <p class=\"meta\">No terminal recording for this walkthrough — see the guide.</p>\n")
       "</section>\n"))

(defn output [& _]
  (let [entries (index-entries)
        css     (slurp (str assets-dir "/asciinema-player.css"))
        js      (slurp (str assets-dir "/asciinema-player.min.js"))
        html (str "<!doctype html>\n<html lang=\"en\"><head>\n<meta charset=\"utf-8\">\n"
                  "<title>Brainyard by — Tutorials</title>\n"
                  "<!-- GENERATED by `bb tutorial:output` — self-contained; open directly in a browser. -->\n"
                  "<style>\n" css "\n</style>\n"
                  "<style>\n"
                  "  body{background:#0f1419;color:#cdd6f4;"
                  "font:15px/1.55 -apple-system,system-ui,sans-serif;"
                  "max-width:1100px;margin:0 auto;padding:2rem}\n"
                  "  h1{color:#89b4fa} h2{color:#a6e3a1;margin-top:2.5rem}\n"
                  "  code{color:#f9e2af} .desc{color:#bac2de}\n"
                  "  .meta{color:#7f849c;font-size:13px} a{color:#89b4fa}\n"
                  "  .tut{border-top:1px solid #313244;padding-top:.5rem}\n"
                  "  .walkthrough{color:#bac2de}\n"
                  "  .walkthrough strong,.walkthrough em{color:#cdd6f4}\n"
                  "  .walkthrough ul{padding-left:1.3rem;margin:.4rem 0}\n"
                  "  .walkthrough li{margin:.25rem 0}\n"
                  "</style>\n</head>\n<body>\n"
                  "<h1>Brainyard <code>by</code> — Tutorials</h1>\n"
                  "<p>Self-contained terminal walkthroughs — casts and player are inlined, so this "
                  "page works offline with no server. Regenerate with <code>bb tutorial:output</code>.</p>\n"
                  (str/join "\n" (map output-player-section entries))
                  "\n<script>\n" js "\n</script>\n"
                  "<script>\n"
                  "  document.querySelectorAll('.ascii-cast').forEach(function (el) {\n"
                  "    var src = 'data:text/plain;base64,' + el.dataset.b64;\n"
                  "    AsciinemaPlayer.create({url: src, parser: 'asciicast'}, el, {\n"
                  "      cols: +el.dataset.cols, rows: +el.dataset.rows,\n"
                  "      idleTimeLimit: +el.dataset.idle, fit: 'width',\n"
                  "      poster: el.dataset.poster, theme: 'asciinema'\n"
                  "    });\n"
                  "  });\n"
                  "</script>\n</body></html>\n")]
    (fs/create-dirs (fs/parent output-path))
    (spit output-path html)
    (binding [*out* *err*]
      (let [n-players (count (filter :cast entries))
            n-docs    (- (count entries) n-players)]
        (println (str "output: wrote " output-path " ("
                      (count entries) " section(s): " n-players " with a player"
                      (when (pos? n-docs) (str ", " n-docs " docs-only"))
                      ", " (long (/ (count html) 1024)) " KB, self-contained — open directly)"))))))

;; =============================================================================
;; workspace — create an isolated git-inited /tmp tree for the `by` cwd
;; =============================================================================

(defn workspace
  "Materialize the scenario's :workspace spec into a fresh /tmp directory and
   print its absolute path on stdout (record-scenario.sh captures it and passes
   it to `tmux new-session -c`, so the native `by` runs with this as its cwd —
   project-dir resolves to it, isolating all tool/agent writes from the repo).

   :workspace {:git-init true                     ; default true
               :seed [{:path \"src/cli.py\" :content \"...\"} ...]}

   The seed files are written, then (when :git-init) `git init` + an initial
   commit so the tree is clean and project-dir = git-root = this directory."
  [scenario-path]
  (let [scn (load-scenario scenario-path)
        ws  (:workspace scn)]
    (when-not (map? ws)
      (binding [*out* *err*] (println "ERROR: scenario has no :workspace map"))
      (System/exit 1))
    (let [dir (str (fs/create-temp-dir {:prefix (str "by-tut-" (:id scn) "-")}))]
      (doseq [{:keys [path content]} (:seed ws)]
        (let [f (fs/file dir path)]
          (fs/create-dirs (fs/parent f))
          (spit (str f) (or content ""))))
      (when (get ws :git-init true)
        (let [g (fn [& a] (apply proc/shell {:dir dir :out :string :err :string} "git" a))]
          (g "init" "-q")
          (g "config" "user.email" "tutorial@brainyard.local")
          (g "config" "user.name" "Brainyard Tutorial")
          (g "add" "-A")
          (g "commit" "-q" "--allow-empty" "-m" "seed tutorial workspace")))
      (println dir))))

;; =============================================================================
;; meta — emit shell-sourceable launch fields for record-scenario.sh
;; =============================================================================

(defn- sh-quote [s] (str "'" (str/replace (str s) "'" "'\\''") "'"))

(defn meta-cmd [scenario-path]
  (let [scn    (load-scenario scenario-path)
        launch (:launch scn)
        term   (:terminal scn)
        env    (:env launch)
        sid    (get env "BRAINYARD_SESSION_ID")
        exports (->> env
                     (map (fn [[k v]] (str k "=" v)))
                     (str/join " "))]
    (println (str "SCN_ID=" (sh-quote (:id scn))))
    (println (str "SCN_BINARY=" (sh-quote (:binary launch))))
    (println (str "SCN_ARGS=" (sh-quote (str/join " " (:args launch)))))
    (println (str "SCN_COLS=" (or (:cols term) 100)))
    (println (str "SCN_ROWS=" (or (:rows term) 30)))
    (println (str "SCN_TITLE=" (sh-quote (or (:title term) (:title scn) (:id scn)))))
    (println (str "SCN_IDLE=" (or (:idle-time-limit scn) 2.0)))
    (println (str "SCN_STARTUP=" (or (:startup-timeout-secs scn) 20)))
    (println (str "SCN_SID=" (sh-quote (or sid ""))))
    (println (str "SCN_WORKSPACE=" (if (:workspace scn) "1" "")))
    (println (str "SCN_ENV_EXPORTS=" (sh-quote exports)))))

;; =============================================================================
;; list / doctor
;; =============================================================================

(defn list-scenarios [& _]
  (let [files (->> (when (fs/exists? scenarios-dir)
                     (fs/glob scenarios-dir "*.edn"))
                   (map str)
                   sort)]
    (if (empty? files)
      (println "(no scenarios under" scenarios-dir ")")
      (doseq [f files]
        (let [m    (try (edn/read-string (slurp f)) (catch Exception _ nil))
              id   (or (:id m) (fs/file-name f))
              cast (str casts-dir "/" (:id m) ".cast")
              rec  (if (fs/exists? cast)
                     (str "recorded " (fs/file-name cast))
                     "not recorded")]
          (println (format "%-22s %-50s [%s]"
                           id (or (:title m) "?") rec)))))))

(defn- best-effort-version
  "Try a tool's version flag; return a one-line string or nil. BSD `script`
   has no version flag, so presence is established by `which`, not this."
  [name flag]
  (try
    (let [{:keys [exit out err]}
          (proc/shell {:out :string :err :string :continue true} name flag)]
      (when (zero? (or exit 1))
        (first (str/split-lines (str/trim (str (if (str/blank? out) err out)))))))
    (catch Exception _ nil)))

(defn doctor [& _]
  ;; Presence via `which` (robust); version is best-effort detail only.
  (let [checks [["asciinema" "--version" true]
                ["tmux"      "-V"         true]
                ["bb"        "--version"  true]
                ["script"    nil          true]   ;; BSD script has no --version
                ["by"        "--version"  false]] ;; optional: only for `by` launch mode
        results (for [[name flag required?] checks]
                  (let [present? (some? (fs/which name))]
                    [name present? required?
                     (when (and present? flag) (best-effort-version name flag))]))]
    (doseq [[name present? required? ver] results]
      (println (format "%-12s %s %s"
                       name
                       (cond present? "✓" required? "✗ MISSING" :else "○ absent (optional)")
                       (if ver (str "(" ver ")") ""))))
    (let [missing (filter (fn [[_ present? required? _]] (and required? (not present?))) results)]
      (when (seq missing)
        (binding [*out* *err*]
          (println)
          (println "Missing required:" (str/join ", " (map first missing)))
          (println "Install: brew install asciinema tmux  (script ships with macOS/Linux)")
          (println "Note: `by` is optional — only needed for scenarios with :binary \"by\" (run `bb install:ata`)."))
        (System/exit 1)))))

;; =============================================================================
;; CLI dispatch
;; =============================================================================

(defn- parse-flags [args]
  (loop [a args, m {}, pos []]
    (if (empty? a)
      [pos m]
      (let [[x & more] a]
        (case x
          "--tmux"          (recur (rest more) (assoc m :tmux (first more)) pos)
          "--sid"           (recur (rest more) (assoc m :sid (first more)) pos)
          "--frame"         (recur (rest more) (assoc m :frame (first more)) pos)
          "--no-postamble"  (recur more (assoc m :no-postamble true) pos)
          "--check"         (recur more (assoc m :check true) pos)
          (recur more m (conj pos x)))))))

(defn -main [& args]
  (let [[sub & rest-args] args
        [pos flags] (parse-flags rest-args)
        path (first pos)]
    (case sub
      "parse"  (do (load-scenario path) (println "OK:" path "is a valid scenario"))
      "meta"   (meta-cmd path)
      "workspace" (workspace path)
      "plan"   (plan path)
      "list"   (list-scenarios)
      "doctor" (doctor)
      "embed"  (embed flags)
      "output" (output)
      "drive"  (drive path flags)
      "check"  (check path flags)
      (do (binding [*out* *err*]
            (println "usage: drive-scenario.bb <parse|plan|drive|trim> <path> [flags]"))
          (System/exit 2)))))

(apply -main *command-line-args*)
