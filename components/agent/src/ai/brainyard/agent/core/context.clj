;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.core.context
  "Context building for agent execution.

   Builds comprehensive context from:
   - System instructions
   - Parent agent conversation
   - Agent-specific context

   Also constructs enriched recall queries from multi-turn conversation
   context and pending TODOs to improve memory recall relevance.

   System context (instruction / agent-context / tool-context fragments)
   lives at L1 with `:kind :system-context`. There is no agent-side
   wrapper for writing it — operators set entries directly via
   `mem/write-entry`. `assemble-field` is the only consumer here: it
   reads L1 entries for the current session and assembles a system-
   prompt fragment per field."
  (:require [clojure.string :as str]
            [ai.brainyard.agent.core.protocol :as proto]
            [ai.brainyard.memory.interface :as mem]
            [ai.brainyard.memory.interface.protocol :as mem-proto]
            [ai.brainyard.mulog.interface :as mulog]))

;; ============================================================================
;; Context Building
;; ============================================================================

(defn build-comprehensive-context
  "Build a comprehensive context string for agent execution.

   Sources:
   - system-prompt: Base system instructions
   - conversation: Recent conversation messages
   - agent-context: Additional context from parent agent

   Returns formatted context string."
  [& {:keys [system-prompt conversation agent-context]}]
  (let [parts (cond-> []
                system-prompt
                (conj (str "## System Instructions\n" system-prompt))

                (seq agent-context)
                (conj (str "## Agent Context\n" agent-context))

                (seq conversation)
                (conj (str "## Conversation History\n"
                           (str/join "\n" (map (fn [msg]
                                                 (str (:role msg) ": " (:content msg)))
                                               conversation)))))]
    (str/join "\n\n" parts)))

;; ============================================================================
;; System Commands
;; ============================================================================

(defn format-system-commands
  "Format available system commands for agent context.
   commands: seq of {:name :description} maps."
  [commands]
  (when (seq commands)
    (str "Available commands:\n"
         (str/join "\n" (map (fn [{:keys [name description]}]
                               (str "- /" name ": " description))
                             commands)))))

(defn process-system-command
  "Process a system command (messages starting with /).
   Returns {:command command-name :args args} or nil if not a command."
  [input]
  (when (and (string? input) (str/starts-with? input "/"))
    (let [parts (str/split (str/trim input) #"\s+" 2)
          command (subs (first parts) 1)
          args (second parts)]
      {:command command :args args})))

;; ============================================================================
;; L1 Context Assembly
;;
;; Both `:system-context` (operator-managed configuration) and
;; `:user-context` (model/user-supplied) live at L1. Each entry carries
;; `:data {:field <field> :section <name>}` and a `:content` string.
;;
;; `assemble-field` reads all entries for a (kind, field) pair within
;; the agent's session, sorts by section, formats as `### <name>\\n<content>`
;; blocks, and prepends a BASE value drawn from BT short-term memory.
;; ============================================================================

(def ^:private default-assemble-field-max-chars
  "Default soft cap on assembled field size, in characters. Equates to
   roughly 16K tokens at chars/4 — large enough that BRAINYARD.md +
   per-agent instruction + overlays fit comfortably; small enough
   that a runaway L1 overlay or an outsized :agent-context surfaces
   as a mulog warning instead of silently bloating the system prompt."
  65000)

(defn report-overflow!
  "Emit an `::assemble-field-overflow` mulog warn. Factored out as a
   plain fn (rather than expanded inline via the warn macro) so tests
   can intercept it via `with-redefs`."
  [event-map]
  (mulog/warn ::assemble-field-overflow
              :kind          (:kind event-map)
              :field         (:field event-map)
              :size-chars    (:size-chars event-map)
              :max-chars     (:max-chars event-map)
              :overlay-count (:overlay-count event-map)
              :base-chars    (:base-chars event-map)))

(defn assemble-field
  "Assemble a kind-qualified prompt fragment for `field`.

  Required:
    agent — the running agent
    kind  — `:system-context` or `:user-context`
    field — keyword field (e.g. `:instruction`, `:tool-context`,
            `:agent-context` for system context; arbitrary for user
            context)

  Options:
    :title     — string to use as the default base when BT st-memory has
                 no value at `field`. Falls back to `(name kind)` when
                 :title is also absent.
    :max-chars — soft cap on the assembled string length. When the
                 result exceeds it, a `mulog/warn ::assemble-field-overflow`
                 fires (with field / size / cap / overlay-count) so
                 operators can spot a bloated overlay without computing
                 token totals manually. Defaults to
                 `default-assemble-field-max-chars` (65000 chars ≈ 16K
                 tokens). Pass `:max-chars 0` to disable the check.

  Reads the BASE from the agent's BT short-term memory
  (`proto/get-bt-st-memory`) at `field`. Reads L1 entries with
    :kind <kind>, :session-id <agent's session>, :data.field <field>
  Sorts by `:data.section`, formats each as `### <section>\\n<content>`,
  joins with blank lines, and prepends the base (or default).

  Returns the assembled string. When no entries exist, returns just
  the base/default. The size check is a warning only — the string is
  always returned in full."
  [agent kind field & {:keys [title max-chars]}]
  (let [st-mem   (when agent (proto/get-bt-st-memory agent))
        base     (when st-mem (get @st-mem field))
        mm       (when agent (:memory-manager @(:!state agent)))
        store    (when mm (mem/store mm))
        sid      (when agent (proto/session-id agent))
        entries  (when (and store sid)
                   (mem-proto/read-entries
                    store :l1
                    {:kind kind :session-id sid :field field}
                    {:limit 200 :consistent true}))
        sections (->> entries
                      (map (fn [{:keys [data content]}]
                             [(:section data) content]))
                      (sort-by first))
        effective-base (cond
                         (and (string? base) (not (str/blank? base))) base
                         (and (string? title) (not (str/blank? title))) title
                         :else (name kind))
        assembled (if (empty? sections)
                    effective-base
                    (let [section-strs (for [[nm content] sections]
                                         (str "### " nm "\n" content))]
                      (str effective-base "\n\n" (str/join "\n\n" section-strs))))
        cap (long (or max-chars default-assemble-field-max-chars))]
    (when (and (pos? cap) (> (count assembled) cap))
      (report-overflow!
       {:kind kind
        :field field
        :size-chars (count assembled)
        :max-chars cap
        :overlay-count (count sections)
        :base-chars (count (or effective-base ""))}))
    assembled))

;; ============================================================================
;; Recall Query Construction
;; ============================================================================

(defn build-recall-query
  "Build an enriched recall query from multi-turn context.

   Combines:
   1. Current question (primary, highest weight)
   2. Key terms from recent 2-3 conversation turns
   3. Pending TODO task descriptions (if available)

   Parameters:
     question      - Current user question
     conversation  - Recent conversation messages [{:role :content}]
     todo-list     - Current TODO items [{:description :done}] or nil

   Returns: Enhanced query string for memory recall (max 500 chars)."
  [question conversation todo-list]
  (let [recent-msgs (->> (or conversation [])
                         (take-last 4)
                         (map :content)
                         (remove nil?)
                         (str/join " "))
        pending-todos (->> (or todo-list [])
                           (remove :done)
                           (map :description)
                           (remove nil?)
                           (str/join " "))
        query (str question
                   (when (seq recent-msgs)
                     (str " " (subs recent-msgs 0 (min 200 (count recent-msgs)))))
                   (when (seq pending-todos)
                     (str " " (subs pending-todos 0 (min 100 (count pending-todos))))))]
    (subs query 0 (min 500 (count query)))))

;; ============================================================================
;; Parent Context Extraction
;; ============================================================================

(defn extract-parent-context
  "Extract relevant context from a parent agent for a sub-agent.
   Returns a context string summarizing parent's state."
  [parent-agent]
  (when parent-agent
    (let [session-data @(:!session parent-agent)
          recent-msgs (when session-data
                        (take-last 5 (:messages session-data)))]
      (when (seq recent-msgs)
        (str "Parent agent conversation (last "
             (count recent-msgs) " messages):\n"
             (str/join "\n" (map (fn [msg]
                                   (let [content (str (:content msg))]
                                     (str (:role msg) ": "
                                          (subs content 0 (min 200 (count content))))))
                                 recent-msgs)))))))
