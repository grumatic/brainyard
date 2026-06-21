# Skills design

How brainyard's agent runtime defines, registers, discovers, and dispatches
*skills* — reusable capabilities that an LLM-driven agent can invoke.

A skill comes in two flavors that share one registry but have very different
execution models:

- **Static skills** (`defskill`) — real Clojure functions, compiled into the
  image, called directly.
- **Dynamic skills** — `SKILL.md` documents discovered on disk at runtime.
  Each is surfaced as a `:skill$<name>` tool whose body is *not* code but a
  thin wrapper that hands the document to the **skill-agent** to interpret.

This document explains both paths and — the part that surprises people — why a
dynamically-registered skill routes through the skill-agent instead of calling
a function directly.

## The unified tool registry

Everything callable by an agent — commands, skills, sub-agents, MCP tools —
lives in a single atom:

```clojure
(defonce !tool-defs (atom {}))   ; agent/core/tool.clj
```

Each entry is keyed by id and carries a `:type` discriminator:

```clojure
{:id   :skill$foo
 :type :skill            ; :tool | :command | :skill | :agent
 :fn   (var ...)         ; the callable
 :meta {...}}            ; description, :input-schema, :output-schema, ...
```

`:type` is what the rest of the system filters and routes on. The sandbox
auto-binds every *visible* entry as a callable function (see
`agent/common/sandbox_bindings.clj` → `auto-tool-bindings`), and the LLM
tool-descriptor layer reads from the same registry. There is one source of
truth; the three flavors differ only in how their `:fn` is built.

## Path 1 — static skills (`defskill`)

`defskill` (`agent/core/tool.clj`) is a thin macro over `deftool` that sets
`:type :skill`:

```clojure
(defmacro defskill
  "Define a skill. Thin wrapper around deftool with :type :skill."
  [skill-name description use-fn & {:as args}]
  `(deftool ~skill-name ~description ~use-fn
     ~@(mapcat identity (assoc args :type :skill))))
```

`deftool` defines a var, wraps it so call args are merged with metadata, and
registers it:

```clojure
(swap! !tool-defs assoc id {:id id :type tool-type :fn (var tool-name) :meta options})
```

Properties:

- **Compile-time** registration — baked into the code (and the native image).
- The `:fn` is a **real Clojure function** (the supplied `use-fn`).
- Invocation is a **direct function call** — no sub-agent, no LLM round-trip.

Use `defskill` when the capability is deterministic code: it's fast, testable,
and rigid.

## Path 2 — dynamic skills (`SKILL.md` on disk)

A dynamic skill is a markdown document, not code. It is discovered and
registered at *runtime*, entirely separate from `defskill`.

### Discovery roots

`list-skills` walks these roots (`agent/common/skills.clj`):

| Root                    | `type`     | Scope                |
|-------------------------|------------|----------------------|
| `.brainyard/skills/`    | `brainyard`| project              |
| `~/.brainyard/skills/`  | `brainyard`| user                 |
| `~/.claude/skills/`     | `claude`   | CLI-installed        |
| `~/.agents/skills/`     | `agents`   | CLI-installed        |

### Registration

`reload-skills!` is the entry point. It diffs the discovered set against the
set of previously-registered dynamic ids and reconciles:

```clojure
(defn reload-skills! []
  (let [skills     (list-skills (current-dirs))
        prior      @!registered-dynamic-skill-ids
        registered (into #{} (keep register-dynamic-skill!) skills)
        stale      (set/difference prior registered)]
    (doseq [id stale] (unregister-dynamic-skill! id))
    {:registered ... :unregistered ... :total ...}))
```

`register-dynamic-skill!` validates the name, synthesizes a `:fn` (see below),
interns a var, and adds the `:skill$<name>` entry to `!tool-defs`. The
`skills$reload` command exposes this to the LLM so it can re-scan after editing
or installing a skill.

### Registration is a runtime call, never `defonce`

There is a deliberate comment in `skills.clj` warning against
`(defonce _ (reload-skills!))`. A `defonce` fires at namespace
class-initialization, which for the native `by` binary happens at **build
time** (the native-image policy initializes all `ai.brainyard.*` namespaces at
build time). That would scan the *build machine's* skill directories and bake
the snapshot into the image heap — the user's own `~/.brainyard`, `~/.claude`,
`~/.agents` skills would never be read. So each entry point calls
`reload-skills!` explicitly once after config/dirs init (e.g. the TUI's
`start!`).

## Dispatch — why dynamic skills go through the skill-agent

This is the crux. The `:fn` that `register-dynamic-skill!` installs is built by
`make-dynamic-skill-fn`. It does **not** contain the skill's logic — it cannot,
because the skill is a markdown document. Instead, on every call it:

1. Re-reads `SKILL.md` **fresh** from disk (so edits propagate without reload).
2. Wraps the content into a system prompt via `skill-system-prompt`.
3. Dispatches the user's `question` to the **`:skill-agent`** via
   `tool/invoke-tool`, passing the SKILL.md as `:agent-context`.
4. Resolves the sub-agent result and formats the answer.

```clojure
(defn- make-dynamic-skill-fn [skill-name skill-type skill-scope]
  (fn [& {:keys [question]}]
    (let [q (or question "")]
      (cond
        (str/blank? q)                        {:error-message "question is required" ...}
        (nil? (tool/get-tool-defs :id :skill-agent))
                                              {:error-message "skill-agent not registered ..." ...}
        :else
        (let [skill (read-skill (current-dirs) skill-name :type skill-type :scope skill-scope)
              ctx   (skill-system-prompt skill-name (:content skill))
              raw   (tool/invoke-tool :skill-agent
                                      :question q :agent-context ctx
                                      :parent-agent  proto/*current-agent*
                                      :auto-close?   true)]
          (format-skill-output (tool/resolve-agent-ref raw) skill-name (:type skill)))))))
```

So the honest framing is: **for a dynamic skill there is no "direct skill
function" to bypass.** The `:fn` *is* the skill-agent dispatch. The skill body
is prose meant to be interpreted, and the skill-agent is the interpreter
harness.

This indirection buys three things:

- **Live editing** — `SKILL.md` is re-read on every invocation; no reload step
  between an edit and the next call.
- **Tool composition** — the skill-agent carries `skills$*`, bash, read-file,
  etc., so a markdown skill can orchestrate real work without shipping code.
- **Native-image correctness** — because registration is runtime and the body
  is a document, the binary reads the *user's* skills at runtime rather than a
  build-time snapshot.

Contrast across all three registry flavors:

| Flavor          | `:fn` body                         | Invocation             |
|-----------------|------------------------------------|------------------------|
| `defskill`      | real Clojure fn (`use-fn`)         | **direct** call        |
| dynamic skill   | wrapper around a markdown document | routed via **skill-agent** |
| MCP tool        | RPC proxy to the MCP server        | **direct** call        |

`defskill` is the only "skill" with a directly callable body. A dynamic skill
has none — by design.

## The skill-agent

`agent/common/skill_agent.clj` defines the specialist that executes dynamic
skills and also handles skill *lifecycle* requests:

```clojure
(defagent skill-agent
  "Specialist for skill usage and lifecycle (list/find/read/create/update/remove/install/sync)."
  coact/run-coact-derived
  :bt-factory   (fn [{:keys [max-iterations]}] (coact/coact-behavior-tree max-iterations))
  :input-schema  [:map [:question ...] [:agent-context {:optional true} ...]]
  :output-schema [:map [:answer ...]]
  :agent-tools  {:tools skills/skills-commands}   ; skills$list/find/read/write/install/sync/reload
  :instruction  instruction)
```

The `skills$*` set is `list / find / read / write / install / sync / reload`
(`skills/skills-commands`). Create / update / remove are not separate commands —
they are the polymorphic `skills$write` mutation surface (`:op
:create | :update | :remove`).

It runs the CoAct behavior tree (a ReAct-style thought/action loop). It is
invoked two ways:

1. **Directly** — when a user/agent wants to *manage* skills ("list my skills",
   "install skill X", "create a skill for Y"). It uses the `skills$*` commands.
2. **Indirectly** — as the execution engine for every dynamic `:skill$<name>`
   tool, with the SKILL.md supplied as `:agent-context`.

### Sub-agent guards

Because dynamic-skill dispatch spawns a sub-agent, it is subject to the same
guards as any agent-typed tool call (`do-call-tool--agent` in
`agent/core/tool.clj`):

- **Kill switch** — `enable-subagent-calls` must be on.
- **Depth limit** — `max-agent-call-depth` bounds recursion.
- **Cycle detection** — a `:skill-agent` already on the call chain is rejected.

Each dispatch creates a **fresh** skill-agent instance and (by default)
auto-closes it when the call returns.

## Visibility & sandbox binding

Whether a registry entry is offered to a given agent is decided by
`tool-visible?` (`agent/core/tool.clj`), driven by the entry's
`:tool-use-control`:

1. `{:visibility :hidden}` — invisible to all
2. `{:allow ["react-*"]}` — only matching agent ids
3. `{:deny  ["coact-*"]}` — all except matching ids
4. `{}` / nil — visible to all

`auto-tool-bindings` (`agent/common/sandbox_bindings.clj`) iterates `!tool-defs`,
keeps the visible entries, and for each derives a sandbox-callable wrapper from
its `:input-schema` (required fields → positional args, optional → trailing
kwargs), supporting both positional-first and pure-kwargs calling styles. A
dynamic skill therefore becomes callable from a `clojure` fence exactly like
any other tool — and calling it triggers the skill-agent dispatch described
above.

## Choosing static vs dynamic

| Want…                                   | Use            |
|-----------------------------------------|----------------|
| Deterministic logic, speed, unit tests  | `defskill`     |
| Prose instructions, live editing, no compile, tool orchestration by an LLM | `SKILL.md` (dynamic) |

There is currently **no** path for a dynamic (`SKILL.md`) skill to run without
the skill-agent. If a markdown skill turns out to need a deterministic, no-LLM
body, port it to `defskill`. (A future option would be to fast-path
`make-dynamic-skill-fn` for skills whose frontmatter declares a pure/code body,
calling a bound fn directly instead of `invoke-tool :skill-agent` — not
implemented today.)

## Key files

| File | Role |
|------|------|
| `components/agent/src/ai/brainyard/agent/core/tool.clj` | `!tool-defs`, `deftool`/`defskill`, `tool-visible?`, `do-call-tool--agent` |
| `components/agent/src/ai/brainyard/agent/common/skills.clj` | discovery (`list-skills`), `reload-skills!`, `register-dynamic-skill!`, `make-dynamic-skill-fn`, `skills$reload` |
| `components/agent/src/ai/brainyard/agent/common/skill_agent.clj` | `skill-agent` defagent + `skills$*` commands |
| `components/agent/src/ai/brainyard/agent/common/sandbox_bindings.clj` | `auto-tool-bindings` — registry → sandbox callables |
