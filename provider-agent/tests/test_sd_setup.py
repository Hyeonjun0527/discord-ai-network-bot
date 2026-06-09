"""이미지 엔진 공용 모듈(sd_setup) 테스트 — 모델 카탈로그·해상도·다운로드·standalone Python·공용 헬퍼."""
from __future__ import annotations

import asyncio

from provider_agent import sd_setup as sd_mod


def test_install_dir_is_dot_free(monkeypatch, tmp_path):
    # gradio 3.43.2 는 경로에 '.'로 시작하는 컴포넌트가 있으면 /file= 정적자산을 403 으로 막는다.
    # → install_dir 의 어떤 컴포넌트도 점으로 시작하면 안 된다(WebUI JS 로드 보장). 이름은 sdnext.
    d = sd_mod.install_dir()
    assert d.name == "sdnext"
    assert not any(part.startswith(".") for part in d.parts), f"점으로 시작하는 경로 컴포넌트 금지: {d}"


def test_standalone_python_url_per_platform(monkeypatch):
    # 호환 Python 미보유 머신용 standalone CPython URL 이 OS/arch 별로 맞는 트리플을 가리킨다.
    cases = {
        ("darwin", "arm64"): "aarch64-apple-darwin",
        ("darwin", "x86_64"): "x86_64-apple-darwin",
        ("win32", "AMD64"): "x86_64-pc-windows-msvc",
        ("linux", "x86_64"): "x86_64-unknown-linux-gnu",
        ("linux", "aarch64"): "aarch64-unknown-linux-gnu",
    }
    for (plat, mach), triple in cases.items():
        monkeypatch.setattr(sd_mod.sys, "platform", plat)
        monkeypatch.setattr(sd_mod.platform, "machine", lambda m=mach: m)
        url = sd_mod._standalone_python_url()
        assert url and triple in url
        assert sd_mod.BUNDLED_PYTHON_VERSION in url and sd_mod.BUNDLED_PYTHON_RELEASE in url
        assert url.endswith("-install_only.tar.gz")


def test_bundled_python_path_layout(monkeypatch):
    # install_only 는 <dest>/python/ 로 풀린다 → bin/python3.11 (unix) / python.exe (win).
    monkeypatch.setattr(sd_mod.sys, "platform", "darwin")
    assert sd_mod.bundled_python_path().as_posix().endswith("python/bin/python3.11")
    monkeypatch.setattr(sd_mod.sys, "platform", "win32")
    assert sd_mod.bundled_python_path().name == "python.exe"


def test_compatible_python_prefers_bundled(monkeypatch):
    # 이미 받아둔 standalone 이 있으면 시스템 PATH 와 무관하게 그걸 최우선으로 쓴다.
    monkeypatch.setattr(sd_mod, "_bundled_python_ready", lambda: "/data/nexa/python/python/bin/python3.11")
    assert sd_mod.compatible_python() == "/data/nexa/python/python/bin/python3.11"


async def test_ensure_bundled_python_returns_existing_without_download(monkeypatch):
    # 이미 받아둔 게 있으면 네트워크 없이 즉시 그 경로 반환.
    monkeypatch.setattr(sd_mod, "_bundled_python_ready", lambda: "/x/python/bin/python3.11")
    assert await sd_mod.ensure_bundled_python() == "/x/python/bin/python3.11"


async def test_ensure_bundled_python_none_on_unsupported_platform(monkeypatch):
    # 미지원 OS/arch(URL None)면 다운로드 시도 없이 None → 호출부가 패키지 매니저 폴백으로.
    monkeypatch.setattr(sd_mod, "_bundled_python_ready", lambda: None)
    monkeypatch.setattr(sd_mod, "_standalone_python_url", lambda: None)
    assert await sd_mod.ensure_bundled_python() is None


def test_install_dir_is_sdnext():
    # SD.Next 는 기존 A1111(stable-diffusion-webui)과 분리된 경로(sdnext)에 설치.
    d = sd_mod.install_dir()
    assert d.name == "sdnext" and "stable-diffusion-webui" not in str(d)


def test_compatible_python(monkeypatch):
    # PATH 에 있으면 명령 그대로 반환.
    monkeypatch.setattr(sd_mod.shutil, "which", lambda c: "/x/python3.11" if c == "python3.11" else None)
    assert sd_mod.compatible_python() == "python3.11"
    # PATH 에도 없고 절대 경로에도 없으면 None.
    monkeypatch.setattr(sd_mod.shutil, "which", lambda c: None)
    monkeypatch.setattr(sd_mod.os.path, "isfile", lambda p: False)
    assert sd_mod.compatible_python() is None
    # macOS GUI 앱(PATH 에 brew 경로 없음): 절대 경로에 있으면 그 경로 반환(no-python 회귀 방지).
    monkeypatch.setattr(sd_mod.sys, "platform", "darwin")
    monkeypatch.setattr(sd_mod.os.path, "isfile", lambda p: p == "/opt/homebrew/bin/python3.11")
    monkeypatch.setattr(sd_mod.os, "access", lambda p, m: True)
    assert sd_mod.compatible_python() == "/opt/homebrew/bin/python3.11"


def test_custom_model_from_url():
    # resolve 직접 링크 → 모델 dict
    m = sd_mod.custom_model_from_url("https://huggingface.co/cagliostrolab/animagine-xl-4.0/resolve/main/animagine-xl-4.0-opt.safetensors")
    assert m is not None
    assert m["filename"] == "animagine-xl-4.0-opt.safetensors"
    assert m["url"].endswith("animagine-xl-4.0-opt.safetensors")
    # blob URL(페이지) → resolve 로 보정
    blob = sd_mod.custom_model_from_url("https://huggingface.co/x/y/blob/main/foo.safetensors")
    assert blob is not None and "/resolve/" in blob["url"] and blob["filename"] == "foo.safetensors"
    # .ckpt 허용
    assert sd_mod.custom_model_from_url("https://huggingface.co/a/b/resolve/main/m.ckpt") is not None
    # HF 아님 / 확장자 안 맞음 → None(임의 호스트 차단)
    assert sd_mod.custom_model_from_url("https://evil.com/m.safetensors") is None
    assert sd_mod.custom_model_from_url("https://huggingface.co/a/b/resolve/main/readme.txt") is None
    assert sd_mod.custom_model_from_url("") is None


def test_resolution_for_checkpoint():
    # SDXL 계열 → 1024, SD1.5 계열 → 512. 체크포인트 문자열은 "name [hash]" 부분일치.
    assert sd_mod.resolution_for_checkpoint("animagine-xl-4.0-opt.safetensors [abc123]") == (1024, 1024)
    assert sd_mod.resolution_for_checkpoint("sd_xl_base_1.0.safetensors") == (1024, 1024)
    # SD.Next 는 확장자 없이 보고한다(실증: "AnythingV5V3_v5PrtRE") → stem 매칭 필수
    assert sd_mod.resolution_for_checkpoint("animagine-xl-4.0-opt") == (1024, 1024)
    assert sd_mod.resolution_for_checkpoint("AnythingV5V3_v5PrtRE") == (512, 512)
    assert sd_mod.resolution_for_checkpoint("AnythingV5V3_v5PrtRE.safetensors") == (512, 512)
    assert sd_mod.resolution_for_checkpoint("v1-5-pruned-emaonly.safetensors") == (512, 512)
    # 모르는 모델/None → 안전하게 512
    assert sd_mod.resolution_for_checkpoint("someones-custom-merge.safetensors") == (512, 512)
    assert sd_mod.resolution_for_checkpoint(None) == (512, 512)


class _FakeStreamResp:
    """aiohttp 응답 스트리밍 모킹: status·content_length·청크 시퀀스를 흉내낸다."""

    def __init__(self, status, body=b"", content_length=None):
        self.status = status
        self._body = body
        self.content_length = content_length

    async def __aenter__(self):
        return self

    async def __aexit__(self, *a):
        return False

    @property
    def content(self):
        body = self._body

        class _Content:
            @staticmethod
            async def iter_chunked(n):
                for i in range(0, len(body), n):
                    yield body[i : i + n]

        return _Content()


class _FakeSession:
    """aiohttp.ClientSession 모킹: get(url, headers) 호출을 기록하고 미리 준비한 응답을 돌려준다."""

    def __init__(self, response, captured):
        self._response = response
        self._captured = captured

    async def __aenter__(self):
        return self

    async def __aexit__(self, *a):
        return False

    def get(self, url, headers=None):
        self._captured["url"] = url
        self._captured["headers"] = dict(headers or {})
        return self._response


def _patch_session(monkeypatch, response, captured):
    monkeypatch.setattr(
        sd_mod.aiohttp, "ClientSession", lambda timeout=None: _FakeSession(response, captured)
    )


def test_download_resumes_with_range_206(monkeypatch, tmp_path):
    """(b) 이어받기: 기존 .part 가 있으면 Range 요청 → 206 응답을 append 로 이어붙인다."""
    dest = tmp_path / "model.safetensors"
    part = dest.with_suffix(dest.suffix + ".part")
    part.write_bytes(b"AAAA")  # 이미 4바이트 받음
    rest = b"BBBBBB"  # 남은 6바이트
    captured: dict = {}
    # 206: content_length 는 '남은 분량'(6)만.
    _patch_session(monkeypatch, _FakeStreamResp(206, rest, content_length=len(rest)), captured)

    asyncio.run(sd_mod._download("http://x/model", dest))

    assert captured["headers"].get("Range") == "bytes=4-"  # 받은 크기로 Range 요청
    assert dest.read_bytes() == b"AAAA" + rest  # 기존 + 남은 분량 = 완성
    assert not part.exists()


def test_download_range_unsupported_restarts_200(monkeypatch, tmp_path):
    """(c) Range 미지원: .part 있어도 서버가 200(전체 재전송)이면 처음부터(절단)."""
    dest = tmp_path / "model.safetensors"
    part = dest.with_suffix(dest.suffix + ".part")
    part.write_bytes(b"OLD-GARBAGE")  # 이전 부분 — 200 이면 버려야 함
    full = b"FULLBODY12"
    captured: dict = {}
    _patch_session(monkeypatch, _FakeStreamResp(200, full, content_length=len(full)), captured)

    asyncio.run(sd_mod._download("http://x/model", dest))

    assert captured["headers"].get("Range") == "bytes=11-"  # 시도는 했으나
    assert dest.read_bytes() == full  # 200 → 절단 후 전체 = 정확히 full(이전 garbage 없음)
    assert not part.exists()


def test_download_416_treats_as_complete(monkeypatch, tmp_path):
    """416(범위 초과): 이미 다 받은 것으로 보고 .part 를 rename 한다."""
    dest = tmp_path / "model.safetensors"
    part = dest.with_suffix(dest.suffix + ".part")
    part.write_bytes(b"COMPLETE")
    captured: dict = {}
    _patch_session(monkeypatch, _FakeStreamResp(416), captured)

    asyncio.run(sd_mod._download("http://x/model", dest))
    assert dest.read_bytes() == b"COMPLETE"
    assert not part.exists()


def test_download_preserves_part_on_error(monkeypatch, tmp_path):
    """(d) 네트워크 끊김 등 예외 시 .part 를 보존(삭제 금지) → 다음 시도가 이어받음."""
    dest = tmp_path / "model.safetensors"
    part = dest.with_suffix(dest.suffix + ".part")

    class _BoomResp(_FakeStreamResp):
        @property
        def content(self):
            class _Content:
                @staticmethod
                async def iter_chunked(n):
                    yield b"PARTIAL"  # 일부 받고
                    raise ConnectionResetError("끊김")  # 도중 끊김
            return _Content()

    captured: dict = {}
    _patch_session(monkeypatch, _BoomResp(200, content_length=100), captured)

    import pytest as _pt

    with _pt.raises(ConnectionResetError):
        asyncio.run(sd_mod._download("http://x/model", dest))
    assert part.exists() and part.read_bytes() == b"PARTIAL"  # 받은 만큼 보존
    assert not dest.exists()


def test_resolution_custom_base(monkeypatch):
    import provider_agent.config_file as cf
    monkeypatch.setattr(cf, "load_config", lambda *a, **k: {"custom_bases": {"my-sdxl-merge.safetensors": "sdxl"}})
    # 커스텀 SDXL → 1024(확장자 없이 보고돼도 stem 매칭)
    assert sd_mod.resolution_for_checkpoint("my-sdxl-merge [abc]") == (1024, 1024)
    assert sd_mod.resolution_for_checkpoint("my-sdxl-merge.safetensors") == (1024, 1024)
    # 등록 안 된 커스텀 → 512
    assert sd_mod.resolution_for_checkpoint("unknown-merge") == (512, 512)


