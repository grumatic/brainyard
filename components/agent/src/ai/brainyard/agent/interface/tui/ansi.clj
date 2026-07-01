;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.interface.tui.ansi
  "Public interface for TUI ANSI escape codes and helpers."
  (:require [ai.brainyard.util.interface.macros :refer [export-symbols]]))

;; Color control + theme bindings
(export-symbols ai.brainyard.agent.tui.ansi
                no-color! color! style theme-style set-theme! current-theme)

;; Semantic helpers (theme-aware)
(export-symbols ai.brainyard.agent.tui.ansi
                header success failure warning muted thought
                tool-name answer-text user-text observation-text rule
                iter-marker-running iter-marker-success
                iter-marker-failure iter-marker-done
                iter-label iter-usage
                tool-bullet tool-done tool-error
                tool-arg-name tool-arg-value
                spinner-active)

;; Cursor & screen control
(export-symbols ai.brainyard.agent.tui.ansi
                cursor-to set-scroll-region)

;; Constants are exported as defs (export-symbols handles them)
(export-symbols ai.brainyard.agent.tui.ansi
                esc reset bold dim italic underline reverse-video
                black red green yellow blue magenta cyan white
                bright-black bright-red bright-green bright-yellow
                bright-blue bright-magenta bright-cyan bright-white
                bg-black bg-bright-black bg-256
                h-line v-line tl-corner tr-corner bl-corner br-corner
                check cross-mark arrow left-arrow bullet ellipsis
                save-cursor restore-cursor hide-cursor show-cursor
                enter-alt-screen leave-alt-screen clear-screen
                reset-scroll-region erase-line
                enable-alt-scroll disable-alt-scroll
                enable-bracketed-paste disable-bracketed-paste)
