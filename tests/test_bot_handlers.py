"""슬래시 핸들러(/summarize · /ask · /chat) 통합 테스트 (ROADMAP #57).

create_bot(settings) 내부의 슬래시 명령 콜백은 클로저이므로 직접 import 할 수
없다. bot.tree.get_command(name).callback 으로 꺼내 mock interaction 으로 호출한다.

네트워크/LLM/Discord 는 전부 mock 한다:
  - LLM: bot._get_llm 을 가짜 클라이언트(_FakeLLM)를 돌려주도록 monkeypatch.
    실제 generate/generate_stream/generate_with_tools 가 가짜 응답을 낸다.
  - 트랜스크립트: bot._collect_transcript 를 AsyncMock 으로 패치해 주입(채널
    히스토리/Discord HTTP 의존을 끊는다).
  - Interaction: AsyncMock 으로 response.defer/send_message, followup.send,
    edit_original_response 등을 흉내낸다.

ConfigStore 는 :memory: 가 연결별로 분리되는 문제가 있어 tempfile 기반
파일 DB(_FileStoreCase 패턴)를 사용한다. usage_log 기록은 store.get_stats 로
검증한다.
"""
from __future__ import annotations

import tempfile
import unittest
from pathlib import Path
from typing import Any
from unittest.mock import AsyncMock, MagicMock, patch

from discord_assistant import bot as bot_module
from discord_assistant.bot import UserFacingError, create_bot, reset_cooldowns
from discord_assistant.llm import LLMError, TokenUsage
from discord_assistant.models import LLMProvider
from discord_assistant.settings import AppSettings
from discord_assistant.storage import ConfigStore


# ---------------------------------------------------------------------------
# 가짜 LLM 클라이언트 — 실제 네트워크 호출 없이 고정 응답을 낸다.
# ---------------------------------------------------------------------------
class _FakeLLM:
    """generate/generate_stream/generate_with_tools 를 흉내내는 가짜 LLM.

    - generate: 고정 텍스트를 돌려준다.
    - generate_stream: 고정 텍스트를 청크로 나눠 yield 한다(스트리밍 경로용).
    - generate_with_tools: search=True 경로용. tool_runner 는 호출하지 않고
      generate 와 동일한 텍스트를 돌려준다(루프 동작은 llm 단위 테스트가 담당).
    - last_usage: _usage_tokens 가 읽는 토큰 사용량(기록 검증용).
    """

    def __init__(self, text: str = "가짜 응답입니다.") -> None:
        self.text = text
        self.last_usage = TokenUsage(prompt_tokens=11, completion_tokens=7)
        self.generate_calls: list[str] = []
        self.stream_calls: list[str] = []
        self.tool_calls: list[str] = []

    async def generate(self, prompt: str, *, model: str | None = None, **_: Any) -> str:
        self.generate_calls.append(prompt)
        return self.text

    async def generate_stream(self, prompt: str, *, model: str | None = None):
        self.stream_calls.append(prompt)
        # 두 청크로 나눠 점진 출력 경로(_stream_to_interaction)를 실제로 태운다.
        for piece in (self.text[: len(self.text) // 2], self.text[len(self.text) // 2 :]):
            yield piece

    async def generate_with_tools(
        self, prompt: str, *, tools=None, tool_runner=None, model: str | None = None
    ) -> str:
        self.tool_calls.append(prompt)
        return self.text


class _RaisingLLM(_FakeLLM):
    """generate/스트림 모두 LLMError 를 던지는 가짜 LLM(오류 경로용)."""

    def __init__(self, exc: Exception) -> None:
        super().__init__()
        self._exc = exc

    async def generate(self, prompt: str, *, model: str | None = None, **_: Any) -> str:
        raise self._exc

    async def generate_stream(self, prompt: str, *, model: str | None = None):
        raise self._exc
        yield ""  # pragma: no cover — 도달 불가(위에서 raise). 제너레이터 표식용.

    async def generate_with_tools(
        self, prompt: str, *, tools=None, tool_runner=None, model: str | None = None
    ) -> str:
        raise self._exc


def _make_settings(database_url: str) -> AppSettings:
    """테스트용 AppSettings. 명령 동기화/메트릭 서버는 끈다."""
    return AppSettings(
        discord_bot_token="test-token",
        ollama_base_url="http://localhost:11434",
        ollama_model="test-model",
        database_url=database_url,
        default_summary_limit=10,
        max_context_chars=12_000,
        default_language="ko",
        ollama_timeout_seconds=60,
        auto_sync_commands=False,
        secret_key="test-secret-key-1234567890",
        metrics_port=0,
    )


def _make_interaction() -> MagicMock:
    """슬래시 핸들러에 넘길 discord.Interaction 목.

    conftest.interaction 픽스처와 동일한 구조지만, 클래스 기반
    IsolatedAsyncioTestCase 에서 쓰기 위해 직접 구성한다.
    """
    user = MagicMock(name="User")
    user.id = 111
    user.display_name = "tester"
    user.roles = []  # _has_allowed_role 가 순회하므로 비어 있어야 안전.

    guild = MagicMock(name="Guild")
    guild.id = 222
    guild.name = "test-guild"

    channel = MagicMock(name="Channel")
    channel.id = 333
    channel.send = AsyncMock()

    response = MagicMock(name="Response")
    # 실제 discord.py 처럼 defer/send_message 이후 is_done() 이 True 가 되도록
    # 상태를 흉내낸다. 이래야 _send_answer_with_overflow 가 followup 경로로 분기한다.
    response.is_done = MagicMock(return_value=False)

    def _mark_done(*_args: Any, **_kwargs: Any) -> None:
        response.is_done.return_value = True

    response.defer = AsyncMock(side_effect=_mark_done)
    response.send_message = AsyncMock(side_effect=_mark_done)

    # followup.send 는 wait=True 시 메시지 핸들을 돌려준다. 스트리밍 경로
    # (_stream_to_interaction)는 그 핸들에 await message.edit(...) 를 호출하고,
    # 피드백 추적(_track_for_feedback)은 await msg.add_reaction(...) 을 호출하므로
    # 둘 다 AsyncMock 으로 둬야 await 가 가능하다.
    sent_message = MagicMock(name="SentMessage")
    sent_message.id = 999
    sent_message.edit = AsyncMock()
    sent_message.add_reaction = AsyncMock()
    followup = MagicMock(name="Followup")
    followup.send = AsyncMock(return_value=sent_message)

    inter = MagicMock(name="Interaction")
    inter.response = response
    inter.followup = followup
    inter.user = user
    inter.guild = guild
    inter.channel = channel
    inter.id = 4242
    inter.edit_original_response = AsyncMock()
    return inter


class _HandlerCase(unittest.IsolatedAsyncioTestCase):
    """파일 기반 임시 SQLite + create_bot 으로 슬래시 콜백을 꺼내 쓰는 베이스.

    :memory: 는 연결별 분리 이슈가 있어 tempfile 파일 DB 를 쓴다(#95 와 동일).
    """

    async def asyncSetUp(self) -> None:
        reset_cooldowns()  # 쿨다운 전역 상태를 테스트마다 초기화한다.
        self._tmp = tempfile.TemporaryDirectory()
        db_path = Path(self._tmp.name) / "assistant.db"
        self.database_url = f"sqlite:///{db_path}"
        self.settings = _make_settings(self.database_url)
        self.bot = create_bot(self.settings)
        # create_bot 내부 클로저가 만든 ConfigStore 와 같은 DB 를 가리키는 별도
        # 핸들이 필요하다. setup_hook 을 호출하지 않으므로, 같은 URL 로 직접 연다.
        self.store = ConfigStore(
            self.database_url,
            default_model="test-model",
            default_summary_limit=10,
            default_language="ko",
        )
        await self.store.initialize()

    async def asyncTearDown(self) -> None:
        # create_bot 내부의 ConfigStore 는 클로저 지역이라 직접 닫을 핸들이 없다.
        # 우리가 연 별도 핸들만 닫고, 임시 디렉터리 정리로 파일을 제거한다(테스트
        # 격리). 봇 store 의 미닫힌 연결은 임시 DB 와 함께 사라진다.
        await self.store.close()
        self._tmp.cleanup()
        reset_cooldowns()

    def _callback(self, name: str):
        """등록된 슬래시 명령의 콜백(클로저)을 꺼낸다."""
        cmd = self.bot.tree.get_command(name)
        assert cmd is not None, f"command not found: {name}"
        return cmd.callback

    def _patch_llm(self, llm: Any):
        """bot._get_llm 을 주어진 가짜 LLM 을 돌려주도록 패치하는 컨텍스트매니저."""
        return patch.object(bot_module, "_get_llm", return_value=llm)

    def _patch_transcript(self, text: str):
        """bot._collect_transcript 를 고정 트랜스크립트로 패치한다."""
        return patch.object(
            bot_module, "_collect_transcript", AsyncMock(return_value=text)
        )


# ---------------------------------------------------------------------------
# /summarize
# ---------------------------------------------------------------------------
class SummarizeHandlerTest(_HandlerCase):
    async def test_defer_then_followup_records_usage(self) -> None:
        """defer → followup 경로: 가짜 LLM 응답을 followup 으로 보내고 usage 기록."""
        inter = _make_interaction()
        llm = _FakeLLM("이것은 요약 결과입니다.")
        with self._patch_llm(llm), self._patch_transcript("a: 안녕\nb: 반가워"):
            await self._callback("summarize")(inter, limit=5)

        inter.response.defer.assert_awaited_once()
        # 본문은 followup 으로 전송된다(_send_answer_with_overflow → followup.send).
        self.assertTrue(inter.followup.send.await_count >= 1)
        sent = inter.followup.send.await_args.args[0]
        self.assertIn("이것은 요약 결과입니다.", sent)
        # LLM 의 비스트리밍 generate 경로를 탔다.
        self.assertEqual(len(llm.generate_calls), 1)

        stats = await self.store.get_stats(222)
        by_cmd = {r["command"]: r["count"] for r in stats["by_command"]}
        self.assertEqual(by_cmd.get("summarize"), 1)
        self.assertEqual(stats["error_rate"], 0.0)

    async def test_empty_transcript_raises_user_facing_guidance(self) -> None:
        """빈 트랜스크립트면 UserFacingError 안내가 오류 임베드로 전송되고 error 기록."""
        inter = _make_interaction()
        inter.response.is_done = MagicMock(return_value=True)  # defer 이후 상태.
        llm = _FakeLLM()
        with self._patch_llm(llm), self._patch_transcript(""):
            await self._callback("summarize")(inter, limit=5)

        # 오류 임베드는 followup 으로 보낸다(_send_error_embed, response.is_done()).
        inter.followup.send.assert_awaited()
        kwargs = inter.followup.send.await_args.kwargs
        embed = kwargs.get("embed")
        self.assertIsNotNone(embed)
        self.assertIn("요약할 메시지가 없어요", embed.description)
        # LLM 은 호출되지 않아야 한다(트랜스크립트 단계에서 차단).
        self.assertEqual(len(llm.generate_calls), 0)

        stats = await self.store.get_stats(222)
        self.assertEqual(stats["error_rate"], 100.0)

    async def test_cooldown_blocks_second_call(self) -> None:
        """연속 호출 시 두 번째는 쿨다운으로 차단(LLM 미호출)된다."""
        llm = _FakeLLM("요약")
        with self._patch_llm(llm), self._patch_transcript("a: 메시지"):
            first = _make_interaction()
            await self._callback("summarize")(first, limit=5)
            second = _make_interaction()
            await self._callback("summarize")(second, limit=5)

        # 두 번째 호출은 defer 전에 쿨다운 안내(send_message)로 끝난다.
        second.response.send_message.assert_awaited_once()
        msg = second.response.send_message.await_args.args[0]
        self.assertIn("초 후에 다시 시도", msg)
        second.response.defer.assert_not_awaited()
        # generate 는 첫 호출 1번만.
        self.assertEqual(len(llm.generate_calls), 1)

    async def test_disallowed_role_blocks(self) -> None:
        """allowed_role 설정 시 역할 없는 사용자는 권한 오류로 차단된다."""
        await self.store.set_allowed_role(222, role_id=555)
        inter = _make_interaction()
        inter.user.roles = []  # 필요한 역할이 없다.
        inter.response.is_done = MagicMock(return_value=True)
        llm = _FakeLLM()
        with self._patch_llm(llm), self._patch_transcript("a: hi"):
            await self._callback("summarize")(inter, limit=5)

        inter.followup.send.assert_awaited()
        embed = inter.followup.send.await_args.kwargs.get("embed")
        self.assertIsNotNone(embed)
        self.assertIn("권한이 없어요", embed.description)
        self.assertEqual(len(llm.generate_calls), 0)

    async def test_custom_summarize_prompt_used(self) -> None:
        """custom_summarize_prompt 가 설정되면 {transcript} 치환 후 LLM 에 전달된다.

        #89/#116: 커스텀 경로도 _wrap_untrusted + _INJECTION_GUARD 로 인젝션 방어선을
        유지한다. 보안 지침이 앞에 붙고 transcript 가 구분자로 래핑되므로, 커스텀
        본문은 보안 지침 다음에 위치하고 transcript 내용은 태그로 감싸진다.
        """
        await self.store.set_custom_prompt(222, "summarize", "요약해줘: {transcript}")
        inter = _make_interaction()
        llm = _FakeLLM("커스텀 요약")
        with self._patch_llm(llm), self._patch_transcript("a: 본문내용"):
            await self._callback("summarize")(inter, limit=5)

        self.assertEqual(len(llm.generate_calls), 1)
        prompt = llm.generate_calls[0]
        # 인젝션 방어선이 앞에 붙고, 그 뒤에 커스텀 프롬프트 본문이 온다.
        self.assertTrue(prompt.startswith("Security:"))
        self.assertIn("요약해줘: ", prompt)
        # transcript 내용은 들어가되 구분자 태그로 래핑된다.
        self.assertIn("본문내용", prompt)
        self.assertIn("<transcript>", prompt)


# ---------------------------------------------------------------------------
# /ask
# ---------------------------------------------------------------------------
class AskHandlerTest(_HandlerCase):
    async def test_defer_then_followup_records_usage(self) -> None:
        """defer → followup 경로로 답을 보내고 질문/답을 함께 표시, usage 기록."""
        inter = _make_interaction()
        llm = _FakeLLM("정답입니다.")
        with self._patch_llm(llm), self._patch_transcript("a: 어제 회의 어땠어?"):
            await self._callback("ask")(inter, question="회의 결론은?", limit=5)

        inter.response.defer.assert_awaited_once()
        inter.followup.send.assert_awaited()
        # 첫 followup.send 가 본문(질문+답)을 보낸다.
        sent = inter.followup.send.await_args_list[0].args[0]
        self.assertIn("회의 결론은?", sent)
        self.assertIn("정답입니다.", sent)
        self.assertEqual(len(llm.generate_calls), 1)

        stats = await self.store.get_stats(222)
        by_cmd = {r["command"]: r["count"] for r in stats["by_command"]}
        self.assertEqual(by_cmd.get("ask"), 1)

    async def test_empty_transcript_raises_user_facing_guidance(self) -> None:
        """참고할 메시지가 없으면 UserFacingError 안내가 전송되고 error 기록."""
        inter = _make_interaction()
        inter.response.is_done = MagicMock(return_value=True)
        llm = _FakeLLM()
        with self._patch_llm(llm), self._patch_transcript(""):
            await self._callback("ask")(inter, question="뭐였지?", limit=5)

        inter.followup.send.assert_awaited()
        embed = inter.followup.send.await_args.kwargs.get("embed")
        self.assertIsNotNone(embed)
        self.assertIn("참고할 최근 메시지가 없어요", embed.description)
        self.assertEqual(len(llm.generate_calls), 0)
        stats = await self.store.get_stats(222)
        self.assertEqual(stats["error_rate"], 100.0)

    async def test_cooldown_blocks_second_call(self) -> None:
        """연속 /ask 의 두 번째는 쿨다운으로 차단(send_message)된다."""
        llm = _FakeLLM("답")
        with self._patch_llm(llm), self._patch_transcript("a: hi"):
            first = _make_interaction()
            await self._callback("ask")(first, question="q1", limit=5)
            second = _make_interaction()
            await self._callback("ask")(second, question="q2", limit=5)

        second.response.send_message.assert_awaited_once()
        self.assertIn("초 후에 다시 시도", second.response.send_message.await_args.args[0])
        second.response.defer.assert_not_awaited()
        self.assertEqual(len(llm.generate_calls), 1)

    async def test_search_path_uses_generate_with_tools(self) -> None:
        """search=True 면 generate_with_tools(툴 루프) 경로를 탄다."""
        inter = _make_interaction()
        llm = _FakeLLM("검색 기반 답")
        with self._patch_llm(llm), self._patch_transcript("a: hi"):
            await self._callback("ask")(inter, question="언제 결정됐어?", limit=5, search=True)

        # 일반 generate 가 아니라 generate_with_tools 가 호출됐다.
        self.assertEqual(len(llm.tool_calls), 1)
        self.assertEqual(len(llm.generate_calls), 0)
        sent = inter.followup.send.await_args_list[0].args[0]
        self.assertIn("검색 기반 답", sent)

    async def test_llm_error_sends_error_embed_and_records(self) -> None:
        """LLMError 발생 시 오류 임베드를 보내고 error 상태로 기록한다."""
        inter = _make_interaction()
        inter.response.is_done = MagicMock(return_value=True)
        llm = _RaisingLLM(LLMError("일시적 서버 오류"))
        with self._patch_llm(llm), self._patch_transcript("a: hi"):
            await self._callback("ask")(inter, question="q", limit=5)

        inter.followup.send.assert_awaited()
        embed = inter.followup.send.await_args.kwargs.get("embed")
        self.assertIsNotNone(embed)
        stats = await self.store.get_stats(222)
        self.assertEqual(stats["error_rate"], 100.0)


# ---------------------------------------------------------------------------
# /chat
# ---------------------------------------------------------------------------
class ChatHandlerTest(_HandlerCase):
    async def test_streaming_path_records_usage(self) -> None:
        """defer(ephemeral) → 스트리밍 followup 경로로 응답하고 usage 기록."""
        inter = _make_interaction()
        llm = _FakeLLM("스트리밍 응답 전체")
        with self._patch_llm(llm):
            await self._callback("chat")(inter, message="안녕?")

        # 기본은 비공개(ephemeral=True) defer.
        inter.response.defer.assert_awaited_once()
        self.assertTrue(inter.response.defer.await_args.kwargs.get("ephemeral"))
        # 스트리밍 경로(_stream_to_interaction)가 followup.send 로 첫 메시지를 만든다.
        inter.followup.send.assert_awaited()
        self.assertEqual(len(llm.stream_calls), 1)
        # 스트림이 텍스트를 냈으므로 폴백 generate 는 타지 않는다.
        self.assertEqual(len(llm.generate_calls), 0)

        stats = await self.store.get_stats(222)
        by_cmd = {r["command"]: r["count"] for r in stats["by_command"]}
        self.assertEqual(by_cmd.get("chat"), 1)

    async def test_chat_history_persisted(self) -> None:
        """대화 후 user/assistant 메시지가 chat_history 에 저장된다."""
        inter = _make_interaction()
        llm = _FakeLLM("응답 텍스트")
        with self._patch_llm(llm):
            await self._callback("chat")(inter, message="첫 메시지")

        history = await self.store.get_chat_history(
            111, guild_id=222, channel_id=333, limit=10
        )
        roles = [h["role"] for h in history]
        self.assertIn("user", roles)
        self.assertIn("assistant", roles)

    async def test_public_flag_defers_non_ephemeral(self) -> None:
        """public=True 면 공개(ephemeral=False)로 defer 한다."""
        inter = _make_interaction()
        llm = _FakeLLM("공개 응답")
        with self._patch_llm(llm):
            await self._callback("chat")(inter, message="공개로!", public=True)

        inter.response.defer.assert_awaited_once()
        self.assertFalse(inter.response.defer.await_args.kwargs.get("ephemeral"))

    async def test_cooldown_blocks_second_call(self) -> None:
        """연속 /chat 의 두 번째는 쿨다운으로 차단된다(LLM 미호출)."""
        llm = _FakeLLM("응답")
        with self._patch_llm(llm):
            first = _make_interaction()
            await self._callback("chat")(first, message="m1")
            second = _make_interaction()
            await self._callback("chat")(second, message="m2")

        second.response.send_message.assert_awaited_once()
        self.assertIn("초 후에 다시 시도", second.response.send_message.await_args.args[0])
        second.response.defer.assert_not_awaited()
        self.assertEqual(len(llm.stream_calls), 1)

    async def test_stream_failure_falls_back_to_generate(self) -> None:
        """스트림이 첫 청크 전에 LLMError 면 비스트리밍 generate 로 폴백한다."""

        class _StreamFailLLM(_FakeLLM):
            async def generate_stream(self, prompt: str, *, model: str | None = None):
                raise LLMError("스트림 시작 실패")
                yield ""  # pragma: no cover — 제너레이터 표식.

        inter = _make_interaction()
        llm = _StreamFailLLM("폴백 응답")
        with self._patch_llm(llm):
            await self._callback("chat")(inter, message="안녕")

        # 스트림 실패 후 폴백 generate 가 호출돼 응답을 보낸다.
        self.assertEqual(len(llm.generate_calls), 1)
        inter.followup.send.assert_awaited()
        stats = await self.store.get_stats(222)
        by_cmd = {r["command"]: r["count"] for r in stats["by_command"]}
        self.assertEqual(by_cmd.get("chat"), 1)


# ---------------------------------------------------------------------------
# 가짜 LLM 자체 sanity 체크 — 핸들러 가정(시그니처/반환)이 깨지면 잡아낸다.
# ---------------------------------------------------------------------------
class FakeLLMContractTest(unittest.IsolatedAsyncioTestCase):
    async def test_fake_generate_and_stream(self) -> None:
        llm = _FakeLLM("hello world")
        self.assertEqual(await llm.generate("p"), "hello world")
        chunks = [c async for c in llm.generate_stream("p")]
        self.assertEqual("".join(chunks), "hello world")

    async def test_provider_enum_importable(self) -> None:
        # 핸들러 경로가 LLMProvider 를 참조하므로 import 가능 여부만 가볍게 확인.
        self.assertTrue(hasattr(LLMProvider, "OLLAMA"))

    def test_user_facing_error_is_runtime_error(self) -> None:
        self.assertTrue(issubclass(UserFacingError, RuntimeError))


if __name__ == "__main__":  # pragma: no cover
    unittest.main()
