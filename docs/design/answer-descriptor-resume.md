# Answer descriptors on resume — scope

Closes the **"Faithful re-wrapping"** gap in
[docs/tui/persistence.md](../tui/persistence.md): a resumed session's answer
boxes are frozen rows, so resuming narrower than the session was written gives
ragged borders. This is the **narrow** version that doc recommends — a
descriptor for the answer emits only, leaving every live block alone.

Status: **implemented**. As-built notes are at the bottom, where the shipped
code diverged from this plan.

## The gap, precisely

Two things are true at once today:

- `core/resume-tail-renderer` (core.clj:1531) can make replayed rows *fit* a new
  width — it word-wraps any row wider than `cols` and stays attached as the
  entry's `:render`, so the tail keeps reflowing on later resizes.
- It cannot **redraw a box**. It receives rows, not the values that produced
  them, so a 130-column answer frame resumed at 80 becomes a wrapped 130-column
  frame: the border fragments land mid-screen instead of the right margin.

The rows that fit pass through untouched, which is deliberate and is why the
common same-width-or-wider resume looks correct. The damage is confined to the
narrower resume.

The missing input is the answer's **source text**. `:scrollback-src` holds it in
a closure (`#(fmt/format-answer answer %)`, session.clj:3044) and closures do
not serialise, so nothing about it reaches disk.

## Shape of the fix

Persist a descriptor beside the rendered bytes; on resume, locate the rendered
block in the tail by content and hand that segment a live renderer.

```
emit time    format-answer answer cols  ->  rows        -> scrollback.stream.txt
                                        \-> {:kind :answer :text answer :block rows}
                                                        -> scrollback.stream.desc.ndjson

resume time  tail + descriptors -> segments
               [rows ......]                  :render (constantly rows)   (as today)
               [answer block]                 :render #(format-answer text %)   <- new
               [rows ......]                  :render (constantly rows)
```

The replay stops being one `write-output!` of the whole tail and becomes one per
segment. Row count is unchanged; only the renderer attached to the answer
segments differs.

### Why content-matching, not offsets

The obvious design — record each emit's byte offset and align the tail against
it — does not survive this storage layer:

- `scrollback/tail-bytes` returns the last N **bytes** walking rotated files
  newest→oldest. There is no absolute position to align to without reading the
  whole stream.
- Rotation renames files (`live → .1.txt → .2.txt`), so any file-relative offset
  shifts underneath the record.
- `repair-concat!` rewrites stream files in place, inserting newlines at
  `\033[0m\033[` boundaries. Offsets recorded before a repair are wrong after it.

Content-matching is agnostic to all three. A descriptor whose block was
truncated away, rotated out, or altered simply fails to match and that region
stays plain rows — **exactly today's behavior**. Degradation is the default,
which is the same instinct as `layout/ensure-src!` rebuilding from what is on
screen.

### Why the events log is not already enough

`persist_bridge/on-ask-post` (persist_bridge.clj:155-160) already writes
`{:kind :agent.ask/post :payload {:answer …}}` to the event log, so the source
text is arguably on disk already. It cannot be used as-is:

- The answer is **truncated to 4000 chars** at that call site. Re-rendering from
  it would silently shorten long answers on resume — a worse failure than a
  ragged border, because it is invisible.
- It carries no correlation to a position in the stream, which is the part
  actually missing.
- The event is keyed by the emitting agent's session id, while the sub-agent
  answer emit (session.clj:3082) tees to the **root's** `:sub-output` stream —
  so the sub-output tab's answers would need a second correlation anyway.

A descriptor written at the tee has the exact bytes and the untruncated text in
the same place, for free.

## The matching algorithm

Work line-wise on ANSI-**stripped** rows:

```clojure
(let [tail-lines (str/split-lines tail)
      stripped   (mapv fmt/strip-ansi tail-lines)]
  ;; descriptors are in stream order -> one forward pass, cursor never rewinds
  (loop [ds descriptors, cursor 0, segments []]
    ...))
```

For each descriptor, `(mapv fmt/strip-ansi (str/split-lines (:block d)))` is the
needle; find the first window at or after `cursor` that equals it, emit the rows
before it as a plain segment and the window as an answer segment, advance the
cursor past it.

Two properties this buys:

- **Stripping tolerates theme drift.** The `:block` is used only to *locate*;
  the re-render uses the current theme. Matching raw bytes would fail whenever
  the answer-box colour changed between sessions — a silent regression to frozen
  rows. Note `fmt/strip-ansi` skips OSC as well as SGR (see the clickable-links
  section in CLAUDE.md), which is what makes this safe against an OSC-8
  annotation inside the answer.
- **Line windows map back trivially.** Matching on a stripped *string* would
  need offsets translated back into row indices; matching on a vector of
  stripped rows yields `[start-line, end-line)` directly.

Worst case is O(tail-lines × block-lines) per descriptor, and every answer box of
the same width shares a top border, so first-line collisions are common. If
profiling shows it matters on a 10 MiB tail, index positions by first stripped
line and verify candidates — near-linear, no change to semantics. Do not build
the index up front.

## Files

| File | Change | ~Lines |
|---|---|---|
| `components/agent-tui-persist/…/core/scrollback.clj` | `append-descriptor!`, `read-descriptors`, `desc-tag->filename` beside the existing `stream-tag->filename`; trim-on-write cap | 60 |
| `components/agent-tui-persist/…/interface.clj` | export both (mirrors `append-scrollback!` / `tail-scrollback` at lines 59-61) | 2 |
| `bases/agent-tui/…/persist_bridge.clj` | `tee-scrollback!` / `tee-sub-output-scrollback!` take an optional `desc`; write `(assoc desc :block terminated)` after the append | 15 |
| `bases/agent-tui/…/sessions.clj` | `emit-to-session!` threads `:desc` from `opts` into the tee (both tee targets) | 5 |
| `bases/agent-tui/…/session.clj` | `emit!` carries `:desc` (it currently rebuilds `opts` as `(when render {:render render})`, dropping everything else) | 5 |
| `bases/agent-tui/…/session.clj` | the 2 answer emit sites (3036-3044 root, 3082-3091 sub-agent) gain `:desc` | 4 |
| `bases/agent-tui/…/core.clj` | `tail-segments` (pure); `write-resume-tail!` iterates segments; the 3 tail-read sites (1147, 1833, 1847) also read descriptors | 60 |
| `bases/agent-tui/…/session.clj` | `restore-sub-output-session!` takes segments instead of `(tail render)` | 10 |
| `components/agent/…/core/config.clj` | `:resume-answer-descriptors` beside `:resume-scrollback-bytes` (line 485) | 3 |
| tests | see below | ~200 |

The call site supplies only `{:kind :answer :variant :boxed :text answer}` — the
tee already holds the exact emitted string and fills `:block` itself. That keeps
the answer from being rendered twice and keeps the two halves impossible to
desync.

## Storage

The sidecar duplicates the rendered block plus the source text. Bound it by
entry count (`:resume-answer-descriptors`, suggest 200), rewriting the file when
it passes 2× the cap. It deliberately does **not** rotate in lockstep with the
stream: a descriptor whose bytes have rotated away is inert, because it can no
longer match anything in the tail. Lockstep rotation would be more machinery for
the same outcome.

## Decisions

1. **Re-render with the stored variant, not the current mode.** *(Settled.)* A
   descriptor records `:boxed` or `:plain` as emitted, and the resume renders
   with that, ignoring the resuming process's `display-format`. Resuming a boxed
   transcript into `:quiet` should not silently reformat history — the tail is a
   record of what happened, and reflow is about width, not about re-deciding
   presentation.

   Consequence to keep in mind when implementing: `quiet?` is therefore read at
   **emit** time only. The resume path must not consult it — reaching for it
   there is the natural mistake, since every other renderer in `session.clj`
   does.
2. **Inline resume is untouched.** `write-resume-tail!` writes the tail raw when
   `layout/terminal-owns-line-breaking?`, because imposing our own hard breaks
   would replace a wrap the terminal can rejoin on copy with a permanent one.
   Descriptors are read but unused there.
3. **`format-answer-soft` gets no descriptor.** It emits no hard newlines and
   only ever runs on the inline path.

## Tests

Pure, on `tail-segments`:

- locates a descriptor and returns a live renderer for that window only
- a descriptor whose block was cut by the byte tail is skipped; those rows stay
  plain (assert the segment list still covers every row exactly once)
- two identical-width boxes in the tail resolve in order — the forward cursor
  never assigns the second descriptor to the first box's rows
- ANSI/theme drift between the stored block and the tail still matches
- **no descriptors ⇒ output identical to `resume-tail-renderer` today** (guards
  the regression path directly)

Round-trip:

- tee a boxed answer at 130 with a descriptor, read back, resume at 80 → the
  right border is at column 80 by `fmt/display-width`, not a wrapped fragment
- the `:persist? false` replay paths (`handle-resume-session-op` core.clj:1150,
  `restore-sub-output-session!`) append no descriptors — same doubling hazard the
  flag exists for

Existing suites that must stay green:
`resize_reflow_test.clj` (the three `resume-tail-renderer-*` tests at 381-410),
`output_session_reflow_test.clj`, `sub_output_resume_test.clj`,
`persist_bridge_test.clj`.

## Out of scope

- **Live blocks** (iteration widgets, think blocks, task blocks) still do not
  come back at all. That is the other gap in persistence.md and needs teeing a
  block's frozen state at `src-freeze-block!` — unrelated machinery.
- **Other box-drawn output** — tables in tool results, the welcome banner, the
  frozen compaction summary — stays ragged on a narrow resume. Each would need
  its own descriptor kind. The registry generalises if that becomes worth doing;
  nothing here forecloses it.
- The full ~60-site descriptor conversion, and the cross-release compatibility
  surface it would create.

## Effort

~350-400 lines, roughly half tests. One focused day. Blast radius is low by
construction: every new path is additive, and the no-descriptor case is the
current code path unchanged.

## As built

Four deviations from the plan above, each with its reason:

1. **No `:resume-answer-descriptors` config key.** The caps live as
   `default-max-descriptors` / `default-desc-max-bytes` in `scrollback.clj`,
   overridable per call like `append!`'s `:max-bytes`. Nothing in the tee path
   passes them, so adding a schema key would have shipped a knob that no call
   site reads. The cap bounds disk at ~1 MiB per stream and nothing about
   correctness depends on it; add the key if someone needs to tune it.

2. **`resume-tail-renderer` was replaced, not extended.** It became `fit-rows`
   (rows + cols → rows), because the per-span renderers need to fit a *subvec*
   rather than re-split the whole tail each call. Its docstring — the two
   failure modes of an unfitted tail, measured — moved with it. The three
   `resume-tail-renderer-*` tests are now `resume-tail-fitting-*` and build the
   same closure locally.

3. **`restore-sub-output-session!` takes segments, not a renderer.** The
   splitter lives in `core`, which requires `session`, so the split happens at
   the call site. Same for the `:resume-session` adopt path, which emits one
   `emit-to-session!` per segment instead of one for the whole tail.

4. **The trim triggers on bytes, keeps by count.** `append-descriptor!` checks
   `.length` on every append (O(1)) and rewrites to the newest
   `max-descriptors` only when it exceeds `max-bytes`. The steady state
   therefore oscillates between the two, which is what the cap test asserts —
   an exact surviving count would be brittle to the width of one EDN line.

Verified: `answer-descriptor-test` (7), `persist-test` (14),
`persist-bridge-test` (12, including the end-to-end tee → sidecar → redraw),
`resize-reflow-test` (16), `sub-output-resume-test` (6),
`output-session-reflow-test` (9) — all green, plus `bb poly check`.

The end-to-end test is the one to keep: the unit tests on either side build
their own descriptors, so only that one would notice if the `:block` the tee
records drifted from the bytes it writes. That drift fails silently — every
answer simply goes back to being frozen rows.
