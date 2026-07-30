#!/usr/bin/env bash
# Post-build smoke test for the native `by` binary.
#
# Exists because v0.5.0 shipped an `acp-agent` that was advertised in
# `by agents` and fatal on first use, twice over: acp-client was never
# AOT-compiled into the image (so `requiring-resolve` could not find it), and
# once that was fixed, an unhinted `Writer.write` bound to the char[] overload
# under native-image and killed every ACP turn on its first outbound message.
#
# Neither defect could be caught by `bb test` or by the existing smoke checks.
# Both are native-image-only, and the existing checks (`--help`, `agents`,
# `sessions list`) never spawn a subprocess or write a byte to one. ACP had
# passed live end-to-end verification repeatedly — all of it on the JVM via
# `bb tui:acp`, where runtime source loading and reflective overload resolution
# both behave differently than in an image.
#
# So: exercise the paths that only break in a native binary. Hermetically —
# no LLM, no network, no npx, ~1s.
#
# Usage: bin/smoke-native.sh [path-to-by]     (default: the built target/by)

set -euo pipefail

REPO_ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
BY="${1:-${REPO_ROOT}/projects/agent-tui-app/target/by}"

pass() { printf '\033[1;32m[smoke]\033[0m %s\n' "$*"; }
fail() { printf '\033[1;31m[smoke]\033[0m %s\n' "$*" >&2; exit 1; }
log()  { printf '\033[1;34m[smoke]\033[0m %s\n' "$*"; }

[[ -x "${BY}" ]] || fail "no executable binary at ${BY} — run 'bb native:ata' first"

WORK="$(mktemp -d)"
trap 'rm -rf "${WORK}"' EXIT

log "Smoke-testing ${BY}"

# ── 1. Basic surfaces (cheap; catch a wholesale broken image) ───────────────

"${BY}" --version  >/dev/null 2>&1 || fail "--version failed"
"${BY}" --help     >/dev/null 2>&1 || fail "--help failed"
"${BY}" agents     >/dev/null 2>&1 || fail "agents failed (config + agent registry load)"
pass "cli surfaces: --version, --help, agents"

# ── 2. ACP: subprocess spawn + JSON-RPC write + read + parse ────────────────
#
# `cat` as the backend is the whole trick. The transport spawns it, writes the
# `initialize` request to its stdin, and cat echoes the bytes straight back —
# so the client parses its own request as an inbound message and answers
# "method not found: initialize". That reply is proof the FULL round trip ran:
# resolve → spawn → write → read → parse. A real backend would need npx, a
# network, and credentials; cat needs none and is deterministic.
#
# The negative assertions name the two shipped regressions directly, so a
# reintroduction fails here by name rather than as a vague non-zero exit.

mkdir -p "${WORK}/.brainyard"
cat > "${WORK}/.brainyard/config.edn" <<'EOF'
{:agent {:config {:acp-backend      :claude-code
                  :acp-backend-opts {:command ["cat"]}}}}
EOF

set +e
ACP_OUT="$(cd "${WORK}" && timeout 60 env -u CLAUDECODE \
             BY_PROJECT_DIR="${WORK}" BY_WORKING_DIR="${WORK}" \
             "${BY}" ask -a acp-agent "hi" 2>&1)"
set -e

# Regression 1 — acp-client not compiled into the image (v0.5.1).
if grep -q "needs ai.brainyard/acp-client" <<<"${ACP_OUT}"; then
  fail "ACP regression: acp-client not resolvable in the image.
  A namespace reached only via requiring-resolve is never AOT-compiled, and a
  native image has no runtime compiler to load its source. Require it
  statically. Output was:
${ACP_OUT}"
fi

# Regression 2 — reflective Writer.write bound to char[] (v0.5.1).
if grep -q "cannot be cast to char\[\]" <<<"${ACP_OUT}"; then
  fail "ACP regression: reflective Writer.write bound to the char[] overload.
  Writer declares write(String) AND write(char[]) at arity 1; hint the
  argument. Output was:
${ACP_OUT}"
fi

# Positive assertion — proves the round trip actually happened rather than
# failing early in some new way that merely avoids both strings above.
if ! grep -q "method not found: initialize" <<<"${ACP_OUT}"; then
  fail "ACP round trip did not complete. Expected the echoed initialize
  request to come back as 'method not found: initialize'. Output was:
${ACP_OUT}"
fi
pass "acp-agent: spawn + JSON-RPC write + read + parse"

log "All native smoke checks passed."
