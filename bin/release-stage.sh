#!/usr/bin/env bash
# Stage built artifacts into release/ for upload to GitHub Releases.
#
# Inputs (must already exist in the working tree):
#   - projects/agent-tui-app/target/agent-tui-app.jar    (from `bb uberjar:ata`)
#   - projects/agent-tui-app/target/by                   (from `bb native:ata`)
#   - projects/agent-tui-app/scripts/by-wrapper.sh
#   - projects/agent-tui-app/resources/build-version.edn (from `bb version:ata`,
#                                                        baked from `git describe` in THIS repo)
#
# Outputs (written to release/, gitignored — uploaded by the release workflow):
#   - by-<version>.jar
#   - by.jar                            (version-less alias of by-<version>.jar so
#                                        releases/latest/download/by.jar resolves)
#   - by-<version>-<platform>           (e.g. by-0.2.0-macos-arm64)
#   - by-wrapper.sh
#   - SHA256SUMS                        (checksums for the 4 files above)
#   - BUILD-INFO.txt                    (provenance: source commit, version, build time)
#
# Asset names match what bin/install.sh expects — do not rename without
# updating install.sh in lockstep.

set -euo pipefail

REPO_ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
SHIPPING_PROJECT_DIR="${REPO_ROOT}/projects/agent-tui-app"
TARGET_DIR="${SHIPPING_PROJECT_DIR}/target"
RELEASE_DIR="${REPO_ROOT}/release"
VERSION_EDN="${SHIPPING_PROJECT_DIR}/resources/build-version.edn"
WRAPPER_SRC="${SHIPPING_PROJECT_DIR}/scripts/by-wrapper.sh"

log()  { printf '\033[1;34m[release-stage]\033[0m %s\n' "$*"; }
die()  { printf '\033[1;31m[release-stage]\033[0m %s\n' "$*" >&2; exit 1; }

# ── Detect platform (must match bin/install.sh's detect_platform) ───────────

detect_platform() {
  local os arch
  case "$(uname -s)" in
    Darwin) os="macos" ;;
    Linux)  os="linux" ;;
    *) die "Unsupported OS: $(uname -s)" ;;
  esac
  case "$(uname -m)" in
    x86_64|amd64)   arch="amd64" ;;
    arm64|aarch64)  arch="arm64" ;;
    *) die "Unsupported arch: $(uname -m)" ;;
  esac
  echo "${os}-${arch}"
}

# ── Resolve version from build-version.edn (stamped by `bb version:ata`) ────
#
# Upstream's app-version is now baked at build time from `git describe`
# of THIS repo, so a clean release requires:
#   - HEAD sits exactly on a tag (no commits since), and
#   - the working tree is clean (no -dirty suffix).
# Refuse to stage otherwise — those would yield "v0.1.1-3-gabcdef" or
# "v0.1.1-dirty" baked into a public binary, which is misleading.

read_version() {
  [[ -f "${VERSION_EDN}" ]] || die "Missing ${VERSION_EDN} — run 'bb version:ata' (or 'bb build:ata') first"
  local raw
  raw="$(awk 'match($0, /:version "[^"]+"/) {
      print substr($0, RSTART + 11, RLENGTH - 12); exit
    }' "${VERSION_EDN}")"
  [[ -n "${raw}" ]] || die "Could not parse :version from ${VERSION_EDN}"

  case "${raw}" in
    dev)
      die "Version is 'dev' — was the build run without a .git directory? Re-run 'bb version:ata' from a git working tree."
      ;;
    *-dirty)
      die "Version '${raw}' has uncommitted changes — commit or stash, then re-run 'bb build:ata' to restamp."
      ;;
    *-g[0-9a-f]*)
      die "Version '${raw}' has commits past the last tag — tag HEAD (e.g. 'git tag v<X.Y.Z>'), then re-run 'bb build:ata' to restamp."
      ;;
  esac
  # Strip leading 'v' so asset names look like by-0.1.1-…, not by-v0.1.1-…
  echo "${raw#v}"
}

# ── Source provenance from THIS repo's git ──────────────────────────────────

source_commit() { git -C "${REPO_ROOT}" rev-parse HEAD 2>/dev/null || echo "unknown"; }
source_branch() { git -C "${REPO_ROOT}" rev-parse --abbrev-ref HEAD 2>/dev/null || echo "unknown"; }

# ── SHA-256 helper (macOS has shasum, Linux has sha256sum) ──────────────────

sha256_cmd() {
  if command -v sha256sum >/dev/null 2>&1; then
    echo "sha256sum"
  elif command -v shasum >/dev/null 2>&1; then
    echo "shasum -a 256"
  else
    die "Need sha256sum or shasum"
  fi
}

# ── Main ────────────────────────────────────────────────────────────────────

main() {
  local platform version src_commit src_branch
  platform="$(detect_platform)"
  version="$(read_version)"
  src_commit="$(source_commit)"
  src_branch="$(source_branch)"

  local jar_src="${TARGET_DIR}/agent-tui-app.jar"
  local bin_src="${TARGET_DIR}/by"
  [[ -f "${jar_src}" ]]     || die "Missing ${jar_src} — run 'bb uberjar:ata' first"
  [[ -f "${bin_src}" ]]     || die "Missing ${bin_src} — run 'bb native:ata' first"
  [[ -f "${WRAPPER_SRC}" ]] || die "Missing ${WRAPPER_SRC}"

  mkdir -p "${RELEASE_DIR}"

  local jar_dst="${RELEASE_DIR}/by-${version}.jar"
  local jar_alias_dst="${RELEASE_DIR}/by.jar"
  local bin_dst="${RELEASE_DIR}/by-${version}-${platform}"
  local wrapper_dst="${RELEASE_DIR}/by-wrapper.sh"
  local sums_dst="${RELEASE_DIR}/SHA256SUMS"
  local info_dst="${RELEASE_DIR}/BUILD-INFO.txt"

  log "Staging artifacts for version ${version}, platform ${platform}"
  cp -f "${jar_src}"     "${jar_dst}"
  cp -f "${jar_src}"     "${jar_alias_dst}"
  cp -f "${bin_src}"     "${bin_dst}"
  cp -f "${WRAPPER_SRC}" "${wrapper_dst}"
  chmod +x "${bin_dst}" "${wrapper_dst}"

  local sha_cmd
  sha_cmd="$(sha256_cmd)"
  log "Generating SHA256SUMS"
  ( cd "${RELEASE_DIR}" \
      && ${sha_cmd} "by-${version}.jar" "by.jar" "by-${version}-${platform}" "by-wrapper.sh" \
        > "SHA256SUMS" )

  local build_ts
  build_ts="$(date -u +%Y-%m-%dT%H:%M:%SZ)"

  log "Writing BUILD-INFO.txt"
  {
    echo "# Provenance for this release. Read alongside SHA256SUMS."
    echo "# Auto-generated by bin/release-stage.sh — do not edit by hand."
    echo ""
    echo "version:          ${version}"
    echo "platform:         ${platform}"
    echo "built_at:         ${build_ts}"
    echo ""
    echo "source_commit:    ${src_commit}"
    echo "source_branch:    ${src_branch}"
    echo ""
    echo "artifacts:"
    echo "  - by-${version}.jar"
    echo "  - by.jar"
    echo "  - by-${version}-${platform}"
    echo "  - by-wrapper.sh"
    echo "  - SHA256SUMS"
  } > "${info_dst}"

  log "Done. Staged files in ${RELEASE_DIR}:"
  ls -1 "${RELEASE_DIR}" | sed 's/^/  /'
  log "Upload these as assets to the v${version} GitHub Release."
}

main "$@"
