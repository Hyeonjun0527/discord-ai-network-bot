"""Policy v1 Model Card 자동 생성(NEXA-P11-T024).

학습 데이터, 지표, calibration, 실패 유형, 사용 금지(금지 추론), artifact hash 를 기록한다. 수치는
**manifest 에서 자동 삽입** 한다(수동 드리프트 금지). LIVE 승인 전제와 known limitations 를 구체적으로 명시한다.

**acceptance(T024) — LIVE 승인 전제와 known limitations 가 구체적이다**:
- [render_model_card] 가 [PolicyModelManifest] 에서 dataset_id·artifact hash·feature schema·calibration·metrics 를
  자동으로 채운다(손으로 적지 않는다).
- 금지 추론(observable-state-policy), 실패 유형, LIVE 승인 전제(parity·generalization·calibration 기준)와
  known limitations 를 정적 정책 텍스트로 포함한다.
"""

from __future__ import annotations

import hashlib
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any


@dataclass(frozen=True)
class PolicyModelManifest:
    """정책 모델 manifest(자동 집계 — 수동 수치 드리프트 금지).

    parity fixture·학습 구성에서 결정론적으로 파생된다. 수치(metrics)는 평가 파이프라인이 채운다.
    """

    model_id: str
    model_version: str
    dataset_id: str
    artifact_sha256: str
    feature_schema_version: int
    calibration_version: str
    opset: int
    metrics: dict[str, float] = field(default_factory=dict)

    def to_dict(self) -> dict[str, Any]:
        return {
            "model_id": self.model_id,
            "model_version": self.model_version,
            "dataset_id": self.dataset_id,
            "artifact_sha256": self.artifact_sha256,
            "feature_schema_version": self.feature_schema_version,
            "calibration_version": self.calibration_version,
            "opset": self.opset,
            "metrics": dict(self.metrics),
        }


def manifest_from_fixture(
    onnx_path: Path,
    *,
    model_id: str,
    model_version: str,
    dataset_id: str,
    feature_schema_version: int,
    calibration_version: str,
    opset: int,
    metrics: dict[str, float],
) -> PolicyModelManifest:
    """fixture ONNX artifact 의 sha256 을 자동 계산해 manifest 를 만든다(hash 수동 입력 금지)."""
    sha = hashlib.sha256(onnx_path.read_bytes()).hexdigest()
    return PolicyModelManifest(
        model_id=model_id,
        model_version=model_version,
        dataset_id=dataset_id,
        artifact_sha256=sha,
        feature_schema_version=feature_schema_version,
        calibration_version=calibration_version,
        opset=opset,
        metrics=dict(metrics),
    )


# 정적 정책 텍스트(출처·금지 추론·실패 유형·LIVE 전제·한계). 합성 fixture 기준 SSOT.
_DATA_SOURCE = (
    "운영 데이터 미접근. 합성 fixture(`nexa_policy.datasets.make_synthetic_dataset`, seed 결정론)로 학습한다 — "
    "P10 라벨 의미(action/target/delay/burst/social_act)와 P08 feature 카탈로그를 미러하되 실제 메시지/식별자는 없다. "
    "실제 학습 시에도 동의(opt-in)·관찰 가능 신호만(P10 export 경계) 사용한다."
)
_FORBIDDEN_INFERENCE = (
    "observable-state-policy(P09) 위반 추론을 **사용 금지** 한다: 내면 상태·정체성·민감 속성"
    "(정치/종교/건강/성적지향 등) 추론, 원문 텍스트·실제 식별자 입력, 특정 member ID 를 feature 로 직접 사용. "
    "feature 는 관찰/집계 신호(OBSERVABLE/AGGREGATE)뿐이다(FeatureCatalog)."
)
_FAILURE_MODES = (
    "- 소형/저tempo 또는 특정 언어 길드에서 평균은 좋아도 붕괴할 수 있다(서버 간 일반화 분석, T022). "
    "최악 부분군이 floor 미만이면 채택 금지.\n"
    "- 클래스 불균형(다수 IGNORE)으로 SPEAK recall 이 낮을 수 있다(FIR/MIR 모니터링).\n"
    "- calibration 미보정 시 확률이 과신될 수 있다(ECE/Brier 로 검증, P09 calibration 일관).\n"
    "- 약지도 social act 라벨 노이즈로 act head 신뢰도가 낮을 수 있다(low-confidence weight 제외)."
)
_LIVE_PREREQUISITES = (
    "- **Python-JVM parity**: 같은 ONNX·입력에서 head 출력이 허용오차(1e-4) 내 일치(T019 golden parity 통과).\n"
    "- **baseline 대비 개선**: P09 baseline 들보다 balanced accuracy·FIR/MIR 가 의미 있게 낫다.\n"
    "- **일반화**: 최악 부분군이 collapse_floor 이상(평균만 좋은 기만 모델 아님, T022).\n"
    "- **calibration**: ECE/Brier 가 기준 이하(과신 아님).\n"
    "- **레지스트리 승인**: ShadowModelRegistry 에서 REGISTERED→SHADOW→APPROVED 를 거친 artifact 만 LIVE 자격"
    "(미승인 artifact LIVE 선택 불가, T020). 자동 승격 없음 — 독립 리뷰(human gate, T025) 필수.\n"
    "- **운영 적용은 별도**: 본 카드 작성·승인 기준 충족이 곧 배포가 아니다(ShadowMode CANARY→LIVE 는 길드별 승인)."
)
_KNOWN_LIMITATIONS = (
    "- fixture 는 합성이라 절대 성능 수치는 운영 일반화를 보장하지 않는다(상대 비교·파이프라인 검증용).\n"
    "- target head 는 추상 후보 슬롯이라 JVM 어댑터는 장면 ID 부재 시 none 대상으로 복원한다"
    "(구체 대상 결정은 상위 단계).\n"
    "- 7일 shadow 비교(T021)·독립 리뷰(T025)는 운영 게이트라 본 산출물 범위 밖이다."
)


def _fmt_metrics(metrics: dict[str, float]) -> str:
    if not metrics:
        return "  - (평가 전 — 미기록)\n"
    return "".join(f"  - `{k}`: {v:.4f}\n" for k, v in sorted(metrics.items()))


def render_model_card(manifest: PolicyModelManifest) -> str:
    """manifest 수치를 자동 삽입한 Policy v1 Model Card(Markdown)를 생성한다."""
    return (
        f"# Model Card — Policy v1 (`{manifest.model_id}`)\n\n"
        "> 이 문서는 manifest 에서 자동 생성된다. 수동으로 수정하지 마라(수치 드리프트 금지, P11-T024).\n"
        "> 재생성: `nexa_policy.reporting.model_card.render_model_card`.\n\n"
        "## 식별 (Identity)\n"
        f"- model_id: `{manifest.model_id}`\n"
        f"- model_version: `{manifest.model_version}`\n"
        f"- dataset_id: `{manifest.dataset_id}`\n"
        f"- artifact_sha256: `{manifest.artifact_sha256}`\n"
        f"- feature_schema_version: `{manifest.feature_schema_version}`\n"
        f"- calibration_version: `{manifest.calibration_version}`\n"
        f"- onnx_opset: `{manifest.opset}`\n\n"
        "## 학습 데이터 (Training Data)\n"
        f"{_DATA_SOURCE}\n\n"
        "## 지표 (Metrics)\n"
        f"{_fmt_metrics(manifest.metrics)}\n"
        "## Calibration\n"
        f"- calibration_version: `{manifest.calibration_version}`. P09 calibration(EXP-talkativeness logit 보정)과 일관. "
        "ECE/Brier 로 과신 여부를 검증한다.\n\n"
        "## 실패 유형 (Failure Modes)\n"
        f"{_FAILURE_MODES}\n\n"
        "## 사용 금지 / 금지 추론 (Prohibited Use)\n"
        f"{_FORBIDDEN_INFERENCE}\n\n"
        "## LIVE 승인 전제 (Prerequisites for LIVE Approval)\n"
        f"{_LIVE_PREREQUISITES}\n\n"
        "## Known Limitations\n"
        f"{_KNOWN_LIMITATIONS}\n"
    )
