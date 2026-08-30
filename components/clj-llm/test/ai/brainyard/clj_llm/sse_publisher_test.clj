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

(defn- eager-publisher
  "A publisher that completes EAGERLY: it calls `onComplete` as soon as the
   items run out, in the SAME `request` call that delivered the last one,
   without waiting for further demand.

   This is what `java.net.http` does, and reproducing it is the entire reason
   this fake exists. `fake-publisher` above completes LAZILY — it only
   completes when demand arrives for the item past the end, which guarantees
   the consumer transferred the previous value first. That difference is
   invisible in every assertion slices 1 and 2 make, and it is where a real
   bug lived: `m/subscribe` silently dropped the last value, so every SSE
   stream over a real socket would have lost its final event (§18).

   Completion is deliberately NOT demand-gated. Reactive streams gates
   `onNext` on demand; a publisher may complete whenever it likes, so this
   fake is legal and the bridge must cope with it."
  [chunks !state]
  (reify Flow$Publisher
    (subscribe [_ sub]
      (let [!idx (atom 0) done? (atom false)]
        (.onSubscribe
         sub
         (reify Flow$Subscription
           (request [_ n]
             (swap! !state update :requested + n)
             (dotimes [_ n]
               (when-not @done?
                 (let [i @!idx]
                   (if (< i (count chunks))
                     (do (swap! !idx inc)
                         (swap! !state update :emitted inc)
                         (.onNext sub (->item (nth chunks i)))
                         (when (= @!idx (count chunks))
                           (reset! done? true)
                           (.onComplete sub)))
                     (do (reset! done? true) (.onComplete sub)))))))
           (cancel [_] (swap! !state assoc :cancelled? true) (reset! done? true))))))))

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

;; ============================================================================
;; The eager-completion case — what slices 1 and 2 were structurally blind to
;; ============================================================================

(deftest eager-completion-does-not-lose-the-last-event
  (testing "onComplete arriving in the same request() as the last onNext must
            not discard that value

           This is the §18 root cause. m/subscribe loses exactly one value —
           always the last — when completion is not demand-gated, which is how
           the real JDK body behaves. Over a socket that means every SSE stream
           silently drops its final event; harmless when it is [DONE], token
           loss when it is a content delta."
    (doseq [n [1 2 3 50]]
      (let [!s (new-state)
            chunks (vec (for [i (range n)] (str "data: " i "\n\n")))
            events (drain (sse-pub/publisher->event-flow (eager-publisher chunks !s)))]
        (is (= n (count events)) (str n " events in, " n " out"))
        (is (= (mapv str (range n)) (mapv :data events))
            "and in order, with none missing")))))

(deftest eager-completion-agrees-with-the-blocking-reader
  (testing "same differential assertion as the lazy case, against the eager
            publisher and at several chunkings"
    (let [body (str "event: message_start\ndata: {\"a\":1}\n\n"
                    "data: {\"b\":2}\n\n")
          expected (doall (sse/read-sse-events (BufferedReader. (StringReader. body))))]
      (doseq [[label chunks] {"whole"     [body]
                              "by-9"      (mapv #(apply str %) (partition-all 9 body))
                              "by-1-byte" (mapv str body)}]
        (let [!s (new-state)]
          (is (= expected
                 (drain (sse-pub/publisher->event-flow (eager-publisher chunks !s))))
              (str "eager, chunked " label)))))))

(deftest eager-empty-stream-completes
  (testing "no items at all, completing immediately"
    (let [!s (new-state)]
      (is (= [] (drain (sse-pub/publisher->event-flow (eager-publisher [] !s))))))))

(deftest eager-stream-ending-in-done
  (testing "[DONE] as the final event is the case that HID this bug — the lost
            value was one nobody would miss"
    (let [!s (new-state)
          chunks ["data: a\n\n" "data: b\n\n" "data: [DONE]\n\n"]]
      (is (= [{:event nil :data "a"} {:event nil :data "b"}]
             (drain (sse-pub/publisher->event-flow (eager-publisher chunks !s))))))))

(deftest demand-is-bounded-against-the-eager-publisher
  (testing "the bridge must not request unboundedly just because the publisher
            answers synchronously"
    (let [!s (new-state)
          chunks (vec (for [i (range 20)] (str "data: " i "\n\n")))]
      (drain (sse-pub/publisher->event-flow (eager-publisher chunks !s)))
      (is (<= (:emitted @!s) (:requested @!s))
          "never emitted more than was requested"))))

;; ============================================================================
;; Slice 4 — the Flow path must agree with the reader path, byte for byte
;; ============================================================================

(defn- task-value [task]
  (let [p (promise)]
    (task #(deliver p {:ok %}) #(deliver p {:err %}))
    (let [r (deref p 10000 {:timeout true})]
      (if (contains? r :ok) (:ok r) (throw (ex-info "task failed" r))))))

(deftest openai-flow-path-agrees-with-the-reader
  (testing "same bytes, same response, same on-chunk sequence — the whole
            justification for extracting the fold before wiring the Flow"
    (let [body (str "data: {\"choices\":[{\"delta\":{\"role\":\"assistant\",\"content\":\"He\"}}]}\n\n"
                    "data: {\"choices\":[{\"delta\":{\"content\":\"llo\"}}],"
                    "\"usage\":{\"prompt_tokens\":3,\"completion_tokens\":2}}\n\n"
                    "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"length\"}]}\n\n"
                    "data: [DONE]\n\n")
          !reader-chunks (atom [])
          expected (sse/process-openai-stream
                    (BufferedReader. (StringReader. body))
                    #(swap! !reader-chunks conj %))]
      (doseq [[label chunks] {"whole"     [body]
                              "by-11"     (mapv #(apply str %) (partition-all 11 body))
                              "by-1-byte" (mapv str body)}]
        (let [!s (new-state)
              !flow-chunks (atom [])
              actual (task-value (sse-pub/openai-response-task
                                  (eager-publisher chunks !s)
                                  #(swap! !flow-chunks conj %)))]
          (is (= expected actual) (str "response, chunked " label))
          (is (= @!reader-chunks @!flow-chunks) (str "on-chunk seq, chunked " label)))))))

(deftest anthropic-flow-path-agrees-with-the-reader
  (testing "including the mid-stream terminal: events after message_stop must
            be ignored on BOTH paths"
    (let [body (str "event: message_start\ndata: {\"message\":{\"model\":\"m\",\"usage\":{\"input_tokens\":4}}}\n\n"
                    "event: content_block_delta\ndata: {\"delta\":{\"text\":\"hi\"}}\n\n"
                    "event: message_delta\ndata: {\"delta\":{\"stop_reason\":\"max_tokens\"},"
                    "\"usage\":{\"output_tokens\":2}}\n\n"
                    "event: message_stop\ndata: {}\n\n"
                    "event: content_block_delta\ndata: {\"delta\":{\"text\":\"IGNORED\"}}\n\n")
          !reader-chunks (atom [])
          expected (sse/process-anthropic-stream
                    (BufferedReader. (StringReader. body))
                    #(swap! !reader-chunks conj %))]
      (is (= "hi" (get-in expected [:content 0 :text])) "sanity: reader ignored it too")
      (doseq [[label chunks] {"whole"     [body]
                              "by-13"     (mapv #(apply str %) (partition-all 13 body))
                              "by-1-byte" (mapv str body)}]
        (let [!s (new-state)
              !flow-chunks (atom [])
              actual (task-value (sse-pub/anthropic-response-task
                                  (eager-publisher chunks !s)
                                  #(swap! !flow-chunks conj %)))]
          (is (= expected actual) (str "response, chunked " label))
          (is (= @!reader-chunks @!flow-chunks) (str "on-chunk seq, chunked " label)))))))
