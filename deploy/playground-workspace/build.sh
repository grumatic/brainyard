#!/usr/bin/env bash
# Copyright (c) 2024-2026 Grumatic, Inc.
# SPDX-License-Identifier: MIT
#
# Build the Brainyard Playground workspace image. Copies the agent-tui-app
# uberjar into the build context (it must already be built — `bb uberjar:ata`)
# and runs `docker build`.
#
#   deploy/playground-workspace/build.sh [tag]   # default tag: brainyard/workspace:dev
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
repo="$(cd "$here/../.." && pwd)"
tag="${1:-brainyard/workspace:dev}"
jar="$repo/projects/agent-tui-app/target/agent-tui-app.jar"

if [ ! -f "$jar" ]; then
  echo "error: uberjar not found at $jar" >&2
  echo "       build it first:  bb uberjar:ata" >&2
  exit 1
fi

echo "→ staging uberjar ($(du -h "$jar" | cut -f1)) into build context"
cp "$jar" "$here/by.jar"

echo "→ docker build -t $tag"
docker build -t "$tag" "$here"

rm -f "$here/by.jar"
echo "✓ built $tag"
