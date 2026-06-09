;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent-tui.dirs
  "Thin delegation to ai.brainyard.agent.interface.

   Each delegate captures the *var* (`#'agent/x`), not its dereferenced value
   (`agent/x`). A plain value-copy bakes the interface var's binding at this
   ns's load time; under GraalVM native-image, class-init ordering can run this
   ns before `ai.brainyard.agent.interface`'s `export-symbols` defs bind their
   vars, freezing an unbound snapshot (observed: `init-dirs!` → 'Attempting to
   call unbound fn'). Capturing the var object instead defers the deref to call
   time, by which point the interface var is bound. Vars implement IFn, so
   `(init-dirs!)` still invokes through transparently."
  (:require [ai.brainyard.agent.interface :as agent]))

(def find-git-root #'agent/find-git-root)
(def resolve-working-dir #'agent/resolve-working-dir)
(def resolve-project-dir #'agent/resolve-project-dir)
(def resolve-dirs #'agent/resolve-dirs)
(def ensure-config-dirs! #'agent/ensure-config-dirs!)
(def init-dirs! #'agent/init-dirs!)
(def list-config-files #'agent/list-config-files)
(def read-config-file #'agent/read-config-file)
(def load-brainyard-instructions #'agent/load-brainyard-instructions)
(def read-edn-config #'agent/read-edn-config)
(def write-edn-config! #'agent/write-edn-config!)
(def subdir-scope-policy #'agent/subdir-scope-policy)
(def subdir-allowed-scopes #'agent/subdir-allowed-scopes)
(def subdir-scope-allowed? #'agent/subdir-scope-allowed?)
(def brainyard-subdir #'agent/brainyard-subdir)
(def brainyard-subdir! #'agent/brainyard-subdir!)
