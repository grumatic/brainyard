# Tool-Agent — LLM-Mediated Authoring of Persistent User-Defined Tools (CoAct-derived)

> Status: design proposal. Companion to the already-shipped `tools$*` command
> family in `components/agent/src/ai/brainyard/agent/common/user_tools.clj`.
> Sibling docs: `skill-agent` (skill lifecycle), `mcp-agent` (MCP lifecycle),
> `config-agent` (config lifecycle), `main-agent-design.md` (router).

## 1. Motivation

`by` already lets the LLM mint first-class tools at runtime. `tools$create`
takes a name, a one-line description, a Malli `[:map …]` input schema, and a
`(fn [args] …)` body string; it eval-smoke-tests the body, persists the source
to `.brainyard/tools/<name>.edn`, and registers it into the shared
`agent.core.tool/!tool-defs` registry as `user$<name>` — discoverable, coerced,
and callable on the very next turn. `tools$list`, `tools$read`, and
`tools$delete` round out the lifecycle.

What is missing is a *specialist owner* for this surface. Today any agent can
reach `tools$create`, but no agent is shaped for the job: there is no curated
instruction that teaches schema-first authoring, body composition by symbol,
the smoke-test-then-persist discipline, or the duplicate-before-create check
that `skill-agent` enforces for skills. Authoring a good tool is its own
sub-discipline — naming, a tight input schema, a body that composes existing
tools rather than re-implementing them, and a verification pass — and it
benefits from the same dedicated CoAct-derived specialist treatment the other
lifecycle domains already get.

`tool-agent` is that owner: the single specialist `main-agent` routes to for
"make me a tool that …", "what tools have I defined?", "fix the `count-words`
tool", and "remove `shout`". It turns the raw `tools$*` commands into a guided
authoring workflow with verification built in.

## 2. Design Principles

The agent inherits the house principles for lifecycle specialists and adds a
few specific to tool authoring.

It is a thin CoAct-derived specialist. Like `skill-agent`, it is a `defagent`
over `coact/run-coact-derived` with the CoAct behavior tree pinned via
`:bt-factory`. It owns no new host primitive — it composes the existing
`tools$*` commands plus the standard discovery fallbacks. All real machinery
already lives in `user_tools.clj`; the agent is instruction + a curated tool
roster, nothing more.

It authors schema-first. The input schema is not an afterthought — it drives
`call-tool`'s Malli coercion, the auto-generated sandbox arglist, and the doc
string other agents see. The agent always settles the `:name` /
`:description` / `:input-schema` triple before writing the body.

It composes, never re-implements. A tool body is a macro over the existing
palette, not a new privilege. The agent prefers a body that calls
`(read-file {…})`, `(bash {…})`, or a peer `(user$other {…})` over one that
reaches for raw host interop. This is also the security posture: user tools
grant no capability the sandbox does not already grant (see §10).

It validates before it persists. Authoring executable code is the one place a
specialist can leave durable junk behind — a half-working tool registered under
a good name. The agent dry-runs every body through `tools$validate` (§5A) before
`tools$create` ever touches disk, so a malformed schema or an uncompilable body
is caught while it is still a draft, not after it has overwritten a working tool.

It verifies before it claims success. `tools$create` also eval-smoke-tests the
body, but a body that *parses* is not a body that *works*. The agent runs the
freshly registered `user$<name>` against a representative input and reads the
result before reporting done — the standard specialist verification step.

It refuses to duplicate. Mirroring `skill-agent`'s "find before create" rule,
the agent runs `tools$list` (and inspects near-matches with `tools$read`)
before authoring, and prefers refining an existing tool to minting a near-clone.

It never invents tools. If discovery turns up nothing, it says so and offers to
author one — it does not fabricate a `user$…` that was never defined.

## 2A. Scope — Project vs User

Today `tools$create` persists to `<project>/.brainyard/tools/<name>.edn` only —
project scope, mirroring `.brainyard/skills` and `.brainyard/plans`. A tool
authored in a checkout is a shared project asset, and the project dir is already
the working directory.

`user_tools.clj` explicitly leaves room for a `:scope :user` variant rooted at
`~/.brainyard/tools`, exactly as skills do. The agent's instruction should be
written so that adding a `:scope` argument later is a non-breaking extension:
default `:project`, reserve `:user` for personal/global tools. Until that lands,
the agent states plainly that authored tools live with the current project.

## 3. Position in the Agent Stack

`tool-agent` is a leaf specialist, peer to `skill-agent`, `mcp-agent`,
`memory-agent`, and `config-agent`. It self-registers in `!tool-defs` through
its own `defagent` and is loaded — like every built-in agent — by the single
side-effecting require list in
`components/agent/src/ai/brainyard/agent/interface.clj` (the documented "single
source of truth: add a new agent here when it ships").

`main-agent` is the front door. It classifies a question's shape and delegates;
tool-lifecycle questions become a `tool-agent` call. This adds one routing shape
to the existing roster in
`components/agent/src/ai/brainyard/agent/common/main.clj` and the routing
instruction in `main_agent.clj` — see §7.

Like its siblings, the agent stays flat: it does not clone-self, and it does not
write to sibling-specialist storage (`.brainyard/skills`, `.brainyard/agents/…`,
etc.). Its writable surface is exactly `.brainyard/tools/`, reached only through
the `tools$*` commands.

## 4. Capability Surface

The agent handles five capability kinds, each mapping to one `tools$*` command
(authoring and refinement add a `tools$validate` dry-run and a post-create
verification call):

1. Discover — "what tools have I defined?" → `tools$list`.
2. Inspect — "show me the `count-words` tool" → `tools$read :name "count-words"`.
3. Author — "make a tool that …" → `tools$list` (dup check) → settle
   name/description/schema → `tools$validate` (dry-run) → `tools$create` →
   invoke `user$<name>` to verify.
4. Refine — "fix / change the `count-words` tool" → `tools$read` →
   `tools$validate` the edited draft → re-`tools$create` with the same name
   (create overwrites in place) → re-verify.
5. Remove — "delete `shout`" → `tools$delete :name "shout"`.

Authoring and refinement are the same write path: `tools$create` keys on
`:name`, so re-creating an existing name replaces both the persisted `.edn`
source and the live registry entry. There is no separate `tools$update`
command today (see §13 Open Questions); the agent expresses "update" as
"re-create with the same name," reading the old source first so it edits rather
than rewrites from scratch.

## 5. Tool Roster

Primary surface — the `tools$*` family. These already exist as `defcommand`s in
`user_tools.clj`; this design proposes collecting them into a `tools-commands`
var (mirroring `skills/skills-commands`) so the roster is declared in one place:

```clojure
;; in ai.brainyard.agent.common.user-tools
(def tools-commands
  "All user-tool management commands, for binding into tool-agent."
  [#'tools$create #'tools$validate #'tools$list #'tools$read #'tools$delete])
```

| Command | Args | Effect |
|---|---|---|
| `tools$validate` | `:body`, `:input-schema?`, `:name?`, `:sample?` | Dry-run: parse + eval-smoke-test the body and check the schema in a throwaway fork — **persists nothing, registers nothing**. Returns a structured report (§5A). |
| `tools$create` | `:name`, `:body`, `:description?`, `:input-schema?` | Validate → eval-smoke-test → persist source to `.brainyard/tools/<name>.edn` → register `user$<name>`. Returns `{:id :name :persisted}` or `{:error}`. |
| `tools$list` | — | `{:tools [{:id :description :input-schema}]}` for every user-defined tool. |
| `tools$read` | `:name` | `{:name :description :input-schema :body}` from disk (falls back to registry metadata, `:body nil`, when source is absent). |
| `tools$delete` | `:name` | Unregister + delete persisted source. Returns `{:deleted}` or `{:error}`. |

The authored tool itself — once `tools$create` succeeds, `user$<name>` is a
live, directly-callable tool. The agent calls it by id/symbol to verify.

Discovery fallbacks (use only when the `tools$*` surface is not enough):
`list-tools` / `get-tool-info` to enumerate what is already registered (so a new
body can compose existing tools), and `search` / `tree` to explore project
files and config. These mirror the fallback set every specialist carries.

`call-tool` is intentionally hidden (`:visibility :hidden`); the agent composes
tools by their direct symbol, never through a generic call-tool helper — the
same rule that governs user-tool bodies.

## 5A. Dry-Run & Validation Surface (`tools$validate`)

`tools$create` couples three concerns — validation, persistence, and
registration — into one irreversible step. That is fine for a body the agent is
confident in, but it is the wrong primitive for *iterating* on a draft: every
attempt writes the `.edn` and mutates the live registry, and a re-create under
an existing name overwrites a working tool with a broken one. `tools$validate`
splits validation off as a pure, side-effect-free check the agent can run as
many times as it likes before committing.

Semantics. `tools$validate` performs exactly the checks `define-tool` /
`install-body!` perform today, but against a *throwaway fork* of the tools
sandbox and with **no `spit`, no `swap!` into `!tool-defs`, and no
`user$<name>` binding**:

- Name check (when `:name` supplied) — matches `^[a-z][a-z0-9-]*$`, and reports
  whether the name collides with an already-registered tool (so "author" vs.
  "overwrite" is an explicit, surfaced decision, not a silent one).
- Schema check — `:input-schema`, if present, is a well-formed Malli
  `[:map …]`; reuses the same shape guard `define-tool` applies.
- Body check — `(def __probe <body>)` is eval'd in the fork; a parse/eval
  failure is captured as a message rather than thrown.
- Optional sample run — when `:sample` (a map) is supplied, the freshly eval'd
  probe is invoked once on it and the result (or its error) is returned, so the
  agent can see behavior, not just compilability, without registering anything.

The fork is discarded on return; nothing leaks into the live sandbox.

Return shape (a report, never a throw):

```clojure
{:valid         true|false        ;; AND of the individual checks
 :name-ok       true|false        ;; nil when :name omitted
 :collision     true|false        ;; would tools$create overwrite an existing tool?
 :schema-ok     true|false
 :body-ok       true|false
 :sample-result <any>             ;; present only when :sample supplied
 :errors        ["…" ...]}        ;; one line per failed check; empty when :valid
```

Proposed `defcommand` sketch (sibling of `tools$create` in `user_tools.clj`),
reusing the existing helpers:

```clojure
(defcommand tools$validate
  "Dry-run a user-tool draft: parse + eval-smoke-test the body and check the
   schema/name WITHOUT persisting or registering anything. Use before
   tools$create to iterate safely. Optionally pass :sample to run the body once."
  (fn [& {:as args}]
    (try
      (let [{:keys [name body input-schema sample]} args
            name-ok    (when name (boolean (re-matches tool-name-re name)))
            collision  (and name (contains? @tool/!tool-defs (tool-id name)))
            schema-ok  (or (nil? input-schema)
                           (and (vector? input-schema) (= :map (first input-schema))))
            fork       (sb/fork-sandbox (tools-sandbox))   ;; throwaway
            evald      (sb/eval-code fork (str "(def __probe " body ")"))
            body-ok    (nil? (:error evald))
            sample-res (when (and body-ok (map? sample))
                         (sb/set-var! fork 'args sample)
                         (let [r (sb/eval-code fork "(__probe args)")]
                           (if (:error r) {:error (:error r)} (:result r))))
            errors     (cond-> []
                         (false? name-ok)   (conj "name must match ^[a-z][a-z0-9-]*$")
                         (false? schema-ok) (conj ":input-schema must be a [:map ...] schema")
                         (not body-ok)      (conj (str "body failed to eval: " (:error evald))))]
        (cond-> {:valid (empty? errors)
                 :collision (boolean collision)
                 :schema-ok schema-ok :body-ok body-ok :errors errors}
          (some? name-ok)             (assoc :name-ok name-ok)
          (map? sample)               (assoc :sample-result sample-res)))
      (catch Exception e {:valid false :errors [(str "tools$validate failed: " (.getMessage e))]})))
  :input-schema  [:map
                  [:body         [:string {:desc "Clojure source: a `(fn [args] ...)` of one map"}]]
                  [:name         {:optional true} [:string {:desc "Proposed name; enables name + collision check"}]]
                  [:input-schema {:optional true} [:any {:desc "Malli [:map ...] arg schema to validate"}]]
                  [:sample       {:optional true} [:map {:desc "Example args map; if given, body is run once on it"}]]]
  :output-schema [:map
                  [:valid         [:boolean {:desc "True iff all checks passed"}]]
                  [:name-ok       {:optional true} [:boolean {:desc "Name matches the required pattern"}]]
                  [:collision     [:boolean {:desc "A tool with this name already exists"}]]
                  [:schema-ok     [:boolean {:desc "input-schema is a well-formed [:map ...]"}]]
                  [:body-ok       [:boolean {:desc "Body parses and evals"}]]
                  [:sample-result {:optional true} [:any {:desc "Result of running the body on :sample"}]]
                  [:errors        [:any {:desc "Vector of human-readable failure lines"}]]])
```

This is the only machinery addition the agent requires beyond the existing
commands. It deliberately mirrors `tools$create`'s argument names so a validated
draft promotes to a create call with no reshaping: validate `{:name :body
:input-schema}`, then create with the same three.

`tools$validate` is also the right home for the `:sample` smoke that §6's
verification step would otherwise do post-create — for risky bodies the agent
can confirm behavior *before* anything is written, and reserve the post-create
`user$<name>` call for final confirmation against the truly-registered tool.

## 6. Instruction Shape

The instruction follows the `skill-agent` template: a role line, a decision
flow keyed to the five capability kinds, content-handling rules for the
name/schema/body triple, large-output handling, and a safety block. Sketch:

```
You are a tool-authoring specialist. You help the user discover, inspect,
author, refine, and remove PERSISTENT user-defined tools. An authored tool is
saved as <project>/.brainyard/tools/<name>.edn, registered as user$<name>, and
is callable as a first-class tool on the next turn.

DECISION FLOW
1. Classify the ask:
   - discover → tools$list
   - inspect  → tools$read :name <name>
   - author   → see AUTHORING
   - refine   → tools$read first, then re-create with the same name
   - remove   → tools$delete :name <name>
2. Before authoring, ALWAYS tools$list (and tools$read near-matches) to avoid
   duplicating an existing tool. Prefer refining an existing tool to a clone.

AUTHORING (the disciplined path)
1. Settle the triple BEFORE writing the body:
   - :name          lowercase-kebab, leading letter, matches ^[a-z][a-z0-9-]*$
                    (no user$ prefix). It becomes the filename and the symbol.
   - :description   one tight line — this is what other agents see in discovery.
   - :input-schema  a Malli [:map ...]; every field [:k [:type {:desc "..."}]].
                    The schema drives coercion, the sandbox arglist, and docs.
2. Write :body as a string "(fn [args] ...)" of ONE map argument. COMPOSE the
   existing palette by direct symbol — (read-file {...}), (bash {...}), or a
   peer (user$other {...}) — instead of re-implementing or reaching for host
   interop. Pull args out of the `args` map by the schema's keys.
3. DRY-RUN: tools$validate the draft (:name :body :input-schema, plus a :sample
   args map for a behavior check). Persists nothing. Iterate here until :valid
   is true. If :collision is true, you would OVERWRITE an existing tool —
   confirm that is intended (a refine) before proceeding.
4. tools$create with the same name/body/schema. If it returns :error, the body
   failed to eval — fix and retry.
5. VERIFY: call user$<name> with a representative input and read the result.
   Only report success after the tool actually runs and returns sane output.

CONTENT HANDLING
- A tool body is a macro over the tool palette, not a new primitive. Keep it
  small and composable.
- Echo the final :name, :description, :input-schema, and a one-line "verified
  with <input> → <result>" back to the user.

LARGE OUTPUTS
- When tools$read returns a long body, summarize and cite the :persisted path;
  do not echo a huge body verbatim.
- When listing many tools, give id + one-line description, not full schemas.

SAFETY
- Never author a body that exfiltrates secrets, shells out destructively, or
  writes outside the workspace. The body runs in the tools sandbox with the
  agent's existing capabilities — do not expand them.
- Confirm with the user before tools$delete; deletion removes the source.
- Never invent a user$ tool. If discovery finds nothing, say so and offer to
  author one.
```

A `:tool-context` block (as in `skill-agent`) restates the `tools$*` arg
signatures and the typical flows so the model has the command surface inline.

## 7. Routing — Wiring into main-agent

`main-agent` owns a fixed roster of routing-decision shapes (the lettered list
A–S in `main.clj` and `main_agent.clj`). Tool lifecycle currently has no shape;
the bare `tools$create` is reachable as a generic tool, but there is no
delegation target. This design adds one decision shape:

- `:tool-lifecycle → tool-agent` — author/inspect/refine/remove user-defined
  tools. Distinct from `:skill-lifecycle` (SKILL.md prose workflows) and
  `:mcp-lifecycle` (external MCP servers): this is for in-process
  `(fn [args] …)` tools persisted under `.brainyard/tools`.

Concretely:

1. Add `:tool-lifecycle` to the routing-decisions enum in `main.clj`
   (alongside `:skill-lifecycle`, `:mcp-lifecycle`).
2. Add the corresponding lettered shape + one-line cue to the routing
   instruction in `main_agent.clj` (the "→ tool-agent" line in the specialist
   table and the decision list).
3. Add `[ai.brainyard.agent.common.tool-agent]` to the side-effecting require
   list in `interface.clj`.

Routing cue for the instruction: route to `tool-agent` when the user wants to
*make, see, change, or remove a reusable tool/command* of their own — phrasings
like "make me a tool that …", "add a command for …", "what tools have I built",
"fix my `<name>` tool", "delete `<name>`". Do NOT route here for skills
(`skill-agent`), MCP servers (`mcp-agent`), or one-off computation the model can
just do inline.

## 8. File Layout

```
components/agent/src/ai/brainyard/agent/common/
  tool_agent.clj          NEW — the defagent (instruction + roster)
  user_tools.clj          EXISTING — tools$* commands; add `tools-commands` var
```

The new file mirrors `skill_agent.clj` almost exactly:

```clojure
(ns ai.brainyard.agent.common.tool-agent
  "Tool-agent — a CoAct-derived specialist for authoring, inspecting, refining,
   and removing persistent user-defined tools (the tools$* command family)."
  (:require [ai.brainyard.agent.core.tool :refer [defagent]]
            [ai.brainyard.agent.common.coact-agent :as coact]
            [ai.brainyard.agent.common.user-tools :as user-tools]))

(def ^:private instruction "...")    ;; §6
(def ^:private tool-context "...")   ;; §6

(defagent tool-agent
  "Specialist for authoring persistent user-defined tools (create/list/read/delete)."
  coact/run-coact-derived
  :bt-factory (fn [{:keys [max-iterations]}]
                (coact/coact-behavior-tree max-iterations))
  :tool-use-control {}
  :input-schema  [:map
                  [:question [:string {:desc "User question about user-defined tools"}]]
                  [:agent-context {:optional true} [:string {:desc "Extra context"}]]]
  :output-schema [:map
                  [:answer [:string {:desc "Summary of the tool operation / authored tool id"}]]]
  :agent-tools {:tools user-tools/tools-commands}
  :instruction instruction
  :tool-context tool-context)
```

No change to the persistence, registration, or sandbox machinery is required —
that is all already in `user_tools.clj`. The agent is purely a curated front end
over commands that ship today.

## 9. Conversation Patterns (worked examples)

### 9.1 Author a tool (capability kind 3)

> "Make me a tool that counts words in a file."

1. `tools$list` — no `count-words` yet.
2. Settle the triple: name `count-words`; description "Count the number of words
   in a file's content"; schema `[:map [:path [:string {:desc "Path to the file"}]]]`.
3. Write the body, composing `read-file`:
   `"(fn [args] (let [content (or (:content (read-file {:path (:path args)})) \"\") words (remove clojure.string/blank? (clojure.string/split content #\"\\s+\"))] {:path (:path args) :word-count (count words)}))"`.
4. `tools$validate :name "count-words" :body <above> :input-schema <above>
   :sample {:path "README.md"}` → `{:valid true :collision false :schema-ok true
   :body-ok true :sample-result {:path "README.md" :word-count 412}}`. Nothing
   persisted yet — the draft already runs.
5. `tools$create` → `{:id "user$count-words" :persisted ".brainyard/tools/count-words.edn"}`.
6. Verify against the registered tool: `(user$count-words {:path "README.md"})`
   → `{:path "README.md" :word-count 412}`.
7. Report: created `user$count-words`, verified on `README.md` → 412 words.

(This is the exact tool already checked into `.brainyard/tools/count-words.edn`,
which makes a good regression fixture for the agent's authoring path.)

### 9.2 Refine an existing tool (capability kind 4)

> "Have `count-words` ignore Markdown headers."

1. `tools$read :name "count-words"` → current source + schema.
2. Edit the body to strip lines starting with `#` before splitting.
3. `tools$validate` the edited draft with a `:sample` — `:collision true`
   confirms this re-create overwrites the existing tool (the intended refine),
   and the sample result shows the new count before anything is written.
4. `tools$create` with the same `:name` (overwrites source + registry entry).
5. Re-verify on the same file; report the new count and what changed.

### 9.3 Compose tools (capability kind 3, composition)

> "Add a tool that greps a pattern and counts the matching files."

The body calls `(bash {…})` for the grep and composes the peer `user$count-words`
only if relevant — demonstrating body-to-body composition by direct symbol. The
agent confirms the peer tool exists (`tools$list`) before referencing it.

### 9.4 Discover & inspect (capability kinds 1–2)

> "What tools have I built, and what does `shout` do?"

`tools$list` for the roster, then `tools$read :name "shout"` for the source and
schema of the one the user named. Long bodies are summarized with the path, not
dumped.

### 9.5 Remove (capability kind 5)

> "Delete `shout`."

Confirm intent, `tools$delete :name "shout"` → `{:deleted "shout"}`. Note that
the orphaned `__ut_shout` / `user$shout` sandbox vars are harmless (registry
dispatch is gone) and clear on the next sandbox rebuild.

## 10. Edge Cases & Safety

Name validation. `:name` must match `^[a-z][a-z0-9-]*$`. The agent normalizes a
user's free-text name to kebab-case and confirms before creating; an invalid
name throws in `define-tool`, surfaced as `{:error …}`.

Body fails to eval. The agent catches this at the `tools$validate` dry-run
(`:body-ok false`, with the eval message in `:errors`) before `tools$create` is
called, so a malformed draft never reaches disk. As a backstop, `tools$create`
itself eval-smoke-tests via `install-body!`; a parse or eval error there returns
`{:error "tools$create failed: …"}` and nothing is persisted or registered. The
agent reads the error, fixes the body, and retries — it never reports success on
an `:error`.

Silent overwrite. Because `tools$create` keys on `:name`, re-creating an
existing name replaces a working tool with no warning. The agent's pre-create
`tools$validate` surfaces `:collision true`, turning "author" vs. "overwrite"
into an explicit confirmation rather than an accident.

Body parses but misbehaves. The mandatory verification call (§6 step 4) catches
this. A body that returns `{:error …}` at *call* time (e.g. a missing file) is a
runtime result, not a definition failure — the agent distinguishes "the tool is
broken" from "the tool correctly reported a bad input."

Capability boundary. User tools are macros over the existing palette; they grant
no privilege the sandbox does not already grant. The body runs in the long-lived
`!tools-sandbox`, forked per call for isolation. The agent must not author
bodies that attempt to widen this boundary (raw host interop to read secrets,
destructive shell commands, writes outside the workspace). This is a hard rule
in the safety block, not a soft preference.

Peer-composition ordering. At session boot, `load-user-tools!` registers all
tools (binding every `user$<name>` symbol) before installing any body, so a body
may reference a peer regardless of `.edn` file order. The agent does not need to
worry about ordering when authoring tools that compose each other.

Source absent on disk. `tools$read` falls back to registry metadata with
`:body nil` and a `:note "source not on disk"`. The agent surfaces this honestly
rather than fabricating a body.

Deletion is destructive. `tools$delete` removes the persisted `.edn`. The agent
confirms before deleting and cannot recover the source afterward.

## 11. Testing Plan

Unit / command level (already largely covered by `user_tools.clj` behavior):
round-trip `tools$create` → `tools$list` → `tools$read` → `tools$delete`;
invalid-name rejection; body-eval-failure path returns `{:error}` and persists
nothing; re-create-same-name overwrites both source and registry; peer
composition resolves across boot order. For `tools$validate`: a valid draft
returns `:valid true` and leaves `!tool-defs` and `.brainyard/tools` untouched
(the core dry-run guarantee); a bad schema, bad name, and uncompilable body each
flip the matching flag and populate `:errors`; `:collision` is true iff the name
is already registered; a `:sample` runs the body and returns its result without
registering anything.

Agent level (new, mirroring `skill_agent`/`config_agent` tests under
`components/agent/test/ai/brainyard/agent/`): `tool-agent` self-registers in
`!tool-defs` with `:type :agent`; routes an "author a tool" question through
`tools$create` and then invokes `user$<name>` to verify; runs `tools$list`
before authoring (dup check); refuses to delete without the named tool existing;
summarizes rather than dumps long bodies.

Routing level: a `main-agent` routing test asserting that tool-lifecycle
phrasings select `:tool-lifecycle → tool-agent` and that skill / MCP phrasings
still select their own specialists (no regression in the existing roster).

Smoke test against the binary after build:

```
projects/agent-tui-app/target/by agents          # tool-agent appears in registry
projects/agent-tui-app/target/by ask -p bedrock -m amazon.nova-lite-v1:0 \
  'use the tool agent to make a tool that counts words in a file, then run it on README.md'
```

## 12. Migration / Phasing

Phase 1 — ship the agent + dry-run. Add the `tools$validate` command (§5A) and
the `tools-commands` var to `user_tools.clj`, create `tool_agent.clj`, register
it in `interface.clj`. `tools$validate` reuses existing helpers (`tool-name-re`,
`tools-sandbox`, `sb/fork-sandbox`, `sb/eval-code`) and adds no new machinery
beyond a side-effect-free command. The agent is immediately usable by direct
invocation, with validate-before-create wired into its authoring flow.

Phase 2 — wire routing. Add `:tool-lifecycle` to `main.clj`'s decision enum and
the cue to `main_agent.clj`'s instruction so `main-agent` delegates
automatically. Add the routing regression test.

Phase 3 (optional) — close the remaining gaps in §13: a real `tools$update` for
partial edits, `:scope :user`, and tool versioning. Each is a `user_tools.clj`
change the agent's instruction can adopt without a structural rewrite.

## 13. Open Questions

`tools$update` vs. re-create. Today "update" is "re-create with the same name,"
which requires the agent to resupply the full body. A dedicated `tools$update`
(patch description/schema without resupplying the body, or vice versa) would be
cleaner and safer. Worth adding if refinement becomes the dominant flow.

User scope. `user_tools.clj` flags a future `:scope :user` (`~/.brainyard/tools`).
Should the agent default project and offer user scope, exactly as `skill-agent`
does for skills? Recommended yes, once the storage variant lands.

Versioning / provenance. Skills carry optional `version` frontmatter. Should an
authored tool record who/when/why (provenance) in its `.edn`? Helps audit
LLM-authored tools that accrete over a project's life.

Permission gating. Should `tools$create` (LLM authoring executable code) sit
behind a tool-use-control or human-confirm gate distinct from invoking an
existing tool? The capability boundary argument (§10) says the risk is bounded
by the sandbox, but a project may still want create/delete behind a confirm.

Discoverability of authored tools to *other* specialists. Once `user$<name>` is
registered it is visible to every agent via `auto-tool-bindings`. Is that always
desired, or should some authored tools be scoped to the authoring session /
hidden from the router? Ties into the visibility (`:tool-use-control`) story.
