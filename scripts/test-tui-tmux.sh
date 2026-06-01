#!/usr/bin/env bash
# Real-terminal test harness for `bb tui` driven via tmux.
#
# Spawns `bb tui --inline -p <provider> -m <model> -a <agent>` inside a
# detached tmux session, drives it with `tmux send-keys`, captures the
# pane with `tmux capture-pane`, and asserts on the captured text.
#
# Used as the agent-level smoke test for react / coact agents across
# providers. See docs/testing/real-terminal-tests.md (TBD) for design.
#
# Usage:
#   scripts/test-tui-tmux.sh <agent> <provider> <model>
#
# Examples:
#   scripts/test-tui-tmux.sh react-agent claude-code opus
#   scripts/test-tui-tmux.sh coact-agent bedrock global.anthropic.claude-sonnet-4-6
#   scripts/test-tui-tmux.sh react-agent openai gpt-4o
#
# Exit codes:
#   0 — all assertions passed
#   1 — at least one assertion failed
#   2 — startup failure (tmux session never reached idle prompt)
set -uo pipefail

AGENT="${1:?usage: $0 <agent> <provider> <model>}"
PROVIDER="${2:?usage: $0 <agent> <provider> <model>}"
MODEL="${3:?usage: $0 <agent> <provider> <model>}"

SESSION="by-test-$$"
STARTUP_TIMEOUT=30      # seconds to wait for first prompt
TURN_TIMEOUT=90         # seconds per agent turn (LLM call)
POLL_INTERVAL=2         # seconds between capture-pane polls
IDLE_CHECKS=2           # consecutive identical captures => idle

# Spinner glyphs / labels the TUI uses while the agent is working. If any
# match the captured pane, the turn is NOT idle yet.
SPINNER_RE=$'[⠋⠙⠹⠸⠼⠴⠦⠧⠇⠏]|Computing…|Reflecting…|Cogitating…|Calculating…|Thinking…'

cleanup() { tmux kill-session -t "$SESSION" 2>/dev/null || true; }
trap cleanup EXIT INT TERM

capture() { tmux capture-pane -t "$SESSION" -p 2>/dev/null; }

# Idle when: (a) spinner is gone AND (b) the input-prompt line is present
# AND (c) the capture is stable across $IDLE_CHECKS polls. The spinner
# vanishing alone isn't enough because the TUI redraws as the answer
# streams in; we want the screen to settle.
is_idle_pane() {
    local pane="$1"
    grep -q 'Alt+Enter: newline' <<<"$pane" || return 1
    grep -qE "$SPINNER_RE" <<<"$pane" && return 1
    return 0
}

wait_for_idle() {
    local timeout="$1"
    local deadline=$(( $(date +%s) + timeout ))
    local prev="" cur="" stable=0
    while (( $(date +%s) < deadline )); do
        cur="$(capture)"
        if is_idle_pane "$cur" && [[ "$cur" == "$prev" ]]; then
            stable=$(( stable + 1 ))
            if (( stable >= IDLE_CHECKS )); then
                printf '%s' "$cur"
                return 0
            fi
        else
            stable=0
        fi
        prev="$cur"
        sleep "$POLL_INTERVAL"
    done
    printf '%s' "$cur"
    return 1
}

# `assert_pane <name> <pattern>` — fail the suite if grep -qiE doesn't match
# the latest captured pane.
PASS=0; FAIL=0
assert_pane() {
    local name="$1" pattern="$2" pane="$3"
    if grep -qiE "$pattern" <<<"$pane"; then
        echo "  ✓ $name"
        PASS=$(( PASS + 1 ))
    else
        echo "  ✗ $name (pattern: $pattern)"
        echo "    --- tail of pane ---"
        echo "$pane" | tail -8 | sed 's/^/    /'
        echo "    --------------------"
        FAIL=$(( FAIL + 1 ))
    fi
}

ask_and_wait() {
    local question="$1"
    tmux send-keys -t "$SESSION" "$question" Enter
    wait_for_idle "$TURN_TIMEOUT"
}

# ---------- 1. boot bb tui in detached tmux ----------
echo "== $AGENT / $PROVIDER:$MODEL =="
tmux new-session -d -s "$SESSION" \
    "bb tui --inline -p '$PROVIDER' -m '$MODEL' -a '$AGENT'" \
    || { echo "FATAL: tmux new-session failed"; exit 2; }

# ---------- 2. drive the session picker (choose New) ----------
# Picker shows: `Choice [1- N ] / (N)ew:`  — wait for it, then send `N`.
deadline=$(( $(date +%s) + STARTUP_TIMEOUT ))
while (( $(date +%s) < deadline )); do
    if capture | grep -qE '(\(N\)ew|Alt\+Enter)'; then break; fi
    sleep 1
done
if capture | grep -q '(N)ew'; then
    tmux send-keys -t "$SESSION" "N" Enter
fi

# ---------- 3. wait for the agent ready prompt ----------
pane="$(wait_for_idle "$STARTUP_TIMEOUT")" || {
    echo "FATAL: agent never reached idle prompt"
    echo "$pane" | tail -20
    exit 2
}

# ---------- 4. test case A: simple single-turn question ----------
echo "[A] simple question — 2 + 2"
pane="$(ask_and_wait 'Reply with just the digit 4 and nothing else: what is 2+2?')" \
    || { echo "  ✗ turn timed out"; FAIL=$(( FAIL + 1 )); }
# Expect the answer to mention 4 and the per-turn usage footer to appear.
assert_pane "answer contains 4"        '(^|[^0-9])4([^0-9]|$)'   "$pane"
assert_pane "turn-end footer present"  'calls .* tokens'         "$pane"

# ---------- 5. test case B: multi-turn (remember → recall) ----------
echo "[B] multi-turn — remember favorite color, then recall"
pane="$(ask_and_wait 'My favorite color is blue. Acknowledge with a single short sentence.')" \
    || { echo "  ✗ remember turn timed out"; FAIL=$(( FAIL + 1 )); }
assert_pane "remember turn produced output" 'Goal achieved|blue' "$pane"

pane="$(ask_and_wait 'What did I say my favorite color was? Reply with just one word: the color name.')" \
    || { echo "  ✗ recall turn timed out"; FAIL=$(( FAIL + 1 )); }
assert_pane "recalled color is blue" '(^|[^a-z])blue([^a-z]|$)' "$pane"

# ---------- 6. summary ----------
echo
echo "== $AGENT / $PROVIDER:$MODEL  pass=$PASS fail=$FAIL =="
exit $(( FAIL > 0 ? 1 : 0 ))
