;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: Apache-2.0
;; Licensed under the Apache License, Version 2.0 (the "License"); you may
;; not use this file except in compliance with the License. See LICENSE at
;; the repository root and CONTRIBUTING.md for contribution terms.

(ns user
  "REPL utilities for Agent TUI development."
  (:require [ai.brainyard.agent-tui.core :as tui]))

(defn start!
  "Start TUI session in REPL mode (inline, no alt screen)."
  [& {:keys [agent-id lm-provider lm-model]
      :or {agent-id :coact-agent
           lm-provider :claude-code}}]
  (apply tui/start!
         (concat [:agent-id agent-id :lm-provider lm-provider]
                 (when lm-model [:lm-model lm-model]))))

(defn stop!
  "Stop TUI session."
  []
  (tui/stop!))

(defn ask
  "Ask the agent a question (synchronous)."
  [input]
  (tui/ask input))

(defn restart!
  "Restart TUI session."
  [& opts]
  (stop!)
  (apply start! opts))

(comment
  (start!)
  (start! :agent-id :react-agent)
  (ask "hello")
  (stop!)
  (restart!))
