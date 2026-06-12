;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
(ns ai.brainyard.playground-ui.views
  "Pure render: app-state -> hiccup. No side effects, no DOM access. Event
   handlers are DATA (`:on {:click [[:action ...]]}`) interpreted by `dispatch`.
   The only imperative corner — the xterm.js terminal — is encapsulated behind
   `terminal/terminal`, which renders a node with life-cycle hooks."
  (:require [ai.brainyard.playground-ui.terminal :as terminal]))

(defn login-view []
  [:div.center
   [:h1 "Brainyard Playground"]
   [:p "Sign in to launch your sandboxed " [:code "by"] " environment."]
   [:button.btn {:on {:click [[:auth/login]]}} "Sign in"]])

(defn session-row [{:keys [id status]}]
  [:li.session {:replicant/key id}
   [:span.id id]
   [:span.status {:class (some-> status name)} (name (or status :unknown))]
   [:span.actions
    [:button {:on {:click [[:nav/workspace id]]}} "Open"]
    (when (= "suspended" (some-> status name))
      [:button {:on {:click [[:session/resume id]]}} "Resume"])
    [:button.danger {:on {:click [[:session/destroy id]]}} "Destroy"]]])

(defn dashboard-view [{:keys [user sessions]}]
  [:div.dashboard
   [:header
    [:h1 "Workspaces"]
    [:div.spacer]
    [:span.user (:email user)]
    [:button {:on {:click [[:auth/logout]]}} "Sign out"]]
   [:button.btn.primary {:on {:click [[:session/create]]}} "New workspace"]
   (if (seq sessions)
     [:ul.sessions (map session-row (vals sessions))]
     [:p.empty "No workspaces yet — create one to start."])])

(defn workspace-view [state id]
  [:div.workspace
   [:header
    [:button {:on {:click [[:nav/dashboard]]}} "← Workspaces"]
    [:span.id id]
    [:span.conn (name (get-in state [:conn id] :idle))]]
   ;; Stable node: the terminal mounts here once and survives header re-renders.
   (terminal/terminal id)])

(defn root
  "Top-level view chosen by auth state, then route."
  [{:keys [user route] :as state}]
  (cond
    (nil? user)   [:div.center [:p "Loading…"]]
    (false? user) (login-view)
    :else
    (case (:name route)
      :route/workspace (workspace-view state (-> route :params :id))
      (dashboard-view state))))
