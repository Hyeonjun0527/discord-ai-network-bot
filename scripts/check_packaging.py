#!/usr/bin/env python3
"""패키지/릴리스 자산명 SSOT 드리프트 검사.

`packaging/assets.json`(SSOT)의 패키지 ID·릴리스 자산 파일명·설치 명령이, 그것을 사용하는 모든
소비처(winget/scoop/brew 매니페스트, 릴리스 CI, 앱 가이드 코드 InstallGuide.kt, 웹 랜딩 install.html,
문서)에서 **글자 그대로 일치**하는지 강제한다. 한 곳만 자산명을 바꾸면(다운로드 404·등록 실패의 원인)
여기서 실패한다. 크로스언어(YAML/JSON/Ruby/Kotlin/HTML/Markdown) 라 코드젠 대신 강제 검사로 묶는다.

실행: python3 scripts/check_packaging.py
"""
from __future__ import annotations

import json
import pathlib
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
A = json.loads((ROOT / "packaging" / "assets.json").read_text(encoding="utf-8"))

pkg = A["packages"]
asset = A["assets"]
install = A["installCommands"]

# 소비처 파일 → 그 파일에 반드시 등장해야 하는 SSOT 토큰 목록.
EXPECT: dict[str, list[str]] = {
    "provider-agent/packaging/winget/Nyassistant.DiscordAiNetworkBot.installer.yaml": [
        pkg["wingetId"],
        asset["guiWin"],
        pkg["command"],
    ],
    "provider-agent/packaging/scoop/discord-ai-network-bot.json": [
        asset["guiWin"],
        pkg["scoopBin"],
    ],
    "provider-agent/packaging/homebrew/nyassistant.rb": [
        f'cask "{pkg["homebrewCask"]}"',
        asset["guiMac"],
        A["macAppBundle"],
    ],
    "provider-agent/packaging/homebrew/discord-ai-network-bot.rb": [
        pkg["homebrewFormula"],
        asset["cliMac"],
        asset["cliLinux"],
    ],
    ".github/workflows/agent-build.yml": [
        asset["guiMac"],
        asset["guiMacDmg"],
        asset["guiWin"],
        asset["cliLinux"],
        asset["cliMac"],
        asset["cliWin"],
        A["macAppBundle"],
    ],
    # 디스코드 가이드(ProviderOnboarding)의 설치 명령 SSOT 는 InstallGuide.kt.
    "central-server/src/main/kotlin/com/discordassistant/central/domain/InstallGuide.kt": [
        pkg["wingetId"],
        install["mac"],
    ],
    # 웹 랜딩: "직접 다운로드" 자산명 리터럴 + 브랜드 워드마크(SSOT of record) 드리프트 검사.
    "central-server/src/main/resources/static/install.html": [
        asset["guiMacDmg"],
        asset["guiWin"],
        A["brandName"],
        A["brandTagline"],
    ],
    # 관리자 콘솔(대시보드): 한국어 앱 표시명(appDisplayName) 워드마크 드리프트 검사(title + brand 표기).
    "central-server/src/main/resources/static/admin/dashboard/index.html": [
        A["appDisplayName"],
    ],
    "docs/PACKAGE_MANAGERS.md": [
        pkg["wingetId"],
        f'--cask {pkg["homebrewTap"]}/{pkg["homebrewCask"]}',
    ],
}


def main() -> int:
    problems: list[str] = []
    for rel, tokens in EXPECT.items():
        path = ROOT / rel
        if not path.exists():
            problems.append(f"❌ 소비처 파일 없음: {rel}")
            continue
        text = path.read_text(encoding="utf-8")
        for tok in tokens:
            if tok not in text:
                problems.append(f"❌ {rel}: SSOT 토큰 누락 → {tok!r}")

    if problems:
        print("패키지 자산 SSOT 드리프트 발견:")
        for p in problems:
            print("  " + p)
        print(f"\nSSOT: packaging/assets.json — 위 토큰을 일치시키세요({len(problems)}건).")
        return 1
    print(f"✅ 패키지 자산 SSOT 일치 — {len(EXPECT)}개 소비처 검증 완료")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
