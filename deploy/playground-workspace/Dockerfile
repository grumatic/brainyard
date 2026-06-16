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

# ttyd (PTY-over-WebSocket), tmux (detached live session for reconnect), git,
# plus the dev toolchain + fs/web utilities the agent uses via bash:
#   search/fs : ripgrep (rg), fd, jq, tree, less, find/grep (coreutils)
#   web/fetch : curl, wget
#   python    : python3 + pip + venv (uv/uvx added below)
#   build/clj : rlwrap (for `clj`), unzip, ca-certificates
#   privesc   : sudo (tenant runs as non-root `by`; passwordless sudo below
#               lets it apt-install / npm -g without a prompt — see USER by)
RUN apt-get update \
 && apt-get install -y --no-install-recommends \
      ttyd tmux git ca-certificates curl wget unzip rlwrap less tree jq \
      ripgrep fd-find python3 python3-pip python3-venv sudo \
 && ln -s "$(command -v fdfind)" /usr/local/bin/fd \
 && rm -rf /var/lib/apt/lists/*

# Node.js LTS (node / npm / npx) via NodeSource.
RUN curl -fsSL https://deb.nodesource.com/setup_lts.x | bash - \
 && apt-get install -y --no-install-recommends nodejs \
 && rm -rf /var/lib/apt/lists/* \
 && npm config set update-notifier false

# Next.js + Playwright, installed globally so `next` / `playwright` are on PATH
# for every tenant. PLAYWRIGHT_BROWSERS_PATH points the browser download at a
# world-readable system dir (the default is per-user ~/.cache, which the non-root
# `by` user wouldn't share); `--with-deps chromium` also pulls the apt libs the
# browser needs to launch headless. Chromium only — the full browser set would
# bloat the image; add firefox/webkit here if a tenant needs them.
ENV PLAYWRIGHT_BROWSERS_PATH=/ms-playwright
RUN npm install -g next playwright \
 && playwright install --with-deps chromium \
 && chmod -R a+rX /ms-playwright \
 && rm -rf /var/lib/apt/lists/*

# uv / uvx (Astral) — fast Python package+runtime manager, installed system-wide.
RUN curl -LsSf https://astral.sh/uv/install.sh | env UV_INSTALL_DIR=/usr/local/bin sh \
 && uv --version && uvx --version

# GitHub CLI (gh) — from GitHub's apt repo (keyring + arch-matched source).
RUN curl -fsSL https://cli.github.com/packages/githubcli-archive-keyring.gpg \
      -o /usr/share/keyrings/githubcli-archive-keyring.gpg \
 && chmod go+r /usr/share/keyrings/githubcli-archive-keyring.gpg \
 && echo "deb [arch=$(dpkg --print-architecture) signed-by=/usr/share/keyrings/githubcli-archive-keyring.gpg] https://cli.github.com/packages stable main" \
      > /etc/apt/sources.list.d/github-cli.list \
 && apt-get update \
 && apt-get install -y --no-install-recommends gh \
 && rm -rf /var/lib/apt/lists/* \
 && gh --version

# AWS CLI v2 — official zip installer. `uname -m` (x86_64 / aarch64) matches
# Amazon's asset naming, so the image builds on either arch.
RUN arch="$(uname -m)" \
 && curl -fsSL "https://awscli.amazonaws.com/awscli-exe-linux-${arch}.zip" -o /tmp/awscliv2.zip \
 && unzip -q /tmp/awscliv2.zip -d /tmp \
 && /tmp/aws/install \
 && rm -rf /tmp/awscliv2.zip /tmp/aws \
 && aws --version

# Clojure CLI (clj/clojure) + Babashka (bb), system-wide.
RUN curl -fsSL https://github.com/clojure/brew-install/releases/latest/download/linux-install.sh -o /tmp/clj-install.sh \
 && bash /tmp/clj-install.sh && rm /tmp/clj-install.sh \
 && curl -fsSL https://raw.githubusercontent.com/babashka/babashka/master/install -o /tmp/bb-install.sh \
 && bash /tmp/bb-install.sh --dir /usr/local/bin && rm /tmp/bb-install.sh \
 && clojure --version && bb --version

# clj-nrepl-eval: a bb wrapper around bhauman/clojure-mcp-light's nREPL-eval main
# (an nREPL CLIENT — the agent starts an nREPL server, then evaluates against it).
# Add the project as a :local/root dep (via --config) so its `src` is on the
# classpath without changing the agent's CWD (clj-nrepl-eval discovers
# .nrepl-port from CWD). Warm the deps at build so first use is fast.
RUN git clone --depth 1 https://github.com/bhauman/clojure-mcp-light.git /opt/clojure-mcp-light \
 && printf '{:deps {local/deps {:local/root "/opt/clojure-mcp-light"}}}\n' > /opt/clj-nrepl-eval.edn \
 && printf '#!/usr/bin/env bash\nexec bb --deps-root /opt/clojure-mcp-light --config /opt/clj-nrepl-eval.edn -m clojure-mcp-light.nrepl-eval -- "$@"\n' \
      > /usr/local/bin/clj-nrepl-eval \
 && chmod +x /usr/local/bin/clj-nrepl-eval \
 && (cd /tmp && clj-nrepl-eval --discover-ports >/dev/null 2>&1 || true)

# The agent's uberjar + a `by` shim so `which by` / BY_WEB_SELF resolve to it.
COPY by.jar /opt/by/by.jar
RUN printf '#!/bin/sh\nexec java -jar /opt/by/by.jar "$@"\n' > /usr/local/bin/by \
 && chmod +x /usr/local/bin/by

# ttyd shim (resolved before /usr/bin/ttyd on PATH, which web-share's `which
# ttyd` picks up): inject client options. A smaller fontSize gives a wider grid
# (~150 cols vs ttyd's default ~80), matching a normal terminal; theme matches
# the playground's dark background.
RUN printf '#!/bin/sh\nexec /usr/bin/ttyd -t fontSize=13 -t '\''theme={"background":"#0b0e14"}'\'' "$@"\n' \
      > /usr/local/bin/ttyd \
 && chmod +x /usr/local/bin/ttyd

# Entrypoint supervisor: bootstraps the workspace (git init, `by config --auto`)
# then runs `by --web-tmux` in a restart loop. See entrypoint.sh + ENTRYPOINT.
COPY entrypoint.sh /usr/local/bin/entrypoint.sh
RUN chmod +x /usr/local/bin/entrypoint.sh

# Non-root tenant. Home holds ~/.brainyard (memory/config); /workspace is the
# project tree (TUI sessions + files). Both writable; the rest of the fs is the
# tenant boundary. ~/.brainyard is pre-created so the per-session named volume
# mounted there (see playground workspace.clj) seeds from a by-owned dir — a
# missing mount point would be created root-owned and break the tenant's writes.
RUN useradd --create-home --shell /bin/bash by \
 && mkdir -p /workspace /home/by/.brainyard \
 && chown -R by:by /workspace /home/by \
 && echo 'by ALL=(ALL) NOPASSWD:ALL' > /etc/sudoers.d/by \
 && chmod 0440 /etc/sudoers.d/by

# User-scope BRAINYARD.md — `by` reads ~/.brainyard/BRAINYARD.md as User
# Instructions each turn, guiding the LLM on the workspace environment + tools.
# Seeded into the pg-state-<id> named volume on first start (it mounts here and
# copies the image content); --chown keeps it by-owned so the tenant can edit it.
COPY --chown=by:by BRAINYARD.md /home/by/.brainyard/BRAINYARD.md

# Disable the tmux status bar. web-share's serve-tmux! starts a tmux server that
# loads ~/.tmux.conf; its bottom status row redraws independently and fights the
# TUI's scroll-region/absolute-cursor menu redraws over xterm.js, leaving the
# autocomplete/model-picker menu overlapped and duplicated. With status off the
# TUI owns the whole pane and renders cleanly. (The TUI draws its own status row.)
RUN printf 'set -g status off\n' > /home/by/.tmux.conf \
 && chown by:by /home/by/.tmux.conf

USER by
WORKDIR /workspace

# Warm clj-nrepl-eval's deps for the `by` user (Clojure tools + project deps),
# so the agent's first Clojure eval doesn't stall ~30s downloading them.
RUN cd /tmp && clj-nrepl-eval --discover-ports >/dev/null 2>&1 || true

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
# BY_RESUME_LATEST: UNLIKE BY_WEB_TMUX above, we WANT this inherited by the child
# `by run` — it makes the TUI reattach to the newest persisted session (restored
# from the /workspace volume on resume), falling back to a fresh session on first
# boot. The web-tmux re-entrancy concern doesn't apply (it's not a --web* flag).
#
# BY_USER_ID is a fallback only: the control plane's `start!` always passes
# `-e BY_USER_ID=<session-id>` (per-session memory partitioning under
# ~/.brainyard/memory/<user-id>.db), which overrides this. The "playground"
# default just gives a sane identity if the image is run standalone (e.g. the
# `docker run … brainyard/workspace:dev` smoke test) without that -e.
ENV BY_WEB_BIND=0.0.0.0 \
    BY_WEB_PORT=7681 \
    BY_WEB_SELF=by \
    BY_USER_ID=playground \
    BY_RESUME_LATEST=1

EXPOSE 7681

# entrypoint.sh bootstraps the workspace (git init + `by config --auto`) then
# supervises `by --web-tmux` in a restart loop so the published ttyd port keeps
# serving if the launcher exits. The `--web-tmux` FLAG (not env) launches
# ttyd → tmux → `by run`; the child runs the real TUI. Provider/model/agent
# flags appended by the control plane reach the script as "$@" and are forwarded.
ENTRYPOINT ["/usr/local/bin/entrypoint.sh"]
