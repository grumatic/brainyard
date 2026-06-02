;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.display-block.core.providers.in-memory
  "Provider that keeps the full content in heap. No temp file is created,
   so -resource-path returns nil and an editor key (Ctrl-O) is a no-op for
   blocks of this kind."
  (:require [ai.brainyard.display-block.core.marker :as marker]
            [ai.brainyard.display-block.interface.protocol :as p]
            [clojure.string :as str]))

(def ^:const default-max-expanded-lines 200)

(def ^:const default-hint-collapsed "Enter: expand")
(def ^:const default-hint-expanded  "Enter: collapse")

(defn- short-id []
  (subs (str (java.util.UUID/randomUUID)) 0 8))

(defrecord InMemoryProvider [meta-map content]
  p/BlockProvider
  (-meta [_] meta-map)

  (-collapsed-marker-line [_]
    (let [{:keys [id hidden-lines hint-collapsed line-decorator]} meta-map
          decorate (or line-decorator identity)
          hint     (or hint-collapsed default-hint-collapsed)]
      (decorate (marker/collapsed-line id
                                       (str "+" (or hidden-lines 0) " lines")
                                       :hint hint))))

  (-expanded-lines [_]
    (let [{:keys [id max-expanded-lines hint-expanded line-decorator
                  total-lines hidden-lines]} meta-map
          cap         (or max-expanded-lines default-max-expanded-lines)
          decorate    (or line-decorator identity)
          hint        (or hint-expanded default-hint-expanded)
          shown       (- (or total-lines 0) (or hidden-lines 0))
          lines       (str/split-lines (str content))
          hidden-tail (drop shown lines)
          tail-count  (count hidden-tail)
          visible     (vec (take cap hidden-tail))
          overflow    (max 0 (- tail-count cap))
          trailer     (when (pos? overflow)
                        (str "  +" overflow " more lines (in-memory)"))
          tail-summary (if (pos? overflow)
                         (str (count visible) " of " tail-count " hidden lines")
                         (str tail-count " hidden lines"))
          expanded-marker (marker/expanded-line id tail-summary :hint hint)]
      (vec (concat (mapv decorate visible)
                   (when trailer [(decorate trailer)])
                   [(decorate expanded-marker)]))))

  (-resource-path [_] nil)
  (-dispose! [_] nil))

(defn make
  "Construct an InMemoryProvider.

   Optional opts:
     :id :class :label :hint-collapsed :hint-expanded :line-decorator
     :max-expanded-lines :total-lines :hidden-lines"
  [content {:keys [id class label hint-collapsed hint-expanded line-decorator
                   max-expanded-lines total-lines hidden-lines]}]
  (let [block-id (or id (short-id))
        lines-cnt (or total-lines (count (str/split-lines (str content))))
        meta-map  {:id                 block-id
                   :class              class
                   :label              label
                   :hint-collapsed     hint-collapsed
                   :hint-expanded      hint-expanded
                   :line-decorator     line-decorator
                   :max-expanded-lines (or max-expanded-lines default-max-expanded-lines)
                   :total-lines        lines-cnt
                   :hidden-lines       (or hidden-lines 0)}]
    (->InMemoryProvider meta-map content)))
