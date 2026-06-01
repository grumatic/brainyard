;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: Apache-2.0
;; Licensed under the Apache License, Version 2.0 (the "License"); you may
;; not use this file except in compliance with the License. See LICENSE at
;; the repository root and CONTRIBUTING.md for contribution terms.

(ns ai.brainyard.util.core.naming
  "Utilities for naming conventions and random name generation."
  (:require
   [inflections.core :as inf]
   ;; Built-in word lists below (lines ~17-44) replaced talltale to avoid
   ;; Reflector type-coercion failures under GraalVM native-image. The
   ;; talltale dep itself has been moved to the :test alias of
   ;; components/util/deps.edn — keeping a dead require here just to
   ;; "preserve the build-time class graph" was costing ~2 MB of binary
   ;; size and several hundred reflection entries (see
   ;; docs/design/native-image-design.md §5).
   #?@(:cljs [["random-words" :refer [generate]]
              ["unique-names-generator" :refer [uniqueNamesGenerator adjectives names]]])))

#?(:clj
   ;; Built-in word lists — replaces talltale to avoid Reflector type-coercion
   ;; failures under GraalVM native-image (talltale's `rand-data` calls
   ;; `(.nextInt rnd (count data))` which fails because `count` returns Long
   ;; and the native-image Reflector doesn't widen long→int).
   (do
     (def ^:private colors
       ["amber" "azure" "beige" "black" "blue" "bronze" "brown" "coral" "crimson"
        "cyan" "ebony" "emerald" "fuchsia" "gold" "gray" "green" "indigo" "ivory"
        "jade" "lavender" "lime" "magenta" "maroon" "navy" "olive" "orange"
        "peach" "pink" "plum" "purple" "red" "rose" "ruby" "salmon" "sapphire"
        "scarlet" "silver" "tan" "teal" "turquoise" "violet" "white" "yellow"])
     (def ^:private animals
       ["ant" "bear" "bee" "bird" "cat" "cow" "crab" "crow" "deer" "dog" "dolphin"
        "duck" "eagle" "elk" "ferret" "finch" "fish" "fox" "frog" "goat" "hare"
        "hawk" "horse" "koala" "lion" "lynx" "mole" "moose" "mouse" "newt" "otter"
        "owl" "panda" "pig" "puma" "rabbit" "raven" "salmon" "seal" "shark"
        "sheep" "snake" "stork" "swan" "tiger" "toad" "turtle" "vole" "whale" "wolf"])
     (def ^:private first-names
       ["alex" "avery" "bailey" "blake" "cameron" "casey" "drew" "ellis" "emery"
        "finley" "gray" "harper" "indigo" "jordan" "kai" "kendall" "logan" "morgan"
        "noel" "oakley" "parker" "quinn" "reese" "river" "rowan" "sage" "skyler"
        "taylor" "wren" "zion"])
     (def ^:private last-names
       ["adler" "bishop" "clarke" "dumont" "evans" "fischer" "gardner" "hayes"
        "ingram" "joyce" "keller" "lambert" "moreno" "nilsson" "owens" "petrov"
        "quinn" "reyes" "saito" "tanaka" "ulrich" "vasquez" "winters" "xie"
        "young" "zaragoza"])
     (defn- street-number [] (inc (rand-int 9999)))))

(defn kw->nspc
  "Convert a keyword to a namespace-style string.
   Handles namespaced keywords by joining with '.'"
  [kw]
  (try
    (let [prefix (namespace kw)
          param-name (inf/parameterize (name kw))]
      (if (nil? prefix)
        param-name
        (str prefix "." param-name)))
    (catch #?(:clj Exception :cljs js/Error) e
      (throw (ex-info "kw->nspc error" {:causes #?(:clj (ex-message e) :cljs (.-message e))
                                        :input kw})))))

(defn abbreviate
  "Abbreviate a value to a specified length with ellipsis."
  ([x] (abbreviate x 16))
  ([x pre] (abbreviate x pre pre))
  ([x pre post]
   (let [sx (str x)
         len (count sx)]
     (if (> len (+ pre post))
       (str (.substring sx 0 pre) " ...[" (- len pre post) " chars]... " (.substring sx (- len post) len))
       sx))))

(defn kw->str
  "Convert a keyword to string, preserving namespace.
   :foo -> \"foo\", :ns/foo -> \"ns/foo\""
  [kw]
  (if (keyword? kw)
    (if-let [ns (namespace kw)]
      (str ns "/" (name kw))
      (name kw))
    (str kw)))

(defn gen-random-words
  "Generate random words using built-in lists (CLJ) or random-words (CLJS)."
  [& [words-per-string]]
  #?(:clj (str (rand-nth colors) "-" (rand-nth animals) "-" (street-number))
     :cljs (-> (js->clj (generate #js {:exactly 1
                                       :wordsPerString (or words-per-string 3)
                                       :separator "-"}))
               first)))

(defn gen-unique-names
  "Generate unique names using built-in lists (CLJ) or unique-names-generator (CLJS)."
  []
  #?(:clj (str (rand-nth colors) "-" (rand-nth first-names) "-" (rand-nth last-names) "-" (street-number))
     :cljs (uniqueNamesGenerator #js {:dictionaries #js [adjectives names]
                                      :style "lowerCase"})))
