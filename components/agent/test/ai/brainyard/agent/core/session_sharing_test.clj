;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.core.session-sharing-test
  "`:share-parent-session` — the second kind of subagent.

   A subagent used to mean exactly one thing: dispatched by a BT to do a job,
   borrowing the parent's session to emit output, dying when it answers. ACP
   connections (acp$create) are the other kind — peers inside the user's own
   session — and they used to be modelled by simply having NO parent, which made
   them read as roots to persist (they clobbered the session's resume identity
   in meta.edn, patched by a per-defagent exception), to the reach fences, and
   to the session itself (create-agent fell through to a STANDALONE session atom
   carrying the same session-id).

   Now both kinds carry a parent, and `:share-parent-session` says which is
   which. These tests pin the three predicates and the four behaviours that
   branch on them."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [ai.brainyard.agent.core.agent :as agent]
            [ai.brainyard.agent.core.protocol :as proto]
            [ai.brainyard.agent.core.session :as session]))

;; Deliberately does NOT call hooks/reset-hooks!: the parent-close cascade is
;; registered once at namespace load (defonce), so clearing the hook table
;; would silently disarm the very behaviour the cascade test asserts.
(use-fixtures :each
  (fn [f]
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

(deftest cascade-collects-workers-but-spares-sharing-siblings
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
  (let [pred    (deref #'agent/root-agent-capture-event?)
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
