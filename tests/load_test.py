"""동시 요청 부하 테스트 (#64).

기존에는 ``localhost:11434`` 의 실제 Ollama 인스턴스를 요구해 CI 에서 돌 수
없었다. 여기서는 ``OllamaClient._generate_sync`` 를 mock 해 네트워크 없이
N 개의 동시 요청과 세마포어(동시 실행 상한) 동작을 결정적으로 검증한다.

- 네트워크 호출 없음(urlopen 미사용).
- ``python -m pytest`` 로 수집되는 일반 pytest 테스트 함수(과거 main 가드 제거).
"""
from __future__ import annotations

import asyncio
import threading
from unittest import mock

import pytest

from discord_assistant.llm import OllamaClient

_MODEL = "llama3.1:8b"
_CONCURRENCY = 10


def _make_client() -> OllamaClient:
    """부하 테스트용 클라이언트(서킷 브레이커 없이 단순 경로)."""
    return OllamaClient(
        base_url="http://test-host:11434",
        default_model=_MODEL,
        timeout_seconds=5,
    )


def test_concurrent_requests_all_succeed() -> None:
    """N 개의 동시 generate() 요청이 모두 성공하고 호출 수가 N 인지 확인한다."""
    client = _make_client()
    calls = {"n": 0}
    lock = threading.Lock()

    def fake_generate_sync(prompt, model, images=None):  # type: ignore[no-untyped-def]
        # asyncio.to_thread 로 별도 스레드에서 호출되므로 카운터를 락으로 보호한다.
        with lock:
            calls["n"] += 1
        return "ok"

    async def run() -> list[object]:
        with mock.patch.object(
            OllamaClient, "_generate_sync", side_effect=fake_generate_sync
        ):
            tasks = [
                client.generate("Hello, respond with one word.")
                for _ in range(_CONCURRENCY)
            ]
            return await asyncio.gather(*tasks, return_exceptions=True)

    results = asyncio.run(run())
    errors = [r for r in results if isinstance(r, Exception)]
    assert errors == [], f"예상치 못한 오류: {errors}"
    assert len(results) == _CONCURRENCY
    assert all(r == "ok" for r in results)
    assert calls["n"] == _CONCURRENCY


def test_concurrent_requests_partial_failures_are_isolated() -> None:
    """일부 요청이 실패해도 ``return_exceptions=True`` 로 나머지는 영향받지 않는다."""
    client = _make_client()
    from discord_assistant.llm import OllamaError

    seq = {"i": 0}
    lock = threading.Lock()

    def fake_generate_sync(prompt, model, images=None):  # type: ignore[no-untyped-def]
        with lock:
            idx = seq["i"]
            seq["i"] += 1
        # 짝수 인덱스는 성공, 홀수 인덱스는 실패(즉시 실패하는 4xx 류로 재시도 차단).
        if idx % 2 == 1:
            raise OllamaError("boom", status_code=400)
        return "ok"

    async def run() -> list[object]:
        with mock.patch.object(
            OllamaClient, "_generate_sync", side_effect=fake_generate_sync
        ):
            tasks = [client.generate("hi") for _ in range(_CONCURRENCY)]
            return await asyncio.gather(*tasks, return_exceptions=True)

    results = asyncio.run(run())
    successes = [r for r in results if r == "ok"]
    errors = [r for r in results if isinstance(r, Exception)]
    assert len(successes) == _CONCURRENCY // 2
    assert len(errors) == _CONCURRENCY // 2
    assert all(isinstance(e, OllamaError) for e in errors)


@pytest.mark.asyncio
async def test_semaphore_caps_concurrent_in_flight_requests() -> None:
    """세마포어로 동시 실행 요청 수 상한이 지켜지는지 검증한다.

    호출부가 ``asyncio.Semaphore`` 로 동시 실행을 제한하는 패턴을 흉내내고,
    동시에 in-flight 상태인 요청 수가 상한(limit)을 넘지 않음을 확인한다.
    실제 네트워크 대신 ``asyncio.sleep`` 으로 작업 지속 시간을 흉내낸다.
    """
    limit = 3
    sem = asyncio.Semaphore(limit)
    in_flight = 0
    max_in_flight = 0
    state_lock = asyncio.Lock()

    async def fake_generate(prompt: str) -> str:
        nonlocal in_flight, max_in_flight
        async with sem:
            async with state_lock:
                in_flight += 1
                max_in_flight = max(max_in_flight, in_flight)
            try:
                # 동시성이 실제로 겹치도록 잠깐 양보한다.
                await asyncio.sleep(0.01)
                return "ok"
            finally:
                async with state_lock:
                    in_flight -= 1

    tasks = [fake_generate("hi") for _ in range(_CONCURRENCY)]
    results = await asyncio.gather(*tasks)

    assert results == ["ok"] * _CONCURRENCY
    # 세마포어가 동시 실행을 limit 이하로 제한했는지 확인.
    assert max_in_flight <= limit
    # 충분한 동시성이 발생했는지(상한에 도달)도 확인해 테스트가 무의미해지지 않게 한다.
    assert max_in_flight == limit
