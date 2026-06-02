;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.memory.core.capture.sidecar
  "Side-car worker for the memory capture pipeline.

  A single dedicated `core.async/thread` per `MemoryManager` consumes
  events from the dispatcher's two channels, parses them into entries
  (`parser/parse`), and writes them to L2 via the unified store. The
  agent loop never blocks on capture.

  Channel discipline:
    1. The critical channel is drained first (priority via `alts!!`).
    2. The events channel is drained next.
    3. When both close, the worker exits.

  Errors in S1 / write are logged and swallowed — capture must never
  surface its own faults back to the agent loop."
  (:require [clojure.core.async :as async]
            [ai.brainyard.mulog.interface :as mulog]
            [ai.brainyard.memory.core.capture.parser :as parser]
            [ai.brainyard.memory.interface.protocol :as proto]))

;; =====================================================
;; Worker
;; =====================================================

(defn- handle-event!
  [store event]
  (try
    (let [entry (parser/parse event)]
      (proto/write-entry store :l2 entry)
      (mulog/debug ::capture-write
                   :event-key (:event-key event)
                   :session-id (:session-id event)))
    (catch Exception e
      (mulog/warn ::capture-handle-failed
                  :event-key (:event-key event)
                  :exception e))))

(defn- run-loop!
  [store critical-ch events-ch !running]
  (try
    (loop []
      ;; Critical events take priority. We poll critical first; if empty
      ;; and not closed, fall through to alts!! over both channels so we
      ;; don't busy-spin.
      (when @!running
        (let [[ev port] (async/alts!! [critical-ch events-ch] :priority true)]
          (cond
            (nil? ev)
            ;; A channel closed. If both are closed, exit.
            (do
              (when (= port critical-ch)
                (mulog/debug ::critical-ch-closed))
              (when (= port events-ch)
                (mulog/debug ::events-ch-closed))
              (recur))
            :else
            (do (handle-event! store ev)
                (recur))))))
    (catch Throwable t
      (mulog/error ::capture-sidecar-crashed :exception t))))

;; =====================================================
;; Sidecar record
;; =====================================================

(defrecord Sidecar [store dispatcher thread !running])

(defn start!
  "Spawn the sidecar thread bound to `dispatcher`'s channels and writing
  to `store`. Returns a `Sidecar` record."
  [store dispatcher]
  (let [[crit ev] ((requiring-resolve
                    'ai.brainyard.memory.core.capture.dispatcher/channels)
                   dispatcher)
        !running  (atom true)
        t         (async/thread (run-loop! store crit ev !running))]
    (mulog/info ::capture-sidecar-started)
    (->Sidecar store dispatcher t !running)))

(defn stop!
  "Signal the sidecar to exit and wait for it to finish. Idempotent.
  Note: the dispatcher's `stop!` should be called separately to close
  the channels; the sidecar exits naturally once both channels close."
  [^Sidecar s]
  (when (and s @(:!running s))
    (reset! (:!running s) false)
    ;; Closing the dispatcher's channels (separately) lets the alts!!
    ;; unblock and the loop exit.
    (mulog/info ::capture-sidecar-stopping)
    nil))

(defn await-drain!
  "Wait up to `timeout-ms` for the sidecar thread to finish processing
  any in-flight events and exit. Useful in tests after `dispatcher/stop!`."
  [^Sidecar s timeout-ms]
  (when s
    (let [[v _] (async/alts!! [(:thread s) (async/timeout timeout-ms)])]
      v)))
