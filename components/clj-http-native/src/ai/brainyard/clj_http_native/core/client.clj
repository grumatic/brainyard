;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.clj-http-native.core.client
  "Implementation of the clj-http-compatible HTTP client built on
   `java.net.http`. Public API in `..interface`."
  (:require [clojure.string :as str])
  (:import [java.net URI ProxySelector InetSocketAddress]
           [java.net.http HttpClient HttpClient$Version HttpClient$Builder
            HttpRequest HttpRequest$Builder HttpRequest$BodyPublishers
            HttpResponse HttpResponse$BodyHandlers]
           [java.time Duration]
           [java.io InputStream InputStreamReader BufferedReader]
           [java.nio.charset StandardCharsets]
           [java.security KeyManagementException NoSuchAlgorithmException
            SecureRandom]
           [java.security.cert X509Certificate]
           [javax.net.ssl SSLContext TrustManager X509TrustManager
            HttpsURLConnection HostnameVerifier]))

;; ============================================================================
;; SSL: insecure mode (off by default, opt-in for self-signed / dev only)
;; ============================================================================

(defn- ^SSLContext insecure-ssl-context
  "Build an SSLContext that trusts everything. Use only for development
   against self-signed certs (CLJ_HTTP_INSECURE=true or :insecure? true)."
  []
  (let [trust-all (proxy [X509TrustManager] []
                    (checkClientTrusted [_certs _auth] nil)
                    (checkServerTrusted [_certs _auth] nil)
                    (getAcceptedIssuers [] (make-array X509Certificate 0)))
        ctx       (SSLContext/getInstance "TLS")]
    (.init ctx nil (into-array TrustManager [trust-all]) (SecureRandom.))
    ctx))

;; ============================================================================
;; HttpClient: shared, lazily-built
;; ============================================================================

(defn- env-proxy
  "Return a {:host :port} map from the https_proxy / HTTPS_PROXY env vars,
   or nil if neither is set / parseable."
  []
  (when-let [proxy-url (or (System/getenv "https_proxy")
                           (System/getenv "HTTPS_PROXY"))]
    (try
      (let [uri (URI. proxy-url)]
        (when (and (.getHost uri) (pos? (.getPort uri)))
          {:host (.getHost uri) :port (.getPort uri)}))
      (catch Exception _ nil))))

(defn- ^HttpClient build-client
  "Build a fresh HttpClient configured from the supplied opts. We do
   NOT share a single global client because per-call options (proxy,
   insecure SSL) can differ — but the per-call cost is small since
   HttpClient itself is lightweight; the heavyweight pieces (TLS
   session cache, connection pool) are inside the JDK's shared
   `sun.net.www.protocol.https` machinery."
  [{:keys [connect-timeout-ms proxy-host proxy-port insecure?]
    :or   {connect-timeout-ms 10000}}]
  (let [b ^HttpClient$Builder (-> (HttpClient/newBuilder)
                                  (.version HttpClient$Version/HTTP_2)
                                  (.connectTimeout (Duration/ofMillis connect-timeout-ms)))
        b (if-let [pp (or (when proxy-host
                            {:host proxy-host :port (or proxy-port 8080)})
                          (env-proxy))]
            (.proxy b (ProxySelector/of (InetSocketAddress. ^String (:host pp)
                                                            (int (:port pp)))))
            b)
        b (if (or insecure? (= "true" (System/getenv "CLJ_HTTP_INSECURE")))
            (.sslContext b (insecure-ssl-context))
            b)]
    (.build b)))

;; ============================================================================
;; Headers
;; ============================================================================

(defn- ^HttpRequest$Builder apply-headers
  "Set request headers from a map. Skips nil values; coerces non-string
   values via str."
  [^HttpRequest$Builder b headers]
  (doseq [[k v] headers]
    (when (and (some? k) (some? v))
      (.header b (name k) (str v))))
  b)

(defn- headers-from-response
  "Coerce java.net.http HttpHeaders to a Clojure map with lowercase
   string keys and single-string values (joined with comma when the JDK
   reports a multi-value header — clj-http does the same). Drops HTTP/2
   pseudo-headers (`:status`, `:authority`, …) which the JDK surfaces in
   the response headers map but clj-http never exposed."
  [^java.net.http.HttpHeaders hs]
  ;; Thread the transient through reduce — `assoc!` may RETURN a new transient
  ;; (when an array-backed transient promotes to a hash-map past 8 entries), so
  ;; a `doseq` that discards the return silently loses the 9th+ key. With JDK
  ;; header maps sorted case-insensitively, that dropped the alphabetically-last
  ;; header (e.g. `www-authenticate`) on any response with >8 headers.
  (persistent!
   (reduce (fn [out [k vs]]
             (if (str/starts-with? k ":")
               out
               (assoc! out (str/lower-case k) (str/join ", " vs))))
           (transient {})
           (.map hs))))

;; ============================================================================
;; Body publishers / handlers
;; ============================================================================

(defn- ^HttpRequest$BodyPublishers body-publisher
  "Return a BodyPublisher for the supplied body."
  [body]
  (cond
    (nil? body)         (HttpRequest$BodyPublishers/noBody)
    (string? body)      (HttpRequest$BodyPublishers/ofString ^String body
                                                             StandardCharsets/UTF_8)
    (bytes? body)       (HttpRequest$BodyPublishers/ofByteArray ^bytes body)
    (instance? java.io.File body)
    (HttpRequest$BodyPublishers/ofFile (.toPath ^java.io.File body))
    :else
    (HttpRequest$BodyPublishers/ofString (str body) StandardCharsets/UTF_8)))

(defn- body-handler
  "Return a java.net.http HttpResponse$BodyHandler matching the :as option."
  [as]
  (case as
    (:string nil) (HttpResponse$BodyHandlers/ofString StandardCharsets/UTF_8)
    :stream       (HttpResponse$BodyHandlers/ofInputStream)
    :reader       (HttpResponse$BodyHandlers/ofInputStream)
    :byte-array   (HttpResponse$BodyHandlers/ofByteArray)
    (throw (ex-info (str "Unsupported :as " as) {:as as}))))

(defn- coerce-body
  "Convert the JDK body (InputStream or String) to the shape callers
   expect for :as."
  [body as]
  (case as
    (:string nil) body
    :stream       body
    :reader       (-> ^InputStream body
                      (InputStreamReader. StandardCharsets/UTF_8)
                      (BufferedReader.))
    :byte-array   body))

;; ============================================================================
;; Method
;; ============================================================================

(defn- ^HttpRequest$Builder apply-method
  "Set the HTTP method on the request builder, with a body publisher
   when one applies."
  [^HttpRequest$Builder b method body]
  (let [bp (body-publisher body)]
    (case method
      :GET    (.GET b)
      :DELETE (.DELETE b)
      :POST   (.POST b bp)
      :PUT    (.PUT b bp)
      (.method b (name method) bp))))

;; ============================================================================
;; Public entry
;; ============================================================================

(defn request
  "Issue an HTTP request and return {:status :headers :body}."
  [method url
   {:keys [headers body as
           throw-exceptions
           timeout-ms
           content-type]
    :or   {timeout-ms       60000
           throw-exceptions false
           as               :string}
    :as   opts}]
  (let [client  (build-client opts)
        ;; :content-type :json is clj-http sugar; mirror it.
        headers (cond-> (or headers {})
                  (= :json content-type) (assoc "Content-Type" "application/json"))
        req     ^HttpRequest (-> ^HttpRequest$Builder (HttpRequest/newBuilder (URI. url))
                                 (.timeout (Duration/ofMillis (long timeout-ms)))
                                 (apply-method method body)
                                 (apply-headers headers)
                                 (.build))
        resp    ^HttpResponse (.send ^HttpClient client req (body-handler as))
        status  (.statusCode resp)
        result  {:status  status
                 :headers (headers-from-response (.headers resp))
                 :body    (coerce-body (.body resp) as)}]
    (if (and throw-exceptions (>= status 400))
      (throw (ex-info (str "HTTP " status " " url)
                      {:status  status
                       :headers (:headers result)
                       :body    (let [b (:body result)]
                                  (cond
                                    (string? b) b
                                    (instance? InputStream b)
                                    (slurp (InputStreamReader. ^InputStream b
                                                               StandardCharsets/UTF_8))
                                    :else b))}))
      result)))
