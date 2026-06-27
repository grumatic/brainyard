# Changelog

All notable changes to Brainyard's public distribution are documented here. Versions follow [Semantic Versioning](https://semver.org/).

## [Unreleased]

### Added

- **Context-graph memory — a typed entity/relationship graph + semantic vector index over long-term memory, with a self-contained in-binary embedder.** Opt-in via `BY_ENABLE_GRAPH_MEMORY` (default off, fully non-regressing — an empty graph leaves recall byte-identical to the existing FTS/BM25/RRF behavior), Brainyard's long-term store (`components/memory`) gains a **context graph** that adds two recall signals fused into the same Reciprocal Rank Fusion: **semantic similarity** — a `sqlite-vec` vector index over L3 facts that catches matches sharing no keywords (e.g. "seatbelt sandbox" ≈ "OS write-containment policy") — and **relational, multi-hop** recall — typed, bi-temporally-versioned edges between entities (config keys, components, files, people, concepts) traversed 1–2 hops and surfaced in a new **"## Related"** briefing section (e.g. `BY_SANDBOX_INTEROP —configures→ clj-sandbox —part_of→ code-eval`). The graph **populates itself**: an async LLM extraction sidecar hung off the capture pipeline distills entities + relationships from episodes (`BY_GRAPH_EXTRACT_MODEL`, any chat model, e.g. `bedrock/amazon.nova-lite-v1:0`), and graph-community detection + summaries supersede the old heuristic L2→L3 time-bucket reducer — clustering by relatedness rather than by time window. Embeddings can be **fully self-contained**: the default `BY_GRAPH_EMBED_MODEL=static` is a pure-JVM **Model2Vec** static embedder (`potion-base-8M`, 256-dim) bundled into the binary — no embedding server, no native libraries, no native-image risk — so semantic recall works out of the box and your memory data never leaves the machine; power users can instead point it at any OpenAI-compatible `/embeddings` endpoint (`ollama/nomic-embed-text` (local), `openai/text-embedding-3-small`). Because vectors from different embedders aren't comparable, **changing the embedding model is detected by fingerprint and *pauses* semantic recall** — falling back to keyword search rather than ever serving a silently-wrong ranking — with a one-line startup banner and a `memory$status :vec-index` flag, until you rebuild the index with the new **`memory$reembed`** command. The bundled `sqlite-vec` extension and Model2Vec model are sha-pinned and fetched into the native image by `bb sqlite-vec:fetch` / `bb model2vec:fetch`. Design in [`docs/design/context-graph-memory-design.md`](docs/design/context-graph-memory-design.md); steered by the `BY_ENABLE_GRAPH_MEMORY` / `BY_GRAPH_EMBED_MODEL` / `BY_GRAPH_EXTRACT_MODEL` / `BY_GRAPH_EMBED_DIMS` env vars (annotated in `.env.example`).
- **`trajectory$search` — on-demand recall of past tool calls and code evals.** With routine tool/eval results no longer in semantic memory (see the capture change below), agents can now pull their *operational* history back when they need it: `trajectory$search` queries the session trajectory — every past tool call (`name`/`args`/`result`) and code evaluation (`code`/`result`/`output`/`error`), recorded per turn — filtered by `:query` (substring across all of those), `:tool`, `:kind` (`tool`|`code`), `:turn-id`, and `:limit` (most-recent first). This is the clean split: `memory$recall` returns durable *knowledge* (conversation + facts), `trajectory$search` returns *what you did and what it output* (e.g. a prior `bash`/file result or an eval value) — so operational detail stays fully available without flooding recall. Read-only and session-scoped.
- **Colon-command introspections are recorded into the model's context as Live Artifacts.** When you run a colon command at the TUI prompt (`:list-tools`, `:get-tool-info`, `:task$list`, `:time$now`, …) to inspect tools or state, the interaction — the command line plus a one-line digest of its result (`→` on success, `✗` on error) — is now captured as an inline Live Artifact tagged `:origin :console` and badged "🔎 you inspected". Because it rides the existing Live-Artifacts plumbing, it survives the turn reset and surfaces in the `## Live Artifacts` section on subsequent turns, so the agent becomes aware of what you've been poking at (a signal of intent) without you having to restate it. The entries are ordinary artifacts: they show up in `artifact$list` with `origin "console"`, are LLM-removable via `artifact$remove`, and are evicted oldest-first by the `:drop-live-artifacts` budget reducer (they're unpinned). A rolling window keeps only the newest few and dedupes a consecutive identical invocation. Three flat config keys steer it: `:enable-console-activity` (default `true`), `:console-activity-max-entries` (default `10`), and `:console-activity-result-chars` (default `200`, the per-entry result-digest cap).
- **`by memory` — deterministic, non-LLM maintenance on the long-term store, plus `ask --session` to thread memory across one-shot questions.** A new top-level subcommand operates directly on `~/.brainyard/memory/<user-id>.db` outside any agent session: `by memory consolidate -u <user> [-s <session>] [--reducer heuristic|community]` promotes L2 episodes into L3 semantic facts (the deterministic trigger the agent-driven essence path can't be — it's async/LLM-mediated); `by memory graph-build -u <user> [-s <session>]` **synchronously** extracts entities/relationships from L2 into the context graph (the async capture-time extractor is dropped by the 1s shutdown drain on a one-shot `ask`, so a blocking build is required for scripting/backfill — needs the graph tier configured); and `by memory stats -u <user>` reports L1/L2/L3 counts. Separately, `by ask` gains **`--session`/`-s`** to pin the session id (default: a fresh ephemeral `ask-<millis>`) — reuse the same id across one-shot asks so session-scoped L1/L2 recall links them. Together these make the memory pipeline scriptable end to end.
- **Memory test harnesses (`bb test:memory`, `bb test:memory:l3`, `bb test:memory:l3-graph`).** Three real-LLM, self-cleaning harnesses under `scripts/` exercise the full capture → persist → recall loop against a throwaway user/db: session-scoped L1/L2 recall across separate processes; cross-session L3 recall via the heuristic reducer; and cross-session L3 recall via the graph-community tier (extract → community summaries → recall).

### Changed

- **Heuristic L2→L3 consolidation now actually fires for ordinary conversation.** The heuristic reducer bucketed episodes by their tag set minus `event:`/`kind:` — but kept the auto-extracted per-message `topic:` tags, which are unique per turn (`topic:teal`, `topic:photon`, …). So every turn landed in its own bucket, the batch-size threshold was never met, and consolidation silently produced nothing for any varied real conversation. The bucket key now also excludes `topic:` tags, reducing to the stable structural tags (primarily `role:`) so a run of conversation in one time window batches into a single per-(role, window) digest. (Topic/entity-cohesive consolidation remains the graph-community tier's job; this is the lightweight digest tier.)
- **Memory capture is leaner and no longer recalls your own question.** The L2 capture pipeline previously wrote one episode per lifecycle event — the bare `:agent.ask/pre` question, the `:agent.ask/post` answer, and *every* tool call and code eval — so a single turn exploded into many fine-grained, often-duplicated episodes, and (worst) recall run over the current question matched the just-captured question as its own top hit. Capture is reworked around what's actually worth remembering: the conversation is now **one Q&A episode written at `:agent.ask/post`** (question + answer together), carrying a content-addressable `entry_id` so re-asking the same thing **upserts** instead of duplicating; the bare pre-question is no longer captured at all (it fired *before* the turn's recall, which is why it self-matched). Tool-use and code-eval are captured **only on error** — a successful tool/eval is operational noise for semantic recall and already lives in full in the trajectory log and the `memory_audit` trail — so failures stay recallable ("last time this errored with …") without the per-event flood. Net effect: far fewer, higher-signal L2 episodes, no cross-turn question self-recall, and no duplicate episodes for repeated questions or tool calls. (`:agent.ask/pre` is no longer a subscribed capture hook.)
- **Config precedence: environment variables now win.** `get-config` resolves through `ENV > per-agent override > session > .brainyard/config.edn (merged over static defaults) > schema default` — previously env vars were the *lowest*-precedence fallback (read inside each key's `:default-fn`), so a persisted `config.edn` value silently beat them. Env reads now live in a dedicated per-key `:env-fn` evaluated first; a set variable overrides every persisted/in-memory layer (a sentinel distinguishes a real `false`/`0` from "unset"). Affects the env-bound keys `:tavily-api-key` (`TAVILY_API_KEY`), `:nrepl-enabled?` (`BY_NREPL_ENABLED`), `:nrepl-port` (`BY_NREPL_PORT`), `:ask-channel-enabled?` (`BY_ASK_CHANNEL`), `:ask-timeout-ms` (`BY_ASK_TIMEOUT_MS`), and `:sandbox-interop` (`BY_SANDBOX_INTEROP`). Each resolution is mulog-tracked once per (key, source) via `::config-resolved` so the winning layer is observable. **Behavior change:** e.g. `BY_NREPL_ENABLED=true` now enables the nREPL even when `config.edn` sets `:nrepl-enabled? false`.
- **The on-demand usage-guide tool and sandbox binding are renamed `usage` → `usage$guide`.** Both the tool exposed on the tool-calls channel and the matching SCI sandbox binding now follow the `$`-suffixed tool-naming convention (like `task$detail`, `log$turns`, `artifact$add`) and no longer collide by name with the `/usage` meta-command (token/cost summary). The sandbox binding is no longer a hand-written special case — `usage$guide` is now bound through the generic auto-tool-binding path like any other tool: its `:topic` input is a keyword, so agents call `(usage$guide :topic :memory)` in a Clojure fence (`(usage$guide)` lists topics). A known topic returns the guide as a plain string so it renders verbatim (real newlines) in the iteration record rather than a pr-str'd map with escaped newlines; listing/unknown-topic calls return a small map. The registered tool id is `:usage$guide`. All system-prompt pointers, usage guides, and the just-in-time nudge were updated to the new name and call shape.

## [v0.3.3] — 2026-06-22

### Added

- **OAuth for MCP HTTP servers — device flow, native loopback, dynamic client registration, and 401-challenge discovery.** Brainyard can now authenticate to OAuth-protected MCP servers (e.g. Notion's hosted `https://mcp.notion.com/mcp`) end to end, with no hand-copied tokens. A new brick — **`components/clj-oauth`** — implements the OAuth 2.0 Device Authorization Grant (RFC 8628), Authorization-Code + PKCE (RFC 7636) over a **native loopback redirect** (RFC 8252, a throwaway `127.0.0.1` HTTP listener captures the `?code` callback so the browser round-trip completes without an out-of-band paste), **Dynamic Client Registration** (RFC 7591), and authorization-server discovery (OIDC `/.well-known/openid-configuration`, falling back to `/.well-known/oauth-authorization-server`, RFC 8414). When an HTTP MCP server answers `initialize` with `401`, the `WWW-Authenticate` challenge is parsed for its protected-resource-metadata pointer (RFC 9728) and the issuer is discovered automatically; the flow is then chosen per server (`:device` / `:loopback` / `:paste`, default `:auto` based on what the server advertises). Tokens persist in a hardened store under `~/.brainyard/oauth` (mode 0600, atomic writes, keyed by `(account, user)`) with provider-agnostic refresh, and an optional OS-keychain backend (`security` on macOS, `secret-tool` on Linux — both subprocess-based, no JNI, native-image safe). Authenticate a server with **`/mcp <server> auth`**; the verification box (device user-code + URL, or a browser link) renders inline, with a scannable QR code via `qrencode` when it's on `PATH`. Two new flat config keys steer it: `:oauth-flow` (`auto` | `device` | `loopback` | `paste`) and `:oauth-token-store` (`auto` | `file` | `keychain`), plus `:oauth-qr?`. A ready-to-try **`notion2`** seed ships in the MCP config (native `:http`, `:flow :auto`) so the whole chain — 401 → discover → register → browser → loopback callback → token exchange → connected — can be exercised against a real provider.
- **Long-running MCP operations run off the command thread behind a live spinner.** `/mcp <server> start | stop | auth | status` block on the network — the `initialize` handshake, token exchange, or the user authorizing in a browser — and previously froze the TUI until they settled. Each now runs on a background thread behind a one-line braille spinner (`⠋ Connecting <server>…`) so the input bar stays responsive; when the op settles the spinner is disposed and replaced by a single outcome line (`Started <server>` / `Auth failed for <server>: …`), and the hardware cursor is returned to the input prompt. `status` only detaches when the server is connected (the disconnected detail is a local read and stays synchronous). The spinner respects the autocomplete overlay — paints defer while a menu owns the screen and resume once it's dismissed.
- **Usage guides are delivered just-in-time and exposed as a tool.** The per-tool usage guidance that helps the model call tools correctly is now pushed exactly when it's needed — surfaced on a malformed first tool call (not only on later ones) — and is reachable on demand via a `usage` tool backed by an open registry, so an agent (or its sandbox binding) can pull the guide for a specific tool/topic instead of carrying every guide in the system prompt. A new `:scope` tier keeps only system-level guides riding the system prompt, and the CoAct large-results playbook is slimmed, deferring the detail to `(usage :truncation)`.

### Changed

- **`/login` and `/logout` are scoped to auth providers; MCP-server auth lives under `/mcp`.** The two command surfaces no longer overlap. `/login` now reports auth-provider status (e.g. Anthropic) and signs in to a provider; `/logout <provider>` signs out. MCP servers are managed entirely by `/mcp <server> start | stop | auth | status` — passing an MCP server name to `/login` or `/logout` is rejected with a pointer to the right `/mcp` action. (Anthropic subscription OAuth remains restricted to Claude Code / claude.ai and can't be completed here; other providers authenticate via API keys in your `.env`.)

### Fixed

- **`clj-http-native` honors `:content-type :form` and explicit string content types.** The client previously only special-cased `:content-type :json`; any other value — including `:form` and raw strings like `"application/x-www-form-urlencoded"` — was dropped, so the header never reached the server. OAuth token endpoints require form-encoded bodies, and Notion's rejected the exchange with `invalid_request: "Content-Type must be application/x-www-form-urlencoded"`. The content-type sugar now maps `:json` → `application/json`, `:form` → `application/x-www-form-urlencoded`, and passes any other string through verbatim.
- **`clj-http-native` no longer drops the last response header.** `headers-from-response` accumulated headers with a transient `assoc!` inside a `doseq`, discarding the return value — so the final header in iteration order was silently lost. This only bit responses with enough headers to expose it: Notion's `401` carried the `WWW-Authenticate` challenge as the dropped header, breaking OAuth discovery. Rebuilt as a `reduce` over the transient that keeps each `assoc!` result.
- **Boot-time OAuth device prompts are no longer swallowed by the alt-screen switch.** MCP servers that connect at startup could fire their device/verification prompt before the TUI had switched to its alternate screen buffer, so the code + URL scrolled away unseen. Boot-time prompts are now deferred behind an emit gate and flushed once the live screen is up, so the verification box always lands where you can read it.

### Added

- **`by run --resume-latest` reattaches to the newest session non-interactively.** A new flag — and the matching `BY_RESUME_LATEST` env var — resumes the most-recent persisted session (newest by last-attached-at, then started-at), falling back to a fresh session when there are none. It fills the gap between bare `--resume` (which opens the interactive stdin picker) and `--resume <id>` (which exits 1 if the id is absent): neither suits an automated relaunch. The flag is forwarded through the `--web`/`--sandbox` child launch, so a session whose process was relaunched — e.g. a hosted workspace whose container was recreated — lands back exactly where the user left off instead of in a blank session.
- **The agent's suggested follow-up persists on the input bar and shares the idle prompt with rotating help tips.** At the end of a turn the agent can emit a `next-user-prompt` — a one-line follow-up you might send next — offered on the empty input bar as a right-arrow-acceptable ghost suggestion (`↳ … (→ to use)`). It previously vanished the instant you accepted it and, while live, fully shadowed the rotating static help tips so they never showed. The suggestion now stays live until the next turn actually starts (cleared at `:agent.ask/pre`), so you can clear the input and re-summon it; accept is gated to an empty buffer so a second right-arrow can't double-append it. A new idle-tip ticker alternates the placeholder every 15s between the suggestion and a rotating static tip (`… (→ for suggestion)`) while you sit at an empty prompt — self-guarded to empty/active/no-popover, so it never repaints during a turn or while you're typing. The static tip set also grew from 4 to 16 entries covering the full `/help` command surface.

### Removed

- **The runtime `/session resume` subcommand is removed.** It was never actually implemented — the handler only listed persisted sessions or printed a `would switch to …` stub, never rehydrating any state. (The real restore path is the CLI `--resume` flag / `--resume-latest`, which are unchanged.) Typing `/session resume` now falls through to the standard `/session` usage line, and the subcommand is gone from `/help` and the `/session` autocomplete submenu. The other persisted-session subcommands (`list`, `show`, `tree`, `fork`, `label`) are unaffected.

### Fixed

- **Switching provider with `/model` no longer sends the previous provider's API key.** Hot-swapping the active LM with `/model <name>` resolved the new provider's key from a hardcoded partial env-var map that listed only OpenAI/Anthropic/Google/DeepSeek/Groq; any provider outside it (free-llm, Mistral, Together, Fireworks, OpenRouter, Azure) missed the lookup and fell back to the *current* LM's key — so switching, say, OpenAI → free-llm sent the OpenAI key as the free-llm Bearer token and the request failed with HTTP 401. The switch now reuses the current key only when the provider is unchanged, and otherwise leaves it unset so `create-lm` resolves the key from the provider catalog's own env var — matching how a fresh `by ask -p <provider>` already behaved.
- **Quitting no longer orphans background-task subprocesses.** A process started as a detached background task (e.g. `npm run dev`, which forks `sh → npm → node → next-server`) survived as a live OS process after the TUI exited — still holding its port — whether you quit via `/quit`, EOF, or double-Ctrl-C. The kill path (`cancel-task` → `:on-cancel` → `destroy-process-tree!`) and the manager's `shutdown` already existed, and the interface wrapped them as `task-shutdown`, but nothing ever called it on exit. `stop!` deliberately skipped task teardown on a false premise — that the executor's daemon worker threads let the JVM clean up — but daemon-ness only stops the JVM from *waiting* on threads; it doesn't kill `ProcessBuilder`-spawned OS processes. `stop!` now calls `task-shutdown` (covers `/quit` + EOF), and the JVM shutdown hook calls it too (covers double-Ctrl-C / SIGTERM / crash, which bypass `stop!`); the two are idempotent, so each running detached task is cancelled and its whole subprocess tree destroyed exactly once.
- **The answer box no longer breaks on tab-indented content (e.g. `git status`).** Long-form `git status` indents untracked files with a literal TAB; the answer-box layout measured width with `display-width` (which counts a TAB as one column) while the terminal expanded the TAB to a tab stop, so the right border was shoved out and the box edge went ragged on every tab-indented line. TABs in the answer are now expanded to spaces (tab stop 4) before layout, so the measured and rendered widths match and the box stays rectangular.
- **Resuming a session whose agent type is no longer registered no longer crashes.** A persisted session records its agent type; if that type isn't registered this run — a deleted user-defined agent (`user$agent$…` / a tool/hook authored then removed), a renamed/removed built-in, or a plugin agent not loaded — `create-tui-agent!` previously got an `{:error-message …}` map back from `invoke-tool` and `swap!`-ed its `nil` `:!session`, throwing an opaque `NullPointerException` that aborted the whole resume. The TUI now detects an unregistered agent type and falls back to the default `coact-agent` (with a one-line notice), so the session's conversation history still opens; a residual setup failure is surfaced as a clear error instead of a nil-`swap!` NPE.

## [v0.3.1] — 2026-06-11

### Added

- **User-defined agents (`meta-agent`).** The agent can now author its own persistent specialists at runtime: `meta-agent$create` takes a `:name`, a one-line `:description`, an `:instruction` block, and a `:tool-context` block, and registers a **CoAct-derived agent** — `user$agent$<name>` — that is discoverable, routable, and callable as a sub-agent on the next turn. The authored agent inherits the full CoAct loop and tool palette, so it binds **no tools**; it is shaped entirely by its two prose blocks. Persistence is a directory per agent (`<project>/.brainyard/agents/user$agent/<name>/` — `agent.edn` + `instruction.md` + `tool-context.md`), so the prose stays editable Markdown. Unlike user tools/hooks there is no body to eval and no sandbox to rehydrate — an authored agent grants no capability `coact-agent` lacks; it is a persona over the same guarded palette. A `meta-agent$create` / `meta-agent$validate` / `meta-agent$list` / `meta-agent$read` / `meta-agent$delete` command family (mirroring `tool-agent$*` / `hook-agent$*`) manages them, fronted by the new `meta-agent` specialist; `main-agent` gains an `:agent-lifecycle` routing shape that delegates "make me an agent that …" asks to it. Design in [`docs/design/meta-agent-design.md`](docs/design/meta-agent-design.md).

### Changed

- **User-defined tool/hook command families renamed to `tool-agent$*` / `hook-agent$*`.** The `tools$*` command family (`create`, `validate`, `list`, `read`, `delete`) is now `tool-agent$*`, and the `hooks$*` family (`events`, `create`, `validate`, `list`, `read`, `delete`) is now `hook-agent$*` — each command is namespaced under the specialist agent that owns it (`tool-agent`, `hook-agent`), matching the `<owner>$<verb>` convention. Straight rename with no legacy alias: update any prompt, script, or skill that invoked the old names (e.g. `tools$create` → `tool-agent$create`, `hooks$events` → `hook-agent$events`). Persisted definitions under `.brainyard/tools/` and `.brainyard/hooks/` are unaffected.
- **User-defined tool ids are now namespaced `user$tool$<name>`.** A tool authored via `tool-agent$create` is registered, auto-bound, and dispatched as `user$tool$<name>` (was `user$<name>`), and the body-to-body composition symbol matches — a peer is called as `(user$tool$other {…})`. This aligns the tool namespace with the sibling user-defined asset namespaces (`user$agent$<name>`, planned `user$skill$<name>`) so the `user$` space stays partitioned by asset type. Straight rename with no legacy alias: any authored tool body that composed a peer by its old `user$<name>` symbol must update to `user$tool$<name>`. Persisted sources under `.brainyard/tools/<name>.edn` are unaffected — the id is derived from the name at load time, so existing tools pick up the new prefix automatically on next boot.
- **`BRAINYARD_*` environment variables renamed to `BY_*`.** Every remaining `BRAINYARD_`-prefixed env var now follows the `BY_*` convention already used by `BY_USER_ID`, `BY_WORKING_DIR`, `BY_PROJECT_DIR`, `BY_WEB_*`, and `BY_SANDBOX_*`. Renamed: `BRAINYARD_NREPL_ENABLED` → `BY_NREPL_ENABLED`, `BRAINYARD_NREPL_PORT` → `BY_NREPL_PORT`, `BRAINYARD_NREPL_GRANT` → `BY_NREPL_GRANT`, `BRAINYARD_SESSION_ID` → `BY_SESSION_ID`, `BRAINYARD_VERSION` → `BY_VERSION`, `BRAINYARD_RUN_INTEGRATION` → `BY_RUN_INTEGRATION`, `BRAINYARD_TUTORIAL_MODE` → `BY_TUTORIAL_MODE`, `BRAINYARD_DEV_REPO` → `BY_DEV_REPO`, `BRAINYARD_ITER_LIVE_BLOCKS` → `BY_ITER_LIVE_BLOCKS`, `BRAINYARD_DEFAULT_PROVIDER` → `BY_DEFAULT_PROVIDER`. Straight rename with no legacy alias — update any script, `.env`, or shell that set an old name (notably the live-nREPL opt-in is now `BY_NREPL_ENABLED=true`).
- **`memory$remember` / `memory$recall` reject an unknown `:kind` with an actionable error.** Previously both tools accepted any `:kind` string: `remember` mapped it straight to storage (an invalid kind like `user-identity` silently polluted the L3 taxonomy or surfaced only the opaque `"Write to l3 returned no entry"`, so the LLM looped), and `recall` used it as a filter that silently matched nothing — indistinguishable from "memory is empty". Both now validate an explicit `:kind` against the per-layer set (l1: `system-context|user-context|episode|fact|observation`; l2: `conversation|action|observation|thought|evaluation|error`; l3: `summary|fact|preference|entity|concept|relationship`) and return `{:error "Invalid kind \"…\" for layer l3. Valid kinds: …"}` before acting, so the model retries with a valid kind. `recall` additionally errors when a `:kind` filter is given without a `:layer` (cross-layer recall ignores it). The valid kinds per layer are now enumerated in each `:kind` field description. Omitting `:kind` still applies the per-layer default (remember) or no filter (recall).
- **`by run`: bare `--resume` now opens the interactive session picker.** Previously bare `--resume`/`-r` resumed the *latest* persisted session and a separate `--select-resume` flag showed the picker. The two are unified: bare `--resume` now shows the newest-first picker (the old `--select-resume` behavior; fresh if no sessions), and **`--select-resume` is removed**. `--resume <id>` is unchanged (resume that session, error+exit 1 if absent).
- **A turn always ends with a usable answer.** Two cases that could previously leave a turn with no answer now resolve cleanly. (1) When the iteration loop hits its cap without the model ever producing a final answer, the agent now backfills a best-effort answer from the trajectory (the latest tool result, else the latest reasoning, else a deterministic recap) instead of exiting with nothing — the loop's `:repeat` returns success on exhaustion, so the old catastrophic-failure fallback never fired for this common "ran out of iterations" case. (2) The no-action loop guard (the model reasoned but populated no channel several iterations running) now surfaces that same best-effort progress rather than a stale or empty placeholder.
- **Model-failure recovery is unified and shows progress.** The CoAct loop's two recovery paths (the LLM-call guard and the router's repair fallback) are folded into one action that classifies the failure and applies a per-kind retry budget: a **malformed** model response (DSPy/JSON parse error) re-prompts across iterations before aborting; an **empty** response (the model returned nothing usable) is retried inline with exponential backoff; a deliberate **no-action** turn is nudged before the loop guard stops it. Each retry now emits a muted progress line in the TUI (`⟳ Model returned an empty response — retrying (2/5)…`) instead of a silent pause. The budgets are configurable: `:max-retries-on-llm-empty-result` (**renamed** from `:empty-result-max-retries`, default raised to **5**), plus new `:max-retries-on-llm-malformed-output` (**3**) and `:max-retries-on-llm-no-action` (**3**). Anyone who set the old `:empty-result-max-retries` key must rename it — there is no legacy alias.

### Fixed

- **The abort reason survives a fatal LLM error.** When a turn aborts on an unrecoverable model error (auth / rate-limit / quota, or repeated malformed output), `:terminated-by` now reports `:llm-error` instead of being overwritten to `:answer-channel` by the terminal answer path. The recovery action stamps the abort answer before the router runs, and the router's answer handler was hard-setting the reason; it now preserves a pre-set one, and per-turn state is cleared at turn start so a prior turn's reason can't leak forward.

## [v0.3.0] — 2026-06-08

### Added

- **Live artifacts — reference docs in context + agent-curated material.** The agent's prompt now carries a `## Live Artifacts` section fed by two streams. **System reference docs** named by the new `:reference-artifact-paths` config (default `CLAUDE.md` / `AGENTS.md`; relative names resolve against the project/working dir, absolute and `~` paths as-is, e.g. `~/.claude/CLAUDE.md`) are seeded fresh every turn, pinned, and never removable by the LLM — so project/user guidance rides along automatically. **Dynamic artifacts** are material the agent pins itself via a new `artifact$add` (an absolute file `:path`, reloaded fresh each turn — e.g. a skill's `SKILL.md` — or inline `:content`) / `artifact$list` / `artifact$remove` / `artifact$pin` command family; these persist across turns within a session. File-backed artifacts render as a 400-char preview plus a `(read-file {:path …})` pointer (the file reloads each turn, so the full bytes needn't ride the prompt), while inline notes render up to `:live-artifact-max-chars` (default 4000). `BRAINYARD.md` / `CLAUDE.md` / `AGENTS.md` that are **linked to one source** (symlink or hardlink, at project or user scope) are de-duped by file identity, and a reference doc linked to `BRAINYARD.md` (already loaded as instructions) is dropped rather than emitted twice. Design notes in [`docs/design/artifacts.md`](docs/design/artifacts.md).

### Changed

- **Pinned context is never dropped wholesale under budget pressure.** The token-budget reducer gains a `:keep-floor?` section policy: when a section's compaction strategy can no longer shrink it (e.g. only pinned/system live artifacts remain), its irreducible floor is kept and the section is retired from compaction instead of being dropped as a loop-breaking last resort. Live-artifact eviction is pin-aware — only unpinned, agent-added artifacts are dropped under pressure; pinned and system reference docs always survive. Design notes in [`docs/design/compaction.md`](docs/design/compaction.md).
- **Memory capture is on by default.** The layered-memory capture pipeline (the S0/S1/S2 hooks that auto-populate the L2 episodic chronicle from lifecycle events) now runs for every agent unless explicitly disabled. The `:enable-memory-capture` config default flipped to `true`, and the capture-start gate now reads it through `config/get-config` — honoring schema defaults plus per-agent/session/global overrides — instead of a direct map lookup that bypassed the default. Previously capture only ran where a `defagent` opted in via `:config-extra`, so the schema default was dead config. Set `:enable-memory-capture false` to turn it off. (Per-turn LLM essence extraction, `:enable-memory-essence`, remains **off** by default.)
- **Verbatim code blocks ride forward across turns.** A four-backtick verbatim block now carries its full body on the iteration record's `:code` (previously dropped to an empty string once written to a scratch path), so the model keeps sight of what it generated on later turns; token economy is left to the iteration-record compaction layer rather than the writer. The explore-agent uses this to author large or fence-heavy dossier bodies without hand-escaping YAML + markdown into a Clojure string — write the body verbatim, read it back, then prepend frontmatter via `explore$write`.

## [v0.2.7] — 2026-06-07

### Added

- **User-defined hooks (`hook-agent`).** The agent can now author its own runtime hooks: `hooks$create` takes Clojure source and registers it as a persistent observer on a pre-defined Brainyard event (e.g. `:agent.tool-use/post`, `:agent.iteration/post`). Mirroring user-defined tools, the handler *source* is persisted to `.brainyard/hooks/<id>.edn` and re-evaled in a dedicated sandbox to rehydrate on fire and at session boot; the body composes the tool palette by direct symbol — `(write-file {…})`, `(bash {…})` — to enact its side effect. A `hooks$events` / `hooks$create` / `hooks$validate` / `hooks$list` / `hooks$read` / `hooks$delete` command family (mirroring `tools$*`) manages them, fronted by the new `hook-agent`. v1 is observer-only (gated events that block/modify/replace are reserved) and requires an explicit `:match` scope; safety rests on fail-open handler errors, a re-entrancy guard, and the `enable-user-hooks` kill-switch.
- **Hook-agent tutorial.** A new recorded walkthrough (`21-hook-authoring`) takes the `hook-agent` through the full hook lifecycle across three turns — discover the hookable events, author a persistent `audit-bash` hook from a plain-English request, then read it back to confirm it is registered and active.
- **Contain a session in a sandbox (`by --sandbox`).** A new launcher re-execs `by run` under macOS [`sandbox-exec`](https://keith.github.io/xcode-man-pages/sandbox-exec.1.html) (seatbelt) with a generated **write-containment** profile: reads, network and subprocess exec stay allowed (an agent's job is running tools and calling LLMs), but filesystem *writes* are confined to `~/.brainyard`, the project/cwd subtree, `$TMPDIR`/`/tmp`, `~/Library/Caches` and `/dev` — so an agent (or a tool it runs) can't clobber `~/.ssh`, `~/.aws/credentials`, `/etc`, or unrelated repos. The sandboxed session runs in the **same terminal** (unlike `--web`'s PTY-over-network). Add writable roots with `--sandbox-allow-write` (repeatable/comma-separated), cut off the network with `--sandbox-no-network`, or supply your own seatbelt profile with `--sandbox-profile`; all `--sandbox*` flags have `BY_SANDBOX_*` env equivalents. macOS-only (warns and runs unsandboxed elsewhere) and mutually exclusive with `--web` in v1; `sandbox-exec` is probed at runtime and is not a build dependency. New brick `components/os-sandbox`; full guide in [`docs/sandboxing.md`](docs/sandboxing.md).
- **Set the working directory (`by --working-dir`/`-C`).** You can now point a session at a working directory other than the launch cwd, so file-touching tools (`bash`, `read-file`, `write-file`, `grep`) and the functional agents resolve relative paths — and land their `.brainyard/` artifacts — where you intend. Precedence: `--working-dir`/`-C` flag > `BY_WORKING_DIR` env > the process cwd. The flag is strict (a non-existent / non-directory path exits 1); a bad `BY_WORKING_DIR` env value falls back to the cwd. The project root (where `.brainyard/` lives) re-derives by walking up to the nearest `.git` from the effective working dir, unless **`BY_PROJECT_DIR`** explicitly overrides it. No real `chdir` happens — a JVM can't change its process cwd at runtime — the value is threaded through config and applied to spawned subprocesses and path resolution. The `--web`/`--sandbox` launchers forward `-C` into their re-exec'd child. Documented in `.env.example` and `CLAUDE.md`.

### Changed

- **Liveness signal for long-running subagent (and tool) tasks.** When a tool call — including a subagent invoked as a tool — runs past its fast-eval window and is detached into a background task, it now emits a periodic liveness heartbeat into its task output (`[<tool> ] running… elapsed Ns`, every 10s). Previously a `:tool` job, unlike `:bash`/sandbox/nREPL, bound no streamable `*out*`, so its `task$detail` line count stayed frozen at the initial `Invoking…` line for the whole run — making a healthy multi-minute subagent look wedged and inviting a premature `task$cancel`. `task$detail` also gains `:elapsed-ms` and `:last-output-age-ms` (from the output-log mtime) so a polling agent can tell "alive but quiet" from "stuck", and the detach marker now explicitly warns against cancelling on a quiet output window. The heartbeat rides the existing detach drain pipe and self-terminates with the task; the task's `:result` is unchanged (heartbeats live only on the streaming surface).
- **Per-iteration progress trace for detached subagent tasks.** Building on the heartbeat, a detached subagent now streams a compact, meaningful trace into its task output — `[iter N/MAX] thinking…` at each iteration start and `  tool <name> → ok` / `error: …` as each tool call completes — so a polling agent watches real progress, not just a liveness tick. It works without touching the agent loop: a single built-in subscriber on the existing `:agent.iteration/pre` and `:agent.tool-use/post` events appends to the running task's output, keyed off `proto/*current-task*`. Because a subagent-as-tool runs its own loop on the future thread that carries that binding (conveyed into its `pmap` tool dispatch), its events route to the adopted task while the parent's own iterations — firing on the unbound BT thread — stay out. Observer-only and fail-open (registered under `:source :task-progress`); no-op outside a task context.
- **Structured progress snapshot in `task$detail`.** Beyond the streamed lines, `task$detail` now returns a `:progress` map for a running subagent task — `{:iteration :tools-completed :last-tool :last-tool-result :last-reasoning}` (`:last-reasoning` = the agent's latest "Think:" text) — so a polling agent can judge how far along a subagent is at a glance instead of parsing the log tail. Maintained by the same `:task-progress` hooks (iteration/tool boundaries plus the latest per-iteration observation) and evicted when the task goes terminal (`:result` then carries the final answer). Absent for non-subagent tasks.
- **Cascading cancel for subagent tasks.** Cancelling a background task that wraps a subagent (via `task$cancel`) now cancels the whole subagent chain (task → subagent → its subagents → …), not just the top future. The dispatched subagent's instance-id is captured at adoption and the task's `on-cancel` calls `runtime/cancel-run` on it, which sets the cooperative `:cancelled?` flag and aborts its in-flight LLM stream; every descendant sees it through the existing upward parent-chain `cancelled?` walk and aborts at its next BT checkpoint. Previously only the top future was interrupted, leaving descendant subagents orphaned on their own pmap threads, still burning tokens. Cancellation is cooperative — a descendant mid-LLM-call stops once that call returns. (Ctrl-C of the foreground agent already cascaded this way; this brings targeted `task$cancel` to parity.)
- **Steer a paused agent by typing (resume with a request).** While an agent's iteration loop is paused (Ctrl-\\), typing a message + Enter now resumes the *running* loop carrying that message as a mid-run steering request, instead of queueing a separate next turn. The note is folded into the loop's **active task** (`st-memory :question`, the primary objective both the react and coact loops render each iteration) as a framed directive — `[MID-RUN STEERING — you were paused and the user resumed the iteration loop with this added/updated instruction; treat it as authoritative and adjust your remaining work now] …` — so the LLM actually changes course rather than treating it as background history. Plumbed via `resume-run`'s new optional `note` arg → `[:runtime :resume-note]` → consumed at the next BT checkpoint by `apply-resume-note!` (fires whether or not the loop parked, so a pause+resume inside one LLM call still lands) → appended to `:question`. The paused banner now reads `[paused] Ctrl-\\ resume · Ctrl-C cancel · or type a message + Enter to resume with it`. (Ctrl-\\ still toggles a plain resume; Ctrl-C still cancels.)
- **Unified, generalized user-feedback mechanism.** The TUI now binds a single interactive-input primitive to each session (`:user-feedback-fn`) that dispatches on a request `:kind` — `select` (pick from 2–6 options, the historical behavior and the default), `text` (free-form line), or `confirm` (single-key choice — file-access permission uses yes/no/always/never, the last two remembered per session). Each kind renders through whichever backend is available — raw in-stream live-block, non-raw stdin, or (optionally, when feasible) a tmux popup — so the popup is just one optional backend, not a special case. File-access **permission** is now a thin adapter over this primitive (a `:confirm` request): it keeps only path normalization and its per-session approved-dir cache and delegates all prompting, collapsing the duplicate pending-state channel, lock, and raw-byte interceptor branch into one. The `get-user-feedback` tool gains a `kind` argument (`select`/`text`/`confirm`); existing positional `select` calls — `(get-user-feedback "q" ["a" "b"])` — are unchanged. A new `enable-tmux-popup` config key (default `true`) gates the popup backend — set it `false` to force every prompt to the in-stream live-block.
- **Reviewable persistence for user-defined tools & hooks.** Each definition is now saved as a human-readable pair — a pretty-printed metadata `.edn` plus a `.clj` sidecar holding the body as **verbatim** Clojure source (`#()`, regex, quotes preserved exactly; no escaping; opens with editor highlighting) — instead of a single dense one-line `.edn` with the body as an escaped string. A shared `def-store` ns handles both `tools$*` and `hooks$*`; reads still go through the safe `clojure.edn` reader and transparently fall back to the legacy single-file format, which migrates to the pair on its next overwrite.
- **`BRAINYARD_PROJECT_DIR` renamed to `BY_PROJECT_DIR`.** The project-root override env var now follows the `BY_*` convention shared by `BY_USER_ID`, `BY_WORKING_DIR`, `BY_WEB_*`, and `BY_SANDBOX_*`. Straight rename with no legacy alias — update any script or `.env` that set the old name.

## [v0.2.6] — 2026-06-04

### Added

- **Share a session over the web (`by --web`).** A new launcher wraps `by run` in [ttyd](https://github.com/tsl0922/ttyd) so a TUI session is reachable in the browser — and shared by all connected clients. Two modes: `--web` serves a fresh session (Tier 1), and `--web-tmux` runs the TUI in a private detached tmux session that several clients attach to and drive live (Tier 2). With `--web-tmux` the launching terminal stays a **dashboard** so the connection info (URL + credentials) remains visible to copy and share; drive locally from another terminal with the printed `tmux attach` command, or just open the URL. Auth is always required (password auto-generated if unset), binding defaults to localhost, and origin-checking is always on; `ttyd`/`tmux` are probed at runtime and are not build dependencies. All `--web*` flags have `BY_WEB_*` env equivalents. New brick `components/web-share`; full guide in [`docs/web-sharing.md`](docs/web-sharing.md).

## [v0.2.5] — 2026-06-03

### Added

- **Tool-agent tutorial.** A new recorded walkthrough (`18-creating-tools`) takes the `tool-agent` through the full user-defined-tool lifecycle across three turns — discover existing tools, author a persistent `count-words` tool from a plain-English request, then read it back and run it on a real file. The `docs/tutorials/` README catalog was also backfilled to list every walkthrough (09–18).

### Changed

- **Auto-background detach default raised 30s → 120s.** A code block (clojure / bash / python / javascript) now runs synchronously in the foreground for up to 120s before detaching into a background task, up from 30s — set by the `:auto-background-timeout-ms` config default. Longer evals stay inline (with live output) before backgrounding; the value is still per-agent configurable.

## [v0.2.4] — 2026-06-03

### Added

- **User-defined tools.** The agent can now author its own tools at runtime: `tools$create` takes Clojure source and turns it into a first-class, persistent, discoverable tool (auto-bound as `user$<name>`, flowing through the same Malli coercion, hook/permission, and depth guards as built-ins). Because SCI closures aren't EDN-serializable, the tool *source* is persisted to `.brainyard/tools/<name>.edn` and re-evaled in a dedicated sandbox to rehydrate on call and at session boot. A uniform `tools$create` / `tools$list` / `tools$read` / `tools$delete` command family (mirroring `skills$*`) manages them.

### Changed

- **Relicensed from Apache-2.0 to MIT.** The project now ships under the [MIT License](LICENSE), copyright Grumatic, Inc. The `LICENSE` file, all source-file SPDX headers, and the `bb license:*` tooling were updated accordingly; the Apache-specific `NOTICE` file was removed, and a `CONTRIBUTING.md` was added (inbound = outbound MIT). A `bb license:migrate` task performs the one-time header rewrite. Note: MIT carries no express patent grant, unlike Apache-2.0.

### Fixed

- **Installer JVM fallback.** `bin/install.sh` now falls back to the JVM uberjar on platforms with no published native binary (Linux, Intel macOS), installing a `by` launcher that runs `java -jar` (requires JDK 21+). A stable `by.jar` alias is staged alongside the versioned asset.
- **Polylith workspace check.** Resolved `check` errors 101 (route `bootstrap-driver` through the `agent` interface) and 107 (declare `clj-http-native` explicitly in the project deps). `bb poly check` is now clean.

## [v0.2.3] — 2026-06-01

### Added

- **Startup user identity.** `by` now resolves a real per-user identity at startup instead of the fixed `tui-user`/`cli-user` placeholders. Precedence: `--user-id`/`-u` flag > `BY_USER_ID` env > the `user.name` system property (OS login) > `by-user`. Sessions and memory (`~/.brainyard/memory/<user-id>.db`) key on it, so your history is scoped to you out of the box. `BY_USER_ID` is documented in `.env.example` and `CLAUDE.md`.
- **Gmail & Google Calendar MCP servers.** Added `gmail` and `google-calendar` to the built-in MCP server set. Both bridge Google's official hosted MCP endpoints (`gmailmcp.googleapis.com` / `calendarmcp.googleapis.com`) via `mcp-remote` (mirroring the notion/linear pattern). They read a pre-registered OAuth client from `GCP_OAUTH_CLIENT_ID`/`GCP_OAUTH_CLIENT_SECRET` (documented in `.env.example`), ship `:enabled false`, and connect on first `/mcp gmail start` (browser consent).

### Fixed

- **`/mcp` server list rendering.** Server names no longer show a literal `:bold` prefix (e.g. `:boldnotion`) — a keyword was passed to `ansi/style` where it expects an ANSI escape-code string.

### Notes

- The user-id change means default runs no longer read the old `tui-user`/`cli-user` memory stores; a default run now scopes to your OS login (`<user.name>.db`). Existing placeholder stores are left untouched on disk.

## [v0.2.2] — 2026-06-01

**Opus is now the default model.** The out-of-box default LM changes from Sonnet to Opus.

### Changed

- **Default LM → Opus.** With no `-m`/config override, `by` now defaults to `claude-code:opus` (most capable Claude via the CLI, no API key). The `-p anthropic` provider default is the latest `claude-opus-4-7`, and `by config --auto` now selects `claude-code:opus`. Override anytime with `-m sonnet`/`-m haiku` or `LM_MODEL`.

### Fixed

- **`:dev` alias.** Trimmed `deps.edn`'s `:dev` alias to the mirrored brick set (17 components + 2 bases); it had listed ~28 components/bases not present in this repo, breaking `bb repl` / `bb repl:test` with "Local lib not found".

### Removed

- Dropped `bb` tasks targeting non-mirrored upstream projects (`repl:fra/ea/ra/awa`, `shadow:fra/awa`) — they could never run here.

## [v0.2.1] — 2026-06-01

Documentation and tooling only — no binary or behavior changes since v0.2.0. The `by` binary is functionally identical to v0.2.0 (only the reported `--version` differs).

### Added

- **Hosted tutorials.** The recorded walkthroughs are now published to GitHub Pages at [grumatic.github.io/brainyard](https://grumatic.github.io/brainyard/) and linked from the README.
- **`bb tutorial:publish`.** Regenerates the self-contained tutorials page and force-pushes it to the `gh-pages` branch (`scripts/asciinema/publish-pages.sh`, via git plumbing — no working-tree churn).
- **`.env.example`.** Credential template covering the key-based LLM providers (Anthropic, OpenAI, Google, Groq, Mistral, DeepSeek, …) and AWS/Bedrock (`AWS_PROFILE`/`AWS_REGION`). README install flow now points users to copy it.
- **Upstream `scripts/` migrated.** The asciinema tutorial pipeline (`scripts/asciinema/*`), the license-header tool, and TUI test helpers — so `bb tutorial:output`/`record`/`verify` run locally.
- **Upstream `development/` migrated.** The Polylith `:dev` project source (`development/src`) and the `dev.repl-test` helper behind `bb repl:test`.

### Changed

- **`docs/usage.md` rewritten** against the actual v0.2.0 CLI: corrected session flags (`-r/--resume`/`--select-resume` replace the removed `-s/--session-id`), split `run`-only flags from `ask`, documented `by config` as the `config.edn` bootstrap pipeline, expanded the provider list, and fixed the environment-variable table.

### Removed

- Dropped the `docs/reference/CCA.md` and `docs/reference/OPTRA-CODER.md` reference docs and the `docs/*.pptx` slide decks (purged from history to keep the repo lean).

## [v0.2.0] — 2026-06-01

**Brainyard is now open source.** This release publishes the full Polylith workspace — sources, build config, and docs — directly in this repository. Earlier releases (v0.1.x) shipped binaries only, built from a private upstream via a sync wrapper; that machinery has been retired and development now happens here. The codebase is licensed under Apache-2.0, copyright Grumatic, Inc.

### Highlights since v0.1.1

- **Interactive tutorials.** A full asciinema-based tutorial suite under [`docs/tutorials/`](docs/tutorials/) — 17 recorded scenarios with a vendored player, turn-by-turn walkthroughs, and a CI golden-frame drift gate.
- **MCP improvements.** `config.edn`-backed MCP servers, runtime skill/MCP registration, a `:lazy` connect flag, per-server connect knobs, a startup status banner, and non-blocking per-server connect with background skill scanning.
- **TUI polish.** Reworked session-resume UX (ordered by update time), full prompt echo with wrapping, configurable collapsed/expanded line limits, and assorted rendering fixes.
- **Safer agent writes.** Shared write-guards (secret scanning + size caps) across memory, workflow, plan/exec/eval, and bootstrap dossiers; secret scanning in `config$apply`.
- **Security-gated nREPL** (`clj-nrepl` component): confirm/grant flows, audit, and drift detection for REPL eval.
- **Performance.** Cached MCP tool listing, faster `explore$find` (INDEX.md first), and fewer redundant routing-log reads.

### Artifacts

- `by-0.2.0.jar` — Clojure uberjar. Runs on JDK 21+.
- `by-0.2.0-macos-arm64` — native (GraalVM) binary.
- `by-wrapper.sh` — wrapper shell script (sources `.env`, execs the native binary).
- `SHA256SUMS` — checksums.
- `BUILD-INFO.txt` — version, platform, build timestamp, and source commit.

### Known gaps

- **Linux** and **macOS amd64** binaries are not in v0.2.0 — use the uberjar on those platforms.
- **Windows** is deferred.

---

## [v0.1.1] — 2026-05-20

Tooling release. No user-visible behavior changes vs. v0.1.0 — same 18 agents, same 6 subcommands, same provider lineup. Recommended for everyone on v0.1.0 (the upgrade is a drop-in `curl | bash` re-run).

### What changed

- **Build version baked at compile time.** Upstream's `app-version` now reads `resources/build-version.edn`, which `bb version:ata` stamps from `git describe --tags --always --dirty` before AOT compile. The binary's reported version always reflects the actual source tag, not a hand-edited string literal that could drift.
- **`bb native:ata` honors the `.sdkmanrc` pin** when probing for `native-image` on machines with multiple GraalVM installs. Fewer "wrong-toolchain-built" surprises on multi-Graal dev boxes.
- **Wrapper-repo invariant.** This repo's `bin/release-stage.sh` now refuses to stage a release when the wrapper's `git describe` carries `-dirty`, `-N-gabc123` (commits past tag), or resolves to `dev` (no git). Prevents stamping a misleading version into a public artifact.

### Artifacts

- `by-0.1.1.jar` — Clojure uberjar (50 MB). Runs on JDK 21+.
- `by-0.1.1-macos-arm64` — native binary (138 MB). Cold start ~1.5 s.
- `by-wrapper.sh` — wrapper shell script (unchanged from v0.1.0).
- `SHA256SUMS` — checksums.
- `BUILD-INFO.txt` — upstream SHA, branch, sync/build timestamps.

### Upstream provenance

Built from upstream commit `184d6ecf0c87148041f82a4bdcf9e018597f3562` (branch `main`, synced 2026-05-20). Full provenance in `release/BUILD-INFO.txt`.

### Known gaps (unchanged from v0.1.0)

- **Linux binaries** not in v0.1.1. Use the uberjar on Linux for now.
- **macOS amd64** not in v0.1.1. Use the uberjar on Intel Macs.
- **Windows** deferred (see `docs/deploy-design.md` §7.2).

---

## [v0.1.0] — 2026-05-20

First public release of the `by` binary (the Brainyard agent TUI).

This release is a **manual M1 release** per the deployment design — built locally on macOS arm64, with limited platform coverage. Linux and macOS amd64 binaries land at M3 once the CI matrix is in place.

### Artifacts

- `by-0.1.0.jar` — Clojure uberjar (50 MB). Runs on JDK 21+.
- `by-0.1.0-macos-arm64` — native binary (138 MB). Cold start ~1.5 s.
- `by-wrapper.sh` — wrapper shell script. Sources `.env` and execs the native binary.
- `SHA256SUMS` — checksums covering all of the above.

### Install

See [`docs/install.md`](docs/install.md) for the full install paths. Quick start:

```bash
# Native binary (macOS arm64 only in v0.1.0)
curl -fsSL https://raw.githubusercontent.com/grumatic/brainyard/main/bin/install.sh | bash

# Uberjar (any platform with JDK 21+)
curl -LO https://github.com/grumatic/brainyard/releases/download/v0.1.0/by-0.1.0.jar
java -jar by-0.1.0.jar --help
```

### Upstream provenance

Built from the private upstream `~/Projects/MyDev/brainyard` at commit `ebd66caef58e2319c51bd37a248286e2f4d5fe0b` (branch `main`, synced 2026-05-20). Provenance lives in `release/BUILD-INFO.txt` (uploaded with each release) — synced sources are not committed to this repo.

### Build environment (this release)

- macOS arm64
- GraalVM Oracle 25.0.3+9.1 (build 25.0.3+9-LTS) — matches upstream `.sdkmanrc` (`java=25.0.3-graal`).
- Babashka 1.3.190
- Clojure CLI 1.12.0.1530

### Known gaps

- **Linux binaries** are not in v0.1.0. Use the uberjar on Linux for now.
- **macOS amd64** is not in v0.1.0. Use the uberjar on Intel Macs.
- **Windows** is deferred (see `docs/deploy-design.md` §7.2).
- **GraalVM 21.0.9** surfaces a `sci.impl.multimethods__init` clinit failure that GraalVM 25 does not. Upstream `native-image.properties` may need an `--initialize-at-run-time=sci.impl.multimethods__init` carve-out before CI on older GraalVM is reliable.

### Closure

18 Polylith bricks mirrored: 2 bases (`agent-tui`, `acp-stub-agent`) and 16 components. Full list in `bin/.brick-set`.
