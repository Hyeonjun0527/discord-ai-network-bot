"""데스크톱 앱 문구 i18n(ko/en/ja).

문구의 SSOT 는 저장소 루트 `i18n/messages.json`(agent 섹션)이고, `scripts/gen_i18n.py` 가 이 패키지의
`i18n_messages.json` 생성본을 만든다(봇·웹과 동일 SSOT 공유). 언어는 인자 > OS 로케일 > ko 순.
"""

from __future__ import annotations

import json
import locale as _locale
from functools import lru_cache
from importlib import resources

SUPPORTED: tuple[str, ...] = ("ko", "en", "ja")
DEFAULT = "ko"


@lru_cache(maxsize=1)
def _table() -> dict[str, dict[str, str]]:
    raw = json.loads(
        resources.files("provider_agent").joinpath("i18n_messages.json").read_text("utf-8")
    )
    return {k: v for k, v in raw.items() if not str(k).startswith("_")}


def detect_lang() -> str:
    """OS 로케일을 지원 언어로 정규화. 알 수 없으면 [DEFAULT]."""
    try:
        code = (_locale.getlocale()[0] or "").lower()
    except (ValueError, TypeError):
        code = ""
    for s in SUPPORTED:
        if code.startswith(s):
            return s
    return DEFAULT


def t(key: str, lang: str | None = None) -> str:
    """문구 조회. 미지원 언어/키는 en→ko→키 폴백."""
    entry = _table().get(key)
    if not entry:
        return key
    code = lang if lang in SUPPORTED else detect_lang()
    return entry.get(code) or entry.get("en") or entry.get(DEFAULT) or key
