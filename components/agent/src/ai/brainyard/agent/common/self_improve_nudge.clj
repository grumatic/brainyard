;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.common.self-improve-nudge
  "Per-turn nudge surfacing for the self-improvement loop (R1 — Phase 3,
   docs/design/self-improve-design.md).

   The skill-distillation hook (Phase 1) stages SKILL.md *proposals* under
   `.brainyard/skills/proposals/` asynchronously after a turn. Without a nudge
   the user has to remember to run `skill-proposal$list` to find them. This
   namespace surfaces a one-line notice — the moment a fresh proposal exists —
   on the next turn, riding the iteration `:notices` field the model already
   reads (the same channel `usage-nudge` uses). The model relays it to the user.

   Mechanism (mirrors `usage-nudge`):
   - At turn start (`maybe-queue!`, called from coact-init), when
     `:enable-self-improve-nudges` is true for a ROOT agent, compare the
     proposals on disk against the set already surfaced this session
     (`:self-improve-nudged` in the agent's cross-turn `st-memory-init`). Any
     FRESH proposal queues a notice into the per-turn `bt-st-memory`
     (`:pending-self-improve-notice`).
   - The coact iteration-record builder drains it via
     `drain-iteration-notice!`, attaching it to the record's `:notices`.

   The surfaced set is re-intersected with what's still on disk each turn, so a
   proposal that is accepted/rejected (and re-staged later) nudges again —
   while a still-pending one never re-nags."
  (:require [ai.brainyard.agent.common.skill-distill.proposals :as proposals]
            [ai.brainyard.agent.core.config :as config]
            [ai.brainyard.agent.core.protocol :as proto]
            [ai.brainyard.mulog.interface :as mulog]
            [clojure.set :as set]
            [clojure.string :as str]))

(defn pending-proposal-names
  "Set of staged proposal names under `project-dir` (empty set when none)."
  [project-dir]
  (into #{} (keep :name) (proposals/list-proposals project-dir)))

(defn render-notice
  "Render the one-line LLM-facing notice for a set of fresh proposal names."
  [names]
  (let [sorted (sort names)
        n      (count sorted)]
    (str "💡 Self-improvement: " n " skill proposal"
         (when (> n 1) "s") " awaiting review — "
         (str/join ", " (map #(str "`" % "`") sorted)) ". "
         "These were auto-distilled from recent work and are staged under "
         "`.brainyard/skills/proposals/`, NOT yet live. Tell the user they can "
         "review with `(skill-proposal$read :name <name>)` and promote with "
         "`(skill-proposal$accept :name <name>)` or discard with "
         "`(skill-proposal$reject :name <name>)`.")))

(defn compute-and-queue!
  "Core (no agent / no config): compare proposals on disk to the already-nudged
   set in `init-atom`, and when fresh ones exist queue a notice into
   `st-memory`. Returns :queued (with names), :none, or :no-store.

   `init-atom` carries `:self-improve-nudged` (a set of names) across turns;
   `st-memory` is the per-turn store the iteration builder drains."
  [project-dir init-atom st-memory]
  (if (or (nil? init-atom) (nil? st-memory))
    :no-store
    (let [pending (pending-proposal-names project-dir)
          ;; Re-intersect the previously-surfaced set with what's still on disk,
          ;; so accepted/rejected proposals drop out (a later re-stage nudges
          ;; again). Fresh = pending not yet surfaced.
          nudged  (set/intersection (set (:self-improve-nudged @init-atom)) pending)
          fresh   (set/difference pending nudged)]
      ;; Persist the pruned set EVERY call (not only when queueing) so deletions
      ;; self-heal — otherwise a removed proposal lingers in the surfaced set and
      ;; its re-stage would be silently suppressed forever.
      (swap! init-atom assoc :self-improve-nudged pending)
      (if (empty? fresh)
        :none
        (do (swap! st-memory assoc :pending-self-improve-notice (render-notice fresh))
            (mulog/log ::nudge-queued :fresh fresh)
            :queued)))))

(defn- root-agent?
  "True when `agent` has no parent — only root agents nudge (sub-agents share
   the session)."
  [agent]
  (try (nil? (get-in @(:!state agent) [:runtime :parent-agent]))
       (catch Exception _ false)))

(defn maybe-queue!
  "Turn-start entry point. No-op unless `:enable-self-improve-nudges` is true
   for a root `agent`. Resolves the project dir + cross-turn store and delegates
   to `compute-and-queue!`. Never throws."
  [agent st-memory]
  (try
    (when (and agent
               (root-agent? agent)
               (config/get-config agent :enable-self-improve-nudges))
      (compute-and-queue! (config/project-dir agent)
                          (proto/get-st-memory-init agent)
                          st-memory))
    (catch Exception e
      (mulog/warn ::maybe-queue-failed :error (.getMessage e))
      nil)))

(defn drain-iteration-notice!
  "Drain + clear this turn's queued self-improvement notice from `st-memory`,
   returning the notice string or nil. Called as the iteration record is built
   so the notice rides the very next iteration the model reads."
  [st-memory]
  (when st-memory
    (when-let [n (:pending-self-improve-notice @st-memory)]
      (swap! st-memory dissoc :pending-self-improve-notice)
      n)))
