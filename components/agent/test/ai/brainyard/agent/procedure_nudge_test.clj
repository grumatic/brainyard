;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.procedure-nudge-test
  "Locate / render / inject for procedural-graph guidance, plus the
   trajectory miner that seeds the graph.

   Design: docs/design/procedural-graph-implementation.md §7"
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.string :as str]
            [ai.brainyard.agent.common.procedure-nudge :as pn]
            [ai.brainyard.agent.common.procedure-seed :as seed]
            [ai.brainyard.agent.core.config :as config]
            [ai.brainyard.agent.core.hooks :as hooks]
            [ai.brainyard.agent.core.protocol :as proto]
            [ai.brainyard.memory.core.sqlite :as sqlite]
            [ai.brainyard.memory.core.unified-store :as us]
            [ai.brainyard.memory.interface :as mem]))

(def ^:dynamic *ds* nil)
(def ^:dynamic *store* nil)

(defn with-test-db [f]
  (let [ds (sqlite/create-datasource ":memory:")]
    (sqlite/init-schema! ds)
    (binding [*ds* ds *store* (us/create-unified-store :user-id "u1" :ds ds)]
      (try (f) (finally (.close ds))))))

(use-fixtures :each with-test-db)

;; A minimal stand-in carrying only what the drain path touches: the per-turn
;; bt-st-memory and the memory manager. Deliberately not a real Agent — this
;; exercises the guidance path, not agent construction.
(defrecord StubAgent [!bt !mm !cfg]
  proto/IAgentBTIntegration
  (get-bt-st-memory [_] !bt)
  proto/IAgentMemoryAccess
  (get-memory-manager [_] @!mm))

(defn- stub-agent
  ([] (stub-agent {}))
  ([cfg] (->StubAgent (atom {}) (atom *store*) (atom cfg))))

(defn- with-config
  "Run `f` with `get-config` answering from `m`, defaulting the gate ON."
  [m f]
  (with-redefs [config/get-config
                (fn [_agent k] (get (merge {:enable-procedure-guidance true} m) k))]
    (f)))

(defn- node! [nm & {:as opts}]
  (mem/procedure-upsert-node! *store* nil (merge {:name nm :kind :tool} opts)))

(defn- edge! [src dst relation & {:as opts}]
  (mem/procedure-upsert-edge! *store* nil (merge {:src-id (:id src) :dst-id (:id dst)
                                                  :relation relation} opts)))

;; =====================================================
;; Locate
;; =====================================================

(deftest probe-resolution-test
  (testing "a tool recorded by the hook wins"
    (is (= "edit$apply" (pn/probe-for-iteration (atom {:last-procedure "edit$apply"})))))

  (testing "a code block that invoked NO tool localizes NOWHERE"
    ;; It used to probe code:<lang>. Measured over 87 real recorded turns that
    ;; named no procedure -- 23 of 41 transitions were code:bash -> code:bash,
    ;; a retry loop -- so the graph has no such node and the probe could only
    ;; ever miss.
    (is (nil? (pn/probe-for-iteration
               (atom {:last-code-results [{:lang "clojure"} {:lang "bash"}]}))))
    (is (nil? (pn/probe-for-iteration
               (atom {:last-code-results [{:lang "bash"}] :iteration-count 4})))))

  (testing "a tool called FROM a code block still localizes -- the hook saw it"
    (is (= "task$wait"
           (pn/probe-for-iteration
            (atom {:last-procedure "task$wait" :last-code-results [{:lang "bash"}]})))))

  (testing "the first iteration with no action at all localizes on Start"
    (is (= "Start" (pn/probe-for-iteration (atom {:iteration-count 1})))))

  (testing "a LATER iteration with no action localizes nowhere"
    ;; Falling back to Start mid-turn would guide from the top of the graph
    ;; after the agent has already moved.
    (is (nil? (pn/probe-for-iteration (atom {:iteration-count 5}))))))

(deftest note-procedure-overwrites-test
  (let [a (stub-agent)]
    (with-config {}
      (fn []
        (pn/note-procedure! a "read-file")
        (pn/note-procedure! a "edit$apply")
        (pn/note-procedure! a "build$run")
        (testing "an iteration emitting N tool calls localizes on the LAST"
          (is (= "build$run" (:last-procedure @(proto/get-bt-st-memory a)))))
        (testing "and the multiplicity is counted, so the divergence is measurable"
          (is (= 3 (:procedure-action-count @(proto/get-bt-st-memory a)))))))))

(deftest hook-registration-is-not-a-latch-test
  (testing "re-registering after a registry wipe re-arms the observer"
    ;; ~24 test namespaces call reset-hooks!; a defonce latch would silently
    ;; disarm this for the life of the JVM.
    (hooks/reset-hooks!)
    (pn/ensure-global-hooks!)
    (let [a (stub-agent)]
      (with-config {}
        (fn []
          (hooks/fire! :agent.tool-use/post {:agent a :tool-name "edit$apply"})
          (is (= "edit$apply" (:last-procedure @(proto/get-bt-st-memory a)))))))))

(deftest hook-respects-the-gate-test
  (testing "with the gate off the observer records nothing"
    (hooks/reset-hooks!)
    (pn/ensure-global-hooks!)
    (let [a (stub-agent)]
      (with-redefs [config/get-config (fn [_ k] (when-not (= k :enable-procedure-guidance) nil))]
        (hooks/fire! :agent.tool-use/post {:agent a :tool-name "edit$apply"})
        (is (nil? (:last-procedure @(proto/get-bt-st-memory a))))))))

;; =====================================================
;; Render
;; =====================================================

(deftest render-guidance-test
  (let [e {:src_name "edit$apply" :dst_name "read-file" :relation :verifies :depth 0
           :condition "the apply reported success"
           :guidance  "re-read the region before reporting done"
           :pitfalls  "a successful apply is not a successful edit"}]

    (testing "all three Phi fields render under their transition"
      (let [s (pn/render-guidance "edit$apply" [e] 600)]
        (is (str/includes? s "edit$apply` —verifies→ `read-file"))
        (is (str/includes? s "when:  the apply reported success"))
        (is (str/includes? s "do:    re-read the region"))
        (is (str/includes? s "avoid: a successful apply is not"))))

    (testing "a seeded edge with EMPTY Phi renders as bare topology, no blank labels"
      (let [s (pn/render-guidance "a" [{:src_name "a" :dst_name "b" :relation :precedes
                                        :depth 0 :condition "" :guidance nil :pitfalls ""}]
                                  600)]
        (is (str/includes? s "`a` —precedes→ `b`"))
        (is (not (str/includes? s "when:")))
        (is (not (str/includes? s "do:")))
        (is (not (str/includes? s "avoid:")))))

    (testing "deeper rings collapse to one `then:` line rather than a subgraph"
      (let [s (pn/render-guidance "a" [{:src_name "a" :dst_name "b" :relation :precedes :depth 0}
                                       {:src_name "b" :dst_name "c" :relation :precedes :depth 1}
                                       {:src_name "b" :dst_name "d" :relation :precedes :depth 1}]
                                  600)]
        (is (str/includes? s "then: `c`, `d`"))
        (is (= 1 (count (filter #(str/starts-with? % "  then:") (str/split-lines s)))))))

    (testing "an empty neighborhood renders nil, not a bare header"
      (is (nil? (pn/render-guidance "edit$apply" [] 600))))

    (testing "the cap truncates on a TRANSITION boundary, never mid-sentence"
      (let [s (pn/render-guidance "edit$apply" [e] 80)]
        ;; Only the header would fit, so there is nothing worth saying.
        (is (nil? s)))
      (let [edges [{:src_name "a" :dst_name "b" :relation :precedes :depth 0}
                   {:src_name "a" :dst_name "cccccccccccccccccccccccccccccc"
                    :relation :precedes :depth 0}]
            s (pn/render-guidance "a" edges 60)]
        (is (str/includes? s "`a` —precedes→ `b`"))
        (is (not (str/includes? s "cccccccccccccccccccccccccccccc")))
        (is (<= (count s) 60))))))

;; =====================================================
;; Extract + inject (end to end against a real store)
;; =====================================================

(deftest drain-emits-guidance-for-a-known-procedure-test
  (let [a (node! "edit$apply") b (node! "read-file") c (node! "build$run")]
    (edge! a b :verifies :guidance "re-read the region")
    (edge! b c :precedes)
    (let [ag (stub-agent)]
      (swap! (proto/get-bt-st-memory ag) assoc :last-procedure "edit$apply")
      (with-config {}
        (fn []
          (let [s (pn/drain-iteration-notice! ag (proto/get-bt-st-memory ag))]
            (testing "the localized out-neighborhood reaches the notice"
              (is (str/includes? s "observed after `edit$apply`"))
              (is (str/includes? s "re-read the region"))
              (is (str/includes? s "then: `build$run`")))
            (testing "the probe is CLEARED, so a later iteration cannot re-guide from it"
              (is (nil? (:last-procedure @(proto/get-bt-st-memory ag))))
              (is (nil? (pn/drain-iteration-notice! ag (proto/get-bt-st-memory ag)))))))))))

(deftest drain-on-a-match-miss-emits-nothing-test
  (node! "edit$apply")
  (let [ag (stub-agent)]
    (swap! (proto/get-bt-st-memory ag) assoc :last-procedure "some$unknown-tool")
    (with-config {}
      (fn []
        (testing "an unknown procedure yields no guidance — the hot path"
          ;; Deliberately NOT the paper's full-graph fallback: that measured
          ;; 18.10 points worse on the task shape closest to ours.
          (is (nil? (pn/drain-iteration-notice! ag (proto/get-bt-st-memory ag)))))
        (testing "and the probe is still cleared"
          (is (nil? (:last-procedure @(proto/get-bt-st-memory ag)))))))))

(deftest drain-reads-the-configured-graph-id-test
  ;; The funnel the whole feature rests on: the CLI and the runtime must name
  ;; the same graph. They agree by BOTH resolving `:procedure-graph-id` — whose
  ;; `:default-fn` is the project slug — rather than by each deriving a name.
  ;; Two components computing the same key independently is how this feature
  ;; has already produced two silent-inertness bugs.
  (let [a (mem/procedure-upsert-node! *store* "g-custom" {:name "alpha$one" :kind :tool})
        b (mem/procedure-upsert-node! *store* "g-custom" {:name "beta$two"  :kind :tool})]
    (mem/procedure-upsert-edge! *store* "g-custom"
                                {:src-id (:id a) :dst-id (:id b) :relation :precedes}))
  (let [ag (stub-agent)]
    (swap! (proto/get-bt-st-memory ag) assoc :last-procedure "alpha$one")
    (with-config {:procedure-graph-id "g-custom"}
      (fn []
        (testing "guidance comes from the graph the config names"
          (is (str/includes? (pn/drain-iteration-notice! ag (proto/get-bt-st-memory ag))
                             "`alpha$one` —precedes→ `beta$two`")))))
    (swap! (proto/get-bt-st-memory ag) assoc :last-procedure "alpha$one")
    (with-config {:procedure-graph-id "g-other"}
      (fn []
        (testing "and a different graph id sees nothing — the id is not decorative"
          (is (nil? (pn/drain-iteration-notice! ag (proto/get-bt-st-memory ag)))))))))

(deftest procedure-graph-id-defaults-to-the-project-slug-test
  (testing "the schema default is the project registry slug, not a shared constant"
    ;; Two repos seeding into one \"default\" graph would let an unrelated
    ;; project's tool order guide this one. The slug is <basename>-<8 hex of
    ;; SHA-256(canonical path)>.
    (let [slug (config/project-graph-id)]
      (is (some? slug))
      (is (not= "default" slug))
      (is (re-matches #".+-[0-9a-f]{8}" slug))
      (testing "and get-config resolves to exactly that, with no env/config override"
        (is (= slug (config/get-config :procedure-graph-id)))))))

(deftest drain-respects-the-gate-test
  (let [a (node! "edit$apply") b (node! "read-file")]
    (edge! a b :verifies))
  (let [ag (stub-agent)]
    (swap! (proto/get-bt-st-memory ag) assoc :last-procedure "edit$apply")
    (testing "gate off means no guidance even with a populated graph"
      (with-redefs [config/get-config (fn [_ _] nil)]
        (is (nil? (pn/drain-iteration-notice! ag (proto/get-bt-st-memory ag))))))))

(deftest drain-never-throws-test
  (testing "no memory manager degrades to nil rather than breaking the turn"
    (let [ag (->StubAgent (atom {:last-procedure "edit$apply"}) (atom nil) (atom {}))]
      (with-config {} (fn [] (is (nil? (pn/drain-iteration-notice! ag (proto/get-bt-st-memory ag))))))))

  (testing "a store that throws degrades to nil"
    (let [ag (stub-agent)]
      (swap! (proto/get-bt-st-memory ag) assoc :last-procedure "edit$apply")
      (with-redefs [mem/procedure-find-node (fn [& _] (throw (ex-info "boom" {})))]
        (with-config {} (fn [] (is (nil? (pn/drain-iteration-notice! ag (proto/get-bt-st-memory ag))))))))))

(deftest drain-honours-hop-and-char-limits-test
  (let [ns- (mapv #(node! (str "n" %)) (range 4))]
    (doseq [i (range 3)] (edge! (ns- i) (ns- (inc i)) :precedes))
    (let [ag (stub-agent)]
      (with-config {:procedure-guidance-hops 1}
        (fn []
          (swap! (proto/get-bt-st-memory ag) assoc :last-procedure "n0")
          (let [s (pn/drain-iteration-notice! ag (proto/get-bt-st-memory ag))]
            (testing "hops=1 shows the immediate ring only"
              (is (str/includes? s "`n0` —precedes→ `n1`"))
              (is (not (str/includes? s "then:")))))))
      (with-config {:procedure-guidance-max-chars 40}
        (fn []
          (swap! (proto/get-bt-st-memory ag) assoc :last-procedure "n0")
          (testing "the char cap is enforced end to end"
            (let [s (pn/drain-iteration-notice! ag (proto/get-bt-st-memory ag))]
              (is (or (nil? s) (<= (count s) 40))))))))))

;; =====================================================
;; Seed mining
;; =====================================================

(deftest iteration->procedure-test
  (testing "a v3 tool iteration names its LAST tool"
    (is (= "edit$apply"
           (seed/iteration->procedure {:channel "tool"
                                       :tools [{:name "read-file"} {:name "edit$apply"}]}))))

  (testing "a code iteration with no tool call names NO procedure"
    ;; The seeder half of the same decision; must agree with
    ;; `probe-for-iteration` or the seeded graph is unreachable.
    (is (nil? (seed/iteration->procedure {:channel "code" :lang ["clojure" "bash"]
                                          :code ["(+ 1 2)" "ls -la"]}))))

  (testing "a code iteration that CALLS a tool names the TOOL, not code:<lang>"
    ;; The seeder/localizer agreement bug, found by mining this repo's own 87
    ;; recorded turns. `project-iteration` populates `:tools` only for the tool
    ;; CHANNEL, so a Clojure block invoking `(aws$whoami)` records as
    ;; {:channel "code" :code ["(aws$whoami)"] :lang ["clojure"]} — while at
    ;; runtime the tool-use hook fires and the localizer probes "aws$whoami".
    ;; Keyed as "code:clojure" the seeded graph is unreachable forever.
    (is (= "aws$whoami"
           (seed/iteration->procedure {:channel "code" :lang ["clojure"]
                                       :code ["(aws$whoami)"]})))
    (is (= "config$apply"
           (seed/iteration->procedure
            {:channel "code" :lang ["clojure"]
             :code ["(let [r (config$apply :scope \"project\")] r)"]}))))

  (testing "the LAST tool in a multi-call block wins, matching the localizer"
    (is (= "task$wait"
           (seed/iteration->procedure
            {:channel "code" :lang ["clojure"]
             :code ["(edit$apply {:a 1})" "(task$wait \"t-1\")"]}))))

  (testing "a $-less name is NOT mined — a false positive guides from a fiction"
    ;; `search`/`filter`/`map` are clojure.core or locals as often as tools.
    ;; A missed procedure means guidance does not fire; an invented one means
    ;; guidance fires WRONG.
    (is (nil? (seed/iteration->procedure {:channel "code" :lang ["clojure"]
                                          :code ["(search \"aws\" :type \"tool\")"]}))))

  (testing "an answer or reasoning-only iteration names no procedure"
    (is (nil? (seed/iteration->procedure {:channel "answer" :answer "done"})))
    (is (nil? (seed/iteration->procedure {:channel "none"})))))

(deftest transitions-test
  (testing "consecutive procedures become pairs"
    (is (= [["a" "b"] ["b" "c"]]
           (seed/transitions {:iterations [{:tools [{:name "a"}]}
                                           {:tools [{:name "b"}]}
                                           {:tools [{:name "c"}]}]}))))

  (testing "an iteration with no procedure does not BREAK the chain"
    ;; A pure-reasoning step between two tool calls does not stop the second
    ;; from following the first.
    (is (= [["a" "b"]]
           (seed/transitions {:iterations [{:tools [{:name "a"}]}
                                           {:channel "none"}
                                           {:tools [{:name "b"}]}]}))))

  (testing "the terminal answer iteration contributes no transition"
    (is (= [["a" "b"]]
           (seed/transitions {:iterations [{:tools [{:name "a"}]}
                                           {:tools [{:name "b"}]}
                                           {:channel "answer" :answer "x"}]}))))

  (testing "a single-procedure turn yields nothing"
    (is (empty? (seed/transitions {:iterations [{:tools [{:name "a"}]}]})))))

(deftest build-writes-bare-topology-test
  (let [records [{:iterations [{:tools [{:name "read-file"}]}
                               {:tools [{:name "edit$apply"}]}
                               {:channel "answer" :answer "ok"}]}
                 {:iterations [{:tools [{:name "read-file"}]}
                               {:tools [{:name "edit$apply"}]}]}
                 {:iterations [{:tools [{:name "read-file"}]}
                               {:tools [{:name "edit$apply"}]}]}
                 ;; observed once — below the support threshold
                 {:iterations [{:tools [{:name "read-file"}]}
                               {:tools [{:name "task$wait"}]}]}]]
    (with-redefs [seed/mine-transitions (fn [] (reduce (fn [a rec]
                                                         (-> a
                                                             (update :turns inc)
                                                             (update :counts #(reduce (fn [m t] (update m t (fnil inc 0)))
                                                                                      % (seed/transitions rec)))))
                                                       {:counts {} :procedures #{} :turns 0 :sessions 1}
                                                       records))]
      (let [r (seed/seed-procedures! *store* {:min-support 3})]
        (testing "only transitions at or above min-support become edges"
          (is (= 1 (:edges r)))
          (is (= 2 (:nodes r)))
          (is (= 1 (:skipped r))))

        (testing "the edge is :precedes, origin seed, with the observed support"
          (let [n (mem/procedure-find-node *store* nil "read-file")
                [e] (mem/procedure-out-neighborhood *store* nil (:id n) {})]
            (is (= :precedes (:relation e)))
            (is (= "seed" (:origin e)))
            (is (= 3 (:support e)))
            (is (= "edit$apply" (:dst_name e)))))

        (testing "Phi is EMPTY — a seed asserts only that this order was observed"
          (let [n (mem/procedure-find-node *store* nil "read-file")
                [e] (mem/procedure-out-neighborhood *store* nil (:id n) {})]
            (is (nil? (:condition e)))
            (is (nil? (:guidance e)))
            (is (nil? (:pitfalls e)))))

        (testing "re-running is idempotent, not accumulative"
          (seed/seed-procedures! *store* {:min-support 3})
          (is (= 1 (count (mem/procedure-out-neighborhood
                           *store* nil
                           (:id (mem/procedure-find-node *store* nil "read-file")) {})))))))))

(deftest build-drops-self-transitions-test
  (with-redefs [seed/mine-transitions (fn [] {:counts {["a" "a"] 9 ["a" "b"] 5}
                                              :procedures #{} :turns 1 :sessions 1})]
    (let [r (seed/seed-procedures! *store* {:min-support 3})]
      (testing "a procedure following itself is a retry loop, not a transition"
        (is (= 1 (:edges r)))))))

(deftest seeded-graph-is-reachable-by-the-localizer-test
  ;; The quiet failure this feature is most exposed to: the seeder and the
  ;; localizer keying nodes differently, so the graph is populated and Match
  ;; never hits.
  ;;
  ;; The tool-inside-a-code-block case in `iteration->procedure-test` is the
  ;; one that was actually broken, until mining this repo's own trajectories
  ;; surfaced it. Here the end-to-end round trip is pinned: a name the seeder
  ;; writes must be a name the localizer probes for.
  (with-redefs [seed/mine-transitions (fn [] {:counts {["aws$whoami" "edit$apply"] 4}
                                              :procedures #{} :turns 1 :sessions 1})]
    (seed/seed-procedures! *store* {:min-support 3}))
  (let [ag (stub-agent)]
    ;; A Clojure block that called `(aws$whoami)`: the hook recorded the tool,
    ;; and the code-results are present but must NOT change the probe.
    (swap! (proto/get-bt-st-memory ag) assoc
           :last-procedure "aws$whoami"
           :last-code-results [{:lang "clojure"}])
    (with-config {}
      (fn []
        (let [probe (pn/probe-for-iteration (proto/get-bt-st-memory ag))]
          (is (= "aws$whoami" probe))
          (is (some? (mem/procedure-find-node *store* nil probe)))
          (is (str/includes? (pn/drain-iteration-notice! ag (proto/get-bt-st-memory ag))
                             "`aws$whoami` —precedes→ `edit$apply`"))))))

  (testing "and no code:<lang> node was created by the seeder"
    (is (nil? (mem/procedure-find-node *store* nil "code:clojure")))
    (is (nil? (mem/procedure-find-node *store* nil "code:bash")))))
