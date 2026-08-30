
;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.behavior-tree.interrupted-test
  "A cancelled turn must not be mistaken for a model failure.

   `dspy-action`'s handler is `(catch Exception e ...)`, which catches
   InterruptedException too — so a cancellation was classified (falling to
   classify-error's \"unknown => malformed\" default), turned into p/failure and
   RE-PROMPTED. A live cancel showed \"Cancelled.\" followed by two spurious
   \"Malformed model output — re-prompting\" lines, with iterations completing in
   5-9ms: LLM calls failing instantly on the still-interrupted thread."
  (:require [clojure.test :refer [deftest is testing]]
            [ai.brainyard.behavior-tree.core.dspy-action :as dspy]))

(deftest recognises-a-bare-interrupt
  (is (true? (dspy/interrupted? (InterruptedException. "cancelled")))))

(deftest recognises-a-WRAPPED-interrupt
  (testing "by the time it surfaces it may be wrapped by the retry wrapper or a
            Task's failure callback"
    (is (true? (dspy/interrupted?
                (ex-info "llm call failed" {} (InterruptedException. "cancelled")))))
    (is (true? (dspy/interrupted?
                (RuntimeException. "outer"
                                   (ex-info "inner" {} (InterruptedException.))))))))

(deftest ordinary-failures-are-not-interrupts
  (testing "the whole point: a real model failure must still be classified"
    (is (false? (dspy/interrupted? (ex-info "JSON parse failed" {:raw-text "{"}))))
    (is (false? (dspy/interrupted? (RuntimeException. "connection reset"))))
    (is (false? (dspy/interrupted? (java.io.IOException. "stream closed"))))))

(deftest survives-a-cyclic-cause-chain
  (testing "depth-bounded — a cause cycle must not hang the classifier"
    (let [a (RuntimeException. "a")
          b (RuntimeException. "b" a)]
      (try (.initCause a b) (catch Throwable _))   ;; may be refused; fine either way
      (is (false? (dspy/interrupted? b))))))
