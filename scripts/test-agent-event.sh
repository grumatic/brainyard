#!/usr/bin/env bash
# event-agent harness — the user-event front door (event$*/reaction$*/watch$*).
#
# Exercises the load-bearing flow end-to-end against a real LLM:
#   define an event WITH a payload schema, then emit it WITH a payload.
# The payload schema is a Malli schema (::vector-object-arg): the agent supplies
# it as an EDN string (tool-calls) or a native vector (code) and event$define
# persists it to <project>/.brainyard/events/<slug>.edn — so the strong asserts
# are artifact-based (the def file + the stored :payload-schema), not prose.
#
# Cases
#   [1] DEFINE order/shipped with :payload-schema [:map [:order-id :string]
#       [:carrier :string]]  → def file exists AND stores the payload schema
#   [2] EMIT order/shipped with a VALID payload {:order-id … :carrier …}
#       → the turn references the event and the payload is NOT rejected
#   [3] EMIT an INVALID payload (order-id = 42) → the payload schema rejects it
#       (informational — depends on the model surfacing the tool error)
#
# Run standalone it sweeps BOTH backends and prints a per-backend matrix:
#   claude-code:opus  and  free-llm:auto
# Under test-agent-matrix.sh it behaves like every other harness in the family:
# the matrix pins PROVIDER/MODEL per cell, so a single backend runs.
#
# Usage:   scripts/test-agent-event.sh [--keep]
# Env:     BACKENDS="p1:m1 p2:m2"   explicit sweep (highest precedence)
#          PROVIDER / MODEL         single backend — how test-agent-matrix.sh drives it
#          BY_BIN                   native binary (else `bb tui` from source)
#          any provider creds are sourced from the repo .env automatically
# Exit:    0 all backends passed · 1 a backend failed · 2 nothing could run
set -uo pipefail
cd "$(dirname "$0")/.."

# Backend selection, highest precedence first:
#   1. BACKENDS          — explicit sweep
#   2. PROVIDER/MODEL    — one cell; the family convention test-agent-matrix.sh uses
#   3. the default sweep — both backends, for a standalone run
if [[ -z "${BACKENDS:-}" ]]; then
    if [[ -n "${PROVIDER:-}" || -n "${MODEL:-}" ]]; then
        BACKENDS="${PROVIDER:-claude-code}:${MODEL:-haiku}"
    else
        BACKENDS="claude-code:opus free-llm:auto"
    fi
fi

# ============================================================================
# CHILD MODE — one isolated single-backend run (re-exec of self per backend).
# ============================================================================
if [[ -n "${BY_EVENT_BACKEND:-}" ]]; then
    source scripts/lib-agent-harness.sh
    [[ " $* " == *" --keep "* ]] && export KEEP=1
    export PROVIDER="${BY_EVENT_BACKEND%%:*}"
    export MODEL="${BY_EVENT_BACKEND#*:}"
    harness_init "event-agent"
    harness_banner "event-agent · define(+payload-schema) → emit(+payload)"

    EVENT="order/shipped"
    EVENTS_GLOB="$PROJ/.brainyard/events/*.edn"
    DOSSIER_GLOB="$PROJ/.brainyard/agents/event-agent/dossiers/*.md"

    # [1] DEFINE the event WITH a payload schema.
    # by_ask (in a $() subshell) `exit 2`s on a cannot-run condition (provider
    # unavailable / limit). `exit` inside command substitution only leaves the
    # subshell, so propagate it explicitly — otherwise a cannot-run degrades into
    # a false FAIL when the assertions run against an empty answer.
    def_ans="$(by_ask "Define a user event identified by :$EVENT whose payload must match \
the Malli schema [:map [:order-id :string] [:carrier :string]]. Use event\$define \
with :event-id :$EVENT and :payload-schema set to that schema. Confirm once it is defined.")" || exit $?
    assert_file_exists   "[1] event def persisted (.brainyard/events/*.edn)" "$EVENTS_GLOB"
    assert_file_contains "[1] payload-schema stored"    "payload-schema" "$EVENTS_GLOB"
    assert_file_contains "[1] schema carries order-id"  "order-id"       "$EVENTS_GLOB"

    # [2] EMIT with a VALID payload — same session, so the def is on disk.
    emit_ans="$(by_ask "Now emit the :$EVENT event with the payload \
{:order-id \"ORD-1\" :carrier \"UPS\"} using event\$emit (:event-id :$EVENT), then report whether it fired.")" || exit $?
    assert_contains     "[2] emit turn references the event" "$EVENT" "$emit_ans"
    assert_not_contains "[2] valid payload was NOT rejected" "does not match" "$emit_ans"

    # [3] BONUS — emit an INVALID payload; the :payload-schema must reject it.
    #     Informational: it depends on the model faithfully surfacing the error.
    bad_ans="$(by_ask "Try to emit :$EVENT once more, but with an INVALID payload where \
order-id is the number 42 instead of a string (carrier \"UPS\"). Report exactly what happens.")" || exit $?
    if grep -qiE 'not match|payload-schema|reject|invalid|should be a string|:string|error' <<<"$bad_ans"
    then bad_ok=true; else bad_ok=false; fi
    note_info "$bad_ok" \
        "[3] invalid payload surfaced as a schema mismatch" \
        "[3] invalid-payload rejection not clearly surfaced"

    # Dossier is contractually expected but agents occasionally skip it → soft.
    if compgen -G "$DOSSIER_GLOB" >/dev/null 2>&1; then doss=true; else doss=false; fi
    note_info "$doss" "[*] event-agent dossier written" "[*] event-agent dossier skipped"

    harness_summary "event-agent ($PROVIDER:$MODEL)"
    # harness_summary exits.
fi

# ============================================================================
# PARENT MODE — sweep the backends, one isolated child run each.
# ============================================================================
# Provider creds (e.g. FREELLM_BASE_URL / FREELLM_API_KEY) live in the repo
# .env; a real env var wins over the child's -C .env discovery (which points at
# a throwaway project dir), so export them here before spawning children.
[[ -f .env ]] && { set -a; . ./.env; set +a; }

command -v jq  >/dev/null 2>&1 || { echo "FATAL: 'jq' is required." >&2; exit 2; }
command -v git >/dev/null 2>&1 || { echo "FATAL: 'git' is required." >&2; exit 2; }

echo "== event-agent harness =="
echo "   backends: $BACKENDS"
echo "   runner:   ${BY_BIN:-bb tui}"
echo

declare -a RESULTS=()
overall=0 ran=0
for backend in $BACKENDS; do
    echo "######################################################"
    echo "#  event-agent × $backend"
    echo "######################################################"
    BY_EVENT_BACKEND="$backend" "$0" "$@"
    rc=$?
    case "$rc" in
        0) RESULTS+=("$backend|PASS");            ran=1 ;;
        2) RESULTS+=("$backend|SKIP(cannot-run)")      ;;
        *) RESULTS+=("$backend|FAIL($rc)"); overall=1; ran=1 ;;
    esac
    echo
done

echo "=================== event-agent matrix ==================="
printf '%-22s %s\n' BACKEND STATUS
for r in "${RESULTS[@]}"; do IFS='|' read -r b s <<<"$r"; printf '%-22s %s\n' "$b" "$s"; done
echo "=========================================================="

# 0 = every backend that ran passed; 1 = a backend failed; 2 = nothing ran.
if (( ! ran )); then exit 2; fi
exit "$overall"
