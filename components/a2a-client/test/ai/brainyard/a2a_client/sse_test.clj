;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.a2a-client.sse-test
  "Unit tests for SSE frame parsing. Pure — no socket.

   SSE framing is fiddly in exactly the ways that produce silent data
   corruption: the one-space rule after the colon, comment keep-alives,
   and multi-line `data:` joining. Testing it directly beats only ever
   exercising it through a live stream."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [ai.brainyard.a2a-client.core.transport :as transport]))

(defn- feed
  "Feed `lines` through the parser, returning the vector of completed
   frames (as their joined data payloads)."
  [lines]
  (loop [acc {} ls lines out []]
    (if (empty? ls)
      out
      (let [[acc' dispatch?] (transport/parse-sse-line acc (first ls))]
        (if dispatch?
          (recur {} (rest ls) (conj out (transport/frame-payload acc')))
          (recur acc' (rest ls) out))))))

(deftest single-frame-test
  (testing "a data line followed by a blank line dispatches one frame"
    (is (= ["{\"a\":1}"] (feed ["data: {\"a\":1}" ""])))))

(deftest one-space-rule-test
  (testing "exactly ONE leading space after the colon is stripped"
    (is (= ["x"] (feed ["data: x" ""]))))

  (testing "a second space is CONTENT, not framing"
    ;; Stripping greedily here would silently corrupt any payload whose
    ;; first character is a space.
    (is (= [" x"] (feed ["data:  x" ""]))))

  (testing "no space at all is fine"
    (is (= ["x"] (feed ["data:x" ""])))))

(deftest comment-lines-test
  (testing "comment lines are IGNORED, not treated as data"
    ;; Servers send these as keep-alives. Treating one as data would
    ;; inject garbage into the decoded stream.
    (is (= ["{\"a\":1}"]
           (feed [":" ": keep-alive" "data: {\"a\":1}" ""]))))

  (testing "a comment alone never dispatches a frame"
    (is (= [] (feed [": ping" ""])))))

(deftest multi-line-data-test
  (testing "multiple data lines join with a newline, per the SSE grammar"
    (is (= ["line1\nline2"] (feed ["data: line1" "data: line2" ""]))))

  (testing "an empty data line contributes an empty segment"
    (is (= ["a\n\nb"] (feed ["data: a" "data:" "data: b" ""])))))

(deftest multiple-frames-test
  (testing "consecutive frames are separated by blank lines"
    (is (= ["one" "two" "three"]
           (feed ["data: one" ""
                  "data: two" ""
                  "data: three" ""]))))

  (testing "state does not leak between frames"
    (is (= ["a" "b"] (feed ["data: a" "" "data: b" ""])))))

(deftest other-fields-test
  (testing "event / id / retry are captured without dispatching"
    (let [[acc dispatch?] (transport/parse-sse-line {} "event: status-update")]
      (is (= "status-update" (:event acc)))
      (is (not dispatch?)))
    (let [[acc _] (transport/parse-sse-line {} "id: 42")]
      (is (= "42" (:id acc))))
    (let [[acc _] (transport/parse-sse-line {} "retry: 3000")]
      (is (= "3000" (:retry acc)))))

  (testing "an unknown field is ignored rather than breaking the frame"
    (let [[acc dispatch?] (transport/parse-sse-line {} "future-field: x")]
      (is (not dispatch?))
      (is (not (contains? acc :data)))))

  (testing "a field line with no colon is a field with an empty value"
    (let [[acc dispatch?] (transport/parse-sse-line {} "data")]
      (is (not dispatch?))
      (is (= [""] (:data acc))))))

(deftest blank-line-without-data-test
  (testing "a blank line with NO accumulated data does not dispatch"
    ;; Otherwise leading blank lines and keep-alive gaps would each
    ;; produce a spurious empty frame.
    (let [[_ dispatch?] (transport/parse-sse-line {} "")]
      (is (not dispatch?)))
    (let [[_ dispatch?] (transport/parse-sse-line {:event "x"} "")]
      (is (not dispatch?) "an event field alone is not a dispatchable frame")))

  (testing "a blank line WITH accumulated data dispatches"
    (let [[_ dispatch?] (transport/parse-sse-line {:data ["x"]} "")]
      (is dispatch?))))

(deftest nil-line-test
  (testing "a nil line (EOF) is inert"
    (let [[acc dispatch?] (transport/parse-sse-line {:data ["x"]} nil)]
      (is (not dispatch?))
      (is (= {:data ["x"]} acc)))))

(deftest realistic-a2a-stream-test
  (testing "a realistic A2A SSE exchange frames correctly"
    (let [lines [": ok"
                 "event: status-update"
                 "data: {\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"taskId\":\"t1\",\"kind\":\"status-update\",\"status\":{\"state\":\"working\"}}}"
                 ""
                 ": keep-alive"
                 "event: artifact-update"
                 "data: {\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"taskId\":\"t1\",\"kind\":\"artifact-update\",\"artifact\":{\"artifactId\":\"a1\",\"parts\":[]}}}"
                 ""
                 "data: {\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"taskId\":\"t1\",\"kind\":\"status-update\",\"status\":{\"state\":\"completed\"},\"final\":true}}"
                 ""]
          frames (feed lines)]
      (is (= 3 (count frames)))
      (is (every? #(str/starts-with? % "{\"jsonrpc\"") frames)))))
