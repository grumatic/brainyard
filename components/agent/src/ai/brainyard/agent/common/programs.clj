;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.common.programs
  "Datasets, named metrics and evaluation for predictors — the agent half of
   Phase 2 in docs/design/dspy-programming-model-proposal.md. The generic
   machinery (examples, metrics, `evaluate`) lives in clj-llm; what lives here
   needs the project: where sessions and datasets are, which metrics exist,
   and which LM a run should bill.

   Layout, under `<project>/.brainyard/programs/` (the same root the params
   files resolve from):

     <predictor-id>.edn                    params (core.predictor)
     <predictor-id>/datasets/<name>.edn    {:v 1 :predictor-id … :examples [...]}
     <predictor-id>/evals/<ts>.edn         evaluation reports

   **Datasets built from the prediction log carry SILVER labels** — the
   outputs a model actually produced, not a human's answer. That is what the
   paper's bootstrapping uses as candidate demos, and it is a fine baseline to
   measure a cheaper model's AGREEMENT against; it is not ground truth, and a
   report scored against it says \"agrees with what the logged model said\".
   The dataset records its `:label-source` so that cannot be forgotten.

   Two filters matter more than they look:

   - **Clipped records are skipped by default.** The log clips long strings;
     an example whose input was clipped asks the model a different question
     than the one its label answers.
   - **Redaction runs by default**, reusing trajectory export's scrubber:
     logged inputs are literal prompt inputs.

   Evaluation calls run under `{:suppress-log? true}` trace context so an eval
   never feeds the prediction log it was built from — otherwise a second
   dataset build would learn from the evaluated model's own outputs."
  (:require [ai.brainyard.agent.common.predictions :as predictions]
            [ai.brainyard.agent.common.trajectory-export :as traj-export]
            [ai.brainyard.agent.core.config :as config]
            [ai.brainyard.agent.core.tool :refer [defcommand]]
            [ai.brainyard.clj-llm.interface :as clj-llm]
            [ai.brainyard.mulog.interface :as mulog]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.set]
            [clojure.string :as str]
            [clojure.walk :as walk])
  (:import [java.io File]))

;; ============================================================================
;; Layout
;; ============================================================================

(defn programs-root
  "`<project>/.brainyard/programs` for the current agent's project."
  ^File []
  (io/file (str (config/project-dir)) ".brainyard" "programs"))

(def ^:private name-re #"[A-Za-z0-9_-][A-Za-z0-9_.-]*")

(defn- check-name! [kind s]
  (when-not (and (string? s) (re-matches name-re s))
    (throw (ex-info (str "Invalid " kind ": " (pr-str s)) {kind s}))))

(defn- check-predictor-id! [pid]
  ;; The id becomes a path segment sequence under programs/, so it gets the
  ;; same traversal-proof check as a params file.
  (when-not (clj-llm/valid-predictor-id? pid)
    (throw (ex-info (str "Invalid predictor id: " (pr-str pid)) {:predictor-id pid}))))

(defn dataset-file ^File [pid dataset-name]
  (check-predictor-id! pid)
  (check-name! :dataset dataset-name)
  (io/file (programs-root) (str pid) "datasets" (str dataset-name ".edn")))

(defn evals-dir ^File [pid]
  (check-predictor-id! pid)
  (io/file (programs-root) (str pid) "evals"))

(defn list-datasets [pid]
  (let [dir (.getParentFile (dataset-file pid "x"))]
    (if (.isDirectory dir)
      (->> (.listFiles dir)
           (map #(.getName ^File %))
           (filter #(str/ends-with? % ".edn"))
           (map #(subs % 0 (- (count %) 4)))
           sort vec)
      [])))

;; ============================================================================
;; Dataset construction
;; ============================================================================

(def ^:private clip-marker "…[truncated ")

(defn- clipped? [v]
  (let [hit (volatile! false)]
    (walk/postwalk (fn [x]
                     (when (and (string? x) (str/includes? x clip-marker))
                       (vreset! hit true))
                     x)
                   v)
    @hit))

(defn list-prediction-sessions
  "Session ids under the sessions root that have a predictions log."
  []
  (let [^File root (io/file (str (config/sessions-root)))]
    (if-not (.isDirectory root)
      []
      (->> (.listFiles root)
           (filter #(.isDirectory ^File %))
           (map #(.getName ^File %))
           (filter #(.isFile (predictions/session-predictions-file %)))
           sort vec))))

(defn records->examples
  "Examples from prediction-log records for one predictor. Returns
   {:examples [...] :skipped {:error n :invalid n :clipped n :duplicate n}}.

   Inputs are deduplicated (first occurrence wins, i.e. oldest) — the same
   question asked twice is one example, and counting it twice weights a
   metric toward whatever gets repeated."
  [records {:keys [skip-clipped? redact? max-examples]
            :or   {skip-clipped? true redact? true}}]
  (loop [[r & more] records
         seen #{}
         out []
         skipped {:error 0 :invalid 0 :clipped 0 :duplicate 0}]
    (cond
      (or (nil? r) (and max-examples (>= (count out) max-examples)))
      {:examples out :skipped skipped}

      (:error r)            (recur more seen out (update skipped :error inc))
      (false? (:valid? r))  (recur more seen out (update skipped :invalid inc))
      (and skip-clipped? (or (clipped? (:inputs r)) (clipped? (:outputs r))))
      (recur more seen out (update skipped :clipped inc))
      (contains? seen (:inputs r)) (recur more seen out (update skipped :duplicate inc))

      :else
      (let [ex (clj-llm/example (:inputs r) (:outputs r)
                                (cond-> {:session (:session r) :ts (:ts r)}
                                  (:model r) (assoc :model (:model r))))]
        (recur more (conj seen (:inputs r))
               (conj out (if redact? (traj-export/redact-example ex) ex))
               skipped)))))

(defn build-dataset
  "Build and write a dataset for `pid` from the prediction logs of `session-ids`
   (default: every session with a log). Returns a summary map."
  [pid dataset-name & {:keys [session-ids] :as opts}]
  (let [sids    (or (seq session-ids) (list-prediction-sessions))
        records (mapcat #(predictions/read-predictions % pid) sids)
        {:keys [examples skipped]} (records->examples records opts)
        ^File f (dataset-file pid dataset-name)
        data    {:v 1
                 :predictor-id pid
                 :created (System/currentTimeMillis)
                 :label-source :prediction-log
                 :sessions (vec sids)
                 :examples examples}]
    (when (empty? examples)
      (throw (ex-info (str "No usable prediction records for " pid)
                      {:sessions (count sids) :records (count records) :skipped skipped})))
    (.mkdirs (.getParentFile f))
    (spit f (pr-str data))
    {:path (.getPath f) :examples (count examples) :records (count records)
     :sessions (count sids) :skipped skipped :dataset-hash (clj-llm/dataset-hash examples)}))

(defn load-dataset
  "The dataset map for `pid`/`dataset-name`; throws when absent."
  [pid dataset-name]
  (let [^File f (dataset-file pid dataset-name)]
    (when-not (.isFile f)
      (throw (ex-info (str "No dataset '" dataset-name "' for " pid
                           " (have: " (str/join ", " (list-datasets pid)) ")")
                      {:path (.getPath f)})))
    (edn/read-string (slurp f))))

;; ============================================================================
;; Named metrics
;; ============================================================================

(defn- lc [s] (str/lower-case (str/trim (str s))))

(defn- f1 [tp n-pred n-gold]
  (cond
    (and (zero? n-pred) (zero? n-gold)) 1.0
    (or (zero? tp) (zero? n-pred) (zero? n-gold)) 0.0
    :else (let [p (/ tp (double n-pred)) r (/ tp (double n-gold))]
            (/ (* 2 p r) (+ p r)))))

(defn- entity-names [e]
  (into #{(lc (:name e))} (map lc) (:aliases e)))

(defn graph-extract-score
  "Alias-aware graph F1. An entity counts as found when any of its name or
   aliases matches any of a gold entity's — a live run extracted
   `native-image` with alias `GraalVM native-image`, which exact name matching
   scored as a miss and a hallucination at once. Each gold entity matches at
   most one prediction. Relations compare [src relation dst] after mapping
   predicted endpoint names onto the gold entity they matched, so a relation
   is not failed for naming the entity by its alias. Mean of the two F1s."
  [{:keys [labels]} result _trace]
  (let [gold  (vec (:entities labels))
        pred  (vec (get-in result [:outputs :entities]))
        ;; greedy one-to-one matching: pred index → gold canonical name
        match (loop [[[pi pe] & more] (map-indexed vector pred)
                     used #{}
                     acc {}]
                (if-not pe
                  acc
                  (if-let [gi (first (keep-indexed
                                      (fn [gi ge]
                                        (when (and (not (used gi))
                                                   (seq (clojure.set/intersection
                                                         (entity-names pe) (entity-names ge))))
                                          gi))
                                      gold))]
                    (recur more (conj used gi) (assoc acc pi (lc (:name (nth gold gi)))))
                    (recur more used acc))))
        canon (into {} (mapcat (fn [[pi gname]] (map #(vector % gname) (entity-names (nth pred pi)))))
                    match)
        rel-key (fn [names r] [(get names (lc (:src r)) (lc (:src r)))
                               (lc (:relation r))
                               (get names (lc (:dst r)) (lc (:dst r)))])
        gold-rels (set (map #(rel-key {} %) (:relations labels)))
        pred-rels (set (map #(rel-key canon %) (get-in result [:outputs :relations])))]
    (/ (+ (f1 (count match) (count pred) (count gold))
          (f1 (count (clojure.set/intersection gold-rels pred-rels)) (count pred-rels) (count gold-rels)))
       2.0)))

(def metrics
  "Metric name → {:doc … :make (fn [] metric)}. Named so a command (and a
   report) can refer to one; a Clojure caller can pass any metric fn."
  {"exact-match"      {:doc  "Every labelled field equals the output (normalized)."
                       :make clj-llm/metric-exact-match}
   "schema-valid"     {:doc  "Outputs were schema-valid."
                       :make clj-llm/metric-schema-valid}
   "graph-extract-f1" {:doc  "Mean of entity F1 (name/alias-aware) and relation F1 (src/relation/dst), case-insensitive."
                       :make (constantly graph-extract-score)}})

;; ============================================================================
;; Evaluation
;; ============================================================================

(defn- resolve-eval-lm
  "Explicit label > tier > the agent's sub-LM. The sub-LM default matters: a
   predictor with no params and no tier would otherwise run on the process
   default LM, which inside an agent is often unset."
  [{:keys [lm tier]}]
  (cond
    (not (str/blank? (str lm)))
    (or (clj-llm/parse-lm-str lm)
        (throw (ex-info (str "Unresolvable :lm " (pr-str lm)) {:lm lm})))

    (not (str/blank? (str tier)))
    (or (config/resolve-tier-lm (keyword (str/replace (str tier) #"^:" "")))
        (throw (ex-info (str "Tier " tier " has no model in :agent-lm-tiers") {:tier tier})))

    :else (config/resolve-sub-lm)))

(defn- select-split [examples split]
  (case (some-> split name)
    (nil "all") examples
    ("train" "val" "test") (get (clj-llm/split-examples examples {}) (keyword (name split)))
    (throw (ex-info (str "Unknown split " (pr-str split) " — all|train|val|test") {:split split}))))

(defn- summarize-report
  "The report minus per-example outputs — small enough to return to an LLM."
  [report]
  (cond-> (-> report
              (dissoc :per-example)
              (assoc :worst (->> (:per-example report)
                                 (sort-by :score)
                                 (take 5)
                                 (mapv #(select-keys % [:index :score :error :error-class])))))
    ;; With repeats, name the examples whose score moves the most between
    ;; passes — where the noise comes from, and the first labels to re-check.
    (> (or (:repeats report) 1) 1)
    (assoc :least-stable (->> (:per-example report)
                              (filter #(pos? (:spread % 0)))
                              (sort-by :spread >)
                              (take 5)
                              (mapv #(select-keys % [:index :score :scores :spread]))))))

(defn eval-predictor
  "Evaluate predictor `pid` on a dataset with a named metric; writes the full
   report under evals/ and returns {:report-path … :summary …}."
  [pid dataset-name metric-name & {:keys [split budget-usd parallel repeats max-calls max-tokens]
                                   :or   {budget-usd 1.0 parallel 2 repeats 1}
                                   :as opts}]
  (let [p       (or (clj-llm/get-predictor pid)
                    (throw (ex-info (str "Unknown predictor " pid " (registered: "
                                         (str/join ", " (map :predictor/id (clj-llm/list-predictors))) ")")
                                    {:predictor-id pid})))
        mdef    (or (get metrics metric-name)
                    (throw (ex-info (str "Unknown metric " (pr-str metric-name) " — "
                                         (str/join ", " (sort (keys metrics))))
                                    {:metric metric-name})))
        dataset (load-dataset pid dataset-name)
        exs     (select-split (:examples dataset) split)
        _       (when (empty? exs)
                  (throw (ex-info (str "Split " split " of " dataset-name " is empty") {})))
        lm      (resolve-eval-lm opts)
        report  (clj-llm/with-trace-context {:suppress-log? true}
                  (clj-llm/evaluate (clj-llm/predictor-program p :lm-config lm)
                                    exs ((:make mdef))
                                    :parallel parallel :repeats repeats :budget-usd budget-usd
                                    :max-calls max-calls :max-tokens max-tokens))
        full    (assoc report
                       :predictor-id pid
                       :dataset dataset-name
                       :dataset-hash (:dataset report)
                       :label-source (:label-source dataset)
                       :split (or (some-> split name) "all")
                       :metric metric-name
                       :model (clj-llm/format-lm-label (:provider lm) (:model lm))
                       :params-source (:source (clj-llm/resolve-params p))
                       :budget-usd budget-usd
                       :ts (System/currentTimeMillis))
        ^File f (io/file (evals-dir pid) (str (:ts full) ".edn"))]
    (.mkdirs (.getParentFile f))
    (spit f (pr-str full))
    (mulog/info ::evaluated :predictor-id pid :dataset dataset-name :metric metric-name
                :score (:score full) :cost (:cost full) :stopped (:stopped full))
    {:report-path (.getPath f) :summary (summarize-report (dissoc full :dataset))}))

;; ============================================================================
;; Commands
;; ============================================================================

(defn- non-blank [s] (when (and (string? s) (not (str/blank? s))) s))

(defcommand program$list
  "List named predictors with their params source and datasets."
  (fn [& _]
    {:predictors
     (mapv (fn [p]
             (let [pid (:predictor/id p)]
               {:id        pid
                :signature (get-in p [:signature :name])
                :strategy  (name (:strategy p))
                :params    (some-> (:source (clj-llm/resolve-params p)) str)
                :datasets  (list-datasets pid)}))
           (clj-llm/list-predictors))
     :metrics (into (sorted-map) (update-vals metrics :doc))})
  :input-schema  [:map]
  :output-schema [:map
                  [:predictors [:vector {:desc "{:id :signature :strategy :params :datasets}"} :any]]
                  [:metrics    [:map-of {:desc "Named metrics → description"} :string :string]]])

(defcommand program$build-dataset
  "Build a predictor dataset from session prediction logs (silver labels; redacted; clipped records skipped)."
  (fn [& {:keys [predictor-id name session-id all max-examples skip-clipped redact]}]
    (try
      (build-dataset predictor-id name
                     :session-ids (cond all nil
                                        (non-blank session-id) [session-id]
                                        :else nil)
                     :max-examples max-examples
                     :skip-clipped? (if (some? skip-clipped) (boolean skip-clipped) true)
                     :redact? (if (some? redact) (boolean redact) true))
      (catch Exception e
        {:error (ex-message e) :detail (some-> (ex-data e) (select-keys [:sessions :records :skipped]))})))
  :input-schema  [:map
                  [:predictor-id [:string {:desc "Predictor id, e.g. memory/graph-extract"}]]
                  [:name         [:string {:desc "Dataset name"}]]
                  [:session-id   {:optional true} [:string {:desc "One session's log (default: all sessions)"}]]
                  [:all          {:optional true} [:boolean {:desc "Use every session with a log (default)"}]]
                  [:max-examples {:optional true} [:int {:desc "Cap on examples"}]]
                  [:skip-clipped {:optional true} [:boolean {:desc "Skip records with clipped strings (default true)"}]]
                  [:redact       {:optional true} [:boolean {:desc "Scrub secret-like tokens (default true)"}]]]
  :output-schema [:map
                  [:path         {:optional true} [:string {:desc "Dataset file"}]]
                  [:examples     {:optional true} [:int {:desc "Examples written"}]]
                  [:records      {:optional true} [:int {:desc "Log records read"}]]
                  [:sessions     {:optional true} [:int {:desc "Sessions read"}]]
                  [:skipped      {:optional true} [:map-of {:desc "Skip counts by reason"} :keyword :int]]
                  [:dataset-hash {:optional true} [:string {:desc "Content hash"}]]
                  [:error        {:optional true} [:string {:desc "Error message"}]]
                  [:detail       {:optional true} [:map {:desc "Error context"}]]])

(defcommand program$eval
  "Evaluate a predictor on a dataset with a named metric under a spend cap; writes a report."
  (fn [& {:keys [predictor-id dataset metric split budget-usd parallel lm tier repeats max-calls max-tokens]}]
    (try
      (eval-predictor predictor-id dataset (or (non-blank metric) "exact-match")
                      :split split
                      :budget-usd (or budget-usd 1.0)
                      :parallel (or parallel 2)
                      :repeats (or repeats 1)
                      :max-calls max-calls :max-tokens max-tokens
                      :lm lm :tier tier)
      (catch Exception e
        {:error (ex-message e)})))
  :input-schema  [:map
                  [:predictor-id [:string {:desc "Predictor id"}]]
                  [:dataset      [:string {:desc "Dataset name"}]]
                  [:metric       {:optional true} [:string {:desc "Metric name (program$list); default exact-match"}]]
                  [:split        {:optional true} [:string {:desc "all (default) | train | val | test"}]]
                  [:budget-usd   {:optional true} [:double {:desc "Spend cap in USD (default 1.0)"}]]
                  [:parallel     {:optional true} [:int {:desc "Worker threads (default 2)"}]]
                  [:lm           {:optional true} [:string {:desc "provider/model to evaluate (default: sub-LM)"}]]
                  [:tier         {:optional true} [:string {:desc "light | standard | deep (via :agent-lm-tiers)"}]]
                  [:repeats      {:optional true} [:int {:desc "Passes over the set; ≥2 reports stddev and a 95% interval (default 1)"}]]
                  [:max-calls    {:optional true} [:int {:desc "Cap on predictor calls"}]]
                  [:max-tokens   {:optional true} [:int {:desc "Cap on input+output tokens"}]]]
  :output-schema [:map
                  [:report-path {:optional true} [:string {:desc "Full report file"}]]
                  [:summary     {:optional true} [:map {:desc "Score, cost, tokens, stopped, worst examples"}]]
                  [:error       {:optional true} [:string {:desc "Error message"}]]])

;; ============================================================================
;; Compile → proposal → review → accept
;; ============================================================================
;;
;; `compile-predictor` never touches the params file a predictor resolves. It
;; writes a PROPOSAL:
;;
;;   <id>/proposals/<ts>/params.edn   the candidate params record
;;   <id>/proposals/<ts>/report.edn   optimizer report + leaderboard
;;   <id>/proposals/<ts>/REVIEW.md    what a human reads: scores, cost, and the
;;                                    exact system-prompt text the demos become
;;   <id>/proposals/<ts>/status.edn   {:status :pending|:accepted|:rejected}
;;
;; Accepting is deliberately NOT an LLM tool (only `accept-proposal!` and the
;; `by programs accept` CLI). A proposal's demos are text harvested from what a
;; teacher read, destined for a system message; an agent that could accept its
;; own proposal would be choosing its own future instructions.

(def optimizers #{"labeled-few-shot" "bootstrap-few-shot" "bootstrap-random-search"})

(defn proposals-dir ^File [pid]
  (check-predictor-id! pid)
  (io/file (programs-root) (str pid) "proposals"))

(defn- proposal-dir ^File [pid proposal-id]
  (check-name! :proposal proposal-id)
  (io/file (proposals-dir pid) (str proposal-id)))

(defn params-file-for
  "The PROJECT params file for pid — where an accepted proposal lands."
  ^File [pid]
  (check-predictor-id! pid)
  (io/file (programs-root) (str pid ".edn")))

(defn- lm-label [lm]
  (when lm (clj-llm/format-lm-label (:provider lm) (:model lm))))

(defn- resolve-teacher-lm
  "Explicit :teacher-lm > :teacher-tier > the :deep tier when configured > the
   student's LM. Falling back to the student is allowed but REPORTED — a
   teacher that is the student bootstraps only what the student already does."
  [{:keys [teacher-lm teacher-tier]} student-lm]
  (cond
    (not (str/blank? (str teacher-lm))) (resolve-eval-lm {:lm teacher-lm})
    (not (str/blank? (str teacher-tier))) (resolve-eval-lm {:tier teacher-tier})
    :else (or (config/resolve-tier-lm :deep) student-lm)))

(defn- clip-for-review [s n]
  (let [s (str s)]
    (if (> (count s) n) (str (subs s 0 n) "…") s)))

(defn render-review
  "The REVIEW.md dossier. The demos section is `render-demos` over the params'
   signature — the literal text the proposal adds to the system message, not a
   summary of it."
  [p params report meta]
  (let [sig    (clj-llm/with-instructions (:signature p) (or (:instructions params)
                                                             (get-in p [:signature :instructions])))
        demos  (:demos params)
        rendered (clj-llm/render-demos sig
                                       (mapv #(update % :inputs update-vals
                                                      (fn [v] (if (string? v) (clip-for-review v 2000) v)))
                                             demos)
                                       {:chain-of-thought? (= :cot (:strategy p))})
        board  (:leaderboard report)]
    (str "# Proposal " (:proposal-id meta) " — " (:predictor/id p) "\n\n"
         "- optimizer: `" (:optimizer meta) "`  metric: `" (:metric meta) "`\n"
         "- dataset: `" (:dataset meta) "` (" (:dataset-hash meta) ", labels: " (name (or (:label-source meta) :unknown)) ")\n"
         "- student: `" (:student meta) "`  teacher: `" (:teacher meta) "`"
         (when (= (:student meta) (:teacher meta)) "  ⚠ teacher is the student")
         "\n"
         "- cost: $" (format "%.4f" (double (or (:cost report) 0.0)))
         (when (:calls report) (str " · " (:calls report) " calls"))
         (when (:tokens report) (str " · " (:tokens report) " tokens"))
         (when (:stopped report) (str "  stopped: `" (name (:stopped report)) "`"))
         "\n"
         (when-let [ps (seq (keep identity (get-in report [:pool :scores])))]
           (str "- teacher on train: " (get-in report [:pool :passed]) "/" (get-in report [:pool :attempted])
                " passed the threshold · scores " (str/join " " (map #(format "%.2f" (double %)) ps)) "\n"))
         "- recommendation: "
         (cond (:no-op? meta) (str "**no demos** — zero-shot scored best; accepting installs "
                                   (if (or (:instructions params) (seq (:field-descs params)))
                                     "the instructions/field-desc override below, with no demos"
                                     "empty params")
                                   (when (:candidate-file? meta) " (the losing candidate is in candidate.edn)"))
               :else (str "**" (some-> (:best report) name) "** at " (:best-score report)
                          (when-let [z (:zero-shot-score meta)] (str " (zero-shot " z ")"))))
         (when-let [t (:teacher-score report)]
           (str " · teacher zero-shot " (format "%.3f" (double t))))
         "\n\n"
         (when (seq board)
           (str "## Leaderboard (valset"
                (when (> (or (:repeats report) 1) 1) (str ", " (:repeats report) " passes per row"))
                ")\n\n| candidate | score | ± sd | vs zero-shot | examples | calls | tokens | cost | stopped |\n|---|---|---|---|---|---|---|---|---|\n"
                (str/join "\n" (for [{:keys [candidate score stddev vs-baseline attempted n cost calls tokens stopped reference?]} board]
                                 (format "| %s | %.3f | %s | %s | %d/%d | %s | %s | $%.4f | %s |"
                                         (str (name candidate) (when reference? " _(teacher, reference — not eligible)_"))
                                         (double score)
                                         (if stddev (format "%.3f" (double stddev)) "—")
                                         (if-let [{:keys [better? diff margin tested?]} vs-baseline]
                                           (format "%s %+.3f%s" (if better? "✅" "✗") (double diff)
                                                   (if tested? (format " (needs >%.3f)" (double margin)) ""))
                                           "")
                                         attempted n (or calls "") (or tokens "") (double cost)
                                         (or (some-> stopped name) ""))))
                "\n\n"
                (if (> (or (:repeats report) 1) 1)
                  "_A candidate wins only if it beats zero-shot by more than the combined run-to-run noise (Welch t, 95%)._\n\n"
                  "_Single pass per row: differences are NOT tested against noise — rerun with `:repeats 3` before trusting a small gap._\n\n")
                (when (some #(and (:stopped %) (not (:reference? %))) board)
                  "_Rows marked stopped were cut by the budget and are not eligible to win._\n\n")))
         (when (:instructions params)
           (str "## Instructions override\n\n```\n" (:instructions params) "\n```\n\n"))
         "## Demos — exact system-prompt text (" (count demos) ")\n\n"
         (if rendered (str "```\n" rendered "\n```\n") "_none_\n")
         "\n## Review checklist\n\n"
         "- [ ] No secrets, personal data or instructions hidden in demo inputs\n"
         "- [ ] Demo outputs are answers you would want imitated\n"
         "- [ ] Score gain over zero-shot is worth the added prompt tokens\n\n"
         "Accept: `by programs accept " (:predictor/id p) " " (:proposal-id meta) "`  "
         "Reject: `by programs reject " (:predictor/id p) " " (:proposal-id meta) "`\n")))

(defn compile-predictor
  "Run an optimizer for `pid` over a dataset and write a proposal. Uses the
   dataset's :train split to bootstrap and :val to select; :test is untouched.

   opts: :optimizer (default bootstrap-random-search) :metric (exact-match)
         :lm/:tier (student; default sub-LM) :teacher-lm/:teacher-tier
         (default :deep tier) :budget-usd (2.0) :trials (6)
         :max-bootstrapped (4) :parallel (2) :threshold (1.0) :seed (0)
         :max-calls (nil — cap on predictor calls, the budget that binds on
         subscription providers whose USD is notional)
         :max-tokens (nil — cap on input+output tokens across the whole run)
         :teacher-baseline? (true — score the teacher zero-shot on val as a
         reference row)"
  [pid dataset-name & {:keys [optimizer metric budget-usd trials max-bootstrapped
                              parallel threshold seed max-calls max-tokens teacher-baseline? repeats]
                       :or   {optimizer "bootstrap-random-search" metric "exact-match"
                              budget-usd 2.0 trials 6 max-bootstrapped 4 parallel 2
                              threshold 1.0 seed 0 teacher-baseline? true repeats 1}
                       :as   opts}]
  (let [p        (or (clj-llm/get-predictor pid)
                     (throw (ex-info (str "Unknown predictor " pid) {:predictor-id pid})))
        _        (when-not (optimizers optimizer)
                   (throw (ex-info (str "Unknown optimizer " (pr-str optimizer) " — "
                                        (str/join ", " (sort optimizers))) {})))
        mdef     (or (get metrics metric)
                     (throw (ex-info (str "Unknown metric " (pr-str metric)) {})))
        dataset  (load-dataset pid dataset-name)
        {:keys [train val]} (clj-llm/split-examples (:examples dataset) {})
        _        (when (empty? train)
                   (throw (ex-info "Dataset train split is empty — add examples" {})))
        _        (when (and (= optimizer "bootstrap-random-search") (empty? val))
                   (throw (ex-info "Dataset val split is empty — random search needs a valset" {})))
        m        ((:make mdef))
        student-lm (resolve-eval-lm opts)
        teacher-lm (resolve-teacher-lm opts student-lm)
        student  (clj-llm/predictor-program p :lm-config student-lm)
        teacher  (clj-llm/predictor-program p :lm-config teacher-lm)
        ;; Demos are compiled FOR a prompt. The instructions/field descs the
        ;; predictor currently resolves are laid under every candidate, the
        ;; teacher and the proposal — otherwise a params record whose
        ;; :instructions is being tried would be silently replaced by each
        ;; candidate's demos-only record, and accepting would drop it.
        base     (select-keys (:params (clj-llm/resolve-params p)) [:instructions :field-descs])
        common   {:max-bootstrapped max-bootstrapped :predictor-id pid :seed seed
                  :threshold threshold :budget-usd budget-usd :parallel parallel
                  :max-calls max-calls :max-tokens max-tokens :repeats repeats
                  :teacher-baseline? teacher-baseline?
                  :teacher-params {pid base}
                  :base-params {pid base}}
        {:keys [params report]}
        (clj-llm/with-trace-context {:suppress-log? true}
          (case optimizer
            "labeled-few-shot"
            (clj-llm/optimize-labeled-few-shot pid train {:k max-bootstrapped :seed seed})
            "bootstrap-few-shot"
            (clj-llm/optimize-bootstrap-few-shot teacher train m common)
            "bootstrap-random-search"
            (clj-llm/optimize-bootstrap-random-search teacher student train val m
                                                      (assoc common :trials trials))))
        params   (update params pid #(merge {} base %))
        ;; Search optimizers select on val themselves; the others propose
        ;; blind, so score their proposal against zero-shot here — every
        ;; REVIEW.md then answers the same question: did it beat no demos?
        report   (if (or (:leaderboard report) (empty? val))
                   report
                   (clj-llm/with-trace-context {:suppress-log? true}
                     (let [spent  (atom (or (:cost report) 0.0))
                           calls  (atom (or (:calls report) 0))
                           tokens (atom (or (:tokens report) 0))
                           row-tokens (fn [r] (+ (get-in r [:tokens :in] 0) (get-in r [:tokens :out] 0)))
                           eval-row (fn [prog params]
                                      (let [r (clj-llm/with-params params
                                                (clj-llm/evaluate prog val m :parallel parallel :repeats repeats
                                                                  :budget-usd (max 0.0 (- budget-usd @spent))
                                                                  :max-calls (when max-calls (max 0 (- max-calls @calls)))
                                                                  :max-tokens (when max-tokens (max 0 (- max-tokens @tokens)))))]
                                        (swap! spent + (:cost r))
                                        (swap! calls + (:calls r))
                                        (swap! tokens + (row-tokens r))
                                        r))
                           z   (eval-row student {pid base})
                           t   (when teacher-baseline? (eval-row teacher {pid base}))
                           c   (eval-row student {pid (get params pid base)})
                           row (fn [cname r] (merge {:candidate cname :tokens (row-tokens r)}
                                                    (select-keys r clj-llm/leaderboard-row-keys)))
                           ok? #(and (nil? (:stopped %)) (= (:attempted %) (:n %)))
                           ;; same rule as random search: beat zero-shot beyond noise
                           [win board] (clj-llm/select-best
                                        (cond-> [(row :zero-shot z)]
                                          t (conj (assoc (row :teacher t) :reference? true))
                                          true (conj (row :proposal c))))]
                       (assoc report
                              :leaderboard board
                              :best (:candidate win)
                              :best-score (:score win)
                              :repeats repeats
                              :teacher-score (when (and t (ok? t)) (:score t))
                              :valset (clj-llm/dataset-hash val)
                              :cost @spent
                              :calls @calls
                              :tokens @tokens))))
        ;; params.edn is always the WINNER, so accepting does what the
        ;; leaderboard says. A blind candidate that lost to zero-shot is kept
        ;; as candidate.edn for the reviewer, never as what accept installs.
        ;; nil best = even the zero-shot baseline did not finish (budget):
        ;; nothing was validated, so nothing but the base may be proposed.
        ;; `contains?`, never `(#{:zero-shot nil} best)`: a set called as a
        ;; function returns the MATCHED element, and the matched nil is falsy.
        no-winner? (contains? #{:zero-shot nil} (:best report))
        losing-candidate (when (and no-winner? (seq (get-in params [pid :demos])))
                           (get params pid))
        params   (if no-winner? {pid base} params)
        ts       (System/currentTimeMillis)
        pid-params (-> (get params pid {})
                       traj-export/redact-example
                       (cond-> (seq (get params pid))
                         (assoc :version ts
                                :compiled-by {:optimizer optimizer :metric metric
                                              :dataset dataset-name
                                              :dataset-hash (clj-llm/dataset-hash (:examples dataset))
                                              :valset-hash (:valset report)
                                              :score (:best-score report)
                                              :lm (lm-label student-lm)
                                              :teacher (lm-label teacher-lm)})))
        _        (let [{:keys [valid? errors]} (clj-llm/validate-params pid-params)]
                   (when-not valid?
                     (throw (ex-info "Optimizer produced invalid params" {:errors errors}))))
        zero     (some #(when (= :zero-shot (:candidate %)) (:score %)) (:leaderboard report))
        meta     {:proposal-id (str ts) :optimizer optimizer :metric metric
                  :dataset dataset-name :dataset-hash (clj-llm/dataset-hash (:examples dataset))
                  :label-source (:label-source dataset)
                  :student (lm-label student-lm) :teacher (lm-label teacher-lm)
                  :zero-shot-score zero
                  :no-op? (empty? (:demos pid-params))
                  :candidate-file? (boolean losing-candidate)}
        dir      (proposal-dir pid (str ts))
        other    (dissoc params pid)]
    (when (seq other)
      ;; A single-predictor program only; params for other ids would be
      ;; silently dropped by a one-file accept.
      (mulog/warn ::extra-predictor-params :predictor-id pid :others (vec (keys other))))
    (.mkdirs dir)
    (spit (io/file dir "params.edn") (pr-str pid-params))
    (when losing-candidate
      (spit (io/file dir "candidate.edn") (pr-str (traj-export/redact-example losing-candidate))))
    (spit (io/file dir "report.edn") (pr-str (assoc report :meta meta)))
    (spit (io/file dir "status.edn") (pr-str {:status :pending :ts ts}))
    (spit (io/file dir "REVIEW.md") (render-review p pid-params report meta))
    (mulog/info ::proposal-written :predictor-id pid :proposal ts :optimizer optimizer
                :best (:best report) :score (:best-score report) :cost (:cost report))
    {:proposal-id (str ts)
     :review (.getPath (io/file dir "REVIEW.md"))
     :best (some-> (:best report) name)
     :best-score (:best-score report)
     :zero-shot-score zero
     :teacher-score (:teacher-score report)
     :demos (count (:demos pid-params))
     :no-op (:no-op? meta)
     :cost (:cost report)
     :calls (:calls report)
     :tokens (:tokens report)
     :stopped (some-> (:stopped report) name)}))

(defn list-proposals [pid]
  (let [dir (proposals-dir pid)]
    (if-not (.isDirectory dir)
      []
      (->> (.listFiles dir)
           (filter #(.isDirectory ^File %))
           (keep (fn [^File d]
                   (try
                     (let [status (edn/read-string (slurp (io/file d "status.edn")))
                           meta   (:meta (edn/read-string (slurp (io/file d "report.edn"))))
                           report (edn/read-string (slurp (io/file d "report.edn")))]
                       {:proposal-id (.getName d)
                        :status (:status status)
                        :optimizer (:optimizer meta)
                        :best (some-> (:best report) name)
                        :best-score (:best-score report)
                        :zero-shot-score (:zero-shot-score meta)
                        :review (.getPath (io/file d "REVIEW.md"))})
                     (catch Exception _ nil))))
           (sort-by :proposal-id #(compare %2 %1))
           vec))))

(defn accept-proposal!
  "Install a pending proposal as the project params file for pid. The params
   file it replaces (if any) is kept beside the proposal as previous.edn, so an
   accept is one copy away from undone. Returns {:params-file :previous}."
  [pid proposal-id]
  (let [dir    (proposal-dir pid proposal-id)
        status (try (edn/read-string (slurp (io/file dir "status.edn")))
                    (catch Exception _
                      (throw (ex-info (str "No proposal " proposal-id " for " pid) {}))))
        _      (when-not (= :pending (:status status))
                 (throw (ex-info (str "Proposal " proposal-id " is " (name (:status status))) {})))
        params (edn/read-string (slurp (io/file dir "params.edn")))
        _      (let [{:keys [valid? errors]} (clj-llm/validate-params params)]
                 (when-not valid? (throw (ex-info "Proposal params are invalid" {:errors errors}))))
        target (params-file-for pid)
        prev   (when (.isFile target)
                 (let [f (io/file dir "previous.edn")]
                   (io/copy target f)
                   (.getPath f)))]
    (.mkdirs (.getParentFile target))
    (spit target (pr-str params))
    (spit (io/file dir "status.edn") (pr-str (assoc status :status :accepted
                                                    :decided (System/currentTimeMillis))))
    (mulog/info ::proposal-accepted :predictor-id pid :proposal proposal-id)
    {:params-file (.getPath target) :previous prev}))

(defn reject-proposal!
  [pid proposal-id]
  (let [f (io/file (proposal-dir pid proposal-id) "status.edn")]
    (when-not (.isFile f)
      (throw (ex-info (str "No proposal " proposal-id " for " pid) {})))
    (spit f (pr-str (assoc (edn/read-string (slurp f))
                           :status :rejected :decided (System/currentTimeMillis))))
    {:status :rejected}))

(defcommand program$compile
  "Optimize a predictor's demos over a dataset and write a PROPOSAL for human review (never applied)."
  (fn [& {:keys [predictor-id dataset optimizer metric budget-usd trials max-bootstrapped
                 parallel lm tier teacher-lm teacher-tier threshold max-calls max-tokens repeats]}]
    (try
      (apply compile-predictor predictor-id dataset
             (mapcat identity
                     (cond-> {}
                       threshold                (assoc :threshold threshold)
                       max-calls                (assoc :max-calls max-calls)
                       max-tokens               (assoc :max-tokens max-tokens)
                       repeats                  (assoc :repeats repeats)
                       (non-blank optimizer)    (assoc :optimizer optimizer)
                       (non-blank metric)       (assoc :metric metric)
                       budget-usd               (assoc :budget-usd budget-usd)
                       trials                   (assoc :trials trials)
                       max-bootstrapped         (assoc :max-bootstrapped max-bootstrapped)
                       parallel                 (assoc :parallel parallel)
                       (non-blank lm)           (assoc :lm lm)
                       (non-blank tier)         (assoc :tier tier)
                       (non-blank teacher-lm)   (assoc :teacher-lm teacher-lm)
                       (non-blank teacher-tier) (assoc :teacher-tier teacher-tier))))
      (catch Exception e
        {:error (ex-message e)})))
  :input-schema  [:map
                  [:predictor-id     [:string {:desc "Predictor id"}]]
                  [:dataset          [:string {:desc "Dataset name (train split bootstraps, val selects)"}]]
                  [:optimizer        {:optional true} [:string {:desc "bootstrap-random-search (default) | bootstrap-few-shot | labeled-few-shot"}]]
                  [:metric           {:optional true} [:string {:desc "Metric name; default exact-match"}]]
                  [:budget-usd       {:optional true} [:double {:desc "Spend cap in USD (default 2.0)"}]]
                  [:trials           {:optional true} [:int {:desc "Random-search trials (default 6)"}]]
                  [:max-bootstrapped {:optional true} [:int {:desc "Max demos per predictor (default 4)"}]]
                  [:parallel         {:optional true} [:int {:desc "Eval worker threads (default 2)"}]]
                  [:lm               {:optional true} [:string {:desc "Student provider/model (default sub-LM)"}]]
                  [:tier             {:optional true} [:string {:desc "Student tier"}]]
                  [:teacher-lm       {:optional true} [:string {:desc "Teacher provider/model"}]]
                  [:teacher-tier     {:optional true} [:string {:desc "Teacher tier (default deep)"}]]
                  [:threshold        {:optional true} [:double {:desc "Min metric score for a teacher trace to become a demo (default 1.0; use <1 for F1-style metrics)"}]]
                  [:max-calls        {:optional true} [:int {:desc "Cap on predictor calls (binds on subscription providers, whose USD is notional)"}]]
                  [:max-tokens       {:optional true} [:int {:desc "Cap on input+output tokens across the run"}]]
                  [:repeats          {:optional true} [:int {:desc "Passes per leaderboard row; ≥2 requires beating zero-shot beyond noise (default 1)"}]]]
  :output-schema [:map
                  [:proposal-id     {:optional true} [:string {:desc "Proposal id"}]]
                  [:review          {:optional true} [:string {:desc "REVIEW.md path for the human"}]]
                  [:best            {:optional true} [:string {:desc "Winning candidate"}]]
                  [:best-score      {:optional true} [:double {:desc "Its valset score"}]]
                  [:zero-shot-score {:optional true} [:double {:desc "Baseline valset score"}]]
                  [:teacher-score   {:optional true} [:double {:desc "Teacher zero-shot valset score (reference)"}]]
                  [:calls           {:optional true} [:int {:desc "Predictor calls made"}]]
                  [:tokens          {:optional true} [:int {:desc "Input+output tokens used"}]]
                  [:demos           {:optional true} [:int {:desc "Demos proposed"}]]
                  [:no-op           {:optional true} [:boolean {:desc "Zero-shot won; nothing to apply"}]]
                  [:cost            {:optional true} [:double {:desc "USD spent"}]]
                  [:stopped         {:optional true} [:string {:desc "Why the search stopped early"}]]
                  [:error           {:optional true} [:string {:desc "Error message"}]]])

(defcommand program$proposals
  "List a predictor's optimization proposals and their review status."
  (fn [& {:keys [predictor-id]}]
    (try {:proposals (list-proposals predictor-id)}
         (catch Exception e {:error (ex-message e)})))
  :input-schema  [:map [:predictor-id [:string {:desc "Predictor id"}]]]
  :output-schema [:map
                  [:proposals {:optional true} [:vector {:desc "{:proposal-id :status :optimizer :best :best-score :review}"} :any]]
                  [:error     {:optional true} [:string {:desc "Error message"}]]])

(def program-commands
  "Predictor dataset/eval/compile commands, bound into the common roster.
   Accept/reject are deliberately absent — see the compile section."
  [#'program$list #'program$build-dataset #'program$eval
   #'program$compile #'program$proposals])
