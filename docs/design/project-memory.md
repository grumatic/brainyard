# Project memory design

A durable, **project-scoped, file-based** knowledge base that the LLM reads and
writes with the ordinary file tools — no special agent tools. It complements the
L1/L2/L3 store (see [../core/memory.md](../core/memory.md)) rather than replacing
it.

| | L1/L2/L3 memory | Project memory (this doc) |
|---|---|---|
| Backing | SQLite + FTS5 | Plain markdown files |
| Scope | **User** (`~/.brainyard/memory/<user-id>.db`) | **Project** (`<project>/.brainyard/memory/`) |
| Population | Auto-captured + recalled (memory-agent, `contextual-recall`) | Explicit; the LLM writes it |
| Access | `IMemoryStore` protocol / memory-agent | `read-file` / `write-file` / `update-file` |
| Visibility | Opaque to direct editing | Human-readable, hand-editable |
| Persistence | Cross-project, per account | Local to the repo clone (`.brainyard/` is gitignored) |

## Layout

```
<project>/.brainyard/memory/
  index.md          # one line per topic: - [Title](slug.md) — one-line hook
  <slug>.md         # one fact/topic per file: YAML frontmatter + body
```

Each `<slug>.md` carries light frontmatter (`title`, `tags`, `updated`) followed
by the fact, with `[[other-slug]]` cross-links. `index.md` is the map the LLM
sees every turn; topic files are pulled on demand.

`.brainyard/` is auto-allowed for `write-file` (see
[artifacts.md](artifacts.md) and `reference/write-project-file`), so these
writes never trigger a permission prompt.

## How it reaches the prompt

A `## Project Memory (.brainyard/memory/)` section is added to the CoAct
**system-context**, placed right after `## Project Instructions (BRAINYARD.md)`
and before `## User Instructions`. It is session-stable like the BRAINYARD.md
sections — re-seeded every turn (so on-disk edits, including the LLM's own writes
from the previous turn, show live), costing one prefix-cache miss per edit rather
than one per turn.

The section is two parts:

1. **Static protocol** (`coact-project-memory-protocol`) — the recall/remember
   rules: read the relevant `<slug>.md` before answering; on learning a durable
   fact, write the file and update its index pointer; one fact per file; update
   don't duplicate; don't store transient state or anything already in code, git,
   or BRAINYARD.md.
2. **Live index** — the capped contents of `index.md`, or an empty-state stub
   inviting the LLM to create it on first remember.

### Lifecycle (per turn)

- `config/load-project-memory-index` reads `<project-config-dir>/memory/index.md`
  → `{:content <trimmed-or-nil> :file-count <n>}`.
- The CoAct init action seeds it (next to `load-brainyard-instructions`),
  stamps `:max-chars` from `:project-memory-max-chars`, and threads it as
  `:project-memory` into the section assembler. `nil` (facility disabled)
  omits the section entirely.
- `format-project-memory-section` renders protocol + (truncated) index.
- Budget: `:project-memory` is an immutable section at priority 85 (BRAINYARD.md
  tier). It is bounded at seed time by `:project-memory-max-chars`, so there is
  no compaction strategy.

## Config

- `:enable-project-memory` (default `true`) — gates the whole section.
- `:project-memory-max-chars` (default `4000`) — truncation cap on the injected
  `index.md` contents (topic files are read on demand, uncapped, via the tools).

## Scope policy

`subdir-scope-policy` maps `"memory"` to `:both`: the SQLite store stays
user-scoped while this file memory is project-scoped. They are distinct files at
distinct scopes under the same `memory/` name; the runtime does not merge them.

## Deliberate non-goals

- **No new tools** — the existing file tools are the entire interface.
- **No auto-bootstrap** — `write-file` creates the dir and files on first
  remember; there is no eager directory creation.
- **No git tracking** — `.brainyard/` is gitignored, so memory is local to each
  clone. Carve a `.gitignore` exception if a team-shared knowledge base is wanted.
