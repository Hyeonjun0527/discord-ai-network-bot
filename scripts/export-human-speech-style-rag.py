#!/usr/bin/env python3
"""Export the private human-review card set into the Speech-only runtime JSONL contract.

The source cards remain the private curation SSOT. This exporter deliberately omits original paths, message IDs,
ordinals, and provenance traces: the runtime needs only minimally generalized bubbles, response-style metadata, and a
non-reversible source fingerprint. It strips parser event/media metadata, normalizes machine speaker labels, and drops
cards left without usable context or a usable human response. It never prints dialogue text.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import stat
import sys
from collections import Counter
from pathlib import Path
from typing import Any


IMPORT_SCHEMA = "nia-human-speech-style-import-card.v1"
SOURCE_SCHEMA = "nia-human-speech-card-preview.v1"
QUALITY = "USER_AUTHORIZED_CANDIDATE"
CONSENT_REVISION = "2026-08-04-user-authorized-candidate"
ALLOWED_DECISIONS = {"STYLE_ONLY", "USE"}
ALLOWED_MODES = {
    "REACTION",
    "ALIGNMENT",
    "PLAY",
    "FOLLOW_UP",
    "SPECULATION",
    "CARE",
    "COORDINATION",
}
DEFAULT_SOURCE_ROOTS = (
    Path("data/private/nia-human-dialogue/2026-08-01-manual-v2/curation/month-plans"),
    Path("data/private/nia-human-dialogue/2026-08-01-manual-v2/curation/source-month-plans"),
)
DEFAULT_INPUT_MANIFEST = Path("data/private/nia-human-dialogue/2026-08-01-manual-v2/manifest.json")
CARD_FILENAME = re.compile(r"card-[0-9]+\.json")
RUNTIME_SPEAKER_ALIASES = {
    "A": "서진",
    "B": "지우",
}
RUNTIME_EVENT_ONLY_LINE = re.compile(
    r"^(?:"
    r"\[(?:외부 링크|내용 없음 또는 만료된 첨부물|(?:GIF|사진|이미지|동영상) 첨부)\]"
    r"|Reacted .+ to your message"
    r"|(?:\u2764\ufe0f?\s*)?참여자\s*[AB](?:\s*\[반응 시점\])?"
    r"|참여자\s*[AB]이\s*\[스토리 공유\]"
    r"|\[반응 시점\]"
    r")$",
)
RUNTIME_INLINE_REACTION = re.compile(r"(?:\u2764\ufe0f?|👍)\s*참여자\s*[AB]\b")
RUNTIME_LEGACY_MARKER = re.compile(
    r"참여자\s*[AB]\b|\[인물\s*\d*\]|\[외부 링크\]|\[내용 없음 또는 만료된 첨부물\]|Reacted .+ to your message",
)


def main() -> int:
    args = parse_args()
    source_roots = [root.resolve() for root in args.source_roots or DEFAULT_SOURCE_ROOTS]
    input_manifest = args.input_manifest.resolve()
    output_dir = args.output_dir.resolve()
    expected_sources = read_expected_sources(input_manifest)
    cards = read_cards(source_roots)
    validate_source_coverage(cards, expected_sources)
    records: list[dict[str, Any]] = []
    excluded_non_dialogue_cards = 0
    for card in cards:
        record = to_runtime_record(card, len(records) + 1)
        if record is None:
            excluded_non_dialogue_cards += 1
        else:
            records.append(record)
    validate_runtime_records(records)

    output_dir.mkdir(mode=0o700, parents=True, exist_ok=True)
    os.chmod(output_dir, 0o700)
    jsonl_path = output_dir / "human-speech-style-cards.jsonl"
    manifest_path = output_dir / "manifest.json"
    write_jsonl(jsonl_path, records)
    digest = sha256_file(jsonl_path)
    write_json(
        manifest_path,
        {
            "schema": "nia-human-speech-style-import-manifest.v1",
            "record_count": len(records),
            "jsonl_sha256": digest,
            "quality": QUALITY,
            "consent_revision": CONSENT_REVISION,
            "response_mode_counts": dict(sorted(Counter(record["response_mode"] for record in records).items())),
            "source_count": len(expected_sources),
            "source_fingerprint_count": len({record["source_fingerprint"] for record in records}),
            "excluded_non_dialogue_card_count": excluded_non_dialogue_cards,
            "input_manifest_sha256": sha256_file(input_manifest),
            "all_expected_sources_present": True,
        },
    )
    print(
        "human-speech-style export complete "
        f"records={len(records)} excluded_non_dialogue={excluded_non_dialogue_cards} "
        f"sha256={digest} modes={dict(sorted(Counter(record['response_mode'] for record in records).items()))}",
    )
    return 0


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--source-root",
        dest="source_roots",
        action="append",
        type=Path,
        help="private card root; repeat to combine roots (defaults to every approved curation root)",
    )
    parser.add_argument(
        "--input-manifest",
        type=Path,
        default=DEFAULT_INPUT_MANIFEST,
        help="private source manifest used to fail closed on missing source coverage",
    )
    parser.add_argument(
        "--output-dir",
        type=Path,
        default=Path("data/private/nia-human-dialogue/2026-08-01-manual-v2/style-rag-runtime-import"),
    )
    return parser.parse_args()


def read_cards(source_roots: list[Path]) -> list[dict[str, Any]]:
    paths: list[Path] = []
    for source_root in source_roots:
        if not source_root.is_dir():
            raise ValueError("private human-review card source directory does not exist")
        paths.extend(path for path in source_root.rglob("card-*.json") if CARD_FILENAME.fullmatch(path.name))
    if not paths:
        raise ValueError("no private human-review cards found")
    cards: list[dict[str, Any]] = []
    card_ids: set[str] = set()
    for path in sorted(set(paths)):
        try:
            with path.open(encoding="utf-8") as handle:
                card = json.load(handle)
        except (OSError, json.JSONDecodeError) as error:
            raise ValueError("private human-review card could not be read") from error
        validate_source_card(card)
        card_id = card["card_id"]
        if card_id in card_ids:
            raise ValueError("private human-review card id is duplicated")
        card_ids.add(card_id)
        cards.append(card)
    return sorted(
        cards,
        key=lambda card: (card["source_id"], card["month"], int(card["preview_sequence"]), card["card_id"]),
    )


def validate_source_card(card: dict[str, Any]) -> None:
    if card.get("schema") != SOURCE_SCHEMA:
        raise ValueError("private human-review card schema is unsupported")
    if card.get("candidate_decision") not in ALLOWED_DECISIONS:
        raise ValueError("private human-review card decision is unsupported")
    if card.get("response_mode") not in ALLOWED_MODES:
        raise ValueError("private human-review card response mode is unsupported")
    if not isinstance(card.get("context_messages"), list) or not isinstance(card.get("actual_human_reply"), list):
        raise ValueError("private human-review card bubbles are missing")
    if not card.get("situation") or not isinstance(card.get("style_signals"), list):
        raise ValueError("private human-review card metadata is missing")
    if not isinstance(card.get("combined_chars"), int) or not 1 <= card["combined_chars"] <= 350:
        raise ValueError("private human-review card size is invalid")
    if not isinstance(card.get("card_id"), str) or not card["card_id"].strip():
        raise ValueError("private human-review card id is missing")
    if not isinstance(card.get("source_id"), str) or not card["source_id"].strip():
        raise ValueError("private human-review card source id is missing")
    if not isinstance(card.get("month"), str) or not re.fullmatch(r"[0-9]{4}-[0-9]{2}", card["month"]):
        raise ValueError("private human-review card month is invalid")
    if not isinstance(card.get("preview_sequence"), int) or card["preview_sequence"] < 1:
        raise ValueError("private human-review card preview sequence is invalid")


def read_expected_sources(path: Path) -> dict[str, str]:
    try:
        with path.open(encoding="utf-8") as handle:
            manifest = json.load(handle)
    except (OSError, json.JSONDecodeError) as error:
        raise ValueError("private source manifest could not be read") from error
    sources = manifest.get("sources")
    if not isinstance(sources, list) or not sources:
        raise ValueError("private source manifest has no sources")
    if manifest.get("source_count") != len(sources):
        raise ValueError("private source manifest source count is invalid")

    source_hashes = {
        source.get("source_id"): source.get("source_sha256")
        for source in sources
        if isinstance(source, dict)
    }
    if (
        len(source_hashes) != len(sources)
        or not all(isinstance(source_id, str) and source_id for source_id in source_hashes)
        or not all(isinstance(source_hash, str) and re.fullmatch(r"[0-9a-f]{64}", source_hash) for source_hash in source_hashes.values())
    ):
        raise ValueError("private source manifest source ids are invalid")
    return source_hashes


def validate_source_coverage(cards: list[dict[str, Any]], expected_sources: dict[str, str]) -> None:
    card_source_ids = {card["source_id"] for card in cards}
    if card_source_ids != set(expected_sources):
        raise ValueError("private human-review card source coverage does not match the input manifest")
    for card in cards:
        source_sha256 = card.get("source_trace", {}).get("source_sha256")
        if source_sha256 != expected_sources[card["source_id"]]:
            raise ValueError("private human-review card source fingerprint does not match the input manifest")


def to_runtime_record(card: dict[str, Any], ordinal: int) -> dict[str, Any] | None:
    source_sha256 = card.get("source_trace", {}).get("source_sha256")
    if not isinstance(source_sha256, str) or len(source_sha256) != 64:
        raise ValueError("private human-review card source fingerprint is missing")
    context_bubbles = [bubble for message in card["context_messages"] if (bubble := to_bubble(message)) is not None]
    response_bubbles = [bubble for message in card["actual_human_reply"] if (bubble := to_bubble(message)) is not None]
    if not context_bubbles or not response_bubbles:
        return None
    return {
        "schema": IMPORT_SCHEMA,
        "example_id": f"human-style-{ordinal:06d}",
        "response_mode": card["response_mode"],
        "situation": normalized_text(card["situation"], "situation", 240),
        "style_signals": [normalized_text(signal, "style signal", 80) for signal in card["style_signals"]],
        "context_bubbles": context_bubbles,
        "response_bubbles": response_bubbles,
        "quality": QUALITY,
        "source_fingerprint": f"sha256:{source_sha256}",
        "consent_revision": CONSENT_REVISION,
        "combined_chars": card["combined_chars"],
        "embedding_model": "text-embedding-3-small",
    }


def to_bubble(message: dict[str, Any]) -> dict[str, str] | None:
    if not isinstance(message, dict):
        raise ValueError("private human-review bubble is invalid")
    text = sanitize_runtime_bubble_text(message.get("text"))
    if not text:
        return None
    return {
        "speaker": normalize_runtime_speaker(message.get("speaker")),
        "text": text,
    }


def normalize_runtime_speaker(value: Any) -> str:
    speaker = normalized_text(value, "speaker", 80)
    return RUNTIME_SPEAKER_ALIASES.get(speaker, speaker)


def sanitize_runtime_bubble_text(value: Any) -> str:
    text = normalized_text(value, "bubble", 350)
    retained_lines: list[str] = []
    for raw_line in text.splitlines():
        line = raw_line.strip()
        if RUNTIME_EVENT_ONLY_LINE.fullmatch(line):
            continue
        line = RUNTIME_INLINE_REACTION.sub("", line).strip()
        if not line or RUNTIME_EVENT_ONLY_LINE.fullmatch(line):
            continue
        retained_lines.append(line)
    return "\n".join(retained_lines)


def validate_runtime_records(records: list[dict[str, Any]]) -> None:
    if not records:
        raise ValueError("no usable private human-review cards remain after runtime sanitization")
    for record in records:
        for bubble in [*record["context_bubbles"], *record["response_bubbles"]]:
            if bubble["speaker"] in RUNTIME_SPEAKER_ALIASES:
                raise ValueError("runtime human-review card has a machine speaker label")
            if RUNTIME_LEGACY_MARKER.search(bubble["text"]):
                raise ValueError("runtime human-review card retains parser metadata")


def normalized_text(value: Any, field: str, maximum: int) -> str:
    if not isinstance(value, str):
        raise ValueError(f"private human-review {field} is invalid")
    text = value.strip()
    if not text or len(text) > maximum:
        raise ValueError(f"private human-review {field} length is invalid")
    return text


def write_jsonl(path: Path, records: list[dict[str, Any]]) -> None:
    temporary_path = path.with_name(f".{path.name}.tmp")
    try:
        with temporary_path.open("w", encoding="utf-8") as handle:
            os.chmod(temporary_path, stat.S_IRUSR | stat.S_IWUSR)
            for record in records:
                handle.write(json.dumps(record, ensure_ascii=False, separators=(",", ":")))
                handle.write("\n")
        os.replace(temporary_path, path)
    finally:
        temporary_path.unlink(missing_ok=True)


def write_json(path: Path, value: dict[str, Any]) -> None:
    temporary_path = path.with_name(f".{path.name}.tmp")
    try:
        with temporary_path.open("w", encoding="utf-8") as handle:
            os.chmod(temporary_path, stat.S_IRUSR | stat.S_IWUSR)
            json.dump(value, handle, ensure_ascii=False, indent=2, sort_keys=True)
            handle.write("\n")
        os.replace(temporary_path, path)
    finally:
        temporary_path.unlink(missing_ok=True)


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except ValueError as error:
        print(f"human-speech-style export failed: {error}", file=sys.stderr)
        raise SystemExit(2)
