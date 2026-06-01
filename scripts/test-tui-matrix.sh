#!/usr/bin/env bash
# Run the tmux harness across react-agent + coact-agent on the three
# configured providers. Prints a final matrix summary and exits nonzero
# if any (agent × provider) cell failed.
#
# Override the matrix with $AGENTS / $PROVIDERS, e.g.
#   AGENTS='react-agent' PROVIDERS='claude-code:opus' scripts/test-tui-matrix.sh
#
# Provider format: <provider>:<model>  (colon-delimited)
set -uo pipefail
cd "$(dirname "$0")/.."

AGENTS="${AGENTS:-react-agent coact-agent}"
PROVIDERS="${PROVIDERS:-claude-code:opus bedrock:global.anthropic.claude-sonnet-4-6 openai:gpt-4o}"

declare -a CELLS=()        # one line per cell: "<agent>|<provider>:<model>|<exit>"
overall=0

for agent in $AGENTS; do
    for entry in $PROVIDERS; do
        provider="${entry%%:*}"
        model="${entry#*:}"
        echo
        echo "=================================================="
        echo " $agent × $provider:$model"
        echo "=================================================="
        if scripts/test-tui-tmux.sh "$agent" "$provider" "$model"; then
            CELLS+=( "$agent|$entry|PASS" )
        else
            CELLS+=( "$agent|$entry|FAIL($?)" )
            overall=1
        fi
    done
done

echo
echo "=========== matrix summary ==========="
printf '%-14s %-50s %s\n' AGENT PROVIDER:MODEL STATUS
for c in "${CELLS[@]}"; do
    IFS='|' read -r a p s <<<"$c"
    printf '%-14s %-50s %s\n' "$a" "$p" "$s"
done
echo "======================================"
exit $overall
