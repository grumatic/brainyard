;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: Apache-2.0
;; Licensed under the Apache License, Version 2.0 (the "License"); you may
;; not use this file except in compliance with the License. See LICENSE at
;; the repository root and CONTRIBUTING.md for contribution terms.

(ns ai.brainyard.util.core.common
  "Common utility functions for UUID generation, file operations, etc."
  (:require [clojure.java.io :as io]
            [ai.brainyard.mulog.interface :as mulog])
  (:import [java.util UUID]))

(defn new-uuid
  "Create a new UUID or convert a value to UUID.
   - No args: returns a random UUID
   - uuid? value: returns the value unchanged
   - int value: creates a deterministic UUID from the integer
   - string value: parses the string as UUID"
  ([] (UUID/randomUUID))
  ([v]
   (cond
     (uuid? v) v
     (int? v) (UUID/fromString (format "ffffffff-ffff-ffff-ffff-%012d" v))
     :else (UUID/fromString (str v)))))

(defn custom-uuid
  "Generate an uppercase UUID string without hyphens."
  []
  (.toUpperCase (.replace (.toString (UUID/randomUUID)) "-" "")))

(defn create-dir
  "Create a directory from a URL string if it doesn't exist."
  [url-str]
  (let [f (io/file (io/as-url url-str))]
    (when-not (and (.exists f) (.isDirectory f))
      (mulog/debug ::create-dir :url url-str)
      (.mkdir f))))

(defn exist-dir?
  "Check if a directory exists at the given URL string."
  [url-str]
  (let [f (io/file (io/as-url url-str))]
    (and (.exists f) (.isDirectory f))))

(defn copy-file
  "Copy a file from source URL to destination URL."
  [source-url-str dest-url-str]
  (let [sf (io/file (io/as-url source-url-str))
        df (io/file (io/as-url dest-url-str))]
    (if (.exists sf)
      (io/copy sf df)
      (mulog/warn ::copy-file-not-found :source-url source-url-str))))

(defn iter-seq
  "Convert a Java Iterable to a lazy sequence."
  ([^Iterable iterable]
   (iter-seq iterable (.iterator iterable)))
  ([iterable ^java.util.Iterator iter]
   (lazy-seq
    (when (.hasNext iter)
      (cons (.next iter) (iter-seq iterable iter))))))
