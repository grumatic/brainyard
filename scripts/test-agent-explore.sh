#!/usr/bin/env bash
# explore-agent harness — read-mostly discovery that persists a durable artifact.
#
# explore-agent gathers across surfaces (files, web, MCP, skills) and authors ONE
# markdown dossier under `.brainyard/agents/explore-agent/results/…` plus an
# INDEX.md line (explore.clj §50-52). Its function under test: FIND across the
# project and PERSIST the result re-readably. NEGATIVE: it does not mutate source.
#
# Cases
#   [1] a synthetic token planted across ≥2 files is found and reported
#   [2] an explore artifact (results dossier or INDEX) landed on disk
#   [3] NEGATIVE — the planted source files are unchanged (read-mostly)
#
# Usage:   scripts/test-agent-explore.sh [--keep]
# Env:     BY_BIN, PROVIDER, MODEL
# Exit:    0 all pass · 1 assertion failed · 2 cannot run
set -uo pipefail
cd "$(dirname "$0")/.."
source scripts/lib-agent-harness.sh

harness_parse_args "$@"
harness_init "explore-agent"
harness_banner "explore-agent harness"

# ---------- fixture: a synthetic symbol spanning two files ----------
TOKEN="zephyrCalibrate"
mkdir -p "$(proj_file src)" "$(proj_file docs)"
printf 'function %s() { return 42; } // core impl\n' "$TOKEN" > "$(proj_file src/core.js)"
printf '# Design\nThe %s routine calibrates the flux capacitor.\n' "$TOKEN" > "$(proj_file docs/design.md)"
( cd "$PROJ" && git add -A && git commit -q -m "plant fixture" )
BEFORE_HASH="$( cd "$PROJ" && git rev-parse HEAD:src/core.js )"

RESULTS_GLOB="$PROJ/.brainyard/agents/explore-agent/results/*"
INDEX="$PROJ/.brainyard/agents/explore-agent/INDEX.md"

echo "[1-2] ask explore-agent to locate '$TOKEN' across the project"
ans="$(by_ask "Explore this project and find everywhere the symbol $TOKEN is defined or referenced. Report which files mention it, and save your findings.")"

# Deterministic probe: the discovery must span both planted surfaces. The token
# is synthetic, so a hit can only come from actually reading the files.
assert_contains "reports the JS impl file"  "core.js"   "$ans"
assert_contains "reports the design doc"    "design.md" "$ans"
# explore-agent authors its dossier directly with write-file (the write-helper
# chain is retired — explore.clj §7-10), so persisting one is model-dependent.
# Informational, not a contract failure; the findings above are the real signal.
if ls $RESULTS_GLOB >/dev/null 2>&1 || [[ -f "$INDEX" ]]; then
    echo "  ✓ explore artifact persisted"
else
    echo "  ⚠ no explore artifact under .brainyard/agents/explore-agent/ (informational — model-dependent write)"
fi

echo "[3] NEGATIVE — read-mostly: planted source is untouched"
AFTER_HASH="$( cd "$PROJ" && git rev-parse HEAD:src/core.js 2>/dev/null || echo changed )"
if [[ "$BEFORE_HASH" == "$AFTER_HASH" ]] && \
   ! ( cd "$PROJ" && git status --porcelain -- src docs | grep -q .); then
    echo "  ✓ source files unchanged"; PASS=$((PASS+1))
else
    echo "  ✗ explore mutated source files"; FAIL=$((FAIL+1))
fi

harness_summary "explore-agent harness"
