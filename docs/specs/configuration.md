# Spec: Configuration

*Area code `CFG`. Covers the agent runtime's configuration: the config
schema, the precedence chain, the persisted-EDN shape at
`~/.brainyard/config.edn`, and directory resolution. Note this is the
*agent* config chain (`agent/core/config.clj`); the separate
`components/config` Aero+Integrant component is a different thing and is
out of scope here.*

Status legend and contract-ID conventions: see [README](README.md).

> **Naming caution.** The config schema var is `config-schema` in
> `agent/core/config.clj` and is a **flat map of `key → {:type …
> :default|:default-fn …}`** — *not* a Malli schema. The Malli `domain`
> schema in `agent/common/schema.clj` is for DSPy signatures and is
> unrelated to runtime configuration. Don't conflate them.

---

## 1. Schema

| ID | Contract | Status | Source |
|---|---|---|---|
| CR-CFG-01 | The set of valid config keys MUST be defined by `config-schema`, a flat map of `key → {:type … :default│:default-fn …}`; `config-keys` MUST be `(set (keys config-schema))`. | Implemented | `agent/core/config.clj` (`config-schema`, `config-keys`) |
| CR-CFG-02 | `:clj-backend` MUST default to `:sandbox`. | Implemented | `config.clj` |
| CR-CFG-03 | `:acp-backend` MUST default to `:stub`. | Implemented (stub by design) | `config.clj` |

CR-CFG-03 records that the ACP backend defaults to a stub — the real ACP
agent is soft-coupled via `requiring-resolve`. This is intentional, not a
gap; it's flagged so the stub default isn't read as a defect.

---

## 2. Precedence chain

| ID | Contract | Status | Source |
|---|---|---|---|
| CR-CFG-04 | `get-config`/`set-config!` MUST resolve a key by precedence (lowest→highest): schema default/`default-fn` < global config (`.brainyard/config.edn` `[:agent :config]`) < session config (`(:config @!session)`) < per-agent override (`(:config @st-memory-init)`). | Implemented | `config.clj` (`get-config`, `set-config!`) |
| CR-CFG-05 | Resolution MUST honor a boolean `false` value (via `contains?`, not truthiness) so an explicit `false` overrides a `true` default. | Implemented | `config.clj` |

The per-agent override layer (`st-memory-init :config`) is how
per-instance settings like `set-allowed-dirs!` land without touching
`config.edn` — a contract relied on by the `:nrepl` backend's per-agent
grants ([reasoning](reasoning.md) CR-RSN-14).

---

## 3. Persisted EDN shape

| ID | Contract | Status | Source |
|---|---|---|---|
| CR-CFG-06 | Persisted config MUST live under `[:agent :config <key> <val>]` in `config.edn`. | Implemented | `config.clj` |
| CR-CFG-07 | User-facing `[:permissions :allowed-dirs]` and `[:permissions :mode]` MUST bridge to the flat keys `:allowed-dirs` / `:permission-mode`. | Implemented | `config.clj` (`bridge-permissions-section`) |
| CR-CFG-08 | A legacy `[:agent :max-iterations]` shape MUST be migrated to the current shape on read. | Implemented | `config.clj` (`migrate-legacy-edn-shape`) |

---

## 4. Directory resolution

| ID | Contract | Status | Source |
|---|---|---|---|
| CR-CFG-09 | Directory resolution MUST consider `user.home` (user-dir), `user.dir` (working-dir), `BY_PROJECT_DIR` and a git-root walk (project-dir). The three scopes are orthogonal and each report truth — no env var conflates them. | Implemented | `config.clj` (`resolve-dirs`) |
| CR-CFG-10 | `.brainyard` subdirectories MUST honor `subdir-scope-policy`: memory/sessions/logs are user-only, tasks/charts are project-only, config.edn/skills are both. | Implemented | `config.clj` (`brainyard-subdir`, `subdir-scope-policy`) |

The scope policy is load-bearing for several other specs: sessions are
user-scoped ([tui](tui.md) CR-TUI-12), tasks are project-scoped
([task-manager](task-manager.md) CR-TASK-08).

---

## Gaps & candidate TODOs (this spec)

None. The configuration code carries no `TODO`/`FIXME`/stub markers and
every contract is Implemented. The only item worth noting is the one
intentional default recorded above: `:acp-backend :stub` (CR-CFG-03). It
is not a gap. (Context compaction across all scales is governed by the
single `:enable-context-budget` knob, default true — see
[memory-and-context](memory-and-context.md).)
