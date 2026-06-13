;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
(ns ai.brainyard.playground-ui.auth
  "Client side of the OIDC flow. The SPA never holds a token — the session is an
   httpOnly cookie set by playground-auth — so login/logout are full-page
   redirects into the server-driven flow, and we only ever ask \"who am I?\"."
  (:require [ai.brainyard.playground-ui.api :as api]
            [ai.brainyard.playground-ui.state :as state]))

(defn login! []
  (set! (.. js/window -location -href) "/auth/login"))

(defn logout! []
  (set! (.. js/window -location -href) "/auth/logout"))

(defn refresh-session!
  "Resolve current identity into app-state: a user map, or false when logged out."
  []
  (-> (api/me)
      (.then  (fn [user] (swap! state/app-state assoc :user user)))
      (.catch (fn [_]    (swap! state/app-state assoc :user false)))))
