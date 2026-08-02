;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.a2a-client.core.auth
  "Outbound credentials for A2A peers.

   Covers the schemes a client can satisfy with a static secret: HTTP
   bearer, HTTP basic, and API key (header or query). OAuth2 and OIDC are
   Phase 7 and route through `components/clj-oauth`; mTLS would need
   transport-level work and is not planned.

   ## Everything here must be redaction-safe

   An auth map holds a live secret. It gets attached to a peer record,
   which gets logged, inspected by `a2a$list`, and rendered into LLM
   context. `redact` is the only shape that may leave this namespace for
   any of those purposes — `describe` and `redact` never expose the
   secret, and the raw map should never be passed to `mulog` or returned
   from a command."
  (:require [clojure.string :as str])
  (:import [java.net URLEncoder]
           [java.nio.charset StandardCharsets]
           [java.util Base64]))

;; =============================================================================
;; Normalization
;; =============================================================================

(defn normalize
  "Coerce a user-supplied auth spec into a canonical map, or nil for none.

   Accepted shapes, in the order a human is likely to reach for them:

     nil / \"\"                       -> nil (anonymous)
     \"sk-abc\"                        -> {:type :bearer  :token \"sk-abc\"}
     {:token \"sk-abc\"}               -> {:type :bearer  :token \"sk-abc\"}
     {:type :bearer :token \"…\"}      -> as given
     {:type :basic :username \"u\" :password \"p\"}
     {:type :api-key :name \"X-Api-Key\" :value \"…\" :in \"header\"|\"query\"}

   The bare-string and `:token` forms exist because `a2a$connect` is
   called by an LLM from a code block, and demanding a fully-tagged map
   for the overwhelmingly common bearer case would just produce
   malformed calls. Unrecognized maps pass through untouched so a future
   scheme is not silently dropped."
  [auth]
  (cond
    (nil? auth) nil
    (string? auth) (when-not (str/blank? auth) {:type :bearer :token auth})
    (not (map? auth)) nil
    (:type auth) (update auth :type keyword)
    (:token auth) {:type :bearer :token (:token auth)}
    (and (:username auth) (:password auth)) (assoc auth :type :basic)
    (empty? auth) nil
    :else auth))

;; =============================================================================
;; Application
;; =============================================================================

(defn- basic-credential ^String [username password]
  (let [raw (str username ":" password)]
    (.encodeToString (Base64/getEncoder)
                     (.getBytes raw StandardCharsets/UTF_8))))

(defn headers
  "HTTP headers carrying `auth`. Empty map when there is nothing to send,
   or when the scheme applies to the URL instead (`:in \"query\"`)."
  [auth]
  (let [a (normalize auth)]
    (case (:type a)
      :bearer  (if-let [t (not-empty (str (:token a)))]
                 {"Authorization" (str "Bearer " t)}
                 {})
      :basic   {"Authorization" (str "Basic " (basic-credential (:username a)
                                                                (:password a)))}
      :api-key (if (= "query" (some-> (:in a) str str/lower-case))
                 {}
                 {(or (not-empty (str (:name a))) "X-API-Key") (str (:value a))})
      {})))

(defn apply-to-url
  "Append an API key to `url` when the scheme places it in the query
   string. Returns `url` unchanged for every other scheme.

   Kept separate from `headers` rather than folded into one 'apply auth'
   step because the URL is also used as the peer's identity (and as the
   call-chain token) — a credential must never end up baked into that."
  [url auth]
  (let [a (normalize auth)]
    (if (and (= :api-key (:type a))
             (= "query" (some-> (:in a) str str/lower-case)))
      ;; The ^String hints are load-bearing, not decoration: without them
      ;; `URLEncoder/encode` resolves REFLECTIVELY, and reflective interop
      ;; is a native-image break waiting to happen. `bb build:ata`'s
      ;; reflect:check ratchet catches exactly this — it caught this line.
      ;;
      ;; They must sit on the LOCAL BINDINGS, not on the argument forms:
      ;; `^String (or …)` is silently lost, because `or` is a macro and the
      ;; hint's metadata does not survive macroexpansion. Hinting the local
      ;; does survive, and is why this now compiles non-reflectively.
      (let [^String k-name (or (not-empty (str (:name a))) "api_key")
            ^String v-str  (str (:value a))
            k   (URLEncoder/encode k-name StandardCharsets/UTF_8)
            v   (URLEncoder/encode v-str StandardCharsets/UTF_8)
            sep (if (str/includes? url "?") "&" "?")]
        (str url sep k "=" v))
      url)))

;; =============================================================================
;; Redaction — the only shapes that may be logged or shown
;; =============================================================================

(defn redact
  "`auth` with every secret replaced by \"<redacted>\". Structure is
   preserved so a caller can still see which scheme is configured."
  [auth]
  (when-let [a (normalize auth)]
    (cond-> a
      (:token a)    (assoc :token "<redacted>")
      (:password a) (assoc :password "<redacted>")
      (:value a)    (assoc :value "<redacted>"))))

(defn describe
  "One-word description of the configured scheme, for display and logs.
   Never includes the secret."
  [auth]
  (if-let [a (normalize auth)]
    (case (:type a)
      :bearer  "bearer"
      :basic   "basic"
      :api-key (str "api-key(" (or (not-empty (str (:name a))) "X-API-Key") ")")
      (str (name (:type a))))
    "none"))

(defn configured?
  "True when `auth` carries usable credentials."
  [auth]
  (some? (normalize auth)))

;; =============================================================================
;; Card security schemes
;; =============================================================================

(defn card-requires-auth?
  "True when a card declares any security requirement.

   Absence is not a promise of anonymity — plenty of cards omit the field
   and still reject unauthenticated calls — so this is a hint for error
   messages, never a gate."
  [card]
  (boolean (or (seq (:security card))
               (seq (:securitySchemes card)))))

(defn missing-credentials-hint
  "A human-readable hint when a request fails with 401/403 against a card,
   or nil when we have no useful advice. Used to turn an opaque HTTP status
   into something an LLM can act on."
  [card auth]
  (when-not (configured? auth)
    (let [schemes (some->> (:securitySchemes card) vals (keep :type) distinct seq)]
      (str "peer requires authentication but no credentials were supplied"
           (when schemes
             (str " (declared schemes: " (str/join ", " schemes) ")"))))))
