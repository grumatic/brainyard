;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.effect.core.flows
  "Flow constructors for the shapes brainyard builds by hand today.

   The recurring pattern being replaced is: a `Thread.` + `setDaemon` + `loop`
   + `Thread/sleep` + an idempotent `when-not @!ticker-thread` start guard + a
   `reset!` to nil on exit + a self-stop check. That is ~20 lines of lifecycle
   per ticker, written five times in `agent_tui/session.clj` alone.

   A Flow has none of it, because the lifecycle inverts: the CONSUMER holds the
   canceller. Nothing needs to detect that it should stop — whoever started it
   stops it, and a Flow that nobody runs costs nothing."
  (:require [ai.brainyard.effect.core.prim :as prim]
            [missionary.core :as m]))

(defn ticking
  "A Task that calls `tick!`, sleeps `ms`, and repeats for as long as `tick!`
   returns truthy. Completes with nil once it returns falsey.

   This is the shape all seven TUI tickers hand-rolled: do the work, sleep,
   repeat, stop when there is nothing left to animate. What it removes at each
   site is the lifecycle — a `Thread.`, `setDaemon`, `setName`, an
   idempotence guard on a thread atom, and a `reset!` to nil on exit — none of
   which was ever the point.

   `tick!` runs on `m/blk`, NOT on the thread driving the coroutine. After the
   first park that thread is the single process-wide `missionary scheduler`,
   which every timer in the process shares; rendering a live block there would
   let one slow repaint delay every other ticker, task timeout and LLM
   backoff. The scheduler only ever enqueues.

   Work-then-sleep, matching six of the seven. A ticker that wants to sleep
   first should say so at its own call site rather than turn this into a
   function with a mode."
  [ms tick!]
  (m/sp (loop []
          (when (m/? (prim/task-of tick!))
            (m/? (m/sleep ms))
            (recur)))))

(defn ticker
  "A discrete Flow emitting 0, 1, 2, … every `ms`, forever.

   Discrete (pull-driven), not continuous: the next tick is produced when the
   consumer asks for it, so a slow consumer cannot accumulate a backlog of
   stale frames. For a spinner that is exactly right — a frame nobody rendered
   is a frame nobody needed."
  [ms]
  (m/ap (loop [i 0]
          (m/? (m/sleep ms))
          (m/amb i (recur (inc i))))))

(defn sample-lines
  "A Flow of newly-completed lines appended to `writer` (a `StringWriter`),
   sampled every `ms`. Tracks its own read offset; emits only up to the last
   newline, so a partial line is never surfaced twice.

   This is the ONE thing today's detach watcher gets right and the migration
   must keep: sampling is the correct model for a growing buffer. What the
   watcher gets wrong is using the same poll to detect COMPLETION, which a Task
   reports directly."
  [^java.io.StringWriter writer ms]
  (m/ap
   (let [!offset (atom 0)]
     (m/? (m/sleep ms))
     (loop []
       ;; `long` hints: `@!offset` is an Object to the compiler, so the
       ;; comparison and arithmetic below box and warn under the project's
       ;; *warn-on-reflection*.
       (let [^String s (.toString writer)
             offset    (long @!offset)
             len       (long (count s))]
         (if (> len offset)
           (let [^String fresh (subs s offset)
                 last-nl       (.lastIndexOf fresh (int \newline))]
             (if (>= last-nl 0)
               (let [chunk (subs fresh 0 (inc last-nl))]
                 (reset! !offset (+ offset (inc last-nl)))
                 (m/amb chunk (do (m/? (m/sleep ms)) (recur))))
               (do (m/? (m/sleep ms)) (recur))))
           (do (m/? (m/sleep ms)) (recur))))))))

(defn watch-flow
  "A continuous Flow of `(f @!atom)`, emitting on change.

   `m/watch` is the effect-world equivalent of `add-watch`, minus the
   registration/deregistration bookkeeping — dropping the flow deregisters.
   Continuous, so a consumer sees the LATEST value rather than every
   intermediate one, which is what a status bar or a spinner wants and what
   `add-watch` cannot express without its own coalescing."
  ([!atom] (m/watch !atom))
  ([!atom f] (m/latest f (m/watch !atom))))

(defn debounce
  "Emit a value from `flow` only once `ms` has passed with no newer value.

   The canonical demonstration that cancellation is first-class: `m/?<`
   switches to the newest value, which CANCELS the in-flight sleep of the
   superseded one, and the `Cancelled` branch emits `(m/amb)` — nothing.
   Note that this is stronger than a core.async sliding buffer, which only
   drops QUEUED items: a value already in flight survives a sliding buffer and
   does not survive this."
  [ms flow]
  (m/ap (let [x (m/?< flow)]
          (try (m/? (m/sleep ms x))
               (catch missionary.Cancelled _ (m/amb))))))
