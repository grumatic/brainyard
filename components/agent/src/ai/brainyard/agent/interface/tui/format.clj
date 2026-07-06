;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.interface.tui.format
  "Public interface for TUI formatting functions."
  (:require [ai.brainyard.util.interface.macros :refer [export-symbols]]))

(export-symbols ai.brainyard.agent.tui.format
                command-registry
                refresh-terminal-size! terminal-columns terminal-rows
                display-width format-number format-help format-welcome-banner
                format-iteration-header format-iteration-exhausted
                format-recovery-status
                format-thought format-tool-calls format-tool-results
                format-todo-progress format-todo-list
                format-observation format-eval-sections format-tool-call-block
                format-tool-result-block format-tool-error-block
                format-goal-status
                format-next-prompt format-eval-verdict
                format-answer format-answer-plain format-usage-summary format-usage-table
                format-conversation-message format-conversation-history
                format-status-summary
                format-trace format-mulog-event format-mulog-event-data
                format-memory-activity-event)

