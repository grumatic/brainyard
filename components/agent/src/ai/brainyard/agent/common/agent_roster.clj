;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.common.agent-roster
  "Shared default tool roster for the general-purpose agents (coact, react).

   Both agents advertise the SAME curated roster — all common deftool tools
   plus all common commands. It used to be duplicated byte-for-byte at each
   defagent's `:agent-tools` site; defined once here so it cannot drift.

   The two agents differ only in how they RENDER this roster, not in its
   membership:
     - coact reaches every tool through the SCI sandbox + hot-path primitives,
       so it renders the roster compactly (`:compact-agent-tools`) — the roster
       only scopes the category index and names the tools.
     - react has no code channel; this roster IS its advertised tool surface,
       so it renders verbose per-tool specs.

   `:agent-tools` is evaluated at defagent load time (NOT a macro-literal — only
   :type/:input-schema/:output-schema are parsed at macro-expand time), so the
   defagents reference `default-agent-roster` by symbol. This ns is required by
   both agents, which makes the native-image class-init order deterministic:
   it (and transitively common.tools / common.commands) loads before either
   agent's `def` runs."
  (:require [ai.brainyard.agent.common.tools :as common-tools]
            [ai.brainyard.agent.common.commands :as common-cmds]))

(def default-agent-roster
  "Shared coact/react `:agent-tools` value: all common deftool tools + all
   common commands, de-duped. Shape matches the defagent `:agent-tools` slot
   (`{:tools [...]}`)."
  {:tools (vec (distinct (concat common-tools/all-common-tools
                                 common-cmds/all-common-commands)))})
