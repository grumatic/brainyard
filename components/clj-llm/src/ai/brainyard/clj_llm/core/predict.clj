;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.clj-llm.core.predict
  "Predict operation - basic LLM call with structured output."
  (:require [ai.brainyard.clj-llm.core.prompt :as prompt]
            [ai.brainyard.clj-llm.core.llm :as llm]
            [ai.brainyard.clj-llm.core.schema :as schema]
            [ai.brainyard.clj-llm.core.usage :as usage]
            [ai.brainyard.clj-llm.core.providers :as providers]
            [ai.brainyard.mulog.interface :as mulog]
            [malli.core :as m]))

(defn- schema-default
  "Return a type-appropriate default value for a Malli schema.
   Resolves schema references via the global registry before inspecting the type."
  [malli-schema]
  (let [resolved (try (m/schema malli-schema) (catch Exception _ nil))
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

(defn predict
  "Execute a predict operation on a signature with given inputs.

   signature  - Compiled signature map (from defsignature)
   inputs     - Map of input values matching signature's :input-keys
   opts       - Optional map:
                :lm-config     - LM configuration (falls back to default)
                :usage-tracker - Atom from create-usage-tracker (optional)

   Returns {:outputs {<output-field> <value>} :usage {...}}
   Throws on validation failure or LLM error."
  [signature inputs & {:keys [lm-config usage-tracker system-context
                              stream? on-chunk
                              input-token-breakdown cache-zones
                              user-cache-boundary]
                       :as opts}]
  (let [lm (or lm-config (providers/get-default-lm))
        _  (when-not lm
             (throw (ex-info "No LM configured. Call configure-default-lm! or pass :lm-config."
                             {:signature (:name signature)})))
        ;; Build messages with JSON schema in system prompt + token breakdown
        json-schema (:output-json-schema signature)
        {:keys [messages token-breakdown user-cache-prefix]}
        (prompt/build-messages-with-breakdown
         signature inputs {:json-schema json-schema
                           :user-cache-boundary user-cache-boundary})
        ;; Merge caller-provided breakdown (hierarchical — :system-prompt group from BT)
        breakdown (merge token-breakdown input-token-breakdown)
        ;; Append system-context to the signature system message
        [breakdown messages]
        (if system-context
          [(if input-token-breakdown
             breakdown  ;; caller already provided hierarchical groups — don't add blob
             (assoc breakdown :system-context
                    (usage/build-token-group
                     (usage/build-token-breakdown {:system-context system-context}))))
           (let [[sys-msg & rest-msgs] messages]
             (into [(update sys-msg :content str "\n\n" system-context)] rest-msgs))]
          [breakdown messages])
        response (apply llm/chat-completion lm messages
                        (mapcat identity
                                (cond-> {:json-schema json-schema
                                         ;; prompt.clj already put the schema in the
                                         ;; system prompt — don't let chat-completion
                                         ;; inject it again for non-native providers.
                                         :schema-in-prompt? true
                                         :usage-tracker usage-tracker
                                         :on-chunk on-chunk
                                         :input-token-breakdown breakdown}
                                  (contains? opts :stream?) (assoc :stream? stream?)
                                  (seq cache-zones) (assoc :cache-zones cache-zones)
                                  user-cache-prefix (assoc :user-cache-prefix user-cache-prefix))))
        ;; Parse response, fill defaults for missing keys
        raw-text (llm/extract-content response lm)
        parsed   (-> (llm/parse-json-response raw-text)
                     (fill-output-defaults signature))
        ;; Validate against output schema
        malli-schema (schema/fields->malli-schema (:outputs signature))
        validation   (schema/validate-output malli-schema parsed)
        ;; Extract usage
        usage-map (::llm/usage response)]
    (when-not (:valid? validation)
      (mulog/warn ::output-validation-failed :signature-name (:name signature) :errors (:errors validation)))
    (cond-> {:outputs parsed}
      usage-map (assoc :usage usage-map))))
