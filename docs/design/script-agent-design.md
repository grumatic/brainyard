# Script-Agent — A Two-Channel CoAct Where the Tool Registry Is a Directory

> **Status:** **P0 shipped.** `script-agent` is registered in `components/agent`
> (`common/script_agent.clj`), the library lives in `common/scripts.clj`, and
> the language gate is in `common/coact_agent.clj`. Tests:
> `components/agent/test/ai/brainyard/agent/script_agent_test.clj` (14 tests,
> 128 assertions).
>
> **As-built deltas from the proposal below — read these first:**
> - **The substrates had to go too, and that was the bulk of the win.** §2.2
>   only counted the role/format/execution-model sections. The five base
>   substrates (skill / MCP / todo / exec / subagent) and BOTH
>   `coact-critical-rules` and `coact-large-results-playbook` are written
>   against the tool registry — `doc$read`, `usage$guide`, `read-file :lines`,
>   `todo$sync`, `edit-agent` dispatch. Gating them on a new `registry?`
>   predicate (`tool-channel? OR a clojure fence`) is what took the static
>   prompt from 20,810 chars to **8,696** — a 58% cut, measured. The rules and
>   playbook get script-shaped variants (`sed -n`, `grep`, `head -c`) rather
>   than being dropped: the problems they describe are real, only the verbs
>   were wrong.
> - **Byte-identity for existing agents is enforced, not hoped for.** The
>   two-arity `render-instructions` / `think-act-code-signature` delegate to the
>   three-arity form with `all-code-langs`, and the two places where a template
>   literal became a variable (`lang-blurb`, `script-fences`) reproduce the
>   ORIGINAL hand-wrapped text for the full set — including the line break
>   before `` `javascript` ``, which `script-fences` carries along with the word
>   "blocks" for exactly that reason. Verified by capturing all three renders
>   before the edit and diffing after.
> - **The PATH injection is a COMMAND PREFIX**, not an env map threaded through
>   `local-exec-shell` / the fast-eval ProcessBuilder / the `:bash` task
>   executor. All three hand the string to `/bin/sh -c`, so one prefix covers
>   them and none grows a parameter that can fall out of sync.
> - **The library has TWO renderings, chosen by `script-library-mode`.** `:full`
>   (no clojure fence) injects PATH, materializes the builtin pack, and carries
>   the authoring contract — the library IS that agent's tool surface. `:brief`
>   (a clojure fence, so a registry) is NAMES ONLY: no PATH, no authoring, no
>   builtins, and it renders nothing at all until a script has actually been
>   saved, so it is free until it has something to say (290 chars vs 1,279 when
>   populated). PATH stays `:full`-only because prepending directories without
>   saying so is a silent change to what a bare command resolves to; `:brief`
>   names a PATH, never a command.
>
>   This was added after measuring the gap. Asked "which design docs are over
>   1000 lines?", router-agent chose `code-compose` and hand-rolled a find/wc
>   pipeline the library already held, because nothing in its prompt said the
>   library existed. With `:brief` present the same question routes to
>   `tool-fetch` with the reason "a saved script already computes this exactly,
>   so I ran it directly rather than hand-rolling a find/wc pipeline". Builtins
>   are excluded from `:brief` for the same reason: the pack is materialized on
>   first use, so counting it would make every repo look like it had a library,
>   and a router will never run `scripts-new`.
> - **Builtins are materialized from strings in `scripts.clj`**, not shipped as
>   classpath resources: a resource needs native-image resource-config, and a
>   missing entry fails at runtime with an empty library and no error anyone
>   would connect to the cause.
> - **`# name:` parsing is scoped to the contiguous header block**, first-wins.
>   A whole-head scan indexed `scripts-new` — which contains `# name: $name`
>   inside the heredoc it emits — as a script literally called `$name`. Any
>   script that writes another script has that shape. Regression test:
>   `header-parsing-stops-at-the-header-test`.
> - `:script-lib-dirs` defaults to `[]` (derive), not to an explicit list.
> - **P1 and P2 are NOT built.** No `by scripts` CLI, no `::script-invoked`
>   telemetry (`scripts/resolve-invoked` exists and has no caller), no `by`
>   shim. §9 and §13 describe intent, not code.
>
> **Scope:** `components/agent/src/ai/brainyard/agent/common/script_agent.clj`,
> `common/scripts.clj`, `common/coact_agent.clj`, `core/config.clj`,
> `core/feature.clj`, `common/router_agent.clj`, `interface.clj`.
> **Built on:** `coact_agent.clj` via `coact/run-coact-derived`.
> **Sibling of:** `react-agent` (the other single-action-channel agent — it
> pins the *opposite* channel off).

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
other decision below follows from it.

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
                    tool-calls   code-blocks           answer
react-agent             ✓            ✗                   ✓      registry-only
coact-agent             ✓      clj/bash/py/js            ✓      both
script-agent            ✗         bash/py                ✓      directory-only
```

`script-agent` is a **leaf** in v1: it dispatches no sub-agents (§7.3). The
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

`name` defaults to the filename stem when absent, so it is optional in
practice. `desc` is what appears in the prompt index. `usage` is *not* rendered
in the prompt — it is there for `--help` and for a human reading the file.

**There is deliberately no parameter schema.** A JSON-schema'd argument list
would recreate exactly the registry ceremony this design exists to delete, and
it would be worse than nothing: a schema is validated *before* the script runs,
so a stale or wrong schema turns a working script into an unreachable one,
while a wrong `usage:` comment merely misinforms and is corrected by running
the thing. The script's real contract is its exit code and its stderr.

### 5.3 What goes into the prompt

```markdown
## Scripts (your reusable tools — `.brainyard/scripts/bin/`)

changed-since      — List files changed since a git ref, filtered by extension.
pdf-pages          — Print the page count of a PDF.
csv-schema         — Infer column types from a CSV sample.
route-report (shadows builtin) — Summarize routing.log for a session.
+3 more — run `scripts-ls` to see all.

Read any script's source with `cat $(which <name>)`. To add one:
write it to .brainyard/scripts/bin/<name>, chmod +x, done — it is on PATH
for every block from the next one onward.
```

Bounded by `:script-index-limit` (default 60). At ~60 chars a line that is a
~3.6 KB ceiling — against the ~4.4 KB of *static prose* CoAct spends just
explaining what its tool channel is, before listing a single tool.

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

### 5.5 Builtin pack — kept small on purpose

Ship ~4, not ~40. A large builtin pack is a roster wearing a different hat, and
it would re-import the problem: prompt weight for capabilities the model did
not ask for and cannot easily audit.

| script | why it earns a slot |
|---|---|
| `scripts-ls` | the overflow escape for `:script-index-limit`; prints name/desc/path/scope |
| `scripts-new <name>` | writes the header skeleton + `chmod +x` — removes the only step the model reliably forgets |
| `scripts-doctor` | `bash -n` / `python -m py_compile` every script, report non-executable or header-less ones |
| `fetch <url>` | `curl` with sane flags, a timeout, and a size cap — the one thing bash gets wrong by default |

Everything else starts life as a project script.

---

## 6. Core changes to CoAct

Five edits, each mirroring an existing pattern. The alternative — a
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
  auto-harvest, and output truncation-to-file. It should land near 2 KB against
  the sandbox model's ~8 KB, because everything about SCI, `context-get`,
  `[:user-vars]`, and sandbox persistence is inapplicable.
- `:sandbox-context-accessor` is dropped by the existing
  `(and code-channel? (not= :nrepl clj-backend))` guard being extended with
  `(langs-allow? :clojure)` — there is no sandbox to accessor into.
- `:code-blocks-format` selects a script variant: fence syntax, the
  `<!-- ParallelBlock -->` marker, the four-backtick verbatim-content fences
  (which are language-independent and still useful for writing files), minus
  every SCI string restriction.
- `:scripts` is a **new section** carrying §5.3's index plus §5.4's rule. It
  rides `:session-context` in `coact-system-zones` (it changes when a script is
  written, which is exactly the session-stable cadence that zone exists for),
  slotted where `:tools` sits.

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

`:agent-tools` is `{}`. `run-coact-derived` merges CoAct's `:agent-tools` onto
derived agents, so `script-agent` must pass an explicit empty roster and the
merge helper must treat an explicit `{}` as "none", not as "absent, inherit".
That is the one place this design touches shared merge behavior, and it needs a
regression test.

---

## 7. What is lost, and what happens to it

Removing the tool channel removes real capability. Naming it honestly:

### 7.1 Background work — **kept, for free**

CoAct's auto-background detach and the harvest at iteration start
(`coact-inc-iter-action` resolving pending evals) are channel-independent —
they operate on the task manager and the `::eval-entry` records, not on
`tool-calls`. A block that exceeds `:auto-background-timeout-ms` still detaches,
still returns a `:pending` entry with a `task-id`, and is still folded back in a
later iteration.

What is lost is the *interactive* surface: `task$detail`, `task$wait`,
`task$cancel` are registry tools. In v1 the model backgrounds explicitly
instead — `nohup cmd > .brainyard/scripts/run/<id>.log 2>&1 &` then `tail` in a
later block. This is strictly less ergonomic and it is the strongest single
argument for Phase 2.

### 7.2 Memory recall, artifacts, project memory — **partly lost**

`memory$recall`, `artifact$*`, `trajectory$search` are registry tools. Project
memory and BRAINYARD.md still ride the *system context* (they are prompt
sections, not tools), so the agent keeps its standing instructions and project
notes. Graph/L1-L3 recall is unavailable in v1.

### 7.3 Sub-agent dispatch — **lost by design**

`script-agent` is a leaf. Making it a dispatcher would mean either the tool
channel (which it does not have) or a shim (Phase 2). A leaf specialist that
the router calls *into* is the right v1 shape and matches how `explore-agent`
is used.

### 7.4 MCP — **lost**

MCP servers are reached through the registry. Note that many MCP servers are
themselves stdio subprocesses; a project script wrapping one is a legitimate
workaround and exactly the pattern §5 is for.

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
  silently: the index labels it, and builtin files themselves are never
  overwritten (the model writes to the *project* dir; the builtin dir ships
  read-only inside the binary's resource tree and is materialized to a cache
  path on first use).

---

## 9. Observability

One event and one derived question. `::script-invoked {:name :scope :exit
:ms}` is emitted by matching the first PATH-resolvable token of an executed
bash block against the library index — cheap, and it answers the only question
that decides whether this design worked:

> Is the library being *reused*, or is the model re-typing pipelines it already
> saved?

A reuse rate near zero means §5.4's rule or §5.3's index placement is wrong,
and no amount of adding scripts fixes that. `by scripts` (CLI) lists the
library with invocation counts, mirroring `by agents`.

---

## 10. Configuration summary

| key | default | meaning |
|---|---|---|
| `:code-langs` | `#{:clojure :bash :python :javascript}` | languages the code channel executes; script-agent pins `#{:bash :python}` |
| `:enable-script-library` | `true` | gate for `:exec/script-library` |
| `:script-lib-dirs` | project → user → builtin | ordered; earlier shadows later |
| `:script-index-limit` | `60` | max scripts rendered in the prompt |

Env vars follow the existing precedence (env > per-agent > session >
`.brainyard/config.edn` > schema default): `BY_CODE_LANGS`,
`BY_SCRIPT_LIB_DIRS`, `BY_SCRIPT_INDEX_LIMIT`.

---

## 11. Testing

`components/agent/test/ai/brainyard/agent/script_agent_test.clj` — 15 tests,
131 assertions, no LLM. `run-single-block` is driven directly, which is enough
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

---

## 13. Phasing

**P0 — the agent.** `:code-langs` gate (6.1–6.3), script-only prompt sections
(6.4), no-sandbox init (6.5), library index + PATH injection, the four builtins.
No new commands, no IPC. This is complete and useful on its own: bash and
python already cover file I/O, search, HTTP, git, and process control.

**P1 — ergonomics.** `by scripts` CLI, `::script-invoked` telemetry, the reuse
report. Ship only after P0 has run enough turns to say whether the library is
actually being reused.

**P2 — the bridge, only if earned.** A `by` shim on PATH that speaks the
existing AF_UNIX EDN transport (`components/ask-channel`, extended per
`docs/design/session-channel-extensions.md`) back to the running agent, giving
scripts a curated set of brainyard commands:

```bash
by tool memory\$recall --query "prompt cache zones"
by tool task\$detail --task-id t-17 --last-n 40
by agent explore-agent --question "where is X wired"
```

This restores §7.1–7.3 without restoring the tool *channel* — the model still
emits only bash, and the shim is just another script, discoverable in the same
index as the rest.

**It is listed last on purpose.** Building the bridge first would make
script-agent a thin skin over CoAct's roster and would answer none of the
question this design exists to ask: how much of a tool registry a capable model
actually needs when it can write files and run them.
