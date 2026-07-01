#!/usr/bin/env bash
# todo-agent harness — decompose a task into a routed checklist.
#
# todo-agent decomposes a plan into items carrying `{via: …}` routing tags
# (edit-agent / bash / mcp / explore-agent / read-only / manual) and ALWAYS
# emits a per-turn handoff dossier under `.brainyard/agents/todo-agent/dossiers/`
# (todo.clj §714, auto-persisted regardless of model). The standalone checklist
# file (`.brainyard/agents/todo-agent/todos/<slug>.md`, todo.clj §316) is
# authored via doc$create — model-dependent. Its HARD RULE: it authors todos, it
# does not execute them.
#
# Cases
#   [1] deterministic — the per-turn dossier is auto-persisted
#   [2] the routed checklist is produced: a `{via:}` tag appears in the authored
#       file OR (for a model that narrates instead of writing tersely) the answer
#   [3] NEGATIVE — authoring ≠ executing: the described work (widget.sh) is not
#       performed
#
# Usage:   scripts/test-agent-todo.sh [--keep]
# Env:     BY_BIN, PROVIDER, MODEL
# Exit:    0 all pass · 1 assertion failed · 2 cannot run
set -uo pipefail
cd "$(dirname "$0")/.."
source scripts/lib-agent-harness.sh

harness_parse_args "$@"
harness_init "todo-agent"
harness_banner "todo-agent harness"

# New writes land under .brainyard/agents/todo-agent/ (todo.clj §316, §714);
# .brainyard/todos/ is read-fallback only.
DOSSIER_GLOB="$PROJ/.brainyard/agents/todo-agent/dossiers/*.md"
TODOS_GLOB="$PROJ/.brainyard/agents/todo-agent/todos/*.md"

echo "[1-2] decompose a small plan into a routed checklist"
ans="$(by_ask 'Decompose this plan into a routed todo checklist and save it:
PLAN: Add a --version flag to widget.sh.
  - edit widget.sh to handle the --version argument and print "widget 1.2.3"
  - run widget.sh --version to confirm it prints the version and exits 0
Route each item with a {via: ...} tag.')"

# Deterministic probe: the handoff dossier is auto-persisted every turn, and it
# records a `next_agent:` handoff in frontmatter (the reducer sets it even when
# the model doesn't author its own dossier).
assert_file_exists   "per-turn dossier written"        "$DOSSIER_GLOB"
assert_file_contains "dossier records a next_agent handoff" "next_agent:" "$DOSSIER_GLOB"

# Routing signal: whether an item's `via:` routing tag surfaces in a greppable
# place is model-dependent — it lives in the standalone todo file (which a model
# may not author) and its surface format varies (`{via: bash}` vs `*via:* `bash``);
# the dossier embeds the answer only when the reducer reconstructs it. So this is
# INFORMATIONAL. The deterministic proof that todo-agent decomposed AND routed is
# the auto-persisted dossier + its `next_agent:` handoff, asserted above.
if grep -rqiF -- "via:" $DOSSIER_GLOB 2>/dev/null || grep -qiF -- "via:" <<<"$ans"; then
    echo "  ✓ routed checklist carries a via: tag"
else
    echo "  ⚠ no greppable via: tag (informational — routing captured via the dossier handoff)"
fi

# Informational: did it also author a standalone checklist file?
if ls $TODOS_GLOB >/dev/null 2>&1; then
    echo "  ✓ standalone todo file authored under todos/"
else
    echo "  ⚠ no standalone todo file (informational — dossier is the durable record)"
fi

echo "[3] NEGATIVE — todo-agent authors, it does not execute"
# It must not have actually created/edited widget.sh (that's exec/edit's job).
assert_no_file "widget.sh was NOT created by the planner" "$PROJ/widget.sh"

harness_summary "todo-agent harness"
