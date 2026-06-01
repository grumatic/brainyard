;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: Apache-2.0
;; Licensed under the Apache License, Version 2.0 (the "License"); you may
;; not use this file except in compliance with the License. See LICENSE at
;; the repository root and CONTRIBUTING.md for contribution terms.

(ns ai.brainyard.clj-sandbox.bench.gen
  "Synthetic data generation for RLM benchmarks.
   Deterministic — all generation uses seeded java.util.Random."
  (:require [clojure.string :as str])
  (:import [java.util Random]))

;; ============================================================================
;; Constants
;; ============================================================================

(def ^:private chars-per-token
  "Approximate characters per token (conservative estimate)."
  4)

(defn chars-for-tokens
  "Approximate char count for a target token count."
  [token-count]
  (* token-count chars-per-token))

;; ============================================================================
;; Filler Text
;; ============================================================================

(def ^:private filler-sentences
  ["The morning commute was busier than expected due to construction on the main highway."
   "Several new restaurants opened downtown last month, offering a variety of cuisines."
   "The annual report showed steady growth in customer satisfaction scores across all regions."
   "Weather forecasters predicted mild temperatures throughout the rest of the week."
   "The library extended its hours to accommodate increased demand for study spaces."
   "A new pedestrian bridge was completed connecting the east and west sections of the park."
   "The quarterly review meeting has been rescheduled to next Thursday afternoon."
   "Local farmers reported an excellent harvest season despite the unusual spring weather."
   "The museum announced a new exhibition featuring contemporary photography from around the world."
   "Traffic patterns changed significantly after the opening of the bypass road."
   "The school board approved funding for three new science laboratories."
   "Recent surveys indicate growing interest in renewable energy solutions among homeowners."
   "The community center hosted a successful fundraiser for the youth sports program."
   "New guidelines for workplace safety were distributed to all department managers."
   "The airport expansion project is expected to be completed by the end of next year."
   "A recent study found that walking thirty minutes daily significantly reduces stress levels."
   "The city council voted to increase spending on public transportation infrastructure."
   "Registration for the summer workshop series begins on the first of next month."
   "The technology conference attracted over two thousand attendees from fifteen countries."
   "New parking regulations will take effect starting the first day of the following quarter."
   "The botanical garden introduced several rare plant species to its tropical collection."
   "Customer feedback prompted changes to the return policy for online purchases."
   "The volunteer program expanded to include weekend activities for senior citizens."
   "Road maintenance crews completed resurfacing work on three major intersections."
   "The annual music festival featured performances by over forty local and regional bands."
   "A new recycling initiative aims to reduce landfill waste by twenty percent this year."
   "The fire department conducted safety inspections at all commercial buildings downtown."
   "Public health officials recommended additional precautions during the allergy season."
   "The university announced plans to build a new student recreation center on campus."
   "Evening classes in computer programming saw a significant increase in enrollment."
   "The harbor commission approved permits for two new commercial fishing vessels."
   "Recent renovations to the town hall included improved accessibility features."
   "The monthly book club meeting will feature a discussion of classic detective novels."
   "New street lighting was installed along the waterfront promenade for better visibility."
   "The agricultural cooperative reported record exports of organic produce this quarter."
   "Shuttle bus service between the train station and business district runs every fifteen minutes."
   "The historical society organized walking tours of the oldest neighborhoods in the city."
   "Updated zoning regulations allow for mixed-use development in several residential areas."
   "The swimming pool renovation includes a new filtration system and expanded deck area."
   "Local businesses participated in the annual sidewalk sale event last weekend."
   "The environmental agency released new data on water quality in regional streams."
   "Construction of the new medical facility is progressing ahead of schedule."
   "The transit authority extended late-night service on two popular bus routes."
   "A new community garden project will provide plots for fifty families this spring."
   "The school district implemented a revised curriculum for middle school mathematics."
   "Regional hospitals reported lower emergency room wait times after staffing increases."
   "The arts council awarded grants to twelve local organizations for cultural programming."
   "Updated building codes require improved insulation in all new residential construction."
   "The veterinary clinic expanded its services to include specialized exotic animal care."
   "Weekend parking restrictions in the historic district will be enforced beginning next month."])

(defn- make-rng
  "Create a seeded Random."
  ^Random [seed]
  (Random. (long seed)))

(defn- pick
  "Pick a random element from coll using rng."
  [^Random rng coll]
  (nth coll (.nextInt rng (count coll))))

(defn- shuffle-with
  "Shuffle a collection using the given rng."
  [^Random rng coll]
  (let [arr (java.util.ArrayList. ^java.util.Collection (vec coll))]
    (java.util.Collections/shuffle arr rng)
    (vec arr)))

(defn generate-filler-text
  "Generate approximately n-chars of filler text using seeded RNG.
   Groups sentences into paragraphs of 3-6 sentences."
  [n-chars seed]
  (let [rng (make-rng seed)
        sb (StringBuilder.)]
    (loop [para-count 0]
      (if (>= (.length sb) n-chars)
        (subs (.toString sb) 0 (min (.length sb) n-chars))
        (let [n-sentences (+ 3 (.nextInt rng 4))
              sentences (repeatedly n-sentences #(pick rng filler-sentences))
              para (str (str/join " " sentences) "\n\n")]
          (.append sb para)
          (recur (inc para-count)))))))

;; ============================================================================
;; S-NIAH Generation
;; ============================================================================

(def ^:private sniah-keys
  ["alpha" "bravo" "charlie" "delta" "echo" "foxtrot" "golf" "hotel"
   "india" "juliet" "kilo" "lima" "mike" "november" "oscar" "papa"
   "quebec" "romeo" "sierra" "tango" "uniform" "victor" "whiskey" "xray"])

(defn generate-sniah-example
  "Generate one S-NIAH example.
   Returns {:context str :query str :gold str :needle-position :early|:middle|:late
            :context-chars int :key str :value str}"
  [context-chars seed]
  (let [rng (make-rng seed)
        key-word (str (pick rng sniah-keys) "-" (.nextInt rng 100))
        value (str (+ 100000 (.nextInt rng 900000)))
        needle (str "One of the special magic numbers for " key-word " is: " value ".")
        ;; Generate filler minus needle length
        filler-chars (- context-chars (count needle) 2)
        filler (generate-filler-text filler-chars (+ seed 7919))
        ;; Insert needle at random position (by paragraph boundary)
        paragraphs (str/split filler #"\n\n")
        n-paras (count paragraphs)
        insert-idx (if (<= n-paras 1)
                     0
                     (+ 1 (.nextInt rng (dec n-paras))))
        position (cond
                   (< insert-idx (quot n-paras 3)) :early
                   (< insert-idx (* 2 (quot n-paras 3))) :middle
                   :else :late)
        context (str (str/join "\n\n" (concat (take insert-idx paragraphs)
                                              [needle]
                                              (drop insert-idx paragraphs))))]
    {:context context
     :query (str "What is the special magic number for " key-word "?")
     :gold value
     :key key-word
     :value value
     :needle-position position
     :context-chars (count context)}))

;; ============================================================================
;; OOLONG Generation
;; ============================================================================

(def ^:private category-templates
  {"abbreviation" ["What does %s stand for in technical documentation?"
                   "The abbreviation %s is commonly used in scientific papers."
                   "In formal writing, %s refers to a standard measurement unit."]
   "entity"       ["The company %s announced its quarterly earnings report today."
                   "Organization %s has been operating in the technology sector since its founding."
                   "The institution %s received recognition for its research contributions."]
   "description"  ["This concept involves the systematic analysis of complex data structures."
                   "The process describes a method for transforming raw information into actionable insights."
                   "An abstract framework that models the relationship between input variables and outcomes."]
   "human"        ["Dr. %s presented findings at the international conference on biomedical engineering."
                   "Professor %s authored several influential publications in the field of linguistics."
                   "Researcher %s contributed to the development of the new classification system."]
   "location"     ["The city of %s is located in the northern region of the province."
                   "The %s district encompasses several historic neighborhoods and commercial areas."
                   "The region surrounding %s features diverse geographical characteristics."]
   "numeric"      ["The measurement yielded a value of approximately %s units."
                   "Statistical analysis produced a result of %s with high confidence."
                   "The calculated quantity was determined to be %s based on the available data."]})

(def ^:private sample-names
  ["Anderson" "Chen" "Garcia" "Kim" "Müller" "Patel" "Rossi" "Silva"
   "Tanaka" "Williams" "Zhang" "Martin" "Ali" "Thompson" "Nakamura"])

(def ^:private sample-entities
  ["TechCorp" "BioGen" "DataSys" "NetPrime" "SkyLab" "CoreLogic"
   "InfoWave" "MetaLink" "NovaTech" "PulseAI"])

(def ^:private sample-locations
  ["Riverside" "Oakdale" "Westfield" "Brookhaven" "Clearwater"
   "Stonegate" "Fairview" "Hillcrest" "Lakeview" "Maplewood"])

(def ^:private sample-abbrevs
  ["TCP" "UDP" "HTTP" "DNS" "SSH" "SSL" "API" "SDK" "IDE" "CLI"])

(def ^:private generic-templates
  ["This item relates to the general topic of %s in contemporary analysis."
   "A recent study examined the role of %s in various applied contexts."
   "The concept of %s has been discussed extensively in recent literature."])

(defn- generate-item-text
  "Generate a text snippet for a given category."
  [^Random rng category]
  (let [templates (get category-templates category generic-templates)
        template (pick rng templates)]
    (case category
      "abbreviation" (format template (pick rng sample-abbrevs))
      "entity" (format template (pick rng sample-entities))
      "human" (format template (pick rng sample-names))
      "location" (format template (pick rng sample-locations))
      "numeric" (format template (str (.nextInt rng 10000)))
      "description" template
      ;; Generic fallback — use category name as the topic
      (format template category))))

(defn generate-oolong-example
  "Generate one OOLONG example with n items across categories.
   query-type: :most-frequent | :count-category
   Returns {:context str :query str :gold str :query-type kw
            :category-counts map :n-items int}"
  [n-items categories query-type seed]
  (let [rng (make-rng seed)
        ;; Create uneven distribution: one dominant category
        dominant-idx (.nextInt rng (count categories))
        dominant-cat (nth categories dominant-idx)
        ;; Dominant gets ~35%, rest split evenly
        dominant-count (max 1 (int (* n-items 0.35)))
        remaining (- n-items dominant-count)
        other-cats (vec (remove #{dominant-cat} categories))
        ;; Distribute remaining across other categories
        per-other (if (empty? other-cats) 0 (quot remaining (count other-cats)))
        leftover (if (empty? other-cats) remaining (rem remaining (count other-cats)))
        cat-counts (into {dominant-cat dominant-count}
                         (map-indexed (fn [i cat]
                                        [cat (+ per-other (if (< i leftover) 1 0))])
                                      other-cats))
        ;; Generate items
        items (shuffle-with
               rng
               (mapcat (fn [[cat n]]
                         (repeatedly n (fn [] {:category cat
                                               :text (generate-item-text rng cat)})))
                       cat-counts))
        ;; Build context: each item on its own line with category tag
        context (str/join "\n" (map-indexed
                                (fn [i {:keys [category text]}]
                                  (str "Item " (inc i) " [" category "]: " text))
                                items))
        ;; Build query and gold answer
        [query gold]
        (case query-type
          :most-frequent
          [(str "Looking at all " n-items " items above, which category label appears most frequently?")
           dominant-cat]
          :count-category
          (let [target-cat (pick rng (vec (keys cat-counts)))
                target-count (get cat-counts target-cat)]
            [(str "How many items have the category label \"" target-cat "\"?")
             (str target-count)]))]
    {:context context
     :query query
     :gold gold
     :query-type query-type
     :category-counts cat-counts
     :n-items n-items}))

;; ============================================================================
;; Simple Retrieval Generation
;; ============================================================================

(defn generate-retrieval-example
  "Generate one simple retrieval example with n-pairs key-value pairs.
   Returns {:context str :query str :gold str :target-key str :n-pairs int}"
  [n-pairs seed]
  (let [rng (make-rng seed)
        keys-pool (shuffle-with rng (mapv #(str (pick rng sniah-keys) "-" %)
                                          (range n-pairs)))
        values (mapv (fn [_] (str (+ 1000 (.nextInt rng 9000)))) (range n-pairs))
        target-idx (.nextInt rng n-pairs)
        target-key (nth keys-pool target-idx)
        target-value (nth values target-idx)
        context (str/join "\n" (map (fn [k v] (str "Key: " k ", Value: " v))
                                    keys-pool values))]
    {:context context
     :query (str "What is the value for key '" target-key "'?")
     :gold target-value
     :target-key target-key
     :n-pairs n-pairs}))
