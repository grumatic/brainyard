# Agent TUI — Menu (Autocomplete Popover) Design

*The bottom-anchored autocomplete menu that surfaces slash commands,
colon-prefixed tools, sub-menus, and `@`-prefix file picks for the
`by` binary.*

Status: **as-built** (May 2026) with a TODO list for follow-up work.
Audience: TUI base maintainers, agent runtime maintainers.

---

## 1. Scope

This document covers the **autocomplete popover menu** rendered by
`bases/agent-tui/src/ai/brainyard/agent_tui/autocomplete.clj` plus the
layout integration in `bases/agent-tui/.../layout.clj`. It is the menu
that pops up at the bottom of the terminal when a user types `/`, `:`,
or `@` at the input prompt — it is **not** the tmux `display-popup`
questionnaire used for permission dialogs in Mode B (that surface is
documented in [tui/renderer.md §Permissions UI](../tui/renderer.md#permissions-ui)).

The menu spans three input contexts:

| Prefix | Pool                                                       | Notes |
|--------|------------------------------------------------------------|-------|
| `/`    | `command-registry` (TUI slash commands) + tools from `!tool-defs` | Top-level + registered sub-menus. |
| `:`    | Tools only (from `!tool-defs`)                              | Colon syntax used to invoke a tool directly without a slash command. |
| `@`    | Files and directories under the current working directory  | Drill-down on directory accept. |

---

## 2. Current implementation

### 2.1 Sources of menu items

1. **Canonical slash-command list** — `format/command-registry` in
   `components/agent/.../tui/format.clj` is the single source of truth.
   Each entry is `[cmd args desc completions?]`; the optional fourth
   element is a completion hint map with a `:completions` vector of
   `[value desc]` pairs that the registry walker turns into a static
   sub-menu.
2. **Tool registry** — `agent/!tool-defs` (commands, skills, sub-agents,
   MCP tools). `tool-command-defs` emits one entry per tool id plus one
   per alias under the requested prefix (`/` or `:`).
3. **Dynamic sub-menus** — `init-dynamic-submenus!` registers:
   - `/model` — popular models filtered by `available-models` (env-key
     and AWS-credential probes; `:claude-code`, `:ollama`, `:apple-fm`
     are always shown).
   - `/config` — runtime-config keys with current/default values.
   - `/sandbox` — `agent/sandbox-menu-items` plus a built-in
     `/sandbox eval` entry.
   - `/mcp` — two-level (`server` → `start|stop|status`), handled by
     `mcp-submenu-fn`.
   - `/agent new`, `/agent switch`, `/session new` — driven by
     `available-agent-types` and `session-instances`.

### 2.2 Filtering and ranking

`filter-commands` is intentionally narrow:

- Case-insensitive **substring match against the command name only**.
  Description text is excluded by design — matching descriptions used
  to drag unrelated commands into the menu (e.g. `/he` kept `/clear`
  visible because its description contained the word "history"). The
  unit tests in `autocomplete_test.clj` regression-pin this behaviour.
- Results are bucket-sorted by `prefix-first-sort-key`: entries whose
  name (with the leading `/` or `:` stripped) **starts with** the
  query body land in bucket 0; substring-only matches go to bucket 1.
  Within each bucket, ties break alphabetically on the lowercase
  command name.

Sub-menu filtering reuses the same logic with a local `sort-by` on
`(if starts-with? 0 1)`, preserving registration order within each
bucket via the stable sort.

### 2.3 Sub-menu registry

`!submenu-registry` is a `sorted-map` keyed on the literal prefix
(`"/model"`, `"/agent new"`, …). Each value is one of:

| Key          | Shape                                | Use case |
|--------------|--------------------------------------|----------|
| `:items`     | Static `[[display desc] ...]`        | Built from `:completions` hints. |
| `:items-fn`  | Zero-arg fn returning `:items` shape | Snapshot of runtime state at menu-open time. |
| `:custom-fn` | `(fn [buffer-str] [[display desc]…])` | Escape hatch for multi-token completion (`/mcp`). |

`resolve-submenu` scans the registry **longest prefix first** so
`/agent new` wins over `/agent` when the buffer matches both.

### 2.4 Layout and rendering

Fullscreen mode (`layout/fullscreen?` is true):

- The popover reserves `ceil(rows * 0.30)` rows, clamped by
  `recalc-layout-rows!` to at most `rows - 7` to preserve chrome
  (input + separators + tab + status). The reservation is applied via
  `set-menu-height!`, which shifts the input block + scroll region up
  and re-paints the input line on the new row.
- The last reserved row is a right-aligned scroll indicator —
  `format-scroll-indicator` returns `↑ N · ↓ M more` strings; empty
  when nothing is hidden in either direction so the row stays blank
  but reserved (menu height stays stable as the user types).
- Each item is `▸ <padded-cmd>  <truncated-desc>` — descriptions are
  collapsed to a single line (`normalize-desc`) and truncated via
  `truncate-to-width`, which appends a `[+N chars]` indicator that
  itself must fit within the available column budget.
- The selected row uses ANSI reverse-video; non-selected rows use the
  `tool-name` + `muted` styles.

Inline mode prints menu lines below the cursor with no row
reservation; row count is `(menu-reserved-rows)` capped by item count.

### 2.5 Popover gate

While the menu is active, `set-popover-active! true` tells background
writers (live block tickers, viewport renders) to **defer** their
terminal paints. The data updates to `!scrollback` / `!live-blocks`
still happen — only the writes are gated, and a `dirty?` flag triggers
a full redraw on dismiss (`set-menu-height! 0` restores layout and
repaints chrome + scrollback). The `:redraw?` option on
`clear-autocomplete-menu!` keeps the gate active across the
clear → draw transition so rapid keystroke updates do not flicker.

### 2.6 Input handling

`read-line-raw!` owns the input loop. Menu-relevant keys:

| Key                        | Behaviour with menu active |
|----------------------------|----------------------------|
| `/` or `:` (first char)    | Open top-level menu against the matching pool. |
| Any printable char         | Re-filter; preserve selection where possible. |
| `Backspace`                | Re-filter or dismiss when buffer empties. |
| `↑` / `↓`                  | Cycle selection with wrap; scroll window follows. |
| `Tab`                      | Accept selection (drill-down when applicable). |
| `Enter`                    | Accept selection — **does not** submit the line. |
| `Esc`                      | Dismiss menu; buffer unchanged. |
| `PgUp` / `PgDn`            | Dismiss menu, scroll the scrollback viewport. |
| `Shift-←` / `Shift-→`      | Dismiss menu, navigate input history. |
| `Ctrl-N/P/T/W/O`           | Dismiss menu, run session-management action. |

The selection-preservation logic in `show-menu!` is nontrivial: if the
previously selected item still appears in the new matches it stays
selected, but if it was an auto-pick (index 0) and the new query has a
fresh bucket-0 (prefix) match, the selection snaps back to the new top
so progressively typing a longer prefix doesn't strand the user on a
substring-only carryover. Single-match results auto-select index 0.

### 2.7 `@`-prefix file/directory picker

`extract-at-token` scans backwards from the cursor for an `@` token
preceded by whitespace or BOL with no embedded whitespace.
`list-path-matches` enumerates entries relative to CWD:

- Hidden entries (starting with `.`) are filtered out.
- Directories sort before files; alphabetical within each group.
- Capped at 50 entries.
- The description column shows `dir` or a human-readable byte size.

Accepting a directory re-opens the menu rooted at that directory
(drill-down). Accepting a file replaces only the `@`-token span in the
buffer, not the entire input.

### 2.8 Help integration

`format-help` (the `/help` command body) walks `command-registry` to
emit the same canonical list — adding a new command in one place
surfaces it both in `/help` and in the autocomplete menu, eliminating
drift.

---

## 3. Files and responsibilities

| File | Role |
|------|------|
| `bases/agent-tui/.../autocomplete.clj` | Menu logic, sub-menu registry, `read-line-raw!`. |
| `bases/agent-tui/.../layout.clj`       | `set-menu-height!`, `set-popover-active!`, layout maths. |
| `bases/agent-tui/.../commands.clj`     | Slash-command dispatch + `available-agent-types`, `session-instances`. |
| `bases/agent-tui/.../terminal.clj`     | Raw-mode key reader (`read-key!`), input redraw. |
| `bases/agent-tui/.../input.clj`        | Bracketed-paste sentinel (`!pasting?`). |
| `components/agent/.../tui/format.clj`  | `command-registry` (canonical roster), `format-help`. |
| `components/agent/.../common/sandbox_meta.clj` | `sandbox-menu-items` provider. |
| `components/agent/.../interface.clj`   | `!tool-defs`, `list-configured-servers`, `list-active-clients`, `config-schema`. |
| `bases/agent-tui/test/.../autocomplete_test.clj` | Unit coverage for sort + filter + scroll indicator. |

---

## 4. Known limitations and TODOs

The list below is grounded in the current code; each item names the
function or registry call that would need to change.

### 4.1 Matching and ranking

- **TODO — fuzzy / subsequence matching.** `filter-commands` is a flat
  substring filter. A subsequence ranker (FZF-style `mdl → /model`)
  would help users who type partial words. Likely shape: replace the
  bucket-0/1 split with a graded score (`[bucket, -score, name]`) and
  expose a switch (`/config menu.match :substring | :fuzzy`).
- **TODO — highlight matched characters.** With either substring or
  fuzzy matching, underlining the matched positions inside the
  command name would make ranking legible. Today the menu only signals
  the selected row, not *why* an entry matched.
- **TODO — opt-in description search.** Some users want to grep the
  registry by what a command does. Reserve a leading `?` (e.g.
  `/?history`) for description matching so the default name-only
  behaviour is preserved.

### 4.2 Inventory shape

- **TODO — group aliases under the canonical entry.** `tool-command-defs`
  emits one full row per alias, which inflates the menu for any tool
  with multiple shorthands. Consider rendering the canonical name with
  `(aliases: a, b, c)` in the description column.
- **TODO — recently-used boost.** Track the last N accepted commands per
  TUI session in `!sessions` and bias `prefix-first-sort-key` to put
  them ahead of cold matches inside their bucket.
- **TODO — argument completion from runtime data.** Commands like
  `/task detail <id>`, `/task cancel <id>`, `/queue cancel <uuid>`,
  `/agent close <id>`, `/session show <id>` could surface live
  IDs from the task manager / queue / session registry. Today these
  are unstructured free text.
- **TODO — value enums for `/config`.** `config-schema` knows the type
  but the sub-menu only echoes the current value. When the type is
  `:enum` or `:boolean`, drill into a value picker.

### 4.3 Sub-menu registry

- **TODO — make `init-submenus!` event-driven.** It runs once at TUI
  startup. Newly configured MCP servers, late-bound agents, or models
  pulled in after launch are not visible until restart. Re-running on
  `:agent.tool-use/post`, `:mcp/server-added`, or a registry-watch
  would fix this without per-keystroke recompute.
- **TODO — generalise `/mcp` two-level handling.** `mcp-submenu-fn`
  hand-rolls substring filtering and reconstructs the full prefix
  string for matching. The same shape could power any
  `<verb> <noun> <action>` command, so extract a `multi-token-spec`
  helper that the registry can use without per-command custom fns.

### 4.4 `@`-prefix picker

- **TODO — support `~`, `$VAR`, and absolute paths.**
  `list-path-matches` only walks relative paths today.
- **TODO — optional inclusion of dotfiles.** Hidden entries are
  unconditionally filtered. A toggle (`@.`) or trailing flag would
  let users complete `.brainyard/` etc.
- **TODO — whitespace-safe paths.** Files with spaces are inserted raw;
  the rest of the buffer parser then splits on whitespace. Either
  shell-quote on accept or keep the `@`-token semantics as a single
  span that downstream tools recognise.
- **TODO — pluggable token sources.** The `@` token is hardcoded to
  filesystem entries. Expand the contract so tools/skills can register
  additional providers (MRU files, project symbols, recent URLs)
  surfaced under different sigils (`#`, `~`, …).

### 4.5 Rendering and ergonomics

- **TODO — adaptive height.** The 30 % reservation is friendly on
  big terminals but cramped on short windows (12 rows → 2 visible
  items + indicator). An `auto-up-to-N` mode (e.g. `min(items, ceil(rows * 0.5))`)
  would help; expose the cap via runtime-config.
- **TODO — quick-pick by digit/letter.** The Mode-B questionnaire
  popup supports `1`–`9` and letter shortcuts. The fullscreen
  autocomplete popover does not — it cannot, while `read-line-raw!`
  treats digits as buffer input. A modal "menu-focused" mode (e.g.
  `Ctrl-Space` to enter menu-only navigation) would unlock this.
- **TODO — within-menu paging.** `PgUp` / `PgDn` currently dismiss the
  menu and scroll the scrollback. Re-route them to half-page menu
  paging while the menu is active, and bind `Alt-PgUp` / `Alt-PgDn`
  (or keep arrows) for the existing scrollback control.
- **TODO — keyboard hint footer.** Reserve one of the popover rows for
  `Tab=accept  Esc=dismiss  ↑↓=nav  Enter=accept` — currently users
  must discover the bindings from `/help`.
- **TODO — vertical divider glyph.** The space between command and
  description is plain whitespace. A faint glyph (`│` or `·`) would
  make wide-terminal scanning easier.

### 4.6 Lifecycle and dismiss semantics

- **TODO — sticky dismiss.** `Esc` dismisses but the next keystroke
  re-opens the menu. There is no "I cancelled, don't reopen" mode
  short of clearing the prefix. Add a one-shot flag that arms on
  `Esc` and clears on whitespace or buffer clear.
- **TODO — explicit submenu cursor contract.** Backspacing past the
  space character that separates command from arg silently restores
  the parent menu. The cursor management is correct in practice but
  underdocumented; codify it (and test it).

### 4.7 Provider integration

- **TODO — `/model` paging affordance.** `available-models` is capped
  at 50. Long lists rely on the popover's ↑/↓ scrolling — fine
  functionally, but there is no grouping by provider, no "show all"
  toggle, and no obvious affordance for users with many configured
  backends.
- **TODO — provider-side sandbox filter.** `agent/sandbox-menu-items`
  is rendered verbatim. A current-agent-aware filter would hide
  sandbox functions that the active agent has no binding for.

### 4.8 Test coverage

`autocomplete_test.clj` covers `prefix-first-sort-key`,
`filter-commands`, and `format-scroll-indicator`. Untested today:

- **TODO** — `extract-at-token` (boundary conditions on
  whitespace-before-`@`, multi-line buffers, cursor at edge).
- **TODO** — `resolve-submenu` longest-prefix-wins behaviour and the
  `:custom-fn` dispatch.
- **TODO** — `truncate-to-width` indicator fit (currently relies on
  manual verification).
- **TODO** — layout reservation interactions (`set-menu-height!` on
  very small terminals; popover gate dirty-flag flushing).

### 4.9 Internationalisation and accessibility

- **TODO — i18n.** All strings (`"current"`, `"connected"`,
  `"disconnected"`, `"dir"`, indicator suffix `"more"`) are hardcoded
  English.
- **TODO — accessibility hint hooks.** Selection changes only paint
  reverse-video. For screen readers / external observers, expose a
  hook (`:tui.menu/selection-changed`) carrying the focused
  command and description so external surfaces can mirror the state.

---

## 5. Cross-references

- [tui/renderer.md](../tui/renderer.md) — the broader file map for
  `bases/agent-tui`, slash-command roster, permissions UI, multi-session.
- [tui/architecture.md](../tui/architecture.md) — process topology,
  Mode A / B / C, persistence layout.
- [core/tool.md](../core/tool.md) — `!tool-defs`,
  `deftool` / `defcommand` / `defskill` / `defagent`, MCP plumbing.
- [core/agent.md](../core/agent.md) — hooks catalog the renderer
  subscribes to.
