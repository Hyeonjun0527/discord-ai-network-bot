from __future__ import annotations

import asyncio
import unittest

from discord_assistant.llm import (
    AnthropicClient,
    CircuitBreaker,
    CircuitBreakerOpenError,
    LLMError,
    OllamaClient,
    OllamaError,
    OpenAIClient,
    _is_retryable,
    _with_circuit_breaker,
    _with_retry,
    parse_generate_response,
)


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


if __name__ == "__main__":
    unittest.main()
