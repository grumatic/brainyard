;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.clj-llm.core.acp
  "ACP (Agent Client Protocol) provider — drives an external
   ACP-conformant agent via stdio + JSON-RPC and presents a chat
   completion surface compatible with the rest of clj-llm.

   This provider is intentionally narrow: it squashes one ACP turn
   into a single `chat-completion` result, flattening away plans,
   tool-call lifecycle, and permission prompts. For the full ACP UX
   (streamed plans, tool calls, permissions in the TUI) callers should
   use the `acp-agent` defagent (Phase 5 of docs/design/acp-design.md) — that
   path bypasses clj-llm and lets the external agent own its loop.

   ## Soft coupling

   This namespace must not pull `ai.brainyard.acp-client` in at
   compile time. Doing so would force `clj-llm/deps.edn` to add
   `ai.brainyard/acp-client` as a hard dep, polluting any clj-llm
   consumer that doesn't use ACP. We use `requiring-resolve` instead
   — same pattern `core/llm.clj` uses for the `oauth` namespace at
   call time. Callers who pick `:acp` provider are responsible for
   ensuring `ai.brainyard/acp-client` is on the classpath at runtime.

   ## Backends

   `:backend` on the lm-config selects the ACP agent subprocess:
     :stub                — in-tree stub agent (CI default)
     :claude-agent-acp    — Phase 6: npx @agentclientprotocol/claude-agent-acp
     :gemini / :codex / … — Phase 6: launch specs added to acp-client/registry

   ## Caching

   Phase 4 spawns a fresh subprocess per call. Per the per-LM cache
   design in §4.3 of docs/design/acp-design.md, a future revision will cache
   AcpClients keyed by `(provider, backend, model, config-hash)` (Open
   Decision 5) for conversational continuity. Stub testing doesn't
   need it; real backends with multi-second startup will."
  (:require [clojure.string :as str]
            [ai.brainyard.mulog.interface :as mulog]))

;; =============================================================================
;; Soft-resolve into ai.brainyard.acp-client.interface
;;
;; We never (:require ...) acp-client at the top of this ns. Each
;; helper resolves the var at first call and caches the lookup. If
;; acp-client is not on the classpath, callers see a clear error
;; rather than a confusing compile-time failure.
;; =============================================================================

(def ^:private acp-client-ns 'ai.brainyard.acp-client.interface)

(defn- acp-client-fn
  "Resolve a var from acp-client/interface. Throws with a helpful
   message if acp-client is not on the classpath."
  [sym-name]
  (let [v (try (requiring-resolve (symbol (name acp-client-ns) (name sym-name)))
               (catch Exception _ nil))]
    (when-not v
      (throw (ex-info
              (str "ACP provider needs ai.brainyard/acp-client on the classpath; "
                   "could not resolve " acp-client-ns "/" sym-name ". "
                   "Add `ai.brainyard/acp-client {:local/root \"components/acp-client\"}` "
                   "to your project's deps.edn.")
              {:provider :acp :missing (str acp-client-ns "/" sym-name)})))
    v))

(defn- spawn!*           [& args] (apply (acp-client-fn 'spawn!) args))
(defn- initialize!*      [& args] (apply (acp-client-fn 'initialize!) args))
(defn- new-session!*     [& args] (apply (acp-client-fn 'new-session!) args))
(defn- prompt!*          [& args] (apply (acp-client-fn 'prompt!) args))
(defn- close!*           [& args] (apply (acp-client-fn 'close!) args))
(defn- translate-update* [& args] (apply (acp-client-fn 'translate-update) args))

;; =============================================================================
;; Message flattening
;;
;; ACP content blocks are richer than what we expose here, but a
;; clj-llm chat-completion call deals in plain text messages. We
;; collapse them into one labeled text block — same trick claude-code
;; uses. A future iteration may surface multimodal blocks for callers
;; that pass content vectors instead of strings.
;; =============================================================================

(defn- flatten-messages
  "Concatenate chat messages into a single labeled-text prompt."
  [messages]
  (let [system-msgs (filter #(= "system" (:role %)) messages)
        other-msgs  (remove #(= "system" (:role %)) messages)
        system-text (when (seq system-msgs)
                      (str/join "\n\n" (map :content system-msgs)))
        body (cond
               (= 1 (count other-msgs))
               (:content (first other-msgs))

               :else
               (->> other-msgs
                    (map (fn [{:keys [role content]}]
                           (str "[" role "]: " content)))
                    (str/join "\n\n")))]
    (cond->> body
      (seq system-text) (str system-text "\n\n"))))

;; =============================================================================
;; Streaming bridge
;;
;; We hook a closure into the AcpClient's `:on-event`. Each
;; `session/update` notification flows in; we filter to text chunks,
;; accumulate into a StringBuilder, and forward to the caller's
;; `on-chunk` callback in the {:type :content-delta :text …} shape
;; that claude-code already uses.
;; =============================================================================

(defn- make-on-event
  "Build an :on-event callback that:
     - accumulates text from agent_message_chunk events,
     - forwards each delta to `on-chunk` (when non-nil) using the
       claude-code-compatible {:type :content-delta :text …} shape."
  [^StringBuilder accumulator on-chunk]
  (fn [msg]
    (when (= "session/update" (:method msg))
      (when-let [evt (translate-update* (:params msg))]
        (when (and (= :agent.dspy-action/chunk (:event evt))
                   ;; Skip thoughts — chat-completion contract is text-only.
                   (not= :thought (-> evt :data :meta :kind)))
          (let [chunk (-> evt :data :chunk)]
            (when (seq chunk)
              (.append accumulator ^String chunk)
              (when on-chunk
                (on-chunk {:type :content-delta :text chunk})))))))))

;; =============================================================================
;; Public API
;; =============================================================================

(defn chat-completion-stream
  "ACP chat completion, streaming variant.

   Spawns an ACP backend subprocess, opens a session, sends one
   `session/prompt` with the flattened messages, accumulates streamed
   text chunks, and returns a response map shaped like the
   claude-code provider's:

     {:content [{:type \"text\" :text result}]
      :model   m
      :usage   {:input_tokens N :output_tokens N}
      :stop-reason \"end_turn\"|\"cancelled\"|…}

   Calls `on-chunk` (when non-nil) once per text delta with
   `{:type :content-delta :text …}` and once at the end with
   `{:type :done :usage {…}}`. Matches the claude-code contract so
   downstream callers (chain-of-thought, predict) work unchanged.

   `:backend` on lm-config selects the ACP backend — defaults to
   `:stub`. `:chunk-delay-ms` (test-only) is forwarded to the stub
   for cancel-test pacing."
  [lm-config messages opts on-chunk]
  (let [backend       (or (:backend lm-config) :stub)
        backend-opts  (select-keys lm-config [:chunk-delay-ms])
        text-prompt   (flatten-messages messages)
        accumulator   (StringBuilder.)
        on-event      (make-on-event accumulator on-chunk)
        start-ns      (System/nanoTime)
        client        (spawn!* backend
                               {:on-event     on-event
                                :backend-opts backend-opts})]
    (mulog/log ::acp-call
               :provider :acp
               :backend  backend
               :model    (:model lm-config)
               :stream   true)
    (try
      (initialize!* client)
      (let [sess        (new-session!* client)
            timeout-ms  (or (:timeout-ms opts) 600000)
            result      (prompt!* sess
                                  [{:type "text" :text text-prompt}]
                                  {:timeout-ms timeout-ms})
            stop-reason (:stop-reason result)
            duration-ms (quot (- (System/nanoTime) start-ns) 1000000)
            text        (str accumulator)]
        (when on-chunk
          (on-chunk {:type :done :usage {}}))
        (mulog/log ::acp-call-result
                   :provider :acp
                   :backend  backend
                   :model    (:model lm-config)
                   :duration-ms duration-ms
                   :stop-reason stop-reason
                   :text-length (count text))
        {:content     [{:type "text" :text text}]
         :model       (:model lm-config)
         :usage       {:input_tokens 0 :output_tokens 0}
         :stop-reason stop-reason})
      (finally
        (try (close!* client)
             (catch Throwable t
               (mulog/warn ::acp-close-error :error (ex-message t))))))))

(defn chat-completion
  "ACP chat completion, non-streaming variant — delegates to the
   streaming impl with a nil on-chunk callback. The ACP wire is
   inherently streaming, so a 'non-streaming' call just buffers the
   chunks internally."
  [lm-config messages opts]
  (chat-completion-stream lm-config messages opts nil))
