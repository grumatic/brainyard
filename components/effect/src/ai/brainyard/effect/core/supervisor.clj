;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.effect.core.supervisor
  "One registry for every long-lived effect, keyed by label.

   Brainyard has 27 `.setDaemon true` sites — 27 processes whose shutdown story
   is 'the JVM is a daemon-thread graveyard, and hopefully the right teardown
   hook found this one'. `create-task-manager`'s own docstring says it out
   loud: daemon-ness governs whether the JVM *waits*, and 'does NOT kill
   subprocesses a task spawned via ProcessBuilder … An app's exit path MUST
   call `tp/shutdown`'.

   Every started effect returns a canceller. Putting them in one place makes
   shutdown a single call over a known set, instead of a promise that each
   subsystem remembered to register its own hook. This is worth having on its
   own, independently of anything else in the migration.

   Labels are unique: starting under a label that is already live cancels the
   incumbent first. That is deliberate — it makes `start!` idempotent, which is
   the property every one of the hand-rolled tickers spends a `when-not @!x`
   guard to obtain."
  (:require [ai.brainyard.effect.core.prim :as prim]
            [ai.brainyard.mulog.interface :as mulog]))

;; label -> {:cancel (fn []) :started-at ms}
;; An empty map is safe to bake into a native image; nothing here holds a
;; resource acquired at build time.
(defonce ^:private !processes (atom {}))

(declare stop!)

(defn start!
  "Run `task` under `label` and register its canceller. Returns the canceller.

   Cancels any incumbent under the same label first, so this is idempotent —
   call it every turn without a guard.

   Failure is logged, not thrown: a supervised process is by definition one
   nobody is awaiting, so there is no caller to hand an exception to. A process
   that ends (successfully or not) deregisters itself, which is what keeps
   `running` honest rather than a list of things that used to be true."
  [label task]
  (stop! label)
  (let [deregister! (fn [] (swap! !processes dissoc label))
        cancel (prim/run task
                         (fn [_] (deregister!))
                         (fn [e]
                           (deregister!)
                           (when-not ((requiring-resolve
                                       'ai.brainyard.effect.core.policy/cancelled?) e)
                             (mulog/warn ::supervised-effect-failed
                                         :label label :exception e))))]
    (swap! !processes assoc label {:cancel cancel
                                   :started-at (System/currentTimeMillis)})
    cancel))

(defn running?
  "Is a process currently registered under `label`?"
  [label]
  (contains? @!processes label))

(defn ensure!
  "Start `task` under `label` only if nothing is running there. Returns true
   when it started one, false when an incumbent was left alone.

   Distinct from `start!`, and the distinction is the whole reason both exist.
   `start!` means *replace* — cancel the incumbent, run this. `ensure!` means
   *make sure one is running* — leave the incumbent alone.

   Every one of the seven TUI tickers wants `ensure!`: each is called on the
   event that creates the thing it animates, so `start-think-block-ticker!`
   fires on every new think block. Under `start!` semantics that would cancel
   and relaunch the ticker mid-animation each time — a visible hiccup, and
   pure churn. Their hand-rolled `(when-not @!x-thread …)` guard was `ensure!`
   spelled out; this is the same idea without the thread atom.

   `task` is only constructed by the caller either way, so passing a task that
   is not started costs nothing — it is a value."
  [label task]
  (if (running? label)
    false
    (do (start! label task) true)))

(defn stop!
  "Cancel the process registered under `label`, if any. Returns true when
   something was cancelled. Idempotent."
  [label]
  (when-let [{:keys [cancel]} (get @!processes label)]
    (swap! !processes dissoc label)
    (try (cancel) (catch Throwable t
                    (mulog/warn ::supervised-cancel-failed
                                :label label :exception t)))
    true))

(defn stop-all!
  "Cancel every registered process. The exit path's single call. Returns the
   number cancelled."
  []
  (let [labels (keys @!processes)]
    (doseq [l labels] (stop! l))
    (count labels)))

(defn running
  "Labels currently registered, with how long each has been up. Diagnostic —
   the thing that does not exist today for the 27 daemon threads."
  []
  (let [now (System/currentTimeMillis)]
    (into {} (map (fn [[l {:keys [started-at]}]]
                    [l {:uptime-ms (- now started-at)}]))
          @!processes)))
