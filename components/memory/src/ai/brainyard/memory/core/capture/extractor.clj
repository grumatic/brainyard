;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.memory.core.capture.extractor
  "Async graph-extraction sidecar (CR-MEM-22).

  A dedicated `core.async/thread` consumes L2 episodes forwarded by the
  capture sidecar (`sidecar/:on-write`), runs LLM entity+relationship
  extraction, and populates `graph_nodes`/`graph_edges`. It never blocks
  the agent loop: enqueue is non-blocking and the channel is a
  sliding-buffer, so under back-pressure the OLDEST pending episodes are
  dropped (extraction is best-effort, the FTS store remains authoritative).
  Errors are logged and swallowed.

  Off by default — started only when a `extract-fn` is configured (i.e.
  `:enable-graph-memory` + an embedding/LLM provider)."
  (:require [clojure.core.async :as async]
            [ai.brainyard.memory.core.extract :as extract]
            [ai.brainyard.mulog.interface :as mulog]))

;; Episodes shorter than this are almost never worth an LLM round-trip.
(def ^:private default-min-content 40)

(defrecord Extractor [store extract-fn ch thread !running])

(defn enqueue!
  "Offer an L2 entry for extraction. Non-blocking; a full channel drops the
  oldest (sliding buffer). No-op when the extractor is stopped."
  [^Extractor ex entry]
  (when (and ex entry @(:!running ex))
    (async/offer! (:ch ex) entry)))

(defn- process!
  [store extract-fn entry]
  (try
    (let [content (:content entry)]
      (when (and (string? content) (>= (count content) default-min-content))
        (when-let [result (extract-fn content)]
          (extract/process-extraction! store result (:id entry)))))
    (catch Exception e
      (mulog/warn ::graph-extract-failed :entry-id (:id entry) :error (ex-message e)))))

(defn- run-loop!
  [store extract-fn ch !running]
  (try
    (loop []
      (when @!running
        (when-let [entry (async/<!! ch)]
          (process! store extract-fn entry)
          (recur))))
    (catch Throwable t
      (mulog/error ::extractor-crashed :exception t))))

(defn start!
  "Spawn the extractor thread. `extract-fn` is `(fn [text] -> {:entities
  [...] :relations [...]})`. Returns an `Extractor`."
  [store extract-fn & {:keys [buffer] :or {buffer 256}}]
  (let [ch       (async/chan (async/sliding-buffer buffer))
        !running (atom true)
        t        (async/thread (run-loop! store extract-fn ch !running))]
    (mulog/info ::graph-extractor-started)
    (->Extractor store extract-fn ch t !running)))

(defn stop!
  "Signal the extractor to exit and close its channel. Idempotent."
  [^Extractor ex]
  (when (and ex @(:!running ex))
    (reset! (:!running ex) false)
    (async/close! (:ch ex))
    (mulog/info ::graph-extractor-stopping)
    nil))

(defn await-drain!
  "Wait up to `timeout-ms` for the extractor thread to finish in-flight work."
  [^Extractor ex timeout-ms]
  (when ex
    (let [[v _] (async/alts!! [(:thread ex) (async/timeout timeout-ms)])]
      v)))
