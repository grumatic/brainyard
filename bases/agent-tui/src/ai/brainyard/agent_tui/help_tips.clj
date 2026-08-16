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
  (:require [ai.brainyard.agent.interface.tui.format :as fmt]
            [clojure.string :as str]))

;; ----------------------------------------------------------------------
;; Dynamic: agent suggestion (top priority)
;; ----------------------------------------------------------------------

(defonce ^{:doc "Per-tab agent suggestions: {key → {:text \"<raw prompt>\"}}.
                 `key` is opaque to this leaf ns — callers pass the owning
                 (or active) session index. Per-key so a background tab's
                 follow-up doesn't clobber the active tab's idle suggestion."}
  !agent-suggestions (atom {}))

(defonce ^{:private true
           :doc "Alternation frame for the idle-tip ticker. Even = show the
                 suggestion, odd = show a static tip. Reset to 0 (suggestion
                 first) whenever a fresh suggestion is captured."}
  !frame (atom 0))

(defn set-agent-suggestion!
  "Record a dynamic agent suggestion (raw follow-up prompt text) for tab `k`.
   A blank value clears it. Resets the alternation frame so the suggestion is
   shown first."
  [k text]
  (reset! !frame 0)
  (if (str/blank? (str text))
    (swap! !agent-suggestions dissoc k)
    (swap! !agent-suggestions assoc k {:text (str/trim (str text))})))

(defn clear-agent-suggestion!
  "Drop tab `k`'s agent suggestion. Called when a new turn starts
   (`ask-pre-handler`); accepting it via right-arrow keeps it live so it
   persists across idle prompts until the next turn."
  [k]
  (swap! !agent-suggestions dissoc k))

(defn agent-suggestion
  "Raw suggestion text for tab `k` (accept-into-buffer / liveness check), or
   nil when none is live."
  [k]
  (get-in @!agent-suggestions [k :text]))

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
   "/display-format to set detail · /quit to exit"])

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

;; Columns the input row spends on things that are not the tip: the `> ` prompt
;; it prints before the placeholder, and a column of right margin.
(def ^:private prompt-cols 2)
(def ^:private right-margin 1)

(defn- tip-budget
  "Columns available to the tip TEXT, after the prompt, the margin, and any
   lead/suffix that must survive alongside it.

   This used to be a flat 72 measured in `count`, which was wrong twice. It
   ignored the pane — a 200-column terminal cut a follow-up at 72 for no
   reason, and a 50-column one was over budget and relied on the input row to
   cut it a second time. And `count` is UTF-16 code units, not columns: 72
   chars of CJK is 143 columns, so on the one input where a cap matters most it
   capped nothing at all.

   `cols` nil means \"ask the terminal\"; an explicit value is for tests."
  [cols lead-w suffix-w]
  (max 8 (- (or cols (fmt/terminal-columns))
            prompt-cols right-margin lead-w suffix-w)))

;; `fmt/truncate-to-width` replaces a local `count`/`subs` truncate. Besides
;; measuring in columns it lands the cut on a grapheme-cluster boundary — the
;; old one sliced UTF-16 blind, and cutting 72 chars into a run of ZWJ family
;; emoji left a LONE HIGH SURROGATE at the end, which is not a character at all.
;; Per CLAUDE.md: anything walking a string steps by `fmt/next-unit`, never by
;; char index.

(defn current-tip
  "Resolve tab `k`'s active tip by priority: agent-suggestion > static.
   Returns {:source :agent-suggestion|:static :raw \"<text>\"} or nil."
  [k]
  (if-let [sug (agent-suggestion k)]
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
   Returns \"\" when no tip is available.

   The 2-arity takes an explicit pane width; the 1-arity asks the terminal.

   Truncation here reserves room for the trailing affordance rather than
   letting it fall off the end. The input row clamps the finished string to the
   width it actually has (`redraw-input-line!`), which is the authority on
   fitting — but it cuts from the RIGHT, so a suffix left unreserved is the
   first thing lost, taking the `→` discoverability with it."
  ([k] (current-placeholder k nil))
  ([k cols]
   (let [sug      (agent-suggestion k)
         st       (current-static)
         lead     "↳ "
         sug-sfx  "  (→ to use)"
         lead-w   (fmt/display-width lead)
         sug-sfx-w (fmt/display-width sug-sfx)
         frame-sfx-w (fmt/display-width tip-frame-suffix)
         fit-sug  (fn [s] (fmt/truncate-to-width s (tip-budget cols lead-w sug-sfx-w)))]
     (cond
      ;; Live suggestion + a static tip available → alternate by frame.
       (and sug st)
       (if (even? (long @!frame))
         (str lead (fit-sug sug) sug-sfx)
         (str (fmt/truncate-to-width st (tip-budget cols 0 frame-sfx-w))
              tip-frame-suffix))
      ;; Suggestion only (no static set) → always show it.
       sug (str lead (fit-sug sug) sug-sfx)
      ;; No suggestion → static tip, fitted to the pane.
       st  (fmt/truncate-to-width (str st) (tip-budget cols 0 0))
       :else ""))))
