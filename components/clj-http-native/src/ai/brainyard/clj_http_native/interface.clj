;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.clj-http-native.interface
  "Minimal HTTP client built on `java.net.http` (JDK 11+), exposing a
   clj-http-compatible surface for the call sites in clj-llm and agent.

   Why this exists: cognitect.aws-api already drags `java.net.http` into
   the native-image binary (via cognitect.aws.http.java), so the Apache
   HttpComponents stack that clj-http bundles is pure duplicate weight.
   Replacing clj-http with this wrapper saves ~5 MB of native binary
   size and removes the NTLM `<clinit>` + Apache SSL/Hostname reflection
   surface that today's native-image build has to special-case. See
   docs/design/native-image-design.md §6.

   The wrapper supports only the option subset the existing call sites
   actually use:

       (post url {:headers H        ;; map<str,str>
                  :body    B        ;; String, byte[], or nil
                  :as      :string  ;; or :reader, :stream
                  :throw-exceptions true|false
                  :timeout-ms          60000
                  :connect-timeout-ms  10000
                  :content-type :json  ;; sugar: sets Content-Type header
                  :proxy-host \"...\" :proxy-port 8080
                  :insecure? true})

   Returns {:status int :headers {lower-str -> str} :body T}
   where T depends on :as.

   On non-2xx with :throw-exceptions true, throws ex-info with the same
   {:status :headers :body} keys in ex-data — matching clj-http's shape
   so that retry-with-backoff (llm.clj:156) keeps working without
   changes.

   Options NOT supported (call sites don't use them):
     - cookie store, automatic redirect to non-HTTPS, NTLM/digest auth,
       multipart bodies (`:multipart`), connection manager (just ignored),
       socket-timeout vs connection-timeout split (collapsed into one
       :timeout-ms + :connect-timeout-ms pair).

   For migration ergonomics, unknown keys are silently ignored — clj-http
   call sites that pass `:connection-manager`, `:socket-timeout` etc. can
   keep doing so during incremental migration."
  (:refer-clojure :exclude [get])
  (:require [ai.brainyard.clj-http-native.core.client :as core]))

(defn post
  "POST `url`. See ns docstring for opts."
  [url opts]
  (core/request :POST url opts))

(defn get*
  "GET `url`. See ns docstring for opts.
   Named `get*` to avoid shadowing clojure.core/get when imported with :refer.
   Callers using `:as alias` should still spell it `alias/get` — see the
   `get` re-export below."
  [url opts]
  (core/request :GET url opts))

;; `clojure.core/get` is a fundamental function; we cannot shadow it via
;; `defn get` in a public namespace without breaking every consumer that
;; uses `clojure.core/get` after a `:refer :all`. Instead we expose the
;; alias-friendly `get` via def, which is the form `http/get` resolves to
;; when callers do `(:require [...interface :as http])`.
(def get get*)

(defn delete
  "DELETE `url`. See ns docstring for opts."
  [url opts]
  (core/request :DELETE url opts))

(defn put
  "PUT `url`. See ns docstring for opts."
  [url opts]
  (core/request :PUT url opts))
