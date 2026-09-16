;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.common.user-predictors
  "Runtime-defined predictors — the user names a SIGNATURE and gets back a
   first-class, persistent, addressable predictor.

   Everything that executes already ships (`clj-llm/core/predictor.clj`:
   `predict` / `chain-of-thought`, layered params, demos, tracing) and so does
   everything downstream (`common/programs.clj`: datasets, `program$eval`,
   `program$compile`, proposals, `by programs accept`). All of it keys on
   `:predictor/id` in the shared registry and knows nothing about where a
   predictor came from — which is why authoring one at runtime unlocks the
   whole measurement layer with no new machinery. Design:
   `docs/design/predictor-agent-design.md`.

   A PREDICTOR IS DATA, NOT CODE — the one fact this whole namespace follows
   from. A user *tool* is a `fn` body: it must be persisted as source,
   re-evaluated in an SCI sandbox to rehydrate, forked per call, budgeted
   against a runaway and smoke-tested before it can be trusted. A predictor is

     {:id :instructions :inputs :outputs :input-order :strategy :tier}

   so there is no body to eval, no sandbox to fork, no timeout to budget and no
   privilege to expand. Three consequences:

   - **No `.clj` sidecar** (`def-store` exists to keep a BODY verbatim outside
     the `.edn`). One pretty-printed `.edn`, read only by `clojure.edn` — the
     safe-reader discipline is not a rule to remember here, it is the only
     option.
   - **No phase-1/phase-2 split.** `boot.clj` separates registration from body
     installation because SCI resolves symbols during analysis and a body
     calling `(bash :command …)` cannot eval without the agent's palette bound.
     A predictor resolves nothing at registration, so it registers whole, once.
   - **The dry-run is offline and free**: `compile-signature`, which derives the
     output JSON Schema and fails on anything malformed. Only the optional
     `:sample` costs an LLM call.

   TWO FILES, TWO LIFETIMES. The DEFINITION lives at
   `.brainyard/predictors/user/<name>.edn` (this ns). The PARAMS — the
   compiler's output: an instructions override, demos, an lm/tier hint — live at
   `.brainyard/programs/user/<name>.edn` and are written by `program$compile` /
   `by programs accept`, which overwrite that file WHOLESALE. Sharing one file
   would let an accepted proposal destroy the user's field declarations, and
   would subject a definition to params' deliberate first-layer-wins-whole
   resolution. Deleting a predictor therefore leaves its params, datasets and
   proposals in place and says so.

   AUTHORED IDS ARE NAMESPACED `user/…`, and registration refuses to replace an
   id that is already registered from source. `predictor/register!` is
   last-wins by id, so without both guards a definition file claiming
   `memory/graph-extract` would silently hijack every graph extraction in the
   process. Changing a built-in's behaviour is a PARAMS file, which is
   reviewable, comparable against zero-shot by `program$eval`, and cannot
   change the field set its calling source reads out of the result map.

   INVOCATION REUSES THE TOOL REGISTRY. An authored predictor registers into the
   same `agent.core.tool/!tool-defs` that `deftool` uses, as
   `user$predictor$<name>` — so it appears in `list-tools` / `search`, flows
   through `call-tool`'s Malli coercion and its hook / permission / depth
   guards, and is callable in the SAME turn it is created, from both the
   code-block and tool-calls channels. The `:fn` is a direct call into
   `run-predictor`: no sandbox, no fork, no body timeout.

   Not yet: a per-call `:lm-config` (the `query$llm` rule — refuse `:base-url`
   and `:api-key`, mint through `create-lm`). The predictor's `:tier` and its
   params `:lm` already decide the model."
  (:require [ai.brainyard.agent.common.schema :as acs]
            [ai.brainyard.agent.common.user-tools :as user-tools]
            [ai.brainyard.agent.core.config :as config]
            [ai.brainyard.agent.core.tool :as tool :refer [defcommand]]
            [ai.brainyard.clj-llm.interface :as clj-llm]
            [ai.brainyard.mulog.interface :as mulog]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.pprint :as pp]
            [clojure.string :as str])
  (:import [java.io File]))

;; ============================================================================
;; Naming
;; ============================================================================

(def ^:private predictor-name-re
  "User predictor names: lowercase kebab, leading letter. Keeps
   `user$predictor$<name>` a clean symbol and `<name>.edn` a safe filename."
  #"^[a-z][a-z0-9-]*$")

(def ^:const id-namespace
  "The one namespace an authored predictor may live in. Structural rather than
   advisory: it is what makes a collision with a source predictor impossible
   instead of merely unlikely, and it keeps `program$list` honest about which
   predictors a human wrote at runtime."
  "user")

(defn predictor-id
  "The registry id (and params-file path) for an authored predictor name."
  [name]
  (str id-namespace "/" name))

(defn- tool-id [name] (keyword (str "user$predictor$" name)))

(defn parse-name
  "The bare `<name>` from an author-supplied id, or nil.

   Accepts `foo` and `user/foo` alike — the LLM will write both, and the
   namespace is not a choice it gets to make. Any OTHER namespace is nil rather
   than silently re-homed: `memory/graph-extract` must read as refused, not as
   quietly renamed to `user/graph-extract`."
  [s]
  (when (string? s)
    (let [s (str/trim s)
          [a b :as parts] (str/split s #"/")]
      (when (and (<= (count parts) 2)
                 (or (nil? b) (= a id-namespace)))
        (let [n (or b a)]
          (when (re-matches predictor-name-re n) n))))))

;; ============================================================================
;; Persistence
;; ============================================================================
;;
;; `<root>/user/<name>.edn`, where root is `.brainyard/predictors` at either
;; scope. The id doubles as the relative path, exactly as it does under the
;; params roots, so `predictors/user/foo.edn` and `programs/user/foo.edn` sit at
;; mirrored paths and "which file holds what" is answerable from the tree.

(defn predictors-root
  "`.brainyard/predictors` at `scope` (:project | :user), or nil when that
   scope cannot be resolved (see config/subdir-scope-policy)."
  [dirs scope]
  (config/brainyard-subdir dirs "predictors" scope))

(defn- record-file ^File [root name]
  (io/file (str root) id-namespace (str name ".edn")))

(defn- write-record!
  "Persist `rec` to `<root>/user/<name>.edn`, pretty-printed. Returns the path."
  [root name rec]
  (let [f (record-file root name)]
    (.mkdirs (.getParentFile f))
    (spit f (binding [*print-length* nil *print-level* nil]
              (with-out-str (pp/pprint rec))))
    (.getPath f)))

(defn- read-record
  "The record in `f`, with `:file` attached, or nil when unreadable (logged).
   One corrupt `.edn` must not cost the user every other predictor."
  [^File f]
  (try
    (let [m (edn/read-string (slurp f))]
      (when (map? m) (assoc m :file (.getPath f))))
    (catch Exception e
      (mulog/warn ::unreadable-predictor-file :file (.getPath f) :error (ex-message e))
      nil)))

(defn- read-scope
  "Every readable record under one scope's root, sorted by filename. Sorted for
   the reason the user-tools loader sorts: two files can claim one `:name` and
   `.listFiles` order is undefined, so which one won would otherwise be a
   property of the filesystem."
  [root]
  (let [dir (io/file (str root) id-namespace)]
    (if (.isDirectory dir)
      (->> (.listFiles dir)
           (filter #(str/ends-with? (.getName ^File %) ".edn"))
           (sort-by #(.getName ^File %))
           (keep read-record)
           vec)
      [])))

(defn read-persisted
  "Every persisted record across both scopes, project first.

   Project WINS a same-name collision and the shadowed user-scope file is
   REPORTED, never merged — this is name resolution, not params layering. A
   definition that silently loses is a bug the user cannot see."
  [dirs]
  (let [proj (read-scope (predictors-root dirs :project))
        usr  (read-scope (predictors-root dirs :user))
        by-name (into {} (map (juxt :name identity)) proj)]
    (doseq [r usr :when (contains? by-name (:name r))]
      (mulog/warn ::predictor-shadowed
                  :name (:name r)
                  :winner (:file (get by-name (:name r)))
                  :shadowed (:file r)))
    (into proj (remove #(contains? by-name (:name %))) usr)))

;; ============================================================================
;; Record -> signature -> predictor
;; ============================================================================

(defn- ordered-fields
  "Rebuild a field map as an array-map in `order`.

   THIS IS NOT COSMETIC. `clojure.edn/read-string` yields a PersistentArrayMap
   up to 8 entries and a PersistentHashMap at 9, so a 9-field map read back from
   disk is in whatever order the hash seed says — stable within a process and
   different across processes, which is the hardest shape of bug to notice. For
   INPUTS the consequence is not cosmetic either: input order is
   cache-significant (the user message renders values in it), so a reordering
   silently destroys the turn-stable prompt prefix prompt caching depends on.

   Fields the order omits are appended in map order rather than dropped, so a
   hand-edited file loses its ordering, never a field."
  [fields order]
  (let [order (filter #(contains? fields %) order)
        rest* (remove (set order) (keys fields))
        ks    (concat order rest*)]
    (if (seq ks)
      (apply array-map (mapcat (fn [k] [k (get fields k)]) ks))
      {})))

(defn compile-record
  "The compiled signature for a record. Throws on a field map that derives no
   JSON Schema — which is the dry-run's whole smoke test."
  [{:keys [id instructions inputs outputs input-order output-order]}]
  (let [inputs  (ordered-fields inputs input-order)
        outputs (ordered-fields outputs output-order)]
    (clj-llm/compile-signature id
                               (or instructions "")
                               inputs
                               outputs
                               ;; Always explicit, so compile-signature's >8
                               ;; throw is unreachable for a record read from
                               ;; EDN rather than a trap waiting at the ninth
                               ;; field. `create` always persists this.
                               (vec (keys inputs)))))

(defn ->predictor
  "The predictor VALUE for a record (built, not registered)."
  [rec]
  (clj-llm/predictor (cond-> {:id       (:id rec)
                              :signature (compile-record rec)
                              :strategy (or (:strategy rec) :predict)}
                       (:tier rec) (assoc :tier (:tier rec)))))

(defn- tool-input-schema [sig]
  (into [:map] (map (fn [k] [k (get (:inputs sig) k)])) (:input-order sig)))

(defn- tool-result-schema
  "The tool's OUTPUT schema. `:outputs` carries the signature's fields; a `:cot`
   predictor's reasoning is a SIBLING of them, not one of them —
   `chain-of-thought` dissocs `:reasoning` before validating the rest against
   the signature, so declaring it inside `:outputs` would describe a shape the
   call never returns."
  [rec sig]
  (cond-> [:map
           [:outputs (into [:map] (map (fn [[k s]] [k s])) (:outputs sig))]
           [:valid? {:optional true} [:boolean {:desc "Did the output validate against the signature"}]]
           [:validation-errors {:optional true} [:any {:desc "Present only when :valid? is false"}]]]
    (= :cot (:strategy rec))
    (conj [:reasoning {:optional true}
           [:string {:desc "The model's step-by-step reasoning"}]])))

(defn- description-for
  "The one-line tool card. Falls back to the first line of the instructions —
   a predictor's instructions ARE its description, and asking for both invites
   them to disagree."
  [rec]
  (or (not-empty (str/trim (str (:description rec))))
      (some-> (:instructions rec) str/split-lines first str/trim not-empty)
      (str "User-defined predictor " (:id rec))))

;; ============================================================================
;; Registration
;; ============================================================================

(defonce ^{:private true
           :doc "`{id file}` for every predictor THIS namespace registered.
  How a collision tells `memory/graph-extract` (source — refuse) apart from
  `user/foo` re-created over itself (ours — that is how update is expressed),
  without teaching clj-llm's registry about provenance it has no use for."}
  !owned
  (atom {}))

(defn owned? [id] (contains? @!owned id))

(defn source-predictor?
  "True when `id` is registered from SOURCE (a `defpredictor`) rather than by
   this namespace — the one collision that is always fatal."
  [id]
  (and (some? (clj-llm/get-predictor id)) (not (owned? id))))

(defn register!
  "Register a record as BOTH a predictor (so `program$*` can see it) and a
   `user$predictor$<name>` tool (so anything can call it). Pure registry work —
   no sandbox, no agent — so it is safe at process boot. Returns the tool id.

   Throws when the record would replace a source predictor."
  [rec]
  (let [{:keys [id name file]} rec]
    (when (source-predictor? id)
      (throw (ex-info (str "predictor " id " is defined in source and cannot be "
                           "redefined at runtime — change its behaviour with a "
                           "params file under .brainyard/programs instead")
                      {:id id})))
    (let [p      (->predictor rec)
          sig    (:signature p)
          tid    (tool-id name)
          invoke (fn [args]
                   (let [inputs (dissoc args :agent :parent-agent :agent-session
                                        :_deftool$id :_deftool$type
                                        :_deftool$description
                                        :_deftool$input-schema
                                        :_deftool$output-schema)
                         ;; Re-resolve the predictor by id rather than closing
                         ;; over `p`: re-creating a predictor replaces the
                         ;; registry entry, and a stale closure would keep
                         ;; serving the previous signature from an already
                         ;; rebound symbol.
                         p'     (or (clj-llm/get-predictor id) p)
                         r      (clj-llm/run-predictor p' inputs)]
                     ;; Deliberately NOT the raw result: it also carries
                     ;; :predictor-id, :params-source (a filesystem path), the
                     ;; usage map and the raw completion — useful to
                     ;; `program$eval`, noise in a tool result an LLM reads on
                     ;; every call.
                     (cond-> {:outputs (:outputs r)}
                       (contains? r :valid?) (assoc :valid? (:valid? r))
                       (:reasoning r)        (assoc :reasoning (:reasoning r))
                       (:validation-errors r) (assoc :validation-errors
                                                     (:validation-errors r)))))
          tool-def {:id   tid
                    :type :tool
                    :fn   invoke
                    :meta {:id            tid
                           :type          :tool
                           :description   (description-for rec)
                           :input-schema  (tool-input-schema sig)
                           :output-schema (tool-result-schema rec sig)
                           :category      :user
                           :user-defined  true
                           ;; What tells a predictor apart from a user TOOL in
                           ;; one registry — `tool-agent$list` filters on it, so
                           ;; it does not offer to read or delete a file it has
                           ;; no reader for.
                           :predictor-id  id}}]
      (clj-llm/register-predictor! p)
      (swap! !owned assoc id file)
      (tool/register-def! tid tool-def file)
      ;; Same-turn callability from the LLM's clojure code blocks. Generic over
      ;; any !tool-defs entry — user-agents reuses the same fn for :type :agent.
      (user-tools/bind-into-live-sandbox! tool-def)
      tid)))

(defn unregister!
  "Drop a predictor from both registries. Returns true when it was there."
  [id name]
  (let [had (some? (clj-llm/get-predictor id))]
    (clj-llm/unregister-predictor! id)
    (swap! !owned dissoc id)
    (swap! tool/!tool-defs dissoc (tool-id name))
    had))

(defonce ^{:private true
           :doc "Roots whose records are registered this process. A defonce ATOM
  holding a set — NOT a defonce side effect: the native-image policy initializes
  ai.brainyard.* at BUILD time, so a load-time scan would bake the build
  machine's directory listing into the image heap and never read the user's.
  Same guard shape, and same reason, as boot/register-skills-once!."}
  !registered
  (atom #{}))

(defn reset-registry!
  "Drop the process guard so the next `ensure-registered!` re-scans. Tests."
  []
  (reset! !registered #{}))

(defn register-persisted!
  "Register every persisted record. A record that fails to compile is warned
   about by FILE and skipped — a broken signature must not cost the user their
   other predictors, and the file is what they open to fix it. Returns the ids
   registered."
  [dirs]
  (->> (read-persisted dirs)
       (keep (fn [rec]
               (try (register! rec) (:id rec)
                    (catch Exception e
                      (mulog/warn ::register-predictor-failed
                                  :id (:id rec) :file (:file rec)
                                  :error (ex-message e))
                      nil))))
       vec))

(defn ensure-registered!
  "Idempotent boot registration for this project's + user's predictors, once
   per root pair per process. Marks before doing and rolls the mark back on a
   throw, so a transient FS error stays transient (same shape as
   `user-tools/ensure-registered!`)."
  [dirs]
  (let [k [(predictors-root dirs :project) (predictors-root dirs :user)]]
    (when-not (contains? @!registered k)
      (swap! !registered conj k)
      (try
        (let [ids (register-persisted! dirs)]
          (when (seq ids) (mulog/info ::user-predictors-registered :ids ids))
          ids)
        (catch Throwable t (swap! !registered disj k) (throw t))))))

;; ============================================================================
;; Validate / create / read / delete
;; ============================================================================

(defn- current-dirs
  "Dirs from the current agent session, falling back to `init-dirs!`. Resolved
   at runtime for the same reason `user-tools` does it: `core.protocol` sits in
   a load graph this ns should not pin."
  []
  (or (when-let [a (some-> (requiring-resolve 'ai.brainyard.agent.core.protocol/*current-agent*)
                           deref)]
        (some-> (:!session a) deref
                ((or (requiring-resolve 'ai.brainyard.agent.core.session/get-session-config)
                     (constantly nil))
                 :dirs)))
      (config/init-dirs!)))

(defn- coerce-fields
  "Normalize a caller-supplied field map. Tool-call args arrive as JSON, which
   cannot express a keyword-headed vector or a keyword key, so the LLM passes an
   EDN STRING (`\"{:entry [:string {:desc \\\"…\\\"}]}\"`); in-process callers
   pass the real map. Throws with a readable message on unparseable EDN so the
   shape checks downstream see a value, not a crash."
  [label v]
  (cond
    (nil? v)    nil
    (map? v)    v
    (string? v) (let [s (str/trim v)]
                  (when-not (str/blank? s)
                    (let [parsed (try (edn/read-string s)
                                      (catch Exception e
                                        (throw (ex-info (str label " is not readable EDN: "
                                                             (ex-message e))
                                                        {label v}))))]
                      (when-not (map? parsed)
                        (throw (ex-info (str label " must be a field map like "
                                             "{:field [:string {:desc \"…\"}]}")
                                        {label v})))
                      parsed)))
    :else       (throw (ex-info (str label " must be a field map, or an EDN string of one")
                                {label v}))))

(defn- coerce-keyword [v allowed]
  (let [k (cond (keyword? v) v
                (and (string? v) (not (str/blank? v))) (keyword (str/trim v))
                :else nil)]
    (when (contains? allowed k) k)))

(defn- coerce-order [v]
  (cond
    (nil? v)    nil
    (vector? v) (mapv #(if (keyword? %) % (keyword (str %))) v)
    (string? v) (let [s (str/trim v)]
                  (when-not (str/blank? s)
                    (let [p (try (edn/read-string s) (catch Exception _ nil))]
                      (when (sequential? p) (mapv #(if (keyword? %) % (keyword (str %))) p)))))
    (sequential? v) (mapv #(if (keyword? %) % (keyword (str %))) v)
    :else nil))

(defn- draft
  "Normalize command args into a record, or throw with a readable message."
  [{:keys [id name instructions inputs outputs strategy tier description
           input-order output-order]}]
  (let [n        (parse-name (or name id))
        inputs   (coerce-fields :inputs inputs)
        outputs  (coerce-fields :outputs outputs)
        in-order (coerce-order input-order)
        strategy (or (coerce-keyword strategy #{:predict :cot}) :predict)]
    (cond-> {:name         n
             :id           (when n (predictor-id n))
             :instructions (some-> instructions str str/trim)
             :inputs       (or inputs {})
             :outputs      (or outputs {})
             ;; Persisted ALWAYS, at any field count — see `ordered-fields`.
             :input-order  (vec (or (seq in-order) (keys (or inputs {}))))
             :strategy     strategy}
      (seq (coerce-order output-order)) (assoc :output-order (coerce-order output-order))
      (coerce-keyword tier #{:light :standard :deep}) (assoc :tier (coerce-keyword tier #{:light :standard :deep}))
      (not (str/blank? (str description))) (assoc :description (str/trim (str description))))))

(defn validate-draft
  "Structured report for a draft — offline, persists and registers nothing.
   Never throws."
  [args]
  (try
    (let [{:keys [id name inputs outputs strategy input-order] :as rec} (draft args)
          id-ok       (some? name)
          collision   (and id-ok (some? (clj-llm/get-predictor id)))
          builtin     (and id-ok (source-predictor? id))
          outputs-ok  (boolean (seq outputs))
          ;; A `reasoning` output under :cot is always empty: chain-of-thought
          ;; dissocs the key before validating, then fills the declared field
          ;; with a schema default. Refused rather than silently neutered.
          cot-clash   (and (= :cot strategy) (contains? outputs :reasoning))
          ;; EDN read loses map order past 8 entries, so past 8 the declared
          ;; order is already gone by the time we see it — it has to be stated.
          ;; Checked against the RAW argument, not the drafted record: `draft`
          ;; fills :input-order from (keys inputs), which for a 9-entry hash-map
          ;; is a hash order that would satisfy any set-equality test while
          ;; being exactly the scrambling this rule exists to catch.
          stated      (seq (coerce-order (:input-order args)))
          order-ok    (or (<= (count inputs) 8)
                          (= (set stated) (set (keys inputs))))
          [sig sig-err] (if (and id-ok outputs-ok order-ok)
                          (try [(compile-record rec) nil]
                               (catch Exception e [nil (ex-message e)]))
                          [nil nil])
          errors (cond-> []
                   (not id-ok)
                   (conj (str ":name must match ^[a-z][a-z0-9-]*$ and live in the "
                              "`user/` namespace (given: " (pr-str (or name (:name args) id)) ")"))
                   builtin
                   (conj (str id " is defined in source — change it with a params file "
                              "under .brainyard/programs, not by redefining it"))
                   (not outputs-ok)
                   (conj ":outputs must declare at least one field (an empty output map compiles to an empty schema and the call returns nothing)")
                   cot-clash
                   (conj ":cot returns reasoning alongside :outputs — remove the :reasoning output field")
                   (not order-ok)
                   (conj ":input-order is required past 8 inputs (EDN map order is lost above that size) and must name every input")
                   (some? sig-err)
                   (conj (str "signature does not compile: " sig-err)))]
      (cond-> {:valid        (empty? errors)
               :id           id
               :id-ok        (boolean id-ok)
               :collision    (boolean collision)
               :builtin      (boolean builtin)
               :outputs-ok   outputs-ok
               :signature-ok (some? sig)
               :errors       errors}
        sig (assoc :input-order (vec (:input-order sig))
                   :output-keys (vec (keys (:outputs sig))))))
    (catch Exception e
      {:valid false :id-ok false :collision false :builtin false
       :outputs-ok false :signature-ok false
       :errors [(ex-message e)]})))

(defn define-predictor!
  "Validate → persist `<root>/user/<name>.edn` → register. Returns
   `{:id :name :persisted :tool}` or throws."
  [dirs args & {:keys [scope] :or {scope :project}}]
  (let [report (validate-draft args)]
    (when-not (:valid report)
      (throw (ex-info (str "invalid predictor: " (str/join "; " (:errors report)))
                      {:errors (:errors report)})))
    (let [rec  (draft args)
          root (or (predictors-root dirs scope)
                   (predictors-root dirs :project)
                   (predictors-root dirs :user)
                   (throw (ex-info "cannot resolve a .brainyard/predictors directory" {})))
          path (write-record! root (:name rec) (dissoc rec :file))
          tid  (register! (assoc rec :file path))]
      (mulog/info ::define-predictor :id (:id rec) :file path)
      {:id (:id rec) :name (:name rec) :persisted path :tool (clojure.core/name tid)})))

(defn list-user-predictors
  "Summaries of every registered authored predictor, sorted by id."
  []
  (->> (vals @tool/!tool-defs)
       (keep (fn [td]
               (let [m (:meta td)]
                 (when-let [pid (:predictor-id m)]
                   (let [p (clj-llm/get-predictor pid)]
                     {:id           pid
                      :tool         (clojure.core/name (:id m))
                      :description  (:description m)
                      :strategy     (clojure.core/name (or (:strategy p) :predict))
                      :inputs       (vec (:input-order (:signature p)))
                      :outputs      (vec (keys (:outputs (:signature p))))
                      :params       (some-> (:source (clj-llm/resolve-params p)) str)})))))
       (sort-by :id)
       vec))

(defn read-user-predictor
  "The persisted record for one authored predictor, plus where its params
   currently resolve from.

   Reports the params SOURCE, not their content: demos are text harvested from
   traces and belong in `programs/<id>.edn` and a REVIEW.md, not echoed into
   every transcript that asks what a predictor is."
  [dirs name]
  (if-let [n (parse-name name)]
    (let [id  (predictor-id n)
          rec (first (filter #(= n (:name %)) (read-persisted dirs)))
          p   (clj-llm/get-predictor id)]
      (cond
        rec (let [src (when p (some-> (:source (clj-llm/resolve-params p)) str))]
              (cond-> (dissoc rec :file)
                true (assoc :persisted (:file rec) :registered (some? p))
                ;; Omitted rather than nil: "no params file" is the normal
                ;; state, and a nil in the result reads as a missing value.
                src  (assoc :params-source src)))
        p   {:id id :name n :registered true :note "registered but no definition file on disk"}
        :else {:error (str "no user predictor named " (pr-str name))}))
    {:error (str "not a user predictor name: " (pr-str name))}))

(defn- programs-leftovers
  "Paths under `.brainyard/programs` that belong to `id` and that a delete
   deliberately does NOT touch — params, datasets, evals, proposals."
  [dirs id]
  (->> [:project :user]
       (keep #(config/brainyard-subdir dirs "programs" %))
       (mapcat (fn [root] [(io/file (str root) (str id ".edn"))
                           (io/file (str root) (str id))]))
       (filter #(.exists ^File %))
       (mapv #(.getPath ^File %))))

(defn delete-user-predictor!
  "Unregister and delete the DEFINITION file. Params, datasets, evals and
   proposals under `.brainyard/programs/<id>` are left in place and reported —
   a delete that was really a rename should not silently discard a compile
   someone paid for, and re-creating the id picks them back up."
  [dirs name]
  (if-let [n (parse-name name)]
    (let [id   (predictor-id n)
          recs (filter #(= n (:name %)) (read-persisted dirs))
          had  (unregister! id n)
          left (programs-leftovers dirs id)]
      (if (or had (seq recs))
        (do
          (doseq [r recs] (io/delete-file (io/file (:file r)) true))
          (mulog/info ::delete-predictor :id id :files (mapv :file recs))
          (cond-> {:deleted id :files (mapv :file recs)}
            (seq left) (assoc :kept left
                              :note "program params/datasets/proposals kept — re-creating this id picks them back up")))
        {:error (str "no user predictor named " (pr-str name))}))
    {:error (str "not a user predictor name: " (pr-str name))}))

;; ============================================================================
;; Commands
;; ============================================================================

(def ^:private fields-desc
  "Malli field map: a native map (code channel) or an EDN string (tool-calls channel), e.g. {:entry [:string {:desc \"One changelog line\"}]}")

(defcommand predictor$validate
  "DRY-RUN a predictor draft: compile the signature and check the name, outputs
   and strategy. Persists nothing, registers nothing, contacts no provider.
   Run before predictor$create and iterate until :valid is true."
  (fn [& {:as args}] (validate-draft args))
  :input-schema  [:map
                  [:name    [:string {:desc "lowercase-kebab name (bare, or `user/<name>`) — no other namespace"}]]
                  [:inputs  {:desc fields-desc} ::acs/map-object-arg]
                  [:outputs {:desc fields-desc} ::acs/map-object-arg]
                  [:instructions {:optional true} [:string {:desc "What the transformation is — the signature's objective"}]]
                  [:strategy {:optional true} [:string {:desc "predict (default) | cot (adds a reasoning sibling to the result)"}]]
                  [:tier     {:optional true} [:string {:desc "light | standard | deep — the default work tier via :agent-lm-tiers"}]]
                  [:input-order {:optional true :desc "Input keys in ascending volatility. REQUIRED past 8 inputs (EDN map order is lost above that size); order is prompt-cache significant."} ::acs/vector-object-arg]
                  [:output-order {:optional true :desc "Output keys in render order"} ::acs/vector-object-arg]
                  [:description {:optional true} [:string {:desc "One-line tool card (default: first line of :instructions)"}]]]
  :output-schema [:map
                  [:valid        [:boolean {:desc "True iff every check passed"}]]
                  [:id           {:optional true} [:string {:desc "The registry id this would take, e.g. user/changelog-classify"}]]
                  [:id-ok        [:boolean {:desc "Name is kebab and in the user/ namespace"}]]
                  [:collision    [:boolean {:desc "An id already registered — create would OVERWRITE (that is how update is expressed)"}]]
                  [:builtin      [:boolean {:desc "Collides with a SOURCE predictor — always fatal; use a params file instead"}]]
                  [:outputs-ok   [:boolean {:desc "At least one output field"}]]
                  [:signature-ok [:boolean {:desc "compile-signature derived a JSON Schema for every output"}]]
                  [:input-order  {:optional true} [:any {:desc "Resolved input order"}]]
                  [:output-keys  {:optional true} [:any {:desc "Resolved output keys"}]]
                  [:errors       [:any {:desc "Vector of human-readable failure lines"}]]])

(defcommand predictor$create
  "Author a PERSISTENT predictor from a signature. It registers as
   user$predictor$<name> (callable as a tool in the SAME turn it is created) and
   as the predictor id user/<name>, so program$build-dataset / program$eval /
   program$compile work on it immediately. Requires a passing predictor$validate."
  (fn [& {:as args}]
    (try
      (define-predictor! (current-dirs) args
        :scope (or (coerce-keyword (:scope args) #{:project :user}) :project))
      (catch Exception e {:error (str "predictor$create failed: " (ex-message e))})))
  :input-schema  [:map
                  [:name    [:string {:desc "lowercase-kebab name (bare, or `user/<name>`)"}]]
                  [:inputs  {:desc fields-desc} ::acs/map-object-arg]
                  [:outputs {:desc fields-desc} ::acs/map-object-arg]
                  [:instructions {:optional true} [:string {:desc "What the transformation is — the signature's objective"}]]
                  [:strategy {:optional true} [:string {:desc "predict (default) | cot"}]]
                  [:tier     {:optional true} [:string {:desc "light | standard | deep"}]]
                  [:input-order {:optional true :desc "Input keys in ascending volatility; REQUIRED past 8 inputs"} ::acs/vector-object-arg]
                  [:output-order {:optional true :desc "Output keys in render order"} ::acs/vector-object-arg]
                  [:description {:optional true} [:string {:desc "One-line tool card"}]]
                  [:scope    {:optional true} [:string {:desc "project (default) | user — which .brainyard/predictors the file lands in"}]]]
  :output-schema [:map
                  [:id        {:optional true} [:string {:desc "Predictor id, e.g. user/changelog-classify"}]]
                  [:name      {:optional true} [:string {:desc "Predictor name"}]]
                  [:tool      {:optional true} [:string {:desc "Registered tool id, e.g. user$predictor$changelog-classify"}]]
                  [:persisted {:optional true} [:string {:desc "Path the definition was written to"}]]
                  [:error     {:optional true} [:string {:desc "Error if the definition was refused"}]]])

(defcommand predictor$list
  "List authored predictors (id, tool symbol, strategy, fields, params source)."
  (fn [& _] {:predictors (list-user-predictors)})
  :input-schema  [:map]
  :output-schema [:map [:predictors [:any {:desc "Vector of {:id :tool :description :strategy :inputs :outputs :params}"}]]])

(defcommand predictor$read
  "Read one authored predictor's definition by name (no user/ prefix needed).
   Reports where its params resolve from, not their content."
  (fn [& {:as args}]
    (if (str/blank? (str (:name args)))
      {:error "name is required"}
      (read-user-predictor (current-dirs) (:name args))))
  :input-schema  [:map [:name [:string {:desc "Predictor name, e.g. \"changelog-classify\""}]]]
  :output-schema [:map
                  [:id            {:optional true} [:string {:desc "Predictor id"}]]
                  [:instructions  {:optional true} [:string {:desc "The signature's objective"}]]
                  [:inputs        {:optional true} [:any {:desc "Input field map"}]]
                  [:outputs       {:optional true} [:any {:desc "Output field map"}]]
                  [:input-order   {:optional true} [:any {:desc "Declared input order"}]]
                  [:strategy      {:optional true} [:any {:desc "predict | cot"}]]
                  [:tier          {:optional true} [:any {:desc "light | standard | deep, when one was declared"}]]
                  [:persisted     {:optional true} [:string {:desc "Definition file"}]]
                  [:params-source {:optional true} [:string {:desc "Where params resolve from now (a programs/ path, :default, or absent)"}]]
                  [:error         {:optional true} [:string {:desc "Error if not found"}]]])

(defcommand predictor$delete
  "Delete an authored predictor's DEFINITION and unregister it. Its params,
   datasets, evals and proposals under .brainyard/programs are kept and
   reported. Destructive — confirm first."
  (fn [& {:as args}]
    (if (str/blank? (str (:name args)))
      {:error "name is required"}
      (delete-user-predictor! (current-dirs) (:name args))))
  :input-schema  [:map [:name [:string {:desc "Predictor name to delete"}]]]
  :output-schema [:map
                  [:deleted {:optional true} [:string {:desc "Deleted predictor id"}]]
                  [:files   {:optional true} [:any {:desc "Definition files removed"}]]
                  [:kept    {:optional true} [:any {:desc "programs/ artifacts deliberately left in place"}]]
                  [:note    {:optional true} [:string {:desc "Why they were kept"}]]
                  [:error   {:optional true} [:string {:desc "Error if not found"}]]])

(def predictors-commands
  "All predictor-authoring commands, for binding into predictor-agent. Mirrors
   `user-tools/tools-commands`. Deliberately NOT in the common roster: the
   measurement half (`program$*`) is already there, authoring is a specialist
   act."
  [#'predictor$create #'predictor$validate #'predictor$list
   #'predictor$read #'predictor$delete])
