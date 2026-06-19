;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.common.user-hooks
  "Runtime-defined hooks — the LLM authors a handler from Clojure source and it
   becomes a first-class, persistent observer on a pre-defined Brainyard hook
   event (see `ai.brainyard.agent.core.hooks/event-catalog`).

   This is the hook counterpart of `ai.brainyard.agent.common.user-tools`. Same
   shape, different registry: where user-tools persists a `(fn [args] ...)` and
   registers it into `tool/!tool-defs`, user-hooks persists a `(fn [event] ...)`
   and registers it into the existing hook registry via `hooks/register-hook!`.

   Why source (not a closure): SCI closures are not EDN-serializable, so we
   persist the SOURCE form to `.brainyard/hooks/<id>.edn` and RE-EVAL it in a
   sandbox to rehydrate the handler on fire and on session start.

   The handler body runs in a dedicated, long-lived `!hooks-sandbox` (forked per
   fire for isolation). The body is a `(fn [event] ...)` of one map argument — a
   SANITIZED view of the event (the live Agent record is dropped; stable
   `:event-key` / `:agent-id` / `:defagent-type` are added) — and may compose the
   registered tool palette by DIRECT symbol, e.g. `(read-file {…})` /
   `(bash {…})`, exactly like a user tool body.

   v1 scope (observer-only, explicit scope):
   - Only NON-gated (`fire!`) events are allowed; gated events
     (`:agent.tool-use/pre`, `:agent.ask/finalize`) are rejected — a user hook
     cannot block/modify/replace. Return values are ignored on the `fire!` path.
   - `:match` is REQUIRED — a declarative scope map ({:tool-name …},
     {:defagent-type …}, {:agent-id …}) or {:global true} to fire everywhere.

   Safety: handlers register with `:on-error :log` (a throwing handler is
   swallowed = fail-open), a re-entrancy guard skips firing when invoked from
   inside another hook handler, and the `enable-user-hooks` config (default true)
   is a global kill-switch."
  (:require [ai.brainyard.agent.core.tool :as tool :refer [defcommand]]
            [ai.brainyard.agent.core.hooks :as hooks]
            [ai.brainyard.agent.core.config :as config]
            [ai.brainyard.agent.common.def-store :as def-store]
            [ai.brainyard.clj-sandbox.interface :as sb]
            [ai.brainyard.mulog.interface :as mulog]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]))

;; ============================================================================
;; Hooks sandbox (holds every handler body as a real SCI var)
;; ============================================================================

(defonce ^{:doc "Long-lived sandbox holding handler bodies as `__uh_<id>` vars.
  Forked per fire so concurrent invocations don't share mutable state."}
  !hooks-sandbox
  (atom nil))

(defn- hooks-sandbox
  "The live hooks sandbox, created on first use. `extra-bindings` (the agent's
   `auto-tool-bindings`) expose registered tools as DIRECT symbols a handler body
   may compose — `(bash {…})`, `(read-file {…})`, `(user$tool$peer {…})`."
  ([] (hooks-sandbox nil))
  ([extra-bindings]
   (let [sbx (or @!hooks-sandbox
                 (reset! !hooks-sandbox
                         (sb/create-sandbox :bindings (or extra-bindings {})
                                            :interop (config/resolve-sandbox-interop))))]
     (when (seq extra-bindings)
       (sb/update-bindings! sbx extra-bindings))
     sbx)))

(defonce ^{:doc "Set of hooks-dirs already loaded this process, so `ensure-loaded!`
  is a no-op after the first session-boot for a given project."}
  !loaded
  (atom #{}))

(defn reset-hooks-sandbox!
  "Drop the hooks sandbox and the loaded-dirs set (next call rebuilds / reloads).
   For tests / reload. Does NOT unregister already-installed hooks — call
   `(hooks/unregister-source! :user-hook)` for that."
  []
  (reset! !hooks-sandbox nil)
  (reset! !loaded #{}))

;; ============================================================================
;; Naming / paths
;; ============================================================================

(def ^:private hook-id-re
  "User hook ids: lowercase kebab, leading letter. Keeps the registry keyword
   (`:user-hook/<id>`) clean and a safe filename."
  #"^[a-z][a-z0-9-]*$")

(defn- hooks-dir
  "`.brainyard/hooks` under the project (fallback working dir, then cwd).
   Project-scoped, mirroring `.brainyard/tools`."
  [dirs]
  (str (or (:project-dir dirs) (:working-dir dirs) ".") "/.brainyard/hooks"))

(defn- handler-id
  "Registry handler-id for a user hook: `:user-hook/<id>`."
  [id]
  (keyword "user-hook" id))

;; ============================================================================
;; Coercion (LLM passes EDN as strings over JSON tool-calls)
;; ============================================================================

(defn- coerce-event
  "Normalize a caller-supplied :event into a keyword. The LLM passes it as a
   string (e.g. \"agent.tool-use/post\" or \":agent.iteration/post\")."
  [v]
  (cond
    (keyword? v) v
    (string? v)  (let [s (str/trim v)]
                   (when-not (str/blank? s)
                     (keyword (if (str/starts-with? s ":") (subs s 1) s))))
    :else        (throw (ex-info ":event must be a keyword or string" {:event v}))))

(defn- coerce-match
  "Normalize a caller-supplied :match into a map. Tool-call args arrive as JSON,
   which cannot express a map literal cleanly, so the LLM passes the scope as an
   EDN STRING (e.g. \"{:tool-name \\\"bash\\\"}\"). In-process callers pass the
   real map."
  [v]
  (cond
    (nil? v)    nil
    (map? v)    v
    (string? v) (let [s (str/trim v)]
                  (when-not (str/blank? s)
                    (try (edn/read-string s)
                         (catch Exception e
                           (throw (ex-info (str ":match is not readable EDN: " (ex-message e))
                                           {:match v}))))))
    :else       (throw (ex-info ":match must be an EDN map string like \"{:tool-name \\\"bash\\\"}\""
                                {:match v}))))

;; ============================================================================
;; Sandbox eval / event view / kill-switch
;; ============================================================================

(defn- install-body!
  "Eval `(def __uh_<id> <body>)` into the hooks sandbox so it is a callable SCI
   var. Throws if the body fails to parse/eval."
  [id body-str]
  (let [r (sb/eval-code (hooks-sandbox)
                        (str "(def __uh_" id " " body-str ")"))]
    (when-let [err (:error r)]
      (throw (ex-info (str "hook body failed to eval: " err)
                      {:id id :body body-str})))
    r))

(defn- sanitize-event
  "Build the EDN-friendly view of an event-map handed to a handler body as
   `event`. Drops the live Agent record(s) and derives stable scalar keys."
  [event-key event-map]
  (let [agent (or (:agent event-map) (:stage-agent event-map))
        aid   (:agent-id agent)]
    (cond-> (-> event-map
                (dissoc :agent :stage-agent)
                (assoc :event-key event-key))
      agent (assoc :agent-id      (some-> aid name)
                   :defagent-type (when (keyword? aid) (namespace aid))))))

(defn- user-hooks-enabled?
  "Global kill-switch: read `enable-user-hooks` (default true) from the event's
   agent config. Runtime-resolved to avoid a static require cycle."
  [event-map]
  (let [get-config (requiring-resolve 'ai.brainyard.agent.core.config/get-config)
        agent      (or (:agent event-map) (:stage-agent event-map))]
    (if get-config
      (boolean (get-config agent :enable-user-hooks))
      true)))

;; ============================================================================
;; Match / registration
;; ============================================================================

(defn- build-match
  "Translate a declarative :match map into a predicate over the raw event-map,
   composing the existing `hooks/match-*` helpers. Required: must scope by at
   least one of :defagent-type / :agent-id / :tool-name, or be {:global true}.
   Throws on a missing/empty/unrecognized map (the explicit-scope rule)."
  [m]
  (when-not (and (map? m) (seq m))
    (throw (ex-info ":match is required (declarative scope map); use {:global true} for an unscoped hook"
                    {:match m})))
  (if (:global m)
    (constantly true)
    (let [preds (cond-> []
                  (:defagent-type m)
                  (conj (hooks/match-defagent-type (keyword (:defagent-type m))))
                  (:agent-id m)
                  (conj (hooks/match-agent-id (keyword (:agent-id m))))
                  (:tool-name m)
                  (conj (let [tn (str (:tool-name m))]
                          (fn [ev] (= tn (str (:tool-name ev)))))))]
      (when (empty? preds)
        (throw (ex-info ":match must scope by :defagent-type, :agent-id, or :tool-name, or be {:global true}"
                        {:match m})))
      (apply hooks/match-all preds))))

(defn- make-handler-fn
  "The fn registered into the hook registry. Re-entrancy-guarded and
   kill-switch-gated; forks the hooks sandbox, binds the sanitized event as
   `event`, and evals `(__uh_<id> event)`. Observer-only: the return value is
   ignored by the `fire!` path."
  [id event-key]
  (fn [event-map]
    (when (and (<= (hooks/current-depth) 1)        ;; skip re-entrant firings
               (user-hooks-enabled? event-map))
      (let [fork (sb/fork-sandbox (hooks-sandbox))]
        (sb/set-var! fork 'event (sanitize-event event-key event-map))
        (sb/eval-code fork (str "(__uh_" id " event)"))
        nil))))

(defn- register!
  "Register (or replace) the hook in the shared hook registry. Tagged
   `:source :user-hook` for bulk teardown and `:on-error :log` so a throwing
   handler fails open."
  [{:keys [id event match priority]}]
  (hooks/register-hook!
   event
   (handler-id id)
   (make-handler-fn id event)
   :match    (build-match match)
   :priority (or priority 0)
   :source   :user-hook
   :on-error :log)
  (handler-id id))

(defn- find-registered-event
  "The event-key a user hook id is currently registered under, scanning the live
   registry. Lets `delete` work even when the .edn is gone."
  [id]
  (let [hid (handler-id id)]
    (some (fn [[ev entries]]
            (when (some #(= hid (:id %)) entries) ev))
          (hooks/list-hooks))))

;; ============================================================================
;; Definition / persistence
;; ============================================================================

(defn define-hook
  "Define a persistent observer hook from source.

   Kwargs:
     :id         - lowercase-kebab string (matches #\"^[a-z][a-z0-9-]*$\")
     :event      - a non-gated hook event key (keyword or string); see
                   `hooks/event-catalog`
     :match      - REQUIRED declarative scope map ({:tool-name …},
                   {:defagent-type …}, {:agent-id …}) or {:global true}
     :priority   - integer, higher fires first (default 0)
     :doc        - one-line description
     :body       - a string `(fn [event] ...)` of ONE map arg
     :dirs       - {:project-dir ...} resolving where to persist

   Effects: validates + eval-smoke-tests the body, persists the metadata to
   `.brainyard/hooks/<id>.edn` and the verbatim body source to the
   `.brainyard/hooks/<id>.clj` sidecar, and registers it via
   `hooks/register-hook!`. Returns {:id :event :persisted} (:persisted is the
   .edn path)."
  [& {:keys [id event match priority doc body dirs extra-bindings]}]
  (when-not (and (string? id) (re-matches hook-id-re id))
    (throw (ex-info "hook :id must match ^[a-z][a-z0-9-]*$" {:id id})))
  (let [event-kw (coerce-event event)]
    (when-not (hooks/known-event? event-kw)
      (throw (ex-info (str "unknown event " event-kw "; call hook-agent$events for the catalog")
                      {:event event-kw})))
    (when (hooks/gated-event? event-kw)
      (throw (ex-info (str event-kw " is a gated event; user-defined hooks are observer-only in v1")
                      {:event event-kw})))
    (when-not (string? body)
      (throw (ex-info "hook :body must be a string `(fn [event] ...)`" {:body body})))
    (let [match-map (coerce-match match)]
      (build-match match-map)                       ;; validate scope (throws if bad)
      (hooks-sandbox extra-bindings)                ;; ensure + refresh tool palette
      (install-body! id body)                       ;; compile-now smoke test
      (let [dir (hooks-dir dirs)
            rec {:id id :event event-kw :match match-map
                 :priority (or priority 0) :doc doc :body body}
            {:keys [edn]} (def-store/write-def! dir id (dissoc rec :body) body)]
        (register! rec)
        (mulog/info ::define-hook :id id :event event-kw :file edn)
        {:id id :event event-kw :persisted edn}))))

(defn load-user-hooks!
  "Startup loader: re-eval every persisted body into the hooks sandbox and
   re-register it. Call once when an agent session boots. Returns the ids
   loaded."
  [& {:keys [dirs extra-bindings]}]
  (hooks-sandbox extra-bindings)                    ;; ensure + refresh tool palette
  (let [dir-str (hooks-dir dirs)
        dir     (io/file dir-str)]
    (if (.isDirectory dir)
      (let [recs (->> (.listFiles dir)
                      (filter #(str/ends-with? (.getName ^java.io.File %) ".edn"))
                      (keep (fn [^java.io.File f]
                              (let [base (subs (.getName f) 0 (- (count (.getName f)) 4))]
                                (try (def-store/read-def dir-str base)
                                     (catch Exception e
                                       (mulog/warn ::load-user-hook-read-failed
                                                   :file (.getName f) :error (ex-message e))
                                       nil)))))
                      vec)]
        (->> recs
             (keep (fn [rec]
                     (try
                       (install-body! (:id rec) (:body rec))
                       (register! rec)
                       (:id rec)
                       (catch Exception e
                         (hooks/unregister-hook! (:event rec) (handler-id (:id rec)))
                         (mulog/warn ::load-user-hook-failed
                                     :id (:id rec) :error (ex-message e))
                         nil))))
             vec))
      [])))

(defn ensure-loaded!
  "Idempotent session-boot loader: load this project's persisted hooks the first
   time it is seen this process, and no-op thereafter. Safe to call on every
   turn. Returns the ids loaded (or nil when already loaded)."
  [& {:keys [dirs extra-bindings]}]
  (let [dir (hooks-dir dirs)]
    (when-not (contains? @!loaded dir)
      (swap! !loaded conj dir)
      (load-user-hooks! :dirs dirs :extra-bindings extra-bindings))))

;; ============================================================================
;; Management (list / read / delete)
;; ============================================================================

(defn registered-user-hooks
  "Active user hooks from the LIVE registry (dir-independent): {:id :event
   :priority}, sorted by id. The source of truth for what is currently firing."
  []
  (->> (hooks/list-hooks)
       (mapcat (fn [[ev entries]]
                 (->> entries
                      (filter #(= :user-hook (:source %)))
                      (map (fn [e] {:id (name (:id e)) :event ev :priority (:priority e)})))))
       (sort-by :id)
       vec))

(defn list-user-hooks
  "Summaries of every persisted user hook on disk, sorted by id."
  [dirs]
  (let [dir (io/file (hooks-dir dirs))]
    (if (.isDirectory dir)
      (->> (.listFiles dir)
           (filter #(str/ends-with? (.getName ^java.io.File %) ".edn"))
           (keep (fn [^java.io.File f]
                   (try (let [r (edn/read-string (slurp f))]
                          {:id       (:id r)
                           :event    (:event r)
                           :match    (:match r)
                           :priority (:priority r)
                           :doc      (:doc r)})
                        (catch Exception _ nil))))
           (sort-by :id)
           vec)
      [])))

(defn read-user-hook
  "Full record for one user hook — {:id :event :match :priority :doc :body} —
   read from the persisted metadata `.edn` + body `.clj` sidecar."
  [dirs id]
  (or (def-store/read-def (hooks-dir dirs) id)
      {:error (str "no user hook named " (pr-str id))}))

(defn delete-user-hook!
  "Unregister a user hook and delete its persisted source (both .edn and .clj)."
  [dirs id]
  (let [rec   (def-store/read-def (hooks-dir dirs) id)
        event (or (:event rec) (find-registered-event id))]
    (if (or rec event)
      (do
        (when event (hooks/unregister-hook! event (handler-id id)))
        (def-store/delete-def! (hooks-dir dirs) id)
        (mulog/info ::delete-user-hook :id id)
        {:deleted id})
      {:error (str "no user hook named " (pr-str id))})))

;; ============================================================================
;; Current-agent resolution (mirrors user-tools; runtime-resolved to dodge a
;; static require cycle — sandbox-bindings requires this ns for the palette)
;; ============================================================================

(defn- current-dirs
  []
  (or (when-let [a (some-> (requiring-resolve 'ai.brainyard.agent.core.protocol/*current-agent*)
                           deref)]
        (some-> (:!session a) deref
                ((or (requiring-resolve 'ai.brainyard.agent.core.session/get-session-config)
                     (constantly nil))
                 :dirs)))
      ((or (requiring-resolve 'ai.brainyard.agent.core.config/init-dirs!)
           (constantly {})))))

(defn- current-extra-bindings
  []
  (let [agent (some-> (requiring-resolve 'ai.brainyard.agent.core.protocol/*current-agent*)
                      deref)]
    ((requiring-resolve 'ai.brainyard.agent.common.sandbox-bindings/auto-tool-bindings)
     agent)))

;; ============================================================================
;; Commands — mirrors the tool-agent$* family
;; ============================================================================

(defcommand hook-agent$events
  "List the pre-defined Brainyard hook events a user hook may target, with each
   event's payload keys (what the handler's `event` map will contain) and whether
   it is gated. Only events with :available? true accept a user-defined hook in
   v1 (observer-only); gated events (block/modify/replace) are reserved. Call
   this FIRST when authoring a hook."
  (fn [& _]
    {:events (->> hooks/event-catalog
                  (map (fn [[ev {:keys [keys gates?]}]]
                         {:event        ev
                          :payload-keys (vec (sort keys))
                          :gated?       (boolean gates?)
                          :available?   (not (boolean gates?))}))
                  (sort-by (comp str :event))
                  vec)})
  :input-schema  [:map]
  :output-schema [:map [:events [:any {:desc "Vector of {:event :payload-keys :gated? :available?}"}]]])

(defcommand hook-agent$validate
  "Dry-run a user-hook draft: parse + eval-smoke-test the body and check the
   id/event/match in a THROWAWAY fork of the hooks sandbox — persists nothing,
   registers nothing, mutates no live state. Use before hook-agent$create to iterate
   safely. Optionally pass :sample (an event map) to run the body once. Mirrors
   hook-agent$create's arg names. Returns a structured report (never throws)."
  (fn [& {:as args}]
    (try
      (let [{:keys [id event body sample]} args
            event-kw    (when event (coerce-event event))
            id-ok       (when id (boolean (re-matches hook-id-re id)))
            event-ok    (boolean (and event-kw (hooks/known-event? event-kw)))
            event-gated (boolean (and event-kw (hooks/gated-event? event-kw)))
            match-map   (coerce-match (:match args))
            match-ok    (boolean (and match-map
                                      (try (build-match match-map) true
                                           (catch Exception _ false))))
            collision   (boolean (and id (find-registered-event id)))
            fork        (sb/fork-sandbox (hooks-sandbox (current-extra-bindings)))
            evald       (when (string? body)
                          (sb/eval-code fork (str "(def __probe " body ")")))
            body-ok     (boolean (and (string? body) (nil? (:error evald))))
            sample-res  (when (and body-ok (map? sample))
                          (sb/set-var! fork 'event sample)
                          (let [r (sb/eval-code fork "(__probe event)")]
                            (if (:error r) {:error (:error r)} (:result r))))
            errors      (cond-> []
                          (false? id-ok)                 (conj "id must match ^[a-z][a-z0-9-]*$")
                          (and event-kw (not event-ok))  (conj (str "unknown event " event-kw))
                          event-gated                    (conj (str event-kw " is gated; user hooks are observer-only in v1"))
                          (nil? event-kw)                (conj ":event is required")
                          (nil? match-map)               (conj ":match is required (e.g. {:tool-name \"bash\"} or {:global true})")
                          (and match-map (not match-ok)) (conj ":match must scope by :defagent-type/:agent-id/:tool-name or be {:global true}")
                          (not (string? body))           (conj ":body is required (a string `(fn [event] ...)`)")
                          (and (string? body) (not body-ok))
                          (conj (str "body failed to eval: " (:error evald))))]
        (cond-> {:valid       (empty? errors)
                 :event-ok    event-ok
                 :event-gated event-gated
                 :match-ok    match-ok
                 :body-ok     body-ok
                 :collision   collision
                 :errors      errors}
          (some? id-ok) (assoc :id-ok id-ok)
          (map? sample) (assoc :sample-result sample-res)))
      (catch Exception e
        {:valid false :event-ok false :event-gated false :match-ok false
         :body-ok false :collision false
         :errors [(str "hook-agent$validate failed: " (.getMessage e))]})))
  :input-schema  [:map
                  [:body     [:string {:desc "Clojure source: a `(fn [event] ...)` of one map"}]]
                  [:id       {:optional true} [:string {:desc "Proposed id; enables id + collision check"}]]
                  [:event    {:optional true} [:string {:desc "Event key, e.g. \"agent.tool-use/post\""}]]
                  [:match    {:optional true} [:string {:desc "Scope as EDN string, e.g. \"{:tool-name \\\"bash\\\"}\" or \"{:global true}\""}]]
                  [:sample   {:optional true} [:map {:desc "Example event map; if given, body is run once on it"}]]]
  :output-schema [:map
                  [:valid         [:boolean {:desc "True iff all checks passed"}]]
                  [:id-ok         {:optional true} [:boolean {:desc "id matches ^[a-z][a-z0-9-]*$"}]]
                  [:event-ok      [:boolean {:desc "Event is in the catalog"}]]
                  [:event-gated   [:boolean {:desc "Event is gated (not allowed for user hooks in v1)"}]]
                  [:match-ok      [:boolean {:desc "Scope map is well-formed"}]]
                  [:body-ok       [:boolean {:desc "Body parses and evals"}]]
                  [:collision     [:boolean {:desc "A hook with this id is already registered"}]]
                  [:sample-result {:optional true} [:any {:desc "Result of running the body on :sample"}]]
                  [:errors        [:any {:desc "Vector of human-readable failure lines"}]]])

(defcommand hook-agent$create
  "Author a reusable, PERSISTENT observer hook from Clojure source. :body is a
   string `(fn [event] ...)` taking the sanitized event map; :event is a
   non-gated event key (see hook-agent$events); :match is a REQUIRED declarative scope
   passed as an EDN string. The hook survives restarts, fires on every matching
   event, and its body may compose other tools by their direct symbol, e.g.
   (bash {…}) or (read-file {…}). Observer-only: the return value is ignored — a
   user hook cannot block or rewrite agent behavior."
  (fn [& {:as args}]
    (try
      (let [extra (current-extra-bindings)]
        (define-hook :id (:id args)
          :event (:event args)
          :match (:match args)
          :priority (:priority args)
          :doc (:doc args)
          :body (:body args)
          :dirs (current-dirs)
          :extra-bindings extra))
      (catch Exception e {:error (str "hook-agent$create failed: " (.getMessage e))})))
  :input-schema  [:map
                  [:id       [:string {:desc "lowercase-kebab hook id (no prefix)"}]]
                  [:event    [:string {:desc "Non-gated event key from hook-agent$events, e.g. \"agent.tool-use/post\""}]]
                  [:body     [:string {:desc "Clojure source: a `(fn [event] ...)` of one map"}]]
                  [:match    [:string {:desc "REQUIRED scope as EDN string: \"{:tool-name \\\"bash\\\"}\", \"{:defagent-type \\\"main-agent\\\"}\", or \"{:global true}\""}]]
                  [:doc      {:optional true} [:string {:desc "one-line description"}]]
                  [:priority {:optional true} [:int {:desc "Higher fires first; default 0"}]]]
  :output-schema [:map
                  [:id        [:string {:desc "Hook id"}]]
                  [:event     [:any {:desc "Registered event key"}]]
                  [:persisted [:string {:desc "Path the source was written to"}]]
                  [:error     [:string {:desc "Error if definition failed"}]]])

(defcommand hook-agent$list
  "List ACTIVE user-defined hooks (authored via hook-agent$create) from the live
   registry. Returns id, event, priority, enriched best-effort with match/doc
   from the persisted source."
  (fn [& _]
    (let [disk (into {} (map (juxt :id identity)) (list-user-hooks (current-dirs)))]
      {:hooks (mapv (fn [h] (merge (select-keys (get disk (:id h)) [:match :doc]) h))
                    (registered-user-hooks))}))
  :input-schema  [:map]
  :output-schema [:map [:hooks [:any {:desc "Vector of {:id :event :priority :match :doc}"}]]])

(defcommand hook-agent$read
  "Read a user-defined hook's full record (incl. body) by id."
  (fn [& {:as args}]
    (if (str/blank? (:id args))
      {:error "id is required"}
      (read-user-hook (current-dirs) (:id args))))
  :input-schema  [:map [:id [:string {:desc "Hook id (no prefix)"}]]]
  :output-schema [:map
                  [:id       [:string {:desc "Hook id"}]]
                  [:event    [:any {:desc "Event key"}]]
                  [:match    [:any {:desc "Scope map"}]]
                  [:priority [:int {:desc "Priority"}]]
                  [:doc      [:string {:desc "Description"}]]
                  [:body     [:string {:desc "Clojure source `(fn [event] ...)`"}]]
                  [:error    [:string {:desc "Error if not found"}]]])

(defcommand hook-agent$delete
  "Delete a user-defined hook: unregister it and remove its persisted source."
  (fn [& {:as args}]
    (if (str/blank? (:id args))
      {:error "id is required"}
      (delete-user-hook! (current-dirs) (:id args))))
  :input-schema  [:map [:id [:string {:desc "Hook id to delete (no prefix)"}]]]
  :output-schema [:map
                  [:deleted [:string {:desc "Id of the deleted hook"}]]
                  [:error   [:string {:desc "Error if not found"}]]])

(def hooks-commands
  "All user-hook management commands, for binding into hook-agent. Mirrors
   `user-tools/tools-commands`."
  [#'hook-agent$events #'hook-agent$create #'hook-agent$validate #'hook-agent$list #'hook-agent$read #'hook-agent$delete])
