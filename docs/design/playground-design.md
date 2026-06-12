# Brainyard Playground — multi-user web-based sandboxed `by`

> Status: **design draft** · Scope: a web service where users log in and drive
> `by` from a browser terminal, each in their own dedicated, pre-provisioned,
> sandboxed environment (git, MCP servers, skills, LLM keys).

This document designs the **playground base in the repo** — the new Polylith
project/base/components that turn the single-user `--web` / `--sandbox`
launchers into a multi-tenant hosted product. It follows the system-design
framework: requirements → high-level design → deep dive → scale/reliability →
trade-offs → what to revisit.

---

## 1. The gap (why this is new code, not a flag)

Two existing launchers look adjacent but neither is multi-tenant:

| Existing | What it is | Why it doesn't cover the playground |
|---|---|---|
| `by --web` / `--web-tmux` (`components/web-share`) | Wraps one `by run` in **ttyd** → one **shared** PTY, HTTP basic-auth, binds `127.0.0.1`. | One process = one shared session co-driven by everyone with the URL. No per-user identity, no isolation between users, no provisioning. It's "share *my* session," not "give each user *their own*." |
| `by --sandbox` (`components/os-sandbox`) | Re-execs `by run` under macOS **seatbelt** with a write-containment profile. | macOS-only; deprecated `sandbox-exec`; **write-containment only** (network is all-or-nothing, no CPU/mem/PID limits, shares the host kernel). Explicitly "a blast-radius limiter, not a jail." Not a tenant boundary for hostile/unknown users. |

**Hard requirement that reframes everything:** `by` runs an agent that executes
arbitrary code and tools. In a hosted, multi-user setting every session is
**potentially hostile**, so **tenant isolation is the #1 non-functional
requirement**, and it must be a real OS/VM boundary — a Linux container or
microVM per session — not seatbelt. The existing flags become *how `by` is
launched inside* that boundary, not the boundary itself.

So the playground = a **control plane** (auth + session orchestration + proxy)
in front of a **data plane** of per-session isolated **workspaces**.

---

## 2. Requirements

**Functional**
- User **logs in** (SSO/OIDC) → stable identity that maps to `BY_USER_ID`.
- On demand, user gets a **dedicated environment**: a project folder, git,
  configured MCP servers, installed skills, and LLM provider keys already wired.
- User drives `by` from a **browser terminal**, exactly as in a local TUI
  (raw mode, alt-screen, resize, colors) — ttyd already gives us this.
- **Persistence**: a user's `~/.brainyard` (sessions sqlite, memory DB, config)
  and project files survive disconnect/suspend/resume.
- **Lifecycle**: create, suspend (idle), resume, destroy a workspace.

**Non-functional (priority order)**
1. **Isolation / security** — one tenant cannot read, write, or DoS another, nor
   exfiltrate another's keys; egress is constrained to known endpoints.
2. **Responsiveness** — sub-100 ms keystroke round-trip on the terminal; new
   session ready in seconds, not minutes.
3. **Cost** — idle workspaces reclaim compute; storage is cheap and per-user.
4. **Availability** — control-plane restart never loses a user's persisted work;
   a single workspace crash is contained to that user.

**Constraints**
- Clojure / Polylith workspace; ship `by` as the existing native binary + ttyd.
- Reuse `web-share` and `os-sandbox` bricks where they fit.
- Don't fork `by`'s session/memory model: sessions are project-scoped under
  `<project>/.brainyard/sessions/<id>/`; memory is `BY_USER_ID`-partitioned under
  `~/.brainyard/memory/<user-id>.db`. The playground supplies the *right*
  `BY_USER_ID`, `BY_WORKING_DIR`/`BY_PROJECT_DIR`, and `~/.brainyard` per user.

---

## 3. High-level design

```
                          ┌────────────────────────────────────────────┐
  Browser                 │                EDGE                          │
  playground-ui SPA       │  TLS termination · reverse proxy · WAF       │
   login·dashboard·term   │  (serves SPA static assets, same-origin)     │
  ─ HTTPS + WSS ─────────▶│                                              │
                          └───────────────┬──────────────┬───────────────┘
                                          │ /auth, /api  │ /tty (WSS)
                                ┌─────────▼─────────┐    │
                                │   CONTROL PLANE   │    │  (auth",authz'd
                                │  (playground-http)│    │   ws upgrade)
                                │                   │    │
                                │  playground-auth  │    │
                                │   OIDC, JWT/cookie│    │
                                │                   │    │
                                │  session-broker   │◀───┤ resolves user→workspace
                                │   lifecycle FSM   │    │
                                │                   │    │
                                │  workspace-runtime│    │
                                │   provision driver│    │
                                │                   │    │
                                │  playground-proxy │────┘ proxies WSS → ttyd
                                └───┬───────────┬───────────────┘
                  state (Postgres) │           │ provision / exec
                  ┌────────────────▼──┐   ┌─────▼───────────────────────────┐
                  │ users, sessions,  │   │      DATA PLANE  (per session)  │
                  │ workspaces, audit │   │  ┌───────────────────────────┐  │
                  └───────────────────┘   │  │ Workspace (container/µVM) │  │
                  ┌───────────────────┐   │  │   non-root, ro rootfs     │  │
   secrets ──────▶│  Vault / KMS      │──▶│  │   `by --web-tmux` + ttyd  │  │
   (LLM, MCP)     │  per-user scoped  │   │  │   git · MCP · skills      │  │
                  └───────────────────┘   │  │   ┌──── writable vol ───┐ │  │
                  ┌───────────────────┐   │  │   │ ~/.brainyard  +     │ │  │
   snapshots ────▶│  object store     │◀──│  │   │ /workspace project  │ │  │
   (suspend)      └───────────────────┘   │  │   └─────────────────────┘ │  │
                                          │  │   egress proxy (allowlist)│  │
                                          │  └───────────────────────────┘  │
                                          └─────────────────────────────────┘
```

**Data flow for a session**
1. Browser hits Edge → `playground-auth` runs OIDC; sets a short-lived JWT cookie
   carrying `sub` (→ `BY_USER_ID`).
2. Browser calls `POST /api/sessions`. `session-broker` looks up or allocates the
   user's workspace and asks `workspace-runtime` to start a container from the
   **workspace image**, mounting that user's persistent volume and injecting
   secrets from Vault at runtime.
3. Inside the container, `by --web-tmux` starts the agent in a detached tmux
   session served by ttyd on the **container-internal** loopback only.
4. Browser opens `WSS /api/sessions/:id/tty`. `playground-proxy` authorizes the
   JWT, resolves the session→container, and proxies the WebSocket to that
   container's ttyd. The user is now driving `by` in the browser.
5. On idle, `session-broker` suspends (snapshot + stop); on resume, restore.

---

## 4. Polylith layout — "the playground base in the repo"

New **project** `playground-server` (alias `pgs`), composed of one base and new
components, reusing existing bricks. Mirrors how `agent-tui-app` composes
`web-share` + `os-sandbox`.

```
bases/
  playground-http/                 # HTTP/WS entry point (Ring/Jetty or http-kit)
    src/ai/brainyard/playground_http/
      core.clj                     # router: /auth/*, /api/*, /tty
      server.clj                   # jetty lifecycle, ws upgrade

components/
  playground-auth/                 # OIDC login, JWT issue/verify, user upsert
    interface.clj                  # login-url, exchange-code, verify-token, ->user-id
  session-broker/                  # lifecycle state machine + scheduling
    interface.clj                  # ensure-session!, get, suspend!, resume!, destroy!
  workspace-runtime/               # pluggable provisioning driver
    interface.clj                  # start!, stop!, snapshot!, restore!, status
    src/.../docker.clj             # Phase 0/1 driver
    src/.../firecracker.clj        # Phase 2 driver (same interface)
  playground-proxy/                # authz'd WS reverse proxy → container ttyd
    interface.clj                  # proxy-tty!, resolve-upstream
  playground-secrets/              # per-user LLM/MCP cred resolution (Vault/KMS)
    interface.clj                  # env-for-user, rotate!
  playground-store/                # Postgres: users, sessions, workspaces, audit
    interface.clj

  web-share/    (reuse)            # ttyd argv/serve! — used *inside* the image
  os-sandbox/   (reuse, optional)  # seatbelt — only as in-container defense on macOS dev

frontend/
  playground-ui/                   # the user-facing SPA (ClojureScript + Replicant)
    shadow-cljs.edn                # build config; release output → http base resources
    src/ai/brainyard/playground_ui/
      core.cljs                    # app entry: state atom + render loop + reitit routes
      dispatch.cljs                # central action-data interpreter (api/nav/ws effects)
      views.cljs                   # pure state→hiccup: login · dashboard · workspace
      auth.cljs                    # OIDC redirect handling, /api/me, refresh
      terminal.cljs                # xterm.js via :replicant/on-mount + ttyd WS client
      api.cljs                     # wrappers over the control-plane REST
    resources/public/index.html    # SPA shell

projects/
  playground-server/
    deps.edn                       # composes base + components above
    # release build: shadow-cljs compiles playground-ui →
    #   bases/playground-http/resources/public/  (served same-origin by the base)
```

`workspace.edn` gains a `"playground-server"` project entry; the
control-plane bricks stay JVM (uberjar) — they don't need the native-image
constraints `agent-tui-app` lives under, which keeps the HTTP/DB stack
unrestricted.

**Separation of concerns** kept brick-clean: `session-broker` owns *policy*
(when to start/suspend/destroy), `workspace-runtime` owns *mechanism* (how to
run a container) behind one interface so Docker→Firecracker is a driver swap,
`playground-proxy` owns *only* the data-path WS bridge, `playground-secrets`
isolates credential handling so keys never pass through the broker or logs.

---

## 5. Deep dive

### 5.1 Identity → `BY_USER_ID`
OIDC `sub` (stable, opaque) is hashed to a filesystem-safe `BY_USER_ID`. Because
`by` already partitions memory by `BY_USER_ID` and sessions by project dir, the
playground gets per-user memory and session isolation **for free** by setting
those env vars when launching `by` in the container. JWT is short-lived
(~15 min) with refresh; the `/tty` WS upgrade re-verifies the token so a stale
tab can't keep a socket open past logout.

### 5.2 Session lifecycle (state machine in `session-broker`)
```
requested → provisioning → ready → active ⇄ idle → suspended → destroyed
                   │                         │           │
                   └── failed ───────────────┴── reaped ─┘
```
- **provisioning**: pull image (warm pool hides this), mount volume, inject
  secrets, start `by --web-tmux`, health-check ttyd.
- **active/idle**: idle = no WS clients + no agent activity for *N* min.
- **suspended**: tmux + container stopped, volume retained (+ optional snapshot)
  → near-zero compute cost, fast resume because `~/.brainyard` is intact.
- **destroyed**: container gone; volume retained per retention policy, then GC'd
  (parallels Brainyard's existing task-output GC: decouple removal from
  reclamation).

### 5.3 API contracts (control plane, JSON over HTTPS)
```
POST   /api/sessions            → 201 {id, status, ttyUrl}    (ensure-session!)
GET    /api/sessions/:id        → 200 {id, status, lastActiveAt}
POST   /api/sessions/:id/resume → 200 {status}
DELETE /api/sessions/:id        → 204                          (destroy!)
WSS    /api/sessions/:id/tty    → ttyd WebSocket (proxied)
GET    /api/me                  → 200 {userId, quota, workspaces}
```
All mutate endpoints require the JWT; `:id` is authorized against the owning
`userId` (no cross-tenant access by guessing IDs).

### 5.4 Storage
- **Postgres** (`playground-store`): users, sessions, workspaces, audit log —
  the authoritative control-plane state so the control plane is **stateless**
  and restart-safe.
- **Per-user persistent volume**: holds `~/.brainyard` (sessions sqlite, memory
  DB, config, skills) and `/workspace` (the project + git). This *is* the user's
  durable environment; everything else is reconstructable.
- **Object store**: suspend snapshots and volume backups.
- **Secrets manager** (Vault/KMS): LLM keys and MCP creds, **scoped per user**,
  **injected at container start as env**, never baked into the image and never
  written to Postgres or logs.

### 5.5 The workspace image (the "pre-defined environment")
A single OCI image is the unit of reproducibility: `by` binary + ttyd + tmux +
git + the standard MCP servers + the curated skills, plus a baked `.env`
template. **Keys are not in the image** — they arrive at runtime from secrets.
Updating the environment = ship a new image tag; existing volumes (user data)
carry forward unchanged.

### 5.6 Isolation — defense in depth (the core of the design)
Layered, because the agent runs untrusted code:
1. **Workspace = container/microVM per session**, non-root, **read-only root
   fs**, writable only on the mounted volume + `/tmp`. (This is the real tenant
   boundary that seatbelt cannot provide cross-tenant.)
2. **Resource quotas**: CPU/memory/PIDs/disk per session → one tenant can't
   starve others (seatbelt has none of these).
3. **Network egress allowlist** via a per-pod egress proxy: only LLM provider +
   approved MCP endpoints. This directly fixes `--sandbox`'s documented
   "network is all-or-nothing" limitation — and matters more here because keys
   live in the container.
4. **seccomp / gVisor (or Firecracker microVM)** to shrink the kernel attack
   surface for the strongest tenancy.
5. **No host control sockets** (no Docker socket), no privileged mounts.
6. **In-container `--sandbox` (optional)**: on macOS dev hosts only, as extra
   write-containment defense-in-depth — not the tenancy boundary in prod.

---

## 6. Front-end (`playground-ui`)

The control plane exposes JSON + a ttyd WebSocket, but nothing renders them. The
front-end is the **only thing the user actually touches**: log in, see your
workspaces, open a terminal, drive `by`. It is a single-page app served
**same-origin** by `playground-http`, so the JWT cookie and the `WSS /tty`
upgrade Just Work without CORS or cross-site cookie headaches.

### 6.1 Language & stack — **decision: ClojureScript + Replicant**
**ClojureScript + shadow-cljs, rendering with [Replicant](https://replicant.fun/).**
Keeps the product in one language as the rest of the repo, and — unlike a
React-based stack — pulls in **zero JS framework dependencies** (Replicant
renders hiccup straight to the DOM with its own diffing). The app is small
(login + dashboard + one terminal) and its hardest component is imperative
`xterm.js` regardless of framework, so re-frame's React machinery would mostly
be unused weight. Replicant's **actions-as-data** model — event handlers are
data dispatched to one central handler — also lines up with how `by` itself is
built (data-driven throughout).

The shape we own ourselves (Replicant is a renderer, not a framework):
- a single app-state **atom**; render is a pure `state → hiccup` function,
  re-rendered on atom change.
- a central **dispatch** fn interpreting action-data vectors
  (`[[:assoc-in path v] [:api/post url body] [:nav :workspace id]]`) — this is
  where API calls, navigation, and WS control live.
- lightweight client routing (e.g. `reitit.frontend`) feeding the same atom.

`xterm.js` renders the terminal; `xterm-addon-fit` handles resize.
**Alternatives considered and rejected:** re-frame (React dep + batteries we'd
barely use for an app this size); Vite+React or Next.js (second language/runtime;
Next's SSR/RSC/API-route value is wasted behind a login). See the trade-off table.

### 6.2 Screens
- **Login** — a button that redirects to the OIDC provider; the callback lands
  back on the app, which then reads `GET /api/me`.
- **Dashboard** — the user's workspaces with live status (ready/active/idle/
  suspended), and **Create / Open / Resume / Destroy** actions mapped 1:1 to the
  control-plane API. Shows quota usage.
- **Workspace (terminal)** — the embedded `xterm.js` terminal bound to one
  session, plus a thin header (status, reconnect, fullscreen) and a session
  switcher. This is where the user spends ~all their time.
- **Settings** — provider keys (BYO, later phase), display/theme, sign-out.

### 6.3 The terminal client (the one non-trivial part)
`terminal.cljs` speaks **ttyd's WebSocket protocol** directly — not an iframe of
ttyd's own page — so we own the auth handshake, theming, and reconnect:
1. `POST /api/sessions` → `{id, status}`; poll `GET` until `ready`.
2. Open `WSS /api/sessions/:id/tty`; `playground-proxy` authorizes the JWT and
   bridges to that container's ttyd.
3. Wire `xterm.js` ⇄ socket: keystrokes → `INPUT` frames, server bytes →
   `term.write`, `fit`/`SIGWINCH` → `RESIZE` frames. The `xterm.js` instance is
   created/destroyed via Replicant's **`:replicant/on-mount` / `:on-unmount`**
   lifecycle hooks on the terminal node — the imperative widget lives outside the
   pure render path, with the atom holding only connection state.
4. **Reconnect**: because the agent runs in `by --web-tmux` (a detached, live
   tmux pane), a dropped socket or refreshed tab **re-attaches to the same live
   session** — the agent keeps running. The UI shows a "reconnecting" banner and
   re-opens the WS with a fresh token.

### 6.4 Auth on the client
No tokens in `localStorage` — the session is an **httpOnly, SameSite cookie** set
by `playground-auth`, so XSS can't read it. The SPA only ever knows "am I logged
in" (from `/api/me`) and handles 401 by bouncing to login. A short pre-`/tty`
call mints the per-socket token so a backgrounded tab can't hold a stale WS open
past logout.

### 6.5 Build & serve
`shadow-cljs release app` compiles `playground-ui` to static JS/CSS whose output
dir is `bases/playground-http/resources/public/`. The base serves `index.html`
+ assets as same-origin static files; in dev, `shadow-cljs watch` runs a hot-
reload server proxying `/api` and `/auth` to the JVM control plane. The SPA is
**not a Polylith brick** (it's CLJS, outside brick scanning) — it lives under
`frontend/` and is wired in only at the project's release build step.

---

## 7. Scale & reliability

- **Load**: assume *C* concurrent active users. Active workspaces ≈ *C*;
  suspended ones cost only storage. Control plane is stateless → scale
  horizontally behind the Edge; Postgres is the only shared stateful tier.
- **Cold-start hiding**: keep a **warm pool** of pre-started workspaces from the
  current image; assignment becomes "claim + mount volume + inject secrets,"
  which is the fast part.
- **Idle reclamation**: a reaper suspends idle sessions (configurable, e.g.
  20 min) → bounds cost; resume is fast because the volume persists.
- **Autoscaling**: workspace nodes scale on active-session count; control-plane
  pods on request rate. Session→node **affinity** so the WS proxy path is one
  hop; on node loss, the session is re-provisioned from its persistent volume.
- **Failure containment**: a workspace crash affects one user; `--web-tmux`
  means the agent survives a *browser* disconnect (reattach to the live pane).
  Control-plane restart loses nothing (state in Postgres; data in volumes).
- **Observability**: reuse Brainyard's mulog; per-session structured logs +
  audit trail (who launched what), resource metrics, and ttyd connection health.

---

## 8. Trade-offs (made explicit)

| Decision | Options | Choice & why | Cost |
|---|---|---|---|
| Tenant isolation | seatbelt · Docker+gVisor · Firecracker µVM | **Docker (Phase 0–1) → gVisor/Firecracker (Phase 2)** behind one `workspace-runtime` interface. Start simple, harden before opening to untrusted users. | µVM adds boot latency + ops complexity; defer it. |
| Terminal transport | ttyd-per-container · build our own xterm/WS server | **Reuse ttyd** — already proven in `web-share`, gives real PTY semantics free. | One ttyd per active session (cheap); we proxy it. |
| Front-end stack | CLJS/Replicant · CLJS/re-frame · Vite+React · Next.js | **ClojureScript + Replicant** — one language, zero JS-framework deps, data-driven model matches `by`; app is small enough not to need re-frame's batteries. Next.js rejected: SSR/RSC value wasted behind a login + second runtime. | Smaller hiring pool than React; we own the store/dispatch/routing wiring. |
| Terminal embedding | custom xterm.js client · iframe ttyd's page | **Custom xterm.js** speaking ttyd's WS protocol — own auth, theme, reconnect. | A bit more client code than an iframe. |
| Secrets | bake into image · inject at runtime | **Inject at runtime, per-user, from Vault** | Needs a secrets manager + rotation path. |
| State durability | persistent volume · ephemeral + snapshot only | **Persistent volume** (fast resume, simple mental model) + snapshots for suspend/backup. | Storage cost; mitigated by GC + tiering. |
| Control-plane state | in-memory · Postgres | **Postgres** → stateless, restart-safe control plane. | A DB to run; worth it. |
| Network policy | all-or-nothing (seatbelt) · egress allowlist proxy | **Egress allowlist** — keys live in-container, exfil risk is real. | Must maintain the endpoint allowlist. |

---

## 9. Phased delivery

- **Phase 0 — internal preview (single node).** `playground-http` + `playground-auth`
  (OIDC) + `session-broker` + `workspace-runtime/docker` + `playground-proxy`.
  One Docker container per user running `by --web-tmux`; ephemeral volume; keys
  via env. Plus a minimal **`playground-ui`**: login + a one-button "open
  terminal" wired to the `xterm.js` client. Proves the end-to-end browser→`by`
  path. *Trusted users only.*
- **Phase 1 — durable & secure-enough for a wider beta.** Persistent volumes,
  `playground-secrets` (Vault), `playground-store` (Postgres), idle reaper,
  resume, audit log. Front-end grows the full **dashboard** (multi-workspace
  list, status, resume/destroy) and **settings**.
- **Phase 2 — multi-tenant-grade.** Egress allowlist, gVisor/Firecracker driver,
  warm pool, node autoscaling, snapshot suspend/restore, quotas.

A thin MVP for Phase 0 can ship behind a feature flag without touching
`agent-tui-app` at all — the playground is an additive project that *invokes*
the existing `by` binary, so the core TUI keeps shipping independently.

### As-built (Phase 0, implemented)

The browser→`by` path is working end-to-end:

- **`frontend/playground-ui`** — the Replicant SPA (login · dashboard · xterm
  terminal). xterm.js loads as a CDN UMD global (Closure can't bundle it).
- **`bases/playground-http`** — the control plane:
  - `auth.clj` (`playground-auth`) — **real OIDC** authorization-code flow when
    `OIDC_ISSUER` is set (bare-cookie stub otherwise); the OIDC `sub` → a
    sha-256 `user-id`.
  - `store.clj` (`playground-store`) — durable session records in **Postgres**
    (`PG_DATABASE_URL`) or in-memory; only non-secret fields persisted.
  - `sessions.clj` — the broker: lifecycle policy, **user-scoped** ownership,
    and a **restart-safe reconcile** that rebuilds runtime state (host port +
    ttyd password) from running containers — secrets never hit the DB.
  - `workspace.clj` (`workspace-runtime`) — the Docker driver (`docker run` a
    container per session on a loopback-published ephemeral port; health-check
    ttyd; mount `~/.aws` for Bedrock).
  - `proxy.clj` (`playground-proxy`) — the WS bridge: browser ⇄ container ttyd
    over a `java.net.http` WebSocket, injecting the ttyd `AuthToken`.
  - `routes.clj` (REST + SPA serving), `tty.clj` (echo, fake mode only).
- **`deploy/playground-workspace`** — the workspace image: `temurin:21-jre` +
  ttyd + tmux + git + the `by` uberjar, launched via the `--web-tmux` flag.
  Provider via `PG_WORKSPACE_PROVIDER` (OpenAI + Bedrock/Claude both verified).
- Build/run: `bb playground:ui`, `bb playground:image`, `bb playground:run`.

Verified end-to-end against a mock OIDC IdP + a Postgres container: full OIDC
login → authenticated dashboard; two users isolated (each sees only their own
sessions; cross-tenant access 404); control-plane restart loses nothing (state
in Postgres, runtime reconciled from live containers).

Still to do (Phase 1+): `playground-secrets` (Vault) — currently a shared
`.env` + mounted `~/.aws`; signature/nonce verification on the id_token;
session-broker scheduling (warm pool, idle reaper); egress allowlist;
gVisor/Firecracker. And the structural step: these bricks still live as
namespaces inside the base — they graduate to `components/` with a
`playground-server` Polylith project + `workspace.edn` entry.

---

## 10. What I'd revisit as it grows

- **Isolation upgrade trigger**: the moment untrusted/self-serve signups arrive,
  move the default `workspace-runtime` driver from Docker to gVisor/Firecracker.
- **Per-host egress control** to fully retire seatbelt's all-or-nothing network
  model; consider mTLS to MCP endpoints.
- **BYO-keys**: let users supply their own LLM keys (changes the secrets model
  from platform-funded to user-funded; affects quota/billing).
- **Collaboration**: ttyd already serves multiple clients to one pane — expose
  controlled session sharing (the original `--web` use case) *within* a tenant.
- **Multi-region** workspaces for latency, with the volume as the thing that
  must be placed/replicated.
- **Right-sizing**: revisit warm-pool size and idle timeout from real usage; the
  tension is always cold-start latency vs idle cost.

---

### Assumptions
- An OIDC IdP is available (Google/Okta/etc.) — we don't build auth from scratch.
- A container orchestrator (Docker/K8s) and a secrets manager exist or will be
  provisioned; this design treats them as platform, not in-repo code.
- `by` remains launchable as a single binary with env-driven config — true today
  via `BY_USER_ID`, `BY_WORKING_DIR`, `BY_PROJECT_DIR`, `BY_WEB_*`, `.env`.
