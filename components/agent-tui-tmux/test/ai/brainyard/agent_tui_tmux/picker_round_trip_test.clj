;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: Apache-2.0
;; Licensed under the Apache License, Version 2.0 (the "License"); you may
;; not use this file except in compliance with the License. See LICENSE at
;; the repository root and CONTRIBUTING.md for contribution terms.

(ns ai.brainyard.agent-tui-tmux.picker-round-trip-test
  "Tests for the :open-picker frame + the host/emit-picker! side of the
   resume-picker round-trip.

   The `by-ui` orchestrator that consumed `:open-picker` was retired in
   May 2026; only the host-side emit/encode path is covered here, since
   the substrate is kept test-only (see CR-TUI-20)."
  (:require [ai.brainyard.agent-tui-tmux.core.control.protocol :as proto]
            [ai.brainyard.agent-tui-tmux.core.host :as host]
            [clojure.test :refer [deftest is testing]])
  (:import [java.util.concurrent ConcurrentHashMap TimeUnit]))

;; ----------------------------------------------------------------------------
;; Frame constructor
;; ----------------------------------------------------------------------------

(deftest open-picker-frame-shape
  (let [items [{:id "a" :label "first"} {:id "b" :label "second"}]
        f     (proto/open-picker "req-1" "Resume" items)]
    (is (= :open-picker (:type f)))
    (is (= "req-1" (:id f)))
    (is (= "Resume" (:title f)))
    (is (= items (:items f))))
  (testing "nil title coerces to empty string"
    (is (= "" (:title (proto/open-picker "x" nil [])))))
  (testing "items vec is preserved as a vec"
    (is (vector? (:items (proto/open-picker "x" "t" '({:id "a"})))))))

(deftest popup-result-with-selection
  (let [r (proto/popup-result "req-1" :submitted nil "chosen-id")]
    (is (= :popup-result (:type r)))
    (is (= "req-1" (:id r)))
    (is (= :submitted (:status r)))
    (is (= "chosen-id" (:selection r))))
  (testing "without selection, the older 3-arity still works"
    (let [r (proto/popup-result "x" :cancelled nil)]
      (is (= :popup-result (:type r)))
      (is (not (contains? r :selection))))))

;; ----------------------------------------------------------------------------
;; emit-picker! + reply path
;; ----------------------------------------------------------------------------

(defn- mock-host []
  ;; A minimal host record stub. emit-picker! reads :!pending-popups
  ;; and calls (emit! host frame); we capture the frame via a no-op
  ;; on-input bridge, but the emit happens via host/emit! which writes
  ;; to !active-conn. For our tests we only need the future-registration
  ;; behavior, not the wire write — stub the active conn.
  {:!pending-popups (atom (ConcurrentHashMap.))
   :!active-conn    (atom nil)
   :!pending-out    (atom clojure.lang.PersistentQueue/EMPTY)})

(deftest emit-picker-registers-future-and-completes-on-reply
  (let [h  (mock-host)
        items [{:id "a" :label "alpha"}]
        cf (host/emit-picker! h "Pick" items "fixed-id")]
    (is (instance? java.util.concurrent.CompletableFuture cf))
    (is (.containsKey ^ConcurrentHashMap @(:!pending-popups h) "fixed-id"))
    ;; Simulate the orchestrator reply via the host's inbound dispatch.
    (let [reply (proto/popup-result "fixed-id" :submitted nil "a")]
      (#'host/handle-inbound h nil reply)
      (is (.isDone cf))
      (is (= reply (.get cf 100 TimeUnit/MILLISECONDS))))))

(deftest await-picker-reply-extracts-selection
  (let [h  (mock-host)
        cf (host/emit-picker! h "Pick" [{:id "a"}] "id-1")]
    ;; Submit
    (#'host/handle-inbound h nil (proto/popup-result "id-1" :submitted nil "a"))
    (is (= "a" (host/await-picker-reply! cf {:timeout-ms 100})))))

(deftest await-picker-reply-cancel-returns-nil
  (let [h  (mock-host)
        cf (host/emit-picker! h "Pick" [{:id "a"}] "id-2")]
    (#'host/handle-inbound h nil (proto/popup-result "id-2" :cancelled nil nil))
    (is (nil? (host/await-picker-reply! cf {:timeout-ms 100})))))

(deftest await-picker-reply-timeout-returns-nil
  (let [h  (mock-host)
        cf (host/emit-picker! h "Pick" [{:id "a"}] "id-3")]
    (is (nil? (host/await-picker-reply! cf {:timeout-ms 30})))))
