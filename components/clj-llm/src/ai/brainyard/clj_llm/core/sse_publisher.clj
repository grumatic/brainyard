;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.clj-llm.core.sse-publisher
  "SPIKE §18 slice 2: the transport bridge — a pushed body becomes a Flow.

   Slice 1 (`sse-flow`) proved the FRAMING matches the blocking reader. This is
   the other half: getting bytes from `java.net.http`'s push handler into
   missionary, which is what makes a streaming body cancellable at all.

       BodyHandlers/ofPublisher   → java.util.concurrent.Flow$Publisher
       FlowAdapters/toPublisher   → org.reactivestreams.Publisher
       a Subscriber we OWN        → missionary Flow

   The last step is deliberately not `m/subscribe`: it discards a value that
   has been delivered but not yet transferred when the flow terminates, which
   against an eagerly-completing publisher — `java.net.http` — silently drops
   the final SSE event of every stream. See `subscribe->queue!` and §18.

   No new dependency: `reactive-streams` arrives transitively with missionary.

   The property that matters is not that bytes arrive — it is that **cancelling
   the Flow cancels the subscription**. That is what lets the JDK abort the
   exchange, and it is the reason `.close` on `:active-http` can eventually go.
   The test asserts it against a fake publisher, so it is proven before any
   real socket is involved.

   Still wired to nothing: no provider code and no transport change. The next
   slice is `sendAsync` + `ofPublisher` behind a flag."
  (:require [ai.brainyard.clj-llm.core.sse :as sse]
            [ai.brainyard.clj-llm.core.sse-flow :as sse-flow]
            [ai.brainyard.effect.interface :as fx]
            [missionary.core :as m])
  (:import [java.nio ByteBuffer]
           [java.nio.charset StandardCharsets]
           [java.util List]
           [java.util.concurrent LinkedBlockingQueue]
           [org.reactivestreams FlowAdapters Subscriber Subscription]))

(defn buffers->string
  "Decode one pushed item — `List<ByteBuffer>` — into a String.

   UTF-8 decoded per item rather than per buffer. That is safe here only
   because the SSE framing downstream is byte-boundary tolerant: a multi-byte
   character split across two PUSHED ITEMS would still be mangled. Real
   provider streams deliver whole SSE lines, and slice 1 tolerates arbitrary
   line splitting, but this is the sharp edge to revisit before shipping —
   a `CharsetDecoder` carried across items is the fix."
  ^String [^List buffers]
  (let [sb (StringBuilder.)]
    (doseq [^ByteBuffer b buffers]
      (.append sb (.decode StandardCharsets/UTF_8 (.duplicate b))))
    (.toString sb)))

(defn- subscribe->queue!
  "Subscribe to `flow-publisher`, pushing every signal into `q` AS A VALUE.

   THE POINT — and the whole reason `m/subscribe` is not used here (§18):
   `onComplete` is enqueued BEHIND the last `onNext`, so completion cannot
   overtake a value that has been delivered but not yet consumed. Queue
   ordering, not timing, decides what the consumer sees.

   `m/subscribe` gets this wrong against a publisher that completes EAGERLY —
   one that says `onComplete` as soon as the body is exhausted, without waiting
   for further demand, which is exactly what `java.net.http` does and what no
   hand-written fake did. Measured: the final value is silently dropped, and
   under a stricter consumer (`m/buffer`) the flow violates missionary's
   protocol outright — an internal NPE, a hang, 128 of 200 items lost.

   Demand is one item at a time and is re-issued by the CONSUMER after a take,
   so at most one item is ever in flight. That is the backpressure the blocking
   SSE reader has no way to express."
  [flow-publisher ^LinkedBlockingQueue q]
  (let [!sub (atom nil)]
    (.subscribe (FlowAdapters/toPublisher flow-publisher)
                (reify Subscriber
                  (onSubscribe [_ s] (reset! !sub s) (.request ^Subscription s 1))
                  (onNext     [_ item] (.put q [:item item]))
                  (onError    [_ t]    (.put q [:error t]))
                  (onComplete [_]      (.put q [:done]))))
    !sub))

(defn- take-message!
  "Block for the next signal, then ask for one more. Runs on `m/blk`.

   Interruptible, unlike the `.readLine` this replaces — a reader blocked in
   `read` that no interrupt can reach is precisely what `.close` on
   `:active-http` exists to work around (§14, §17).

   CANCELLATION REACHES THE JDK HERE, and this is the only place it can.
   `m/via m/blk` cancels by interrupting the thread, so a cancelled flow
   surfaces as an `InterruptedException` out of `.take` — at which point the
   subscription is cancelled and the exception rethrown. It covers external
   cancellation and downstream `reduced` alike, since both cancel the pending
   task.

   A `try/finally` inside the `m/ap` looks like the obvious place for this and
   is WRONG: the fork means the finally runs once per branch, so it cancelled
   the subscription after the very first item. Every flow in this namespace
   failed and `:cancelled?` was true before the test even looked."
  [^LinkedBlockingQueue q !sub]
  (try
    (let [msg (.take q)]
      (when (= :item (nth msg 0))
        (some-> ^Subscription @!sub (.request 1)))
      msg)
    (catch InterruptedException e
      (some-> ^Subscription @!sub (.cancel))
      (throw e))))

(defn publisher->chunk-flow
  "A JDK `Flow.Publisher<List<ByteBuffer>>` as a missionary Flow of Strings.

   Owns its subscriber rather than delegating to `m/subscribe`; see
   `subscribe->queue!` for why that is a correctness requirement, not an
   optimisation.

   THE LOOP TERMINATES ITSELF on `:done`. The obvious alternative — fork
   forever over `(m/seed (repeat nil))` and let a downstream transducer return
   `reduced` — parks one more branch on `.take` AFTER the terminal message and
   leaves it there. With a single `m/eduction` the reduction still settles and
   the leak is invisible; layer a second one (`sse-events-xf`) and termination
   never propagates, so the flow hangs while holding a blocked `m/blk` thread.
   Measured both ways. Owning termination here also makes cancellation prompt:
   `(m/eduction (take 3) …)` over a 50-item stream parks 4 times, not 51."
  [flow-publisher]
  (->> (m/ap
        (let [q    (LinkedBlockingQueue.)
              !sub (subscribe->queue! flow-publisher q)]
          (loop []
            (let [msg (m/? (fx/task-of #(take-message! q !sub)))]
              (case (nth msg 0)
                :item  (m/amb (nth msg 1) (recur))
                :done  (m/amb)
                :error (throw (nth msg 1)))))))
       (m/eduction (map buffers->string))))

(defn publisher->event-flow
  "The whole chain: pushed body → SSE event maps, as a Flow.

   `{:event … :data …}` items, identical to what `sse/read-sse-events` yields
   for the same bytes (asserted in `sse-flow-test`), but pull-driven with
   backpressure and cancellable."
  [flow-publisher]
  (->> (publisher->chunk-flow flow-publisher)
       (m/eduction (sse-flow/sse-events-xf))))

;; ============================================================================
;; Slice 3 — a real exchange, still wired to nothing
;; ============================================================================

(defn request-event-flow
  "Send `req` on `client` with the PUSH body handler, and yield its SSE events
   as a Flow. Cancelling the Flow aborts the HTTP exchange.

   The whole point in four lines: `sendAsync` returns a `CompletableFuture`
   (which implements `Future`, so `fx/from-future` adopts it), `.body` is a
   `Flow.Publisher`, and slice 2 turns that into events. No `BufferedReader`,
   so no thread blocked in `read`, so no `.close` needed to escape one.

   Deliberately NOT wired into any provider. `llm.clj`'s streaming branches
   still use `sse/read-sse-events`; this is the third additive slice, and the
   contract change (`on-chunk`) is the next one."
  [^java.net.http.HttpClient client ^java.net.http.HttpRequest req]
  (m/ap
   (let [resp (m/? (fx/from-future
                    (.sendAsync client req
                                (java.net.http.HttpResponse$BodyHandlers/ofPublisher))))]
     (m/?> (publisher->event-flow (.body ^java.net.http.HttpResponse resp))))))

;; ============================================================================
;; Slice 4 — the provider folds, driven by the Flow instead of the reader
;; ============================================================================

(defn openai-response-task
  "A pushed OpenAI body as a Task of the reconstructed non-streaming response.

   The SAME `openai-step` the blocking reader uses (`sse/process-openai-stream`
   is `reduce` over `read-sse-events`; this is `m/reduce` over the event Flow).
   No second copy of the delta logic — that was the entire point of extracting
   the fold first, and it is what makes the two paths comparable rather than
   merely similar.

   `m/reduce` honours a `reduced` step identically to `clojure.core/reduce`,
   measured across every flow shape, so the terminal behaves the same on both
   paths. `on-chunk` fires exactly as it does today, per the public contract."
  [flow-publisher on-chunk]
  (m/sp
   (-> (m/? (m/reduce (sse/openai-step on-chunk) (sse/openai-init)
                      (publisher->event-flow flow-publisher)))
       (sse/openai-result on-chunk))))

(defn anthropic-response-task
  "A pushed Anthropic body as a Task of the reconstructed response.

   As `openai-response-task`, and the terminal matters more here:
   `message_stop` arrives MID-STREAM, so `anthropic-step` returns `reduced` and
   the reduction must actually stop — over a Flow, a terminal that only goes
   quiet never completes."
  [flow-publisher on-chunk]
  (m/sp
   (-> (m/? (m/reduce (sse/anthropic-step on-chunk) (sse/anthropic-init)
                      (publisher->event-flow flow-publisher)))
       (sse/anthropic-result on-chunk))))
