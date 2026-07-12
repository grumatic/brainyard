# Installing Brainyard

> **Status:** shipping. `by` publishes tagged GitHub Releases (currently on the v0.3.x line). The install paths below are live.

This page covers the supported install paths, manual install for air-gapped or unusual environments, checksum verification, and troubleshooting. For a one-liner, see the [README](../README.md).

---

## Install paths at a glance

| Path | Best for | Requires | Cold start |
|---|---|---|---|
| `curl \| bash` → native binary | Almost everyone | nothing (single binary) | ~1.5 s |
| `java -jar by.jar` | JVM users, debugging | JDK 21+ | ~3–5 s |

Both paths land on the same GitHub Release. Pick whichever you already have a runtime for.

---

## 1. Native binary via `curl | bash` (recommended)

```bash
curl -fsSL https://raw.githubusercontent.com/grumatic/brainyard/main/bin/install.sh | bash
```

### What it does

1. Detects your OS (`uname -s`) and arch (`uname -m`) and maps to the release asset:
   - `by-<version>-linux-amd64`
   - `by-<version>-linux-arm64`
   - `by-<version>-macos-amd64`
   - `by-<version>-macos-arm64`
2. Resolves the latest release tag via the GitHub API (or honors `BY_VERSION=vX.Y.Z`).
3. Downloads three files: the native binary, the `by-wrapper.sh` script, and `SHA256SUMS`.
4. Verifies SHA-256 of every downloaded file against `SHA256SUMS`.
5. Installs:
   - `~/.local/bin/by-bin` — the native binary (chmod +x).
   - `~/.local/bin/by` — the wrapper (chmod +x). Users invoke `by`; the wrapper sources nearby `.env` files and execs `by-bin`.
6. *(macOS only)* Re-applies an ad-hoc codesign to `by-bin`. Without this, AMFI sends SIGKILL on first launch because `native-image`'s signature is bound to the original inode and breaks after `cp`.
7. Prints a post-install hint to add `~/.local/bin` to `PATH` if it isn't already.

### Flags & env vars

| Variable / flag | Purpose |
|---|---|
| `BY_VERSION=v0.3.3` | Pin to a specific release. Default: latest. |
| `--prefix=/usr/local` | Install to a different directory. Prompts for `sudo` if needed. |
| `--with-jar` | Also download and install `by.jar` (≈49 MB). Enables `BY_JAR=1 by …` JVM-mode fallback for debugging native-image issues. |
| `--no-verify` | Skip SHA-256 verification. Not recommended; use only if `shasum`/`sha256sum` is unavailable. |

### Wrapper behavior at runtime

`by` is the wrapper. It walks up from the current working directory to find the nearest `.env` file (a project-local convention used by the upstream development repo), sources it under `set -a` so values are exported, then `exec`s `by-bin` from the same directory. The wrapper deliberately does **not** override env vars already set in your parent shell — your shell wins.

Override the wrapper's `.env` discovery:

| Variable | Purpose |
|---|---|
| `BY_ENV_FILE=/path/to/.env` | Force a specific `.env` file. |
| `BY_NO_DOTENV=1` | Skip `.env` discovery entirely. |
| `BY_JAR=1` | Execute `by.jar` via `java -jar` instead of `by-bin`. Useful for diagnosing native-image regressions. |

---

## 2. Java uberjar

```bash
curl -LO https://github.com/grumatic/brainyard/releases/latest/download/by.jar
java -jar by.jar --help
```

- Requires JDK 21+.
- Stable URL: `/latest/download/by.jar` always points to the latest release.
- Versioned URL: `https://github.com/grumatic/brainyard/releases/download/v0.3.3/by-0.3.3.jar`.

The uberjar exposes the same CLI as the native binary. Differences from native:

- Slower cold start (~3–5 s vs ~1.5 s native).
- Full Clojure runtime — handy if you need to `:require` Brainyard from another JVM workflow.
- Predictable behavior across all OS/arch combinations (no native compilation needed).

---

## 3. Manual install

For air-gapped environments, custom install locations, or when you don't trust `curl | bash`:

```bash
# 1. Find the release you want
open https://github.com/grumatic/brainyard/releases

# 2. Download (replace VERSION and ASSET as appropriate)
VERSION=v0.3.3
ASSET=macos-arm64   # or linux-amd64, linux-arm64, macos-amd64
curl -LO https://github.com/grumatic/brainyard/releases/download/${VERSION}/by-${VERSION#v}-${ASSET}
curl -LO https://github.com/grumatic/brainyard/releases/download/${VERSION}/by-wrapper.sh
curl -LO https://github.com/grumatic/brainyard/releases/download/${VERSION}/SHA256SUMS

# 3. Verify checksums
shasum -a 256 -c SHA256SUMS --ignore-missing

# 4. Install
mkdir -p ~/.local/bin
mv by-${VERSION#v}-${ASSET} ~/.local/bin/by-bin
mv by-wrapper.sh ~/.local/bin/by
chmod +x ~/.local/bin/by ~/.local/bin/by-bin

# 5. macOS only: re-codesign the binary
codesign --force --sign - ~/.local/bin/by-bin
```

---

## Verifying releases

Every release ships a `SHA256SUMS` file covering all artifacts (native binaries, jar, wrapper). The recommended verification flow:

```bash
curl -LO https://github.com/grumatic/brainyard/releases/download/v0.3.3/SHA256SUMS
curl -LO https://github.com/grumatic/brainyard/releases/download/v0.3.3/by-0.3.3-macos-arm64
shasum -a 256 -c SHA256SUMS --ignore-missing
```

Look for `by-0.3.3-macos-arm64: OK`. If it says `FAILED`, do not install — open an issue with the platform you're on and which mirror you downloaded from.

---

## Building from source

This repo holds the full source. You need a GraalVM JDK (25.0.3+, pinned in `.sdkmanrc`), `bb` (Babashka), and the `clojure` CLI.

```bash
git clone https://github.com/grumatic/brainyard
cd brainyard
sdk use java 25.0.3-graal     # honors .sdkmanrc

bb compile:ata                # AOT compile main namespace
bb uberjar:ata                # build target/agent-tui-app.jar
bb native:ata                 # build target/by via native-image
# or, the chained shortcut:
bb build:ata                  # compile → uberjar → native

bb install:ata                # copy by + by-bin + by.jar to ~/.local/bin
# or, to stage a release for upload (writes release/BUILD-INFO.txt with this repo's commit):
bin/release-stage.sh
```

Prerequisites:

- **GraalVM 25** (25.0.3+, matches `.sdkmanrc`) on `PATH` (or via SDKMAN).
- **Babashka** for the `bb` task runner.
- **Clojure CLI tools** (`clj`).

This repo is the source of truth — no private upstream sync is involved (the
earlier sync-wrapper model was retired; see [`../CLAUDE.md`](../CLAUDE.md)). The
Polylith bricks required by `agent-tui-app` are committed here under
`projects/agent-tui-app/`, `bases/agent-tui/`, and `components/` (21 bricks). The
bundled `native-image` config lives at
`projects/agent-tui-app/resources/META-INF/native-image/ai.brainyard/agent-tui-app/`.

To validate the committed `native-image` config:

```bash
bb check:ata                  # static drift gate: file present, non-empty, under size ceiling
bb size:ata                   # post-build size + reachability report (after a native build)
```

---

## Troubleshooting

### macOS: "killed: 9" on first launch

The native binary's `linker-signed,adhoc` signature is bound to the original inode. After `cp`, the kernel refuses to launch the copy and AMFI sends SIGKILL.

```bash
codesign --force --sign - ~/.local/bin/by-bin
```

The official installer does this automatically; it's only relevant for manual installs.

### `by: command not found`

`~/.local/bin` is not on `PATH`. Add it to your shell rc:

```bash
echo 'export PATH="$HOME/.local/bin:$PATH"' >> ~/.zshrc   # or ~/.bashrc
exec $SHELL
```

### The wrapper picks up the wrong `.env`

Force a specific file with `BY_ENV_FILE=/abs/path/to/.env by …`, or skip discovery entirely with `BY_NO_DOTENV=1 by …`.

### Native binary crashes; uberjar works

Switch to JVM mode and capture a stack trace:

```bash
BY_JAR=1 by ask 'reproduce the crash'
```

If the JVM run succeeds where the native run fails, the issue is almost always a missing reflection registration. File an issue with the JVM stack trace attached.

On JDK 21+ the uberjar emits warnings from `org.sqlite.SQLiteJDBCLoader` about restricted native-access methods (`java.lang.System::load`). These are informational — sqlite still loads and works. To silence them, run with `--enable-native-access=ALL-UNNAMED`:

```bash
BY_JAR=1 by ask 'hi'                          # warnings printed, run succeeds
java --enable-native-access=ALL-UNNAMED \     # warnings silenced
     -jar ~/.local/bin/by.jar ask 'hi'
```

A future Java release will block the call by default; the native binary is unaffected.

### `bb native:ata` fails with "native-image not found"

GraalVM isn't on `PATH` and `JAVA_HOME` doesn't point at it.

```bash
sdk install java 25.0.3-graal
sdk use java 25.0.3-graal
```

On macOS, the bb task also probes `/Library/Java/JavaVirtualMachines/` for any GraalVM install. If yours is somewhere else, set `JAVA_HOME` explicitly.

---

## See also

- [`usage.md`](usage.md) — full command reference for `by`.
- [`deploy-design.md`](deploy-design.md) — release architecture and rollout plan.
- [`../README.md`](../README.md) — quick install + overview.
