;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.common.programs-test
  "Predictor datasets from prediction logs, named metrics, eval reports."
  (:require [ai.brainyard.agent.common.predictions :as predictions]
            [ai.brainyard.agent.common.programs :as programs]
            [ai.brainyard.agent.core.config :as config]
            [ai.brainyard.agent.core.feature :as feature]
            [ai.brainyard.agent.core.protocol :as proto]
            [ai.brainyard.clj-llm.core.llm :as llm]
            [ai.brainyard.clj-llm.core.predictor :as p]
            [ai.brainyard.clj-llm.interface :as clj-llm]
            [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]))

(def ^:dynamic *dir* nil)

(defn- delete-tree! [^java.io.File f]
  (when (.exists f)
    (doseq [^java.io.File c (reverse (file-seq f))] (.delete c))))

(def qa-sig (clj-llm/compile-signature "QA" "Answer." {:question :string} {:answer :string}))

(defn- with-tmp-project [f]
  (let [dir (io/file (System/getProperty "java.io.tmpdir") (str "by-programs-test-" (System/nanoTime)))]
    (.mkdirs dir)
    (p/register! (p/predictor {:id "test/qa" :signature qa-sig}))
    (binding [config/*sessions-root-override* (.getAbsolutePath (io/file dir "sessions"))
              *dir* dir]
      (with-redefs [config/project-dir (constantly (.getAbsolutePath dir))
                    config/resolve-sub-lm (constantly {:provider :openai :model "sub-model"
                                                       :message-format :openai})]
        (try (f) (finally (delete-tree! dir)))))))

(use-fixtures :each with-tmp-project)

(defn- log! [sid record]
  (predictions/append-prediction! sid (merge {:v 1 :session sid :predictor-id "test/qa"} record)))

(deftest records->examples-filters
  (let [{:keys [examples skipped]}
        (programs/records->examples
         [{:inputs {:question "q1"} :outputs {:answer "a1"} :valid? true}
          {:inputs {:question "q1"} :outputs {:answer "a1-again"}}
          {:inputs {:question "q2"} :error "boom"}
          {:inputs {:question "q3"} :outputs {:answer "x"} :valid? false}
          {:inputs {:question "q4 …[truncated 12 chars]"} :outputs {:answer "x"}}
          {:inputs {:question "token sk-abcdefghijklmnopqrstu"} :outputs {:answer "ok"}}]
         {})]
    (is (= {:error 1 :invalid 1 :clipped 1 :duplicate 1} skipped))
    (is (= ["q1" "token [REDACTED]"] (mapv #(get-in % [:inputs :question]) examples)))
    (is (= {:answer "a1"} (:labels (first examples))) "oldest occurrence wins")))

(deftest build-then-eval-writes-a-report
  (log! "s1" {:inputs {:question "q1"} :outputs {:answer "a1"}})
  (log! "s1" {:predictor-id "other/pred" :inputs {:question "zz"} :outputs {:answer "zz"}})
  (log! "s2" {:inputs {:question "q2"} :outputs {:answer "a2"}})
  (let [built (programs/build-dataset "test/qa" "silver")]
    (is (= 2 (:examples built)) "only this predictor's records, across sessions")
    (is (= ["silver"] (programs/list-datasets "test/qa")))
    (is (= :prediction-log (:label-source (programs/load-dataset "test/qa" "silver")))))
  (let [models (atom #{})
        {:keys [report-path summary]}
        (with-redefs [llm/chat-completion (fn [lm messages & _]
                                            (swap! models conj (:model lm))
                                            {:q (-> messages second :content)})
                      llm/extract-content (fn [resp _]
                                            (json/write-str {:answer (if (str/includes? (:q resp) "q1") "a1" "nope")}))]
          (programs/eval-predictor "test/qa" "silver" "exact-match" :parallel 1))]
    (is (== 0.5 (:score summary)))
    (is (= #{"sub-model"} @models) "defaults to the agent's sub-LM")
    (is (= "exact-match" (:metric summary)))
    (is (= :prediction-log (:label-source summary)))
    (let [full (edn/read-string (slurp report-path))]
      (is (= 2 (count (:per-example full))))
      (is (= (:dataset-hash summary) (:dataset-hash full))))))

(deftest eval-calls-never-feed-the-prediction-log
  (log! "s1" {:inputs {:question "q1"} :outputs {:answer "a1"}})
  (programs/build-dataset "test/qa" "silver")
  (let [agent (reify proto/IAgent (agent-id [_] :a/x) (session-id [_] "s1"))]
    (clj-llm/set-trace-sink! predictions/sink)
    (try
      (with-redefs [feature/on? (constantly true)
                    llm/chat-completion (fn [& _] {})
                    llm/extract-content (constantly (json/write-str {:answer "a1"}))]
        (binding [proto/*current-agent* agent]
          (programs/eval-predictor "test/qa" "silver" "exact-match" :parallel 2)))
      (finally (clj-llm/set-trace-sink! nil)))
    (is (= 1 (count (predictions/read-predictions "s1"))) "still just the original record")))

(deftest bad-names-and-unknowns-are-errors
  (is (thrown? clojure.lang.ExceptionInfo (programs/dataset-file "../x" "d")))
  (is (thrown? clojure.lang.ExceptionInfo (programs/dataset-file "test/qa" "../d")))
  (is (str/includes? (:error (programs/program$eval :predictor-id "nope/x" :dataset "d")) "Unknown predictor"))
  (testing "a dataset with no usable records is refused, not written empty"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"No usable prediction records"
                          (programs/build-dataset "test/qa" "d")))
    (is (empty? (programs/list-datasets "test/qa")))))

(deftest graph-extract-f1-metric
  (let [m ((:make (get programs/metrics "graph-extract-f1")))
        ex (clj-llm/example {:activity "x"}
                            {:entities [{:name "Polylith"} {:name "GraalVM"}]
                             :relations [{:src "Polylith" :relation "uses" :dst "GraalVM"}]})]
    (is (== 1.0 (m ex {:outputs {:entities [{:name "graalvm"} {:name "polylith"}]
                                 :relations [{:src "polylith" :relation "USES" :dst "graalvm"}]}} [])))
    (is (== 0.5 (m ex {:outputs {:entities [{:name "Polylith"} {:name "GraalVM"}] :relations []}} [])))
    (testing "an alias matches, and relations may name the entity by its alias"
      (is (== 1.0 (m ex {:outputs {:entities [{:name "native-image" :aliases ["GraalVM"]}
                                              {:name "polylith"}]
                                   :relations [{:src "Polylith" :relation "uses" :dst "native-image"}]}}
                     []))))
    (testing "one prediction cannot satisfy two gold entities"
      (is (< (m ex {:outputs {:entities [{:name "x" :aliases ["Polylith" "GraalVM"]}]
                              :relations []}}
                [])
             0.5)))
    (testing "nothing expected, nothing found"
      (is (== 1.0 (m (clj-llm/example {} {:entities [] :relations []})
                     {:outputs {:entities [] :relations []}} []))))))
