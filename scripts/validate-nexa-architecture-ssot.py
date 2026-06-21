#!/usr/bin/env python3
"""NEXA 아키텍처 SSOT 일치 검사 (P01-T024).

ai-context/domain.json 의 nexaContexts 와 docs/nexa/architecture 의 바운디드 컨텍스트 계약
문서가 서로 정확히 일치하는지 검증한다 — 문서와 JSON 의 모듈 목록·책임이 드리프트하지 않게 한다.

검사:
1. nexaContexts.contexts 의 각 항목이 필수 필드(id, role, owns, forbids, contract)를 갖는다.
2. 컨텍스트 id 집합이 정확히 기대 6개와 일치한다(누락·오타·추가 금지).
3. 각 contract 파일이 실제로 존재한다.
4. docs/nexa/architecture 의 컨텍스트 계약 문서(*-context.md + discord-adapter-boundary.md)가
   정확히 nexaContexts.contracts 집합과 일치한다(고아 문서 / 미선언 문서 금지).
5. adr 목록과 moduleDag 파일이 존재한다.

불일치가 하나라도 있으면 비-0 으로 종료한다.
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[1]
DOMAIN_JSON = REPO_ROOT / "ai-context" / "domain.json"
ARCH_DIR = REPO_ROOT / "docs" / "nexa" / "architecture"

EXPECTED_IDS = {
    "conversation",
    "participation",
    "socialmemory",
    "speech",
    "actionruntime",
    "platform-discord",
}
REQUIRED_FIELDS = ("id", "role", "owns", "forbids", "contract")


def fail(errors: list[str]) -> None:
    print("INVALID: NEXA architecture SSOT 불일치", file=sys.stderr)
    for err in errors:
        print(f"  - {err}", file=sys.stderr)
    sys.exit(1)


def main() -> None:
    errors: list[str] = []
    domain = json.loads(DOMAIN_JSON.read_text(encoding="utf-8"))

    nexa = domain.get("nexaContexts")
    if not isinstance(nexa, dict):
        fail(["ai-context/domain.json 에 nexaContexts 객체가 없다"])

    contexts = nexa.get("contexts")
    if not isinstance(contexts, list) or not contexts:
        fail(["nexaContexts.contexts 가 비어 있거나 리스트가 아니다"])

    ids: list[str] = []
    declared_contracts: set[str] = set()
    for index, ctx in enumerate(contexts):
        if not isinstance(ctx, dict):
            errors.append(f"contexts[{index}] 가 객체가 아니다")
            continue
        for field in REQUIRED_FIELDS:
            value = ctx.get(field)
            if not isinstance(value, str) or not value.strip():
                errors.append(f"contexts[{index}].{field} 누락/빈 값")
        ctx_id = ctx.get("id")
        if isinstance(ctx_id, str):
            ids.append(ctx_id)
        contract = ctx.get("contract")
        if isinstance(contract, str):
            declared_contracts.add(contract)
            if not (REPO_ROOT / contract).is_file():
                errors.append(f"contract 파일 없음: {contract} (id={ctx_id})")

    # 2. id 집합 일치
    id_set = set(ids)
    if len(ids) != len(id_set):
        errors.append(f"중복 컨텍스트 id 존재: {sorted(ids)}")
    missing = EXPECTED_IDS - id_set
    extra = id_set - EXPECTED_IDS
    if missing:
        errors.append(f"누락된 컨텍스트 id: {sorted(missing)}")
    if extra:
        errors.append(f"기대에 없는 컨텍스트 id: {sorted(extra)}")

    # 4. 문서 ↔ JSON 양방향 일치(고아 문서 / 미선언 문서 금지)
    on_disk = {
        f"docs/nexa/architecture/{p.name}"
        for p in ARCH_DIR.glob("*-context.md")
    }
    on_disk.add("docs/nexa/architecture/discord-adapter-boundary.md")
    orphan = on_disk - declared_contracts
    undeclared_present = declared_contracts - on_disk
    if orphan:
        errors.append(f"JSON 에 선언되지 않은 컨텍스트 계약 문서: {sorted(orphan)}")
    if undeclared_present:
        errors.append(
            f"JSON 이 가리키지만 컨텍스트 계약 목록과 안 맞는 경로: {sorted(undeclared_present)}"
        )

    # 5. adr / moduleDag 존재
    for adr in nexa.get("adr", []):
        if not isinstance(adr, str) or not (REPO_ROOT / adr).is_file():
            errors.append(f"adr 파일 없음: {adr}")
    module_dag = nexa.get("moduleDag")
    if not isinstance(module_dag, str) or not (REPO_ROOT / module_dag).is_file():
        errors.append(f"moduleDag 파일 없음: {module_dag}")

    if errors:
        fail(errors)

    print(
        f"NEXA architecture SSOT OK: {len(id_set)} contexts, "
        f"{len(declared_contracts)} contracts, domain.json ↔ docs/nexa/architecture 일치"
    )


if __name__ == "__main__":
    main()
