;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.reactor-test
  "Tests for the event reactor (Phase 2 of docs/design/event-bus-and-reactor.md):
   rule store, payload matching, {{template}} interpolation, action sinks
   (:turn/:artifact/:emit), and the bus handler's session-scope + fire-budget
   gating."
  (:require [clojure.test :refer [deftest testing is are use-fixtures]]
            [ai.brainyard.agent.common.reactor :as reactor]
            [ai.brainyard.agent.common.events :as events]
            [ai.brainyard.agent.core.config :as config]
            [ai.brainyard.agent.core.hooks :as hooks]
            [ai.brainyard.agent.core.protocol :as proto]
            [ai.brainyard.agent.core.tool :as tool]
            [malli.core :as m]
            [clojure.java.io :as io]))

(def ^:dynamic *pdir* nil)

(defn temp-dir-fixture [f]
  (let [dir (io/file (System/getProperty "java.io.tmpdir")
                     (str "reactor-test-" (System/currentTimeMillis) "-" (rand-int 100000)))]
    (.mkdirs dir)
    (binding [*pdir* (.getPath dir)]
      (try (f)
           (finally
             (reactor/reset-state!)
             (hooks/reset-hooks!)
             (doseq [^java.io.File x (reverse (file-seq dir))] (.delete x)))))))

(use-fixtures :each temp-dir-fixture)

;; Reach the private helpers under test.
(def ^:private pmatch    @#'reactor/payload-match?)
(def ^:private interp    @#'reactor/interpolate)
(def ^:private interp-do @#'reactor/interp-do)
(def ^:private rules-for @#'reactor/rules-for)
(def ^:private desired   @#'reactor/desired-events)
(def ^:private make-handler @#'reactor/make-handler)
(def ^:private exec      @#'reactor/execute-action!)

;; ============================================================================
;; Store
;; ============================================================================

(deftest store-round-trip
  (is (empty? (reactor/list-specs *pdir*)))
  (reactor/write-spec! *pdir* {:id "r1" :on :order/shipped
                               :do {:as :turn :text "hi"} :enabled true :created 1})
  (is (= "r1" (:id (reactor/read-spec *pdir* "r1"))))
  (is (= 1 (count (reactor/list-specs *pdir*))))
  (is (true? (reactor/delete-spec! *pdir* "r1")))
  (is (nil? (reactor/read-spec *pdir* "r1"))))

(deftest id-validation
  (are [id ok?] (= ok? (reactor/valid-id? id))
    "on-ship" true
    "a1"      true
    "Bad Id"  false
    "-lead"   false
    ""        false))

;; ============================================================================
;; Matching + interpolation
;; ============================================================================

(deftest payload-matching
  (is (true?  (pmatch nil {:a 1})))
  (is (true?  (pmatch {} {:a 1})))
  (is (true?  (pmatch {:region "us"} {:region "us" :x 1})))
  (is (false? (pmatch {:region "us"} {:region "eu"})))
  (is (false? (pmatch {:region "us"} {}))))

(deftest interpolation
  (is (= "Order A-91 shipped" (interp "Order {{order-id}} shipped" {:order-id "A-91"})))
  (is (= "none " (interp "none {{missing}}" {})))
  (is (= {:as :turn :text "x"} (interp-do {:as :turn :text "{{n}}"} {:n "x"})))
  (is (= {:as :turn :await? false} (interp-do {:as :turn :await? false} {}))
      "non-string values pass through untouched"))

(deftest interpolation-key-form-agnostic
  (testing "a {{token}} resolves against a string-keyed payload (no-schema events)"
    (is (= "Order B-100 via Fedex"
           (interp "Order {{order-id}} via {{carrier}}"
                   {"order-id" "B-100" "carrier" "Fedex"})))
    (is (= "Order A-100"
           (interp "Order {{order-id}}" {:order-id "A-100"}))
        "keyword-keyed payload still works"))
  (testing "interpolation recurses into an :emit sink's nested :payload map"
    (is (= {:as :emit :event :app/notify-needed :payload {"order-id" "B-100"}}
           (interp-do {:as :emit :event :app/notify-needed
                       :payload {"order-id" "{{order-id}}"}}
                      {"order-id" "B-100"}))
        "nested template value is substituted, not passed through literally")))

;; ============================================================================
;; rules-for / desired-events (disk-backed)
;; ============================================================================

(deftest rules-selection
  (reactor/write-spec! *pdir* {:id "a" :on :order/shipped :do {:as :turn} :enabled true :created 1})
  (reactor/write-spec! *pdir* {:id "b" :on :order/shipped :do {:as :turn} :enabled false :created 2})
  (reactor/write-spec! *pdir* {:id "c" :on :deploy/done   :do {:as :emit} :enabled true :created 3})
  (is (= ["a"] (mapv :id (rules-for *pdir* :order/shipped))) "enabled + matching :on only")
  (is (= #{:order/shipped :deploy/done} (desired *pdir*))))

;; ============================================================================
;; Action sinks
;; ============================================================================

(deftest emit-action-delivers
  (with-redefs [config/project-dir (fn ([] *pdir*) ([_] *pdir*))]
    (let [seen (atom nil)]
      (hooks/register-hook! :downstream/x ::probe (fn [m] (reset! seen m)) :source ::t)
      (is (= :emit (exec {:mock :agent}
                         {:id "re" :do {:as :emit :event "downstream/x" :payload {:v 1}}}
                         {:src 9})))
      (is (= 1 (:v @seen))))))

(deftest turn-action-interpolates-and-submits
  (let [submitted (atom nil)]
    (with-redefs-fn {#'reactor/interactive-host? (constantly true)
                     #'reactor/!submit-turn (delay (fn [_a t o] (reset! submitted [t o])))}
      (fn [] (is (= :turn (exec :AG {:id "rt" :do {:as :turn :text "Order {{oid}}"}} {:oid "Z"})))))
    (is (= "Order Z" (first @submitted)))
    (is (= :reaction (:source (second @submitted))))))

(deftest turn-action-headless-skipped
  (with-redefs-fn {#'reactor/interactive-host? (constantly false)}
    (fn [] (is (= :skipped-headless (exec :AG {:id "rt" :do {:as :turn :text "x"}} {}))))))

(deftest context-action-appends-to-inbox
  (let [inbox (atom {})]
    (with-redefs-fn {#'proto/get-st-memory-init (fn [_] inbox)}
      (fn []
        (is (= :context (exec :AG {:id "rc" :on :order/shipped
                                   :do {:as :context :text "shipped {{oid}}"}}
                              {:oid "Z"})))))
    (is (= "shipped Z"     (-> @inbox :events-inbox first :text)))
    (is (= :order/shipped  (-> @inbox :events-inbox first :event)))))

(deftest context-inbox-caps-and-keeps-newest
  (let [inbox (atom {})]
    (with-redefs-fn {#'proto/get-st-memory-init (fn [_] inbox)}
      (fn []
        (dotimes [i 30]
          (exec :AG {:id "rc" :on :e/x :do {:as :context :text (str "n" i)}} {}))))
    (is (= 20 (count (:events-inbox @inbox))) "capped at max-inbox")
    (is (= "n29" (-> @inbox :events-inbox last :text)) "keeps newest")))

(deftest memory-action-uses-shared-writer
  (let [pcd (str (io/file (System/getProperty "java.io.tmpdir")
                          (str "reactor-mem-" (System/currentTimeMillis) "-" (rand-int 100000))))]
    (with-redefs [config/project-config-dir (fn [_] pcd)
                  config/get-config (fn [& _] {:project-dir pcd})]
      (is (= :memory (exec :AG {:id "rm" :on :x/y
                                :do {:as :memory :slug "note-1" :text "hello"}} {}))))
    (is (.exists (io/file pcd "memory" "note-1.md")))
    (doseq [^java.io.File f (reverse (file-seq (io/file pcd)))] (.delete f))))

;; ============================================================================
;; Bus handler — session scope + budget
;; ============================================================================

(deftest handler-session-scope
  (reactor/write-spec! *pdir* {:id "r1" :on :order/shipped
                               :do {:as :turn} :enabled true :created 1})
  (let [fired (atom [])]
    (with-redefs-fn {#'reactor/execute-action! (fn [_ rule _] (swap! fired conj (:id rule)) :turn)
                     #'config/get-config       (fn [& _] 50)}
      (fn []
        (let [h (make-handler :AG "sid-1" *pdir* :order/shipped)]
          (h {:session-id "sid-1"})   ; same session → fire
          (h {:session-id "other"})   ; other session → skip
          (h {}))))                    ; no session (global) → fire
    (is (= ["r1" "r1"] @fired))))

(deftest handler-budget-caps-cascade
  (reactor/write-spec! *pdir* {:id "r1" :on :order/shipped
                               :do {:as :turn} :enabled true :created 1})
  (let [fired (atom 0)]
    (with-redefs-fn {#'reactor/execute-action! (fn [_ _ _] (swap! fired inc) :turn)
                     #'config/get-config       (fn [& _] 3)}
      (fn []
        (let [h (make-handler :AG "sid-b" *pdir* :order/shipped)]
          (dotimes [_ 10] (h {:session-id "sid-b"})))))
    (is (= 3 @fired) "per-session budget stops a runaway cascade")))

;; ============================================================================
;; reaction$add :do input schema — precise, not :any
;; ============================================================================

(deftest do-schema-precision
  (let [in-sch   (get-in (tool/get-tool-defs :id :reaction$add) [:meta :input-schema])
        schema   (tool/inputs->malli-map-schema in-sch)
        decode   (fn [args] (m/decode schema args tool/llm-args-transformer))
        valid?   (fn [args] (nil? (m/explain schema (decode args))))]
    (testing "schema->type builds an LLM JSON schema without throwing"
      (is (map? (tool/schema->type in-sch)))
      (is (= [:as] (:required (:do (tool/schema->type in-sch)))) ":as is required"))
    (testing "string-form :as/:event coerce to keywords (JSON tool-call path)"
      (is (= {:as :emit :event :downstream/pong :payload {"v" 1}}
             (:do (decode {:on "x" :do {:as "emit" :event "downstream/pong" :payload {:v 1}}}))))
      (is (valid? {:on "x" :do {:as "context" :text "hi"}})))
    (testing "keyword-form passes through (code-fence path)"
      (is (valid? {:on "x" :do {:as :turn :text "hi"}}))
      (is (valid? {:on "x" :do {:as :artifact :name "db" :path "/tmp/x" :pin? true}})))
    (testing "invalid / missing :as is rejected"
      (is (not (valid? {:on "x" :do {:as "bogus" :text "x"}})))
      (is (not (valid? {:on "x" :do {:text "x"}}))))
    (testing "non-map :do is rejected"
      (is (not (valid? {:on "x" :do "notmap"}))))
    (testing "explicit nil on a nested optional is tolerated"
      (is (valid? {:on "x" :do {:as :context :text "x" :path nil}})))
    (testing ":match accepts a filter map"
      (is (valid? {:on "x" :do {:as :turn :text "x"} :match {:region "us"}})))))

(deftest handler-respects-match-filter
  (reactor/write-spec! *pdir* {:id "us-only" :on :order/shipped :match {:region "us"}
                               :do {:as :turn} :enabled true :created 1})
  (let [fired (atom [])]
    (with-redefs-fn {#'reactor/execute-action! (fn [_ rule _] (swap! fired conj (:id rule)) :turn)
                     #'config/get-config       (fn [& _] 50)}
      (fn []
        (let [h (make-handler :AG "s" *pdir* :order/shipped)]
          (h {:session-id "s" :region "us"})    ; matches
          (h {:session-id "s" :region "eu"}))))  ; filtered out
    (is (= ["us-only"] @fired))))
