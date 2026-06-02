;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.capture.emit-sites-test
  "Tests that the agent runtime emits the lifecycle hook events that the
  memory capture pipeline subscribes to. We attach a test listener and
  verify the events fire from the right call sites — the actual capture
  → L2 plumbing is exercised in sidecar-test."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [ai.brainyard.agent.core.hooks :as hooks]
            [ai.brainyard.agent.core.agent :as agent-core]
            [ai.brainyard.agent.interface :as agent-iface]
            [ai.brainyard.agent.core.protocol :as ap]
            [ai.brainyard.agent.common.coact-agent]
            [ai.brainyard.clj-sandbox.interface :as clj-sandbox]))

(use-fixtures :each
  (fn [f]
    (hooks/reset-hooks!)
    (reset! @#'agent-core/!agent-registry {})
    (f)
    (hooks/reset-hooks!)))

(defn- collect-events
  "Register a listener that appends matching events to `events-atom`."
  [events-atom event-key]
  (hooks/register-hook!
   event-key ::collect
   (fn [m] (swap! events-atom conj m))
   :source ::emit-sites-test))

;; The :agent/knowledge-section-set hook tests were removed in the L1
;; simplification refactor — that hook no longer exists. System
;; context is operator-only and is not reflected in the capture
;; pipeline.

;; =====================================================
;; :agent/exception
;; =====================================================

;; =====================================================
;; :agent.code-eval/post
;; =====================================================

(deftest code-eval-fires-once-per-block-test
  (let [a      (agent-iface/create-agent "u-emit-eval" "s-emit-eval"
                                         "agent-emit-eval"
                                         :config {:name "Eval Test"})
        events (atom [])]
    (try
      (collect-events events :agent.code-eval/post)
      (let [sandbox (clj-sandbox/create-sandbox {})
            st-mem  (atom {:sandbox sandbox
                           :code-blocks (str "```clojure\n(+ 1 2)\n```\n"
                                             "```clojure\n(* 3 4)\n```")
                           :tools {}
                           :tools-fn-map {}
                           :iteration-count 0})
            ;; coact-code-eval-action is private — reach in via the var
            action (resolve 'ai.brainyard.agent.common.coact-agent/coact-code-eval-action)]
        (action {:st-memory st-mem :agent a})
        (is (= 2 (count @events))
            "One :agent.code-eval/post per evaluated block")
        (is (every? #(string? (:code %)) @events))
        (is (every? #(some? (:result %)) @events))
        (is (= a (:agent (first @events))))
        ;; Confirm the actual block contents flowed through
        (let [codes (mapv :code @events)]
          (is (some #(re-find #"\(\+ 1 2\)" %) codes))
          (is (some #(re-find #"\(\* 3 4\)" %) codes))))
      (finally (.close a)))))

;; Note on :agent/exception coverage:
;; The emit site lives in agent.core.agent/ask's outermost catch
;; branch. Forcing that branch from a unit test requires defeating
;; protocol dispatch on `process`, which `with-redefs` can't reach
;; cleanly (protocol methods bypass var lookup). We rely on:
;;   1. Static inspection: the (hooks/fire! :agent/exception ...) call
;;      is in agent.clj alongside the existing :agent.ask/pre / :agent.ask/post
;;      emits.
;;   2. Coverage of the consumer side via dispatcher-test
;;      (`critical-events-route-to-critical-channel-test` exercises
;;      :agent.ask/pre on the same critical channel; :agent/exception
;;      shares the route).