;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.tui.format-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [ai.brainyard.agent.tui.format :as fmt]))

(defn- strip-ansi [s]
  (str/replace (or s "") #"\[[;\d]*[A-Za-z]" ""))

(def ^:private sample-history
  ;; Chronological (oldest first), matching collect-all-usage's sort-by :timestamp.
  ;; Call 1 (older):  cache write turn — non-cached input = 6, write = 9,082.
  ;; Call 2 (recent): cache hit on Bedrock Anthropic — non-cached input = 6, read = 10,000.
  [{:provider :bedrock :model "global.anthropic.claude-opus-4-7"
    :latency-ms 3759
    :input-tokens 6 :output-tokens 60
    :cache {:read-tokens 0 :write-tokens 9082}
    :cost {:total-cost 0.0568}
    :turn-id 1 :iteration 1
    :input-token-breakdown {:system-prompt  {:estimated-tokens 9082}
                            :dspy-signature {:estimated-tokens 1775}
                            :user-message   {:estimated-tokens 169}
                            :system-context {:estimated-tokens 0}}}
   {:provider :bedrock :model "global.anthropic.claude-opus-4-7"
    :latency-ms 4184
    :input-tokens 6 :output-tokens 319
    :cache {:read-tokens 10000 :write-tokens 0}
    :cost {:total-cost 0.0048}
    :turn-id 2 :iteration 2
    :input-token-breakdown {:system-prompt  {:estimated-tokens 9171
                                             :parts {:tools                  {:estimated-tokens 4713}
                                                     :project-instructions   {:estimated-tokens 1508}}}
                            :dspy-signature {:estimated-tokens 1775}
                            :user-message   {:estimated-tokens 795
                                             :parts {:input-values   {:estimated-tokens 758}
                                                     :output-reminder {:estimated-tokens 37}}}
                            :system-context {:estimated-tokens 0}}}])

(deftest usage-table-default-test
  (let [out (strip-ansi (fmt/format-usage-table sample-history))]
    (testing "single combined header line is present"
      (is (str/includes? out "Usage (2 calls"))
      (is (str/includes? out "Cache hit rate")))

    (testing "default column header carries In / CacheR / CacheW / Cost / Model"
      (is (str/includes? out "  In  "))
      (is (str/includes? out "CacheR"))
      (is (str/includes? out "CacheW"))
      (is (str/includes? out "Cost"))
      (is (str/includes? out "Model")))

    (testing "default mode hides per-call breakdown columns"
      (is (not (str/includes? out "System  "))
          "no 'System' header column without --breakdown")
      (is (not (str/includes? out "UserMsg"))
          "no 'UserMsg' header column without --breakdown")
      (is (not (str/includes? out "TotalIn"))
          "no 'TotalIn' header column without --breakdown")
      (is (not (str/includes? out "System Prompt Parts"))
          "no Prompt Parts section without --breakdown"))

    (testing "cache columns render as token counts, not percentages"
      (is (str/includes? out "10,000") "cache-read tokens shown verbatim")
      (is (str/includes? out "9,082")  "cache-write tokens shown verbatim")
      (is (not (str/includes? out "93%")) "no percent rendering anywhere")
      (is (not (str/includes? out "write")) "no 'write' literal label"))

    (testing "rows render in chronological order (oldest = #1, latest = last)"
      (let [lines  (str/split-lines out)
            row-1  (first (filter #(re-find #"^\s+1\s" %) lines))
            row-2  (first (filter #(re-find #"^\s+2\s" %) lines))]
        (is (str/includes? row-1 "9,082")  "row #1 = older write turn")
        (is (str/includes? row-2 "10,000") "row #2 = latest cache-hit turn")))

    (testing "Turn/Iter columns surface per-call labels"
      (is (str/includes? out "Turn"))
      (is (str/includes? out "Iter")))))

(deftest usage-table-breakdown-test
  (let [out (strip-ansi (fmt/format-usage-table sample-history {:breakdown? true}))]
    (testing "--breakdown appends prompt-attribution columns"
      (is (str/includes? out "System") "System header column present")
      (is (str/includes? out "UserMsg"))
      (is (str/includes? out "TotalIn")))

    (testing "SysCtx column is hidden when every row is zero"
      (is (not (str/includes? out "SysCtx"))))

    (testing "--breakdown renders System / User Prompt Parts sections"
      (is (str/includes? out "System Prompt Parts (latest):"))
      (is (str/includes? out "User Message Parts (latest):"))
      (is (str/includes? out "tools"))
      (is (str/includes? out "input-values")))

    (testing "--breakdown computes growth across user-message tokens"
      (is (str/includes? out "Growth:"))
      (is (str/includes? out "169 → 795")))

    (testing "default cache + cost columns survive --breakdown"
      (is (str/includes? out "CacheR"))
      (is (str/includes? out "CacheW"))
      (is (str/includes? out "Cost")))))

(deftest usage-table-empty-history-returns-nil
  (is (nil? (fmt/format-usage-table [])))
  (is (nil? (fmt/format-usage-table nil))))

(deftest usage-table-breakdown-without-attribution-data
  (let [out (strip-ansi
             (fmt/format-usage-table
              [{:provider :openai :model "gpt-4o-mini"
                :latency-ms 800 :input-tokens 100 :output-tokens 25
                :cache {:read-tokens 0 :write-tokens 0}
                :cost {:total-cost 0.001}}]
              {:breakdown? true}))]
    (testing "rendering still works when no :input-token-breakdown is present"
      (is (str/includes? out "Usage (1 calls"))
      ;; Without attribution data, the breakdown columns are suppressed.
      (is (not (str/includes? out "TotalIn"))))))

(deftest usage-table-model-name-not-truncated
  (let [long-model "global.anthropic.claude-haiku-4-5-20251001-v1:0"
        out (strip-ansi
             (fmt/format-usage-table
              [{:provider :bedrock :model long-model
                :latency-ms 1200 :input-tokens 6 :output-tokens 42
                :cache {:read-tokens 0 :write-tokens 0}
                :cost {:total-cost 0.0001}
                :turn-id 1 :iteration 1}]))]
    (testing "Model column survives in full (no 28-char truncation)"
      (is (str/includes? out (str "bedrock:" long-model))
          "full model id (with provider prefix) must appear verbatim"))
    (testing "Model column is rendered after the numeric block"
      (let [header (first (filter #(str/includes? % "Cost") (str/split-lines out)))]
        (is (re-find #"Cost\s+Model\s*$" header)
            "Model is the last header, right-aligned after Cost")))))

(deftest usage-table-syx-ctx-shown-when-populated
  (let [out (strip-ansi
             (fmt/format-usage-table
              [{:provider :bedrock :model "claude-haiku"
                :latency-ms 800 :input-tokens 6 :output-tokens 25
                :cache {:read-tokens 0 :write-tokens 0}
                :cost {:total-cost 0.001}
                :turn-id 1 :iteration 1
                :input-token-breakdown
                {:system-prompt  {:estimated-tokens 100}
                 :dspy-signature {:estimated-tokens 50}
                 :user-message   {:estimated-tokens 30}
                 :system-context {:estimated-tokens 200}}}]
              {:breakdown? true}))]
    (testing "SysCtx column appears when at least one row has a non-zero value"
      (is (str/includes? out "SysCtx"))
      (is (str/includes? out "200")))))

;; ============================================================================
;; Per-agent grouping (multi-agent / sub-tracker rendering)
;; ============================================================================

(def ^:private multi-agent-history
  ;; Three calls: two from main-agent, one interleaved from a sub-tracker.
  ;; Chronological by :timestamp. Note that the sub-agent's call lands between
  ;; main-agent iters 1 and 2 (mimicking a sub-agent invocation mid-turn) —
  ;; which is exactly the ordering that confused readers pre-grouping.
  [{:provider :bedrock :model "claude-opus" :latency-ms 1000
    :input-tokens 6 :output-tokens 100
    :cache {:read-tokens 0 :write-tokens 5000}
    :cost {:total-cost 0.10}
    :turn-id 1 :iteration 1
    :agent-instance-id :violet-cat-5530}
   {:provider :bedrock :model "claude-opus" :latency-ms 2000
    :input-tokens 6 :output-tokens 200
    :cache {:read-tokens 4000 :write-tokens 0}
    :cost {:total-cost 0.05}
    :turn-id 1 :iteration 1
    :agent-instance-id "explore-agent/maroon-fox-5013"}
   {:provider :bedrock :model "claude-opus" :latency-ms 3000
    :input-tokens 6 :output-tokens 300
    :cache {:read-tokens 5000 :write-tokens 0}
    :cost {:total-cost 0.07}
    :turn-id 1 :iteration 2
    :agent-instance-id :violet-cat-5530}])

(deftest usage-table-grouped-renders-per-agent-sub-tables
  (let [out (strip-ansi (fmt/format-usage-table multi-agent-history))]
    (testing "aggregate Usage header counts all calls across groups"
      (is (str/includes? out "Usage (3 calls")))

    (testing "each agent gets its own group header"
      (is (str/includes? out "── violet-cat-5530 ·")
          "main agent header present")
      (is (str/includes? out "── explore-agent/maroon-fox-5013 ·")
          "sub-agent header present"))

    (testing "main agent (whose first call is oldest) renders before sub-agent"
      (let [main-idx (.indexOf out "violet-cat-5530")
            sub-idx  (.indexOf out "explore-agent/maroon-fox-5013")]
        (is (and (pos? main-idx) (pos? sub-idx)))
        (is (< main-idx sub-idx)
            "first-seen agent renders first regardless of timestamp interleave")))

    (testing "group headers carry per-group call count and cost"
      (is (str/includes? out "2 calls") "main agent has 2 calls")
      (is (str/includes? out "1 calls") "sub-agent has 1 call")
      ;; Main agent total cost: $0.10 + $0.07 = $0.17
      (is (str/includes? out "$0.1700"))
      ;; Sub-agent total cost: $0.0500
      (is (str/includes? out "$0.0500")))

    (testing "per-group `#` column restarts at 1 (each agent has its own row #1)"
      (let [lines (str/split-lines out)
            row-1-count (count (filter #(re-find #"^\s+1\s+\d" %) lines))]
        (is (= 2 row-1-count)
            "two rows numbered #1 — one per group")))

    (testing "per-row Agent column is suppressed (group header carries the label)"
      (let [header (first (filter #(str/includes? % "Cost") (str/split-lines out)))]
        (is (not (str/includes? header "Agent"))
            "no Agent column header when groups are present")
        (is (re-find #"Cost\s+Model\s*$" header)
            "Model still trails Cost as the last column")))

    (testing "cache hit rate aggregates across all groups"
      (is (str/includes? out "Cache hit rate"))
      ;; 2 of 3 calls had cache reads.
      (is (str/includes? out "(2/3 calls)")))))

(deftest usage-table-single-tagged-agent-renders-without-group-header
  (testing "one agent tag everywhere → legacy single-table layout (no `──` header)"
    (let [history [{:provider :bedrock :model "claude-opus" :latency-ms 1000
                    :input-tokens 6 :output-tokens 100
                    :cache {:read-tokens 0 :write-tokens 0}
                    :cost {:total-cost 0.01}
                    :turn-id 1 :iteration 1
                    :agent-instance-id :violet-cat-5530}
                   {:provider :bedrock :model "claude-opus" :latency-ms 1100
                    :input-tokens 6 :output-tokens 110
                    :cache {:read-tokens 0 :write-tokens 0}
                    :cost {:total-cost 0.02}
                    :turn-id 1 :iteration 2
                    :agent-instance-id :violet-cat-5530}]
          out (strip-ansi (fmt/format-usage-table history))]
      (is (not (str/includes? out "── violet-cat-5530"))
          "no group separator when there's only one agent")
      (let [header (first (filter #(str/includes? % "Cost") (str/split-lines out)))]
        (is (str/includes? header "Agent")
            "single-tagged-agent mode preserves the legacy per-row Agent column")))))

(deftest usage-table-grouped-three-agents-realistic-interleaving-test
  ;; Reproduces the exact shape from the bug report in /tmp/c.txt: a main
  ;; agent calls a research sub-agent, which in turn dispatches plan-agent.
  ;; Pre-grouping, all six calls landed in one flat table with iter values
  ;; jumping out of order. Post-grouping each agent gets its own sub-table
  ;; under a `── label · N · Σ · $Σ ──` header, intra-group iters stay
  ;; monotonic, and the aggregate footer still spans every call.
  (let [history
        [{:provider :bedrock :model "claude-opus" :latency-ms 8000
          :input-tokens 6 :output-tokens 500
          :cache {:read-tokens 0 :write-tokens 30000} :cost {:total-cost 0.25}
          :turn-id 1 :iteration 1
          :agent-instance-id :violet-cat-5530}
         {:provider :bedrock :model "claude-opus" :latency-ms 4000
          :input-tokens 6 :output-tokens 200
          :cache {:read-tokens 28000 :write-tokens 0} :cost {:total-cost 0.08}
          :turn-id 1 :iteration 2
          :agent-instance-id :violet-cat-5530}
         {:provider :bedrock :model "claude-opus" :latency-ms 12000
          :input-tokens 6 :output-tokens 800
          :cache {:read-tokens 25000 :write-tokens 0} :cost {:total-cost 0.18}
          :turn-id 1 :iteration 1
          :agent-instance-id "research-agent/turquoise-ant-6100"}
         {:provider :bedrock :model "claude-opus" :latency-ms 5000
          :input-tokens 6 :output-tokens 300
          :cache {:read-tokens 22000 :write-tokens 0} :cost {:total-cost 0.12}
          :turn-id 1 :iteration 2
          :agent-instance-id "research-agent/turquoise-ant-6100"}
         {:provider :bedrock :model "claude-opus" :latency-ms 7000
          :input-tokens 6 :output-tokens 600
          :cache {:read-tokens 20000 :write-tokens 0} :cost {:total-cost 0.15}
          :turn-id 1 :iteration 1
          :agent-instance-id "plan-agent/pink-deer-6726"}
         {:provider :bedrock :model "claude-opus" :latency-ms 3000
          :input-tokens 6 :output-tokens 100
          :cache {:read-tokens 30000 :write-tokens 0} :cost {:total-cost 0.06}
          :turn-id 1 :iteration 3
          :agent-instance-id :violet-cat-5530}]
        out (strip-ansi (fmt/format-usage-table history))]

    (testing "aggregate Usage header counts every call regardless of group"
      (is (str/includes? out "Usage (6 calls")))

    (testing "all three agents get their own group separator"
      (is (str/includes? out "── violet-cat-5530 · 3 calls"))
      (is (str/includes? out "── research-agent/turquoise-ant-6100 · 2 calls"))
      (is (str/includes? out "── plan-agent/pink-deer-6726 · 1 calls")))

    (testing "groups render in first-seen (oldest-call) order, not alphabetical"
      (let [v-idx (.indexOf out "violet-cat-5530")
            r-idx (.indexOf out "research-agent/turquoise-ant-6100")
            p-idx (.indexOf out "plan-agent/pink-deer-6726")]
        (is (and (pos? v-idx) (pos? r-idx) (pos? p-idx)))
        (is (< v-idx r-idx) "main-agent (first call) before research-agent")
        (is (< r-idx p-idx) "research-agent before plan-agent")))

    (testing "per-group # restarts at 1 — three rows numbered #1"
      (let [lines (str/split-lines out)
            row-1s (count (filter #(re-find #"^\s+1\s+\d" %) lines))]
        (is (= 3 row-1s))))

    (testing "per-row Agent column is suppressed; group header carries the label"
      (let [header (first (filter #(str/includes? % "Cost") (str/split-lines out)))]
        (is (not (str/includes? header "Agent")))
        (is (re-find #"Cost\s+Model\s*$" header)
            "Model is still the last column right after Cost")))

    (testing "per-group totals match the sum of each group's per-row costs"
      ;; main:    $0.25 + $0.08 + $0.06 = $0.39
      ;; research: $0.18 + $0.12        = $0.30
      ;; plan:    $0.15                  = $0.15
      (is (str/includes? out "$0.3900"))
      (is (str/includes? out "$0.3000"))
      (is (str/includes? out "$0.1500")))

    (testing "aggregate cache-hit rate spans every group"
      ;; 5 of 6 calls had cache reads (only call #1 was a write turn).
      (is (str/includes? out "Cache hit rate"))
      (is (str/includes? out "(5/6 calls)")))))

(deftest display-width-emoji-vs16-test
  (testing "a base char followed by U+FE0F renders 2 columns (emoji presentation)"
    (is (= 2 (fmt/display-width "⚠️")) "⚠️ (U+26A0 + VS16) is 2 cols")
    (is (= 2 (fmt/display-width "✅"))       "✅ (U+2705) is 2 cols")
    (is (= 1 (fmt/display-width "→"))       "→ (U+2192, no VS16) stays 1 col")
    (is (= 0 (fmt/display-width "‍"))       "ZWJ is 0 cols")
    (is (= 16 (fmt/display-width "⚠️ Top two risks")) "prefix width counted correctly")))

(deftest format-answer-box-right-border-aligned-test
  (testing "every box content row has identical display width, incl. emoji-presentation lines"
    (let [box   (fmt/format-answer
                 (str "✅ Single recommended approach\n"
                      "⚠️ Top two risks / open questions\n"
                      "produce → render → print\n"
                      "plain ascii line here")
                 70)
          plain (strip-ansi box)
          rows  (->> (str/split-lines plain)
                     (filter #(str/starts-with? % "│")))]  ;; │…│ content rows
      (is (seq rows))
      (is (= 1 (count (distinct (map fmt/display-width rows))))
          "all │…│ rows must be equal width so the right border aligns"))))

(deftest format-recovery-status-test
  (testing "empty-result shows N/M progress"
    (let [s (strip-ansi (fmt/format-recovery-status :empty-result 2 5))]
      (is (str/includes? s "empty response"))
      (is (str/includes? s "(2/5)"))))
  (testing "malformed-output shows N/M progress"
    (let [s (strip-ansi (fmt/format-recovery-status :malformed-output 1 3))]
      (is (str/includes? s "Malformed"))
      (is (str/includes? s "(1/3)"))))
  (testing "no-action shows N/M progress"
    (let [s (strip-ansi (fmt/format-recovery-status :no-action 2 3))]
      (is (str/includes? s "nudging the model"))
      (is (str/includes? s "(2/3)"))))
  (testing "nil max degrades gracefully: no '/', count only past the first"
    (let [s1 (strip-ansi (fmt/format-recovery-status :no-action 1 nil))
          s2 (strip-ansi (fmt/format-recovery-status :no-action 2 nil))]
      (is (not (str/includes? s1 "/")) "single occurrence shows no counter")
      (is (str/includes? s2 "(x2)") "repeat shows an occurrence count")))
  (testing "unknown kind degrades to a generic notice rather than throwing"
    (is (str/includes? (strip-ansi (fmt/format-recovery-status :weird 1 nil))
                       "Recovering"))))

(deftest format-answer-expands-tabs-keeps-box-rectangular
  ;; Regression: long-form `git status` indents untracked files with a literal
  ;; TAB. A raw TAB advances to a terminal tab stop but display-width counts it
  ;; as one column, so the right box border desynced (ragged right edge). TABs
  ;; are now expanded to spaces (tab stop 4) before the box is laid out.
  (testing "tab-indented content does not break the answer box"
    (let [answer (str "```\n"
                      "Untracked files:\n"
                      "\t.gitignore\n"
                      "\tpackage.json\n"
                      "```")
          out    (fmt/format-answer answer 80)
          clean  (str/replace out #"\[[0-9;]*m" "")
          box    (->> (str/split-lines clean)
                      (filter #(str/starts-with? % "│")))]
      ;; (1) no raw TAB survives into the rendered output (the desync source)
      (is (not (str/includes? out "\t")) "tabs expanded before layout")
      ;; (2) the leading TAB became 4 spaces (tab stop 4 at column 0)
      (is (str/includes? clean "    .gitignore") "leading tab -> 4 spaces")
      (is (not (str/includes? clean "\t.gitignore")))
      ;; (3) every bordered content row is the same visible width -> rectangular
      (is (= 1 (count (set (map count box))))
          (str "box rows must be equal width; got " (sort (set (map count box))))))))
