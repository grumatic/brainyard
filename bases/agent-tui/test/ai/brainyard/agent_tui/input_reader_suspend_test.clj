;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent-tui.input-reader-suspend-test
  "Stopping the input reader is a SUSPEND, and must not look like a closed
   terminal.

   The reader thread announces `:eof` on its way out. That sentinel means the
   tty ended: `read-key!` maps it to -1 → `:ctrl-d`, and the readline loop
   returns nil on an empty buffer, which ends the session with
   `System/exit 0`. So a deliberate stop that enqueued it quit the user's
   session — measured against a live TUI, whose `main` was parked in
   `LinkedBlockingQueue.take` when `stop-input-reader!` ran.

   Whether it happened at all was a race with a 5 ms poll: interrupted inside
   `Thread/sleep`, the exception clears the interrupt flag and the put
   succeeds (spurious quit); interrupted while runnable, the flag is still set
   and the put — then outside the try — threw, killing the thread silently and
   leaving the parked reader waiting forever. Both branches were wrong, so the
   loop below stops the reader many times to cover both landings.

   Needs a controlling terminal (the reader opens /dev/tty). Skipped where
   there is none."
  (:require [ai.brainyard.agent-tui.input :as input]
            [clojure.test :refer [deftest is testing]])
  (:import [java.util.concurrent LinkedBlockingQueue TimeUnit]))

(defn- tty? []
  (try (with-open [_ (java.io.FileInputStream. "/dev/tty")] true)
       (catch Throwable _ false)))

(deftest a-suspend-never-announces-eof
  (if-not (tty?)
    (is true "no controlling terminal — reader-thread behaviour not exercised here")
    (testing "no start/stop cycle enqueues the sentinel that ends the session"
      (let [seen (atom [])]
        (dotimes [i 40]
          (input/start-input-reader! System/in)
          ;; Alternate the moment of the interrupt: immediately (the reader is
          ;; still starting / runnable) and after it has settled into its 5 ms
          ;; sleep. Pre-fix these two produced the hang and the quit
          ;; respectively.
          (when (odd? i) (Thread/sleep (long 8)))
          (input/stop-input-reader!)
          ;; Give a dying thread longer than its poll interval to do whatever
          ;; it was going to do to the queue.
          (Thread/sleep (long 15))
          (swap! seen conj (.size ^LinkedBlockingQueue input/!raw-input-queue)))
        (is (every? zero? @seen)
            (str "a suspended reader left something in the queue: " @seen))))))

(deftest a-parked-reader-is-left-parked-not-woken-with-eof
  (if-not (tty?)
    (is true "no controlling terminal — reader-thread behaviour not exercised here")
    (testing "the case that quit a live session: a taker waiting through the stop"
      (input/start-input-reader! System/in)
      (Thread/sleep (long 10))
      (let [got (atom ::still-waiting)
            taker (doto (Thread.
                         ^Runnable
                         (fn []
                           (try
                             (when-let [item (.poll ^LinkedBlockingQueue
                                              input/!raw-input-queue
                                                    500 TimeUnit/MILLISECONDS)]
                               (reset! got item))
                             (catch InterruptedException _))))
                    (.setDaemon true)
                    (.start))]
        (Thread/sleep (long 20))
        (input/stop-input-reader!)
        (.join taker 1000)
        (is (= ::still-waiting @got)
            (str "a suspend must not hand the parked reader a keystroke — got " @got))
        (is (not= :eof @got)
            "…least of all :eof, which read-key! turns into :ctrl-d and a quit")))))
