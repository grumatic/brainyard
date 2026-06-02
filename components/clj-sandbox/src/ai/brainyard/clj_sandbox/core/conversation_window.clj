;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.clj-sandbox.core.conversation-window
  "Indexed conversation history for selective retrieval.

   Instead of passing raw conversation messages to the sandbox,
   this module indexes them by turn with lightweight summaries.
   The LLM uses (context-index) to scan summaries (~1 line per turn),
   then (get-conversation :from-turn N) to selectively load specific turns."
  (:require [clojure.string :as str]))

(defn- partition-by-turns
  "Group conversation messages into turns (user + assistant pairs).
   Returns vector of vectors, each containing messages for one turn."
  [messages]
  (when (seq messages)
    (loop [msgs messages
           turns []
           current-turn []]
      (if (empty? msgs)
        (if (seq current-turn)
          (conj turns current-turn)
          turns)
        (let [msg (first msgs)]
          (if (and (= "user" (:role msg)) (seq current-turn))
            ;; New user message starts a new turn
            (recur (rest msgs) (conj turns current-turn) [msg])
            ;; Continue current turn
            (recur (rest msgs) turns (conj current-turn msg))))))))

(defn- build-turn-summary
  "Extract a one-line summary from a turn's messages."
  [turn-msgs max-len]
  (let [user-msg (first (filter #(= "user" (:role %)) turn-msgs))
        q (when user-msg
            (let [c (:content user-msg "")]
              (subs c 0 (min max-len (count c)))))]
    (or q "(no user message)")))

(defn build-indexed-conversation
  "Build an indexed conversation structure for the context variable.

   Each turn gets a one-line summary (cheap to scan) plus full content
   (only loaded when the LLM explicitly requests it via get-conversation).

   Parameters:
     conversation  - [{:role :content} ...] full history
     opts          - {:max-summary-len 80}

   Returns:
     {:turn-count N
      :turns [{:index 0
               :summary \"Asked about MCP servers\"
               :messages [{:role :content} ...]}
              ...]}"
  [conversation & {:keys [max-summary-len] :or {max-summary-len 80}}]
  (if (empty? conversation)
    {:turn-count 0 :turns []}
    (let [turns (partition-by-turns conversation)]
      {:turn-count (count turns)
       :turns (vec (map-indexed
                    (fn [i turn-msgs]
                      {:index i
                       :summary (build-turn-summary turn-msgs max-summary-len)
                       :messages (vec turn-msgs)})
                    turns))})))
