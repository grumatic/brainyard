;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: Apache-2.0
;; Licensed under the Apache License, Version 2.0 (the "License"); you may
;; not use this file except in compliance with the License. See LICENSE at
;; the repository root and CONTRIBUTING.md for contribution terms.

(ns ai.brainyard.agent-tui-tmux.core.stub
  "Recording stub for the Tmux protocol — used by tests of `by-ui` so we can
   exercise the orchestrator without a real tmux server."
  (:require [ai.brainyard.agent-tui-tmux.core.protocol :as p])
  (:import [java.util.concurrent.atomic AtomicLong]))

(defrecord StubTmux [!state]
  p/Tmux
  (version [_] (:version @!state))
  (probe-version [_] (:probe-version @!state))
  (running? [_] (:running? @!state))

  (new-session! [_ opts]
    (swap! !state (fn [s]
                    (-> s
                        (update :calls conj [:new-session opts])
                        (update :sessions (fnil conj []) (:name opts)))))
    (:name opts))

  (kill-session! [_ name]
    (swap! !state (fn [s]
                    (-> s
                        (update :calls conj [:kill-session name])
                        (update :sessions #(vec (remove #{name} %)))))))

  (list-sessions [_] (or (:sessions @!state) []))

  (new-window! [_ opts]
    (swap! !state update :calls conj [:new-window opts])
    (str (:target opts) ":" (:name opts)))

  (kill-window! [_ target]
    (swap! !state update :calls conj [:kill-window target]))

  (kill-pane! [_ target]
    (swap! !state update :calls conj [:kill-pane target]))

  (rename-window! [_ target new-name]
    (swap! !state update :calls conj [:rename-window target new-name]))

  (split-pane! [this opts]
    (let [pane-id (str "%" (.incrementAndGet ^AtomicLong (:pane-counter @!state)))]
      (swap! !state update :calls conj [:split-pane (assoc opts ::pane pane-id)])
      pane-id))

  (resize-pane! [_ opts]
    (swap! !state update :calls conj [:resize-pane opts]))

  (select-pane! [_ target]
    (swap! !state update :calls conj [:select-pane target]))

  (select-window! [_ target]
    (swap! !state update :calls conj [:select-window target]))

  (send-keys! [_ opts]
    (swap! !state update :calls conj [:send-keys opts]))

  (pipe-pane! [_ opts]
    (swap! !state update :calls conj [:pipe-pane opts]))

  (capture-pane [_ opts]
    (swap! !state update :calls conj [:capture-pane opts])
    (or (:capture-output @!state) ""))

  (display-popup! [_ opts]
    (swap! !state update :calls conj [:display-popup opts])
    (:popup-exit @!state 0))

  (set-option! [_ opts]
    (swap! !state update :calls conj [:set-option opts]))

  (display-message [_ opts]
    (swap! !state update :calls conj [:display-message opts])
    (or (:display-output @!state) ""))

  (signal! [_ name]
    (swap! !state update :calls conj [:signal name]))

  (run-shell [_ opts]
    (swap! !state update :calls conj [:run-shell opts])
    {:exit 0 :stdout "" :stderr ""}))

(defn create
  "Construct a StubTmux suitable for tests.  Options:
     :version       — string returned by version (default \"3.4\")
     :probe-version — [maj min] returned by probe-version (default [3 4])
     :running?      — bool (default true)
     :sessions      — initial vector of session names
     :capture-output — string returned from capture-pane
     :display-output — string returned from display-message"
  ([] (create {}))
  ([{:keys [version probe-version running? sessions capture-output display-output popup-exit]
     :or {version "3.4"
          probe-version [3 4]
          running? true
          sessions []
          popup-exit 0}}]
   (->StubTmux (atom {:version version
                      :probe-version probe-version
                      :running? running?
                      :sessions (vec sessions)
                      :capture-output capture-output
                      :display-output display-output
                      :popup-exit popup-exit
                      :pane-counter (AtomicLong. 0)
                      :calls []}))))

(defn calls
  "Return the recorded call vector.  Each entry is `[method opts-or-arg]`."
  [^StubTmux stub]
  (-> stub :!state deref :calls))

(defn calls-of
  "Return only the recorded calls of `method` (e.g. :new-window)."
  [stub method]
  (filter #(= method (first %)) (calls stub)))

(defn last-call
  ([stub] (last (calls stub)))
  ([stub method] (last (calls-of stub method))))

(defn reset-calls!
  "Clear the recorded call log."
  [^StubTmux stub]
  (swap! (:!state stub) assoc :calls []))
