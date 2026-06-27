;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.memory.core.capture.reducer
  "S2 — episodic-to-semantic reduction (P3 stub, full impl in P4).

  Groups L2 episodes by tag-set and time window, then emits a templated
  summary entry into L3. Deterministic: no LLM calls, reproducible
  output for a given input batch.

  In P3 this runs only on explicit invocation (no scheduling, no
  triggers). P4 will add scheduling + an opt-in LLM-backed reducer
  under `:reducer :llm`."
  (:require [clojure.string :as str]
            [ai.brainyard.mulog.interface :as mulog]
            [ai.brainyard.memory.core.policy :as policy]
            [ai.brainyard.memory.interface.protocol :as proto]))

;; =====================================================
;; Helpers
;; =====================================================

(def ^:private default-window-ms (* 10 60 1000)) ; 10 minutes
(def ^:private default-min-batch-size 3)

(defn- tag-bucket
  "Project an entry to the subset of tags we group by. Excludes the noisy
  per-event/per-message tag families so that an ordinary run of conversation
  in one time window batches together:

    - `event:` / `kind:`  — capture-pipeline bookkeeping.
    - `topic:`            — auto-extracted per-message keywords. These are
                            unique per turn (`topic:teal`, `topic:photon`, …),
                            so keeping them would put every turn in its own
                            bucket and the batch threshold would never be met.
                            Topic/entity-cohesive consolidation is the job of
                            the graph-community tier (CR-MEM-24), not this
                            heuristic digest.

  What remains is the stable structural tag set (primarily `role:`), so the
  heuristic produces a per-(role, time-window) digest within a session."
  [entry]
  (->> (:tags entry)
       (remove #(or (str/starts-with? % "event:")
                    (str/starts-with? % "kind:")
                    (str/starts-with? % "topic:")))
       sort
       vec))

(defn- ->millis
  "Coerce :created-at into millis since epoch. Entries written through
  the L1 store carry numeric millis; entries read back from SQLite
  carry the database's ISO timestamp string ('YYYY-MM-DD HH:MM:SS')."
  [v]
  (cond
    (nil? v)     0
    (number? v)  (long v)
    (string? v)  (try
                   (let [;; SQLite uses ' ' between date and time; java.time
                         ;; parses 'T'-separated. Replace once.
                         iso (str/replace v #" " "T")]
                     (-> (java.time.LocalDateTime/parse iso)
                         (.toInstant java.time.ZoneOffset/UTC)
                         (.toEpochMilli)))
                   (catch Exception _ 0))
    :else        0))

(defn- bucket-key
  [entry window-ms]
  (let [bucket (long (/ (->millis (:created-at entry)) window-ms))]
    [(tag-bucket entry) bucket]))

(defn- group-batches
  [episodes window-ms]
  (->> episodes
       (group-by #(bucket-key % window-ms))
       (filter (fn [[_ es]] (>= (count es) default-min-batch-size)))))

(defn- summarize-batch
  "Templated, deterministic summary for one [tag-bucket window-bucket]
  batch. Picks the longest common prefix of contents when meaningful,
  otherwise concatenates representative snippets."
  [[[tags _bucket] entries]]
  (let [contents  (->> entries (map :content) (remove str/blank?))
        n         (count entries)
        sample    (->> contents
                       (take 3)
                       (map #(if (> (count %) 80) (str (subs % 0 80) "…") %)))
        head      (str "[summary of " n " events]"
                       (when (seq tags) (str " tags=" (vec tags))))]
    (str head "\n- " (str/join "\n- " sample))))

;; =====================================================
;; Public API
;; =====================================================

(defn reduce-l2!
  "Run the heuristic reducer over L2 entries for `session-id`. Writes
  one L3 fact per qualifying batch with `:sources` chained back to the
  source episode ids.

  Options:
    :session-id   — required scope
    :user-id      — required user partition
    :window-ms    — bucket size (default 10 minutes)
    :min-batch    — minimum events to summarize (default 3)
    :reducer      — :heuristic (default) | :llm (deferred to P4)

  Returns a summary map:
    {:from-layer :l2 :to-layer :l3
     :produced n :consumed n :batches [...]}"
  [store & {:keys [session-id user-id ds window-ms min-batch reducer]
            :or   {window-ms default-window-ms
                   min-batch default-min-batch-size
                   reducer   :heuristic}}]
  (when (= reducer :llm)
    (mulog/warn ::llm-reducer-not-implemented :using :heuristic))
  (let [episodes (proto/read-entries store :l2
                                     (cond-> {}
                                       session-id (assoc :session-id session-id))
                                     {:limit 1000})
        batches  (group-batches episodes window-ms)
        produced (atom 0)
        kept     (atom 0)
        results
        (vec
         (for [[[tags bucket] entries] batches
               :let [summary (summarize-batch [[tags bucket] entries])
                     sources (mapv (fn [e]
                                     {:type :consolidation
                                      :id   (or (:id e) (:db-id e))
                                      :from-layer :l2})
                                   entries)
                     entry   {:kind       :summary
                              :content    summary
                              :user-id    user-id
                              :session-id session-id
                              :tags       (set tags)
                              :sources    sources
                              :confidence 0.85}]]
           (do
             (proto/write-entry store :l3 entry)
             (swap! produced inc)
             ;; Pin source episodes against the L2 retention sweep so
             ;; the L3 fact's :sources chain stays valid forever.
             (when ds
               (let [ids (->> entries (keep :db-id))]
                 (when (seq ids)
                   (let [n (policy/keep-episodes-by-db-id! ds ids)]
                     (swap! kept + (or n 0))))))
             {:tags tags
              :consumed (count entries)
              :summary-bytes (count summary)})))]
    (mulog/info ::capture-reducer-done
                :session-id session-id
                :batches (count results)
                :produced @produced
                :kept @kept)
    {:from-layer :l2
     :to-layer   :l3
     :produced   @produced
     :consumed   (reduce + (map :consumed results))
     :auto-kept  @kept
     :batches    results}))
