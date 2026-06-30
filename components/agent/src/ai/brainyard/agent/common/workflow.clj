;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.common.workflow
  "Workflow-agent helpers — the surviving READ/DERIVE/VALIDATE seams + the
   auto-finalize backstop (lightweight redesign, docs/design/
   workflow-agent-design.md).

   Orchestration is LLM judgment and persistence is markdown the model authors,
   so the structured-construction helpers (workflow$bootstrap's acceptance/
   stages vectors-of-maps, workflow$update-stage / -update-acceptance status
   flips, workflow$write-verdict's frontmatter, workflow$append-log /
   -index-append) are RETIRED. The agent now: `bash mkdir` + `write-file` the
   dossier files from the loaded template; tracks acceptance AND the stage
   roster as markdown CHECKLISTS (the shared todo substrate, §5) flipped
   index-free by stable id via `update-file`; appends findings.log + INDEX with
   `write-file :append`.

   What stays is mechanism a machine does better than the model:
   - workflow$id            — deterministic resume key.
   - workflow$resume?        — parse dossier frontmatter + the acceptance/stages
     CHECKLISTS (dual-reads legacy frontmatter acceptance + stages.edn).
   - workflow$list-templates — discover project/user/built-in templates (*.md +
     legacy *.edn).
   - workflow$load-template  — parse + VALIDATE a markdown template (dual-reads
     legacy EDN); the read seam that makes hand-edited templates safe.
   - workflow$install-starters — copy the built-in markdown starters.
   - workflow$verdict-outcome — derive acceptance_outcome + stage_outcomes from
     the checklists and enforce the :achieved guard (read-side validator the
     model calls BEFORE write-file-ing verdict.md).

   Templates are markdown now (§6): id/name/description + an Acceptance checklist
   + a Stages checklist — the SAME format the run dossier uses, so CRUD is plain
   file ops (workflow-agent owns it in an authoring mode)."
  (:require [ai.brainyard.agent.core.config :as config]
            [ai.brainyard.agent.core.hooks :as hooks]
            [ai.brainyard.agent.core.protocol :as proto]
            [ai.brainyard.agent.core.tool :refer [defcommand]]
            [ai.brainyard.mulog.interface :as mulog]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]))

;; ============================================================================
;; Constants
;; ============================================================================

(def ^:private results-base ".brainyard/agents/workflow-agent")
(def ^:private index-rel (str results-base "/INDEX.md"))
(def ^:private templates-rel ".brainyard/workflows")
(def ^:private builtin-templates-cp "workflows")

(def ^:private slug-stopwords
  #{"a" "an" "the" "is" "are" "and" "or" "of" "in" "on" "at" "to" "for"
    "by" "with" "from" "as" "but" "if" "then" "than" "so"
    "what" "where" "when" "who" "whom" "why" "how" "which"
    "do" "does" "did" "can" "could" "would" "should" "shall" "will"
    "this" "that" "these" "those" "it" "its" "we" "they" "you" "i"
    "be" "been" "being" "was" "were" "have" "has" "had"
    "run" "runs" "running" "ship" "ships" "shipped" "shipping"})

(def ^:private valid-criterion-statuses
  #{:open :satisfied :partial :descoped :contradicted})

(def ^:private valid-stage-statuses
  #{:pending :in-progress :satisfied :failed :skipped :abandoned})

;; ============================================================================
;; Time formatters
;; ============================================================================

(defn- now-iso [] (str (java.time.Instant/now)))

(defn- now-yyyy-mm-dd-hh-mm []
  (-> (subs (now-iso) 0 16) (str/replace "T" " ")))

;; ============================================================================
;; Slugify
;; ============================================================================

(defn- slugify
  [text max-chars]
  (let [tokens (-> (str text)
                   str/lower-case
                   (str/replace #"[^a-z0-9]+" "-")
                   (str/split #"-"))
        kept   (->> tokens
                    (remove #(or (str/blank? %) (slug-stopwords %)))
                    seq)
        joined (if kept (str/join "-" kept) "workflow")]
    (subs joined 0 (min max-chars (count joined)))))

;; ============================================================================
;; Checklist parsing — ONE format for templates, dossiers, and the todo
;; substrate. Acceptance lines and stage lines both carry an optional
;; `(status)` token (authoritative) else the checkbox char maps to a status.
;; ============================================================================

(def ^:private acc-box->status
  {" " :open "x" :satisfied "~" :partial "-" :descoped "!" :contradicted})

(def ^:private stage-box->status
  {" " :pending ">" :in-progress "x" :satisfied "~" :partial "-" :skipped "!" :failed})

(defn- resolve-status
  "Prefer the explicit `(token)` status when it's valid; else the checkbox char."
  [valid box->status box token]
  (let [tok (some-> token str/trim not-empty keyword)]
    (if (and tok (valid tok))
      tok
      (get box->status box :open))))

(def ^:private acc-line-re
  ;; - [<box>] <id> (<status>)? — <text>
  #"(?m)^\s*-\s*\[(.)\]\s+([A-Za-z0-9_-]+)\s*(?:\(([a-z][a-z-]*)\))?\s+(?:—|–|-)\s+(.*?)\s*$")

(def ^:private stage-line-re
  ;; - [<box>] <sid> <name> (<status>)? — <purpose> {agent:…, gate:…, focus:[…]}
  #"(?m)^\s*-\s*\[(.)\]\s+([A-Za-z0-9_-]+)\s+([A-Za-z0-9_-]+)\s*(?:\(([a-z][a-z-]*)\))?\s+(?:—|–|-)\s+(.*?)\s*$")

(defn- parse-inline-tags
  "Parse a trailing `{agent: X, gate: Y, focus: [a1, a2]}` tag block off a stage
   line's body → {:agent kw :gate kw :focus [kw…] :purpose <body-without-tags>}."
  [body]
  (let [focus  (when-let [[_ f] (re-find #"focus:\s*\[([^\]]*)\]" body)]
                 (->> (str/split f #",")
                      (map str/trim) (remove str/blank?)
                      (mapv keyword)))
        agent  (some-> (re-find #"agent:\s*([A-Za-z0-9_-]+)" body) second keyword)
        gate   (some-> (re-find #"gate:\s*([A-Za-z0-9_-]+)" body) second keyword)
        purpose (-> body (str/replace #"\{[^}]*\}" "") str/trim)]
    {:agent agent :gate gate :focus (or focus []) :purpose purpose}))

(defn- parse-acceptance-md
  "Parse a `# Acceptance` checklist block → vector of {:id kw :text :status kw}."
  [^String content]
  (->> (re-seq acc-line-re (str content))
       (mapv (fn [[_ box id token text]]
               {:id     (keyword id)
                :text   text
                :status (resolve-status valid-criterion-statuses acc-box->status box token)}))))

(defn- parse-stages-md
  "Parse a `# Stages` checklist block → vector of
   {:id kw :name str :purpose str :agent kw :gate kw :focus [kw] :status kw}."
  [^String content]
  (->> (re-seq stage-line-re (str content))
       (mapv (fn [[_ box sid name token body]]
               (merge {:id     (keyword sid)
                       :name   name
                       :status (resolve-status valid-stage-statuses stage-box->status box token)}
                      (parse-inline-tags body))))))

(defn- acceptance-state-map
  "{id-kw → status-kw} from a parsed acceptance vector."
  [acc] (into {} (map (juxt :id :status) acc)))

;; ============================================================================
;; Frontmatter parsing (markdown templates + dossiers)
;; ============================================================================

(defn- read-frontmatter-lines
  "Lines between the opening and closing `---`, or nil if the file doesn't start
   with `---`."
  [^java.io.File file]
  (with-open [r (io/reader file)]
    (let [reader (java.io.BufferedReader. r)]
      (when (= "---" (.readLine reader))
        (loop [acc []]
          (let [ln (.readLine reader)]
            (cond
              (nil? ln)    nil
              (= "---" ln) acc
              :else        (recur (conj acc ln)))))))))

(defn- extract-flat
  "Targeted `key: <value>` extraction from frontmatter lines."
  [lines key]
  (some (fn [ln]
          (when-let [[_ v] (re-matches (re-pattern (str "^" key ":\\s*(.*)$")) ln)]
            (let [v (str/trim v)]
              (cond
                (and (str/starts-with? v "\"") (str/ends-with? v "\""))
                (subs v 1 (dec (count v)))
                (= "null" v) nil
                :else v))))
        lines))

(defn- parse-defaults-flow
  "Parse a `defaults: {hitl: gates, max_stage_attempts: 3, sub_lm: …}` line."
  [lines]
  (let [raw (extract-flat lines "defaults")]
    (when raw
      (cond-> {}
        (re-find #"hitl:\s*([A-Za-z0-9_-]+)" raw)
        (assoc :hitl (keyword (second (re-find #"hitl:\s*([A-Za-z0-9_-]+)" raw))))
        (re-find #"max_stage_attempts:\s*(\d+)" raw)
        (assoc :max-stage-attempts (parse-long (second (re-find #"max_stage_attempts:\s*(\d+)" raw))))
        (re-find #"sub_lm:\s*([^,}]+)" raw)
        (assoc :sub-lm (str/trim (second (re-find #"sub_lm:\s*([^,}]+)" raw))))))))

;; ============================================================================
;; Legacy dual-read helpers (pre-redesign dossiers + EDN templates)
;; ============================================================================

(defn- extract-acceptance-state-legacy
  "Parse a legacy `acceptance:` frontmatter block (`- id:/text:/status:`)."
  [lines]
  (loop [ls lines, in? false, pending {}, acc {}]
    (if (empty? ls)
      (if-let [pid (:id pending)]
        (assoc acc (keyword pid) (or (some-> (:status pending) keyword) :open))
        acc)
      (let [ln (first ls), r (rest ls)]
        (cond
          (re-matches #"^acceptance:\s*$" ln) (recur r true {} acc)
          (and in? (re-matches #"^[a-z_]+:.*$" ln))
          (if-let [pid (:id pending)]
            (assoc acc (keyword pid) (or (some-> (:status pending) keyword) :open)) acc)
          (and in? (re-matches #"^\s*- id:\s*(\S+)\s*$" ln))
          (let [[_ id-str] (re-matches #"^\s*- id:\s*(\S+)\s*$" ln)
                acc' (if-let [pid (:id pending)]
                       (assoc acc (keyword pid) (or (some-> (:status pending) keyword) :open)) acc)]
            (recur r true {:id id-str} acc'))
          (and in? (re-matches #"^\s+status:\s*(\S+)\s*$" ln))
          (recur r true (assoc pending :status (second (re-matches #"^\s+status:\s*(\S+)\s*$" ln))) acc)
          :else (recur r in? pending acc))))))

(defn- read-stages-edn-legacy
  [^java.io.File file]
  (when (.isFile file)
    (try (:stages (edn/read-string (slurp file))) (catch Throwable _ nil))))

(defn- normalize-edn-template
  "Map a legacy EDN template map to the uniform shape load-template returns."
  [t]
  {:workflow/id          (:workflow/id t)
   :workflow/name        (:workflow/name t)
   :workflow/description (:workflow/description t)
   :defaults             (:defaults t)
   :acceptance           (mapv (fn [a] {:id (keyword (name (:id a)))
                                        :text (:text a)
                                        :status (or (:status a) :open)})
                               (:acceptance t))
   :stages               (mapv (fn [s] {:id     (keyword (name (:id s)))
                                        :name   (name (:id s))
                                        :purpose (:purpose s)
                                        :agent  (:recommended-agent s)
                                        :gate   (:gate-after s)
                                        :focus  (vec (:acceptance-focus s))
                                        :status (or (:status s) :pending)})
                               (:stages t))})

;; ============================================================================
;; Dossier acceptance/stages resolution (dual-read)
;; ============================================================================

(defn- read-acceptance-state
  "{id-kw → status-kw} for a workflow id — from the §5 acceptance.md checklist,
   else the legacy dossier.md frontmatter acceptance block."
  [base-dir id]
  (let [acc-file (io/file base-dir results-base id "acceptance.md")
        from-md  (when (.isFile acc-file)
                   (acceptance-state-map (parse-acceptance-md (slurp acc-file))))]
    (if (seq from-md)
      from-md
      (let [dossier (io/file base-dir results-base id "dossier.md")]
        (when (.isFile dossier)
          (when-let [lines (read-frontmatter-lines dossier)]
            (let [legacy (extract-acceptance-state-legacy lines)]
              (when (seq legacy) legacy))))))))

(defn- read-stages-state
  "Vector of stage maps for a workflow id — from the §5 stages.md checklist,
   else the legacy stages.edn roster."
  [base-dir id]
  (let [stages-file (io/file base-dir results-base id "stages.md")
        from-md     (when (.isFile stages-file)
                      (parse-stages-md (slurp stages-file)))]
    (if (seq from-md)
      from-md
      (mapv (fn [s] {:id (keyword (name (or (:id s) :_unknown)))
                     :status (or (:status s) :pending)})
            (read-stages-edn-legacy (io/file base-dir results-base id "stages.edn"))))))

;; ============================================================================
;; workflow$id — deterministic kebab-case workflow id
;; ============================================================================

(defcommand workflow$id
  "Deterministic kebab-case workflow id. Shape:
     <template-id>--<question-slug>   when :template is named
     <question-slug>                   when :template is :ad-hoc / nil"
  (fn [& {:keys [template question max-chars]
          :or   {max-chars 60}}]
    (cond
      (not (string? question))
      {:error ":question is required (string)"}

      (or (not (integer? max-chars)) (<= max-chars 0))
      {:error ":max-chars must be a positive integer"}

      :else
      (let [q-slug  (slugify question max-chars)
            tmpl-id (cond
                      (nil? template)       nil
                      (= :ad-hoc template)  nil
                      (keyword? template)   (name template)
                      (string? template)    template
                      :else                 nil)]
        {:slug (if tmpl-id (str tmpl-id "--" q-slug) q-slug)})))
  :input-schema  [:map
                  [:template  {:optional true} [:any    {:desc "Template id (keyword like :feature-launch, string, or :ad-hoc / nil)"}]]
                  [:question  [:string {:desc "User question to slugify"}]]
                  [:max-chars {:optional true} [:int    {:desc "Cap on question-slug length (default 60)"}]]]
  :output-schema [:map
                  [:slug  [:string {:desc "Kebab-case workflow id"}]]
                  [:error {:optional true} [:string {:desc "Error if validation failed"}]]])

;; ============================================================================
;; Template discovery — list + load (markdown, dual-read EDN)
;; ============================================================================

(defn- list-template-files
  "List `.md` and `.edn` files inside `dir`."
  [^java.io.File dir]
  (if (and (some? dir) (.isDirectory dir))
    (vec (filter #(and (.isFile ^java.io.File %)
                       (let [n (.getName ^java.io.File %)]
                         (or (str/ends-with? n ".md") (str/ends-with? n ".edn"))))
                 (.listFiles dir)))
    []))

(defn- list-classpath-templates
  "Enumerate built-in templates under `<classpath>/workflows/*.md` (+ legacy
   *.edn). Returns `{:id :name :description :source :resource}` maps."
  []
  (try
    (when-let [url (io/resource builtin-templates-cp)]
      (let [conn (.openConnection url)]
        (.setUseCaches conn false)
        (letfn [(tmpl? [n] (or (str/ends-with? n ".md") (str/ends-with? n ".edn")))
                (->id [base] (str/replace base #"\.(md|edn)$" ""))]
          (cond
            (= "file" (.getProtocol url))
            (let [dir (io/file url)]
              (mapv (fn [^java.io.File f]
                      (let [base (.getName f)]
                        {:id (->id base) :name (->id base) :description nil
                         :source :built-in :resource (str builtin-templates-cp "/" base)}))
                    (list-template-files dir)))

            (= "jar" (.getProtocol url))
            (let [jar    (.getJarFile ^java.net.JarURLConnection conn)
                  prefix (str builtin-templates-cp "/")]
              (->> (enumeration-seq (.entries jar))
                   (map #(.getName ^java.util.jar.JarEntry %))
                   (filter #(and (str/starts-with? % prefix) (tmpl? %)))
                   (mapv (fn [^String entry]
                           (let [base (subs entry (count prefix))]
                             {:id (->id base) :name (->id base) :description nil
                              :source :built-in :resource (str builtin-templates-cp "/" base)})))))

            :else []))))
    (catch Throwable _ [])))

(defn- read-template-meta
  "Read id/name/description off a template file (markdown frontmatter or legacy
   EDN). Returns nil on failure (list-templates is best-effort)."
  [^java.io.File f source]
  (try
    (let [base (str/replace (.getName f) #"\.(md|edn)$" "")]
      (if (str/ends-with? (.getName f) ".edn")
        (let [edn (edn/read-string (slurp f))]
          (when (map? edn)
            {:id (or (some-> (:workflow/id edn) name) base)
             :name (or (:workflow/name edn) base)
             :description (:workflow/description edn)
             :source source :path (.getPath f)}))
        (when-let [lines (read-frontmatter-lines f)]
          {:id (or (extract-flat lines "workflow_id") base)
           :name (or (extract-flat lines "name") base)
           :description (extract-flat lines "description")
           :source source :path (.getPath f)})))
    (catch Throwable _ nil)))

(defcommand workflow$list-templates
  "Enumerate workflow templates from project-local, user-local, and built-in
   locations (`.brainyard/workflows/*.md` + legacy `*.edn`)."
  (fn [& {:keys [base-dir include-built-in?]
          :or   {base-dir          (config/project-dir)
                 include-built-in? true}}]
    (let [project-dir (io/file base-dir templates-rel)
          home        (System/getProperty "user.home")
          user-dir    (when home (io/file home templates-rel))
          project (keep #(read-template-meta % :project) (list-template-files project-dir))
          user    (keep #(read-template-meta % :user)    (list-template-files user-dir))
          builtin (when include-built-in? (list-classpath-templates))]
      {:templates (vec (concat project user (or builtin [])))}))
  :input-schema  [:map
                  [:base-dir          {:optional true} [:string  {:desc "Project root (default: resolved)"}]]
                  [:include-built-in? {:optional true} [:boolean {:desc "Include classpath starters (default true)"}]]]
  :output-schema [:map
                  [:templates [:vector {:desc "Vector of template descriptor maps"} :any]]])

(defn- resolve-template-source
  "Resolve a template id/path to a file-based source (explicit path → project →
   user dirs), preferring `.md` over legacy `.edn`. Returns nil for built-in
   (the caller falls back to `resolve-template-cp`)."
  [{:keys [id path base-dir]}]
  (let [home (System/getProperty "user.home")]
    (cond
      path
      (let [f (io/file path)]
        (when (.isFile f) {:source :explicit :reader f :display-path path
                           :edn? (str/ends-with? path ".edn")}))
      :else
      (let [id-name (cond (keyword? id) (name id) (string? id) id :else nil)]
        (when id-name
          (some (fn [[base source]]
                  (when base
                    (let [md  (io/file base templates-rel (str id-name ".md"))
                          edn (io/file base templates-rel (str id-name ".edn"))]
                      (cond
                        (.isFile md)  {:source source :reader md :display-path (.getPath md) :edn? false}
                        (.isFile edn) {:source source :reader edn :display-path (.getPath edn) :edn? true}))))
                [[base-dir :project] [home :user]]))))))

(defn- resolve-template-cp
  "Built-in classpath fallback for a template id (md first, then edn)."
  [id-name]
  (let [md  (io/resource (str builtin-templates-cp "/" id-name ".md"))
        edn (io/resource (str builtin-templates-cp "/" id-name ".edn"))]
    (cond
      md  {:source :built-in :reader md  :display-path (str "classpath:" builtin-templates-cp "/" id-name ".md") :edn? false}
      edn {:source :built-in :reader edn :display-path (str "classpath:" builtin-templates-cp "/" id-name ".edn") :edn? true})))

(defn- frontmatter-lines-of
  "Frontmatter lines from a markdown string (between the opening/closing ---),
   or nil. Content-based so it works for file, classpath, and explicit readers."
  [^String content]
  (when-let [[_ fm] (re-find #"(?s)\A---\n(.*?)\n---" (str content))]
    (str/split-lines fm)))

(defn- parse-md-template
  "Parse a markdown template's content → the uniform template map. Splits the
   body on `# Acceptance` / `# Stages` headers."
  [^String content]
  (let [lines        (frontmatter-lines-of content)
        body         (str/replace content #"(?s)\A---\n.*?\n---\n" "")
        ;; split body into sections keyed by their `# Header`
        sections     (->> (str/split body #"(?m)^#\s+")
                          (remove str/blank?)
                          (map (fn [chunk]
                                 (let [[hdr & rest] (str/split-lines chunk)]
                                   [(str/lower-case (str/trim hdr)) (str/join "\n" rest)])))
                          (into {}))
        acc-section   (some (fn [[k v]] (when (str/starts-with? k "acceptance") v)) sections)
        stage-section (some (fn [[k v]] (when (str/starts-with? k "stages") v)) sections)]
    {:workflow/id          (some-> (extract-flat lines "workflow_id") keyword)
     :workflow/name        (extract-flat lines "name")
     :workflow/description (extract-flat lines "description")
     :defaults             (parse-defaults-flow lines)
     :acceptance           (parse-acceptance-md (or acc-section ""))
     :stages               (parse-stages-md (or stage-section ""))}))

(defn- validate-template
  "Returns nil when OK, else a precise error string. Cross-checks every stage
   `focus` id against the acceptance ids (§15)."
  [t]
  (let [acc-ids (set (map :id (:acceptance t)))]
    (cond
      (not (map? t))                          "template is not a map"
      (not (keyword? (:workflow/id t)))       ":workflow/id (frontmatter workflow_id) is required"
      (not (string? (:workflow/name t)))      ":workflow/name (frontmatter name) is required"
      (not (seq (:stages t)))                 "at least one stage is required (# Stages checklist)"
      (not (seq (:acceptance t)))             "at least one acceptance criterion is required (# Acceptance checklist)"
      :else
      (let [bad-focus (for [s (:stages t)
                            f (:focus s)
                            :when (not (contains? acc-ids f))]
                        (str (name (:id s)) "→" (name f)))]
        (when (seq bad-focus)
          (str "stage focus references unknown acceptance id(s): " (str/join ", " bad-focus)))))))

(defcommand workflow$load-template
  "Load + validate a workflow template (markdown; dual-reads legacy EDN).
   Resolution: explicit :path → project → user → built-in (`.md` preferred over
   `.edn`). Returns `{:template <map> :source <kw> :path <display>}` or
   `{:error …}`. The template map shape:
     {:workflow/id :workflow/name :workflow/description :defaults
      :acceptance [{:id :text :status}] :stages [{:id :name :purpose :agent :gate :focus :status}]}"
  (fn [& {:keys [id path base-dir]
          :or   {base-dir (config/project-dir)}}]
    (cond
      (and (nil? id) (nil? path))
      {:error "supply :id or :path"}

      :else
      (if-let [src (or (resolve-template-source {:id id :path path :base-dir base-dir})
                       (when id (resolve-template-cp (if (keyword? id) (name id) id))))]
        (try
          (let [{:keys [source reader display-path edn?]} src
                content (slurp reader)
                tmpl    (if edn?
                          (normalize-edn-template (edn/read-string content))
                          (parse-md-template content))]
            (if-let [err (validate-template tmpl)]
              {:error (str "invalid template at " display-path ": " err)}
              {:template tmpl :source source :path display-path}))
          (catch Throwable t
            {:error (str "failed to read template: " (.getMessage t))}))
        {:error (str "template not found: "
                     (if path path (str (some-> id name) " (looked in project, user, built-in)")))})))
  :input-schema  [:map
                  [:id       {:optional true} [:any    {:desc "Template id (keyword like :feature-launch or string)"}]]
                  [:path     {:optional true} [:string {:desc "Explicit path (alternative to :id)"}]]
                  [:base-dir {:optional true} [:string {:desc "Project root (default: resolved)"}]]]
  :output-schema [:map
                  [:template {:optional true} [:map     {:desc "Loaded + normalized template"}]]
                  [:source   {:optional true} [:keyword {:desc ":project | :user | :built-in | :explicit"}]]
                  [:path     {:optional true} [:string  {:desc "Display path of the loaded template"}]]
                  [:error    {:optional true} [:string  {:desc "Error if not found or invalid"}]]])

;; ============================================================================
;; workflow$install-starters — copy built-in markdown starters to project-local
;; ============================================================================

(defcommand workflow$install-starters
  "Copy built-in workflow starters (markdown) from classpath to
   <project>/.brainyard/workflows/. Idempotent — existing files preserved
   unless :overwrite?."
  (fn [& {:keys [base-dir overwrite?]
          :or   {base-dir (config/project-dir) overwrite? false}}]
    (let [dest-dir (io/file base-dir templates-rel)
          _        (.mkdirs dest-dir)
          builtins (list-classpath-templates)
          results  (reduce
                    (fn [acc {:keys [id resource]}]
                      (let [url    (io/resource resource)
                            ext    (if (str/ends-with? resource ".edn") ".edn" ".md")
                            dest-f (io/file dest-dir (str id ext))]
                        (cond
                          (nil? url)                      (update acc :skipped conj id)
                          (and (.isFile dest-f) (not overwrite?)) (update acc :skipped conj id)
                          :else (try (spit dest-f (slurp url))
                                     (update acc :installed conj id)
                                     (catch Throwable _ (update acc :skipped conj id))))))
                    {:installed [] :skipped []}
                    builtins)]
      (mulog/log ::workflow.install-starters
                 :installed (:installed results) :skipped (:skipped results))
      (assoc results :dir (.getPath dest-dir))))
  :input-schema  [:map
                  [:base-dir   {:optional true} [:string  {:desc "Project root (default: resolved)"}]]
                  [:overwrite? {:optional true} [:boolean {:desc "Overwrite existing files (default false)"}]]]
  :output-schema [:map
                  [:installed [:vector {:desc "Template ids newly written"} :string]]
                  [:skipped   [:vector {:desc "Template ids skipped"} :string]]
                  [:dir       [:string {:desc "Destination directory path"}]]])

;; ============================================================================
;; workflow$resume? — cheap probe (parses §5 checklists; dual-reads legacy)
;; ============================================================================

(defcommand workflow$resume?
  "Cheap probe: does a workflow dossier exist for this id, and what is its
   state? Reads dossier.md frontmatter + the acceptance.md / stages.md
   checklists (dual-reads legacy frontmatter acceptance + stages.edn)."
  (fn [& {:keys [id base-dir]
          :or   {base-dir (config/project-dir)}}]
    (cond
      (not (string? id))
      {:error ":id is required (string)"}

      :else
      (let [dossier (io/file base-dir results-base id "dossier.md")
            dir      (io/file base-dir results-base id)]
        (if-not (.isDirectory dir)
          {:exists? false}
          (let [lines  (when (.isFile dossier) (read-frontmatter-lines dossier))
                status (some-> (and lines (extract-flat lines "status")) keyword)
                last-i (some-> (and lines (extract-flat lines "last_iteration")) (str) parse-long)
                hitl   (some-> (and lines (extract-flat lines "hitl_mode")) keyword)
                accept (or (read-acceptance-state base-dir id) {})
                stages (read-stages-state base-dir id)
                pending (->> stages
                             (remove #(contains? #{:satisfied :skipped :abandoned} (:status %)))
                             (mapv :id))]
            {:exists?          true
             :status           (or status :in-progress)
             :last-iteration   (or last-i 1)
             :hitl-mode        (or hitl :gates)
             :acceptance-state accept
             :pending-stages   pending
             :stage-count      (count stages)
             :n-pending        (count pending)})))))
  :input-schema  [:map
                  [:id       [:string {:desc "Workflow id"}]]
                  [:base-dir {:optional true} [:string {:desc "Working directory (default: project root)"}]]]
  :output-schema [:map
                  [:exists?          [:boolean {:desc "true if the dossier directory exists"}]]
                  [:status           {:optional true} [:keyword {:desc "Overall workflow status"}]]
                  [:last-iteration   {:optional true} [:int     {:desc "last_iteration from frontmatter"}]]
                  [:hitl-mode        {:optional true} [:keyword {:desc "HITL mode"}]]
                  [:acceptance-state {:optional true} [:map     {:desc "{<criterion-id-kw> <status-kw>}"}]]
                  [:pending-stages   {:optional true} [:vector  {:desc "Stage ids not satisfied/skipped/abandoned"} :keyword]]
                  [:stage-count      {:optional true} [:int     {:desc "Total stages"}]]
                  [:n-pending        {:optional true} [:int     {:desc "Count of pending stages"}]]
                  [:error            {:optional true} [:string  {:desc "Error if validation failed"}]]])

;; ============================================================================
;; workflow$verdict-outcome — derive outcome + stage outcomes + :achieved guard
;; ============================================================================

(defn- derive-outcome
  "Derive a terminal outcome from the acceptance-state map.
     - any :open               → :in-progress (not finalizable)
     - all :satisfied/:descoped → :achieved
     - all :contradicted        → :abandoned
     - any other mix            → :partial"
  [state]
  (let [vs (set (vals state))]
    (cond
      (empty? vs)                          :in-progress
      (contains? vs :open)                 :in-progress
      (every? #{:satisfied :descoped} vs)  :achieved
      (every? #{:contradicted} vs)         :abandoned
      :else                                :partial)))

(defcommand workflow$verdict-outcome
  "Read the acceptance + stages checklists, derive the verdict outcome +
   acceptance_outcome + stage_outcomes, and enforce the :achieved guard.
   READ-ONLY — call before write-file-ing verdict.md."
  (fn [& {:keys [id base-dir]
          :or   {base-dir (config/project-dir)}}]
    (cond
      (not (string? id))
      {:error ":id is required (string)"}

      :else
      (let [state (read-acceptance-state base-dir id)]
        (if (nil? state)
          {:error (str "No acceptance checklist for id " id
                       " (acceptance.md missing and no legacy frontmatter). Bootstrap first.")}
          (let [stages   (read-stages-state base-dir id)
                outcome  (derive-outcome state)
                ok?      (and (seq state) (every? #{:satisfied :descoped} (vals state)))
                blockers (vec (for [[cid s] state :when (not (#{:satisfied :descoped} s))]
                                (str (name cid) ":" (name s))))]
            (mulog/log ::workflow.verdict-outcome
                       :id id :outcome outcome :achieved-ok? ok?
                       :n-criteria (count state) :n-stages (count stages))
            {:outcome            outcome
             :achieved-ok?       ok?
             :blockers           blockers
             :acceptance-outcome (into {} (map (fn [[k v]] [k (name v)]) state))
             :stage-outcomes     (into {} (map (fn [s] [(:id s) (name (:status s))]) stages))})))))
  :input-schema  [:map
                  [:id       [:string {:desc "Workflow id"}]]
                  [:base-dir {:optional true} [:string {:desc "Working directory (default: project root)"}]]]
  :output-schema [:map
                  [:outcome            {:optional true} [:keyword {:desc ":achieved | :partial | :abandoned | :in-progress"}]]
                  [:achieved-ok?       {:optional true} [:boolean {:desc "true iff every criterion :satisfied/:descoped (the guard)"}]]
                  [:blockers           {:optional true} [:vector {:desc "Criteria blocking :achieved, \"aN:status\""} :string]]
                  [:acceptance-outcome {:optional true} [:map {:desc "{<criterion-id-kw> <status-name>}"}]]
                  [:stage-outcomes     {:optional true} [:map {:desc "{<stage-id-kw> <status-name>}"}]]
                  [:error              {:optional true} [:string {:desc "Error if validation failed or no checklist"}]]])

;; ============================================================================
;; Public roster (for workflow-agent's :agent-tools)
;; ============================================================================

(def workflow-helpers
  "The surviving workflow READ/DERIVE/VALIDATE seams: deterministic id, the
   resume probe (frontmatter + §5 checklists), template discovery + load+validate
   + starter install, and the verdict outcome derivation + :achieved guard. The
   structured-construction helpers are retired — the dossier + templates are
   authored directly with the file tools."
  [#'workflow$id
   #'workflow$resume?
   #'workflow$list-templates
   #'workflow$load-template
   #'workflow$install-starters
   #'workflow$verdict-outcome])

;; ============================================================================
;; INDEX append (append-only; backs the auto-finalize backstop)
;; ============================================================================

(defn- append-index!
  [base-dir {:keys [id status one-line]}]
  (let [trimmed (-> (str one-line) (str/replace #"\s+" " ") str/trim)
        capped  (if (> (count trimmed) 200) (str (subs trimmed 0 197) "…") trimmed)
        line    (str "- " (now-yyyy-mm-dd-hh-mm)
                     " [" id "](" id "/) — "
                     (str/upper-case (name status)) " · " capped "\n")
        file    (io/file base-dir index-rel)]
    (.mkdirs (.getParentFile file))
    (spit file line :append true)
    (mulog/log ::workflow.index :id id :status status)
    line))

;; ============================================================================
;; Auto-finalize backstop
;;
;; The instruction tells the LLM to workflow$verdict-outcome → write-file
;; verdict.md → append INDEX → end :answer with `Saved workflow dossier:`.
;; This gated `:agent.ask/finalize` hook is the safety net: it derives the
;; outcome, renders verdict.md, appends INDEX, and injects the absent contract
;; line. Strict trigger: dossier exists AND no acceptance criterion is :open
;; AND verdict.md doesn't already exist.
;; ============================================================================

(def ^:private saved-workflow-prefix
  "If :answer already contains this prefix, the LLM finalized itself; no-op."
  "Saved workflow dossier:")

(defn- workflow-agent? [agent]
  (try (= :workflow-agent (proto/defagent-type agent)) (catch Throwable _ false)))

(defn- already-finalized? [^String answer]
  (boolean (and (string? answer) (str/includes? answer saved-workflow-prefix))))

(defn- render-verdict-md
  "Compose verdict.md (frontmatter + ## Verdict body) for the backstop."
  [{:keys [id status iterations acceptance-outcome stage-outcomes narrative]}]
  (str "---\n"
       "workflow_id: " id "\n"
       "status: " (name status) "\n"
       "terminated: " (now-iso) "\n"
       (when iterations (str "iterations: " iterations "\n"))
       (when (seq acceptance-outcome)
         (str "acceptance_outcome:\n"
              (str/join "" (for [[k v] acceptance-outcome] (str "  " (name k) ": " (name v) "\n")))))
       (when (seq stage-outcomes)
         (str "stage_outcomes:\n"
              (str/join "" (for [[k v] stage-outcomes] (str "  " (name k) ": " (name v) "\n")))))
       "---\n\n"
       "## Verdict\n"
       (str/trim (str narrative))
       "\n"))

(defn- one-line-summary
  [^String answer max-chars]
  (let [stripped (-> answer
                     (str/replace #"^---\n[\s\S]*?\n---\n" "")
                     (str/replace #"(?m)^Saved workflow dossier:.*$" "")
                     str/trim)
        paragraphs (->> (str/split stripped #"\n\n") (map str/trim) (remove str/blank?))
        prose? (fn [p] (and (not (str/starts-with? p "#"))
                            (not (str/starts-with? p "|"))
                            (not (str/starts-with? p "```"))
                            (not (re-matches #"^[-*_]{3,}$" p))))
        chosen (or (first (filter prose? paragraphs)) (first paragraphs) "")
        flat   (-> chosen (str/replace #"\s+" " ") (str/replace #"^#+\s*" "") str/trim)]
    (subs flat 0 (min max-chars (count flat)))))

(defn- finalize-config [agent]
  (try {:enabled? (boolean (config/get-config agent :workflow-auto-finalize))}
       (catch Throwable _ {:enabled? true})))

(defn workflow-auto-finalize
  "Auto-write verdict.md + append INDEX.md + inject the contract line when
   workflow-agent emits a non-blank answer without finalizing. Strict trigger:
   dossier exists AND no criterion :open AND verdict.md absent. Defensive —
   failures logged, never re-thrown."
  [{:keys [agent input result]}]
  (try
    (when (and (workflow-agent? agent) (map? result))
      (let [answer (:answer result)
            {:keys [enabled?]} (finalize-config agent)]
        (when (and enabled? (string? answer) (not (str/blank? answer))
                   (not (already-finalized? answer)))
          (let [question (or (when (string? input) input) (some-> input :question str) "")
                base-dir (config/project-dir)
                candidate-slug (when-not (str/blank? question) (slugify question 60))
                root (io/file base-dir results-base)
                ;; The id is <template>--<slug> or <slug>; match a dir by slug.
                wid  (when (and candidate-slug (.isDirectory root))
                       (->> (.listFiles root)
                            (filter #(and (.isDirectory ^java.io.File %)
                                          (let [n (.getName ^java.io.File %)]
                                            (or (= n candidate-slug)
                                                (str/ends-with? n (str "--" candidate-slug))))))
                            first
                            (#(when % (.getName ^java.io.File %)))))]
            (when wid
              (let [dir (io/file base-dir results-base wid)]
                (cond
                  (not (.isDirectory dir))
                  (mulog/log ::workflow.no-dossier-skip :id wid :answer-chars (count answer))

                  (.isFile (io/file dir "verdict.md"))
                  (mulog/log ::workflow.auto-finalize-skip :id wid :reason :verdict-exists)

                  :else
                  (let [state   (or (read-acceptance-state base-dir wid) {})
                        outcome (derive-outcome state)]
                    (cond
                      (or (empty? state) (= :in-progress outcome))
                      (mulog/log ::workflow.auto-finalize-skip
                                 :id wid
                                 :reason (cond (empty? state) :no-acceptance
                                               :else          :open-criteria-remain)
                                 :acceptance-state state)

                      :else
                      (let [dossier   (io/file dir "dossier.md")
                            last-iter (when (.isFile dossier)
                                        (some-> (read-frontmatter-lines dossier)
                                                (extract-flat "last_iteration") (str) parse-long))
                            stages    (read-stages-state base-dir wid)
                            summary   (one-line-summary answer 200)
                            verdict   (render-verdict-md
                                       {:id wid :status outcome :iterations last-iter
                                        :acceptance-outcome (into {} (map (fn [[k v]] [k (name v)]) state))
                                        :stage-outcomes (into {} (map (fn [s] [(:id s) (name (:status s))]) stages))
                                        :narrative answer})
                            vfile     (io/file dir "verdict.md")]
                        (.mkdirs dir)
                        (spit vfile verdict)
                        (append-index! base-dir
                                       {:id wid :status outcome
                                        :one-line (if (str/blank? summary)
                                                    "(auto-finalized; no summary extracted)" summary)})
                        (mulog/log ::workflow.auto-finalize
                                   :id wid :status outcome :answer-chars (count answer)
                                   :n-criteria (count state))
                        (when-not (str/includes? answer "Saved workflow dossier:")
                          {:result      :replace
                           :reason      "injected absent Saved-workflow-dossier handoff line"
                           :replacement (assoc result :answer
                                               (str answer "\n\nSaved workflow dossier: "
                                                    results-base "/" wid "/"))})))))))))))
    (catch Throwable t
      (mulog/error ::workflow.auto-finalize-failed
                   :exception t
                   :agent-id (try (proto/agent-id agent) (catch Throwable _ "unknown"))))))

(defn install-auto-finalize!
  "Register the auto-finalize backstop on `:agent.ask/finalize`, scoped to
   workflow-agent. Idempotent. Per-turn opt-out via
   `agent-runtime$config :key \"workflow-auto-finalize\" :value \"false\"`."
  []
  (hooks/register-hook!
   :agent.ask/finalize
   ::workflow-auto-finalize
   workflow-auto-finalize
   :source   :workflow-agent
   :match    (fn [{:keys [agent]}] (workflow-agent? agent))
   :priority 50))

;; Self-install at namespace load. Idempotent — register-hook! replaces by id.
(install-auto-finalize!)
