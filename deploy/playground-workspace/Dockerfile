# Copyright (c) 2024-2026 Grumatic, Inc.
# SPDX-License-Identifier: MIT
#
# Brainyard Playground — per-session workspace image (Phase 0).
#
# One container == one tenant's sandboxed `by`. ttyd serves the TUI over a
# WebSocket on the container-internal port 7681; `playground-proxy` (in the
# control plane) bridges the browser to it. The image runs the `by` UBERJAR on
# a stock JRE — NOT the native binary, which is platform-specific (the dev host
# builds a macOS binary). Clojure AOT emits Java 8 bytecode, so any modern JRE
# runs it; temurin:21-jre is small and current.
#
# Build context expects `by.jar` (the agent-tui-app uberjar) copied in beside
# this file — see deploy/playground-workspace/build.sh.

FROM eclipse-temurin:21-jre

# ttyd (PTY-over-WebSocket), tmux (detached live session for reconnect), git.
RUN apt-get update \
 && apt-get install -y --no-install-recommends ttyd tmux git ca-certificates \
 && rm -rf /var/lib/apt/lists/*

# The agent's uberjar + a `by` shim so `which by` / BY_WEB_SELF resolve to it.
COPY by.jar /opt/by/by.jar
RUN printf '#!/bin/sh\nexec java -jar /opt/by/by.jar "$@"\n' > /usr/local/bin/by \
 && chmod +x /usr/local/bin/by

# Non-root tenant. Home holds ~/.brainyard (memory/sessions/config); /workspace
# is the project tree. Both writable; the rest of the fs is the tenant boundary.
RUN useradd --create-home --shell /bin/bash by \
 && mkdir -p /workspace \
 && chown -R by:by /workspace /home/by
USER by
WORKDIR /workspace

# `by` reads these at startup; the launcher (web-share) wraps `by run` in ttyd
# inside a detached tmux session. BY_WEB_SELF makes ttyd relaunch the child via
# the shim (= the jar). Bind 0.0.0.0 so the host reaches ttyd via a published
# port; the per-session BY_WEB_PASS is injected at `docker run` time.
#
# IMPORTANT: do NOT set BY_WEB_TMUX=1 here. That env is inherited by the child
# `by run` (the tmux session's program), making it re-enter the web launcher
# instead of running the TUI — the BY_WEB_CHILD=1 guard is only set for the
# Tier-1 `serve!` path, not Tier-2 `serve-tmux!`. We trigger web-tmux with the
# `--web-tmux` FLAG in the ENTRYPOINT instead (flags aren't forwarded to the
# child by web-child-argv), so the child sees neither flag nor env and runs the
# TUI. BY_WEB_BIND/PORT/SELF are safe as env — alone they don't trigger sharing.
ENV BY_WEB_BIND=0.0.0.0 \
    BY_WEB_PORT=7681 \
    BY_WEB_SELF=by \
    BY_USER_ID=playground

EXPOSE 7681

# The flag launches ttyd → tmux → `by run`; the child runs the real TUI.
ENTRYPOINT ["by", "--web-tmux"]
