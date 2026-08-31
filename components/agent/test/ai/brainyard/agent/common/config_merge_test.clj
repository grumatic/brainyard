;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.common.config-merge-test
  "The preview must describe the write.

   config$diff merged its :proposed with a `merge-with` that recursed exactly
   ONE level, while config$apply used the recursive `deep-merge`. For a nested
   map — `{:mcp {:servers {:clickhouse …}}}` — the two disagreed: the diff
   showed every OTHER server being deleted, and the apply kept them.

   That is worse than either behaviour on its own. An agent installing one MCP
   server saw its four siblings marked for removal and refused; the same input
   applied cleanly. A preview is only useful if it is trusted, and it is only
   trustworthy if it runs the same merge.

   It hid because top-level siblings were never affected — only a map nested two
   deep, which is exactly where :mcp/:servers lives."
  (:require [ai.brainyard.agent.common.config :as cfg]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]))

(def ^:private existing
  {:mcp   {:servers {:linear           {:enabled true}
                     :github           {:transport :stdio :enabled true}
                     :github2          {:transport :stdio :enabled true}
                     :google-workspace {:transport :stdio :enabled true}}}
   :agent {:config {:max-iterations 100}}})

(def ^:private proposed
  {:mcp {:servers {:clickhouse {:transport :stdio :enabled true}}}})

(deftest deep-merge-keeps-nested-siblings
  (let [deep-merge @#'cfg/deep-merge]

    (testing "a proposed server is ADDED to the existing ones, not swapped for them"
      (is (= #{:linear :github :github2 :google-workspace :clickhouse}
             (set (keys (get-in (deep-merge existing proposed) [:mcp :servers]))))))

    (testing "the one-level merge that used to back the diff loses all four"
      ;; Kept as the counter-example rather than deleted with the code: this is
      ;; the exact expression config$diff used, and it is the thing that must
      ;; never come back.
      (let [shallow (merge-with (fn [a b] (if (and (map? a) (map? b)) (merge a b) b))
                                existing proposed)]
        (is (= #{:clickhouse} (set (keys (get-in shallow [:mcp :servers]))))
            "documents the bug — if this ever equals the deep result, the fixture is wrong")))

    (testing "unrelated top-level keys survive, which is why this went unnoticed"
      (is (= 100 (get-in (deep-merge existing proposed) [:agent :config :max-iterations]))))

    (testing "a scalar from the proposal still wins"
      (is (false? (get-in (deep-merge {:a {:b true}} {:a {:b false}}) [:a :b]))))

    (testing "vectors are replaced wholesale, not concatenated"
      (is (= [3] (get-in (deep-merge {:a {:v [1 2]}} {:a {:v [3]}}) [:a :v]))))))

(deftest diff-reports-no-removals-for-a-nested-add
  (let [dir (io/file (System/getProperty "java.io.tmpdir")
                     (str "cfg-merge-test-" (System/nanoTime)))
        by  (io/file dir ".brainyard")]
    (try
      (.mkdirs by)
      (spit (io/file by "config.edn") (pr-str existing))
      (let [res (cfg/config$diff :proposed proposed :scope :project
                                 :project-dir (.getAbsolutePath dir))]
        (is (nil? (:error res)))

        (testing "installing one server removes nothing"
          (is (= {} (:removes (:structural res)))))

        (testing "and every existing server still appears in the rendered diff"
          (doseq [k [:linear :github :github2 :google-workspace]]
            (is (re-find (re-pattern (name k)) (str (:diff res)))
                (str (name k) " went missing from the preview")))))
      (finally
        (doseq [f (reverse (file-seq dir))] (.delete ^java.io.File f))))))
