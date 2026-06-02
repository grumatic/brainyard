;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.clj-nrepl.core.grant
  "In-memory grant store for crossing the sandbox→live-runtime boundary.

   A grant is {:scope :read-only|:mutate, :expires-at <ms>, :reason str,
   :issued-at <ms>}. Grants are non-persistent — they die with the
   process. Phase 1 only honors :read-only; :mutate is reserved for
   Phase 2 (see docs/design/clj-nrepl-eval.md §10 / §8.2)."
  (:require [ai.brainyard.mulog.interface :as mulog]
            [clojure.string :as str]))

(defonce ^:private !grant (atom nil))

(def ^:const default-ttl-ms (* 15 60 1000))

(defn- now-ms [] (System/currentTimeMillis))

(defn grant!
  "Issue a grant. `scope` is :read-only (Phase 1) or :mutate (Phase 2).
   `ttl-ms` defaults to 15 minutes. `reason` is recorded for audit."
  [& {:keys [scope ttl-ms reason]
      :or {scope :read-only ttl-ms default-ttl-ms reason "manual"}}]
  (let [g {:scope scope
           :expires-at (+ (now-ms) ttl-ms)
           :reason reason
           :issued-at (now-ms)}]
    (reset! !grant g)
    (mulog/info ::grant-issued :scope scope :ttl-ms ttl-ms :reason reason)
    g))

(defn revoke! []
  (when @!grant
    (mulog/info ::grant-revoked :scope (:scope @!grant)))
  (reset! !grant nil))

(defn active?
  "True when a grant exists and hasn't expired. Self-prunes expired grants."
  []
  (when-let [g @!grant]
    (if (> (:expires-at g) (now-ms))
      true
      (do (reset! !grant nil) false))))

(defn scope
  "Current grant scope or nil."
  []
  (when (active?) (:scope @!grant)))

(defn current
  "Return the active grant map or nil."
  []
  (when (active?) @!grant))

;; ============================================================================
;; Env-var bootstrap
;;
;; BRAINYARD_NREPL_GRANT=read-only:15m  →  read-only grant, 15 min TTL
;; BRAINYARD_NREPL_GRANT=read-only      →  read-only grant, default TTL
;; BRAINYARD_NREPL_GRANT=mutate:5m      →  mutate grant (Phase 2)
;; ============================================================================

(def ^:private duration-re #"^(\d+)([smh])$")

(defn- parse-duration-ms [s]
  (when-let [[_ n unit] (re-matches duration-re (str s))]
    (let [n (parse-long n)]
      (case unit
        "s" (* n 1000)
        "m" (* n 60 1000)
        "h" (* n 60 60 1000)))))

(defn maybe-grant-from-env!
  "Read BRAINYARD_NREPL_GRANT and issue a grant when present.
   Format: <scope>[:<ttl>] e.g. 'read-only:15m' or 'read-only'.
   Returns the grant map (or nil when no env var / unrecognized scope)."
  ([] (maybe-grant-from-env! (System/getenv "BRAINYARD_NREPL_GRANT")))
  ([raw]
   (when (and raw (not (str/blank? raw)))
     (let [[scope-s ttl-s] (str/split raw #":" 2)
           scope (keyword scope-s)
           ttl-ms (or (parse-duration-ms ttl-s) default-ttl-ms)]
       (if (#{:read-only :mutate} scope)
         (grant! :scope scope :ttl-ms ttl-ms
                 :reason "env BRAINYARD_NREPL_GRANT")
         (mulog/warn ::env-grant-rejected
                     :raw raw
                     :reason "unknown scope"))))))
