# Predictor-Agent — Authoring User-Defined Predictors from a Signature

> **Status:** Design / proposal (2026-09-16). The predictor machinery is
> **shipped** — `defpredictor` / `run-predictor`, layered params, tracing,
> datasets, `evaluate`, optimizers, proposals
> ([`dspy-programming-model-proposal.md`](./dspy-programming-model-proposal.md)
> §9 "As built", Phases 0–3). Every one of those predictors is declared in
> **source**. This doc adds the missing half: a predictor authored at
> **runtime**, from a signature, the way a user tool, hook or agent already is.
> **Scope:** a new `components/agent/.../common/user_predictors.clj` (persistence
> + registration + the `predictor$*` command family) and a thin
> `common/predictor_agent.clj` (`coact/run-coact-derived`), plus one line of app
> wiring and one entry in `subdir-scope-policy`.
> **Built on:** `clj-llm/core/predictor.clj` (unchanged), `common/programs.clj`
> (unchanged), `common/def-store.clj`, `common/user_tools.clj` (the pattern).
> **Sibling of:** [`tool-agent`](./tool-agent-design.md)-shaped authoring agents —
> `skill-agent`, `meta-agent`, `event-agent`. Adjacent: `program$*`, which is
> already in the **common** roster and needs no front door of its own.

---

## 1. Motivation

Brainyard has two ways to get an LLM to do a typed, schema-checked
transformation, and they sit at opposite ends of a gap:

| | how you say it | addressable? | params | demos | traced | dataset | eval | compile |
|---|---|---|---|---|---|---|---|---|
| `query$llm` + `:output-schema` | a prompt string, per call | ✗ | ✗ | ✗ | ✗ | ✗ | ✗ | ✗ |
| `defpredictor` in source | Clojure, at build time | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |

A user who wants "classify this changelog entry into `{feat,fix,chore}` with a
one-line justification" today writes a prompt into `query$llm`, or authors a
**user tool** whose body wraps `query$llm`. Either way the prompt is an opaque
string inside a call. It has no id, so nothing can attach params to it, nothing
records what it was asked and what it answered, no dataset can be built from it,
and `program$eval` / `program$compile` — the whole measurement and optimization
layer that already ships — cannot see it at all. The only way into that layer is
to edit `memory/core/signatures.clj`, rebuild a GraalVM binary, and ship it.

That is the same gap `user-tools` closed for tools: *a capability that a plain
`defn` in the agent's sandbox cannot provide is **persistence + registration
under a stable id***. For a predictor the stable id is worth even more, because
the id **is** the params path, the trace key and the dataset directory. Naming a
transformation is most of what makes it optimizable.

So: **the user names a signature; the runtime gives back a first-class
predictor.** Everything downstream — params layering, demos, prediction log,
`program$build-dataset`, `program$eval`, `program$compile`, `by programs accept`
— then works on it with **zero new code**, because all of it keys on
`:predictor/id` in the registry and knows nothing about where a predictor came
from.

This also answers §11 Q2 of the DSPy proposal ("should baked-in params exist at
all, or does every install compile locally?") from the other direction: with
runtime-authored predictors, a user's *own* pipelines are the natural first
compile targets, and they carry no provider-portability risk to anyone else.

---

## 2. The central decision — a predictor is DATA, not code

A user tool is a `fn` body. It must be persisted as source, re-evaluated in an
SCI sandbox to rehydrate, forked per call for isolation, budgeted against a
runaway, and smoke-tested before it can be trusted. Roughly two thirds of
`user_tools.clj` (682 lines) exists to manage that.

**A predictor needs none of it.** Its entire definition is:

```clojure
{:id           "user/changelog-classify"
 :instructions "Classify a changelog entry by the kind of change it describes."
 :inputs       {:entry [:string {:desc "One changelog line"}]}
 :outputs      {:kind      [:enum {:desc "Change category"} "feat" "fix" "chore"]
                :rationale [:string {:desc "One sentence, citing the wording"}]}
 :input-order  [:entry]
 :strategy     :predict
 :tier         :light}
```

That is EDN. There is no body to eval, no sandbox to fork, no timeout to
budget, no privilege to expand. The thing that executes — `predict`,
`chain-of-thought`, parse/lift/coerce/validate, retry classification, cache
zones — already ships and is shared verbatim with the built-in predictors.

Three consequences worth stating plainly, because they are what make this
proposal small:

- **No `.clj` sidecar.** `def-store/write-def!` exists to keep a *body* verbatim
  outside the `.edn`. With no body, a user predictor is one pretty-printed
  `.edn` file and `clojure.edn/read-string` is the only reader ever used. The
  safe-reader discipline is not a rule to remember here; it is the only option.
- **No phase-1/phase-2 split.** `boot.clj` splits registration (no agent needed)
  from body installation (needs the agent's tool palette bound) because a SCI
  body resolves symbols at analysis time. A predictor resolves nothing at
  registration, so it registers whole, once, at boot.
- **Validation is offline and free.** The dry-run for a tool body is "eval it in
  a throwaway fork and hope". The dry-run for a predictor is
  `compile-signature` — which derives the JSON Schema and throws on anything
  malformed — and it contacts no provider. The *behavioural* check needs no
  probe at all: it is a real call to the registered predictor after create (§6).

---

## 3. Definition and parameters are separate files

This is the decision most likely to be got wrong, so it gets its own section.

```
<project>/.brainyard/predictors/user/changelog-classify.edn   ← DEFINITION (this doc)
<project>/.brainyard/programs/user/changelog-classify.edn     ← PARAMS (already ships)
<project>/.brainyard/programs/user/changelog-classify/
    datasets/…  evals/…  proposals/…                          ← already ships
```

The definition says **what fields exist**. The params say **what the prompt
currently is** — an instructions override, demos, an lm/tier hint — and they are
the compiler's output. Putting both in one file fails four ways:

1. **`program$compile` writes params, and `by programs accept` overwrites the
   params file wholesale** (backing up the old one as `previous.edn`). If the
   definition shared that file, an accepted proposal would either destroy the
   user's field declarations or force the optimizer to preserve keys it does not
   own and cannot validate. Today a bad accept can only ever install a bad
   prompt; keeping the definition out of that blast radius is what preserves
   that. Same decoupling as task removal vs. task-artifact GC.
2. **Params resolution is *first layer wins WHOLE*, deliberately** — "a params
   file always means exactly what it says." A *definition* must not obey that
   rule: a project-scope params file must not be able to silently delete a
   user-scope predictor's output fields by not mentioning them.
3. **The two have different lifetimes.** A definition changes when a human
   changes their mind about the task. Params change every time a compile is
   accepted. Co-locating them means every accept touches a file under review for
   a different reason.
4. **The params schema would have to grow definition keys** (`:inputs`,
   `:outputs`, `:strategy`), and `validate-params` is applied to *every* params
   file including the built-ins' — so a typo in a user predictor's field map
   would start failing validation for files that have nothing to do with it.

The pairing is exactly the one that already exists in source: `defsignature`
(committed, reviewed, changes rarely) vs. `programs/<id>.edn` (generated,
proposed, changes per compile). Runtime authoring should not collapse a
distinction that the source path keeps.

**Deleting a predictor does not delete its params, datasets, evals or
proposals.** `predictor$delete` removes `predictors/<id>.edn` and unregisters;
it reports what remains under `programs/<id>/` and leaves it there. Re-creating
the same id picks the params back up — which is the right behaviour when a
"delete" was really a rename-and-restore, and the wrong behaviour to guess at
silently, so the response says so.

### Scope

Add to `core.config/subdir-scope-policy`:

```clojure
;; both scopes: a predictor DEFINITION. Project scope is a repo's own
;; transformation (its changelog taxonomy, its review rubric); user scope is a
;; personal one that should follow the account across repos. Mirrors
;; "programs" :both, and the project layer wins on a same-id collision — the
;; same precedence order the params roots already use.
"predictors" :both
```

Unlike params, this is **name resolution, not layering**: the project file wins
whole and the shadowed user file is *reported*, never merged. Shadowing is
announced for the same reason `tool/register-def!` announces a second file
claiming one `:name` — a definition that silently loses is a bug the user cannot
see.

---

## 4. Ids: `user/` is a prefix, and the built-ins are not writable

`predictor/valid-id?` already restricts ids to path-safe segments (no `.`, `..`
or separators beyond `/`) because an id **is** a relative file path under a
params root. That is necessary but not sufficient here, because
`predictor/register!` is **last-wins by id**:

```clojure
(swap! !registry assoc (:predictor/id p) p)
```

A user predictor loaded at boot *after* `memory.core.signatures` has loaded, and
claiming `memory/graph-extract`, would silently replace the built-in and hijack
every graph extraction in the process. Two independent guards, because either
alone leaves a hole:

- **Authored ids are namespaced `user/…`** — `predictor$create` prepends it when
  the author omits it, and refuses an id whose first segment is any other
  namespace. Structural, visible in `program$list`, and it keeps the file tree
  self-describing (`predictors/user/…`, `programs/user/…`).
- **Registration refuses to replace an id that is already registered from
  source**, and says which built-in it collided with. This is the backstop for
  the case the prefix rule cannot cover — a future built-in shipping under
  `user/…`, or a loader called out of order.

**Overriding a built-in is not blocked, it is redirected.** The sanctioned way
to change how `memory/graph-extract` behaves is a params file
(`programs/memory/graph-extract.edn`) — instructions, demos, lm — which is
reviewable, layered, comparable against zero-shot by `program$eval`, and cannot
change the field set the calling source expects. The agent's instruction says
this in one line and hands the user to `program$*`. A user redefining a built-in
predictor's *signature* would break its call site, which reads specific output
keys out of the result map.

---

## 5. Invocation — an authored predictor registers as a tool

A definition nothing can call is a dead file. The question is which call path,
and there are three candidates:

| path | verdict |
|---|---|
| `query$llm :predictor-id …` (proposal §Phase 4) | **No.** `query$llm` has one contract — prompts in, results out. A second contract where `:prompts` is meaningless and `:inputs` is required is a different command wearing the same name. |
| a bespoke `predictor$run :id :inputs {…}` | Sufficient, but it is a *second* dispatch surface that has to re-derive coercion, permission gating and depth guards that already exist. |
| **register it in `!tool-defs` as `user$predictor$<name>`** | **Yes.** |

The third reuses the decision `user-tools` already made and validated: register
into the **same** `agent.core.tool/!tool-defs` registry `deftool` uses, and the
authored thing immediately appears in `list-tools` / `search`, flows through
`call-tool`'s Malli coercion and its hook / permission / depth guards, and is
auto-bound into agent sandboxes as a directly-callable symbol — callable **in
the same turn it is created**, from both the code-block channel and the
tool-calls channel.

The mapping is mechanical, which is the point:

```
signature :inputs   ->  tool :input-schema   [:map [:entry [:string {:desc …}]]]
signature :outputs  ->  tool :output-schema  [:map [:kind …] [:rationale …]]
tool :description   ->  the signature's :instructions, first line
tool :fn            ->  (fn [args] (:outputs (clj-llm/run-predictor p (clean args))))
```

Note what the `:fn` is **not**: there is no sandbox, no fork, no body timeout,
no `install-bodies!` phase. It is a direct call into `run-predictor`, so params,
demos, LM/tier resolution, the trace sink and the prediction log all apply
exactly as they do to `memory/graph-extract`.

Three details that are load-bearing:

- **`:cot`'s reasoning is a SIBLING of `:outputs`, not one of them.**
  `chain-of-thought` augments the JSON Schema it *sends* with a leading
  `reasoning` property, then `dissoc`s that key before validating the rest
  against the signature and returns it alongside — so the tool's result schema
  is `{:outputs {…signature fields…} :reasoning …}`. Declaring `reasoning`
  inside `:outputs` would describe a shape the call never returns; declaring it
  as a signature output field is worse, since it would then be stripped and
  refilled with a schema default on every call (which is why `predictor$validate`
  refuses one).
- **The `:fn` returns `(:outputs result)` plus `:reasoning` when present, not the
  raw result map.** `run-predictor`'s result also carries `:predictor-id`,
  `:params-source`, `:usage` and the raw completion — useful to `program$eval`,
  noise in a tool result that an LLM reads on every call. `:params-source` in
  particular would put a filesystem path into the model's context on every
  invocation.
- **A per-call `:lm-config` follows the `query$llm` rule, including the
  refusals.** `run-predictor` already treats a call-site `:lm-config` as
  outranking params, deliberately ("the user's configured model; a params file
  compiled elsewhere must not silently re-route it"). Exposing it per call is
  consistent — but it is resolved through the same `resolve-query-lm` that
  refuses `:base-url` and `:api-key`, for the same exfiltration reason: a
  predictor's `:inputs` carry whatever the caller gathered.

---

## 6. Validate → create → verify

The authoring contract mirrors tool-agent's hard rules, with the steps that mean
something different here called out.

**`predictor$validate` (dry-run, offline, persists and registers nothing).**
It compiles the signature and reports structured findings rather than throwing:

- `:id-ok` — `valid-id?`, and the `user/` namespace rule (§4).
- `:collision` — an id already registered (from source, or from another
  definition file), with the source named. True is not fatal for a *refine* of
  one's own predictor; it is fatal against a built-in.
- `:signature-ok` — `compile-signature` succeeded, so `fields->json-schema`
  derived a schema for every output field. This catches the realistic failures:
  a Malli form that has no JSON Schema mapping, a `:desc` on a registry-ref
  schema, a nested `[:map …]` output the provider's structured-output mode
  cannot express.
- `:outputs-ok` — `:outputs` is non-empty. An empty output map compiles to an
  empty JSON Schema and the call returns nothing; the signature is
  well-formed and useless.
- `:strategy-ok` — `:predict` or `:cot`, and for `:cot` that the author has
  **not** declared their own `reasoning` output: `chain-of-thought` dissocs that
  key before validating, so the declared field would be filled with a schema
  default and come back empty on every call. Refused rather than silently
  neutered.
*(Built without the `:sample` live call this section originally proposed.)* A
tool must smoke-test its body **before** create, because a body that fails to
eval would register a tool that cannot run. A predictor has nothing that can be
broken at create time which the offline checks above did not already catch — so
the behavioural check moves to where it is cheaper and more honest: **call
`user$predictor$<name>` after create**, which is a real invocation of the real
thing rather than a throwaway probe of a copy. Same "verify before claiming
success" rule as tool-agent, one step later.

### `:input-order` must always be written, and this is not optional

`compile-signature` takes input order from the `:inputs` map's own ordering, and
**throws** past 8 inputs because a map literal beyond that size is a hash-map.
Measured on this workspace's Clojure:

```
(edn/read-string "{:a 1 … :h 8}")  -> PersistentArrayMap  (:a :b :c :d :e :f :g :h)
(edn/read-string "{:a 1 … :i 9}")  -> PersistentHashMap   (:e :g :c :h :b :d :f :i :a)
```

Source predictors mostly dodge this because a `defsignature` literal is read
once by the Clojure reader and reviewed by a human. A predictor read from EDN on
every boot does not dodge it: at nine inputs the order is whatever the hash
seed says, and **input order is cache-significant** — the user message renders
inputs in this order, so a reordering silently destroys the turn-stable prompt
prefix that prompt caching depends on. Worse, it would be *stable within a
process and different across processes*, which is the hardest shape of bug to
notice.

So `predictor$create` **always** persists an explicit `:input-order` vector,
derived from the order the author declared, regardless of field count, and
`compile-signature` is always called with it. The `>8` throw then becomes
unreachable for user predictors rather than a trap waiting at the ninth field.
The same reasoning applies to `:outputs` for render order, though the
consequence there is cosmetic rather than cache-relevant.

**`predictor$create`** requires a passing validate, writes
`predictors/<id>.edn` (pretty-printed, `:input-order` included), and registers
both the predictor and its `user$predictor$<name>` tool. Keyed on id, so
re-creating overwrites — that is how "update" is expressed, same as user tools.

**Verify** is a real call to `user$predictor$<name>` with a representative
input. The agent must distinguish three outcomes that read alike and mean
different things: a *definition* failure (the tool is broken), a *validation*
failure (`:valid? false` — the model answered off-schema, which `predict` logs
rather than throws), and a *content* disagreement (well-formed output the user
thinks is wrong — that is a job for instructions, or for `program$eval`).

---

## 7. What the agent owns, and where it hands off

```
predictor-agent
  ├─ predictor$list | read | validate | create | delete     ← new, this doc
  ├─ program$list | build-dataset | eval | compile           ← already in the COMMON roster
  └─ hands off:
       • a built-in's behaviour  -> program$* (params), never predictor$create
       • accepting a proposal    -> `by programs accept` (CLI only, by design)
       • a code body / side effect -> tool-agent
       • a multi-turn specialist   -> meta-agent
```

`program$*` is bound into the common roster (`commands.clj:1393`), so
predictor-agent does not need to claim it — but it is the natural continuation
of the authoring arc, and the instruction says so: *author → verify →
`program$build-dataset` → `program$eval` → `program$compile` → hand the human a
`by programs accept` line.* That arc is the reason this agent exists as a front
door rather than as five loose commands.

**`accept` stays out of reach, unchanged.** The existing rule — "a proposal's
demos are text harvested from what a teacher read, destined for a system
message; an agent that could accept its own proposal would be choosing its own
future instructions" — is *more* pointed for a user-authored predictor, not
less, since the agent also wrote the signature. Nothing in this proposal moves
that line.

**Dossier contract.** Per the front-door convention
(`schedule-agent` / `event-agent` / `state-machine-agent`): every write-producing
turn writes a markdown dossier under
`.brainyard/agents/predictor-agent/dossiers/<ts>-<slug>.md` and prepends to
`INDEX.md`, enforced by a `FINAL-STEP CHECKLIST` in the instruction. The dossier
records the signature as created, the sample verification, and — when the arc
continued — the eval score it was measured at, which is the number a later
compile is trying to beat.

---

## 8. Boot wiring — one function, not `boot-registries!`

The natural-looking home is `boot.clj`, alongside skills / user tools / user
agents. It is the wrong one, for a concrete reason: **`by programs list|eval|
compile` never calls `boot-registries!`.** They call `install-predictor-params!`.
A user predictor registered only via `boot-registries!` would be invisible to
exactly the commands this feature exists to unlock — `by programs eval
user/changelog-classify` would answer "unknown predictor".

And the split `boot.clj` exists to manage does not apply: there is no body to
eval against an agent's tool palette (§2), so there is no phase 2.

So the app's `install-predictor-params!` grows a third responsibility and a
name that matches it:

```clojure
(defn- install-programs!
  "Params roots, tier resolution, and user-authored predictor definitions —
   the three things that make `.brainyard/programs` and `.brainyard/predictors`
   real. One function because every entry point that needs one needs all three:
   a predictor with no params root resolves nothing, and a params file for a
   predictor that was never registered is a file nobody reads."
  []
  … set-params-roots! … set-lm-resolver! …
  (agent/register-user-predictors! dirs))
```

Existing call sites already cover every path that matters: `run-tui!`,
`cmd-ask`, the detached `by memory reduce`, and `cmd-programs-{list,eval,compile}`.
Two ordering rules, both already observed by the surrounding code and both easy
to break later:

- **After `install-working-dir!`**, or `<project>/.brainyard/predictors` resolves
  against the wrong project — the same rule `register-project!` documents.
- **Registration must not be a namespace-load side effect.** The native-image
  policy initializes `ai.brainyard.*` at *build* time, so a load-time scan would
  bake the build machine's directory listing into the image heap and never read
  the user's. Same `defonce`-atom-plus-runtime-CAS guard `boot.clj` uses, and
  the same reason.

Failure is swallowed and logged: one corrupt `.edn` must not cost the user every
other predictor (the `read-persisted` rule from `user_tools.clj`), and no
registry load may block a session.

---

## 9. What this does *not* add

- **No new mechanism in `clj-llm`.** `predictor.clj` is untouched.
  `register!` / `predictor` / `run` already accept a runtime-built signature —
  the BT `dspy` node with a `:predictor-id` builds an ad-hoc predictor per call
  today. This proposal only adds a persistent, named, discoverable source of
  those spec maps.
- **No new optimizer, metric, or dataset builder.** An authored predictor is a
  registry entry, and `programs.clj` keys on registry entries.
- **No change to the params/accept trust boundary** (§7).
- **No sandbox, no new privilege.** A predictor sends text to a configured
  provider and parses the reply. It cannot read a file, run a command, or reach
  a host the agent could not already reach — which is why this agent needs
  nothing like the `:agent.tool-use/pre` fail-closed gate `mcp-agent` carries.
- **Not a replacement for user tools.** A tool *does* something; a predictor
  *decides* something. The composition the instruction should teach is a user
  tool whose body calls `(user$predictor$classify :entry …)` — the tool keeps
  the side effect, the predictor keeps the id, the params and the measurability.

---

## 10. Phasing

| Phase | Scope | Exit criterion |
|---|---|---|
| **1 — done** | `user_predictors.clj`: persistence, `user/` namespacing + collision refusal, always-explicit `:input-order`, registration as predictor **and** as `user$predictor$<name>`; `predictor$validate|create|list|read|delete`; `subdir-scope-policy` entry; `install-programs!` wiring | author a predictor and call it in the same turn; `by programs list` shows it with its params source |
| **2 — done** | `predictor_agent.clj` — instruction + tool-context + dossier checklist; router-agent registration (directory, decision table, summary) | a plain-language ask ("make me something that classifies changelog lines") produces a verified predictor and a dossier |
| **3** | The arc: instruction wires `program$build-dataset` → `program$eval` → `program$compile` → a `by programs accept` hand-off; dossier records the baseline score | a user predictor reaches a reviewed proposal without anyone editing Clojure |
| **4** (optional) | `predictor$from-tool` — derive a draft signature from an existing `query$llm`-wrapping user tool, so the prompts people have already written become addressable | one migrated tool measurably scored |

Tests follow the house shape for this family: a structural suite (persistence
round-trip **including a 9-input order check**, `user/` refusal, built-in
collision refusal, `:cot` output-schema derivation, delete-leaves-params) and a
hermetic pass-through suite against a fake LM, contacting no provider.

---

## 10a. As built (Phase 1, 2026-09-16)

Shipped as designed, with three departures worth recording.

- `components/agent/.../common/user_predictors.clj` — naming (`parse-name`
  accepts `foo` and `user/foo`, refuses every other namespace), persistence
  (`<root>/user/<name>.edn`, pretty-printed, read only by `clojure.edn`),
  `read-persisted` across both scopes with the project winner and a
  `::predictor-shadowed` warning, `compile-record` / `->predictor`,
  `register!` / `unregister!` / `register-persisted!` / `ensure-registered!`
  (defonce-atom guard + runtime CAS, per the native-image rule), and the five
  `predictor$*` commands. 11 tests, 84 assertions, hermetic.
- `clj-llm`: `predictor/unregister!` plus `register-predictor!` /
  `unregister-predictor!` on the interface. Nothing else in `predictor.clj`
  moved — `predictor` and `run` already accepted a runtime-built signature.
- `core/config.clj`: `"predictors" :both`.
- `agent/interface.clj`: side-effecting require (so the `defcommand`s register),
  `predictors-commands` export, and a `register-user-predictors!` wrapper.
- `main.clj`: `install-predictor-params!` → **`install-programs!`**, now also
  registering authored predictors. All six existing call sites carry over.
- `user_tools/list-user-tools` excludes `:predictor-id` entries — an authored
  predictor is a `:user-defined` entry in the same registry, and tool-agent must
  not offer to read or delete a file it has no reader for.

**Departures from this doc:**

1. **No `:sample` in validate** — see §6. The check that mattered moved to
   after create, where it is a real call rather than a probe of a copy.
2. **`:cot` reasoning is a sibling, not an output** — the doc had this as a
   `prepend-output` signature transform, which is how `predictor.clj` offers it
   but *not* how `run`'s `:cot` strategy works. Corrected in §5/§6.
3. **The ">8 inputs" check reads the raw ARGUMENT, not the drafted record.**
   The first implementation compared `(set input-order)` against
   `(set (keys inputs))` on the normalized record — which passes trivially,
   because the record's `:input-order` is *derived from* `(keys inputs)`, hash
   order and all. Caught by the test, and it is the exact failure the rule
   exists to prevent: a check that validates the scrambling against itself.

**Verified end to end**, not only in tests: a hand-written
`.brainyard/predictors/user/changelog-classify.edn` is listed by
`bb tui programs list` beside the four built-ins; and driving the real
`tool/call-tool` dispatch with tool-calls-channel-shaped args (schemas as EDN
strings) runs validate → create → list → *call the predictor* → read → delete,
with the call returning `{:outputs … :valid? true :reasoning …}` and none of
the usage / params-source / raw-response noise.

---

## 10b. As built (Phase 2, 2026-09-16)

- `components/agent/.../common/predictor_agent.clj` — a `coact/run-coact-derived`
  `defagent` with a pinned `:bt-factory`, in the shape of `schedule-agent` /
  `config-agent`. Six capability kinds (SHOW / AUTHOR / REFINE / MEASURE /
  OPTIMIZE / RETIRE), the validate→create→verify authoring flow as HARD RULES,
  five guidances, four hand-offs, nine hard rules, edge cases, and the
  FINAL-STEP dossier checklist. 30 tools.
- `agent/interface.clj` — the side-effecting require (built-in `defagent` roster).
- `router_agent.clj` — all three registration points: the agent directory, the
  lettered decision table (**`Y. PREDICTOR-LIFECYCLE`**), and the summary list.
- `predictor_agent_test.clj` — 8 tests, 73 assertions: structural, instruction /
  tool-context anchors, and a check that all three router points are present.

**Three scope decisions worth recording:**

1. **It owns BOTH command families, and `program$*` is bound explicitly.**
   Authoring and measuring are one arc — a user asking "make this cheaper"
   should not be routed twice. `program$*` also rides the *common* roster, but a
   derived agent's `:agent-tools` **is** its roster, so inheriting it is not
   something this agent may assume; the test asserts the explicit binding,
   because the arc breaks silently in the middle if it regresses.
2. **Accept stays unreachable, and the test asserts the absence of the
   command, not merely its absence from the roster.** A future
   `defcommand program$accept` would then fail here rather than quietly handing
   the agent its own future instructions.
3. **Two guidances exist only because this codebase measured them.** G4 (read
   `:enable-prediction-log` before promising a dataset — it is off by default,
   so `program$build-dataset` would otherwise return an empty result that reads
   as a finding) and G5 (never a bare score — the graph-extract re-measurement
   put run-to-run noise at ~0.05 on a 12-example val set, with the *same*
   configuration scoring 0.559 and 0.605 on two passes).

**Verified live**, against the real LLM in a scratch project:

- *"What predictors do I have?"* — read sweep across `predictor$list` +
  `program$list`, authored and built-in separated with the params-not-
  redefinition rule stated, the prediction-log gate flagged before any dataset
  was promised, and no dossier for a pure SHOW read. Correct on every count.
- *"Make me a predictor that scores how risky a git diff hunk is…"* — validated,
  created `user/diff-hunk-risk` (`:cot`, `:hunk` → `:risk` + `:reason`), **called
  it** on a hunk deleting `verify_signature`/`is_expired` and got
  `{:risk "high" :reason "Authentication bypass…" :valid? true}` with reasoning
  beside `:outputs`, then wrote the dossier and prepended to `INDEX.md`. It also
  declined the tier claim on its own ("no `:tier` set, so no claim that a cheap
  model was selected") and volunteered the tool-agent composition ("a predictor
  is data: it cannot run `git diff` itself").

### The router lettering, and what cleaning it turned up

Registering a 28th move exposed that router-agent's decision table had reused
`U` (ACP *and* TOOL-LIFECYCLE) and `V` (META-RESUME *and* AGENT-LIFECYCLE), and
carried an `O2` from an earlier insert that dodged a renumber. **Renumbering was
not available: 28 moves do not fit in 26 letters.** So the letters were removed
and the move NAME became the identity — which it effectively already was, since
`router/valid-shapes` and `router_agent_hooks/specialist->shape` key on the name
and nothing anywhere parses a letter. Cross-references read better for it
(`NOT skills (SKILL-LIFECYCLE)`), and a 29th move can no longer collide.

Removing the letters meant touching the two registries whose comments indexed
them, which is where the real defect was: **both had drifted, and the drift was
invisible.** `valid-shapes` held 22 of 28 moves and `specialist->shape` 17 of
23 specialists, so routing to `a2a-agent`, `schedule-agent`, `event-agent`,
`state-machine-agent`, `script-agent` (and `predictor-agent`) logged
`:unspecified` — the same value `coerce-shape` produces for a garbled parse. The
router's own history could not say it had decided anything on those turns. Both
maps are now complete, and `every-table-move-has-a-shape` parses the table out
of the live instruction and asserts the three stay in step, so the next move to
be added fails a test instead of silently logging as undecided.

---

## 11. Open questions

1. **Project vs. user default scope for `predictor$create`.** Params default to
   project-first; a *definition* arguably belongs to the person more often than
   to the repo. Proposal: default **project**, matching where the params and
   datasets will land, with an explicit `:scope :user` argument.
2. **Should an authored predictor be usable as a metric?** `programs.clj` has
   named metrics; an LM-judge metric is "a program that is itself a program" in
   DSPy terms, and a user predictor is now exactly that shape. It is a real
   capability and a real footgun (a judge nobody calibrated — proposal §11 Q4),
   so: not in phase 1.
3. **Does `predictor$read` show resolved params?** Showing them makes the
   definition/params split legible in one call; it also puts demo text — which
   may be redacted trace content — into the transcript. Proposal: report the
   params **source and version**, not their content, and point at
   `programs/<id>.edn`.
4. **`:strategy` beyond `:predict` / `:cot`.** `run` hard-codes the pair. The
   proposal's phase-4 modules (`best-of-n`, `refine`, `multi-chain-comparison`)
   would each be a new strategy keyword — worth confirming that a user-authored
   predictor should get them at the same moment a source one does.
