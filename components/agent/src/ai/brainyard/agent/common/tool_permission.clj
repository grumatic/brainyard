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
     silently ungated one.

   `:tool-deny-tools` is a SECOND gate rather than a third branch of the first,
   because it wants a different priority and a different relationship to
   `[:permissions :mode]`. See `tool-deny-gate`."
  (:require [ai.brainyard.agent.core.config :as config]
            [ai.brainyard.util.interface :as util]
            [ai.brainyard.agent.core.hooks :as hooks]
            [ai.brainyard.agent.core.session :as session]
            [ai.brainyard.mulog.interface :as mulog]
            [clojure.string :as str]))

;; ============================================================================
;; Glob matching — shared with :script-bridge-tools and :mcp-allow-tools
;; ============================================================================

(def glob->re
  "See `util/glob->re` — moved to `util` when the environment policy keys
   became its fifth and sixth callers and could not reach it here. The var is
   captured, not the value, so a `with-redefs` in either place still works."
  #'util/glob->re)

(def glob-match?
  "See `util/glob-match?`."
  #'util/glob-match?)

(def first-match
  "See `util/first-match`. Returns the PATTERN rather than a boolean because
   the approval prompt caches on it: the unit a human agrees to is the family
   they were shown, not the one call."
  #'util/first-match)

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

(def ^:private deny-hint
  (str "This tool is denied by :tool-deny-tools. No permission mode overrides "
       "it — narrow or remove the matching glob to allow it."))

(defn deny-pattern
  "The `:tool-deny-tools` glob refusing `tool-name`, or nil.

   Deliberately consults ONE key. `:tool-allow-tools` carves narrow holes in
   `:tool-approval-patterns` and stops there: a deny that another key can undo
   is not a deny, and the reason to reach for this over an approval pattern is
   precisely that no local exemption and no permission mode can talk it round.
   To allow something, narrow the glob."
  [agent tool-name]
  (first-match (or (config/get-config agent :tool-deny-tools) []) tool-name))

(defn deny-matches?
  "`:match` predicate for the deny gate. Like `gate-matches?`, the empty case —
   the shipped default — must cost one config read and a `seq`."
  [{:keys [agent tool-name]}]
  (let [globs (or (config/get-config agent :tool-deny-tools) [])]
    (boolean (and (seq globs)
                  (first-match globs (name (or tool-name "")))))))

(defn tool-deny-gate
  "`:agent.tool-use/pre` handler for the unconditional deny.

   Three things separate it from `tool-permission-gate`, and each is why this
   is a second handler rather than a third branch of the first.

   - **It never calls `gate-verdict`,** so it does not consult
     `[:permissions :mode]`. A deny survives `:auto-approve` — that is the
     whole reason to write one — and under `:ask-each-time` it does not
     prompt, because a prompt is an offer to run and a denied tool is not on
     offer. Prompting for something no answer can permit trains a user to
     answer without reading.

   - **Priority 95, ABOVE the tool-result cache at 90.** The approval gate sits
     BELOW that cache on purpose: approving a call about to be served from
     cache is a prompt paid for nothing, and no side effect happens either way.
     That reasoning inverts for a deny. `tool-cache-lookup-pre` caches every
     tool indiscriminately once `:tool-cache-ttl` is positive and returns a
     `:replace`, which short-circuits the whole walk — so a deny at 85 would be
     stepped over by a cache hit, and a `read-file` denied to keep a file out
     of the context would hand back that file's previously-read contents. The
     only thing a deny must outrank is anything that can SERVE a call; the
     refusals above it (the loop guard at 100, the memory write-guard at 200)
     reach the same outcome by another route.

   - **It is still veto-only.** Returning nil on no match is an abstention, so
     this can only ever add a refusal — the same property every ordering claim
     in this namespace rests on.

   What it does NOT reach, measured rather than assumed: a **shell code-eval
   fence**. `:agent.tool-use/pre` fires in `dispatch-with-hooks`, so this gate
   sees the LLM tool channel, sandbox callables (`(read-file :path …)` inside a
   code block), the `bash` TOOL, the script bridge and sub-agent dispatch — but
   a ```bash block is run by the code-eval channel and never dispatches a tool
   at all. Instrumenting the hook across a live turn where `read-file` was
   denied recorded ZERO events while the model answered the question with
   `sed -n '2p'`, having worked out the detour on its own. So a deny raises the
   cost of a mistake and makes the audit trail legible; it is not a containment
   boundary, and anything that needs one wants `--sandbox`."
  [{:keys [agent tool-name]}]
  (let [tname (name (or tool-name ""))]
    (when-let [pat (deny-pattern agent tname)]
      (mulog/log ::tool-denied :tool tname :pattern pat)
      (deny-replace {:by            ::tool-deny-gate
                     :display       tname
                     :reason        (str "denied by :tool-deny-tools pattern " (pr-str pat))
                     :reason-prefix "Tool permission refused"
                     :error-prefix  "Tool call refused (denied)"
                     :hint          deny-hint}))))

(defn install-tool-deny-gate!
  "Register the unconditional deny on `:agent.tool-use/pre`. Idempotent, and
   `:on-error :throw` for the same reason the approval gate is: under the house
   `:log` default a crashing gate returns nil, which reads as an abstention, so
   the failure permits the call it exists to refuse."
  []
  (hooks/register-hook!
   :agent.tool-use/pre
   ::tool-deny-gate
   tool-deny-gate
   :source   :tool-permission
   :match    deny-matches?
   :priority 95
   :on-error :throw)
  (mulog/info ::tool-deny-gate-installed))

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
(install-tool-deny-gate!)
