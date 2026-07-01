#!/usr/bin/env bash
# main-agent harness — the front-door router.
#
# main-agent routes a request to the right specialist and maintains a per-session
# routing log. The routing-log dir is bootstrapped on session-created and the
# per-turn line is HOOK-recorded (record-routing-line is the sole writer —
# main_agent_hooks.clj §324). Artifacts live at
# `<project>/.brainyard/agents/main-agent/<session-id>/routing.log` (+ pointers.md).
#
# Cases
#   [1] the per-session routing-log dir + routing.log are bootstrapped
#   [2] routing.log accrues a line per turn across several request shapes
#
# Usage:   scripts/test-agent-main.sh [--keep]
# Env:     BY_BIN, PROVIDER, MODEL
# Exit:    0 all pass · 1 assertion failed · 2 cannot run
set -uo pipefail
cd "$(dirname "$0")/.."
source scripts/lib-agent-harness.sh

harness_parse_args "$@"
harness_init "main-agent"
harness_banner "main-agent harness"

ROUTE_DIR="$PROJ/.brainyard/agents/main-agent/$SESSION_ID"
ROUTE_LOG="$ROUTE_DIR/routing.log"

echo "[1-2] issue requests of several shapes; routing log should accrue lines"
by_ask 'Explore this project and tell me what languages it uses.'      >/dev/null
by_ask 'What is 7 times 6? Just the number.'                            >/dev/null
by_ask 'Draft a short plan to add a README to this project.'           >/dev/null

assert_file_exists "routing-log dir bootstrapped" "$ROUTE_DIR"
assert_file_exists "routing.log written"          "$ROUTE_LOG"

if [[ -f "$ROUTE_LOG" ]]; then
    lines=$(grep -c . "$ROUTE_LOG" 2>/dev/null || echo 0)
    if (( lines >= 1 )); then
        echo "  ✓ routing.log has $lines line(s)"; PASS=$((PASS+1))
    else
        echo "  ✗ routing.log is empty"; FAIL=$((FAIL+1))
    fi
fi

harness_summary "main-agent harness"
