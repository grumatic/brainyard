;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: Apache-2.0
;; Licensed under the Apache License, Version 2.0 (the "License"); you may
;; not use this file except in compliance with the License. See LICENSE at
;; the repository root and CONTRIBUTING.md for contribution terms.

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

(deftest start-registers-all-events-test
  (let [d (disp/start!)]
    (try
      (let [counts (into {}
                         (for [ev [:agent.ask/pre :agent.ask/post :agent.tool-use/post
                                   :agent.code-eval/post :agent/exception]]
                           [ev (count (or (hooks/list-hooks ev) []))]))]
        (is (every? pos? (vals counts)))
        (is (= 5 (count counts))))
      (finally (disp/stop! d)))))

(deftest stop-unregisters-test
  (let [d (disp/start!)]
    (disp/stop! d)
    (is (zero? (count (or (hooks/list-hooks :agent.ask/pre) []))))
    (is (zero? (count (or (hooks/list-hooks :agent.tool-use/post) []))))))

;; =====================================================
;; Routing — critical vs non-critical
;; =====================================================

(deftest critical-events-route-to-critical-channel-test
  (let [d (disp/start!)
        [crit ev] (disp/channels d)]
    (try
      (hooks/fire! :agent.ask/pre {:session-id "s" :user-id "u" :input "x"})
      (let [crit-events (drain crit 200)
            ev-events   (drain ev 50)]
        (is (= 1 (count crit-events)))
        (is (= :agent.ask/pre (-> crit-events first :event-key)))
        (is (zero? (count ev-events))))
      (finally (disp/stop! d)))))

(deftest non-critical-events-route-to-events-channel-test
  (let [d (disp/start!)
        [crit ev] (disp/channels d)]
    (try
      (hooks/fire! :agent.tool-use/post {:session-id "s" :user-id "u"
                                         :tool-name "bash" :args {} :result "ok"})
      (let [crit-events (drain crit 50)
            ev-events   (drain ev 200)]
        (is (zero? (count crit-events)))
        (is (= 1 (count ev-events)))
        (is (= :agent.tool-use/post (-> ev-events first :event-key))))
      (finally (disp/stop! d)))))

;; =====================================================
;; Dedup
;; =====================================================

(deftest dedup-collapses-identical-tool-post-test
  (let [d (disp/start!)
        [_ ev-ch] (disp/channels d)]
    (try
      ;; Fire 5 identical agent.tool-use/post events. Dedup should collapse to 1.
      (dotimes [_ 5]
        (hooks/fire! :agent.tool-use/post {:session-id "s" :user-id "u"
                                           :tool-name "bash"
                                           :args {:cmd "ls"}
                                           :result "deploy.sh"}))
      (let [out (drain ev-ch 200)]
        (is (= 1 (count out))
            "Identical agent.tool-use/post events deduped to one"))
      (finally (disp/stop! d)))))

(deftest dedup-allows-distinct-tool-post-test
  (let [d (disp/start!)
        [_ ev-ch] (disp/channels d)]
    (try
      (hooks/fire! :agent.tool-use/post {:session-id "s" :user-id "u"
                                         :tool-name "bash" :args {:cmd "ls"} :result "a"})
      (hooks/fire! :agent.tool-use/post {:session-id "s" :user-id "u"
                                         :tool-name "bash" :args {:cmd "pwd"} :result "b"})
      (hooks/fire! :agent.tool-use/post {:session-id "s" :user-id "u"
                                         :tool-name "find" :args {:path "."} :result "c"})
      (let [out (drain ev-ch 200)]
        (is (= 3 (count out))
            "Distinct agent.tool-use/post events all pass through"))
      (finally (disp/stop! d)))))

(deftest dedup-does-not-collapse-critical-events-test
  (let [d (disp/start!)
        [crit-ch _] (disp/channels d)]
    (try
      ;; Even if agent.ask/pre payloads were identical, every one must surface
      ;; (no dedup on critical events).
      (dotimes [_ 3]
        (hooks/fire! :agent.ask/pre {:session-id "s" :user-id "u" :input "same"}))
      (let [out (drain crit-ch 200)]
        (is (= 3 (count out))))
      (finally (disp/stop! d)))))

;; =====================================================
;; Match scoping
;; =====================================================

(deftest match-pred-scopes-capture-test
  (let [d (disp/start! :match (fn [m] (= "u-keep" (:user-id m))))
        [_ ev-ch] (disp/channels d)]
    (try
      (hooks/fire! :agent.tool-use/post {:session-id "s" :user-id "u-keep"
                                         :tool-name "bash" :args {} :result "ok"})
      (hooks/fire! :agent.tool-use/post {:session-id "s" :user-id "u-skip"
                                         :tool-name "bash" :args {:x 1} :result "ok"})
      (let [out (drain ev-ch 200)]
        (is (= 1 (count out)))
        (is (= "u-keep" (-> out first :user-id))))
      (finally (disp/stop! d)))))

;; =====================================================
;; M8b dup-storage cleanup
;; =====================================================

(deftest hook-replaced-events-skipped-test
  (testing "Events carrying :hook-replaced true never enter the channels"
    (let [d (disp/start!)
          [_crit-ch ev-ch] (disp/channels d)]
      (try
        ;; A real call should enter the channel.
        (hooks/fire! :agent.tool-use/post
                     {:session-id "s" :user-id "u"
                      :tool-name "bash" :args {:cmd "ls"} :result "ok"})
        ;; The cached replay carries :hook-replaced true — should drop.
        (hooks/fire! :agent.tool-use/post
                     {:session-id "s" :user-id "u"
                      :tool-name "bash" :args {:cmd "ls"} :result "ok"
                      :hook-replaced true
                      :replaced-by :context-actions/tool-cache-lookup})
        (let [out (drain ev-ch 200)]
          ;; Only the real call survives.
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
