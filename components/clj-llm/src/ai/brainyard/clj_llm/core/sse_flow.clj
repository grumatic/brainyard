;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.clj-llm.core.sse-flow
  "SPIKE §18 slice 1: SSE framing as a stateful transducer over byte chunks,
   instead of a blocking `.readLine` loop over a `BufferedReader`.

   Why this exists. `sse/read-sse-events` reads from a `BufferedReader`, which
   means a thread blocked in `read` that no interrupt can reach — the sole
   reason `.close` on `:active-http` survives every version of the effect
   design (§14, §17). `java.net.http` will instead PUSH the body:
   `BodyHandlers/ofPublisher` yields a `Flow.Publisher<List<ByteBuffer>>`,
   `FlowAdapters/toPublisher` bridges it to reactive-streams, and
   `sse-publisher` turns that into a Flow with a subscriber it owns — NOT
   `m/subscribe`, which drops the last value against a publisher that completes
   eagerly, as `java.net.http` does (§18). Cancelling the Flow cancels the
   subscription and aborts the exchange — structural cancellation of a
   streaming body.

   This namespace is the part that has to be RIGHT before any of that plumbing
   matters: the framing. Nothing here touches missionary or the JDK, precisely
   so it can be tested against the existing reader for identical output.

   A transducer rather than an `m/ap` loop, for three reasons: it is the same
   shape the existing code has (a fold over lines), `m/eduction` accepts it
   directly, and it can be exercised with plain `sequence` — so the
   differential test needs no flow machinery to prove the semantics match.

   Semantics mirrored from `sse/read-sse-events`, including the awkward parts:
     - `event: t` sets the event type for the next emission
     - `data: p` accumulates; multiple `data:` lines join with \\n
     - a blank line is the event boundary
     - `[DONE]` ends the stream, but any data accumulated BEFORE it on the same
       event is still emitted first
     - EOF emits whatever is pending, unless that pending data is `[DONE]`

   `[DONE]` returns `reduced`, not merely a flag. Going quiet is enough when the
   upstream ends on its own — a `StringReader`, or a fake publisher with a
   finite chunk list. It is NOT enough over a real socket, where the reduction
   must actively stop or it keeps pulling from a body that has finished. Slices
   1 and 2 could not detect the difference; §18's slice 3 did, by hanging."
  (:require [clojure.string :as str]))

(defn- strip-cr
  "`.readLine` swallows the CR of a CRLF pair; splitting on \\n does not."
  ^String [^String s]
  (if (str/ends-with? s "\r") (subs s 0 (dec (count s))) s))

(defn sse-events-xf
  "Stateful transducer: string chunks in, `{:event … :data …}` maps out.

   Chunk boundaries are the whole difficulty — a chunk may end mid-line, mid
   `data:` prefix, or between the two newlines of an event boundary — so the
   partial tail is carried in `!buf` and only complete lines are processed."
  []
  (fn [rf]
    (let [!buf   (volatile! "")
          !event (volatile! nil)
          !parts (volatile! [])
          !done  (volatile! false)]
      (fn
        ([] (rf))
        ([acc]
         ;; Completion = EOF. `read-sse-events` emits pending data here, unless
         ;; that data is [DONE].
         ;;
         ;; FIRST, though: a body may end MID-LINE, with no trailing newline.
         ;; `.readLine` hands back that final partial line at EOF, so the
         ;; reader parses it; splitting on \n does not, and it would sit in
         ;; !buf forever. Dropping it loses the stream's last event whenever a
         ;; server truncates or omits the final blank line — the same class of
         ;; silent last-event loss as the m/subscribe bug (§18), found by
         ;; slice 3 running against a real server.
         (let [tail (strip-cr @!buf)]
           (vreset! !buf "")
           (when-not (or @!done (str/blank? tail))
             (cond
               (str/starts-with? tail "event:")
               (vreset! !event (str/trim (subs tail 6)))

               (str/starts-with? tail "data:")
               (let [payload (str/trim (subs tail 5))]
                 (if (= "[DONE]" payload)
                   (vreset! !done true)
                   (vswap! !parts conj payload)))

               ;; comment or unknown prefix — skipped, as upstream
               :else nil)))
         (let [acc (if (and (not @!done) (seq @!parts))
                     (let [d (str/join "\n" @!parts)]
                       (if (= "[DONE]" d) acc (unreduced (rf acc {:event @!event :data d}))))
                     acc)]
           (rf acc)))
        ([acc chunk]
         (if @!done
           (reduced acc)
           (do
             (vswap! !buf str chunk)
             (loop [acc acc]
               (let [^String b @!buf
                     i (.indexOf b "\n")]
                 (if (or (neg? i) @!done)
                   acc
                   (let [line (strip-cr (subs b 0 i))]
                     (vreset! !buf (subs b (inc i)))
                     (cond
                       ;; Blank line = event boundary.
                       (str/blank? line)
                       (if (seq @!parts)
                         (let [d (str/join "\n" @!parts)]
                           (vreset! !parts [])
                           (if (= "[DONE]" d)
                             (do (vreset! !done true) (reduced acc))
                             (let [acc' (rf acc {:event @!event :data d})]
                               (vreset! !event nil)
                               (if (reduced? acc') (do (vreset! !done true) @acc')
                                   (recur acc')))))
                         (recur acc))

                       (str/starts-with? line "event:")
                       (do (vreset! !event (str/trim (subs line 6))) (recur acc))

                       (str/starts-with? line "data:")
                       (let [payload (str/trim (subs line 5))]
                         (if (= "[DONE]" payload)
                           ;; [DONE] ends the stream, but data accumulated
                           ;; before it on this event is emitted first.
                           (do (vreset! !done true)
                               (reduced
                                (if (seq @!parts)
                                  (let [d (str/join "\n" @!parts)]
                                    (vreset! !parts [])
                                    (unreduced (rf acc {:event @!event :data d})))
                                  acc)))
                           (do (vswap! !parts conj payload) (recur acc))))

                       ;; Comment or unknown prefix — skipped, as upstream.
                       :else (recur acc)))))))))))))

(defn events-from-chunks
  "Eager convenience for tests and for callers that already have the whole
   body: chunk seq in, event seq out."
  [chunks]
  (sequence (sse-events-xf) chunks))
