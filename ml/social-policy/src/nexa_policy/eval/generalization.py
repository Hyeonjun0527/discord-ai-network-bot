"""서버 간 일반화 분석(NEXA-P11-T022). 운영 데이터 미접근 — 합성 fixture 전용·결정론.

길드(서버) 규모·tempo·언어 같은 **부분군(subgroup)** 별로 정책 모델 성능을 쪼개 본다. 평균 지표만으로는
"평균은 좋아졌지만 특정 문화/소형 길드에서 무너지는" 모델을 놓친다 — 이 모듈은 부분군별 성능과 **최악 부분군**,
그리고 평균↑·최악군 붕괴 패턴을 명시적으로 식별한다.

**acceptance(T022) — 평균만 좋아지고 특정 문화에서 붕괴하는 모델을 식별한다**:
- [evaluate_subgroups] 가 부분군별 balanced accuracy·FIR·표본 수를 낸다.
- [worst_subgroup] 이 최악 부분군을 돌려준다.
- [detect_collapse] 가 (전체 평균은 baseline 이상이나 어떤 부분군이 임계 이하로 붕괴)하는 모델을 True 로 표시한다.

부분군 키는 호출자가 길드 가명→그룹으로 매핑해 넘긴다(언어/규모/tempo 등 어떤 축이든 동일 분석). 매핑 자체는
합성 fixture 의 결정론 파생([derive_subgroups])으로 제공한다.
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import TYPE_CHECKING

from nexa_policy.datasets import ACTION_HEAD_CLASSES
from nexa_policy.metrics import balanced_accuracy, false_ignore_rate

if TYPE_CHECKING:
    import numpy as np

_IGNORE = ACTION_HEAD_CLASSES.index("ignore")
_SPEAK = ACTION_HEAD_CLASSES.index("speak")


@dataclass(frozen=True)
class SubgroupPerformance:
    """한 부분군의 성능 요약."""

    subgroup: str
    n: int
    balanced_accuracy: float
    false_ignore_rate: float


def evaluate_subgroups(
    *,
    subgroups: list[str],
    y_true: np.ndarray,
    y_pred: np.ndarray,
) -> list[SubgroupPerformance]:
    """부분군별 balanced accuracy·FIR·표본 수를 계산한다(부분군 이름 정렬, 결정론).

    UNKNOWN(-1) 라벨 샘플은 평가에서 제외한다(라벨 없는 행은 정확도 정의 불가).
    """
    import numpy as np

    if not (len(subgroups) == y_true.shape[0] == y_pred.shape[0]):
        raise ValueError("subgroups·y_true·y_pred 길이가 같아야 한다.")
    groups = np.asarray(subgroups)
    out: list[SubgroupPerformance] = []
    for name in sorted(set(subgroups)):
        mask = (groups == name) & (y_true >= 0)
        yt = y_true[mask]
        yp = y_pred[mask]
        out.append(
            SubgroupPerformance(
                subgroup=name,
                n=int(yt.shape[0]),
                balanced_accuracy=balanced_accuracy(yt, yp, n_classes=len(ACTION_HEAD_CLASSES)),
                false_ignore_rate=false_ignore_rate(
                    yt, yp, speak_class=_SPEAK, ignore_class=_IGNORE
                ),
            )
        )
    return out


def worst_subgroup(performances: list[SubgroupPerformance]) -> SubgroupPerformance:
    """balanced accuracy 가 가장 낮은 부분군(동률이면 이름 순). 빈 입력은 거부."""
    if not performances:
        raise ValueError("빈 성능 목록에서 최악 부분군을 고를 수 없다.")
    return min(performances, key=lambda p: (p.balanced_accuracy, p.subgroup))


@dataclass(frozen=True)
class CollapseVerdict:
    """평균↑·부분군 붕괴 판정 결과."""

    mean_balanced_accuracy: float
    worst: SubgroupPerformance
    improves_on_average: bool
    collapses_on_subgroup: bool

    @property
    def is_deceptive(self) -> bool:
        """평균은 baseline 이상이나 어떤 부분군이 붕괴 → 채택하면 특정 문화가 무너진다."""
        return self.improves_on_average and self.collapses_on_subgroup


def detect_collapse(
    performances: list[SubgroupPerformance],
    *,
    baseline_mean: float,
    collapse_floor: float,
) -> CollapseVerdict:
    """평균은 baseline 이상이나 최악 부분군이 [collapse_floor] 미만으로 붕괴하는지 판정한다.

    - mean_balanced_accuracy = 부분군 표본 가중 평균(전체 평균과 일치).
    - improves_on_average = 평균 ≥ baseline_mean.
    - collapses_on_subgroup = 최악 부분군 balanced accuracy < collapse_floor.
    """
    if not performances:
        raise ValueError("빈 성능 목록으로 붕괴를 판정할 수 없다.")
    total_n = sum(p.n for p in performances)
    if total_n == 0:
        raise ValueError("표본이 0 인 성능 목록으로 붕괴를 판정할 수 없다.")
    weighted = sum(p.balanced_accuracy * p.n for p in performances) / total_n
    worst = worst_subgroup(performances)
    return CollapseVerdict(
        mean_balanced_accuracy=weighted,
        worst=worst,
        improves_on_average=weighted >= baseline_mean,
        collapses_on_subgroup=worst.balanced_accuracy < collapse_floor,
    )


def derive_subgroups(guild_ids: list[str], *, axis: str = "size") -> list[str]:
    """합성 fixture 의 길드 가명에서 결정론적으로 부분군 라벨을 만든다(운영 데이터 미접근).

    실제로는 길드 메타(규모/tempo/언어)에서 그룹을 얻지만, fixture 에서는 가명 해시로 안정적인 그룹을 파생해
    분석 파이프라인을 결정론적으로 검증한다. axis 는 라벨 접두사로만 쓰여 여러 축을 같은 함수로 다룬다.
    """
    labels: list[str] = []
    for gid in guild_ids:
        # 가명 끝 숫자(없으면 길이)로 안정 버킷. 3개 그룹(small/mid/large 또는 언어 a/b/c).
        digits = "".join(ch for ch in gid if ch.isdigit())
        bucket = (int(digits) if digits else len(gid)) % 3
        labels.append(f"{axis}-{bucket}")
    return labels
