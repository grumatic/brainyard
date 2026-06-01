;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: Apache-2.0
;; Licensed under the Apache License, Version 2.0 (the "License"); you may
;; not use this file except in compliance with the License. See LICENSE at
;; the repository root and CONTRIBUTING.md for contribution terms.

(ns ai.brainyard.agent-tui.permissions-test
  "Verify that make-permission-fn / make-user-feedback-fn dispatch to the popup
   path when in Mode B and to the in-stream path otherwise."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [ai.brainyard.agent-tui.permissions :as p]
            [ai.brainyard.agent-tui.popup :as popup]
            [ai.brainyard.agent-tui.session :as tui-session]
            [ai.brainyard.agent-tui.tmux-side :as tmux-side]
            [ai.brainyard.agent-tui-tmux.interface :as tmux-iface]))

(defn- with-mode-b-installed
  [f]
  (let [stub (tmux-iface/stub-tmux {:version "3.4"})]
    (try
      (with-redefs [;; Skip the live tmux pane probe.
                    ai.brainyard.agent-tui.tmux-side/current-pane-id
                    (fn [_] "%99")
                    ;; Pretend the client is tall enough for popups.
                    popup/feasible? (fn [_t] true)]
        (swap! tui-session/!tui-state assoc :mode :B)
        (tmux-side/install! {:tmux stub})
        (f stub))
      (finally
        (tmux-side/uninstall!)
        (swap! tui-session/!tui-state assoc :mode :A)))))

(use-fixtures :each
  (fn [t]
    (try (t)
         (finally
           (tmux-side/uninstall!)
           (swap! tui-session/!tui-state assoc :mode nil)))))

(deftest mode-b-permission-routes-through-popup
  (testing ":yes from popup ⇒ {:allowed true}"
    (with-mode-b-installed
      (fn [_stub]
        (with-redefs [popup/show! (fn [_t _q _opts]
                                    {:status :submitted
                                     :answers {:decision {:value :yes}}})]
          (let [pf (p/make-permission-fn (atom nil))
                r  (pf {:tool "Read" :path "/tmp/foo"})]
            (is (= {:allowed true} r)))))))

  (testing ":no from popup ⇒ {:denied true …}"
    (with-mode-b-installed
      (fn [_stub]
        (with-redefs [popup/show! (fn [_t _q _opts]
                                    {:status :submitted
                                     :answers {:decision {:value :no}}})]
          (let [pf (p/make-permission-fn (atom nil))
                r  (pf {:tool "Read" :path "/tmp/foo"})]
            (is (true? (:denied r)))
            (is (string? (:reason r))))))))

  (testing ":always from popup remembers the parent dir"
    (with-mode-b-installed
      (fn [_stub]
        (let [pf (p/make-permission-fn (atom nil))]
          (with-redefs [popup/show! (fn [_t _q _opts]
                                      {:status :submitted
                                       :answers {:decision {:value :always}}})]
            (is (= {:allowed true} (pf {:tool "Read" :path "/tmp/foo/a"}))))
          ;; Subsequent call inside the same parent dir bypasses the popup.
          (with-redefs [popup/show! (fn [& _]
                                      (throw (ex-info "popup must not be invoked" {})))]
            (is (= {:allowed true} (pf {:tool "Read" :path "/tmp/foo/b"}))))))))

  (testing "popup cancel ⇒ denied"
    (with-mode-b-installed
      (fn [_stub]
        (with-redefs [popup/show! (fn [_t _q _opts]
                                    {:status :cancelled :answers {}})]
          (let [pf (p/make-permission-fn (atom nil))
                r  (pf {:tool "Write" :path "/etc/passwd"})]
            (is (true? (:denied r)))
            (is (re-find #"cancelled" (:reason r)))))))))

(deftest mode-a-permission-uses-in-stream-path
  (testing "When mode is :A, popup is bypassed even if a side channel exists"
    (let [stub (tmux-iface/stub-tmux {:version "3.4"})]
      (with-redefs [ai.brainyard.agent-tui.tmux-side/current-pane-id
                    (fn [_] "%1")
                    popup/feasible? (fn [_t] true)
                    popup/show! (fn [& _]
                                  (throw (ex-info "popup must not be invoked" {})))]
        (swap! tui-session/!tui-state assoc :mode :A)
        (tmux-side/install! {:tmux stub})
        (let [pf (p/make-permission-fn (atom nil))
              ;; Non-raw mode (no input reader thread) → falls through to
              ;; auto-deny path with a hint, which doesn't call popup/show!.
              r  (pf {:tool "Read" :path "/tmp/x"})]
          (is (true? (:denied r)))
          (is (re-find #"non-interactive mode" (:reason r))))))))

(deftest mode-b-feedback-routes-through-popup
  (testing "submitted reply ⇒ {:selected … :index …}"
    (with-mode-b-installed
      (fn [_stub]
        (with-redefs [popup/show! (fn [_t _q _opts]
                                    {:status :submitted
                                     :answers {:feedback {:value 1}}})]
          (let [ff (p/make-user-feedback-fn (atom nil))
                r  (ff {:question "Pick" :options ["A" "B" "C"]})]
            (is (= "B" (:selected r)))
            (is (= 1 (:index r))))))))

  (testing "cancelled reply ⇒ nil"
    (with-mode-b-installed
      (fn [_stub]
        (with-redefs [popup/show! (fn [_t _q _opts]
                                    {:status :cancelled :answers {}})]
          (let [ff (p/make-user-feedback-fn (atom nil))]
            (is (nil? (ff {:question "Pick" :options ["A" "B"]})))))))))
