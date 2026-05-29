from __future__ import annotations

import asyncio
import io
import json
import unittest
from unittest import mock
from urllib import error as urllib_error

from discord_assistant.llm import (
    PRICING,
    AnthropicClient,
    AnthropicError,
    CircuitBreaker,
    CircuitBreakerOpenError,
    GeminiClient,
    LLMError,
    OllamaClient,
    OllamaError,
    OpenAIClient,
    OpenAIError,
    TokenUsage,
    ToolSpec,
    _is_retryable,
    _parse_anthropic_usage,
    _parse_gemini_usage,
    _parse_ollama_usage,
    _parse_openai_usage,
    _with_circuit_breaker,
    _with_retry,
    estimate_cost,
    parse_generate_response,
    supports_vision,
)
from discord_assistant.models import LLMProvider


class LlmTest(unittest.TestCase):
    def test_parse_generate_response(self) -> None:
        self.assertEqual(parse_generate_response({"response": " hello "}), "hello")

    def test_parse_generate_response_error(self) -> None:
        with self.assertRaises(OllamaError):
            parse_generate_response({"error": "model not found"})

    def test_client_strips_trailing_base_url_slash(self) -> None:
        client = OllamaClient(base_url="http://localhost:11434/", default_model="llama3.1:8b")

        self.assertEqual(client.base_url, "http://localhost:11434")


# ---------------------------------------------------------------------------
# #21: 재시도 가능 판정 / 4xx 즉시 실패
# ---------------------------------------------------------------------------


class RetryClassificationTest(unittest.TestCase):
    def test_4xx_not_retryable(self) -> None:
        for code in (400, 401, 403, 404):
            self.assertFalse(_is_retryable(LLMError("x", status_code=code)))

    def test_429_retryable(self) -> None:
        self.assertTrue(_is_retryable(LLMError("x", status_code=429)))

    def test_5xx_retryable(self) -> None:
        for code in (500, 502, 503):
            self.assertTrue(_is_retryable(LLMError("x", status_code=code)))

    def test_none_status_retryable(self) -> None:
        # 네트워크/타임아웃 등 status_code 없는 오류는 재시도 가능
        self.assertTrue(_is_retryable(LLMError("network down")))


class WithRetryTest(unittest.TestCase):
    def test_4xx_fails_immediately_without_retry(self) -> None:
        calls = {"n": 0}

        async def fail_400() -> str:
            calls["n"] += 1
            raise LLMError("bad request", status_code=400)

        async def run() -> None:
            with self.assertRaises(LLMError):
                await _with_retry(fail_400, max_attempts=3, delay=0)

        asyncio.run(run())
        self.assertEqual(calls["n"], 1)  # 재시도 없이 1회만 호출

    def test_5xx_is_retried(self) -> None:
        calls = {"n": 0}

        async def fail_500() -> str:
            calls["n"] += 1
            raise LLMError("server error", status_code=500)

        async def run() -> None:
            with self.assertRaises(LLMError):
                await _with_retry(fail_500, max_attempts=3, delay=0)

        asyncio.run(run())
        self.assertEqual(calls["n"], 3)  # 최대 시도 횟수만큼 호출

    def test_success_on_retry(self) -> None:
        calls = {"n": 0}

        async def flaky() -> str:
            calls["n"] += 1
            if calls["n"] < 2:
                raise LLMError("server error", status_code=503)
            return "ok"

        async def run() -> str:
            return await _with_retry(flaky, max_attempts=3, delay=0)

        self.assertEqual(asyncio.run(run()), "ok")
        self.assertEqual(calls["n"], 2)


# ---------------------------------------------------------------------------
# #61: _with_retry 단위 테스트 (asyncio.sleep 을 mock 해 결정적 검증)
#
# 위 WithRetryTest 가 delay=0 으로 시간 없이 돌리는 반면, 여기서는 백오프 sleep
# 호출 자체를 검증/차단하기 위해 asyncio.sleep 을 mock 한다. 또한 경계 조건
# (max_attempts<1 → ValueError)과 "전부 실패 시 마지막 예외 재전파"를 다룬다.
# ---------------------------------------------------------------------------


class WithRetrySleepMockedTest(unittest.TestCase):
    def test_max_attempts_below_one_raises_value_error(self) -> None:
        async def never_called() -> str:  # pragma: no cover - 호출되면 안 됨
            raise AssertionError("coro_fn 이 호출되면 안 된다")

        async def run() -> None:
            with self.assertRaises(ValueError):
                await _with_retry(never_called, max_attempts=0, delay=1.0)

        asyncio.run(run())

    def test_success_after_one_failure_sleeps_once(self) -> None:
        calls = {"n": 0}

        async def flaky() -> str:
            calls["n"] += 1
            if calls["n"] < 2:
                raise LLMError("server error", status_code=503)
            return "ok"

        async def run() -> str:
            return await _with_retry(flaky, max_attempts=3, delay=1.0)

        with mock.patch(
            "discord_assistant.llm.asyncio.sleep", new=mock.AsyncMock()
        ) as sleep_mock:
            result = asyncio.run(run())

        self.assertEqual(result, "ok")
        self.assertEqual(calls["n"], 2)
        # 1회 실패 → 1회 백오프(sleep)만 발생.
        self.assertEqual(sleep_mock.await_count, 1)

    def test_all_failures_reraise_last_exception(self) -> None:
        calls = {"n": 0}
        # 매 시도마다 서로 구분되는 예외 객체를 던져 "마지막 것"이 재전파되는지 확인.
        exceptions = [
            LLMError("first", status_code=500),
            LLMError("second", status_code=502),
            LLMError("third-last", status_code=503),
        ]

        async def always_fail() -> str:
            exc = exceptions[calls["n"]]
            calls["n"] += 1
            raise exc

        async def run() -> None:
            with self.assertRaises(LLMError) as cm:
                await _with_retry(always_fail, max_attempts=3, delay=1.0)
            # 마지막 시도의 예외가 그대로 재전파된다.
            self.assertIs(cm.exception, exceptions[-1])
            self.assertIn("third-last", str(cm.exception))

        with mock.patch(
            "discord_assistant.llm.asyncio.sleep", new=mock.AsyncMock()
        ) as sleep_mock:
            asyncio.run(run())

        self.assertEqual(calls["n"], 3)
        # 마지막 시도 후에는 sleep 하지 않으므로 (max_attempts-1)=2 회만 백오프.
        self.assertEqual(sleep_mock.await_count, 2)

    def test_4xx_fails_immediately_without_sleep(self) -> None:
        calls = {"n": 0}

        async def fail_403() -> str:
            calls["n"] += 1
            raise LLMError("forbidden", status_code=403)

        async def run() -> None:
            with self.assertRaises(LLMError) as cm:
                await _with_retry(fail_403, max_attempts=5, delay=1.0)
            self.assertEqual(cm.exception.status_code, 403)

        with mock.patch(
            "discord_assistant.llm.asyncio.sleep", new=mock.AsyncMock()
        ) as sleep_mock:
            asyncio.run(run())

        # 4xx 는 재시도 불가 → 1회 호출, sleep 미발생.
        self.assertEqual(calls["n"], 1)
        sleep_mock.assert_not_called()

    def test_429_and_5xx_are_retried(self) -> None:
        for code in (429, 500, 503):
            with self.subTest(code=code):
                calls = {"n": 0}

                async def fail() -> str:
                    calls["n"] += 1
                    raise LLMError("transient", status_code=code)

                async def run() -> None:
                    with self.assertRaises(LLMError):
                        await _with_retry(fail, max_attempts=3, delay=1.0)

                with mock.patch(
                    "discord_assistant.llm.asyncio.sleep", new=mock.AsyncMock()
                ) as sleep_mock:
                    asyncio.run(run())

                # 재시도 가능 → max_attempts 만큼 호출, 그 사이 sleep (max_attempts-1)회.
                self.assertEqual(calls["n"], 3)
                self.assertEqual(sleep_mock.await_count, 2)

    def test_single_attempt_no_retry_no_sleep(self) -> None:
        calls = {"n": 0}

        async def fail() -> str:
            calls["n"] += 1
            raise LLMError("server error", status_code=500)

        async def run() -> None:
            with self.assertRaises(LLMError):
                await _with_retry(fail, max_attempts=1, delay=1.0)

        with mock.patch(
            "discord_assistant.llm.asyncio.sleep", new=mock.AsyncMock()
        ) as sleep_mock:
            asyncio.run(run())

        # max_attempts=1 이면 재시도 여지가 없으므로 sleep 미발생.
        self.assertEqual(calls["n"], 1)
        sleep_mock.assert_not_called()


# ---------------------------------------------------------------------------
# #52: 서킷 브레이커
# ---------------------------------------------------------------------------


class CircuitBreakerTest(unittest.TestCase):
    def test_opens_after_threshold_and_fast_fails(self) -> None:
        clock = {"t": 0.0}
        breaker = CircuitBreaker(
            failure_threshold=2, reset_timeout=30.0, time_fn=lambda: clock["t"]
        )
        calls = {"n": 0}

        async def always_fail() -> str:
            calls["n"] += 1
            raise LLMError("server error", status_code=500)

        async def run() -> None:
            # 두 번 실패하면 임계치 도달 → open
            for _ in range(2):
                with self.assertRaises(LLMError):
                    await _with_circuit_breaker(
                        breaker, always_fail, max_attempts=1, delay=0
                    )
            # open 상태에서는 coro_fn 호출 없이 빠르게 실패
            with self.assertRaises(CircuitBreakerOpenError):
                await _with_circuit_breaker(breaker, always_fail, max_attempts=1, delay=0)

        asyncio.run(run())
        self.assertEqual(calls["n"], 2)  # open 이후엔 실제 호출 없음

    def test_half_open_after_reset_timeout(self) -> None:
        clock = {"t": 0.0}
        breaker = CircuitBreaker(
            failure_threshold=1, reset_timeout=10.0, time_fn=lambda: clock["t"]
        )

        async def fail_then_ok(should_fail: bool) -> str:
            if should_fail:
                raise LLMError("server error", status_code=500)
            return "ok"

        async def run() -> None:
            with self.assertRaises(LLMError):
                await _with_circuit_breaker(
                    breaker, lambda: fail_then_ok(True), max_attempts=1, delay=0
                )
            # open 상태 (reset_timeout 전)
            with self.assertRaises(CircuitBreakerOpenError):
                await _with_circuit_breaker(
                    breaker, lambda: fail_then_ok(True), max_attempts=1, delay=0
                )
            # 시간 경과 → half-open → 성공 시 닫힘
            clock["t"] = 20.0
            result = await _with_circuit_breaker(
                breaker, lambda: fail_then_ok(False), max_attempts=1, delay=0
            )
            self.assertEqual(result, "ok")

        asyncio.run(run())

    def test_none_breaker_is_passthrough(self) -> None:
        async def ok() -> str:
            return "ok"

        async def run() -> str:
            return await _with_circuit_breaker(None, ok, max_attempts=1, delay=0)

        self.assertEqual(asyncio.run(run()), "ok")


# ---------------------------------------------------------------------------
# #22: 클라이언트 파라미터 주입 (백워드 호환 기본값 유지)
# ---------------------------------------------------------------------------


class ClientParamInjectionTest(unittest.TestCase):
    def test_openai_defaults(self) -> None:
        client = OpenAIClient(api_key="sk-x")
        self.assertEqual(client.temperature, 0.2)
        self.assertEqual(client.system_prompt, "You are a helpful Discord bot assistant.")
        self.assertIsNone(client.circuit_breaker)

    def test_openai_injected(self) -> None:
        client = OpenAIClient(
            api_key="sk-x", temperature=0.9, system_prompt="너는 봇이다."
        )
        self.assertEqual(client.temperature, 0.9)
        self.assertEqual(client.system_prompt, "너는 봇이다.")

    def test_anthropic_defaults(self) -> None:
        client = AnthropicClient(api_key="sk-x")
        self.assertEqual(client.max_tokens, 4096)
        self.assertEqual(client.system_prompt, "You are a helpful Discord bot assistant.")

    def test_anthropic_injected(self) -> None:
        client = AnthropicClient(api_key="sk-x", max_tokens=1024, system_prompt="hi")
        self.assertEqual(client.max_tokens, 1024)
        self.assertEqual(client.system_prompt, "hi")


# ---------------------------------------------------------------------------
# #14: 비전(멀티모달) 지원 판정
# ---------------------------------------------------------------------------


class SupportsVisionTest(unittest.TestCase):
    def test_openai_vision_models(self) -> None:
        for model in (
            "gpt-4o",
            "gpt-4o-mini",
            "gpt-4-turbo",
            "gpt-4.1",
            "gpt-4.1-mini",
            "o1",
            "o4-mini",
        ):
            self.assertTrue(
                supports_vision(LLMProvider.OPENAI, model), f"{model} should support vision"
            )

    def test_openai_text_only_models(self) -> None:
        for model in ("gpt-3.5-turbo", "o1-mini", "o3-mini"):
            self.assertFalse(
                supports_vision(LLMProvider.OPENAI, model), f"{model} should be text-only"
            )

    def test_openai_unknown_model_is_false(self) -> None:
        self.assertFalse(supports_vision(LLMProvider.OPENAI, "babbage-002"))

    def test_anthropic_vision_models(self) -> None:
        for model in (
            "claude-3-opus-20240229",
            "claude-3-5-sonnet-20241022",
            "claude-3-5-haiku-latest",
            "claude-opus-4-20250101",
            "claude-sonnet-4-5",
            "claude-haiku-4-5-20251001",
        ):
            self.assertTrue(
                supports_vision(LLMProvider.ANTHROPIC, model), f"{model} should support vision"
            )

    def test_anthropic_legacy_text_only(self) -> None:
        # claude-2 계열은 비전 미지원
        self.assertFalse(supports_vision(LLMProvider.ANTHROPIC, "claude-2.1"))

    def test_ollama_vision_models(self) -> None:
        for model in (
            "llava:13b",
            "bakllava:latest",
            "llama3.2-vision",
            "minicpm-v",
            "moondream",
        ):
            self.assertTrue(
                supports_vision(LLMProvider.OLLAMA, model), f"{model} should support vision"
            )

    def test_ollama_text_only_models(self) -> None:
        for model in ("llama3.1:8b", "qwen2.5:7b", "mistral:7b", "phi3:mini"):
            self.assertFalse(
                supports_vision(LLMProvider.OLLAMA, model), f"{model} should be text-only"
            )

    def test_empty_model_is_false(self) -> None:
        self.assertFalse(supports_vision(LLMProvider.OPENAI, ""))
        self.assertFalse(supports_vision(LLMProvider.ANTHROPIC, "   "))

    def test_case_insensitive(self) -> None:
        self.assertTrue(supports_vision(LLMProvider.OPENAI, "GPT-4O-MINI"))
        self.assertTrue(supports_vision(LLMProvider.OLLAMA, "LLaVA:13B"))


# ---------------------------------------------------------------------------
# #18: 제공자·모델별 단가 + 비용 계산
# ---------------------------------------------------------------------------


class EstimateCostTest(unittest.TestCase):
    def test_openai_known_model_exact(self) -> None:
        # gpt-4o-mini: (0.00015, 0.0006) per 1K
        cost = estimate_cost(LLMProvider.OPENAI, "gpt-4o-mini", 1000, 1000)
        self.assertAlmostEqual(cost, 0.00015 + 0.0006)

    def test_openai_partial_match_with_suffix(self) -> None:
        # 정확히 등록되지 않은 변형도 부분 문자열 매칭으로 처리
        cost = estimate_cost(LLMProvider.OPENAI, "gpt-4o-2024-08-06", 2000, 0)
        self.assertAlmostEqual(cost, 2 * 0.0025)

    def test_anthropic_known_model(self) -> None:
        # claude-3-5-sonnet: (0.003, 0.015) per 1K
        cost = estimate_cost(
            LLMProvider.ANTHROPIC, "claude-3-5-sonnet-20241022", 1000, 2000
        )
        self.assertAlmostEqual(cost, 0.003 + 2 * 0.015)

    def test_longest_key_wins(self) -> None:
        # "claude-3-5-haiku"가 "claude-3"보다 더 구체적이므로 우선
        cost = estimate_cost(LLMProvider.ANTHROPIC, "claude-3-5-haiku-latest", 1000, 0)
        self.assertAlmostEqual(cost, 0.0008)

    def test_ollama_is_free(self) -> None:
        self.assertEqual(
            estimate_cost(LLMProvider.OLLAMA, "llama3.1:8b", 999999, 999999), 0.0
        )

    def test_unknown_model_is_zero(self) -> None:
        self.assertEqual(estimate_cost(LLMProvider.OPENAI, "totally-unknown", 1000, 1000), 0.0)

    def test_negative_tokens_clamped(self) -> None:
        cost = estimate_cost(LLMProvider.OPENAI, "gpt-4o-mini", -500, -500)
        self.assertEqual(cost, 0.0)

    def test_empty_model_is_zero(self) -> None:
        self.assertEqual(estimate_cost(LLMProvider.OPENAI, "", 1000, 1000), 0.0)

    def test_pricing_table_is_populated(self) -> None:
        # 단가 테이블이 비어있지 않고 모든 값이 (float, float) 형태인지 확인
        self.assertGreater(len(PRICING), 0)
        for (provider, model), price in PRICING.items():
            self.assertIsInstance(provider, LLMProvider)
            self.assertIsInstance(model, str)
            self.assertEqual(len(price), 2)
            self.assertGreaterEqual(price[0], 0.0)
            self.assertGreaterEqual(price[1], 0.0)


# ---------------------------------------------------------------------------
# #60: OpenAIClient / AnthropicClient _generate_sync HTTP 응답 파싱·에러 테스트
#
# urllib.request.urlopen 을 mock 으로 대체해 실제 네트워크를 타지 않게 한다.
# - 정상 응답 파싱
# - 4xx 즉시 실패(재시도 안 함)
# - 429 / 5xx 재시도(_with_retry, asyncio.sleep mock 으로 결정적)
# - URLError / TimeoutError / 잘못된 JSON 처리
# - 응답 원문(payload) 비노출(사용자 메시지에 원문 미포함)
# ---------------------------------------------------------------------------


class _FakeHTTPResponse:
    """urllib.request.urlopen 의 with-블록용 컨텍스트 매니저 가짜 응답.

    ``response.read().decode("utf-8")`` 흐름을 흉내내려고 bytes 본문을 들고 있다.
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
    """주어진 dict 를 JSON bytes 로 직렬화한 가짜 HTTP 응답을 만든다."""
    return _FakeHTTPResponse(json.dumps(payload).encode("utf-8"))


def _http_error(code: int, body: str = "secret error detail") -> urllib_error.HTTPError:
    """주어진 상태 코드의 HTTPError 를 만든다.

    ``exc.read()`` 가 본문을 돌려주도록 io.BytesIO 를 fp 로 넣는다(코드에서
    detail = exc.read().decode(...) 를 호출하므로).
    """
    return urllib_error.HTTPError(
        url="https://example.test",
        code=code,
        msg="error",
        hdrs=None,  # type: ignore[arg-type]
        fp=io.BytesIO(body.encode("utf-8")),
    )


# OpenAI / Anthropic 정상 응답 payload 형태(코드 파싱 경로에 맞춤).
_OPENAI_OK_PAYLOAD = {"choices": [{"message": {"content": "  안녕하세요  "}}]}
_ANTHROPIC_OK_PAYLOAD = {"content": [{"text": "  반갑습니다  "}]}


class OpenAIGenerateSyncTest(unittest.TestCase):
    """OpenAIClient._generate_sync 의 HTTP 응답 파싱·에러 처리."""

    def setUp(self) -> None:
        self.client = OpenAIClient(api_key="sk-secret-key")

    def test_parses_successful_response(self) -> None:
        with mock.patch(
            "discord_assistant.llm.request.urlopen",
            return_value=_json_response(_OPENAI_OK_PAYLOAD),
        ):
            result = self.client._generate_sync("hi", "gpt-4o-mini")
        # content 앞뒤 공백이 strip 되어 반환된다.
        self.assertEqual(result, "안녕하세요")

    def test_does_not_send_api_key_in_url_or_unexpectedly(self) -> None:
        # urlopen 에 넘어가는 Request 객체에 Authorization 헤더가 들어가는지 확인.
        captured: dict = {}

        def fake_urlopen(req, timeout=None):  # type: ignore[no-untyped-def]
            captured["headers"] = dict(req.headers)
            captured["url"] = req.full_url
            return _json_response(_OPENAI_OK_PAYLOAD)

        with mock.patch("discord_assistant.llm.request.urlopen", side_effect=fake_urlopen):
            self.client._generate_sync("hi", "gpt-4o-mini")
        # Bearer 토큰은 헤더로만 전달되고 URL 에는 노출되지 않는다.
        self.assertNotIn("sk-secret-key", captured["url"])
        self.assertEqual(captured["headers"].get("Authorization"), "Bearer sk-secret-key")

    def test_4xx_raises_openai_error_with_status_code(self) -> None:
        with mock.patch(
            "discord_assistant.llm.request.urlopen",
            side_effect=_http_error(400, body="invalid payload xyz"),
        ):
            with self.assertRaises(OpenAIError) as cm:
                self.client._generate_sync("hi", "gpt-4o-mini")
        self.assertEqual(cm.exception.status_code, 400)

    def test_4xx_error_message_hides_response_body(self) -> None:
        # 사용자에게 노출되는 예외 메시지에 HTTP 응답 원문이 섞이지 않아야 한다.
        secret_body = "super-secret-server-detail-12345"
        with mock.patch(
            "discord_assistant.llm.request.urlopen",
            side_effect=_http_error(401, body=secret_body),
        ):
            with self.assertRaises(OpenAIError) as cm:
                self.client._generate_sync("hi", "gpt-4o-mini")
        self.assertNotIn(secret_body, str(cm.exception))
        self.assertEqual(cm.exception.status_code, 401)

    def test_url_error_raises_openai_error_without_status(self) -> None:
        with mock.patch(
            "discord_assistant.llm.request.urlopen",
            side_effect=urllib_error.URLError("connection refused"),
        ):
            with self.assertRaises(OpenAIError) as cm:
                self.client._generate_sync("hi", "gpt-4o-mini")
        # 네트워크 오류는 status_code 가 없다(재시도 가능 분류).
        self.assertIsNone(cm.exception.status_code)

    def test_timeout_raises_openai_error(self) -> None:
        with mock.patch(
            "discord_assistant.llm.request.urlopen",
            side_effect=TimeoutError("timed out"),
        ):
            with self.assertRaises(OpenAIError) as cm:
                self.client._generate_sync("hi", "gpt-4o-mini")
        self.assertIn("시간", str(cm.exception))

    def test_invalid_json_raises_openai_error(self) -> None:
        with mock.patch(
            "discord_assistant.llm.request.urlopen",
            return_value=_FakeHTTPResponse(b"not-json{{{"),
        ):
            with self.assertRaises(OpenAIError):
                self.client._generate_sync("hi", "gpt-4o-mini")

    def test_unexpected_shape_raises_openai_error(self) -> None:
        # choices 가 없는 응답 → KeyError 경로 → OpenAIError 로 변환.
        with mock.patch(
            "discord_assistant.llm.request.urlopen",
            return_value=_json_response({"unexpected": True}),
        ):
            with self.assertRaises(OpenAIError):
                self.client._generate_sync("hi", "gpt-4o-mini")


class AnthropicGenerateSyncTest(unittest.TestCase):
    """AnthropicClient._generate_sync 의 HTTP 응답 파싱·에러 처리."""

    def setUp(self) -> None:
        self.client = AnthropicClient(api_key="sk-ant-secret")

    def test_parses_successful_response(self) -> None:
        with mock.patch(
            "discord_assistant.llm.request.urlopen",
            return_value=_json_response(_ANTHROPIC_OK_PAYLOAD),
        ):
            result = self.client._generate_sync("hi", "claude-haiku-4-5")
        self.assertEqual(result, "반갑습니다")

    def test_api_key_passed_as_header_not_url(self) -> None:
        captured: dict = {}

        def fake_urlopen(req, timeout=None):  # type: ignore[no-untyped-def]
            captured["headers"] = dict(req.headers)
            captured["url"] = req.full_url
            return _json_response(_ANTHROPIC_OK_PAYLOAD)

        with mock.patch("discord_assistant.llm.request.urlopen", side_effect=fake_urlopen):
            self.client._generate_sync("hi", "claude-haiku-4-5")
        self.assertNotIn("sk-ant-secret", captured["url"])
        # urllib 은 헤더 키를 title-case 로 정규화한다(x-api-key → X-api-key).
        self.assertEqual(captured["headers"].get("X-api-key"), "sk-ant-secret")

    def test_4xx_raises_anthropic_error_with_status_code(self) -> None:
        with mock.patch(
            "discord_assistant.llm.request.urlopen",
            side_effect=_http_error(403, body="forbidden detail"),
        ):
            with self.assertRaises(AnthropicError) as cm:
                self.client._generate_sync("hi", "claude-haiku-4-5")
        self.assertEqual(cm.exception.status_code, 403)

    def test_error_message_hides_response_body(self) -> None:
        secret_body = "anthropic-internal-trace-99"
        with mock.patch(
            "discord_assistant.llm.request.urlopen",
            side_effect=_http_error(429, body=secret_body),
        ):
            with self.assertRaises(AnthropicError) as cm:
                self.client._generate_sync("hi", "claude-haiku-4-5")
        self.assertNotIn(secret_body, str(cm.exception))
        self.assertEqual(cm.exception.status_code, 429)

    def test_url_error_raises_anthropic_error(self) -> None:
        with mock.patch(
            "discord_assistant.llm.request.urlopen",
            side_effect=urllib_error.URLError("dns failure"),
        ):
            with self.assertRaises(AnthropicError) as cm:
                self.client._generate_sync("hi", "claude-haiku-4-5")
        self.assertIsNone(cm.exception.status_code)

    def test_timeout_raises_anthropic_error(self) -> None:
        with mock.patch(
            "discord_assistant.llm.request.urlopen",
            side_effect=TimeoutError(),
        ):
            with self.assertRaises(AnthropicError):
                self.client._generate_sync("hi", "claude-haiku-4-5")

    def test_invalid_json_raises_anthropic_error(self) -> None:
        with mock.patch(
            "discord_assistant.llm.request.urlopen",
            return_value=_FakeHTTPResponse(b"<<<garbage>>>"),
        ):
            with self.assertRaises(AnthropicError):
                self.client._generate_sync("hi", "claude-haiku-4-5")

    def test_unexpected_shape_raises_anthropic_error(self) -> None:
        with mock.patch(
            "discord_assistant.llm.request.urlopen",
            return_value=_json_response({"content": []}),  # IndexError 경로
        ):
            with self.assertRaises(AnthropicError):
                self.client._generate_sync("hi", "claude-haiku-4-5")


class GenerateRetryIntegrationTest(unittest.TestCase):
    """generate() 의 재시도·즉시실패 동작을 urlopen mock + sleep mock 으로 결정적으로 검증."""

    def test_4xx_fails_immediately_no_retry(self) -> None:
        client = OpenAIClient(api_key="sk-x")

        with mock.patch(
            "discord_assistant.llm.request.urlopen",
            side_effect=_http_error(400),
        ) as urlopen_mock, mock.patch(
            "discord_assistant.llm.asyncio.sleep", new=mock.AsyncMock()
        ) as sleep_mock:
            with self.assertRaises(OpenAIError):
                asyncio.run(client.generate("hi"))

        # 4xx 는 재시도 불가 → urlopen 단 1회, sleep 미호출.
        self.assertEqual(urlopen_mock.call_count, 1)
        sleep_mock.assert_not_called()

    def test_5xx_is_retried_up_to_max_attempts(self) -> None:
        client = AnthropicClient(api_key="sk-ant-x")

        with mock.patch(
            "discord_assistant.llm.request.urlopen",
            side_effect=_http_error(500),
        ) as urlopen_mock, mock.patch(
            "discord_assistant.llm.asyncio.sleep", new=mock.AsyncMock()
        ) as sleep_mock:
            with self.assertRaises(AnthropicError):
                asyncio.run(client.generate("hi"))

        # 기본 max_attempts=2 → 2회 호출, 그 사이 sleep 1회(백오프).
        self.assertEqual(urlopen_mock.call_count, 2)
        self.assertEqual(sleep_mock.await_count, 1)

    def test_429_is_retried(self) -> None:
        client = OpenAIClient(api_key="sk-x")

        with mock.patch(
            "discord_assistant.llm.request.urlopen",
            side_effect=_http_error(429),
        ) as urlopen_mock, mock.patch(
            "discord_assistant.llm.asyncio.sleep", new=mock.AsyncMock()
        ):
            with self.assertRaises(OpenAIError):
                asyncio.run(client.generate("hi"))
        self.assertEqual(urlopen_mock.call_count, 2)

    def test_succeeds_after_transient_5xx(self) -> None:
        client = OpenAIClient(api_key="sk-x")
        # 첫 호출은 500, 두 번째 호출은 정상 응답.
        responses = [_http_error(503), _json_response(_OPENAI_OK_PAYLOAD)]

        def fake_urlopen(req, timeout=None):  # type: ignore[no-untyped-def]
            item = responses.pop(0)
            if isinstance(item, urllib_error.HTTPError):
                raise item
            return item

        with mock.patch(
            "discord_assistant.llm.request.urlopen", side_effect=fake_urlopen
        ), mock.patch("discord_assistant.llm.asyncio.sleep", new=mock.AsyncMock()):
            result = asyncio.run(client.generate("hi"))
        self.assertEqual(result, "안녕하세요")


# ---------------------------------------------------------------------------
# #17: 토큰 사용량 파싱 (제공자별 usage 메타데이터)
# ---------------------------------------------------------------------------


class TokenUsageParsingTest(unittest.TestCase):
    def test_openai_usage(self) -> None:
        usage = _parse_openai_usage(
            {"usage": {"prompt_tokens": 12, "completion_tokens": 34}}
        )
        self.assertEqual((usage.prompt_tokens, usage.completion_tokens), (12, 34))

    def test_anthropic_usage(self) -> None:
        usage = _parse_anthropic_usage(
            {"usage": {"input_tokens": 7, "output_tokens": 9}}
        )
        self.assertEqual((usage.prompt_tokens, usage.completion_tokens), (7, 9))

    def test_gemini_usage(self) -> None:
        usage = _parse_gemini_usage(
            {"usageMetadata": {"promptTokenCount": 100, "candidatesTokenCount": 50}}
        )
        self.assertEqual((usage.prompt_tokens, usage.completion_tokens), (100, 50))

    def test_ollama_usage(self) -> None:
        usage = _parse_ollama_usage({"prompt_eval_count": 5, "eval_count": 6})
        self.assertEqual((usage.prompt_tokens, usage.completion_tokens), (5, 6))

    def test_missing_usage_is_zero(self) -> None:
        for parser in (
            _parse_openai_usage,
            _parse_anthropic_usage,
            _parse_gemini_usage,
            _parse_ollama_usage,
        ):
            usage = parser({})
            self.assertEqual((usage.prompt_tokens, usage.completion_tokens), (0, 0))

    def test_negative_and_nonnumeric_clamped_to_zero(self) -> None:
        usage = _parse_openai_usage(
            {"usage": {"prompt_tokens": -3, "completion_tokens": "x"}}
        )
        self.assertEqual((usage.prompt_tokens, usage.completion_tokens), (0, 0))


class LastUsageRecordedTest(unittest.TestCase):
    """_generate_sync 성공 시 last_usage 가 갱신되는지 확인한다 (#17)."""

    def test_openai_sets_last_usage(self) -> None:
        client = OpenAIClient(api_key="sk-x")
        payload = {
            "choices": [{"message": {"content": "hi"}}],
            "usage": {"prompt_tokens": 11, "completion_tokens": 22},
        }
        with mock.patch(
            "discord_assistant.llm.request.urlopen",
            return_value=_json_response(payload),
        ):
            client._generate_sync("hi", "gpt-4o-mini")
        self.assertEqual(client.last_usage, TokenUsage(11, 22))

    def test_anthropic_sets_last_usage(self) -> None:
        client = AnthropicClient(api_key="sk-x")
        payload = {
            "content": [{"text": "hi"}],
            "usage": {"input_tokens": 3, "output_tokens": 4},
        }
        with mock.patch(
            "discord_assistant.llm.request.urlopen",
            return_value=_json_response(payload),
        ):
            client._generate_sync("hi", "claude-haiku-4-5")
        self.assertEqual(client.last_usage, TokenUsage(3, 4))

    def test_default_last_usage_is_zero(self) -> None:
        # generate 호출 전 기본값은 (0, 0).
        self.assertEqual(OpenAIClient(api_key="x").last_usage, TokenUsage(0, 0))


# ---------------------------------------------------------------------------
# #12: 멀티모달 이미지 입력 — 제공자별 요청 본문에 이미지 블록이 들어가는지 검증
# ---------------------------------------------------------------------------


def _capture_body(payload: dict) -> tuple[dict, dict]:
    """urlopen 을 가로채 전송 body(JSON)를 캡처하는 헬퍼.

    반환: (captured dict, 가로챈 body dict). captured["body"] 에 디코딩된 dict 가 담긴다.
    """
    captured: dict = {}

    def fake_urlopen(req, timeout=None):  # type: ignore[no-untyped-def]
        captured["body"] = json.loads(req.data.decode("utf-8"))
        return _json_response(payload)

    return captured, fake_urlopen  # type: ignore[return-value]


class ImageInputTest(unittest.TestCase):
    _IMG = (b"\x89PNG\r\n\x1a\n fake png bytes")

    def test_openai_no_images_keeps_plain_string_content(self) -> None:
        client = OpenAIClient(api_key="sk-x")
        captured, fake = _capture_body(_OPENAI_OK_PAYLOAD)
        with mock.patch("discord_assistant.llm.request.urlopen", side_effect=fake):
            client._generate_sync("hello", "gpt-4o-mini")
        # 이미지가 없으면 user content 는 기존처럼 평문 문자열이다(백워드 호환).
        user_msg = captured["body"]["messages"][1]
        self.assertEqual(user_msg["content"], "hello")

    def test_openai_with_image_builds_content_array(self) -> None:
        client = OpenAIClient(api_key="sk-x")
        captured, fake = _capture_body(_OPENAI_OK_PAYLOAD)
        with mock.patch("discord_assistant.llm.request.urlopen", side_effect=fake):
            client._generate_sync(
                "describe", "gpt-4o", [("image/png", self._IMG)]
            )
        content = captured["body"]["messages"][1]["content"]
        self.assertIsInstance(content, list)
        self.assertEqual(content[0], {"type": "text", "text": "describe"})
        self.assertEqual(content[1]["type"], "image_url")
        self.assertTrue(
            content[1]["image_url"]["url"].startswith("data:image/png;base64,")
        )

    def test_anthropic_with_image_builds_source_block(self) -> None:
        client = AnthropicClient(api_key="sk-x")
        captured, fake = _capture_body(_ANTHROPIC_OK_PAYLOAD)
        with mock.patch("discord_assistant.llm.request.urlopen", side_effect=fake):
            client._generate_sync("describe", "claude-3-5-sonnet", [self._IMG])
        content = captured["body"]["messages"][0]["content"]
        self.assertIsInstance(content, list)
        # 이미지 블록이 먼저, 텍스트 블록이 뒤에 온다.
        self.assertEqual(content[0]["type"], "image")
        self.assertEqual(content[0]["source"]["type"], "base64")
        # raw bytes 만 넘기면 기본 MIME(image/png) 으로 가정한다.
        self.assertEqual(content[0]["source"]["media_type"], "image/png")
        self.assertEqual(content[-1], {"type": "text", "text": "describe"})

    def test_anthropic_no_images_keeps_plain_string(self) -> None:
        client = AnthropicClient(api_key="sk-x")
        captured, fake = _capture_body(_ANTHROPIC_OK_PAYLOAD)
        with mock.patch("discord_assistant.llm.request.urlopen", side_effect=fake):
            client._generate_sync("hi", "claude-3-5-sonnet")
        self.assertEqual(captured["body"]["messages"][0]["content"], "hi")

    def test_gemini_with_image_adds_inline_data(self) -> None:
        client = GeminiClient(api_key="sk-x")
        payload = {
            "candidates": [{"content": {"parts": [{"text": "ok"}]}}],
            "usageMetadata": {"promptTokenCount": 1, "candidatesTokenCount": 2},
        }
        captured, fake = _capture_body(payload)
        with mock.patch("discord_assistant.llm.request.urlopen", side_effect=fake):
            client._generate_sync(
                "describe", "gemini-1.5-flash", [("image/jpeg", self._IMG)]
            )
        parts = captured["body"]["contents"][0]["parts"]
        self.assertEqual(parts[0], {"text": "describe"})
        self.assertEqual(parts[1]["inlineData"]["mimeType"], "image/jpeg")
        # 이미지가 있는 응답도 usage 가 기록된다.
        self.assertEqual(client.last_usage, TokenUsage(1, 2))

    def test_ollama_with_image_adds_images_field(self) -> None:
        client = OllamaClient(base_url="http://x", default_model="llava")
        payload = {"response": "ok", "prompt_eval_count": 8, "eval_count": 4}
        captured, fake = _capture_body(payload)
        with mock.patch("discord_assistant.llm.request.urlopen", side_effect=fake):
            client._generate_sync("describe", "llava", [self._IMG])
        self.assertIn("images", captured["body"])
        self.assertEqual(len(captured["body"]["images"]), 1)
        self.assertEqual(client.last_usage, TokenUsage(8, 4))

    def test_ollama_no_images_omits_images_field(self) -> None:
        client = OllamaClient(base_url="http://x", default_model="llama3.1:8b")
        captured, fake = _capture_body({"response": "ok"})
        with mock.patch("discord_assistant.llm.request.urlopen", side_effect=fake):
            client._generate_sync("hi", "llama3.1:8b")
        # 이미지가 없으면 images 필드를 보내지 않아 기존 텍스트 경로 그대로다.
        self.assertNotIn("images", captured["body"])

    def test_generate_returns_text_with_images(self) -> None:
        # generate() 의 반환은 이미지 유무와 무관하게 항상 텍스트(str)다.
        client = OpenAIClient(api_key="sk-x")
        with mock.patch(
            "discord_assistant.llm.request.urlopen",
            return_value=_json_response(_OPENAI_OK_PAYLOAD),
        ):
            result = asyncio.run(
                client.generate("describe", images=[("image/png", self._IMG)])
            )
        self.assertEqual(result, "안녕하세요")


# ---------------------------------------------------------------------------
# #20: 함수/툴 호출 (OpenAI tools / Anthropic tool_use) 경량 에이전트 루프
# ---------------------------------------------------------------------------


def _sequential_urlopen(payloads: list[dict]) -> tuple[list, object]:
    """호출 순서대로 payloads 를 돌려주는 urlopen 페이크를 만든다 (#20).

    반환: (전송된 body dict 리스트, fake_urlopen). 각 호출마다 다음 payload 를
    소비하므로 다단계 에이전트 루프(tool_call → tool_result → 최종 답변)를 흉내낼 수 있다.
    """
    sent_bodies: list = []
    remaining = list(payloads)

    def fake_urlopen(req, timeout=None):  # type: ignore[no-untyped-def]
        sent_bodies.append(json.loads(req.data.decode("utf-8")))
        payload = remaining.pop(0)
        return _json_response(payload)

    return sent_bodies, fake_urlopen


_SEARCH_TOOL = ToolSpec(
    name="search_messages",
    description="Search the channel.",
    parameters={
        "type": "object",
        "properties": {"query": {"type": "string"}},
        "required": ["query"],
    },
)


class OpenAIToolLoopTest(unittest.TestCase):
    """OpenAIClient.generate_with_tools 경량 에이전트 루프 (#20)."""

    def setUp(self) -> None:
        self.client = OpenAIClient(api_key="sk-x")

    def test_no_tools_falls_back_to_generate(self) -> None:
        # 툴이 비어 있으면 일반 generate 경로(텍스트)와 동일하게 동작한다.
        with mock.patch(
            "discord_assistant.llm.request.urlopen",
            return_value=_json_response(_OPENAI_OK_PAYLOAD),
        ):
            result = asyncio.run(
                self.client.generate_with_tools(
                    "hi", tools=[], tool_runner=_make_tool_runner({})
                )
            )
        self.assertEqual(result, "안녕하세요")

    def test_tool_call_then_final_answer(self) -> None:
        # 1차 응답: search_messages 호출. 2차 응답: 최종 텍스트.
        first = {
            "choices": [
                {
                    "message": {
                        "role": "assistant",
                        "content": None,
                        "tool_calls": [
                            {
                                "id": "call_1",
                                "type": "function",
                                "function": {
                                    "name": "search_messages",
                                    "arguments": json.dumps({"query": "회의"}),
                                },
                            }
                        ],
                    }
                }
            ]
        }
        final = {"choices": [{"message": {"role": "assistant", "content": "최종 답변"}}]}
        ran: dict = {}

        async def runner(name: str, args: dict) -> str:
            ran["name"] = name
            ran["args"] = args
            return "찾은 메시지: 회의는 3시"

        sent, fake = _sequential_urlopen([first, final])
        with mock.patch("discord_assistant.llm.request.urlopen", side_effect=fake):
            result = asyncio.run(
                self.client.generate_with_tools(
                    "회의 언제야?", tools=[_SEARCH_TOOL], tool_runner=runner
                )
            )
        self.assertEqual(result, "최종 답변")
        # 툴 러너가 모델이 준 인자로 호출됐는지 확인.
        self.assertEqual(ran["name"], "search_messages")
        self.assertEqual(ran["args"], {"query": "회의"})
        # 2번째 요청에 tool 결과 메시지가 포함됐는지 확인.
        second_messages = sent[1]["messages"]
        tool_msgs = [m for m in second_messages if m.get("role") == "tool"]
        self.assertEqual(len(tool_msgs), 1)
        self.assertEqual(tool_msgs[0]["content"], "찾은 메시지: 회의는 3시")
        # 첫 요청에 tools 가 실렸는지 확인.
        self.assertIn("tools", sent[0])

    def test_no_tool_call_returns_text_immediately(self) -> None:
        # 모델이 툴을 호출하지 않고 바로 텍스트를 주면 1회 호출로 끝난다.
        payload = {"choices": [{"message": {"role": "assistant", "content": "바로 답변"}}]}
        sent, fake = _sequential_urlopen([payload])
        with mock.patch("discord_assistant.llm.request.urlopen", side_effect=fake):
            result = asyncio.run(
                self.client.generate_with_tools(
                    "hi", tools=[_SEARCH_TOOL], tool_runner=_make_tool_runner({})
                )
            )
        self.assertEqual(result, "바로 답변")
        self.assertEqual(len(sent), 1)

    def test_tool_runner_failure_does_not_break_loop(self) -> None:
        # 툴 실행 예외는 모델에 관측값으로 전달되고 루프는 계속된다.
        first = {
            "choices": [
                {
                    "message": {
                        "role": "assistant",
                        "content": None,
                        "tool_calls": [
                            {
                                "id": "c1",
                                "type": "function",
                                "function": {"name": "search_messages", "arguments": "{}"},
                            }
                        ],
                    }
                }
            ]
        }
        final = {"choices": [{"message": {"role": "assistant", "content": "복구된 답변"}}]}

        async def failing_runner(name: str, args: dict) -> str:
            raise RuntimeError("boom")

        sent, fake = _sequential_urlopen([first, final])
        with mock.patch("discord_assistant.llm.request.urlopen", side_effect=fake):
            result = asyncio.run(
                self.client.generate_with_tools(
                    "q", tools=[_SEARCH_TOOL], tool_runner=failing_runner
                )
            )
        self.assertEqual(result, "복구된 답변")
        tool_msgs = [m for m in sent[1]["messages"] if m.get("role") == "tool"]
        self.assertIn("failed", tool_msgs[0]["content"])


class AnthropicToolLoopTest(unittest.TestCase):
    """AnthropicClient.generate_with_tools 경량 에이전트 루프 (#20)."""

    def setUp(self) -> None:
        self.client = AnthropicClient(api_key="sk-ant-x")

    def test_no_tools_falls_back_to_generate(self) -> None:
        with mock.patch(
            "discord_assistant.llm.request.urlopen",
            return_value=_json_response(_ANTHROPIC_OK_PAYLOAD),
        ):
            result = asyncio.run(
                self.client.generate_with_tools(
                    "hi", tools=[], tool_runner=_make_tool_runner({})
                )
            )
        self.assertEqual(result, "반갑습니다")

    def test_tool_use_then_final_answer(self) -> None:
        first = {
            "stop_reason": "tool_use",
            "content": [
                {"type": "text", "text": "찾아볼게요"},
                {
                    "type": "tool_use",
                    "id": "tu_1",
                    "name": "search_messages",
                    "input": {"query": "회의"},
                },
            ],
        }
        final = {
            "stop_reason": "end_turn",
            "content": [{"type": "text", "text": "최종 답변"}],
        }
        ran: dict = {}

        async def runner(name: str, args: dict) -> str:
            ran["name"] = name
            ran["args"] = args
            return "회의는 3시"

        sent, fake = _sequential_urlopen([first, final])
        with mock.patch("discord_assistant.llm.request.urlopen", side_effect=fake):
            result = asyncio.run(
                self.client.generate_with_tools(
                    "회의 언제?", tools=[_SEARCH_TOOL], tool_runner=runner
                )
            )
        self.assertEqual(result, "최종 답변")
        self.assertEqual(ran["args"], {"query": "회의"})
        # 2번째 요청 messages 에 tool_result 가 user role 로 실린다.
        second_messages = sent[1]["messages"]
        user_results = [
            m
            for m in second_messages
            if m.get("role") == "user"
            and isinstance(m.get("content"), list)
            and any(
                isinstance(b, dict) and b.get("type") == "tool_result"
                for b in m["content"]
            )
        ]
        self.assertEqual(len(user_results), 1)
        # input_schema 규격으로 tools 가 실렸는지 확인.
        self.assertIn("tools", sent[0])
        self.assertIn("input_schema", sent[0]["tools"][0])

    def test_no_tool_use_returns_text(self) -> None:
        payload = {
            "stop_reason": "end_turn",
            "content": [{"type": "text", "text": "바로 답변"}],
        }
        sent, fake = _sequential_urlopen([payload])
        with mock.patch("discord_assistant.llm.request.urlopen", side_effect=fake):
            result = asyncio.run(
                self.client.generate_with_tools(
                    "hi", tools=[_SEARCH_TOOL], tool_runner=_make_tool_runner({})
                )
            )
        self.assertEqual(result, "바로 답변")
        self.assertEqual(len(sent), 1)


class FallbackToolLoopTest(unittest.TestCase):
    """툴 미지원 제공자(Ollama/Gemini)는 generate 로 폴백한다 (#20)."""

    def test_ollama_falls_back_to_generate(self) -> None:
        client = OllamaClient(base_url="http://x", default_model="llama3.1:8b")
        with mock.patch(
            "discord_assistant.llm.request.urlopen",
            return_value=_json_response({"response": "오라마 답변"}),
        ):
            result = asyncio.run(
                client.generate_with_tools(
                    "hi", tools=[_SEARCH_TOOL], tool_runner=_make_tool_runner({})
                )
            )
        # 툴을 무시하고 일반 generate 텍스트를 반환한다(폴백).
        self.assertEqual(result, "오라마 답변")

    def test_gemini_falls_back_to_generate(self) -> None:
        client = GeminiClient(api_key="x", default_model="gemini-1.5-flash")
        payload = {"candidates": [{"content": {"parts": [{"text": "제미니 답변"}]}}]}
        with mock.patch(
            "discord_assistant.llm.request.urlopen",
            return_value=_json_response(payload),
        ):
            result = asyncio.run(
                client.generate_with_tools(
                    "hi", tools=[_SEARCH_TOOL], tool_runner=_make_tool_runner({})
                )
            )
        self.assertEqual(result, "제미니 답변")


def _make_tool_runner(_mapping: dict):  # type: ignore[no-untyped-def]
    """간단한 no-op 툴 러너(폴백/툴 미사용 경로용)."""

    async def _runner(name: str, args: dict) -> str:  # pragma: no cover - 호출되지 않음
        return ""

    return _runner


if __name__ == "__main__":
    unittest.main()
