# Brainyard `by` — Tutorials

Terminal walkthroughs of the `by` CLI, recorded with
[asciinema](https://asciinema.org) and played back with a vendored, offline
player (no CDN). Each tutorial pairs a recorded session with a turn-by-turn
walkthrough.

## View the tutorials

- **[index.md](index.md)** — the tutorials **index page**: every walkthrough
  with an embedded, offline player. This is the page rendered on the docs site;
  regenerate it with `bb tutorial:embed`.
- **[output/index.html](output/index.html)** — a self-contained local-view page
  (casts + player inlined as base64) that opens directly in a browser with no
  server. Build it with `bb tutorial:output` (it is gitignored — regenerable).

## Catalog

| #  | Tutorial                                   | Agent / focus                                            |
|----|--------------------------------------------|----------------------------------------------------------|
| 01 | Hello, brainyard                           | ACP stub — recording-substrate smoke test (CI anchor)    |
| 02 | CoAct in action                            | coact-agent — code channel + tool channel                |
| 03 | Creating a skill                           | skill-agent — discover / author / verify                 |
| 04 | Codebase discovery                         | explore-agent — locate / summarize / persist             |
| 05 | Drafting an implementation plan            | plan-agent — pre-flight / blueprint / post-flight        |
| 06 | From plan to todos                         | todo-agent — ordered todos + R1–R7 rubric                |
| 07 | Multi-turn with the native `by` binary     | native `by` — basic tools → functional → coordinate      |
| 08 | Coordinating specialists with research     | research-agent — multi-specialist threaded dossier       |

## Authoring & regenerating

Scenarios (the source of truth) live in [`scenarios/`](scenarios/); the casts in
`casts/`, the vendored player in `assets/`. See **[AUTHORING.md](AUTHORING.md)**
for the full guide (launch modes, `:workspace`, multi-turn, `:walkthrough`).

```bash
bb tutorial:list          # list scenarios + recorded status
bb tutorial:dry-run <id>  # parse + plan, no recording
bb tutorial:record  <id>  # record a cast
bb tutorial:embed         # regenerate index.md (commit it)
bb tutorial:output        # regenerate the self-contained output/index.html
bb tutorial:doctor        # check prerequisites (asciinema, tmux, …)
```
