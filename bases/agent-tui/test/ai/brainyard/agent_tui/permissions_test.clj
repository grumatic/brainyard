;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent-tui.permissions-test
  "Verify the unified feedback primitive (make-feedback-fn) and the file-access
   permission adapter (make-permission-fn) dispatch to the tmux-popup backend in
   Mode B and to the in-stream/auto-deny path otherwise. Permission rides on the
   feedback primitive as a :confirm request, so its Mode-B path now goes through
   the generic feedback questionnaire (tab :feedback, choice :value)."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [ai.brainyard.agent-tui.permissions :as p]
            [ai.brainyard.agent-tui.popup :as popup]
            [ai.brainyard.agent-tui.session :as tui-session]
            [ai.brainyard.agent-tui.tmux-side :as tmux-side]
            [ai.brainyard.agent-tui-tmux.interface :as tmux-iface]))

(defn- permission-fn
  "Build a permission adapter wired to a fresh feedback primitive (non-raw)."
  []
  (p/make-permission-fn (atom nil) (p/make-feedback-fn (atom nil))))

(defn- with-mode-b-installed
  [f]
  (let [stub (tmux-iface/stub-tmux {:version "3.4"})
        ;; Capture the real resolver so the stub can delegate non-popup keys.
        real-get-config @#'ai.brainyard.agent.interface/get-config]
    (try
      (with-redefs [;; Skip the live tmux pane probe.
                    ai.brainyard.agent-tui.tmux-side/current-pane-id
                    (fn [_] "%99")
                    ;; Pretend the client is tall enough for popups.
                    popup/feasible? (fn [_t] true)
                    ;; Be hermetic about the popup toggle: an ambient
                    ;; .brainyard/config.edn (project or ~/.brainyard) may set
                    ;; :enable-tmux-popup false, which would make Mode B
                    ;; infeasible and route to the in-stream/stdin path (no TTY
                    ;; in CI ⇒ 60s timeout). Force the default-on value here;
                    ;; other keys delegate to the real resolver. The toggle
                    ;; test overrides this inline for its :false case.
                    ai.brainyard.agent.interface/get-config
                    (fn [& args]
                      (if (= (last args) :enable-tmux-popup)
                        true
                        (apply real-get-config args)))]
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
                                     :answers {:feedback {:value :yes}}})]
          (let [pf (permission-fn)
                r  (pf {:tool "Read" :path "/tmp/foo"})]
            (is (= {:allowed true} r)))))))

  (testing ":no from popup ⇒ {:denied true …}"
    (with-mode-b-installed
      (fn [_stub]
        (with-redefs [popup/show! (fn [_t _q _opts]
                                    {:status :submitted
                                     :answers {:feedback {:value :no}}})]
          (let [pf (permission-fn)
                r  (pf {:tool "Read" :path "/tmp/foo"})]
            (is (true? (:denied r)))
            (is (string? (:reason r))))))))

  (testing ":always from popup remembers the parent dir"
    (with-mode-b-installed
      (fn [_stub]
        (let [pf (permission-fn)]
          (with-redefs [popup/show! (fn [_t _q _opts]
                                      {:status :submitted
                                       :answers {:feedback {:value :always}}})]
            (is (= {:allowed true} (pf {:tool "Read" :path "/tmp/foo/a"}))))
          ;; Subsequent call inside the same parent dir bypasses the popup.
          (with-redefs [popup/show! (fn [& _]
                                      (throw (ex-info "popup must not be invoked" {})))]
            (is (= {:allowed true} (pf {:tool "Read" :path "/tmp/foo/b"}))))))))

  (testing ":never from popup denies and remembers the parent dir"
    (with-mode-b-installed
      (fn [_stub]
        (let [pf (permission-fn)]
          (with-redefs [popup/show! (fn [_t _q _opts]
                                      {:status :submitted
                                       :answers {:feedback {:value :never}}})]
            (is (true? (:denied (pf {:tool "Write" :path "/tmp/secret/a"})))))
          ;; Subsequent call inside the same parent dir is auto-denied — no popup.
          (with-redefs [popup/show! (fn [& _]
                                      (throw (ex-info "popup must not be invoked" {})))]
            (let [r (pf {:tool "Write" :path "/tmp/secret/b"})]
              (is (true? (:denied r)))
              (is (re-find #"won't ask again" (:reason r)))))))))

  (testing "popup cancel ⇒ denied"
    (with-mode-b-installed
      (fn [_stub]
        (with-redefs [popup/show! (fn [_t _q _opts]
                                    {:status :cancelled :answers {}})]
          (let [pf (permission-fn)
                r  (pf {:tool "Write" :path "/etc/passwd"})]
            (is (true? (:denied r)))
            (is (string? (:reason r)))))))))

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
        (let [pf (permission-fn)
              ;; Non-raw mode (no input reader thread) → falls through to
              ;; auto-deny path with a hint, which doesn't call popup/show!.
              r  (pf {:tool "Read" :path "/tmp/x"})]
          (is (true? (:denied r)))
          (is (re-find #"non-interactive mode" (:reason r))))))))

(deftest mode-b-feedback-routes-through-popup
  (testing "submitted reply ⇒ {:selected … :index …} (no :kind ⇒ :select)"
    (with-mode-b-installed
      (fn [_stub]
        (with-redefs [popup/show! (fn [_t _q _opts]
                                    {:status :submitted
                                     :answers {:feedback {:value 1}}})]
          (let [ff (p/make-feedback-fn (atom nil))
                r  (ff {:question "Pick" :options ["A" "B" "C"]})]
            (is (= "B" (:selected r)))
            (is (= 1 (:index r))))))))

  (testing "cancelled reply ⇒ nil"
    (with-mode-b-installed
      (fn [_stub]
        (with-redefs [popup/show! (fn [_t _q _opts]
                                    {:status :cancelled :answers {}})]
          (let [ff (p/make-feedback-fn (atom nil))]
            (is (nil? (ff {:question "Pick" :options ["A" "B"]})))))))))

(deftest confirm-kind-routes-through-popup
  (testing ":confirm in Mode B returns {:value … :key …}"
    (with-mode-b-installed
      (fn [_stub]
        (with-redefs [popup/show! (fn [_t _q _opts]
                                    {:status :submitted
                                     :answers {:feedback {:value :always}}})]
          (let [ff (p/make-feedback-fn (atom nil))
                r  (ff {:kind :confirm :question "Proceed?"})]
            (is (= :always (:value r)))
            (is (= \a (:key r)))))))))

(deftest enable-tmux-popup-toggle
  (testing "enable-tmux-popup false makes Mode B infeasible (falls back in-stream)"
    (with-mode-b-installed
      (fn [_stub]
        ;; default (true) → popup feasible in Mode B
        (is (true? (boolean (#'p/mode-b-popup-feasible?))))
        ;; toggle off → not feasible, even in Mode B with a popup-capable client
        (with-redefs [ai.brainyard.agent.interface/get-config
                      (fn [& args]
                        (if (= (last args) :enable-tmux-popup) false true))]
          (is (false? (boolean (#'p/mode-b-popup-feasible?)))))))))

(deftest select-guard-on-too-few-options
  (testing "in-stream select with <2 options errors"
    (let [ff (p/make-feedback-fn (atom nil))]
      (is (re-find #"2-6" (:error (ff {:question "Q" :options ["only"]})))))))
