#!/usr/bin/env python3
"""NEXA-P08-T024 정책 계약 golden fixture 의 Python loader.

Kotlin 역직렬화(PolicyContractGoldenTest.kt)와 **같은 JSON fixture**
(`contracts/policy/fixtures/policy-decision-response.golden.json`)를 읽어 와이어 형태(camelCase·안정 코드·
확률 합)를 검증한다. 한쪽(코드 또는 fixture)이 바뀌면 양측 중 하나가 실패해 drift 를 즉시 잡는다.

**acceptance(T024) — schema version 변경 시 golden diff 가 명시적으로 실패한다**: fixture 의 schemaVersion 이
EXPECTED_SCHEMA_VERSION 과 다르면 비-0 종료로 실패한다(조용한 호환 금지).
"""

from __future__ import annotations

import json
import sys
from pathlib import Path
from typing import Any

REPO_ROOT = Path(__file__).resolve().parents[1]
FIXTURE_PATH = REPO_ROOT / "contracts" / "policy" / "fixtures" / "policy-decision-response.golden.json"

EXPECTED_SCHEMA_VERSION = 1
ACTION_KINDS = {"ignore", "wait", "react", "speak", "cancel_pending"}
DELAY_BUCKETS = {"IMMEDIATE", "SHORT", "MEDIUM", "LONG", "NEVER"}
SOCIAL_ACTS = {
    "acknowledge", "agree", "disagree", "tease", "ask",
    "correct", "self_disclose", "change_topic", "unknown",
}
EPSILON = 1e-9


def _require_prob_sum(weights: dict[str, float], field: str) -> None:
    total = sum(weights.values())
    if abs(total - 1.0) > EPSILON:
        raise ValueError(f"{field} 확률 합은 1.0 이어야 한다: 합={total}")
    for key, value in weights.items():
        if not 0.0 <= value <= 1.0:
            raise ValueError(f"{field}.{key} 확률은 [0,1] 범위여야 한다: {value}")


def validate(data: dict[str, Any]) -> None:
    schema_version = data.get("schemaVersion")
    if schema_version != EXPECTED_SCHEMA_VERSION:
        raise ValueError(
            f"정책 응답 fixture schema version 불일치: 기대 {EXPECTED_SCHEMA_VERSION}, 실제 {schema_version} (golden diff)"
        )

    action_weights = data["actionWeights"]
    unknown_actions = set(action_weights) - ACTION_KINDS
    if unknown_actions:
        raise ValueError(f"미지 action kind: {unknown_actions}")
    _require_prob_sum(action_weights, "actionWeights")

    target = data["targetDistribution"]
    candidate_sum = sum(c["probability"] for c in target["candidates"])
    total = candidate_sum + target["noneProbability"]
    if abs(total - 1.0) > EPSILON:
        raise ValueError(f"targetDistribution 확률 합은 1.0 이어야 한다: 합={total}")
    if not target["resolverVersion"]:
        raise ValueError("resolverVersion 은 비어 있을 수 없다")

    delay_weights = data["delayDistribution"]["weights"]
    unknown_buckets = set(delay_weights) - DELAY_BUCKETS
    if unknown_buckets:
        raise ValueError(f"미지 delay bucket: {unknown_buckets}")
    _require_prob_sum(delay_weights, "delayDistribution.weights")

    social_acts = data.get("socialActWeights", {})
    unknown_acts = set(social_acts) - SOCIAL_ACTS
    if unknown_acts:
        raise ValueError(f"미지 social act: {unknown_acts}")
    if social_acts:
        _require_prob_sum(social_acts, "socialActWeights")

    burst = data["burstProfile"]
    fragment_weights = {int(k): v for k, v in burst["fragmentCountWeights"].items()}
    if any(k < 1 for k in fragment_weights):
        raise ValueError("조각 수는 1 이상이어야 한다")
    _require_prob_sum({str(k): v for k, v in fragment_weights.items()}, "burstProfile.fragmentCountWeights")
    if burst["maxFragmentLength"] < 1:
        raise ValueError("maxFragmentLength 는 양수여야 한다")
    if burst["gapLowerBoundSeconds"] > burst["gapUpperBoundSeconds"]:
        raise ValueError("gapLowerBound 는 gapUpperBound 보다 클 수 없다")

    if not 0.0 <= data["uncertainty"] <= 1.0:
        raise ValueError("uncertainty 는 [0,1] 범위여야 한다")
    if not data["modelVersion"]:
        raise ValueError("modelVersion 은 비어 있을 수 없다")


def main() -> int:
    if not FIXTURE_PATH.exists():
        print(f"[fail] 정책 golden fixture 부재: {FIXTURE_PATH}", file=sys.stderr)
        return 1
    data = json.loads(FIXTURE_PATH.read_text(encoding="utf-8"))
    try:
        validate(data)
    except (ValueError, KeyError) as exc:
        print(f"[fail] {FIXTURE_PATH.name}: {exc}", file=sys.stderr)
        return 1
    print(f"[ok] {FIXTURE_PATH.name} (schemaVersion={EXPECTED_SCHEMA_VERSION})")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
