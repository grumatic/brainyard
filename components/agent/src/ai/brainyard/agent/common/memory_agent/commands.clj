;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: Apache-2.0
;; Licensed under the Apache License, Version 2.0 (the "License"); you may
;; not use this file except in compliance with the License. See LICENSE at
;; the repository root and CONTRIBUTING.md for contribution terms.

(ns ai.brainyard.agent.common.memory-agent.commands
  "Phase 1 primitive tools (`memory$*`) for the memory-agent.

   These are thin `defcommand` wrappers over `components/memory/interface.clj`
   plus a `memory$stats` composite that aggregates cheap count queries
   into the §6 stats map.

   Surface (design §5):

     Reads (callable by any agent):
       memory$read              — single-layer raw read
       memory$stats             — composite stats (cheap, no FTS scan)
       memory$keywords          — extract distinctive keywords from text

     Writes (gated to memory-agent — see hooks.clj):
       memory$write             — write-entry
       memory$promote           — promote-entry across layers
       memory$forget            — soft-delete (tombstone)
       memory$keep!             — pin against TTL sweep (toggle)
       memory$archive!          — exclude from default recall (toggle)
       memory$consolidate       — heuristic L2 → L3 reduction (P1)
       memory$sweep-l2          — TTL sweep

     Working-area (gated to memory-agent):
       memory$state-read        — read EDN slot (stats.edn, pending/*)
       memory$state-write       — write EDN slot
       memory$essence-append    — append NDJSON record to essence.log

   The existing `memory$recall`, `memory$explain`, `memory$remember`,
   `memory$status` defcommands in `agent.common.commands` continue to
   serve as the LLM-facing curated surface; the primitives here are
   raw substrate.

   `entry-id-for` is the L3 dedupe helper that gives `memory$write` a
   content-addressable id when the caller omits `:entry-id`."
  (:require [clojure.set]
            [ai.brainyard.agent.common.guard :as guard]
            [ai.brainyard.agent.common.memory-agent.signatures :as ma-sig]
            [ai.brainyard.agent.common.memory-agent.working-area :as wa]
            [ai.brainyard.agent.core.config :as config]
            [ai.brainyard.agent.core.protocol :as proto]
            [ai.brainyard.agent.core.tool :refer [defcommand]]
            [ai.brainyard.clj-llm.interface :as clj-llm]
            [ai.brainyard.memory.interface :as mem]
            [ai.brainyard.mulog.interface :as mulog]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [next.jdbc :as jdbc]))

;; ============================================================================
;; Helpers
;; ============================================================================

(def ^:private layer-kw
  {"l1" :l1 "l2" :l2 "l3" :l3
   "L1" :l1 "L2" :l2 "L3" :l3
   :l1 :l1 :l2 :l2 :l3 :l3})

(defn- ->layer [v]
  (or (layer-kw v)
      (throw (IllegalArgumentException.
              (str "memory$*: invalid layer: " (pr-str v) " (expected :l1, :l2, or :l3)")))))

(defn- current-mm []
  (some-> proto/*current-agent* :!state deref :memory-manager))

(defn- current-user-id []
  (or (some-> (current-mm) :user-id)
      (some-> proto/*current-agent* proto/user-id)))

(defn- current-session-id []
  (some-> proto/*current-agent* proto/session-id))

(defn- require-mm []
  (or (current-mm)
      (throw (ex-info "memory$*: no memory manager bound on current agent"
                      {:reason :no-memory-manager}))))

;; ============================================================================
;; Content-addressable id (L3 dedupe)
;; ============================================================================

(defn- normalize-for-hash
  "Normalize content for hash-based dedupe: lowercase, collapsed whitespace,
   trimmed punctuation. Two essences that say the same thing in slightly
   different casing/spacing converge on one row."
  [s]
  (-> (str s)
      str/lower-case
      str/trim
      (str/replace #"[\p{Punct}]" " ")
      (str/replace #"\s+" " ")))

(defn entry-id-for
  "Stable content-addressable entry id of the form `\"<layer>/<sha-16>\"`.

   Two writes whose `content` normalizes to the same string converge on
   one row (the unique index on `entry_id` upserts in place). Callers
   that already carry an id should pass it explicitly to `memory$write`."
  [layer content]
  (let [bytes (.getBytes ^String (normalize-for-hash content) "UTF-8")
        md    (java.security.MessageDigest/getInstance "SHA-256")
        digest (.digest md bytes)
        hex   (apply str (map #(format "%02x" (bit-and % 0xff)) digest))]
    (str (name (->layer layer)) "/" (subs hex 0 16))))

;; ============================================================================
;; Read primitives
;; ============================================================================

(defcommand memory$read
  "Read entries from a single memory layer (l1 | l2 | l3) by per-layer query map."
  (fn [& {:keys [layer query limit include-archived]}]
    (try
      (let [mm    (require-mm)
            lyr   (->layer layer)
            qry   (or query {})
            opts  (cond-> {:limit (or limit 20)}
                    include-archived (assoc :include-archived true))
            xs    (mem/read-entries mm lyr qry opts)]
        {:layer (name lyr) :count (count xs) :entries (vec xs)})
      (catch Exception e
        (mulog/warn ::memory$read-failed :exception e)
        {:error (ex-message e)})))
  :input-schema  [:map
                  [:layer            [:string {:desc "memory layer: l1 | l2 | l3"}]]
                  [:query            {:optional true} [:map    {:desc "per-layer query map (see docstring)"}]]
                  [:limit            {:optional true} [:int    {:desc "max results (default 20)"}]]
                  [:include-archived {:optional true} [:boolean {:desc "include archived entries (default false)"}]]]
  :output-schema [:map
                  [:layer   [:string {:desc "Layer queried"}]]
                  [:count   [:int    {:desc "Entries returned"}]]
                  [:entries [:vector {:desc "Vector of entries"} :any]]
                  [:error   [:string {:desc "Error message if read failed"}]]])

(defn- count-where
  "COUNT(*) helper that swallows the error path back to 0 — stats must
   never throw at the caller."
  [ds sql params]
  (try
    (let [r (jdbc/execute-one! ds (into [sql] params))]
      (or (:cnt r) 0))
    (catch Exception _ 0)))

(defn- min-max-timestamp
  "Return [oldest newest] timestamps for `episodes` filtered by user-id.
   Both nil when the table is empty."
  [ds user-id]
  (try
    (let [r (jdbc/execute-one!
             ds
             ["SELECT MIN(timestamp) AS oldest, MAX(timestamp) AS newest
               FROM episodes WHERE user_id = ? AND tombstoned_flag = 0"
              user-id])]
      [(:oldest r) (:newest r)])
    (catch Exception _ [nil nil])))

(defn- l3-by-kind
  "Returns {fact-type count} for the user's non-tombstoned L3 facts."
  [ds user-id]
  (try
    (->> (jdbc/execute! ds
                        ["SELECT fact_type, COUNT(*) AS cnt
                          FROM semantic_facts
                          WHERE user_id = ? AND tombstoned_flag = 0
                          GROUP BY fact_type" user-id])
         (reduce (fn [acc r] (assoc acc (keyword (:fact_type r)) (:cnt r))) {}))
    (catch Exception _ {})))

(defn- l3-confidence-buckets
  [ds user-id]
  (try
    (let [r (jdbc/execute-one!
             ds
             ["SELECT
                 SUM(CASE WHEN confidence >= 0.8 THEN 1 ELSE 0 END) AS high,
                 SUM(CASE WHEN confidence >= 0.5 AND confidence < 0.8 THEN 1 ELSE 0 END) AS medium,
                 SUM(CASE WHEN confidence <  0.5 THEN 1 ELSE 0 END) AS low
               FROM semantic_facts
               WHERE user_id = ? AND tombstoned_flag = 0" user-id])]
      {:high (or (:high r) 0) :medium (or (:medium r) 0) :low (or (:low r) 0)})
    (catch Exception _ {:high 0 :medium 0 :low 0})))

(defn- expand-home
  "Expand a leading `~` to the user's home dir. Mirrors the helper in
   memory.core.sqlite — duplicated here so this namespace only depends
   on memory's public interface (polylith rule)."
  [path]
  (cond
    (not (string? path)) path
    (= path "~")         (System/getProperty "user.home")
    (str/starts-with? path "~/")
    (str (System/getProperty "user.home") (subs path 1))
    :else path))

(defn- db-file-bytes
  "Bytes on disk for the DB file. Returns nil for in-memory DBs."
  [path]
  (when (and path (not (str/includes? (str path) ":memory:")))
    (try
      (let [f (java.io.File. ^String (expand-home path))]
        (when (.isFile f) (.length f)))
      (catch Exception _ nil))))

(defn- compute-stats
  "Cheap composite stats. Per design §6 — count queries only, no FTS scan.

   Fields deferred to later phases (return nil/sentinel for now):
     :db {:wal-bytes :page-count :page-size}    — needs PRAGMA queries
     :l1 {:quota-keys :quota-bytes :bytes}      — L1 quota was removed
     :l2 {:sessions-orphan :bytes-fts}          — Phase 4 (purge)
     :l3 {:stale :orphan}                        — Phase 4 (purge)
     :capture {:backlog :critical?}              — sidecar internals
     :health {:last-sweep-at :last-consolidate-at} — Phase 2 (stats.edn)"
  [manager session-id]
  (let [ds        (:ds manager)
        user-id   (:user-id manager)
        db-path   (or (:db-path manager) ":memory:")
        l1-entries (some-> manager :store :l1-store :!entries deref)
        l1-pinned  (or (some->> l1-entries vals
                                (filter (comp boolean :keep))
                                count)
                       0)
        l1-session-count (or (some->> l1-entries
                                      (filter (fn [[[sid _] _]] (= sid session-id)))
                                      count)
                             0)
        [l2-oldest l2-newest] (min-max-timestamp ds user-id)]
    {:db {:path      db-path
          :bytes     (db-file-bytes db-path)
          :wal-bytes nil
          :page-count nil
          :page-size nil}
     :l1 {:count       (count l1-entries)
          :session-id  session-id
          :session-entries l1-session-count
          :pinned      l1-pinned}
     :l2 {:total           (count-where ds "SELECT COUNT(*) AS cnt FROM episodes WHERE user_id = ? AND tombstoned_flag = 0" [user-id])
          :current-session (when session-id
                             (count-where ds "SELECT COUNT(*) AS cnt FROM episodes WHERE user_id = ? AND session_id = ? AND tombstoned_flag = 0"
                                          [user-id session-id]))
          :sessions-known  (count-where ds "SELECT COUNT(DISTINCT session_id) AS cnt FROM episodes WHERE user_id = ?" [user-id])
          :sessions-orphan nil
          :keep-flagged    (count-where ds "SELECT COUNT(*) AS cnt FROM episodes WHERE user_id = ? AND keep_flag = 1" [user-id])
          :archived        (count-where ds "SELECT COUNT(*) AS cnt FROM episodes WHERE user_id = ? AND archived_flag = 1" [user-id])
          :tombstoned      (count-where ds "SELECT COUNT(*) AS cnt FROM episodes WHERE user_id = ? AND tombstoned_flag = 1" [user-id])
          :oldest-at       l2-oldest
          :newest-at       l2-newest}
     :l3 {:total              (count-where ds "SELECT COUNT(*) AS cnt FROM semantic_facts WHERE user_id = ? AND tombstoned_flag = 0" [user-id])
          :by-kind            (l3-by-kind ds user-id)
          :confidence-buckets (l3-confidence-buckets ds user-id)
          :stale              nil
          :orphan             nil
          :archived           (count-where ds "SELECT COUNT(*) AS cnt FROM semantic_facts WHERE user_id = ? AND archived_flag = 1" [user-id])
          :tombstoned         (count-where ds "SELECT COUNT(*) AS cnt FROM semantic_facts WHERE user_id = ? AND tombstoned_flag = 1" [user-id])}
     :capture {:running? (boolean (mem/capture-running? manager))
               :backlog  nil
               :critical? nil
               :reducer  :heuristic}
     :audit   {:rows  (count-where ds "SELECT COUNT(*) AS cnt FROM memory_audit WHERE user_id = ?" [user-id])
               :bytes nil}
     :health  {:status   :ok
               :warnings []
               :last-sweep-at       nil
               :last-consolidate-at nil}}))

(defcommand memory$stats
  "Composite memory stats (db size, layer counts, capture state). Cheap — no FTS scan."
  (fn [& _]
    (try
      (let [mm  (require-mm)
            sid (current-session-id)]
        {:stats (compute-stats mm sid)})
      (catch Exception e
        (mulog/warn ::memory$stats-failed :exception e)
        {:error (ex-message e)})))
  :output-schema [:map
                  [:stats [:any    {:desc "Composite stats map (see memory-agent design §6)"}]]
                  [:error [:string {:desc "Error if no memory manager / DB unreachable"}]]])

(defcommand memory$keywords
  "Extract distinctive keywords from text via FTS5 normalization."
  (fn [& {:keys [text]}]
    (if (str/blank? text)
      {:error ":text is required"}
      (try {:keywords (vec (mem/extract-keywords text))}
           (catch Exception e {:error (ex-message e)}))))
  :input-schema  [:map
                  [:text [:string {:desc "Source text"}]]]
  :output-schema [:map
                  [:keywords [:vector {:desc "Vector of keyword strings"} :string]]
                  [:error    [:string {:desc "Error message"}]]])

;; ============================================================================
;; Write primitives (gated to memory-agent — see hooks.clj)
;; ============================================================================

(defn- ->entry
  "Normalize an :entry argument from the LLM. Accepts a map; ensures
   :session-id and :user-id are populated from the current agent."
  [raw-entry layer]
  (let [entry (if (map? raw-entry) raw-entry {})
        sid   (current-session-id)
        uid   (current-user-id)]
    (cond-> entry
      (and (contains? #{:l1 :l2} layer) (nil? (:session-id entry))) (assoc :session-id sid)
      (nil? (:user-id entry)) (assoc :user-id uid)
      ;; Mint a content-addressable :id when the caller didn't supply
      ;; one — the unified-store maps :id → SQL `entry_id` (the unique
      ;; cross-layer id), so this keeps repeated L3 writes idempotent.
      (and (= layer :l3) (nil? (:id entry)) (:content entry))
      (assoc :id (entry-id-for layer (:content entry))))))

(defcommand memory$write
  "Write an entry to a layer. L3 writes are idempotent via content-addressable id."
  (fn [& {:keys [layer entry]}]
    (try
      (let [mm    (require-mm)
            lyr   (->layer layer)
            ent   (->entry entry lyr)]
        (if-let [v (guard/content-violation (pr-str ent))]
          v
          (let [saved (or (mem/write-entry mm lyr ent)
                          ;; Unique-constraint duplicate path — the underlying
                          ;; store returns nil rather than upserting. Look the
                          ;; existing row up by our minted :id so the caller
                          ;; gets a stable id and an idempotent result.
                          (when (and (:id ent) (contains? #{:l2 :l3} lyr))
                            (first (mem/read-entries mm lyr {:id (:id ent)} {}))))]
            (if saved
              {:entry-id (:id saved)
               :layer    (name lyr)
               :entry    saved}
              {:error "memory$write: store rejected the entry (no row inserted, no existing row found)"}))))
      (catch Exception e
        (mulog/warn ::memory$write-failed :exception e)
        {:error (ex-message e)})))
  :input-schema  [:map
                  [:layer [:string {:desc "layer: l1 | l2 | l3"}]]
                  [:entry [:map    {:desc "entry map (see protocol/IMemoryStore for schema)"}]]]
  :output-schema [:map
                  [:entry-id [:string {:desc "Persisted entry-id"}]]
                  [:layer    [:string {:desc "Layer written to"}]]
                  [:entry    [:any    {:desc "Hydrated saved entry"}]]
                  [:error    [:string {:desc "Error message"}]]])

(defcommand memory$promote
  "Copy an entry across layers, stamping :sources for provenance."
  (fn [& {:keys [entry from to]}]
    (try
      (let [mm    (require-mm)
            from' (->layer from)
            to'   (->layer to)
            saved (mem/promote-entry mm entry from' to')]
        {:entry-id (:id saved)
         :from     (name from')
         :to       (name to')
         :entry    saved})
      (catch Exception e
        (mulog/warn ::memory$promote-failed :exception e)
        {:error (ex-message e)})))
  :input-schema  [:map
                  [:entry [:map    {:desc "live entry map to promote"}]]
                  [:from  [:string {:desc "source layer"}]]
                  [:to    [:string {:desc "target layer"}]]]
  :output-schema [:map
                  [:entry-id [:string {:desc "id of the new entry in :to"}]]
                  [:from     [:string {:desc "Source layer"}]]
                  [:to       [:string {:desc "Target layer"}]]
                  [:entry    [:any    {:desc "Hydrated saved entry"}]]
                  [:error    [:string {:desc "Error message"}]]])

(defcommand memory$forget
  "Soft-delete (tombstone) an entry; never hard-deletes (audit retention)."
  (fn [& {:keys [layer entry-id reason]}]
    (try
      (let [mm   (require-mm)
            lyr  (->layer layer)
            ok?  (mem/forget-entry mm lyr entry-id)]
        (mulog/info ::memory$forget :layer lyr :entry-id entry-id :reason reason)
        {:ok      (boolean ok?)
         :layer   (name lyr)
         :entry-id entry-id})
      (catch Exception e
        (mulog/warn ::memory$forget-failed :exception e)
        {:error (ex-message e)})))
  :input-schema  [:map
                  [:layer    [:string {:desc "layer: l1 | l2 | l3"}]]
                  [:entry-id [:string {:desc "stable entry-id to tombstone"}]]
                  [:reason   {:optional true} [:string {:desc "Free-text reason recorded in logs"}]]]
  :output-schema [:map
                  [:ok       [:boolean {:desc "True if the entry existed"}]]
                  [:layer    [:string  {:desc "Layer affected"}]]
                  [:entry-id [:string  {:desc "Echoed entry-id"}]]
                  [:error    [:string  {:desc "Error message"}]]])

(defcommand memory$keep!
  "Toggle keep_flag on an L2/L3 entry (pin against TTL sweep)."
  (fn [& {:keys [layer entry-id value] :or {value true}}]
    (try
      (let [mm   (require-mm)
            lyr  (->layer layer)
            ok?  (if value
                   (mem/keep! mm lyr entry-id)
                   (mem/unkeep! mm lyr entry-id))]
        {:ok      (boolean ok?)
         :layer   (name lyr)
         :entry-id entry-id
         :value   (boolean value)})
      (catch Exception e
        (mulog/warn ::memory$keep-failed :exception e)
        {:error (ex-message e)})))
  :input-schema  [:map
                  [:layer    [:string  {:desc "layer: l2 | l3"}]]
                  [:entry-id [:string  {:desc "stable entry-id"}]]
                  [:value    {:optional true} [:boolean {:desc "true (pin) | false (un-pin); default true"}]]]
  :output-schema [:map
                  [:ok       [:boolean {:desc "True when the entry existed"}]]
                  [:layer    [:string  {:desc "Layer affected"}]]
                  [:entry-id [:string  {:desc "Echoed entry-id"}]]
                  [:value    [:boolean {:desc "Resulting keep_flag value"}]]
                  [:error    [:string  {:desc "Error message"}]]])

(defcommand memory$archive!
  "Toggle archived_flag on an L2/L3 entry (exclude from default recall)."
  (fn [& {:keys [layer entry-id value] :or {value true}}]
    (try
      (let [mm   (require-mm)
            lyr  (->layer layer)
            ok?  (if value
                   (mem/archive! mm lyr entry-id)
                   (mem/unarchive! mm lyr entry-id))]
        {:ok      (boolean ok?)
         :layer   (name lyr)
         :entry-id entry-id
         :value   (boolean value)})
      (catch Exception e
        (mulog/warn ::memory$archive-failed :exception e)
        {:error (ex-message e)})))
  :input-schema  [:map
                  [:layer    [:string  {:desc "layer: l2 | l3"}]]
                  [:entry-id [:string  {:desc "stable entry-id"}]]
                  [:value    {:optional true} [:boolean {:desc "true (archive) | false (unarchive); default true"}]]]
  :output-schema [:map
                  [:ok       [:boolean {:desc "True when the entry existed"}]]
                  [:layer    [:string  {:desc "Layer affected"}]]
                  [:entry-id [:string  {:desc "Echoed entry-id"}]]
                  [:value    [:boolean {:desc "Resulting archived_flag value"}]]
                  [:error    [:string  {:desc "Error message"}]]])

(defcommand memory$consolidate
  "Run L2 → L3 consolidation (heuristic reducer; pass :reducer \"llm\" for LLM-reduced)."
  (fn [& {:keys [session-id window-ms min-batch reducer]}]
    (try
      (let [mm  (require-mm)
            sid (or session-id (current-session-id))
            opts (cond-> []
                   sid       (into [:session-id sid])
                   window-ms (into [:window-ms window-ms])
                   min-batch (into [:min-batch min-batch])
                   reducer   (into [:reducer (keyword reducer)]))
            r (apply mem/consolidate-l2! mm opts)]
        {:report r})
      (catch Exception e
        (mulog/warn ::memory$consolidate-failed :exception e)
        {:error (ex-message e)})))
  :input-schema  [:map
                  [:session-id {:optional true} [:string {:desc "Restrict to this session (default: current session)"}]]
                  [:window-ms  {:optional true} [:int    {:desc "Bucket size in ms (default 600000)"}]]
                  [:min-batch  {:optional true} [:int    {:desc "Minimum events per batch (default 3)"}]]
                  [:reducer    {:optional true} [:string {:desc "Reducer kind: heuristic (default) | llm (Phase 4)"}]]]
  :output-schema [:map
                  [:report [:any    {:desc "{:from-layer :to-layer :produced :consumed :batches}"}]]
                  [:error  [:string {:desc "Error message"}]]])

(defcommand memory$sweep-l2
  "TTL sweep on L2; tombstones non-kept episodes older than :retention-days (default 30)."
  (fn [& {:keys [retention-days]}]
    (try
      (let [mm (require-mm)
            opts (cond-> [] retention-days (into [:retention-days retention-days]))
            n  (apply mem/sweep-l2! mm opts)]
        {:tombstoned n
         :retention-days (or retention-days 30)})
      (catch Exception e
        (mulog/warn ::memory$sweep-l2-failed :exception e)
        {:error (ex-message e)})))
  :input-schema  [:map
                  [:retention-days {:optional true} [:int {:desc "TTL window in days (default 30)"}]]]
  :output-schema [:map
                  [:tombstoned     [:int    {:desc "Rows tombstoned"}]]
                  [:retention-days [:int    {:desc "Effective TTL window"}]]
                  [:error          [:string {:desc "Error message"}]]])

;; ============================================================================
;; Working-area primitives (gated to memory-agent)
;; ============================================================================

(defcommand memory$state-read
  "Read an EDN slot from the memory-agent working area (whitelisted slots only)."
  (fn [& {:keys [slot]}]
    (try
      (let [uid (current-user-id)]
        (when-not uid
          (throw (ex-info "memory$state-read: no user-id available" {})))
        (when-not (wa/allowed-slot? slot)
          (throw (ex-info (str "memory$state-read: slot not allowed: " (pr-str slot))
                          {:slot slot})))
        {:slot slot :content (wa/read-slot uid slot)})
      (catch Exception e
        {:error (ex-message e)})))
  :input-schema  [:map
                  [:slot [:string {:desc "Whitelisted relative slot path"}]]]
  :output-schema [:map
                  [:slot    [:string {:desc "Echoed slot"}]]
                  [:content [:any    {:desc "Parsed EDN content (nil when absent)"}]]
                  [:error   [:string {:desc "Error message"}]]])

(defcommand memory$state-write
  "Write an EDN slot in the memory-agent working area (whitelisted slots only)."
  (fn [& {:keys [slot content]}]
    (try
      (let [uid (current-user-id)]
        (when-not uid
          (throw (ex-info "memory$state-write: no user-id available" {})))
        (if-let [v (guard/content-violation (pr-str content))]
          v
          (let [path (wa/write-slot! uid slot content)]
            {:slot slot :path path})))
      (catch Exception e
        {:error (ex-message e)})))
  :input-schema  [:map
                  [:slot    [:string {:desc "Whitelisted relative slot path"}]]
                  [:content [:any    {:desc "EDN-printable content"}]]]
  :output-schema [:map
                  [:slot  [:string {:desc "Echoed slot"}]]
                  [:path  [:string {:desc "Absolute path of written file"}]]
                  [:error [:string {:desc "Error message"}]]])

;; ============================================================================
;; LLM-backed essence extraction (gated)
;; ----------------------------------------------------------------------------
;; Wraps EssenceExtraction in a tool so memory-agent's CoAct LLM doesn't have
;; to hand-craft prompts + parse JSON. The chain-of-thought call runs against
;; the agent's sub-LM (typically a haiku-class model — see :sub-lm-config in
;; runtime config) since essence judgement is cheap reasoning, not generation.
;; ============================================================================

(defn- resolve-usage-tracker []
  (when-let [agent proto/*current-agent*]
    (get-in @(:!session agent) [:config :usage-tracker])))

(defcommand memory$essence-extract
  "Run EssenceExtraction over a just-finished turn; returns 0..3 essence maps."
  (fn [& {:keys [turn-summary turn-messages recent-episodes user-id]}]
    (try
      (let [result (clj-llm/chain-of-thought
                    ma-sig/EssenceExtraction
                    {:turn-summary    (or turn-summary "")
                     :turn-messages   (or turn-messages "")
                     :recent-episodes (or recent-episodes "")
                     :user-id         (or user-id (current-user-id) "unknown")}
                    :lm-config     (config/resolve-sub-lm)
                    :usage-tracker (resolve-usage-tracker))]
        {:essences (or (some-> result :outputs :essences vec) [])
         :reasoning (:reasoning result)})
      (catch Exception e
        (mulog/warn ::memory$essence-extract-failed :exception e)
        {:error (ex-message e)})))
  :input-schema  [:map
                  [:turn-summary    {:optional true} :string]
                  [:turn-messages   {:optional true} :string]
                  [:recent-episodes {:optional true} :string]
                  [:user-id         {:optional true} :string]]
  :output-schema [:map
                  [:essences  [:vector {:desc "0..3 essence maps"} :any]]
                  [:reasoning {:optional true} [:string {:desc "Chain-of-thought rationale"}]]
                  [:error     {:optional true} [:string {:desc "Error if the call failed"}]]])

(defcommand memory$verify-fact
  "Run FactVerification on one L3 fact against fresh recall + optional evidence; returns a verdict map."
  (fn [& {:keys [fact fresh-recall evidence]}]
    (try
      (let [fact-map (cond-> (or fact {})
                       ;; Stamp safe defaults so the Malli :map schema
                       ;; never sees missing keys from a sloppy caller.
                       (nil? (:id fact))         (assoc :id "")
                       (nil? (:content fact))    (assoc :content "")
                       (nil? (:confidence fact)) (assoc :confidence 0.5)
                       (nil? (:tags fact))       (assoc :tags []))
            result   (clj-llm/chain-of-thought
                      ma-sig/FactVerification
                      {:fact         fact-map
                       :fresh-recall (or fresh-recall "")
                       :evidence     (or evidence "")}
                      :lm-config     (config/resolve-sub-lm)
                      :usage-tracker (resolve-usage-tracker))
            out      (:outputs result)]
        {:verdict         (some-> (:verdict out) keyword)
         :refined-content (or (:refined-content out) "")
         :new-confidence  (or (:new-confidence out) (:confidence fact-map))
         :rationale       (or (:rationale out) "")
         :reasoning       (:reasoning result)})
      (catch Exception e
        (mulog/warn ::memory$verify-fact-failed :exception e)
        {:error (ex-message e)})))
  :input-schema  [:map
                  [:fact         [:map    {:desc "Stored L3 fact map {:id :content :confidence :tags}"}]]
                  [:fresh-recall {:optional true} :string]
                  [:evidence     {:optional true} :string]]
  :output-schema [:map
                  [:verdict         [:string {:desc ":still-true | :refine | :wrong"}]]
                  [:refined-content [:string {:desc "Replacement (refine) or counter-fact (wrong); empty when :still-true"}]]
                  [:new-confidence  [:double {:desc "Confidence after verdict"}]]
                  [:rationale       [:string {:desc "One-sentence justification"}]]
                  [:reasoning       {:optional true} [:string {:desc "Chain-of-thought rationale"}]]
                  [:error           {:optional true} [:string {:desc "Error if the call failed"}]]])

(defcommand memory$llm-consolidate
  "Run LlmReducer over a windowed slice of L2 episodes; returns up to 5 distilled L3 facts."
  (fn [& {:keys [episodes window-desc existing-l3-hits user-id]}]
    (try
      (let [result (clj-llm/chain-of-thought
                    ma-sig/LlmReducer
                    {:episodes         (or episodes [])
                     :window-desc      (or window-desc "")
                     :existing-l3-hits (or existing-l3-hits "")
                     :user-id          (or user-id (current-user-id) "unknown")}
                    :lm-config     (config/resolve-sub-lm)
                    :usage-tracker (resolve-usage-tracker))]
        {:facts     (or (some-> result :outputs :facts vec) [])
         :reasoning (:reasoning result)})
      (catch Exception e
        (mulog/warn ::memory$llm-consolidate-failed :exception e)
        {:error (ex-message e)})))
  :input-schema  [:map
                  [:episodes         {:optional true} [:vector :any]]
                  [:window-desc      {:optional true} :string]
                  [:existing-l3-hits {:optional true} :string]
                  [:user-id          {:optional true} :string]]
  :output-schema [:map
                  [:facts     [:vector {:desc "0..5 distilled L3 fact maps"} :any]]
                  [:reasoning {:optional true} [:string {:desc "Chain-of-thought rationale"}]]
                  [:error     {:optional true} [:string {:desc "Error if the call failed"}]]])

;; ============================================================================
;; Purge planning — Phase 4
;; ----------------------------------------------------------------------------
;; Deterministic helper. Produces the candidate list (orphan sessions,
;; orphan facts, stale facts) without taking ANY tombstone action.
;; Memory-agent's :op :purge handler reads this plan and dispatches
;; memory$forget (and, in Phase 5, memory$verify-fact) per candidate.
;; ============================================================================

(def ^:const persist-sessions-root
  "Default agent-tui-persist session directory. Cross-referenced when
   detecting orphan sessions — anything in the memory DB but not in
   here AND not in the agent-registry is orphan. We read directly
   instead of taking a polylith dep on agent-tui-persist."
  (str (System/getProperty "user.home") "/.brainyard/sessions"))

(defn live-sessions-on-disk
  "Set of session-id strings present under the persist root. Returns #{}
   when the directory doesn't exist or is unreadable. Public so tests
   can `with-redefs` over it."
  []
  (let [^java.io.File root (io/file persist-sessions-root)]
    (if (and (.isDirectory root) (.canRead root))
      (into #{} (comp (filter (fn [^java.io.File f]
                                (and (.isDirectory f)
                                     (not (str/starts-with? (.getName f) ".")))))
                      (map (fn [^java.io.File f] (.getName f))))
            (.listFiles root))
      #{})))

(defn live-sessions-in-registry
  "Set of session-id strings currently in the agent registry. Reads
   via requiring-resolve so this ns doesn't take a hard compile-time
   dep on agent.core.agent. Public so tests can `with-redefs` over it."
  []
  (try
    (let [list-agents (requiring-resolve 'ai.brainyard.agent.core.agent/list-agents)]
      (into #{} (keep #(when-let [s (proto/session-id %)] (str s))) (list-agents)))
    (catch Exception _ #{})))

(defn- db-sessions
  "Set of session-id strings the user's DB has L2 episodes for
   (non-tombstoned, non-keep). Empty when the table is unreachable.
   next.jdbc returns namespaced row keys (`:episodes/session_id`),
   so we accept either shape."
  [ds user-id]
  (try
    (into #{}
          (keep (fn [row]
                  (or (:session_id row) (:episodes/session_id row))))
          (jdbc/execute! ds
                         ["SELECT DISTINCT session_id FROM episodes
                            WHERE user_id = ?
                              AND tombstoned_flag = 0
                              AND keep_flag = 0"
                          user-id]))
    (catch Exception _ #{})))

(defn- orphan-l2-episodes
  "L2 entry-ids belonging to orphan sessions (cap N). Each row:
   `{:entry-id :session-id :timestamp :content-snippet}`."
  [ds user-id orphan-sids cap]
  (if (empty? orphan-sids)
    []
    (let [placeholders (str/join ", " (repeat (count orphan-sids) "?"))
          sql (format "SELECT entry_id, session_id, timestamp, content
                         FROM episodes
                        WHERE user_id = ?
                          AND tombstoned_flag = 0
                          AND keep_flag = 0
                          AND session_id IN (%s)
                        ORDER BY timestamp ASC
                        LIMIT ?" placeholders)
          params (into [user-id] orphan-sids)
          params (conj params cap)
          get-row (fn [r col-kw]
                    ;; next.jdbc may return either bare or :episodes/<col>
                    (or (get r col-kw)
                        (get r (keyword "episodes" (name col-kw)))))]
      (try
        (mapv (fn [r]
                (let [content (get-row r :content)]
                  {:entry-id        (get-row r :entry_id)
                   :session-id      (get-row r :session_id)
                   :timestamp       (get-row r :timestamp)
                   :content-snippet (some-> content (subs 0 (min 80 (count content))))}))
              (jdbc/execute! ds (into [sql] params)))
        (catch Exception _ [])))))

(defn- l3-stale-facts
  "L3 facts whose last_accessed is older than `stale-days` AND confidence
   is below 0.5. Capped at N."
  [ds user-id stale-days cap]
  (try
    (let [get-row (fn [r col-kw]
                    (or (get r col-kw)
                        (get r (keyword "semantic_facts" (name col-kw)))))]
      (mapv (fn [r]
              {:entry-id      (get-row r :entry_id)
               :content       (get-row r :content)
               :confidence    (get-row r :confidence)
               :last-accessed (get-row r :last_accessed)
               :reason        :stale})
            (jdbc/execute!
             ds
             ["SELECT entry_id, content, confidence, last_accessed
                 FROM semantic_facts
                WHERE user_id = ?
                  AND tombstoned_flag = 0
                  AND archived_flag  = 0
                  AND keep_flag      = 0
                  AND COALESCE(confidence, 1.0) < 0.5
                  AND last_accessed IS NOT NULL
                  AND last_accessed < datetime('now', '-' || ? || ' days')
                ORDER BY last_accessed ASC
                LIMIT ?"
              user-id stale-days cap])))
    (catch Exception _ [])))

(defn- l3-orphan-facts
  "L3 facts whose :sources JSON list contains only ids of tombstoned
   rows. Cheap-ish — does ONE pass over non-keep facts with a non-null
   :sources column, parses the JSON, and checks each source id against
   a tombstoned-rowid index. Capped at N. Imperfect (sources may point
   at L1/system/external rows we can't trace), so the LLM still has
   final say at the :op :verify-fact step in Phase 5."
  [ds user-id cap]
  ;; For now we return a conservative empty list — full implementation
  ;; needs cross-referencing the :sources JSON shape, which varies
  ;; across producers. Phase 5's :op :verify-fact will catch these
  ;; via recall instead of source-chain analysis.
  (try
    (let [_ (jdbc/execute-one! ds
                               ["SELECT COUNT(*) AS cnt FROM semantic_facts WHERE user_id = ?"
                                user-id])]
      (when (pos? cap) []))
    (catch Exception _ [])))

(defcommand memory$purge-plan
  "Compute the candidate list for a :op :purge run; takes NO tombstone action."
  (fn [& {:keys [cap stale-days]}]
    (try
      (let [mm        (require-mm)
            ds        (:ds mm)
            user-id   (:user-id mm)
            cap       (or cap 500)
            stale     (or stale-days 60)
            in-db     (db-sessions ds user-id)
            in-reg    (live-sessions-in-registry)
            on-disk   (live-sessions-on-disk)
            live      (into in-reg on-disk)
            orphan-sids (vec (sort (clojure.set/difference in-db live)))
            l2-orph     (orphan-l2-episodes ds user-id orphan-sids cap)
            l3-stale    (l3-stale-facts ds user-id stale cap)
            l3-orph     (l3-orphan-facts ds user-id cap)]
        {:l2-orphan-sessions orphan-sids
         :l2-orphan-episodes l2-orph
         :l3-stale-facts     l3-stale
         :l3-orphan-facts    l3-orph
         :counts {:l2-orphan-sessions (count orphan-sids)
                  :l2-orphan-episodes (count l2-orph)
                  :l3-stale           (count l3-stale)
                  :l3-orphan          (count l3-orph)}
         :cap   cap
         :stale-days stale})
      (catch Exception e
        (mulog/warn ::memory$purge-plan-failed :exception e)
        {:error (ex-message e)})))
  :input-schema  [:map
                  [:cap        {:optional true} :int]
                  [:stale-days {:optional true} :int]]
  :output-schema [:map
                  [:l2-orphan-sessions [:vector {:desc "Session-ids in DB but not in registry/disk"} :string]]
                  [:l2-orphan-episodes [:vector {:desc "L2 entry rows belonging to orphan sessions"} :any]]
                  [:l3-stale-facts     [:vector {:desc "L3 facts with stale last_accessed + low confidence"} :any]]
                  [:l3-orphan-facts    [:vector {:desc "L3 facts whose :sources are all tombstoned"} :any]]
                  [:counts             [:any    {:desc "{:l2-orphan-sessions :l2-orphan-episodes :l3-stale :l3-orphan}"}]]
                  [:cap                [:int    {:desc "Cap applied per candidate set"}]]
                  [:stale-days         [:int    {:desc "Effective stale-days threshold"}]]
                  [:error              {:optional true} [:string {:desc "Error if the plan could not be built"}]]])

(defcommand memory$essence-append
  "Append one NDJSON record to memory-agent's essence.log (per-turn audit trail)."
  (fn [& {:keys [turn-id agent-id essences]}]
    (try
      (let [uid (current-user-id)]
        (when-not uid
          (throw (ex-info "memory$essence-append: no user-id available" {})))
        (let [record {:turn-id  turn-id
                      :agent-id (str agent-id)
                      :essences (or essences [])
                      :at       (System/currentTimeMillis)}
              path   (wa/append-essence! uid record)]
          {:path path :appended? true}))
      (catch Exception e
        {:error (ex-message e)})))
  :input-schema  [:map
                  [:turn-id  [:int    {:desc "Per-agent turn id"}]]
                  [:agent-id [:string {:desc "Calling agent's id (string)"}]]
                  [:essences {:optional true} [:vector {:desc "Vector of essence maps (may be empty)"} :any]]]
  :output-schema [:map
                  [:path      [:string  {:desc "Absolute path of essence.log"}]]
                  [:appended? [:boolean {:desc "True when the record landed on disk"}]]
                  [:error     [:string  {:desc "Error message"}]]])

;; ============================================================================
;; Tool sets — convenient for memory-agent's roster + the write-guard
;; ============================================================================

(def write-guarded-tools
  "Tool names that must only be callable from inside memory-agent. The
   hook in `memory_agent.hooks` filters on this set."
  #{"memory$write"
    "memory$promote"
    "memory$forget"
    "memory$keep!"
    "memory$archive!"
    "memory$consolidate"
    "memory$sweep-l2"
    "memory$state-read"
    "memory$state-write"
    "memory$essence-append"
    "memory$essence-extract"
    "memory$llm-consolidate"
    "memory$purge-plan"
    "memory$verify-fact"})

(def all-tool-ids
  "Every memory$* primitive registered in this namespace, as keywords."
  #{:memory$read
    :memory$stats
    :memory$keywords
    :memory$write
    :memory$promote
    :memory$forget
    :memory$keep!
    :memory$archive!
    :memory$consolidate
    :memory$sweep-l2
    :memory$state-read
    :memory$state-write
    :memory$essence-append
    :memory$essence-extract
    :memory$llm-consolidate
    :memory$purge-plan
    :memory$verify-fact})
