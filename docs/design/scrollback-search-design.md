# Scrollback search (Ctrl-F)

Status: **shipped** (phases 1–3). Fullscreen TUI only.

As-built: `bases/agent-tui/…/search.clj` (`compile-query`, `scan`,
`highlight-row`, hit selection), `layout/finish-row` + the `Scrollback search`
and resync sections, `:ctrl-f` in `terminal/read-key!`, the search sub-mode in
`autocomplete/read-line-raw!`, `:search/match` / `:search/current` +
`ansi/mark-on`|`mark-off`, per-tab state in `sessions`. Tests:
`bases/agent-tui/test/…/search_test.clj` (31).

Find a keyword in the active session's scrollback, land the viewport on the
match, and step through the other matches with ↑/↓.

```
Ctrl-F            enter search        (input line becomes the search bar)
<type>            incremental — first hit at-or-above the current position
↑ / ↓             previous / next hit (wraps, with a notice)
Ctrl-F            next hit            (browser muscle memory)
Enter             keep the position + highlights, return to the input line
Esc / Ctrl-C      cancel: clear highlights, viewport stays where it is
```

## 1. What is searched, and why it is the rendered rows

`!scrollback` is a vector of **rendered, styled rows**. Search runs over
`fmt/strip-ansi` of each row, and the recorded spans are indices into that
plain text.

Searching the styled string instead would be wrong three ways: a query
spanning a style boundary never matches, escape bodies contribute phantom
hits (`m`, `1`, `38;5`), and the spans could not be handed to a span painter
that has to insert between escapes. This is the same conclusion
`links/detect-in-row` reached, for the same reason.

**Semantics:** literal substring, **smart-case** — case-insensitive unless the
query contains an uppercase character (rg/vim convention; no flag UI needed).
**Every** hit on a row is recorded, not just the first, or "next" silently
skips matches inside a long row and the `3/17` counter lies.

**A leading `/` opts into regex**; `//` escapes it, so a literal `/foo` is
`//foo`. Smart case applies to both. Two things this needs to be usable:

- **An uncompilable pattern is a VALUE (`{:kind :invalid}`), not a throw.** The
  query recompiles on every keystroke and nearly every prefix of a real pattern
  is broken on the way in (`(`, `[a`, a trailing backslash), so throwing would
  make the ordinary act of typing a regex an error path.
- **The bar says "bad pattern", not "no matches."** They are different answers,
  and reporting the second for `/a(` sends the user looking for the wrong
  problem.

Zero-width matches (`x*`, `^`, `\b`) match at every position and never advance
the cursor, so they are stepped over without being recorded — otherwise the hit
list fills with nothing-spans and the scan does not terminate.

**Known gap — a hard-wrapped match is invisible.** A query broken across a
render wrap is not present in any single row. Identical to
`links/detect-in-row`, and identical in cause: fullscreen pre-wraps because
`render-viewport!` addresses rows absolutely. `unwrapped-entry-text` would
find it, but there is no row to scroll to; the honest fix is the
logical-lines refactor described in CLAUDE.md, not this feature.

**Known gap — collapsed blocks.** Text inside a collapsed `[*Block:…*]` is not
in `!scrollback`, so it is not searched. Expanding blocks mid-search moves
every index after the splice under the anchor; worth its own increment.

## 2. Position is an INDEX; the offset is derived from it

The load-bearing decision, and the same one `viewport-anchor` already makes for
resize.

`:viewport-offset` counts ROWS BACK FROM THE TAIL. Every append moves the
tail, so the same offset lands on different text. `write-output!`
(layout.clj:684) resolves this today by snapping the offset to 0 on any
emit — which means **a search result is yanked back to live on the next
streamed chunk**, i.e. exactly when a long scrollback is worth searching.

So search stores `:cur-idx`, a scrollback index, and derives the offset:

```clojure
offset = clamp(total - cur-idx - k, 0, max-off)   ; k ≈ scroll-bottom/3
```

`k` seats the hit about a third down the region so there is context above and
below it, rather than pinning it to the top or bottom edge.

While search state is live, `write-output!`'s auto-snap is **suppressed** and
the offset is re-derived from `:cur-idx` after the insert instead. Appends land
at or after the viewed index, so re-deriving holds the text steady under the
user while the turn keeps streaming.

Esc does **not** jump to live. Throwing away the position the user just
searched for is the one thing cancel must not do; `End` is one keystroke away.

### Three events invalidate the hit list, and they want three different answers

Hits are scrollback indices, so anything that inserts, removes or re-wraps rows
invalidates them. Getting this wrong is quiet rather than loud — marks drift
onto text that does not match, and the anchor holds the viewport on the wrong
line — which is why it is three code paths and not one:

| event | response | why not the others |
|---|---|---|
| live-block tick | `shift-search!` — index surgery, no rescan | A streamed chunk is a splice. Rescanning a long scrollback at streaming rates is not affordable, and surgery is *exact* for what it covers: rows after the splice moved by a known delta, rows inside it were replaced so their hits are gone. It deliberately cannot see matches in the NEW rows — a block re-rendering under the reader must not renumber their `3/17` mid-navigation. |
| expand / collapse | `resync-search-after-splice!` — shift, then rescan | The rows an expand reveals are exactly the text that could not be searched a moment ago, which is what the user expanded the block for. |
| resize reflow | `rescan-search! :ordinal` | Every index moved, so matching on the old one is hopeless. But a resize changes where lines break, never what text exists — so the **Nth match is still the Nth**, which survives a rewrap that nothing index-based does. |
| session switch | per-tab save/restore beside `:viewport-offset` | Hits belong to one tab's rows. The incoming tab's search is installed (nil if it has none); the outgoing tab's is saved, so switching away and back does not lose a search in progress. |

Two rules the resync paths share. `shift-search!` **follows the current hit's
identity** rather than its ordinal, since an ordinal shifts whenever an earlier
hit is dropped. And neither path **re-anchors a search that had let go**:
`end-search-typing!` drops `:cur-idx` on purpose, and restoring it would
silently re-enable the auto-snap suppression after the user had left the bar.

## 3. Rendering the highlight

Two new theme tokens, bound exactly like `:link/target`:

```clojure
:search/match   [:reverse]
:search/current [:reverse :bold]
```

Same restriction, same reason: the span is inserted MID-ROW into text we do not
own, so only cleanly-toggleable mods are legal — a colour has no "off" and
ending it needs a `reset`, which discards the row's own styling.
`:reverse`'s off-code is 27 (`mod->off-code`), exactly reversible, which is why
reverse-video is the right default here and a background colour is not.

`search/highlight-row` is a direct sibling of `links/decorate-row*`: one walk
over the styled row tracking the plain index alongside it, inserting on/off only
between escapes, and **re-asserting `on` after every escape the row carries** —
a row's own SGR reset would otherwise drop the highlight partway through the
match. It preserves `fmt/display-width` exactly, which is the contract
`install-row-decorator!` already states.

No memo. Rows with no hit take a map lookup and return unchanged; only rows that
actually match pay for the walk.

### The three paint paths must all apply it

`render-viewport!`, `render-block-rows!` (live-block ticks) and
`write-output!`'s hardware-scroll path all paint rows. A row highlighted on one
and not another changes appearance the moment anything repaints it — the exact
bug the link decorator hit. Rather than remember this a third time, fold both
passes into one `finish-row [row sb-idx]` that all three call.

Ordering is fixed: **after the clamp, never before**. The clamp is a
width-aware truncate and can cut away a closing `27m`, leaving reverse-video on
for every row below it — SGR state survives a cursor move.

## 4. State

One key in `!layout`, beside `:collapse-highlight`:

```clojure
:search {:query   "TODO"
         :hits    [{:idx 412 :start 12 :end 16} …]   ; scrollback idx + PLAIN span
         :cur     3                                   ; index into :hits
         :cur-idx 412                                 ; the anchor (§2)
         :typing? true
         :stale?  false}
```

In `!layout` rather than a new atom because `render-viewport!` already reads
`:collapse-highlight` from it, on the same paint path, under the same lock, seen
by the same painted-row diff. A second source of truth the frame diff cannot see
is how rows go stale.

Code lives in a new `agent_tui/search.clj` — pure scan, pure span painter,
plus `reveal!`/`next!`/`prev!` — mirroring `links.clj`. Layout, terminal and
autocomplete get small hooks only.

## 5. Keys

Search is a **sub-mode of the existing readline loop** in `autocomplete.clj`,
checked before the `case` where `@paste-mode?` and the mouse-map interception
already sit. A nested read loop would have to re-implement paste, mouse, SIGINT,
resize and the pending-feedback interception.

`Ctrl-F` is byte 6, currently unmapped in `read-key!` — so today it falls
through to the printable branch and inserts a raw control character into the
input line. Mapping it to `:ctrl-f` incidentally fixes that.

**↑/↓ arrive as `:scroll-up` / `:scroll-down`.** `read-key!` maps `ESC[A`/`ESC[B`
that way because alternate-scroll sends them for the wheel, and with mouse
reporting on the wheel arrives as SGR 64/65 and is mapped to the same two
keywords. They are indistinguishable by construction, so in search mode both the
arrows and the wheel step hits. That is what was asked for on the keyboard side
and is defensible on the wheel side.

While search is active every other key is consumed — no half-live editing
underneath a modal bar.

**Interlock with marker mode:** entering search clears `selected-mark` /
`:collapse-highlight`; Tab (marker cycling) exits search. Two reverse-video
highlights on screen at once is not a state worth having.

**Highlights outlive the mode; the ANCHOR does not.** Enter keeps the marks
painted so the user can type their follow-up with the match still visible, but
drops `:cur-idx` — they have just gone back to the input line to submit
something, and an anchor that survived would go on suppressing the auto-snap,
leaving them watching an old match while the reply streamed past underneath.
Marks are cleared by Esc, a new search, a session switch, or returning to
live — not by ordinary emits.

## 6. The search bar

The **input line** becomes the bar, mirroring the pending-feedback prompt:
`redraw-input-line!` already selects its prompt from
`tui-session/feedback-prompt-parts`, so a search prompt takes precedence the
same way, with the query as the buffer. The in-progress input is saved and
restored on exit, exactly as history navigation already does with `saved-inp`.

The prompt **must be 2 visible columns** — `redraw-input-line!` hard-codes
`prompt-w 2` and all its cursor math depends on it. `"/ "` fits.

The **separator row** carries the counter, replacing its scroll indicator while
search is active:

```
─── ⌕ "config" — 3/17 · ↑↓ next · Enter keep · Esc cancel ───
```

Natural division: the separator says where you are (it already does), the input
line takes what you type (it already has the cursor and the width-aware
redraw).

Wrapping past the last hit returns to the first **with a muted one-line
notice**. Wrapping silently is how a user concludes the search is broken.

## 7. Not doing

- **No config key.** Keyboard-only, no cost when unused; `:enable-mouse` exists
  because mouse reporting takes drag-select away from the terminal, and search
  takes nothing.
- Fullscreen only. Inline mode has no row model to address, and `by ask` has no
  viewport — same boundary every other viewport feature draws.

## 8. Phasing (all shipped)

1. **Feature.** `search.clj`, `:search` state, `finish-row` unification across
   the three paint paths, `:ctrl-f`, the key sub-mode, the bar, seat/next/prev,
   and the auto-snap suppression from §2. The last was not optional — without
   it search is unusable during a turn.
2. **Durability.** The four resync paths above.
3. **Polish.** Wrap notice, marker-mode interlock, `/`-prefixed regex.

### Still open

- A hard-wrapped match is invisible (§1) — blocked on the logical-lines
  refactor, not on this feature.
- Collapsed-block text is not searched until the block is expanded. Expanding
  *during* a search now resyncs correctly, so auto-expanding to search inside
  blocks is a reachable increment.
- `scan` strips ANSI per row on every keystroke. Fine at the scrollback sizes
  measured; if it ever bites, memoise the strip on the row string the way
  `links/decorate-row` memoises decoration.

## 9. Tests

Following `mouse_test.clj` / `resize_reflow_test.clj`:

- `scan` — all hits per row, smart-case both ways, correct plain spans on
  ANSI-carrying rows, zero hits on an empty query.
- `highlight-row` — `display-width` unchanged (the decorator contract),
  re-asserts after an embedded reset, never splits an escape, closes at row end.
- `reveal!` — hit near the head, near the tail, and a scrollback shorter than
  the region (clamping at both ends).
- Anchor durability — holds across an append (auto-snap suppressed), re-seats
  across a splice, re-seats to the nearest hit across a resize reflow.
- Wrap notice fires exactly at the boundary, not one hit early or late.
