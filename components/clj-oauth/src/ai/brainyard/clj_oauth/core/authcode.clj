;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.clj-oauth.core.authcode
  "Provider-agnostic authorization-code + PKCE flow with an out-of-band paste
   step — the formalized, headless version of what clj-llm's Anthropic flow
   already does by hand. Used as the fallback rung when discovery shows no
   device endpoint: still beats browser-loopback in a container (no listening
   port), it just needs the user to paste the code back.

   Unlike loopback, this binds no socket. The caller supplies `:read-code` (how
   to read the pasted code/URL — e.g. the TUI's input box, or `read-line`); we
   never assume a TTY. `post-fn` is injectable for tests."
  (:require [ai.brainyard.clj-http-native.interface :as http]
            [ai.brainyard.clj-oauth.core.encode :as encode]
            [ai.brainyard.clj-oauth.core.pkce :as pkce]
            [ai.brainyard.mulog.interface :as mulog]
            [clojure.data.json :as json]
            [clojure.string :as str])
  (:import [java.net URLEncoder]))

(def ^:private form-ct "application/x-www-form-urlencoded")
;; Out-of-band redirect: the provider shows the code instead of redirecting to a
;; listener. RFC 8628 superseded this for CLIs, but providers without a device
;; endpoint still support OOB, and it needs no local port.
(def ^:private oob-redirect "urn:ietf:wg:oauth:2.0:oob")

(defn ^:private enc [s] (URLEncoder/encode (str s) "UTF-8"))

(defn authorize-url
  "Build an authorization-code + PKCE authorize URL from discovery metadata."
  [{:keys [authorization-endpoint client-id redirect-uri scopes code-challenge state]
    :or   {redirect-uri oob-redirect scopes []}}]
  (str authorization-endpoint
       "?response_type=code"
       "&client_id=" (enc client-id)
       "&redirect_uri=" (enc redirect-uri)
       "&scope=" (enc (str/join " " scopes))
       "&code_challenge=" (enc code-challenge)
       "&code_challenge_method=S256"
       "&state=" (enc state)))

(defn ^:private parse-pasted
  "Extract the auth code from a pasted value: a bare code, `code#state`, or a
   full callback URL with `?code=&state=`. Returns {:code :state} or {:error}."
  [pasted]
  (let [s (str/trim pasted)]
    (cond
      (str/includes? s "?code=")
      (let [q (some-> (java.net.URI. s) .getQuery)
            m (when q (into {} (map #(str/split % #"=" 2) (str/split q #"&"))))]
        (if-let [c (get m "code")] {:code c :state (get m "state")} {:error "no code in URL"}))

      (str/includes? s "#")
      (let [[c st] (str/split s #"#" 2)] {:code c :state st})

      (str/blank? s) {:error "empty input"}
      :else {:code s})))

(defn exchange-code!
  "Exchange an authorization `code` for tokens at `token-endpoint` (form-encoded
   per RFC 6749). Returns the token bundle with `:expires_at`. `post-fn`
   injectable for tests."
  [{:keys [token-endpoint client-id code code-verifier redirect-uri post-fn]
    :or   {redirect-uri oob-redirect post-fn http/post}}]
  (let [resp (post-fn token-endpoint
                      {:body         (encode/form-encode
                                      {"grant_type"    "authorization_code"
                                       "client_id"     client-id
                                       "code"          code
                                       "code_verifier" code-verifier
                                       "redirect_uri"  redirect-uri})
                       :content-type form-ct
                       :as :string
                       :throw-exceptions false})]
    (when-not (<= 200 (:status resp) 299)
      (throw (ex-info "Authorization-code exchange failed"
                      {:status (:status resp) :body (:body resp)})))
    (let [body (json/read-str (:body resp) :key-fn keyword)
          now  (System/currentTimeMillis)]
      (assoc body
             :expires_at  (+ now (* (:expires_in body 3600) 1000))
             :obtained_at now))))

(defn paste-login!
  "Run the headless auth-code + PKCE paste flow. Shows the authorize URL via
   `on-user-prompt`, reads the pasted code via the caller-supplied `read-code`
   (0-arg fn → string), validates `state`, and exchanges. Returns the bundle.

   Requires `:read-code`; a non-interactive caller that can't read input gets a
   clear error rather than a hang."
  [{:keys [authorization-endpoint token-endpoint client-id scopes
           on-user-prompt read-code redirect-uri post-fn]
    :or   {redirect-uri oob-redirect post-fn http/post}}]
  (when-not (fn? read-code)
    (throw (ex-info "paste-login! requires a :read-code fn (no input source)"
                    {:flow :paste})))
  (let [verifier  (pkce/generate-code-verifier)
        challenge (pkce/generate-code-challenge verifier)
        state     (pkce/generate-state)
        url       (authorize-url {:authorization-endpoint authorization-endpoint
                                  :client-id      client-id
                                  :redirect-uri   redirect-uri
                                  :scopes         scopes
                                  :code-challenge challenge
                                  :state          state})]
    (when on-user-prompt
      (on-user-prompt {:authorize_uri url :scopes scopes}))
    (let [parsed (parse-pasted (read-code))]
      (when (:error parsed)
        (throw (ex-info (str "Could not parse pasted authorization code: " (:error parsed))
                        {:flow :paste})))
      (when (and (:state parsed) (not= (:state parsed) state))
        (throw (ex-info "OAuth state mismatch — possible CSRF" {:flow :paste})))
      (mulog/info ::authcode-exchange :endpoint token-endpoint)
      (exchange-code! {:token-endpoint token-endpoint
                       :client-id      client-id
                       :code           (:code parsed)
                       :code-verifier  verifier
                       :redirect-uri   redirect-uri
                       :post-fn        post-fn}))))
