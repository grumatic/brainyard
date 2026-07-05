#!/usr/bin/env bash
# Timeline conversation-history harness — the END-TO-END counterpart to the
# deterministic property suite in
#   components/agent/test/ai/brainyard/agent/common/timeline_history_test.clj
#
# Timeline history (conversation-style "timeline", commit c076705) only lives
# inside ONE long-running process: completed own-turn Q/A pairs older than
# :conversation-keep-verbatim collapse to [Turn N] refs into the append-only
# :previous-turns chain. A one-shot `by ask` starts fresh each time and can't
# exercise it — so this harness holds a live session with `by run` (in tmux)
# and injects each turn over the ask side-channel with `by ask --attach`, so
# conversation history actually accumulates.
#
# It then verifies BOTH surfaces:
#   • STRUCTURAL (over the live process's nREPL): the rendered :conversation
#     carries a turn-ref for the oldest turn, that ref points at a real
#     :previous-turns entry, and the collapsed turn's text is NOT double-rendered
#     in the window (the G9 de-dup) while it IS present in the chain.
#   • BEHAVIORAL: a codeword seeded in turn 1 is still recalled several turns
#     later — after it has left the verbatim window and become a ref — proving
#     the chain preserves content the window dropped.
#
# Usage:   scripts/test-conversation-timeline.sh [--keep]
# Env:     PROVIDER (default claude-code)  MODEL (default haiku)
#          BY_BIN unsupported here — the harness needs `bb tui run` + nREPL from
#          source (a native binary has no nREPL); it always runs `bb tui`.
# Exit:    0 all pass · 1 assertion failed · 2 cannot run
set -uo pipefail
cd "$(dirname "$0")/.."
source scripts/lib-agent-harness.sh

harness_parse_args "$@"
# BY_BIN would bypass the nREPL/run path this harness depends on.
BY_BIN=""
harness_init "coact-agent"

TMUX_SESSION="convtl-$$"
CODEWORD="TOPAZ-7"
STARTUP_TIMEOUT=150     # bb tui compiles from source on first launch
# `run -s <id>` pins the session id (and errors on collision), so the ask-socket
# path is deterministic and needs no post-boot discovery. SESSION_ID comes from
# harness_init.
SOCK="$PROJ/.brainyard/sessions/$SESSION_ID/ask.sock"
NREPL_PORT=""

# ---------- combined cleanup (tmux run process + harness isolation) ----------
convtl_cleanup() {
    tmux kill-session -t "$TMUX_SESSION" 2>/dev/null || true
    harness_cleanup
}
trap convtl_cleanup EXIT INT TERM

# ---------- force the timeline to collapse after just a couple of turns ------
# keep-verbatim 1 ⇒ the moment a 2nd completed turn exists, turn 1 collapses to
# a [Turn 1] ref. Written BEFORE `run` so the session reads it at startup.
mkdir -p "$PROJ/.brainyard"
# NB: config.edn keys live under the [:agent :config] subtree (they are
# intersected with config-keys there); a flat top-level map is ignored.
cat > "$PROJ/.brainyard/config.edn" <<EOF
{:agent {:config {:conversation-style "timeline"
                  :conversation-keep-verbatim 1
                  :conversation-limit 20}}}
EOF

harness_banner "conversation-timeline harness"
echo "   tmux=$TMUX_SESSION  codeword=$CODEWORD"
echo

# ---------- boot a persistent `bb tui run` session in tmux -------------------
# -s pins the session id (deterministic ask-socket path); BY_NREPL_ENABLED
# exposes the in-process nREPL for structural inspection.
echo "[boot] launching persistent session '$SESSION_ID' (compiles from source; up to ${STARTUP_TIMEOUT}s)…"
snapshot_ports() { clj-nrepl-eval --discover-ports 2>/dev/null | grep -oE 'localhost:[0-9]+' | grep -oE '[0-9]+' | sort -u; }
PORTS_BEFORE="$(snapshot_ports)"

tmux new-session -d -s "$TMUX_SESSION" -x 200 -y 50 \
    "BY_NREPL_ENABLED=true BY_PROJECT_DIR='$PROJ' bb tui run -s '$SESSION_ID' \
       -u '$USER_ID' -p '$PROVIDER' -m '$MODEL' -a coact-agent -C '$PROJ'" \
    || { echo "FATAL: tmux new-session failed"; exit 2; }

# ---------- wait for the pinned session to become attachable -----------------
deadline=$(( $(date +%s) + STARTUP_TIMEOUT ))
while (( $(date +%s) < deadline )); do
    [[ -S "$SOCK" ]] && break
    # honour the ask.sock relocation fallback (deep paths overflow the ~104B
    # AF_UNIX cap → recorded in meta.edn :ask-socket-path)
    alt="$(grep -oE ':ask-socket-path "[^"]+"' "$PROJ/.brainyard/sessions/$SESSION_ID/meta.edn" 2>/dev/null \
             | grep -oE '/[^"]+' | head -1)"
    [[ -n "$alt" && -S "$alt" ]] && { SOCK="$alt"; break; }
    sleep 3
done
if [[ ! -S "$SOCK" ]]; then
    echo "FATAL: session '$SESSION_ID' never became attachable (sock='$SOCK')"
    tmux capture-pane -t "$TMUX_SESSION" -p 2>/dev/null | tail -15
    exit 2
fi
echo "[boot] session '$SESSION_ID' attachable (socket up)."

# ---------- locate this process's nREPL port (the newly-appeared one) --------
for _ in $(seq 1 20); do
    NREPL_PORT="$(comm -13 <(printf '%s\n' "$PORTS_BEFORE") <(snapshot_ports) | head -1)"
    [[ -n "$NREPL_PORT" ]] && break
    sleep 2
done
nrepl_eval() { clj-nrepl-eval -p "$NREPL_PORT" --timeout 20000 "$1" 2>/dev/null | sed -n 's/^=> //p' | tail -1; }
if [[ -n "$NREPL_PORT" ]] && [[ "$(nrepl_eval '(some? (ai.brainyard.agent-tui.session/get-active-agent))')" == "true" ]]; then
    echo "[boot] nREPL inspection channel up on port $NREPL_PORT."
    NREPL_OK=1
    # Guard: the timeline only collapses if our config.edn actually took effect.
    # (config.edn keys must sit under [:agent :config]; a flat map is ignored.)
    kv="$(nrepl_eval '(ai.brainyard.agent.core.config/get-config (ai.brainyard.agent-tui.session/get-active-agent) :conversation-keep-verbatim)')"
    echo "[boot] effective conversation-keep-verbatim=$kv (want 1)"
    if [[ "$kv" != "1" ]]; then
        echo "FATAL: config.edn override not applied (keep-verbatim=$kv) — the"
        echo "       timeline won't collapse; structural checks would be meaningless."
        exit 2
    fi
else
    echo "[boot] nREPL channel unavailable — structural checks will be SKIPPED."
    NREPL_OK=0
fi

# ---------- attach-driven turn runner ----------------------------------------
attach_ask() {   # <question> → prints .answer; exit 2 on runner/limit failure
    local q="$1" raw json answer
    raw="$(bb tui ask --attach "$SESSION_ID" --json -C "$PROJ" --timeout 150 "$q" 2>/dev/null)"
    json="$(grep -E '^\{.*\}$' <<<"$raw" | tail -1)"
    if [[ -z "$json" ]]; then
        echo "FATAL: no JSON from attach for: $q" >&2; echo "$raw" | tail -6 >&2; exit 2
    fi
    if [[ "$(jq -r '.success' <<<"$json")" != "true" ]]; then
        echo "FATAL: attach reported failure: $(jq -r '.error // "unknown"' <<<"$json")" >&2; exit 2
    fi
    answer="$(jq -r '.answer // ""' <<<"$json")"
    if grep -qiE 'hit your session limit|session limit · resets|rate.?limit(ed)?|quota exceeded|429 too many' <<<"$answer"; then
        echo "FATAL: provider limit reached (cannot run): $(head -1 <<<"$answer")" >&2; exit 2
    fi
    printf '%s' "$answer"
}

# nREPL reader: the live agent's rendered :conversation / :previous-turns.
# :conversation is per-turn st-memory; :previous-turns is the cross-turn chain.
read_state() {   # <edn-expr-over-`mem`(per-turn) and `init`(cross-turn)> → value
    nrepl_eval "(let [ag (ai.brainyard.agent-tui.session/get-active-agent)
                      mem (some-> ag :!state deref :behavior-tree :context :st-memory deref)
                      init (some-> ag ai.brainyard.agent.core.protocol/get-st-memory-init deref)]
                  $1)"
}

# ---------- drive the conversation -------------------------------------------
echo "[turn 1] seed a codeword"
a1="$(attach_ask "Remember this codeword for later: ${CODEWORD}. Acknowledge in one short sentence.")"
assert_contains "turn 1 acknowledged the codeword" "$CODEWORD" "$a1"

echo "[turn 2] unrelated filler (pushes turn 1 past keep-verbatim)"
attach_ask 'What is 11 + 7? Reply with just the number.' >/dev/null

echo "[turn 3] unrelated filler"
attach_ask 'What is 20 - 5? Reply with just the number.' >/dev/null

# ---------- STRUCTURAL assertions over the live timeline ---------------------
echo "[structural] inspect live :conversation / :previous-turns"
if (( NREPL_OK )); then
    chain_len="$(read_state '(count (:previous-turns init))')"
    ref_count="$(read_state '(count (filter #(= "turn-ref" (:role %)) (:conversation mem)))')"
    max_ref="$(read_state '(reduce max 0 (map :turn (filter #(= "turn-ref" (:role %)) (:conversation mem))))')"
    # codeword present in the chain (turn 1) but collapsed OUT of the window
    word_in_conv="$(read_state "(boolean (some #(clojure.string/includes? (str (:content %)) \"$CODEWORD\") (:conversation mem)))")"
    word_in_chain="$(read_state "(boolean (some (fn [t] (some #(clojure.string/includes? (str %) \"$CODEWORD\") [(:question t) (:answer t)])) (:previous-turns init)))")"

    echo "   chain_len=$chain_len ref_count=$ref_count max_ref=$max_ref word_in_conv=$word_in_conv word_in_chain=$word_in_chain"

    # chain has ≥2 completed turns (turn 3's own Q/A lands after its prep)
    if [[ "${chain_len:-0}" =~ ^[0-9]+$ ]] && (( chain_len >= 2 )); then
        echo "  ✓ previous-turns chain grew across turns (len=$chain_len)"; PASS=$((PASS+1))
    else echo "  ✗ previous-turns chain did not grow (len=$chain_len)"; FAIL=$((FAIL+1)); fi

    # the window collapsed the oldest turn(s) to a ref
    if [[ "${ref_count:-0}" =~ ^[0-9]+$ ]] && (( ref_count >= 1 )); then
        echo "  ✓ conversation window carries a turn-ref (count=$ref_count)"; PASS=$((PASS+1))
    else echo "  ✗ conversation window has no turn-ref (timeline did not collapse)"; FAIL=$((FAIL+1)); fi

    # the ref points at a REAL chain entry (1 ≤ max_ref ≤ chain_len)
    if [[ "${max_ref:-0}" =~ ^[0-9]+$ ]] && (( max_ref >= 1 && max_ref <= ${chain_len:-0} )); then
        echo "  ✓ turn-ref resolves to a real chain entry (turn $max_ref ≤ $chain_len)"; PASS=$((PASS+1))
    else echo "  ✗ turn-ref out of range (turn $max_ref, chain $chain_len)"; FAIL=$((FAIL+1)); fi

    # G9 de-dup: codeword lives in the chain, NOT re-rendered in the window
    assert_contains  "codeword retained in previous-turns chain" "true"  "$word_in_chain"
    assert_not_contains "codeword NOT double-rendered in window" "true"  "$word_in_conv"
else
    note_skip "structural timeline checks" "no nREPL channel on the run process"
fi

# ---------- BEHAVIORAL assertion: recall after collapse ----------------------
echo "[behavioral] recall the codeword after it left the verbatim window"
recall="$(attach_ask 'What codeword did I ask you to remember earlier? Reply with just the codeword, nothing else.')"
assert_contains "codeword recalled from collapsed history" "$CODEWORD" "$recall"

harness_summary "conversation-timeline harness"
