;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.clj-oauth.core.encode
  "Tiny shared encoders for OAuth token/device requests."
  (:require [clojure.string :as str])
  (:import [java.net URLEncoder]))

(defn form-encode
  "`application/x-www-form-urlencoded` body from a map of string-ish k/v pairs."
  [m]
  (->> m
       (map (fn [[k v]]
              (str (URLEncoder/encode (str (if (keyword? k) (name k) k)) "UTF-8")
                   "=" (URLEncoder/encode (str v) "UTF-8"))))
       (str/join "&")))
