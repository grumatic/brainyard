# Script-Agent — A Two-Channel CoAct Where the Tool Registry Is a Directory

> **Status: SHIPPED — P0, P1 and P2.** This document describes what is built,
> not what was proposed; the as-built corrections that used to sit in a delta
> block here are folded into the sections they belong to.
>
> **Scope:** `common/script_agent.clj` (the agent), `common/scripts.clj` (the
> library), `common/script_bridge.clj` (P2), `common/coact_agent.clj` (the
> language gate and the registry-substrate gate), `core/config.clj`,
> `core/feature.clj`, `core/context_budget.clj`, `common/router_agent.clj`,
> `interface.clj`, and `by scripts` in the app project.
> **Tests:** `components/agent/test/…/script_agent_test.clj` — 22 tests,
> 202 assertions.
> **Built on:** `coact_agent.clj` via `coact/run-coact-derived`.
> **Sibling of:** `react-agent` (the other single-action-channel agent — it
> pins the *opposite* channel off).
>
> **The four things that turned out differently from the proposal**, each
> explained where it lives:
> - The five base **substrates** had to be gated too, and that was the bulk of
>   the prompt saving — §6.6.
> - The library has **two renderings**, not one; a registry agent gets names
>   only — §5.3.
> - A script's name is its **filename**, never its `# name:` header — §5.2.
> - The telemetry event is per **block**, not per invocation, because the
>   question is a ratio — §9.

---

## 1. What it is, in one paragraph

`script-agent` is CoAct with two channels instead of three: **code-blocks**
(bash and python only) and **answer**. There is no `tool-calls` channel, no
Clojure fence, no JavaScript fence, and no SCI sandbox. Its tool surface is not
a registry — it is a **directory of executable scripts**. The model writes a
script with a heredoc, `chmod +x`s it, and from that point on invokes it by
bare name, in this turn, in later turns, and in later sessions. The library
index is what gets rendered into the system prompt in place of CoAct's
`## Tools` section.

That single substitution — registry → directory — is the whole design. Every
other decision below follows from it, including the two that were not obvious
until it was built: that the registry-shaped *prompt* has to go with the
registry (§6.6, and it is 58% of the static system prompt), and that agents
which keep their registry still want to know the library exists (§5.3).

---

## 2. Motivation

### 2.1 Two agents already prove the shape is supported

CoAct's action channels are independently gated:

```clojure
;; react_agent.clj:77
:config-extra {:code-channel? false :tool-channel? true}
```

`resolve-action-channels` (coact_agent.clj:1734) enforces only that at least
one survives, and `think-act-code-signature` (:1761) *drops the disabled
channel's output field from the compiled DSPy schema* rather than merely
telling the model not to use it. The comment there states the principle this
design leans on:

> react-agent has long been told 'there is no code-blocks channel' while its
> schema still demanded `code_blocks`. Dropping the field makes the contract
> structural.

`script-agent` is the mirror image of react-agent: `{:code-channel? true
:tool-channel? false}`. That combination is *already* handled — `build-tools-section`
has a code-only arm, `:role` resolves to `coact-role-code-only`, the JSON
envelope section is dropped. Nothing in this design fights the existing
machinery; it extends the same gate along a second axis (language) and swaps
what fills the tool section.

### 2.2 What is actually heavy about CoAct

Measured against the current source, the *static* system prompt — before a
single tool spec or sandbox binding is rendered:

| Section | chars |
|---|---:|
| `coact-instructions-template` (DSPy `:instructions`) | ~8,960 |
| `execution-model-sandbox` + `sandbox-context-accessor` | ~9,730 |
| `coact-tools-overview` + `coact-tools-hotpath` | ~4,430 |
| `coact-code-blocks-format` | ~3,670 |
| role variants + channel-routing + tool-call-format | ~4,910 |
| critical-rules + large-results-playbook | ~3,210 |

On top of that, a bound agent renders `### Sandbox Categories` (the SCI
function index) **and** `### Agent Tools` (full per-tool specs). For
`default-agent-roster` that spec block covers every common tool and command
family plus the skills-read subset and the whole MCP command family — dozens
of entries, each with a description and a parameter list.

A script-only agent legitimately drops: the whole SCI/sandbox execution model,
the state-memory accessor contract, the JSON tool-call envelope, the
channel-routing heuristics ("when to use which channel" earns nothing when
there is one action channel), the sandbox function index, and the agent-tools
spec roster. What replaces the last two is **one line per script**.

### 2.3 Why the model should be able to write its own tools as files

`tool-agent$create` already lets the LLM author a runtime tool. It costs:
an SCI `(fn [args] …)` body that must be EDN-serializable, persistence to
`.brainyard/tools/<name>.edn`, rehydration by re-eval in a dedicated forked
sandbox, and registration into `!tool-defs` so it appears in `search` /
`list-tools`. That is a lot of mechanism, and it buys a callable the model
cannot `cat`, cannot run outside brainyard, and cannot debug with a print
statement.

A script buys the same reuse with `chmod +x`. It is readable by `cat $(which
foo)`, runnable from a terminal by a human, forkable by copying a file,
debuggable by adding `set -x`, and version-controllable because it is a file in
the repo. The registry's advantages — Malli coercion, hook/permission gating,
depth guards — are real, but they are advantages *for tools that reach
privileged surfaces*. A script reaches exactly what a `bash` fence already
reaches, so it needs no gate the bash fence does not already have (§8).

---

## 3. Design principles

1. **The contract is structural, never prose.** A language the agent may not
   use is removed from the schema description and refused at dispatch — not
   discouraged in the instruction. This is the react-agent lesson applied to
   languages instead of channels.
2. **The tool surface is a filesystem, not an API.** Discovery is `ls`.
   Inspection is `cat`. Creation is a heredoc plus `chmod`. There is no
   `script$create` command, because a command that writes a file is a worse
   `write-file`.
3. **One line of prompt per tool.** A script contributes `name — desc` to the
   index and nothing else. Its full contract is its `--help` and its source,
   both one bash block away and neither paid for unless wanted.
4. **Nothing new is privileged.** A script is a file the bash fence runs. The
   library adds persistence and discoverability, not reach.
5. **Degrade, don't fail.** No library dir ⇒ no `### Scripts` section and the
   agent is a plain bash/python CoAct. An unparseable header ⇒ the script lists
   as `(undocumented)` and still runs.

---

## 4. Position in the agent stack

```
                 tool-calls   code-blocks       answer   tool surface   library
react-agent          ✓             ✗              ✓      registry       —
coact-agent          ✓      clj/bash/py/js        ✓      registry       :brief
script-agent         ✗         bash/py            ✓      directory      :full
```

`script-agent` is a **leaf**: it dispatches no sub-agents (§7.3), and the
script bridge deliberately does not reopen that door. The
router may dispatch *to* it, and should, for anything whose natural expression
is a shell pipeline or a small python program over local files — data munging,
log triage, format conversion, repo-wide mechanical edits, build/CI probing.

---

## 5. The script library

### 5.1 Layout and precedence

```
<project>/.brainyard/scripts/bin/     project scope  (highest precedence)
<project>/.brainyard/scripts/lib/     importable modules for the above
~/.brainyard/scripts/bin/             user scope
~/.brainyard/scripts/lib/
<builtins>/bin/                       shipped with the binary (lowest)
```

Every bash/python block runs with

```
PATH=<project>/bin:<user>/bin:<builtin>/bin:$PATH
PYTHONPATH=<project>/lib:<user>/lib:<builtin>/lib:$PYTHONPATH
```

so a bare `pdf-pages foo.pdf` resolves, and a project script **shadows** a
same-named user or builtin script. Shadowing is deliberate: forking a builtin
by copying it into the project and editing it is the intended way to specialize
one. The index reports it (`pdf-pages (shadows builtin)`) so the model is never
guessing which one it just ran.

`cwd` for every block is already `config/project-dir` (`run-script-block`
anchors at the git root, matching the `bash` tool). Scripts inherit that, so a
script's relative paths mean the same thing as a fence's relative paths.

### 5.2 The header contract

The only ceremony, and it is three comment lines:

```bash
#!/usr/bin/env bash
# name: changed-since
# desc: List files changed since a git ref, filtered by extension.
# usage: changed-since <ref> [ext...]
set -euo pipefail
...
```

**`name` is documentation, never the name.** The library name is the FILENAME,
extension included, because that is what PATH resolves — anything else is a
promise the shell will not keep. This was wrong in both directions before it
was right: listing `pdf-pages.py` as `pdf-pages` advertises an invocation that
fails, and honouring a header that disagrees with its file is worse. Copying
`clj-count` to `fetch` without editing its header made the index show a second
`clj-count` shadowing the first, while the project `fetch` — the file that
actually shadows the builtin on PATH — vanished from the listing entirely.
`scripts-doctor` reports the disagreement instead.

`desc` is what appears in the prompt index. `usage` is *not* rendered there —
it is for `--help` and for a human reading the file.

**There is deliberately no parameter schema.** A JSON-schema'd argument list
would recreate exactly the registry ceremony this design exists to delete, and
it would be worse than nothing: a schema is validated *before* the script runs,
so a stale or wrong schema turns a working script into an unreachable one,
while a wrong `usage:` comment merely misinforms and is corrected by running
the thing. The script's real contract is its exit code and its stderr.

### 5.3 What goes into the prompt — two renderings, not one

**`:full`** — for an agent with no clojure fence, whose tools ARE the library:

```markdown
## Scripts — your reusable tools
Every script below is on PATH for every bash/python block. Call it by bare name.

changed-since   — List files changed since a git ref, filtered by extension.
pdf-pages       — Print the page count of a PDF.
fetch           — HTTP GET a URL with a timeout and a size cap.
…and 3 more — run `scripts-ls` for the full list.

### Reading one
`cat $(which <name>)` — the source IS the contract.

### Writing one
[the heredoc skeleton, the `# desc:` contract, and §5.4's rule]
```

**`:brief`** — for an agent that HAS a registry (a clojure fence) and could
otherwise re-derive work the library already holds. Names only:

```markdown
## Scripts (already saved, in .brainyard/scripts/bin)
changed-since · pdf-pages · route-report
Run one directly — `bash .brainyard/scripts/bin/<name>` — or `cat` it to see
what it does. Before writing a shell pipeline, check whether one of these
already is it. script-agent owns adding to the set.
```

Chosen by `coact-agent/script-library-mode`, which returns `:full`, `:brief`,
or nil (no code channel — the feature requires one).

**Why a second rendering rather than the same one.** The full section teaches
*authoring*, and none of that is a registry agent's job. What it needs is one
fact — these exist — so it can run one or hand the work to script-agent instead
of re-deriving it. This was added after measuring the gap: asked "which design
docs are over 1000 lines?", router-agent chose `code-compose` and hand-rolled a
`find`/`wc` pipeline the library already held, because nothing in its prompt
said the library existed. With `:brief` present the same question routes to
`tool-fetch` with the reason *"a saved script already computes this exactly, so
I ran it directly rather than hand-rolling a find/wc pipeline"*.

**`:brief` excludes builtins, and that is what makes it affordable.** The pack
is materialized on first use, so counting it would make every repo look like it
had a library before anyone saved a script — and a router will never run
`scripts-new`. With only builtins present it renders nothing at all. Populated,
it is 290 chars against `:full`'s 1,279.

**PATH injection is `:full`-only.** Prepending directories without saying so is
a silent change to what a bare command resolves to; `:brief` names a path,
never a command, so nothing about resolution moves for an agent that did not
ask. The two halves must give the same answer, which is why one predicate
decides both.

Both are bounded by `:script-index-limit` (default 60), overflowing to a
`…and N more` line rather than more rows.

### 5.4 When to write one — the rule that makes this work

A library nobody writes to is a directory of zero files, and a library the
model writes to on every turn is landfill. One rule, stated once in the
instruction:

> Inline code is for one-off work. When you find yourself typing the same
> pipeline a second time, the third time save it: write it to
> `.brainyard/scripts/bin/<name>`, add the `# desc:` line, `chmod +x`, and call
> it by name from then on. Before writing anything non-trivial, check the
> Scripts index above — you may already have it.

The index being in the prompt at turn start is half the mechanism: the model
cannot re-derive a tool it can see it already has.

### 5.5 Builtin pack — four, plus one that is conditional

Ship four, not forty. A large builtin pack is a roster wearing a different hat,
and it would re-import the problem: prompt weight for capabilities the model
did not ask for and cannot easily audit.

They are **materialized from strings in `scripts.clj`**, not shipped as
classpath resources: a resource needs native-image resource-config, and a
missing entry fails at runtime with an empty library and no error anyone would
connect to the cause. A string compiled into the binary cannot go missing, and
rewriting on a content change makes a binary upgrade refresh the pack for free.

| script | why it earns a slot |
|---|---|
| `scripts-ls` | the overflow escape for `:script-index-limit`; prints name/desc/path/scope |
| `scripts-new <name>` | writes the header skeleton + `chmod +x` — removes the only step the model reliably forgets |
| `scripts-doctor` | syntax-check every script; report non-executable, shebang-less, header-less ones, and a `# name:` that disagrees with the filename |
| `fetch <url>` | `curl` with sane flags, a timeout, and a size cap — the one thing bash gets wrong by default |

**`by-tool`** is a fifth, materialized only when `:enable-script-bridge` is on
and PRUNED from the builtin scope when it is off (§13, P2). Without the prune,
the first person to try the bridge leaves a permanent index entry that answers
"the bridge is off" — an advertisement for a door that is not there.

Everything else starts life as a project script.

---

## 6. Core changes to CoAct

Six edits, each mirroring an existing pattern. The alternative — a
prose-only agent that merely *asks* the model not to emit Clojure — is rejected
for the reason `think-act-code-signature`'s own docstring gives: the output
contract is what the provider enforces, and a prompt that contradicts it is the
bug, not the fix.

### 6.1 `:code-langs` — a new gate on the same axis as `:code-channel?`

```clojure
;; core/config.clj — config-schema
:code-langs {:default #{:clojure :bash :python :javascript}
             :desc    "Languages the code channel will execute. A fence in any
                       other language is refused, not run."}
```

Resolved per-agent exactly like `:code-channel?`, so `script-agent` sets it in
`:config-extra` and nothing else in the tree changes:

```clojure
:config-extra {:code-channel? true
               :tool-channel? false
               :code-langs    #{:bash :python}}
```

Feature-flag registration under the existing `:exec` family, so `by config`
and the feature surface see it:

```clojure
:exec/script-library
{:title "Script library" :family :exec :gate :enable-script-library
 :keys [:script-lib-dirs :script-index-limit]
 :requires #{:exec/code-channel} :lifecycle :session
 :doc "PATH-injected directory of reusable scripts; replaces the tool roster
       for code-only agents."}
```

### 6.2 Signature and instructions become language-aware

`think-act-code-signature` is memoized on `[code? tool?]`; the key becomes
`[code? tool? langs]`. Two things it feeds change:

- **`::code-blocks` description** currently names all four languages inline and
  advises `(pmap f coll)` for Clojure fan-out. Rendered from `langs`, a
  bash/python agent's description names two languages and keeps only the
  `<!-- ParallelBlock -->` convention (which does apply to bash/python).
- **`render-instructions`** gains `langs` in its Selmer context. The two
  surviving worked examples in `coact-instructions-template` are the fan-out
  ones; the `pmap` one is Clojure-specific and drops.

Memoization stays byte-stable per session (the schema sits at the head of the
system message, the most cache-sensitive position) because `langs` is a
session-lifecycle config value.

### 6.3 Dispatch refuses, it does not execute

```clojure
;; dispatch-code-block, coact_agent.clj:~3855
(and (langs-allow? agent lang) (= "clojure" lang))   (exec-backend/exec-clj-code …)
(and (langs-allow? agent lang) (#{"bash" "python" "javascript"} lang))
                                                     (run-script-block …)
(not (langs-allow? agent lang))
{:lang lang :code code :result nil :output ""
 :error (str "Language `" lang "` is not enabled for this agent. "
             "Enabled: bash, python.")}
```

Refusal-as-a-value, not a throw — same shape as the existing
`"Unsupported language: …"` arm, so a stray fence costs one iteration with a
legible reason instead of an aborted turn.

### 6.4 Script-only prompt sections

In `coact-system-context`'s `cond->` (:1170):

- `:execution-model` selects `execution-model-script` (new) instead of
  `execution-model-sandbox` when `:clojure` is not in `:code-langs`. The new
  text covers only: the two interpreters, cwd = project-dir, the PATH/PYTHONPATH
  injection, the fast-eval → auto-background detach deadline and its
  auto-harvest, and output truncation-to-file. Everything about SCI,
  `context-get`, `[:user-vars]` and sandbox persistence is inapplicable and
  gone.
- `:sandbox-context-accessor` is dropped by the existing
  `(and code-channel? (not= :nrepl clj-backend))` guard being extended with
  `(langs-allow? :clojure)` — there is no sandbox to accessor into.
- `:code-blocks-format` selects a script variant: fence syntax, the
  `<!-- ParallelBlock -->` marker, the four-backtick verbatim-content fences
  (which are language-independent and still useful for writing files), minus
  every SCI string restriction.
- `:role` selects `coact-role-script-only`, which names the two real channels
  and says the tools are executables on PATH rather than sandbox callables.
- `:scripts` is a **new section** carrying whichever of §5.3's two renderings
  applies. It rides `:session-context` in `coact-system-zones` — it changes
  when a script is saved, which is exactly the session-stable cadence that zone
  exists for, and parking it in `:agent-core` would bust the largest cached
  prefix on a `chmod +x`. It also has a `default-section-policies` entry
  (priority 90, no compact strategy: it is already bounded at render time by
  `:script-index-limit`, and there is no tier below "one line per script" that
  still names the script).

`coact-system-order` derives from `coact-system-zones`, so adding `:scripts`
to the zone vector is the whole registration — the "compose drops sections
missing from the order" hazard is handled by construction.

### 6.5 No sandbox is created at all

This is where "lightweight" stops being a prompt-size claim. In `coact-init`,
a script-only agent skips: SCI sandbox creation, the per-turn sandbox fork, the
auto-tool-binding pass that binds every roster tool as a callable, and the
nREPL tool-namespace interning. `build-tools-section` receives no
`sandbox-bindings`, so its "bootstrap call describing nothing" guard already
returns nil and the section simply does not render.

`:agent-tools` is an explicit `{:tools []}`. `run-coact-derived` merges CoAct's
roster onto derived agents, so `merge-derived-tools` had to learn that an
explicitly EMPTY roster is a declaration and `nil` is an omission — without
that, the declaration is unexpressible and script-agent carries
`default-agent-roster`, a full spec block for tools it has no channel to call.
That is the one place this design touches shared merge behaviour; there is a
regression test pinning all three cases.

### 6.6 The registry substrates had to be gated too — and that was the bulk of it

The proposal counted the role, format and execution-model sections and stopped
there. It was wrong about where the weight was.

CoAct installs five base substrates — skill, MCP, todo, exec, subagent — and
both `coact-critical-rules` and `coact-large-results-playbook` are written
against the tool registry: `doc$read`, `usage$guide`, `read-file :lines`,
`todo$sync`, `edit-agent` dispatch. For an agent with no registry these are not
merely wasted tokens. They are **instructions it cannot follow**, and the cost
of that is an iteration spent discovering so.

The gate is one predicate, `registry?` = `tool-channel? OR a clojure fence` —
the two ways into the registry, since a clojure block auto-binds every visible
tool as a callable. With neither, the five substrates are dropped and the rules
and playbook select script-shaped variants: the same problems (a spilled
result, no history on iteration 1, both `.brainyard` roots) with shell verbs
(`sed -n`, `grep`, `head -c`) instead of tool calls.

**Measured: the static system prompt goes from 20,810 chars to 8,696** — 58%.
A full-channel agent's sections are unchanged, pinned by a test that asserts
every one of them still renders and still gets the *registry* variants.

---

## 7. What is lost, and what happens to it

Removing the tool channel removes real capability. Naming it honestly, with
what the script bridge (§13, P2) restores marked — **the bridge is off by
default, so read the unbridged column as the shipping default.**

| | unbridged (default) | bridge on |
|---|---|---|
| background execution | kept, free | kept |
| task inspection (`task$detail`/`wait`/`cancel`) | lost | restored |
| memory recall / status | lost | restored |
| artifacts, `trajectory$search` | lost | lost |
| sub-agent dispatch | lost by design | still closed |
| MCP | lost | lost |

### 7.1 Background work — **kept, for free**

CoAct's auto-background detach and the harvest at iteration start
(`coact-inc-iter-action` resolving pending evals) are channel-independent —
they operate on the task manager and the `::eval-entry` records, not on
`tool-calls`. A block that exceeds `:auto-background-timeout-ms` still detaches,
still returns a `:pending` entry with a `task-id`, and is still folded back in a
later iteration.

What the unbridged agent loses is the *interactive* surface: `task$detail`,
`task$wait`, `task$cancel` are registry tools. It backgrounds explicitly
instead — `cmd > .brainyard/run-<name>.log 2>&1 &` then read the log in a later
block, which the execution-model section teaches. That is strictly less
ergonomic, and it was the strongest single argument for P2. With the bridge on,
all three are on the default allowlist.

### 7.2 Memory recall, artifacts, project memory — **partly lost**

`memory$recall`, `artifact$*` and `trajectory$search` are registry tools.
Project memory and BRAINYARD.md still ride the *system context* — they are
prompt sections, not tools — so the agent keeps its standing instructions and
project notes regardless. `memory$recall` and `memory$status` are on the
bridge's default allowlist; artifacts and trajectory search are not, and stay
unavailable.

### 7.3 Sub-agent dispatch — **lost by design, and still closed**

`script-agent` is a leaf. Making it a dispatcher would mean either the tool
channel (which it does not have) or putting agent tools on the bridge — and the
bridge deliberately does not carry them: a door opened for memory recall must
not also pass the write surface of every agent in the process. A leaf
specialist the router calls *into* is the right shape and matches how
`explore-agent` is used.

### 7.4 MCP — **lost**

MCP servers are reached through the registry, and `mcp$*` is not on the
allowlist. Note that many MCP servers are themselves stdio subprocesses; a
project script wrapping one is legitimate and exactly the pattern §5 is for.

---

## 8. Safety

A script is an executable file the model authored. The claim that this adds no
privilege rests on three facts already true of the bash fence:

1. **Same executor.** `run-script-block` writes a temp file and runs it; a
   library script is the same call with a stable path. Nothing new runs.
2. **The library is inside the existing write allowlist.** `.brainyard/` is
   always-allowed by the write gate, and under `--sandbox` the seatbelt policy
   confines writes to `~/.brainyard`, the cwd subtree, and tmp — so the library
   is writable and the rest of the disk is not. A project script cannot be
   written outside that containment.
3. **Project source stays mode-gated.** Writing to tracked source is governed
   by the permission-fn (`:auto-approve` / `:ask` / `:deny`) regardless of
   whether the write comes from a fence or a script.

Two things the persistence *does* change, and their answers:

- **A bad script persists.** A one-off fence dies with its iteration; a saved
  script is a lasting footgun. `scripts-doctor` (syntax-check every script) is
  in the builtin pack for this, and it is the natural thing to run after a
  turn that wrote one.
- **A builtin can be shadowed.** Allowed — forking is the point — but never
  silently: the index labels it. The model writes to the *project* dir; the
  builtin dir is managed by the binary, which rewrites its files on a content
  change and prunes what it no longer ships.

**The script bridge is the one part that DOES add reach**, and it is the reason
everything above is stated as narrowly as it is. Its answers, in full at §13:
it is off by default; what it exposes is an allowlist, not a filter over the
registry; the default set is read/observe only, with no write tool and no agent
dispatch; and an argument crossing it is coerced but never `read-string`-ed, so
it cannot become code.

---

## 9. Observability

**Shipped**, and the event is not the one §9 originally proposed. A bare
`::script-invoked` fires only when a script ran — a numerator with no
denominator, which cannot answer the question the library exists to raise:

> Is the library being *reused*, or is the model re-typing pipelines it
> already saved?

That is a RATIO, so the event is per **block**, and it fires even when nothing
was invoked:

```clojure
::script-block
  {:lang "bash" :invoked ["clj-count"] :reused? true :scopes [:project]
   :library 5 :exit "0" :failed? false :ms 403}
```

- `:reused?` over all `::script-block` events is the rate. Near zero means the
  index is in the wrong place or the save rule (§5.4) is wrong — and no amount
  of adding scripts fixes either.
- `:scopes` says whether the shipped builtins earn their slots (§5.5).
- `:failed?` separates "reused it and it broke" from "did not reach for it",
  which are opposite problems. A failed run of a library script still counts as
  a reuse.

Emitted from the **common tail** of `coact-code-eval-action`, after both the
sequential and parallel arms have produced their entries — instrumenting either
alone would report a rate for half the blocks. Silent when the agent has no
library, and wrapped so telemetry can never fail a turn. Agent identity rides
mulog's global context, so every event is already attributed per agent and
per turn.

**`scripts/resolve-invoked` reads command position, not text.** It splits each
non-comment line on shell separators and takes the head of each segment, plus
whatever follows an interpreter — so `bash .brainyard/scripts/bin/foo`,
`x | foo`, `a && foo`, `$(foo)`, `VAR=1 foo` and `./bin/foo` all count, while
`echo foo` and `grep -r foo .` do not. Matching bare names alone would have
measured only `:full` mode, since `:brief` never puts the library on PATH —
i.e. it would have answered for the agent that needed the answer least.
It is a heuristic and says so: a name built at runtime from a variable is
invisible to it.

**Reading it.** In-session, `log$search "script-block"`. Otherwise:

```
by scripts list           the library, highest-precedence scope first
by scripts reuse          the rate, by script and by scope
by scripts reuse --json   the same, for a dashboard
```

`reuse` streams the current app log plus its rotations, parsing only the
blank-line-delimited blocks that mention the marker — measured at 601 ms across
~200 MB, against the tens of seconds a full EDN parse of that would cost.
`list` is read-only: unlike a `:full`-mode turn it does not create the
directories or materialize the builtin pack, because inspecting a library
should not be the thing that brings one into existence.

**First measurement, six live turns:** 2 reuses / 4 script blocks. The one that
matters is the miss — router-agent had `clj-count` in its `:brief` index and
still hand-rolled `find | wc -l` for "how many clj files under
components/memory?", while on a different question it *did* reach for the saved
script. So the brief index changes the router's behaviour but does not
determine it. That is a datapoint, not a verdict: four blocks is not a sample,
and the point of shipping this is that the next hundred will be.

## 10. Configuration summary

| key | default | meaning |
|---|---|---|
| `:code-langs` | `[:clojure :bash :python :javascript]` | languages the code channel EXECUTES; a fence in any other is refused as a value. script-agent pins `[:bash :python]`. Rides `:exec/code-channel` |
| `:enable-script-library` | `true` | gate for `:exec/script-library`. Applies only to agents whose `:code-langs` exclude `:clojure` — see `script-library-mode` |
| `:script-lib-dirs` | `[]` (derive) | override the roots, highest precedence first; builtin is always appended and cannot be removed |
| `:script-index-limit` | `60` | max scripts rendered in either prompt rendering |
| `:enable-script-bridge` | **`false`** | gate for `:exec/script-bridge` (requires `:exec/script-library`). The one knob that adds reach |
| `:script-bridge-tools` | `[:memory$recall :memory$status :task$detail :task$cancel :task$wait]` | the `by-tool` allowlist. Not a filter over the registry; empty means the bridge answers nothing |

Feature flags: `:exec/script-library` and `:exec/script-bridge`, both in the
`:exec` family, both `:session` lifecycle. `:code-langs` rides
`:exec/code-channel` rather than a flag of its own — the languages are one
choice with one answer, and four independent booleans would make
`#{:clojure :javascript}` as expressible as the two combinations anyone wants.

Env vars follow the existing precedence (env > per-agent > session >
`.brainyard/config.edn` > schema default): `BY_CODE_LANGS` (comma- or
space-separated), `BY_SCRIPT_LIB_DIRS`, `BY_SCRIPT_INDEX_LIMIT`,
`BY_ENABLE_SCRIPT_LIBRARY`, `BY_ENABLE_SCRIPT_BRIDGE`.

**Configuring an agent programmatically layers correctly**, but only since this
work fixed it. `setup-agent-by-id` used to merge caller options over defagent
metadata SHALLOWLY, so `:config-extra {:enable-script-bridge true}` replaced
script-agent's own `:config-extra` wholesale and silently restored
`:tool-channel?` and the full `:code-langs` — a script-only agent turned back
into CoAct without a word. The `deftool` wrapper (the `call-tool` path) had
layered these two keys since it was written; `setup-agent-by-id` simply never
got it, so the same agent behaved differently depending on how it was reached.
Both now share `tool/merge-agent-options`.

---

## 11. Testing

`components/agent/test/ai/brainyard/agent/script_agent_test.clj` — 22 tests,
202 assertions, no LLM. `run-single-block` is driven directly, which is enough
because the language gate sits in it, above every executing arm.

1. **Registration** — the defagent is in `!tool-defs`, and its `:config-extra`
   pins `{:code-channel? true :tool-channel? false :code-langs [:bash :python]}`
   with an explicit `:agent-tools {:tools []}`.
2. **Signature** — no `tool_calls` field; the `code_blocks` description names
   bash and python and neither clojure nor javascript; the JSON schema does not
   grow; the two-arity form is *identical* to what it compiled before.
3. **Instructions** — no `pmap`, no `clojure`, no `SCI`; `ParallelBlock`
   survives (it applies to bash/python); heuristic numbering stays contiguous
   from 1 after the clojure rows are gated out.
4. **Dispatch refusal** — a clojure fence returns an `:error` map rather than
   reaching the evaluator (with a nil sandbox, reaching it would NPE, so a
   clean value is itself the evidence); javascript likewise; bash and python
   run; verbatim fences are untouched by the gate; a nil `:code-langs` refuses
   nothing.
5. **Prompt shape** — `## Scripts` present; no `## Tools`, `tool-call-format`,
   `sandbox-context-accessor` or `channel-routing`; all five registry
   substrates dropped; the rules/playbook are the script variants; nothing
   anywhere promises a ```clojure fence.
6. **Prompt size** — the script prompt is under 70% of the full CoAct one.
   This is the claim the design is sold on, so a registry section creeping back
   in fails the build rather than merely costing tokens.
7. **CoAct is untouched** — every section a full-channel agent had is still
   there, and it still gets the *registry* variants of the rules and playbook.
8. **Zone placement** — `:scripts` is in the system order and composes into
   `:session-context`, never `:agent-core`: the index changes on every
   `chmod +x`, and the static zone is the largest cached prefix.
9. **Roster merge** — `{:tools []}` survives the derived merge; `nil` still
   inherits; a non-empty roster still concatenates.
10. **Config resolution** — an instantiated `script-agent` resolves
    `#{:bash :python}`, `:tool-channel? false`, and an active library; an
    instantiated `coact-agent` resolves an INACTIVE one (PATH and prompt must
    agree, and for a registry agent the answer is no).
11. **PATH composition** — project shadows user shadows builtin, the shadow is
    marked, the host `$PATH` survives, a missing dir contributes no empty
    element, and `BY_SCRIPT_ROOTS` / `BY_SCRIPT_PROJECT_BIN` are exported.
12. **Quoting** — a root path containing `$(…)` and backticks is escaped, not
    substituted.
13. **Header parsing** — a `# name:` inside a heredoc a script *emits* does not
    rename that script (the `scripts-new` → `$name` regression).
14. **Index rendering** — one line per script; `(undocumented)` for a
    header-less one; overflow past the limit becomes `…and N more`; an empty
    library still explains how to start one; no library and nowhere to write
    renders no section.
15. **Builtin pack** — every builtin has a shebang and a matching `# name:` /
    `# desc:` header; materialization is idempotent and marks them executable.

**Not covered by a test, verified by hand:** that `coact-init-action` itself
takes the no-sandbox path — driving it needs a full BT context, so the test
asserts the config resolution that decides it instead. Live verification: three
`by ask` turns on `bedrock/amazon.nova-lite-v1:0` — one counting files
correctly, one authoring `clj-count` through `scripts-new`, and a THIRD PROCESS
discovering that script in its index and reusing it by bare name for a
different directory, with the right answer.

Run the affected namespaces only (`bb test` is ~3 min and stops at the first
failure). The whole agent component in one JVM:
`bb test:ns ai.brainyard.agent.` — 1680 tests / 9633 assertions, whose one
failure (`session-sharing-test/hierarchy-axes-stay-separate`) reproduces
unchanged on a pristine HEAD worktree and is not from this work.

## 12. Registration checklist

Following the convention the three front-door agents established:

1. Add `ai.brainyard.agent.common.script-agent` to the side-effecting require
   list in `components/agent/src/ai/brainyard/agent/interface.clj` — the single
   source of truth for built-in `defagent`s.
2. Wire into `common/router_agent.clj` in **three** places: the directory, the
   lettered decision table, and the summary list. Routing rule: *natural
   expression is a shell pipeline or a small local-file program, and no
   brainyard tool surface is needed.*
3. Add a `:agent-tier-map` entry. `{:default :light :max :standard}` —
   script work is mostly mechanical, and a router asking for `:deep` on a shell
   pipeline is wrong about cost.
4. Add to `docs/core/agent.md`'s roster table.

**All four done, and the routing was verified rather than assumed** — four
turns through router-agent on `bedrock/claude-sonnet-5`, read from the routing
log:

| question | routed to | |
|---|---|---|
| "print line counts for docs/design .md, top 5" | `code-compose` | correct — one-off |
| "I check this **every week**… one command next time" | **script-agent** `:light` | wrote the script |
| "where is auto-background detach implemented?" | `explore-agent` | no over-attraction |
| "run my weekly check" (new process) | **script-agent** | reused the saved script |

The lesson from the first row is §5.3's: entry X only fires on a recurrence
signal, so without the `:brief` index a one-off script-shaped question is
answered by re-deriving work the library already holds.

---

## 13. Phasing

All three phases shipped, in order, and the ordering is the part worth keeping:
each phase answered a question the next one needed.

**P0 — the agent. Shipped.** The `:code-langs` gate (§6.1–6.3), script-only
prompt sections (§6.4), the substrate gate (§6.6), no-sandbox init (§6.5),
library index + PATH injection, four builtins. No new commands, no IPC. Complete
on its own: bash and python already cover file I/O, search, HTTP, git and
process control.

Verified live on `bedrock/amazon.nova-lite-v1:0` — three turns, the third a
separate process discovering a script the second one authored and reusing it by
bare name for a different directory, with the right answer.

**P1 — ergonomics. Shipped.** `::script-block` telemetry (§9) and
`by scripts list` / `by scripts reuse`. What P0 could not answer without it was
the only question that matters — whether the library is reused or re-typed —
and running P0 first is what produced the measurement that justified §5.3's
`:brief` rendering.

**P2 — the bridge. Shipped, and OFF by default.** The agent binds its own
AF_UNIX socket (`components/ask-channel`'s transport, unchanged — `start-listener!`
is already "bind a socket, serve `(fn [req] response)`"), exports it as
`BY_TOOL_SOCK` in the block environment, and ships a `by-tool` executable:

```bash
by-tool 'memory$recall' --query "prompt cache zones"
by-tool 'task$detail' --task-id t-17 --last-n 40
```

This restores part of §7 without restoring the tool *channel* — the model still
emits only script fences, and the way in is an executable like every other
capability it has. Five decisions:

- **`:enable-script-bridge` defaults false.** This is the one part of the
  design that adds REACH rather than persistence: everything else a script does,
  a bash fence could already do. A new privilege surface is opt-in or it is a
  surprise.
- **`:script-bridge-tools` is an allowlist, not a filter.** Default is
  `memory$recall`, `memory$status`, `task$detail`, `task$cancel`, `task$wait` —
  the read/observe half of §7.1–7.2. Exposing `call-tool` wholesale would pass
  the WRITE surface of every agent in the process (`edit-agent`, `write-file`,
  `mcp$*`) through a door opened for memory recall. §7.3 (sub-agent dispatch)
  stays closed.
- **Its own socket, not the session `ask.sock`** — that one is per-session,
  absent under `by ask`, and carries the *user's* turn queue. This one is per
  agent instance, so `memory$*` resolves the right identity, and a hook on
  `:agent.instance/closed` unlinks it. The path is `<tmpdir>/by-tool-<hash>.sock`,
  short by construction, so AF_UNIX's ~104-byte cap has no long case to handle.
- **Argument parsing is server-side.** The shim ships raw argv; composing EDN in
  bash would mean quoting model-authored strings into a reader. Values are
  coerced (`--last-n 40` → `40`) but never `read-string`-ed — an argument must
  not become code.
- **`by-tool` is python3, not bash**, because `nc -U` is not portable and this
  agent already requires python3 for its own fence. It is materialized only when
  the bridge is on, and PRUNED from the builtin scope when it is off — otherwise
  the first person to try the bridge leaves a permanent entry in the index that
  answers "the bridge is off".

**The `$` trap, found on the first live run.** Every registered tool name
contains `$`, which is a variable sigil in an unquoted bash word — so
`by-tool memory$status` arrives at the server as `memory`. The shell has already
destroyed the information, so the refusal cannot be repaired; instead, when the
mangled name prefixes exactly the tools that *were* allowed, the error says so
and names the quoted form. Measured: the model hit it, read the hint,
re-ran `by-tool 'memory$status'` and got its answer — one iteration, not a turn.
The warning also rides the shim's `# desc:` line, which is what the prompt
renders.

**It was listed last on purpose, and that ordering paid.** Building the bridge
first would have made script-agent a thin skin over CoAct's roster and answered
none of the question this design exists to ask: how much of a tool registry a
capable model actually needs when it can write files and run them.

---

## 14. What is not settled

- **The reuse rate is measured over too few blocks to mean anything.** 56% over
  9 script blocks at the time of writing. The number exists so the next few
  hundred can be read; nothing should be changed on the strength of nine.
- **`:brief` changes the router's behaviour without determining it.** On one
  question it reached for a saved script; on another, with `clj-count` sitting
  in its index, it hand-rolled `find | wc -l` anyway. Whether that is prompt
  placement, wording, or simply model variance is exactly what the telemetry is
  for.
- **Widening the library to every code agent** — PATH and the full section, not
  just `:brief` — remains a deliberate later decision. It would add a prompt
  section to every shipped agent, and there is no measurement yet that says the
  registry agents want one.
- **`by scripts doctor` does not exist.** The lint runs only as a script inside
  an agent's PATH, so there is no way to check the library from a terminal
  before committing to it.
