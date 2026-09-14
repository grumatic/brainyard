;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.common.predictions-test
  "Per-session named-predictor call log (predictions.edn)."
  (:require [ai.brainyard.agent.common.predictions :as predictions]
            [ai.brainyard.agent.core.config :as config]
            [ai.brainyard.agent.core.feature :as feature]
            [ai.brainyard.agent.core.protocol :as proto]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]))

(defn- delete-tree! [^java.io.File f]
  (when (.exists f)
    (doseq [^java.io.File c (reverse (file-seq f))] (.delete c))))

(defn- with-tmp-root [f]
  (let [dir (io/file (System/getProperty "java.io.tmpdir")
                     (str "by-predictions-test-" (System/nanoTime)))]
    (.mkdirs dir)
    (binding [config/*sessions-root-override* (.getAbsolutePath dir)]
      (try (f) (finally (delete-tree! dir))))))

(use-fixtures :each with-tmp-root)

(defn- stub-agent [aid sid]
  (reify proto/IAgent
    (agent-id [_] aid)
    (session-id [_] sid)))

(def ^:private entry
  {:predictor-id   "coact/think-act-code"
   :inputs         {:question "Q" :iterations (apply str (repeat 5000 "x"))}
   :outputs        {:answer "A"}
   :reasoning      "because"
   :valid?         true
   :usage          {:input-tokens 10 :output-tokens 3
                    :cache {:read-tokens 7} :cost {:total-cost 0.01}}
   :model          "m"
   :params-source  nil
   :elapsed-ms     42})

(deftest entry->record-projects-and-clips
  (let [r (predictions/entry->record (assoc entry :context {:node-id :coact.action/think-act-code})
                                     "s1" :coact-agent/x)]
    (is (= 1 (:v r)))
    (is (= "s1" (:session r)))
    (is (= ":coact-agent/x" (:agent r)))
    (is (= :coact.action/think-act-code (:node-id r)))
    (is (= {:in 10 :out 3 :cache-read 7 :cost 0.01} (:usage r)))
    (is (not (contains? r :params-source)) "nil source is omitted, not recorded as nil")
    (testing "long strings are clipped with a marker; short ones untouched"
      (is (str/includes? (get-in r [:inputs :iterations]) "…[truncated 1000 chars]"))
      (is (= "Q" (get-in r [:inputs :question]))))))

(deftest sink-routes-by-context-agent-then-current-agent
  (let [a (stub-agent :coact-agent/x "s1")
        b (stub-agent :coact-agent/y "s2")]
    (with-redefs [feature/on? (fn [_ fid] (= fid :analytics/predictions))]
      (testing "the BT node's trace-context agent wins"
        (binding [proto/*current-agent* b]
          (predictions/sink (assoc entry :context {:agent a}))))
      (testing "no context agent ⇒ the current agent"
        (binding [proto/*current-agent* b]
          (predictions/sink entry)))
      (testing "no agent at all ⇒ not logged anywhere"
        (predictions/sink entry)))
    (is (= 1 (count (predictions/read-predictions "s1"))))
    (is (= 1 (count (predictions/read-predictions "s2"))))
    (is (= ["coact/think-act-code"] (mapv :predictor-id (predictions/read-predictions "s1" "coact/think-act-code"))))
    (is (empty? (predictions/read-predictions "s1" "memory/graph-extract")))))

(deftest sink-writes-nothing-when-the-gate-is-off
  (with-redefs [feature/on? (constantly false)]
    (predictions/sink (assoc entry :context {:agent (stub-agent :a/x "s3")})))
  (is (not (.exists (predictions/session-predictions-file "s3")))))
