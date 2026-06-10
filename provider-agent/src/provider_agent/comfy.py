"""로컬 ComfyUI 이미지 생성 백엔드 — SD.Next(A1111) 대안.

유저 자율: 어떤 로컬 이미지 도구든 쓰게 한다. ComfyUI 는 워크플로 그래프 API(/prompt)라 A1111
(/sdapi)과 다르지만, **SDClient 와 동일한 ``txt2img(prompt, options)->base64 PNG`` · ``health()``**
인터페이스를 제공해 에이전트가 백엔드를 그대로 갈아끼울 수 있다(_handle_image 변경 없음).

기본 txt2img 워크플로를 내장하고, 체크포인트 이름은 ComfyUI 에 설치된 것을 자동 조회해 채운다
(유저가 어떤 모델을 넣었든 동작). localhost 전용(netguard 원칙) — 원격은 명시 옵션에서만.
"""
from __future__ import annotations

import asyncio
import base64
import json
import logging
import uuid
from typing import Any, Callable

import aiohttp

logger = logging.getLogger("provider_agent.comfy")

# KSampler seed 위젯 뒤에 따라오는 control_after_generate 의사 위젯 값(노드 입력엔 없고 widgets_values 에만 존재).
_CONTROL_VALUES = {"fixed", "increment", "decrement", "randomize"}
# UI 그래프에서 실행 그래프로 변환할 때 건너뛸 비실행 노드.
_SKIP_NODE_TYPES = {"Note", "MarkdownNote", "Reroute", "PrimitiveNode", "PrimitiveString"}


class ComfyError(Exception):
    """ComfyUI 호출/응답 오류."""


def build_workflow(
    prompt: str,
    ckpt_name: str,
    *,
    negative: str = "",
    width: int = 512,
    height: int = 512,
    steps: int = 20,
    cfg: float = 7.0,
    seed: int = 0,
) -> dict:
    """기본 txt2img 워크플로(ComfyUI API 그래프). 순수 함수(테스트 가능).

    표준 노드(CheckpointLoaderSimple→CLIPTextEncode×2→EmptyLatentImage→KSampler→VAEDecode→SaveImage).
    """
    return {
        "4": {"class_type": "CheckpointLoaderSimple", "inputs": {"ckpt_name": ckpt_name}},
        "5": {"class_type": "EmptyLatentImage", "inputs": {"width": width, "height": height, "batch_size": 1}},
        "6": {"class_type": "CLIPTextEncode", "inputs": {"text": prompt, "clip": ["4", 1]}},
        "7": {"class_type": "CLIPTextEncode", "inputs": {"text": negative, "clip": ["4", 1]}},
        "3": {
            "class_type": "KSampler",
            "inputs": {
                "seed": seed,
                "steps": steps,
                "cfg": cfg,
                "sampler_name": "euler",
                "scheduler": "normal",
                "denoise": 1.0,
                "model": ["4", 0],
                "positive": ["6", 0],
                "negative": ["7", 0],
                "latent_image": ["5", 0],
            },
        },
        "8": {"class_type": "VAEDecode", "inputs": {"samples": ["3", 0], "vae": ["4", 2]}},
        "9": {"class_type": "SaveImage", "inputs": {"filename_prefix": "nexa", "images": ["8", 0]}},
    }


# ── 유저 ComfyUI 워크플로 템플릿(SSOT) → 실행 그래프 변환·주입 ──────────────────────────
# 유저가 ComfyUI 에서 만든 워크플로(user/default/workflows/*.json, UI 그래프 포맷)를 그대로
# 백엔드로 쓴다. `/prompt` 는 API 포맷(노드 id→{class_type,inputs})만 받으므로, 실행 중 ComfyUI 의
# /object_info 스키마를 권위로 삼아 UI→API 변환한 뒤 긍정/부정 프롬프트·시드만 주입한다.
# (검증: 실 default.json 9노드 변환→실 PNG 생성 E2E 통과. control_after_generate 오프셋 포함.)


def ui_graph_to_api(graph: dict, object_info: dict) -> dict:
    """ComfyUI UI 그래프(JSON) → /prompt API 그래프. object_info 로 위젯↔입력을 정렬한다.

    - 연결 입력: 노드 inputs[].link 를 links 표로 풀어 ``[src_node_id, src_slot]`` 로.
    - 위젯 입력: object_info 의 required+optional 입력 순서대로 widgets_values 를 소비.
      seed 처럼 control_after_generate 가 붙은 위젯은 다음 한 칸(제어값)을 추가 소비한다.
    """
    nodes = graph.get("nodes", [])
    links = {ln[0]: ln for ln in graph.get("links", []) if isinstance(ln, list) and ln}
    api: dict[str, dict] = {}
    for n in nodes:
        ctype = n.get("type")
        if not ctype or ctype in _SKIP_NODE_TYPES or n.get("mode") in (2, 4):  # 2=muted,4=bypassed
            continue
        nid = str(n.get("id"))
        inputs: dict[str, Any] = {}
        linked: set[str] = set()
        for inp in n.get("inputs", []) or []:
            lk = inp.get("link")
            if lk is not None and lk in links:
                link = links[lk]
                inputs[inp["name"]] = [str(link[1]), link[2]]
                linked.add(inp["name"])
        wv = n.get("widgets_values") or []
        info = object_info.get(ctype)
        if info and isinstance(wv, list):
            req = info.get("input", {}).get("required", {})
            opt = info.get("input", {}).get("optional", {})
            wi = 0
            for name, spec in list(req.items()) + list(opt.items()):
                if name in linked or not isinstance(spec, list) or not spec:
                    continue
                t = spec[0]
                if not (isinstance(t, list) or t in ("INT", "FLOAT", "STRING", "BOOLEAN")):
                    continue  # 노드-링크 타입(미연결) — 위젯 아님
                if wi >= len(wv):
                    break
                inputs[name] = wv[wi]
                wi += 1
                spec_opts = spec[1] if len(spec) > 1 and isinstance(spec[1], dict) else {}
                if wi < len(wv) and (spec_opts.get("control_after_generate") or (isinstance(wv[wi], str) and wv[wi] in _CONTROL_VALUES)):
                    wi += 1  # control_after_generate 의사 위젯 소비
        api[nid] = {"class_type": ctype, "inputs": inputs}
    return api


def find_sampler_id(api: dict) -> str | None:
    """API 그래프에서 KSampler 류 노드 id(긍정/부정/시드 주입 지점). 없으면 None."""
    for nid, node in api.items():
        ct = node.get("class_type", "")
        if "KSampler" in ct or "SamplerCustom" in ct:
            return str(nid)
    return None


def inject_prompt(api: dict, positive: str, negative: str | None, seed: int | None) -> dict:
    """API 그래프의 긍정/부정 CLIPTextEncode 와 KSampler seed 를 주입(in-place 반환).

    긍정/부정 노드는 KSampler 의 positive/negative 링크를 역추적해 식별한다(노드 이름·순서 무관).
    """
    ks = find_sampler_id(api)
    if ks is None:
        raise ComfyError("워크플로에 KSampler 가 없습니다(템플릿 부적합)")
    si = api[ks]["inputs"]
    pos_ref = si.get("positive")
    if isinstance(pos_ref, list) and pos_ref:
        api[str(pos_ref[0])]["inputs"]["text"] = positive
    else:
        raise ComfyError("KSampler positive 입력이 연결돼 있지 않습니다")
    if negative is not None:
        neg_ref = si.get("negative")
        if isinstance(neg_ref, list) and neg_ref and str(neg_ref[0]) in api:
            api[str(neg_ref[0])]["inputs"]["text"] = negative
    if seed is not None and "seed" in si:
        si["seed"] = seed
    elif seed is not None and "noise_seed" in si:
        si["noise_seed"] = seed
    return api


def _pick_file(object_info: dict, node: str, field: str, prefer: tuple[str, ...]) -> str | None:
    """object_info 의 노드 입력 콤보에서 prefer 키워드를 포함하는 첫 파일, 없으면 첫 파일."""
    try:
        choices = object_info[node]["input"]["required"][field][0]
    except (KeyError, IndexError, TypeError):
        return None
    if not isinstance(choices, list) or not choices:
        return None
    for kw in prefer:
        for c in choices:
            if kw.lower() in str(c).lower():
                return str(c)
    return str(choices[0])


def build_anima_workflow(object_info: dict, prompt: str, negative: str, *, width: int = 1024, height: int = 1024, seed: int = 0) -> dict:
    """번들 폴백 — default.json 변환 실패 시 쓰는 Anima 정합 워크플로(API 포맷).

    설치된 파일을 object_info 에서 골라(anima/qwen 우선) UNETLoader+CLIPLoader+VAELoader 로 구성.
    유저의 검증된 기본값(er_sde/simple/30/cfg4)을 미러링한다.
    """
    unet = _pick_file(object_info, "UNETLoader", "unet_name", ("anima", "wai"))
    clip = _pick_file(object_info, "CLIPLoader", "clip_name", ("qwen_3", "qwen"))
    vae = _pick_file(object_info, "VAELoader", "vae_name", ("qwen_image", "qwen"))
    if not (unet and clip and vae):
        raise ComfyError("Anima 폴백 구성 실패(UNET/CLIP/VAE 파일 없음)")
    clip_types = object_info.get("CLIPLoader", {}).get("input", {}).get("required", {}).get("type", [[]])[0]
    clip_type = "anima" if "anima" in clip_types else ("stable_diffusion" if "stable_diffusion" in clip_types else (clip_types[0] if clip_types else "stable_diffusion"))
    return {
        "44": {"class_type": "UNETLoader", "inputs": {"unet_name": unet, "weight_dtype": "default"}},
        "45": {"class_type": "CLIPLoader", "inputs": {"clip_name": clip, "type": clip_type}},
        "15": {"class_type": "VAELoader", "inputs": {"vae_name": vae}},
        "28": {"class_type": "EmptyLatentImage", "inputs": {"width": width, "height": height, "batch_size": 1}},
        "11": {"class_type": "CLIPTextEncode", "inputs": {"text": prompt, "clip": ["45", 0]}},
        "12": {"class_type": "CLIPTextEncode", "inputs": {"text": negative, "clip": ["45", 0]}},
        "19": {
            "class_type": "KSampler",
            "inputs": {
                "seed": seed, "steps": 30, "cfg": 4.0, "sampler_name": "er_sde",
                "scheduler": "simple", "denoise": 1.0,
                "model": ["44", 0], "positive": ["11", 0], "negative": ["12", 0], "latent_image": ["28", 0],
            },
        },
        "8": {"class_type": "VAEDecode", "inputs": {"samples": ["19", 0], "vae": ["15", 0]}},
        "9": {"class_type": "SaveImage", "inputs": {"filename_prefix": "nexa", "images": ["8", 0]}},
    }


class ComfyClient:
    def __init__(self, base_url: str, timeout: float = 180.0, *, workflows_dir: "Any" = None) -> None:
        self._base = base_url.rstrip("/")
        self._timeout = aiohttp.ClientTimeout(total=timeout)
        self._active: str | None = None  # 선택된 체크포인트(없으면 첫 모델 자동)
        self._client_id = uuid.uuid4().hex  # /prompt·/ws 매칭용
        self._cur_prompt_id: str | None = None  # 진행 중 작업(취소 대상)
        # 유저 워크플로 디렉터리(앱 관리 ComfyUI). 비면 comfy_setup 기본 경로를 지연 조회.
        self._workflows_dir = workflows_dir
        # 앱(/그림)이 제출한 prompt_id — 전문가 리스너가 '유저 직접 생성'과 구분하려고 추적(상한 FIFO).
        self._submitted_ids: set[str] = set()
        self._submitted_order: list[str] = []

    async def health(self) -> bool:
        """ComfyUI 가 응답하는지(capability 광고 판단용). /object_info 200."""
        try:
            async with aiohttp.ClientSession(timeout=aiohttp.ClientTimeout(total=10)) as s:
                async with s.get(f"{self._base}/object_info") as r:
                    return r.status == 200
        except aiohttp.ClientError:
            return False

    async def set_output_png(self) -> bool:
        """SDClient 호환 no-op(ComfyUI 는 SaveImage 가 PNG 로 저장). 항상 성공."""
        return True

    async def current_checkpoint(self) -> str | None:
        """SDClient 호환 — 활성 체크포인트(해상도 판정용). 선택값 있으면 그것, 없으면 첫 모델."""
        if self._active:
            return self._active
        return await self.first_checkpoint()

    async def set_checkpoint(self, name: str) -> bool:
        """활성 체크포인트를 전환(SDClient 호환). 설치 목록에 있으면 _active 로 두고 True.

        ComfyUI 는 워크플로의 ckpt_name 으로 모델을 고르므로 핫스왑이 즉시(다음 생성부터) 반영된다.
        """
        if not name:
            return False
        ckpts = await self.list_checkpoints()
        if name in ckpts:
            self._active = name
            return True
        return False

    async def list_checkpoints(self) -> list[str]:
        """ComfyUI 에 설치된 체크포인트 전체 목록(폴더 스캔 결과 — 유저가 넣은 .safetensors 다 포함)."""
        try:
            async with aiohttp.ClientSession(timeout=aiohttp.ClientTimeout(total=10)) as s, s.get(
                f"{self._base}/object_info/CheckpointLoaderSimple"
            ) as r:
                data = await r.json()
        except (aiohttp.ClientError, ValueError):
            return []
        try:
            names = data["CheckpointLoaderSimple"]["input"]["required"]["ckpt_name"][0]
            return [str(n) for n in names] if isinstance(names, list) else []
        except (KeyError, IndexError, TypeError):
            return []

    async def first_checkpoint(self) -> str | None:
        """ComfyUI 에 설치된 첫 체크포인트 이름(선택값 없을 때 자동 선택)."""
        ckpts = await self.list_checkpoints()
        return ckpts[0] if ckpts else None

    async def _fetch_object_info(self, s: aiohttp.ClientSession) -> dict:
        """ComfyUI /object_info 전체(노드 입력 스키마). 변환·폴백 구성의 권위."""
        async with s.get(f"{self._base}/object_info") as r:
            data = await r.json()
        return data if isinstance(data, dict) else {}

    def _default_workflow_path(self) -> "Any":
        """앱 관리 ComfyUI 의 default 워크플로 파일 경로(없으면 None)."""
        import pathlib

        base = self._workflows_dir
        if base is None:
            try:
                from . import comfy_setup

                base = comfy_setup.install_dir() / "user" / "default" / "workflows"
            except Exception:  # noqa: BLE001
                return None
        p = pathlib.Path(base) / "default.json"
        return p if p.is_file() else None

    async def _build_graph(self, s: aiohttp.ClientSession, prompt: str, negative: str, width: int, height: int, seed: int) -> dict:
        """제출용 API 그래프 구성: ① 유저 default.json 템플릿 주입 → ② 실패 시 Anima 번들 폴백.

        템플릿(유저 ComfyUI 워크플로) = SSOT. 유저가 워크플로를 바꾸면 그대로 반영된다(§계획 6-A).
        """
        object_info = await self._fetch_object_info(s)
        path = self._default_workflow_path()
        if path is not None:
            try:
                graph = json.loads(path.read_text(encoding="utf-8"))
                api = ui_graph_to_api(graph, object_info)
                inject_prompt(api, prompt, negative, seed)
                return api
            except (ComfyError, ValueError, KeyError, OSError) as exc:
                logger.warning("default.json 템플릿 변환 실패 — Anima 번들 폴백: %s", exc)
        return build_anima_workflow(object_info, prompt, negative, width=width, height=height, seed=seed)

    async def txt2img(
        self,
        prompt: str,
        options: dict | None = None,
        on_progress: "Callable[[int], None] | None" = None,
    ) -> str:
        """프롬프트로 이미지를 생성해 base64 PNG(첫 장) 반환. 오류 시 ComfyError. SDClient 동일 인터페이스.

        유저 default 워크플로 템플릿에 긍정/부정/시드를 주입해 제출한다. on_progress 가 있으면
        ComfyUI /ws 진행 이벤트(패시브 푸시 — 모델을 건드리지 않아 MPS 안전)로 0~100 을 보고한다.
        """
        opts = options or {}
        negative = str(opts.get("negative_prompt", ""))
        width = int(opts.get("width", 1024) or 1024)
        height = int(opts.get("height", 1024) or 1024)
        seed = int(opts.get("seed", 0) or 0)
        try:
            async with aiohttp.ClientSession(timeout=self._timeout) as s:
                workflow = await self._build_graph(s, prompt, negative, width, height, seed)
                async with s.post(f"{self._base}/prompt", json={"prompt": workflow, "client_id": self._client_id}) as r:
                    sub = await r.json()
                prompt_id = sub.get("prompt_id") if isinstance(sub, dict) else None
                if not prompt_id:
                    raise ComfyError(f"ComfyUI 작업 제출 실패: {sub}")
                self._cur_prompt_id = str(prompt_id)
                self._mark_submitted(str(prompt_id))  # 전문가 리스너가 내 작업을 제외하도록
                prog_task = (
                    asyncio.create_task(self._stream_progress(str(prompt_id), on_progress)) if on_progress else None
                )
                try:
                    img = await self._await_image(s, str(prompt_id))
                finally:
                    if prog_task is not None:
                        prog_task.cancel()
                    self._cur_prompt_id = None
        except aiohttp.ClientError as exc:
            raise ComfyError(f"ComfyUI 연결 실패: {exc}") from exc
        return img

    async def _stream_progress(self, prompt_id: str, on_progress: "Callable[[int], None]") -> None:
        """ComfyUI /ws 를 구독해 샘플링 진행률(0~100)을 on_progress 로 보고(best-effort).

        ``progress``(value/max) 와 ``executing`` 푸시만 읽는다 — 서버가 능동적으로 보내는 패시브
        스트림이라 모델을 폴링하지 않는다(A1111 /progress 폴링형 MPS 크래시와 무관).
        """
        url = f"{self._base.replace('http', 'ws', 1)}/ws?clientId={self._client_id}"
        try:
            async with aiohttp.ClientSession() as s, s.ws_connect(url, heartbeat=20) as ws:
                async for msg in ws:
                    if msg.type != aiohttp.WSMsgType.TEXT:
                        continue
                    try:
                        ev = json.loads(msg.data)
                    except ValueError:
                        continue
                    if ev.get("type") != "progress":
                        continue
                    data = ev.get("data") or {}
                    if data.get("prompt_id") and data["prompt_id"] != prompt_id:
                        continue
                    mx = data.get("max") or 0
                    val = data.get("value") or 0
                    if mx:
                        pct = max(0, min(100, int(val * 100 / mx)))
                        try:
                            on_progress(pct)
                        except Exception:  # noqa: BLE001
                            pass
        except asyncio.CancelledError:
            raise
        except Exception as exc:  # noqa: BLE001 - 진행률은 best-effort(실패해도 생성엔 영향 없음)
            logger.debug("ComfyUI 진행률 스트림 종료: %s", exc)

    async def interrupt(self) -> bool:
        """진행 중 작업을 취소(ComfyUI /interrupt + 큐에서 제거). 취소 시도 성공 여부."""
        ok = False
        try:
            async with aiohttp.ClientSession(timeout=aiohttp.ClientTimeout(total=10)) as s:
                async with s.post(f"{self._base}/interrupt") as r:
                    ok = r.status == 200
                pid = self._cur_prompt_id
                if pid:
                    try:
                        async with s.post(f"{self._base}/queue", json={"delete": [pid]}):
                            pass
                    except aiohttp.ClientError:
                        pass
        except aiohttp.ClientError as exc:
            logger.warning("ComfyUI 취소 실패: %s", exc)
            return False
        return ok

    def _mark_submitted(self, prompt_id: str) -> None:
        """앱(/그림) 제출 prompt_id 기록(상한 FIFO — 무한 누적 방지)."""
        if prompt_id in self._submitted_ids:
            return
        self._submitted_ids.add(prompt_id)
        self._submitted_order.append(prompt_id)
        while len(self._submitted_order) > 512:
            self._submitted_ids.discard(self._submitted_order.pop(0))

    async def listen_user_images(self, on_image: "Callable[[bytes], Any]", poll_interval: float = 3.0) -> None:
        """ComfyUI /history 를 폴링해 **유저가 웹에서 직접 생성한** 이미지를 감지(전문가 층).

        ComfyUI 는 executed/progress 를 **제출한 client_id 의 ws 로만** 보내므로(브로드캐스트 아님),
        브라우저로 생성한 작업은 에이전트 ws 에 안 온다. 그래서 /history 를 폴링한다(P1 에서 history
        폴링은 MPS 안전 입증). 앱(/그림) 제출 prompt_id(self._submitted_ids)는 제외하고, **시작 시점의
        기존 history 는 baseline 으로 무시**(과거 이미지 폭주 방지). 새로 완료된 것만 on_image(png) 호출.
        """
        seen: set[str] = set()
        seen_order: list[str] = []

        def _mark_seen(pid: str) -> None:
            if pid in seen:
                return
            seen.add(pid)
            seen_order.append(pid)
            while len(seen_order) > 1024:
                seen.discard(seen_order.pop(0))

        baseline = True
        async with aiohttp.ClientSession() as s:
            while True:
                try:
                    async with s.get(f"{self._base}/history", params={"max_items": 64}) as r:
                        hist = await r.json()
                except (aiohttp.ClientError, ValueError) as exc:
                    logger.debug("history 폴링 실패: %s", exc)
                    await asyncio.sleep(poll_interval)
                    continue
                if isinstance(hist, dict):
                    for pid, entry in hist.items():
                        if pid in seen or pid in self._submitted_ids or not isinstance(entry, dict):
                            continue
                        refs = _all_image_refs(entry)  # 워크플로의 모든 출력 이미지(다중 SaveImage 지원)
                        if not refs:
                            _mark_seen(pid)  # 종료-무이미지(실패/취소) → 재확인 방지
                            continue
                        if baseline:
                            _mark_seen(pid)  # 시작 시점 기존 이미지는 포워드하지 않음
                            continue
                        ok = await self._forward_refs(s, refs, on_image)
                        if ok:
                            _mark_seen(pid)  # **성공 시에만** seen — 실패는 다음 폴링에서 재시도(max_items 창 내 자연 만료)
                baseline = False
                await asyncio.sleep(poll_interval)

    async def _forward_refs(
        self,
        s: aiohttp.ClientSession,
        refs: list[tuple[str, str, str]],
        on_image: "Callable[[bytes], Any]",
    ) -> bool:
        """이미지 ref 들을 받아 on_image 로 넘긴다. 하나라도 실패하면 False(재시도 대상)."""
        for fn, sub, typ in refs:
            try:
                async with s.get(f"{self._base}/view", params={"filename": fn, "subfolder": sub, "type": typ}) as ir:
                    raw = await ir.read()
                res = on_image(raw)
                if asyncio.iscoroutine(res):
                    await res
            except (aiohttp.ClientError, OSError) as exc:
                logger.warning("유저 생성 이미지 포워드 실패(다음 폴링에서 재시도): %s", exc)
                return False
        return True

    async def _await_image(self, s: aiohttp.ClientSession, prompt_id: str) -> str:
        """history 를 폴링해 완료된 이미지(첫 장)를 받아 base64 PNG 로 반환."""
        deadline = self._timeout.total or 180.0
        waited = 0.0
        while waited < deadline:
            async with s.get(f"{self._base}/history/{prompt_id}") as r:
                hist = await r.json()
            entry = hist.get(prompt_id) if isinstance(hist, dict) else None
            if entry:
                # history 엔트리는 작업이 **종료된 뒤에만** 생긴다. 이미지가 있으면 성공, 없으면
                # 종료-무이미지 = 취소(/interrupt)·실패 → 타임아웃까지 폴링하지 말고 즉시 중단.
                images = _first_image_ref(entry)
                if images is not None:
                    fn, sub, typ = images
                    async with s.get(f"{self._base}/view", params={"filename": fn, "subfolder": sub, "type": typ}) as ir:
                        raw = await ir.read()
                    return base64.b64encode(raw).decode("ascii")
                status = entry.get("status") if isinstance(entry, dict) else None
                if isinstance(status, dict) and status.get("completed") is False:
                    raise ComfyError("이미지 생성이 취소/중단되었습니다")
                raise ComfyError("ComfyUI 가 이미지를 생성하지 못했습니다(워크플로 오류 가능)")
            await asyncio.sleep(1.0)
            waited += 1.0
        raise ComfyError("ComfyUI 생성 시간 초과")


def _first_image_ref(entry: dict) -> tuple[str, str, str] | None:
    """history 항목에서 첫 출력 이미지의 (filename, subfolder, type). 없으면 None."""
    outputs = entry.get("outputs") if isinstance(entry, dict) else None
    if not isinstance(outputs, dict):
        return None
    for node in outputs.values():
        imgs = node.get("images") if isinstance(node, dict) else None
        if isinstance(imgs, list) and imgs and isinstance(imgs[0], dict):
            i = imgs[0]
            return (str(i.get("filename", "")), str(i.get("subfolder", "")), str(i.get("type", "output")))
    return None


def _all_image_refs(entry: dict) -> list[tuple[str, str, str]]:
    """history 항목의 **모든** 출력 이미지 (filename, subfolder, type). 다중 SaveImage 노드 지원."""
    outputs = entry.get("outputs") if isinstance(entry, dict) else None
    if not isinstance(outputs, dict):
        return []
    refs: list[tuple[str, str, str]] = []
    for node in outputs.values():
        imgs = node.get("images") if isinstance(node, dict) else None
        if not isinstance(imgs, list):
            continue
        for i in imgs:
            if isinstance(i, dict) and i.get("filename"):
                # temp(미저장 프리뷰)는 제외하고 저장된 출력만 포워드.
                if str(i.get("type", "output")) == "temp":
                    continue
                refs.append((str(i.get("filename", "")), str(i.get("subfolder", "")), str(i.get("type", "output"))))
    return refs
