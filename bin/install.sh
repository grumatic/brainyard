#!/usr/bin/env bash
# Brainyard `by` installer.
#
# Downloads the native binary + wrapper script (+ optional uberjar) from a
# GitHub Release and installs them under ~/.local/bin (or BY_INSTALL_DIR).
#
# Usage (one-liner):
#   curl -fsSL https://raw.githubusercontent.com/grumatic/brainyard/main/bin/install.sh | bash
#
# Usage (advanced):
#   BY_VERSION=v0.1.0 bash install.sh           # pin a specific release
#   bash install.sh --with-jar                   # also install by.jar (enables BY_JAR=1)
#   bash install.sh --prefix=/usr/local          # different install location
#   bash install.sh --no-verify                  # skip SHA-256 (not recommended)
#
# See docs/install.md for full options.

set -euo pipefail

# ── Defaults ────────────────────────────────────────────────────────────────

REPO_OWNER="grumatic"
REPO_NAME="brainyard"
INSTALL_DIR="${HOME}/.local/bin"
WITH_JAR=0
VERIFY=1
VERSION="${BY_VERSION:-}"

# Allow override of the download base for local testing.
# Defaults to GitHub Releases URLs for the resolved version.
DOWNLOAD_BASE="${BY_DOWNLOAD_BASE:-}"

# ── Logging ─────────────────────────────────────────────────────────────────

log()  { printf '\033[1;34m[install]\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33m[install]\033[0m %s\n' "$*" >&2; }
die()  { printf '\033[1;31m[install]\033[0m %s\n' "$*" >&2; exit 1; }

# ── CLI args ────────────────────────────────────────────────────────────────

usage() {
  cat <<EOF
Usage: install.sh [options]

Options:
  --prefix=DIR           Install under DIR/bin (default: ~/.local)
  --install-dir=DIR      Install directly into DIR (default: ~/.local/bin)
  --with-jar             Also download and install by.jar (enables BY_JAR=1 mode)
  --no-verify            Skip SHA-256 verification (not recommended)
  -h, --help             Show this help

Environment:
  BY_VERSION             Release tag to install (default: latest)
  BY_INSTALL_DIR         Equivalent to --install-dir
  BY_DOWNLOAD_BASE       Override the asset URL base (for local testing)
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --prefix=*)        INSTALL_DIR="${1#*=}/bin" ;;
    --install-dir=*)   INSTALL_DIR="${1#*=}" ;;
    --with-jar)        WITH_JAR=1 ;;
    --no-verify)       VERIFY=0 ;;
    -h|--help)         usage; exit 0 ;;
    *) die "Unknown option: $1 — see --help" ;;
  esac
  shift
done

# Honor BY_INSTALL_DIR if --install-dir was not passed.
if [[ -n "${BY_INSTALL_DIR:-}" ]]; then
  INSTALL_DIR="${BY_INSTALL_DIR}"
fi

# ── Tool checks ─────────────────────────────────────────────────────────────

require_cmd() {
  command -v "$1" >/dev/null 2>&1 || die "Missing required command: $1"
}

require_cmd curl
require_cmd uname

# Pick a SHA-256 verifier — macOS has shasum, Linux usually has sha256sum.
if [[ ${VERIFY} -eq 1 ]]; then
  if command -v shasum >/dev/null 2>&1; then
    SHA_CMD="shasum -a 256"
  elif command -v sha256sum >/dev/null 2>&1; then
    SHA_CMD="sha256sum"
  else
    die "Need shasum or sha256sum for verification. Re-run with --no-verify to skip."
  fi
fi

# ── Detect OS/arch ──────────────────────────────────────────────────────────

detect_platform() {
  local os arch
  case "$(uname -s)" in
    Darwin) os="macos" ;;
    Linux)  os="linux" ;;
    *) die "Unsupported OS: $(uname -s). Use the uberjar (java -jar by.jar) instead." ;;
  esac
  case "$(uname -m)" in
    x86_64|amd64)        arch="amd64" ;;
    arm64|aarch64)       arch="arm64" ;;
    *) die "Unsupported arch: $(uname -m). Use the uberjar instead." ;;
  esac
  echo "${os}-${arch}"
}

PLATFORM="$(detect_platform)"
log "Detected platform: ${PLATFORM}"

# ── Resolve version ─────────────────────────────────────────────────────────

if [[ -z "${VERSION}" ]]; then
  log "Resolving latest release tag…"
  VERSION="$(curl -fsSL \
    "https://api.github.com/repos/${REPO_OWNER}/${REPO_NAME}/releases/latest" \
    2>/dev/null \
    | grep -E '"tag_name":' \
    | head -1 \
    | sed -E 's/.*"tag_name": *"([^"]+)".*/\1/')" || true

  if [[ -z "${VERSION}" ]]; then
    die "Could not resolve latest release tag. Try: BY_VERSION=v0.1.0 bash install.sh"
  fi
fi

# Strip the leading 'v' to derive the version-string used in asset names.
VSTRIPPED="${VERSION#v}"

log "Installing ${REPO_NAME} ${VERSION} for ${PLATFORM}"

# ── Compute asset URLs ──────────────────────────────────────────────────────

BIN_ASSET="by-${VSTRIPPED}-${PLATFORM}"
JAR_ASSET="by-${VSTRIPPED}.jar"
WRAPPER_ASSET="by-wrapper.sh"
SUMS_ASSET="SHA256SUMS"

if [[ -z "${DOWNLOAD_BASE}" ]]; then
  DOWNLOAD_BASE="https://github.com/${REPO_OWNER}/${REPO_NAME}/releases/download/${VERSION}"
fi

# ── Download to a temp dir ──────────────────────────────────────────────────

TMPDIR="$(mktemp -d -t brainyard-install.XXXXXX)"
trap "rm -rf '${TMPDIR}'" EXIT

# Returns curl's exit status; callers decide whether a failure is fatal.
download() {
  local name="$1"
  local url="${DOWNLOAD_BASE}/${name}"
  log "Downloading ${name}…"
  curl -fL --progress-bar -o "${TMPDIR}/${name}" "${url}"
}

# Probe the native binary first. We currently publish only macOS arm64, so
# Linux and Intel-Mac users won't find a binary for their platform — fall back
# to the JVM uberjar (requires a JDK on PATH) instead of dying on a 404.
NATIVE=1
if ! download "${BIN_ASSET}"; then
  NATIVE=0
  log "No native binary for ${PLATFORM} in ${VERSION}; falling back to the JVM uberjar."
  require_cmd java
fi

download "${WRAPPER_ASSET}" || die "Download failed: ${WRAPPER_ASSET}"
if [[ ${VERIFY} -eq 1 ]]; then
  download "${SUMS_ASSET}" || die "Download failed: ${SUMS_ASSET}"
fi
# The jar is required in fallback mode, and optional (--with-jar) alongside a
# native install.
if [[ ${NATIVE} -eq 0 || ${WITH_JAR} -eq 1 ]]; then
  download "${JAR_ASSET}" || die "Download failed: ${JAR_ASSET}"
fi

# ── Verify checksums ────────────────────────────────────────────────────────

if [[ ${VERIFY} -eq 1 ]]; then
  log "Verifying SHA-256 checksums…"
  pushd "${TMPDIR}" > /dev/null
  # Build a SHA file restricted to what we actually downloaded so unrelated
  # entries don't trip --ignore-missing on platforms whose shasum is picky.
  local_files=("${WRAPPER_ASSET}")
  [[ ${NATIVE} -eq 1 ]] && local_files+=("${BIN_ASSET}")
  [[ ${NATIVE} -eq 0 || ${WITH_JAR} -eq 1 ]] && local_files+=("${JAR_ASSET}")
  if ! ${SHA_CMD} -c "${SUMS_ASSET}" --ignore-missing > /dev/null 2>&1; then
    warn "Checksum verification failed. Aborting."
    ${SHA_CMD} -c "${SUMS_ASSET}" --ignore-missing >&2 || true
    popd > /dev/null
    exit 1
  fi
  popd > /dev/null
  log "Checksums OK."
fi

# ── Install ─────────────────────────────────────────────────────────────────

mkdir -p "${INSTALL_DIR}"

if [[ ${NATIVE} -eq 1 ]]; then
  install -m 755 "${TMPDIR}/${WRAPPER_ASSET}" "${INSTALL_DIR}/by"
  install -m 755 "${TMPDIR}/${BIN_ASSET}"     "${INSTALL_DIR}/by-bin"
  if [[ ${WITH_JAR} -eq 1 ]]; then
    install -m 644 "${TMPDIR}/${JAR_ASSET}" "${INSTALL_DIR}/by.jar"
  fi
else
  # JVM fallback: install the canonical wrapper as `by-wrapper` (it owns the
  # .env discovery + BY_JAR handling), the uberjar as `by.jar`, and a small
  # `by` shim that forces BY_JAR=1 so `by` runs the jar by default.
  install -m 755 "${TMPDIR}/${WRAPPER_ASSET}" "${INSTALL_DIR}/by-wrapper"
  install -m 644 "${TMPDIR}/${JAR_ASSET}"     "${INSTALL_DIR}/by.jar"
  cat > "${INSTALL_DIR}/by" <<EOF
#!/usr/bin/env bash
# Brainyard launcher — JVM uberjar mode (no native binary for this platform).
# Forces BY_JAR=1 and delegates to by-wrapper, which sources a nearby .env and
# then exec's \`java -jar by.jar\`.
export BY_JAR=1
exec "${INSTALL_DIR}/by-wrapper" "\$@"
EOF
  chmod 755 "${INSTALL_DIR}/by"
fi

# macOS: re-apply an ad-hoc codesign to the copied native binary.
#
# native-image emits a `linker-signed,adhoc` signature that is bound to the
# original inode. After the cp/install above the kernel won't recognize the
# copy and AMFI will SIGKILL it on first launch. Re-signing produces a fresh
# ad-hoc signature on the installed file.
if [[ ${NATIVE} -eq 1 && "$(uname -s)" == "Darwin" ]]; then
  if command -v codesign >/dev/null 2>&1; then
    log "Re-applying ad-hoc codesign on macOS (avoids AMFI SIGKILL)…"
    codesign --force --sign - "${INSTALL_DIR}/by-bin"
  else
    warn "codesign not found — installed binary may be killed by AMFI on first run."
  fi
fi

# ── Post-install hint ───────────────────────────────────────────────────────

log "Installed to ${INSTALL_DIR}:"
if [[ ${NATIVE} -eq 1 ]]; then
  log "  by      — wrapper (sources .env, execs by-bin)"
  log "  by-bin  — native binary"
  [[ ${WITH_JAR} -eq 1 ]] && log "  by.jar  — uberjar (BY_JAR=1 to use)"
else
  log "  by          — launcher (JVM uberjar mode; requires java on PATH)"
  log "  by-wrapper  — wrapper (sources .env, runs java -jar)"
  log "  by.jar      — uberjar"
fi

case ":${PATH}:" in
  *":${INSTALL_DIR}:"*) ;;
  *)
    warn "${INSTALL_DIR} is not on PATH. Add to your shell rc:"
    warn "  echo 'export PATH=\"${INSTALL_DIR}:\$PATH\"' >> ~/.zshrc   # or ~/.bashrc"
    ;;
esac

log "Done. Try:  by --help"
