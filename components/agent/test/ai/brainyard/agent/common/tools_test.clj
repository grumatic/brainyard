;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.common.tools-test
  "`list-tools` result shape — the discovery surface's token cost.

   `list-tools` used to inline full Malli `:input-schema`/`:output-schema` for
   every match as soon as ANY filter was given. Measured against the live
   registry that was 6,013 chars for `:pattern \"schedule\"` (9 matches) and
   161,734 chars — ~40K tokens, 5× over the `:max-output-chars` cap — for
   `:type \"command\"`, a call the CoAct prompt recommends by name. The schemas
   were redundant besides: the prompt already tells the model to call
   `get-tool-info` before invoking anything unfamiliar, and that costs 219
   chars for the one tool it is about to use.

   So the listing answers WHICH tool (id/type/description) and `get-tool-info`
   answers HOW to call it. `:detail true` opts back into the old shape."
  (:require [clojure.test :refer [deftest is testing]]
            ;; side-effect require: registers every built-in deftool/defagent,
            ;; without which the registry is empty and every probe below is
            ;; vacuously true.
            [ai.brainyard.agent.interface]
            [ai.brainyard.agent.core.tool :as tool]))

(defn- list-tools [args] (tool/invoke-tool :list-tools args))
(defn- chars [x] (count (pr-str x)))

;; The inline cap a tool result is truncated at (:max-output-chars default).
(def ^:private max-output-chars 32000)

(deftest no-args-returns-the-grouped-index
  (let [r (list-tools {})]
    (is (map? r))
    (is (pos? (:total r)))
    (is (map? (:families r)))
    (testing "index entries carry id + description only"
      (let [entry (first (val (first (:families r))))]
        (is (= #{:id :description} (set (keys entry))))))))

(deftest pattern-returns-a-flat-compact-list
  (let [hits (list-tools {:pattern "schedule"})]
    (is (vector? hits))
    (is (seq hits) "sanity: the schedule family is registered")
    (is (every? #(= #{:id :type :description} (set (keys %))) hits)
        "no schemas — get-tool-info is the drill-in")))

(deftest type-alone-stays-grouped-and-bounded
  ;; The regression that motivated this: `:type "command"` was a flat detailed
  ;; list at ~40K tokens, and the prompt advertises it.
  (let [r (list-tools {:type "command"})]
    (is (map? r) ":type alone is an index, not a schema dump")
    (is (< (chars r) max-output-chars)
        (str "a recommended discovery call must not blow the truncation cap "
             "(was " (chars r) " chars)"))))

(deftest detail-opts-back-into-schemas
  (let [compact (list-tools {:pattern "schedule"})
        full    (list-tools {:pattern "schedule" :detail true})]
    (is (vector? full))
    (is (some :input-schema full) ":detail true inlines the schemas")
    (is (< (chars compact) (chars full))
        "the default listing is the cheap one")
    (testing ":detail implies flat even with :type alone (an index has nowhere to put a schema)"
      (is (vector? (list-tools {:type "skill" :detail true}))))))

(deftest grouped-false-forces-the-flat-list
  (let [r (list-tools {:grouped false})]
    (is (vector? r))
    (is (every? #(= #{:id :type :description} (set (keys %))) r)
        "still compact — :grouped false chooses the shape, not the detail")))
