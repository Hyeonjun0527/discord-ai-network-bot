"""이미지 생성 백엔드 공통 계약(SSOT).

이미지 백엔드는 **덕타이핑**으로 묶여 있다 — ComfyClient(로컬)·StabilityClient·RunPodClient 가
모두 같은 ``txt2img(prompt, options, on_progress) -> base64 PNG`` · ``health() -> bool`` 을 제공해
``ProviderAgent._handle_image`` 가 백엔드와 무관하게 동작한다. 여기서는 그 계약을 명시(typing.Protocol)
하고, 모든 백엔드 오류의 **공통 베이스 예외**를 둔다.

공통 베이스가 필요한 이유: 에이전트의 생성 재시도 로직(_generate_image_with_retry)이 백엔드별 예외
(ComfyError/StabilityError/RunPodError)를 **한 번에** 잡아 사용자에게 InferError 로 변환하기 위함이다
(클라우드 백엔드 오류가 미처리로 새어나가는 것을 막는다).
"""
from __future__ import annotations

from typing import Any, Callable, Protocol, runtime_checkable


class ImageBackendError(Exception):
    """이미지 생성 백엔드 호출/응답 오류의 공통 베이스(ComfyError/StabilityError/RunPodError 의 상위).

    에이전트는 이 타입 하나로 모든 백엔드 실패를 잡아 사용자에게 전달한다.
    """


@runtime_checkable
class ImageBackend(Protocol):
    """이미지 생성 백엔드가 제공해야 하는 최소 인터페이스(덕타이핑 문서화).

    필수: ``txt2img`` · ``health``. 그 외는 선택(없으면 에이전트가 getattr 로 안전 폴백).
    """

    async def txt2img(
        self,
        prompt: str,
        options: dict | None = None,
        on_progress: "Callable[[int], None] | None" = None,
    ) -> str:
        """프롬프트로 이미지를 생성해 base64 PNG(첫 장) 반환. 실패 시 ImageBackendError."""
        ...

    async def health(self) -> bool:
        """백엔드가 즉시 생성 가능한지(capability 광고 판단용)."""
        ...


# ── 선택 메서드(클라우드 백엔드가 폴백으로 쓰는 no-op 기본 구현) ──────────────────────────
# ComfyUI 는 이 메서드들을 풍부히 구현하지만, 클라우드 백엔드(요청/응답형)는 대부분 무의미하다.
# 에이전트는 getattr 로 존재를 확인하므로, 클라우드 백엔드는 아래 믹스인을 상속해 호환만 맞춘다.
class CloudImageBackendMixin:
    """요청/응답형 클라우드 이미지 백엔드의 공통 호환 메서드.

    - 로컬 프로세스가 없으므로 ``interrupt`` 는 의미 없음(이미 전송된 HTTP 요청은 중단 불가) → False.
    - 체크포인트 핫스왑/목록 개념이 없으므로 SDClient 호환 stub 을 둔다(에이전트 호출이 깨지지 않게).
    - 해상도는 백엔드가 자체 결정하므로 ``default_resolution`` 으로 에이전트에 알린다(ComfyUI 의
      체크포인트 기반 해상도 추정을 우회 → 클라우드는 항상 자기 기본 해상도로 생성).
    """

    _model: str = ""
    _default_wh: tuple[int, int] = (1024, 1024)

    def default_resolution(self) -> tuple[int, int]:
        """이 백엔드가 생성하는 기본 해상도(에이전트 _resolution 이 우선 사용)."""
        return self._default_wh

    async def interrupt(self) -> bool:
        """클라우드 요청은 중간 취소가 불가능하다(no-op)."""
        return False

    async def set_output_png(self) -> bool:
        """SDClient 호환 no-op(클라우드는 항상 PNG 반환)."""
        return True

    async def current_checkpoint(self) -> str | None:
        """현재 모델 라벨(해상도 판정엔 default_resolution 이 우선)."""
        return self._model or None

    async def list_checkpoints(self) -> list[str]:
        """선택 가능한 모델 목록(기본은 현재 모델 1개)."""
        return [self._model] if self._model else []

    async def set_checkpoint(self, name: str) -> bool:
        """모델 전환(설치 개념이 없으므로 라벨만 교체)."""
        if name:
            self._model = name
            return True
        return False

    def _ensure_dims(self, options: dict | None) -> tuple[int, int]:
        """옵션의 width/height 가 유효하면 그것, 아니면 백엔드 기본 해상도.

        에이전트가 ComfyUI 체크포인트 추정에서 넘긴 512 폴백 등으로 품질이 떨어지지 않게,
        명시적으로 의미 있는 값(>=256)만 존중하고 그 외엔 기본값을 쓴다.
        """
        opts: dict[str, Any] = options or {}
        try:
            w = int(opts.get("width") or 0)
            h = int(opts.get("height") or 0)
        except (TypeError, ValueError):
            w = h = 0
        dw, dh = self._default_wh
        return (w if w >= 256 else dw, h if h >= 256 else dh)
