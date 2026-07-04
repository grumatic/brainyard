;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.clj-llm.core.claude-code
  "Claude Code CLI provider — uses `claude -p` subprocess for chat completion.
   No API key required; uses the user's Claude Code subscription auth."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [ai.brainyard.mulog.interface :as mulog])
  (:import [java.io BufferedReader File InputStreamReader OutputStreamWriter]
           [java.util.concurrent TimeUnit]))

;; ============================================================================
;; Argv size guard (system-prompt + json-schema travel as argv, bounded by
;; OS ARG_MAX — 1 MB on macOS, 128 KB on some Linux configs). Large CoAct turns
;; with heavy stable-context can approach this. Spool large prompts to a temp
;; file via --system-prompt-file to bypass ARG_MAX entirely.
;; ============================================================================

(def ^:private spool-threshold-bytes
  "Spool --system-prompt to a temp file via --system-prompt-file once the prompt
   exceeds this many UTF-16 chars. 256 KB leaves comfortable headroom in the
   1 MB macOS argv pool for env + other argv + the --json-schema string."
  262144)

(defn- spool-system-prompt
  "If system-prompt is large, write it to a temp file. Returns a map:
     {:flag      \"--system-prompt\" or \"--system-prompt-file\"
      :value     the literal prompt string OR the temp-file path
      :temp-file the java.io.File to delete after the subprocess exits, or nil}"
  [system-prompt]
  (if (and system-prompt (> (count system-prompt) spool-threshold-bytes))
    (let [tmp (doto (File/createTempFile "brainyard-cc-sys-" ".txt")
                .deleteOnExit)]
      (spit tmp system-prompt)
      {:flag "--system-prompt-file" :value (.getAbsolutePath tmp) :temp-file tmp})
    {:flag "--system-prompt" :value system-prompt :temp-file nil}))

;; ============================================================================
;; Message Flattening
;; ============================================================================

(defn- flatten-messages
  "Extract system messages and flatten conversation into CLI-compatible format.
   Returns {:system-prompt \"...\" :prompt \"...\"}."
  [messages]
  (let [system-msgs (filter #(= "system" (:role %)) messages)
        other-msgs  (remove #(= "system" (:role %)) messages)
        system-text (when (seq system-msgs)
                      (str/join "\n\n" (map :content system-msgs)))]
    {:system-prompt system-text
     :prompt (if (= 1 (count other-msgs))
               (:content (first other-msgs))
               ;; Multi-turn: format as labeled turns
               (str/join "\n\n"
                         (map (fn [{:keys [role content]}]
                                (str "[" role "]: " content))
                              other-msgs)))}))

;; ============================================================================
;; Structured-output reinforcement
;; ============================================================================

(def ^:private structured-output-directive
  "Claude-code-specific hardening for structured output. `--json-schema` exposes
   a StructuredOutput tool but with tool_choice=auto — it is OPTIONAL, so on
   large/agentic prompts the model can answer in plain prose (which fails JSON
   parsing) or try to DO the task itself. This directive closes that gap; it is
   appended to the system prompt only when --json-schema is in force. (If the
   CLI ever gains a force-tool-choice flag, that would be the stronger successor
   to this prompt-level nudge.)"
  (str "\n\n=== STRUCTURED OUTPUT — MANDATORY ===\n"
       "You are a headless structured-output endpoint, NOT an interactive assistant. "
       "Return your ENTIRE response by calling the StructuredOutput tool with a single "
       "JSON object matching the schema. Never reply in prose, markdown, or natural "
       "language, and never narrate a plan.\n"
       "You have NO tools and NO filesystem access: never write files, never run shell "
       "or heredocs (e.g. `cat > file <<'EOF'`), and never perform the task yourself. "
       "To run code, put it verbatim as a STRING in the `code-blocks` field — the host "
       "executes it and returns the result to you on the next turn. This holds for code "
       "of ANY size, including large or multi-file edits: emit it in `code-blocks`, never "
       "inline it as prose.\n"
       "If you still need information, populate `tool-calls`. Populate `answer` ONLY when "
       "the task is complete."))

(defn- reinforce-structured-output
  "Append `structured-output-directive` to the system prompt when structured
   output (--json-schema) is active, else return it unchanged. Applied to both
   the buffered and streaming CLI paths."
  [system-prompt opts]
  (if (:json-schema opts)
    (str system-prompt structured-output-directive)
    system-prompt))

;; ============================================================================
;; CLI Argument Building
;; ============================================================================

(defn- build-cli-args
  "Build the CLI argument vector for `claude` command.
   When opts has :json-schema, pass --json-schema so the CLI exposes a synthetic
   StructuredOutput tool and the model is forced to emit schema-conformant JSON
   as a tool_use input. With --max-turns 1 the CLI exits with code 1 after the
   tool call (terminal_reason=max_turns), but the JSON is already in the events
   — extract-structured-output reads it from the assistant.message.content tool_use
   block. This is cheaper than --max-turns 2 (which adds a redundant trailing
   acknowledgment turn) and far more reliable than prompt-only JSON instruction
   for long structured prompts like CoAct."
  [lm-config opts {:keys [system-prompt-flag system-prompt-value]}]
  (let [{:keys [model max-tokens]} lm-config
        json-schema (:json-schema opts)]
    (cond-> ["claude" "-p"
             "--no-session-persistence"
             "--output-format" "json"
             "--tools" ""                ;; Disable all built-in tools
             "--setting-sources" ""      ;; No project CLAUDE.md / MCP / plugins
             "--strict-mcp-config"       ;; No MCP servers (no --mcp-config = zero servers)
             "--disable-slash-commands"   ;; No skills/commands context
             "--max-turns" "1"]          ;; Single LLM turn, no agentic loop
      model               (into ["--model" model])
      max-tokens          (into ["--max-tokens" (str max-tokens)])
      system-prompt-value (into [system-prompt-flag system-prompt-value])
      json-schema         (into ["--json-schema" (json/write-str json-schema)]))))

;; ============================================================================
;; Process Execution
;; ============================================================================

(defn- start-claude-process
  "Start the Claude CLI process. Rewrites IOException with a precise cause:
   - 'Argument list too long' (E2BIG) → argv overflow, with the offending size
   - 'Cannot run program' / 'No such file' → CLI not installed"
  ^Process [^java.util.List args]
  (let [pb (ProcessBuilder. args)
        _ (.redirectErrorStream pb false)
        _ (.. pb environment (remove "CLAUDECODE"))]
    (try
      (.start pb)
      (catch java.io.IOException e
        (let [msg (or (.getMessage e) "")
              argv-bytes (reduce + (map #(count (str %)) args))]
          (cond
            (str/includes? msg "Argument list too long")
            (throw (ex-info
                    (str "Claude CLI argv exceeded OS ARG_MAX. "
                         "Total argv size: " argv-bytes " bytes. "
                         "Largest single arg is likely --system-prompt or --json-schema. "
                         "Increase spool-threshold-bytes or shrink the prompt.")
                    {:cause msg :argv-bytes argv-bytes}
                    e))

            (or (str/includes? msg "Cannot run program")
                (str/includes? msg "No such file"))
            (throw (ex-info "Claude CLI not found. Install Claude Code CLI first."
                            {:cause msg} e))

            :else
            (throw e)))))))

(defn- execute-process
  "Execute a CLI command via ProcessBuilder.
   Returns {:exit int :stdout str :stderr str}."
  [args stdin-text timeout-ms]
  (let [proc (start-claude-process args)]
    ;; Write stdin
    (when stdin-text
      (with-open [writer (OutputStreamWriter. (.getOutputStream proc) "UTF-8")]
        (.write writer ^String stdin-text)
        (.flush writer)))
    ;; Read stdout and stderr concurrently
    (let [stdout-future (future
                          (with-open [rdr (BufferedReader.
                                           (InputStreamReader. (.getInputStream proc) "UTF-8"))]
                            (slurp rdr)))
          stderr-future (future
                          (with-open [rdr (BufferedReader.
                                           (InputStreamReader. (.getErrorStream proc) "UTF-8"))]
                            (slurp rdr)))
          exited? (.waitFor proc timeout-ms TimeUnit/MILLISECONDS)]
      (if exited?
        {:exit (.exitValue proc)
         :stdout @stdout-future
         :stderr @stderr-future}
        (do
          (.destroyForcibly proc)
          (throw (ex-info "Claude CLI timed out"
                          {:timeout-ms timeout-ms :args args})))))))

;; ============================================================================
;; Response Normalization
;; ============================================================================

(defn- parse-ndjson-result
  "Parse JSON output from Claude CLI, extracting the result event.
   CLI may output: NDJSON (one object per line) or a JSON array of objects.
   We want the last 'result' type event."
  [stdout]
  (let [trimmed (str/trim stdout)
        ;; Try parsing the whole output first
        parsed-all (try (json/read-str trimmed :key-fn keyword) (catch Exception _ nil))
        ;; If it's an array, search within it
        events (cond
                 (vector? parsed-all) parsed-all
                 (map? parsed-all)    [parsed-all]
                 ;; NDJSON: split by lines and parse each
                 :else (->> (str/split-lines trimmed)
                            (keep (fn [line]
                                    (when-not (str/blank? line)
                                      (try (json/read-str line :key-fn keyword)
                                           (catch Exception _ nil)))))))]
    (or (->> events
             (filter #(= "result" (:type %)))
             last)
        ;; Fallback: return a pseudo-result with the raw text
        {:result trimmed})))

(defn- value-wrapper?
  "True when a parsed StructuredOutput tool input is the single-key
   `{:value \"<json>\"}` envelope some models (observed with claude-code:sonnet)
   emit instead of filling the schema fields directly (as claude-code:haiku
   does). A real signature never has a lone `value` string field, so this shape
   is unambiguously the wrapper."
  [m]
  (and (map? m)
       (= [:value] (keys m))
       (string? (:value m))))

(defn- unwrap-structured-input
  "Normalize a StructuredOutput tool_use `:input` MAP into the JSON string the
   parser expects: unwrap the `{:value \"<json>\"}` envelope to its inner JSON
   string; otherwise write the map out as-is."
  [input]
  (if (value-wrapper? input)
    (:value input)
    (json/write-str input)))

(defn- unwrap-structured-json
  "Same as `unwrap-structured-input` but for an already-serialized JSON STRING
   (the streaming path accumulates the tool input as raw `partial_json`). Returns
   the inner JSON when the string is the `{\"value\":\"<json>\"}` envelope, else
   the string unchanged (non-JSON / natural-language text passes through)."
  [json-str]
  (or (try
        (let [m (json/read-str json-str :key-fn keyword)]
          (when (value-wrapper? m) (:value m)))
        (catch Exception _ nil))
      json-str))

(defn- extract-structured-output
  "Extract structured output from CLI events when the LLM uses the StructuredOutput tool.
   Returns the tool input as a JSON string, or nil if not found."
  [stdout]
  (let [trimmed (str/trim stdout)
        parsed-all (try (json/read-str trimmed :key-fn keyword) (catch Exception _ nil))
        events (cond
                 (vector? parsed-all) parsed-all
                 (map? parsed-all) [parsed-all]
                 :else (->> (str/split-lines trimmed)
                            (keep (fn [line]
                                    (when-not (str/blank? line)
                                      (try (json/read-str line :key-fn keyword)
                                           (catch Exception _ nil)))))))]
    ;; Find assistant event with StructuredOutput tool use
    (some (fn [event]
            (when (= "assistant" (:type event))
              (some (fn [block]
                      (when (and (= "tool_use" (:type block))
                                 (= "StructuredOutput" (:name block)))
                        (unwrap-structured-input (:input block))))
                    (get-in event [:message :content]))))
          events)))

(defn- extract-assistant-text
  "Extract text content from assistant events in the CLI output.
   Falls back to this when result.is_error=true but the LLM produced text
   before making a tool call that failed (e.g., MCP tool use with --max-turns 1)."
  [stdout]
  (let [trimmed (str/trim stdout)
        parsed-all (try (json/read-str trimmed :key-fn keyword) (catch Exception _ nil))
        events (cond
                 (vector? parsed-all) parsed-all
                 (map? parsed-all) [parsed-all]
                 :else (->> (str/split-lines trimmed)
                            (keep (fn [line]
                                    (when-not (str/blank? line)
                                      (try (json/read-str line :key-fn keyword)
                                           (catch Exception _ nil)))))))]
    (->> events
         (filter #(= "assistant" (:type %)))
         (mapcat (fn [evt]
                   (keep (fn [block]
                           (when (= "text" (:type block))
                             (:text block)))
                         (get-in evt [:message :content]))))
         (str/join "\n")
         not-empty)))

(defn- normalize-response
  "Normalize CLI NDJSON response to Anthropic-like format.
   CLI outputs multiple JSON lines; the 'result' event contains the answer.
   When --json-schema is used, the JSON is in a StructuredOutput tool_use block
   and the :result field is unreliable (often a max_turns error string or a
   trailing natural-language acknowledgment) — prefer the tool_use input.
   We produce: {:content [{:type \"text\" :text \"...\"}] :model m :usage {...}}"
  [cli-result model]
  (let [stdout (:stdout cli-result)
        parsed (parse-ndjson-result stdout)
        structured (extract-structured-output stdout)
        result-text (or structured
                        (let [r (:result parsed)]
                          (when-not (str/blank? r) r))
                        stdout)
        usage (:usage parsed)]
    {:content [{:type "text" :text result-text}]
     :model   (or (:model parsed) model)
     :usage   {:input_tokens               (or (:input_tokens usage) 0)
               :output_tokens              (or (:output_tokens usage) 0)
               :cache_read_input_tokens    (or (:cache_read_input_tokens usage) 0)
               :cache_creation_input_tokens (or (:cache_creation_input_tokens usage) 0)}
     ::cli-cost    (:total_cost_usd parsed)
     ::duration-ms (:duration_ms parsed)
     ::num-turns   (:num_turns parsed)}))

;; ============================================================================
;; Public API
;; ============================================================================

(def ^:private default-timeout-ms 300000) ;; 5 minutes

(defn chat-completion
  "Execute a chat completion via Claude CLI subprocess.
   Returns an Anthropic-like response map."
  [lm-config messages opts]
  (let [{:keys [system-prompt prompt]} (flatten-messages messages)
        system-prompt (reinforce-structured-output system-prompt opts)
        spooled (spool-system-prompt system-prompt)
        args    (build-cli-args lm-config opts
                                {:system-prompt-flag  (:flag spooled)
                                 :system-prompt-value (:value spooled)})
        _       (mulog/debug ::claude-cli-args
                             :args args
                             :system-prompt-spooled? (some? (:temp-file spooled))
                             :system-prompt-chars (count system-prompt))
        timeout (or (:timeout-ms opts) default-timeout-ms)]
    (mulog/log ::cli-call
               :provider :claude-code
               :model (:model lm-config)
               :args args
               :prompt prompt)
    (try
      (let [start-ns (System/nanoTime)
            result (execute-process args prompt timeout)]
        (when (not= 0 (:exit result))
          ;; Try to recover a useful response from a partial response. Two cases:
          ;;   (a) --json-schema + max-turns 1: model emits a StructuredOutput
          ;;       tool_use and CLI exits with terminal_reason=max_turns. The JSON
          ;;       is in the tool_use input — extract-structured-output recovers it.
          ;;   (b) LLM produced text but a follow-up tool call failed under
          ;;       --max-turns 1. extract-assistant-text salvages the text blocks.
          (if-let [salvaged-text (or (extract-structured-output (:stdout result))
                                     (extract-assistant-text (:stdout result)))]
            (mulog/warn ::claude-cli-error-recovered
                        :exit (:exit result) :recovered-chars (count salvaged-text))
            (throw (ex-info (str "Claude CLI exited with code " (:exit result))
                            {:exit (:exit result) :stderr (:stderr result) :args args}))))
        ;; Use salvaged text if exit was non-zero but we recovered. Preserve the
        ;; original stdout so normalize-response can still see the events (and the
        ;; usage/cost metadata in the 'result' event); only rebuild it if we have
        ;; no structured output to feed normalize-response from.
        (let [structured (when (not= 0 (:exit result))
                           (extract-structured-output (:stdout result)))
              result (cond
                       (zero? (:exit result)) result
                       ;; Structured output present — keep stdout intact so
                       ;; normalize-response reads it AND the usage event.
                       structured result
                       ;; Fall back to a synthetic result containing only the
                       ;; salvaged assistant text.
                       :else (assoc result :stdout
                                    (json/write-str
                                     [{:type "result"
                                       :result (extract-assistant-text (:stdout result))}])))
              response (normalize-response result (:model lm-config))
              duration-ms (quot (- (System/nanoTime) start-ns) 1000000)]
          (mulog/log ::cli-call-result
                     :provider :claude-code
                     :model (:model lm-config)
                     :duration-ms duration-ms
                     :input-tokens (get-in response [:usage :input_tokens])
                     :output-tokens (get-in response [:usage :output_tokens])
                     :cli-cost (::cli-cost response)
                     :num-turns (::num-turns response)
                     :response response)
          response))
      (finally
        (when-let [^File tmp (:temp-file spooled)]
          (try (.delete tmp) (catch Exception _ nil)))))))

(defn chat-completion-stream
  "Execute a streaming chat completion via Claude CLI subprocess.
   Uses --output-format stream-json. Calls on-chunk for each content delta when
   on-chunk is non-nil; a nil on-chunk is accepted (chunks are consumed and
   discarded). Returns the final reconstructed response."
  [lm-config messages opts on-chunk]
  (let [{:keys [system-prompt prompt]} (flatten-messages messages)
        system-prompt (reinforce-structured-output system-prompt opts)
        spooled (spool-system-prompt system-prompt)
        args (-> (build-cli-args lm-config opts
                                 {:system-prompt-flag  (:flag spooled)
                                  :system-prompt-value (:value spooled)})
                 ;; Replace "json" with "stream-json" in the args
                 (assoc 4 "stream-json")
                 ;; --verbose is required for stream-json with -p
                 (conj "--verbose"))]
    (mulog/log ::cli-call
               :provider :claude-code
               :model (:model lm-config)
               :args args
               :prompt prompt
               :stream true)
    (let [start-ns (System/nanoTime)
          proc (start-claude-process args)]
      (try
        ;; Write stdin
        (when prompt
          (with-open [writer (OutputStreamWriter. (.getOutputStream proc) "UTF-8")]
            (.write writer ^String prompt)
            (.flush writer)))
        ;; Read stream line by line
        (let [accumulated (StringBuilder.)
              tool-json (StringBuilder.)
              all-events (atom [])
              final-result (atom nil)]
          (with-open [rdr (BufferedReader.
                           (InputStreamReader. (.getInputStream proc) "UTF-8"))]
            (loop []
              (when-let [line (.readLine rdr)]
                (when-not (str/blank? line)
                  (try
                    (let [parsed (json/read-str line :key-fn keyword)]
                      (swap! all-events conj parsed)
                      (case (:type parsed)
                        "content_block_delta"
                        (let [delta (:delta parsed)
                              text (or (:text delta) "")
                              json-chunk (:partial_json delta)]
                          (when-not (str/blank? text)
                            (.append accumulated text)
                            (when on-chunk
                              (on-chunk {:type :content-delta :text text})))
                          (when json-chunk
                            (.append tool-json json-chunk)))

                        ;; assistant event — full response (Claude CLI doesn't stream deltas)
                        ;; Extract text blocks first; fall back to thinking blocks
                        ;; when extended thinking is enabled and no text was produced.
                        "assistant"
                        (let [content (get-in parsed [:message :content])
                              texts (when (sequential? content)
                                      (keep (fn [block]
                                              (when (= "text" (:type block))
                                                (:text block)))
                                            content))
                              thinking-texts (when (and (empty? texts) (sequential? content))
                                               (keep (fn [block]
                                                       (when (= "thinking" (:type block))
                                                         (:thinking block)))
                                                     content))
                              full-text (str/join "\n" (or (seq texts) thinking-texts))]
                          (when-not (str/blank? full-text)
                            (.append accumulated full-text)
                            (when on-chunk
                              (on-chunk {:type :content-delta :text full-text}))))

                        "result"
                        (reset! final-result parsed)

                        ;; Other event types — skip
                        nil))
                    (catch Exception _ nil)))
                (recur))))
          ;; Wait for the process to finish. `.waitFor` with a timeout returns a
          ;; boolean and does NOT throw — but `.exitValue` on a still-running
          ;; process throws IllegalThreadStateException, so guard the timeout
          ;; explicitly (mirrors the non-streaming path).
          (let [exited? (.waitFor proc (or (:timeout-ms opts) default-timeout-ms) TimeUnit/MILLISECONDS)]
            (when-not exited?
              (.destroyForcibly proc)
              (throw (ex-info "Claude CLI stream timed out"
                              {:timeout-ms (or (:timeout-ms opts) default-timeout-ms) :args args}))))
          (let [exit-code (.exitValue proc)
                nonzero-stderr (when (not= 0 exit-code)
                                 (try (slurp (.getErrorStream proc)) (catch Exception _ "")))]
            (when (not= 0 exit-code)
              (let [acc-len (.length accumulated)
                    has-rate-limit (some #(= "rate_limit_event" (:type %)) @all-events)]
                ;; Downgrade to debug when we have accumulated text (rate-limit with partial response)
                (if (and (pos? acc-len) has-rate-limit)
                  (mulog/debug ::claude-code-stream-rate-limited
                               :exit exit-code
                               :accumulated-length acc-len)
                  (mulog/warn ::claude-code-stream-nonzero-exit
                              :exit exit-code :stderr nonzero-stderr
                              :event-types (mapv :type @all-events)
                              :accumulated-length acc-len
                              :tool-json-length (.length tool-json)))))
            (let [tool-json-str (str tool-json)
                  structured-output (when (str/blank? tool-json-str)
                                      (some (fn [event]
                                              (when (= "assistant" (:type event))
                                                (some (fn [block]
                                                        (when (and (= "tool_use" (:type block))
                                                                   (= "StructuredOutput" (:name block)))
                                                          (json/write-str (:input block))))
                                                      (get-in event [:message :content]))))
                                            @all-events))
                  result-text (let [fr @final-result
                                    fr-result (when fr
                                                (let [r (:result fr)]
                                                  (when-not (str/blank? r) r)))]
                                ;; Any of these candidates may be the
                                ;; `{"value":"<json>"}` StructuredOutput envelope
                                ;; (observed with claude-code:sonnet); unwrap it so
                                ;; the downstream JSON parser sees the real fields.
                                ;; Non-envelope text passes through untouched.
                                (unwrap-structured-json
                                 (or fr-result
                                     (when-not (str/blank? tool-json-str) tool-json-str)
                                     structured-output
                                     (str accumulated))))
                  model (:model lm-config)
                  duration-ms (quot (- (System/nanoTime) start-ns) 1000000)]
              (when (str/blank? result-text)
                (mulog/warn ::claude-code-stream-empty-result
                            :event-count (count @all-events)
                            :event-types (mapv :type @all-events)
                            :accumulated-length (.length accumulated)
                            :tool-json-length (.length tool-json)
                            :final-result-keys (when @final-result (keys @final-result))
                            :final-result-result (:result @final-result)
                            :assistant-events (filterv #(= "assistant" (:type %)) @all-events)))
              ;; A nonzero exit that yielded NO recoverable text is a hard failure:
              ;; throw a clean, classifiable error instead of returning an empty
              ;; answer that silently corrupts the turn (mirrors chat-completion,
              ;; which throws on an unrecoverable nonzero exit). The salvageable
              ;; cases — StructuredOutput tool_use / accumulated text under
              ;; --max-turns 1 — already produced non-blank result-text above.
              (when (and (not= 0 exit-code) (str/blank? result-text))
                (throw (ex-info (str "Claude CLI stream exited with code " exit-code
                                     " and produced no output")
                                {:exit exit-code :stderr nonzero-stderr
                                 :event-types (mapv :type @all-events)})))
              (when on-chunk
                (on-chunk {:type :done :usage {}}))
              (let [response {:content [{:type "text" :text result-text}]
                              :model   model
                              :usage   {:input_tokens                (or (get-in @final-result [:usage :input_tokens]) 0)
                                        :output_tokens               (or (get-in @final-result [:usage :output_tokens]) 0)
                                        :cache_read_input_tokens     (or (get-in @final-result [:usage :cache_read_input_tokens]) 0)
                                        :cache_creation_input_tokens (or (get-in @final-result [:usage :cache_creation_input_tokens]) 0)}
                              ::cli-cost    (:total_cost_usd @final-result)
                              ::duration-ms (:duration_ms @final-result)
                              ::num-turns   (:num_turns @final-result)}]
                (mulog/log ::cli-call-result
                           :provider :claude-code
                           :model model
                           :stream true
                           :duration-ms duration-ms
                           :input-tokens (get-in response [:usage :input_tokens])
                           :output-tokens (get-in response [:usage :output_tokens])
                           :cli-cost (::cli-cost response)
                           :num-turns (::num-turns response)
                           :response response)
                response))))
        (catch Throwable e
          ;; Throwable, not Exception: a StackOverflowError / OutOfMemoryError
          ;; from the read/parse path is an Error, not an Exception — catching
          ;; only Exception would let it escape with the `claude` subprocess
          ;; still alive (a leak). Destroy the process, then re-throw so the
          ;; caller's error classification handles it.
          (.destroyForcibly proc)
          (throw e))
        (finally
          (when-let [^File tmp (:temp-file spooled)]
            (try (.delete tmp) (catch Exception _ nil))))))))
