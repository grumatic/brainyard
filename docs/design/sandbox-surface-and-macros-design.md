# Code Channel — Call Shape, Prompt Truth, and Macros

> **Status:** Draft, revision 4 (2026-08-24). Prompted by an audit against
> [lisptc](https://d4shi.com/blog/lisptc), a Lisp dialect built for programmatic
> tool calling.
>
> **Every revision has corrected the previous one's central claim — including
> two where the measuring instrument, not the reasoning, was at fault.** R1 proposed
> documenting the sandbox's `clojure.core` surface; measurement showed the model
> never fails on vocabulary, so R1's remedy is retracted in §3.1. R2 then
> concluded the model fails on **delimiters** — but counted raw log occurrences,
> which the `:iterations` replay inflates ~6x. Deduplicated (§1), delimiters are
> 4 failures in 487 evals, not 32. R3's dedup was then found broken too (it keyed
> on an iteration number belonging to the wrong event), so the real figures are
> **2 delimiter failures (0.41%)** and **6 model-syntax failures total (1.23%)** —
> and timeouts, which R3 crowned the dominant class, are 7 events from a single
> session.
>
> - **CR-SBX-0 — Make kwargs the canonical tool-call shape.** **SHIPPED.**
>   Justified by correctness and by the code's own stated preference — **not**,
>   as R2 claimed, by being the largest failure class (§2.7). §2.6 has the
>   as-built notes: scope was ~7x wider than estimated, and the
>   "documentation-only" assumption was false.
> - **CR-SBX-1 — Three prompt truth-fixes.** Two false statements and one broken
>   example. Justified by correctness, not by frequency.
> - **CR-SBX-2 — Persistent macros.** **Narrowed to a probe and shipped as
>   teaching only** (§4.0). The full design — persistence, a `macro-agent`
>   specialist, a command family — is implementation-ready in §4.1–4.14 but
>   deliberately NOT built: it has zero measured demand, and the cheapest way to
>   get that evidence was to teach the capability and watch. `bb code-eval:stats`
>   reports adoption.

---

## 0. Context

lisptc argues that conventional tool calling wastes round trips — "every
intermediate value has to pass back through the model's context along the way" —
and that the fix is to let the model write a *program* over tools that are
ordinary globals in a homoiconic, allow-list language.

Brainyard already does most of that, in several respects better:

| lisptc claim | Brainyard today |
|---|---|
| Programs, not per-call round trips | CoAct code channel; `def`s persist across iterations |
| Tools as ordinary globals | `sandbox-bindings/auto-tool-bindings` derives a callable from **every** `!tool-defs` entry — builtins, commands, skills, agents, user tools, and MCP tools auto-registered as `mcp$<server>$<tool>` |
| Allow-list, not sandboxed deny-list | SCI `:classes` is an allow-list by construction (`sandbox.clj` `sci-classes` / `sci-init-opts`) |
| Values stay out of context | Results live in sandbox vars; only printed output returns. Plus `context-index/get/sample/search` and `truncation/truncate-to-file` |
| Grammar-constrained syntax | **No equivalent** — and it matters less than expected: model-syntax errors are 1.64% of code-evals (§1.1), so the missing grammar is not what is costing iterations |
| Homoiconic; macros as durable procedure | Gap — §4 |

GBNF itself is not adoptable: Brainyard talks to hosted providers (Bedrock,
Anthropic, OpenAI) that accept a JSON schema but not a grammar. That turns out
to be a small loss — §1.1 puts every model-syntax error class together at 1.64%
of code-evals, so a grammar would be buying down an already-small number.

---

## 1. Measurement

> **CORRECTION (revision 4).** Revision 3's dedup was itself broken. Its key
> included an `:iteration` number scraped from the **enclosing provider event**,
> not from the iteration record carrying the error — so a replayed failure got a
> fresh iteration each time and was still counted as distinct. Fixed by keying on
> `[session, code, error-class]` only. Effect: **75 "distinct" failures collapse
> to 34** (13.6x replay, not 6.2x), and two headline claims die with it —
> `Unmatched delimiter` is **2**, not 4 or 32; `Evaluation timed out` is **7
> (1.44%)**, not 36 (7.39%), so **timeouts were never the dominant class** and
> the "start the next CR there" conclusion in §2.7 and §6 was wrong.
>
> Known cost of the fix: a genuine RETRY of identical code within one session now
> collapses with a replay. Replay and retry are textually identical in this log,
> so they are not separable — and undercounting is the safer error for a number
> used to justify work.
>
> **CORRECTION (revision 3).** Revisions 1–2 counted **raw error occurrences**
> and were inflated ~6x. Error text does not live on the `code-eval` event at
> all — it reaches the log inside the **provider API-call events**
> (`clj-llm/openai-api-call`, `clj-llm.claude-code/cli-call`,
> `clj-llm.bedrock/bedrock-api-call`), on their `:prompt` / `:request` /
> `:messages` field — that is, inside the **outbound prompt payload**. The
> prompt carries `## Previous Iterations`, rendered from a buffer of the last
> **10 iterations**, and every LLM call logs its whole prompt. So a failure is
> re-logged once per API call for as long as it stays in that window — and
> there is more than one API call per turn. Long sessions inflate most.
>
> Deduplicated, the headline number moves from **32 delimiter failures (6.6%)**
> to **4 (0.82%)**, and the whole ranking changes: the dominant failure class is
> not syntax at all, it is **timeouts**. The corrected tables are below. The
> denominator was never affected — `code-eval` is emitted once per eval and is
> not replayed (all 487 mentions are top-level events; the script re-checks
> this invariant on every run).
>
> This is now measured by `bb code-eval:stats`, which dedupes on
> `[session-id iteration code-hash error-class]` and prints the replay factor so
> the inflation stays visible. §2.7 records what this does to CR-SBX-0's
> justification.

**Method.** `bb code-eval:stats` over
`~/.brainyard/logs/agent-tui-app.log{,.1,.2,.3}` — four rotated files,
2026-08-14 → 08-23. Denominator: **487 `coact-agent/code-eval` events**.
Numerator: 462 raw error occurrences → **34 distinct failures** (13.6x replay).

### 1.1 Failures by class (deduplicated)

| Error class | Distinct | % of code-evals | Model syntax? |
|---|---|---|---|
| `Exit code` (shell non-zero) | 10 | 2.05% | no |
| `Evaluation timed out` | 7 | 1.44% | no |
| `FORMAT ERROR` (provider/schema) | 4 | 0.82% | no |
| `Could not resolve symbol` | 3 | 0.62% | **yes** |
| `Subprocess timed out` | 2 | 0.41% | no |
| `No action emitted` | 2 | 0.41% | no |
| `Unmatched delimiter` | 2 | 0.41% | **yes** |
| `Unexpected text on code fence` | 1 | 0.21% | **yes** |
| `Unsupported escape character` | **0** | 0% | yes |
| other (interop denial, /0, indexOf) | 3 | 0.62% | no |

**Model-syntax failures total 6 of 487 — 1.23%.** Nothing here is a systemic
problem: 34 distinct failures across 487 evaluations means ~93% of code blocks
ran clean, and the largest single class is a shell command exiting non-zero,
which is frequently legitimate (a `grep` that matches nothing exits 1).

**The timeouts are one session, not a rate.** All 7 — plus both
`Subprocess timed out` — come from a single session on 2026-08-18 running one
pattern: dispatching `edit-agent` (a full sub-agent run, minutes long) from
*inside* clojure code blocks, fanned out over seven todo items, then polling for
the results with a bash loop. That is a code block asking the eval timeout to
cover work the deferred-tasking machinery exists to handle. Worth a guide note
about dispatching sub-agents from code; not worth a CR.

### 1.2 The unresolved symbols, itemized (3 distinct)

```
1  System/getProperty        ← interop policy — and inside a deliberate try/catch probe
1  proposed-map              ← the model's own local typo
1  clojure.java.shell/sh     ← interop policy
```

**Zero** missing-`clojure.core` failures. Not one `parse-long`, `update-vals`,
`abs`, or `random-uuid` — none of the 129 names R1 proposed documenting cost a
single iteration. That conclusion is unchanged by the correction, and in fact
strengthened: the class is even smaller than it looked.

The `System/getProperty` case deserves its own note, because revisions 1–2 built
§3.5 on it. It appeared 14 times in raw counts; it is **one** occurrence, and
`--detail` shows the model had already wrapped it in `(try … (catch Exception e
"DENIED: …"))` — it was *probing* the interop policy, not tripping over it. The
prohibition-vs-redirection argument below still holds on its merits, but it has
essentially no measured cost behind it.

Interop denials are 2 of 3. The prompt already carries the prohibition
("**No interop**: System, Runtime, ProcessBuilder, ClassLoader access denied")
and it fires anyway, because a prohibition does not answer the question the
model is actually asking ("how do I get the working directory?"). In the one
case inspected end-to-end the model recovered itself in the next expression via
`(context-get [:agent-state :config])`. Cost: one iteration, self-healing.

### 1.3 What the delimiter failures actually look like

Not long, complex blocks. This is representative:

```clojure
(explore-agent {:question "which file defines clamp-tier?")
;; Unmatched delimiter: ), expected: } to match {
```

Paren closed, brace forgotten. Short form, single-token slip. They cluster on
the **map-argument call shape**, which nests two delimiter kinds in one call.

Two aggravating facts:

- `try-repair-eof` only *appends* closing delimiters for **unclosed** forms. It
  cannot fix a **mismatched** one, so both reach SCI unrepaired.
- The kwargs call form has no braces at all and **cannot** produce this error.

### 1.4 Caveats on the data

- **Single user, single machine, ~10 days.** With the corrected counts the
  syntax class is 8 events — small enough that it is a handful of anecdotes,
  not a rate. Treat any before/after comparison on it as directional at best;
  `bb code-eval:stats --split` prints a provisional warning under 100 evals for
  this reason.
- **Raw counts mislead here, permanently.** The replay inflation is a property
  of how `:iterations` is logged, not a one-off mistake — any future `grep -c`
  over this log will be wrong the same way. Use `bb code-eval:stats`.
- **Repair frequency is now instrumented (was: unmeasured).** `clj-sandbox`'s
  mulog was never broken — it depends on the mulog component, and `mulog/debug`
  is just `mu/log` with `:level :debug`, ungated and unfiltered (455 debug
  events from other namespaces appear in the same logs). The repair events were
  absent because **the logging sat on a path CoAct does not take**: `eval-code`
  logged, while CoAct's sequential clojure path goes through
  `eval-sandbox-thunk`, which performed the *same two repairs* with no mulog
  call at all.

  Both entry points now route through a single `repair-code` helper
  (`sandbox.clj`) that logs `::repaired-escape-sequences` /
  `::repaired-unclosed-delimiters` with a `:via` tag naming the caller. The
  duplication is what allowed the drift, so the fix removes the duplication
  rather than adding a third copy of the calls.

  `bb code-eval:stats` reports these as a **silent code repairs** block. Logs
  written before 2026-08-24 cannot contain them, so the report prints `n/a` for
  earlier windows rather than a misleading zero.

- **Absence of errors is not evidence of quality.** The `str/` falsehood (§3.2)
  produces verbose code, never an error, so it is invisible to this method by
  construction.

---

## 2. CR-SBX-0 — Make kwargs the canonical tool-call shape

> Shipped. Read §2.7 first if you are here for the justification — the headline
> number this CR was originally argued from did not survive deduplication.

### 2.1 The contradiction

`bind-one-tool` (`sandbox_bindings.clj:105-124`) supports three conventions and
its own docstring states the preference:

> "Kwargs mode is preferred because (a) it ignores declaration order — which
> matters for tools with >8 inputs whose map representation is unordered — and
> (b) it matches the LLM's natural style."

The prompt teaches the other one. `coact_agent.clj:659`, in the decision table
the model reads every turn:

> `| Run a registered agent | `(explore-agent {:question "…"})` / `(plan-agent {…})` |`

`user_tools.clj` repeats the map form in four docstrings (`:19-20`, `:51-52`,
`:274-275`) and in `tool-agent$create`'s user-facing description (`:352`), plus
two internal comments (`:421-422`). `usage_guides.clj` teaches it too —
`(task$run {:job-type :bash :command "ls"})` in `usage-tool`, and
`(task$detail {:task-id "…"})` / `(task$run {:job-type :bash …})` in
`usage-code`. The documentation teaches the shape that fails, against the code's
own stated preference.

### 2.2 Eligibility

Kwargs mode triggers when the first argument is a keyword **and** that keyword
is a declared input key. Verified against the registry:

| Tool | Declared first key | Kwargs form |
|---|---|---|
| `explore-agent` | `:question` (required) | `(explore-agent :question "…")` |
| `bash` | `:command` (required) | `(bash :command "ls")` |
| `read-file` | `:path` (required) | `(read-file :path "…")` |
| `task$run` | `:job-type` (required) | `(task$run :job-type "bash" :command "ls")` |

One nuance surfaced by `task$run`: its `:job-type` is
`[:enum … "bash" "tool"]` — **strings** — while the guides teach the keyword
`{:job-type :bash …}`. Whether `llm-args-transformer` coerces that is
unverified; check it during the sweep rather than propagating the keyword form
into kwargs unexamined.

The two shapes are exactly equivalent through `bind-one-tool`; only the
delimiter profile differs.

**Not eligible**, and must keep the map form:

- `call-tool` — hand-written special; the target's args ride as a positional map
  precisely to avoid colliding with its own routing kwargs.
- Any nested map *value* (e.g. a `:sample` args map) still needs braces. Kwargs
  removes the outer pair, not all of them.

### 2.3 Change

1. Rewrite the `coact_agent.clj` decision-table row to
   `(explore-agent :question "…")`.
2. Sweep `usage_guides.clj` (`usage-agents`, `usage-tool`, `usage-code`) to
   kwargs.
3. Sweep `user_tools.clj`'s six docstring/description sites, including
   `tool-agent$create`'s description — user tool bodies compose peers, so this
   propagates into authored code.
4. Add one line to `usage-sandbox`: *"Prefer kwargs — `(tool :k v)`. The map form
   `(tool {:k v})` is equivalent but nests two delimiter kinds; a dropped `}` is
   the most common code-block failure."*
5. Leave `bind-one-tool` unchanged. All three conventions stay supported; this is
   a change to what we *teach*, not what we *accept*.

### 2.4 Verification

Documentation changes cannot be unit-tested into correctness, so the check is
behavioral: re-run the delimiter count against logs after a week of use and
compare against the corrected 0.82% / 1.64% baselines with
`bb code-eval:stats --split`. Add a test asserting kwargs/map equivalence
for a representative tool so the claim in §2.2 does not rot.

### 2.5 Risk

Low, and reversible. The main one is **partial migration**: mixed shapes across
guides could read as two APIs rather than one preferred idiom. Sweep all sites
in one change or none.

### 2.6 As-built (shipped 2026-08-24)

**69 conversions across 21 files** — not the 3 files §2.3 listed. The map form
was taught in nearly every agent's `:instruction`: `workflow_agent`,
`research_agent`, `explore_agent`, `plan_agent`, `exec_agent`, `todo_agent`,
`edit_agent`, `a2a_agent`, `router_agent`, `agent_roster`, `memory_agent`,
`rlm_agent`, plus `tool.clj`'s `:ask-hint` format string.

Four things the implementation learned that the design did not anticipate:

- **"Documentation-only" was wrong.** `sandbox_meta.clj`'s `:code-template`
  strings are **executed**: `/sandbox <fn> <args>` runs them through `format`
  and then `clj-sandbox/eval-code` (`agent_tui.commands/handle-sandbox-fn`).
  Nine of them were rewritten, so the equivalence claim in §2.2 became
  load-bearing rather than a nicety — hence the test.
- **Scope was set by machine, not by eye.** Every candidate was checked against
  the live registry (256 tools, booted and dumped) to confirm its leading key is
  a declared input. Result: **123 candidates, 0 unsafe**. Doing this by reading
  schemas would not have been credible at that count.
- **Multi-line map args were completed in a second pass — all 54.** The first
  pass deferred them as too risky to script; they were then done with an
  **escape-aware** brace matcher that skips `\"…\"` regions, which is what makes
  it safe: several sites carry literal braces *inside* string values
  (`\"artifacts: {exploration: […]}\"` in `research_agent`, `\"- [ ] <action>
  {via:…}\"` in `todo_agent`) that a naive matcher would have mismatched.
  Continuation lines are de-indented by one column only when they are genuine
  `:key` lines, so multi-line string *bodies* (e.g. `init_agent`'s wrapped
  `:question` text) keep their content untouched.
- **Two live call sites were excluded**, `a2a.clj:430` and `:439` — real code,
  not model-facing text. Both forms work identically on a `deftool` var, so this
  was scope discipline rather than a correctness need.

- **A third shape existed and was left alone.** Single-argument positional calls
  (`(bash "ls")`, `(search "kw")`) are already brace-free, so converting them to
  kwargs would add verbosity for no delimiter benefit. The CR's goal is removing
  the `{…}` nesting, not enforcing one syntax everywhere.

**Pre-existing defect surfaced by the test, now fixed.** The `:code-template`
guard flagged `agent-knowledge$update` / `$remove` / `$list` in
`sandbox_meta.clj`: three `/sandbox` menu entries for tools **absent from the
registry**, which failed with "not bound as a tool" whatever their call shape —
evidence of a rename or removal that never reached the menu. All three rows were
deleted, along with the now-orphaned `;; Knowledge management` comment and the
vestigial `:knowledge` entry in `format-sandbox-help`'s `category-order`.

**Verification.**

- `call_shape_test.clj` — kwargs/map equivalence (order-independence, nested map
  values, odd-arg error path), two registry-driven `:code-template` guards, and
  a **source-scan guard** that fails if any instruction reintroduces the map
  form. The scan is **mutation-tested**: injecting `(search {:query …})` into
  `rlm_agent.clj` makes it fail with the exact file:line, so it is known to be
  live rather than silently skipping.
- **Balance invariant.** Removing a `{` and its matching `}` is balance-neutral,
  so every changed file must keep its HEAD delimiter counts. All 26 do. This
  caught one real regression — a lone `` `}` `` in prose in the new
  `usage-sandbox` guidance, since reworded.
- **Rendered Category B strings parse.** The `str`-concatenated `:next-call`
  handoffs were rendered across their branches and the embedded call extracted
  and read with `edamame`; all parse as balanced kwargs forms.
- 80 tests / 351 assertions green across `call-shape`, `user-tools`, `tools`,
  `commands`, `nrepl-bindings`, `coact-agent-step-f`, `user-agents`,
  `previous-turns`. Agent component compiles.

Known cosmetic residue: in a few `(str …)` bodies the *string-literal*
continuation lines now sit one column off their opening form, because
de-indentation was deliberately restricted to `:key` lines. It is whitespace
inside prompt examples — invisible to the model, and not worth the risk of a
broader rule.

### 2.7 Does the corrected data still justify this CR?

Partly. The claim it was *sold* on — "targets the largest measured failure
class, 6.6% of code-evals" — **does not survive deduplication**. Delimiters are
4 distinct failures in 487 evals (0.82%), and timeouts are 5.5x the entire
model-syntax class. Had the counting been right up front, this CR would not have
been sequenced first.

What survives, and why it was still worth shipping:

- **The contradiction was real and independent of frequency.** `bind-one-tool`'s
  docstring says kwargs is preferred; the prompt taught the map form in 121
  places. That is a defect at any rate.
- **The failure mode is real.** `(explore-agent {:question "…")` did happen, and
  the kwargs form *cannot* produce it — the brace does not exist to drop.
- **It is strictly cheaper to emit** — one fewer delimiter pair per call, on the
  hottest text in the system.
- **The cost was low and the risk is now bounded** by tests, a mutation-tested
  source guard, and a balance invariant (§2.6).

Revision 4 note: R3 concluded here that timeouts were "the single largest
failure class" at 7.39% and that the next CR should start there. That was an
artifact of the same broken dedup — timeouts are 7 distinct failures, all in one
session, all one pattern (§1.1). **There is no failure-driven CR waiting.** At
34 distinct failures in 487 evals the code channel is not where the problems
are; what remains from the original audit is capability work and correctness
fixes, neither of which the error log will ever motivate.

The honest summary: CR-SBX-0 is a correct, cheap, low-risk cleanup that removes
a real failure mode; it was not the highest-leverage thing available, and the
measurement that said otherwise was wrong.

Still outstanding: §2.4's re-measurement — now against the corrected 0.82% /
1.64% baselines via `bb code-eval:stats --split 2026-08-24`. Note §1.4's caveat:
at 8 syntax events the baseline is a handful of anecdotes, so a before/after on
this class can be suggestive but never conclusive.

---

## 3. CR-SBX-1 — Prompt truth-fixes

Three items. None is justified by frequency; each is justified because the
prompt currently says something false.

### 3.1 Retraction — do not document the language surface

R1 proposed `build-language-surface` deriving an exclusion list from the live
interpreter, plus a drift test, plus an idiom block advertising forms the guide
omits (`->`, `for`, `try`, `atom`, `defrecord`).

**Retracted.** §1.2 shows zero vocabulary failures in 487 evals. The model knows
Clojure from training; enumerating what exists is redundant, and enumerating
what is missing addresses a failure class that does not occur. Both would spend
system-prompt tokens — the most expensive tokens in the system, paid every turn
— on a non-problem.

`available-clojure-guide` (`prompt.clj:46-76`) should therefore be **shrunk, not
regenerated**. What earns its place is only the non-derivable delta: the
`user`-ns extras (`FINAL`, `parse-json`, `to-json`, `pprint`) and the interop
policy. Everything else is training data.

If the ~40 interesting exclusions are documented at all, they belong behind
`(usage$guide :topic :sandbox)` — pull, not push.

### 3.2 Fix the `str/` rule — it is false

Four sites (`prompt.clj:50`, `:319`, `:328`; `usage_guides.clj:482`) assert:
"No `str/` alias — always use full `clojure.string/` prefix."

Measured:

```
eval 1: (require '[clojure.string :as str])  => nil
eval 2: (str/upper-case "ok")                => "OK"      ; separate eval — alias persisted
fork  : (str/upper-case "forked")            => "FORKED"  ; fork inherits the alias
```

Aliases persist across `eval-code` calls *and* into `fork-sandbox` forks.
Replace all four with the idiom:

> Alias once per session: `(require '[clojure.string :as str])`. The alias
> persists across iterations and into parallel forks.

This will not move the error rate. It shortens every string-handling block the
model writes, which is a cost this measurement method cannot see (§1.4).

### 3.3 Reduce the escape rules from five sites to one

`Unsupported escape` is 0/487. Five sections defend against it:
`prompt.clj:106` (`sci-string-restrictions`, a full numbered subsection),
`prompt.clj:316`, `prompt.clj:324`, `coact_agent.clj:534`,
`usage_guides.clj:476`.

Keep **one**, in `usage-sandbox` (pull-path). Drop the rest from the system
prompt.

**Unblocked (2026-08-24).** This item was previously gated on an unanswerable
question — whether `Unsupported escape: 0` meant "rare" or "always silently
repaired." The repair paths are now instrumented (§1.4), so the question is
simply a measurement:

```
bb code-eval:stats --since 2026-08-24     # read the "silent code repairs" block
```

Decide on the number, not on argument:

- **Repair rate ≈ 0** → the escaping rules are guarding a problem that is not
  occurring. Cut four of the five sections; keep one in `usage-sandbox`.
- **Repair rate non-trivial** → the rules are load-bearing *and* the repair is
  hiding the model's mistakes from it. Keep them, and additionally surface the
  repair through the iteration record's `:notices` field (which already carries
  usage nudges and format guidance) so the model learns instead of being
  silently corrected. That is the "repair silently, teach loudly" item in §4 of
  the deferred list.

Note the asymmetry the `:via` tag exposes: `eval-code` covers `/sandbox` and
parallel blocks, `eval-sandbox-thunk` covers CoAct's sequential path — the
common one. A repair rate that is high only on `:eval-sandbox-thunk` is the
signal that matters here.

### 3.4 Fix the `parse-long` example

`parallel-execution-guide` ("Pattern: parallel compute → sequential combine")
ends with:

```clojure
(FINAL (str "Total: " (+ (parse-long sum-a) (parse-long sum-b))))
```

`parse-long` does not resolve in the sandbox; `Long/parseLong` does, and is
advertised twenty lines earlier in the same file. The prompt contradicts itself
and teaches the broken half. One-token fix.

### 3.5 Redirect, don't prohibit (interop)

2 of 3 distinct unresolved symbols are interop denials, one of them
`System/getProperty` — a working-directory lookup, and one the model had already
guarded with `try`/`catch` (§1.2). The existing bullet states the prohibition
without naming the alternative. **Weakest item in this CR by evidence** — it is
a correctness/clarity argument with almost no measured cost behind it, so
sequence it last or fold it into an unrelated prompt edit.

Amend to name it: working dir via `(context-get [:agent-state :config])`,
environment via the `bash` tool. Cheap, and it targets the one failure class
that is both real and currently unaddressed.

---

## 4. CR-SBX-2 — Persistent macros

**Stated plainly: the error data does not support this CR.** Zero logged
failures relate to macros, because macros are never used, because they are never
taught. This is a capability argument and should be judged as one — it is the
one place Brainyard is genuinely behind lisptc's thesis, and it should not
borrow authority from §1.

### 4.0 What actually shipped: the teaching, not the machinery

Reviewing the value case against the cost turned up two things that argued for
shrinking this CR to its cheapest testable core.

**The unique benefit is narrower than it looks.** Everything a macro offers is
already covered except one capability:

| Capability | Already possible? |
|---|---|
| Persistent, shared, named call shape | **yes** — `tool-agent$create`, and registry-discoverable by every agent |
| Session-local abbreviation | **yes** — a plain `defn` in a block |
| **Wrap an UNEVALUATED body** (`(with-retry 3 …)`) | **no — the only unique one** |
| Bind a name the body can see | follows from body-wrapping |

**And "shared across agents" is not the win it appears to be.** Persisted macros
*would* reach every agent, since `install-macros!` runs on each sandbox
creation — but they would be **installed everywhere and discoverable nowhere**.
Measured: a macro is absent from `build-function-directory` and
`build-function-index`, and absent from `list-tools` by design (§4.5). Its only
surface is `(keys (ns-publics 'user))`, which the prompt frames as *"your `def`'d
vars"* — so a specialist would carry macros it has no reason to look for. Closing
that needs yet another mechanism on top of a CR with no measured demand.

So v1 is **five lines of guide text and no code**: `defmacro` is now taught in
`(usage$guide :topic :sandbox)`. Session-local macros already work end to end —
measured, they survive the turn-reuse path and forks (§4.6) and pick up
refreshed tool bindings automatically. A model that wants a body-wrapping form
can have one today.

**The probe reports itself.** `bb code-eval:stats` counts distinct `defmacro`
definitions in emitted code blocks (deduped against prompt replay, and scoped to
model-emitted `:code` so the guide's own example cannot self-trigger):

- **stays 0** → the capability is unwanted; §4.1–4.14 stays on the shelf and
  this CR closes.
- **non-zero** → Blockers B and C (hygiene, persistence) are worth the
  namespace; build §4 as designed, and solve the discovery gap above at the
  same time.

Everything below is the deferred full design, kept because it is ready and
because §4.6 records a lifecycle finding worth not re-deriving.

### 4.1 What already works

`defmacro` evaluates in the sandbox today. lisptc's motivating example — a macro
wrapping a *body* around a tool call — is expressible:

```clojure
(defmacro visit [url & body]
  (list 'let ['page (list 'my-tool url)] (cons 'do body)))

(visit "u" (:v page))   ;=> "u"
```

Three things stop it being useful.

### 4.2 Blocker A — never taught

`defmacro` appears in zero prompt or usage-guide text.

### 4.3 Blocker B — the hygiene footgun

The natural syntax-quote form, which is what a model writes first, fails:

```clojure
(defmacro visit [url & body] `(let [page (my-tool :nav ~url)] ~@body))
(visit "http://x" (str "page=" page))
;=> Could not resolve symbol: page

(macroexpand-1 '(visit "u" 1))
;=> (clojure.core/let [user/page (user/my-tool :nav "u")] 1)
```

Correct Clojure — syntax-quote namespace-resolves, and lisptc's CL-style example
is unhygienic in a way Clojure deliberately is not. But the error points at the
*call site*, not the definition, which is the kind of error models loop on.

**No magic.** Teach `~'page` for deliberate anaphora, and make the dry-run
**return the macroexpansion** so the model sees `user/page` and fixes itself.
Same reasoning that made `tool-agent$validate` the kept mechanism: a model
cannot reliably self-assess this, but a fork can.

### 4.4 Blocker C — does not survive

```
{:kept {}, :lost [{:name helper, :reason :non-edn}
                  {:name visit,  :reason :non-edn}]}
```

`extract-user-vars-with-survival` drops functions and macros alike. This is the
same limitation that motivated `user_tools.clj`, whose docstring states the fix:
persist the SOURCE and re-eval it.

Macros cannot get that from `tool-agent$create`, whose `:body` contract is
`(fn [args] …)` — one map argument, eagerly evaluated. A form wrapping an
unevaluated body is inexpressible.

### 4.5 Key conclusion: a macro is not a tool

A user *tool* dispatches through `tool/call-tool` at **runtime** — registry
lookup, Malli coercion, hooks, permission gates, depth guards. A macro must be
in the SCI context **before analysis** of the calling form. Therefore it:

- cannot dispatch through `call-tool`;
- cannot be invoked from the JSON tool-calls channel at all;
- must install as a real SCI var in *each agent's own* code-eval sandbox, not
  the shared `!tools-sandbox`.

Consequences: macros are a **code-channel-only** affordance, and they must
**not** be registered in `!tool-defs` — a non-callable registry entry would
surface in `list-tools` and the tool-calls channel as something the model can
call and then cannot. Discovery gets a dedicated `macro-agent$list`.

They confer no new privilege: a macro expands to calls the sandbox already
permits — the same argument `user_tools.clj` makes for tool bodies.

### 4.6 Lifecycle — measured, and it changes the plan

A macro is an SCI var in an agent's own `user` namespace, so its lifetime is the
**sandbox's**, not the process's. Measured against a real sandbox:

| Event | Macro survives? | Consequence |
|---|---|---|
| Turn 2+ reuse (`update-context!` + `update-bindings!` + `clear-history!`) | **yes** | no per-turn reinstall needed |
| `fork-sandbox` (parallel code blocks) | **yes** | macros usable in parallel blocks |
| `create-sandbox` (new agent, new session, `--resume`) | **no** | must reinstall per sandbox |
| `extract-user-vars-with-survival` | listed `:non-edn` **lost** | never persists via sandbox-state — this CR is the only route |

**This kills the "`ensure-loaded!`, beside `user-tools/ensure-loaded!`" plan.**
`user-tools/ensure-loaded!` is **process-scoped idempotent** — it keys a
`!loaded` set by directory and no-ops thereafter — and that is correct *for
tools*, because a tool lands in the shared `!tools-sandbox` plus the global
`!tool-defs` registry, so loading once per process serves every agent.

Macros have no shared home. Copying that pattern would install them into the
**first** agent's sandbox and silently skip every sandbox created afterwards —
a second agent, a new session, or a `--resume` would come up with no macros and
no error. So the loader is deliberately **not** named `ensure-loaded!`:

```clojure
(install-macros! sandbox :dirs dirs)   ;; per-SANDBOX, runs on every creation
```

Disk reads may still be cached per process; the **SCI install must not be**.
Call site is the `(nil? existing-sandbox)` branch of `coact-init` — the same
branch that runs the `ensure-loaded!` family — but for the opposite reason:
that branch is where a *sandbox* is born, which is exactly the macro's scope.

One free property worth keeping: a macro expands to a *symbol* resolved at eval
time, so a macro written against `bash` picks up each turn's refreshed tool
closure automatically. Verified — after `update-bindings!` swapped a tool, the
macro expanded to the new one with no reinstall.

### 4.7 Shape

New `agent.common.user-macros`, parallel to `user_tools.clj`:

```
.brainyard/macros/<name>.edn   ;; {:name :description}
.brainyard/macros/<name>.clj   ;; verbatim (defmacro <name> [args] …)
```

`def-store/write-def!` / `read-def` / `delete-def!` reused unchanged — already
the shared primitive for user tools and hooks, and the `.edn` + verbatim `.clj`
pair is right here (paren-matching, no escaping).

| Command | Behavior |
|---|---|
| `macro-agent$validate` | Eval the source into a throwaway fork, `macroexpand-1` the `:sample` call, and **return the expansion**. Checks name collisions. Persists nothing, installs nothing. |
| `macro-agent$create` | Validate, persist, then install into the current agent's live sandbox so a create-then-use in the SAME turn resolves. |
| `macro-agent$list` / `$read` / `$delete` | As per `tool-agent$*`. `$delete` removes the files; the already-installed var lingers until the next sandbox (documented, harmless — same as `delete-user-tool!`). |

**Returning the expansion is the load-bearing part**, not a nicety. §4.3's
hygiene footgun produces an error at the *call site* (`Could not resolve symbol:
page`) that points nowhere near the `defmacro`. A model cannot debug that from
the message; it can read `(clojure.core/let [user/page …] …)` and see the
namespace-qualified symbol immediately. Same "a model can't self-assess, but a
fork can" reasoning that made `tool-agent$validate` the kept mechanism.

### 4.8 Failure modes

| Risk | Guard |
|---|---|
| A broken persisted macro poisons **every** sandbox at boot (worse than a broken tool, which merely fails to register) | Install each in its own try/catch; quarantine and log `::load-user-macro-failed`; continue. Mirrors `load-user-tools!` pass-2 rollback. |
| A macro shadows a bound tool symbol (a macro named `bash`) | Reject at validate: no collision with `auto-tool-bindings` keys or `clojure.core` publics. |
| Expands to a symbol absent from a future palette | Ordinary eval error, ordinary message. Do not validate against hypothetical palettes. |
| Called from the tool-calls channel | Not in `!tool-defs`; `tool-bound?` rejects with the standard error. |
| Installed into only the first sandbox (the `ensure-loaded!` trap, §4.6) | Loader is per-sandbox by construction and takes the sandbox as an argument, so there is no process-scoped guard to get wrong. Covered by a two-sandbox test. |
| Startup cost grows with macro count | Install is `eval` per macro on sandbox creation. Cheap now; if it ever isn't, cache the *parsed* source, never the install. |
| Resume banner under-reports | `extract-user-vars-with-survival` counts macros as `:non-edn` **lost** (measured), so the TUI's "N restored, M lost" line will keep counting reinstalled macros as lost. Cosmetic; fix the banner, not the snapshot. |

### 4.9 Command surface

Schemas follow `tool-agent$*` exactly, including the EDN-string escape hatch for
values the JSON tool-call channel cannot express.

```clojure
macro-agent$create
  :name        [:string]  ;; ^[a-z][a-z0-9-]*$ — same rule as user tools
  :body        [:string]  ;; verbatim "(defmacro <name> [args] …)"
  :description {:optional true} [:string]
  → {:name :persisted}  |  {:error …}

macro-agent$validate
  :body        [:string]
  :name        {:optional true} [:string]   ;; enables collision check
  :sample      {:optional true} [:string]   ;; a call form, e.g. "(visit \"u\" 1)"
  → {:valid :name-ok :collision :body-ok :expansion :errors}

macro-agent$list   → {:macros [{:name :description}]}
macro-agent$read   :name → {:name :description :body}
macro-agent$delete :name → {:deleted}  |  {:error}
```

Two deliberate departures from `tool-agent$*`:

- **No `:input-schema`.** A macro has no Malli contract because it never passes
  through `call-tool` (§4.5). Arity errors surface as ordinary SCI errors.
- **`:sample` is a STRING, not a map.** For a tool, `:sample` is an args map to
  run the body against. For a macro the useful probe is a *call form* to expand,
  and a call form is code, not data — so it arrives as source and is read before
  `macroexpand-1`.

**Name rejection set** (checked at validate, enforced at create):
`clojure.core` publics visible in the sandbox ∪ the agent's `auto-tool-bindings`
keys ∪ the `user`-ns defaults (`FINAL`, `parse-json`, `to-json`, `pprint`) ∪ the
context accessors. Rationale: a macro named `bash` shadows the tool for every
later block in that sandbox, and the failure would present as "the tool started
behaving strangely," which is near-impossible to attribute.

### 4.10 Specialist or folded into tool-agent?

**Own specialist — `macro-agent`.** §4.5 establishes that a macro is not a tool:
different registry (none), different channel (code only), different lifetime
(per-sandbox), different failure modes (shadowing, hygiene). A shared
instruction would have to hedge every rule with "unless it's a macro," and the
tool-agent instruction is already a hard-rules document (validate-before-create,
verify-after-create) where hedging is the thing that breaks it.

The cost is one more entry in the router's three registration points. That is
the cheaper mistake to unwind than a blurred instruction.

Registration, per the pattern in `agent/interface.clj`: add the ns to the
side-effecting require list, then wire `macro-agent` into `common/router_agent.clj`
in three places (directory, lettered decision table, summary list).

### 4.11 What the model is told

Three prompt changes, all in the pull path (`usage$guide`), none in the system
prompt — macros are an occasional capability, not a per-turn one:

1. **A `:macros` guide topic.** What a macro is for (a repeated call shape worth
   naming), the anaphora rule (`~'page`, with the §4.3 expansion shown as the
   worked example), that it is **code-channel only**, and the
   validate → create → use loop.
2. **One line in `usage-sandbox`** noting `defmacro` works and pointing at the
   guide. This is the discovery hook — §4.2's blocker is that nothing anywhere
   mentions the capability.
3. **A `macro-agent` row** in the router's agent directory.

Explicitly NOT changed: the system prompt's function directory. A macro is not a
registered tool, so it must not appear in a listing whose entries are all
callable through either channel.

### 4.12 Test plan

Mirroring `user_tools_test.clj`, which exercises the real sandbox + real store:

- **Round trip** — create → persist → new sandbox → `install-macros!` → the
  macro expands and runs. This is the load-bearing test; it is the capability a
  plain `defmacro` in the sandbox cannot provide.
- **Two sandboxes from one process** — the §4.6 trap. Install into sandbox A,
  create sandbox B, assert B also has it. A process-scoped guard fails here.
- **Survives the turn-reuse path** — `update-context!` + `update-bindings!` +
  `clear-history!`, then still expands (and picks up a swapped tool binding).
- **Survives a fork** — parallel-block usability.
- **Hygiene** — a syntax-quoted body reports `user/page` in
  `macro-agent$validate`'s `:expansion`; the `~'page` form expands clean.
- **Quarantine** — a corrupt `.clj` on disk does not prevent the other macros
  installing, and logs `::install-user-macro-failed`.
- **Name rejection** — `bash`, `map`, `FINAL`, `context-get` all rejected.
- **Not a tool** — the name does not appear in `list-tools`, and `tool-bound?`
  is false for it.

### 4.13 Not in v1

- **Compiling skills to macros** (§4.14) — let the two coexist and observe first.
- **User-scope macros.** Project-scoped only, like user tools; a `:scope :user`
  variant can follow the same path skills took.
- **Macro composition guarantees.** A macro may expand to another macro; nothing
  orders installs beyond alphabetical, so a macro depending on a peer may fail
  to *install* while installing fine on the next boot. If this bites, adopt
  `load-user-tools!`'s two-pass shape (register all, then install all) rather
  than inventing dependency ordering.
- **Retention/GC.** Macros are small text files; revisit with skills.

### 4.14 Relationship to skills — and one measured experiment

`skills.clj` re-reads `SKILL.md` on every invocation and asks the LM to follow
prose — the "filing cabinet of markdown memory" lisptc criticizes by name.
Macros are the executable counterpart: a procedure that runs rather than being
re-interpreted.

**Experiment (2026-08-24): compile a skill to a user TOOL.** Not a macro — a
tool, since `tool-agent$create` already exists and needs no new machinery. Run
against the three skills present in this repo, which turn out to be a useful
spread:

| skill | content | compiles? |
|---|---|---|
| `run-bash` (145 ln) | API reference for `bash` / `task$run` | **No** — it is documentation for tools that already exist |
| `by-rebuild-stamp-verify-loop` (100 ln) | procedure with judgement (write the commit message, judge staleness, decide whether a red check blocks) | **Partly** |
| `nrepl-raw-bencode-client` (142 ln) | ~90 lines of bencode client **code** plus a fixed procedure | **Yes** — the payload is code |

The third was compiled end to end: validate → create → persist → rehydrate in a
**fresh JVM** → call. It works, including stdout capture, error propagation
(`eval-error` status with the real exception text), and Malli rejecting a
malformed call (`{:code ["missing required key"]}`) — a contract the prose skill
never had.

Token cost of the same capability:

| path | tokens |
|---|---|
| skill: `SKILL.md` into context | 1542 |
| skill: client code the model must then emit | 656 |
| **skill total, per use** | **2198** |
| tool: directory entry (description + schema) | 41 |
| tool: the call itself | 13 |
| **tool total, per use** | **54** |

**~40x**, plus one iteration instead of two or more, plus arg validation, plus
the client is no longer re-derived every session.

**But read what actually compiled.** The skill has six sections; only §2 ("write
the client ONCE") became the tool. §1 (determine the port), §3 (handshake and
PROVE you are on the right JVM), §4 (discover before you mutate), §5 (reload and
re-test) and §6 (hygiene) did **not** compile, and should not — they are
operational caution, and a single tool call cannot decide whether you are about
to mutate the wrong JVM.

So the honest result: **the mechanism compiled, the procedure did not.** The 40x
is real and applies to the code payload; it says nothing about the judgement
around it. That maps cleanly onto the table above — a skill whose value is a
lump of code is a tool wearing prose, and a skill whose value is knowing when to
stop is not.

**Implication for CR-SBX-2, unchanged:** this argues for *tools*, not macros.
Nothing in the experiment needed an unevaluated body, which remains the only
thing a macro uniquely provides (§4.0). If a compile-skills-to-X path is ever
built, X is `tool-agent$create` and the interesting work is deciding which
sections to leave in prose.

**Not proposed:** an automatic skill→tool compiler. The classification above is
a judgement about what a skill *is*, and the two failing cases fail for
different reasons — one is redundant documentation, the other is irreducibly
human. A compiler would have to make that call, and the sample here is three.

## 5. Sequencing

1. ~~**CR-SBX-0** (§2) — kwargs sweep.~~ **SHIPPED 2026-08-24.** Note the
   justification shrank twice under better counting: it was never the largest
   failure class (§2.7). It stands as a correctness fix.
2. ~~**CR-SBX-1** §3.2, §3.4, §3.5~~ **SHIPPED 2026-08-24** — the `str/`
   falsehood replaced with the alias idiom at all four sites, the `parse-long`
   example corrected to `Long/parseLong`, and the interop bullet now names the
   alternatives rather than only the prohibition.
3. **CR-SBX-1** §3.1 — shrink `available-clojure-guide` to the non-derivable
   delta. Measure with `build-system-prompt`'s `:return-breakdown?`.
4. ~~Instrument the repair path~~ **DONE (2026-08-24)** — `repair-code` in
   `sandbox.clj` now logs both layers from both entry points with a `:via` tag,
   and `bb code-eval:stats` reports them. §3.3 is decidable once a few days of
   post-instrumentation logs exist.
5. **CR-SBX-2** (§4) — **probe shipped 2026-08-24**: `defmacro` taught in the
   `:sandbox` guide, adoption counted by `bb code-eval:stats`. The full design
   (§4.1–4.14) is implementation-ready — lifecycle measured (§4.6), command
   surface fixed (§4.9), specialist-vs-folded resolved (§4.10), test plan
   written (§4.12) — and gated on the adoption number, not on argument.

Steps 1–3 are prompt-only. Step 5 is the only one adding a namespace, and it
adds it by cloning a proven one.

## 6. Open questions

- Does the kwargs sweep move the corrected 0.82% delimiter rate, or do the slips
  relocate to nested map values? `bb code-eval:stats --split 2026-08-24` answers
  it — though at 4 baseline events the answer can only ever be suggestive.
- ~~Why is `Evaluation timed out` 7.39%?~~ **Answered: it is not.** 7 distinct
  failures (1.44%), all one session, all the same pattern — `edit-agent`
  dispatched from inside a code block, which asks the eval timeout to cover a
  minutes-long sub-agent run. The open item it leaves is small and documentary:
  should the sandbox guide say "dispatch a sub-agent from a code block only via
  `task$run`, never inline"? Worth a line; not worth a CR.
- **Does anything in this doc still have failure-data behind it?** No. After the
  revision-4 dedup fix, model-syntax is 6 events in 487 evals. Remaining work is
  justified by correctness (§3.2/§3.4) or capability (§4, and the value-handle
  gap that never made it into this doc) — not by the log.
- ~~Are `clj-sandbox` mulog events absent from the publisher, or filtered?~~
  **Answered:** neither. mulog is wired and ungated; the repair logging simply
  sat on `eval-code`, a path CoAct does not take for sequential blocks. Fixed by
  routing both entry points through one `repair-code` helper (§1.4). Worth
  noting the general hazard: two copies of the same logic, one instrumented,
  reads exactly like a broken logging pipeline.
- ~~`macro-agent$*` as its own specialist, or folded into `tool-agent`?~~
  **Resolved (§4.10): its own specialist.** The deciding factor was not moving
  parts but the instruction — `tool-agent`'s is a hard-rules document, and every
  rule would need an "unless it's a macro" hedge.

---

## 7. Disposition of the original lisptc audit

The audit that started this doc listed five gaps. Recorded here because three of
the five closed without code, and two of those closed by being **wrong**.

| # | Gap | Disposition |
|---|---|---|
| 1 | Advertised language surface is wrong | Main remedy **retracted** (§3.1 — zero vocabulary failures). Three truth-fixes **SHIPPED 2026-08-24**: §3.2, §3.4, §3.5 |
| 2 | No macro layer | Probe shipped (§4.0); full build gated on adoption |
| 3 | Silent repair — model never learns | Instrumented; §3.3 decidable on data |
| 4 | No value-handle across the tool-call boundary | **Closed — the claim was false.** See §7.1; the follow-up it surfaced (§7.2) shipped |
| 5 | Skills are prose, not composed programs | Explicit v1 non-goal (§4.14) |

### 7.1 Gap 4 was already solved, twice over

The claim was: a large tool result stays in a sandbox `def` on the code channel
but enters context whole through the tool-calls channel, so the two should be
unified behind a `ref://` handle.

**Neither half holds.**

*The mechanism already exists, and it is shared.* `sanitize-tool-result` routes
every `:tool-result` through `truncate-iter-field` → `clj-sandbox/truncate-to-file`
— the identical path `sanitize-eval-entry` uses for `:result` and `:output`.
Both are capped at `:max-output-chars` (32000, ~8k tokens), keep head 70% + tail
20%, spill the full value to a temp file, and leave a `(read-file …)` pointer.
That *is* the value handle the gap asked for; it addresses by filesystem path
rather than a `ref://` scheme, which needs no new resolver.

*And the model never sees the untruncated form.* `ThinkActCode`'s only history
input is `:iterations`, built exclusively by `coact-accumulate-iteration-action`,
which sanitizes on the way in. The raw `:last-tool-results` is transient state
consumed by that action and by the TUI display stage — it is never a signature
input.

*The residual difference costs nothing measurable.* A code-channel result can
stay entirely out of context (bound to a var, never printed); a tool-channel
result costs up to the cap. Truncation markers in the logs, by class:

```
1610  compact-answer
  12  eval-output
   1  tool-result      ← the whole of gap 4, one occurrence
```

One tool result in the entire log period was large enough to spill. Building a
handle protocol for that would be machinery in search of a problem.

**Incidental finding worth its own look:** `compact-answer` accounts for ~99% of
all truncation. That is the conversation-compaction path repeatedly spilling
oversized answers to disk — unrelated to this doc, and the only place the
truncation machinery is under real load. (Raw counts, so replay-inflated; the
ratio is what matters, not the absolute.)

### 7.2 Follow-up: why compact-answer is 99% of all truncation

Investigated after §7.1 surfaced the ratio. The cause is a design choice in
`context_compaction/compact-previous-turns`, and it has a consequence worth
fixing.

**It searches by applying.** The compactor tries progressively tighter passes —
`[3 10 2000] → [1 5 1000] → [0 0 400] → [0 0 200]` — and after each pass
*measures the result* to decide whether to stop. Applying a pass calls
`recompress-turns`, which calls `truncate-to-file` on every summary/minimal
turn. That is real filesystem I/O: a read (to recover the prior original) plus a
write (of the new original). So the search **writes files for candidate passes
it then discards**; only the winning pass is ever referenced.

Cost per compaction is up to `4 passes × turns-over-limit` file round-trips
where `1 × turns-over-limit` would do. And auto-compaction fires after *every
turn* once a session exceeds `:max-context-tokens` (`agent_tui/core.clj`), so a
long session churns this continuously. Hence 1610 markers against 12 for
`eval-output` and 1 for `tool-result`.

**Each pass truncates the previous pass's output, not the original.** The loop
recurs on `compressed`, so a turn that is `:summary` in both pass 1 and pass 2
is truncated twice. `truncate-to-file` is idempotent *by reading the temp file
back* to recover the untruncated text, so correctness depends on that file
surviving.

**What happens when it does not survive — measured, and milder than expected.**
I predicted nested markers; that is wrong. Deleting the pass-1 file before
pass 2 yields:

```
normal chain            markers: 1   reports "original: 5000 chars"   (correct)
pass-1 file removed     markers: 1   reports "original: 2309 chars"   (silently wrong)
```

No nesting, no error, no stale path — but the new temp file now holds the
**already-truncated** text while the marker still says "Full content saved to".
Recoverability is lost quietly, and nothing downstream can detect it.

**Why that is reachable rather than theoretical.** `gc/sweep-sandbox-cache!`
caps the cache at `:sandbox-cache-max-files` (**default 200**) and evicts
**oldest first**. The compaction churn above is what fills that cache, and the
oldest files are precisely the ones holding the oldest turns' original answers.
Since `:previous-turns` is mutated in place, each turn's compaction re-truncates
the previous turn's already-truncated text — so the recovery chain is only as
strong as its oldest surviving link. The GC docstring already notes the adjacent
hazard: "a hot truncation cache can crowd out stale file-backed entries."

**Fix — separate searching from committing. IMPLEMENTED 2026-08-24.**
`truncate-to-file` gained `:dry-run?`, which returns the byte-identical string
it would commit but performs no write (placeholder id is the same width, so the
measured length matches — verified: 2307 == 2307). `compact-previous-turns` now
prices every candidate pass against the **original** turns with `:dry-run?`,
then applies the winner **once**.

**Correction to this section's own claim.** It said writes would drop ~4x. They
do not drop at all — `truncate-to-file` *reuses* the temp file it recovered
from, so it is one file per truncated answer either way. The chain's real cost
is **read-backs**, measured over 12 turns:

| scenario | old reads | old writes | new reads | new writes |
|---|---|---|---|---|
| loose target (pass 1 fits) | 0 | 9 | **0** | 9 |
| mid | 9 | 11 | **0** | 11 |
| tight | 32 | 12 | **0** | 12 |
| no pass fits | 56 | 20 | **0** | 20 |

(Counters scoped to the truncation cache and both sides measured warm. A first
cut counted every `slurp`, which also caught cold config/dirs resolution and so
compared a cold old run against a warm new one — it reported 2 reads for the
loose case, where the honest figure is 0 because pass 1 fits and neither
implementation re-truncates. The regression test scopes its counter for the
same reason.)

Those reads *are* the eviction dependency — each one is a re-truncation asking
the temp file to still hold the original. Removing them removes the silent
re-basing failure entirely, which is the point; the I/O saving is incidental.

Output is unchanged, verified against a reimplementation of the old cumulative
loop across four targets: same turn count, same `:depth` assignments, same
answers modulo the temp-file id. That oracle lives in
`context_compaction_test.clj` rather than being a one-off check, because the
split is only safe while depth stays a pure function of recency and the pass
parameters — if that stops holding, the failure is silent.

Still only the WITHIN-compaction chain. `:previous-turns` is mutated in place,
so turn N+1 still starts from turn N's truncated text; breaking that needs
originals retained somewhere and is a larger change.
