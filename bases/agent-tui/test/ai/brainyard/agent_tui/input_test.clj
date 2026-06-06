;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent-tui.input-test
  "Unit coverage for the unified raw-byte feedback interceptor
   (input/try-intercept-byte) across the three kinds — :confirm, :text,
   :select — plus a CJK round-trip through the shared text editor."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [ai.brainyard.agent-tui.input :as input]
            [ai.brainyard.agent-tui.layout :as layout]
            [ai.brainyard.agent-tui.session :as tui-session]
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

(deftest confirm-kind
  (testing "a choice key delivers {:value … :key …}"
    (let [pr (promise)]
      (reset! tui-session/!pending-feedback
              {:promise pr :kind :confirm :choices p/default-confirm-choices})
      (is (true? (input/try-intercept-byte (int \a))))
      (is (= {:value :always :key \a} @pr))))

  (testing "a non-choice key is not consumed"
    (let [pr (promise)]
      (reset! tui-session/!pending-feedback
              {:promise pr :kind :confirm :choices p/default-confirm-choices})
      (is (nil? (input/try-intercept-byte (int \z))))
      (is (not (realized? pr)))))

  (testing "a custom :never choice (key \\d, case-insensitive) delivers :never"
    (let [pr (promise)
          choices (conj (vec p/default-confirm-choices)
                        {:key \d :label "never" :value :never})]
      (reset! tui-session/!pending-feedback
              {:promise pr :kind :confirm :choices choices})
      (is (true? (input/try-intercept-byte (int \D))))   ;; uppercase still matches
      (is (= {:value :never :key \d} @pr)))))

(deftest text-kind
  (testing "typed line + Enter delivers {:input … :index 0}"
    (let [pr (promise)]
      (reset! tui-session/!pending-feedback
              {:promise pr :kind :text :mode :awaiting-text
               :buf (StringBuilder.) :free-idx 0 :options [{:label "Q"}]})
      (feed! "hi")
      (input/try-intercept-byte 13)            ;; Enter
      (is (= {:input "hi" :index 0} @pr)))))

(deftest text-kind-cjk-roundtrip
  (testing "a multi-byte UTF-8 (CJK) char survives the byte buffer"
    (let [pr (promise)
          ;; 日 = U+65E5 → UTF-8 E6 97 A5
          bs  [0xE6 0x97 0xA5]]
      (reset! tui-session/!pending-feedback
              {:promise pr :kind :text :mode :awaiting-text
               :buf (StringBuilder.) :free-idx 0 :options [{:label "Q"}]})
      (doseq [b bs] (input/try-intercept-byte b))
      (input/try-intercept-byte 13)
      (is (= {:input "日" :index 0} @pr)))))

(deftest select-kind
  (testing "a digit selects an option"
    (let [pr (promise)]
      (reset! tui-session/!pending-feedback
              {:promise pr :kind :select
               :options [{:label "A"} {:label "B"}]})
      (is (true? (input/try-intercept-byte (int \2))))
      (is (= {:selected "B" :index 1} @pr))))

  (testing "an out-of-range digit is not consumed"
    (let [pr (promise)]
      (reset! tui-session/!pending-feedback
              {:promise pr :kind :select
               :options [{:label "A"} {:label "B"}]})
      (is (not (true? (input/try-intercept-byte (int \5)))))
      (is (not (realized? pr))))))

(deftest no-pending-feedback
  (testing "no pending prompt ⇒ byte not intercepted"
    (reset! tui-session/!pending-feedback nil)
    (is (nil? (input/try-intercept-byte (int \y))))))
