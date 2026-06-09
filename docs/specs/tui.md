# Spec: Terminal UI

*Area code `TUI`. Covers the `by` binary's user-facing surface: run
modes and entry, the hook-driven render/session loop, slash commands,
permissions/HITL and popups, session persistence, the tmux substrate,
display-blocks, and environment detection. Files live in
`bases/agent-tui/*`, the real `-main` in
`projects/agent-tui-app/.../main.clj`, and the substrate in
`components/agent-tui-tmux`, `agent-tui-persist`, `display-block`,
`env-detect`.*

Status legend and contract-ID conventions: see [README](README.md).

> **Where the heart is.** The rendering heart is
> `bases/agent-tui/.../session.clj` (2800+ lines of lifecycle handlers
> and live-block renderers), **not** `render.clj` (a thin formatter) or
> `layout.clj` (terminal geometry). The real `-main` is in the
> `agent-tui-app` project's `main.clj` (cli-matic), not the base's
> `core.clj` (which is the REPL/`run!` API).

---

## 1. Run modes & entry

| ID | Contract | Status | Source |
|---|---|---|---|
| CR-TUI-01 | `-main` MUST route via cli-matic subcommands `run`/`ask`/`agents`/`models`/`config`/`sessions`; no subcommand MUST default to `run`, and a bare agent-id MUST run that agent. | Implemented | `projects/agent-tui-app/.../main.clj` (`cli-config`, `-main`) |
| CR-TUI-02 | `run` MUST start interactively: enter fullscreen, and if stdin is a terminal use raw mode, otherwise fall back to a buffered line-reader for piped input. | Implemented | `main.clj` (`cmd-run`), `bases/agent-tui/.../core.clj` (`run!`) |
| CR-TUI-03 | `-i`/inline MUST force a no-alt-screen, no-raw line-reader loop. | Implemented | `core.clj` (`force-inline?`) |
| CR-TUI-04 | `ask` MUST do a single one-shot turn, print the answer, and exit 0/1 (session id `ask-<millis>`). | Implemented | `main.clj` (`cmd-ask`) |
| CR-TUI-05 | The tmux mode (A/B/C) MUST be decided pre-boot from `--with-tmux` / `$TMUX` / a `tmux`-on-PATH probe; an unsatisfiable explicit `--with-tmux` (Mode C) MUST print guidance and exit 1. | Implemented | `bases/agent-tui/.../mode.clj` (`probe`), `main.clj` |
| CR-TUI-06 | Resume wiring MUST support bare `--resume` (interactive picker; fresh if none) and `--resume <id>` (error+exit if absent). No resume flag → fresh session; `--new` is a deprecated no-op kept for back-compat. | Implemented | `main.clj` (`inject-bare-resume-sentinel`, `pick-session-interactive!`) |
| CR-TUI-07 | `config` MUST launch the config wizard; `sessions list`/`sessions prune` MUST manage persisted sessions. | Implemented | `main.clj`, `bases/agent-tui/.../config_wizard.clj` |

**CR-TUI-07 (reconciled May 2026, T-8):** the wizard's write path was
flagged Partial based on stale "don't run yet" comments next to two
filters in `config_wizard.clj` and `bootstrap_driver.clj`. In reality
the wizard's `run!` already persists on every non-`--dry-run` path
(`config_wizard.clj` lines 638/642), calling `agent/write-edn-config!
dirs filled` against the `delta-config` that `boot/merge-delta`
produces. The vestigial `:write-config` / `:write-stub-config` action
tokens were declarative plan markers that no executor arm ever
consumed — both runners explicitly removed them. They were dropped
from `plan-actions` (and the filters with them); persistence behavior
is unchanged, including the rung-(g) stub case (still written via
`merge-delta`'s `:incomplete?` branch).

---

## 2. Render / session loop

Rendering is **hook-driven**, not a per-frame poll loop: lifecycle hooks
fire and the corresponding `session.clj` handler paints.

| ID | Contract | Status | Source |
|---|---|---|---|
| CR-TUI-08 | `register-tui-hooks!` MUST wire the agent lifecycle events (iteration pre/post, dspy pre/chunk/post, tool-use pre/post, code-eval pre/post, compaction, evaluation, analytics, todo-updated) to `session.clj` handlers. | Implemented | `core.clj` (`register-tui-hooks!`), `session.clj` |
| CR-TUI-09 | Fullscreen layout MUST use an alt-screen with a DECSTBM scroll region, a fixed status bar, separators, and a tab strip; live blocks MUST update/freeze/dispose at the scrollback tail. | Implemented | `bases/agent-tui/.../layout.clj` |
| CR-TUI-10 | A real-time "thinking" indicator MUST animate in fullscreen (spinner + iteration label + streaming LLM snippet) and MUST render a static spinner inline. | Implemented (inline static **by design**) | `session.clj` (`start-thinking-indicator!`) |
| CR-TUI-11 | `emit!` MUST route output by explicit session-idx → dynamic render index → installed output-sink → `layout/write-output!`, teeing scrollback to disk, and MUST defer background paints while a popover/autocomplete is active. | Implemented | `session.clj` (`emit!`); `bases/agent-tui/.../iteration_sink.clj` (`IterationSink`) |

**CR-TUI-10 (Partial-by-design):** inline mode shows a static spinner
with no live LLM-streaming animation — a deliberate asymmetry with
fullscreen, recorded so it isn't mistaken for a regression. Candidate
TODO only if inline streaming is wanted.

---

## 3. Input, commands, permissions, popups

| ID | Contract | Status | Source |
|---|---|---|---|
| CR-TUI-12a | Slash commands MUST be dispatched by `handle-input-line`, covering at minimum: `/quit /status /history /clear /compact /todo /usage /verbose /model /config /init /effort /help /continue /task /allow-path /capture /sandbox /mcp /agent /session /memory /pause /resume /queue` (plus Mode-B `/activity /log /scrollback /popup`). `/session` carries the persisted-session subcommands `tree /  fork / resume / rename` (formerly the standalone `/tree`, `/fork`, `/resume-session`, `/name`). | Implemented | `bases/agent-tui/.../commands.clj` (`handle-input-line`) |
| CR-TUI-12b | Raw-mode input MUST provide a line editor with slash sub-menu autocomplete. | Implemented | `bases/agent-tui/.../autocomplete.clj` (`read-line-raw!`, `register-submenu!`) |
| CR-TUI-13 | Permission prompts MUST cache by session dir, use a Mode-B tmux popup when feasible, fall back to an in-stream raw-mode y/n/a prompt (30s timeout), and auto-deny with an `/allow-path` hint when non-raw. | Implemented | `bases/agent-tui/.../permissions.clj` (`make-permission-fn`) |
| CR-TUI-14 | User-feedback prompts MUST present 2–6 options, serialized on a lock, with a 60s timeout. | Implemented | `permissions.clj` (`make-user-feedback-fn`) |
| CR-TUI-15 | The popup/questionnaire path MUST drive an interactive tmux `display-popup`, gated on tmux ≥3.2 and client height ≥24, falling back to in-stream otherwise. | Implemented (with gaps) | `bases/agent-tui/.../popup.clj` (`show!`, `feasible?`) |

**CR-TUI-15 (Partial) — three known popup gaps:**

- Multi-tab questionnaire navigation is not supported (falls back to
  in-stream). *(`popup.clj`)*
- Mode-B popup user-feedback does not support `:free-input` options
  (falls back to in-stream). *(`permissions.clj`)*
- `tui-confirm-mutation` (the nREPL mutating-eval gate) is a v1
  visibility-only auto-allow — there is no blocking Y/n popup; an
  explicit "future v2". *(`core.clj`)*

Candidate TODOs: implement multi-tab popup nav, popup free-input, and the
v2 blocking mutation gate.

---

## 4. Session persistence & tmux substrate

| ID | Contract | Status | Source |
|---|---|---|---|
| CR-TUI-16 | `persist-bridge` MUST subscribe once on `start!`, route by `session-id`, append `messages.log` (events + payloads with a high-water counter), snapshot `session.edn` on every `:agent.ask/post`, write `meta.edn` on created events, and tee ANSI scrollback to `scrollback.stream.txt`. | Implemented | `bases/agent-tui/.../persist_bridge.clj` |
| CR-TUI-17 | The on-disk store MUST live at `~/.brainyard/sessions/<id>/` (user-scoped) with canonical filenames for meta/session/messages/scrollback/layout/dialogs/permissions/queue/todo/status/input-history and a `by-host.lock`. | Implemented | `components/agent-tui-persist/.../paths.clj` |
| CR-TUI-18 | The store MUST guarantee: a PID + `FileLock` lockfile with stale-crash detection; eviction at a 50 MiB cap and 14-day TTL; scrollback truncate/repair; snapshot read/write/update; session restore; and a fork/lineage tree. | Implemented | `agent-tui-persist` (`lock.clj`, `eviction.clj`, `scrollback.clj`, `snapshots.clj`, `restore.clj`, `tree.clj`) |
| CR-TUI-19 | The `Tmux` protocol MUST be satisfied by a `RealTmux` (shells out) and a `StubTmux` (records calls for tests), and MUST expose `supports-popup?` (≥3.2). | Implemented | `components/agent-tui-tmux/.../protocol.clj`, `real.clj`, `stub.clj` |
| CR-TUI-20 | A control protocol (Unix-socket EDN frames, host/host-callbacks/control client+server) MUST exist for a `by-host`↔`by-ui` daemon split. | **Retired** — substrate kept as test-only/internal; no shipping consumer. | `agent-tui-tmux/.../control/*`, `host.clj`, `host_callbacks.clj` |

**CR-TUI-20 (Retired) — daemon split formally dropped.**
The two-process design (`by-host` daemon + `by-ui` orchestrator over a
Unix-socket control protocol with per-pane FIFOs) was retired in May
2026. `bases/agent-tui-ui/` has been deleted from the tree. The shipping
TUI is the single-process `by` binary; tmux Mode B is used in-process for
side panes + popups, not as a separate orchestrator process. The
`agent-tui-tmux` control protocol, host transport, host-callbacks and
sink namespaces are retained as test-only/internal substrate — they
remain fully StubTmux-tested but have no shipping consumer. See
`docs/tui/architecture.md` §1 and §9 for the as-built topology and the
retirement record.

---

## 5. Display-blocks & environment detection

| ID | Contract | Status | Source |
|---|---|---|---|
| CR-TUI-21 | Display-blocks MUST go through a `BlockProvider` protocol (`-meta`/`-collapsed-marker-line`/`-expanded-lines`/`-resource-path`/`-dispose!`) and an `IBlockRegistry`, with `file-backed` and `in-memory` providers and producers for text/eval-code/result/output/error blocks. | Implemented | `components/display-block/.../protocol.clj`, providers, `bases/agent-tui/.../display_block_ui.clj` |
| CR-TUI-22 | `env-detect` MUST detect LLM providers (API-key/Ollama/Claude-CLI), executables (PATH + version), sandbox/container env (Docker/Nix/devcontainer/SSH), OS, and network egress. | Implemented | `components/env-detect/.../interface.clj` |

**Platform note:** Windows Ollama auto-install is not supported (manual
download) — a platform limitation, not a general gap.
(`env-detect/.../ollama_install.clj`, `config_wizard.clj`.)

---

## Gaps & candidate TODOs (this spec)

- **CR-TUI-15 — three popup gaps.** Multi-tab nav, popup `:free-input`,
  and the v2 blocking mutation gate (`tui-confirm-mutation`). *(Medium,
  splittable into three.)*
- **CR-TUI-10 — inline thinking indicator is static.** By design today;
  only a TODO if live inline streaming is wanted. *(Optional.)*
- **Windows Ollama auto-install unsupported.** Platform limitation;
  TODO only if Windows is a target. *(Optional.)*

Note: this codebase records limitations in prose ("not supported yet",
"don't run yet", "future v2") rather than `TODO`/`FIXME` tags — the gaps
above were read from intent, not from tag comments.
