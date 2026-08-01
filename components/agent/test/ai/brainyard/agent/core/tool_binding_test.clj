;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.core.tool-binding-test
  "`:enable-tool-binding` — whether `setup-agent` binds the agent's declared
   `:agent-tools` roster into the turn.

   Binding buys exactly two things: the `### Agent Tools` block in the system
   prompt, and the tools-fn-map fast path in `call-tool`. Neither is needed to
   CALL a tool — `call-tool` falls through to the `!tool-defs` registry, which
   applies the same visibility check and hook chain — so a capable model can
   discover tools with `list-tools` instead of being handed the roster in every
   prompt. These tests pin that: off ⇒ nothing bound, everything still callable.

   `:functions` (raw fn vars) are the exception. They have no registry entry to
   fall back to, so they stay bound either way."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [ai.brainyard.agent.core.agent :as agent-core]
            [ai.brainyard.agent.core.config :as config]
            [ai.brainyard.agent.core.hooks :as hooks]
            [ai.brainyard.agent.core.protocol :as proto]
            [ai.brainyard.agent.core.tool :as tool]))

(use-fixtures :each
  (fn [f]
    (hooks/reset-hooks!)
    (agent-core/reset-agent-registry!)
    (try (f)
         (finally
           (hooks/reset-hooks!)
           (agent-core/reset-agent-registry!)))))

(tool/deftool tool-binding-test$echo
  "Test-only tool; echoes its args."
  (fn [& {:as args}] {:got args})
  :input-schema [:map [:text [:string {:desc "anything"}]]])

(defn ^{:desc "Test-only raw fn (no registry entry)."} tool-binding-test-raw
  [^{:type "string" :desc "a value"} value]
  {:raw value})

(def ^:private roster
  {:tools     [:tool-binding-test$echo]
   :functions [#'tool-binding-test-raw]})

(defn- setup!
  "Create a throwaway agent with the roster above; `overrides` go in as
   per-agent config-schema overrides (setup-agent's `:config-extra` layer)."
  [id overrides]
  (apply agent-core/setup-agent
         (mapcat identity
                 (cond-> {:id            id
                          :agent-session {:user-id "u" :session-id "s"}
                          :agent-tools   roster
                          :memory-opts   {}}
                   (seq overrides) (assoc :config-extra overrides)))))

(defn- bound-names [ag]
  (set (map :name (proto/get-tools ag))))

;; ============================================================================
;; The flag
;; ============================================================================

(deftest defaults-to-binding-the-roster
  (testing ":enable-tool-binding defaults true — every existing agent unchanged"
    (is (true? (get config/default-config :enable-tool-binding))))

  (let [ag (setup! :tool-binding-on nil)]
    (try
      (is (contains? (bound-names ag) "tool-binding-test$echo")
          "the registered roster is bound by default")
      (is (contains? (bound-names ag) "tool-binding-test-raw")
          ":functions are bound too")
      (finally (.close ag)))))

(deftest disabled-drops-the-registered-roster-but-keeps-raw-functions
  (let [ag (setup! :tool-binding-off {:enable-tool-binding false})]
    (try
      (is (not (contains? (bound-names ag) "tool-binding-test$echo"))
          "a registered tool is reachable via the registry — no need to bind it")
      (is (contains? (bound-names ag) "tool-binding-test-raw")
          "a raw fn has no registry entry; dropping it would make it uncallable")
      (finally (.close ag)))))

(deftest disabled-with-no-functions-binds-nothing
  (let [ag (apply agent-core/setup-agent
                  (mapcat identity
                          {:id            :tool-binding-empty
                           :agent-session {:user-id "u" :session-id "s"}
                           :agent-tools   {:tools [:tool-binding-test$echo]}
                           :config-extra  {:enable-tool-binding false}
                           :memory-opts   {}}))]
    (try
      (is (empty? (proto/get-tools ag))
          "nothing bound ⇒ no `### Agent Tools` block in the system prompt")
      (is (nil? (:tools-fn-map @(:st-memory-init @(:!state ag))))
          "and no fn-map either — call-tool takes the registry path")
      (finally (.close ag)))))

(deftest declared-roster-survives-on-agent-meta
  (testing "the declaration is still recorded — only the BINDING is skipped"
    (let [ag (setup! :tool-binding-meta {:enable-tool-binding false})]
      (try
        (is (= roster (get-in @(:!state ag) [:meta :agent-tools]))
            "derived agents merge :agent-tools from meta; it must not be rewritten")
        (finally (.close ag))))))

;; ============================================================================
;; Nothing bound ⇒ tools still callable and still guarded
;; ============================================================================

(deftest unbound-registered-tools-stay-callable-and-visible
  (testing "tool-bound? accepts any registered id, so the strip-unbound BT action
            does not eat real tool-calls when the roster is unbound"
    (is (true? (tool/tool-bound? :tool-binding-test$echo [] {})))
    (is (false? (tool/tool-bound? :no-such-tool$nope [] {}))))

  (testing "call-tool falls through to the registry path with nothing bound"
    ;; The registry path also hands the tool its own `:_deftool$*` metadata,
    ;; so assert on the caller's arg rather than the whole map.
    (is (= "hi" (-> (tool/call-tool :tool-binding-test$echo {:text "hi"}
                                    :tools [] :tools-fn-map {})
                    :got :text))))

  (testing "the registry path still validates args"
    (is (string? (:error-message
                  (tool/call-tool :tool-binding-test$echo {}
                                  :tools [] :tools-fn-map {})))
        "missing required :text is rejected, not passed through as nil")))
