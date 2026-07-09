# Multi-method `/login` (auth-method registry)

Status: **slices 1–3 shipped**. Slices 4–5 are follow-ups.

## Problem

`/login` historically conflated **provider** with **auth *method***: it knew only
"anthropic = OAuth", and even that path dead-ended (the Anthropic
subscription-OAuth flow in `clj-llm/core/oauth.clj` produces tokens that, as of
Jan 2026, Anthropic rejects for third-party API calls — usable only by Claude
Code / claude.ai). Meanwhile three *real* auth mechanisms already existed in the
tree, unconnected to `/login`:

- **API key** — `.env` / real env, read by `clj-llm` (`ANTHROPIC_API_KEY`,
  `OPENAI_API_KEY`, …); Bedrock via the AWS credential chain (`AWS_PROFILE`).
- **OAuth (real, multi-flow)** — `components/clj-oauth` (`login!` supports
  device / paste / loopback / DCR), store at `~/.brainyard/oauth/<user>/<account>.json`.
  Wired to MCP servers via `:auth {:type :oauth …}` + `/mcp <srv> auth`.
- **Claude subscription via the `claude` CLI** — the ACP `:claude-code` backend
  (`npx @zed-industries/claude-code-acp`) reads Claude Code's *own* credential
  store, so a logged-in `claude` CLI authenticates it with no API key. Worked,
  but was invisible to `/login`.

## Model: auth method per target

A **login target** is a provider/backend the user authenticates. Each declares
exactly one method:

| method | credential lives in | `/login <t>` does | `/logout <t>` does |
|---|---|---|---|
| `:api-key` | env var (`.env` / real env) | instruct: set `$ENV` | instruct: unset `$ENV` |
| `:oauth` | clj-oauth store (**we own it**) | run clj-oauth `login!` (slice 2) | `oauth/logout!` |
| `:cli-delegate` | **another CLI's** store | instruct: run `!<cli> /login` | instruct: `!<cli> /logout` |

v1 is **detect-and-instruct**: `status` reports sign-in state; `instructions`
returns the exact command to run. `by` never drives a PTY or holds a
subscription token.

### v1 targets (`components/agent/.../common/auth.clj`)

- `:anthropic` — `:api-key`, `ANTHROPIC_API_KEY` (renamed from the draft's
  `anthropic-key`; replaces the old restricted-OAuth no-op).
- `:openai` — `:api-key`, `OPENAI_API_KEY`.
- `:bedrock` — `:api-key`, probed via `clj-llm/aws-credentials-detected?`.
- `:claude` — `:cli-delegate`, `claude /login`; the subscription path the ACP
  `:claude-code` backend already consumes.

## The `claude` credential probe (empirically verified, macOS + Linux)

Claude Code does **not** write `~/.claude/.credentials.json` on macOS. Verified
on this box:

- **macOS** → login **Keychain**, service `"Claude Code-credentials"`, account =
  OS login. Probe: `security find-generic-password -s "Claude Code-credentials"`
  (exit 0 ⇒ signed in). Never pass `-w` — existence is the whole signal.
- **Linux/other** → `~/.claude/.credentials.json` (0600) file-exists check.

`~/.claude.json` holds only feature flags + per-project token *stats* — **not**
the credential. Three states: `:signed-in` / `:not-signed-in` /
`:cli-missing` (`claude` not on PATH). Falls back to `:not-signed-in` if
`security` throws/absent, so `/login claude` still prints the instruction.

## Wiring

- `auth.clj`: `auth-targets`, `auth-find-target`, `auth-status` (multimethod on
  `:method`), `auth-instructions` (multimethod), `auth-logout!`,
  `claude-logged-in?`. Pure data out; the TUI does the coloring.
- `agent/interface.clj`: `export-symbols` the five public fns.
- `agent-tui/commands.clj`: `handle-login-command` / `handle-logout-command`
  route through `agent/auth-*`; the dead "anthropic OAuth is restricted" branch
  is removed. Dispatch at `"/login"` / `"/logout"` unchanged.

## Slice 2 (shipped) — MCP OAuth servers as `/login` targets

The draft imagined "standalone OAuth *providers*" (Notion etc. via `/login
<name>`). Investigation showed **every real OAuth flow in the tree is an MCP
server** (e.g. the builtin `github2` — GitHub's hosted remote MCP over native
`:http` + OAuth); there is **no non-MCP OAuth LLM provider** (Anthropic
subscription OAuth is API-restricted and became the `:claude` cli-delegate
target). Fabricating a standalone provider would be dishonest, so slice 2
instead makes `/login` the single sign-in entry point by **folding MCP OAuth
servers in as targets**:

- `provider-status-overview` appends a row per `agent/mcp-oauth-status` entry
  (`{:server :authenticated?}`), best-effort (empty if MCP isn't initialized).
- `/login <server>` for an OAuth MCP server runs the **real** device/auth-code
  flow via `reauth-mcp-async!` (same plumbing as `/mcp <server> auth`, which
  still works).
- `/logout <server>` clears its stored token via `agent/mcp-oauth-logout!`.
- Static providers still win name resolution; MCP names resolve case-insensitively.

The `:oauth` method in `auth.clj` stays as a tested mechanism/seam for a future
genuine standalone OAuth provider, but no static target uses it today.

## Slice 3 (shipped) — ACP backend auth visibility

The subscription path (`:claude-code` backend consuming the `claude` CLI
credential) worked but was invisible. Now `acp/descriptor` carries a live
`:auth` field (`:signed-in | :not-signed-in`) for CLI-delegated backends,
computed via `backend-auth-status` → `auth/claude-logged-in?`. It flows through
both `acp$detail` (whole descriptor) and `acp$list` rows automatically. Kept in
the agent layer — `acp-client` stays a soft dep and the probe isn't pushed down
into it (so `backend-available?` is unchanged; deferred).

## Follow-ups (not shipped)

4. **Interactive `claude /login`** — PTY takeover instead of detect-and-instruct.
5. **`.env` writes** — optionally append the key for the user instead of just
   instructing; auto-surface the full `clj-llm` provider catalog behind
   `/login --all`. Optionally add `:logged-in?` to `acp-client`
   `backend-available?` if a lower-layer consumer ever needs it.
