;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: Apache-2.0
;; Licensed under the Apache License, Version 2.0 (the "License"); you may
;; not use this file except in compliance with the License. See LICENSE at
;; the repository root and CONTRIBUTING.md for contribution terms.

(ns ai.brainyard.mulog.core.publisher
  "Publisher management and Integrant lifecycle for μ/log."
  (:refer-clojure :exclude [reset!])
  (:require [clojure.string :as str]
            [com.brunobonacci.mulog :as mu]
            [com.brunobonacci.mulog.buffer :as rb]
            [com.brunobonacci.mulog.core :as mu-core]
            [com.brunobonacci.mulog.utils :as ut]
            [com.brunobonacci.mulog.publisher :as pub]
            [integrant.core :as ig])
  (:import [java.time Instant LocalDateTime ZoneId]
           [java.time.format DateTimeFormatter]))

;; Publisher registry (atom holding active publishers)
(defonce ^:private !publishers (atom {}))

;; Default publisher atoms (call start-default-publisher! to activate)
(defonce ^:private !default-publisher (atom nil))  ;; console handle
(defonce ^:private !repl-publisher (atom nil))     ;; repl handle
(defonce ^:private !repl-out (atom nil))           ;; captured Writer

(def ^:private ^DateTimeFormatter ts-formatter
  (DateTimeFormatter/ofPattern "yyyy-MM-dd HH:mm:ss.SSS"))

(defn add-human-timestamp
  "Add a human-readable :mulog/timestamp-str from :mulog/timestamp (epoch millis)."
  [event]
  (if-let [ts (:mulog/timestamp event)]
    (assoc event :mulog/timestamp-str
           (.format (LocalDateTime/ofInstant
                     (Instant/ofEpochMilli (long ts))
                     (ZoneId/systemDefault))
                    ts-formatter))
    event))

(defn- strip-ansi
  "Remove ANSI escape sequences from a string."
  [s]
  (str/replace s #"\x1b\[[0-9;]*[a-zA-Z]" ""))

(defn- strip-ansi-deep
  "Recursively strip ANSI codes from all string values in a data structure."
  [x]
  (cond
    (string? x)  (strip-ansi x)
    (map? x)     (persistent! (reduce-kv (fn [m k v] (assoc! m k (strip-ansi-deep v)))
                                         (transient {}) x))
    (vector? x)  (mapv strip-ansi-deep x)
    (seq? x)     (map strip-ansi-deep x)
    :else        x))

(defn make-pretty-file-publisher
  "Create a PPublisher that writes pretty-printed events to a file.
   Events are separated by blank lines for readability.
   Adds :mulog/timestamp-str with human-readable local timestamp."
  [filename & {:keys [buffer-size delay] :or {buffer-size 10000 delay 500}}]
  (let [buf (rb/agent-buffer buffer-size)]
    (reify pub/PPublisher
      (agent-buffer [_] buf)
      (publish-delay [_] delay)
      (publish [_ buffer]
        (let [items (map second (rb/items buffer))]
          (when (seq items)
            (try
              (with-open [w (java.io.FileWriter. ^String filename true)]
                (doseq [item items]
                  (.write w (str (ut/pprint-event-str (add-human-timestamp (strip-ansi-deep item))) \newline))
                  (.flush w)))
              (catch Exception _))))
        (rb/clear buffer)))))

(defn- make-repl-publisher
  "Create a PPublisher that writes pretty-printed events to the Writer in !writer."
  [!writer]
  (let [buf (rb/agent-buffer 10000)]
    (reify pub/PPublisher
      (agent-buffer [_] buf)
      (publish-delay [_] 200)
      (publish [_ buffer]
        (when-let [w @!writer]
          (try
            (doseq [item (map second (rb/items buffer))]
              (.write ^java.io.Writer w (str (ut/pprint-event-str item) \newline))
              (.flush ^java.io.Writer w))
            (catch Exception _
              (clojure.core/reset! !writer nil))))
        (rb/clear buffer)))))

(defn make-fn-publisher
  "Create a PPublisher that calls (f event-map) for each event.
   Useful for routing mulog events to custom sinks (e.g., TUI scroll region).

   Parameters:
     f           - (fn [event-map]) called for each event
     :buffer-size - Ring buffer capacity (default 10000)
     :delay       - Publish delay in ms (default 200)"
  [f & {:keys [buffer-size delay] :or {buffer-size 10000 delay 200}}]
  (let [buf (rb/agent-buffer buffer-size)]
    (reify pub/PPublisher
      (agent-buffer [_] buf)
      (publish-delay [_] delay)
      (publish [_ buffer]
        (doseq [item (map second (rb/items buffer))]
          (try (f item) (catch Exception _)))
        (rb/clear buffer)))))

(defn set-repl-output!
  "Capture current *out* as the REPL output target for mulog events.
   Starts the REPL publisher if not already running. Call from your nREPL session."
  []
  (clojure.core/reset! !repl-out *out*)
  (when-not @!repl-publisher
    (let [handle (mu/start-publisher!
                  {:type :inline
                   :publisher (make-repl-publisher !repl-out)})]
      (clojure.core/reset! !repl-publisher handle)))
  :ok)

(defn start-default-publisher!
  "Start console publisher and REPL publisher (when in nREPL session).
   Not called automatically — apps must opt-in by calling this at startup.
   Respects MULOG_DEFAULT_PUBLISHER=none env var to disable."
  []
  (when-not (= "none" (System/getenv "MULOG_DEFAULT_PUBLISHER"))
    (let [handle (mu/start-publisher! {:type :console :pretty? true})]
      (clojure.core/reset! !default-publisher handle))
    ;; If loaded from nREPL session, *out* is thread-bound to session writer
    (when (thread-bound? #'*out*)
      (set-repl-output!))))

(defn stop-default-publisher!
  "Stop all auto-started default publishers. Idempotent."
  []
  (when-let [handle @!default-publisher]
    (handle)
    (clojure.core/reset! !default-publisher nil))
  (when-let [handle @!repl-publisher]
    (handle)
    (clojure.core/reset! !repl-publisher nil))
  (clojure.core/reset! !repl-out nil))

(defn reset!
  "Reset mulog after :reload-all breaks protocols.
   Best-effort stops publishers, resets mulog internal state, clears all local state.
   Does NOT restart any publishers — call start-default-publisher! or start-publishers! after."
  []
  ;; 1. Best-effort stop (handles may be stale after :reload-all)
  (try (when-let [h @!default-publisher] (h)) (catch Throwable _))
  (try (when-let [h @!repl-publisher] (h)) (catch Throwable _))
  (doseq [[_ h] @!publishers]
    (try (h) (catch Throwable _)))
  ;; 2. Reset mulog internal state (public atoms in com.brunobonacci.mulog.core)
  (clojure.core/reset! mu-core/publishers {})
  (clojure.core/reset! mu-core/*default-logger* (rb/ring-buffer 1000))
  ;; 3. Clear local state
  (clojure.core/reset! !default-publisher nil)
  (clojure.core/reset! !repl-publisher nil)
  (clojure.core/reset! !repl-out nil)
  (clojure.core/reset! !publishers {}))

(defn start-publisher!
  "Start a publisher with the given configuration.
   Returns a publisher handle that can be used to stop it."
  [config]
  (mu/start-publisher! config))

(defn stop-publisher!
  "Stop a publisher using its handle."
  [publisher]
  (publisher))  ;; Publisher handles are functions that stop when called

(defn start-publishers!
  "Start multiple publishers from a configuration map.
   Config format: {:publisher-id {:type :console :pretty? true} ...}
   Returns map of publisher-id -> publisher-handle."
  [publishers-config]
  (reduce-kv
   (fn [acc id config]
     (assoc acc id (start-publisher! config)))
   {}
   publishers-config))

(defn stop-publishers!
  "Stop all publishers in the handles map."
  [publisher-handles]
  (doseq [[_id handle] publisher-handles]
    (stop-publisher! handle)))

;; Integrant lifecycle
(defmethod ig/init-key ::publishers [_ {:keys [publishers global-context]}]
  (stop-default-publisher!)
  (when global-context
    (mu/set-global-context! global-context))
  (let [handles (start-publishers! publishers)]
    (clojure.core/reset! !publishers handles)
    handles))

(defmethod ig/halt-key! ::publishers [_ handles]
  (stop-publishers! handles)
  (clojure.core/reset! !publishers {}))

(defmethod ig/suspend-key! ::publishers [_ _handles]
  ;; Keep publishers running during suspend
  nil)

(defmethod ig/resume-key ::publishers [key opts old-opts old-handles]
  (if (= opts old-opts)
    old-handles
    (do
      (ig/halt-key! key old-handles)
      (ig/init-key key opts))))
