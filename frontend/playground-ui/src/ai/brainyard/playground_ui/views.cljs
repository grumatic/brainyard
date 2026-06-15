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
   [:div.toolbar
    [:button.btn.primary {:on {:click [[:session/create]]}} "New workspace"]
    [:button {:on {:click [[:nav/settings]]}} "Settings"]]
   (if (seq sessions)
     [:ul.sessions (map session-row (vals sessions))]
     [:p.empty "No workspaces yet — create one to start."])])

(defn settings-view [{:keys [user] {:keys [rows status suggested]} :settings}]
  [:div.settings {:replicant/key :view/settings}
   [:header
    [:button {:on {:click [[:nav/dashboard]]}} "← Workspaces"]
    [:h1 "Settings"]
    [:div.spacer]
    [:span.user (:email user)]]
   [:section
    [:h2 "Workspace environment"]
    [:p.hint "Variables injected into your workspaces — e.g. provider keys like "
     [:code "OPENAI_API_KEY"] " or " [:code "ANTHROPIC_API_KEY"]
     ". Applied to newly created or resumed workspaces."]
    ;; Suggestions for the name field; the input stays free-text (type anything).
    [:datalist {:id "env-names"}
     (for [n suggested] [:option {:replicant/key n :value n}])]
    [:table.env
     [:tbody
      (map-indexed
       (fn [i [k v]]
         [:tr {:replicant/key i}
          [:td [:input.k {:placeholder "NAME" :value k :list "env-names"
                          :on {:input [[:settings/set-row i 0 :event/target.value]]}}]]
          [:td [:input.v {:placeholder "value" :value v
                          :on {:input [[:settings/set-row i 1 :event/target.value]]}}]]
          [:td [:button.danger {:on {:click [[:settings/remove-row i]]}} "✕"]]])
       rows)]]
    [:div.toolbar
     [:button {:on {:click [[:settings/add-row]]}} "+ Add variable"]
     [:button.btn.primary {:on {:click [[:settings/save]]}} "Save"]
     (case status
       :saving [:span.hint "Saving…"]
       :saved  [:span.hint.ok "Saved ✓"]
       :error  [:span.hint.err "Save failed"]
       nil)]]])

(defn port-menu
  "Dev-port dropdown for the workspace header: selecting a container port opens
   its mapped host address (http://127.0.0.1:<host>) in a new tab. `ports` is
   [{:container n :host n} ...] loaded on workspace nav."
  [ports]
  [:select.ports {:title "Open a workspace dev port in a new tab"
                  :on {:change [[:ports/open :event/target.value]]}}
   [:option {:value ""} (if (seq ports) "Ports ▾" "No ports")]
   (for [{:keys [container host]} ports]
     [:option {:replicant/key container :value host}
      (str ":" container " → 127.0.0.1:" host)])])

(defn workspace-view [{:keys [ports]} id]
  [:div.workspace {:replicant/key :view/workspace}
   [:header
    [:button {:on {:click [[:nav/dashboard]]}} "← Workspaces"]
    [:span.id id]
    [:div.spacer]
    (port-menu (get ports id))]
   ;; ttyd's own client, proxied same-origin. Stable :replicant/key so the iframe
   ;; (and its live ttyd session) survives header re-renders.
   ;; `allow` grants clipboard access so the proxy-injected copy-on-select
   ;; (Shift-drag to select past the TUI's mouse mode) can write to the clipboard.
   [:iframe.term {:replicant/key (str "term-" id)
                  :allow "clipboard-read; clipboard-write"
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
      :route/settings  (settings-view state)
      (dashboard-view state))))
