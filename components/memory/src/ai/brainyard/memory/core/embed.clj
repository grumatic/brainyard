;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.memory.core.embed
  "Embedding helpers for the context-graph vector index (CR-MEM-21).

  The memory store stays embedding-provider-agnostic: callers inject an
  `embed-fn` (built here over clj-llm, or a stub in tests). When no
  provider is configured the embed-fn is nil and vector search degrades
  to a no-op — recall falls back to FTS, non-regressing."
  (:require [clojure.string :as str]
            [ai.brainyard.clj-llm.interface :as llm]
            [ai.brainyard.mulog.interface :as mulog]))

(defn ->vec0-json
  "Serialize a numeric vector to sqlite-vec's `'[f,f,…]'` literal form."
  [v]
  (str "[" (str/join "," (map double v)) "]"))

(defn make-embed-fn
  "Build an `embed-fn` — `(fn [texts] -> [[float…] …])` — backed by
  clj-llm. `lm-config` comes from `clj-llm/create-lm`; `:model` selects the
  embedding model. Returns nil when `lm-config` is absent, so the store
  treats vector search as unavailable. Failures log and yield nil rather
  than propagating into the agent loop."
  [lm-config & {:keys [model]}]
  (when lm-config
    (fn [texts]
      (try
        (apply llm/create-embeddings lm-config (vec texts)
               (when model [:model model]))
        (catch Exception e
          (mulog/warn ::embed-failed :error (ex-message e))
          nil)))))

(defn embed-one
  "Embed a single string → one vector, or nil."
  [embed-fn text]
  (when (and embed-fn (string? text) (not (str/blank? text)))
    (first (embed-fn [text]))))
