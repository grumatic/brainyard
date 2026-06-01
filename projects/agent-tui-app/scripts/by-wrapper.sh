#!/usr/bin/env bash
# Wrapper for the `by` native binary.
#
# Walks up from the current working directory to find the nearest .env file
# (project-local convention), sources it (set -a so values are exported),
# then exec's the real binary `by-bin` from the same directory as this
# wrapper. Already-set env vars in the parent shell win because we source
# AFTER capturing them and bash's `source` overwrites — so we deliberately
# only auto-source when the var is unset (set -a + check pattern).
#
# Override discovery: BY_ENV_FILE=/path/to/.env forces a specific file.
# Skip discovery: BY_NO_DOTENV=1.
#
# Run as JVM (uberjar) instead of native binary: BY_JAR=1.
# Useful for debugging missing reflect-config under native-image, or when
# the native binary is broken/missing. Slower startup (~3-5s) than native.

set -e

bin_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
real_bin="${bin_dir}/by-bin"
jar_file="${bin_dir}/by.jar"

load_env_file() {
    local f="$1"
    # Source under `set -a` so all assignments become exported, but
    # only set keys that aren't already in the environment so the
    # parent shell's vars take precedence.
    while IFS='=' read -r key rest; do
        # Skip blanks, comments, malformed lines
        case "$key" in
            ''|\#*) continue ;;
        esac
        # Strip leading 'export '
        key="${key#export }"
        key="${key# }"
        key="${key% }"
        # Skip if already set in the env
        if [ -n "${!key+x}" ]; then continue; fi
        # Strip surrounding quotes from the value
        local val="$rest"
        case "$val" in
            \"*\") val="${val%\"}"; val="${val#\"}" ;;
            \'*\') val="${val%\'}"; val="${val#\'}" ;;
        esac
        export "$key=$val"
    done < "$f"
}

if [ -z "$BY_NO_DOTENV" ]; then
    if [ -n "$BY_ENV_FILE" ] && [ -f "$BY_ENV_FILE" ]; then
        load_env_file "$BY_ENV_FILE"
    else
        # Walk up from cwd looking for .env
        d="$PWD"
        while [ "$d" != "/" ] && [ -n "$d" ]; do
            if [ -f "$d/.env" ]; then
                load_env_file "$d/.env"
                break
            fi
            d="$(dirname "$d")"
        done
    fi
fi

if [ -n "$BY_JAR" ] && [ "$BY_JAR" != "0" ]; then
    if [ ! -f "$jar_file" ]; then
        echo "by: BY_JAR set but uberjar not found at $jar_file" >&2
        echo "    Run 'bb uberjar:ata && bb install:ata' to install it." >&2
        exit 1
    fi
    exec java -jar "$jar_file" "$@"
fi

if [ ! -x "$real_bin" ]; then
    echo "by: native binary not found at $real_bin" >&2
    echo "    Run 'bb build:ata && bb install:ata', or set BY_JAR=1 to use the uberjar." >&2
    exit 1
fi

exec "$real_bin" "$@"
