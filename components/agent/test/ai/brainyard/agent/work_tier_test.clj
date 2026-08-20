;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.work-tier-test
  "Tests for router-agent work-tier model routing (docs/design/
   router-agent-model-routing-plan.md).

   The property that matters most is INERTNESS: shipped defaults must resolve
   to nothing, so a build that has never been configured dispatches exactly as
   it did before tiers existed. Most of these tests assert that nothing
   happens."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [ai.brainyard.agent.core.config :as config]
            [ai.brainyard.agent.core.feature :as feature]
            [ai.brainyard.clj-llm.interface :as llm]))

;; ============================================================================
;; Fixture — isolate the global config layer
;; ============================================================================

(defn- with-clean-global-config
  [f]
  (let [!g      @(resolve 'ai.brainyard.agent.core.config/!global-config)
        before  @!g]
    (try (f)
         (finally (reset! !g before)))))

(use-fixtures :each with-clean-global-config)

(defn- set-tiers!
  [m]
  (swap! @(resolve 'ai.brainyard.agent.core.config/!global-config)
         assoc :agent-lm-tiers m))

;; ============================================================================
;; Tier vocabulary
;; ============================================================================

(deftest tier-order-is-the-ranking
  (testing "tiers run cheapest → most capable"
    (is (= [:light :standard :deep] config/tier-order)))

  (testing "coerce-tier accepts keywords, strings, and leading-colon strings"
    (is (= :light    (config/coerce-tier :light)))
    (is (= :deep     (config/coerce-tier "deep")))
    (is (= :standard (config/coerce-tier ":standard"))))

  (testing "an unknown tier is nil, not a default — 'unspecified' and 'light' are different events"
    (is (nil? (config/coerce-tier "bogus")))
    (is (nil? (config/coerce-tier nil)))
    (is (nil? (config/coerce-tier 3)))))

;; ============================================================================
;; Clamping
;; ============================================================================

(deftest clamp-tier-bounds-a-request
  (testing "a request above :max is pulled down and flagged"
    (let [r (config/clamp-tier :deep {:default :light :max :standard})]
      (is (= :standard (:tier r)))
      (is (true? (:clamped? r)))
      (is (= :deep (:from r)))))

  (testing "a request below :min is pulled up and flagged"
    (let [r (config/clamp-tier :light {:default :deep :min :standard})]
      (is (= :standard (:tier r)))
      (is (true? (:clamped? r)))))

  (testing "a request inside the window passes through unflagged"
    (is (= {:tier :standard :clamped? false :from :standard}
           (config/clamp-tier :standard {:min :light :max :deep}))))

  (testing "an entry with no bounds constrains nothing"
    (is (= :deep  (:tier (config/clamp-tier :deep {}))))
    (is (= :light (:tier (config/clamp-tier :light {})))))

  (testing "an inverted window (:min above :max) widens to :min rather than pinning everything to it"
    ;; Guards a malformed config: without the fix, hi < lo makes every request
    ;; clamp to :min, silently. Widening keeps :min reachable and honest.
    (let [r (config/clamp-tier :deep {:min :deep :max :light})]
      (is (= :deep (:tier r))))))

;; ============================================================================
;; Per-specialist resolution
;; ============================================================================

(deftest resolve-work-tier-uses-the-table
  (testing "no request → the specialist's :default"
    (is (= :light (:tier (config/resolve-work-tier nil :config-agent))))
    (is (= :deep  (:tier (config/resolve-work-tier nil :plan-agent)))))

  (testing "a request is clamped by the specialist's window"
    (let [r (config/resolve-work-tier nil :config-agent :deep)]
      (is (= :standard (:tier r)) "config-agent is capped at :standard")
      (is (true? (:clamped? r)))))

  (testing "a request inside the window is honored"
    (is (= :deep (:tier (config/resolve-work-tier nil :explore-agent :deep)))))

  (testing "an agent absent from the table is unconstrained :standard — new and user-authored agents behave as today"
    (let [r (config/resolve-work-tier nil :some-user-authored-agent)]
      (is (= :standard (:tier r)))
      (is (nil? (:entry r))))
    (is (= :deep (:tier (config/resolve-work-tier nil :some-user-authored-agent :deep)))))

  (testing "an unparseable requested tier falls back to the default rather than erroring"
    (is (= :light (:tier (config/resolve-work-tier nil :config-agent "nonsense"))))))

;; ============================================================================
;; Tier → LM
;; ============================================================================

(deftest resolve-tier-lm-is-inert-by-default
  ;; Asserts the SCHEMA default, deliberately not `get-config`'s merged view.
  ;; Reading the merged value would make this test fail on any machine whose
  ;; .brainyard/config.edn actually configures tiers — i.e. it would punish
  ;; adopting the feature. The invariant that matters is what SHIPS.
  (testing "the shipped schema default maps every tier to nothing"
    (let [shipped (get-in config/config-schema [:agent-lm-tiers :default])]
      (is (= #{:light :standard :deep} (set (keys shipped))))
      (is (every? nil? (vals shipped))
          "a stock build must not re-point any dispatch at a different model")))

  (testing "with the shipped default in effect, every tier resolves to nothing"
    (set-tiers! (get-in config/config-schema [:agent-lm-tiers :default]))
    (doseq [t config/tier-order]
      (is (nil? (config/resolve-tier-lm nil t))
          (str "tier " t " must resolve to nil on a stock build")))))

(deftest resolve-tier-lm-resolves-a-configured-label
  (set-tiers! {:light    "bedrock/amazon.nova-lite-v1:0"
               :standard nil
               :deep     "bedrock/anthropic.claude-opus-5"})

  (testing "a configured tier parses to an lm-config"
    (let [l (config/resolve-tier-lm nil :light)]
      (is (= :bedrock (:provider l)))
      (is (some? (:model l)))))

  (testing "a nil tier stays inert — it means 'use whatever the agent would have used'"
    (is (nil? (config/resolve-tier-lm nil :standard))))

  (testing "an unknown tier resolves to nothing"
    (is (nil? (config/resolve-tier-lm nil :enormous)))))

(deftest resolve-tier-lm-swallows-a-bad-label
  (testing "an unparseable label is inert, not a crash and not a silent pin to the session model"
    ;; Falling back to the main LM here would make a typo invisible: every
    ;; dispatch would keep working while the tier quietly did nothing.
    (set-tiers! {:light "not-a-provider-slash-model"})
    (is (nil? (config/resolve-tier-lm nil :light))))

  (testing "a blank label is inert"
    (set-tiers! {:light "   "})
    (is (nil? (config/resolve-tier-lm nil :light)))))

;; ============================================================================
;; Config-schema integration
;; ============================================================================

(deftest tier-keys-are-claimed-by-a-feature
  (testing "both new schema keys belong to :agents/work-tiers"
    ;; core.feature asserts a total partition of the schema; an unclaimed key
    ;; is a standing to-do, not a harmless omission.
    (let [ks (set (:keys (get feature/feature-registry :agents/work-tiers)))]
      (is (contains? ks :agent-lm-tiers))
      (is (contains? ks :agent-tier-map))))

  (testing "the feature requires subagents — a tier is meaningless without a dispatch"
    (is (contains? (:requires (get feature/feature-registry :agents/work-tiers))
                   :agents/subagents))))

(deftest tier-map-covers-the-built-in-specialists
  (testing "every specialist the router can route to has a tier entry"
    (let [m (get-in config/config-schema [:agent-tier-map :default])]
      (doseq [a [:explore-agent :plan-agent :todo-agent :exec-agent :eval-agent
                 :edit-agent :research-agent :workflow-agent :rlm-agent
                 :skill-agent :mcp-agent :tool-agent :meta-agent :memory-agent
                 :init-agent :config-agent :schedule-agent :event-agent
                 :state-machine-agent]]
        (is (contains? m a) (str a " has no work-tier entry")))))

  (testing "every entry's tiers are valid and its window is not inverted"
    (doseq [[a {:keys [default min max]}] (get-in config/config-schema [:agent-tier-map :default])]
      (is (some? (config/coerce-tier default)) (str a " :default"))
      (when min (is (some? (config/coerce-tier min)) (str a " :min")))
      (when max (is (some? (config/coerce-tier max)) (str a " :max")))
      ;; The default must itself be reachable, else the entry contradicts itself.
      (let [r (config/clamp-tier (config/coerce-tier default) {:min min :max max})]
        (is (false? (:clamped? r))
            (str a " :default " default " is outside its own [:min :max] window")))))

  (testing "agents whose output is prose or a gating verdict are floor-capped"
    (let [m (get-in config/config-schema [:agent-tier-map :default])]
      (doseq [a [:plan-agent :eval-agent :research-agent :workflow-agent]]
        (is (some? (:min (get m a)))
            (str a " should have a :min floor — a weak model here produces "
                 "prose a human acts on or a verdict that gates work"))))))

;; ============================================================================
;; Usage attribution (P0.3)
;; ============================================================================

(deftest usage-attribution-rolls-up-by-agent
  (let [tracker (llm/create-usage-tracker)
        record! (requiring-resolve 'ai.brainyard.clj-llm.core.usage/record-usage!)]

    (testing "an attributed call lands under its agent-type"
      (llm/with-usage-attribution* {:agent-id :plan-agent/abc :agent-type :plan-agent}
        (fn [] (record! tracker {:model "m1" :input-tokens 100 :output-tokens 50
                                 :total-tokens 150 :cost {:total-cost 0.25}})))
      (let [s (llm/get-usage-summary tracker)]
        (is (= 0.25 (get-in s [:by-agent :plan-agent :total-cost])))
        (is (= 1 (get-in s [:by-agent :plan-agent :call-count])))
        (is (= #{"m1"} (get-in s [:by-agent :plan-agent :models])))))

    (testing "an UNattributed call still updates totals but adds no agent bucket"
      (record! tracker {:model "m2" :input-tokens 10 :output-tokens 5
                        :total-tokens 15 :cost {:total-cost 0.01}})
      (let [s (llm/get-usage-summary tracker)]
        (is (= 2 (get-in s [:totals :call-count])))
        (is (= #{:plan-agent} (set (keys (:by-agent s))))
            "an unattributed call must not invent a bucket")))

    (testing "totals stay the sum of everything, attributed or not"
      (is (= 0.26 (get-in (llm/get-usage-summary tracker) [:totals :total-cost]))))

    (testing "two agents accumulate separately"
      (llm/with-usage-attribution* {:agent-id :exec-agent/xyz :agent-type :exec-agent}
        (fn [] (record! tracker {:model "m3" :input-tokens 20 :output-tokens 10
                                 :total-tokens 30 :cost {:total-cost 0.05}})))
      (let [s (llm/get-usage-summary tracker)]
        (is (= #{:plan-agent :exec-agent} (set (keys (:by-agent s)))))
        (is (= 0.05 (get-in s [:by-agent :exec-agent :total-cost])))))))

(deftest usage-attribution-nil-passes-through
  (testing "a nil attribution does not clobber an outer binding"
    (let [tracker (llm/create-usage-tracker)
          record! (requiring-resolve 'ai.brainyard.clj-llm.core.usage/record-usage!)]
      (llm/with-usage-attribution* {:agent-id :a/one :agent-type :outer-agent}
        (fn []
          (llm/with-usage-attribution* nil
            (fn [] (record! tracker {:model "m" :input-tokens 1 :output-tokens 1
                                     :total-tokens 2 :cost {:total-cost 0.1}})))))
      (is (= #{:outer-agent} (set (keys (:by-agent (llm/get-usage-summary tracker)))))))))

(deftest reset-tracker-clears-the-agent-rollup
  (testing "reset drops :by-agent along with the other rollups"
    (let [tracker (llm/create-usage-tracker)
          record! (requiring-resolve 'ai.brainyard.clj-llm.core.usage/record-usage!)]
      (llm/with-usage-attribution* {:agent-id :a/one :agent-type :plan-agent}
        (fn [] (record! tracker {:model "m" :input-tokens 1 :output-tokens 1
                                 :total-tokens 2 :cost {:total-cost 0.1}})))
      (llm/reset-tracker! tracker)
      (let [s (llm/get-usage-summary tracker)]
        (is (= {} (:by-agent s)))
        (is (= 0 (get-in s [:totals :call-count])))))))

;; ============================================================================
;; Pricing coverage (P0.1 / P0.2)
;; ============================================================================

(deftest pricing-coverage-partitions-the-catalog
  (let [{:keys [priced unpriced not-applicable counts]} (llm/pricing-coverage)]

    (testing "the three buckets account for every catalog entry"
      (is (= (:total counts)
             (+ (count priced) (count unpriced) (count not-applicable))))
      (is (pos? (:priced counts)) "the pricing table is not empty"))

    (testing "providers that cannot carry a per-token rate are not reported as gaps"
      ;; claude-code reports cost_usd directly; ollama/apple-fm are local.
      (let [na (set (map :provider not-applicable))]
        (is (contains? na :claude-code)))
      (is (empty? (filter #(#{:claude-code :ollama :apple-fm :free-llm} (:provider %))
                          unpriced))))

    (testing "a known-priced model lands in :priced"
      (is (some #(= "claude-sonnet-5" (:model %)) priced)))

    (testing "results are sorted by provider then model — a stable diff between runs"
      (is (= (sort-by (juxt #(name (:provider %)) :model) unpriced) unpriced)))))

(deftest pricing-coverage-curated-only-is-a-subset
  (let [all     (:counts (llm/pricing-coverage))
        curated (:counts (llm/pricing-coverage :curated-only? true))]
    (testing "curated-only narrows to the picker-visible set"
      (is (<= (:total curated) (:total all))))))

(deftest get-pricing-normalizes-bedrock-ids
  (testing "a region-prefixed, versioned Bedrock id still finds its rate"
    (is (some? (llm/get-pricing :bedrock "us.amazon.nova-lite-v1:0")))
    (is (= (llm/get-pricing :bedrock "amazon.nova-lite")
           (llm/get-pricing :bedrock "us.amazon.nova-lite-v1:0"))))

  (testing "an unpriced pair is nil, not zero — unknown must not read as free"
    (is (nil? (llm/get-pricing :bedrock "totally.made.up.model")))))
