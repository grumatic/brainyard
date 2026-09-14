;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.clj-llm.predictor-test
  (:require [ai.brainyard.clj-llm.core.chain-of-thought :as cot]
            [ai.brainyard.clj-llm.core.llm :as llm]
            [ai.brainyard.clj-llm.core.predictor :as p]
            [ai.brainyard.clj-llm.core.prompt :as prompt]
            [ai.brainyard.clj-llm.core.signature :as sig]
            [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]))

(def qa
  (sig/compile-signature
   "QA"
   "Answer questions accurately."
   {:context  [:string {:desc "Background"}]
    :question [:string {:desc "The question"}]}
   {:answer [:string {:desc "The answer"}]
    :confidence :double}))

(def fake-lm {:provider :openai :model "fake-model" :message-format :openai})

(defn- with-clean-store [f]
  (p/set-params-roots! [])
  (p/set-lm-resolver! nil)
  (p/set-trace-sink! nil)
  (try (f)
       (finally
         (p/set-params-roots! [])
         (p/set-lm-resolver! nil)
         (p/set-trace-sink! nil))))

(use-fixtures :each with-clean-store)

(defn- stub-llm
  "Run f with chat-completion stubbed to reply `reply`; returns
   {:result … :calls [{:lm … :messages …}]}."
  [reply f]
  (let [calls (atom [])]
    (with-redefs [llm/chat-completion (fn [lm messages & _]
                                        (swap! calls conj {:lm lm :messages messages})
                                        {})
                  llm/extract-content (constantly (json/write-str reply))]
      {:result (f) :calls @calls})))

(defn- system-content [calls] (-> calls first :messages first :content))

(defn- tmp-dir []
  (doto (io/file (System/getProperty "java.io.tmpdir")
                 (str "predictor-test-" (System/nanoTime)))
    (.mkdirs)))

;; ---------------------------------------------------------------------------
;; Demos in the prompt
;; ---------------------------------------------------------------------------

(deftest no-demos-is-byte-identical
  (let [ins {:question "Q?" :context "C"}]
    (doseq [o [{} {:chain-of-thought? true}]]
      (is (= (prompt/build-messages qa ins o)
             (prompt/build-messages qa ins (assoc o :demos []))
             (prompt/build-messages qa ins (assoc o :demos nil)))))))

(deftest demos-render-before-instructions
  (let [demos [{:inputs {:question "2+2?" :context "math"}
                :outputs {:answer "4" :confidence 0.9 :bogus "x"}}]
        content (-> (prompt/build-messages qa {:question "Q"} {:demos demos}) first :content)
        demo-at (str/index-of content "Here are examples")
        obj-at  (str/index-of content "your objective is")]
    (testing "demos sit between the format line and the objective"
      (is (and demo-at obj-at (< (str/index-of content "Respond with a JSON object") demo-at obj-at))))
    (testing "demo inputs use the live input-line format and declared order"
      (is (str/includes? content "Input:\ncontext: math\nquestion: 2+2?")))
    (testing "output JSON keeps declared field order and drops undeclared keys"
      (is (str/includes? content "{\"answer\":\"4\",\"confidence\":0.9}"))
      (is (not (str/includes? content "bogus"))))
    (testing "the breakdown attributes the demos part"
      (is (contains? (get-in (prompt/build-messages-with-breakdown qa {:question "Q"} {:demos demos})
                             [:token-breakdown :dspy-signature :parts])
                     :demos)))))

(deftest cot-demos-put-reasoning-first
  (let [demos [{:inputs {:question "q"} :outputs {:answer "a" :confidence 1.0} :reasoning "because"}]]
    (is (str/includes? (prompt/render-demos qa demos {:chain-of-thought? true})
                       "{\"reasoning\":\"because\",\"answer\":\"a\",\"confidence\":1.0}"))
    (is (not (str/includes? (prompt/render-demos qa demos {}) "reasoning"))
        "a non-CoT prompt never shows a reasoning field its schema lacks")))

;; ---------------------------------------------------------------------------
;; Signature transforms
;; ---------------------------------------------------------------------------

(deftest transforms-recompile-the-schema
  (testing "prepend-output puts the field first in outputs and the JSON schema"
    (let [s (p/prepend-output qa :rationale [:string {:desc "Why"}])]
      (is (= [:rationale :answer :confidence] (keys (:outputs s))))
      (is (contains? (get-in s [:output-json-schema :properties]) "rationale"))))
  (testing "append-input goes last in render order"
    (let [s (p/append-input qa :feedback :string)]
      (is (= [:context :question :feedback] (:input-order s)))))
  (testing "with-field-descs rewrites descriptions, bare and props forms"
    (let [s (p/with-field-descs qa {:answer "A short answer" :confidence "0..1"})]
      (is (= [:string {:desc "A short answer"}] (get-in s [:outputs :answer])))
      (is (= [:double {:desc "0..1"}] (get-in s [:outputs :confidence])))
      (is (= [:answer :confidence] (keys (:outputs s))))))
  (testing "with-instructions keeps extra keys a caller assoc'd"
    (let [s (p/with-instructions (assoc qa :custom 1) "Be terse.")]
      (is (= "Be terse." (:instructions s)))
      (is (= 1 (:custom s))))))

;; ---------------------------------------------------------------------------
;; Predictor values
;; ---------------------------------------------------------------------------

(deftest ids-cannot-escape-a-params-root
  (doseq [bad [nil "" "../x" "a/../b" "/abs" "a//b" "a/." ".hidden" "a b"]]
    (is (not (p/valid-id? bad)) (pr-str bad)))
  (doseq [good ["qa" "memory/graph-extract" "a.b/c_d-1"]]
    (is (p/valid-id? good) good))
  (is (thrown? clojure.lang.ExceptionInfo (p/predictor {:id "../x" :signature qa}))))

(deftest invalid-default-params-fail-at-definition
  (is (thrown? clojure.lang.ExceptionInfo
               (p/predictor {:id "qa" :signature qa :params {:demos [{:inputs "nope"}]}}))))

;; ---------------------------------------------------------------------------
;; run: params resolution
;; ---------------------------------------------------------------------------

(deftest run-without-params-matches-predict
  (let [pred (p/predictor {:id "t/qa" :signature qa})
        reply {:answer "x" :confidence 0.5}
        direct (stub-llm reply #(ai.brainyard.clj-llm.core.predict/predict qa {:question "Q"} :lm-config fake-lm))
        viaP   (stub-llm reply #(p/run pred {:question "Q"} :lm-config fake-lm))]
    (is (= (:calls direct) (:calls viaP)) "same messages, same LM")
    (is (= {:answer "x" :confidence 0.5} (-> viaP :result :outputs)))
    (is (= "t/qa" (-> viaP :result :predictor-id)))))

(deftest params-layers-first-found-wins-whole
  (let [root-hi (tmp-dir)
        root-lo (tmp-dir)
        pred (p/predictor {:id "t/qa" :signature qa
                           :params {:instructions "DEFAULT-INSTR"}})]
    (p/set-params-roots! [(.getPath root-hi) (.getPath root-lo)])
    (testing "defaults apply when no file exists"
      (is (str/includes? (system-content (:calls (stub-llm {} #(p/run pred {:question "Q"} :lm-config fake-lm))))
                         "DEFAULT-INSTR")))
    (testing "a lower root's file beats defaults"
      (io/make-parents (p/params-file root-lo "t/qa"))
      (spit (p/params-file root-lo "t/qa") (pr-str {:instructions "LO-INSTR"}))
      (let [{:keys [calls result]} (stub-llm {} #(p/run pred {:question "Q"} :lm-config fake-lm))]
        (is (str/includes? (system-content calls) "LO-INSTR"))
        (is (str/ends-with? (:params-source result) "t/qa.edn"))))
    (testing "a higher root wins, and does not merge the lower record"
      (io/make-parents (p/params-file root-hi "t/qa"))
      (spit (p/params-file root-hi "t/qa")
            (pr-str {:demos [{:inputs {:question "dq"} :outputs {:answer "da" :confidence 1.0}}]}))
      (let [content (system-content (:calls (stub-llm {} #(p/run pred {:question "Q"} :lm-config fake-lm))))]
        (is (str/includes? content "question: dq"))
        (is (str/includes? content "Answer questions accurately."))
        (is (not (str/includes? content "LO-INSTR")))))
    (testing "with-params beats every file"
      (p/with-params {"t/qa" {:instructions "DYN-INSTR"}}
        (let [{:keys [calls result]} (stub-llm {} #(p/run pred {:question "Q"} :lm-config fake-lm))]
          (is (str/includes? (system-content calls) "DYN-INSTR"))
          (is (= :dynamic (:params-source result))))))
    (testing "an invalid file is skipped, falling through to the next layer"
      (spit (p/params-file root-hi "t/qa") (pr-str {:tier :enormous}))
      (is (str/includes? (system-content (:calls (stub-llm {} #(p/run pred {:question "Q"} :lm-config fake-lm))))
                         "LO-INSTR")))))

(deftest demos-come-only-from-params
  (let [pred (p/predictor {:id "t/qa" :signature qa})
        {:keys [calls]} (stub-llm {} #(p/run pred {:question "Q"}
                                             :lm-config fake-lm
                                             :demos [{:inputs {:question "INJECTED"} :outputs {}}]))]
    (is (not (str/includes? (system-content calls) "INJECTED")))))

(deftest long-demo-inputs-are-truncated
  (let [pred (p/predictor {:id "t/qa" :signature qa
                           :params {:max-demo-value-chars 10
                                    :demos [{:inputs {:context (apply str (repeat 50 "z"))}
                                             :outputs {:answer "a" :confidence 1.0}}]}})
        content (system-content (:calls (stub-llm {} #(p/run pred {:question "Q"} :lm-config fake-lm))))]
    (is (str/includes? content "context: zzzzzzzzzz…[truncated]"))))

;; ---------------------------------------------------------------------------
;; run: LM resolution
;; ---------------------------------------------------------------------------

(deftest lm-resolution-order
  (let [resolved (atom [])
        _ (p/set-lm-resolver! (fn [req]
                                (swap! resolved conj req)
                                (assoc fake-lm :model (str (or (:lm req) (name (:tier req)))))))
        pred (p/predictor {:id "t/qa" :signature qa :tier :light})
        lm-of (fn [f] (-> (stub-llm {} f) :calls first :lm :model))]
    (testing "call-site lm-config wins over params :lm"
      (p/with-params {"t/qa" {:lm "openai/pinned"}}
        (is (= "fake-model" (lm-of #(p/run pred {:question "Q"} :lm-config fake-lm))))))
    (testing "params :lm is used when the call site names none"
      (p/with-params {"t/qa" {:lm "openai/pinned"}}
        (is (= "openai/pinned" (lm-of #(p/run pred {:question "Q"}))))))
    (testing "params :tier beats the predictor's default tier"
      (p/with-params {"t/qa" {:tier :deep}}
        (is (= "deep" (lm-of #(p/run pred {:question "Q"}))))))
    (testing "predictor default tier is the last resort"
      (is (= "light" (lm-of #(p/run pred {:question "Q"})))))))

;; ---------------------------------------------------------------------------
;; Tracing
;; ---------------------------------------------------------------------------

(deftest trace-captures-calls-including-futures-and-errors
  (let [pred (p/predictor {:id "t/qa" :signature qa :strategy :cot})
        sunk (atom [])]
    (p/set-trace-sink! #(swap! sunk conj %))
    (p/with-trace [t]
      (stub-llm {:reasoning "r" :answer "a" :confidence 1.0}
                #(do (p/run pred {:question "Q1"} :lm-config fake-lm)
                     @(future (p/run pred {:question "Q2"} :lm-config fake-lm))))
      (is (= 2 (count @t)))
      (is (= [{:question "Q1"} {:question "Q2"}] (mapv :inputs @t)))
      (is (= "r" (:reasoning (first @t))))
      (is (= {:answer "a" :confidence 1.0} (:outputs (first @t))))
      (testing "a failing call is traced and rethrown"
        (with-redefs [llm/chat-completion (fn [& _] (throw (ex-info "boom" {})))]
          (is (thrown? clojure.lang.ExceptionInfo (p/run pred {:question "Q3"} :lm-config fake-lm))))
        (is (= "boom" (:error (last @t))))))
    (is (= 3 (count @sunk)) "the sink sees every call")))

(deftest trace-context-rides-every-entry
  (let [pred (p/predictor {:id "t/qa" :signature qa})]
    (p/with-trace [t]
      (stub-llm {:answer "a" :confidence 1.0}
                #(p/with-trace-context {:node-id :n1}
                   (p/with-trace-context {:agent "A"}
                     (p/run pred {:question "Q"} :lm-config fake-lm))
                   (p/run pred {:question "Q"} :lm-config fake-lm)))
      (is (= [{:node-id :n1 :agent "A"} {:node-id :n1}] (mapv :context @t))
          "contexts nest by merge and unwind with the extent"))))

(deftest cot-strategy-uses-augmented-schema
  (let [pred (p/predictor {:id "t/qa" :signature qa :strategy :cot})
        {:keys [calls result]} (stub-llm {:reasoning "because" :answer "a" :confidence 1.0}
                                         #(p/run pred {:question "Q"} :lm-config fake-lm))]
    (is (str/includes? (system-content calls) "`reasoning`"))
    (is (= "because" (:reasoning result)))
    (is (= (cot/augment-schema-with-reasoning (:output-json-schema qa))
           (cot/augment-schema-with-reasoning (:output-json-schema (:signature pred)))))))
