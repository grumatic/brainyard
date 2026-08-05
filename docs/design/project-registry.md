# User-scope project registry (`~/.brainyard/projects/`)

Gives every project a stable, per-account folder in **user scope**, so
user-scoped state *about* a project has somewhere to live that is not the
repo's own `<project>/.brainyard/`.

Implementation: `components/agent/src/ai/brainyard/agent/core/projects.clj`.
Tests: `components/agent/test/ai/brainyard/agent/core/projects_test.clj`.

## Why a third place

`by` already has two scopes, and neither fits:

- `<project>/.brainyard/` is **project scope** — it travels with the codebase
  and is gitignored per-repo. Anything the *user* knows about a project (when
  they last opened it, private per-project preferences) doesn't belong in a
  directory that lives inside someone's repo.
- `~/.brainyard/` is **user scope**, but flat. There was no per-project
  subdivision of it, so anything project-specific either leaked across
  projects (one global memory DB) or had to go back into the repo.

The registry is the missing per-(user, project) slot. v1 stores **registry
metadata only** — nothing was relocated out of `<project>/.brainyard/`.

## Layout

```
~/.brainyard/projects/
  brainyard-3f9a1c2d/
    project.edn          ; authoritative record for THIS project
  brainyard-8e40bb17/    ; a different checkout that shares a basename
    project.edn
  index.edn              ; DERIVED cache: slug -> path
```

`projects` is registered `:user-only` in `core.config/subdir-scope-policy`, so
it resolves through the normal `brainyard-subdir` machinery and asking for it
at `:project` scope returns nil rather than creating a stray dir inside a repo.

### `project.edn`

```clojure
{:path           "/Users/xroger88/Projects/Grumatic/brainyard"
 :slug           "brainyard-3f9a1c2d"
 :name           "brainyard"
 :git-remote     "git@github.com:grumatic/brainyard.git"   ; nil when absent
 :created-at     #inst "2026-08-04T13:50:17.000-00:00"
 :last-opened-at #inst "2026-08-04T13:50:17.000-00:00"
 :schema-version 1}
```

`:schema-version` exists so a later phase (a user-scope per-project
`config.edn`, a project-partitioned memory DB) can evolve the shape without
guessing at old files. Unknown keys written by a newer version are merged
through on re-registration rather than dropped.

`:git-remote` is parsed straight out of `.git/config` (following the `gitdir:`
pointer when `.git` is a file, i.e. worktrees and submodules). Deliberately not
a `git` subprocess: this runs at every startup, and a subprocess there costs
latency and adds a process-spawn dependency under native-image for a purely
informational field.

## The slug

`<sanitized-basename>-<8 hex of SHA-256(canonical path)>` — e.g.
`brainyard-3f9a1c2d`.

- **Space-free and readable.** The basename is reduced to `[A-Za-z0-9._-]`,
  runs collapsed, capped at 48 chars. `/` degrades to `root`.
- **Stable.** Derived from the *canonical* path, so the same project always
  yields the same slug. That's what makes registration idempotent.
- **Collision-free across checkouts.** `~/Projects/x/brainyard` and
  `~/MyDev/brainyard` share a basename but not a hash.

## Reverse mapping is a lookup, not an algorithm

slug → absolute path is recovered by reading `<slug>/project.edn`. This is
exact and lossless, and it was a deliberate choice over two alternatives:

| Scheme | Reversible? | Readable? |
|---|---|---|
| Separator substitution (`/Users/me/my-app` → `-Users-me-my-app`) | **No** — ambiguous once a segment contains the separator character, and real paths do (`my-app` vs `my/app`) | Yes |
| Percent-encoding the whole path | Yes | No — long and unreadable in `ls` |
| **Short readable name + record file** | **Yes** (exact) | **Yes** |

There is a regression test for precisely the case that defeats separator
substitution: `my-app` and `my/app` under the same parent must round-trip to
distinct, exact paths.

## `index.edn` is a derived cache

It maps slug → path for fast listing, is rebuilt by scanning the per-slug
`project.edn` files, and is **never** read as the source of truth
(`project-path-for-slug` doesn't consult it at all).

That is what makes concurrent `by` processes safe: each writes only its own
slug's `project.edn`, so there is no contention on the common case, and a stale
or torn index heals on the next refresh. Both files are written via a
same-directory temp file + atomic move, so a reader never sees a half-written
record.

## When registration happens

`register-project!` is called from exactly two places, both in the app's
`main.clj` and both **after** `install-working-dir!` — so `-C` /
`BY_PROJECT_DIR` are already in effect and the project registered is the one
the session will really work in:

- `run-tui!` (interactive)
- `cmd-ask` (headless)

It is deliberately **not** hooked into `ensure-config-dirs!`, which runs on
every `init-dirs!` (including each memory-manager construction) — stamping
`:last-opened-at` there would be write churn on a hot path. Read-only
invocations (`--version`, `--help`, `by projects list`) never register.

Failure is swallowed and logged (`::project-register-failed`): the registry is
auxiliary bookkeeping and must never stop a session from starting.

`ensure-project-registered!` is the non-stamping variant for consumers that
need the folder to exist but aren't a session start.

## CLI

```bash
by projects list            # every registered project, newest first
by projects list --json     # machine-readable
by projects path <slug>     # reverse a slug to its absolute path (exit 1 if unknown)
```

```
SLUG                NAME       OPENED      PATH
my-app-00bbafc2     my-app     2026-08-04  /tmp/repos/my-app
brainyard-91736f6a  brainyard  2026-08-04  /tmp/repos/two/brainyard  (missing)
brainyard-c59395fc  brainyard  2026-08-04  /tmp/repos/one/brainyard
```

## Known limits (v1)

- **Moving a project produces a new slug**, orphaning the old entry. The slug
  is path-derived, so this is inherent to the scheme. `:git-remote` is recorded
  so a future `by projects adopt` could merge them; today the stale entry is
  simply flagged `(missing)`.
- ~~**No pruning.**~~ **Resolved:** `by projects prune` (`prune-projects!`)
  reclaims entries whose directory is gone. It keeps the precedent that
  reclamation is a *separate, explicit* operation rather than a side effect of
  another one — `list-projects` still only flags, and nothing prunes
  automatically. That is deliberate: `(missing)` is not proof a project is
  gone. An unmounted volume, a detached disk and a downed network share all
  report identically and come back, so automatic reclamation would silently
  discard the user-scope folder of a project that still exists. Hence the
  command confirms before deleting, with `--yes` for scripting.

  The slug is untrusted input on this path — it is read back off disk, not
  recomputed — so `delete-record-dir!` accepts only a bare directory name and
  re-checks that the canonical target is a direct child of the registry root
  before deleting anything. It is also total: a hostile or malformed record
  returns false rather than throwing, so it cannot abort a prune partway and
  leave the index describing directories that are already gone.
- **A corrupt `project.edn` is skipped, not repaired** — it's dropped from
  listings and rewritten on the next registration of that path.

## Extension points

The obvious next phases, both anticipated by `:schema-version`:

1. **User-scope per-project `config.edn`**, slotted into the config precedence
   chain *below* `<project>/.brainyard/config.edn` and *above* the global
   `~/.brainyard/config.edn` — private per-project settings that never get
   committed.
2. **Project-partitioned memory**, moving `~/.brainyard/memory/<user-id>.db` to
   `~/.brainyard/projects/<slug>/memory/<user-id>.db`. This one needs a
   migration story for existing DBs and is a real behavior change, not a
   drop-in.
