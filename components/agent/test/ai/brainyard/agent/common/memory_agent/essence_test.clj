;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.common.memory-agent.essence-test
  "Phase 3 — essence capture surface.

   Covers:
   - EssenceExtraction signature compiles and carries the expected
     output schema.
   - `memory$essence-extract` tool dispatches through chain-of-thought
     and validates output (uses with-redefs to stub clj-llm).
   - `consolidation-cadence-handler` counts turns and fires (or elides)
     the batch reducer on `:agent.ask/post` based on the eligibility
     predicate (memory-agent self-skip, non-root skip, flag off skip)
     and the every-Nth-turn cadence."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.string :as str]
            [ai.brainyard.agent.common.background :as bg]
            [ai.brainyard.agent.common.memory-agent.commands :as ma-cmds]
            [ai.brainyard.agent.common.memory-agent.hooks :as ma-hooks]
            [ai.brainyard.agent.common.memory-agent.signatures :as ma-sig]
            [ai.brainyard.agent.core.config :as core-config]
            [ai.brainyard.agent.core.hooks :as hooks]
            [ai.brainyard.agent.core.protocol :as proto]
            [ai.brainyard.clj-llm.interface :as clj-llm]))

;; ============================================================================
;; Stub agent — mirrors the one in commands-test
;; ============================================================================

(defrecord StubAgent [agent-id !state !session]
  proto/IAgent
  (agent-id [_] agent-id)
  (agent-name [_] (str agent-id))
  (agent-description [_] "stub")
  (user-id [_] (some-> !session deref :user-id))
  (session-id [_] (some-> !session deref :session-id))
  (defagent-type [_]
    (if-let [ns (and (keyword? agent-id) (namespace agent-id))]
      (keyword ns)
      agent-id))
  (process [_ _ _] nil)
  (get-tools [_] [])
  (get-state [_] @!state))

(defn- make-stub
  "Test stub mirroring setup-agent's routing: schema-key entries in
   `:config` land in st-memory-init :config; non-schema entries land in
   the agent record's :config slot."
  [agent-id & {:keys [user-id session-id config st-mem-config parent-agent total-turns
                      ;; Legacy alias retained for callers still using
                      ;; the pre-Phase-2 :runtime-config kwarg.
                      runtime-config]
               :or {user-id "user-x" session-id "s-x"
                    config {} st-mem-config {} runtime-config {}}}]
  (let [schema-keys core-config/config-keys
        cfg-schema-half    (select-keys config schema-keys)
        cfg-nonschema-half (apply dissoc config schema-keys)
        st-mem-config-full (merge cfg-schema-half st-mem-config runtime-config)]
    (->StubAgent agent-id
                 (atom {:config         cfg-nonschema-half
                        :st-memory-init (atom {:config st-mem-config-full})
                        :runtime        (when parent-agent
                                          {:parent-agent parent-agent})})
                 (atom {:user-id user-id :session-id session-id
                        :total-turns (or total-turns 0)}))))

;; ============================================================================
;; EssenceExtraction signature
;; ============================================================================

(deftest essence-extraction-signature-test
  (testing "signature is compiled and carries the dspy metadata"
    (is (some? ma-sig/EssenceExtraction))
    (is (:dspy/signature (meta #'ma-sig/EssenceExtraction))))

  (testing "input keys match the documented shape"
    (let [iks (set (:input-keys ma-sig/EssenceExtraction))]
      (is (contains? iks :turn-summary))
      (is (contains? iks :turn-messages))
      (is (contains? iks :recent-episodes))
      (is (contains? iks :user-id))))

  (testing "outputs declare :essences"
    (let [oks (set (:output-keys ma-sig/EssenceExtraction))]
      (is (contains? oks :essences))))

  (testing "instructions surface the cap (\"three\") and the empty-default"
    (let [instr (str (:instructions ma-sig/EssenceExtraction))]
      (is (str/includes? instr "THREE"))
      (is (str/includes? instr "Empty output is the COMMON case"))
      (is (str/includes? instr "fact"))
      (is (str/includes? instr "observation"))
      (is (str/includes? instr "user-context")))))

;; ============================================================================
;; memory$essence-extract — tool dispatch
;; ============================================================================

(deftest essence-extract-registered-test
  (testing "memory$essence-extract is in the roster + the guard set"
    (is (contains? ma-cmds/all-tool-ids :memory$essence-extract))
    (is (contains? ma-cmds/write-guarded-tools "memory$essence-extract"))))

(deftest essence-extract-happy-path-test
  (testing "tool returns Malli-validated essences when chain-of-thought succeeds"
    (let [agent (make-stub :memory-agent/test :user-id "alice")
          stub-essences [{:kind "fact"
                          :content "User prefers polylith layout"
                          :tags ["arch"]
                          :confidence 0.9
                          :source-ids ["ep1"]
                          :rationale "user said so explicitly"}]]
      (with-redefs [clj-llm/chain-of-thought
                    (fn [sig inputs & _]
                      (is (= ma-sig/EssenceExtraction sig))
                      (is (= "summary text" (:turn-summary inputs)))
                      (is (= "alice" (:user-id inputs)))
                      {:outputs {:essences stub-essences}
                       :reasoning "spotted an explicit preference"})]
        (proto/with-agent agent
          (let [r (ma-cmds/memory$essence-extract
                   :turn-summary "summary text"
                   :turn-messages "a\nb"
                   :recent-episodes "ep1 fact x"
                   :user-id "alice")]
            (is (= stub-essences (:essences r)))
            (is (= "spotted an explicit preference" (:reasoning r)))
            (is (nil? (:error r)))))))))

(deftest essence-extract-empty-output-test
  (testing "empty essences vector flows through cleanly (most turns)"
    (let [agent (make-stub :memory-agent/test)]
      (with-redefs [clj-llm/chain-of-thought
                    (fn [_ _ & _] {:outputs {:essences []}
                                   :reasoning "nothing worth lifting"})]
        (proto/with-agent agent
          (let [r (ma-cmds/memory$essence-extract :turn-summary "")]
            (is (= [] (:essences r)))
            (is (nil? (:error r)))))))))

(deftest essence-extract-error-surface-test
  (testing "chain-of-thought exceptions surface as :error, not as throws"
    (let [agent (make-stub :memory-agent/test)]
      (with-redefs [clj-llm/chain-of-thought
                    (fn [& _] (throw (ex-info "llm down" {})))]
        (proto/with-agent agent
          (let [r (ma-cmds/memory$essence-extract :turn-summary "x")]
            (is (string? (:error r)))
            (is (str/includes? (:error r) "llm down"))))))))

;; ============================================================================
;; consolidation-cadence-handler — :agent.ask/post
;; ============================================================================

(defn- reset-counters! []
  (reset! @#'ma-hooks/!turn-counters {}))

(deftest consolidation-eligible-cases-test
  (testing "memory-agent never consolidates on its own turn"
    (let [ag (make-stub :memory-agent/abc
                        :config {:enable-memory-consolidation true})]
      (is (false? (boolean (ma-hooks/consolidation-eligible? ag))))))

  (testing "sub-agents (with :parent-agent) are not eligible — the root is"
    (let [parent (make-stub :coact-agent/root)
          child  (make-stub :coact-agent/child
                            :config {:enable-memory-consolidation true}
                            :parent-agent parent)]
      (is (false? (boolean (ma-hooks/consolidation-eligible? child))))))

  (testing "both flags off → not eligible even on a root coact-agent"
    (let [ag (make-stub :coact-agent/root
                        :config {:enable-memory-consolidation false
                                 :enable-graph-memory false})]
      (is (false? (boolean (ma-hooks/consolidation-eligible? ag))))))

  (testing "consolidation flag on + root coact-agent → eligible (graph off)"
    (let [ag (make-stub :coact-agent/root
                        :config {:enable-memory-consolidation true
                                 :enable-graph-memory false})]
      (is (true? (boolean (ma-hooks/consolidation-eligible? ag))))))

  (testing "graph memory on IMPLIES consolidation even with the flag off"
    (let [ag (make-stub :coact-agent/root
                        :config {:enable-memory-consolidation false
                                 :enable-graph-memory true})]
      (is (true? (boolean (ma-hooks/consolidation-eligible? ag)))))))

(deftest consolidation-cadence-fires-every-n-test
  (testing "handler fires the reducer only on every Nth eligible turn"
    (reset-counters!)
    (let [fires (atom 0)
          root  (make-stub :coact-agent/root
                           :session-id "s-cadence"
                           :config {:enable-memory-consolidation true
                                    :memory-consolidate-every-n-turns 3})]
      (with-redefs [ma-hooks/run-consolidation! (fn [_] (swap! fires inc) {:produced 0})]
        ;; 6 completed turns at N=3 → fire on turn 3 and turn 6. Consolidations
        ;; are single-flight per session, so let each one land before the next
        ;; boundary — see `consolidation-cadence-single-flight-test` for what
        ;; happens when one is still running.
        (dotimes [_ 6]
          (ma-hooks/consolidation-cadence-handler {:agent root})
          (bg/await-quiet! 5000)))
      (is (= 2 @fires) "reducer ran exactly twice across 6 turns at N=3"))))

(deftest consolidation-cadence-single-flight-test
  (testing "a boundary reached while a consolidation is still running is dropped"
    (reset-counters!)
    (let [fires (atom 0)
          gate  (promise)
          root  (make-stub :coact-agent/root
                           :session-id "s-single-flight"
                           :config {:enable-memory-consolidation true
                                    ;; every turn is a boundary
                                    :memory-consolidate-every-n-turns 1})]
      (with-redefs [ma-hooks/run-consolidation!
                    (fn [_] (swap! fires inc) (deref gate 5000 nil) {:produced 0})]
        (ma-hooks/consolidation-cadence-handler {:agent root})
        ;; wait for the first reduce to actually be running
        (let [deadline (+ (System/currentTimeMillis) 5000)]
          (while (and (zero? @fires) (< (System/currentTimeMillis) deadline))
            (Thread/sleep 25)))
        (is (= 1 @fires))

        ;; three more boundaries while it is still inside the reducer
        (dotimes [_ 3] (ma-hooks/consolidation-cadence-handler {:agent root}))
        (is (= 1 @fires)
            "two overlapping reduces over ONE session's L2→L3 is a race, so the extra boundaries are dropped")
        (deliver gate :go)
        (bg/await-quiet! 5000))

      (testing "and the next boundary runs normally once it is quiet"
        (with-redefs [ma-hooks/run-consolidation! (fn [_] (swap! fires inc) {:produced 0})]
          (ma-hooks/consolidation-cadence-handler {:agent root})
          (bg/await-quiet! 5000))
        (is (= 2 @fires)
            "nothing is lost by a dropped boundary — the next reduce covers the accumulated episodes")))))

(deftest consolidation-cadence-elides-when-off-test
  (testing "flag off → counter untouched, reducer never runs"
    (reset-counters!)
    (let [fires (atom 0)
          root  (make-stub :coact-agent/root
                           :session-id "s-off"
                           :config {:enable-memory-consolidation false
                                    :enable-graph-memory false
                                    :memory-consolidate-every-n-turns 1})]
      (with-redefs [ma-hooks/run-consolidation! (fn [_] (swap! fires inc) nil)]
        (dotimes [_ 5]
          (ma-hooks/consolidation-cadence-handler {:agent root}))
        (Thread/sleep 100))
      (is (= 0 @fires))
      (is (nil? (get @@#'ma-hooks/!turn-counters "s-off"))))))

(deftest consolidation-cadence-job-timeout-is-mode-aware-test
  (testing "the graph reducer gets a far larger ceiling than the heuristic one"
    ;; A live graph reduce over an 88-episode window took 261s — 87% of the
    ;; background layer's generic 300s default. Inheriting that default would
    ;; cancel a longer session's reduce mid-flight.
    (let [timeout-of (fn [graph?]
                       (reset-counters!)
                       (let [captured (atom nil)
                             root (make-stub :coact-agent/root
                                             :session-id (str "s-timeout-" graph?)
                                             :config {:enable-memory-consolidation true
                                                      :enable-graph-memory graph?
                                                      :memory-consolidate-every-n-turns 1})]
                         (with-redefs [bg/run-off-turn! (fn [& {:keys [timeout-ms]}]
                                                          (reset! captured timeout-ms)
                                                          :submitted)]
                           (ma-hooks/consolidation-cadence-handler {:agent root}))
                         @captured))
          heuristic (timeout-of false)
          graph     (timeout-of true)]
      (is (some? heuristic) "the handler passes an explicit ceiling, never inherits the default")
      (is (> graph heuristic) "graph mode gets the larger ceiling")
      (is (> graph 261000)
          "and comfortably exceeds the 261s a real graph reduce actually took"))))

;; ============================================================================
;; Cadence offload — detached child vs in-process task
;; ============================================================================

(defn- cadence-routing
  "Fire one boundary and report where the work went: {:detached args-or-nil
   :in-process bool}. `offload` is the launcher (nil = none installed)."
  [{:keys [graph? offload session-id]}]
  (reset-counters!)
  (let [detached   (atom nil)
        in-process (atom false)
        root (make-stub :coact-agent/root
                        :session-id (or session-id "s-offload")
                        :config {:enable-memory-consolidation true
                                 :enable-graph-memory graph?
                                 :memory-consolidate-every-n-turns 1})]
    (ma-hooks/set-offload-fn! (when offload
                                (fn [args] (reset! detached args) (offload args))))
    (try
      (with-redefs [bg/run-off-turn! (fn [& _] (reset! in-process true) :submitted)]
        (ma-hooks/consolidation-cadence-handler {:agent root}))
      {:detached @detached :in-process @in-process}
      (finally (ma-hooks/set-offload-fn! nil)))))

(deftest cadence-offloads-graph-reduces-to-a-detached-child-test
  (testing "graph mode + a launcher → detached child, not an in-process job"
    ;; A graph reduce measured 261s; in-process it is cancelled outright if the
    ;; user quits, throwing away LLM spend already paid.
    (let [r (cadence-routing {:graph? true :offload (constantly 4242)})]
      (is (some? (:detached r)) "the launcher was invoked")
      (is (= :community (:reducer (:detached r))) "scoped to the community reducer")
      (is (= "s-offload" (:session-id (:detached r))) "and to THIS session")
      (is (false? (:in-process r)) "no in-process job was submitted as well")))

  (testing "heuristic mode → in-process, even with a launcher installed"
    ;; LLM-free and returns in milliseconds — nothing to outlive the session.
    (let [r (cadence-routing {:graph? false :offload (constantly 4242)})]
      (is (nil? (:detached r)))
      (is (true? (:in-process r)))))

  (testing "no launcher installed (tests / non-TUI) → in-process"
    (let [r (cadence-routing {:graph? true :offload nil})]
      (is (nil? (:detached r)))
      (is (true? (:in-process r)))))

  (testing "a launcher that declines falls back to consolidating in-process"
    (let [r (cadence-routing {:graph? true :offload (constantly nil)})]
      (is (some? (:detached r)) "it was asked")
      (is (true? (:in-process r)) "and the work still happened")))

  (testing "a launcher that throws never escapes the hook"
    (let [r (cadence-routing {:graph? true
                              :offload (fn [_] (throw (ex-info "spawn failed" {})))})]
      (is (true? (:in-process r)) "the reduce still ran in-process"))))

(deftest cadence-detached-single-flight-test
  (testing "a boundary is dropped while this session's detached child is alive"
    ;; The task-manager guard cannot see a child process, so without a separate
    ;; check two children would race the watermark over one session's L2→L3.
    (reset-counters!)
    (let [spawns (atom 0)
          in-process (atom 0)
          ;; our own pid is guaranteed alive
          live-pid (.pid (java.lang.ProcessHandle/current))
          root (make-stub :coact-agent/root
                          :session-id "s-detach-sf"
                          :config {:enable-memory-consolidation true
                                   :enable-graph-memory true
                                   :memory-consolidate-every-n-turns 1})]
      (ma-hooks/set-offload-fn! (fn [_] (swap! spawns inc) live-pid))
      (try
        (with-redefs [bg/run-off-turn! (fn [& _] (swap! in-process inc) :submitted)]
          (ma-hooks/consolidation-cadence-handler {:agent root})
          (is (= 1 @spawns) "first boundary spawns a child")
          (dotimes [_ 3] (ma-hooks/consolidation-cadence-handler {:agent root}))
          (is (= 1 @spawns) "later boundaries are dropped while it is alive")
          (is (zero? @in-process) "and are NOT quietly rerouted in-process"))
        (finally (ma-hooks/set-offload-fn! nil))))))

(deftest consolidation-cadence-handler-registered-test
  (testing "consolidation-cadence hook is registered on :agent.ask/post at namespace load"
    ;; Re-install in case other tests reset the registry.
    (ma-hooks/install-consolidation-cadence!)
    (let [entries (hooks/list-hooks :agent.ask/post)
          ids     (set (map :id entries))]
      (is (contains? ids :ai.brainyard.agent.common.memory-agent.hooks/consolidation-cadence)))))

;; ============================================================================
;; session-end-flush-handler — :agent.instance/closed
;; ============================================================================

(deftest session-end-flush-fires-on-eligible-root-test
  (testing "root close with counted turns → final consolidation runs, counter cleared"
    (reset-counters!)
    (let [fires (atom 0)
          root  (make-stub :coact-agent/root
                           :session-id "s-end"
                           :config {:enable-memory-consolidation true})]
      ;; Simulate two cadence ticks having counted turns for this session.
      (swap! @#'ma-hooks/!turn-counters assoc "s-end" 5)
      (with-redefs [ma-hooks/run-consolidation! (fn [_] (swap! fires inc) {:produced 1})]
        (ma-hooks/session-end-flush-handler {:agent root})
        (Thread/sleep 100))
      (is (= 1 @fires) "final consolidation ran once on root close")
      (is (nil? (get @@#'ma-hooks/!turn-counters "s-end")) "session counter cleared"))))

(deftest session-end-flush-skips-when-no-turns-test
  (testing "root close with zero counted turns → no consolidation, counter still cleared"
    (reset-counters!)
    (let [fires (atom 0)
          root  (make-stub :coact-agent/root
                           :session-id "s-empty"
                           :config {:enable-memory-consolidation true})]
      (with-redefs [ma-hooks/run-consolidation! (fn [_] (swap! fires inc) nil)]
        (ma-hooks/session-end-flush-handler {:agent root})
        (Thread/sleep 50))
      (is (= 0 @fires)))))

(deftest session-end-flush-skips-when-off-test
  (testing "flag off → no flush even on a root close"
    (reset-counters!)
    (let [fires (atom 0)
          root  (make-stub :coact-agent/root
                           :session-id "s-off2"
                           :config {:enable-memory-consolidation false
                                    :enable-graph-memory false})]
      (swap! @#'ma-hooks/!turn-counters assoc "s-off2" 9)
      (with-redefs [ma-hooks/run-consolidation! (fn [_] (swap! fires inc) nil)]
        (ma-hooks/session-end-flush-handler {:agent root})
        (Thread/sleep 50))
      (is (= 0 @fires)))))

(deftest session-end-flush-skips-sub-agent-test
  (testing "sub-agent (with :parent-agent) close → no flush; the root handles it"
    (reset-counters!)
    (let [fires  (atom 0)
          parent (make-stub :coact-agent/root)
          child  (make-stub :coact-agent/child
                            :session-id "s-sub"
                            :config {:enable-memory-consolidation true}
                            :parent-agent parent)]
      (swap! @#'ma-hooks/!turn-counters assoc "s-sub" 4)
      (with-redefs [ma-hooks/run-consolidation! (fn [_] (swap! fires inc) nil)]
        (ma-hooks/session-end-flush-handler {:agent child})
        (Thread/sleep 50))
      (is (= 0 @fires)))))

(deftest session-end-flush-handler-registered-test
  (testing "session-end-flush hook is registered on :agent.instance/closed at namespace load"
    (ma-hooks/install-session-end-flush!)
    (let [entries (hooks/list-hooks :agent.instance/closed)
          ids     (set (map :id entries))]
      (is (contains? ids :ai.brainyard.agent.common.memory-agent.hooks/session-end-flush)))))

;; ============================================================================
;; Reducer routing follows the MANAGER, not the live config
;; ============================================================================
;;
;; `:enable-graph-memory` is read once, at `create-memory-manager`, from the
;; 0-arity global — the manager never sees agent config. These five hook sites
;; used to re-read it live every turn, so a mid-session change flipped them into
;; graph mode while the manager held no graph fns, and the community reducer ran
;; against a storage-only graph. They now read `:graph-enabled?` off the manager,
;; which makes that disagreement unreachable.

(defn- with-manager
  "Attach a memory manager to a stub agent. A plain map suffices — `graph-mode?`
   only reads `:graph-enabled?`, and the real MemoryManager record carries it as
   an assoc'd field."
  [ag graph-enabled?]
  (swap! (:!state ag) assoc :memory-manager {:graph-enabled? graph-enabled?})
  ag)

(deftest graph-mode-follows-the-manager-not-the-config-test
  (let [graph-mode? @#'ma-hooks/graph-mode?]

    (testing "manager built WITHOUT graph wins over a config that now says true"
      (let [ag (with-manager (make-stub :coact-agent/root
                                        :config {:enable-graph-memory true})
                             false)]
        (is (false? (graph-mode? ag))
            "this is the §1.5 bug: the live flag must not flip the reducer path
             when the manager has no :extract-fn/:embed-fn")))

    (testing "manager built WITH graph wins over a config that now says false"
      (let [ag (with-manager (make-stub :coact-agent/root
                                        :config {:enable-graph-memory false})
                             true)]
        (is (true? (graph-mode? ag)))))

    (testing "no manager bound → the configured feature is the only truth"
      (is (false? (graph-mode? (make-stub :coact-agent/root
                                          :config {:enable-graph-memory false}))))
      (is (true? (graph-mode? (make-stub :coact-agent/root
                                         :config {:enable-graph-memory true})))))))

(deftest job-timeout-follows-the-manager-test
  (testing "the mode-aware ceiling is chosen by the manager, not the live flag"
    (let [timeout-of (fn [mgr-graph? cfg-graph?]
                       (reset-counters!)
                       (let [captured (atom nil)
                             root (with-manager
                                    (make-stub :coact-agent/root
                                               :session-id (str "s-mgr-" mgr-graph? "-" cfg-graph?)
                                               :config {:enable-memory-consolidation true
                                                        :enable-graph-memory cfg-graph?
                                                        :memory-consolidate-every-n-turns 1})
                                    mgr-graph?)]
                         (with-redefs [bg/run-off-turn! (fn [& {:keys [timeout-ms]}]
                                                          (reset! captured timeout-ms)
                                                          :submitted)]
                           (ma-hooks/consolidation-cadence-handler {:agent root}))
                         @captured))]
      (is (= (timeout-of true true) (timeout-of true false))
          "manager graph=true → graph ceiling regardless of the live flag")
      (is (= (timeout-of false true) (timeout-of false false))
          "manager graph=false → heuristic ceiling regardless of the live flag")
      (is (> (timeout-of true false) (timeout-of false true))
          "and the two ceilings still differ by mode"))))
