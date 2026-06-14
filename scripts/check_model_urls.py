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
_PERMANENT_HTTP_ERRORS = {401, 403, 404, 410}


def _head_ok(url: str) -> tuple[bool, str, bool]:
    """URL 이 200인지 확인한다. 반환값: (ok, detail, transient_failure)."""
    last = "?"
    transient = False
    for attempt in range(_RETRIES):
        req = urllib.request.Request(url, method="HEAD", headers={"User-Agent": _UA})
        try:
            with urllib.request.urlopen(req, timeout=_TIMEOUT) as resp:  # noqa: S310 - https 고정
                code = resp.status
                if code == 200:
                    size = resp.headers.get("Content-Length") or resp.headers.get("x-linked-size") or "?"
                    return True, f"200 · {size} bytes", False
                last = f"HTTP {code}"
                transient = True
        except urllib.error.HTTPError as e:
            if e.code in _PERMANENT_HTTP_ERRORS:
                return False, f"HTTP {e.code}", False
            transient = True
            if e.code == 429:
                last = "HTTP 429 (rate limited)"
            else:
                last = f"HTTP {e.code}"
        except (urllib.error.URLError, TimeoutError, OSError) as e:
            transient = True
            last = f"{type(e).__name__}: {e}"
        if attempt < _RETRIES - 1:
            time.sleep(2 * (attempt + 1))
    return False, f"{last} after {_RETRIES} attempts", transient


def main() -> int:
    permanent_failures: list[str] = []
    transient_failures: list[str] = []
    for m in MODELS:
        url = m["url"]
        ok, detail, transient = _head_ok(url)
        mark = "OK " if ok else "!! "
        print(f"{mark}[{m['id']}] {detail} — {url}")
        if ok:
            continue
        failure = f"{m['id']} ({m['filename']}): {detail}"
        if transient:
            transient_failures.append(failure)
        else:
            permanent_failures.append(failure)
    if permanent_failures or transient_failures:
        print("\n릴리스 게이트 실패 — 다음 모델 URL 을 확인해야 합니다:", file=sys.stderr)
        for f in permanent_failures:
            print(f"  · {f}", file=sys.stderr)
        for f in transient_failures:
            print(f"  · {f}", file=sys.stderr)
        if permanent_failures:
            print("sd_setup.MODELS 의 url 을 살아있는 미러로 교체하거나 모델을 교체하세요.", file=sys.stderr)
        if transient_failures:
            print("HTTP 429/5xx/네트워크 오류는 일시적일 수 있습니다. CI 는 이 체크 전체를 3회 재시도합니다.", file=sys.stderr)
        return 1
    print(f"\n✅ 기본 이미지 모델 URL {len(MODELS)}개 전부 살아있음(HTTP 200).")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
