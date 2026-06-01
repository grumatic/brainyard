;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: Apache-2.0
;; Licensed under the Apache License, Version 2.0 (the "License"); you may
;; not use this file except in compliance with the License. See LICENSE at
;; the repository root and CONTRIBUTING.md for contribution terms.

(ns ai.brainyard.agent.common.rlm
  "RLM quality-of-life helpers — the 5 small functions an agent following the
   chunk → map → reduce playbook needs in every run.

   Each helper is a `defcommand` so it surfaces in the unified tool registry
   and is auto-bound into the SCI sandbox (callable as `(rlm$chunk-text ...)`
   in a clojure fence). They are not new primitives — the playbook works
   without them — but they shrink the prompt by 30–40% because the LLM no
   longer has to inline equivalent helpers in every map-reduce run.

   See docs/rlm-agent-design.md §8."
  (:require [ai.brainyard.agent.core.config :as config]
            [ai.brainyard.agent.core.hooks :as hooks]
            [ai.brainyard.agent.core.protocol :as proto]
            [ai.brainyard.agent.core.tool :refer [defcommand]]
            [ai.brainyard.mulog.interface :as mulog]
            [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]))

;; ============================================================================
;; rlm$chunk-text — pure substring slicing with optional overlap
;; ============================================================================

(defn- chunk-text*
  [text size overlap]
  (cond
    (str/blank? (str text)) []
    (<= (count text) size) [text]
    :else
    (let [step (max 1 (- size overlap))]
      (loop [i 0 acc []]
        (if (>= i (count text))
          acc
          (let [end (min (count text) (+ i size))
                piece (subs text i end)]
            (if (>= end (count text))
              (conj acc piece)
              (recur (+ i step) (conj acc piece)))))))))

(defcommand rlm$chunk-text
  "Slice a string into overlapping chunks of :size chars (default 80000) with :overlap chars between adjacent chunks."
  (fn [& {:keys [text size overlap]
          :or   {size 80000 overlap 0}}]
    (cond
      (not (string? text))
      {:error "text is required (string)"}

      (or (not (integer? size)) (<= size 0))
      {:error ":size must be a positive integer"}

      (or (not (integer? overlap)) (neg? overlap))
      {:error ":overlap must be a non-negative integer"}

      (>= overlap size)
      {:error ":overlap must be < :size"}

      :else
      (let [chunks (chunk-text* text size overlap)]
        (mulog/log ::rlm.chunk-text
                   :input-chars (count text)
                   :size size
                   :overlap overlap
                   :n-chunks (count chunks))
        {:chunks chunks :n-chunks (count chunks)})))
  :input-schema  [:map
                  [:text    [:string {:desc "Input text to slice"}]]
                  [:size    {:optional true} [:int {:desc "Max chunk size in chars (default 80000)"}]]
                  [:overlap {:optional true} [:int {:desc "Chars of overlap between adjacent chunks (default 0)"}]]]
  :output-schema [:map
                  [:chunks   [:vector {:desc "Vector of chunk strings"} :string]]
                  [:n-chunks [:int {:desc "Number of chunks produced"}]]
                  [:error    [:string {:desc "Error if validation failed"}]]])

;; ============================================================================
;; rlm$chunk-files — read paths and group them into stitched chunks
;; ============================================================================

(defn- read-file-safely
  [path]
  (try
    {:path path :content (slurp (io/file path))}
    (catch Exception e
      {:path path :error (.getMessage e)})))

(defn- pack-into-chunks
  "Pack a sequence of {:path :content} maps into chunks bounded by
   :group-size (file count) AND :max-bytes (cumulative content bytes).
   Returns vector of vectors of file maps."
  [files group-size max-bytes]
  (loop [remaining files current [] current-bytes 0 acc []]
    (if (empty? remaining)
      (if (seq current) (conj acc current) acc)
      (let [{:keys [content] :as f} (first remaining)
            file-bytes (count (or content ""))
            would-overflow? (and (seq current)
                                 (or (>= (count current) group-size)
                                     (> (+ current-bytes file-bytes) max-bytes)))]
        (if would-overflow?
          (recur remaining [] 0 (conj acc current))
          (recur (rest remaining)
                 (conj current f)
                 (+ current-bytes file-bytes)
                 acc))))))

(defn- stitch-group
  [group separator]
  (->> group
       (map (fn [{:keys [path content]}]
              (str "=== " path " ===\n" content)))
       (str/join separator)))

(defcommand rlm$chunk-files
  "Read :paths and pack contents into stitched chunks bounded by :group-size files and :max-bytes per chunk; each file prefixed with === <path> === header."
  (fn [& {:keys [paths group-size max-bytes separator]
          :or   {group-size 5 max-bytes 200000 separator "\n\n---\n\n"}}]
    (cond
      (not (sequential? paths))
      {:error ":paths must be a vector of file paths"}

      (or (not (integer? group-size)) (<= group-size 0))
      {:error ":group-size must be a positive integer"}

      (or (not (integer? max-bytes)) (<= max-bytes 0))
      {:error ":max-bytes must be a positive integer"}

      :else
      (let [reads        (mapv read-file-safely paths)
            successes    (filterv :content reads)
            errors       (filterv :error reads)
            groups       (pack-into-chunks successes group-size max-bytes)
            chunks       (mapv #(stitch-group % separator) groups)]
        (mulog/log ::rlm.chunk-files
                   :n-paths (count paths)
                   :n-success (count successes)
                   :n-error (count errors)
                   :n-chunks (count chunks)
                   :group-size group-size
                   :max-bytes max-bytes)
        {:chunks chunks
         :n-chunks (count chunks)
         :errors (mapv #(select-keys % [:path :error]) errors)})))
  :input-schema  [:map
                  [:paths      [:vector {:desc "File paths to read and pack"} :string]]
                  [:group-size {:optional true} [:int {:desc "Max files per chunk (default 5)"}]]
                  [:max-bytes  {:optional true} [:int {:desc "Max cumulative content bytes per chunk (default 200000)"}]]
                  [:separator  {:optional true} [:string {:desc "Separator between files within a chunk (default \"\\n\\n---\\n\\n\")"}]]]
  :output-schema [:map
                  [:chunks   [:vector {:desc "Stitched chunk strings, file headers prefixed"} :string]]
                  [:n-chunks [:int {:desc "Number of chunks produced"}]]
                  [:errors   [:vector {:desc "Files that failed to read"} :any]]
                  [:error    [:string {:desc "Error if validation failed"}]]])

;; ============================================================================
;; rlm$parse-map-results — 3-tier fallback parser per element
;; ============================================================================

(defn- try-json [s]
  (try
    {:ok (json/read-str s :key-fn keyword)}
    (catch Exception _ nil)))

(defn- try-edn
  "EDN parse that requires the input to consume to EOF — bare tokens like
   `complete garbage` would otherwise be silently truncated to the symbol
   `complete`. We reject any input that has more than one form."
  [s]
  (try
    (let [eof (Object.)
          rdr (java.io.PushbackReader. (java.io.StringReader. s))
          v   (edn/read {:eof eof} rdr)]
      (if (identical? v eof)
        nil
        (let [more (edn/read {:eof eof} rdr)]
          (if (identical? more eof)
            {:ok v}
            nil))))
    (catch Exception _ nil)))

(defn- parse-one
  "Single-element parse with 3-tier fallback. `shape` is :json or :edn —
   determines tier-1 parser. Tier 2 always tries the other. Returns either
   the parsed value or {:parse-failed true :raw s}."
  [s shape idx]
  (let [s (str s)]
    (if (str/blank? s)
      (do (mulog/log ::rlm.fallback-parse :idx idx :tier :blank)
          {:parse-failed true :raw s})
      (let [tier-1 (case shape :edn (try-edn s) (try-json s))
            tier-2 (or tier-1 (case shape :edn (try-json s) (try-edn s)))]
        (cond
          tier-1
          (do (mulog/log ::rlm.fallback-parse :idx idx :tier 1)
              (:ok tier-1))

          tier-2
          (do (mulog/log ::rlm.fallback-parse :idx idx :tier 2)
              (:ok tier-2))

          :else
          (do (mulog/log ::rlm.fallback-parse :idx idx :tier 3)
              {:parse-failed true :raw s}))))))

(defn- split-lines-non-blank [s]
  (->> (str/split-lines (str s))
       (remove str/blank?)))

(defcommand rlm$parse-map-results
  "Parse each :results string with 3-tier fallback (tier-1 :shape parser, tier-2 the other, tier-3 mark parse-failed). :per-line splits by newline first."
  (fn [& {:keys [results shape per-line]
          :or   {shape :json per-line false}}]
    (cond
      (not (sequential? results))
      {:error ":results must be a vector of strings"}

      (and (not= shape :json) (not= shape :edn) (not= shape "json") (not= shape "edn"))
      {:error ":shape must be :json or :edn"}

      :else
      (let [shape-kw (if (#{:edn "edn"} shape) :edn :json)
            items (if per-line
                    (->> results
                         (mapcat split-lines-non-blank)
                         vec)
                    (vec results))
            parsed-with-idx
            (->> items
                 (map-indexed (fn [i s] [i (parse-one s shape-kw i)]))
                 vec)
            failed (->> parsed-with-idx
                        (filter (fn [[_ v]] (and (map? v) (:parse-failed v))))
                        (mapv (fn [[i v]] {:idx i :raw (:raw v)})))
            parsed (->> parsed-with-idx
                        (remove (fn [[_ v]] (and (map? v) (:parse-failed v))))
                        (mapv second))]
        {:parsed parsed
         :failed failed
         :n-parsed (count parsed)
         :n-failed (count failed)})))
  :input-schema  [:map
                  [:results  [:vector {:desc "Vector of strings to parse (e.g. :results from query$llm batched)"} :string]]
                  [:shape    {:optional true} [:string {:desc "Tier-1 parser: \"json\" or \"edn\" (default \"json\")"}]]
                  [:per-line {:optional true} [:string {:desc "When true, split each result by newline and parse each non-blank line (default false)"}]]]
  :output-schema [:map
                  [:parsed   [:vector {:desc "Successfully parsed values"} :any]]
                  [:failed   [:vector {:desc "Parse failures: {:idx :raw}"} :any]]
                  [:n-parsed [:int {:desc "Count of parsed values"}]]
                  [:n-failed [:int {:desc "Count of parse failures"}]]
                  [:error    [:string {:desc "Error if validation failed"}]]])

;; ============================================================================
;; rlm$reduce-counts — frequencies + percentages over a key field
;; ============================================================================

(defcommand rlm$reduce-counts
  "Aggregate parsed map rows by :key (default :category) with :count-key (default :count, falls back to 1); returns counts + percent sorted desc."
  (fn [& {:keys [parsed-results key count-key]
          :or   {key :category count-key :count}}]
    (if-not (sequential? parsed-results)
      {:error ":parsed-results must be a vector of maps"}
      (let [key-kw      (if (string? key) (keyword key) key)
            count-kw    (if (string? count-key) (keyword count-key) count-key)
            valid       (->> parsed-results
                             (filter map?)
                             (remove :parse-failed))
            groups      (group-by #(get % key-kw :_unkeyed) valid)
            tallied     (->> groups
                             (map (fn [[k rows]]
                                    {key-kw k
                                     :count (reduce + 0 (map #(or (get % count-kw) 1) rows))}))
                             (sort-by :count >)
                             vec)
            total       (reduce + 0 (map :count tallied))
            with-pct    (mapv (fn [m]
                                (assoc m :percent
                                       (if (pos? total)
                                         (double (/ (* 100.0 (:count m)) total))
                                         0.0)))
                              tallied)]
        {:counts with-pct
         :total total
         :n-categories (count with-pct)})))
  :input-schema  [:map
                  [:parsed-results [:vector {:desc "Parsed map rows (e.g. (:parsed (rlm$parse-map-results …)))"} :any]]
                  [:key            {:optional true} [:string {:desc "Group-by key (default :category)"}]]
                  [:count-key      {:optional true} [:string {:desc "Per-row count key (default :count; falls back to 1 if absent)"}]]]
  :output-schema [:map
                  [:counts       [:vector {:desc "Sorted [{<key> :count :percent} ...] desc by :count"} :any]]
                  [:total        [:int {:desc "Sum across all categories"}]]
                  [:n-categories [:int {:desc "Number of distinct keys"}]]
                  [:error        [:string {:desc "Error if validation failed"}]]])

;; ============================================================================
;; rlm$conservative-verdict — Paper-3 "any chunk says X → overall X"
;; ============================================================================

(defcommand rlm$conservative-verdict
  "Paper-3 aggregator: ANY chunk with :positive-key (default :malicious?)=true → overall verdict true; parse-failed rows skipped."
  (fn [& {:keys [parsed-results positive-key]
          :or   {positive-key :malicious?}}]
    (if-not (sequential? parsed-results)
      {:error ":parsed-results must be a vector of maps"}
      (let [pos-kw   (if (string? positive-key) (keyword positive-key) positive-key)
            valid    (filter map? parsed-results)
            skipped  (count (filter :parse-failed valid))
            counted  (remove :parse-failed valid)
            positive (filter #(true? (get % pos-kw)) counted)
            negative (remove #(true? (get % pos-kw)) counted)]
        {:verdict (boolean (seq positive))
         :positive-count (count positive)
         :negative-count (count negative)
         :skipped skipped
         :evidence (vec positive)})))
  :input-schema  [:map
                  [:parsed-results [:vector {:desc "Parsed map rows from sub-LLM map step"} :any]]
                  [:positive-key   {:optional true} [:string {:desc "Boolean field that, when true on ANY row, flips the verdict (default :malicious?)"}]]]
  :output-schema [:map
                  [:verdict        [:boolean {:desc "true iff any non-failed row reports positive-key=true"}]]
                  [:positive-count [:int {:desc "Rows that reported positive"}]]
                  [:negative-count [:int {:desc "Rows that reported negative"}]]
                  [:skipped        [:int {:desc "Parse-failed rows excluded from the vote"}]]
                  [:evidence       [:vector {:desc "The positive rows themselves, for citation"} :any]]
                  [:error          [:string {:desc "Error if validation failed"}]]])

;; ============================================================================
;; Public roster (for rlm-agent's :agent-tools)
;; ============================================================================

(def rlm-helpers
  "Vector of all rlm$* helper vars in registration order. rlm-agent appends
   these to its :agent-tools roster so the SCI sandbox auto-binds them."
  [#'rlm$chunk-text
   #'rlm$chunk-files
   #'rlm$parse-map-results
   #'rlm$reduce-counts
   #'rlm$conservative-verdict])

;; ============================================================================
;; Auto-persist + handoff
;;
;; rlm reports are large by nature; the playbook tells the LLM to write the
;; report under results/ and emit a `Saved RLM report:` line. This is the
;; safety net: when a non-trivial answer lacks that line, a gated
;; `:agent.ask/finalize` hook persists the report AND injects the line so
;; answer-grepping consumers (and the main-agent capture hook) can find it —
;; the same observe-only-gap fix used for explore/research/etc.
;; ============================================================================

(def ^:private results-base ".brainyard/agents/rlm-agent")
(def ^:private results-dir-rel (str results-base "/results"))
(def ^:private index-rel (str results-base "/INDEX.md"))
(def ^:private saved-prefix "Saved RLM report: ")
(def ^:private trivial-char-threshold
  "Reports >= this many chars auto-persist; smaller answers stay inline."
  1000)

(defn- now-ts []
  (.format (java.text.SimpleDateFormat. "yyyyMMdd-HHmmss") (java.util.Date.)))
(defn- now-iso [] (str (java.time.Instant/now)))
(defn- now-yyyy-mm-dd-hh-mm []
  (.format (java.text.SimpleDateFormat. "yyyy-MM-dd HH:mm") (java.util.Date.)))

(defn- slugify [s max-chars]
  (let [base (-> (str s) str/lower-case
                 (str/replace #"[^a-z0-9]+" "-")
                 (str/replace #"^-+|-+$" ""))
        base (if (str/blank? base) "rlm-report" base)]
    (subs base 0 (min max-chars (count base)))))

(defn- rlm-agent? [agent]
  (try (= :rlm-agent (proto/defagent-type agent)) (catch Throwable _ false)))

(defn- one-line-summary [^String answer max-chars]
  (let [line (->> (str/split-lines (str answer))
                  (map str/trim)
                  (remove str/blank?)
                  (remove #(or (str/starts-with? % "#") (str/starts-with? % "|")
                               (str/starts-with? % "```") (str/starts-with? % "---")))
                  first)
        flat (-> (or line "") (str/replace #"\s+" " ") str/trim)]
    (subs flat 0 (min max-chars (count flat)))))

(defn- materialize-rlm-report!
  "Write `answer` to results/<ts>-<slug>.md with minimal frontmatter and
   prepend an INDEX.md line. Returns {:path <rel-path> :slug …} or nil on
   failure (logged, never thrown)."
  [{:keys [answer question base-dir]}]
  (try
    (let [slug (slugify (or question "rlm-report") 60)
          ts   (now-ts)
          dir  (io/file base-dir results-dir-rel)
          _    (.mkdirs dir)
          file (io/file dir (str ts "-" slug ".md"))
          fm   (str "---\nslug: " slug
                    "\nquestion: " (pr-str (str question))
                    "\ncreated: " (now-iso)
                    "\nagent: rlm-agent\n---\n\n")]
      (spit file (str fm answer))
      (let [idx      (io/file base-dir index-rel)
            line     (str "- " (now-yyyy-mm-dd-hh-mm)
                          " [" slug "](results/" (.getName file) ") — *"
                          (one-line-summary answer 200) "*\n")
            existing (if (.isFile idx) (slurp idx) "")]
        (.mkdirs (.getParentFile idx))
        (spit idx (str line existing)))
      (mulog/log ::rlm.report-persisted :slug slug :path (.getPath file))
      {:path (str results-dir-rel "/" (.getName file)) :slug slug})
    (catch Throwable t
      (mulog/warn ::rlm.report-persist-failed :error (ex-message t))
      nil)))

(defn rlm-auto-persist
  "Gated `:agent.ask/finalize` handler. When rlm-agent emits a non-trivial
   answer without a `Saved RLM report:` line, persist the report and return a
   `:replace` decision injecting the line. Idempotent (no-op when the line is
   present or the answer is small). Defensive — failures logged, never thrown."
  [{:keys [agent input result]}]
  (try
    (when (and (rlm-agent? agent) (map? result))
      (let [answer (str (:answer result))]
        (when (and (>= (count answer) trivial-char-threshold)
                   (not (str/includes? answer saved-prefix)))
          (let [question (or (when (string? input) input)
                             (some-> input :question str)
                             "(question not captured)")
                {:keys [path]} (materialize-rlm-report!
                                {:answer answer :question question
                                 :base-dir (config/project-dir)})]
            (when path
              {:result      :replace
               :reason      "persisted RLM report + injected handoff line"
               :replacement (assoc result :answer (str answer "\n\n" saved-prefix path))})))))
    (catch Throwable t
      (mulog/error ::rlm.auto-persist-failed
                   :exception t
                   :agent-id (try (proto/agent-id agent) (catch Throwable _ "unknown")))
      nil)))

(defn install-auto-persist!
  "Register the rlm auto-persist net on the gated `:agent.ask/finalize` event,
   scoped to rlm-agent. Idempotent — register-hook! replaces by id."
  []
  (hooks/register-hook!
   :agent.ask/finalize
   ::rlm-auto-persist
   rlm-auto-persist
   :source   :rlm-agent
   :match    (fn [{:keys [agent]}] (rlm-agent? agent))
   :priority 50))

;; Self-install at namespace load.
(install-auto-persist!)
