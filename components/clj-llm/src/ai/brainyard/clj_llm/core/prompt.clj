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
;; JSON Schema Instruction
;; ============================================================================

(defn- json-schema-instruction
  "Build the JSON schema instruction for the system message.
   Ensures the LLM sees the exact schema in its prompt context."
  [json-schema]
  (when json-schema
    (str "IMPORTANT: You MUST respond with ONLY a valid JSON object matching this schema:\n"
         (json/write-str json-schema)
         "\nDo not include any text before or after the JSON."
         "\nUse EXACTLY the field names specified in the schema.")))

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
;; System Messages
;; ============================================================================

(defn- build-system-message
  "Build the system message from a signature.
   Order follows DSPy convention: field descriptions → JSON schema → format → instructions (last)."
  [signature json-schema]
  (let [{:keys [instructions inputs outputs]} signature
        parts (cond-> []
                (seq inputs)    (conj (str "Your input fields are:\n" (indexed-fields inputs)))
                (seq outputs)   (conj (str "Your output fields are:\n" (indexed-fields outputs)))
                json-schema     (conj (json-schema-instruction json-schema))
                true            (conj "Respond with a JSON object containing the output fields.")
                instructions    (conj (str "In adhering to this structure, your objective is:\n" instructions)))]
    {:role    "system"
     :content (str/join "\n\n" parts)}))

(defn- build-cot-system-message
  "Build the system message for chain-of-thought.
   Prepends reasoning as the first output field (DSPy convention:
   structural position alone forces reasoning-first, no explicit
   'think step by step' instruction needed).
   Includes augmented JSON schema with reasoning field."
  [signature json-schema]
  (let [{:keys [instructions inputs outputs]} signature
        cot-outputs (into [[:reasoning [:string {:desc "Step-by-step reasoning before producing the answer"}]]]
                          outputs)
        parts (cond-> []
                (seq inputs)   (conj (str "Your input fields are:\n" (indexed-fields inputs)))
                true           (conj (str "Your output fields are:\n" (indexed-fields-raw cot-outputs)))
                json-schema    (conj (json-schema-instruction json-schema))
                true           (conj "Respond with a JSON object containing all output fields.")
                instructions   (conj (str "In adhering to this structure, your objective is:\n" instructions)))]
    {:role    "system"
     :content (str/join "\n\n" parts)}))

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
;; Parts Collection (for token breakdown)
;; ============================================================================

(defn- collect-system-parts
  "Collect system message parts as a named map before joining.
   Returns ordered pairs [[category-kw text] ...]."
  [signature json-schema chain-of-thought?]
  (let [{:keys [instructions inputs outputs]} signature]
    (if chain-of-thought?
      (let [cot-outputs (into [[:reasoning [:string {:desc "Step-by-step reasoning before producing the answer"}]]]
                              outputs)]
        (cond-> []
          (seq inputs)   (conj [:input-fields (str "Your input fields are:\n" (indexed-fields inputs))])
          true           (conj [:output-fields (str "Your output fields are:\n" (indexed-fields-raw cot-outputs))])
          json-schema    (conj [:json-schema (json-schema-instruction json-schema)])
          true           (conj [:format "Respond with a JSON object containing all output fields."])
          instructions   (conj [:instructions (str "In adhering to this structure, your objective is:\n" instructions)])))
      (cond-> []
        (seq inputs)    (conj [:input-fields (str "Your input fields are:\n" (indexed-fields inputs))])
        (seq outputs)   (conj [:output-fields (str "Your output fields are:\n" (indexed-fields outputs))])
        json-schema     (conj [:json-schema (json-schema-instruction json-schema)])
        true            (conj [:format "Respond with a JSON object containing the output fields."])
        instructions    (conj [:instructions (str "In adhering to this structure, your objective is:\n" instructions)])))))

(defn- collect-user-parts
  "Collect user message parts as a named map (inputs rendered in
   signature-declared order — see ordered-input-pairs)."
  [signature inputs opts]
  (let [input-text (str/join "\n" (map input-line (ordered-input-pairs signature inputs)))
        reminder (output-requirements signature opts)]
    [[:input-values input-text]
     [:output-reminder reminder]]))

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
     :json-schema       - JSON Schema to include in system prompt"
  [signature inputs {:keys [chain-of-thought? json-schema] :as opts}]
  (let [sys-msg (if chain-of-thought?
                  (build-cot-system-message signature json-schema)
                  (build-system-message signature json-schema))
        usr-msg (build-user-message signature inputs opts)]
    [sys-msg usr-msg]))

(defn build-messages-with-breakdown
  "Like build-messages, but also returns hierarchical token breakdown.
   Returns {:messages [...] :token-breakdown {:dspy-signature {...} :user-message {...}}}.
   Each group has :text-length, :estimated-tokens, and :parts with sub-categories.

   opts additionally accepts :user-cache-boundary — a field keyword marking
   the first per-iteration-volatile input. When set (and the preceding
   turn-stable prefix is large enough to cache), the result carries
   :user-cache-prefix — the exact leading substring of the user message a
   provider adapter can mark as a cache breakpoint."
  [signature inputs {:keys [chain-of-thought? json-schema user-cache-boundary] :as opts}]
  (let [sys-parts  (collect-system-parts signature json-schema chain-of-thought?)
        usr-parts  (collect-user-parts signature inputs opts)
        sys-msg    {:role "system" :content (parts->content sys-parts)}
        usr-msg    {:role "user"   :content (parts->content usr-parts)}
        breakdown  {:dspy-signature (usage/build-token-group (parts->breakdown sys-parts))
                    :user-message   (usage/build-token-group (parts->breakdown usr-parts))}
        prefix     (user-cache-prefix (ordered-input-pairs signature inputs)
                                      user-cache-boundary)]
    (cond-> {:messages [sys-msg usr-msg]
             :token-breakdown breakdown}
      prefix (assoc :user-cache-prefix prefix))))
