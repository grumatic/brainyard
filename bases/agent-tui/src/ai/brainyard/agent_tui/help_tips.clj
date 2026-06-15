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

(defonce ^{:private true
           :doc "Alternation frame for the idle-tip ticker. Even = show the
                 suggestion, odd = show a static tip. Reset to 0 (suggestion
                 first) whenever a fresh suggestion is captured."}
  !frame (atom 0))

(defn set-agent-suggestion!
  "Record a dynamic agent suggestion (raw follow-up prompt text). A blank
   value clears it. Resets the alternation frame so the suggestion is shown
   first."
  [text]
  (reset! !frame 0)
  (if (str/blank? (str text))
    (reset! !agent-suggestion nil)
    (reset! !agent-suggestion {:text (str/trim (str text))})))

(defn clear-agent-suggestion!
  "Drop the current agent suggestion. Called when a new turn starts
   (`ask-pre-handler`); accepting it via right-arrow keeps it live so it
   persists across idle prompts until the next turn."
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
  "Curated usage hints rotated across idle prompts so users discover the
   commands surfaced by `/help`. The first entry preserves the historical
   single placeholder. Kept terse (one short line) to fit narrow terminals;
   `/help` remains the authoritative full listing."
  ["Alt+Enter: newline · /help for all commands"
   "/agent to switch agents · /session to manage tabs & sessions"
   "/model to change model · /effort low|medium|high"
   "/continue [N] for more iterations · /history to review the chat"
   "Ctrl-C to interrupt · /pause and /resume a running agent"
   "Tab cycles output blocks · PgUp/PgDn scroll output history"
   "/clear restarts the session · /compact shrinks the context"
   "/memory manages long-term memory · /init authors BRAINYARD.md"
   "/task manages background tasks · /queue shows the input queue"
   "/usage for token & cost summary · /status for agent status"
   "/mcp manages MCP servers · /config shows/sets runtime config"
   "/activity and /log open side panes · /capture saves scrollback"
   "/sandbox eval CODE · /allow-path PATH to whitelist files"
   "Ctrl-N/Ctrl-P switch sessions · Ctrl-T new · Ctrl-W close"
   "Shift+←/→ navigate prompt history · Ctrl-O toggles the TODO list"
   "/verbose to set detail · /quit to exit"])

(defonce ^:private !static-idx (atom 0))

(defn rotate-static!
  "Advance to the next static tip. Call once per fresh idle prompt draw so
   successive idle prompts surface different hints."
  []
  (when (seq static-tips)
    (swap! !static-idx #(mod (inc (long %)) (count static-tips)))))

(defn tick-frame!
  "Advance the alternation frame (driven by the idle-tip ticker). On entering
   a 'tip' frame (odd), rotate to the next static tip so successive tip frames
   cycle through the curated set. Returns the new frame."
  []
  (let [f (swap! !frame inc)]
    (when (odd? (long f)) (rotate-static!))
    f))

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

(def ^:private tip-frame-suffix
  "Trailing affordance shown on a tip frame so the persistent suggestion stays
   right-arrow-discoverable even while a static tip is on screen."
  "  (→ for suggestion)")

(defn current-placeholder
  "Plain (un-styled) placeholder string for the idle input line; the caller
   applies muting.

   When an agent suggestion is live, the placeholder alternates by frame
   (advanced by the idle-tip ticker via `tick-frame!`) so users still discover
   commands while a follow-up is offered:
     - even frame → `↳ <prompt>  (→ to use)`
     - odd frame  → `<static tip>  (→ for suggestion)`
   The suggestion stays right-arrow-acceptable on both frames (accept reads the
   suggestion atom, not the rendered text); the odd-frame suffix keeps that
   discoverable. With no live suggestion, the static tip renders verbatim.
   Returns \"\" when no tip is available."
  []
  (let [sug (agent-suggestion)
        st  (current-static)]
    (cond
      ;; Live suggestion + a static tip available → alternate by frame.
      (and sug st)
      (if (even? (long @!frame))
        (str "↳ " (truncate sug max-tip-width) "  (→ to use)")
        (str (truncate st (max 8 (- max-tip-width (count tip-frame-suffix))))
             tip-frame-suffix))
      ;; Suggestion only (no static set) → always show it.
      sug (str "↳ " (truncate sug max-tip-width) "  (→ to use)")
      ;; No suggestion → static tip verbatim.
      st  (str st)
      :else "")))
