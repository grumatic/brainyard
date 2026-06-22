;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent-tui.oauth-render-test
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.string :as str]
            [ai.brainyard.agent-tui.session :as tui-session]
            [ai.brainyard.agent-tui.layout :as layout]
            [ai.brainyard.agent-tui.oauth-render :as r]))

(defn- strip-ansi [s] (str/replace s #"\x1b\[[0-9;]*m" ""))
(defn- visible-lines [boxed] (str/split-lines (strip-ansi boxed)))

(deftest frame-rows-are-equal-width-and-bordered
  (let [boxed (r/frame ["short" "a much longer line here" "mid"])
        lines (str/split-lines boxed)
        w     (count (first lines))]
    (is (every? #(= w (count %)) lines) "all rows share one width")
    (is (str/starts-with? (first lines) "┌"))
    (is (str/ends-with? (first lines) "┐"))
    (is (str/starts-with? (last lines) "└"))
    (is (every? #(and (str/starts-with? % "│") (str/ends-with? % "│"))
                (butlast (rest lines))))))

(deftest device-box-content-and-alignment
  (let [ev    {:account-id "notion" :verification_uri "https://mcp.notion.com/device"
               :user_code "WDJB-MJHT" :expires_in 900 :scopes ["read" "write"]}
        boxed (r/device-box ev)
        plain (strip-ansi boxed)
        lines (visible-lines boxed)
        w     (count (first lines))]
    (testing "carries the URL, code, scopes, expiry, account"
      (is (str/includes? plain "Authorize \"notion\""))
      (is (str/includes? plain "https://mcp.notion.com/device"))
      (is (str/includes? plain "WDJB-MJHT"))
      (is (str/includes? plain "read write"))
      (is (str/includes? plain "15m")))
    (testing "ANSI styling does not break box alignment"
      (is (every? #(= w (count %)) lines)))
    (testing "the code is styled (ANSI present around it)"
      (is (not= boxed plain)))))

(deftest paste-box-shows-authorize-url
  (let [boxed (r/paste-box {:account-id "linear" :authorize_uri "https://linear.app/oauth?x=1"
                            :scopes ["read"]})
        plain (strip-ansi boxed)]
    (is (str/includes? plain "Authorize \"linear\""))
    (is (str/includes? plain "https://linear.app/oauth?x=1"))
    (is (str/includes? plain "Paste the code back"))))

(deftest render-dispatch
  (let [emitted (atom [])]
    (with-redefs [tui-session/emit! (fn [s] (swap! emitted conj (strip-ansi s)))
                  layout/restore-input-cursor! (fn [] nil)
                  ;; force QR off regardless of host qrencode
                  r/qr-block (fn [_] nil)]
      (testing ":prompt device → one code box"
        (reset! emitted [])
        (r/render {:event :prompt :account-id "notion"
                   :verification_uri "https://x/device" :user_code "ABCD-1234"})
        (is (= 1 (count @emitted)))
        (is (str/includes? (first @emitted) "ABCD-1234")))
      (testing ":authorized → success line names the account"
        (reset! emitted [])
        (r/render {:event :authorized :account-id "notion"})
        (is (= 1 (count @emitted)))
        (is (str/includes? (first @emitted) "notion")))
      (testing ":slow-down → a notice"
        (reset! emitted [])
        (r/render {:event :slow-down :account-id "notion"})
        (is (= 1 (count @emitted))))
      (testing ":pending is silent (box already says waiting)"
        (reset! emitted [])
        (r/render {:event :pending :account-id "notion"})
        (is (empty? @emitted))))))

(deftest qr-skipped-when-config-disabled
  (with-redefs [ai.brainyard.agent.interface/get-config (fn [_] false)]
    (is (nil? (r/qr-block "https://example/complete")))))
