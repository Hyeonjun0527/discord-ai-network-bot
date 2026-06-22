#!/usr/bin/env python3
"""NEXA-P17-T021 — dependency·container scan 예외 레지스트리 검증.

`docs/nexa/security/scan-exceptions.yaml` 의 모든 예외 항목이 **만료일·소유자·근거** 를 갖는지 검사한다.
**acceptance(T021) — critical 취약점 예외는 만료일·소유자·근거 없이 허용되지 않는다**: critical(과 high)
예외에 필수 필드가 없거나 만료일이 과거이면 비-0 종료로 실패한다(조용한 무기한·무기명 예외 금지).

실행: python3 scripts/validate-nexa-scan-exceptions.py
"""

from __future__ import annotations

import datetime as dt
import sys
from pathlib import Path
from typing import Any

import yaml

REPO_ROOT = Path(__file__).resolve().parents[1]
EXCEPTIONS_PATH = REPO_ROOT / "docs" / "nexa" / "security" / "scan-exceptions.yaml"

REQUIRED_FIELDS = ("id", "scope", "severity", "owner", "justification", "expires")
VALID_SCOPES = {"gradle", "python", "container"}
VALID_SEVERITIES = {"low", "medium", "high", "critical"}
# 만료일·소유자·근거를 반드시 요구하는 심각도(acceptance 핵심).
ENFORCED_SEVERITIES = {"high", "critical"}


def _validate_entry(index: int, entry: Any, errors: list[str]) -> None:
    if not isinstance(entry, dict):
        errors.append(f"예외[{index}] 는 매핑이어야 한다: {entry!r}")
        return

    missing = [f for f in REQUIRED_FIELDS if not str(entry.get(f, "")).strip()]
    severity = str(entry.get("severity", "")).strip().lower()

    if severity and severity not in VALID_SEVERITIES:
        errors.append(f"예외[{index}] severity 가 올바르지 않다: {severity}")

    scope = str(entry.get("scope", "")).strip().lower()
    if scope and scope not in VALID_SCOPES:
        errors.append(f"예외[{index}] scope 가 올바르지 않다: {scope}")

    # high/critical 은 필수 필드 누락을 절대 허용하지 않는다.
    if severity in ENFORCED_SEVERITIES and missing:
        errors.append(
            f"예외[{index}] ({severity}) 는 필수 필드 누락 금지 — 누락: {missing}"
        )
    elif missing:
        # 그 외 심각도도 필드는 권장 — 누락 시 경고성 실패(추적 가능성 유지).
        errors.append(f"예외[{index}] 필수 필드 누락: {missing}")

    expires = str(entry.get("expires", "")).strip()
    if expires:
        try:
            expiry_date = dt.date.fromisoformat(expires)
        except ValueError:
            errors.append(f"예외[{index}] expires 는 YYYY-MM-DD 형식이어야 한다: {expires}")
        else:
            if expiry_date < dt.date.today():
                errors.append(
                    f"예외[{index}] ({entry.get('id')}) 예외가 만료됨({expires}) — 무기한 예외 금지, 재검토 필요"
                )


def main() -> int:
    if not EXCEPTIONS_PATH.is_file():
        print(f"scan 예외 파일이 없다: {EXCEPTIONS_PATH}", file=sys.stderr)
        return 1

    data = yaml.safe_load(EXCEPTIONS_PATH.read_text(encoding="utf-8")) or {}
    exceptions = data.get("exceptions")
    if exceptions is None:
        exceptions = []
    if not isinstance(exceptions, list):
        print("`exceptions` 는 리스트여야 한다", file=sys.stderr)
        return 1

    errors: list[str] = []
    for index, entry in enumerate(exceptions):
        _validate_entry(index, entry, errors)

    if errors:
        print("scan 예외 검증 실패:", file=sys.stderr)
        for err in errors:
            print(f"  - {err}", file=sys.stderr)
        return 1

    print(f"scan 예외 검증 통과: {len(exceptions)} 건(필수 필드·만료일 OK)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
