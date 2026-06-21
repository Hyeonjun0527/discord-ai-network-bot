#!/usr/bin/env python3
"""NEXA 시나리오 DSL validator + 시뮬레이터 재생 게이트 (NEXA-P16-T002).

test-fixtures/nexa/scenarios/*.yaml 의 각 시나리오를:
1. DSL 구조(schemaVersion·필수 키·이벤트 target 무결성·존재하지 않는 message target)를 검증하고,
2. nexa-simulate.py 시뮬레이터로 결정론 재생한 뒤,
3. 기대 행동 invariant(과반응 안함·이미 답한 질문 침묵·먼저 안 나섬·stale 전송 0 등)를 검증한다.

acceptance(T002): schema 오류와 존재하지 않는 message target 이 검증에서 실패한다.
이 validator 는 nexa-verify.sh docs 에 묶여 CI 가드로 동작한다. 운영 데이터/전송/배포 없음(shadow).
"""
from __future__ import annotations

import importlib.util
import sys
from pathlib import Path
from types import ModuleType

import yaml

REPO = Path(__file__).resolve().parents[1]
SCENARIO_DIR = REPO / "test-fixtures" / "nexa" / "scenarios"
SCHEMA_PATH = SCENARIO_DIR / "schema.json"
SIMULATOR_PATH = REPO / "scripts" / "nexa-simulate.py"


def _load_simulator() -> ModuleType:
    spec = importlib.util.spec_from_file_location("nexa_simulate", SIMULATOR_PATH)
    if spec is None or spec.loader is None:
        raise RuntimeError(f"cannot load simulator at {SIMULATOR_PATH}")
    module = importlib.util.module_from_spec(spec)
    # dataclass introspection(Python 3.12+)이 모듈을 sys.modules 에서 찾으므로 exec 전에 등록한다.
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


def main() -> int:
    if not SCHEMA_PATH.exists():
        print(f"INVALID: missing scenario schema {SCHEMA_PATH.relative_to(REPO)}")
        return 1
    sim = _load_simulator()

    paths = sorted(SCENARIO_DIR.glob("*.yaml"))
    if not paths:
        print("INVALID: no NEXA scenarios found")
        return 1

    errors: list[str] = []
    total_decisions = 0
    seen_ids: set[str] = set()

    for path in paths:
        rel = path.relative_to(REPO)
        try:
            scenario = sim.load_scenario(path)
        except (OSError, sim.ScenarioError, yaml.YAMLError) as exc:
            errors.append(f"{rel}: load/schema error: {exc}")
            continue
        scenario_id = scenario["scenarioId"]
        if scenario_id in seen_ids:
            errors.append(f"{rel}: duplicate scenarioId {scenario_id}")
            continue
        seen_ids.add(scenario_id)

        result = sim.NexaSimulator(scenario).run()
        if result.sends != 0:
            errors.append(f"{rel}: shadow mode violated — sends={result.sends} (must be 0)")
        failures = sim.check_invariants(scenario, result)
        for f in failures:
            errors.append(f"{rel}: invariant failed: {f}")
        total_decisions += len(result.decisions)

    if errors:
        print("INVALID NEXA scenarios")
        for error in errors:
            print(f"- {error}")
        return 1

    print(
        f"NEXA scenarios OK: {len(paths)} files, {total_decisions} decisions replayed, "
        f"all invariants held, sends=0 (shadow)"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
