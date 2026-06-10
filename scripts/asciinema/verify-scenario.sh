#!/usr/bin/env bash
# verify-scenario.sh — headless drift check for a tutorial scenario.
#
#   verify-scenario.sh <scenario-id> [--update-golden]
#
# Launches `by` in a detached tmux pane (NO asciinema), drives the scenario's
# preamble + chapters, captures the resulting pane, redacts volatile bits, and
# compares against docs/tutorials/golden/<id>.frame.txt. Also asserts the
# scenario's :expect predicates against the captured frame.
#
# Exit codes: 0 (match + expectations pass), 2 (golden drift), 3 (:expect
# failure), 1 (setup/other). First run (or --update-golden) writes the golden
# frame and exits 0.
#
# This is the CI gate from docs/asciinema-deploy.md §9 — frame diff, not cast
# byte-equality.

set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib.sh
source "$HERE/lib.sh"

ROOT="$(by_root "$HERE")"
cd "$ROOT"

SCENARIO_ID="${1:-}"
UPDATE_GOLDEN=0
[[ "${2:-}" == "--update-golden" ]] && UPDATE_GOLDEN=1
[[ -z "$SCENARIO_ID" ]] && die "usage: verify-scenario.sh <scenario-id> [--update-golden]"

SCENARIO="docs/tutorials/scenarios/${SCENARIO_ID}.edn"
GOLDEN="docs/tutorials/golden/${SCENARIO_ID}.frame.txt"
DRIVE="bb $ROOT/scripts/asciinema/drive-scenario.bb"

$DRIVE parse "$SCENARIO" >/dev/null || die "scenario failed validation"
eval "$($DRIVE meta "$SCENARIO")"
[[ -n "${SCN_SID:-}" ]] || die "scenario must set :env BY_SESSION_ID"

SESSION="by-verify-${SCENARIO_ID}-$(short_sha)"

# Launch `by` directly (no asciinema) — verify only needs the rendered pane.
PANE_CMD="env $SCN_ENV_EXPORTS $SCN_BINARY $SCN_ARGS"
tmux kill-session -t "$SESSION" 2>/dev/null || true
cleanup() {
  tmux send-keys -t "$SESSION" "/quit" 2>/dev/null || true
  tmux send-keys -t "$SESSION" C-m 2>/dev/null || true
  sleep 0.5
  tmux kill-session -t "$SESSION" 2>/dev/null || true
}
trap cleanup EXIT

log "verify: launching $SESSION (${SCN_COLS}x${SCN_ROWS}, no asciinema)"
tmux new-session -d -s "$SESSION" -x "$SCN_COLS" -y "$SCN_ROWS" "$PANE_CMD"

if ! wait_for_ready "$SESSION" "$SCN_SID" "$SCN_STARTUP"; then
  tmux capture-pane -t "$SESSION" -p | tail -20 >&2 || true
  die "TUI did not start"
fi

# Drive preamble + chapters, but STOP before the postamble so we capture the
# result frame (the postamble would /quit and tear the screen down).
log "verify: driving (no postamble)"
$DRIVE drive "$SCENARIO" --tmux "$SESSION" --sid "$SCN_SID" --no-postamble

# Capture the rendered pane (capture-pane -p strips ANSI) and redact.
ACTUAL="$(mktemp -t "verify-${SCENARIO_ID}.XXXXXX")"
tmux capture-pane -t "$SESSION" -p | redact_frame > "$ACTUAL"

# Golden compare.
if [[ "$UPDATE_GOLDEN" == 1 || ! -f "$GOLDEN" ]]; then
  mkdir -p "$(dirname "$GOLDEN")"
  cp "$ACTUAL" "$GOLDEN"
  log "verify: wrote golden frame $GOLDEN ($(wc -l <"$GOLDEN") lines)"
  GOLDEN_STATUS="generated"
else
  if diff -u "$GOLDEN" "$ACTUAL" > /tmp/verify-diff.txt 2>&1; then
    log "verify: frame matches golden ✓"
    GOLDEN_STATUS="match"
  else
    warn "verify: FRAME DRIFT vs $GOLDEN"
    sed 's/^/    /' /tmp/verify-diff.txt >&2
    GOLDEN_STATUS="drift"
  fi
fi

# Assert :expect predicates against the captured frame.
log "verify: checking :expect predicates"
EXPECT_OK=1
$DRIVE check "$SCENARIO" --frame "$ACTUAL" || EXPECT_OK=0

rm -f "$ACTUAL"

# Exit policy.
[[ "$GOLDEN_STATUS" == "drift" ]] && exit 2
[[ "$EXPECT_OK" == 0 ]] && exit 3
log "verify: PASS ($GOLDEN_STATUS)"
exit 0
