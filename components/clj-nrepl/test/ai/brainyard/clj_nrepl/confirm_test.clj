;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: Apache-2.0
(ns ai.brainyard.clj-nrepl.confirm-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [ai.brainyard.clj-nrepl.core.confirm :as confirm]))

(use-fixtures :each (fn [t]
                      (confirm/revoke-confirmation!)
                      (confirm/set-confirm-fn! nil)
                      (t)
                      (confirm/revoke-confirmation!)
                      (confirm/set-confirm-fn! nil)))

(deftest no-confirm-fn-defaults-allow
  (testing "absent confirm-fn → allow with audit"
    (is (true? (confirm/confirm-mutation! "s-1" "(def x 1)")))
    (is (confirm/confirmed? "s-1"))))

(deftest confirm-fn-called-once-per-session
  (let [calls (atom 0)]
    (confirm/set-confirm-fn! (fn [_] (swap! calls inc) true))
    (is (true? (confirm/confirm-mutation! "s-A" "(def x 1)")))
    (is (true? (confirm/confirm-mutation! "s-A" "(def y 2)"))
        "second mutation in same session passes without re-prompt")
    (is (= 1 @calls) "confirm-fn called exactly once for session s-A")
    (is (true? (confirm/confirm-mutation! "s-B" "(def x 1)"))
        "different session triggers a fresh prompt")
    (is (= 2 @calls))))

(deftest declined-confirmation-blocks
  (confirm/set-confirm-fn! (fn [_] false))
  (is (false? (confirm/confirm-mutation! "s-X" "(def x 1)")))
  (is (not (confirm/confirmed? "s-X"))
      "decline does NOT mark session confirmed — next call re-prompts"))

(deftest revoke-clears-session
  (confirm/set-confirm-fn! (fn [_] true))
  (confirm/confirm-mutation! "s-R" "(def x 1)")
  (is (confirm/confirmed? "s-R"))
  (confirm/revoke-confirmation! "s-R")
  (is (not (confirm/confirmed? "s-R"))))

(deftest confirm-fn-exception-treated-as-deny
  (confirm/set-confirm-fn! (fn [_] (throw (ex-info "boom" {}))))
  (is (false? (confirm/confirm-mutation! "s-E" "(def x 1)")))
  (is (not (confirm/confirmed? "s-E"))))
