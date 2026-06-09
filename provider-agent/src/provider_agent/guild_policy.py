"""서버(guild)별 정책·일일 한도·동시성을 한곳에서 소유·강제하는 협력자(SE-182).

세 상태(정책 override / 남은 일일 한도 / 길드 세마포어)는 강하게 얽혀 있다 — 정책이 한도·
동시 처리 상한을 정하고, 정책이 바뀌면 잔여·세마포어를 **함께** 폐기해야 stale 참조가 안 생긴다.
ProviderAgent 에 흩어져 있던 이 셋을 한 협력자로 묶어 일관성(원자적 변경·중앙 리로드)을 보장한다.
"""

from __future__ import annotations

import asyncio

from .config import AgentConfig


class GuildPolicyManager:
    """길드별 일일 한도·동시 처리·최대 시간·모델/이미지/일시중지 정책을 소유.

    - 일일 한도는 **길드별 독립** 카운트(전역 합산 공유 X). 0 = 무제한(dict 미사용).
    - 동시 처리 상한도 길드별: 길드 전용 세마포어(lazy). 전역 머신 보호 세마포어는 ProviderAgent 별도 보유.
    - 키 = guild_id(None = 길드 미상 토큰 연결 폴백). lazy init: 첫 접근 시 그 길드 값으로 채움.
    - 정책 override 없는 길드는 전역 기본(cfg)을 쓴다.
    """

    def __init__(self, cfg: AgentConfig) -> None:
        self._cfg = cfg
        # 서버별 정책 override {guild_id: {daily_limit, max_concurrency, max_seconds,
        # chatModels, imageEnabled, paused}} — 데스크톱 앱 G3 가 설정. reload() 에서 로드.
        self._policies: dict[int, dict] = {}
        # 길드별 남은 일일 요청 수(lazy init). 키 None = 길드 미상 폴백.
        self._remaining: dict[int | None, int] = {}
        # 길드별 동시성 세마포어(lazy). 정책 변경 시 폐기→재생성.
        self._sems: dict[int | None, asyncio.Semaphore] = {}

    # ── 일일 한도(서버별) ───────────────────────────────────────────────
    def limit_for(self, guild_id: int | None) -> int:
        """이 길드에 적용할 일일 한도. 서버별 override(G3) 가 있으면 그 값, 없으면 전역 기본."""
        if guild_id is not None:
            pol = self._policies.get(guild_id)
            if pol is not None and pol.get("daily_limit") is not None:
                return max(0, int(pol["daily_limit"]))
        return self._cfg.daily_limit

    def remaining_for(self, guild_id: int | None) -> int:
        """이 길드의 남은 일일 한도. 무제한이면 0(센티넬). 첫 접근 시 그 길드 한도로 init."""
        limit = self.limit_for(guild_id)
        if limit <= 0:
            return 0  # 무제한(hello 의 remaining=0 은 '한도 없음'을 뜻함)
        if guild_id not in self._remaining:
            self._remaining[guild_id] = limit
        return self._remaining[guild_id]

    def decrement_remaining(self, guild_id: int | None) -> None:
        """요청 처리 직전 이 길드 잔여 1 감소(호출자가 limit>0 을 보장). hot-path."""
        self._remaining[guild_id] = self.remaining_for(guild_id) - 1

    # ── 동시 처리·최대 시간(서버별) ──────────────────────────────────────
    def concurrency_for(self, guild_id: int | None) -> int:
        """이 길드에 적용할 동시 처리 상한. 서버별 override(G3) 우선, 없으면 전역."""
        if guild_id is not None:
            pol = self._policies.get(guild_id)
            if pol is not None and pol.get("max_concurrency") is not None:
                return max(1, int(pol["max_concurrency"]))
        return self._cfg.max_concurrency

    def guild_sem(self, guild_id: int | None) -> asyncio.Semaphore:
        """이 길드 전용 동시성 세마포어(lazy). 정책 변경 시 set_policy 에서 폐기→재생성."""
        sem = self._sems.get(guild_id)
        if sem is None:
            sem = asyncio.Semaphore(self.concurrency_for(guild_id))
            self._sems[guild_id] = sem
        return sem

    def max_seconds_for(self, guild_id: int | None) -> float:
        """이 길드 1건 최대 처리 시간(초). 서버별 override 만 적용(없으면 0 = 추가 상한 없음)."""
        if guild_id is not None:
            pol = self._policies.get(guild_id)
            if pol is not None and pol.get("max_seconds") is not None:
                return max(0.0, float(pol["max_seconds"]))
        return 0.0

    # ── 핸드셰이크 입력(에이전트 상태를 주입받아 정책과 결합) ──────────────
    def models_for(self, guild_id: int | None, all_models: list[str]) -> list[str]:
        """이 길드에 광고할 채팅 모델. chatModels override(현재 제공 가능한 것만), 없거나 비면 전체."""
        if guild_id is not None:
            sel = self._policies.get(guild_id, {}).get("chatModels")
            if isinstance(sel, list) and sel:
                picked = [m for m in sel if m in all_models]
                if picked:
                    return picked
        return all_models

    def image_for(self, guild_id: int | None, image_ready: bool) -> bool:
        """이 길드에 이미지(SD) capability 를 광고할지. 준비됨 + 길드가 명시 비활성(imageEnabled=False)이 아님."""
        if not image_ready:
            return False
        return guild_id is None or self._policies.get(guild_id, {}).get("imageEnabled") is not False

    def paused_for(self, guild_id: int | None) -> bool:
        """이 길드 제공이 일시중지 상태인지(정책 paused)."""
        return bool(guild_id is not None and self._policies.get(guild_id, {}).get("paused"))

    # ── 정책 조회·변경·리로드 ────────────────────────────────────────────
    def policy(self, guild_id: int) -> dict:
        """현재 적용 중인 이 서버의 정책(전역 기본값과 병합)."""
        pol = dict(self._policies.get(guild_id, {}))
        pol.setdefault("daily_limit", self._cfg.daily_limit)
        return pol

    def set_policy(self, guild_id: int, policy: dict) -> None:
        """이 서버 정책 override 저장·적용. 한도/동시성이 바뀌었을 수 있으니 잔여·세마포어를 폐기
        → 다음 접근에 새 값으로 재생성(stale 방지). 재광고(hello)는 호출자(ProviderAgent)가 수행."""
        from .config_file import set_guild_policy as _save

        _save(guild_id, policy)
        self._policies[guild_id] = {**self._policies.get(guild_id, {}), **policy}
        # 새 한도로 잔여 리셋, 새 상한으로 세마포어 재생성(다음 접근에 lazy reinit).
        self._remaining.pop(guild_id, None)
        self._sems.pop(guild_id, None)

    def reload(self, policies: dict[int, dict]) -> None:
        """저장된 서버별 정책 전체 로드(startup). 잔여·세마포어는 새 정책 기준으로 재생성되도록 비운다."""
        self._policies = dict(policies)
        self._remaining.clear()
        self._sems.clear()

    def summary(self) -> str:
        """서버별 일일 잔여 요약(로깅). 무제한이면 '무제한', 아니면 길드별 잔여(아직 없으면 한도값)."""
        if self._cfg.daily_limit <= 0:
            return "무제한"
        if not self._remaining:
            return f"{self._cfg.daily_limit}/서버"
        return " ".join(f"{g}:{n}" for g, n in self._remaining.items())
