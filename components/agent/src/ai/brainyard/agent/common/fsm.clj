;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.common.fsm
  "User-defined state machines (Phase 1 of docs/design/state-machine-design.md).

   An FSM is a *stateful reactor*: durable per-session state + state-gated
   transitions over the same event bus (ai.brainyard.agent.core.hooks). A fired
   event looks up the current state's transitions, evaluates their declarative
   guards, and — on the first match — runs the transition's actions (reusing the
   reactor's `run-action!` sinks, plus `:assign`) and moves to the target state,
   firing `:fsm/transition` / `:fsm/entered` / `:fsm/final` back onto the bus.

   Definition (project-scoped): `<project>/.brainyard/fsm/<id>/machine.edn`.
   Runtime state (per-session):  `<project>/.brainyard/fsm/<id>/runtime/<sid>.edn`.

   v1 is declarative-only (guards + actions are data, no code-eval); an SCI
   code-guard escape hatch is Phase 4. Off by default (`:enable-fsm`)."
  (:require [ai.brainyard.agent.common.events :as events]
            [ai.brainyard.agent.common.reactor :as reactor]
            [ai.brainyard.agent.core.config :as config]
            [ai.brainyard.agent.core.hooks :as hooks]
            [ai.brainyard.agent.core.protocol :as proto]
            [ai.brainyard.agent.core.tool :refer [defcommand]]
            [ai.brainyard.mulog.interface :as mulog]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.io File]))

(def ^:private history-cap 25)
(def ^:private max-depth
  "Hard cap on synchronous re-entrancy (an :emit action re-entering the engine)."
  8)

(def ^:private !get-parent-agent
  (delay (requiring-resolve 'ai.brainyard.agent.core.runtime/get-parent-agent)))

(defn- root-agent? [agent]
  (and agent (:!state agent) (nil? (@!get-parent-agent (:!state agent)))))

(defn- agent-running? [agent]
  (try (= :running (:status @(:!state agent))) (catch Throwable _ false)))

(defn- kw [x] (cond (keyword? x) x (string? x) (events/->event-key x) :else x))

;; ============================================================================
;; Store — definitions + per-session runtime state (atomic, mirrors schedule)
;; ============================================================================

(def ^:private id-re #"^[a-z0-9][a-z0-9-]*$")
(defn valid-id? [id] (boolean (and (string? id) (re-matches id-re id))))

(defn ^File fsm-root [project-dir] (io/file (str project-dir) ".brainyard" "fsm"))
(defn- ^File machine-file [project-dir id] (io/file (fsm-root project-dir) id "machine.edn"))
(defn- ^File runtime-file [project-dir id sid]
  (io/file (fsm-root project-dir) id "runtime"
           (str (str/replace (str sid) #"[^a-zA-Z0-9]+" "_") ".edn")))

(defn- atomic-spit! [^File f content]
  (.mkdirs (.getParentFile f))
  (let [tmp (io/file (.getParentFile f) (str (.getName f) ".tmp"))]
    (spit tmp content)
    (.renameTo tmp f)))

(defn- read-edn [^File f]
  (when (.exists f)
    (try (edn/read-string (slurp f))
         (catch Exception e (mulog/warn ::read-failed :file (str f) :exception e) nil))))

(defn- normalize-machine
  "Coerce state / event / target names to keywords so a definition authored with
   string keys (a JSON tool-call) behaves like one authored in EDN (a code fence)."
  [m]
  (when (map? m)
    (-> m
        (update :initial kw)
        (update :states
                (fn [states]
                  (reduce-kv
                   (fn [acc sname sdef]
                     (assoc acc (kw sname)
                            (update sdef :on
                                    (fn [on]
                                      (reduce-kv
                                       (fn [o ev ts] (assoc o (kw ev) (mapv #(update % :target kw) ts)))
                                       {} (or on {}))))))
                   {} (or states {})))))))

(defn write-machine! [project-dir {:keys [id] :as machine}]
  (when-not (valid-id? id)
    (throw (ex-info (str "invalid fsm id: " (pr-str id)) {:id id})))
  (atomic-spit! (machine-file project-dir id) (pr-str machine))
  machine)

(defn read-machine [project-dir id]
  (some-> (read-edn (machine-file project-dir id)) normalize-machine))

(defn list-machines [project-dir]
  (let [^File root (fsm-root project-dir)]
    (if-not (.isDirectory root)
      []
      (->> (.listFiles root)
           (filter #(.isDirectory ^File %))
           (keep #(read-machine project-dir (.getName ^File %)))
           (sort-by :id)
           vec))))

(defn delete-machine! [project-dir id]
  (when (valid-id? id)
    (let [^File dir (io/file (fsm-root project-dir) id)]
      (when (.isDirectory dir)
        (doseq [^File f (reverse (file-seq dir))] (.delete f))
        true))))

(defn write-runtime! [project-dir id sid rt]
  (atomic-spit! (runtime-file project-dir id sid) (pr-str rt)) rt)

(defn- initial-runtime [machine]
  {:machine (:id machine) :state (:initial machine)
   :context (or (:context machine) {}) :history []
   :entered-at (System/currentTimeMillis)})

(defn current-runtime
  "Read-only view of a machine's runtime state (initial if never run)."
  [project-dir machine sid]
  (or (read-edn (runtime-file project-dir (:id machine) sid))
      (initial-runtime machine)))

(defn- ensure-runtime!
  "Like current-runtime, but persists the initial runtime the first time a
   machine is touched so `:entered-at` (the timed-transition clock) is stable."
  [project-dir machine sid]
  (or (read-edn (runtime-file project-dir (:id machine) sid))
      (write-runtime! project-dir (:id machine) sid (initial-runtime machine))))

;; ============================================================================
;; Guards (declarative)
;; ============================================================================

(defn- submap? [sub m]
  (or (nil? sub) (and (map? sub) (empty? sub))
      (and (map? m) (every? (fn [[k v]] (= v (get m k))) sub))))

(defn- as-num [v] (when (some? v) (let [s (str v)] (or (parse-long s) (parse-double s)))))

(defn- clause-pass? [k v {:keys [event context agent entered-at now]}]
  (case (keyword k)
    :event/match    (submap? v event)
    :context/=      (every? (fn [[ck cv]] (= cv (get context ck))) v)
    :context/gte    (every? (fn [[ck cv]] (let [n (as-num (get context ck)) t (as-num cv)]
                                            (boolean (and n t (>= n t))))) v)
    :context/all    (every? #(get context %) v)
    :context/any    (boolean (some #(get context %) v))
    :agent/idle?    (= (boolean v) (not (agent-running? agent)))
    :agent/running? (= (boolean v) (boolean (agent-running? agent)))
    ;; timed: milliseconds elapsed in the current state ≥ v
    :elapsed/gte    (boolean (and entered-at now (>= (- now entered-at) (or (as-num v) 0))))
    (do (mulog/warn ::unknown-guard-clause :clause k) true)))

(defn guard-pass?
  "Declarative guard over `{:event :context :agent}`. A map is AND over its
   clauses; a vector is AND over sub-guards; nil accepts."
  [guard gctx]
  (cond
    (nil? guard)    true
    (map? guard)    (every? (fn [[k v]] (clause-pass? k v gctx)) guard)
    (vector? guard) (every? #(guard-pass? % gctx) guard)
    :else           true))

;; ============================================================================
;; Actions — reuse reactor sinks; :assign is FSM-specific
;; ============================================================================

(defn- apply-assign [context assign-map event]
  (reduce-kv
   (fn [ctx k spec]
     (assoc ctx k
            (cond
              (= spec [:inc]) (inc (or (as-num (get ctx k)) 0))
              (= spec [:dec]) (dec (or (as-num (get ctx k)) 0))
              (and (vector? spec) (= :from-event (first spec))) (get event (second spec))
              :else spec)))
   context assign-map))

(defn- normalize-action
  "Accept both the terse vector form `[:emit :x]` / `[:assign {…}]` /
   `[:turn \"txt\"]` and the reactor-style map form `{:as :emit :event :x}`, and
   return the map form that `run-action!` / `:assign` consume."
  [a]
  (cond
    (map? a)    a
    (vector? a) (let [[k arg] a, as (keyword k)]
                  (case as
                    :emit      {:as :emit :event arg}
                    :fire-hook {:as :fire-hook :event arg}
                    :assign    {:as :assign :assign arg}
                    (:turn :run :context) (merge {:as as} (if (string? arg) {:text arg} (or arg {})))
                    (:artifact :memory)   (merge {:as as} (or arg {}))
                    {:as as}))
    :else       a))

(defn- run-actions! [agent actions ctx-atom event-payload machine-id]
  (doseq [raw actions
          :let [action (normalize-action raw)]]
    (if (= :assign (keyword (:as action)))
      (swap! ctx-atom apply-assign (:assign action) event-payload)
      (reactor/run-action! agent action
                           (merge @ctx-atom (when (map? event-payload) event-payload))
                           {:context-event (keyword "fsm" machine-id) :source :fsm}))))

;; ============================================================================
;; step! / tick! — the transition engine
;; ============================================================================

(defn- apply-transition!
  "Run transition `t` from state `s` of `machine` for `sid`: exit → :do → entry
   actions (+ :assign), persist the new state (resetting :entered-at on a real
   state change), and fire the :fsm lifecycle events. Returns the new state."
  [agent project-dir machine sid rt s t event-kw payload]
  (let [ctx     (atom (or (:context rt) {}))
        target  (or (some-> (:target t) kw) s)
        src-def (get-in machine [:states s])
        tgt-def (get-in machine [:states target])
        now     (System/currentTimeMillis)]
    (run-actions! agent (:exit src-def) ctx payload (:id machine))
    (run-actions! agent (:do t)         ctx payload (:id machine))
    (when (not= target s)
      (run-actions! agent (:entry tgt-def) ctx payload (:id machine)))
    (let [rt' (cond-> (assoc rt :state target :context @ctx)
                (not= target s) (assoc :entered-at now)
                :always (update :history
                                (fn [h] (->> (conj (vec h) {:from s :to target :event event-kw :ts now})
                                             (take-last history-cap) vec))))]
      (write-runtime! project-dir (:id machine) sid rt')
      (hooks/fire! :fsm/transition {:machine (:id machine) :from s :to target
                                    :event event-kw :context @ctx})
      (hooks/fire! :fsm/entered {:machine (:id machine) :state target :context @ctx})
      (when (= :final (get-in machine [:states target :type]))
        (hooks/fire! :fsm/final {:machine (:id machine) :state target}))
      (mulog/info ::stepped :machine (:id machine) :from s :to target :event event-kw)
      target)))

(defn- pick-transition [transes rt payload agent]
  (let [gctx {:event payload :context (:context rt) :agent agent
              :entered-at (:entered-at rt) :now (System/currentTimeMillis)}]
    (first (filter #(guard-pass? (:guard %) gctx) transes))))

(defn step!
  "Advance `machine` for `sid` on `event-kw`: pick the first current-state
   `:on`-transition whose guard passes and apply it. Returns the new state or nil."
  [agent project-dir machine sid event-kw payload]
  (let [rt (ensure-runtime! project-dir machine sid)
        s  (:state rt)]
    (when-let [t (pick-transition (get-in machine [:states s :on event-kw]) rt payload agent)]
      (apply-transition! agent project-dir machine sid rt s t event-kw payload))))

(defn- eventless-transitions
  "The current state's eventless transitions: explicit `:always` plus `:after`
   entries (each `{:after <ms> :target …}` gains an `:elapsed/gte <ms>` guard)."
  [state-def]
  (concat (:always state-def)
          (for [a (:after state-def)
                :let [ms (:after a)]]
            (-> (dissoc a :after)
                (assoc :guard (let [g (:guard a)]
                                (if g [g {:elapsed/gte ms}] {:elapsed/gte ms})))))))

(defn tick!
  "Evaluate `machine`'s eventless / timed transitions for `sid` once: apply the
   first current-state `:always`/`:after` transition whose guard passes. Returns
   the new state or nil. Driven by the scheduler tick."
  [agent project-dir machine sid]
  (let [rt (ensure-runtime! project-dir machine sid)
        s  (:state rt)]
    (when-let [t (pick-transition (eventless-transitions (get-in machine [:states s])) rt nil agent)]
      (apply-transition! agent project-dir machine sid rt s t :fsm/tick nil))))

(defn tick-machines!
  "Tick every machine for `sid`, chaining eventless transitions until they settle
   (bounded, so a mistaken `:always` loop can't spin)."
  [agent project-dir sid]
  (doseq [machine (list-machines project-dir)]
    (loop [n 0]
      (when (and (< n max-depth)
                 (try (tick! agent project-dir machine sid)
                      (catch Throwable e
                        (mulog/warn ::fsm-tick-failed :machine (:id machine) :exception e) nil)))
        (recur (inc n))))))

;; ============================================================================
;; Bus handler + per-session install (mirrors reactor/ensure-reactions!)
;; ============================================================================

(defonce ^:private !installed (atom {}))          ; sid -> {:agent :events}
(defonce ^:private !cleanup-installed (atom false))

(defn- event-session-id [payload]
  (or (:session-id payload)
      (try (some-> (:agent payload) proto/session-id) (catch Throwable _ nil))))
(defn- session-match? [sid ev-sid] (or (nil? ev-sid) (nil? sid) (= sid ev-sid)))

(defn- make-handler [agent sid project-dir event-kw]
  (fn [payload]
    (let [ev-sid (event-session-id payload)]
      (when (and (session-match? sid ev-sid) (< (hooks/current-depth) max-depth))
        (doseq [machine (list-machines project-dir)
                :let  [s (:state (current-runtime project-dir machine sid))]
                :when (seq (get-in machine [:states s :on event-kw]))]
          (try (step! agent project-dir machine sid event-kw payload)
               (catch Throwable e
                 (mulog/warn ::fsm-step-failed :machine (:id machine) :exception e))))))))

(defn- desired-events [project-dir]
  (into #{} (for [m (list-machines project-dir)
                  [_ sdef] (:states m)
                  ev (keys (:on sdef))]
              ev)))

(defn- any-eventless?
  "True when any machine has `:always`/`:after` transitions — i.e. it needs the
   scheduler tick to advance timed / eventless transitions."
  [project-dir]
  (boolean (some (fn [m] (some (fn [[_ sd]] (or (seq (:always sd)) (seq (:after sd))))
                               (:states m)))
                 (list-machines project-dir))))

(defn- fsm-source [sid] (keyword "fsm-src" (str/replace (str sid) #"[^a-zA-Z0-9]+" "_")))
(defn- fsm-hid [sid ev]
  (keyword "fsm" (str (str/replace (str sid) #"[^a-zA-Z0-9]+" "_") "__" (name ev))))

(defn- teardown-session! [sid]
  (hooks/unregister-source! (fsm-source sid))
  (swap! !installed dissoc sid))

(defn- ensure-cleanup-hook! []
  (when (compare-and-set! !cleanup-installed false true)
    (hooks/register-hook!
     :agent.session/closed ::fsm-cleanup
     (fn [{:keys [session-id]}] (when session-id (teardown-session! session-id)))
     :source :fsm-cleanup)))

(defn ensure-fsm!
  "Install / re-sync this session's FSM bus handlers. Runtime-only, safe every
   turn: no-op unless `:enable-fsm` and a root agent. One handler per trigger
   event across all machines; re-syncs on machine-set or agent-instance change;
   torn down on session close. Toggling off tears the handlers down."
  [agent]
  (let [sid (try (proto/session-id agent) (catch Throwable _ nil))]
    (when (and agent sid (root-agent? agent))
      (if-not (config/get-config agent :enable-fsm)
        (when (contains? @!installed sid) (teardown-session! sid))
        (let [pdir    (str (config/project-dir agent))
              want    (desired-events pdir)
              tick?   (any-eventless? pdir)
              current (get @!installed sid)]
          (ensure-cleanup-hook!)
          (when (or (not= (:agent current) agent) (not= (:events current) want)
                    (not= (:tick? current) tick?))
            (hooks/unregister-source! (fsm-source sid))
            (doseq [ev want]
              (hooks/register-hook! ev (fsm-hid sid ev) (make-handler agent sid pdir ev)
                                    :source (fsm-source sid)))
            ;; Timed / eventless transitions advance on the scheduler tick (needs
            ;; :enable-scheduler for the ticker). See state-machine-design.md §6.
            (when tick?
              (hooks/register-hook!
               :scheduler/tick (fsm-hid sid :scheduler-tick)
               (fn [_] (when (< (hooks/current-depth) max-depth) (tick-machines! agent pdir sid)))
               :source (fsm-source sid)))
            (swap! !installed assoc sid {:agent agent :events want :tick? tick?})
            (mulog/info ::fsm-synced :session sid :events (vec want) :tick tick?))))))
  nil)

(defn reset-state!
  "Clear engine state + handlers. For tests."
  []
  (doseq [sid (keys @!installed)] (hooks/unregister-source! (fsm-source sid)))
  (reset! !installed {})
  (reset! !cleanup-installed false)
  (hooks/unregister-source! :fsm-cleanup))

;; ============================================================================
;; Commands
;; ============================================================================

(defcommand fsm$define
  "Define (or replace) a user state machine: :id :initial :states {state -> {:on {event [{:target …}]} :entry :exit}}."
  (fn [& {:as opts}]
    (let [id (str (:id opts)) initial (:initial opts) states (:states opts)]
      (cond
        (not (valid-id? id)) {:error "invalid :id — use lowercase-kebab (e.g. 'deploy-gate')"}
        (not (map? states))  {:error ":states must be a map of state -> {:on {event [{:target …}]} …}"}
        (nil? initial)       {:error ":initial state is required"}
        :else
        (let [machine (cond-> {:id id :initial (kw initial) :states states
                               :created (System/currentTimeMillis)}
                        (map? (:context opts)) (assoc :context (:context opts)))]
          (write-machine! (config/project-dir) (normalize-machine machine))
          (cond-> {:defined id :initial (kw initial) :states (mapv kw (keys states))}
            (not (config/get-config :enable-fsm))
            (assoc :note "State machines are OFF — set :enable-fsm true (or BY_ENABLE_FSM) to run them; the definition is stored regardless."))))))
  :input-schema  [:map
                  [:id      [:string {:desc "Machine id (lowercase-kebab)"}]]
                  [:initial [:string {:desc "Initial state name"}]]
                  [:states  [:any {:desc "Map of state -> {:on {event [{:target … :guard … :do …}]} :entry [action…] :exit […] :type :final}; a state def is arbitrary EDN, so :any"}]]
                  [:context {:optional true} [:map-of {:desc "Initial context variables"} :any :any]]]
  :output-schema [:map
                  [:defined {:optional true} [:string]]
                  [:initial {:optional true} [:any]]
                  [:states  {:optional true} [:vector :any]]
                  [:note    {:optional true} [:string]]
                  [:error   {:optional true} [:string]]])

(defcommand fsm$list
  "List defined state machines with their current state for this session."
  (fn [& _]
    (let [pdir (config/project-dir) sid (proto/get-current-session-id)]
      {:machines (mapv (fn [m]
                         {:id (:id m) :initial (:initial m)
                          :state (when sid (:state (current-runtime pdir m sid)))
                          :states (vec (keys (:states m)))})
                       (list-machines pdir))}))
  :input-schema  [:map]
  :output-schema [:map [:machines [:vector {:desc "Machine summaries"} :any]]])

(defcommand fsm$status
  "Show a machine's current state, context, and recent transitions for this session."
  (fn [& {:keys [id]}]
    (let [pdir (config/project-dir) sid (proto/get-current-session-id)
          machine (read-machine pdir (str id))]
      (cond
        (nil? machine) {:error (str "no machine '" id "'")}
        (nil? sid)     {:error "no active session"}
        :else (let [rt (current-runtime pdir machine sid)]
                {:id (str id) :state (:state rt) :context (:context rt)
                 :history (vec (take-last 10 (:history rt)))}))))
  :input-schema  [:map [:id [:string {:desc "Machine id"}]]]
  :output-schema [:map
                  [:id      {:optional true} [:string]]
                  [:state   {:optional true} [:any]]
                  [:context {:optional true} [:any]]
                  [:history {:optional true} [:vector :any]]
                  [:error   {:optional true} [:string]]])

(defcommand fsm$send
  "Send an event to the machines (fires it on the bus; a matching transition advances). Sugar over event$emit, scoped to this session."
  (fn [& {:keys [event payload]}]
    (let [sid  (proto/get-current-session-id)
          base (cond-> (if (map? payload) payload {}) sid (assoc :session-id sid))
          r    (events/emit-event! event base)]
      (if (:error r) r {:sent (:fired r)})))
  :input-schema  [:map
                  [:event   [:string {:desc "Event to fire (namespaced keyword)"}]]
                  [:payload {:optional true} [:map-of {:desc "Event payload"} :any :any]]]
  :output-schema [:map [:sent {:optional true} [:any]] [:error {:optional true} [:string]]])

(defcommand fsm$reset
  "Reset a machine's runtime state to its :initial state for this session."
  (fn [& {:keys [id]}]
    (let [pdir (config/project-dir) sid (proto/get-current-session-id)
          machine (read-machine pdir (str id))]
      (cond
        (nil? machine) {:error (str "no machine '" id "'")}
        (nil? sid)     {:error "no active session"}
        :else (do (write-runtime! pdir (str id) sid (initial-runtime machine))
                  {:id (str id) :state (:initial machine)}))))
  :input-schema  [:map [:id [:string {:desc "Machine id"}]]]
  :output-schema [:map [:id {:optional true} [:string]] [:state {:optional true} [:any]]
                  [:error {:optional true} [:string]]])

(defcommand fsm$remove
  "Remove a state machine definition (and its runtime state)."
  (fn [& {:keys [id]}]
    (if (delete-machine! (config/project-dir) (str id))
      {:removed (str id)}
      {:error (str "no machine '" id "'")}))
  :input-schema  [:map [:id [:string {:desc "Machine id"}]]]
  :output-schema [:map [:removed {:optional true} [:string]] [:error {:optional true} [:string]]])

(def fsm-commands
  "State-machine command family, bound into the common roster."
  [#'fsm$define #'fsm$list #'fsm$status #'fsm$send #'fsm$reset #'fsm$remove])
