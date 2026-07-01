#!/usr/bin/env bash
# eval-agent harness — read-only scoring against acceptance criteria.
#
# eval-agent scores whether executed work met its source plan's acceptance
# criteria and produces ONE unified verdict file under
# `.brainyard/agents/eval-agent/verdicts/<ts>-<slug>.md` (YAML frontmatter =
# machine handoff, body = rationale — eval_agent.clj §28-33). Hard rule:
# read-only toward upstream — it writes no source.
#
# We give it a concrete plan (acceptance criteria) plus a real artifact to judge,
# and assert a verdict file lands and no source is mutated.
#
# Cases
#   [1] a verdict file is written under verdicts/
#   [2] the verdict references the acceptance criterion
#   [3] NEGATIVE — read-only: the judged source file is untouched
#
# Usage:   scripts/test-agent-eval.sh [--keep]
# Env:     BY_BIN, PROVIDER, MODEL
# Exit:    0 all pass · 1 assertion failed · 2 cannot run
set -uo pipefail
cd "$(dirname "$0")/.."
source scripts/lib-agent-harness.sh

harness_parse_args "$@"
harness_init "eval-agent"
harness_banner "eval-agent harness"

# ---------- fixture: an artifact that satisfies a stated criterion ----------
cat > "$(proj_file widget.sh)" <<'SH'
#!/usr/bin/env bash
[ "$1" = "--version" ] && { echo "widget 1.2.3"; exit 0; }
echo "widget"; exit 0
SH
chmod +x "$(proj_file widget.sh)"
( cd "$PROJ" && git add -A && git commit -q -m "widget with --version" )
BEFORE_HASH="$( cd "$PROJ" && git rev-parse HEAD:widget.sh )"

VERDICTS_GLOB="$PROJ/.brainyard/agents/eval-agent/verdicts/*.md"

echo "[1-2] score widget.sh against its acceptance criterion"
ans="$(by_ask 'Evaluate whether the executed work meets the plan.
PLAN acceptance criterion: "Running widget.sh --version prints exactly \"widget 1.2.3\" and exits 0."
EXECUTION: widget.sh has been implemented in this repo.
Verify the criterion against the actual file and produce a verdict.')"

assert_file_exists   "verdict file written"          "$VERDICTS_GLOB"
assert_file_contains "verdict cites version string"  "1.2.3" "$VERDICTS_GLOB"

echo "[3] NEGATIVE — eval is read-only toward source"
AFTER_HASH="$( cd "$PROJ" && git rev-parse HEAD:widget.sh 2>/dev/null || echo changed )"
if [[ "$BEFORE_HASH" == "$AFTER_HASH" ]] && \
   ! ( cd "$PROJ" && git status --porcelain -- widget.sh | grep -q .); then
    echo "  ✓ widget.sh unchanged by eval"; PASS=$((PASS+1))
else
    echo "  ✗ eval mutated the source it was judging"; FAIL=$((FAIL+1))
fi

harness_summary "eval-agent harness"
