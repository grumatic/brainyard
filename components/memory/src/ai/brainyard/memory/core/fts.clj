;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: Apache-2.0
;; Licensed under the Apache License, Version 2.0 (the "License"); you may
;; not use this file except in compliance with the License. See LICENSE at
;; the repository root and CONTRIBUTING.md for contribution terms.

(ns ai.brainyard.memory.core.fts
  "FTS5 query utilities for the agent memory system.

   Provides query normalization to convert natural language queries
   into valid SQLite FTS5 MATCH syntax, plus keyword extraction
   for the multi-step recall pipeline."
  (:require [clojure.string :as str]))

;; FTS5 special characters to replace with spaces
(def ^:private fts5-special-chars-pattern
  #"[#@$%^&*()\-+=\[\]{}|\\/<>:\"'~`,\.!?;]")

;; FTS5 boolean operators to neutralize by lowercasing
(def ^:private fts5-operators
  #{"AND" "OR" "NOT" "NEAR"})

;; Common English stop words to filter out during keyword extraction
(def ^:private stop-words
  #{"a" "an" "the" "and" "or" "but" "in" "on" "at" "to" "for"
    "of" "with" "by" "from" "as" "is" "was" "are" "were" "be"
    "been" "being" "have" "has" "had" "do" "does" "did" "will"
    "would" "could" "should" "may" "might" "shall" "can" "need"
    "dare" "ought" "used" "it" "its" "this" "that" "these" "those"
    "i" "me" "my" "we" "our" "you" "your" "he" "him" "his" "she"
    "her" "they" "them" "their" "what" "which" "who" "whom" "where"
    "when" "why" "how" "all" "each" "every" "both" "few" "more"
    "most" "other" "some" "such" "no" "nor" "not" "only" "own"
    "same" "so" "than" "too" "very" "just" "about" "above" "after"
    "again" "also" "any" "because" "before" "below" "between"
    "during" "into" "out" "over" "then" "there" "through" "under"
    "until" "up" "while" "if" "else" "here" "am" "let" "get" "got"})

(defn- replace-special-chars-with-spaces
  "Replace FTS5 special characters with spaces to preserve word boundaries."
  [text]
  (str/replace text fts5-special-chars-pattern " "))

(defn- neutralize-operator
  "Convert FTS5 operators to lowercase to treat as regular words."
  [word]
  (if (contains? fts5-operators (str/upper-case word))
    (str/lower-case word)
    word))

(defn- normalize-word
  "Normalize a single word for FTS5 query."
  [word]
  (let [cleaned (-> word
                    str/trim
                    neutralize-operator)]
    (when-not (str/blank? cleaned)
      cleaned)))

(defn- escape-quotes
  "Escape FTS5 phrase double-quotes by doubling them, per SQLite syntax."
  [s]
  (str/replace s "\"" "\"\""))

(defn normalize-fts-query
  "Normalize a natural language query string for FTS5 MATCH clause.

   Normalization rules:
   1. Replace FTS5 special characters with spaces
   2. Split on whitespace
   3. Convert FTS5 operators (AND, OR, NOT, NEAR) to lowercase
   4. Filter out empty words
   5. Join the remaining words according to `:match`

   `:match` values:
     :or     — join with `OR` (default; broadest recall, backward-compatible)
     :and    — join with space (FTS5's implicit AND; every word must match)
     :phrase — wrap in double quotes (exact consecutive phrase match)

   Single-word queries are returned verbatim regardless of mode (the
   distinction only matters with 2+ tokens).

   Returns: Normalized query string, or nil if no valid terms."
  ([query] (normalize-fts-query query :or))
  ([query match]
   (when-not (str/blank? query)
     (let [sanitized (replace-special-chars-with-spaces query)
           words (->> (str/split sanitized #"\s+")
                      (map normalize-word)
                      (filter some?)
                      vec)]
       (when (seq words)
         (cond
           (= 1 (count words))
           (first words)

           (= match :phrase)
           (str "\"" (escape-quotes (str/join " " words)) "\"")

           (= match :and)
           (str/join " " words)

           :else
           (str/join " OR " words)))))))

(defn extract-keywords
  "Extract distinctive keywords from text for secondary FTS queries.

   Filters out stop words, short words, and returns the most
   distinctive terms suitable for cross-layer search.

   Parameters:
     text - Input text to extract keywords from

   Options:
     :min-length - Minimum word length (default 3)
     :max-keywords - Maximum keywords to return (default 10)

   Returns: Vector of keyword strings, or empty vector."
  [text & {:keys [min-length max-keywords]
           :or {min-length 3 max-keywords 10}}]
  (if (str/blank? text)
    []
    (let [sanitized (replace-special-chars-with-spaces text)
          words (->> (str/split sanitized #"\s+")
                     (map str/lower-case)
                     (filter #(>= (count %) min-length))
                     (remove stop-words)
                     frequencies)
          ;; Sort by frequency (descending) for distinctiveness
          sorted (->> words
                      (sort-by val >)
                      (map key))]
      (vec (take max-keywords sorted)))))
