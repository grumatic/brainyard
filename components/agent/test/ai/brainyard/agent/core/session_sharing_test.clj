;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.core.session-sharing-test
  "`:share-parent-session` — the second kind of subagent.

   A subagent used to mean exactly one thing: dispatched by a BT to do a job,
   borrowing the parent's session to emit output, dying when it answers. ACP
   connections (acp$create) are the other kind — subagents that run inside the user's own
   session — and they used to be modelled by simply having NO parent, which made
   them read as roots to persist (they clobbered the session's resume identity
   in meta.edn, patched by a per-defagent exception), to the reach fences, and
   to the session itself (create-agent fell through to a STANDALONE session atom
   carrying the same session-id).

   Now both kinds carry a parent, and `:share-parent-session` says which is
   which. Both are SUBAGENTS: a session has exactly one root (no parent), and
   sharing describes whose turn it is, not who owns the session. These tests pin
   the predicates and the behaviours that branch on them."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [ai.brainyard.agent.core.agent :as agent]
            [ai.brainyard.agent.core.protocol :as proto]
            [ai.brainyard.agent.core.session :as session]
            [ai.brainyard.agent.core.runtime :as runtime]
            [ai.brainyard.agent.core.hooks :as hooks]
            [ai.brainyard.agent.core.feature :as feature]))

;; Deliberately does NOT call hooks/reset-hooks!: clearing the hook table would
;; disarm the very behaviour the cascade test asserts. Not resetting is only
;; half the defence though — ~24 OTHER test namespaces call `reset-hooks!`, and
;; whichever runs first wipes the cascade for the rest of the JVM. So re-arm it
;; here: `agent/register-hooks!` is idempotent (register-hook! dedupes by
;; [event-key handler-id]), which keeps this namespace order-independent instead
;; of passing alone and failing in the full suite.
(use-fixtures :each
  (fn [f]
    (agent/register-hooks!)
    (agent/reset-agent-registry!)
    (try (f)
         (finally (agent/reset-agent-registry!)))))

(defn- live?
  "Is this instance still in the registry? Returns the id (or nil) rather than
   the record — an Agent holds its parent, so a failing `is` that printed the
   record would recurse until the stack blew."
  [ag]
  (some-> (agent/get-agent (proto/agent-id ag)) proto/agent-id))

(defn- mk
  "Create a throwaway agent. `opts` may carry :parent-agent /
   :share-parent-session."
  [id & {:as opts}]
  (apply agent/setup-agent
         (mapcat identity
                 (merge {:id            id
                         :agent-session {:user-id "u" :session-id "s"}
                         :memory-opts   {}}
                        opts))))

;; ============================================================================
;; The three predicates
;; ============================================================================

(deftest predicates-partition-the-three-kinds
  (let [root    (mk :ss-root)
        worker  (mk :ss-worker  :parent-agent root)
        sibling (mk :ss-sibling :parent-agent root :share-parent-session true)]
    (try
      (testing "root — no parent, no flag"
        (is (not (agent/subagent? root)))
        (is (not (agent/share-parent-session? root)))
        (is (not (agent/dispatched-subagent? root))))

      (testing "dispatched worker — a parent, flag defaults false"
        (is (agent/subagent? worker))
        (is (not (agent/share-parent-session? worker))
            "default is false: a subagent is a worker unless it says otherwise")
        (is (agent/dispatched-subagent? worker)))

      (testing "session-sharing sibling — a parent AND the flag"
        (is (agent/subagent? sibling) "it is still owned")
        (is (agent/share-parent-session? sibling))
        (is (not (agent/dispatched-subagent? sibling))))

      (testing "both kinds record their creator as :owner"
        (is (= :ss-root (:owner (agent/lifecycle worker))))
        (is (= :ss-root (:owner (agent/lifecycle sibling)))))
      (finally (run! #(.close %) [sibling worker root])))))

;; ============================================================================
;; Session identity — the bug the parent link closes
;; ============================================================================

(deftest sharing-sibling-inherits-the-parents-session-atom
  (let [root    (mk :ss2-root)
        sibling (mk :ss2-sibling :parent-agent root :share-parent-session true)]
    (try
      (is (identical? (:!session root) (:!session sibling))
          "same atom, not merely the same session-id — without a :parent-agent
           create-agent built a standalone atom and the usage tracker,
           :total-turns audit index and session config silently diverged")
      (testing ":total-turns stays monotonic across both (it is the memory_audit row index)"
        (let [a (session/inc-total-turns! (:!session root))
              b (session/inc-total-turns! (:!session sibling))]
          (is (= (inc a) b))))
      (finally (run! #(.close %) [sibling root])))))

;; ============================================================================
;; Dispatch cap / LRU eviction
;; ============================================================================

(deftest sharing-siblings-are-outside-the-dispatch-cap
  (let [root    (mk :ss3-root)
        worker  (mk :ss3-worker  :parent-agent root)
        sibling (mk :ss3-sibling :parent-agent root :share-parent-session true)
        sid     (proto/session-id root)]
    (try
      (is (= 1 (agent/count-subagents sid))
          "only the dispatched worker counts — the sibling has its own cap")
      (testing "the sibling is never the LRU eviction victim"
        (let [victim (agent/lru-subagent sid)]
          (is (= (proto/agent-id worker) (some-> victim proto/agent-id))
              "an idle ACP connection is idle because nobody has asked it yet,
               not because it is stale")))
      (finally (run! #(.close %) [sibling worker root])))))

;; ============================================================================
;; Parent-close cascade
;; ============================================================================

(deftest cascade-collects-workers-but-spares-session-sharing-subagents
  (let [root    (mk :ss4-root)
        worker  (mk :ss4-worker  :parent-agent root)
        sibling (mk :ss4-sibling :parent-agent root :share-parent-session true)]
    (try
      (.close root)
      (is (nil? (live? worker))
          "a dispatched worker dies with the agent that dispatched it")
      (is (= (proto/agent-id sibling) (live? sibling))
          "a session-sharing sibling belongs to the user's session, not to the
           caller's task, and outlives whoever opened it")
      (finally (run! #(try (.close %) (catch Exception _)) [sibling worker])))))

;; ============================================================================
;; L2 capture scope
;; ============================================================================

(deftest capture-keeps-user-facing-turns-only
  (let [pred    (deref #'agent/agent-capture-event?)
        root    (mk :ss5-root)
        worker  (mk :ss5-worker  :parent-agent root)
        sibling (mk :ss5-sibling :parent-agent root :share-parent-session true)]
    (try
      (is (pred {:agent root})    "the root's Q&A is the user's Q&A")
      (is (pred {:agent sibling}) "so is a sharing sibling's — a second model in
                                   the same conversation, not a sub-task")
      (is (not (pred {:agent worker}))
          "a dispatched worker's ask/post is operational detail")
      (is (pred {}) "a missing agent still passes (defensive)")
      (finally (run! #(.close %) [sibling worker root])))))

(deftest hierarchy-axes-stay-separate
  ;; ONE root per session; everything else is a subagent. `:share-parent-session`
  ;; is a property OF a subagent (axis 2 — "are its turns the user's?"), NEVER a
  ;; promotion toward root (axis 1 — "is this THE session's agent?").
  ;;
  ;; Collapsing the two is a live trap: an earlier pass widened the root-only
  ;; gates to admit sharing subagents, which would have had the acp-agent and the
  ;; root BOTH advancing the same session's consolidation cadence — the exact
  ;; double-count those gates exist to prevent. This pins the split so the next
  ;; person adding a gate picks the right axis instead of re-deriving it.
  (let [root      (mk :ss6-root)
        worker    (mk :ss6-worker  :parent-agent root)
        shared    (mk :ss6-shared  :parent-agent root :share-parent-session true)
        root-m    (hooks/match-root-agent)
        user-m    (hooks/match-user-turn-agent)]
    (try
      (testing "axis 1 — root?: the session singleton, and ONLY it"
        (is (runtime/root-state? (:!state root)))
        (is (not (runtime/root-state? (:!state worker))))
        (is (not (runtime/root-state? (:!state shared)))
            "a session-sharing subagent is still a SUBAGENT — it has a parent"))

      (testing "axis 2 — is the user talking?: root + sharing subagent"
        (is (not (runtime/dispatched-subagent-state? (:!state root))))
        (is (runtime/dispatched-subagent-state? (:!state worker))
            "a dispatched worker's ask is operational detail")
        (is (not (runtime/dispatched-subagent-state? (:!state shared)))
            "an acp-agent IS the user, addressing a second model"))

      (testing "the two hook matchers follow their own axis"
        (is (boolean (root-m {:agent root})))
        (is (not (boolean (root-m {:agent worker}))))
        (is (not (boolean (root-m {:agent shared})))
            "match-root-agent is strict — singletons must not double-fire")
        (is (boolean (user-m {:agent root})))
        (is (not (boolean (user-m {:agent worker}))))
        (is (boolean (user-m {:agent shared}))
            "match-user-turn-agent admits the sharing subagent"))

      (testing ":root-only features gate on axis 1 — the root alone"
        (is (feature/on? root :memory/consolidation))
        (is (not (feature/on? worker :memory/consolidation)))
        (is (not (feature/on? shared :memory/consolidation))
            "else the acp-agent and the root both advance one session's cadence"))

      (testing "L2 capture gates on axis 2 — content, not cadence"
        (let [pred (deref #'agent/agent-capture-event?)]
          (is (pred {:agent root}))
          (is (not (pred {:agent worker})))
          (is (pred {:agent shared})
              "a sharing subagent drives no singleton, but its turns are
               still the user's and worth remembering")))

      (testing "an unreadable state never withholds a capability"
        (is (runtime/root-state? (atom :not-a-state-map)))
        (is (not (runtime/dispatched-subagent-state? (atom :not-a-state-map)))))

      (testing "event-provenance answers both axes as data, for consumers off-process"
        ;; The matchers are for a handler in THIS process. A console on the far
        ;; side of ask.sock cannot hold a predicate, and `:op :subscribe` strips
        ;; the `:agent` before forwarding — so the same two answers have to
        ;; travel as data or the frame is anonymous.
        (is (= {:root? true :user-turn? true} (hooks/event-provenance {:agent root})))
        (is (= {:root? false :user-turn? false} (hooks/event-provenance {:agent worker})))
        (is (= {:root? false :user-turn? true} (hooks/event-provenance {:agent shared}))
            "the sharing subagent is the pair the two axes disagree about — and
             the reason a single :root? flag would not have been enough")
        (testing "and reads the same agent key the matchers do"
          (is (= (hooks/event-provenance {:agent shared})
                 (hooks/event-provenance {:stage-agent shared}))))
        (testing "nil for an agentless event, rather than a default"
          ;; Process-level events have no agent; flags defaulted to false would
          ;; read as \"a subagent did this\", which is a different claim.
          (is (nil? (hooks/event-provenance {:session-id "s"})))))
      (finally (run! #(.close %) [shared worker root])))))

;; ============================================================================
;; The invariant: ONE root per agent-session
;; ============================================================================

(deftest exactly-one-root-per-agent-session
  ;; The load-bearing rule the whole hierarchy rests on. Everything created into
  ;; an EXISTING agent-session carries a parent — dispatched worker or
  ;; session-sharing subagent — so `list-agents-for-session` has exactly one
  ;; parentless instance. A second root in one session would give two drivers
  ;; for every per-session singleton and an ambiguous persisted resume identity.
  ;;
  ;; NB the TUI's `/agent new` does NOT violate this: it mints a fresh
  ;; agent-session-id (`agt-…`) per instance, so each is the root of its OWN
  ;; session rather than a second root in the caller's.
  (let [root    (mk :ss7-root)
        worker  (mk :ss7-worker  :parent-agent root)
        shared  (mk :ss7-shared  :parent-agent root :share-parent-session true)
        sid     (proto/session-id root)
        roots   (->> (agent/list-agents-for-session sid)
                     (remove agent/subagent?))]
    (try
      (is (= 1 (count roots))
          (str "expected exactly one parentless agent in session " sid
               ", got " (mapv proto/agent-id roots)))
      (is (= (proto/agent-id root) (proto/agent-id (first roots))))
      (testing "both other kinds are subagents of that root"
        (is (agent/subagent? worker))
        (is (agent/subagent? shared)
            "session-sharing does not exempt an instance from being a subagent")
        (is (= (proto/agent-id root) (:owner (agent/lifecycle worker))))
        (is (= (proto/agent-id root) (:owner (agent/lifecycle shared)))))
      (testing "all three share the one agent-session"
        (is (= sid (proto/session-id worker)))
        (is (= sid (proto/session-id shared))))
      (finally (run! #(.close %) [shared worker root])))))
