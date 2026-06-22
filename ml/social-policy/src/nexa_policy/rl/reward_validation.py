"""reward proxy validation(NEXA-P19-T012). 운영 데이터 미접근 — 합성 fixture·결정론. torch 미사용(numpy).

reward-contract.md(T011)의 각 proxy 축이 **블라인드 인간 평가**와 얼마나 일치하는지 상관·불일치를 분석한다.
상관이 낮은 proxy 는 RL 에 쓰지 않는다(acceptance T012 — reward hacking 경계). 인간 평가는 블라인드(정책
정체 모름)이고, 사용자 심리를 정답으로 강요하지 않는다(체감 평가 입력일 뿐).

핵심:
- [spearman_correlation]/[pearson_correlation]: proxy 점수와 인간 점수의 순위/선형 상관.
- [disagreement_rate]: proxy 와 인간이 "좋다/나쁘다" 부호가 갈리는 비율(상위/하위 판정 불일치).
- [validate_proxy]: 상관이 [min_correlation] 미만이거나 불일치가 높으면 **RL 사용 불가**로 판정한다.
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import TYPE_CHECKING

if TYPE_CHECKING:
    import numpy as np


def _ranks(x: np.ndarray) -> np.ndarray:
    """평균 순위(동률 평균). Spearman 용."""
    import numpy as np

    order = np.argsort(x, kind="mergesort")
    ranks = np.empty_like(order, dtype=np.float64)
    ranks[order] = np.arange(len(x), dtype=np.float64)
    # 동률은 평균 순위로 보정.
    _, inv, counts = np.unique(x, return_inverse=True, return_counts=True)
    sums = np.zeros(len(counts))
    np.add.at(sums, inv, ranks)
    mean_ranks = sums / counts
    return mean_ranks[inv]


def pearson_correlation(a: np.ndarray, b: np.ndarray) -> float:
    """Pearson 상관계수. 표준편차 0 이면 0.0(정의 불가 — 과신 금지)."""
    import numpy as np

    if a.shape != b.shape or a.size < 2:
        raise ValueError("두 점수 배열은 같은 모양이고 최소 2개 표본이어야 한다.")
    am = a - a.mean()
    bm = b - b.mean()
    denom = float(np.sqrt((am**2).sum()) * np.sqrt((bm**2).sum()))
    if denom == 0.0:
        return 0.0
    return float((am * bm).sum() / denom)


def spearman_correlation(a: np.ndarray, b: np.ndarray) -> float:
    """Spearman 순위 상관(순위 변환 후 Pearson)."""
    return pearson_correlation(_ranks(a), _ranks(b))


def disagreement_rate(proxy: np.ndarray, human: np.ndarray) -> float:
    """proxy 와 인간이 중앙값 기준 상/하위로 갈리는(부호 불일치) 표본 비율 [0,1].

    각 점수를 자기 중앙값 기준으로 above/below 로 이진화한 뒤 불일치 비율. 낮을수록 proxy 가 인간과 정합.
    """
    import numpy as np

    if proxy.shape != human.shape or proxy.size == 0:
        raise ValueError("두 점수 배열은 같은 모양이고 비어 있지 않아야 한다.")
    p_high = proxy >= np.median(proxy)
    h_high = human >= np.median(human)
    return float(np.mean(p_high != h_high))


@dataclass(frozen=True)
class ProxyValidationResult:
    """한 proxy 축의 검증 결과. usable=False 면 RL 에 쓰지 않는다."""

    proxy_name: str
    spearman: float
    pearson: float
    disagreement_rate: float
    usable: bool

    def to_dict(self) -> dict[str, object]:
        return {
            "proxy_name": self.proxy_name,
            "spearman": self.spearman,
            "pearson": self.pearson,
            "disagreement_rate": self.disagreement_rate,
            "usable": self.usable,
        }


def validate_proxy(
    *,
    proxy_name: str,
    proxy_scores: np.ndarray,
    human_scores: np.ndarray,
    min_correlation: float = 0.5,
    max_disagreement: float = 0.35,
) -> ProxyValidationResult:
    """proxy 점수 vs 블라인드 인간 점수로 상관·불일치를 분석하고 RL 사용 가부를 판정한다.

    usable = (Spearman ≥ min_correlation) AND (disagreement ≤ max_disagreement).
    상관이 낮은 proxy 는 usable=False → RL 에 쓰지 않는다(acceptance T012).
    """
    sp = spearman_correlation(proxy_scores, human_scores)
    pe = pearson_correlation(proxy_scores, human_scores)
    dis = disagreement_rate(proxy_scores, human_scores)
    usable = bool(sp >= min_correlation and dis <= max_disagreement)
    return ProxyValidationResult(
        proxy_name=proxy_name,
        spearman=sp,
        pearson=pe,
        disagreement_rate=dis,
        usable=usable,
    )
