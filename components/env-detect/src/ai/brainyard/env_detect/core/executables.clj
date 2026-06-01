;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: Apache-2.0
;; Licensed under the Apache License, Version 2.0 (the "License"); you may
;; not use this file except in compliance with the License. See LICENSE at
;; the repository root and CONTRIBUTING.md for contribution terms.

(ns ai.brainyard.env-detect.core.executables
  "Detect executables on PATH with version info."
  (:require [clojure.string :as str])
  (:import [java.io BufferedReader InputStreamReader]))

(def checked-executables
  "Executables to scan for, with version flag and stderr hint."
  [{:name "bash"    :version-flag "--version"}
   {:name "zsh"     :version-flag "--version"}
   {:name "python"  :version-flag "--version"}
   {:name "python3" :version-flag "--version"}
   {:name "pip"     :version-flag "--version"}
   {:name "node"    :version-flag "--version"}
   {:name "npm"     :version-flag "--version"}
   {:name "npx"     :version-flag "--version"}
   {:name "java"    :version-flag "-version"  :stderr? true}
   {:name "clojure" :version-flag "--version"}
   {:name "bb"      :version-flag "--version"}
   {:name "git"     :version-flag "--version"}
   {:name "docker"  :version-flag "--version"}
   {:name "curl"    :version-flag "--version"}
   {:name "wget"    :version-flag "--version"}
   {:name "jq"      :version-flag "--version"}
   {:name "rg"      :version-flag "--version"}
   {:name "fd"      :version-flag "--version"}
   {:name "fzf"     :version-flag "--version"}])

(defn which
  "Find executable path via `which`. Returns path string or nil."
  [name]
  (try
    (let [proc (.start (ProcessBuilder. ^"[Ljava.lang.String;" (into-array String ["which" name])))
          out  (str/trim (slurp (.getInputStream proc)))
          exit (.waitFor proc)]
      (when (and (zero? exit) (not (str/blank? out)))
        out))
    (catch Exception _ nil)))

(defn get-version
  "Run executable with version flag, return first meaningful line of output.
   Some tools (e.g., java -version) write to stderr."
  [path version-flag & {:keys [stderr?]}]
  (try
    (let [proc (.start (ProcessBuilder. ^"[Ljava.lang.String;" (into-array String [path version-flag])))
          read-stream (fn [stream]
                        (with-open [r (BufferedReader. (InputStreamReader. stream))]
                          (str/join "\n" (line-seq r))))
          stdout (read-stream (.getInputStream proc))
          stderr (read-stream (.getErrorStream proc))
          _      (.waitFor proc)
          output (if (and stderr? (str/blank? stdout))
                   stderr
                   (if (str/blank? stdout) stderr stdout))]
      (when-not (str/blank? output)
        (-> output str/split-lines first str/trim)))
    (catch Exception _ nil)))

(defn detect-executables
  "Scan PATH for all checked executables.
   Returns vec of {:name str :path str-or-nil :version str-or-nil :available? bool}"
  []
  (mapv (fn [{:keys [name version-flag stderr?]}]
          (let [path (which name)]
            {:name       name
             :path       path
             :version    (when path (get-version path version-flag :stderr? stderr?))
             :available? (some? path)}))
        checked-executables))
