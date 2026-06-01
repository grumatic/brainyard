;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: Apache-2.0
;; Licensed under the Apache License, Version 2.0 (the "License"); you may
;; not use this file except in compliance with the License. See LICENSE at
;; the repository root and CONTRIBUTING.md for contribution terms.

(ns ai.brainyard.agent.interface.tui.format
  "Public interface for TUI formatting functions."
  (:require [ai.brainyard.util.interface.macros :refer [export-symbols]]))

(export-symbols ai.brainyard.agent.tui.format
                command-registry
                refresh-terminal-size! terminal-columns terminal-rows
                display-width format-number format-help format-welcome-banner
                format-iteration-header format-iteration-exhausted
                format-thought format-tool-calls format-tool-results
                format-todo-progress format-todo-list
                format-observation format-eval-sections format-goal-status
                format-answer format-usage-summary format-usage-table
                format-conversation-message format-conversation-history
                format-status-summary format-analytics-display
                format-trace format-mulog-event format-mulog-event-data)

