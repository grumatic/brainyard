#!/usr/bin/env bash
# exec-agent harness — executes todo items; CANNOT author them.
#
# exec-agent's hard rule (exec_agent.clj §206, §355, §383): authoring todos is
# todo-agent's domain — `doc$create :kind :todo` is forbidden. The cleanly
# testable, model-invariant signal is this REFUSAL: asked to create a new todo
# list, exec-agent must not write a todo file; it points back to todo-agent.
#
# Cases
#   [1] NEGATIVE — asked to CREATE a todo list, no todo file appears
#   [2] the answer defers to todo-agent (routing hint surfaced)
#
# (Positive execution requires a pre-authored todo + edit-agent delegation; that
# longer arc is covered indirectly by test-agent-todo.sh + test-agent-edit.sh.
# This harness pins the one hard rule that is exec-agent's alone.)
#
# Usage:   scripts/test-agent-exec.sh [--keep]
# Env:     BY_BIN, PROVIDER, MODEL
# Exit:    0 all pass · 1 assertion failed · 2 cannot run
set -uo pipefail
cd "$(dirname "$0")/.."
source scripts/lib-agent-harness.sh

harness_parse_args "$@"
harness_init "exec-agent"
harness_banner "exec-agent harness"

# Todos would write under .brainyard/agents/todo-agent/todos/ (todo.clj §316).
# The hard rule is that exec-agent NEVER creates one there.
TODOS_GLOB="$PROJ/.brainyard/agents/todo-agent/todos/*.md"

echo "[1-2] NEGATIVE — exec-agent must NOT author a todo list"
ans="$(by_ask 'Create a brand-new todo checklist from scratch for building a login page: enumerate the steps and save it as a todo list.')"

# Deterministic hard-rule signal: doc$create :kind :todo is forbidden, so no
# todo file may be written — this is the contract, and it's model-invariant.
assert_no_file "no todo file authored by exec-agent" "$TODOS_GLOB"
# Whether it verbally defers to todo-agent is model-dependent phrasing (a weak
# model may just answer inline). Informational only — the no-file check above is
# the real assertion.
if grep -qiF "todo-agent" <<<"$ans"; then
    echo "  ✓ answer defers to todo-agent"
else
    echo "  ⚠ answer did not name todo-agent (informational — hard rule already met by no-file)"
fi

harness_summary "exec-agent harness"
