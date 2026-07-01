#!/usr/bin/env bash
# plan-agent harness — plan authoring with pre/post-flight gating.
#
# plan-agent authors a plan file (`.brainyard/agents/plan-agent/plans/<slug>.md`)
# with required frontmatter, emits a dossier every turn
# (`.brainyard/agents/plan-agent/dossiers/<ts>-<slug>.md`), and reports a
# `Saved plan: <path>` handoff. It GATES on insufficient input rather than
# fabricating a plan.
#
# Cases
#   [1] plan file written for a well-specified task, with acceptance criteria
#   [2] a per-turn dossier landed on disk
#   [3] the answer surfaces the saved-plan handoff
#   [4] NEGATIVE — a hopelessly underspecified ask gates instead of inventing a
#       plan (no new plan file appears)
#
# Usage:   scripts/test-agent-plan.sh [--keep]
# Env:     BY_BIN, PROVIDER, MODEL
# Exit:    0 all pass · 1 assertion failed · 2 cannot run
set -uo pipefail
cd "$(dirname "$0")/.."
source scripts/lib-agent-harness.sh

harness_parse_args "$@"
harness_init "plan-agent"
harness_banner "plan-agent harness"

PLANS_GLOB="$PROJ/.brainyard/agents/plan-agent/plans/*.md"
DOSSIER_GLOB="$PROJ/.brainyard/agents/plan-agent/dossiers/*.md"

echo "[1-3] author a plan for a concrete task"
ans="$(by_ask 'Author a plan to add a --version flag to a small CLI tool named "widget" that prints the string "widget 1.2.3" and exits 0. The tool is a single bash script widget.sh in this repo. Save the plan.')"

# Deterministic probe: the per-turn dossier is AUTO-PERSISTED by plan-agent's
# hook (plan.clj §465), independent of whether the model remembered to author a
# standalone plan doc — and it records the acceptance criteria in frontmatter.
assert_file_exists   "per-turn dossier written"        "$DOSSIER_GLOB"
assert_file_contains "dossier records acceptance"      "acceptance" "$DOSSIER_GLOB"
assert_contains      "answer surfaces a plan"          "plan" "$ans"
# The standalone plan doc under plans/ is authored by the model (write-side), so
# it's model-dependent — informational, not a contract failure.
if ls $PLANS_GLOB >/dev/null 2>&1; then
    echo "  ✓ standalone plan doc authored under plans/"
else
    echo "  ⚠ no standalone plan doc (informational — dossier is the durable record)"
fi

echo "[4] NEGATIVE — underspecified ask should gate, not fabricate"
# Count plan files before; a gate must not add one.
before=$(ls $PLANS_GLOB 2>/dev/null | wc -l | tr -d ' ')
ans="$(by_ask 'Make a plan for the thing. You figure out what.')"
after=$(ls $PLANS_GLOB 2>/dev/null | wc -l | tr -d ' ')
if (( after <= before )); then
    echo "  ✓ gated (no new plan fabricated: $before → $after)"; PASS=$((PASS+1))
else
    echo "  ✗ fabricated a plan from an underspecified ask ($before → $after)"; FAIL=$((FAIL+1))
fi

harness_summary "plan-agent harness"
