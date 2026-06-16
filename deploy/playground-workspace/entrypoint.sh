#!/bin/sh
# Copyright (c) 2024-2026 Grumatic, Inc.
# SPDX-License-Identifier: MIT
#
# Brainyard Playground container entrypoint.
#
# Per-container bootstrap (idempotent — runs against the mounted /workspace and
# ~/.brainyard volumes), then SUPERVISES the web-tmux TUI launcher: ttyd/tmux/by
# can exit (web client disconnect, crash, /quit), so we restart it in a loop to
# keep the published ttyd port serving for the life of the container. The args
# ($@) are the provider/model/agent flags the control plane (workspace.clj
# `by-args`) appends after the image — forward them verbatim to `by --web-tmux`.
set -u

# Version-control the workspace from first boot so the agent has git available
# immediately. Idempotent: a no-op on resume (the volume already has .git).
[ -d /workspace/.git ] || git init -q /workspace || true

# Auto-detect provider/credentials (from the injected env-file) and write
# ~/.brainyard/config.edn without prompts. Best-effort: a failure here must not
# block the TUI from coming up.
by config --auto || true

# Graceful stop: forward SIGTERM/SIGINT to the current child and leave the loop.
child=
on_term() { [ -n "$child" ] && kill -TERM "$child" 2>/dev/null; exit 0; }
trap on_term TERM INT

# Supervise: restart the launcher whenever it exits, with a 1s backoff so a
# fast-failing launch can't busy-loop.
while :; do
  by --web-tmux "$@" &
  child=$!
  wait "$child"
  status=$?
  echo "[entrypoint] 'by --web-tmux' exited (status=$status); restarting in 1s…" >&2
  sleep 1
done
