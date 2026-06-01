;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: Apache-2.0
;; Licensed under the Apache License, Version 2.0 (the "License"); you may
;; not use this file except in compliance with the License. See LICENSE at
;; the repository root and CONTRIBUTING.md for contribution terms.

(ns ai.brainyard.agent.rlm-agent-test
  "Tests for rlm-agent: registration, inherited bt-factory (CoAct),
   curated agent-tools roster (positive + negative assertions),
   instruction-content anchors that pin the playbook contract, and
   unit tests for the rlm$* helper commands."
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [ai.brainyard.agent.common.rlm :as rlm]
            [ai.brainyard.agent.common.rlm-agent]
            [ai.brainyard.agent.core.tool :as tool]))

;; ============================================================================
;; Registration
;; ============================================================================

(deftest registration-test
  (testing "rlm-agent is registered as an agent"
    (let [agent-defs (tool/get-tool-defs :type :agent)]
      (is (contains? agent-defs :rlm-agent))
      (let [agent-def (get agent-defs :rlm-agent)]
        (is (= :rlm-agent (:id agent-def)))
        (is (some? (:fn agent-def)))
        (is (some? (:meta agent-def)))))))

;; ============================================================================
;; Inheritance via run-coact-derived
;;
;; rlm-agent does NOT define its own :bt-factory. At registration time the
;; agent's :meta does not contain a :bt-factory key — the parent's factory is
;; merged in at *call time* inside `run-coact-derived` (coact_agent.clj). So
;; the contractually meaningful checks are: (a) the agent's tool-fn is
;; run-coact-derived, and (b) coact-agent itself has a bt-factory (the
;; thing that actually gets used).
;; ============================================================================

(deftest inheritance-test
  (require 'ai.brainyard.agent.common.coact-agent)
  (let [rlm-def   (get (tool/get-tool-defs :type :agent) :rlm-agent)
        coact-def (get (tool/get-tool-defs :type :agent) :coact-agent)]

    (testing "rlm-agent's :fn is registered (the wrap-fn that invokes run-coact-derived)"
      (is (some? (:fn rlm-def))))

    (testing "rlm-agent pins :bt-factory explicitly (so setup-agent-by-id picks it up)"
      (let [bt-factory (get-in rlm-def [:meta :bt-factory])]
        (is (fn? bt-factory))
        (let [bt (bt-factory {:max-iterations 3})]
          (is (vector? bt))
          (is (= :sequence (first bt)))
          (is (= "coact.sequence" (namespace (get-in bt [1 :id])))))))

    (testing "coact-agent (the parent) has the same bt-factory shape"
      (let [bt-factory (get-in coact-def [:meta :bt-factory])]
        (is (fn? bt-factory))
        (let [bt (bt-factory {:max-iterations 3})]
          (is (= :sequence (first bt)))
          (is (= "coact.sequence" (namespace (get-in bt [1 :id])))))))))

;; ============================================================================
;; Agent tools binding — positive + negative assertions
;; ============================================================================

(defn- rlm-tool-ids []
  (let [agent-def   (get (tool/get-tool-defs :type :agent) :rlm-agent)
        agent-tools (get-in agent-def [:meta :agent-tools])]
    (set (map (comp :id meta deref) (:tools agent-tools)))))

(deftest agent-tools-test
  (testing "rlm-agent :agent-tools includes the four cardinal primitives"
    (let [ids (rlm-tool-ids)]
      ;; Enumeration / chunking
      (is (contains? ids :bash))
      (is (contains? ids :grep))
      (is (contains? ids :read-file))
      (is (contains? ids :write-file))
      (is (contains? ids :search))

      ;; MAP — sub-LLM calls
      (is (contains? ids :query$llm))

      ;; RECURSE — clone-self (depth-2), allow-listed to rlm-* ONLY.
      ;; rlm-agent is the sole holder of query$clone (see Hard Rule A).
      (is (contains? ids :query$clone)
          "query$clone must be in rlm-agent's roster (rlm is the sole holder)")

      ;; Bookkeeping / invocation
      (is (contains? ids :list-tools))
      (is (contains? ids :get-tool-info))
      (is (contains? ids :call-tool))

      ;; Background fan-out
      (is (contains? ids :task$run))

      ;; Runtime config (sub-LM switching)
      (is (contains? ids :agent-runtime$config))

      ;; RLM helpers (Phase B)
      (is (contains? ids :rlm$chunk-text))
      (is (contains? ids :rlm$chunk-files))
      (is (contains? ids :rlm$parse-map-results))
      (is (contains? ids :rlm$reduce-counts))
      (is (contains? ids :rlm$conservative-verdict))))

  (testing "rlm-agent :agent-tools EXCLUDES out-of-scope tools"
    (let [ids (rlm-tool-ids)]
      ;; Out-of-scope domains — covered by other agents
      (is (not (contains? ids :doc$create)))
      (is (not (contains? ids :doc$update)))
      (is (not (contains? ids :memory$recall)))
      (is (not (contains? ids :memory$remember))))))

;; ============================================================================
;; Instruction content anchors — pin the playbook contract
;; ============================================================================

(deftest instruction-content-test
  (testing "instruction string contains the cardinal RLM playbook anchors"
    (let [agent-def   (get (tool/get-tool-defs :type :agent) :rlm-agent)
          instruction (get-in agent-def [:meta :instruction])]
      (is (string? instruction))
      (is (not (str/blank? instruction)))

      ;; Playbook shape
      (is (str/includes? instruction "chunk → map → reduce"))

      ;; Hard Rule A — query$clone present (rlm-only, last-resort recursion)
      (is (str/includes? instruction "query$clone"))
      (is (str/includes? instruction "depth"))

      ;; Batched fan-out cap awareness
      (is (str/includes? instruction "20"))

      ;; Sub-context primitive named explicitly
      (is (str/includes? instruction "sub-context")))))

(deftest tool-context-content-test
  (testing "tool-context names the four primitives + spill-recovery + helpers"
    (let [agent-def    (get (tool/get-tool-defs :type :agent) :rlm-agent)
          tool-context (get-in agent-def [:meta :tool-context])]
      (is (string? tool-context))
      (is (not (str/blank? tool-context)))
      (is (str/includes? tool-context "ENUMERATION"))
      (is (str/includes? tool-context "CHUNKING"))
      (is (str/includes? tool-context "MAP"))
      (is (str/includes? tool-context "REDUCE"))
      (is (str/includes? tool-context "SPILL"))
      (is (str/includes? tool-context "query$llm"))
      ;; Phase B — helpers documented
      (is (str/includes? tool-context "rlm$chunk-text"))
      (is (str/includes? tool-context "rlm$chunk-files"))
      (is (str/includes? tool-context "rlm$parse-map-results"))
      (is (str/includes? tool-context "rlm$reduce-counts"))
      (is (str/includes? tool-context "rlm$conservative-verdict")))))

;; ============================================================================
;; Helper unit tests — rlm$chunk-text
;; ============================================================================

(deftest chunk-text-test
  (testing "blank/empty input → empty :chunks"
    (is (= {:chunks [] :n-chunks 0}
           (rlm/rlm$chunk-text :text "")))
    (is (= {:chunks [] :n-chunks 0}
           (rlm/rlm$chunk-text :text "   "))))

  (testing "single chunk when input fits"
    (let [r (rlm/rlm$chunk-text :text "abcdef" :size 100)]
      (is (= 1 (:n-chunks r)))
      (is (= ["abcdef"] (:chunks r)))))

  (testing "exact-fit boundary produces exactly one chunk"
    (let [r (rlm/rlm$chunk-text :text "abcde" :size 5)]
      (is (= 1 (:n-chunks r)))
      (is (= ["abcde"] (:chunks r)))))

  (testing "no-overlap chunking covers input exactly"
    (let [r (rlm/rlm$chunk-text :text "abcdefghij" :size 4 :overlap 0)
          chunks (:chunks r)]
      (is (= "abcdefghij" (apply str chunks)))
      (is (= 3 (count chunks)))
      (is (= ["abcd" "efgh" "ij"] chunks))))

  (testing "overlap correctness — adjacent chunks share trailing chars"
    (let [r (rlm/rlm$chunk-text :text "abcdefghij" :size 5 :overlap 2)
          chunks (:chunks r)]
      ;; step = size - overlap = 3
      ;; chunk 0: 0..5 = "abcde"
      ;; chunk 1: 3..8 = "defgh"
      ;; chunk 2: 6..10 = "ghij"
      (is (= ["abcde" "defgh" "ghij"] chunks))))

  (testing "non-string text → :error"
    (is (contains? (rlm/rlm$chunk-text :text 123) :error))
    (is (contains? (rlm/rlm$chunk-text) :error)))

  (testing "invalid size/overlap → :error"
    (is (contains? (rlm/rlm$chunk-text :text "abc" :size 0) :error))
    (is (contains? (rlm/rlm$chunk-text :text "abc" :size 10 :overlap -1) :error))
    (is (contains? (rlm/rlm$chunk-text :text "abc" :size 5 :overlap 5) :error))
    (is (contains? (rlm/rlm$chunk-text :text "abc" :size 5 :overlap 6) :error))))

;; ============================================================================
;; Helper unit tests — rlm$chunk-files
;; ============================================================================

(defn- make-tmp-file [name content]
  (let [f (io/file (System/getProperty "java.io.tmpdir")
                   (str "rlm-test-" (System/currentTimeMillis) "-" name))]
    (spit f content)
    (.deleteOnExit f)
    (.getAbsolutePath f)))

(deftest chunk-files-test
  (testing "happy path — group-size cap groups files correctly"
    (let [paths [(make-tmp-file "a.txt" "AAA")
                 (make-tmp-file "b.txt" "BBB")
                 (make-tmp-file "c.txt" "CCC")
                 (make-tmp-file "d.txt" "DDD")
                 (make-tmp-file "e.txt" "EEE")]
          r     (rlm/rlm$chunk-files :paths paths :group-size 2 :max-bytes 1000000)]
      (is (= 3 (:n-chunks r)))                    ; 5 files / 2 per chunk = 3 chunks
      (is (empty? (:errors r)))
      (let [first-chunk (first (:chunks r))]
        (is (str/includes? first-chunk "=== "))   ; file header present
        (is (str/includes? first-chunk "AAA"))
        (is (str/includes? first-chunk "BBB")))))

  (testing "max-bytes cap forces a new chunk before group-size hits"
    (let [paths [(make-tmp-file "big1.txt" (apply str (repeat 60 "x")))
                 (make-tmp-file "big2.txt" (apply str (repeat 60 "y")))
                 (make-tmp-file "big3.txt" (apply str (repeat 60 "z")))]
          r     (rlm/rlm$chunk-files :paths paths :group-size 10 :max-bytes 100)]
      ;; Each file is 60 bytes; only one fits per chunk under max-bytes=100
      ;; (the second would push cumulative to 120 > 100 → new chunk).
      (is (= 3 (:n-chunks r)))
      (is (empty? (:errors r)))))

  (testing "missing path → graceful :errors entry, not exception"
    (let [good (make-tmp-file "ok.txt" "OK")
          r    (rlm/rlm$chunk-files :paths [good "/no/such/file/at/all.xyz"]
                                    :group-size 5 :max-bytes 1000000)]
      (is (= 1 (:n-chunks r)))
      (is (= 1 (count (:errors r))))
      (is (= "/no/such/file/at/all.xyz" (:path (first (:errors r)))))
      (is (some? (:error (first (:errors r)))))))

  (testing "validation errors"
    (is (contains? (rlm/rlm$chunk-files :paths "not a vector") :error))
    (is (contains? (rlm/rlm$chunk-files :paths [] :group-size 0) :error))
    (is (contains? (rlm/rlm$chunk-files :paths [] :max-bytes 0) :error))))

;; ============================================================================
;; Helper unit tests — rlm$parse-map-results
;; ============================================================================

(deftest parse-map-results-test
  (testing "JSON tier hits"
    (let [r (rlm/rlm$parse-map-results
             :results ["{\"category\":\"a\",\"count\":3}"
                       "{\"category\":\"b\",\"count\":1}"])]
      (is (= 2 (:n-parsed r)))
      (is (= 0 (:n-failed r)))
      (is (= [{:category "a" :count 3} {:category "b" :count 1}] (:parsed r)))))

  (testing "EDN shape via :shape \"edn\""
    (let [r (rlm/rlm$parse-map-results
             :results ["{:category \"a\" :count 3}" "{:category \"b\" :count 1}"]
             :shape "edn")]
      (is (= 2 (:n-parsed r)))
      (is (= [{:category "a" :count 3} {:category "b" :count 1}] (:parsed r)))))

  (testing "tier-2 fallback — JSON requested, EDN supplied"
    (let [r (rlm/rlm$parse-map-results
             :results ["{:category \"a\" :count 3}"]
             :shape "json")]
      (is (= 1 (:n-parsed r)))
      (is (= 0 (:n-failed r)))))

  (testing "all-fail tier 3 — neither JSON nor EDN parses"
    (let [r (rlm/rlm$parse-map-results :results ["complete garbage <<<"])]
      (is (= 0 (:n-parsed r)))
      (is (= 1 (:n-failed r)))
      (is (= 0 (:idx (first (:failed r)))))
      (is (= "complete garbage <<<" (:raw (first (:failed r)))))))

  (testing "mixed batch — preserves indices of failures"
    (let [r (rlm/rlm$parse-map-results
             :results ["{\"category\":\"a\"}"
                       "junk junk"
                       "{\"category\":\"b\"}"])]
      (is (= 2 (:n-parsed r)))
      (is (= 1 (:n-failed r)))
      (is (= 1 (:idx (first (:failed r)))))))

  (testing ":per-line mode — splits each result into JSON-per-line"
    (let [r (rlm/rlm$parse-map-results
             :results ["{\"x\":1}\n{\"x\":2}\n{\"x\":3}"
                       "{\"x\":4}\n\n{\"x\":5}"]
             :per-line "true")]
      ;; Empty/blank lines are dropped; we get 5 successful parses.
      (is (= 5 (:n-parsed r)))
      (is (= [{:x 1} {:x 2} {:x 3} {:x 4} {:x 5}] (:parsed r)))))

  (testing "validation"
    (is (contains? (rlm/rlm$parse-map-results :results "not a vector") :error))
    (is (contains? (rlm/rlm$parse-map-results :results [] :shape "yaml") :error))))

;; ============================================================================
;; Helper unit tests — rlm$reduce-counts
;; ============================================================================

(deftest reduce-counts-test
  (testing "empty input"
    (let [r (rlm/rlm$reduce-counts :parsed-results [])]
      (is (= 0 (:total r)))
      (is (= 0 (:n-categories r)))
      (is (= [] (:counts r)))))

  (testing "single category"
    (let [r (rlm/rlm$reduce-counts
             :parsed-results [{:category "a" :count 3}
                              {:category "a" :count 2}])]
      (is (= 5 (:total r)))
      (is (= 1 (:n-categories r)))
      (is (= "a" (:category (first (:counts r)))))
      (is (= 100.0 (:percent (first (:counts r)))))))

  (testing "multi-category sorted desc with percentages"
    (let [r (rlm/rlm$reduce-counts
             :parsed-results [{:category "a" :count 3}
                              {:category "b" :count 7}
                              {:category "c" :count 5}])
          counts (:counts r)]
      (is (= 15 (:total r)))
      (is (= 3 (:n-categories r)))
      (is (= ["b" "c" "a"] (mapv :category counts)))
      (is (= 7 (:count (first counts))))
      ;; Percent ≈ 46.67 — tolerance for double arithmetic
      (is (< (Math/abs (- 46.666 (:percent (first counts)))) 0.01))))

  (testing "missing :count-key falls back to 1 per row"
    (let [r (rlm/rlm$reduce-counts
             :parsed-results [{:category "a"}
                              {:category "a"}
                              {:category "b"}])]
      (is (= 3 (:total r)))
      (is (= "a" (:category (first (:counts r)))))
      (is (= 2 (:count (first (:counts r)))))))

  (testing ":parse-failed rows are skipped"
    (let [r (rlm/rlm$reduce-counts
             :parsed-results [{:category "a" :count 5}
                              {:parse-failed true :raw "junk"}])]
      (is (= 5 (:total r)))
      (is (= 1 (:n-categories r)))))

  (testing "missing :key → grouped under :_unkeyed"
    (let [r (rlm/rlm$reduce-counts
             :parsed-results [{:other "x" :count 2}
                              {:category "a" :count 3}])
          unkeyed (first (filter #(= :_unkeyed (:category %)) (:counts r)))]
      (is (some? unkeyed))
      (is (= 2 (:count unkeyed))))))

;; ============================================================================
;; Helper unit tests — rlm$conservative-verdict
;; ============================================================================

(deftest conservative-verdict-test
  (testing "all-false → verdict false"
    (let [r (rlm/rlm$conservative-verdict
             :parsed-results [{:malicious? false} {:malicious? false}])]
      (is (false? (:verdict r)))
      (is (= 0 (:positive-count r)))
      (is (= 2 (:negative-count r)))
      (is (= 0 (:skipped r)))
      (is (= [] (:evidence r)))))

  (testing "one-true (Paper-3 conservative) → verdict true"
    (let [r (rlm/rlm$conservative-verdict
             :parsed-results [{:malicious? false}
                              {:malicious? true :reason "exploit X"}
                              {:malicious? false}])]
      (is (true? (:verdict r)))
      (is (= 1 (:positive-count r)))
      (is (= 2 (:negative-count r)))
      (is (= 1 (count (:evidence r))))
      (is (= "exploit X" (:reason (first (:evidence r)))))))

  (testing "all-true → verdict true, evidence has all rows"
    (let [r (rlm/rlm$conservative-verdict
             :parsed-results [{:malicious? true} {:malicious? true}])]
      (is (true? (:verdict r)))
      (is (= 2 (:positive-count r)))
      (is (= 2 (count (:evidence r))))))

  (testing ":parse-failed rows are excluded from the vote (not counted positive)"
    (let [r (rlm/rlm$conservative-verdict
             :parsed-results [{:parse-failed true :raw "garbage"}
                              {:malicious? false}])]
      (is (false? (:verdict r)))
      (is (= 0 (:positive-count r)))
      (is (= 1 (:negative-count r)))
      (is (= 1 (:skipped r)))))

  (testing "custom :positive-key"
    (let [r (rlm/rlm$conservative-verdict
             :parsed-results [{:flagged? false} {:flagged? true}]
             :positive-key "flagged?")]
      (is (true? (:verdict r)))
      (is (= 1 (:positive-count r))))))
