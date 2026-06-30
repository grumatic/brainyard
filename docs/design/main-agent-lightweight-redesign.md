# Main-Agent — Lightweight, Hook-Derived Redesign (revision 3)

> **Status:** Proposal. Applies the [series](./agent-lightweight-redesign-synthesis.md)
> argument to the **front-door router**. Main-agent is the root agent: it doesn't
> author a plan/edit/verdict and it doesn't drive a research arc — it *routes*
> each user turn to the right specialist (or answers inline). So the
> judgment-vs-mechanism split lands almost entirely in its favor.
>
> Two things are already true and stay true: main-agent **already binds the file
> tools** (no `remove` clause) and is **already hook-driven** (pointers.md is
> auto-captured by a `:agent.tool-use/post` hook; the session index by a
> `:agent.session/closed` hook). Its core work — *routing by question shape* — is
> pure LLM judgment. The only thing worth changing is the one place it still
> *constructs* a structured artifact: the **routing-log line**. The redesign
> moves that from an LLM-facing constructor to a **turn-derived hook**.
>
> **Scope:** `components/agent/src/ai/brainyard/agent/common/main_agent.clj`,
> `main.clj`. Naming aligns with the adopted `edit-agent` rename.

---

## 1. Why the current path is (mildly) error-prone

Be honest about the magnitude: main-agent is the **least brittle** agent in the
set, because most of its bookkeeping is already hooks + read seams. There is
exactly one authoring micro-tool left, and one instruction pressure:

- **`main$append-log`** makes the model construct a per-move line —
  `{:turn :iter :question :shape :routed-to :artifact :reason}` — and validates
  `:shape` against a **21-element enum**. It's a *flat* map (far less brittle than
  plan/exec's nested ones), but it's still "assemble a structured object every
  turn for a renderer," and the 21-way `:shape` keyword is a classification the
  model must spell exactly right or the call rejects.
- The instruction leans on it hard: *"The log is the contract with the next
  turn. Failing to update it makes resume-shaped questions ambiguous,"* and
  *"log it anyway with `routed-to: nil`."* That's the same per-turn-obligation
  pressure the other docs flagged — and the same failure mode (a smaller model
  skips it, and continuation detection silently degrades).

Everything else main-agent does is already on the right side of the line:
`pointers.md` is auto-captured by a hook; the session index is hook-written; the
sibling read-helpers and `main$session-id`/`resume?`/`last-shape` are pure
mechanism. So the redesign is small and surgical — but it removes the *last*
per-turn structured-authoring obligation from the root agent, which matters
because the root agent runs on every single turn.

## 2. Thesis

Main-agent already does the hard part right: **routing is judgment**, performed
by reading the question (and, when relevant, sibling dossiers) and choosing a
move. Keep that untouched. Then make the one change that fits the series:

> **The routing log is observation, not authoring.** Most of a routing-log line
> is *observable from the turn itself* — which specialist was dispatched, what
> `Saved <kind>:` artifact came back (already hook-captured), the user's
> question. Derive the line in a **hook**, not via an LLM-constructed map.

The model keeps doing the judgment (pick the move, state a one-line reason — which
it already surfaces to the user). The machine records the structured trail. This
is the main-agent analog of eval's "scoring is judgment" and research's
"orchestration is judgment": the *deciding* is inherent capability; only the
*logging* was over-tooled.

In one line, the series refrain, specialized: **route in prose; record the route
with a hook; keep reads typed.**

## 3. Design principles

1. **Routing is judgment — untouched.** The §6 decision table (21 shapes → moves)
   and direct kebab-case dispatch stay exactly as they are.
2. **The routing log is hook-derived.** A `:agent.ask/finalize` (or
   `:agent.tool-use/post`) hook records the per-turn line from the dispatched
   specialist + captured artifact + the shape/reason the model states in its
   answer. No `main$append-log` map construction.
3. **Reads stay deterministic.** `main$session-id` (runtime accessor),
   `main$resume?`, `main$last-shape`, and the five sibling read-helpers are pure
   mechanism — the inputs to routing decisions. All stay.
4. **Hooks already own the rest.** `pointers.md` (auto-capture) and the session
   index stay hook-written. The redesign extends that pattern to the routing
   line; it does not invent new machinery.
5. **Shape is mostly derivable.** For specialist moves, `:shape` is a function of
   the routed-to agent (plan-agent → `:plan-author`, exec-agent → `:execute`, …).
   The hook derives it; the model only needs to signal shape for *self-answered*
   moves (no dispatch to infer from).
6. **Degrade gracefully.** A missing reason line just yields a thinner log entry,
   never a failed turn; continuation detection falls back to the hook-derived
   routed-to + artifact (which are observed, not authored).

## 4. What stays, what goes

| Concern | Today | Redesign |
| --- | --- | --- |
| Pick the move (route) | reasoning over the decision table | **Unchanged** — inherent LLM judgment. |
| Read sibling dossiers to route | `plan$/todo$/exec$/eval$read-dossier`, `edit$read-record` | **Keep all** — routing-decision inputs. |
| Session id | `main$session-id` | **Keep** — runtime accessor (not constructable by the model). |
| Resume probe / last move | `main$resume?`, `main$last-shape` | **Keep** — read seams for continuation detection. |
| Per-move routing line | `main$append-log` (LLM builds `{:shape :routed-to :artifact :reason …}`) | **Removed as an LLM tool.** A hook derives the line from the turn (dispatched agent → shape + routed-to; captured `Saved <kind>:` → artifact; model's one-line reason). |
| Capture `Saved <kind>:` paths | `:agent.tool-use/post` hook → pointers.md | **Unchanged** — already hook-driven; the routing-line hook reuses the same capture. |
| Bootstrap routing dir | `main$bootstrap` (mkdir + empty files) | **Keep or inline** — trivial mechanism (mkdir + header); harmless either way. |
| Session-close index | `main$index-append` (via `:agent.session/closed` hook) | **Unchanged** — hook-driven. |
| Explicit pointer add | `main$append-pointer` | **Keep** — rare, hook usually covers it. |

Net: the single authoring micro-tool (`main$append-log`) moves to a hook;
everything else (accessors, read seams, hook-driven pointers/index) stays. File
tools are already bound. The root agent ends each turn with **zero structured
artifacts to construct** — it routes, states a reason, and the trail records
itself.

## 5. The routing log as hook-derived observation (the main-agent-specific move)

Today the model must, every turn, assemble and submit a routing-log map. Replace
that with a hook that *observes* the turn:

**What the hook can derive on its own (no model effort):**

- **`routed-to`** — the specialist defagent dispatched this turn (the tool/
  code-channel call the runtime already sees). `nil` if the turn was
  self-answered.
- **`shape`** — for specialist moves, a deterministic map from the routed-to
  agent: `plan-agent → :plan-author`, `todo-agent → :decompose`,
  `exec-agent → :execute`, `eval-agent → :evaluate`, `explore-agent → :explore`,
  `edit-agent → :update`, `research-agent → :research`, … The decision table is
  essentially a shape↔agent bijection on the specialist rows, so the hook reads
  shape off the dispatch for free.
- **`artifact`** — the `Saved <kind>: <path>` line the specialist emitted, which
  the existing capture hook already extracts.
- **`question`** — the user's turn input.
- **`turn` / `iter`** — the loop already tracks these.

**What the model still supplies (judgment, in prose it writes anyway):**

- **`reason`** — the one-sentence routing rationale. The instruction *already*
  requires the model to surface "what you did (one-sentence routing decision)" in
  its `:answer`. The hook lifts that line; no separate field.
- **`shape` for self-answered moves** — `:direct-answer` / `:tool-fetch` /
  `:code-compose` / `:meta-resume` / `:clarify` have no dispatch to infer from.
  For these the model emits one stable line in its answer —
  `Routing: <shape> — <reason>` — and the hook parses it. One line, one enum
  token, not a seven-key map.

So the routing line is reconstructed from observation + one optional answer line.
The `:shape` enum still gets validated — but at *parse* time in the hook, against
the same 21-element set, with an `:unspecified` fallback that never fails the
turn. `pointers.md` continues to be auto-captured; the routing-line hook simply
correlates the captured artifact with the move.

**Why this is the right move for the root agent specifically:** main-agent runs
on *every* turn, so any per-turn authoring obligation is paid on every turn and
is the highest-frequency place for the "smaller model skips the bookkeeping"
failure. Moving it to a hook removes that tax entirely while keeping the audit
trail complete (and arguably *more* complete, since the hook can't forget).

## 6. The read/accessor seams worth keeping

Main-agent's routing decisions are data-driven; these are the inputs and stay:

- **Sibling readers** — `plan$/todo$/exec$/eval$read-dossier`, `edit$read-record`.
  Used to detect pipeline state before routing ("a plan exists and its
  post-flight passed → route to todo-agent", "eval recommends plan-agent →
  route there"). Mechanism; untouched.
- **`main$session-id`** — runtime accessor for the agent-session id; the model
  cannot construct it. Keep.
- **`main$resume?` / `main$last-shape`** — read the routing-log dir / last line to
  drive continuation detection ("now do X with the thing we just did — what was
  the thing?"). Keep (now reading a hook-written log).

## 7. Routing is LLM-inherent (nothing to retire there)

The decision table — 21 question shapes mapped to moves, plus "when in doubt,
prefer the specialist," "clarify over speculate," "don't hand-roll a
multi-specialist arc (route to research-agent)" — is the model reasoning about
question shape. That is exactly the judgment the series keeps. The redesign does
**not** touch: the shape taxonomy, direct kebab-case dispatch, the hand-off
surfacing discipline (surface `Saved <kind>:` lines verbatim), or the hard rules.

## 8. Main-agent is the chief beneficiary of the substrates

The most important cross-cutting point. The todo / exec / edit redesigns install
**base-agent substrates** so any coact-derived agent can track a checklist,
execute with evidence, and make safe edits *inline*. **Main-agent is the agent
that benefits most**, because it's the front door and already holds the file
tools:

- A small, concrete user request ("rename foo→bar in x.clj") today routes to
  edit-agent. With the **edit substrate**, main-agent can call `edit$apply`
  inline for the trivial case and only route to the edit-agent subagent when the
  edit needs mode judgment or a formal audited record.
- "Track these three follow-ups" today has no home (it's not a contract todo).
  With the **todo substrate**, main-agent keeps a working checklist inline.
- A two-step "do this then that" today either gets hand-rolled or over-routed.
  With the **exec substrate**, main-agent can route → verify → record inline.

So the substrate work across the series is, in large part, *for* main-agent: it
lets the front door handle the common case without a subagent hop, while still
routing the genuinely contract-shaped work (gated plan→todo→exec→eval, audited
edits, durable research) to the specialists. This redesign and the substrates are
complementary: this one removes main-agent's last authoring obligation (the
routing log); the substrates give main-agent the inline capabilities that reduce
how often it must dispatch at all.

(There is no substrate *below* main-agent — it is the root. The "two kinds"
framing terminates here: main-agent is the casual path's home, and it delegates
to the contract path when the work earns it.)

## 9. Instruction & tool-context (sketch)

Keep the specialist roster, the decision table, the hand-off surfacing, and the
hard rules. Change only the logging obligation:

```text
ROUTING LOG — you no longer assemble a log line. A hook records the route from
  this turn automatically (which specialist you dispatched, the Saved <kind>:
  artifact it returned, your one-sentence reason). Your only obligations:
  • State the one-sentence routing reason in your :answer (you do this already).
  • For a SELF-ANSWERED move (direct-answer / tool-fetch / code-compose /
    meta-resume / clarify), add one line to your :answer:
        Routing: <shape> — <reason>
    so the hook can record the shape (there's no dispatch for it to infer from).
  Continuation detection still works: main$resume? / main$last-shape read the
  hook-written log.
```

Tool-context: drop `main$append-log` from the helper list; keep `main$session-id`,
`main$resume?`, `main$last-shape`, `main$append-pointer` (rare/explicit), the
sibling read-helpers; note the routing line is now hook-recorded and point at the
`Routing:` answer-line convention for self-answered moves. `main$bootstrap` /
`main$index-append` unchanged (mechanism/hook).

## 10. `main.clj` changes

- **Add** a routing-log hook on `:agent.ask/finalize` scoped to `:main-agent`:
  derive `{:turn :iter :question :routed-to :shape :artifact :reason}` from the
  turn (dispatched agent → shape via a `routed-agent→shape` table; captured
  `Saved <kind>:`; the `Routing:`/reason answer line) and append it to
  routing.log. Reuse the existing `Saved <kind>:` capture.
- **Remove** `main$append-log` as a bound, LLM-facing helper (or keep its
  rendering fn private and call it from the hook). Keep the **21-shape enum** as
  the hook's parse-and-validate set, with an `:unspecified` fallback.
- **Keep** `main$session-id`, `main$resume?`, `main$last-shape`,
  `main$append-pointer`, `main$index-append`, and the cherry-picked sibling
  readers bound in the agent.
- **Keep or inline** `main$bootstrap` (mkdir + header — trivial).
- No roster change otherwise: file tools already bound. When the base substrates
  land, main-agent inherits the inline track/execute/edit capabilities (§8) with
  no per-agent wiring.

## 11. Migration

- Land the routing-log hook + the instruction change (drop the append-log
  obligation; add the `Routing:` self-answer convention).
- **routing.log format unchanged** (still NDJSON, same keys) — only the *writer*
  moves from the model to the hook, so `main$resume?` / `main$last-shape` parse
  it identically. No data migration.
- The change is independent of the specialist rewrites; it can land any time. It
  pairs naturally with **Phase 1** of the synthesis rollout (base substrates),
  since both touch the root agent and §8 ties them together.

## 12. Risks & mitigations

| Risk | Mitigation |
| --- | --- |
| Hook mis-derives shape for an ambiguous turn (e.g. model dispatched two specialists). | Hook records one line per dispatched specialist; shape per dispatch is deterministic. For 0 dispatches, the `Routing:` answer line (or `:unspecified`) governs. |
| Model forgets the `Routing:` line on a self-answered move. | Shape falls back to `:unspecified`; routed-to is `nil` (observed). The log entry is thinner but never missing — strictly better than today's "model skipped append-log entirely." |
| Losing the eager shape-typo validation. | Same 21-enum validated at parse time in the hook, with a logged `:unspecified` fallback instead of a hard reject. |
| Continuation detection regresses. | It reads the same routing.log; the hook writes it *more* reliably than the model did. `main$last-shape` unchanged. |
| Over-reliance on substrates for work that should route. | The decision table's "prefer the specialist; don't hand-roll multi-specialist arcs" rules stay; substrates cover only the trivially-scoped casual case (§8). |

## 13. Verification

- **Routing unchanged** — given a question, main-agent picks the same move and
  surfaces the specialist's `Saved <kind>:` lines verbatim.
- **Hook-derived log** — after a plan-agent route, routing.log gains a line with
  `routed-to: plan-agent`, `shape: plan-author`, the captured plan dossier path,
  and the model's reason — without the model calling `main$append-log`.
- **Self-answered move** — a `:direct-answer` turn with a `Routing: direct-answer
  — <reason>` line yields a `routed-to: nil` log entry with the right shape.
- **Missing Routing line** — a self-answered turn with no `Routing:` line still
  logs (`shape: :unspecified`), turn does not fail.
- **Continuation** — next turn, `main$last-shape` returns the prior route;
  "now do X with that" re-routes to the same family.
- **Substrate beneficiary** — with the edit substrate present, a trivial
  "rename A→B in F" is handled inline via `edit$apply` (no edit-agent dispatch),
  and still surfaces `Saved edit:`/`Rollback:`.

## 14. Open questions

1. **Hook event: `:agent.ask/finalize` vs `:agent.tool-use/post`?** finalize sees
   the whole turn (answer + all dispatches) — better for one-line-per-turn
   logging; tool-use/post fires per dispatch — better when a turn legitimately
   dispatches twice. Likely finalize, iterating per captured dispatch.
2. **Keep a `Routing:` answer line at all, or infer shape purely from dispatch +
   a tiny self-answer classifier?** A one-line convention is cheap and explicit;
   a classifier removes even that. Lean on the one-line convention first.
3. **Should main-agent default to the substrates (§8) or route-by-default?**
   Tie to the synthesis's default-on-vs-opt-in decision; the front door is
   exactly where the trade-off (fewer hops vs. more durable artifacts) is felt.
4. **Fold `main$bootstrap` into the hook?** The first finalize hook could
   `mkdir` lazily, dropping `main$bootstrap` entirely. Minor; defer.

---

## Appendix — before/after, one routed turn

**Before — model constructs the routing line every turn:**

```clojure
(def sid (:session-id (main$session-id)))
;; … decide + dispatch …
(plan-agent {:question "draft a plan to migrate auth" :agent-context "…"})
;; … then, by hand, assemble and submit the log line:
(main$append-log :turn 3 :iter 2
                 :question "draft a plan to migrate auth"
                 :shape :plan-author :routed-to "plan-agent"
                 :artifact ".brainyard/agents/plan-agent/dossiers/…md"
                 :reason "question reduced to plan authoring")
```

**After — model routes + states a reason; the hook records the line:**

```clojure
(def sid (:session-id (main$session-id)))        ; accessor (kept)
;; decide + dispatch — that's the whole job:
(plan-agent {:question "draft a plan to migrate auth" :agent-context "…"})
;; :answer surfaces the Saved plan:/Saved dossier: lines + one-sentence reason.
;; → a :agent.ask/finalize hook derives {turn, iter, question, routed-to:
;;   "plan-agent", shape: :plan-author (from the dispatch), artifact (captured),
;;   reason (from the answer line)} and appends it to routing.log. No map built.
```

The root agent ends the turn having done only judgment — pick the route, state
the reason — while the structured trail records itself. The two things a machine
does better stay mechanical: **reading sibling dossiers** to inform the route,
and **observing the turn** to log it.
