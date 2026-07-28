;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.common.background
  "Off-turn execution for the agent's post-turn background work.

   Several hooks kick off work at turn end that is far too slow for the turn
   thread — skill distillation and refinement (an LLM scoring call, R1,
   docs/design/self-improve-design.md) and the memory consolidation cadence
   (an L2→L3 reduce that in graph mode does batch extraction plus per-community
   LLM summaries, docs/design/memory-agent-design.md §10.0). Each used a bare
   `future`, which was the wrong off-ramp: unbounded, invisible (no queue, no
   record), and killed on a daemon thread at JVM exit AFTER the cost was paid.

   These jobs are submitted to the TASK MANAGER instead, as `:fn` tasks:

     bounded    — the manager's fixed pool caps how many run at once
     visible    — `/task`, `task$detail`, and a per-task `output.log` under
                  `.brainyard/tasks/<id>/`, GC'd by the retention sweep, so
                  what a job decided is auditable after the fact
     drainable  — `await-quiet!` gives in-flight jobs a grace period at exit
                  before `task-shutdown` cancels them
     single-flight — one job per (kind, key); a duplicate is dropped rather
                  than racing. Two scorers staging the SAME proposal name would
                  interleave `SKILL.md` and `proposal.edn` from different runs;
                  two consolidations would reduce one session's L2→L3
                  concurrently.

   Deliberately NOT tagged `:coact/pending-from-iter`: that key is what puts a
   task on the model's in-flight surfaces (`coact-agent/in-flight-coact-tasks`,
   `harvest-pending-tasks!` and the iteration hold). This is infrastructure —
   it must never enter the LLM's context or hold a turn. `:display-mode
   :background` likewise keeps it out of the TUI's per-task block.

   Fallback: if the manager can't be obtained or the submission fails, the job
   runs in a plain `future` — the pre-existing behaviour, kept as a safety net
   so a task-layer fault can never silently drop the work."
  (:require [ai.brainyard.agent.task.manager :as task-mgr]
            [ai.brainyard.agent.task.protocol :as tp]
            [ai.brainyard.mulog.interface :as mulog]))

(def ^:const kind-key
  "Metadata key marking a task as agent background work: `:distill`,
   `:refine`, or `:consolidate`."
  :background/kind)

(def ^:const flight-key
  "Metadata key carrying the single-flight identity within a kind — the
   session-id for distillation and consolidation, the skill name for
   refinement."
  :background/key)

(def ^:private terminal-statuses #{:completed :failed :cancelled})

(def ^:const default-timeout-ms
  "Default ceiling on one background job, sized for a SCORING call — a sub-LM
   drafting a full SKILL.md routinely runs a minute or more. It exists to
   reclaim a wedged job, not to bound normal work.

   Jobs whose runtime is a different order of magnitude MUST pass their own
   `:timeout-ms` rather than inherit this. Graph consolidation does: a live run
   over an 88-episode window took 261s, 87% of this default, and a longer
   session would be cancelled mid-reduce — see
   `memory-agent.hooks/cadence-job-timeout-ms`."
  300000)

(defn in-flight-tasks
  "Non-terminal agent background tasks. With `kind` / `k`, narrows to that
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
     :kind       — `:distill` | `:refine` | `:consolidate` (single-flight
                   namespace)
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
  "Block up to `timeout-ms` for in-flight background jobs to finish. Returns
   the number still running when the wait ended (0 = fully drained).

   Called from the TUI's `stop!` before `task-shutdown`, which CANCELS running
   tasks: by then the LLM spend is already incurred, so losing the result at
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
