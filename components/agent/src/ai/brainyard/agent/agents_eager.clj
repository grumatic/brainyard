;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.agents-eager
  "Eager agent-registration requires, split out of
   ai.brainyard.agent.interface so the JVM require path no longer pays
   the ~5.2s cost of eagerly loading all 26 built-in agents.

   This ns exists solely to be statically required by the GraalVM native
   entry point (ai.brainyard.agent-tui-app.native-main) so the native
   image bakes the FULL agent roster. On the JVM path agents load lazily
   on first dispatch; requiring THIS ns force-registers every one.

   Single source of truth: add a new agent's require here when it ships."
  (:require
   ;; Side-effecting loads: register every built-in defagent in the
   ;; unified tool registry. Kept verbatim (order/style) from the
   ;; pre-split interface.clj roster block.
   [ai.brainyard.agent.common.react-agent]
   [ai.brainyard.agent.common.coact-agent]
   [ai.brainyard.agent.common.skill-agent]
   [ai.brainyard.agent.common.rlm-agent]
   [ai.brainyard.agent.common.explore-agent]
   [ai.brainyard.agent.common.debug-agent]
   [ai.brainyard.agent.common.edit-agent]
   [ai.brainyard.agent.common.plan-agent]
   [ai.brainyard.agent.common.todo-agent]
   [ai.brainyard.agent.common.exec-agent]
   [ai.brainyard.agent.common.eval-agent]
   [ai.brainyard.agent.common.mcp-agent]
   [ai.brainyard.agent.common.tool-agent]
   [ai.brainyard.agent.common.hook-agent]
   [ai.brainyard.agent.common.meta-agent]
   [ai.brainyard.agent.common.research-agent]
   [ai.brainyard.agent.common.memory-agent]
   [ai.brainyard.agent.common.workflow-agent]
   [ai.brainyard.agent.common.config-agent]
   [ai.brainyard.agent.common.schedule-agent]
   [ai.brainyard.agent.common.event-agent]
   [ai.brainyard.agent.common.state-machine-agent]
   [ai.brainyard.agent.common.init-agent]
   [ai.brainyard.agent.common.acp-commands]
   [ai.brainyard.agent.common.main-agent]
   [ai.brainyard.agent.common.main-agent-hooks]))
