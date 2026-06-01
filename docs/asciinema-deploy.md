# asciinema Tutorial Recording & Deployment — Design

*Status: implemented (PR0–PR5 landed). Owner: tooling. Last updated: 2026-05-30.*

> **As-built notes (2026-05-30).** The recording substrate, scenario DSL,
> parser/schema, `bb tutorial:{list,doctor,dry-run,record,play}`, and the
> `01-hello` smoke test are implemented and verified end-to-end against the
> ACP **stub** backend (no API keys). Spiking the pipeline corrected several
> assumptions in this doc — each is flagged inline with **[as-built]**:
> - Readiness is signalled by the **input prompt rendered in the pane**, not a
>   startup "lock" file (the persist lock is a transient mutex). (§5.4)
> - Sessions now start fresh by default, so no flag is needed to avoid the
>   resume picker (it appears only with `--select-resume`). Existing launch
>   args may still pass **`--new`** — it is a harmless deprecated no-op. (§6)
> - `BRAINYARD_SESSION_ID` is now honored by the TUI startup (the env hook the
>   DSL assumed). (§4, §6)
> - The post-hoc **trim step was removed** — asciinema 3.x's
>   `--idle-time-limit` embeds the limit in the cast header and the **player**
>   caps idle gaps at playback (raw timestamps keep the real pause, e.g. a 60 s
>   LLM turn). Re-serializing to trim risked invalid JSON for no benefit. (§5.7)
> - asciinema is **3.x** (Rust rewrite), not 2.4; casts are pinned to
>   `asciicast-v2` for player/diff compatibility. `--headless` (native) and
>   `--capture-env` simplify the verifier and version metadata. (§9, §8.1, §11)
> - `:narrate` types-then-**backspaces** (inline mode has no readline editing,
>   so `C-a`/`C-k` render literally). (§4.1)
> - The vendored player exposes `AsciinemaPlayer.create()` (JS API), **not** a
>   `<asciinema-player>` custom element; `bb tutorial:embed` emits `create()`
>   calls + a shared init script. (§8a) Render verified in a real headless
>   browser (agent-browser): the player loads the cast and renders its terminal.
> - The verifier launches `by` in **tmux without asciinema** and captures the
>   pre-teardown frame; CI is **two-tier** (pure-bb PR gate + heavy local/
>   nightly frame check). (§9)

This document specifies how Brainyard records, ships, and embeds
asciinema-based tutorials for the `by` CLI (`projects/agent-tui-app`).
It covers the directory layout, scenario DSL, recording driver, the
`bb tutorial:*` task surface, and the publication / CI gates.

The shipped artifact is the GraalVM-native `by` binary (see
[docs/build-and-deploy.md](build-and-deploy.md)). All tutorials target
that binary — never a dev-only REPL path — so the recording matches
what an end user installs.

---

## 1. Goals & non-goals

**Goals**

1. **Repeatable recordings.** Re-running a scenario yields a `.cast`
   that differs only in timing jitter; the typed commands, prompts,
   and assistant responses are deterministic enough to diff.
2. **Hand-free authoring.** Tutorial authors write an EDN *scenario*
   (chapters, prompts, narration); a driver script feeds those into a
   live `by` session while `asciinema rec` captures the terminal.
3. **One source, multiple surfaces.** The same `.cast` is playable
   locally (`asciinema play`), embeddable on the docs site
   (asciinema-player), and uploadable to asciinema.org for shareable
   links.
4. **Drift detection.** When the `by` UI or prompts shift, a CI job
   re-runs a representative scenario and fails on snapshot
   divergence, so tutorials don't silently rot.

**Non-goals**

- Recording GUI / browser sessions (use a separate tool).
- General-purpose macro replay against arbitrary TUIs — the harness
  is brainyard-specific and reuses `bb tui:drive` semantics.
- Real-time streaming. Tutorials are pre-recorded and replayed.

---

## 2. Why asciinema (and what we reuse)

asciinema's `.cast` format is a JSONL stream of `(timestamp,
event-type, payload)` events; payloads are raw ANSI bytes. That means
anything the user's terminal can render — colors, alt-screen, cursor
moves, `by`'s split-screen TUI — replays faithfully without
re-running the agent or burning LLM tokens at view time. Casts are
text-diffable, git-friendly, and editable (we can trim timestamps,
splice chapters, or speed up dead air).

We reuse three existing brainyard primitives:

- **`bb tui:drive`** — sends a prompt into a `by` instance running in
  tmux via `tmux send-keys`, then polls a per-session `turn.complete`
  stamp until the agent settles. Exact mechanism documented in
  `docs/live-debugging.md §9 #1`. This is the input-injection path.
- **`bb tui:attach-ask`** — synchronous nREPL one-shot. Used for
  *headless verification* in CI (no tmux, no asciinema), not for the
  recording itself.
- **per-session stamps** under `~/.brainyard/sessions/<id>/` — the
  driver waits on `turn.complete` rather than guessing sleep
  durations, so timing is bounded by the agent, not by a magic
  `sleep 5`.

asciinema runs *outside* tmux, attached to the pane's PTY. The
driver sends keystrokes *into* tmux. The two are decoupled: asciinema
sees whatever the pane shows.

---

## 3. Directory layout

```
docs/
├── asciinema-deploy.md          ← this file (design)
└── tutorials/
    ├── index.md                 ← rendered catalog with embedded players
    ├── scenarios/               ← EDN scenario files (source of truth)
    │   ├── 01-hello.edn
    │   ├── 02-tools-and-skills.edn
    │   ├── 03-coact-code-blocks.edn
    │   ├── 04-research-agent.edn
    │   └── 05-memory-and-recall.edn
    ├── casts/                   ← recorded artifacts (committed)
    │   ├── 01-hello.cast
    │   ├── 02-tools-and-skills.cast
    │   └── …
    ├── golden/                  ← redacted reference frames for drift CI
    │   ├── 01-hello.frame.txt
    │   └── …
    └── assets/                  ← embed JS/CSS for static-site player
        ├── asciinema-player.min.js
        └── asciinema-player.min.css

scripts/
└── asciinema/
    ├── record-scenario.sh       ← orchestrator (tmux + asciinema + driver)
    ├── drive-scenario.bb        ← Babashka scenario runner (reads EDN)
    ├── verify-scenario.bb       ← headless replay → frame diff
    └── lib.sh                   ← shared helpers (tmux session names, cleanup)
```

Scenarios are the *source*; `.cast` files are the *build product*.
Casts are still committed (they're small, text, and we want PR
reviewers to play them in their browser without running the
toolchain), but they're regenerable from the scenario at any time.

---

## 4. Scenario DSL

A scenario is an EDN map. The format is intentionally narrow — every
field maps to a primitive in `bb tui:drive` or to an asciinema
control. New verbs require code changes in `drive-scenario.bb`, not
magic strings.

```edn
;; docs/tutorials/scenarios/02-tools-and-skills.edn
{:id          "02-tools-and-skills"
 :title       "Using built-in tools & skills from `by`"
 :description "Shows how the CoAct agent dispatches a tool call and
               how to register a custom skill at runtime."

 ;; Recording-time controls (do not affect the agent itself).
 :terminal    {:cols 100 :rows 30 :title "brainyard tutorial — tools"}
 :idle-time-limit 2.0      ;; asciinema --idle-time-limit (compress pauses)

 ;; `by` launch arguments. Matches `bb tui` flags exactly.
 :launch
 {:binary  "by"           ;; or "bb" "tui" — see §6 launch modes
  :args    ["-a" "coact-agent" "-p" "claude-code" "-m" "haiku" "-i"]
  :env     {"BRAINYARD_TUTORIAL_MODE" "1"
            ;; Stable session id keeps `~/.brainyard/sessions/<id>` paths
            ;; deterministic, so the driver can find the stamp file.
            "BRAINYARD_SESSION_ID" "tutorial-02"}}

 ;; Optional pre-roll narration before the first prompt — typed into a
 ;; comment line so it appears on screen but is harmless to the agent.
 :preamble
 [{:type :narrate :text "# Demo: tools & skills" :pause-ms 1500}
  {:type :narrate :text "# The CoAct agent picks tools or code per turn." :pause-ms 2000}]

 ;; Chapters — recorded in order, each producing a chapter marker in
 ;; the .cast metadata so asciinema-player can show a TOC.
 :chapters
 [{:id    :ch1
   :label "Ask a simple question"
   :prompt "What does the `defcommand` macro do?"
   :expect {:contains   ["registry" "command"]
            :max-tokens 800}
   :settle {:timeout-secs 60}}

  {:id    :ch2
   :label "Trigger the explore-agent tool"
   :prompt "Find every file under components/agent that mentions `defcommand`."
   :expect {:tool-called "explore-agent"
            :max-tokens  1200}
   :settle {:timeout-secs 90}}

  {:id    :ch3
   :label "Define a skill on the fly"
   :prompt "Create a skill called `greet` that prints \"hello, world\"."
   :expect {:contains ["defskill" "greet"]}
   :settle {:timeout-secs 60}}]

 ;; Optional teardown — usually just a clean `/quit`.
 :postamble
 [{:type :keys :send "/quit" :pause-ms 500}]}
```

### 4.1 Step types

| Type        | Fields                                  | Effect                                                                              |
|-------------|-----------------------------------------|-------------------------------------------------------------------------------------|
| `:narrate`  | `:text`, `:pause-ms`                    | Types a comment-style line (`# …`) and waits.                                       |
| `:keys`     | `:send`, `:pause-ms`                    | Raw `tmux send-keys` — useful for slash commands, arrow keys, Ctrl-sequences.       |
| `:prompt`   | `:prompt`, `:expect`, `:settle`         | Implicit step type for chapter entries; uses `bb tui:drive` and waits for settle.   |
| `:sleep`    | `:ms`                                   | Hard sleep, only when narration timing genuinely needs it.                          |
| `:annotate` | `:text`                                 | Writes a marker into `.cast` metadata (no on-screen effect) for chapter navigation. |

### 4.2 Expectations

`:expect` is checked by the *verifier* (`verify-scenario.bb`), not
during recording. During recording, missing expectations only log a
warning. In CI, missing expectations fail the build. This split keeps
authoring fast while still gating drift.

Supported predicates: `:contains` (vector of substrings, all must
appear in answer), `:matches` (regex), `:tool-called` (the agent
actually invoked the named tool, checked via session log), and
`:max-tokens` (soft budget so a regression that triples answer
length is noticed).

---

## 5. Recording orchestrator

`scripts/asciinema/record-scenario.sh` is a thin shell wrapper. The
real logic lives in `drive-scenario.bb`. Split this way because
asciinema needs to be `exec`-ed as the controlling process of the
tmux pane — easier from shell — while scenario parsing and `bb
tui:drive` invocation belong in Clojure.

Sequence:

1. **Resolve scenario.** `record-scenario.sh <id>` looks up
   `docs/tutorials/scenarios/<id>.edn` and asserts it parses.
2. **Allocate tmux session.** Name is `by-tut-<id>-<short-sha>`.
   Width/height come from `:terminal`. A trap ensures the session is
   killed on exit.
3. **Launch `by` inside the tmux pane.** The pane's command is
   `asciinema rec --idle-time-limit … --command "<binary> <args…>"
   --overwrite docs/tutorials/casts/<id>.cast`. asciinema owns the
   PTY; `by` runs as its child. `--overwrite` keeps the artifact
   pinned to the scenario id.
4. **Wait for `by` ready.** **[as-built]** Poll the pane
   (`tmux capture-pane`) until the input prompt (`/help for commands`) is
   rendered, gated on the session's `meta.edn` existing. This is the true
   "ready for keystrokes" signal, identical in inline and fullscreen modes.
   (The original design polled a startup `lock` file — that file is a
   transient mutex, never present in inline mode, not a readiness beacon.)
   Abort after `:startup-timeout-secs` (default 20; 90 for JVM `bb tui*`
   launch modes — clojure cold start).
5. **Run preamble → chapters → postamble.** Each chapter calls into
   the existing `bb tui:drive` task with `-s <tmux>` and `-S <sid>`,
   so the driver waits on the same `turn.complete` stamp the live
   debugging tools already use. No `sleep`-based heuristics. `:narrate`
   steps type onto the screen then **backspace** the text out (inline mode
   has no readline editing); the postamble types `/quit` then submits with
   `C-m`.
6. **Stop asciinema cleanly.** The postamble `/quit` + `C-m` exits `by`,
   which ends asciinema's `--command` child so the cast flushes naturally;
   a `C-d` fallback fires only if the pane is still alive after a grace
   period.
7. **Validate.** **[as-built]** Confirm the cast's JSON header carries a
   `version` field and the file is non-empty. *No post-hoc trim* — asciinema
   3.x embeds `--idle-time-limit` in the header and the **player** caps idle
   gaps at playback (raw timestamps keep the real pause — a live LLM turn can
   be a 60 s gap that plays back in ~2.5 s). Re-serializing to trim risks
   invalid JSON for no benefit. The cast is kept exactly as asciinema wrote it.

A `--dry-run` mode short-circuits step 3, prints the planned
keystrokes and timing, and skips asciinema. Useful when iterating on
a scenario without burning LLM calls.

---

## 6. Launch modes

Brainyard has three CLI surfaces; the scenario `:launch` map picks
one:

| `:binary`         | What runs                                                            | When to use                                                      |
|-------------------|----------------------------------------------------------------------|------------------------------------------------------------------|
| `"by"`            | Native binary on `$PATH` (post-`bb install:ata`).                    | **Default for tutorials** — matches the shipped artifact.        |
| `"bb tui"`        | Babashka task → JVM REPL TUI.                                         | Demos of dev-only features (live REPL, nREPL attach) before they land in the native build. |
| `"bb tui:acp"`    | ACP-driven TUI with the stub agent.                                  | Demos of the ACP protocol; never needs network or API keys.      |

The orchestrator normalizes these — for `bb` modes it shells through
`bb`; for `by` it `exec`s the binary — so the scenario stays
identical otherwise.

**[as-built]** Sessions start fresh by default now, so launch args no longer
need a flag to avoid the resume picker (it only shows with `--select-resume`),
and the TUI lands in a fresh agent named by `BRAINYARD_SESSION_ID`. Existing
scenarios still carry **`--new`** in `:launch :args` — it is a harmless
deprecated no-op and can stay or be dropped. JVM modes
(`bb tui*`) also want a higher `:startup-timeout-secs` (~90) for clojure cold
start; the native `by` binary starts in ~1.5 s.

---

## 7. Babashka task surface

Add to `bb.edn`:

```
bb tutorial:list                # show scenarios with title + last-recorded date
bb tutorial:record <id>         # full record loop, writes docs/tutorials/casts/<id>.cast
bb tutorial:record-all          # iterate :record over every scenario (used by CI nightly)
bb tutorial:play <id>           # asciinema play docs/tutorials/casts/<id>.cast
bb tutorial:verify <id>         # headless replay → check :expect → diff golden frame
bb tutorial:upload <id>         # asciinema upload (asciinema.org); echoes the share URL
bb tutorial:embed <id>          # regenerate docs/tutorials/index.md with the cast embed
bb tutorial:dry-run <id>        # parse + plan (no recording, no agent calls)
```

Each task is a thin wrapper that calls `drive-scenario.bb` with a
subcommand. Authoring loop is `dry-run` → `record` → `play` →
commit. CI runs `verify` only.

---

## 8. Publication & embedding

Three distribution paths, ordered by recommendation:

**(a) In-repo, in-docs (default).** The `.cast` lives under
`docs/tutorials/casts/`. `docs/tutorials/index.md` embeds it with
`asciinema-player` (vendored under `docs/tutorials/assets/`) so the
mkdocs / static-site build serves a self-contained player without
external dependencies. Offline-friendly, version-locked to the repo.

**[as-built]** The vendored player (asciinema-player **3.15.1**) exposes the
JS API `AsciinemaPlayer.create(src, el, opts)`, not a `<asciinema-player>`
custom element — and ships its CSS as `asciinema-player.css` (not `.min.css`).
`bb tutorial:embed` therefore emits, per recorded cast, a `<div class="ascii-cast"
data-cast=… data-cols=… data-rows=… data-idle=…>` plus one shared init script
that calls `create()` for every such div, and a single `<link>`/`<script>` to
the vendored assets. The `by` version is read from the cast header's `env`
map and printed under each player.

```html
<link rel="stylesheet" href="assets/asciinema-player.css">

<div class="ascii-cast" data-cast="casts/02-tools-and-skills.cast"
     data-cols="100" data-rows="30" data-idle="2"></div>

<script src="assets/asciinema-player.min.js"></script>
<script>
  document.querySelectorAll('.ascii-cast').forEach(function (el) {
    AsciinemaPlayer.create(el.dataset.cast, el, {
      cols: +el.dataset.cols, rows: +el.dataset.rows,
      idleTimeLimit: +el.dataset.idle, fit: 'width',
      poster: 'npt:0:01', theme: 'asciinema'
    });
  });
</script>
```

> Note: GitHub's Markdown rendering strips `<script>`, so the player runs on
> the **mkdocs / static-site build**, not on github.com's file view. Reviewers
> play casts from the site preview (or locally via `bb tutorial:play <id>`).

**(b) asciinema.org.** `bb tutorial:upload <id>` posts the cast
(default visibility **unlisted**) and caches the returned URL in
`docs/tutorials/casts/<id>.url`. **[as-built]** Rather than *replacing* the
offline player with a CDN `<iframe>` (which would break the offline-default
goal), `bb tutorial:embed` keeps the vendored player and adds an opt-in
"▶ Play on asciinema.org" **link** under it when the `.url` is present. Good
for sharing *outside* the docs (Slack, X, etc.) without a CDN dependency in
the page itself.

**(c) Self-hosted player on a static asset bucket.** Only worth it
if the docs site can't ship the player JS. Same `.cast` artifact —
just hosted at a different URL.

We default to (a) and treat (b) as an opt-in nice-to-have.

### 8.1 Versioning

Each `.cast` carries the `by --version` string (`git describe
--tags --always --dirty`) in its `env` header. **[as-built]** The recorder
exports `BRAINYARD_VERSION` and passes `--capture-env
BRAINYARD_VERSION,BRAINYARD_SESSION_ID` so asciinema writes them into the
header `env` map — no hand-editing of the JSON. `bb tutorial:embed`
surfaces this version next to each player so readers know which
build the demo targets. The version is *not* in the filename, so
re-recording doesn't churn `docs/tutorials/index.md`.

---

## 9. CI / drift detection

`bb tutorial:verify <id>` runs the scenario and diffs a redacted frame.
**[as-built]** It launches `by` in a **detached tmux pane** (no asciinema;
tmux supplies the PTY the TUI needs to render), drives preamble + chapters
*with `--no-postamble`* so the result frame is captured before teardown, then
`tmux capture-pane -p` (already ANSI-stripped) → `redact_frame` (masks session
ids, costs, token/call counts, `/Users/…` paths, ports, trailing space) →
compared against `docs/tutorials/golden/<id>.frame.txt`. First run (or
`--update-golden`) writes the golden. It also asserts the scenario's `:expect`
predicates against the captured frame via `drive-scenario.bb check`.

Exit codes: `0` match + expectations pass, `2` golden drift (prints the diff),
`3` `:expect` failure, `1` setup error. `:contains` / `:matches` are checked
against the frame; `:tool-called` / `:max-tokens` need the session log and are
skipped with a note (a follow-up can read `~/.brainyard/sessions/<sid>/`).

**[as-built]** CI is **two-tier**, because the frame check needs the full JVM
stack (and the agent-tui-app project pulls Datomic, which needs creds):

- **PR gate (`.github/workflows/tutorials.yml`, pure babashka, no creds).**
  Validates every scenario parses and that `docs/tutorials/index.md` is
  committed up to date (`drive-scenario.bb embed --check`). Fast, always runs.
- **Frame drift (heavy, local / nightly / self-hosted).**
  `bb tutorial:verify <id>` — run locally before merging UI/agent changes, or
  wire to a self-hosted runner that has JDK + clojure + tmux + Datomic creds.
  Intentionally **not** in the credential-free PR gate. Verifications use the
  stub ACP backend (or `haiku`) to stay cost-bounded.

We do **not** gate PRs on re-recorded `.cast` byte equality —
timing jitter alone would make that brittle. The gate is *frame
diff*, not *cast diff*.

---

## 10. Worked example: scripting the orchestrator

For the design to be testable, here's the full intended flow for
`02-tools-and-skills`:

```bash
# 1. Author iterates without burning API calls
bb tutorial:dry-run 02-tools-and-skills
#   → prints: "would launch: by -a coact-agent -p claude-code -m haiku -i"
#             "would send 3 chapter prompts, expected runtime ~3m20s"

# 2. Live record
bb tutorial:record 02-tools-and-skills
#   → docs/tutorials/casts/02-tools-and-skills.cast (≈ 80 KB)

# 3. Local sanity check
bb tutorial:play 02-tools-and-skills

# 4. (optional) share externally
bb tutorial:upload 02-tools-and-skills
#   → https://asciinema.org/a/XXXXXX (URL cached in casts/02-….url)

# 5. Update the docs catalog
bb tutorial:embed 02-tools-and-skills
#   → rewrites the player block in docs/tutorials/index.md

# 6. Commit
git add docs/tutorials/{scenarios,casts,golden}/02-* docs/tutorials/index.md
git commit -m "docs(tutorials): add tools & skills walkthrough"
```

CI on that PR runs `bb tutorial:verify 02-tools-and-skills` against
the rebuilt `by`. The reviewer plays the cast in-browser from the PR
preview and approves on the content, not on byte equality.

---

## 11. Prerequisites & install

Per-developer prerequisites (added to `docs/build-and-deploy.md`'s
*Prerequisites* section once this lands):

- `asciinema >= 3.0` (`brew install asciinema`) — recording, playback,
  upload. **[as-built]** 3.x is a Rust rewrite: default output format is
  `asciicast-v3`, so we pin `--output-format asciicast-v2` for max
  asciinema-player / `cat` / diff compatibility. 3.x also adds native
  `--headless` (replaces the `script -q` PTY hack in §9) and `--capture-env`
  (used for §8.1 version metadata). Note `asciinema cat` outputs a *cast*,
  not rendered text, in 3.x.
- `tmux >= 3.2` — already required for the TUI side-channel.
- `bb` — already required.
- `script` (BSD/util-linux) — already present on macOS/Linux; used
  by `verify` for headless PTY allocation.
- For embedded playback in the docs site: `asciinema-player` JS/CSS
  vendored under `docs/tutorials/assets/` (committed; no runtime CDN
  fetch).

A `bb tutorial:doctor` task probes each of these and reports the
first missing piece, mirroring `bb check:ata`'s style.

---

## 12. Open questions

These are intentionally unresolved — each is a small follow-up once
the skeleton lands:

1. **Deterministic LLM output.** `haiku` is cheap but still
   non-deterministic. Options: (a) record once and replay the cast
   forever (current plan); (b) stub the LLM with a fixture provider
   for `:verify` runs. (b) is more rigorous but adds a new component.
   Lean toward (a) for tutorials and (b) only for CI verification.
2. **Multi-pane recordings.** `by` Mode B has a side tmux pane.
   asciinema records a single PTY, so the side pane needs a separate
   cast or a tmux `capture-pane` overlay. Defer until we ship a
   tutorial that actually needs Mode B.
3. **Chapter markers in player.** Upstream `asciinema-player` supports
   chapter markers via the cast's `idle_time_limit` / custom JSON
   events. We'd encode `:annotate` steps as `m` (marker) events. Spec
   exists; implementation deferred.
4. **Localization.** Korean narration overlays would diverge from
   the English `.cast`. Probably ship two scenarios per language
   rather than fork mid-cast.

---

## 13. Implementation checklist

Suggested PR sequence (each PR independently mergeable):

1. **Skeleton.** ✅ **[done]** Dirs, `bb tutorial:{list,dry-run,doctor}`
   wired, scenario parser + validation in `drive-scenario.bb`, plus the
   `BRAINYARD_SESSION_ID` env hook in the TUI startup.
2. **Recorder.** ✅ **[done]** `record-scenario.sh` + `lib.sh` + asciinema
   integration + `tutorial:record` / `tutorial:play` tasks. `01-hello.edn`
   (ACP-stub smoke test) records and validates end-to-end.
3. **Player + docs page.** ✅ **[done]** Vendored asciinema-player 3.15.1
   (`docs/tutorials/assets/`), `bb tutorial:embed` regenerates
   `docs/tutorials/index.md` with one player per recorded cast + the `by`
   version from each cast header. Path wiring verified via a static server
   (all asset/cast URLs 200); interactive JS render is a manual browser check.
4. **Verifier + golden frames.** ✅ **[done]** `verify-scenario.sh` +
   `drive-scenario.bb check` + `bb tutorial:verify`; redacted golden-frame
   diff (exit 2 on drift, 3 on `:expect` fail) and the pure-babashka PR gate
   `.github/workflows/tutorials.yml`. `01-hello.frame.txt` golden committed;
   generate/match/drift/expect-fail paths all verified. The heavy frame check
   stays local/nightly (needs the JVM+tmux+Datomic stack).
5. **Upload + authoring docs.** ✅ **[done]** `upload-scenario.sh` +
   `bb tutorial:upload` (visibility-aware, caches `casts/<id>.url`); `embed`
   surfaces an opt-in "▶ Play on asciinema.org" link under the offline player.
   `docs/tutorials/AUTHORING.md` written. (No real upload performed — that
   publishes publicly; URL-parse + dry-run + embed link verified locally.)

---

## 14. References

- asciinema CLI quick-start — <https://docs.asciinema.org/manual/cli/quick-start/>
- asciinema cast file format v2 — <https://docs.asciinema.org/manual/asciicast/v2/>
- asciinema-player — <https://github.com/asciinema/asciinema-player>
- `bb tui:drive` / `bb tui:attach-ask` — `bb.edn`, design context in
  [docs/live-debugging.md §9 #1](live-debugging.md).
- `by` binary build & versioning — [docs/build-and-deploy.md](build-and-deploy.md).
- TUI architecture & session paths — [docs/tui/architecture.md](tui/architecture.md).
