;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent-tui.oauth-render-test
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.string :as str]
            [ai.brainyard.agent-tui.session :as tui-session]
            [ai.brainyard.agent-tui.layout :as layout]
            [ai.brainyard.agent.interface.tui.format :as fmt]
            [ai.brainyard.agent-tui.oauth-render :as r]))

(defn- strip-ansi [s] (str/replace s #"\x1b\[[0-9;]*m" ""))
;; OSC-8 hyperlink open (ESC]8;;<url>BEL) and close (ESC]8;;BEL).
(defn- strip-osc8 [s] (str/replace s #"\u001b\]8;;[^\u0007]*\u0007" ""))
(defn- visible [boxed] (-> boxed strip-osc8 strip-ansi))
(defn- visible-lines [boxed] (str/split-lines (visible boxed)))

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

(deftest loopback-box-content
  (let [boxed (r/loopback-box {:account-id "notion2" :authorize_uri "https://mcp.notion.com/authorize?x=1"
                               :scopes ["read"]})
        plain (strip-ansi boxed)]
    (is (str/includes? plain "Authorize \"notion2\""))
    (is (str/includes? plain "https://mcp.notion.com/authorize?x=1"))
    (is (str/includes? plain "browser"))
    (is (not (str/includes? plain "Paste")) "loopback never asks for a paste")))

(deftest long-url-is-capped-truncated-and-clickable
  ;; Pin a terminal width so the 80% target is deterministic: 100 cols → box
  ;; outer ≈ 80, inner 76.
  (with-redefs [fmt/terminal-columns (fn [] 100)]
    (let [long-url (str "https://github.com/login/oauth/authorize?response_type=code"
                        "&client_id=Iv23li43rr9dAO4GpezE"
                        "&redirect_uri=http%3A%2F%2F127.0.0.1%3A63597%2Fcallback"
                        "&scope=repo+read%3Aorg&code_challenge=" (apply str (repeat 60 "x"))
                        "&state=abcdef")
          boxed (r/loopback-box {:account-id "github2" :authorize_uri long-url
                                 :scopes ["repo" "read:org" "read:user"]})
          vlines (str/split-lines (visible boxed))
          widths (map fmt/display-width vlines)]
      (testing "box is capped near 80% of the terminal, all rows equal width"
        (is (= 1 (count (distinct widths))) "every row shares one display width")
        (is (= 80 (first widths)) "outer width = floor(0.8*100)"))
      (testing "the long URL is truncated for display"
        (is (str/includes? (visible boxed) "…"))
        (is (not (str/includes? (visible boxed) "state=abcdef")) "tail elided from the label"))
      (testing "the FULL url is the OSC-8 click target"
        (is (str/includes? boxed (str "\u001b]8;;" long-url "\u0007"))
            "ESC]8;;<full-url>BEL wraps the shown label")))))

(deftest short-url-not-truncated-but-still-clickable
  (with-redefs [fmt/terminal-columns (fn [] 100)]
    (let [u "https://linear.app/oauth?x=1"
          boxed (r/loopback-box {:account-id "linear" :authorize_uri u :scopes ["read"]})]
      (is (str/includes? (visible boxed) u) "short URL shown in full")
      (is (not (str/includes? (visible boxed) "…")))
      (is (str/includes? boxed (str "\u001b]8;;" u "\u0007")) "still a clickable link"))))

(deftest render-loopback-prompt-uses-loopback-box
  (let [emitted (atom [])]
    (with-redefs [tui-session/emit! (fn [s] (swap! emitted conj (strip-ansi s)))
                  layout/restore-input-cursor! (fn [] nil)
                  r/qr-block (fn [_] nil)]
      (r/flush-deferred!)
      (r/render {:event :prompt :account-id "notion2" :mode :loopback
                 :authorize_uri "https://mcp.notion.com/authorize?x=1"})
      (is (= 1 (count @emitted)))
      (is (str/includes? (first @emitted) "browser"))
      (is (not (str/includes? (first @emitted) "Paste"))))))

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

(deftest paste-read-code-provider-blocks-then-receives
  (with-redefs [tui-session/emit! (fn [_] nil)
                layout/restore-input-cursor! (fn [] nil)]
    (let [result (atom nil)
          worker (future (reset! result (r/read-code-provider "notion")))]
      (Thread/sleep 80)
      (testing "the provider parks, marking a pending paste"
        (is (true? (r/pending-code?))))
      (testing "consume-code! delivers the next line and clears pending"
        (is (true? (r/consume-code! "  ABC-123  ")))
        (is (false? (r/pending-code?))))
      @worker
      (is (= "ABC-123" @result) "trimmed code returned to the blocked login"))))

(deftest consume-code-no-op-when-nothing-pending
  (is (nil? (r/consume-code! "hello"))))

(deftest boot-emit-gate-buffers-then-replays
  (let [emitted (atom [])]
    (with-redefs [tui-session/emit! (fn [s] (swap! emitted conj (strip-ansi s)))
                  layout/restore-input-cursor! (fn [] nil)
                  r/qr-block (fn [_] nil)]
      (try
        (testing "armed → a boot-time prompt is buffered, not emitted"
          (r/arm-deferral!)
          (r/render {:event :prompt :account-id "notion" :verification_uri "u" :user_code "ABCD-1234"})
          (is (empty? @emitted)))
        (testing "flush-deferred! replays the buffered box once the loop is up"
          (r/flush-deferred!)
          (is (= 1 (count @emitted)))
          (is (str/includes? (first @emitted) "ABCD-1234")))
        (testing "after flush, prompts emit straight through"
          (reset! emitted [])
          (r/render {:event :authorized :account-id "notion"})
          (is (= 1 (count @emitted))))
        (finally (r/flush-deferred!))))))   ; leave the gate open for other tests
