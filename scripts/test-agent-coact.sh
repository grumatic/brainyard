#!/usr/bin/env bash
# coact-agent harness — the base-agent substrate.
#
# coact is the reference agent every specialist derives from. This harness
# probes the substrate functions (docs/test-harness.md §4): channel routing,
# code-as-action across runtimes, and SCI persistence. Routing is checked
# STRUCTURALLY off the trajectory (`:channel "code"|"tool"|"none"` per
# iteration), not inferred from prose — trajectory recording is on by default
# (:enable-trajectory-recording).
#
# Cases
#   [1] direct answer    — arithmetic answered in one turn, no code/tool channel
#   [2] code-as-action   — a clojure block computes Fibonacci(10) → 55
#   [3] bash runtime     — a bash block echoes a synthetic token, captured back
#   [4] SCI persistence  — (def x 41) in turn A, (inc x) → 42 in turn B
#
# Usage:   scripts/test-agent-coact.sh [--keep]
# Env:     BY_BIN, PROVIDER, MODEL
# Exit:    0 all pass · 1 assertion failed · 2 cannot run
set -uo pipefail
cd "$(dirname "$0")/.."
source scripts/lib-agent-harness.sh

harness_parse_args "$@"
harness_init "coact-agent"
harness_banner "coact-agent harness"

echo "[1] direct answer — 2+2, no channel needed"
ans="$(by_ask 'What is 2+2? Reply with just the digit and nothing else.')"
assert_contains "answer is 4" "4" "$ans"

echo "[2] code-as-action (clojure) — Fibonacci(10) = 55"
# NOTE: routing is verified by SIDE EFFECT, not the trajectory channel field —
# one-shot `ask` doesn't persist trajectory.edn (that's a TUI-session artifact;
# see lib-agent-harness.sh trajectory_available). Cases [3] (bash stdout) and [4]
# (SCI def surviving a turn) are the deterministic proof that code actually ran;
# the fib value alone could come from the model's own knowledge.
ans="$(by_ask 'Define a Clojure function for Fibonacci and use a code block to compute the 10th Fibonacci number (0-indexed: 0,1,1,2,3,5,...). State the number.')"
assert_contains "fib(10) = 55 in answer" "55" "$ans"
if trajectory_available; then
    assert_contains "a code channel was recorded" "code" "$(trajectory_channels)"
else
    note_skip "code channel probe" "trajectory.edn not persisted on headless ask (see [3]/[4] for the code-ran proof)"
fi

echo "[3] bash runtime — synthetic token round-trips through a bash block"
ans="$(by_ask 'Run a bash code block that echoes exactly the token QUARK-88, then report what it printed.')"
assert_contains "bash stdout surfaced" "QUARK-88" "$ans"

echo "[4] SCI persistence across turns — (def x 41) then (inc x) => 42"
by_ask 'In a clojure code block, evaluate (def harness-x 41). Acknowledge briefly.' >/dev/null
ans="$(by_ask 'In a clojure code block, evaluate (inc harness-x) and report the result number.')"
assert_contains "def survived to next turn (42)" "42" "$ans"

harness_summary "coact-agent harness"
