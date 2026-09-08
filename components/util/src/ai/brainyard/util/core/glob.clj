;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.util.core.glob
  "The one glob matcher — `*` over names, nothing else.

   Five call sites arrived at this same construction independently before it
   was extracted once: `:script-bridge-tools`, `:mcp-allow-tools`,
   `:tool-approval-patterns`, `:tool-deny-tools`, and now the environment
   policy keys (`:env-allow` / `:env-deny`). It lives in `util` for the same
   reason `resolve-var` does — it depends on nothing and every layer wants it;
   the environment policy sits below the agent component and could not reach
   the copy that lived in `common/tool_permission.clj`.

   Two bugs are pinned here rather than in each caller, because both were live
   and both are invisible until someone writes the wrong pattern. See
   `glob->re` and `glob-match?`."
  (:require [clojure.string :as str]))

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
