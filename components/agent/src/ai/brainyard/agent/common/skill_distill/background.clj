;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.common.skill-distill.background
  "Off-turn execution for the self-improvement loop (R1 —
   docs/design/self-improve-design.md).

   Skill distillation and refinement both score a finished turn with an LLM
   call that takes tens of seconds. That must never run on the turn thread —
   but a bare `future` per job was the wrong off-ramp: unbounded (one sub-LM
   call per eligible turn, with no damper), invisible (no queue, no record),
   and killed on a daemon thread at JVM exit AFTER the tokens were spent.

   These jobs are submitted to the TASK MANAGER instead, as `:fn` tasks:

     bounded    — the manager's fixed pool caps how many scorers run at once
     visible    — `/task`, `task$detail`, and a per-task `output.log` under
                  `.brainyard/tasks/<id>/`, GC'd by the retention sweep, so a
                  scoring decision is auditable after the fact
     drainable  — `await-quiet!` gives in-flight jobs a grace period at exit
                  before `task-shutdown` cancels them
     single-flight — one job per (kind, key); a duplicate is dropped rather
                  than racing. Two concurrent scorers staging the SAME
                  proposal name would interleave `SKILL.md` and
                  `proposal.edn` from different runs.

   Deliberately NOT tagged `:coact/pending-from-iter`: that key is what puts a
   task on the model's in-flight surfaces (`coact-agent/in-flight-coact-tasks`,
   `harvest-pending-tasks!` and the iteration hold). Self-improvement is
   infrastructure — it must never enter the LLM's context or hold a turn.
   `:display-mode :background` likewise keeps it out of the TUI's per-task
   block.

   Fallback: if the manager can't be obtained or the submission fails, the job
   runs in a plain `future` — the pre-existing behaviour, kept as a safety net
   so a task-layer fault can never silently drop self-improvement work."
  (:require [ai.brainyard.agent.task.manager :as task-mgr]
            [ai.brainyard.agent.task.protocol :as tp]
            [ai.brainyard.mulog.interface :as mulog]))

(def ^:const kind-key
  "Metadata key marking a task as self-improvement work (`:distill` / `:refine`)."
  :self-improve/kind)

(def ^:const flight-key
  "Metadata key carrying the single-flight identity within a kind — the
   session-id for distillation, the skill name for refinement."
  :self-improve/key)

(def ^:private terminal-statuses #{:completed :failed :cancelled})

(def ^:const default-timeout-ms
  "Ceiling on one scoring job. Generous — a sub-LM drafting a full SKILL.md
   routinely runs a minute or more; this exists to reclaim a wedged job, not
   to bound normal work."
  300000)

(defn in-flight-tasks
  "Non-terminal self-improvement tasks. With `kind` / `k`, narrows to that
   single-flight identity (nil matches any). Returns [] when no manager exists
   — `peek-default-manager`, so a read never spins one up."
  ([] (in-flight-tasks nil nil))
  ([kind k]
   (if-let [mgr (task-mgr/peek-default-manager)]
     (try
       (filterv (fn [t]
                  (let [md (:metadata t)]
                    (and (some? (get md kind-key))
                         (not (terminal-statuses (:status t)))
                         (or (nil? kind) (= kind (get md kind-key)))
                         (or (nil? k)    (= (str k) (get md flight-key))))))
                (tp/list-tasks mgr))
       (catch Exception _ []))
     [])))

(defn run-off-turn!
  "Submit `thunk` as a background `:fn` task and return immediately.

   Options:
     :kind       — `:distill` | `:refine` (single-flight namespace)
     :key        — identity within the kind (session-id / skill name)
     :label      — human-readable task name (shown in `/task`)
     :thunk      — 0-arg fn; its return value becomes the task result
     :timeout-ms — job ceiling (default `default-timeout-ms`)

   Returns `:submitted` | `:duplicate` (one already in flight for this
   kind+key) | `:future` (no manager available — ran in a plain future) |
   `:error` (submission failed; the thunk was run in a future instead).
   Never throws — the caller is a hook."
  [& {:keys [kind key label thunk timeout-ms]
      :or   {timeout-ms default-timeout-ms}}]
  (let [started? (volatile! false)]
    (try
      ;; `get-default-manager` (not `peek`): it lazily auto-initializes, which is
      ;; the normal accessor everywhere else. Peeking here would silently fall
      ;; back to a `future` in every session where the user hadn't already run a
      ;; task — i.e. most of them — defeating the point of this namespace.
      (if-let [mgr (task-mgr/get-default-manager)]
        (if (seq (in-flight-tasks kind key))
          (do (mulog/log ::skipped-duplicate :kind kind :key (str key))
              :duplicate)
          (let [t (tp/create-task mgr
                                  (or label (str (name kind) " " key))
                                  :fn
                                  {:f thunk :label label :timeout-ms timeout-ms}
                                  {:metadata {:display-mode :background
                                              kind-key     kind
                                              flight-key   (str key)}})]
            (tp/start-task mgr (:id t))
            (vreset! started? true)
            (mulog/log ::submitted :kind kind :key (str key) :task-id (:id t))
            :submitted))
        (do (future (thunk)) :future))
      (catch Throwable e
        (mulog/warn ::submit-failed :kind kind :key (str key) :exception e)
        ;; Only re-run when the task never started — otherwise we'd double-run.
        (when-not @started?
          (try (future (thunk)) (catch Throwable _ nil)))
        :error))))

(defn await-quiet!
  "Block up to `timeout-ms` for in-flight self-improvement jobs to finish.
   Returns the number still running when the wait ended (0 = fully drained).

   Called from the TUI's `stop!` before `task-shutdown`, which CANCELS running
   tasks: the sub-LM spend is already incurred by then, so losing the result at
   the finish line is pure waste. Bounded — `/quit` must stay responsive, so a
   job that outruns the grace period is still cancelled."
  [timeout-ms]
  (let [deadline (+ (System/currentTimeMillis) (long timeout-ms))]
    (loop []
      (let [n (count (in-flight-tasks))]
        (cond
          (zero? n)                                  0
          (>= (System/currentTimeMillis) deadline)   n
          :else (do (Thread/sleep 100) (recur)))))))
