#!/usr/bin/env python3
"""데스크톱 앱 시안(prototypes/desktop)을 provider-agent 가 서빙하는 실제 앱 자산으로 이식한다.

prototypes/desktop 은 디자인/UX 의 SSOT(시안)로 그대로 유지하고, 이 스크립트가 멱등하게
provider-agent/src/provider_agent/webui_assets/ 로 복사·변환한 생성물을 만든다(커밋 안 함).

변환:
- 모든 자산: `@proto-only` 구간 제거 — 프로토타입(시안) 전용 mock/데모 코드를 실 앱에서 통째로 들어낸다.
    · JS:   `/* @proto-only */ … /* @end-proto-only */`
    · HTML: `<!-- @proto-only --> … <!-- @end-proto-only -->`
  → 실 데스크톱 앱(webui_assets)에는 mock 데이터·데모 컨트롤러·가짜 분기가 전혀 들어가지 않는다(실 HTTP 만).
- adapter.js: `export const USE_MOCK = true;` → `false`(혹시 남은 참조 대비 — 보통 구간 제거로 미사용).
- index.html: <head> 바로 다음에 세션키 주입 한 줄 삽입(실 앱이 __SESSION_KEY__ 를 치환).

  python3 scripts/sync_desktop_app.py
"""
from __future__ import annotations

import pathlib
import re
import shutil
import sys

# Windows 콘솔 기본 인코딩(cp1252)은 한글·'→'(U+2192) 출력 시 UnicodeEncodeError 로 죽는다.
# stdout/stderr 를 UTF-8 로 재설정해 CI(windows-latest)·로컬 Windows 어디서나 안전하게 찍는다.
for _stream in (sys.stdout, sys.stderr):
    try:
        _stream.reconfigure(encoding="utf-8")  # type: ignore[union-attr]
    except (AttributeError, ValueError):
        pass

# 프로토타입 전용(mock/데모) 구간 마커. sync 시 통째로 제거한다.
#  - 인라인/블록 모두 지원. 앞 들여쓰기와 뒤 개행까지 함께 지워 빈 줄을 남기지 않는다.
_PROTO_JS = re.compile(r"[ \t]*/\* @proto-only \*/.*?/\* @end-proto-only \*/[ \t]*\n?", re.DOTALL)
_PROTO_HTML = re.compile(r"[ \t]*<!-- @proto-only -->.*?<!-- @end-proto-only -->[ \t]*\n?", re.DOTALL)


def _strip_proto_only(text: str) -> str:
    """프로토타입 전용 mock/데모 구간(@proto-only … @end-proto-only)을 제거한다(JS·HTML 양쪽)."""
    text = _PROTO_JS.sub("", text)
    return _PROTO_HTML.sub("", text)

ROOT = pathlib.Path(__file__).resolve().parents[1]
SRC = ROOT / "prototypes" / "desktop"
DST = ROOT / "provider-agent" / "src" / "provider_agent" / "webui_assets"

# 복사 대상 파일(시안 자산만 — 테스트/설정/의존성 제외).
FILES = (
    "index.html",
    "adapter.js",
    "contract.js",
    "presenter.js",
    "state.js",
    "toast.js",
    "install.js",
)


def _transform_adapter(text: str) -> str:
    """mock 토글을 끈다(실 백엔드 fetch). 정확히 한 줄만 치환."""
    needle = "export const USE_MOCK = true;"
    repl = "export const USE_MOCK = false;"
    if needle not in text:
        raise SystemExit(f"adapter.js 에 '{needle}' 가 없습니다 — 시안 변경으로 sync 불가")
    return text.replace(needle, repl, 1)


def _transform_index(text: str) -> str:
    """<head> 바로 다음에 세션키 주입 스크립트 한 줄을 삽입(실 앱이 __SESSION_KEY__ 치환)."""
    needle = "<head>"
    # 세션키 주입 + 부팅 플래그(app-booting): 실 앱은 hasToken 판정 전까지 메인을 숨겨 FOUC(메인 깜빡임)를 막는다.
    # setStage 가 stage 결정 시 app-booting 을 제거한다(index.html). 프로토타입(키 미주입)엔 클래스가 안 붙어 무해.
    inject = '\n  <script>window.__SESSION_KEY="__SESSION_KEY__";document.documentElement.classList.add("app-booting");</script>'
    idx = text.find(needle)
    if idx < 0:
        raise SystemExit("index.html 에 <head> 가 없습니다 — 시안 변경으로 sync 불가")
    cut = idx + len(needle)
    return text[:cut] + inject + text[cut:]


def main() -> None:
    if not SRC.is_dir():
        raise SystemExit(f"시안 디렉토리가 없습니다: {SRC}")

    # 멱등: 매 실행 시 깨끗이 비우고 재생성.
    if DST.exists():
        shutil.rmtree(DST)
    DST.mkdir(parents=True)

    written: list[str] = []
    for name in FILES:
        src = SRC / name
        if not src.is_file():
            raise SystemExit(f"시안 파일이 없습니다: {src}")
        text = src.read_text(encoding="utf-8")
        text = _strip_proto_only(text)  # 모든 자산에서 프로토타입 전용 mock/데모 구간 제거(실 앱 클린)
        if name == "adapter.js":
            text = _transform_adapter(text)
        elif name == "index.html":
            text = _transform_index(text)
        (DST / name).write_text(text, encoding="utf-8")
        written.append(name)

    # img/ 디렉토리 전체 복사(이미지 자산).
    img_src = SRC / "img"
    img_count = 0
    if img_src.is_dir():
        img_dst = DST / "img"
        img_dst.mkdir()
        for f in sorted(img_src.iterdir()):
            if f.is_file():
                shutil.copy2(f, img_dst / f.name)
                img_count += 1

    # 자가 검증: 프로토타입 전용 mock/데모가 실 앱에 새지 않았는지(strip 보증). 누수면 sync 실패.
    leaks: list[str] = []
    bad = {"@proto-only": "마커 잔존", "const MOCK = {": "mock 데이터", "export const USE_MOCK = true": "USE_MOCK=true", 'id="proto"': "PROTO 컨트롤러"}
    for name in ("index.html", "adapter.js"):
        t = (DST / name).read_text(encoding="utf-8")
        for needle, desc in bad.items():
            if needle in t:
                leaks.append(f"{name}: {desc} ('{needle}')")
    if leaks:
        raise SystemExit("[sync-desktop] ❌ 생성물에 프로토타입 전용 코드 누수:\n  " + "\n  ".join(leaks))

    print(f"[sync-desktop] {SRC} → {DST}")
    print(f"  files: {', '.join(written)}")
    print(f"  img/: {img_count} 개")
    print("  adapter.js: USE_MOCK=false, index.html: 세션키 주입, @proto-only 제거(누수 0 확인)")


if __name__ == "__main__":
    main()
