;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
(ns ai.brainyard.playground-ui.state
  "The single app-state atom. Everything the UI renders is derived from this;
   `core` re-renders on every change. Keep it plain data — no functions, no JS
   objects. The terminal is an iframe of ttyd's own client, so it holds no
   connection state here.")

(defonce app-state
  (atom {;; :user — nil = still checking /api/me, false = logged out, map = logged in
         :user     nil
         ;; :route — {:name :route/dashboard|:route/workspace, :params {...}}
         :route    nil
         ;; :sessions — id -> session map {:id :status :created-at}
         :sessions {}
         ;; :ports — session-id -> [{:container n :host n} ...] for the
         ;; workspace header's dev-port dropdown (loaded on workspace nav)
         :ports {}
         ;; :provision — id -> {:status "provisioning"|"failed" :elapsed ms :nav? bool}
         ;; in-flight workspace startup, driving the progress-bar modal. Entries
         ;; are added on create/resume and removed once the workspace is ready.
         :provision {}
         ;; :brainyard — workspace-id -> config-view state:
         ;;   {:open? bool                 modal open
         ;;    :status :loading|:error|nil session-list fetch state
         ;;    :sessions [{:session-id :project-dir :model :agent} …]
         ;;    :selected <brainyard-session-id>
         ;;    :config { <sid> -> config-payload | {:loading? true} }
         ;;    :show-snapshot? bool        reveal the full effective config}
         :brainyard {}}))
