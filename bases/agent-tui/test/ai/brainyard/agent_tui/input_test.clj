;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent-tui.input-test
  "Unit coverage for the unified raw-byte feedback interceptor
   (input/try-intercept-byte) across the three kinds — :confirm, :text,
   :select — plus a CJK round-trip through the shared text editor."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.string]
            [ai.brainyard.agent-tui.input :as input]
            [ai.brainyard.agent-tui.layout :as layout]
            [ai.brainyard.agent-tui.session :as tui-session]
            [ai.brainyard.agent.interface :as agent]
            [ai.brainyard.agent-tui.permissions :as p]))

;; Silence terminal echo + block ops so the interceptor runs hermetically.
(use-fixtures :each
  (fn [t]
    (with-redefs [layout/write-raw-chars!   (fn [_] nil)
                  layout/dispose-live-block! (fn [_] nil)
                  tui-session/emit!         (fn [& _] nil)]
      (try (t)
           (finally (reset! tui-session/!pending-feedback nil))))))

(defn- feed! [bytes]
  (doseq [b bytes] (input/try-intercept-byte (int b))))

(defn- handle! [key]
  (input/handle-feedback-key! @tui-session/!pending-feedback key))

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

(deftest text-kind-passes-through
  (testing ":text is no longer byte-intercepted — bytes flow to the readline
            editor (sticky input line), which captures + delivers on submit"
    (let [pr (promise)]
      (reset! tui-session/!pending-feedback {:promise pr :kind :text})
      (is (nil? (input/try-intercept-byte (int \h))))
      (is (nil? (input/try-intercept-byte 13)))   ;; Enter not consumed here
      (is (not (realized? pr))))))

(deftest awaiting-text-kind
  (testing ":select :free-input typed line + Enter delivers :input via the byte editor"
    (let [pr (promise)]
      (reset! tui-session/!pending-feedback
              {:promise pr :kind :select :mode :awaiting-text
               :buf (StringBuilder.) :free-idx 0
               :options [{:label "Q" :free-input true}]})
      (feed! "hi")
      (input/try-intercept-byte 13)            ;; Enter
      (is (= {:selected "Q" :index 0 :input "hi"} @pr)))))

(deftest awaiting-text-cjk-roundtrip
  (testing "a multi-byte UTF-8 (CJK) char survives the byte buffer"
    (let [pr (promise)
          ;; 日 = U+65E5 → UTF-8 E6 97 A5
          bs  [0xE6 0x97 0xA5]]
      (reset! tui-session/!pending-feedback
              {:promise pr :kind :select :mode :awaiting-text
               :buf (StringBuilder.) :free-idx 0
               :options [{:label "Q" :free-input true}]})
      (doseq [b bs] (input/try-intercept-byte b))
      (input/try-intercept-byte 13)
      (is (= {:selected "Q" :index 0 :input "日"} @pr)))))

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

  (testing "a :free-input option transitions to byte-driven text collection"
    (let [pr (promise)]
      (reset! tui-session/!pending-feedback
              {:promise pr :kind :select
               :options [{:label "A"} {:label "Other" :free-input true}]})
      (is (true? (handle! "2")))     ;; picks the free-input option
      (is (= :awaiting-text (:mode @tui-session/!pending-feedback)))
      (is (not (realized? pr)))
      (feed! "hi")                   ;; now the byte layer collects the text
      (input/try-intercept-byte 13)
      (is (= {:selected "Other" :index 1 :input "hi"} @pr)))))

(deftest try-intercept-byte-only-awaiting-text
  (testing "confirm/select/text bytes pass through (handled by the editor)"
    (reset! tui-session/!pending-feedback
            {:promise (promise) :kind :confirm :choices p/default-confirm-choices})
    (is (nil? (input/try-intercept-byte (int \y))))
    (reset! tui-session/!pending-feedback
            {:promise (promise) :kind :select :options [{:label "A"}]})
    (is (nil? (input/try-intercept-byte (int \1))))
    (reset! tui-session/!pending-feedback {:promise (promise) :kind :text})
    (is (nil? (input/try-intercept-byte (int \h)))))
  (testing "no pending prompt ⇒ byte not intercepted"
    (reset! tui-session/!pending-feedback nil)
    (is (nil? (input/try-intercept-byte (int \y))))))

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
