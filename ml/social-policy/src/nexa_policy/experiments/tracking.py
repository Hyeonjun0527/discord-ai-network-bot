"""실험 config·artifact 추적(NEXA-P11-T016).

한 run 에 dataset ID(P10 manifest), git commit, model config, metrics, artifact hash, 환경 캡처를
묶는다. 결과 파일(JSON)만으로 학습 명령을 재구성할 수 있어야 한다.

**acceptance(T016) — 결과 파일만으로 학습 명령을 재구성할 수 있다**:
- [ExperimentConfig] 는 학습 명령 재구성에 필요한 모든 인자(seed·epochs·lr·hidden_dim·weights·dataset_id)를
  담고, [config_digest] 로 안정 해시한다(P10 versioning 과 동일 방식).
- [RunRecord.to_dict] 는 config + metrics + artifact hashes + 환경 + [reconstruct_command] 를 담는다.
- [require_dataset_binding] 은 dataset_id 없는 run 을 거부한다(P10 require_manifest_id 와 일관).
"""

from __future__ import annotations

import json
from dataclasses import asdict, dataclass, field
from pathlib import Path
from typing import Any

from nexa_policy.data.versioning import stable_digest
from nexa_policy.reproducibility import EnvironmentCapture


class ExperimentError(ValueError):
    """실험 추적 불변식 위반(fail-closed)."""


@dataclass(frozen=True)
class ExperimentConfig:
    """학습 명령 재구성에 필요한 모든 인자(드리프트 금지)."""

    experiment: str
    dataset_id: str
    seed: int
    epochs: int
    learning_rate: float
    hidden_dim: int
    task_weights: dict[str, float] = field(default_factory=dict)
    extra: dict[str, Any] = field(default_factory=dict)

    def to_canonical(self) -> dict[str, Any]:
        return {
            "experiment": self.experiment,
            "dataset_id": self.dataset_id,
            "seed": self.seed,
            "epochs": self.epochs,
            "learning_rate": self.learning_rate,
            "hidden_dim": self.hidden_dim,
            "task_weights": dict(sorted(self.task_weights.items())),
            "extra": self.extra,
        }

    def config_digest(self) -> str:
        return stable_digest(self.to_canonical(), digest_size=16)


def reconstruct_command(config: ExperimentConfig) -> str:
    """결과 파일만으로 학습을 재현하는 CLI 명령 문자열을 만든다(재구성 가능성 보장)."""
    return (
        "python -m nexa_policy.experiments.run "
        f"--experiment {config.experiment} "
        f"--dataset-id {config.dataset_id} "
        f"--seed {config.seed} "
        f"--epochs {config.epochs} "
        f"--lr {config.learning_rate} "
        f"--hidden-dim {config.hidden_dim}"
    )


def artifact_hash(payload: Any) -> str:
    """모델 가중치/메트릭 등 임의 JSON-직렬화 payload 의 안정 해시(artifact 무결성)."""
    return stable_digest(payload, digest_size=20)


@dataclass(frozen=True)
class RunRecord:
    """한 실험 run 의 완전한 추적 레코드."""

    config: ExperimentConfig
    metrics: dict[str, Any]
    artifact_hashes: dict[str, str]
    environment: EnvironmentCapture
    code_commit: str

    def to_dict(self) -> dict[str, Any]:
        return {
            "config": self.config.to_canonical(),
            "config_digest": self.config.config_digest(),
            "metrics": self.metrics,
            "artifact_hashes": dict(self.artifact_hashes),
            "environment": self.environment.to_dict(),
            "code_commit": self.code_commit,
            "reconstruct_command": reconstruct_command(self.config),
        }

    def write(self, path: Path) -> Path:
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(
            json.dumps(self.to_dict(), indent=2, ensure_ascii=False, sort_keys=True),
            encoding="utf-8",
        )
        return path


def require_dataset_binding(config: ExperimentConfig) -> str:
    """dataset_id 없는 run 을 거부한다(P10 manifest 가드와 일관)."""
    if not config.dataset_id.strip():
        raise ExperimentError("dataset_id 없이 실험 run 을 기록할 수 없다(P10 require_manifest_id 일관).")
    return config.dataset_id


def load_run_record(path: Path) -> dict[str, Any]:
    """기록된 run JSON 을 로드한다(재구성·검증용)."""
    data: dict[str, Any] = json.loads(path.read_text(encoding="utf-8"))
    return data


# asdict 는 frozen dataclass 직렬화 보조로 노출(테스트 편의).
def config_as_dict(config: ExperimentConfig) -> dict[str, Any]:
    return asdict(config)
