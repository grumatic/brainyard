;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: Apache-2.0
;; Licensed under the Apache License, Version 2.0 (the "License"); you may
;; not use this file except in compliance with the License. See LICENSE at
;; the repository root and CONTRIBUTING.md for contribution terms.

(ns ai.brainyard.clj-llm.cache-breakpoints-test
  "Tests for M7 — provider-aware cache breakpoints.
   Exercises the private helpers in clj-llm.core.llm that translate
   `:cache-zones` (from the BT dspy-action) into Anthropic structured
   `:system` blocks with `cache_control: ephemeral` markers."
  (:require [ai.brainyard.clj-llm.core.llm :as llm]
            [clojure.test :refer [deftest is testing]]))

;; ============================================================================
;; build-anthropic-system-blocks (private)
;; ============================================================================

(def ^:private split-blocks @#'llm/build-anthropic-system-blocks)
(def ^:private build-body  @#'llm/build-anthropic-body)

(deftest split-blocks-no-zones-test
  ;; build-anthropic-body only calls split-blocks when zones are non-empty,
  ;; but defensively the helper still produces a single uncached block when
  ;; called with empty zones (so callers can fall back uniformly).
  (testing "empty zones → whole system becomes one uncached block"
    (is (= [{:type "text" :text "anything"}]
           (split-blocks "anything" []))))

  (testing "empty system + empty zones → empty result"
    (is (= [] (split-blocks "" [])))))

(deftest split-blocks-single-zone-test
  (testing "single zone splits preamble + zone, only zone gets cache_control"
    (let [system "DSPy preamble line\n\n## system-context\nbody here"
          zones [{:key :system-context :text "## system-context\nbody here"}]
          blocks (split-blocks system zones)]
      (is (= 2 (count blocks)))
      (is (= {:type "text" :text "DSPy preamble line"} (first blocks)))
      (is (= {:type "text"
              :text "## system-context\nbody here"
              :cache_control {:type "ephemeral"}}
             (second blocks))))))

(deftest split-blocks-two-zones-test
  (testing "two zones — preamble + zone1 + zone2, both zones cached"
    (let [system (str "DSPy preamble\n\n"
                      "## system-context\nstable text\n\n"
                      "## user-context\nvolatile text")
          zones [{:key :system-context :text "## system-context\nstable text"}
                 {:key :user-context :text "## user-context\nvolatile text"}]
          blocks (split-blocks system zones)]
      (is (= 3 (count blocks)))
      (is (= "DSPy preamble" (:text (nth blocks 0))))
      (is (nil? (:cache_control (nth blocks 0))))
      (is (= "## system-context\nstable text" (:text (nth blocks 1))))
      (is (= {:type "ephemeral"} (:cache_control (nth blocks 1))))
      (is (= "## user-context\nvolatile text" (:text (nth blocks 2))))
      (is (= {:type "ephemeral"} (:cache_control (nth blocks 2)))))))

(deftest split-blocks-zone-without-preamble-test
  (testing "system message starts with the first zone (no preamble) → no leading uncached block"
    (let [system "## system-context\nstable"
          zones [{:key :system-context :text "## system-context\nstable"}]
          blocks (split-blocks system zones)]
      (is (= 1 (count blocks)))
      (is (= {:type "text"
              :text "## system-context\nstable"
              :cache_control {:type "ephemeral"}}
             (first blocks))))))

(deftest split-blocks-zone-not-found-test
  (testing "zone text not present in system → returns nil (caller falls back to string)"
    (let [system "DSPy preamble"
          zones [{:key :system-context :text "## system-context\nmissing"}]]
      (is (nil? (split-blocks system zones))))))

;; ============================================================================
;; build-anthropic-body wiring
;; ============================================================================

(def ^:private base-lm-config
  {:model "claude-sonnet-4-5"
   :provider :anthropic
   :max-tokens 4096})

(def ^:private cached-lm-config
  (assoc base-lm-config :prompt-cache true))

(def ^:private sample-messages
  [{:role "system"
    :content (str "DSPy preamble\n\n"
                  "## system-context\nstable text\n\n"
                  "## user-context\nvolatile text")}
   {:role "user" :content "hello"}])

(def ^:private sample-zones
  [{:key :system-context :text "## system-context\nstable text"}
   {:key :user-context :text "## user-context\nvolatile text"}])

(deftest body-no-prompt-cache-yields-string-system
  (testing "prompt-cache disabled → system is a plain string even when zones are passed"
    (let [body (build-body base-lm-config sample-messages
                           {:cache-zones sample-zones})]
      (is (string? (:system body))))))

(deftest body-prompt-cache-no-zones-yields-string-system
  (testing "prompt-cache enabled but no zones → system stays as a string"
    (let [body (build-body cached-lm-config sample-messages {})]
      (is (string? (:system body))))))

(deftest body-prompt-cache-with-zones-yields-structured-system
  (testing "prompt-cache enabled + zones → :system becomes structured blocks with cache_control"
    (let [body (build-body cached-lm-config sample-messages
                           {:cache-zones sample-zones})
          system (:system body)]
      (is (vector? system))
      (is (= 3 (count system)))
      (is (nil? (:cache_control (nth system 0))))
      (is (= {:type "ephemeral"} (:cache_control (nth system 1))))
      (is (= {:type "ephemeral"} (:cache_control (nth system 2)))))))

(deftest body-falls-back-when-zone-text-missing
  (testing "Zone text not found in system → falls back to string emission"
    (let [bad-zones [{:key :nope :text "this text is not in the system message"}]
          body (build-body cached-lm-config sample-messages
                           {:cache-zones bad-zones})]
      (is (string? (:system body))))))

(deftest body-no-top-level-cache_control
  (testing "Top-level :cache_control field is never emitted (Anthropic ignores it there)"
    (let [body-cached (build-body cached-lm-config sample-messages
                                  {:cache-zones sample-zones})
          body-plain  (build-body cached-lm-config sample-messages {})]
      (is (not (contains? body-cached :cache_control)))
      (is (not (contains? body-plain :cache_control))))))
