;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.a2a-loopback-test
  "The end-to-end proof: the REAL A2A client talking to the REAL A2A server
   over a REAL socket, with the real call-chain stamping in between.

   This is what §4 and §10 of docs/design/a2a-design.md promise. Everything
   up to Phase 4 could only *simulate* the hops; here the chain actually
   crosses a TCP connection, gets JSON-encoded, keywordized on decode, and
   checked by a handler running on another thread. Two earlier versions of
   the guard passed every simulated test while being unable to fire — this
   is the test that would have caught them without argument.

   `ask-fn` is a stub. The point under test is the wire contract and the
   guard, not an LLM turn, and a real agent here would make the suite slow
   and non-hermetic. The agent-side `make-ask-fn` is covered separately."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [ai.brainyard.a2a.interface :as a2a]
            [ai.brainyard.a2a-client.interface :as a2a-client]
            [ai.brainyard.a2a-server.interface :as a2a-server]
            [ai.brainyard.agent.common.a2a :as a2a-cmd]
            [ai.brainyard.agent.common.a2a-serve :as serve]
            ;; Side-effecting: registers the built-in defagent roster, which
            ;; is what `exposable-agents` reads. Without it the allow-list
            ;; matches nothing and every card is empty.
            [ai.brainyard.agent.interface]
            [ai.brainyard.agent.core.agent :as agent-core]
            [ai.brainyard.agent.core.protocol :as proto]
            [ai.brainyard.agent.core.remote-agent :as remote]
            [ai.brainyard.agent.core.tool :as tool]))

;; =============================================================================
;; Fixtures
;; =============================================================================

(defn clean [f]
  (let [saved @tool/!tool-defs]
    (a2a-client/reset-peers!)
    (try (f)
         (finally (reset! tool/!tool-defs saved)
                  (a2a-client/reset-peers!)
                  (agent-core/reset-agent-registry!)))))

(use-fixtures :each clean)

(def ^:const TOKEN "loopback-token")

(defn- start-server!
  "Start the real server with the real card generator and a stub ask-fn.

   The card's `:url` is filled in AFTER binding, because we ask for an
   ephemeral port (`:port 0`) and only then learn which one we got. A card
   advertising port 0 would send every client to an unassignable address —
   `card-fn` is called per request, so late-binding it through an atom is
   enough. (`serve!` has no such problem: it binds a configured port and
   knows the URL up front.)"
  [& {:keys [ask-fn allow] :or {allow ["explore-agent"]}}]
  (let [!asks (atom [])
        !url  (atom nil)
        service {:card-fn   (fn [] (serve/build-card {:url @!url :allow allow}))
                 :ask-fn    (or ask-fn
                                (fn [req]
                                  (swap! !asks conj req)
                                  {:answer (str "served: " (:text req))
                                   :state :completed}))
                 :auth-token TOKEN
                 :max-depth  3}
        handle  (a2a-server/start! service {:host "127.0.0.1" :port 0})]
    (reset! !url (str (:url handle) a2a-server/RPC_PATH))
    (assoc handle :asks !asks)))

(defn- make-session []
  (atom {:session-id "agt-loop" :user-id "u" :messages [] :total-turns 0 :data {}}))

(defn- make-parent [!session]
  (agent-core/map->Agent
   {:agent-id :main-agent/loopback-parent
    :!state   (atom {:status :idle
                     :lifecycle {:owner nil :answers 0
                                 :created-at (System/currentTimeMillis)}
                     :runtime {}})
    :!session !session}))

;; =============================================================================
;; Discovery + a real round trip
;; =============================================================================

(deftest card-is-public-and-rpc-is-not-test
  (let [srv (start-server!)]
    (try
      (testing "the well-known card is served UNAUTHENTICATED, per spec"
        (let [{:keys [card error]} (a2a-client/fetch-card! (:url srv) :refresh? true)]
          (is (nil? error))
          (is (= "brainyard" (:name card)))
          (is (= ["explore-agent"] (mapv :id (a2a/card-skills card))))
          (is (a2a/card-supports? card :streaming))))

      (testing "the RPC endpoint REJECTS an unauthenticated caller"
        (let [peer (a2a-client/make-peer {:name "self" :url (str (:url srv) "/a2a")
                                          :card {:name "x"}})
              out  (a2a-client/send-message! peer "hi")]
          (is (some? (:error out)))
          (is (str/includes? (:error out) "401"))))

      (testing "a wrong token is rejected too"
        (let [peer (a2a-client/make-peer {:name "self" :url (str (:url srv) "/a2a")
                                          :card {:name "x"} :auth "wrong"})]
          (is (str/includes? (:error (a2a-client/send-message! peer "hi")) "401"))))
      (finally (a2a-server/stop! srv)))))

(deftest connect-and-ask-round-trip-test
  (let [srv (start-server!)]
    (try
      (testing "a2a$connect discovers the peer and registers its skills"
        (let [r (a2a-client/connect! {:name "self" :url (:url srv) :auth TOKEN})]
          (is (nil? (:error r)))
          (is (= ["explore-agent"] (:skills (:peer r))))
          (a2a-cmd/register-skills! "self" (:card r))
          (is (contains? @tool/!tool-defs :a2a$self$explore-agent))))

      (testing "a raw client ask reaches the server's ask-fn"
        ;; No chain metadata: this is the plain 'stranger' path.
        (let [peer (a2a-client/get-peer "self")
              out  (a2a-client/send-message! peer "hello there")]
          (is (nil? (:error out)))
          (is (= "served: hello there" (:answer out)))
          (is (= :completed (:state out)))))
      (finally (a2a-server/stop! srv)))))

;; =============================================================================
;; THE test: a process pointed at itself refuses to recurse
;; =============================================================================

(deftest self-loopback-is-refused-over-the-wire-test
  (let [srv (start-server!)]
    (try
      (a2a-client/connect! {:name "self" :url (:url srv) :auth TOKEN})
      (a2a-cmd/register-skills! "self" (:card (a2a-client/get-peer "self")))
      (let [!s     (make-session)
            parent (make-parent !s)
            ra     (remote/create {:agent-id :a2a$self$explore-agent/loop
                                   :peer-name "self" :skill-id "explore-agent"
                                   :parent-agent parent :!session !s
                                   :remote-agent-id (str (:url srv) "/a2a#explore-agent")})
            out    (proto/process ra "do something" nil)]

        (testing "asking OURSELVES over A2A is refused as a cycle"
          ;; Client and server share this process's node id, so the chain the
          ;; client stamps already contains the node the server is checking
          ;; against. That is precisely the A → B → A shape, collapsed to one
          ;; hop, and it is caught on the wire rather than by simulation.
          (is (some? (:error out)))
          (is (str/includes? (str/lower-case (:error out)) "cycle")))

        (testing "the server did NO work"
          (is (zero? (count @(:asks srv)))
              "ask-fn must never run for a refused request")))
      (finally (a2a-server/stop! srv)))))

(deftest foreign-node-is-served-then-refused-on-return-test
  ;; The two-node scenario, driven by crafting the chain a *different* node
  ;; would have stamped. One JVM has one node id, so a genuine second peer
  ;; cannot exist here; the wire format is what matters and it is real.
  (let [srv    (start-server!)
        peer   (a2a-client/make-peer {:name "raw" :url (str (:url srv) "/a2a")
                                      :card {:name "x"} :auth TOKEN})
        other  "by-node:some-other-process"]
    (try
      (testing "a chain from a FOREIGN node is served"
        (let [out (a2a-client/send-message!
                   peer "hi"
                   :metadata {a2a/CHAIN_KEY [other] a2a/DEPTH_KEY 1})]
          (is (nil? (:error out)))
          (is (= "served: hi" (:answer out)))))

      (testing "a chain that already contains US is refused"
        (let [out (a2a-client/send-message!
                   peer "hi"
                   :metadata {a2a/CHAIN_KEY [other (a2a/node-id)]
                              a2a/DEPTH_KEY 2})]
          (is (some? (:error out)))
          (is (str/includes? (str/lower-case (:error out)) "cycle"))))

      (testing "the depth limit is enforced independently of cycles"
        (let [out (a2a-client/send-message!
                   peer "hi"
                   :metadata {a2a/CHAIN_KEY [other "by-node:b" "by-node:c"]
                              a2a/DEPTH_KEY 3})]
          (is (some? (:error out)))
          (is (str/includes? (str/lower-case (:error out)) "depth"))))

      (testing "only the refused calls were refused — the served one ran"
        (is (= 1 (count @(:asks srv)))))
      (finally (a2a-server/stop! srv)))))

;; =============================================================================
;; Streaming over the wire
;; =============================================================================

(deftest streaming-round-trip-test
  (let [srv (start-server!
             :ask-fn (fn [{:keys [text on-chunk]}]
                       (on-chunk "thinking " "thinking ")
                       (on-chunk "done" "thinking done")
                       {:answer "thinking done" :state :completed}))]
    (try
      (a2a-client/connect! {:name "self" :url (:url srv) :auth TOKEN})
      (let [peer   (a2a-client/get-peer "self")
            !evts  (atom [])
            !done  (promise)
            handle (a2a-client/stream-message!
                    peer "go"
                    {:on-event (fn [p] (swap! !evts conj p))
                     :on-close (fn [] (deliver !done true))})]
        (deref !done 5000 nil)
        (try ((:stop! handle)) (catch Throwable _ nil))

        (testing "the client received the server's frames"
          (is (seq @!evts)))

        (testing "the frames translate into brainyard descriptors"
          ;; Client-side translation of server-side framing — the two halves
          ;; agreeing is the thing this proves.
          (let [{:keys [events]} (a2a-client/translate-all @!evts)
                kinds (set (map :event events))]
            (is (contains? kinds :a2a/task-state))
            (is (contains? kinds :agent.dspy-action/chunk))
            (is (contains? kinds :a2a/task-terminal))))

        (testing "the terminal descriptor carries the full answer"
          (let [{:keys [events]} (a2a-client/translate-all @!evts)
                terminal (first (filter #(= :a2a/task-terminal (:event %)) events))]
            (is (= :completed (-> terminal :data :state)))
            (is (= "thinking done" (-> terminal :data :answer))))))
      (finally (a2a-server/stop! srv)))))

;; =============================================================================
;; Serve-side guards
;; =============================================================================

(deftest streaming-carries-context-id-for-follow-ups-test
  ;; Regression, found only by live verification. `message/send` returned the
  ;; peer's contextId via `task-response`, but the STREAMING path never put it
  ;; in any frame and `ask-streaming` never read it out of the accumulator.
  ;; Streaming is the DEFAULT, so in normal use every follow-up silently
  ;; started a FRESH remote conversation — defeating the entire point of the
  ;; instance remembering a context.
  (let [srv (start-server!
             :ask-fn (fn [{:keys [context-id]}]
                       ;; A real server establishes a context on the first
                       ;; call and echoes the client's on later ones.
                       {:answer "ok" :state :completed
                        :context-id (or context-id "ctx-established")}))]
    (try
      (a2a-client/connect! {:name "self" :url (:url srv) :auth TOKEN})
      (let [peer (a2a-client/get-peer "self")]

        (testing "the blocking path returns the context (it always did)"
          (is (= "ctx-established"
                 (:context-id (a2a-client/send-message! peer "hi" :blocking? true)))))

        (testing "the STREAMING path returns it too"
          (let [!s (make-session)
                ra (remote/create {:agent-id :a2a$self$echo/ctx
                                   :peer-name "self" :skill-id "explore-agent"
                                   :parent-agent (make-parent !s) :!session !s
                                   :remote-agent-id "x#y"})
                out (remote/ask-streaming ra peer "hi" {:timeout-ms 8000})]
            (is (nil? (:error out)))
            (is (= "ctx-established" (:context-id out))
                "without this, process stores nil and continuity is lost")))

        ;; There is deliberately NO in-process assertion that `process`
        ;; stores the context across two turns. `process` stamps this node's
        ;; id, and here the client and server share a JVM — so the server
        ;; correctly refuses its own id as a cycle (see
        ;; `self-loopback-is-refused-over-the-wire-test`). The full
        ;; client -> HTTP -> separate-server -> follow-up path cannot be
        ;; expressed in a same-JVM test BY DESIGN.
        ;;
        ;; It was verified live instead, TUI JVM against a separate server
        ;; process: first ask captured contextId a2a-1785707243147, and the
        ;; follow-up through agent-registry$ask reported the same one.
        ;; What this test pins is the two halves that made that possible —
        ;; the server emitting contextId in its stream frames, and
        ;; `ask-streaming` reading it back out.
        )
      (finally (a2a-server/stop! srv)))))

(deftest serve-refuses-without-configuration-test
  (testing "serve! refuses when A2A is disabled"
    ;; :enable-a2a defaults false, so an unconfigured process cannot
    ;; accidentally start listening.
    (let [r (serve/serve! nil {:port 0})]
      (is (some? (:error r)))
      (is (str/includes? (:error r) "disabled"))))

  (testing "start! refuses without a token"
    (let [r (a2a-server/start! {:card-fn (constantly {:name "x"})
                                :ask-fn (fn [_] {:answer "x"})}
                               {:port 0})]
      (is (some? (:error r)))
      (is (str/includes? (:error r) "refuses to bind")))))

(deftest empty-allow-list-exposes-nothing-test
  (testing "a card built with no allow-list has no skills"
    ;; The security default: nothing is reachable until an operator names it.
    (is (empty? (:skills (serve/build-card {:url "http://x/a2a" :allow []})))))

  (testing "an agent NOT on the allow-list is not exposed"
    (is (empty? (:skills (serve/build-card {:url "http://x/a2a"
                                            :allow ["no-such-agent"]}))))))

(deftest unexposed-skill-is-indistinguishable-from-missing-test
  (let [ask (serve/make-ask-fn {:allow ["explore-agent"]})]
    (testing "asking for an agent that exists locally but is NOT exposed"
      ;; It must read the same as a nonexistent one, or a caller can
      ;; enumerate the local roster by diffing the errors.
      ;;
      ;; The two messages are not byte-identical: each echoes back the name
      ;; the CALLER supplied. That is not a leak — they already know what
      ;; they asked for. What must not differ is anything derived from
      ;; local state, so the comparison strips the echoed name.
      (let [r1 (ask {:text "[skill: coact-agent]\nhi"})
            r2 (ask {:text "[skill: definitely-not-real]\nhi"})
            strip #(str/replace % #":.*$" ":")]
        (is (some? (:error r1)))
        (is (some? (:error r2)))
        (is (= (strip (:error r1)) (strip (:error r2)))
            "the response must not betray whether the agent exists locally")
        (is (str/includes? (:error r1) "no such skill"))
        (is (not (str/includes? (:error r1) "exposed"))
            "must not hint that the agent exists but is withheld")))))

(deftest named-skill-is-never-silently-substituted-test
  ;; Regression: the single-exposed-skill fallback used to override an
  ;; EXPLICIT name, so a caller asking for `coact-agent` was served by
  ;; `explore-agent` — a confident answer from the wrong agent, with no
  ;; way for the caller to tell.
  (let [ask (serve/make-ask-fn {:allow ["explore-agent"]})]
    (testing "a named-but-unexposed skill errors instead of falling back"
      (let [r (ask {:text "[skill: coact-agent]\nhi"})]
        (is (some? (:error r)))
        (is (str/includes? (:error r) "no such skill"))))

    (testing "the fallback still applies when NOTHING is named"
      ;; With one exposed skill and no prefix, routing is unambiguous.
      ;; (It reaches instantiation, which is as far as this test goes —
      ;; the point is that it is not a routing error.)
      (let [r (ask {:text "plain question with no skill prefix"})]
        (is (not (and (:error r) (str/includes? (:error r) "no such skill"))))))))
