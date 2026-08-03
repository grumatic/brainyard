#!/usr/bin/env bash
# Interoperability check: brainyard's A2A client against the OFFICIAL
# `a2a-sdk` Python implementation.
#
# Exists because nothing else can catch a wire-dialect change. Brainyard
# shipped A2A speaking v0.3 while the ecosystem had already moved to v1.0 —
# a materially different binding (PascalCase methods, a required
# `A2A-Version` header, proto enum values, no `Part` discriminator, a wrapped
# result envelope, and `blocking` replaced by its own logical negation
# `returnImmediately`). Every unit test passed the whole time, because
# fixtures encode what we already believe. Only a genuinely independent
# implementation disagrees with us.
#
# Deliberately NOT part of `bb test`: it needs Python and network access on
# first run, and it clones a third-party repo. Same treatment as the ACP
# real-LLM verification.
#
# Usage: bin/a2a-interop.sh [--port N] [--keep]
#          --port N   port for the sample agent (default 9998)
#          --keep     leave the provisioned venv in place for a faster re-run

set -euo pipefail

REPO_ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
PORT=9998
KEEP=0
CACHE="${TMPDIR:-/tmp}/brainyard-a2a-interop"
SAMPLE="${CACHE}/samples/python/agents/helloworld"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --port) PORT="$2"; shift 2 ;;
    --keep) KEEP=1; shift ;;
    *) echo "unknown arg: $1" >&2; exit 2 ;;
  esac
done

pass() { printf '\033[1;32m[interop]\033[0m %s\n' "$*"; }
fail() { printf '\033[1;31m[interop]\033[0m %s\n' "$*" >&2; exit 1; }
log()  { printf '\033[1;34m[interop]\033[0m %s\n' "$*"; }
skip() { printf '\033[1;33m[interop]\033[0m SKIPPED: %s\n' "$*"; exit 0; }

# ── Preconditions ───────────────────────────────────────────────────────────
# Skip rather than fail: a missing Python is an absent capability, not a
# broken build, and this must not block anyone who simply does not have it.

command -v python3 >/dev/null 2>&1 || skip "python3 not on PATH"
command -v git     >/dev/null 2>&1 || skip "git not on PATH"

# ── Provision the official sample (cached across runs) ──────────────────────

if [[ ! -d "${SAMPLE}/.venv" ]]; then
  log "provisioning the official a2a-samples helloworld agent (first run)"
  rm -rf "${CACHE}"
  git clone --depth 1 --filter=blob:none --sparse \
      https://github.com/a2aproject/a2a-samples.git "${CACHE}" >/dev/null 2>&1 \
    || skip "could not clone a2a-samples (offline?)"
  (cd "${CACHE}" && git sparse-checkout set samples/python/agents/helloworld >/dev/null 2>&1) \
    || fail "sparse-checkout failed"
  python3 -m venv "${SAMPLE}/.venv" || fail "venv creation failed"
  "${SAMPLE}/.venv/bin/pip" install -q -r "${SAMPLE}/requirements.txt" \
    || skip "could not install a2a-sdk (offline?)"
else
  log "reusing the provisioned sample at ${SAMPLE}"
fi

SDK_VERSION="$("${SAMPLE}/.venv/bin/pip" show a2a-sdk 2>/dev/null \
                 | awk '/^Version:/{print $2}')"
[[ -n "${SDK_VERSION}" ]] || fail "a2a-sdk not installed in the sample venv"
log "official a2a-sdk ${SDK_VERSION}"

# ── Start it ────────────────────────────────────────────────────────────────

AGENT_LOG="$(mktemp)"
( cd "${SAMPLE}" && A2A_PORT="${PORT}" ./.venv/bin/python __main__.py ) \
  >"${AGENT_LOG}" 2>&1 &
AGENT_PID=$!

cleanup() {
  kill "${AGENT_PID}" 2>/dev/null || true
  wait "${AGENT_PID}" 2>/dev/null || true
  rm -f "${AGENT_LOG}"
  [[ "${KEEP}" -eq 1 ]] || rm -rf "${CACHE}"
}
trap cleanup EXIT

# The sample hardcodes 9999 unless it honours A2A_PORT; probe whichever answers.
for candidate in "${PORT}" 9999; do
  for _ in $(seq 1 40); do
    if curl -s --max-time 2 -o /dev/null \
         "http://127.0.0.1:${candidate}/.well-known/agent-card.json" 2>/dev/null; then
      PORT="${candidate}"; break 2
    fi
    sleep 1
  done
done

curl -s --max-time 3 -o /dev/null \
  "http://127.0.0.1:${PORT}/.well-known/agent-card.json" 2>/dev/null \
  || { cat "${AGENT_LOG}" >&2; fail "sample agent did not come up"; }
pass "sample agent listening on 127.0.0.1:${PORT}"

# ── Drive OUR client against it ─────────────────────────────────────────────

cd "${REPO_ROOT}/components/a2a-client"
RESULT="$(clojure -M:test -e "
(require '[ai.brainyard.a2a-client.interface :as c])
(c/invalidate-card-cache!) (c/reset-peers!)
(let [url \"http://127.0.0.1:${PORT}\"
      {:keys [error peer]} (c/connect! {:name \"interop\" :url url})]
  (when error (println \"FAIL connect:\" error) (System/exit 1))
  (println \"OK connect dialect=\" (:dialect peer) \"skills=\" (pr-str (:skills peer)))
  (let [p (c/get-peer \"interop\")
        out (c/send-message! p \"interop ping\" :blocking? true)]
    (when (:error out) (println \"FAIL send:\" (:error out)) (System/exit 1))
    (when (clojure.string/blank? (str (:answer out)))
      (println \"FAIL send: empty answer\") (System/exit 1))
    (println \"OK send answer=\" (pr-str (:answer out)) \"state=\" (:state out)))
  (let [p (c/get-peer \"interop\") !e (atom []) !d (promise)
        h (c/stream-message! p \"interop stream\"
            {:on-event #(swap! !e conj %) :on-close (fn [] (deliver !d true))})]
    (deref !d 60000 nil)
    (try ((:stop! h)) (catch Throwable _ nil))
    (let [{:keys [events]} (c/translate-all @!e)
          kinds (set (map :event events))]
      (when (empty? @!e) (println \"FAIL stream: no frames\") (System/exit 1))
      (when-not (contains? kinds :a2a/task-terminal)
        (println \"FAIL stream: no terminal descriptor, got\" (pr-str kinds))
        (System/exit 1))
      (println \"OK stream frames=\" (count @!e) \"descriptors=\" (pr-str (vec kinds))))))
(shutdown-agents)
(System/exit 0)
" 2>&1)" || { echo "${RESULT}" >&2; fail "client run failed"; }

echo "${RESULT}" | grep -E '^(OK|FAIL)' || true

# An explicit `if`, not `grep -q … && fail`. Under `set -e` that form leaves
# the compound's exit status as grep's — 1 on the SUCCESS path, where no FAIL
# line was found — which is a coin flip on whether the script reports success.
if echo "${RESULT}" | grep -q '^FAIL'; then
  fail "interop check failed"
fi

# Belt and braces: the client prints one OK line per stage. Fewer means a
# stage was skipped rather than failed, which no FAIL grep would catch.
OK_COUNT="$(echo "${RESULT}" | grep -c '^OK' || true)"
[[ "${OK_COUNT}" -eq 3 ]] || fail "expected 3 OK stages, got ${OK_COUNT}"

pass "connect + card + SendMessage + SendStreamingMessage against a2a-sdk ${SDK_VERSION}"
log  "All A2A interop checks passed."
