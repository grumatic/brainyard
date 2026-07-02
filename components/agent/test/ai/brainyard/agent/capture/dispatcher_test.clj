;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.capture.dispatcher-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.core.async :as async]
            [ai.brainyard.memory.core.capture.dispatcher :as disp]
            [ai.brainyard.agent.core.hooks :as hooks]))

(use-fixtures :each
  (fn [f]
    (hooks/reset-hooks!)
    (f)
    (hooks/reset-hooks!)))

(defn- drain
  "Drain everything currently buffered on `ch` within `timeout-ms`.
  Returns a vector of events."
  [ch timeout-ms]
  (let [end (+ (System/currentTimeMillis) timeout-ms)
        out (atom [])]
    (loop []
      (when (< (System/currentTimeMillis) end)
        (let [t (async/timeout 20)
              [v port] (async/alts!! [ch t])]
          (when (and v (not= port t))
            (swap! out conj v)
            (recur)))))
    @out))

;; =====================================================
;; Subscription
;; =====================================================

(deftest start-registers-only-ask-post-test
  (let [d (disp/start!)]
    (try
      ;; L2 captures ONLY the Q&A episode at :agent.ask/post. tool-use/post,
      ;; code-eval/post and agent/exception are deliberately NOT subscribed —
      ;; they only ever produced operational error episodes (see dispatcher's
      ;; subscribed-events). ask/pre is folded into ask/post.
      (is (pos? (count (or (hooks/list-hooks :agent.ask/post) []))))
      (doseq [ev [:agent.ask/pre :agent.tool-use/post
                  :agent.code-eval/post :agent/exception]]
        (is (zero? (count (or (hooks/list-hooks ev) [])))
            (str ev " is not subscribed by capture")))
      (finally (disp/stop! d)))))

(deftest stop-unregisters-test
  (let [d (disp/start!)]
    (disp/stop! d)
    (is (zero? (count (or (hooks/list-hooks :agent.ask/post) []))))))

;; =====================================================
;; Routing — critical vs non-critical
;; =====================================================

(deftest critical-events-route-to-critical-channel-test
  (let [d (disp/start!)
        [crit ev] (disp/channels d)]
    (try
      (hooks/fire! :agent.ask/post {:session-id "s" :user-id "u" :input "x" :result "y"})
      (let [crit-events (drain crit 200)
            ev-events   (drain ev 50)]
        (is (= 1 (count crit-events)))
        (is (= :agent.ask/post (-> crit-events first :event-key)))
        (is (zero? (count ev-events))))
      (finally (disp/stop! d)))))

;; NOTE: the dispatcher still supports a non-critical (droppable, debounced)
;; channel generically, but no currently-subscribed event routes there — L2
;; captures only the critical `:agent.ask/post` episode. The former
;; non-critical-routing and dedup/debounce tests exercised that path via
;; `:agent.tool-use/post`, which capture no longer subscribes to, so they were
;; removed. `dedup-does-not-collapse-critical-events-test` below still covers the
;; critical-path no-dedup guarantee.

;; =====================================================
;; Dedup
;; =====================================================

(deftest dedup-does-not-collapse-critical-events-test
  (let [d (disp/start!)
        [crit-ch _] (disp/channels d)]
    (try
      ;; Even if agent.ask/post payloads were identical, every one must surface
      ;; (no dedup on critical events).
      (dotimes [_ 3]
        (hooks/fire! :agent.ask/post {:session-id "s" :user-id "u" :input "same" :result "a"}))
      (let [out (drain crit-ch 200)]
        (is (= 3 (count out))))
      (finally (disp/stop! d)))))

;; =====================================================
;; Match scoping
;; =====================================================

(deftest match-pred-scopes-capture-test
  ;; Match scoping gates every event before channel routing; tested here on the
  ;; critical :agent.ask/post path (the only subscribed event).
  (let [d (disp/start! :match (fn [m] (= "u-keep" (:user-id m))))
        [crit-ch _] (disp/channels d)]
    (try
      (hooks/fire! :agent.ask/post {:session-id "s" :user-id "u-keep" :input "a" :result "x"})
      (hooks/fire! :agent.ask/post {:session-id "s" :user-id "u-skip" :input "b" :result "y"})
      (let [out (drain crit-ch 200)]
        (is (= 1 (count out)))
        (is (= "u-keep" (-> out first :user-id))))
      (finally (disp/stop! d)))))

;; =====================================================
;; M8b dup-storage cleanup
;; =====================================================

(deftest hook-replaced-events-skipped-test
  (testing "Events carrying :hook-replaced true never enter the channels"
    (let [d (disp/start!)
          [crit-ch _] (disp/channels d)]
      (try
        ;; A real turn should enter the channel.
        (hooks/fire! :agent.ask/post
                     {:session-id "s" :user-id "u" :input "q" :result "ok"})
        ;; A replayed/replaced event carries :hook-replaced true — should drop.
        (hooks/fire! :agent.ask/post
                     {:session-id "s" :user-id "u" :input "q" :result "ok"
                      :hook-replaced true
                      :replaced-by :context-actions/tool-cache-lookup})
        (let [out (drain crit-ch 200)]
          ;; Only the real turn survives.
          (is (= 1 (count out)))
          (is (not (:hook-replaced (first out)))))
        (finally (disp/stop! d))))))

;; =====================================================
;; Idempotent stop
;; =====================================================

(deftest stop-idempotent-test
  (let [d (disp/start!)]
    (disp/stop! d)
    (is (nil? (disp/stop! d))))) ; no-op, no throw
