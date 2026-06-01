;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: Apache-2.0
;; Licensed under the Apache License, Version 2.0 (the "License"); you may
;; not use this file except in compliance with the License. See LICENSE at
;; the repository root and CONTRIBUTING.md for contribution terms.

(ns ai.brainyard.acp-stub-agent.core-test
  "End-to-end test: spawn the stub as a subprocess via the acp stdio
   transport and drive a full prompt round-trip. Validates the protocol
   from both ends without any external dependency.

   Tests are slow (subprocess JVM startup ≈ 5s); keep the count low."
  (:require [clojure.test :refer [deftest is testing]]
            [ai.brainyard.acp.interface :as acp]
            [clojure.java.io :as io]))

;; =============================================================================
;; Helpers
;; =============================================================================

(defn- repo-root
  "Walk up from cwd until we find workspace.edn. The base test runs
   from bases/acp-stub-agent so the root is two levels up — but we
   discover it dynamically to be robust to different invocation
   directories."
  []
  (loop [d (-> (System/getProperty "user.dir") io/file)]
    (cond
      (nil? d) (throw (ex-info "could not locate workspace.edn" {}))
      (.exists (io/file d "workspace.edn")) (.getCanonicalPath d)
      :else (recur (.getParentFile d)))))

(defn- spawn-stub!
  ([] (spawn-stub! ["--echo" "--chunk-delay-ms=5"]))
  ([extra-args]
   ;; Spawn from the project directory (projects/acp-stub-agent) — that's
   ;; where the deps.edn declares the brick deps (acp, mulog, acp-stub-agent
   ;; base) per the Polylith convention. The base's own deps.edn declares
   ;; third-party deps only and would not boot a working classpath.
   (let [project-dir (str (repo-root) "/projects/acp-stub-agent")
         t (acp/create-stdio-transport
            {:command (into ["clj" "-M" "-m" "ai.brainyard.acp-stub-agent.core"]
                            extra-args)
             :working-dir project-dir})]
     (acp/open! t))))

(defn- send-and-await-response
  "Send a request and read messages until a response with the same id
   arrives. Returns {:response <msg> :notifications <vec>} so callers
   can inspect inter-leaved session/update events."
  [t method params {:keys [next-id timeout-ms] :or {timeout-ms 30000}}]
  (let [id (next-id)
        deadline (+ (System/currentTimeMillis) timeout-ms)]
    (acp/write-message! t (acp/request id method params))
    (loop [notifs []]
      (let [remaining (- deadline (System/currentTimeMillis))]
        (when (neg? remaining)
          (throw (ex-info "timed out waiting for response"
                          {:method method :id id :notifications notifs})))
        (let [msg (acp/read-message! t remaining)]
          (cond
            (nil? msg)
            (throw (ex-info "transport closed before response arrived"
                            {:method method :id id :notifications notifs}))

            (and (acp/response? msg) (= id (:id msg)))
            {:response msg :notifications notifs}

            (acp/notification? msg)
            (recur (conj notifs msg))

            :else
            (recur notifs)))))))

(defn- drain-until-response
  "Like send-and-await-response, but the request was already sent and
   we're collecting whatever arrives until response with `id`."
  [t id {:keys [timeout-ms] :or {timeout-ms 30000}}]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop [notifs []]
      (let [remaining (- deadline (System/currentTimeMillis))]
        (when (neg? remaining)
          (throw (ex-info "timed out" {:id id :notifications notifs})))
        (let [msg (acp/read-message! t remaining)]
          (cond
            (nil? msg)
            (throw (ex-info "transport closed" {:id id :notifications notifs}))

            (and (acp/response? msg) (= id (:id msg)))
            {:response msg :notifications notifs}

            :else
            (recur (cond-> notifs (acp/notification? msg) (conj msg)))))))))

;; =============================================================================
;; End-to-end flow
;; =============================================================================

(deftest ^:integration end-to-end-prompt-test
  (testing "initialize → session/new → session/prompt streams chunks then end_turn"
    (let [t (spawn-stub!)
          next-id (acp/make-id-source)]
      (try
        (let [init (send-and-await-response t "initialize"
                                            {:protocolVersion acp/PROTOCOL_VERSION
                                             :clientCapabilities {}}
                                            {:next-id next-id})]
          (is (= acp/PROTOCOL_VERSION
                 (-> init :response :result :protocolVersion))))

        (let [new-resp (send-and-await-response t "session/new"
                                                {:cwd "/tmp"}
                                                {:next-id next-id})
              session-id (-> new-resp :response :result :sessionId)]
          (is (string? session-id))
          (is (.startsWith ^String session-id "stub-"))

          (let [prompt-resp (send-and-await-response
                             t "session/prompt"
                             {:sessionId session-id
                              :prompt [{:type "text" :text "hello world brainyard"}]}
                             {:next-id next-id})
                {:keys [response notifications]} prompt-resp
                chunks (->> notifications
                            (filter #(= "session/update" (:method %)))
                            (map :params)
                            (filter #(= "agent_message_chunk" (:sessionUpdate %))))
                concat-text (->> chunks
                                 (map #(get-in % [:content :text]))
                                 (apply str))]
            (is (= "end_turn" (-> response :result :stopReason)))
            (is (seq chunks) "at least one agent_message_chunk arrived")
            (is (= session-id (-> chunks first :sessionId)))
            (is (re-find #"hello" concat-text))
            (is (re-find #"world" concat-text))
            (is (re-find #"brainyard" concat-text))))
        (finally
          (acp/close! t))))))

(deftest ^:integration cancel-test
  (testing "session/cancel during a streaming prompt yields stopReason=cancelled"
    (let [t (spawn-stub! ["--echo" "--chunk-delay-ms=200"])
          next-id (acp/make-id-source)]
      (try
        (send-and-await-response t "initialize"
                                 {:protocolVersion acp/PROTOCOL_VERSION
                                  :clientCapabilities {}}
                                 {:next-id next-id})
        (let [{:keys [response]} (send-and-await-response t "session/new"
                                                          {:cwd "/tmp"}
                                                          {:next-id next-id})
              session-id (-> response :result :sessionId)
              ;; Manually send a long prompt without awaiting — we want
              ;; to interleave a cancel before it finishes.
              prompt-id (next-id)
              long-prompt (clojure.string/join " " (repeat 50 "tok"))]
          (acp/write-message! t (acp/request prompt-id "session/prompt"
                                             {:sessionId session-id
                                              :prompt [{:type "text" :text long-prompt}]}))
          ;; Give the stub a moment to start streaming
          (Thread/sleep 300)
          ;; Send cancel
          (let [cancel-id (next-id)]
            (acp/write-message! t (acp/request cancel-id "session/cancel"
                                               {:sessionId session-id}))
            ;; Drain until prompt response arrives
            (let [{:keys [response]} (drain-until-response t prompt-id {:timeout-ms 10000})]
              (is (= "cancelled" (-> response :result :stopReason))))))
        (finally
          (acp/close! t))))))
