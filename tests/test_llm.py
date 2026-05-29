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
    LLMError,
    OllamaClient,
    OllamaError,
    OpenAIClient,
    OpenAIError,
    _is_retryable,
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


if __name__ == "__main__":
    unittest.main()
