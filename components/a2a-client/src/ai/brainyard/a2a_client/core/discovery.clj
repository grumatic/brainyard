;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.a2a-client.core.discovery
  "Agent Card discovery: fetch `/.well-known/agent-card.json`, validate it,
   and cache the result.

   The card is the peer's contract — its skills, transports, capabilities
   and security schemes — so it is fetched once at connect and re-read
   rarely. It is cached with a TTL rather than forever because a peer can
   legitimately gain or lose a skill between our calls, and pinning a stale
   card would make us call a skill that no longer exists.

   ## Validation is not optional

   A card that fails validation is rejected here rather than being carried
   forward as a half-usable map. Everything downstream — skill
   registration, endpoint resolution, the call-chain token — reads fields
   off this map, and a malformed card would otherwise surface as a
   confusing failure several layers away from its cause."
  (:require [ai.brainyard.a2a.interface :as a2a]
            [ai.brainyard.a2a-client.core.transport :as transport]
            [ai.brainyard.mulog.interface :as mulog]))

(def ^:const DEFAULT_TTL_MS 300000) ;; 5 minutes

(defonce ^:private !cache
  ;; card-url -> {:card <map> :at <epoch ms>}
  (atom {}))

(defn- now-ms ^long [] (System/currentTimeMillis))

(defn invalidate!
  "Drop a cached card (all of them when called with no args). Call after a
   peer reports a skill we did not know about — the card has moved on."
  ([] (reset! !cache {}))
  ([url] (swap! !cache dissoc (a2a/card-url url))))

(defn cached
  "The cached card for `url` when present and within `ttl-ms`, else nil."
  ([url] (cached url DEFAULT_TTL_MS))
  ([url ttl-ms]
   (when-let [{:keys [card at]} (get @!cache (a2a/card-url url))]
     (when (< (- (now-ms) at) (or ttl-ms DEFAULT_TTL_MS))
       card))))

(defn fetch-card!
  "Fetch and validate the public Agent Card for a peer base URL.

   Returns `{:card …}` or `{:error …}`. Honours the cache unless
   `:refresh? true`. Never throws."
  [url & {:keys [auth timeout-ms ttl-ms refresh?]}]
  (let [card-url (a2a/card-url url)]
    (cond
      (nil? card-url)
      {:error "A2A peer URL is blank"}

      (and (not refresh?) (cached url ttl-ms))
      {:card (cached url ttl-ms) :cached true}

      :else
      (let [{:keys [result error] :as res}
            (transport/get-json! card-url {:auth auth :timeout-ms timeout-ms})]
        (if error
          res
          (let [parsed (a2a/parse-card result)]
            (if (:error parsed)
              parsed
              (let [card (:card parsed)]
                (if-let [verr (a2a/card-version-error card)]
                  verr
                  (do
                    (swap! !cache assoc card-url {:card card :at (now-ms)})
                    (mulog/info ::card-fetched
                                :url card-url
                                :name (:name card)
                                :skills (count (a2a/card-skills card))
                                :streaming (a2a/card-supports? card :streaming))
                    {:card card}))))))))))

(defn fetch-extended-card!
  "Fetch the AUTHENTICATED extended Agent Card, which may expose skills the
   public card withholds.

   Returns `{:card …}` or `{:error …}`. Refuses up front when the public
   card does not advertise the capability — calling anyway would earn an
   `ExtendedAgentCardNotConfiguredError` and cost a round trip to learn
   what the card already told us."
  [peer]
  (let [card (:card peer)]
    (cond
      (nil? card)
      {:error "no public Agent Card — fetch it before requesting the extended card"}

      (not (a2a/extended-card? card))
      {:error "peer does not advertise an authenticated extended card"}

      :else
      (let [{:keys [result error] :as res}
            (transport/rpc! peer :agent-extended-card nil)]
        (if error
          res
          (let [parsed (a2a/parse-card result)]
            (if (:error parsed)
              parsed
              {:card (:card parsed)})))))))

(defn resolve-peer-endpoint
  "The JSON-RPC endpoint for a card, or an `{:error …}` explaining that the
   peer offers no JSON-RPC binding.

   A gRPC-only peer is a legitimate A2A agent that we simply cannot reach;
   saying so plainly beats a confusing connection failure later."
  [card]
  (if-let [ep (a2a/jsonrpc-endpoint card)]
    {:endpoint ep}
    {:error (str "peer '" (:name card) "' exposes no JSON-RPC binding"
                 " (preferredTransport "
                 (pr-str (:preferredTransport card))
                 "); brainyard speaks JSON-RPC only")}))
