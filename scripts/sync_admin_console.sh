#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SOURCE="$ROOT/admin-console/dist"
TARGET="$ROOT/central-server/src/main/resources/static/admin/console"

pnpm --dir "$ROOT/admin-console" install --frozen-lockfile
pnpm --dir "$ROOT/admin-console" build

rm -rf "$TARGET"
mkdir -p "$TARGET"
cp -R "$SOURCE"/. "$TARGET"/
