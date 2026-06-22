#!/usr/bin/env python3
"""로그 redaction 스캐너(NEXA-P17-T013, security).

로그 파일(또는 표준입력)에서 **금지 문자열**(Discord snowflake·API key·Bearer 토큰)을 탐지한다.
금지 문자열이 한 건이라도 있으면 비-0 으로 종료한다(CI 가드). 패턴은 central 의
[SensitiveLogRedactor](../central-server/src/main/kotlin/com/discordassistant/central/global/observability/SensitiveLogRedactor.kt)
와 동일하게 유지한다(드리프트 시 양쪽 테스트가 깨진다).

사용:
    python3 scripts/scan-sensitive-logs.py <logfile> [logfile...]
    cat app.log | python3 scripts/scan-sensitive-logs.py -
"""
from __future__ import annotations

import re
import sys

# central SensitiveLogRedactor 와 동일 패턴(SSOT: redaction-contract.md).
SNOWFLAKE_RE = re.compile(r"\b\d{17,20}\b")
API_KEY_RE = re.compile(r"\b(?:sk-[A-Za-z0-9]{16,}|AIza[A-Za-z0-9_\-]{16,})\b")
BEARER_RE = re.compile(r"(?i)Bearer\s+[A-Za-z0-9._\-]{8,}")

PATTERNS = {
    "discord_snowflake": SNOWFLAKE_RE,
    "api_key": API_KEY_RE,
    "bearer_token": BEARER_RE,
}


def scan_line(line: str) -> list[str]:
    """한 라인에서 매칭된 금지 패턴 이름 목록(원문은 반환하지 않는다)."""
    return [name for name, pat in PATTERNS.items() if pat.search(line)]


def scan_stream(name: str, stream) -> list[str]:
    findings: list[str] = []
    for lineno, line in enumerate(stream, start=1):
        hits = scan_line(line)
        if hits:
            findings.append(f"{name}:{lineno}: {', '.join(hits)}")
    return findings


def main(argv: list[str]) -> int:
    if not argv:
        print(__doc__)
        return 2
    findings: list[str] = []
    for path in argv:
        if path == "-":
            findings.extend(scan_stream("<stdin>", sys.stdin))
        else:
            with open(path, encoding="utf-8", errors="ignore") as fh:
                findings.extend(scan_stream(path, fh))

    if findings:
        print(f"FAIL: 로그에 금지 문자열 {len(findings)}건(snowflake/API key/토큰):", file=sys.stderr)
        for f in findings:
            print(f"  - {f}", file=sys.stderr)
        return 1
    print("OK: 금지 문자열 없음")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
