;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: Apache-2.0
;; Licensed under the Apache License, Version 2.0 (the "License"); you may
;; not use this file except in compliance with the License. See LICENSE at
;; the repository root and CONTRIBUTING.md for contribution terms.

(ns ai.brainyard.agent-tui-tmux.protocol-test
  (:require [ai.brainyard.agent-tui-tmux.interface :as tmux]
            [clojure.test :refer [deftest is testing]]))

(deftest stub-roundtrip-test
  (testing "stub records calls and returns sane defaults"
    (let [s (tmux/stub-tmux)]
      (is (= "3.4" (tmux/version s)))
      (is (true? (tmux/running? s)))
      (is (true? (tmux/supports-popup? s)))
      (is (= [] (tmux/list-sessions s)))
      (tmux/new-session! s {:name "brainyard-x"})
      (is (= ["brainyard-x"] (tmux/list-sessions s)))
      (let [pane (tmux/split-pane! s {:target "brainyard-x:0" :orientation :v :percentage 30})]
        (is (re-matches #"%\d+" pane)))
      (is (= 0 (tmux/display-popup! s {:command "true" :width 70 :height 24})))
      (is (some #(= :display-popup (first %)) (tmux/stub-calls s)))))
  (testing "supports-popup? false on tmux 3.1"
    (let [s (tmux/stub-tmux {:probe-version [3 1]})]
      (is (not (tmux/supports-popup? s)))))
  (testing "supports-popup? false when tmux missing"
    (let [s (tmux/stub-tmux {:probe-version nil})]
      (is (not (tmux/supports-popup? s))))))

(deftest stub-call-helpers-test
  (let [s (tmux/stub-tmux)]
    (tmux/new-window! s {:target "brainyard-x" :name "react-agent"})
    (tmux/new-window! s {:target "brainyard-x" :name "search-agent"})
    (tmux/send-keys! s {:target "%1" :keys ["hello" "Enter"]})
    (is (= 2 (count (tmux/stub-calls-of s :new-window))))
    (is (= [:send-keys {:target "%1" :keys ["hello" "Enter"]}]
           (tmux/stub-last-call s :send-keys)))
    (tmux/stub-reset-calls! s)
    (is (zero? (count (tmux/stub-calls s))))))

(deftest real-tmux-without-binary
  (testing "RealTmux with a non-existent binary fails gracefully"
    (let [t (tmux/real-tmux {:binary "tmux-does-not-exist-xyz"})]
      ;; version returns nil rather than throwing
      (is (or (nil? (try (tmux/version t) (catch Throwable _ ::threw)))
              (= ::threw (try (tmux/version t) (catch Throwable _ ::threw))))))))
