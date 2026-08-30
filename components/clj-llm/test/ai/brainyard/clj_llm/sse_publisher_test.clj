;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.clj-llm.sse-publisher-test
  "SPIKE §18 slice 2: the pushed-body → Flow bridge, against a FAKE publisher.

   Fake rather than real HTTP on purpose. The property under test is that
   cancelling the Flow reaches `Subscription.cancel()` — the thing that lets
   the JDK abort a streaming exchange, and the reason `.close` on
   `:active-http` can eventually go. A fake publisher can *observe* that
   directly; a real socket can only imply it."
  (:require [clojure.test :refer [deftest is testing]]
            [ai.brainyard.clj-llm.core.sse :as sse]
            [ai.brainyard.clj-llm.core.sse-publisher :as sse-pub]
            [missionary.core :as m])
  (:import [java.io BufferedReader StringReader]
           [java.nio ByteBuffer]
           [java.nio.charset StandardCharsets]
           [java.util.concurrent Flow$Publisher Flow$Subscription]))

(defn- ->item
  "One pushed item, as the JDK delivers it: a List<ByteBuffer>."
  [^String s]
  (java.util.List/of (ByteBuffer/wrap (.getBytes s StandardCharsets/UTF_8))))

(defn- fake-publisher
  "A `Flow.Publisher` emitting `chunks`, honouring demand from its own thread —
   the shape the JDK's HTTP body publisher has.

   Records into `!state`: `:cancelled?`, `:requested` (total demand seen), and
   `:emitted` (how many items actually went out), so a test can distinguish
   'stopped asking' from 'stopped sending'."
  ([chunks !state] (fake-publisher chunks !state 0))
  ([chunks !state delay-ms]
  (reify Flow$Publisher
    (subscribe [_ sub]
      (let [!demand (atom 0)
            !idx    (atom 0)
            done?   (atom false)]
        (.onSubscribe
         sub
         (reify Flow$Subscription
           (request [_ n] (swap! !state update :requested + n) (swap! !demand + n))
           (cancel [_] (swap! !state assoc :cancelled? true) (reset! done? true))))
        (doto (Thread.
               (fn []
                 (loop []
                   (cond
                     @done? nil
                     (pos? @!demand)
                     (let [i @!idx]
                       (swap! !demand dec)
                       (if (< i (count chunks))
                         (do (swap! !idx inc)
                             (swap! !state update :emitted inc)
                             (.onNext sub (->item (nth chunks i)))
                             (when (pos? delay-ms) (Thread/sleep (long delay-ms)))
                             (recur))
                         (do (reset! done? true) (.onComplete sub))))
                     :else (do (Thread/sleep 5) (recur))))))
          (.setDaemon true)
          (.start)))))))

(defn- new-state [] (atom {:cancelled? false :requested 0 :emitted 0}))

(defn- drain
  "Run the event flow to completion, returning the events."
  [flow]
  (let [p (promise)]
    ((m/reduce conj [] flow) #(deliver p {:ok %}) #(deliver p {:err %}))
    (let [r (deref p 10000 {:timeout true})]
      (if (contains? r :ok) (:ok r) (throw (ex-info "flow failed" r))))))

;; ============================================================================

(deftest bridge-produces-the-same-events-as-the-blocking-reader
  (testing "the whole chain agrees with sse/read-sse-events, and the chunking
            the publisher happens to use makes no difference"
    (let [body (str "event: message_start\ndata: {\"a\":1}\n\n"
                    "data: {\"b\":2}\n\n"
                    "data: [DONE]\n\n")
          expected (doall (sse/read-sse-events (BufferedReader. (StringReader. body))))]
      (doseq [[label chunks] {"whole"     [body]
                              "by-9"      (mapv #(apply str %) (partition-all 9 body))
                              "by-1-byte" (mapv str body)}]
        (let [!s (new-state)]
          (is (= expected
                 (drain (sse-pub/publisher->event-flow (fake-publisher chunks !s))))
              (str "chunked " label)))))))

(deftest cancelling-the-flow-cancels-the-subscription
  (testing "THE POINT OF SLICE 2

           This is what makes a streaming body cancellable, and therefore what
           eventually retires `.close` on `:active-http`. A fake publisher lets
           us observe the Subscription.cancel() a real socket would only imply."
    (let [!s (new-state)
          ;; A long stream with no [DONE]: it would run until the chunks ran
          ;; out, so anything that stops early stopped because of the cancel.
          ;; 5ms per item so the stream CANNOT drain before we cancel — the
          ;; first version of this test finished all 2000 in under 200ms and
          ;; then "failed to cancel" a flow that had already completed.
          chunks (vec (for [i (range 2000)] (str "data: " i "\n\n")))
          !out (promise)
          cancel ((m/reduce conj [] (sse-pub/publisher->event-flow
                                     (fake-publisher chunks !s 5)))
                  #(deliver !out {:ok %}) #(deliver !out {:err %}))]
      (Thread/sleep 200)
      (is (false? (:cancelled? @!s)) "not cancelled yet")
      (cancel)
      (is (not= :TIMEOUT (deref !out 3000 :TIMEOUT)) "cancelling must settle the flow")
      (Thread/sleep 200)
      (is (true? (:cancelled? @!s))
          "Subscription.cancel() must have been called — this is the assertion
           the whole slice exists for")
      (let [emitted (:emitted @!s)]
        (Thread/sleep 300)
        (is (= emitted (:emitted @!s))
            "and the publisher must have stopped emitting")
        (is (< emitted (count chunks))
            "it stopped early rather than running the stream out")))))

(deftest demand-is-bounded-not-unlimited
  (testing "m/subscribe pulls — it does not let the publisher run free.

           This is the backpressure §1 listed as a reason to adopt missionary
           and which the current SSE reader has no way to express."
    (let [!s (new-state)
          chunks (vec (for [i (range 500)] (str "data: " i "\n\n")))
          !out (promise)
          cancel ((m/reduce conj [] (sse-pub/publisher->event-flow
                                     (fake-publisher chunks !s 5)))
                  #(deliver !out {:ok %}) #(deliver !out {:err %}))]
      (Thread/sleep 150)
      (cancel)
      (deref !out 3000 :TIMEOUT)
      (is (pos? (:requested @!s)) "demand was signalled")
      (is (<= (:emitted @!s) (:requested @!s))
          "the publisher never emitted more than was requested"))))

(deftest empty-stream-completes
  (let [!s (new-state)]
    (is (= [] (drain (sse-pub/publisher->event-flow (fake-publisher [] !s)))))))

(deftest utf8-across-buffers-in-one-item
  (testing "multi-byte characters decode correctly when whole"
    (let [!s (new-state)
          body "data: héllo — wörld\n\n"]
      (is (= [{:event nil :data "héllo — wörld"}]
             (drain (sse-pub/publisher->event-flow (fake-publisher [body] !s))))))))
