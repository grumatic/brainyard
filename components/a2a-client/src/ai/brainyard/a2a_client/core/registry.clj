;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.a2a-client.core.registry
  "Named A2A peers — connect, look up, disconnect.

   A peer is a live handle: a fetched Agent Card plus a resolved endpoint
   and credentials. `connect!` is the only constructor, because a peer
   without a validated card is not usable — the card is what tells us the
   endpoint, the skills and whether streaming is available.

   Mirrors `acp-client/core/registry.clj`'s role (named backends) but not
   its shape: ACP backends are LAUNCH SPECS for subprocesses we start,
   whereas A2A peers are REMOTE SERVICES we merely address. There is
   nothing to spawn and nothing to reap; a peer is dropped by forgetting
   it."
  (:require [clojure.string :as str]
            [ai.brainyard.a2a.interface :as a2a]
            [ai.brainyard.a2a-client.core.auth :as auth]
            [ai.brainyard.a2a-client.core.client :as client]
            [ai.brainyard.a2a-client.core.discovery :as discovery]
            [ai.brainyard.mulog.interface :as mulog]))

(defonce ^:private !peers
  ;; peer-name (string) -> peer record from client/make-peer
  (atom {}))

(def peer-name-re
  "Peer names are used as tool-id segments (`a2a$<peer>$<skill>`), so they
   are constrained to what makes a legal, readable identifier. Rejecting a
   bad name here beats generating an unusable tool id later."
  #"^[a-z][a-z0-9-]*$")

;; =============================================================================
;; Registry access
;; =============================================================================

(defn get-peer
  "The live peer record for `name`, or nil."
  [name]
  (get @!peers (str name)))

(defn list-peers
  "All live peer records."
  []
  (vec (vals @!peers)))

(defn describe-peers
  "Redaction-safe summaries of every peer — safe for logs, `a2a$list` and
   LLM context."
  []
  (mapv client/describe-peer (list-peers)))

(defn reset-peers!
  "Forget every peer. For tests and session teardown."
  []
  (reset! !peers {}))

(defn register-peer!
  "Insert a prebuilt peer record. `connect!` is the normal entry point;
   this exists for tests and for callers that already hold a card."
  [peer]
  (swap! !peers assoc (:name peer) peer)
  (mulog/info ::peer-registered :name (:name peer) :endpoint (:endpoint peer))
  peer)

;; =============================================================================
;; Connect / disconnect
;; =============================================================================

(defn connect!
  "Discover, validate and register an A2A peer.

   Steps, each of which can fail with an ordinary `{:error …}`:
     1. validate the local name
     2. fetch + validate the Agent Card (version-negotiated)
     3. resolve a JSON-RPC endpoint from it
     4. build and register the peer record

   Returns `{:peer <redaction-safe summary> :card <card>}` or `{:error …}`.
   Re-connecting an existing name REPLACES it — that is how a peer's card
   is refreshed after it gains a skill."
  [{:keys [name url auth timeout-ms stream-timeout-ms refresh? dialect]}]
  (let [nm (some-> name str str/trim str/lower-case)]
    (cond
      (str/blank? (str url))
      {:error "url is required"}

      (or (str/blank? (str nm)) (not (re-matches peer-name-re nm)))
      {:error (str "peer :name must match " peer-name-re
                   " (lowercase letters, digits, hyphens; leading letter)"
                   " — it becomes part of the tool id a2a$<peer>$<skill>")}

      :else
      (let [{:keys [card error] :as res}
            (discovery/fetch-card! url :auth auth :timeout-ms timeout-ms
                                   :refresh? refresh?)]
        (if error
          res
          (let [{:keys [endpoint error] :as eres} (discovery/resolve-peer-endpoint card)]
            (if error
              eres
              (let [peer (client/make-peer {:name nm :url url :card card
                                            :auth auth :endpoint endpoint
                                            :timeout-ms timeout-ms
                                            :stream-timeout-ms stream-timeout-ms
                                            ;; nil means "infer from the card",
                                            ;; which is what :auto resolves to.
                                            :dialect dialect})]
                (register-peer! peer)
                {:peer (client/describe-peer peer)
                 :card card}))))))))

(defn disconnect!
  "Forget a peer. Returns `{:disconnected true :name …}`, or `{:error …}`
   when no such peer is registered.

   There is nothing to tear down — an A2A peer is a remote service, not a
   subprocess. Any in-flight SSE subscriptions are owned by whoever opened
   them and must be stopped through their own `:stop!` handle; forgetting
   the peer here does not cancel them, and pretending otherwise would be a
   lie about what this function does."
  [name]
  (let [nm (str name)]
    (if-not (contains? @!peers nm)
      {:error (str "no such A2A peer: " nm)}
      (do (swap! !peers dissoc nm)
          (mulog/info ::peer-disconnected :name nm)
          {:disconnected true :name nm}))))

;; =============================================================================
;; Config seeding
;; =============================================================================

(defn seed-peers!
  "Connect every peer in a config map `{peer-name {:url … :auth …}}`.

   Best-effort: one unreachable peer must not stop the others from
   connecting, so failures are collected and returned rather than thrown.
   Returns `{:connected [names] :failed {name error}}`."
  [peers-config]
  (reduce (fn [acc [nm spec]]
            (let [{:keys [error]} (connect! (assoc spec :name (name nm)))]
              (if error
                (do (mulog/warn ::peer-seed-failed :name (str nm) :error error)
                    (assoc-in acc [:failed (str nm)] error))
                (update acc :connected conj (str nm)))))
          {:connected [] :failed {}}
          peers-config))

;; =============================================================================
;; Skill resolution
;; =============================================================================

(defn resolve-skill
  "Resolve `peer-name` + `skill-id` to `{:peer … :skill … :agent-id …}`,
   or `{:error …}`.

   `:agent-id` is the URL-scoped call-chain token from
   `a2a/peer-agent-id` — the identity this remote skill carries in the
   cross-process cycle guard."
  [peer-name skill-id]
  (if-let [peer (get-peer peer-name)]
    (if-let [skill (a2a/find-skill (:card peer) skill-id)]
      {:peer     peer
       :skill    skill
       :agent-id (a2a/peer-agent-id (:card peer) skill-id)}
      {:error (str "peer '" peer-name "' exposes no skill '" skill-id "'"
                   " (has: " (str/join ", " (map :id (a2a/card-skills (:card peer)))) ")")})
    {:error (str "no such A2A peer: " peer-name)}))

(defn peer-auth-summary
  "Redaction-safe auth description for a peer, for display."
  [peer-name]
  (some-> (get-peer peer-name) :auth auth/describe))
