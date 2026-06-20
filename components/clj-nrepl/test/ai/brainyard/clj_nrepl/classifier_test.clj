;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.clj-nrepl.classifier-test
  (:require [clojure.test :refer [deftest is testing]]
            [ai.brainyard.clj-nrepl.core.classifier :as cls]))

;; --- deny-list (the only eval-path check) ----------------------------------

(deftest deny-list-detection
  (testing "process-control + credential reaches are denied"
    (doseq [code ["(System/exit 0)"
                  "(prn :System/exit)"
                  "(.exec (Runtime/getRuntime) \"ls\")"
                  "(require 'ai.brainyard.aws-client.interface)"
                  "(shutdown-agents)"]]
      (is (cls/denied? code) (str "expected denied? for " code))
      (is (string? (cls/deny-reason code)))))

  (testing "innocent code is not denied"
    (is (not (cls/denied? "(+ 1 2)")))
    (is (not (cls/denied? "(def x 1)"))
        "mutation is no longer denied — nREPL is full-trust, deny-list only")
    (is (nil? (cls/deny-reason "(prn :hi)")))))
