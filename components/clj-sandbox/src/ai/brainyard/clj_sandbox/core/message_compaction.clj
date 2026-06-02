;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.clj-sandbox.core.message-compaction
  "Mid-turn message compaction for the RLM completion loop.

   As iterations accumulate (2 messages per iteration), the LLM prompt can
   exceed context limits. This module compresses old iteration messages into
   a structured summary while keeping recent iterations verbatim."
  (:require [clojure.string :as str]
            [ai.brainyard.clj-sandbox.core.truncation :as trunc]))

(defn estimate-message-tokens
  "Estimate token count for a messages vector using char/4 heuristic."
  [messages]
  (quot (reduce + 0 (map #(count (str (:content %))) messages)) 4))

(defn needs-compaction?
  "Check if messages exceed the compaction threshold.
   threshold-ratio: fraction of max-context-tokens to trigger at (default 0.6)."
  [messages max-context-tokens & {:keys [threshold-ratio] :or {threshold-ratio 0.6}}]
  (> (estimate-message-tokens messages)
     (long (* max-context-tokens threshold-ratio))))

(defn- extract-def-bindings
  "Extract (def ...) patterns from code content."
  [code-str]
  (when code-str
    (let [def-re #"\(def\s+(\S+)\s"]
      (->> (re-seq def-re code-str)
           (mapv second)))))

(defn- extract-call-tools
  "Extract (call-tool ...) invocations from code content."
  [code-str]
  (when code-str
    (let [call-re #"\(call-tool\s+\"([^\"]+)\""]
      (->> (re-seq call-re code-str)
           (mapv second)))))

(defn- extract-errors
  "Extract error lines from a feedback message."
  [content]
  (when content
    (->> (str/split-lines content)
         (filter #(or (str/starts-with? % "Error:")
                      (str/includes? % "Exception")))
         (mapv str/trim)
         seq)))

(defn- summarize-messages
  "Build a structured summary from messages to compress.
   Extracts def bindings, tool calls, errors, and key results."
  [messages max-summary-chars]
  (let [all-defs (atom [])
        all-tools (atom [])
        all-errors (atom [])
        all-results (atom [])
        iteration-count (atom 0)]
    ;; Scan pairs of (assistant, user-feedback) messages
    (doseq [msg messages]
      (let [content (or (:content msg) "")]
        (if (= "assistant" (:role msg))
          (do
            (swap! iteration-count inc)
            ;; Extract code patterns from assistant messages
            (let [code-blocks (re-seq #"```(?:clojure|clj)\s*\n([\s\S]*?)```" content)]
              (doseq [[_ code] code-blocks]
                (swap! all-defs into (extract-def-bindings code))
                (swap! all-tools into (extract-call-tools code)))))
          ;; User feedback messages
          (do
            (when-let [errs (extract-errors content)]
              (swap! all-errors into errs))
            ;; Extract eval results (=> lines)
            (let [result-lines (->> (str/split-lines content)
                                    (filter #(str/starts-with? % "=> "))
                                    (mapv #(subs % 3)))]
              (swap! all-results into result-lines))))))

    ;; Build summary
    (let [parts (cond-> [(str "[Previous iterations summary (" @iteration-count " iterations)]")]
                  (seq @all-defs)
                  (conj (str "Defined vars: " (str/join ", " (distinct @all-defs))))

                  (seq @all-tools)
                  (conj (str "Tool calls: " (str/join ", " (distinct @all-tools))))

                  (seq @all-errors)
                  (conj (str "Errors encountered: " (str/join "; " (take 3 @all-errors))))

                  (seq @all-results)
                  (conj (str "Key results: "
                             (str/join "; " (take 3 (mapv #(trunc/truncate-to-file % 100 "compacted-result"
                                                                                   :label "result")
                                                          @all-results))))))
          summary (str/join "\n" parts)]
      (trunc/truncate-to-file summary max-summary-chars "compaction-summary"
                              :label "compaction summary"))))

(defn compact-messages
  "Compress old iteration messages into a summary.

   Strategy:
   1. Always keep: system msg (index 0), initial user query (index 1)
   2. Keep the last `keep-recent` iteration pairs verbatim
   3. Compress iterations 1...(N-keep-recent) into a single summary user message

   Parameters:
     messages          - Current messages vector
     opts              - {:keep-recent 3
                          :max-summary-chars 3000}

   Returns: Compacted messages vector, or original if no compaction needed."
  [messages & {:keys [keep-recent max-summary-chars]
               :or {keep-recent 3 max-summary-chars 3000}}]
  (let [n (count messages)]
    ;; Need at least: 2 header msgs + 2*keep-recent + 2 (one pair to compress)
    (if (< n (+ 2 (* 2 keep-recent) 2))
      messages  ;; Not enough messages to compact
      (let [header-msgs (subvec (vec messages) 0 2)  ;; system + initial user
            iteration-msgs (subvec (vec messages) 2)  ;; all iteration pairs
            ;; Keep last keep-recent*2 messages (pairs of assistant+user)
            n-iter (count iteration-msgs)
            keep-count (* 2 keep-recent)
            compress-msgs (subvec iteration-msgs 0 (- n-iter keep-count))
            recent-msgs (subvec iteration-msgs (- n-iter keep-count))
            ;; Build summary
            summary (summarize-messages compress-msgs max-summary-chars)
            summary-msg {:role "user"
                         :content (str "[Context from prior iterations]\n" summary)}]
        (vec (concat header-msgs [summary-msg] recent-msgs))))))
