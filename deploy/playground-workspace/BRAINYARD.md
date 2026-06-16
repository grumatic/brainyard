# Brainyard Playground — Workspace Environment

You are the `by` agent running inside a sandboxed, single-tenant Linux container
(the Brainyard Playground — one container per user session). This file describes
your environment and the tools available at the user level. It is reference
guidance, not a task.

## Environment

- **OS / shell**: Ubuntu Linux with `bash` and POSIX coreutils. You run as the
  non-root user `by` (home `/home/by`). **Passwordless `sudo`** is available for
  system-level changes — e.g. `sudo apt-get update && sudo apt-get install <pkg>`
  or `sudo npm install -g <pkg>`.
- **Working directory `/workspace`**: a git repository (initialized on first
  boot) that holds your project tree. Files here **persist across sessions**.
- **Home `/home/by/.brainyard`**: your `by` memory, config, and persisted
  sessions — also persists across sessions.
- **Network**: outbound network is available; you may fetch URLs and install
  packages.
- **Dev servers**: container ports **3000–3010** are published to the host. A dev
  server you start in the workspace (e.g. `npm run dev` on :3000) is reachable
  from the user's browser via the workspace **Ports** menu.

## Tools (invoke via the shell/bash tool)

**Version control & cloud**
- `git` — the workspace is already a repo; commit your work here.
- `gh` — GitHub CLI. Authenticated only if the user set `GH_TOKEN` /
  `GITHUB_TOKEN` in Settings.
- `aws` — AWS CLI v2. Authenticated only if the user set AWS credentials
  (`AWS_PROFILE`, or `AWS_ACCESS_KEY_ID` / `AWS_SECRET_ACCESS_KEY` /
  `AWS_SESSION_TOKEN`) in Settings.

**JavaScript / TypeScript**
- `node`, `npm`, `npx` (Node.js LTS).
- `next` — Next.js installed globally (`npx create-next-app`, `next dev`, …).
- `playwright` — installed globally with a headless **Chromium** already
  downloaded (`PLAYWRIGHT_BROWSERS_PATH=/ms-playwright`). Use for browser
  automation / e2e tests. Other browsers: `sudo playwright install <name>`.

**Python**
- `python3`, `pip`, `venv`.
- `uv` / `uvx` — fast Python package & tool runner; prefer it for installs/runs.

**Clojure**
- `clojure` / `clj`, and `bb` (Babashka).
- `clj-nrepl-eval` — evaluate Clojure against a running nREPL server: start an
  nREPL in your project, then `clj-nrepl-eval --discover-ports` and
  `clj-nrepl-eval -p <port> "<code>"`.

**Search / files / data**
- `rg` (ripgrep), `fd`, `tree`, `jq`, `less`, plus `find` / `grep`.
- `curl`, `wget` for fetching.

**Memory (your own `by` tools, not shell)**
- `memory$recall` — search your long-term memory for relevant past facts.
- `memory$write` / `memory$keep!` — save a durable fact worth remembering across
  sessions. `memory$read`, `memory$forget`, `memory$stats` manage it.
- Memory is partitioned per user and persists under `/home/by/.brainyard`.

## Conventions

- Keep project files under `/workspace` so they persist and surface in the user's
  session.
- **Project-scope guidance** lives in `/workspace/.brainyard/BRAINYARD.md`,
  layered above this user-scope file — put repo-specific instructions there.
- Missing a tool or library? Install it: `uv`/`pip` (Python), `npm i` (Node), or
  `sudo apt-get install …` (system packages).
- Credentials are injected from the user's Settings as environment variables —
  use them, but never print or echo their values.
