;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.env-detect.core.os
  "Detect OS information from JVM system properties.")

(defn detect-os
  "Returns {:name str :version str :arch str}."
  []
  {:name    (System/getProperty "os.name")
   :version (System/getProperty "os.version")
   :arch    (System/getProperty "os.arch")})
