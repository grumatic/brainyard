#!/usr/bin/env bash
# Publish the self-contained tutorials page to the gh-pages branch (GitHub Pages).
#
# Takes docs/tutorials/output/index.html (generate it with `bb tutorial:output`)
# and force-pushes it — plus a .nojekyll marker — as a single-commit orphan
# gh-pages branch. Served at https://<owner>.github.io/<repo>/.
#
# Uses git plumbing only: it never touches your working tree or local branches,
# so it is safe to run from any branch with uncommitted changes.
#
# PUBLISHES PUBLICLY. Usage: bash scripts/asciinema/publish-pages.sh [remote]
#   remote — git remote to push to (default: origin)

set -euo pipefail

REPO_ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "${REPO_ROOT}"

REMOTE="${1:-origin}"
PAGE="docs/tutorials/output/index.html"

log() { printf '\033[1;34m[publish-pages]\033[0m %s\n' "$*"; }
die() { printf '\033[1;31m[publish-pages]\033[0m %s\n' "$*" >&2; exit 1; }

[[ -f "${PAGE}" ]] || die "Missing ${PAGE} — run 'bb tutorial:output' first."

# Hash the page (and an empty .nojekyll, so Pages serves the raw HTML as-is)
# into the object store, assemble a tree, and create an orphan commit.
blob_html="$(git hash-object -w "${PAGE}")"
blob_nojekyll="$(git hash-object -w --stdin </dev/null)"

tree="$(printf '100644 blob %s\t.nojekyll\n100644 blob %s\tindex.html\n' \
          "${blob_nojekyll}" "${blob_html}" | git mktree)"

stamp="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
commit="$(git commit-tree "${tree}" -m "Publish tutorials page (${stamp})")"

log "Force-pushing ${commit} -> ${REMOTE}/gh-pages"
git push -f "${REMOTE}" "${commit}:refs/heads/gh-pages"

# Best-effort: derive the Pages URL from the remote (owner/repo), pure bash.
if remote_url="$(git remote get-url "${REMOTE}" 2>/dev/null)"; then
  path="${remote_url%.git}"      # strip trailing .git
  repo="${path##*/}"            # last path segment
  rest="${path%/*}"            # everything before the repo
  owner="${rest##*[:/]}"        # segment after the last ':' or '/'
  [[ -n "${owner}" && -n "${repo}" ]] \
    && log "Published. Live shortly at: https://${owner}.github.io/${repo}/"
fi
log "Done. (gh-pages is a generated, single-commit orphan branch.)"
