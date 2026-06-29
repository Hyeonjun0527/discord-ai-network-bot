#!/usr/bin/env python3
"""Validate NEXA missed_intervention / false_interruption eval fixtures.

The fixtures are synthetic-only counterfactual proxy sets. This validator checks structure,
coverage, and whether each case's expected proxy matches the same observable rule used by
InterventionProxies:

- false_interruption = predicted SPEAK and a human action inside the immediate 3s window.
- missed_intervention = predicted IGNORE/WAIT and any human action inside the 120s window.
"""
from __future__ import annotations

import re
import sys
import json
from pathlib import Path
from typing import Any

REPO = Path(__file__).resolve().parents[1]
EVAL_DIR = REPO / "test-fixtures" / "nexa" / "evals"
SCHEMA_VERSION = "nexa.intervention-eval.v1"
IMMEDIATE_WINDOW_MS = 3_000
MAX_WINDOW_MS = 120_000

REQUIRED_TAGS = {
    "missed_intervention": {"direct_support_request", "repeated_call", "idle_gap", "ignored_question"},
    "false_interruption": {"human_to_human_conversation", "private_support", "rapid_dyad", "already_being_answered"},
}
ALLOWED_ACTIONS = {"SPEAK", "WAIT", "IGNORE", "REACT"}
ALLOWED_PROXIES = {"missed_intervention", "false_interruption", "none"}
REAL_ID_LIKE = re.compile(r"\b\d{15,21}\b")


def fail(errors: list[str], path: Path, message: str) -> None:
    errors.append(f"{path.relative_to(REPO)}: {message}")


def as_mapping(value: Any) -> dict[str, Any] | None:
    return value if isinstance(value, dict) else None


def as_list(value: Any) -> list[Any] | None:
    return value if isinstance(value, list) else None


def string_field(data: dict[str, Any], key: str) -> str | None:
    value = data.get(key)
    return value if isinstance(value, str) and value else None


def validate_case(
    *,
    path: Path,
    eval_set_id: str,
    case: dict[str, Any],
    seen_case_ids: set[str],
    covered_tags: set[str],
    positive_count: dict[str, int],
    negative_count: dict[str, int],
    errors: list[str],
) -> None:
    case_id = string_field(case, "caseId")
    if case_id is None:
        fail(errors, path, "case missing caseId")
        return
    if case_id in seen_case_ids:
        fail(errors, path, f"duplicate caseId {case_id}")
    seen_case_ids.add(case_id)

    tags = as_list(case.get("tags"))
    if not tags or not all(isinstance(tag, str) and tag for tag in tags):
        fail(errors, path, f"{case_id}: tags must be non-empty strings")
    else:
        covered_tags.update(tags)

    transcript = as_list(case.get("transcript"))
    if not transcript:
        fail(errors, path, f"{case_id}: transcript must be non-empty")
        return
    message_ids: set[str] = set()
    for index, event in enumerate(transcript):
        item = as_mapping(event)
        if item is None:
            fail(errors, path, f"{case_id}: transcript[{index}] must be a mapping")
            continue
        for key in ("messageId", "actorId", "text"):
            value = string_field(item, key)
            if value is None:
                fail(errors, path, f"{case_id}: transcript[{index}] missing {key}")
            elif REAL_ID_LIKE.search(value):
                fail(errors, path, f"{case_id}: transcript[{index}] {key} looks like a real snowflake")
        offset = item.get("atOffsetMs")
        if not isinstance(offset, int) or offset < 0:
            fail(errors, path, f"{case_id}: transcript[{index}] atOffsetMs must be a non-negative integer")
        message_id = string_field(item, "messageId")
        if message_id:
            if message_id in message_ids:
                fail(errors, path, f"{case_id}: duplicate transcript messageId {message_id}")
            message_ids.add(message_id)

    prediction = as_mapping(case.get("prediction"))
    if prediction is None:
        fail(errors, path, f"{case_id}: prediction must be a mapping")
        return
    action = string_field(prediction, "action")
    if action not in ALLOWED_ACTIONS:
        fail(errors, path, f"{case_id}: prediction.action must be one of {sorted(ALLOWED_ACTIONS)}")
        return
    prediction_at = prediction.get("atOffsetMs")
    if not isinstance(prediction_at, int) or prediction_at < 0:
        fail(errors, path, f"{case_id}: prediction.atOffsetMs must be a non-negative integer")
        return
    target = prediction.get("targetMessageId")
    if target is not None and target not in message_ids:
        fail(errors, path, f"{case_id}: prediction.targetMessageId {target} not found in transcript")

    observation = as_mapping(case.get("observation"))
    human_actions = as_list(observation.get("humanActions") if observation else None)
    if human_actions is None:
        fail(errors, path, f"{case_id}: observation.humanActions must be a list")
        return
    human_action_offsets: list[int] = []
    for index, event in enumerate(human_actions):
        item = as_mapping(event)
        if item is None:
            fail(errors, path, f"{case_id}: humanActions[{index}] must be a mapping")
            continue
        offset = item.get("atOffsetMs")
        if not isinstance(offset, int) or offset < prediction_at:
            fail(errors, path, f"{case_id}: humanActions[{index}] must be at or after prediction")
            continue
        human_action_offsets.append(offset)
        target_message_id = item.get("targetMessageId")
        if target_message_id is not None and target_message_id not in message_ids:
            fail(errors, path, f"{case_id}: humanActions[{index}] targetMessageId {target_message_id} not in transcript")
        for key in ("actorId", "type"):
            if string_field(item, key) is None:
                fail(errors, path, f"{case_id}: humanActions[{index}] missing {key}")

    expected = as_mapping(case.get("expected"))
    expected_proxy = string_field(expected or {}, "proxy")
    if expected_proxy not in ALLOWED_PROXIES:
        fail(errors, path, f"{case_id}: expected.proxy must be one of {sorted(ALLOWED_PROXIES)}")
        return
    if expected_proxy != "none" and expected_proxy != eval_set_id:
        fail(errors, path, f"{case_id}: expected.proxy {expected_proxy} does not belong in evalSetId {eval_set_id}")

    computed = compute_proxy(action, prediction_at, human_action_offsets)
    if expected_proxy != computed:
        fail(errors, path, f"{case_id}: expected.proxy={expected_proxy}, computed={computed}")
    if expected_proxy == eval_set_id:
        positive_count[eval_set_id] = positive_count.get(eval_set_id, 0) + 1
    if expected_proxy == "none":
        negative_count[eval_set_id] = negative_count.get(eval_set_id, 0) + 1


def compute_proxy(action: str, prediction_at: int, human_action_offsets: list[int]) -> str:
    immediate = any(offset <= prediction_at + IMMEDIATE_WINDOW_MS for offset in human_action_offsets)
    any_window = any(offset <= prediction_at + MAX_WINDOW_MS for offset in human_action_offsets)
    if action == "SPEAK" and immediate:
        return "false_interruption"
    if action in {"IGNORE", "WAIT"} and any_window:
        return "missed_intervention"
    return "none"


def validate_file(path: Path, seen_case_ids: set[str], errors: list[str]) -> tuple[str | None, set[str], dict[str, int], dict[str, int]]:
    try:
        data = load_fixture(path)
    except (OSError, ValueError) as exc:
        fail(errors, path, f"load error: {exc}")
        return None, set(), {}, {}
    root = as_mapping(data)
    if root is None:
        fail(errors, path, "root must be a mapping")
        return None, set(), {}, {}
    if root.get("schemaVersion") != SCHEMA_VERSION:
        fail(errors, path, f"schemaVersion must be {SCHEMA_VERSION}")
    eval_set_id = string_field(root, "evalSetId")
    if eval_set_id not in REQUIRED_TAGS:
        fail(errors, path, f"evalSetId must be one of {sorted(REQUIRED_TAGS)}")
        return None, set(), {}, {}
    if root.get("syntheticOnly") is not True:
        fail(errors, path, "syntheticOnly must be true")
    windows = root.get("counterfactualWindowsMs")
    if windows != [3_000, 10_000, 30_000, 120_000]:
        fail(errors, path, "counterfactualWindowsMs must be [3000, 10000, 30000, 120000]")
    coverage = as_mapping(root.get("coverage"))
    declared_required = set(as_list(coverage.get("requiredTags") if coverage else None) or [])
    if declared_required != REQUIRED_TAGS[eval_set_id]:
        fail(errors, path, f"coverage.requiredTags must be {sorted(REQUIRED_TAGS[eval_set_id])}")

    covered_tags: set[str] = set()
    positive_count: dict[str, int] = {}
    negative_count: dict[str, int] = {}
    cases = as_list(root.get("cases"))
    if not cases:
        fail(errors, path, "cases must be a non-empty list")
        return eval_set_id, covered_tags, positive_count, negative_count
    for case in cases:
        item = as_mapping(case)
        if item is None:
            fail(errors, path, "case must be a mapping")
            continue
        validate_case(
            path=path,
            eval_set_id=eval_set_id,
            case=item,
            seen_case_ids=seen_case_ids,
            covered_tags=covered_tags,
            positive_count=positive_count,
            negative_count=negative_count,
            errors=errors,
        )
    missing_tags = REQUIRED_TAGS[eval_set_id] - covered_tags
    if missing_tags:
        fail(errors, path, f"missing required coverage tags: {sorted(missing_tags)}")
    return eval_set_id, covered_tags, positive_count, negative_count


def load_fixture(path: Path) -> Any:
    text = path.read_text(encoding="utf-8")
    try:
        return json.loads(text)
    except json.JSONDecodeError:
        try:
            import yaml  # type: ignore[import-not-found]
        except ModuleNotFoundError as exc:
            raise ValueError("fixture is not JSON-compatible YAML and PyYAML is unavailable") from exc
        return yaml.safe_load(text)


def main() -> int:
    paths = sorted(EVAL_DIR.glob("*.yaml"))
    if not paths:
        print(f"INVALID: no intervention eval fixtures under {EVAL_DIR.relative_to(REPO)}")
        return 1

    errors: list[str] = []
    seen_case_ids: set[str] = set()
    seen_eval_sets: set[str] = set()
    positives: dict[str, int] = {}
    negatives: dict[str, int] = {}

    for path in paths:
        eval_set_id, _tags, positive_count, negative_count = validate_file(path, seen_case_ids, errors)
        if eval_set_id:
            if eval_set_id in seen_eval_sets:
                fail(errors, path, f"duplicate evalSetId {eval_set_id}")
            seen_eval_sets.add(eval_set_id)
            positives[eval_set_id] = positives.get(eval_set_id, 0) + positive_count.get(eval_set_id, 0)
            negatives[eval_set_id] = negatives.get(eval_set_id, 0) + negative_count.get(eval_set_id, 0)

    missing_sets = set(REQUIRED_TAGS) - seen_eval_sets
    if missing_sets:
        errors.append(f"missing eval sets: {sorted(missing_sets)}")
    for eval_set_id in REQUIRED_TAGS:
        if positives.get(eval_set_id, 0) < len(REQUIRED_TAGS[eval_set_id]):
            errors.append(f"{eval_set_id}: expected at least {len(REQUIRED_TAGS[eval_set_id])} positive cases")
        if negatives.get(eval_set_id, 0) < 1:
            errors.append(f"{eval_set_id}: expected at least one negative control case")

    if errors:
        print("INVALID NEXA intervention eval fixtures")
        for error in errors:
            print(f"- {error}")
        return 1

    print(
        "NEXA intervention eval fixtures OK: "
        f"{len(paths)} files, {len(seen_case_ids)} cases, "
        f"missed_intervention positives={positives.get('missed_intervention', 0)}, "
        f"false_interruption positives={positives.get('false_interruption', 0)}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
