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

;; ---------------------------------------------------------------------------
;; compile → proposal → accept / reject
;; ---------------------------------------------------------------------------

(def upper-sig (clj-llm/compile-signature "Upper" "Uppercase the word." {:word :string} {:out :string}))

(defn- fake-upper-llm
  "Teacher always right; student right only with >= `need` demos in its prompt."
  [need]
  {:chat (fn [lm messages & _]
           {::llm/usage {:cost {:total-cost 0.001}}
            :lm (:model lm)
            :sys (-> messages first :content)
            :word (second (re-find #"word: (\S+)" (-> messages second :content)))})
   :extract (fn [{:keys [lm sys word]} _]
              (let [right? (or (= lm "teacher") (>= (count (re-seq #"Example \d+" sys)) need))]
                (json/write-str {:out (if right? (str/upper-case word) "??")})))})

(defn- write-upper-dataset! []
  (p/register! (p/predictor {:id "test/upper" :signature upper-sig}))
  (let [f (programs/dataset-file "test/upper" "words")]
    (.mkdirs (.getParentFile f))
    (spit f (pr-str {:v 1 :predictor-id "test/upper" :label-source :hand
                     :examples (mapv #(clj-llm/example {:word (str "w" %)} {:out (str "W" %)}) (range 30))}))))

(defmacro ^:private with-upper-llm [need & body]
  `(let [f# (fake-upper-llm ~need)]
     (with-redefs [llm/chat-completion (:chat f#)
                   llm/extract-content (:extract f#)
                   config/resolve-sub-lm (constantly {:provider :openai :model "student" :message-format :openai})
                   config/resolve-tier-lm (fn [& args#]
                                            (when (= :deep (last args#))
                                              {:provider :openai :model "teacher" :message-format :openai}))]
       ~@body)))

(deftest compile-writes-a-reviewable-proposal-and-applies-nothing
  (write-upper-dataset!)
  (with-upper-llm 2
    (let [r (programs/compile-predictor "test/upper" "words" :trials 2 :max-bootstrapped 3 :parallel 1)]
      (is (nil? (:error r)))
      (is (= 1.0 (:best-score r)))
      (is (= 0.0 (:zero-shot-score r)))
      (is (= 1.0 (:teacher-score r)) "the teacher reference row is reported")
      (is (pos? (:calls r)))
      (is (pos? (:demos r)))
      (is (not (.exists (programs/params-file-for "test/upper"))) "compile never installs params")
      (let [review (slurp (:review r))]
        (is (str/includes? review "Here are examples of inputs"))
        (is (str/includes? review "teacher: `openai/teacher`"))
        (is (str/includes? review "| zero-shot | 0.000"))
        (is (str/includes? review "teacher, reference — not eligible"))
        (is (str/includes? review "teacher zero-shot 1.000"))
        (is (str/includes? review "passed the threshold · scores"))
        (is (str/includes? review "by programs accept test/upper")))
      (is (= [:pending] (mapv :status (programs/list-proposals "test/upper"))))
      (testing "accept installs the winner"
        (let [{:keys [params-file previous]} (programs/accept-proposal! "test/upper" (:proposal-id r))
              installed (edn/read-string (slurp params-file))]
          (is (nil? previous))
          (is (= params-file (.getPath (programs/params-file-for "test/upper"))))
          (is (= "bootstrap-random-search" (get-in installed [:compiled-by :optimizer])))
          (is (= "openai/student" (get-in installed [:compiled-by :lm])))
          (is (seq (:demos installed)))
          (is (= [:accepted] (mapv :status (programs/list-proposals "test/upper"))))
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"is accepted"
                                (programs/accept-proposal! "test/upper" (:proposal-id r)))))))))

(deftest a-blind-proposal-that-loses-to-zero-shot-installs-nothing
  (write-upper-dataset!)
  (with-upper-llm 0
    (let [r   (programs/compile-predictor "test/upper" "words" :optimizer "labeled-few-shot" :parallel 1)
          dir (.getParentFile (io/file (:review r)))]
      (is (:no-op r) "demos did not beat zero-shot (both 1.0; ties go to zero-shot)")
      (is (str/includes? (slurp (:review r)) "accepting installs empty params"))
      (is (= {} (edn/read-string (slurp (io/file dir "params.edn")))))
      (is (.exists (io/file dir "candidate.edn")) "the losing candidate is kept for review")
      (is (= {:status :rejected} (programs/reject-proposal! "test/upper" (:proposal-id r))))
      (is (= [:rejected] (mapv :status (programs/list-proposals "test/upper")))))))

(deftest compile-keeps-the-instructions-it-compiles-against
  (write-upper-dataset!)
  (let [pf (programs/params-file-for "test/upper")
        systems (atom [])]
    (.mkdirs (.getParentFile pf))
    (spit pf (pr-str {:instructions "BASE-INSTR uppercase the word."}))
    (clj-llm/set-params-roots! [(.getPath (programs/programs-root))])
    (try
      (with-upper-llm 2
        (let [f (fake-upper-llm 2)]
          (with-redefs [llm/chat-completion (fn [lm messages & more]
                                              (swap! systems conj (-> messages first :content))
                                              (apply (:chat f) lm messages more))]
            (let [r (programs/compile-predictor "test/upper" "words" :trials 1 :max-bootstrapped 2 :parallel 1)
                  proposal (edn/read-string (slurp (io/file (.getParentFile (io/file (:review r))) "params.edn")))]
              (is (seq @systems))
              (is (every? #(str/includes? % "BASE-INSTR") @systems)
                  "teacher, zero-shot and every candidate ran with the base instructions")
              (is (= "BASE-INSTR uppercase the word." (:instructions proposal))
                  "accepting the proposal keeps the instructions instead of dropping them")
              (is (seq (:demos proposal)))
              (is (str/includes? (slurp (:review r)) "Instructions override"))))
          (testing "a no-demo winner over base instructions says it installs the instructions"
            (with-upper-llm 0
              (let [r (programs/compile-predictor "test/upper" "words" :trials 1 :max-bootstrapped 2 :parallel 1)]
                (is (:no-op r))
                (is (str/includes? (slurp (:review r)) "installs the instructions/field-desc override")))))))
      (finally (clj-llm/set-params-roots! [])))))

(deftest repeats-reach-the-review-and-eval-summary
  (write-upper-dataset!)
  (with-upper-llm 2
    (let [r (programs/compile-predictor "test/upper" "words" :trials 1 :max-bootstrapped 2
                                        :parallel 1 :repeats 2)
          review (slurp (:review r))]
      (is (str/includes? review "2 passes per row"))
      (is (str/includes? review "| ± sd | vs zero-shot |"))
      (is (str/includes? review "✅"))
      (is (str/includes? review "beats zero-shot by more than the combined run-to-run noise"))))
  (with-upper-llm 0
    (let [{:keys [summary]} (programs/eval-predictor "test/upper" "words" "exact-match"
                                                     :split "val" :parallel 1 :repeats 3)]
      (is (= 3 (:completed-repeats summary)))
      (is (== 0.0 (:stddev summary)))
      (is (vector? (:least-stable summary)))
      (is (nil? (:per-example summary)) "the summary stays small"))))

(deftest an-unfinished-baseline-proposes-nothing
  ;; Found by the CLI smoke test: with the zero-shot row cut by the budget,
  ;; labeled-few-shot still proposed its 4 unvalidated demos.
  (write-upper-dataset!)
  (with-upper-llm 0
    (let [r   (programs/compile-predictor "test/upper" "words" :optimizer "labeled-few-shot"
                                          :parallel 1 :max-calls 1 :teacher-baseline? false)
          dir (.getParentFile (io/file (:review r)))]
      (is (nil? (:best r)))
      (is (zero? (:demos r)) "nothing was validated, so no demos are proposed")
      (is (:no-op r))
      (is (= {} (edn/read-string (slurp (io/file dir "params.edn")))))
      (is (.exists (io/file dir "candidate.edn")) "the unvalidated candidate is kept for inspection"))))

(deftest max-calls-caps-a-compile
  (write-upper-dataset!)
  (with-upper-llm 2
    (let [r (programs/compile-predictor "test/upper" "words" :trials 4 :max-bootstrapped 3
                                        :parallel 1 :max-calls 20)]
      (is (<= (:calls r) 20))
      (is (= "budget" (:stopped r))))))

(deftest accept-and-reject-are-not-llm-tools
  (let [names (set (map #(-> % meta :name str) programs/program-commands))]
    (is (contains? names "program$compile"))
    (is (not-any? #(re-find #"accept|reject" %) names))))
