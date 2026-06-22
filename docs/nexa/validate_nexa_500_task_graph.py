#!/usr/bin/env python3
from __future__ import annotations

import json
import re
import sys
from collections import defaultdict, deque
from dataclasses import dataclass
from pathlib import Path
from typing import Any

import yaml

SCHEMA_PATH = Path(__file__).resolve().parent / "roadmap" / "task-schema.json"


@dataclass(frozen=True)
class SchemaContract:
    top_required: tuple[str, ...]
    program_required: tuple[str, ...]
    task_required: tuple[str, ...]
    task_id_pattern: re.Pattern[str]
    program_id_pattern: re.Pattern[str]
    status_values: frozenset[str]
    kind_values: frozenset[str]
    risk_values: frozenset[str]


def load_yaml(path: Path) -> dict[str, Any]:
    data = yaml.safe_load(path.read_text(encoding="utf-8"))
    if not isinstance(data, dict):
        raise ValueError("task graph root must be a mapping")
    return data


def load_schema_contract(path: Path) -> SchemaContract:
    schema = json.loads(path.read_text(encoding="utf-8"))
    defs = schema.get("$defs", {})
    task = defs.get("task", {})
    program = defs.get("program", {})
    task_properties = task.get("properties", {})
    program_properties = program.get("properties", {})
    return SchemaContract(
        top_required=tuple(schema.get("required", ())),
        program_required=tuple(program.get("required", ())),
        task_required=tuple(task.get("required", ())),
        task_id_pattern=re.compile(task_properties["id"]["pattern"]),
        program_id_pattern=re.compile(program_properties["id"]["pattern"]),
        status_values=frozenset(task_properties["status"]["enum"]),
        kind_values=frozenset(task_properties["kind"]["enum"]),
        risk_values=frozenset(task_properties["risk"]["enum"]),
    )


def is_non_empty_string_list(value: Any) -> bool:
    return isinstance(value, list) and all(isinstance(item, str) and item.strip() for item in value)


def add_schema_errors(data: dict[str, Any], contract: SchemaContract, errors: list[str]) -> None:
    for key in contract.top_required:
        if key not in data:
            errors.append(f"root: missing field {key}")

    programs = data.get("programs", [])
    if not isinstance(programs, list):
        errors.append("root: programs must be a list")
        programs = []
    tasks = data.get("tasks", [])
    if not isinstance(tasks, list):
        errors.append("root: tasks must be a list")
        tasks = []

    for program in programs:
        if not isinstance(program, dict):
            errors.append("program entry must be a mapping")
            continue
        program_id = program.get("id", "<missing>")
        for key in contract.program_required:
            if key not in program:
                errors.append(f"program {program_id}: missing field {key}")
        if "id" in program and not contract.program_id_pattern.fullmatch(str(program["id"])):
            errors.append(f"program {program_id}: invalid id format")
        if program.get("task_count") != 25:
            errors.append(f"program {program_id}: task_count must be 25")

    for task in tasks:
        if not isinstance(task, dict):
            errors.append("task entry must be a mapping")
            continue
        task_id = task.get("id", "<missing>")
        for key in contract.task_required:
            if key not in task:
                errors.append(f"{task_id}: missing field {key}")
        if "id" in task and not contract.task_id_pattern.fullmatch(str(task["id"])):
            errors.append(f"{task_id}: invalid id format")
        if "program" in task and not contract.program_id_pattern.fullmatch(str(task["program"])):
            errors.append(f"{task_id}: invalid program format")
        if task.get("status") not in contract.status_values:
            errors.append(f"{task_id}: invalid status {task.get('status')}")
        if task.get("kind") not in contract.kind_values:
            errors.append(f"{task_id}: invalid kind {task.get('kind')}")
        if task.get("risk") not in contract.risk_values:
            errors.append(f"{task_id}: invalid risk {task.get('risk')}")
        sequence = task.get("sequence")
        if not isinstance(sequence, int) or not 1 <= sequence <= 25:
            errors.append(f"{task_id}: sequence must be 1..25")
        depends_on = task.get("depends_on", [])
        if not isinstance(depends_on, list):
            errors.append(f"{task_id}: depends_on must be a list")
        for dep in depends_on if isinstance(depends_on, list) else []:
            if not contract.task_id_pattern.fullmatch(str(dep)):
                errors.append(f"{task_id}: invalid dependency id format {dep}")
        for key in ("recommended_paths", "verification"):
            if not is_non_empty_string_list(task.get(key)):
                errors.append(f"{task_id}: {key} must be a non-empty string list")
        if "verification_evidence" in task and not is_non_empty_string_list(task.get("verification_evidence")):
            errors.append(f"{task_id}: verification_evidence must be a non-empty string list")
        if task.get("status") == "VERIFIED" and not is_non_empty_string_list(task.get("verification_evidence")):
            errors.append(f"{task_id}: VERIFIED requires verification_evidence")
        if not isinstance(task.get("human_gate"), bool):
            errors.append(f"{task_id}: human_gate must be boolean")


def add_graph_errors(data: dict[str, Any], errors: list[str]) -> None:
    tasks = [task for task in data.get("tasks", []) if isinstance(task, dict)]
    programs = [program for program in data.get("programs", []) if isinstance(program, dict)]

    if len(tasks) != 500:
        errors.append(f"expected 500 tasks, got {len(tasks)}")
    if len(programs) != 20:
        errors.append(f"expected 20 programs, got {len(programs)}")

    ids = [task.get("id") for task in tasks]
    if len(set(ids)) != len(ids):
        errors.append("duplicate task IDs detected")
    by_id = {task["id"]: task for task in tasks if task.get("id")}

    for task in tasks:
        task_id = task.get("id", "<missing>")
        for dep in task.get("depends_on", []):
            if dep not in by_id:
                errors.append(f"{task_id}: missing dependency {dep}")

    indegree = {task_id: 0 for task_id in by_id}
    adj: defaultdict[str, list[str]] = defaultdict(list)
    for task in tasks:
        task_id = task.get("id")
        if task_id not in by_id:
            continue
        for dep in task.get("depends_on", []):
            if dep in by_id:
                adj[dep].append(task_id)
                indegree[task_id] += 1
    queue = deque([task_id for task_id, degree in indegree.items() if degree == 0])
    visited = 0
    while queue:
        node = queue.popleft()
        visited += 1
        for nxt in adj[node]:
            indegree[nxt] -= 1
            if indegree[nxt] == 0:
                queue.append(nxt)
    if visited != len(by_id):
        errors.append("dependency cycle detected")

    for program in programs:
        program_id = program.get("id")
        count = sum(1 for task in tasks if task.get("program") == program_id)
        if count != 25:
            errors.append(f"program {program_id} expected 25 tasks, got {count}")
        gate_id = f"NEXA-{program_id}-T025"
        gate = by_id.get(gate_id)
        if not gate or gate.get("kind") != "review" or not gate.get("human_gate"):
            errors.append(f"program {program_id} missing human review gate at T025")


def main() -> int:
    path = Path(sys.argv[1] if len(sys.argv) > 1 else Path(__file__).with_name("nexa_500_task_graph.yaml"))
    errors: list[str] = []
    try:
        data = load_yaml(path)
        contract = load_schema_contract(SCHEMA_PATH)
        add_schema_errors(data, contract, errors)
        add_graph_errors(data, errors)
    except (OSError, ValueError, KeyError, yaml.YAMLError, json.JSONDecodeError) as exc:
        errors.append(str(exc))

    if errors:
        print("INVALID")
        for error in errors:
            print(f"- {error}")
        return 1
    data = load_yaml(path)
    print(f"VALID: {len(data.get('tasks', []))} tasks, {len(data.get('programs', []))} programs, DAG acyclic")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
