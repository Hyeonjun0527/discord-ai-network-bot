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

# Icon Composer 는 아트워크를 타일 가장자리까지 꽉 채워(full-bleed) 내보낸다. macOS 앱 아이콘은
# 사방에 ~9.77% 투명 여백(1024 캔버스 기준 둥근사각형 824)을 둬야 Dock/Cmd+Tab 에서 다른 앱과
# 같은 크기·모양으로 보인다(여백 0 이면 옆 앱보다 크고 각져 보임). 각 타일을 824/1024 로 인셋한다.
python3 - "$ICONSET" <<'PY'
import os, sys, glob
from PIL import Image  # 필요: python3 -m pip install pillow

iconset = sys.argv[1]
MARGIN = 100 / 1024  # Apple 그리드: 1024 캔버스, 콘텐츠 824, 여백 100px
for path in glob.glob(os.path.join(iconset, "*.png")):
    im = Image.open(path).convert("RGBA")
    w, h = im.size
    m = round(w * MARGIN)
    content = w - 2 * m
    out = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    out.paste(im.resize((content, content), Image.LANCZOS), (m, m))
    out.save(path)
print(f"inset {len(glob.glob(os.path.join(iconset, '*.png')))} tiles to Apple margin")
PY

iconutil -c icns "$ICONSET" -o "$OUT_ICNS"
cp "$ICONSET/icon_512x512@2x.png" "$OUT_SOURCE"

echo "generated $OUT_ICNS"
echo "generated $OUT_SOURCE"
