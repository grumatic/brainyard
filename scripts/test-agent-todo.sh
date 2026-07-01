#!/usr/bin/env bash
# todo-agent harness — decompose a task into a routed checklist.
#
# todo-agent authors a checklist under `.brainyard/todos/<slug>.md` whose items
# carry `{via: …}` routing tags (edit-agent / bash / mcp / explore-agent /
# read-only / manual) and reconciles via `todo$sync` (todo.clj §110, §1139).
# Its HARD RULE: it authors todos, it does not execute them.
#
# Cases
#   [1] a todo file is written with `- [ ]` checklist items
#   [2] items carry `{via: …}` routing tags
#   [3] NEGATIVE — the target work was NOT performed (authoring ≠ executing):
#       the file the todo describes is not created by todo-agent
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

# New writes land under .brainyard/agents/todo-agent/todos/ (todo.clj §316);
# .brainyard/todos/ is read-fallback only.
TODOS_GLOB="$PROJ/.brainyard/agents/todo-agent/todos/*.md"

echo "[1-2] decompose a small plan into a routed checklist"
ans="$(by_ask 'Decompose this plan into a routed todo checklist and save it:
PLAN: Add a --version flag to widget.sh.
  - edit widget.sh to handle the --version argument and print "widget 1.2.3"
  - run widget.sh --version to confirm it prints the version and exits 0
Route each item with a {via: ...} tag.')"

assert_file_exists   "todo file written"        "$TODOS_GLOB"
assert_file_contains "has an unchecked item"    "- [ ]" "$TODOS_GLOB"
assert_file_contains "items carry a {via:} tag" "{via:" "$TODOS_GLOB"

echo "[3] NEGATIVE — todo-agent authors, it does not execute"
# It must not have actually created/edited widget.sh (that's exec/edit's job).
assert_no_file "widget.sh was NOT created by the planner" "$PROJ/widget.sh"

harness_summary "todo-agent harness"
