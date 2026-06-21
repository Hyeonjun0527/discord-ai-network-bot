"""Dataset Card 자동 생성(NEXA-P10-T024).

목적, 동의, 구성, 편향, 제한, 삭제, 재현 정보를 작성한다. 수치는 manifest 에서 자동 삽입한다.

**acceptance(T024) — 실제 manifest 수치가 자동 삽입되고 수동 수치 드리프트가 없다**:
- [render_dataset_card] 는 [DatasetManifest] 에서 row 수·길드 수·기간·class 분포·exclusions·hash 를 읽어
  Markdown 으로 자동 채운다. 수치를 손으로 적지 않는다(드리프트 0).
- 출처·한계·금지 추론·라이선스·삭제 권리는 정적 정책 텍스트로 포함한다(P09 observable-state-policy 일관).
"""

from __future__ import annotations

from nexa_policy.data.manifest import DatasetManifest

# 정적 정책 텍스트(출처·한계·금지 추론·라이선스·삭제). 합성 fixture 기준 SSOT.
_PURPOSE = (
    "NEXA participation 정책(SPEAK/REACT/WAIT/IGNORE·target·timing·social act)의 지도학습. "
    "사람처럼 '말할지 말지/언제/누구에게'를 결정하는 모델을 학습한다."
)
_CONSENT = (
    "opt-in 한 작성자의 관찰 가능한 행동 신호만 포함한다(masks.consent_opt_in). "
    "export 보안 경계(P10-T002)가 비동의·비관찰 행을 제외하며, consent_snapshot_id 로 동의 시점을 봉인한다."
)
_LIMITATIONS = (
    "- 원문/첨부/실제 식별자는 포함하지 않는다(가명·신호만, P10-T015 redaction).\n"
    "- 약지도 social act 라벨은 gold 가 아니다(confidence·model_version 동반, P10-T009).\n"
    "- 관찰 불가 구간은 UNKNOWN 으로 마스킹되어 강제 라벨링하지 않는다(P10-T005)."
)
_FORBIDDEN_INFERENCE = (
    "내면 상태·정체성·민감 속성(정치/종교/건강/성적지향 등) 추론을 금지한다. "
    "observable-state-policy(P09) 허용 신호 외 어떤 파생도 학습에 쓰지 않는다."
)
_DELETION = (
    "삭제 요청 시 해당 작성자/길드의 행을 재빌드에서 제외하고 dataset_id 를 재계산한다. "
    "immutable dataset_id 덕에 삭제 전/후 데이터셋이 구분된다(P10-T016)."
)
_LICENSE = "내부 전용(공개 불가). 합성 fixture 외 실제 운영 데이터 재배포 금지."


def _fmt_distribution(dist: dict[str, int]) -> str:
    if not dist:
        return "  - (없음)\n"
    return "".join(f"  - `{k}`: {v}\n" for k, v in sorted(dist.items()))


def _fmt_exclusions(excl: dict[str, int]) -> str:
    if not excl:
        return "  - (없음)\n"
    return "".join(f"  - `{k}`: {v}\n" for k, v in sorted(excl.items()))


def render_dataset_card(manifest: DatasetManifest) -> str:
    """manifest 수치를 자동 삽입한 Dataset Card(Markdown)를 생성한다."""
    period = (
        f"{manifest.period_start_ms} ~ {manifest.period_end_ms} (epoch ms)"
        if manifest.period_start_ms is not None
        else "(빈 데이터셋)"
    )
    return (
        f"# Dataset Card — `{manifest.dataset_id}`\n\n"
        "> 이 문서는 manifest 에서 자동 생성된다. 수동으로 수정하지 마라(수치 드리프트 금지, P10-T024).\n\n"
        "## 목적 (Purpose)\n"
        f"{_PURPOSE}\n\n"
        "## 동의 (Consent)\n"
        f"{_CONSENT}\n\n"
        "## 구성 (Composition)\n"
        f"- dataset_id: `{manifest.dataset_id}`\n"
        f"- schema_version: `{manifest.schema_version}`\n"
        f"- source_watermark: `{manifest.source_watermark}`\n"
        f"- code_commit: `{manifest.code_commit}`\n"
        f"- consent_snapshot_id: `{manifest.consent_snapshot_id}`\n"
        f"- row 수: **{manifest.row_count}**\n"
        f"- 길드 수: **{manifest.guild_count}**\n"
        f"- 기간: {period}\n"
        "- class 분포:\n"
        f"{_fmt_distribution(manifest.class_distribution)}"
        "\n## 편향·제외 (Bias / Exclusions)\n"
        "- 제외 내역:\n"
        f"{_fmt_exclusions(manifest.exclusions)}"
        "- 길드 단위 split 으로 소수 활성 길드가 과대표집될 수 있다(편향 모니터링 필요).\n\n"
        "## 제한 (Limitations)\n"
        f"{_LIMITATIONS}\n\n"
        "## 금지 추론 (Forbidden Inference)\n"
        f"{_FORBIDDEN_INFERENCE}\n\n"
        "## 삭제 (Deletion / Right to be Forgotten)\n"
        f"{_DELETION}\n\n"
        "## 재현 (Reproducibility)\n"
        f"- content_hash: `{manifest.content_hash}`\n"
        "- 같은 source_watermark·schema·code_commit·consent_snapshot·config 로 같은 dataset_id 가 재생성된다.\n\n"
        "## 라이선스 (License)\n"
        f"{_LICENSE}\n"
    )
