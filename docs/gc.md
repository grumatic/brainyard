# Garbage Collection of On-Disk Artifacts

Brainyard writes a number of **transient artifacts** to disk while an
agent runs — background-task logs, CoAct scratch files, sandbox output
overflow caches. These are working files, not deliverables: they exist
so the LLM can read state back across iterations, survive a ring-buffer
eviction, or recover truncated output. Left unmanaged they would grow
without bound.

Reclamation happens in **two tiers**:

1. **Eager cleanup** — artifacts tied to a single, short-lived operation
   are deleted the moment that operation finishes (with a safety-net
   hook for abnormal exits). This is the CoAct code-script path.
2. **Garbage collection (GC)** — everything else is reclaimed in bulk by
   age/count/byte-bounded *sweeps* that run automatically at session
   start and on demand via `task$sweep`. This is the backstop, and the
   sole mechanism for artifacts that must outlive the operation that
   created them (task output logs, verbatim scratch, truncation caches).

The GC layer lives in `ai.brainyard.agent.gc`. All artifacts are
**project-scoped** — they land under `<project>/.brainyard/`, resolved
via `BY_PROJECT_DIR` / git-root walk (see [Configuration](core/config.md)).

---

## Why two tiers

A single eager-delete-on-finish policy is wrong for most of these files,
because the consumer is the *next* LLM iteration, not the operation that
wrote them:

- A **task output log** is read back via `task$detail` *after* the task
  completes — deleting it on completion would defeat its purpose.
- A **verbatim scratch file** (`````markdown name.md`````) is written in
  one iteration and promoted (`(spit "docs/x.md" (slurp <path>))`) in a
  later one.
- A **truncation cache** holds the untruncated original of an
  over-length result so the LLM can `read-file` chunks of it later.

So the only artifact safe to delete eagerly is the **CoAct code script**
(`.sh`/`.py`/`.js`) — it is consumed by the shell that runs it and is
worthless afterward. Everything else is intentionally retained and swept
on a clock.

---

## The three GC classes

`ai.brainyard.agent.gc` defines three sweeps, one per artifact class:

| Class | Location (under `<project>/.brainyard/`) | Bound | Liveness-aware |
|---|---|---|---|
| `:tasks` | `tasks/task-N/` | newest **N** *OR* younger than **D** days (union) | ✓ (skips non-terminal) |
| `:coact-scratch` | `temp/coact-agent/scratch/` | older than **H** hours | — (file mtime only) |
| `:sandbox-cache` | `temp/clj-sandbox/{truncation,file-backed}/` | **count** + **bytes** + **age** caps | — (file mtime only) |

Each sweep returns a uniform result map:

```clojure
{:class :tasks :scanned 12 :deleted 3 :kept 9 :bytes-freed 48213 :dry-run? false}
```

`run-all!` runs all three independently — one sweep failing (logged via
mulog) does not skip the others.

---

### `:tasks` — background-task directories

Each background task gets `<project>/.brainyard/tasks/task-N/` holding
`output.log` (combined stdout+stderr) and `meta.edn` (lifecycle
snapshot). See [Task manager](core/task.md).

`sweep-tasks!` keeps a directory if **any** of three predicates holds —
it is a **union**, so a task survives unless *all* bounds agree to drop
it:

1. **Live** — `meta.edn` reports a non-terminal `:status` (anything other
   than `:completed` / `:failed` / `:cancelled`). A missing or unreadable
   `meta.edn` is treated as live, so a sweep never races a writer that
   has created the dir but not yet closed its appender.
2. **In newest-N** — within the most recent `:task-retention-count`
   (default **100**) dirs by mtime.
3. **Fresh** — younger than `:task-retention-days` (default **7**).

The live check is applied *first*, then count/age — a running task is
never reclaimed regardless of how many siblings exist.

> **Task removal ≠ artifact removal.** Deleting a task via `/task del`
> (`agent/remove-task`) drops only the in-memory registry entry; the
> on-disk dir is left for post-mortem inspection and reclaimed later by
> this sweep. An opt-in helper `manager/remove-task-and-artifacts!`
> deletes both at once, but it is deliberately *not* the default path.
> This decoupling is recorded in the repo-root `CLAUDE.md`.

---

### `:coact-scratch` — CoAct scratch files

`<project>/.brainyard/temp/coact-agent/scratch/` holds two kinds of file,
both named `coact-<millis>-<rand>[-<filename>].<ext>`:

- **Code scripts** (`.sh` / `.py` / `.js`) — code from a CoAct fenced
  block, written byte-for-byte so the shell can execute it without
  escaping hazards.
- **Verbatim content** (`.md` / `.html` / `.txt`) — large documents the
  LLM emits via 4-backtick fences, written verbatim and promoted into the
  project tree from a later code block.

`sweep-coact-scratch!` is a plain flat-file delete (no subdirs) of
anything older than `:coact-scratch-max-age-hours` (default **24**).

**Code scripts are normally gone before the sweep ever sees them** —
they are deleted eagerly (see [Eager cleanup](#eager-cleanup-coact-code-scripts)).
The 24-hour sweep is what actually reclaims the **verbatim** files (which
have no eager-delete path) and mops up any code script whose eager delete
was missed.

---

### `:sandbox-cache` — eval output overflow caches

When an eval/tool result exceeds `:max-output-chars`, the SCI sandbox
(`ai.brainyard.clj-sandbox`) spills the untruncated original to a file
under `temp/clj-sandbox/truncation/<class>/` (and display blocks under
`temp/clj-sandbox/file-backed/<class>/`), returning a truncation marker
that tells the LLM how to `read-file` it back.

`sweep-sandbox-cache!` walks **both roots together** (so a hot truncation
cache can crowd out stale file-backed entries) and drops oldest-first in
three ordered passes:

1. **Age** — drop everything older than `:sandbox-cache-max-age-days`
   (default **7**). Cheap, no sort.
2. **Count** — if survivors exceed `:sandbox-cache-max-files`
   (default **200**), drop the oldest until under.
3. **Bytes** — if survivors still exceed `:sandbox-cache-max-bytes`
   (default **50 MiB**), drop the oldest until under.

Order matters: an age-expired file is always dropped before count/byte
trimming runs.

---

## Eager cleanup: CoAct code scripts

The one artifact reclaimed *without* waiting for a sweep. After a code
block runs, `coact-agent` calls `delete-coact-tmp-file!` on every exit
path:

- inline (fast-eval) success,
- task-based completion,
- pending-task harvest (soft-timeout → later completion),
- exception.

As a backstop for paths that finalize a task abnormally (cancel, crash
recovery, the detach-watcher) and never reach the harvest loop, a
`:task/completed` hook (`::coact-scratch-cleanup`) deletes the file keyed
off `:metadata :coact/tmp-file`. The delete is **idempotent** — the eager
deletes are just for promptness; the hook makes loss impossible; and the
24-hour `:coact-scratch` sweep is the final safety net.

> Verbatim scratch files (`.md`/`.html`/`.txt`) have **no** eager-delete
> path on purpose — they are intermediates the LLM reads back later, so
> they ride the 24-hour sweep instead.

---

## When sweeps run

**Automatically, at session start.** `gc` registers a
`:agent.session/created` hook that fires `run-all!` in a background
future — best-effort, exceptions never reach the caller. A fresh session
triggers cleanup once.

**Throttled to once per hour.** TUI bring-up plus a first `ask` can emit
two `:agent.session/created` events in under a second; an in-process
throttle (`!last-sweep-ms`, 1 h window) collapses bursts so the disk
isn't walked repeatedly.

**On demand,** via the `task$sweep` command (below).

---

## `task$sweep` — manual / scoped GC

```
task$sweep                              ; sweep all three classes
task$sweep :scope "tasks"               ; one class only
task$sweep :scope "coact-scratch"
task$sweep :scope "sandbox-cache"
task$sweep :dry-run? true               ; report what would be deleted, delete nothing
```

`:scope` is one of `"tasks"`, `"coact-scratch"`, `"sandbox-cache"`, or
`"all"` (default). The result aggregates the per-class maps plus
`:total-deleted` and `:total-bytes-freed`. With `:dry-run? true`, every
sweep reports `:deleted`/`:bytes-freed` counts *as if* it had run but
touches nothing — use it to preview before reclaiming.

---

## Configuration

All bounds live in `ai.brainyard.agent.core.config/config-schema` and are
overridable per the standard [config precedence chain](core/config.md):

| Key | Default | Governs |
|---|---|---|
| `:task-retention-count` | `100` | `:tasks` — newest-N kept |
| `:task-retention-days` | `7` | `:tasks` — age floor |
| `:coact-scratch-max-age-hours` | `24` | `:coact-scratch` — age cutoff |
| `:sandbox-cache-max-files` | `200` | `:sandbox-cache` — file-count cap |
| `:sandbox-cache-max-bytes` | `52428800` (50 MiB) | `:sandbox-cache` — byte cap |
| `:sandbox-cache-max-age-days` | `7` | `:sandbox-cache` — age cutoff |

Every sweep function also accepts inline overrides (`:retention-count`,
`:max-age-hours`, `:max-files`, …) for tests and one-off calls, which
take precedence over the config defaults.

---

## Caveat: the legacy `/tmp/` fallback

Every artifact writer *prefers* the project-scoped `.brainyard/` path but
falls back to `/tmp/` when the project scope can't be resolved (rare —
e.g. no `user.home`, certain native-image quirks). **Files written to the
`/tmp/` fallback are not project-scoped and are therefore never visited
by these sweeps** — they rely on the OS's own `/tmp` reaping. This only
affects degraded environments; the normal path is fully GC-managed. The
`/tmp/` → `.brainyard/` migration table is in
[architecture.md](architecture.md).

---

## Where the code lives

| Concern | Namespace / function |
|---|---|
| Sweeps + session hook + `run-all!` | `ai.brainyard.agent.gc` |
| `task$sweep` command | `ai.brainyard.agent.task.commands` |
| Task dir layout + `delete-task-dir!` | `ai.brainyard.agent.task.persist` |
| Eager code-script delete + `:task/completed` hook | `ai.brainyard.agent.common.coact-agent` |
| Truncation / file-backed cache writers | `ai.brainyard.clj-sandbox` |
| Retention bounds (schema) | `ai.brainyard.agent.core.config` |

Tests: `ai.brainyard.agent.gc-test` exercises all three sweeps including
liveness-skip, union retention, and the oldest-first byte/count passes.
