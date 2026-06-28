;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.core.system-info
  "Builds the stable `:system-info` and per-turn `:turn-info` prompt sections.

   `:system-info` is byte-stable across all turns in a session for the same
   agent / model / working directory. It lives in `:system-context`
   (priority 98), above the cross-turn cache breakpoint. Host, workspace,
   LLM, and session identity — nothing that changes turn-to-turn.

   `:turn-info` is rendered every turn but byte-identical across all
   iterations within a turn. It lives in `:user-context` (priority 88),
   below the cross-turn cache breakpoint and above the within-turn one.
   Date / time / turn-id only.

   Together the two sections answer questions like \"what's today's date?\",
   \"is this macOS?\", \"am I in a container?\", \"what timezone is the user
   in?\" — which the LLM otherwise has no way to know.

   See `docs/design/context-management.md` §P4 for the design rationale."
  (:require [ai.brainyard.agent.common.sandbox-bindings :as sb-bind]
            [ai.brainyard.agent.core.config :as config]
            [ai.brainyard.agent.core.protocol :as proto]
            [ai.brainyard.agent.core.timeutil :as timeutil]
            [clojure.string :as str])
  (:import (java.time Instant ZoneId ZonedDateTime)))

;; ============================================================================
;; env-detect — soft dependency (loaded only when on the classpath)
;; ============================================================================

(defn- detect-os
  "Pure JVM property reads — same shape as env-detect/detect-os but without
   the soft-dep cost. Returns {:name :version :arch}."
  []
  {:name    (System/getProperty "os.name")
   :version (System/getProperty "os.version")
   :arch    (System/getProperty "os.arch")})

(defn- detect-sandbox-environment*
  "Soft-dep call to env-detect/detect-sandbox-environment. Returns
   nil when the env-detect component is not on the classpath."
  []
  (try
    (when-let [f (requiring-resolve
                  'ai.brainyard.env-detect.interface/detect-sandbox-environment)]
      (f))
    (catch Throwable _ nil)))

;; ============================================================================
;; OS / Host
;; ============================================================================

(defn- friendly-os-name
  [raw]
  (case raw
    "Mac OS X" "macOS"
    raw))

(defn- host-rows
  "Return Host subsection lines as a vector of strings (no header)."
  []
  (let [os (detect-os)
        sandbox (detect-sandbox-environment*)
        sandbox-type (:sandbox-type sandbox)
        shell (or (System/getenv "SHELL") "/bin/sh")
        locale (or (System/getenv "LANG") "C")]
    (cond-> [(str "- OS: " (friendly-os-name (:name os))
                  " " (:version os)
                  " (" (:name os) ", " (:arch os) ")")
             (str "- Shell: " shell)
             (str "- Locale: " locale)]
      (and sandbox-type (not= :host sandbox-type))
      (conj (str "- Sandbox: " (name sandbox-type))))))

;; ============================================================================
;; Workspace
;; ============================================================================

(defn- workspace-rows
  [agent]
  (let [dirs (when agent (sb-bind/get-dirs agent))
        wd (or (:working-dir dirs)
               (System/getProperty "user.dir"))
        ;; project-root: prefer dirs :project-dir (git-root walk), else
        ;; fall back to wd. The previous code used `:project-root` which
        ;; is never set anywhere in the codebase, so this row always
        ;; rendered as the working-dir — under bb tui that's the subdir,
        ;; not the canonical brainyard root.
        project-root (or (:project-dir dirs) wd)
        home (System/getProperty "user.home")
        user (or (System/getProperty "user.name")
                 (System/getenv "USER"))]
    [(str "- Working directory: " wd)
     (str "- Project root: " project-root)
     (str "- Home: " home)
     (str "- User: " user)]))

;; ============================================================================
;; LLM
;; ============================================================================

(defn- llm-rows
  [agent]
  (let [lm (when agent (config/get-config agent :lm-config))
        provider (some-> (:provider lm) name)
        model (:model lm)
        max-context (config/get-config agent :max-context-tokens)
        max-output (or (:max-tokens lm) 4096)]
    [(str "- Provider: " (or provider "<unset>")
          " · Model: " (or model "<unset>"))
     (str "- Context window: " max-context " tokens"
          " · Max output: " max-output " tokens")]))

;; ============================================================================
;; Runtime — behavior-governing knobs the LLM acts under this turn
;; ============================================================================

(defn- runtime-rows
  "Render the `### Runtime` subsection: the handful of config knobs that change
   how the agent behaves this turn (iteration budget, permission policy, the
   code-eval backend + effective interop level, execution backend). These rarely
   change within a session, so the section stays byte-stable for the cross-turn
   cache. The full config is NOT dumped here — the long tail is discoverable on
   demand via the `agent-runtime$config` :query search. No secrets are read."
  [agent]
  (let [max-iter   (config/get-config agent :max-iterations)
        perm-mode  (config/get-config agent :permission-mode)
        clj-be     (config/get-config agent :clj-backend)
        ;; resolve :auto → :restricted|:full so the line reflects reality
        interop    (config/resolve-sandbox-interop agent)
        exec-be    (config/get-config agent :exec-backend)]
    [(str "- Iteration budget: " max-iter " max per turn")
     (str "- Permissions: " (some-> perm-mode name))
     (str "- Code eval: " (some-> clj-be name) " backend, "
          (some-> interop name) " interop")
     (str "- Exec backend: " (some-> exec-be name))]))

;; ============================================================================
;; Session
;; ============================================================================

(defn- session-rows
  [agent depth parent-agent-id]
  (let [agent-id (when agent (proto/agent-id agent))
        session-id (when agent (proto/session-id agent))
        zone-id (str (ZoneId/systemDefault))
        depth (or depth 0)
        ;; Map zone-id to UTC offset for readability.
        offset (try
                 (let [zoned (ZonedDateTime/now (ZoneId/systemDefault))]
                   (str (.getOffset zoned)))
                 (catch Exception _ ""))
        agent-line (str "- Agent: " (or agent-id "<unknown>")
                        (cond
                          (zero? depth) " (root, depth 0)"
                          :else (str " (depth " depth ")")))
        parent-line (when (and parent-agent-id (pos? depth))
                      (str "- Parent: " parent-agent-id))]
    (cond-> [agent-line]
      parent-line (conj parent-line)
      true (conj (str "- Session: " (or session-id "<unknown>")))
      true (conj (str "- Timezone: " zone-id
                      (when (seq offset) (str " (UTC" offset ")")))))))

;; ============================================================================
;; Public — :system-info
;; ============================================================================

(defn build-system-info-section
  "Render the stable `:system-info` section for the current agent.

   Inputs (all optional):
     :agent            - the agent instance. Drives workspace/LLM/session
                          rows. When nil, those rows fall back to
                          JVM-/env-derived defaults.
     :depth            - integer depth of this agent in the call stack
                          (0 = root). When omitted, treated as 0.
     :parent-agent-id  - parent agent's id keyword. When non-nil AND
                          depth > 0, a `Parent: <id>` row is added under
                          the agent identity (M9).

   The output string is byte-stable across all turns in a session for the
   same agent + model + working directory — that's what makes the
   cross-turn prompt cache valid above the cross-turn breakpoint."
  [& {:keys [agent depth parent-agent-id]}]
  (let [hdr "## System Information"
        host (host-rows)
        ws (workspace-rows agent)
        llm (llm-rows agent)
        runtime (runtime-rows agent)
        sess (session-rows agent depth parent-agent-id)]
    (str/join "\n"
              (concat [hdr
                       ""
                       "### Host"]
                      host
                      [""
                       "### Workspace"]
                      ws
                      [""
                       "### LLM"]
                      llm
                      [""
                       "### Runtime"]
                      runtime
                      [""
                       "### Session"]
                      sess))))

;; ============================================================================
;; Public — :turn-info
;; ============================================================================

(defn build-turn-info-section
  "Render the per-turn `:turn-info` section.

   Inputs (all optional):
     :turn-id     - 1-based per-agent turn id
     :total-turns - session-wide turn counter
     :now         - java.time.Instant (default: current wall clock)
     :tz          - java.time.ZoneId (default: system zone)

   The clock is sourced from `timeutil/instant->map`, so the rendered `- Now:`
   line is byte-for-byte consistent with the `time$now` tool (same ISO format,
   truncated to whole seconds). Output is small (~3 lines) and byte-identical
   across all iterations within the same turn — the within-turn prompt cache
   stays valid."
  [& {:keys [turn-id total-turns now tz]}]
  (let [now (or now (Instant/now))
        tz (or tz (ZoneId/systemDefault))
        m (timeutil/instant->map now tz)
        iso (:iso m)
        dow (:day-of-week m)
        turn (or turn-id 1)
        total (or total-turns turn)]
    (str/join "\n"
              ["## Turn"
               ""
               (str "- Now: " iso " (" dow ")")
               (str "- Turn: " turn " (session total: " total ")")])))

;; Wall-clock access from sandbox code / the LLM is the `time$now` tool
;; (see `core.timeutil` + `common.tools`), which replaced the bespoke `(now)`
;; sandbox binding that used to live here as `now-snapshot`.
