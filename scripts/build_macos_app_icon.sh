#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ICON_DOC="$ROOT/provider-agent/packaging/icons/app.icon"
OUT_ICNS="$ROOT/provider-agent/packaging/icons/app.icns"
OUT_SOURCE="$ROOT/provider-agent/packaging/icons/app-source.png"

find_ictool() {
  if [[ -n "${ICTOOL:-}" && -x "${ICTOOL:-}" ]]; then
    printf '%s\n' "$ICTOOL"
    return 0
  fi
  local candidates=(
    "/Applications/Xcode.app/Contents/Applications/Icon Composer.app/Contents/Executables/ictool"
    "/Applications/Xcode-beta.app/Contents/Applications/Icon Composer.app/Contents/Executables/ictool"
    "/Applications/Icon Composer.app/Contents/Executables/ictool"
  )
  local candidate
  for candidate in "${candidates[@]}"; do
    if [[ -x "$candidate" ]]; then
      printf '%s\n' "$candidate"
      return 0
    fi
  done
  return 1
}

ICTOOL_BIN="$(find_ictool)" || {
  echo "error: Icon Composer ictool not found. Install Xcode/Icon Composer or set ICTOOL=/path/to/ictool." >&2
  exit 1
}

[[ -d "$ICON_DOC" ]] || {
  echo "error: missing Icon Composer document: $ICON_DOC" >&2
  exit 1
}

TMPDIR="$(mktemp -d)"
cleanup() { rm -rf "$TMPDIR"; }
trap cleanup EXIT
ICONSET="$TMPDIR/app.iconset"
mkdir -p "$ICONSET"

for size in 16 32 128 256 512; do
  "$ICTOOL_BIN" "$ICON_DOC" \
    --export-image \
    --output-file "$ICONSET/icon_${size}x${size}.png" \
    --platform macOS \
    --rendition Default \
    --width "$size" \
    --height "$size" \
    --scale 1 >/dev/null

  "$ICTOOL_BIN" "$ICON_DOC" \
    --export-image \
    --output-file "$ICONSET/icon_${size}x${size}@2x.png" \
    --platform macOS \
    --rendition Default \
    --width "$size" \
    --height "$size" \
    --scale 2 >/dev/null
done

iconutil -c icns "$ICONSET" -o "$OUT_ICNS"
cp "$ICONSET/icon_512x512@2x.png" "$OUT_SOURCE"

echo "generated $OUT_ICNS"
echo "generated $OUT_SOURCE"
