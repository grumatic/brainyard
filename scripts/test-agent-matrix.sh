#!/usr/bin/env bash
# Run the per-agent harness family across a set of providers and print a
# pass/fail matrix. Sibling of test-tui-matrix.sh, but for the headless
# `test-agent-<name>.sh` scripts (which probe durable artifacts, not the TUI).
#
# Each cell runs one harness against one provider:model. The harness owns its
# own isolation (throwaway user + throwaway project), so cells are independent.
#
# Override the axes with $HARNESSES / $PROVIDERS:
#   HARNESSES='test-agent-edit.sh test-agent-plan.sh' \
#   PROVIDERS='claude-code:haiku' scripts/test-agent-matrix.sh
#
#   BY_BIN=projects/agent-tui-app/target/by scripts/test-agent-matrix.sh
#
# Provider format: <provider>:<model>  (colon-delimited).
# Exit: 0 if every cell passed; 1 if any cell failed (exit 2 from a harness —
#       "cannot run" — is surfaced but does NOT fail the matrix, matching the
#       environment-vs-agent split the memory/tui harnesses use).
set -uo pipefail
cd "$(dirname "$0")/.."

HARNESSES="${HARNESSES:-test-agent-coact.sh test-agent-react.sh test-agent-edit.sh test-agent-plan.sh test-agent-todo.sh test-agent-exec.sh test-agent-explore.sh test-agent-eval.sh test-agent-main.sh}"
PROVIDERS="${PROVIDERS:-claude-code:haiku}"

declare -a CELLS=()
overall=0

for h in $HARNESSES; do
    for entry in $PROVIDERS; do
        provider="${entry%%:*}"
        model="${entry#*:}"
        echo
        echo "=================================================="
        echo " $h × $provider:$model"
        echo "=================================================="
        PROVIDER="$provider" MODEL="$model" "scripts/$h"
        rc=$?
        case "$rc" in
            0) CELLS+=( "$h|$entry|PASS" ) ;;
            2) CELLS+=( "$h|$entry|SKIP(cannot-run)" ) ;;
            *) CELLS+=( "$h|$entry|FAIL($rc)" ); overall=1 ;;
        esac
    done
done

echo
echo "=================== matrix summary ==================="
printf '%-26s %-30s %s\n' HARNESS PROVIDER:MODEL STATUS
for c in "${CELLS[@]}"; do
    IFS='|' read -r a p s <<<"$c"
    printf '%-26s %-30s %s\n' "$a" "$p" "$s"
done
echo "====================================================="
exit $overall
