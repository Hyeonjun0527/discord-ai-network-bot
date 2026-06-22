#!/usr/bin/env python3
"""NEXA 적대적 평가 리포트 + 30일 시뮬레이션 CI 게이트 (NEXA-P16-T022/T024).

`nexa-verify.sh docs` 에 묶여, 두 산출물의 핵심 계약을 결정론으로 검증한다:

1. 적대 리포트(generate-nexa-eval-report.py):
   - 현재 시나리오로 리포트를 만들면 verdict=PASS·critical 실패 0.
   - **합성 critical 실패를 주입하면 verdict 가 FAIL 로 뒤집힌다**(acceptance T024: critical 하나 실패 시
     PASS 금지). 종합이 좋아도 critical 이 있으면 FAIL 임을 증명한다.

2. 30일 시뮬레이션(simulate-30day-member.py):
   - shadow sends=0(전송 없음).
   - acceptance(T022) 보고 항목(상태 크기·반복 문구·stale memory·점유율 drift)이 모두 산출된다.

운영 데이터/전송/배포 없음. 외부 의존 없음(stdlib).
"""
from __future__ import annotations

import importlib.util
import sys
from pathlib import Path
from types import ModuleType

REPO = Path(__file__).resolve().parents[1]
REPORT_GEN = REPO / "scripts" / "generate-nexa-eval-report.py"
SIM30 = REPO / "scripts" / "simulate-30day-member.py"


def _load(path: Path, name: str) -> ModuleType:
    spec = importlib.util.spec_from_file_location(name, path)
    if spec is None or spec.loader is None:
        raise RuntimeError(f"cannot load {path}")
    module = importlib.util.module_from_spec(spec)
    # dataclass introspection(Python 3.12+)이 모듈을 sys.modules 에서 찾으므로 exec 전에 등록한다.
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


def _check_report(errors: list[str]) -> None:
    gen = _load(REPORT_GEN, "nexa_eval_report")
    report = gen.build_report("ci")

    if report.verdict != "PASS":
        errors.append(f"report verdict expected PASS, got {report.verdict}")
    if report.critical_failure_count != 0:
        errors.append(f"report critical failures expected 0, got {report.critical_failure_count}")
    if not report.results:
        errors.append("report has no scenario results")
    if not report.weakness_counts:
        errors.append("report missing weakness axis aggregation")

    # acceptance(T024): critical 실패를 주입하면 verdict 가 FAIL 로 뒤집혀야 한다(종합 무관).
    if report.results:
        victim = report.results[0]
        victim.failures = ["no_stale_send: sends=1 (shadow must be 0)"]
        victim.critical_failures = [f for f in victim.failures if gen._is_critical(f)]
        if not victim.critical_failures:
            errors.append("injected no_stale_send not recognized as critical")
        if report.verdict != "FAIL":
            errors.append("critical failure did not force verdict=FAIL (T024 acceptance violated)")

    # 비-critical 실패만으로도 PASS 가 아니어야 한다(전체 실패가 있으면 FAIL).
    fresh = gen.build_report("ci2")
    if fresh.results:
        fresh.results[0].failures = ["max_react_count: reacts=3 > 1"]
        fresh.results[0].critical_failures = []
        if fresh.verdict != "FAIL":
            errors.append("non-critical failure should still yield FAIL verdict")
        if fresh.critical_failure_count != 0:
            errors.append("non-critical failure miscounted as critical")


def _check_30day(errors: list[str]) -> None:
    sim = _load(SIM30, "nexa_sim30")
    report = sim.simulate(days=30, seed=30001)

    if report.sends != 0:
        errors.append(f"30-day sim shadow violated: sends={report.sends}")
    # acceptance(T022) 보고 항목 존재.
    d = report.to_dict()
    for key in ("stateSizeBytes", "topRepeatedPhrases", "staleFactCount", "dominance",
                "dominanceDriftFlagged", "nicknameChanges", "relationshipScore"):
        if key not in d:
            errors.append(f"30-day report missing required field: {key}")
    if report.total_messages <= 0:
        errors.append("30-day sim produced no messages")
    # 결정론: 같은 seed 면 동일 결과.
    again = sim.simulate(days=30, seed=30001)
    if again.to_dict() != d:
        errors.append("30-day sim is not deterministic for same seed")


def main() -> int:
    errors: list[str] = []
    _check_report(errors)
    _check_30day(errors)
    if errors:
        print("INVALID NEXA eval report / 30-day sim")
        for e in errors:
            print(f"- {e}")
        return 1
    print("NEXA eval report OK: verdict=PASS, critical-fail forces FAIL, 30-day shadow sends=0")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
