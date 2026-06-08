#!/usr/bin/env python3
"""릴리스 전 게이트: 온보딩 기본 이미지 모델 URL 이 살아있는지(HTTP 200) 확인한다.

신규설치 신뢰성 감사 후속 — HuggingFace 가 repo 를 옮기거나 모델을 gated/삭제하면 익명 GET 이
404/401/403 이 되어 **전 사용자**의 이미지 모델 다운로드가 깨진다(`sd_setup._download`). 그 깨진
URL 을 들고 릴리스가 나가는 걸 막기 위해, `sd_setup.MODELS`(SSOT)의 url 을 여기서 HEAD 로 검증한다.

CI(agent-build) 에서 빌드 전 1회 실행 → 하나라도 200 이 아니면 비0 종료로 릴리스 실패시킨다.
일시적 네트워크 흔들림은 재시도로 흡수. 로컬에서도 `python scripts/check_model_urls.py` 로 점검 가능.
"""

from __future__ import annotations

import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

# provider-agent 패키지를 import 경로에 추가(스크립트는 리포 루트에서 실행).
_SRC = Path(__file__).resolve().parents[1] / "provider-agent" / "src"
sys.path.insert(0, str(_SRC))

from provider_agent.sd_setup import MODELS  # noqa: E402

_TIMEOUT = 30
_RETRIES = 3
_UA = "nexa-model-url-check/1"


def _head_ok(url: str) -> tuple[bool, str]:
    """URL 이 200(리다이렉트 따라간 최종)인지. (ok, detail). 일시 오류는 재시도."""
    last = "?"
    for attempt in range(_RETRIES):
        req = urllib.request.Request(url, method="HEAD", headers={"User-Agent": _UA})
        try:
            with urllib.request.urlopen(req, timeout=_TIMEOUT) as resp:  # noqa: S310 - https 고정
                code = resp.status
                if code == 200:
                    size = resp.headers.get("Content-Length") or resp.headers.get("x-linked-size") or "?"
                    return True, f"200 · {size} bytes"
                last = f"HTTP {code}"
        except urllib.error.HTTPError as e:
            # 404/401/403 은 재시도해도 동일(이전/삭제/gated) → 즉시 실패 처리.
            if e.code in (401, 403, 404, 410):
                return False, f"HTTP {e.code}"
            last = f"HTTP {e.code}"
        except (urllib.error.URLError, TimeoutError, OSError) as e:
            last = f"{type(e).__name__}: {e}"
        if attempt < _RETRIES - 1:
            time.sleep(2 * (attempt + 1))
    return False, last


def main() -> int:
    failures: list[str] = []
    for m in MODELS:
        url = m["url"]
        ok, detail = _head_ok(url)
        mark = "OK " if ok else "!! "
        print(f"{mark}[{m['id']}] {detail} — {url}")
        if not ok:
            failures.append(f"{m['id']} ({m['filename']}): {detail}")
    if failures:
        print("\n릴리스 게이트 실패 — 다음 모델 URL 이 살아있지 않습니다:", file=sys.stderr)
        for f in failures:
            print(f"  · {f}", file=sys.stderr)
        print("sd_setup.MODELS 의 url 을 살아있는 미러로 교체하거나 모델을 교체하세요.", file=sys.stderr)
        return 1
    print(f"\n✅ 기본 이미지 모델 URL {len(MODELS)}개 전부 살아있음(HTTP 200).")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
