;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
(ns ai.brainyard.playground-ui.dispatch
  "The central action interpreter. Replicant event handlers in `views` are pure
   DATA — vectors of action tuples — and every one flows through here. This is
   the only place side effects (API calls, navigation, state mutation) happen,
   which keeps the views pure and the whole UI replayable/testable.

   An action is `[:action/name & args]`; a handler is a collection of them."
  (:require [clojure.string :as str]
            [clojure.walk :as walk]
            [reitit.frontend.easy :as rfe]
            [ai.brainyard.playground-ui.state :as state]
            [ai.brainyard.playground-ui.api :as api]
            [ai.brainyard.playground-ui.auth :as auth]))

(defn- reload-sessions! []
  (-> (api/list-sessions)
      (.then (fn [sessions]
               (swap! state/app-state assoc :sessions
                      (into {} (map (juxt :id identity)) sessions))))))

(defn- env->rows
  "Server env map (keywordized) -> ordered [[name value] ...] for the editor."
  [env]
  (->> env (map (fn [[k v]] [(name k) v])) (sort-by first) vec))

(defn- load-env! []
  (-> (api/get-env)
      (.then (fn [{:keys [env]}]
               (swap! state/app-state assoc :settings
                      {:rows (env->rows env) :status nil})))))

(defn- run-action [dom-event [action & args]]
  (case action
    ;; --- pure state -------------------------------------------------------
    :db/assoc-in   (swap! state/app-state assoc-in (first args) (second args))

    ;; --- navigation -------------------------------------------------------
    :nav/dashboard (rfe/push-state :route/dashboard)
    :nav/workspace (rfe/push-state :route/workspace {:id (first args)})
    :nav/settings  (rfe/push-state :route/settings)

    ;; --- settings: BYO env editor -----------------------------------------
    :settings/load       (load-env!)
    :settings/set-row    (let [[idx field v] args]      ; field 0=name, 1=value
                           (swap! state/app-state assoc-in [:settings :rows idx field] v))
    :settings/add-row    (swap! state/app-state update-in [:settings :rows] (fnil conj []) ["" ""])
    :settings/remove-row (let [idx (first args)]
                           (swap! state/app-state update-in [:settings :rows]
                                  #(into (subvec % 0 idx) (subvec % (inc idx)))))
    :settings/save
    (let [rows (get-in @state/app-state [:settings :rows])
          env  (into {} (for [[k v] rows :when (not (str/blank? k))] [(str/trim k) v]))]
      (swap! state/app-state assoc-in [:settings :status] :saving)
      (-> (api/put-env env)
          (.then (fn [{:keys [env]}]
                   (swap! state/app-state assoc :settings
                          {:rows (env->rows env) :status :saved})))
          (.catch (fn [_] (swap! state/app-state assoc-in [:settings :status] :error)))))

    ;; --- auth -------------------------------------------------------------
    :auth/login    (auth/login!)
    :auth/logout   (auth/logout!)

    ;; --- session lifecycle (maps onto §5.2 of the design) -----------------
    :sessions/reload (reload-sessions!)
    :session/create
    (-> (api/create-session!)
        (.then (fn [s]
                 (swap! state/app-state assoc-in [:sessions (:id s)] s)
                 (rfe/push-state :route/workspace {:id (:id s)})))
        (.catch (fn [_] (js/console.error "create-session failed"))))
    :session/resume  (-> (api/resume!  (first args)) (.then reload-sessions!))
    :session/destroy (-> (api/destroy! (first args)) (.then reload-sessions!))

    ;; --- escape hatch for imperative event control ------------------------
    :event/prevent-default (some-> dom-event .preventDefault)

    (js/console.warn "Unknown action:" (pr-str action))))

(defn- interpolate
  "Replace data placeholders with live values from the DOM event before running
   actions (clojure.walk templating, per the Replicant guide)."
  [dom-event actions]
  (walk/postwalk
   (fn [x]
     (case x
       :event/target.value (some-> dom-event .-target .-value)
       x))
   actions))

(defn handle
  "Global Replicant dispatch. Only wired for DOM events here; terminal
   life-cycle hooks use function form, so they never reach this."
  [event-data actions]
  (let [dom-event (:replicant/dom-event event-data)]
    (doseq [action (interpolate dom-event actions)]
      (run-action dom-event action))))
