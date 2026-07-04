;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.mulog.interface
  "Public interface for μ/log event-based logging.
   Resilient to :reload-all — log/trace silently degrade if mulog protocols break.

   Provides structured event logging with support for:
   - Event logging (log, trace)
   - Context management (global and local)
   - Built-in publishers (console, file, elasticsearch, etc.)
   - Custom publisher creation
   - Integrant lifecycle management

   Usage:
   ```clojure
   ;; Basic logging
   (mulog/log ::user-login :user-id \"123\" :method :oauth)

   ;; With timing
   (mulog/trace ::api-call [:endpoint \"/users\"]
     (http/get \"https://api.example.com/users\"))

   ;; Configure via Integrant
   {:ai.brainyard.mulog.core.publisher/publishers
    {:publishers {:console {:type :console :pretty? true}}
     :global-context {:app-name \"my-app\" :env \"prod\"}}}
   ```

   Integrant keys:
   - :ai.brainyard.mulog.core.publisher/publishers"
  (:refer-clojure :exclude [reset!])
  (:require [com.brunobonacci.mulog :as mu]
            [ai.brainyard.mulog.core.publisher :as publisher]
            [ai.brainyard.mulog.core.context :as context]
            [ai.brainyard.mulog.core.custom :as custom]
            [ai.brainyard.mulog.core.slf4j-bridge :as slf4j-bridge]))

;; ============================================================================
;; Core Logging API
;; ============================================================================

(defmacro log
  "Log an event with key-value pairs.
   Resilient to :reload-all — protocol failures are silently dropped.

   Args:
   - event-name: Namespaced keyword identifying the event
   - key-values: Pairs of keys and values

   Example:
   (log ::order-placed :order-id \"123\" :total 99.99)"
  [event-name & key-values]
  `(try
     (mu/log ~event-name ~@key-values)
     (catch Exception _#)))

(defmacro trace
  "Trace an operation, capturing timing and outcome.
   Resilient to :reload-all — body runs exactly once and log failures
   are caught separately so the body result is never lost.

   Args:
   - event-name: Namespaced keyword
   - pairs: Vector of key-value pairs (evaluated before body)
   - body: Code to execute and time

   Captures:
   - :mulog/duration (nanoseconds)
   - :mulog/outcome (:ok or :error)
   - :exception (on error)

   Example:
   (trace ::db-query [:table :users :limit 100]
     (db/query \"SELECT * FROM users LIMIT 100\"))"
  [event-name pairs & body]
  `(let [start-ns# (System/nanoTime)]
     (try
       (let [result# (do ~@body)]
         (try
           (mu/log ~event-name ~@pairs
                   :mulog/duration (- (System/nanoTime) start-ns#)
                   :mulog/outcome :ok)
           (catch Exception _#))
         result#)
       (catch Exception body-ex#
         (try
           (mu/log ~event-name ~@pairs
                   :mulog/duration (- (System/nanoTime) start-ns#)
                   :mulog/outcome :error
                   :exception body-ex#)
           (catch Exception _#))
         (throw body-ex#)))))

;; ============================================================================
;; Level-based Convenience Macros
;; ============================================================================

(defmacro debug
  "Log a debug-level event. Auto-injects :level :debug.
   Example: (debug ::cache-hit :key \"user-123\")"
  [event-name & key-values]
  `(log ~event-name :level :debug ~@key-values))

(defmacro info
  "Log an info-level event. Auto-injects :level :info.
   Example: (info ::agent-started :agent-id \"a1\")"
  [event-name & key-values]
  `(log ~event-name :level :info ~@key-values))

(defmacro warn
  "Log a warn-level event. Auto-injects :level :warn.
   Example: (warn ::deprecated-usage :fn \"old-fn\")"
  [event-name & key-values]
  `(log ~event-name :level :warn ~@key-values))

(defmacro error
  "Log an error-level event. Auto-injects :level :error.
   Example: (error ::request-failed :url url :exception e)"
  [event-name & key-values]
  `(log ~event-name :level :error ~@key-values))

;; ============================================================================
;; SLF4J Bridge
;; ============================================================================

(def setup-slf4j-bridge!
  "Set up SLF4J-to-mulog bridge. Routes Java library SLF4J events into mulog.
   Replaces logback appenders with a mulog-forwarding appender.
   Default captures WARN+ level. Pass {:level Level/INFO} for more."
  slf4j-bridge/setup!)

(def stop-slf4j-bridge!
  "Stop the SLF4J-to-mulog bridge."
  slf4j-bridge/stop!)

;; ============================================================================
;; Context Management
;; ============================================================================

(def set-global-context!
  "Set global context applied to all events.

   Args: context-map with common metadata

   Example:
   (set-global-context! {:app-name \"my-app\" :version \"1.0\" :env \"prod\"})"
  context/set-global-context!)

(def update-global-context!
  "Merge additional key-values into global context."
  context/update-global-context!)

(defmacro with-context
  "Execute body with additional local context.

   Example:
   (with-context {:request-id \"abc123\"}
     (log ::processing-request)
     (process-request))"
  [context-map & body]
  `(context/with-context ~context-map ~@body))

(def app-context
  "Create a standard application context map.

   Args: {:app-name \"app\" :version \"1.0\" :env \"prod\"}
   Returns: Map with :app-name :version :env :host :pid"
  context/app-context)

;; ============================================================================
;; Publisher Management
;; ============================================================================

(def start-publisher!
  "Start a single publisher with configuration.

   Built-in publisher types:
   - :console - {:type :console :pretty? true}
   - :simple-file - {:type :simple-file :filename \"app.log\"}
   - :elasticsearch - {:type :elasticsearch :url \"http://localhost:9200\"}
   - :kafka - {:type :kafka :bootstrap.servers \"localhost:9092\"}
   - :cloudwatch - {:type :cloudwatch :group-name \"my-app\"}
   - :slack - {:type :slack :webhook-url \"...\"}
   - :zipkin - {:type :zipkin :url \"http://localhost:9411\"}

   Returns: Publisher handle (call to stop)"
  publisher/start-publisher!)

(def stop-publisher!
  "Stop a publisher using its handle."
  publisher/stop-publisher!)

(def start-publishers!
  "Start multiple publishers from a config map.

   Args: {:id1 {:type :console} :id2 {:type :file ...}}
   Returns: Map of id -> handle"
  publisher/start-publishers!)

(def stop-publishers!
  "Stop all publishers in a handles map."
  publisher/stop-publishers!)

(def start-default-publisher!
  "Start console publisher and REPL publisher (when in nREPL session).
   Call this at app startup if you want the default console+REPL logging.
   Respects MULOG_DEFAULT_PUBLISHER=none env var to disable."
  publisher/start-default-publisher!)

(def stop-default-publisher!
  "Stop the default console and REPL publishers. Idempotent."
  publisher/stop-default-publisher!)

(def set-repl-output!
  "Capture current *out* as the REPL output target for mulog.
   Auto-called when mulog is loaded from an nREPL session.
   Call manually to re-bind to a new REPL session."
  publisher/set-repl-output!)

(def reset!
  "Reset mulog after :reload-all breaks protocols.
   Stops publishers and clears internal state.
   Call start-default-publisher! or start-publishers! after to restore logging."
  publisher/reset!)

;; ============================================================================
;; Custom Publishers
;; ============================================================================

(def agent-buffer
  "Create an agent-buffer for custom publishers.

   Args: optional capacity (default 10000)"
  custom/agent-buffer)

(def buffer-items
  "Get items from a ring buffer."
  custom/items)

(def buffer-clear
  "Clear all items from a buffer."
  custom/clear)

(def make-fn-publisher
  "Create a PPublisher that calls (f event-map) for each event.
   Use with start-publisher! to route events to custom sinks.

   Example:
   (start-publisher!
     {:type :inline
      :publisher (make-fn-publisher (fn [event] (my-emit! (format-event event))))})"
  publisher/make-fn-publisher)

(def simple-publisher
  "Create a simple inline publisher from a function.

   Args:
   - publish-fn: (fn [events] ...) receives seq of event maps
   - opts: {:delay 500}

   Example:
   (start-publisher!
     (simple-publisher (fn [events] (doseq [e events] (println e)))))"
  custom/simple-publisher)

(def add-human-timestamp
  "Add a human-readable :mulog/timestamp-str from :mulog/timestamp (epoch millis).
   Useful for making log events readable before writing to files."
  publisher/add-human-timestamp)

(def make-pretty-file-publisher
  "Create a PPublisher that writes pretty-printed events to a file.
   Events are separated by blank lines with human-readable timestamps.

   Args:
   - filename: Path to the log file (appends)
   - :buffer-size - Ring buffer capacity (default 10000)
   - :delay - Publish delay in ms (default 500)

   Example:
   (start-publisher!
     {:type :inline
      :publisher (make-pretty-file-publisher \"/tmp/app.log\")})"
  publisher/make-pretty-file-publisher)

(def pretty-event-str
  "Format one mulog event as a native-safe pretty string (trailing newline;
   human timestamp, ANSI stripped). Use for custom per-event file sinks instead
   of `clojure.pprint`, which throws under native-image."
  publisher/pretty-event-str)

(def make-rotating-pretty-file-publisher
  "Like `make-pretty-file-publisher` but rotates the file at `:max-bytes`
   (default 50 MiB), keeping `:max-rotations` backups (default 3;
   `<file>.1` … `.N`, oldest dropped). Same native-safe formatting path.

   Args: filename, plus :buffer-size, :delay, :max-bytes, :max-rotations."
  publisher/make-rotating-pretty-file-publisher)
