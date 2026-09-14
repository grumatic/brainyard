;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.clj-llm.core.prompt
  "Prompt construction from signatures for LLM calls.

   Follows DSPy conventions:
   - System message: field descriptions (with types) → JSON schema → output format → task instructions (last)
   - User message: input values → output field reminder
   - CoT: reasoning prepended as regular output field (position forces reasoning-first)
   - JSON schema always included in system prompt (not just API-level enforcement)"
  (:require [ai.brainyard.clj-llm.core.schema :as schema]
            [ai.brainyard.clj-llm.core.usage :as usage]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [malli.core :as m]))

;; ============================================================================
;; Field Description Helpers
;; ============================================================================

(defn- malli-type-name
  "Resolve a Malli schema to a human-readable type name for prompt display.
   Uses m/deref to resolve keyword schema references from the registry."
  [raw-schema]
  (let [{:keys [schema]} (schema/parse-malli-field raw-schema)
        resolved (try (m/deref (m/schema schema)) (catch Exception _ nil))
        t (when resolved (m/type resolved))]
    (case t
      :string     "string"
      :boolean    "bool"
      :int        "int"
      :double     "float"
      :vector     "list"
      :sequential "list"
      :set        "set"
      :map        "object"
      "string")))

(defn- field-description
  "Build a numbered, typed field description.
   Format: `N. \\`field_name\\` (type): description`
   Resolves descriptions from Malli registry when not in the field spec directly."
  [idx field-key raw-schema]
  (let [{:keys [desc schema]} (schema/parse-malli-field raw-schema)
        ;; If desc not in the field spec, resolve from Malli registry
        desc (or desc (try (:desc (m/properties (m/deref (m/schema schema))))
                           (catch Exception _ nil)))
        type-name (malli-type-name raw-schema)]
    (if desc
      (str (inc idx) ". `" (name field-key) "` (" type-name "): " desc)
      (str (inc idx) ". `" (name field-key) "` (" type-name ")"))))

(defn- indexed-fields
  "Build numbered field descriptions from a fields map."
  [fields]
  (str/join "\n" (map-indexed (fn [i [k v]] (field-description i k v)) fields)))

(defn- indexed-fields-raw
  "Build numbered field descriptions from a seq of [key schema] pairs.
   Handles both Malli schemas and plain string descriptions."
  [field-pairs]
  (str/join "\n"
            (map-indexed (fn [i [k v]]
                           (if (string? v)
                             (str (inc i) ". `" (name k) "` (string): " v)
                             (field-description i k v)))
                         field-pairs)))

;; ============================================================================
;; Chain-of-Thought Reasoning Field
;; ============================================================================

(def reasoning-field-desc
  "Description for the CoT `reasoning` output field — the single source of
   truth, used by the CoT system message here and by the augmented JSON
   schema in chain-of-thought.clj (so the model reads the same contract in
   both places).

   Deliberately prescriptive about LENGTH. DSPy's structural position alone
   forces reasoning-first, but not brevity, and unbounded reasoning is paid
   for twice in the agent loop: once as output tokens, then again every
   subsequent iteration, because `:last-reasoning` becomes the iteration
   record's `:thought` and is replayed in the prompt UNTRUNCATED."
  (str "Brief reasoning leading to the answer: at most 3 short sentences (~60 words). "
       "Plain prose only; no headings, no bullet lists, no code blocks. "
       "State the decision and why; do not restate the inputs, narrate what you "
       "are about to do, or explain the output format."))

(def ^:private reasoning-field
  "The CoT reasoning field as a [key schema] pair, prepended to the outputs."
  [:reasoning [:string {:desc reasoning-field-desc}]])

;; ============================================================================
;; JSON Schema Instruction
;; ============================================================================

(defn- json-schema-instruction
  "Build the JSON schema instruction for the system message — the shared
   `schema/json-schema-instruction`, so the DSPy prompt and the non-native
   structured-output fallback in `chat-completion` say the same thing."
  [json-schema]
  (when json-schema
    (schema/json-schema-instruction json-schema)))

;; ============================================================================
;; Output Requirements (User Message Reminder)
;; ============================================================================

(defn- output-requirements
  "Build the output field reminder appended to user messages.
   Lists all fields the LLM must produce, in order."
  [signature {:keys [chain-of-thought?]}]
  (let [output-keys (if chain-of-thought?
                      (into [:reasoning] (mapv first (:outputs signature)))
                      (mapv first (:outputs signature)))]
    (str "Respond with a JSON object containing the output fields, starting with the field "
         (str/join ", then " (map #(str "`" (name %) "`") output-keys))
         ".")))

;; ============================================================================
;; User Messages
;; ============================================================================

(defn- ordered-input-pairs
  "Deterministic render order for user-message input values: the signature's
   :input-order (declaration order — see compile-signature) first, then any
   undeclared extra keys, sorted. Skips keys absent from `inputs`.

   Order is cache-significant: with inputs declared in ascending volatility,
   the turn-stable fields form a byte-stable message prefix across
   iterations (prompt-cache Phase 2). Hand-built signatures without
   :input-order render in `inputs` map order, as before."
  [signature inputs]
  (let [order (or (:input-order signature) (vec (keys inputs)))
        extra (sort (remove (set order) (keys inputs)))]
    (for [k (concat order extra)
          :when (contains? inputs k)]
      [k (get inputs k)])))

(defn- input-line
  [[k v]]
  (str (name k) ": " (str v)))

(def ^:private min-user-cache-prefix-chars
  "Providers only cache prefixes above ~1K tokens (model-dependent minimum);
   a breakpoint on a smaller prefix wastes one of the 4 slots. ~4 chars/token."
  4000)

(defn- user-cache-prefix
  "Text of the rendered input lines for the fields BEFORE `boundary-key` in
   render order — the turn-stable user-message prefix a provider can mark as
   a cache breakpoint. Returns nil when the boundary key is absent from the
   rendered pairs, nothing precedes it, or the prefix is below the minimum
   cacheable size."
  [pairs boundary-key]
  (when boundary-key
    (let [pairs (vec pairs)
          idx (first (keep-indexed (fn [i [k _]] (when (= k boundary-key) i)) pairs))]
      (when (and idx (pos? idx))
        (let [prefix (str/join "\n" (map input-line (subvec pairs 0 idx)))]
          (when (>= (count prefix) min-user-cache-prefix-chars)
            prefix))))))

(defn- build-user-message
  "Build the user message from input values (rendered in signature-declared
   order — see ordered-input-pairs).
   Appends an output field reminder listing all required output fields."
  [signature inputs opts]
  (let [input-lines (map input-line (ordered-input-pairs signature inputs))
        reminder (output-requirements signature opts)]
    {:role    "user"
     :content (str (str/join "\n" input-lines) "\n\n" reminder)}))

;; ============================================================================
;; Demonstrations
;; ============================================================================

(defn- demo-output-json
  "The JSON object a demo's model turn would have emitted: `reasoning` first
   when chain-of-thought (the same position the live schema forces), then the
   signature's output fields in declared order. Keys the signature does not
   declare are dropped — a demo must never show the model a field its schema
   will reject."
  [signature {:keys [outputs reasoning]} chain-of-thought?]
  (let [declared (keys (:outputs signature))
        pairs    (cond-> []
                   (and chain-of-thought? reasoning) (conj ["reasoning" reasoning])
                   true (into (for [k declared :when (contains? outputs k)]
                                [(name k) (get outputs k)])))]
    ;; array-map from the pair list keeps render order at any size; a hash-map
    ;; past 8 keys would reorder fields and put reasoning after the answer.
    (json/write-str (apply array-map (mapcat identity pairs)))))

(defn render-demos
  "Render demonstrations as one system-message part, or nil when there are
   none. Each demo is `{:inputs {…} :outputs {…} :reasoning \"…\"?}`.

   Inputs render with the SAME `name: value` lines, in the SAME order, as the
   live user message — the example must look like the call it teaches.

   Demos sit in the system message, which is the stable cache prefix: they
   change only when a predictor's params change, never per call. They are also
   untrusted text elevated to system position (a bootstrapped demo contains
   whatever the program read), which is why they arrive only through reviewed
   params, never from the call site's own inputs."
  [signature demos {:keys [chain-of-thought?]}]
  (when (seq demos)
    (str "Here are examples of inputs and the JSON object to respond with:\n\n"
         (str/join "\n\n"
                   (map-indexed
                    (fn [i {:keys [inputs] :as demo}]
                      (str "Example " (inc i) "\n"
                           "Input:\n"
                           (str/join "\n" (map input-line (ordered-input-pairs signature inputs)))
                           "\nOutput:\n"
                           (demo-output-json signature demo chain-of-thought?)))
                    demos)))))

;; ============================================================================
;; Parts Collection (for token breakdown)
;; ============================================================================

(defn- collect-system-parts
  "Collect system message parts as ordered [[category-kw text] ...] pairs.
   Order follows DSPy convention: field descriptions → JSON schema → format →
   demonstrations → instructions (last).

   Chain-of-thought prepends reasoning as the first output field — structural
   position alone forces reasoning-first, no 'think step by step' instruction
   needed — with a length budget, which position alone does not impose (see
   reasoning-field-desc).

   This is the ONLY place the system message is assembled: `build-messages`
   and `build-messages-with-breakdown` both join these parts, so the
   attributed breakdown can never describe a different prompt than was sent."
  [signature json-schema chain-of-thought? demos]
  (let [{:keys [instructions inputs outputs]} signature
        demo-text (render-demos signature demos {:chain-of-thought? chain-of-thought?})]
    (if chain-of-thought?
      (let [cot-outputs (into [reasoning-field] outputs)]
        (cond-> []
          (seq inputs)   (conj [:input-fields (str "Your input fields are:\n" (indexed-fields inputs))])
          true           (conj [:output-fields (str "Your output fields are:\n" (indexed-fields-raw cot-outputs))])
          json-schema    (conj [:json-schema (json-schema-instruction json-schema)])
          true           (conj [:format "Respond with a JSON object containing all output fields."])
          demo-text      (conj [:demos demo-text])
          instructions   (conj [:instructions (str "In adhering to this structure, your objective is:\n" instructions)])))
      (cond-> []
        (seq inputs)    (conj [:input-fields (str "Your input fields are:\n" (indexed-fields inputs))])
        (seq outputs)   (conj [:output-fields (str "Your output fields are:\n" (indexed-fields outputs))])
        json-schema     (conj [:json-schema (json-schema-instruction json-schema)])
        true            (conj [:format "Respond with a JSON object containing the output fields."])
        demo-text       (conj [:demos demo-text])
        instructions    (conj [:instructions (str "In adhering to this structure, your objective is:\n" instructions)])))))

(defn- collect-user-parts
  "Collect user-message parts for token attribution: one entry PER INPUT
   FIELD (its rendered `name: value` line, in signature-declared order)
   plus the output reminder — so /usage --breakdown shows :question /
   :recalled-memory / :iterations etc. instead of one :input-values blob.

   Attribution only — the actual user-message content is built by
   build-user-message (fields join with \"\\n\", not the \"\\n\\n\" part
   separator), so these parts must never be recomposed into content."
  [signature inputs opts]
  (let [pairs (ordered-input-pairs signature inputs)
        reminder (output-requirements signature opts)]
    (conj (mapv (fn [[k _ :as pair]] [k (input-line pair)]) pairs)
          [:output-reminder reminder])))

(defn- parts->content
  "Join collected parts into a single content string."
  [parts]
  (str/join "\n\n" (map second parts)))

(defn- parts->breakdown
  "Build token breakdown from collected parts."
  [parts]
  (usage/build-token-breakdown (into {} parts)))

;; ============================================================================
;; Public API
;; ============================================================================

(defn build-messages
  "Build the full message list for an LLM call.
   opts:
     :chain-of-thought? - Use CoT prompting (prepend reasoning field)
     :json-schema       - JSON Schema to include in system prompt
     :demos             - Demonstrations (see render-demos); absent/empty
                          leaves the prompt byte-identical to no demos"
  [signature inputs {:keys [chain-of-thought? json-schema demos] :as opts}]
  [{:role    "system"
    :content (parts->content (collect-system-parts signature json-schema chain-of-thought? demos))}
   (build-user-message signature inputs opts)])

(defn build-messages-with-breakdown
  "Like build-messages, but also returns hierarchical token breakdown.
   Returns {:messages [...] :token-breakdown {:dspy-signature {...} :user-message {...}}}.
   Each group has :text-length, :estimated-tokens, and :parts with sub-categories.

   opts additionally accepts :user-cache-boundary — a field keyword marking
   the first per-iteration-volatile input. When set (and the preceding
   turn-stable prefix is large enough to cache), the result carries
   :user-cache-prefix — the exact leading substring of the user message a
   provider adapter can mark as a cache breakpoint."
  [signature inputs {:keys [chain-of-thought? json-schema user-cache-boundary demos] :as opts}]
  (let [sys-parts  (collect-system-parts signature json-schema chain-of-thought? demos)
        usr-parts  (collect-user-parts signature inputs opts)
        sys-msg    {:role "system" :content (parts->content sys-parts)}
        ;; Content comes from build-user-message, NOT parts->content:
        ;; usr-parts are per-field attribution entries whose "\n\n" part
        ;; separator would not match the real "\n" field separator.
        usr-msg    (build-user-message signature inputs opts)
        breakdown  {:dspy-signature (usage/build-token-group (parts->breakdown sys-parts))
                    :user-message   (usage/build-token-group (parts->breakdown usr-parts))}
        prefix     (user-cache-prefix (ordered-input-pairs signature inputs)
                                      user-cache-boundary)]
    (cond-> {:messages [sys-msg usr-msg]
             :token-breakdown breakdown}
      prefix (assoc :user-cache-prefix prefix))))
