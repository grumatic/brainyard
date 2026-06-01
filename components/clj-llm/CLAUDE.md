# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What This Is

clj-llm is a pure Clojure DSPy-style framework for structured LLM interactions. It is a Polylith component in the brainyard monorepo (top namespace: `ai.brainyard.clj-llm`).

## Commands

### Run all tests
```bash
cd components/clj-llm
clj -M:test -e "(require 'clojure.test 'ai.brainyard.clj-llm.signature-test 'ai.brainyard.clj-llm.schema-test 'ai.brainyard.clj-llm.prompt-test) (clojure.test/run-tests 'ai.brainyard.clj-llm.signature-test 'ai.brainyard.clj-llm.schema-test 'ai.brainyard.clj-llm.prompt-test) (shutdown-agents)"
```

### Run a single test namespace
```bash
clj -M:test -e "(require 'clojure.test 'ai.brainyard.clj-llm.schema-test) (clojure.test/run-tests 'ai.brainyard.clj-llm.schema-test) (shutdown-agents)"
```

### REPL (from monorepo root)
```bash
cd /Users/you/Projects/brainyard
clj -M:dev
```

## Architecture

### Data Flow

```
defsignature → compile-signature → {name, instructions, inputs, outputs, output-json-schema}
                                          ↓
                              build-messages (prompt.clj)
                                          ↓
                              chat-completion (llm.clj) → provider dispatch
                                          ↓
                              parse-json-response → validate-output
                                          ↓
                              {:outputs {...}} or {:outputs {...} :reasoning "..."}
```

### Core Modules

- **signature.clj** — `defsignature` macro compiles a signature definition (instructions + Malli input/output schemas) into a map with pre-computed JSON Schema.
- **schema.clj** — Malli↔JSON Schema conversion. Applies `additionalProperties: false` recursively and hoists `$ref` definitions to root level for OpenAI strict mode compliance.
- **schema_registry.clj** — Global mutable Malli registry. `defschemas` macro registers schemas at load time via `mr/set-default-registry!`.
- **providers.clj** — Multi-provider LM config. Auto-detects provider from model name via catalog lookup. Reads API keys from env vars. `default-lm` atom holds global config.
- **llm.clj** — HTTP client dispatching to OpenAI-compatible (`/chat/completions`) or Anthropic (`/messages`) APIs. Includes retry with exponential backoff for 429/5xx. Handles JSON schema as request param (when supported) or fallback instruction in system message.
- **prompt.clj** — Builds system/user messages from signatures. Chain-of-thought mode augments the output schema with a `reasoning` field.
- **predict.clj** / **chain_of_thought.clj** — The two operations. Both resolve LM, build messages, call LLM, parse JSON, validate output. CoT additionally separates reasoning from outputs.

### Key Design Decisions

- **Malli-centric**: All schema validation uses Malli. JSON Schema is derived, never hand-written.
- **Two message formats**: Provider dispatch is based on `:message-format` (`:openai` or `:anthropic`). Most providers use OpenAI-compatible format.
- **`execute-dspy-operation` multimethod**: Dispatches on keyword (`:predict`, `:chain-of-thought`). Used by the behavior-tree component's `dspy-action` node.
- **No protocols/records**: Pure functions and maps throughout.

### Downstream Dependents

- **agent component** — Uses `create-lm`, `chat-completion`, `create-embedding`, `defschemas`, `defsignature`. Soft dep via `requiring-resolve` for `parse-malli-field`.
- **behavior-tree component** — `dspy-action.clj` calls `execute-dspy-operation` to run predict/CoT from BT nodes.

### Supported Providers

OpenAI, Anthropic, Google, Azure, Groq, Together, Fireworks, OpenRouter, Ollama, Mistral, DeepSeek. Each has an env var for its API key (e.g., `OPENAI_API_KEY`, `ANTHROPIC_API_KEY`).

Additionally, **Anthropic Max** (`:anthropic-max` provider) supports OAuth 2.0 PKCE authentication for Max/Pro plan subscriptions — no API key required. Use `(oauth-authenticate!)` to log in via browser, then `(create-lm {:model "claude-sonnet-4-6" :provider :anthropic-max})` to create an LM config using subscription auth.
