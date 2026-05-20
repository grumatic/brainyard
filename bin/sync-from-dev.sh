#!/usr/bin/env bash
# Sync the publishable Polylith subset from the private upstream
# (~/MyDev/brainyard by default) into this public repo.
#
# Manual, idempotent. Never pushes. The operator commits and pushes
# explicitly after reviewing the diff.
#
# See docs/deploy-design.md §3 for the design rationale.

set -euo pipefail

# ── Constants ────────────────────────────────────────────────────────────────

PUBLIC_REPO_ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
DEFAULT_DEV_REPO="${HOME}/Projects/MyDev/brainyard"
BRAINYARD_DEV_REPO="${BRAINYARD_DEV_REPO:-${DEFAULT_DEV_REPO}}"
SHIPPING_PROJECT="agent-tui-app"
BRICK_SET_FILE="${PUBLIC_REPO_ROOT}/bin/.brick-set"

# rsync excludes — applied to every brick and to root-level dirs.
RSYNC_EXCLUDES=(
  --exclude=.git
  --exclude=target/
  --exclude=classes/
  --exclude=.cpcache/
  --exclude=.shadow-cljs/
  --exclude=.lsp/
  --exclude=.calva/
  --exclude=.nrepl-port
  --exclude=node_modules/
  --exclude=benchmark-results/
  --exclude=.brainyard/
  --exclude=.env
  --exclude=.env.*
  --exclude=.DS_Store
  --exclude='*.swp'
  --exclude='*.swo'
  --exclude='*~'
  --exclude='.#*'
)

# Root-level files mirrored verbatim from upstream.
ROOT_LEVEL_FILES=(
  bb.edn
  deps.edn
  workspace.edn
  .sdkmanrc
)

# ── CLI args ────────────────────────────────────────────────────────────────

ALLOW_DIRTY=0
LIST_BRICKS_ONLY=0
ALLOW_BRICK_SET_CHANGE=0
SKIP_VALIDATION=0

usage() {
  cat <<EOF
Usage: bin/sync-from-dev.sh [options]

Sync the publishable Polylith subset from a private upstream repo
into this public repo. Manual, idempotent; never pushes.

Environment:
  BRAINYARD_DEV_REPO   Upstream repo path (default: ~/MyDev/brainyard)

Options:
  --allow-dirty               Allow sync from a dirty upstream working tree
                              (off by default; every public release must be
                              traceable to a specific upstream SHA).
  --list-bricks               Print the resolved brick set and exit.
                              Use for debugging the closure resolver.
  --allow-brick-set-change    Proceed even if the resolved brick set differs
                              from bin/.brick-set. Without this flag, a
                              mismatch aborts the sync (defense against
                              accidentally exposing newly-added components).
  --skip-validation           Skip the post-sync 'bb compile:ata' closure check.
                              Use only when bb/clojure are unavailable locally.
  -h, --help                  Show this help.
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --allow-dirty)             ALLOW_DIRTY=1 ;;
    --list-bricks)             LIST_BRICKS_ONLY=1 ;;
    --allow-brick-set-change)  ALLOW_BRICK_SET_CHANGE=1 ;;
    --skip-validation)         SKIP_VALIDATION=1 ;;
    -h|--help)                 usage; exit 0 ;;
    *) echo "Unknown option: $1" >&2; usage >&2; exit 2 ;;
  esac
  shift
done

# ── Logging helpers ─────────────────────────────────────────────────────────

log()  { printf '\033[1;34m[sync]\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33m[sync]\033[0m %s\n' "$*" >&2; }
die()  { printf '\033[1;31m[sync]\033[0m %s\n' "$*" >&2; exit 1; }

# ── Step 1 — verify upstream ────────────────────────────────────────────────

verify_upstream() {
  log "Verifying upstream at ${BRAINYARD_DEV_REPO}"

  [[ -d "${BRAINYARD_DEV_REPO}" ]] \
    || die "Upstream not found: ${BRAINYARD_DEV_REPO} (set BRAINYARD_DEV_REPO)"

  [[ -d "${BRAINYARD_DEV_REPO}/.git" ]] \
    || die "Upstream is not a git repo: ${BRAINYARD_DEV_REPO}"

  [[ -d "${BRAINYARD_DEV_REPO}/projects/${SHIPPING_PROJECT}" ]] \
    || die "Upstream missing projects/${SHIPPING_PROJECT} — wrong path?"

  if [[ ${ALLOW_DIRTY} -eq 0 ]]; then
    if [[ -n "$(git -C "${BRAINYARD_DEV_REPO}" status --porcelain)" ]]; then
      die "Upstream working tree is dirty. Commit/stash, or pass --allow-dirty."
    fi
  fi
}

upstream_sha()    { git -C "${BRAINYARD_DEV_REPO}" rev-parse HEAD; }
upstream_branch() { git -C "${BRAINYARD_DEV_REPO}" rev-parse --abbrev-ref HEAD; }

# ── Step 2 — resolve brick closure ──────────────────────────────────────────
#
# Walks :local/root edges starting from projects/<SHIPPING_PROJECT>/deps.edn
# until a fixed point. Inspects :deps and the :extra-deps maps of every alias.
# Emits one path per line, relative to the upstream root (e.g.
# "bases/agent-tui", "components/agent"). Sorted, deduplicated, stable.

resolve_brick_set() {
  bb -e "$(cat <<'BB'
(require '[clojure.edn :as edn]
         '[babashka.fs :as fs])

(def upstream  (System/getenv "BRAINYARD_DEV_REPO"))
(def project   (System/getenv "SHIPPING_PROJECT"))

(defn read-deps [path]
  (try (edn/read-string (slurp path))
       (catch Exception _ nil)))

(defn local-roots
  "Collect every :local/root value from a deps.edn map's :deps and
   from each alias's :extra-deps. Resolved to absolute paths anchored
   at deps-edn's directory."
  [deps-map deps-edn-path]
  (let [base-dir   (fs/parent deps-edn-path)
        coord-roots (fn [coord-map]
                      (->> (vals (or coord-map {}))
                           (keep #(when (map? %) (:local/root %)))))
        from-deps   (coord-roots (:deps deps-map))
        from-aliases (->> (vals (or (:aliases deps-map) {}))
                          (mapcat #(coord-roots (:extra-deps %))))]
    (->> (concat from-deps from-aliases)
         (map #(fs/canonicalize (fs/path base-dir %)))
         (map str)
         distinct)))

(defn close-bricks
  "Return the set of all brick directories transitively required by
   the seed deps.edn, as absolute paths."
  [seed-deps-edn]
  (loop [frontier #{(str (fs/canonicalize (fs/parent seed-deps-edn)))}
         visited  #{}]
    (let [new-bricks (clojure.set/difference frontier visited)]
      (if (empty? new-bricks)
        visited
        (let [next-frontier
              (->> new-bricks
                   (mapcat (fn [brick-dir]
                             (when-let [m (read-deps (str (fs/path brick-dir "deps.edn")))]
                               (local-roots m (str (fs/path brick-dir "deps.edn"))))))
                   (filter #(fs/exists? (fs/path % "deps.edn")))
                   set)]
          (recur next-frontier
                 (clojure.set/union visited new-bricks)))))))

(defn drop-subpaths
  "If X/foo and X are both in the set, drop X/foo — rsyncing the
   parent already covers the child. Operates on relative paths."
  [paths]
  (let [s (set paths)]
    (->> paths
         (remove (fn [p]
                   (some (fn [ancestor]
                           (and (not= ancestor p)
                                (clojure.string/starts-with? p (str ancestor "/"))))
                         s))))))

(let [seed   (str (fs/path upstream "projects" project "deps.edn"))
      bricks (close-bricks seed)
      ;; Drop the seed project itself — we handle it separately.
      seed-dir (str (fs/canonicalize (fs/parent seed)))
      brick-rels (->> (disj bricks seed-dir)
                      (map #(str (fs/relativize upstream %)))
                      (filter #(or (clojure.string/starts-with? % "bases/")
                                   (clojure.string/starts-with? % "components/")))
                      sort
                      drop-subpaths)]
  (doseq [b brick-rels] (println b)))
BB
)"
}

# ── Step 3 — brick set diff gate ────────────────────────────────────────────

check_brick_set() {
  local resolved="$1"  # path to a file with the resolved brick list

  if [[ ! -f "${BRICK_SET_FILE}" ]]; then
    warn "No committed brick set at bin/.brick-set — accepting the resolved set as the baseline."
    warn "Run: cp \"${resolved}\" \"${BRICK_SET_FILE}\" && git add bin/.brick-set"
    return 0
  fi

  if diff -u "${BRICK_SET_FILE}" "${resolved}" > /dev/null; then
    log "Brick set matches bin/.brick-set."
    return 0
  fi

  warn "Resolved brick set differs from bin/.brick-set:"
  diff -u "${BRICK_SET_FILE}" "${resolved}" >&2 || true
  if [[ ${ALLOW_BRICK_SET_CHANGE} -eq 0 ]]; then
    die "Refusing to proceed. Re-run with --allow-brick-set-change and update bin/.brick-set after review."
  fi
  warn "Continuing because --allow-brick-set-change was passed."
}

# ── Step 4 — rsync subtrees ─────────────────────────────────────────────────

sync_dir() {
  local rel="$1"
  local src="${BRAINYARD_DEV_REPO}/${rel}"
  local dst="${PUBLIC_REPO_ROOT}/${rel}"

  [[ -d "${src}" ]] || die "Missing upstream dir: ${rel}"
  mkdir -p "$(dirname -- "${dst}")"
  rsync -a --delete "${RSYNC_EXCLUDES[@]}" "${src}/" "${dst}/"
}

sync_file() {
  local rel="$1"
  local src="${BRAINYARD_DEV_REPO}/${rel}"
  local dst="${PUBLIC_REPO_ROOT}/${rel}"

  if [[ ! -f "${src}" ]]; then
    warn "Skipping missing root file: ${rel}"
    return 0
  fi
  mkdir -p "$(dirname -- "${dst}")"
  cp -f "${src}" "${dst}"
}

# ── Step 5 — trim workspace.edn ─────────────────────────────────────────────
#
# Keep only :projects entries that exist in the mirrored set. Without
# this, `bb poly:check` in the public repo fails on missing bricks.

trim_workspace_edn() {
  local ws="${PUBLIC_REPO_ROOT}/workspace.edn"
  [[ -f "${ws}" ]] || die "workspace.edn missing after sync"

  bb -e "$(cat <<'BB'
(require '[clojure.edn :as edn]
         '[clojure.pprint :as pp]
         '[clojure.string :as str])

(def ws-path (str (System/getenv "PUBLIC_REPO_ROOT") "/workspace.edn"))
(def keep-project (System/getenv "SHIPPING_PROJECT"))

(let [m   (edn/read-string (slurp ws-path))
      ps  (:projects m)
      kept (select-keys ps [keep-project])
      m'  (assoc m :projects kept)]
  (spit ws-path (with-out-str (pp/pprint m'))))
BB
)"
  log "Trimmed workspace.edn to :projects {\"${SHIPPING_PROJECT}\" …}"
}

# ── Step 6 — SYNCED-FROM.txt ────────────────────────────────────────────────

write_synced_from() {
  local resolved="$1"
  local out="${PUBLIC_REPO_ROOT}/SYNCED-FROM.txt"
  local sha branch ts user

  sha="$(upstream_sha)"
  branch="$(upstream_branch)"
  ts="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  user="$(whoami)"

  {
    echo "# Provenance of the mirrored Polylith subset."
    echo "# Auto-generated by bin/sync-from-dev.sh — do not edit by hand."
    echo ""
    echo "upstream_sha:    ${sha}"
    echo "upstream_branch: ${branch}"
    echo "synced_at:       ${ts}"
    echo "synced_by:       ${user}"
    echo "shipping_project: projects/${SHIPPING_PROJECT}"
    echo ""
    echo "Resolved bricks (closure of :local/root from projects/${SHIPPING_PROJECT}/deps.edn):"
    sed 's/^/  - /' "${resolved}"
  } > "${out}"

  log "Wrote ${out}"
}

# ── Step 7 — closure validation ─────────────────────────────────────────────

validate_closure() {
  if [[ ${SKIP_VALIDATION} -eq 1 ]]; then
    warn "Skipping closure validation (--skip-validation)."
    return 0
  fi

  if ! command -v bb >/dev/null 2>&1; then
    warn "bb not found; cannot run 'bb compile:ata' closure check. Skipping."
    return 0
  fi

  log "Running 'bb compile:ata' as closure validation (this may take a minute)…"
  if ( cd "${PUBLIC_REPO_ROOT}" && bb compile:ata ); then
    log "Closure validation passed."
  else
    die "Closure validation failed. The mirrored brick set is likely incomplete — inspect the AOT error and re-resolve."
  fi
}

# ── Main ────────────────────────────────────────────────────────────────────

main() {
  verify_upstream

  log "Resolving brick closure from projects/${SHIPPING_PROJECT}/deps.edn …"
  local tmp_bricks
  tmp_bricks="$(mktemp -t brainyard-bricks.XXXXXX)"
  trap "rm -f '${tmp_bricks}'" EXIT

  BRAINYARD_DEV_REPO="${BRAINYARD_DEV_REPO}" \
  SHIPPING_PROJECT="${SHIPPING_PROJECT}" \
  resolve_brick_set > "${tmp_bricks}"

  log "Resolved $(wc -l < "${tmp_bricks}" | tr -d ' ') brick(s):"
  sed 's/^/  /' "${tmp_bricks}"

  if [[ ${LIST_BRICKS_ONLY} -eq 1 ]]; then
    exit 0
  fi

  check_brick_set "${tmp_bricks}"

  # rsync the shipping project + every resolved brick.
  log "Mirroring shipping project: projects/${SHIPPING_PROJECT}"
  sync_dir "projects/${SHIPPING_PROJECT}"

  while IFS= read -r brick; do
    [[ -n "${brick}" ]] || continue
    log "Mirroring brick: ${brick}"
    sync_dir "${brick}"
  done < "${tmp_bricks}"

  # Root-level files
  for f in "${ROOT_LEVEL_FILES[@]}"; do
    log "Mirroring root file: ${f}"
    sync_file "${f}"
  done

  # .clj-kondo/ if present
  if [[ -d "${BRAINYARD_DEV_REPO}/.clj-kondo" ]]; then
    log "Mirroring .clj-kondo/"
    rsync -a --delete "${RSYNC_EXCLUDES[@]}" \
      "${BRAINYARD_DEV_REPO}/.clj-kondo/" "${PUBLIC_REPO_ROOT}/.clj-kondo/"
  fi

  # docs/upstream-readme.md (for reference; not the public README)
  if [[ -f "${BRAINYARD_DEV_REPO}/projects/${SHIPPING_PROJECT}/TUI.md" ]]; then
    log "Mirroring projects/${SHIPPING_PROJECT}/TUI.md → docs/upstream-readme.md"
    mkdir -p "${PUBLIC_REPO_ROOT}/docs"
    cp -f "${BRAINYARD_DEV_REPO}/projects/${SHIPPING_PROJECT}/TUI.md" \
          "${PUBLIC_REPO_ROOT}/docs/upstream-readme.md"
  fi

  PUBLIC_REPO_ROOT="${PUBLIC_REPO_ROOT}" \
  SHIPPING_PROJECT="${SHIPPING_PROJECT}" \
  trim_workspace_edn

  write_synced_from "${tmp_bricks}"

  validate_closure

  log "Sync complete. Review with: git -C \"${PUBLIC_REPO_ROOT}\" status"
  log "When ready: commit (chore: sync from upstream @ $(upstream_sha | cut -c1-12)) and push."
  log "This script never pushes."
}

main "$@"
