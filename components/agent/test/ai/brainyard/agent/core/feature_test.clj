;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.core.feature-test
  "Invariants over the feature registry.

   `every-schema-key-is-classified` is the load-bearing one and mirrors
   `every-schema-key-has-doc` in config_test: add a key to `config-schema`
   without classifying it and `bb test` fails. That is what stops this
   classification rotting the way the `;; ---` section comments in
   config-schema did — comments are not data, so nothing could enforce them."
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.set :as set]
            [ai.brainyard.agent.core.config :as cfg]
            [ai.brainyard.agent.core.feature :as feat]))

;; ============================================================================
;; The partition — every schema key belongs somewhere, exactly once
;; ============================================================================

(deftest every-schema-key-is-classified
  (testing "config-schema partitions into features + ambient + quarantine"
    (let [claimed (set/union feat/claimed-keys
                             feat/family-gate-keys
                             feat/ambient-keys
                             feat/unclassified-keys)]
      (is (= cfg/config-keys claimed)
          (str "unclassified schema keys: "
               (sort (set/difference cfg/config-keys claimed))
               " | registry keys absent from schema: "
               (sort (set/difference claimed cfg/config-keys)))))))

(deftest no-key-claimed-twice
  (testing "each schema key belongs to exactly one feature (catches copy-paste between families)"
    (let [all-claims (mapcat feat/feature-keys feat/all-features)
          dupes      (->> all-claims frequencies (filter #(> (val %) 1)) (map key) sort)]
      (is (empty? dupes) (str "keys claimed by more than one feature: " dupes)))))

(deftest ambient-and-feature-sets-are-disjoint
  (is (empty? (set/intersection feat/ambient-keys feat/claimed-keys))
      "an ambient key must not also be owned by a feature")
  (is (empty? (set/intersection feat/unclassified-keys feat/claimed-keys))
      "a quarantined key must not also be owned by a feature"))

(deftest partition-counts-match-the-design
  (testing "32 feature gates + 9 family gates + 91 knobs + 16 presentation + 13 ambient = 161"
    (let [knobs (->> feat/all-features
                     (mapcat :keys)
                     (remove feat/presentation-key?)
                     count)
          pres  (->> feat/all-features (filter :presentation) (mapcat :keys) count)]
      (is (= 32 (count feat/gate-keys)) "+1: :enable-a2a (agents/a2a)")
      (is (= 9 (count feat/family-gate-keys)) "one per capability family; :ui has none")
      (is (= 91 knobs) "+8: the :a2a-* knobs (agents/a2a)")
      (is (= 16 pres))
      (is (= 13 (count feat/ambient-keys)) "+1: :enable-tool-binding, beside :compact-agent-tools")
      (is (= 0 (count feat/unclassified-keys))
          "no schema key is currently unreadable")
      (is (= 161 (count cfg/config-keys)))
      (testing "the partition still balances"
        ;; The real invariant behind the hardcoded numbers: every schema key
        ;; lands in exactly one bucket. Asserting the sum catches a
        ;; mis-updated ledger that happens to keep the individual counts
        ;; self-consistent.
        (is (= (count cfg/config-keys)
               (+ (count feat/gate-keys) (count feat/family-gate-keys)
                  knobs pres (count feat/ambient-keys))))))))

;; ============================================================================
;; Family master switches
;; ============================================================================

(deftest family-gates-cover-every-capability-family
  (is (= (disj (set feat/families) :ui) (set (keys feat/family-gates)))
      "every family except :ui has a master switch")
  (doseq [[fam k] feat/family-gates]
    (is (contains? cfg/config-keys k) (str fam " gate " k " must be a schema key"))
    (is (= "boolean" (get-in cfg/config-schema [k :type])))
    (is (true? (get-in cfg/config-schema [k :default]))
        (str k " must default true — a family switch is a kill-switch, not an opt-in"))))

(deftest family-gates-are-not-feature-keys
  (is (empty? (set/intersection feat/family-gate-keys feat/claimed-keys))
      "a family switch belongs to the family, not to any one feature")
  (is (empty? (set/intersection feat/family-gate-keys feat/ambient-keys))))

;; ============================================================================
;; Gates
;; ============================================================================

(deftest gates-are-schema-keys
  (testing "every non-:proposed gate exists in config-schema"
    (doseq [[fid f] feat/feature-registry
            :when   (and (:gate f) (not (:proposed f)))]
      (is (contains? cfg/config-keys (:gate f))
          (str fid " gate " (:gate f) " is not a config-schema key")))))

(deftest no-proposed-features-remain
  (testing "P3 created all six planned gates; :proposed is now unused"
    (is (empty? (filter :proposed feat/all-features))
        "a :proposed feature means a gate that is not yet a schema key")
    (doseq [k [:enable-memory-recall :enable-compaction
               :enable-live-artifacts :enable-artifact-gc :enable-acp
               :enable-gateway]]
      (is (contains? cfg/config-keys k) (str k " should now be a schema key"))
      (is (contains? feat/gate-keys k) (str k " should now be a live gate"))
      (is (true? (get-in cfg/config-schema [k :default]))
          (str k " must default true — it gates behaviour that runs today, so
               a false default would silently disable it on upgrade")))))

(deftest gates-are-boolean-or-have-a-pred
  (testing "a non-boolean gate must declare :gate-pred"
    (doseq [[fid f] feat/feature-registry
            :let    [g (feat/gate-of f)]
            :when   g]
      (let [t (get-in cfg/config-schema [g :type])]
        (if (= "boolean" t)
          (is (nil? (:gate-pred f))
              (str fid " has a boolean gate and does not need :gate-pred"))
          (is (some? (:gate-pred f))
              (str fid " gate " g " is " t ", not boolean — it must declare :gate-pred")))))))

(deftest numeric-gate-preds-behave
  (testing "the two numeric-zero gates read off at 0 and on above it"
    (doseq [fid [:reasoning/refinement :tools/cache]
            :let [pred (:gate-pred (feat/feature-doc fid))]]
      (is (false? (boolean (pred 0))) (str fid " must be off at 0"))
      (is (true? (boolean (pred 3))) (str fid " must be on above 0")))))

;; ============================================================================
;; Structure
;; ============================================================================

(deftest live-keys-are-subset-of-keys
  (doseq [[fid f] feat/feature-registry
          :when   (seq (:live-keys f))]
    (is (set/subset? (:live-keys f) (set (feat/feature-keys f)))
        (str fid " :live-keys must be keys the feature owns"))))

(deftest lifecycles-are-known
  (doseq [[fid f] feat/feature-registry]
    (is (contains? feat/lifecycles (:lifecycle f))
        (str fid " has unknown :lifecycle " (:lifecycle f)))))

(deftest every-feature-has-title-family-and-doc
  (doseq [[fid f] feat/feature-registry]
    (is (string? (:title f)) (str fid " needs a :title"))
    (is (keyword? (:family f)) (str fid " needs a :family"))
    (is (and (string? (:doc f)) (seq (:doc f))) (str fid " needs a :doc"))
    (is (= (namespace fid) (name (:family f)))
        (str fid " id namespace must match its :family"))))

(deftest deps-reference-known-features
  (doseq [[fid f] feat/feature-registry
          dep     (concat (mapcat #(if (set? %) % [%]) (:requires f))
                          (:implies f)
                          (keys (:requires-partial f)))]
    (is (contains? feat/feature-registry dep)
        (str fid " references unknown feature " dep))
    (is (not= fid dep) (str fid " must not depend on itself"))))

(deftest implies-graph-is-acyclic
  (testing "the P1 resolution fixpoint must terminate"
    (letfn [(walk [fid seen path]
              (doseq [nxt (:implies (feat/feature-doc fid))]
                (is (not (contains? seen nxt))
                    (str "cycle in :implies — " (conj path nxt)))
                (when-not (contains? seen nxt)
                  (walk nxt (conj seen nxt) (conj path nxt)))))]
      (doseq [fid (keys feat/feature-registry)]
        (walk fid #{fid} [fid])))))

(deftest requires-graph-is-acyclic
  (letfn [(hard-deps [fid]
            (remove set? (:requires (feat/feature-doc fid))))
          (walk [fid seen path]
            (doseq [nxt (hard-deps fid)]
              (is (not (contains? seen nxt))
                  (str "cycle in :requires — " (conj path nxt)))
              (when-not (contains? seen nxt)
                (walk nxt (conj seen nxt) (conj path nxt)))))]
    (doseq [fid (keys feat/feature-registry)]
      (walk fid #{fid} [fid]))))

;; ============================================================================
;; Lifecycle ↔ :requires-restart (P3 replaces the per-key flag with this)
;; ============================================================================

(deftest startup-features-are-the-restart-keys
  (testing "restart-ness is derived from :lifecycle :startup, not a per-key flag"
    ;; This exact set was asserted against config-schema's :requires-restart
    ;; flag before the flag was deleted; keeping it verbatim is what proves the
    ;; derivation reproduces the old behaviour rather than redefining it.
    (is (= #{:enable-graph-memory :graph-embed-model :graph-extract-model
             :enable-memory-capture :memory-question-max-chars :memory-answer-max-chars
             :graph-extract-max-input-chars :graph-max-entities-per-episode
             :graph-max-relations-per-episode :graph-max-nodes :graph-max-edges
             :graph-extract-mode :graph-prune-orphans?}
           feat/restart-required-keys))
    (is (feat/requires-restart-key? :enable-graph-memory))
    (is (feat/requires-restart-key? :graph-embed-model))
    (testing "live-read keys are NOT flagged"
      (is (not (feat/requires-restart-key? :enable-memory-consolidation)))
      (is (not (feat/requires-restart-key? :enable-mid-turn-recall)))
      (is (not (feat/requires-restart-key? :max-iterations)))))
  (testing "and config-schema no longer carries the flag at all"
    (is (empty? (filter :requires-restart (vals cfg/config-schema)))
        "a stray :requires-restart in the schema is now dead metadata")))

(deftest annotate-hit-adds-requires-restart
  (testing "the annotation moved here from search-config-keys"
    (is (true? (:requires-restart (feat/annotate-hit {:key "graph-extract-model"}))))
    (is (not (contains? (feat/annotate-hit {:key "enable-mid-turn-recall"}) :requires-restart)))
    (is (not (contains? (feat/annotate-hit {:key "max-iterations"}) :requires-restart)))))

(deftest graph-batch-episodes-is-exempt-from-restart
  (testing ":graph-extract-batch-episodes is on a :startup feature but re-read per graph-build"
    (is (contains? (set (:keys (feat/feature-doc :memory/graph)))
                   :graph-extract-batch-episodes))
    (is (not (contains? feat/restart-required-keys :graph-extract-batch-episodes))
        "without the :live-keys exemption the derived set would be 14, not 13")))

;; ============================================================================
;; Indexes and query helpers
;; ============================================================================

(deftest feature-of-key-inverts-the-registry
  (is (= :memory/graph (feat/feature-of-key :graph-max-nodes)))
  (is (= :exec/tasks (feat/feature-of-key :fast-eval-timeout-ms))
      "governs every tool call, not just code — must not sit under exec/code-channel")
  (is (= :ui/display (feat/feature-of-key :show-memory-activity))
      "a display knob, despite reading as memory")
  (is (nil? (feat/feature-of-key :permission-mode)) "ambient keys have no feature")
  (is (nil? (feat/feature-of-key :feature-profile))
      "ambient — configures the feature system, belongs to no feature"))

(deftest gate-keys-are-reachable-through-the-index
  (testing "a gate is owned by its own feature"
    (is (= :memory/graph (feat/feature-of-key :enable-graph-memory)))
    (is (= :agents/subagents (feat/feature-of-key :enable-subagent-calls)))
    (is (= :reasoning/refinement (feat/feature-of-key :max-refinements)))))

(deftest presentation-keys-are-flagged
  (is (feat/presentation-key? :display-format))
  (is (feat/presentation-key? :enable-tmux-popup)
      ":enable-*-shaped but presentation — needs an explicit home, not a gate")
  (is (not (feat/presentation-key? :enable-graph-memory))))

(deftest families-cover-the-registry
  (is (= (set feat/families) (set (map :family feat/all-features))))
  (is (= (count feat/feature-registry)
         (reduce + (map (comp count feat/family->features) feat/families))))
  (testing "nine capability families hold 42 features — 32 gated, 10 ungated groupings"
    (let [capability (remove :presentation feat/all-features)]
      (is (= 9 (count (disj (set feat/families) :ui))))
      (is (= 42 (count capability)) "+1: :agents/a2a")
      (is (= 32 (count (filter :gate capability)))
          "every gated feature now has a real schema key")
      (is (= 10 (count (remove :gate capability)))
          "an ungated grouping exists so its knobs have a discoverable home")))
  (testing "ui is modelled as sub-features, not one flat 16-key bucket"
    (is (= 2 (count (feat/family->features :ui))))))

(deftest annotate-hit-adds-feature-and-family
  (let [hit (feat/annotate-hit {:key "graph-max-nodes" :value 100})]
    (is (= "memory/graph" (:feature hit)))
    (is (= "memory" (:family hit)))
    (is (= 100 (:value hit)) "existing hit fields survive"))
  (testing "ambient and quarantined keys get no :feature — absence is meaningful"
    (is (nil? (:feature (feat/annotate-hit {:key "permission-mode"}))))
    (is (nil? (:feature (feat/annotate-hit {:key "feature-profile"}))))))

(deftest annotate-hits-maps-over-results
  (let [hits (feat/annotate-hits [{:key "recall-limit"} {:key "lm-config"}])]
    (is (= 2 (count hits)))
    (is (= "memory/recall" (:feature (first hits))))
    (is (nil? (:feature (second hits))))))

(deftest family-view-resolves-names-and-lists-members
  (doseq [input [:memory "memory" "Memory" "memory.graph" ":memory"]]
    (is (some? (feat/family-view nil input)) (str "should resolve " (pr-str input))))
  (is (nil? (feat/family-view nil "nope")))
  (let [v     (feat/family-view nil :memory)
        graph (first (filter #(= "memory/graph" (:feature %)) (:features v)))]
    (is (= "memory" (:family v)))
    (is (= 6 (count (:features v))))
    (is (= "enable-graph-memory" (:gate graph)))
    (is (= :startup (:lifecycle graph)))
    (is (= ["memory/consolidation"] (:implies graph)))
    (is (= ["memory/capture"] (:requires graph)))
    (is (= 10 (count (:keys graph))))
    (is (every? #(contains? % :default) (:keys graph)))))

(deftest family-view-marks-gate-and-partial
  (let [recall (->> (feat/family-view nil :memory) :features
                    (filter #(= "memory/recall" (:feature %))) first)]
    (is (nil? (:proposed recall)) "promoted in P3 — no longer proposed")
    (is (= "enable-memory-recall" (:gate recall)))
    (is (some? (:gate-value recall)) "a real schema key resolves to a value"))
  (let [fsm (->> (feat/family-view nil :automation) :features
                 (filter #(= "automation/fsm" (:feature %))) first)]
    (is (= {"automation/scheduler"
            "timed/eventless (:always/:after) transitions never advance"}
           (:requires-partial fsm)))))

(deftest family-view-renders-disjunctive-requires
  (let [nudges (->> (feat/family-view nil :self-improve) :features
                    (filter #(= "self-improve/nudges" (:feature %))) first)]
    (is (= [{:any-of ["self-improve/distillation" "self-improve/refinement"]}]
           (:requires nudges))
        "nudges needs EITHER producer — a hard AND would be wrong")))

(deftest family-summary-covers-every-family
  (let [s (feat/family-summary)]
    (is (= (count feat/families) (count s)))
    (is (= (count feat/feature-registry) (reduce + (map :features s))))
    (is (= 0 (:gated (first (filter #(= "ui" (:family %)) s))))
        "ui is presentation-only — no gates")))

;; ============================================================================
;; P1 — resolution
;; ============================================================================
;;
;; Resolution is tested through `feature-state*` / `on?*`, which take a plain
;; snapshot map. That injects synthetic gate values without redefining
;; `get-config` and without touching the real .brainyard/config.edn.

(def ^:private all-on
  "A snapshot with every real gate on, so a test can flip one key and attribute
   any change to that key alone."
  (into {} (for [g feat/gate-keys]
             [g (if (= "boolean" (get-in cfg/config-schema [g :type])) true 1)])))

(defn- snap [& kvs] (merge all-on (apply hash-map kvs)))

(deftest ungated-groupings-are-always-on
  (doseq [fid [:context/conversation :exec/tasks :reasoning/loop :tools/mcp]]
    (is (feat/on?* all-on fid) (str fid " has no gate — it cannot be off"))))

(deftest numeric-gates-read-zero-as-off
  (is (not (feat/on?* (snap :max-refinements 0) :reasoning/refinement)))
  (is (feat/on?* (snap :max-refinements 3) :reasoning/refinement))
  (is (not (feat/on?* (snap :tool-cache-ttl 0) :tools/cache)))
  (is (feat/on?* (snap :tool-cache-ttl 60) :tools/cache)))

(deftest numeric-gate-tolerates-nil-and-non-numeric
  (testing "a numeric gate must read as off, not throw, on a junk value"
    (is (not (feat/on?* (snap :max-refinements nil) :reasoning/refinement)))
    (is (not (feat/on?* (snap :tool-cache-ttl "x") :tools/cache)))))

(deftest implies-turns-on-the-target
  (testing "graph on + consolidation off => consolidation resolves ON (was the ad-hoc `or`)"
    (let [s (snap :enable-graph-memory true :enable-memory-consolidation false)]
      (is (feat/on?* s :memory/consolidation))
      (is (= :implied-by (:source (feat/feature-state* s :memory/consolidation))))
      (is (= #{:memory/graph} (:implied-by (feat/feature-state* s :memory/consolidation))))))
  (testing "graph off + consolidation off => off"
    (let [s (snap :enable-graph-memory false :enable-memory-consolidation false)]
      (is (not (feat/on?* s :memory/consolidation)))
      (is (= :off (:source (feat/feature-state* s :memory/consolidation))))))
  (testing "consolidation on by itself => on via its own gate, not implication"
    (let [s (snap :enable-graph-memory false :enable-memory-consolidation true)]
      (is (feat/on?* s :memory/consolidation))
      (is (= :base (:source (feat/feature-state* s :memory/consolidation))))
      (is (empty? (:implied-by (feat/feature-state* s :memory/consolidation)))))))

(deftest unmet-requires-resolves-off-fail-safe
  (testing "graph without capture is off — extraction against an empty store"
    (let [s  (snap :enable-memory-capture false :enable-graph-memory true)
          st (feat/feature-state* s :memory/graph)]
      (is (not (:on? st)))
      (is (= #{:memory/capture} (:unmet st)))
      (is (= :off (:source st)))))
  (testing "the implication does not leak past a pruned implier"
    (let [s (snap :enable-memory-capture false
                  :enable-graph-memory true
                  :enable-memory-consolidation false)]
      (is (not (feat/on?* s :memory/consolidation))
          "graph is off for want of capture, so it implies nothing"))))

(deftest requires-pruning-reaches-dependents
  ;; This used to walk `mid-turn-recall -> recall -> capture`. Dropping
  ;; `:memory/recall :requires :memory/capture` (read-only memory is valid —
  ;; see `recall-does-not-require-capture`) removed the registry's ONLY 2-level
  ;; :requires chain, so there is no longer a pure transitive case to exercise.
  ;; What remains, and is the harder path anyway, is pruning applied OVER the
  ;; implication closure — `final-on?` after `closed-on?`.
  (testing "a dependent is pruned when its requirement's own gate goes off"
    (is (feat/on?* all-on :memory/mid-turn-recall))
    (is (not (feat/on?* (snap :enable-memory-recall false) :memory/mid-turn-recall))
        "mid-turn re-recall cannot outlive plain recall"))

  (testing "pruning beats implication: an implied feature still needs its requirements"
    ;; graph :implies consolidation, but consolidation :requires capture. The
    ;; closure turns consolidation on, then pruning must take it back off —
    ;; the case a single interleaved fixpoint would oscillate on.
    (let [s (snap :enable-memory-capture false :enable-graph-memory true)]
      (is (not (feat/on?* s :memory/consolidation))
          "implied-on must not survive an unmet requirement")
      (is (feat/on?* s :memory/recall)
          "and the pruning must not overreach into features with no such requirement"))))

(deftest disjunctive-requires-needs-only-one
  (let [both-off (snap :enable-skill-distillation false :enable-skill-refinement false)
        one-on   (snap :enable-skill-distillation true :enable-skill-refinement false)]
    (is (not (feat/on?* both-off :self-improve/nudges))
        "with neither producer the nudge can never fire")
    (is (feat/on?* one-on :self-improve/nudges))
    (is (= #{#{:self-improve/distillation :self-improve/refinement}}
           (:unmet (feat/feature-state* both-off :self-improve/nudges))))))

(deftest requires-partial-degrades-without-disabling
  (let [s  (snap :enable-scheduler false)
        st (feat/feature-state* s :automation/fsm)]
    (is (:on? st) "an FSM still works event-driven; a hard :requires would be wrong")
    (is (= {:automation/scheduler
            "timed/eventless (:always/:after) transitions never advance"}
           (:degraded st))))
  (is (empty? (:degraded (feat/feature-state* all-on :automation/fsm)))
      "no note when the scheduler is on"))

(deftest sandbox-persistence-requires-the-code-channel
  (is (feat/on?* all-on :exec/sandbox-persistence))
  (is (not (feat/on?* (snap :code-channel? false) :exec/sandbox-persistence))
      "nothing to persist with no code channel"))

(deftest recall-does-not-require-capture
  ;; The memory store is USER-scoped and long-lived, so a session can read a
  ;; corpus it never writes to. `by ask` relies on this: a throwaway one-shot
  ;; turns capture off (its "what is 2+2?" Q&A is noise) but must still answer
  ;; with the benefit of prior memory. Nothing mechanical couples them either —
  ;; core.agent/create-agent creates the manager unconditionally and only
  ;; `start-capture!` is gated.
  (is (feat/on?* (snap :enable-memory-capture false) :memory/recall)
      "recall must survive capture being off — read-only memory is valid")
  (is (not (feat/on?* (snap :enable-memory-capture false) :memory/capture)))

  (testing "the WRITE-side features do still require capture"
    (doseq [fid [:memory/consolidation :memory/graph]]
      (is (not (feat/on?* (snap :enable-memory-capture false
                                :enable-graph-memory true
                                :enable-memory-consolidation true)
                          fid))
          (str fid " has nothing to write without capture"))))

  (testing "recall's own gate still turns it off"
    (is (not (feat/on?* (snap :enable-memory-capture false
                              :enable-memory-recall false)
                        :memory/recall)))))

(deftest unknown-feature-resolves-to-nil-not-a-throw
  (is (nil? (feat/feature-state* all-on :nope/nope)))
  (is (false? (feat/on?* all-on :nope/nope))))

(deftest snapshot-falls-back-to-schema-default
  (testing "get-config-snapshot omits :default-fn-only keys; resolution must not read them as off"
    (is (feat/on?* {} :memory/project)
        ":enable-project-memory defaults true, so an empty snapshot resolves on")))

;; ---------------------------------------------------------------------------
;; off-reason — the shared denial
;; ---------------------------------------------------------------------------

(deftest off-reason-is-nil-when-on
  (with-redefs [cfg/get-config (fn ([k] (get all-on k true))
                                 ([_ k] (get all-on k true))
                                 ([_ k d] (get all-on k d)))]
    (is (nil? (feat/off-reason nil :agents/subagents)))))

(deftest off-reason-names-the-gate-then-the-dependency
  (testing "gate off => the gate, in the exact shape the old error strings used"
    (with-redefs [cfg/get-config (fn ([k] (if (= k :enable-subagent-calls) false (get all-on k true)))
                                   ([_ k] (if (= k :enable-subagent-calls) false (get all-on k true)))
                                   ([_ k d] (if (= k :enable-subagent-calls) false (get all-on k d))))]
      (is (= "enable-subagent-calls=false" (feat/off-reason nil :agents/subagents)))))
  (testing "gate ON but a dependency unmet => name the dependency, not the gate"
    (with-redefs [cfg/get-config (fn ([k] (if (= k :enable-memory-capture) false (get all-on k true)))
                                   ([_ k] (if (= k :enable-memory-capture) false (get all-on k true)))
                                   ([_ k d] (if (= k :enable-memory-capture) false (get all-on k d))))]
      (is (= "requires memory/capture" (feat/off-reason nil :memory/graph))
          "reporting enable-graph-memory=false here would be a lie — the user set it true"))))

(deftest off-reason-reports-a-numeric-gate-by-value
  (with-redefs [cfg/get-config (fn ([k] (if (= k :tool-cache-ttl) 0 (get all-on k true)))
                                 ([_ k] (if (= k :tool-cache-ttl) 0 (get all-on k true)))
                                 ([_ k d] (if (= k :tool-cache-ttl) 0 (get all-on k d))))]
    (is (= "tool-cache-ttl=0" (feat/off-reason nil :tools/cache))
        "=false would name a value the user never set")))

(deftest off-reason-renders-a-disjunction
  (with-redefs [cfg/get-config (fn ([k] (get (snap :enable-skill-distillation false
                                                   :enable-skill-refinement false) k true))
                                 ([_ k] (get (snap :enable-skill-distillation false
                                                   :enable-skill-refinement false) k true))
                                 ([_ k d] (get (snap :enable-skill-distillation false
                                                     :enable-skill-refinement false) k d)))]
    (is (= "requires self-improve/distillation or self-improve/refinement"
           (feat/off-reason nil :self-improve/nudges)))))

;; ---------------------------------------------------------------------------
;; Family switch semantics (AND, and only over GATED features)
;; ---------------------------------------------------------------------------

(deftest family-off-forces-gated-members-off
  (let [s (snap :enable-memory false)]
    (doseq [fid [:memory/capture :memory/consolidation :memory/graph :memory/project]]
      (is (not (feat/on?* s fid)) (str fid " must follow its family switch")))))

(deftest family-off-does-not-reach-ungated-groupings
  (testing "a family switch is a master over the family's SWITCHES, not its knobs"
    (is (feat/on?* (snap :enable-reasoning false) :reasoning/loop)
        "/feature reasoning off must not claim to have disabled the agent loop")
    (is (feat/on?* (snap :enable-exec false) :exec/tasks))
    (is (feat/on?* (snap :enable-tools false) :tools/mcp)))
  (testing "an ungated member CAN still go off transitively, via :requires"
    (let [s (snap :enable-analytics false)]
      (is (not (feat/on?* s :analytics/trajectory)) "gated — the switch reaches it")
      (is (not (feat/on?* s :analytics/scoring))
          "ungated, so the switch does not reach it directly — but it requires
           trajectory, which the switch did reach")
      (is (= #{:analytics/trajectory} (:unmet (feat/feature-state* s :analytics/scoring)))
          "and the reported cause is the requirement, not the family switch")))
  (testing "but the gated members of those families do follow it"
    (is (not (feat/on?* (snap :enable-reasoning false :max-refinements 3) :reasoning/refinement)))
    (is (not (feat/on?* (snap :enable-exec false) :exec/code-channel)))
    (is (not (feat/on?* (snap :enable-tools false :tool-cache-ttl 60) :tools/cache)))
    (is (not (feat/on?* (snap :enable-analytics false) :analytics/trajectory)))))

(deftest family-on-defers-to-each-feature-gate
  (testing "true is not an override — it does not force a member on"
    (let [s (snap :enable-memory true :enable-mid-turn-recall false)]
      (is (feat/on?* s :memory/capture))
      (is (not (feat/on?* s :memory/mid-turn-recall))
          "the family switch must not resurrect a feature the user turned off"))))

(deftest family-switch-is-non-destructive
  (testing "off then on restores per-feature settings, because member gates are untouched"
    (let [members {:enable-memory-capture true
                   :enable-mid-turn-recall false
                   :enable-graph-memory true}
          off  (merge (snap) members {:enable-memory false})
          back (merge (snap) members {:enable-memory true})]
      (is (not (feat/on?* off :memory/capture)))
      (is (feat/on?* back :memory/capture) "restored")
      (is (not (feat/on?* back :memory/mid-turn-recall))
          "and still off, because that is how the user left it"))))

(deftest family-off-suppresses-implication
  (testing "graph cannot imply consolidation when the family switch is off"
    (let [s (snap :enable-memory false
                  :enable-graph-memory true
                  :enable-memory-consolidation false)]
      (is (not (feat/on?* s :memory/consolidation))))))

(deftest ui-has-no-family-switch
  (is (nil? (feat/family-gates :ui)))
  (is (feat/on?* (snap) :ui/display)))

(deftest family-switch-only-kills-on-explicit-false
  (testing "a nil read defers — a partial config view must not silently disable a family"
    (is (feat/on?* (snap :enable-memory nil) :memory/capture))
    (is (feat/on?* {:enable-memory-capture true} :memory/capture)
        "a snapshot that never heard of the family key resolves on"))
  (testing "but an explicit false still kills"
    (is (not (feat/on?* (snap :enable-memory false) :memory/capture)))))

;; ---------------------------------------------------------------------------
;; BY_FEATURES
;; ---------------------------------------------------------------------------

(deftest by-features-parses-signs-and-separators
  (let [parse @#'feat/parse-features-spec]
    (is (= {:memory/graph false :automation/reactions true}
           (parse "-memory.graph,+automation.reactions")))
    (is (= {:memory/graph true} (parse "memory.graph")) "a bare token means on")
    (is (= {:memory/graph true} (parse "  memory/graph  ")) "slash form and padding")
    (is (nil? (parse nil)))
    (is (nil? (parse "   ")))
    (testing "an unresolvable token is dropped, not thrown — a typo in a
              container env var must not stop the binary"
      (is (= {:memory/graph false} (parse "-memory.graph,-bogus.thing")))
      (is (= {} (parse "nonsense"))))))

(deftest by-features-overrides-the-persisted-gate
  (with-redefs [feat/features-env-overrides (fn [] {:memory/graph false})]
    (is (not (feat/on?* (snap :enable-graph-memory true) :memory/graph)))
    (is (feat/on?* (snap :enable-scheduler true) :automation/scheduler)
        "features not named are untouched")))

(deftest specific-env-var-beats-the-bulk-list
  (testing "BY_ENABLE_GRAPH_MEMORY set → BY_FEATURES does not apply to it"
    (with-redefs [feat/features-env-overrides (fn [] {:memory/graph false})
                  cfg/schema-env-value        (fn [k] (if (= k :enable-graph-memory)
                                                        true cfg/env-unset))]
      (is (feat/on?* (snap :enable-graph-memory true) :memory/graph)))))

(deftest by-features-cannot-defeat-a-requirement
  (testing "turning a feature on via env still respects :requires — fail-safe"
    (with-redefs [feat/features-env-overrides (fn [] {:memory/graph true})]
      (is (not (feat/on?* (snap :enable-memory-capture false :enable-graph-memory false)
                          :memory/graph))))))

;; ---------------------------------------------------------------------------
;; Feature profiles
;; ---------------------------------------------------------------------------

(deftest profiles-only-name-real-gates
  (testing "a profile must not drift into naming something that is not a switch"
    (doseq [[profile overrides] cfg/feature-profiles
            [k v] overrides]
      (is (contains? cfg/config-keys k)
          (str profile " names " k ", which is not a config key"))
      (is (or (contains? feat/gate-keys k) (contains? feat/family-gate-keys k))
          (str profile " names " k ", which is not a gate — profiles set the
               baseline for GATES, not arbitrary knobs"))
      (is (some? v) (str profile "/" k " must declare a value")))))

(deftest profile-values-match-their-gate-type
  (doseq [[profile overrides] cfg/feature-profiles
          [k v] overrides]
    (is (cfg/valid-config-value? k v)
        (str profile "/" k " value " (pr-str v) " does not match the schema type"))))

(deftest standard-profile-is-inert
  (is (= {} (:standard cfg/feature-profiles))
      "the default profile must change nothing, so the mechanism is opt-in"))

(deftest profiles-are-known-names
  (is (= #{:minimal :standard :full} (set (keys cfg/feature-profiles))))
  (is (= :standard (get-in cfg/config-schema [:feature-profile :default]))))

(deftest minimal-turns-off-background-work
  (let [m (:minimal cfg/feature-profiles)]
    (testing "nothing that spends an LLM call or runs off-turn survives"
      (doseq [k [:enable-memory-consolidation :enable-graph-memory
                 :enable-skill-distillation :enable-skill-refinement
                 :enable-scheduler :enable-reactions]]
        (is (false? (get m k)) (str k " should be off under :minimal"))))
    (testing "but capture and recall are NOT disabled — memory with nothing in
              it is not minimal, it is broken"
      (is (not (contains? m :enable-memory-capture)))
      (is (not (contains? m :enable-memory-recall))))))

;; ---------------------------------------------------------------------------
;; :root-only (§9 Q4) — declared once, enforced in the agent-aware path
;; ---------------------------------------------------------------------------

(defn- stub-agent
  "Minimal agent shape: only the parent slot matters to root detection."
  [parent]
  {:!state (atom {:runtime {:parent-agent parent}})})

(defn- stub-cfg
  "get-config stand-in over `all-on`, covering the arities resolution uses."
  [overrides]
  (fn ([k]     (get overrides k (get-in cfg/config-schema [k :default])))
    ([_ k]   (get overrides k (get-in cfg/config-schema [k :default])))
    ([_ k d] (get overrides k d))))

(def ^:private root-only-features
  #{:memory/consolidation :automation/reactions :automation/fsm
    :self-improve/distillation :self-improve/nudges :exec/task-notify})

(deftest root-only-marks-exactly-the-root-driven-features
  (is (= root-only-features
         (into #{} (keep (fn [[fid f]] (when (:root-only f) fid))) feat/feature-registry))
      "these are the features whose call sites checked root-agent? by hand"))

(deftest root-only-withholds-from-a-sub-agent
  (with-redefs [cfg/get-config (stub-cfg all-on)]
    (doseq [fid root-only-features]
      (testing (str fid)
        (is (feat/on? (stub-agent nil) fid) "root drives it")
        (is (not (feat/on? (stub-agent {:agent-id :root/x}) fid))
            "a sub-agent shares the root's session and must not also drive it")
        (let [st (feat/feature-state (stub-agent {:agent-id :root/x}) fid)]
          (is (true? (:root-only-blocked st)))
          (is (= :off (:source st))))))))

(deftest root-only-does-not-affect-other-features
  (with-redefs [cfg/get-config (stub-cfg all-on)]
    (doseq [fid [:memory/capture :memory/graph :context/budget :exec/code-channel]]
      (is (feat/on? (stub-agent {:agent-id :root/x}) fid)
          (str fid " is not root-only and must stay on for a sub-agent")))))

(deftest root-only-explains-itself
  (with-redefs [cfg/get-config (stub-cfg all-on)]
    (is (re-find #"root-only" (feat/off-reason (stub-agent {:agent-id :root/x})
                                               :memory/consolidation)))
    (is (nil? (feat/off-reason (stub-agent nil) :memory/consolidation)))))

(deftest nil-and-unreadable-agents-count-as-root
  (testing "withholding a capability on an unknown agent shape would be the wrong default"
    (with-redefs [cfg/get-config (stub-cfg all-on)]
      (is (feat/on? nil :memory/consolidation))
      (is (feat/on? {:not-an-agent true} :memory/consolidation)))))

(deftest snapshot-arity-does-not-apply-root-only
  (testing "on?* carries no agent, so :root-only cannot be evaluated there —
            call sites that DRIVE a root-only feature must use the agent arity"
    (is (feat/on?* (snap) :memory/consolidation))))
