;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: Apache-2.0
;; Licensed under the Apache License, Version 2.0 (the "License"); you may
;; not use this file except in compliance with the License. See LICENSE at
;; the repository root and CONTRIBUTING.md for contribution terms.

(ns ai.brainyard.display-block.eval-test
  (:require [ai.brainyard.display-block.core.providers.file-backed :as file-backed]
            [ai.brainyard.display-block.core.registry :as registry]
            [ai.brainyard.display-block.interface :as block]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]))

(def ^:dynamic *tmp-dir* nil)

(defn- tmp-dir-fixture [t]
  (let [d (java.nio.file.Files/createTempDirectory
           "display-block-eval-test"
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

(defn- long-body
  "Build a multi-line code body of `n` lines."
  [n]
  (str/join "\n" (map #(str "(form " % ")") (range 1 (inc n)))))

(defn- block-id-from
  "Extract the block id from the marker line at the tail of `out`."
  [out]
  (-> out str/split-lines last block/parse-marker :id))

(deftest short-content-passthrough
  (let [out (block/eval-code-block "(println :hi)")]
    (is (= "(println :hi)" out))
    (is (empty? (block/all-blocks))
        "no provider should be registered for short content")))

(deftest long-content-registers-block-with-code-defaults
  (let [out  (block/eval-code-block (long-body 25) {:max-collapsed-lines 5})
        out-lines (str/split-lines out)
        marker-line (last out-lines)
        parsed (block/parse-marker marker-line)
        m (block/block-meta (:id parsed))]
    (testing "first 5 lines kept inline"
      (is (= ["(form 1)" "(form 2)" "(form 3)" "(form 4)" "(form 5)"]
             (take 5 out-lines))))
    (testing "marker registered + code class defaults applied"
      (is (= :collapsed (:state parsed)))
      (is (= "+20 lines" (:summary parsed)))
      (is (= :eval-code  (:class m)))
      (is (= "eval-code" (:class-dir m)))
      (is (= "Code"     (:label m))
          "no :lang -> bare 'Code' label"))))

(deftest lang-shapes-default-label
  (doseq [[lang expected] [[:clojure "Code (clojure)"]
                           [:bash    "Code (bash)"]
                           [:python  "Code (python)"]
                           ["Python" "Code (python)"]
                           ["  ruby" "Code (ruby)"]]]
    (let [out (block/eval-code-block (long-body 15)
                                     {:max-collapsed-lines 3 :lang lang})
          id  (block-id-from out)]
      (is (= expected (:label (block/block-meta id)))
          (str "lang=" (pr-str lang))))))

(deftest blank-lang-falls-back-to-bare-code
  (doseq [lang [nil "" "   "]]
    (let [out (block/eval-code-block (long-body 15)
                                     {:max-collapsed-lines 3 :lang lang})
          id  (block-id-from out)]
      (is (= "Code" (:label (block/block-meta id)))
          (str "lang=" (pr-str lang))))))

(deftest explicit-label-overrides-lang
  (let [out (block/eval-code-block (long-body 15)
                                   {:max-collapsed-lines 3
                                    :lang :clojure
                                    :label "My Snippet"})
        id  (block-id-from out)]
    (is (= "My Snippet" (:label (block/block-meta id))))))

(deftest writes-to-eval-code-classdir
  (let [out  (block/eval-code-block (long-body 15) {:max-collapsed-lines 3})
        id   (block-id-from out)
        path (block/resource-path id)]
    (is (some? path))
    (is (.exists (io/file path)))
    (is (str/includes? path "/eval-code/")
        "file-backed provider should land under the eval-code class-dir")))

(deftest expand-roundtrip-yields-tail-then-marker
  ;; New design: -expanded-lines returns ONLY the hidden tail (lines
  ;; beyond the head already in scrollback) followed by the expanded
  ;; marker. The Code section's head is the first :max-collapsed-lines lines, so
  ;; the tail begins at form (:max-collapsed-lines + 1).
  (let [out (block/eval-code-block (long-body 30)
                                   {:max-collapsed-lines 5 :lang :bash})
        id  (block-id-from out)
        exp (block/expand-lines id)]
    (is (vector? exp))
    (is (= "(form 6)" (first exp))
        "first tail line is the form right after the inline head")
    (is (= :expanded (:state (block/parse-marker (last exp))))
        "last line is the expanded marker")
    (is (re-find #"Enter: collapse" (last exp))
        "expanded marker carries the state-aware Enter:collapse hint")))

;; ----------------------------------------------------------------------
;; Result / Output / Error variants — same shape as eval-code-block but
;; without :lang and with fixed labels.
;; ----------------------------------------------------------------------

(def section-fns
  "[fn class class-dir label]"
  [[block/eval-result-block :eval-result "eval-result" "Result"]
   [block/eval-output-block :eval-output "eval-output" "Output"]
   [block/eval-error-block  :eval-error  "eval-error"  "Error"]])

(deftest section-blocks-short-content-passthrough
  (doseq [[f _ _ label] section-fns]
    (let [out (f "small body")]
      (is (= "small body" out)
          (str label ": short content should pass through"))
      (is (empty? (block/all-blocks))
          (str label ": no provider for short content")))))

(deftest section-blocks-defaults-applied
  (doseq [[f class class-dir label] section-fns]
    (let [out  (f (long-body 25) {:max-collapsed-lines 5})
          id   (block-id-from out)
          m    (block/block-meta id)
          path (block/resource-path id)]
      (testing (str label ": class / class-dir / label defaults")
        (is (= class     (:class m)))
        (is (= class-dir (:class-dir m)))
        (is (= label     (:label m))))
      (testing (str label ": file lands under the right class-dir")
        (is (some? path))
        (is (.exists (io/file path)))
        (is (str/includes? path (str "/" class-dir "/")))))))

(deftest section-blocks-explicit-label-overrides
  (doseq [[f _ _ default-label] section-fns]
    (let [out (f (long-body 15) {:max-collapsed-lines 3 :label "Custom"})
          id  (block-id-from out)]
      (is (= "Custom" (:label (block/block-meta id)))
          (str default-label ": caller-supplied :label should win")))))

(deftest section-blocks-expand-roundtrip-yields-tail-then-marker
  (doseq [[f _ _ label] section-fns]
    (let [out (f (long-body 30) {:max-collapsed-lines 5})
          id  (block-id-from out)
          exp (block/expand-lines id)]
      (is (vector? exp))
      (is (= "(form 6)" (first exp))
          (str label ": tail starts immediately after the inline head"))
      (is (= :expanded (:state (block/parse-marker (last exp))))
          (str label ": last line is the expanded marker"))
      (is (re-find #"Enter: collapse" (last exp))
          (str label ": marker carries the Enter:collapse hint")))))
