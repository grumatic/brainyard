#!/usr/bin/env bash
# Cross-session L3 memory harness for `by` (heuristic consolidation).
#
# Verifies the durable, CROSS-session path: facts stated in session A are
# recalled in a DIFFERENT session B — which is only possible via L3 semantic
# facts, because L1/L2 recall is session-scoped (see test-memory-recall.sh for
# the in-session L1/L2 path).
#
# Flow
# ----
#   1. State facts over several one-shot `ask` turns in session A (→ L2).
#   2. `by memory consolidate -s <A>` promotes L2 → L3 (deterministic heuristic
#      reducer; per-(role, time-window) digest). No LLM, no graph stack.
#   3. Deterministic checks: `by memory stats` shows semantic-facts > 0, and the
#      semantic_facts rows contain the stated facts.
#   4. Recall in a FRESH session B (`ask -s <B>`): L2 is filtered out by
#      session, so a correct answer can only come from cross-session L3 recall.
#
# Synthetic, non-colliding facts (name "Wexler", color "teal", project
# "Photon-7") are used so a hit cannot come from this repo's own
# .brainyard/memory project files — only from L3.
#
# Isolation: a unique throwaway `-u mem-l3-<pid>-<epoch>` gets its OWN db file,
# removed on exit (`--keep-db` to retain). The graph overlay stays OFF — this
# covers the heuristic tier. For the graph-community tier see
# scripts/test-memory-l3-graph.sh.
#
# Usage:   scripts/test-memory-l3-recall.sh [--keep-db]
# Env:     BY_BIN (native binary path; default `bb tui`), PROVIDER, MODEL, MEM_DIR
# Exit:    0 all pass · 1 assertion failed · 2 cannot run (tooling/provider)
set -uo pipefail
cd "$(dirname "$0")/.."

KEEP_DB=0
[[ "${1:-}" == "--keep-db" ]] && KEEP_DB=1

PROVIDER="${PROVIDER:-claude-code}"
MODEL="${MODEL:-haiku}"
MEM_DIR="${MEM_DIR:-$HOME/.brainyard/memory}"
BY_BIN="${BY_BIN:-}"

USER_ID="mem-l3-$$-$(date +%s)"
SESS_A="l3-A-$$-$(date +%s)"   # facts stated here
SESS_B="l3-B-$$-$(date +%s)"   # recalled here (different session)
DB_PATH="$MEM_DIR/$USER_ID.db"

for tool in jq sqlite3; do
    command -v "$tool" >/dev/null 2>&1 || { echo "FATAL: '$tool' required." >&2; exit 2; }
done

cleanup() {
    if (( KEEP_DB )); then echo "  (kept memory db: $DB_PATH)"
    else rm -f "$DB_PATH" "$DB_PATH-wal" "$DB_PATH-shm" 2>/dev/null || true; fi
}
trap cleanup EXIT INT TERM

# by <args...> → raw stdout of the runner (native binary or bb tui).
by() {
    if [[ -n "$BY_BIN" ]]; then "$BY_BIN" "$@"; else bb tui "$@"; fi
}

# json_field <jq-filter> reads the single JSON object line from stdin.
json_field() { grep -E '^\{.*\}$' | tail -1 | jq -r "$1"; }

# ask <question> → agent answer text (.answer); exit 2 on runner failure.
ask() {
    local raw json
    raw="$(by ask --json -u "$USER_ID" -s "$1" -p "$PROVIDER" -m "$MODEL" "$2" 2>/dev/null)"
    json="$(grep -E '^\{.*\}$' <<<"$raw" | tail -1)"
    [[ -z "$json" ]] && { echo "FATAL: no JSON for: $2" >&2; echo "$raw" | tail -5 >&2; exit 2; }
    [[ "$(jq -r '.success' <<<"$json")" == "true" ]] || {
        echo "FATAL: runner failure: $(jq -r '.error // "?"' <<<"$json")" >&2; exit 2; }
    jq -r '.answer // ""' <<<"$json"
}

PASS=0; FAIL=0
assert_contains() {
    local name="$1" needle="$2" hay="$3"
    if grep -qiF -- "$needle" <<<"$hay"; then echo "  ✓ $name"; PASS=$((PASS+1))
    else echo "  ✗ $name (want: '$needle')"; echo "$hay" | head -4 | sed 's/^/    /'; FAIL=$((FAIL+1)); fi
}
assert_ge() {
    local name="$1" got="$2" min="$3"
    if [[ "${got:-0}" =~ ^[0-9]+$ ]] && (( got >= min )); then echo "  ✓ $name ($got ≥ $min)"; PASS=$((PASS+1))
    else echo "  ✗ $name (got '$got', want ≥ $min)"; FAIL=$((FAIL+1)); fi
}

echo "== cross-session L3 harness (heuristic) =="
echo "   runner=${BY_BIN:-bb tui}  provider=$PROVIDER  model=$MODEL"
echo "   user=$USER_ID  sessionA=$SESS_A  sessionB=$SESS_B"
echo

echo "[1] state facts in session A (→ L2)"
ask "$SESS_A" 'My name is Wexler. Acknowledge in one short sentence.'                        >/dev/null; echo "  · name"
ask "$SESS_A" 'My favorite color is teal. Acknowledge in one short sentence.'                >/dev/null; echo "  · color"
ask "$SESS_A" 'My current project is codenamed Photon-7. Acknowledge in one short sentence.' >/dev/null; echo "  · project"
sleep 1

echo "[2] consolidate L2 → L3 (deterministic heuristic)"
before_facts="$(by memory stats -u "$USER_ID" --json 2>/dev/null | json_field '.["semantic-facts"]')"
report="$(by memory consolidate -u "$USER_ID" -s "$SESS_A" --json 2>/dev/null)"
produced="$(json_field '.report.produced' <<<"$report")"
assert_ge "consolidation produced an L3 fact" "$produced" 1
after_facts="$(by memory stats -u "$USER_ID" --json 2>/dev/null | json_field '.["semantic-facts"]')"
assert_ge "semantic-facts grew" "$after_facts" 1

echo "[3] L3 capture check (sqlite over semantic_facts)"
l3="$(sqlite3 "$DB_PATH" "SELECT content FROM semantic_facts WHERE user_id='$USER_ID';" 2>/dev/null)"
assert_contains "name in L3"    "Wexler"   "$l3"
assert_contains "color in L3"   "teal"     "$l3"
assert_contains "project in L3" "Photon-7" "$l3"

echo "[4] cross-session recall (fresh session B — only L3 can know)"
ans="$(ask "$SESS_B" 'Based on what you remember about me, what is my favorite color and my project codename? Be specific.')"
assert_contains "recall color"   "teal"     "$ans"
assert_contains "recall project" "Photon-7" "$ans"

echo
echo "== cross-session L3 harness (heuristic)  pass=$PASS fail=$FAIL =="
exit $(( FAIL > 0 ? 1 : 0 ))
