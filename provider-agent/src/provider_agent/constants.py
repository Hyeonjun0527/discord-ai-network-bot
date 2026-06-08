"""WS 프로토콜 상수 — Kotlin 중앙 서버(central-server)와 동일 계약 (api.md §8).

공유 와이어 상수(PROTOCOL_VERSION·MAX_FRAME_BYTES·MAX_PROMPT_CHARS·FrameType·ErrorCode·
ALLOWED_OPTION_KEYS)는 SSOT ``protocol/wire-contract.json`` 에서 ``scripts/gen_wire_contract.py`` 로
생성된 ``_wire_contract_generated`` 에 있다. Kotlin ``Frame.kt`` 와 단일 생성기로 동기화돼 drift 가
불가능하다. 여기서는 그 공유 상수를 재노출(re-export)하고, **에이전트 전용** 상수만 추가로 정의한다.
"""
from __future__ import annotations

from typing import Final

from provider_agent._wire_contract_generated import (
    ALLOWED_OPTION_KEYS as ALLOWED_OPTION_KEYS,
)
from provider_agent._wire_contract_generated import (
    MAX_FRAME_BYTES as MAX_FRAME_BYTES,
)
from provider_agent._wire_contract_generated import (
    MAX_PROMPT_CHARS as MAX_PROMPT_CHARS,
)
from provider_agent._wire_contract_generated import (
    PROTOCOL_VERSION as PROTOCOL_VERSION,
)
from provider_agent._wire_contract_generated import (
    ErrorCode as ErrorCode,
)
from provider_agent._wire_contract_generated import (
    FrameType as FrameType,
)

# ── 에이전트 전용 상수(와이어 계약 아님 — 클라이언트 측 안전 기본값) ──────────────
APP_DISPLAY_NAME: Final[str] = "Nexa"
MAC_APP_BUNDLE: Final[str] = "Nexa.app"
GUI_MAC_ASSET: Final[str] = "nexa-macos.zip"
GUI_WIN_ASSET: Final[str] = "nexa-windows.exe"
# 안전 기본값(차수: 일반 사용자 배포). 0 = 무제한이지만, 무제한은 --allow-unlimited 로만 가능.
DEFAULT_DAILY_LIMIT: Final[int] = 15
# 배포 온보딩/GUI 제공 모델 기본값.
# packaging/assets.json 의 defaultTextModel 과 드리프트 검사를 통과해야 한다.
DEFAULT_TEXT_MODEL: Final[str] = "exaone3.5:7.8b"
# 단일 응답 텍스트 상한(문자). 무거운/폭주 응답이 끝없이 커지지 않게 에이전트가 자른다.
MAX_RESPONSE_CHARS: Final[int] = 24_000
# 응답 토큰 상한(서버 옵션 num_predict 의 하드 캡). 서버가 더 큰 값을 줘도 이 값으로 클램프.
MAX_NUM_PREDICT: Final[int] = 2_048
# 이미지(base64) 전송 시 ChunkFrame 한 조각의 최대 문자 수(1MB 프레임 한계 내, SD Phase 2).
IMAGE_CHUNK_CHARS: Final[int] = 600_000

AGENT_VERSION: Final[str] = "0.34.0"

__all__ = [
    "AGENT_VERSION",
    "ALLOWED_OPTION_KEYS",
    "APP_DISPLAY_NAME",
    "DEFAULT_DAILY_LIMIT",
    "DEFAULT_TEXT_MODEL",
    "ErrorCode",
    "FrameType",
    "GUI_MAC_ASSET",
    "GUI_WIN_ASSET",
    "IMAGE_CHUNK_CHARS",
    "MAC_APP_BUNDLE",
    "MAX_FRAME_BYTES",
    "MAX_NUM_PREDICT",
    "MAX_PROMPT_CHARS",
    "MAX_RESPONSE_CHARS",
    "PROTOCOL_VERSION",
]
