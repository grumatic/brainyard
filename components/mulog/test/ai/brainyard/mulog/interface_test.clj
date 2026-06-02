;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.mulog.interface-test
  (:require [clojure.test :refer [deftest testing is]]
            [ai.brainyard.mulog.interface :as mulog]))

(deftest interface-functions-exist-test
  (testing "Core logging functions are defined"
    (is (some? (var mulog/log)))
    (is (some? (var mulog/trace))))

  (testing "Context functions are defined"
    (is (fn? mulog/set-global-context!))
    (is (fn? mulog/update-global-context!))
    (is (some? (var mulog/with-context)))
    (is (fn? mulog/app-context)))

  (testing "Publisher management functions are defined"
    (is (fn? mulog/start-publisher!))
    (is (fn? mulog/stop-publisher!))
    (is (fn? mulog/start-publishers!))
    (is (fn? mulog/stop-publishers!)))

  (testing "Custom publisher helpers are defined"
    (is (fn? mulog/agent-buffer))
    (is (fn? mulog/buffer-items))
    (is (fn? mulog/buffer-clear))
    (is (fn? mulog/simple-publisher))))

(deftest context-test
  (testing "app-context creates standard context"
    (let [ctx (mulog/app-context {:app-name "test-app"
                                  :version "1.0.0"
                                  :env "test"})]
      (is (= "test-app" (:app-name ctx)))
      (is (= "1.0.0" (:version ctx)))
      (is (= "test" (:env ctx)))
      (is (string? (:host ctx)))
      (is (number? (:pid ctx))))))

(deftest publisher-lifecycle-test
  (testing "Start and stop console publisher"
    (let [handle (mulog/start-publisher! {:type :console :pretty? true})]
      (is (fn? handle))
      (mulog/stop-publisher! handle))))
