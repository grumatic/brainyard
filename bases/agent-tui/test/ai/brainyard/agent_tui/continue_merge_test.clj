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

(deftest continue-escapes-the-steering-note-path
  (testing "a paused run reads every typed line as a mid-run steering note, so
            the one command that ENDS a pause has to be exempted — or /pause's
            own advice is answered by handing the LLM the text \"/continue\""
    (is (commands/pause-exit-command? "/continue"))
    (is (commands/pause-exit-command? "  /continue  "))
    (is (commands/pause-exit-command? "/continue 40")))
  (testing "and the exemption is exactly that one command — steering with
            arbitrary text, slash-prefixed or not, is the default and stays"
    (is (not (commands/pause-exit-command? "/status")))
    (is (not (commands/pause-exit-command? "/continue-ish")))
    (is (not (commands/pause-exit-command? "keep going but skip the tests")))
    (is (not (commands/pause-exit-command? "")))))

(deftest resume-is-gone-from-every-surface-that-offers-commands
  (testing "the canonical registry — the source /help and autocomplete both read"
    (is (not-any? #(= "/resume" (first %)) fmt/command-registry))
    (is (some #(= "/continue" (first %)) fmt/command-registry)))
  (testing "and therefore the autocomplete menu, which is how it would still be
            suggested to someone who never read /help"
    (is (not-any? #(= "/resume" (first %)) (autocomplete/filter-commands "/res")))))
