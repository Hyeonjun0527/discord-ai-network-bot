"""monitor.py 단위 테스트 — 레이트리밋·중복 억제(#53) 포함.

`time_fn` 주입으로 시간을 결정적으로 제어한다(실제 sleep 없음).
"""
from __future__ import annotations

import unittest
from unittest.mock import AsyncMock, MagicMock

import pytest

from discord_assistant.monitor import (
    AlertRateLimiter,
    compute_signature,
    format_disconnect_message,
    format_error_message,
    notify_developer,
)


class FakeClock:
    """주입 가능한 결정적 시계. now()는 단조 증가하는 초 단위 값."""

    def __init__(self, start: float = 0.0) -> None:
        self.t = start

    def __call__(self) -> float:
        return self.t

    def advance(self, seconds: float) -> None:
        self.t += seconds


# ---------------------------------------------------------------------------
# compute_signature
# ---------------------------------------------------------------------------


class ComputeSignatureTest(unittest.TestCase):
    def test_numbers_are_normalized(self) -> None:
        # 숫자만 다른 메시지는 같은 시그니처로 묶여야 한다.
        sig_a = compute_signature("error at line 42 in worker 7")
        sig_b = compute_signature("error at line 99 in worker 3")
        self.assertEqual(sig_a, sig_b)

    def test_hex_addresses_are_normalized(self) -> None:
        sig_a = compute_signature("object at 0xdeadbeef failed")
        sig_b = compute_signature("object at 0x12345678 failed")
        self.assertEqual(sig_a, sig_b)

    def test_different_errors_differ(self) -> None:
        sig_a = compute_signature("ValueError: bad input")
        sig_b = compute_signature("KeyError: missing key")
        self.assertNotEqual(sig_a, sig_b)

    def test_traceback_uses_header_and_last_line(self) -> None:
        # 트레이스백처럼 여러 줄이면 마지막 의미 있는 줄(예외 타입)이 핵심에 포함된다.
        msg = (
            "[discord-assistant] Unhandled error in event `on_message`:\n"
            "```\n"
            'File "x.py", line 10, in foo\n'
            "    raise ValueError(123)\n"
            "ValueError: something broke\n"
            "```"
        )
        sig = compute_signature(msg)
        self.assertIn("valueerror: something broke", sig)
        self.assertIn("on_message", sig)


# ---------------------------------------------------------------------------
# AlertRateLimiter
# ---------------------------------------------------------------------------


class AlertRateLimiterTest(unittest.TestCase):
    def test_first_message_allowed(self) -> None:
        clock = FakeClock()
        limiter = AlertRateLimiter(time_fn=clock)
        allowed, outgoing = limiter.check("first error")
        self.assertTrue(allowed)
        self.assertEqual(outgoing, "first error")

    def test_dedup_window_suppresses_same_signature(self) -> None:
        clock = FakeClock()
        limiter = AlertRateLimiter(dedup_window_seconds=60.0, time_fn=clock)

        allowed1, _ = limiter.check("crash code 1")
        self.assertTrue(allowed1)

        # 윈도우 내(같은 시그니처: 숫자만 다름) -> 억제.
        clock.advance(30.0)
        allowed2, _ = limiter.check("crash code 2")
        self.assertFalse(allowed2)
        self.assertEqual(limiter.pending_suppressed(), 1)

    def test_dedup_window_expires(self) -> None:
        clock = FakeClock()
        limiter = AlertRateLimiter(dedup_window_seconds=60.0, time_fn=clock)

        limiter.check("crash code 1")
        # 윈도우 경과 후 같은 시그니처는 다시 통과.
        clock.advance(61.0)
        allowed, outgoing = limiter.check("crash code 9")
        self.assertTrue(allowed)
        # 억제건이 없었으므로 요약 없이 원문 그대로.
        self.assertEqual(outgoing, "crash code 9")

    def test_distinct_signatures_not_deduped(self) -> None:
        clock = FakeClock()
        limiter = AlertRateLimiter(
            dedup_window_seconds=60.0, max_per_minute=10, time_fn=clock
        )

        allowed_a, _ = limiter.check("ValueError: bad")
        allowed_b, _ = limiter.check("KeyError: missing")
        self.assertTrue(allowed_a)
        self.assertTrue(allowed_b)

    def test_per_minute_cap(self) -> None:
        clock = FakeClock()
        limiter = AlertRateLimiter(
            dedup_window_seconds=0.0, max_per_minute=3, max_per_hour=100, time_fn=clock
        )

        # dedup 윈도우 0이므로 같은 시그니처도 분당 상한까지는 통과.
        for i in range(3):
            allowed, _ = limiter.check("error X")
            self.assertTrue(allowed, f"iteration {i} should pass")
            clock.advance(1.0)

        # 4번째는 분당 상한 초과 -> 억제.
        allowed, _ = limiter.check("error X")
        self.assertFalse(allowed)
        self.assertEqual(limiter.pending_suppressed(), 1)

    def test_per_minute_cap_resets_after_window(self) -> None:
        clock = FakeClock()
        limiter = AlertRateLimiter(
            dedup_window_seconds=0.0, max_per_minute=2, max_per_hour=100, time_fn=clock
        )

        self.assertTrue(limiter.check("err")[0])
        clock.advance(1.0)
        self.assertTrue(limiter.check("err")[0])
        clock.advance(1.0)
        # 상한 초과.
        self.assertFalse(limiter.check("err")[0])
        # 분 경계를 넘기면 다시 통과.
        clock.advance(60.0)
        self.assertTrue(limiter.check("err")[0])

    def test_per_hour_cap(self) -> None:
        clock = FakeClock()
        limiter = AlertRateLimiter(
            dedup_window_seconds=0.0,
            max_per_minute=1000,
            max_per_hour=5,
            time_fn=clock,
        )

        for _ in range(5):
            self.assertTrue(limiter.check("err")[0])
            clock.advance(120.0)  # 분당 상한엔 안 걸리도록 넉넉히 진행

        # 6번째는 시간당 상한 초과 -> 억제.
        self.assertFalse(limiter.check("err")[0])

    def test_suppressed_summary_attached_to_next_allowed(self) -> None:
        clock = FakeClock()
        limiter = AlertRateLimiter(dedup_window_seconds=60.0, time_fn=clock)

        # 첫 통과.
        allowed1, out1 = limiter.check("boom 1")
        self.assertTrue(allowed1)
        self.assertNotIn("억제", out1)

        # 같은 시그니처 2건 억제.
        clock.advance(1.0)
        limiter.check("boom 2")
        clock.advance(1.0)
        limiter.check("boom 3")
        self.assertEqual(limiter.pending_suppressed(), 2)

        # 윈도우 경과 후 다음 통과 알림에 요약이 붙고, 카운터는 리셋.
        clock.advance(60.0)
        allowed_next, out_next = limiter.check("boom 99")
        self.assertTrue(allowed_next)
        self.assertIn("2건", out_next)
        self.assertIn("억제", out_next)
        self.assertEqual(limiter.pending_suppressed(), 0)

    def test_summary_lists_top_signatures(self) -> None:
        clock = FakeClock()
        limiter = AlertRateLimiter(dedup_window_seconds=60.0, time_fn=clock)

        limiter.check("alpha error")  # 통과
        clock.advance(1.0)
        limiter.check("alpha error 1")  # 억제(같은 시그니처)
        clock.advance(1.0)
        limiter.check("alpha error 2")  # 억제

        clock.advance(60.0)
        _, out = limiter.check("recovered")
        self.assertIn("억제된 주요 시그니처", out)


# ---------------------------------------------------------------------------
# notify_developer (async) — 환경변수/봇 mock
# ---------------------------------------------------------------------------


def _make_bot() -> MagicMock:
    """get_user/fetch_user/user.send를 가진 가짜 봇."""
    bot = MagicMock()
    user = MagicMock()
    user.send = AsyncMock()
    bot.get_user = MagicMock(return_value=user)
    bot.fetch_user = AsyncMock(return_value=user)
    bot._fake_user = user  # 테스트 접근용
    return bot


@pytest.mark.asyncio
async def test_notify_developer_skips_when_unset(monkeypatch) -> None:
    monkeypatch.delenv("DEVELOPER_USER_ID", raising=False)
    bot = _make_bot()
    await notify_developer("hi", bot)
    bot._fake_user.send.assert_not_called()


@pytest.mark.asyncio
async def test_notify_developer_sends_when_configured(monkeypatch) -> None:
    monkeypatch.setenv("DEVELOPER_USER_ID", "12345")
    bot = _make_bot()
    limiter = AlertRateLimiter(time_fn=FakeClock())
    await notify_developer("hello dev", bot, rate_limiter=limiter)
    bot._fake_user.send.assert_awaited_once_with("hello dev")


@pytest.mark.asyncio
async def test_notify_developer_invalid_id_skips(monkeypatch) -> None:
    monkeypatch.setenv("DEVELOPER_USER_ID", "not-an-int")
    bot = _make_bot()
    await notify_developer("hello", bot)
    bot._fake_user.send.assert_not_called()


@pytest.mark.asyncio
async def test_notify_developer_rate_limited_does_not_send(monkeypatch) -> None:
    monkeypatch.setenv("DEVELOPER_USER_ID", "12345")
    bot = _make_bot()
    clock = FakeClock()
    limiter = AlertRateLimiter(dedup_window_seconds=60.0, time_fn=clock)

    await notify_developer("repeat error 1", bot, rate_limiter=limiter)
    clock.advance(5.0)
    await notify_developer("repeat error 2", bot, rate_limiter=limiter)

    # 두 번째는 dedup으로 억제 -> send는 한 번만.
    self_send = bot._fake_user.send
    self_send.assert_awaited_once()


@pytest.mark.asyncio
async def test_notify_developer_truncates_long_message(monkeypatch) -> None:
    monkeypatch.setenv("DEVELOPER_USER_ID", "12345")
    bot = _make_bot()
    limiter = AlertRateLimiter(time_fn=FakeClock())
    long_msg = "x" * 5000
    await notify_developer(long_msg, bot, rate_limiter=limiter)
    sent = bot._fake_user.send.await_args.args[0]
    assert len(sent) <= 1900
    assert sent.endswith("...")


@pytest.mark.asyncio
async def test_notify_developer_fetch_user_fallback(monkeypatch) -> None:
    monkeypatch.setenv("DEVELOPER_USER_ID", "12345")
    bot = _make_bot()
    bot.get_user = MagicMock(return_value=None)  # 캐시 미스 -> fetch_user 사용
    limiter = AlertRateLimiter(time_fn=FakeClock())
    await notify_developer("via fetch", bot, rate_limiter=limiter)
    bot.fetch_user.assert_awaited_once_with(12345)
    bot._fake_user.send.assert_awaited_once_with("via fetch")


# ---------------------------------------------------------------------------
# format_* 시그니처 유지 회귀 테스트
# ---------------------------------------------------------------------------


class FormatMessageTest(unittest.TestCase):
    def test_disconnect_message_no_shard(self) -> None:
        msg = format_disconnect_message()
        self.assertIn("disconnected", msg)
        self.assertNotIn("shard", msg)

    def test_disconnect_message_with_shard(self) -> None:
        msg = format_disconnect_message(shard_id=2)
        self.assertIn("shard 2", msg)

    def test_error_message_contains_event_and_traceback(self) -> None:
        try:
            raise ValueError("kaboom")
        except ValueError as exc:
            msg = format_error_message("on_message", exc)
        self.assertIn("on_message", msg)
        self.assertIn("ValueError", msg)
        self.assertIn("kaboom", msg)


if __name__ == "__main__":  # pragma: no cover
    unittest.main()
