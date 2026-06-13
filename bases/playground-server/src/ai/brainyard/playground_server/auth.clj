;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
(ns ai.brainyard.playground-server.auth
  "Identity — the `playground-auth` component. Two modes behind one surface:

   - **OIDC** when `OIDC_ISSUER` is set: standard authorization-code flow against
     a discovered IdP. `/auth/login` redirects to the IdP; `/auth/callback`
     exchanges the code at the token endpoint (back channel) and starts a
     server-side session keyed by an httpOnly cookie. The stable OIDC `sub`
     becomes a filesystem-safe `user-id` (sessions/memory partition on it).
   - **stub** otherwise: a bare cookie = one demo tenant, so the dev / `PG_FAKE`
     flow runs with no IdP.

   The id_token is verified: its RS256 signature is checked against the IdP's
   JWKS (fetched from discovery, keyed by `kid`), and iss/aud/exp + the `nonce`
   (bound to the login request) are validated. Moving the server-side session
   into the store (for a fully stateless control plane) is Phase-1 hardening."
  (:require [clojure.string :as str]
            [jsonista.core :as j])
  (:import [java.math BigInteger]
           [java.net URI URLEncoder]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers
            HttpResponse$BodyHandlers]
           [java.security KeyFactory MessageDigest Signature]
           [java.security.spec RSAPublicKeySpec]
           [java.util Base64]))

(def cookie-name "pg_session")
(def ^:private session-ttl-ms (* 8 60 60 1000))    ; 8h
(def ^:private state-ttl-ms   (* 10 60 1000))      ; 10m

(defonce ^:private http (delay (HttpClient/newHttpClient)))
(defonce ^:private disco-cache (atom nil))
(defonce ^:private jwks-cache  (atom nil))   ; kid -> JWK map
(defonce ^:private states   (atom {}))   ; csrf state -> {:created :nonce}
(defonce ^:private sessions (atom {}))   ; sid -> {:user-id :email :exp}

;; --- config ----------------------------------------------------------------

(defn configured? [] (boolean (not-empty (System/getenv "OIDC_ISSUER"))))
(defn- issuer        [] (str/replace (System/getenv "OIDC_ISSUER") #"/$" ""))
(defn- client-id     [] (System/getenv "OIDC_CLIENT_ID"))
(defn- client-secret [] (System/getenv "OIDC_CLIENT_SECRET"))
(defn- redirect-uri  [] (or (not-empty (System/getenv "OIDC_REDIRECT_URI"))
                            "http://localhost:8090/auth/callback"))

;; --- small http/json/crypto helpers ----------------------------------------

(defn- enc ^String [s] (URLEncoder/encode (str s) "UTF-8"))
(defn- now [] (System/currentTimeMillis))
(defn- rand-tok [] (str/replace (str (random-uuid) (random-uuid)) "-" ""))

(defn- http-get [url]
  (let [resp (.send @http (-> (HttpRequest/newBuilder (URI/create url)) (.GET) (.build))
                    (HttpResponse$BodyHandlers/ofString))]
    (when (= 200 (.statusCode resp)) (.body resp))))

(defn- http-post-form [url form]
  (let [body (->> form (map (fn [[k v]] (str (enc k) "=" (enc v)))) (str/join "&"))
        resp (.send @http (-> (HttpRequest/newBuilder (URI/create url))
                              (.header "Content-Type" "application/x-www-form-urlencoded")
                              (.header "Accept" "application/json")
                              (.POST (HttpRequest$BodyPublishers/ofString body))
                              (.build))
                    (HttpResponse$BodyHandlers/ofString))]
    {:status (.statusCode resp) :body (.body resp)}))

(defn- ->json [s] (j/read-value s (j/object-mapper {:decode-key-fn keyword})))

(defn- discover []
  (or @disco-cache
      (some->> (http-get (str (issuer) "/.well-known/openid-configuration"))
               ->json (reset! disco-cache))))

(defn- b64url->bytes ^bytes [^String s]
  (.decode (Base64/getUrlDecoder) s))

(defn- jwt-segment->map [seg]
  (when seg (-> (b64url->bytes seg) (String. "UTF-8") ->json)))

(defn- jwks
  "kid -> JWK, fetched from the discovered jwks_uri (cached)."
  []
  (or @jwks-cache
      (let [ks (some-> (discover) :jwks_uri http-get ->json :keys)]
        (when (seq ks)
          (reset! jwks-cache (into {} (map (juxt :kid identity)) ks))))))

(defn- jwk->public-key [{:keys [n e]}]
  (let [bi (fn [^String s] (BigInteger. 1 (b64url->bytes s)))]
    (.generatePublic (KeyFactory/getInstance "RSA")
                     (RSAPublicKeySpec. (bi n) (bi e)))))

(defn- verify-rs256? [^String jwt jwk]
  (try
    (let [[h p s] (str/split jwt #"\.")
          sig (doto (Signature/getInstance "SHA256withRSA")
                (.initVerify (jwk->public-key jwk))
                (.update (.getBytes (str h "." p) "UTF-8")))]
      (.verify sig (b64url->bytes s)))
    (catch Exception _ false)))

(defn- verify-and-decode
  "Verify the id_token's RS256 signature against the JWKS (keyed by `kid`) and
   return its claims, or nil if the signature is missing/unknown/invalid."
  [^String jwt]
  (let [[h _ _] (str/split jwt #"\.")
        {:keys [kid alg]} (jwt-segment->map h)
        jwk (get (jwks) kid)]
    (when (and jwk (= "RS256" (or alg "RS256")) (verify-rs256? jwt jwk))
      (jwt-segment->map (second (str/split jwt #"\."))))))

(defn- sub->user-id
  "Stable, filesystem-safe id from the OIDC sub (sha-256 hex, truncated)."
  [sub]
  (let [d (.digest (MessageDigest/getInstance "SHA-256") (.getBytes (str sub) "UTF-8"))]
    (->> d (take 8) (map #(format "%02x" %)) (apply str))))

;; --- session cookie --------------------------------------------------------

(defn- set-cookie [sid]
  {cookie-name {:value sid :http-only true :path "/" :same-site :lax}})

(defn- clear-cookie []
  {cookie-name {:value "" :http-only true :path "/" :max-age 0}})

(defn- cookie-val [req] (some-> req :cookies (get cookie-name) :value not-empty))

;; --- public: identity ------------------------------------------------------

(defn identity-of
  "Resolve the request's identity -> {:user-id :email} or nil."
  [req]
  (if (configured?)
    (when-let [s (get @sessions (cookie-val req))]
      (when (> (:exp s) (now)) (select-keys s [:user-id :email])))
    (when (cookie-val req)
      {:user-id "demo-user" :email "demo@grumatic.com"})))

;; --- public: handlers ------------------------------------------------------

(defn login [_req]
  (if (configured?)
    (let [state (rand-tok)
          nonce (rand-tok)
          _     (swap! states assoc state {:created (now) :nonce nonce})
          d     (discover)
          url   (str (:authorization_endpoint d)
                     "?response_type=code"
                     "&client_id="    (enc (client-id))
                     "&redirect_uri=" (enc (redirect-uri))
                     "&scope="        (enc "openid email profile")
                     "&state="        state
                     "&nonce="        nonce)]
      {:status 302 :headers {"Location" url}})
    ;; stub: a bare cookie = the demo tenant
    {:status 302 :headers {"Location" "/"} :cookies (set-cookie (rand-tok))}))

(defn- valid-claims? [claims expected-nonce]
  (and claims
       (= (:iss claims) (issuer))
       (let [aud (:aud claims)]
         (if (coll? aud) (some #{(client-id)} aud) (= aud (client-id))))
       (= (:nonce claims) expected-nonce)
       (or (nil? (:exp claims)) (> (* 1000 (long (:exp claims))) (now)))))

(defn callback
  "OIDC redirect target: exchange code -> verify id_token -> session cookie."
  [req]
  (let [{:strs [code state]} (:query-params req)
        st (get @states state)]
    (cond
      (not (configured?))       {:status 404 :body "OIDC not configured"}
      (not (and code state st)) {:status 400 :body "invalid state or code"}
      :else
      (do
        (swap! states dissoc state)
        (let [d (discover)
              {:keys [status body]} (http-post-form
                                     (:token_endpoint d)
                                     {"grant_type"    "authorization_code"
                                      "code"          code
                                      "redirect_uri"  (redirect-uri)
                                      "client_id"     (client-id)
                                      "client_secret" (client-secret)})
              claims (when (= 200 status)
                       (some-> (->json body) :id_token verify-and-decode))]
          (if (valid-claims? claims (:nonce st))
            (let [sid (rand-tok)]
              (swap! sessions assoc sid
                     {:user-id (sub->user-id (:sub claims))
                      :email   (or (:email claims) (:preferred_username claims) (:sub claims))
                      :exp     (+ (now) session-ttl-ms)})
              {:status 302 :headers {"Location" "/"} :cookies (set-cookie sid)})
            {:status 401 :body "token exchange failed"}))))))

(defn logout [req]
  (when (configured?)
    (swap! sessions dissoc (cookie-val req)))
  {:status 302 :headers {"Location" "/"} :cookies (clear-cookie)})
