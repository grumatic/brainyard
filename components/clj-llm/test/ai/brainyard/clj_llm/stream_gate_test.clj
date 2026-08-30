;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.clj-llm.stream-gate-test
  "§18 step 3c: the two streaming paths, driven through the PRODUCTION
   functions and compared.

   Everything before this compared folds, flows and bridges in isolation. This
   drives `openai-chat-completion-stream` / `anthropic-chat-completion-stream`
   themselves — the real HTTP call, real headers, real error handling — once
   with `BY_STREAM_FLOW` off and once on, against the same local server, and
   asserts the two are indistinguishable.

   A local server rather than a provider: it is deterministic, it costs
   nothing, and it can be made to fail in ways a provider will not do on
   demand (401 mid-suite). What it CANNOT do is prove a real provider's framing
   matches, which is why a live differential is still owed before the default
   flips."
  (:require [clojure.test :refer [deftest is testing]]
            [ai.brainyard.clj-llm.core.llm :as llm])
  (:import [com.sun.net.httpserver HttpServer HttpHandler]
           [java.net InetSocketAddress]))

(defn- serve
  "One server answering any path with `status` and `body`."
  ^HttpServer [status ^String body]
  (doto (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)
    (.createContext "/" (reify HttpHandler
                          (handle [_ exch]
                            (let [b (.getBytes body "UTF-8")]
                              (.sendResponseHeaders exch status (alength b))
                              (doto (.getResponseBody exch) (.write b) (.close))))))
    (.setExecutor nil)
    (.start)))

(defn- base-url [^HttpServer s]
  (str "http://127.0.0.1:" (.getPort (.getAddress s))))

(defn- run-both
  "Call `f` with the gate off, then on; return [reader-result flow-result],
   each as {:result … :chunks […]}."
  [f]
  (mapv (fn [flow?]
          (with-redefs [llm/stream-via-flow? (constantly flow?)]
            (let [!chunks (atom [])]
              {:result (f #(swap! !chunks conj %)) :chunks @!chunks})))
        [false true]))

;; ============================================================================

(deftest openai-both-paths-agree
  (testing "same server, same bytes — reader path and flow path indistinguishable"
    (let [body (str "data: {\"choices\":[{\"delta\":{\"role\":\"assistant\",\"content\":\"He\"}}]}\n\n"
                    "data: {\"choices\":[{\"delta\":{\"content\":\"llo\"}}],"
                    "\"usage\":{\"prompt_tokens\":5,\"completion_tokens\":2}}\n\n"
                    "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"length\"}]}\n\n"
                    "data: [DONE]\n\n")
          srv (serve 200 body)]
      (try
        (let [lm {:base-url (base-url srv) :model "test-model" :provider :openai}
              [rdr flow] (run-both
                          (fn [on-chunk]
                            (#'llm/openai-chat-completion-stream
                             lm [{:role "user" :content "hi"}] {} on-chunk)))]
          (is (= (:result rdr) (:result flow)) "reconstructed response must match")
          (is (= (:chunks rdr) (:chunks flow)) "on-chunk sequence must match")
          (is (= "Hello" (get-in (:result flow) [:choices 0 :message :content])))
          (is (= "length" (get-in (:result flow) [:choices 0 :finish_reason]))
              "non-default finish_reason survives the flow path"))
        (finally (.stop srv 0))))))

(deftest anthropic-both-paths-agree
  (testing "including the mid-stream terminal"
    (let [body (str "event: message_start\ndata: {\"message\":{\"model\":\"m\",\"usage\":{\"input_tokens\":4}}}\n\n"
                    "event: content_block_delta\ndata: {\"delta\":{\"text\":\"hi\"}}\n\n"
                    "event: message_delta\ndata: {\"delta\":{\"stop_reason\":\"max_tokens\"},"
                    "\"usage\":{\"output_tokens\":2}}\n\n"
                    "event: message_stop\ndata: {}\n\n"
                    "event: content_block_delta\ndata: {\"delta\":{\"text\":\"IGNORED\"}}\n\n")
          srv (serve 200 body)]
      (try
        (let [lm {:base-url (base-url srv) :model "claude-test" :provider :anthropic}
              [rdr flow] (run-both
                          (fn [on-chunk]
                            (#'llm/anthropic-chat-completion-stream
                             lm [{:role "user" :content "hi"}] {} on-chunk)))]
          (is (= (:result rdr) (:result flow)))
          (is (= (:chunks rdr) (:chunks flow)))
          (is (= "hi" (get-in (:result flow) [:content 0 :text]))
              "text after message_stop reaches neither path"))
        (finally (.stop srv 0))))))

(deftest error-status-behaves-identically-on-both-paths
  (testing "THE case :as :publisher was designed around

           A 401 must throw the same ex-info with a READABLE body on both
           paths. On the flow path the body would be an unread publisher if
           the subscriber were not chosen by status — and an unreadable error
           body is what once made insufficient_quota look like a throttle."
    (let [srv (serve 401 "{\"error\":{\"message\":\"bad key\",\"code\":\"invalid_api_key\"}}")]
      (try
        (let [lm {:base-url (base-url srv) :model "test-model" :provider :openai}
              catch-it (fn [flow?]
                         (with-redefs [llm/stream-via-flow? (constantly flow?)]
                           (try (#'llm/openai-chat-completion-stream
                                 lm [{:role "user" :content "hi"}] {} nil)
                                (catch clojure.lang.ExceptionInfo e
                                  (select-keys (ex-data e) [:status :body])))))
              rdr  (catch-it false)
              flow (catch-it true)]
          (is (= 401 (:status rdr)) "reader path throws with the status")
          (is (= rdr flow) "flow path must throw identically, body included")
          (is (string? (:body flow)) "and the error body must be readable")
          (is (re-find #"invalid_api_key" (:body flow))))
        (finally (.stop srv 0))))))

(deftest gate-defaults-off
  (testing "BY_STREAM_FLOW unset means the reader path — the shipping default"
    (is (false? (llm/stream-via-flow?))
        "nothing in this suite may flip the default for the whole process")))

(defn- truncating-server
  "A RAW socket server that sends a valid response head promising 4096 bytes,
   writes ~18, and hangs up.

   `HttpServer` cannot do this — asked to under-deliver it fails before
   sending headers at all, so the client sees `HTTP/1.1 header parser received
   no bytes` and the transport throws on BOTH paths. That made the first
   version of the test below pass trivially, which a negative control caught."
  ^java.net.ServerSocket []
  (let [ss (java.net.ServerSocket. 0 0 (java.net.InetAddress/getByName "127.0.0.1"))]
    (doto (Thread.
           (fn []
             (try
               (with-open [sock (.accept ss)]
                 (.read (.getInputStream sock) (byte-array 8192))
                 (doto (.getOutputStream sock)
                   (.write (.getBytes (str "HTTP/1.1 200 OK\r\n"
                                           "Content-Type: text/event-stream\r\n"
                                           "Content-Length: 4096\r\n\r\n"
                                           "data: {\"choices\":[")
                                      "UTF-8"))
                   (.flush))
                 (Thread/sleep 50))
               (catch Throwable _ nil))))
      (.setDaemon true)
      (.start))
    ss))

(deftest task-level-failure-is-thrown-not-swallowed
  (testing "a stream that DIES MID-BODY must throw on the flow path

           Found by a negative control: replacing the `:err` branch of
           run-stream-task!! with nil passed the entire suite. The 401 test
           above cannot reach it — that throws from http/post before the task
           ever runs — so nothing exercised a task that FAILS rather than an
           HTTP call that fails.

           The response head arrives intact and the body is then truncated, so
           the JDK publisher signals onError AFTER the flow is live, which the
           bridge turns into a failed Task. If run-stream-task!! swallowed that,
           a truncated stream would return nil and the caller would see a
           successful turn with no answer."
    (with-open [ss (truncating-server)]
      (let [lm {:base-url (str "http://127.0.0.1:" (.getLocalPort ss))
                :model "test-model" :provider :openai}
            outcome (with-redefs [llm/stream-via-flow? (constantly true)]
                      (try {:returned (#'llm/openai-chat-completion-stream
                                       lm [{:role "user" :content "hi"}] {} nil)}
                           (catch Exception e {:threw (.getName (class e))})))]
        (is (contains? outcome :threw)
            (str "a dead stream must throw, not return " (pr-str (:returned outcome))))
        (is (not= {:returned nil} outcome)
            "and specifically must not return nil, which reads as an empty answer")))))

(deftest interrupted-llm-call-throws-interrupted-not-npe
  (testing "a cancelled turn must not surface as a NullPointerException

           run!! reports an interrupt as {:interrupted true} rather than
           propagating InterruptedException, so retry-with-backoff's
           (throw (:err r)) branch would throw NIL -- on the path EVERY LLM
           call takes. Caught by auditing run!! callers, not by the suite."
    (let [!outcome (atom :none)
          t (doto (Thread.
                   (fn []
                     (reset! !outcome
                             (try (#'llm/retry-with-backoff
                                   (fn [] (Thread/sleep 30000) :never) {})
                                  (catch Throwable e (.getSimpleName (class e)))))))
              (.setDaemon true) (.start))]
      (Thread/sleep 400)
      (.interrupt t)
      (.join t 5000)
      (is (= "InterruptedException" @!outcome)
          "an interrupted call reports interruption, not a NullPointerException"))))
