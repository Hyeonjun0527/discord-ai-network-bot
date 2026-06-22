#!/usr/bin/env python3
"""NEXA-P09-T020 talkativeness multiplier offline simulation(experiment).

실제 발화 없이(운영 DB·Discord 미연결) talkativeness multiplier(0.5/1.0/1.5/2.0)가 SPEAK 확률·action 분포를
어떻게 바꾸는지 **재생**한다. central 도메인 `PolicyCalibration`/`TalkativenessMultiplier` 의 보정 규칙을 그대로
따른다: SPEAK logit += ln(multiplier) 후 softmax 재정규화(메시지 개수 곱 아님 — T017 경계).

acceptance(T020): 낮은 기본 확률을 무리하게 높은 확률로 만드는지 **saturation curve** 가 나온다. multiplier 를
키워도 logit 가산은 오즈 배율이라, 기본 확률이 매우 낮으면 절대 확률 증가가 작고(saturation), 기본이 중간이면
크게 움직인다 — 이 비선형을 표로 보인다.

근거: docs/nexa/experiments/EXP-talkativeness.md,
central PolicyCalibration.kt / TalkativenessMultiplier.kt(같은 logit 규칙).

실행:
    python3 scripts/simulate-talkativeness.py            # 표 출력(결정론)
    python3 scripts/simulate-talkativeness.py --json     # 기계 판독(JSON)
"""
from __future__ import annotations

import argparse
import json
import math

# central TalkativenessMultiplier 와 동일한 상수.
MIN_LOGIT_ADJUSTMENT = -10.0  # multiplier 0 의 logit 보정 하한(강한 침묵 편향, -inf 방지).
MULTIPLIERS = (0.5, 1.0, 1.5, 2.0)
# 기본 SPEAK 확률 grid — 낮음/중간/높음. saturation 을 드러내려고 양 끝을 포함한다.
BASE_SPEAK_PROBS = (0.02, 0.05, 0.1, 0.25, 0.5, 0.75, 0.9)


def logit_adjustment(multiplier: float) -> float:
    """multiplier 의 SPEAK logit 가산 보정량(ln 배율). 0 이면 강한 음수로 클램프(central 과 동일)."""
    if multiplier <= 0.0:
        return MIN_LOGIT_ADJUSTMENT
    return max(math.log(multiplier), MIN_LOGIT_ADJUSTMENT)


def safe_logit(p: float) -> float:
    """확률 -> 로그(클램프로 +-inf 방지). central safeLogit 미러(softmax 입력 logit)."""
    return math.log(min(max(p, 1e-12), 1.0))


def adjusted_speak_prob(base_speak: float, multiplier: float) -> float:
    """SPEAK logit 에만 ln(multiplier)를 더하고 {SPEAK, NOT_SPEAK} 2항 softmax 로 확률 복원.

    central 은 다항(IGNORE/WAIT/REACT/SPEAK/...) softmax 지만, SPEAK 만 보정하므로 SPEAK vs 나머지의
    상대 오즈로 압축해도 SPEAK 확률은 동일하다(나머지 logit 불변 -> 합쳐 하나로 봐도 무방).
    """
    speak_logit = safe_logit(base_speak) + logit_adjustment(multiplier)
    rest_logit = safe_logit(1.0 - base_speak)
    m = max(speak_logit, rest_logit)
    e_speak = math.exp(speak_logit - m)
    e_rest = math.exp(rest_logit - m)
    return e_speak / (e_speak + e_rest)


def simulate() -> list[dict[str, object]]:
    rows: list[dict[str, object]] = []
    for base in BASE_SPEAK_PROBS:
        row: dict[str, object] = {"base_speak": base}
        adjusted = {}
        for mult in MULTIPLIERS:
            p = adjusted_speak_prob(base, mult)
            adjusted[str(mult)] = p
        row["adjusted_speak"] = adjusted
        # saturation 지표: 2.0 배율이 기본 대비 올린 절대 확률 증가량(작을수록 saturated).
        row["abs_gain_at_2x"] = adjusted["2.0"] - base
        rows.append(row)
    return rows


def render_table(rows: list[dict[str, object]]) -> str:
    header = "| base SPEAK | " + " | ".join(f"x{m}" for m in MULTIPLIERS) + " | abs gain @2x |"
    sep = "| ---: | " + " | ".join("---:" for _ in MULTIPLIERS) + " | ---: |"
    lines = [header, sep]
    for row in rows:
        adj = row["adjusted_speak"]  # type: ignore[assignment]
        cells = " | ".join(f"{adj[str(m)]:.3f}" for m in MULTIPLIERS)  # type: ignore[index]
        lines.append(f"| {row['base_speak']:.2f} | {cells} | {row['abs_gain_at_2x']:.3f} |")  # type: ignore[index]
    return "\n".join(lines)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--json", action="store_true", help="JSON 출력(기계 판독)")
    args = parser.parse_args()
    rows = simulate()
    if args.json:
        print(json.dumps({"multipliers": MULTIPLIERS, "rows": rows}, ensure_ascii=False, indent=2))
    else:
        print("talkativeness multiplier offline simulation (logit += ln(multiplier), softmax)")
        print(render_table(rows))
        print()
        print("해석: base SPEAK 가 매우 낮을수록(0.02) 2x 배율도 절대 확률 증가(abs gain)가 작다 = saturation.")
        print("      중간(0.25~0.5)에서 가장 크게 움직이고, 높은 base 는 다시 천장에 눌린다(양끝 saturation).")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
