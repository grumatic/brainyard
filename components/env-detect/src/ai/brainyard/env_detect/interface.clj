;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: Apache-2.0
;; Licensed under the Apache License, Version 2.0 (the "License"); you may
;; not use this file except in compliance with the License. See LICENSE at
;; the repository root and CONTRIBUTING.md for contribution terms.

(ns ai.brainyard.env-detect.interface
  "Public interface for environment detection.
   Detects LLM providers, executables, sandbox environment, and OS info."
  (:require [ai.brainyard.env-detect.core.providers :as providers]
            [ai.brainyard.env-detect.core.executables :as executables]
            [ai.brainyard.env-detect.core.sandbox :as sandbox]
            [ai.brainyard.env-detect.core.os :as os]
            [ai.brainyard.env-detect.core.ollama-install :as ollama]
            [ai.brainyard.env-detect.core.apple-fm-install :as apple-fm]))

(def provider-priority
  "Preference order when multiple API-key providers are reachable.
   See `ai.brainyard.env-detect.core.providers/provider-priority`."
  providers/provider-priority)

(defn detect-all-providers
  "Detect available LLM providers (API keys, Ollama, Claude CLI).
   Returns vec of {:provider kw :available? bool :method :api-key|:network|:cli :detail str}"
  []
  (providers/detect-all-providers))

(defn detect-ollama-installation
  "Detect Ollama installation state (binary, version, daemon, pulled models).
   Returns {:installed? :binary-path :version :daemon-running?
            :pulled-models [str] :detail str}"
  []
  (providers/detect-ollama-installation))

(defn detect-network-egress
  "Probe egress to hosts the bootstrap ladder depends on.
   Returns {:huggingface? :ollama? :detail str}"
  []
  (providers/detect-network-egress))

(defn detect-executables
  "Scan PATH for common executables with version info.
   Returns vec of {:name str :path str-or-nil :version str-or-nil :available? bool}"
  []
  (executables/detect-executables))

(defn detect-sandbox-environment
  "Detect sandbox/container environment (Docker, Nix, devcontainer, SSH).
   Returns {:sandbox-type kw :details map :terminal map}"
  []
  (sandbox/detect-sandbox-environment))

(defn detect-os
  "Detect OS info from JVM system properties.
   Returns {:name str :version str :arch str}"
  []
  (os/detect-os))

(defn detect-all
  "Run complete environment detection. Returns map with all results.
   Includes `:ollama-install` and `:network` for bootstrap's fallback ladder."
  []
  {:providers      (detect-all-providers)
   :executables    (detect-executables)
   :sandbox        (detect-sandbox-environment)
   :os             (detect-os)
   :ollama-install (detect-ollama-installation)
   :network        (detect-network-egress)})

;; ============================================================================
;; Ollama install / pull helpers (rung (e) of the bootstrap ladder)
;; ============================================================================

(defn ollama-install-instructions
  "Per-OS guidance for installing Ollama. Pure.
   See `ai.brainyard.env-detect.core.ollama-install/install-instructions`."
  [os-info]
  (ollama/install-instructions os-info))

(defn install-ollama!
  "Run an Ollama install command (e.g. `brew install ollama`).
   Caller must confirm with the user first."
  [command]
  (ollama/install-ollama! command))

(defn start-ollama-daemon!
  "Start the Ollama daemon and poll until it is reachable. Up to 10s."
  []
  (ollama/start-daemon!))

(defn pull-ollama-model!
  "Run `ollama pull <model>`, streaming progress to `on-progress`."
  [model on-progress]
  (ollama/pull-model! model on-progress))

(defn ollama-signin!
  "Run `ollama signin` for cloud-tier models (e.g. glm-5:cloud)."
  []
  (ollama/signin!))

(def recommended-ollama-model
  "Default model for rung (e) — see ollama-install/recommended-default-model."
  ollama/recommended-default-model)

(def cloud-ollama-model
  "Disk-free fallback model (Ollama Cloud free tier; requires signin)."
  ollama/cloud-fallback-model)

(defn start-apple-fm-daemon!
  "Run `apfel --serve --port 11435` in the background and poll /health up to 10s.
   Returns {:ok? :elapsed-ms :detail}. Caller must confirm with the user first."
  []
  (apple-fm/start-daemon!))
