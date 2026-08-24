#!/usr/bin/env bb
;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns code-eval-stats
  "Measure how often the code channel FAILS, from the app log.

   Exists to answer one question repeatably: did a prompt change move the
   code-block failure rate? (CR-SBX-0 in
   docs/design/sandbox-surface-and-macros-design.md made kwargs the canonical
   tool-call shape and needs an after-measurement.)

   ## The counting trap this script exists to avoid

   Errors do NOT live on the `coact-agent/code-eval` event — that event carries
   only `:blocks`/`:iteration`. Error text reaches the log inside the PROVIDER
   API-CALL events (`clj-llm/openai-api-call`, `clj-llm.claude-code/cli-call`,
   `clj-llm.bedrock/bedrock-api-call`), on their `:prompt` / `:request` /
   `:messages` field — i.e. inside the outbound prompt payload.

   The prompt carries a `## Previous Iterations` section rendered from the
   agent's `:iterations` buffer, which holds the LAST 10 iterations. Every LLM
   call logs its whole prompt, so a failure is re-logged once per API call for
   as long as it stays inside that 10-iteration window — and there is more than
   one API call per turn.

   Measured on the 2026-08 logs: 462 raw error occurrences collapse to 75
   distinct failures — a 6.2x inflation. Any grep -c over this log overstates
   failures by roughly the number of subsequent LLM calls, so it overstates
   long sessions most: a failure early in a long session is replayed far more
   often than the same failure in a short one.

   So this script deduplicates on `[session-id iteration code-hash error-class]`
   before reporting, and prints the replay factor so the inflation stays
   visible rather than silently corrected.

   The DENOMINATOR needs no such treatment: `code-eval` is emitted once per
   evaluation and never appears inside a conversation dump (verified — all 487
   mentions in the 2026-08 logs are top-level events). The script re-checks this
   invariant on every run and warns if it stops holding.

   ## Usage

     bb code-eval:stats                          # whole log history
     bb code-eval:stats --split 2026-08-24       # before/after a change date
     bb code-eval:stats --since 2026-08-24       # window
     bb code-eval:stats --detail                 # list each distinct failure
     bb code-eval:stats --logs '/path/to/*.log*' # non-default log location
     bb code-eval:stats --edn                    # machine-readable

   `--split` is the CR-SBX-0 workflow: pass the date the change landed and read
   the two rate columns."
  (:require [clojure.string :as str]
            [clojure.java.io :as io]
            [clojure.pprint :as pp]))

;; ---------------------------------------------------------------------------
;; Args
;; ---------------------------------------------------------------------------

(defn- parse-args [args]
  (loop [a args m {}]
    (if (empty? a)
      m
      (let [[k & r] a]
        (case k
          "--split"  (recur (rest r) (assoc m :split (first r)))
          "--since"  (recur (rest r) (assoc m :since (first r)))
          "--until"  (recur (rest r) (assoc m :until (first r)))
          "--logs"   (recur (rest r) (assoc m :logs (first r)))
          "--detail" (recur r (assoc m :detail true))
          "--edn"    (recur r (assoc m :edn true))
          ("--help" "-h") (recur r (assoc m :help true))
          (recur r m))))))

(def ^:private default-log-glob
  (str (System/getProperty "user.home") "/.brainyard/logs/agent-tui-app.log*"))

(defn- log-files [glob-str]
  (let [g    (or glob-str default-log-glob)
        dir  (io/file (str/join "/" (butlast (str/split g #"/"))))
        pat  (last (str/split g #"/"))
        re   (re-pattern (str "^" (-> pat
                                      (str/replace "." "\\.")
                                      (str/replace "*" ".*")) "$"))]
    (->> (when (.isDirectory dir) (.listFiles dir))
         (filter #(re-matches re (.getName ^java.io.File %)))
         (sort-by #(.getName ^java.io.File %))
         vec)))

;; ---------------------------------------------------------------------------
;; Extraction
;;
;; Deliberately regex over raw text rather than reading EDN: the logs are ~150MB
;; of pretty-printed forms carrying `#mulog/flake` tagged literals, and a full
;; EDN parse costs minutes for data we sample four fields from. Every pattern
;; below is anchored to a mulog-emitted shape, not to free text.
;; ---------------------------------------------------------------------------

(def ^:private code-eval-event-re
  ;; Top-level event only. mulog pretty-prints long event names onto the line
  ;; AFTER `{:mulog/event-name`, so both spellings are matched — and both are
  ;; anchored at column 0/1 so a mention inside a nested dump cannot count.
  #"(?m)^(?:\{:mulog/event-name |\s):ai\.brainyard\.agent\.common\.coact-agent/code-eval\b")

(def ^:private any-code-eval-mention-re
  #"coact-agent/code-eval")

(def ^:private session-re #":session-id \"([^\"]+)\"")

(def ^:private timestamp-re #":mulog/timestamp-str \"(\d{4}-\d{2}-\d{2})")

(def ^:private eval-entry-re
  ;; One `:code-results` entry inside a serialized conversation dump. The
  ;; strings are escaped (\") because the dump is itself inside a string.
  #"\{:lang \\\"(\w+)\\\", :code \\\"(.*?)\\\", :result.*?:error \\\"(.*?)\\\"")

(def ^:private iteration-re #":iteration (\d+)")

(def ^:private repair-event-re
  ;; Silent code repairs, emitted by clj-sandbox's `repair-code`. These are
  ;; top-level events (one per repair, never replayed), so unlike failures they
  ;; are counted RAW. `:via` distinguishes the CoAct sequential path
  ;; (`:eval-sandbox-thunk`) from `/sandbox` + parallel blocks (`:eval-code`).
  ;;
  ;; Added 2026-08-24. Logs written before that date CANNOT contain them —
  ;; `eval-sandbox-thunk` repaired silently — so a zero here on older windows
  ;; means "not instrumented yet", not "no repairs happened". The report says so.
  ;; `[\w.-]+` (not \S+) so mulog's trailing EDN comma is not captured into the
  ;; :via value — that would break both the grouping and the CoAct marker.
  #"repaired-(escape-sequences|unclosed-delimiters)[\s\S]{0,120}?:via :([\w.-]+)")

(def ^:private macro-taught-from
  "Date `defmacro` was first taught. Before it, a zero means \"never mentioned\"."
  "2026-08-24")

(def ^:private repair-instrumented-from
  "Date the repair logging landed; before this, absence proves nothing."
  "2026-08-24")

(defn- error-class
  "Collapse an error message to its CLASS — the part that is stable across
   occurrences. Line/column numbers, paths and symbol names are stripped so
   `Unmatched delimiter: ), expected: } at [1 16]` and the same error at [3 8]
   count as one class, while remaining readable."
  [err]
  (let [e (-> (str err)
              (str/replace #"\(line \d+,? col \d+\)" "")
              (str/replace #"\[\d+ \d+\]" "")
              (str/trim))]
    (condp #(str/starts-with? %2 %1) e
      "Could not resolve symbol"       "Could not resolve symbol"
      "Unmatched delimiter"            "Unmatched delimiter"
      "Unsupported escape character"   "Unsupported escape character"
      "EOF while reading"              "EOF while reading"
      "Unexpected text on code fence"  "Unexpected text on code fence"
      "Evaluation timed out"           "Evaluation timed out"
      "Subprocess timed out"           "Subprocess timed out"
      "You emitted no action"          "No action emitted"
      "FORMAT ERROR"                   "FORMAT ERROR (provider/schema)"
      "Exit code:"                     "Exit code (shell non-zero)"
      (let [head (first (str/split e #"[:\n]"))]
        (if (str/blank? head) "(other)" (str/trim head))))))

;; Classes that indicate the MODEL wrote bad Clojure — the only ones a prompt
;; change like CR-SBX-0 can move. Everything else (shell exit codes, provider
;; format errors, timeouts) is noise for this question and is reported
;; separately.
(def ^:private syntax-classes
  #{"Unmatched delimiter" "EOF while reading" "Unsupported escape character"
    "Could not resolve symbol" "Unexpected text on code fence"})

(defn- day-of [chunk]
  (second (re-find timestamp-re chunk)))

(defn- scan-file
  "Scan one log file. Returns {:evals [day…] :failures [{…}] :raw n :mentions n}."
  [^java.io.File f]
  (let [s (slurp f)
        ;; Event boundaries: a top-level form starts at column 0 with `{`.
        chunks (str/split s #"(?m)^(?=\{:mulog/event-name)")
        acc (reduce
             (fn [acc chunk]
               (let [day (day-of chunk)
                     ev? (re-find code-eval-event-re chunk)
                     sess (or (second (re-find session-re chunk)) "?")
                     entries (re-seq eval-entry-re chunk)
                     iters (map second (re-seq iteration-re chunk))]
                 (cond-> acc
                   ev? (update :evals conj day)
                   (seq entries)
                   (as-> a
                         (reduce
                          (fn [a2 [_ lang code err]]
                            (if (str/blank? err)
                              a2
                              (-> a2
                                  (update :raw inc)
                                  (update :failures conj
                                          {:day   day
                                           :sess  sess
                                           :lang  lang
                                           :class (error-class err)
                                           :err   (subs err 0 (min 160 (count err)))
                                           :code  (subs code 0 (min 200 (count code)))
                                           ;; Iteration index disambiguates two
                                           ;; different failures in one session;
                                           ;; the code hash disambiguates two in
                                           ;; one iteration (parallel blocks).
                                           :key   [sess (first iters)
                                                   (hash (subs code 0 (min 300 (count code))))
                                                   (error-class err)]}))))
                          a entries)))))
             {:evals [] :failures [] :raw 0 :repairs [] :macros []}
             chunks)
        ;; Repairs are scanned over the whole file rather than per-chunk: they
        ;; are their own top-level events and carry their own timestamp line.
        repairs (for [chunk chunks
                      [_ kind via] (re-seq repair-event-re chunk)]
                  {:day (day-of chunk) :kind kind :via via})
        ;; Reuses `eval-entry-re` rather than a bespoke pattern: it already
        ;; extracts `:code` correctly, and — critically — it scopes the match to
        ;; code the MODEL EMITTED. A looser scan would self-trigger, because the
        ;; `:sandbox` usage guide now contains a `defmacro` example of its own
        ;; and lands in the prompt whenever it is consulted.
        macros  (for [chunk chunks
                      :let [sess (or (second (re-find session-re chunk)) "?")]
                      [_ _lang code _err] (re-seq eval-entry-re chunk)
                      :when (str/includes? code "defmacro")]
                  {:day (day-of chunk) :sess sess
                   :key [sess (hash (subs code 0 (min 300 (count code))))]
                   :code (subs code 0 (min 160 (count code)))})]
    (assoc acc :repairs (vec repairs)
           :macros (vec macros)
           :mentions (count (re-seq any-code-eval-mention-re s))
           :top-level (count (re-seq code-eval-event-re s)))))

;; ---------------------------------------------------------------------------
;; Reporting
;; ---------------------------------------------------------------------------

(defn- in-window? [day {:keys [since until]}]
  (and (or (nil? since) (nil? day) (>= (compare day since) 0))
       (or (nil? until) (nil? day) (<= (compare day until) 0))))

(defn- bucket [day split]
  (cond (nil? split) :all
        (nil? day)   :unknown
        (neg? (compare day split)) :before
        :else :after))

(defn- rate [n d] (if (zero? d) 0.0 (* 100.0 (/ (double n) d))))

(defn- summarize [evals failures]
  (let [distinct-failures (->> failures (group-by :key) vals (map first))
        by-class (frequencies (map :class distinct-failures))
        syntax   (filter #(syntax-classes (:class %)) distinct-failures)]
    {:evals (count evals)
     :raw (count failures)
     :distinct (count distinct-failures)
     :syntax (count syntax)
     :syntax-rate (rate (count syntax) (count evals))
     :by-class by-class
     :items distinct-failures}))

(defn- summarize-repairs [evals repairs]
  {:total (count repairs)
   :rate  (rate (count repairs) (count evals))
   :by-kind (frequencies (map :kind repairs))
   :by-via  (frequencies (map :via repairs))})

(defn- print-repairs
  "Report SILENT repairs — code the sandbox rewrote before evaluating. This is
   the number that decides whether the prompt's escaping rules still earn their
   place: `Unsupported escape` errors reaching the model can be 0 either because
   the problem is genuinely rare OR because every instance is repaired first."
  [{:keys [total rate by-kind by-via]} window-start]
  (println "  silent code repairs:")
  (if (and (zero? total)
           (or (nil? window-start) (neg? (compare window-start repair-instrumented-from))))
    (printf "    n/a — repair logging landed %s; earlier windows cannot contain these events%n"
            repair-instrumented-from)
    (do
      (printf "    %d repaired blocks → %.2f%% of code-evals%n" total rate)
      (doseq [[k n] (sort-by (comp - val) by-kind)]
        (printf "      %-24s %3d%n" k n))
      (doseq [[v n] (sort-by (comp - val) by-via)]
        (printf "      via %-20s %3d%s%n" v n
                (if (= v "eval-sandbox-thunk") "  <- CoAct sequential path" ""))))))

(defn- print-macros
  "Report `defmacro` ADOPTION — the evidence CR-SBX-2 is waiting on. The full
   macro design (persistence, a `macro-agent` specialist, a command family) was
   deliberately NOT built; only the teaching was. If this stays at zero, that
   decision was right and the design stays on the shelf."
  [evals macros window-start]
  (let [ds (->> macros (group-by :key) vals (map first))]
    (println "  defmacro adoption:")
    (cond
      (and (empty? ds)
           (or (nil? window-start) (neg? (compare window-start macro-taught-from))))
      (printf "    n/a — defmacro first taught %s; earlier windows predate the guide%n"
              macro-taught-from)

      (empty? ds)
      (println "    0 — capability taught but unused; CR-SBX-2 stays deferred")

      :else
      (do (printf "    %d distinct macro definitions → %.2f%% of code-evals%n"
                  (count ds) (rate (count ds) (count evals)))
          (println "    (non-zero = revisit CR-SBX-2 §4: persistence + discovery)")
          (doseq [m (take 5 ds)]
            (printf "      %s | %s%n" (or (:day m) "?")
                    (-> (:code m) (str/replace #"\\n" " ") (subs 0 (min 90 (count (:code m)))))))))))

(defn- print-block [label {:keys [evals raw distinct syntax syntax-rate by-class]}]
  (println)
  (println (str "── " label " " (apply str (repeat (max 2 (- 58 (count label))) "─"))))
  (if (zero? evals)
    (println "  no code-eval events in this window")
    (do
      (printf "  code-eval events (denominator) : %d%n" evals)
      (printf "  distinct failures              : %d  (from %d raw occurrences, %.1fx replay)%n"
              distinct raw (if (pos? distinct) (/ (double raw) distinct) 0.0))
      (printf "  MODEL-SYNTAX failures          : %d  → %.2f%% of code-evals%n"
              syntax syntax-rate)
      (println "  by class (distinct):")
      (doseq [[c n] (sort-by (comp - val) by-class)]
        (printf "    %-34s %3d  %5.2f%%%s%n"
                c n (rate n evals)
                (if (syntax-classes c) "  ← model syntax" ""))))))

(defn- print-detail [{:keys [items]}]
  (println "\n  distinct failures:")
  (doseq [{:keys [day sess lang class err code]} (sort-by (juxt :class :day) items)]
    (printf "    [%s] %-30s %s/%s%n" (or day "?") class (or day "?") (subs sess 0 (min 18 (count sess))))
    (printf "        err : %s%n" (str/replace err #"\\n" " "))
    (printf "        code: %s%n" (str/replace (str/trim code) #"\\n" " ⏎ "))))

(def ^:private baseline
  "The pre-CR-SBX-0 figures, RE-COUNTED with this script's dedup. The numbers
   quoted in revision 2 of the design doc (32 delimiter failures / 6.6%) were
   raw occurrence counts and are superseded — see the doc's §1 correction note."
  {:window "2026-08-14 → 2026-08-23" :evals 487})

(defn -main [& args]
  (let [{:keys [help split since until logs detail edn] :as opts} (parse-args args)]
    (when help
      (println (:doc (meta (the-ns 'code-eval-stats))))
      (System/exit 0))
    (let [files (log-files logs)]
      (when (empty? files)
        (println "No log files matched" (or logs default-log-glob))
        (System/exit 1))
      (println (str "Scanning " (count files) " log file(s): "
                    (str/join ", " (map #(.getName ^java.io.File %) files))))
      (let [scans (map scan-file files)
            mentions  (reduce + (map :mentions scans))
            top-level (reduce + (map :top-level scans))
            evals     (->> scans (mapcat :evals) (filter #(in-window? % opts)))
            failures  (->> scans (mapcat :failures) (filter #(in-window? (:day %) opts)))]
        ;; Denominator integrity: code-eval must never appear inside a nested
        ;; conversation dump, or the rate silently deflates.
        (when (not= mentions top-level)
          (printf "\n!! WARNING: %d code-eval mentions but %d top-level events — the denominator may now be replayed too; re-check the dedup assumptions.%n"
                  mentions top-level))
        (if edn
          (pp/pprint
           (if split
             (into {} (for [[b items] (group-by #(bucket % split) evals)]
                        [b (summarize items (filter #(= b (bucket (:day %) split)) failures))]))
             (assoc (summarize evals failures)
                    :repairs (summarize-repairs
                              evals (->> scans (mapcat :repairs)
                                         (filter #(in-window? (:day %) opts)))))))
          (if split
            (do
              (println (str "\nSplit at " split " (before = < date, after = >= date)"))
              (doseq [b [:before :after]]
                (let [e (filter #(= b (bucket % split)) evals)
                      f (filter #(= b (bucket (:day %) split)) failures)
                      r (->> scans (mapcat :repairs)
                             (filter #(in-window? (:day %) opts))
                             (filter #(= b (bucket (:day %) split))))
                      s (summarize e f)]
                  (print-block (str/upper-case (name b)) s)
                  (print-repairs (summarize-repairs e r)
                                 (if (= b :before) (:since opts) split))
                  (print-macros e
                                (->> scans (mapcat :macros)
                                     (filter #(in-window? (:day %) opts))
                                     (filter #(= b (bucket (:day %) split))))
                                (if (= b :before) (:since opts) split))
                  (when detail (print-detail s))))
              (let [bs (summarize (filter #(= :before (bucket % split)) evals)
                                  (filter #(= :before (bucket (:day %) split)) failures))
                    as (summarize (filter #(= :after (bucket % split)) evals)
                                  (filter #(= :after (bucket (:day %) split)) failures))]
                (println)
                (if (zero? (:evals as))
                  (println "VERDICT: no code-evals after the split date yet — nothing to compare.")
                  (printf "VERDICT: model-syntax failure rate %.2f%% → %.2f%% (%s)%n"
                          (:syntax-rate bs) (:syntax-rate as)
                          (let [d (- (:syntax-rate as) (:syntax-rate bs))]
                            (cond (< d -0.01) (format "down %.2f pts" (- d))
                                  (> d 0.01)  (format "UP %.2f pts" d)
                                  :else       "unchanged"))))
                ;; Thin-sample caveat, but only when there IS a sample — with
                ;; zero evals the verdict line already says so.
                (when (< 0 (:evals as) 100)
                  (printf "         (only %d code-evals after the split — treat as provisional; the baseline window had %d)%n"
                          (:evals as) (:evals baseline)))))
            (let [s (summarize evals failures)
                  reps (->> scans (mapcat :repairs)
                            (filter #(in-window? (:day %) opts)))]
              (print-block "ALL" s)
              (print-repairs (summarize-repairs evals reps) (:since opts))
              (print-macros evals
                            (->> scans (mapcat :macros)
                                 (filter #(in-window? (:day %) opts)))
                            (:since opts))
              (when detail (print-detail s)))))))))

(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
