;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent-tui.config-command-test
  "`/config unset KEY`, the TUI twin of `agent-runtime$config :unset true`.
   The agent config layer is stubbed; `unset-config!` itself is covered in
   agent.core.config-test."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [ai.brainyard.agent-tui.autocomplete :as ac]
            [ai.brainyard.agent-tui.commands :as commands]
            [ai.brainyard.agent-tui.session :as tui-session]
            [ai.brainyard.agent.interface :as agent]))

(defn- run-config
  "Run `/config <args>` against a stub agent. Returns the joined emitted text
   plus every unset-config!/set-config! call the handler made."
  ([args] (run-config args {}))
  ([args {:keys [source value] :or {source :default}}]
   (let [out   (atom [])
         unset (atom [])
         sets  (atom [])]
     (with-redefs [tui-session/get-active-agent (fn [] ::ag)
                   tui-session/emit!            (fn [& xs] (swap! out conj (str/join " " xs)))
                   agent/unset-config!          (fn [ag k] (swap! unset conj [ag k]) value)
                   agent/set-config!            (fn [ag k v] (swap! sets conj [ag k v]) v)
                   agent/get-config             (fn [_ _] value)
                   agent/config-source          (fn [_ _] source)]
       (#'commands/handle-config-command args))
     {:out (str/join "\n" @out) :unset @unset :sets @sets})))

(deftest unset-is-not-a-config-key
  (is (not (contains? agent/config-keys :unset))
      "/config unset KEY would otherwise shadow a real key named unset"))

(deftest unset-resets-a-nil-default-key
  (let [{:keys [out unset sets]} (run-config "unset sub-lm-config")]
    (is (= [[::ag :sub-lm-config]] unset))
    (is (empty? sets))
    (is (str/includes? out "sub-lm-config unset = nil"))
    (is (str/includes? out "source: default"))))

(deftest unset-refuses-bad-input
  (testing "no key: usage, nothing unset"
    (let [{:keys [out unset]} (run-config "unset")]
      (is (empty? unset))
      (is (str/includes? out "Usage: /config unset KEY"))))
  (testing "unknown key"
    (let [{:keys [out unset]} (run-config "unset not-a-real-key")]
      (is (empty? unset))
      (is (str/includes? out "Unknown config key: not-a-real-key"))))
  (testing "read-only key"
    (when-let [k (first (sort agent/read-only-keys))]
      (let [{:keys [out unset]} (run-config (str "unset " (subs (str k) 1)))]
        (is (empty? unset))
        (is (str/includes? out "read-only"))))))

(deftest unset-explains-a-layer-that-still-wins
  (is (str/includes? (:out (run-config "unset sub-lm-config" {:source :env :value "x/y"}))
                     "environment variable still overrides"))
  (is (str/includes? (:out (run-config "unset sub-lm-config" {:source :session :value "x/y"}))
                     "session-level value still applies")))

(deftest unset-flags-restart-keys
  (when-let [k (->> (sort agent/config-keys)
                    (remove agent/read-only-key?)
                    (filter agent/requires-restart-key?)
                    first)]
    (is (str/includes? (:out (run-config (str "unset " (subs (str k) 1)))) "restart"))))

(deftest set-form-unchanged
  (let [{:keys [unset sets]} (run-config "max-iterations 7")]
    (is (empty? unset))
    (is (= [[::ag :max-iterations 7]] sets))))

(deftest unset-submenu-lists-writable-keys
  (with-redefs [tui-session/!tui-state (atom {})]
    (let [items (#'ac/config-unset-menu-items)]
      (is (seq items))
      (is (every? #(str/starts-with? (first %) "/config unset ") items))
      (is (not-any? (fn [[d]] (agent/read-only-key? (keyword (subs d (count "/config unset ")))))
                    items)))))
