#!/usr/bin/env python3
"""NEXA-P07-T024 — stale social-memory 평가 스크립트(experiment).

합성 golden fixture(`test-fixtures/nexa/memory/stale-memory-cases.yaml`)에 대해 baseline retrieval 규칙을
적용해 **stale usage rate**(써선 안 되는 기억을 retrieval 이 쓴 비율)를 다섯 축으로 측정한다:

- **change**: 변경(supersession). 현재 조회에 옛 사실(SUPERSEDED·validTo 지남)을 쓰면 stale.
- **delete**: 삭제(INVALIDATED). 출처 redaction 으로 무효화된 기억을 쓰면 stale.
- **conflict**: 모순(CONFLICTED). 근거 부족 보류 기억을 임의로 쓰면 stale.
- **joke**: 농담/비단정(modality≠ASSERTED) 또는 민감 추론(sensitive) 사실을 쓰면 stale.
- **scope**: cross-guild 누출. 다른 guild 기억을 이 guild prompt 에 쓰면 stale.

baseline retrieval 규칙(socialmemory 도메인의 MemoryRetrievalRanking·CandidatePromotionRule 와 동일 의미):
유효(status=ACTIVE 이고 valid-at 구간 안)·같은 guild scope·단정(ASSERTED)·비민감일 때만 "쓴다(use=True)".
규칙이 쓰기로 한 기억의 fixture 라벨(shouldUse)이 False 면 stale usage 1건.

각 실패는 case id·axis·source(기억 상태/구간/스코프/modality)·retrieval decision(asOf/요청 스코프)으로 재현
가능하게 출력한다(acceptance T024). 실제 운영 데이터·외부 API·DB 를 쓰지 않는다 — 합성 fixture 만 본다.

실행:
    python3 scripts/evaluate-social-memory.py            # 표 출력
    python3 scripts/evaluate-social-memory.py --json     # 기계 판독(JSON)
"""
from __future__ import annotations

import argparse
import json
import sys
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

import yaml

REPO_ROOT = Path(__file__).resolve().parents[1]
FIXTURE = REPO_ROOT / "test-fixtures" / "nexa" / "memory" / "stale-memory-cases.yaml"
SCHEMA = "nexa.memory-eval.v1"

RETRIEVABLE_STATUS = "ACTIVE"  # SUPERSEDED/CONFLICTED/INVALIDATED/EXPIRED 는 retrieval 제외(MemoryStatus.isRetrievable).


@dataclass(frozen=True)
class Case:
    id: str
    axis: str
    memory: dict[str, Any]
    retrieval: dict[str, Any]
    should_use: bool


@dataclass
class AxisResult:
    total: int = 0
    stale: int = 0
    failures: list[str] = field(default_factory=list)

    @property
    def stale_rate(self) -> float:
        return (self.stale / self.total) if self.total else 0.0


def load_cases() -> list[Case]:
    root = yaml.safe_load(FIXTURE.read_text(encoding="utf-8"))
    if root.get("schemaVersion") != SCHEMA:
        raise SystemExit(f"예상치 못한 eval fixture 스키마 버전: {root.get('schemaVersion')}")
    cases = []
    for c in root["cases"]:
        cases.append(
            Case(
                id=c["id"],
                axis=c["axis"],
                memory=c["memory"],
                retrieval=c["retrieval"],
                should_use=bool(c["shouldUse"]),
            )
        )
    return cases


def baseline_uses(case: Case) -> bool:
    """baseline retrieval 규칙: 유효·같은 scope·단정·비민감일 때만 쓴다(도메인 필터와 동일 의미)."""
    mem = case.memory
    ret = case.retrieval
    # 1) status 필터(MemoryStatus.isRetrievable == ACTIVE).
    if mem["status"] != RETRIEVABLE_STATUS:
        return False
    # 2) valid-at 필터(validFrom <= asOf < validTo, validTo None 이면 열린 구간).
    as_of = int(ret["asOfSec"])
    valid_from = int(mem["validFromSec"])
    valid_to = mem.get("validToSec")
    if as_of < valid_from:
        return False
    if valid_to is not None and as_of >= int(valid_to):
        return False
    # 3) scope 필터(같은 guild·요청 스코프 포함; cross-guild 금지).
    if mem["guild"] != ret["requestGuild"]:
        return False
    # 4) 농담/비단정·민감 추론 필터(승격 단계 차단의 retrieval 측 안전망).
    if mem.get("modality", "ASSERTED") != "ASSERTED":
        return False
    if bool(mem.get("sensitive", False)):
        return False
    return True


def reproduce(case: Case, used: bool) -> str:
    mem = case.memory
    ret = case.retrieval
    return (
        f"{case.id} [{case.axis}] used={used} shouldUse={case.should_use} "
        f"| source: id={mem['id']} status={mem['status']} "
        f"valid=[{mem['validFromSec']},{mem.get('validToSec')}) guild={mem['guild']} "
        f"modality={mem.get('modality', 'ASSERTED')} sensitive={mem.get('sensitive', False)} "
        f"| retrieval: asOf={ret['asOfSec']} requestGuild={ret['requestGuild']}"
    )


def evaluate(cases: list[Case]) -> dict[str, AxisResult]:
    results: dict[str, AxisResult] = {}
    for case in cases:
        ar = results.setdefault(case.axis, AxisResult())
        ar.total += 1
        used = baseline_uses(case)
        # stale usage: 규칙이 썼는데(used=True) 쓰면 안 되는(shouldUse=False) 경우.
        if used and not case.should_use:
            ar.stale += 1
            ar.failures.append(reproduce(case, used))
    return results


def render_table(results: dict[str, AxisResult], overall: AxisResult) -> str:
    lines = ["| axis | cases | stale | stale usage rate |", "| --- | ---: | ---: | ---: |"]
    for axis in sorted(results):
        ar = results[axis]
        lines.append(f"| {axis} | {ar.total} | {ar.stale} | {ar.stale_rate:.3f} |")
    lines.append(f"| **overall** | {overall.total} | {overall.stale} | {overall.stale_rate:.3f} |")
    if overall.failures:
        lines.append("")
        lines.append("재현 가능한 stale usage 실패:")
        for f in overall.failures:
            lines.append(f"  - {f}")
    return "\n".join(lines)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--json", action="store_true")
    args = parser.parse_args()

    cases = load_cases()
    results = evaluate(cases)
    overall = AxisResult()
    for ar in results.values():
        overall.total += ar.total
        overall.stale += ar.stale
        overall.failures.extend(ar.failures)

    if args.json:
        payload = {
            "fixture": str(FIXTURE.relative_to(REPO_ROOT)),
            "overall": {"cases": overall.total, "stale": overall.stale, "staleRate": overall.stale_rate},
            "byAxis": {
                axis: {"cases": ar.total, "stale": ar.stale, "staleRate": ar.stale_rate, "failures": ar.failures}
                for axis, ar in results.items()
            },
        }
        print(json.dumps(payload, ensure_ascii=False, indent=2))
    else:
        print(f"fixture: {FIXTURE.relative_to(REPO_ROOT)}  (cases={overall.total})")
        print(render_table(results, overall))

    return 0


if __name__ == "__main__":
    sys.exit(main())
