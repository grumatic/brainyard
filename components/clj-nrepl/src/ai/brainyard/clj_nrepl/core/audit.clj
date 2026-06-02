;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.clj-nrepl.core.audit
  "Audit shim — every eval is logged via mulog. Audit is not optional
   and cannot be disabled by the agent (Principle 6 / §8.2 #6)."
  (:require [ai.brainyard.mulog.interface :as mulog]))

(defn- preview [s n]
  (let [s (str s)]
    (subs s 0 (min n (count s)))))

(defn audit-eval
  "Emit a structured ::nrepl-eval event for one evaluation."
  [{:keys [code session result duration-ms]}]
  (mulog/info ::nrepl-eval
              :session     session
              :duration-ms duration-ms
              :code-len    (count (str code))
              :code        (preview code 200)
              :result-len  (count (str (:result result)))
              :ns          (:ns result)
              :error       (:error result)))
