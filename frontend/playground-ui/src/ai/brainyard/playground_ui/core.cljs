;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
(ns ai.brainyard.playground-ui.core
  "App entry point: wire Replicant's data dispatch, re-render on every state
   change, start HTML5 routing, and resolve the current user. `init` is the
   shadow-cljs :init-fn."
  (:require [replicant.dom :as r]
            [reitit.frontend :as rf]
            [reitit.frontend.easy :as rfe]
            [ai.brainyard.playground-ui.state :as state]
            [ai.brainyard.playground-ui.dispatch :as dispatch]
            [ai.brainyard.playground-ui.views :as views]
            [ai.brainyard.playground-ui.auth :as auth]))

(defonce !el (delay (js/document.getElementById "app")))

(defn render! []
  (r/render @!el (views/root @state/app-state)))

(def routes
  [["/"               {:name :route/dashboard}]
   ["/workspace/:id"  {:name :route/workspace}]])

(defn- on-navigate [m]
  (when m
    (swap! state/app-state assoc :route
           {:name   (-> m :data :name)
            :params (:path-params m)})))

(defn init []
  ;; 1. event handlers + life-cycle hooks expressed as data flow through here
  (r/set-dispatch! dispatch/handle)
  ;; 2. re-render whenever app-state changes (single source of truth)
  (add-watch state/app-state ::render (fn [_ _ _ _] (render!)))
  ;; 3. HTML5 history routing -> app-state :route
  (rfe/start! (rf/router routes) on-navigate {:use-fragment false})
  ;; 4. who am I? (sets :user → flips login vs dashboard); then, if authed,
  ;;    load any existing workspaces.
  (-> (auth/refresh-session!)
      (.then #(when (map? (:user @state/app-state))
                (dispatch/handle {} [[:sessions/reload]]))))
  (render!))

(defn after-load
  "shadow-cljs hot-reload hook."
  []
  (render!))
