;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: Apache-2.0
;; Licensed under the Apache License, Version 2.0 (the "License"); you may
;; not use this file except in compliance with the License. See LICENSE at
;; the repository root and CONTRIBUTING.md for contribution terms.

(ns ai.brainyard.clj-nrepl.core.drift
  "Runtime-drift tracking — every successful mutating eval is recorded
   so the operator (and the LLM) can see the live image has diverged
   from source.

   Design (§7.3, Principle 7): a hot-patch in the running image is
   ephemeral and dies with the process. Until promoted to a real
   source change via update-agent, the runtime is drifted. Nothing
   silent — the marker is queryable from anywhere and surfaced via
   mulog on every event."
  (:require [ai.brainyard.mulog.interface :as mulog]))

(def ^:const ^:private code-preview-chars 80)

;; Vector of {:timestamp ms :session str :code-preview str :reason kw}
(defonce ^:private !markers (atom []))

(defn- preview [code]
  (let [s (str code)]
    (subs s 0 (min code-preview-chars (count s)))))

(defn mark!
  "Record a runtime mutation. Called by the client whenever a mutating
   eval REACHES the live runtime — even when a later form in the same
   block surfaced an error, because Clojure's sequential top-level
   evaluation means earlier defs may have already executed. `reason`
   is a keyword for telemetry (defaults to :mutating-eval)."
  ([session code] (mark! session code :mutating-eval))
  ([session code reason]
   (let [m {:timestamp     (System/currentTimeMillis)
            :session       session
            :code-preview  (preview code)
            :reason        reason}]
     (swap! !markers conj m)
     (mulog/warn ::runtime-drift
                 :session session
                 :reason  reason
                 :code-preview (:code-preview m)
                 :total-markers (count @!markers))
     m)))

(defn markers
  "Return the vector of drift markers in arrival order."
  []
  @!markers)

(defn drifted?
  "True when the runtime has at least one recorded drift."
  []
  (boolean (seq @!markers)))

(defn marker-count [] (count @!markers))

(defn clear!
  "Reset drift state. Reserved for the operator-driven \"runtime
   reconciled with source\" path (e.g. after a rebuild + restart of
   the process — and for tests)."
  []
  (let [prior (count @!markers)]
    (reset! !markers [])
    (mulog/info ::runtime-drift-cleared :prior-markers prior)
    prior))
