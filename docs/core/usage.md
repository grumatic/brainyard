# Usage Guides

On-demand, just-in-time topic context for the LLM. A *usage guide* is a short
markdown explainer for one capability (`:memory`, `:plans`, `:nrepl`, …). Instead
of inlining every explainer into the system prompt — paying for all of them every
turn — the agent surfaces the one that's relevant, when it's relevant.

Guides live in an **open registry** (mirroring the tool registry): any brick can
register a topic, so a guide can live next to the feature it documents.

Primary files:

- `components/agent/src/ai/brainyard/agent/core/usage.clj` — the registry (atom + API).
- `components/agent/src/ai/brainyard/agent/common/usage_guides.clj` — the built-in guide CONTENT.
- `components/agent/src/ai/brainyard/agent/common/commands.clj` — the `usage` tool (`:usage`).
- `components/agent/src/ai/brainyard/agent/common/sandbox_bindings.clj` — the `(usage …)` sandbox binding.
- `components/agent/src/ai/brainyard/agent/common/usage_nudge.clj` — the JIT push (first-use / error / inline).
- `components/agent/src/ai/brainyard/agent/common/coact_agent.clj` — renders the consult-table into `## Tools`.

---

## The registry

`core/usage.clj` keeps all guides in one process-wide atom, `!usage-defs`,
populated at namespace load (a plain `defonce` atom — GraalVM-safe; registration
is idempotent, so reloads and re-registration just overwrite):

```clojure
{:topic    :memory          ; keyword key
 :title    "Memory"         ; human label (defaults to a humanized topic)
 :guide    "## Memory\n…"   ; the markdown explainer
 :category :memory          ; TOPICAL grouping (for catalog display)
 :scope    :system          ; PROMOTION tier — :system | :user  (default :user)
 :consult  "Before …"       ; one-line "when to consult" hint (system-prompt table)
 :order    3}               ; deterministic listing order
```

API:

| Fn | Returns |
|---|---|
| `register-usage! [topic opts]` | registers/overwrites; throws on a blank guide. Returns the topic keyword. |
| `defusage [topic opts]` | macro sugar over `register-usage!`, for colocating a guide with its feature. |
| `get-usage-guide [topic]` | the guide string, or nil. Accepts keyword / string / `":kw"` / symbol. |
| `usage-def [topic]` | the full registry entry, or nil. |
| `list-usage-topics []` | all topic keywords, in `[:order :topic]` order. |
| `usage-catalog []` | `[{:topic :title :category}]` for every guide, in listing order. |
| `consult-table []` | markdown "when to consult" table — **`:system` guides only** (see below). |

`:category` vs `:scope` are **orthogonal**: `:category` is topical grouping
(`:memory`, `:sandbox`, `:tools`, …) used for catalog display; `:scope` is the
promotion tier that decides whether a guide rides the always-on system prompt.

---

## Scope: what rides the system prompt

The always-on `### Usage Guides` table in `## Tools` is the only place guides cost
tokens **every turn**. To keep it lean, `consult-table` includes a guide only when
its `:scope` is `:system` (and it declares a `:consult` hint):

- **`:system`** — foundational, always-on. Listed in the consult-table. The
  centralized built-in batch defaults to this.
- **`:user`** *(default)* — on-demand only. Reachable via `(usage)` and the JIT
  nudge, but kept out of the always-on table. Runtime-created guides and
  specialized/agent-colocated topics land here.

A `:user` guide is **not** lost — it's one `(usage :topic)` away, and the nudge
will push it the moment it's relevant. Demoting a topic to `:user` is the
zero-loss way to recover per-turn prompt budget.

---

## Built-in content

`common/usage_guides.clj` registers the foundational set as one ordered batch.
That batch defaults to `:scope :system`; extended topics opt out to `:user`:

| Scope | Topics |
|---|---|
| `:system` (always-on) | `:llm-query` `:agent-state` `:memory` `:todo` `:plans` `:skills` `:files` `:artifacts` `:mcp` `:tool-priority` `:discovery` `:truncation` `:final` `:feedback` `:rules` |
| `:user` (on-demand) | `:tool` `:code` `:sandbox` `:agents` |

### Colocation

A guide does not have to live in `usage_guides.clj`. The registry's purpose is to
let a guide sit next to the feature it documents. The `:nrepl` guide is the
worked example: it is defined and registered in
`common/debug_agent.clj` (the live-runtime agent), which also **inlines that exact
string as its own tool-context** — one source, two consumers.

```clojure
;; in debug_agent.clj
(def ^:private nrepl-guide "## Live runtime (clj-nrepl)\n…")
(usage/register-usage! :nrepl {:guide nrepl-guide :scope :user :category :debug …})
(def ^:private debug-tool-context
  (str debug-lifecycle-preamble "\n\n" (usage/get-usage-guide :nrepl)))
```

Because `:nrepl` is colocated, it is only registered when `debug-agent` is on the
classpath — by design: the JIT nudge that surfaces it keys off `clj-nrepl$*`
tools, which are debug-only too, so the guide and its trigger appear together.

---

## The three delivery paths

All three read from the same registry, so there is exactly one copy of each guide.

### 1. Pull — `(usage …)` and the `:usage` tool

Inside a `clojure` fence (SCI sandbox backend), the `usage` binding is bound:

```clojure
(usage)          ; → vector of all topic keywords
(usage :memory)  ; → the guide string
(usage :nope)    ; → {:error … :topics […]}
```

The binding **delegates to the `:usage` tool** (`call-tool :usage`) and unwraps
the result to the legacy shape (a guide string / topic vector / error map), so
the tool is the single dispatch point. The tool is also callable directly through
the tool-calls channel — `(call-tool :usage {:topic "memory"})` — which is how
non-sandbox backends (e.g. `:nrepl`) reach guides:

```clojure
(usage/list-usage-topics)            ; no topic  → {:topics […]}
(call-tool :usage {:topic "memory"}) ; a topic   → {:topic "memory" :guide "…"}
```

### 2. JIT push — `usage_nudge.clj`

The pull path is opt-in and the model often forgets. The nudge **pushes** the
relevant guide at the moment of need, via observers on `:agent.tool-use/post` and
`:agent.tool-use/rejected`:

- **First-use auto-inline** — the first time the model invokes a guide-backed tool
  family this session, that family's guide is folded into the next iteration's
  context (once per topic).
- **Error-triggered** — if that first call errored (runtime or arg-validation),
  the guide rides the feedback under an "(that call errored)" header.

The family→topic map (`tool-family->topic`) is deliberately conservative — only
easy-to-misuse families with a dedicated guide, e.g. `memory → :memory`,
`plan → :plans`, `code → :code`, `clj-nrepl → :nrepl`.

### 3. Permanent inline — `:inline-usage-guides` config

Topics named in the config key `:inline-usage-guides` (default `[:artifacts]`)
are rendered into the agent's tool-context every turn and pre-marked as shown, so
the JIT path never re-surfaces them. Use sparingly — this is always-on weight.

---

## Adding a topic

**Centralized** (foundational, always-on): add a spec to the `guides` vector in
`usage_guides.clj`. It defaults to `:scope :system`; add `:scope :user` to keep it
on-demand.

**Colocated** (feature-specific): call `register-usage!` / `defusage` from the
namespace that owns the feature, defaulting to `:scope :user`:

```clojure
(require '[ai.brainyard.agent.core.usage :as usage])
(usage/defusage :my-thing
  {:title    "My Thing"
   :category :my-area
   :guide    "## My Thing\n…how to use it…"})   ; :scope defaults to :user
```

Either way the topic is immediately reachable via `(usage :my-thing)`, the
`(usage)` catalog, and — if you add a `tool-family->topic` entry — the JIT nudge.

---

## Testing

- `components/agent/test/ai/brainyard/agent/core/usage_test.clj` — registry:
  register/get/list/catalog, coercion, blank-guide rejection, and scope gating
  (`:system` in the consult-table, `:user` omitted but still in the catalog).
- `components/agent/test/ai/brainyard/agent/common/commands_test.clj` — the
  `:usage` tool + the `(usage …)` binding's legacy-shape contract.
- `components/agent/test/ai/brainyard/agent/common/debug_agent_test.clj` —
  `:nrepl` colocation: the guide registers when debug-agent loads, and the
  tool-context inlines that exact string.

Run a single namespace against a live dev REPL with `bb repl:test <ns>`, or the
whole brick offline from `components/agent/` via `clojure -M:test`.
