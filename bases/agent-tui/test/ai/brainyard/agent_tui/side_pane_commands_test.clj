;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: Apache-2.0
;; Licensed under the Apache License, Version 2.0 (the "License"); you may
;; not use this file except in compliance with the License. See LICENSE at
;; the repository root and CONTRIBUTING.md for contribution terms.

(ns ai.brainyard.agent-tui.side-pane-commands-test
  "Verify the four Mode-B slash commands dispatch correctly.
   Mode A → friendly note (no tmux calls).
   Mode B → drives tmux_side, with calls observable via stub-tmux."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [ai.brainyard.agent-tui.side-pane-commands :as spc]
            [ai.brainyard.agent-tui.popup :as popup]
            [ai.brainyard.agent-tui.session :as tui-session]
            [ai.brainyard.agent-tui.tmux-side :as tmux-side]
            [ai.brainyard.agent-tui-tmux.interface :as tmux-iface]))

(use-fixtures :each
  (fn [t]
    (try (t)
         (finally
           (tmux-side/uninstall!)
           (swap! tui-session/!tui-state assoc :mode nil)))))

(defn- with-mode-b
  [f]
  (let [stub (tmux-iface/stub-tmux {:version "3.4"})]
    (with-redefs [ai.brainyard.agent-tui.tmux-side/current-pane-id
                  (fn [_] "%99")]
      (swap! tui-session/!tui-state assoc :mode :B)
      (tmux-side/install! {:tmux stub :session-dir "/tmp/by-test/sess-x"})
      (f stub))))

(deftest activity-show-mode-a-emits-note
  (testing "Mode A → /activity show returns :continue and does NOT install side channel"
    (swap! tui-session/!tui-state assoc :mode :A)
    (is (= :continue (spc/handle-activity-command "show")))
    (is (false? (tmux-side/installed?)))))

(deftest activity-show-mode-b-splits-pane
  (testing "Mode B → /activity show calls tmux split-pane with right/30 default"
    (with-mode-b
      (fn [stub]
        (is (= :continue (spc/handle-activity-command "show")))
        (let [calls (tmux-iface/stub-calls-of stub :split-pane)]
          (is (= 1 (count calls)))
          (let [opts (second (first calls))]
            (is (= "%99" (:target opts)))
            (is (= :h (:orientation opts)))
            (is (= 30 (:percentage opts)))
            (is (re-find #"cat .*\.fifo" (:command opts))))))))

  (testing "Mode B → /activity show bottom -p 50 honours direction + percentage"
    (with-mode-b
      (fn [stub]
        (spc/handle-activity-command "show bottom -p 50")
        (let [opts (second (last (tmux-iface/stub-calls-of stub :split-pane)))]
          (is (= :v (:orientation opts)))
          (is (= 50 (:percentage opts))))))))

(deftest activity-hide-mode-b-kills-pane
  (testing "Mode B → show then hide → kill-pane invoked on the recorded pane id"
    (with-mode-b
      (fn [stub]
        (spc/handle-activity-command "show")
        (spc/handle-activity-command "hide")
        (let [kills (tmux-iface/stub-calls-of stub :kill-pane)]
          (is (>= (count kills) 1)))))))

(deftest log-show-and-hide
  (testing "/log show + hide round-trip"
    (with-mode-b
      (fn [stub]
        (is (= :continue (spc/handle-log-command "show")))
        (is (some? (:log (tmux-side/state))))
        (is (= :continue (spc/handle-log-command "hide")))
        (is (nil? (:log (tmux-side/state))))
        (is (>= (count (tmux-iface/stub-calls-of stub :split-pane)) 1))))))

(deftest log-show-uses-tail-f-not-fifo
  (testing "/log show spawns `tail -F /tmp/agent-tui-app.log`"
    (with-mode-b
      (fn [stub]
        (spc/handle-log-command "show")
        (let [opts (second (last (tmux-iface/stub-calls-of stub :split-pane)))]
          (is (re-find #"tail -F .*agent-tui-app\.log" (:command opts)))
          (is (not (re-find #"\.fifo" (:command opts)))))))))

(deftest scrollback-dump-mode-b-calls-capture
  (testing "/scrollback dump invokes capture-pane on the host pane"
    (with-mode-b
      (fn [stub]
        ;; The stub returns "" from capture-pane by default (so capture-host-pane!
        ;; writes an empty file). We just want to confirm the call was made.
        (spc/handle-scrollback-command "dump")
        (let [calls (tmux-iface/stub-calls-of stub :capture-pane)]
          (is (= 1 (count calls)))
          (let [opts (second (first calls))]
            (is (= "%99" (:target opts)))
            (is (= "-" (:start opts)))))))))

(deftest popup-test-mode-a-emits-note-only
  (testing "/popup test in Mode A → no popup invocation"
    (swap! tui-session/!tui-state assoc :mode :A)
    (let [called? (atom false)]
      (with-redefs [popup/show! (fn [& _] (reset! called? true) nil)]
        (spc/handle-popup-command "test")
        (is (false? @called?))))))

(deftest popup-test-mode-b-routes-through-popup
  (testing "/popup test in Mode B → popup/show! invoked once"
    (with-mode-b
      (fn [_stub]
        (let [calls (atom 0)]
          (with-redefs [popup/show! (fn [& _]
                                      (swap! calls inc)
                                      {:status :submitted
                                       :answers {:feedback {:value 1}}})]
            (spc/handle-popup-command "test"))
          (is (= 1 @calls)))))))
