;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent-tui-tmux.interface
  "Public API for the tmux-substrate component.

   Exports:
   - the `Tmux` protocol and its operations
   - `RealTmux` (shells out to tmux(1)) and `StubTmux` (test recorder)
   - the questionnaire popup primitive (data + helpers)"
  (:require [ai.brainyard.agent-tui-tmux.core.control.client :as control-client]
            [ai.brainyard.agent-tui-tmux.core.control.protocol :as control-proto]
            [ai.brainyard.agent-tui-tmux.core.control.server :as control-server]
            [ai.brainyard.agent-tui-tmux.core.host :as host]
            [ai.brainyard.agent-tui-tmux.core.host-callbacks :as host-cb]
            [ai.brainyard.agent-tui-tmux.core.protocol :as protocol]
            [ai.brainyard.agent-tui-tmux.core.questionnaire :as questionnaire]
            [ai.brainyard.agent-tui-tmux.core.real :as real]
            [ai.brainyard.agent-tui-tmux.core.sink :as sink]
            [ai.brainyard.agent-tui-tmux.core.stub :as stub]
            [ai.brainyard.agent-tui-tmux.core.widgets :as widgets]))

;; -- Tmux protocol & backends -------------------------------------------------

(def Tmux protocol/Tmux)

(def version             protocol/version)
(def probe-version       protocol/probe-version)
(def running?            protocol/running?)
(def supports-popup?     protocol/supports-popup?)
(def new-session!        protocol/new-session!)
(def kill-session!       protocol/kill-session!)
(def list-sessions       protocol/list-sessions)
(def new-window!         protocol/new-window!)
(def kill-window!        protocol/kill-window!)
(def kill-pane!          protocol/kill-pane!)
(def rename-window!      protocol/rename-window!)
(def split-pane!         protocol/split-pane!)
(def resize-pane!        protocol/resize-pane!)
(def select-pane!        protocol/select-pane!)
(def select-window!      protocol/select-window!)
(def send-keys!          protocol/send-keys!)
(def pipe-pane!          protocol/pipe-pane!)
(def capture-pane        protocol/capture-pane)
(def display-popup!      protocol/display-popup!)
(def set-option!         protocol/set-option!)
(def display-message     protocol/display-message)
(def signal!             protocol/signal!)
(def run-shell           protocol/run-shell)

(def real-tmux           real/create)
(def stub-tmux           stub/create)
(def stub-calls          stub/calls)
(def stub-calls-of       stub/calls-of)
(def stub-last-call      stub/last-call)
(def stub-reset-calls!   stub/reset-calls!)

;; -- Questionnaire popup primitive -------------------------------------------

(def make-questionnaire        questionnaire/make)
(def validate-questionnaire    questionnaire/validate)
(def single-tab?               questionnaire/single-tab?)
(def default-answers           questionnaire/default-answers)
(def ready-to-submit?          questionnaire/ready-to-submit?)
(def submitted-reply           questionnaire/submitted-reply)
(def cancelled-reply           questionnaire/cancelled-reply)
(def timeout-reply             questionnaire/timeout-reply)
(def permission-questionnaire  questionnaire/permission-questionnaire)
(def confirm-questionnaire     questionnaire/confirm-questionnaire)
(def feedback-questionnaire    questionnaire/feedback-questionnaire)
(def text-questionnaire        questionnaire/text-questionnaire)
(def permission-decision       questionnaire/permission-decision)

;; -- Sinks (per-pane ANSI byte routing) --------------------------------------

(def sink-channels       sink/channels)
(def write-sink!         sink/write!)
(def flush-sink!         sink/flush!)
(def close-sink!         sink/close*)
(def write-string-sink!  sink/write-string!)
(def writer-sink         sink/writer-sink)
(def pipe-sink           sink/pipe-sink)
(def null-sink           sink/null-sink)
(def ensure-fifo!        sink/ensure-fifo!)
(def multi-sink          sink/multi-sink)
(def sink-of             sink/sink-of)
(def write-channel!      sink/write-channel!)
(def set-channel!        sink/set-channel!)
(def close-all-sinks!    sink/close-all!)
(def write-stream!       sink/write-stream!)
(def write-activity!     sink/write-activity!)
(def write-status!       sink/write-status!)
(def flush-all-sinks!    sink/flush-all!)

;; -- Widgets (live-block analogue at the tail of a stream pane) --------------

(def set-widget!         widgets/set-widget!)
(def freeze-widget!      widgets/freeze-widget!)
(def clear-widget!       widgets/clear-widget!)
(def forget-multi-sink!  widgets/forget-multi-sink!)
(def widget-line-count   widgets/line-count)

;; -- Control protocol (Unix-domain-socket EDN frames) ------------------------

(def control-protocol-version  control-proto/protocol-version)
(def control-write-frame!      control-proto/write-frame!)
(def control-read-frame        control-proto/read-frame)
(def control-frame-seq         control-proto/frame-seq)

(def control-msg-hello         control-proto/hello)
(def control-msg-hello-ack     control-proto/hello-ack)
(def control-msg-input         control-proto/input)
(def control-msg-slash         control-proto/slash)
(def control-msg-cancel        control-proto/cancel)
(def control-msg-pause         control-proto/pause-msg)
(def control-msg-resume        control-proto/resume-msg)
(def control-msg-pause-toggle  control-proto/pause-toggle-msg)
(def control-msg-detach        control-proto/detach)
(def control-msg-ping          control-proto/ping)
(def control-msg-pong          control-proto/pong)
(def control-msg-resize        control-proto/resize)
(def control-msg-status        control-proto/status-update)
(def control-msg-popup         control-proto/popup)
(def control-msg-popup-result  control-proto/popup-result)
(def control-msg-open-picker   control-proto/open-picker)
(def control-msg-list-sessions control-proto/list-sessions-msg)
(def control-msg-sessions      control-proto/sessions-reply)
(def control-msg-attach        control-proto/attach-msg)
(def control-msg-error         control-proto/error-msg)
(def control-msg-bye           control-proto/bye)
(def control-msg-new-agent     control-proto/new-agent)
(def control-msg-new-agent-result control-proto/new-agent-result)
(def control-msg-close-agent   control-proto/close-agent)
(def control-msg-list-agents   control-proto/list-agents-msg)
(def control-msg-agents        control-proto/agents-reply)
(def control-msg-client-slash  control-proto/client-slash)
(def control-msg-rename-window control-proto/rename-window)
(def control-msg-close-window  control-proto/close-window)
(def control-msg-agent-state   control-proto/agent-state)
(def control-msg-activity-state control-proto/activity-state)

(def host-broadcast!  host/broadcast!)

(def control-server-start!     control-server/start!)
(def control-server-send!      control-server/send!)
(def control-server-close!     control-server/close-connection!)
(def control-server-open?      control-server/open?)
(def control-client-connect!   control-client/connect!)
(def control-request-response! control-client/request-response!)

;; -- by-host transport adapter -----------------------------------------------

(def host-start!              host/start!)
(def host-emit!               host/emit!)
(def host-emit-status!        host/emit-status!)
(def host-emit-popup!         host/emit-popup!)
(def host-await-popup-reply!  host/await-popup-reply!)
(def host-emit-picker!        host/emit-picker!)
(def host-await-picker-reply! host/await-picker-reply!)
(def host-attached?           host/attached?)
(def host-active-conn         host/active-conn)
(def host-set-popup-quiet!    host/set-popup-quiet!)
(def host-popup-quiet?        host/popup-quiet?)

;; -- Host-side callback adapters (drop into agent runtime) -------------------

(def make-host-permission-fn  host-cb/make-permission-fn)
(def make-host-user-feedback-fn host-cb/make-user-feedback-fn)
