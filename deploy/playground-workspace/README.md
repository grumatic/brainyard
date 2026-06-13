# Playground workspace image

The per-session sandbox image for the Brainyard Playground — one container is
one tenant's `by`. It runs `by --web-tmux`, which wraps `by run` in a detached
tmux session served over a WebSocket by **ttyd** on container-internal `7681`.
The control plane's `playground-proxy` bridges the browser to it.

## Contents

- `eclipse-temurin:21-jre` + `ttyd` + `tmux` + `git`
- the **`by` uberjar** at `/opt/by/by.jar` (the native binary is platform-
  specific; the uberjar is Java-8 bytecode and runs on any JRE) and a `by` shim
- non-root user `by`; `/workspace` is the project tree

## Build

```bash
bb uberjar:ata                       # produce projects/agent-tui-app/target/agent-tui-app.jar
bb playground:image                  # → brainyard/workspace:dev
#   or: deploy/playground-workspace/build.sh [tag]
```

`build.sh` stages the uberjar into the build context (it's `.gitignore`d) and
runs `docker build`.

## Run (standalone, for debugging)

```bash
docker run --rm -p 127.0.0.1:0:7681 \
  -e BY_WEB_PASS=secret \
  --env-file /path/to/.env \         # provider creds for `by`
  brainyard/workspace:dev
docker port <id> 7681                 # find the host port
# ttyd: 401 unauthenticated, 200 with  -u by:secret
```

## Design notes

- **Why the `--web-tmux` FLAG, not `BY_WEB_TMUX=1`:** a global env var is
  inherited by the child `by run`, making it re-enter the web launcher (the
  `BY_WEB_CHILD=1` re-entrancy guard is only set on the Tier-1 `serve!` path,
  not Tier-2 `serve-tmux!`). The flag isn't forwarded to the child, so the child
  runs the real TUI. `BY_WEB_BIND/PORT/SELF` are safe as env — alone they don't
  trigger sharing.
- **Bind `0.0.0.0`:** ttyd binds all interfaces *inside* the container; the host
  reaches it only via a loopback-published ephemeral port
  (`-p 127.0.0.1:0:7681`). Not publicly exposed.
- **Per-session password:** injected as `BY_WEB_PASS` at `docker run`; the proxy
  holds it and authenticates upstream so the browser never sees it.
- **Provider creds:** Phase 0 passes a shared `.env`; `playground-secrets` will
  inject per-user creds from a vault later.
