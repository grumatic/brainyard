;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.common.reactor
  "Event reactor — persisted `event → action` rules (Phase 2 of
   docs/design/event-bus-and-reactor.md §3.3). Generalizes
   `ai.brainyard.agent.common.auto-notify` (whose one hard-wired rule is
   `:task/completed → inject a resume turn`) into a user-configurable table.

   A rule (persisted under `<project>/.brainyard/reactions/<id>.edn`):

     {:id \"on-ship-refresh\"
      :on :order/shipped            ; any bus event, built-in or user-defined
      :match {:region \"us\"}         ; optional payload subset filter
      :do {:as :turn                ; :turn | :run | :artifact | :emit
           :text \"Order {{order-id}} shipped — refresh the dashboard.\"}
      :enabled true :max-fires nil :created <ms>}

   `ensure-reactions!` (runtime-only, gated by `:enable-reactions`, called per
   turn from coact_agent) installs one bus handler per (session, referenced
   event) that — when a matching event fires — runs each rule's `:do` through the
   same component-level sinks the ask.sock `:op :inject` uses:

     :turn / :run  → agent.core.agent/submit-turn   (serializes on the owner queue)
     :artifact     → common.artifacts/add-artifact!
     :emit         → common.events/emit-event!       (cascade-budgeted)

   `:memory` and `:context` (the passive context-injection sinks) land with the
   Phase-3 event inbox. String fields in `:do` are `{{key}}`-interpolated from
   the triggering payload.

   Safety: turn-producing actions are gated to an interactive host + root agent;
   a per-session fire budget (`:max-reaction-fires-per-session`) plus a hook
   re-entrancy-depth guard bound a runaway event→reaction→event cascade."
  (:require [ai.brainyard.agent.common.artifacts :as artifacts]
            [ai.brainyard.agent.common.events :as events]
            [ai.brainyard.agent.common.project-memory :as project-memory]
            [ai.brainyard.agent.core.config :as config]
            [ai.brainyard.agent.core.hooks :as hooks]
            [ai.brainyard.agent.core.protocol :as proto]
            [ai.brainyard.agent.core.tool :refer [defcommand]]
            [ai.brainyard.mulog.interface :as mulog]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.io File]))

;; ============================================================================
;; Lazy cross-namespace handles (core.agent sits above the common layer —
;; mirror auto_notify's requiring-resolve to avoid a load cycle).
;; ============================================================================

(def ^:private !submit-turn
  (delay (requiring-resolve 'ai.brainyard.agent.core.agent/submit-turn)))
(def ^:private !turn-submitter
  (delay (requiring-resolve 'ai.brainyard.agent.core.agent/!turn-submitter)))
(def ^:private !get-parent-agent
  (delay (requiring-resolve 'ai.brainyard.agent.core.runtime/get-parent-agent)))

(defn- interactive-host?
  "True when a host turn-submitter is registered (TUI / web); headless `by ask`
   leaves it nil. Gates turn-producing reactions off a one-shot host."
  []
  (some? (deref (deref @!turn-submitter))))

(defn- root-agent? [agent]
  (and agent (:!state agent) (nil? (@!get-parent-agent (:!state agent)))))

(def ^:private max-depth
  "Hard cap on synchronous hook re-entrancy (an :emit reaction firing another
   event that re-enters the reactor). Above this the reactor no-ops."
  8)

;; ============================================================================
;; Store — .brainyard/reactions/<id>.edn (atomic, mirrors schedule/events)
;; ============================================================================

(defn ^File reactions-root [project-dir]
  (io/file (str project-dir) ".brainyard" "reactions"))

(defn- ^File spec-file [project-dir id]
  (io/file (reactions-root project-dir) (str id ".edn")))

(def ^:private id-re #"^[a-z0-9][a-z0-9-]*$")
(defn valid-id? [id] (boolean (and (string? id) (re-matches id-re id))))

(defn write-spec!
  "Atomically write a rule spec (must carry a valid `:id`). Returns the spec."
  [project-dir {:keys [id] :as spec}]
  (when-not (valid-id? id)
    (throw (ex-info (str "invalid reaction id: " (pr-str id)) {:id id})))
  (let [^File dir (reactions-root project-dir)]
    (.mkdirs dir)
    (let [tmp (io/file dir (str id ".edn.tmp"))
          dst (spec-file project-dir id)]
      (spit tmp (pr-str spec))
      (.renameTo tmp dst))
    spec))

(defn read-spec [project-dir id]
  (let [^File f (spec-file project-dir id)]
    (when (.exists f)
      (try (edn/read-string (slurp f))
           (catch Exception e (mulog/warn ::read-spec-failed :id id :exception e) nil)))))

(defn list-specs [project-dir]
  (let [^File root (reactions-root project-dir)]
    (if-not (.isDirectory root)
      []
      (->> (.listFiles root)
           (filter (fn [^File f] (and (.isFile f) (str/ends-with? (.getName f) ".edn"))))
           (keep (fn [^File f] (try (edn/read-string (slurp f)) (catch Exception _ nil))))
           (sort-by :created)
           vec))))

(defn delete-spec! [project-dir id]
  (when (valid-id? id)
    (let [^File f (spec-file project-dir id)]
      (when (.exists f) (.delete f) true))))

(defn- gen-id [title now-ms]
  (let [slug (-> (str (or (not-empty (str title)) "rule"))
                 str/lower-case
                 (str/replace #"[^a-z0-9]+" "-")
                 (str/replace #"(^-+|-+$)" ""))
        slug (if (str/blank? slug) "rule" slug)]
    (str (subs slug 0 (min 24 (count slug))) "-" now-ms)))

;; ============================================================================
;; Matching + template interpolation
;; ============================================================================

(defn- payload-match?
  "True when every key in `match` equals the same key in `payload`. An empty/nil
   match accepts everything."
  [match payload]
  (or (nil? match) (empty? match)
      (and (map? payload)
           (every? (fn [[k v]] (= v (get payload k))) match))))

(defn- interpolate
  "Replace `{{key}}` tokens in a string with `(get payload (keyword key))`."
  [s payload]
  (if (string? s)
    (str/replace s #"\{\{([^}]+)\}\}"
                 (fn [[_ k]] (str (get payload (keyword (str/trim k)) ""))))
    s))

(defn- interp-do
  "Interpolate every string value of the `:do` action map from `payload`."
  [do-map payload]
  (reduce-kv (fn [m k v] (assoc m k (interpolate v payload))) {} do-map))

;; ============================================================================
;; Per-session budgets (in-memory; reset on session close)
;; ============================================================================

(defonce ^:private !fire-counts (atom {}))   ; session-id -> total actions fired
(defonce ^:private !rule-fires  (atom {}))    ; rule-id    -> lifetime actions fired

(defn- max-fires-per-session [agent]
  (or (config/get-config agent :max-reaction-fires-per-session) 50))

(defn- session-over-budget? [agent sid]
  (>= (get @!fire-counts sid 0) (max-fires-per-session agent)))

(defn- rule-budget-ok? [rule]
  (let [cap (:max-fires rule)]
    (or (nil? cap) (< (get @!rule-fires (:id rule) 0) cap))))

(defn- bump-budgets! [sid rule]
  (swap! !fire-counts update sid (fnil inc 0))
  (swap! !rule-fires update (:id rule) (fnil inc 0)))

;; ============================================================================
;; Action execution
;; ============================================================================

(defn- action-text [d] (or (:text d) (:content d) ""))

(def ^:private max-inbox
  "Cap on retained passive event-inbox entries per session (renders as
   `## Events`; rolls off oldest-first, like the sibling `## Live Artifacts`)."
  20)

(defn- record-context-event!
  "Append a passive event into the agent's cross-turn store so the next turn
   surfaces it in the `## Events` context section (no forced turn)."
  [agent event-key text]
  (when-let [st (some-> agent proto/get-st-memory-init)]
    (swap! st update :events-inbox
           (fn [q] (->> (conj (vec q) {:event event-key :text (str text)})
                        (take-last max-inbox)
                        vec)))))

(defn- execute-action!
  "Run one rule's `:do` action against `agent`, interpolating strings from the
   triggering `payload`. Returns a short keyword describing what ran."
  [agent rule payload]
  (let [d  (interp-do (:do rule) payload)
        as (keyword (:as d))]
    (case as
      (:turn :run)
      (if (interactive-host?)
        (do (@!submit-turn agent (action-text d)
                           {:source :reaction :reaction-id (:id rule)})
            :turn)
        :skipped-headless)

      :artifact
      (do (artifacts/add-artifact! agent {:name    (:name d)
                                          :content (:content d)
                                          :path    (:path d)
                                          :pinned  (:pin? d)})
          :artifact)

      :emit
      (do (events/emit-event! agent (events/->event-key (:event d))
                              (or (:payload d) payload))
          :emit)

      :memory
      (let [pcd (config/project-config-dir (config/get-config agent :dirs))
            r   (project-memory/write-memory! pcd (:slug d) (action-text d))]
        (when (:error r)
          (mulog/warn ::memory-action-failed :rule (:id rule) :error (:error r)))
        :memory)

      :context
      (do (record-context-event! agent (:on rule) (action-text d))
          :context)

      (do (mulog/warn ::unknown-reaction-action :as as :rule (:id rule))
          :unknown))))

;; ============================================================================
;; Bus handler
;; ============================================================================

(defn- event-session-id
  "Resolve the session-id an event belongs to: an explicit `:session-id`
   (external emit), else the firing agent's session (internal events)."
  [payload]
  (or (:session-id payload)
      (try (some-> (:agent payload) proto/session-id) (catch Throwable _ nil))))

(defn- session-match? [sid ev-sid]
  (or (nil? ev-sid) (nil? sid) (= sid ev-sid)))

(defn- rules-for
  "Enabled rules whose `:on` equals `event-key`, freshly read from disk so rule
   edits take effect without re-registering handlers."
  [project-dir event-key]
  (->> (list-specs project-dir)
       (filter #(and (:enabled %) (= event-key (some-> (:on %) events/->event-key))))))

(defn- make-handler
  "The bus handler for (session, event-key): on a matching event fire, run every
   matching rule's action against `agent`, within budget + re-entrancy caps."
  [agent sid project-dir event-key]
  (fn [payload]
    (let [ev-sid (event-session-id payload)]
      (when (and (session-match? sid ev-sid)
                 (< (hooks/current-depth) max-depth)
                 (not (session-over-budget? agent sid)))
        (doseq [rule (rules-for project-dir event-key)
                :when (and (payload-match? (:match rule) payload)
                           (rule-budget-ok? rule))]
          (bump-budgets! sid rule)
          (try
            (let [outcome (execute-action! agent rule payload)]
              (mulog/info ::reacted :rule (:id rule) :on event-key :outcome outcome))
            (catch Throwable e
              (mulog/warn ::reaction-failed :rule (:id rule) :exception e))))))))

;; ============================================================================
;; Per-session install / sync (runtime-only)
;; ============================================================================

(defonce ^:private !installed (atom {}))   ; session-id -> {:agent a :events #{event-keys}}
(defonce ^:private !cleanup-installed (atom false))

(defn- reactor-source [sid] (keyword "reactor-src" (str/replace (str sid) #"[^a-zA-Z0-9]+" "_")))
(defn- reactor-hid [sid event-key]
  (keyword "reactor" (str (str/replace (str sid) #"[^a-zA-Z0-9]+" "_") "__" (name event-key))))

(defn- desired-events
  "Distinct event-keys referenced by the project's enabled rules."
  [project-dir]
  (->> (list-specs project-dir)
       (filter :enabled)
       (keep #(some-> (:on %) events/->event-key))
       (into #{})))

(defn- teardown-session! [sid]
  (hooks/unregister-source! (reactor-source sid))
  (swap! !installed dissoc sid)
  (swap! !fire-counts dissoc sid))

(defn- ensure-cleanup-hook! []
  (when (compare-and-set! !cleanup-installed false true)
    (hooks/register-hook!
     :agent.session/closed ::reactor-cleanup
     (fn [{:keys [session-id]}] (when session-id (teardown-session! session-id)))
     :source :reactor-cleanup)))

(defn ensure-reactions!
  "Install / re-sync this session's reaction handlers. Runtime-only, safe to call
   every turn: no-op unless `:enable-reactions` and a root agent. Registers one
   bus handler per referenced event, refreshing when the rule set or the live
   agent instance (resume) changes. When the feature is toggled off, tears the
   session's handlers down."
  [agent]
  (let [sid (try (proto/session-id agent) (catch Throwable _ nil))]
    (when (and agent sid (root-agent? agent))
      (if-not (config/get-config agent :enable-reactions)
        (when (contains? @!installed sid) (teardown-session! sid))
        (let [pdir    (str (config/project-dir agent))
              want    (desired-events pdir)
              current (get @!installed sid)]
          (ensure-cleanup-hook!)
          (when (or (not= (:agent current) agent) (not= (:events current) want))
            ;; agent changed (resume) or rule set changed → rebuild cleanly.
            (hooks/unregister-source! (reactor-source sid))
            (doseq [ek want]
              (hooks/register-hook!
               ek (reactor-hid sid ek)
               (make-handler agent sid pdir ek)
               :source (reactor-source sid)))
            (swap! !installed assoc sid {:agent agent :events want})
            (mulog/info ::reactions-synced :session sid :events (vec want)))))))
  nil)

(defn reset-state!
  "Clear reactor state + handlers. For tests."
  []
  (doseq [sid (keys @!installed)] (hooks/unregister-source! (reactor-source sid)))
  (reset! !installed {})
  (reset! !fire-counts {})
  (reset! !rule-fires {})
  (reset! !cleanup-installed false)
  (hooks/unregister-source! :reactor-cleanup))

;; ============================================================================
;; Commands
;; ============================================================================

(def ^:private action-targets #{:turn :run :artifact :emit :memory :context})

(defcommand reaction$add
  "Add a rule that runs an action when an event fires: :on <event> :do {:as :turn|:run|:artifact|:emit …}."
  (fn [& {:as opts}]
    (let [on     (:on opts)
          action (:do opts)
          match  (:match opts)
          ek     (events/->event-key on)]
      (cond
        (nil? ek)               {:error ":on must name an event (e.g. 'order/shipped')"}
        (not (map? action))     {:error ":do must be an action map, e.g. {:as :turn :text \"…\"}"}
        (nil? (:as action))     {:error ":do must include :as (:turn|:run|:artifact|:emit)"}
        (not (contains? action-targets (keyword (:as action))))
        {:error (str "unknown :as " (pr-str (:as action)) " — want :turn|:run|:artifact|:emit")}
        :else
        (let [pdir (config/project-dir)
              now  (System/currentTimeMillis)
              rid  (let [given (not-empty (str (:id opts)))]
                     (if (and given (valid-id? given)) given (gen-id (or (:title opts) (name ek)) now)))
              spec (cond-> {:id rid :on ek :do action
                            :enabled (if (some? (:enabled opts)) (boolean (:enabled opts)) true)
                            :created now}
                     (map? match)                    (assoc :match match)
                     (some? (:max-fires opts))       (assoc :max-fires (:max-fires opts))
                     (not-empty (str (:title opts)))  (assoc :title (str (:title opts))))]
          (write-spec! pdir spec)
          (cond-> {:id rid :on ek :enabled (:enabled spec)}
            (not (config/get-config :enable-reactions))
            (assoc :note "Reactions are OFF — set :enable-reactions true (or BY_ENABLE_REACTIONS) to install them; rules are stored regardless."))))))
  :input-schema  [:map
                  [:on        [:string {:desc "Event to react to (namespaced keyword, e.g. 'order/shipped')"}]]
                  [:do        [:any {:desc "Action map: {:as :turn|:run|:artifact|:emit …}; string fields interpolate {{payload-key}}"}]]
                  [:match     {:optional true} [:any {:desc "Payload subset filter, e.g. {:region \"us\"}"}]]
                  [:id        {:optional true} [:string {:desc "Explicit rule id (lowercase-kebab)"}]]
                  [:title     {:optional true} [:string {:desc "Human label (seeds the id)"}]]
                  [:max-fires {:optional true} [:int {:desc "Lifetime cap on how many times this rule may fire"}]]
                  [:enabled   {:optional true} [:boolean {:desc "Start enabled (default true)"}]]]
  :output-schema [:map
                  [:id      {:optional true} [:string]]
                  [:on      {:optional true} [:any {:desc "Event key"}]]
                  [:enabled {:optional true} [:boolean]]
                  [:note    {:optional true} [:string]]
                  [:error   {:optional true} [:string]]])

(defcommand reaction$list
  "List event→action reaction rules and their status."
  (fn [& _]
    {:reactions (mapv #(select-keys % [:id :title :on :match :do :enabled :max-fires])
                      (list-specs (config/project-dir)))})
  :input-schema  [:map]
  :output-schema [:map [:reactions [:vector {:desc "Reaction rules"} :any]]])

(defcommand reaction$remove
  "Remove a reaction rule."
  (fn [& {:keys [id]}]
    (if (delete-spec! (config/project-dir) id)
      {:removed id}
      {:error (str "no reaction '" id "'")}))
  :input-schema  [:map [:id [:string {:desc "Rule id"}]]]
  :output-schema [:map [:removed {:optional true} [:string]] [:error {:optional true} [:string]]])

(defn- set-enabled! [id enabled?]
  (let [pdir (config/project-dir)]
    (if-let [spec (read-spec pdir id)]
      (do (write-spec! pdir (assoc spec :enabled enabled?))
          {:id id :enabled enabled?})
      {:error (str "no reaction '" id "'")})))

(defcommand reaction$enable
  "Enable a reaction rule (installs on the next turn when :enable-reactions is on)."
  (fn [& {:keys [id]}] (set-enabled! id true))
  :input-schema  [:map [:id [:string {:desc "Rule id"}]]]
  :output-schema [:map [:id {:optional true} [:string]] [:enabled {:optional true} [:boolean]]
                  [:error {:optional true} [:string]]])

(defcommand reaction$disable
  "Disable a reaction rule (keeps it; stops it firing)."
  (fn [& {:keys [id]}] (set-enabled! id false))
  :input-schema  [:map [:id [:string {:desc "Rule id"}]]]
  :output-schema [:map [:id {:optional true} [:string]] [:enabled {:optional true} [:boolean]]
                  [:error {:optional true} [:string]]])

(def reaction-commands
  "Event-reactor command family, bound into the common roster."
  [#'reaction$add #'reaction$list #'reaction$remove #'reaction$enable #'reaction$disable])
