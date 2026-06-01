#!/usr/bin/env bash
# upload-scenario.sh — upload a recorded cast to an asciinema server and cache
# the returned URL.
#
#   upload-scenario.sh <scenario-id> [--dry-run] [--visibility public|unlisted|private]
#
# Uploads docs/tutorials/casts/<id>.cast and writes the share URL to
# docs/tutorials/casts/<id>.url, which `bb tutorial:embed` surfaces as a
# "Play on asciinema.org" link under the offline player.
#
# NOTE: uploading PUBLISHES the cast to a public service. Default visibility is
# `unlisted` (reachable by direct URL, not listed/searchable). Review the cast
# before uploading — `bb tutorial:play <id>`.

set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib.sh
source "$HERE/lib.sh"

ROOT="$(by_root "$HERE")"
cd "$ROOT"

SCENARIO_ID=""
DRY_RUN=0
VISIBILITY="unlisted"
while [[ $# -gt 0 ]]; do
  case "$1" in
    --dry-run)    DRY_RUN=1; shift ;;
    --visibility) VISIBILITY="$2"; shift 2 ;;
    *)            SCENARIO_ID="$1"; shift ;;
  esac
done
[[ -z "$SCENARIO_ID" ]] && die "usage: upload-scenario.sh <scenario-id> [--dry-run] [--visibility V]"

CAST="docs/tutorials/casts/${SCENARIO_ID}.cast"
URL_FILE="docs/tutorials/casts/${SCENARIO_ID}.url"
[[ -f "$CAST" ]] || die "no cast at $CAST — record it first (bb tutorial:record $SCENARIO_ID)"

CMD=(asciinema upload --visibility "$VISIBILITY" "$CAST")

if [[ "$DRY_RUN" == 1 ]]; then
  log "DRY RUN — would run: ${CMD[*]}"
  log "would cache the returned URL in $URL_FILE"
  exit 0
fi

command -v asciinema >/dev/null 2>&1 || die "asciinema not installed (see bb tutorial:doctor)"
log "uploading $CAST (visibility: $VISIBILITY) — this publishes to a public server"

OUT="$("${CMD[@]}" 2>&1 | tee /dev/stderr)"
URL="$(printf '%s\n' "$OUT" | extract_cast_url)"
[[ -n "$URL" ]] || die "could not parse a recording URL from asciinema output"

printf '%s\n' "$URL" > "$URL_FILE"
log "cached share URL: $URL  ->  $URL_FILE"
log "run \`bb tutorial:embed\` to surface the link in docs/tutorials/index.md"
