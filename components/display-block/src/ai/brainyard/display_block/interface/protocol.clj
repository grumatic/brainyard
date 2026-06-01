;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: Apache-2.0
;; Licensed under the Apache License, Version 2.0 (the "License"); you may
;; not use this file except in compliance with the License. See LICENSE at
;; the repository root and CONTRIBUTING.md for contribution terms.

(ns ai.brainyard.display-block.interface.protocol
  "Public protocols for display-block.

   A `display-block` is a piece of content that:
     - has a stable `id`
     - renders into scrollback as a single MARKER LINE (collapsed form)
     - can produce a multi-line EXPANDED FORM on demand
     - is toggle-able by any rendering surface that scans the marker
       line out of scrollback and consults the registry by id.

   `BlockProvider` is the producer-side abstraction. Consumers (TUI glue)
   use the registry API in `display-block.interface` and never touch the
   protocol directly unless they implement a custom provider.")

(defprotocol BlockProvider
  "Source of truth for a single display-block. Implementations decide how
   the full content is stored (file, memory, lazy fetch …) and how the
   collapsed/expanded forms are rendered."
  (-meta [this]
    "Static metadata map. Consumers may inspect:
       :id                 — unique block id (string, [a-z0-9]+)
       :class              — semantic category keyword (e.g. :code :result :error)
       :label              — human-readable section name (e.g. \"Code\")
       :hint               — short hint shown in the marker
                             (e.g. \"Enter: expand, Ctrl-O: edit\")
       :total-lines        — total line count of the underlying content
       :hidden-lines       — lines hidden in the collapsed form (= total - shown-lines)
       :max-expanded-lines — cap applied when expanding (provider-defined)")
  (-collapsed-marker-line [this]
    "Return the marker LINE (single string, no newline) shown when the
     block is collapsed. By convention it carries the block id so the
     marker scanner can find this provider in the registry. Use
     `display-block.core.marker/collapsed-line` to build a standard one.")
  (-expanded-lines [this]
    "Return a vector of strings to splice into scrollback when the user
     expands. The provider decides what goes in: header notice, capped
     body lines, trailer/expanded-marker, etc. The first line should
     usually be a notice; the LAST line should be the expanded marker
     (so the user can collapse back).")
  (-resource-path [this]
    "Filesystem path or URI for editor integration, or nil. When non-nil,
     the TUI may offer a 'view in editor' affordance.")
  (-dispose! [this]
    "Release any backing resources (delete temp file, drop heap reference,
     …). Idempotent."))

(defprotocol IBlockRegistry
  "Centralised lookup of providers by id. The default implementation in
   `display-block.core.registry` is an atom-backed map; tests or
   alternate runtimes can swap their own."
  (-register!  [this provider]    "Register provider; return its id.")
  (-get        [this id]          "Look up provider by id, or nil.")
  (-unregister [this id]          "Remove provider entry without dispose.")
  (-all        [this]             "Map of id -> provider.")
  (-clear!     [this]             "Drop everything (for tests)."))
