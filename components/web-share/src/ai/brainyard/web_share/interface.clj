;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.web-share.interface
  "Public interface for sharing a Brainyard TUI session over the web via ttyd.

   The `--web` launcher (in agent-tui-app) wraps `by run` in ttyd so remote
   browsers share one live PTY session. Auth is always required and binding
   defaults to localhost — see core.clj for the security rationale."
  (:require [ai.brainyard.web-share.core :as core]))

(defn available?
  "Probe for the ttyd binary on PATH.
   Returns {:ok? true :path str :version str-or-nil}
        or {:ok? false :hint str}."
  []
  (core/available?))

(defn self-exec-argv
  "Resolve the argv prefix ttyd should use to relaunch the TUI.
   Honors the BY_WEB_SELF override; otherwise resolves the native binary path
   or `which by`. Returns {:ok? true :argv [str ...]} or {:ok? false :reason str}."
  ([] (core/self-exec-argv))
  ([env] (core/self-exec-argv env)))

(defn resolve-credential
  "Resolve the mandatory ttyd basic-auth credential, generating a password when
   none is supplied. Returns {:user :pass :credential :generated?}."
  [opts]
  (core/resolve-credential opts))

(defn build-ttyd-argv
  "Pure constructor for the ttyd command vector. See core/build-ttyd-argv."
  [opts]
  (core/build-ttyd-argv opts))

(defn serve!
  "Spawn ttyd wrapping the TUI; returns {:proc :url :argv :stop}.
   Forces BY_WEB_CHILD=1 on the child so it runs the TUI, not another ttyd."
  [opts]
  (core/serve! opts))

(defn tmux-available?
  "True when the tmux binary is on PATH (required for serve-tmux!)."
  []
  (core/tmux-available?))

(defn serve-tmux!
  "Tier 2: run the TUI inside a detached tmux session (private socket) and serve
   it via ttyd so the local terminal and browsers co-drive one live process.
   Returns {:proc :url :session :socket :tmux-path :attach-argv :log-file
            :alive? :stop}. See core/serve-tmux!."
  [opts]
  (core/serve-tmux! opts))
