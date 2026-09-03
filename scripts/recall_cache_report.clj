#!/usr/bin/env bb
;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns recall-cache-report
  "Measure what `:recall-mode \"conditional\"` costs and saves, from the app log.

   Exists to answer one question repeatably: does gating recall injection
   reduce prompt spend WITHOUT costing cache hits? Run it once with
   `:recall-mode \"always\"`, once with `\"conditional\"`, and compare.

   ## The cache geometry this measures against

   `:recalled-memory` is a USER-message input sitting in the turn-stable
   prefix BEFORE `:iterations` (CoAct's think-act-code node sets
   `:user-cache-boundary :iterations`). So per turn:

     iteration 1      -> the whole prefix, recalled-memory included, is a
                         cache WRITE (billed above the input rate on Anthropic)
     iterations 2..N  -> the same prefix is a cache READ

   It is NOT one of the system zones (:agent-core / :session-context /
   :history-context), and it is preceded by `:question`, which changes every
   turn. So that prefix never survives into the next turn anyway.

   ## The falsifiable prediction

   Because the gate runs ONCE at turn start — before iteration 1 — it cannot
   invalidate a cache read that would otherwise have hit. Conditional mode
   should therefore show:

     cache-write/turn   DOWN   (smaller prefix to write)
     cache-read/turn    DOWN   (same prefix, read N-1 times)
     iterations/turn    FLAT   (a turn should not need more passes because
                                weakly-related memory was withheld)

   If iterations/turn RISES, the gate is dropping hits the agent needed and
   is buying tokens with turns — the one outcome that makes it a bad trade,
   and the reason that column is here.

   ## What 3 paired runs actually showed (2026-09-03, claude-code)

   Same question per pair, capture disabled, `min-terms 1`:

     pair          iters (a/c)   gate saved   Δcache-write   Δcache-read
     sqlite-vec        2 / 2         194 tok        +3,141        +4,547
     sandbox           2 / 2         260 tok        +5,578       +61,914
     emoji-width       4 / 4         412 tok        -1,904       +31,022

   Read this carefully, because the cache columns do NOT show a win:

   - **iterations/turn was IDENTICAL in every pair.** That is the safety
     result the gate needed, and the only one this n supports.
   - **The cache deltas are noise, not signal.** They swing by 3k-62k tokens
     in both directions while the gate moves 194-412. The agent chooses which
     files to read, and one different Read dwarfs the whole recall block. A
     paired A/B on a free-running agent CANNOT resolve a 200-400 token change
     — you would need the tool trajectory pinned, or roughly n>100 per arm.

   ## Pick a workload with NO tool variance, and the signal appears

   The three pairs above were repo questions, so the agent read files — and
   which files it chose swamped everything. Re-run on questions answerable
   ONLY from memory (the answer lives in a prior-session episode and no file
   in the repo contains it), and the tool-output variance disappears:

     pair          iters (a/c)   gate saved   Δcache-write   Δcache-read
     carrier           1 / 1         349 tok        -1,875          +380
     intent-taxonomy   2 / 2         423 tok          -508           +95

   Now cache-write moves DOWN in both, the predicted direction, and
   cache-read is flat to within 1% — i.e. the prefix is NOT being
   invalidated. Still only n=2, and both write deltas exceed the gate's own
   estimate, so residual variance remains; but the sign is right and the
   read column behaves exactly as the geometry says it should.

   Both arms also answered BOTH questions correctly and equivalently, which
   is the result that matters most: those answers were reachable only
   through recall, so a gate that had dropped the answer-bearing hit would
   have shown up as a wrong answer, not a smaller number.

   So: measure the token saving with `::recall-gate` (`gate-tok/turn`),
   which diffs the gated and ungated render over identical hits and is
   exact. Use the cache columns as a SAFETY check — iters/turn flat,
   cache-read flat — and only trust them on a tool-free workload.

   ## The one thing that CAN invalidate mid-turn

   `re-recall-after-tool-use` (`:enable-mid-turn-recall`, default off)
   rewrites `:recalled-memory` PART-WAY through a turn, which invalidates the
   user prefix from that iteration on. Turns where it fired are counted
   separately — averaging them in would blame the gate for its cost.

   Usage:
     bb scripts/recall_cache_report.clj [log-file] [--sessions] [--turns]

   Default log: ~/.brainyard/logs/agent-tui-app.log"
  (:require [clojure.string :as str]))

;; Event boundaries: a top-level form starts at column 0 with `{`. Chunk-and-
;; regex rather than edn/read, because the log carries `#mulog/flake` tags a
;; plain reader rejects — the same approach scripts/code_eval_stats.clj uses.
(def ^:private chunk-re #"(?m)^(?=\{:mulog/event-name)")

(defn- field
  "Read a scalar field from a chunk. Returns nil for a literal `nil` value.

   Trailing `,` AND `}` are both stripped: the LAST field of a record closes
   the map on the same line (`:value \"always\"}`), so stripping only the
   comma leaves a brace glued to the value and every mode reads as a distinct
   bucket."
  [chunk k]
  (let [m (re-find (re-pattern (str "(?m)^\\s*" k "\\s+(.+?)$")) chunk)
        v (some-> (second m) str/trim (str/replace #"[,}]+$" "") str/trim)]
    (when (and v (seq v) (not= v "nil")) v)))

(defn- num-field [chunk k]
  (some-> (field chunk k) (str/replace #"[^0-9-]" "") not-empty parse-long))

(defn- str-field [chunk k]
  (some-> (field chunk k) (str/replace #"^\"|\"$" "")))

(defn- event-of [chunk]
  (second (re-find #"\{:mulog/event-name\s+:([^\s,]+)" chunk)))

(defn- short-name [ev]
  (when ev (or (second (re-find #"/([^/]+)$" ev)) ev)))

(defn scan
  "Single pass over the log. Returns {:turns {[pid agent turn] {…}} :modes {pid mode}}.

   ## Why the join key is [pid agent-id turn-id] and not session-id

   `:session-id` is NOT on every event that matters here. Measured against a
   real log: `::dspy-completed` carries it, but `::recall-gate` and
   `::config-resolved` do NOT — the gate runs at BT step 2, before the session
   context is bound, and config resolution happens outside a session entirely.
   Joining on session-id silently drops every gate event and leaves every
   session's mode as \"?\", which reads as 'the feature never ran' when it
   in fact ran fine.

   `:pid` is on all three. `:agent-id` is on the two per-turn events, and is
   what keeps concurrent tabs in ONE long-lived TUI process from colliding on
   a shared turn number. Mode is per-process, so it keys on pid alone."
  [^String s]
  (reduce
   (fn [acc chunk]
     (let [ev    (short-name (event-of chunk))
           pid   (num-field chunk ":pid")
           agent (field chunk ":agent-id")
           turn  (num-field chunk ":turn-id")
           k     [pid agent turn]
           ok?   (and pid agent turn)]
       (case ev
         "config-resolved"
         (if (and pid (= ":recall-mode" (field chunk ":key")))
           (assoc-in acc [:modes pid] (or (str-field chunk ":value") "?"))
           acc)

         "dspy-completed"
         (if ok?
           (cond-> (-> acc
                       (update-in [:turns k :iters] (fnil inc 0))
                       (update-in [:turns k :cache-write]
                                  (fnil + 0) (or (num-field chunk ":cache-write-tokens") 0))
                       (update-in [:turns k :cache-read]
                                  (fnil + 0) (or (num-field chunk ":cache-read-tokens") 0)))
             ;; Only dspy-completed knows the session-id; carry it for display.
             (str-field chunk ":session-id")
             (assoc-in [:turns k :sid] (str-field chunk ":session-id")))
           acc)

         "recall-gate"
         (if ok?
           (-> acc
               (update-in [:turns k :gate-tokens-saved]
                          (fnil + 0) (or (num-field chunk ":est-tokens-saved") 0))
               (update-in [:turns k :gate-chars-saved]
                          (fnil + 0) (or (num-field chunk ":chars-saved") 0))
               (update-in [:turns k :hits-in]
                          (fnil + 0) (or (num-field chunk ":hits-in") 0))
               (update-in [:turns k :hits-kept]
                          (fnil + 0) (or (num-field chunk ":hits-kept") 0)))
           acc)

         "mid-turn-recall-fired"
         (if ok? (assoc-in acc [:turns k :mid-turn?] true) acc)

         acc)))
   {:turns {} :modes {}}
   (str/split s chunk-re)))

(defn- mean [xs]
  (if (seq xs) (/ (double (reduce + xs)) (count xs)) 0.0))

(defn- fmt [n]
  (let [n (long n)]
    (->> (str (abs n)) reverse (partition-all 3) (map #(apply str %))
         (str/join ",") reverse (apply str)
         (str (if (neg? n) "-" "")))))

(defn- report [{:keys [turns modes]} opts]
  (let [rows (for [[[pid agent turn] v] turns]
               (assoc v :pid pid :agent agent :turn turn
                      :sid (or (:sid v) (str "pid-" pid))
                      :mode (get modes pid "?")))
        rows (sort-by (juxt :mode :sid :turn) rows)]

    (when (:turns opts)
      (println "\nPER TURN")
      (println (format "  %-11s %-24s %5s %5s %12s %12s %10s %6s"
                       "mode" "session" "turn" "iters" "cache-write" "cache-read"
                       "gate-tok" "mid?"))
      (doseq [r rows]
        (println (format "  %-11s %-24s %5d %5d %12s %12s %10s %6s"
                         (:mode r) (:sid r) (:turn r) (or (:iters r) 0)
                         (fmt (or (:cache-write r) 0)) (fmt (or (:cache-read r) 0))
                         (fmt (or (:gate-tokens-saved r) 0))
                         (if (:mid-turn? r) "yes" "")))))

    (when (:sessions opts)
      (println "\nPER SESSION")
      (println (format "  %-11s %-24s %6s %6s %14s %14s"
                       "mode" "session" "turns" "iters" "cache-write" "cache-read"))
      (doseq [[[mode sid] rs] (sort (group-by (juxt :mode :sid) rows))]
        (println (format "  %-11s %-24s %6d %6d %14s %14s"
                         mode sid (count rs) (reduce + (keep :iters rs))
                         (fmt (reduce + (keep :cache-write rs)))
                         (fmt (reduce + (keep :cache-read rs)))))))

    (println "\nBY RECALL-MODE  (clean turns — mid-turn recall excluded)")
    (println (format "  %-12s %6s %6s %10s %16s %16s %14s"
                     "mode" "sess" "turns" "iters/turn" "cache-write/turn"
                     "cache-read/turn" "gate-tok/turn"))
    (doseq [[mode rs] (sort-by key (group-by :mode rows))]
      (let [clean (remove :mid-turn? rs)]
        (println (format "  %-12s %6d %6d %10.2f %16s %16s %14s"
                         mode
                         (count (distinct (map :sid clean)))
                         (count clean)
                         (mean (map #(or (:iters %) 0) clean))
                         (fmt (mean (map #(or (:cache-write %) 0) clean)))
                         (fmt (mean (map #(or (:cache-read %) 0) clean)))
                         (fmt (mean (map #(or (:gate-tokens-saved %) 0) clean)))))))

    (let [gated (filter :gate-tokens-saved rows)
          dirty (filter :mid-turn? rows)]
      (when (seq gated)
        (let [in (reduce + (keep :hits-in gated))
              kept (reduce + (keep :hits-kept gated))]
          (println (format "\nGATE   %d turns gated · %d/%d hits kept (%.0f%% dropped) · %s tokens saved total"
                           (count gated) kept in
                           (if (pos? in) (* 100.0 (/ (double (- in kept)) in)) 0.0)
                           (fmt (reduce + (keep :gate-tokens-saved gated)))))))
      (when (seq dirty)
        (println (format "NOTE   %d turn(s) fired mid-turn recall and are excluded above —\n       those rewrite the user prefix mid-turn and invalidate it from that iteration on."
                         (count dirty))))
      (when (empty? gated)
        (println "\nNOTE   no ::recall-gate events — every session ran :recall-mode \"always\".\n       Set BY_RECALL_MODE=conditional and collect a comparison run.")))

    (println (format "\n%d turns across %d session(s)."
                     (count rows) (count (distinct (map :sid rows)))))))

(let [args (vec *command-line-args*)
      flags (set (filter #(str/starts-with? % "--") args))
      path (or (first (remove #(str/starts-with? % "--") args))
               (str (System/getProperty "user.home")
                    "/.brainyard/logs/agent-tui-app.log"))
      f (java.io.File. ^String path)]
  (if-not (.exists f)
    (do (println "No log at" path) (System/exit 1))
    (do (println (format "RECALL / CACHE REPORT   %s (%.1f MB)"
                         path (/ (.length f) 1048576.0)))
        (report (scan (slurp f))
                {:turns (contains? flags "--turns")
                 :sessions (contains? flags "--sessions")}))))
