;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: Apache-2.0
;; Licensed under the Apache License, Version 2.0 (the "License"); you may
;; not use this file except in compliance with the License. See LICENSE at
;; the repository root and CONTRIBUTING.md for contribution terms.

(ns ai.brainyard.agent-tui.smoke-test
  "Bootstrap smoke test: verify the chosen LLM provider+model actually answers.
   Wraps `clj-llm/predict` in `future` + `deref` with a wall-clock timeout
   because `predict` has no native timeout knob.

   The underlying HTTP request keeps running on the orphaned thread until it
   resolves on its own — acceptable here since the wizard is a one-shot CLI
   that exits after writing the config."
  (:require [ai.brainyard.clj-llm.interface :as llm]))

(llm/defsignature SmokeProbe
  "Trivial probe used by the bootstrap wizard to confirm the LLM responds."
  {:inputs  {:ping [:string {:desc "An arbitrary short string"}]}
   :outputs {:pong [:string {:desc "Any short reply"}]}})

(defn- build-lm [provider model]
  (let [opts (cond-> {:provider provider}
               model (assoc :model model))]
    (llm/create-lm opts)))

(defn smoke-test!
  "Send one trivial prompt to {provider, model}. Returns
     {:ok? bool :latency-ms int :error str-or-nil :raw map-or-nil :ts iso-str}.

   `timeout-ms` defaults to 30000 (45000 is recommended right after a fresh
   `ollama pull` since the first call also loads weights into VRAM)."
  ([provider model]               (smoke-test! provider model 30000))
  ([provider model timeout-ms]
   (let [start    (System/currentTimeMillis)
         lm       (try (build-lm provider model)
                       (catch Exception e
                         {:ex e}))
         finish   (fn [m] (assoc m :latency-ms (- (System/currentTimeMillis) start)
                                 :ts (.toString (java.time.Instant/now))))]
     (cond
       (and (map? lm) (:ex lm))
       (finish {:ok? false :error (str "create-lm failed: " (.getMessage ^Throwable (:ex lm)))})

       :else
       (let [fut (future
                   (try
                     (llm/predict #'SmokeProbe {:ping "hi"} :lm-config lm)
                     (catch Throwable t
                       {::error t})))
             result (deref fut timeout-ms ::timeout)]
         (cond
           (= ::timeout result)
           (finish {:ok? false
                    :error (str "no response within " timeout-ms "ms")})

           (and (map? result) (::error result))
           (finish {:ok? false
                    :error (.getMessage ^Throwable (::error result))})

           :else
           (finish {:ok? true :raw result})))))))
