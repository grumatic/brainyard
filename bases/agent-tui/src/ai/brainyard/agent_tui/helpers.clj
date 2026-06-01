;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: Apache-2.0
;; Licensed under the Apache License, Version 2.0 (the "License"); you may
;; not use this file except in compliance with the License. See LICENSE at
;; the repository root and CONTRIBUTING.md for contribution terms.

(ns ai.brainyard.agent-tui.helpers
  "Helper functions extracted from core: LM setup, usage tracking, JUL suppression."
  (:require [ai.brainyard.agent-tui.session :as tui-session]
            [ai.brainyard.agent.interface.tui.ansi :as ansi]
            [ai.brainyard.agent.interface :as agent]
            [ai.brainyard.clj-llm.interface :as clj-llm])
  (:import [java.util.logging Logger Level]))

;; ============================================================================
;; Internal Helpers
;; ============================================================================

(defn suppress-jul-cookie-warnings!
  "Suppress Apache HttpClient cookie warnings (JUL, bypasses Timbre).
   Returns the logger instance — MUST be held as a strong reference
   to prevent GC (JUL uses weak refs internally)."
  []
  (doto (Logger/getLogger "org.apache.http.client.protocol.ResponseProcessCookies")
    (.setLevel Level/SEVERE)))

;; Suppress JUL cookie warnings at namespace load time.
;; Strong ref prevents GC from resetting the logger level.
(defonce ^:private _jul-cookie-logger (suppress-jul-cookie-warnings!))

(defn resolve-react-bt
  "Resolve react-behavior-tree from common.react-agent."
  []
  agent/react-behavior-tree)

(defn setup-lm!
  "Auto-setup LM with provider defaults."
  [provider & {:keys [model]}]
  (let [default-models {:openai      "gpt-4.1-mini"
                        :anthropic   "claude-sonnet-4-6"
                        :claude-code "sonnet"
                        :ollama      "glm-5:cloud"
                        :apple-fm    "apple-foundationmodel"}
        env-vars       {:openai    "OPENAI_API_KEY"
                        :anthropic "ANTHROPIC_API_KEY"}
        resolved-model (or model (get default-models provider))
        resolved-key   (when-not (#{:claude-code :ollama :apple-fm} provider)
                         (when-let [env-var (get env-vars provider)]
                           (System/getenv env-var)))]
    (when (and (nil? resolved-key) (get env-vars provider))
      (throw (ex-info (str "No API key for " (name provider)
                           ". Set " (get env-vars provider) " env var")
                      {:provider provider})))
    (let [lm (clj-llm/create-lm (cond-> {:model resolved-model :provider provider}
                                  resolved-key (assoc :api-key resolved-key)))]
      (clj-llm/configure-default-lm! lm)
      (tui-session/emit! (str (ansi/muted (str "LM configured: " (name provider) " / " resolved-model))))
      lm)))

(defn get-usage
  "Get usage summary from session's tracker."
  [agent]
  (when-let [tracker (agent/get-session-config @(:!session agent) :usage-tracker)]
    (clj-llm/get-usage-summary tracker)))

(defn get-usage-totals
  "Extract flat totals {:calls N :tokens N :cost F} from usage summary."
  [agent]
  (when-let [usage (get-usage agent)]
    (let [totals (or (:totals usage) usage)]
      {:calls  (or (:call-count totals) (:total-calls totals) 0)
       :tokens (or (:total-tokens totals)
                   (+ (or (:input-tokens totals) (:total-input-tokens totals) 0)
                      (or (:output-tokens totals) (:total-output-tokens totals) 0)))
       :cost   (or (:total-cost totals) 0.0)})))

(defn usage-diff
  "Compute the difference between two usage snapshots."
  [before after]
  (if (and before after)
    {:calls  (- (:calls after 0) (:calls before 0))
     :tokens (- (:tokens after 0) (:tokens before 0))
     :cost   (- (:cost after 0.0) (:cost before 0.0))}
    after))
