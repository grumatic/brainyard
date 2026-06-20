;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.clj-nrepl.integration-test
  "End-to-end smoke for the Phase-1 surface — start server, route through
   code$eval, exercise both the registry (:nrepl arm) and the CoAct fence
   parser (now rejecting per-fence backend modifiers as fence errors)."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [ai.brainyard.clj-nrepl.interface :as n]
            [ai.brainyard.clj-sandbox.interface :as s]
            [ai.brainyard.agent.core.tool :as tool]
            ;; Trigger registration of code$eval.
            [ai.brainyard.agent.common.code-eval]))

(defn- with-running-server [t]
  (try
    (n/start-server! :bind "127.0.0.1" :port 0)
    (t)
    (finally
      (try (n/stop-server!) (catch Exception _)))))

(use-fixtures :each with-running-server)

(deftest code-eval-registered
  (is (some? (tool/get-tool-defs :id :code$eval))
      "code$eval should be in the unified tool registry"))

(deftest invoke-tool-roundtrip
  (testing ":nrepl arm reaches the live runtime"
    (let [r (tool/invoke-tool :code$eval :code "(+ 1 2)" :backend :nrepl)]
      (is (= "3" (:result r)))
      (is (= :nrepl (:backend r)))))

  (testing ":sandbox arm returns a fence-path pointer"
    (let [r (tool/invoke-tool :code$eval :code "(+ 1 2)" :backend :sandbox)]
      (is (= :sandbox (:backend r)))
      (is (re-find #"CoAct" (:error r))))))

(deftest fence-parser-rejects-trailing-fence-text
  (testing "bare ```clojure parses with no fence-error"
    (let [[blk] (s/extract-all-code-blocks-multi
                 "```clojure\n(+ 1 2)\n```")]
      (is (= "clojure" (:lang blk)))
      (is (nil? (:fence-error blk)))))

  (testing "```clojure :nrepl is a fence-error (no per-fence backend route)"
    (let [[blk] (s/extract-all-code-blocks-multi
                 "```clojure :nrepl\n(+ 1 2)\n```")]
      (is (string? (:fence-error blk))
          "trailing :nrepl on the fence must surface as fence-error")))

  (testing "non-clj fences still parse"
    (let [[blk] (s/extract-all-code-blocks-multi
                 "```bash\necho hi\n```")]
      (is (= "bash" (:lang blk)))
      (is (nil? (:fence-error blk))))))
