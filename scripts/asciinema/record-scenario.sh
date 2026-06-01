#!/usr/bin/env bash
# record-scenario.sh — orchestrate a tutorial recording.
#
#   record-scenario.sh <scenario-id> [--dry-run]
#
# Sequence (see docs/asciinema-deploy.md §5):
#   1. resolve + validate the scenario
#   2. allocate a detached tmux session sized to :terminal
#   3. launch `asciinema rec --command "<binary> <args>"` inside the pane,
#      with the scenario's :env exported so BRAINYARD_SESSION_ID is honored
#   4. wait for the TUI lock file (ready)
#   5. drive preamble -> chapters -> postamble via drive-scenario.bb
#   6. let the postamble /quit exit `by`, flushing the cast; kill the session
#   7. validate + trim the cast
#
# asciinema owns the pane PTY; `by` runs as its child. tmux send-keys writes
# to the pane PTY (asciinema's stdin), which asciinema forwards to `by`.

set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib.sh
source "$HERE/lib.sh"

ROOT="$(by_root "$HERE")"
cd "$ROOT"

SCENARIO_ID="${1:-}"
DRY_RUN=0
[[ "${2:-}" == "--dry-run" ]] && DRY_RUN=1
[[ -z "$SCENARIO_ID" ]] && die "usage: record-scenario.sh <scenario-id> [--dry-run]"

SCENARIO="docs/tutorials/scenarios/${SCENARIO_ID}.edn"
CAST="docs/tutorials/casts/${SCENARIO_ID}.cast"
DRIVE="bb $ROOT/scripts/asciinema/drive-scenario.bb"

# 1. Validate + load meta.
$DRIVE parse "$SCENARIO" >/dev/null || die "scenario failed validation"
eval "$($DRIVE meta "$SCENARIO")"
[[ -n "${SCN_SID:-}" ]] || die "scenario must set :env BRAINYARD_SESSION_ID (deterministic session id)"

VERSION="$(resolve_version "$SCN_BINARY")"
SESSION="$(tmux_session_name "$SCENARIO_ID")"

if [[ "$DRY_RUN" == 1 ]]; then
  log "DRY RUN — no recording"
  $DRIVE plan "$SCENARIO"
  log "would launch tmux session: $SESSION (${SCN_COLS}x${SCN_ROWS})"
  log "would record: $SCN_BINARY $SCN_ARGS  ->  $CAST  (version: $VERSION)"
  [[ -n "${SCN_WORKSPACE:-}" ]] && log "would git-init a /tmp workspace and run \`by\` there (isolated cwd)"
  exit 0
fi

command -v asciinema >/dev/null 2>&1 || die "asciinema not installed (see bb tutorial:doctor)"

# Optional isolated workspace: git-init a /tmp tree (seeded per :workspace) and
# run `by` there, so project-dir — and every tool/agent write — stays out of the
# repo. Defaults to $ROOT (current behavior) when the scenario has no :workspace.
WORKDIR=""
if [[ -n "${SCN_WORKSPACE:-}" ]]; then
  WORKDIR="$($DRIVE workspace "$SCENARIO")" || die "workspace setup failed"
  [[ -d "$WORKDIR" ]] || die "workspace dir not created: $WORKDIR"
  log "workspace: $WORKDIR (git-inited; \`by\` runs here, isolated from the repo)"
fi

# 2-3. Build the pane command: env exports + asciinema rec + child `by`.
#      Pin asciicast-v2 for max asciinema-player / cat compatibility.
PANE_CMD="env $SCN_ENV_EXPORTS BRAINYARD_VERSION='$VERSION' \
  asciinema rec \
    --output-format asciicast-v2 \
    --overwrite \
    --idle-time-limit '$SCN_IDLE' \
    --title '$SCN_TITLE' \
    --capture-env BRAINYARD_VERSION,BRAINYARD_SESSION_ID \
    --command '$SCN_BINARY $SCN_ARGS' \
    '$ROOT/$CAST'"

# Kill any stale session of the same name; ensure cleanup on exit.
tmux kill-session -t "$SESSION" 2>/dev/null || true
cleanup() { tmux kill-session -t "$SESSION" 2>/dev/null || true; }
trap cleanup EXIT

log "starting tmux session $SESSION (${SCN_COLS}x${SCN_ROWS})"
tmux new-session -d -s "$SESSION" -c "${WORKDIR:-$ROOT}" -x "$SCN_COLS" -y "$SCN_ROWS" "$PANE_CMD"

# 4. Wait for the TUI to be ready (input prompt rendered).
if ! wait_for_ready "$SESSION" "$SCN_SID" "$SCN_STARTUP"; then
  warn "lock not seen within ${SCN_STARTUP}s — capturing pane for diagnosis:"
  tmux capture-pane -t "$SESSION" -p | tail -20 >&2 || true
  die "TUI did not start"
fi

# 5. Drive the scenario.
log "driving scenario steps"
$DRIVE drive "$SCENARIO" --tmux "$SESSION" --sid "$SCN_SID"

# 6. Give the postamble /quit time to exit `by` so asciinema flushes the cast.
#    Fall back to C-d if the pane is still alive.
for _ in $(seq 1 20); do
  tmux has-session -t "$SESSION" 2>/dev/null || break
  # If the inner program exited, the pane (and session) goes away.
  sleep 0.5
done
if tmux has-session -t "$SESSION" 2>/dev/null; then
  warn "pane still alive after postamble — sending C-d to stop recording"
  tmux send-keys -t "$SESSION" C-d || true
  sleep 1
fi

# 7. Validate. (No post-hoc trim: asciinema's --idle-time-limit already caps
#    every inter-event gap natively, including the JVM cold-start pause. The
#    cast is kept exactly as asciinema wrote it — guaranteed-valid JSON.)
[[ -f "$CAST" ]] || die "no cast produced at $CAST"
validate_cast "$CAST"
log "recorded: $CAST (version: $VERSION)"
