;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

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

(defn pretty-event-str
  "Format one mulog event as a native-safe pretty string with a trailing
   newline — human timestamp added, ANSI stripped, via mulog's own
   `pprint-event-str` (NOT `clojure.pprint`, which throws under native-image).
   The single-event counterpart to what the file publishers write per line."
  [event]
  (str (ut/pprint-event-str (add-human-timestamp (strip-ansi-deep event))) \newline))

(defn- rotate-log-file!
  "Rotate `filename` → `filename.1`, `.1` → `.2`, …, dropping anything past
   `max-rotations`. Best-effort — a failed rename just means the live file keeps
   growing this once rather than losing events."
  [^String filename max-rotations]
  (try
    (let [oldest (java.io.File. (str filename "." max-rotations))]
      (when (.exists oldest) (.delete oldest)))
    (doseq [n (range (dec max-rotations) 0 -1)]
      (let [src (java.io.File. (str filename "." n))]
        (when (.exists src)
          (.renameTo src (java.io.File. (str filename "." (inc n)))))))
    (let [live (java.io.File. filename)]
      (when (.exists live)
        (.renameTo live (java.io.File. (str filename ".1")))))
    (catch Exception _)))

(defn make-rotating-pretty-file-publisher
  "Like `make-pretty-file-publisher`, but rotates `filename` → .1 … .N once the
   live file reaches `:max-bytes` (checked per publish batch, so it may overshoot
   by at most one batch), keeping `:max-rotations` backups. Same native-safe
   formatting path — deliberately a direct PPublisher reify (NOT a
   `make-fn-publisher` + `clojure.pprint` combo, which throws under native-image
   and would silently drop every event)."
  [filename & {:keys [buffer-size delay max-bytes max-rotations]
               :or {buffer-size 10000 delay 500
                    max-bytes (* 50 1024 1024) max-rotations 3}}]
  (let [buf (rb/agent-buffer buffer-size)]
    (reify pub/PPublisher
      (agent-buffer [_] buf)
      (publish-delay [_] delay)
      (publish [_ buffer]
        (let [items (map second (rb/items buffer))]
          (when (seq items)
            (try
              (let [f (java.io.File. ^String filename)]
                (when (and (.exists f) (>= (.length f) (long max-bytes)))
                  (rotate-log-file! filename max-rotations)))
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
