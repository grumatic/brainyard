#!/usr/bin/env bash
# Verify a PUBLISHED GitHub Release against its own SHA256SUMS.
#
# Downloads every asset the release actually carries and checks it against the
# manifest that shipped with it. This catches two things a green local build
# cannot:
#
#   - an asset that was staged and checksummed but never uploaded, and
#   - an asset that uploaded corrupted or truncated.
#
# v0.6.0 shipped with the first: the assets were hand-listed at publish time
# and the version-less `by.jar` alias was omitted. `SHA256SUMS` still listed
# it, so `shasum -c` exited 1 for every user who verified their download, and
# the documented `releases/latest/download/by.jar` URL 404'd — on a release
# whose binaries were otherwise fine. Nothing in the build or staging step can
# see that, because it happens after both.
#
# Usage:
#   bin/release-verify.sh              # newest published release
#   bin/release-verify.sh v0.6.0       # a specific tag
#   bin/release-verify.sh --dir release/   # a local directory, no download
#
# `--dir` runs the same checks against files already on disk: use it on
# release/ BEFORE publishing to confirm the staged set is self-consistent, or
# on a directory you downloaded by hand.
#
# Requires: gh (authenticated) unless --dir is given, and sha256sum or shasum.

set -euo pipefail

REPO="${BY_RELEASE_REPO:-grumatic/brainyard}"

log()  { printf '\033[1;34m[release-verify]\033[0m %s\n' "$*"; }
ok()   { printf '\033[1;32m[release-verify]\033[0m %s\n' "$*"; }
die()  { printf '\033[1;31m[release-verify]\033[0m %s\n' "$*" >&2; exit 1; }

sha_check_cmd() {
  if command -v sha256sum >/dev/null 2>&1; then
    echo "sha256sum -c"
  elif command -v shasum >/dev/null 2>&1; then
    echo "shasum -a 256 -c"
  else
    die "Need sha256sum or shasum"
  fi
}

main() {
  local tag="" workdir="" label=""

  if [[ "${1:-}" == "--dir" ]]; then
    [[ -n "${2:-}" ]] || die "Usage: bin/release-verify.sh --dir <path>"
    workdir="$2"
    [[ -d "${workdir}" ]] || die "Not a directory: ${workdir}"
    label="${workdir}"
    log "Verifying local directory ${workdir} (no download)"
  else
    command -v gh >/dev/null 2>&1 || die "Need the gh CLI on PATH."
    tag="${1:-}"
    if [[ -z "${tag}" ]]; then
      tag="$(gh release view --repo "${REPO}" --json tagName --jq .tagName)" \
        || die "Could not resolve the newest release for ${REPO}"
      log "No tag given — using newest published release: ${tag}"
    fi
    label="${tag}"

    workdir="$(mktemp -d)"
    # shellcheck disable=SC2064
    trap "rm -rf '${workdir}'" EXIT

    log "Downloading assets for ${tag} from ${REPO}"
    gh release download "${tag}" --repo "${REPO}" --dir "${workdir}" --clobber \
      >/dev/null 2>&1 || die "Download failed for ${tag}"
  fi

  [[ -f "${workdir}/SHA256SUMS" ]] \
    || die "${label} has no SHA256SUMS — nothing to verify against."

  # Every file the manifest names must have been downloaded. Checking this
  # first turns "could not read" into a precise "this asset was never
  # uploaded", which is the failure that actually shipped.
  local missing=()
  while read -r _sum name; do
    [[ -n "${name}" ]] || continue
    [[ -f "${workdir}/${name}" ]] || missing+=("${name}")
  done < "${workdir}/SHA256SUMS"

  if (( ${#missing[@]} > 0 )); then
    printf '\n'
    local hint
    if [[ -n "${tag}" ]]; then
      # Published release: the fix is to upload the omitted asset.
      hint="Upload with:  gh release upload ${tag} release/${missing[0]} --repo ${REPO}"
    else
      # Local dir: the staged set itself is incomplete — re-stage.
      hint="Re-stage with:  bin/release-stage.sh"
    fi
    die "SHA256SUMS lists $(( ${#missing[@]} )) asset(s) missing from ${label}: ${missing[*]}
    ${hint}"
  fi

  log "Checking $(grep -c . "${workdir}/SHA256SUMS") checksum(s)"
  ( cd "${workdir}" && $(sha_check_cmd) SHA256SUMS ) \
    || die "Checksum mismatch — an asset uploaded corrupted. Re-upload it."

  # The version-less alias is what keeps the documented
  # `releases/latest/download/by.jar` URL working; it is easy to drop because
  # it is the one asset whose name carries no version.
  if [[ -f "${workdir}/by.jar" ]]; then
    ok "by.jar alias present (keeps releases/latest/download/by.jar resolving)"
  else
    log "note: no by.jar alias in this release"
  fi

  ok "${label}: all assets present and checksums match."
}

main "$@"
