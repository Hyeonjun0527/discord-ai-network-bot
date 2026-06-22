"""재현성·환경 캡처(NEXA-P11-T001).

모든 학습/평가 진입점이 같은 fixture+config 에서 같은 결과를 내도록 seed 와 결정론 설정을
한곳에 고정한다. 환경(Python/주요 라이브러리 버전)을 캡처해 run manifest(T016)에 봉인한다.

**acceptance(T001) — 동일 dataset/config 에서 핵심 metric 변동이 허용 범위 안이다**:
- [seed_everything] 은 Python `random`, numpy(설치 시), `PYTHONHASHSEED` 를 한 seed 로 고정한다.
- numpy 기반 모델은 [rng] 가 주는 `numpy.random.Generator` 만 쓰고 전역 상태에 의존하지 않는다(결정론).
- [capture_environment] 가 Python·numpy·sklearn·onnx 버전을 기록해 환경 드리프트를 감지 가능하게 한다.

무거운 의존(torch)을 끌어오지 않는다. numpy 는 ML 모델의 필수 의존이고, 없으면 명시적으로 거부한다.
"""

from __future__ import annotations

import importlib
import os
import platform
import random
from dataclasses import dataclass
from typing import TYPE_CHECKING, Any

if TYPE_CHECKING:
    import numpy as np

DEFAULT_SEED = 20260622

# run 환경 캡처에 기록할 라이브러리(있으면 버전, 없으면 None).
_CAPTURED_LIBRARIES = ("numpy", "scipy", "sklearn", "onnx", "onnxruntime")


class ReproducibilityError(RuntimeError):
    """재현성 전제 위반(fail-closed)."""


def require_numpy() -> Any:
    """numpy 를 import 한다. 없으면 명시적으로 거부한다(조용한 fallback 금지)."""
    try:
        import numpy as np
    except ImportError as exc:  # pragma: no cover - 환경 의존
        raise ReproducibilityError(
            "numpy 가 필요하다(`pip install -e .[ml]`). ML 모델은 numpy 위에서만 동작한다."
        ) from exc
    return np


def seed_everything(seed: int = DEFAULT_SEED) -> int:
    """Python `random`, `PYTHONHASHSEED`, numpy 전역 seed 를 한 seed 로 고정한다.

    모델 코드는 가능하면 [rng] 의 명시적 Generator 를 쓰되, 전역 의존 라이브러리(sklearn)를 위해
    전역 seed 도 함께 고정한다(이중 안전). 같은 seed → 같은 난수 흐름.
    """
    if seed < 0:
        raise ReproducibilityError(f"seed 는 음수일 수 없다: {seed}")
    os.environ["PYTHONHASHSEED"] = str(seed)
    random.seed(seed)
    try:
        import numpy as np

        np.random.seed(seed)
    except ImportError:  # pragma: no cover - 환경 의존
        pass
    return seed


def rng(seed: int = DEFAULT_SEED) -> np.random.Generator:
    """결정론적 numpy `Generator`(전역 상태 비의존). 모델 가중치 초기화 등에 쓴다."""
    np = require_numpy()
    return np.random.default_rng(seed)


@dataclass(frozen=True)
class EnvironmentCapture:
    """학습/평가 run 의 환경 스냅샷. run manifest(T016)에 봉인해 드리프트를 감지한다."""

    python_version: str
    platform: str
    seed: int
    libraries: dict[str, str | None]

    def to_dict(self) -> dict[str, Any]:
        return {
            "python_version": self.python_version,
            "platform": self.platform,
            "seed": self.seed,
            "libraries": dict(self.libraries),
        }


def _library_version(name: str) -> str | None:
    try:
        module = importlib.import_module(name)
    except ImportError:
        return None
    version = getattr(module, "__version__", None)
    return str(version) if version is not None else None


def capture_environment(seed: int = DEFAULT_SEED) -> EnvironmentCapture:
    """현재 환경(Python·플랫폼·주요 라이브러리 버전)과 seed 를 캡처한다."""
    return EnvironmentCapture(
        python_version=platform.python_version(),
        platform=platform.platform(),
        seed=seed,
        libraries={name: _library_version(name) for name in _CAPTURED_LIBRARIES},
    )
