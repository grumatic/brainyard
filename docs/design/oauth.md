# Device-Flow OAuth for brainyard

> Status: **Proposed** (design draft, 2026-06-22, author: Jake Na + assistant).
> Scope: a standalone `clj-oauth` component implementing the OAuth 2.0 **Device
> Authorization Grant (RFC 8628)** plus OIDC discovery and keychain-backed token
> storage, shared by both the **MCP HTTP transport** (`components/agent/.../mcp`)
> and the **LLM provider** auth path (`components/clj-llm/.../core/oauth.clj`).
>
> Motivation: `by` runs headless in the playground Docker container. Every OAuth
> path we ship today assumes a browser is co-located with the process — it isn't,
> so MCP and provider logins stall. RFC 8628 separates the device that *requests*
> the token from the device that *authorizes* it, removing the local-browser and
> port-binding assumptions entirely.
>
> Reference / prior art that prompted this: "CLI Authentication, the Right Way"
> (abgeo.dev), discussed at <https://news.hada.io/topic?id=30648>. The takeaways
> we adopt: device flow as the default, OIDC `.well-known` discovery instead of
> hard-coded endpoints, strict `interval`/`slow_down` handling, and refresh tokens
> in the OS keychain rather than a plaintext JSON file.

## 1. Context — why the current flows break in a container

brainyard has two independent OAuth surfaces today, and both assume the browser
and the CLI share one machine.

**LLM provider auth.** `components/clj-llm/src/ai/brainyard/clj_llm/core/oauth.clj`
implements the Anthropic Max/Pro **authorization-code + PKCE** flow. It calls
`java.awt.Desktop/.browse` (falling back to `xdg-open`) to open
`https://claude.ai/oauth/authorize`, then blocks on `read-line` waiting for the
user to paste the `code#state` string back from the console callback page. Tokens
land in a plaintext file at `~/.config/clj-llm/anthropic-oauth-tokens.json`. In a
container there is no `Desktop`, usually no `xdg-open`, and the paste step is the
only thing that actually works — i.e. it already degrades to a *manual* device
flow, which is exactly the signal RFC 8628 formalizes.

**MCP server auth.** `components/agent/src/ai/brainyard/agent/mcp/client.clj`'s
`HttpMCPClient` does a plain `initialize` POST and attaches whatever static
`:headers` the server config carries (`{"Authorization" "Bearer …"}`). It has **no
OAuth handshake**. So remote OAuth servers (Notion, Linear, Google) are bridged
through `mcp-remote` (stdio) — see the seed config in
`components/agent/src/ai/brainyard/agent/mcp/integration.clj`. `mcp-remote` runs a
**browser loopback** flow (`127.0.0.1:<port>/oauth2callback`) on first start. That
is precisely the pattern that breaks under SSH / containers / WSL described in the
reference article, and we already have it documented breaking in-tree: the Gmail
seed notes that `mcp-remote`'s redirect "always landed on a dead port
(ERR_CONNECTION_REFUSED)" because it never binds the callback listener before
opening the browser. The workaround there (`taylorwilsdon/google_workspace_mcp`
with its own loopback callback) just moves the same broken assumption into a
different process.

The common root cause: **loopback redirect requires a local listening port and a
local browser to hit it.** Remove that requirement and both surfaces work
identically on a laptop, in the playground container, over SSH, and in CI.

## 2. Goals and non-goals

Goals:

1. A reusable `clj-oauth` component that performs RFC 8628 device flow end to end
   with no port binding and no assumption of a local browser.
2. OIDC discovery (`/.well-known/openid-configuration`) so endpoints are never
   hard-coded; capability detection (does the provider advertise a
   `device_authorization_endpoint`?) drives flow selection.
3. A token store with an **OS keychain** backend and a hardened **file fallback**,
   keyed by `(issuer/server-id, BY_USER_ID)`, with transparent refresh.
4. Wire it into the MCP HTTP transport so capable remote servers authenticate
   natively — retiring the `mcp-remote` loopback bridge for those servers.
5. Re-home the Anthropic provider flow on the same store + refresh machinery.
6. Honest fallbacks: when a provider does *not* support device grant, degrade to a
   headless authorization-code "paste the code" path rather than to a broken
   loopback.

Non-goals (this milestone):

- We are **not** building an authorization *server*. `bases/playground-server`'s
  OIDC relying-party code (`playground_server/auth.clj`) stays as-is; this
  component is a *client*.
- We do **not** try to defeat device-code phishing in the client — per the article
  and the Storm-2372 / APT29 campaigns, that mitigation belongs to the
  authorization server. We cover the client's share in §8.
- No change to the stdio MCP transport contract; `mcp-remote` remains available as
  an explicit fallback for servers that only speak browser-loopback.

## 3. RFC 8628 in one screen

```
 CLI (by, headless)                          Authorization Server
 ─────────────────                           ────────────────────
 1. POST device_authorization_endpoint
       client_id, scope                  ─▶
                                         ◀─  { device_code, user_code,
                                               verification_uri,
                                               verification_uri_complete?,
                                               expires_in (e.g. 1800),
                                               interval (e.g. 5) }
 2. Print to terminal:
       "Go to https://issuer/device and enter code WDJB-MJHT"
       (optionally a QR for verification_uri_complete)

 3. loop every `interval` seconds:
       POST token_endpoint
         grant_type=urn:ietf:params:oauth:grant-type:device_code
         device_code, client_id          ─▶
                                         ◀─  authorization_pending → keep polling
                                         ◀─  slow_down            → interval += 5
                                         ◀─  access_denied        → abort
                                         ◀─  expired_token        → restart
                                         ◀─  { access_token, refresh_token,
                                               expires_in, token_type } → done
```

Key properties we rely on: **no `redirect_uri`, no listening socket, no local
browser.** The user authorizes on *any* device — their laptop or phone — while
`by` polls a stateless endpoint. Typical logins finish in well under a minute
(~10 polls at the 5 s default), so polling is cheaper than holding a websocket per
pending login.

## 4. Component shape

New Polylith component `components/clj-oauth`, depending only on
`clj-http-native`, `env-detect`, `mulog`, and `util` — deliberately **no `agent`
or `clj-llm` dependency**, so both can consume it without a cycle.

```
components/clj-oauth/
  src/ai/brainyard/clj_oauth/
    interface.clj          ; public surface (see §4.1)
    core/
      pkce.clj             ; verifier/challenge/state (lifted from clj-llm oauth.clj)
      discovery.clj        ; .well-known/openid-configuration fetch + cache
      device.clj           ; RFC 8628: start! + poll! state machine
      authcode.clj         ; PKCE auth-code + headless out-of-band paste fallback
      store.clj            ; token store: keychain backend + file fallback
      flow.clj             ; orchestrator: pick flow from env + provider metadata
```

`clj-http-native` already wraps `java.net.http` (which native-image pulls in via
`cognitect.aws-api`), so the component adds **no new native-image surface** for
HTTP. The PKCE helpers (`generate-code-verifier`, `generate-code-challenge`,
`generate-state`) move verbatim from `clj_llm/core/oauth.clj`; that file becomes a
thin Anthropic-specific adapter over `clj-oauth` (§6).

### 4.1 Public interface (`ai.brainyard.clj-oauth.interface`)

```clojure
;; Discovery
(discover issuer)
;; → {:device_authorization_endpoint url? :token_endpoint url
;;    :authorization_endpoint url? :grant_types_supported [..] ...}  (cached)

;; One-call login. Picks device | authcode | paste from env + provider caps.
(login! {:account-id   "notion"          ; storage key, e.g. server name or :anthropic
         :issuer        "https://mcp.notion.com"   ; or explicit :endpoints {...}
         :client-id     "…"
         :scopes        ["read" "write"]
         :flow          :auto             ; :auto | :device | :loopback | :paste
         :on-user-prompt (fn [{:keys [verification_uri user_code
                                      verification_uri_complete expires_in]}] …)})
;; → token-bundle, also persisted to the store

;; Token retrieval with transparent refresh
(access-token account-id)        ; → string,   refreshes if within 60s of expiry
(bearer-headers account-id)      ; → {"Authorization" "Bearer …"}
(logout! account-id)             ; clear stored tokens
(authenticated? account-id)      ; → boolean
```

`on-user-prompt` is how the caller renders the code — the TUI prints a code box
(and QR); a non-interactive caller logs it. The component never assumes a TTY.

## 5. Flow selection (`core/flow.clj`)

Default is **device flow**, matching the article's "new CLI → device by default"
guidance. Selection logic:

1. Read the desired flow: `--oauth-flow` / `BY_OAUTH_FLOW` / config
   `[:oauth :flow]` (precedence mirrors the existing `.env` ladder in CLAUDE.md),
   default `:auto`.
2. For `:auto`, run discovery. If the provider advertises
   `device_authorization_endpoint` → **device flow**.
3. Else, consult `env-detect`:
   - `env-detect/detect-sandbox-environment` already classifies Docker / Nix /
     devcontainer / SSH. Add a small `headless?` probe (`$DISPLAY` /
     `$WAYLAND_DISPLAY`, `xdg-open` on PATH via `detect-executables`,
     `java.awt.Desktop/isDesktopSupported`).
   - **headless** (container/SSH/no browser) → headless authorization-code with
     out-of-band paste (`core/authcode.clj`) — the formalized version of what
     `clj_llm/core/oauth.clj` already does.
   - **local + browser** → loopback is permitted *only* when the user opts in via
     `:flow :loopback` (or `--web`); never the silent default.

This keeps the playground container on a flow that works (device, else paste) and
never silently picks loopback.

## 6. Token storage (`core/store.clj`)

Keyed by `(account-id, BY_USER_ID)` so memory/session partitioning by user (per
CLAUDE.md) extends to credentials. Bundle shape is the existing one:
`{:access_token :refresh_token :expires_at :scope :token_type :obtained_at}`.

Backends, selected by `[:oauth :token-store]` (`:auto | :keychain | :file`),
default `:auto`:

- **macOS keychain** — shell out to the system `security` binary
  (`add-generic-password -U -s ai.brainyard.oauth -a <key> -w <json>` /
  `find-generic-password -w`). Subprocess, so no JNI/reflection — safe under
  native-image, unlike a bundled keyring lib.
- **Linux libsecret** — `secret-tool store/lookup` when present (probed via
  `env-detect/detect-executables`).
- **File fallback** — `~/.brainyard/oauth/<account>.json`, file mode `0600`, dir
  `0700`. This is the path the playground container takes (no keychain daemon).
  It supersedes the current `~/.config/clj-llm/…` location and the world-readable
  default that ships today.

`:auto` resolution: keychain if its CLI is present and a probe write/read
round-trips, else file. The choice is logged once at `mulog/info ::oauth-store`
(never the secret). Tokens are **never** written to mulog; note that
`mcp/client.clj`'s stderr drain logs URLs at `::mcp-stderr-url` — that path must
not be used to carry bearer tokens.

Refresh reuses the existing logic in `clj_llm/core/oauth.clj`
(`token-expired?` with a 60 s skew, `refresh-access-token`,
`get-valid-access-token`), lifted into `core/store.clj` and made
provider-agnostic (refresh endpoint comes from discovery, not a constant).

## 7. Integration points

### 7.1 MCP HTTP transport

Extend the MCP server config schema (`mcp-server-config` in
`agent/mcp/integration.clj`) with an optional `:auth` block:

```clojure
"notion"
{:transport :http
 :config {:url "https://mcp.notion.com/mcp"
          :auth {:type      :oauth
                 :issuer    "https://mcp.notion.com"  ; → discovery
                 :client-id "…"                        ; or dynamic reg (RFC 7591)
                 :scopes    ["read" "write"]
                 :flow      :auto}}
 :enabled false
 :auto-register-tools true}
```

`HttpMCPClient/connect!` changes: before the `initialize` POST, if `:auth :oauth`
is declared (or the server answers `401` with a `WWW-Authenticate: Bearer`
challenge, per the MCP auth spec), call `clj-oauth/login!` once, then have
`build-request-headers` merge `clj-oauth/bearer-headers` on every request. On a
`401` mid-session, refresh once and retry; if refresh fails, surface a
re-auth prompt through the same `on-settle` channel `load-mcp-servers!` already
uses for per-server status. Connects already run on background futures
(`connect-server-async!`), so the interactive device prompt never blocks startup
or the other servers — the generous `:connect-timeout-ms` (already 120 s+ for
OAuth bridges) covers the human round-trip.

For servers that advertise device grant, this **removes the `mcp-remote`
dependency entirely**. For servers that only do browser-loopback, keep the
existing stdio `mcp-remote` seed as a documented fallback.

A caveat worth stating plainly: many hosted MCP servers implement OAuth 2.1 with
**dynamic client registration + authorization-code/PKCE**, not RFC 8628. Where
discovery shows no `device_authorization_endpoint`, the HTTP transport uses the
headless authorization-code paste path (§5 step 3), which still beats loopback in
a container. Device flow is the preferred rung, not the only rung.

### 7.2 LLM provider auth

`clj_llm/core/oauth.clj` keeps its public fns (`authenticate!`,
`get-oauth-headers`, `oauth-authenticated?`, `logout!`) for source compatibility
but delegates storage/refresh to `clj-oauth` under `account-id :anthropic`.
Anthropic's console may not expose a device endpoint, so its flow stays
authorization-code; the win is unified, keychain-backed storage and one refresh
implementation. Any future provider that *does* support device grant gets it for
free.

### 7.3 TUI commands

- `/login [provider]` and `/logout [provider]` — new commands in
  `bases/agent-tui/.../commands.clj`, delegating to `clj-oauth/login!`/`logout!`.
- `/mcp <name> auth` — extend `agent/mcp/commands.clj` to (re)run auth for one
  server on demand (pairs with the existing `/mcp <name> start|stop`).
- Rendering: the `on-user-prompt` callback draws a code box via `display-block`
  and, when `[:oauth :qr?]` is true, a terminal QR for
  `verification_uri_complete`. Status lines ("waiting for authorization…",
  "slow_down — backing off to N s", "authorized") stream as the poll loop turns.

### 7.4 Config schema additions

In `agent.core.config/config-schema`:

```clojure
:oauth {:flow            :auto      ; :auto | :device | :loopback | :paste
        :token-store     :auto      ; :auto | :keychain | :file
        :poll-cap-ms     900000     ; hard stop even if expires_in is larger
        :qr?             true}
```

Plus the per-server `:auth` map under `[:mcp :servers <name> :config]`. Env
overrides `BY_OAUTH_FLOW` / `BY_OAUTH_TOKEN_STORE` follow the documented `.env`
precedence (real env > `.env` > default).

## 8. Security considerations

**Device-code phishing.** RFC 8628 trades a local attack surface for a social one:
an attacker starts a real device flow and tricks a victim into entering the
attacker's `user_code` at the genuine provider (the Storm-2372 / APT29
campaigns against M365 since 2024). Per the article, the durable mitigations live
on the **authorization server** (short `user_code` TTL, showing client name +
location on the verification page, rate-limiting code entry, conditional access).
The client's responsibilities, which we honor:

- Follow the spec and take no shortcuts — no auto-submitting codes, no scraping.
- Surface the requested `scopes` and client identity in the terminal prompt so the
  user can sanity-check before authorizing.
- Prefer showing the **code + plain `verification_uri`** and treat
  `verification_uri_complete` (the click-through/QR variant) as opt-in, since the
  one-click path is what makes a phishing landing page convincing.
- Honor `interval` and `slow_down` (avoid hammering, which also looks like abuse).

**Tokens at rest.** Keychain where available; otherwise `0600`/`0700` file — never
the world-readable `~/.config` JSON we ship today. Never log access/refresh tokens
(watch the `::mcp-stderr-url` drain path). PKCE + `state` are retained on the
authorization-code fallback for CSRF protection (the existing state-mismatch check
in `oauth.clj` carries over).

**Container blast radius.** The playground runs under the seatbelt/`--sandbox`
write-containment policy on macOS hosts; the `~/.brainyard/oauth/` store is inside
the allowed write root, so no policy change is needed.

## 9. Testing

- **Unit** — `core/device.clj` poll state machine across every RFC 8628 response
  (`authorization_pending` → continue, `slow_down` → `interval += 5`,
  `access_denied`/`expired_token` → terminal, success → bundle), driven by a
  stub token endpoint. `core/discovery.clj` parsing + cache. `core/store.clj`
  round-trip for each backend (keychain mocked via an injectable shell-runner;
  file backend asserts `0600`).
- **Integration** — stand up the article's reference target: a **Keycloak** realm
  with "OAuth 2.0 Device Authorization Grant" enabled, run `login!` end to end in
  CI headlessly (the poll completes once a scripted admin approves the code).
- **MCP** — connect a local mock HTTP MCP server that 401s until a bearer is
  attached; assert `connect!` runs the flow, attaches the header, and refreshes on
  a forced 401.
- **Native-image parity** — exercise the keychain subprocess and the device poll
  under both the JVM (`BY_JAR=1`) and the native `by` binary, since
  `java.awt.Desktop` and JNI keyrings are the usual native-image traps and we
  avoid both.

## 10. Phasing

1. **Extract** `clj-oauth`: move PKCE + token store out of `clj_llm/core/oauth.clj`
   behind the new interface; no behavior change, file store relocates to
   `~/.brainyard/oauth/`.
2. **Device flow + discovery**: `core/device.clj` + `core/discovery.clj`, Keycloak
   smoke test green.
3. **MCP HTTP OAuth**: `:auth` config, `connect!` integration, headless auth-code
   fallback; retire `mcp-remote` for device-capable servers.
4. **Provider unification + polish**: Anthropic delegates to the store; keychain
   backend; `/login` `/logout` `/mcp … auth` commands; QR rendering.

## 11. Open questions

- **Dynamic client registration (RFC 7591).** Hosted MCP servers increasingly
  expect the client to self-register rather than ship a static `client-id`. Do we
  implement DCR in `core/discovery.clj`, or require a pre-provisioned `client-id`
  per server in config for v1?
- **Keychain on the playground host vs. container.** The container always uses the
  file backend; should `by` running natively on the user's macOS host default to
  keychain, or stay on file for parity with the container experience?
- **Refresh-token rotation.** Some providers rotate the refresh token on every
  use; the store must persist the new one atomically. Confirm our file-write path
  is crash-safe (write-temp-then-rename) before Phase 1.
- **Multi-account.** `account-id` is currently one key per provider/server. Do we
  need multiple identities per server (e.g. two Google accounts), and if so does
  the key become `(server, email)` like the `:tool-arg-overrides` `user_google_email`
  pattern already in the MCP seed config?
```
