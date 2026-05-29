"""bot.py 슬래시 명령 핸들러 커버리지 (bot-cmds-a).

대상: /summarize(since·thread·cache·권한·빈채널·토큰버짓), /ask(follow-up·search·
근거·에러), /translate(캐시·정상·쿨다운·에러), /search(매칭·무매칭·권한없음·에러),
/digest(정상·멀티청크·권한·빈기간·쿨다운).

test_bot_handlers.py 의 _FakeLLM/_make_interaction/파일DB 패턴을 모방한다.
모든 네트워크/LLM/Discord 호출은 mock 한다. ConfigStore 는 tempfile 파일 DB 를
쓰고 반드시 close() 한다(:memory: 는 연결별 분리 이슈, aiosqlite 비데몬 스레드
hang 방지).
"""
from __future__ import annotations

import tempfile
import unittest
from datetime import datetime, timezone
from pathlib import Path
from typing import Any
from unittest.mock import AsyncMock, MagicMock, patch

from discord_assistant import bot as bot_module
from discord_assistant.bot import (
    create_bot,
    reset_cooldowns,
    summarize_cache,
)
from discord_assistant.cache import clear_translation_cache, get_translation
from discord_assistant.llm import LLMError, TokenUsage
from discord_assistant.settings import AppSettings
from discord_assistant.storage import ConfigStore


# ---------------------------------------------------------------------------
# 가짜 LLM — test_bot_handlers.py 의 _FakeLLM 를 동일하게 재현.
# ---------------------------------------------------------------------------
class _FakeLLM:
    def __init__(self, text: str = "가짜 응답입니다.") -> None:
        self.text = text
        self.last_usage = TokenUsage(prompt_tokens=13, completion_tokens=9)
        self.generate_calls: list[str] = []
        self.tool_calls: list[str] = []

    async def generate(self, prompt: str, *, model: str | None = None, **_: Any) -> str:
        self.generate_calls.append(prompt)
        return self.text

    async def generate_stream(self, prompt: str, *, model: str | None = None):
        yield self.text

    async def generate_with_tools(
        self, prompt: str, *, tools=None, tool_runner=None, model: str | None = None
    ) -> str:
        self.tool_calls.append(prompt)
        return self.text


class _RaisingLLM(_FakeLLM):
    """generate/generate_with_tools 가 주어진 예외를 던지는 가짜 LLM(에러 경로)."""

    def __init__(self, exc: Exception) -> None:
        super().__init__()
        self._exc = exc

    async def generate(self, prompt: str, *, model: str | None = None, **_: Any) -> str:
        raise self._exc

    async def generate_with_tools(
        self, prompt: str, *, tools=None, tool_runner=None, model: str | None = None
    ) -> str:
        raise self._exc


def _make_settings(database_url: str) -> AppSettings:
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
    """슬래시 핸들러에 넘길 discord.Interaction 목 (handlers 테스트와 동일 구조)."""
    user = MagicMock(name="User")
    user.id = 111
    user.display_name = "tester"
    user.roles = []
    user.send = AsyncMock()

    guild = MagicMock(name="Guild")
    guild.id = 222
    guild.name = "test-guild"

    channel = MagicMock(name="Channel")
    channel.id = 333
    channel.send = AsyncMock()

    response = MagicMock(name="Response")
    response.is_done = MagicMock(return_value=False)

    def _mark_done(*_a: Any, **_k: Any) -> None:
        response.is_done.return_value = True

    response.defer = AsyncMock(side_effect=_mark_done)
    response.send_message = AsyncMock(side_effect=_mark_done)

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
    inter.created_at = datetime(2026, 5, 30, tzinfo=timezone.utc)
    inter.edit_original_response = AsyncMock()
    return inter


class _HandlerCase(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self) -> None:
        reset_cooldowns()
        summarize_cache.clear()
        clear_translation_cache()
        self._tmp = tempfile.TemporaryDirectory()
        db_path = Path(self._tmp.name) / "assistant.db"
        self.database_url = f"sqlite:///{db_path}"
        self.settings = _make_settings(self.database_url)
        self.bot = create_bot(self.settings)
        self.store = ConfigStore(
            self.database_url,
            default_model="test-model",
            default_summary_limit=10,
            default_language="ko",
        )
        await self.store.initialize()

    async def asyncTearDown(self) -> None:
        await self.store.close()
        self._tmp.cleanup()
        reset_cooldowns()
        summarize_cache.clear()
        clear_translation_cache()

    def _callback(self, name: str):
        cmd = self.bot.tree.get_command(name)
        assert cmd is not None, f"command not found: {name}"
        return cmd.callback

    def _patch_llm(self, llm: Any):
        return patch.object(bot_module, "_get_llm", return_value=llm)

    def _patch_transcript(self, text: str):
        return patch.object(
            bot_module, "_collect_transcript", AsyncMock(return_value=text)
        )

    async def _by_command(self) -> dict[str, int]:
        stats = await self.store.get_stats(222)
        return {r["command"]: r["count"] for r in stats["by_command"]}


# ---------------------------------------------------------------------------
# /summarize — since · thread · cache · 권한 · 빈채널 · 토큰버짓
# ---------------------------------------------------------------------------
class SummarizeTest(_HandlerCase):
    async def test_since_filter_skips_cache_and_passes_after(self) -> None:
        """since 지정 시 cache 를 건너뛰고 _collect_transcript 에 after 가 전달된다."""
        inter = _make_interaction()
        llm = _FakeLLM("기간 요약")
        collect = AsyncMock(return_value="a: 어제 회의\nb: 결정함")
        with self._patch_llm(llm), patch.object(
            bot_module, "_collect_transcript", collect
        ):
            await self._callback("summarize")(inter, limit=5, since="1h")

        inter.response.defer.assert_awaited_once()
        # since 가 주어지면 _collect_transcript 가 after(datetime)와 함께 호출된다.
        self.assertIsNotNone(collect.await_args.kwargs.get("after"))
        sent = inter.followup.send.await_args.args[0]
        self.assertIn("기간 요약", sent)
        # since 라벨이 헤더에 반영된다(since 경로의 since_label 분기).
        self.assertIn("since", sent)
        self.assertEqual(len(llm.generate_calls), 1)

    async def test_thread_delivery_success_posts_to_thread(self) -> None:
        """thread=True 이고 스레드 생성 성공 시 followup 안내 + usage ok 기록.

        _deliver_summary_to_thread 는 create_bot 내부 클로저라 모듈 패치가 불가하다.
        대신 채널을 TextChannel spec 으로 만들어 isinstance 체크를 통과시키고,
        create_thread 가 성공하도록 둔다(_send_channel_chunks 는 모듈 패치로 차단).
        """
        import discord

        inter = _make_interaction()
        ch = MagicMock(spec=discord.TextChannel)
        ch.id = 333
        thread_obj = MagicMock(name="Thread")
        ch.create_thread = AsyncMock(return_value=thread_obj)
        inter.channel = ch
        llm = _FakeLLM("스레드 요약")
        with self._patch_llm(llm), self._patch_transcript("a: hi\nb: yo"), patch.object(
            bot_module, "_send_channel_chunks", AsyncMock()
        ):
            await self._callback("summarize")(inter, limit=5, thread=True)

        ch.create_thread.assert_awaited_once()
        # 스레드 게시 성공 안내가 followup 으로 전송된다.
        notice = inter.followup.send.await_args.args[0]
        self.assertIn("새 스레드", notice)
        by = await self._by_command()
        self.assertEqual(by.get("summarize"), 1)

    async def test_thread_delivery_failure_falls_back_to_channel(self) -> None:
        """thread=True 이나 스레드를 못 만들면(일반 채널 아님) 채널 폴백 안내 후 본문 전송.

        기본 MagicMock 채널은 discord.TextChannel 인스턴스가 아니므로
        _deliver_summary_to_thread 가 자연스럽게 False 를 돌려준다(폴백 경로).
        """
        inter = _make_interaction()
        llm = _FakeLLM("폴백 요약")
        with self._patch_llm(llm), self._patch_transcript("a: hi"):
            await self._callback("summarize")(inter, limit=5, thread=True)

        # 폴백 안내 메시지 + 본문이 followup 들로 나간다.
        texts = [c.args[0] for c in inter.followup.send.await_args_list if c.args]
        self.assertTrue(any("권한이 없어" in t for t in texts))
        self.assertTrue(any("폴백 요약" in t for t in texts))

    async def test_cache_hit_serves_cached_without_llm(self) -> None:
        """캐시가 있으면(limit/since 미지정) LLM 없이 캐시 헤더+본문을 보낸다."""
        # 첫 호출(기본 옵션)로 캐시를 채운다.
        inter1 = _make_interaction()
        llm1 = _FakeLLM("최초 요약 본문")
        with self._patch_llm(llm1), self._patch_transcript("a: 본문 대화"):
            await self._callback("summarize")(inter1)
        self.assertEqual(len(llm1.generate_calls), 1)

        reset_cooldowns()  # 쿨다운만 풀고 캐시는 유지.
        inter2 = _make_interaction()
        llm2 = _FakeLLM("두번째 (호출되면 안됨)")
        with self._patch_llm(llm2), self._patch_transcript("a: 새 대화"):
            await self._callback("summarize")(inter2)

        # 캐시 적중 → LLM 미호출, 캐시 헤더 안내가 본문에 포함.
        self.assertEqual(len(llm2.generate_calls), 0)
        sent = inter2.followup.send.await_args.args[0]
        self.assertIn("최초 요약 본문", sent)

    async def test_token_budget_exceeded_blocks(self) -> None:
        """일일 토큰 상한(0)을 두면 LLM 호출 전 UserFacingError 로 차단된다."""
        await self.store.set_daily_token_budget(222, 0)
        inter = _make_interaction()
        inter.response.is_done = MagicMock(return_value=True)  # defer 이후 상태.
        llm = _FakeLLM()
        with self._patch_llm(llm), self._patch_transcript("a: hi"):
            await self._callback("summarize")(inter, limit=5)

        # 토큰 버짓 초과 → LLM 미호출 + 오류 임베드.
        self.assertEqual(len(llm.generate_calls), 0)
        embed = inter.followup.send.await_args.kwargs.get("embed")
        self.assertIsNotNone(embed)
        self.assertIn("한도를 초과", embed.description)
        stats = await self.store.get_stats(222)
        self.assertEqual(stats["error_rate"], 100.0)

    async def test_empty_channel_raises_guidance(self) -> None:
        """빈 트랜스크립트 → 안내 오류 임베드, error 기록."""
        inter = _make_interaction()
        inter.response.is_done = MagicMock(return_value=True)
        llm = _FakeLLM()
        with self._patch_llm(llm), self._patch_transcript(""):
            await self._callback("summarize")(inter, limit=5)

        embed = inter.followup.send.await_args.kwargs.get("embed")
        self.assertIsNotNone(embed)
        self.assertIn("요약할 메시지가 없어요", embed.description)
        self.assertEqual(len(llm.generate_calls), 0)


# ---------------------------------------------------------------------------
# /ask — follow-up · search · 근거(질문 표시) · 에러
# ---------------------------------------------------------------------------
class AskTest(_HandlerCase):
    async def test_question_and_answer_shown_with_followup_view(self) -> None:
        """본문에 질문+답이 함께 표시되고 FollowUpView 가 첨부된다."""
        inter = _make_interaction()
        llm = _FakeLLM("핵심은 X 였습니다.")
        with self._patch_llm(llm), self._patch_transcript("a: 회의록"):
            await self._callback("ask")(inter, question="결론은?", limit=5)

        sent = inter.followup.send.await_args
        body = sent.args[0]
        self.assertIn("결론은?", body)
        self.assertIn("핵심은 X 였습니다.", body)
        # FollowUpView 가 view 로 전달되고 wait=True 로 메시지 핸들을 받는다.
        self.assertIsNotNone(sent.kwargs.get("view"))
        self.assertTrue(sent.kwargs.get("wait"))
        by = await self._by_command()
        self.assertEqual(by.get("ask"), 1)

    async def test_search_uses_generate_with_tools(self) -> None:
        """search=True 면 generate_with_tools(툴 루프)로 채널 검색 경로를 탄다."""
        inter = _make_interaction()
        llm = _FakeLLM("검색 기반 답변")
        with self._patch_llm(llm), self._patch_transcript("a: hi"):
            await self._callback("ask")(
                inter, question="언제였지?", limit=5, search=True
            )

        self.assertEqual(len(llm.tool_calls), 1)
        self.assertEqual(len(llm.generate_calls), 0)
        body = inter.followup.send.await_args.args[0]
        self.assertIn("검색 기반 답변", body)

    async def test_custom_ask_prompt_substituted(self) -> None:
        """custom_ask_prompt 가 {transcript}/{question} 치환 후 LLM 에 전달된다.

        #89/#116: 커스텀 경로도 신뢰 불가 입력을 _wrap_untrusted 로 감싸고
        _INJECTION_GUARD 를 prepend 한다(인젝션 방어선 유지). 따라서 본문이 그대로
        들어가되 구분자 태그로 래핑되고, 보안 지침이 앞에 붙는다.
        """
        await self.store.set_custom_prompt(222, "ask", "맥락:{transcript} 질문:{question}")
        inter = _make_interaction()
        llm = _FakeLLM("커스텀 답")
        with self._patch_llm(llm), self._patch_transcript("대화내용"):
            await self._callback("ask")(inter, question="무엇?", limit=5)

        self.assertEqual(len(llm.generate_calls), 1)
        prompt = llm.generate_calls[0]
        # 커스텀 프롬프트 본문/치환 내용이 들어가되 신뢰 불가 입력은 래핑된다.
        self.assertIn("맥락:", prompt)
        self.assertIn("질문:", prompt)
        self.assertIn("대화내용", prompt)
        self.assertIn("무엇?", prompt)
        # 인젝션 방어선이 적용됐다: 보안 지침 prepend + 구분자 래핑.
        self.assertIn("untrusted DATA", prompt)
        self.assertIn("<transcript>", prompt)
        self.assertIn("<question>", prompt)

    async def test_long_answer_uses_preview_and_long_view(self) -> None:
        """답이 매우 길면 프리뷰 1개 + (DM 받기 버튼 병합) 후속질문 뷰로 보낸다."""
        inter = _make_interaction()
        big = "가" * 5000  # MAX_DISCORD_MESSAGE_CHARS(1900) 훨씬 초과.
        llm = _FakeLLM(big)
        with self._patch_llm(llm), self._patch_transcript("a: hi"):
            await self._callback("ask")(inter, question="길게?", limit=5)

        sent = inter.followup.send.await_args
        preview = sent.args[0]
        # 프리뷰는 잘려서 말줄임표로 끝난다.
        self.assertTrue(preview.endswith("…"))
        self.assertLessEqual(len(preview), bot_module.MAX_DISCORD_MESSAGE_CHARS)
        self.assertIsNotNone(sent.kwargs.get("view"))

    async def test_llm_error_records_error(self) -> None:
        """LLMError → 오류 임베드 + error 기록(error_rate 100%)."""
        inter = _make_interaction()
        inter.response.is_done = MagicMock(return_value=True)
        llm = _RaisingLLM(LLMError("일시 오류"))
        with self._patch_llm(llm), self._patch_transcript("a: hi"):
            await self._callback("ask")(inter, question="q", limit=5)

        embed = inter.followup.send.await_args.kwargs.get("embed")
        self.assertIsNotNone(embed)
        stats = await self.store.get_stats(222)
        self.assertEqual(stats["error_rate"], 100.0)

    async def test_disallowed_role_blocks(self) -> None:
        """allowed_role 설정 시 역할 없는 사용자는 권한 오류로 차단(LLM 미호출)."""
        await self.store.set_allowed_role(222, role_id=777)
        inter = _make_interaction()
        inter.user.roles = []
        inter.response.is_done = MagicMock(return_value=True)
        llm = _FakeLLM()
        with self._patch_llm(llm), self._patch_transcript("a: hi"):
            await self._callback("ask")(inter, question="q", limit=5)

        embed = inter.followup.send.await_args.kwargs.get("embed")
        self.assertIsNotNone(embed)
        self.assertIn("권한이 없어요", embed.description)
        self.assertEqual(len(llm.generate_calls), 0)

    async def test_retryable_llm_error_clears_cooldown(self) -> None:
        """#3: 재시도 가능한 LLM 오류 후에는 진입 시 기록한 쿨다운을 롤백해야 한다.

        그래야 오류 임베드에 붙는 '재시도' 버튼이 쿨다운 안내만 띄우지 않고 실제로
        다시 실행된다. status_code 없는 LLMError 는 재시도 대상이다.
        """
        inter = _make_interaction()
        inter.response.is_done = MagicMock(return_value=True)
        llm = _RaisingLLM(LLMError("일시 오류"))
        with self._patch_llm(llm), self._patch_transcript("a: hi"):
            await self._callback("ask")(inter, question="q", limit=5)

        # 진입 시 기록된 (guild=222, user=111) 쿨다운이 롤백돼 재시도가 막히지 않는다.
        self.assertNotIn((222, 111), bot_module._cooldowns)


# ---------------------------------------------------------------------------
# /translate — 정상 · 캐시 · 쿨다운 · 에러
# ---------------------------------------------------------------------------
class TranslateTest(_HandlerCase):
    async def test_translate_records_and_caches(self) -> None:
        """정상 번역: 임베드 전송 + usage ok + 결과 캐시 저장."""
        inter = _make_interaction()
        llm = _FakeLLM("hello")
        with self._patch_llm(llm):
            await self._callback("translate")(inter, text="안녕", target_language="en")

        inter.response.defer.assert_awaited_once()
        embed = inter.followup.send.await_args.kwargs.get("embed")
        self.assertIsNotNone(embed)
        # 원문/번역 필드가 모두 들어간다.
        field_names = [f.name for f in embed.fields]
        self.assertTrue(any("원문" in n for n in field_names))
        self.assertEqual(len(llm.generate_calls), 1)
        # 캐시에 저장됐다.
        self.assertEqual(get_translation("안녕", "en"), "hello")
        by = await self._by_command()
        self.assertEqual(by.get("translate"), 1)

    async def test_translate_cache_hit_skips_llm(self) -> None:
        """캐시 적중 시 LLM 호출 없이 *(캐시)* 표시 임베드를 보낸다."""
        inter1 = _make_interaction()
        llm1 = _FakeLLM("bonjour")
        with self._patch_llm(llm1):
            await self._callback("translate")(inter1, text="안녕", target_language="fr")
        self.assertEqual(len(llm1.generate_calls), 1)

        reset_cooldowns()
        inter2 = _make_interaction()
        llm2 = _FakeLLM("호출되면 안됨")
        with self._patch_llm(llm2):
            await self._callback("translate")(inter2, text="안녕", target_language="fr")

        self.assertEqual(len(llm2.generate_calls), 0)
        embed = inter2.followup.send.await_args.kwargs.get("embed")
        self.assertIsNotNone(embed)
        self.assertTrue(any("캐시" in f.name for f in embed.fields))

    async def test_translate_cooldown_blocks_second(self) -> None:
        """연속 /translate 의 두 번째는 쿨다운으로 차단(send_message)된다."""
        llm = _FakeLLM("x")
        with self._patch_llm(llm):
            first = _make_interaction()
            await self._callback("translate")(first, text="a", target_language="en")
            second = _make_interaction()
            await self._callback("translate")(second, text="b", target_language="en")

        second.response.send_message.assert_awaited_once()
        self.assertIn(
            "초 후에 다시 시도", second.response.send_message.await_args.args[0]
        )
        second.response.defer.assert_not_awaited()
        self.assertEqual(len(llm.generate_calls), 1)

    async def test_translate_llm_error_records_error(self) -> None:
        """LLMError → 경고 청크 전송 + error 기록."""
        inter = _make_interaction()
        inter.response.is_done = MagicMock(return_value=True)
        llm = _RaisingLLM(LLMError("번역 실패"))
        with self._patch_llm(llm):
            await self._callback("translate")(inter, text="x", target_language="en")

        inter.followup.send.assert_awaited()
        stats = await self.store.get_stats(222)
        self.assertEqual(stats["error_rate"], 100.0)


# ---------------------------------------------------------------------------
# /search — 매칭 요약 · 무매칭 · 권한없음 · 에러
# ---------------------------------------------------------------------------
def _fake_history(messages: list[Any]):
    """interaction.channel.history(...) 가 돌려줄 async iterator 를 만든다."""

    def _factory(*_a: Any, **_k: Any):
        async def _gen():
            for m in messages:
                yield m

        return _gen()

    return _factory


def _msg(content: str, author: str = "u", hour: int = 12, minute: int = 0) -> MagicMock:
    m = MagicMock()
    m.content = content
    m.author = MagicMock()
    m.author.display_name = author
    m.created_at = datetime(2026, 5, 30, hour, minute, tzinfo=timezone.utc)
    return m


class SearchTest(_HandlerCase):
    async def test_search_matches_summarized(self) -> None:
        """키워드 일치 메시지를 모아 LLM 요약 임베드를 전송하고 usage 기록."""
        inter = _make_interaction()
        inter.channel.history = _fake_history(
            [_msg("배포 일정 논의"), _msg("점심 메뉴"), _msg("배포 완료")]
        )
        llm = _FakeLLM("배포 관련 요약")
        with self._patch_llm(llm):
            await self._callback("search")(inter, query="배포", limit=100)

        embed = inter.followup.send.await_args.kwargs.get("embed")
        self.assertIsNotNone(embed)
        self.assertIn("배포", embed.title)
        # 일치 2건 → '2개' 가 필드 값에 들어간다.
        match_values = [f.value for f in embed.fields]
        self.assertTrue(any("2개" in v for v in match_values))
        self.assertEqual(len(llm.generate_calls), 1)
        by = await self._by_command()
        self.assertEqual(by.get("search"), 1)

    async def test_search_no_match_sends_notice_without_llm(self) -> None:
        """일치 없음 → 안내 메시지, LLM 미호출, usage 기록 없음."""
        inter = _make_interaction()
        inter.channel.history = _fake_history([_msg("hello"), _msg("world")])
        llm = _FakeLLM("호출 안됨")
        with self._patch_llm(llm):
            await self._callback("search")(inter, query="없는키워드", limit=50)

        self.assertEqual(len(llm.generate_calls), 0)
        text = inter.followup.send.await_args.args[0]
        self.assertIn("검색 결과 없음", text)

    async def test_search_forbidden_history_records_error(self) -> None:
        """history 가 discord.Forbidden 이면 권한 안내 + error 기록."""
        import discord

        def _raise_factory(*_a: Any, **_k: Any):
            async def _gen():
                raise discord.Forbidden(MagicMock(status=403), "no perm")
                yield  # pragma: no cover

            return _gen()

        inter = _make_interaction()
        inter.channel.history = _raise_factory
        llm = _FakeLLM()
        with self._patch_llm(llm):
            await self._callback("search")(inter, query="x")

        self.assertEqual(len(llm.generate_calls), 0)
        text = inter.followup.send.await_args.args[0]
        self.assertIn("권한이 없어요", text)
        stats = await self.store.get_stats(222)
        self.assertEqual(stats["error_rate"], 100.0)

    async def test_search_llm_error_records_error(self) -> None:
        """일치는 있으나 LLM 이 실패하면 경고 전송 + error 기록."""
        inter = _make_interaction()
        inter.channel.history = _fake_history([_msg("배포 됨")])
        llm = _RaisingLLM(LLMError("요약 실패"))
        with self._patch_llm(llm):
            await self._callback("search")(inter, query="배포")

        inter.followup.send.assert_awaited()
        stats = await self.store.get_stats(222)
        self.assertEqual(stats["error_rate"], 100.0)

    async def test_search_none_channel_friendly_error(self) -> None:
        """#6: interaction.channel 이 None 이면 AttributeError 로 침묵 실패하지 않고
        친절 안내 + error 기록한다."""
        inter = _make_interaction()
        inter.channel = None
        llm = _FakeLLM("호출 안됨")
        with self._patch_llm(llm):
            await self._callback("search")(inter, query="배포")

        self.assertEqual(len(llm.generate_calls), 0)
        text = inter.followup.send.await_args.args[0]
        self.assertIn("채널에서만 사용할 수 있어요", text)
        stats = await self.store.get_stats(222)
        self.assertEqual(stats["error_rate"], 100.0)

    async def test_search_long_summary_attaches_overflow_view(self) -> None:
        """#8: 요약이 1024 자를 넘으면 'DM 으로 전체 받기' 버튼(LongResponseView)을 붙인다."""
        inter = _make_interaction()
        inter.channel.history = _fake_history([_msg("배포 됨")])
        llm = _FakeLLM("요" * 2000)  # 임베드 필드 한도(1024) 초과.
        with self._patch_llm(llm):
            await self._callback("search")(inter, query="배포")

        view = inter.followup.send.await_args.kwargs.get("view")
        self.assertIsInstance(view, bot_module.LongResponseView)


# ---------------------------------------------------------------------------
# /digest — 정상 · 멀티청크 · 권한 · 빈기간 · 쿨다운
# ---------------------------------------------------------------------------
class DigestTest(_HandlerCase):
    async def test_digest_default_period_summarizes(self) -> None:
        """기본 1d 로 트랜스크립트를 모아 정리 결과를 보내고 usage 기록."""
        inter = _make_interaction()
        llm = _FakeLLM("핵심·결정·액션 정리")
        with self._patch_llm(llm), self._patch_transcript("a: 오늘 한 일"):
            await self._callback("digest")(inter)

        inter.response.defer.assert_awaited_once()
        first = inter.followup.send.await_args_list[0].args[0]
        self.assertIn("오늘의 정리", first)
        self.assertIn("핵심·결정·액션 정리", first)
        self.assertEqual(len(llm.generate_calls), 1)
        by = await self._by_command()
        self.assertEqual(by.get("digest"), 1)

    async def test_digest_long_answer_sends_multiple_chunks(self) -> None:
        """답이 매우 길면 _split_discord_text 로 여러 청크를 순차 전송한다."""
        inter = _make_interaction()
        # 줄바꿈으로 분리되도록 긴 본문을 여러 줄로 구성(청크가 2개 이상 나오게).
        long_answer = "\n".join("정리 줄 " + str(i) + "내용" * 50 for i in range(60))
        llm = _FakeLLM(long_answer)
        with self._patch_llm(llm), self._patch_transcript("a: 많은 대화"):
            await self._callback("digest")(inter, since="6h")

        # 첫 메시지(wait=True) + 추가 청크들 → 2번 이상 followup.send.
        self.assertGreaterEqual(inter.followup.send.await_count, 2)
        # 첫 청크만 wait=True 핸들을 받는다.
        self.assertTrue(
            inter.followup.send.await_args_list[0].kwargs.get("wait")
        )

    async def test_digest_empty_period_raises(self) -> None:
        """기간 내 메시지가 없으면 안내 경고 + error 기록."""
        inter = _make_interaction()
        inter.response.is_done = MagicMock(return_value=True)
        llm = _FakeLLM()
        with self._patch_llm(llm), self._patch_transcript(""):
            await self._callback("digest")(inter, since="1d")

        self.assertEqual(len(llm.generate_calls), 0)
        inter.followup.send.assert_awaited()
        text = inter.followup.send.await_args.args[0]
        self.assertIn("정리할 메시지가 없어요", text)
        stats = await self.store.get_stats(222)
        self.assertEqual(stats["error_rate"], 100.0)

    async def test_digest_disallowed_role_blocks(self) -> None:
        """allowed_role 설정 시 역할 없는 사용자는 권한 오류로 차단(LLM 미호출)."""
        await self.store.set_allowed_role(222, role_id=888)
        inter = _make_interaction()
        inter.user.roles = []
        inter.response.is_done = MagicMock(return_value=True)
        llm = _FakeLLM()
        with self._patch_llm(llm), self._patch_transcript("a: hi"):
            await self._callback("digest")(inter, since="1h")

        self.assertEqual(len(llm.generate_calls), 0)
        text = inter.followup.send.await_args.args[0]
        self.assertIn("권한이 없어요", text)
        stats = await self.store.get_stats(222)
        self.assertEqual(stats["error_rate"], 100.0)

    async def test_digest_cooldown_blocks_second(self) -> None:
        """연속 /digest 의 두 번째는 쿨다운으로 차단(send_message)된다."""
        llm = _FakeLLM("정리")
        with self._patch_llm(llm), self._patch_transcript("a: hi"):
            first = _make_interaction()
            await self._callback("digest")(first, since="1h")
            second = _make_interaction()
            await self._callback("digest")(second, since="1h")

        second.response.send_message.assert_awaited_once()
        self.assertIn(
            "초 후에 다시 시도", second.response.send_message.await_args.args[0]
        )
        second.response.defer.assert_not_awaited()
        self.assertEqual(len(llm.generate_calls), 1)

    async def test_digest_invalid_since_raises(self) -> None:
        """잘못된 since 형식이면 _parse_since 가 UserFacingError → 경고 + error 기록."""
        inter = _make_interaction()
        inter.response.is_done = MagicMock(return_value=True)
        llm = _FakeLLM()
        with self._patch_llm(llm), self._patch_transcript("a: hi"):
            await self._callback("digest")(inter, since="garbage")

        self.assertEqual(len(llm.generate_calls), 0)
        inter.followup.send.assert_awaited()
        stats = await self.store.get_stats(222)
        self.assertEqual(stats["error_rate"], 100.0)


if __name__ == "__main__":  # pragma: no cover
    unittest.main()
