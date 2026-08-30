;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.clj-llm.sse-flow-test
  "SPIKE §18 slice 1: does push-based SSE framing agree with the blocking
   reader?

   `equivalent` is the whole point — the same bytes go through
   `sse/read-sse-events` (a `BufferedReader`) and `sse-flow/events-from-chunks`
   (a transducer over chunks), and the event seqs must be identical. A
   reimplementation that merely looks right is worthless here; this is the code
   every streamed LLM token passes through.

   Every case is run at SEVERAL chunk splittings, including one byte at a time.
   Chunk boundaries are the entire difficulty of the push model: the blocking
   reader gets whole lines from the JDK, whereas `ofPublisher` hands over
   whatever arrived, which may end mid-line, mid-prefix, or between the two
   newlines of an event boundary."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [ai.brainyard.clj-llm.core.sse :as sse]
            [ai.brainyard.clj-llm.core.sse-flow :as sse-flow])
  (:import [java.io BufferedReader StringReader]))

(defn- via-reader
  "The existing implementation: the reference answer."
  [^String body]
  (doall (sse/read-sse-events (BufferedReader. (StringReader. body)))))

(defn- chunkings
  "Several ways the network might have delivered `body`."
  [^String body]
  {"whole"      [body]
   "by-1-byte"  (mapv str body)
   "by-7"       (mapv #(apply str %) (partition-all 7 body))
   "by-3"       (mapv #(apply str %) (partition-all 3 body))
   "split-at-1" (if (> (count body) 1)
                  [(subs body 0 1) (subs body 1)]
                  [body])})

(defn- equivalent
  "Assert the transducer matches the reader for `body`, at every chunking."
  [label ^String body]
  (let [expected (via-reader body)]
    (doseq [[how chunks] (chunkings body)]
      (is (= expected (sse-flow/events-from-chunks chunks))
          (str label " — chunked " how)))
    expected))

;; ============================================================================

(deftest single-event
  (is (= [{:event nil :data "hello"}]
         (equivalent "one data line" "data: hello\n\n"))))

(deftest multiple-events
  (is (= [{:event nil :data "a"} {:event nil :data "b"} {:event nil :data "c"}]
         (equivalent "three events" "data: a\n\ndata: b\n\ndata: c\n\n"))))

(deftest event-type-is-carried
  (is (= [{:event "message" :data "x"}]
         (equivalent "event: then data:" "event: message\ndata: x\n\n"))))

(deftest multi-line-data-joins-with-newline
  (is (= [{:event nil :data "line1\nline2"}]
         (equivalent "two data lines" "data: line1\ndata: line2\n\n"))))

(deftest done-terminates
  (testing "[DONE] ends the stream and is not itself emitted"
    (is (= [{:event nil :data "a"}]
           (equivalent "data then [DONE]" "data: a\n\ndata: [DONE]\n\n"))))

  (testing "events after [DONE] are not emitted"
    (is (= [{:event nil :data "a"}]
           (equivalent "trailing events after [DONE]"
                       "data: a\n\ndata: [DONE]\n\ndata: ignored\n\n")))))

(deftest done-emits-data-accumulated-before-it
  (testing "the awkward case: data: accumulated, then data: [DONE] on the SAME
            event, before any blank line"
    (is (= [{:event nil :data "partial"}]
           (equivalent "accumulate then [DONE]" "data: partial\ndata: [DONE]\n\n")))))

(deftest eof-flushes-pending-data
  (testing "no trailing blank line — EOF still emits"
    (is (= [{:event nil :data "no-trailing-blank"}]
           (equivalent "eof with pending" "data: no-trailing-blank\n")))))

(deftest eof-with-pending-done-emits-nothing
  (is (= [] (equivalent "eof on [DONE]" "data: [DONE]\n"))))

(deftest comments-and-unknown-prefixes-are-skipped
  (is (= [{:event nil :data "kept"}]
         (equivalent "comment lines"
                     ": this is a comment\nid: 42\ndata: kept\n\n"))))

(deftest blank-lines-without-data-are-not-events
  (is (= [{:event nil :data "only"}]
         (equivalent "leading blank lines" "\n\n\ndata: only\n\n"))))

(deftest crlf-line-endings
  (testing ".readLine swallows the CR of a CRLF pair; splitting on \\n does not"
    (is (= [{:event "m" :data "crlf"}]
           (equivalent "crlf" "event: m\r\ndata: crlf\r\n\r\n")))))

(deftest empty-body
  (is (= [] (equivalent "empty" ""))))

(deftest realistic-openai-stream
  (let [body (str "data: {\"choices\":[{\"delta\":{\"content\":\"He\"}}]}\n\n"
                  "data: {\"choices\":[{\"delta\":{\"content\":\"llo\"}}]}\n\n"
                  "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n"
                  "data: [DONE]\n\n")
        events (equivalent "openai-shaped stream" body)]
    (is (= 3 (count events)))
    (is (every? #(str/starts-with? (:data %) "{") events))))

(deftest realistic-anthropic-stream
  (let [body (str "event: message_start\ndata: {\"type\":\"message_start\"}\n\n"
                  "event: content_block_delta\ndata: {\"delta\":{\"text\":\"hi\"}}\n\n"
                  "event: message_stop\ndata: {\"type\":\"message_stop\"}\n\n")
        events (equivalent "anthropic-shaped stream" body)]
    (is (= ["message_start" "content_block_delta" "message_stop"]
           (mapv :event events)))))

(deftest event-type-does-not-leak-to-the-next-event
  (testing "an event: applies to its own emission only"
    (is (= [{:event "first" :data "a"} {:event nil :data "b"}]
           (equivalent "typed then untyped"
                       "event: first\ndata: a\n\ndata: b\n\n")))))

(deftest laziness-is-preserved-for-early-termination
  (testing "taking 1 from a long stream does not force the rest — the reader is
            lazy and the transducer must not lose that under m/eduction"
    (let [body (apply str (for [i (range 500)] (str "data: " i "\n\n")))]
      (is (= [{:event nil :data "0"}]
             (take 1 (sse-flow/events-from-chunks [body])))))))

;; ============================================================================
;; The case slices 1 and 2 structurally could not detect
;; ============================================================================

(deftest terminates-on-done-even-when-upstream-never-ends
  (testing "[DONE] must END the reduction, not merely stop emitting

           A StringReader and a fake publisher both END on their own, so a
           transducer that goes quiet without terminating looks correct. Over a
           real socket the reduction keeps pulling from a finished body and the
           flow never completes — which is exactly how §18 slice 3 hung. This
           drives an INFINITE chunk seq: if the xf does not return `reduced`,
           `into` never returns."
    (let [infinite (cons "data: a\n\ndata: [DONE]\n\n" (repeat "data: more\n\n"))
          f (future (into [] (sse-flow/sse-events-xf) infinite))
          r (deref f 5000 ::hung)]
      (when (= ::hung r) (future-cancel f))
      (is (= [{:event nil :data "a"}] r)
          "must terminate at [DONE] rather than consuming forever"))))

(deftest terminates-on-done-mid-chunk-when-upstream-never-ends
  (testing "same, with [DONE] arriving inline after accumulated data"
    (let [infinite (cons "data: partial\ndata: [DONE]\n\n" (repeat "data: more\n\n"))
          f (future (into [] (sse-flow/sse-events-xf) infinite))
          r (deref f 5000 ::hung)]
      (when (= ::hung r) (future-cancel f))
      (is (= [{:event nil :data "partial"}] r)))))
