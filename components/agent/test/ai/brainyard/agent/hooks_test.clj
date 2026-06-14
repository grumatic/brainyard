;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.hooks-test
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [ai.brainyard.agent.core.hooks :as hooks]))

(use-fixtures :each (fn [f] (hooks/reset-hooks!) (f) (hooks/reset-hooks!)))

(deftest register-and-fire
  (testing "register-hook! + fire! invokes the handler with the event map"
    (let [seen (atom nil)]
      (hooks/register-hook! :agent.instance/created ::t
                            (fn [ev] (reset! seen ev)))
      (hooks/fire! :agent.instance/created {:agent :dummy})
      (is (= {:agent :dummy} @seen))))

  (testing "re-registering the same [event handler-id] replaces the entry"
    (let [counter (atom 0)]
      (hooks/register-hook! :agent.instance/created ::t (fn [_] (swap! counter inc)))
      (hooks/register-hook! :agent.instance/created ::t (fn [_] (swap! counter + 100)))
      (hooks/fire! :agent.instance/created {:agent :x})
      (is (= 100 @counter))
      (is (= 1 (count (hooks/list-hooks :agent.instance/created)))))))

(deftest priority-ordering
  (testing "higher :priority fires first"
    (let [order (atom [])]
      (hooks/register-hook! :agent.instance/created ::lo (fn [_] (swap! order conj :lo)) :priority 1)
      (hooks/register-hook! :agent.instance/created ::hi (fn [_] (swap! order conj :hi)) :priority 10)
      (hooks/register-hook! :agent.instance/created ::mid (fn [_] (swap! order conj :mid)) :priority 5)
      (hooks/fire! :agent.instance/created {:agent :x})
      (is (= [:hi :mid :lo] @order)))))

(deftest match-predicate
  (testing ":match filters which handlers see the event"
    (let [seen (atom [])]
      (hooks/register-hook! :agent.ask/pre ::always
                            (fn [ev] (swap! seen conj [:always (:input ev)])))
      (hooks/register-hook! :agent.ask/pre ::only-foo
                            (fn [ev] (swap! seen conj [:foo (:input ev)]))
                            :match (fn [ev] (= "foo" (:input ev))))
      (hooks/fire! :agent.ask/pre {:agent :x :input "foo"})
      (hooks/fire! :agent.ask/pre {:agent :x :input "bar"})
      (is (= [[:always "foo"] [:foo "foo"] [:always "bar"]] @seen)))))

(deftest exception-isolation
  (testing "handler exceptions are swallowed by default and do not affect siblings"
    (let [seen (atom [])]
      (hooks/register-hook! :agent.instance/created ::boom (fn [_] (throw (ex-info "boom" {}))))
      (hooks/register-hook! :agent.instance/created ::ok   (fn [_] (swap! seen conj :ok)))
      (is (nil? (hooks/fire! :agent.instance/created {:agent :x})))
      (is (= [:ok] @seen))))

  (testing ":on-error :throw propagates"
    (hooks/register-hook! :agent.instance/created ::boom (fn [_] (throw (ex-info "boom" {})))
                          :on-error :throw)
    (is (thrown? Exception (hooks/fire! :agent.instance/created {:agent :x})))))

(deftest suggestion-event-cataloged
  (testing ":agent.suggestion/next-user-prompt is a known, observer-only event"
    (is (hooks/known-event? :agent.suggestion/next-user-prompt))
    (is (not (hooks/gated-event? :agent.suggestion/next-user-prompt))))
  (testing "firing the suggestion event reaches its handler with the prompt"
    (let [seen (atom nil)]
      (hooks/register-hook! :agent.suggestion/next-user-prompt ::t
                            (fn [ev] (reset! seen ev)))
      (hooks/fire! :agent.suggestion/next-user-prompt
                   {:agent :dummy :prompt "do the next thing" :input "x"})
      (is (= "do the next thing" (:prompt @seen))))))

(deftest unregister
  (testing "unregister-hook! removes a specific handler"
    (hooks/reset-hooks!)
    (hooks/register-hook! :agent.instance/created ::a (fn [_]))
    (hooks/register-hook! :agent.instance/created ::b (fn [_]))
    (is (true? (hooks/unregister-hook! :agent.instance/created ::a)))
    (is (= 1 (count (hooks/list-hooks :agent.instance/created))))
    (is (false? (hooks/unregister-hook! :agent.instance/created ::a))))

  (testing "unregister-source! removes every handler tagged with that source"
    (hooks/reset-hooks!)
    (hooks/register-hook! :agent.instance/created ::x (fn [_]) :source :my-plugin)
    (hooks/register-hook! :agent.ask/pre       ::y (fn [_]) :source :my-plugin)
    (hooks/register-hook! :agent.ask/post      ::z (fn [_]) :source :other)
    (is (= 2 (hooks/unregister-source! :my-plugin)))
    (is (empty? (hooks/list-hooks :agent.instance/created)))
    (is (empty? (hooks/list-hooks :agent.ask/pre)))
    (is (= 1 (count (hooks/list-hooks :agent.ask/post))))))

;; ============================================================================
;; Decision contract (fire-decision!)
;; ============================================================================

(deftest fire-decision-defaults-allow
  (testing "no handlers → :allow"
    (is (= {:result :allow}
           (hooks/fire-decision! :agent.tool-use/pre {:agent nil :tool-name "x" :args {}}))))

  (testing "handler returns nil → :allow"
    (hooks/register-hook! :agent.tool-use/pre ::observer (fn [_] nil))
    (is (= {:result :allow}
           (hooks/fire-decision! :agent.tool-use/pre {:agent nil :tool-name "x" :args {}}))))

  (testing "handler returns explicit {:result :allow} → :allow (no :by stamp)"
    (hooks/register-hook! :agent.tool-use/pre ::yes (fn [_] {:result :allow}))
    (is (= {:result :allow}
           (hooks/fire-decision! :agent.tool-use/pre {:agent nil :tool-name "x" :args {}}))))

  (testing "non-decision return (e.g. arbitrary map) is ignored"
    (hooks/register-hook! :agent.tool-use/pre ::weird (fn [_] {:foo :bar}))
    (is (= {:result :allow}
           (hooks/fire-decision! :agent.tool-use/pre {:agent nil :tool-name "x" :args {}})))))

(deftest fire-decision-block-replace-modify
  (testing ":block decision returned with :by stamped from handler-id"
    (hooks/register-hook! :agent.tool-use/pre ::nope
                          (fn [_] {:result :block :reason "no"}))
    (let [d (hooks/fire-decision! :agent.tool-use/pre {:agent nil :tool-name "x" :args {}})]
      (is (= :block (:result d)))
      (is (= "no" (:reason d)))
      (is (= ::nope (:by d)))))

  (testing ":replace verdict carries replacement"
    (hooks/reset-hooks!)
    (hooks/register-hook! :agent.tool-use/pre ::cache
                          (fn [_] {:result :replace :reason "cached"
                                   :replacement {:cached 1}}))
    (let [d (hooks/fire-decision! :agent.tool-use/pre {:agent nil :tool-name "x" :args {}})]
      (is (= :replace (:result d)))
      (is (= {:cached 1} (:replacement d)))))

  (testing ":modify-args verdict carries new args"
    (hooks/reset-hooks!)
    (hooks/register-hook! :agent.tool-use/pre ::clamp
                          (fn [_] {:result :modify-args :reason "clamped"
                                   :args {:max 10}}))
    (let [d (hooks/fire-decision! :agent.tool-use/pre {:agent nil :tool-name "x" :args {}})]
      (is (= :modify-args (:result d)))
      (is (= {:max 10} (:args d))))))

(deftest fire-decision-priority-and-short-circuit
  (testing "higher-priority blocker wins; lower-priority skipped"
    (let [calls (atom [])]
      (hooks/register-hook! :agent.tool-use/pre ::hi
                            (fn [_] (swap! calls conj :hi)
                              {:result :block :reason "hi"})
                            :priority 10)
      (hooks/register-hook! :agent.tool-use/pre ::lo
                            (fn [_] (swap! calls conj :lo)
                              {:result :block :reason "lo"})
                            :priority 1)
      (let [d (hooks/fire-decision! :agent.tool-use/pre {:agent nil :tool-name "x" :args {}})]
        (is (= "hi" (:reason d)))
        (is (= ::hi (:by d)))
        (is (= [:hi] @calls))))))

(deftest fire-decision-malformed-skipped
  (testing "decision missing required keys is skipped, falls through"
    (hooks/register-hook! :agent.tool-use/pre ::bad
                          (fn [_] {:result :modify-args :args nil}))   ;; missing args
    (is (= {:result :allow}
           (hooks/fire-decision! :agent.tool-use/pre {:agent nil :tool-name "x" :args {}}))))

  (testing "exception in handler swallowed; decision falls through"
    (hooks/register-hook! :agent.tool-use/pre ::throws
                          (fn [_] (throw (ex-info "boom" {}))))
    (is (= {:result :allow}
           (hooks/fire-decision! :agent.tool-use/pre {:agent nil :tool-name "x" :args {}})))))

(deftest fire-decision-reentrancy-depth
  (testing "current-depth is 0 outside, 1 inside the handler"
    (let [seen (atom nil)]
      (hooks/register-hook! :agent.tool-use/pre ::observe-depth
                            (fn [_] (reset! seen (hooks/current-depth)) nil))
      (is (zero? (hooks/current-depth)))
      (hooks/fire-decision! :agent.tool-use/pre {:agent nil :tool-name "x" :args {}})
      (is (= 1 @seen))
      (is (zero? (hooks/current-depth))))))
