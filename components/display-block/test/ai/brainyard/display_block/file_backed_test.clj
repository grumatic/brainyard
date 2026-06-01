;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: Apache-2.0
;; Licensed under the Apache License, Version 2.0 (the "License"); you may
;; not use this file except in compliance with the License. See LICENSE at
;; the repository root and CONTRIBUTING.md for contribution terms.

(ns ai.brainyard.display-block.file-backed-test
  (:require [ai.brainyard.display-block.core.providers.file-backed :as file-backed]
            [ai.brainyard.display-block.core.registry :as registry]
            [ai.brainyard.display-block.interface :as block]
            [ai.brainyard.display-block.interface.protocol :as p]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]))

(def ^:dynamic *tmp-dir* nil)

(defn- tmp-dir-fixture [t]
  (let [d (java.nio.file.Files/createTempDirectory "display-block-test"
                                                   (into-array java.nio.file.attribute.FileAttribute []))]
    (binding [*tmp-dir* (str d)
              file-backed/*working-dir* (str d)]
      (try (registry/clear!) (t)
           (finally
             (registry/clear!)
             (try (doseq [^java.io.File f (reverse (file-seq (io/file (str d))))]
                    (.delete f))
                  (catch Exception _)))))))

(use-fixtures :each tmp-dir-fixture)

(deftest make-writes-file-and-meta
  (let [body (str/join "\n" (map #(str "line " %) (range 1 31)))
        p    (file-backed/make body
                               {:id "abc123" :class :code :class-dir "snippets"
                                :label "Code" :hidden-lines 20})
        path (p/-resource-path p)]
    (is (some? path))
    (is (.exists (io/file path)))
    (is (= body (slurp path)))
    (is (= 30 (:total-lines (p/-meta p))))
    (is (= 20 (:hidden-lines (p/-meta p))))))

(deftest collapsed-then-expanded-roundtrip
  (let [body (str/join "\n" (map #(str "line " %) (range 1 51)))
        p    (file-backed/make body
                               {:id "ff00aa" :class :code :class-dir "snippets"
                                :label "Code" :hidden-lines 40
                                :max-expanded-lines 100
                                :hint "Enter: expand, Ctrl-O: edit"})
        coll (p/-collapsed-marker-line p)
        exp  (p/-expanded-lines p)]
    (testing "collapsed marker parses back"
      (let [m (block/parse-marker coll)]
        (is (= "ff00aa" (:id m)))
        (is (= :collapsed (:state m)))
        (is (str/includes? (:hint m) "Ctrl-O"))))
    (testing "expanded body shape (tail-only, no notices)"
      ;; head was lines 1..10 (kept inline); hidden tail is lines 11..50.
      (is (= "line 11" (first exp))
          "tail starts at the first hidden line")
      (is (= 40 (count (filter #(str/starts-with? % "line ") exp)))
          "only hidden tail lines (40), not the duplicated full body (50)")
      (is (= :expanded (:state (block/parse-marker (last exp))))))))

(deftest dispose-deletes-file
  (let [p (file-backed/make "a\nb\nc"
                            {:id "xx99" :class-dir "snippets"})
        path (p/-resource-path p)]
    (is (.exists (io/file path)))
    (p/-dispose! p)
    (is (not (.exists (io/file path))))))

(deftest text-block-factory-end-to-end
  (let [body (str/join "\n" (map #(str "L" %) (range 1 26)))
        out  (block/text-block body
                               {:max-collapsed-lines 5
                                :class :code
                                :label "Code"
                                :storage :file
                                :file-opts {:class-dir "snippets"}})
        out-lines (str/split-lines out)]
    (testing "first 5 lines preserved"
      (is (= ["L1" "L2" "L3" "L4" "L5"] (take 5 out-lines))))
    (testing "marker line tail"
      (let [marker-line (last out-lines)
            parsed (block/parse-marker marker-line)]
        (is (= :collapsed (:state parsed)))
        (is (= "+20 lines" (:summary parsed)))
        (is (some? (block/get-block (:id parsed))))
        (is (= 25 (:total-lines (block/block-meta (:id parsed)))))))))

(deftest text-block-leaves-short-content-untouched
  (let [out (block/text-block "one\ntwo\nthree"
                              {:max-collapsed-lines 10 :storage :memory})]
    (is (= "one\ntwo\nthree" out))
    (is (empty? (block/all-blocks)))))
