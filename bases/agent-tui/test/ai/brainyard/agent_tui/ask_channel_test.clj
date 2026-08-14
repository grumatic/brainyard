;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent-tui.ask-channel-test
  "Integration test for the side ask channel inside a TUI session: the real
   per-session AF_UNIX listener, the real client, and the real response-shaping
   in `ask-handle-fn` — only the agent/queue boundary (`inject-side-ask!`) is
   stubbed, so no LLM is needed."
  (:require [clojure.test :refer [deftest is testing]]
            [ai.brainyard.agent-tui.core :as core]
            [ai.brainyard.agent-tui.session :as tui-session]
            [ai.brainyard.agent-tui.sessions :as sessions]
            [ai.brainyard.ask-channel.interface :as ask]
            [ai.brainyard.agent-tui-persist.interface :as persist]
            [ai.brainyard.agent.interface :as agent])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(defn- tmp-root []
  (.toFile (Files/createTempDirectory "ask-it" (make-array FileAttribute 0))))

(def ^:private fake-ag
  (reify java.io.Closeable (close [_] nil)))

(deftest listener-lifecycle-and-attach
  (let [root (tmp-root)
        sid  "agt-test-123"]
    (persist/with-root root
      (with-redefs [agent/session-id  (fn [_] sid)
                    agent/get-config  (fn [k] (case k
                                                :ask-channel-enabled? true
                                                :ask-timeout-ms 5000
                                                nil))
                    core/inject-side-ask! (fn [_ag question]
                                            (doto (promise)
                                              (deliver {:answer (str "ECHO:" question)})))]
        (testing "start opens the socket and records its path in meta.edn"
          (core/start-ask-listener! fake-ag)
          (let [sock (persist/file-of sid :ask-sock)]
            (is (.exists sock) "ask.sock should exist after start")
            (is (= (.getAbsolutePath sock) (:ask-socket-path (persist/read-meta sid)))
                "meta.edn should carry :ask-socket-path")

            (testing "a client reaches the agent and gets the answer back"
              (let [resp (ask/ask-via-socket! {:path (.getAbsolutePath sock)
                                               :question "hi"
                                               :timeout-ms 5000})]
                ;; The response also carries LM metadata (:provider/:model/:agent,
                ;; added by the `--json` ask feature) sourced from the process-global
                ;; default LM — irrelevant to and unstable for this channel-roundtrip
                ;; test, so assert only the roundtrip essentials.
                (is (= {:status :ok :answer "ECHO:hi" :usage nil}
                       (select-keys resp [:status :answer :usage])))))

            (testing "an empty question is rejected without injecting"
              (let [resp (ask/ask-via-socket! {:path (.getAbsolutePath sock)
                                               :question "   "
                                               :timeout-ms 5000})]
                (is (= :error (:status resp)))))))

        (testing "start is idempotent per session-id (no second listener)"
          (let [before (count @@#'core/!ask-listeners)]
            (core/start-ask-listener! fake-ag)
            (is (= before (count @@#'core/!ask-listeners)))))

        (testing "stop unlinks the socket and clears the registry"
          (core/stop-ask-listener! sid)
          (is (not (.exists (persist/file-of sid :ask-sock))))
          (is (not (contains? @@#'core/!ask-listeners sid))))))))

(deftest config-op-reads-effective-config
  (testing ":config returns a non-blocking effective-config read (no turn injected)"
    (let [root (tmp-root) sid "agt-cfg-1"]
      (persist/with-root root
        (with-redefs [agent/session-id (fn [_] sid)
                      ;; :ask-channel-enabled? is a GATE — read via the feature
                      ;; registry now, so stub both: the gate here, the knob below.
                      agent/feature-on? (fn [_ _] true)
                      agent/get-config (fn [k] (case k
                                                 :ask-channel-enabled? true
                                                 :ask-timeout-ms 5000 nil))
                      ;; a :config read must NEVER inject a turn — fail loudly if it does
                      core/inject-side-ask! (fn [_ _]
                                              (throw (ex-info "config op injected a turn!" {})))]
          (try
            (core/start-ask-listener! fake-ag)
            (let [sock (.getAbsolutePath (persist/file-of sid :ask-sock))]
              (testing "full read carries overrides + a redacted snapshot"
                (let [resp (ask/send-op! sock {:op :config})]
                  (is (= :ok (:status resp)))
                  (is (= sid (:session-id resp)))
                  (is (integer? (:total resp)))
                  (is (map? (:overrides resp)))
                  (is (map? (:snapshot resp)))
                  (is (<= (count (:overrides resp)) (:total resp)))))
              (testing "query mode narrows to matching keys"
                (let [resp (ask/send-op! sock {:op :config :query "iteration"})]
                  (is (= :ok (:status resp)))
                  (is (= "iteration" (:query resp)))
                  (is (vector? (:matches resp))))))
            (finally (core/stop-ask-listener! sid))))))))

(deftest disabled-by-config
  (testing "no socket is opened when :ask-channel-enabled? is false"
    (let [root (tmp-root) sid "agt-off-1"]
      (persist/with-root root
        (with-redefs [agent/session-id (fn [_] sid)
                      agent/feature-on? (fn [_ _] false)
                      agent/get-config (fn [_] false)]
          (core/start-ask-listener! fake-ag)
          (is (not (contains? @@#'core/!ask-listeners sid)))
          (is (not (.exists (persist/file-of sid :ask-sock)))))))))

(deftest attach-timeout-when-turn-never-completes
  (testing "a turn that never delivers surfaces a timeout to the client"
    (let [root (tmp-root) sid "agt-slow-1"]
      (persist/with-root root
        (with-redefs [agent/session-id (fn [_] sid)
                      ;; :ask-channel-enabled? is a GATE — read via the feature
                      ;; registry now, so stub both: the gate here, the knob below.
                      agent/feature-on? (fn [_ _] true)
                      agent/get-config (fn [k] (case k
                                                 :ask-channel-enabled? true
                                                 :ask-timeout-ms 5000 nil))
                      ;; never deliver — exercises the deref timeout path
                      core/inject-side-ask! (fn [_ _] (promise))]
          (try
            (core/start-ask-listener! fake-ag)
            (let [sock (persist/file-of sid :ask-sock)
                  resp (ask/ask-via-socket! {:path (.getAbsolutePath sock)
                                             :question "hi" :timeout-ms 300})]
              (is (= :error (:status resp)))
              (is (re-find #"timed out" (:error resp))))
            (finally (core/stop-ask-listener! sid))))))))

(deftest rename-session-op-writes-both-surfaces
  (testing ":rename-session updates the PERSISTED label and the live tab"
    (let [root (tmp-root) sid "agt-rn-1"
          op   #'core/handle-rename-session-op]
      (persist/with-root root
        (with-redefs [agent/session-id (fn [_] sid)]
          (sessions/reset-sessions!)
          (let [ag (reify ai.brainyard.agent.core.protocol/IAgent
                     (session-id [_] sid)
                     java.io.Closeable (close [_] nil))]
            (sessions/create-session! {:id 0 :label "main0" :agent ag :agent-id :a
                                       :agent-instances [ag] :skip-agent-creation true})

            (testing "a set renames the tab AND persists the label"
              (let [resp (op ag {:label "  release work  "})]
                (is (= :ok (:status resp)))
                (is (= "release work" (:label resp)))
                (is (true? (:live-tab resp)))
                (is (= "release work" (:label (sessions/get-session 0))))
                (is (= "release work" (persist/session-label sid))
                    "`by sessions list` reads this")))

            (testing "a blank/omitted label is REJECTED, leaving both surfaces alone"
              (doseq [req [{:label "   "} {:label nil} {}]]
                (let [resp (op ag req)]
                  (is (= :error (:status resp)) (str "rejected: " (pr-str req)))
                  (is (re-find #":label" (:error resp)))
                  (is (= "release work" (:label (sessions/get-session 0)))
                      "tab keeps its label")
                  (is (= "release work" (persist/session-label sid))
                      "a missing label must never wipe the persisted one"))))

            (testing "with no live tab the persisted label is still written"
              (sessions/reset-sessions!)
              (let [resp (op ag {:label "headless"})]
                (is (= :ok (:status resp)))
                (is (false? (:live-tab resp)))
                (is (= "headless" (persist/session-label sid)))))

            (testing "an unresolvable session-id is rejected without persisting"
              (with-redefs [agent/session-id (fn [_] nil)]
                (is (= :error (:status (op ag {:label "x"}))))
                (is (= "headless" (persist/session-label sid)))))))))))

(deftest switch-session-op
  (testing ":switch-session makes one co-hosted session the active tab"
    ;; Two live tabs: index 3 is active, index 7 is the switch target. The op is
    ;; process-level, so it's driven directly — no socket/TUI needed.
    (let [switched (atom [])
          bar      (atom 0)
          tabs     [{:id 3 :agent-session-id "agt-a" :label "main1" :defagent-id :coact-agent}
                    {:id 7 :agent-session-id "agt-b" :label "main2" :defagent-id :mcp-agent}]
          op       #'core/handle-switch-session-op]
      (with-redefs [sessions/session-list          (fn [] tabs)
                    sessions/active-idx            (fn [] 3)
                    sessions/switch-to!            (fn [idx] (swap! switched conj idx))
                    tui-session/update-status-bar! (fn ([] (swap! bar inc))
                                                     ([_] (swap! bar inc)))]
        (testing "a blank :session-id is rejected"
          (is (= :error (:status (op {}))))
          (is (= :error (:status (op {:session-id "  "}))))
          (is (empty? @switched)))

        (testing "an unknown session-id is rejected"
          (let [resp (op {:session-id "agt-nope"})]
            (is (= :error (:status resp)))
            (is (re-find #"no live session" (:error resp)))
            (is (empty? @switched))))

        (testing "a match switches by TAB INDEX and repaints the status bar"
          (let [resp (op {:session-id "agt-b"})]
            (is (= {:status :ok :switched "agt-b" :index 7
                    :label "main2" :defagent-id "mcp-agent"}
                   resp))
            (is (= [7] @switched) "switch-to! takes the tab index, not the session-id")
            (is (= 1 @bar))))

        (testing "switching to the already-active session is a no-op"
          (let [resp (op {:session-id "agt-a"})]
            (is (= :ok (:status resp)))
            (is (true? (:already-active resp)))
            (is (= [7] @switched) "no second switch-to!")))))))

(deftest resume-session-op
  (testing ":resume-session adopts a PERSISTED session into this running host"
    ;; Process-level like its siblings, so it is driven directly. Everything
    ;; that touches disk or builds a real agent is stubbed; what is under test is
    ;; the decision table (refuse / already-live / resume) and that a resume
    ;; carries the session's OWN identity rather than a fresh one.
    (let [op       #'core/handle-resume-session-op
          hydrated (atom [])
          built    (atom [])
          watched  (atom [])
          history  (atom [])
          sid      "agt-old-1"
          fake-ag  {:agent-id :coact-agent/inst-9 :!session (atom {})}]
      (sessions/reset-sessions!)
      (with-redefs [;; create-session! files the tab under the id it reads OFF
                    ;; the agent, which is the whole point of the op — so the
                    ;; stand-in agent has to answer with the resumed id.
                    agent/session-id                   (fn [_] sid)
                    persist/list-sessions              (fn [] [sid])
                    persist/held-by-other-live-process? (fn [_] false)
                    persist/owner-pid                  (fn [_] 4242)
                    persist/read-meta                  (fn [_] {:defagent-id :mcp-agent
                                                                :label "old work"
                                                                :model "opus"})
                    persist/read-snap                  (fn [& _] nil)
                    persist/file-of                    (fn [s _] (java.io.File. (str "/tmp/" s "/ask.sock")))
                    core/hydrate-persisted-session!    (fn [s] (swap! hydrated conj s) 17)
                    core/create-tui-agent!             (fn [a & kvs]
                                                         (swap! built conj [a (apply hash-map kvs)])
                                                         fake-ag)
                    core/load-input-history-for-session! (fn [s] (swap! history conj s))
                    tui-session/set-agent!             (fn [_ inst & kvs]
                                                         (swap! watched conj
                                                                [inst (apply hash-map kvs)]))]

        (testing "a blank :session-id is rejected"
          (doseq [req [{} {:session-id "  "}]]
            (is (= :error (:status (op req))) (str "rejected: " (pr-str req))))
          (is (empty? @hydrated) "nothing was restored"))

        (testing "an id with no persisted session is rejected"
          (let [resp (op {:session-id "agt-nope"})]
            (is (= :error (:status resp)))
            (is (re-find #"no persisted session" (:error resp)))
            (is (empty? @hydrated))))

        (testing "a session open in ANOTHER live process is refused, naming the owner"
          (with-redefs [persist/held-by-other-live-process? (fn [_] true)]
            (let [resp (op {:session-id sid})]
              (is (= :error (:status resp)))
              (is (re-find #"another live" (:error resp)))
              (is (re-find #"4242" (:error resp)) "the owning pid is reported")
              (is (empty? @hydrated) "co-ownership never begins"))))

        (testing "a resume hydrates, builds the agent on the SESSION'S OWN id, and adds a tab"
          (let [resp (op {:session-id sid})]
            (is (= :ok (:status resp)))
            (is (= sid (:session-id resp)))
            (is (= 17 (:messages resp)) "the restored message count is reported")
            (is (= "mcp-agent" (:defagent-id resp)) "the persisted agent type, not a default")
            (is (= "old work" (:label resp)) "the persisted label")
            (is (= "opus" (:model resp))
                "reported rather than applied — configure-default-lm! is process-global")
            (is (nil? (:already-live resp)))
            (is (= [sid] @hydrated))
            (let [[agent-kw opts] (first @built)]
              (is (= :mcp-agent agent-kw))
              (is (= sid (:session-id opts))
                  "resumed under its own id — a fresh one would be a new session"))
            (is (= [sid] @history) "input recall is loaded for the resumed tab")
            (let [idx (:index resp)]
              (is (= sid (:agent-session-id (sessions/get-session idx))))
              (is (= [[(:agent-id fake-ag) {:session-idx idx}]] @watched)
                  "watches attached, so its output routes to its own tab"))))

        (testing "resuming one already live HERE is the answer, not a failure"
          (let [resp (op {:session-id sid})]
            (is (= :ok (:status resp)))
            (is (true? (:already-live resp)))
            (is (= [sid] @hydrated) "no second hydration")
            (is (= 1 (count @built)) "no second agent")))

        (testing ":label overrides the persisted one, for a rename-as-you-resume"
          (sessions/reset-sessions!)
          (let [resp (op {:session-id sid :label "  revived  "})]
            (is (= "revived" (:label resp)))
            (is (= "revived" (:label (sessions/get-session (:index resp)))))))))))
