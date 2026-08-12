;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.a2a.core.chain
  "The cross-process call chain — brainyard's A2A extension for cycle and
   depth detection.

   ## Why this exists

   In-process, brainyard detects agent-to-agent cycles with a DYNAMIC
   BINDING: `proto/*call-chain*`, a vector of agent-ids conj'd on each hop
   and checked before dispatch (`agent/core/agent.clj`). A dynamic binding
   does not cross a socket. Point two brainyard instances at each other and
   `A -> B -> A -> B …` recurses until something times out — and because
   every hop is a real LLM turn, it burns tokens the whole way down.

   So the chain travels on the wire, in `Message.metadata`:

       {\"ai.brainyard/call-chain\" [\"by-node:5f2c…\" \"by-node:9a11…\"]
        \"ai.brainyard/call-depth\" 2
        \"ai.brainyard/context-id\" \"agt-0193…\"}

   The keys are NAMESPACED precisely because A2A `metadata` is a
   free-for-all map shared with every other extension; a bare
   \"call-chain\" would be asking for a collision.

   ## The chain holds NODE ids, and the CALLER appends itself

   Two decisions that took a wrong turn first, so they are spelled out.

   **1. Entries are node ids, not agent or skill ids.** A brainyard
   instance has two unrelated names for itself: locally it is
   `main-agent/lime-mole-8966`, and to a remote peer it is
   `https://a.example/a2a#main`. If the chain carried those, `A -> B -> A`
   would compare `\"main-agent/lime-mole-8966\"` against
   `\"https://a.example/a2a#main\"`, never match, and the guard would
   silently never fire — the worst outcome for a safety check, because it
   looks like it is working. So every node stamps ONE stable identity
   (`node-id`) that it uses both when calling out and when checking
   inbound requests. It is process-scoped and URL-independent, so it
   survives proxies, multiple interfaces and a peer being reachable at
   more than one address.

   **2. The CALLER appends itself; the callee only checks membership.**
   The tempting alternative — append the callee — puts the receiver's own
   id in the chain it receives, so a membership check would refuse the
   very first hop. With the caller appending, the invariant is clean:

       chain = every node ALREADY on the stack, most recent last
       cycle  = my node id is in that list

   Worked example, A calling B calling back to A:

       A stamps  [nodeA]                  -> B sees nodeB not in chain: OK
       B stamps  [nodeA nodeB]            -> A sees nodeA IS in chain: REFUSED

   ## Granularity is deliberately coarse

   Cycles are detected per NODE, not per skill. `A#planner -> B ->
   A#reviewer` is refused even though the two skills differ. That is a
   conscious trade: the failure it prevents (unbounded paid recursion)
   is far more expensive than the pattern it forbids (a callback into a
   different skill on the calling node), and node granularity is the only
   level at which the identity is unambiguous. Depth remains the finer
   control for legitimate deep chains.

   ## Trust boundary

   A remote caller controls this metadata completely. It can understate its
   depth, omit the chain, or forge one. So this is a **cooperation protocol
   between well-behaved peers, not a security control** — it stops
   accidental recursion between cooperating agents, which is the actual
   failure mode. It does NOT stop a hostile caller, and nothing here should
   be relied on as if it did; that job belongs to authentication, the skill
   allow-list, and the OS sandbox (docs/design/a2a-design.md §8)."
  (:require [clojure.string :as str])
  (:import [java.util UUID]))

;; =============================================================================
;; Node identity
;; =============================================================================

(defonce ^:private !node-id
  ;; One stable identity per process, used BOTH when stamping outbound calls
  ;; and when checking inbound ones. It must be the same token in both
  ;; directions or the guard cannot fire — see the ns docstring.
  ;;
  ;; Holds nil until first use. The id is minted in `node-id`, NOT here:
  ;; see the comment there.
  (atom nil))

(defn node-id
  "This process's stable A2A node identity, minted on first use.

   A random UUID rather than the served URL: a node may serve on several
   addresses, sit behind a proxy, or not serve at all, and any of those
   would make a URL-derived id disagree with itself.

   Minted LAZILY rather than in the `defonce` initializer above, because
   under GraalVM native-image a `defonce` runs at BUILD time and its value
   is baked into the image heap as a constant. An eagerly-generated UUID
   would therefore be the SAME for every process launched from one binary
   — and two nodes sharing an id refuse each other's calls on the first
   hop (`cycle?` sees its own id in the chain the caller stamped), which
   makes brainyard-to-brainyard A2A impossible rather than merely
   mis-detected. Lazy init is what keeps this per-process under both the
   JVM and native-image.

   No unit test can catch a regression here — the defect would be created
   by the compiler, not the code, so this namespace's tests pass either
   way. The guard lives in the build: `bb smoke:ata` (bin/smoke-native.sh)
   asserts the image contains no `by-node:` followed by a UUID."
  []
  ;; `(or % …)` keeps the swap idempotent under CAS retry, so two threads
  ;; racing on first use cannot end up with different ids.
  (or @!node-id
      (swap! !node-id #(or % (str "by-node:" (UUID/randomUUID))))))

(defn set-node-id!
  "Override the node id. For tests, and for an operator who wants a
   deterministic identity across restarts (two processes sharing an id
   would refuse each other's calls, so this is not a knob to reach for
   casually)."
  [id]
  (reset! !node-id (str id)))

;; =============================================================================
;; Keys
;;
;; Each key is read under BOTH its string form and its keyword form. JSON
;; arrives through `a2a/decode`, which keywordizes with `:key-fn keyword` —
;; so the wire string "ai.brainyard/call-chain" becomes the namespaced
;; keyword :ai.brainyard/call-chain by the time a handler sees it. Writing
;; strings and reading only strings would silently never match, which is
;; the same class of bug as the identity mismatch above.
;; =============================================================================

(def ^:const CHAIN_KEY "ai.brainyard/call-chain")
(def ^:const DEPTH_KEY "ai.brainyard/call-depth")
(def ^:const CONTEXT_KEY "ai.brainyard/context-id")

(defn- read-key
  "Read `k` from `metadata` under either its string or keyword form."
  [metadata k]
  (when (map? metadata)
    (let [v (get metadata k)]
      (if (some? v) v (get metadata (keyword k))))))

;; =============================================================================
;; Outbound
;; =============================================================================

(defn stamp
  "Build the metadata map for an outbound request.

   Appends THIS node's id to `chain` (see the ns docstring for why the
   caller appends itself rather than the callee). `chain` is the chain we
   received, or `[]` when this call originates locally.

   `:self-id` overrides the process node id — tests only. `:depth` is the
   caller's current depth; the stamped value is one greater."
  [{:keys [chain depth context-id self-id]}]
  (let [self (str (or self-id (node-id)))
        prior (mapv str (or chain []))]
    (cond-> {CHAIN_KEY (conj prior self)
             DEPTH_KEY (inc (or depth 0))}
      (not (str/blank? (str context-id))) (assoc CONTEXT_KEY (str context-id)))))

;; =============================================================================
;; Inbound
;; =============================================================================

(defn read-chain
  "The call chain from inbound metadata, as a vector of strings. `[]` when
   absent — a non-brainyard caller sends none."
  [metadata]
  (let [v (read-key metadata CHAIN_KEY)]
    (if (sequential? v) (mapv str v) [])))

(defn read-depth
  "The call depth from inbound metadata. 0 when absent or unparseable.

   A malformed depth reads as 0 rather than throwing: it comes from a
   remote peer, and a bad value must not be able to crash a handler."
  [metadata]
  (let [v (read-key metadata DEPTH_KEY)]
    (cond
      (integer? v) (max 0 v)
      (string? v)  (try (max 0 (Integer/parseInt ^String v)) (catch Exception _ 0))
      :else        0)))

(defn read-context-id
  "The caller's context id from inbound metadata, or nil."
  [metadata]
  (let [v (read-key metadata CONTEXT_KEY)]
    (when-not (str/blank? (str v)) (str v))))

(defn cycle?
  "True when `self` (default: this node) already appears in `chain` — i.e.
   servicing this request would re-enter a node already on the stack."
  ([chain] (cycle? chain (node-id)))
  ([chain self]
   (let [s (str self)]
     (boolean (some #(= s (str %)) chain)))))

(defn check
  "Gate an inbound request. Returns nil when it may proceed, else an
   `{:error … :reason …}` map.

   `:reason` is `:cycle` or `:depth`, so a handler can map each to the
   right protocol error without matching on prose.

   Call this BEFORE doing any work: the whole point is to refuse before
   spending an LLM turn."
  [{:keys [metadata self-id max-depth]}]
  (let [chain (read-chain metadata)
        depth (read-depth metadata)
        self  (str (or self-id (node-id)))
        limit (or max-depth 3)]
    (cond
      (cycle? chain self)
      {:reason :cycle
       :error  (str "A2A call cycle refused: this node (" self
                    ") already appears in the call chain "
                    (pr-str chain)
                    ". Servicing it would recurse.")}

      (>= depth limit)
      {:reason :depth
       :error  (str "A2A call depth limit reached (" depth " >= " limit
                    "); refusing to extend the chain " (pr-str chain) ".")}

      :else nil)))

(defn inbound-chain
  "The chain to bind locally while servicing an inbound request: what we
   received, unchanged.

   We do NOT append ourselves here — the next outbound `stamp` does that.
   Appending in both places would double-count and make a two-hop chain
   look like four."
  [metadata]
  (read-chain metadata))

(defn describe
  "Human-readable one-liner for logs and errors."
  [metadata]
  (let [chain (read-chain metadata)]
    (str "depth=" (read-depth metadata)
         " chain=" (if (seq chain) (str/join " -> " chain) "<none>"))))
