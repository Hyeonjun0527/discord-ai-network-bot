"""카오스/폴트 인젝션 테스트 (ROADMAP #65).

LLM 클라이언트의 네트워크 경계(``request.urlopen`` 와 ``_generate_sync``)에
연결 거부/타임아웃/HTTP 500/부분 응답/잘못된 JSON 등의 장애를 주입하고, 그 결과
시스템이 다음 4가지 회복성(resilience) 속성을 만족하는지 검증한다.

(a) 적절한 ``LLMError`` 하위 타입으로 변환된다(원시 예외가 그대로 새어나가지 않음).
(b) ``_with_retry`` 가 429/5xx(및 status 없는 네트워크 오류)는 재시도하고,
    4xx 클라이언트 오류는 재시도 없이 즉시 실패한다(``asyncio.sleep`` 은 mock).
(c) 핸들러 경로(``error_hint``)가 사용자에게 친절한 한국어 메시지로 안내하며,
    HTTP 응답 원문(payload)을 사용자 메시지에 노출하지 않는다.
(d) 서킷 브레이커가 연속 실패 임계에 도달하면 열려서(open) 빠르게 실패한다.

네트워크/LLM/Discord 는 전부 mock 한다(실제 호출 없음). ``request.urlopen`` 을
patch 하고, 백오프 ``asyncio.sleep`` 도 mock 해 테스트를 결정적으로 만든다.
"""
from __future__ import annotations

import asyncio
import io
import json
import unittest
from unittest import mock
from urllib import error as urllib_error

from discord_assistant.llm import (
    AnthropicClient,
    AnthropicError,
    CircuitBreaker,
    CircuitBreakerOpenError,
    GeminiClient,
    GeminiError,
    LLMError,
    OllamaClient,
    OllamaError,
    OpenAIClient,
    OpenAIError,
    _is_retryable,
    _with_circuit_breaker,
    _with_retry,
)
from discord_assistant.ui import error_hint

# ---------------------------------------------------------------------------
# 네트워크 폴트 인젝션용 가짜 응답/오류 (test_llm.py 의 헬퍼와 동일한 형태).
# request.urlopen 은 with-블록 컨텍스트 매니저를 돌려주므로, read()/__enter__/
# __exit__ 만 흉내내면 _generate_sync 의 파싱 경로를 그대로 탈 수 있다.
# ---------------------------------------------------------------------------


class _FakeHTTPResponse:
    """``request.urlopen`` 의 with-블록용 컨텍스트 매니저 가짜 응답.

    ``response.read().decode("utf-8")`` 흐름을 흉내내려고 bytes 본문을 들고 있다.
    부분 응답(잘린 JSON)·잘못된 JSON 주입에도 이 클래스를 그대로 사용한다.
    """

    def __init__(self, body: bytes) -> None:
        self._body = body

    def __enter__(self) -> "_FakeHTTPResponse":
        return self

    def __exit__(self, *exc_info: object) -> None:
        return None

    def read(self) -> bytes:
        return self._body


def _json_response(payload: dict) -> _FakeHTTPResponse:
    """dict 를 JSON bytes 로 직렬화한 정상 가짜 HTTP 응답."""
    return _FakeHTTPResponse(json.dumps(payload).encode("utf-8"))


def _http_error(code: int, body: str = "secret server detail xyz") -> urllib_error.HTTPError:
    """주어진 상태 코드의 HTTPError 를 만든다(HTTP 500 등 폴트 주입용).

    코드가 ``detail = exc.read().decode(...)`` 를 호출하므로, fp 로 BytesIO 를
    넣어 본문을 읽을 수 있게 한다. body 는 "서버 원문"으로, 사용자 메시지에
    절대 노출되면 안 되는 비밀 디테일을 흉내낸다.
    """
    return urllib_error.HTTPError(
        url="https://example.test",
        code=code,
        msg="error",
        hdrs=None,  # type: ignore[arg-type]
        fp=io.BytesIO(body.encode("utf-8")),
    )


def _patch_urlopen(side_effect=None, return_value=None):
    """``request.urlopen`` 을 patch 하는 헬퍼(네트워크 차단)."""
    if side_effect is not None:
        return mock.patch(
            "discord_assistant.llm.request.urlopen", side_effect=side_effect
        )
    return mock.patch(
        "discord_assistant.llm.request.urlopen", return_value=return_value
    )


def _patch_sleep() -> mock.AsyncMock:
    """백오프 ``asyncio.sleep`` 을 mock 으로 대체(테스트 결정성)."""
    return mock.patch("discord_assistant.llm.asyncio.sleep", new=mock.AsyncMock())


# 정상 응답 payload (코드 파싱 경로에 맞춤).
_OPENAI_OK = {"choices": [{"message": {"content": "  안녕하세요  "}}]}
_ANTHROPIC_OK = {"content": [{"text": "  반갑습니다  "}]}


# ---------------------------------------------------------------------------
# (a) 폴트 → 적절한 LLMError 하위 타입 변환
#
# urlopen 에 연결 거부(URLError)/타임아웃(TimeoutError)/HTTP 500/부분 응답/잘못된
# JSON 을 주입했을 때, 각 어댑터의 _generate_sync 가 자기 제공자의 LLMError
# 하위 타입으로 변환하는지(원시 예외가 새어나가지 않는지) 검증한다.
# ---------------------------------------------------------------------------


class FaultToLLMErrorTest(unittest.TestCase):
    """폴트 인젝션 시 적절한 LLMError 하위 타입으로 변환되는지 검증 (a)."""

    def test_connection_refused_maps_to_provider_error(self) -> None:
        # 연결 거부(URLError)는 각 제공자의 LLMError 하위 타입이 되어야 한다.
        cases = [
            (OpenAIClient(api_key="sk-x"), "gpt-4o-mini", OpenAIError),
            (AnthropicClient(api_key="sk-ant"), "claude-haiku-4-5", AnthropicError),
            (GeminiClient(api_key="g-key"), "gemini-1.5-flash", GeminiError),
            (
                OllamaClient(base_url="http://x", default_model="llama3.1:8b"),
                "llama3.1:8b",
                OllamaError,
            ),
        ]
        for client, model, exc_type in cases:
            with self.subTest(provider=exc_type.__name__):
                with _patch_urlopen(
                    side_effect=urllib_error.URLError("Connection refused")
                ):
                    with self.assertRaises(exc_type) as cm:
                        client._generate_sync("hi", model)
                # 네트워크 오류는 HTTP status 가 없다(→ 재시도 가능 분류).
                self.assertIsNone(cm.exception.status_code)
                # LLMError 의 하위 타입이어야 한다(상위 핸들러가 한 번에 처리 가능).
                self.assertIsInstance(cm.exception, LLMError)

    def test_timeout_maps_to_provider_error(self) -> None:
        # 타임아웃(TimeoutError)도 각 제공자 LLMError 로 변환된다.
        cases = [
            (OpenAIClient(api_key="sk-x"), "gpt-4o-mini", OpenAIError),
            (AnthropicClient(api_key="sk-ant"), "claude-haiku-4-5", AnthropicError),
            (GeminiClient(api_key="g-key"), "gemini-1.5-flash", GeminiError),
            (
                OllamaClient(base_url="http://x", default_model="llama3.1:8b"),
                "llama3.1:8b",
                OllamaError,
            ),
        ]
        for client, model, exc_type in cases:
            with self.subTest(provider=exc_type.__name__):
                with _patch_urlopen(side_effect=TimeoutError("timed out")):
                    with self.assertRaises(exc_type) as cm:
                        client._generate_sync("hi", model)
                # 타임아웃 안내 문구는 "시간"(초과) 을 포함한다.
                self.assertIn("시간", str(cm.exception))

    def test_http_500_maps_to_provider_error_with_status(self) -> None:
        # HTTP 500 은 status_code=500 을 담은 LLMError 하위 타입이 되어야 한다.
        cases = [
            (OpenAIClient(api_key="sk-x"), "gpt-4o-mini", OpenAIError),
            (AnthropicClient(api_key="sk-ant"), "claude-haiku-4-5", AnthropicError),
            (GeminiClient(api_key="g-key"), "gemini-1.5-flash", GeminiError),
        ]
        for client, model, exc_type in cases:
            with self.subTest(provider=exc_type.__name__):
                with _patch_urlopen(side_effect=_http_error(500)):
                    with self.assertRaises(exc_type) as cm:
                        client._generate_sync("hi", model)
                self.assertEqual(cm.exception.status_code, 500)

    def test_invalid_json_maps_to_provider_error(self) -> None:
        # 응답 본문이 JSON 이 아니면(잘못된 JSON) 제공자 LLMError 로 변환.
        cases = [
            (OpenAIClient(api_key="sk-x"), "gpt-4o-mini", OpenAIError),
            (AnthropicClient(api_key="sk-ant"), "claude-haiku-4-5", AnthropicError),
            (GeminiClient(api_key="g-key"), "gemini-1.5-flash", GeminiError),
            (
                OllamaClient(base_url="http://x", default_model="llama3.1:8b"),
                "llama3.1:8b",
                OllamaError,
            ),
        ]
        for client, model, exc_type in cases:
            with self.subTest(provider=exc_type.__name__):
                with _patch_urlopen(
                    return_value=_FakeHTTPResponse(b"not-json{{{garbage")
                ):
                    with self.assertRaises(exc_type):
                        client._generate_sync("hi", model)

    def test_partial_truncated_json_maps_to_provider_error(self) -> None:
        # 부분 응답(JSON 이 중간에 잘림) → json.loads 실패 → 제공자 LLMError.
        # 연결이 끊겨 본문이 절반만 도착한 상황을 흉내낸다(bytes 는 ASCII 만 가능).
        truncated = b'{"choices": [{"message": {"content": "hello'
        client = OpenAIClient(api_key="sk-x")
        with _patch_urlopen(return_value=_FakeHTTPResponse(truncated)):
            with self.assertRaises(OpenAIError):
                client._generate_sync("hi", "gpt-4o-mini")

    def test_partial_valid_json_but_unexpected_shape(self) -> None:
        # 부분 응답이지만 JSON 자체는 유효한 경우(필드 누락) — 모양 해석 실패로
        # 제공자 LLMError 가 되어야 한다(KeyError/IndexError 경로).
        cases = [
            (OpenAIClient(api_key="sk-x"), "gpt-4o-mini", OpenAIError, {"choices": []}),
            (
                AnthropicClient(api_key="sk-ant"),
                "claude-haiku-4-5",
                AnthropicError,
                {"content": []},
            ),
            (
                GeminiClient(api_key="g-key"),
                "gemini-1.5-flash",
                GeminiError,
                {"candidates": []},
            ),
        ]
        for client, model, exc_type, payload in cases:
            with self.subTest(provider=exc_type.__name__):
                with _patch_urlopen(return_value=_json_response(payload)):
                    with self.assertRaises(exc_type):
                        client._generate_sync("hi", model)


# ---------------------------------------------------------------------------
# (b) _with_retry: 429/5xx 재시도, 4xx 즉시 실패 (asyncio.sleep mock)
#
# 두 가지로 검증한다:
#   1) 순수 _with_retry/_is_retryable 단위 — 폴트 분류가 맞는지.
#   2) 실제 generate() 통합 — urlopen 에 폴트를 주입했을 때 재시도 횟수.
# ---------------------------------------------------------------------------


class RetryFaultClassificationTest(unittest.TestCase):
    """폴트별 재시도 가능 분류 + _with_retry 의 재시도/즉시실패 동작 (b)."""

    def test_4xx_classified_not_retryable(self) -> None:
        for code in (400, 401, 403, 404, 422):
            self.assertFalse(_is_retryable(LLMError("x", status_code=code)))

    def test_429_and_5xx_classified_retryable(self) -> None:
        for code in (429, 500, 502, 503, 504):
            self.assertTrue(_is_retryable(LLMError("x", status_code=code)))

    def test_network_fault_without_status_is_retryable(self) -> None:
        # 연결 거부/타임아웃 등 status 없는 네트워크 폴트는 일시적일 수 있어 재시도.
        self.assertTrue(_is_retryable(OpenAIError("connection refused")))

    def test_4xx_fails_immediately_no_sleep(self) -> None:
        # 4xx 는 재시도 없이 즉시 실패, 백오프 sleep 미호출.
        calls = {"n": 0}

        async def fail_400() -> str:
            calls["n"] += 1
            raise OpenAIError("bad request", status_code=400)

        async def run() -> None:
            with _patch_sleep() as sleep_mock:
                with self.assertRaises(OpenAIError):
                    await _with_retry(fail_400, max_attempts=4, delay=1.0)
                sleep_mock.assert_not_called()

        asyncio.run(run())
        self.assertEqual(calls["n"], 1)

    def test_5xx_retries_up_to_max_attempts(self) -> None:
        # 5xx 는 max_attempts 만큼 시도하고, 그 사이 백오프 sleep 이 호출된다.
        calls = {"n": 0}

        async def fail_500() -> str:
            calls["n"] += 1
            raise AnthropicError("server error", status_code=500)

        async def run() -> None:
            with _patch_sleep() as sleep_mock:
                with self.assertRaises(AnthropicError):
                    await _with_retry(fail_500, max_attempts=3, delay=1.0)
                # 3회 시도 → 시도 사이 sleep 2회.
                self.assertEqual(sleep_mock.await_count, 2)

        asyncio.run(run())
        self.assertEqual(calls["n"], 3)

    def test_recovers_after_transient_5xx(self) -> None:
        # 일시적 5xx 후 성공 — 재시도가 회복으로 이어지는 골든 패스.
        calls = {"n": 0}

        async def flaky() -> str:
            calls["n"] += 1
            if calls["n"] < 2:
                raise OpenAIError("server hiccup", status_code=503)
            return "ok"

        async def run() -> str:
            with _patch_sleep():
                return await _with_retry(flaky, max_attempts=3, delay=1.0)

        self.assertEqual(asyncio.run(run()), "ok")
        self.assertEqual(calls["n"], 2)


class GenerateRetryFaultTest(unittest.TestCase):
    """generate() 통합 경로에서 폴트 주입 시 재시도/즉시실패 (b)."""

    def test_generate_4xx_fault_no_retry(self) -> None:
        client = OpenAIClient(api_key="sk-x")
        with _patch_urlopen(side_effect=_http_error(400)) as urlopen_mock, _patch_sleep() as sleep_mock:
            with self.assertRaises(OpenAIError):
                asyncio.run(client.generate("hi"))
        # 4xx → urlopen 단 1회, sleep 미호출.
        self.assertEqual(urlopen_mock.call_count, 1)
        sleep_mock.assert_not_called()

    def test_generate_500_fault_retried(self) -> None:
        client = AnthropicClient(api_key="sk-ant")
        with _patch_urlopen(side_effect=_http_error(500)) as urlopen_mock, _patch_sleep() as sleep_mock:
            with self.assertRaises(AnthropicError):
                asyncio.run(client.generate("hi"))
        # 기본 max_attempts=2 → urlopen 2회, 사이에 sleep 1회.
        self.assertEqual(urlopen_mock.call_count, 2)
        self.assertEqual(sleep_mock.await_count, 1)

    def test_generate_connection_refused_retried(self) -> None:
        # status 없는 네트워크 폴트(연결 거부)도 재시도된다.
        client = OpenAIClient(api_key="sk-x")
        with _patch_urlopen(
            side_effect=urllib_error.URLError("Connection refused")
        ) as urlopen_mock, _patch_sleep():
            with self.assertRaises(OpenAIError):
                asyncio.run(client.generate("hi"))
        self.assertEqual(urlopen_mock.call_count, 2)

    def test_generate_recovers_after_transient_fault(self) -> None:
        # 첫 호출 503 폴트, 두 번째 정상 → generate 가 회복된 텍스트를 반환.
        client = OpenAIClient(api_key="sk-x")
        responses: list = [_http_error(503), _json_response(_OPENAI_OK)]

        def fake_urlopen(req, timeout=None):  # type: ignore[no-untyped-def]
            item = responses.pop(0)
            if isinstance(item, urllib_error.HTTPError):
                raise item
            return item

        with mock.patch(
            "discord_assistant.llm.request.urlopen", side_effect=fake_urlopen
        ), _patch_sleep():
            result = asyncio.run(client.generate("hi"))
        self.assertEqual(result, "안녕하세요")


# ---------------------------------------------------------------------------
# (c) 핸들러가 친절 메시지로 안내 + 원문 payload 비노출
#
# error_hint(exc) 는 bot.py 의 _make_error_embed 가 LLMError 를 사용자에게 알릴
# 때 쓰는 사용자용 한국어 문구 매핑이다. 폴트별로 (1) 친절한 한국어 안내가
# 나오고, (2) HTTP 응답 원문(서버 비밀 디테일)이 사용자 메시지에 새어나가지
# 않는지 검증한다.
# ---------------------------------------------------------------------------


class HandlerFriendlyMessageTest(unittest.TestCase):
    """error_hint 가 폴트를 친절 메시지로 변환하고 원문을 숨기는지 검증 (c)."""

    def test_error_hint_for_401_403(self) -> None:
        # 인증/권한 실패는 API 키 재등록을 안내한다.
        for code in (401, 403):
            hint = error_hint(OpenAIError("auth failed", status_code=code))
            self.assertIn("API 키", hint)

    def test_error_hint_for_429_rate_limit(self) -> None:
        hint = error_hint(AnthropicError("rate limited", status_code=429))
        # 레이트리밋은 "잠시 기다렸다 다시" 류 안내.
        self.assertIn("다시 시도", hint)

    def test_error_hint_for_5xx(self) -> None:
        hint = error_hint(GeminiError("server boom", status_code=503))
        self.assertIn("일시적", hint)

    def test_error_hint_for_circuit_breaker(self) -> None:
        hint = error_hint(CircuitBreakerOpenError("open"))
        self.assertIn("다시 시도", hint)

    def test_error_hint_for_timeout(self) -> None:
        hint = error_hint(TimeoutError("timed out"))
        self.assertIn("다시 시도", hint)

    def test_hint_never_leaks_http_response_body(self) -> None:
        # 폴트 주입으로 만든 실제 LLMError 의 사용자 안내(error_hint)에 서버
        # 응답 원문(비밀 디테일)이 절대 포함되지 않아야 한다.
        secret = "super-secret-internal-trace-99887766"
        client = OpenAIClient(api_key="sk-x")
        with _patch_urlopen(side_effect=_http_error(500, body=secret)):
            with self.assertRaises(OpenAIError) as cm:
                client._generate_sync("hi", "gpt-4o-mini")
        exc = cm.exception
        # 예외 메시지 자체에도, 사용자용 hint 에도 원문이 없어야 한다.
        self.assertNotIn(secret, str(exc))
        self.assertNotIn(secret, error_hint(exc))

    def test_hint_never_leaks_invalid_json_body(self) -> None:
        # 잘못된 JSON 폴트의 경우에도 원시 본문이 사용자 안내에 새어나가지 않는다.
        garbage = "raw-broken-payload-DEADBEEF"
        client = AnthropicClient(api_key="sk-ant")
        with _patch_urlopen(
            return_value=_FakeHTTPResponse(garbage.encode("utf-8"))
        ):
            with self.assertRaises(AnthropicError) as cm:
                client._generate_sync("hi", "claude-haiku-4-5")
        self.assertNotIn(garbage, error_hint(cm.exception))


# ---------------------------------------------------------------------------
# (d) 서킷 브레이커: 연속 실패 후 열려서(open) 빠르게 실패
#
# _with_circuit_breaker 가 _generate_sync 폴트로 연속 실패를 기록하다가 임계에
# 도달하면, before_call 에서 CircuitBreakerOpenError 로 빠르게 실패시켜 더 이상
# 네트워크(urlopen)를 호출하지 않는지 검증한다. 시간 의존을 없애기 위해
# CircuitBreaker.time_fn 을 주입한다.
# ---------------------------------------------------------------------------


class CircuitBreakerChaosTest(unittest.TestCase):
    """폴트 누적 → 서킷 오픈 → 빠른 실패(네트워크 미호출) (d)."""

    def test_breaker_opens_after_consecutive_failures(self) -> None:
        # failure_threshold=2, 4xx(재시도 불가) 폴트로 연속 실패를 누적시킨다.
        # 4xx 를 쓰면 _with_retry 가 1회만 시도하므로 실패 카운트 계산이 단순하다.
        breaker = CircuitBreaker(failure_threshold=2, reset_timeout=30.0)
        client = OpenAIClient(api_key="sk-x", circuit_breaker=breaker)

        async def run() -> None:
            with _patch_urlopen(side_effect=_http_error(400)) as urlopen_mock, _patch_sleep():
                # 1차 실패(record_failure → failures=1, 아직 닫힘).
                with self.assertRaises(OpenAIError):
                    await client.generate("hi")
                # 2차 실패(failures=2 ≥ threshold → open).
                with self.assertRaises(OpenAIError):
                    await client.generate("hi")
                calls_before = urlopen_mock.call_count
                # 3차 시도: 서킷이 열려 before_call 에서 빠르게 실패해야 한다.
                with self.assertRaises(CircuitBreakerOpenError):
                    await client.generate("hi")
                # 빠른 실패이므로 urlopen 은 추가로 호출되지 않는다.
                self.assertEqual(urlopen_mock.call_count, calls_before)

        asyncio.run(run())

    def test_breaker_fast_fail_with_clock_injection(self) -> None:
        # _with_circuit_breaker 단위로 빠른 실패를 검증한다(주입된 clock 사용).
        clock = {"t": 0.0}
        breaker = CircuitBreaker(
            failure_threshold=2,
            reset_timeout=30.0,
            time_fn=lambda: clock["t"],
        )
        calls = {"n": 0}

        async def always_fail() -> str:
            calls["n"] += 1
            raise OpenAIError("server error", status_code=500)

        async def run() -> None:
            with _patch_sleep():
                # 두 번의 실제 호출로 서킷을 연다(각각 max_attempts=1 로 1회 호출).
                for _ in range(2):
                    with self.assertRaises(OpenAIError):
                        await _with_circuit_breaker(
                            breaker, always_fail, max_attempts=1, delay=0
                        )
                calls_after_open = calls["n"]
                # 서킷이 열린 동안(시계 정지)에는 coro 를 실행하지 않고 즉시 실패.
                with self.assertRaises(CircuitBreakerOpenError):
                    await _with_circuit_breaker(
                        breaker, always_fail, max_attempts=1, delay=0
                    )
                self.assertEqual(calls["n"], calls_after_open)

        asyncio.run(run())
        self.assertEqual(calls["n"], 2)

    def test_breaker_half_open_recovers_after_timeout(self) -> None:
        # reset_timeout 경과 후 half-open 으로 한 번 시도를 허용하고, 성공하면 닫힌다.
        clock = {"t": 0.0}
        breaker = CircuitBreaker(
            failure_threshold=1,
            reset_timeout=10.0,
            time_fn=lambda: clock["t"],
        )
        client = OpenAIClient(api_key="sk-x", circuit_breaker=breaker)

        def fake_urlopen(req, timeout=None):  # type: ignore[no-untyped-def]
            # 타임아웃 경과(half-open) 전에는 계속 500 폴트, 그 이후엔 정상 응답.
            # generate() 내부 재시도(max_attempts=2)가 응답을 소비해도 일관되게
            # 동작하도록 시계 기반으로 결과를 결정한다.
            if clock["t"] < breaker.reset_timeout:
                raise _http_error(500)
            return _json_response(_OPENAI_OK)

        async def run() -> None:
            with mock.patch(
                "discord_assistant.llm.request.urlopen", side_effect=fake_urlopen
            ), _patch_sleep():
                # 1차: 500 폴트 → 서킷 오픈(threshold=1).
                with self.assertRaises(OpenAIError):
                    await client.generate("hi")
                # 시계가 reset_timeout 미만이면 여전히 열려 빠르게 실패.
                clock["t"] = 5.0
                with self.assertRaises(CircuitBreakerOpenError):
                    await client.generate("hi")
                # reset_timeout 경과 → half-open → 실제 호출(정상 응답) → 닫힘.
                clock["t"] = 20.0
                result = await client.generate("hi")
                self.assertEqual(result, "안녕하세요")

        asyncio.run(run())


if __name__ == "__main__":  # pragma: no cover
    unittest.main()
