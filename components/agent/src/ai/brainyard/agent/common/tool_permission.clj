;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.common.tool-permission
  "The tool permission gate — `docs/design/tool-permission-gate-design.md`.

   Two things live here: the **shared mechanism** every tool-permission policy
   needs (a glob matcher, and one mode-resolved verdict function), and the
   **general policy** over registered tool names. `mcp/permission.clj` keeps its
   own classification — `readOnlyHint`, the `server/tool` target shape, the
   proxy batch — and calls the same `gate-verdict`.

   Why here rather than in `core/tool`'s `check-permission`, which this replaces:
   a gate inside `call-tool` has no prompt channel, no `:replace` verdict, and
   no `:match` predicate, so it pays a policy lookup on every dispatch and can
   only ever return an error string. `:agent.tool-use/pre` fires inside
   `dispatch-with-hooks`, which is the one chokepoint the LLM tool channel, the
   sandbox callables, the script bridge and sub-agent dispatch all share.

   Four things that are load-bearing:

   - **Ships INERT.** `:tool-approval-patterns` and `:tool-allow-tools` are both
     empty by default, so `gate-matches?` short-circuits on two empty vectors
     and no dispatch changes. A gate that arrives switched on is a gate that
     arrives breaking something.

   - **A gate is VETO-ONLY.** `fire-decision!` treats an allow as an
     ABSTENTION — returning nil does not stop the walk — so a gate can only
     ever ADD a refusal and can never license a call another gate refuses.
     That is why `:tool-allow-tools` cannot widen anything for MCP tools:
     matching it yields nil, the walk continues, and `mcp-permission-gate`
     still gets its say. `:mcp-allow-tools` remains the only key that bypasses
     that gate.

   - **Priority 85, and both neighbours are deliberate.** Below
     `context-actions/tool-cache-lookup` (90), so a cache hit short-circuits
     before a prompt — approving a call that was about to be served from cache
     is a prompt paid for nothing. Above `mcp-permission-gate` (80), so a
     general deny reaches an MCP tool that `readOnlyHint` would have
     auto-allowed. Ordering matters in that direction and no other.

   - **`:on-error :throw`, against the house default.** `fire-decision!` fails
     OPEN: a handler that throws is logged and stepped over, so with the
     default `:log` a gate that crashes PERMITS the call. For a cache or a
     nudge that is right; for a permission gate it is the wrong-direction
     failure. Throwing surfaces the bug as a failed tool call instead of a
     silently ungated one."
  (:require [ai.brainyard.agent.core.config :as config]
            [ai.brainyard.agent.core.hooks :as hooks]
            [ai.brainyard.agent.core.session :as session]
            [ai.brainyard.mulog.interface :as mulog]
            [clojure.string :as str]))

;; ============================================================================
;; Glob matching — shared with :script-bridge-tools and :mcp-allow-tools
;; ============================================================================

(defn glob->re
  "`user$*` → `^\\Quser$\\E.*$`. Only `*` is special (any run of chars).

   The literal segments are `Pattern/quote`d rather than escaped by hand,
   because EVERY registered tool name contains `$`, which inside a regex is an
   end-of-input ANCHOR. The naive `(str/replace pat \"*\" \".*\")` compiles
   `mcp$*` to `^mcp$.*$` — a pattern that matches nothing beginning `mcp$`.
   The same reason a raw name must never be handed to `re-pattern`: that was
   half of what made `check-permission` inert.

   `*` spans `$` deliberately, so `user$*` covers `user$tool$create` and the
   nested forms are refinements rather than additions."
  [pat]
  (re-pattern (str "^"
                   (->> (str/split (str pat) #"\*" -1)
                        (map #(java.util.regex.Pattern/quote %))
                        (str/join ".*"))
                   "$")))

(def ^:private glob->re* (memoize glob->re))

(defn glob-match?
  "True when `glob` admits `target`. Whole-name, never a substring — the other
   half of what made `check-permission` inert was `re-find`, under which
   `:deny [\"read\"]` denied `read-file` AND `spread-metrics`."
  [glob target]
  (boolean (re-matches (glob->re* glob) (str target))))

(defn first-match
  "The first glob in `globs` admitting `target`, or nil. Returns the PATTERN
   rather than a boolean because the approval prompt caches on it: the unit a
   human agrees to is the family they were shown, not the one call."
  [globs target]
  (first (filter #(glob-match? % target) globs)))

;; ============================================================================
;; The shared verdict
;; ============================================================================

(defn- permission-fn [agent]
  (some-> (:!session agent) deref (session/get-session-config :permission-fn)))

(defn deny-replace
  "A `:replace` refusal. `:replace` rather than `:block` so the model gets a
   result it can read and the turn continues; `blocked-tool-result` would end
   the loop, which is the wrong shape for \"ask again with permission\".

   The prefixes and hint are parameters so MCP's wording stays byte-identical
   after the extraction — the refusal text is what a user actually sees, and
   changing it is not what \"extract a shared function\" is allowed to mean."
  [{:keys [by display reason reason-prefix error-prefix hint]}]
  {:result      :replace
   :by          by
   :reason      (str reason-prefix ": " display " — " reason)
   :replacement {:error (str error-prefix ": " display ". " reason ". " hint)}})

(defn gate-verdict
  "Decide allow (nil) or refuse (`:replace`) for a target that has already been
   classified as needing approval, honoring the resolved `:permission-mode`.

   `:ask-each-time` with no `permission-fn` REFUSES — fail-closed, mirroring
   write-file's headless behavior. A gate that silently allows when it cannot
   ask is worse than no gate, because the audit trail then reads as approved."
  [agent {:keys [request] :as opts}]
  (case (config/resolve-permission-mode agent)
    :auto-approve    nil
    :deny-by-default (deny-replace (assoc opts :reason "permission mode is :deny-by-default"))
    (if-let [pfn (permission-fn agent)]
      (let [resp (try (pfn request)
                      (catch Exception e
                        {:denied true :reason (str "permission prompt error: " (ex-message e))}))]
        (if (:allowed resp)
          nil
          (deny-replace (assoc opts :reason (or (:reason resp) "denied by user")))))
      (deny-replace (assoc opts :reason "no interactive permission channel (headless)")))))

;; ============================================================================
;; The general policy over registered tool names
;; ============================================================================

(def ^:private hint
  (str "To allow it: approve interactively, add a matching :tool-allow-tools "
       "glob, or set [:permissions :mode] :auto-approve."))

(defn- patterns [agent]
  [(or (config/get-config agent :tool-approval-patterns) [])
   (or (config/get-config agent :tool-allow-tools) [])])

(defn approval-pattern
  "The `:tool-approval-patterns` glob demanding approval for `tool-name`, or nil
   when none does or a `:tool-allow-tools` glob exempts it.

   Allow is checked FIRST and wins, which is the only ordering that lets a
   broad approval pattern carry a narrow exemption (`:tool-approval-patterns
   [\"mcp$*\"]` with `:tool-allow-tools [\"mcp$linear$*\"]`). The reverse
   ordering would make the exemption unreachable.

   Note this is a LOCAL exemption: it stops THIS gate asking. It cannot stop
   another gate refusing, because an allow is an abstention (see the ns
   docstring) — which is why `:tool-allow-tools` does not bypass MCP's gate."
  [agent tool-name]
  (let [[approve allow] (patterns agent)]
    (when (and (seq approve) (not (first-match allow tool-name)))
      (first-match approve tool-name))))

(defn gate-matches?
  "`:match` predicate. Runs on EVERY tool call in the process, so the empty
   case — the shipped default, and the overwhelming majority — must cost one
   config read and a `seq`, never a glob scan. Only a configured pattern pays
   for the rest."
  [{:keys [agent tool-name]}]
  (let [[approve allow] (patterns agent)
        tname           (name (or tool-name ""))]
    (boolean (and (seq approve)
                  (not (first-match allow tname))
                  (first-match approve tname)))))

(defn tool-permission-gate
  "`:agent.tool-use/pre` handler for the general policy."
  [{:keys [agent tool-name]}]
  (let [tname (name (or tool-name ""))]
    (when-let [pat (approval-pattern agent tname)]
      (mulog/log ::tool-approval-required :tool tname :pattern pat)
      (gate-verdict agent
                    {:by           ::tool-permission-gate
                     :display      tname
                     :reason-prefix "Tool permission refused"
                     :error-prefix  "Tool call refused (permission)"
                     :hint          hint
                     ;; `:pattern` is what the TUI caches an "always" on — the
                     ;; family the human was shown, not the single call.
                     :request      {:type    :tool-use
                                    :action  :call
                                    :tool    tname
                                    :pattern pat
                                    :display tname}}))))

(defn install-tool-permission-gate!
  "Register the general gate on `:agent.tool-use/pre`. Idempotent —
   `register-hook!` replaces on [event-key handler-id], so a `:reload` cannot
   accumulate duplicates."
  []
  (hooks/register-hook!
   :agent.tool-use/pre
   ::tool-permission-gate
   tool-permission-gate
   :source   :tool-permission
   :match    gate-matches?
   :priority 85
   :on-error :throw)
  (mulog/info ::tool-permission-gate-installed))

(install-tool-permission-gate!)
