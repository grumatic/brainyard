;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
(ns ai.brainyard.playground-ui.views
  "Pure render: app-state -> hiccup. No side effects, no DOM access. Event
   handlers are DATA (`:on {:click [[:action ...]]}`) interpreted by `dispatch`.
   The terminal is ttyd's own self-contained client, embedded same-origin via an
   iframe (`/api/sessions/:id/term/`) — so the TUI renders exactly as ttyd
   intends, with no hand-rolled xterm client to maintain."
  (:require [clojure.string :as str]
            [ai.brainyard.playground-ui.graph :as graph]))

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

(defn- secret-name?
  "Heuristic: a variable whose name mentions a key/secret/token/password holds a
   credential, so its value is masked (type=password) by default with a reveal
   toggle. Plain vars (AWS_REGION, *_BASE_URL, …) stay visible."
  [k]
  ;; lower-case + flagless regex: JS RegExp has no inline (?i), so normalise.
  (boolean (re-find #"key|secret|token|pass|credential" (str/lower-case (str k)))))

(defn settings-view [{:keys [user] {:keys [rows status suggested reveal]} :settings}]
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
         (let [secret? (secret-name? k)
               shown?  (contains? (set reveal) i)]
           [:tr {:replicant/key i}
            ;; Suppress the browser's autofill / password-manager popup (it would
            ;; treat NAME as a "username" and overlay the datalist arrow). The
            ;; `:list` datalist suggestions are unaffected by autocomplete=off.
            [:td [:input.k {:placeholder "NAME" :value k :list "env-names"
                            :autocomplete "off" :autocorrect "off"
                            :autocapitalize "off" :spellcheck false
                            :data-1p-ignore "true" :data-lpignore "true"
                            :on {:input [[:settings/set-row i 0 :event/target.value]]}}]]
            [:td.v-cell
             [:input.v {:placeholder "value" :value v
                        ;; Mask credential values; toggled to text by the eye.
                        :type (if (and secret? (not shown?)) "password" "text")
                        ;; new-password is the value Chrome honors to stop the
                        ;; saved-password dropdown over a type=password field.
                        :autocomplete (if secret? "new-password" "off")
                        :autocorrect "off" :autocapitalize "off" :spellcheck false
                        :data-1p-ignore "true" :data-lpignore "true"
                        :data-form-type "other"
                        :on {:input [[:settings/set-row i 1 :event/target.value]]}}]
             (when secret?
               [:button.reveal {:type "button"
                                :title (if shown? "Hide value" "Show value")
                                :aria-label (if shown? "Hide value" "Show value")
                                :on {:click [[:settings/toggle-reveal i]]}}
                (if shown? "🙈" "👁")])]
            [:td [:button.danger {:on {:click [[:settings/remove-row i]]}} "✕"]]]))
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

(defn- config-kv-table
  "Render a config map (keyword keys) as a two-column key/value table. Values are
   `pr-str`'d so nested maps/vectors (and redacted secrets) show verbatim."
  [m]
  [:table.env.config-kv
   [:tbody
    (for [[k v] (sort-by (comp str first) m)]
      [:tr {:replicant/key (str k)}
       [:td [:code (if (keyword? k) (name k) (str k))]]
       [:td [:code (pr-str v)]]])]])

(defn- config-detail
  "The selected brainyard session's effective config: header line, overrides
   table, and a toggle to reveal the full effective snapshot."
  [id show-snapshot? cfg]
  (cond
    (nil? cfg)              [:p.hint "Select a session to view its configuration."]
    (:loading? cfg)         [:p.hint "Reading configuration…"]
    (false? (:success cfg)) [:p.hint.err (str "Error: " (:error cfg))]
    :else
    (let [{:keys [agent provider model total overrides snapshot]} cfg]
      [:div.config-detail-body
       [:p.config-head
        (when agent    [:span "agent: " [:code agent] "  "])
        (when provider [:span "provider: " [:code provider] "  "])
        (when model    [:span "model: " [:code model]])]
       [:h3 (str "Overrides — " (count overrides) " of " total " keys differ")]
       (if (seq overrides)
         (config-kv-table overrides)
         [:p.hint "Every key is at its default."])
       [:div.toolbar
        [:button {:on {:click [[:brainyard/toggle-snapshot id]]}}
         (if show-snapshot? "Hide full config" "Show full config")]]
       (when show-snapshot?
         [:div.config-snapshot
          [:h3 "Full effective config"]
          (config-kv-table snapshot)])])))

(defn brainyard-config-modal
  "Read-only per-session configuration overlay for a workspace. A session picker
   (one entry per live brainyard session / project dir) drives a config detail
   pane. Data is read over each session's ask channel; secrets are pre-redacted
   server-side."
  [id {:keys [open? status sessions selected config show-snapshot?]}]
  (when open?
    [:div.modal-backdrop {:replicant/key :brainyard-config}
     [:div.modal.config-modal
      [:header
       [:h2 "Session configuration"]
       [:div.spacer]
       [:button {:on {:click [[:brainyard/toggle id]]}} "Close"]]
      (cond
        (= :loading status) [:p.hint "Loading sessions…"]
        (= :error status)   [:p.hint.err "Couldn't reach the workspace container."]
        (empty? sessions)   [:p.hint "No live brainyard sessions in this workspace yet. "
                             "Open the terminal and start one, then reopen this panel."]
        :else
        [:div.config-body
         [:div.config-sessions
          (for [{:keys [session-id project-dir model]} sessions]
            [:button.config-session {:replicant/key session-id
                                     :class (when (= session-id selected) "active")
                                     :on {:click [[:brainyard/select id session-id]]}}
             [:span.id session-id]
             [:span.hint (str project-dir (when model (str " · " model)))]])]
         [:div.config-detail
          (config-detail id show-snapshot? (get config selected))]])]]))

(defn graph-memory-modal
  "Large overlay showing the workspace's context-graph memory as an interactive
   node-link canvas (drag nodes, scroll to zoom, drag background to pan). Whole-DB
   / user-scoped — not tied to a single brainyard session."
  [id {:keys [open?] :as g}]
  (when open?
    [:div.modal-backdrop {:replicant/key :graph-memory}
     [:div.modal.graph-modal
      [:header
       [:h2 "Graph memory"]
       [:div.spacer]
       [:button {:on {:click [[:graph/refresh id]]}} "Refresh"]
       [:button {:on {:click [[:graph/toggle id]]}} "Close"]]
      (graph/panel id g)]]))

;; --- memory DB panel -------------------------------------------------------

(defn- mem-preview [s n]
  (let [c (str s)] (if (> (count c) n) (str (subs c 0 n) "…") c)))

(defn- memory-entry-row [{:keys [id _layer kind content confidence]}]
  [:div.mem-row {:replicant/key (str (or id (hash content)))}
   [:div.mem-meta
    (when _layer [:span.mem-badge (name _layer)])
    (when kind [:span.mem-kind (str kind)])
    (when confidence [:span.mem-conf (str "conf " confidence)])
    (when id [:span.mem-id id])]
   [:div.mem-content (mem-preview content 260)]])

(defn- memory-entries-pane [{:keys [status entries error]}]
  (cond
    (nil? status)       [:p.hint "Select a tab to load."]
    (= :loading status) [:p.hint "Loading…"]
    (= :error status)   [:p.hint.err (str "Error: " error)]
    (empty? entries)    [:p.hint "No entries in this layer."]
    :else               (into [:div.mem-list] (map memory-entry-row entries))))

(defn- memory-status-pane [{:keys [loading? success stats vec-status graph-enabled? error]}]
  (cond
    loading?        [:p.hint "Loading…"]
    (false? success) [:p.hint.err (str "Error: " error)]
    :else
    [:div.mem-status
     [:div [:b "Episodes (L2): "] (:episodes stats)]
     [:div [:b "Semantic facts (L3): "] (:semantic-facts stats)]
     [:div [:b "Schema: "] (:schema-version stats)]
     [:div [:b "Graph tier: "] (if graph-enabled? "on" "off")]
     (when vec-status
       [:div [:b "Vector index: "]
        (if (:stale? vec-status) "STALE — run reembed" "ok")
        " (" (:count vec-status) " vectors)"])]))

(defn- memory-search-pane [id {:keys [q status entries error]}]
  [:div.mem-search
   [:form.mem-search-bar {:on {:submit [[:event/prevent-default] [:memory/search id]]}}
    [:input {:type "text" :placeholder "Recall query (weighted RRF across L1/L2/L3)…"
             :value (or q "")
             :on {:input [[:memory/search-input id :event/target.value]]}}]
    [:button {:type "submit"} "Search"]]
   (cond
     (= :loading status) [:p.hint "Searching…"]
     (= :error status)   [:p.hint.err (str "Error: " error)]
     (nil? status)       [:p.hint "Enter a query and press Search."]
     (empty? entries)    [:p.hint "No results."]
     :else               (into [:div.mem-list] (map memory-entry-row entries)))])

(defn- memory-tabs [id active]
  (into [:div.mem-tabs]
        (for [[tab label] [[:status "Status"] [:l1 "L1"] [:l2 "L2"] [:l3 "L3"] [:search "Search"]]]
          [:button {:replicant/key tab
                    :class (when (= tab active) "active")
                    :on {:click [[:memory/tab id tab]]}}
           label])))

(defn memory-modal
  "Inspect the workspace's user-scoped memory DB: L1/L2/L3 counts, raw layer
   rows, and cross-layer recall search. Whole-DB / user-scoped, read over
   `by memory … --json`."
  [id {:keys [open? tab status lists search]}]
  (when open?
    (let [tab (or tab :status)]
      [:div.modal-backdrop {:replicant/key :memory}
       [:div.modal.memory-modal
        [:header
         [:h2 "Memory DB"]
         [:div.spacer]
         [:button {:on {:click [[:memory/refresh id]]}} "Refresh"]
         [:button {:on {:click [[:memory/toggle id]]}} "Close"]]
        (memory-tabs id tab)
        [:div.memory-body
         (case tab
           :status (memory-status-pane status)
           :search (memory-search-pane id (or search {}))
           (memory-entries-pane (get lists tab)))]]])))

(defn workspace-view [state id]
  [:div.workspace {:replicant/key :view/workspace}
   [:header
    [:button {:on {:click [[:nav/dashboard]]}} "← Workspaces"]
    [:span.id id]
    [:div.spacer]
    [:button {:on {:click [[:brainyard/toggle id]]}} "Config"]
    [:button {:on {:click [[:graph/toggle id]]}} "Graph Memory"]
    [:button {:on {:click [[:memory/toggle id]]}} "Memory"]
    (port-menu (get (:ports state) id))]
   ;; ttyd's own client, proxied same-origin. Stable :replicant/key so the iframe
   ;; (and its live ttyd session) survives header re-renders.
   ;; `allow` grants clipboard access so the proxy-injected copy-on-select
   ;; (Shift-drag to select past the TUI's mouse mode) can write to the clipboard.
   [:iframe.term {:replicant/key (str "term-" id)
                  :allow "clipboard-read; clipboard-write"
                  :src (str "/api/sessions/" id "/term/")}]
   (brainyard-config-modal id (get (:brainyard state) id))
   (graph-memory-modal id (get (:graph state) id))
   (memory-modal id (get (:memory state) id))])

;; ~time the 0→95% ramp targets; real readiness snaps it to ready/dismiss.
(def ^:private provision-expected-ms 15000)

(defn- provision-phase
  "Best-effort phase label off elapsed time (the backend blocks rather than
   streaming events, so this is informative, not authoritative)."
  [elapsed]
  (cond
    (< elapsed 2000)  "Allocating container…"
    (< elapsed 6000)  "Starting the workspace image…"
    (< elapsed 12000) "Booting by (ttyd + tmux)…"
    :else             "Almost ready…"))

(defn provision-modal
  "Progress-bar overlay shown while a workspace container starts. The bar fills
   from elapsed time (capped at 95% until the server reports ready); on failure
   it turns red and offers Retry/Dismiss."
  [id {:keys [status elapsed]}]
  (let [failed? (= status "failed")
        pct     (if failed? 100
                    (min 95 (js/Math.round (* 100 (/ (or elapsed 0) provision-expected-ms)))))]
    [:div.modal-backdrop {:replicant/key :provision-modal}
     [:div.modal
      [:h2 (if failed? "Workspace failed to start" "Preparing your workspace")]
      [:p.id id]
      [:div.progress {:class (when failed? "err")}
       [:div.bar {:style {:width (str pct "%")}}]]
      (if failed?
        [:div
         [:p.hint.err "The container didn't become ready in time — Docker may be "
          "slow, still pulling the image, or low on resources."]
         [:div.toolbar
          [:button {:on {:click [[:provision/dismiss id]]}} "Dismiss"]
          [:button.btn.primary {:on {:click [[:provision/dismiss id] [:session/resume id]]}}
           "Retry"]]]
        [:p.hint (provision-phase elapsed)])]]))

(defn root
  "Top-level view chosen by auth state, then route. A provisioning workspace
   overlays a progress-bar modal on top of the current view."
  [{:keys [user route provision] :as state}]
  (cond
    (nil? user)   [:div.center {:replicant/key :view/loading} [:p "Loading…"]]
    (false? user) (login-view)
    :else
    [:div {:replicant/key :app-root}
     (case (:name route)
       :route/workspace (workspace-view state (-> route :params :id))
       :route/settings  (settings-view state)
       (dashboard-view state))
     (when (seq provision)
       (let [[id p] (first provision)]
         (provision-modal id p)))]))
