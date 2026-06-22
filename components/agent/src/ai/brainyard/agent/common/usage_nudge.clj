;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.common.usage-nudge
  "Just-in-time surfacing of `(usage :topic)` guides.

   The on-demand guides are PULL-based: the system prompt lists them once and the
   model must remember to fetch one. In practice it rarely does. This namespace
   PUSHES the relevant guide to the model at the moment of need:

   - **First-use auto-inline** — the first time the model invokes a guide-backed
     tool family this session, the family's full guide is folded into the NEXT
     iteration's context (once per topic).
   - **Error-triggered** — if that first call errored, the same guide rides the
     feedback under an `(that call errored)` header. This covers both runtime
     failures (the tool ran and returned `:error`) and arg-validation rejections
     (a malformed call that never dispatched — see the `:agent.tool-use/rejected`
     hook below), which is exactly when the guide helps most.
   - **Permanent inline** — topics named in config `:inline-usage-guides` are
     rendered into the agent's tool-context every turn and pre-marked as shown,
     so the JIT path never re-surfaces them (no duplication).

   Mechanism: an observer on `:agent.tool-use/post` (and `:agent.tool-use/rejected`
   for malformed calls that short-circuit before dispatch) records first use into
   the agent's cross-turn `st-memory-init` (`:usage-tips-shown`, once per session)
   and queues a pending guide into the per-turn `bt-st-memory`
   (`:pending-usage-guides`). `coact-accumulate-iteration-action` drains that
   queue when it builds the iteration record, attaching the guide as the record's
   `:notices` field — which the model sees via DSPy serialization of `:iterations`.

   Code-channel tool calls route through `tool/call-tool` → the same hook chain,
   so SCI-sandbox invocations (CoAct's preferred channel) trigger this too."
  (:require [ai.brainyard.agent.core.hooks :as hooks]
            [ai.brainyard.agent.core.protocol :as proto]
            [ai.brainyard.clj-sandbox.interface :as clj-sandbox]
            [ai.brainyard.mulog.interface :as mulog]
            [clojure.string :as str]))

(def tool-family->topic
  "Tool-id family segment (text before the first `$`) → the usage topic whose
   guide explains it. Deliberately conservative — only families that are easy to
   misuse AND have a dedicated guide. Edit here to tune coverage."
  {"artifact" :artifacts
   "memory"   :memory
   "plan"     :plans
   "query"    :llm-query
   "skills"   :skills
   "todo"     :todo
   "mcp"      :mcp})

(defn topic-for-tool
  "Resolve the usage topic for `tool-name` (string/keyword/symbol), or nil when
   the tool's family isn't guide-backed."
  [tool-name]
  (when tool-name
    (let [s      (name tool-name)
          family (if-let [i (str/index-of s "$")] (subs s 0 i) s)]
      (get tool-family->topic family))))

(defn- init-atom [agent] (some-> agent proto/get-st-memory-init))
(defn- bt-atom   [agent] (some-> agent proto/get-bt-st-memory))

(defn- result-error?
  "True when a tool result map signals failure (the two shapes the tool layer
   emits)."
  [result]
  (and (map? result)
       (boolean (or (:error result) (:error-message result)))))

(defn- suppressed?
  "A topic is suppressed once it has been surfaced this session OR is permanently
   inlined in the agent's tool-context (so the JIT path never duplicates it)."
  [agent topic]
  (let [m (some-> (init-atom agent) deref)]
    (boolean (or (contains? (set (:usage-tips-shown m)) topic)
                 (contains? (set (:usage-inlined-topics m)) topic)))))

(defn note-tool-use!
  "Record a guide-backed tool invocation. On the first un-suppressed use of a
   family this session, mark the topic shown (cross-turn) and queue its guide for
   the next iteration (per-turn). No-op for unguided tools, suppressed topics, or
   when the agent has no cross-turn store."
  [agent tool-name result]
  (when-let [topic (topic-for-tool tool-name)]
    (when (and (init-atom agent) (not (suppressed? agent topic)))
      (let [reason (if (result-error? result) :error :first-use)]
        (swap! (init-atom agent) update :usage-tips-shown (fnil conj #{}) topic)
        (some-> (bt-atom agent)
                (swap! update :pending-usage-guides (fnil conj [])
                       {:topic topic :reason reason :tool (name tool-name)}))
        (mulog/log ::queued :topic topic :reason reason :tool (name tool-name))))))

(defn- on-tool-post [{:keys [agent tool-name result]}]
  (try (note-tool-use! agent tool-name result)
       (catch Exception e
         (mulog/warn ::on-tool-post-error :error (.getMessage e))))
  nil)

(defonce ^:private !installed (atom false))

(defn ensure-global-hooks!
  "Install the `:agent.tool-use/post` observer once per process at RUNTIME
   (guarded by a runtime atom so native-image bakes `false` and the first real
   turn installs). Safe to call every turn."
  []
  (when (compare-and-set! !installed false true)
    (hooks/register-hook! :agent.tool-use/post ::usage-nudge on-tool-post
                          :source :usage-nudge)
    ;; Arg-validation rejections never reach :agent.tool-use/post (they
    ;; short-circuit before dispatch), so also listen for the rejected event —
    ;; a malformed FIRST call to a family is exactly when the guide helps most.
    (hooks/register-hook! :agent.tool-use/rejected ::usage-nudge-rejected on-tool-post
                          :source :usage-nudge)
    (mulog/info ::global-hooks-installed))
  nil)

(defn seed-inlined-topics!
  "Pre-mark permanently-inlined guide topics as shown so the JIT path skips them.
   Idempotent — call from coact-init each turn with the resolved
   `:inline-usage-guides` topic list."
  [agent topics]
  (when (and (init-atom agent) (seq topics))
    (swap! (init-atom agent) update :usage-inlined-topics
           (fnil into #{}) (map keyword topics)))
  nil)

(defn- guide-text [topic]
  (clj-sandbox/get-usage-guide (keyword topic)))

(defn- render-pending [{:keys [topic reason tool]}]
  (when-let [g (guide-text topic)]
    (str "💡 Usage guide — first use of `" tool "` "
         (if (= reason :error) "this turn (that call errored)" "this session")
         ". Read it before continuing:\n\n"
         g
         "\n\n(Re-read anytime with `(usage " topic ")`.)")))

(defn drain-iteration-notices!
  "Drain + clear this turn's queued guides from the per-turn `st-memory`, returning
   the rendered notice string (guide blocks joined), or nil when none are pending.
   Called as the iteration record is assembled so the guide rides the very next
   iteration the model reads."
  [st-memory]
  (when st-memory
    (when-let [pending (seq (:pending-usage-guides @st-memory))]
      (swap! st-memory dissoc :pending-usage-guides)
      (let [blocks (keep render-pending pending)]
        (when (seq blocks) (str/join "\n\n" blocks))))))

(defn inline-guides-overlay
  "Render the full guide text for `topics` as a tool-context sub-section, or nil
   when none resolve. Appended to the agent's `:tool-context` so the guides are
   permanently present in `## Tools`."
  [topics]
  (let [blocks (keep (fn [t]
                       (when-let [g (guide-text t)]
                         g))
                     (distinct (map keyword topics)))]
    (when (seq blocks)
      (str "These guides are always in context for this agent — no `(usage)` "
           "call needed:\n\n"
           (str/join "\n\n" blocks)))))
