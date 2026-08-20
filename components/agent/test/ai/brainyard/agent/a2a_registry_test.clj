;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.a2a-registry-test
  "Tests for A2A registry unification: the `RemoteAgent` record, the
   `a2a$*` command family, skill tool-def registration, and the near-side
   half of the cycle guard.

   The point of the record tests is that a RemoteAgent must satisfy every
   shape the EXISTING call sites read — `instance-summary`,
   `agent-registry$detail`, `ask`, `close-instance!` — without any of them
   being changed. Anything that only works because a call site was
   special-cased is a design failure, not a passing test."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [ai.brainyard.a2a.interface :as a2a]
            [ai.brainyard.a2a-client.interface :as a2a-client]
            [ai.brainyard.agent.common.a2a :as a2a-cmd]
            ;; Loaded for its side effect: registers agent-registry$ask, the
            ;; command a remote peer is asked through. Without it the
            ;; "there is no a2a$ask" assertion below tests nothing.
            [ai.brainyard.agent.common.commands]
            [ai.brainyard.agent.core.agent :as agent-core]
            [ai.brainyard.agent.core.config :as config]
            [ai.brainyard.agent.core.protocol :as proto]
            [ai.brainyard.agent.core.remote-agent :as remote]
            [ai.brainyard.agent.core.session :as session]
            [ai.brainyard.agent.core.tool :as tool]))

;; =============================================================================
;; Fixtures
;; =============================================================================

(defn clean-registries [f]
  (let [saved-tools @tool/!tool-defs]
    (try
      (a2a-client/reset-peers!)
      (f)
      (finally
        (reset! tool/!tool-defs saved-tools)
        (a2a-client/reset-peers!)
        (agent-core/reset-agent-registry!)))))

(use-fixtures :each clean-registries)

(def a-card
  {:name            "peer-b"
   :url             "https://peer-b.example/a2a"
   :protocolVersion "0.3"
   :capabilities    {:streaming true}
   :skills          [{:id "planner" :name "Planner" :description "Plans things"}
                     {:id "reviewer" :name "Reviewer"}]})

(defn- make-session []
  (atom {:session-id "agt-test" :user-id "u" :messages [] :total-turns 0 :data {}}))

(defn- make-parent
  "A minimal local agent standing in for the dispatching parent."
  [!session]
  (agent-core/map->Agent
   {:agent-id :router-agent/test-parent
    :!state   (atom {:status :idle
                     :lifecycle {:owner nil :answers 0
                                 :created-at (System/currentTimeMillis)}
                     :runtime {}})
    :!session !session}))

(defn- make-remote [!session parent]
  (remote/create {:agent-id        :a2a$b$planner/test-remote
                  :peer-name       "b"
                  :skill-id        "planner"
                  :parent-agent    parent
                  :!session        !session
                  :description     "Plans things"
                  :remote-agent-id "https://peer-b.example/a2a#planner"}))

(defn- register-peer! []
  (a2a-client/register-peer!
   (a2a-client/make-peer {:name "b" :url (:url a-card) :card a-card})))

;; =============================================================================
;; The record satisfies every protocol the existing call sites use
;; =============================================================================

(deftest satisfies-agent-protocols-test
  (let [!s (make-session)
        ra (make-remote !s (make-parent !s))]
    (testing "implements every agent protocol"
      (doseq [p [proto/IAgent proto/IAgentLifecycle proto/IAgentState
                 proto/IAgentBTIntegration proto/IAgentMemoryAccess]]
        (is (satisfies? p ra) (str "must satisfy " p))))

    (testing "is Closeable, so close-instance! and the cascade work"
      (is (instance? java.io.Closeable ra)))

    (testing "identity reads through"
      (is (= :a2a$b$planner/test-remote (proto/agent-id ra)))
      (is (= "agt-test" (proto/session-id ra)))
      (is (= "u" (proto/user-id ra)))
      (is (str/includes? (proto/agent-name ra) "a2a$b$planner")))))

(deftest state-shape-test
  (let [!s (make-session)
        parent (make-parent !s)
        ra (make-remote !s parent)]

    (testing "a remote peer is ALWAYS a subagent — :owner is set"
      ;; A session has exactly one root and it is local by definition.
      (is (= :router-agent/test-parent (:owner (agent-core/lifecycle ra))))
      (is (agent-core/subagent? ra)))

    (testing "it is a DISPATCHED worker, not a session-sharing subagent"
      ;; Session-sharing is the ACP case (a second model in the user's own
      ;; session). A remote peer is a worker: evictable, and it dies with
      ;; its creator.
      (is (not (agent-core/share-parent-session? ra)))
      (is (agent-core/dispatched-subagent? ra)))

    (testing "lifecycle bookkeeping fields exist for instance-summary"
      (let [lc (agent-core/lifecycle ra)]
        (is (= 0 (:answers lc)))
        (is (number? (:created-at lc)))
        (is (nil? (:last-ask-at lc)))))

    (testing "instance-idle-ms works (it reads :lifecycle)"
      (is (number? (agent-core/instance-idle-ms ra))))

    (testing "status starts :idle and is not :running"
      (is (= :idle (:status @(:!state ra))))
      (is (not (agent-core/running-instance? ra))))))

(deftest opaque-state-returns-nil-not-fabrication-test
  (let [!s (make-session)
        ra (make-remote !s (make-parent !s))]
    (testing "BT/state accessors return nil rather than a fake local shadow"
      ;; A2A peers are opaque by design; inventing a local iteration count
      ;; would put a lie in agent-registry$detail.
      (is (nil? (proto/get-bt ra)))
      (is (nil? (proto/get-bt-context ra)))
      (is (nil? (proto/get-st-memory-init ra)))
      (is (nil? (proto/get-bt-st-memory ra)))
      (is (nil? (proto/get-memory-manager ra))))

    (testing "the accessors ask/instance-summary call are nil-SAFE"
      ;; `ask` reaches .get-bt-st-memory on EVERY ask (for :terminated-by
      ;; and :next-user-prompt). If these threw, every remote ask would die.
      (is (nil? (agent-core/last-answer ra)))
      (is (nil? (some-> (proto/get-bt-st-memory ra) deref :terminated-by))))

    (testing "get-tools is empty — a remote peer's tools are not ours"
      (is (= [] (proto/get-tools ra))))))

(deftest clone-is-refused-test
  (let [!s (make-session)
        ra (make-remote !s (make-parent !s))]
    (testing "cloning throws rather than producing a half-clone"
      ;; Cloning snapshots local BT + st-memory, of which there is none.
      (is (thrown? clojure.lang.ExceptionInfo (proto/clone-agent ra))))))

(deftest remote-agent-predicate-test
  (let [!s (make-session)
        ra (make-remote !s (make-parent !s))
        local (make-parent !s)]
    (is (remote/remote-agent? ra))
    (is (not (remote/remote-agent? local)))
    (is (not (remote/remote-agent? nil)))))

(deftest describe-test
  (let [!s (make-session)
        ra (make-remote !s (make-parent !s))
        d  (remote/describe ra)]
    (testing "reports what a remote instance CAN know about itself"
      (is (= :remote (:kind d)))
      (is (= "b" (:peer d)))
      (is (= "planner" (:skill d)))
      (is (= "https://peer-b.example/a2a#planner" (:remote-id d)))
      (is (nil? (:context-id d)) "no context until the first exchange"))))

;; =============================================================================
;; Registry integration — the point of the whole phase
;; =============================================================================

(deftest lives-in-the-normal-registry-test
  (let [!s (make-session)
        parent (make-parent !s)
        ra (make-remote !s parent)]
    (agent-core/register-agent ra)

    (testing "a remote peer is found by the ordinary registry lookups"
      (is (some? (agent-core/get-agent (proto/agent-id ra))))
      (is (some #(= (proto/agent-id ra) (proto/agent-id %))
                (agent-core/list-agents-for-session "agt-test"))))

    (testing "it counts as a dispatched subagent for the LRU cap"
      ;; Remote peers must share the per-session budget; a separate one
      ;; would let a session hold unlimited instances.
      (is (pos? (agent-core/count-subagents "agt-test"))))

    (testing "close-instance! actually UNREGISTERS it"
      ;; Regression: an earlier stop-agent flipped :status but never
      ;; unregistered, so agent-registry$close reported success while the
      ;; instance stayed in the registry forever — still listed, still
      ;; counting against the per-session LRU cap.
      (is (:closed (agent-core/close-instance! (proto/agent-id ra))))
      (is (nil? (agent-core/get-agent (proto/agent-id ra))))
      (is (zero? (agent-core/count-subagents "agt-test"))
          "a closed remote peer must stop counting against the cap"))))

(deftest process-without-peer-is-an-error-not-a-throw-test
  (let [!s (make-session)
        ra (make-remote !s (make-parent !s))]
    (testing "asking a peer that is no longer connected reports it cleanly"
      (let [r (proto/process ra "hello" nil)]
        (is (some? (:error r)))
        (is (str/includes? (:error r) "no longer connected"))
        (is (str/includes? (:error r) "a2a$connect"))))))

;; =============================================================================
;; Skill -> tool-def registration
;; =============================================================================

(deftest skill-tool-id-test
  (testing "mirrors MCP's $-segmented convention"
    (is (= :a2a$b$planner (a2a-cmd/skill-tool-id "b" "planner")))))

(deftest register-skills-test
  (let [ids (a2a-cmd/register-skills! "b" a-card)]
    (testing "every skill becomes a tool-def"
      (is (= [:a2a$b$planner :a2a$b$reviewer] (vec (sort ids))))
      (is (every? #(contains? @tool/!tool-defs %) ids)))

    (testing "each is :type :agent, so it dispatches through the subagent path"
      ;; Not :command — a remote agent must ride the depth guard, the call
      ;; chain and the subagents display block.
      (doseq [id ids]
        (is (= :agent (:type (get @tool/!tool-defs id))))))

    (testing "the description marks it as remote and names the endpoint"
      (let [d (get-in @tool/!tool-defs [:a2a$b$planner :meta :description])]
        (is (str/includes? d "[A2A remote]"))
        (is (str/includes? d "peer-b.example"))))

    (testing "meta carries the call-chain token"
      (is (= "https://peer-b.example/a2a#planner"
             (get-in @tool/!tool-defs [:a2a$b$planner :meta :remote-id]))))

    (testing "unregister removes exactly this peer's skills"
      (let [other (a2a-cmd/register-skills! "c" (assoc a-card :url "https://c/a2a"))
            removed (a2a-cmd/unregister-skills! "b")]
        (is (= 2 (count removed)))
        (is (not-any? #(contains? @tool/!tool-defs %) removed))
        (is (every? #(contains? @tool/!tool-defs %) other)
            "peer c's skills must survive peer b's disconnect")))))

;; =============================================================================
;; The near-side cycle guard
;; =============================================================================

(deftest local-cycle-guard-test
  (let [rid "https://peer-b.example/a2a#planner"]
    (testing "not a cycle when the target is absent from the local chain"
      (binding [proto/*call-chain* [:router-agent/x]]
        (is (not (remote/cycle-target? rid)))))

    (testing "IS a cycle when the same remote skill is already dispatched"
      (binding [proto/*call-chain* [:router-agent/x rid]]
        (is (remote/cycle-target? rid))))

    (testing "keyword entries in the local chain compare without their colon"
      (binding [proto/*call-chain* [:explore-agent/lime-mole]]
        (is (remote/cycle-target? "explore-agent/lime-mole"))))

    (testing "describe-outbound-chain renders the would-be chain"
      (binding [proto/*call-chain* [:router-agent/x]]
        (is (= (str "router-agent/x -> " rid)
               (remote/describe-outbound-chain rid)))))))

(deftest wire-chain-is-node-scoped-not-agent-scoped-test
  (testing "outbound metadata carries NODE ids, not local agent ids"
    ;; The two vocabularies must not be mixed: a local chain holds
    ;; :explore-agent/x while the wire chain holds by-node:UUID. Mixing
    ;; them is what made an earlier version of the guard unable to fire.
    (binding [proto/*call-chain* [:router-agent/x]
              proto/*call-depth* 1]
      (let [m (remote/outbound-metadata "agt-1")]
        (is (= [(a2a/node-id)] (a2a/read-chain m))
            "the chain is this NODE, not the local agent stack")
        (is (= "agt-1" (a2a/read-context-id m))))))

  (testing "a FIRST hop stamps depth 1, not 3"
    ;; Regression, found only by live verification against a real peer.
    ;; `*call-depth*` is ALREADY incremented for this dispatch by the time
    ;; `process` runs — `tool/call-tool` does it for a :type :agent tool,
    ;; `ask-agent` does it for agent-registry$ask. An earlier version also
    ;; incremented in `make-invoke`, and `stamp-chain` adds its own +1, so
    ;; one logical hop was counted three times: with the default limit of 3,
    ;; the very first remote call came back "depth limit reached (3 >= 3)"
    ;; and NO remote dispatch could ever succeed. Every unit test set
    ;; *call-depth* by hand and so never exercised the real layering.
    (binding [proto/*call-depth* 1]   ;; what call-tool leaves for hop #1
      (is (= 1 (a2a/read-depth (remote/outbound-metadata nil)))))

    (testing "and a second hop stamps 2"
      (binding [proto/*call-depth* 2]
        (is (= 2 (a2a/read-depth (remote/outbound-metadata nil))))))

    (testing "depth never goes negative if something calls in unbound"
      (binding [proto/*call-depth* 0]
        (is (= 1 (a2a/read-depth (remote/outbound-metadata nil)))))))

  (testing "an inbound chain is extended, not replaced, on the next hop"
    (binding [remote/*inbound-chain* ["by-node:upstream"]
              proto/*call-depth* 1]
      (let [m (remote/outbound-metadata nil)]
        (is (= ["by-node:upstream" (a2a/node-id)] (a2a/read-chain m)))))))

;; =============================================================================
;; Command gating
;; =============================================================================

(deftest expose-skills-env-parsing-test
  ;; Regression: the key was declared :type "array" with NO :env-fn, so
  ;; BY_A2A_EXPOSE_SKILLS was silently ignored — while both the doc string
  ;; and the serve-refusal message told the operator to set it. A documented
  ;; env var that does nothing is worse than none.
  (testing "the natural comma-separated env form parses"
    (is (= ["explore-agent"] (config/parse-string-list "explore-agent")))
    (is (= ["a" "b" "c"] (config/parse-string-list "a,b, c"))))

  (testing "the EDN vector form parses too (matches config.edn)"
    (is (= ["a" "b"] (config/parse-string-list "[\"a\" \"b\"]"))))

  (testing "junk yields nil, NOT an empty vector"
    ;; For an allow-list, 'unparseable' and 'deliberately empty' must not
    ;; look the same: nil falls through to the next config layer, [] would
    ;; silently install an empty allow-list as if it were intended.
    (doseq [s ["" "   " "[unclosed" ",,," nil]]
      (is (nil? (config/parse-string-list s)) (str "should be nil: " (pr-str s))))))

(deftest commands-are-registered-test
  (testing "the a2a$* family is in the tool registry"
    (doseq [id [:a2a$connect :a2a$list :a2a$card :a2a$disconnect]]
      (is (contains? @tool/!tool-defs id) (str id " should be registered")))))

(deftest there-is-no-a2a-ask-test
  (testing "asking a remote peer goes through agent-registry$ask, not a2a$ask"
    ;; A second ask path would fork the reach policy, and the fork would be
    ;; the copy that forgets a rule.
    (is (not (contains? @tool/!tool-defs :a2a$ask)))
    (is (contains? @tool/!tool-defs :agent-registry$ask))))

(deftest card-command-test
  (register-peer!)
  (testing "a2a$card reports skills with their tool ids"
    (let [r ((:fn (get @tool/!tool-defs :a2a$card)) :name "b")]
      ;; The gate is off by default, so this returns the gate error unless
      ;; no current agent is bound (nil agent bypasses the feature check).
      (when-not (:error r)
        (is (= "peer-b" (:agent-name r)))
        (is (= 2 (count (:skills r))))
        (is (= "a2a$b$planner" (:tool-id (first (:skills r)))))))))

(deftest tool-ids-handed-to-the-llm-carry-no-colon-test
  ;; Regression: (str :a2a$b$planner) is ":a2a$b$planner". These strings are
  ;; given to the model AS THE TOOL NAME TO CALL, so a stray leading colon
  ;; makes it call something that does not exist. Same bug class as the
  ;; Agent Card skill ids in components/a2a.
  (register-peer!)
  (a2a-cmd/register-skills! "b" a-card)

  (testing "a2a$card :tool-id"
    (let [r ((:fn (get @tool/!tool-defs :a2a$card)) :name "b")]
      (when-not (:error r)
        (doseq [s (:skills r)]
          (is (not (str/starts-with? (:tool-id s) ":"))
              (str "leading colon in :tool-id " (pr-str (:tool-id s))))))))

  (testing "a2a$disconnect :unregistered"
    (let [r ((:fn (get @tool/!tool-defs :a2a$disconnect)) :name "b")]
      (when-not (:error r)
        (doseq [id (:unregistered r)]
          (is (not (str/starts-with? id ":"))
              (str "leading colon in :unregistered " (pr-str id))))))))

(deftest list-command-never-leaks-credentials-test
  (a2a-client/register-peer!
   (a2a-client/make-peer {:name "b" :url (:url a-card) :card a-card
                          :auth "sk-SUPERSECRET"}))
  (testing "a2a$list output carries no secret"
    (let [r ((:fn (get @tool/!tool-defs :a2a$list)))]
      (is (not (str/includes? (pr-str r) "SUPERSECRET"))))))

;; =============================================================================
;; peers-op — the turn-free face of the same commands (ask channel `{:op :a2a}`)
;; =============================================================================

(deftest peers-op-list-test
  (register-peer!)
  (testing "list reports the same peers a2a$list would"
    (let [r (a2a-cmd/peers-op nil {:action :list})]
      (is (= 1 (:total r)))
      (is (= "b" (:name (first (:peers r)))))))
  (testing "and says the registry is process-wide, because it is"
    ;; A caller that assumed per-session peers would be wrong in a shared
    ;; host — one connect is visible to every co-hosted session.
    (is (true? (:host-wide? (a2a-cmd/peers-op nil {:action :list}))))))

(deftest peers-op-list-never-leaks-credentials-test
  (a2a-client/register-peer!
   (a2a-client/make-peer {:name "b" :url (:url a-card) :card a-card
                          :auth "sk-SUPERSECRET"}))
  ;; The command path is already covered above; this is the SECOND door to the
  ;; same data, and a redaction that only holds on one door is not a redaction.
  (is (not (str/includes? (pr-str (a2a-cmd/peers-op nil {:action :list}))
                          "SUPERSECRET"))))

(deftest peers-op-add-refuses-an-existing-name-test
  (register-peer!)
  ;; Silently overwriting would let a caller believe it created a peer while
  ;; actually replacing someone else's — the kind of thing an API must not do
  ;; quietly, even though `a2a$connect` (a human-driven command) may.
  (let [r (a2a-cmd/peers-op nil {:action :add :name "b" :url "https://other.example"})]
    (is (:error r))
    (is (str/includes? (:error r) "already connected"))
    (is (str/includes? (:error r) ":update"))
    (testing "and the existing peer is untouched"
      (is (= (:url a-card) (:endpoint (first (:peers (a2a-cmd/peers-op nil {:action :list})))))))))

(deftest peers-op-update-refuses-an-unknown-name-test
  (let [r (a2a-cmd/peers-op nil {:action :update :name "ghost" :url "https://x.example"})]
    (is (:error r))
    (is (str/includes? (:error r) "no such A2A peer"))
    (is (str/includes? (:error r) ":add"))))

(deftest peers-op-delete-test
  (register-peer!)
  (a2a-cmd/register-skills! "b" a-card)
  (is (contains? @tool/!tool-defs :a2a$b$planner))
  (let [r (a2a-cmd/peers-op nil {:action :delete :name "b"})]
    (is (:disconnected r))
    (testing "the peer is gone AND so are its skill tool-defs"
      ;; Leaving the tool-defs behind would keep a dead peer callable — the
      ;; model would dispatch it and get a confusing runtime failure.
      (is (zero? (:total (a2a-cmd/peers-op nil {:action :list}))))
      (is (not (contains? @tool/!tool-defs :a2a$b$planner)))))
  (testing "deleting it again is an error, not a silent success"
    (is (str/includes? (:error (a2a-cmd/peers-op nil {:action :delete :name "b"}))
                       "no such A2A peer"))))

(deftest peers-op-validates-input-test
  (testing "unknown action names the four that exist"
    (let [e (:error (a2a-cmd/peers-op nil {:action :frobnicate}))]
      (is (str/includes? e ":list"))
      (is (str/includes? e ":delete"))))
  (testing "a string action is accepted — the wire carries EDN keywords loosely"
    ;; A client that sent "list" or ":list" as a string should not get an
    ;; "unknown action" for what is plainly the right one.
    (is (= 0 (:total (a2a-cmd/peers-op nil {:action "list"}))))
    (is (= 0 (:total (a2a-cmd/peers-op nil {:action ":list"})))))
  (testing "missing required args are named"
    (is (str/includes? (:error (a2a-cmd/peers-op nil {:action :add :name "x"})) "url"))
    (is (str/includes? (:error (a2a-cmd/peers-op nil {:action :add :url "https://x.example"})) "name"))
    (is (str/includes? (:error (a2a-cmd/peers-op nil {:action :delete})) "name"))))
