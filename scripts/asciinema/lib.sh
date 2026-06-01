#!/usr/bin/env bash
# lib.sh — shared helpers for the asciinema tutorial recorder.
# Sourced by record-scenario.sh. Not executable on its own.

set -euo pipefail

# Repo root (git top-level). All tutorial paths are relative to this.
by_root() { git -C "${1:-$PWD}" rev-parse --show-toplevel; }

log()  { printf '\033[36m[tutorial]\033[0m %s\n' "$*" >&2; }
warn() { printf '\033[33m[tutorial] WARN:\033[0m %s\n' "$*" >&2; }
die()  { printf '\033[31m[tutorial] ERROR:\033[0m %s\n' "$*" >&2; exit 1; }

# Short git sha for unique-but-stable tmux session names.
short_sha() { git rev-parse --short HEAD 2>/dev/null || echo "nogit"; }

# tmux session name for a scenario id.
tmux_session_name() { echo "by-tut-$1-$(short_sha)"; }

# Resolve the version string for cast metadata. `by` has no --version flag
# (its version is baked from `git describe` at build time), and `bb tui` runs
# from source — so for every launch mode the repo's `git describe` is both the
# accurate and the simplest source of truth.
#   $1 = launch binary (unused; kept for call-site compatibility)
resolve_version() {
  git describe --tags --always --dirty 2>/dev/null || echo "dev"
}

# Poll until the TUI is ready for input. There is no startup "lock" beacon
# (the persist lock is a transient mutex), so readiness is signalled by the
# rendered input prompt appearing in the pane — the true "ready for keystrokes"
# state, and identical in inline and fullscreen modes. We also require the
# session dir + meta.edn so we know the agent was actually created.
#   $1 = tmux session   $2 = by session id   $3 = timeout secs
wait_for_ready() {
  local tmux_session="$1" sid="$2" timeout="$3"
  local meta="$HOME/.brainyard/sessions/$sid/meta.edn"
  local waited=0
  log "waiting for TUI ready (prompt in pane $tmux_session, timeout ${timeout}s)"
  while :; do
    if [[ -f "$meta" ]] \
       && tmux capture-pane -t "$tmux_session" -p 2>/dev/null \
            | grep -q '/help for commands'; then
      log "TUI ready after ${waited}s"
      return 0
    fi
    sleep 0.5
    waited=$(awk "BEGIN{print $waited + 0.5}")
    if (( $(awk "BEGIN{print ($waited >= $timeout)}") )); then
      return 1
    fi
  done
}

# Redact volatile bits from a captured frame so the golden diff catches
# semantic regressions, not cosmetic noise. Reads stdin, writes stdout.
redact_frame() {
  sed -E \
    -e 's/session [A-Za-z0-9_-]+/session <SESSION>/g' \
    -e 's/agt-[0-9]+-[0-9]+/<SESSION>/g' \
    -e 's/tutorial-[A-Za-z0-9_-]+/<SESSION>/g' \
    -e 's/\$[0-9]+\.[0-9]+/$<COST>/g' \
    -e 's/[0-9]+ calls/<N> calls/g' \
    -e 's/[0-9]+ tokens/<N> tokens/g' \
    -e 's#/Users/[^ ]+#<PATH>#g' \
    -e 's/:[0-9]{4,5}\b/:<PORT>/g' \
    -e 's/[[:space:]]+$//'
}

# Extract the recording URL from `asciinema upload` output. Reads stdin,
# prints the first asciicast URL (…/a/<id>) or nothing. Works for asciinema.org
# and self-hosted servers.
extract_cast_url() {
  grep -oE 'https?://[^[:space:]]+/a/[A-Za-z0-9._-]+' | head -1
}

# Validate a recorded cast: non-empty + first line is a JSON header with version.
validate_cast() {
  local cast="$1"
  [[ -s "$cast" ]] || die "cast is empty: $cast"
  head -1 "$cast" | grep -q '"version"' || die "cast header missing version field: $cast"
  log "cast header OK: $(head -1 "$cast")"
}
