;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.a2a.core.card
  "Agent Card construction, parsing, endpoint resolution, and version
   negotiation.

   The Agent Card is A2A's discovery document: who an agent is, what
   skills it exposes, which transports it speaks, and how to authenticate.
   Both halves of the integration need it — the client parses a peer's
   card, the server generates its own from the local agent roster.

   ## Peer identity

   `peer-agent-id` defines what a remote agent is *called* inside
   brainyard: `<endpoint-url>#<skill-id>`. This is not cosmetic. It is the
   token that goes into the cross-process call chain
   (docs/design/a2a-design.md §4), so it has to be globally unique and
   stable. A bare skill id would not be — two unrelated peers can both
   expose a skill called `planner`, and collapsing them would make the
   cycle detector fire on the wrong pair (or, worse, not fire on the
   right one)."
  (:require [clojure.string :as str]
            [ai.brainyard.a2a.core.methods :as methods]
            [ai.brainyard.a2a.core.schema :as schema]
            [ai.brainyard.util.interface :as util]))

;; =============================================================================
;; Identifier coercion
;; =============================================================================

(defn- ->id-str
  "Coerce a skill/agent identifier to its wire string.

   Brainyard identifiers are KEYWORDS (`!tool-defs` is keyed by
   `:explore-agent`, `:a2a$peer$skill`, `:coact-agent/suffix`), and a bare
   `(str :explore-agent)` yields \":explore-agent\" — leading colon and all.
   That colon would end up in the public Agent Card and in every
   call-chain token. `util/kw->str` drops it while preserving a namespace
   (`:ns/foo` -> \"ns/foo\")."
  [x]
  (if (keyword? x) (util/kw->str x) (str x)))

;; =============================================================================
;; Well-known discovery URL
;; =============================================================================

(defn base-url
  "Normalize a peer base URL: trim, drop any trailing slash. Returns nil for
   blank input."
  [url]
  (let [u (some-> url str str/trim)]
    (when-not (str/blank? u)
      (str/replace u #"/+$" ""))))

(defn card-url
  "The well-known Agent Card URL for a peer base URL.

   Idempotent when handed a URL that already ends in the well-known path,
   so a user who pastes the card URL itself (which is what a browser shows
   them) gets the right thing rather than a doubled path."
  [url]
  (when-let [b (base-url url)]
    (if (str/ends-with? b methods/AGENT_CARD_PATH)
      b
      (str b methods/AGENT_CARD_PATH))))

;; =============================================================================
;; Parsing
;; =============================================================================

(defn parse
  "Validate a decoded Agent Card. Returns `{:card …}` or `{:error …}`.

   Never throws: a malformed card is an ordinary remote-peer failure, not
   an exceptional condition, and it has to surface as something an LLM can
   read."
  [card]
  (cond
    (not (map? card))
    {:error "Agent Card is not a JSON object"}

    (str/blank? (str (:name card)))
    {:error "Agent Card has no :name"}

    (not (schema/valid? schema/AgentCard card))
    {:error (str "Agent Card failed validation: "
                 (pr-str (schema/explain schema/AgentCard card)))}

    :else
    {:card card}))

(defn skills
  "Skills declared by a card (empty vector when it declares none)."
  [card]
  (vec (:skills card)))

(defn find-skill
  "Look up a skill by id. Returns the skill map or nil. Accepts a keyword
   id (brainyard's native form) as well as a string."
  [card skill-id]
  (let [want (->id-str skill-id)]
    (first (filter #(= want (->id-str (:id %))) (skills card)))))

;; =============================================================================
;; Transport / endpoint resolution
;; =============================================================================

(def ^:const JSONRPC_TRANSPORT "JSONRPC")

(defn- interface-entries
  "Every transport binding a card declares, across BOTH card generations.

   A2A v1.0 replaced the v0.3 endpoint fields wholesale, and real servers
   ship both shapes:

     v0.3  :url + :preferredTransport + :additionalInterfaces [{:url :transport}]
     v1.0  :supportedInterfaces [{:url :protocolBinding :protocolVersion}]

   A v1.0 card has NO top-level `:url` and NO `:preferredTransport` at all,
   so a reader that only knows v0.3 resolves no endpoint and reports the
   peer as offering no JSON-RPC binding — which is what brainyard did
   against the official a2a-sdk 1.1.0 sample until this existed."
  [card]
  (concat (:supportedInterfaces card) (:additionalInterfaces card)))

(defn- jsonrpc-interface-url
  "The URL of one interface entry when it is a JSON-RPC binding, else nil.
   Reads `:protocolBinding` (v1.0) or `:transport` (v0.3)."
  [{:keys [url protocolBinding transport]}]
  (let [binding' (or protocolBinding transport)]
    (when (= JSONRPC_TRANSPORT (some-> binding' str str/upper-case))
      (base-url url))))

(defn jsonrpc-endpoint
  "Resolve the JSON-RPC endpoint URL from a card, or nil when the peer does
   not offer one. Understands both the v0.3 and v1.0 card shapes — see
   `interface-entries`.

   Order:
   1. The v0.3 primary `:url`, when `:preferredTransport` is JSONRPC or
      absent (absent defaults to JSONRPC per spec, so omitting it is a
      positive statement rather than a missing one).
   2. Any JSON-RPC entry among `:supportedInterfaces` / `:additionalInterfaces`.
      This covers a v1.0 card, and also a gRPC-first v0.3 agent that is
      still reachable over JSON-RPC."
  [card]
  (let [preferred (some-> (:preferredTransport card) str str/upper-case)
        primary   (base-url (:url card))]
    (or (when (and primary (or (nil? preferred) (= JSONRPC_TRANSPORT preferred)))
          primary)
        (some jsonrpc-interface-url (interface-entries card)))))

(defn peer-agent-id
  "The identity of one remote skill, as used in the cross-process call
   chain: `<endpoint-url>#<skill-id>`.

   See the ns docstring for why this is URL-scoped rather than a bare
   skill id."
  [card skill-id]
  (str (or (jsonrpc-endpoint card) (base-url (:url card)) "a2a")
       "#" (->id-str skill-id)))

;; =============================================================================
;; Capabilities
;; =============================================================================

(defn supports?
  "True when the card advertises capability `k` (`:streaming`,
   `:pushNotifications`, `:stateTransitionHistory`).

   Absent means unsupported: A2A capability negotiation is opt-in, so
   anything a card does not claim must be treated as unavailable rather
   than assumed."
  [card k]
  (true? (get-in card [:capabilities k])))

(defn extended-card?
  "True when the card says an authenticated extended card is available."
  [card]
  (true? (:supportsAuthenticatedExtendedCard card)))

;; =============================================================================
;; Version negotiation
;; =============================================================================

(defn parse-version
  "Parse a `Major.Minor` (or `Major.Minor.Patch`) version string into
   `[major minor]`. Returns nil when unparseable."
  [v]
  (when-let [s (some-> v str str/trim not-empty)]
    (let [[maj min'] (str/split s #"\.")
          ->int      (fn [x] (try (Integer/parseInt (str x)) (catch Exception _ nil)))
          maj'       (->int maj)]
      (when maj'
        [maj' (or (->int min') 0)]))))

(defn compatible?
  "True when `their-version` can serve requests written against
   `our-version` (defaults to our `PROTOCOL_VERSION`).

   Compatibility is by **major** version. Minor versions are additive by
   the spec's own rule — a peer on a newer minor must still honour an
   older minor's requests — so a minor gap is fine in either direction.
   An unparseable or absent peer version is treated as compatible: the
   field is optional, plenty of live cards omit it, and refusing to talk
   to them would be stricter than the protocol."
  ([their-version] (compatible? their-version methods/PROTOCOL_VERSION))
  ([their-version our-version]
   (let [theirs (parse-version their-version)
         ours   (parse-version our-version)]
     (cond
       (nil? theirs) true
       (nil? ours)   true
       :else         (= (first theirs) (first ours))))))

(defn version-error
  "Nil when the card's `:protocolVersion` is compatible with ours, else an
   `{:error …}` map explaining the mismatch."
  [card]
  (let [theirs (:protocolVersion card)]
    (when-not (compatible? theirs)
      {:error (format "A2A protocol version mismatch: peer speaks %s, we speak %s"
                      (pr-str theirs) methods/PROTOCOL_VERSION)})))

;; =============================================================================
;; Construction (used by the server half to publish our own card)
;; =============================================================================

(defn skill
  "Build an AgentSkill map. `:id` and `:name` are required; the rest are
   omitted when nil rather than emitted as nulls, because a card is a
   public document and empty keys read as broken."
  [{:keys [id name description tags examples input-modes output-modes]}]
  (cond-> {:id (->id-str id) :name (str name)}
    description             (assoc :description (str description))
    (seq tags)              (assoc :tags (vec tags))
    (seq examples)          (assoc :examples (vec examples))
    (seq input-modes)       (assoc :inputModes (vec input-modes))
    (seq output-modes)      (assoc :outputModes (vec output-modes))))

(defn build
  "Build an Agent Card for this agent, advertising BOTH generations.

   The v0.3 fields (`:url` + `:preferredTransport` + `:protocolVersion`)
   and the v1.0 field (`:supportedInterfaces`) are DIFFERENT fields, so one
   card satisfies both without negotiation and without a second endpoint —
   a v0.3 reader sees what it expects and ignores the rest, and a v1.0
   reader does the same. That is most of why serving both dialects is
   nearly free, and it is why a v1.0 client can discover us at all."
  [{:keys [name description url version provider capabilities skills
           default-input-modes default-output-modes
           security-schemes security extended-card?]}]
  (cond-> {:name            (str name)
           :protocolVersion methods/PROTOCOL_VERSION
           :preferredTransport JSONRPC_TRANSPORT
           :capabilities    (or capabilities {})
           :skills          (vec skills)}
    url (assoc :supportedInterfaces
               [{:url (base-url url)
                 :protocolBinding JSONRPC_TRANSPORT
                 :protocolVersion "1.0"}
                {:url (base-url url)
                 :protocolBinding JSONRPC_TRANSPORT
                 :protocolVersion methods/PROTOCOL_VERSION}])
    description          (assoc :description (str description))
    url                  (assoc :url (base-url url))
    version              (assoc :version (str version))
    provider             (assoc :provider provider)
    (seq default-input-modes)  (assoc :defaultInputModes (vec default-input-modes))
    (seq default-output-modes) (assoc :defaultOutputModes (vec default-output-modes))
    (seq security-schemes)     (assoc :securitySchemes security-schemes)
    (seq security)             (assoc :security (vec security))
    extended-card?             (assoc :supportsAuthenticatedExtendedCard true)))
