;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.clj-sandbox.bench.rlm
  "RLM-shaped benchmarks for `rlm-agent` vs `coact-agent` comparisons.

   Two suites:
     • multi-file-summary — N synthetic markdown docs; ask for per-doc
       summaries + cross-doc themes. O(N) MapReduce shape.
     • pairwise-dup       — N docs with K duplicate clusters (paraphrased
       restatements of the same underlying story). O(N²) shape that
       Pattern 5 (per-chunk summaries → second pass over summaries) is
       designed for.

   Both expose `benchmark-def` maps compatible with
   `bench.core/run-benchmark`. Agent-dispatch is optional and lives in
   `run-one-example-via-agent` below; it soft-resolves the agent runtime
   via requiring-resolve so this ns loads even when the agent component
   is not on the classpath.

   Status: PREPARED — actual benchmark runs deferred. No LLM calls fire
   from this ns at load time; suite generation is pure data."
  (:require [ai.brainyard.clj-sandbox.bench.scoring :as scoring]
            [clojure.set :as set]
            [clojure.string :as str])
  (:import [java.util Random]))

;; ============================================================================
;; RNG helpers (mirror bench.gen — kept inline to avoid coupling)
;; ============================================================================

(defn- ^Random rng [seed] (Random. (long seed)))
(defn- pick [^Random r coll] (nth coll (.nextInt r (count coll))))
(defn- shuffle-with [^Random r coll]
  (let [arr (java.util.ArrayList. ^java.util.Collection (vec coll))]
    (java.util.Collections/shuffle arr r)
    (vec arr)))

;; ============================================================================
;; Multi-file summary — synthetic doc bundle
;; ============================================================================

(def ^:private summary-topics
  "Topic vocabulary. Each entry yields a doc with a unique anchor word."
  [{:topic "renewable-energy"     :anchors ["photovoltaic" "tariff-feedback"]}
   {:topic "supply-chain"         :anchors ["just-in-time" "bullwhip"]}
   {:topic "cryptography"         :anchors ["lattice-based" "quantum-resistant"]}
   {:topic "marine-biology"       :anchors ["bioluminescence" "thermocline"]}
   {:topic "linguistics"          :anchors ["agglutinative" "phonotactic"]}
   {:topic "urban-planning"       :anchors ["transit-oriented" "zoning-overlay"]}
   {:topic "civil-engineering"    :anchors ["post-tensioned" "scour-protection"]}
   {:topic "macroeconomics"       :anchors ["stagflation" "yield-curve-inversion"]}
   {:topic "climate-modeling"     :anchors ["albedo-feedback" "ENSO-cycle"]}
   {:topic "neural-architecture"  :anchors ["mixture-of-experts" "rotary-embedding"]}
   {:topic "epidemiology"         :anchors ["herd-threshold" "wastewater-surveillance"]}
   {:topic "industrial-control"   :anchors ["PLC-ladder" "fail-safe-interlock"]}
   {:topic "satellite-imaging"    :anchors ["multispectral" "synthetic-aperture"]}
   {:topic "structural-acoustics" :anchors ["modal-density" "aeroelastic-flutter"]}
   {:topic "metallurgy"           :anchors ["austenite-tempering" "grain-refinement"]}])

(def ^:private cross-doc-theme-pool
  "Themes that may apply to multiple docs. Generators decide which to assign."
  ["risk-and-resilience"
   "measurement-uncertainty"
   "scaling-tradeoffs"
   "feedback-loops"
   "regulatory-pressure"])

(defn- generate-summary-doc
  "Build one synthetic doc. Returns {:id :topic :content :anchors :themes}.
   `themes` is a vector of cross-doc theme strings the doc explicitly nods to.
   Anchors are nonce-y multi-word terms that any honest summary should keep."
  [^Random r idx topic-spec themes]
  (let [{:keys [topic anchors]} topic-spec
        title (-> topic (str/replace "-" " ") str/capitalize)
        para1 (str "This document discusses " (str/replace topic "-" " ")
                   " with emphasis on " (str/join " and " anchors)
                   ". The treatment is intentionally short.")
        para2 (str "Two definitions matter here. First, "
                   (first anchors) " refers to the dominant idea in this paper. "
                   "Second, " (or (second anchors) (first anchors))
                   " is the secondary idea, used to extend the dominant one.")
        theme-paras (mapv (fn [t]
                            (str "On " t ": this paper notes that " topic
                                 " interacts with " t " in non-trivial ways. "
                                 "Examples are deferred to the appendix."))
                          themes)
        para-tail (str "We close with three observations specific to "
                       (pick r ["the academic" "the industrial" "the regulatory"])
                       " context, which we summarize as "
                       (pick r ["a balanced view" "an open question" "a tractable refinement"])
                       ".")
        content (str/join "\n\n"
                          (concat [(str "# " title)
                                   (str "## Document " (inc idx))
                                   para1 para2]
                                  theme-paras
                                  [para-tail]))]
    {:id      (format "doc-%02d" (inc idx))
     :topic   topic
     :content content
     :anchors (vec anchors)
     :themes  (vec themes)}))

(defn generate-multi-file-summary-example
  "Generate one multi-file-summary example.
   Returns {:context :query :gold :n-docs :doc-ids :per-doc-anchors :cross-doc-themes}."
  [n-docs seed]
  (let [r (rng seed)
        topic-pool (shuffle-with r summary-topics)
        chosen-topics (vec (take n-docs (cycle topic-pool)))
        ;; Pick 2 cross-doc themes; each is shared by 2-4 docs.
        chosen-themes (vec (take 2 (shuffle-with r cross-doc-theme-pool)))
        ;; For each theme, choose which doc indices participate (≥2).
        theme->doc-idxs
        (into {}
              (map (fn [t]
                     (let [k (+ 2 (.nextInt r (max 1 (- n-docs 2))))]
                       [t (set (take k (shuffle-with r (range n-docs))))]))
                   chosen-themes))
        ;; Per-doc theme list = which themes that doc participates in
        doc-themes
        (mapv (fn [idx]
                (vec (for [t chosen-themes
                           :when (contains? (theme->doc-idxs t) idx)]
                       t)))
              (range n-docs))
        docs (mapv (fn [idx topic themes]
                     (generate-summary-doc r idx topic themes))
                   (range n-docs) chosen-topics doc-themes)
        bundle (str/join "\n\n---\n\n"
                         (map (fn [{:keys [id content]}]
                                (str "=== " id ".md ===\n" content))
                              docs))
        per-doc-anchors (mapv (fn [{:keys [id anchors]}]
                                {:doc-id id :anchors anchors})
                              docs)
        cross-doc-anchors (vec chosen-themes)]
    {:context bundle
     :query (str "Read the " n-docs " documents bundled above (separated by "
                 "`=== doc-NN.md ===` headers). For EACH document, write a "
                 "one-sentence summary mentioning its key technical anchors. "
                 "Then list the cross-document themes that appear in two or "
                 "more documents.")
     :gold {:per-doc per-doc-anchors
            :cross-doc-anchors cross-doc-anchors}
     :n-docs n-docs
     :doc-ids (mapv :id docs)
     :per-doc-anchors per-doc-anchors
     :cross-doc-themes cross-doc-anchors
     :context-chars (count bundle)}))

;; ============================================================================
;; Pairwise duplicate detection — paraphrased base stories
;; ============================================================================

(def ^:private pairwise-base-stories
  "Each base story has 2-3 paraphrased variants. Variants encode the same
   facts with different phrasing — agent must identify them as the same
   underlying event."
  [{:base "datacenter-fire"
    :variants
    ["A fire broke out at the East-Bay datacenter on March 14th. Operators "
     "evacuated the building within ninety seconds; sprinklers contained the "
     "blaze before it reached the colocation cages. Three racks were damaged."

     "On 2026-03-14, an electrical fault caused a fire at the company's "
     "East-Bay datacenter. The team evacuated and the suppression system "
     "successfully prevented the fire from reaching the colocation cages. "
     "Three damaged racks were later replaced."

     "Mid-March saw an electrical-fault fire at East-Bay. The 90-second "
     "evacuation was clean and the suppression contained the fire short of "
     "the colocation rows; only three racks needed replacement afterward."]}

   {:base "stadium-earthquake"
    :variants
    ["A 5.4-magnitude earthquake struck during the Saturday match at the new "
     "stadium. The structural-health monitoring flagged a single anomaly on "
     "the southern roof truss, and the venue was cleared as a precaution."

     "During Saturday's stadium match, sensors registered a magnitude-5.4 "
     "tremor. One alert came from the south-roof truss, prompting officials "
     "to vacate the stands while inspections proceeded."

     "Saturday's match at the stadium was interrupted by a 5.4 quake; a "
     "single south-roof-truss alarm triggered a precautionary evacuation."]}

   {:base "river-spill"
    :variants
    ["A small chemical spill from a barge entered the river east of the "
     "harbor. Cleanup crews deployed booms within four hours; the affected "
     "stretch reopened to traffic the following morning."

     "Authorities responded to a barge-sourced chemical spill in the harbor "
     "river yesterday. Containment booms were on-site within four hours and "
     "river traffic resumed at sunrise."

     "Yesterday's barge incident released a small amount of chemical into the "
     "river east of the harbor. Booms went in inside four hours and "
     "navigation reopened the next morning."]}

   {:base "satellite-anomaly"
    :variants
    ["A reaction-wheel anomaly on the Polar-7 satellite triggered safe-mode "
     "at 03:12 UTC. Ground control re-established attitude control after "
     "switching to the redundant wheel set."

     "Polar-7 entered safe-mode at 03:12 UTC after a reaction-wheel anomaly. "
     "Operators failed-over to the backup wheels and recovered attitude "
     "control shortly afterward."

     "At 03:12 UTC, Polar-7's primary reaction wheel misbehaved and the "
     "spacecraft safed itself. The redundant wheel set was promoted and "
     "control was re-established."]}

   {:base "rail-signal-failure"
    :variants
    ["A relay-room failure took the western signaling system offline for "
     "eleven minutes during the morning commute. Trains were held at red "
     "until manual signaling protocols took over."

     "Morning rail traffic in the west was delayed by eleven minutes after a "
     "relay-room outage knocked out signaling. Fallback manual signaling "
     "kept trains stationary until power was restored."

     "On commute morning, the western relay room failed and signaling went "
     "dark for eleven minutes. Trains held at red, and manual procedures "
     "covered the gap until restoration."]}])

(def ^:private pairwise-distractor-templates
  "One-off filler stories used to fill non-cluster docs. Each is unique so
   it should not duplicate-cluster with any other distractor."
  ["A new community garden opened at the edge of the eastern district. "
   "Volunteers planted several heirloom vegetable varieties. The harvest "
   "is donated to the food bank each week."

   "The municipal museum unveiled a small exhibit on early printing presses. "
   "Two restored letterpress machines are part of the exhibit. Hands-on "
   "demonstrations are scheduled on weekends."

   "An amateur radio club coordinated a region-wide propagation experiment. "
   "Several stations reported unusually clear signals on the ten-meter band. "
   "Logs were submitted for the annual contest."

   "A regional bakery announced an apprenticeship program for local schools. "
   "Selected students will rotate through three production stations. The "
   "first cohort starts in the autumn term."

   "A volunteer crew completed the restoration of an antique wooden footbridge "
   "in the upper park. Replacement timbers were milled from storm-fallen trees. "
   "A short ceremony marked the reopening."

   "A school district adopted a new arts-integration curriculum. Teachers "
   "received summer training across all grade levels. Pilot results are due "
   "next semester."

   "A historic clock tower received its first full overhaul in forty years. "
   "Specialists from a nearby university documented every gear before "
   "disassembly. The clock returned to service on schedule."

   "A craft brewery launched a limited-edition seasonal lager using locally "
   "grown hops. The release sold out within a single weekend at the taproom. "
   "A second batch is already in fermentation."

   "An astronomy outreach event drew several hundred visitors to the "
   "lakeside observatory. A clear sky allowed views of the rings of Saturn "
   "and the Andromeda galaxy. Volunteers ran the telescopes."

   "A local engineering firm donated its time to redesign the public library's "
   "ventilation. The new layout reduced peak summer temperatures by several "
   "degrees. Books in the rare-collection room are now better preserved."])

(defn- pairwise-doc-content [^Random r doc-id story]
  ;; Wrap the story with a short header so doc IDs are visible in the bundle.
  (str "# Report " doc-id "\n\n"
       "**Filed:** "
       (pick r ["Tuesday" "Wednesday" "Thursday" "Friday" "Monday"])
       "\n\n"
       story
       "\n\n"
       "_End of report._"))

(defn generate-pairwise-dup-example
  "Generate a pairwise-duplicate example.

   n-docs    — total docs in the bundle
   n-clusters — how many clusters of duplicates to plant (≥2 docs each)
   seed       — RNG seed

   Returns {:context :query :gold :doc-ids :clusters ...} where :clusters is
   `[#{<doc-id> <doc-id> ...} ...]` — ground-truth clusters of size ≥2."
  [n-docs n-clusters seed]
  (let [r (rng seed)
        chosen-bases (vec (take n-clusters (shuffle-with r pairwise-base-stories)))
        ;; Decide cluster sizes: each cluster gets 2-3 docs.
        cluster-sizes (mapv (fn [_] (+ 2 (.nextInt r 2))) chosen-bases)
        cluster-doc-counts (reduce + cluster-sizes)
        _ (when (> cluster-doc-counts n-docs)
            (throw (ex-info "n-docs too small for n-clusters with size 2-3"
                            {:n-docs n-docs :needs cluster-doc-counts})))
        n-distractors (- n-docs cluster-doc-counts)
        ;; Build assignment: vector of {:story <base or :distractor>}
        cluster-entries
        (mapcat (fn [size base]
                  (let [variants (shuffle-with r (:variants base))]
                    (mapv (fn [v]
                            {:cluster-base (:base base)
                             :story v})
                          (take size variants))))
                cluster-sizes chosen-bases)
        distractors (take n-distractors (shuffle-with r pairwise-distractor-templates))
        distractor-entries (mapv (fn [s] {:cluster-base nil :story s}) distractors)
        ;; Combine and shuffle so cluster members are scattered through the bundle.
        all-entries (shuffle-with r (concat cluster-entries distractor-entries))
        docs (map-indexed
              (fn [idx {:keys [cluster-base story]}]
                {:id (format "doc-%02d" (inc idx))
                 :cluster-base cluster-base
                 :content (pairwise-doc-content r (format "doc-%02d" (inc idx)) story)})
              all-entries)
        bundle (str/join "\n\n---\n\n"
                         (map (fn [{:keys [id content]}]
                                (str "=== " id ".md ===\n" content))
                              docs))
        ;; Ground-truth clusters: group docs by cluster-base (only ≥2 are clusters)
        gold-clusters
        (->> docs
             (filter :cluster-base)
             (group-by :cluster-base)
             (vals)
             (map (fn [docs] (set (map :id docs))))
             (filter #(>= (count %) 2))
             vec)]
    {:context bundle
     :query (str "The bundle above contains " n-docs " short reports "
                 "(each headed by `=== doc-NN.md ===`). Some reports describe "
                 "the SAME underlying event in different words — they are "
                 "duplicates. Identify any clusters of duplicate reports and "
                 "list each cluster as a comma-separated set of doc-NN ids on "
                 "one line. Reports that are unique should NOT appear in any "
                 "cluster.")
     :gold {:clusters gold-clusters}
     :doc-ids (mapv :id docs)
     :clusters gold-clusters
     :n-docs n-docs
     :n-clusters n-clusters
     :context-chars (count bundle)}))

;; ============================================================================
;; Suite generators
;; ============================================================================

(def multi-file-summary-default-config
  {:n-docs-options [6 12]
   :examples-per-option 5
   :max-iterations 20
   :max-depth 1})

(def pairwise-dup-default-config
  {:n-docs-options [10 18]
   :n-clusters 3
   :examples-per-option 5
   :max-iterations 25
   :max-depth 1})

(defn generate-multi-file-summary-suite [config]
  (let [{:keys [n-docs-options examples-per-option]} config]
    (vec
     (for [n-docs n-docs-options
           i (range examples-per-option)
           :let [seed (hash [::multi-file-summary n-docs i])
                 ex (generate-multi-file-summary-example n-docs seed)]]
       (assoc ex
              :id (str "mfs-" n-docs "d-" i)
              :max-iterations (:max-iterations config)
              :max-depth (:max-depth config))))))

(defn generate-pairwise-dup-suite [config]
  (let [{:keys [n-docs-options n-clusters examples-per-option]} config]
    (vec
     (for [n-docs n-docs-options
           i (range examples-per-option)
           :let [seed (hash [::pairwise-dup n-docs n-clusters i])
                 ex (generate-pairwise-dup-example n-docs n-clusters seed)]]
       (assoc ex
              :id (str "pdup-" n-docs "d-" i)
              :max-iterations (:max-iterations config)
              :max-depth (:max-depth config))))))

;; ============================================================================
;; Scoring
;; ============================================================================

(defn- contains-ci?
  "Case-insensitive substring containment, NIL-safe, anchor normalized to
   match either dashed or spaced form."
  [s anchor]
  (let [hay (str/lower-case (str s))
        a   (str/lower-case (str anchor))
        a2  (str/replace a "-" " ")
        a3  (str/replace a " " "-")]
    (or (str/includes? hay a)
        (str/includes? hay a2)
        (str/includes? hay a3))))

(defn score-multi-file-summary
  "Score a multi-file-summary answer.

   Components:
     - per-doc-anchor recall  (weight 0.7)
     - cross-doc-theme recall (weight 0.3)

   Returns {:score double :correct? bool :per-doc-recall :cross-doc-recall
            :missing-anchors :missing-themes :gold :answer}."
  [example result]
  (let [answer (str (:answer result))
        per-doc (get-in example [:gold :per-doc])
        cross   (get-in example [:gold :cross-doc-anchors])
        per-doc-hits
        (mapv (fn [{:keys [doc-id anchors]}]
                (let [hit (count (filter #(contains-ci? answer %) anchors))]
                  {:doc-id doc-id :anchors anchors :hit hit :total (count anchors)}))
              per-doc)
        per-doc-anchor-total (reduce + 0 (map :total per-doc-hits))
        per-doc-anchor-hit   (reduce + 0 (map :hit per-doc-hits))
        per-doc-recall (if (pos? per-doc-anchor-total)
                         (double (/ per-doc-anchor-hit per-doc-anchor-total))
                         0.0)
        cross-hit (count (filter #(contains-ci? answer %) cross))
        cross-recall (if (seq cross) (double (/ cross-hit (count cross))) 0.0)
        score (+ (* 0.7 per-doc-recall) (* 0.3 cross-recall))
        correct? (>= score 0.7)
        missing-anchors (vec (mapcat (fn [{:keys [anchors] :as m}]
                                       (when (< (:hit m) (:total m))
                                         (filter #(not (contains-ci? answer %)) anchors)))
                                     per-doc-hits))
        missing-themes (vec (filter #(not (contains-ci? answer %)) cross))]
    {:score score
     :correct? correct?
     :per-doc-recall per-doc-recall
     :cross-doc-recall cross-recall
     :missing-anchors missing-anchors
     :missing-themes missing-themes
     :gold (:gold example)
     :answer answer}))

(defn- extract-doc-ids
  "Pull doc-NN tokens out of an answer string."
  [s]
  (set (re-seq #"doc-\d{2}" (str/lower-case (str s)))))

(defn- partition-into-clusters
  "Split the answer into proposed clusters by line. Each non-empty line that
   contains ≥2 doc-NN tokens is treated as one proposed cluster."
  [answer]
  (->> (str/split-lines (str answer))
       (map extract-doc-ids)
       (filter #(>= (count %) 2))
       vec))

(defn score-pairwise-dup
  "Score a pairwise-dup answer.

   Cluster recall: for each gold cluster of size ≥2, check whether some
   proposed cluster (extracted by line from the answer) is a SUPERSET of
   the gold cluster. Score = (matched-gold / total-gold).

   Light precision penalty: if the answer proposes spurious clusters that
   contain no gold member overlap, deduct up to 0.2.

   Returns {:score :correct? :gold-matched :gold-total :extra-clusters
            :proposed-clusters :gold-clusters}."
  [example result]
  (let [answer (str (:answer result))
        gold (get-in example [:gold :clusters])
        proposed (partition-into-clusters answer)
        matched (count (filter (fn [g] (some #(set/subset? g %) proposed)) gold))
        gold-total (count gold)
        recall (if (pos? gold-total) (double (/ matched gold-total)) 1.0)
        ;; Spurious = proposed clusters whose member-set has zero overlap
        ;; with any gold cluster.
        spurious (count (filter (fn [p]
                                  (every? #(empty? (set/intersection p %)) gold))
                                proposed))
        precision-penalty (min 0.2 (* 0.05 spurious))
        score (max 0.0 (- recall precision-penalty))]
    {:score score
     :correct? (>= score 0.7)
     :gold-matched matched
     :gold-total gold-total
     :extra-clusters spurious
     :proposed-clusters proposed
     :gold-clusters gold}))

;; ============================================================================
;; Benchmark defs
;; ============================================================================

(def multi-file-summary-def
  {:name "Multi-File-Summary"
   :description "Per-doc summaries + cross-doc theme synthesis (O(N))"
   :generate-fn generate-multi-file-summary-suite
   :score-fn score-multi-file-summary
   :default-config multi-file-summary-default-config})

(def pairwise-dup-def
  {:name "Pairwise-Duplicate"
   :description "Find paraphrased duplicates across N docs (O(N²) → Pattern 5)"
   :generate-fn generate-pairwise-dup-suite
   :score-fn score-pairwise-dup
   :default-config pairwise-dup-default-config})

(def all-rlm-benchmarks
  [multi-file-summary-def
   pairwise-dup-def])

;; ============================================================================
;; Optional agent-dispatch runner
;;
;; The default bench harness (bench.core/run-one-example) drives
;; `clj-sandbox/completion` directly. To compare rlm-agent against
;; coact-agent (the BT-loop dispatch path) we need a separate runner that
;; spawns an agent and asks it the question, with the bundle written to
;; a tmp dir so the agent's file tools can read it.
;;
;; Soft-resolved via requiring-resolve so this ns loads without the agent
;; component on the classpath.
;; ============================================================================

(defn- spit-bundle-to-dir!
  "Write the multi-doc bundle out to per-file paths under <dir>. Returns
   the absolute path of <dir>."
  [bundle-string]
  (let [dir (java.nio.file.Files/createTempDirectory
             "rlm-bench-"
             (make-array java.nio.file.attribute.FileAttribute 0))
        ;; Split on the bundle's delimiter — produced by both generators.
        chunks (str/split bundle-string #"\n\n---\n\n")]
    (doseq [chunk chunks]
      (when-let [m (re-find #"=== (\S+\.md) ===\n" chunk)]
        (let [filename (second m)
              content (str/replace chunk (re-pattern (str "=== " filename " ===\n")) "")]
          (spit (str (.toString dir) "/" filename) content))))
    (.toString dir)))

(defn run-one-example-via-agent
  "Drive a single benchmark example through an agent (defaults :rlm-agent).
   Writes the bundle to a tmp dir and rewrites the question to point at it.

   Soft-resolves agent dispatch — throws a clear error if the agent
   component is not loaded.

   Returns the same shape as bench.core/run-one-example."
  [example {:keys [agent-id score-fn user-id session-id max-iterations on-example]
            :or {agent-id    :rlm-agent
                 user-id     "bench-user"
                 session-id  (str "bench-" (System/currentTimeMillis))}}]
  (let [setup-fn (requiring-resolve 'ai.brainyard.agent.core.agent/setup-agent-by-id)
        ask-fn   (requiring-resolve 'ai.brainyard.agent.core.agent/ask)
        _ (when-not (and setup-fn ask-fn)
            (throw (ex-info "Agent runtime not on classpath; cannot dispatch via agent."
                            {:agent-id agent-id})))
        dir (spit-bundle-to-dir! (:context example))
        rewritten-query (str/replace (:query example)
                                     "above"
                                     (str "in the directory " dir))
        rewritten-query (str rewritten-query "\n\n(Files live at: " dir ")")
        ag (setup-fn agent-id
                     :agent-session {:user-id user-id :session-id session-id}
                     :max-iterations (or max-iterations
                                         (:max-iterations example)
                                         20))
        start (System/currentTimeMillis)
        result (try
                 (ask-fn ag rewritten-query)
                 (catch Exception e
                   {:answer nil :error (.getMessage e)}))
        elapsed (- (System/currentTimeMillis) start)
        score (score-fn example result)
        result-map (merge (dissoc example :context)
                          {:answer (:answer result)
                           :iterations-count (:total-iterations result)
                           :terminated-by (:terminated-by result)
                           :elapsed-ms elapsed
                           :score-result score
                           :agent-id agent-id
                           :bundle-dir dir})]
    (try (.close ag) (catch Exception _))
    (when on-example (on-example result-map))
    result-map))
