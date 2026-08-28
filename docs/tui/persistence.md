# TUI persistence — what survives a resume, and what does not

What `by --resume <session-id>` can put back on screen, why the rest is
missing, and what it would cost to close each gap. The on-disk layout itself is
in [architecture.md §5](architecture.md#5-componentsagent-tui-persist); this
page is about the **rendering** half — the ANSI streams and the tabs rebuilt
from them.

## The three ANSI streams

Each lives under `<project>/.brainyard/sessions/<agent-session-id>/` and is
size-capped with rotation (`scrollback.clj`, 5 MiB × 10 rotations by default).

| file | holds | written by |
|---|---|---|
| `scrollback.stream.txt` | the conversation — everything the chat tab rendered | `persist-bridge/tee-scrollback!`, from `emit!` / `emit-to-session!` |
| `scrollback.sub-output.txt` | the root's shared sub-output tab — every sub-agent's transcript | `persist-bridge/tee-sub-output-scrollback!` |
| `scrollback.activity.txt` | — | **nothing, today** |

`:activity` is a reserved slot: the tag has a filename, and archive and eviction
both handle it, but no code appends to it. (Mode B's activity *pane* is a live
FIFO in `tmux-side`, not this file.) Worth knowing before assuming a resumed
activity pane exists to restore.

The sub-output stream lives in the **root's** directory rather than one of its
own. That is not a shortcut: the tab is created with `:skip-agent-creation
true`, so it has no agent and therefore no `agent-session-id` to name a
directory with — and its lifetime is already the root's (one tab per root,
created on the root's first dispatch, cascade-closed with it).
`sessions/sub-output-tee-target` is the lookup that resolves an output tab to
the root whose stream backs it.

## What writes to disk, and what deliberately does not

Three write paths, and the difference between them is the whole model:

- **`emit-to-session!` tees.** Chat tabs to their own session id, output tabs to
  their root's sub-output stream. This is the persisted surface.
- **`layout/write-output!` does not.** It is the bypass, used by the banner, the
  resume notice, and the replay itself — bytes that must reach the screen
  without re-entering the file they came from.
- **`update-live-block!` does not.** Live blocks re-render on every tick; teeing
  each tick would write the same widget tens of times. This is the source of the
  biggest gap below.

`emit-to-session!` also takes `:persist? false` for the one case that needs the
bypass but cannot use `write-output!` — replaying into a **background** tab. Drop
it and each resume appends the whole transcript to itself.

## What a resume restores

1. **The conversation.** `start!` snapshots the `:stream` tail
   (`:resume-scrollback-bytes`, default 10 MiB) before anything this boot emits
   reaches the file; `run!` replays it via `write-resume-tail!`.
2. **The output-only tab.** The `:sub-output` tail is read in the same place,
   independently — losing the sub-agent history must not cost the conversation —
   and `session/restore-sub-output-session!` rebuilds the tab and replays it.
   Fullscreen only: inline mode has no tab strip to restore onto.

In fullscreen both replays go through `core/tail-segments`, which splits the tail
into spans and emits one per span. Each carries a `:render` and stays attached as
the entry's, so they keep reflowing on later resizes: `fit-rows` pre-wraps a
span of ordinary rows to the width being resumed onto, and a span claimed by an
answer descriptor is redrawn from its source instead. Inline prints the tail raw
and wraps nothing — there, the terminal owns line breaking (see
`layout/terminal-owns-line-breaking?`), and imposing our own breaks would
replace a wrap the terminal can rejoin on copy with a permanent one.

The restore also **registers** the rebuilt tab in `session/!root-output-sessions`
under the resumed root's agent-id. A resumed root is a new instance with a new
id; without that registration the next dispatch opens a *second* output tab —
history in one, live output in the other.

## What it does not restore

### Live blocks (iteration widgets, think blocks, task blocks)

Not teed, so neither tab gets them back. A resumed session shows the ask headers
and answer boxes but not the `[✓] Iteration 1 …` widget or its `Think:` text.

*To close it:* tee a block's **frozen** state once, at freeze time, rather than
per tick — `src-freeze-block!` / `freeze-live-block-in-session!` are already the
single points where a block stops changing. Cost is modest, but it changes
chat-tab resume too, so it is not a sub-output-only change.

### Faithful re-wrapping — except for answers

Replayed rows are rows: `fit-rows` can make them *fit* a new width but cannot
redraw a box at it, because the structured values that produced them are gone. A
narrower resume gives ragged borders on any box-drawn output.

**Answers are the exception**, because they record a descriptor. See
[docs/design/answer-descriptor-resume.md](../design/answer-descriptor-resume.md);
in short, the answer emits tee `{:kind :answer :variant … :text …}` to a sidecar
beside the ANSI stream, and `core/tail-segments` locates the rendered block back
in the replayed tail by content, handing that span a renderer built from the
source. So an answer written at 130 and resumed at 80 is *redrawn* at 80.

Everything else — tables in tool results, the welcome banner, the frozen
compaction summary — still comes back at the width it was written.

*To close it generally:* a descriptor per renderer kind, plus a registry that
rebuilds the closure. Note the shape of the cost before attempting it —
`:render` is a closure and closures do not serialise, so this is not a storage
change but a format change:

- close to 60 `:render` sites, roughly half of them in `session.clj`, become
  descriptors.
- The format becomes a cross-release compatibility surface: a descriptor written
  by one version must still render, or degrade, under the next.
- Some renderers close over state that cannot round-trip — a live atom
  (`:st-mem-atom`), a spinner frame, elapsed time computed against
  `System/currentTimeMillis`.

The answer case avoided all three by closing over nothing but a string. Weigh
each further kind on that basis rather than generalising the mechanism.

### The reflow source

`:scrollback-src` (the `{:render :n :block-id}` entry list) is in-memory only. It
survives tab switches, because `!sessions` holds it alongside the rows, and
nothing else — it is closures, and the session map is never serialised. Rows
recovered from disk are re-attached to a renderer at replay time instead, one
per segment: `fit-rows` for a span that can only be refitted, and the answer's
own formatter for a span a descriptor claimed.

### Sibling root tabs

`--resume` restores one agent-session. Tabs created with `/session new` are
independent roots with their own session ids and are resumed one at a time.

## Adding a fourth stream

The tag is the easy part; three places enumerate streams and all three must
learn about it, or the file is silently orphaned:

1. `scrollback/stream-tag->filename` — the tag itself.
2. `archive/scrollback-tags` — else archiving a session strands the file in the
   emptied live directory.
3. `eviction/enforce-size!` — else it is exempt from the size budget it counts
   against.

## Where the code is

| concern | file |
|---|---|
| stream files, rotation, `tail-bytes` | `components/agent-tui-persist/.../core/scrollback.clj` |
| tee helpers (newline discipline, `:display` hook) | `bases/agent-tui/.../persist_bridge.clj` |
| tee routing, `:persist?`, background buffering | `bases/agent-tui/.../sessions.clj` |
| output-tab registry, restore | `bases/agent-tui/.../session.clj` |
| resume read + replay | `bases/agent-tui/.../core.clj` |
| tests | `bases/agent-tui/test/.../sub_output_resume_test.clj`, `output_session_reflow_test.clj` |

## See also

- [architecture.md](architecture.md) — process topology, the per-session
  directory, the three "session" ids.
- [renderer.md](renderer.md) — scrollback, live blocks, reflow.
- [pause-resume.md](pause-resume.md) — pausing and resuming a running agent
  (distinct from resuming a persisted session).
