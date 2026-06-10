#!/usr/bin/env python3
"""문구 i18n SSOT(i18n/messages.json)에서 모듈별 생성본을 만든다(봇·웹·데스크톱 앱 공유).

생성 대상:
- central-server/src/main/resources/i18n/messages.json   ← 섹션 bot  (Kotlin I18n 이 로드)
- central-server/src/main/resources/static/i18n/web.json  ← 섹션 web  (설치 랜딩이 fetch/주입)
- provider-agent/src/provider_agent/i18n_messages.json    ← 섹션 agent (데스크톱 앱이 로드)

생성본은 직접 편집하지 말고 이 스크립트로만 갱신한다(`make i18n-gen`). 모든 키는 ko/en/ja 가 있어야 한다.

  python3 scripts/gen_i18n.py           # 생성본 작성
  python3 scripts/gen_i18n.py --check   # 드리프트 + ko/en/ja 완전성 검증(CI 가드, 비0 종료 시 실패)
"""
from __future__ import annotations

import json
import pathlib
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
SRC = ROOT / "i18n" / "messages.json"
REQUIRED = ("ko", "en", "ja")

# 섹션 → JSON 생성본 경로(봇·웹·데스크톱 Python 백엔드가 로드).
TARGETS = {
    "bot": ROOT / "central-server/src/main/resources/i18n/messages.json",
    "web": ROOT / "central-server/src/main/resources/static/i18n/web.json",
    "agent": ROOT / "provider-agent/src/provider_agent/i18n_messages.json",
}

# desktop 섹션 → 데스크톱 UI 가 동기 사용할 JS 표(window.__I18N). 기존 .js 자산 라우트로 서빙된다(새 라우트 불필요).
DESKTOP_JS = ROOT / "prototypes/desktop/i18n-agent.js"

GEN_NOTE = "GENERATED from i18n/messages.json by scripts/gen_i18n.py — DO NOT EDIT. 문구 변경은 i18n/messages.json 에서."


def load_src() -> dict:
    return json.loads(SRC.read_text(encoding="utf-8"))


def completeness_errors(src: dict) -> list[str]:
    errs: list[str] = []
    for section, entries in src.items():
        if section.startswith("_"):
            continue
        for key, langs in entries.items():
            if not isinstance(langs, dict):
                errs.append(f"{section}.{key}: 객체가 아님")
                continue
            for loc in REQUIRED:
                if not str(langs.get(loc, "")).strip():
                    errs.append(f"{section}.{key}: {loc} 누락/빈값")
    return errs


def rendered(section: str, src: dict) -> str:
    body = {"_generated": GEN_NOTE, **src[section]}
    return json.dumps(body, ensure_ascii=False, indent=2) + "\n"


def rendered_desktop_js(src: dict) -> str:
    """데스크톱 UI 다국어 표를 동기 주입할 JS(window.__I18N). i18n.js 가 이걸 읽는다."""
    table = json.dumps(src["desktop"], ensure_ascii=False, indent=2)
    return f"// {GEN_NOTE}\nwindow.__I18N = {table};\n"


def main(check: bool) -> int:
    if not SRC.exists():
        print(f"❌ SSOT 없음: {SRC}")
        return 1
    src = load_src()

    errs = completeness_errors(src)
    if errs:
        print("i18n 완전성 오류(모든 키는 ko/en/ja 필수):")
        for e in errs:
            print("  ❌ " + e)
        return 1

    drift: list[str] = []
    for section, path in TARGETS.items():
        if section not in src:
            print(f"❌ SSOT 에 섹션 없음: {section}")
            return 1
        content = rendered(section, src)
        if check:
            current = path.read_text(encoding="utf-8") if path.exists() else ""
            if current != content:
                drift.append(str(path.relative_to(ROOT)))
        else:
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(content, encoding="utf-8")

    # 데스크톱 UI 표(window.__I18N) — desktop 섹션을 JS 로 생성.
    if "desktop" not in src:
        print("❌ SSOT 에 섹션 없음: desktop")
        return 1
    desktop_js = rendered_desktop_js(src)
    if check:
        cur = DESKTOP_JS.read_text(encoding="utf-8") if DESKTOP_JS.exists() else ""
        if cur != desktop_js:
            drift.append(str(DESKTOP_JS.relative_to(ROOT)))
    else:
        DESKTOP_JS.write_text(desktop_js, encoding="utf-8")

    if check:
        if drift:
            print("i18n 생성본이 SSOT 와 동기화되지 않음(드리프트):")
            for d in drift:
                print("  ❌ " + d)
            print("\n`make i18n-gen` 으로 재생성 후 커밋하세요.")
            return 1
        print(f"✅ i18n 생성본 동기 + ko/en/ja 완전 — {len(TARGETS)}개 모듈")
        return 0

    print(f"✅ i18n 생성본 작성 완료 — {len(TARGETS)}개 모듈: {', '.join(TARGETS)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(check="--check" in sys.argv[1:]))
