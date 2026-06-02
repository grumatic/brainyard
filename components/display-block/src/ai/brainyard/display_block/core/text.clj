;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.display-block.core.text
  "Convenience factory: turn a long text body into a (registered)
   display-block, returning the text-with-marker that the producer
   embeds in their output stream.

   Usage:
     (text-block content
       {:max-collapsed-lines 20
        :class :code
        :label \"Code\"
        :storage :file
        :file-opts {:class-dir \"snippets\"}
        :hint \"Enter: expand, Ctrl-O: edit\"})

   Returns either:
     - the original `content` unchanged (when total lines <= max-collapsed-lines), OR
     - a string of the form
         <first max-collapsed-lines lines>
         <collapsed-marker-line>
       with a provider registered under the marker's id."
  (:require [ai.brainyard.display-block.core.providers.file-backed :as file-backed]
            [ai.brainyard.display-block.core.providers.in-memory :as in-memory]
            [ai.brainyard.display-block.core.registry :as registry]
            [ai.brainyard.display-block.interface.protocol :as p]
            [clojure.string :as str]))

(def ^:const default-max-collapsed-lines 20)

(defn- build-provider
  "Construct a provider per the requested storage backend."
  [content {:keys [storage class label hint hint-collapsed hint-expanded
                   line-decorator max-expanded-lines
                   total-lines hidden-lines file-opts id]
            :or {storage :file}}]
  (let [common {:id                 id
                :class              class
                :label              label
                ;; Back-compat: a single :hint applies to both states unless
                ;; the more specific opts are provided.
                :hint-collapsed     (or hint-collapsed hint)
                :hint-expanded      (or hint-expanded  hint)
                :line-decorator     line-decorator
                :max-expanded-lines max-expanded-lines
                :total-lines        total-lines
                :hidden-lines       hidden-lines}]
    (case storage
      :file   (file-backed/make content
                                (merge common file-opts))
      :memory (in-memory/make content common)
      (throw (ex-info (str "Unknown :storage " storage) {:storage storage})))))

(defn text-block
  "Wrap `content` (a string) as a display-block.

   When (count lines) <= max-collapsed-lines, returns content unchanged (no provider
   created — the body is already small).

   Otherwise:
     1. constructs the requested provider with the FULL content
     2. registers it
     3. returns
          <first max-collapsed-lines lines>
          <collapsed-marker-line>

   Options:
     :max-collapsed-lines int (default 20) — lines kept inline above the marker
     :max-expanded-lines  int — cap on the tail spliced in on expand (default 200)
     :class               keyword — semantic category
     :label               string — section name shown in expanded notices
     :hint                string — back-compat shorthand for both hints (below)
     :hint-collapsed      string — hint when block is collapsed
                          (default: 'Enter: expand, Ctrl-O: edit')
     :hint-expanded       string — hint when block is expanded
                          (default: 'Enter: collapse, Ctrl-O: edit')
     :line-decorator      fn `line -> styled-line` — applied by the provider
                          to every line returned by `-expanded-lines` AND
                          to the collapsed marker line. Producers pass this
                          to keep the box-drawing chrome and per-section
                          ANSI styling consistent across the head (already
                          in scrollback) and the tail spliced in on expand.
     :storage             :file (default) | :memory
     :file-opts           extra opts for the file-backed provider, must include :class-dir
     :id                  caller-supplied id (default: random short id)

   Note: storage :file requires :file-opts {:class-dir <string>}."
  ([content] (text-block content {}))
  ([content opts]
   (let [opts (merge {:max-collapsed-lines default-max-collapsed-lines
                      :storage :file}
                     opts)
         lines (when content (str/split-lines (str content)))
         total (count (or lines []))
         max-collapsed-lines (:max-collapsed-lines opts)]
     (if (or (nil? content) (<= total max-collapsed-lines))
       (str content)
       (let [hidden   (- total max-collapsed-lines)
             head     (str/join "\n" (take max-collapsed-lines lines))
             provider (build-provider
                       content
                       (assoc opts :total-lines total :hidden-lines hidden))
             _id      (registry/register! provider)
             marker   (p/-collapsed-marker-line provider)]
         (str head "\n" marker))))))
