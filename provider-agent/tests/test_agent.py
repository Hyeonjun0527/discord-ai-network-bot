"""에이전트 처리 테스트 — 가짜 ollama·연결 주입(빠르고 결정적)."""
from __future__ import annotations

import asyncio

import pytest

from provider_agent.agent import ProviderAgent
from provider_agent.config import AgentConfig
from provider_agent.constants import ErrorCode
from provider_agent.ollama import OllamaError
from provider_agent.protocol import (
    CancelFrame,
    Frame,
    InferError,
    InferRequest,
    InferResult,
    Usage,
)


@pytest.fixture(autouse=True)
def _no_auto_pause(monkeypatch):
    """이 모듈의 테스트는 자원 자동 pause 와 무관하게 결정적이어야 한다(하드웨어 독립)."""
    monkeypatch.setattr("provider_agent.sysinfo.load_level", lambda: "idle")
    monkeypatch.setattr("provider_agent.sysinfo.battery_state", lambda: "charging")


class FakeConn:
    def __init__(self, guild_id: int | None = None) -> None:
        self.sent: list[Frame] = []
        self.authed = True
        self.guild_id = guild_id  # 서버별 일일 한도의 키(연결이 속한 길드)

    async def send(self, frame: Frame) -> None:
        self.sent.append(frame)


class FakeOllama:
    def __init__(self, text: str = "결과", usage: Usage | None = None, error: str | None = None, delay: float = 0.0):
        self.text = text
        self.usage = usage or Usage(1, 2)
        self.error = error
        self.delay = delay

    async def generate(self, prompt: str, model: str | None) -> tuple[str, Usage]:
        if self.delay:
            await asyncio.sleep(self.delay)
        if self.error:
            raise OllamaError(self.error)
        return self.text, self.usage

    async def list_models(self) -> list[str]:
        return ["m1"]

    async def generate_stream(self, prompt: str, model: str | None):
        for piece in ("안", "녕", "!"):
            yield ("chunk", piece)
        yield ("done", self.usage)


def test_hello_advertises_only_selected_models():
    """서버에 광고(provider_hello)되는 모델 = 사용자가 고른 목록 그대로. 서버는 이 목록만 라우팅한다."""
    agent = ProviderAgent(AgentConfig(token="T", models=("llama3.1:8b", "gemma2")), ollama=FakeOllama())  # type: ignore[arg-type]
    assert agent._build_hello().models == ["llama3.1:8b", "gemma2"]


def test_hello_empty_when_nothing_selected():
    """아무것도 선택 안 하면 광고 목록이 비어, 서버가 이 PC로 텍스트 요청을 라우팅하지 않는다
    (예전의 '빈 목록 → 전체 자동감지' 폴백이 되살아나지 않게 회귀 방지)."""
    agent = ProviderAgent(AgentConfig(token="T", models=()), ollama=FakeOllama())  # type: ignore[arg-type]
    assert agent._build_hello().models == []


@pytest.mark.asyncio
async def test_handle_infer_streaming_emits_chunks():
    """스트리밍(#142): req.stream 시 ChunkFrame 점진 전송 + done 종료."""
    from provider_agent.protocol import ChunkFrame

    agent = ProviderAgent(AgentConfig(token="T"), ollama=FakeOllama())  # type: ignore[arg-type]
    conn = FakeConn()
    await agent.handle_infer(conn, InferRequest(request_id="s1", prompt="안녕", stream=True))  # type: ignore[arg-type]
    chunks = [f for f in conn.sent if isinstance(f, ChunkFrame)]
    deltas = [c.delta for c in chunks if not c.done]
    assert "".join(deltas) == "안녕!"
    assert chunks[-1].done is True
    assert agent.processed == 1


@pytest.mark.asyncio
async def test_broadcast_image_chunks_to_authed_conns():
    """전문가 층: 유저 직접 생성 이미지를 인증 연결에 청크 분할 ImageBroadcastFrame 으로 보낸다."""
    from provider_agent.protocol import ImageBroadcastFrame

    agent = ProviderAgent(AgentConfig(token="T"), ollama=FakeOllama())  # type: ignore[arg-type]
    conn = FakeConn(guild_id=111)
    async with agent._entries_lock:
        agent._entries.append({"conn": conn, "guild_name": "g"})
    b64 = "A" * 2_500_000  # IMAGE_CHUNK_CHARS 보다 커서 여러 청크
    await agent._broadcast_image(b64)
    frames = [f for f in conn.sent if isinstance(f, ImageBroadcastFrame)]
    assert len(frames) >= 2  # 최소 1개 데이터 청크 + done
    assert frames[-1].done is True and frames[-1].delta == ""
    # 모든 청크가 같은 broadcast_id 로 묶임
    assert len({f.broadcast_id for f in frames}) == 1
    # 데이터 청크를 합치면 원본 base64
    assert "".join(f.delta for f in frames) == b64


@pytest.mark.asyncio
async def test_handle_image_emits_progress_then_data():
    """이미지 생성: ComfyUI /ws 실시간 진행률을 on_progress 콜백으로 받아 progress 청크로 흘리고,
    완료 시 b64 데이터 청크 + done 을 보낸다(번역은 Gemini 없으면 원문 폴백 — 외부 호출 없음)."""
    from provider_agent.protocol import ChunkFrame

    class SlowSD:
        async def txt2img(self, prompt: str, options=None, on_progress=None) -> str:
            for pct in (20, 55, 90):  # 실 ComfyUI 처럼 샘플링 진행률을 콜백으로 푸시
                if on_progress:
                    on_progress(pct)
                await asyncio.sleep(0.02)
            return "AAAA"

        async def health(self) -> bool:
            return True

    agent = ProviderAgent(AgentConfig(token="T"), ollama=FakeOllama(), sd=SlowSD())  # type: ignore[arg-type]
    agent._image_ready = True
    conn = FakeConn()
    await agent._handle_image(conn, InferRequest(request_id="img1", prompt="고양이", task="image"))  # type: ignore[arg-type]
    await asyncio.sleep(0.05)  # on_progress 가 create_task 한 진행률 청크 전송이 완료되도록 양보
    chunks = [f for f in conn.sent if isinstance(f, ChunkFrame)]
    assert any(0 < c.progress < 100 for c in chunks), "실시간 진행률 청크(0<pct<100)가 전송돼야 함"
    assert any(c.delta and c.progress < 0 for c in chunks), "b64 데이터 청크가 있어야 함"
    assert chunks[-1].done is True


@pytest.mark.asyncio
async def test_handle_infer_success():
    agent = ProviderAgent(AgentConfig(token="T"), ollama=FakeOllama(text="답", usage=Usage(3, 4)))  # type: ignore[arg-type]
    conn = FakeConn()
    await agent.handle_infer(conn, InferRequest(request_id="r1", prompt="안녕"))  # type: ignore[arg-type]
    res = conn.sent[0]
    assert isinstance(res, InferResult) and res.text == "답" and res.usage.prompt_tokens == 3


@pytest.mark.asyncio
async def test_handle_infer_ollama_error():
    agent = ProviderAgent(AgentConfig(token="T"), ollama=FakeOllama(error="boom"))  # type: ignore[arg-type]
    conn = FakeConn()
    await agent.handle_infer(conn, InferRequest(request_id="r1", prompt="x"))  # type: ignore[arg-type]
    err = conn.sent[0]
    assert isinstance(err, InferError) and err.code == ErrorCode.OLLAMA_ERROR


@pytest.mark.asyncio
async def test_daily_limit():
    agent = ProviderAgent(AgentConfig(token="T", daily_limit=1), ollama=FakeOllama())  # type: ignore[arg-type]
    conn = FakeConn()
    await agent.handle_infer(conn, InferRequest(request_id="r1", prompt="a"))  # type: ignore[arg-type]
    await agent.handle_infer(conn, InferRequest(request_id="r2", prompt="b"))  # type: ignore[arg-type]
    assert isinstance(conn.sent[0], InferResult)
    assert isinstance(conn.sent[1], InferError) and conn.sent[1].code == ErrorCode.BUSY


@pytest.mark.asyncio
async def test_daily_limit_per_guild():
    # 서버별 독립 한도: 한 서버의 소진이 다른 서버에 영향 주지 않는다(전역 합산 X).
    agent = ProviderAgent(AgentConfig(token="T", daily_limit=1), ollama=FakeOllama())  # type: ignore[arg-type]
    a = FakeConn(guild_id=100)
    b = FakeConn(guild_id=200)
    await agent.handle_infer(a, InferRequest(request_id="a1", prompt="x"))  # type: ignore[arg-type] A: 성공
    await agent.handle_infer(a, InferRequest(request_id="a2", prompt="x"))  # type: ignore[arg-type] A: 한도초과
    await agent.handle_infer(b, InferRequest(request_id="b1", prompt="x"))  # type: ignore[arg-type] B: 독립 성공
    assert isinstance(a.sent[0], InferResult)
    assert isinstance(a.sent[1], InferError) and a.sent[1].code == ErrorCode.BUSY
    assert isinstance(b.sent[0], InferResult)  # B 는 A 소진과 무관하게 성공


@pytest.mark.asyncio
async def test_per_guild_limit_override():
    # 서버별로 *다른* 한도값(G3 override): 서버 100 은 2건, 서버 200 은 전역 기본 1건.
    agent = ProviderAgent(AgentConfig(token="T", daily_limit=1), ollama=FakeOllama())  # type: ignore[arg-type]
    agent._guild_policy = {100: {"daily_limit": 2}}
    a = FakeConn(guild_id=100)
    b = FakeConn(guild_id=200)
    for rid in ("a1", "a2", "a3"):
        await agent.handle_infer(a, InferRequest(request_id=rid, prompt="x"))  # type: ignore[arg-type]
    for rid in ("b1", "b2"):
        await agent.handle_infer(b, InferRequest(request_id=rid, prompt="x"))  # type: ignore[arg-type]
    assert sum(isinstance(f, InferResult) for f in a.sent) == 2  # override 2건
    assert isinstance(a.sent[2], InferError) and a.sent[2].code == ErrorCode.BUSY
    assert sum(isinstance(f, InferResult) for f in b.sent) == 1  # 전역 기본 1건
    assert isinstance(b.sent[1], InferError) and b.sent[1].code == ErrorCode.BUSY


def test_guild_policy_override_in_hello():
    # override 한도가 hello 의 remaining 에도 반영된다(중앙 라우팅이 이 값을 본다).
    agent = ProviderAgent(AgentConfig(token="T", daily_limit=5, models=("m",)))
    agent._guild_policy = {100: {"daily_limit": 99}}
    assert agent._build_hello(100).remaining_daily_requests == 99
    assert agent._build_hello(200).remaining_daily_requests == 5  # override 없으면 전역


@pytest.mark.asyncio
async def test_per_guild_concurrency_enforced():
    """동시 처리 상한이 **서버별**로 강제된다. 서버 100 은 1, 서버 200 은 2(override)."""
    # 전역 세마포어가 병목이 되지 않게 넉넉히(10) — 길드별 상한만 관찰.
    peak: dict[int, int] = {100: 0, 200: 0}
    agent = ProviderAgent(AgentConfig(token="T", max_concurrency=10, daily_limit=0))  # type: ignore[arg-type]
    agent._guild_policy = {100: {"max_concurrency": 1}, 200: {"max_concurrency": 2}}

    # in-flight 피크를 길드별로 직접 측정(동시성 관찰).
    inflight = {100: 0, 200: 0}

    class P2(FakeOllama):
        async def generate(self, prompt: str, model: str | None) -> tuple[str, Usage]:
            gid = int(prompt)
            inflight[gid] += 1
            peak[gid] = max(peak[gid], inflight[gid])
            await asyncio.sleep(0.05)
            inflight[gid] -= 1
            return "x", Usage()

    agent._ollama = P2()  # type: ignore[assignment]
    a, b = FakeConn(guild_id=100), FakeConn(guild_id=200)
    await asyncio.gather(
        agent.handle_infer(a, InferRequest(request_id="a1", prompt="100")),  # type: ignore[arg-type]
        agent.handle_infer(a, InferRequest(request_id="a2", prompt="100")),  # type: ignore[arg-type]
        agent.handle_infer(b, InferRequest(request_id="b1", prompt="200")),  # type: ignore[arg-type]
        agent.handle_infer(b, InferRequest(request_id="b2", prompt="200")),  # type: ignore[arg-type]
    )
    assert peak[100] == 1  # 서버 100: 동시 1개만(override)
    assert peak[200] == 2  # 서버 200: 전역 2개까지


@pytest.mark.asyncio
async def test_per_guild_max_seconds_enforced():
    """1건 최대 처리 시간(서버별)을 넘기면 중단·BUSY/에러 반려(한도 소모 후에도 결과 없음)."""
    agent = ProviderAgent(AgentConfig(token="T", daily_limit=0), ollama=FakeOllama(delay=1.0))  # type: ignore[arg-type]
    agent._guild_policy = {100: {"max_seconds": 0.05}}  # 처리(1s) 보다 짧은 상한
    conn = FakeConn(guild_id=100)
    await agent.handle_infer(conn, InferRequest(request_id="t1", prompt="x"))  # type: ignore[arg-type]
    assert not any(isinstance(f, InferResult) for f in conn.sent)  # 결과 없음(시간 초과)
    err = conn.sent[-1]
    assert isinstance(err, InferError) and err.code == ErrorCode.OLLAMA_ERROR
    assert "최대 처리 시간" in err.message


def test_guild_concurrency_in_hello():
    # 동시 처리 override 가 hello 의 max_concurrency 에도 길드별로 반영된다.
    agent = ProviderAgent(AgentConfig(token="T", max_concurrency=4, models=("m",)))
    agent._guild_policy = {100: {"max_concurrency": 1}}
    assert agent._build_hello(100).max_concurrency == 1
    assert agent._build_hello(200).max_concurrency == 4  # override 없으면 전역


@pytest.mark.asyncio
async def test_admin_action_calls_central(monkeypatch, tmp_path):
    # 관리 작업은 durable 토큰으로 central 관리 채널을 호출한다(권한 판정은 central).
    monkeypatch.setenv("XDG_CONFIG_HOME", str(tmp_path))
    from provider_agent.config_file import add_connection

    add_connection("dv1.fakedurable", guild_id=100, guild_name="A")
    agent = ProviderAgent(AgentConfig(token="dv1.fakedurable"))  # type: ignore[arg-type]
    calls: list = []

    def fake_post(base, action, dt, gid, tid=0):
        calls.append((action, dt, gid, tid))
        return {"ok": True, "message": "승인됨"}

    monkeypatch.setattr("provider_agent.agent._post_provider_admin", fake_post)
    res = await agent.admin_action("approve", 100, 99)
    assert res["ok"] is True
    assert calls == [("approve", "dv1.fakedurable", 100, 99)]


@pytest.mark.asyncio
async def test_admin_set_policy_calls_central(monkeypatch, tmp_path):
    # 자동 승인 토글도 durable 토큰으로 central 관리 채널을 호출한다.
    monkeypatch.setenv("XDG_CONFIG_HOME", str(tmp_path))
    from provider_agent.config_file import add_connection

    add_connection("dv1.fakedurable", guild_id=100, guild_name="A")
    agent = ProviderAgent(AgentConfig(token="dv1.fakedurable"))  # type: ignore[arg-type]
    calls: list = []

    def fake(base, dt, gid, auto):
        calls.append((dt, gid, auto))
        return {"ok": True}

    monkeypatch.setattr("provider_agent.agent._post_provider_admin_policy", fake)
    res = await agent.admin_set_policy(100, True)
    assert res["ok"] is True
    assert calls == [("dv1.fakedurable", 100, True)]


@pytest.mark.asyncio
async def test_admin_rejects_unknown_action_and_missing_durable(monkeypatch, tmp_path):
    monkeypatch.setenv("XDG_CONFIG_HOME", str(tmp_path))
    agent = ProviderAgent(AgentConfig(token="onetime"))  # type: ignore[arg-type] durable 아님
    assert (await agent.admin_action("nuke", 100, 1))["ok"] is False  # 알 수 없는 작업
    assert (await agent.admin_manage(100))["ok"] is False  # durable 없음


def test_build_hello_per_guild():
    # hello 의 remaining_daily_requests 가 길드별로 다르게 보고된다.
    agent = ProviderAgent(AgentConfig(token="T", daily_limit=5, models=("m",)))
    agent._remaining_by_guild[100] = 3  # 길드 100 은 2건 처리해 잔여 3
    assert agent._build_hello(100).remaining_daily_requests == 3
    assert agent._build_hello(200).remaining_daily_requests == 5  # 200 은 첫 접근 → 한도값


@pytest.mark.asyncio
async def test_set_image_enabled_toggles_capability():
    """이미지 라이브 토글: set_image_enabled 가 hello capabilities 의 'image' 를 켜고 끈다(재시작 불필요)."""

    class FakeSD:
        async def health(self) -> bool:
            return True

        async def set_output_png(self) -> bool:
            return True

    agent = ProviderAgent(AgentConfig(token="T", models=("m",)), ollama=FakeOllama(), sd=FakeSD())  # type: ignore[arg-type]
    ready = await agent.set_image_enabled(True)
    assert ready is True
    assert "image" in agent._build_hello().capabilities
    await agent.set_image_enabled(False)
    assert "image" not in agent._build_hello().capabilities
    assert agent.image_ready is False


def test_build_hello_unlimited_reports_zero():
    # 무제한(daily_limit=0)이면 hello remaining=0(=한도 없음 센티넬).
    agent = ProviderAgent(AgentConfig(token="T", daily_limit=0, models=("m",)))
    assert agent._build_hello(100).remaining_daily_requests == 0


@pytest.mark.asyncio
async def test_cancel_no_result():
    agent = ProviderAgent(AgentConfig(token="T", max_concurrency=1), ollama=FakeOllama(delay=2.0))  # type: ignore[arg-type]
    conn = FakeConn()
    await agent._on_server_frame(conn, InferRequest(request_id="r1", prompt="x"))  # type: ignore[arg-type]
    await asyncio.sleep(0.05)
    await agent._on_server_frame(conn, CancelFrame(request_id="r1"))  # type: ignore[arg-type]
    await asyncio.sleep(0.1)
    assert not any(isinstance(f, InferResult) for f in conn.sent)


@pytest.mark.asyncio
async def test_concurrency_limit():
    seen: list[int] = []
    agent = ProviderAgent(AgentConfig(token="T", max_concurrency=1))  # ollama 교체
    conn = FakeConn()

    class Probe(FakeOllama):
        async def generate(self, prompt: str, model: str | None) -> tuple[str, Usage]:
            seen.append(agent._inflight)
            await asyncio.sleep(0.05)
            return "x", Usage()

    agent._ollama = Probe()  # type: ignore[assignment]
    await asyncio.gather(
        agent.handle_infer(conn, InferRequest(request_id="a", prompt="1")),  # type: ignore[arg-type]
        agent.handle_infer(conn, InferRequest(request_id="b", prompt="2")),  # type: ignore[arg-type]
    )
    assert max(seen) == 1  # 동시 1개만 처리


def test_build_hello():
    agent = ProviderAgent(AgentConfig(token="T", models=("m1", "m2"), max_concurrency=3))
    hello = agent._build_hello()
    assert hello.models == ["m1", "m2"] and hello.max_concurrency == 3


@pytest.mark.asyncio
async def test_model_default_to_own():
    # 서버가 모델을 안 주면(model=None) 에이전트가 자기 첫 모델로 처리한다(E2E 회귀 방지).
    class RecOllama(FakeOllama):
        last_model: str | None = None

        async def generate(self, prompt: str, model: str | None) -> tuple[str, Usage]:
            RecOllama.last_model = model
            return "ok", Usage()

    agent = ProviderAgent(AgentConfig(token="T", models=("mymodel",)), ollama=RecOllama())  # type: ignore[arg-type]
    await agent.handle_infer(FakeConn(), InferRequest(request_id="r", prompt="x"))  # type: ignore[arg-type]
    assert RecOllama.last_model == "mymodel"


def test_reload_models_hot_reload(monkeypatch, tmp_path):
    """SIGHUP hot-reload(#129): 저장 설정에서 models 재적용."""
    monkeypatch.setenv("XDG_CONFIG_HOME", str(tmp_path))
    from provider_agent.config_file import save_config
    agent = ProviderAgent(AgentConfig(token="T", models=("old",)))  # type: ignore[arg-type]
    assert agent._models == ["old"]
    # 새 모델로 설정 저장 후 reload
    save_config(AgentConfig(token="T", models=("a", "b")))
    assert agent.reload_models() == ["a", "b"]
    assert agent._models == ["a", "b"]


@pytest.mark.asyncio
async def test_multi_connection_add_remove(monkeypatch, tmp_path):
    """멀티-서버: 실행 중 연결 추가/해제가 엔트리·상태에 반영된다(네트워크 없이 가짜 연결)."""
    monkeypatch.setenv("XDG_CONFIG_HOME", str(tmp_path))

    class FakeConn:
        def __init__(self, cfg, on_frame, hello, on_durable_token=None, on_guild_info=None):
            self._authed = True

        @property
        def authed(self):
            return self._authed

        async def run(self):
            await asyncio.Event().wait()

        async def stop(self):
            self._authed = False

    monkeypatch.setattr("provider_agent.agent.AgentConnection", FakeConn)
    agent = ProviderAgent(AgentConfig(token="T"), ollama=FakeOllama())  # type: ignore[arg-type]
    await agent.add_connection("TA", guild_id=100, guild_name="A")
    await agent.add_connection("TB", guild_id=200, guild_name="B")
    st = agent.connections_status()
    assert {s["guildId"] for s in st} == {"100", "200"}  # guildId 는 64bit 정밀도 위해 문자열로 emit
    assert agent.is_connected() is True
    assert await agent.remove_connection(guild_id=100) is True
    assert [s["guildId"] for s in agent.connections_status()] == ["200"]
    await agent.remove_connection(guild_id=200)
    assert agent.is_connected() is False


class _FakeSD:
    def __init__(self, healthy: bool = True) -> None:
        self._healthy = healthy

    async def health(self) -> bool:
        return self._healthy

    async def set_output_png(self) -> bool:
        return True


@pytest.mark.asyncio
async def test_boot_sd_noop_when_not_installed(monkeypatch):
    """ComfyUI 가 설치/실행 안 돼 있으면(health False) 자동기동해도 재광고하지 않는다."""
    import provider_agent.comfy_setup as comfy_setup

    monkeypatch.setattr(comfy_setup, "is_installed", lambda directory=None: False)
    agent = ProviderAgent(
        AgentConfig(token="T", models=("m",), enable_image=True), ollama=FakeOllama(), sd=_FakeSD(False)
    )
    called = {}

    async def fake_readv():
        called["re"] = True

    monkeypatch.setattr(agent, "_readvertise", fake_readv)
    await agent._boot_sd()
    assert "re" not in called
    assert agent.image_ready is False


@pytest.mark.asyncio
async def test_boot_sd_launches_and_readvertises(monkeypatch):
    """이미지 엔진=ComfyUI: 설치돼 있으면 자동기동 → 준비(health)되면 image capability 재광고(재연결)."""
    import provider_agent.comfy_setup as comfy_setup

    launched = {}

    async def fake_start(directory=None):
        launched["started"] = True
        return True

    monkeypatch.setattr(comfy_setup, "is_installed", lambda directory=None: True)
    monkeypatch.setattr(comfy_setup, "start", fake_start)
    agent = ProviderAgent(
        AgentConfig(token="T", models=("m",), enable_image=True), ollama=FakeOllama(), sd=_FakeSD(True)
    )
    re_called = {}

    async def fake_readv():
        re_called["yes"] = True

    monkeypatch.setattr(agent, "_readvertise", fake_readv)
    await agent._boot_sd()
    assert launched.get("started") is True  # ComfyUI 자동 기동
    assert agent.image_ready is True
    assert re_called.get("yes") is True


@pytest.mark.asyncio
async def test_readvertise_reconnects_all_entries(monkeypatch):
    """capability 변경 반영: 모든 연결을 끊고 새 hello 로 재접속(SD 준비됨 등)."""
    agent = ProviderAgent(AgentConfig(token="T", models=("m",)), ollama=FakeOllama())
    agent._entries = [
        {"token": "A", "guild_id": 1, "guild_name": "g1", "conn": FakeConn(), "task": None, "status_task": None},
        {"token": "B", "guild_id": 2, "guild_name": "g2", "conn": FakeConn(), "task": None, "status_task": None},
    ]
    stopped: list = []
    made: list = []
    spawned: list = []

    async def fake_stop(e):
        stopped.append(e["token"])

    def fake_make(token, gid, name):
        made.append(token)
        return {"token": token, "guild_id": gid, "guild_name": name, "conn": FakeConn(), "task": None, "status_task": None}

    def fake_spawn(e):
        spawned.append(e["token"])
        agent._entries.append(e)

    monkeypatch.setattr(agent, "_stop_entry", fake_stop)
    monkeypatch.setattr(agent, "_make_entry", fake_make)
    monkeypatch.setattr(agent, "_spawn_entry", fake_spawn)
    await agent._readvertise()
    assert stopped == ["A", "B"]  # 기존 연결 전부 끊김
    assert set(made) == {"A", "B"} and set(spawned) == {"A", "B"}  # 같은 토큰으로 재접속
    assert len(agent._entries) == 2
