;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: Apache-2.0
;; Licensed under the Apache License, Version 2.0 (the "License"); you may
;; not use this file except in compliance with the License. See LICENSE at
;; the repository root and CONTRIBUTING.md for contribution terms.

(ns ai.brainyard.clj-sandbox.sandbox-test
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.string :as str]
            [ai.brainyard.clj-sandbox.core.sandbox :as sandbox]))

(deftest create-sandbox-test
  (testing "creates sandbox with map context"
    (let [sb (sandbox/create-sandbox :context {:text "hello world" :count 42})]
      (is (some? (:sci-ctx sb)))
      (is (some? (:output sb)))
      (is (some? (:history sb)))
      ;; Raw context is not bound — access via accessors only
      (let [r (sandbox/eval-code sb "(context-index)")]
        (is (contains? (set (:keys (:result r))) :text))
        (is (= :string (get-in (:result r) [:structure :text :type]))))))

  (testing "creates sandbox without context"
    (let [sb (sandbox/create-sandbox)]
      (is (some? sb))))

  (testing "non-map context throws a clear error"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #":context must be a map"
                          (sandbox/create-sandbox :context "raw string"))))

  (testing "creates sandbox with custom bindings"
    (let [sb (sandbox/create-sandbox :bindings {'my-data [1 2 3]})]
      (is (= [1 2 3] (sandbox/get-var sb 'my-data))))))

(deftest eval-code-basic-test
  (testing "evaluates simple expressions"
    (let [sb (sandbox/create-sandbox)
          result (sandbox/eval-code sb "(+ 1 2)")]
      (is (= 3 (:result result)))
      (is (nil? (:error result)))))

  (testing "evaluates string operations via context accessor"
    (let [sb (sandbox/create-sandbox :context {:greeting "Hello World"})
          result (sandbox/eval-code sb "(context-get [:greeting] :raw true)")]
      (is (= "Hello World" (:result result)))))

  (testing "captures stdout from println"
    (let [sb (sandbox/create-sandbox)
          result (sandbox/eval-code sb "(println \"test output\")")]
      (is (clojure.string/includes? (:output result) "test output"))))

  (testing "captures stdout from prn"
    (let [sb (sandbox/create-sandbox)
          result (sandbox/eval-code sb "(prn {:a 1})")]
      (is (clojure.string/includes? (:output result) "{:a 1}")))))

(deftest eval-code-persistence-test
  (testing "variables persist between eval calls"
    (let [sb (sandbox/create-sandbox)]
      (sandbox/eval-code sb "(def x 42)")
      (let [result (sandbox/eval-code sb "x")]
        (is (= 42 (:result result))))))

  (testing "defn persists between eval calls"
    (let [sb (sandbox/create-sandbox)]
      (sandbox/eval-code sb "(defn double-it [n] (* 2 n))")
      (let [result (sandbox/eval-code sb "(double-it 21)")]
        (is (= 42 (:result result)))))))

(deftest eval-code-error-test
  (testing "catches runtime errors"
    (let [sb (sandbox/create-sandbox)
          result (sandbox/eval-code sb "(/ 1 0)")]
      (is (some? (:error result)))
      (is (nil? (:result result)))))

  (testing "catches syntax errors"
    (let [sb (sandbox/create-sandbox)
          result (sandbox/eval-code sb "(defn")]
      (is (some? (:error result))))))

(deftest eval-code-whitelisting-test
  (testing "clojure.string functions are available"
    (let [sb (sandbox/create-sandbox :context {:greeting "Hello World"})
          result (sandbox/eval-code sb "(clojure.string/upper-case (context-get [:greeting] :raw true))")]
      (is (= "HELLO WORLD" (:result result)))))

  (testing "clojure.set functions are available"
    (let [sb (sandbox/create-sandbox)
          result (sandbox/eval-code sb "(clojure.set/union #{1 2} #{2 3})")]
      (is (= #{1 2 3} (:result result)))))

  (testing "Math functions are available"
    (let [sb (sandbox/create-sandbox)
          result (sandbox/eval-code sb "(Math/abs -5)")]
      (is (= 5 (:result result))))))

(deftest final-termination-test
  (testing "FINAL throws termination"
    (let [sb (sandbox/create-sandbox)]
      (is (thrown? clojure.lang.ExceptionInfo
                   (sandbox/eval-code sb "(FINAL \"the answer\")")))
      (try
        (sandbox/eval-code sb "(FINAL \"the answer\")")
        (catch clojure.lang.ExceptionInfo e
          (is (sandbox/termination? e))
          (let [result (sandbox/termination-result e)]
            (is (= :final (:type result)))
            (is (= "the answer" (:value result)))))))))

(deftest get-set-var-test
  (testing "get-var retrieves variable"
    (let [sb (sandbox/create-sandbox)]
      (sandbox/eval-code sb "(def x 100)")
      (is (= 100 (sandbox/get-var sb 'x)))))

  (testing "set-var! sets variable"
    (let [sb (sandbox/create-sandbox)]
      (sandbox/set-var! sb 'y "hello")
      (is (= "hello" (sandbox/get-var sb 'y)))))

  (testing "set-var! value accessible in eval"
    (let [sb (sandbox/create-sandbox)]
      (sandbox/set-var! sb 'data [1 2 3])
      (let [result (sandbox/eval-code sb "(reduce + data)")]
        (is (= 6 (:result result)))))))

(deftest eval-timeout-test
  (testing "uncancellable SCI tight loop hits the hard-cancel branch (Step G — no more soft-survives)"
    ;; SCI's loop/recur ignores Thread.interrupt, so the future never
    ;; resolves and future-cancel can't unblock it. Post-Step-G the
    ;; sandbox no longer keeps such evals alive in a pending registry —
    ;; they surface :status :timeout and the daemon thread is left to
    ;; finish on its own. (Soft-timeout-survives now lives in the agent
    ;; task manager — see :clj-sandbox-eval job type.)
    (let [sb (sandbox/create-sandbox)
          result (sandbox/eval-code sb "(loop [] (recur))" :timeout-ms 200)]
      (is (= :timeout (:status result)))
      (is (= 200 (:timeout-ms result)))
      (is (= "Evaluation timed out" (:error result))))))

(deftest history-test
  (testing "history records evaluations"
    (let [sb (sandbox/create-sandbox)]
      (sandbox/eval-code sb "(+ 1 1)")
      (sandbox/eval-code sb "(+ 2 2)")
      (let [hist (sandbox/get-history sb)]
        (is (= 2 (count hist)))
        (is (= "(+ 1 1)" (:code (first hist))))
        (is (= "(+ 2 2)" (:code (second hist))))))))

(deftest llm-query-fn-test
  (testing "llm-query function is accessible when provided via :bindings"
    (let [mock-fn (fn
                    ([_prompt] "mock response")
                    ([_prompt _ctx] "mock with context"))
          sb (sandbox/create-sandbox :bindings {'llm-query mock-fn})
          result (sandbox/eval-code sb "(llm-query \"test\")")]
      (is (= "mock response" (:result result)))))

  (testing "llm-query with sub-context"
    (let [mock-fn (fn
                    ([_prompt] "no context")
                    ([_prompt ctx] (str "got: " ctx)))
          sb (sandbox/create-sandbox :bindings {'llm-query mock-fn})
          result (sandbox/eval-code sb "(llm-query \"test\" \"my data\")")]
      (is (= "got: my data" (:result result))))))

(deftest json-in-sandbox-test
  (testing "parse-json defaults to string keys"
    (let [sb (sandbox/create-sandbox)]
      (is (= {"name" "alice" "age" 30}
             (:result (sandbox/eval-code sb
                                         "(parse-json \"{\\\"name\\\":\\\"alice\\\",\\\"age\\\":30}\")"))))))

  (testing "parse-json with :key-fn keyword returns keyword keys"
    (let [sb (sandbox/create-sandbox)]
      (is (= {:name "alice" :age 30}
             (:result (sandbox/eval-code sb
                                         "(parse-json \"{\\\"name\\\":\\\"alice\\\",\\\"age\\\":30}\" :key-fn keyword)"))))))

  (testing "to-json produces JSON string"
    (let [sb (sandbox/create-sandbox)]
      (is (= "{\"name\":\"alice\",\"age\":30}"
             (:result (sandbox/eval-code sb "(to-json {:name \"alice\" :age 30})"))))))

  (testing "clojure.data.json is reachable via fully-qualified forms; parse-json/to-json remain the canonical short aliases"
    ;; clojure.data.json is pre-registered in library-namespaces (sandbox.clj)
    ;; so the LLM can call `(clojure.data.json/read-str …)` directly. The
    ;; standalone `(require '[clojure.data.json :as json])` form succeeds —
    ;; and after it, the local alias `json/...` also resolves.
    (let [sb (sandbox/create-sandbox)
          req (sandbox/eval-code sb "(require '[clojure.data.json :as json])")
          fq  (sandbox/eval-code sb "(clojure.data.json/read-str \"{\\\"k\\\":1}\")")
          aliased (sandbox/eval-code
                   sb
                   "(require '[clojure.data.json :as json]) (json/read-str \"{\\\"k\\\":2}\")")]
      (is (str/blank? (or (:error req) ""))
          "require of a pre-registered namespace succeeds without error")
      (is (= {"k" 1} (:result fq))
          "fully-qualified form returns parsed value")
      (is (= {"k" 2} (:result aliased))
          ":as alias bound by require resolves in subsequent forms"))))

(deftest split-code-at-final-test
  (testing "no FINAL returns nil pre-final"
    (let [r (sandbox/split-code-at-final "(def x 1)\n(println x)")]
      (is (nil? (:pre-final r)))
      (is (false? (:has-final? r)))))

  (testing "FINAL only (no pre-code) returns nil pre-final"
    (let [r (sandbox/split-code-at-final "(FINAL \"hello\")")]
      (is (nil? (:pre-final r)))
      (is (true? (:has-final? r)))))

  (testing "FINAL with leading whitespace only"
    (let [r (sandbox/split-code-at-final "  \n  (FINAL \"hello\")")]
      (is (nil? (:pre-final r)))
      (is (true? (:has-final? r)))))

  (testing "code before FINAL is extracted"
    (let [r (sandbox/split-code-at-final "(println \"hello\")\n(def x 42)\n(FINAL (str \"answer: \" x))")]
      (is (some? (:pre-final r)))
      (is (true? (:has-final? r)))
      (is (not (str/includes? (:pre-final r) "FINAL")))))

  (testing "multi-line code before FINAL"
    (let [code "(println \"=== CONTEXT ===\")\n(inspect idx)\n\n(println \"TOOLS\")\n(def tools (list-tools))\n\n(FINAL (str \"Result: \" tools))"
          r (sandbox/split-code-at-final code)]
      (is (some? (:pre-final r)))
      (is (true? (:has-final? r)))
      (is (not (str/includes? (:pre-final r) "FINAL")))))

  (testing "FINAL inside expression (not at line start) is not split"
    (let [r (sandbox/split-code-at-final "(str \"x\" (FINAL \"y\"))")]
      (is (nil? (:pre-final r)))
      (is (false? (:has-final? r)))))

  (testing "FINAL indented at line start is detected"
    (let [r (sandbox/split-code-at-final "(def x 1)\n  (FINAL x)")]
      (is (some? (:pre-final r)))
      (is (true? (:has-final? r)))))

  (testing "string containing word FINAL is not matched"
    (let [r (sandbox/split-code-at-final "(println \"FINAL answer\")")]
      (is (false? (:has-final? r)))))

  (testing "complex expression before FINAL"
    (let [code "(let [x (+ 1 2)\n      y (* x 3)]\n  (println x y))\n(FINAL (str \"done\"))"
          r (sandbox/split-code-at-final code)]
      (is (some? (:pre-final r)))
      (is (true? (:has-final? r)))))

  (testing "pre-final code is executable in sandbox"
    (let [code "(println \"Computing...\")\n(def x (+ 1 2))\n(FINAL (str \"x=\" x))"
          {:keys [pre-final]} (sandbox/split-code-at-final code)
          sb (sandbox/create-sandbox :context nil)
          result (sandbox/eval-code sb pre-final :timeout-ms 5000)]
      (is (= "Computing...\n" (:output result)))
      (is (nil? (:error result)))
      ;; x should be defined for next iteration
      (is (= 3 (sandbox/get-var sb 'x))))))

;; ============================================================================
;; Fork & Parallel Execution Tests
;; ============================================================================

(deftest fork-sandbox-test
  (testing "forked sandbox inherits parent vars"
    (let [parent (sandbox/create-sandbox :bindings {'x 42 'data [1 2 3]})
          fork (sandbox/fork-sandbox parent)]
      (is (= 42 (:result (sandbox/eval-code fork "x"))))
      (is (= [1 2 3] (:result (sandbox/eval-code fork "data"))))))

  (testing "new def in fork does NOT appear in parent"
    (let [parent (sandbox/create-sandbox)
          fork (sandbox/fork-sandbox parent)]
      ;; Define a new var in the fork
      (sandbox/eval-code fork "(def fork-only 123)")
      (is (= 123 (:result (sandbox/eval-code fork "fork-only"))))
      ;; Parent should NOT see it
      (is (some? (:error (sandbox/eval-code parent "fork-only"))))))

  (testing "fork has independent output buffer"
    (let [parent (sandbox/create-sandbox)
          fork (sandbox/fork-sandbox parent)]
      (sandbox/eval-code fork "(println \"fork output\")")
      (sandbox/eval-code parent "(println \"parent output\")")
      (let [fork-hist (sandbox/get-history fork)
            parent-hist (sandbox/get-history parent)]
        (is (str/includes? (:output (first fork-hist)) "fork output"))
        (is (str/includes? (:output (first parent-hist)) "parent output")))))

  (testing "fork has independent history"
    (let [parent (sandbox/create-sandbox)
          _ (sandbox/eval-code parent "(+ 1 1)")
          fork (sandbox/fork-sandbox parent)]
      (sandbox/eval-code fork "(+ 2 2)")
      (is (= 1 (count (sandbox/get-history fork))))
      (is (= 1 (count (sandbox/get-history parent)))))))

(deftest eval-code-blocks-parallel-test
  (testing "single block — no fork overhead"
    (let [sb (sandbox/create-sandbox)
          {:keys [eval-results]} (sandbox/eval-code-blocks-parallel sb ["(+ 1 2)"])]
      (is (= 1 (count eval-results)))
      (is (= 3 (:result (first eval-results))))))

  (testing "empty blocks — returns empty results"
    (let [sb (sandbox/create-sandbox)
          {:keys [eval-results]} (sandbox/eval-code-blocks-parallel sb [])]
      (is (= 0 (count eval-results)))))

  (testing "multiple blocks execute concurrently and return results"
    (let [sb (sandbox/create-sandbox :bindings {'data [10 20 30]})
          blocks ["(reduce + data)"
                  "(count data)"
                  "(map inc data)"]
          {:keys [eval-results]} (sandbox/eval-code-blocks-parallel sb blocks)]
      (is (= 3 (count eval-results)))
      (is (= 60 (:result (nth eval-results 0))))
      (is (= 3 (:result (nth eval-results 1))))
      (is (= '(11 21 31) (:result (nth eval-results 2))))))

  (testing "error isolation — one block errors, others succeed"
    (let [sb (sandbox/create-sandbox)
          blocks ["(+ 1 2)"
                  "(/ 1 0)"
                  "(* 3 4)"]
          {:keys [eval-results]} (sandbox/eval-code-blocks-parallel sb blocks)]
      (is (= 3 (count eval-results)))
      (is (= 3 (:result (nth eval-results 0))))
      (is (some? (:error (nth eval-results 1))))
      (is (= 12 (:result (nth eval-results 2))))))

  (testing "FINAL in parallel block returns error"
    (let [sb (sandbox/create-sandbox)
          blocks ["(+ 1 2)"
                  "(FINAL \"done\")"]
          {:keys [eval-results]} (sandbox/eval-code-blocks-parallel sb blocks)]
      (is (= 2 (count eval-results)))
      (is (= 3 (:result (first eval-results))))
      (is (str/includes? (:error (second eval-results))
                         "FINAL cannot be called in parallel blocks"))))

  (testing "too many blocks rejected"
    (let [sb (sandbox/create-sandbox)
          blocks (vec (repeat 11 "(+ 1 1)"))
          {:keys [eval-results]} (sandbox/eval-code-blocks-parallel sb blocks)]
      (is (= 1 (count eval-results)))
      (is (str/includes? (:error (first eval-results)) "Too many parallel blocks"))))

  (testing "new defs in parallel blocks are merged into parent sandbox"
    (let [sb (sandbox/create-sandbox)
          blocks ["(def block1-var 100)"
                  "(def block2-var 200)"]
          _ (sandbox/eval-code-blocks-parallel sb blocks)]
      ;; Parent should see vars defined in parallel blocks
      (is (= 100 (:result (sandbox/eval-code sb "block1-var"))))
      (is (= 200 (:result (sandbox/eval-code sb "block2-var"))))))

  (testing "parallel block conflict — last block wins"
    (let [sb (sandbox/create-sandbox)
          blocks ["(def shared-var :from-block-1)"
                  "(def shared-var :from-block-2)"]
          _ (sandbox/eval-code-blocks-parallel sb blocks)]
      (is (= :from-block-2 (:result (sandbox/eval-code sb "shared-var"))))))

  (testing "vars from errored parallel blocks are still merged"
    (let [sb (sandbox/create-sandbox)
          blocks ["(def good-var 42)"
                  "(do (def partial-var 99) (/ 1 0))"]
          {:keys [eval-results]} (sandbox/eval-code-blocks-parallel sb blocks)]
      ;; Second block errors but partial-var was defined before the error
      (is (some? (:error (second eval-results))))
      (is (= 42 (:result (sandbox/eval-code sb "good-var"))))
      (is (= 99 (:result (sandbox/eval-code sb "partial-var")))))))

(deftest java-time-in-sandbox-test
  (testing "java.time.Instant/now returns an Instant"
    (let [sb (sandbox/create-sandbox)
          result (sandbox/eval-code sb "(str (java.time.Instant/now))")]
      (is (nil? (:error result)))
      (is (string? (:result result)))
      (is (re-find #"\d{4}-\d{2}-\d{2}T" (:result result)))))

  (testing "java.time.Instant/parse works"
    (let [sb (sandbox/create-sandbox)
          result (sandbox/eval-code sb "(str (java.time.Instant/parse \"2024-01-15T10:30:00Z\"))")]
      (is (= "2024-01-15T10:30:00Z" (:result result)))))

  (testing "java.time.Duration/between computes duration"
    (let [sb (sandbox/create-sandbox)
          result (sandbox/eval-code sb
                                    "(let [a (java.time.Instant/parse \"2024-01-01T00:00:00Z\")
                          b (java.time.Instant/parse \"2024-01-01T01:30:00Z\")]
                      (.toMinutes (java.time.Duration/between a b)))")]
      (is (= 90 (:result result)))))

  (testing "java.time.LocalDate/now returns a date"
    (let [sb (sandbox/create-sandbox)
          result (sandbox/eval-code sb "(str (java.time.LocalDate/now))")]
      (is (nil? (:error result)))
      (is (re-find #"\d{4}-\d{2}-\d{2}" (:result result)))))

  (testing "java.time.ZonedDateTime with ZoneId"
    (let [sb (sandbox/create-sandbox)
          result (sandbox/eval-code sb
                                    "(str (.getZone (java.time.ZonedDateTime/now (java.time.ZoneId/of \"UTC\"))))")]
      (is (= "UTC" (:result result)))))

  (testing "DateTimeFormatter formatting"
    (let [sb (sandbox/create-sandbox)
          result (sandbox/eval-code sb
                                    "(let [dt (java.time.LocalDate/parse \"2024-03-15\")
                          fmt (java.time.format.DateTimeFormatter/ofPattern \"dd/MM/yyyy\")]
                      (.format dt fmt))")]
      (is (= "15/03/2024" (:result result))))))

;; ============================================================================
;; Escape Repair Tests
;; ============================================================================

(deftest try-repair-escapes-test
  (testing "returns nil when no repair needed — valid escapes"
    (is (nil? (sandbox/try-repair-escapes "(println \"hello\\nworld\")")))
    (is (nil? (sandbox/try-repair-escapes "(println \"tab\\there\")")))
    (is (nil? (sandbox/try-repair-escapes "(println \"quote\\\"here\")")))
    (is (nil? (sandbox/try-repair-escapes "(println \"backslash\\\\here\")")))
    (is (nil? (sandbox/try-repair-escapes "(+ 1 2)"))))

  (testing "returns nil for code without strings"
    (is (nil? (sandbox/try-repair-escapes "(def x (+ 1 2))")))
    (is (nil? (sandbox/try-repair-escapes ""))))

  (testing "repairs \\d → \\\\d in strings (regex digit)"
    (let [repaired (sandbox/try-repair-escapes "(bash \"grep '\\d+' file.txt\")")]
      (is (some? repaired))
      (is (str/includes? repaired "\\\\d+"))))

  (testing "repairs \\s → \\\\s in strings (regex whitespace)"
    (let [repaired (sandbox/try-repair-escapes "(bash \"sed 's/\\s+/ /g' file\")")]
      (is (some? repaired))
      (is (str/includes? repaired "\\\\s+"))))

  (testing "repairs \\w → \\\\w in strings (regex word)"
    (let [repaired (sandbox/try-repair-escapes "(bash \"awk '/\\w+/{print}' file\")")]
      (is (some? repaired))
      (is (str/includes? repaired "\\\\w+"))))

  (testing "repairs multiple invalid escapes in one string"
    (let [repaired (sandbox/try-repair-escapes "(bash \"grep -P '\\d+\\s+\\w+' file\")")]
      (is (some? repaired))
      (is (str/includes? repaired "\\\\d+\\\\s+\\\\w+"))))

  (testing "does not modify valid \\n \\t \\\\ within same string as invalid escapes"
    (let [repaired (sandbox/try-repair-escapes "(bash \"echo '\\d+'\\necho done\")")]
      (is (some? repaired))
      ;; \d should be repaired, \n should remain as-is
      (is (str/includes? repaired "\\\\d+"))
      (is (str/includes? repaired "\\n"))))

  (testing "handles multiple strings in code"
    (let [repaired (sandbox/try-repair-escapes "(def a \"\\d\") (def b \"\\w\")")]
      (is (some? repaired))
      (is (str/includes? repaired "\\\\d"))
      (is (str/includes? repaired "\\\\w"))))

  (testing "repaired code evaluates successfully in SCI"
    (let [code "(def x \"regex: \\d+ \\w+ \\s+\")"
          repaired (sandbox/try-repair-escapes code)
          sb (sandbox/create-sandbox)
          result (sandbox/eval-code sb repaired)]
      (is (nil? (:error result)))
      ;; The string should contain literal backslash-d etc.
      (is (str/includes? (str (sandbox/get-var sb 'x)) "\\d+"))))

  (testing "does not repair outside of strings"
    ;; Backslash in code outside of strings should not be touched
    (let [code "(re-find #\"\\d+\" \"123\")"]
      (is (nil? (sandbox/try-repair-escapes code))))))
