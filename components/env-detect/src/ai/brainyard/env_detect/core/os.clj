;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: Apache-2.0
;; Licensed under the Apache License, Version 2.0 (the "License"); you may
;; not use this file except in compliance with the License. See LICENSE at
;; the repository root and CONTRIBUTING.md for contribution terms.

(ns ai.brainyard.env-detect.core.os
  "Detect OS information from JVM system properties.")

(defn detect-os
  "Returns {:name str :version str :arch str}."
  []
  {:name    (System/getProperty "os.name")
   :version (System/getProperty "os.version")
   :arch    (System/getProperty "os.arch")})
