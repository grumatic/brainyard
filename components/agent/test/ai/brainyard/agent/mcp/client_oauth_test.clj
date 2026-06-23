;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.mcp.client-oauth-test
  "MCP HTTP transport OAuth wiring (docs/design/oauth.md §7.1): a server config
   `:auth {:type :oauth …}` logs in once, attaches a fresh bearer per request,
   and refreshes + retries once on a server-side 401."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.data.json :as json]
            [ai.brainyard.clj-http-native.interface :as http]
            [ai.brainyard.clj-oauth.interface :as oauth]
            [ai.brainyard.agent.mcp.client :as mcp-client]
            [ai.brainyard.agent.mcp.integration :as integration]))

(def ^:private oauth-server? @#'mcp-client/oauth-server?)
(def ^:private effective-auth-headers @#'mcp-client/effective-auth-headers)
(def ^:private unauthorized? @#'mcp-client/unauthorized?)

;; ---------------------------------------------------------------------------
;; Pure helpers
;; ---------------------------------------------------------------------------

(deftest oauth-server?-predicate
  (is (true?  (oauth-server? {:type :oauth :account-id "notion"})))
  (is (false? (oauth-server? {:type :static})))
  (is (false? (oauth-server? nil))))

(deftest unauthorized?-predicate
  (is (true?  (unauthorized? {:status 401})))
  (is (false? (unauthorized? {:status 200})))
  (is (false? (unauthorized? {:status 403}))))

(deftest effective-auth-headers-merges-fresh-bearer
  (testing "non-OAuth: static headers pass through untouched"
    (is (= {"X-Api-Key" "k"} (effective-auth-headers {"X-Api-Key" "k"} nil))))
  (testing "OAuth: a fresh bearer is merged over the static headers"
    (with-redefs [oauth/bearer-headers (fn [acct]
                                         (is (= "notion" acct))
                                         {"Authorization" "Bearer FRESH"})]
      (is (= {"X-Trace" "t" "Authorization" "Bearer FRESH"}
             (effective-auth-headers {"X-Trace" "t"}
                                     {:type :oauth :account-id "notion"}))))))

;; ---------------------------------------------------------------------------
;; account-id injection (integration layer)
;; ---------------------------------------------------------------------------

(deftest inject-oauth-account-id-rules
  (testing "defaults account-id to the server name"
    (is (= "notion"
           (get-in (integration/inject-oauth-account-id
                    {:url "u" :auth {:type :oauth}} "notion")
                   [:auth :account-id]))))
  (testing "an explicit account-id is preserved"
    (is (= "pinned"
           (get-in (integration/inject-oauth-account-id
                    {:url "u" :auth {:type :oauth :account-id "pinned"}} "notion")
                   [:auth :account-id]))))
  (testing "non-OAuth config is untouched"
    (is (= {:url "u"} (integration/inject-oauth-account-id {:url "u"} "notion")))))

;; ---------------------------------------------------------------------------
;; connect! — logs in, attaches bearer, persists :oauth-auth
;; ---------------------------------------------------------------------------

(defn- init-ok [session]
  {:status 200
   :headers {"mcp-session-id" session}
   :body (json/write-str {:id 1 :result {:serverInfo {:name "mock"}}})})

(deftest connect!-logs-in-and-attaches-bearer
  (let [login-calls (atom 0)
        seen-auth   (atom [])]
    (with-redefs [oauth/authenticated? (fn [_] false)
                  oauth/login!        (fn [opts]
                                        (swap! login-calls inc)
                                        (is (= "notion" (:account-id opts)))
                                        (is (fn? (:on-user-prompt opts)))
                                        {:access_token "AT"})
                  oauth/bearer-headers (fn [_] {"Authorization" "Bearer AT"})
                  http/post (fn [_ opts]
                              (swap! seen-auth conj (get-in opts [:headers "Authorization"]))
                              (init-ok "S1"))]
      (let [client    (mcp-client/create-client :http {})
            connected (mcp-client/connect! client {:url "https://mcp.notion.com/mcp"
                                                   :auth {:type :oauth :account-id "notion"}})]
        (testing "login ran exactly once before initialize"
          (is (= 1 @login-calls)))
        (testing "the initialize POST carried the bearer"
          (is (= "Bearer AT" (first @seen-auth))))
        (testing "connected record carries session + oauth auth config"
          (is (= "S1" (:session-id connected)))
          (is (= :oauth (get-in connected [:oauth-auth :type]))))))))

(deftest connect!-skips-login-when-token-usable
  ;; The skip-login gate is `token-usable?` (present AND not-expired-or-refreshable),
  ;; not bare `authenticated?` — a stale refresh-less bundle is authenticated but
  ;; unusable and must re-login. A usable token skips login.
  (let [login-calls (atom 0)]
    (with-redefs [oauth/token-usable?  (fn [_] true)
                  oauth/login!         (fn [_] (swap! login-calls inc) {})
                  oauth/bearer-headers (fn [_] {"Authorization" "Bearer AT"})
                  http/post (fn [_ _] (init-ok "S2"))]
      (mcp-client/connect! (mcp-client/create-client :http {})
                           {:url "u" :auth {:type :oauth :account-id "notion"}})
      (is (zero? @login-calls) "usable token → no re-login"))))

(deftest connect!-relogins-when-token-unusable
  ;; A stale, refresh-less bundle (authenticated? true but token-usable? false)
  ;; must trigger a fresh login rather than dead-ending downstream.
  (let [login-calls (atom 0)]
    (with-redefs [oauth/authenticated? (fn [_] true)
                  oauth/token-usable?  (fn [_] false)
                  oauth/login!         (fn [_] (swap! login-calls inc) {})
                  oauth/bearer-headers (fn [_] {"Authorization" "Bearer AT"})
                  http/post (fn [_ _] (init-ok "S3"))]
      (mcp-client/connect! (mcp-client/create-client :http {})
                           {:url "u" :auth {:type :oauth :account-id "notion"}})
      (is (= 1 @login-calls) "unusable stored token → re-login"))))

(deftest connect!-401-relogins-when-refresh-unavailable
  ;; initialize 401s on a usable-looking token; refresh! can't service it (e.g.
  ;; GitHub issues no refresh token) → clear + re-login + retry, rather than
  ;; failing the connect with "No refresh token available".
  (let [usable      (atom true)
        login-calls (atom 0)
        logout-calls (atom 0)
        post-calls  (atom 0)]
    (with-redefs [oauth/token-usable?  (fn [_] @usable)
                  oauth/refresh!       (fn [_] (throw (ex-info "No refresh token available." {})))
                  oauth/logout!        (fn [_] (swap! logout-calls inc) (reset! usable false) nil)
                  oauth/login!         (fn [_] (swap! login-calls inc) {:access_token "AT2"})
                  oauth/bearer-headers (fn [_] {"Authorization" "Bearer AT"})
                  http/post (fn [_ _]
                              (if (= 1 (swap! post-calls inc))
                                {:status 401 :headers {} :body ""}   ; initialize rejected
                                (init-ok "S4")))]                      ; retry after re-login
      (let [connected (mcp-client/connect! (mcp-client/create-client :http {})
                                           {:url "u" :auth {:type :oauth :account-id "github2"}})]
        (is (= 1 @logout-calls) "stale creds cleared")
        (is (= 1 @login-calls)  "re-login ran once after the failed refresh")
        (is (= "S4" (:session-id connected)) "initialize retried successfully after re-login")))))

;; ---------------------------------------------------------------------------
;; send-request! — mid-session 401 forces one refresh + retry
;; ---------------------------------------------------------------------------

(deftest send-request!-refreshes-and-retries-on-401
  (let [refreshed (atom 0)
        responses (atom [{:status 401 :headers {} :body "unauthorized"}
                         {:status 200 :headers {}
                          :body (json/write-str {:id 1 :result {:tools []}})}])]
    (with-redefs [oauth/bearer-headers (fn [_] {"Authorization" "Bearer AT"})
                  oauth/refresh!       (fn [acct] (swap! refreshed inc) (is (= "notion" acct)) "AT2")
                  http/post (fn [_ _] (let [r (first @responses)] (swap! responses rest) r))]
      (let [client (assoc (mcp-client/create-client :http {})
                          :base-url "https://mcp.notion.com/mcp"
                          :session-id "S1"
                          :oauth-auth {:type :oauth :account-id "notion"})
            result (mcp-client/send-request! client "tools/list" {})]
        (is (= 1 @refreshed) "refreshed exactly once on the 401")
        (is (= {:tools []} result) "retry succeeded and returned the result")))))

(deftest send-request!-no-oauth-no-retry
  (testing "a 401 on a non-OAuth client surfaces immediately (no refresh attempt)"
    (with-redefs [http/post (fn [_ _] {:status 401 :headers {} :body "nope"})]
      (let [client (assoc (mcp-client/create-client :http {})
                          :base-url "u" :session-id "S1")]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"HTTP request failed"
                              (mcp-client/send-request! client "tools/list" {})))))))

;; ---------------------------------------------------------------------------
;; OAuth prompt renderer sink (Phase 4 rich code box)
;; ---------------------------------------------------------------------------

(deftest prompt-renderer-routing
  (let [dispatch @#'mcp-client/dispatch-oauth-prompt!
        seen     (atom [])]
    (try
      (mcp-client/set-oauth-prompt-renderer! (fn [ev] (swap! seen conj (:event ev))))
      (dispatch {:event :prompt :account-id "notion" :user_code "X"})
      (dispatch {:event :slow-down :account-id "notion"})
      (dispatch {:event :authorized :account-id "notion"})
      (is (= [:prompt :slow-down :authorized] @seen)
          "all events route to the registered renderer")
      (finally (mcp-client/set-oauth-prompt-renderer! nil)))))

(deftest prompt-renderer-failure-never-breaks-login
  (testing "a throwing renderer is swallowed (falls back for :prompt)"
    (let [dispatch @#'mcp-client/dispatch-oauth-prompt!]
      (try
        (mcp-client/set-oauth-prompt-renderer! (fn [_] (throw (RuntimeException. "boom"))))
        (is (nil? (dispatch {:event :prompt :account-id "n"
                             :verification_uri "u" :user_code "c"})))
        (is (nil? (dispatch {:event :authorized :account-id "n"})))
        (finally (mcp-client/set-oauth-prompt-renderer! nil))))))

;; ---------------------------------------------------------------------------
;; 401 WWW-Authenticate discovery (undeclared :auth) — MCP auth spec / RFC 9728
;; ---------------------------------------------------------------------------

(def ^:private discover-challenge @#'mcp-client/discover-oauth-from-challenge)

(deftest challenge-discovery-origin-fallback
  (testing "no resource_metadata pointer → issuer is the resource origin"
    (let [auth (discover-challenge "http://localhost:7777/mcp"
                                   {:status 401 :headers {"www-authenticate" "Bearer realm=\"x\""}}
                                   "brainyard")]
      (is (= :oauth (:type auth)))
      (is (= "brainyard" (:account-id auth)))
      (is (= "http://localhost:7777" (:issuer auth)))
      (is (= :auto (:flow auth))))))

(deftest challenge-discovery-resource-metadata
  (testing "resource_metadata pointer is followed to authorization_servers"
    (with-redefs [http/get (fn [url _]
                             (is (= "https://rs/.well-known/oauth-protected-resource" url))
                             {:status 200 :body (json/write-str {:authorization_servers ["https://as.example"]})})]
      (let [auth (discover-challenge
                  "https://rs/mcp"
                  {:status 401 :headers {"www-authenticate"
                                         "Bearer resource_metadata=\"https://rs/.well-known/oauth-protected-resource\""}}
                  "notion")]
        (is (= "https://as.example" (:issuer auth)))))))

(deftest challenge-discovery-not-an-oauth-challenge
  (is (nil? (discover-challenge "http://x/mcp" {:status 200} "s")))
  (is (nil? (discover-challenge "http://x/mcp" {:status 401 :headers {"www-authenticate" "Basic realm=x"}} "s"))))

(deftest connect!-discovers-oauth-from-401
  (testing "an undeclared server that 401s with a Bearer challenge auto-authenticates"
    (let [logins (atom 0)]
      (with-redefs [oauth/authenticated? (fn [_] false)
                    oauth/login!        (fn [opts]
                                          (swap! logins inc)
                                          (is (= "brainyard" (:account-id opts)))
                                          (is (= "http://localhost:7779" (:issuer opts)))
                                          {:access_token "AT"})
                    oauth/bearer-headers (fn [_] {"Authorization" "Bearer AT"})
                    http/post (fn [_ opts]
                                (if (get-in opts [:headers "Authorization"])
                                  (init-ok "S9")
                                  {:status 401 :headers {"www-authenticate" "Bearer realm=\"brainyard\""} :body ""}))]
        (let [connected (mcp-client/connect! (mcp-client/create-client :http {})
                                             {:url "http://localhost:7779/mcp" :server-name "brainyard"})]
          (is (= 1 @logins) "discovered the issuer and logged in once")
          (is (= "S9" (:session-id connected)))
          (is (= :oauth (get-in connected [:oauth-auth :type])))
          (is (= "brainyard" (get-in connected [:oauth-auth :account-id]))))))))
