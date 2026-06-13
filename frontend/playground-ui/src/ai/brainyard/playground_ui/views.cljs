;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
(ns ai.brainyard.playground-ui.views
  "Pure render: app-state -> hiccup. No side effects, no DOM access. Event
   handlers are DATA (`:on {:click [[:action ...]]}`) interpreted by `dispatch`.
   The terminal is ttyd's own self-contained client, embedded same-origin via an
   iframe (`/api/sessions/:id/term/`) — so the TUI renders exactly as ttyd
   intends, with no hand-rolled xterm client to maintain.")

(defn login-view []
  [:div.center {:replicant/key :view/login}
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
  ;; Distinct :replicant/key per top-level view so Replicant REPLACES the subtree
  ;; on navigation instead of diffing dashboard<->workspace in place. The
  ;; workspace subtree contains xterm's imperative (foreign) DOM; diffing across
  ;; that boundary corrupts Replicant's vdom tracking and wedges later renders.
  [:div.dashboard {:replicant/key :view/dashboard}
   [:header
    [:h1 "Workspaces"]
    [:div.spacer]
    [:span.user (:email user)]
    [:button {:on {:click [[:auth/logout]]}} "Sign out"]]
   [:button.btn.primary {:on {:click [[:session/create]]}} "New workspace"]
   (if (seq sessions)
     [:ul.sessions (map session-row (vals sessions))]
     [:p.empty "No workspaces yet — create one to start."])])

(defn workspace-view [_state id]
  [:div.workspace {:replicant/key :view/workspace}
   [:header
    [:button {:on {:click [[:nav/dashboard]]}} "← Workspaces"]
    [:span.id id]]
   ;; ttyd's own client, proxied same-origin. Stable :replicant/key so the iframe
   ;; (and its live ttyd session) survives header re-renders.
   [:iframe.term {:replicant/key (str "term-" id)
                  :src (str "/api/sessions/" id "/term/")}]])

(defn root
  "Top-level view chosen by auth state, then route."
  [{:keys [user route] :as state}]
  (cond
    (nil? user)   [:div.center {:replicant/key :view/loading} [:p "Loading…"]]
    (false? user) (login-view)
    :else
    (case (:name route)
      :route/workspace (workspace-view state (-> route :params :id))
      (dashboard-view state))))
