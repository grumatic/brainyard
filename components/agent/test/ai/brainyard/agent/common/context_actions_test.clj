;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.common.context-actions-test
  (:require [ai.brainyard.agent.common.context-actions :as ctx-actions]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]])
  (:import (java.time Instant)))

(def ^:private sample-l2-episode
  {:_layer :l2
   :kind :episode
   :role "user"
   :content "Asked about S3 bucket policies"
   :created-at (Instant/parse "2026-05-14T10:00:00Z")})

(def ^:private sample-l2-assistant
  {:_layer :l2
   :kind :episode
   :role "assistant"
   :content "Provided an IAM policy template"
   :created-at (Instant/parse "2026-05-14T10:05:00Z")})

(def ^:private sample-l3-fact
  {:_layer :l3
   :kind :fact
   :content "The user prefers terraform over cloudformation."
   :tags []})

(def ^:private sample-l3-preference
  {:_layer :l3
   :kind :preference
   :content "Likes terse markdown answers"
   :tags ["style"]})

(def ^:private sample-l1-overlay
  {:_layer :l1
   :kind :system-context
   :content "Be terse; rich-text markdown only."
   :tags []})

(deftest format-recalled-memory-empty-test
  (testing "empty hits → empty string (caller treats as missing)"
    (is (= "" (ctx-actions/format-recalled-memory [])))
    (is (= "" (ctx-actions/format-recalled-memory nil)))))

(deftest format-recalled-memory-groups-by-layer
  (testing "layer order: Episodes → Facts → System overlays"
    (let [out (ctx-actions/format-recalled-memory
               [sample-l3-fact sample-l1-overlay sample-l2-episode])]
      (is (str/starts-with? out "## Recalled Memory"))
      (is (str/includes? out "### Episodes (L2"))
      (is (str/includes? out "### Facts (L3"))
      (is (str/includes? out "### System overlays (L1"))
      ;; Episodes block appears before Facts block, Facts before L1.
      (let [ep-idx (str/index-of out "### Episodes")
            fa-idx (str/index-of out "### Facts")
            l1-idx (str/index-of out "### System overlays")]
        (is (< ep-idx fa-idx))
        (is (< fa-idx l1-idx))))))

(deftest format-recalled-memory-omits-empty-sections
  (testing "missing layer → its heading is omitted"
    (let [out-l2-only (ctx-actions/format-recalled-memory
                       [sample-l2-episode sample-l2-assistant])
          out-l3-only (ctx-actions/format-recalled-memory
                       [sample-l3-fact sample-l3-preference])
          out-l1-only (ctx-actions/format-recalled-memory
                       [sample-l1-overlay])]
      (is (str/includes? out-l2-only "### Episodes"))
      (is (not (str/includes? out-l2-only "### Facts")))
      (is (not (str/includes? out-l2-only "### System overlays")))

      (is (str/includes? out-l3-only "### Facts"))
      (is (not (str/includes? out-l3-only "### Episodes")))

      (is (str/includes? out-l1-only "### System overlays"))
      (is (not (str/includes? out-l1-only "### Episodes"))))))

(deftest format-recalled-memory-l2-shape
  (testing "L2 hit renders as `- [date role] content`"
    (let [out (ctx-actions/format-recalled-memory [sample-l2-episode])]
      (is (str/includes? out "[2026-05-14 user] Asked about S3"))))

  (testing "L2 hit without created-at still renders cleanly"
    (let [hit (dissoc sample-l2-episode :created-at)
          out (ctx-actions/format-recalled-memory [hit])]
      (is (str/includes? out "[user] Asked about S3")))))

(deftest format-recalled-memory-l3-shape
  (testing "L3 fact omits the (fact) prefix; preference shows it"
    (let [out (ctx-actions/format-recalled-memory
               [sample-l3-fact sample-l3-preference])]
      (is (str/includes? out "- The user prefers terraform"))
      (is (str/includes? out "- (preference)"))
      (is (str/includes? out "[style]")))))

(deftest format-recalled-memory-l1-shape
  (testing "L1 overlay renders as `- (<kind> overlay) <content>`"
    (let [out (ctx-actions/format-recalled-memory [sample-l1-overlay])]
      (is (str/includes? out "- (system-context overlay) Be terse")))))

(deftest format-recalled-memory-unknown-layer-test
  (testing "unrecognised :_layer falls under ### Other (not dropped)"
    (let [out (ctx-actions/format-recalled-memory
               [{:_layer :unknown
                 :kind :something
                 :content "mystery content"}])]
      (is (str/includes? out "### Other"))
      (is (str/includes? out "mystery content")))))

(deftest format-recalled-memory-content-snipping-test
  (testing "long content gets snipped at the default *snip-chars* (600)"
    (let [long-content (apply str (repeat 1000 "X"))
          out (ctx-actions/format-recalled-memory
               [(assoc sample-l3-fact :content long-content)])]
      (is (str/includes? out "…"))
      (is (not (str/includes? out (apply str (repeat 800 "X")))) "cut below 800"))))

(deftest format-recalled-memory-snip-chars-configurable-test
  (testing "*snip-chars* controls the per-hit cut"
    (let [content (apply str (repeat 1000 "X"))
          tight   (binding [ctx-actions/*snip-chars* 100]
                    (ctx-actions/format-recalled-memory
                     [(assoc sample-l3-fact :content content)]))
          wide    (binding [ctx-actions/*snip-chars* 900]
                    (ctx-actions/format-recalled-memory
                     [(assoc sample-l3-fact :content content)]))]
      (is (< (count tight) (count wide)) "a smaller snip yields a shorter render")
      (is (str/includes? tight "…"))
      (is (str/includes? wide "…")))))

;; ============================================================================
;; M8a — Mid-turn re-recall
;; ============================================================================

(def ^:private extract-terms @#'ctx-actions/extract-entity-terms)
(def ^:private novel-terms-fn @#'ctx-actions/novel-terms)
(def ^:private merge-hits-fn @#'ctx-actions/merge-hits)

(deftest extract-entity-terms-test
  (testing "CamelCase / TitleCase words ≥4 chars are extracted"
    (let [terms (extract-terms "Found UserService and HttpClient in DataPipeline")]
      (is (some #{"UserService"} terms))
      (is (some #{"HttpClient"} terms))
      (is (some #{"DataPipeline"} terms))))

  (testing "Backtick-quoted identifiers (≥4 chars) are extracted"
    (let [terms (extract-terms "See `aws$describe-instances` and `s3.bucket-1`")]
      (is (some #{"aws$describe-instances"} terms))
      (is (some #{"s3.bucket-1"} terms))))

  (testing "Short words / common words are not extracted"
    (let [terms (extract-terms "The cat sat on the mat")]
      (is (not (some #{"The"} terms)))))

  (testing "non-string result is pr-str'd first"
    (let [terms (extract-terms {:status "OK" :Service "UserService"})]
      (is (some #{"UserService"} terms))))

  (testing "nil and empty return empty"
    (is (empty? (extract-terms nil)))
    (is (empty? (extract-terms "")))))

(deftest novel-terms-test
  (testing "drops terms already in recalled-text (substring match)"
    (let [terms ["UserService" "HttpClient" "DataPipeline"]
          recalled "## Recalled Memory\n### Episodes\n- UserService is the auth gateway"]
      (is (= ["HttpClient" "DataPipeline"]
             (novel-terms-fn terms recalled)))))

  (testing "blank / nil terms are filtered"
    (is (= ["X"] (novel-terms-fn ["X" "" nil] "")))))

(deftest merge-hits-dedup-by-content-test
  (testing "merge keeps existing first, drops dupes by :content, respects limit"
    (let [existing [{:content "a"} {:content "b"}]
          new-hits [{:content "b"} {:content "c"} {:content "d"}]
          merged (merge-hits-fn existing new-hits 5)]
      (is (= ["a" "b" "c" "d"] (map :content merged))))

    (let [existing [{:content "a"}]
          new-hits [{:content "b"} {:content "c"}]
          merged (merge-hits-fn existing new-hits 2)]
      (is (= 2 (count merged)))
      (is (= ["a" "b"] (map :content merged))))))

(defrecord StubMemAgent [agent-id-kw !state mm]
  ai.brainyard.agent.core.protocol/IAgent
  (agent-id [_] agent-id-kw)
  (agent-name [_] nil)
  (agent-description [_] nil)
  (user-id [_] "u")
  (session-id [_] "s")
  (defagent-type [_] :test)
  (process [_ _ _] nil)
  (get-tools [_] nil)
  (get-state [_] nil)
  ai.brainyard.agent.core.protocol/IAgentMemoryAccess
  (get-memory-manager [_] mm))

(defn- stub-mem-agent
  [st-memory-map mm]
  (->StubMemAgent
   :test/mid-turn
   (atom {:behavior-tree {:context {:st-memory (atom st-memory-map)}}})
   mm))

(deftest re-recall-after-tool-use-disabled-by-default
  (testing "flag off → no recall, no st-memory mutation"
    (let [recall-calls (atom 0)
          st (atom {:recalled-memory ""})
          agent (->StubMemAgent
                 :test/a
                 (atom {:behavior-tree  {:context {:st-memory st}}
                        :st-memory-init (atom {:config {}})})
                 :stub-mm)]
      (with-redefs [ai.brainyard.agent.core.memory/recall
                    (fn [& _] (swap! recall-calls inc) [])]
        (ctx-actions/re-recall-after-tool-use
         {:agent agent
          :tool-name "test$foo"
          :result "long enough Result with HttpClient mentioned here for sure"}))
      (is (zero? @recall-calls))
      (is (= "" (:recalled-memory @st))))))

(deftest re-recall-after-tool-use-fires-on-novel-terms
  (testing "flag on + novel entity terms → recall fires + :recalled-memory updates"
    (let [st (atom {:recalled-memory ""
                    :recalled-memory-hits []
                    :turn-id 1
                    :total-turns 1})
          agent (->StubMemAgent
                 :test/b
                 (atom {:behavior-tree   {:context {:st-memory st}}
                        :st-memory-init  (atom {:config {:enable-mid-turn-recall true}})})
                 :stub-mm)
          fake-hit {:_layer :l3
                    :kind :fact
                    :content "HttpClient routes through the gateway"
                    :tags []}]
      (with-redefs [ai.brainyard.agent.core.memory/recall
                    (fn [_mm & _kw-args] [fake-hit])]
        (ctx-actions/re-recall-after-tool-use
         {:agent agent
          :tool-name "test$inspect"
          :result "The HttpClient component handles all outbound requests."}))
      (is (= 1 (count (:recalled-memory-hits @st))))
      (is (str/includes? (:recalled-memory @st) "HttpClient routes"))
      (is (= 1 (:mid-turn-recall-fired @st))))))

(deftest re-recall-after-tool-use-skips-tiny-result
  (testing "tiny result (< 50 chars) → no recall fired"
    (let [recall-calls (atom 0)
          st (atom {:recalled-memory ""})
          agent (->StubMemAgent
                 :test/c
                 (atom {:behavior-tree  {:context {:st-memory st}}
                        :st-memory-init (atom {:config {:enable-mid-turn-recall true}})})
                 :stub-mm)]
      (with-redefs [ai.brainyard.agent.core.memory/recall
                    (fn [& _] (swap! recall-calls inc) [])]
        (ctx-actions/re-recall-after-tool-use
         {:agent agent :tool-name "test$x" :result "tiny"}))
      (is (zero? @recall-calls)))))

;; ============================================================================
;; M8b — Cross-turn tool-result cache
;; ============================================================================

(deftest tool-cache-disabled-by-default
  (testing "ttl=0 → no read-entries call, no decision (handler returns nil)"
    (let [read-calls (atom 0)
          st (atom {})
          agent (->StubMemAgent
                 :test/cache-off
                 (atom {:behavior-tree  {:context {:st-memory st}}
                        :st-memory-init (atom {:config {}})})
                 :stub-mm)]
      (with-redefs [ai.brainyard.memory.interface/read-entries
                    (fn [& _] (swap! read-calls inc) [])]
        (let [decision (ctx-actions/tool-cache-lookup-pre
                        {:agent agent
                         :tool-name "aws$describe-instances"
                         :args {:region "us-east-1"}})]
          (is (nil? decision))
          (is (zero? @read-calls)))))))

(deftest tool-cache-hit-returns-replace
  (testing "ttl>0 + matching prior entry → :replace decision with cached result"
    (let [cached-result {:instances [{:id "i-abc"}]}
          st (atom {})
          agent (->StubMemAgent
                 :test/cache-hit
                 (atom {:behavior-tree  {:context {:st-memory st}}
                        :st-memory-init (atom {:config {:tool-cache-ttl 300}})})
                 :stub-mm)
          captured-query (atom nil)]
      (with-redefs [ai.brainyard.memory.interface/read-entries
                    (fn [_mm _layer query _opts]
                      (reset! captured-query query)
                      [{:created-at (System/currentTimeMillis)
                        :data {:tool-name "aws$describe-instances"
                               :args {:region "us-east-1"}
                               :result cached-result}}])]
        (let [decision (ctx-actions/tool-cache-lookup-pre
                        {:agent agent
                         :tool-name "aws$describe-instances"
                         :args {:region "us-east-1"}})]
          (is (= :replace (:result decision)))
          (is (= cached-result (:replacement decision)))
          (is (str/includes? (:reason decision) "tool-cache hit"))
          ;; Query carried the tag filter
          (let [tags (:tags @captured-query)]
            (is (contains? tags "kind:tool-result"))
            (is (contains? tags "tool:aws$describe-instances")))))
      (is (some? @captured-query)))))

(deftest tool-cache-misses-on-different-args
  (testing "Entry exists but args differ → no decision (real call proceeds)"
    (let [st (atom {})
          agent (->StubMemAgent
                 :test/cache-miss
                 (atom {:behavior-tree  {:context {:st-memory st}}
                        :st-memory-init (atom {:config {:tool-cache-ttl 300}})})
                 :stub-mm)]
      (with-redefs [ai.brainyard.memory.interface/read-entries
                    (fn [& _]
                      [{:created-at (System/currentTimeMillis)
                        :data {:tool-name "aws$describe-instances"
                               :args {:region "us-east-1"}
                               :result {:instances []}}}])]
        (let [decision (ctx-actions/tool-cache-lookup-pre
                        {:agent agent
                         :tool-name "aws$describe-instances"
                         :args {:region "us-west-2"}})]
          (is (nil? decision)))))))

(deftest tool-cache-keyword-tool-name-test
  (testing "Tool-name keyword is normalised the same way the parser does"
    (let [st (atom {})
          agent (->StubMemAgent
                 :test/cache-kw
                 (atom {:behavior-tree  {:context {:st-memory st}}
                        :st-memory-init (atom {:config {:tool-cache-ttl 60}})})
                 :stub-mm)
          captured-tags (atom nil)]
      (with-redefs [ai.brainyard.memory.interface/read-entries
                    (fn [_mm _layer query _opts]
                      (reset! captured-tags (:tags query))
                      [])]
        (ctx-actions/tool-cache-lookup-pre
         {:agent agent
          :tool-name :foo$bar
          :args {}})
        (is (contains? @captured-tags "tool:foo$bar"))))))

;; ============================================================================
;; Timeline conversation transform (Q/A dedup vs Previous Turns)
;; ============================================================================

(def ^:private timeline-transform
  @#'ctx-actions/timeline-transform)

(def ^:private fmt-conversation
  (requiring-resolve 'ai.brainyard.agent.core.context.formatters/format-conversation))

(defn- u [s] {:role "user" :content s})
(defn- a [s] {:role "assistant" :content s :agent-id :root :kind :turn-answer})
(defn- sub-a [s] {:role "assistant" :content s :agent-id :explore-agent :kind :turn-answer})
(defn- dispatch [s] {:role "assistant" :content s :agent-id :root :kind :dispatch})

(deftest timeline-collapses-old-units-keeps-recent-verbatim-test
  (testing "older completed Q/A pairs → merged turn refs; last K verbatim;
            sub-agent + system messages stay in place"
    (let [msgs [(u "q1") (a "a1")
                (u "q2") (dispatch "explore this") (sub-a "found it") (a "a2")
                {:role "system" :content "[wakeup] task done"}
                (u "q3") (a "a3")
                (u "q4") (a "a4")]
          ;; chain has 4 completed turns; keep last 2 verbatim
          out (timeline-transform msgs :root 4 2)]
      ;; turns 1+2 collapse into ONE merged range ref; the dispatch/
      ;; sub-agent/system messages survive.
      (is (= {:role "turn-ref" :turn 1} (dissoc (first out) :to)))
      (is (= 2 (:to (first out))) "turns 1–2 merged into a range ref")
      (is (= 1 (count (filter #(= "turn-ref" (:role %)) out))))
      (is (some #(= "explore this" (:content %)) out) "dispatch kept")
      (is (some #(= "found it" (:content %)) out) "sub-agent answer kept")
      (is (some #(= "system" (:role %)) out) "system note kept")
      ;; last two units verbatim
      (is (some #(= "q3" (:content %)) out))
      (is (some #(= "a4" (:content %)) out))
      (is (not-any? #(= "a1" (:content %)) out))
      (is (not-any? #(= "q2" (:content %)) out)))))

(deftest timeline-legacy-untagged-heuristic-test
  (testing "untagged messages: last self assistant in a segment is the answer;
            earlier self assistants (legacy dispatches) survive as events"
    (let [legacy-a (fn [s] {:role "assistant" :content s :agent-id :root})
          msgs [(u "q1") (legacy-a "dispatch-ish") (legacy-a "a1")
                (u "q2") (legacy-a "a2")
                (u "q3") (legacy-a "a3")]
          out (timeline-transform msgs :root 3 2)]
      ;; only turn 1 converts (3 units - keep 2)
      (is (= {:role "turn-ref" :turn 1} (first out)))
      (is (some #(= "dispatch-ish" (:content %)) out)
          "the earlier self assistant is treated as an event, not the answer")
      (is (not-any? #(= "a1" (:content %)) out)))))

(deftest timeline-guards-test
  (testing "chain shorter than window units (deep compaction) → untouched"
    (let [msgs [(u "q1") (a "a1") (u "q2") (a "a2")]
          out (timeline-transform msgs :root 1 0)]
      (is (= msgs out))))
  (testing "fewer units than keep-verbatim → untouched"
    (let [msgs [(u "q1") (a "a1")]]
      (is (= msgs (timeline-transform msgs :root 5 2)))))
  (testing "window opening mid-segment: answer without its question still refs"
    (let [msgs [(a "a-cut") (u "q2") (a "a2")]
          out (timeline-transform msgs :root 5 1)]
      (is (= {:role "turn-ref" :turn 4} (first out))
          "tail-aligned: 2 units, chain 5 → first unit is turn 4"))))

(deftest timeline-ref-rendering-test
  (testing "format-conversation renders single and ranged refs"
    (let [out (fmt-conversation [{:role "turn-ref" :turn 3}
                                 {:role "turn-ref" :turn 5 :to 7}
                                 (u "hello")])]
      (is (str/includes? out "[Turn 3]"))
      (is (str/includes? out "[Turns 5–7]"))
      (is (str/includes? out "see Previous Turns"))
      (is (str/includes? out "**user**: hello")))))
