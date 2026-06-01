;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: Apache-2.0
;; Licensed under the Apache License, Version 2.0 (the "License"); you may
;; not use this file except in compliance with the License. See LICENSE at
;; the repository root and CONTRIBUTING.md for contribution terms.

(ns ai.brainyard.agent-tui.dirs
  "Thin delegation to ai.brainyard.agent.interface."
  (:require [ai.brainyard.agent.interface :as agent]))

(def find-git-root agent/find-git-root)
(def resolve-working-dir agent/resolve-working-dir)
(def resolve-project-dir agent/resolve-project-dir)
(def resolve-dirs agent/resolve-dirs)
(def ensure-config-dirs! agent/ensure-config-dirs!)
(def init-dirs! agent/init-dirs!)
(def list-config-files agent/list-config-files)
(def read-config-file agent/read-config-file)
(def load-brainyard-instructions agent/load-brainyard-instructions)
(def read-edn-config agent/read-edn-config)
(def write-edn-config! agent/write-edn-config!)
(def subdir-scope-policy agent/subdir-scope-policy)
(def subdir-allowed-scopes agent/subdir-allowed-scopes)
(def subdir-scope-allowed? agent/subdir-scope-allowed?)
(def brainyard-subdir agent/brainyard-subdir)
(def brainyard-subdir! agent/brainyard-subdir!)
