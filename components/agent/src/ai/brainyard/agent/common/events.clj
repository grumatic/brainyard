;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.common.events
  "User-defined event registry + emit path (Phase 1 of
   docs/design/event-bus-and-reactor.md).

   The hooks bus (ai.brainyard.agent.core.hooks) already fires and streams any
   keyword, so custom events work at the plumbing level. This namespace adds the
   missing halves:

   - a persisted **vocabulary** of user event definitions under
     `<project>/.brainyard/events/<slug>.edn` (atomic writes, mirroring the
     `schedule` subsystem), folded into the hooks dynamic registry so
     `known-event?` recognizes them and discovery can advertise them;
   - an **emit path** (`emit-event!`) that validates a payload against the
     event's declared `:payload-schema` (when present) and fires it on the bus.

   Surfaces:
   - `event$define/list/remove/emit` commands (bound into the common roster).
   - `emit-event!` — exported on the agent interface; backs both the LLM
     `event$emit` tool and the `ask.sock` `:op :emit` verb (external → agent).
   - `ensure-events-loaded!` — runtime-only, called per-turn to load persisted
     defs into the dynamic registry once per (process, project).

   Reactions (event → action) and the watch loop are Phases 2 & 4 — this file is
   deliberately just the registry + emit."
  (:require [ai.brainyard.agent.core.config :as config]
            [ai.brainyard.agent.core.hooks :as hooks]
            [ai.brainyard.agent.core.protocol :as proto]
            [ai.brainyard.agent.core.tool :refer [defcommand]]
            [ai.brainyard.mulog.interface :as mulog]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [malli.core :as m]
            [malli.transform :as mt])
  (:import [java.io File]))

;; ============================================================================
;; Event-name coercion
;; ============================================================================

(defn ->event-key
  "Coerce a user event name to a keyword, or nil when invalid. Accepts a keyword
   or a string like \"order/shipped\", \":order/shipped\", or \"deploy-done\".
   A namespaced form (\"order/shipped\") is preferred but not required."
  [x]
  (cond
    (keyword? x) x
    (string? x)
    (let [s (str/replace (str/trim x) #"^:" "")]
      (when-not (str/blank? s)
        (let [[a b] (str/split s #"/" 2)]
          (cond
            (str/blank? a)       nil
            (not (str/blank? b)) (keyword a b)
            :else                (keyword a)))))
    :else nil))

(defn- event-slug
  "Filesystem-safe slug for an event key. Unique enough for a filename; the def
   map carries the authoritative `:name`, so the slug need not be reversible."
  [event-key]
  (-> (subs (str event-key) 1)                 ; drop the leading ':'
      (str/replace #"[^a-zA-Z0-9]+" "_")))

;; ============================================================================
;; Store — .brainyard/events/<slug>.edn (atomic)
;; ============================================================================

(defn ^File events-root [project-dir]
  (io/file (str project-dir) ".brainyard" "events"))

(defn- ^File def-file [project-dir event-key]
  (io/file (events-root project-dir) (str (event-slug event-key) ".edn")))

(defn write-def!
  "Atomically write an event def map (must carry a keyword `:name`). Returns it."
  [project-dir {:keys [name] :as def'}]
  (let [^File dir (events-root project-dir)]
    (.mkdirs dir)
    (let [tmp (io/file dir (str (event-slug name) ".edn.tmp"))
          dst (def-file project-dir name)]
      (spit tmp (pr-str def'))
      (.renameTo tmp dst))
    def'))

(defn read-def
  "Read the persisted def for `event-key`, or nil when absent/unreadable."
  [project-dir event-key]
  (let [^File f (def-file project-dir event-key)]
    (when (.exists f)
      (try (edn/read-string (slurp f))
           (catch Exception e
             (mulog/warn ::read-def-failed :event event-key :exception e) nil)))))

(defn list-defs
  "All persisted event defs under `project-dir`, oldest first."
  [project-dir]
  (let [^File root (events-root project-dir)]
    (if-not (.isDirectory root)
      []
      (->> (.listFiles root)
           (filter (fn [^File f] (and (.isFile f) (str/ends-with? (.getName f) ".edn"))))
           (keep (fn [^File f] (try (edn/read-string (slurp f)) (catch Exception _ nil))))
           (sort-by :created)
           vec))))

(defn delete-def! [project-dir event-key]
  (let [^File f (def-file project-dir event-key)]
    (when (.exists f) (.delete f) true)))

;; ============================================================================
;; Dynamic-registry loading (runtime-only)
;; ============================================================================

(defonce ^:private !loaded
  ;; Set of project-dirs whose defs have been folded into the hooks dynamic
  ;; registry this process. defonce ⇒ native-image bakes it empty.
  (atom #{}))

(defn load-project-events!
  "Register every persisted event def under `project-dir` into the hooks dynamic
   registry (so `known-event?` recognizes them + discovery advertises them).
   Idempotent — re-registering replaces. Returns the count loaded."
  [project-dir]
  (reduce (fn [n d]
            (if-let [ek (->event-key (:name d))]
              (do (hooks/register-event! ek (dissoc d :name :created)) (inc n))
              n))
          0 (list-defs project-dir)))

(defn ensure-events-loaded!
  "Load persisted event defs once per (process, project-dir). Runtime-only and
   safe to call every turn — the first call for a project folds its defs into the
   dynamic registry; later calls are a cheap set lookup."
  [agent]
  (when agent
    (let [pdir (str (config/project-dir agent))]
      (when (and (not (str/blank? pdir)) (not (contains? @!loaded pdir)))
        (swap! !loaded conj pdir)
        (try (load-project-events! pdir)
             (catch Throwable e (mulog/warn ::load-events-failed :project-dir pdir :exception e))))))
  nil)

;; ============================================================================
;; Emit path
;; ============================================================================

(def ^:private payload-transformer
  "Keywordize the payload's string map keys on the way in. The `event$emit` tool
   crosses a JSON/marshalling boundary that stringifies top-level payload keys
   (`{\"order-id\" …}`), so a naturally keyword-keyed schema like
   `[:map [:order-id :string]]` would never validate and the reactor's
   keyword-based `{{key}}` interpolation (`(get payload :order-id)`) would render
   blank. Coercing keys once here lets authors write idiomatic keyword schemas
   and keeps validation + interpolation aligned. Value types are left untouched
   (no string-transformer) to avoid surprising coercions."
  (mt/key-transformer {:decode keyword}))

(defn- coerce-payload
  "Coerce `payload` against the event's malli `schema` before validation and
   fire, keywordizing its string map keys. A malformed schema or a non-map
   payload passes through untouched — coercion is a convenience, not a gate
   (mirrors `valid-payload?`)."
  [schema payload]
  (if (and (some? schema) (map? payload))
    (try (m/decode schema payload payload-transformer)
         (catch Throwable _ payload))
    payload))

(defn- valid-payload?
  "Validate `payload` against a malli data `schema`. A malformed schema does NOT
   block the emit (returns true) — validation is a guard, not a gate."
  [schema payload]
  (try (m/validate schema payload) (catch Throwable _ true)))

(defn- explain-payload [schema payload]
  (try (pr-str (m/explain schema payload)) (catch Throwable _ "invalid")))

(defn emit-event!
  "Validate `payload` against the event's declared `:payload-schema` (when the
   event is registered and carries one) and fire it on the hooks bus. `payload`
   is delivered to subscribers as the event map, augmented with `:event` and a
   `:source` (default `:emit`). Returns `{:fired event-key :subscribers n}` or
   `{:error …}`. Firing an unregistered event is allowed (the bus is permissive)
   but flagged with a `:note`.

   Arity-2 resolves the project from the bound current-agent (LLM-tool path);
   arity-3 takes an explicit `agent` (the ask-listener thread has none bound)."
  ([event-key payload] (emit-event! nil event-key payload))
  ([agent event-key payload]
   (let [pdir (str (if agent (config/project-dir agent) (config/project-dir)))
         ek   (->event-key event-key)]
     (if (nil? ek)
       {:error (str "invalid event name: " (pr-str event-key))}
       (let [def'    (read-def pdir ek)
             schema  (:payload-schema def')
             payload (coerce-payload schema payload)]
         (if (and (some? schema) (not (valid-payload? schema payload)))
           {:error (str "payload does not match :payload-schema — " (explain-payload schema payload))}
           (do
             (hooks/fire! ek (-> (if (map? payload) payload {})
                                 (assoc :event ek)
                                 (update :source #(or % :emit))))
             (cond-> {:fired ek :subscribers (count (hooks/list-hooks ek))}
               (nil? def') (assoc :note "event not registered — fired anyway; declare it with event$define")))))))))

(defn- llm-injectable?
  "True when the LLM may emit `event-key`: no declaration, or one that does not
   set `:llm-injectable? false`."
  [project-dir event-key]
  (let [d (read-def (str project-dir) event-key)]
    (not (false? (:llm-injectable? d)))))

;; ============================================================================
;; Commands
;; ============================================================================

(defcommand event$define
  "Declare a user-defined event type (name + optional payload schema) so it can be emitted, subscribed to, and reacted to."
  (fn [& {:keys [name desc payload-schema llm-injectable]}]
    (let [ek (->event-key name)]
      (if (nil? ek)
        {:error (str "invalid :name " (pr-str name) " — use a namespaced keyword like 'order/shipped'")}
        (let [pdir   (config/project-dir)
              now    (System/currentTimeMillis)
              schema (if (string? payload-schema)
                       (try (edn/read-string payload-schema) (catch Exception _ nil))
                       payload-schema)
              def'   (cond-> {:name ek :created now}
                       (not (str/blank? (str desc))) (assoc :desc (str desc))
                       (some? schema)                (assoc :payload-schema schema)
                       (some? llm-injectable)        (assoc :llm-injectable? (boolean llm-injectable)))]
          (write-def! pdir def')
          (hooks/register-event! ek (dissoc def' :name :created))
          {:defined ek :path (.getAbsolutePath ^File (def-file pdir ek))}))))
  :input-schema  [:map
                  [:name           [:string {:desc "Event name, a namespaced keyword e.g. 'order/shipped'"}]]
                  [:desc           {:optional true} [:string {:desc "One-line description"}]]
                  [:payload-schema {:optional true} [:any {:desc "Optional malli schema the payload must match, e.g. [:map [:order-id :string]] — a schema is arbitrary EDN (usually a vector), so this stays :any, not a map type"}]]
                  [:llm-injectable {:optional true} [:boolean {:desc "May the agent emit this via event$emit? (default true)"}]]]
  :output-schema [:map
                  [:defined {:optional true} [:any {:desc "Registered event key"}]]
                  [:path    {:optional true} [:string {:desc "Def file path"}]]
                  [:error   {:optional true} [:string {:desc "Error if invalid"}]]])

(defcommand event$list
  "List the user-defined event types declared in this project."
  (fn [& _]
    {:events (mapv #(select-keys % [:name :desc :payload-schema :llm-injectable?])
                   (list-defs (config/project-dir)))})
  :input-schema  [:map]
  :output-schema [:map [:events [:vector {:desc "Event definitions"} :any]]])

(defcommand event$remove
  "Remove a user-defined event type declaration."
  (fn [& {:keys [name]}]
    (let [ek   (->event-key name)
          pdir (config/project-dir)]
      (if (and ek (delete-def! pdir ek))
        (do (hooks/unregister-event! ek) {:removed ek})
        {:error (str "no event '" (pr-str name) "'")})))
  :input-schema  [:map [:name [:string {:desc "Event name (namespaced keyword)"}]]]
  :output-schema [:map
                  [:removed {:optional true} [:any {:desc "Removed event key"}]]
                  [:error   {:optional true} [:string {:desc "Error if absent"}]]])

;; Lazy handle to the shared reaction+FSM installer (fsm requires events, so we
;; can't require it statically — resolve at first use to avoid the load cycle).
(def ^:private !ensure-handlers
  (delay (requiring-resolve 'ai.brainyard.agent.common.fsm/ensure-session-handlers!)))

(defcommand event$emit
  "Emit (fire) a user-defined event with an optional payload; subscribers and reactions receive it."
  (fn [& {:keys [event payload]}]
    (let [ek   (->event-key event)
          pdir (config/project-dir)]
      (cond
        (nil? ek)                        {:error (str "invalid :event " (pr-str event))}
        (not (llm-injectable? pdir ek))  {:error (str "event " ek " is declared :llm-injectable? false")}
        :else
        (do
          ;; Self-heal: install this session's reaction/FSM handlers so enabling
          ;; :enable-reactions / :enable-fsm and emitting in the same turn works
          ;; (they otherwise install only at turn setup).
          (when-let [ag proto/*current-agent*]
            (when-let [f @!ensure-handlers] (try (f ag) (catch Throwable _ nil))))
          (emit-event! ek payload)))))
  :input-schema  [:map
                  [:event   [:string {:desc "Event name to fire (namespaced keyword)"}]]
                  [:payload {:optional true} [:map-of {:desc "Event payload map delivered to subscribers"} :any :any]]]
  :output-schema [:map
                  [:fired       {:optional true} [:any {:desc "Fired event key"}]]
                  [:subscribers {:optional true} [:int {:desc "Registered handler count for this event"}]]
                  [:note        {:optional true} [:string {:desc "Advisory note"}]]
                  [:error       {:optional true} [:string {:desc "Error if invalid / not injectable"}]]])

(def events-commands
  "User-defined event command family, bound into the common roster."
  [#'event$define #'event$list #'event$remove #'event$emit])
