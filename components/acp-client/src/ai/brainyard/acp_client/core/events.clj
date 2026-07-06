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
  [{:keys [entries sessionId]}]
  {:event event-todo-updated
   :data  {:todo-list  (mapv (fn [e]
                               {:content (:content e)
                                :status  (or (:status e) "pending")
                                :priority (:priority e)})
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

(defmethod dispatch-update "tool_call_update"
  [{:keys [toolCall sessionId] :as params}]
  (let [{:keys [toolCallId status content] :as src} (or toolCall params)]
    (case status
      ("completed" "failed")
      {:event event-tool-use-post
       :data  {:call-id    toolCallId
               :tool-name  (acp-tool-name src)
               :result     (cond-> {:status status}
                             (= status "failed")    (assoc :error content)
                             (= status "completed") (assoc :content content))
               :session-id sessionId}}

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
