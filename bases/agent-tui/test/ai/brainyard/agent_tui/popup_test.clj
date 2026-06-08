;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent-tui.popup-test
  "Cover the popup helper's questionnaire rendering, byte→option matching,
   and feasibility predicate. Real `display-popup!` shellouts are exercised
   via integration; here we drive a stub backend."
  (:require [clojure.test :refer [deftest is testing]]
            [ai.brainyard.agent-tui.popup :as popup]
            [ai.brainyard.agent-tui-tmux.interface :as tmux-iface])
  (:import [java.io File]))

(defn- yn-questionnaire []
  (tmux-iface/permission-questionnaire {:tool "Read" :path "/tmp/foo"}))

(deftest render-text-includes-prompt-and-options
  (testing "single-tab radio render carries title, prompt, and labelled options"
    (let [q (yn-questionnaire)
          s (popup/render-text q)]
      (is (re-find #"Permission required" s))
      (is (re-find #"Allow Read to access /tmp/foo" s))
      (is (re-find #"Yes" s))
      (is (re-find #"No" s))
      (is (re-find #"Always" s))
      (is (re-find #"Tab" s))
      (is (re-find #"Enter" s))
      (is (re-find #"Esc" s)))))

(defn- result-path-from-command
  "Extract the result-file path the popup script will write to. Both the radio
   and text commands end with `… \"result\" \"timeout_secs\"`, so the result
   file is the SECOND-TO-LAST whitespace-separated argument (stripped of its
   surrounding double quotes)."
  [^String command]
  (-> command
      clojure.string/trim
      (clojure.string/split #"\s+")
      butlast                ;; drop the trailing timeout_secs arg
      last
      (clojure.string/replace #"^\"|\"$" "")))

(deftest show!-stub-cancel-and-shortcut-paths
  (testing "Stub returning a non-zero exit ⇒ cancelled"
    (let [stub (tmux-iface/stub-tmux {:popup-exit 130})
          reply (popup/show! stub (yn-questionnaire) {})]
      (is (= :cancelled (:status reply)))))

  (testing "When the popup writes 'y' to the result file ⇒ submitted :yes"
    ;; The recipe runs a bash script; the stub's `display-popup!` doesn't
    ;; execute the shell, so we simulate by overriding the impl to copy a
    ;; pre-baked answer into the result file before returning 0.
    (let [fake (reify ai.brainyard.agent-tui-tmux.core.protocol/Tmux
                 (display-popup! [_ {:keys [command]}]
                   (spit (result-path-from-command command) "y")
                   0)
                 (display-message [_ _] "")
                 (probe-version [_] [3 4])
                 (version [_] "3.4")
                 (running? [_] true))
          reply (popup/show! fake (yn-questionnaire) {})]
      (is (= :submitted (:status reply)))
      (is (= :yes (get-in reply [:answers :decision :value])))))

  (testing "Digit selection: '2' picks the second option (No)"
    (let [fake (reify ai.brainyard.agent-tui-tmux.core.protocol/Tmux
                 (display-popup! [_ {:keys [command]}]
                   (spit (result-path-from-command command) "2")
                   0)
                 (display-message [_ _] "")
                 (probe-version [_] [3 4])
                 (version [_] "3.4")
                 (running? [_] true))
          reply (popup/show! fake (yn-questionnaire) {})]
      (is (= :submitted (:status reply)))
      (is (= :no (get-in reply [:answers :decision :value]))))))

(defn- fake-writing
  "A Tmux impl whose display-popup! writes `s` to the script's result file and
   exits 0 — used to drive show!'s reply parsing without a real shellout."
  [s]
  (reify ai.brainyard.agent-tui-tmux.core.protocol/Tmux
    (display-popup! [_ {:keys [command]}]
      (spit (result-path-from-command command) s)
      0)
    (display-message [_ _] "")
    (probe-version [_] [3 4])
    (version [_] "3.4")
    (running? [_] true)))

(deftest show!-text-entry-paths
  (let [text-q (tmux-iface/text-questionnaire {:question "Name the release?"})]
    (testing "text popup writes 'S<input>' ⇒ submitted with :input"
      (let [reply (popup/show! (fake-writing "Shello world") text-q {})]
        (is (= :submitted (:status reply)))
        (is (= "hello world" (get-in reply [:answers :answer :input])))))

    (testing "empty submit ('S') ⇒ submitted with empty :input"
      (let [reply (popup/show! (fake-writing "S") text-q {})]
        (is (= :submitted (:status reply)))
        (is (= "" (get-in reply [:answers :answer :input])))))

    (testing "blank result ⇒ cancelled"
      (let [reply (popup/show! (fake-writing "") text-q {})]
        (is (= :cancelled (:status reply)))))

    (testing "non-zero popup exit ⇒ cancelled"
      (let [stub (tmux-iface/stub-tmux {:popup-exit 130})]
        (is (= :cancelled (:status (popup/show! stub text-q {}))))))

    (testing "text popup writes 'T' ⇒ timeout"
      (is (= :timeout (:status (popup/show! (fake-writing "T") text-q {})))))))

(deftest show!-radio-timeout
  (testing "radio popup writes '__TO__' ⇒ timeout"
    (is (= :timeout (:status (popup/show! (fake-writing "__TO__") (yn-questionnaire) {}))))))

(deftest feasible?-checks-version-and-height
  (testing "tmux 3.1 is too old"
    (let [stub (tmux-iface/stub-tmux {:version "3.1"})]
      (is (false? (popup/feasible? stub)))))

  (testing "tmux 3.4 with tall client is fine"
    ;; `display-message` returns nil from the default stub; build a
    ;; small test-only impl that returns a height.
    (let [tall (reify ai.brainyard.agent-tui-tmux.core.protocol/Tmux
                 (probe-version [_] [3 4])
                 (display-message [_ _] "40"))]
      (is (true? (popup/feasible? tall)))))

  (testing "tmux 3.4 with short client (h=20) falls back"
    (let [short (reify ai.brainyard.agent-tui-tmux.core.protocol/Tmux
                  (probe-version [_] [3 4])
                  (display-message [_ _] "20"))]
      (is (false? (popup/feasible? short))))))
