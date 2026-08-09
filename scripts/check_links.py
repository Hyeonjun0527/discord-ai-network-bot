#!/usr/bin/env python3
"""문서 상대 링크 검증(차수 17 #266).

저장소의 모든 `*.md` 에서 `[text](relative/path.md)` 형태의 **상대 경로 링크**가 실제 파일을
가리키는지 확인한다. http(s)/앵커(#...)/mailto 는 건너뛴다. 깨진 링크가 있으면 비0 종료(CI 가드용).

실행: python3 scripts/check_links.py
"""
from __future__ import annotations

import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
LINK_RE = re.compile(r"\[[^\]]*\]\(([^)]+)\)")
SKIP_DIRS = {".git", ".omx", "node_modules", "build", ".gradle", "__pycache__", ".venv", "dist"}
PRIVATE_DATA_ROOT = ROOT / "data" / "private"


def is_external(target: str) -> bool:
    return (
        target.startswith(("http://", "https://", "mailto:", "#"))
        or target.startswith("<")  # 자동링크
    )


def main() -> int:
    broken: list[str] = []
    md_files = [
        p for p in ROOT.rglob("*.md")
        if not any(part in SKIP_DIRS for part in p.parts) and not p.is_relative_to(PRIVATE_DATA_ROOT)
    ]
    for md in md_files:
        text = md.read_text(encoding="utf-8", errors="ignore")
        for m in LINK_RE.finditer(text):
            target = m.group(1).strip()
            # 링크 제목(" ...") 및 앵커 제거
            target = target.split()[0].split("#")[0]
            if not target or is_external(target):
                continue
            resolved = (md.parent / target).resolve()
            if not resolved.exists():
                broken.append(f"{md.relative_to(ROOT)} → {target}")

    print(f"검사한 문서: {len(md_files)}개")
    if broken:
        print(f"❌ 깨진 상대 링크 {len(broken)}건:")
        for b in broken:
            print(f"  - {b}")
        return 1
    print("✅ 깨진 상대 링크 없음")
    return 0


if __name__ == "__main__":
    sys.exit(main())
