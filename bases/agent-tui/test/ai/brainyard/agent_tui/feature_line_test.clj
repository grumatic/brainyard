;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent-tui.feature-line-test
  "The `/feature` row: an annotation must not crowd out the `:doc`.

   `self-improve/nudges` is the worst case in the catalogue — an any-of
   requirement on two siblings — and it is the row that first lost its doc."
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.string :as str]
            [ai.brainyard.agent.core.feature :as feat]
            [ai.brainyard.agent-tui.session :as tui-session]
            [ai.brainyard.agent-tui.commands :as cmd]))

(defn- strip-ansi [s] (str/replace s #"\x1b\[[0-9;]*m" ""))

(defn- render-line
  "The plain text `emit-feature-line!` would print for `fid` in state `st`."
  [fid st]
  (let [out (atom nil)]
    (with-redefs [tui-session/emit! (fn [s & _] (reset! out s))]
      (#'cmd/emit-feature-line! fid st))
    (strip-ansi @out)))

(deftest same-family-requirement-is-unqualified
  (testing "a sibling requirement drops the family the row is already under"
    (let [f (feat/feature-doc :self-improve/nudges)]
      (is (= "needs distillation|refinement"
             (first (#'cmd/feature-annotation f (:gate f) {:unmet (:requires f)}))))))
  (testing "a cross-family requirement stays qualified"
    (is (= "needs memory/recall"
           (first (#'cmd/feature-annotation {:family :ui :gate :x} :x
                                            {:unmet #{:memory/recall}}))))))

(deftest unmet-row-still-shows-its-doc
  (let [f    (feat/feature-doc :self-improve/nudges)
        line (render-line :self-improve/nudges {:on? false :unmet (:requires f)})]
    (is (str/includes? line "needs distillation|refinement"))
    (is (str/includes? line "Surfaces pending skill proposals")
        "the annotation must leave room for the doc")))
