;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: Apache-2.0
;; Licensed under the Apache License, Version 2.0 (the "License"); you may
;; not use this file except in compliance with the License. See LICENSE at
;; the repository root and CONTRIBUTING.md for contribution terms.

(ns ai.brainyard.env-detect.core.sandbox
  "Detect sandbox/container environment."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]))

(defn- file-exists? [path]
  (.exists (io/file path)))

(defn- file-contains? [path needle]
  (try
    (when (file-exists? path)
      (str/includes? (slurp path) needle))
    (catch Exception _ false)))

(defn detect-docker?
  "Check /.dockerenv or /proc/1/cgroup for Docker indicators."
  []
  (or (file-exists? "/.dockerenv")
      (file-contains? "/proc/1/cgroup" "docker")
      (file-contains? "/proc/1/cgroup" "containerd")))

(defn detect-nix-shell?
  "Check IN_NIX_SHELL or NIX_BUILD_TOP env vars."
  []
  (or (some? (System/getenv "IN_NIX_SHELL"))
      (some? (System/getenv "NIX_BUILD_TOP"))))

(defn detect-devcontainer?
  "Check REMOTE_CONTAINERS, CODESPACES, or DEVCONTAINER env vars."
  []
  (or (some? (System/getenv "REMOTE_CONTAINERS"))
      (some? (System/getenv "CODESPACES"))
      (some? (System/getenv "DEVCONTAINER"))))

(defn detect-ssh?
  "Check SSH_CLIENT or SSH_TTY env vars."
  []
  (or (some? (System/getenv "SSH_CLIENT"))
      (some? (System/getenv "SSH_TTY"))))

(defn detect-vscode-terminal?
  "Check TERM_PROGRAM=vscode env var."
  []
  (= "vscode" (System/getenv "TERM_PROGRAM")))

(defn detect-tmux?
  "Check TMUX env var."
  []
  (some? (System/getenv "TMUX")))

(defn detect-screen?
  "Check STY env var."
  []
  (some? (System/getenv "STY")))

(defn detect-sandbox-environment
  "Run all environment detections.
   Returns {:sandbox-type :docker|:nix|:devcontainer|:ssh|:none
            :details {:docker? bool :nix? bool :devcontainer? bool :ssh? bool}
            :terminal {:vscode? bool :tmux? bool :screen? bool}}"
  []
  (let [docker?       (detect-docker?)
        nix?          (detect-nix-shell?)
        devcontainer? (detect-devcontainer?)
        ssh?          (detect-ssh?)
        sandbox-type  (cond
                        docker?       :docker
                        devcontainer? :devcontainer
                        nix?          :nix
                        ssh?          :ssh
                        :else         :none)]
    {:sandbox-type sandbox-type
     :details      {:docker?       docker?
                    :nix?          nix?
                    :devcontainer? devcontainer?
                    :ssh?          ssh?}
     :terminal     {:vscode? (detect-vscode-terminal?)
                    :tmux?   (detect-tmux?)
                    :screen? (detect-screen?)}}))
