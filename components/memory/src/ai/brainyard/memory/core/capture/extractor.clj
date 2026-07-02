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

;; Truncate an episode to this many chars before extraction: a huge episode
;; (e.g. an explore-agent answer listing many files) both stresses the extractor
;; LLM and yields far more entities/relations than are worth keeping. Caps input
;; → caps output → confines graph growth. Overridable via :limits :max-input-chars.
(def ^:private default-max-input-chars 12000)

(defrecord Extractor [store extract-fn ch thread !running limits])

(defn enqueue!
  "Offer an L2 entry for extraction. Non-blocking; a full channel drops the
  oldest (sliding buffer). No-op when the extractor is stopped."
  [^Extractor ex entry]
  (when (and ex entry @(:!running ex))
    (async/offer! (:ch ex) entry)))

(defn- process!
  [store extract-fn entry limits]
  (try
    (let [raw     (:content entry)
          max-in  (:max-input-chars limits default-max-input-chars)
          content (if (and (string? raw) (> (count raw) max-in))
                    (subs raw 0 max-in)
                    raw)]
      (when (and (string? content) (>= (count content) default-min-content))
        (when-let [result (extract-fn content)]
          (extract/process-extraction! store result (:id entry) limits))))
    (catch Exception e
      (mulog/warn ::graph-extract-failed :entry-id (:id entry) :error (ex-message e)))))

(defn extract-batch!
  "Synchronously run graph extraction over `entries` (a seq of L2 entry maps),
   populating graph_nodes/graph_edges via `extract-fn`. BLOCKS until every
   eligible entry is processed — the deterministic counterpart to the async
   sidecar, for scripted/backfill graph builds. Entries whose content is below
   `:min-content` chars are skipped (same threshold as the async path).
   Returns {:attempted <n eligible> :total <n entries>}."
  [store extract-fn entries & {:keys [min-content limits] :or {min-content default-min-content}}]
  (let [attempted (reduce (fn [n entry]
                            (let [content (:content entry)]
                              (if (and (string? content) (>= (count content) min-content))
                                (do (process! store extract-fn entry limits) (inc n))
                                n)))
                          0 entries)]
    {:attempted attempted :total (count entries)}))

(defn- run-loop!
  [store extract-fn ch !running limits]
  (try
    (loop []
      (when @!running
        (when-let [entry (async/<!! ch)]
          (process! store extract-fn entry limits)
          (recur))))
    (catch Throwable t
      (mulog/error ::extractor-crashed :exception t))))

(defn start!
  "Spawn the extractor thread. `extract-fn` is `(fn [text] -> {:entities
  [...] :relations [...]})`. Returns an `Extractor`.

  Options:
    :buffer — sliding-buffer size (default 256).
    :limits — per-episode graph caps merged over the extractor/extract defaults:
              `:max-input-chars` (episode truncation), `:max-entities`,
              `:max-relations` (see extract/default-graph-limits). Confines
              node/edge explosion from large episodes."
  [store extract-fn & {:keys [buffer limits] :or {buffer 256}}]
  (let [ch       (async/chan (async/sliding-buffer buffer))
        !running (atom true)
        t        (async/thread (run-loop! store extract-fn ch !running limits))]
    (mulog/info ::graph-extractor-started :limits limits)
    (->Extractor store extract-fn ch t !running limits)))

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
