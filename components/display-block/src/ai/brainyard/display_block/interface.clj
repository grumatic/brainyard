;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.display-block.interface
  "Public API for display-block.

   A `display-block` is a piece of content rendered into scrollback as a
   single MARKER LINE (collapsed form). A rendering surface (TUI) scans
   scrollback for markers and uses this API to expand or collapse them
   on demand.

   Two flows:

   1. PRODUCER — embed content with a marker:

        (require '[ai.brainyard.display-block.interface :as block])

        (block/text-block big-string
          {:max-collapsed-lines 20
           :class :snippet
           :label \"Snippet\"
           :storage :file
           :file-opts {:class-dir \"snippets\"}})

      Returns a string with `<head>\\n<collapsed-marker-line>`. Inside,
      a provider is registered keyed by the id embedded in the marker.

   2. CONSUMER (TUI glue) — scan + toggle:

        (block/scan-lines !scrollback-vec start end)
        ;; -> [{:id :state :line-idx :summary :hint} …]

        (block/expand-lines id)    ;; -> vector<string> to splice in
        (block/collapse-line id)   ;; -> single-line string to splice in
        (block/resource-path id)   ;; -> filesystem path or nil
        (block/dispose! id)        ;; -> remove block + free resources"
  (:require [ai.brainyard.display-block.core.eval :as ev]
            [ai.brainyard.display-block.core.marker :as marker]
            [ai.brainyard.display-block.core.providers.file-backed :as file-backed]
            [ai.brainyard.display-block.core.providers.in-memory :as in-memory]
            [ai.brainyard.display-block.core.registry :as registry]
            [ai.brainyard.display-block.core.text :as text]
            [ai.brainyard.display-block.interface.protocol :as p]))

;; ----------------------------------------------------------------------
;; Marker scanning
;; ----------------------------------------------------------------------

(def marker-re
  "Regex that matches both `collapsed` and `expanded` marker lines.
   Capture groups: 1=id, 2=state-string, 3=summary+hint body."
  marker/marker-re)

(defn parse-marker
  "Parse a single line. Returns {:id :state :summary :hint} or nil."
  [line]
  (marker/parse line))

(defn scan-lines
  "Scan a vector of scrollback lines and return ordered marker hits:
     [{:line-idx :id :state :summary :hint} …]
   `state` is :collapsed or :expanded."
  ([lines]            (marker/scan-lines lines))
  ([lines start end]  (marker/scan-lines lines start end)))

;; ----------------------------------------------------------------------
;; Producer-side: build a block from text
;; ----------------------------------------------------------------------

(def text-block
  "See `display-block.core.text/text-block`."
  text/text-block)

(def eval-code-block
  "See `display-block.core.eval/eval-code-block`. A `text-block` variant
   pre-configured for LLM-generated code sections (Clojure / Bash /
   Python / …) with class `:eval-code` and a language-aware default
   label."
  ev/eval-code-block)

(def eval-result-block
  "See `display-block.core.eval/eval-result-block`. A `text-block`
   variant pre-configured for the Result section of an LLM eval (class
   `:eval-result`, label `\"Result\"`)."
  ev/eval-result-block)

(def eval-output-block
  "See `display-block.core.eval/eval-output-block`. A `text-block`
   variant pre-configured for the Output section of an LLM eval (class
   `:eval-output`, label `\"Output\"`)."
  ev/eval-output-block)

(def eval-error-block
  "See `display-block.core.eval/eval-error-block`. A `text-block`
   variant pre-configured for the Error section of an LLM eval (class
   `:eval-error`, label `\"Error\"`)."
  ev/eval-error-block)

;; ----------------------------------------------------------------------
;; Consumer-side: registry lookups + provider calls
;; ----------------------------------------------------------------------

(defn get-block
  "Return the BlockProvider registered under `id`, or nil."
  [id]
  (registry/get-block id))

(defn block-meta
  "Return the provider's metadata map (or nil if no such block)."
  [id]
  (when-let [provider (registry/get-block id)]
    (p/-meta provider)))

(defn collapse-line
  "Return the collapsed marker line for `id`. nil if no such block."
  [id]
  (when-let [provider (registry/get-block id)]
    (p/-collapsed-marker-line provider)))

(defn expand-lines
  "Return a vector of lines to splice into scrollback when the user
   expands the block. nil if no such block."
  [id]
  (when-let [provider (registry/get-block id)]
    (p/-expanded-lines provider)))

(defn resource-path
  "Return a filesystem path / URI for `id`, or nil if the block doesn't
   expose one (e.g. in-memory provider)."
  [id]
  (when-let [provider (registry/get-block id)]
    (p/-resource-path provider)))

(defn dispose!
  "Release resources for `id` and drop it from the registry."
  [id]
  (registry/dispose! id))

(defn all-blocks
  "Return the full id -> provider map (mostly for tests / introspection)."
  []
  (registry/all))

(defn clear!
  "Drop all registry entries (no -dispose! called). Test helper."
  []
  (registry/clear!))

;; ----------------------------------------------------------------------
;; Provider constructors (advanced; producers should usually call
;; `text-block` instead).
;; ----------------------------------------------------------------------

(defn file-backed-provider
  "Construct a file-backed provider directly (no auto-registration).
   See `display-block.core.providers.file-backed/make`."
  [content opts]
  (file-backed/make content opts))

(defn in-memory-provider
  "Construct an in-memory provider directly (no auto-registration).
   See `display-block.core.providers.in-memory/make`."
  [content opts]
  (in-memory/make content opts))

(defn register!
  "Register a custom provider in the default registry; returns the id."
  [provider]
  (registry/register! provider))
