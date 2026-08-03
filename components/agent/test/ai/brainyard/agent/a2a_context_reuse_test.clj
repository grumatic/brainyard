;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.a2a-context-reuse-test
  "Per-`contextId` instance reuse on the serving side.

   A2A's contextId names a conversation, so a follow-up on the same id must
   reach the same agent — but a remote caller invents contextIds freely, so
   the reuse has to be bounded. These tests pin both halves: that a follow-up
   continues, and that nothing accumulates without limit.

   Hermetic: the agent layer is stubbed, so no LLM, subprocess or registry is
   touched. What is under test is the context bookkeeping in `make-ask-fn`."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [ai.brainyard.agent.common.a2a-serve :as serve]
            [ai.brainyard.agent.core.agent :as agent-core]
            [ai.brainyard.agent.core.config :as config]))

;; =============================================================================
;; Stub agent layer
;; =============================================================================

(def ^:private !instances (atom {}))   ;; agent-id -> fake Agent
(def ^:private !asks      (atom []))   ;; [[agent-id prompt] …]
(def ^:private !closed    (atom []))   ;; agent-ids, in close order
(def ^:private !seq       (atom 0))

(defn- reset-stubs! []
  (reset! !instances {}) (reset! !asks []) (reset! !closed []) (reset! !seq 0)
  (serve/reset-contexts!))

(use-fixtures :each (fn [f] (reset-stubs!) (try (f) (finally (reset-stubs!)))))

(defn- fake-instance
  "A real Agent record — `run-turn!` reads its id and session through the
   protocol, so a bare map would not do."
  [skill sid]
  (let [n   (swap! !seq inc)
        aid (keyword skill (str "ctx" n))]
    (agent-core/map->Agent
     {:agent-id aid
      :!state   (atom {:status :idle
                       :lifecycle {:owner nil :answers 0
                                   :created-at (System/currentTimeMillis)}
                       :runtime {}})
      :!session (atom {:session-id sid :user-id "u" :messages []
                       :total-turns 0 :data {}})})))

(defn- with-stubs
  "Run `f` with the agent layer stubbed and config pinned to `cap`/`ttl`.

   `exposable-agents` is stubbed too: it reads the live `!tool-defs`, and
   loading the real roster here would drag in every defagent for a test about
   bookkeeping. `:agents` overrides the exposed roster; `ask` defaults to a
   successful answer, pass one that returns `{:error …}` or blocks to
   exercise the other paths."
  [{:keys [cap ttl ask agents]} f]
  (let [ask    (or ask (fn [_inst prompt] {:answer (str "ok: " prompt)}))
        agents (or agents [[:explore-agent {:type :agent}]])]
    (with-redefs [serve/exposable-agents (fn [_allow] agents)
                  config/get-config (fn [_agent k]
                                      (case k
                                        :a2a-max-contexts   cap
                                        :a2a-context-ttl-ms ttl
                                        nil))
                  agent-core/setup-agent-by-id
                  (fn [agent-id & {:keys [agent-session]}]
                    (let [inst (fake-instance (name agent-id) (:session-id agent-session))]
                      (swap! !instances assoc (:agent-id inst) inst)
                      inst))
                  agent-core/get-agent      (fn [aid] (get @!instances aid))
                  agent-core/close-instance! (fn [aid]
                                               (swap! !instances dissoc aid)
                                               (swap! !closed conj aid)
                                               {:closed true})
                  agent-core/ask            (fn [inst prompt]
                                              (swap! !asks conj [(:agent-id inst) prompt])
                                              (ask inst prompt))]
      (f))))

(defn- ask-fn []
  (serve/make-ask-fn {:allow ["explore-agent"] :agent nil}))

(defn- ask! [f ctx text]
  (f {:text text :context-id ctx :metadata {}}))

(defn- asked-ids [] (mapv first @!asks))

;; =============================================================================
;; Reuse
;; =============================================================================

(deftest same-context-reuses-the-instance-test
  (testing "a follow-up on one contextId continues the same agent"
    (with-stubs {:cap 8 :ttl 600000}
      (fn []
        (let [f (ask-fn)
              a (ask! f "ctx-1" "first")
              b (ask! f "ctx-1" "second")]
          (is (= "ok: first" (:answer a)))
          (is (= "ok: second" (:answer b)))
          (is (= 1 (count (distinct (asked-ids))))
              "both turns landed on ONE instance")
          (is (empty? @!closed) "and it was not reclaimed between turns")
          (is (= "ctx-1" (:context-id a) (:context-id b))
              "the contextId is stable, so the caller can keep using it"))))))

(deftest distinct-contexts-get-distinct-instances-test
  (testing "two conversations do not share an agent"
    (with-stubs {:cap 8 :ttl 600000}
      (fn []
        (let [f (ask-fn)]
          (ask! f "ctx-a" "one")
          (ask! f "ctx-b" "two")
          (is (= 2 (count (distinct (asked-ids)))))
          (is (= 2 (serve/context-count))))))))

(deftest reuse-falls-back-when-the-instance-vanished-test
  (testing "an instance closed out from under us is replaced, not asked"
    (with-stubs {:cap 8 :ttl 600000}
      (fn []
        (let [f   (ask-fn)
              _   (ask! f "ctx-1" "first")
              old (first (asked-ids))]
          ;; Simulate a cascade/teardown closing it behind the registry's back.
          (swap! !instances dissoc old)
          (let [r (ask! f "ctx-1" "second")]
            (is (= "ok: second" (:answer r)))
            (is (not= old (last (asked-ids)))
                "the second turn ran on a fresh instance")))))))

;; =============================================================================
;; Bounds — the reason reuse is allowed at all
;; =============================================================================

(deftest cap-evicts-least-recently-used-test
  (testing "past :a2a-max-contexts the LRU idle context is closed"
    (with-stubs {:cap 2 :ttl 600000}
      (fn []
        (let [f (ask-fn)]
          (ask! f "ctx-a" "a")
          (ask! f "ctx-b" "b")
          (ask! f "ctx-a" "a again")     ;; a is now the most recent
          (ask! f "ctx-c" "c")           ;; forces an eviction
          (is (= 2 (serve/context-count)) "the cap holds")
          (let [live (set (map :context-id (serve/describe-contexts)))]
            (is (= #{"ctx-a" "ctx-c"} live)
                "ctx-b was the least recently used, so it went"))
          (is (= 1 (count @!closed)) "and its instance was actually closed"))))))

(deftest ttl-sweeps-idle-contexts-test
  (testing "a context idle past the TTL is swept on the next turn"
    (with-stubs {:cap 8 :ttl 1}
      (fn []
        (let [f (ask-fn)]
          (ask! f "ctx-old" "a")
          (Thread/sleep (long 25))
          (ask! f "ctx-new" "b")
          (is (= ["ctx-new"] (map :context-id (serve/describe-contexts)))
              "the stale conversation is gone")
          (is (= 1 (count @!closed))))))))

(deftest cap-zero-restores-fresh-per-turn-test
  (testing ":a2a-max-contexts 0 disables reuse entirely"
    (with-stubs {:cap 0 :ttl 600000}
      (fn []
        (let [f (ask-fn)]
          (ask! f "ctx-1" "first")
          (ask! f "ctx-1" "second")
          (is (= 2 (count (distinct (asked-ids)))) "no instance was reused")
          (is (= 2 (count @!closed)) "each turn reclaimed its instance")
          (is (zero? (serve/context-count)) "and nothing is retained"))))))

;; =============================================================================
;; Failure and conflict handling
;; =============================================================================

(deftest failed-turn-drops-the-context-test
  (testing "a context whose turn errored is not reused"
    (with-stubs {:cap 8 :ttl 600000
                 :ask (fn [_ prompt]
                        (if (= "boom" prompt)
                          {:error "backend died"}
                          {:answer (str "ok: " prompt)}))}
      (fn []
        (let [f (ask-fn)
              _ (ask! f "ctx-1" "first")
              first-id (first (asked-ids))
              r (ask! f "ctx-1" "boom")]
          (is (= "backend died" (:error r)))
          (is (= [first-id] @!closed) "the wedged instance was closed")
          (is (zero? (serve/context-count)) "and the context forgotten")
          (ask! f "ctx-1" "third")
          (is (not= first-id (last (asked-ids)))
              "the next turn starts clean rather than inheriting it"))))))

(deftest skill-change-retires-the-old-instance-test
  (testing "re-addressing one contextId to another skill is a new conversation"
    (with-stubs {:cap 8 :ttl 600000
                 :agents [[:explore-agent {:type :agent}]
                          [:plan-agent {:type :agent}]]}
      (fn []
        (let [f (serve/make-ask-fn {:allow ["explore-agent" "plan-agent"] :agent nil})
              _ (ask! f "ctx-1" "[skill: explore-agent]\nfirst")
              explore-id (first (asked-ids))
              _ (ask! f "ctx-1" "[skill: plan-agent]\nsecond")]
          (is (= [explore-id] @!closed)
              "the explore instance is retired rather than handed plan's turn")
          (is (= "plan-agent" (namespace (last (asked-ids)))))
          (is (= 1 (serve/context-count)) "one context, now bound to plan-agent"))))))

(deftest concurrent-turn-on-one-context-is-refused-test
  (testing "a second in-flight turn on one contextId errors instead of queueing"
    (let [release (promise)
          entered (promise)]
      (with-stubs {:cap 8 :ttl 600000
                   :ask (fn [_ prompt]
                          (when (= "slow" prompt)
                            (deliver entered true)
                            (deref release 5000 :timeout))
                          {:answer (str "ok: " prompt)})}
        (fn []
          (let [f   (ask-fn)
                fut (future (ask! f "ctx-1" "slow"))]
            (is (= true (deref entered 5000 nil)) "first turn is in flight")
            (let [r (ask! f "ctx-1" "overlapping")]
              (is (some? (:error r)))
              (is (re-find #"in flight" (:error r))))
            (deliver release true)
            (is (= "ok: slow" (:answer @fut)))
            (is (= 1 (count (distinct (asked-ids))))
                "the refused turn never reached an agent")))))))
