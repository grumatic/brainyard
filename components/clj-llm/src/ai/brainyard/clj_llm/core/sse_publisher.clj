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
       m/subscribe                → missionary Flow

   No new dependency: `reactive-streams` arrives transitively with missionary.

   The property that matters is not that bytes arrive — it is that **cancelling
   the Flow cancels the subscription**. That is what lets the JDK abort the
   exchange, and it is the reason `.close` on `:active-http` can eventually go.
   The test asserts it against a fake publisher, so it is proven before any
   real socket is involved.

   Still wired to nothing: no provider code and no transport change. The next
   slice is `sendAsync` + `ofPublisher` behind a flag."
  (:require [ai.brainyard.clj-llm.core.sse-flow :as sse-flow]
            [missionary.core :as m])
  (:import [java.nio ByteBuffer]
           [java.nio.charset StandardCharsets]
           [java.util List]
           [org.reactivestreams FlowAdapters]))

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

(defn publisher->chunk-flow
  "A JDK `Flow.Publisher<List<ByteBuffer>>` as a missionary Flow of Strings."
  [flow-publisher]
  (->> (FlowAdapters/toPublisher flow-publisher)
       (m/subscribe)
       (m/eduction (map buffers->string))))

(defn publisher->event-flow
  "The whole chain: pushed body → SSE event maps, as a Flow.

   `{:event … :data …}` items, identical to what `sse/read-sse-events` yields
   for the same bytes (asserted in `sse-flow-test`), but pull-driven with
   backpressure and cancellable."
  [flow-publisher]
  (->> (publisher->chunk-flow flow-publisher)
       (m/eduction (sse-flow/sse-events-xf))))
