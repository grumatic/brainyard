#!/usr/bin/env bash
# Automatic cross-session L3 consolidation harness for `by`.
#
# The sibling test-memory-l3-recall.sh proves the L2→L3 reducer works when
# invoked MANUALLY (`by memory consolidate`). This harness proves the SAME
# promotion happens AUTOMATICALLY — with no `consolidate` call anywhere — via
# the rev-3 consolidation hooks (memory-agent-design.md §10.0):
#
#   - session-end flush (:agent.instance/closed) — when a root agent closes,
#     it runs a final L2→L3 reduce over the session. `by ask` closes its agent
#     in a finally (main.clj cmd-ask), so EVERY one-shot ask flushes on exit.
#   - cadence (:agent.ask/post) — every N turns; not exercised here because
#     one-shot asks are separate processes (the per-session counter resets),
#     so we lean on the flush, which is N-independent.
#
# Both hooks are gated on :enable-memory-consolidation (default OFF). We flip it
# on for the positive run via BY_ENABLE_MEMORY_CONSOLIDATION=true (a real env
# var always wins over config), and OFF for a negative control to prove the gate.
#
# Flow
# ----
#   POSITIVE (consolidation ON):
#     1. State 3 facts over one-shot `ask` turns in session A (→ L2). Each ask's
#        close flushes, but the heuristic reducer needs ≥3 episodes in a
#        (role, time-window) bucket, so the early flushes are no-ops.
#     2. A 4th "trigger" ask in session A closes once all 3 facts are durable
#        (each prior process drained its capture on exit) → the flush promotes
#        L2 → L3. NO `by memory consolidate` is ever called.
#     3. Deterministic checks: `by memory stats` shows semantic-facts > 0 and the
#        rows contain the stated facts.
#     4. Cross-session recall in a FRESH session B — L2 is session-scoped, so a
#        correct answer can only come from the auto-promoted L3.
#   NEGATIVE control (consolidation OFF, separate user):
#     5. Same 4 asks with BY_ENABLE_MEMORY_CONSOLIDATION=false → semantic-facts
#        stays 0, proving the L3 in the positive run came from the gated hook,
#        not from some always-on path.
#
# Synthetic, non-colliding facts (name "Quillon", color "amber", project
# "Vortex-9") so a hit can't come from this repo's own .brainyard memory.
#
# Isolation: unique throwaway `-u` ids get their OWN db files, removed on exit
# (`--keep-db` to retain). Graph overlay stays OFF — this is the heuristic tier.
#
# Usage:   scripts/test-memory-auto-consolidate.sh [--keep-db]
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

STAMP="$$-$(date +%s)"
USER_ON="mem-auto-on-$STAMP"     # positive run (consolidation enabled)
USER_OFF="mem-auto-off-$STAMP"   # negative control (consolidation disabled)
SESS_A="auto-A-$STAMP"           # facts stated here
SESS_B="auto-B-$STAMP"           # recalled here (different session)
DB_ON="$MEM_DIR/$USER_ON.db"
DB_OFF="$MEM_DIR/$USER_OFF.db"

for tool in jq sqlite3; do
    command -v "$tool" >/dev/null 2>&1 || { echo "FATAL: '$tool' required." >&2; exit 2; }
done

cleanup() {
    if (( KEEP_DB )); then echo "  (kept memory dbs: $DB_ON, $DB_OFF)"
    else rm -f "$DB_ON" "$DB_ON-wal" "$DB_ON-shm" \
               "$DB_OFF" "$DB_OFF-wal" "$DB_OFF-shm" 2>/dev/null || true; fi
}
trap cleanup EXIT INT TERM

# by <args...> → raw stdout of the runner (native binary or bb tui).
by() {
    if [[ -n "$BY_BIN" ]]; then "$BY_BIN" "$@"; else bb tui "$@"; fi
}

# json_field <jq-filter> reads the single JSON object line from stdin.
json_field() { grep -E '^\{.*\}$' | tail -1 | jq -r "$1"; }

# ask <user> <session> <question> → agent answer text (.answer). The ambient
# BY_ENABLE_MEMORY_CONSOLIDATION decides whether the close-time flush fires.
ask() {
    local raw json
    raw="$(by ask --json -u "$1" -s "$2" -p "$PROVIDER" -m "$MODEL" "$3" 2>/dev/null)"
    json="$(grep -E '^\{.*\}$' <<<"$raw" | tail -1)"
    [[ -z "$json" ]] && { echo "FATAL: no JSON for: $3" >&2; echo "$raw" | tail -5 >&2; exit 2; }
    [[ "$(jq -r '.success' <<<"$json")" == "true" ]] || {
        echo "FATAL: runner failure: $(jq -r '.error // "?"' <<<"$json")" >&2; exit 2; }
    jq -r '.answer // ""' <<<"$json"
}

# l3_count <db> <user> → number of live semantic_facts rows (0 if db absent).
l3_count() {
    [[ -f "$1" ]] || { echo 0; return; }
    sqlite3 "$1" "SELECT COUNT(*) FROM semantic_facts WHERE user_id='$2' AND tombstoned_flag=0;" 2>/dev/null || echo 0
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
assert_eq() {
    local name="$1" got="$2" want="$3"
    if [[ "${got:-x}" == "$want" ]]; then echo "  ✓ $name (= $want)"; PASS=$((PASS+1))
    else echo "  ✗ $name (got '$got', want '$want')"; FAIL=$((FAIL+1)); fi
}

state_facts() {   # state_facts <user>
    ask "$1" "$SESS_A" 'My name is Quillon. Acknowledge in one short sentence.'                        >/dev/null; echo "  · name"
    ask "$1" "$SESS_A" 'My favorite color is amber. Acknowledge in one short sentence.'                >/dev/null; echo "  · color"
    ask "$1" "$SESS_A" 'My current project is codenamed Vortex-9. Acknowledge in one short sentence.'  >/dev/null; echo "  · project"
    sleep 1
    ask "$1" "$SESS_A" 'Thanks — please acknowledge.'                                                   >/dev/null; echo "  · trigger (close-flush over ≥3 durable episodes)"
    sleep 1
}

echo "== automatic L3 consolidation harness (session-end flush, heuristic) =="
echo "   runner=${BY_BIN:-bb tui}  provider=$PROVIDER  model=$MODEL"
echo "   user(on)=$USER_ON  user(off)=$USER_OFF  sessionA=$SESS_A  sessionB=$SESS_B"
echo

echo "[1] POSITIVE: state facts with consolidation ENABLED (no manual consolidate)"
export BY_ENABLE_MEMORY_CONSOLIDATION=true
state_facts "$USER_ON"

echo "[2] auto-consolidation check — L3 grew with no \`memory consolidate\` call"
on_facts="$(by memory stats -u "$USER_ON" --json 2>/dev/null | json_field '.["semantic-facts"]')"
assert_ge "semantic-facts auto-promoted" "$on_facts" 1
assert_ge "semantic_facts rows (sqlite)" "$(l3_count "$DB_ON" "$USER_ON")" 1

echo "[3] L3 content check (sqlite over semantic_facts)"
l3="$(sqlite3 "$DB_ON" "SELECT content FROM semantic_facts WHERE user_id='$USER_ON';" 2>/dev/null)"
assert_contains "name in L3"    "Quillon"  "$l3"
assert_contains "color in L3"   "amber"    "$l3"
assert_contains "project in L3" "Vortex-9" "$l3"

echo "[4] cross-session recall (fresh session B — only auto-promoted L3 can know)"
ans="$(ask "$USER_ON" "$SESS_B" 'Based on what you remember about me, what is my favorite color and my project codename? Be specific.')"
assert_contains "recall color"   "amber"    "$ans"
assert_contains "recall project" "Vortex-9" "$ans"

echo "[5] NEGATIVE control: same asks with consolidation DISABLED → no L3"
export BY_ENABLE_MEMORY_CONSOLIDATION=false
state_facts "$USER_OFF"
off_facts="$(by memory stats -u "$USER_OFF" --json 2>/dev/null | json_field '.["semantic-facts"]')"
assert_eq "semantic-facts stays 0 when gated off" "${off_facts:-0}" "0"
assert_eq "semantic_facts rows stay 0 (sqlite)"   "$(l3_count "$DB_OFF" "$USER_OFF")" "0"

echo
echo "== automatic L3 consolidation harness  pass=$PASS fail=$FAIL =="
exit $(( FAIL > 0 ? 1 : 0 ))
