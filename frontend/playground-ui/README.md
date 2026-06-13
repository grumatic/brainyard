# playground-ui

The Brainyard Playground front-end — a ClojureScript single-page app rendered
with [Replicant](https://replicant.fun/). It logs the user in, lists their
sandboxed workspaces, and drives `by` in the browser via an `xterm.js` terminal
bridged to each session's ttyd WebSocket.

See [`docs/design/playground-design.md`](../../docs/design/playground-design.md)
§6 for where this fits in the overall architecture.

## Architecture in one breath

A single `state/app-state` atom is the source of truth; `core` re-renders the
whole `views/root` on every change. Views are **pure** `state → hiccup` and
their event handlers are **data** (`:on {:click [[:action ...]]}`) interpreted
by `dispatch` — the only place side effects (API, navigation, mutation) happen.
The one imperative piece, the xterm.js terminal, is sealed behind Replicant
life-cycle hooks in `terminal`.

```
core.cljs      entry: set-dispatch!, add-watch render loop, routing, auth
state.cljs     the single app-state atom
views.cljs     pure state -> hiccup (login · dashboard · workspace)
dispatch.cljs  central action-data interpreter (effects live here)
api.cljs       fetch wrappers over the control-plane REST (cookie-authed)
auth.cljs      OIDC login/logout redirects + /api/me
terminal.cljs  xterm.js mount/unmount + ttyd WebSocket handshake
```

## Develop

```bash
npm install
npm run watch            # http://localhost:8080, hot-reloaded
```

Unmatched requests (`/api/*`, `/auth/*`, the `/tty` WebSocket) are proxied to
the JVM control plane at `http://localhost:8090` — run `playground-server`
locally on that port so cookies and the terminal socket behave as same-origin.

## Build for production

```bash
npm run release          # compiles to ../../bases/playground-server/resources/public/js
```

`playground-server` then serves `index.html` + `/js/main.js` same-origin. The
`release` script compiles `main.js` into the base's `resources/public/js/` **and**
copies `index.html` alongside it, so both land in the base automatically (both
are gitignored there as build artifacts).

## Notes

- **Not a Polylith brick.** This is CLJS/npm and lives outside brick scanning;
  it's wired in only at the project's release build step.
- The `xterm.css` is loaded via CDN in `index.html` and **must** match the
  `@xterm/xterm` version in `package.json`.
- The ttyd protocol contract (command bytes, auth frame) is documented at the
  top of `terminal.cljs`; it must stay in lock-step with `playground-proxy`.
