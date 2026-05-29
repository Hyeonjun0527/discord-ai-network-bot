"""llm.py 미커버 경로 커버 테스트.

대상(미커버 라인): 390, 432-453, 467, 535-611, 738-794, 846-859, 897-944,
1114-1127, 1174, 1192, 1203-1221, 1337-1349, 1387-1415, 1504.

즉:
- BaseLLMClient.generate_stream 기본 폴백(390)
- _iter_in_thread (블로킹 동기 제너레이터 → 비동기, 정상/예외 전파) (432-453)
- parse_generate_response 의 비-str response → OllamaError (467)
- Ollama _generate_sync 의 HTTP/URL/Timeout 에러 매핑 + generate_stream/_stream_sync
  (정상 스트림/에러/JSON 무시/done) (535-611)
- OpenAI generate_stream/_stream_sync (SSE 델타/DONE/에러) (738-794)
- OpenAI _chat_sync 의 에러 매핑 (846-859)
- OpenAI generate_with_tools: 비정상 응답 shape, 반복 상한 도달 후 최종 답변 재요청 (897-944)
- Anthropic _messages_sync 에러 매핑 (1114-1127)
- Anthropic generate_with_tools: content 비-list → 에러(1174), tool_use input 비-dict(1192),
  반복 상한 도달 후 최종 답변(1203-1221)
- _parse_gemini_payload: blockReason 차단/비정상 shape/빈 응답 (1337-1349)
- OllamaManager._list_sync 예외→[], pull_model(bin 없음/성공/실패), is_available (1387-1415)
- supports_vision 알 수 없는 제공자(Gemini) → False (1504)

네트워크/SDK 호출은 전부 monkeypatch(mock) 으로 가짜 응답/스트림을 주입한다.
실제 외부 호출 없음. tests/test_llm.py 패턴을 모방한다.
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
    BaseLLMClient,
    CircuitBreaker,
    CircuitBreakerOpenError,
    GeminiError,
    LLMError,
    OllamaClient,
    OllamaError,
    OllamaManager,
    OpenAIClient,
    OpenAIError,
    TokenUsage,
    ToolSpec,
    _add_usage,
    _coerce_token_count,
    _iter_in_thread,
    _parse_gemini_payload,
    _with_circuit_breaker,
    parse_generate_response,
    supports_vision,
)
from discord_assistant.models import LLMProvider, OllamaModel

_PATCH = "discord_assistant.llm.request.urlopen"


# ---------------------------------------------------------------------------
# 공통 가짜 HTTP 응답 (비스트리밍 / 스트리밍 둘 다 지원)
# ---------------------------------------------------------------------------


class _FakeResponse:
    """urlopen with-블록용 컨텍스트 매니저 가짜 응답.

    - 비스트리밍: ``response.read()`` 가 body 를 돌려준다.
    - 스트리밍: ``for raw_line in response`` 로 줄 단위(bytes) 이터레이션을 지원한다.
      _stream_sync 가 response 를 직접 순회하므로 __iter__ 를 구현한다.
    """

    def __init__(self, body: bytes = b"", *, lines: list[bytes] | None = None) -> None:
        self._body = body
        self._lines = lines or []

    def __enter__(self) -> "_FakeResponse":
        return self

    def __exit__(self, *exc_info: object) -> None:
        return None

    def read(self) -> bytes:
        return self._body

    def __iter__(self):  # type: ignore[no-untyped-def]
        return iter(self._lines)


def _json_response(payload: dict) -> _FakeResponse:
    return _FakeResponse(json.dumps(payload).encode("utf-8"))


def _stream_response(lines: list[str]) -> _FakeResponse:
    """줄 문자열 리스트를 bytes 라인으로 갖는 스트리밍 가짜 응답을 만든다."""
    return _FakeResponse(lines=[(ln + "\n").encode("utf-8") for ln in lines])


def _http_error(code: int, body: str = "secret server detail") -> urllib_error.HTTPError:
    return urllib_error.HTTPError(
        url="https://example.test",
        code=code,
        msg="error",
        hdrs=None,  # type: ignore[arg-type]
        fp=io.BytesIO(body.encode("utf-8")),
    )


async def _collect(aiter) -> list:  # type: ignore[no-untyped-def]
    """비동기 이터레이터의 모든 청크를 리스트로 모은다."""
    out: list = []
    async for chunk in aiter:
        out.append(chunk)
    return out


# ---------------------------------------------------------------------------
# 390: BaseLLMClient.generate_stream 기본 폴백 (generate 결과 단일 청크 yield)
# ---------------------------------------------------------------------------


class _DummyClient(BaseLLMClient):
    """generate 만 구현한 최소 클라이언트. generate_stream 은 기본 폴백을 쓴다."""

    def __init__(self, text: str) -> None:
        self._text = text
        self.seen_model: str | None = "unset"

    async def generate(self, prompt, *, model=None, images=None):  # type: ignore[no-untyped-def]
        self.seen_model = model
        return f"{self._text}:{prompt}"


class BaseGenerateStreamFallbackTest(unittest.TestCase):
    def test_default_stream_yields_single_chunk_from_generate(self) -> None:
        client = _DummyClient("R")

        chunks = asyncio.run(_collect(client.generate_stream("hi", model="m1")))

        # 폴백은 generate() 결과 전체를 단 한 번 yield 한다.
        self.assertEqual(chunks, ["R:hi"])
        # model 인자가 generate 로 그대로 전달된다.
        self.assertEqual(client.seen_model, "m1")

    def test_default_generate_with_tools_falls_back_to_generate(self) -> None:
        # generate_with_tools 기본 구현도 generate 로 폴백한다(미지원 제공자 경로).
        client = _DummyClient("T")

        async def runner(name, args):  # type: ignore[no-untyped-def]  # pragma: no cover
            return ""

        result = asyncio.run(
            client.generate_with_tools(
                "q", tools=[ToolSpec("x", "d")], tool_runner=runner, model="mZ"
            )
        )
        self.assertEqual(result, "T:q")
        self.assertEqual(client.seen_model, "mZ")


# ---------------------------------------------------------------------------
# 432-453: _iter_in_thread — 블로킹 동기 제너레이터를 비동기로 변환
# ---------------------------------------------------------------------------


class IterInThreadTest(unittest.TestCase):
    def test_yields_all_chunks_in_order(self) -> None:
        def make_iter():  # type: ignore[no-untyped-def]
            yield "a"
            yield "b"
            yield "c"

        chunks = asyncio.run(_collect(_iter_in_thread(make_iter)))
        self.assertEqual(chunks, ["a", "b", "c"])

    def test_empty_iterator_yields_nothing(self) -> None:
        def make_iter():  # type: ignore[no-untyped-def]
            return iter(())

        chunks = asyncio.run(_collect(_iter_in_thread(make_iter)))
        self.assertEqual(chunks, [])

    def test_exception_in_worker_is_propagated_to_caller(self) -> None:
        # 워커 스레드에서 발생한 예외는 큐를 통해 메인 루프로 전달되어 다시 던져진다.
        def make_iter():  # type: ignore[no-untyped-def]
            yield "first"
            raise RuntimeError("boom in worker")

        async def run() -> list:
            collected: list = []
            with self.assertRaises(RuntimeError) as cm:
                async for chunk in _iter_in_thread(make_iter):
                    collected.append(chunk)
            self.assertIn("boom in worker", str(cm.exception))
            return collected

        collected = asyncio.run(run())
        # 예외 직전에 yield 된 청크는 정상 수신된다.
        self.assertEqual(collected, ["first"])

    def test_early_break_does_not_hang_with_full_queue(self) -> None:
        # 소비자가 일찍 break 하면 워커가 가득 찬 바운드 큐(maxsize=64)의 put 에서
        # 막힐 수 있다. finally 가 큐를 drain 해 워커가 진행·종료되어야 하며,
        # 코루틴이 hang 되지 않아야 한다(#53 회귀 방지).
        produced = 200  # 큐 용량(64)을 넘겨 put 블로킹을 유발한다.

        def make_iter():  # type: ignore[no-untyped-def]
            for i in range(produced):
                yield str(i)

        async def run() -> list:
            collected: list = []
            async for chunk in _iter_in_thread(make_iter):
                collected.append(chunk)
                if len(collected) >= 3:
                    break  # 소비를 조기 중단 → 워커는 가득 찬 큐에 put 시도.
            return collected

        # 무한 hang 방지를 위해 전체 실행에 타임아웃을 건다.
        async def guarded() -> list:
            return await asyncio.wait_for(run(), timeout=10.0)

        collected = asyncio.run(guarded())
        self.assertEqual(collected, ["0", "1", "2"])


# ---------------------------------------------------------------------------
# 467: parse_generate_response — response 가 str 이 아니면 OllamaError
# ---------------------------------------------------------------------------


class ParseGenerateResponseTest(unittest.TestCase):
    def test_non_string_response_raises(self) -> None:
        with self.assertRaises(OllamaError):
            parse_generate_response({"response": 123})

    def test_missing_response_raises(self) -> None:
        with self.assertRaises(OllamaError):
            parse_generate_response({"done": True})


# ---------------------------------------------------------------------------
# 535-550: Ollama _generate_sync 에러 매핑 (HTTP/URL/Timeout/JSON)
# ---------------------------------------------------------------------------


class OllamaGenerateSyncErrorTest(unittest.TestCase):
    def setUp(self) -> None:
        self.client = OllamaClient(base_url="http://x", default_model="llama3.1:8b")

    def test_http_error_maps_to_ollama_error_with_status(self) -> None:
        with mock.patch(_PATCH, side_effect=_http_error(503, body="secret-detail")):
            with self.assertRaises(OllamaError) as cm:
                self.client._generate_sync("hi", "llama3.1:8b")
        self.assertEqual(cm.exception.status_code, 503)
        # 응답 원문은 사용자 메시지에 노출되지 않는다.
        self.assertNotIn("secret-detail", str(cm.exception))

    def test_url_error_maps_to_ollama_error_no_status(self) -> None:
        with mock.patch(_PATCH, side_effect=urllib_error.URLError("conn refused")):
            with self.assertRaises(OllamaError) as cm:
                self.client._generate_sync("hi", "llama3.1:8b")
        self.assertIsNone(cm.exception.status_code)
        self.assertIn("ollama serve", str(cm.exception))

    def test_timeout_maps_to_ollama_error(self) -> None:
        with mock.patch(_PATCH, side_effect=TimeoutError("slow")):
            with self.assertRaises(OllamaError) as cm:
                self.client._generate_sync("hi", "llama3.1:8b")
        self.assertIn("시간", str(cm.exception))

    def test_invalid_json_maps_to_ollama_error(self) -> None:
        with mock.patch(_PATCH, return_value=_FakeResponse(b"not-json{{")):
            with self.assertRaises(OllamaError):
                self.client._generate_sync("hi", "llama3.1:8b")

    def test_success_records_usage(self) -> None:
        payload = {"response": " ok ", "prompt_eval_count": 7, "eval_count": 3}
        with mock.patch(_PATCH, return_value=_json_response(payload)):
            result = self.client._generate_sync("hi", "llama3.1:8b")
        self.assertEqual(result, "ok")
        self.assertEqual(self.client.last_usage, TokenUsage(7, 3))


# ---------------------------------------------------------------------------
# 552-613: Ollama generate_stream / _stream_sync (정상 스트림 / 분기 / 에러)
# ---------------------------------------------------------------------------


class OllamaStreamTest(unittest.TestCase):
    def setUp(self) -> None:
        self.client = OllamaClient(base_url="http://x", default_model="llama3.1:8b")

    def test_stream_yields_pieces_until_done(self) -> None:
        lines = [
            json.dumps({"response": "안", "done": False}),
            "",  # 빈 줄은 건너뛴다
            "not-json-line",  # JSON 파싱 실패 줄은 건너뛴다
            json.dumps({"response": "녕", "done": False}),
            json.dumps({"response": "", "done": False}),  # 빈 piece 는 yield 안 함
            json.dumps({"response": "!", "done": True}),  # done → 이 piece yield 후 종료
            json.dumps({"response": "무시", "done": False}),  # done 이후 줄은 처리 안 됨
        ]
        with mock.patch(_PATCH, return_value=_stream_response(lines)):
            chunks = asyncio.run(_collect(self.client.generate_stream("hi", model="m")))
        self.assertEqual(chunks, ["안", "녕", "!"])

    def test_stream_error_field_raises_ollama_error(self) -> None:
        lines = [json.dumps({"error": "model not found"})]
        with mock.patch(_PATCH, return_value=_stream_response(lines)):
            with self.assertRaises(OllamaError) as cm:
                asyncio.run(_collect(self.client.generate_stream("hi")))
        self.assertIn("model not found", str(cm.exception))

    def test_stream_http_error_raises_with_status(self) -> None:
        with mock.patch(_PATCH, side_effect=_http_error(500)):
            with self.assertRaises(OllamaError) as cm:
                asyncio.run(_collect(self.client.generate_stream("hi")))
        self.assertEqual(cm.exception.status_code, 500)

    def test_stream_url_error_raises(self) -> None:
        with mock.patch(_PATCH, side_effect=urllib_error.URLError("down")):
            with self.assertRaises(OllamaError) as cm:
                asyncio.run(_collect(self.client.generate_stream("hi")))
        self.assertIn("ollama serve", str(cm.exception))

    def test_stream_timeout_raises(self) -> None:
        with mock.patch(_PATCH, side_effect=TimeoutError()):
            with self.assertRaises(OllamaError) as cm:
                asyncio.run(_collect(self.client.generate_stream("hi")))
        self.assertIn("시간", str(cm.exception))

    def test_stream_sets_last_usage_from_done_line(self) -> None:
        # #58: 스트림 경로도 done 라인의 prompt_eval_count/eval_count 를 파싱해
        # last_usage 를 채운다(비스트리밍 경로처럼 (0,0) 누락이 없어야 한다).
        lines = [
            json.dumps({"response": "hi", "done": False}),
            json.dumps(
                {"response": "!", "done": True, "prompt_eval_count": 12, "eval_count": 7}
            ),
        ]
        with mock.patch(_PATCH, return_value=_stream_response(lines)):
            chunks = asyncio.run(_collect(self.client.generate_stream("hi", model="m")))
        self.assertEqual(chunks, ["hi", "!"])
        self.assertEqual(self.client.last_usage, TokenUsage(12, 7))

    def test_stream_resets_usage_between_calls(self) -> None:
        # #58: 새 스트림 시작 시 직전 usage 가 누출되지 않도록 초기화한다.
        self.client.last_usage = TokenUsage(99, 99)
        lines = [json.dumps({"response": "x", "done": True})]  # usage 없음
        with mock.patch(_PATCH, return_value=_stream_response(lines)):
            asyncio.run(_collect(self.client.generate_stream("hi")))
        self.assertEqual(self.client.last_usage, TokenUsage(0, 0))


# ---------------------------------------------------------------------------
# 738-796: OpenAI generate_stream / _stream_sync (SSE 델타 / DONE / 에러)
# ---------------------------------------------------------------------------


def _sse(obj: dict) -> str:
    return "data: " + json.dumps(obj)


class OpenAIStreamTest(unittest.TestCase):
    def setUp(self) -> None:
        self.client = OpenAIClient(api_key="sk-x")

    def test_stream_yields_deltas_until_done(self) -> None:
        lines = [
            "",  # 빈 줄 무시
            "event: ping",  # data: 로 시작하지 않는 줄 무시
            _sse({"choices": [{"delta": {"content": "Hel"}}]}),
            _sse({"choices": [{"delta": {"content": "lo"}}]}),
            _sse({"choices": [{"delta": {}}]}),  # content 없는 델타 → 건너뜀
            "data: {bad json",  # data: 인데 JSON 깨짐 → 건너뜀
            _sse({"choices": []}),  # IndexError 경로 → 건너뜀
            "data: [DONE]",  # 종료
            _sse({"choices": [{"delta": {"content": "무시"}}]}),  # DONE 이후 줄 처리 안 됨
        ]
        with mock.patch(_PATCH, return_value=_stream_response(lines)):
            chunks = asyncio.run(_collect(self.client.generate_stream("hi", model="m")))
        self.assertEqual(chunks, ["Hel", "lo"])

    def test_stream_http_error_raises_with_status(self) -> None:
        with mock.patch(_PATCH, side_effect=_http_error(502)):
            with self.assertRaises(OpenAIError) as cm:
                asyncio.run(_collect(self.client.generate_stream("hi")))
        self.assertEqual(cm.exception.status_code, 502)

    def test_stream_url_error_raises(self) -> None:
        with mock.patch(_PATCH, side_effect=urllib_error.URLError("dns")):
            with self.assertRaises(OpenAIError) as cm:
                asyncio.run(_collect(self.client.generate_stream("hi")))
        self.assertIsNone(cm.exception.status_code)

    def test_stream_timeout_raises(self) -> None:
        with mock.patch(_PATCH, side_effect=TimeoutError()):
            with self.assertRaises(OpenAIError) as cm:
                asyncio.run(_collect(self.client.generate_stream("hi")))
        self.assertIn("시간", str(cm.exception))

    def test_stream_sets_last_usage_from_usage_chunk(self) -> None:
        # #58: include_usage 옵션으로 [DONE] 직전에 오는 usage 전용 청크(choices 빈)를
        # 파싱해 last_usage 를 채운다. 일반 델타 청크는 usage 가 없어도 영향 없어야 한다.
        lines = [
            _sse({"choices": [{"delta": {"content": "Hel"}}]}),
            _sse({"choices": [{"delta": {"content": "lo"}}]}),
            _sse({"choices": [], "usage": {"prompt_tokens": 9, "completion_tokens": 4}}),
            "data: [DONE]",
        ]
        with mock.patch(_PATCH, return_value=_stream_response(lines)):
            chunks = asyncio.run(_collect(self.client.generate_stream("hi", model="m")))
        self.assertEqual(chunks, ["Hel", "lo"])
        self.assertEqual(self.client.last_usage, TokenUsage(9, 4))

    def test_stream_resets_usage_between_calls(self) -> None:
        # #58: 새 스트림 시작 시 직전 usage 가 누출되지 않도록 초기화한다.
        self.client.last_usage = TokenUsage(50, 50)
        lines = [
            _sse({"choices": [{"delta": {"content": "x"}}]}),  # usage 없음
            "data: [DONE]",
        ]
        with mock.patch(_PATCH, return_value=_stream_response(lines)):
            asyncio.run(_collect(self.client.generate_stream("hi")))
        self.assertEqual(self.client.last_usage, TokenUsage(0, 0))


# ---------------------------------------------------------------------------
# 846-859: OpenAI _chat_sync 에러 매핑 (HTTP/URL/Timeout/JSON)
# ---------------------------------------------------------------------------


_SEARCH_TOOL = ToolSpec(
    name="search_messages",
    description="Search the channel.",
    parameters={
        "type": "object",
        "properties": {"query": {"type": "string"}},
        "required": ["query"],
    },
)


class OpenAIChatSyncErrorTest(unittest.TestCase):
    def setUp(self) -> None:
        self.client = OpenAIClient(api_key="sk-x")
        self.tools = self.client._tools_to_openai([_SEARCH_TOOL])
        self.messages = [{"role": "user", "content": "hi"}]

    def test_http_error_maps_with_status(self) -> None:
        with mock.patch(_PATCH, side_effect=_http_error(400, body="hidden")):
            with self.assertRaises(OpenAIError) as cm:
                self.client._chat_sync(self.messages, "gpt-4o", self.tools)
        self.assertEqual(cm.exception.status_code, 400)
        self.assertNotIn("hidden", str(cm.exception))

    def test_url_error_maps(self) -> None:
        with mock.patch(_PATCH, side_effect=urllib_error.URLError("x")):
            with self.assertRaises(OpenAIError) as cm:
                self.client._chat_sync(self.messages, "gpt-4o", self.tools)
        self.assertIsNone(cm.exception.status_code)

    def test_timeout_maps(self) -> None:
        with mock.patch(_PATCH, side_effect=TimeoutError()):
            with self.assertRaises(OpenAIError):
                self.client._chat_sync(self.messages, "gpt-4o", self.tools)

    def test_invalid_json_maps(self) -> None:
        with mock.patch(_PATCH, return_value=_FakeResponse(b"<garbage>")):
            with self.assertRaises(OpenAIError):
                self.client._chat_sync(self.messages, "gpt-4o", self.tools)

    def test_success_records_usage_and_returns_payload(self) -> None:
        payload = {
            "choices": [{"message": {"content": "ok"}}],
            "usage": {"prompt_tokens": 4, "completion_tokens": 5},
        }
        with mock.patch(_PATCH, return_value=_json_response(payload)):
            got = self.client._chat_sync(self.messages, "gpt-4o", self.tools)
        self.assertEqual(got, payload)
        self.assertEqual(self.client.last_usage, TokenUsage(4, 5))


# ---------------------------------------------------------------------------
# urlopen 페이크 (툴 루프 다단계 응답)
# ---------------------------------------------------------------------------


def _sequential_urlopen(payloads: list[dict]):  # type: ignore[no-untyped-def]
    sent_bodies: list = []
    remaining = list(payloads)

    def fake(req, timeout=None):  # type: ignore[no-untyped-def]
        sent_bodies.append(json.loads(req.data.decode("utf-8")))
        return _json_response(remaining.pop(0))

    return sent_bodies, fake


async def _noop_runner(name: str, args: dict) -> str:  # pragma: no cover
    return ""


# ---------------------------------------------------------------------------
# 897-944: OpenAI generate_with_tools — 비정상 shape / 반복 상한 도달 후 최종 답변
# ---------------------------------------------------------------------------


class OpenAIToolLoopExtraTest(unittest.TestCase):
    def setUp(self) -> None:
        self.client = OpenAIClient(api_key="sk-x")

    def test_unexpected_response_shape_raises(self) -> None:
        # choices 가 없으면 KeyError 경로 → OpenAIError.
        with mock.patch(_PATCH, return_value=_json_response({"unexpected": True})):
            with self.assertRaises(OpenAIError):
                asyncio.run(
                    self.client.generate_with_tools(
                        "q", tools=[_SEARCH_TOOL], tool_runner=_noop_runner
                    )
                )

    def test_args_non_string_dict_is_coerced(self) -> None:
        # arguments 가 문자열이 아니라 dict 면 dict(raw_args) 경로를 탄다.
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
                                "function": {
                                    "name": "search_messages",
                                    "arguments": {"query": "회의"},  # dict (str 아님)
                                },
                            }
                        ],
                    }
                }
            ]
        }
        final = {"choices": [{"message": {"role": "assistant", "content": "끝"}}]}
        seen: dict = {}

        async def runner(name, args):  # type: ignore[no-untyped-def]
            seen["args"] = args
            return "결과"

        _, fake = _sequential_urlopen([first, final])
        with mock.patch(_PATCH, side_effect=fake):
            result = asyncio.run(
                self.client.generate_with_tools(
                    "q", tools=[_SEARCH_TOOL], tool_runner=runner
                )
            )
        self.assertEqual(result, "끝")
        self.assertEqual(seen["args"], {"query": "회의"})

    def test_bad_arguments_json_falls_back_to_empty_dict(self) -> None:
        # arguments 가 깨진 JSON 문자열이면 {} 로 보정한다.
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
                                "function": {
                                    "name": "search_messages",
                                    "arguments": "{not valid json",
                                },
                            }
                        ],
                    }
                }
            ]
        }
        final = {"choices": [{"message": {"role": "assistant", "content": "복구"}}]}
        seen: dict = {}

        async def runner(name, args):  # type: ignore[no-untyped-def]
            seen["args"] = args
            return "r"

        _, fake = _sequential_urlopen([first, final])
        with mock.patch(_PATCH, side_effect=fake):
            result = asyncio.run(
                self.client.generate_with_tools(
                    "q", tools=[_SEARCH_TOOL], tool_runner=runner
                )
            )
        self.assertEqual(result, "복구")
        self.assertEqual(seen["args"], {})

    def test_iteration_cap_then_final_followup_request(self) -> None:
        # 매 응답이 tool_call 만 내고 텍스트가 없으면 max_iterations 도달 후
        # "최종 답변" 후속 요청을 한 번 더 보낸다(content 없는 루프 → 924-944).
        tool_call_payload = {
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
        final = {"choices": [{"message": {"role": "assistant", "content": "최종 종합"}}]}
        # max_iterations=2 → tool_call 2번 + 최종 후속 1번 = 3개의 응답 소비.
        sent, fake = _sequential_urlopen([tool_call_payload, tool_call_payload, final])

        async def runner(name, args):  # type: ignore[no-untyped-def]
            return "관측값"

        with mock.patch(_PATCH, side_effect=fake):
            result = asyncio.run(
                self.client.generate_with_tools(
                    "q",
                    tools=[_SEARCH_TOOL],
                    tool_runner=runner,
                    max_iterations=2,
                )
            )
        self.assertEqual(result, "최종 종합")
        # 마지막(3번째) 요청은 tools 없이 보내며, 최종 답변 유도 user 메시지가 들어간다.
        self.assertEqual(len(sent), 3)
        self.assertNotIn("tools", sent[2])
        followup = sent[2]["messages"][-1]
        self.assertEqual(followup["role"], "user")
        self.assertIn("최종 답변", followup["content"])

    def test_iteration_cap_final_followup_unparseable_returns_empty(self) -> None:
        # 후속 요청 응답마저 형식이 깨지면 last_text 가 빈 문자열로 남는다(940-944).
        tool_call_payload = {
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
        broken_final = {"unexpected": True}
        _, fake = _sequential_urlopen([tool_call_payload, broken_final])

        async def runner(name, args):  # type: ignore[no-untyped-def]
            return "관측값"

        with mock.patch(_PATCH, side_effect=fake):
            result = asyncio.run(
                self.client.generate_with_tools(
                    "q",
                    tools=[_SEARCH_TOOL],
                    tool_runner=runner,
                    max_iterations=1,
                )
            )
        self.assertEqual(result, "")


# ---------------------------------------------------------------------------
# 1114-1127: Anthropic _messages_sync 에러 매핑
# ---------------------------------------------------------------------------


class AnthropicMessagesSyncErrorTest(unittest.TestCase):
    def setUp(self) -> None:
        self.client = AnthropicClient(api_key="sk-ant-x")
        self.tools = self.client._tools_to_anthropic([_SEARCH_TOOL])
        self.messages = [{"role": "user", "content": "hi"}]

    def test_http_error_maps_with_status(self) -> None:
        with mock.patch(_PATCH, side_effect=_http_error(429, body="trace")):
            with self.assertRaises(AnthropicError) as cm:
                self.client._messages_sync(self.messages, "claude-haiku-4-5", self.tools)
        self.assertEqual(cm.exception.status_code, 429)
        self.assertNotIn("trace", str(cm.exception))

    def test_url_error_maps(self) -> None:
        with mock.patch(_PATCH, side_effect=urllib_error.URLError("x")):
            with self.assertRaises(AnthropicError) as cm:
                self.client._messages_sync(self.messages, "claude-haiku-4-5", self.tools)
        self.assertIsNone(cm.exception.status_code)

    def test_timeout_maps(self) -> None:
        with mock.patch(_PATCH, side_effect=TimeoutError()):
            with self.assertRaises(AnthropicError):
                self.client._messages_sync(self.messages, "claude-haiku-4-5", self.tools)

    def test_invalid_json_maps(self) -> None:
        with mock.patch(_PATCH, return_value=_FakeResponse(b"##garbage##")):
            with self.assertRaises(AnthropicError):
                self.client._messages_sync(self.messages, "claude-haiku-4-5", self.tools)

    def test_success_records_usage(self) -> None:
        payload = {
            "content": [{"type": "text", "text": "ok"}],
            "usage": {"input_tokens": 2, "output_tokens": 6},
        }
        with mock.patch(_PATCH, return_value=_json_response(payload)):
            got = self.client._messages_sync(self.messages, "claude-haiku-4-5", self.tools)
        self.assertEqual(got, payload)
        self.assertEqual(self.client.last_usage, TokenUsage(2, 6))


# ---------------------------------------------------------------------------
# 1174 / 1192 / 1203-1221: Anthropic generate_with_tools 분기
# ---------------------------------------------------------------------------


class AnthropicToolLoopExtraTest(unittest.TestCase):
    def setUp(self) -> None:
        self.client = AnthropicClient(api_key="sk-ant-x")

    def test_content_not_list_raises(self) -> None:
        # content 가 list 가 아니면 형식 오류로 처리한다(1174).
        with mock.patch(_PATCH, return_value=_json_response({"content": "oops"})):
            with self.assertRaises(AnthropicError):
                asyncio.run(
                    self.client.generate_with_tools(
                        "q", tools=[_SEARCH_TOOL], tool_runner=_noop_runner
                    )
                )

    def test_tool_use_input_non_dict_coerced_to_empty(self) -> None:
        # tool_use 의 input 이 dict 가 아니면 {} 로 보정한 채 러너를 호출한다(1192).
        first = {
            "stop_reason": "tool_use",
            "content": [
                {
                    "type": "tool_use",
                    "id": "tu_1",
                    "name": "search_messages",
                    "input": "not-a-dict",
                }
            ],
        }
        final = {"stop_reason": "end_turn", "content": [{"type": "text", "text": "끝"}]}
        seen: dict = {}

        async def runner(name, args):  # type: ignore[no-untyped-def]
            seen["args"] = args
            return "관측"

        _, fake = _sequential_urlopen([first, final])
        with mock.patch(_PATCH, side_effect=fake):
            result = asyncio.run(
                self.client.generate_with_tools(
                    "q", tools=[_SEARCH_TOOL], tool_runner=runner
                )
            )
        self.assertEqual(result, "끝")
        self.assertEqual(seen["args"], {})

    def test_iteration_cap_then_final_followup(self) -> None:
        # 매 응답이 텍스트 없이 tool_use 만 내면 반복 상한 도달 후 최종 답변을
        # 한 번 더 요청한다(1203-1221).
        tool_use_payload = {
            "stop_reason": "tool_use",
            "content": [
                {
                    "type": "tool_use",
                    "id": "tu",
                    "name": "search_messages",
                    "input": {"query": "x"},
                }
            ],
        }
        final = {"content": [{"type": "text", "text": "최종 종합"}]}
        sent, fake = _sequential_urlopen([tool_use_payload, tool_use_payload, final])

        async def runner(name, args):  # type: ignore[no-untyped-def]
            return "관측값"

        with mock.patch(_PATCH, side_effect=fake):
            result = asyncio.run(
                self.client.generate_with_tools(
                    "q",
                    tools=[_SEARCH_TOOL],
                    tool_runner=runner,
                    max_iterations=2,
                )
            )
        self.assertEqual(result, "최종 종합")
        self.assertEqual(len(sent), 3)
        # 마지막 요청은 tools 없이 보낸다.
        self.assertNotIn("tools", sent[2])
        followup = sent[2]["messages"][-1]
        self.assertEqual(followup["role"], "user")
        self.assertIn("최종 답변", followup["content"])

    def test_iteration_cap_final_followup_no_text_returns_empty(self) -> None:
        # 후속 요청 응답에도 텍스트 블록이 없으면 빈 문자열을 돌려준다(1216-1221).
        tool_use_payload = {
            "stop_reason": "tool_use",
            "content": [
                {
                    "type": "tool_use",
                    "id": "tu",
                    "name": "search_messages",
                    "input": {"query": "x"},
                }
            ],
        }
        # 후속 응답에 텍스트 없음(content 가 list 지만 text 블록 없음).
        empty_final = {"content": [{"type": "tool_use", "id": "z", "name": "n", "input": {}}]}
        _, fake = _sequential_urlopen([tool_use_payload, empty_final])

        async def runner(name, args):  # type: ignore[no-untyped-def]
            return "관측값"

        with mock.patch(_PATCH, side_effect=fake):
            result = asyncio.run(
                self.client.generate_with_tools(
                    "q",
                    tools=[_SEARCH_TOOL],
                    tool_runner=runner,
                    max_iterations=1,
                )
            )
        self.assertEqual(result, "")


# ---------------------------------------------------------------------------
# 1337-1349: _parse_gemini_payload — 차단 / 비정상 shape / 빈 응답
# ---------------------------------------------------------------------------


class ParseGeminiPayloadTest(unittest.TestCase):
    def test_block_reason_raises(self) -> None:
        with self.assertRaises(GeminiError) as cm:
            _parse_gemini_payload({"promptFeedback": {"blockReason": "SAFETY"}})
        self.assertIn("SAFETY", str(cm.exception))

    def test_block_reason_falsey_is_ignored(self) -> None:
        # blockReason 이 없거나 falsy 면 차단 처리하지 않고 본문 파싱으로 진행한다.
        payload = {
            "promptFeedback": {"blockReason": ""},
            "candidates": [{"content": {"parts": [{"text": " hi "}]}}],
        }
        self.assertEqual(_parse_gemini_payload(payload), "hi")

    def test_unexpected_shape_raises(self) -> None:
        with self.assertRaises(GeminiError):
            _parse_gemini_payload({"candidates": []})

    def test_empty_text_raises(self) -> None:
        payload = {"candidates": [{"content": {"parts": [{"text": "   "}]}}]}
        with self.assertRaises(GeminiError) as cm:
            _parse_gemini_payload(payload)
        self.assertIn("비어", str(cm.exception))

    def test_joins_multiple_parts(self) -> None:
        payload = {
            "candidates": [
                {"content": {"parts": [{"text": "안"}, {"notext": 1}, {"text": "녕"}]}}
            ]
        }
        self.assertEqual(_parse_gemini_payload(payload), "안녕")


# ---------------------------------------------------------------------------
# 1387-1415: OllamaManager._list_sync / pull_model / is_available
# ---------------------------------------------------------------------------


class OllamaManagerTest(unittest.TestCase):
    def setUp(self) -> None:
        self.mgr = OllamaManager(base_url="http://x/")

    def test_base_url_trailing_slash_stripped(self) -> None:
        self.assertEqual(self.mgr.base_url, "http://x")

    def test_list_sync_parses_models(self) -> None:
        payload = {"models": [{"name": "llama3.1:8b", "size": 4700000000}, {"name": "x"}]}
        with mock.patch(_PATCH, return_value=_json_response(payload)):
            models = asyncio.run(self.mgr.list_models())
        self.assertEqual(
            models,
            [OllamaModel(name="llama3.1:8b", size_bytes=4700000000), OllamaModel(name="x", size_bytes=0)],
        )

    def test_list_sync_on_error_returns_empty(self) -> None:
        # 어떤 예외든 잡아서 빈 리스트를 돌려준다(1387-1389).
        with mock.patch(_PATCH, side_effect=urllib_error.URLError("down")):
            models = asyncio.run(self.mgr.list_models())
        self.assertEqual(models, [])

    def test_is_available_true_when_list_succeeds(self) -> None:
        with mock.patch(_PATCH, return_value=_json_response({"models": []})):
            self.assertTrue(asyncio.run(self.mgr.is_available()))

    def test_is_available_false_on_connection_error(self) -> None:
        # is_available 는 raise_on_error=True 경로로 _list_sync 를 호출하므로
        # 연결 실패(서버 다운)는 예외가 전파되어 False 가 된다. '빈 목록'(성공)과
        # '연결 실패'(미가용)를 구분한다.
        with mock.patch(_PATCH, side_effect=urllib_error.URLError("down")):
            self.assertFalse(asyncio.run(self.mgr.is_available()))

    def test_list_models_still_swallows_error(self) -> None:
        # 목록 조회용 list_models 는 기존대로 예외를 흡수해 [] 를 돌려준다(백워드 호환).
        with mock.patch(_PATCH, side_effect=urllib_error.URLError("down")):
            self.assertEqual(asyncio.run(self.mgr.list_models()), [])

    def test_pull_model_missing_binary_raises(self) -> None:
        with mock.patch("discord_assistant.llm.shutil.which", return_value=None):
            with self.assertRaises(OllamaError) as cm:
                asyncio.run(self.mgr.pull_model("llama3.1:8b"))
        self.assertIn("ollama", str(cm.exception).lower())

    def test_pull_model_success(self) -> None:
        # #60: communicate() 대신 stderr 를 직접 읽으며 마지막 N 바이트만 보관한다.
        # read() 가 빈 바이트를 돌려줄 때까지 읽고 proc.wait() 로 종료를 기다린다.
        proc = mock.Mock()
        proc.returncode = 0
        proc.stderr = mock.Mock()
        proc.stderr.read = mock.AsyncMock(side_effect=[b""])
        proc.wait = mock.AsyncMock(return_value=0)
        with mock.patch("discord_assistant.llm.shutil.which", return_value="/usr/bin/ollama"), \
            mock.patch(
                "discord_assistant.llm.asyncio.create_subprocess_exec",
                new=mock.AsyncMock(return_value=proc),
            ):
            # 예외 없이 완료되어야 한다.
            asyncio.run(self.mgr.pull_model("llama3.1:8b"))
        proc.wait.assert_awaited_once()

    def test_pull_model_nonzero_exit_raises(self) -> None:
        # #60: stderr 청크를 순차로 돌려주고 마지막에 b"" 로 EOF 를 알린다.
        proc = mock.Mock()
        proc.returncode = 1
        proc.stderr = mock.Mock()
        proc.stderr.read = mock.AsyncMock(side_effect=[b"pull failed: disk full", b""])
        proc.wait = mock.AsyncMock(return_value=1)
        with mock.patch("discord_assistant.llm.shutil.which", return_value="/usr/bin/ollama"), \
            mock.patch(
                "discord_assistant.llm.asyncio.create_subprocess_exec",
                new=mock.AsyncMock(return_value=proc),
            ):
            with self.assertRaises(OllamaError) as cm:
                asyncio.run(self.mgr.pull_model("llama3.1:8b"))
        self.assertIn("disk full", str(cm.exception))

    def test_pull_model_stderr_tail_is_bounded(self) -> None:
        # #60: stderr 가 비정상적으로 장황해도 마지막 _PULL_STDERR_TAIL_BYTES 바이트만
        # 보관해 메모리/오류 메시지가 무제한 커지지 않는다(꼬리 유지).
        from discord_assistant.llm import _PULL_STDERR_TAIL_BYTES

        big_head = b"A" * (_PULL_STDERR_TAIL_BYTES * 3)
        tail_marker = b"final error: out of disk"
        proc = mock.Mock()
        proc.returncode = 1
        proc.stderr = mock.Mock()
        proc.stderr.read = mock.AsyncMock(side_effect=[big_head, tail_marker, b""])
        proc.wait = mock.AsyncMock(return_value=1)
        with mock.patch("discord_assistant.llm.shutil.which", return_value="/usr/bin/ollama"), \
            mock.patch(
                "discord_assistant.llm.asyncio.create_subprocess_exec",
                new=mock.AsyncMock(return_value=proc),
            ):
            with self.assertRaises(OllamaError) as cm:
                asyncio.run(self.mgr.pull_model("llama3.1:8b"))
        msg = str(cm.exception)
        # 꼬리(끝부분)는 보존되지만 앞부분의 거대한 head 는 상한으로 잘려나간다.
        self.assertIn("final error: out of disk", msg)
        self.assertLessEqual(len(msg.encode("utf-8")), _PULL_STDERR_TAIL_BYTES + 200)


# ---------------------------------------------------------------------------
# 1504: supports_vision — 알 수 없는/미지원 제공자(Gemini)는 False
# ---------------------------------------------------------------------------


class SupportsVisionGeminiTest(unittest.TestCase):
    def test_gemini_provider_returns_false(self) -> None:
        # GEMINI 는 supports_vision 분기에 없으므로 최종 return False 로 떨어진다(1504).
        self.assertFalse(supports_vision(LLMProvider.GEMINI, "gemini-1.5-flash"))
        self.assertFalse(supports_vision(LLMProvider.GEMINI, "gemini-1.5-pro"))

    def test_empty_model_still_false(self) -> None:
        self.assertFalse(supports_vision(LLMProvider.GEMINI, ""))


# ---------------------------------------------------------------------------
# #47: OpenAI _generate_sync — content 가 null/비-str 이면 형식 오류로 변환
# ---------------------------------------------------------------------------


class OpenAIGenerateContentNullTest(unittest.TestCase):
    def setUp(self) -> None:
        self.client = OpenAIClient(api_key="sk-x")

    def test_content_null_raises_openai_error_not_attribute_error(self) -> None:
        # 모델이 content:null 로 응답하면(refusal/content filter) None.strip() 의 raw
        # AttributeError 가 아니라 친절한 OpenAIError(LLMError) 로 변환돼야 한다.
        payload = {"choices": [{"message": {"role": "assistant", "content": None}}]}
        with mock.patch(_PATCH, return_value=_json_response(payload)):
            with self.assertRaises(OpenAIError) as cm:
                asyncio.run(self.client.generate("hi"))
        # LLMError 의 서브클래스여야 ask 핸들러의 except (UserFacingError, LLMError) 에 걸린다.
        self.assertIsInstance(cm.exception, LLMError)
        self.assertNotIsInstance(cm.exception, AttributeError)

    def test_valid_string_content_still_works(self) -> None:
        payload = {
            "choices": [{"message": {"role": "assistant", "content": " 안녕 "}}],
            "usage": {"prompt_tokens": 1, "completion_tokens": 2},
        }
        with mock.patch(_PATCH, return_value=_json_response(payload)):
            result = asyncio.run(self.client.generate("hi"))
        self.assertEqual(result, "안녕")
        self.assertEqual(self.client.last_usage, TokenUsage(1, 2))


# ---------------------------------------------------------------------------
# #48: OpenAI _stream_sync — SSE error 이벤트를 표면화한다
# ---------------------------------------------------------------------------


class OpenAIStreamErrorEventTest(unittest.TestCase):
    def setUp(self) -> None:
        self.client = OpenAIClient(api_key="sk-x")

    def test_stream_error_event_raises_openai_error(self) -> None:
        # 스트림 중간에 {"error": {...}} 이벤트가 오면 조용히 빈 응답으로 끝내지 않고
        # OpenAIError 로 표면화해야 한다(Ollama 스트림과 대칭).
        lines = [
            _sse({"choices": [{"delta": {"content": "Hel"}}]}),
            _sse({"error": {"message": "rate limit exceeded", "type": "rate_limit"}}),
        ]
        with mock.patch(_PATCH, return_value=_stream_response(lines)):
            with self.assertRaises(OpenAIError) as cm:
                asyncio.run(_collect(self.client.generate_stream("hi", model="m")))
        self.assertIn("rate limit exceeded", str(cm.exception))

    def test_stream_error_event_without_message_still_raises(self) -> None:
        lines = [_sse({"error": True})]
        with mock.patch(_PATCH, return_value=_stream_response(lines)):
            with self.assertRaises(OpenAIError):
                asyncio.run(_collect(self.client.generate_stream("hi")))


# ---------------------------------------------------------------------------
# #54: OllamaManager.pull_model — communicate() 타임아웃 시 정리 + 안내 오류
# ---------------------------------------------------------------------------


class OllamaPullTimeoutTest(unittest.TestCase):
    def setUp(self) -> None:
        self.mgr = OllamaManager(base_url="http://x/")

    def test_pull_timeout_kills_proc_and_raises(self) -> None:
        # #60: 타임아웃은 stderr 읽기/종료 대기를 감싼 wait_for 에서 발생한다.
        # stderr.read 가 TimeoutError 를 던지면 _drain_and_wait 가 깨지고 except 로
        # 들어가 kill + wait 로 프로세스를 정리한다.
        proc = mock.Mock()
        proc.stderr = mock.Mock()
        proc.stderr.read = mock.AsyncMock(side_effect=TimeoutError())
        proc.kill = mock.Mock()
        proc.wait = mock.AsyncMock(return_value=0)
        with mock.patch("discord_assistant.llm.shutil.which", return_value="/usr/bin/ollama"), \
            mock.patch(
                "discord_assistant.llm.asyncio.create_subprocess_exec",
                new=mock.AsyncMock(return_value=proc),
            ):
            with self.assertRaises(OllamaError) as cm:
                asyncio.run(self.mgr.pull_model("llama3.1:8b"))
        # 타임아웃 시 프로세스를 정리(kill + wait)해야 좀비/리소스 누수가 없다.
        proc.kill.assert_called_once()
        proc.wait.assert_awaited_once()
        self.assertIn("초과", str(cm.exception))


# ---------------------------------------------------------------------------
# #56: OllamaManager._list_sync — 비정상 항목('name' 누락/비-dict)은 건너뛴다
# ---------------------------------------------------------------------------


class OllamaListMalformedTest(unittest.TestCase):
    def setUp(self) -> None:
        self.mgr = OllamaManager(base_url="http://x/")

    def test_missing_name_and_non_dict_items_skipped(self) -> None:
        # 'name' 누락 항목, dict 가 아닌 항목, name 이 str 이 아닌 항목은 건너뛰고
        # 정상 항목만 반환한다(KeyError/TypeError 전파 없이).
        payload = {
            "models": [
                {"name": "llama3.1:8b", "size": 100},
                {"size": 200},               # name 누락 → skip
                "not-a-dict",                # dict 아님 → skip
                {"name": 12345},             # name 이 str 아님 → skip
                {"name": "qwen2.5:7b"},      # size 없음 → 0
                {"name": "phi3:mini", "size": None},  # #49: size:null → 0
                {"name": "gemma2:9b", "size": "big"},  # #49: size 비-int → 0
            ]
        }
        with mock.patch(_PATCH, return_value=_json_response(payload)):
            models = asyncio.run(self.mgr.list_models())
        self.assertEqual(
            models,
            [
                OllamaModel(name="llama3.1:8b", size_bytes=100),
                OllamaModel(name="qwen2.5:7b", size_bytes=0),
                OllamaModel(name="phi3:mini", size_bytes=0),
                OllamaModel(name="gemma2:9b", size_bytes=0),
            ],
        )


# ---------------------------------------------------------------------------
# #55: CircuitBreaker — half-open 단일 프로브 가드(thundering herd 방지)
# ---------------------------------------------------------------------------


class CircuitBreakerHalfOpenProbeTest(unittest.TestCase):
    def test_half_open_allows_single_probe_others_fast_fail(self) -> None:
        clock = {"t": 0.0}
        breaker = CircuitBreaker(
            failure_threshold=1, reset_timeout=10.0, time_fn=lambda: clock["t"]
        )
        # 한 번 실패시켜 open 으로 만든다.
        breaker.record_failure()
        self.assertTrue(breaker._is_open())
        # reset_timeout 경과 → half-open.
        clock["t"] = 20.0
        self.assertFalse(breaker._is_open())
        # 첫 프로브는 통과(예외 없음).
        breaker.before_call()
        # 같은 half-open 창에서 결과가 기록되기 전 두 번째 호출은 빠르게 실패해야 한다.
        with self.assertRaises(CircuitBreakerOpenError):
            breaker.before_call()
        # 프로브 성공 기록 시 닫히고 이후 호출은 통과한다.
        breaker.record_success()
        breaker.before_call()  # 예외 없어야 함

    def test_half_open_probe_failure_reopens_and_blocks(self) -> None:
        clock = {"t": 0.0}
        breaker = CircuitBreaker(
            failure_threshold=1, reset_timeout=10.0, time_fn=lambda: clock["t"]
        )
        breaker.record_failure()
        clock["t"] = 20.0
        # half-open 프로브 통과 후 실패 → 다시 open.
        breaker.before_call()
        breaker.record_failure()
        # _opened_at 이 now(20.0)로 재설정되어 reset_timeout 동안 open.
        with self.assertRaises(CircuitBreakerOpenError):
            breaker.before_call()

    def test_concurrent_half_open_only_one_probe_passes(self) -> None:
        # reset_timeout 직후 동시에 대기하던 여러 코루틴 중 단 하나의 프로브만
        # 실제 coro_fn 을 호출하고 나머지는 CircuitBreakerOpenError 로 빠르게 실패한다.
        clock = {"t": 0.0}
        breaker = CircuitBreaker(
            failure_threshold=1, reset_timeout=10.0, time_fn=lambda: clock["t"]
        )
        breaker.record_failure()
        clock["t"] = 20.0
        calls = {"n": 0}

        async def slow_ok() -> str:
            calls["n"] += 1
            await asyncio.sleep(0)  # 다른 코루틴에 양보
            return "ok"

        async def run() -> list:
            tasks = [
                asyncio.create_task(
                    _with_circuit_breaker(breaker, slow_ok, max_attempts=1, delay=0)
                )
                for _ in range(5)
            ]
            return await asyncio.gather(*tasks, return_exceptions=True)

        results = asyncio.run(run())
        # 정확히 한 프로브만 coro_fn 을 호출했어야 한다.
        self.assertEqual(calls["n"], 1)
        oks = [r for r in results if r == "ok"]
        blocked = [r for r in results if isinstance(r, CircuitBreakerOpenError)]
        self.assertEqual(len(oks), 1)
        self.assertEqual(len(blocked), 4)


# ---------------------------------------------------------------------------
# #45/#57: generate_with_tools — 멀티 왕복 last_usage 누적(과소 집계 방지)
# ---------------------------------------------------------------------------


class ToolLoopUsageAccumulationTest(unittest.TestCase):
    def test_add_usage_sums_both_fields(self) -> None:
        self.assertEqual(
            _add_usage(TokenUsage(3, 4), TokenUsage(10, 20)),
            TokenUsage(13, 24),
        )

    def test_openai_tool_loop_accumulates_usage(self) -> None:
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
            ],
            "usage": {"prompt_tokens": 10, "completion_tokens": 5},
        }
        final = {
            "choices": [{"message": {"role": "assistant", "content": "끝"}}],
            "usage": {"prompt_tokens": 7, "completion_tokens": 3},
        }
        client = OpenAIClient(api_key="sk-x")

        async def runner(name, args):  # type: ignore[no-untyped-def]
            return "관측값"

        _, fake = _sequential_urlopen([first, final])
        with mock.patch(_PATCH, side_effect=fake):
            result = asyncio.run(
                client.generate_with_tools(
                    "q", tools=[_SEARCH_TOOL], tool_runner=runner
                )
            )
        self.assertEqual(result, "끝")
        # 마지막 왕복분(7,3)만이 아니라 두 왕복 합(17,8)이 기록돼야 한다.
        self.assertEqual(client.last_usage, TokenUsage(17, 8))

    def test_anthropic_tool_loop_accumulates_usage(self) -> None:
        first = {
            "content": [
                {"type": "tool_use", "id": "t1", "name": "search_messages", "input": {}}
            ],
            "stop_reason": "tool_use",
            "usage": {"input_tokens": 12, "output_tokens": 4},
        }
        final = {
            "content": [{"type": "text", "text": "끝"}],
            "stop_reason": "end_turn",
            "usage": {"input_tokens": 6, "output_tokens": 2},
        }
        client = AnthropicClient(api_key="sk-ant-x")

        async def runner(name, args):  # type: ignore[no-untyped-def]
            return "관측값"

        _, fake = _sequential_urlopen([first, final])
        with mock.patch(_PATCH, side_effect=fake):
            result = asyncio.run(
                client.generate_with_tools(
                    "q", tools=[_SEARCH_TOOL], tool_runner=runner
                )
            )
        self.assertEqual(result, "끝")
        self.assertEqual(client.last_usage, TokenUsage(18, 6))


# ---------------------------------------------------------------------------
# #46: 서킷 브레이커는 재시도 불가 4xx 를 실패로 카운트하지 않는다
# ---------------------------------------------------------------------------


class CircuitBreakerNonRetryableTest(unittest.TestCase):
    def test_non_retryable_4xx_does_not_open_breaker(self) -> None:
        # 401/403/400 같은 재시도 불가 클라이언트 오류는 서킷 실패로 세지 않으므로
        # 임계치만큼 반복해도 서킷이 열리지 않는다(매번 실제 호출이 도달한다).
        clock = {"t": 0.0}
        breaker = CircuitBreaker(
            failure_threshold=2, reset_timeout=30.0, time_fn=lambda: clock["t"]
        )
        calls = {"n": 0}

        async def client_error() -> str:
            calls["n"] += 1
            raise LLMError("unauthorized", status_code=401)

        async def run() -> None:
            for _ in range(3):
                with self.assertRaises(LLMError):
                    await _with_circuit_breaker(
                        breaker, client_error, max_attempts=1, delay=0
                    )

        asyncio.run(run())
        # 서킷이 열리지 않았으므로 3번 모두 실제 호출이 일어났다(빠른 실패 없음).
        self.assertEqual(calls["n"], 3)

    def test_retryable_5xx_still_opens_breaker(self) -> None:
        # 5xx 는 여전히 실패로 카운트되어 임계치 도달 시 서킷이 열린다(회귀 방지).
        clock = {"t": 0.0}
        breaker = CircuitBreaker(
            failure_threshold=2, reset_timeout=30.0, time_fn=lambda: clock["t"]
        )
        calls = {"n": 0}

        async def server_error() -> str:
            calls["n"] += 1
            raise LLMError("server", status_code=500)

        async def run() -> None:
            for _ in range(2):
                with self.assertRaises(LLMError):
                    await _with_circuit_breaker(
                        breaker, server_error, max_attempts=1, delay=0
                    )
            with self.assertRaises(CircuitBreakerOpenError):
                await _with_circuit_breaker(
                    breaker, server_error, max_attempts=1, delay=0
                )

        asyncio.run(run())
        self.assertEqual(calls["n"], 2)

    def test_non_retryable_releases_half_open_probe(self) -> None:
        # half-open 상태에서 프로브가 재시도 불가 오류로 실패해도 probe-in-flight 가
        # 풀려 다음 호출이 막히지 않는다(record_ignored 가 플래그를 해제).
        clock = {"t": 0.0}
        breaker = CircuitBreaker(
            failure_threshold=1, reset_timeout=10.0, time_fn=lambda: clock["t"]
        )

        async def fail_500() -> str:
            raise LLMError("server", status_code=500)

        async def fail_401() -> str:
            raise LLMError("unauthorized", status_code=401)

        async def ok() -> str:
            return "ok"

        async def run() -> None:
            # 5xx 로 서킷을 연다.
            with self.assertRaises(LLMError):
                await _with_circuit_breaker(breaker, fail_500, max_attempts=1, delay=0)
            # reset_timeout 경과 → half-open. 프로브가 401(재시도 불가)로 실패.
            clock["t"] = 20.0
            with self.assertRaises(LLMError):
                await _with_circuit_breaker(breaker, fail_401, max_attempts=1, delay=0)
            # 프로브가 해제됐으므로 다음 호출이 통과해야 한다(probe stuck 아님).
            result = await _with_circuit_breaker(breaker, ok, max_attempts=1, delay=0)
            self.assertEqual(result, "ok")

        asyncio.run(run())


# ---------------------------------------------------------------------------
# #50: Anthropic 툴 루프는 stop_reason 과 무관하게 tool_use 블록이 있으면 실행
# ---------------------------------------------------------------------------


class AnthropicToolUseDespiteStopReasonTest(unittest.TestCase):
    def test_tool_use_executed_even_if_stop_reason_not_tool_use(self) -> None:
        # 모델이 tool_use 블록을 냈지만 stop_reason 이 'max_tokens' 로 들어온 경우에도
        # 도구를 실행해야 한다(과거 OR 조건은 도구를 건너뛰고 부분 텍스트만 반환했다).
        first = {
            "content": [
                {"type": "tool_use", "id": "t1", "name": "search_messages", "input": {}}
            ],
            "stop_reason": "max_tokens",  # tool_use 가 아님
            "usage": {"input_tokens": 5, "output_tokens": 1},
        }
        final = {
            "content": [{"type": "text", "text": "도구결과반영"}],
            "stop_reason": "end_turn",
            "usage": {"input_tokens": 3, "output_tokens": 2},
        }
        client = AnthropicClient(api_key="sk-ant-x")
        ran = {"called": False}

        async def runner(name, args):  # type: ignore[no-untyped-def]
            ran["called"] = True
            return "관측값"

        _, fake = _sequential_urlopen([first, final])
        with mock.patch(_PATCH, side_effect=fake):
            result = asyncio.run(
                client.generate_with_tools(
                    "q", tools=[_SEARCH_TOOL], tool_runner=runner
                )
            )
        self.assertTrue(ran["called"])  # 도구가 실제로 실행됐다.
        self.assertEqual(result, "도구결과반영")

    def test_no_tool_use_returns_text_immediately(self) -> None:
        # tool_use 블록이 없으면 stop_reason 무관하게 즉시 최종 텍스트를 반환한다.
        only_text = {
            "content": [{"type": "text", "text": "답"}],
            "stop_reason": "end_turn",
            "usage": {"input_tokens": 2, "output_tokens": 1},
        }
        client = AnthropicClient(api_key="sk-ant-x")
        _, fake = _sequential_urlopen([only_text])
        with mock.patch(_PATCH, side_effect=fake):
            result = asyncio.run(
                client.generate_with_tools(
                    "q", tools=[_SEARCH_TOOL], tool_runner=_noop_runner
                )
            )
        self.assertEqual(result, "답")


# ---------------------------------------------------------------------------
# #61: _coerce_token_count — 정상/내림/음수/비정상 입력 처리
# ---------------------------------------------------------------------------


class CoerceTokenCountTest(unittest.TestCase):
    def test_positive_int_passthrough(self) -> None:
        self.assertEqual(_coerce_token_count(42), 42)

    def test_float_truncates_down(self) -> None:
        # 의도된 보수적 내림(반올림 아님).
        self.assertEqual(_coerce_token_count(1.9), 1)

    def test_negative_becomes_zero(self) -> None:
        self.assertEqual(_coerce_token_count(-5), 0)

    def test_none_and_non_numeric_become_zero(self) -> None:
        self.assertEqual(_coerce_token_count(None), 0)
        self.assertEqual(_coerce_token_count("12"), 0)

    def test_bool_becomes_zero(self) -> None:
        # bool 은 int 의 서브클래스지만 토큰 수로 취급하지 않는다.
        self.assertEqual(_coerce_token_count(True), 0)


if __name__ == "__main__":
    unittest.main()
