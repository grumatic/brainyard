;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.clj-oauth.core.loopback
  "OAuth 2.0 authorization-code + PKCE via a loopback redirect (RFC 8252).

   For LOCAL use with a browser: providers that require a real redirect_uri and
   don't support device flow or OOB (e.g. Notion) need somewhere to redirect the
   `?code=`. A short-lived HTTP server on `127.0.0.1:<random>` receives it — no
   code is pasted. Opt-in (`:flow :loopback`); needs a local browser, so it is
   not for headless containers. Built on the JDK `HttpServer`, and the browser
   is opened via the OS opener subprocess (no `java.awt.Desktop` — native-safe)."
  (:require [ai.brainyard.clj-http-native.interface :as http]
            [ai.brainyard.clj-oauth.core.authcode :as authcode]
            [ai.brainyard.clj-oauth.core.pkce :as pkce]
            [ai.brainyard.mulog.interface :as mulog]
            [clojure.java.shell :as shell]
            [clojure.string :as str])
  (:import [com.sun.net.httpserver HttpServer HttpHandler HttpExchange]
           [java.net InetSocketAddress URLDecoder]))

(defn ^:private query-params [^HttpExchange ex]
  (into {} (for [pair (str/split (or (.getQuery (.getRequestURI ex)) "") #"&")
                 :when (seq pair)]
             (let [[k v] (str/split pair #"=" 2)
                   ;; Hinted locals pin the decode(String,String) overload —
                   ;; without ^String the call is reflective (k/v are untyped),
                   ;; which fails under native-image's strict reflection on the
                   ;; callback path. The hint only attaches to a symbol, not to
                   ;; the (or v "") form, so bind it first.
                   ^String k k
                   ^String v (or v "")]
               [(URLDecoder/decode k "UTF-8")
                (URLDecoder/decode v "UTF-8")]))))

(defn ^:private respond-html! [^HttpExchange ex ^String body]
  (let [bs (.getBytes body "UTF-8")]
    (.set (.getResponseHeaders ex) "Content-Type" "text/html; charset=utf-8")
    (.sendResponseHeaders ex 200 (alength bs))
    (with-open [os (.getResponseBody ex)] (.write os bs))))

(defn start-callback-server!
  "Start a loopback callback server on 127.0.0.1:<random>. Returns
   `{:redirect-uri :await :stop!}`; `await` blocks up to `timeout-ms` for
   `{:code :state :error}` (or `::timeout`)."
  []
  (let [result (promise)
        server (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)]
    (.createContext
     server "/callback"
     (reify HttpHandler
       (handle [_ ex]
         (let [q (query-params ex)]
           (respond-html!
            ex (if (q "code")
                 "<html><body style='font-family:sans-serif'><h2>&#10003; Authorized</h2><p>You can close this tab and return to your terminal.</p></body></html>"
                 (str "<html><body style='font-family:sans-serif'><h2>Authorization failed</h2><p>"
                      (or (q "error") "no code returned") "</p></body></html>")))
           (deliver result {:code (q "code") :state (q "state") :error (q "error")})
           (.close ex)))))
    (.setExecutor server nil)
    (.start server)
    (let [port (.getPort (.getAddress server))]
      {:redirect-uri (str "http://127.0.0.1:" port "/callback")
       :await        (fn [timeout-ms] (deref result timeout-ms ::timeout))
       :stop!        (fn [] (.stop server 0))})))

(defn open-browser!
  "Best-effort: open `url` in the default browser via the OS opener (subprocess,
   off-thread — native-image safe, no java.awt.Desktop). Never throws."
  [url]
  (try
    (let [os  (str/lower-case (str (System/getProperty "os.name")))
          cmd (cond (str/includes? os "mac") ["open" url]
                    (str/includes? os "win") ["cmd" "/c" "start" url]
                    :else                     ["xdg-open" url])]
      (future (try (apply shell/sh cmd) (catch Throwable _ nil)))
      true)
    (catch Throwable _ false)))

(defn loopback-login!
  "Run the loopback auth-code + PKCE flow: open the browser to the authorize URL
   (also surfaced via `on-user-prompt`), wait for the `?code=` redirect on the
   callback server, validate `state`, and exchange. Returns the token bundle.
   `open-browser-fn`/`post-fn` are injectable for tests."
  [{:keys [authorization-endpoint token-endpoint client-id scopes redirect-uri
           on-user-prompt await-fn open-browser-fn post-fn timeout-ms]
    :or   {open-browser-fn open-browser! post-fn http/post timeout-ms 300000}}]
  (let [verifier  (pkce/generate-code-verifier)
        challenge (pkce/generate-code-challenge verifier)
        csrf      (pkce/generate-state)
        url       (authcode/authorize-url {:authorization-endpoint authorization-endpoint
                                           :client-id client-id :redirect-uri redirect-uri
                                           :scopes scopes :code-challenge challenge :state csrf})]
    (when on-user-prompt
      (on-user-prompt {:authorize_uri url :scopes scopes :mode :loopback}))
    (open-browser-fn url)
    (let [cb (await-fn timeout-ms)]
      (cond
        (= cb ::timeout)
        (throw (ex-info "Timed out waiting for the browser redirect" {:flow :loopback}))

        (:error cb)
        (throw (ex-info (str "Authorization failed: " (:error cb)) {:flow :loopback}))

        (and (:state cb) (not= (:state cb) csrf))
        (throw (ex-info "OAuth state mismatch — possible CSRF" {:flow :loopback}))

        (str/blank? (:code cb))
        (throw (ex-info "No authorization code in the redirect" {:flow :loopback}))

        :else
        (do (mulog/info ::loopback-code-received)
            (authcode/exchange-code! {:token-endpoint token-endpoint :client-id client-id
                                      :code (:code cb) :code-verifier verifier
                                      :redirect-uri redirect-uri :post-fn post-fn}))))))
