#!/usr/bin/env bash
# End-to-end memory-recall harness for `by`.
#
# Verifies the persistent, session-scoped L1/L2 memory store: facts stated in
# early turns are recalled in later turns PURELY through SQLite recall — NOT
# through in-prompt conversation history.
#
# How the isolation works
# -----------------------
# Each turn is a SEPARATE one-shot `ask --json` process, so no in-memory
# conversation history carries between turns (one-shot ask uses a standalone,
# empty session each process). BUT every turn pins the SAME `--session <id>`
# (and `-u <user-id>`), and L1/L2 recall is *session-scoped*
# (memory/contextual-recall: "Restrict L1/L2 reads to this session"). So a later
# process recalls earlier facts ONLY by reading the prior episodes back out of
# the user-scoped SQLite store (~/.brainyard/memory/<uid>.db) filtered to the
# shared session-id. That is exactly the persistent-recall path under test.
#   (Cross-SESSION recall is a different path — L3 semantic facts, populated by
#    essence/consolidation — and is intentionally NOT what this harness covers.)
#
# Two-layer verification
# ----------------------
#   - Phase 2 reads the SQLite DB directly (deterministic; proves capture).
#   - Phase 3 asks recall questions in fresh processes (proves session-scoped
#     L2 recall surfaces the facts to the agent).
# A split result localizes a regression to capture vs. recall.
#
# Synthetic, non-colliding facts (name "Wexler", color "teal", project
# "Photon-7") are used so a hit cannot come from this repo's own
# .brainyard/memory project files or the user_role memory — only from L2.
#
# Isolation: a unique throwaway `-u mem-harness-<pid>-<epoch>` gets its OWN db
# file, removed on exit (`--keep-db` to retain). The real user's memory is
# never touched. The context-graph overlay stays OFF (default).
#
# Usage:
#   scripts/test-memory-recall.sh [--keep-db]
#
# Env overrides:
#   BY_BIN     Path to the native `by` binary (e.g. projects/agent-tui-app/target/by).
#              Much faster per call. Default: unset => `bb tui` (JVM from source).
#   PROVIDER   LM provider. Default: claude-code
#   MODEL      LM model.    Default: haiku
#   MEM_DIR    Memory base dir. Default: ~/.brainyard/memory
#
# Exit codes:
#   0 — all assertions passed
#   1 — at least one assertion failed
#   2 — could not run (missing tool, provider auth, runner failure)
set -uo pipefail
cd "$(dirname "$0")/.."

KEEP_DB=0
[[ "${1:-}" == "--keep-db" ]] && KEEP_DB=1

PROVIDER="${PROVIDER:-claude-code}"
MODEL="${MODEL:-haiku}"
MEM_DIR="${MEM_DIR:-$HOME/.brainyard/memory}"
BY_BIN="${BY_BIN:-}"

# Unique, disposable identity → its own ~/.brainyard/memory/<uid>.db.
# A single shared session-id is pinned across ALL turns so session-scoped
# L1/L2 recall links them.
USER_ID="mem-harness-$$-$(date +%s)"
SESSION_ID="memsess-$$-$(date +%s)"
DB_PATH="$MEM_DIR/$USER_ID.db"

# ---------- tool guards ----------
for tool in jq sqlite3; do
    command -v "$tool" >/dev/null 2>&1 || {
        echo "FATAL: '$tool' is required but not on PATH." >&2
        exit 2
    }
done

# ---------- cleanup ----------
cleanup() {
    if (( KEEP_DB )); then
        echo "  (kept memory db: $DB_PATH)"
    else
        rm -f "$DB_PATH" "$DB_PATH-wal" "$DB_PATH-shm" 2>/dev/null || true
    fi
}
trap cleanup EXIT INT TERM

# ---------- runner ----------
# by_ask <question>  → prints the agent's answer text (from --json .answer).
# Pins -u/-s so all turns share the session-scoped memory. Exits 2 on a
# non-content failure (provider auth, runner crash, no JSON).
by_ask() {
    local q="$1" raw json
    if [[ -n "$BY_BIN" ]]; then
        raw="$("$BY_BIN" ask --json -u "$USER_ID" -s "$SESSION_ID" -p "$PROVIDER" -m "$MODEL" "$q" 2>/dev/null)"
    else
        raw="$(bb tui ask --json -u "$USER_ID" -s "$SESSION_ID" -p "$PROVIDER" -m "$MODEL" "$q" 2>/dev/null)"
    fi
    # Isolate the single-line JSON object (the runner may emit other noise on
    # stdout via the bb wrapper; --json routes incidental agent output to stderr).
    json="$(grep -E '^\{.*\}$' <<<"$raw" | tail -1)"
    if [[ -z "$json" ]]; then
        echo "FATAL: no JSON from runner for: $q" >&2
        echo "--- raw output ---" >&2; echo "$raw" | tail -5 >&2
        exit 2
    fi
    if [[ "$(jq -r '.success' <<<"$json")" != "true" ]]; then
        echo "FATAL: runner reported failure: $(jq -r '.error // "unknown"' <<<"$json")" >&2
        exit 2
    fi
    jq -r '.answer // ""' <<<"$json"
}

# ---------- assertions ----------
PASS=0; FAIL=0
# assert_contains <name> <needle> <haystack>  (case-insensitive substring)
assert_contains() {
    local name="$1" needle="$2" hay="$3"
    if grep -qiF -- "$needle" <<<"$hay"; then
        echo "  ✓ $name"
        PASS=$(( PASS + 1 ))
    else
        echo "  ✗ $name (expected to find: '$needle')"
        echo "    --- got ---"; echo "$hay" | head -4 | sed 's/^/    /'
        FAIL=$(( FAIL + 1 ))
    fi
}

echo "== memory-recall harness =="
echo "   runner=${BY_BIN:-bb tui}  provider=$PROVIDER  model=$MODEL"
echo "   user-id=$USER_ID"
echo "   session=$SESSION_ID  (pinned across all turns)"
echo "   db=$DB_PATH"
echo

# ---------- Phase 1: state facts (3 separate ask processes, shared session) ----------
echo "[1] state facts"
by_ask 'My name is Wexler. Acknowledge in one short sentence.'                         >/dev/null
echo "  · stated name"
by_ask 'My favorite color is teal. Acknowledge in one short sentence.'                 >/dev/null
echo "  · stated color"
by_ask 'My current project is codenamed Photon-7. Acknowledge in one short sentence.'  >/dev/null
echo "  · stated project"

# Belt-and-suspenders: each process already drained on close, but give the FS a beat.
sleep 1

# ---------- Phase 2: deterministic capture check (sqlite3, no LLM) ----------
echo "[2] capture check (sqlite3 over episodes)"
if [[ ! -f "$DB_PATH" ]]; then
    echo "  ✗ memory db was never created at $DB_PATH"
    FAIL=$(( FAIL + 1 ))
    captured=""
else
    captured="$(sqlite3 "$DB_PATH" \
        "SELECT content FROM episodes WHERE user_id='$USER_ID' ORDER BY timestamp;" 2>/dev/null)"
fi
assert_contains "name captured"    "Wexler"   "$captured"
assert_contains "color captured"   "teal"     "$captured"
assert_contains "project captured" "Photon-7" "$captured"

# ---------- Phase 3: recall (4 more separate ask processes, same uid+session) ----------
echo "[3] recall (fresh processes — only session-scoped L2 recall can know)"
ans="$(by_ask 'What is my name? Reply with just the name.')"
assert_contains "recall name"    "Wexler"   "$ans"
ans="$(by_ask 'What is my favorite color? Answer with one word.')"
assert_contains "recall color"   "teal"     "$ans"
ans="$(by_ask 'What is my current project codename? Reply with just the codename.')"
assert_contains "recall project" "Photon-7" "$ans"
ans="$(by_ask 'Summarize everything you know about me in one line.')"
assert_contains "combined: name"    "Wexler"   "$ans"
assert_contains "combined: color"   "teal"     "$ans"
assert_contains "combined: project" "Photon-7" "$ans"

# ---------- summary ----------
echo
echo "== memory-recall harness  pass=$PASS fail=$FAIL =="
exit $(( FAIL > 0 ? 1 : 0 ))
