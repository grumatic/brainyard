;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.ask-channel.core.protocol
  "Wire framing for the side ask channel.

   One EDN map per line. `pr-str` emits single-line EDN (embedded newlines in a
   question are escaped as \\n), so a trailing newline is an unambiguous frame
   delimiter. Both ends are `by`, so EDN — not JSON — is the natural format."
  (:require [clojure.edn :as edn])
  (:import [java.io BufferedReader Writer]))

(defn write-msg!
  "Serialize `m` (an EDN-safe map) and write it as one newline-terminated frame
   to `writer`, flushing so the peer can read immediately."
  [^Writer writer m]
  (.write writer (pr-str m))
  (.write writer "\n")
  (.flush writer))

(defn read-msg
  "Read one newline-framed EDN map from `reader`. Returns the parsed value, or
   `nil` on EOF. Throws on malformed EDN (caller decides how to surface it)."
  [^BufferedReader reader]
  (when-let [line (.readLine reader)]
    (edn/read-string line)))
