;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: Apache-2.0
;; Licensed under the Apache License, Version 2.0 (the "License"); you may
;; not use this file except in compliance with the License. See LICENSE at
;; the repository root and CONTRIBUTING.md for contribution terms.

(ns ai.brainyard.mulog.core.context
  "Context management helpers for μ/log."
  (:require [com.brunobonacci.mulog :as mu]))

(defn set-global-context!
  "Set global context that applies to all events.
   Common keys: :app-name :version :env :host"
  [context-map]
  (mu/set-global-context! context-map))

(defn update-global-context!
  "Update global context with additional key-values."
  [context-map]
  (mu/update-global-context! merge context-map))

(defmacro with-context
  "Execute body with additional local context."
  [context-map & body]
  `(mu/with-context ~context-map ~@body))

(defn app-context
  "Create a standard application context map."
  [{:keys [app-name version env]
    :or {env "development"}}]
  {:app-name app-name
   :version version
   :env env
   :host (.. java.net.InetAddress getLocalHost getHostName)
   :pid (.pid (java.lang.ProcessHandle/current))})
