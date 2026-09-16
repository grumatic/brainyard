;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent-tui.tmux-side-test
  "Cover install!/uninstall! lifecycle and pane discovery against StubTmux."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
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
;; Wheel-to-arrow bindings (the fallback for a TUI with the mouse off)
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
          ;; Target is `=` (in mouse-binding context tmux resolves it to
          ;; the wheel-event pane). Regression guard: NOT `{mouse}` —
          ;; tmux's command parser treats `{...}` as a brace-block and
          ;; would try to run `mouse` as a command ("unknown command:
          ;; mouse" at runtime).
          (is (has-shell-args? stub ["-t" "="]))
          (is (not (has-shell-args? stub ["-t" "{mouse}"]))
              "`{mouse}` triggers tmux's brace-block parser — use `=` instead")
          ;; The arrow translation itself, now one level in: it is the
          ;; alt-screen branch of the nested if-shell rather than the outer
          ;; condition, so it reads as a substring of a binding's else-arg.
          (is (some (fn [args] (some #(str/includes? % "send-keys -t = Up") args))
                    (args-of stub :run-shell)))
          (is (some (fn [args] (some #(str/includes? % "send-keys -t = Down") args))
                    (args-of stub :run-shell)))
          ;; Regression guard for the stolen-wheel fix. These bindings are
          ;; SERVER-global, so a pane that turned mouse reporting on — Claude
          ;; Code in a sibling pane, or this TUI with `:enable-mouse` at its
          ;; default — must get the real event, never a bare arrow. That means
          ;; `#{mouse_any_flag}` is the FIRST condition and `#{alternate_on}`
          ;; only decides among the panes that did not ask for the mouse.
          (is (has-shell-args? stub ["if-shell" "#{||:#{mouse_any_flag},#{pane_in_mode}}" "send-keys -M"])
              "a pane that asked for the mouse must get `send-keys -M`, not an arrow")
          ;; And the /log-pane wheel-up fix it replaced: the last resort for a
          ;; pane that is neither mouse-driven nor on the alt-screen is
          ;; copy-mode, not a raw `send-keys -M` that `tail -F` ignores.
          (is (has-shell-args? stub
                               ["if-shell -F -t = '#{alternate_on}' 'send-keys -t = Up' 'copy-mode -et='"])
              "wheel-up else branch must enter copy-mode for non-alt-screen panes"))))))

(deftest uninstall-removes-bindings-and-restores-mouse
  (testing "uninstall! unbinds WheelUp/Down and restores the saved mouse value"
    (let [stub (tmux-iface/stub-tmux {:version "3.4" :display-output "off"})]
      (with-redefs [ai.brainyard.agent-tui.tmux-side/current-pane-id
                    (fn [_t] "%1")]
        (tmux-side/install! {:tmux stub})
        (tmux-iface/stub-reset-calls! stub)
        (tmux-side/uninstall!)
        ;; The stub reports no prior binding, so unbinding IS the whole of
        ;; restoring: both keys were unbound before we arrived.
        (is (has-shell-args? stub ["unbind-key" "WheelUpPane"]))
        (is (has-shell-args? stub ["unbind-key" "WheelDownPane"]))
        (is (not (has-shell-args? stub ["source-file"]))
            "nothing was bound before install!, so there is nothing to replay")
        ;; prior mouse "off" restored
        (is (some (fn [[_ opts]]
                    (and (= "mouse" (:name opts))
                         (= "off" (str (:value opts)))))
                  (tmux-iface/stub-calls-of stub :set-option)))))))

(deftest uninstall-restores-the-wheel-bindings-it-replaced
  (testing "uninstall! replays the bind-key lines that were there before install!"
    ;; These bindings are SERVER-global. Unbinding on the way out — which this
    ;; used to do — left every OTHER pane on the server with no wheel at all
    ;; until the user re-sourced their tmux.conf, so what was there has to come
    ;; back, not merely go away.
    (let [stub     (tmux-iface/stub-tmux {:version "3.4" :display-output "off"})
          saved    "bind-key -T root WheelUpPane if -F \"#{alternate_on}\" \"send -M\" \"copy-mode -e\""
          replayed (atom nil)
          orig     tmux-iface/run-shell]
      (with-redefs [ai.brainyard.agent-tui.tmux-side/current-pane-id
                    (fn [_t] "%7")
                    ;; Delegate to the stub so calls are still recorded, and
                    ;; only dress the two commands that need real output: the
                    ;; `list-keys` install! captures with, and the `source-file`
                    ;; uninstall! replays with — read here because the temp file
                    ;; is deleted the moment the replay returns.
                    tmux-iface/run-shell
                    (fn [t {:keys [args] :as opts}]
                      (let [r (orig t opts)]
                        (cond
                          (and (= "list-keys" (first args)) (= "WheelUpPane" (last args)))
                          (assoc r :stdout (str saved "\n"))

                          (= "source-file" (first args))
                          (do (reset! replayed (slurp (second args))) r)

                          :else r)))]
        (tmux-side/install! {:tmux stub})
        (is (= saved (:prior-wheel (tmux-side/state)))
            "install! must capture the binding it is about to replace")
        (tmux-iface/stub-reset-calls! stub)
        (tmux-side/uninstall!)
        ;; Unbind FIRST — WheelDownPane had no saved line and restoring it
        ;; means leaving it unbound — then replay what was captured.
        (is (has-shell-args? stub ["unbind-key" "WheelUpPane"]))
        (is (has-shell-args? stub ["unbind-key" "WheelDownPane"]))
        (is (has-shell-args? stub ["source-file"]))
        (is (= saved (some-> @replayed str/trim))
            "the replayed file must carry the captured line verbatim")))))

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

