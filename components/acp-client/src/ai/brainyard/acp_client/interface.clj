;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.acp-client.interface
  "Public API for the ACP client.

   Lifecycle:

     (def client (acp-client/spawn! :stub
                                    {:on-event #(prn :event %)}))
     (acp-client/initialize! client)
     (def sess (acp-client/new-session! client))
     (def result (acp-client/prompt-text! sess \"hello\"))
     (acp-client/close! client)

   Layer notes:

   - `:on-event` receives raw ACP `session/update` notification
     messages. To translate them to brainyard hook event descriptors,
     pass each through `translate-update`. To actually fire hooks,
     wire your own hook system in (Phase 5 does this from the
     `acp-agent` defagent in components/agent).

   - Permission, fs/read, fs/write reverse calls land in the
     `:callbacks` handlers. Defaults reject permission requests and
     read/write only absolute paths from disk. Callers supply
     overrides via `(spawn! :stub {:callbacks {…}})`.

   This component depends only on `acp + util + mulog`. It does
   **not** depend on `agent` — wiring to brainyard hooks happens at
   the call site (Phase 5)."
  (:require [ai.brainyard.acp.interface :as acp]
            [ai.brainyard.acp-client.core.callbacks :as callbacks]
            [ai.brainyard.acp-client.core.client :as client]
            [ai.brainyard.acp-client.core.events :as events]
            [ai.brainyard.acp-client.core.registry :as registry]
            [ai.brainyard.acp-client.core.session :as session]))

;; =============================================================================
;; Lifecycle
;; =============================================================================

(defn spawn!
  "Spawn an ACP backend subprocess and return an opened (but not yet
   initialized) AcpClient.

   `backend` is a registry keyword (`:stub`) or a launch-spec map
   `{:command [...] :working-dir str :env {...}}` for ad-hoc backends.

   `opts`:
     :on-event   — fn(notification-msg) for `session/update` events
     :callbacks  — overrides for reverse-call handlers (see callbacks.clj)
     :backend-opts — passed through to the registry factory (e.g. `:chunk-delay-ms`)"
  ([backend] (spawn! backend {}))
  ([backend {:keys [on-event callbacks backend-opts]}]
   (let [launch-spec (if (map? backend)
                       backend
                       (registry/resolve-backend backend (or backend-opts {})))
         transport   (acp/create-stdio-transport launch-spec)
         c           (client/create {:transport transport
                                     :on-event  on-event
                                     :callbacks callbacks})]
     (client/open! c))))

(defn initialize!
  "Run the ACP `initialize` handshake. Returns the agent's result map."
  ([client] (client/initialize! client))
  ([client opts] (client/initialize! client opts)))

(defn close!
  "Stop the dispatcher pump and tear down the subprocess."
  [client]
  (client/close! client))

(defn open?
  "True while the client is alive."
  [client]
  (client/open? client))

(defn server-info
  "Return the agent's `initialize` result, or nil before initialize."
  [client]
  (client/server-info client))

;; =============================================================================
;; Sessions
;; =============================================================================

(defn new-session!
  "Open a new ACP session. Returns a session map `{:session-id :client}`."
  ([client] (session/new! client))
  ([client opts] (session/new! client opts)))

(defn prompt!
  "Send `session/prompt` and block for the result. `content` is a
   vector of ACP content blocks."
  ([sess content] (session/prompt! sess content))
  ([sess content opts] (session/prompt! sess content opts)))

(defn prompt-text!
  "Convenience for a single-text-block prompt."
  ([sess text] (session/prompt-text! sess text))
  ([sess text opts] (session/prompt-text! sess text opts)))

(defn cancel!
  "Cancel the in-flight prompt for this session."
  ([sess] (session/cancel! sess))
  ([sess opts] (session/cancel! sess opts)))

(defn set-model!
  "Select the session's model via ACP `session/set_model`. `model-id`
   must be one advertised in the `new-session!` result's `:models`
   (resolve user input with `resolve-model-id` first)."
  ([sess model-id] (session/set-model! sess model-id))
  ([sess model-id opts] (session/set-model! sess model-id opts)))

(defn resolve-model-id
  "Fuzzy-match a user model string against a session's advertised
   `:models :availableModels`; returns the matched `:modelId` or nil."
  [available-models model]
  (session/resolve-model-id available-models model))

(defn iteration-pre-event
  "Build an iteration/pre event descriptor for `prompt!` callers that
   want to fire it through their own hook system."
  [sess prompt]
  (session/iteration-pre-event sess prompt))

;; =============================================================================
;; Translation (re-exported)
;; =============================================================================

(defn translate-update
  "Translate a session/update notification's params to a brainyard
   hook event descriptor `{:event :data}`, or nil if no event applies."
  [params]
  (events/translate-update params))

(defn translate-stop-reason
  "Translate a stop-reason string into a brainyard hook event descriptor."
  [stop-reason session-id]
  (events/translate-stop-reason stop-reason session-id))

;; =============================================================================
;; Permission / callback utilities (re-exported)
;; =============================================================================

(defn pick-option-id
  "Map a brainyard permission decision (`:allow`, `:block`, etc.) to
   one of the agent-supplied permission option ids per §9.2 fallback
   policy."
  [decision options]
  (callbacks/pick-option-id decision options))

(def ^{:doc "Default reverse-call handlers (fs read/write + permission deny)."}
  default-callbacks callbacks/default-callbacks)

;; =============================================================================
;; Registry — exposed so callers can introspect and add backends later.
;; =============================================================================

(defn find-workspace-root
  "Walk up from cwd to find workspace.edn. Used by registry entries
   that need to resolve in-tree paths."
  ([] (registry/find-workspace-root))
  ([start-dir] (registry/find-workspace-root start-dir)))

(defn resolve-backend
  "Resolve a registered backend keyword into a launch spec."
  ([backend] (registry/resolve-backend backend))
  ([backend opts] (registry/resolve-backend backend opts)))

(defn list-backends
  "Return the registered backends as `{kw {:description :experimental :prereqs}}`."
  []
  (registry/list-backends))

(defn register-backend!
  "Register or replace a backend at runtime.

   Args:
     backend-key   Keyword identifier (e.g. :my-fork-of-claude).
     factory       (fn [opts] -> launch-spec-map). See registry.clj
                   for the launch-spec shape.

   Optional kwargs:
     :description    One-line summary (string).
     :experimental   Boolean (default true).
     :prereqs        Vec of executable names whose PATH presence
                     indicates the backend is usable."
  [backend-key factory & opts]
  (apply registry/register-backend! backend-key factory opts))

(defn unregister-backend!
  "Remove a backend from the registry. Refuses to remove :stub."
  [backend-key]
  (registry/unregister-backend! backend-key))

(defn backend-available?
  "Check whether a registered backend's prereq executables are on PATH.
   Returns `{:status :ok | :missing-prereqs | :unregistered ...}`."
  [backend-key]
  (registry/backend-available? backend-key))

(defn which
  "Return the absolute path of `cmd` on PATH, or nil. Useful for
   skipping integration tests when a backend's prereq isn't installed."
  [cmd]
  (registry/which cmd))
