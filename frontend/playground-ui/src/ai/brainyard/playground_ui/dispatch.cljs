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
            [ai.brainyard.playground-ui.graph :as graph]
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
      (.then (fn [{:keys [env suggested]}]
               (swap! state/app-state assoc :settings
                      {:rows (env->rows env) :suggested (vec suggested)
                       :reveal #{} :status nil})))))

(defn- load-graph! [id]
  (swap! state/app-state assoc-in [:graph id :status] :loading)
  (-> (api/graph id)
      (.then
       (fn [{:keys [success enabled? nodes edges counts] :as g}]
         (let [nodes  (vec nodes)
               edges  (vec edges)
               status (cond
                        (false? success)  :error
                        (empty? nodes)    (if enabled? :empty :not-enabled)
                        :else             :ready)
               pos    (when (= status :ready) (graph/layout nodes edges))]
           (swap! state/app-state update-in [:graph id] merge
                  {:status status :enabled? enabled? :error (:error g)
                   :nodes nodes :edges edges
                   :counts (or counts {:nodes (count nodes) :edges (count edges)})
                   :pos pos :view (when pos (graph/view pos))
                   :selected nil :drag nil}))))
      (.catch (fn [_] (swap! state/app-state assoc-in [:graph id :status] :error)))))

;; Zoom the viewBox by `factor` about SVG point [cx cy] (keeps that point fixed).
(defn- zoom-view [{:keys [x y w h]} factor cx cy]
  (let [w' (-> (* w factor) (max 60) (min 40000))
        h' (-> (* h factor) (max 42) (min 28000))
        f  (/ w' w)]
    {:x (- cx (* (- cx x) f)) :y (- cy (* (- cy y) f)) :w w' :h h'}))

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
                           (swap! state/app-state update :settings
                                  (fn [s]
                                    (-> s
                                        (update :rows #(into (subvec % 0 idx) (subvec % (inc idx))))
                                        ;; reveal tracks row indices; drop the removed
                                        ;; one and shift the rest down to stay aligned.
                                        (update :reveal
                                                (fn [r] (into #{} (comp (remove #{idx})
                                                                        (map #(if (> % idx) (dec %) %)))
                                                              (set r))))))))
    ;; Toggle plaintext reveal of a masked credential value (eye button).
    :settings/toggle-reveal (let [idx (first args)]
                              (swap! state/app-state update-in [:settings :reveal]
                                     (fn [r] (let [r (set r)]
                                               (if (contains? r idx) (disj r idx) (conj r idx))))))
    :settings/save
    (let [rows (get-in @state/app-state [:settings :rows])
          env  (into {} (for [[k v] rows :when (not (str/blank? k))] [(str/trim k) v]))]
      (swap! state/app-state assoc-in [:settings :status] :saving)
      (-> (api/put-env env)
          (.then (fn [{:keys [env]}]
                   ;; Rows come back re-sorted; re-mask (clear reveal) so a stale
                   ;; index can't expose a different row's secret.
                   (swap! state/app-state update :settings merge
                          {:rows (env->rows env) :reveal #{} :status :saved})))
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
                 (if (= "ready" (:status s))
                   (rfe/push-state :route/workspace {:id (:id s)})  ; fake mode: instant
                   (run-action nil [:provision/start (:id s) true])))) ; real: poll → nav
        (.catch (fn [_] (js/console.error "create-session failed"))))
    :session/resume
    (let [id (first args)]
      (-> (api/resume! id)
          (.then (fn [s]
                   (swap! state/app-state assoc-in [:sessions id] s)
                   (if (= "ready" (:status s))
                     (reload-sessions!)
                     (run-action nil [:provision/start id false]))))))  ; poll, stay on dash
    :session/destroy (-> (api/destroy! (first args)) (.then reload-sessions!))

    ;; --- workspace provisioning progress (poll until ready/failed) --------
    ;; Backend create!/resume! return a `provisioning` record immediately and
    ;; start the container in the background; we poll the session status and
    ;; drive the progress-bar modal off elapsed time until it goes ready/failed.
    :provision/start
    (let [[id nav?] args]
      (swap! state/app-state assoc-in [:provision id]
             {:status "provisioning" :elapsed 0 :nav? (boolean nav?)})
      (run-action nil [:provision/poll id (js/Date.now)]))
    :provision/poll
    (let [[id started-at] args]
      ;; Stop if the entry was dismissed/removed (e.g. failed → Dismiss).
      (when-let [p (get-in @state/app-state [:provision id])]
        (swap! state/app-state assoc-in [:provision id :elapsed] (- (js/Date.now) started-at))
        (-> (api/get-session id)
            (.then (fn [s]
                     (let [st (:status s)]
                       (swap! state/app-state assoc-in [:sessions id] s)
                       (swap! state/app-state assoc-in [:provision id :status] st)
                       (case st
                         "ready"  (do (swap! state/app-state update :provision dissoc id)
                                      (if (:nav? p)
                                        (rfe/push-state :route/workspace {:id id})
                                        (reload-sessions!)))
                         "failed" nil  ; keep entry so the modal shows error + Retry
                         (js/setTimeout #(run-action nil [:provision/poll id started-at]) 800)))))
            (.catch (fn [_]
                      ;; transient (e.g. brief 404 before persist) — keep polling
                      (js/setTimeout #(run-action nil [:provision/poll id started-at]) 1000))))))
    :provision/dismiss
    (swap! state/app-state update :provision dissoc (first args))

    ;; --- workspace dev-port dropdown --------------------------------------
    :ports/load
    (let [id (first args)]
      (-> (api/ports id)
          (.then (fn [{:keys [ports]}]
                   (swap! state/app-state assoc-in [:ports id] (vec ports))))
          (.catch (fn [_] nil))))
    :ports/open
    (let [host (first args)]
      (when-not (str/blank? (str host))
        (js/window.open (str "http://127.0.0.1:" host) "_blank" "noopener"))
      ;; reset the <select> back to its placeholder so it reads as a menu
      (when-let [t (some-> dom-event .-target)] (set! (.-value t) "")))

    ;; --- brainyard session config view (per workspace) --------------------
    :brainyard/toggle
    (let [id    (first args)
          open? (get-in @state/app-state [:brainyard id :open?])]
      (swap! state/app-state assoc-in [:brainyard id :open?] (not open?))
      ;; Lazily load the session list the first time the panel opens.
      (when (and (not open?) (nil? (get-in @state/app-state [:brainyard id :sessions])))
        (run-action nil [:brainyard/load-sessions id])))
    :brainyard/load-sessions
    (let [id (first args)]
      (swap! state/app-state assoc-in [:brainyard id :status] :loading)
      (-> (api/brainyard-sessions id)
          (.then (fn [{:keys [sessions]}]
                   (let [ss (vec sessions)]
                     (swap! state/app-state update-in [:brainyard id] merge
                            {:sessions ss :status nil})
                     ;; Auto-select the first session so the panel isn't empty.
                     (when-let [sid (:session-id (first ss))]
                       (run-action nil [:brainyard/select id sid])))))
          (.catch (fn [_] (swap! state/app-state assoc-in [:brainyard id :status] :error)))))
    :brainyard/select
    (let [[id sid] args]
      (swap! state/app-state assoc-in [:brainyard id :selected] sid)
      (when (nil? (get-in @state/app-state [:brainyard id :config sid]))
        (run-action nil [:brainyard/load-config id sid])))
    :brainyard/load-config
    (let [[id sid] args]
      (swap! state/app-state assoc-in [:brainyard id :config sid] {:loading? true})
      (-> (api/session-config id sid)
          (.then (fn [cfg] (swap! state/app-state assoc-in [:brainyard id :config sid] cfg)))
          (.catch (fn [_] (swap! state/app-state assoc-in [:brainyard id :config sid]
                                 {:success false :error "request failed"})))))
    :brainyard/toggle-snapshot
    (swap! state/app-state update-in [:brainyard (first args) :show-snapshot?] not)

    ;; --- graph memory canvas (per workspace) ------------------------------
    :graph/toggle
    (let [id    (first args)
          open? (get-in @state/app-state [:graph id :open?])]
      (swap! state/app-state assoc-in [:graph id :open?] (not open?))
      ;; (Re)load every time it opens so the canvas reflects the latest graph.
      (when-not open? (load-graph! id)))
    :graph/refresh (load-graph! (first args))

    :graph/select
    (let [[id nid] args]
      (swap! state/app-state assoc-in [:graph id :selected] nid))

    :graph/refit
    (let [id (first args)]
      (when-let [pos (get-in @state/app-state [:graph id :pos])]
        (swap! state/app-state assoc-in [:graph id :view] (graph/view pos))))

    :graph/zoom
    (let [id (first args)
          v  (get-in @state/app-state [:graph id :view])]
      (when (and v dom-event)
        (let [factor (if (neg? (.-deltaY dom-event)) 0.85 1.18)
              [cx cy] (graph/event->svg dom-event v)]
          (swap! state/app-state assoc-in [:graph id :view] (zoom-view v factor cx cy)))))

    :graph/node-down
    (let [[id nid] args
          v (get-in @state/app-state [:graph id :view])
          p (get-in @state/app-state [:graph id :pos nid])]
      (when (and v p dom-event)
        (let [[px py] (graph/event->svg dom-event v)]
          (try (.setPointerCapture (.-currentTarget dom-event) (.-pointerId dom-event))
               (catch :default _ nil))
          (swap! state/app-state update-in [:graph id] assoc
                 :selected nid
                 :drag {:mode :node :nid nid :ox (- px (first p)) :oy (- py (second p))}))))

    :graph/bg-down
    (let [id (first args)
          v  (get-in @state/app-state [:graph id :view])]
      (when (and v dom-event)
        (let [svg  (.-currentTarget dom-event)
              rect (.getBoundingClientRect svg)]
          (try (.setPointerCapture svg (.-pointerId dom-event))
               (catch :default _ nil))
          (swap! state/app-state assoc-in [:graph id :drag]
                 {:mode :pan
                  :sx (.-clientX dom-event) :sy (.-clientY dom-event)
                  :vx (:x v) :vy (:y v)
                  :kx (/ (:w v) (max 1 (.-width rect)))
                  :ky (/ (:h v) (max 1 (.-height rect)))}))))

    :graph/pointer-move
    (let [id   (first args)
          drag (get-in @state/app-state [:graph id :drag])]
      (when (and drag dom-event)
        (case (:mode drag)
          :node (let [v (get-in @state/app-state [:graph id :view])
                      [px py] (graph/event->svg dom-event v)]
                  (swap! state/app-state assoc-in [:graph id :pos (:nid drag)]
                         [(- px (:ox drag)) (- py (:oy drag))]))
          :pan  (swap! state/app-state update-in [:graph id :view] merge
                       {:x (- (:vx drag) (* (- (.-clientX dom-event) (:sx drag)) (:kx drag)))
                        :y (- (:vy drag) (* (- (.-clientY dom-event) (:sy drag)) (:ky drag)))})
          nil)))

    :graph/pointer-up
    (swap! state/app-state assoc-in [:graph (first args) :drag] nil)

    ;; --- escape hatch for imperative event control ------------------------
    :event/prevent-default   (some-> dom-event .preventDefault)
    :event/stop-propagation  (some-> dom-event .stopPropagation)

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
