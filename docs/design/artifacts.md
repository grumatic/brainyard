# Live artifacts design

How brainyard injects *reference material* into an agent's prompt at runtime and
lets the LLM curate it — skill files, project docs (`CLAUDE.md` / `AGENTS.md`),
or ad-hoc notes that help the model understand the working context.

A live artifact is a small descriptor that renders as one `### <name>` block
inside the `## Live Artifacts` section of the CoAct **user-context** (see
[context-management.md](context-management.md) §2.2 for where that section sits
in the prompt and how prompt caching treats it). Two independent streams feed
the section every turn:

- **System artifacts** — reference files the runtime seeds automatically
  (config-driven; `CLAUDE.md` / `AGENTS.md` by default). Pinned, never removable
  by the LLM, re-derived fresh each turn.
- **Dynamic artifacts** — material the LLM pins itself via the `artifact$*`
  tools. Persisted for the session so they survive across turns.

This document covers the descriptor model, the per-turn lifecycle, persistence,
rendering, token-budget behaviour, link-dedup, and the LLM-facing tools.

## The descriptor

Every artifact — system or dynamic — is a plain map:

```clojure
{:id        "ref:/abs/CLAUDE.md"   ; stable key for dedupe / removal
 :name      "CLAUDE.md"            ; display heading
 :source    :file                  ; :file (reload from disk) | :inline (stored)
 :path      "/abs/CLAUDE.md"       ; when :source :file
 :content   "..."                  ; when :source :inline
 :origin    :system                ; :system (seeded) | :llm (tool-added)
 :pinned?   true                   ; protected from budget eviction / LLM removal
 :max-chars 4000}                  ; inline truncation cap (stamped at resolve)
```

`:source` is the key distinction:

- **`:file`** stores only a path; content is (re)loaded fresh from disk every
  turn, so on-disk edits show up live and the registry stays tiny. Rendered as a
  short **preview** (see [Rendering](#rendering)).
- **`:inline`** stores its content verbatim — for LLM-authored notes with no
  backing file. Rendered up to `:max-chars`.

A bare `{:name … :content …}` map (no `:source`) is treated as `:inline`, so
legacy callers keep working.

## Per-turn lifecycle

All assembly happens once per turn in `coact-init-action`
(`agent.common.coact_agent`). The section starts empty in `st-memory` and is
built by merging the two streams, resolving file content, and writing the result
back so the rest of the turn (rendering + budget) can read it:

```
coact-init-action:
  load BRAINYARD.md          → config/load-brainyard-instructions
                               (returns :instruction-identities for dedup)
  seed system artifacts      → config/reference-artifact-descriptors
                               (config :reference-artifact-paths,
                                excluding BRAINYARD.md identities)
  dynamic artifacts          ← (:live-artifacts @st-memory)
                               (copied from st-memory-init by the BT reset)
  merge (system ++ dynamic)  → merge-artifact-descriptors  (dedupe by :id)
  resolve                    → resolve-artifacts            (load :file fresh,
                                                             drop missing,
                                                             stamp :max-chars)
  (swap! st-memory assoc :live-artifacts resolved)   ← the write
  render + budget            → format-live-artifacts / enforce
```

`merge-artifact-descriptors` is public so the tools and the init path share one
dedupe rule: system artifacts come first (stable display) and the first
occurrence of an `:id` wins.

`resolve-artifacts` is what makes `:file` artifacts cheap and always-fresh: it
reloads each file's content from disk, silently drops descriptors whose file has
gone missing, and stamps the effective `:max-chars` onto every survivor.

## Persistence model

The split between the two stores is the crux of the design:

| Store | Holds | Lifetime |
|---|---|---|
| **per-turn `st-memory`** (BT context) | the resolved set rendered *this* turn (system + dynamic) | one turn — reset at the next turn's init |
| **`st-memory-init`** (agent `!state`) | the dynamic registry (`:origin :llm`) | the whole session |

At the start of every turn the behaviour tree resets the per-turn `st-memory`
from a copy of `st-memory-init` (`agent.core.bt`). That single mechanism is why
dynamic artifacts persist: the tools write the **registry** in `st-memory-init`,
and each turn's reset re-seeds them into the per-turn store, where the init path
then merges the freshly-derived system artifacts on top.

Consequences, by design:

- **System artifacts are never persisted** — they are re-derived from disk every
  turn, so config or file changes always take effect immediately.
- **Dynamic artifacts are never re-seeded** — they live only in the registry.
- **Tool edits take effect next turn.** A tool writes `st-memory-init`, but the
  prompt for the *current* turn was already assembled at init. So an added /
  removed / pinned artifact changes what renders starting from the next turn.
- **Dynamic persistence is session-only / in-memory** — it does **not** survive
  `--resume` (a deliberate scope choice; the registry is not serialised).

## Rendering

`format-live-artifacts` turns the resolved descriptors into the section text.
Two behaviours matter:

**Badges.** Each block is headed `### <name>` with a parenthetical badge for
`:origin :system` and `:pinned?` so the LLM can tell what it may remove:

```
### CLAUDE.md (system 📌)
### E2E Note (📌)
### scratch
```

**Source-dependent truncation.**

- **`:file` artifacts** render only a **400-char preview**
  (`file-artifact-preview-chars`). When the file is longer, the body is cut and a
  pointer to fetch the rest on demand is appended:

  ```
  ### CLAUDE.md (system 📌)
  # CLAUDE.md
  This file provides guidance to Claude Code …
  …
  [truncated — `(read-file {:path "/abs/CLAUDE.md"})` for the full content]
  ```

  The full bytes need not ride the prompt every turn: the file reloads each turn
  anyway, and the LLM can `read-file` the path when it actually needs the body.

- **`:inline` / legacy artifacts** render their content up to `:max-chars`
  (config `:live-artifact-max-chars`, default 4000) — there is no file to read
  back, so the content must live in the prompt.

## Token-budget behaviour

(For the budget loop itself — `enforce`, the strategy catalog, the cross-turn
compactor — see [compaction.md](compaction.md).)

The section is registered in `agent.core.context_budget/default-section-policies`
as `:live-artifacts {:priority 70 :compact :drop-live-artifacts}` — below
`previous-turns` (50) and `conversation-history` (60) in protection, above
nothing else volatile, so it is trimmed only after cheaper carryover.

The `:drop-live-artifacts` strategy (in `coact-strategies`) is **pin-aware**: it
evicts the *oldest droppable* artifact first, where droppable means **not
`:pinned?` and not `:origin :system`**. System reference docs and explicitly
pinned artifacts are protected.

When only pinned/system artifacts remain, the strategy makes no progress. The
`:live-artifacts` policy carries `:keep-floor? true`, so `enforce` treats that as
a **floor**: it keeps the remaining (pinned/system) content and retires the
section from further compaction rather than dropping it wholesale. The pass is
recorded as `:kept-floor` in the `:compactions` audit trail, and the turn
proceeds `:over-budget? true` if nothing else can be trimmed. In other words,
**pinned and system artifacts are never dropped, even as a last resort** — only
the unpinned, LLM-added artifacts are evicted under pressure. (Without
`:keep-floor?`, `enforce`'s generic no-progress guard would drop the whole
section to break the loop.)

## Link-dedup (BRAINYARD.md / CLAUDE.md / AGENTS.md)

These three docs serve as project- and user-scope context and are frequently
*linked* to a single source — a project may symlink (or hardlink)
`CLAUDE.md → AGENTS.md` for a single source of truth, or point either at
`BRAINYARD.md`. The same content must never load twice.

Dedup is by **file identity**, not path. `config/file-identity` returns the NIO
`fileKey` (device + inode), which collapses **both symlinks and hardlinks** to
the same target (falling back to the canonical path string if `fileKey` is
unavailable). `reference-artifact-descriptors` de-dupes its resolved files by
this identity.

Cross-stream dedup against `BRAINYARD.md` closes the last gap: `BRAINYARD.md`
is loaded separately as `:system-context` instructions (not as a live artifact),
so `load-brainyard-instructions` also returns `:instruction-identities`, and
`coact-init-action` passes them as `:exclude-identities` to the seeder. A
`CLAUDE.md`/`AGENTS.md` that is linked to `BRAINYARD.md` is therefore dropped
from the artifacts rather than emitted a second time.

User-scope reference docs are handled by the same config knob: list an absolute
or `~`-prefixed path in `:reference-artifact-paths` (e.g. `~/.claude/CLAUDE.md`)
and it participates in the same identity dedup.

## LLM-facing tools

`agent.common.artifacts` defines four `defcommand`s, bound into every
coact-derived agent via `artifact-commands` in `all-common-commands`
(`agent.common.commands`):

| Tool | Effect |
|---|---|
| `artifact$add` | Pin a file (absolute `:path`, reloaded each turn — e.g. a skill's `SKILL.md`) or inline `:content`; optional `:pinned`. Writes the registry. |
| `artifact$list` | List the artifacts the agent can see or act on. |
| `artifact$remove` | Remove a dynamic artifact by `:id`. Refuses `:origin :system` unless `:force`. |
| `artifact$pin` | Toggle a dynamic artifact's `:pinned?`. |

All mutations target `st-memory-init` (the persistent registry) via
`upsert!` / direct `swap!`, so they survive across turns — and, per the
persistence model, take effect from the next turn.

**Steering the model.** The tools alone don't tell the LLM *which* of the
things it has read are worth a slot. That decision policy is surfaced three
ways (`agent.common.coact_agent` + `agent.common.usage_nudge`):

- An always-on hot-path row in `coact-tools-hotpath` —
  *"Seen a file/skill you'll re-reference → `(artifact$add {:path …})`"* — the
  cheap nudge that makes the model reach for the tool unprompted.
- A deep guide, `(usage :artifacts)` (`get-usage-guide` / `usage-topics` in
  `clj-sandbox/core/prompt.clj`). It spells out the policy: add a file you'll
  re-reference (prefer `:path` — only a preview rides the prompt and it reloads
  fresh); don't duplicate system artifacts or Project Memory, or pin one-off /
  huge reads; `artifact$remove` once stale; `pin` sparingly since every
  artifact is per-turn budget weight.
- **Just-in-time + permanent inline** (`usage_nudge`, see below) — the
  `:artifacts` guide is permanently inlined into the tool-context by default
  (config `:inline-usage-guides`), so the model never has to *call* `(usage)`
  for it. Every other guide-backed family is pushed on first use.

This mirrors the two-tier pattern used for the other stewardship areas
(`:memory`, `:skills`, `:todo`, `:plans`) — all of which `usage_nudge` surfaces
just-in-time.

### Just-in-time usage guides (`usage_nudge`)

The `(usage :topic)` guides are pull-based and were chronically under-triggered
— the model rarely *chose* to fetch one. `agent.common.usage_nudge` makes them
push-based:

- A `:agent.tool-use/post` observer (`ensure-global-hooks!`, installed once per
  process from `coact-init-action`) watches every tool call — including SCI
  code-channel calls, which route through `tool/call-tool` → the same hook
  chain. `tool-family->topic` maps the id's family segment (text before `$`) to
  a topic.
- The same handler also listens on **`:agent.tool-use/rejected`**, a dedicated
  event `call-tool` fires when a call fails *arg-validation* and short-circuits
  before dispatch (so it never reaches `:agent.tool-use/post`). This is what
  surfaces the guide on a malformed *first* call to a real family — arguably the
  moment the model needs it most. (Permission-`:denied` and unknown-tool early
  returns deliberately do **not** fire it.)
- On the **first** use of a guided family this session, it marks the topic in
  `st-memory-init :usage-tips-shown` (once per session) and queues the guide
  into the per-turn `bt-st-memory :pending-usage-guides`. A failing first call —
  whether a runtime `:error` or an arg-validation rejection — is queued with
  `:reason :error`.
- `coact-accumulate-iteration-action` drains the queue when it builds the
  iteration record, attaching the rendered guide as the record's `:notices`
  field — which the model reads next iteration via DSPy serialization of
  `:iterations` (`:notices` is in the `::iteration` schema).
- Topics in config `:inline-usage-guides` (default `[:artifacts]`) are rendered
  into the tool-context every turn *and* pre-seeded into `:usage-inlined-topics`
  via `seed-inlined-topics!`, so the first-use path never duplicates them.

Net effect: the one universally-relevant guide (`:artifacts`) is always present;
the long tail (`:memory`, `:plans`, `:todo`, `:skills`, `:llm-query`, `:mcp`)
fires once, exactly when the model first reaches for that family. Tune coverage
by editing `tool-family->topic`; the full pull catalog stays available via
`(usage)`.

**The union view.** Reads (`artifact$list`, and the presence check in
`artifact$remove`) go through `effective-artifacts`, which returns the **union**
(deduped by `:id`, per-turn store first) of:

- the per-turn `st-memory` set (system artifacts + dynamic artifacts already
  merged in for *this* turn), and
- the `st-memory-init` registry (dynamic artifacts, including ones just added
  this turn that have not yet surfaced in the prompt).

The union matters: once a system doc is seeded the per-turn store is always
non-empty, so reading it alone would hide a just-added artifact until the next
turn — making it un-listable and un-removable in the same turn it was added.
(This was a real bug, caught by the live nREPL e2e and fixed by switching to the
union; see the regression test in `artifacts_test.clj`.)

## Configuration

| Key | Default | Meaning |
|---|---|---|
| `:reference-artifact-paths` | `["CLAUDE.md" "AGENTS.md"]` | Files seeded as system artifacts. Relative names resolve against project-dir then working-dir; absolute / `~` as-is; missing files skipped. |
| `:live-artifact-max-chars` | `4000` | Truncation cap for inline artifacts. |

The 400-char file preview is a code constant
(`file-artifact-preview-chars` in `coact_agent.clj`), not a config knob.

## Testing

- **Unit** — `agent/test/.../artifacts_test.clj` (tool round-trips, the union
  view, system-removal refusal), `coact_agent_test.clj/live-artifacts-helpers-test`
  (formatter badges + file preview + read-file pointer, `resolve-artifacts`,
  `merge-artifact-descriptors`, pin-aware drop),
  `core/config_test.clj/reference-artifact-descriptors-dedupes-linked-files`
  (symlink + hardlink collapse, BRAINYARD.md exclusion).
- **Live e2e** — drive a real `bb tui` session and inspect the assembled
  context over the in-process nREPL; see
  [../tui/nrepl-verify.md](../tui/nrepl-verify.md). The canonical check: confirm
  `CLAUDE.md` seeds with a preview + read-file pointer, exercise
  `artifact$add/list/remove/pin`, then run a second turn and confirm a pinned
  note survives and renders while a removed artifact is gone.

## Key files

| File | What it holds |
|---|---|
| `components/agent/src/.../common/artifacts.clj` | `artifact$*` tools, `effective-artifacts` union, `upsert!`, `artifact-commands` |
| `components/agent/src/.../common/coact_agent.clj` | `format-live-artifacts`, `resolve-artifacts`, `merge-artifact-descriptors`, `file-artifact-preview-chars`, the `coact-init-action` seed/merge/resolve, the `:drop-live-artifacts` strategy |
| `components/agent/src/.../core/config.clj` | `reference-artifact-descriptors`, `file-identity`, `load-brainyard-instructions` (`:instruction-identities`), the two config keys |
| `components/agent/src/.../core/context_budget.clj` | `:live-artifacts` priority + compact policy |
| `components/agent/src/.../common/commands.clj` | wires `artifact-commands` into `all-common-commands` |

## Design decisions

- **Reference docs by path, not by value.** File artifacts store a path and
  reload each turn — edits show live, the registry stays small, and only a
  preview rides the prompt.
- **One section, two streams, pin to protect.** System and dynamic artifacts
  share the same section and renderer; pinning (always on for system) is the
  single mechanism that protects an artifact from both budget eviction and LLM
  removal.
- **Registry in `st-memory-init`, render in `st-memory`.** Writing the persistent
  store and rendering the per-turn store is what gives cross-turn persistence and
  the "effective next turn" semantics — at the cost that reads must union the two.
- **Dedup by inode, across all three docs.** `BRAINYARD.md`/`CLAUDE.md`/`AGENTS.md`
  linked to one source load once, regardless of symlink vs hardlink or scope.
- **Session-only dynamic persistence.** The registry is in-memory; surviving
  `--resume` was explicitly out of scope.
