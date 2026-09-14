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
  (-> report
      (dissoc :per-example)
      (assoc :worst (->> (:per-example report)
                         (sort-by :score)
                         (take 5)
                         (mapv #(select-keys % [:index :score :error :error-class]))))))

(defn eval-predictor
  "Evaluate predictor `pid` on a dataset with a named metric; writes the full
   report under evals/ and returns {:report-path … :summary …}."
  [pid dataset-name metric-name & {:keys [split budget-usd parallel params-source]
                                   :or   {budget-usd 1.0 parallel 2}
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
                                    :parallel parallel :budget-usd budget-usd))
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
  (fn [& {:keys [predictor-id dataset metric split budget-usd parallel lm tier]}]
    (try
      (eval-predictor predictor-id dataset (or (non-blank metric) "exact-match")
                      :split split
                      :budget-usd (or budget-usd 1.0)
                      :parallel (or parallel 2)
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
                  [:tier         {:optional true} [:string {:desc "light | standard | deep (via :agent-lm-tiers)"}]]]
  :output-schema [:map
                  [:report-path {:optional true} [:string {:desc "Full report file"}]]
                  [:summary     {:optional true} [:map {:desc "Score, cost, tokens, stopped, worst examples"}]]
                  [:error       {:optional true} [:string {:desc "Error message"}]]])

(def program-commands
  "Predictor dataset/eval commands, bound into the common roster."
  [#'program$list #'program$build-dataset #'program$eval])
