;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.effect.interface
  "Functional effects as values — brainyard's seam onto missionary.

   An effect is a Task (exactly one value, or an error) or a Flow (many values
   over time). Both are plain functions of callbacks; nothing runs until
   something runs it, and running returns a canceller.

   Why a brick rather than requiring `missionary.core` everywhere:

     1. The native-image class-init carve-out is a hard constraint that must
        not be rediscovered by whoever adds the second call site — see
        `docs/design/functional-effect-system.md` §7 and the deps.edn note.
     2. `m/blk` vs `m/cpu` vs 'inline on the caller' is the single most common
        way to get missionary wrong, and it is a policy decision, not a
        per-call-site preference. One brick, one answer.
     3. A brick nobody requires costs nothing, so the migration stays
        incremental.

   WHAT IS NOT HERE: the coroutine macros (`m/sp`, `m/ap`, `m/?`, `m/?>`,
   `m/?<`, `m/amb`, `m/via`). A macro cannot be re-exported without becoming a
   second thing to keep in sync with the original, and these are the part of
   missionary least likely to need a brainyard-shaped wrapper. Require
   `[missionary.core :as m]` directly for those; use this interface for
   running, policy, lifecycle and the bridges onto brainyard's existing
   `future`/`promise`/atom surfaces."
  (:require [ai.brainyard.effect.core.flows :as flows]
            [ai.brainyard.effect.core.policy :as policy]
            [ai.brainyard.effect.core.prim :as prim]
            [ai.brainyard.effect.core.smoke :as smoke]
            [ai.brainyard.effect.core.supervisor :as supervisor]))

;; ============================================================================
;; Running
;; ============================================================================

(defn run
  "Run `task`; returns its canceller (a 0-arg fn)."
  [task success failure]
  (prim/run task success failure))

(defn run!!
  "Run `task` and BLOCK. Returns `{:ok v}` / `{:err e}` / `{:timeout true}`.
   For the edges only — inside an effect use `m/?`."
  ([task] (prim/run!! task))
  ([task timeout-ms] (prim/run!! task timeout-ms)))

(defn run-detached
  "Fire and forget, logging failure. Returns the canceller."
  ([task] (prim/run-detached task))
  ([task label] (prim/run-detached task label)))

;; ============================================================================
;; Adopting already-started effects
;; ============================================================================

(defn from-future
  "Adopt a running `java.util.concurrent.Future` as a Task. Cancelling the Task
   cancels the future too."
  [fut]
  (prim/from-future fut))

(defn from-promise
  "Adopt a Clojure promise as a Task. Cancelling abandons the wait; it cannot
   cancel the producer."
  [p]
  (prim/from-promise p))

(defn task-of
  "Lift a plain thunk into a Task on `m/blk`, conveying the caller's dynamic
   bindings — the drop-in shape of `(future …)`. The macro-free entry into the
   effect world, which is what makes effects usable from the SCI sandbox where
   `m/sp`/`m/ap` cannot work (design §8 Q1).

   Read `conveying-note` before putting a dynamic var anywhere near an `m/sp`
   body: conveyance into a thunk is not the same as surviving a park, and the
   difference is timing-dependent and silent (§8 Q4)."
  [f]
  (prim/task-of f))

(def conveying-note
  "Why there is no general `conveying` wrapper, the measured mid-body binding
   hazard inside `m/sp`, and the rule that avoids it. See §8 Q4."
  prim/conveying-note)

(defn success
  "A Task that immediately succeeds with `v`."
  [v]
  (prim/success v))

(defn failure
  "A Task that immediately fails with `e`."
  [e]
  (prim/failure e))

;; ============================================================================
;; Policy
;; ============================================================================

(defn timeout
  "Fail (or fall back to `fallback`) after `ms`, cancelling the task."
  ([task ms] (policy/timeout task ms))
  ([task ms fallback] (policy/timeout task ms fallback)))

(defn retry-backoff
  "Retry with exponential backoff + jitter. See
   `ai.brainyard.effect.core.policy/retry-backoff` for opts."
  [opts task]
  (policy/retry-backoff opts task))

(defn bounded
  "Run `tasks` at most `n` at a time, preserving input order. `pmap`'s
   replacement."
  [n tasks]
  (policy/bounded n tasks))

(defn all
  "Run every task in parallel; a failure cancels the siblings."
  [tasks]
  (policy/all tasks))

(defn race
  "First to settle wins; the rest are cancelled."
  [& tasks]
  (apply policy/race tasks))

(defn cancelled?
  "True when `e` is `missionary.Cancelled`."
  [e]
  (policy/cancelled? e))

;; ============================================================================
;; Flows
;; ============================================================================

(defn ticker
  "Discrete Flow of 0, 1, 2, … every `ms`. Replaces a daemon ticker thread."
  [ms]
  (flows/ticker ms))

(defn sample-lines
  "Flow of newly-completed lines appended to a `StringWriter`, sampled."
  [writer ms]
  (flows/sample-lines writer ms))

(defn watch-flow
  "Continuous Flow of an atom's value (optionally mapped through `f`)."
  ([!atom] (flows/watch-flow !atom))
  ([!atom f] (flows/watch-flow !atom f)))

(defn debounce
  "Emit only after `ms` of quiet; supersede-and-cancel semantics."
  [ms flow]
  (flows/debounce ms flow))

;; ============================================================================
;; Supervision
;; ============================================================================

(defn start!
  "Run `task` under `label`, cancelling any incumbent. Idempotent. Returns the
   canceller."
  [label task]
  (supervisor/start! label task))

(defn stop!
  "Cancel the process under `label`. Idempotent."
  [label]
  (supervisor/stop! label))

(defn stop-all!
  "Cancel every supervised process. The exit path's single call."
  []
  (supervisor/stop-all!))

(defn running
  "Labels currently supervised, with uptime."
  []
  (supervisor/running))

;; ============================================================================
;; Native gate
;; ============================================================================

(defn smoke-report
  "Run the missionary native gate; returns `{:pass? bool :checks [...]}`."
  []
  (smoke/run-smoke!))

(defn smoke-main!
  "Run the gate, print the report, `System/exit` 0 or 1. Hidden
   `by effect-smoke` entry point."
  []
  (smoke/-print-and-exit!))
