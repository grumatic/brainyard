;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.common.debug-agent
  "Live-runtime debug specialist.

   Drives the §7.2 self-debugging loop from docs/design/clj-nrepl-eval.md
   against the running brainyard JVM via clj-nrepl. Sibling to
   explore-agent and exec-agent; CoAct-derived, so the BT loop, hooks,
   and channel discipline come for free.

   Per-instance lifecycle:
   - On :agent.instance/created — if the in-process nREPL server is up,
     open a server-issued session and pin it on this instance's
     per-agent config (`:nrepl-session-id`). Also write
     `:clj-backend :nrepl` so every clojure fence routes to the live
     runtime. CoAct's run-clj-nrepl-block reads both from the agent
     config; there is no per-fence override (the fence accepts only the
     language token).
   - On :agent.instance/closed — close the session.

   Phase 2b scope:
   - Tool bag is tight: code\\$eval + task\\$detail/list/cancel +
     drift inspection. No filesystem/bash/MCP — operator switches to
     coact-agent for broader investigations.
   - No write-to-source path. update-agent hand-off lands in Phase 3.

   Operator pre-requisites — both routes work, in this precedence:
   - .brainyard/config.edn (durable):
       :agent {:config {:nrepl-enabled? true
                        :nrepl-grant    \"read-only:15m\"}}
     (or \"mutate:5m\" for hot-patching). Manage via config-agent's
     config$apply for the snapshot/diff/dossier safety net.
   - BRAINYARD_NREPL_ENABLED / BRAINYARD_NREPL_GRANT env vars (transient
     env-fallback layer of the same schema keys)."
  (:require [ai.brainyard.agent.core.tool :refer [defagent defcommand]]
            [ai.brainyard.agent.core.protocol :as proto]
            [ai.brainyard.agent.core.hooks :as hooks]
            [ai.brainyard.agent.core.config :as cfg]
            [ai.brainyard.agent.common.coact-agent :as coact]
            ;; Loading code-eval ensures `code$eval` is in the registry
            ;; whenever debug-agent is on the classpath — the defagent's
            ;; :agent-tools vector references it.
            [ai.brainyard.agent.common.code-eval]
            [ai.brainyard.clj-nrepl.interface :as clj-nrepl]
            [ai.brainyard.mulog.interface :as mulog]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.time ZonedDateTime ZoneId]
           [java.time.format DateTimeFormatter]))

;; ============================================================================
;; Promotion artifact — bridges runtime mutation → source change request
;; ============================================================================
;;
;; debug-agent never edits source files (§11 Non-Goal). When a hot-patch
;; has been validated, it writes a promotion-request artifact under
;; .brainyard/agents/debug-agent/promotions/<ts>-<slug>.md and tells the operator
;; how to invoke update-agent against it. update-agent's existing
;; :agent-context reader picks up the artifact path, applies the
;; "Proposed source change" code block via its probe→apply→verify pipeline,
;; and emits its own `Saved edit:` / `Rollback:` lines.
;;
;; Phase 3 (clj-nrepl-eval §7.3) — pattern-mode promotions only; syntax-
;; aware rewrites are a follow-up.

(def ^:private ts-formatter
  (DateTimeFormatter/ofPattern "yyyy-MM-dd'T'HH-mm-ss"))

(def ^:private iso-formatter
  (DateTimeFormatter/ofPattern "yyyy-MM-dd'T'HH:mm:ssXXX"))

(defn- now-fmt [fmt]
  (.format ^DateTimeFormatter fmt (ZonedDateTime/now (ZoneId/systemDefault))))

(defn- slug
  "File-safe slug derived from `target-symbol` (or rationale fallback).
   Trims to 60 chars; preserves a stable shape across re-runs of the
   same target."
  [target-symbol]
  (let [base (-> (str target-symbol)
                 (str/replace #"[^A-Za-z0-9-]+" "-")
                 (str/replace #"^-+|-+$" "")
                 (str/lower-case))]
    (if (> (count base) 60) (subs base 0 60) base)))

(defn- promotion-dir
  "Resolve the operator's `.brainyard/agents/debug-agent/promotions/` directory.
   Anchors at `cfg/project-dir` (git-root) — same root the rest of the
   functional agents use. Falls back to `(System/getProperty user.dir)`
   for callers without an agent context (e.g. tests)."
  []
  (let [wd (try
             (cfg/project-dir proto/*current-agent*)
             (catch Throwable _ (System/getProperty "user.dir")))
        dir (io/file wd ".brainyard" "agents" "debug-agent" "promotions")]
    (.mkdirs dir)
    dir))

(defn- format-frontmatter
  [{:keys [created-at debug-session nrepl-session drift-index target-file
           target-symbol edit-mode status]}]
  (str "---\n"
       "created-at: " created-at "\n"
       (when debug-session  (str "debug-session: " debug-session "\n"))
       (when nrepl-session  (str "nrepl-session: " nrepl-session "\n"))
       (when drift-index    (str "drift-marker-index: " drift-index "\n"))
       "target-file: " target-file "\n"
       "target-symbol: " target-symbol "\n"
       "edit-mode: " (name edit-mode) "\n"
       "status: " (name status) "\n"
       "---\n"))

(defn- format-body
  [{:keys [drift-marker pattern replacement rationale validation-evidence
           artifact-path]}]
  (str "\n## What changed in the live runtime\n\n"
       "Drift marker recorded at "
       (some-> drift-marker :timestamp str) ":\n\n"
       "```clojure\n"
       (or (:code-preview drift-marker) "<no drift marker — promotion requested without prior mutation>")
       "\n```\n\n"

       "## Validation evidence\n\n"
       (or validation-evidence "<no validation evidence supplied>")
       "\n\n"

       "## Proposed source change (pattern mode)\n\n"
       "Apply via `update-file` with `:pattern` / `:replacement`.\n\n"
       "### Current (pattern)\n\n"
       "```clojure\n" pattern "\n```\n\n"
       "### Replacement\n\n"
       "```clojure\n" replacement "\n```\n\n"

       "## Notes for update-agent\n\n"
       (or rationale "<no rationale supplied>")
       "\n\n"

       "Saved hot-patch: " artifact-path "\n"
       "Promotion request: bb tui ask \"@" artifact-path "\" -a update-agent\n"))

(defn- write-promotion!
  "Compose + write the artifact. Returns its repo-relative path."
  [{:keys [target-file target-symbol pattern replacement rationale
           validation-evidence edit-mode drift-marker drift-index] :as opts}]
  (let [ts            (now-fmt ts-formatter)
        iso           (now-fmt iso-formatter)
        dir           (promotion-dir)
        fname         (str ts "-" (slug target-symbol) ".md")
        abs-file      (io/file dir fname)
        ;; Repo-relative path for the artifact's own internal references
        ;; and for the operator-facing `bb tui ask` hint.
        repo-rel-path (str ".brainyard/agents/debug-agent/promotions/" fname)
        agent         proto/*current-agent*
        agent-session (when agent
                        (try (proto/session-id agent) (catch Throwable _ nil)))
        nrepl-session (or (some-> drift-marker :session)
                          (when agent
                            (try (cfg/get-config agent :nrepl-session-id)
                                 (catch Throwable _ nil))))
        content (str (format-frontmatter
                      {:created-at    iso
                       :debug-session agent-session
                       :nrepl-session nrepl-session
                       :drift-index   drift-index
                       :target-file   target-file
                       :target-symbol target-symbol
                       :edit-mode     edit-mode
                       :status        :proposed})
                     (format-body (assoc opts
                                         :artifact-path repo-rel-path)))]
    (spit abs-file content)
    (mulog/info ::promotion-written
                :path repo-rel-path
                :target-file target-file
                :target-symbol target-symbol
                :edit-mode edit-mode
                :drift-index drift-index)
    repo-rel-path))

(defn- resolve-drift-marker
  "Pick a marker by `index` (default = latest). Returns [marker actual-index]
   or [nil nil] when no markers exist or index out of range."
  [index]
  (let [ms (clj-nrepl/drift-markers)
        n  (count ms)]
    (cond
      (zero? n) [nil nil]
      (nil? index) [(last ms) (dec n)]
      (or (neg? index) (>= index n)) [nil nil]
      :else [(nth ms index) index])))

(defcommand debug$promote-hot-patch
  "Promote a validated live-runtime hot-patch into a committed source
   change. Writes a markdown artifact under
   `.brainyard/agents/debug-agent/promotions/` describing what changed in the
   live runtime, the validation evidence, and the proposed source
   patch in `update-file` pattern-mode shape.

   Call this AFTER you've validated a fix via clj-nrepl (the drift
   marker is your audit anchor). debug-agent does NOT edit files —
   the artifact's last lines tell the operator how to invoke
   update-agent against it.

   Returns the artifact path plus a `:next-step` shell hint."
  (fn [{:keys [drift-index target-file target-symbol pattern replacement
               rationale validation-evidence]}]
    (let [[marker actual-idx] (resolve-drift-marker drift-index)]
      (cond
        (and (some? drift-index) (nil? marker))
        {:error (str "drift-index " drift-index " out of range — current count: "
                     (clj-nrepl/drift-count))}

        (and (nil? drift-index) (zero? (clj-nrepl/drift-count)))
        {:error "no drift markers recorded yet — apply + validate the hot-patch via code$eval :backend :nrepl first"}

        :else
        (let [path (write-promotion!
                    {:drift-marker        marker
                     :drift-index         actual-idx
                     :target-file         target-file
                     :target-symbol       target-symbol
                     :edit-mode           :pattern
                     :pattern             pattern
                     :replacement         replacement
                     :rationale           rationale
                     :validation-evidence validation-evidence})]
          ;; Mark the promotion so the finalize hook can inject a
          ;; `Saved hot-patch: <path>` line if the LLM forgets to surface it.
          (when-let [ag proto/*current-agent*]
            (swap! (:!state ag) update ::pending-promotions (fnil conj []) path))
          {:path      path
           :status    :proposed
           :next-step (str "bb tui ask \"@" path "\" -a update-agent")}))))
  :input-schema
  [:map
   [:target-file [:string {:desc "Repo-relative path to the source file that should be edited."}]]
   [:target-symbol [:string {:desc "Fully-qualified symbol the patch targets (e.g. ai.brainyard.foo/bar)."}]]
   [:pattern [:string {:desc "The literal current source snippet that update-agent's pattern mode will match — must exist verbatim in target-file."}]]
   [:replacement [:string {:desc "The replacement snippet update-agent should write in place of :pattern."}]]
   [:rationale [:string {:desc "Why this is the right source change — for the operator + update-agent's review."}]]
   [:validation-evidence [:string {:desc "Probe outputs that proved the fix works in the live runtime."}]]
   [:drift-index {:optional true} :int]]
  :output-schema
  [:map
   [:path [:string {:desc "Repo-relative path to the written artifact."}]]
   [:status [:keyword {:desc ":proposed | :promoted | :rejected"}]]
   [:next-step {:optional true} [:string {:desc "Shell hint for invoking update-agent against the artifact."}]]
   [:error {:optional true} [:string {:desc "Present when promotion was refused (no markers, index out of range)."}]]])

;; ============================================================================
;; Drift-inspection tool
;; ============================================================================
;;
;; A thin defcommand so the LLM can ask "what mutations have I applied
;; to the live runtime so far?" without hand-rolling the namespace
;; reach. Read-only — drift state is process-local audit material, not
;; rewritable from the agent.

(defcommand clj-nrepl$drift-markers
  "Inspect runtime-drift markers — successful mutating evals against
   the live image. Returns a vector of {:timestamp :session :code-preview
   :reason} entries plus a count + drifted? boolean."
  (fn [_]
    {:markers  (clj-nrepl/drift-markers)
     :count    (clj-nrepl/drift-count)
     :drifted? (clj-nrepl/drifted?)})
  :input-schema  [:map]
  :output-schema [:map
                  [:markers [:any {:desc "Vector of drift markers in arrival order."}]]
                  [:count [:int {:desc "Total markers since process start."}]]
                  [:drifted? [:boolean {:desc "True when at least one mutation has occurred."}]]])

;; ============================================================================
;; Per-instance lifecycle — open / close nREPL session
;;
;; The execution-model prompt section is selected by coact-system-context
;; based on the agent's :clj-backend config; setting :clj-backend :nrepl
;; below is sufficient — no separate :execution-model write needed.
;; ============================================================================

(defn- debug-agent?
  "True when `agent` is a debug-agent instance (agent-id namespaced by
   :debug-agent)."
  [agent]
  (and agent (= :debug-agent (proto/defagent-type agent))))

(defn- write-config!
  "Per-instance config write. Bypasses agent.core.config/set-config! to
   avoid the global / .brainyard/config.edn write that 2- and 3-arity
   set-config! perform — instance-scoped state must not leak into
   global config. Mirrors the set-allowed-dirs! pattern."
  [agent k v]
  (when-let [smi (some-> agent :!state deref :st-memory-init)]
    (swap! smi assoc-in [:config k] v)))

(defn- on-instance-created
  "Pin a server-issued nREPL session id + the :clj-backend route on the
   new debug-agent instance. When the server isn't running, the agent
   still starts — first code-eval call will surface the gate error so
   the LLM can report it."
  [{:keys [agent]}]
  (when (debug-agent? agent)
    (write-config! agent :clj-backend :nrepl)
    (if (clj-nrepl/running?)
      (try
        (let [sid (clj-nrepl/new-session)]
          (write-config! agent :nrepl-session-id sid)
          (mulog/info ::debug-agent-session-opened
                      :agent-id (proto/agent-id agent)
                      :session  sid))
        (catch Throwable t
          (mulog/warn ::debug-agent-session-open-failed
                      :agent-id (proto/agent-id agent)
                      :error    (.getMessage t))))
      (mulog/warn ::debug-agent-no-server
                  :agent-id (proto/agent-id agent)
                  :message  "clj-nrepl server not running; debug-agent will fail at first eval"))))

(defn- on-instance-closed
  "Close the pinned nREPL session when the agent is torn down."
  [{:keys [agent]}]
  (when (debug-agent? agent)
    (when-let [sid (some-> agent :!state deref
                           :st-memory-init :config :nrepl-session-id)]
      (try (clj-nrepl/close-session sid) (catch Throwable _))
      (mulog/info ::debug-agent-session-closed
                  :agent-id (proto/agent-id agent)
                  :session  sid))))

;; register-hook! dedupes by [event-key handler-id], so registering at
;; ns load (and across reloads) is safe.
(hooks/register-hook! :agent.instance/created ::debug-agent-created
                      on-instance-created :source :debug-agent)
(hooks/register-hook! :agent.instance/closed ::debug-agent-closed
                      on-instance-closed :source :debug-agent)

;; Promotion handoff net. debug$promote-hot-patch stashes each artifact path
;; under ::pending-promotions; this gated :agent.ask/finalize handler injects a
;; `Saved hot-patch: <path>` line into the answer for any promotion the LLM
;; didn't surface, so the operator never loses the promotion path. Idempotent
;; (clears the marker; only injects lines absent from the answer); defensive.
(defn debug-promotion-finalize
  [{:keys [agent result]}]
  (try
    (when (and (debug-agent? agent) (map? result))
      (when-let [pend (seq (some-> agent :!state deref ::pending-promotions))]
        (swap! (:!state agent) dissoc ::pending-promotions)
        (let [answer  (str (:answer result))
              missing (remove #(str/includes? answer (str "Saved hot-patch: " %)) pend)]
          (when (seq missing)
            {:result      :replace
             :reason      "injected absent Saved-hot-patch promotion line(s)"
             :replacement (assoc result :answer
                                 (str answer "\n\n"
                                      (str/join "\n" (map #(str "Saved hot-patch: " %) missing))))}))))
    (catch Throwable t
      (mulog/error ::debug.promotion-finalize-failed :exception t)
      nil)))

(hooks/register-hook! :agent.ask/finalize ::debug-promotion-finalize
                      debug-promotion-finalize
                      :source :debug-agent
                      :match  (fn [{:keys [agent]}] (debug-agent? agent))
                      :priority 50)

;; ============================================================================
;; Instruction
;; ============================================================================

(def ^:private debug-instruction
  "You are debugging the LIVE brainyard JVM image via clj-nrepl. Every
   ```clojure fence you emit runs in the running process — `(*e)`,
   `Thread/getAllStackTraces`, full reflection, and the entire tool
   registry are all reachable.

   Loop:
     1. Reproduce — bind the offending inputs to a var, call the failing
        function, read *e and the stack trace.
     2. Probe — inspect related state (Integrant system map, atoms, the
        tool registry, agent sessions, hooks).
     3. Hypothesize — state your guess explicitly before testing.
     4. Test — propose a fix by `alter-var-root`-ing or `def`-ing the
        replacement, then re-run the reproducer to confirm.

   Guardrails:
   - The FIRST mutating form per session triggers a human confirmation
     prompt; subsequent mutations in the same session pass silently.
   - Catastrophic forms (System/exit, Runtime/.exec, credential nses)
     are rejected regardless of grant scope.
   - Every successful mutation marks the runtime as drifted from
     source. Use `clj-nrepl$drift-markers` to inspect what you (or
     anything else in this process) has changed. Drift survives only
     until the process restarts.

   You do NOT need to add the `:nrepl` info-arg — your code blocks
   route to the live runtime by default.

   ## Promoting a fix to source
   You never edit files. When a hot-patch is validated and you believe
   the same change should land in source, call `debug$promote-hot-patch`
   with:
     :target-file         — repo-relative path to the file
     :target-symbol       — fully-qualified symbol (ai.brainyard.foo/bar)
     :pattern             — the EXACT current source snippet to match
     :replacement         — what should be written in its place
     :rationale           — why this is the right source change
     :validation-evidence — the probe outputs proving the fix works
   The tool writes a markdown artifact under
   `.brainyard/agents/debug-agent/promotions/` and returns `:next-step` — a
   `bb tui ask \"@<artifact>\" -a update-agent` line the operator runs
   to apply the source change. The runtime drift marker stays as the
   audit anchor between hot-patch and committed change.

   Out of scope for THIS agent: writing the file. update-agent owns
   the probe→apply→verify→persist→rollback pipeline.")

;; ============================================================================
;; Defagent registration
;; ============================================================================

(defagent debug-agent
  "Live-runtime debug specialist: drives the clj-nrepl-eval reproduce → probe → hypothesize → test loop against the running brainyard JVM. Pins an nREPL session per instance so multi-turn investigations accumulate state; routes every ```clojure block to the live runtime via clj-nrepl. Out of scope: source edits (handled by update-agent)."
  coact/run-coact-derived
  ;; Pin :bt-factory explicitly so direct-resolution entry points
  ;; (setup-agent-by-id used by `bb tui ask`) work without going
  ;; through run-coact-derived. See explore-agent for the pattern.
  :bt-factory (fn [{:keys [max-iterations]}]
                (coact/coact-behavior-tree max-iterations))
  :tool-use-control {}
  :input-schema  [:map
                  [:question [:string {:desc "Bug description, stack trace, or wedged-component observation to investigate."}]]
                  [:agent-context {:optional true} [:string {:desc "Optional pointer to upstream context — a related explore-agent dossier, an issue link, prior debug notes."}]]]
  :output-schema [:map
                  [:answer [:string {:desc "Investigation summary: root cause, what was probed, any hot-patch applied (with drift marker reference), and recommended next step (promote / revert / dig deeper)."}]]]
  :agent-tools {:tools [:code$eval
                        :task$detail
                        :task$list
                        :task$cancel
                        :clj-nrepl$drift-markers
                        :debug$promote-hot-patch]}
  :instruction debug-instruction
  :max-iterations 30)
