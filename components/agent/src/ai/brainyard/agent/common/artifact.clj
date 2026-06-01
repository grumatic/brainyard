;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: Apache-2.0
;; Licensed under the Apache License, Version 2.0 (the "License"); you may
;; not use this file except in compliance with the License. See LICENSE at
;; the repository root and CONTRIBUTING.md for contribution terms.

(ns ai.brainyard.agent.common.artifact
  "Artifact management for agent outputs (charts, UI layouts, images, etc.).
   Ported from cloudcast.backend.agent.artifact.")

(defn conj-artifact
  "Add an artifact to the artifacts vector, deduplicating by type and content.
   Artifacts are maps like {:artifact-type :chart :artifact-data {:chart-spec ...}}.
   If a duplicate exists (same type + same spec/layout), it is replaced."
  [artifacts new-artifact]
  (let [filtered (filterv
                  #(not (and (= (:artifact-type %) (:artifact-type new-artifact))
                             (case (:artifact-type %)
                               :chart     (= (get-in % [:artifact-data :chart-spec])
                                             (get-in new-artifact [:artifact-data :chart-spec]))
                               :ui-layout (= (get-in % [:artifact-data :ui-layout])
                                             (get-in new-artifact [:artifact-data :ui-layout]))
                               :image     (= (get-in % [:artifact-data :url])
                                             (get-in new-artifact [:artifact-data :url]))
                               false)))
                  artifacts)]
    (conj filtered new-artifact)))
