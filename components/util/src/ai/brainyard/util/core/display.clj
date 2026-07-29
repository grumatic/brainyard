;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.util.core.display
  "Value normalization for human-facing display.

   Lives in `util` rather than in the agent component because the consumers
   span two bricks — and one of them, `agent-tui.render`, deliberately avoids
   depending on the agent component (loading `agent.interface` side-effect
   requires every built-in agent, which that ns keeps out of its test path).
   `util` is a side-effect-free leaf both can reach.")

(defn- ->display-key
  "Render an arg key as a keyword. Arg names are identifiers in practice
   (they come from a tool's JSON-schema properties / Malli input schema)."
  [k]
  (if (keyword? k) k (keyword (str k))))

(defn- pair-list?
  "True for the legacy `[{:name k :value v} …]` tool-args shape."
  [xs]
  (and (seq xs)
       (every? #(and (map? %) (contains? % :name) (contains? % :value)) xs)))

(defn args->display-map
  "Normalize a tool call's args into a map fit for EDN display.

   Four surfaces render tool args — the TUI live pane (`⮕`) and iteration
   block, the sub-agent stream line (`→`), and the LLM's own compacted
   briefing — and each used to normalize separately, so one call could print
   three different ways. This is the single source of truth.

   Two shapes arrive:

     - A plain arg map (`::acs/tool-args`, the current shape). Keys are
       keywords off the tool-calls channel, but STRINGS when `call-tool`'s
       `:json` bound-fn path re-stringifies them for the bound wrapper — so a
       bootstrap tool rendered as `{\"pattern\" \"…\"}` beside a registry
       tool's `{:path \"…\"}`. Keys are keywordized here, at the display edge
       ONLY: the string keys are load-bearing at dispatch (the bound wrapper
       and a hook's `:modify-args` both expect them).

     - A legacy `[{:name k :value v} …]` pair-list, from a session recorded
       before tool-args became an object. Folded onto the same shape.

   `nil` (a call with no args) becomes `{}`. Anything else — a scalar, or a
   vector that is not a pair-list — passes through untouched so a caller's
   `pr-str` stays honest rather than reporting empty args."
  [args]
  (cond
    (nil? args)  {}
    (map? args)  (update-keys args ->display-key)

    (and (sequential? args) (pair-list? args))
    (into {} (map (fn [{:keys [name value]}] [(->display-key name) value])) args)

    :else args))
