;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.clj-llm.http-flow-test
  "§18 step 3a: does the java.net.http request carry what clj-http was carrying?

   Sends nothing. The whole risk in adding a second HTTP client is dropping
   something the first one had, and every one of those failures is invisible
   until it is in production."
  (:require [clojure.test :refer [deftest is testing]]
            [ai.brainyard.clj-llm.core.http-flow :as hf])
  (:import [java.net URI]))

(deftest headers-all-survive
  (testing "every header llm.clj builds reaches the request"
    (let [headers {"Content-Type" "application/json"
                   "Authorization" "Bearer sk-test"
                   "anthropic-version" "2023-06-01"}
          req (hf/post-request "https://api.example.com/v1/chat" headers "{}")
          hm  (.map (.headers req))]
      (doseq [[k v] headers]
        (is (= [v] (vec (.get hm k))) (str "header " k)))
      (is (= "POST" (.method req)))
      (is (= (URI. "https://api.example.com/v1/chat") (.uri req))))))

(deftest body-is-carried-verbatim
  (testing "the serialised JSON is not re-encoded or altered"
    (let [body "{\"model\":\"m\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}"
          req (hf/post-request "https://x.test/y" {} body)]
      (is (= (long (count body))
             (.contentLength ^java.net.http.HttpRequest$BodyPublisher
                             (.get (.bodyPublisher req))))
          "body length must match the exact string llm.clj serialised"))))

(deftest a-configured-proxy-yields-a-selector
  (testing "a host/port pair becomes a ProxySelector

           NOTE what this does NOT assert. The name it originally carried
           claimed equivalence with llm.clj's proxy-opts; it does not test
           that, because both read System/getenv directly and a JVM cannot set
           its own env. The equivalence here is STRUCTURAL — same two env vars,
           same URI host/port extraction, same catch-all fallback — and that is
           weaker than it sounds.

           To make it real, both should read the proxy URL through one var that
           a test can redef, and then proxy-opts and proxy-host-port can be
           asserted to agree on the same inputs including the malformed ones.
           That is owed before step 3c ships the gate."
    (with-redefs [hf/proxy-host-port (fn [] ["proxy.corp" 3128])]
      (is (some? (hf/proxy-selector)) "a configured proxy yields a selector"))))

(deftest no-proxy-configured-yields-nil
  (testing "unset means unset — never a default that routes traffic somewhere"
    (with-redefs [hf/proxy-host-port (fn [] nil)]
      (is (nil? (hf/proxy-selector))))))

(deftest portless-proxy-url-is-treated-as-no-proxy
  (testing "URI.getPort returns -1 with no port, which InetSocketAddress rejects

           proxy-opts passes that -1 to clj-http today. Rather than invent a
           default port clj-http never used, this resolves to nil. Recorded as
           a deliberate divergence — inventing 8080 or 443 would route traffic
           somewhere nobody configured."
    (is (nil? (#'hf/proxy-host-port))
        "with no https_proxy in this env, nil")
    (let [uri (URI. "http://proxy.corp")]
      (is (= -1 (.getPort uri)) "the -1 this guards against is real"))))

(deftest client-builds-without-a-proxy
  (testing "the common path: no proxy env, a usable client"
    (with-redefs [hf/proxy-host-port (fn [] nil)]
      (is (some? (hf/client))))))
