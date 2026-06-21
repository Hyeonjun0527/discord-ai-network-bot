#!/usr/bin/env python3
from __future__ import annotations

import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Any

import yaml

REPO_ROOT = Path(__file__).resolve().parents[1]
DEFAULT_FIXTURE_DIR = REPO_ROOT / "test-fixtures" / "nexa" / "conversations"
SCHEMA_PATH = REPO_ROOT / "test-fixtures" / "nexa" / "schemas" / "conversation-fixture.schema.json"
SCHEMA_VERSION = "nexa.conversation-fixture.v1"
EVENT_TYPES = {
    "message_create",
    "message_update",
    "message_delete",
    "typing_start",
    "reaction_add",
    "reaction_remove",
}


@dataclass(frozen=True)
class FixtureResult:
    path: Path
    events: int


def load_mapping(path: Path) -> dict[str, Any]:
    data = yaml.safe_load(path.read_text(encoding="utf-8"))
    if not isinstance(data, dict):
        raise ValueError("fixture root must be a mapping")
    return data


def require_string(value: Any, field: str) -> str:
    if not isinstance(value, str) or not value.strip():
        raise ValueError(f"{field} must be a non-empty string")
    return value


def require_int(value: Any, field: str, minimum: int = 0) -> int:
    if not isinstance(value, int) or value < minimum:
        raise ValueError(f"{field} must be an integer >= {minimum}")
    return value


def actor_labels(actors: list[Any]) -> tuple[set[str], dict[str, str]]:
    actor_ids: set[str] = set()
    label_by_id: dict[str, str] = {}
    labels: set[str] = set()
    for index, actor in enumerate(actors, start=1):
        if not isinstance(actor, dict):
            raise ValueError(f"actors[{index}] must be a mapping")
        actor_id = require_string(actor.get("actorId"), f"actors[{index}].actorId")
        label = require_string(actor.get("label"), f"actors[{index}].label")
        if actor_id in actor_ids:
            raise ValueError(f"duplicate actorId: {actor_id}")
        if label in labels:
            raise ValueError(f"duplicate actor label: {label}")
        actor_ids.add(actor_id)
        labels.add(label)
        label_by_id[actor_id] = label
    return actor_ids, label_by_id


def require_actor(actor_id: Any, field: str, actor_ids: set[str]) -> str:
    value = require_string(actor_id, field)
    if value not in actor_ids:
        raise ValueError(f"{field} references unknown actor: {value}")
    return value


def validate_message_create(event: dict[str, Any], actor_ids: set[str], messages: set[str], transcript: list[tuple[str, str]], label_by_id: dict[str, str]) -> None:
    message_id = require_string(event.get("messageId"), "message_create.messageId")
    if message_id in messages:
        raise ValueError(f"duplicate messageId: {message_id}")
    author_id = require_actor(event.get("authorId"), "message_create.authorId", actor_ids)
    content = event.get("content")
    if not isinstance(content, str):
        raise ValueError("message_create.content must be a string")
    messages.add(message_id)
    transcript.append((label_by_id[author_id], content))


def validate_message_update(event: dict[str, Any], actor_ids: set[str], messages: set[str]) -> None:
    message_id = require_string(event.get("messageId"), "message_update.messageId")
    if message_id not in messages:
        raise ValueError(f"message_update references unknown message: {message_id}")
    require_actor(event.get("editorId"), "message_update.editorId", actor_ids)
    if not isinstance(event.get("content"), str):
        raise ValueError("message_update.content must be a string")


def validate_message_delete(event: dict[str, Any], actor_ids: set[str], messages: set[str], deleted: set[str]) -> None:
    message_id = require_string(event.get("messageId"), "message_delete.messageId")
    if message_id not in messages:
        raise ValueError(f"message_delete references unknown message: {message_id}")
    if message_id in deleted:
        raise ValueError(f"message deleted more than once: {message_id}")
    deleted_by = event.get("deletedByActorId")
    if deleted_by is not None:
        require_actor(deleted_by, "message_delete.deletedByActorId", actor_ids)
    deleted.add(message_id)


def validate_reaction(event: dict[str, Any], actor_ids: set[str], messages: set[str]) -> None:
    message_id = require_string(event.get("messageId"), f"{event['type']}.messageId")
    if message_id not in messages:
        raise ValueError(f"{event['type']} references unknown message: {message_id}")
    require_actor(event.get("actorId"), f"{event['type']}.actorId", actor_ids)
    emoji = event.get("emoji")
    if not isinstance(emoji, dict):
        raise ValueError(f"{event['type']}.emoji must be a mapping")
    require_string(emoji.get("kind"), f"{event['type']}.emoji.kind")
    require_string(emoji.get("value"), f"{event['type']}.emoji.value")


def validate_expected_transcript(data: dict[str, Any], actual: list[tuple[str, str]]) -> None:
    expected = data.get("expected") or {}
    source_transcript = expected.get("sourceTranscript") if isinstance(expected, dict) else None
    if source_transcript is None:
        return
    if not isinstance(source_transcript, list):
        raise ValueError("expected.sourceTranscript must be a list")
    expected_lines: list[tuple[str, str]] = []
    for index, line in enumerate(source_transcript, start=1):
        if not isinstance(line, dict):
            raise ValueError(f"expected.sourceTranscript[{index}] must be a mapping")
        expected_lines.append((
            require_string(line.get("speakerLabel"), f"expected.sourceTranscript[{index}].speakerLabel"),
            line.get("text") if isinstance(line.get("text"), str) else None,
        ))
    if any(text is None for _, text in expected_lines):
        raise ValueError("expected.sourceTranscript.text must be a string")
    if expected_lines != actual:
        raise ValueError("expected.sourceTranscript does not match message_create order/content")


def validate_fixture(path: Path) -> FixtureResult:
    data = load_mapping(path)
    if data.get("schemaVersion") != SCHEMA_VERSION:
        raise ValueError(f"schemaVersion must be {SCHEMA_VERSION}")
    require_string(data.get("fixtureId"), "fixtureId")
    actors = data.get("actors")
    if not isinstance(actors, list) or not actors:
        raise ValueError("actors must be a non-empty list")
    actor_ids, label_by_id = actor_labels(actors)
    events = data.get("events")
    if not isinstance(events, list) or not events:
        raise ValueError("events must be a non-empty list")

    event_ids: set[str] = set()
    messages: set[str] = set()
    deleted: set[str] = set()
    transcript: list[tuple[str, str]] = []
    previous_seq = 0
    previous_offset = -1

    for index, event in enumerate(events, start=1):
        if not isinstance(event, dict):
            raise ValueError(f"events[{index}] must be a mapping")
        seq = require_int(event.get("seq"), f"events[{index}].seq", 1)
        if seq <= previous_seq:
            raise ValueError(f"events[{index}].seq must be strictly increasing")
        previous_seq = seq
        offset = require_int(event.get("atOffsetMs"), f"events[{index}].atOffsetMs")
        if offset < previous_offset:
            raise ValueError(f"events[{index}].atOffsetMs must be non-decreasing")
        previous_offset = offset
        event_id = require_string(event.get("eventId"), f"events[{index}].eventId")
        if event_id in event_ids:
            raise ValueError(f"duplicate eventId: {event_id}")
        event_ids.add(event_id)
        event_type = require_string(event.get("type"), f"events[{index}].type")
        if event_type not in EVENT_TYPES:
            raise ValueError(f"events[{index}].type is not supported: {event_type}")

        if event_type == "message_create":
            validate_message_create(event, actor_ids, messages, transcript, label_by_id)
        elif event_type == "message_update":
            validate_message_update(event, actor_ids, messages)
        elif event_type == "message_delete":
            validate_message_delete(event, actor_ids, messages, deleted)
        elif event_type == "typing_start":
            require_actor(event.get("actorId"), "typing_start.actorId", actor_ids)
        else:
            validate_reaction(event, actor_ids, messages)

    validate_expected_transcript(data, transcript)
    return FixtureResult(path=path, events=len(events))


def fixture_paths(args: list[str]) -> list[Path]:
    if args:
        return [Path(arg) for arg in args]
    return sorted(DEFAULT_FIXTURE_DIR.glob("*.yaml"))


def main() -> int:
    if not SCHEMA_PATH.exists():
        print(f"INVALID: missing schema {SCHEMA_PATH.relative_to(REPO_ROOT)}")
        return 1
    paths = fixture_paths(sys.argv[1:])
    if not paths:
        print("INVALID: no conversation fixtures found")
        return 1
    results: list[FixtureResult] = []
    errors: list[str] = []
    for path in paths:
        try:
            results.append(validate_fixture(path))
        except (OSError, ValueError, yaml.YAMLError) as exc:
            display = path.relative_to(REPO_ROOT) if path.is_absolute() and path.is_relative_to(REPO_ROOT) else path
            errors.append(f"{display}: {exc}")
    if errors:
        print("INVALID conversation fixtures")
        for error in errors:
            print(f"- {error}")
        return 1
    event_count = sum(result.events for result in results)
    print(f"conversation fixtures OK: {len(results)} files, {event_count} events")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
