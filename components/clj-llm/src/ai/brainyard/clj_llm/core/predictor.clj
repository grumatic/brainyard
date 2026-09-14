;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.clj-llm.core.predictor
  "Parameterized predictors — the DSPy `Predict` module as a value.

   A signature says WHAT a transformation is; a predictor is a NAMED use of
   one, with parameters that can change without a source edit:

     :instructions  replaces the signature's instructions
     :field-descs   {field desc} overrides for input/output field descriptions
     :demos         [{:inputs {…} :outputs {…} :reasoning \"…\"?}] examples
     :lm            provider/model label used when the call site names no LM
     :tier          :light | :standard | :deep, same fallback position as :lm

   Parameters are DATA resolved at call time, highest precedence first:

     *params* (with-params)  >  params roots (EDN files)  >  defpredictor :params

   The first layer that has a record for the predictor wins WHOLE — layers are
   not deep-merged, so a params file always means exactly what it says. With no
   record anywhere the call is byte-identical to calling the signature directly.

   Two rules keep params a hint rather than a second configuration system:

   - **The call site's `:lm-config` always wins.** It is the user's configured
     model; a params file compiled elsewhere must not silently re-route it. A
     mismatch with the model the params were compiled for is logged once.
   - **Demos arrive only through params, never through call opts.** A demo is
     text promoted into the system message, and a bootstrapped one contains
     whatever the program read. Routing them through reviewed params is what
     keeps a call site's own inputs from choosing their influence.

   Tracing (`with-trace`) records every predictor call made inside its dynamic
   extent — the raw material an optimizer turns into demos. Bindings convey
   into `future`/`pmap`, so parallel sub-calls are captured too."
  (:require [ai.brainyard.clj-llm.core.chain-of-thought :as cot]
            [ai.brainyard.clj-llm.core.llm :as llm]
            [ai.brainyard.clj-llm.core.predict :as predict]
            [ai.brainyard.clj-llm.core.signature :as signature]
            [ai.brainyard.mulog.interface :as mulog]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [malli.core :as m]
            [malli.error :as me]))

;; ============================================================================
;; Signature Transforms
;; ============================================================================

(defn- recompile
  "Rebuild a signature from changed parts, keeping any extra keys a caller
   assoc'd onto the original. :output-json-schema is re-derived, so a transform
   can never leave the schema describing different fields than the prompt."
  [sig {:keys [instructions inputs outputs input-order]}]
  (merge sig
         (signature/compile-signature (:name sig)
                                      instructions
                                      inputs
                                      outputs
                                      input-order)))

(defn- sig-parts [sig]
  (select-keys sig [:instructions :inputs :outputs :input-order]))

(defn- ordered-map
  "Map from [k v] pairs that keeps pair order at any size. Signature field maps
   are ORDER-significant (render order, JSON schema order), and a hash-map past
   8 entries loses it."
  [pairs]
  (apply array-map (mapcat identity pairs)))

(defn with-instructions
  "Signature with its instructions replaced."
  [sig instructions]
  (recompile sig (assoc (sig-parts sig) :instructions instructions)))

(defn- set-field-desc
  "Field schema with its :desc set, or nil when the schema has no props slot
   we can safely write (a qualified registry ref — wrapping one would change
   the JSON Schema it derives, not just its description)."
  [raw desc]
  (cond
    (qualified-keyword? raw) nil
    (keyword? raw) [raw {:desc desc}]
    (and (vector? raw) (keyword? (first raw)) (map? (second raw)))
    (assoc-in raw [1 :desc] desc)
    (and (vector? raw) (keyword? (first raw)))
    (into [(first raw) {:desc desc}] (rest raw))
    :else nil))

(defn with-field-descs
  "Signature with field descriptions overridden from `{field desc}`, applied to
   inputs and outputs alike. A field whose schema cannot carry a description
   (a registry ref) is left unchanged and logged; an unknown field is ignored."
  [sig descs]
  (if (empty? descs)
    sig
    (let [update-fields
          (fn [fields]
            (ordered-map
             (for [[k raw] fields]
               (if-let [d (get descs k)]
                 (if-let [raw' (set-field-desc raw d)]
                   [k raw']
                   (do (mulog/warn ::field-desc-not-settable :signature (:name sig) :field k)
                       [k raw]))
                 [k raw]))))]
      (recompile sig (-> (sig-parts sig)
                         (update :inputs update-fields)
                         (update :outputs update-fields))))))

(defn prepend-output
  "Signature with a new FIRST output field (e.g. a rationale, as DSPy's
   ChainOfThought does). Position is the point: it is emitted before the rest."
  [sig k schema]
  (recompile sig (update (sig-parts sig) :outputs
                         #(ordered-map (cons [k schema] (remove (fn [[k' _]] (= k k')) %))))))

(defn append-input
  "Signature with a new LAST input field. Last is deliberate: inputs render in
   ascending volatility, and a field added by a module (feedback, candidate
   completions) is the most per-call-volatile thing in the message."
  [sig k schema]
  (let [{:keys [inputs input-order] :as parts} (sig-parts sig)
        order (vec (remove #{k} (or input-order (keys inputs))))]
    (recompile sig (assoc parts
                          :inputs (ordered-map (concat (remove (fn [[k' _]] (= k k')) inputs)
                                                       [[k schema]]))
                          :input-order (conj order k)))))

;; ============================================================================
;; Params Schema
;; ============================================================================

(def Demo
  [:map
   [:inputs [:map-of :keyword :any]]
   [:outputs [:map-of :keyword :any]]
   [:reasoning {:optional true} :string]
   [:source {:optional true} :map]])

(def Params
  [:map
   [:instructions {:optional true} :string]
   [:field-descs {:optional true} [:map-of :keyword :string]]
   [:demos {:optional true} [:sequential Demo]]
   [:lm {:optional true} :string]
   [:tier {:optional true} [:enum :light :standard :deep]]
   [:max-demo-value-chars {:optional true} pos-int?]
   [:version {:optional true} :any]
   [:compiled-by {:optional true} :map]])

(def ^:private params-validator (m/validator Params))
(def ^:private params-explainer (m/explainer Params))

(defn validate-params
  "`{:valid? bool :errors …}` for a params record."
  [params]
  (if (params-validator params)
    {:valid? true}
    {:valid? false :errors (me/humanize (params-explainer params))}))

;; ============================================================================
;; Predictor Values + Registry
;; ============================================================================

(def ^:private id-re
  "Ids double as relative params-file paths (`memory/graph-extract` →
   `<root>/memory/graph-extract.edn`), so they are restricted to segments that
   cannot escape a root: no empty, `.` or `..` segment, no separators beyond
   `/`."
  #"[A-Za-z0-9_-][A-Za-z0-9_.-]*(/[A-Za-z0-9_-][A-Za-z0-9_.-]*)*")

(defn valid-id? [id]
  (and (string? id)
       (boolean (re-matches id-re id))
       (not-any? #{"." ".."} (str/split id #"/"))))

(defn predictor
  "Build a predictor value. spec:
     :id        string, e.g. \"memory/graph-extract\" (required, stable — it is
                the params-file path and the trace key)
     :signature compiled signature (required)
     :strategy  :predict (default) | :cot
     :tier      default tier when params and call site name no LM
     :params    default params, the lowest-precedence layer"
  [{:keys [id signature strategy tier params] :or {strategy :predict}}]
  (when-not (valid-id? id)
    (throw (ex-info (str "Invalid predictor id: " (pr-str id)) {:id id})))
  (when-not (and (map? signature) (:output-json-schema signature))
    (throw (ex-info (str "Predictor " id " needs a compiled signature") {:id id})))
  (when-not (#{:predict :cot} strategy)
    (throw (ex-info (str "Predictor " id ": unknown strategy " strategy) {:id id :strategy strategy})))
  (when params
    (let [{:keys [valid? errors]} (validate-params params)]
      (when-not valid?
        (throw (ex-info (str "Predictor " id ": invalid default params") {:id id :errors errors})))))
  (cond-> {:predictor/id id
           :signature    signature
           :strategy     strategy}
    tier   (assoc :tier tier)
    params (assoc :default-params params)))

(defonce ^:private !registry (atom {}))

(defn register!
  "Register a predictor by id (last definition wins, so a reloaded namespace
   replaces rather than duplicates). Returns the predictor."
  [p]
  (swap! !registry assoc (:predictor/id p) p)
  p)

(defn list-predictors
  "All registered predictors, sorted by id."
  []
  (sort-by :predictor/id (vals @!registry)))

(defn get-predictor [id] (get @!registry id))

(defmacro defpredictor
  "Define and register a predictor.

     (defpredictor graph-extract
       {:id \"memory/graph-extract\" :signature GraphExtraction :strategy :predict})"
  [sym spec]
  `(def ~sym (register! (predictor ~spec))))

;; ============================================================================
;; Params Store
;; ============================================================================

(def ^:dynamic *params*
  "`{predictor-id params}` bound by `with-params` — the highest-precedence
   layer. This is how an optimizer evaluates a candidate without writing it."
  nil)

(defmacro with-params
  "Evaluate body with `{predictor-id params}` layered over any outer binding."
  [params-map & body]
  `(binding [*params* (merge *params* ~params-map)]
     ~@body))

(defonce ^:private !roots (atom []))

(defn set-params-roots!
  "Install the directories params files are read from, highest precedence
   first. `clj-llm` sits below the agent and cannot resolve `.brainyard`
   itself, so the app installs them (cf. `set-catalog-cache-root!`). Nil
   entries are dropped."
  [roots]
  (reset! !roots (vec (remove nil? roots))))

(defn params-roots [] @!roots)

(defn params-file
  "The params file for `id` under `root`."
  ^java.io.File [root id]
  (io/file root (str id ".edn")))

(defonce ^:private !file-cache (atom {}))

(defn- read-params-file
  "Params from `f`, or nil when absent/unreadable/invalid (logged). Cached on
   path + mtime + length: resolution runs on every call, and a params file
   changes only when a compile is accepted."
  [^java.io.File f]
  (when (.isFile f)
    (let [path (.getPath f)
          stamp [(.lastModified f) (.length f)]
          cached (get @!file-cache path)]
      (if (= stamp (:stamp cached))
        (:params cached)
        (let [params (try
                       (let [p (edn/read-string (slurp f))
                             {:keys [valid? errors]} (validate-params p)]
                         (if valid?
                           p
                           (do (mulog/warn ::invalid-params-file :path path :errors errors)
                               nil)))
                       (catch Exception e
                         (mulog/warn ::unreadable-params-file :path path :error (ex-message e))
                         nil))]
          (swap! !file-cache assoc path {:stamp stamp :params params})
          params)))))

(defn resolve-params
  "`{:params p :source s}` for a predictor — s is :dynamic, the file path, or
   :default — or `{}` when no layer has a record."
  [{id :predictor/id :keys [default-params]}]
  (or (when (contains? *params* id)
        {:params (get *params* id) :source :dynamic})
      (some (fn [root]
              (let [f (params-file root id)]
                (when-let [p (read-params-file f)]
                  {:params p :source (.getPath f)})))
            @!roots)
      (when default-params
        {:params default-params :source :default})
      {}))

;; ============================================================================
;; LM Resolution
;; ============================================================================

(defonce ^:private !lm-resolver (atom nil))

(defn set-lm-resolver!
  "Install `(fn [{:keys [lm tier predictor-id]}] -> lm-config|nil)`. The app
   owns tier → model mapping (`:agent-lm-tiers`) and credential policy; without
   a resolver an `:lm` label is minted by `parse-lm-str` and a `:tier` resolves
   to nothing (the default LM)."
  [f]
  (reset! !lm-resolver f))

(defonce ^:private !mismatch-warned (atom #{}))

(defn- warn-compiled-lm-mismatch!
  [id params lm-config]
  (let [compiled (get-in params [:compiled-by :lm])
        model    (:model lm-config)]
    (when (and compiled model
               (not (str/ends-with? compiled (str "/" model)))
               (not= compiled model)
               (not (contains? @!mismatch-warned [id model])))
      (swap! !mismatch-warned conj [id model])
      (mulog/warn ::params-compiled-for-other-lm
                  :predictor-id id :compiled-for compiled :model model))))

(defn- resolve-lm
  "Call-site lm-config > params :lm > params/predictor :tier > nil (the
   default LM). An unresolvable label or tier degrades to the next step: params
   are a hint, and a hint that cannot be honoured must not fail the call."
  [{id :predictor/id :as p} params lm-config]
  (or lm-config
      (when-let [label (:lm params)]
        (or (if-let [f @!lm-resolver]
              (f {:lm label :predictor-id id})
              (llm/parse-lm-str label))
            (do (mulog/warn ::params-lm-unresolved :predictor-id id :lm label) nil)))
      (when-let [tier (or (:tier params) (:tier p))]
        (when-let [f @!lm-resolver]
          (f {:tier tier :predictor-id id})))))

;; ============================================================================
;; Tracing
;; ============================================================================

(def ^:dynamic *trace*
  "Atom of trace entries while inside `with-trace`, else nil."
  nil)

(defmacro with-trace
  "Evaluate body with a fresh trace atom bound to `sym`; every predictor call
   in the dynamic extent (including conveyed futures) appends an entry.

     (with-trace [t] (run p inputs) @t)"
  [[sym] & body]
  `(let [~sym (atom [])]
     (binding [*trace* ~sym]
       ~@body)))

(def ^:dynamic *trace-context*
  "Map attached as `:context` to every trace entry recorded in its extent.
   The caller knows things this component cannot — which agent, which BT node
   — and a sink needs them to decide where an entry belongs."
  nil)

(defmacro with-trace-context
  "Evaluate body with `ctx` merged over any outer trace context."
  [ctx & body]
  `(binding [*trace-context* (merge *trace-context* ~ctx)]
     ~@body))

(defonce ^:private !trace-sink (atom nil))

(defn set-trace-sink!
  "Install `(fn [entry])` called for EVERY predictor call, traced or not —
   the persistent capture path (e.g. a per-session predictions log). Nil
   uninstalls. Sink failures are swallowed: capture must never fail a call."
  [f]
  (reset! !trace-sink f))

(defn- record-trace! [entry]
  (let [entry (cond-> entry *trace-context* (assoc :context *trace-context*))]
    (when-let [t *trace*]
      (swap! t conj entry))
    (when-let [f @!trace-sink]
      (try (f entry)
           (catch Exception e
             (mulog/warn ::trace-sink-failed :error (ex-message e)))))))

;; ============================================================================
;; Run
;; ============================================================================

(def default-max-demo-value-chars
  "Per-value cap on demo input strings. A demo harvested from a real trace can
   carry a whole file; it teaches the shape of the call, not its bulk."
  2000)

(defn- truncate-value [v max-chars]
  (if (and (string? v) (> (count v) max-chars))
    (str (subs v 0 max-chars) "…[truncated]")
    v))

(defn prepare-demos
  "Demos from params, with long input strings truncated."
  [params]
  (let [max-chars (or (:max-demo-value-chars params) default-max-demo-value-chars)]
    (mapv (fn [demo]
            (update demo :inputs update-vals #(truncate-value % max-chars)))
          (:demos params))))

(defn apply-params
  "The signature a predictor runs with under `params`."
  [sig params]
  (cond-> sig
    (:instructions params) (with-instructions (:instructions params))
    (seq (:field-descs params)) (with-field-descs (:field-descs params))))

(defn run
  "Run a predictor on `inputs`. Accepts the same kwargs as `predict` /
   `chain-of-thought` except `:demos`, which only params may supply.

   Returns the strategy's result plus :predictor-id and :params-source."
  [{id :predictor/id :keys [strategy] :as p} inputs & {:keys [lm-config] :as opts}]
  (let [{:keys [params source]} (resolve-params p)
        sig    (apply-params (:signature p) params)
        demos  (prepare-demos params)
        lm     (resolve-lm p params lm-config)
        _      (when lm (warn-compiled-lm-mismatch! id params lm))
        op     (case strategy :predict predict/predict :cot cot/chain-of-thought)
        call   (cond-> (dissoc opts :lm-config :demos)
                 lm          (assoc :lm-config lm)
                 (seq demos) (assoc :demos demos))
        base   {:predictor-id   id
                :inputs         inputs
                :params-source  source
                :params-version (:version params)
                :model          (:model lm)}
        t0     (System/currentTimeMillis)]
    (try
      (let [result (apply op sig inputs (mapcat identity call))]
        (record-trace! (cond-> (assoc base
                                      :outputs (:outputs result)
                                      :elapsed-ms (- (System/currentTimeMillis) t0))
                         (:reasoning result) (assoc :reasoning (:reasoning result))
                         (:usage result)     (assoc :usage (:usage result))
                         (contains? result :valid?) (assoc :valid? (:valid? result))))
        (assoc result :predictor-id id :params-source source))
      (catch Exception e
        (record-trace! (assoc base
                              :error (ex-message e)
                              :elapsed-ms (- (System/currentTimeMillis) t0)))
        (throw e)))))
