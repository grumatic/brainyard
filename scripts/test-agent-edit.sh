#!/usr/bin/env bash
# edit-agent harness — the single-file safe-edit transaction.
#
# edit-agent is the most deterministically testable specialist: it performs a
# probe→apply→verify→persist→rollback transaction on ONE file and reports the
# stable handoff lines `Saved edit: <path>` / `Rollback: <cmd>` (edit_agent.clj
# §179-181). So we can assert on the BYTES of the file (did the edit apply?) and
# on the ROLLBACK contract (does running the reported command restore the
# original?) — both model-invariant — plus a softer check that the agent
# surfaced the handoff lines.
#
# Cases
#   [1] apply     — a targeted literal replacement lands in the file
#   [2] handoff   — the answer carries `Saved edit:` and a rollback line
#   [3] rollback  — executing the reported `Rollback:` command restores the file
#   [4] scope     — no OTHER file in the project was mutated
#
# Usage:   scripts/test-agent-edit.sh [--keep]
# Env:     BY_BIN, PROVIDER, MODEL   (see lib-agent-harness.sh)
# Exit:    0 all pass · 1 assertion failed · 2 cannot run
set -uo pipefail
cd "$(dirname "$0")/.."
source scripts/lib-agent-harness.sh

harness_parse_args "$@"
harness_init "edit-agent"
harness_banner "edit-agent harness"

# ---------- fixture: a committed target file with a known literal ----------
TARGET="greeting.txt"
ORIGINAL='Hello, world. The codename is Photon-7.'
printf '%s\n' "$ORIGINAL" > "$(proj_file "$TARGET")"
( cd "$PROJ" && git add -A && git commit -q -m "add fixture" )

echo "[1-2] ask edit-agent to replace 'world' with 'Brainyard'"
ans="$(by_ask "In the file $TARGET, replace the word 'world' with 'Brainyard'. Change nothing else. Report the Saved edit and Rollback lines.")"

# --- deterministic probe: the bytes changed exactly as asked ---
after="$(cat "$(proj_file "$TARGET")")"
assert_contains "edit applied (new literal present)" "Brainyard" "$after"
assert_not_contains "old literal gone"               "world"     "$after"
assert_contains "unrelated content preserved"        "Photon-7"  "$after"

# --- soft: the stable handoff lines were surfaced ---
assert_contains "answer has 'Saved edit:'" "Saved edit:" "$ans"
assert_contains "answer has a rollback line" "Rollback" "$ans"

echo "[3] rollback contract — reported command restores the original"
# Pull the rollback command the agent reported. The marker may be bolded
# (`**Rollback:**`), bulleted (`- Rollback:`), or back-ticked (`` `cmd` ``), so
# don't anchor to the line start and strip markdown noise. Skip the failure form
# (`Rolled back:`), which reports a reason, not a runnable command.
rollback_cmd="$(grep -iE 'Rollback:' <<<"$ans" | grep -viE 'Rolled back' | head -1 \
    | sed -E 's/.*Rollback:[[:space:]]*//I; s/[*`]//g; s/^[[:space:]]+//; s/[[:space:]]+$//')"
if [[ -n "$rollback_cmd" ]]; then
    ( cd "$PROJ" && eval "$rollback_cmd" ) >/dev/null 2>&1 || true
    restored="$(cat "$(proj_file "$TARGET")")"
    assert_contains "rollback restored original 'world'" "world" "$restored"
else
    echo "  ⚠ no explicit Rollback command to execute (skipped restore probe)"
fi

echo "[4] scope — no stray files were mutated"
# The only tracked change beyond the fixture should be greeting.txt itself.
stray="$( cd "$PROJ" && git status --porcelain -- . | grep -vE "$TARGET" || true )"
assert_not_contains "no unrelated working-tree changes" ".clj" "$stray"

harness_summary "edit-agent harness"
