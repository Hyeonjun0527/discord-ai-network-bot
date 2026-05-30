"""커뮤니티 Provider Agent — 내 PC의 로컬 Ollama 를 중앙 서버에 연결한다.

구성:
- ``constants`` / ``protocol`` — WS 계약(중앙 서버와 동일 와이어, 차수 1)
- ``config`` — 설정/CLI (차수 1)
- ``ollama`` — localhost Ollama 호출 (차수 3)
- ``connection`` — WS 연결·인증·재연결 (차수 2)
- ``agent`` — 추론 처리 오케스트레이션 (차수 3)
"""
from __future__ import annotations

from .constants import AGENT_VERSION, ErrorCode, FrameType

__all__ = ["AGENT_VERSION", "FrameType", "ErrorCode"]
