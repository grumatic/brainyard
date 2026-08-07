;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.interface.tui.terminal-caps
  "Public interface for terminal capability negotiation (DEC mode 2027)."
  (:require [ai.brainyard.util.interface.macros :refer [export-symbols]]))

(export-symbols ai.brainyard.agent.tui.terminal-caps
                negotiate! grapheme-clustering? status
                set-grapheme-clustering! reset-negotiation!
                terminal-key probe! parse-decrqm-reply read-cache env-var)
