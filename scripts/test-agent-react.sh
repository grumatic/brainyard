#!/usr/bin/env bash
# react-agent harness — coact with the code channel DISABLED (:code-channel?
# false); the tool-only role.
#
# The distinguishing (NEGATIVE) function: react must NEVER emit an executable
# code fence. It reaches for a tool instead. We prove this structurally off the
# trajectory — no iteration may record `:channel "code"` — even for a prompt
# that would tempt coact into a code block. A positive control confirms it still
# answers normally.
#
# Cases
#   [1] answers a simple question (sanity)
#   [2] NEGATIVE — a compute-shaped prompt produces NO code channel in any turn
#
# Usage:   scripts/test-agent-react.sh [--keep]
# Env:     BY_BIN, PROVIDER, MODEL
# Exit:    0 all pass · 1 assertion failed · 2 cannot run
set -uo pipefail
cd "$(dirname "$0")/.."
source scripts/lib-agent-harness.sh

harness_parse_args "$@"
harness_init "react-agent"
harness_banner "react-agent harness"

echo "[1] sanity — simple question answered"
ans="$(by_ask 'What is the capital of France? One word.')"
assert_contains "answered Paris" "Paris" "$ans"

echo "[2] NEGATIVE — a compute-shaped prompt must NOT open a code channel"
# The strict structural check reads the trajectory channels. This is only
# meaningful when a trajectory was persisted — one-shot `ask` does NOT persist
# one, so on the headless surface we SKIP rather than trivially "pass" on an
# empty channel list (which would be a false negative). The persisted-session
# path (tmux TUI harness) is where this assertion actually bites; react's
# code-channel is disabled at construction (:code-channel? false) regardless.
by_ask 'Compute the 10th Fibonacci number for me.' >/dev/null
if trajectory_available; then
    chans="$(trajectory_channels)"
    echo "    recorded channels: $(tr '\n' ' ' <<<"$chans")"
    assert_not_contains "no 'code' channel ever appears" "code" "$chans"
else
    note_skip "no-code-channel probe" "trajectory.edn not persisted on headless ask; covered by the tmux TUI harness"
fi

echo "[3] tool-mode still completes a task (positive control)"
ans="$(by_ask 'How much is 15% of 80? Give just the number.')"
assert_contains "answered 12" "12" "$ans"

harness_summary "react-agent harness"
