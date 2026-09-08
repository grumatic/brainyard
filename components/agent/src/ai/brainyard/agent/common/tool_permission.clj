;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.common.tool-permission
  "Shared mechanism for tool-permission policies —
   `docs/design/tool-permission-gate-design.md` §3.1.

   Two things every such policy needs and neither should own: a **glob matcher**
   over tool names, and one **mode-resolved verdict** function. `mcp/permission.clj`
   is the first caller and keeps everything that is actually about MCP —
   `readOnlyHint`, the `server/tool` target shape, the proxy batch — while the
   `:permission-mode` branching, the headless fail-closed rule and the refusal
   shape live here.

   This commit is deliberately mechanism only: the extraction has to be
   provably inert before a second policy rides on it, which is why the refusal
   wording is passed in by the caller rather than defaulted here. What a user
   actually sees must not change because a function moved.

   The matcher is shared with `:script-bridge-tools`, which arrived at the same
   `Pattern/quote`-segments-joined-on-`.*` construction independently. Two call
   sites converging on one shape is the argument for having one copy of it."
  (:require [ai.brainyard.agent.core.config :as config]
            [ai.brainyard.agent.core.session :as session]
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
