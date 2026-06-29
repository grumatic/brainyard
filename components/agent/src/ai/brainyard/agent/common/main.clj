;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.common.main
  "Main-agent quality-of-life helpers — mechanical defcommands that compress
   the routing-log bootstrap / append-log / append-pointer / index-append
   flow described in docs/design/main-agent-design.md §8.

   Each helper is a `defcommand` so it surfaces in the unified tool registry
   and is auto-bound into the SCI sandbox (callable as `(main$session-id)` in
   a clojure fence). Main-agent works without them — the instruction can fall
   back to inline `write-file :append true` — but binding them shrinks the
   prompt because the routing-log mechanics no longer have to be inlined every
   iteration.

   The routing-log shape is hand-rolled NDJSON (one decision per line) plus a
   companion `pointers.md` for human readability. The 21 valid `:shape` keywords
   correspond 1:1 to the §6 decision table (A–U)."
  (:require [ai.brainyard.agent.core.protocol :as proto]
            [ai.brainyard.agent.core.tool :refer [defcommand]]
            [ai.brainyard.mulog.interface :as mulog]
            [clojure.java.io :as io]
            [ai.brainyard.agent.core.config :as config]
            [clojure.string :as str]))

;; ============================================================================
;; Constants
;; ============================================================================

(def ^:private base-rel ".brainyard/agents/main-agent")
(def ^:private index-rel (str base-rel "/INDEX.md"))

(def valid-shapes
  "The 22 routing-decision shapes from docs/design/main-agent-design.md §6
   (decision-table letter labels A–V). Validated by `main$append-log` so
   typos surface immediately rather than poisoning the log."
  #{:direct-answer    ;; A — answer channel; greeting / factual / explain
    :tool-fetch       ;; B — tool channel; one-shot RPC
    :code-compose     ;; C — code channel; composition / scripts
    :explore          ;; D — explore-agent
    :update           ;; E — edit-agent
    :plan-author      ;; F — plan-agent
    :decompose        ;; G — todo-agent
    :execute          ;; H — exec-agent
    :evaluate         ;; I — eval-agent
    :research         ;; J — research-agent
    :workflow         ;; K — workflow-agent
    :rlm              ;; L — rlm-agent
    :memory           ;; M — memory-agent
    :skill-lifecycle  ;; N — skill-agent
    :mcp-lifecycle    ;; O — mcp-agent
    :init             ;; P — init-agent
    :config           ;; Q — config-agent
    :acp              ;; R — acp-agent
    :meta-resume      ;; S — answer channel from routing.log
    :clarify          ;; T — answer channel; ambiguity clarification
    :tool-lifecycle   ;; U — tool-agent (lifecycle sibling of N skill / O mcp)
    :agent-lifecycle}) ;; V — meta-agent (authors user-defined agents)

;; ============================================================================
;; Time formatters
;; ============================================================================

(defn- now-iso [] (str (java.time.Instant/now)))

(defn- now-yyyy-mm-dd-hh-mm
  []
  (-> (subs (now-iso) 0 16) (str/replace "T" " ")))

;; ============================================================================
;; Session-id resolution
;; ============================================================================

(defn- current-session-id
  "Read the current agent-session id from `*current-agent*`. Returns nil if
   no agent is bound (e.g. direct REPL invocation)."
  []
  (when-let [agent (some-> (requiring-resolve
                            'ai.brainyard.agent.core.protocol/*current-agent*)
                           deref)]
    (try
      (proto/session-id agent)
      (catch Throwable _ nil))))

;; ============================================================================
;; NDJSON helpers (minimal — keys/values are simple scalars or strings)
;; ============================================================================

(defn- json-escape
  [s]
  (-> (str s)
      (str/replace "\\" "\\\\")
      (str/replace "\"" "\\\"")
      (str/replace "\n" "\\n")
      (str/replace "\r" "\\r")
      (str/replace "\t" "\\t")))

(defn- json-kv
  [[k v]]
  (str "\"" (json-escape (if (keyword? k) (name k) (str k))) "\":"
       (cond
         (number? v)  (str v)
         (boolean? v) (if v "true" "false")
         (nil? v)     "null"
         (keyword? v) (str "\"" (json-escape (name v)) "\"")
         :else        (str "\"" (json-escape v) "\""))))

(defn- json-object
  [m]
  (str "{" (str/join "," (map json-kv m)) "}"))

(defn- parse-json-line
  "Tiny NDJSON line parser scoped to the routing.log shape (flat object,
   scalar values only). Returns a map with keyword keys, or nil on parse
   failure. Hand-rolled to avoid pulling in a json dep here."
  [^String line]
  (try
    (let [trimmed (str/trim line)]
      (when (and (str/starts-with? trimmed "{")
                 (str/ends-with? trimmed "}"))
        (let [body (subs trimmed 1 (dec (count trimmed)))
              ;; Split on top-level commas only; values can contain commas
              ;; inside quoted strings. Since strings are escaped, we can
              ;; safely split on `","` boundaries that follow a digit, null,
              ;; true/false, or a closing quote, but it's simpler and safe
              ;; enough for our flat schema to walk char-by-char.
              parts (loop [chars (seq body), buf [], in-str? false, esc? false, acc []]
                      (if (empty? chars)
                        (if (seq buf) (conj acc (apply str buf)) acc)
                        (let [c (first chars)]
                          (cond
                            esc?            (recur (rest chars) (conj buf c) in-str? false acc)
                            (= c \\)        (recur (rest chars) (conj buf c) in-str? true acc)
                            (= c \")        (recur (rest chars) (conj buf c) (not in-str?) false acc)
                            (and (= c \,) (not in-str?))
                            (recur (rest chars) [] false false (conj acc (apply str buf)))
                            :else           (recur (rest chars) (conj buf c) in-str? false acc)))))]
          (into {}
                (for [part parts
                      :let [[k v] (str/split part #":" 2)
                            kk    (-> k str/trim
                                      (str/replace #"^\"|\"$" "")
                                      keyword)
                            vt    (str/trim v)
                            vv    (cond
                                    (or (= "null" vt) (str/blank? vt)) nil
                                    (= "true" vt)  true
                                    (= "false" vt) false
                                    (re-matches #"-?\d+" vt) (Long/parseLong vt)
                                    (and (str/starts-with? vt "\"")
                                         (str/ends-with? vt "\""))
                                    (-> vt (subs 1 (dec (count vt)))
                                        (str/replace "\\\"" "\"")
                                        (str/replace "\\\\" "\\")
                                        (str/replace "\\n" "\n"))
                                    :else vt)]]
                  [kk vv])))))
    (catch Throwable _ nil)))

;; ============================================================================
;; Saved-line parser — public so the hook can share it
;; ============================================================================

(def saved-line-re
  "Matches `Saved <kind>: <path>` lines emitted by specialist agents. `<kind>`
   is a kebab-case word; `<path>` is everything to end-of-line. Multiline
   `(?m)` so it can be applied to a full :answer body."
  #"(?m)^Saved\s+([a-z][a-z0-9-]*):\s+(\S.*?)\s*$")

(defn parse-saved-lines
  "Extract every `Saved <kind>: <path>` line from `text`. Returns a vector of
   `{:kind <string> :path <string>}` maps in source order. Empty when none."
  [^String text]
  (if (str/blank? (or text ""))
    []
    (mapv (fn [[_ kind path]] {:kind kind :path path})
          (re-seq saved-line-re text))))

;; ============================================================================
;; main$session-id
;; ============================================================================

(defcommand main$session-id
  "Return the current agent-session id (read from `*current-agent*`)."
  (fn [& _]
    (if-let [sid (current-session-id)]
      {:session-id sid}
      {:error "No current agent — main$session-id must be called from inside an agent ask loop."}))
  :input-schema  [:map]
  :output-schema [:map
                  [:session-id {:optional true} [:string {:desc "Current agent-session id"}]]
                  [:error      {:optional true} [:string {:desc "Error if no agent is bound"}]]])

;; ============================================================================
;; main$resume?
;; ============================================================================

(defn- read-last-log-line
  [^java.io.File log-file]
  (when (.isFile log-file)
    (with-open [r (io/reader log-file)]
      (last (line-seq (java.io.BufferedReader. r))))))

(defcommand main$resume?
  "Cheap probe: does the routing-log dir exist for this session, and what is its state?"
  (fn [& {:keys [session-id base-dir]
          :or   {base-dir (config/project-dir)}}]
    (let [sid (or session-id (current-session-id))]
      (cond
        (not (string? sid))
        {:error ":session-id is required (string) when not called from an agent ask loop"}

        :else
        (let [dir (io/file base-dir base-rel sid)]
          (if-not (.isDirectory dir)
            {:exists? false}
            (let [log-file (io/file dir "routing.log")
                  ;; Single pass over routing.log — derive line-count, last
                  ;; shape/artifact, and max turn from one read+parse instead
                  ;; of opening the file three times (count + last + reparse).
                  parsed-lines (if (.isFile log-file)
                                 (with-open [r (io/reader log-file)]
                                   (mapv parse-json-line
                                         (line-seq (java.io.BufferedReader. r))))
                                 [])
                  last-parsed  (last parsed-lines)
                  turns        (->> parsed-lines (keep :turn) (apply max 0))]
              {:exists?       true
               :line-count    (count parsed-lines)
               :turn-count    turns
               :last-shape    (some-> (:shape last-parsed) keyword)
               :last-artifact (:artifact last-parsed)}))))))
  :input-schema  [:map
                  [:session-id {:optional true} [:string {:desc "Agent-session id (default: current agent's session)"}]]
                  [:base-dir   {:optional true} [:string {:desc "Working directory (default: project root)"}]]]
  :output-schema [:map
                  [:exists?       [:boolean {:desc "true if the routing-log dir exists"}]]
                  [:line-count    {:optional true} [:int     {:desc "Number of NDJSON lines in routing.log"}]]
                  [:turn-count    {:optional true} [:int     {:desc "Highest turn index seen"}]]
                  [:last-shape    {:optional true} [:keyword {:desc "Last decision shape"}]]
                  [:last-artifact {:optional true} [:string  {:desc "Last artifact path (or nil)"}]]
                  [:error         {:optional true} [:string  {:desc "Error if validation failed"}]]])

;; ============================================================================
;; main$bootstrap
;; ============================================================================

(def ^:private pointers-header
  "# Session pointers\n\n_Routing decisions and durable artifacts emitted this session, newest first._\n\n")

(defcommand main$bootstrap
  "Create .brainyard/agents/main-agent/<session-id>/ with empty routing.log and pointers.md header. Idempotent."
  (fn [& {:keys [session-id base-dir]
          :or   {base-dir (config/project-dir)}}]
    (let [sid (or session-id (current-session-id))]
      (cond
        (not (string? sid))
        {:error ":session-id is required (string) when not called from an agent ask loop"}

        :else
        (let [dir          (io/file base-dir base-rel sid)
              dir-rel      (str base-rel "/" sid)
              log-file     (io/file dir "routing.log")
              pointers     (io/file dir "pointers.md")
              log-rel      (str dir-rel "/routing.log")
              pointers-rel (str dir-rel "/pointers.md")]
          (if (.isDirectory dir)
            (do
              (mulog/log ::main.bootstrap-skip :session-id sid :reason :already-exists)
              {:exists?       true
               :dir           dir-rel
               :log-path      log-rel
               :pointers-path pointers-rel})
            (do
              (.mkdirs dir)
              (when-not (.isFile log-file)
                (spit log-file ""))
              (when-not (.isFile pointers)
                (spit pointers pointers-header))
              (mulog/log ::main.bootstrap :session-id sid)
              {:dir           dir-rel
               :log-path      log-rel
               :pointers-path pointers-rel}))))))
  :input-schema  [:map
                  [:session-id {:optional true} [:string {:desc "Agent-session id (default: current agent's session)"}]]
                  [:base-dir   {:optional true} [:string {:desc "Working directory (default: project root)"}]]]
  :output-schema [:map
                  [:dir           {:optional true} [:string  {:desc "Routing-log directory"}]]
                  [:log-path      {:optional true} [:string  {:desc "Path to routing.log"}]]
                  [:pointers-path {:optional true} [:string  {:desc "Path to pointers.md"}]]
                  [:exists?       {:optional true} [:boolean {:desc "true if directory already existed (idempotent skip)"}]]
                  [:error         {:optional true} [:string  {:desc "Error if validation failed"}]]])

;; ============================================================================
;; main$append-log
;; ============================================================================

(defcommand main$append-log
  "Append one NDJSON line to routing.log. Validates :shape against the 20-element §6 enum."
  (fn [& {:keys [session-id turn iter question shape routed-to artifact reason base-dir]
          :or   {base-dir (config/project-dir)}}]
    (let [sid       (or session-id (current-session-id))
          shape-kw  (cond
                      (keyword? shape) shape
                      (string? shape)  (keyword shape)
                      :else            nil)]
      (cond
        (not (string? sid))
        {:error ":session-id is required (string) when not called from an agent ask loop"}

        (not (integer? turn))
        {:error ":turn is required (integer)"}

        (not (integer? iter))
        {:error ":iter is required (integer)"}

        (not (string? question))
        {:error ":question is required (string)"}

        (or (nil? shape-kw) (not (valid-shapes shape-kw)))
        {:error (str ":shape must be one of " (sort (map name valid-shapes)))}

        (not (string? reason))
        {:error ":reason is required (string) — one-sentence rationale tied to §6 decision table"}

        :else
        (let [dir      (io/file base-dir base-rel sid)
              log-file (io/file dir "routing.log")]
          (if-not (.isDirectory dir)
            {:error (str "routing-log dir not found at " base-rel "/" sid
                         " — call main$bootstrap first")}
            (let [entry (array-map
                         :turn       turn
                         :iter       iter
                         :question   question
                         :shape      (name shape-kw)
                         :routed-to  (when routed-to (str routed-to))
                         :artifact   (when artifact  (str artifact))
                         :reason     reason)
                  line  (str (json-object entry) "\n")]
              (spit log-file line :append true)
              (mulog/log ::main.routing-decision
                         :session-id sid :turn turn :iter iter :shape shape-kw
                         :routed-to routed-to :artifact (boolean artifact))
              {:appended true :line (str/trim-newline line)}))))))
  :input-schema  [:map
                  [:session-id {:optional true} [:string  {:desc "Agent-session id (default: current agent's session)"}]]
                  [:turn       [:int     {:desc "1-based user-turn index within this session"}]]
                  [:iter       [:int     {:desc "main-agent iteration within this turn"}]]
                  [:question   [:string  {:desc "Distilled user-question or sub-question"}]]
                  [:shape      [:string  {:desc "One of: direct-answer / tool-fetch / code-compose / explore / update / plan-author / decompose / execute / evaluate / research / workflow / rlm / memory / skill-lifecycle / mcp-lifecycle / tool-lifecycle / init / config / acp / meta-resume / clarify"}]]
                  [:routed-to  {:optional true} [:string  {:desc "Specialist kebab-case name, or nil for self-answered moves"}]]
                  [:artifact   {:optional true} [:string  {:desc "Path emitted by the specialist (from its Saved <kind>: line)"}]]
                  [:reason     [:string  {:desc "One-sentence rationale tied to §6 decision-table rule"}]]
                  [:base-dir   {:optional true} [:string  {:desc "Working directory (default: project root)"}]]]
  :output-schema [:map
                  [:appended {:optional true} [:boolean {:desc "true on success"}]]
                  [:line     {:optional true} [:string  {:desc "The rendered NDJSON line (without trailing newline)"}]]
                  [:error    {:optional true} [:string  {:desc "Error if validation failed or dir missing"}]]])

;; ============================================================================
;; main$append-pointer
;; ============================================================================

(defcommand main$append-pointer
  "Append one markdown bullet to pointers.md with a timestamp + caption."
  (fn [& {:keys [session-id path caption base-dir]
          :or   {base-dir (config/project-dir)}}]
    (let [sid (or session-id (current-session-id))]
      (cond
        (not (string? sid))     {:error ":session-id is required (string) when not called from an agent ask loop"}
        (not (string? path))    {:error ":path is required (string)"}
        (not (string? caption)) {:error ":caption is required (string)"}
        :else
        (let [dir          (io/file base-dir base-rel sid)
              pointers     (io/file dir "pointers.md")
              flat-caption (-> caption (str/replace #"\s+" " ") str/trim)
              line         (str "- " (now-yyyy-mm-dd-hh-mm)
                                " [" path "](" path ") — "
                                flat-caption "\n")]
          (if-not (.isDirectory dir)
            {:error (str "routing-log dir not found at " base-rel "/" sid
                         " — call main$bootstrap first")}
            (do
              (when-not (.isFile pointers)
                (spit pointers pointers-header))
              (spit pointers line :append true)
              (mulog/log ::main.pointer :session-id sid :path path)
              {:appended true}))))))
  :input-schema  [:map
                  [:session-id {:optional true} [:string {:desc "Agent-session id (default: current agent's session)"}]]
                  [:path       [:string {:desc "Artifact path (typically from a specialist's `Saved <kind>: <path>` line)"}]]
                  [:caption    [:string {:desc "One-line caption — kind + headline, e.g. 'plan body — migrate-auth'"}]]
                  [:base-dir   {:optional true} [:string {:desc "Working directory (default: project root)"}]]]
  :output-schema [:map
                  [:appended {:optional true} [:boolean {:desc "true on success"}]]
                  [:error    {:optional true} [:string  {:desc "Error if validation failed or dir missing"}]]])

;; ============================================================================
;; main$last-shape
;; ============================================================================

(defcommand main$last-shape
  "Return the last routing decision in the current session's routing.log."
  (fn [& {:keys [session-id base-dir]
          :or   {base-dir (config/project-dir)}}]
    (let [sid (or session-id (current-session-id))]
      (cond
        (not (string? sid))
        {:error ":session-id is required (string) when not called from an agent ask loop"}

        :else
        (let [log-file (io/file base-dir base-rel sid "routing.log")
              last-ln  (read-last-log-line log-file)
              parsed   (when last-ln (parse-json-line last-ln))]
          (cond
            (not (.isFile log-file))
            {:exists? false}

            (nil? parsed)
            {:exists? false}

            :else
            {:exists?   true
             :shape     (some-> (:shape parsed) keyword)
             :routed-to (:routed-to parsed)
             :artifact  (:artifact parsed)
             :turn      (:turn parsed)
             :iter      (:iter parsed)
             :question  (:question parsed)
             :reason    (:reason parsed)})))))
  :input-schema  [:map
                  [:session-id {:optional true} [:string {:desc "Agent-session id (default: current agent's session)"}]]
                  [:base-dir   {:optional true} [:string {:desc "Working directory (default: project root)"}]]]
  :output-schema [:map
                  [:exists?   {:optional true} [:boolean {:desc "true if at least one routing decision has been logged"}]]
                  [:shape     {:optional true} [:keyword {:desc "Last decision shape"}]]
                  [:routed-to {:optional true} [:string  {:desc "Last specialist routed to (or nil)"}]]
                  [:artifact  {:optional true} [:string  {:desc "Last artifact path (or nil)"}]]
                  [:turn      {:optional true} [:int     {:desc "Turn the last decision belongs to"}]]
                  [:iter      {:optional true} [:int     {:desc "Iteration the last decision belongs to"}]]
                  [:question  {:optional true} [:string  {:desc "Question text from the last log line"}]]
                  [:reason    {:optional true} [:string  {:desc "One-sentence rationale from the last log line"}]]
                  [:error     {:optional true} [:string  {:desc "Error if validation failed"}]]])

;; ============================================================================
;; main$index-append
;; ============================================================================

(defcommand main$index-append
  "Append one line to .brainyard/agents/main-agent/INDEX.md summarizing a closed session."
  (fn [& {:keys [session-id turn-count shapes base-dir]
          :or   {base-dir (config/project-dir)
                 shapes   []}}]
    (cond
      (not (string? session-id))
      {:error ":session-id is required (string)"}

      (not (integer? turn-count))
      {:error ":turn-count is required (integer)"}

      (not (sequential? shapes))
      {:error ":shapes must be a vector of shape keywords (or empty)"}

      :else
      (let [shape-strs (->> shapes
                            (map (fn [s] (cond
                                           (keyword? s) (name s)
                                           (string?  s) s
                                           :else        (str s))))
                            distinct)
            line       (str "- " (now-yyyy-mm-dd-hh-mm)
                            " [session " session-id "](" session-id "/)"
                            " — turns: " turn-count
                            " · shapes: "
                            (if (seq shape-strs)
                              (str/join ", " shape-strs)
                              "(none)")
                            "\n")
            file       (io/file base-dir index-rel)]
        (.mkdirs (.getParentFile file))
        (spit file line :append true)
        (mulog/log ::main.index :session-id session-id :turn-count turn-count
                   :shape-count (count shape-strs))
        {:appended true :line (str/trim-newline line)})))
  :input-schema  [:map
                  [:session-id [:string {:desc "Closed agent-session id"}]]
                  [:turn-count [:int    {:desc "Number of user-turns this session saw"}]]
                  [:shapes     {:optional true} [:vector {:desc "Distinct routing-decision shapes seen this session"} :string]]
                  [:base-dir   {:optional true} [:string {:desc "Working directory (default: project root)"}]]]
  :output-schema [:map
                  [:appended {:optional true} [:boolean {:desc "true on success"}]]
                  [:line     {:optional true} [:string  {:desc "The appended line"}]]
                  [:error    {:optional true} [:string  {:desc "Error if validation failed"}]]])

;; ============================================================================
;; Internal: read full routing.log (used by hooks for INDEX summary)
;; ============================================================================

(defn read-routing-log
  "Read every parsed NDJSON line from a session's routing.log. Returns a
   vector of maps in chronological order, or `[]` if no log exists."
  [session-id & {:keys [base-dir] :or {base-dir (config/project-dir)}}]
  (let [log-file (io/file base-dir base-rel session-id "routing.log")]
    (if-not (.isFile log-file)
      []
      (with-open [r (io/reader log-file)]
        (->> (line-seq (java.io.BufferedReader. r))
             (keep parse-json-line)
             vec)))))

;; ============================================================================
;; Public roster (for main-agent's :agent-tools)
;; ============================================================================

(def main-helpers
  "Vector of all main$* helper vars. main-agent appends these to its
   :agent-tools roster so the SCI sandbox auto-binds them (callable as
   `(main$bootstrap ...)` in a clojure fence)."
  [#'main$session-id
   #'main$resume?
   #'main$bootstrap
   #'main$append-log
   #'main$append-pointer
   #'main$last-shape
   #'main$index-append])
