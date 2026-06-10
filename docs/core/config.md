# Configuration

A typed schema, a single read/write API, and a layered precedence chain. The
config subsystem keeps loop control, memory, safety, LM selection, analytics,
and directory resolution under one consistent surface — so callers never reach
into `@!state` or `@!session` directly to fetch a knob.

Primary files:

- `components/agent/src/ai/brainyard/agent/core/config.clj` — schema, read/write API, directory resolution.
- `components/agent/src/ai/brainyard/agent/common/config.clj` — `config$apply` / `config$read` user-facing tools and persisted-EDN validators.
- `components/agent/src/ai/brainyard/agent/common/config_agent.clj` — the LLM-facing config-agent that drives those tools.

---

## What lives where

An agent carries five distinct state slots that hold configuration-like data.
Each has a different lifetime, mutability, and reader API.

| Slot | Path | Lifetime | Mutability | Reader |
|---|---|---|---|---|
| Agent identity | `@!state :meta` | set at creation | immutable | `(proto/agent-name agent)`, `(proto/agent-description agent)` |
| Non-schema extras | `@!state :config` | set at creation | rarely written | direct keyword access (e.g. `(:slack (:config @!state))`) |
| Per-agent overrides | `(:config @(:!state agent :st-memory-init))` | per agent | `set-config!` (3-arity) | `config/get-config` |
| Session config | `(:config @(:!session agent))` | per agent-session | `agent/set-session-config` | `config/get-config` |
| Global config | `!global-config` (atom) | process | `set-config!` (2-arity) | `config/get-config` |
| Schema defaults | `config-schema` (def) | static | code-only | lazy via `:default-fn` |

The first two are **agent metadata** (identity + ad-hoc extras like `:slack
{:bot-token …}` or `:system-commands`). The last four are the **schema-driven
configuration chain** — what `get-config` actually walks.

---

## The precedence chain

`get-config` resolves a single key through four layers, lowest → highest:

```
   schema default (`:default` or lazy `:default-fn`)
     < !global-config (`.brainyard/config.edn`)
       < session-config (`(:config @!session)`)
         < per-agent override (`(:config @st-memory-init)`)
```

Boolean `false` overrides are honored — the implementation uses `contains?`
rather than `or` so an explicit `false` shadows a higher-precedence `true`.

```clojure
(config/get-config :max-iterations)         ;; global view; skips agent/session
(config/get-config agent :max-iterations)   ;; full chain, agent-aware
(config/get-config-snapshot agent)          ;; merged map of the full chain
```

The 2-arity accepts an `Agent` record, an `st-memory` map (with optional
`:agent` key), or `nil` — falling back to `proto/*current-agent*` when no
explicit agent is supplied. Most call sites use the dynvar form.

---

## Writes

```clojure
(config/set-config! :max-iterations 50)          ;; global + persisted
(config/set-config! agent :max-iterations 50)    ;; ↑ plus per-agent override
```

`set-config!` is the single mutating entry point. It asserts the key is in
`config-keys`, updates `!global-config`, and writes `[:agent :config <k>]` to
`.brainyard/config.edn` (`:auto` scope). With an agent argument it additionally
swaps the per-agent override slot so the running agent observes the new value
on its next read.

**Ephemeral exception** — `set-allowed-dirs!` deliberately bypasses disk
persistence and only updates the per-agent override layer. The TUI uses this
to widen the allow-list for the current session without polluting the
user-edited `.brainyard/config.edn`.

---

## The schema

`config-schema` is the single registry of every configurable knob. Each entry
declares a type (used by `coerce-value`) plus either a static `:default` or a
lazy `:default-fn`. Schema keys flow through `setup-agent`'s split: any key
present in `(:config-extra options)` or `options` that matches `config-keys`
is routed into the per-agent override slot at startup.

```clojure
{:max-iterations    {:type "integer" :default 100}
 :show-llm-streaming {:type "boolean" :default false}
 :eval-lm-config    {:type "string"  :default nil}
 :working-dir       {:type "string"  :default-fn #(System/getProperty "user.dir")}
 :lm-config         {:type "object"  :default-fn #(clj-llm/get-default-lm)}
 :dirs              {:type "object"  :default-fn #(resolve-dirs)}
 :allowed-dirs      {:type "array"   :default-fn #(default-allowed-dirs)}
 :permission-mode   {:type "keyword" :default :ask-each-time}
 :clj-backend       {:type "keyword" :default :sandbox}     ;; :sandbox | :nrepl (per-agent code-eval backend)
 :acp-backend       {:type "keyword" :default :stub}
 :nrepl-enabled?    {:type "boolean" :default-fn #(env BRAINYARD_NREPL_ENABLED)}
 :nrepl-port        {:type "integer" :default-fn #(env BRAINYARD_NREPL_PORT)}    ;; 0 = ephemeral
 :nrepl-grant       {:type "string"  :default-fn #(env BRAINYARD_NREPL_GRANT)}   ;; e.g. "read-only:15m" / "mutate:5m"
 ...}
```

The `:clj-backend` key selects the per-agent code-execution backend (see
[reasoning.md](reasoning.md)); it replaced the older `:default-clj-backend`
name. The `:nrepl-*` keys were promoted from raw `BRAINYARD_NREPL_*` env
vars into the schema so they can be read through `get-config` and persisted
under `[:agent :config]`, while still honouring the env var as the lazy
default.

Adding a new key is a one-liner: declare the entry, then read it via
`get-config`. Use `:default-fn` whenever the default depends on the
environment (env vars, `user.dir`, other components like
`(clj-llm/get-default-lm)`); it is invoked lazily by `get-config` only when no
override is present, which keeps load-time fast and avoids cyclic init.

**Type strings** (`coerce-value`):

- `"keyword"` — `keyword?`. Preferred for keyword-valued knobs
  (`:permission-mode`, `:acp-backend`, `:clj-backend`).
- `"string"` — accepts `string?` or `keyword?` (legacy tolerance so older
  keyword-valued knobs still round-trip; new keyword knobs should declare
  `"keyword"`).
- `"integer"`, `"number"`, `"boolean"` — direct predicate checks.
- `"array"` / `"vector"` — `sequential?`.
- `"object"` — `map?`.

Unknown declared types are accepted unconditionally (validators ignore them).

---

## Two `:config` slots, two concerns

There are *two* places in `@!state` called `:config`. Keep them straight:

- **`@!state :config`** — non-schema agent-meta extras (`:slack`,
  `:system-commands`, …). Read directly with keyword access. These are set
  once at `setup-agent` time from the caller's `:config-extra` minus the
  schema keys.
- **`(:config @(:!state agent :st-memory-init))`** — schema-shape per-agent
  overrides. Read via `config/get-config`. This is the slot `set-config!`
  writes to when given an agent argument.

`setup-agent` performs the split deterministically:

```clojure
schema-overrides  (merge (select-keys config-extra config/config-keys)
                         (select-keys options config/config-keys))
agent-meta-extras (apply dissoc config-extra config/config-keys)
```

`schema-overrides` lands in `st-memory-init :config`; `agent-meta-extras`
lands in `@!state :config`. Caller-supplied `options` win over defagent
author defaults on schema-key collisions.

---

## Persisted EDN file

`.brainyard/config.edn` follows a sectioned shape that pre-dates the flat
schema. The config-agent's allowlist documents the user-facing surface:

```edn
{:llm         {:default-provider :anthropic
               :default-model    "claude-sonnet-4-6"}
 :permissions {:mode         :ask-each-time
               :allowed-dirs ["/tmp" "/Users/me/Projects"]}
 :agent       {:default-agent  :coact-agent
               :config         {:max-iterations 100
                                :enable-context-budget true}}
 :environment {:sandbox-mode :standard}
 :mcp         {:servers {…}}
 :bootstrap   {…}
 :updated-at  "2026-05-20T…"}
```

`load-global-config!` reads the EDN at `:auto` scope and runs two
shape-normalizations before populating `!global-config`:

1. **`migrate-legacy-edn-shape`** — relocates pre-unification positions:
   `[:agent :max-iterations]` → `[:agent :config :max-iterations]`. Idempotent;
   in-memory only (disk is rewritten on the next `config$apply`).
2. **`bridge-permissions-section`** — one-way read mapping from the
   user-facing nested `[:permissions ...]` subtree onto the flat schema
   keys `:allowed-dirs` and `:permission-mode`. Wizard-edited values take
   effect at runtime via `get-config` without changing the EDN file shape.

Writes — both `set-config!` and the user-facing `config$apply` — always
target the canonical position (`[:agent :config :*]` for schema keys; the
nested `[:permissions ...]` and `[:llm ...]` paths for the legacy sections).
There is no auto-migration on write; the bridge is read-only.

---

## Sessions and the session-config layer

The middle layer of the precedence chain is `(:config @!session)`. It is
written via `agent/set-session-config` (an alias for
`session/set-session-config`) and is intended for **per-agent-session
ephemeral state** that should be visible to every agent and sub-agent
sharing that session — e.g.

```clojure
(swap! (:!session ag) agent/set-session-config :permission-fn …)
(swap! (:!session ag) agent/set-session-config :user-feedback-fn …)
(swap! (:!session ag) agent/set-session-config :dirs dirs)
```

Function-valued and other non-persistable resources (permission prompts,
feedback channels, the per-session usage tracker) live here. They are not
schema keys — the schema currently models persistable knobs only. Reads go
through `get-config` whenever a schema key happens to be set on session
config; raw function refs continue to use direct `(:config @!session)`
access.

---

## Directory resolution

`config.clj` is also the authority on directory layout. Three logical dirs
plus the `.brainyard/` config directories per scope:

| Concept | Resolver | Notes |
|---|---|---|
| Working dir | `resolve-working-dir` / `:working-dir` schema | Always `user.dir` (the JVM's actual cwd). Not user-overridable — by design, the three scopes (user/project/working) are orthogonal and each report truth. |
| Project dir | `resolve-project-dir` / `project-dir` | `BY_PROJECT_DIR` env → git root walking up from working-dir → working-dir itself. Anchors `.brainyard/` artifacts. |
| User dir | (inline in `resolve-dirs`) | `(System/getProperty "user.home")`. No env override. |
| Allowed dirs | `allowed-dirs` / `:allowed-dirs` schema | Allow-list for filesystem-touching tools. Defaults to `/tmp` + project-dir + `~/.brainyard`. |
| Project `.brainyard/` | `(:project-config-dir (resolve-dirs))` | Per-repo artifacts (plans, BRAINYARD.md, project config.edn). |
| User `.brainyard/` | `(:user-config-dir (resolve-dirs))` | Cross-project user state (~/.brainyard). |

`resolve-dirs` is the canonical entry point — it returns the `{:user-dir
:project-dir :working-dir :user-config-dir :project-config-dir}` bundle and
is wired as the lazy default for the `:dirs` schema key.

For path-validating tools (`bash`, `task$run`, `read-file`, `write-file`,
`grep`), `resolve-agent-dirs` composes the canonical triple:

```clojure
{:base-dir          "/abs/path/to/cwd"
 :canonical-allowed [".../base-dir" "/tmp" "..."]
 :permission-fn     #function[...]}
```

`:canonical-allowed` is the union of `:base-dir` and `(allowed-dirs agent)`,
each `.getCanonicalPath`'d so symlinks resolve consistently.

---

## Lifecycle

```
load-global-config! (once, lazy)
  └── reads .brainyard/config.edn (:auto)
      ├── migrate-legacy-edn-shape
      ├── bridge-permissions-section
      └── (merge default-config persisted bridged) → !global-config

setup-agent
  └── splits :config-extra + options:
      ├── schema-shape  → (:config @st-memory-init)
      └── non-schema    → @!state :config
  └── builds @!state :meta from instance-id + _deftool$description + agent-tools

get-config agent k
  └── per-agent (st-memory-init :config)
      └── session-config (@!session :config)
          └── !global-config
              └── schema :default / :default-fn

set-config! agent k v
  ├── swap! !global-config assoc k v
  ├── write-persisted-key! → .brainyard/config.edn [:agent :config k]
  └── swap! st-memory-init assoc-in [:config k] v
```

---

## Design principles

1. **Single read API.** Every config read goes through `get-config`. Direct
   `(get-in @!state [:config :x])` is reserved for non-schema agent metadata.
2. **Lazy defaults.** Runtime-resolved defaults (env vars, system properties,
   resolved dirs, default LM) use `:default-fn` — invoked only when no
   override is present.
3. **The schema is the contract.** Adding or renaming a key starts with a
   schema entry. Callers can grep `config-schema` to know what is configurable.
4. **Persisted shape is user-facing.** The EDN file's sectioned layout
   (`:permissions`, `:llm`, …) is documented in the config-agent allowlist
   and edited interactively by the config wizard. Internal flattening
   happens at load time via `migrate-legacy-edn-shape` and
   `bridge-permissions-section` — never on disk.
5. **Identity is not configuration.** `@!state :meta` carries the agent's
   immutable identity (name, description, declared agent-tools). It is set
   once at creation, never written via `set-config!`, and read through the
   `proto/IAgent` accessors.

---

## See also

- [Agent overview](agent.md) — record, lifecycle, multi-agent coordination.
- [Tool system](tool.md) — `:config-extra` flows through the same per-call
  merge that `deftool` uses.
- `components/agent/src/ai/brainyard/agent/common/config_agent.clj` — the
  LLM-facing config tools (`config$read`, `config$apply`, `config$diff`,
  `config$revert`, `config$preview`).
