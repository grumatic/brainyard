# Prompt-Cache Arrangement — coact-agent context layout by update frequency

> Status: **investigation + plan** (2026-07-04). Companion to
> `context-management.md` §P3.3/§P4 — this doc records what actually landed
> (M7 zones), maps every prompt surface to its update cadence, and plans the
> next round of cache-hit improvements.

## 1. As-built pipeline (verified against code)

> **Superseded in part (2026-07-04):** this section records the layout as
> investigated, BEFORE Phases 1-4 landed. Current state: three ordered system
> zones `## agent-core` / `## session-context` / `## user-context` (see
> `coact-system-zones`), declared-order user-message rendering with a
> stable-prefix breakpoint before `iterations`, and an opt-in 1h `:cache-ttl`.
> The mechanics below (zone derivation, provider adapters, index-of splitting)
> are otherwise unchanged.

```
coact-init-action (once per turn)
  CoActAssembler.sections  →  {section-kw text}          coact_agent.clj:1388-1417
  cb/enforce (budget)      →  compacted sections
  cb/compose sys-order     →  :system-context  string    coact_agent.clj:1735
  cb/compose usr-order     →  :user-context    string    coact_agent.clj:1736
        ↓ st-memory
BT dspy node  :stable-keys #{:system-context :user-context}   coact_agent.clj:4435
        ↓
dspy-action build-system-prompt                          dspy_action.clj:86-122
  – wraps each stable key as "## <key-name>\n<text>"
  – **sorts keys ALPHABETICALLY**
  – emits :zones [{:key :text} …]  (one candidate breakpoint per key)
        ↓
predict / chain-of-thought                               predict.clj:73-78
  system message = [DSPy signature preamble] ++ "\n\n" ++ [zone texts]
  user   message = signature inputs in HASH-SET order (see NB below)
                   ++ output reminder                    prompt.clj:141-148
        ↓ provider adapters
Anthropic  (llm.clj:370-434)   :prompt-cache default true (providers.clj:25)
  system → content array; cache_control {ephemeral} ON EACH ZONE block;
  preamble = leading uncached block. User message: NO cache_control.
Bedrock    (bedrock.clj:108-190)  :prompt-cache default true
  zones → {:text}+cachePoint blocks, ≤3 system points (cap 4 total);
  PLUS an unconditional trailing cachePoint on the last user message.
OpenAI-compatible: zones ignored; relies on automatic prefix caching
  (stable ordering is the only lever).
```

The resulting request layout for the main CoAct loop:

```
─── system message ─────────────────────────────────────────────
  DSPy preamble: input/output field docs + JSON schema + ThinkActCode
                 instructions              ← stable per agent version
  ## system-context                        ← ZONE 1 (cache_control)
     20 sections, sys-order:               coact_agent.clj:1405-1412
     role, system-info, execution-model, channel-routing,
     tool-call-format, code-blocks-format, sandbox-context-accessor,
     tools, critical-rules, large-results-playbook, instruction,
     agent-context, project-instructions, project-memory,
     skill/mcp/todo/exec/subagent substrates, user-instructions, footer
  ## user-context                          ← ZONE 2 (cache_control)
     turn-info, parent-trail, conversation-history,
     previous-turns, live-artifacts
─── user message ───────────────────────────────────────────────
  recalled-memory:  <per-turn stable — primed pre-init>
  question:         <per-turn stable>
  iterations:       <PER-ITERATION, append-only, compacted under budget>
  context-briefing: <per-turn stable — built once at init, :1743/:1769>
  output reminder   <constant>
─────────────────────────────────────────────────────────────────
```

**NB the user-message field order above is HASH-SET order, not declaration
order**: `compile-signature` stores `:input-keys` as `(set (keys inputs))`
(signature.clj:14), dspy-action reduces over that set into the inputs map, and
`build-user-message` iterates the map. The order is deterministic for a fixed
key set (hence still cacheable) but semantically arbitrary — the volatile
`iterations` lands mid-message, ahead of the turn-stable `context-briefing`.
(Verified empirically: declared `[:question :context-briefing :recalled-memory
:iterations]` renders as `[:recalled-memory :question :iterations
:context-briefing]`.)

Supporting facts:

- Usage already parses `cache_creation_input_tokens` / `cache_read_input_tokens`
  (llm.clj:484-485) and usage.clj has cache-read/write pricing — the raw data
  for measuring hit rates exists.
- Mid-turn, `coact-rebudget-action` (coact_agent.clj:2253) recomposes BOTH
  strings every `:rebudget-every-n-iter` (default 10) iterations; compaction
  strategies only mutate zone-2 state (previous-turns / conversation /
  artifacts / iterations) plus the `:tools-tier` ladder which rewrites the
  `:tools` section inside zone 1.
- `build-anthropic-system-blocks` / `system-blocks-from-zones` locate zone text
  by `str/index-of` and **silently** fall back to the uncached / single-point
  form when not found.

## 2. Update-frequency map

| Cadence | Sections / fields | Rides today |
|---|---|---|
| **Static per agent version** | DSPy preamble (schema+instructions), role, execution-model, channel-routing, tool-call-format, code-blocks-format, sandbox-context-accessor, critical-rules, large-results-playbook, skill/mcp/todo/exec/subagent substrates, footer | preamble (uncached lead) + zone 1 |
| **Session-stable** (changes on model switch, cd, file edit, L1 overlay write, tool-registry change, tools-tier compaction) | system-info, tools, instruction, agent-context, project-instructions (BRAINYARD.md), project-memory, user-instructions | zone 1 |
| **Per-turn, iteration-stable** | turn-info, parent-trail, conversation-history, previous-turns, live-artifacts — and, in the user message: question, context-briefing, recalled-memory | zone 2 / user message |
| **Per-iteration** | iterations (append-only; collapsed under budget), output reminder (constant but positioned after iterations) | user message |

Cache-hit profile today:

- **Iterations 2..N within a turn**: zones 1+2 hit (90% discount on the whole
  system message). The user message — question + briefing + recalled-memory +
  full iteration history — is re-billed at full input price every iteration on
  Anthropic/Bedrock. Recalled-memory and the iteration history are routinely
  the largest per-iteration payloads.
- **Across turns with < 5 min gap**: zone 1 hits; zone 2 re-written once.
- **Across turns with > 5 min gap** (the *normal* interactive-TUI cadence):
  everything expires — the 5-minute ephemeral TTL means human-paced sessions
  get **zero** cross-turn caching.
- **Bedrock only**: the unconditional trailing cachePoint on the last user
  message writes a new checkpoint every iteration (25% write premium on the
  user segment) and essentially never gets read back, because the user message
  tail (`iterations` + reminder) differs on every call.

## 3. Gaps

| # | Gap | Impact |
|---|---|---|
| G1 | Zone order is **alphabetical coincidence** — `(sort stable-keys)` at dspy_action.clj:104 happens to put `:system-context` before `:user-context`. Any third stable key (e.g. `:agent-core`) silently reshuffles the prefix. | correctness landmine |
| G2 | Zone 1 mixes static-per-version and session-stable cadences. A BRAINYARD.md edit, `memory$*` project-memory write, L1 overlay write, or tools-tier compaction invalidates the *entire* system prefix including ~10-20K tokens of byte-stable prose. | cross-turn misses |
| G3 | No breakpoint after the turn-stable user-message prefix (question / context-briefing / recalled-memory). Re-billed every iteration on Anthropic/Bedrock. | per-iteration cost |
| G4 | Bedrock's reserved 4th cachePoint sits at end-of-user-message where it never hits; pays write premium each iteration. | negative-value writes |
| G5 | No TTL control. 5-minute ephemeral kills cross-turn caching for human-paced TUI sessions — the dominant usage mode. | biggest real-world miss |
| G6 | No observability: the `::cache-prefix-hash` validation proposed in context-management.md §P3.3 was never implemented; zone-location failures fall back **silently**; /usage doesn't surface cache-read/write per turn. | can't measure or regress-guard |
| G7 | **Previous-turns history is re-billed wholesale every turn.** The chain (`previous_turns.clj`) is nearly append-only — appended most-recent-last, position-stable `[Turn N]` headers — but rides zone C, which is rewritten per turn. The payload grows monotonically with session length. Three byte-stability breakers: (a) progressive compression slides the `:full`→`:summary` (recency 10) and `:summary`→`:minimal` (recency 40) boundaries by one **every turn**, rewriting a mid-chain entry each time; (b) head drops (`:bump-previous-turns`, max-turns trim) renumber all `[Turn N]` headers; (c) per-turn `:turn-info` renders FIRST in usr-order, ahead of the history. | dominant cross-turn cost in long sessions |
| G9 | **Q/A double-render across conversation-history and previous-turns.** Every completed turn's question+answer appears verbatim in BOTH the cached `:history-context` zone (previous-turns `[Turn N]` entries, Q≤500 + A≤1000 chars) and the volatile conversation window (`You:/Agent:` lines ≤500 chars, re-billed every turn). Measured live: conversation-history ~1.2–1.4K tok/turn, almost entirely duplicate. | ~1.2–2.5K volatile tok/turn |
| G8 | **User-message field order is hash-set order, not declared order.** `:input-keys` is a `set` (signature.clj:14); the rendered order is deterministic per key set but arbitrary — today `iterations` (per-iteration volatile) renders *before* the turn-stable `context-briefing`, re-billing it every iteration even under automatic prefix caching, and any input-key change can silently reshuffle the whole message. Also a hard blocker for G3's stable-prefix breakpoint. | per-iteration waste + reshuffle landmine |

## 4. Improvement plan

Ordered so each phase is independently shippable; Phase 0 first so every later
phase has before/after numbers.

### Phase 0 — Measure (S) — **LANDED 2026-07-04** (except baseline capture)

- ✅ `mulog ::cache-zones` at each dspy call: per-zone key + char size +
  16-hex SHA-256 prefix (`log-cache-zones!` in dspy_action.clj); the
  provider's returned cache-read/write tokens ride `::dspy-completed`.
- ✅ `/usage` already had per-call CacheR/CacheW columns + hit rate; added a
  `Cache net saved` estimate line (`estimated-cache-net-savings` in
  tui/format.clj — per-call input rate derived from cost, no pricing import).
- ✅ `mulog/warn ::cache-zone-fallback` on the silent zone-location fallback
  in both `build-anthropic-body` (llm.clj) and `build-system-blocks`
  (bedrock.clj) (G6).
- ✅ First Bedrock baseline (2026-07-04, `bedrock/apac.amazon.nova-lite-v1:0`,
  2×`ask` + a 2-turn TUI session):
  - Zone sizes: `agent-core` 20,588 chars (byte-identical sha across
    processes AND across turns), `session-context` 16,664 chars
    (byte-identical across turns within a session; differs per process —
    session id), `user-context` 657→844 chars (+26 tok/turn on trivial
    turns). Zero `::cache-zone-fallback` events.
  - The cached static segment (DSPy preamble + `agent-core`) measured
    8,424 tokens via an observed cross-process read hit.
  - `/usage` renders CacheR/CacheW per call, hit rate, and the net-saved
    line as designed.
  - **Operational caveat discovered:** cross-region inference profiles
    (`apac.`/`us.` Nova rewrites) defeat Bedrock cache locality — the
    prompt cache is region-scoped, and consecutive byte-identical requests
    can route to different regions (observed: turn-2 CacheR=0 despite
    sha-identical zones). Hits are probabilistic under a profile; pin a
    single-region model id when cache economics matter.
  - Known gap: ephemeral `ask` processes exit before the mulog publisher
    flushes the API-result event — per-call cache tokens for `ask` are
    only visible via the provider console, not the app log. TUI sessions
    flush fine.
  - **Phase-3b live validation** (second 2-turn TUI session, same model):
    turn 1 emitted 2 zones (blank history zone correctly skipped); turn 2
    emitted 3 — `history-context` (106 chars) appeared while
    `agent-core`/`session-context` shas stayed byte-identical, and
    `user-context` no longer claims a breakpoint. Cross-turn economics
    (same-region routing this time): turn 2 read **13,524** tokens from
    cache and wrote only **1,584** — cost $0.0012 → $0.0003 (−75%),
    latency 1.8s → 1.2s.
- **TTL-flip experiment (2026-07-05,
  `bedrock/global.anthropic.claude-haiku-4-5-20251001-v1:0`,
  ~7m15s gaps between turns; `ttl "1h"` presence verified in request
  logs):**
  - Control (5m default): post-gap turn read 0, re-wrote 15.8K — the 5m
    expiry problem, reproduced.
  - 1h leg #1: post-gap turn read 0 (see routing caveat below).
  - Routing baseline: 7/7 quick-succession calls hit the same region's
    cache (incl. cross-session) — the global profile's routing is stable
    but not perfectly so.
  - **1h leg #2: post-gap turn read back EXACTLY the 1h-marked
    checkpoint's span (8,895 tok) and re-wrote exactly the 5m-marked
    segments (6,961 tok)** — the 1h/5m split behaving to spec; the quick
    third turn then hit everything (14,134). Verdict: **the 1h TTL works
    on Bedrock Claude**; leg #1's miss is attributed to an occasional
    cross-region routing flip (a flip costs one full re-write — the same
    as today's every-gap behavior, so 1h's downside is only the write
    premium on the long-TTL zones).
  - Pricing caveat: usage.clj's cache-write rates don't distinguish a 1h
    write premium on Bedrock (Anthropic-direct bills 1h writes at 2x base
    vs 1.25x for 5m). Verify against a real bill before trusting the
    /usage net-saved estimate with 1h on.
- ⬜ Anthropic-direct (API-key or anthropic-max OAuth) confirmation + a
  longer (10-turn, multi-iteration) session still pending. The
  `BY_CACHE_TTL=1h` default-flip decision for Claude-family models is now
  data-backed on Bedrock; flipping requires per-model gating (never send
  `ttl` to Nova — 5m-only).

### Phase 1 — Explicit zone order (S) — fixes G1 — **LANDED 2026-07-04**

- ✅ `:stable-keys` accepts an **ordered vector** (`normalize-stable-keys` in
  dspy_action.clj; legacy sets still render alphabetically). Declared order
  contract: **ascending volatility**, most-stable first.
- ✅ All three CoAct/ReAct BT nodes now pass
  `[:system-context :user-context]`; hook events carry the normalized
  vector. Ordering tests added to `dspy_action_test.clj`
  (`cache_breakpoints_test.clj` unchanged — zone consumption is
  order-agnostic downstream).

### Phase 2 — User-message stable-prefix breakpoint (M) — fixes G3 + G4 + G8 — **LANDED 2026-07-04**

- dspy-action already knows which inputs are turn-stable (everything except
  `:iterations`). Emit a `:user-cache-boundary` marker (char offset or split
  texts) alongside `:cache-zones`.
- Anthropic: render the user message as two content blocks —
  `[question+briefing+recalled-memory](cache_control)` + `[iterations+reminder]`.
- Bedrock: **move** the existing trailing cachePoint to that boundary (stops
  the every-iteration dead write; same 3+1 budget).
- **Step 0 — ordered input rendering (fixes G8, hard prerequisite).** The
  user message currently renders in hash-set order because `compile-signature`
  stores `:input-keys` as a `set`. Preserve declaration order: keep an ordered
  `:input-order` vector on the compiled signature (the `:inputs` map literal is
  an array-map for ≤8 entries, so `(vec (keys inputs))` is declaration order —
  add a compile-time assert for the >8 cliff), and have `build-user-message` /
  `collect-user-parts` render by it. Contract: declare inputs in **ascending
  volatility** — `ThinkActCode` already declares `:iterations` last, so the
  layout becomes question, context-briefing, recalled-memory, iterations.
  No ordered-sections machinery is needed on the user side: four fields with a
  single stable/volatile split — an ordered key vector is sufficient.
- Guard: skip the marker when the stable prefix is trivially small
  (< ~1K tokens ≈ provider minimum cacheable prefix) to avoid wasting a slot.
- Expected win: (question+briefing+recalled-memory) × (iterations−1) × 90%
  per turn; recalled-memory alone is often 1-4K tokens.

**As landed:** `compile-signature` records `:input-order` (declaration order;
>8 inputs must pass `:input-order` explicitly or it throws);
`build-user-message`/`collect-user-parts` render by it; the BT node opt
`:user-cache-boundary :iterations` (all three CoAct ThinkActCode nodes) flows
through predict/CoT as `:user-cache-prefix` — prompt.clj emits it only when
the stable prefix ≥ 4000 chars (`min-user-cache-prefix-chars`). Anthropic
splits the last user message into `[prefix (cache_control)][tail]`; Bedrock
moves its reserved user cachePoint to that boundary (trailing-point fallback
+ `::cache-zone-fallback` warn when the prefix doesn't match).

### Phase 3 — Three-zone system split (M) — fixes G2 — **LANDED 2026-07-04**

Split `:system-context` into two stable keys so the system message carries
three zones ordered by ascending volatility:

```
ZONE A  :agent-core     static per agent version
        role, execution-model, channel-routing, tool-call-format,
        code-blocks-format, sandbox-context-accessor, critical-rules,
        large-results-playbook, skill/mcp/todo/exec/subagent substrates, footer
ZONE B  :session-context  session-stable
        system-info, tools, instruction, agent-context,
        project-instructions, project-memory, user-instructions
ZONE C  :user-context     per-turn (unchanged)
```

- `:tools` moves to ZONE B: its two change triggers (registry change,
  tools-tier compaction) are session-cadence, so a change no longer invalidates
  the big static-prose prefix.
- Note `:footer` moves from prompt-end into ZONE A — a semantic reordering
  (project/user instructions will now render after it). Verify with an eval
  pass; if the footer's "final reminder" position matters, keep it at the end
  of ZONE B instead (costs re-billing its ~100 tokens on session-var changes —
  negligible).
- Budget check: Bedrock 3 system cachePoints = its cap exactly; Anthropic
  3 system + 1 user (Phase 2) = 4 = its cap exactly. Any future per-agent
  overlay zone must displace one of these — document the cap as consumed.
- Implementation: CoActAssembler grows a second system slot
  (`system-core-order` / `system-session-order` or a slot-tag per section);
  init composes three strings; `:stable-keys [:agent-core :session-context
  :user-context]` (ordered, per Phase 1).

**As landed:** `SectionAssembler` gained a `system-zones` method returning the
ordered `[[stable-key section-kws] …]` partition; `coact-system-zones` is the
single source of truth and `system-order` derives from it. Init/rebudget
compose one string per zone into st-memory keys `:agent-core` /
`:session-context` / `:user-context` (`:system-zone-orders` replaces
`:sys-order` in the rebudget stash). `:footer` stayed at the very end (zone B)
rather than moving mid-prompt — no semantic reordering risk for ~100 tokens of
session-zone exposure. Zone budget: Bedrock 3/3 system points, Anthropic
3 system + 1 user = 4/4.

**Bug found & fixed during this phase:** the flat `system-order` was missing
the five base substrate sections (`:skill-substrate :mcp-substrate
:todo-substrate :exec-substrate :subagent-substrate`) — `cb/compose` silently
drops sections absent from the order, so the substrates were built by
`coact-system-context` but **never rendered into the live prompt** (their
unit tests passed because they inspected the section map, not the composed
string). They now render in the `:agent-core` zone, and a partition-invariant
test (zones ⊆⊇ system-order; every built section covered) guards the class.

### Phase 3b — Append-only history zone (M) — fixes G7 — **LANDED 2026-07-04**

The advancing-breakpoint-over-chat-history pattern, applied to zone C. Only
worth shipping **after Phase 4** (without a 1h TTL, cross-turn hits rarely
survive the human gap between turns).

**As landed (deviations from the sketch below):**

- **C1 = `:history-context` = `previous-turns` ONLY.** `conversation-history`
  turned out to be a SLIDING window (`prepare-conversation-action` re-snapshots
  `take-last :conversation-limit` session messages every turn), so its head
  churns in long sessions — it stays in the volatile tail. Previous-turns is
  the append-only (and much larger) payload anyway.
- **C2 stays under the `:user-context` stable key** (turn-info, parent-trail,
  conversation window, live-artifacts), rendered after `:history-context` via
  the new dspy `:no-zone-keys` node opt — in the system text but with NO
  breakpoint of its own; the Phase-2 user-message marker covers it.
  Breakpoints: A, B, C1 = 3 system (Bedrock cap) + 1 user (Anthropic 4/4).
- **Batched demotion shipped as `:demote-batch`** (default 10) in
  `append-turn`: boundaries move in batches, so with the defaults the
  10–19 most recent turns hold `:full` and crossing 20 demotes ten at once.
  `:demote-batch 1` reproduces the old slide-by-one behavior. Tests cover
  byte-stability of demoted entries across a batch window and
  re-compression idempotence (the truncate-to-file concern).
- **Caveat:** when the Phase-2 user breakpoint is skipped (turn-stable user
  prefix < 4000 chars), C2 + the user message re-bill per iteration —
  previously zone C had its own marker. Live-artifact-heavy turns with tiny
  questions are the exposure; revisit the guard threshold if Phase-0 numbers
  show it firing often.

Split zone C by cadence:

```
ZONE C1  :history-context   append-only across turns (breakpoint at end)
         conversation-history, previous-turns
ZONE C2  turn-volatile — NO own breakpoint; covered by the Phase-2
         user-message breakpoint (same cadence: per-turn, iteration-stable)
         turn-info, parent-trail, live-artifacts
```

- Breakpoint budget holds: A, B, C1 = 3 system points (Bedrock cap exactly);
  the Phase-2 user breakpoint covers C2 + question/briefing/recalled-memory =
  4 total (Anthropic cap exactly).
- **usr-order change**: `:turn-info` moves from first to after the history
  block (into C2). Today its per-turn timestamp sits ahead of the history,
  which also defeats OpenAI automatic prefix caching over zone C.
- **Cache-aware compression cadence** in `append-turn`: replace the
  slide-by-one depth boundaries with batched demotion + hysteresis (e.g. hold
  10–19 recent turns at `:full`, demote ten at once when crossing 20). A
  mid-chain rewrite then happens once per ~10 turns instead of every turn;
  between demotions the chain is byte-append-only and the C1 prefix hits.
- Cross-turn re-bill after this phase: new history entry + C2 + user stable
  prefix (+ a batch-demotion tail once per ~10 turns) — instead of the entire
  history every turn.
- Verify: `truncate-to-file` idempotence on re-compression (`append-turn`
  re-runs compression over all entries each call; the second pass must be a
  no-op — if it ever re-emits a fresh `--- TRUNCATED` file path the whole
  chain goes volatile). Add a unit test asserting entries older than the
  demotion boundary are byte-identical across consecutive `append-turn` calls.
- Accepted misses: head drops under budget pressure renumber `[Turn N]`
  headers → one full C1 re-write; rare, and at that point the session is at
  its context ceiling anyway.

### Phase 4 — TTL knob (S) — fixes G5, likely the biggest real-world win — **LANDED 2026-07-04** (opt-in)

- ✅ lm-config `:cache-ttl` (`"5m"` default | `"1h"`), passed through
  `create-lm`. Anthropic: `cache_control {:type "ephemeral" :ttl "1h"}` on
  every zone EXCEPT the last (the per-turn zone — longer TTL there only buys
  write premium); longest-TTL-first ordering holds by construction; the
  `extended-cache-ttl-2025-04-11` beta header is sent whenever 1h is used.
- ✅ **Bedrock too (2026-07-05, corrects the earlier "no-op" note):** the
  Converse `CachePointBlock` carries `:ttl {:shape "CacheTTL"}` with enum
  `["5m" "1h"]` — verified in the pinned cognitect `bedrock-runtime
  871.2.42.29` schema, so no silent-strip. `system-blocks-from-zones` now
  emits `{:cachePoint {:type "default" :ttl "1h"}}` on all system zones
  except the last; the last zone and the user-prefix point stay 5m.
  Model support is per-model (Anthropic Claude models on Bedrock; Nova is
  5m-only — expect a validation error or ignore on unsupported models).
- ✅ `BY_CACHE_TTL` env/dotenv knob wires the value into the TUI session LM
  (`setup-lm!` in agent-tui helpers).
- ✅ **OpenAI (2026-07-05):** caching there is automatic prefix-matching, so
  the zone/ordering work IS the optimization (no markers). Two params wired
  into `build-openai-body`, gated to `#{:openai :azure}` (OpenAI-compatible
  third parties never receive them): `:cache-ttl` beyond "5m" →
  `prompt_cache_retention "24h"` (extended retention, same price — already
  the server default for non-ZDR orgs on gpt-5.x/gpt-4.1), and
  `:prompt-cache-key` → `prompt_cache_key` (session-stable routing key,
  combined with the prefix hash; the TUI sets `by-<uuid>` per process).
  OpenAI has no write premium, so there is no flip trade-off at all there.

**TTL default-flip matrix (as measured):** OpenAI — effectively already on
(24h server default, free); Bedrock Claude — works (proven leg #2), opt-in
via `BY_CACHE_TTL=1h` recommended for interactive sessions, needs per-model
gating before any hard default (Nova is 5m-only); Anthropic direct /
anthropic-max — implemented, unmeasured (needs a one-time `/login
anthropic`).
- ⬜ Default policy (`1h` for interactive root agents, `5m` for subagents)
  deliberately NOT wired — 1h write premium is 2× base input, so flipping the
  default is a billing-behavior change; decide after the Phase-0 baseline.
  Users opt in via `:cache-ttl "1h"` in their `:lm-config`.

### Phase 5 — Regression guards + docs (S) — **LANDED 2026-07-04** (guards)

- ✅ Zone partition invariant test: `system-zones` concatenates to exactly
  `system-order`, no duplicates, disjoint from `user-order`, and every
  section the builder emits is covered by a zone (this is the guard that
  would have caught the dropped substrates).
- ✅ Byte-stability test: same assembler state → byte-identical zone strings
  (the string-level form of the §P3.3 prefix-hash assertion).
- ✅ Volatility scan: `build-system-info-section` output contains no ISO
  wall-clock timestamp (would invalidate the cross-turn cache every turn).
- ⬜ `context-management.md` §P3.3/§P4 status refresh (this doc is the
  authoritative record meanwhile).

### Phase 6 — Conversation timeline (Q/A dedup) — fixes G9 — **LANDED 2026-07-05**

The conversation window becomes a session TIMELINE instead of a second copy
of the transcript: completed own-turn Q/A pairs older than the
`:conversation-keep-verbatim` most recent (default 2) collapse to
`[Turn N] → see Previous Turns` references (adjacent refs merge to ranges);
sub-agent messages, system/wakeup notes, and the last K exchanges stay
verbatim. Previous-turns (cached, append-only) is the single Q/A store; the
volatile window drops from ~1.2–2.5K tok to ~100–300.

Design points:
- **Hybrid by design**: the K-verbatim tail preserves the recency anchor for
  pronoun-style follow-ups ("make it shorter") — the main behavioral risk of
  a pure-ref window. `:conversation-style "full"` restores the legacy window.
- **Turn-answer vs dispatch disambiguation**: both land in the shared session
  as self-tagged assistant messages, so `session/assistant-message` grew a
  `:kind` (`:turn-answer` at ask-completion, `:dispatch` at sub-agent input
  recording). Pre-tag persisted sessions use the structural fallback: within
  a user-bounded segment, the LAST untagged self assistant is the answer
  (dispatches always precede it).
- **Tail-aligned numbering**: the window's last completed unit maps to the
  chain's last entry, so refs match the rendered `[Turn N]` headers even
  after chain trims; if deep compaction leaves the chain shorter than the
  window's units, the transform backs off to the verbatim window.
- Drill-down path for refs: the Previous Turns section itself, or
  `(context-get [:previous-turns])` in the code channel. (NB the
  previously-documented `get-previous-turn` sandbox fn never existed —
  stale docstring, now fixed.)
- ⬜ Phase-3 live continuity A/B (K=0 vs K=2 vs full) still to run.

## 5. Priority / effort summary

| Phase | Effort | Impact | Notes |
|---|---|---|---|
| 0 Measure | S | enables the rest | **DONE** (baseline capture pending) |
| 1 Ordered zones | S | hardening | **DONE** — prerequisite for 2/3 |
| 4 TTL | S/M | **high** (human-paced TUI) | **DONE** (opt-in `:cache-ttl`; default flip pending baseline) |
| 2 User-prefix breakpoint | M | high (multi-iteration turns) | **DONE** — step 0 fixed hash-order rendering (G8); Bedrock dead writes removed |
| 3 Three-zone split | M | medium (edit-heavy sessions) | **DONE** — caps now 4/4 on both providers; also restored the silently-dropped substrate sections |
| 3b History zone | M | high (long sessions) | **DONE** — C1 = previous-turns only (conversation is a sliding window); batched `:demote-batch` compression; caps hold at 4/4 |
| 5 Guards + docs | S | keeps it won | **DONE** (guards; context-management.md refresh pending) |
| 6 Conversation timeline | M | high (~1.2–2.5K volatile tok/turn) | **DONE** — hybrid refs + K=2 verbatim tail; live continuity A/B pending |

## 6. Risks & constraints

- **Breakpoint caps are fully consumed** after Phases 2+3 (Anthropic 4/4,
  Bedrock 4/4). Gate the user-message breakpoint behind config so a future
  per-agent overlay zone can reclaim a slot.
- **Minimum cacheable prefix** (1024-4096 tokens depending on model): small
  zones silently don't cache but still consume a slot — the Phase 0 size
  logging tells us whether ZONE A/B ever fall under it (they won't for coact;
  they might for slim derived agents).
- **`str/index-of` zone location** stays fragile across recomposition; Phase 1
  should pass zone texts explicitly end-to-end rather than re-locating them in
  the joined string (the fallback warn from Phase 0 catches any residue).
- **Semantic reordering** in Phase 3 (footer/tools positions) can shift model
  behavior independently of caching — run the standard agent eval before/after.
