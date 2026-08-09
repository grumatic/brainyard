;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.acp-client.core.events
  "Pure translation: ACP `session/update` notification payloads →
   brainyard hook event descriptors.

   This namespace is **pure data**. It does not call into
   `agent.core.hooks/fire!` — that would create an unwanted dep from
   acp-client → agent. Instead, the dispatcher in `client.clj`
   collects descriptors and hands them to a caller-supplied
   `:on-event` callback. The Phase 5 `acp-agent` defagent provides a
   callback that fires real brainyard hooks.

   The translation table mirrors §4.2.1 of docs/design/acp-design.md.

   Each translation returns either:
     - `nil` if no event is fired (e.g. unknown sessionUpdate variant)
     - a map  `{:event ::keyword, :data {…}}`

   Stop-reason translation (driven from session/prompt's response,
   not session/update) is exposed separately as `translate-stop-reason`."
  (:require [clojure.string :as str]))

;; =============================================================================
;; Hook event keywords (verbatim from agent.core.hooks event catalog)
;;
;; We do NOT require agent.core.hooks here — keys are duplicated as
;; constants so this namespace stays free of agent deps. If the catalog
;; changes upstream, an integration test against the agent's catalog
;; would catch a drift. Phase 5 wires that test up.
;; =============================================================================

(def ^:const event-dspy-chunk        :agent.dspy-action/chunk)
(def ^:const event-tool-use-pre      :agent.tool-use/pre)
(def ^:const event-tool-use-post     :agent.tool-use/post)
(def ^:const event-tool-calls-pre    :agent.tool-calls/pre)
(def ^:const event-tool-calls-post   :agent.tool-calls/post)
(def ^:const event-todo-updated      :todo/updated)
(def ^:const event-iteration-pre     :agent.iteration/pre)
(def ^:const event-iteration-post    :agent.iteration/post)
(def ^:const event-iteration-exhausted :agent.iteration/exhausted)

;; =============================================================================
;; Helpers
;; =============================================================================

(defn- content-block-text
  "Extract text from an ACP content block, joining if it's a vector.
   Returns \"\" when the block has no text content."
  [block]
  (cond
    (nil? block)              ""
    (string? block)           block
    (and (map? block)
         (= "text" (:type block))) (or (:text block) "")
    (vector? block)           (->> block (map content-block-text) (str/join))
    :else                     ""))

;; =============================================================================
;; session/update translation
;; =============================================================================

(defn- normalize-update
  "Lift the ACP-spec `:update` object so translation is agnostic to
   nesting. Real ACP `session/update` params are
   `{:sessionId .. :update {:sessionUpdate .. <payload>}}` (the discriminant
   and payload live inside `:update`); the in-tree stub and some tests emit
   the payload flat at the params top level. Merge the `:update` fields up
   (preserving the sibling `:sessionId`) so `dispatch-update` sees a single
   flat map either way."
  [params]
  (if-let [u (:update params)]
    (merge (dissoc params :update) u)
    params))

(defmulti ^:private dispatch-update
  "Dispatch on the (normalized) `:sessionUpdate` discriminant. Returns nil
   for variants that don't map to a single event (e.g. tool_call_update
   which is merged into the in-progress tool_call's hook data)."
  (fn [params] (:sessionUpdate params)))

(defn translate-update
  "Translate a `session/update` notification's params into a brainyard
   hook event descriptor `{:event :data}`, or nil. Tolerant of both the
   spec-compliant nested (`:update`) and flat payload shapes."
  [params]
  (dispatch-update (normalize-update params)))

(defmethod dispatch-update :default [_] nil)

(defmethod dispatch-update "agent_message_chunk"
  [{:keys [content sessionId]}]
  (let [text (content-block-text content)]
    {:event event-dspy-chunk
     :data  {:chunk      text
             :session-id sessionId}}))

(defmethod dispatch-update "agent_thought_chunk"
  [{:keys [content sessionId]}]
  (let [text (content-block-text content)]
    {:event event-dspy-chunk
     :data  {:chunk      text
             :session-id sessionId
             :meta       {:kind :thought}}}))

(defmethod dispatch-update "plan"
  ;; Normalize ACP `PlanEntry` fields (`content`/`status`) into the native
  ;; todo-item shape the TUI renderers consume (`:description`/`:done` — see
  ;; render/todo-block and tui.format/format-todo-list). Without this the rows
  ;; render blank (no `:description`) and never tick (no `:done`). `:status` /
  ;; `:priority` are preserved so nothing downstream regresses.
  [{:keys [entries sessionId]}]
  {:event event-todo-updated
   :data  {:todo-list  (mapv (fn [e]
                               (let [status (or (:status e) "pending")]
                                 {:description (:content e)
                                  :done        (= "completed" status)
                                  :status      status
                                  :priority    (:priority e)}))
                             entries)
           :session-id sessionId}})

(defn- acp-tool-name
  "The display name for a tool call. Prefer the adapter's real tool name
   (claude-code puts it in `_meta.claudeCode.toolName`, e.g. \"Bash\" /
   \"Read\") over the ACP `:title`, which is a human-readable *description*
   (the shell command, \"Read <path>\", …) — using `:title` would show the
   argument text where the tool name belongs. Falls back to `:title`, then
   `:kind`, for agents that don't populate the claude-code `_meta`."
  [{:keys [title kind] :as src}]
  (or (get-in src [:_meta :claudeCode :toolName])
      title
      (some-> kind name)
      "tool"))

(defmethod dispatch-update "tool_call"
  [{:keys [toolCall sessionId] :as params}]
  ;; First time we see a tool call — fire :pre. Subsequent updates
  ;; (status: completed | failed) fire :post via tool_call_update.
  ;; Real ACP carries the fields inline in the update; the stub nests
  ;; them under :toolCall — accept either.
  ;; Keys mirror the hooks event catalog for :agent.tool-use/pre —
  ;; {:tool-name :args :call-id …} — so the TUI's tool-batch renderer picks
  ;; up the arguments and correlates the later /post by :call-id.
  (let [{:keys [toolCallId status rawInput] :as src} (or toolCall params)]
    {:event event-tool-use-pre
     :data  {:call-id    toolCallId
             :tool-name  (acp-tool-name src)
             :args       (or rawInput {})
             :status     (or status "in_progress")
             :session-id sessionId
             :observer?  true}}))

(defn- strip-lone-code-fence
  "Unwrap ```…``` when it encloses the WHOLE text, else return it unchanged.

   claude-code fences its tool errors (`\"```\\nReading file failed: …\\n```\"`),
   which in a box that is already a box reads as two literal ``` lines, and on
   the head line the opening fence eats 4 of the 40 preview characters before
   the message starts.

   Deliberately conservative: only a text that both begins and ends with a
   fence is unwrapped, so a result mixing prose with a code block keeps its
   fences and still renders as markdown wherever that matters."
  [text]
  (let [t (str/trim text)]
    (if (and (str/starts-with? t "```")
             (str/ends-with? t "```")
             (> (count t) 6)
             ;; Exactly two fence markers — otherwise this is prose containing
             ;; several code blocks, not one fenced block.
             (= 2 (count (re-seq #"(?m)^```" t))))
      (-> t
          (str/replace #"(?s)\A```[^\n]*\n?" "")
          (str/replace #"(?s)\n?```\z" ""))
      text)))

(defn- tool-content-text
  "Display text for ONE `ToolCallContent` entry, or nil when the entry carries
   no text (a `diff`, which is structured separately). Handles both the spec
   wrapper (`{:type \"content\" :content <ContentBlock>}`) and a bare
   ContentBlock, which the in-tree stub and some agents emit directly."
  [block]
  (when (map? block)
    (case (:type block)
      "content"  (let [t (strip-lone-code-fence (content-block-text (:content block)))]
                   (when-not (str/blank? t) t))
      "diff"     nil
      "terminal" (str "[terminal " (:terminalId block) "]")
      ;; Bare ContentBlock, or a variant added to the spec after this was
      ;; written. `content-block-text` returns "" for anything it can't read,
      ;; and we'd rather show a pr-str than silently drop the entry.
      (let [t (content-block-text block)]
        (if (str/blank? t) (pr-str block) t)))))

(defn normalize-tool-content
  "Flatten a tool call's raw `ToolCallContent[]` into the display-ready shape
   the rest of brainyard consumes: `{:text <joined prose> :diffs [{…}]}`.

   The raw vector must never reach a renderer — `pr-str`'d it reads as
   `[{:type \"content\", :content {:type \"text\", :text \"…\"}}]`, i.e. the
   wire envelope where the tool's output belongs. This mirrors why the `plan`
   translation above normalizes PlanEntry into the native todo shape.

   `:text` is nil when no entry carried prose; `:diffs` is omitted when empty,
   so callers can `cond->` on presence."
  [content]
  (let [blocks (cond
                 (nil? content)    []
                 (sequential? content) (vec content)
                 :else             [content])
        text   (->> blocks (keep tool-content-text) (str/join "\n"))
        diffs  (->> blocks
                    (filter #(and (map? %) (= "diff" (:type %))))
                    (mapv (fn [d] {:path (:path d)
                                   :old  (:oldText d)
                                   :new  (:newText d)})))]
    (cond-> {}
      (not (str/blank? text)) (assoc :text text)
      (seq diffs)             (assoc :diffs diffs))))

(defmethod dispatch-update "tool_call_update"
  [{:keys [toolCall sessionId] :as params}]
  (let [{:keys [toolCallId status content rawOutput] :as src} (or toolCall params)]
    (case status
      ("completed" "failed")
      (let [{:keys [text diffs]} (normalize-tool-content content)]
        {:event event-tool-use-post
         :data  {:call-id    toolCallId
                 :tool-name  (acp-tool-name src)
                 ;; `:error` is a STRING on the failure path, not the raw
                 ;; vector: the TUI's `error?` test is `(some? (:error result))`
                 ;; (so the red Error box still triggers) and the head line
                 ;; truncates it to 40 chars — which is only readable if it is
                 ;; prose. The raw vector survives under `:acp/content` for
                 ;; persistence and debugging, but never drives display.
                 :result     (cond-> {:status status}
                               ;; Always present on failure, even when the
                               ;; agent reported no detail — an absent `:error`
                               ;; would read as success and render a green
                               ;; `done` marker for a call that failed.
                               (= status "failed")
                               (assoc :error (or text "tool call failed"))

                               (and (= status "completed") text) (assoc :output text)
                               (seq diffs)                       (assoc :diffs diffs)
                               (some? rawOutput)                 (assoc :raw-output rawOutput)
                               (some? content)                   (assoc :acp/content content))
                 :session-id sessionId}})

      ;; status pending or in_progress (or absent) — observer-only update,
      ;; no hook fired. The dispatcher may still surface progress to UIs
      ;; via the raw notification.
      nil)))

;; =============================================================================
;; Stop-reason translation
;;
;; Called when the session/prompt response arrives. One ACP turn maps
;; to one iteration boundary (open decision 6 from §9.2).
;; =============================================================================

(defn translate-stop-reason
  "Return a hook event descriptor for an end-of-turn signal."
  [stop-reason session-id]
  (case stop-reason
    "end_turn"
    {:event event-iteration-post
     :data  {:goal-achieved true :session-id session-id :stop-reason stop-reason}}

    "cancelled"
    {:event event-iteration-exhausted
     :data  {:reason :cancelled :session-id session-id :stop-reason stop-reason}}

    ;; max_tokens, max_turn_requests, refusal — treated as unsuccessful
    ;; iteration ends; surface as :iteration-exhausted with the reason.
    ("max_tokens" "max_turn_requests" "refusal")
    {:event event-iteration-exhausted
     :data  {:reason     (keyword stop-reason)
             :session-id session-id
             :stop-reason stop-reason}}

    ;; Unknown stop reason — caller decides what to do.
    nil))

;; =============================================================================
;; Iteration boundary helpers (called by the session module on prompt!)
;; =============================================================================

(defn iteration-pre-event
  "Built when a new prompt starts."
  [session-id prompt]
  {:event event-iteration-pre
   :data  {:session-id session-id
           :prompt     prompt}})
