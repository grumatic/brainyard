;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent-tui.input-test
  "Unit coverage for feedback dispatch across the three kinds — :confirm,
   :text, :select — over both halves of the contract: `handle-feedback-key!`
   for single-key answers and `handle-feedback-submit!` for typed lines.

   Every answer reaches these through the readline editor. Nothing reads raw
   bytes behind it: a byte-level collector for free-input answers used to, and
   its text was echoed straight at the terminal, so a resize — which repaints
   from the editor's buffer — erased what the user had typed."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.string]
            [ai.brainyard.agent-tui.input :as input]
            [ai.brainyard.agent-tui.layout :as layout]
            [ai.brainyard.agent-tui.session :as tui-session]
            [ai.brainyard.agent.interface :as agent]
            [ai.brainyard.agent-tui.permissions :as p]))

;; Silence terminal echo + block ops so dispatch runs hermetically.
(use-fixtures :each
  (fn [t]
    (with-redefs [layout/write-raw-chars!   (fn [_] nil)
                  layout/dispose-live-block! (fn [_] nil)
                  layout/draw-input-prompt! (fn [_] nil)
                  tui-session/emit!         (fn [& _] nil)]
      (try (t)
           (finally (reset! tui-session/!pending-feedback nil))))))

(defn- handle! [key]
  (input/handle-feedback-key! @tui-session/!pending-feedback key))

(defn- submit! [line]
  (input/handle-feedback-submit! line))

(deftest confirm-kind
  (testing "a choice key delivers {:value … :key …} (single-key fast-path)"
    (let [pr (promise)]
      (reset! tui-session/!pending-feedback
              {:promise pr :kind :confirm :choices p/default-confirm-choices})
      (is (true? (handle! "a")))
      (is (= {:value :always :key \a} @pr))))

  (testing "an invalid key is rejected (consumed, not delivered)"
    (let [pr (promise)]
      (reset! tui-session/!pending-feedback
              {:promise pr :kind :confirm :choices p/default-confirm-choices})
      (is (true? (handle! "z")))     ;; consumed (so it isn't typed into the line)
      (is (not (realized? pr)))))

  (testing "a custom :never choice (key \\d, case-insensitive) delivers :never"
    (let [pr (promise)
          choices (conj (vec p/default-confirm-choices)
                        {:key \d :label "never" :value :never})]
      (reset! tui-session/!pending-feedback
              {:promise pr :kind :confirm :choices choices})
      (is (true? (handle! "D")))     ;; uppercase still matches
      (is (= {:value :never :key \d} @pr)))))

(deftest text-kind-not-key-intercepted
  (testing ":text returns nil from handle-feedback-key! — the editor edits the line"
    (let [pr (promise)]
      (reset! tui-session/!pending-feedback {:promise pr :kind :text})
      (is (nil? (handle! "h")))
      (is (not (realized? pr))))))

(deftest text-kind-delivers-on-submit
  (testing ":text is answered by the line the editor submits, trimmed"
    (let [pr (promise)]
      (reset! tui-session/!pending-feedback {:promise pr :kind :text})
      (is (true? (submit! "  an answer  ")))
      (is (= {:input "an answer" :index 0} @pr))
      (is (nil? @tui-session/!pending-feedback)
          "the intercept window closes on delivery"))))

(deftest awaiting-text-kind
  (testing ":select :free-input answers with the submitted line, not raw bytes"
    (let [pr (promise)]
      (reset! tui-session/!pending-feedback
              {:promise pr :kind :select :mode :awaiting-text :free-idx 0
               :options [{:label "Q" :free-input true}]})
      (is (true? (submit! "hi")))
      (is (= {:selected "Q" :index 0 :input "hi"} @pr)))))

(deftest awaiting-text-cjk-roundtrip
  (testing "a CJK answer round-trips — the editor already decoded it"
    (let [pr (promise)]
      (reset! tui-session/!pending-feedback
              {:promise pr :kind :select :mode :awaiting-text :free-idx 0
               :options [{:label "Q" :free-input true}]})
      (is (true? (submit! "日本語")))
      (is (= {:selected "Q" :index 0 :input "日本語"} @pr)))))

(deftest submit-with-no-prompt-is-an-ordinary-turn
  (testing "no pending prompt ⇒ nil, so the editor returns the line as a turn"
    (reset! tui-session/!pending-feedback nil)
    (is (nil? (submit! "what is 2+2?"))))

  (testing "an already-answered prompt does not swallow the next turn"
    (let [pr (promise)]
      (deliver pr {:value :yes :key \y})
      (reset! tui-session/!pending-feedback
              {:promise pr :kind :confirm :choices p/default-confirm-choices})
      (is (nil? (submit! "what is 2+2?"))))))

(deftest submit-swallows-a-bare-enter-on-key-kinds
  (testing ":confirm / :select answer by keypress — Enter must not start a turn"
    (doseq [fb [{:promise (promise) :kind :confirm :choices p/default-confirm-choices}
                {:promise (promise) :kind :select :options [{:label "A"} {:label "B"}]}]]
      (reset! tui-session/!pending-feedback fb)
      (is (true? (submit! "")) "consumed, so the box clears and the prompt stays")
      (is (not (realized? (:promise fb))))
      (is (some? @tui-session/!pending-feedback)
          "the prompt is still open — a bare Enter is not an answer"))))

(deftest select-kind
  (testing "a digit selects an option (single-key fast-path)"
    (let [pr (promise)]
      (reset! tui-session/!pending-feedback
              {:promise pr :kind :select
               :options [{:label "A"} {:label "B"}]})
      (is (true? (handle! "2")))
      (is (= {:selected "B" :index 1} @pr))))

  (testing "out-of-range / non-digit is rejected (consumed, not delivered)"
    (let [pr (promise)]
      (reset! tui-session/!pending-feedback
              {:promise pr :kind :select
               :options [{:label "A"} {:label "B"}]})
      (is (true? (handle! "5")))     ;; out of range — consumed, not delivered
      (is (true? (handle! "x")))     ;; non-digit — consumed, not delivered
      (is (not (realized? pr)))))

  (testing "a :free-input option hands the typing to the editor"
    (let [pr (promise)
          repainted (atom 0)
          block-refreshed (atom 0)]
      (reset! tui-session/!pending-feedback
              {:promise pr :kind :select
               :options [{:label "A"} {:label "Other" :free-input true}]})
      (with-redefs [tui-session/redraw-idle-prompt! (fn [] (swap! repainted inc))
                    p/refresh-user-feedback-block! (fn [] (swap! block-refreshed inc))]
        (is (true? (handle! "2"))))  ;; picks the free-input option
      (is (= :awaiting-text (:mode @tui-session/!pending-feedback)))
      (is (= 1 @repainted) "the prompt hint flips without waiting for a keystroke")
      (is (= 1 @block-refreshed) "and so does the question block")
      (is (not (realized? pr)))
      ;; From here the keys are ordinary line editing — the editor holds them.
      (is (nil? (handle! "h")))
      (is (nil? (handle! "2")) "a digit is now text, not an option number")
      (is (true? (submit! "hi")))
      (is (= {:selected "Other" :index 1 :input "hi"} @pr)))))

(deftest awaiting-text-prompt-hint-names-the-option
  (testing "the input line asks for text, not an option number"
    (reset! tui-session/!pending-feedback
            {:promise (promise) :kind :select :mode :awaiting-text :free-idx 1
             :options [{:label "A"} {:label "Other" :free-input true}]})
    (let [{:keys [placeholder]} (tui-session/feedback-prompt-parts)]
      (is (clojure.string/includes? placeholder "Type your response"))
      (is (clojure.string/includes? placeholder "Other"))))

  (testing "before the pick it still asks for the option number"
    (reset! tui-session/!pending-feedback
            {:promise (promise) :kind :select
             :options [{:label "A"} {:label "Other" :free-input true}]})
    (is (clojure.string/includes? (:placeholder (tui-session/feedback-prompt-parts))
                                  "option number"))))

;; ---------------------------------------------------------------------------
;; ESC-to-pause: tips block, turn-in-flight gate, and the shared toggle.
;; ---------------------------------------------------------------------------

(deftest pause-tips-lines-shape
  (testing "the tips block lists ESC / type+Enter / Ctrl-C actions"
    (let [lines (#'input/pause-tips-lines)
          text  (clojure.string/join "\n" lines)]
      (is (seq lines))
      (is (re-find #"Paused" text))
      (is (re-find #"ESC" text))
      (is (re-find #"Enter" text))
      (is (re-find #"Ctrl-C" text)))))

(deftest turn-in-flight?-tracks-ask-threads
  (testing "true only while an ask thread is registered for the agent's tab"
    (with-redefs [tui-session/session-idx-for-agent (fn [_] 0)]
      (let [ag {:!state (atom {})}]
        (reset! input/!ask-threads {})
        (is (false? (input/turn-in-flight? ag)))
        (reset! input/!ask-threads {0 (Thread. (fn []))})
        (is (true? (input/turn-in-flight? ag)))
        (reset! input/!ask-threads {})))))

(deftest toggle-pause!-shows-and-hides-tips
  (let [paused?   (atom false)
        ag        {:!state (atom {})}
        shown     (atom [])
        disposed  (atom [])]
    (with-redefs [tui-session/get-active-agent (fn [] ag)
                  tui-session/update-status-bar! (fn [] nil)
                  agent/paused?     (fn [_] @paused?)
                  agent/pause-run   (fn [_] (reset! paused? true))
                  agent/resume-run  (fn [_] (reset! paused? false))
                  layout/fullscreen? (fn [] true)
                  layout/update-live-block! (fn [id lines & _] (swap! shown conj id))
                  layout/dispose-live-block! (fn [id] (swap! disposed conj id))]
      (testing "first toggle pauses and shows the sticky tips block"
        (input/toggle-pause!)
        (is (true? @paused?))
        (is (= [input/pause-tips-block-id] @shown))
        (is (empty? @disposed)))
      (testing "second toggle resumes and disposes the tips block"
        (input/toggle-pause!)
        (is (false? @paused?))
        (is (= [input/pause-tips-block-id] @disposed))))))
