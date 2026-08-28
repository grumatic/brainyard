# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this repo is

The public source for **Brainyard** — an agent-driven terminal UI for working with LLMs from the command line. The shipping binary is `by`. The codebase is a [Polylith](https://polylith.gitbook.io/) workspace: a curated subset of bricks (`bases/`, `components/`) composed into the `agent-tui-app` project, built to a GraalVM native binary and a JVM uberjar.

This repo was seeded from the upstream `v0.2.0` snapshot and is now the source of truth — develop here directly. (Earlier `v0.1.x` releases were published from a thin sync-wrapper model that has since been retired.)

## Required environment

- GraalVM 25.0.3+ on `PATH` (or via `.sdkmanrc` + SDKMAN). The `bb native:ata` task probes `PATH`, `JAVA_HOME`, and `/Library/Java/JavaVirtualMachines/`.
- `bb` (Babashka) and the `clojure` CLI.
- `gh` CLI for release publishing.
- **Optional, runtime-only:** `ttyd` for `by --web` (browser-shared sessions);
  `tmux` additionally for `by --web-tmux`. Probed at runtime — neither is a
  build dependency. See `components/web-share` and `docs/web-sharing.md`.
  `sandbox-exec` (ships with macOS) backs `by --sandbox` — probed at runtime,
  macOS-only, not a build dependency. See `components/os-sandbox` and
  `docs/sandboxing.md`.

## Runtime configuration (env vars)

`by` reads provider credentials and a few control flags from the environment. A
real shell env var always wins; otherwise the binary loads the nearest `.env`
(walking up from cwd, then `~/.brainyard/.env`) — see `.env.example` for the
full annotated template and `projects/agent-tui-app/src/.../dotenv.clj` /
`scripts/by-wrapper.sh` for the loader.

- **`BY_USER_ID`** — user identity stamped onto sessions and memory; **memory**
  (L1/L2/L3) is partitioned by it under `~/.brainyard/memory/<user-id>.db`.
  Resolved once at startup: `--user-id`/`-u` flag >
  `BY_USER_ID` > the `user.name` system property (OS login) > `"by-user"`.
  Note: persisted TUI **sessions** are **project-scoped**, not user-scoped — they
  live under `<project>/.brainyard/sessions/<id>/` (a session belongs to one repo),
  so `by sessions list` / `--resume` only surface the current project's sessions.
  The app installs that root at startup via `agent/sessions-root` → `persist/set-root!`.
- **`BY_WORKING_DIR`** — effective working directory for tools/agents (no real
  JVM chdir; threaded through config). Resolved once at startup: `--working-dir`/`-C`
  flag > `BY_WORKING_DIR` > the process cwd (`user.dir`). The flag is **strict** (a
  non-directory path exits 1); a bad `BY_WORKING_DIR` env value silently falls back
  to cwd. `project-dir` (where `.brainyard/` artifacts land) re-derives from it via
  git-root walk, unless **`BY_PROJECT_DIR`** explicitly overrides the project root.
  The `--web`/`--sandbox` launchers forward `-C` into the re-exec'd child.
- **`AWS_PROFILE`** — Bedrock credential profile (`AWS_DEFAULT_PROFILE` is **not** honored).
- **`BY_JAR=1`** — run the uberjar instead of the native binary (reflection-config debugging).
- **`BY_ENV_FILE`** / **`BY_NO_DOTENV=1`** — force a specific `.env`, or skip `.env` discovery.
- **`BY_MEMORY_SELF`** — override for how the interactive TUI re-execs itself to
  run the **detached session-end memory consolidation**. On a graph-mode root
  session close the TUI hands the session's L2→graph→L3 tail to a detached
  `by memory reduce -u <uid> -s <sid>` child (surviving `/quit` via a
  `trap '' HUP INT TERM` + `setsid`/`perl` new-session detach), so `/quit` never
  blocks on minutes of extraction + community summaries. The child is resolved to
  the **real** binary (native-image self path, else `which by`) — deliberately
  **not** via `BY_WEB_SELF`, which is often a ttyd stand-in (`BY_WEB_SELF=cat`)
  and would silently misfire the reduce. Set `BY_MEMORY_SELF` (whitespace-split,
  e.g. a dev `clojure -M -m … run` command) to point that re-exec elsewhere for
  source/dev testing; parallel to `BY_WEB_SELF` / `BY_SANDBOX_SELF`. Unset ⇒ real
  binary. Falls back to a bounded in-process flush when the child can't be
  spawned. Impl: `spawn-detached-reduce!` / `reduce-self-argv` in the app `main`.
- **`BY_WEB`, `BY_WEB_*`** — web-sharing defaults (one per `--web*` flag; flag
  wins). The `--web` launcher sets **`BY_WEB_CHILD=1`** on the ttyd child as a
  re-entrancy guard so the relaunched TUI runs in-process instead of spawning
  another ttyd. See `docs/web-sharing.md`.
- **`BY_SANDBOX`, `BY_SANDBOX_*`** — seatbelt sandbox defaults (one per
  `--sandbox*` flag; flag wins). Default policy is **write-containment**: reads,
  network and subprocess exec are allowed, writes are confined to `~/.brainyard`,
  the cwd subtree, `$TMPDIR`/`/tmp`. The `--sandbox` launcher sets
  **`BY_SANDBOX_CHILD=1`** on the re-exec'd child as the re-entrancy guard.
  Mutually exclusive with `--web` in v1; macOS-only. See `docs/sandboxing.md`.
- **`BY_ENABLE_CATALOG_REFRESH`** — let the model catalog refresh itself from
  each configured provider's model-list endpoint (`:enable-catalog-refresh`,
  default **true**). The refresh carries **model ids only**; curation
  (`:curated-rank`, `:description`, `:region`) stays in the baked catalog,
  because a provider's list cannot tell you which of its models a chat client
  can drive — `/v1/models` also returns embeddings, TTS and image models, and
  OpenAI's `-pro` tier answers "not a chat model" on `/v1/chat/completions`.
  So a refreshed model becomes *usable and listed* but never enters the
  `/model` picker on its own. Staleness is per provider via
  `:catalog-refresh-ttl-hours` (default 24); local servers derive a much
  shorter TTL from it, since an Ollama roster changes whenever the user pulls
  a model. Cached under `~/.brainyard/catalog/<provider>.edn`; `by models
  --refresh` forces one and `by models --drift` shows what changed. Off ⇒ the
  shipped catalog is used verbatim and no provider is contacted.
- **`BY_ENABLE_GRAPH_MEMORY`** — opt into the **context-graph memory** overlay
  (`:enable-graph-memory`, default **false**): a typed entity/relationship graph
  + vector index layered over the L1/L2/L3 FTS store as extra recall signals.
  Off by default and non-regressing (empty graph ⇒ recall == pure FTS). Design:
  `docs/design/context-graph-memory-design.md`; impl in `components/memory`
  (CR-MEM-20..24). The remaining graph knobs only take effect when this is on:
  - **`BY_GRAPH_EMBED_MODEL`** — the semantic-similarity embedder
    (`:graph-embed-model`). Two forms: **`static`** = the self-contained,
    in-binary **Model2Vec** embedder (`potion-base-8M`, 256-dim, pure-JVM, no
    server) bundled by `bb model2vec:fetch`; or a **`provider/model`** LM string
    routed through clj-llm's OpenAI-compatible `/embeddings` (e.g.
    `ollama/nomic-embed-text` (768-dim, local), `openai/text-embedding-3-small`).
    **Defaults to `static`** (self-contained, no server) — so the `:vec`
    recall signal + semantic node-seed resolution work out of the box once
    `:enable-graph-memory` is on; set it **blank to disable** the vector signal
    (graph + relational recall still work). Note: Bedrock/Anthropic chat models
    can't embed — use `static`, Ollama, or OpenAI.
  - **`BY_GRAPH_EXTRACT_MODEL`** — chat LM (`provider/model`, e.g.
    `bedrock/amazon.nova-lite-v1:0`) that extracts entities/relationships from
    episodes and writes community summaries. **Unset ⇒ graph stays storage-only**
    (manual edge API; no self-population). The extractor asks for a fixed JSON
    schema: providers with native structured output (OpenAI/Google/Groq/… —
    `:supports-json-schema? true`) get API-level enforcement; providers without
    it (**Bedrock**, Anthropic, Ollama) have the schema appended to the system
    prompt instead (clj-llm `chat-completion` injects it), so `bedrock/*` models
    do extract. Watch `::extracted {:entities N :relations M}` in the app log to
    confirm a model is actually yielding entities (0/0 ⇒ the model is ignoring
    the JSON contract — pick a stronger extract model).
  - **`BY_GRAPH_EMBED_DIMS`** — `graph_vec` vector dimension (default 768). Must
    match the embed model's output; `static` auto-drives it to 256. Changing the
    embed model fingerprint-mismatches the index, which **pauses** vector recall
    (a startup banner + `memory$status` flag it) until `memory$reembed` rebuilds.
  - **`BY_SQLITE_VEC_PATH`** / **`BY_MODEL2VEC_PATH`** — override the locations of
    the bundled `sqlite-vec` extension / Model2Vec model (else the native-image
    resources fetched by `bb sqlite-vec:fetch` / `bb model2vec:fetch` are used).
- **`BY_GRAPHEME_WIDTH`** — how the TUI measures emoji/CJK width
  (`:grapheme-width`, default **`auto`**). Terminals disagree about how wide a
  grapheme is, and the two regimes differ by up to **6 columns on one glyph**:
  a ZWJ family emoji is 8 columns summed per codepoint, 2 columns clustered.
  `auto` asks the terminal via DECRQM (`CSI ? 2027 $ p`) and caches the answer
  in `~/.brainyard/terminal-caps.edn`, keyed by
  `TERM`+`TERM_PROGRAM`+version+tmux (TERM alone is useless — every emulator
  claims `xterm-256color`). `off` counts per codepoint — what a terminal
  *without* DEC private mode 2027 does. `on` forces clustering with no probe.
  It defaults to `auto` because 2027 is default-**on** in Windows Terminal,
  WezTerm, Ghostty, Contour and foot, so a per-codepoint default is actively
  wrong there; and because every failure mode resolves to per-codepoint,
  `auto` can only ever be as wrong as `off`.
  **Everything that walks a string must step by `fmt/next-unit`**, not by
  `Character/charCount` — cursor motion, word-wrap and truncation have to move
  in the same unit `display-width` measures in, or a cut lands inside a ZWJ
  sequence and the two halves render as unrelated glyphs, *widening* the line
  the cut was narrowing.
  Negotiation runs in `run-tui!` only; `by ask` never probes and so always
  renders per-codepoint.
  **Why it caches, and what tmux gets instead:** a terminal that doesn't know
  DECRQM replies with *nothing*, so the read is time-bounded and that timeout
  is then paid in full — measured at ~500 ms on the native binary, roughly 4x
  its entire startup. tmux 3.6a never answers for 2027 (it answers 2004, so
  the query is fine), so `auto` still skips the probe there — but it no longer
  assumes the answer. That assumption was that tmux counts with `wcwidth`;
  measured by writing into a pane and reading `#{cursor_x}`, 3.6a **clusters**:
  a ZWJ family, a flag, a skin-toned thumb and a keycap are all 2 columns.
  Inside tmux the answer therefore comes from measuring tmux — a detached
  scratch session, tens of ms, re-measured when the tmux version changes.
  Every failure mode — no tty, no reply, garbled reply, exception, an
  unmeasurable tmux — resolves to clustering **off**. Impl:
  `components/agent/…/tui/terminal_caps.clj`; negotiation runs once in
  `run-tui!` before the first render.
- **`BY_MOUSE`** — TUI mouse clicks (`:enable-mouse`, default **true**).
  Click a tab in the tab strip to switch sessions; click a collapsed/expanded
  block marker in scrollback to toggle it. Fullscreen only — inline mode and
  `by ask` never emit the sequences, having no row model to resolve a click
  against.
  Enabling `?1000h` **supersedes** `?1007h`: alternate-scroll only synthesises
  arrow keys while mouse reporting is off, so the wheel arrives instead as SGR
  buttons 64/65, which `terminal/read-key!` maps back to `:scroll-up` /
  `:scroll-down`. Turning one on without the other silently kills scrolling.
  `?1006h` (SGR) is likewise not optional — the default X10 encoding packs a
  coordinate into `32 + n` and cannot address a column past 223.
  The cost is that the terminal hands click-drag to the application, so text
  selection needs its bypass modifier (Shift almost everywhere; Option in the
  xterm.js/`--web` path, which `playground-server/proxy.clj` already widens to
  accept both). `BY_MOUSE=false` restores plain-drag selection and falls back
  to alternate-scroll.
  **Inside tmux this works regardless of tmux's own `mouse` setting** —
  measured on 3.6a, nested two deep, both servers `mouse off`: enabling
  `?1000h` in the innermost app flips the outer pane's `#{mouse_any_flag}`
  0→1, and an injected `ESC[<0;5;3M` arrives byte-identical at the innermost
  process. With `mouse off` tmux passes the report straight through; with
  `mouse on` its `MouseDown1Pane` binding is `select-pane -t = ; send-keys -M`,
  which forwards it anyway, and `MouseDrag1Pane` / `DoubleClick1Pane` /
  `TripleClick1Pane` all test `#{mouse_any_flag}` and hand the event to the
  app instead of starting a tmux selection. So brainyard does not need — and
  does not make — any tmux config change for this.
  The one thing enabling mouse reporting *costs* under tmux: `MouseDown3Pane`
  also checks `mouse_any_flag`, so tmux's right-click context menu (which
  carries `Copy #{mouse_hyperlink}`) is suppressed for our pane.
  Resolution is two mappings, each of which must stay honest with its
  renderer: `layout/row->scrollback-idx` is `render-viewport!` read backwards
  (bottom-anchored, so it depends on both scrollback length and viewport
  offset), and `sessions/!tab-spans` is recorded *by* `format-tab-strip` so a
  tab dropped past the `…` is on no column and cannot be clicked. Both measure
  in `fmt/display-width`, never `count`. Impl: `ansi/enable-mouse`,
  `terminal/read-key!` (`ESC[<b;x;yM`), the `click!` handler in
  `autocomplete.clj`; tests in `bases/agent-tui/test/…/mouse_test.clj`.

### Clickable links are DETECTED at click time, not registered by producers

A click on a scroll-region row that carries no block marker falls through to
`links/detect-in-row`: a `path[:line[:col]]` opens in `$EDITOR`, an http(s) URL
goes to the OS opener. Both act immediately — the user clicked the literal text
of the target, so what they see IS what opens, and there is no label/destination
split for a confirm to protect against.

Detection runs against the rendered row rather than a producer-side registry,
which is what makes it work retroactively across all 22 emit sites, survive a
resize for free (it re-runs against whatever is on screen), and stay incapable
of going stale relative to the text on screen. The price is precision, and the
file case pays it with an **existence check rather than a tight regex**:
`path-candidate-re` is deliberately loose and a candidate that resolves to no
real file is simply inert. `3.14` and `e.g.` are candidates; neither exists;
nothing happens. Directories resolve to nil too — `.` and `..` are constant
false positives and opening a directory is never what a path click meant.

Three things that are load-bearing rather than incidental:

- **`fmt/strip-ansi` skips OSC, not just SGR.** The row is stripped before
  detection, so an OSC-8 annotation's hidden URL — which occupies no column —
  can never be clicked. A `#"\033\[[0-9;]*m"` regex leaves that payload in the
  "plain" text, and detection would then offer to open a target the user cannot
  see. This is also why the dead private `strip-ansi` in `format.clj` had to go
  rather than be reused: it was the SGR-only form, and being defined later in
  the file it silently shadowed the real one.
- **Parens are IN `url-re`, and trimmed afterwards.** `…/wiki/Foo_(bar)` is a
  real URL shape; excluding `()` from the class truncates it to something that
  still looks plausible. A class has no memory, so the wrapping case
  (`(see https://x/a)`) is handled by `strip-url-junk` counting them and
  dropping only an unbalanced trailing `)`.
- **`sh-quote`, not `pr-str`.** `open-in-editor!` runs `$EDITOR` through
  `sh -c`, and `pr-str` produces DOUBLE quotes, inside which the shell still
  expands `$VAR` and backticks. Harmless while the only paths were our own
  temp files; not harmless once a click feeds it model-authored text. URLs
  never touch a shell at all — `open-url!` uses argv, with an http(s)-only
  scheme allowlist so `file:`/`javascript:` and registered custom handlers stay
  unreachable.

#### A wrapped target is recovered by re-rendering, never by joining rows

Detection runs on ONE rendered row, so a target the renderer hard-wrapped is
only a fragment there. `layout/unwrapped-entry-text` runs the reflow machinery
backwards to fix that: `!scrollback-src` entries already know how to render
themselves at ANY width — that is what makes a resize re-wrap correctly — so
asking the owning entry for its 100000-column form recovers the logical text,
and `links/recover-target` finds the whole URL or path in it.

**Joining row N's tail to row N+1's head would be the obvious implementation
and is a vulnerability.** A seam whose position the text's author controls is
enough to forge a target: `https://safe.com` ending a row and `@evil.com/x`
beginning the next concatenate to `https://safe.com@evil.com/x`, which
navigates to evil.com while both visible halves read as harmless. Re-rendering
takes the candidate from the RENDERER's own output instead, so there is no seam
to aim at, and the fragment must appear inside the result as a substring.
Ambiguity (a fragment inside two different candidates) declines rather than
guesses. Regression test: `recovery-never-forges-a-target-from-a-seam`.

Live blocks are excluded — their renderers take width from `!layout`'s `:cols`
rather than the argument, so a wide render would return the same wrapped rows
while inviting a re-entrant render of a block a ticker owns. Non-reflowable
`(constantly …)` entries re-render to themselves, so the caller finds nothing
better and nothing breaks — degrading, not failing, as elsewhere in reflow.

**tmux gives none of this for free** (measured on 3.6a): it stores OSC-8
annotations (`#{copy_cursor_hyperlink}`) and forwards them outward, but it has
no URL detection for plain text — a bare `https://…` yields no annotation and
`#{copy_cursor_word}` splits it — and no open action anywhere. Its only
hyperlink affordances are `Type`/`Copy` in the `MouseDown3Pane` menu, which
`#{mouse_any_flag}` suppresses for us.

#### The mark is a hint; the click is authoritative

Nothing on screen would otherwise say a path, URL or tab does anything, so
`links/decorate-row` marks clickable targets and the app installs it via
`layout/install-row-decorator!` — **only when mouse reporting is on**, since a
mark promising a click the terminal will never deliver is worse than no mark at
all. It rides `:enable-mouse` rather than adding a second knob for a
sub-behaviour of the first. `sessions/format-tab-strip` marks tab LABELS the
same way (not the leading space or the active/unread glyph — those are chrome,
and marking them reads as a wider target than the eye picks out).

**What earns a mark is deliberately narrower than what is clickable**
(`links/worth-marking?`). This TUI names files constantly — tool args, results,
dossier paths, echoed commands — and marking every resolvable one turns prose
into a field of underlines and devalues the mark where it matters. So a path is
marked only when it reads as a LOCATION rather than a mention: it carries a
`:line` suffix (the traceback case, where clicking saves the most work) or it is
absolute. URLs are always marked — rarer, and clicking is the only reasonable
thing to do with one. A bare `deps.edn` mid-sentence still OPENS on click; it is
just not advertised.

**The mark is the `:link/target` theme token**, which is the one lever for "too
loud" and moves every call site at once. Only cleanly-toggleable mods belong in
it — `:bold` `:dim` `:italic` `:underline` `:reverse`, per `mod->off-code`. A
COLOUR has no "off": ending it needs a `reset`, which would discard whatever
styling the surrounding row had set, and the mark is inserted MID-ROW into text
we do not own. A colour binding therefore degrades to a mark that never ends —
visible, not corrupting. (Note SGR 22 clears bold and dim together; the terminal
has no separate code, so a `:dim` mark inside bold text ends the bold too.)
`decorate-row`'s memo is keyed on the input row, which does not change when a
theme does, so it tracks the mark separately and clears itself on a rebind.

**Styled underlines (`ESC[4:4m` dotted, `4:5m` dashed) were rejected**, though
they are the obvious "quieter" answer. Five production sites measure visible
width by stripping `#"\033\[[0-9;]*m"` — `core.clj:360`, `render.clj:140`,
`format.clj:539`, `task/format.clj:42` — and that class has no colon, so a
colon-form SGR survives the strip and inflates every length by five. Same class
of silent misalignment as the OSC-8 width bug. Roughly ten test helpers share
the pattern. Quieting by SELECTIVITY costs nothing and has no blast radius.

Three things this got wrong before it was right:

- **All three paint paths must decorate, or rows change appearance when you
  scroll.** `render-viewport!` is the obvious one; `write-output!`'s
  hardware-scroll path (fresh emits) and `render-block-rows!` (live-block ticks)
  paint rows too. Decorating only the first made a path render plain when it
  appeared and underlined the moment anything repainted it.
- **Decorate AFTER the clamp, never before.** The clamp is a width-aware
  truncate; a marker inserted first can have its closing `underline-off` cut
  away, leaving the underline on for every row below it — SGR state survives a
  cursor move.
- **Re-assert the underline after every escape inside a span.** The row's own
  escapes are not ours to interpret and one may be a full SGR reset, which
  silently drops the underline partway through the target. `ansi/underline-off`
  (SGR 24) rather than `reset` for the same reason in the other direction: it
  ends the underline without discarding the row's colour.

Affordable on the paint path because it is **memoised on the row string** —
rows are immutable and repaint constantly, so the regex scan and the `stat()`
run once per distinct row rather than once per frame. The memo never needs
invalidating: every click re-detects and re-resolves from scratch, so a stale
underline on a since-deleted file is cosmetic and the click correctly does
nothing. That is also why file paths are stat-ed for the underline at all — an
underline on a path that will not resolve promises a click that does nothing.

Impl: `bases/agent-tui/…/links.clj` (pure detection, resolution, recovery,
decoration), `layout/unwrapped-entry-text` + `install-row-decorator!`,
`display_block_ui/open-in-editor!` (the shared suspend dance, also behind
`view-in-editor!`), the `open-link!` closure in `autocomplete.clj`. Tests:
`links_test.clj`, plus the unwrapping cases in `mouse_test.clj`.

### Line breaking belongs to whoever owns the grid

A hard newline inserted to make text fit is permanent and lossy — the terminal
cannot tell it from one the author wrote, so a copied answer comes back broken
mid-sentence. A soft wrap is recorded as a wrap and rejoined on copy (verified:
in tmux, `capture-pane -J` reconstitutes a soft-wrapped paragraph exactly,
while a pre-wrapped one stays broken into rows forever).

So the answer renderer is chosen by **who decides where lines break**, via
`layout/terminal-owns-line-breaking?`:

- **Inline mode** — the terminal advances the cursor itself, so
  `format-answer-soft` emits each paragraph as ONE logical line and lets
  DECAWM wrap it. No box: a right border must be padded to a width we chose,
  and choosing that width is exactly what soft wrapping hands away.
- **Fullscreen mode** — keeps `format-answer` and its hard wrap, because
  `render-viewport!` writes each `!scrollback` entry to an
  absolutely-positioned row (`cursor-to`), and viewport offset, page scrolling
  and every live block's `:start-idx` all count entries **as rows**. One
  soft-wrapped entry would occupy two rows and shift everything below it,
  cumulatively. Cursor-addressed rendering and terminal autowrap cannot both
  be in charge.
- **Headless `by ask`** — already correct: it prints the raw answer with
  `println`, never wrapping.

Giving fullscreen soft newlines means making `!scrollback` hold *logical*
lines with a derived row-span index, and teaching viewport/scroll/live-block
accounting to distinguish lines from rows. That is a layout-engine change, not
a renderer change.

#### Pre-wrapped is not the same as wrapped once

Fullscreen must pre-wrap, but a row pre-wrapped at yesterday's width is wrong
today: `handle-resize!` used to update every geometry field and then replay the
*same* rows into a terminal that was no longer that wide. Nothing in the
codebase touches DECAWM (only `?1007` alt-scroll), so autowrap is on and the
overflow was silent — each row wrapped onto the row below, the next row's
`erase-line` wiped the spill, and the tail of every line disappeared; on the
bottom region row it scrolled the DECSTBM region instead, shifting every
absolutely-addressed row under the layout's model of where it was. Widening was
merely ugly: text stayed broken at the old column.

So each emit also records **how to render itself at a width**. `!scrollback-src`
is an ordered entry list covering exactly the rows in `!scrollback`
(`{:render (fn [cols] …) :n :block-id :sticky?}`), and `reflow-scrollback!`
re-renders all of it on resize, then recomputes every live block's `:start-idx`
from the new row counts.

The scroll position gets the same treatment, for the same reason: `viewport-offset`
counts ROWS back from the tail, so after a rewrap the same number lands on
different text. `viewport-anchor` reads it — **before** the resize writes the new
`:scroll-bottom`, since which row is on top depends on the height the viewport
still has — as `[entry-idx row-within-entry]`, and `restore-viewport-anchor!`
seats it back afterwards, clamping the row into an entry that may have got
shorter and the offset into the scrollable range. Offset 0 anchors to *nothing*
and stays 0: at the tail the user is following live output, and holding their top
row fixed while content re-wraps below would walk them off the bottom.

Two properties worth preserving if you touch this:

- **Reflow is opt-in per producer.** A caller that hands over a pre-formatted
  string gets `(constantly rows)` and behaves exactly as before, which is why
  this landed without changing all 22 `write-output!` call sites. Pass
  `:render` (via `write-output!` / `update-live-block!` / `emit!` /
  `emit-to-session!`) to make an emit reflow — `format-answer` and
  `format-answer-plain` already take `cols`, so the answer path is a one-liner.
  `format-answer-soft` needs none: it emits no hard newlines.
- **Live blocks need it MORE than ordinary output, not less.** A block re-renders
  on every tick, so while it is live a resize self-corrects on the next tick —
  but the moment it freezes (`[✓] Iteration 1` and its `Think:` text, a settled
  task, a finished sub-agent rollup) it never ticks again and keeps whatever
  width it was last wrapped at, forever. That is why `src-freeze-block!` keeps
  the renderer when it detaches the block. The block renderers in `session` take
  their width from `layout/!layout`'s `:cols` rather than an argument, and
  `reflow-scrollback!` sets `:cols` before invoking anything — so a block's
  `:render` is just `(fn [_cols] (render-… state …))`, and the two routes agree
  by construction. `iter-sink/-write-widget!` carries `opts` for the same reason.
- **A renderer is only as reflowable as its inputs.** The iteration block's
  `:eval-section-lines` are PRE-RENDERED strings held in state, so re-running
  `render-iteration-block-lines` re-wrapped the header/Think/tool lines and left
  the Code / Result / Output boxes at their old width — measured at a 120→70
  resize as Think 116→68 while the Result rows stayed 119. The block's `:render`
  therefore re-derives them from the structured `:eval-display`; `:id-prefix` is
  what keeps the display-block providers idempotent across re-renders. When
  adding a `:render`, check whether the state it closes over holds *data* or
  *already-wrapped strings*.
- **Some formatters had no width to reflow to.** `format-welcome-banner` sized
  its box to its content and never capped it, `format-next-prompt` truncated at
  200 chars — a content cap, not a width — and the permission prompts
  (`format-{feedback,confirm,text}-lines`) never wrapped their question at all.
  All overflowed a narrow pane at LAUNCH, not just after a resize, so no amount
  of `:render` would have fixed them. All now wrap/clamp to `terminal-columns`;
  the wrap helper is exported as `fmt/ansi-aware-word-wrap`. The permission case
  is the one that was more than cosmetic: rows too wide are clipped at paint
  time, so a long path meant approving something you could not fully read.
- **Wrap prose, truncate tables.** Not every block should reflow the same way.
  Questions, descriptions and think text WRAP. The pause-tips key hints and the
  frozen compaction summary TRUNCATE — they are fixed two-column rows held
  together by padding, and wrapping them reads as noise while truncation keeps
  the part that matters (the key, the verdict) on the left. Same reasoning as
  `draw-status-bar!`.
- **Blocks that re-render fast enough need nothing.** The `async-spin` block in
  `commands` ticks every 120 ms and is disposed on completion, so a resize
  self-corrects within a frame. `:render` there would be dead code.
- **Drift degrades, it never corrupts.** A session switch swaps `!scrollback`
  wholesale and a test resets it directly, so the entry list can fall out of
  step. `ensure-src!` rebuilds it from whatever is on screen, and a reflow only
  runs against a list that accounts for exactly the rows present. The cost of
  drift is that those rows stop reflowing — which is what they did before any
  of this existed. `render-viewport!` / `render-block-rows!` additionally clamp
  every row to `cols` at paint time, so an un-reflowable row clips visibly
  instead of corrupting the screen. Tests: `resize_reflow_test.clj`.

- **`BY_SANDBOX_INTEROP`** — seeds the `:sandbox-interop` config default
  (`restricted` | `full` | `auto`) controlling Java interop in the **in-process
  SCI code-eval sandbox** (distinct from `--sandbox`, which is the OS seatbelt).
  `restricted` denies System/Runtime/ProcessBuilder/ClassLoader; `full` permits
  arbitrary interop (container-only); `auto` (default) relaxes to `full` only
  when a container is detected via `env-detect`, else stays `restricted` — so a
  bare host is never silently relaxed. Per the config precedence (below), a set
  `BY_SANDBOX_INTEROP` **wins over** `.brainyard/config.edn`; the file only
  applies when the env var is unset. Mechanism in `components/clj-sandbox`
  (`sci-init-opts`/`full-classes`); policy in
  `agent.core.config/resolve-sandbox-interop`. See `docs/sandboxing.md`.

  **Config precedence (all schema keys, highest → lowest):** environment
  variable (a key's `:env-fn`) > per-agent override > session config >
  `.brainyard/config.edn` (merged over static defaults) > schema default. A set
  env var wins over every persisted layer; each resolution is mulog-tracked
  once per (key, source) via `::config-resolved`. Resolved in
  `agent.core.config/get-config`.

### Subagent work tiers: router-agent picks a tier, config picks the model

A dispatched specialist has no per-agent `:lm-config`, and `get-config` has no
parent→child inheritance — so before this existed every specialist resolved its
model from the shared *session* layer. A `config-agent` call flipping one
boolean billed whatever `/model` last set. The router chose the cheap agent and
handed it the expensive model.

The fix splits the decision, because neither half can make the other:

```
router-agent  ->  a WORK TIER  (:light | :standard | :deep)   — it can see the work
config        ->  tier -> "provider/model"                    — it knows the credentials
```

The router never names a model. Model ids churn weekly (which is why the
catalog refresh exists), the router cannot know which providers are
configured, and "why did this run on Opus?" deserves a configuration answer
rather than a per-turn LLM whim — the same reasoning as the catalog's
"provider API owns ids, humans own curation" split.

- **`:agent-lm-tiers`** — `{:light "…" :standard nil :deep "…"}`, each a
  `provider/model` label resolved by `resolve-tier-lm` (same fallback
  discipline as `resolve-sub-lm`/`resolve-eval-lm`). **Ships all-nil, so the
  feature is inert**: nothing is injected, and every dispatch resolves exactly
  as it did before. An unparseable label is also inert — deliberately NOT a
  fallback to the main LM, which would hide the typo behind working dispatches.
- **`:agent-tier-map`** — `{<defagent-type> {:default … :min … :max …}}` for the
  19 built-in specialists. `:default` applies when the router says nothing;
  `:min`/`:max` clamp what a router-requested `:work-tier` may become. A clamp
  is logged (`::tier-clamped`), never an error: a router asking for `:deep` on
  a `:light`-capped specialist is wrong about cost, not about intent, so
  failing the dispatch would turn a cost question into an outage while obeying
  would make the cap decorative. An agent absent from the map is unconstrained
  `:standard`, so new and user-authored agents behave as they do today.

The resolved LM rides the dispatch args into `setup-agent`, landing on the
sub-agent's **per-agent** layer, which outranks session. `:work-tier` is
consumed at the dispatch and stripped before the specialist sees it — a
specialist that knew its own tier would be invited to hedge.

**Observability:** `::tier-routed` (every dispatch, including inert ones, so
"why is this still on the session model" is answerable), `::tier-clamped`, and
additive `tier`/`tier-model` fields on the routing.log NDJSON line. Cost is
attributed per specialist via the usage tracker's `:by-agent` rollup, which
needs `llm/with-usage-attribution*` — bound in `agent.clj` around
`proto/process`, since `clj-llm` sits below the agent component and cannot see
an agent. `bb catalog:refresh` reports which catalog models have no entry in
`clj-llm`'s `default-pricing` table and therefore silently bill `0.0`.

Design + as-built map: `docs/design/router-agent-model-routing-plan.md`.

## Build & release pipeline

```bash
sdk use java 25.0.3-graal         # matches .sdkmanrc
bb build:ata                      # version:ata → compile → uberjar → native binary (~3 min)
bin/release-stage.sh              # stage release/ artifacts + SHA256SUMS + BUILD-INFO.txt
gh release create vX.Y.Z release/* --notes-file CHANGELOG-latest.md
bin/release-verify.sh vX.Y.Z      # confirm every SHA256SUMS entry actually published
```

**Upload `release/*` — never a hand-written asset list.** Every staged file is
an asset, including the version-less `by.jar` alias that keeps
`releases/latest/download/by.jar` resolving and that `SHA256SUMS` covers. v0.6.0
shipped without it because the assets were listed by hand: the documented
stable-URL download 404'd and `shasum -c SHA256SUMS` exited 1 for everyone who
verified their download, on a release whose binaries were fine.
`release-stage.sh` now prints the exact `gh release create` line, and
`release-verify.sh` re-downloads the published assets and checks them against
the manifest that shipped with them — the only step that can catch this, since
it happens after the build and after staging.

### Tagging discipline (critical)

The binary's `--version` is **baked at build time from `git describe` of this repo**, so the tag IS the release version. Workflow for a new release:

1. Update `CHANGELOG.md` and `CHANGELOG-latest.md`, commit.
2. `git tag vX.Y.Z` at HEAD.
3. `bb build:ata` — `bb version:ata` runs first, stamping `projects/agent-tui-app/resources/build-version.edn` from `git describe`. (That file is gitignored.)
4. `bin/release-stage.sh` — **refuses to stage** if the describe output is `-dirty`, `-N-gabc123` (commits past the tag), or `dev`. These would bake a misleading version into a public binary.
5. `gh release create vX.Y.Z release/* …` (the glob, not a hand-listed set)
6. `bin/release-verify.sh vX.Y.Z` — re-downloads the published assets and checks them against the shipped `SHA256SUMS`. Also runs against a staging dir before publishing: `bin/release-verify.sh --dir release/`.

Committing after tagging puts the repo into post-tag state (`vX.Y.Z-1-g…`); `release-stage.sh` will reject builds from this state. To re-release after a doc fix, move the tag (`git tag -f vX.Y.Z`) and re-build.

## Key files

- `bin/release-stage.sh` — packages `target/` outputs into `release/` with the exact asset names `bin/install.sh` expects. Reads the version from `projects/agent-tui-app/resources/build-version.edn` and records this repo's commit in `BUILD-INFO.txt`.
- `bin/release-verify.sh` — post-publish gate: downloads a release's assets and verifies them against the `SHA256SUMS` that shipped with it, failing on an asset that was checksummed but never uploaded, or one that uploaded corrupted. `--dir <path>` runs the same checks on a local staging dir before publishing.
- `bin/install.sh` — public `curl | bash` installer. Resolves the latest release tag via the GitHub API, downloads platform-matched assets, verifies SHA-256, re-codesigns on macOS.
- `deps.edn` / `bb.edn` / `workspace.edn` — Polylith workspace + task config.
- `docs/` — architecture, design notes, specs, and tutorials.

## Testing

```bash
bb test                                      # run all Polylith tests (clj -M:poly test)
bb poly <args>                               # Polylith CLI (e.g. bb poly check, bb poly info)
```

After a build, smoke-test the binary directly:

```bash
projects/agent-tui-app/target/by --help      # subcommand routing
projects/agent-tui-app/target/by agents      # config + agent registry load
projects/agent-tui-app/target/by sessions list   # sqlite persist layer
```

Web-sharing smoke test (needs `ttyd` on PATH; `BY_WEB_SELF` points ttyd's child
at a stand-in so the full TUI doesn't boot). Open the URL or `curl` it, then
Ctrl-C — `by` should reap ttyd and free the port:

```bash
BY_WEB_SELF=cat projects/agent-tui-app/target/by --web --web-port 7681 --web-pass test
# elsewhere:  curl -s -o /dev/null -w '%{http_code}\n' -u by:test http://127.0.0.1:7681/   # → 200
#             curl -s -o /dev/null -w '%{http_code}\n'           http://127.0.0.1:7681/    # → 401
```

Sandbox smoke test (macOS only). `BY_SANDBOX_SELF` points the seatbelt child at a
stand-in script so the full TUI doesn't boot — the launcher injects a `run`
subcommand token, so the stand-in must ignore its args:

```bash
cat > /tmp/by-probe.sh <<'EOF'
#!/bin/sh
echo x > /etc/x 2>&1 || echo write-blocked-ok          # denied: outside allowlist
echo ok > "$HOME/.brainyard/e2e" && echo brainyard-write-ok   # allowed
EOF
chmod +x /tmp/by-probe.sh
BY_SANDBOX_SELF=/tmp/by-probe.sh projects/agent-tui-app/target/by --sandbox
# → "write-blocked-ok" then "brainyard-write-ok"
```

For a real LLM round-trip (Bedrock works without API keys if `AWS_PROFILE` is set — note `AWS_DEFAULT_PROFILE` is **not** honored by the binary's SDK chain):

```bash
AWS_PROFILE=<profile> projects/agent-tui-app/target/by \
  ask -p bedrock -m amazon.nova-lite-v1:0 'What is 2+2?'
```

JVM-mode parity check (catches reflection-config gaps):

```bash
BY_JAR=1 projects/agent-tui-app/target/by ask …
```

## Design decisions

### Context-graph memory is an overlay, not a replacement (CR-MEM-20..24)

The graph (`components/memory`: `graph_nodes`/`graph_edges`/`graph_vec` +
`graph_communities`) is layered **over** the existing L1/L2/L3 FTS store as extra
RRF recall signals — semantic-similarity (`:vec`) and relational/multi-hop
(`:graph`) — never a replacement. Off by default (`BY_ENABLE_GRAPH_MEMORY`), it
**degrades gracefully**: no embedder ⇒ no `:vec`; empty graph ⇒ recall is
byte-identical to pure FTS; no extract model ⇒ storage-only. Self-population is
LLM extraction, run in one of two modes (`:graph-extract-mode`, default
`:at-consolidation`): batch-extract new episodes at each consolidation, or
`:per-episode` off the capture sidecar. Community summaries replace the
heuristic L2→L3 reducer (**closes CR-MEM-07**) and are harvested by consolidation,
which is now implied by `BY_ENABLE_GRAPH_MEMORY`. Two decisions worth knowing:

- **Embeddings can be fully self-contained.** The default `BY_GRAPH_EMBED_MODEL
  "static"` is a pure-JVM **Model2Vec** embedder bundled into the binary (no
  server, no JNI, no native-image risk — unlike a real transformer runtime).
  Power users point it at Ollama/OpenAI instead. See the embedding-model
  discussion in `docs/design/context-graph-memory-design.md`.
- **Changing the embed model pauses, never corrupts.** `graph_vec` vectors are
  only comparable within one model (a same-dim model swap silently poisons kNN).
  The store fingerprints the embedder; on a mismatch it **safe-disables** vector
  recall (FTS fallback, no mixed-space writes), surfaces a startup banner +
  `memory$status` flag, and waits for the user to run `memory$reembed`. Guided,
  not automatic — no surprise embedding cost, no silent wrong rankings.

### The model catalog refreshes itself, but curation stays human

`clj-llm`'s `model-catalog` is hand-curated and baked into the binary, and it
drifts — a single audit against the live provider APIs found three OpenAI ids
that do not exist, a retired Claude family still listed, the Claude 5 family
missing, and models catalogued that this client cannot drive at all. So the
catalog now refreshes from each provider's model-list endpoint.

What refreshes and what does not is the whole design:

```
provider API  ->  WHICH IDS EXIST        (refreshable, changes weekly)
baked catalog ->  :curated-rank,
                  :description, :region  (human judgement, changes rarely)
```

A provider's list is **not** a catalog. `/v1/models` returns embeddings, TTS,
transcription and image models; Bedrock's `ListFoundationModels` adds Titan
embeddings and Stability image models; OpenAI's `-pro` tier is chat-shaped but
served only by `/v1/responses`. Nothing in any response says which entries a
chat client can drive, nor which reject `temperature` — both were established
by probing. So the overlay carries **ids only**: it can retire a curated model
and surface a new one as usable, but it can never invent curation, which is
what keeps a `whisper-1` out of the `/model` picker.

Four safety rules, each guarding a quiet failure:

- **A provider absent from the overlay passes through untouched** — the
  offline, first-run and no-credentials path.
- **An empty fetch is ignored, never applied.** An outage returning `{}` must
  not be read as "this provider serves nothing".
- **A partial (region-scoped) fetch is additive only.** Bedrock is enumerated
  one region at a time and the catalog deliberately pins us-east-1-only
  models; retiring on absence would delete a working model because of where
  the user was standing. One region's inventory cannot prove a model is gone.
- **Non-enumerable providers cannot be overlaid** (`claude-code`, `acp`,
  `apple-fm`, `free-llm` have no list endpoint).

**`bb catalog:refresh`** is the maintainer counterpart: it compares the *baked*
catalog against the live providers and reports drift, so what ships in the
binary stays current between releases. `--write` deletes entries a provider no
longer serves; additions are reported but never written, because an entry only
earns a place once a human gives it a rank and a description. The rewrite is
**surgical, not a regenerate** — `providers.clj` carries comments recording
which ids were probed and rejected, and losing them means someone re-adds the
models they warn about. Ids deliberately not catalogued live in
`excluded-model-patterns` as **data**, not prose: as a comment they were
invisible to the tool, so the first run proposed all 40 of them as new and
would have done so forever.

Ollama is the case that most needs this: a baked list of models is a guess
about someone else's machine, and `/v1/models` on the local server is the only
authority for what is actually installed. Impl: `components/clj-llm/…/core/`
`catalog.clj` (pure merge), `catalog_store.clj` (cache, TTL), `catalog_fetch.clj`
(per-provider fetchers). The cache root is **injected** by the app via
`set-catalog-cache-root!`, the same shape as `persist/set-root!`, because
`clj-llm` sits below the agent component and cannot resolve `~/.brainyard`
itself.

### Projects get a user-scope folder, keyed by a reversible slug

Every project is registered under `~/.brainyard/projects/<slug>/`, giving
user-scoped state *about* a project a home that is not the repo's own
`<project>/.brainyard/` (which travels with the codebase). v1 stores **registry
metadata only** — `project.edn` with the canonical path, name, git remote, and
created/last-opened stamps. Nothing moved out of project scope.

The slug is `<sanitized-basename>-<8 hex of SHA-256(canonical path)>`, e.g.
`brainyard-3f9a1c2d`: space-free, readable, stable (so registration is
idempotent), and collision-free across two checkouts that share a basename.

Two decisions worth knowing:

- **slug → path is a LOOKUP, not an algorithm.** It's recovered by reading
  `<slug>/project.edn`, which is exact. The tempting alternative — encoding the
  path by substituting separators (`/Users/me/my-app` → `-Users-me-my-app`) —
  is *not* reversible once a path segment contains the separator character, and
  real paths do (`my-app` vs `my/app`). There's a regression test for exactly
  that pair.
- **`index.edn` is a derived cache, never authority.** It's rebuilt by scanning
  the per-slug records, so concurrent `by` processes never contend (each writes
  only its own slug) and a torn index heals on the next refresh.

Registration happens in `run-tui!` and `cmd-ask` only, always *after*
`install-working-dir!` so `-C`/`BY_PROJECT_DIR` are in effect — deliberately
not in `ensure-config-dirs!`, which is a hot path. Failure is swallowed; the
registry must never block a session. Surfaced by `by projects list` /
`by projects path <slug>`; `by projects prune` reclaims entries whose
directory is gone (confirms first — `(missing)` also covers an unmounted
volume, which comes back). Design: `docs/design/project-registry.md`; impl in
`components/agent/src/ai/brainyard/agent/core/projects.clj`.

### Task output files are GC-reclaimed, not deleted on task removal

Each task gets a project-scoped dir `<project>/.brainyard/tasks/<task-id>/`
holding `output.log` (combined stdout+stderr) and `meta.edn` (lifecycle
snapshot). The LLM reads these back after completion via `task$detail` /
`format-task-output`.

**Decision (2026-06-06): task removal and artifact removal are intentionally
decoupled.** `agent/remove-task` (the protocol method behind the `/task del`
command) only drops the in-memory registry entry; it leaves `output.log` /
`meta.edn` on disk for post-mortem inspection. Disk reclamation is the GC
layer's job — `gc/sweep-tasks!` via the `task$sweep` command, bounded by
`:task-retention-count` (default 100) and `:task-retention-days` (default 7) in
`core.config/config-schema`. The sweep skips dirs whose `meta.edn` reports a
live task.

So output files **outlive** task removal and are reclaimed in bulk by the
retention sweep, rather than dying synchronously with the task. An opt-in
helper `manager/remove-task-and-artifacts!` exists for immediate cleanup
(removes the row *and* calls `persist/delete-task-dir!`), but it is deliberately
**not** the default path and is not wired into `/task del`. See the retention
note in `components/agent/src/ai/brainyard/agent/task/persist.clj`.

### Conversational front-door agents over the event subsystem

Three specialists give the event subsystem a chat CRUD surface, each a thin
`coact/run-coact-derived` `defagent` with **zero new commands** — they only
orchestrate command families that already ship. Same minimal-diff pattern as
`config-agent`/`mcp-agent`. The clean boundaries (a request routes to exactly
one, and they compose by calling each other by name):

- **`schedule-agent`** (`common/schedule_agent.clj`) — **time**-triggered prompt
  jobs (`schedule$*`): "every weekday at 9am, summarize commits." Watches are
  excluded (they're event-agent's).
- **`event-agent`** (`common/event_agent.clj`) — the flat **event** vocabulary
  (`event$*` / `reaction$*` / `watch$*`): declare events, wire `trigger → action`
  reactions, author autonomous watch pollers, diagnose a dead rule.
- **`state-machine-agent`** (`common/state_machine_agent.clj`) — user-defined
  **FSMs** (`fsm$*`): the stateful states/transitions graph plus its per-session
  runtime, two lifecycles (definition vs runtime) kept distinct.

Shared conventions worth knowing:

- **Gates are read here, written by `config-agent`.** Each agent reads
  `:enable-scheduler` / `:enable-reactions` / `:enable-fsm` / `:fsm-allow-code`
  via `agent-runtime$config` but never writes them — a gate change is a config
  write, handed to `config-agent` by name. They also hand flat reactions ↔
  stateful graphs to each other at the seam.
- **Dossier is a hard contract.** Every write-producing turn must write a
  markdown dossier under `.brainyard/agents/<agent>/dossiers/<ts>-<slug>.md` +
  prepend to `INDEX.md`, enforced by a `FINAL-STEP CHECKLIST` in each
  instruction ("a write that ends without a dossier is an INCOMPLETE turn").
  This was added after a live run skipped the (previously advisory) dossier.
- **Registration:** add the ns to the side-effecting require list in
  `components/agent/src/ai/brainyard/agent/interface.clj` (single source of
  truth for built-in `defagent`s) and wire the agent into `common/router_agent.clj`'s
  router in three places (directory, lettered decision table, summary list).

Designs: `docs/design/{schedule,event,state-machine}-agent-design.md`. Each has
a structural + hermetic-pass-through test suite under `components/agent/test/`.

## bb task naming convention

Tasks for the shipping project end in `:ata` (agent-tui-app): `compile:ata`, `uberjar:ata`, `native:ata`, `build:ata`, `install:ata`, `version:ata`, `check:ata` (native-image config drift gate), `size:ata`, `repl:ata`, `tracing:ata`, `docker:ata`. Workspace-wide tasks (`test`, `poly`) have no suffix.

Resource-fetch tasks (CR-MEM-21, context-graph memory) download sha-pinned, gitignored binaries into `components/memory/resources/` and are run by `build:ata` before `uberjar:ata` so they get bundled into the native image: `sqlite-vec:fetch` (the `vec0` extension, per-platform) and `model2vec:fetch` (the bundled `potion-base-8M` static embedding model). They are noun-scoped (not `:ata`) because they populate a component's resources, not the project's build outputs.

Model-catalog tasks are noun-scoped for the same reason — they maintain a component's source, not a build output. `catalog:refresh` reports how the baked catalog differs from the live providers (`--write` applies removals); `catalog:test` verifies the source surgery that `--write` performs, contacting no provider so it is safe offline. `catalog:test` is **not** part of `bb test`, which runs the Polylith brick suites — run it after touching `scripts/catalog_refresh.clj` or the shape of `model-catalog`.
