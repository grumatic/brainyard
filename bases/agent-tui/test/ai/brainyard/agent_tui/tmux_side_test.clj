;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent-tui.tmux-side-test
  "Cover install!/uninstall! lifecycle and pane discovery against StubTmux."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [ai.brainyard.agent-tui.tmux-side :as tmux-side]
            [ai.brainyard.agent-tui-tmux.interface :as tmux-iface]))

(use-fixtures :each
  (fn [t]
    (try (t)
         (finally (tmux-side/uninstall!)))))

(deftest install-uninstall-roundtrip
  (testing "install! caches the impl + reads pane id from $TMUX_PANE"
    (let [stub (tmux-iface/stub-tmux {:version "3.4"})]
      (with-redefs [;; Pretend we're a renderer in pane %42.
                    ai.brainyard.agent-tui.tmux-side/current-pane-id
                    (fn [_t] "%42")]
        (let [s (tmux-side/install! {:tmux stub :session-dir "/tmp/by-test/sess-1"})]
          (is (true? (tmux-side/installed?)))
          (is (= "%42" (:host-pane s)))
          (is (= stub (:tmux s)))
          (is (= "/tmp/by-test/sess-1" (:session-dir s))))))

    (testing "uninstall! drops the impl"
      (tmux-side/uninstall!)
      (is (false? (tmux-side/installed?)))
      (is (nil? (:host-pane (tmux-side/state)))))))

(deftest uninstall-tolerates-no-install
  (testing "uninstall! is a no-op when not installed"
    (is (= :ok (tmux-side/uninstall!)))
    (is (false? (tmux-side/installed?)))))

(deftest installed?-reflects-state
  (testing "installed? is false before install! and true after"
    (is (false? (tmux-side/installed?)))
    (let [stub (tmux-iface/stub-tmux {})]
      (with-redefs [ai.brainyard.agent-tui.tmux-side/current-pane-id
                    (fn [_t] "%1")]
        (tmux-side/install! {:tmux stub})
        (is (true? (tmux-side/installed?)))))))

;; ---------------------------------------------------------------------------
;; Stage 1 wheel-to-arrow bindings (mouse workaround for tmux)
;; ---------------------------------------------------------------------------

(defn- args-of [stub method]
  (->> (tmux-iface/stub-calls-of stub method)
       (map (comp :args second))))

(defn- has-shell-args? [stub tokens]
  (some (fn [args] (every? (fn [t] (some #{t} args)) tokens))
        (args-of stub :run-shell)))

(deftest install-enables-mouse-and-binds-wheel
  (testing "install! sets mouse on, registers WheelUp/Down bindings, captures prior mouse"
    (let [stub (tmux-iface/stub-tmux {:version "3.4" :display-output "off"})]
      (with-redefs [ai.brainyard.agent-tui.tmux-side/current-pane-id
                    (fn [_t] "%42")]
        (let [s (tmux-side/install! {:tmux stub :session-dir "/tmp/by-test/sess-2"})]
          (is (= "off" (:prior-mouse s)))
          ;; mouse on emitted via set-option!
          (is (some (fn [[_ opts]]
                      (and (= "mouse" (:name opts))
                           (= "on" (str (:value opts)))
                           (= :global (:scope opts))))
                    (tmux-iface/stub-calls-of stub :set-option)))
          ;; bind-key WheelUpPane + WheelDownPane via run-shell
          (is (has-shell-args? stub ["bind-key" "WheelUpPane"]))
          (is (has-shell-args? stub ["bind-key" "WheelDownPane"]))
          ;; alt-screen guard present in at least one binding
          (is (has-shell-args? stub ["if-shell" "#{alternate_on}"]))
          ;; Target is `=` (in mouse-binding context tmux resolves it to
          ;; the wheel-event pane). Regression guard: NOT `{mouse}` —
          ;; tmux's command parser treats `{...}` as a brace-block and
          ;; would try to run `mouse` as a command ("unknown command:
          ;; mouse" at runtime).
          (is (has-shell-args? stub ["-t" "="]))
          (is (not (has-shell-args? stub ["-t" "{mouse}"]))
              "`{mouse}` triggers tmux's brace-block parser — use `=` instead")
          (is (has-shell-args? stub ["send-keys -t = Up"]))
          (is (has-shell-args? stub ["send-keys -t = Down"]))
          ;; Regression guard for the /log-pane wheel-up fix. The else
          ;; branch must enter copy-mode when the pane isn't on alt-screen
          ;; and isn't already in copy-mode; otherwise raw `send-keys -M`
          ;; just forwards a mouse byte that `tail -F` ignores. Mirrors
          ;; tmux's default WheelUpPane: in-mode → -M; else → copy-mode -et=.
          (is (has-shell-args? stub
                               ["if-shell -F -t = '#{pane_in_mode}' 'send-keys -M' 'copy-mode -et='"])
              "wheel-up else branch must enter copy-mode for non-alt-screen panes"))))))

(deftest uninstall-removes-bindings-and-restores-mouse
  (testing "uninstall! unbinds WheelUp/Down and restores the saved mouse value"
    (let [stub (tmux-iface/stub-tmux {:version "3.4" :display-output "off"})]
      (with-redefs [ai.brainyard.agent-tui.tmux-side/current-pane-id
                    (fn [_t] "%1")]
        (tmux-side/install! {:tmux stub})
        (tmux-iface/stub-reset-calls! stub)
        (tmux-side/uninstall!)
        (is (has-shell-args? stub ["unbind-key" "WheelUpPane"]))
        (is (has-shell-args? stub ["unbind-key" "WheelDownPane"]))
        ;; prior mouse "off" restored
        (is (some (fn [[_ opts]]
                    (and (= "mouse" (:name opts))
                         (= "off" (str (:value opts)))))
                  (tmux-iface/stub-calls-of stub :set-option)))))))

(deftest uninstall-skips-mouse-restore-when-prior-unknown
  (testing "uninstall! omits set-option when no prior mouse was captured"
    (let [stub (tmux-iface/stub-tmux {:version "3.4" :display-output ""})]
      (with-redefs [ai.brainyard.agent-tui.tmux-side/current-pane-id
                    (fn [_t] "%1")]
        (tmux-side/install! {:tmux stub})
        (is (nil? (:prior-mouse (tmux-side/state))))
        (tmux-iface/stub-reset-calls! stub)
        (tmux-side/uninstall!)
        ;; only unbind-key calls in run-shell
        (let [shells (tmux-iface/stub-calls-of stub :run-shell)]
          (is (every? #(some #{"unbind-key"} (-> % second :args)) shells)))
        ;; no set-option calls
        (is (empty? (tmux-iface/stub-calls-of stub :set-option)))))))

