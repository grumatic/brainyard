;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.core.context.formatters
  "Shared prompt-section formatters used by multiple agents (CoAct, ReAct).

   Each formatter takes a piece of agent state and returns a rendered
   string suitable for embedding inside a section of `:system-context`
   or `:user-context`. Formatters return nil when there's nothing to
   render so callers can suppress the surrounding section heading."
  (:require [ai.brainyard.agent.core.tool :as tool]
            [ai.brainyard.clj-llm.interface :as clj-llm]
            [clojure.string :as str]))

;; ============================================================================
;; Conversation history
;; ============================================================================

(defn format-conversation
  "One-line-per-message conversation formatter:
       - **role**: snippet
   `content` is truncated at 500 chars with an ellipsis. Returns nil for
   an empty conversation.

   `turn-ref` entries ({:role \"turn-ref\" :turn N}, ranged with :to —
   produced by the timeline conversation style) render as pointers into
   the Previous Turns section, which carries the full Q/A + iterations
   for those turns."
  [conversation]
  (when (seq conversation)
    (->> conversation
         (map (fn [{:keys [role content turn to]}]
                (if (= "turn-ref" role)
                  (if to
                    (str "- **[Turns " turn "–" to "]** → see Previous Turns")
                    (str "- **[Turn " turn "]** → see Previous Turns"))
                  (let [snippet (let [s (str content)]
                                  (if (> (count s) 500)
                                    (str (subs s 0 500) "…")
                                    s))]
                    (str "- **" (name (or role "unknown")) "**: " snippet)))))
         (str/join "\n"))))

;; ============================================================================
;; Agent tools detail (full specs, used inside `## Tools` / `### Agent Tools`)
;; ============================================================================

(defn format-agent-tools
  "Detailed listing of the tools bound to an agent for the current turn.

   Each entry renders:
       - `<id>` — <description>
         Inputs:
           - `<arg>` : <type>[ (optional)] — <desc>
         Outputs:                              ;; only when registry meta declares them
           - `<key>` : <type> — <desc>

   `tools` is the vector stored in BT st-memory under :tools (output of
   `tool/bind-tools`); each entry has :name :description :tool-fn-type
   :parameters. Outputs are resolved from `tool/get-tool-defs` (registry
   meta) by direct keyword lookup of :name."
  [tools]
  (when (seq tools)
    (let [registry (tool/get-tool-defs)
          render-arg (fn [arg-name spec required?]
                       (let [t (or (:type spec) "any")
                             d (str (or (:description spec) (:desc spec) ""))
                             opt (when-not required? " (optional)")]
                         (str "    - `" (clojure.core/name arg-name) "` : " t opt
                              (when-not (str/blank? d) (str " — " d)))))
          render-output (fn [entry]
                          (let [k      (tool/malli-map-entry-key entry)
                                spec   (tool/malli-map-entry-schema entry)
                                parsed (try (clj-llm/parse-malli-field spec)
                                            (catch Exception _ {}))
                                schema (:schema parsed)
                                t (or (:type parsed)
                                      (when (and (vector? schema) (keyword? (first schema)))
                                        (name (first schema)))
                                      "any")
                                d (str (or (:desc parsed) ""))]
                            (str "    - `" (clojure.core/name k) "` : " t
                                 (when-not (str/blank? d) (str " — " d)))))
          render-tool
          (fn [{:keys [name description parameters]}]
            (let [desc (str description)
                  props (:properties parameters)
                  required (set (:required parameters))
                  input-lines (when (seq props)
                                (->> props
                                     (map (fn [[arg spec]]
                                            (render-arg arg spec
                                                        (contains? required
                                                                   (clojure.core/name arg)))))
                                     (str/join "\n")))
                  registry-id (keyword name)
                  output-schema (some-> registry (get registry-id) :meta :output-schema)
                  output-entries (tool/malli-map-entries output-schema)
                  output-lines
                  (cond
                    (seq output-entries)
                    (->> output-entries (map render-output) (str/join "\n"))

                    output-schema
                    (let [parsed (try (clj-llm/parse-malli-field output-schema)
                                      (catch Exception _ {}))
                          schema (:schema parsed)
                          t (or (:type parsed)
                                (when (and (vector? schema) (keyword? (first schema)))
                                  (clojure.core/name (first schema)))
                                (when (keyword? schema) (clojure.core/name schema))
                                "any")
                          d (str (or (:desc parsed) ""))]
                      (str "    " t (when-not (str/blank? d) (str " — " d)))))]
              (cond-> (str "- `" name "`"
                           (when-not (str/blank? desc) (str " — " desc)))
                input-lines  (str "\n  Inputs:\n"  input-lines)
                output-lines (str "\n  Outputs:\n" output-lines))))]
      (->> tools (map render-tool) (str/join "\n\n")))))

(defn format-agent-tools-compact
  "One-line listing of bound tools: `- id`. Used as the budget-tier
   fallback when the verbose `format-agent-tools` block is too big."
  [tools]
  (when (seq tools)
    (->> tools
         (map (fn [{:keys [name]}]
                (str "- `" name "`")))
         (str/join "\n"))))
