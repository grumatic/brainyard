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
         :sessions {}}))
