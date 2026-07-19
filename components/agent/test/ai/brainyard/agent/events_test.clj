;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.events-test
  "Tests for the user-defined event registry + emit path (Phase 1 of
   docs/design/event-bus-and-reactor.md): name coercion, spec store, dynamic
   registry fold, and emit-event! (payload validation + bus fire)."
  (:require [clojure.test :refer [deftest testing is are use-fixtures]]
            [ai.brainyard.agent.common.events :as events]
            [ai.brainyard.agent.core.config :as config]
            [ai.brainyard.agent.core.hooks :as hooks]
            [clojure.java.io :as io]))

(def ^:dynamic *pdir* nil)

(defn temp-dir-fixture [f]
  (let [dir (io/file (System/getProperty "java.io.tmpdir")
                     (str "events-test-" (System/currentTimeMillis) "-" (rand-int 100000)))]
    (.mkdirs dir)
    (binding [*pdir* (.getPath dir)]
      (try (f)
           (finally
             (hooks/reset-hooks!)
             (hooks/unregister-event! :order/shipped)
             (doseq [^java.io.File x (reverse (file-seq dir))] (.delete x)))))))

(use-fixtures :each temp-dir-fixture)

;; ============================================================================
;; Name coercion
;; ============================================================================

(deftest event-key-coercion
  (testing "valid forms"
    (are [in out] (= out (events/->event-key in))
      :order/shipped  :order/shipped
      "order/shipped" :order/shipped
      ":a/b"          :a/b
      "deploy-done"   :deploy-done))
  (testing "invalid → nil"
    (are [in] (nil? (events/->event-key in))
      "  " "" nil 42)))

;; ============================================================================
;; Store round-trip
;; ============================================================================

(deftest store-round-trip
  (is (empty? (events/list-defs *pdir*)))
  (events/write-def! *pdir* {:name :order/shipped :desc "shipped"
                             :payload-schema [:map [:order-id :string]] :created 1})
  (is (= :order/shipped (:name (events/read-def *pdir* :order/shipped))))
  (is (= 1 (count (events/list-defs *pdir*))))
  (is (nil? (events/read-def *pdir* :never/defined)))
  (is (true? (events/delete-def! *pdir* :order/shipped)))
  (is (nil? (events/read-def *pdir* :order/shipped))))

;; ============================================================================
;; Dynamic-registry fold
;; ============================================================================

(deftest load-folds-into-known-event
  (hooks/unregister-event! :order/shipped)
  (is (false? (hooks/known-event? :order/shipped)))
  (events/write-def! *pdir* {:name :order/shipped :created 1})
  (is (= 1 (events/load-project-events! *pdir*)))
  (is (true? (hooks/known-event? :order/shipped)) "user event recognized after load")
  (is (true? (hooks/known-event? :agent.ask/post)) "static catalog still recognized"))

;; ============================================================================
;; emit-event!  (project-dir redirected to the temp store)
;; ============================================================================

(deftest emit-fires-and-validates
  (with-redefs [config/project-dir (fn ([] *pdir*) ([_] *pdir*))]
    (events/write-def! *pdir* {:name :order/shipped
                               :payload-schema [:map [:order-id :string]] :created 1})
    (let [seen (atom nil)]
      (hooks/register-hook! :order/shipped ::probe (fn [m] (reset! seen m)) :source ::test)

      (testing "valid payload fires + augments the delivered map"
        (let [r (events/emit-event! :order/shipped {:order-id "A-91"})]
          (is (= :order/shipped (:fired r)))
          (is (= 1 (:subscribers r)))
          (is (= "A-91" (:order-id @seen)))
          (is (= :order/shipped (:event @seen)))
          (is (= :emit (:source @seen)))))

      (testing "invalid payload is rejected and does NOT fire"
        (reset! seen nil)
        (let [r (events/emit-event! :order/shipped {:order-id 42})]
          (is (some? (:error r)))
          (is (nil? @seen))))

      (testing "unregistered event still fires, flagged with a note"
        (let [r (events/emit-event! :ad/hoc {:x 1})]
          (is (= :ad/hoc (:fired r)))
          (is (some? (:note r)))))

      (testing "invalid event name errors"
        (is (some? (:error (events/emit-event! "  " {}))))))))

;; ============================================================================
;; Payload-schema key coercion
;;
;; The `event$emit` tool crosses a JSON/marshalling boundary that stringifies
;; top-level payload keys. A naturally keyword-keyed schema like
;; [:map [:order-id :string]] must still validate such a payload, and the
;; delivered event map must carry KEYWORD keys so the reactor's keyword-based
;; {{key}} interpolation (`(get payload :order-id)`) resolves rather than
;; rendering blank. See docs/design/event-bus-and-reactor.md.
;; ============================================================================

(deftest emit-coerces-string-keys-to-keyword
  (with-redefs [config/project-dir (fn ([] *pdir*) ([_] *pdir*))]
    (events/write-def! *pdir* {:name :order/shipped
                               :payload-schema [:map [:order-id :string] [:carrier :string]]
                               :created 1})
    (let [seen (atom nil)]
      (hooks/register-hook! :order/shipped ::probe (fn [m] (reset! seen m)) :source ::test)

      (testing "keyword schema validates a string-keyed payload and delivers keyword keys"
        (let [r (events/emit-event! :order/shipped {"order-id" "A-100" "carrier" "UPS"})]
          (is (= :order/shipped (:fired r)))
          (is (nil? (:error r)) "string-keyed payload must not be rejected")
          (is (= "A-100" (:order-id @seen)) "delivered map carries keyword keys (interpolation-ready)")
          (is (= "UPS" (:carrier @seen)))
          (is (not (contains? @seen "order-id")) "stringified keys are coerced away, not duplicated")))

      (testing "already-keyword payload is unchanged (idempotent)"
        (reset! seen nil)
        (let [r (events/emit-event! :order/shipped {:order-id "B-200" :carrier "Fedex"})]
          (is (= :order/shipped (:fired r)))
          (is (= "B-200" (:order-id @seen)))))

      (testing "coercion still catches a genuinely wrong value type"
        (reset! seen nil)
        (let [r (events/emit-event! :order/shipped {"order-id" 42 "carrier" "UPS"})]
          (is (some? (:error r)))
          (is (nil? @seen)))))))
