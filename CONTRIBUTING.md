# Contributing to Brainyard

Thanks for your interest in improving Brainyard — the agent-driven terminal UI
that ships as the `by` binary. This guide covers the development setup, the
conventions we follow, and how to get a change merged.

## Licensing of contributions

Brainyard is released under the [MIT License](LICENSE). By submitting a
contribution (a pull request, patch, or any other change), you agree that your
work is licensed under the same MIT terms and that you have the right to submit
it — i.e. **inbound = outbound**. No separate CLA is required.

Every source file carries a short MIT SPDX header; see
[Source-file headers](#source-file-headers) below.

## Development environment

You'll need:

- **GraalVM 25.0.3+** on `PATH` (or via `.sdkmanrc` + SDKMAN: `sdk use java 25.0.3-graal`).
  Required only for building the native binary — plain JDK 21+ is enough for
  running tests and the uberjar.
- **`bb`** (Babashka) and the **`clojure`** CLI.
- **`gh`** (GitHub CLI) — only needed if you publish releases.

Clone and sanity-check the workspace:

```bash
git clone https://github.com/grumatic/brainyard
cd brainyard
bb test          # run the Polylith test suite
bb poly check    # validate the workspace
```

See [`CLAUDE.md`](CLAUDE.md) for the full build/release pipeline and
[`docs/`](docs/) for architecture and design notes.

## Workspace layout

Brainyard is a [Polylith](https://polylith.gitbook.io/) workspace:

- `components/` — reusable bricks (the bulk of the logic).
- `bases/` — entry-point bricks that expose a public API (e.g. the TUI).
- `projects/agent-tui-app/` — the shipping project, composed from a curated
  subset of bricks and built to the `by` native binary + JVM uberjar.

Prefer adding logic to a focused component and wiring it into the project,
rather than growing a base. `bb poly info` shows the brick graph.

## Making a change

1. **Branch** off `main` — don't commit directly to `main`.
2. **Write tests.** New behavior needs coverage; bug fixes should add a
   regression test. Tests live alongside each brick under `test/`.
3. **Run the suite:** `bb test`. For a real LLM round-trip or a binary
   smoke test, see the testing notes in [`CLAUDE.md`](CLAUDE.md).
4. **Add license headers** to any new source file (see below).
5. **Keep the changelog current** — add an entry under the `Unreleased`
   section of [`CHANGELOG.md`](CHANGELOG.md) for user-facing changes.
6. **Open a pull request** against `main` with a clear description of the
   change and why.

## Source-file headers

Every `.clj` / `.cljc` / `.cljs` / `.bb` source file must carry the standard
MIT header. The tooling manages this for you:

```bash
bb license:add            # prepend the header to any file missing it
bb license:add --dry-run  # list files that would be touched
bb license:check          # CI gate — fails if any in-scope file lacks the header
```

`bb license:check` must pass before a PR is merged.

## Commit & PR conventions

- Use **Conventional Commit** prefixes, scoped to the area touched:
  `feat(agent): …`, `fix(poly): …`, `docs(install): …`, `test(tui): …`,
  `chore: …`. Keep the subject line imperative and under ~72 characters.
- Make commits **atomic** — one logical change per commit.
- Match the style of the surrounding code: existing naming, comment density,
  and idioms. We favor small, well-named functions and docstrings on public
  vars.
- Rebase on `main` (rather than merge) to keep history linear when feasible.

## Reporting bugs & proposing features

Open a [GitHub issue](https://github.com/grumatic/brainyard/issues). For bugs,
include your platform, the `by --version` output, and the steps to reproduce.
For larger features, it's worth opening an issue to discuss the approach before
investing in a PR.

## Questions

If something here is unclear, open an issue — improving this guide is itself a
welcome contribution.
