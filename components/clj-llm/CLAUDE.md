# CLAUDE.md — clj-llm

Directory-scoped guidance, loaded when working under `components/clj-llm/`.
**Keep only what does not rot**: this file used to hand-list the providers, the
source files and the test namespaces, and all three drifted (13-of-17,
8-of-19, 3-of-15). Anything that changes when a file or provider is added
belongs in the source, not here.

A pure Clojure DSPy-style framework for structured LLM interactions — a
Polylith component (`ai.brainyard.clj-llm`) sitting **below** the agent.

## Commands

```bash
bb test:component clj-llm    # every *_test.clj under test/, auto-discovered
bb test:ns schema-test       # one namespace (or substring) in a plain JVM
bb repl:test <ns>            # run a namespace against a live nREPL
clj -M:dev                   # REPL, from the monorepo root
```

Prefer these to a hand-listed `clj -M:test -e "(require …)"`: they discover
namespaces, so a new test file cannot fall out of "run everything".

## Data Flow

```
defsignature → compile-signature → {:name :instructions :inputs :outputs
                                    :input-keys :input-order :output-keys
                                    :output-json-schema}
                          ↓
              build-messages (prompt.clj)
                          ↓
              chat-completion (llm.clj) → provider dispatch
                          ↓
     parse-json-response (llm.clj) → validate-output (schema.clj)
                          ↓
              {:outputs {…}} (+ :reasoning for chain-of-thought)
```

`signature` / `prompt` / `llm` / `schema` / `predict` / `chain_of_thought` are
the spine. The rest of `core/` is provider adapters, the model catalog and
usage accounting — read the directory, not a list here.

## Key Design Decisions

- **Malli-centric.** JSON Schema is *derived* (`malli->json-schema`), never
  hand-written. `schema_registry.clj` holds a global registry that `defschemas`
  populates at load time.
- **Two message formats.** Dispatch keys off `:message-format` (`:openai` or
  `:anthropic`); most providers are OpenAI-compatible.
- **No protocols, no records.** Pure functions and maps — this is what keeps
  the component native-image-safe.
- **`execute-dspy-operation` exists TWICE, deliberately.** `interface.clj` has
  the multimethod (`:predict` / `:chain-of-thought`); behavior-tree's
  `core/dspy_action.clj` defines its own same-named one that resolves
  signature/LM from BT context and then calls `clj-llm/predict`. Different
  vars — when tracing a BT `dspy-action`, check which you are reading.
- **The provider/model roster is not documented here.** `core/providers.clj` is
  the authority for providers and their env vars; the catalog is the authority
  for models. Root `CLAUDE.md` covers the catalog-refresh design.

## Downstream Dependents

- **agent** — `create-lm`, `chat-completion`, `create-embedding`,
  `defsignature`, `defschemas`, `parse-malli-field`, `parse-lm-str`,
  `estimate-tokens`, via a **direct** `:require [… :as clj-llm]`
  (e.g. `agent/core/tool.clj`), not `requiring-resolve`.
- **behavior-tree** — `core/dspy_action.clj`, per the note above.
