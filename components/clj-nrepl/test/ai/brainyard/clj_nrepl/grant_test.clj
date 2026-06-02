;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.clj-nrepl.grant-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [ai.brainyard.clj-nrepl.core.grant :as grant]))

(use-fixtures :each (fn [t] (grant/revoke!) (t) (grant/revoke!)))

(deftest grant-lifecycle
  (is (not (grant/active?)))
  (grant/grant! :scope :read-only :ttl-ms 60000)
  (is (grant/active?))
  (is (= :read-only (grant/scope)))
  (grant/revoke!)
  (is (not (grant/active?))))

(deftest expired-grant-self-prunes
  (grant/grant! :scope :read-only :ttl-ms 1)
  (Thread/sleep (long 10))
  (is (not (grant/active?))
      "TTL of 1ms should be expired by now"))

(deftest env-bootstrap-parses-duration
  (testing "BRAINYARD_NREPL_GRANT=read-only:15m"
    (let [g (grant/maybe-grant-from-env! "read-only:15m")]
      (is (= :read-only (:scope g)))
      (is (= :read-only (grant/scope)))))

  (testing "bare scope uses default TTL"
    (grant/revoke!)
    (grant/maybe-grant-from-env! "read-only")
    (is (= :read-only (grant/scope))))

  (testing "unknown scope rejected"
    (grant/revoke!)
    (grant/maybe-grant-from-env! "rootkit:1h")
    (is (not (grant/active?))))

  (testing "blank input is a no-op"
    (grant/revoke!)
    (grant/maybe-grant-from-env! "")
    (grant/maybe-grant-from-env! nil)
    (is (not (grant/active?)))))
