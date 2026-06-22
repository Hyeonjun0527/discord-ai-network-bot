"""행동 분포 distance(NEXA-P16-T021).

사람과 정책 모델의 **행동 분포** 차이를 평균이 아니라 분포 수준(quantile / KS / EMD)에서 잰다.
평균만 같아도 분포 모양(버스트성·지연 꼬리)이 다르면 "사람답지 않다" — 이 모듈은 그 차이를 드러낸다
(acceptance: T021 — 평균만 비교하지 않고 quantile/KS/EMD 중 적절한 지표를 사용한다).

대상 분포(사람 vs 모델):
- burst count: 한 사람/모델이 짧은 창에 연속으로 낸 메시지(발화) 수의 분포.
- delay: 트리거 후 응답까지 지연(ms) 분포 — 즉답 과다/지연 꼬리.
- reaction ratio: 메시지 대비 reaction 비율 분포.
- mention non-response: 호명에 응답하지 않은 비율(사람도 항상 답하지 않는다).

지표:
- [ks_statistic]: 두 표본의 누적분포 최대 차이(Kolmogorov–Smirnov). 분포 모양 차이에 민감.
- [earth_movers_distance]: 1차원 EMD(=정렬 후 CDF 면적 차이). 값 척도 있는 분포(delay)에 적합.
- [quantile_gaps]: 지정 quantile 들에서 두 분포의 값 차이(꼬리/중앙값 분리).

stdlib 만 사용(결정론, 외부 의존 없음). 운영 데이터 미접근 — 합성 분포 전용.
"""

from __future__ import annotations

from collections.abc import Sequence


class DistanceError(ValueError):
    """distance 입력 불변식 위반."""


def _sorted(values: Sequence[float]) -> list[float]:
    if not values:
        raise DistanceError("empty sample has no distribution")
    return sorted(float(v) for v in values)


def ks_statistic(sample_a: Sequence[float], sample_b: Sequence[float]) -> float:
    """두 표본의 KS 통계량(경험 CDF 최대 절대차, 0..1). 분포가 같으면 0.

    평균이 같아도 분포 모양이 다르면 0 보다 크다.
    """
    a = _sorted(sample_a)
    b = _sorted(sample_b)
    grid = sorted(set(a) | set(b))

    def cdf(s: list[float], x: float) -> float:
        # s 는 정렬됨 — x 이하 비율.
        lo, hi = 0, len(s)
        while lo < hi:
            mid = (lo + hi) // 2
            if s[mid] <= x:
                lo = mid + 1
            else:
                hi = mid
        return lo / len(s)

    return max(abs(cdf(a, x) - cdf(b, x)) for x in grid) if grid else 0.0


def earth_movers_distance(sample_a: Sequence[float], sample_b: Sequence[float]) -> float:
    """1차원 EMD(Wasserstein-1). 두 경험 CDF 사이 면적. 값 척도가 있는 분포에 적합.

    같은 크기 표본이 아니어도 동작한다(공통 그리드에서 CDF 차이를 적분).
    """
    a = _sorted(sample_a)
    b = _sorted(sample_b)
    points = sorted(set(a) | set(b))
    if len(points) < 2:
        return 0.0

    def cdf(s: list[float], x: float) -> float:
        lo, hi = 0, len(s)
        while lo < hi:
            mid = (lo + hi) // 2
            if s[mid] <= x:
                lo = mid + 1
            else:
                hi = mid
        return lo / len(s)

    area = 0.0
    for i in range(len(points) - 1):
        x0, x1 = points[i], points[i + 1]
        diff = abs(cdf(a, x0) - cdf(b, x0))
        area += diff * (x1 - x0)
    return area


def quantile(sample: Sequence[float], q: float) -> float:
    """선형 보간 quantile(numpy.percentile 의 'linear' 와 동일 규칙). q in [0,1]."""
    if not 0.0 <= q <= 1.0:
        raise DistanceError("quantile q must be in [0, 1]")
    s = _sorted(sample)
    if len(s) == 1:
        return s[0]
    pos = q * (len(s) - 1)
    lo = int(pos)
    frac = pos - lo
    if lo + 1 >= len(s):
        return s[-1]
    return s[lo] + frac * (s[lo + 1] - s[lo])


def quantile_gaps(
    human: Sequence[float], model: Sequence[float], *, quantiles: Sequence[float] = (0.5, 0.9, 0.99)
) -> dict[float, float]:
    """지정 quantile 들에서 |model - human| 값 차이. 꼬리(0.9/0.99)에서 분포 분리를 드러낸다."""
    return {q: abs(quantile(model, q) - quantile(human, q)) for q in quantiles}


def reaction_ratio(messages: int, reactions: int) -> float:
    """메시지 대비 reaction 비율. messages=0 이면 0."""
    if messages < 0 or reactions < 0:
        raise DistanceError("counts must be >= 0")
    return reactions / messages if messages > 0 else 0.0


def mention_non_response_rate(mentions: int, responses: int) -> float:
    """호명 대비 무응답 비율(사람도 항상 답하지 않는다). mentions=0 이면 0."""
    if mentions < 0 or responses < 0:
        raise DistanceError("counts must be >= 0")
    if mentions == 0:
        return 0.0
    answered = min(responses, mentions)
    return (mentions - answered) / mentions


def behavior_distance_report(
    *,
    human_burst_counts: Sequence[float],
    model_burst_counts: Sequence[float],
    human_delays_ms: Sequence[float],
    model_delays_ms: Sequence[float],
) -> dict[str, float]:
    """사람 vs 모델 행동 분포의 요약 distance 리포트(평균이 아닌 분포 지표).

    burst count 는 분포 모양(KS), delay 는 값 척도(EMD)와 꼬리(quantile gap)로 잰다.
    """
    burst_ks = ks_statistic(human_burst_counts, model_burst_counts)
    delay_emd = earth_movers_distance(human_delays_ms, model_delays_ms)
    delay_q = quantile_gaps(human_delays_ms, model_delays_ms)
    return {
        "burst_ks": burst_ks,
        "delay_emd": delay_emd,
        "delay_p50_gap": delay_q[0.5],
        "delay_p90_gap": delay_q[0.9],
        "delay_p99_gap": delay_q[0.99],
    }
