;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
(ns ai.brainyard.playground-ui.state
  "The single app-state atom. Everything the UI renders is derived from this;
   `core` re-renders on every change. Keep it plain data — no functions, no JS
   objects (those live behind life-cycle hooks in `terminal`).")

(defonce app-state
  (atom {;; :user — nil = still checking /api/me, false = logged out, map = logged in
         :user     nil
         ;; :route — {:name :route/dashboard|:route/workspace, :params {...}}
         :route    nil
         ;; :sessions — id -> session map {:id :status :last-active-at ...}
         :sessions {}
         ;; :conn — session id -> :idle|:connecting|:open|:closed|:error
         :conn     {}}))
