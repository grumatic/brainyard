;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent-tui.session-summary
  "Shared rendering of PERSISTED on-disk sessions for both the CLI
   (`by sessions …`) and the TUI (`/session list|resume|tree`).

   `persist/summarise-sessions` (cheap, meta.edn only) is merged with
   `persist/scan-session` (one streaming pass over messages.log for the event
   count + first user message + last answer) into an enriched row; the
   `format-*` fns render those rows as plain text (CLI pipes) or ANSI (TUI).
   Keeping one source here means the two surfaces never drift.

   Note: this is the PERSISTED-session view. The live in-memory tab list is a
   different domain — see `sessions/format-session-list` (`/session tabs`)."
  (:require [ai.brainyard.agent-tui-persist.interface :as persist]
            [ai.brainyard.agent.interface.tui.ansi :as ansi]
            [clojure.string :as str]))

;; ============================================================================
;; Small formatters
;; ============================================================================

(defn format-bytes [n]
  (let [n (long (or n 0))]
    (cond
      (< n 1024)          (str n " B")
      (< n (* 1024 1024)) (format "%.1f KB" (/ (double n) 1024))
      :else               (format "%.1f MB" (/ (double n) 1024 1024)))))

(defn format-age-millis
  "Humanise an epoch-millis timestamp as a coarse 'N ago' string, or nil."
  [ms]
  (when ms
    (let [age  (- (System/currentTimeMillis) (long ms))
          mins (quot age 60000)
          hrs  (quot mins 60)
          days (quot hrs 24)]
      (cond
        (> days 1) (str days "d ago")
        (> hrs 1)  (str hrs "h ago")
        (> mins 1) (str mins "m ago")
        :else      "just now"))))

(defn- truncate [s n]
  (let [s (str s)]
    (if (<= (count s) n) s (str (subs s 0 (max 0 (dec n))) "…"))))

(defn- one-line
  "Whitespace-collapse to a single trimmed line (a multi-line prompt would
   otherwise break the table)."
  [s]
  (some-> s str (str/replace #"\s+" " ") str/trim))

(defn- agent-name [{:keys [defagent-id agent-id]}]
  (or (some-> defagent-id name) (some-> agent-id name) "-"))

(defn- sty [ansi? s & mods]
  (if (and ansi? (seq mods)) (apply ansi/style s mods) s))

(defn- dim [ansi? s] (if ansi? (ansi/muted s) s))

;; ============================================================================
;; Enrichment
;; ============================================================================

(defn enriched-summaries
  "Every persisted session as an enriched row (meta + scanned log fields),
   newest first by last-attached-at (then started-at)."
  []
  (->> (persist/summarise-sessions)
       (map (fn [{:keys [session-id] :as s}]
              (merge s (persist/scan-session session-id))))
       (sort-by (fn [s] (- 0 (long (or (:last-attached-at s) (:started-at s) 0)))))
       vec))

;; ============================================================================
;; Table (list / picker)
;; ============================================================================

(defn format-table
  "Render enriched `rows` into a vec of display lines (two per session: a
   metadata line + a dim first-user-message preview). Options:
     :ansi?     ANSI-style the output (default false — plain for CLI pipes)
     :numbered? prefix a 1-based index per row (for interactive pickers)
     :active    session-id to flag with a ▸ marker"
  ([rows] (format-table rows {}))
  ([rows {:keys [ansi? numbered? active]}]
   (vec
    (mapcat
     (fn [i {:keys [session-id label model bytes event-count
                    last-attached-at started-at parent-id first-user-input] :as row}]
       (let [marker  (cond (= session-id active) "▸"
                           parent-id             "↳"
                           :else                 " ")
             idx     (when numbered? (format "%3d " (inc i)))
             head    (str (or idx "")
                          marker " "
                          (sty ansi? session-id ansi/bold ansi/bright-cyan)
                          "  " (agent-name row)
                          (when model (str " · " model))
                          "  " (dim ansi? (str (format-bytes bytes)
                                               " · " (or event-count 0) " msg"
                                               " · " (or (format-age-millis
                                                          (or last-attached-at started-at))
                                                         "-")))
                          (when (and label (not (str/blank? label)))
                            (str "  " (sty ansi? (str "[" label "]") ansi/bright-yellow))))
             preview (let [p (one-line first-user-input)]
                       (str "     "
                            (dim ansi? (if (str/blank? p)
                                         "(no messages yet)"
                                         (str "› " (truncate p 76))))))]
         [head preview]))
     (range)
     rows))))

;; ============================================================================
;; Detail (show)
;; ============================================================================

(defn- inst-or-millis->str [v]
  (cond
    (nil? v)        "-"
    (number? v)     (str (java.util.Date. (long v)))
    :else           (str v)))

(defn format-detail
  "Render a single enriched `row` as a multi-line detail block (vec of lines)
   for `by sessions show`. `:ansi?` styles labels when true."
  [{:keys [session-id label model working-dir agent-id defagent-id
           created-at started-at last-attached-at parent-id fork-point
           event-count bytes first-user-input last-answer]}
   & [{:keys [ansi?]}]]
  (let [lineage (persist/session-lineage session-id)
        sb      (try (persist/scrollback-bytes session-id :stream) (catch Throwable _ nil))
        kv      (fn [k v] (str "  " (sty ansi? (format "%-14s" k) ansi/bold) v))
        title   (str "Session " session-id)]
    (cond-> [(if ansi? (ansi/header title) title)
             (kv "label:"       (or label "-"))
             (kv "agent:"       (str (or (some-> defagent-id name) "-")
                                     (when (and agent-id (not= agent-id defagent-id))
                                       (str " (instance " (name agent-id) ")"))))
             (kv "model:"       (or model "-"))
             (kv "working-dir:" (or working-dir "-"))
             (kv "created:"     (inst-or-millis->str (or created-at started-at)))
             (kv "last active:" (str (inst-or-millis->str last-attached-at)
                                     (when last-attached-at
                                       (str "  (" (format-age-millis last-attached-at) ")"))))
             (kv "events:"      (str (or event-count 0)))
             (kv "scrollback:"  (if sb (format-bytes sb) "-"))]
      parent-id
      (conj (kv "parent:" (str parent-id (when fork-point (str " @fork " fork-point)))))
      (and lineage (> (count lineage) 1))
      (conj (kv "lineage:" (str/join " → " lineage)))
      true
      (conj (kv "first prompt:" (or (one-line first-user-input) "-"))
            (kv "last answer:"  (truncate (or (one-line last-answer) "-") 200))))))

;; ============================================================================
;; Tree
;; ============================================================================

(defn format-tree
  "Render the persisted session tree as a vec of lines. `:active` marks the
   currently-attached session; `:ascii?` forces plain box chars."
  [& [{:keys [active ascii?]}]]
  (let [tree (persist/session-tree)]
    (if (empty? (:roots tree))
      ["(no persisted sessions)"]
      (persist/render-session-tree tree (cond-> {}
                                          active (assoc :active active)
                                          ascii? (assoc :ascii? true))))))
