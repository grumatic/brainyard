;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent-tui.continue-merge-test
  "`/resume` is gone; `/continue` covers both ways a run stops.

   The merge is only safe because the two states are mutually exclusive by
   construction — a paused run is LIVE and parked on a condition, an exhausted
   one is IDLE and finished — so what these tests pin is the branch choice and
   the fact that neither `/resume` nor the old `continue-agent` survives
   anywhere a user or the autocomplete menu can still reach."
  (:require [ai.brainyard.agent-tui.commands :as commands]
            [ai.brainyard.agent-tui.autocomplete :as autocomplete]
            [ai.brainyard.agent-tui.input :as input]
            [ai.brainyard.agent-tui.session :as tui-session]
            [ai.brainyard.agent.interface :as agent]
            [ai.brainyard.agent.interface.tui.format :as fmt]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(def ^:private handle-continue #'commands/handle-continue-command)
(def ^:private continue-iterations #'commands/continue-iterations)

(defn- capture
  "Run `f` with a stub active agent, collecting what it emitted."
  [ag f]
  (let [out (atom [])]
    (with-redefs [tui-session/get-active-agent (constantly ag)
                  tui-session/emit! (fn [& args] (swap! out conj (first args)))
                  tui-session/update-status-bar! (constantly nil)]
      (f))
    (str/join "\n" @out)))

(defn- paused-agent []
  (let [!state (atom {})]
    (agent/pause-run !state)
    {:!state !state}))

(deftest a-paused-run-unparks
  (let [ag   (paused-agent)
        seen (atom nil)]
    (with-redefs [ai.brainyard.agent-tui.input/hide-pause-tips! (constantly nil)]
      (let [out (capture ag #(handle-continue ""))]
        (reset! seen out)
        (is (str/includes? out "[resumed]"))
        (is (not (agent/paused? (:!state ag))))))
    (is (some? @seen))))

(deftest the-paused-branch-wins-over-a-stale-exhaustion
  (testing "a run parked RIGHT NOW outranks an :iterations-exhausted flag left
            by an earlier turn — the live agent is the urgent one, and
            re-asking would start a second turn on top of the parked one"
    (let [ag       (paused-agent)
          re-asked (atom false)]
      (with-redefs [ai.brainyard.agent-tui.input/hide-pause-tips! (constantly nil)]
        (with-redefs [commands/continue-iterations (fn [_] (reset! re-asked true))]
          (capture ag #(handle-continue ""))))
      (is (false? @re-asked))
      (is (not (agent/paused? (:!state ag)))))))

(deftest an-iteration-count-on-a-paused-run-is-reported-not-swallowed
  (let [ag (paused-agent)]
    (with-redefs [ai.brainyard.agent-tui.input/hide-pause-tips! (constantly nil)]
      (let [out (capture ag #(handle-continue "40"))]
        (testing "a user who typed a number meant something by it"
          (is (str/includes? out "40"))
          (is (str/includes? out "ignoring")))
        (testing "and the run still unparks — the number is the aside, not the ask"
          (is (str/includes? out "[resumed]"))
          (is (not (agent/paused? (:!state ag)))))))))

(deftest an-unpaused-run-takes-the-iterations-branch
  (let [ag   {:!state (atom {})}
        args (atom ::none)]
    (with-redefs [commands/continue-iterations (fn [a] (reset! args a))]
      (capture ag #(handle-continue "40")))
    (is (= "40" @args))))

(deftest no-agent-says-so-rather-than-branching
  (is (str/includes? (capture nil #(handle-continue "")) "No TUI agent running")))

(deftest the-ways-out-of-a-pause-escape-the-steering-note-path
  (testing "a paused run reads every typed line as a mid-run steering note, so
            the two commands that END one have to be exempted — or /pause's own
            advice is answered by handing the LLM the text \"/continue\", and
            /quit cannot quit"
    (is (input/pause-passthrough-command? "/continue"))
    (is (input/pause-passthrough-command? "  /continue  "))
    (is (input/pause-passthrough-command? "/continue 40"))
    (is (input/pause-passthrough-command? "/quit")))
  (testing "/pause passes through too — as a steering note it would RESUME the
            run, the exact opposite of what was typed"
    (is (input/pause-passthrough-command? "/pause")))
  (testing "the allow-list is short on purpose — several commands would be
            actively wrong mid-pause, /clear on a session with a live parked
            run above all"
    (is (not (input/pause-passthrough-command? "/clear")))
    (is (not (input/pause-passthrough-command? "/status")))
    (is (not (input/pause-passthrough-command? "/continue-ish"))))
  (testing "and \"anything slash-prefixed\" is not available either: an absolute
            path is a normal thing to steer with, and parses as a command"
    (is (not (input/pause-passthrough-command? "/tmp/foo.txt is the file")))
    (is (not (input/pause-passthrough-command? "keep going but skip the tests")))
    (is (not (input/pause-passthrough-command? "")))))

(def ^:private handle-pause #'commands/handle-pause-run-command)

(deftest pause-refuses-when-nothing-is-running
  (let [ag {:!state (atom {})}]
    (with-redefs [input/turn-in-flight? (constantly false)]
      (let [out (capture ag #(handle-pause ""))]
        (testing "arming the flag on an idle agent produced a session reporting
                  `paused` with nothing parked, which then swallowed the user's
                  NEXT question as a steering note for a run that did not exist"
          (is (str/includes? out "Nothing to pause"))
          (is (not (agent/paused? (:!state ag)))))))))

(deftest pause-still-works-on-a-live-turn
  (let [ag {:!state (atom {})}]
    (with-redefs [input/turn-in-flight? (constantly true)]
      (let [out (capture ag #(handle-pause ""))]
        (is (str/includes? out "use /continue to resume"))
        (is (agent/paused? (:!state ag)))))))

(deftest pausing-an-already-paused-run-says-so
  (let [ag (paused-agent)]
    (with-redefs [input/turn-in-flight? (constantly true)]
      (let [out (capture ag #(handle-pause ""))]
        (testing "re-emitting the plain advice would read as though something
                  had happened"
          (is (str/includes? out "already")))))))

(deftest the-guard-covers-the-pause-direction-only
  (testing "a resume must stay reachable whatever `turn-in-flight?` says — an
            agent left paused by an older build has no turn in flight either,
            and refusing to unpause it is the one outcome with no way out"
    (let [ag (paused-agent)]
      (with-redefs [input/turn-in-flight? (constantly false)
                    tui-session/get-active-agent (constantly ag)
                    tui-session/emit! (constantly nil)
                    tui-session/update-status-bar! (constantly nil)
                    ai.brainyard.agent-tui.input/hide-pause-tips! (constantly nil)]
        (input/toggle-pause!))
      (is (not (agent/paused? (:!state ag)))))))

(deftest ctrl-backslash-refuses-on-an-idle-agent
  (testing "ESC already declined this case by pre-checking `turn-in-flight?`;
            Ctrl-\\ went straight to `toggle-pause!` and armed the flag"
    (let [ag  {:!state (atom {})}
          out (atom [])]
      (with-redefs [input/turn-in-flight? (constantly false)
                    tui-session/get-active-agent (constantly ag)
                    tui-session/emit! (fn [& a] (swap! out conj (first a)))
                    tui-session/update-status-bar! (constantly nil)]
        (input/toggle-pause!))
      (is (str/includes? (str/join "\n" @out) "Nothing to pause"))
      (is (not (agent/paused? (:!state ag)))))))

(deftest every-passthrough-command-that-does-something-is-advertised
  (let [tips (str/join "\n" (#'input/pause-tips-lines 200))]
    (testing "the tips block is the only thing on screen while paused, so a
              command that passes through and acts, but is not listed there, is
              a way out nobody can find"
      (doseq [{:keys [cmd tip]} input/pause-passthrough-commands
              :when tip]
        (is (str/includes? tips cmd) (str cmd " missing from the pause tips"))))
    (testing "and one that only declines is kept off the panel — listing it
              would advertise a no-op"
      (doseq [{:keys [cmd tip]} input/pause-passthrough-commands
              :when (nil? tip)]
        (is (not (str/includes? tips cmd)) (str cmd " should not be advertised"))))))

(deftest resume-is-gone-from-every-surface-that-offers-commands
  (testing "the canonical registry — the source /help and autocomplete both read"
    (is (not-any? #(= "/resume" (first %)) fmt/command-registry))
    (is (some #(= "/continue" (first %)) fmt/command-registry)))
  (testing "and therefore the autocomplete menu, which is how it would still be
            suggested to someone who never read /help"
    (is (not-any? #(= "/resume" (first %)) (autocomplete/filter-commands "/res")))))
