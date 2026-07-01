#!/usr/bin/env bash
# Shared harness library for the per-agent `test-agent-<name>.sh` family.
#
# The memory-* harnesses each inline their own runner/assert/isolation block
# (they predate this family and only need a memory DB). The per-agent harnesses
# additionally need an ISOLATED PROJECT so the durable artifacts each specialist
# writes — plans, todos, dossiers, verdicts, edits, routing logs — land in a
# throwaway `.brainyard/` tree instead of polluting this repo. This library
# centralises that setup plus the house-style runner, assertions, and the
# 0/1/2 exit contract so each per-agent script stays a short, focused list of
# cases.
#
# What a sourcing script gets
# ---------------------------
#   Variables (after `harness_init`):
#     AGENT       the agent under test (`-a`), from $1 or the script's default
#     PROVIDER    LM provider   (default claude-code; env override)
#     MODEL       LM model      (default haiku;       env override)
#     BY_BIN      native binary path, or empty => `bb tui` (JVM from source)
#     USER_ID     throwaway `-u` id → its OWN ~/.brainyard/memory/<uid>.db
#     SESSION_ID  pinned `-s` id (so multi-turn cases share one session)
#     PROJ        throwaway git repo used as `-C` working-dir AND project root
#                 (BY_PROJECT_DIR) → all `.brainyard/` artifacts land here
#     PASS FAIL   assertion tallies
#
#   Functions:
#     by_ask <question>            → prints .answer; exit 2 on runner failure
#     by_ask_in <turn-agent> <q>   → same, overriding the agent for one turn
#     proj_file <relpath>          → absolute path under $PROJ
#     trajectory_channels          → prints the per-iteration channel strings
#     assert_contains <n> <needle> <hay>     (case-insensitive substring)
#     assert_not_contains <n> <needle> <hay>
#     assert_file_exists <n> <glob>          (glob may match many; ≥1 = pass)
#     assert_file_contains <n> <needle> <glob>
#     assert_no_file <n> <glob>
#     harness_summary <title>      → prints tally, `exit`s per the contract
#
# Isolation & cleanup: a unique `-u`/PROJ per run; a `trap` removes the memory
# DB and the throwaway project on exit (KEEP=1 / --keep to retain both).
#
# Exit contract (inherited by every sourcing script):
#   0 — all assertions passed
#   1 — at least one assertion failed
#   2 — could not run (missing tool, provider auth, runner failure)

# --- guard: must be sourced, not executed ---------------------------------
(return 0 2>/dev/null) || {
    echo "lib-agent-harness.sh is a library; source it from a test-agent-*.sh." >&2
    exit 2
}

# harness_init <default-agent>  — call once, after parsing --keep.
harness_init() {
    local default_agent="${1:?harness_init needs a default agent name}"

    # Positional $1 (if not a flag) overrides the agent; env still wins for p/m.
    AGENT="$default_agent"
    if [[ -n "${HARNESS_AGENT_ARG:-}" ]]; then AGENT="$HARNESS_AGENT_ARG"; fi

    PROVIDER="${PROVIDER:-claude-code}"
    MODEL="${MODEL:-haiku}"
    BY_BIN="${BY_BIN:-}"
    MEM_DIR="${MEM_DIR:-$HOME/.brainyard/memory}"
    KEEP="${KEEP:-0}"

    local stamp="$$-$(date +%s)"
    USER_ID="agent-harness-$stamp"
    SESSION_ID="agentsess-$stamp"
    DB_PATH="$MEM_DIR/$USER_ID.db"

    # ---------- tool guards ----------
    local tool
    for tool in jq git; do
        command -v "$tool" >/dev/null 2>&1 || {
            echo "FATAL: '$tool' is required but not on PATH." >&2; exit 2; }
    done
    # sqlite3 only needed by scripts that probe the DB; guard lazily there.

    # ---------- throwaway project (git repo → artifacts land here) ----------
    PROJ="$(mktemp -d "${TMPDIR:-/tmp}/by-agent-harness.XXXXXX")" || {
        echo "FATAL: could not create temp project dir." >&2; exit 2; }
    (
        cd "$PROJ" || exit 1
        git init -q .
        git config user.email harness@brainyard.test
        git config user.name  "agent harness"
        # An initial commit so `git diff` / rollback have a clean baseline.
        : > .gitkeep && git add -A && git commit -q -m "harness baseline"
    ) || { echo "FATAL: could not init temp git project at $PROJ." >&2; exit 2; }
    # project-dir re-derives from working-dir via git-root walk; pin it hard so
    # there is zero chance artifacts escape to the real repo.
    export BY_PROJECT_DIR="$PROJ"

    PASS=0; FAIL=0
    trap harness_cleanup EXIT INT TERM
}

harness_cleanup() {
    if (( KEEP )); then
        echo "  (kept memory db: $DB_PATH)"
        echo "  (kept project:   $PROJ)"
    else
        rm -f "$DB_PATH" "$DB_PATH-wal" "$DB_PATH-shm" 2>/dev/null || true
        [[ -n "${PROJ:-}" && -d "$PROJ" ]] && rm -rf "$PROJ" 2>/dev/null || true
    fi
}

# ---------- runner ----------
# _by <args...> → raw stdout of the runner (native binary or bb tui).
_by() { if [[ -n "$BY_BIN" ]]; then "$BY_BIN" "$@"; else bb tui "$@"; fi; }

# by_ask_in <agent> <question> → .answer text; exit 2 on a non-content failure.
# Pins -u/-s and -C/BY_PROJECT_DIR so memory + artifacts stay in the sandbox.
by_ask_in() {
    local agent="$1" q="$2" raw json
    raw="$(_by ask --json -u "$USER_ID" -s "$SESSION_ID" \
              -p "$PROVIDER" -m "$MODEL" -C "$PROJ" -a "$agent" "$q" 2>/dev/null)"
    json="$(grep -E '^\{.*\}$' <<<"$raw" | tail -1)"
    if [[ -z "$json" ]]; then
        echo "FATAL: no JSON from runner for: $q" >&2
        echo "--- raw output ---" >&2; echo "$raw" | tail -6 >&2
        exit 2
    fi
    if [[ "$(jq -r '.success' <<<"$json")" != "true" ]]; then
        echo "FATAL: runner reported failure: $(jq -r '.error // "unknown"' <<<"$json")" >&2
        exit 2
    fi
    jq -r '.answer // ""' <<<"$json"
}

# by_ask <question> → run against $AGENT (the agent under test).
by_ask() { by_ask_in "$AGENT" "$1"; }

# ---------- artifact helpers ----------
proj_file() { printf '%s/%s' "$PROJ" "$1"; }

# trajectory_available → 0 if this session persisted a trajectory.edn.
# IMPORTANT: one-shot `ask` does NOT persist a session — trajectory.edn is only
# written for durable (TUI) sessions. So the structural channel probe below is
# unavailable on the headless surface; callers must `trajectory_available` and
# skip (not fail) when it is absent. The probe remains for future tmux harnesses.
trajectory_file() { printf '%s/.brainyard/sessions/%s/trajectory.edn' "$PROJ" "$SESSION_ID"; }
trajectory_available() { [[ -f "$(trajectory_file)" ]]; }

# trajectory_channels → one channel string per recorded iteration, e.g.
#   code
#   tool
#   none
# (trajectory records `:channel "code"` as a STRING, not a keyword.)
trajectory_channels() {
    local traj; traj="$(trajectory_file)"
    [[ -f "$traj" ]] || return 0
    grep -oE ':channel "[a-z]+"' "$traj" | sed -E 's/:channel "([a-z]+)"/\1/'
}

# note_skip <name> <reason> — record an informational skip (no PASS/FAIL tally),
# for a probe that cannot run on this surface. Keeps the exit contract honest:
# a skip is neither a pass nor a failure.
note_skip() { echo "  ⚠ $1 — SKIPPED: $2"; }

# note_info <ok:0|1> <name-if-ok> <name-if-not> — an informational (non-failing)
# observation, e.g. a model-dependent authoring step that is nice-to-have but not
# contractually required. Never touches FAIL.
note_info() {
    if "$1"; then echo "  ✓ $2"; else echo "  ⚠ $3 (informational — not required)"; fi
}

# ---------- assertions (never abort the suite) ----------
assert_contains() {   # <name> <needle> <haystack>
    local name="$1" needle="$2" hay="$3"
    if grep -qiF -- "$needle" <<<"$hay"; then
        echo "  ✓ $name"; PASS=$((PASS+1))
    else
        echo "  ✗ $name (expected to find: '$needle')"
        echo "$hay" | head -4 | sed 's/^/    /'; FAIL=$((FAIL+1))
    fi
}

assert_not_contains() {   # <name> <needle> <haystack>
    local name="$1" needle="$2" hay="$3"
    if grep -qiF -- "$needle" <<<"$hay"; then
        echo "  ✗ $name (unexpectedly found: '$needle')"
        echo "$hay" | head -4 | sed 's/^/    /'; FAIL=$((FAIL+1))
    else
        echo "  ✓ $name"; PASS=$((PASS+1))
    fi
}

# assert_file_exists <name> <glob> — ≥1 path matching the glob = pass.
assert_file_exists() {
    local name="$1" glob="$2"
    # shellcheck disable=SC2086
    local matches=( $glob )
    if (( ${#matches[@]} >= 1 )) && [[ -e "${matches[0]}" ]]; then
        echo "  ✓ $name (${matches[0]#"$PROJ"/})"; PASS=$((PASS+1))
    else
        echo "  ✗ $name (no file matched: ${glob#"$PROJ"/})"; FAIL=$((FAIL+1))
    fi
}

# assert_no_file <name> <glob> — negative: no path may match.
assert_no_file() {
    local name="$1" glob="$2"
    # shellcheck disable=SC2086
    local matches=( $glob )
    if (( ${#matches[@]} >= 1 )) && [[ -e "${matches[0]}" ]]; then
        echo "  ✗ $name (unexpected file: ${matches[0]#"$PROJ"/})"; FAIL=$((FAIL+1))
    else
        echo "  ✓ $name"; PASS=$((PASS+1))
    fi
}

# assert_file_contains <name> <needle> <glob> — some matching file holds needle.
assert_file_contains() {
    local name="$1" needle="$2" glob="$3"
    # shellcheck disable=SC2086
    local matches=( $glob )
    if (( ${#matches[@]} >= 1 )) && grep -rqiF -- "$needle" "${matches[@]}" 2>/dev/null; then
        echo "  ✓ $name"; PASS=$((PASS+1))
    else
        echo "  ✗ $name (no matching file contained: '$needle')"; FAIL=$((FAIL+1))
    fi
}

harness_banner() {   # <title>
    echo "== $1 =="
    echo "   agent=$AGENT  runner=${BY_BIN:-bb tui}  provider=$PROVIDER  model=$MODEL"
    echo "   user=$USER_ID  session=$SESSION_ID"
    echo "   project=$PROJ"
    echo
}

harness_summary() {   # <title>
    echo
    echo "== $1  pass=$PASS fail=$FAIL =="
    exit $(( FAIL > 0 ? 1 : 0 ))
}

# harness_parse_args "$@" — house convention: optional leading <agent>
# positional, plus a --keep flag anywhere. Call BEFORE harness_init.
harness_parse_args() {
    local a
    for a in "$@"; do
        case "$a" in
            --keep|--keep-db) KEEP=1 ;;
            -*) : ;;                      # ignore unknown flags
            *) HARNESS_AGENT_ARG="$a" ;;  # first bare token = agent override
        esac
    done
}
