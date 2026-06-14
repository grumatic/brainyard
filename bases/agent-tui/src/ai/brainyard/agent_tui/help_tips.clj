;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent-tui.help-tips
  "Input-bar help tips — a small priority-ordered manager for the muted
   placeholder shown on the idle `> ` prompt.

   Two tip sources, highest priority first:

     1. Agent suggestions (dynamic, captured) — e.g. the agent's self-reported
        `next-user-prompt`, delivered via the `:agent.suggestion/*` hooks. When
        present, the suggestion always wins and is right-arrow-acceptable into
        the input buffer.
     2. Static tips (rotating) — a curated set of usage hints, rotated across
        prompts so users discover more features.

   Priority lives in one place (`current-tip`); add more dynamic sources there
   later as higher-priority branches above the static floor.

   This is a leaf namespace (depends only on clojure.string). Callers own the
   muting/rendering — `current-placeholder` returns a plain string that the
   input redraw wraps in `ansi/muted`, matching the prior placeholder path."
  (:require [clojure.string :as str]))

;; ----------------------------------------------------------------------
;; Dynamic: agent suggestion (top priority)
;; ----------------------------------------------------------------------

(defonce ^{:doc "Live agent suggestion: nil | {:text \"<raw follow-up prompt>\"}."}
  !agent-suggestion (atom nil))

(defn set-agent-suggestion!
  "Record a dynamic agent suggestion (raw follow-up prompt text). A blank
   value clears it."
  [text]
  (if (str/blank? (str text))
    (reset! !agent-suggestion nil)
    (reset! !agent-suggestion {:text (str/trim (str text))})))

(defn clear-agent-suggestion!
  "Drop the current agent suggestion (e.g. when a new turn starts or the user
   accepts it)."
  []
  (reset! !agent-suggestion nil))

(defn agent-suggestion
  "Raw suggestion text for accept-into-buffer, or nil when none is live."
  []
  (:text @!agent-suggestion))

;; ----------------------------------------------------------------------
;; Static: rotating usage hints (floor priority)
;; ----------------------------------------------------------------------

(def static-tips
  "Curated usage hints rotated across idle prompts. The first entry preserves
   the historical single placeholder."
  ["Alt+Enter: newline · /help for commands"
   "/agent to switch agents · /sessions to manage sessions"
   "Ctrl-C to interrupt · /quit to exit"
   "Tab cycles output blocks · /model to change model"])

(defonce ^:private !static-idx (atom 0))

(defn rotate-static!
  "Advance to the next static tip. Call once per fresh idle prompt draw so
   successive idle prompts surface different hints."
  []
  (when (seq static-tips)
    (swap! !static-idx #(mod (inc (long %)) (count static-tips)))))

(defn- current-static []
  (when (seq static-tips)
    (nth static-tips (mod @!static-idx (count static-tips)))))

;; ----------------------------------------------------------------------
;; Resolution (priority lives here)
;; ----------------------------------------------------------------------

(def ^:private max-tip-width
  "Cap on the rendered suggestion width so a long follow-up can't overflow the
   single-line placeholder."
  72)

(defn- truncate [s n]
  (let [s (str s)]
    (if (<= (count s) n)
      s
      (str (subs s 0 (max 0 (dec n))) "…"))))

(defn current-tip
  "Resolve the active tip by priority: agent-suggestion > static.
   Returns {:source :agent-suggestion|:static :raw \"<text>\"} or nil."
  []
  (if-let [sug (agent-suggestion)]
    {:source :agent-suggestion :raw sug}
    (when-let [st (current-static)]
      {:source :static :raw st})))

(defn current-placeholder
  "Plain (un-styled) placeholder string for the idle input line; the caller
   applies muting. Agent suggestions render as `↳ <prompt>  (→ to use)`;
   static tips render verbatim. Returns \"\" when no tip is available."
  []
  (let [{:keys [source raw]} (current-tip)]
    (case source
      :agent-suggestion (str "↳ " (truncate raw max-tip-width) "  (→ to use)")
      :static           (str raw)
      "")))
