;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.clj-llm.catalog-test
  "The refresh overlay's merge rules.

   These are the invariants that keep a refresh from doing damage — it runs
   unattended, against remote endpoints, and the failure modes are all quiet
   ones: a catalog silently emptied by an outage, a working model deleted
   because the user was in another AWS region, an embedding model appearing in
   the chat picker. Each has a test here."
  (:require [ai.brainyard.clj-llm.core.catalog :as cat]
            [ai.brainyard.clj-llm.core.providers :as providers]
            [clojure.test :refer [deftest is testing use-fixtures]]))

(defn- clean-overlay [t]
  (cat/clear-overlay!)
  (try (t) (finally (cat/clear-overlay!))))

(use-fixtures :each clean-overlay)

(def ^:private baked
  (array-map
   :openai [{:model "gpt-a" :curated-rank 1 :description "A"}
            {:model "gpt-b"}]
   :claude-code [{:model "opus" :curated-rank 0 :description "CLI"}]))

;; ============================================================================
;; Merge
;; ============================================================================

(deftest curation-survives-a-refresh-test
  (testing "a model the provider still serves keeps its rank and description"
    (let [merged (cat/merge-catalog baked {:openai {:models #{"gpt-a" "gpt-b"}}})
          a      (first (filter #(= "gpt-a" (:model %)) (:openai merged)))]
      (is (= 1 (:curated-rank a)))
      (is (= "A" (:description a))))))

(deftest retires-what-the-provider-dropped-test
  (testing "a catalogued model the provider no longer serves is removed"
    (let [merged (cat/merge-catalog baked {:openai {:models #{"gpt-a"}}})]
      (is (= ["gpt-a"] (mapv :model (:openai merged)))))))

(deftest discovered-models-are-usable-but-never-curated-test
  (testing "a newly served model is added WITHOUT a rank, so it cannot reach
            the picker on its own — curation stays human"
    (let [merged (cat/merge-catalog baked {:openai {:models #{"gpt-a" "gpt-new"}}})
          n      (first (filter #(= "gpt-new" (:model %)) (:openai merged)))]
      (is (true? (:discovered? n)))
      (is (nil? (:curated-rank n)) "a discovered model must have no rank")
      (is (nil? (:description n))))))

;; ============================================================================
;; Safety rules — each corresponds to a way a refresh could do damage
;; ============================================================================

(deftest absent-provider-is-untouched-test
  (testing "a provider with no overlay entry passes through unchanged —
            the offline / no-credentials / first-run path"
    (is (= baked (cat/merge-catalog baked {})))))

(deftest empty-fetch-never-empties-the-catalog-test
  (testing "an entry with no models is ignored, not applied: a failed fetch
            must not be read as 'this provider serves nothing'"
    (is (= baked (cat/merge-catalog baked {:openai {:models #{}}})))
    (cat/set-overlay! {:openai {:models #{}}})
    (is (= {} (cat/overlay)) "an unusable entry is not even stored")))

(deftest non-enumerable-providers-cannot-be-overlaid-test
  (testing "claude-code and friends have no list endpoint, so an overlay for
            them is a bug rather than data"
    (is (= baked (cat/merge-catalog baked {:claude-code {:models #{"bogus"}}})))
    (is (false? (cat/overlayable? :claude-code)))
    (is (false? (cat/overlayable? :free-llm)))
    (is (true? (cat/overlayable? :ollama)))))

(deftest partial-fetch-is-additive-only-test
  (testing "a region-scoped fetch may add but never retire: one region's
            inventory cannot prove a model is globally gone, and the catalog
            deliberately carries us-east-1-only entries"
    (let [merged (cat/merge-catalog baked {:openai {:models #{"gpt-new"}
                                                    :partial? true}})]
      (is (= #{"gpt-a" "gpt-b" "gpt-new"} (set (mapv :model (:openai merged))))
          "nothing retired despite gpt-a/gpt-b being absent from the fetch"))))

;; ============================================================================
;; Drift
;; ============================================================================

(deftest drift-reports-both-directions-test
  (let [d (cat/drift baked {:openai {:models #{"gpt-a" "gpt-new"}
                                     :fetched-at "2026-01-01T00:00:00Z"}})]
    (is (= ["gpt-b"] (mapv :model (:retired (:openai d)))))
    (is (= ["gpt-new"] (:discovered (:openai d))))))

(deftest drift-is-silent-without-a-refresh-test
  (testing "nothing refreshed means nothing to report — an offline run must
            not claim the whole catalog vanished"
    (is (= {} (cat/drift baked {})))))

(deftest drift-hides-dated-snapshots-of-known-models-test
  (testing "providers list every pinned snapshot beside its moving alias;
            reporting those buries the lines a human must act on"
    (let [d (cat/drift baked {:openai {:models #{"gpt-a" "gpt-a-2025-04-14" "gpt-real-new"}}})]
      (is (= ["gpt-real-new"] (:discovered (:openai d)))))))

(deftest partial-drift-reports-no-retirements-test
  (testing "a partial fetch retires nothing, so it must not REPORT retirements
            either — otherwise every us-east-1-only Bedrock model shows as gone
            whenever drift runs from another region"
    (let [d (cat/drift baked {:openai {:models #{"gpt-a"} :partial? true}})]
      (is (empty? (:retired (:openai d)))))))

;; ============================================================================
;; Integration with the live catalog
;; ============================================================================

(deftest no-overlay-means-byte-identical-behaviour-test
  (testing "until something refreshes, the effective catalog IS the baked one"
    (is (= providers/model-catalog (providers/current-catalog)))))

(deftest deliberately-excluded-models-are-never-discovered-test
  (testing "ids probed and rejected stay rejected — as data, so the refresh
            honours them instead of re-proposing them on every run"
    (doseq [id ["gpt-5-pro" "gpt-5.5-pro" "gpt-5.4-pro-2026-03-05"
                "gpt-5.3-codex" "gpt-5.1-codex-max" "o1-pro"
                "gpt-5-chat-latest" "gpt-5-search-api"]]
      (is (true? (providers/excluded-model? :openai id)) (str id " must stay excluded")))
    (doseq [id ["gpt-5.6-terra" "gpt-5.5" "gpt-4.1" "o3" "gpt-5.4"]]
      (is (false? (providers/excluded-model? :openai id))
          (str id " is a real chat model and must NOT be excluded")))
    (testing "exclusions are per provider, not global"
      (is (false? (providers/excluded-model? :ollama "some-pro"))))))

(deftest overlay-reaches-provider-detection-test
  (testing "a discovered model resolves to its provider, which is what makes
            it usable at all"
    (cat/set-overlay! {:openai {:models #{"gpt-5" "totally-new-model"}}})
    (is (= :openai (providers/get-provider-from-model "totally-new-model")))))
