#!/usr/bin/env python3
"""Deterministic NIA few-shot seed evaluation.

This gate validates the admin-managed few-shot constitution without production
data or network calls. It reports example ids and metric names only; raw message
text is intentionally never printed.
"""
from __future__ import annotations

import argparse
import re
import sys
from collections import Counter
from dataclasses import dataclass
from pathlib import Path
from typing import Any

import yaml

REPO_ROOT = Path(__file__).resolve().parents[1]
DEFAULT_FIXTURE = REPO_ROOT / "test-fixtures" / "nexa" / "quality" / "nia-fewshot-seed.yaml"
DEFAULT_REPORT = REPO_ROOT / "docs" / "nexa" / "quality" / "nia-judge-report.md"
SCHEMA_VERSION = "nia.fewshot-seed.v1"
ALLOWED_ACTIONS = {"IGNORE", "WAIT", "REACT", "SPEAK", "CANCEL"}
ALLOWED_PRIVACY = {"SYNTHETIC", "ANONYMIZED"}
EXPECTED_COUNTS = {"SPEAK": 10, "WAIT": 9, "REACT": 6, "IGNORE": 10, "CANCEL": 5}
EXPECTED_TOTAL = 40
EXPECTED_HARD_AMBIGUOUS = 7
REQUIRED_TAGS = {"over-talk-risk", "missed-reply-risk", "stale-memory-override", "hard-ambiguous"}
PRODUCTION_SHAPED_PATTERNS = {
    "discord_snowflake": re.compile(r"\b\d{17,20}\b"),
    "discord_user_mention": re.compile(r"<@!?\d+>"),
    "discord_channel_mention": re.compile(r"<#\d+>"),
    "discord_message_url": re.compile(r"https?://(?:canary\.|ptb\.)?discord(?:app)?\.com/channels/\S+"),
}


@dataclass(frozen=True)
class EvalFailure:
    metric: str
    example_id: str | None
    detail: str


@dataclass(frozen=True)
class EvalReport:
    fixture_path: Path
    total: int
    action_counts: Counter[str]
    hard_ambiguous_count: int
    failures: list[EvalFailure]

    @property
    def status(self) -> str:
        return "PASS" if not self.failures else "FAIL"


def load_mapping(path: Path) -> dict[str, Any]:
    data = yaml.safe_load(path.read_text(encoding="utf-8"))
    if not isinstance(data, dict):
        raise ValueError("fixture root must be a mapping")
    return data


def require_string(value: Any, field: str) -> str:
    if not isinstance(value, str) or not value.strip():
        raise ValueError(f"{field} must be a non-empty string")
    return value


def require_list(value: Any, field: str) -> list[Any]:
    if not isinstance(value, list):
        raise ValueError(f"{field} must be a list")
    return value


def example_id(example: dict[str, Any], index: int) -> str:
    raw = example.get("id")
    if isinstance(raw, str) and raw.strip():
        return raw.strip()
    return f"example[{index}]"


def add_failure(
    failures: list[EvalFailure],
    metric: str,
    detail: str,
    example: dict[str, Any] | None = None,
    index: int = 0,
) -> None:
    failures.append(
        EvalFailure(
            metric=metric,
            example_id=example_id(example, index) if example is not None else None,
            detail=detail,
        ),
    )


def validate_no_production_shape(
    failures: list[EvalFailure],
    example: dict[str, Any],
    index: int,
) -> None:
    privacy = str(example.get("privacyClass", "")).upper()
    if privacy not in ALLOWED_PRIVACY:
        add_failure(failures, "privacy", f"privacyClass must be one of {sorted(ALLOWED_PRIVACY)}", example, index)

    for message in require_list(example.get("rawMessages"), f"{example_id(example, index)}.rawMessages"):
        if not isinstance(message, dict):
            add_failure(failures, "privacy", "rawMessages entry must be a mapping", example, index)
            continue
        text = message.get("text")
        if not isinstance(text, str) or not text.strip():
            add_failure(failures, "privacy", "raw message text is blank", example, index)
            continue
        for pattern_name, pattern in PRODUCTION_SHAPED_PATTERNS.items():
            if pattern.search(text):
                add_failure(failures, "privacy", f"raw text matches production-shaped pattern {pattern_name}", example, index)


def validate_example_contract(
    failures: list[EvalFailure],
    example: dict[str, Any],
    index: int,
) -> str | None:
    action = str(example.get("expectedAction", "")).upper()
    if action not in ALLOWED_ACTIONS:
        add_failure(failures, "action_correctness", f"unknown expectedAction {action!r}", example, index)
        return None

    messages = require_list(example.get("rawMessages"), f"{example_id(example, index)}.rawMessages")
    refs: set[str] = set()
    for message_index, message in enumerate(messages, start=1):
        if not isinstance(message, dict):
            add_failure(failures, "action_correctness", f"rawMessages[{message_index}] must be a mapping", example, index)
            continue
        ref = require_string(message.get("ref"), f"{example_id(example, index)}.rawMessages[{message_index}].ref")
        refs.add(ref)
        require_string(message.get("authorRole"), f"{example_id(example, index)}.rawMessages[{message_index}].authorRole")
        if not isinstance(message.get("offsetMs"), int):
            add_failure(failures, "action_correctness", f"rawMessages[{message_index}].offsetMs must be an integer", example, index)

    evidence_refs = example.get("evidenceRefs")
    if not isinstance(evidence_refs, list) or not evidence_refs:
        add_failure(failures, "action_correctness", "evidenceRefs must be a non-empty list", example, index)
    else:
        missing = sorted(set(evidence_refs) - refs)
        if missing:
            add_failure(failures, "action_correctness", f"evidenceRefs do not exist: {','.join(missing)}", example, index)

    bad = example.get("badAlternative")
    if not isinstance(bad, dict):
        add_failure(failures, "ambiguous_contrast", "badAlternative must be a mapping", example, index)
    else:
        bad_action = str(bad.get("action", "")).upper()
        why_bad = bad.get("whyBad")
        if bad_action not in ALLOWED_ACTIONS:
            add_failure(failures, "ambiguous_contrast", f"unknown badAlternative action {bad_action!r}", example, index)
        if bad_action == action:
            add_failure(failures, "ambiguous_contrast", "badAlternative action must differ from expectedAction", example, index)
        if not isinstance(why_bad, str) or not why_bad.strip():
            add_failure(failures, "ambiguous_contrast", "badAlternative.whyBad is blank", example, index)

    if not isinstance(example.get("reason"), str) or not example.get("reason", "").strip():
        add_failure(failures, "action_correctness", "reason is blank", example, index)
    return action


def validate_metric_tags(
    failures: list[EvalFailure],
    example: dict[str, Any],
    index: int,
    action: str | None,
) -> None:
    tags = set(example.get("tags") or [])
    if "over-talk-risk" in tags and action == "SPEAK":
        add_failure(failures, "over_talk", "over-talk-risk example expects SPEAK", example, index)
    if "missed-reply-risk" in tags and action != "SPEAK":
        add_failure(failures, "under_talk", "missed-reply-risk example does not expect SPEAK", example, index)
    if "hard-ambiguous" in tags:
        bad = example.get("badAlternative") if isinstance(example.get("badAlternative"), dict) else {}
        if not bad.get("whyBad"):
            add_failure(failures, "ambiguous_contrast", "hard-ambiguous example lacks whyBad", example, index)
    if "stale-memory-override" in tags:
        messages = [m for m in example.get("rawMessages", []) if isinstance(m, dict) and isinstance(m.get("offsetMs"), int)]
        latest_refs = {str(m.get("ref")) for m in messages if m.get("offsetMs") == max((x.get("offsetMs") for x in messages), default=0)}
        evidence_refs = {str(ref) for ref in example.get("evidenceRefs", [])}
        if latest_refs and evidence_refs.isdisjoint(latest_refs):
            add_failure(failures, "stale_memory_override", "latest raw message is not cited as evidence", example, index)


def evaluate_fixture(path: Path) -> EvalReport:
    data = load_mapping(path)
    failures: list[EvalFailure] = []

    if data.get("schemaVersion") != SCHEMA_VERSION:
        add_failure(failures, "schema", f"schemaVersion must be {SCHEMA_VERSION}")
    if data.get("source", {}).get("productionRawText") is not False:
        add_failure(failures, "privacy", "source.productionRawText must be false")

    examples = require_list(data.get("examples"), "examples")
    ids = [example_id(example, index) for index, example in enumerate(examples, start=1) if isinstance(example, dict)]
    duplicate_ids = [item for item, count in Counter(ids).items() if count > 1]
    if duplicate_ids:
        add_failure(failures, "schema", f"duplicate example ids: {','.join(sorted(duplicate_ids))}")

    actions: Counter[str] = Counter()
    hard_ambiguous_count = 0
    for index, example in enumerate(examples, start=1):
        if not isinstance(example, dict):
            add_failure(failures, "schema", "example must be a mapping", index=index)
            continue
        action = validate_example_contract(failures, example, index)
        validate_no_production_shape(failures, example, index)
        validate_metric_tags(failures, example, index, action)
        if action is not None:
            actions[action] += 1
        if "hard-ambiguous" in set(example.get("tags") or []):
            hard_ambiguous_count += 1

    if len(examples) != EXPECTED_TOTAL:
        add_failure(failures, "composition", f"expected {EXPECTED_TOTAL} examples, got {len(examples)}")
    for action, expected in EXPECTED_COUNTS.items():
        actual = actions[action]
        if actual != expected:
            add_failure(failures, "composition", f"{action} expected {expected}, got {actual}")
    if hard_ambiguous_count != EXPECTED_HARD_AMBIGUOUS:
        add_failure(failures, "ambiguous_contrast", f"hard-ambiguous expected {EXPECTED_HARD_AMBIGUOUS}, got {hard_ambiguous_count}")
    all_tags = {tag for example in examples if isinstance(example, dict) for tag in (example.get("tags") or [])}
    for tag in sorted(REQUIRED_TAGS - all_tags):
        add_failure(failures, "coverage", f"required tag is absent: {tag}")

    return EvalReport(
        fixture_path=path,
        total=len(examples),
        action_counts=actions,
        hard_ambiguous_count=hard_ambiguous_count,
        failures=failures,
    )


def report_lines(report: EvalReport) -> list[str]:
    fixture = report.fixture_path.relative_to(REPO_ROOT) if report.fixture_path.is_relative_to(REPO_ROOT) else report.fixture_path
    lines = [
        "# NIA Judge Few-Shot Seed Report",
        "",
        f"- fixture: `{fixture}`",
        f"- status: **{report.status}**",
        f"- totalExamples: {report.total}",
        f"- hardAmbiguousExamples: {report.hard_ambiguous_count}",
        "",
        "## Action Coverage",
        "",
        "| Action | Count | Expected |",
        "| --- | ---: | ---: |",
    ]
    for action, expected in EXPECTED_COUNTS.items():
        lines.append(f"| {action} | {report.action_counts[action]} | {expected} |")
    lines += [
        "",
        "## Metric Gates",
        "",
        "| Metric | Status | Failed Example IDs |",
        "| --- | --- | --- |",
    ]
    for metric in (
        "action_correctness",
        "over_talk",
        "under_talk",
        "stale_memory_override",
        "ambiguous_contrast",
        "privacy",
        "composition",
        "coverage",
        "schema",
    ):
        failed_ids = sorted({f.example_id or "-" for f in report.failures if f.metric == metric})
        lines.append(f"| {metric} | {'FAIL' if failed_ids else 'PASS'} | {', '.join(failed_ids) if failed_ids else '-'} |")
    if report.failures:
        lines += ["", "## Failures", ""]
        for failure in report.failures:
            prefix = f"- `{failure.metric}`"
            if failure.example_id:
                prefix += f" `{failure.example_id}`"
            lines.append(f"{prefix}: {failure.detail}")
    else:
        lines += ["", "## Failures", "", "- none"]
    lines.append("")
    return lines


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--fixtures", default=str(DEFAULT_FIXTURE))
    parser.add_argument("--out", default="")
    args = parser.parse_args()

    fixture = Path(args.fixtures)
    if not fixture.is_absolute():
        fixture = REPO_ROOT / fixture
    out = Path(args.out) if args.out else None
    if out is not None and not out.is_absolute():
        out = REPO_ROOT / out

    try:
        report = evaluate_fixture(fixture)
    except (OSError, ValueError, yaml.YAMLError) as exc:
        print(f"INVALID NIA judge seed fixture: {exc}", file=sys.stderr)
        return 1

    lines = report_lines(report)
    if out is not None:
        out.parent.mkdir(parents=True, exist_ok=True)
        out.write_text("\n".join(lines), encoding="utf-8")
    print(f"NIA judge seed eval {report.status}: total={report.total}, failures={len(report.failures)}")
    if report.failures:
        for failure in report.failures:
            target = f" {failure.example_id}" if failure.example_id else ""
            print(f"- {failure.metric}{target}: {failure.detail}")
    return 0 if report.status == "PASS" else 1


if __name__ == "__main__":
    raise SystemExit(main())
