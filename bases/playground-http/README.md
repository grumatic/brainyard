# playground-http (Phase-0)

The Brainyard Playground **control-plane base** — an http-kit server that
implements the REST + WebSocket contract the front-end
([`frontend/playground-ui`](../../frontend/playground-ui)) calls.

It runs in two modes:
- **real** (default): each session is a real `brainyard/workspace` **Docker
  container** running `by --web-tmux`; the `/tty` WebSocket is proxied to the
  container's ttyd, so the browser drives a live `by` agent.
- **fake** (`PG_FAKE=1`, or no Docker daemon): no containers; `/tty` falls back
  to an echo stub. Lets the SPA + REST/auth flow run with no Docker.

Auth is **real OIDC** when `OIDC_ISSUER` is set (bare-cookie stub otherwise) and
state is **Postgres** when `PG_DATABASE_URL` is set (in-memory otherwise). See
[`docs/design/playground-design.md`](../../docs/design/playground-design.md)
§5–6 for the target architecture.

## What's real vs stubbed

| Endpoint | Phase-0 behavior | Still stubbed → real component |
|---|---|---|
| `GET /api/me` | **OIDC identity** (or demo user in stub mode) | — (`playground-auth`, `auth.clj`) |
| `GET/POST /api/sessions`, `:id`, `/resume`, `DELETE` | **Postgres store** + **real Docker container per session**; user-scoped + ownership-checked | session-broker scheduling policy (warm pool, reaper) |
| `POST /api/sessions/:id/tty-token` | random token (unverified) | session-broker |
| `GET /api/sessions/:id/tty` | **proxied to the container's ttyd** (echo when fake) | — (`playground-proxy`, `proxy.clj`) |
| `GET /auth/login` · `/callback` · `/logout` | **OIDC authorization-code flow** (or stub cookie) | — (`playground-auth`) |
| `*` | serve the SPA (static + index.html fallback) | (same) |

## Run

Build the pieces once, then run the control plane:

```bash
bb playground:ui          # shadow-cljs release → this base's resources/public
bb uberjar:ata            # the `by` uberjar the workspace image bundles
bb playground:image       # docker build brainyard/workspace:dev
bb playground:run         # control plane on :8090 (workspaces inherit .env creds)
# open http://localhost:8090 → sign in → New workspace → live `by` TUI in the browser
```

`bb playground:run` sets `PG_WORKSPACE_ENV_FILE=<repo>/.env` so each container
gets a provider configured. Equivalent manual form:

```bash
cd bases/playground-http
PG_WORKSPACE_ENV_FILE="$PWD/../../.env" clojure -M:run [port]
```

**Fake mode (no Docker):** `PG_FAKE=1 clojure -M:run` — the terminal echoes
keystrokes instead of driving a container.

**Hot-reload dev:** `npm run watch` in `frontend/playground-ui` (SPA on :8080
proxying to :8090). The REST/auth flow works through shadow's dev proxy; the
ttyd WebSocket is most reliable same-origin (build with `bb playground:ui`).

## Environment

| Var | Meaning |
|---|---|
| `PG_FAKE=1` | disable containers; `/tty` echoes (also auto-on when no Docker) |
| `PG_DATABASE_URL` | Postgres JDBC URL for the store; in-memory when unset |
| `OIDC_ISSUER` | OIDC issuer URL → enables real auth; bare-cookie stub when unset |
| `OIDC_CLIENT_ID` / `OIDC_CLIENT_SECRET` | OIDC client credentials |
| `OIDC_REDIRECT_URI` | callback (default `http://localhost:8090/auth/callback`) |
| `VAULT_ADDR` / `VAULT_TOKEN` | enable per-user secrets from Vault (KV v2); shared `.env` otherwise |
| `VAULT_KV_MOUNT` / `VAULT_USER_PREFIX` | Vault mount (default `secret`) + path prefix (default `playground/users`) |
| `PG_WORKSPACE_IMAGE` | workspace image tag (default `brainyard/workspace:dev`) |
| `PG_WORKSPACE_ENV_FILE` | `.env` passed into each container (`by` provider creds) |
| `PG_WORKSPACE_PROVIDER` | `by` provider for the workspace agent, e.g. `openai`, `bedrock` |
| `PG_WORKSPACE_MODEL` | model id (required for `bedrock`; openai defaults to gpt-4.1-mini) |
| `PG_WORKSPACE_AGENT` | agent to launch (default `coact-agent`) |
| `PG_WORKSPACE_AWS_DIR` | host `~/.aws` to mount read-only for Bedrock creds |

**Bedrock/Claude workspace example** (creds via the mounted `~/.aws` profile +
`AWS_PROFILE`/`AWS_REGION` from `.env`):

```bash
PG_WORKSPACE_ENV_FILE="$PWD/../../.env" \
PG_WORKSPACE_PROVIDER=bedrock \
PG_WORKSPACE_MODEL=global.anthropic.claude-haiku-4-5-20251001-v1:0 \
PG_WORKSPACE_AWS_DIR="$HOME/.aws" \
clojure -M:run
```

## Files

```
server.clj     http-kit lifecycle + -main (runs sessions/init! on startup)
routes.clj     reitit-ring routes, handlers, static fallback
auth.clj       playground-auth — OIDC authorization-code flow (or stub cookie)
store.clj      playground-store — durable session records (Postgres / in-memory)
secrets.clj    playground-secrets — per-user creds (Vault KV v2 / shared .env)
sessions.clj   session broker — lifecycle policy, user-scoping, restart reconcile
workspace.clj  Docker runtime driver — start!/stop!/status, ttyd health-check
proxy.clj      authz'd WS reverse proxy: browser ⇄ container ttyd (java.net.http)
tty.clj        echo ttyd-protocol WebSocket (fake mode only)
```

The workspace image is built from
[`deploy/playground-workspace/`](../../deploy/playground-workspace).

## Notes

- `workspace.clj` and `proxy.clj` are the `workspace-runtime` and
  `playground-proxy` components living as base namespaces for Phase 0 (one
  runnable artifact). They graduate to `components/` when the `playground-server`
  Polylith project is created.
- Auth is a bare cookie — **not** a security boundary. It only exercises the
  logged-in vs logged-out UI paths. The container is the real tenant boundary
  (the design hardens it to gVisor/Firecracker in Phase 2).
