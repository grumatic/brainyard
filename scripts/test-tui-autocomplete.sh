#!/usr/bin/env bash
# Real-terminal test harness for the TUI autocomplete menu.
#
# Boots `bb tui` (fullscreen mode) inside a detached tmux session, drives
# the input loop with `tmux send-keys`, captures the pane plaintext with
# `tmux capture-pane`, and asserts on what the menu / submenu shows. No
# Enter is ever sent for slash-commands themselves — only Tab to accept
# (or Escape / Backspace to dismiss) — so cases cannot accidentally
# trigger /quit, /clear, etc.
#
# Covers:
#   - top-level slash filter (/, /he, …) and prefix-first sort
#   - colon-prefix tool menu (no builtin /-commands)
#   - static :completions submenus (/agent /verbose /effort /init /task /session)
#   - dynamic submenus (/model /config /agent new)
#   - custom /mcp filter (top-level row visible)
#   - leaf-command selection dismisses the menu so the user can type
#   - parent-command selection drills into the submenu and keeps the menu open
#   - Escape and Backspace-to-empty dismiss the menu
#
# Usage:
#   scripts/test-tui-autocomplete.sh                  # run all cases
#   scripts/test-tui-autocomplete.sh case_agent_submenu   # run one case
#
# Env overrides:
#   PROVIDER   default: claude-code
#   MODEL      default: haiku
#   AGENT      default: coact-agent
#   PANE_W     default: 200
#   PANE_H     default: 60
#
# Exit codes:
#   0 — all assertions passed
#   1 — at least one assertion failed
#   2 — startup failure (TUI never reached idle prompt)

set -uo pipefail

PROVIDER="${PROVIDER:-claude-code}"
MODEL="${MODEL:-haiku}"
AGENT="${AGENT:-coact-agent}"
PANE_W="${PANE_W:-200}"
PANE_H="${PANE_H:-60}"

SESSION="by-menu-$$"
STARTUP_TIMEOUT=45
# How long to wait after each send before capturing. Fullscreen menu
# repaint reserves rows and shifts chrome, which takes a beat.
SETTLE=0.4

PASS=0
FAIL=0

cleanup() { tmux kill-session -t "$SESSION" 2>/dev/null || true; }
trap cleanup EXIT INT TERM

capture() { tmux capture-pane -t "$SESSION" -p 2>/dev/null; }

send_text() { tmux send-keys -t "$SESSION" -l "$1"; sleep "$SETTLE"; }
send_key()  { tmux send-keys -t "$SESSION" "$1";    sleep "$SETTLE"; }

# Drop any active menu and clear the input line. Escape dismisses an
# active menu (no-op otherwise); Ctrl-A / Ctrl-K clears the buffer.
reset_input() {
    send_key 'Escape'
    send_key 'C-a'
    send_key 'C-k'
    sleep 0.15
}

assert_pane() {
    local name="$1" pattern="$2" pane
    pane="$(capture)"
    if printf '%s' "$pane" | grep -qE "$pattern"; then
        echo "    ok    $name"
        PASS=$(( PASS + 1 ))
    else
        echo "    FAIL  $name  (pattern: $pattern)"
        printf '%s' "$pane" | tail -25 | sed 's/^/          /'
        echo  "          ---"
        FAIL=$(( FAIL + 1 ))
    fi
}

refute_pane() {
    local name="$1" pattern="$2" pane
    pane="$(capture)"
    if printf '%s' "$pane" | grep -qE "$pattern"; then
        echo "    FAIL  $name  (unexpected match: $pattern)"
        FAIL=$(( FAIL + 1 ))
    else
        echo "    ok    $name"
        PASS=$(( PASS + 1 ))
    fi
}

wait_for_pattern() {
    local pattern="$1" timeout="${2:-10}"
    local deadline=$(( $(date +%s) + timeout ))
    while (( $(date +%s) < deadline )); do
        capture | grep -qE "$pattern" && return 0
        sleep 0.5
    done
    return 1
}

# ---------- boot ----------
echo "== TUI autocomplete-menu harness =="
echo "   provider=$PROVIDER model=$MODEL agent=$AGENT  pane=${PANE_W}x${PANE_H}"

tmux new-session -d -s "$SESSION" -x "$PANE_W" -y "$PANE_H" \
    "bb tui -p '$PROVIDER' -m '$MODEL' -a '$AGENT'" \
    || { echo "FATAL: tmux new-session failed"; exit 2; }

# wait for either the session picker or the idle prompt
deadline=$(( $(date +%s) + STARTUP_TIMEOUT ))
while (( $(date +%s) < deadline )); do
    capture | grep -qE '(\(N\)ew|Alt\+Enter:)' && break
    sleep 1
done

if capture | grep -q '(N)ew'; then
    tmux send-keys -t "$SESSION" "N" Enter
fi

if ! wait_for_pattern 'Alt\+Enter: newline' "$STARTUP_TIMEOUT"; then
    echo "FATAL: TUI never reached idle prompt"
    capture | tail -25
    exit 2
fi

echo "   booted; running cases..."
echo

# ============================================================================
# cases
# ============================================================================

case_top_slash_full_menu() {
    echo "[1] /  shows builtin command rows (paginated, alphabetical)"
    reset_input
    send_text '/'
    # The menu shows ~15-20 rows from a registry of 200+ entries (TUI
    # builtins + tools + MCP), sorted alphabetically. Assert on builtins
    # that fall inside the visible top window. The scroll indicator
    # confirms there's more below the fold.
    assert_pane '/agent row'           '/agent +.*Manage agents'
    assert_pane '/allow-path row'      '/allow-path.*Whitelist'
    assert_pane '/capture row'         '/capture.*scrollback'
    assert_pane '/clear row'           '/clear.*Restart'
    assert_pane '/compact row'         '/compact.*Compact context'
    assert_pane 'scroll indicator'     '↓ [0-9]+ more'
}

case_top_slash_prefix_filter() {
    echo "[2] /he  filters to prefix matches (name-only, no desc match)"
    reset_input
    send_text '/he'
    assert_pane '/help shown'   '/help'
    refute_pane '/quit hidden'  '/quit.*Exit TUI'
    # filter-commands matches the command NAME only — /clear's description
    # contains "history" but its name does not contain "he", so it must be
    # filtered out (autocomplete.clj filter-commands).
    refute_pane '/clear hidden' '/clear.*Restart'
}

case_colon_prefix_tool_menu() {
    echo "[3] :  shows tool commands only (no builtin /-commands)"
    reset_input
    send_text ':'
    refute_pane 'builtin /help not in : menu' '/help.*Show this help'
    # Tool registry should expose some colon-prefixed entries.
    assert_pane 'at least one :tool row' ':[a-z][a-z0-9_-]+ +\S'
}

case_agent_submenu() {
    echo "[4] /agent <space>  -> static submenu (new/switch/close/trace)"
    reset_input
    send_text '/agent '
    assert_pane '/agent new'     '/agent new.*Create a new agent'
    assert_pane '/agent switch'  '/agent switch.*Switch between'
    assert_pane '/agent close'   '/agent close.*Close current'
    assert_pane '/agent trace'   '/agent trace.*BT trace'
}

case_verbose_submenu() {
    echo "[5] /verbose <space>  -> quiet/normal/verbose"
    reset_input
    send_text '/verbose '
    assert_pane 'quiet'    '/verbose quiet'
    assert_pane 'normal'   '/verbose normal'
    assert_pane 'verbose'  '/verbose verbose'
}

case_effort_submenu() {
    echo "[6] /effort <space>  -> low/medium/high"
    reset_input
    send_text '/effort '
    assert_pane 'low'     '/effort low'
    assert_pane 'medium'  '/effort medium'
    assert_pane 'high'    '/effort high'
}

case_session_submenu() {
    echo "[7] /session <space>  -> new/close/rename/list"
    reset_input
    send_text '/session '
    assert_pane 'new'     '/session new'
    assert_pane 'close'   '/session close'
    assert_pane 'rename'  '/session rename'
    assert_pane 'list'    '/session list'
}

case_task_submenu() {
    echo "[8] /task <space>  -> list/detail/cancel/del/log/run"
    reset_input
    send_text '/task '
    assert_pane 'list'    '/task list'
    assert_pane 'detail'  '/task detail'
    assert_pane 'cancel'  '/task cancel'
    assert_pane 'run'     '/task run'
}

case_init_submenu() {
    echo "[9] /init <space>  -> show/list-snapshots/reseed/revert/help"
    reset_input
    send_text '/init '
    assert_pane 'show'            '/init show'
    assert_pane 'list-snapshots'  '/init list-snapshots'
    assert_pane 'reseed'          '/init reseed'
    assert_pane 'revert'          '/init revert'
    assert_pane 'help'            '/init help'
}

case_model_dynamic_submenu() {
    echo "[10] /model <space>  -> dynamic model list"
    reset_input
    send_text '/model '
    # claude-code:haiku is the boot model, so haiku must appear.
    assert_pane 'haiku model row'  '/model .*haiku'
}

case_config_dynamic_submenu() {
    echo "[11] /config <space>  -> dynamic config keys"
    reset_input
    send_text '/config '
    # config-schema has multiple keys; we just need to see one row.
    assert_pane 'some config key row' '/config [a-z]'
}

case_agent_new_dynamic_submenu() {
    echo "[12] /agent new <space>  -> dynamic agent types"
    reset_input
    send_text '/agent new '
    assert_pane 'coact-agent row'  '/agent new coact-agent'
    assert_pane 'react-agent row'  '/agent new react-agent'
}

case_mcp_top_filter() {
    echo "[13] /mc  shows /mcp row at top-level"
    reset_input
    send_text '/mc'
    assert_pane '/mcp row'  '/mcp.*Manage MCP'
}

case_leaf_dismisses_menu() {
    echo "[14] Tab on /help (leaf, no submenu) dismisses the menu"
    reset_input
    send_text '/he'
    assert_pane 'menu up'  '/help.*Show this help'
    send_key 'Tab'
    # After accept: buffer holds '/help', menu rows gone. The description
    # text 'Show this help' only ever appears in the menu, so its absence
    # is a reliable signal that the menu was dismissed.
    refute_pane 'description row gone'  'Show this help'
}

case_leaf_dismiss_then_typeable() {
    echo "[15] after leaf-dismiss, typing args does NOT re-open a menu"
    reset_input
    send_text '/he'
    send_key 'Tab'     # buffer -> '/help'
    send_text ' xyz'   # '/help' has no submenu -> dismiss-menu! path
    refute_pane 'no submenu rendered for unknown subcommand'  'Show this help'
}

case_drilldown_keeps_menu() {
    echo "[16] Tab on /verbose (parent) drills into the submenu"
    reset_input
    send_text '/verb'
    assert_pane '/verbose in menu'  '/verbose.*Show/set verbosity'
    send_key 'Tab'
    # accept-sel! appends a space and re-triggers the submenu.
    assert_pane 'quiet row'    '/verbose quiet'
    assert_pane 'normal row'   '/verbose normal'
    assert_pane 'verbose row'  '/verbose verbose'
}

case_menu_sits_below_input_above_status() {
    echo "[20] menu rows render between input line and bottom status bar"
    # Capture line numbers of:
    #   - the input prompt line: `> /he`
    #   - the first menu item:    `▸ /help` (selected row)
    #   - the status bar marker:  `idle │`
    # New layout requires:  input < menu < status.
    reset_input
    send_text '/he'
    local pane input_ln menu_ln status_ln
    pane="$(capture)"
    input_ln="$( printf '%s' "$pane" | grep -n '^> /he' | head -1 | cut -d: -f1)"
    menu_ln="$(  printf '%s' "$pane" | grep -n '▸ */help'        | head -1 | cut -d: -f1)"
    status_ln="$(printf '%s' "$pane" | grep -n 'idle │'          | head -1 | cut -d: -f1)"
    if [[ -z "$input_ln" || -z "$menu_ln" || -z "$status_ln" ]]; then
        echo "    FAIL  couldn't locate input/menu/status lines  (i=$input_ln m=$menu_ln s=$status_ln)"
        printf '%s' "$pane" | tail -25 | sed 's/^/          /'
        FAIL=$(( FAIL + 1 ))
        return
    fi
    if (( input_ln < menu_ln && menu_ln < status_ln )); then
        echo "    ok    rows: input=$input_ln  menu=$menu_ln  status=$status_ln"
        PASS=$(( PASS + 1 ))
    else
        echo "    FAIL  expected input < menu < status  (got input=$input_ln menu=$menu_ln status=$status_ln)"
        printf '%s' "$pane" | tail -25 | sed 's/^/          /'
        FAIL=$(( FAIL + 1 ))
    fi
}

case_drilldown_with_substring_conflict() {
    echo "[19] Tab on /agent picks /agent — not /acp-agent — and drills in"
    # Regression test for the bucket-degradation fix in show-menu!:
    # at bare "/", /acp-agent is the alphabetically-first row and is
    # auto-selected. Typing "ag" puts /agent into bucket-0 (name-prefix)
    # but /acp-agent drops to bucket-1 (name-substring only). Without
    # the fix, the preserved selection stays on /acp-agent and Tab
    # accepts the wrong command. With the fix, the selection snaps to
    # the new bucket-0 top (/agent), and Tab drills into its submenu.
    reset_input
    send_text '/'
    assert_pane 'bare-/: /acp-agent is the auto-pick'  '▸ */acp-agent'
    send_text 'ag'
    send_key 'Tab'
    assert_pane '/agent new in submenu'     '/agent new.*Create a new agent'
    assert_pane '/agent switch in submenu'  '/agent switch'
    assert_pane '/agent close in submenu'   '/agent close'
    refute_pane 'buffer is NOT /acp-agent'  '> /acp-agent'
}

case_escape_dismisses() {
    echo "[17] Escape dismisses the menu"
    reset_input
    send_text '/he'
    assert_pane 'menu up'  '/help.*Show this help'
    send_key 'Escape'
    refute_pane 'menu gone after Escape'  'Show this help'
}

case_backspace_to_empty_dismisses() {
    echo "[18] Backspace until buffer is empty dismisses the menu"
    reset_input
    send_text '/h'
    assert_pane 'menu up'  '/help'
    send_key 'BSpace'      # '/h' -> '/'
    send_key 'BSpace'      # '/'  -> ''
    refute_pane 'menu gone' '/help.*Show this help'
}

CASES=(
    case_top_slash_full_menu
    case_top_slash_prefix_filter
    case_colon_prefix_tool_menu
    case_agent_submenu
    case_verbose_submenu
    case_effort_submenu
    case_session_submenu
    case_task_submenu
    case_init_submenu
    case_model_dynamic_submenu
    case_config_dynamic_submenu
    case_agent_new_dynamic_submenu
    case_mcp_top_filter
    case_leaf_dismisses_menu
    case_leaf_dismiss_then_typeable
    case_drilldown_keeps_menu
    case_escape_dismisses
    case_backspace_to_empty_dismisses
    case_menu_sits_below_input_above_status
    case_drilldown_with_substring_conflict
)

if (( $# > 0 )); then
    # Run only the cases named on the command line.
    for name in "$@"; do
        if declare -F "$name" >/dev/null; then
            "$name"
        else
            echo "unknown case: $name"
            FAIL=$(( FAIL + 1 ))
        fi
    done
else
    for c in "${CASES[@]}"; do
        "$c"
    done
fi

echo
echo "== pass=$PASS fail=$FAIL =="
exit $(( FAIL > 0 ? 1 : 0 ))
