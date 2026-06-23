;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.core.usage
  "Open registry for on-demand **usage guides** — just-in-time topic context for
   the LLM.

   A usage guide is a short markdown explainer for one capability/topic (e.g.
   `:memory`, `:nrepl`, `:agents`). Guides are delivered three ways, all built on
   this registry:

   - **Pull** — the `(usage$guide :topic <name>)` sandbox binding and the `:usage$guide` tool.
   - **JIT push** — `agent.common.usage-nudge` auto-inlines the relevant guide on
     first use (or error) of a guide-backed tool family.
   - **Permanent inline** — config `:inline-usage-guides` renders chosen guides
     into an agent's tool-context every turn.

   This namespace is the single source of truth. It mirrors the tool-registry
   pattern (a process-wide atom populated at namespace load): any brick can
   register its own topic via `register-usage!` / `defusage`, so a guide can live
   next to the feature it documents. Registration is idempotent (re-registering a
   topic overwrites it), GraalVM-safe (a plain atom, populated at load), and
   order is carried as data (`:order`) so listings are deterministic regardless
   of load order.

   Guide CONTENT for the built-in topics lives in `agent.common.usage-guides`."
  (:require [clojure.string :as str]))

(defonce ^{:doc "Registry of usage guides: {topic-keyword {:topic :title :guide
  :category :relevance :order}}. Populated at namespace load via register-usage!."}
  !usage-defs
  (atom {}))

(defn- ->topic-kw
  "Coerce a topic (keyword/string/symbol) to a keyword, stripping a leading `:`
   on strings. Returns nil for anything else or blank."
  [topic]
  (cond
    (keyword? topic) topic
    (symbol? topic)  (keyword (name topic))
    (and (string? topic) (not (str/blank? topic)))
    (keyword (str/replace topic #"^:" ""))
    :else nil))

(defn- humanize
  "Default title from a topic keyword: :llm-query → \"Llm Query\"."
  [topic-kw]
  (->> (str/split (name topic-kw) #"-")
       (map str/capitalize)
       (str/join " ")))

(defn register-usage!
  "Register (or overwrite) the usage guide for `topic`.

   opts:
     :guide     (required) non-blank markdown string — the guide text.
     :title     (optional) human label; defaults to a humanized topic.
     :category  (optional) topical grouping keyword for catalog listings (e.g.
                :memory, :sandbox); default :general.
     :scope     (optional) promotion tier — :system (always-on: listed in the
                system-prompt consult-table) or :user (default: on-demand only,
                reachable via `(usage$guide)` + the JIT nudge). Built-in foundational
                guides are :system; runtime-created and specialized guides are
                :user. Distinct from :category (which is topical grouping).
     :consult   (optional) one-line \"when to consult this\" hint, shown in the
                consult-table. Only :system guides with a hint appear there.
     :order     (optional) int for deterministic listing order; default 100.

   Returns the registered topic keyword. Throws on a missing topic or blank guide
   so authoring mistakes fail loudly at load."
  [topic {:keys [guide title category scope consult order]}]
  (let [k (->topic-kw topic)]
    (when (nil? k)
      (throw (ex-info (str "register-usage!: invalid topic " (pr-str topic))
                      {:topic topic})))
    (when (or (not (string? guide)) (str/blank? guide))
      (throw (ex-info (str "register-usage!: guide for " k " must be a non-blank string")
                      {:topic k})))
    (swap! !usage-defs assoc k
           {:topic    k
            :title    (or title (humanize k))
            :guide    guide
            :category (or category :general)
            :scope    (or scope :user)
            :consult  consult
            :order    (or order 100)})
    k))

(defmacro defusage
  "Convenience macro for load-time registration, for colocating a guide with the
   feature it documents:

     (defusage :nrepl {:title \"Live Runtime (clj-nrepl)\" :category :debug
                       :guide \"...\"})

   Expands to `register-usage!`."
  [topic opts]
  `(register-usage! ~topic ~opts))

(defn get-usage-guide
  "Return the guide markdown for `topic` (keyword/string/symbol), or nil if the
   topic is unknown."
  [topic]
  (some-> (->topic-kw topic) (@!usage-defs) :guide))

(defn usage-def
  "Return the full registry entry for `topic`, or nil."
  [topic]
  (some-> (->topic-kw topic) (@!usage-defs)))

(defn- by-order
  "Sort comparator key: [order name] for deterministic listings."
  [{:keys [order topic]}]
  [(or order 100) (name topic)])

(defn list-usage-topics
  "All registered topic keywords, in deterministic [:order :topic] order."
  []
  (->> (vals @!usage-defs) (sort-by by-order) (mapv :topic)))

(defn usage-catalog
  "Vector of {:topic :title :category} for every registered guide, in listing
   order. Used for the `(usage$guide)` no-arg listing and system-prompt pointers."
  []
  (->> (vals @!usage-defs)
       (sort-by by-order)
       (mapv #(select-keys % [:topic :title :category]))))

(defn consult-table
  "Render a markdown 'when to consult' table for the system prompt — one row per
   `:scope :system` guide that declares a `:consult` hint, in listing order.
   `:user`-scoped guides (runtime-created + specialized) are deliberately
   omitted to keep the always-on prompt lean; they stay reachable via `(usage$guide)`
   and the JIT nudge. Registry-driven, so it never goes stale. Returns nil when
   no system guide declares a hint."
  []
  (let [rows (->> (vals @!usage-defs)
                  (sort-by by-order)
                  (filter #(and (= :system (:scope %)) (:consult %))))]
    (when (seq rows)
      (str "| Topic | When to consult |\n"
           "|-------|-----------------|\n"
           (str/join "\n"
                     (map (fn [{:keys [topic consult]}]
                            (str "| `" topic "` | " consult " |"))
                          rows))))))
