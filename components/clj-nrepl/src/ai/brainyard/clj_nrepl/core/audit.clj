;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: Apache-2.0
;; Licensed under the Apache License, Version 2.0 (the "License"); you may
;; not use this file except in compliance with the License. See LICENSE at
;; the repository root and CONTRIBUTING.md for contribution terms.

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
