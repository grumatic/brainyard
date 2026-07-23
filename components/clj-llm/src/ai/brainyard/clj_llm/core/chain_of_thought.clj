;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.clj-llm.core.chain-of-thought
  "Chain-of-thought operation - LLM call with reasoning step."
  (:require [ai.brainyard.clj-llm.core.prompt :as prompt]
            [ai.brainyard.clj-llm.core.llm :as llm]
            [ai.brainyard.clj-llm.core.schema :as schema]
            [ai.brainyard.clj-llm.core.usage :as usage]
            [ai.brainyard.clj-llm.core.providers :as providers]
            [ai.brainyard.mulog.interface :as mulog]
            [clojure.string :as str]
            [malli.core :as m]))

(def ^:private placeholder-stall-note
  "Synthetic reasoning stamped when a model returns a bare placeholder emission
   (e.g. `{\"tool-name\":\"noop\"}`) with no real reasoning. Making reasoning
   non-blank lets the caller classify the turn as a deliberate no-action (nudge)
   instead of an empty/truncated CLI response (expensive retry loop)."
  "(No action: the model emitted a placeholder/no-op instead of a real channel.)")

(defn augment-schema-with-reasoning
  "Add a 'reasoning' property as the first field in a JSON Schema object."
  [json-schema]
  (let [reasoning-prop {"reasoning" {:type "string"
                                     :description "Step-by-step reasoning before producing the answer"}}]
    (-> json-schema
        (update :properties #(merge reasoning-prop %))
        (update :required #(into ["reasoning"] %)))))

(defn- schema-default
  "Return a type-appropriate default value for a Malli schema.
   Resolves schema references via the global registry before inspecting the type.
   `deref-all` unwraps registry refs (e.g. ::tool-calls) — without it `m/type`
   returns :malli.core/schema and every ref field falls to the \"\" fallback."
  [malli-schema]
  (let [resolved (try (m/deref-all (m/schema malli-schema)) (catch Exception _ nil))
        schema-type (when resolved (m/type resolved))]
    (case schema-type
      :string  ""
      :boolean false
      :int     0
      :double  0.0
      :vector  []
      :sequential []
      :set     #{}
      :map     {}
      "")))

(defn- fill-output-defaults
  "Fill in schema-derived defaults for any output keys missing from parsed LLM response.
   Uses the signature's output schemas to determine type-appropriate defaults
   (e.g., \"\" for strings, false for booleans, [] for vectors).
   Prevents nil values from cascading through downstream actions when the LLM
   fails to produce all required fields (common with smaller models like Haiku)."
  [outputs signature]
  (let [output-fields (:outputs signature)]
    (reduce-kv (fn [acc k raw-schema]
                 (if (contains? acc k)
                   acc
                   (let [{:keys [schema]} (schema/parse-malli-field raw-schema)]
                     (assoc acc k (schema-default schema)))))
               outputs
               output-fields)))

(defn chain-of-thought
  "Execute a chain-of-thought operation on a signature with given inputs.

   Like predict, but instructs the LLM to reason step by step.
   The reasoning is captured in the :reasoning key of the result.

   signature  - Compiled signature map (from defsignature)
   inputs     - Map of input values matching signature's :input-keys
   opts       - Optional map:
                :lm-config     - LM configuration (falls back to default)
                :usage-tracker - Atom from create-usage-tracker (optional)

   Returns {:outputs {<output-field> <value>} :reasoning \"...\" :usage {...}}
   Throws on LLM error."
  [signature inputs & {:keys [lm-config usage-tracker system-context
                              stream? on-chunk
                              input-token-breakdown cache-zones
                              user-cache-boundary]
                       :as opts}]
  (let [lm (or lm-config (providers/get-default-lm))
        _  (when-not lm
             (throw (ex-info "No LM configured. Call configure-default-lm! or pass :lm-config."
                             {:signature (:name signature)})))
        ;; Augment JSON schema with reasoning field
        json-schema (augment-schema-with-reasoning (:output-json-schema signature))
        ;; Build CoT messages with augmented schema in system prompt + token breakdown
        {:keys [messages token-breakdown user-cache-prefix]}
        (prompt/build-messages-with-breakdown
         signature inputs {:chain-of-thought? true :json-schema json-schema
                           :user-cache-boundary user-cache-boundary})
        ;; Merge caller-provided breakdown (hierarchical — :system-prompt group from BT)
        breakdown (merge token-breakdown input-token-breakdown)
        ;; Append system-context to the signature system message
        [breakdown messages]
        (if system-context
          [(if input-token-breakdown
             breakdown
             (assoc breakdown :system-context
                    (usage/build-token-group
                     (usage/build-token-breakdown {:system-context system-context}))))
           (let [[sys-msg & rest-msgs] messages]
             (into [(update sys-msg :content str "\n\n" system-context)] rest-msgs))]
          [breakdown messages])
        ;; Call LLM
        response (apply llm/chat-completion lm messages
                        (mapcat identity
                                (cond-> {:json-schema json-schema
                                         ;; schema already in the system prompt via
                                         ;; prompt.clj — skip chat-completion's
                                         ;; non-native-provider injection.
                                         :schema-in-prompt? true
                                         :usage-tracker usage-tracker
                                         :on-chunk on-chunk
                                         :input-token-breakdown breakdown}
                                  (contains? opts :stream?) (assoc :stream? stream?)
                                  (seq cache-zones) (assoc :cache-zones cache-zones)
                                  user-cache-prefix (assoc :user-cache-prefix user-cache-prefix))))
        ;; Parse response
        raw-text  (llm/extract-content response lm)
        parsed    (llm/parse-json-response raw-text)
        ;; Separate reasoning from outputs, lift a flattened single element back
        ;; into its vector field (e.g. top-level tool-name/tool-args → tool-calls),
        ;; fill defaults for missing keys, then coerce present-but-wrong-typed
        ;; values toward their schema so a skeleton-echo (e.g. a scalar for a
        ;; :vector field, "false"/null for a :boolean) is repaired instead of
        ;; rejected. Coercion only keeps a value that then validates, so it never
        ;; masks genuinely unrecoverable output.
        ;; A bare placeholder emission ({"tool-name":"noop"} with no reasoning)
        ;; would read as an empty response and trigger the caller's costly
        ;; empty-result retry loop. Stamp a synthetic reasoning so it is
        ;; classified as a deliberate no-action (nudge) instead.
        reasoning (let [r (:reasoning parsed)]
                    (if (and (str/blank? (str r))
                             (schema/placeholder-emission? parsed))
                      placeholder-stall-note
                      r))
        outputs   (-> (dissoc parsed :reasoning)
                      (schema/lift-flattened-collection signature)
                      (fill-output-defaults signature)
                      (schema/coerce-output-types signature))
        ;; Validate outputs against original schema
        malli-schema (schema/fields->malli-schema (:outputs signature))
        validation   (schema/validate-output malli-schema outputs)
        ;; Extract usage
        usage-map (::llm/usage response)]
    (when-not (:valid? validation)
      (mulog/warn ::output-validation-failed :signature-name (:name signature) :errors (:errors validation)))
    ;; Surface the validation outcome so callers (e.g. the agent repair path)
    ;; can distinguish a schema-validation failure from an empty/blank response.
    (cond-> {:outputs outputs :valid? (:valid? validation)}
      reasoning (assoc :reasoning reasoning)
      usage-map (assoc :usage usage-map)
      (not (:valid? validation)) (assoc :validation-errors (:errors validation)))))
