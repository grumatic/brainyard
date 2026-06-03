;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent-tui.bootstrap-driver-test
  "Tests for the executor extraction + re-run-rung programmatic entry."
  (:require [ai.brainyard.agent-tui.bootstrap :as boot]
            [ai.brainyard.agent-tui.bootstrap-driver :as driver]
            [ai.brainyard.agent-tui.smoke-test :as smoke]
            [ai.brainyard.agent.core.config :as core-config]
            [ai.brainyard.env-detect.interface :as env]
            [clojure.test :refer [deftest is testing]]))

;; All `re-run-rung!` tests mock the core-config read/write so they don't
;; touch the developer's real ~/.brainyard. The mock holds an atom of the
;; "on-disk" config so the test can assert what got persisted.

(def ^:dynamic *fake-config* (atom {}))

(defn- mock-core-config [body]
  (reset! *fake-config* {})
  (with-redefs [core-config/init-dirs! (constantly {:user-dir "/u"
                                                    :project-dir "/p"
                                                    :working-dir "/p"})
                core-config/read-edn-config (fn [_ & _] @*fake-config*)
                core-config/write-edn-config! (fn [_ cfg & _]
                                                (reset! *fake-config* cfg)
                                                "/p/.brainyard/config.edn")]
    (body)))

;; ============================================================================
;; Callback contract — silent defaults, custom overrides
;; ============================================================================

(deftest silent-callbacks-have-all-keys
  (let [keys' (set (keys driver/silent-callbacks))]
    (is (contains? keys' :on-status))
    (is (contains? keys' :on-install-confirm))
    (is (contains? keys' :on-install-hint))
    (is (contains? keys' :on-pull-progress))))

(deftest silent-on-install-confirm-denies
  ;; Programmatic callers (config-agent without --auto) get install-deny
  (is (false? ((:on-install-confirm driver/silent-callbacks) {:command "x"}))))

;; ============================================================================
;; execute-action! dispatches to the right helper
;; ============================================================================

(deftest dispatch-smoke-test
  (let [calls (atom [])]
    (with-redefs [smoke/smoke-test! (fn [provider model timeout-ms]
                                      (swap! calls conj [:smoke provider model timeout-ms])
                                      {:ok? true :latency-ms 42})]
      (let [r (driver/execute-action! {:action :smoke-test
                                       :provider :anthropic
                                       :model "opus"
                                       :timeout-ms 999}
                                      {})]
        (is (true? (:ok? r)))
        (is (= [[:smoke :anthropic "opus" 999]] @calls))))))

(deftest dispatch-pull-model
  (let [calls (atom [])]
    (with-redefs [env/pull-ollama-model! (fn [model _on-progress]
                                           (swap! calls conj [:pull model])
                                           {:ok? true :model model :duration-ms 10})]
      (let [r (driver/execute-action! {:action :pull-model :model "glm-4.5-air"}
                                      {})]
        (is (true? (:ok? r)))
        (is (= [[:pull "glm-4.5-air"]] @calls))))))

;; ============================================================================
;; install-ollama! respects on-install-confirm callback
;; ============================================================================

(deftest install-callback-decline
  (let [calls (atom [])]
    (with-redefs [env/install-ollama! (fn [_cmd]
                                        (swap! calls conj :install)
                                        {:ok? true})]
      (let [r (driver/install-ollama!
               {:hints {:command "brew install ollama" :auto-installable? true}}
               {:on-install-confirm (constantly false)})]
        (is (false? (:ok? r)))
        (is (re-find #"declined" (:detail r)))
        ;; install was NOT called
        (is (empty? @calls))))))

(deftest install-callback-approve
  (let [calls (atom [])]
    (with-redefs [env/install-ollama! (fn [cmd]
                                        (swap! calls conj cmd)
                                        {:ok? true :detail "ran"})]
      (let [r (driver/install-ollama!
               {:hints {:command "brew install ollama" :auto-installable? true}}
               {:on-install-confirm (constantly true)})]
        (is (true? (:ok? r)))
        (is (= ["brew install ollama"] @calls))))))

(deftest install-not-installable-skips-confirm
  (let [confirm-calls (atom 0)]
    (let [r (driver/install-ollama!
             {:hints {:command nil :auto-installable? false}}
             {:on-install-confirm (fn [_] (swap! confirm-calls inc) true)})]
      (is (false? (:ok? r)))
      (is (re-find #"manual install" (:detail r)))
      (is (zero? @confirm-calls)))))

;; ============================================================================
;; re-run-rung! — synthesises a chosen, runs plan-actions
;; ============================================================================

(defn- stub-detection
  "Mockable detection with claude-CLI available."
  []
  {:providers      [{:provider :claude-code :available? true :method :cli}
                    {:provider :anthropic   :available? true :method :api-key}]
   :ollama-install {:installed? false :daemon-running? false :pulled-models []}
   :network        {:huggingface? true :ollama? true}
   :os             {:name "Mac OS X"}})

(deftest re-run-rung-c
  (mock-core-config
   (fn []
     (with-redefs [env/detect-all (fn [] (stub-detection))
                   smoke/smoke-test! (fn [_ _ _] {:ok? true :latency-ms 100})]
       (let [r (driver/re-run-rung! {:rung :c :auto? true} {})]
         (is (true? (:ok? r)))
         (is (= :claude-code (get-in r [:chosen :provider])))
         (is (= (boot/default-model :claude-code) (get-in r [:chosen :model])))
         (is (true? (get-in r [:result :smoke-test :ok?])))
         (testing "config.edn was actually written"
           (is (= :claude-code (get-in @*fake-config* [:llm :default-provider])))
           (is (= (boot/default-model :claude-code) (get-in @*fake-config* [:llm :default-model])))
           (is (= :c           (get-in @*fake-config* [:bootstrap :rung])))))))))

(deftest re-run-rung-b-priority-pick
  (mock-core-config
   (fn []
     (with-redefs [env/detect-all (fn [] (stub-detection))
                   smoke/smoke-test! (fn [_ _ _] {:ok? true :latency-ms 100})]
       (let [r (driver/re-run-rung! {:rung :b :auto? true} {})]
         (is (true? (:ok? r)))
         ;; anthropic is highest priority among API-key providers in detection
         (is (= :anthropic (get-in r [:chosen :provider])))
         (is (= :anthropic (get-in @*fake-config* [:llm :default-provider]))))))))

(deftest re-run-rung-e-without-auto-refuses
  (with-redefs [env/detect-all (fn [] (stub-detection))]
    (let [r (driver/re-run-rung! {:rung :e :auto? false} {})]
      (is (true? (:requires-interactivity? r)))
      (is (re-find #":auto\? true" (:reason r))))))

(deftest re-run-rung-g-refused
  (with-redefs [env/detect-all (fn [] (stub-detection))]
    (let [r (driver/re-run-rung! {:rung :g} {})]
      (is (re-find #"stop sentinel" (:reason r))))))

(deftest re-run-rung-b-with-explicit-provider
  (mock-core-config
   (fn []
     (with-redefs [env/detect-all (fn [] (stub-detection))
                   smoke/smoke-test! (fn [_ _ _] {:ok? true :latency-ms 50})]
       (let [r (driver/re-run-rung! {:rung :b :provider :anthropic
                                     :model "claude-opus-4-6" :auto? true} {})]
         (is (= :anthropic (get-in r [:chosen :provider])))
         (is (= "claude-opus-4-6" (get-in r [:chosen :model])))
         (is (= "claude-opus-4-6" (get-in @*fake-config* [:llm :default-model]))))))))
