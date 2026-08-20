#!/usr/bin/env bash
set -uo pipefail

# bench-cold-start.sh — MEASUREMENT-ONLY cold-start harness for `bb tui`.
#
# Measures two variants of JVM cold start for projects/agent-tui-app:
#   :e2e          -> clojure ... -M${ALIASES} -m ai.brainyard.agent-tui-app.main --help
#   :require-main -> clojure ... -M${ALIASES} -e "(require 'ai.brainyard.agent-tui-app.main)"
#
# The DEFAULT invocation (no -a) is byte-identical in method to the recorded
# a1 baseline (e2e median 7.097s; require-of-main median 7.180s), so later
# runs are directly comparable. This script changes nothing in the repo.

cd "$(dirname "$0")/.." || { echo "BENCH ERROR: cannot cd to repo root" >&2; exit 1; }

RUNS=5
LABEL="baseline"
ALIASES=""
MARKER="BASELINE E2E MEDIAN"

usage() {
  cat <<'USAGE'
Usage: scripts/bench-cold-start.sh [-n RUNS] [-l LABEL] [-a ALIASES] [-k MARKER] [-h]

  -n RUNS     number of runs per variant            (default: 5)
  -l LABEL    label recorded in the output maps     (default: baseline)
  -a ALIASES  alias suffix appended directly to -M  (default: "", e.g. :aot-dev)
  -k MARKER   greppable marker prefix on line 3     (default: BASELINE E2E MEDIAN)
  -h          print this help and exit 0

Output (stdout, exactly 3 lines):
  {:variant :e2e :label "..." :aliases "..." :runs N :times [...] :median M}
  {:variant :require-main :label "..." :aliases "..." :runs N :times [...] :median M}
  <MARKER>: <e2e-median>s

Examples:
  scripts/bench-cold-start.sh
  scripts/bench-cold-start.sh -n 5 -l aot-dev -a :aot-dev -k 'A2 MEASURED'
USAGE
}

while getopts ":n:l:a:k:h" opt; do
  case "$opt" in
    n) RUNS="$OPTARG" ;;
    l) LABEL="$OPTARG" ;;
    a) ALIASES="$OPTARG" ;;
    k) MARKER="$OPTARG" ;;
    h) usage; exit 0 ;;
    \?) echo "BENCH ERROR: unknown option -$OPTARG" >&2; usage >&2; exit 2 ;;
    :)  echo "BENCH ERROR: option -$OPTARG requires an argument" >&2; exit 2 ;;
  esac
done
shift $((OPTIND - 1))

case "$RUNS" in
  ''|*[!0-9]*) echo "BENCH ERROR: -n must be a positive integer (got '$RUNS')" >&2; exit 2 ;;
esac
[ "$RUNS" -ge 1 ] || { echo "BENCH ERROR: -n must be >= 1 (got '$RUNS')" >&2; exit 2; }

command -v python3 >/dev/null 2>&1 || { echo "BENCH ERROR: python3 not found (needed for sub-ms timing)" >&2; exit 1; }
command -v clojure >/dev/null 2>&1 || { echo "BENCH ERROR: clojure not found" >&2; exit 1; }

# Mirror the `bb tui` task body: load .env before any timing run, WITHOUT
# clobbering variables the caller already exported. Snapshot the exported
# environment, source .env normally (so ${VAR} interpolation and quoting still
# work), then re-apply the snapshot so a real shell variable wins — matching
# the shipped binary's dotenv precedence. See `load-dotenv` in bb.edn.
__env_before=$(export -p)
if [ -f .env ]; then
  set -a
  . ./.env
  set +a
fi
eval "$__env_before" 2>/dev/null
unset __env_before

median() {
  python3 -c '
import sys
xs = sorted(float(x) for x in sys.argv[1:])
n = len(xs)
print("%.3f" % (xs[n // 2] if n % 2 else (xs[n // 2 - 1] + xs[n // 2]) / 2.0))
' "$@"
}

# bench <variant> -> fills global TIMES (no warm-up run is discarded).
# Each run is timed INSIDE a single python3 process that itself launches the
# command via subprocess, so both perf_counter endpoints AND the child exit
# code come from one process (fixes the cross-process perf_counter bug that
# produced meaningless ~0.000s / negative times). Output suppression is handled
# by subprocess DEVNULL (equivalent to the former >/dev/null 2>&1).
TIMES=""
bench() {
  variant="$1"
  TIMES=""
  i=1
  while [ "$i" -le "$RUNS" ]; do
    case "$variant" in
      e2e)          CMD="( cd projects/agent-tui-app && clojure -J--enable-native-access=ALL-UNNAMED -M${ALIASES} -m ai.brainyard.agent-tui-app.main --help )" ;;
      require-main) CMD="( cd projects/agent-tui-app && clojure -J--enable-native-access=ALL-UNNAMED -M${ALIASES} -e \"(require 'ai.brainyard.agent-tui-app.main)\" )" ;;
      *) echo "BENCH ERROR: unknown variant $variant" >&2; exit 1 ;;
    esac
    read rc t < <(python3 -c 'import time,subprocess,sys; t=time.perf_counter(); rc=subprocess.run(["bash","-c",sys.argv[1]],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL).returncode; print(rc, f"{time.perf_counter()-t:.3f}")' "$CMD")
    if [ "$rc" -ne 0 ]; then
      echo "BENCH ERROR: $variant run $i exit $rc" >&2
      exit 1
    fi
    if [ -z "$TIMES" ]; then TIMES="$t"; else TIMES="$TIMES $t"; fi
    i=$((i + 1))
  done
}

emit() {
  printf '{:variant :%s :label "%s" :aliases "%s" :runs %d :times [%s] :median %s}\n' \
    "$1" "$LABEL" "$ALIASES" "$RUNS" "$2" "$3"
}

bench e2e
E2E_TIMES="$TIMES"
E2E_MEDIAN=$(median $E2E_TIMES)

bench require-main
REQ_TIMES="$TIMES"
REQ_MEDIAN=$(median $REQ_TIMES)

emit "e2e" "$E2E_TIMES" "$E2E_MEDIAN"
emit "require-main" "$REQ_TIMES" "$REQ_MEDIAN"
printf '%s: %ss\n' "$MARKER" "$E2E_MEDIAN"

exit 0
