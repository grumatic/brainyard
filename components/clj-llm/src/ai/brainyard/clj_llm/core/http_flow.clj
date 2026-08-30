;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.clj-llm.core.http-flow
  "SPIKE §18 step 3a: the java.net.http REQUEST, built from the same inputs
   clj-http gets — and nothing else.

   The streaming path currently calls `clj-http`. The pushed-body Flow needs
   `java.net.http`, so wiring it introduces a SECOND HTTP client into the code
   every streamed token passes through. The subtle mistakes there are not in
   the Flow — that is tested — but in silently failing to carry something
   clj-http was carrying: a proxy, a header, an exception shape.

   So this namespace builds the request and the client, sends NOTHING, and is
   tested for equivalence against the values `llm.clj` hands clj-http. Offline,
   deterministic, and it fails loudly rather than in production behind someone's
   corporate proxy."
  (:import [java.net URI ProxySelector InetSocketAddress]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers]
           [java.time Duration]))

(defn proxy-host-port
  "`https_proxy` / `HTTPS_PROXY` as `[host port]`, or nil.

   Deliberately mirrors `llm.clj`'s private `proxy-opts`, including its
   fallbacks, because a proxy the Flow path silently drops is an outage for
   everyone behind one and is invisible to every test that does not have a
   proxy set.

   ONE DELIBERATE DIVERGENCE, and it is a pre-existing sharp edge rather than a
   new decision: `URI.getPort` returns **-1** when the URL carries no port
   (`https_proxy=http://proxy.corp`). `proxy-opts` passes that -1 straight to
   clj-http; here it would reach `InetSocketAddress`, which rejects it. Rather
   than invent a default port that clj-http never used, a portless proxy URL
   resolves to nil — no proxy, same as an unparseable one. Inventing 8080 or
   443 would route traffic somewhere nobody configured."
  []
  (when-let [url (or (System/getenv "https_proxy") (System/getenv "HTTPS_PROXY"))]
    (try
      (let [uri  (URI. url)
            host (.getHost uri)
            port (.getPort uri)]
        (when (and host (pos? port)) [host port]))
      (catch Exception _ nil))))

(defn proxy-selector
  "A `ProxySelector` for the configured proxy, or nil when there is none."
  ^ProxySelector []
  (when-let [[host port] (proxy-host-port)]
    (ProxySelector/of (InetSocketAddress. ^String host ^int (int port)))))

(defn client
  "An `HttpClient` carrying the same proxy configuration clj-http would use."
  ^HttpClient []
  (let [b (HttpClient/newBuilder)]
    (when-let [ps (proxy-selector)] (.proxy b ps))
    (.build b)))

(defn post-request
  "A POST `HttpRequest` for `url` with `headers` (the string map `llm.clj`
   builds) and `body` (the already-serialised JSON string).

   Takes the SAME values `llm.clj` passes clj-http rather than an `lm-config`,
   so the header and body builders stay the single source of truth and cannot
   drift between the two clients."
  ^HttpRequest [url headers ^String body]
  (let [b (-> (HttpRequest/newBuilder (URI. url))
              (.timeout (Duration/ofMinutes 10))
              (.POST (HttpRequest$BodyPublishers/ofString body)))]
    (doseq [[k v] headers] (.header b (str k) (str v)))
    (.build b)))
