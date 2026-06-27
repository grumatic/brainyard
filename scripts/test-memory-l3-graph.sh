#!/usr/bin/env bash
# Cross-session L3 memory harness for `by` — GRAPH-COMMUNITY tier (CR-MEM-24).
#
# The sophisticated counterpart to scripts/test-memory-l3-recall.sh (heuristic
# tier). Exercises the full context-graph pipeline:
#
#   1. State facts over one-shot `ask` turns in session A (→ L2).
#   2. `by memory graph-build -s A`  → SYNCHRONOUSLY extract entities/relations
#      from L2 into graph_nodes/graph_edges (the async capture-time extractor is
#      dropped by the 1s shutdown drain on one-shot ask, so a sync build is
#      required for scripting).
#   3. `by memory consolidate -s A --reducer community` → detect graph
#      communities and summarize each into an L3 semantic fact.
#   4. Recall in a FRESH session B: L1/L2 are session-scoped, so a correct
#      answer can only come from the cross-session L3 community summaries.
#
# Requires the context-graph tier configured: BY_ENABLE_GRAPH_MEMORY + an
# extract chat model that reliably emits structured entities. Default
# claude-code:haiku (validated). gemma3:12b-class local models extract poorly
# and will produce 0 nodes.
#
# This tier depends on a real LLM for extraction + summarization, so it is
# slower and more variable than the heuristic harness. Deterministic DB
# assertions (nodes/communities/semantic_facts) are the primary signal; the
# cross-session recall is the end-to-end confirmation.
#
# Isolation: throwaway `-u`, own db removed on exit (`--keep-db`). `ask` turns
# run under a temp working dir so the agent's project-file memory can't leak the
# synthetic facts into recall — only L3 can.
#
# Usage:   scripts/test-memory-l3-graph.sh [--keep-db]
# Env:     EXTRACT_MODEL (default claude-code:haiku), PROVIDER, MODEL, MEM_DIR, BY_BIN
# Exit:    0 all pass · 1 assertion failed · 2 cannot run (tooling/provider/graph off)
set -uo pipefail
cd "$(dirname "$0")/.."

KEEP_DB=0
[[ "${1:-}" == "--keep-db" ]] && KEEP_DB=1

PROVIDER="${PROVIDER:-claude-code}"
MODEL="${MODEL:-haiku}"
EXTRACT_MODEL="${EXTRACT_MODEL:-claude-code:haiku}"
MEM_DIR="${MEM_DIR:-$HOME/.brainyard/memory}"
BY_BIN="${BY_BIN:-}"

USER_ID="mem-l3g-$$-$(date +%s)"
SESS_A="l3g-A-$$-$(date +%s)"
SESS_B="l3g-B-$$-$(date +%s)"
DB_PATH="$MEM_DIR/$USER_ID.db"
WORK_DIR="$(mktemp -d "${TMPDIR:-/tmp}/by-l3g.XXXXXX")"

for tool in jq sqlite3; do
    command -v "$tool" >/dev/null 2>&1 || { echo "FATAL: '$tool' required." >&2; exit 2; }
done

cleanup() {
    if (( KEEP_DB )); then echo "  (kept memory db: $DB_PATH)"
    else rm -f "$DB_PATH" "$DB_PATH-wal" "$DB_PATH-shm" 2>/dev/null || true; fi
    rm -rf "$WORK_DIR" 2>/dev/null || true
}
trap cleanup EXIT INT TERM

# Plain runner (no graph env) for `ask`, isolated to the temp working dir.
by()  { if [[ -n "$BY_BIN" ]]; then "$BY_BIN" "$@"; else bb tui "$@"; fi; }
# Graph-enabled runner (subshell-scoped env) for the graph maintenance ops.
gby() { ( export BY_ENABLE_GRAPH_MEMORY=true BY_GRAPH_EXTRACT_MODEL="$EXTRACT_MODEL"; by "$@"; ); }

json1() { grep -E '^\{.*\}$' | tail -1; }
ask() {
    local raw json
    raw="$(by ask --json -u "$USER_ID" -s "$1" -C "$WORK_DIR" -p "$PROVIDER" -m "$MODEL" "$2" 2>/dev/null)"
    json="$(json1 <<<"$raw")"
    [[ -z "$json" ]] && { echo "FATAL: no JSON for: $2" >&2; echo "$raw" | tail -5 >&2; exit 2; }
    [[ "$(jq -r '.success' <<<"$json")" == "true" ]] || {
        echo "FATAL: runner failure: $(jq -r '.error // "?"' <<<"$json")" >&2; exit 2; }
    jq -r '.answer // ""' <<<"$json"
}
db_count() { sqlite3 "$DB_PATH" "SELECT count(*) FROM $1 WHERE user_id='$USER_ID';" 2>/dev/null; }

PASS=0; FAIL=0
assert_ge() {
    local name="$1" got="$2" min="$3"
    if [[ "${got:-0}" =~ ^[0-9]+$ ]] && (( got >= min )); then echo "  ✓ $name ($got ≥ $min)"; PASS=$((PASS+1))
    else echo "  ✗ $name (got '$got', want ≥ $min)"; FAIL=$((FAIL+1)); fi
}
assert_contains() {
    local name="$1" needle="$2" hay="$3"
    if grep -qiF -- "$needle" <<<"$hay"; then echo "  ✓ $name"; PASS=$((PASS+1))
    else echo "  ✗ $name (want: '$needle')"; echo "$hay" | head -4 | sed 's/^/    /'; FAIL=$((FAIL+1)); fi
}

echo "== cross-session L3 harness (graph-community) =="
echo "   runner=${BY_BIN:-bb tui}  provider=$PROVIDER:$MODEL  extract=$EXTRACT_MODEL"
echo "   user=$USER_ID  sessionA=$SESS_A  sessionB=$SESS_B  workdir=$WORK_DIR"
echo

echo "[1] state facts in session A (→ L2)"
ask "$SESS_A" 'My name is Wexler and my favorite color is teal. Acknowledge briefly.'         >/dev/null; echo "  · identity"
ask "$SESS_A" 'My project is codenamed Photon-7 and it uses teal as its theme. Acknowledge.'  >/dev/null; echo "  · project"
ask "$SESS_A" 'Wexler is the lead engineer on the Photon-7 project. Acknowledge.'             >/dev/null; echo "  · role"
sleep 1

echo "[2] graph-build: synchronous L2 → graph extraction ($EXTRACT_MODEL)"
gb="$(gby memory graph-build -u "$USER_ID" -s "$SESS_A" --json 2>/dev/null | json1)"
if [[ "$(jq -r '.report["no-extract-fn"] // false' <<<"$gb")" == "true" ]]; then
    echo "  ✗ graph tier not configured (no extract-fn). Set BY_ENABLE_GRAPH_MEMORY + EXTRACT_MODEL." >&2
    exit 2
fi
assert_ge "graph nodes extracted"  "$(db_count graph_nodes)" 3
assert_ge "graph edges extracted"  "$(db_count graph_edges)" 1

echo "[3] consolidate --reducer community (graph communities → L3)"
report="$(gby memory consolidate -u "$USER_ID" -s "$SESS_A" --reducer community --json 2>/dev/null | json1)"
assert_ge "communities produced L3 facts" "$(jq -r '.report.produced // 0' <<<"$report")" 1
assert_ge "graph_communities rows"        "$(db_count graph_communities)" 1
assert_ge "semantic_facts rows"           "$(db_count semantic_facts)"    1

echo "[4] L3 community-summary content check"
l3="$(sqlite3 "$DB_PATH" "SELECT content FROM semantic_facts WHERE user_id='$USER_ID';" 2>/dev/null)"
assert_contains "L3 mentions Wexler"   "Wexler"   "$l3"
assert_contains "L3 mentions Photon-7" "Photon-7" "$l3"

echo "[5] cross-session recall (fresh session B — only L3 can know)"
ans="$(ask "$SESS_B" 'Based only on what you remember about me, who is the lead engineer of Photon-7 and what color is its theme?')"
assert_contains "recall lead (Wexler)" "Wexler" "$ans"
assert_contains "recall theme (teal)"  "teal"   "$ans"

echo
echo "== cross-session L3 harness (graph-community)  pass=$PASS fail=$FAIL =="
exit $(( FAIL > 0 ? 1 : 0 ))
