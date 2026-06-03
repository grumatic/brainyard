;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent-tui.bootstrap-test
  (:require [clojure.test :refer [deftest is testing]]
            [ai.brainyard.agent-tui.bootstrap :as boot]))

;; ============================================================================
;; Detection fixture builder
;; ============================================================================

(defn- providers-fixture
  "Build the :providers vec from a map of provider→available?."
  [m]
  (mapv (fn [[p ok?]] {:provider p :available? ok? :method (cond
                                                             (= p :ollama)      :network
                                                             (= p :apple-fm)    :network
                                                             (= p :claude-code) :cli
                                                             :else              :api-key)})
        m))

(defn- detection
  [& {:keys [api-keys ollama claude apple-fm
             ollama-installed? daemon? pulled
             huggingface? ollama-egress? os]
      :or {os                {:name "Mac OS X" :version "26.1" :arch "aarch64"}
           ollama            false
           claude            false
           apple-fm          false
           ollama-installed? false
           daemon?           false
           pulled            []
           huggingface?      true
           ollama-egress?    true}}]
  {:providers (providers-fixture
               (merge (zipmap [:openai :anthropic :google :groq :deepseek :mistral
                               :together :fireworks :openrouter :azure]
                              (repeat false))
                      (or api-keys {})
                      {:ollama ollama :claude-code claude :apple-fm apple-fm}))
   :ollama-install {:installed?      ollama-installed?
                    :daemon-running? daemon?
                    :pulled-models   (vec pulled)
                    :binary-path     (when ollama-installed? "/usr/local/bin/ollama")
                    :version         (when ollama-installed? "0.5.0")}
   :network        {:huggingface? huggingface? :ollama? ollama-egress?}
   :os             os})

;; ============================================================================
;; 12 scenario fixtures, per design §13.1
;; ============================================================================

(deftest scenario-1-existing-reachable
  (testing "(a) existing config with reachable provider — no-op"
    (let [det (detection :api-keys {:anthropic true})
          cfg {:llm {:default-provider :anthropic :default-model "claude-opus-4-6"}}
          out (boot/choose-rung det cfg :dev)]
      (is (= :a (:rung out)))
      (is (= :anthropic (:provider out)))
      (is (= "claude-opus-4-6" (:model out))))))

(deftest scenario-2-existing-unreachable-falls-through
  (testing "Existing config but provider not reachable — falls to next rung"
    (let [det (detection :api-keys {:openai true})
          cfg {:llm {:default-provider :anthropic :default-model "x"}}
          out (boot/choose-rung det cfg :dev)]
      (is (= :b (:rung out)))
      (is (= :openai (:provider out))))))

(deftest scenario-3-multiple-api-keys-priority
  (testing "(b) multiple API keys — priority picks anthropic over openai"
    (let [det (detection :api-keys {:anthropic true :openai true :google true})
          out (boot/choose-rung det {} :dev)]
      (is (= :b (:rung out)))
      (is (= :anthropic (:provider out)))
      (is (= #{:anthropic :openai :google} (set (:available-providers out)))))))

(deftest scenario-4-claude-cli-only
  (testing "(c) only claude CLI is on PATH"
    (let [det (detection :claude true)
          out (boot/choose-rung det {} :dev)]
      (is (= :c (:rung out)))
      (is (= :claude-code (:provider out)))
      ;; The default model is derived live from clj-llm/get-popular-models and
      ;; drifts as that list evolves — assert rung :c wires claude-code's
      ;; default through, not a hardcoded model name.
      (is (= (boot/default-model :claude-code) (:model out)))
      (is (string? (:model out))))))

(deftest scenario-5-ollama-with-models
  (testing "(d) Ollama running with pulled models"
    (let [det (detection :ollama true :ollama-installed? true
                         :daemon? true :pulled ["glm-4.5-air:latest" "llama3"])
          out (boot/choose-rung det {} :dev)]
      (is (= :d (:rung out)))
      (is (= :ollama (:provider out)))
      (is (= "glm-4.5-air:latest" (:model out))))))

(deftest scenario-6-ollama-with-only-embedding
  (testing "(d) does NOT fire when the only pulled model is an embedding"
    (let [det (detection :ollama true :ollama-installed? true
                         :daemon? true :pulled ["nomic-embed-text"])
          out (boot/choose-rung det {} :dev)]
      (is (= :e (:rung out)))
      (is (= "glm-4.5-air" (:model out))))))

(deftest scenario-7-nothing-but-egress
  (testing "(e) clean machine — offer install + pull"
    (let [det (detection)
          out (boot/choose-rung det {} :dev)]
      (is (= :e (:rung out)))
      (is (= :ollama (:provider out)))
      (is (= "glm-4.5-air" (:model out)))
      (is (true? (:install? out)))
      (is (true? (:pull? out))))))

(deftest scenario-8-ollama-installed-not-running
  (testing "(e) Ollama installed but daemon not running — start + pull"
    (let [det (detection :ollama-installed? true)
          out (boot/choose-rung det {} :dev)]
      (is (= :e (:rung out)))
      (is (false? (:install? out)))
      (is (true? (:pull? out))))))

(deftest scenario-9-ci-profile-blocks-install
  (testing "CI profile: nothing but ollama-installable → (g) stop"
    (let [det (detection)
          out (boot/choose-rung det {} :ci)]
      (is (= :g (:rung out)))
      (is (nil? (:provider out))))))

(deftest scenario-10-offline-profile-allows-d-blocks-e
  (testing "offline profile: with daemon + model → (d) ok; without → (g)"
    (let [det-ok (detection :ollama true :ollama-installed? true
                            :daemon? true :pulled ["glm-4.5-air"])
          det-no (detection)]
      (is (= :d (:rung (boot/choose-rung det-ok {} :offline))))
      (is (= :g (:rung (boot/choose-rung det-no {} :offline)))))))

(deftest scenario-11-cloud-profile-prefers-cloud-model
  (testing "cloud profile: rung (e) defaults to glm-5:cloud"
    (let [det (detection :ollama-installed? true :daemon? true)
          out (boot/choose-rung det {} :cloud)]
      (is (= :e (:rung out)))
      (is (= "glm-5:cloud" (:model out))))))

(deftest scenario-12-apple-fm-below-ollama
  (testing "(f) Apple FM only fires when (e) is unavailable; rung-order check"
    (let [det (detection :apple-fm true)
          out (boot/choose-rung det {} :dev)]
      ;; (e) is offered (install path) before (f) on default profile
      (is (= :e (:rung out))))
    (testing "but on a profile that excludes (e), (f) does fire"
      (let [det (detection :apple-fm true)
            prof (assoc-in (boot/resolve-profile :dev) [:allowed-rungs] #{:a :b :c :d :f :g})
            out  (boot/choose-rung det {} prof)]
        (is (= :f (:rung out)))
        (is (= :apple-fm (:provider out)))))))

;; ============================================================================
;; plan-actions / merge-delta
;; ============================================================================

(deftest plan-actions-rung-e-full-pipeline
  (testing "rung (e) on clean machine emits install → start → pull → smoke (persistence is not an action — handled by run!)"
    (let [det     (detection)
          chosen  (boot/choose-rung det {} :dev)
          actions (boot/plan-actions chosen det)
          kinds   (mapv :action actions)]
      (is (= [:install-ollama :start-daemon :pull-model :smoke-test]
             kinds)))))

(deftest plan-actions-rung-d-skips-install
  (testing "rung (d) emits only smoke-test (persistence is not an action)"
    (let [det     (detection :ollama true :ollama-installed? true
                             :daemon? true :pulled ["glm-4.5-air"])
          chosen  (boot/choose-rung det {} :dev)
          actions (boot/plan-actions chosen det)]
      (is (= [:smoke-test] (mapv :action actions))))))

(deftest plan-actions-rung-g-emits-no-actions
  (testing "rung (g) emits no side-effect actions — the stub-config write happens in run! via merge-delta's :incomplete? branch"
    (let [det     (detection)
          chosen  (boot/choose-rung det {} :ci)
          actions (boot/plan-actions chosen det)]
      (is (= [] (mapv :action actions))))))

(deftest merge-delta-preserves-existing-keys
  (testing "merge-delta does not stomp :permissions/:agent/:mcp"
    (let [existing {:permissions {:mode :ask-each-time :allowed-dirs ["/home/x"]}
                    :agent       {:default-agent :coact-agent}
                    :mcp         {:servers {:foo {}}}}
          det      (detection :api-keys {:anthropic true})
          chosen   (boot/choose-rung det existing :dev)
          result   {:smoke-test {:ok? true :latency-ms 100}}
          out      (boot/merge-delta existing det chosen result)]
      (is (= (:permissions existing) (:permissions out)))
      (is (= (:agent existing) (:agent out)))
      (is (= (:mcp existing) (:mcp out)))
      (is (= :anthropic (get-in out [:llm :default-provider])))
      (is (= :b (get-in out [:bootstrap :rung])))
      (is (false? (get-in out [:bootstrap :incomplete]))))))

(deftest merge-delta-rung-g-marks-incomplete
  (testing "rung (g) writes :bootstrap/incomplete true"
    (let [det      (detection)
          chosen   (boot/choose-rung det {} :ci)
          result   {:smoke-test nil :next-steps ["Set ANTHROPIC_API_KEY" "Re-run bb tui config"]}
          out      (boot/merge-delta {} det chosen result)]
      (is (true? (get-in out [:bootstrap :incomplete])))
      (is (= ["Set ANTHROPIC_API_KEY" "Re-run bb tui config"]
             (get-in out [:bootstrap :next-steps]))))))

(deftest rotate-log-keeps-last-5
  (testing "rotate-log retains 5 entries in newest-first order"
    (let [seeded (reduce (fn [acc i] (boot/rotate-log acc {:i i}))
                         {}
                         (range 10))]
      (is (= [9 8 7 6 5] (mapv :i (:entries seeded)))))))
