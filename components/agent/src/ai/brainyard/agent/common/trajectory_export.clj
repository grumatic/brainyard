;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.common.trajectory-export
  "Trajectory export — serialize captured session trajectories into a
   training-ready format (R5b — docs/design/hermes-comparison.md).

   Brainyard already records high-fidelity per-turn traces to
   `<project>/.brainyard/sessions/<id>/trajectory.edn` (see
   `ai.brainyard.agent.common.trajectory`). This namespace productizes that
   data: `trajectory$export` reads the records back and writes them as either

   - **openai-jsonl** (default) — one JSON object per turn, an OpenAI
     tool-calling `{\"messages\": [...]}` example. Each acting iteration becomes
     an assistant message with `tool_calls` plus the matching `tool` result
     message(s); code-eval iterations map to a `code_eval` function call. The
     turn's question opens it and its answer closes it. Suitable for SFT of
     tool-calling models.
   - **edn** — the raw turn records, one EDN map per line (lossless).

   A redaction pass (default ON) scrubs secret-like tokens (api keys, bearer
   tokens) from all string content before writing, because trajectories can
   capture credentials."
  (:require [ai.brainyard.agent.common.trajectory :as traj]
            [ai.brainyard.agent.core.config :as config]
            [ai.brainyard.agent.core.protocol :as proto]
            [ai.brainyard.agent.core.tool :refer [defcommand]]
            [ai.brainyard.mulog.interface :as mulog]
            [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.walk :as walk])
  (:import [java.io File]))

;; ============================================================================
;; Redaction
;; ============================================================================

(def ^:private secret-patterns
  "Regexes for secret-like tokens scrubbed from exported content."
  [#"gck_[A-Za-z0-9]{8,}"
   #"sk-[A-Za-z0-9]{16,}"
   #"AKIA[0-9A-Z]{16}"
   #"(?i)bearer\s+[A-Za-z0-9._\-]{12,}"
   #"(?i)(api[-_]?key|secret|token|password)([\"':=\s]+)[A-Za-z0-9._\-/+]{12,}"])

(defn redact-str
  "Replace secret-like substrings in `s` with [REDACTED]. Non-strings pass
   through unchanged."
  [s]
  (if (string? s)
    (reduce (fn [acc re] (str/replace acc re "[REDACTED]")) s secret-patterns)
    s))

(defn redact-example
  "Walk an example map and redact every string leaf."
  [example]
  (walk/postwalk redact-str example))

;; ============================================================================
;; OpenAI tool-calling serializer
;; ============================================================================

(defn- tool-call-id [turn n i]
  (str "call_" turn "_" n "_" i))

(defn- args->json [args]
  (try (json/write-str (or args {}))
       (catch Exception _ (json/write-str {}))))

(defn- non-blank [s] (when (and (string? s) (not (str/blank? s))) s))

(defn iteration->messages
  "Project one trajectory iteration into OpenAI messages: an assistant message
   (with tool_calls for tool/code channels, content carrying the thought) plus
   one `tool` message per call. A reasoning-only iteration yields a single
   assistant message with the thought. Returns [] for empty iterations."
  [turn {:keys [n channel thought tools code result output error]}]
  (let [thought' (non-blank thought)]
    (cond
      ;; Tool channel — one tool_call per invoked tool.
      (seq tools)
      (let [calls (map-indexed
                   (fn [i {:keys [name args]}]
                     {:id (tool-call-id turn n i)
                      :type "function"
                      :function {:name (str name) :arguments (args->json args)}})
                   tools)
            asst (cond-> {:role "assistant" :tool_calls (vec calls)}
                   thought' (assoc :content thought'))
            tool-msgs (map-indexed
                       (fn [i {:keys [result]}]
                         {:role "tool"
                          :tool_call_id (tool-call-id turn n i)
                          :content (str (or result ""))})
                       tools)]
        (into [asst] tool-msgs))

      ;; Code channel — model as a single `code_eval` function call.
      (seq code)
      (let [cid (tool-call-id turn n 0)
            asst (cond-> {:role "assistant"
                          :tool_calls [{:id cid :type "function"
                                        :function {:name "code_eval"
                                                   :arguments (json/write-str {:code (str/join "\n" code)})}}]}
                   thought' (assoc :content thought'))
            tool-content (str/join "\n" (concat (or output []) (or result []) (or error [])))]
        [asst {:role "tool" :tool_call_id cid :content tool-content}])

      ;; Reasoning-only iteration — keep the thought as an assistant turn.
      thought'
      [{:role "assistant" :content thought'}]

      :else [])))

(defn record->example
  "Turn record → one OpenAI tool-calling example `{:messages [...]}`, or nil
   when the turn has neither a question nor any content worth exporting."
  [{:keys [turn question answer iterations] :as _record}]
  (let [user-msg (when (non-blank question) [{:role "user" :content question}])
        iter-msgs (mapcat #(iteration->messages turn %) iterations)
        answer-msg (when (non-blank answer) [{:role "assistant" :content answer}])
        messages (vec (concat user-msg iter-msgs answer-msg))]
    (when (seq messages) {:messages messages})))

(defn records->openai
  "Vector of turn records → vector of OpenAI examples (one per non-empty turn)."
  [records]
  (->> records (keep record->example) vec))

;; ============================================================================
;; Session discovery
;; ============================================================================

(defn list-session-ids
  "Session ids under the project-scoped sessions root that have a trajectory."
  []
  (let [^File root (io/file (str (config/sessions-root)))]
    (if-not (.isDirectory root)
      []
      (->> (.listFiles root)
           (filter #(.isDirectory ^File %))
           (map #(.getName ^File %))
           (filter #(.exists (traj/session-trajectory-file %)))
           vec))))

(defn- resolve-session-ids [{:keys [session-id all]}]
  (cond
    all                    (list-session-ids)
    (non-blank session-id) [session-id]
    :else                  (when-let [sid (some-> proto/*current-agent* proto/session-id)]
                             [(str sid)])))

;; ============================================================================
;; Serialize + write
;; ============================================================================

(defn- serialize-lines
  "Build the output lines (strings, no trailing newline) for the given records
   and format. `redact?` scrubs secrets from each example."
  [records format redact?]
  (case format
    "edn"
    (mapv (fn [r] (pr-str (if redact? (redact-example r) r))) records)

    ;; default: openai-jsonl
    (->> (records->openai records)
         (map #(if redact? (redact-example %) %))
         (mapv json/write-str))))

(defn- default-out-path [format]
  (let [ext (if (= format "edn") "edn" "jsonl")]
    (str (io/file (str (config/project-dir)) ".brainyard" "exports"
                  (str "trajectories-" (System/currentTimeMillis) "." ext)))))

(defcommand trajectory$export
  "Export captured session trajectories to a training-ready file (OpenAI tool-calling JSONL by default)."
  (fn [& {:keys [format out redact] :as opts}]
    (let [fmt    (or (non-blank format) "openai-jsonl")
          redact? (if (some? redact) (boolean redact) true)
          sids   (resolve-session-ids opts)]
      (cond
        (not (#{"openai-jsonl" "edn"} fmt))
        {:error (str "Unknown :format '" fmt "'. Valid: openai-jsonl | edn")}

        (empty? sids)
        {:error "No session to export — pass :session-id, :all true, or run inside a session."}

        :else
        (try
          (let [records (vec (mapcat #(or (traj/read-trajectories %) []) sids))]
            (if (empty? records)
              {:error (str "No trajectory records found for " (count sids) " session(s).")}
              (let [lines (serialize-lines records fmt redact?)
                    path  (or (non-blank out) (default-out-path fmt))
                    ^File f (io/file path)]
                (when-let [p (.getParentFile f)] (.mkdirs p))
                (spit f (str (str/join "\n" lines) "\n"))
                (mulog/log ::exported :path path :format fmt :sessions (count sids)
                           :turns (count records) :examples (count lines))
                {:path path :format fmt :sessions (count sids)
                 :turns (count records) :examples (count lines) :redacted redact?})))
          (catch Exception e
            (mulog/warn ::export-failed :exception e)
            {:error (str "trajectory$export failed: " (.getMessage e))})))))
  :input-schema  [:map
                  [:session-id {:optional true} [:string {:desc "Session id to export (default: current session)"}]]
                  [:all        {:optional true} [:boolean {:desc "Export every session in the project"}]]
                  [:format     {:optional true} [:string {:desc "openai-jsonl (default) | edn (lossless raw records)"}]]
                  [:out        {:optional true} [:string {:desc "Output file path (default .brainyard/exports/trajectories-<ts>.<ext>)"}]]
                  [:redact     {:optional true} [:boolean {:desc "Scrub secret-like tokens from content (default true)"}]]]
  :output-schema [:map
                  [:path     {:optional true} [:string {:desc "Written file path"}]]
                  [:format   {:optional true} [:string {:desc "Format used"}]]
                  [:sessions {:optional true} [:int {:desc "Sessions exported"}]]
                  [:turns    {:optional true} [:int {:desc "Turn records read"}]]
                  [:examples {:optional true} [:int {:desc "Output lines (training examples)"}]]
                  [:redacted {:optional true} [:boolean {:desc "Whether redaction ran"}]]
                  [:error    {:optional true} [:string {:desc "Error message if failed"}]]])

(def export-commands
  "Trajectory export command(s), bound into the common roster."
  [#'trajectory$export])
