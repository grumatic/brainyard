# Writing a `by` tutorial

A short how-to for authoring asciinema tutorials. For the full design and the
`[as-built]` notes, see [../asciinema-deploy.md](../asciinema-deploy.md).

A tutorial is a **scenario** (EDN, the source of truth) that a driver feeds
into a live `by` session while asciinema records the terminal. You write the
scenario; the `.cast`, the golden frame, and the docs page are build products.

## TL;DR authoring loop

```bash
bb tutorial:dry-run <id>     # parse + plan, no recording, no agent calls
bb tutorial:record  <id>     # record -> docs/tutorials/casts/<id>.cast
bb tutorial:play    <id>     # watch it back locally
bb tutorial:verify  <id>     # first run writes the golden frame; later runs diff it
bb tutorial:embed            # regenerate docs/tutorials/index.md (commit it!)
bb tutorial:output           # self-contained output/index.html for local view (opens in a browser, no server; gitignored)
bb tutorial:upload  <id>     # (optional) publish + cache a share link
bb tutorial:doctor           # check prerequisites (asciinema, tmux, bb, …)
```

Commit `scenarios/<id>.edn`, `casts/<id>.cast`, `golden/<id>.frame.txt`, and
the regenerated `index.md` together.

## Scenario skeleton

```edn
{:id          "02-tools-and-skills"        ; must match the filename
 :title       "Using built-in tools & skills"
 :description "One or two sentences shown under the player."

 :terminal    {:cols 100 :rows 32 :title "brainyard tutorial — tools"}
 :idle-time-limit 2.5                       ; player caps idle gaps at this
 :startup-timeout-secs 120                  ; JVM bb tui* cold start; native `by` ~2s

 :launch
 {:binary "bb tui"                          ; see Launch modes below
  ;; NO -i  -> fullscreen, so the live iteration/think/tool blocks render.
  ;; --new  -> REQUIRED, skips the interactive resume picker.
  ;; opus via claude-code is subscription-based (no per-call cost) — use it.
  :args   ["-a" "coact-agent" "-p" "claude-code" "-m" "opus" "--new"]
  :env    {"BRAINYARD_SESSION_ID" "tutorial-02"}}  ; deterministic session dir

 :preamble  [{:type :narrate :text "# Demo: tools & skills" :pause-ms 1500}]
 :chapters
 [{:id :ch1 :label "Ask a question"
   :prompt "What does the `defcommand` macro do?"
   :expect {:contains ["registry" "command"]}
   :settle {:timeout-secs 180}}]           ; opus is slower per call — be generous
 :postamble [{:type :keys :send "/quit" :pause-ms 400}
             {:type :keys :send "C-m"   :pause-ms 800}]}
```

### Required fields
`:id`, `:title`, `:launch` (with a string `:binary`). `:launch :env
BRAINYARD_SESSION_ID` is required by `record`/`verify` so the session paths are
deterministic. Each chapter needs `:id` (keyword) and `:prompt` (string).

## Step types

| Type        | Fields                 | Effect                                                        |
|-------------|------------------------|--------------------------------------------------------------|
| `:narrate`  | `:text` `:pause-ms`    | Types text on screen, holds, then **backspaces it out** (not submitted). |
| `:keys`     | `:send` `:pause-ms`    | Raw `tmux send-keys` (key names like `C-m`, or literal text like `/quit`). |
| `:sleep`    | `:ms`                  | Hard pause (use sparingly).                                   |
| `:prompt`   | chapter `:prompt`      | Implicit per chapter — typed + submitted via `bb tui:drive`, waits on `turn.complete`. |
| `:annotate` | `:text`                | Metadata marker, no on-screen effect.                        |

> To submit a slash command, send the text then a separate `C-m` (raw CR);
> plain `Enter` is swallowed by the autocomplete popup. The `:narrate`/`:keys`
> drivers don't rely on readline editing (`C-a`/`C-k`), so they work in both
> fullscreen and inline (`-i`) modes.

## `:expect` predicates

Checked by `bb tutorial:verify` against the rendered frame:

- `:contains` — vector of substrings, all must appear.
- `:matches`  — a regex string.
- `:tool-called` / `:max-tokens` — need the session log; currently noted +
  skipped by the frame checker.

## Launch modes (`:binary`)

| `:binary`      | Runs                              | Use for                                            |
|----------------|-----------------------------------|----------------------------------------------------|
| `"by"`         | Native binary on `$PATH`          | Matches the shipped artifact (~2s start). NOTE: a stale `by` may predate the `BRAINYARD_SESSION_ID` env hook — rebuild with `bb install:ata`. |
| `"bb tui"`     | JVM REPL TUI                      | **Default for showcase tutorials** — runs from source (env hook present). Slow cold start (bump `:startup-timeout-secs` ~120). |
| `"bb tui:acp"` | ACP stub TUI                      | Hermetic smoke test / CI golden anchor (01-hello) — no network or API keys. |

Always include `--new` in `:args` so the TUI skips the interactive resume
picker and lands in a fresh session named by `BRAINYARD_SESSION_ID`.

## Isolated workspace (`:workspace`) — run `by` in a throwaway /tmp git tree

For tutorials where the agent **reads/writes/explores a project** (so its
tool and `.brainyard/` writes must not touch this repo), add a `:workspace`:

```edn
 :workspace
 {:git-init true                       ; default true — needed so project-dir = this dir
  :seed [{:path "src/cli.py" :content "..."}
         {:path "README.md"  :content "..."}]}
```

`record-scenario.sh` materializes it into a fresh temp dir (seed files →
`git init` → initial commit) and launches `by` there via `tmux new-session
-c`, so **project-dir resolves to the workspace** and every read/write/grep/
bash/agent-artifact lands in /tmp, not the repo. Pairs naturally with
`:binary "by"` (the shipped native binary). Test the setup standalone with
`bb scripts/asciinema/drive-scenario.bb workspace <scenario.edn>` (prints the
dir it created).

## Multi-turn — one chapter per turn

Each `:chapters` entry is **one conversation turn**, driven in order; context
(previous turns + recalled memory) carries across them. So a multi-turn
walkthrough is just several chapters. `02–06` use one chapter; `07-multi-turn-
native` uses three, escalating the capability tiers (basic tools → functional
agent → coordinating a second agent) in one session.

> **Coordination-turn caveat:** a turn that dispatches a sub-agent
> (a direct `(explore-agent …)` / `(plan-agent …)` call → explore/plan/…)
> makes that sub-agent bump the *same*
> session's `turn.complete` stamp when it finishes its nested ask — which can
> release `bb tui:drive` before the outer turn ends. The driver guards this by
> additionally waiting for the pane status line to read `idle` (not `running`)
> after each chapter, bounded by the chapter `:settle`. Give coordination turns
> a generous settle (300–360s).

## Fullscreen vs inline — show the agent *working*

**Drop `-i` (fullscreen) for showcase tutorials.** The live per-iteration
blocks — `Iter[N]`, streaming Thinking, `→ tool`, `Code[N]/Output[N]` — are a
**fullscreen-only** feature (the sticky-bottom live-block layout). With `-i`
(inline) they're bypassed entirely: the cast jumps straight from prompt to the
final answer, hiding the agent reasoning and acting.

- **Showcase tutorials** → omit `-i` → fullscreen → the agent is shown working.
- **The CI/golden stub (01-hello)** → keep `-i` (inline) → linear scrollback
  output, so the redacted golden frame stays simple and `verify` is stable.

Diagnostic on a finished cast: `grep -c '1049h' casts/<id>.cast` (alt-screen
enter) — `1` = fullscreen ✓, `0` = inline. Fullscreen casts are ~10–30× larger
(hundreds of events + spinner frames).

Caveat (handled automatically): a fullscreen cast ends on the **restored blank
terminal** after `/quit`, so `bb tutorial:embed` sets each player's `poster` to
~80% of the cast duration (not the final frame).

## Model & determinism tips

- **Use `-m opus` with `-p claude-code`.** claude-code is **subscription-based
  — no per-call cost** — so opus (best agent behaviour) is the right default;
  the haiku/sonnet penny-pinching only matters for the anthropic *API* provider
  or the credential-free ACP stub. opus is slower per call → bump `:settle`
  (single code-eval ~180s; plan/todo full-flow machinery ~420s).
- **One focused chapter beats several** — each extra chapter multiplies LLM
  flake risk; narrow, concrete prompts complete cleanly.
- **Agents that auto-dispatch a downstream agent** (e.g. todo→exec) won't
  settle — add "author your dossier, then STOP; do not dispatch …" to the
  prompt.
- **No golden frame for real-LLM scenarios** — output is non-deterministic.
  Golden/CI drift-checking (`bb tutorial:verify`) is for the deterministic
  `01-hello` stub only.
- `:idle-time-limit` is embedded in the cast; the **player** caps idle gaps at
  playback (raw timestamps keep the real pause). No separate trim step.
- The golden frame is **redacted** (session ids, costs, token counts, paths,
  ports) so `verify` catches semantic regressions, not cosmetic noise. If a UI
  change is intentional, refresh it: `bb tutorial:verify <id> --update-golden`.

## Publishing (optional)

`bb tutorial:upload <id>` posts the cast to an asciinema server (default
visibility **unlisted**) and caches the URL in `casts/<id>.url`. The next
`bb tutorial:embed` adds a "▶ Play on asciinema.org" link under the offline
player. Uploading **publishes publicly** — review with `bb tutorial:play`
first. The default distribution path is the in-repo offline player; the share
link is an opt-in extra.
