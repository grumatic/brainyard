# Meta-Agent — LLM-Mediated Authoring of Persistent User-Defined Agents (CoAct-derived)

> Status: **implemented** (Phases 1–2). Sibling of the shipped `tool-agent`
> (user-defined *tools*, `docs/design/tool-agent-design.md`) and `skill-agent`
> (user-defined *skills*). Machinery lives in
> `components/agent/src/ai/brainyard/agent/common/user_agents.clj` (commands +
> persistence + runtime registration) and `meta_agent.clj` (the manager
> defagent), mirroring `user_tools.clj` / `tool_agent.clj`; routing is wired via
> the `:agent-lifecycle` shape in `main.clj` / `main_agent.clj`. Related:
> `main-agent-design.md` (router), `config-agent-design.md`, `mcp-agent-design.md`.
> Phase 3 items (a real `meta-agent$update`, `:scope :user`, per-agent config,
> router auto-advertisement, few-shot `examples.md`) remain open — see §13.

## 1. Motivation

`by` ships a fixed roster of CoAct-derived specialists — `explore-agent`,
`plan-agent`, `research-agent`, `update-agent`, and the rest — each a `defagent`
over `coact/run-coact-derived` whose entire personality is a curated
`:instruction` plus a `:tool-context` block layered on top of the shared CoAct
loop and tool palette. They are powerful precisely because they are *thin*: the
behavior tree, the three-channel reasoning loop, the full tool roster, memory
capture, and the sub-agent guards are all inherited; the specialist supplies
only prose that steers them.

That thinness is the opening. If a useful specialist is "CoAct + an instruction
+ a tool-context," then a *user* should be able to mint one at runtime without
recompiling the binary — the same way `tool-agent$create` lets the LLM mint a
first-class tool and `skills$write` lets it author a skill. A consultant who
keeps asking "review this Terraform diff against our tagging policy," a writer
who wants a house-style copy-editor, a team with a fixed incident-triage drill —
each wants a *named, reusable agent* whose instruction encodes their workflow
and whose tool-context names the tools it should reach for, persisted with the
project and routable on the next turn.

`meta-agent` is the specialist that owns this surface: the single agent
`main-agent` routes to for "make me an agent that …", "what agents have I
defined?", "tweak my `tf-reviewer` agent's instructions", and "delete
`copy-editor`." It turns raw persistence into a guided authoring workflow —
draft the instruction, name the tools in the tool-context, validate, register,
and verify with a representative question — exactly as `tool-agent` does for
tools.

The user-defined agents it produces are **CoAct-derived**, identical in
machinery to the built-in specialists: same behavior tree, same three output
channels, same inherited tool palette. They differ only in their persisted
`instruction` + `tool-context`, and in being **registered at runtime** rather
than at compile time.

## 2. Design Principles

The agent inherits the house principles for lifecycle specialists and adds a few
specific to authoring *agents* (as opposed to tools or skills).

**It is a thin CoAct-derived specialist.** Like `tool-agent` and `skill-agent`,
`meta-agent` is itself a `defagent` over `coact/run-coact-derived` with the CoAct
behavior tree pinned via `:bt-factory`. It owns one small new host primitive —
the persistence + runtime-registration layer in `user_agents.clj` (§5A) — and
otherwise is instruction + a curated `meta-agent$*` roster, nothing more.

**The authored agent is instruction + tool-context, never bound tools.** This is
the central design choice. A user-defined agent is CoAct-derived, so it inherits
`coact-agent`'s entire `:agent-tools` roster through `merge-derived-tools`
(`coact_agent.clj`), and every other tool in `!tool-defs` is already reachable
through the loop's tool-call / code-block channels under the standard
permission, visibility, and depth guards. There is therefore **nothing to bind**
— a user-defined agent supplies *no* `:agent-tools`. Its only authored surface
is the two prose blocks:

- `:instruction` — the agent's role, decision flow, content-handling rules, and
  safety constraints (the "who you are and how you work").
- `:tool-context` — which tools from the inherited palette this agent should
  reach for, with the typical flows that map a user ask to a tool sequence (the
  "what's on your bench").

Authoring a good agent is exactly the discipline of writing those two blocks
well. The specialist's whole job is to help the user get them right, not to
wire up plumbing.

**It authors instruction-first.** The instruction is the agent. Before writing
anything, the specialist settles the `:name` / `:description` / role, then drafts
the instruction's decision flow, then names the supporting tools in the
tool-context — in that order, because the tool-context only makes sense once the
role is fixed.

**It composes the existing palette, never invents capability.** A user-defined
agent grants no privilege `coact-agent` does not already have — it is a new
*persona over the same tools*, not a new sandbox or a new permission. The
tool-context should name tools that already exist (`read-file`, `bash`, `search`,
`tree`, the `*$*` command families, peer `user$agent$<name>` agents); the specialist
verifies a named tool is registered before citing it, the same way `tool-agent`
checks a peer tool exists before a body references it.

**It validates before it persists.** `meta-agent$validate` (§5B) is a pure,
side-effect-free check the specialist runs before `meta-agent$create` ever touches
disk: the name matches the pattern, the instruction is non-empty, the
tool-context references only registered tools, and the name does not silently
collide with an existing agent. No half-written persona registered under a good
name.

**It verifies before it claims success.** A persona that *registers* is not a
persona that *works*. After `meta-agent$create`, the specialist runs the freshly
registered `user$agent$<name>` against one representative question and reads the
answer before reporting done — the standard specialist verification step. (This
is an LLM round-trip, so it is opt-in for the user and skipped when they only
want the agent persisted; see §5B.)

**It refuses to duplicate.** Mirroring `tool-agent` and `skill-agent`, the
specialist runs `meta-agent$list` (and inspects near-matches with `meta-agent$read`)
before authoring, and prefers refining an existing agent — or pointing at a
*built-in* specialist that already covers the need — to minting a near-clone. If
the user's ask is already served by `research-agent` or `plan-agent`, it says
so rather than cloning them.

**It never invents agents.** If discovery turns up nothing, it says so and
offers to author one. It does not fabricate a `user$agent$…` that was never defined.

## 2A. Scope — Project vs User

`meta-agent$create` persists to `<project>/.brainyard/agents/user$agent/<name>/` by
default — project scope, mirroring `.brainyard/tools`, `.brainyard/skills`, and
`.brainyard/plans`. A user-defined agent authored in a checkout is a shared
project asset, and the project dir is already the working directory, so the
agent travels with the repo and the team gets it on checkout.

A `:scope :user` variant rooted at `~/.brainyard/agents/user$agent/` is the
natural extension for personal/global personas (a copy-editor you want
everywhere), exactly as `skill-agent` offers `:user` scope for skills. The
specialist's instruction is written so adding a `:scope` argument later is a
non-breaking change: default `:project`, reserve `:user` for personal agents.
Until that lands, the specialist states plainly that authored agents live with
the current project.

## 2B. Why a per-agent *directory* (not a file pair)

`tool-agent` and `hook-agent` persist through `def-store` as a `<base>.edn` +
`<base>.clj` sidecar pair, because a tool/hook is *metadata + one body of code*.
A user-defined agent is different: it is metadata + **two prose blocks** that a
human will want to read and edit in an editor with Markdown rendering. So the
unit of persistence is a **directory per agent**, the layout the request fixes:

```
.brainyard/agents/user$agent/<name>/
  agent.edn          metadata: {:name :description :scope :version :created :updated}
  instruction.md     the :instruction block (prose)
  tool-context.md    the :tool-context block (prose)
```

The directory also leaves obvious room to grow without a schema change — a
future `examples.md` (few-shot transcripts), `config.edn` (per-agent
`:max-iterations`, model overrides), or an `evals/` folder for regression
questions — each a new file, not a migration. This is the same "directory of
companion files" shape skills already use (`SKILL.md` + scripts/resources), so
it is a familiar editing experience.

## 3. Position in the Agent Stack

`meta-agent` is a leaf specialist, peer to `tool-agent`, `skill-agent`,
`mcp-agent`, and `config-agent`. It self-registers in `!tool-defs` through its
own `defagent` and is loaded — like every built-in agent — by the single
side-effecting require list in
`components/agent/src/ai/brainyard/agent/interface.clj` (the documented "single
source of truth: add a new agent here when it ships").

There are **two distinct registration moments**, and keeping them separate is
the heart of the design:

1. **The manager** (`meta-agent`) is registered **at compile time** by its
   `defagent`, exactly like every other specialist.
2. **Each user-defined agent** is registered **at runtime** — at session boot
   by the startup loader, and immediately on `meta-agent$create` — by `swap!`-ing a
   synthetic `:type :agent` entry into the same `!tool-defs` registry. This is
   the identical trick `user_tools.clj` already uses to make `user$tool$<name>` a
   first-class tool the moment it is authored (`register!` /
   `load-user-tools!`); §5A applies it to agents.

The consequence is worth stating plainly: once registered, a user-defined agent
is **indistinguishable from a built-in specialist** to the rest of the system.
It shows up in `list-tools` / `search`, flows through `call-tool`'s coercion and
the sub-agent depth/circular guards in `do-call-tool--agent`, can be a routing
target for `main-agent`, and can be composed by a peer agent's tool-call channel
— all without a recompile.

Like its siblings, the manager stays flat: it does not clone-self, and it writes
only under `.brainyard/agents/user$agent/`, reached only through the `meta-agent$*`
commands. It never writes to sibling-specialist storage.

## 4. Capability Surface

The manager handles five capability kinds, each mapping to one `meta-agent$*`
command (authoring and refinement add a `meta-agent$validate` dry-run and an opt-in
post-create verification ask):

1. **Discover** — "what agents have I defined?" → `meta-agent$list`.
2. **Inspect** — "show me the `tf-reviewer` agent" → `meta-agent$read :name
   "tf-reviewer"`.
3. **Author** — "make an agent that …" → `meta-agent$list` (dup check) → settle
   name/description/role → draft `instruction` → name tools in `tool-context` →
   `meta-agent$validate` → `meta-agent$create` → (opt-in) ask `user$agent$<name>` a
   representative question to verify.
4. **Refine** — "change how `tf-reviewer` works" → `meta-agent$read` →
   `meta-agent$validate` the edited draft → re-`meta-agent$create` with the same name
   (create overwrites in place) → re-verify.
5. **Remove** — "delete `copy-editor`" → `meta-agent$delete :name "copy-editor"`.

Authoring and refinement are the same write path: `meta-agent$create` keys on
`:name`, so re-creating an existing name replaces the persisted directory and
the live registry entry. There is no separate `meta-agent$update`; "update" is
"re-create with the same name," reading the old blocks first so the specialist
edits rather than rewrites from scratch. (A real `meta-agent$update` for partial
edits — patch the tool-context without resupplying the instruction — is an open
question, §13.)

## 5. Tool Roster — the `meta-agent$*` family

Primary surface. Collected into a `meta-agent-commands` var in `user_agents.clj`,
mirroring `user-tools/tools-commands` and `skills/skills-commands`:

```clojure
;; in ai.brainyard.agent.common.user-agents
(def meta-agent-commands
  "All meta-agent management commands, for binding into meta-agent."
  [#'meta-agent$create #'meta-agent$validate #'meta-agent$list #'meta-agent$read #'meta-agent$delete])
```

| Command | Args | Effect |
|---|---|---|
| `meta-agent$validate` | `:name?`, `:description?`, `:instruction`, `:tool-context?`, `:sample?` | Dry-run: structural checks on the name/instruction/tool-context plus a collision check — **persists nothing, registers nothing**. With `:sample` (a question string), optionally registers into a throwaway registry fork and runs one ask. Returns a structured report (§5B). |
| `meta-agent$create` | `:name`, `:instruction`, `:description?`, `:tool-context?` | Validate → persist the three files to `.brainyard/agents/user$agent/<name>/` → register `user$agent$<name>` as a CoAct-derived `:type :agent` in `!tool-defs`. Returns `{:id :name :persisted}` or `{:error}`. |
| `meta-agent$list` | — | `{:agents [{:id :description}]}` for every user-defined agent. |
| `meta-agent$read` | `:name` | `{:name :description :instruction :tool-context}` from disk (falls back to registry metadata when files are absent). |
| `meta-agent$delete` | `:name` | Unregister + delete the persisted directory. Returns `{:deleted}` or `{:error}`. |

The authored agent itself — once `meta-agent$create` succeeds, `user$agent$<name>` is a
live, directly-callable `:type :agent`. The manager calls it (with a
representative `:question`) to verify, and thereafter `main-agent` and peer
agents can call it too.

There is deliberately **no tool-binding command** — no `meta-agent$bind-tool`, no
`:agent-tools` argument anywhere. A user-defined agent reaches tools through its
inherited CoAct palette and its tool-context prose; that is the whole point of
§2. The only "tool wiring" the specialist does is *naming* tools in the
tool-context.

Discovery fallbacks (use only when the `meta-agent$*` surface is not enough):
`list-tools` / `get-tool-info` to confirm a tool named in a draft tool-context
actually exists; `search` / `tree` to explore project files and config. These
mirror the fallback set every specialist carries.

## 5A. Runtime Registration — the one new primitive

This is the only machinery `meta-agent` adds that `tool-agent` did not already
have a precedent for, and it is a near-direct port of `user_tools.clj`'s
`register!` / `load-user-tools!` / `ensure-loaded!`.

A user-defined agent is registered by `swap!`-ing a synthetic `:type :agent`
entry into the shared `agent.core.tool/!tool-defs`. The entry's `:fn` is a thin
closure that calls `coact/run-coact-derived` with the persisted instruction and
tool-context spliced in and the CoAct behavior tree pinned — i.e. it constructs,
at call time, exactly the option map a compile-time `defagent` over
`run-coact-derived` would have produced:

```clojure
;; in ai.brainyard.agent.common.user-agents — sketch
(defn- agent-id [name] (keyword (str "user$agent$" name)))

(defn- register-agent!
  "Register (or replace) a user-defined agent in !tool-defs as a CoAct-derived
   :type :agent. The :fn splices the persisted instruction/tool-context into
   run-coact-derived and pins the CoAct BT — no :agent-tools, so the agent rides
   coact-agent's inherited palette (see §2). Mirrors user-tools/register!."
  [{:keys [name description instruction tool-context]}]
  (let [id (agent-id name)]
    (swap! tool/!tool-defs assoc id
      {:id   id
       :type :agent
       :fn   (fn [opts]
               (coact/run-coact-derived
                 (merge {:bt-factory   (fn [{:keys [max-iterations]}]
                                         (coact/coact-behavior-tree max-iterations))
                         :instruction  instruction
                         :tool-context tool-context}
                        opts)))               ;; caller opts (:id :question
                                              ;; :agent-session :parent-agent) win
       :meta {:id            id
              :type          :agent
              :description   description
              :input-schema  [:map
                              [:question [:string {:desc "Request for this agent"}]]
                              [:agent-context {:optional true} [:string {:desc "Extra context"}]]]
              :output-schema [:map [:answer [:string {:desc "Agent's answer"}]]]
              :tool-use-control {}
              :category      :user
              :user-defined  true}})
    id))
```

Two subtleties, both already handled by the existing call path:

- **Instance identity.** When the agent is invoked as a sub-agent,
  `do-call-tool--agent` calls `generate-instance-id` on the registry id, yielding
  `:user$agent$<name>/<suffix>` — a unique, namespaced instance id per call, exactly
  as built-in specialists get `:research-agent/<suffix>`. The
  `generate-instance-id` precondition ("a keyword naming a registered defagent")
  is satisfied because the entry is in `!tool-defs`.
- **`:fn` arity.** `invoke-tool` dispatches a registered `:fn` as
  `(apply tool-fn [options])` — a single options *map* — and `run-coact-derived`
  takes exactly one map. So the closure is `(fn [opts] …)`, matching how
  `user-tools/register!` shapes its `invoke` fn.

Startup loading mirrors `user_tools` precisely:

```clojure
(defn load-user-agents! [& {:keys [dirs]}]    ;; re-register every persisted agent
  ...)                                         ;; read each <name>/ dir, register-agent!
(defn ensure-loaded!  [& {:keys [dirs]}]      ;; idempotent per user/project per process
  ...)
```

`ensure-loaded!` is called on session boot from `coact-init-action` (the same
`(when (nil? existing-sandbox) …)` block in `coact_agent.clj` that already calls
`user-tools/ensure-loaded!` and `user-hooks/ensure-loaded!`), so a project's
user-defined agents are live from the first turn. Unlike user tools there is
**no sandbox to rehydrate** — an agent has no eval-able body, only prose — so
loading is just "read the directory, `register-agent!` each," and it needs no
`:extra-bindings` (the tool palette is the inheriting agent's concern at run
time, not the registration's). That makes loading strictly cheaper and
failure-free relative to tools (no body that can fail to compile).

## 5B. Dry-Run & Validation (`meta-agent$validate`)

`meta-agent$create` couples validation, persistence, and registration into one step.
That is fine for a draft the specialist is confident in, but the wrong primitive
for *iterating*: every attempt writes the directory and mutates the live
registry, and a re-create under an existing name overwrites a working persona.
`meta-agent$validate` splits validation off as a pure, side-effect-free check.

Checks (no LLM call, instant):

- **Name** (when `:name` supplied) — matches `^[a-z][a-z0-9-]*$` (the same
  `tool-name-re` discipline, so `user$agent$<name>` is a clean symbol/keyword and a
  safe directory name), and reports whether it **collides** with an
  already-registered agent (so "author" vs. "overwrite" is explicit, not
  silent).
- **Instruction** — present and non-blank. The instruction *is* the agent; an
  empty one is the one unrecoverable mistake.
- **Tool-context** — when present, every tool it names by id resolves in
  `!tool-defs` (so the agent is not steered toward a tool that does not exist).
  Unresolved tool names come back as warnings, not hard failures — the prose may
  legitimately mention a tool conceptually.

Optional behavioral smoke (`:sample` a question string, **opt-in, costs an LLM
round-trip**): register the draft into a *throwaway fork* of the registry, run a
single ask, capture the answer, discard the fork. This lets the specialist
confirm the persona behaves before anything is persisted — the agent analogue of
`tool-agent$validate`'s `:sample` body run. Because it is an LLM call (slow,
metered), it is never implicit: the specialist runs it only when the user asks
to "try it first," and otherwise reserves the post-create ask (§6 step 5) for
final confirmation against the truly-registered agent.

Return shape (a report, never a throw):

```clojure
{:valid           true|false     ;; AND of the hard checks (name + instruction)
 :name-ok         true|false     ;; nil when :name omitted
 :collision       true|false     ;; would meta-agent$create overwrite an existing agent?
 :instruction-ok  true|false     ;; present and non-blank
 :unknown-tools   ["foo" ...]    ;; tool-context names that don't resolve (warning)
 :sample-answer   "…"            ;; present only when :sample supplied
 :errors          ["…" ...]}     ;; one line per hard failure; empty when :valid
```

It deliberately mirrors `meta-agent$create`'s argument names so a validated draft
promotes to a create call with no reshaping.

## 6. Manager Instruction Shape

The manager's own `:instruction` follows the `tool-agent` / `skill-agent`
template — a role line, a decision flow keyed to the five capability kinds,
content-handling rules for the instruction/tool-context pair, large-output
handling, and a safety block. Sketch:

```
You are an agent-authoring specialist. You help the user discover, inspect,
author, refine, and remove PERSISTENT user-defined agents. An authored agent is
a CoAct-derived specialist saved under <project>/.brainyard/agents/user$agent/
<name>/ (agent.edn + instruction.md + tool-context.md), registered as
user$agent$<name>, and callable as a first-class agent on the very next turn. It
inherits the full CoAct loop and tool palette — you NEVER bind tools to it; you
shape it entirely through its INSTRUCTION and its TOOL-CONTEXT. Authored agents
live with the CURRENT PROJECT (project scope) — say so plainly.

DECISION FLOW
1. Classify the ask:
   - discover → meta-agent$list
   - inspect  → meta-agent$read :name <name>
   - author   → see AUTHORING
   - refine   → meta-agent$read first, then re-create with the SAME name
   - remove   → meta-agent$delete :name <name>
2. Before authoring, ALWAYS meta-agent$list (and meta-agent$read near-matches) to avoid
   duplicating an existing agent. If a BUILT-IN specialist already covers the
   need (research, planning, exploring, file edits, …), say so and prefer it to
   a clone.

AUTHORING (the disciplined path — instruction first, tools second)
1. Settle the identity BEFORE writing prose:
   - :name         lowercase-kebab, leading letter, ^[a-z][a-z0-9-]*$ (no
                   user$agent$ prefix). It becomes the directory and the symbol.
   - :description  one tight line — what other agents and the router see.
   - the ROLE      one sentence: who this agent is and what it is for.
2. Draft the :instruction. This IS the agent. Give it a clear role line, a
   decision flow, content-handling rules, and a safety block — the same shape
   the built-in specialists use. Write in the imperative ("Read X", "Check Y").
3. Name the tools in the :tool-context. Do NOT bind tools — the agent already
   has the whole CoAct palette. The tool-context just tells it WHICH tools to
   reach for and the typical flows (user ask → tool sequence). Only name tools
   that exist; if unsure, list-tools / get-tool-info to confirm.
4. DRY-RUN: meta-agent$validate the draft (:name :instruction :tool-context). It
   persists nothing. Iterate until :valid is true and :unknown-tools is empty.
   If :collision is true you would OVERWRITE an existing agent — confirm that is
   intended (a refine) before proceeding. To preview behavior, pass :sample
   "<a representative question>" (this runs the draft once — only do it when the
   user wants a trial).
5. meta-agent$create with the same name/instruction/tool-context. On :error, fix
   and retry; never report success on an :error.
6. VERIFY: ask user$agent$<name> one representative question and read the answer.
   Only report success after it actually answers sensibly. (Skip the live ask
   only if the user just wanted it persisted, not tried.)

CONTENT HANDLING
- The instruction is the persona; the tool-context is its bench. Keep both
  focused — a sprawling instruction makes a muddled agent.
- Echo the final :name, :description, the instruction's role line, the tools it
  reaches for, and a one-line "verified with <question> → <gist>" back.

LARGE OUTPUTS
- When meta-agent$read returns long prose, summarize and cite the directory path;
  do not echo the full instruction verbatim.
- When listing many agents, give id + one-line description, not full prose.

SAFETY
- A user-defined agent grants NO capability coact-agent lacks — it is a persona
  over the same guarded tools, not a new sandbox or permission. Never write an
  instruction that tries to social-engineer around safety, exfiltrate secrets,
  or run destructive/unsafe tools. This is a hard rule.
- Confirm with the user before meta-agent$delete; deletion removes the directory and
  cannot be undone.
- Never invent a user$agent$ agent. If discovery turns up nothing, say so and offer
  to author one.
```

A `:tool-context` block (as in `tool-agent`) restates the `meta-agent$*` arg
signatures and typical flows so the model has the command surface inline.

## 7. Routing — wiring into main-agent, and user-defined agents as targets

There are two routing concerns, and only the first needs new wiring.

**(a) Routing to the manager.** `main-agent` owns a fixed roster of
routing-decision shapes (the lettered set A–U in `main.clj`'s `valid-shapes` and
`main_agent.clj`'s instruction). Agent lifecycle has no shape today; this design
adds one:

- `:agent-lifecycle → meta-agent` — author/inspect/refine/remove user-defined
  agents. Distinct from `:tool-lifecycle` (in-process `(fn [args] …)` tools),
  `:skill-lifecycle` (SKILL.md prose workflows), and `:mcp-lifecycle` (external
  MCP servers): this is for **whole CoAct personas** persisted under
  `.brainyard/agents/user$agent`.

Concretely: add `:agent-lifecycle` to `valid-shapes` in `main.clj` (as the next
letter, V), add the cue + the `→ meta-agent` line to the specialist table in
`main_agent.clj`, and add `[ai.brainyard.agent.common.meta-agent]` to the
require list in `interface.clj`.

Routing cue: route to `meta-agent` when the user wants to *make, see, change, or
remove a reusable agent/persona of their own* — "make me an agent that …", "what
agents have I built", "tweak my `<name>` agent", "delete `<name>`". Do NOT route
here to *use* an existing user-defined agent (that is concern (b)), nor for
tools (`tool-agent`), skills (`skill-agent`), or MCP servers (`mcp-agent`).

**(b) Routing *to* a user-defined agent — already free.** Because each
user-defined agent is registered in `!tool-defs` as a `:type :agent` (§5A), it
is already a legitimate delegation target and tool-call. Two integration points
make this useful, and both are extensions rather than new mechanisms:

- The router's specialist roster is assembled from the registry, so a
  registered `user$agent$<name>` can be surfaced to `main-agent` as a candidate —
  routing a matching ask straight to the user's own agent. (How aggressively to
  advertise user agents to the router is an open question, §13 — the safe
  default is to keep them callable-by-name but not auto-routed until the user
  opts in.)
- Any agent's tool-call / code-block channel can invoke `user$agent$<name>`
  directly, so user-defined agents compose with each other and with built-ins
  under the existing depth/circular guards.

No new code is required for (b); it falls out of registering as `:type :agent`.

## 8. File Layout

```
components/agent/src/ai/brainyard/agent/common/
  meta_agent.clj          NEW — the manager defagent (instruction + meta-agent$* roster)
  user_agents.clj         NEW — meta-agent$* commands + persistence + runtime registration
                                (register-agent! / load-user-agents! / ensure-loaded!)
```

The manager file mirrors `tool_agent.clj` / `skill_agent.clj` almost exactly:

```clojure
(ns ai.brainyard.agent.common.meta-agent
  "User-agent — a CoAct-derived specialist for authoring, inspecting, refining,
   and removing persistent user-defined agents (the meta-agent$* command family).
   The agents it authors are themselves CoAct-derived, shaped entirely by their
   persisted :instruction and :tool-context — no bound tools."
  (:require [ai.brainyard.agent.core.tool :refer [defagent]]
            [ai.brainyard.agent.common.coact-agent :as coact]
            [ai.brainyard.agent.common.user-agents :as user-agents]))

(def ^:private instruction "...")    ;; §6
(def ^:private tool-context "...")   ;; §6

(defagent meta-agent
  "Specialist for authoring persistent user-defined agents (create/validate/list/read/delete)."
  coact/run-coact-derived
  ;; Pin :bt-factory so direct-resolution entry points pick up the CoAct BT.
  :bt-factory (fn [{:keys [max-iterations]}]
                (coact/coact-behavior-tree max-iterations))
  :tool-use-control {}
  :input-schema  [:map
                  [:question [:string {:desc "User question about user-defined agents"}]]
                  [:agent-context {:optional true} [:string {:desc "Extra context"}]]]
  :output-schema [:map
                  [:answer [:string {:desc "Summary of the agent operation / authored agent id"}]]]
  :agent-tools {:tools user-agents/meta-agent-commands}
  :instruction instruction
  :tool-context tool-context)
```

The new machinery is confined to `user_agents.clj` (the `meta-agent$*` commands and
the §5A registration layer). The manager itself adds no persistence or runtime
logic — it is a curated front end over those commands.

> **As-built:** `user_agents.clj` does **not** reuse `def_store.clj`. It writes the
> three companion files (`agent.edn` pretty-printed metadata, `instruction.md` and
> `tool-context.md` verbatim prose) directly with `spit`, and reads them back with a
> small `read-agent-dir` helper. The §2B per-directory layout is exactly as shipped.

## 9. Conversation Patterns (worked examples)

### 9.1 Author an agent (capability kind 3)

> "Make me an agent that reviews Terraform diffs against our tagging policy."

1. `meta-agent$list` — no `tf-reviewer` yet; no built-in covers this.
2. Settle identity: name `tf-reviewer`; description "Review Terraform diffs for
   tagging-policy compliance"; role "a Terraform reviewer that checks every
   changed resource for the required `owner`/`cost-center` tags."
3. Draft the `instruction`: a decision flow (read the diff → enumerate changed
   resources → check each for required tags → report violations with file:line),
   content-handling rules, and a safety block.
4. Name tools in the `tool-context`: reach for `bash` (`git diff`), `read-file`,
   and `search` (find the policy doc); typical flow "review the staged diff →
   `(bash {:command \"git diff --staged\"})` → per-resource tag check → report."
   No tools are bound — these are just named.
5. `meta-agent$validate :name "tf-reviewer" :instruction <…> :tool-context <…>` →
   `{:valid true :collision false :instruction-ok true :unknown-tools []}`.
   Nothing persisted.
6. `meta-agent$create` → `{:id "user$agent$tf-reviewer" :persisted
   ".brainyard/agents/user$agent/tf-reviewer/"}`.
7. Verify: ask `user$agent$tf-reviewer :question "review the staged diff"` and read
   that it enumerates resources and flags missing tags.
8. Report: created `user$agent$tf-reviewer`, reaches for `bash`/`read-file`/`search`,
   verified on the staged diff.

### 9.2 Refine an existing agent (capability kind 4)

> "Have `tf-reviewer` also check for an `environment` tag."

1. `meta-agent$read :name "tf-reviewer"` → current instruction + tool-context.
2. Edit the instruction's tag list to add `environment`.
3. `meta-agent$validate` the edited draft — `:collision true` confirms this
   re-create overwrites the existing agent (the intended refine).
4. `meta-agent$create` with the same `:name` (overwrites directory + registry entry).
5. Re-verify on the same diff; report what changed.

### 9.3 Compose agents (capability kind 3, composition)

> "Add a `release-captain` agent that plans the release and then has
> `tf-reviewer` check the infra changes."

The `release-captain` instruction tells it to call the peer `user$agent$tf-reviewer`
for the infra step (and perhaps the built-in `plan-agent` for sequencing). The
manager confirms `user$agent$tf-reviewer` is registered (`meta-agent$list`) before the
tool-context names it. At run time the composition rides the existing
depth/circular guards in `do-call-tool--agent`.

### 9.4 Discover & inspect (capability kinds 1–2)

> "What agents have I built, and what does `copy-editor` do?"

`meta-agent$list` for the roster, then `meta-agent$read :name "copy-editor"` for the
instruction + tool-context of the named one. Long instructions are summarized
with the directory path, not dumped.

### 9.5 Remove (capability kind 5)

> "Delete `copy-editor`."

Confirm intent, `meta-agent$delete :name "copy-editor"` → `{:deleted "copy-editor"}`.
The registry entry is dropped immediately; the orphaned instance ids (if any
were live) close on their own as ephemeral sub-agents.

## 10. Edge Cases & Safety

**Name validation.** `:name` must match `^[a-z][a-z0-9-]*$`. The manager
normalizes a free-text name to kebab-case and confirms before creating; an
invalid name is rejected at `meta-agent$validate` (`:name-ok false`) before
`meta-agent$create` is called.

**Empty instruction.** The one unrecoverable authoring mistake — a registered
persona with no guidance. Caught at `meta-agent$validate` (`:instruction-ok false`)
before anything is persisted; `meta-agent$create` re-checks as a backstop and
returns `{:error}` without writing.

**Tool-context names a non-existent tool.** Surfaced as `:unknown-tools` in
validate (a warning, not a hard failure — prose may mention a tool
conceptually). The manager resolves real tool names with `list-tools` /
`get-tool-info` and corrects the draft so the authored agent is steered only
toward tools that exist.

**Silent overwrite.** `meta-agent$create` keys on `:name`, so re-creating an
existing name replaces a working agent with no warning. The pre-create
`meta-agent$validate` surfaces `:collision true`, turning "author" vs. "overwrite"
into an explicit confirmation.

**Capability boundary.** A user-defined agent is CoAct-derived and reaches the
*same* tool palette as `coact-agent`, under the *same* permission, visibility,
and depth guards (`call-tool`, `tool-visible?`, `do-call-tool--agent`). It grants
**no new privilege** — it cannot do anything `coact-agent` could not already do;
it is a persona, not a sandbox escape. The manager must not author an instruction
that attempts to widen this boundary (steering toward secret exfiltration,
destructive shell, writes outside the workspace). Hard rule in the safety block.

**Runaway / cost.** A user-defined agent runs a full CoAct loop bounded by
`:max-iterations` (config-schema default 100) and the sub-agent
`:max-agent-call-depth` (default 3) and circular-call detection — the same
ceilings every specialist runs under. A poorly written instruction can waste
iterations but cannot recurse without bound. The opt-in nature of the verify
ask (§5B/§6) keeps authoring itself cheap.

**Source absent on disk.** `meta-agent$read` falls back to registry metadata
(`:instruction`/`:tool-context` nil, a `:note`) when the directory is missing,
rather than fabricating prose.

**Deletion is destructive.** `meta-agent$delete` removes the persisted directory.
The manager confirms before deleting and cannot recover it afterward.

**Boot-order independence.** `load-user-agents!` registers every persisted agent
at session boot before any of them runs, so an instruction that names a peer
`user$agent$<name>` resolves regardless of directory order — the same one-pass
guarantee `user_tools` provides (and simpler here, since there is no body to
eval in a second pass).

## 11. Testing Plan

**Command level** (`user_agents.clj`): round-trip `meta-agent$create` →
`meta-agent$list` → `meta-agent$read` → `meta-agent$delete`; invalid-name rejection;
empty-instruction rejection persists nothing; re-create-same-name overwrites both
directory and registry entry; `register-agent!` puts a `:type :agent` entry in
`!tool-defs` with `:user-defined true` and **no `:agent-tools`**; `meta-agent$read`
falls back to registry metadata when the directory is absent. For
`meta-agent$validate`: a valid draft returns `:valid true` and leaves `!tool-defs`
and the project dir untouched (the dry-run guarantee); bad name / empty
instruction each flip the matching flag and populate `:errors`;
`:unknown-tools` lists tool-context names that don't resolve; `:collision` is
true iff the name is already registered; a `:sample` registers into a fork, runs
one ask, and leaves the live registry untouched.

**Agent level** (mirroring `tool_agent` / `config_agent` tests under
`components/agent/test/ai/brainyard/agent/`): `meta-agent` self-registers in
`!tool-defs` with `:type :agent`; routes an "author an agent" question through
`meta-agent$create` and then asks `user$agent$<name>` to verify; runs `meta-agent$list`
before authoring (dup check); refuses to delete a non-existent agent;
summarizes rather than dumps long instructions.

**Runtime-registration level** (new, the load-and-run path): after
`meta-agent$create`, the registered `user$agent$<name>` is invocable as a sub-agent and
returns an `{:answer …}`; `generate-instance-id` yields a
`:user$agent$<name>/<suffix>` instance; the sub-agent inherits `coact-agent`'s tool
roster (assert a known common tool is reachable from inside it) with no
per-agent `:agent-tools`; `ensure-loaded!` re-registers persisted agents on a
fresh process and is a no-op on the second call.

**Routing level**: a `main-agent` routing test asserting `:agent-lifecycle →
meta-agent` for agent-authoring phrasings, and that tool / skill / MCP phrasings
still select their own specialists (no regression in the existing roster).

**Smoke test against the binary** after build:

```
projects/agent-tui-app/target/by agents        # meta-agent appears in the registry
projects/agent-tui-app/target/by ask -p bedrock -m amazon.nova-lite-v1:0 \
  'use the user agent to make an agent that summarizes a file in one line, then run it on README.md'
```

## 12. Migration / Phasing

**Phase 1 — ship the manager + runtime registration.** Add `user_agents.clj`
(the `meta-agent$*` commands, `register-agent!` / `load-user-agents!` /
`ensure-loaded!`, and `meta-agent-commands`), create `meta_agent.clj`, wire
`ensure-loaded!` into the session-boot block in `coact-init-action`
(`coact_agent.clj`) right after the existing `user-tools` / `user-hooks`
loaders, and add both new nses to `interface.clj`. The manager is immediately usable by direct invocation,
with validate-before-create and a runtime-registered, callable agent on success.

**Phase 2 — wire routing.** Add `:agent-lifecycle` to `main.clj`'s
`valid-shapes` and the cue to `main_agent.clj` so `main-agent` delegates
authoring automatically. Add the routing regression test.

**Phase 3 (optional)** — close the §13 gaps: a real `meta-agent$update` for partial
edits, `:scope :user`, per-agent config (model / `:max-iterations`) via a
`config.edn` in the agent directory, and opt-in router advertisement of
user-defined agents as first-class delegation targets. Each is a `user_agents.clj`
change the manager's instruction can adopt without a structural rewrite.

## 13. Open Questions

**`meta-agent$update` vs. re-create.** Today "update" is "re-create with the same
name," resupplying both prose blocks. A dedicated `meta-agent$update` (patch the
tool-context without resupplying the instruction, or vice versa) would be cleaner
once refinement becomes the dominant flow.

**User scope.** Default project and offer `:scope :user`
(`~/.brainyard/agents/user$agent/`), exactly as `skill-agent` does for skills?
Recommended yes, once the storage variant lands.

**Per-agent config.** Should an authored agent carry overrides
(`:max-iterations`, a model pin, memory opt-in/out) in `agent.edn` or a sidecar
`config.edn`, threaded through `:config-extra` at registration? Useful for a
heavyweight research-style persona vs. a quick one-line summarizer.

**Router advertisement.** Once `user$agent$<name>` is registered it is callable, but
how visible should it be to `main-agent`'s classifier? Auto-routing every user
agent risks hijacking asks the built-ins should handle; the safe default is
callable-by-name and composable, with explicit opt-in (a flag in `agent.edn`)
before the router advertises it as a top-level target.

**Few-shot examples.** Should the directory support an `examples.md` of
question→answer transcripts spliced into the instruction at registration?
Few-shots are the highest-leverage way to shape a persona; the directory layout
already leaves room (§2B).

**Provenance.** Should `agent.edn` record who/when/why (and a `version`, as
skills carry)? Helps audit LLM-authored personas that accrete over a project's
life.

**Verify-cost policy.** The post-create ask is an LLM round-trip. Should it be
default-on (safer, slower) or default-off (cheaper, faster) with the user opting
in per the §6 flow? Current recommendation: default-on for a single representative
question, skippable when the user only wants the agent persisted.
